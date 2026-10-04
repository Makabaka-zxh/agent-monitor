"""Authenticated, read-only hub; one personal account, many viewing devices."""
from __future__ import annotations

import asyncio
import hmac
import ipaddress
import logging
import platform
import re
import time
import unicodedata
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.middleware.gzip import GZipMiddleware

from .store import SESSION_SECONDS, PairingRequestError, Store, iso
from .profile import AVATAR_DATA_URL_LENGTH, normalize_avatar

COOKIE = "agent_monitor_session"
logger = logging.getLogger("agent_monitor")


class Login(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=12, max_length=128)
    device_name: str = Field(default="浏览设备", min_length=1, max_length=60)

    @field_validator("username", "device_name")
    @classmethod
    def nonblank(cls, value, info):
        value = value.strip()
        if len(value) < (3 if info.field_name == "username" else 1):
            raise ValueError("名称长度不足")
        return value


class Preferences(BaseModel):
    tool_filter: Literal["all", "codex", "claude"] | None = None
    sync_output: bool | None = None


class ProfileChanges(BaseModel):
    model_config = ConfigDict(extra="forbid")
    display_name: str | None = Field(default=None, min_length=1, max_length=40)
    avatar: str | None = Field(default=None, max_length=AVATAR_DATA_URL_LENGTH)

    @field_validator("display_name")
    @classmethod
    def valid_name(cls, value):
        if value is None or not value.strip() or any(unicodedata.category(c) in {"Cc", "Cf"} for c in value):
            raise ValueError("昵称格式无效")
        return value.strip()

    @field_validator("avatar")
    @classmethod
    def valid_avatar(cls, value):
        if value is None:
            raise ValueError("请使用空字符串移除头像")
        return normalize_avatar(value)


class ArchiveChanges(BaseModel):
    model_config = ConfigDict(extra="forbid")
    archived: StrictBool


class QRPairingStart(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=60)
    platform: str = Field(min_length=1, max_length=60)

    @field_validator("name", "platform")
    @classmethod
    def valid_name(cls, value):
        if not value.strip() or any(unicodedata.category(c) in {"Cc", "Cf"} for c in value):
            raise ValueError("电脑名称或平台格式无效")
        return value.strip()


