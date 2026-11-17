"""Authenticated, bounded decisions for requests on an owned live reply channel.

No request is reconstructed from a transcript. The connector must publish the
actual request while its claimed Monitor reply is still active. HTTP receipt is
not source acknowledgement; decisions are handed to the connector at most once.
"""
from __future__ import annotations

import hashlib
import json
import re
import threading
import time
from typing import Literal

from fastapi import HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator

from .session_controls import Answer, ModelCatalog, ModelSelection, Provider, Question, validate_answers, validate_selection
from .store import ONLINE_SECONDS

UUID = r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$"
OPEN = ("pending", "submitted")
MAX_REQUEST_SECONDS = 600
MAX_CATALOG_SECONDS = 300
CATALOG_RETRY_SECONDS = 60
MAX_LOCAL_CATALOG_DISCOVERIES = 2


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ChoiceInput(Input):
    id: str = Field(min_length=1, max_length=80)
    label: str = Field(min_length=1, max_length=240)
    description: str = Field(default="", max_length=1000)


class QuestionInput(Input):
    id: str = Field(min_length=1, max_length=80)
    text: str = Field(min_length=1, max_length=2000)
    options: list[ChoiceInput] = Field(default_factory=list, max_length=32)
    multiple: StrictBool = False
    allows_text: StrictBool = False


class AnswerInput(Input):
    question_id: str = Field(min_length=1, max_length=80)
    choices: list[str] = Field(default_factory=list, max_length=32)
    text: str | None = Field(default=None, min_length=1, max_length=4000)

    @model_validator(mode="after")
    def bounded_answer(self):
        if any(not item.strip() or len(item) > 80 for item in [self.question_id, *self.choices]):
            raise ValueError("invalid answer identity")
        if self.text is not None and any(ord(ch) < 32 and ch not in '\r\n\t' for ch in self.text):
            raise ValueError("invalid answer text")
        return self


class PendingInput(Input):
    id: str = Field(min_length=1, max_length=128)
    revision: str = Field(min_length=1, max_length=128)
    kind: Literal["question", "approval"]
    provider: Provider
    session_id: str = Field(min_length=1, max_length=200)
    turn_id: str = Field(min_length=1, max_length=200)
    title: str = Field(min_length=1, max_length=240)
    detail: str = Field(default="", max_length=4000)
    operation: str = Field(default="", max_length=8000)
    questions: list[QuestionInput] = Field(default_factory=list, max_length=16)
    expires_at: float = Field(gt=0, allow_inf_nan=False)

    @model_validator(mode="after")
    def bounded_shape(self):
        required = [self.id, self.revision, self.session_id, self.turn_id, self.title]
        for question in self.questions:
            required.extend((question.id, question.text))
            for option in question.options:
                required.extend((option.id, option.label))
        if any(not item.strip() for item in required):
            raise ValueError("blank request field")
        if self.kind == "question":
            questions = [Question.model_validate_json(q.model_dump_json()) for q in self.questions]
            if not questions or len({q.id for q in questions}) != len(questions):
                raise ValueError("invalid question set")
        elif self.questions or not self.operation.strip():
            raise ValueError("approval requires an operation and no answers")
        if len(self.model_dump_json().encode()) > 64 * 1024:
            raise ValueError("request too large")
        # These are displayed as plain text, never Markdown/HTML or a command.
        texts = [self.title, self.detail, self.operation]
        for q in self.questions:
            texts.append(q.text)
            for option in q.options:
                texts.extend((option.label, option.description))
        if any(any((ord(ch) < 32 and ch not in "\r\n\t") or ch in "\x7f\u202d\u202e" for ch in value) for value in texts):
            raise ValueError("invalid display text")
        return self


class DecisionInput(Input):
    task_id: str = Field(min_length=1, max_length=500)
    request_id: str = Field(min_length=1, max_length=128)
    revision: str = Field(min_length=1, max_length=128)
    decision_id: str = Field(pattern=UUID)
    decision: Literal["allow_once", "deny", "answer"]
    answers: list[AnswerInput] = Field(default_factory=list, max_length=16)


