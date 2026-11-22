"""Relay Codex Desktop's structured agent-message questions on their originating turn."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import queue
import threading
import time

from .session_controls import Answer, Choice, Question, validate_answers


OPEN = "<send_user_message_question_reply>"
CLOSE = "</send_user_message_question_reply>"


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _hash(value):
    return hashlib.sha256(_json(value).encode()).hexdigest()


def _valid_text(value, limit):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= limit and "\0" not in value


def _answered_ids(item):
    if item.get("type") == "userMessage":
        content = item.get("content")
    elif item.get("type") == "steeringUserMessage" and (item.get("status") == "accepted" or item.get("steerAcknowledged") is True):
        content = item.get("input")
    else:
        return set()
    if not isinstance(content, list):
        return set()
    raw = "\n".join(part["text"] for part in content if isinstance(part, dict)
                    and part.get("type") == "text" and isinstance(part.get("text"), str)).strip()
    if not raw.startswith(OPEN) or not raw.endswith(CLOSE):
        return set()
    try:
        rows = json.loads(raw[len(OPEN):-len(CLOSE)])
    except (ValueError, RecursionError):
        return set()
    if isinstance(rows, dict):
        rows = [rows]
    if not isinstance(rows, list):
        return set()
    return {row["questionItemId"] for row in rows if isinstance(row, dict) and isinstance(row.get("questionItemId"), str)}


@dataclass
class _Pending:
    public: dict
    questions: tuple[Question, ...]
    item_ids: tuple[str, ...]
    cancelled: threading.Event
    rpc_id: str | None = None


class CodexAsyncQuestions:
    def __init__(self, command_id, session, turn, handler):
        self.command_id, self.session, self.turn, self.handler = command_id, session, turn, handler
        self.pending = {}
        self.seen = {}
        self.decisions = queue.SimpleQueue()
        self.next_check = time.monotonic() + .25
        self.events_before_send = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        for key in list(self.pending):
            self._finish(key, "cancelled")

    def _finish(self, key, state, *, outcome=""):
        prompt = self.pending.pop(key)
        prompt.cancelled.set()
        self._resolve(prompt, state, outcome=outcome)

    def _resolve(self, prompt, state, *, outcome=""):
        resolve = getattr(self.handler, "resolve", None)
        if resolve:
            try:
                if outcome:
                    try:
                        resolve(prompt.public["id"], prompt.public["revision"], state, outcome=outcome)
                        return
                    except TypeError:
                        pass
                resolve(prompt.public["id"], prompt.public["revision"], state)
            except Exception:
                pass

    def _publish(self, item):
        item_id, rows = item.get("id"), item.get("questions")
        if not _valid_text(item_id, 200) or not isinstance(rows, list) or not 1 <= len(rows) <= 16:
            return
        key = _hash([self.command_id, self.session, self.turn, "async-question", item_id])
        revision = _hash(rows)
        if item_id in self.seen:
            if self.seen[item_id] != revision and key in self.pending:
                self._finish(key, "cancelled")
            return
        if len(self.seen) >= 128 or len(self.pending) >= 8:
            return
        questions = []
        for index, row in enumerate(rows):
            if not isinstance(row, dict) or set(row) - {"title", "options"} or not _valid_text(row.get("title"), 2000):
                return
            options = row.get("options", [])
            if options is None:
                options = []
            if (not isinstance(options, list) or len(options) > 32 or any(not _valid_text(o, 240) for o in options)
                    or len(set(options)) != len(options)):
                return
            choices = tuple(Choice(id=f"o{i}", label=label) for i, label in enumerate(options))
            questions.append(Question(id=f"q{index}", text=row["title"], options=choices, allows_text=True))
        public = dict(id=key, revision=revision, provider="codex", kind="question", title="Codex 需要你的选择",
                      command_id=self.command_id, source_id="codex:" + self.session, session_id=self.session,
                      turn_id=self.turn, questions=[q.model_dump(mode="json") for q in questions],
                      detail="", operation="", expires_at=time.time() + 300)
        item_ids = tuple(_json(["request_user_input_async", item_id, i]) for i in range(len(rows)))
        prompt = _Pending(public, tuple(questions), item_ids, threading.Event())
        self.pending[key] = prompt
        self.seen[item_id] = revision

        def ask():
            if prompt.cancelled.is_set():
                return
            try:
                decision = self.handler(dict(public), prompt.cancelled)
            except Exception:
                decision = None
            if prompt.cancelled.is_set():
                # Publication on a remote hub can complete after cancellation.
                self._resolve(prompt, "cancelled")
                return
            self.decisions.put((key, decision))

        threading.Thread(target=ask, daemon=True, name="monitor-codex-question").start()

    def _observe(self, message):
        rpc_id = message.get("id")
        if isinstance(rpc_id, str) and "method" not in message:
            for key, prompt in self.pending.items():
                if prompt.rpc_id == rpc_id:
                    result = message.get("result")
                    accepted = isinstance(result, dict) and result.get("turnId") == self.turn and "error" not in message
                    self._finish(key, "acknowledged" if accepted else "cancelled",
                                 outcome="adopted" if accepted else "rejected")
                    break
            return
        params = message.get("params")
        if not isinstance(params, dict) or params.get("threadId") != self.session or params.get("turnId") != self.turn:
            return
        method, item = message.get("method"), params.get("item")
        if method not in {"item/started", "item/completed"} or not isinstance(item, dict):
            return
        if item.get("type") == "agentMessage" and method == "item/completed":
            self._publish(item)
        else:
            answered = _answered_ids(item)
            for key, prompt in list(self.pending.items()):
                if prompt.rpc_id is None and answered.intersection(prompt.item_ids):
                    self._finish(key, "cancelled", outcome="handled_on_desktop")

    def _send_decisions(self, transport):
        for key, prompt in list(self.pending.items()):
            if time.time() >= prompt.public["expires_at"]:
                self._finish(key, "cancelled")
        while not self.decisions.empty():
            key, decision = self.decisions.get()
            prompt = self.pending.get(key)
            if not prompt or prompt.rpc_id is not None:
                continue
            try:
                if not isinstance(decision, dict) or decision.get("decision") != "answer":
                    raise ValueError("no answer")
                answers = tuple(Answer.model_validate({**a, "choices": tuple(a.get("choices", []))}) for a in decision["answers"])
                validate_answers(prompt.questions, answers)
                indexed = {a.question_id: a for a in answers}
                rows = []
                for question, item_id in zip(prompt.questions, prompt.item_ids):
                    answer = indexed[question.id]
                    labels = {o.id: o.label for o in question.options}
                    value = answer.text if answer.text is not None else labels[answer.choices[0]]
                    rows.append({"questionItemId": item_id, "question": question.text, "answer": value})
            except (AttributeError, IndexError, KeyError, TypeError, ValueError):
                self._finish(key, "cancelled")
                continue
            prompt.rpc_id = "monitor-question-" + key
            transport.send({"id": prompt.rpc_id, "method": "turn/steer", "params": {
                "threadId": self.session, "expectedTurnId": self.turn, "clientUserMessageId": key,
                "input": [{"type": "text", "text": OPEN + "\n" + _json(rows) + "\n" + CLOSE}]}})

    def next(self, transport, buffered):
        while True:
            if buffered:
                message = buffered.pop(0)
            elif self.pending:
                # Process already delivered desktop answers and turn completion before sending.
                message = transport.next(wait=False)
                if message is None:
                    self._send_decisions(transport)
                    self.next_check = time.monotonic() + .25
                    self.events_before_send = None
                    continue
            else:
                message = transport.next()
            if self.handler:
                self._observe(message)
                if self.pending and time.monotonic() >= self.next_check:
                    # Process the bounded batch already waiting (including desktop replies and
                    # turn completion). Later output cannot keep extending that batch forever.
                    if self.events_before_send is None:
                        self.events_before_send = len(buffered) + transport.queued_count()
                    else:
                        self.events_before_send -= 1
                    if self.events_before_send <= 0 and message.get("method") != "turn/completed":
                        self.next_check = time.monotonic() + .25
                        self.events_before_send = None
                        self._send_decisions(transport)
            return message
