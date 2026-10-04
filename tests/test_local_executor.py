"""Protocol and lifecycle tests use recording fake children; no agent/account call."""
from __future__ import annotations

import json
import io
from pathlib import Path
import queue
import threading
import time
import tomllib
from types import SimpleNamespace

import pytest

from agent_monitor import local_executor as le

SESSION = "12345678-1234-5678-9012-123456789abc"


class Pipe:
    def __init__(self): self.queue = queue.Queue(); self.closed = False
    def put(self, value): self.queue.put(value if isinstance(value, bytes) else (json.dumps(value)+"\n").encode())
    def readline(self, limit=-1): return self.queue.get(timeout=3)
    def close(self):
        self.closed = True
        self.queue.put(b"")


class Input:
    def __init__(self, owner): self.owner = owner; self.bytes = bytearray(); self.pending = bytearray(); self.closed = False
    def write(self, value):
        data = bytes(value); self.bytes.extend(data); self.pending.extend(data)
        if self.owner.tool == "codex":
            while b"\n" in self.pending:
                line, _, tail = self.pending.partition(b"\n"); self.pending[:] = tail
                self.owner.receive(json.loads(line))
        return len(data)
    def flush(self): pass
    def close(self):
        if not self.closed and self.owner.tool == "claude": self.owner.claude_result()
        self.closed = True


class Child:
    def __init__(self, argv, cwd, *, outcome="success", resumed=None):
        self.argv, self.cwd, self.outcome, self.resumed = argv, cwd, outcome, resumed
        self.tool = "codex" if "app-server" in argv else "claude"
        self.profile = argv[2].split(".", 1)[1].split("=", 1)[0] if self.tool == "codex" else None
        self.requests = []
        self.close_calls = 0
        self.proc = SimpleNamespace(stdout=Pipe(), stderr=Pipe())
        self.proc.stdin = Input(self)
    def receive(self, message):
        self.requests.append(message)
        method = message.get("method")
        if method == "initialize": self.proc.stdout.put({"id": 1, "result": {}})
        elif method == "thread/resume":
            if self.resumed: self.resumed()
            result = {"thread": {"id": SESSION, "status": {"type": "idle"}}, "cwd": str(self.cwd),
                      "activePermissionProfile": {"id": self.profile}, "approvalPolicy": "untrusted", "approvalsReviewer": "user",
                      "sandbox": {"type": "workspaceWrite", "networkAccess": False}}
            if self.outcome == "wrong-session": result["thread"]["id"] = "another-session"
            if self.outcome == "wrong-profile": result["activePermissionProfile"]["id"] = ":workspace"
            if self.outcome == "wrong-cwd": result["cwd"] = str(Path(self.cwd).parent)
            if self.outcome == "active": result["thread"]["status"]["type"] = "active"
            if self.outcome == "auto-review": result["approvalsReviewer"] = "auto_review"
            if self.outcome == "network": result["sandbox"]["networkAccess"] = True
            self.proc.stdout.put({"id": 2, "result": result})
        elif method == "turn/start":
            self.proc.stdout.put({"id": 3, "result": {"turn": {"id": "turn-1", "status": "inProgress"}}})
            if self.outcome in {"hang", "cancel"}: return
            if self.outcome == "eof": self.proc.stdout.put(b""); return
            if self.outcome == "malformed": self.proc.stdout.put(b"not-json\n"); return
            if self.outcome == "large": self.proc.stdout.put(b"x" * (le.MAX_LINE_BYTES + 1)); return
            if self.outcome in {"approval", "permissions", "elicitation", "dynamic"}:
                kind = {"approval": "item/commandExecution/requestApproval", "permissions": "item/permissions/requestApproval",
                        "elicitation": "mcpServer/elicitation/request", "dynamic": "item/tool/call"}[self.outcome]
                self.proc.stdout.put({"id": "server-1", "method": kind, "params": {"threadId": SESSION, "turnId": "turn-1"}})
                return
            self.proc.stdout.put({"method": "item/agentMessage/delta", "params": {"threadId": SESSION, "delta": "PRIVATE TOOL LOG MUST NOT REACH UI"}})
            self.proc.stdout.put({"method": "turn/completed", "params": {
                "threadId": SESSION, "turn": {"id": "other-turn" if self.outcome == "wrong-turn" else "turn-1",
                    "status": "failed" if self.outcome == "failed" else "completed", "error": None}}})
    def claude_result(self):
        sid = "another-session" if self.outcome == "wrong-session" else SESSION
        mode = self.argv[self.argv.index("--permission-mode")+1]
        self.proc.stdout.put({"type": "system", "subtype": "init", "session_id": sid,
                              "permissionMode": "auto" if self.outcome == "wrong-mode" else mode})
        if self.outcome in {"hang", "cancel"}: return
        if self.outcome == "eof": self.proc.stdout.put(b""); return
        self.proc.stdout.put({"type": "assistant", "session_id": sid, "message": {"content": "DO NOT FORWARD INTERNAL OUTPUT"}})
        self.proc.stdout.put({"type": "result", "session_id": sid, "subtype": "error_max_turns" if self.outcome == "failed" else "success",
                              "is_error": self.outcome == "failed", "permission_denials": ["Bash"] if self.outcome == "approval" else []})
    def close(self, *, graceful=False):
        self.close_calls += 1
        self.proc.stdout.close(); self.proc.stderr.close()


