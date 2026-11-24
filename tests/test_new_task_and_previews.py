"""Tests for remote new-session creation, verified project IDs, and dev port previews."""
from __future__ import annotations

import json
from pathlib import Path
import queue
import secrets
import threading
import time
from types import SimpleNamespace
import uuid

from fastapi.testclient import TestClient

from agent_monitor import collector, local_executor as le
from agent_monitor.dev_previews import (
    classify_bind_address,
    filter_dev_port_candidates,
    valid_preview_host,
)
from agent_monitor.native_access import challenge_for
from agent_monitor.server import create_app
from agent_monitor.store import iso
from agent_monitor.task_results import resolve_local_project

NEW_UUID = "22222222-3333-4444-8555-666666666666"


class _Pipe:
    def __init__(self):
        self.items: queue.Queue = queue.Queue()

    def put(self, value):
        data = value if isinstance(value, bytes) else (json.dumps(value) + "\n").encode("utf-8")
        self.items.put(data)

    def readline(self, limit=-1):
        chunk = self.items.get()
        return chunk if limit < 0 else chunk[:limit]

    def close(self):
        self.items.put(b"")


class _Stdin:
    def __init__(self, owner):
        self.owner = owner
        self.buffer = bytearray()

    def write(self, data):
        self.buffer.extend(data)
        while b"\n" in self.buffer:
            idx = self.buffer.index(b"\n")
            raw = bytes(self.buffer[:idx])
            del self.buffer[: idx + 1]
            if raw:
                self.owner.on_line(json.loads(raw.decode("utf-8")))
        return len(data)

    def flush(self):
        pass

    def close(self):
        pass


class _FakeNewChild:
    def __init__(self, argv, cwd, *, tool: str, ask_approval: bool = False):
        self.argv = list(argv)
        self.cwd = str(cwd)
        self.tool = tool
        self.ask_approval = ask_approval
        self.requests: list[dict] = []
        self.proc = SimpleNamespace(stdout=_Pipe(), stderr=_Pipe())
        self.proc.stdin = _Stdin(self)
        self.profile_id = ""
        for item in self.argv:
            if item.startswith("default_permissions="):
                self.profile_id = json.loads(item.split("=", 1)[1])

    def on_line(self, msg: dict):
        self.requests.append(msg)
        if self.tool == "codex":
            method = msg.get("method")
            if method == "initialize":
                self.proc.stdout.put({"id": msg["id"], "result": {}})
            elif method == "thread/start":
                self.proc.stdout.put({
                    "id": msg["id"],
                    "result": {
                        "thread": {
                            "id": NEW_UUID,
                            "status": {"type": "idle"},
                            "environments": [{
                                "environmentId": "local",
                                "cwd": self.cwd,
                                "runtimeWorkspaceRoots": [self.cwd],
                            }],
                        },
                        "cwd": self.cwd,
                        "model": "gpt-5.4",
                        "reasoningEffort": "high",
                        "activePermissionProfile": {"id": self.profile_id, "extends": None},
                        "approvalPolicy": "untrusted",
                        "approvalsReviewer": "user",
                        "sandbox": {"type": "workspaceWrite", "networkAccess": False},
                    },
                })
            elif method == "turn/start":
                self.proc.stdout.put({"id": msg["id"], "result": {"turn": {"id": "turn-new-1", "status": "inProgress"}}})
                if self.ask_approval:
                    self.proc.stdout.put({
                        "id": "req-1",
                        "method": "item/commandExecution/requestApproval",
                        "params": {
                            "threadId": NEW_UUID,
                            "turnId": "turn-new-1",
                            "itemId": "item-1",
                            "environmentId": "local",
                            "cwd": self.cwd,
                            "command": "npm test",
                        },
                    })
                else:
                    self.proc.stdout.put({
                        "method": "turn/completed",
                        "params": {"threadId": NEW_UUID, "turn": {"id": "turn-new-1", "status": "completed", "error": None}},
                    })
            elif msg.get("id") == "req-1":
                self.proc.stdout.put({
                    "method": "turn/completed",
                    "params": {"threadId": NEW_UUID, "turn": {"id": "turn-new-1", "status": "completed", "error": None}},
                })
        elif self.tool == "claude":
            if msg.get("type") == "control_request" and msg.get("request", {}).get("subtype") == "initialize":
                self.proc.stdout.put({
                    "type": "control_response",
                    "response": {"subtype": "success", "request_id": msg["request_id"], "response": {"models": []}},
                })
            elif msg.get("type") == "user":
                sid = self.argv[self.argv.index("--session-id") + 1]
                self.proc.stdout.put({
                    "type": "system",
                    "subtype": "init",
                    "session_id": sid,
                    "permissionMode": "default",
                    "model": "claude-sonnet-4-6",
                })
                self.proc.stdout.put({
                    "type": "result",
                    "subtype": "success",
                    "is_error": False,
                    "session_id": sid,
                    "permission_denials": [],
                })
        elif self.tool == "antigravity":
            method = msg.get("method")
            if method == "initialize":
                self.proc.stdout.put({"jsonrpc": "2.0", "id": msg["id"], "result": {}})
            elif method == "session/new":
                self.proc.stdout.put({
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "result": {
                        "sessionId": NEW_UUID,
                        "modes": {"currentModeId": "default"},
                        "configOptions": [],
                    },
                })
            elif method == "session/prompt":
                self.proc.stdout.put({"jsonrpc": "2.0", "id": msg["id"], "result": {"stopReason": "end_turn"}})
            elif method == "session/close":
                self.proc.stdout.put({"jsonrpc": "2.0", "id": msg["id"], "result": {}})

    def close(self, *, graceful=False):
        self.proc.stdout.close()
        self.proc.stderr.close()


