"""Optional same-user, local-hub PreToolUse handler for Claude's AskUserQuestion."""
from __future__ import annotations

import argparse
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import time
import uuid

if __package__ in (None, ""):  # Absolute hook script, independent of Claude's working directory.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_monitor.provider_controls import claude_answered_input, claude_question
from agent_monitor.session_controls import Answer
from agent_monitor.store import ONLINE_SECONDS, Store

LEASE_SECONDS = 12
MAX_WAIT = 90
MAX_INPUT = 256 * 1024


def local_principal():
    return _principal(os.getpid())


@lru_cache(maxsize=1)
def _principal(pid):
    # Bind the opt-in to this computer as well as its OS account. A shared path,
    # matching UID/domain login, or two machines with the same name is insufficient.
    import subprocess
    try:
        if os.name == "nt":
            import ctypes
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography",
                                0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
                machine = winreg.QueryValueEx(key, "MachineGuid")[0]
            name = ctypes.create_unicode_buffer(512)
            size = ctypes.c_ulong(len(name))
            if not ctypes.windll.secur32.GetUserNameExW(2, name, ctypes.byref(size)):
                return ""
            owner = name.value.casefold()
        elif sys.platform.startswith("linux"):
            machine = Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()
            owner = str(os.getuid())
        elif sys.platform == "darwin":
            result = subprocess.run(["/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
                                    capture_output=True, check=True, timeout=2)
            match = re.search(rb'"IOPlatformUUID"\s*=\s*"([0-9a-fA-F-]{36})"', result.stdout)
            if not match:
                return ""
            machine = match[1].decode("ascii")
            owner = str(os.getuid())
        else:
            return ""
        identity = uuid.UUID(machine)
        if not identity.int or not owner:
            return ""
        return _digest([sys.platform, str(identity), owner])
    except (OSError, TypeError, ValueError, subprocess.SubprocessError):
        return ""


def local_task(store, source):
    if not source.startswith("claude:"):
        return None
    rows = store.db.execute("""SELECT d.id,t.payload FROM tasks t JOIN devices d ON t.device_id=d.id
        LEFT JOIN task_archive a ON a.device_id=t.device_id AND a.source_id=t.source_id
        WHERE t.source_id=? AND d.local=1 AND d.last_seen>? AND COALESCE(a.archived,0)=0""",
        (source, time.time() - ONLINE_SECONDS)).fetchall()
    if len(rows) == 1 and json.loads(rows[0]["payload"]).get("tool") == "claude":
        return rows[0]["id"]
    return None


def _host_matches(store):
    owner = local_principal()
    row = store.db.execute("SELECT owner FROM claude_question_host WHERE singleton=1").fetchone()
    return bool(owner and row and row["owner"] == owner)


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _finish(store, device, request_id, state):
    with store.db:
        store.db.execute("""UPDATE task_interactions SET state=?,finished_at=?,payload='',decision=''
            WHERE device_id=? AND id=? AND command_id='' AND state IN ('pending','submitted')""",
            (state, time.time(), device, request_id))


def handle(store, reader_id, event, output, *, wait=MAX_WAIT, interval=.5):
    """Write at most one decision to the original stdout; silence hands control back to the desktop."""
    from agent_monitor.task_interactions import PendingInput
    if (not isinstance(event, dict) or event.get("hook_event_name") != "PreToolUse"
            or event.get("tool_name") != "AskUserQuestion" or event.get("agent_id")):
        return
    session, call = event.get("session_id"), event.get("tool_use_id")
    if not isinstance(session, str) or str(uuid.UUID(session)) != session:
        return
    if not isinstance(call, str) or not re.fullmatch(r"[a-zA-Z0-9_.:-]{1,160}", call):
        return
    if not _host_matches(store) or not store.valid_full_reader(reader_id):
        return
    data = event.get("tool_input")
    questions, detail = claude_question(data)
    source = "claude:" + session
    # A new desktop session can issue its first question before the next local scan.
    deadline = time.monotonic() + min(wait, MAX_WAIT)
    discovery_deadline = min(deadline, time.monotonic() + 8)
    while not (device := local_task(store, source)):
        if time.monotonic() >= discovery_deadline:
            return
        time.sleep(interval)
    request_id, revision = _digest(["claude-hook", session, call]), _digest(data)
    now = time.time()
    request = PendingInput(id=request_id, revision=revision, provider="claude", kind="question",
        session_id=session, turn_id=call, title="Claude Code 需要你的选择", detail=detail,
        questions=[q.model_dump(mode="json") for q in questions], expires_at=now + max(0, deadline - time.monotonic()))
    payload = request.model_dump_json()
    with store.db:
        store.db.execute("BEGIN IMMEDIATE")
        if (not store.valid_full_reader(reader_id) or device != local_task(store, source) or not _host_matches(store)
                or store.db.execute("""SELECT 1 FROM task_replies WHERE device_id=? AND source_id=?
                    AND state IN ('dispatching','running')""", (device, source)).fetchone()):
            return
        if store.db.execute("SELECT COUNT(*) FROM task_interactions").fetchone()[0] >= 50000:
            return
        if store.db.execute("""SELECT COUNT(*) FROM task_interactions WHERE device_id=? AND command_id=''
            AND state IN ('pending','submitted') AND expires_at>?""", (device, now)).fetchone()[0] >= 8:
            return
        # Redelivery of the same tool call can never replay the phone decision.
        written = store.db.execute("""INSERT OR IGNORE INTO task_interactions(device_id,id,revision,command_id,reader_id,
            source_id,provider,kind,turn_id,payload,payload_hash,state,expires_at,created_at,hook_seen_at)
            VALUES(?,?,?,'',?,?,'claude','question',?,?,?,'pending',?,?,?)""",
            (device, request_id, revision, reader_id, source, call, payload, hashlib.sha256(payload.encode()).hexdigest(),
             request.expires_at, now, now))
        if written.rowcount != 1:
            return
    final_state = "cancelled"
    next_heartbeat = time.monotonic() + 3
    try:
        while time.monotonic() < deadline:
            with store.db:
                row = store.db.execute("""SELECT state,delivered,decision,hook_seen_at FROM task_interactions
                    WHERE device_id=? AND id=? AND revision=? AND reader_id=?""",
                    (device, request_id, revision, reader_id)).fetchone()
                if (not row or row["state"] not in ("pending", "submitted") or row["delivered"]
                        or row["hook_seen_at"] <= time.time() - LEASE_SECONDS):
                    return
                if row["state"] == "submitted":
                    store.db.execute("BEGIN IMMEDIATE")
                    if (not _host_matches(store) or not store.valid_full_reader(reader_id) or local_task(store, source) != device):
                        return
                    claimed = store.db.execute("""UPDATE task_interactions SET delivered=1 WHERE device_id=? AND id=?
                        AND revision=? AND delivered=0 AND state='submitted' AND expires_at>?""",
                        (device, request_id, revision, time.time()))
                    if claimed.rowcount != 1:
                        return
                    decision = json.loads(row["decision"])
                else:
                    decision = None
                    if time.monotonic() >= next_heartbeat:
                        if not _host_matches(store) or not store.valid_full_reader(reader_id) or local_task(store, source) != device:
                            return
                        store.db.execute("UPDATE task_interactions SET hook_seen_at=? WHERE device_id=? AND id=?",
                                         (time.time(), device, request_id))
                        next_heartbeat = time.monotonic() + 3
            if decision is not None:
                if decision["decision"] != "answer":
                    return
                answers = tuple(Answer.model_validate_json(json.dumps(answer)) for answer in decision["answers"])
                updated = claude_answered_input(data, questions, answers)
                output.write(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                    "permissionDecision": "allow", "updatedInput": updated}}, ensure_ascii=True) + "\n")
                output.flush()
                final_state = "forwarded"
                return
            time.sleep(interval)
        final_state = "expired"
    finally:
        _finish(store, device, request_id, final_state)


