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


@pytest.mark.parametrize("provider,kind,decision", [
    (provider, kind, decision) for provider in ("codex", "claude", "antigravity")
    for kind, decision in (("approval", "allow_once"), ("approval", "deny"), ("question", "answer"), ("question", "deny"))
] + [("codex", "mcp_form", decision) for decision in ("answer", "deny")])
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
        if kind == "mcp_form":
            method = "mcpServer/elicitation/request"
            params = {"threadId": SESSION, "turnId": "synthetic-turn", "mode": "form", "serverName": "test-service",
                      "environmentId": None, "_meta": None, "message": "Choose sections", "requestedSchema": {
                          "type": "object", "properties": {"include_other": {"type": "array", "title": "Sections",
                              "items": {"anyOf": [{"const": "a", "title": "First"}, {"const": "b", "title": "Second"}]}}},
                          "required": ["include_other"]}}
        elif kind == "approval":
            params.update(command="echo synthetic", cwd="C:/synthetic/workspace", availableDecisions=["accept", "decline"])
        else:
            params["questions"] = [{"id": "question", "header": "Synthetic", "question": "Pick one", "isOther": True,
                                     "options": [{"label": "Choice A", "description": "Synthetic description"}]}]
        message = {"id": "synthetic-wire", "method": method, "params": params}
    elif provider == "claude":
        tool_input = {"command": "echo synthetic"} if kind == "approval" else {"questions": [
            {"header": "Synthetic", "question": "Pick one", "multiSelect": False,
             "options": [{"label": "Choice A", "description": "Synthetic description"}]}]}
        message = {"type": "control_request", "request_id": "synthetic-wire", "session_id": SESSION,
                   "request": {"subtype": "can_use_tool", "tool_name": "Bash" if kind == "approval" else "AskUserQuestion",
                               "tool_use_id": "synthetic-item", "input": tool_input}}
    else:
        params = {"sessionId": SESSION}
        if kind == "approval":
            params.update(toolCall={"toolCallId": "tc1", "name": "demo", "title": "Synthetic demo"},
                          options=[{"kind": "allow_once", "optionId": "yes", "name": "Allow"},
                                   {"kind": "reject_once", "optionId": "no", "name": "Reject"}])
            method = "session/request_permission"
        else:
            params.update(mode="form", message="Pick one", requestedSchema={
                "type": "object", "properties": {"theme": {"type": "string", "title": "Theme",
                                                          "enum": ["light", "dark"]}}})
            method = "elicitation/create"
        message = {"jsonrpc": "2.0", "id": "synthetic-wire", "method": method, "params": params}
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
        if kind == "mcp_form" and decision == "answer":
            options = pending["questions"][0]["options"]
            assert [option["label"] for option in options] == ["First", "Second"]
            chosen[0]["choices"] = [options[1]["id"], options[0]["id"]]
        payload = {"task_id": task_id, "request_id": pending["id"], "revision": pending["revision"],
                   "decision_id": str(uuid.uuid4()), "decision": decision, "answers": chosen}
        path = "/api/native/tasks/controls/decision"
        assert client.post(path, headers=headers, json=payload).json()["state"] == "submitted"
        thread.join(2)
        assert not thread.is_alive() and not errors and len(answers) == 1
        wire = request.response(answers[0])
        if provider == "codex":
            assert wire["id"] == message["id"]
            if kind == "mcp_form":
                expected = {"action": "accept", "content": {"include_other": ["b", "a"]}} if decision == "answer" else {
                    "action": "decline", "content": None}
                assert wire["result"] == {**expected, "_meta": None}
            elif decision == "deny":
                assert wire["result"] == ({"decision": "decline"} if kind == "approval" else {"answers": {}})
            elif decision == "allow_once":
                assert wire["result"] == {"decision": "accept"}
            else:
                assert wire["result"] == {"answers": {"question": {"answers": ["Choice A"]}}}
        elif provider == "claude":
            assert wire["response"]["request_id"] == message["request_id"]
            result = wire["response"]["response"]
            if decision == "deny":
                assert result == {"behavior": "deny", "message": "User declined this request", "interrupt": True}
            else:
                assert result["behavior"] == "allow"
                assert result["updatedInput"] == (message["request"]["input"] if kind == "approval" else {
                    **message["request"]["input"], "answers": {"Pick one": "Choice A"}})
            assert "updatedPermissions" not in result
        else:
            assert wire["id"] == message["id"]
            if kind == "approval":
                assert wire["result"] == {"outcome": {"outcome": "selected",
                                                      "optionId": "yes" if decision == "allow_once" else "no"}}
            elif decision == "deny":
                assert wire["result"] == {"action": "decline"}
            else:
                assert wire["result"] == {"action": "accept", "content": {"theme": "light"}}
        service.local_handler.resolve(pending["id"], pending["revision"], "forwarded")
        assert client.post(path, headers=headers, json=payload).json()["state"] == "forwarded"
        assert service.poll_decision(device, pending["id"], pending["revision"])["decision"] is None
        receipt = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": task_id}).json()["pending"][0]
        assert receipt["state"] == "forwarded" and receipt["questions"] == [] and receipt["operation"] == ""
        assert "synthetic description" not in json.dumps(receipt).lower()
    finally:
        cancelled.set()
        thread.join(2)
