"""Read-only adapter for the Antigravity App index and opt-in final text.

The index path never queries previews, search, or protobuf blobs. Only an
explicit result request opens the one corresponding text transcript.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import stat
import time
import unicodedata
from contextlib import closing
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit
from uuid import UUID

_MAX_TASKS = 100
_MAX_DB_BYTES = 96 * 1024 * 1024
_MAX_SIDE_BYTES = 64 * 1024 * 1024
_MAX_WORKSPACE_CHARS = 8192
_RESULT_FAST_TAIL = 64 * 1024
_MAX_RESULT_READ_BYTES = 2 * 1024 * 1024
_READ_TIMEOUT = 0.075
_SCHEMA = "conversation_summaries"
_FIELDS = frozenset({
    "conversation_id", "title", "last_modified_time", "workspace_uris", "status",
    "source", "parent_conversation_id", "nesting_depth", "not_fully_idle", "killed",
    "step_count", "last_user_input_step_index",
})
_LOCAL_SOURCES = frozenset({
    "", "TRAJECTORY_SOURCE_CASCADE_CLIENT", "TRAJECTORY_SOURCE_INTERACTIVE_CASCADE",
    "TRAJECTORY_SOURCE_CLI", "TRAJECTORY_SOURCE_VISUAL_STUDIO", "TRAJECTORY_SOURCE_VSCODE",
})
_SELECT = """
    SELECT substr(conversation_id, 1, 37), substr(title, 1, 360),
           substr(last_modified_time, 1, 80), substr(workspace_uris, 1, 8193),
           substr(status, 1, 80), substr(source, 1, 80), not_fully_idle, killed
    FROM main.conversation_summaries
    WHERE parent_conversation_id = '' AND nesting_depth = 0
    ORDER BY last_modified_time DESC
    LIMIT ?
"""
_SELECT_RESULT = """
    SELECT substr(last_modified_time, 1, 80), substr(status, 1, 80), substr(source, 1, 80),
           not_fully_idle, killed, step_count, last_user_input_step_index
    FROM main.conversation_summaries
    WHERE conversation_id = ? AND parent_conversation_id = '' AND nesting_depth = 0
    LIMIT 2
"""
_SELECT_SESSION = """
    SELECT substr(workspace_uris, 1, 8193), substr(source, 1, 80), killed
    FROM main.conversation_summaries
    WHERE conversation_id = ? AND parent_conversation_id = '' AND nesting_depth = 0
    LIMIT 2
