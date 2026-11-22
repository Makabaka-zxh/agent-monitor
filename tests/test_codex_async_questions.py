import json
import queue
import threading
import time
import uuid
from types import SimpleNamespace

import pytest

from agent_monitor.codex_async_questions import CLOSE, OPEN, CodexAsyncQuestions
from agent_monitor.local_executor import LocalReplyExecutor
from test_task_replies import SESSION, hub, send


TURN = "synthetic-turn"
ITEM = "synthetic-message"


def notification(method, item):
    return {"method": method, "params": {"threadId": SESSION, "turnId": TURN, "item": item}}


def question():
    return notification("item/completed", {"id": ITEM, "type": "agentMessage", "text": "",
        "questions": [{"title": "选一个颜色", "options": ["绿色", "蓝色"]}, {"title": "补充说明", "options": None}]})


def envelope(rows):
    return OPEN + "\n" + json.dumps(rows, ensure_ascii=False, separators=(",", ":")) + "\n" + CLOSE


class Wire:
    def __init__(self):
        self.incoming = queue.Queue()
        self.sent = queue.Queue()

    def next(self, wait=True):
        try:
            return self.incoming.get(timeout=2 if wait else .015)
        except queue.Empty:
            if wait:
                raise TimeoutError("no provider message")
            return None

    def send(self, message):
        self.sent.put(message)

    def queued_count(self):
        return self.incoming.qsize()


def test_running_codex_turn_routes_phone_selection_to_original_item(hub):
    app, client, headers, task_id, *_ = hub
    command, response = send(hub)
    assert response.status_code == 200
    app.state.task_replies._tick()
    service = app.state.task_replies.controls
    executor = LocalReplyExecutor(lambda _: None, interaction_handler=service.local_handler)
    run = SimpleNamespace(command_id=command["request_id"])
    wire = Wire()
    errors = []
    def run_turn():
        try:
            with CodexAsyncQuestions(run.command_id, SESSION, TURN, executor.interaction_handler) as questions:
                executor._codex_turn(wire, SimpleNamespace(session_id=SESSION), run, TURN, [], None, False, questions)
        except Exception as exc:
            errors.append(exc)

    thread = threading.Thread(target=run_turn)
    thread.start()
    wire.incoming.put(question())
    wire.incoming.put(question())  # Replayed source item must remain a single question card.
    try:
        until = time.monotonic() + 3
        while time.monotonic() < until:
            controls = client.get("/api/native/tasks/controls", params={"task_id": task_id}, headers=headers).json()
            if controls["pending"]:
                break
            time.sleep(.01)
        [prompt] = controls["pending"]
        assert [opt["label"] for opt in prompt["questions"][0]["options"]] == ["绿色", "蓝色"]
        assert prompt["questions"][1]["allows_text"]
        phone_answer = dict(task_id=task_id, request_id=prompt["id"], revision=prompt["revision"],
                            decision_id=str(uuid.uuid4()), decision="answer", answers=[
                                {"question_id": "q0", "choices": ["o1"]}, {"question_id": "q1", "text": "稍后"}])
        assert client.post("/api/native/tasks/controls/decision", json=phone_answer, headers=headers).status_code == 200
        steering = wire.sent.get(timeout=2)
        params = steering["params"]
        assert steering["method"] == "turn/steer"
        assert (params["threadId"], params["expectedTurnId"]) == (SESSION, TURN)
        assert params["input"] == [{"type": "text", "text": envelope([
            {"questionItemId": '["request_user_input_async","synthetic-message",0]', "question": "选一个颜色", "answer": "蓝色"},
            {"questionItemId": '["request_user_input_async","synthetic-message",1]', "question": "补充说明", "answer": "稍后"}])}]
        wire.incoming.put({"id": steering["id"], "result": {"turnId": TURN}})
        until = time.monotonic() + 2
        while time.monotonic() < until:
            replay = client.post("/api/native/tasks/controls/decision", json=phone_answer, headers=headers).json()
            if replay.get("state") == "acknowledged":
                break
            time.sleep(.01)
        assert replay["state"] == "acknowledged" and wire.sent.empty()
    finally:
        wire.incoming.put({"method": "turn/completed", "params": {"threadId": SESSION, "turn": {"id": TURN, "status": "completed"}}})
        thread.join(3)
    assert not thread.is_alive() and not errors