@pytest.fixture
def target(tmp_path):
    cwd = tmp_path / "workspace"; cwd.mkdir()
    path = tmp_path / "session.jsonl"
    entries = [
        {"path": {"type": "special", "value": {"kind": "root"}}, "access": "read"},
        {"path": {"type": "path", "path": str(cwd)}, "access": "write"},
        {"path": {"type": "path", "path": str(cwd / ".codex")}, "access": "read", "missing_path_behavior": "skip"},
        {"path": {"type": "special", "value": {"kind": "tmpdir"}}, "access": "write"},
        {"path": {"type": "special", "value": {"kind": "slash_tmp"}}, "access": "write"},
    ]
    context = {"cwd": str(cwd), "model": "gpt-6-astra", "effort": "high",
               "permission_profile": {"type": "managed", "file_system": {"type": "restricted", "entries": entries}, "network": "restricted"},
               "file_system_sandbox_policy": {"kind": "restricted", "entries": entries}}
    path.write_text(json.dumps({"type": "turn_context", "payload": context, "permissionMode": "auto"})+"\n")
    def resolved(tool="codex"):
        return SimpleNamespace(path=path, cwd=cwd, tool=tool, session_id=SESSION, source_id=tool+":"+SESSION)
    return SimpleNamespace(path=path, cwd=cwd, context=context, resolved=resolved)


def run_case(target, tool="codex", outcome="success", timeout=.7, status_reader=None, resumed=None):
    events, children = [], []
    finished = threading.Event()
    def emit(value):
        events.append(value)
        if value["state"] != "running": finished.set()
    def factory(argv, cwd):
        child = Child(argv, cwd, outcome=outcome, resumed=resumed); children.append(child); return child
    status_reader = status_reader or (lambda value: {"id": value.source_id, "tool": value.tool, "status": "completed"})
    executor = le.LocalReplyExecutor(emit, timeout_seconds=timeout,
        resolver=lambda source: target.resolved(tool) if source == tool+":"+SESSION else None,
        status_reader=status_reader, executable_finder=lambda selected: "safe-"+selected+".exe", process_factory=factory)
    response = executor.start("command-1", tool+":"+SESSION, "literal $() `text`\n--dangerous is only prompt text")
    if outcome == "cancel":
        until = time.monotonic()+1
        while not children and time.monotonic()<until: time.sleep(.005)
        executor.cancel("command-1")
    if response["state"] == "running": assert finished.wait(3), events
    executor.close()
    return response, events, children, executor


@pytest.mark.parametrize("tool", ["codex", "claude"])
def test_exact_session_success_and_stdin_only(target, tool):
    response, events, children, executor = run_case(target, tool)
    assert response["state"] == "running"
    assert events[-1] == {"command_id": "command-1", "state": "succeeded", "reason": ""}
    child = children[0]
    assert child.close_calls == 1
    assert all("literal" not in str(arg) for arg in child.argv)
    assert b"literal" in child.proc.stdin.bytes
    assert "PRIVATE" not in json.dumps(events) and "INTERNAL" not in json.dumps(events)
    assert not any("bypass" in arg or "fork" in arg or "acceptEdits" in arg for arg in child.argv)
    if tool == "codex":
        resume = next(r for r in child.requests if r.get("method") == "thread/resume")
        turn = next(r for r in child.requests if r.get("method") == "turn/start")
        assert resume["params"]["threadId"] == turn["params"]["threadId"] == SESSION
        assert resume["params"]["permissions"] == turn["params"]["permissions"]
        assert resume["params"]["approvalsReviewer"] == turn["params"]["approvalsReviewer"] == "user"
        profile = tomllib.loads("profile="+child.argv[2].split("=",1)[1])["profile"]
        assert profile["filesystem"][str(target.cwd/".codex")] == "read"
        assert profile["filesystem"][str(target.cwd)] == "write"
        assert profile["network"]["enabled"] is False
    else:
        assert child.argv[child.argv.index("--resume")+1] == SESSION
        assert child.argv[child.argv.index("--permission-mode")+1] == "dontAsk"
    assert executor.start("command-1", tool+":"+SESSION, "again")["state"] == "succeeded"
    assert len(children) == 1