def main(argv=None):
    parser = argparse.ArgumentParser(description="将本机 Claude Code 的选择题转交给一台指定手机")
    parser.add_argument("--database", required=True, type=Path, help="本机 Monitor 服务现有 monitor.sqlite3 的绝对路径")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--list-readers", action="store_true")
    group.add_argument("--print-config", action="store_true")
    group.add_argument("--run", action="store_true")
    parser.add_argument("--reader-id")
    args = parser.parse_args(argv)
    if not args.list_readers and not args.reader_id:
        parser.error("请明确指定 --reader-id")
    store = None
    try:
        path = args.database.expanduser().resolve(strict=True)
        if os.name == "nt":
            import ctypes
            if ctypes.windll.kernel32.GetDriveTypeW(path.anchor) not in (2, 3, 6):
                raise ValueError("database must be local")
        store = Store(path, existing_only=True)
        if not _host_matches(store):
            raise ValueError("local owner unavailable")
        if args.list_readers:
            devices = [{"id": row["id"], "name": row["name"]} for row in store.db.execute(
                "SELECT id,name FROM native_readers WHERE mode='full_app' ORDER BY last_seen DESC")
                if store.valid_full_reader(row["id"])]
            print(json.dumps(devices, ensure_ascii=False))
        elif args.print_config:
            if not store.valid_full_reader(args.reader_id):
                raise ValueError("reader unavailable")
            command = {"type": "command", "command": Path(sys.executable).absolute().as_posix(),
                       "args": [Path(__file__).resolve().as_posix(), "--database", path.as_posix(), "--run",
                                "--reader-id=" + args.reader_id], "timeout": MAX_WAIT + 10}
            print(json.dumps({"hooks": {"PreToolUse": [{"matcher": "^AskUserQuestion$", "hooks": [command]}]}}, indent=2))
        else:
            raw = sys.stdin.buffer.read(MAX_INPUT + 1)
            if len(raw) <= MAX_INPUT:
                handle(store, args.reader_id, json.loads(raw), sys.stdout)
        return 0
    except (OSError, ValueError, TypeError, KeyError, RecursionError, sqlite3.Error):
        if args.run:
            return 0
        print("请检查本机 Monitor 服务、数据库路径和完整手机连接。", file=sys.stderr)
        return 1
    finally:
        if store is not None:
            store.close()


if __name__ == "__main__":
    raise SystemExit(main())
