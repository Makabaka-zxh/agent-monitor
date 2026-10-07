"""Explicit browser consent for PKCE-bound native readers and full-app handoff.

Reader credentials use separate storage and never authenticate web or agent APIs.
Only credential digests are persisted; the verifier stays on the native device.
"""
from __future__ import annotations

import base64
from collections import OrderedDict, deque
import hashlib
import hmac
import re
import secrets
import threading
import time
import unicodedata
import uuid
from typing import Literal
from urllib.parse import urlsplit

from fastapi import Depends, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .store import digest, iso

PAIRING_SECONDS = 600
READER_SECONDS = 30 * 24 * 60 * 60
WEB_TICKET_SECONDS = 60
MAX_PAIRINGS = 100
MAX_READERS = 50
READER_REQUESTS_PER_IP = 240
ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{32}\Z")
TOKEN_PATTERN = re.compile(r"nrd_[A-Za-z0-9_-]{43}\Z")
SCOPES = {"read_only": ["task_status"], "full_app": ["task_status", "workspace_read", "workspace_write"]}
APP_COOKIE = "agent_monitor_session"


def challenge_for(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode("ascii")


class NativeStart(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_name: str = Field(min_length=1, max_length=60)
    code_challenge: str = Field(min_length=43, max_length=43, pattern=r"^[A-Za-z0-9_-]+$")
    mode: Literal["read_only", "full_app"] = "read_only"

    @field_validator("device_name")
    @classmethod
    def valid_name(cls, value):
        if not value.strip() or any(unicodedata.category(c) in {"Cc", "Cf"} for c in value):
            raise ValueError("设备名称格式无效")
        return value.strip()

    @field_validator("code_challenge")
    @classmethod
    def canonical_challenge(cls, value):
        decoded = base64.urlsafe_b64decode(value + "=")
        if len(decoded) != 32 or base64.urlsafe_b64encode(decoded).rstrip(b"=").decode("ascii") != value:
            raise ValueError("连接验证格式无效")
        return value


class NativePoll(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code_verifier: str = Field(min_length=43, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class NativeApproval(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["read_only", "full_app"] | None = None
    consent_version: str | None = Field(default=None, max_length=40)


class WebSessionExchange(NativePoll):
    ticket: str = Field(min_length=47, max_length=47, pattern=r"^nwt_[A-Za-z0-9_-]{43}$")


class RateLimit:
    """Small, thread-safe, bounded cache; global buckets survive key rotation."""
    def __init__(self):
        self.lock = threading.Lock()
        self.entries = OrderedDict()

    def check(self, key, limit, window=60):
        now = time.monotonic()
        with self.lock:
            queue = self.entries.pop(key, deque())
            while queue and queue[0] <= now - window:
                queue.popleft()
            self.entries[key] = queue
            while len(self.entries) > 4096:
                self.entries.popitem(last=False)
            if len(queue) >= limit:
                retry = max(1, int(window - (now - queue[0])) + 1)
                raise HTTPException(429, "连接请求过于频繁，请稍后再试", headers={"Retry-After": str(retry)})
            queue.append(now)


class NativeAccess:
    def __init__(self, store):
        self.store = store
        self.limits = RateLimit()
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS native_pairings (
                    id TEXT PRIMARY KEY, challenge_hash TEXT NOT NULL,
                    name TEXT NOT NULL, expires_at REAL NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending'
                        CHECK(status IN ('pending','approved','rejected','consumed')),
                    parent_session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL
                );
                CREATE TABLE IF NOT EXISTS native_readers (
                    id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL, last_seen REAL NOT NULL, expires_at REAL NOT NULL,
                    parent_session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS native_readers_parent ON native_readers(parent_session_id);
            """)
        with store.lock, store.db:
            store.db.execute("BEGIN IMMEDIATE")
            for table in ("native_pairings", "native_readers"):
                columns = {row[1] for row in store.db.execute(f"PRAGMA table_info({table})")}
                if "mode" not in columns:
                    store.db.execute(f"ALTER TABLE {table} ADD COLUMN mode TEXT NOT NULL DEFAULT 'read_only' CHECK(mode IN ('read_only','full_app'))")
            columns = {row[1] for row in store.db.execute("PRAGMA table_info(sessions)")}
            if "native_reader_id" not in columns:
                # Row links are acyclic: parent browser -> reader -> new session.
                # Both deletions cascade without creating a row that points back
                # to itself. Old sessions keep a NULL native link.
                store.db.execute("ALTER TABLE sessions ADD COLUMN native_reader_id TEXT REFERENCES native_readers(id) ON DELETE CASCADE")
            store.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS session_native_reader ON sessions(native_reader_id) WHERE native_reader_id IS NOT NULL")
            store.db.execute("""CREATE TABLE IF NOT EXISTS native_web_tickets (
                ticket_hash TEXT PRIMARY KEY,
                reader_id TEXT NOT NULL UNIQUE REFERENCES native_readers(id) ON DELETE CASCADE,
                proof_hash TEXT NOT NULL, expires_at REAL NOT NULL
            )""")

    def _clean(self, now):
        self.store.db.execute("DELETE FROM native_pairings WHERE expires_at<=?", (now,))
        self.store.db.execute("""DELETE FROM native_readers WHERE expires_at<=? OR
            parent_session_id NOT IN (SELECT id FROM sessions WHERE expires_at>?)""", (now, now))
        for reader in self.store.db.execute("SELECT id,parent_session_id FROM native_readers").fetchall():
            if not self.store.valid_session_id(reader["parent_session_id"], now):
                self.store.db.execute("DELETE FROM native_readers WHERE id=?", (reader["id"],))
        self.store.db.execute("DELETE FROM native_web_tickets WHERE expires_at<=?", (now,))

    def _parent(self, session_id, now):
        return self.store.valid_session_id(session_id, now)

    def start(self, name, challenge, mode="read_only"):
        if mode not in SCOPES:
            raise HTTPException(422, "连接权限无效")
        request_id, now = secrets.token_urlsafe(24), time.time()
        with self.store.lock, self.store.db:
            self._clean(now)
            if self.store.db.execute("SELECT COUNT(*) FROM native_pairings").fetchone()[0] >= MAX_PAIRINGS:
                raise HTTPException(429, "待处理连接过多，请稍后再试", headers={"Retry-After": "60"})
            self.store.db.execute("INSERT INTO native_pairings(id,challenge_hash,name,expires_at,mode) VALUES (?,?,?,?,?)",
                                  (request_id, digest(challenge), name, now + PAIRING_SECONDS, mode))
        return {"request_id": request_id, "expires_at": iso(now + PAIRING_SECONDS), "interval": 3}

    def _pairing(self, request_id, *, polling=False):
        row = self.store.db.execute("SELECT * FROM native_pairings WHERE id=?", (request_id,)).fetchone()
        if not row:
            raise HTTPException(410 if polling else 404, "连接请求不存在或已过期，请在原生应用重新连接")
        if row["expires_at"] <= time.time():
            raise HTTPException(410, "连接请求已过期，请在原生应用重新连接")
        return row

    def details(self, request_id):
        with self.store.lock:
            row = self._pairing(request_id)
            return {"request_id": row["id"], "device_name": row["name"],
                    "status": row["status"], "expires_at": iso(row["expires_at"]),
                    "mode": row["mode"], "scopes": SCOPES[row["mode"]],
                    "consent_version": row["mode"] + "_v1"}

    def decide(self, request_id, session_id, approve, consent=None):
        with self.store.lock, self.store.db:
            self.store.db.execute("UPDATE native_pairings SET status=status WHERE id=?", (request_id,))
            if not self._parent(session_id, time.time()):
                raise HTTPException(401, "请重新登录后确认连接")
            row = self._pairing(request_id)
            if row["status"] != "pending":
                raise HTTPException(409, "该连接已经处理，请返回原生应用查看")
            if approve:
                if row["mode"] == "full_app" and (not consent or consent.mode != "full_app" or consent.consent_version != "full_app_v1"):
                    raise HTTPException(403, "请在新版确认页核对完整工作台权限后重新批准")
                if consent and consent.mode is not None and consent.mode != row["mode"]:
                    raise HTTPException(403, "确认的权限与连接请求不一致")
            status = "approved" if approve else "rejected"
            self.store.db.execute("UPDATE native_pairings SET status=?,parent_session_id=? WHERE id=? AND status='pending'",
                                  (status, session_id if approve else None, request_id))
            return {"status": status}

    def poll(self, request_id, verifier):
        expected = digest(challenge_for(verifier))
        with self.store.lock, self.store.db:
            # Begin a write transaction before reading: separate SQLite
            # connections must not both claim one approved request.
            self.store.db.execute("UPDATE native_pairings SET status=status WHERE id=?", (request_id,))
            row = self._pairing(request_id, polling=True)
            if not hmac.compare_digest(expected, row["challenge_hash"]):
                raise HTTPException(401, "连接验证失败，请从发起连接的原生应用继续")
            self.limits.check(("verified-poll", request_id), 30)
            if row["status"] == "rejected":
                raise HTTPException(403, "此连接已被拒绝")
            if row["status"] == "consumed":
                raise HTTPException(410, "此连接已领取，请重新发起连接")
            if row["status"] == "pending":
                return {"status": "pending"}
            now = time.time()
            if row["expires_at"] <= now:
                raise HTTPException(410, "连接请求已过期，请在原生应用重新连接")
            parent = self._parent(row["parent_session_id"], now)
            if not parent:
                raise HTTPException(410, "批准此连接的登录已失效，请重新连接")
            self._clean(now)
            if self.store.db.execute("SELECT COUNT(*) FROM native_readers").fetchone()[0] >= MAX_READERS:
                raise HTTPException(429, "原生连接已达上限，请先在登录设备中移除旧连接", headers={"Retry-After": "60"})
            token, reader_id = "nrd_" + secrets.token_urlsafe(32), secrets.token_urlsafe(24)
            expires_at = min(now + READER_SECONDS, parent["expires_at"])
            self.store.db.execute("""INSERT INTO native_readers(id,token_hash,name,last_seen,expires_at,parent_session_id,mode)
                VALUES (?,?,?,?,?,?,?)""", (reader_id, digest(token), row["name"], now, expires_at, parent["id"], row["mode"]))
            self.store.db.execute("UPDATE native_pairings SET status='consumed' WHERE id=?", (request_id,))
            result = {"status": "approved", "reader_token": token, "expires_at": iso(expires_at)}
            if row["mode"] == "full_app":
                ticket = "nwt_" + secrets.token_urlsafe(32)
                ticket_expires = min(now + WEB_TICKET_SECONDS, expires_at)
                self.store.db.execute("INSERT INTO native_web_tickets VALUES (?,?,?,?)",
                                      (digest(ticket), reader_id, row["challenge_hash"], ticket_expires))
                result.update(mode="full_app", web_session_ticket=ticket,
                              web_session_ticket_expires_at=iso(ticket_expires))
            return result

    def _reader(self, token):
        if not TOKEN_PATTERN.fullmatch(token):
            raise HTTPException(401, "原生连接已失效，请重新连接")
        now = time.time()
        row = self.store.db.execute("""SELECT r.* FROM native_readers r JOIN sessions s ON s.id=r.parent_session_id
            WHERE r.token_hash=? AND r.expires_at>? AND s.expires_at>?
            AND EXISTS(SELECT 1 FROM account WHERE id=1)""", (digest(token), now, now)).fetchone()
        if not row or not self.store.valid_session_id(row["parent_session_id"], now):
            raise HTTPException(401, "原生连接已失效，请重新连接")
        return row

    def exchange_web_session(self, token, ticket, verifier):
        expected = digest(challenge_for(verifier))
        with self.store.lock, self.store.db:
            self._begin_reader(token)
            reader = self._reader(token)
            if reader["mode"] != "full_app":
                raise HTTPException(403, "此连接仅获准读取状态，请重新申请完整工作台权限")
            self.limits.check(("web-exchange", reader["id"]), 10)
            record = self.store.db.execute("SELECT * FROM native_web_tickets WHERE ticket_hash=?", (digest(ticket),)).fetchone()
            if not record or record["expires_at"] <= time.time():
                raise HTTPException(410, "工作台交接已过期或已领取，请重新连接")
            if record["reader_id"] != reader["id"] or not hmac.compare_digest(expected, record["proof_hash"]):
                raise HTTPException(401, "工作台交接验证失败")
            now, session_id = time.time(), str(uuid.uuid4())
            parent = self._parent(reader["parent_session_id"], now)
            if not parent or reader["expires_at"] <= now:
                raise HTTPException(401, "原生连接已失效，请重新连接")
            if record["expires_at"] <= now:
                raise HTTPException(410, "工作台交接已过期或已领取，请重新连接")
            session_token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            expires_at = min(reader["expires_at"], parent["expires_at"])
            self.store.db.execute("""INSERT INTO sessions(id,token_hash,csrf,name,last_seen,expires_at,native_reader_id)
                VALUES (?,?,?,?,?,?,?)""", (session_id, digest(session_token), csrf, reader["name"] + " · 完整 App", now, expires_at, reader["id"]))
            self.store.db.execute("DELETE FROM native_web_tickets WHERE ticket_hash=?", (digest(ticket),))
            return session_token, expires_at

    def check_session(self, token):
        """Validate the existing reader without loading tasks or profile data."""
        with self.store.lock, self.store.db:
            self._begin_reader(token)
            reader = self._reader(token)
            # Keep the same combined read budget as the snapshot-based check.
            self.limits.check(("snapshot", reader["id"]), 120)
            self.store.db.execute("UPDATE native_readers SET last_seen=? WHERE id=?", (time.time(), reader["id"]))

    def snapshot(self, token):
        with self.store.lock, self.store.db:
            self._begin_reader(token)
            reader = self._reader(token)
            self.limits.check(("snapshot", reader["id"]), 120)
            self.store.db.execute("UPDATE native_readers SET last_seen=? WHERE id=?", (time.time(), reader["id"]))
            snapshot = self.store.snapshot()
            profile = self.store.profile()
            devices = [{key: item[key] for key in ("id", "name", "online")} for item in snapshot["devices"]]
            names = {item["id"]: item["name"] for item in devices}
            tasks = [{**{key: item[key] for key in ("id", "title", "tool", "status", "updated_at", "device_id", "archived")},
                      "device_name": names.get(item["device_id"], "电脑")} for item in snapshot["tasks"]]
            result = {"user": {key: profile[key] for key in ("display_name", "avatar")},
                    "devices": devices, "tasks": tasks, "generated_at": snapshot["server_time"]}
            if reader["mode"] == "full_app" and getattr(self, "usage", None):
                self.usage.enrich(result)
            return result

    def revoke_current(self, token):
        with self.store.lock, self.store.db:
            self._begin_reader(token)
            reader = self._reader(token)
            self.store.db.execute("DELETE FROM native_readers WHERE id=?", (reader["id"],))
        return {"ok": True}

    def workspace(self, token, operation, *, write=False):
        """Run a fixed full-app operation under the reader's revocation lock.

        The caller supplies server-owned business logic, never an HTTP target.
        Revalidation after acquiring SQLite's write lock serializes operations
        against revocation from a different Store/connection as well.
        """
        from .request_diagnostics import mark_request_phase
        mark_request_phase("store_lock_wait")
        with self.store.lock:
            mark_request_phase("store_lock_acquired")
            try:
                with self.store.db:
                    mark_request_phase("transaction_start")
                    self._begin_reader(token)
                    mark_request_phase("transaction_acquired")
                    reader = self._reader(token)
                    if reader["mode"] != "full_app":
                        raise HTTPException(403, "此连接仅获准读取状态，请重新申请完整工作台权限")
                    self.limits.check(("workspace-write" if write else "snapshot", reader["id"]), 60 if write else 120)
                    self.store.db.execute("UPDATE native_readers SET last_seen=? WHERE id=?", (time.time(), reader["id"]))
                    result = operation(dict(reader))
            finally:
                mark_request_phase("transaction_end")
        return result

    def _begin_reader(self, token):
        if not TOKEN_PATTERN.fullmatch(token):
            raise HTTPException(401, "原生连接已失效，请重新连接")
        # Unknown credentials must not acquire a database write lock. The
        # caller validates again after the lock to observe concurrent revoke.
        self._reader(token)
        # Serialize parent-session revocation across SQLite connections too.
        self.store.db.execute("UPDATE native_readers SET last_seen=last_seen WHERE token_hash=?", (digest(token),))

    def devices(self):
        now = time.time()
        with self.store.lock:
            rows = self.store.db.execute("""SELECT r.id,r.name,r.last_seen,r.expires_at,r.mode,r.parent_session_id FROM native_readers r
                JOIN sessions s ON s.id=r.parent_session_id WHERE r.expires_at>? AND s.expires_at>?
                ORDER BY r.last_seen DESC""", (now, now)).fetchall()
        return {"devices": [{"id": row["id"], "name": row["name"], "last_seen": iso(row["last_seen"]),
                              "expires_at": iso(row["expires_at"]), "mode": row["mode"], "scopes": SCOPES[row["mode"]]}
                             for row in rows if self.store.valid_session_id(row["parent_session_id"], now)]}

    def revoke(self, reader_id):
        with self.store.lock, self.store.db:
            self.store.db.execute("DELETE FROM native_readers WHERE id=?", (reader_id,))
        return {"ok": True}


def install_native_routes(app, store, public_url, authenticated, same_origin, google_enabled=lambda: False):
    access = NativeAccess(store)
    access.usage = app.state.usage
    app.state.native_access = access

    def pairing_id(value):
        if not ID_PATTERN.fullmatch(value):
            raise HTTPException(404, "连接请求不存在")
        return value

    def bearer(request):
        value = request.headers.get("authorization", "")
        return value[7:] if value.startswith("Bearer ") else ""

    def reader_gate(request):
        access.limits.check(("reader-global",), 1200)
        access.limits.check(("reader-ip", request.client.host), READER_REQUESTS_PER_IP)

    @app.post("/api/native/pairing/start")
    def start(payload: NativeStart, request: Request):
        same_origin(request)
        if not public_url or urlsplit(public_url).scheme != "https":
            raise HTTPException(503, "原生连接需要服务的 HTTPS 地址")
        if not store.has_account():
            raise HTTPException(409, "请先建立 App 账号")
        access.limits.check(("start-global",), 30)
        access.limits.check(("start-ip", request.client.host), 5)
        result = access.start(payload.device_name, payload.code_challenge, payload.mode)
        result["verification_url"] = public_url.rstrip("/") + "/#/native-connect/" + result["request_id"]
        return result

    @app.get("/api/native/pairing/{request_id}")
    def details(request_id: str, session=Depends(authenticated)):
        return access.details(pairing_id(request_id))

    @app.post("/api/native/pairing/{request_id}/approve")
    def approve(request_id: str, payload: NativeApproval | None = None, session=Depends(authenticated)):
        return access.decide(pairing_id(request_id), session["id"], True, payload)

    @app.post("/api/native/pairing/{request_id}/reject")
    def reject(request_id: str, session=Depends(authenticated)):
        return access.decide(pairing_id(request_id), session["id"], False)

    @app.post("/api/native/pairing/{request_id}/poll")
    def poll(request_id: str, payload: NativePoll, request: Request):
        same_origin(request)
        access.limits.check(("poll-global",), 1200)
        access.limits.check(("poll-ip", request.client.host), 240)
        return access.poll(pairing_id(request_id), payload.code_verifier)

    @app.get("/api/native/snapshot")
    def snapshot(request: Request):
        reader_gate(request)
        return access.snapshot(bearer(request))

    @app.get("/api/native/session", status_code=204)
    def check_session(request: Request):
        reader_gate(request)
        access.check_session(bearer(request))
        return Response(status_code=204)

    @app.delete("/api/native/session")
    def disconnect(request: Request):
        reader_gate(request)
        return access.revoke_current(bearer(request))

    @app.post("/api/native/web-session")
    def web_session(payload: WebSessionExchange, request: Request, response: Response):
        same_origin(request)
        if not public_url or urlsplit(public_url).scheme != "https":
            raise HTTPException(503, "工作台交接需要 HTTPS 地址")
        reader_gate(request)
        token, expires_at = access.exchange_web_session(bearer(request), payload.ticket, payload.code_verifier)
        response.set_cookie(APP_COOKIE, token, max_age=max(0, int(expires_at - time.time())),
                            httponly=True, secure=True, samesite="strict", path="/")
        return {"ok": True, "expires_at": iso(expires_at)}

    @app.get("/api/native/devices")
    def devices(session=Depends(authenticated)):
        return access.devices()

    @app.delete("/api/native/devices/{reader_id}")
    def revoke(reader_id: str, session=Depends(authenticated)):
        return access.revoke(pairing_id(reader_id))

    from .native_workspace import install_workspace_routes
    install_workspace_routes(app, access, store, bearer, reader_gate, same_origin, google_enabled)

    return access