@pytest.mark.parametrize("outcome,state", [("wrong-session","uncertain"), ("wrong-turn","uncertain"),
    ("wrong-cwd","uncertain"), ("wrong-profile","blocked"), ("active","blocked"), ("auto-review","blocked"),
    ("network","blocked"), ("approval","blocked"), ("permissions","blocked"), ("elicitation","blocked"),
    ("dynamic","blocked"), ("failed","failed"), ("eof","uncertain"), ("malformed","uncertain"),
    ("large","uncertain"), ("hang","uncertain"), ("cancel","uncertain")])
def test_codex_fail_closed_and_never_retry(target,outcome,state):
    _, events, children, _ = run_case(target,outcome=outcome)
    assert events[-1]["state"] == state
    assert len(children) == 1 and children[0].close_calls == 1
    if outcome in {"wrong-session","wrong-profile","wrong-cwd","active","auto-review","network"}:
        assert not any(r.get("method") == "turn/start" for r in children[0].requests)
    if outcome in {"approval","permissions","elicitation","dynamic"}:
        reply = next(r for r in children[0].requests if r.get("id") == "server-1")
        assert "accept" not in json.dumps(reply)
        assert reply.get("result", {}).get("permissions", {}) == {}


@pytest.mark.parametrize("outcome,state", [("wrong-session","uncertain"),("wrong-mode","blocked"),("approval","blocked"),("failed","failed"),("eof","uncertain"),("hang","uncertain"),("cancel","uncertain")])
def test_claude_requires_matching_final_result(target,outcome,state):
    _, events, children, _ = run_case(target,tool="claude",outcome=outcome)
    assert events[-1]["state"] == state
    assert len(children) == 1 and children[0].close_calls == 1


@pytest.mark.parametrize("status", ["running","waiting","unknown",None])
def test_busy_or_unknown_never_spawns(target,status):
    response, events, children, _ = run_case(target,status_reader=lambda value:{"id":value.source_id,"tool":value.tool,"status":status})
    assert response["state"] == "blocked"
    assert not events and not children


def test_race_after_resume_rechecks_actual_local_state(target):
    status = ["completed"]
    _, events, children, _ = run_case(target,
        status_reader=lambda value:{"id":value.source_id,"tool":value.tool,"status":status[0]},
        resumed=lambda:status.__setitem__(0,"running"))
    assert events[-1]["state"] == "blocked"
    assert not any(r.get("method") == "turn/start" for r in children[0].requests)


def test_environment_drops_parent_tokens_and_nested_identity(monkeypatch):
    for key in ("CLAUDECODE","CLAUDE_CODE_ENTRYPOINT","CODEX_THREAD_ID","OPENAI_API_KEY","ANTHROPIC_API_KEY","GITHUB_TOKEN"):
        monkeypatch.setenv(key,"secret-marker")
    monkeypatch.setenv("USERPROFILE","trusted-home")
    env = le._child_environment()
    assert "secret-marker" not in env.values()
    assert env["USERPROFILE"] == "trusted-home"


@pytest.mark.parametrize("tool", ["codex", "claude"])
def test_child_preserves_exact_inherited_proxy_routing_for_each_tool(monkeypatch, tmp_path, tool):
    proxies = {
        "HTTP_PROXY": "http://127.0.0.1:8090", "http_proxy": "http://127.0.0.1:8091",
        "HTTPS_PROXY": "http://127.0.0.1:8092", "https_proxy": "socks5h://127.0.0.1:8093",
        "ALL_PROXY": "socks5h://127.0.0.1:8094", "all_proxy": "socks5h://127.0.0.1:8095",
        "NO_PROXY": "localhost,127.0.0.1,.internal.example", "no_proxy": "localhost,::1",
    }
    # A plain mapping preserves distinct key cases even on the Windows test host.
    monkeypatch.setattr(le.os, "environ", {**proxies, "PATH": str(tmp_path), "ANTHROPIC_API_KEY": "do-not-inherit"})
    monkeypatch.setattr(le, "_WindowsJob", lambda: None)
    seen = []
    le._Process([tool, "--test"], tmp_path, popen=lambda argv, **options: seen.append(options) or object())
    child_env = seen[0]["env"]
    assert {key: child_env[key] for key in proxies} == proxies
    assert child_env["PATH"] == str(tmp_path)
    assert "ANTHROPIC_API_KEY" not in child_env
    assert seen[0]["shell"] is False