class CatalogInput(Input):
    source_id: str = Field(min_length=1, max_length=200)
    catalog: dict | None


class PublishInput(Input):
    command_id: str = Field(pattern=UUID)
    source_id: str = Field(min_length=1, max_length=200)
    request: PendingInput


class PollInput(Input):
    request_id: str = Field(min_length=1, max_length=128)
    revision: str = Field(min_length=1, max_length=128)


class ResolveInput(PollInput):
    state: Literal["forwarded", "acknowledged", "cancelled"]


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class LocalHandler:
    def __init__(self, service):
        self.service = service

    def __call__(self, request, cancelled):
        value = dict(request)
        command_id, source_id = value.pop("command_id", ""), value.pop("source_id", "")
        service = self.service
        with service.store.lock, service.store.db:
            row = service.store.db.execute("SELECT r.device_id FROM task_replies r JOIN devices d ON r.device_id=d.id WHERE r.id=? AND d.local=1", (command_id,)).fetchone()
            if row is None:
                raise ValueError("owned local command unavailable")
            device_id = row["device_id"]
        published = service.publish_request(device_id, command_id, source_id, value)
        try:
            while not cancelled.is_set() and not service.replies.stopping.is_set():
                result = service.poll_decision(device_id, published["id"], published["revision"])
                if result.get("decision") is not None:
                    return result["decision"]
                if result["state"] not in OPEN:
                    return None
                cancelled.wait(.2)
        except Exception:
            self.resolve(published["id"], published["revision"], "cancelled")
            raise
        self.resolve(published["id"], published["revision"], "cancelled")
        return None

    def resolve(self, request_id, revision, state):
        service = self.service
        with service.store.lock:
            rows = service.store.db.execute("SELECT i.device_id FROM task_interactions i JOIN devices d ON i.device_id=d.id WHERE i.id=? AND i.revision=? AND d.local=1", (request_id, revision)).fetchall()
            if len(rows) != 1:
                raise ValueError("ambiguous local request")
        return service.resolve(rows[0]["device_id"], request_id, revision, state)


