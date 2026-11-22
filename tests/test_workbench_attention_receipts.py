"""End-to-end coverage for cross-filter pending entry, action receipts, sync timings, and continuation states."""
from __future__ import annotations

import secrets
import time
import uuid

from fastapi.testclient import TestClient
import pytest

from agent_monitor.native_access import challenge_for
from agent_monitor.server import create_app
from agent_monitor.store import iso

ORIGIN = "https://receipts-acceptance.example.test"


@pytest.fixture
def env(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["receipts-acceptance.example.test"])
    store = app.state.store
    account = {"username": "acceptance-owner", "password": "Synthetic-acceptance-pass-123"}
    store.setup(**account)
    client = TestClient(app, base_url=ORIGIN)
    login = client.post("/api/login", json=account).json()
    csrf = {"Origin": ORIGIN, "X-CSRF-Token": login["csrf_token"]}

    verifier = secrets.token_urlsafe(32)
    started = client.post(
        "/api/native/pairing/start",
        json={"device_name": "My Phone", "mode": "full_app", "code_challenge": challenge_for(verifier)},
    ).json()
    path = "/api/native/pairing/" + started["request_id"]
    assert client.post(path + "/approve", headers=csrf, json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
    token = client.post(path + "/poll", json={"code_verifier": verifier}).json()["reader_token"]
    phone = {"Authorization": "Bearer " + token}

    try:
        yield app, client, phone, store
    finally:
        app.state.task_replies.close()
        client.close()
        store.close()


def test_acceptance_codex_filter_discovers_other_computer_claude_question_and_tracks_receipts(env):
    app, client, phone, store = env

    # Computer A runs Codex; Computer B runs Claude Code on another machine.
    comp_a = store.register(store.create_pairing()["code"], "Workstation-A", "Windows")
    comp_b = store.register(store.create_pairing()["code"], "Studio-Mac", "macOS")
    agent_a = {"Authorization": "Bearer " + comp_a["token"]}
    agent_b = {"Authorization": "Bearer " + comp_b["token"]}

    session_a = "00000000-0000-4000-8000-000000000011"
    session_b = "00000000-0000-4000-8000-000000000022"
    collected_stamp = iso(time.time() - 0.25)

    assert client.post(
        "/api/agent/heartbeat",
        headers=agent_a,
        json={
            "collected_at": collected_stamp,
            "tasks": [{"id": "codex:" + session_a, "tool": "codex", "title": "Refactor API", "status": "running", "updated_at": iso()}],
            "sources": [{"tool": "codex", "available": True, "mode": "local_log"}],
        },
    ).status_code == 200
    assert client.post(
        "/api/agent/heartbeat",
        headers=agent_b,
        json={
            "collected_at": collected_stamp,
            "tasks": [{"id": "claude:" + session_b, "tool": "claude", "title": "Deploy Check", "status": "completed", "updated_at": iso()}],
            "sources": [{"tool": "claude", "available": True, "mode": "local_log+hook"}],
        },
    ).status_code == 200

    # Phone sets its saved workbench filter to Codex only.
    assert client.patch("/api/native/preferences", headers=phone, json={"tool_filter": "codex"}).status_code == 200

    # Computer B advertises Claude continuation capability; Computer A has not advertised reply worker yet.
    assert client.post("/api/agent/replies/poll", headers=agent_b, json={"supported_tools": ["claude"]}).status_code == 200

    wb = client.get("/api/native/workbench", headers=phone).json()
    assert wb["preferences"]["tool_filter"] == "codex"
    assert wb["sync_timings"]["computer_completed_at"] == collected_stamp
    assert wb["sync_timings"]["server_received_at"] is not None
    assert wb["sync_timings"]["server_generated_at"] == wb["generated_at"]

    tasks_by_id = {t["id"]: t for t in wb["tasks"]}
    task_a_id = comp_a["device_id"] + ":codex:" + session_a
    task_b_id = comp_b["device_id"] + ":claude:" + session_b
    assert tasks_by_id[task_a_id]["continuation_state"] == "view_only"
    assert tasks_by_id[task_b_id]["continuation_state"] == "continuable"

    # Phone starts a continuation turn on Computer B's Claude session, which then asks a structured question.
    reply_id = str(uuid.uuid4())
    assert client.post(
        "/api/native/tasks/reply",
        headers=phone,
        json={"task_id": task_b_id, "request_id": reply_id, "text": "Continue deployment"},
    ).status_code == 200
    claimed_cmd = client.post("/api/agent/replies/poll", headers=agent_b, json={"supported_tools": ["claude"]}).json()["command"]
    assert claimed_cmd["id"] == reply_id
    assert client.post(
        "/api/agent/replies/update",
        headers=agent_b,
        json={"id": reply_id, "state": "running", "applied": {"model": "claude-sonnet-4-6", "effort": "high"}},
    ).status_code == 200

    q_id = str(uuid.uuid4())
    assert client.post(
        "/api/agent/controls/request",
        headers=agent_b,
        json={
            "command_id": reply_id,
            "source_id": "claude:" + session_b,
            "request": {
                "id": q_id,
                "revision": "rev-1",
                "kind": "question",
                "provider": "claude",
                "session_id": session_b,
                "turn_id": "turn-1",
                "title": "选择发布环境",
                "detail": "请选择目标集群",
                "operation": "",
                "questions": [{
                    "id": "env",
                    "text": "目标环境",
                    "multiple": False,
                    "allows_text": False,
                    "options": [{"id": "staging", "label": "预发布"}, {"id": "prod", "label": "生产"}],
                }],
                "expires_at": time.time() + 180,
            },
        },
    ).status_code == 200

    # Even while phone preference is still 'codex', workbench surfaces Computer B's Claude question!
    wb_waiting = client.get("/api/native/workbench", headers=phone).json()
    assert wb_waiting["preferences"]["tool_filter"] == "codex"
    claude_card = next(t for t in wb_waiting["tasks"] if t["id"] == task_b_id)
    assert claude_card["pending_interaction_kind"] == "question"
    assert claude_card["continuation_state"] == "computer_in_use"

    # Phone submits answer -> Step 1: submitted, delivered=False ("手机已发送 -> 等待电脑接收")
    decision_id = str(uuid.uuid4())
    submitted = client.post(
        "/api/native/tasks/controls/decision",
        headers=phone,
        json={
            "task_id": task_b_id,
            "request_id": q_id,
            "revision": "rev-1",
            "decision_id": decision_id,
            "decision": "answer",
            "answers": [{"question_id": "env", "choices": ["staging"], "text": None}],
        },
    ).json()
    assert submitted["state"] == "submitted"
    assert submitted["delivered"] is False
    assert submitted["decision_kind"] == "answer"

    # Step 2: Computer B polls decision -> delivered=True ("手机已发送 -> 电脑已收到")
    polled = client.post("/api/agent/controls/poll", headers=agent_b, json={"request_id": q_id, "revision": "rev-1"}).json()
    assert polled["decision"]["decision"] == "answer"
    after_poll = client.get("/api/native/tasks/controls", headers=phone, params={"task_id": task_b_id}).json()["pending"][0]
    assert after_poll["state"] == "submitted"
    assert after_poll["delivered"] is True

    # Step 3: Computer B forwards to tool, then tool confirms adoption -> ("已采用")
    forwarded = client.post(
        "/api/agent/controls/resolve",
        headers=agent_b,
        json={"request_id": q_id, "revision": "rev-1", "state": "forwarded"},
    ).json()
    assert forwarded["state"] == "forwarded" and forwarded["delivered"] is True

    adopted = client.post(
        "/api/agent/controls/resolve",
        headers=agent_b,
        json={"request_id": q_id, "revision": "rev-1", "state": "acknowledged", "outcome": "adopted"},
    ).json()
    assert adopted["state"] == "acknowledged"
    assert adopted["outcome"] == "adopted"
    assert adopted["decision_kind"] == "answer"

    # Step 4: Check reply status also surfaces the exact applied model & effort for this turn!
    reply_status = client.get("/api/native/tasks/reply", headers=phone, params={"task_id": task_b_id, "request_id": reply_id}).json()
    assert reply_status["claimed_at"] is not None
    assert reply_status["applied"] == {"model": "claude-sonnet-4-6", "effort": "high"}
    assert "Continue deployment" not in str(reply_status)

    # Step 5: Desktop releases session -> task automatically flips back to "continuable"
    assert client.post("/api/agent/replies/update", headers=agent_b, json={"id": reply_id, "state": "succeeded"}).status_code == 200
    wb_released = client.get("/api/native/workbench", headers=phone).json()
    assert wb_released["preferences"]["tool_filter"] == "codex"
    assert next(t for t in wb_released["tasks"] if t["id"] == task_b_id)["continuation_state"] == "continuable"


def test_desktop_handled_and_rejected_receipts_are_distinguished(env):
    app, client, phone, store = env
    comp = store.register(store.create_pairing()["code"], "Office-PC", "Windows")
    agent = {"Authorization": "Bearer " + comp["token"]}
    session = "00000000-0000-4000-8000-000000000033"
    assert client.post(
        "/api/agent/heartbeat",
        headers=agent,
        json={"tasks": [{"id": "codex:" + session, "tool": "codex", "title": "Build", "status": "completed", "updated_at": iso()}], "sources": []},
    ).status_code == 200
    assert client.post("/api/agent/replies/poll", headers=agent, json={"supported_tools": ["codex"]}).status_code == 200
    task_id = comp["device_id"] + ":codex:" + session

    reply_id = str(uuid.uuid4())
    assert client.post("/api/native/tasks/reply", headers=phone, json={"task_id": task_id, "request_id": reply_id, "text": "Run check"}).status_code == 200
    client.post("/api/agent/replies/poll", headers=agent, json={"supported_tools": ["codex"]})
    client.post("/api/agent/replies/update", headers=agent, json={"id": reply_id, "state": "running", "applied": {"model": "gpt-5.4", "effort": "xhigh", "service_tier": "priority", "collaboration_mode": "plan"}})

    # Request 1: Handled directly on desktop before phone acts
    req1 = str(uuid.uuid4())
    client.post("/api/agent/controls/request", headers=agent, json={
        "command_id": reply_id, "source_id": "codex:" + session,
        "request": {"id": req1, "revision": "r1", "kind": "approval", "provider": "codex", "session_id": session, "turn_id": "t1", "title": "执行命令", "detail": "", "operation": "pytest", "questions": [], "expires_at": time.time() + 120},
    })
    res_desktop = client.post("/api/agent/controls/resolve", headers=agent, json={"request_id": req1, "revision": "r1", "state": "cancelled", "outcome": "handled_on_desktop"}).json()
    assert res_desktop["state"] == "cancelled"
    assert res_desktop["outcome"] == "handled_on_desktop"

    # Request 2: Explicitly denied from phone
    req2 = str(uuid.uuid4())
    client.post("/api/agent/controls/request", headers=agent, json={
        "command_id": reply_id, "source_id": "codex:" + session,
        "request": {"id": req2, "revision": "r2", "kind": "approval", "provider": "codex", "session_id": session, "turn_id": "t1", "title": "修改文件", "detail": "", "operation": "edit a.py", "questions": [], "expires_at": time.time() + 120},
    })
    client.post("/api/native/tasks/controls/decision", headers=phone, json={"task_id": task_id, "request_id": req2, "revision": "r2", "decision_id": str(uuid.uuid4()), "decision": "deny", "answers": []})
    client.post("/api/agent/controls/poll", headers=agent, json={"request_id": req2, "revision": "r2"})
    res_denied = client.post("/api/agent/controls/resolve", headers=agent, json={"request_id": req2, "revision": "r2", "state": "forwarded", "outcome": "rejected"}).json()
    assert res_denied["state"] == "forwarded"
    assert res_denied["decision_kind"] == "deny"
    assert res_denied["outcome"] == "rejected"

    reply_info = client.get("/api/native/tasks/reply", headers=phone, params={"task_id": task_id, "request_id": reply_id}).json()
    assert reply_info["applied"] == {"model": "gpt-5.4", "effort": "xhigh", "service_tier": "priority", "collaboration_mode": "plan"}
