"""Passive, incremental usage accounting. This is an observed-log ledger, not billing.

Only numeric usage and opaque identifiers leave this module. No credentials,
conversation text, command execution, account requests, or provider configuration
are involved. Missing records, ambiguous resets and midnight gaps remain partial.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import stat
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

UTC = timezone.utc
SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")
MAX_TOKENS = 1_000_000_000_000
MAX_LINE = 256 * 1024
READ_CHUNK = 64 * 1024
MAX_FILE_BYTES = 1024 * 1024
MAX_ENTRIES = 20_000
MAX_FILES = 100_000
MAX_DIRECTORIES = 20_000
MAX_SCAN_SECONDS = 2.0
LOCAL_CODEX_PARSER_VERSION = 2
RECENT_ACCOUNTING_FILES = 32
RECENT_FILE_BYTES = 4 * 1024 * 1024
ACTIVE_ACCOUNTING_FILES_PER_TOOL = 2
ACTIVE_ACCOUNTING_AGE = 15 * 60
ACTIVE_METADATA_SECONDS = 0.1
HISTORY_ANCHOR_BYTES = 2 * 1024 * 1024
HISTORY_ANCHOR_FILES = 8
MAX_BATCH = 200
MAX_PENDING_BYTES = 4 * 1024 * 1024
QUOTA_TAIL_FILES = 8
QUOTA_TAIL_BYTES = 256 * 1024
QUOTA_TAIL_BUDGET = 2 * 1024 * 1024
QUOTA_TAIL_SECONDS = 0.4
HEX64 = re.compile(r"^[0-9a-f]{64}$")
FIELDS = ("input_tokens", "output_tokens", "cached_input_tokens", "cache_write_tokens")
BASES = {"own", "independent", "inherited", "unknown"}


def _now(value=None):
    if value is None:
        return datetime.now(UTC)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return datetime.fromtimestamp(value, UTC)
    if isinstance(value, datetime) and value.tzinfo is not None:
        return value.astimezone(UTC)
    raise ValueError("now must be timezone-aware")


def _iso(value):
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _stamp(value, now):
    if not isinstance(value, str) or len(value) > 48:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            return None
        dt = dt.astimezone(UTC)
        if dt.year < 2000 or dt > now + timedelta(minutes=5):
            return None
        return _iso(dt)
    except (ValueError, OverflowError):
        return None


def _day(stamp):
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(SHANGHAI).date().isoformat()


def _hash(*parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=True, separators=(",", ":")).encode()).hexdigest()


def _id(value):
    return value if isinstance(value, str) and 0 < len(value) <= 256 and not any(ord(c) < 32 for c in value) else None


def _int(value, maximum=MAX_TOKENS):
    return value if type(value) is int and 0 <= value <= maximum else None


def _link(path):
    try:
        info = path.lstat()
        return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)
    except OSError:
        return True


def _safe(path, root):
    """Reject links/junctions in *every* component, including the supplied root."""
    try:
        path = Path(os.path.abspath(path))
        root = Path(os.path.abspath(root))
        path.relative_to(root)
        current = path
        while True:
            if _link(current):
                return False
            if current.parent == current:
                return True
            current = current.parent
    except (ValueError, OSError):
        return False


def _numbers(usage, tool):
    if not isinstance(usage, dict):
        return None
    keys = ("input_tokens", "output_tokens", "cached_input_tokens", "cache_write_input_tokens") if tool == "codex" else (
        "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
    values = [_int(usage.get(key)) for key in keys]
    if any(key in usage and usage[key] is not None and val is None for key, val in zip(keys, values)):
        return None
    # These older Codex logs predate cache-write reporting; it is a detail of
    # input, never added to total. Unknown remains null.
    if tool == "codex":
        total = _int(usage.get("total_tokens"))
        inp, out, cached, write = values
        if inp is None or out is None or total is None or total != inp + out:
            return None
        if cached is not None and cached > inp or write is not None and write > inp:
            return None
        reasoning = _int(usage.get("reasoning_output_tokens"))
        if "reasoning_output_tokens" in usage and reasoning is None or reasoning is not None and reasoning > out:
            return None
    else:
        if values[0] is None or values[1] is None:
            return None
        # Explicit malformed values are not the same as an absent optional field.
        if any(key in usage and usage[key] is not None and val is None for key, val in zip(keys, values)):
            return None
        total = sum(values) if all(v is not None for v in values) else None
        if total is not None and total > MAX_TOKENS:
            return None
    return dict(zip(FIELDS, values), total_tokens=total)


def _event_key(event):
    if event["tool"] == "codex":
        # Same counter at a later timestamp is NOT new consumption. Cache
        # reporting detail can evolve without changing the actual counter.
        if event.get("counter_key"):
            return _hash("codex-counter-segment-v2", event["session_key"], event["counter_key"],
                         event["input_tokens"], event["output_tokens"])
        return _hash("codex-counter-v1", event["session_key"], event["input_tokens"], event["output_tokens"])
    return event["event_key"]


def read_claude_quota_file(claude_home=None):
    """Read the dedicated numeric receiver file with strict path/size limits."""
    from .usage_statusline import decode, safe_path
    home = Path(claude_home) if claude_home is not None else Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude")
    path = home / "monitor-quota.json"
    try:
        if not safe_path(path):
            return None
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_size > 16384:
            return None
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as handle:
            opened = os.fstat(handle.fileno())
            if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or not safe_path(path):
                return None
            raw = handle.read(16385)
        if len(raw) > 16384:
            return None
        payload = decode(raw)
        return payload if isinstance(payload, dict) else None
    except (OSError, ValueError, UnicodeError, RecursionError):
        return None


class UsageLedger:
    """One separate SQLite ledger; safe for the hub's reader and worker threads.

    ``export_batch`` is an at-least-once outbox. Call ``ack_export(cursor)`` only
    after successful hub acknowledgement. Copied response/counter IDs deduplicate
    across devices. Remote ingestion is never put back into the local outbox.
    """

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(self.path), timeout=10, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=10000")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS usage_events (
            event_key TEXT PRIMARY KEY, tool TEXT NOT NULL, session_key TEXT NOT NULL,
            occurred_at TEXT NOT NULL, observed_at TEXT NOT NULL,
            input_tokens INTEGER, output_tokens INTEGER, cached_input_tokens INTEGER,
            cache_write_tokens INTEGER, total_tokens INTEGER, final INTEGER NOT NULL,
            basis TEXT NOT NULL, started_at TEXT, day TEXT, contribution INTEGER,
            conflict INTEGER NOT NULL DEFAULT 0);
          CREATE INDEX IF NOT EXISTS usage_events_day ON usage_events(tool,day);
          CREATE INDEX IF NOT EXISTS usage_events_session ON usage_events(tool,session_key);
          CREATE TABLE IF NOT EXISTS usage_sessions (
            session_key TEXT PRIMARY KEY, tool TEXT NOT NULL, stamp TEXT,
            high_input INTEGER, high_output INTEGER, cached_input INTEGER, cache_write INTEGER,
            offset_input INTEGER NOT NULL DEFAULT 0, offset_output INTEGER NOT NULL DEFAULT 0,
            own_total INTEGER, blocked INTEGER NOT NULL DEFAULT 0, basis TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS usage_sources (
            event_key TEXT NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY(event_key,device_id));
          CREATE TABLE IF NOT EXISTS usage_quotas (
            quota_id TEXT PRIMARY KEY, tool TEXT NOT NULL, device_id TEXT NOT NULL,
            window_key TEXT NOT NULL, used_percent REAL NOT NULL, window_minutes INTEGER NOT NULL,
            resets_at INTEGER, observed_at TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS usage_files (
            path TEXT PRIMARY KEY, root TEXT NOT NULL, tool TEXT NOT NULL,
            identity TEXT, offset INTEGER NOT NULL DEFAULT 0, head_hash TEXT, head_len INTEGER,
            checkpoint TEXT, meta TEXT NOT NULL DEFAULT '{}', skipping INTEGER NOT NULL DEFAULT 0,
            scanned_at REAL NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0,
            generation INTEGER NOT NULL DEFAULT 0, mtime REAL NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS usage_changes (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, item_key TEXT);
          CREATE TABLE IF NOT EXISTS usage_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL);
        """)
        if "mtime" not in {row[1] for row in self.db.execute("PRAGMA table_info(usage_files)")}:
            self.db.execute("ALTER TABLE usage_files ADD COLUMN mtime REAL NOT NULL DEFAULT 0")
        event_columns = {row[1] for row in self.db.execute("PRAGMA table_info(usage_events)")}
        for name, kind in (("counter_key", "TEXT"), ("anchor_input", "INTEGER"),
                           ("anchor_output", "INTEGER"), ("anchor_at", "TEXT")):
            if name not in event_columns:
                self.db.execute(f"ALTER TABLE usage_events ADD COLUMN {name} {kind}")
        if "owner_key" not in {row[1] for row in self.db.execute("PRAGMA table_info(usage_sessions)")}:
            self.db.execute("ALTER TABLE usage_sessions ADD COLUMN owner_key TEXT")
        self.db.execute("CREATE INDEX IF NOT EXISTS usage_sessions_owner ON usage_sessions(owner_key)")
        # Match task_usage's legacy/segment fallback expressions. A plain
        # owner_key index cannot serve COALESCE; without the counter/time index,
        # each task scans all source rows and sorts unrelated events.
        self.db.execute("CREATE INDEX IF NOT EXISTS usage_sessions_effective_owner "
                        "ON usage_sessions(COALESCE(owner_key,session_key))")
        self.db.execute("CREATE INDEX IF NOT EXISTS usage_events_counter_time "
                        "ON usage_events(COALESCE(counter_key,session_key),occurred_at DESC)")
        self.db.commit()
        self._pending = {}
        self._directories = deque()
        self._iterator = None
        self._roots = None
        self._discovery_complete = False
        self._anchor_bytes = self._anchor_files = 0
        with self.db:
            self._migrate_local_codex()

    def _migrate_local_codex(self):
        """Invalidate only local Codex accounting produced by the old parser.

        Deployment must back up this ledger first. Replaying is bounded by the
        normal scanner, so local totals remain partial while it catches up.
        Remote event rows/contributions, Claude, and quotas are retained. This
        does not retract erroneous records already exported to another hub;
        that hub needs a source-specific repair before accepting replacement
        historical accounting from this device.
        """
        version = self.db.execute("SELECT value FROM usage_meta WHERE key='local_codex_parser_version'").fetchone()
        if version and int(version[0]) >= LOCAL_CODEX_PARSER_VERSION:
            return
        self.db.execute("""CREATE TEMP TABLE codex_rebuild_events AS
            SELECT e.event_key,e.session_key,COALESCE(e.counter_key,e.session_key) counter_key
            FROM usage_events e JOIN usage_sources s USING(event_key)
            WHERE e.tool='codex' AND s.device_id='local'""")
        count = self.db.execute("SELECT count(*) FROM codex_rebuild_events").fetchone()[0]
        self.db.execute("DELETE FROM usage_changes WHERE kind='event' AND item_key IN (SELECT event_key FROM codex_rebuild_events)")
        self.db.execute("DELETE FROM usage_sources WHERE device_id='local' AND event_key IN (SELECT event_key FROM codex_rebuild_events)")
        self.db.execute("""DELETE FROM usage_events WHERE event_key IN (SELECT event_key FROM codex_rebuild_events)
            AND NOT EXISTS (SELECT 1 FROM usage_sources s WHERE s.event_key=usage_events.event_key)""")
        self.db.execute("DELETE FROM usage_sessions WHERE session_key IN (SELECT counter_key FROM codex_rebuild_events)")
        # Reconstruct surviving remote counter state; do not rewrite its stored
        # daily contribution or event metadata during local invalidation.
        for row in self.db.execute("""SELECT * FROM usage_events WHERE tool='codex'
                AND COALESCE(counter_key,session_key) IN (SELECT counter_key FROM codex_rebuild_events)
                ORDER BY occurred_at,event_key""").fetchall():
            self._codex_advance(dict(row))
        self.db.execute("""UPDATE usage_files SET offset=0,meta='{}',skipping=0,scanned_at=0,
            checkpoint=NULL,head_hash=NULL,head_len=NULL,generation=generation+1 WHERE tool='codex'""")
        self.db.execute("DROP TABLE codex_rebuild_events")
        if count:
            self._stat("local_codex_rebuilds")
            self._set("local_codex_rebuilt_events", count)
        self._set("local_codex_parser_version", LOCAL_CODEX_PARSER_VERSION)

    def close(self):
        with self.lock:
            if self._iterator:
                self._iterator[0].close()
            self.db.close()

    def _stat(self, name, amount=1):
        row = self.db.execute("SELECT value FROM usage_meta WHERE key=?", (name,)).fetchone()
        value = int(row[0]) if row else 0
        self.db.execute("INSERT OR REPLACE INTO usage_meta VALUES (?,?)", (name, str(value + amount)))

    def _set(self, name, value):
        self.db.execute("INSERT OR REPLACE INTO usage_meta VALUES (?,?)", (name, str(value)))

    def _changed(self, kind, key):
        self.db.execute("INSERT INTO usage_changes(kind,item_key) VALUES (?,?)", (kind, key))

    def _discover(self, roots, deadline, now):
        if roots != self._roots or (not self._directories and self._iterator is None):
            if self._iterator:
                self._iterator[0].close()
            self._iterator = None
            directories = [(tool, str(root), str(root), 0) for tool, root in roots if _safe(root, root)]
            priority = []
            for tool, root in roots:
                if tool == "codex" and root.name == "sessions":
                    for date in (now.astimezone(SHANGHAI), now.astimezone(SHANGHAI) - timedelta(days=1)):
                        recent = root / date.strftime("%Y") / date.strftime("%m") / date.strftime("%d")
                        if recent.is_dir() and _safe(recent, root):
                            priority.append((tool, str(root), str(recent), 3))
            self._directories = deque(priority + directories)
            self._roots = roots
            self._discovery_complete = False
        examined = 0
        new_files = 0
        file_count = self.db.execute("SELECT count(*) FROM usage_files").fetchone()[0]
        while examined < MAX_ENTRIES and time.monotonic() < deadline:
            if self._iterator is None:
                if not self._directories:
                    self._discovery_complete = True
                    break
                tool, root, directory, depth = self._directories.popleft()
                if not _safe(directory, root):
                    continue
                try:
                    self._iterator = (os.scandir(directory), tool, root, depth)
                except OSError:
                    self._stat("read_errors")
                    continue
            iterator, tool, root, depth = self._iterator
            try:
                entry = next(iterator)
            except StopIteration:
                iterator.close()
                self._iterator = None
                continue
            except OSError:
                iterator.close()
                self._iterator = None
                self._stat("read_errors")
                continue
            examined += 1
            try:
                if entry.is_symlink() or _link(Path(entry.path)):
                    continue
                if entry.is_dir(follow_symlinks=False):
                    if depth < 24 and len(self._directories) < MAX_DIRECTORIES:
                        self._directories.append((tool, root, entry.path, depth + 1))
                    else:
                        self._stat("discovery_capped")
                elif entry.name.endswith(".jsonl") and entry.is_file(follow_symlinks=False):
                    if file_count >= MAX_FILES:
                        self._stat("discovery_capped")
                        continue
                    changed = self.db.execute("INSERT OR IGNORE INTO usage_files(path,root,tool) VALUES(?,?,?)",
                                              (entry.path, root, tool)).rowcount
                    info = entry.stat(follow_symlinks=False)
                    self.db.execute("UPDATE usage_files SET mtime=?,size=? WHERE path=?",
                                    (info.st_mtime, info.st_size, entry.path))
                    file_count += changed
                    new_files += changed
            except OSError:
                self._stat("read_errors")
        return examined, new_files

    def _active_accounting_files(self, now, deadline):
        """Refresh a bounded recent shortlist, without reading transcript tails.

        Discovery can take several cycles over a large log tree. Stat both
        tools' recent known files in alternation so its cached mtime does not
        leave a currently growing transcript behind old replay work.
        """
        candidates = [self.db.execute("""SELECT * FROM usage_files WHERE tool=?
            ORDER BY mtime DESC,path LIMIT ?""", (tool, RECENT_ACCOUNTING_FILES)).fetchall()
            for tool in ("codex", "claude")]
        active = {"codex": [], "claude": []}
        for index in range(max(map(len, candidates), default=0)):
            for rows in candidates:
                if time.monotonic() >= deadline:
                    break
                if index >= len(rows):
                    continue
                row = dict(rows[index])
                path, root = Path(row["path"]), Path(row["root"])
                try:
                    if not _safe(path, root):
                        continue
                    info = path.stat()
                    if not stat.S_ISREG(info.st_mode):
                        continue
                    row.update(mtime=info.st_mtime, size=info.st_size)
                    self.db.execute("UPDATE usage_files SET mtime=?,size=? WHERE path=?",
                                    (row["mtime"], row["size"], row["path"]))
                    if (row["mtime"] >= now.timestamp() - ACTIVE_ACCOUNTING_AGE
                            and row["size"] - row["offset"] > MAX_FILE_BYTES):
                        active[row["tool"]].append(row)
                except OSError:
                    continue  # The ordinary fair scanner records read errors.
            else:
                continue
            break
        return [sorted(rows, key=lambda row: (-row["mtime"], row["path"]))[:ACTIVE_ACCOUNTING_FILES_PER_TOOL]
                for rows in active.values() if rows]

    def scan(self, codex_home=None, claude_home=None, budget_bytes=8 * 1024 * 1024, now=None):
        """Read at most budget_bytes accounting bytes and two seconds per cycle.

        Directory traversal and parsing have separate entry/line/depth caps.
        Complete lines only are committed; incomplete lines stay in bounded RAM,
        and a restart safely rereads them from the durable newline cursor.
        A separate quota-only tail pass reads <=2 MiB/0.4 seconds within that
        same time deadline. Its bytes are reported separately; cumulative token
        counters from that pass never enter accounting or change file cursors.
        Known history-base anchors use a separate <=2 MiB/8-file probe budget,
        reported separately and still within the same overall time deadline.
        """
        now = _now(now)
        if type(budget_bytes) is not int or not 1 <= budget_bytes <= 64 * 1024 * 1024:
            raise ValueError("invalid scan byte budget")
        codex = Path(codex_home) if codex_home is not None else Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")
        claude = Path(claude_home) if claude_home is not None else Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude")
        roots = tuple((tool, Path(os.path.abspath(root))) for tool, root in (
            ("codex", codex / "sessions"), ("codex", codex / "archived_sessions"), ("claude", claude / "projects")))
        started = time.monotonic()
        deadline = started + MAX_SCAN_SECONDS
        with self.lock, self.db:
            self._anchor_bytes = self._anchor_files = 0
            examined, discovered = self._discover(roots, min(deadline, started + 0.35), now)
            quota_tail = self._scan_quota_tails(now, min(deadline, time.monotonic() + QUOTA_TAIL_SECONDS))
            read = lines = files = 0
            self._scan_deadline = deadline
            active = self._active_accounting_files(now, min(deadline, time.monotonic() + ACTIVE_METADATA_SECONDS))
            recent_budget = max(1, budget_bytes * 3 // 4)
            recent_deadline = time.monotonic() + max(0, deadline - time.monotonic()) * 0.65
            seen = set()
            # Half the total byte budget belongs to the newest still-updating
            # large logs, split equally between tools, then up to two files per
            # tool. They must advance on every cycle even while thousands of
            # old files have earlier scanned_at values. All reads still begin
            # at the durable cursor; this never invents a tail baseline.
            active_budget = budget_bytes // 2
            active_seconds = max(0, recent_deadline - time.monotonic()) * 2 / 3
            for candidates in active:
                file_budget = active_budget // len(active) // len(candidates)
                file_seconds = active_seconds / len(active) / len(candidates)
                for row in candidates:
                    if file_budget < 1 or time.monotonic() >= recent_deadline:
                        break
                    count, parsed = self._scan_file(row, min(RECENT_FILE_BYTES, file_budget),
                        min(recent_deadline, time.monotonic() + file_seconds), now)
                    read += count
                    lines += parsed
                    files += 1
                    seen.add(row["path"])
            # A busy append must not rotate behind hundreds of already-read
            # recent files. Reserve capacity for the newest growing files,
            # while leaving both bytes and time for fair historical progress.
            hot = self.db.execute("""SELECT * FROM usage_files WHERE mtime>=?
                AND (offset<size OR mtime*1000000000>scanned_at)
                ORDER BY mtime DESC,path LIMIT ?""",
                (now.timestamp() - 2 * 86400, RECENT_ACCOUNTING_FILES)).fetchall()
            hot = sorted(hot, key=lambda row: (row["size"] - row["offset"] > MAX_FILE_BYTES, row["scanned_at"], -row["mtime"]))
            for row in hot:
                if row["path"] in seen:
                    continue
                if read >= recent_budget or time.monotonic() >= recent_deadline:
                    break
                count, parsed = self._scan_file(row, min(RECENT_FILE_BYTES, recent_budget - read), recent_deadline, now)
                read += count
                lines += parsed
                files += 1
                seen.add(row["path"])
            # Fair rotating work list: a huge old transcript cannot monopolise a
            # cycle or starve Claude behind Codex files forever.
            recent = self.db.execute("""SELECT * FROM usage_files WHERE mtime>=?
                ORDER BY scanned_at,mtime DESC LIMIT 128""", (now.timestamp() - 2 * 86400,)).fetchall()
            # On a rebuild every cursor can have scanned_at=0. Break those
            # ties toward old history rather than duplicating the recent list.
            backfill = self.db.execute("SELECT * FROM usage_files ORDER BY scanned_at,mtime,path LIMIT 256").fetchall()
            rows = []
            for index in range(max(len(recent), len(backfill))):
                for candidates in (recent, backfill):
                    if index < len(candidates) and candidates[index]["path"] not in seen:
                        rows.append(candidates[index])
                        seen.add(candidates[index]["path"])
            for row in rows:
                if read >= budget_bytes or time.monotonic() >= deadline:
                    break
                count, parsed = self._scan_file(row, min(MAX_FILE_BYTES, budget_bytes - read), deadline, now)
                read += count
                lines += parsed
                files += 1
            self._set("last_scan_at", _iso(now))
            self._set("last_scan_bytes", read)
            self._set("quota_tail_bytes", quota_tail["bytes_read"])
            self._set("quota_tail_files", quota_tail["files_checked"])
            self._set("history_anchor_bytes", self._anchor_bytes)
            self._set("history_anchor_files", self._anchor_files)
            self._set("discovery_complete", int(self._discovery_complete))
            backlog = self.db.execute("SELECT count(*) FROM usage_files WHERE offset<size").fetchone()[0]
            return {"bytes_read": read, "lines_read": lines, "files_checked": files,
                    "entries_examined": examined, "files_discovered": discovered,
                    "backlog_files": backlog, "discovery_complete": self._discovery_complete,
                    "quota_tail_bytes": quota_tail["bytes_read"], "quota_tail_files": quota_tail["files_checked"],
                    "history_anchor_bytes": self._anchor_bytes, "history_anchor_files": self._anchor_files,
                    "coverage": "partial", "elapsed_ms": round((time.monotonic() - started) * 1000)}

    def _history_anchor(self, base, session_key, started_at, current_path, now):
        """Resolve a known rollout-segment boundary, never a model last total.

        The final UUID in a rollout filename identifies its physical segment;
        the first header identifies its logical conversation. Copied archive
        files can agree on the same anchor; disagreement is left unknown.
        """
        reference = base.get("thread_id")
        cutoff = _int(base.get("end_byte_offset"), 1 << 50)
        if not isinstance(reference, str) or not re.fullmatch(r"[0-9a-fA-F-]{36}", reference) or not cutoff or not started_at:
            return None
        rows = self.db.execute("SELECT path,root FROM usage_files WHERE tool='codex' AND path LIKE ? LIMIT 5",
                               ("%" + reference + ".jsonl",)).fetchall()
        if len(rows) > 4:
            return None
        anchors = []
        for row in rows:
            if (time.monotonic() >= getattr(self, "_scan_deadline", float("inf"))
                    or self._anchor_bytes >= HISTORY_ANCHOR_BYTES or self._anchor_files >= HISTORY_ANCHOR_FILES):
                return None
            path, root = Path(row["path"]), Path(row["root"])
            if path == current_path or not _safe(path, root):
                continue
            try:
                info = path.stat()
                if not stat.S_ISREG(info.st_mode) or info.st_size < cutoff:
                    continue
                descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
                with os.fdopen(descriptor, "rb") as handle:
                    self._anchor_files += 1
                    opened = os.fstat(handle.fileno())
                    if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or not _safe(path, root):
                        continue
                    head = handle.readline(min(MAX_LINE + 1, HISTORY_ANCHOR_BYTES - self._anchor_bytes))
                    self._anchor_bytes += len(head)
                    if len(head) > MAX_LINE or not head.endswith(b"\n"):
                        continue
                    record = json.loads(head)
                    payload = record.get("payload") if isinstance(record, dict) else None
                    if not isinstance(record, dict) or record.get("type") != "session_meta" or not isinstance(payload, dict):
                        continue
                    owner = _id(payload.get("id") or payload.get("session_id"))
                    parent_start = _stamp(payload.get("timestamp"), now) or _stamp(record.get("timestamp"), now)
                    if not owner or _hash("codex", owner) != session_key or not parent_start or parent_start >= started_at:
                        continue
                    remaining = HISTORY_ANCHOR_BYTES - self._anchor_bytes
                    if remaining <= 0:
                        return None
                    start = max(0, cutoff - min(MAX_LINE, remaining))
                    handle.seek(start)
                    raw = handle.read(cutoff - start)
                    self._anchor_bytes += len(raw)
                if not raw.endswith(b"\n"):
                    continue
                records = raw.split(b"\n")[1 if start else 0:-1]
                for line in reversed(records):
                    if not line.strip():
                        continue
                    try:
                        point = json.loads(line)
                    except (ValueError, UnicodeError, RecursionError):
                        return None
                    if not isinstance(point, dict) or point.get("type") != "event_msg":
                        continue
                    event = point.get("payload")
                    if not isinstance(event, dict) or event.get("type") != "token_count":
                        continue
                    stamp = _stamp(point.get("timestamp"), now)
                    usage = event.get("info")
                    if usage is None:  # quota-only snapshot, no new counter
                        continue
                    values = _numbers(usage.get("total_token_usage"), "codex") if isinstance(usage, dict) else None
                    if values and stamp and parent_start <= stamp <= started_at:
                        anchors.append((values["input_tokens"], values["output_tokens"], stamp))
                        break
                    return None  # never silently anchor before a malformed latest counter
            except (OSError, ValueError, UnicodeError, RecursionError):
                continue
        return anchors[0] if anchors and all(anchor == anchors[0] for anchor in anchors) else None

    def _scan_quota_tails(self, now, deadline):
        """Refresh only timestamped quota snapshots independently of backfill.

        Huge active transcripts can take many accounting cycles to reach EOF.
        Quota freshness must follow their latest complete token_count records,
        without treating a tail cumulative counter as today's new consumption.
        """
        read = checked = 0
        candidates = self.db.execute("""SELECT path,root FROM usage_files WHERE tool='codex'
            ORDER BY mtime DESC,path LIMIT ?""", (QUOTA_TAIL_FILES,)).fetchall()
        for row in candidates:
            if read >= QUOTA_TAIL_BUDGET or time.monotonic() >= deadline:
                break
            path, root = Path(row["path"]), Path(row["root"])
            if not _safe(path, root):
                continue
            try:
                info = path.stat()
                if not stat.S_ISREG(info.st_mode) or info.st_size == 0:
                    continue
                amount = min(QUOTA_TAIL_BYTES, QUOTA_TAIL_BUDGET - read, info.st_size)
                start = info.st_size - amount
                descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
                with os.fdopen(descriptor, "rb") as handle:
                    opened = os.fstat(handle.fileno())
                    if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or not _safe(path, root):
                        continue
                    handle.seek(start)
                    raw = handle.read(amount)
                read += len(raw)
                checked += 1
                # Even a parseable first fragment must be discarded when its
                # beginning lies outside the read window. The last fragment is
                # either empty (newline) or an unfinished append; discard both.
                lines = raw.split(b"\n")
                if start:
                    lines = lines[1:]
                lines = lines[:-1]
                for line in reversed(lines):
                    if time.monotonic() >= deadline:
                        break
                    if not line or len(line) > MAX_LINE:
                        continue
                    try:
                        record = json.loads(line)
                    except (ValueError, UnicodeError, RecursionError):
                        continue
                    if not isinstance(record, dict) or record.get("type") != "event_msg":
                        continue
                    payload = record.get("payload")
                    if not isinstance(payload, dict) or payload.get("type") != "token_count":
                        continue
                    stamp = _stamp(record.get("timestamp"), now)
                    if stamp and isinstance(payload.get("rate_limits"), dict):
                        self._codex_quotas(payload["rate_limits"], stamp, now)
            except OSError:
                continue
        return {"bytes_read": read, "files_checked": checked}

    def _scan_file(self, row, budget, deadline, now):
        path, root = Path(row["path"]), Path(row["root"])
        self.db.execute("UPDATE usage_files SET scanned_at=? WHERE path=?", (time.time_ns(), str(path)))
        if not _safe(path, root):
            self._stat("read_errors")
            return 0, 0
        read = lines = 0
        try:
            info = path.stat()
            if not stat.S_ISREG(info.st_mode):
                return 0, 0
            identity = f"{info.st_dev}:{info.st_ino}"
            offset = row["offset"]
            meta = json.loads(row["meta"])
            skipping = bool(row["skipping"])
            pending = self._pending.pop(str(path), b"")
            descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as handle:
                opened = os.fstat(handle.fileno())
                # Check again after opening to avoid reading a changed/reparse
                # target on Windows, where O_NOFOLLOW is unavailable.
                if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or not _safe(path, root):
                    self._stat("read_errors")
                    return 0, 0
                # Metadata probes are <=320 bytes/file and never parsed/logged.
                head_len = min(row["head_len"] or 256, info.st_size)
                head_hash = hashlib.sha256(handle.read(head_len)).hexdigest()
                changed = (row["identity"] not in (None, identity) or info.st_size < offset + len(pending)
                           or row["head_hash"] is not None and row["head_len"] == head_len and row["head_hash"] != head_hash)
                if not changed and offset and row["checkpoint"]:
                    handle.seek(max(0, offset - 64))
                    changed = hashlib.sha256(handle.read(min(64, offset))).hexdigest() != row["checkpoint"]
                if changed:
                    offset, pending, meta, skipping = 0, b"", {}, False
                    self._stat("file_restarts")
                    self.db.execute("UPDATE usage_files SET generation=generation+1 WHERE path=?", (str(path),))
                handle.seek(offset + len(pending))
                while read < budget and time.monotonic() < deadline:
                    chunk = handle.read(min(READ_CHUNK, budget - read))
                    if not chunk:
                        break
                    read += len(chunk)
                    data = pending + chunk
                    pending = b""
                    while b"\n" in data:
                        raw, data = data.split(b"\n", 1)
                        offset += len(raw) + 1
                        if skipping:
                            skipping = False
                            continue
                        if len(raw) > MAX_LINE:
                            self._stat("oversized_lines")
                            continue
                        if not raw.strip():
                            continue
                        lines += 1
                        try:
                            record = json.loads(raw)
                            if isinstance(record, dict):
                                self._record(record, row["tool"], meta, path, now)
                        except (ValueError, UnicodeError, RecursionError):
                            self._stat("invalid_records")
                    if skipping or len(data) > MAX_LINE:
                        if not skipping:
                            self._stat("oversized_lines")
                        skipping = True
                        offset += len(data)
                    else:
                        pending = data
                if pending:
                    self._pending[str(path)] = pending
                    while sum(len(value) for value in self._pending.values()) > MAX_PENDING_BYTES:
                        # The durable cursor still points before this fragment;
                        # eviction costs a bounded reread, never lost accounting.
                        self._pending.pop(next(iter(self._pending)))
                handle.seek(max(0, offset - 64))
                checkpoint = hashlib.sha256(handle.read(min(64, offset))).hexdigest() if offset else None
            self.db.execute("""UPDATE usage_files SET identity=?,offset=?,head_hash=?,head_len=?,checkpoint=?,
                meta=?,skipping=?,size=?,mtime=? WHERE path=?""", (identity, offset, head_hash, head_len, checkpoint,
                    json.dumps(meta, separators=(",", ":")), int(skipping), info.st_size, info.st_mtime, str(path)))
        except (OSError, ValueError):
            self._stat("read_errors")
        return read, lines

    def _record(self, record, tool, meta, path, now):
        stamp = _stamp(record.get("timestamp"), now)
        if tool == "codex":
            payload = record.get("payload")
            if not isinstance(payload, dict):
                return
            if record.get("type") == "session_meta":
                # Rollouts begin with their own metadata, then can embed parent
                # metadata whose outer timestamp has been rewritten. Only the
                # owning first header can establish identity and fork semantics.
                if meta.get("owner_pinned"):
                    return
                meta["owner_pinned"] = True
                session = _id(payload.get("id") or payload.get("session_id"))
                if session:
                    meta.clear()
                    stamp = _stamp(payload.get("timestamp"), now) or stamp
                    source = payload.get("source")
                    fork = bool(payload.get("forked_from_id") or payload.get("parent_thread_id") or
                                isinstance(source, dict) and "subagent" in source)
                    meta.update(session_key=_hash("codex", session), started_at=stamp, owner_pinned=True,
                                basis="inherited" if fork else "own",
                                independent_candidate=bool(fork and payload.get("history_mode") == "paginated"))
                    base = payload.get("history_base")
                    if isinstance(base, dict):
                        reference = _id(base.get("thread_id"))
                        ordinal = _int(base.get("end_ordinal_exclusive"), 1 << 50)
                        offset = _int(base.get("end_byte_offset"), 1 << 50)
                        if reference and ordinal is not None and offset is not None and stamp:
                            meta["counter_key"] = _hash("codex-segment-v2", meta["session_key"], stamp, reference, ordinal, offset)
                            anchor = self._history_anchor(base, meta["session_key"], stamp, path, now)
                            if anchor:
                                meta.update(anchor_input=anchor[0], anchor_output=anchor[1], anchor_at=anchor[2])
                        else:
                            # Malformed inheritance metadata is not evidence of
                            # a new zero-based independent conversation.
                            meta.update(basis="unknown", independent_candidate=False)
                return
            if record.get("type") != "event_msg" or payload.get("type") != "token_count" or not stamp:
                return
            self._codex_quotas(payload.get("rate_limits"), stamp, now)
            info = payload.get("info")
            if not isinstance(info, dict) or not meta.get("session_key"):
                return
            numbers = _numbers(info.get("total_token_usage"), "codex")
            if numbers is None:
                self._stat("invalid_usage")
                return
            if meta.get("counter_key") and meta.get("anchor_input") is not None and (
                    meta["anchor_input"] > numbers["input_tokens"] or meta["anchor_output"] > numbers["output_tokens"]):
                # The declared parent cut does not establish a monotone start.
                # Keep this segment partial, rather than exporting a bad anchor.
                for key in ("anchor_input", "anchor_output", "anchor_at"):
                    meta.pop(key, None)
                self._stat("invalid_history_anchors")
            if meta.get("started_at") and stamp < meta["started_at"]:
                self._stat("inherited_records")
                return
            last = _numbers(info.get("last_token_usage"), "codex")
            basis = meta.get("basis", "unknown")
            if not meta.get("counter_seen"):
                if last and all(last[field] == numbers[field] for field in ("input_tokens", "output_tokens")) and meta.get("started_at"):
                    if not meta.get("counter_key") and (basis == "own" or meta.get("independent_candidate")):
                        basis = "independent"
                # Only the proven first point may advertise a zero baseline.
                # A later outbox page must never charge an entire cumulative
                # history as a fresh first response at a different hub.
                meta["basis"] = "own" if basis == "independent" else basis
                meta["counter_seen"] = True
            event = dict(numbers, tool="codex", session_key=meta["session_key"], occurred_at=stamp,
                         observed_at=_iso(now), final=True, basis=basis, started_at=meta.get("started_at"))
            if meta.get("counter_key"):
                event.update({key: meta.get(key) for key in ("counter_key", "anchor_input", "anchor_output", "anchor_at")})
            event["event_key"] = _event_key(event)
        else:
            if record.get("type") != "assistant" or not stamp:
                return
            message = record.get("message")
            if not isinstance(message, dict):
                return
            response = _id(message.get("id"))
            session = _id(record.get("sessionId"))
            numbers = _numbers(message.get("usage"), "claude")
            if not response or not session or numbers is None:
                if message.get("usage") is not None:
                    self._stat("invalid_usage")
                return
            # Subagents often reuse the root sessionId. Keep own-session totals
            # separate; provider totals still include all responses exactly once.
            subagent = path.stem if "subagents" in path.parts else None
            session_key = _hash("claude", session) if subagent is None else _hash("claude-subagent", session, subagent)
            event = dict(numbers, event_key=_hash("claude-response-v1", response), tool="claude",
                         session_key=session_key, occurred_at=stamp, observed_at=_iso(now),
                         final=isinstance(message.get("stop_reason"), str) and bool(message.get("stop_reason")),
                         basis="own", started_at=None)
        self._upsert(event, "local", local=True)

    def _codex_quotas(self, limits, stamp, now):
        if not isinstance(limits, dict):
            return
        limit_id = _id(limits.get("limit_id")) or "codex"
        for slot in ("primary", "secondary"):
            window = limits.get(slot)
            if not isinstance(window, dict):
                continue
            quota = {"tool": "codex", "key": _hash("codex-limit", limit_id, slot),
                     "used_percent": window.get("used_percent"), "window_minutes": window.get("window_minutes"),
                     "resets_at": window.get("resets_at"), "observed_at": stamp}
            if self._valid_quota(quota, now):
                self._quota(quota, "local", local=True)
            else:
                self._stat("invalid_quotas")

    def _upsert(self, event, device_id, *, local=False):
        key = event["event_key"]
        old = self.db.execute("SELECT * FROM usage_events WHERE event_key=?", (key,)).fetchone()
        self.db.execute("INSERT OR IGNORE INTO usage_sources VALUES (?,?)", (key, device_id))
        if old:
            if old["tool"] != event["tool"] or old["session_key"] != event["session_key"]:
                self.db.execute("UPDATE usage_events SET conflict=1 WHERE event_key=?", (key,))
                self._stat("identity_conflicts")
                return False
            if event["tool"] == "codex":
                return False
            same = all(old[field] == event[field] for field in FIELDS)
            if old["final"]:
                if event["final"] and not same:
                    self.db.execute("UPDATE usage_events SET conflict=1 WHERE event_key=?", (key,))
                    self._stat("usage_conflicts")
                return False
            # Never construct a usage vector from component-wise maxima. Older
            # placeholders cannot replace final or newer accumulated output.
            if event["occurred_at"] < old["occurred_at"]:
                return False
            if any(old[field] is not None and event[field] is not None and old[field] != event[field]
                   for field in ("input_tokens", "cached_input_tokens", "cache_write_tokens")):
                self.db.execute("UPDATE usage_events SET conflict=1 WHERE event_key=?", (key,))
                self._stat("usage_conflicts")
                return False
            if not event["final"] and (same or event["output_tokens"] < old["output_tokens"]):
                return False
            if event["output_tokens"] < old["output_tokens"]:
                self.db.execute("UPDATE usage_events SET conflict=1 WHERE event_key=?", (key,))
                self._stat("usage_conflicts")
                return False
        day = _day(event["occurred_at"]) if event["tool"] == "claude" and event["final"] else None
        contribution = event["total_tokens"] if day else None
        if event["tool"] == "codex":
            day, contribution = self._codex_advance(event)
        values = [event[k] for k in ("event_key", "tool", "session_key", "occurred_at", "observed_at", *FIELDS,
                                   "total_tokens", "final", "basis", "started_at")]
        self.db.execute("""INSERT OR REPLACE INTO usage_events(event_key,tool,session_key,occurred_at,observed_at,
            input_tokens,output_tokens,cached_input_tokens,cache_write_tokens,total_tokens,final,basis,started_at,
            day,contribution,conflict,counter_key,anchor_input,anchor_output,anchor_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (*values, day, contribution, old["conflict"] if old else 0,
                         *(event.get(key) for key in ("counter_key", "anchor_input", "anchor_output", "anchor_at"))))
        if local:
            self._changed("event", key)
        return True

    def _codex_advance(self, event):
        key, stamp = event.get("counter_key") or event["session_key"], event["occurred_at"]
        inp, out, total = event["input_tokens"], event["output_tokens"], event["total_tokens"]
        old = self.db.execute("SELECT * FROM usage_sessions WHERE session_key=?", (key,)).fetchone()
        day, contribution = None, None
        if old is None:
            inherited = event["basis"] in {"inherited", "unknown"} or bool(event.get("counter_key"))
            oi, oo = (inp, out) if inherited else (0, 0)
            anchor_input, anchor_output = event.get("anchor_input"), event.get("anchor_output")
            anchored = (bool(event.get("counter_key")) and type(anchor_input) is int and type(anchor_output) is int
                        and 0 <= anchor_input <= inp and 0 <= anchor_output <= out and event.get("anchor_at"))
            if anchored:
                oi, oo = anchor_input, anchor_output
            # A verified first independent counter has a genuine zero baseline.
            if anchored and _day(stamp) == _day(event["anchor_at"]):
                day, contribution = _day(stamp), total - oi - oo
            elif not event.get("counter_key") and event["basis"] == "independent" and event.get("started_at") and _day(stamp) == _day(event["started_at"]):
                day, contribution = _day(stamp), total
            else:
                self._stat("missing_baselines")
            self.db.execute("""INSERT INTO usage_sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                            (key, "codex", stamp, inp, out, event["cached_input_tokens"], event["cache_write_tokens"],
                             oi, oo, total - oi - oo, 0, event["basis"], event["session_key"]))
            return day, contribution
        # Established older snapshots are replays, even if their byte/file
        # position differs. A newer decreasing vector is an ambiguous reset.
        if stamp <= old["stamp"]:
            return None, None
        if old["blocked"]:
            return None, None
        if inp < old["high_input"] or out < old["high_output"]:
            self.db.execute("UPDATE usage_sessions SET blocked=1,own_total=NULL WHERE session_key=?", (key,))
            self._stat("ambiguous_resets")
            return None, None
        delta = total - old["high_input"] - old["high_output"]
        if _day(stamp) == _day(old["stamp"]):
            day, contribution = _day(stamp), delta
        else:
            self._stat("midnight_gaps")
        self.db.execute("""UPDATE usage_sessions SET stamp=?,high_input=?,high_output=?,cached_input=?,cache_write=?,
            own_total=? WHERE session_key=?""", (stamp, inp, out, event["cached_input_tokens"], event["cache_write_tokens"],
                                                total - old["offset_input"] - old["offset_output"], key))
        return day, contribution

    @staticmethod
    def _valid_quota(quota, now):
        if not isinstance(quota, dict) or quota.get("tool") not in {"codex", "claude"}:
            return False
        if not isinstance(quota.get("key"), str) or not HEX64.fullmatch(quota["key"]):
            return False
        percent, minutes, reset = quota.get("used_percent"), quota.get("window_minutes"), quota.get("resets_at")
        if type(percent) not in (int, float) or not math.isfinite(percent) or not 0 <= percent <= 100:
            return False
        if _int(minutes, 525600) is None or minutes == 0:
            return False
        if reset is not None and (_int(reset, int(now.timestamp()) + 366 * 86400) is None or reset < 946684800):
            return False
        return _stamp(quota.get("observed_at"), now) is not None

    def _quota(self, quota, device_id, *, local=False):
        key = _hash("quota-source", device_id, quota["tool"], quota["key"])
        old = self.db.execute("SELECT * FROM usage_quotas WHERE quota_id=?", (key,)).fetchone()
        stamp = quota["observed_at"]
        if old and stamp <= old["observed_at"]:
            return False
        self.db.execute("INSERT OR REPLACE INTO usage_quotas VALUES(?,?,?,?,?,?,?,?)", (
            key, quota["tool"], device_id, quota["key"], quota["used_percent"], quota["window_minutes"], quota.get("resets_at"), stamp))
        if local:
            self._changed("quota", key)
        return True

    def ingest_claude_quota(self, payload, now=None, device_id="local"):
        """Accept only the documented statusline windows, never its text/context.

        The caller must arrange an opt-in statusline receiver. This method does
        not install hooks, replace statusline settings or query private APIs.
        """
        now = _now(now)
        if not _id(device_id) or not isinstance(payload, dict):
            return {"accepted": 0, "rejected": 1}
        stamp = _stamp(payload.get("observed_at"), now)
        limits = payload.get("rate_limits")
        if stamp is None or not isinstance(limits, dict):
            return {"accepted": 0, "rejected": 1}
        accepted = rejected = 0
        with self.lock, self.db:
            for slot, minutes in (("five_hour", 300), ("seven_day", 10080)):
                window = limits.get(slot)
                if window is None:
                    continue
                if not isinstance(window, dict):
                    rejected += 1
                    continue
                quota = {"tool": "claude", "key": _hash("claude-statusline-limit", slot),
                         "used_percent": window.get("used_percentage"), "window_minutes": minutes,
                         "resets_at": window.get("resets_at"), "observed_at": stamp}
                if self._valid_quota(quota, now):
                    self._quota(quota, device_id, local=device_id == "local")
                    accepted += 1
                else:
                    rejected += 1
        return {"accepted": accepted, "rejected": rejected}

    def summary(self, now=None, device_ids=None):
        now = _now(now)
        today = now.astimezone(SHANGHAI).date()
        day = today.isoformat()
        history_days = [(today - timedelta(days=offset)).isoformat() for offset in range(89, -1, -1)]
        scope_sql, scope_args, quota_sql = "", [], ""
        if device_ids is not None:
            if not isinstance(device_ids, (list, tuple, set)) or len(device_ids) > 1000 or any(not _id(item) for item in device_ids):
                raise ValueError("invalid source devices")
            scope_args = list(device_ids)
            placeholders = ",".join("?" for _ in scope_args) or "NULL"
            scope_sql = (" AND EXISTS (SELECT 1 FROM usage_sources s WHERE s.event_key=usage_events.event_key "
                         f"AND s.device_id IN ({placeholders}))")
            quota_sql = f" AND device_id IN ({placeholders})"
        with self.lock:
            providers = []
            for tool in ("codex", "claude"):
                totals = self.db.execute("""SELECT sum(contribution) AS tokens,count(DISTINCT session_key) AS sessions,
                    max(observed_at) AS observed FROM usage_events WHERE tool=? AND day=? AND occurred_at<=?""" + scope_sql,
                                         (tool, day, _iso(now), *scope_args)).fetchone()
                known = self.db.execute("SELECT max(observed_at) FROM usage_events WHERE tool=?" + scope_sql,
                                        (tool, *scope_args)).fetchone()[0]
                pending = self.db.execute("SELECT count(*) FROM usage_events WHERE tool=? AND final=0" + scope_sql,
                                          (tool, *scope_args)).fetchone()[0]
                # One indexed range aggregation per provider, not 90 queries.
                # EXISTS scopes event identities without multiplying copied
                # events by their source count. These are observed partial
                # contributions, never a complete bill or estimated quota.
                daily = {row["day"]: row["tokens"] for row in self.db.execute("""SELECT day,sum(contribution) AS tokens
                    FROM usage_events WHERE tool=? AND day>=? AND day<=? AND occurred_at<=?"""
                    + scope_sql + " GROUP BY day", (tool, history_days[0], day, _iso(now), *scope_args))}
                history = {"start_day": history_days[0], "end_day": day, "timezone": "Asia/Shanghai",
                           "days": [{"day": date, "tokens": daily.get(date),
                                     "coverage": "partial" if daily.get(date) is not None else "unavailable"}
                                    for date in history_days]}
                quotas = []
                for row in self.db.execute("SELECT * FROM usage_quotas WHERE tool=?" + quota_sql + " ORDER BY device_id,window_minutes",
                                           (tool, *scope_args)):
                    minutes = row["window_minutes"]
                    label = f"{minutes // 1440} 天" if minutes % 1440 == 0 else f"{minutes // 60} 小时" if minutes % 60 == 0 else f"{minutes} 分钟"
                    observed = datetime.fromisoformat(row["observed_at"].replace("Z", "+00:00"))
                    expired = row["resets_at"] is not None and row["resets_at"] <= now.timestamp()
                    quotas.append({"key": row["window_key"], "label": label, "used_percent": row["used_percent"],
                                   "remaining_percent": max(0, min(100, 100 - row["used_percent"])),
                                   "window_minutes": minutes, "resets_at": row["resets_at"],
                                   "observed_at": row["observed_at"], "source_device_id": row["device_id"],
                                   "stale": expired or (now - observed).total_seconds() > 900,
                                   "availability": "expired" if expired else "observed", "account_scope": "unverified"})
                providers.append({"tool": tool, "today_tokens": totals["tokens"], "session_count": totals["sessions"],
                                  "observed_at": known, "coverage": "partial" if known else "unavailable",
                                  "quotas": quotas, "pending_requests": pending, "source": "local_logs",
                                  "history": history})
            statistics = {row["key"]: row["value"] for row in self.db.execute("SELECT * FROM usage_meta")}
            scan = {key: int(value) for key, value in statistics.items() if key != "last_scan_at"}
            scan["last_scan_at"] = statistics.get("last_scan_at")
            scan["known_files"] = self.db.execute("SELECT count(*) FROM usage_files").fetchone()[0]
            scan["backlog_files"] = self.db.execute("SELECT count(*) FROM usage_files WHERE offset<size").fetchone()[0]
            if device_ids is not None and "local" not in device_ids:
                scan = {}
            return {"day": day, "timezone": "Asia/Shanghai", "coverage": "partial", "providers": providers,
                    "scan": scan, "source": "local_logs", "semantics": "observed_tokens_including_cache"}

    def task_usage(self, tool, source_id, now=None, device_id=None):
        _now(now)
        if tool not in {"codex", "claude"} or not isinstance(source_id, str):
            raise ValueError("invalid task identity")
        raw_id = source_id[len(tool) + 1:] if source_id.startswith(tool + ":") else source_id
        key = _hash(tool, raw_id)
        result = dict.fromkeys(("total_tokens", *FIELDS, "observed_at"))
        result.update(tool=tool, coverage="unavailable", source="local_logs", scope="own_session")
        with self.lock:
            if device_id is not None and not self.db.execute("""SELECT 1 FROM usage_events e JOIN usage_sources s
                    ON e.event_key=s.event_key WHERE e.session_key=? AND e.tool=? AND s.device_id=? LIMIT 1""",
                    (key, tool, device_id)).fetchone():
                return result
            if tool == "codex":
                segments = []
                for row in self.db.execute("SELECT * FROM usage_sessions WHERE COALESCE(owner_key,session_key)=?", (key,)).fetchall():
                    point = None
                    if device_id is not None:
                        point = self.db.execute("""SELECT e.* FROM usage_events e JOIN usage_sources s
                            ON e.event_key=s.event_key WHERE COALESCE(e.counter_key,e.session_key)=? AND s.device_id=?
                            ORDER BY e.occurred_at DESC LIMIT 1""", (row["session_key"], device_id)).fetchone()
                        if point is None:
                            continue
                    inp = point["input_tokens"] if point else row["high_input"]
                    out = point["output_tokens"] if point else row["high_output"]
                    inherited = row["offset_input"] != 0 or row["offset_output"] != 0
                    segments.append(dict(
                        input_tokens=None if row["blocked"] else max(0, inp - row["offset_input"]),
                        output_tokens=None if row["blocked"] else max(0, out - row["offset_output"]),
                        total_tokens=None if row["blocked"] else max(0, inp + out - row["offset_input"] - row["offset_output"]),
                        cached_input_tokens=None if inherited else (point["cached_input_tokens"] if point else row["cached_input"]),
                        cache_write_tokens=None if inherited else (point["cache_write_tokens"] if point else row["cache_write"]),
                        observed_at=point["occurred_at"] if point else row["stamp"]))
                if segments:
                    for field in ("total_tokens", "input_tokens", "output_tokens"):
                        result[field] = sum(segment[field] for segment in segments) if all(segment[field] is not None for segment in segments) else None
                    # Cache subsets cannot be safely combined across a branch.
                    if len(segments) == 1:
                        for field in ("cached_input_tokens", "cache_write_tokens"):
                            result[field] = segments[0][field]
                    result.update(observed_at=max(segment["observed_at"] for segment in segments), coverage="partial")
            else:
                scoped = " AND EXISTS (SELECT 1 FROM usage_sources s WHERE s.event_key=usage_events.event_key AND s.device_id=?)" if device_id is not None else ""
                rows = self.db.execute("SELECT * FROM usage_events WHERE tool='claude' AND session_key=?" + scoped,
                                       (key, device_id) if device_id is not None else (key,)).fetchall()
                if rows:
                    for field in ("total_tokens", *FIELDS):
                        result[field] = sum(row[field] for row in rows) if all(row[field] is not None for row in rows) else None
                    result.update(observed_at=max(row["observed_at"] for row in rows), coverage="partial",
                                  pending_requests=sum(not row["final"] for row in rows))
        return result

    @staticmethod
    def _public_event(row):
        fields = ("event_key", "tool", "session_key", "occurred_at", "observed_at", *FIELDS,
                  "total_tokens", "final", "basis", "started_at")
        result = {key: row[key] for key in fields}
        if row["tool"] == "codex" and row["counter_key"]:
            result.update({key: row[key] for key in ("counter_key", "anchor_input", "anchor_output", "anchor_at")})
        result["final"] = bool(result["final"])
        return result

    def export_batch(self, limit=MAX_BATCH):
        if type(limit) is not int or not 1 <= limit <= MAX_BATCH:
            raise ValueError("invalid export limit")
        with self.lock:
            changes = self.db.execute("SELECT * FROM usage_changes ORDER BY seq LIMIT ?", (limit,)).fetchall()
            events, quotas, seen = [], [], set()
            for change in changes:
                identity = change["kind"], change["item_key"]
                if identity in seen:
                    continue
                seen.add(identity)
                if change["kind"] == "event":
                    row = self.db.execute("SELECT * FROM usage_events WHERE event_key=?", (change["item_key"],)).fetchone()
                    if row:
                        events.append(self._public_event(row))
                else:
                    row = self.db.execute("SELECT * FROM usage_quotas WHERE quota_id=?", (change["item_key"],)).fetchone()
                    if row:
                        quotas.append({"tool": row["tool"], "key": row["window_key"], "used_percent": row["used_percent"],
                                       "window_minutes": row["window_minutes"], "resets_at": row["resets_at"], "observed_at": row["observed_at"]})
            return {"version": 1, "events": events, "quotas": quotas, "cursor": changes[-1]["seq"] if changes else 0}

    def export_quota_batch(self, limit=20):
        """Small current local snapshots, independent of the accounting outbox.

        Do not acknowledge these: the ordinary FIFO cursor belongs exclusively
        to export_batch. Repeated quota snapshots are timestamp-idempotent at the
        hub; sending them first prevents historical token backfill delaying the
        current account window on a newly installed connector.
        """
        if type(limit) is not int or not 1 <= limit <= 20:
            raise ValueError("invalid quota export limit")
        with self.lock:
            rows = self.db.execute("""SELECT * FROM usage_quotas WHERE device_id='local'
                ORDER BY observed_at DESC,quota_id LIMIT ?""", (limit,)).fetchall()
            quotas = [{"tool": row["tool"], "key": row["window_key"], "used_percent": row["used_percent"],
                       "window_minutes": row["window_minutes"], "resets_at": row["resets_at"],
                       "observed_at": row["observed_at"]} for row in rows]
            return {"version": 1, "events": [], "quotas": quotas}

    def ack_export(self, cursor):
        if type(cursor) is not int or cursor < 0:
            raise ValueError("invalid export cursor")
        with self.lock, self.db:
            self.db.execute("DELETE FROM usage_changes WHERE seq<=?", (cursor,))

    @staticmethod
    def _validate_event(event, now):
        if not isinstance(event, dict) or len(event) > 24:
            return None
        tool = event.get("tool")
        if tool not in {"codex", "claude"} or event.get("basis") not in BASES or type(event.get("final")) is not bool:
            return None
        if any(not isinstance(event.get(k), str) or not HEX64.fullmatch(event[k]) for k in ("event_key", "session_key")):
            return None
        occurred, observed = _stamp(event.get("occurred_at"), now), _stamp(event.get("observed_at"), now)
        started = _stamp(event.get("started_at"), now) if event.get("started_at") else None
        if not occurred or not observed or event.get("started_at") is not None and not started:
            return None
        if started is not None and started > occurred:
            return None
        values = []
        for field in (*FIELDS, "total_tokens"):
            value = event.get(field)
            if value is not None and _int(value) is None:
                return None
            values.append(value)
        inp, out, cached, write, total = values
        if inp is None or out is None:
            return None
        segment = {}
        if tool == "codex":
            if total is None or total != inp + out or cached is not None and cached > inp or write is not None and write > inp:
                return None
            if event["event_key"] != _event_key(event) or not event["final"]:
                return None
            if event.get("counter_key") is not None:
                counter_key = event["counter_key"]
                if not isinstance(counter_key, str) or not HEX64.fullmatch(counter_key) or not started:
                    return None
                ai, ao, at = (event.get(key) for key in ("anchor_input", "anchor_output", "anchor_at"))
                if any(value is not None for value in (ai, ao, at)):
                    at = _stamp(at, now)
                    if _int(ai) is None or _int(ao) is None or ai > inp or ao > out or not at or at > started:
                        return None
                segment = dict(counter_key=counter_key, anchor_input=ai, anchor_output=ao, anchor_at=at)
            elif any(event.get(key) is not None for key in ("anchor_input", "anchor_output", "anchor_at")):
                return None
        elif total != (inp + out + cached + write if cached is not None and write is not None else None):
            return None
        # Strict reconstruction drops arbitrary text, absolute paths and fields.
        return dict(zip((*FIELDS, "total_tokens"), values), tool=tool, session_key=event["session_key"],
                    event_key=event["event_key"], occurred_at=occurred, observed_at=observed, started_at=started,
                    final=event["final"], basis=event["basis"], **segment)

    def ingest_batch(self, device_id, payload, now=None):
        now = _now(now)
        if not _id(device_id):
            raise ValueError("invalid source device")
        if not isinstance(payload, dict) or payload.get("version") != 1:
            return {"accepted": 0, "rejected": 1}
        events, quotas = payload.get("events", []), payload.get("quotas", [])
        if not isinstance(events, list) or not isinstance(quotas, list) or len(events) + len(quotas) > MAX_BATCH:
            return {"accepted": 0, "rejected": 1}
        accepted = rejected = 0
        with self.lock, self.db:
            for item in events:
                event = self._validate_event(item, now)
                if event is None:
                    rejected += 1
                else:
                    self._upsert(event, device_id)
                    accepted += 1
            for quota in quotas:
                if self._valid_quota(quota, now):
                    clean = {key: quota.get(key) for key in ("tool", "key", "used_percent", "window_minutes", "resets_at", "observed_at")}
                    clean["observed_at"] = _stamp(clean["observed_at"], now)
                    self._quota(clean, device_id)
                    accepted += 1
                else:
                    rejected += 1
        return {"accepted": accepted, "rejected": rejected}