def test_dev_preview_filtering_and_host_validation():
    assert valid_preview_host("100.88.12.34") == "100.88.12.34"
    assert valid_preview_host("192.168.1.42") == "192.168.1.42"
    assert valid_preview_host("workstation.example-tailnet.ts.net.") == "workstation.example-tailnet.ts.net"
    assert valid_preview_host("127.0.0.1") == ""
    assert valid_preview_host("8.8.8.8") == ""
    assert valid_preview_host("public.example.com") == ""

    assert classify_bind_address("0.0.0.0") == "network"
    assert classify_bind_address("[::]") == "network"
    assert classify_bind_address("100.90.1.2") == "network"
    assert classify_bind_address("192.168.10.5") == "network"
    assert classify_bind_address("127.0.0.1") == "loopback"
    assert classify_bind_address("[::1]") == "loopback"

    proj_id = "a" * 24
    filtered = filter_dev_port_candidates([
        {"port": 5173, "bind_scope": "network", "process": "node.exe", "project_id": proj_id},
        {"port": 3000, "bind_scope": "loopback", "process": "bun", "project_id": ""},
        {"port": 7890, "bind_scope": "loopback", "process": "clash", "project_id": ""},
        {"port": 8766, "bind_scope": "network", "process": "python", "project_id": proj_id},
        {"port": 5432, "bind_scope": "loopback", "process": "postgres", "project_id": ""},
        {"port": 55123, "bind_scope": "loopback", "process": "code.exe", "project_id": ""},
    ])
    assert [item["port"] for item in filtered] == [5173, 3000]
    assert filtered[0]["bind_scope"] == "network"
    assert filtered[0]["project_id"] == proj_id
    assert filtered[1]["bind_scope"] == "loopback"


