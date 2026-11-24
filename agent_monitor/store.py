"""SQLite persistence. This module never accesses AI account credentials."""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .error_analysis import sanitize_task_analysis
from .failure_reasons import normalize_failure_code

SESSION_SECONDS = 30 * 24 * 60 * 60
ONLINE_SECONDS = 25
QR_PAIRING_SECONDS = 600
STATUS_PREVIEWS = {
    "running": "最近记录显示正在执行", "waiting": "等待在电脑端确认",
    "completed": "本轮响应已结束", "error": "本轮出现错误，请在电脑端查看",
    "unknown": "暂无足够记录确认当前状态", "idle": "会话已打开，等待新任务",
}


def iso(value: float | None = None) -> str:
    return datetime.fromtimestamp(time.time() if value is None else value, timezone.utc).isoformat()


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(value: str, salt: str) -> str:
    return hashlib.scrypt(value.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1, dklen=32).hex()


class PairingRequestError(ValueError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class Store:
    def __init__(self, path: Path, *, existing_only=False):
        self.lock = threading.RLock()
        if existing_only:
            self.db = sqlite3.connect(path.resolve().as_uri() + "?mode=rw", uri=True, timeout=1)
            self.db.row_factory = sqlite3.Row
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path), check_same_thread=False, timeout=10)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
          PRAGMA journal_mode=WAL;
          PRAGMA foreign_keys=ON;
          CREATE TABLE IF NOT EXISTS account (
            id INTEGER PRIMARY KEY CHECK(id=1), username TEXT NOT NULL UNIQUE,
            salt TEXT NOT NULL, password_hash TEXT NOT NULL
          );
          CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, csrf TEXT NOT NULL,
            name TEXT NOT NULL, last_seen REAL NOT NULL, expires_at REAL NOT NULL
          );
          CREATE TABLE IF NOT EXISTS preferences (
            id INTEGER PRIMARY KEY CHECK(id=1), tool_filter TEXT NOT NULL, sync_output INTEGER NOT NULL
          );
          INSERT OR IGNORE INTO preferences VALUES (1,'all',0);
          CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, platform TEXT NOT NULL,
            token_hash TEXT UNIQUE, local INTEGER NOT NULL DEFAULT 0,
            last_seen REAL NOT NULL DEFAULT 0, sources TEXT NOT NULL DEFAULT '[]',
            last_collected_at REAL
          );
          CREATE TABLE IF NOT EXISTS tasks (
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            source_id TEXT NOT NULL, payload TEXT NOT NULL,
            PRIMARY KEY (device_id, source_id)
          );
          CREATE TABLE IF NOT EXISTS pairing (
            code_hash TEXT PRIMARY KEY, expires_at REAL NOT NULL
          );
          CREATE TABLE IF NOT EXISTS profile (
            id INTEGER PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE CHECK(id=1),
            display_name TEXT NOT NULL, avatar TEXT NOT NULL DEFAULT ''
          );
          CREATE TABLE IF NOT EXISTS task_archive (
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            source_id TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (device_id, source_id)
          );
          CREATE TABLE IF NOT EXISTS qr_pairing (
            id TEXT PRIMARY KEY, poll_hash TEXT NOT NULL,
            name TEXT NOT NULL, platform TEXT NOT NULL,
            expires_at REAL NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
            include_collector INTEGER NOT NULL DEFAULT 1,
            issue_collector_code INTEGER NOT NULL DEFAULT 0,
            include_reader INTEGER NOT NULL DEFAULT 0,
            reader_name TEXT NOT NULL DEFAULT '',
            challenge_hash TEXT NOT NULL DEFAULT '',
            parent_session_id TEXT
          );
        """)
        qr_columns = {row[1] for row in self.db.execute("PRAGMA table_info(qr_pairing)")}
        for column, definition in (
            ("include_collector", "INTEGER NOT NULL DEFAULT 1"),
            ("issue_collector_code", "INTEGER NOT NULL DEFAULT 0"),
            ("include_reader", "INTEGER NOT NULL DEFAULT 0"),
            ("reader_name", "TEXT NOT NULL DEFAULT ''"),
            ("challenge_hash", "TEXT NOT NULL DEFAULT ''"),
            ("parent_session_id", "TEXT"),
        ):
            if column not in qr_columns:
                self.db.execute(f"ALTER TABLE qr_pairing ADD COLUMN {column} {definition}")
        dev_columns = {row[1] for row in self.db.execute("PRAGMA table_info(devices)")}
        if "last_collected_at" not in dev_columns:
            self.db.execute("ALTER TABLE devices ADD COLUMN last_collected_at REAL")
        for col_name in ("projects", "dev_ports", "preview_hosts"):
            if col_name not in dev_columns:
                self.db.execute(f"ALTER TABLE devices ADD COLUMN {col_name} TEXT NOT NULL DEFAULT '[]'")
        self.db.commit()

    def close(self):
        with self.lock:
            self.db.close()

    def has_account(self) -> bool:
        with self.lock:
            return self.db.execute("SELECT 1 FROM account").fetchone() is not None

    def setup(self, username: str, password: str):
        salt = secrets.token_hex(16)
        hashed = password_hash(password, salt)
        with self.lock, self.db:
            if self.has_account():
                raise ValueError("账号已经建立，请直接登录")
            self.db.execute("INSERT INTO account VALUES (1,?,?,?)", (username, salt, hashed))

    def verify_password(self, username: str, password: str) -> bool:
        with self.lock:
            row = self.db.execute("SELECT * FROM account WHERE id=1").fetchone()
        # Perform the same expensive operation for unknown usernames.
        salt = row["salt"] if row else "0" * 32
        computed = password_hash(password, salt)
        return bool(row and hmac.compare_digest(computed, row["password_hash"]) and hmac.compare_digest(username.encode(), row["username"].encode()))

    def profile(self):
        with self.lock:
            row = self.db.execute("""SELECT account.username,
                COALESCE(profile.display_name,account.username) AS display_name,
                COALESCE(profile.avatar,'') AS avatar FROM account
                LEFT JOIN profile ON profile.id=account.id WHERE account.id=1""").fetchone()
        return dict(row) if row else None

    def update_profile(self, changes: dict):
        with self.lock, self.db:
            current = self.profile()
            if not current:
                raise ValueError("请先建立账号")
            current.update(changes)
            self.db.execute("""INSERT INTO profile (id,display_name,avatar) VALUES (1,?,?)
                ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,avatar=excluded.avatar""",
                (current["display_name"], current["avatar"]))
            return current

    def new_session(self, name: str):
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        session_id, now = str(uuid.uuid4()), time.time()
        with self.lock, self.db:
            self.db.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))
            self.db.execute("INSERT INTO sessions(id,token_hash,csrf,name,last_seen,expires_at) VALUES (?,?,?,?,?,?)",
                            (session_id, digest(token), csrf, name, now, now + SESSION_SECONDS))
        return token, csrf

    def session(self, token: str | None):
        if not token or len(token) > 256:
            return None
        with self.lock, self.db:
            row = self.db.execute("SELECT sessions.*,account.username FROM sessions CROSS JOIN account WHERE token_hash=? AND expires_at>?", (digest(token), time.time())).fetchone()
            if row and self.valid_session_id(row["id"]):
                self.db.execute("UPDATE sessions SET last_seen=? WHERE id=?", (time.time(), row["id"]))
                return dict(row)
        return None

    def valid_session_id(self, session_id: str, now: float | None = None):
        """Validate native-derived session ancestry as well as its own expiry.

        Older databases/sessions have no native_reader_id and keep their existing
        behavior. Links are server-created from an already-existing parent; a
        malformed or cyclic chain fails closed instead of extending access.
        """
        now = time.time() if now is None else now
        with self.lock:
            original, visited = None, set()
            while session_id and session_id not in visited and len(visited) < 100:
                visited.add(session_id)
                row = self.db.execute("SELECT * FROM sessions WHERE id=? AND expires_at>?", (session_id, now)).fetchone()
                if not row:
                    return None
                if original is None:
                    original = dict(row)
                reader_id = row["native_reader_id"] if "native_reader_id" in row.keys() else None
                if not reader_id:
                    return original
                reader = self.db.execute("SELECT parent_session_id FROM native_readers WHERE id=? AND expires_at>? AND mode='full_app'",
                                         (reader_id, now)).fetchone()
                if not reader:
                    return None
                session_id = reader["parent_session_id"]
        return None

    def valid_full_reader(self, reader_id: str) -> bool:
        now = time.time()
        row = self.db.execute("SELECT mode,expires_at,parent_session_id FROM native_readers WHERE id=?", (reader_id,)).fetchone()
        return bool(row and row["mode"] == "full_app" and row["expires_at"] > now
                    and self.valid_session_id(row["parent_session_id"], now))

    def sessions(self, current: str):
        with self.lock:
            rows = self.db.execute("SELECT id,name,last_seen FROM sessions WHERE expires_at>? ORDER BY last_seen DESC", (time.time(),)).fetchall()
            rows = [row for row in rows if self.valid_session_id(row["id"])]
        return [{"id": r["id"], "name": r["name"], "last_seen": iso(r["last_seen"]), "current": r["id"] == current} for r in rows]

    def revoke_session(self, session_id: str):
        with self.lock, self.db:
            row = self.db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            if row and "native_reader_id" in row.keys() and row["native_reader_id"]:
                # Logging out of a managed WebView also ends its native reader.
                # Reader deletion cascades to this web session and descendants,
                # while the browser that approved it remains signed in.
                self.db.execute("DELETE FROM native_readers WHERE id=?", (row["native_reader_id"],))
            self.db.execute("DELETE FROM sessions WHERE id=?", (session_id,))

    def preferences(self):
        with self.lock:
            row = self.db.execute("SELECT tool_filter,sync_output FROM preferences WHERE id=1").fetchone()
        return {"tool_filter": row["tool_filter"], "sync_output": bool(row["sync_output"])}

    def update_preferences(self, changes: dict):
        with self.lock, self.db:
            prefs = self.preferences() | changes
            self.db.execute("UPDATE preferences SET tool_filter=?,sync_output=? WHERE id=1", (prefs["tool_filter"], int(prefs["sync_output"])))
            if not prefs["sync_output"]:
                if self.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='final_results'").fetchone():
                    self.db.execute("DELETE FROM final_results")
                for row in self.db.execute("SELECT device_id,source_id,payload FROM tasks").fetchall():
                    task = json.loads(row["payload"])
                    task["output"] = ""
                    task["error_analysis"] = None
                    task["failure_code"] = ""
                    task["final_result_id"] = ""
                    task["final_result_at"] = ""
                    task["preview"] = STATUS_PREVIEWS.get(task["status"], STATUS_PREVIEWS["unknown"])
                    self.db.execute("UPDATE tasks SET payload=? WHERE device_id=? AND source_id=?", (json.dumps(task, ensure_ascii=False), row["device_id"], row["source_id"]))
        return prefs

    def ensure_local(self, name: str, platform: str):
        with self.lock, self.db:
            row = self.db.execute("SELECT id FROM devices WHERE local=1").fetchone()
            if row:
                return row["id"]
            device_id = str(uuid.uuid4())
            self.db.execute("INSERT INTO devices (id,name,platform,local) VALUES (?,?,?,1)", (device_id, name, platform))
        return device_id

    def create_pairing(self):
        code, now = secrets.token_hex(6).upper(), time.time()
        with self.lock, self.db:
            self.db.execute("DELETE FROM pairing")
            self.db.execute("INSERT INTO pairing VALUES (?,?)", (digest(code), now + 600))
        return {"code": code, "expires_at": iso(now + 600)}

    def register(self, code: str, name: str, platform: str):
        code = code.strip().upper().replace("-", "")
        now, token, device_id = time.time(), secrets.token_urlsafe(32), str(uuid.uuid4())
        with self.lock, self.db:
            row = self.db.execute("SELECT 1 FROM pairing WHERE code_hash=? AND expires_at>?", (digest(code), now)).fetchone()
            if not row:
                raise ValueError("配对码无效或已过期，请重新生成")
            self.db.execute("DELETE FROM pairing WHERE code_hash=?", (digest(code),))
            self.db.execute("INSERT INTO devices (id,name,platform,token_hash,last_seen) VALUES (?,?,?,?,?)", (device_id, name, platform, digest(token), now))
        return {"device_id": device_id, "token": token, "sync_output": self.preferences()["sync_output"]}

    def create_qr_pairing(
        self,
        name: str,
        platform: str,
        *,
        include_collector: bool = True,
        issue_collector_code: bool = False,
        include_reader: bool = False,
        reader_name: str | None = None,
        code_challenge: str | None = None,
    ):
        if not include_collector and not include_reader:
            raise PairingRequestError(422, "配对请求至少需要包含电脑采集或桌面端登录")
        if issue_collector_code and not include_collector:
            raise PairingRequestError(422, "仅在绑定电脑采集器时可签发配对码")
        if include_reader and not code_challenge:
            raise PairingRequestError(422, "桌面端扫码登录需要提供安全验证摘要")
        if not include_reader and code_challenge:
            raise PairingRequestError(422, "未启用桌面端登录时不可提交验证摘要")
        effective_reader_name = (reader_name or f"{name} · 桌面端").strip()[:60] if include_reader else ""
        challenge_digest = digest(code_challenge) if include_reader and code_challenge else ""
        request_id, poll_secret = secrets.token_urlsafe(24), secrets.token_urlsafe(32)
        now = time.time()
        with self.lock, self.db:
            self.db.execute("DELETE FROM qr_pairing WHERE expires_at<?", (now - QR_PAIRING_SECONDS,))
            active = self.db.execute("SELECT COUNT(*) FROM qr_pairing WHERE expires_at>? AND status IN ('pending','approved')", (now,)).fetchone()[0]
            if active >= 100:
                raise PairingRequestError(429, "待确认的配对过多，请稍后再试")
            self.db.execute(
                """INSERT INTO qr_pairing (
                    id, poll_hash, name, platform, expires_at,
                    include_collector, issue_collector_code, include_reader, reader_name, challenge_hash
                ) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (
                    request_id,
                    digest(poll_secret),
                    name,
                    platform,
                    now + QR_PAIRING_SECONDS,
                    int(bool(include_collector)),
                    int(bool(issue_collector_code)),
                    int(bool(include_reader)),
                    effective_reader_name,
                    challenge_digest,
                ),
            )
        return {
            "request_id": request_id,
            "poll_secret": poll_secret,
            "expires_at": iso(now + QR_PAIRING_SECONDS),
            "interval": 3,
            "include_collector": bool(include_collector),
            "include_reader": bool(include_reader),
        }

    def _qr_request(self, request_id: str):
        row = self.db.execute("SELECT * FROM qr_pairing WHERE id=?", (request_id,)).fetchone()
        if not row:
            raise PairingRequestError(404, "配对请求不存在")
        if row["expires_at"] <= time.time():
            raise PairingRequestError(410, "配对已过期，请在电脑重新生成二维码")
        return row

    def qr_pairing_details(self, request_id: str):
        with self.lock:
            row = self._qr_request(request_id)
            details = {
                "request_id": row["id"],
                "name": row["name"],
                "platform": row["platform"],
                "expires_at": iso(row["expires_at"]),
                "status": row["status"],
                "include_collector": bool(row["include_collector"]),
                "include_reader": bool(row["include_reader"]),
            }
            if row["include_reader"]:
                details["reader_name"] = row["reader_name"] or f"{row['name']} · 桌面端"
            return details

    def decide_qr_pairing(self, request_id: str, approved: bool, *, parent_session_id: str | None = None):
        with self.lock, self.db:
            row = self._qr_request(request_id)
            if row["status"] != "pending":
                raise PairingRequestError(409, "这个配对请求已经处理")
            now = time.time()
            if approved and row["include_reader"]:
                if not parent_session_id or not self.valid_session_id(parent_session_id, now):
                    raise PairingRequestError(401, "请重新登录后再批准桌面端连接")
            status = "approved" if approved else "denied"
            saved_parent = parent_session_id if (approved and parent_session_id) else None
            changed = self.db.execute(
                "UPDATE qr_pairing SET status=?, parent_session_id=? WHERE id=? AND status='pending' AND expires_at>?",
                (status, saved_parent, request_id, now),
            )
            if changed.rowcount != 1:
                raise PairingRequestError(409, "这个配对请求已经处理或过期")
        return {"ok": True, "status": status}

    def poll_qr_pairing(self, request_id: str, poll_secret: str, *, code_verifier: str | None = None, native_access=None):
        with self.lock, self.db:
            self.db.execute("UPDATE qr_pairing SET status=status WHERE id=?", (request_id,))
            # Verify the independent secret before disclosing status or expiry.
            row = self.db.execute("SELECT * FROM qr_pairing WHERE id=?", (request_id,)).fetchone()
            if not row or not hmac.compare_digest(row["poll_hash"], digest(poll_secret)):
                raise PairingRequestError(401, "配对验证失败，请重新生成二维码")
            if row["include_reader"]:
                if not code_verifier:
                    raise PairingRequestError(401, "桌面端连接缺少安全验证参数")
                from .native_access import challenge_for
                expected_challenge = digest(challenge_for(code_verifier))
                if not row["challenge_hash"] or not hmac.compare_digest(expected_challenge, row["challenge_hash"]):
                    raise PairingRequestError(401, "桌面端安全验证失败，请重新生成二维码")
            elif code_verifier is not None:
                raise PairingRequestError(401, "未启用桌面端登录时不可提交安全验证参数")
            row = self._qr_request(request_id)
            if row["status"] == "pending":
                return {"status": "pending", "interval": 3}
            if row["status"] == "denied":
                raise PairingRequestError(403, "手机已拒绝此配对")
            if row["status"] == "consumed":
                raise PairingRequestError(410, "配对凭据已经领取，请重新生成二维码")
            now = time.time()
            claimed = self.db.execute(
                "UPDATE qr_pairing SET status='consumed' WHERE id=? AND status='approved' AND expires_at>?",
                (request_id, now),
            )
            if claimed.rowcount != 1:
                raise PairingRequestError(410, "配对已过期或凭据已经领取")
            result: dict = {"status": "approved", "sync_output": self.preferences()["sync_output"]}
            if row["include_reader"]:
                if native_access is None:
                    raise PairingRequestError(503, "当前服务未启用桌面端原生登录")
                issuer = getattr(native_access, "issue_reader_in_transaction", native_access.issue_reader)
                issued = issuer(
                    parent_session_id=row["parent_session_id"] or "",
                    name=row["reader_name"] or f"{row['name']} · 桌面端",
                    mode="full_app",
                    now=now,
                )
                result.update(issued)
            if row["include_collector"]:
                if row["issue_collector_code"]:
                    code = secrets.token_hex(6).upper()
                    self.db.execute("DELETE FROM pairing WHERE expires_at<=?", (now,))
                    self.db.execute("INSERT INTO pairing VALUES (?,?)", (digest(code), now + 600))
                    result["collector_code"] = code
                    result["collector_code_expires_at"] = iso(now + 600)
                else:
                    token, device_id = secrets.token_urlsafe(32), str(uuid.uuid4())
                    self.db.execute(
                        "INSERT INTO devices (id,name,platform,token_hash,last_seen) VALUES (?,?,?,?,?)",
                        (device_id, row["name"], row["platform"], digest(token), now),
                    )
                    result["device_id"] = device_id
                    result["token"] = token
            return result

    def agent_device(self, token: str):
        if not token or len(token) > 256:
            return None
        with self.lock:
            row = self.db.execute("SELECT id FROM devices WHERE token_hash=? AND local=0", (digest(token),)).fetchone()
        return row["id"] if row else None

    def ingest(
        self,
        device_id: str,
        tasks: list[dict],
        sources: list[dict],
        *,
        collected_at: str | None = None,
        projects: list[dict] | None = None,
        dev_ports: list[dict] | None = None,
        preview_hosts: list[str] | None = None,
    ):
        with self.lock, self.db:
            if not self.db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
                raise ValueError("设备已断开，请重新配对")
            sync_output = self.preferences()["sync_output"]
            now = time.time()
            collected_ts = None
            if isinstance(collected_at, str) and collected_at:
                try:
                    parsed = datetime.fromisoformat(collected_at.replace("Z", "+00:00"))
                    if parsed.tzinfo is not None:
                        collected_ts = min(parsed.timestamp(), now)
                except ValueError:
                    collected_ts = None
            self.db.execute(
                """UPDATE devices SET
                    last_seen=?,
                    last_collected_at=COALESCE(?,last_collected_at),
                    sources=?,
                    projects=COALESCE(?,projects),
                    dev_ports=COALESCE(?,dev_ports),
                    preview_hosts=COALESCE(?,preview_hosts)
                   WHERE id=?""",
                (
                    now,
                    collected_ts,
                    json.dumps(sources, ensure_ascii=False),
                    json.dumps(projects[:24], ensure_ascii=False) if projects is not None else None,
                    json.dumps(dev_ports[:12], ensure_ascii=False) if dev_ports is not None else None,
                    json.dumps(preview_hosts[:6], ensure_ascii=False) if preview_hosts is not None else None,
                    device_id,
                ),
            )
            incoming_ids = {item["id"] for item in tasks[:100] if isinstance(item, dict) and isinstance(item.get("id"), str)}
            preserved_rows: list[tuple[str, str]] = []
            has_replies = self.db.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='task_replies'"
            ).fetchone()
            if has_replies:
                reply_cols = {r[1] for r in self.db.execute("PRAGMA table_info(task_replies)")}
                if "created_task_id" in reply_cols:
                    prefix = device_id + ":"
                    for r in self.db.execute(
                        "SELECT created_task_id FROM task_replies WHERE device_id=? AND state IN ('dispatching','running') AND created_task_id!=''",
                        (device_id,),
                    ).fetchall():
                        ctid = r["created_task_id"]
                        if ctid.startswith(prefix):
                            sid = ctid[len(prefix):]
                            if sid and sid not in incoming_ids:
                                existing_task = self.db.execute(
                                    "SELECT payload FROM tasks WHERE device_id=? AND source_id=?",
                                    (device_id, sid),
                                ).fetchone()
                                if existing_task:
                                    preserved_rows.append((sid, existing_task["payload"]))
            self.db.execute("DELETE FROM tasks WHERE device_id=?", (device_id,))
            for item in tasks[:100]:
                task = dict(item)
                task["failure_code"] = normalize_failure_code(task.get("failure_code")) if sync_output and task.get("status") == "error" else ""
                task["error_analysis"] = sanitize_task_analysis(task, enabled=sync_output)
                if not sync_output:
                    task["output"] = ""
                    task["final_result_id"] = ""
                    task["final_result_at"] = ""
                    task["preview"] = STATUS_PREVIEWS.get(task["status"], STATUS_PREVIEWS["unknown"])
                self.db.execute("INSERT OR REPLACE INTO tasks VALUES (?,?,?)", (device_id, task["id"], json.dumps(task, ensure_ascii=False)))
            for sid, payload in preserved_rows:
                self.db.execute("INSERT OR IGNORE INTO tasks VALUES (?,?,?)", (device_id, sid, payload))

    def upsert_spawned_task(
        self,
        device_id: str,
        source_id: str,
        tool: str,
        title: str,
        project: str = "",
        project_id: str = "",
    ) -> None:
        clean_title = " ".join((title or "").split())[:72] or f"{tool} 新任务"
        payload = {
            "id": source_id,
            "tool": tool,
            "title": clean_title,
            "status": "running",
            "updated_at": iso(),
            "preview": STATUS_PREVIEWS["running"],
            "output": "",
            "final_result_id": "",
            "final_result_at": "",
            "project": project[:120],
            "project_id": project_id[:24],
            "status_source": "hook" if tool == "claude" else "local_log",
            "error_analysis": None,
            "failure_code": "",
        }
        with self.lock, self.db:
            if self.db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
                self.db.execute(
                    "INSERT OR IGNORE INTO tasks VALUES (?,?,?)",
                    (device_id, source_id, json.dumps(payload, ensure_ascii=False)),
                )

    def remove_device(self, device_id: str):
        with self.lock, self.db:
            row = self.db.execute("SELECT local FROM devices WHERE id=?", (device_id,)).fetchone()
            if row and row["local"]:
                raise ValueError("本机采集器由服务管理，停止服务即可断开")
            self.db.execute("DELETE FROM devices WHERE id=?", (device_id,))

    def archive_task(self, task_id: str, archived: bool):
        device_id, separator, source_id = task_id.partition(":")
        with self.lock, self.db:
            if not separator or not self.db.execute("SELECT 1 FROM tasks WHERE device_id=? AND source_id=?", (device_id, source_id)).fetchone():
                raise ValueError("任务不存在或已不再同步")
            self.db.execute("""INSERT INTO task_archive VALUES (?,?,?)
                ON CONFLICT(device_id,source_id) DO UPDATE SET archived=excluded.archived""",
                (device_id, source_id, int(archived)))
        return {"id": task_id, "archived": archived}

    def snapshot(self):
        now = time.time()
        with self.lock:
            devices = []
            for row in self.db.execute("SELECT * FROM devices ORDER BY local DESC,name").fetchall():
                keys = row.keys()
                is_online = now - row["last_seen"] < ONLINE_SECONDS
                entry = {
                    "id": row["id"],
                    "name": row["name"],
                    "platform": row["platform"],
                    "last_seen": iso(row["last_seen"]),
                    "online": is_online,
                    "local": bool(row["local"]),
                    "sources": json.loads(row["sources"]),
                    "projects": json.loads(row["projects"]) if "projects" in keys and row["projects"] else [],
                    "dev_ports": (json.loads(row["dev_ports"]) if "dev_ports" in keys and row["dev_ports"] else []) if is_online else [],
                    "preview_hosts": json.loads(row["preview_hosts"]) if "preview_hosts" in keys and row["preview_hosts"] else [],
                }
                if "last_collected_at" in keys and row["last_collected_at"]:
                    entry["collected_at"] = iso(row["last_collected_at"])
                devices.append(entry)
            online = {d["id"]: d["online"] for d in devices}
            tasks = []
            for row in self.db.execute("""SELECT tasks.*,COALESCE(task_archive.archived,0) AS archived FROM tasks
                LEFT JOIN task_archive ON tasks.device_id=task_archive.device_id AND tasks.source_id=task_archive.source_id""").fetchall():
                item = json.loads(row["payload"])
                item["id"] = row["device_id"] + ":" + row["source_id"]
                item["device_id"] = row["device_id"]
                item["archived"] = bool(row["archived"])
                item["stale"] = not online.get(row["device_id"], False)
                if item["stale"]:
                    item["last_known_status"] = item["status"]
                    item["status"] = "unknown"
                    item["preview"] = "电脑已断开，以下为最后一次记录"
                    item["error_analysis"] = None
                    item["failure_code"] = ""
                tasks.append(item)
        tasks.sort(key=lambda item: item["updated_at"], reverse=True)
        return {"devices": devices, "tasks": tasks, "server_time": iso(now)}