"""
_TEXT_STEP_FIELDS = frozenset({
    "step_index", "source", "type", "status", "created_at", "content", "thinking", "tool_calls",
    "input_tokens", "cache_read_tokens", "output_tokens", "truncated_fields", "model",
})


def _file(path: Path, *, missing=False, size_limit=_MAX_DB_BYTES) -> os.stat_result | None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        if missing:
            return None
        raise
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
            or getattr(info, "st_file_attributes", 0) & 0x400 or info.st_size > size_limit):
        raise ValueError("unsupported local Antigravity file")
    return info


def _plain_directory(directory: Path) -> Path:
    if ".." in directory.parts:
        raise ValueError("invalid local Antigravity directory")
    directory = directory.absolute()
    if directory.anchor.startswith(("\\\\", "//")):
        raise ValueError("network Antigravity directory")
    for piece in (*reversed(directory.parents), directory):
        info = piece.lstat()
        if not stat.S_ISDIR(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("linked Antigravity directory")
    return directory


def _base(base: Path | str | None) -> Path:
    return Path(base).expanduser() if base is not None else Path.home() / ".gemini" / "antigravity"


def _stamp(info: os.stat_result) -> tuple[int, int, int, int]:
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _side(path: Path, suffix: str) -> os.stat_result | None:
    return _file(path.with_name(path.name + suffix), missing=True, size_limit=_MAX_SIDE_BYTES)


def _allow_index_columns(action, first, second, database, _origin):
    if action == sqlite3.SQLITE_SELECT:
        return sqlite3.SQLITE_OK
    if action == sqlite3.SQLITE_READ and database == "main" and first == _SCHEMA and second in _FIELDS:
        return sqlite3.SQLITE_OK
    if action == sqlite3.SQLITE_FUNCTION and second == "substr":
        return sqlite3.SQLITE_OK
    return sqlite3.SQLITE_DENY


def _read(base, limit: int, *, session_id: str | None = None, query: str | None = None) -> list[tuple]:
    path = _plain_directory(_base(base)) / "conversation_summaries.db"
    first = _file(path)
    with path.open("rb") as file:
        if not os.path.samestat(first, os.fstat(file.fileno())):
            raise ValueError("Antigravity index path changed")
        header = file.read(20)
    if header[:16] != b"SQLite format 3\0" or header[18:20] not in (b"\1\1", b"\2\2"):
        raise ValueError("unexpected Antigravity index header")
    wal, shm, journal = (_side(path, suffix) for suffix in ("-wal", "-shm", "-journal"))
    # Calling SQLite in read-only mode may still create WAL sidecars if one is
    # missing. For a WAL database with neither file, use its checkpointed copy
    # in place, and invalidate the scan if a writer appeared meanwhile.
    if (wal is None) != (shm is None) or journal is not None or (wal is not None and header[18] != 2):
        raise ValueError("unsupported Antigravity index sidecars")
    checkpointed = wal is None and header[18] == 2
    uri = path.as_uri() + "?mode=ro" + ("&immutable=1" if checkpointed else "")
    deadline = time.monotonic() + _READ_TIMEOUT
    with closing(sqlite3.connect(uri, uri=True, timeout=_READ_TIMEOUT)) as connection:
        connection.set_progress_handler(lambda: int(time.monotonic() >= deadline), 256)
        connection.execute("PRAGMA query_only=ON")
        connection.execute("PRAGMA trusted_schema=OFF")
        connection.execute("PRAGMA temp_store=MEMORY")
        # SQLite distinguishes ordinary tables from views and virtual tables.
        table = connection.execute("SELECT type FROM pragma_table_list(?) WHERE schema = 'main' AND name = ?",
                                   (_SCHEMA, _SCHEMA)).fetchone()
        if table != ("table",):
            raise ValueError("unexpected Antigravity index schema")
        connection.set_authorizer(_allow_index_columns)
        rows = connection.execute(_SELECT, (limit,)).fetchall() if session_id is None else (
            connection.execute(query or _SELECT_RESULT, (session_id,)).fetchall())
    after = _file(path)
    if not os.path.samestat(first, after):
        raise ValueError("Antigravity index replaced during scan")
    if checkpointed and (_side(path, "-wal") is not None or _side(path, "-shm") is not None
                         or _stamp(first) != _stamp(after)):
        raise ValueError("checkpointed Antigravity index changed during scan")
    return rows


def _display(value, cap: int) -> str:
    if not isinstance(value, str):
        return ""
    visible = "".join(" " if c.isspace() or unicodedata.category(c).startswith("C") else c for c in value)
    return " ".join(visible.split())[:cap]


def _workspace_posix(raw) -> PurePosixPath | None:
    if not isinstance(raw, str) or len(raw) > _MAX_WORKSPACE_CHARS:
        return None
    try:
        values = json.loads(raw)
        if not isinstance(values, list) or len(values) != 1 or not isinstance(values[0], str):
            return None
        # Only accept a single local file:/// URI. Remote URI hosts, query data,
        # decoded separators and ambiguous relative paths are rejected.
        uri = values[0]
        if not uri.startswith("file:///") or any(ord(ch) < 32 for ch in uri):
            return None
        parts = urlsplit(uri)
        if parts.netloc or parts.query or parts.fragment or re.search(r"%(?:2f|5c)", parts.path, re.IGNORECASE):
            return None
        decoded = unquote(parts.path, errors="strict")
        if decoded.startswith("//") or "\\" in decoded or any(ord(ch) < 32 for ch in decoded):
            return None
        path = PurePosixPath(decoded)
        if ".." in path.parts or (len(path.parts) == 2 and re.fullmatch(r"[a-zA-Z]:", path.name)):
            return None
        return path
    except (ValueError, UnicodeError, RecursionError):
        return None


def _project(raw) -> str:
    path = _workspace_posix(raw)
    return _display(path.name, 80) if path is not None else ""


def resolve_antigravity_workspace(raw) -> Path | None:
    posix = _workspace_posix(raw)
    if posix is None:
        return None
    text = str(posix)
    if os.name == "nt":
        if not re.match(r"^/[a-zA-Z]:/.+", text):
            return None
        candidate = Path(text[1:])
    else:
        if not text.startswith("/") or len(posix.parts) < 2 or re.match(r"^/[a-zA-Z]:(?:/|$)", text):
            return None
        candidate = Path(text)
    if not candidate.is_absolute() or ".." in candidate.parts or candidate.anchor.startswith(("\\\\", "//")):
        return None
    return candidate


def resolve_antigravity_session(session_id: str, *, base: Path | str | None = None) -> tuple[Path, Path] | None:
    """Resolve an indexed, top-level local Antigravity trajectory and its workspace."""
    try:
        if not isinstance(session_id, str) or len(session_id) != 36 or str(UUID(session_id)) != session_id:
            return None
        rows = _read(base, 0, session_id=session_id, query=_SELECT_SESSION)
        if len(rows) != 1:
            return None
        workspace, source, killed = rows[0]
        if source not in _LOCAL_SOURCES or type(killed) is not int or killed != 0:
            return None
        cwd = resolve_antigravity_workspace(workspace)
        if cwd is None:
            return None
        conversations = _plain_directory(_base(base) / "conversations")
        path = conversations / f"{session_id}.db"
        _file(path, size_limit=2**63 - 1)
        return path, cwd
    except (OSError, sqlite3.Error, ValueError, TypeError, OverflowError):
        return None


def _status(native, not_idle, killed, updated, now, stale_seconds):
    if type(not_idle) is not int or not_idle not in (0, 1) or type(killed) is not int or killed != 0:
        return "unknown"
    if native == "CASCADE_RUN_STATUS_IDLE" and not_idle == 0:
        return "idle"
    if native == "CASCADE_RUN_STATUS_RUNNING" and now - updated <= timedelta(seconds=stale_seconds):
        return "running"
    # Busy/canceling and partially idle do not establish why progress stopped.
    return "unknown"


def scan_antigravity(*, base: Path | str | None = None, limit: int = 50,
                     now: datetime | None = None) -> list[dict] | None:
    """Return bounded task metadata; None means the local index is unavailable."""
    from . import collector

    limit = max(0, min(limit, _MAX_TASKS))
    try:
        rows = _read(base, min(_MAX_TASKS * 3, limit * 3))
    except (OSError, sqlite3.Error, ValueError, OverflowError):
        return None
    current = now if now is not None else collector.utc_now()
    tasks = []
    for raw_id, title, stamp, workspace, status, source, not_idle, killed in rows:
        if source not in _LOCAL_SOURCES or not isinstance(raw_id, str) or len(raw_id) != 36:
            continue
        try:
            identifier = str(UUID(raw_id))
        except ValueError:
            continue
        if raw_id != identifier:
            continue
        updated = collector._timestamp(stamp)
        if updated is None or updated > current + timedelta(seconds=60):
            continue
        status = _status(status, not_idle, killed, updated, current, collector.STALE_SECONDS)
        label = _display(title, 120) or f"Antigravity 会话 {identifier[:8]}"
        tasks.append(collector._task("antigravity", identifier, label, status, updated, _project(workspace)))
        if len(tasks) >= limit:
            break
    return tasks


def antigravity_available(*, base: Path | str | None = None) -> bool:
    return scan_antigravity(base=base, limit=0) is not None


def _result_index(base, session_id, now):
    from . import collector

    rows = _read(base, 0, session_id=session_id)
    if len(rows) != 1:
        return None
    stamp, status, source, not_idle, killed, count, user_step = rows[0]
    updated = collector._timestamp(stamp)
    if (source not in _LOCAL_SOURCES or not updated or updated > now + timedelta(seconds=60)
            or _status(status, not_idle, killed, updated, now, collector.STALE_SECONDS) != "idle"
            or type(count) is not int or count < 2 or type(user_step) is not int
            or not -1 <= user_step < count - 1):
        return None
    return updated, count, user_step


def _record_time(value):
    from . import collector

    if isinstance(value, str) and re.search(r"(?:Z|[+-][0-9]{2}:[0-9]{2})$", value):
        return collector._timestamp(value)
    return None


def _last_explicit_input(lines, final_index):
    # Projectless desktop sessions can leave the saved input index at -1.
    # Require a contiguous suffix back to a real user turn in that case.
    for offset, raw in enumerate(reversed(lines[-256:]), start=1):
        step = json.loads(raw)
        if (not isinstance(step, dict) or type(step.get("step_index")) is not int
                or step["step_index"] != final_index - offset or step["step_index"] < 0):
            return None
        if step.get("type") == "USER_INPUT" or step.get("source") == "USER_EXPLICIT":
            text = step.get("content")
            if (step.get("type") == "USER_INPUT" and step.get("source") == "USER_EXPLICIT"
                    and step.get("status") == "DONE" and isinstance(text, str) and text.strip()):
                return _record_time(step.get("created_at"))
            return None
    return None


def _last_complete_record(file, size: int, count: int, user_step: int):
    for cap in (_RESULT_FAST_TAIL, _MAX_RESULT_READ_BYTES):
        start = max(0, size - cap)
        file.seek(start)
        tail = file.read(cap)
        if not tail.endswith(b"\n"):
            return None
        line_at = tail.rfind(b"\n", 0, len(tail) - 1)
        if line_at < 0 and start > 0:
            continue
        final = tail[line_at + 1:-1]
        if user_step >= 0:
            return final, None
        if line_at >= 0:
            # For a truncated window never trust the first line, even if it
            # happens to look like JSON. Expand the bounded tail if necessary.
            earlier = tail[:line_at].split(b"\n")
            prompt_at = _last_explicit_input(earlier[1:] if start else earlier, count - 1)
            if prompt_at is not None:
                return final, prompt_at
        if start == 0:
            return None
    return None


def read_antigravity_result(session_id: str, *, base: Path | str | None = None) -> dict | None:
    """Read only the final model text from a confirmed idle, identical trajectory."""
    from . import collector
    from .task_results import MAX_RESULT_CHARS

    try:
        if not isinstance(session_id, str) or len(session_id) != 36 or str(UUID(session_id)) != session_id:
            return None
        now = collector.utc_now()
        index = _result_index(base, session_id, now)
        if index is None:
            return None
        updated, count, user_step = index
        logs = _plain_directory(_base(base) / "brain" / session_id / ".system_generated" / "logs")
        path = logs / "transcript.jsonl"
        initial = _file(path, size_limit=2**63 - 1)
        with path.open("rb") as file:
            before = os.fstat(file.fileno())
            if _stamp(initial) != _stamp(before):
                return None
            latest = _last_complete_record(file, before.st_size, count, user_step)
            if _stamp(os.fstat(file.fileno())) != _stamp(before):
                return None
        if latest is None:
            return None
        raw_step, prompt_at = latest
        step = json.loads(raw_step)
        if (not isinstance(step, dict) or set(step) - _TEXT_STEP_FIELDS
                or step.get("type") != "PLANNER_RESPONSE" or step.get("source") != "MODEL"
                or step.get("status") != "DONE" or step.get("tool_calls", []) != []
                or ("model" in step and not isinstance(step["model"], str))
                or type(step.get("step_index")) is not int or step["step_index"] != count - 1
                or step["step_index"] <= user_step):
            return None
        stamp = _record_time(step.get("created_at"))
        if not stamp or stamp > updated or (prompt_at is not None and prompt_at > stamp):
            return None
        text = step.get("content")
        truncated_fields = step.get("truncated_fields", [])
        if (not isinstance(text, str) or not text.replace("\0", "").strip()
                or not isinstance(truncated_fields, list)
                or any(not isinstance(field, str) for field in truncated_fields)):
            return None
        if (_stamp(_file(path, size_limit=2**63 - 1)) != _stamp(before)
                or _result_index(base, session_id, now) != index):
            return None
        text = text.replace("\0", "")
        truncated = len(text) > MAX_RESULT_CHARS or "content" in truncated_fields
        text = text[:MAX_RESULT_CHARS]
        completed_at = collector._iso(stamp)
        result_id = hashlib.sha256(f"antigravity\n{completed_at}\n{text}".encode()).hexdigest()
        return {"text": text, "completed_at": completed_at, "truncated": truncated,
                "result_id": result_id, "started_at": "", "files": []}
    except (OSError, sqlite3.Error, ValueError, TypeError, OverflowError, RecursionError):
        return None