def test_collector_projects_and_local_project_resolution(tmp_path):
    codex_home = tmp_path / "codex"
    claude_home = tmp_path / "claude"
    sessions = codex_home / "sessions" / "2026" / "11" / "24"
    sessions.mkdir(parents=True)
    proj_a = tmp_path / "team_alpha" / "webapp"
    proj_b = tmp_path / "team_beta" / "webapp"
    unindexed = tmp_path / "secret_dir"
    proj_a.mkdir(parents=True)
    proj_b.mkdir(parents=True)
    unindexed.mkdir(parents=True)

    s1 = str(uuid.uuid4())
    s2 = str(uuid.uuid4())
    (sessions / f"rollout-1-{s1}.jsonl").write_text(
        json.dumps({"type": "session_meta", "payload": {"id": s1, "cwd": str(proj_a)}}) + "\n"
        + json.dumps({"timestamp": "2026-11-24T10:00:00Z", "type": "event_msg", "payload": {"type": "task_complete"}}) + "\n",
        encoding="utf-8",
    )
    (sessions / f"rollout-2-{s2}.jsonl").write_text(
        json.dumps({"type": "session_meta", "payload": {"id": s2, "cwd": str(proj_b)}}) + "\n"
        + json.dumps({"timestamp": "2026-11-24T10:05:00Z", "type": "event_msg", "payload": {"type": "task_complete"}}) + "\n",
        encoding="utf-8",
    )

    snap = collector.collect_snapshot(codex_home=codex_home, claude_home=claude_home)
    projects = snap["projects"]
    assert len(projects) == 2
    # Duplicate basenames are disambiguated cleanly without leaking full paths.
    names = {p["name"] for p in projects}
    assert names == {"team_alpha/webapp", "team_beta/webapp"}

    id_a = collector.project_id_for_cwd(proj_a)
    id_unindexed = collector.project_id_for_cwd(unindexed)
    resolved = resolve_local_project(id_a, codex_home=codex_home, claude_home=claude_home, antigravity_home=tmp_path / "none")
    assert resolved == proj_a
    # Unindexed directories must never resolve even if their hash is known.
    assert resolve_local_project(id_unindexed, codex_home=codex_home, claude_home=claude_home, antigravity_home=tmp_path / "none") is None


