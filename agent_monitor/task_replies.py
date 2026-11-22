"""Authenticated, idempotent replies to an existing task on its owning computer."""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import threading
import time
from typing import Literal

from fastapi import HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .store import ONLINE_SECONDS, iso
from .failure_reasons import FAILURE_LABELS
from .session_controls import ModelSelection, reply_fingerprint

UUID_PATTERN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
LIVE = ("queued", "dispatching", "running")
FINAL = ("succeeded", "failed", "blocked", "uncertain")
REASONS = {
    "queued": "等待电脑接收", "running": "正在回复", "succeeded": "回复完成",
    "dispatching": "等待电脑确认",
    "failed": "回复未完成，请查看任务结果", "blocked": "需要在电脑端确认后继续",
    "uncertain": "执行结果尚未确认，请先查看任务，避免重复发送",
    "busy": "任务正在运行或等待确认", "offline": "电脑当前离线",
    "archived": "请先恢复此任务", "unsupported": "这台电脑尚未启用远程回复，请在电脑端检查回复设置",
    "reply_not_ready": "回复连接尚未就绪，请检查电脑端的回复设置与连接",
    "codex_missing": "电脑连接器未找到 Codex 命令程序，请在电脑上安装或配置后重试",
    "claude_missing": "电脑连接器未找到 Claude Code 命令程序，请在电脑上安装或配置后重试",
    "antigravity_missing": "电脑连接器未找到 Antigravity 命令程序，请在电脑上安装或配置后重试",
    "unavailable": "此会话暂时不能续接", "expired": "发送已过期，请确认任务后重新发送",
    "revoked": "账号连接已失效，本次回复已停止", "restarted": "服务曾中断，请核对任务后再发送",
    # Local continuation reports only these fixed diagnostics; never pass through
    # the executor's free-text reason or a provider's raw exception.
    "permissions": "无法保留这个会话的权限设置，请在电脑上继续",
    "changed": "任务状态或设置已变化，请刷新后重新发送",
    "cli": "电脑端未找到所需的命令程序，请检查安装后重试",
    "approval": "这一步需要在电脑端确认权限或补充信息",
    "declined": "已拒绝本次操作，本轮已停止",
    "settings_changed": "模型设置已过期或变化，请刷新后重新选择",
}
REASONS.update(FAILURE_LABELS)


class ReplyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_id: str = Field(min_length=1, max_length=500)
    request_id: str = Field(pattern=r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    text: str = Field(min_length=1, max_length=8000)
    selection: ModelSelection | None = None

    @field_validator("text")
    @classmethod
    def valid_text(cls, value):
        if not value.strip() or any(ord(c) < 32 and c not in "\r\n\t" for c in value):
            raise ValueError("回复内容无效")
        return value


class AgentPoll(BaseModel):
    model_config = ConfigDict(extra="forbid")
    supported_tools: list[Literal["codex", "claude", "antigravity"]] = Field(default_factory=list, max_length=3)


MODEL_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*(?:\[[A-Za-z0-9._-]+\])?\Z")
EFFORT_PATTERN = re.compile(r"^[a-z0-9_]{1,32}\Z")


def sanitize_applied_settings(value) -> dict[str, str] | None:
    if not isinstance(value, dict):
        return None
    cleaned: dict[str, str] = {}
    model = value.get("model")
    if isinstance(model, str) and 1 <= len(model) <= 120 and MODEL_ID_PATTERN.fullmatch(model):
        cleaned["model"] = model
    effort = value.get("effort")
    if isinstance(effort, str) and EFFORT_PATTERN.fullmatch(effort):
        cleaned["effort"] = effort
    tier = value.get("service_tier")
    if tier == "priority":
        cleaned["service_tier"] = "priority"
    mode = value.get("collaboration_mode")
    if mode == "plan":
        cleaned["collaboration_mode"] = "plan"
    return cleaned or None


class AgentUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    state: Literal["running", "succeeded", "failed", "blocked", "uncertain"]
    error_code: str = Field(default="", max_length=80, pattern=r"^[a-z_]*$")
    applied: dict[str, str] | None = None

    @field_validator("applied")
    @classmethod
    def valid_applied(cls, value):
        if value is None:
            return None
        if set(value) - {"model", "effort", "service_tier", "collaboration_mode"}:
            raise ValueError("无效的运行设置回执")
        cleaned = sanitize_applied_settings(value)
        if cleaned is None or set(cleaned) != set(value):
            raise ValueError("无效的运行设置回执")
        return cleaned


class TaskReplies:
    def __init__(self, store, *, local_enabled=False, inspector=None, executor_factory=None):
        self.store = store
        self.local_enabled = local_enabled
        self.inspector = inspector
        self.executor_factory = executor_factory
        self.executor = None
        self.stopping = threading.Event()
        self.lifecycle_lock = threading.RLock()
        self.closed = False
        self._capabilities = {}
        self._local_tools_cache: tuple[set[str], float] = (set(), 0.0)
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS task_replies (
                    id TEXT PRIMARY KEY, reader_id TEXT NOT NULL, device_id TEXT NOT NULL,
                    source_id TEXT NOT NULL, task_id TEXT NOT NULL, tool TEXT NOT NULL,
                    prompt TEXT NOT NULL, prompt_hash TEXT NOT NULL, state TEXT NOT NULL,
                    reason_code TEXT NOT NULL DEFAULT '', queued_at REAL NOT NULL,
                    started_at REAL, finished_at REAL, expires_at REAL NOT NULL, claimed_at REAL
                );
                CREATE INDEX IF NOT EXISTS task_replies_pending ON task_replies(device_id,state);
            """)
            if 'claimed_at' not in {r['name'] for r in store.db.execute('PRAGMA table_info(task_replies)')}:
                store.db.execute('ALTER TABLE task_replies ADD COLUMN claimed_at REAL')
            columns = {r['name'] for r in store.db.execute('PRAGMA table_info(task_replies)')}
            if 'selection' not in columns:
                store.db.execute("ALTER TABLE task_replies ADD COLUMN selection TEXT NOT NULL DEFAULT ''")
            if 'request_schema' not in columns:
                store.db.execute('ALTER TABLE task_replies ADD COLUMN request_schema INTEGER NOT NULL DEFAULT 1')
            if 'applied' not in columns:
                store.db.execute("ALTER TABLE task_replies ADD COLUMN applied TEXT NOT NULL DEFAULT ''")
            # Never replay a command after a hub restart; the tool may have received it.
            store.db.execute("UPDATE task_replies SET state='uncertain',reason_code='restarted',"
                             "prompt='',finished_at=? WHERE state IN ('queued','dispatching','running')", (time.time(),))
        from .task_interactions import TaskInteractions
        self.controls = TaskInteractions(store, self)

    def _task(self, task_id):
        device_id, sep, source_id = task_id.partition(":")
        if not sep:
            raise HTTPException(404, "任务不存在")
        row = self.store.db.execute("""SELECT t.payload,d.local,d.last_seen,
                COALESCE(a.archived,0) AS archived FROM tasks t JOIN devices d ON t.device_id=d.id
                LEFT JOIN task_archive a ON a.device_id=t.device_id AND a.source_id=t.source_id
                WHERE t.device_id=? AND t.source_id=?""", (device_id, source_id)).fetchone()
        if not row:
            raise HTTPException(404, "任务不存在或已不再同步")
        return device_id, source_id, row, json.loads(row["payload"])

    def _valid_reader(self, reader_id):
        return self.store.valid_full_reader(reader_id)

    @staticmethod
    def _public(row):
        value = {key: row[key] for key in ("id", "task_id", "state")}
        value["found"] = True
        value["reason"] = REASONS.get(row["reason_code"], REASONS[row["state"]])
        keys = row.keys()
        for key in ("queued_at", "claimed_at", "started_at", "finished_at"):
            value[key] = iso(row[key]) if key in keys and row[key] is not None else None
        if row["started_at"]:
            value["delivery_ms"] = max(0, round((row["started_at"] - row["queued_at"]) * 1000))
        if "selection" in keys and row["selection"]:
            try:
                parsed_sel = sanitize_applied_settings(json.loads(row["selection"]))
                if parsed_sel:
                    value["requested_selection"] = parsed_sel
            except (ValueError, TypeError):
                pass
        if "applied" in keys and row["applied"]:
            try:
                parsed_app = sanitize_applied_settings(json.loads(row["applied"]))
                if parsed_app:
                    value["applied"] = parsed_app
            except (ValueError, TypeError):
                pass
        return value

    def _local_supported_tools(self) -> set[str]:
        if not self.local_enabled:
            return set()
        if self.inspector is not None or self.executor_factory is not None:
            return {"codex", "claude", "antigravity"}
        now = time.monotonic()
        cached, stamp = self._local_tools_cache
        if stamp > 0 and now - stamp < 15.0:
            return cached
        try:
            from .local_executor import supported_tools
            found = set(supported_tools())
        except Exception:
            found = set()
        self._local_tools_cache = (found, now)
        return found

    def continuation_states(self, snapshot: dict) -> dict[str, str]:
        """Classify each live, unarchived task as continuable, computer_in_use, or view_only."""
        now = time.time()
        devices = {d["id"]: d for d in snapshot.get("devices", []) if isinstance(d, dict) and "id" in d}
        busy_devices = {
            r["device_id"]
            for r in self.store.db.execute(
                "SELECT DISTINCT device_id FROM task_replies WHERE state IN ('queued','dispatching','running')"
            ).fetchall()
        }
        local_tools = self._local_supported_tools() if any(d.get("local") and d.get("online") for d in devices.values()) else set()
        states: dict[str, str] = {}
        for task in snapshot.get("tasks", []):
            if not isinstance(task, dict) or task.get("archived") or task.get("stale"):
                continue
            dev = devices.get(task.get("device_id", ""))
            if not dev or not dev.get("online"):
                continue
            tool = task.get("tool", "")
            status = task.get("status", "")
            if tool not in {"codex", "claude", "antigravity"} or status not in {"running", "waiting", "completed", "idle", "error"}:
                states[task["id"]] = "view_only"
                continue
            if dev.get("local"):
                supported = self.local_enabled and tool in local_tools
            else:
                rem_tools, stamp = self._capabilities.get(dev["id"], ([], 0))
                supported = (now - stamp < ONLINE_SECONDS) and (tool in rem_tools)
            if not supported:
                states[task["id"]] = "view_only"
            elif status in {"running", "waiting"} or dev["id"] in busy_devices:
                states[task["id"]] = "computer_in_use"
            else:
                states[task["id"]] = "continuable"
        return states

    def _base_capability(self, task_id, *, for_catalog=False):
        device_id, source_id, row, task = self._task(task_id)
        reason = ""
        if task.get("tool") not in {"codex", "claude", "antigravity"}:
            reason = "unsupported"
        elif row["archived"]:
            reason = "archived"
        elif time.time() - row["last_seen"] >= ONLINE_SECONDS:
            reason = "offline"
        elif not for_catalog and task.get("status") not in {"completed", "idle", "error"}:
            reason = "busy"
        elif not for_catalog and self.store.db.execute("SELECT 1 FROM task_replies WHERE device_id=? AND state IN ('queued','dispatching','running')", (device_id,)).fetchone():
            reason = "busy"
        elif row["local"]:
            if not self.local_enabled:
                reason = "unsupported"
        else:
            supported, stamp = self._capabilities.get(device_id, ([], 0))
            if time.time() - stamp >= ONLINE_SECONDS:
                reason = "reply_not_ready"
            elif task.get("tool") not in supported:
                reason = {"codex": "codex_missing", "claude": "claude_missing",
                          "antigravity": "antigravity_missing"}.get(task.get("tool"), "unsupported")
        return device_id, source_id, row, task, reason

    def get(self, reader, task_id, request_id=None):
        if request_id:
            if not UUID_PATTERN.fullmatch(request_id):
                raise HTTPException(422, "发送编号格式无效")
            row = self.store.db.execute("SELECT * FROM task_replies WHERE id=? AND reader_id=? AND task_id=?",
                                        (request_id, reader["id"], task_id)).fetchone()
            if not row:
                return {"found": False, "state": "not_found"}
            return self._public(row)
        _, _, _, _, reason = self._base_capability(task_id)
        latest = self.store.db.execute("SELECT * FROM task_replies WHERE reader_id=? AND task_id=? ORDER BY queued_at DESC LIMIT 1",
                                       (reader["id"], task_id)).fetchone()
        return {"available": not reason, "reason": REASONS.get(reason, ""),
                "latest": self._public(latest) if latest else None}

    def enqueue(self, reader, data):
        prompt_hash = reply_fingerprint(data.text, data.selection)
        row = self.store.db.execute("SELECT * FROM task_replies WHERE id=?", (data.request_id,)).fetchone()
        if row:
            expected = prompt_hash if row['request_schema'] >= 2 else hashlib.sha256(data.text.encode('utf-8')).hexdigest()
            if (row["reader_id"] != reader["id"] or row["task_id"] != data.task_id or row["prompt_hash"] != expected
                    or (row['request_schema'] < 2 and data.selection is not None)):
                raise HTTPException(409, "发送编号已使用，请先查询原回复")
            return self._public(row)
        device_id, source_id, device, task, reason = self._base_capability(data.task_id)
        if reason:
            raise HTTPException(409, REASONS[reason])
        if data.selection is not None:
            self.controls.validate_selection(device_id, source_id, data.selection)
        # Keep request IDs as durable tombstones: an old mobile retry must never
        # become a new execution after result/history retention or a restart.
        if self.store.db.execute("SELECT COUNT(*) FROM task_replies").fetchone()[0] >= 50000:
            raise HTTPException(429, "发送记录较多，请稍后再试")
        now = time.time()
        self.store.db.execute("""INSERT INTO task_replies
            (id,reader_id,device_id,source_id,task_id,tool,prompt,prompt_hash,state,queued_at,expires_at,selection,request_schema)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,2)""", (data.request_id, reader["id"], device_id, source_id,
            data.task_id, task["tool"], data.text, prompt_hash, "queued", now, now + 120,
            data.selection.model_dump_json() if data.selection is not None else ''))
        return self._public(self.store.db.execute("SELECT * FROM task_replies WHERE id=?", (data.request_id,)).fetchone())

    def update(self, command_id, state, error_code="", *, device_id=None, applied=None):
        if state not in (*FINAL, "running"):
            return
        cleaned_applied = sanitize_applied_settings(applied)
        applied_json = json.dumps(cleaned_applied, separators=(",", ":"), sort_keys=True) if cleaned_applied else None
        with self.store.lock, self.store.db:
            row = self.store.db.execute("SELECT * FROM task_replies WHERE id=?", (command_id,)).fetchone()
            if not row or (device_id and row["device_id"] != device_id):
                raise HTTPException(404, "发送记录不存在")
            # An acknowledgement cannot resurrect a terminal request.
            if row["state"] not in LIVE:
                return self._public(row)
            if row["state"] not in ("dispatching", "running"):
                raise HTTPException(409, "电脑尚未领取此回复")
            reason = error_code if error_code in REASONS else ""
            if state == 'running':
                self.store.db.execute('UPDATE task_replies SET started_at=COALESCE(started_at,?) WHERE id=?', (time.time(), command_id))
            if applied_json is not None:
                self.store.db.execute("UPDATE task_replies SET applied=? WHERE id=?", (applied_json, command_id))
            self.store.db.execute("UPDATE task_replies SET state=?,reason_code=?,prompt='',finished_at=? WHERE id=?",
                                  (state, reason, time.time() if state in FINAL else None, command_id))
            if state in FINAL:
                self.controls.cancel_command(command_id)
            return self._public(self.store.db.execute("SELECT * FROM task_replies WHERE id=?", (command_id,)).fetchone())

    def _emit(self, event):
        try:
            self.update(event.get("command_id", event.get("id", "")), event["state"], event.get("error_code", ""),
                        applied=event.get("applied"))
        except (HTTPException, KeyError):
            pass

    def _claim(self, device_id):
        """Caller owns database lock; claim is committed before any external action."""
        row = self.store.db.execute("SELECT * FROM task_replies WHERE device_id=? AND state='queued' ORDER BY queued_at LIMIT 1", (device_id,)).fetchone()
        if not row:
            return None
        reason = ""
        if not self._valid_reader(row["reader_id"]):
            reason = "revoked"
        elif time.time() > row["expires_at"]:
            reason = "expired"
        else:
            try:
                _, _, device, task = self._task(row["task_id"])
                if device["archived"] or task.get("status") not in {"completed", "idle", "error"}:
                    reason = "busy"
                elif time.time() - device["last_seen"] >= ONLINE_SECONDS:
                    reason = "offline"
            except HTTPException:
                reason = "unavailable"
        selection = None
        if not reason and row['selection']:
            try:
                selection = ModelSelection.model_validate_json(row['selection'])
                self.controls.validate_selection(row['device_id'], row['source_id'], selection)
            except (HTTPException, ValueError):
                reason = 'settings_changed'
        if reason:
            self.store.db.execute("UPDATE task_replies SET state='blocked',reason_code=?,prompt='',finished_at=? WHERE id=?", (reason, time.time(), row["id"]))
            return None
        self.store.db.execute("UPDATE task_replies SET state='dispatching',claimed_at=?,prompt='' WHERE id=?", (time.time(), row["id"]))
        command = {"id": row["id"], "source_id": row["source_id"], "text": row["prompt"], "tool": row["tool"]}
        if selection is not None:
            command['selection'] = selection.model_dump(mode='json')
        return command

    def poll_agent(self, device_id, supported_tools):
        with self.store.lock, self.store.db:
            self._capabilities[device_id] = (list(set(supported_tools)), time.time())
            running = self.store.db.execute("SELECT id,reader_id FROM task_replies WHERE device_id=? AND state IN ('dispatching','running')", (device_id,)).fetchall()
            cancel_ids = [row["id"] for row in running if not self._valid_reader(row["reader_id"])]
            cancel_ids += [row["id"] for row in self.store.db.execute(
                "SELECT id FROM task_replies WHERE device_id=? AND state='blocked' AND reason_code='revoked' AND finished_at>?",
                (device_id, time.time() - 3600)).fetchall()]
            if running:
                return {"command": None, "cancel_ids": cancel_ids, 'catalog_requests': self.controls.take_catalog_requests(device_id)}
            command = self._claim(device_id)
            return {"command": command, "cancel_ids": cancel_ids, 'catalog_requests': self.controls.take_catalog_requests(device_id)}

    def _tick(self):
        if self.stopping.is_set():
            return
        with self.store.lock, self.store.db:
            self.controls.expire_invalid()
            if not self.store.db.execute("SELECT 1 FROM task_replies WHERE state IN ('queued','dispatching','running') LIMIT 1").fetchone():
                return
            now = time.time()
            self.store.db.execute("UPDATE task_replies SET state='blocked',reason_code='expired',prompt='',finished_at=? WHERE state='queued' AND expires_at < ?", (now, now))
            self.store.db.execute("UPDATE task_replies SET state='uncertain',prompt='',finished_at=? WHERE state='running' AND started_at < ?", (now, now - 3600))
            self.store.db.execute("UPDATE task_replies SET state='uncertain',prompt='',finished_at=? WHERE state='dispatching' AND claimed_at < ?", (now, now - 30))
            running = self.store.db.execute("SELECT * FROM task_replies WHERE state IN ('dispatching','running')").fetchall()
            canceled = [r["id"] for r in running if not self._valid_reader(r["reader_id"])]
            for command_id in canceled:
                self.store.db.execute("UPDATE task_replies SET state='blocked',reason_code='revoked',prompt='',finished_at=? WHERE id=?", (now, command_id))
            self.controls.expire_invalid()
            locals_ = [r["id"] for r in self.store.db.execute("SELECT id FROM devices WHERE local=1").fetchall()] if self.local_enabled else []
        if self.executor:
            for command_id in canceled:
                self.executor.cancel(command_id)
        for device_id in locals_:
            with self.store.lock, self.store.db:
                command = self._claim(device_id)
            if not command:
                continue
            with self.lifecycle_lock:
                if self.stopping.is_set():
                    self.update(command['id'], 'uncertain')
                    continue
                try:
                    self.update(command['id'], 'running')
                    if self.executor is None:
                        if self.executor_factory:
                            self.executor = self.executor_factory(self._emit)
                            if hasattr(self.executor, "report_applied"):
                                self.executor.report_applied = True
                        else:
                            from .local_executor import LocalReplyExecutor
                            self.executor = LocalReplyExecutor(
                                emit=self._emit,
                                interaction_handler=self.controls.local_handler,
                                report_applied=True,
                            )
                    settings = {'selection': command['selection']} if 'selection' in command else {}
                    result = self.executor.start(command["id"], command["source_id"], command["text"], **settings)
                    if isinstance(result, dict) and result.get("state") in FINAL:
                        self.update(command["id"], result["state"], result.get("error_code", ""),
                                    applied=result.get("applied"))
                except Exception:
                    # Do not reveal raw errors (which can contain prompt, path or credentials).
                    self.update(command["id"], "uncertain")

    async def run(self):
        try:
            while not self.stopping.is_set():
                await asyncio.to_thread(self._tick)
                await asyncio.sleep(0.25)
        finally:
            self.close()

    def close(self):
        self.stopping.set()
        with self.lifecycle_lock:
            if self.closed:
                return
            self.closed = True
            try:
                if self.executor:
                    self.executor.close()
            finally:
                with self.store.lock, self.store.db:
                    self.store.db.execute("UPDATE task_interactions SET state='cancelled',payload='',decision='',finished_at=? WHERE state IN ('pending','submitted')", (time.time(),))


def install_reply_routes(app, store, perform):
    service = app.state.task_replies

    @app.get("/api/native/tasks/reply")
    def get_reply(request: Request, task_id: str, request_id: str | None = None):
        result = perform(request, lambda reader: service.get(reader, task_id, request_id))
        if not request_id and result.get("available"):
            with store.lock:
                _, source_id, task, _ = service._task(task_id)
                local = task["local"]
            if local:
                try:
                    from .local_executor import inspect_reply_target
                    inspection = (service.inspector or inspect_reply_target)(source_id)
                    # Recheck identity, expiry and task lifecycle after filesystem I/O.
                    result = perform(request, lambda reader: service.get(reader, task_id))
                    if not inspection.get("available"):
                        result.update(available=False, reason=inspection.get("reason") or REASONS["unavailable"])
                except (OSError, ValueError):
                    result.update(available=False, reason=REASONS["unavailable"])
        return result

    @app.post("/api/native/tasks/reply")
    def send_reply(data: ReplyInput, request: Request):
        return perform(request, lambda reader: service.enqueue(reader, data), write=True)

    @app.post("/api/agent/replies/poll")
    def poll_reply(data: AgentPoll, request: Request):
        return app.state.agent_operation(request, lambda device_id: service.poll_agent(device_id, data.supported_tools))

    @app.post("/api/agent/replies/update")
    def update_reply(data: AgentUpdate, request: Request):
        return app.state.agent_operation(
            request,
            lambda device_id: service.update(data.id, data.state, data.error_code, device_id=device_id, applied=data.applied),
        )