class QRPairingPoll(BaseModel):
    model_config = ConfigDict(extra="forbid")
    poll_secret: str = Field(min_length=32, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class Registration(BaseModel):
    code: str = Field(min_length=8, max_length=40)
    name: str = Field(min_length=1, max_length=60)
    platform: str = Field(min_length=1, max_length=60)


class Task(BaseModel):
    id: str = Field(min_length=1, max_length=200)
    tool: Literal["codex", "claude"]
    title: str = Field(min_length=1, max_length=200)
    status: Literal["running", "waiting", "completed", "error", "unknown", "idle"]
    updated_at: str
    preview: str = Field(default="", max_length=500)
    output: str = Field(default="", max_length=12000)
    final_result_id: str = Field(default="", max_length=64)
    final_result_at: str = Field(default="", max_length=80)
    project: str = Field(default="", max_length=120)
    status_source: Literal["local_log", "hook"] = "local_log"

    @field_validator("updated_at")
    @classmethod
    def timestamp(cls, value):
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("时间必须带时区")
        stamp = min(parsed.timestamp(), time.time())
        return iso(stamp)


class Source(BaseModel):
    tool: Literal["codex", "claude"]
    available: bool
    mode: str = Field(max_length=60)
    detail: str = Field(default="", max_length=500)


class Heartbeat(BaseModel):
    tasks: list[Task] = Field(max_length=100)
    sources: list[Source] = Field(max_length=10)


class Attempts:
    def __init__(self):
        self.entries = defaultdict(deque)

    def check(self, key, limit, window):
        now = time.monotonic()
        # Bound memory from clients rotating addresses.
        if len(self.entries) > 5000:
            self.entries = defaultdict(deque, {k: v for k, v in self.entries.items() if v and v[-1] > now - 600})
        queue = self.entries[key]
        while queue and queue[0] < now - window:
            queue.popleft()
        if len(queue) >= limit:
            raise HTTPException(429, "尝试过于频繁，请稍后再试", headers={"Retry-After": str(window)})
        queue.append(now)


def create_app(state_dir: Path | str, *, collect_local=False, public_url: str | None = None, allowed_hosts=None,
               request_diagnostics: bool | None = None):
    state_dir = Path(state_dir)
    store = Store(state_dir / "monitor.sqlite3")
    from .task_replies import TaskReplies
    from .task_results import TaskResults
    replies = TaskReplies(store, local_enabled=collect_local)
    results = TaskResults(store)
    from .usage_service import UsageService
    usage = UsageService(state_dir, store, collect_local)
    attempts = Attempts()
    from .request_diagnostics import RequestTimingMiddleware, RequestTimings, configured
    request_timings = RequestTimings(state_dir, enabled=configured(request_diagnostics))
    secure_cookie = bool(public_url and public_url.startswith("https://"))

    local_id = None

    def collect_once():
        nonlocal local_id
        try:
            from .collector import collect_snapshot
            if local_id is None:
                local_id = store.ensure_local(platform.node() or "这台电脑", platform.system())
            snapshot = collect_snapshot(include_output=store.preferences()["sync_output"])
            parsed = Heartbeat.model_validate(snapshot)
            store.ingest(local_id, [t.model_dump() for t in parsed.tasks], [s.model_dump() for s in parsed.sources])
        except Exception as exc:
            # Handle failures before they reach the future: a cancelled shield
            # may otherwise report the raw worker exception through asyncio.
            logger.warning("Local collection unavailable (%s)", type(exc).__name__)

    async def collect_loop():
        while True:
            # Preferences and ingest also acquire the Store's threading lock.
            # Keep the whole step off the event loop, not only the log scan.
            worker = asyncio.get_running_loop().run_in_executor(None, collect_once)
            cancelled = False
            while True:
                try:
                    await asyncio.shield(worker)
                except asyncio.CancelledError:
                    # Cancelling an await cannot stop its Python worker thread.
                    # Drain this step before lifespan closes the Store, even if
                    # shutdown sends another cancellation while it is finishing.
                    cancelled = True
                    if worker.cancelled():
                        raise
                    continue
                break
            if cancelled:
                raise asyncio.CancelledError
            await asyncio.sleep(5)

    @asynccontextmanager
    async def lifespan(app):
        collector = asyncio.create_task(collect_loop()) if collect_local else None
        reply_worker = asyncio.create_task(replies.run())
        usage_worker = asyncio.create_task(usage.run())
        claude_quota_worker = asyncio.create_task(usage.run_claude_quota())
        request_timings.start()
        try:
            yield
        finally:
            try:
                usage_worker.cancel()
                claude_quota_worker.cancel()
                try:
                    await usage_worker
                except asyncio.CancelledError:
                    pass
                try:
                    await claude_quota_worker
                except asyncio.CancelledError:
                    pass
                reply_worker.cancel()
                try:
                    await reply_worker
                except asyncio.CancelledError:
                    pass
                if collector:
                    collector.cancel()
                    try:
                        await collector
                    except asyncio.CancelledError:
                        pass
                usage.close()
                store.close()
            finally:
                await asyncio.to_thread(request_timings.close)

    app = FastAPI(title="Agent Monitor", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.store = store
    app.state.task_replies = replies
    app.state.task_results = results
    app.state.usage = usage
    app.state.request_timings = request_timings
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=allowed_hosts or ["localhost", "127.0.0.1", "[::1]", "testserver"])
    # Android decodes negotiated gzip automatically; file downloads explicitly
    # request identity so their byte counts and SHA256 remain exact.
    app.add_middleware(GZipMiddleware, minimum_size=1024, compresslevel=4)

    @app.exception_handler(RequestValidationError)
    async def invalid_input(request, exc):
        # Framework validation errors can echo submitted passwords or payloads.
        return JSONResponse({"detail": "输入格式不正确，请检查名称、密码长度和数据格式"}, status_code=422)

    @app.exception_handler(PairingRequestError)
    async def pairing_error(request, exc):
        return JSONResponse({"detail": str(exc)}, status_code=exc.status)

    @app.middleware("http")
    async def bounds_and_headers(request: Request, call_next):
        if request.method in {"POST", "PUT", "PATCH"}:
            size, chunks = 0, []
            async for chunk in request.stream():
                size += len(chunk)
                if size > 2 * 1024 * 1024:
                    return JSONResponse({"detail": "提交的数据过大"}, status_code=413)
                chunks.append(chunk)
            request._body = b"".join(chunks)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' https://cdn.jsdelivr.net; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    def same_origin(request: Request):
        origin = request.headers.get("origin")
        expected = public_url.rstrip("/") if public_url else str(request.base_url).rstrip("/")
        if origin and origin != expected:
            raise HTTPException(403, "请从本应用页面操作")
        if request.headers.get("sec-fetch-site") == "cross-site":
            raise HTTPException(403, "请从本应用页面操作")

    def authenticated(request: Request):
        session = store.session(request.cookies.get(COOKIE))
        if not session:
            raise HTTPException(401, "请先登录你的 App 账号")
        if request.method not in {"GET", "HEAD", "OPTIONS"}:
            same_origin(request)
            if not hmac.compare_digest(request.headers.get("x-csrf-token", ""), session["csrf"]):
                raise HTTPException(403, "登录验证已失效，请刷新后重试")
        return session

    def issue_session(response, credentials):
        token, csrf = store.new_session(credentials.device_name)
        response.set_cookie(COOKIE, token, max_age=SESSION_SECONDS, httponly=True, secure=secure_cookie, samesite="strict", path="/")
        return {"user": store.profile(), "csrf_token": csrf}

    @app.get("/api/health")
    def health():
        return {"ok": True, "version": "0.1.0"}

    @app.get("/api/bootstrap")
    def bootstrap():
        return {"needs_setup": not store.has_account(), "google_login": google_enabled()}

    @app.post("/api/setup")
    def setup(credentials: Login, request: Request, response: Response):
        if public_url:
            raise HTTPException(403, "公开服务请先在服务主机运行账号初始化命令")
        same_origin(request)
        try:
            local = ipaddress.ip_address(request.client.host).is_loopback
        except ValueError:
            local = False
        if not local:
            raise HTTPException(403, "请先在运行同步服务的电脑上建立账号")
        attempts.check((request.client.host, "login"), 8, 60)
        try:
            store.setup(credentials.username, credentials.password)
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None
        return issue_session(response, credentials)

    @app.post("/api/login")
    def login(credentials: Login, request: Request, response: Response):
        same_origin(request)
        attempts.check((request.client.host, "login"), 8, 60)
        if not store.verify_password(credentials.username, credentials.password):
            raise HTTPException(401, "账号或密码不正确")
        return issue_session(response, credentials)

    @app.get("/api/me")
    def me(session=Depends(authenticated)):
        return {"user": store.profile(), "csrf_token": session["csrf"], "preferences": store.preferences(), "sync_mode": "self_hosted"}

    @app.patch("/api/profile")
    def profile(changes: ProfileChanges, session=Depends(authenticated)):
        return {"user": store.update_profile(changes.model_dump(exclude_unset=True))}

    @app.post("/api/logout")
    def logout(response: Response, session=Depends(authenticated)):
        store.revoke_session(session["id"])
        response.delete_cookie(COOKIE, path="/", httponly=True, secure=secure_cookie, samesite="strict")
        return {"ok": True}

    @app.get("/api/snapshot")
    def snapshot(session=Depends(authenticated)):
        return store.snapshot()

    @app.patch("/api/tasks/{task_id:path}/archive")
    @app.post("/api/tasks/{task_id:path}/archive")
    def archive(task_id: str, changes: ArchiveChanges, session=Depends(authenticated)):
        if len(task_id) > 500:
            raise HTTPException(404, "任务不存在或已不再同步")
        try:
            return store.archive_task(task_id, changes.archived)
        except ValueError as exc:
            raise HTTPException(404, str(exc)) from None

    @app.patch("/api/preferences")
    def preferences(changes: Preferences, session=Depends(authenticated)):
        return store.update_preferences(changes.model_dump(exclude_none=True))

    @app.get("/api/sessions")
    def sessions(session=Depends(authenticated)):
        return {"sessions": store.sessions(session["id"])}

    @app.delete("/api/sessions/{session_id}")
    def revoke_session(session_id: str, session=Depends(authenticated)):
        store.revoke_session(session_id)
        return {"ok": True}

    @app.post("/api/pairing")
    def pairing(session=Depends(authenticated)):
        return store.create_pairing()

    def qr_id(request_id: str):
        if not re.fullmatch(r"[A-Za-z0-9_-]{32}", request_id):
            raise HTTPException(404, "配对请求不存在")
        return request_id

    @app.post("/api/agent/pairing/start")
    def qr_start(payload: QRPairingStart, request: Request):
        same_origin(request)
        if not public_url or urlsplit(public_url).scheme != "https":
            raise HTTPException(503, "扫码配对需要先配置服务的 HTTPS 公开地址")
        if not store.has_account():
            raise HTTPException(409, "请先在服务电脑建立账号")
        attempts.check((request.client.host, "qr-start"), 5, 60)
        result = store.create_qr_pairing(payload.name, payload.platform)
        result["verification_url"] = public_url.rstrip("/") + "/#/pairing-confirm/" + result["request_id"]
        return result

    @app.get("/api/pairing/requests/{request_id}")
    def qr_details(request_id: str, session=Depends(authenticated)):
        return store.qr_pairing_details(qr_id(request_id))

    @app.post("/api/pairing/requests/{request_id}/approve")
    def qr_approve(request_id: str, session=Depends(authenticated)):
        return store.decide_qr_pairing(qr_id(request_id), True)

    @app.post("/api/pairing/requests/{request_id}/reject")
    def qr_reject(request_id: str, session=Depends(authenticated)):
        return store.decide_qr_pairing(qr_id(request_id), False)

    @app.post("/api/agent/pairing/{request_id}/poll")
    def qr_poll(request_id: str, payload: QRPairingPoll, request: Request):
        request_id = qr_id(request_id)
        same_origin(request)
        attempts.check((request.client.host, "qr-poll"), 200, 60)
        attempts.check((request_id, "qr-poll-request"), 30, 60)
        return store.poll_qr_pairing(request_id, payload.poll_secret)

    @app.delete("/api/devices/{device_id}")
    def remove_device(device_id: str, session=Depends(authenticated)):
        try:
            store.remove_device(device_id)
            usage.refresh()
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from None
        return {"ok": True}

    @app.post("/api/agent/register")
    def register(credentials: Registration, request: Request):
        attempts.check((request.client.host, "pair"), 10, 300)
        try:
            return store.register(credentials.code, credentials.name, credentials.platform)
        except ValueError as exc:
            raise HTTPException(401, str(exc)) from None

    @app.post("/api/agent/heartbeat")
    def heartbeat(payload: Heartbeat, request: Request):
        authorization = request.headers.get("authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else ""
        device_id = store.agent_device(token)
        if not device_id:
            raise HTTPException(401, "设备连接已失效，请重新配对")
        store.ingest(device_id, [t.model_dump() for t in payload.tasks], [s.model_dump() for s in payload.sources])
        return {"sync_output": store.preferences()["sync_output"]}

    from .google_login import install_google_routes
    google_enabled = install_google_routes(app, store, state_dir, public_url, authenticated, same_origin)

    from .native_access import install_native_routes
    install_native_routes(app, store, public_url, authenticated, same_origin, google_enabled)

    def agent_operation(request, operation):
        same_origin(request)
        attempts.check((request.client.host, "agent-results"), 600, 60)
        authorization = request.headers.get("authorization", "")
        token = authorization[7:] if authorization.startswith("Bearer ") else ""
        with store.lock, store.db:
            device_id = store.agent_device(token)
            if not device_id:
                raise HTTPException(401, "电脑连接已失效")
            return operation(device_id)

    from .task_results import install_agent_result_routes
    app.state.agent_operation = agent_operation
    install_agent_result_routes(app, results, agent_operation)

    @app.post("/api/agent/usage")
    def agent_usage(payload: dict, request: Request):
        # The ledger independently validates every numeric field and opaque ID.
        # This endpoint grants neither file access nor result/output permission.
        device_id = agent_operation(request, lambda identity: identity)
        # Do not hold the task Store lock while the independent ledger writes.
        # Removed-device records are excluded by the source filter on reads.
        return usage.ledger.ingest_batch(device_id, payload)

    web = Path(__file__).resolve().parents[1] / "web"
    if web.is_dir():
        app.mount("/", StaticFiles(directory=str(web), html=True), name="web")
    # Outermost user middleware sees arrival before the existing request policy,
    # then observes the unchanged ASGI response. It never enables access logging.
    app.add_middleware(RequestTimingMiddleware, sink=request_timings)
    return app