def test_local_executor_starts_new_codex_and_claude_sessions(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_MONITOR_STATE_DIR", str(tmp_path / "state"))
    workspace = tmp_path / "project"
    workspace.mkdir()
    proj_id = collector.project_id_for_cwd(workspace)

    for tool in ("codex", "claude"):
        events: list[dict] = []
        done = threading.Event()

        def emit(ev):
            events.append(ev)
            if ev["state"] in {"succeeded", "failed", "blocked", "uncertain"}:
                done.set()

        children: list[_FakeNewChild] = []
        executor = le.LocalReplyExecutor(
            emit,
            timeout_seconds=3,
            project_resolver=lambda pid: workspace if pid == proj_id else None,
            executable_finder=lambda t: f"fake-{t}.exe",
            process_factory=lambda argv, cwd, _t=tool: (children.append(_FakeNewChild(argv, cwd, tool=_t)) or children[-1]),
            interaction_handler=lambda req, cancelled: {"decision": "allow_once", "answers": []},
            report_applied=True,
        )
        res = executor.start(str(uuid.uuid4()), f"{tool}:new_{proj_id}", "Initialize new feature")
        assert res["state"] == "running"
        assert done.wait(3), events
        executor.close()
        assert events[-1]["state"] == "succeeded"
        created = events[-1].get("created_source_id", "")
        assert created.startswith(f"{tool}:")
        if tool == "codex":
            assert created == f"codex:{NEW_UUID}"
            assert events[-1]["applied"]["model"] == "gpt-5.4"
        else:
            assert "--session-id" in children[0].argv
            assert "--resume" not in children[0].argv
            assert events[-1]["applied"]["model"] == "claude-sonnet-4-6"


def test_http_start_task_and_turn_one_live_approval(tmp_path):
    origin = "https://start.test"
    app = create_app(tmp_path, public_url=origin, allowed_hosts=["start.test"])
    store = app.state.store
    account = {"username": "owner", "password": "Synthetic-start-password-123"}
    store.setup(**account)
    client = TestClient(app, base_url=origin)
    login = client.post("/api/login", json=account).json()
    csrf = {"Origin": origin, "X-CSRF-Token": login["csrf_token"]}
    verifier = secrets.token_urlsafe(32)
    start_pair = client.post(
        "/api/native/pairing/start",
        json={"device_name": "phone", "mode": "full_app", "code_challenge": challenge_for(verifier)},
    ).json()
    pair_path = "/api/native/pairing/" + start_pair["request_id"]
    assert client.post(pair_path + "/approve", headers=csrf, json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
    reader_token = client.post(pair_path + "/poll", json={"code_verifier": verifier}).json()["reader_token"]
    headers = {"Authorization": "Bearer " + reader_token}

    workspace = tmp_path / "live_repo"
    workspace.mkdir()
    proj_id = collector.project_id_for_cwd(workspace)
    device_id = store.ensure_local("Workstation", "Windows")
    store.ingest(
        device_id,
        [],
        [{"tool": "codex", "available": True, "mode": "local_log", "detail": "ok"}],
        projects=[{"id": proj_id, "name": "live_repo", "tools": ["codex"], "updated_at": iso()}],
        dev_ports=[{"port": 5173, "bind_scope": "network", "process": "node", "project_id": proj_id}],
        preview_hosts=["100.88.10.20", "192.168.1.50"],
    )

    replies = app.state.task_replies
    replies.local_enabled = True
    replies._local_tools_cache = ({"codex", "claude"}, time.monotonic())
    children: list[_FakeNewChild] = []

    def make_executor(emit):
        return le.LocalReplyExecutor(
            emit=emit,
            timeout_seconds=5,
            project_resolver=lambda pid: workspace if pid == proj_id else None,
            executable_finder=lambda t: f"fake-{t}.exe",
            process_factory=lambda argv, cwd: (children.append(_FakeNewChild(argv, cwd, tool="codex", ask_approval=True)) or children[-1]),
            interaction_handler=replies.controls.local_handler,
            report_applied=True,
        )

    replies.executor_factory = make_executor
    try:
        wb = client.get("/api/native/workbench", headers=headers).json()
        dev = wb["devices"][0]
        assert dev["supported_tools"] == ["codex", "claude", "antigravity"]
        assert dev["projects"][0]["id"] == proj_id
        assert dev["dev_ports"][0]["port"] == 5173
        assert dev["preview_hosts"] == ["100.88.10.20", "192.168.1.50"]

        req_id = str(uuid.uuid4())
        started = client.post(
            "/api/native/tasks/start",
            headers=headers,
            json={
                "device_id": device_id,
                "project_id": proj_id,
                "tool": "codex",
                "request_id": req_id,
                "text": "Run tests and fix lint",
            },
        )
        assert started.status_code == 200
        synthetic_task_id = f"{device_id}:codex:new_{proj_id}"
        replies._tick()

        created_task_id = ""
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            polled = client.get(
                "/api/native/tasks/reply",
                headers=headers,
                params={"task_id": synthetic_task_id, "request_id": req_id},
            ).json()
            created_task_id = polled.get("created_task_id", "")
            if created_task_id:
                controls = client.get(
                    "/api/native/tasks/controls",
                    headers=headers,
                    params={"task_id": created_task_id},
                ).json()
                if controls.get("pending"):
                    break
            time.sleep(0.05)

        assert created_task_id == f"{device_id}:codex:{NEW_UUID}"
        # Workbench immediately includes the spawned running task AND its pending approval badge!
        wb_live = client.get("/api/native/workbench", headers=headers).json()
        spawned_row = next(t for t in wb_live["tasks"] if t["id"] == created_task_id)
        assert spawned_row["status"] == "running"
        assert spawned_row["pending_interaction_kind"] == "approval"

        controls = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": created_task_id}).json()
        pending_req = controls["pending"][0]
        decided = client.post(
            "/api/native/tasks/controls/decision",
            headers=headers,
            json={
                "task_id": created_task_id,
                "request_id": pending_req["id"],
                "revision": pending_req["revision"],
                "decision_id": str(uuid.uuid4()),
                "decision": "allow_once",
                "answers": [],
            },
        )
        assert decided.status_code == 200

        deadline = time.monotonic() + 3.0
        final_state = ""
        while time.monotonic() < deadline:
            polled = client.get(
                "/api/native/tasks/reply",
                headers=headers,
                params={"task_id": synthetic_task_id, "request_id": req_id},
            ).json()
            final_state = polled.get("state", "")
            if final_state == "succeeded":
                break
            time.sleep(0.05)
        assert final_state == "succeeded"
    finally:
        replies.close()
        client.close()
        store.close()
