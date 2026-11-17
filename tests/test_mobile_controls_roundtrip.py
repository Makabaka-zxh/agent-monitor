"""Isolated provider → hub → authenticated phone → original wire contract.

No subprocess, phone, real session, approval, or provider request is executed.
"""
import json
import threading
import time
import uuid

import pytest

from agent_monitor.provider_controls import normalize_request
from test_task_replies import hub, send, SESSION


@pytest.mark.parametrize("provider", ["codex", "claude"])
@pytest.mark.parametrize("kind,decision", [("approval", "allow_once"), ("approval", "deny"),
                                           ("question", "answer"), ("question", "deny")])
def test_original_request_roundtrip_and_durable_no_replay(hub, provider, kind, decision):
    app, client, headers, task_id, original_task, *_ = hub
    device = task_id.partition(":")[0]
    source = provider + ":" + SESSION
    task_id = device + ":" + source
    app.state.store.ingest(device, [{**original_task, "tool": provider, "id": source}], [])
    command, response = send(hub, task_id=task_id)
    assert response.status_code == 200
    app.state.task_replies._tick()
    if provider == "codex":
        method = "item/commandExecution/requestApproval" if kind == "approval" else "item/tool/requestUserInput"
        params = {"threadId": SESSION, "turnId": "synthetic-turn", "itemId": "synthetic-item"}
        if kind == "approval":
            params.update(command="echo synthetic", cwd="C:/synthetic/workspace", availableDecisions=["accept", "decline"])
        else:
            params["questions"] = [{"id": "question", "header": "Synthetic", "question": "Pick one", "isOther": True,
                                     "options": [{"label": "Choice A", "description": "Synthetic description"}]}]
        message = {"id": "synthetic-wire", "method": method, "params": params}
    else:
        tool_input = {"command": "echo synthetic"} if kind == "approval" else {"questions": [
            {"header": "Synthetic", "question": "Pick one", "multiSelect": False,
             "options": [{"label": "Choice A", "description": "Synthetic description"}]}]}
        message = {"type": "control_request", "request_id": "synthetic-wire", "session_id": SESSION,
                   "request": {"subtype": "can_use_tool", "tool_name": "Bash" if kind == "approval" else "AskUserQuestion",
                               "tool_use_id": "synthetic-item", "input": tool_input}}
    request = normalize_request(provider, message, command_id=command["request_id"], source_id=source,
                                session_id=SESSION, turn_id="synthetic-turn")
    service = app.state.task_replies.controls
    cancelled = threading.Event()
    answers, errors = [], []
    def waiter():
        try: answers.append(service.local_handler(request.public, cancelled))
        except Exception as error: errors.append(type(error).__name__)
    thread = threading.Thread(target=waiter)
    thread.start()
    try:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            value = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": task_id}).json()
            if value["pending"]: break
            time.sleep(.01)
        pending = value["pending"][0]
        assert pending["id"] == request.public["id"] and pending["revision"] == request.public["revision"]
        chosen = [{"question_id": pending["questions"][0]["id"], "choices": ["o0"], "text": None}] if decision == "answer" else []
        payload = {"task_id": task_id, "request_id": pending["id"], "revision": pending["revision"],
                   "decision_id": str(uuid.uuid4()), "decision": decision, "answers": chosen}
        path = "/api/native/tasks/controls/decision"
        assert client.post(path, headers=headers, json=payload).json()["state"] == "submitted"
        thread.join(2)
        assert not thread.is_alive() and not errors and len(answers) == 1
        wire = request.response(answers[0])
        if provider == "codex":
            assert wire["id"] == message["id"]
            if decision == "deny":
                assert wire["result"] == ({"decision": "decline"} if kind == "approval" else {"answers": {}})
            elif decision == "allow_once":
                assert wire["result"] == {"decision": "accept"}
            else:
                assert wire["result"] == {"answers": {"question": {"answers": ["Choice A"]}}}
        else:
            assert wire["response"]["request_id"] == message["request_id"]
            result = wire["response"]["response"]
            if decision == "deny":
                assert result == {"behavior": "deny", "message": "User declined this request", "interrupt": True}
            else:
                assert result["behavior"] == "allow"
                assert result["updatedInput"] == (message["request"]["input"] if kind == "approval" else {
                    **message["request"]["input"], "answers": {"Pick one": "Choice A"}})
            assert "updatedPermissions" not in result
        service.local_handler.resolve(pending["id"], pending["revision"], "forwarded")
        assert client.post(path, headers=headers, json=payload).json()["state"] == "forwarded"
        assert service.poll_decision(device, pending["id"], pending["revision"])["decision"] is None
        receipt = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": task_id}).json()["pending"][0]
        assert receipt["state"] == "forwarded" and receipt["questions"] == [] and receipt["operation"] == ""
        assert "synthetic description" not in json.dumps(receipt).lower()
    finally:
        cancelled.set()
        thread.join(2)