class TaskInteractions:
    def __init__(self, store, replies):
        self.store, self.replies = store, replies
        self.catalog_provider = None  # Tests may inject; production discovery is lazy.
        self.catalogs = {}
        self.catalog_requests = {}
        self.catalog_attempts = {}
        self.local_catalog_discoveries = set()
        self.local_handler = LocalHandler(self)
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS task_interactions (
                    device_id TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,
                    command_id TEXT NOT NULL,reader_id TEXT NOT NULL,source_id TEXT NOT NULL,
                    provider TEXT NOT NULL,kind TEXT NOT NULL,turn_id TEXT NOT NULL,
                    payload TEXT NOT NULL,payload_hash TEXT NOT NULL,state TEXT NOT NULL,
                    expires_at REAL NOT NULL,created_at REAL NOT NULL,finished_at REAL,
                    decision_id TEXT,decision TEXT NOT NULL DEFAULT '',decision_hash TEXT NOT NULL DEFAULT '',delivered INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(device_id,id)
                );
                CREATE UNIQUE INDEX IF NOT EXISTS task_interaction_decision_unique ON task_interactions(decision_id) WHERE decision_id IS NOT NULL;
                CREATE INDEX IF NOT EXISTS task_interaction_command ON task_interactions(command_id,state);
            """)
            if 'decision_hash' not in {row['name'] for row in store.db.execute('PRAGMA table_info(task_interactions)')}:
                store.db.execute("ALTER TABLE task_interactions ADD COLUMN decision_hash TEXT NOT NULL DEFAULT ''")
            store.db.execute("UPDATE task_interactions SET state='cancelled',payload='',decision='',finished_at=? WHERE state IN ('pending','submitted')", (time.time(),))

    def _catalog(self, device_id, source_id):
        catalog = self.catalogs.get((device_id, source_id))
        if catalog and catalog["valid_until"] > time.time():
            return catalog
        self.catalogs.pop((device_id, source_id), None)
        return None

    def publish_catalog(self, device_id, source_id, value):
        with self.store.lock, self.store.db:
            _, _, device, task = self.replies._task(device_id + ":" + source_id)
            if device["archived"] or time.time() - device["last_seen"] >= ONLINE_SECONDS:
                raise HTTPException(409, "任务连接已变化")
            key = (device_id, source_id)
            self.catalog_requests.pop(key, None)
            if key in self.catalog_attempts:
                self.catalog_attempts[key] = (self.catalog_attempts[key][0], False)
            if value is None:
                self.catalogs.pop(key, None)
                return {"ok": True}
            if not isinstance(value, dict) or set(value) - {"provider", "catalog_revision", "models", "valid_until", "current_model", "current_effort", "collaboration_modes"}:
                raise HTTPException(422, "模型目录格式无效")
            try:
                catalog = ModelCatalog.model_validate_json(_canonical({"provider": value.get("provider"), "device_id": device_id,
                    "session_id": source_id.partition(":")[2], "revision": value.get("catalog_revision"),
                    "valid_until": value.get("valid_until"), "models": value.get("models"),
                    "collaboration_modes": value.get("collaboration_modes", [])}))
                if catalog.provider != task["tool"] or source_id.partition(":")[0] != catalog.provider:
                    raise ValueError()
                if not time.time() < catalog.valid_until <= time.time() + MAX_CATALOG_SECONDS:
                    raise ValueError()
                current = value.get("current_model")
                effort = value.get("current_effort")
                # Observed current IDs can be versioned aliases or retired
                # models. They never expand the selectable capability set.
                if current is not None and (not isinstance(current, str) or len(current) > 120 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]*(?:\[[A-Za-z0-9._-]+\])?', current)):
                    raise ValueError()
                if effort is not None and (not isinstance(effort, str) or not re.fullmatch(r'[a-z0-9_]{1,32}', effort)):
                    raise ValueError()
            except (ValueError, TypeError):
                raise HTTPException(422, "模型目录格式无效") from None
            self.catalogs[key] = {"provider": catalog.provider, "catalog_revision": catalog.revision,
                "valid_until": catalog.valid_until, "models": [item.model_dump(mode="json") for item in catalog.models],
                **({"collaboration_modes": list(catalog.collaboration_modes)} if catalog.collaboration_modes else {}),
                "current_model": current, "current_effort": effort}
            return {"ok": True}

    def validate_selection(self, device_id, source_id, selection):
        value = self._catalog(device_id, source_id)
        if value is None:
            raise HTTPException(409, "模型目录已过期，请刷新后重选")
        try:
            selection = selection if isinstance(selection, ModelSelection) else ModelSelection.model_validate(selection)
            catalog = ModelCatalog.model_validate_json(_canonical({"provider": value["provider"], "device_id": device_id,
                "session_id": source_id.partition(":")[2], "revision": value["catalog_revision"],
                "valid_until": value["valid_until"], "models": value["models"],
                "collaboration_modes": value.get("collaboration_modes", [])}))
            return validate_selection(selection, catalog, device_id=device_id, session_id=source_id.partition(":")[2], now=time.time())
        except (ValueError, TypeError):
            raise HTTPException(409, "回复设置已不可用，请刷新后重选") from None

    def _request_catalog(self, key, *, local):
        """Schedule while the authenticated reader operation still owns the Store lock."""
        if self.replies.stopping.is_set():
            return False
        now = time.time()
        if key in self.local_catalog_discoveries:
            return True
        last_attempt, pending = self.catalog_attempts.get(key, (0, False))
        if now - last_attempt < CATALOG_RETRY_SECONDS:
            return pending
        if local and len(self.local_catalog_discoveries) >= MAX_LOCAL_CATALOG_DISCOVERIES:
            # No unbounded backlog, and no cooldown for work we did not start.
            # A subsequent authenticated poll can claim the next available slot.
            return True
        self.catalog_attempts[key] = (now, True)
        if not local:
            self.catalog_requests[key] = now
            return True
        self.local_catalog_discoveries.add(key)
        try:
            threading.Thread(target=self._discover_local_catalog, args=(key, now),
                             name="monitor-model-catalog", daemon=True).start()
        except RuntimeError:
            self.local_catalog_discoveries.discard(key)
            self.catalog_attempts[key] = (now, False)
            return False
        return True

    def _discover_local_catalog(self, key, attempted_at):
        device_id, source_id = key
        try:
            # Let the initiating reader release its transaction first. Discovery
            # itself can neither hold the Store lock nor start a provider turn.
            with self.store.lock:
                if self.replies.stopping.is_set():
                    return
            if self.catalog_provider:
                value = self.catalog_provider(source_id)
            else:
                from .local_executor import discover_reply_catalog
                value = discover_reply_catalog(source_id, cancelled=self.replies.stopping)
            with self.store.lock:
                if not self.replies.stopping.is_set() and self.catalog_attempts.get(key) == (attempted_at, True):
                    self.publish_catalog(device_id, source_id, value)
        except Exception:
            # No raw program errors or account information become API text.
            with self.store.lock:
                if self.catalog_attempts.get(key) == (attempted_at, True):
                    self.catalog_attempts[key] = (attempted_at, False)
        finally:
            with self.store.lock:
                self.local_catalog_discoveries.discard(key)

    def take_catalog_requests(self, device_id):
        now = time.time()
        result = []
        for key, stamp in list(self.catalog_requests.items()):
            if stamp < now - 300:
                self.catalog_requests.pop(key, None)
            elif key[0] == device_id and len(result) < 4:
                result.append(key[1])
                self.catalog_requests.pop(key, None)
        return result

    def _live_command(self, row):
        if self.replies.stopping.is_set():
            return False
        command = self.store.db.execute("SELECT * FROM task_replies WHERE id=? AND device_id=? AND source_id=?", (row["command_id"], row["device_id"], row["source_id"])).fetchone()
        if not command or command["state"] not in ("dispatching", "running") or command["reader_id"] != row["reader_id"] or not self.replies._valid_reader(row["reader_id"]):
            return False
        try:
            _, _, device, _ = self.replies._task(row["device_id"] + ":" + row["source_id"])
            return not device["archived"] and time.time() - device["last_seen"] < ONLINE_SECONDS
        except HTTPException:
            return False

    def _refresh(self, row):
        if row["state"] in OPEN:
            state = "expired" if row["expires_at"] <= time.time() else "cancelled" if not self._live_command(row) else None
            if state:
                self.store.db.execute("UPDATE task_interactions SET state=?,payload='',decision='',finished_at=? WHERE device_id=? AND id=?", (state, time.time(), row["device_id"], row["id"]))
                row = self._row(row["device_id"], row["id"], row["revision"])
        return row

    def _row(self, device_id, request_id, revision):
        row = self.store.db.execute("SELECT * FROM task_interactions WHERE device_id=? AND id=? AND revision=?", (device_id, request_id, revision)).fetchone()
        if row is None:
            raise HTTPException(404, "待处理请求不存在")
        return row

    @staticmethod
    def _public(row):
        payload = json.loads(row["payload"]) if row["payload"] else {}
        return {"id": row["id"], "revision": row["revision"], "kind": row["kind"], "provider": row["provider"],
            "title": payload.get("title", "请求已结束"), "detail": payload.get("detail", ""), "operation": payload.get("operation", ""),
            "questions": payload.get("questions", []), "expires_at": row["expires_at"], "state": row["state"],
            "turn_id": row["turn_id"], "decision_id": row["decision_id"]}

    def publish_request(self, device_id, command_id, source_id, value):
        try:
            request = value if isinstance(value, PendingInput) else PendingInput.model_validate(value)
        except (ValueError, TypeError):
            raise HTTPException(422, "待处理请求格式无效") from None
        payload = request.model_dump_json()
        digest = hashlib.sha256(payload.encode()).hexdigest()
        with self.store.lock, self.store.db:
            command = self.store.db.execute("SELECT * FROM task_replies WHERE id=? AND device_id=? AND source_id=?", (command_id, device_id, source_id)).fetchone()
            if not command or command["state"] not in ("dispatching", "running"):
                raise HTTPException(409, "原会话控制通道不可用")
            if request.provider != command["tool"] or source_id != request.provider + ":" + request.session_id:
                raise HTTPException(409, "请求与原会话不匹配")
            binding = {"command_id": command_id, "device_id": device_id, "source_id": source_id, "reader_id": command["reader_id"]}
            if not self._live_command(binding):
                raise HTTPException(409, "原会话控制通道已失效")
            old = self.store.db.execute("SELECT * FROM task_interactions WHERE device_id=? AND id=?", (device_id, request.id)).fetchone()
            if old:
                if old["command_id"] != command_id or old["source_id"] != source_id or old["revision"] != request.revision or old["payload_hash"] != digest:
                    raise HTTPException(409, "请求编号已使用")
                return self._public(self._refresh(old))
            if not time.time() < request.expires_at <= time.time() + MAX_REQUEST_SECONDS:
                raise HTTPException(422, "请求有效期无效")
            if self.store.db.execute("SELECT COUNT(*) FROM task_interactions").fetchone()[0] >= 50000:
                raise HTTPException(429, "交互记录较多，暂时不能接收新请求")
            if self.store.db.execute("SELECT COUNT(*) FROM task_interactions WHERE command_id=? AND state IN ('pending','submitted')", (command_id,)).fetchone()[0] >= 8:
                raise HTTPException(429, "待处理请求较多")
            self.store.db.execute("""INSERT INTO task_interactions(device_id,id,revision,command_id,reader_id,source_id,provider,kind,turn_id,payload,payload_hash,state,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'pending',?,?)""", (device_id, request.id, request.revision, command_id, command["reader_id"], source_id, request.provider, request.kind, request.turn_id, payload, digest, request.expires_at, time.time()))
            return self._public(self._row(device_id, request.id, request.revision))

    def get(self, reader, task_id):
        device_id, source_id, device, task, reason = self.replies._base_capability(task_id, for_catalog=True)
        rows = self.store.db.execute("""SELECT * FROM task_interactions WHERE device_id=? AND source_id=? AND reader_id=?
            ORDER BY CASE WHEN state IN ('pending','submitted') THEN 0 ELSE 1 END,created_at DESC LIMIT 12""",
            (device_id, source_id, reader["id"])).fetchall()
        pending = [self._public(self._refresh(row)) for row in rows]
        catalog = None if reason else self._catalog(device_id, source_id)
        if reason:
            status = reason if reason in {"offline", "archived"} else ("pending" if reason == "reply_not_ready" else "unsupported")
        elif catalog and catalog["models"]:
            status = "ready"
        elif not catalog and self._request_catalog((device_id, source_id), local=bool(device["local"])):
            status = "pending"
        else:
            status = "unavailable"
        active = [item for item in pending if item["state"] == "pending"]
        return {"catalog": catalog, "catalog_status": status, "pending": pending, "capabilities": {"questions": any(item["kind"] == "question" for item in active),
                "approvals": any(item["kind"] == "approval" for item in active), "model_settings": bool(catalog and catalog["models"])},
            "reason": "" if active or catalog else "此会话暂无可在手机处理的请求"}

    def decide(self, reader, data):
        device_id, source_id, _, _ = self.replies._task(data.task_id)
        row = self._row(device_id, data.request_id, data.revision)
        if row["source_id"] != source_id or row["reader_id"] != reader["id"]:
            raise HTTPException(404, "待处理请求不存在")
        row = self._refresh(row)
        decision = {"decision_id": data.decision_id, "decision": data.decision,
                    "answers": [answer.model_dump() for answer in data.answers]}
        encoded = _canonical(decision)
        digest = hashlib.sha256(encoded.encode('utf-8')).hexdigest()
        if row["decision_id"] is not None:
            if row["decision_id"] == data.decision_id and row["decision_hash"] == digest:
                return self._public(row)
            raise HTTPException(409, "此请求已处理，请刷新状态")
        if row["state"] != "pending":
            raise HTTPException(409, "请求已过期或已取消")
        if self.store.db.execute("SELECT 1 FROM task_interactions WHERE decision_id=?", (data.decision_id,)).fetchone():
            raise HTTPException(409, "提交编号已使用")
        payload = json.loads(row["payload"])
        try:
            if row["kind"] == "approval":
                if data.decision not in ("allow_once", "deny") or data.answers:
                    raise ValueError()
            elif data.decision == "answer":
                questions = tuple(Question.model_validate_json(_canonical(q)) for q in payload["questions"])
                answers = tuple(Answer.model_validate_json(answer.model_dump_json()) for answer in data.answers)
                validate_answers(questions, answers)
            elif data.decision != "deny" or data.answers:
                raise ValueError()
        except (ValueError, TypeError):
            raise HTTPException(422, "答案与当前请求不匹配") from None
        self.store.db.execute("UPDATE task_interactions SET state='submitted',decision_id=?,decision=?,decision_hash=? WHERE device_id=? AND id=? AND state='pending'", (data.decision_id, encoded, digest, device_id, row["id"]))
        return self._public(self._row(device_id, row["id"], row["revision"]))

    def poll_decision(self, device_id, request_id, revision):
        with self.store.lock, self.store.db:
            row = self._refresh(self._row(device_id, request_id, revision))
            decision = None
            if row["state"] == "submitted" and not row["delivered"]:
                self.store.db.execute("UPDATE task_interactions SET delivered=1 WHERE device_id=? AND id=?", (device_id, request_id))
                decision = json.loads(row["decision"])
            return {"state": row["state"], "decision": decision}

    def resolve(self, device_id, request_id, revision, state):
        if state not in ("forwarded", "acknowledged", "cancelled"):
            raise HTTPException(422, "交互回执格式无效")
        with self.store.lock, self.store.db:
            row = self._refresh(self._row(device_id, request_id, revision))
            if row["state"] not in OPEN:
                return self._public(row)
            if state in ("forwarded", "acknowledged") and (row["state"] != "submitted" or not row["delivered"]):
                raise HTTPException(409, "尚未向原会话交付这个决定")
            self.store.db.execute("UPDATE task_interactions SET state=?,payload='',decision='',finished_at=? WHERE device_id=? AND id=?", (state, time.time(), device_id, request_id))
            return self._public(self._row(device_id, request_id, revision))

    def cancel_command(self, command_id):
        self.store.db.execute("UPDATE task_interactions SET state='cancelled',payload='',decision='',finished_at=? WHERE command_id=? AND state IN ('pending','submitted')", (time.time(), command_id))

    def expire_invalid(self):
        # Caller owns the store transaction. No provider I/O occurs here.
        for row in self.store.db.execute("SELECT * FROM task_interactions WHERE state IN ('pending','submitted')").fetchall():
            self._refresh(row)


def install_control_routes(app, store, perform):
    service = app.state.task_replies.controls

    @app.get("/api/native/tasks/controls")
    def get_controls(request: Request, task_id: str):
        # Authentication, rate limiting and the immediate view belong to one
        # request. Slow provider discovery finishes independently of the HTTP GET.
        return perform(request, lambda reader: service.get(reader, task_id))

    @app.post("/api/native/tasks/controls/decision")
    def decide(data: DecisionInput, request: Request):
        return perform(request, lambda reader: service.decide(reader, data), write=True)

    @app.post("/api/agent/controls/catalog")
    def catalog(data: CatalogInput, request: Request):
        return app.state.agent_operation(request, lambda device: service.publish_catalog(device, data.source_id, data.catalog))

    @app.post("/api/agent/controls/request")
    def publish(data: PublishInput, request: Request):
        return app.state.agent_operation(request, lambda device: service.publish_request(device, data.command_id, data.source_id, data.request))

    @app.post("/api/agent/controls/poll")
    def poll(data: PollInput, request: Request):
        return app.state.agent_operation(request, lambda device: service.poll_decision(device, data.request_id, data.revision))

    @app.post("/api/agent/controls/resolve")
    def resolve(data: ResolveInput, request: Request):
        return app.state.agent_operation(request, lambda device: service.resolve(device, data.request_id, data.revision, data.state))