def test_child_does_not_invent_proxy_settings_when_parent_has_none(monkeypatch):
    monkeypatch.setattr(le.os, "environ", {"HOME": "/Users/test", "PATH": "/usr/bin:/bin"})
    env = le._child_environment()
    assert not any(key.upper().endswith("_PROXY") for key in env)


def test_claude_plan_cannot_be_upgraded_or_exited(target):
    target.path.write_text(json.dumps({"permissionMode": "plan"})+"\n")
    _, events, children, _ = run_case(target, tool="claude")
    assert events[-1]["state"] == "succeeded"
    argv = children[0].argv
    assert argv[argv.index("--permission-mode")+1] == "plan"
    assert argv[argv.index("--disallowedTools")+1] == "ExitPlanMode"


@pytest.mark.parametrize("mode", [None, "new-unknown-mode"])
def test_claude_unknown_permissions_never_start(target, mode):
    target.path.write_text(json.dumps({"permissionMode": mode} if mode else {})+"\n")
    response, events, children, _ = run_case(target, tool="claude")
    assert response["state"] == "blocked" and not children and not events


def test_profile_rejects_unknown_or_weaker_mapping(target):
    context = json.loads(json.dumps(target.context))
    context["permission_profile"]["file_system"]["entries"].append({"path":{"type":"special","value":{"kind":"future-special"}},"access":"write"})
    with pytest.raises(le.ExecutionIssue): le._permission_profile(context,target.cwd)
    context = json.loads(json.dumps(target.context))
    context["file_system_sandbox_policy"]["entries"] = []
    with pytest.raises(le.ExecutionIssue): le._permission_profile(context,target.cwd)


def test_profile_retains_protected_path_and_restricts_network(target):
    profile = le._permission_profile(target.context,target.cwd)
    assert profile["filesystem"] == {":root":"read",str(target.cwd):"write",str(target.cwd/".codex"):"read",":tmpdir":"write",":slash_tmp":"write"}
    assert profile["network"] == {"enabled":False}
    assert tomllib.loads("profile="+le._toml(profile))["profile"] == profile


def test_duplicate_source_is_blocked_and_close_cancels_owned_process(target):
    children, events = [], []
    executor = le.LocalReplyExecutor(events.append, timeout_seconds=5,
        resolver=lambda _:target.resolved(), status_reader=lambda v:{"id":v.source_id,"tool":v.tool,"status":"idle"},
        executable_finder=lambda _:"codex.exe", process_factory=lambda argv,cwd:children.append(Child(argv,cwd,outcome="hang")) or children[-1])
    assert executor.start("a", "codex:"+SESSION,"one")["state"] == "running"
    assert executor.start("b", "codex:"+SESSION,"two")["state"] == "blocked"
    until = time.monotonic()+1
    while not children and time.monotonic()<until: time.sleep(.005)
    executor.close()
    assert len(children) == 1 and children[0].close_calls == 1
    assert events[-1]["state"] == "uncertain"
    assert executor.start("c","codex:"+SESSION,"three")["state"] == "blocked"


def test_posix_group_is_closed_even_when_group_leader_already_exited(monkeypatch):
    # Never create/kill a real group; exercise shutdown of an owned fake group.
    calls = []
    monkeypatch.setattr(le.os, "killpg", lambda pid, sig: calls.append((pid, sig)), raising=False)
    monkeypatch.setattr(le.signal, "SIGKILL", 9, raising=False)
    child = le._Process.__new__(le._Process)
    child.job = None
    child.closed = False
    child.proc = SimpleNamespace(pid=424242, poll=lambda:0, stdin=io.BytesIO(), stdout=io.BytesIO(), stderr=io.BytesIO())
    child.close()
    assert calls == [(424242, le.signal.SIGTERM), (424242, le.signal.SIGKILL)]
    child.close()
    assert len(calls) == 2