@pytest.mark.parametrize("change", ["desktop_answer", "changed_choices", "turn_ended"])
def test_late_phone_decision_is_not_steered(change):
    published = threading.Event()
    finished = []
    class Handler:
        def __call__(self, request, cancelled):
            published.set()
            cancelled.wait(2)
            return {"decision": "answer", "answers": [{"question_id": "q0", "choices": ["o1"]}, {"question_id": "q1", "text": "x"}]}

        def resolve(self, key, revision, state):
            finished.append(state)

    wire = Wire()
    with CodexAsyncQuestions("command", SESSION, TURN, Handler()) as questions:
        questions.next(wire, [question()])
        assert published.wait(1)
        if change == "desktop_answer":
            item = {"type": "userMessage", "content": [{"type": "text", "text": envelope([
                {"questionItemId": '["request_user_input_async","synthetic-message",0]', "question": "选一个颜色", "answer": "绿色"}])}]}
            questions.next(wire, [notification("item/completed", item)])
        elif change == "changed_choices":
            changed = question()
            changed["params"]["item"]["questions"][0]["options"] = ["新的选项"]
            questions.next(wire, [changed])
    questions._send_decisions(wire)
    assert wire.sent.empty() and set(finished) == {"cancelled"}


@pytest.mark.parametrize("response", ["rejected", "wrong_turn", "timeout"])
def test_steering_without_matching_acceptance_never_acknowledged_or_retried(response):
    finished = []
    class Handler:
        def __call__(self, request, cancelled):
            return {"decision": "answer", "answers": [{"question_id": "q0", "choices": ["o1"]},
                                                      {"question_id": "q1", "text": "test"}]}

        def resolve(self, key, revision, state):
            finished.append(state)

    wire = Wire()
    with CodexAsyncQuestions("command", SESSION, TURN, Handler()) as questions:
        questions.next(wire, [question()])
        until = time.monotonic() + 2
        while wire.sent.empty() and time.monotonic() < until:
            questions._send_decisions(wire)
            time.sleep(.01)
        rpc = wire.sent.get_nowait()
        if response == "timeout":
            next(iter(questions.pending.values())).public["expires_at"] = 0
            questions._send_decisions(wire)
            late = {"id": rpc["id"], "result": {"turnId": TURN}}
            questions.next(wire, [late])
        else:
            reply = {"error": {"code": -32600}} if response == "rejected" else {"result": {"turnId": "different"}}
            questions.next(wire, [{"id": rpc["id"], **reply}])
        questions.next(wire, [question()])
        questions._send_decisions(wire)
    assert wire.sent.empty() and finished == ["cancelled"]


@pytest.mark.parametrize("already_queued", ["only_deltas", "desktop_answer", "turn_ended"])
def test_busy_output_makes_progress_after_observing_already_queued_events(already_queued):
    class BusyWire(Wire):
        def next(self, wait=True):
            return self.incoming.get() if not self.incoming.empty() else {"method": "item/agentMessage/delta"}

    def choose(request, cancelled):
        return {"decision": "answer", "answers": [{"question_id": "q0", "choices": ["o0"]},
                                                  {"question_id": "q1", "text": "test"}]}
    wire = BusyWire()
    with CodexAsyncQuestions("command", SESSION, TURN, choose) as questions:
        questions.next(wire, [question()])
        until = time.monotonic() + 2
        while questions.decisions.empty() and time.monotonic() < until:
            time.sleep(.005)
        assert not questions.decisions.empty()
        for _ in range(5):
            wire.incoming.put({"method": "item/agentMessage/delta"})
        if already_queued == "turn_ended":
            wire.incoming.put({"method": "turn/completed", "params": {"threadId": SESSION, "turn": {"id": TURN}}})
        elif already_queued == "desktop_answer":
            item = {"type": "userMessage", "content": [{"type": "text", "text": envelope([
                {"questionItemId": '["request_user_input_async","synthetic-message",0]', "question": "选一个颜色", "answer": "蓝色"}])}]}
            wire.incoming.put(notification("item/completed", item))
        questions.next_check = 0
        for _ in range(12):
            # Keep adding output as we consume it. The queue never becomes empty.
            wire.incoming.put({"method": "item/agentMessage/delta"})
            if questions.next(wire, [])["method"] == "turn/completed":
                break
    assert wire.sent.qsize() == int(already_queued == "only_deltas")
