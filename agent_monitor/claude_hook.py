"""Optional, non-decision-making Claude Code status hook.

Generate a settings fragment with --print-config, or use install_claude_hooks.
Normal hook execution emits nothing, returns 0 even on failure, and never calls
a network service. It only stores minimal status in Agent Monitor's directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

try:
    from .collector import _iso, project_name, state_directory, utc_now
except ImportError:  # Allows an absolute script path in Claude's hook command.
    from collector import _iso, project_name, state_directory, utc_now

MAX_INPUT_BYTES = 256 * 1024
HOOK_MARKER = "agent-monitor-status-v1"
EVENT_STATES = {
    "SessionStart": "idle", "UserPromptSubmit": "running",
    "PreToolUse": "running", "PostToolUse": "running",
    "PostToolUseFailure": "running",  # One failed tool does not end a turn.
    "PermissionRequest": "waiting", "Stop": "unknown",  # Refined by the explicit work registry below.
    "StopFailure": "error", "SessionEnd": "idle",
    "Elicitation": "waiting", "ElicitationResult": "running",
}
NOTIFICATION_STATES = {
    "permission_prompt": "waiting",
    "elicitation_dialog": "waiting", "elicitation_url_dialog": "waiting",
}


def _identifier(value) -> str:
    return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}", value) else ""


def _work_count(value, *, scheduled=False) -> int | None:
    """Validate registry shape without retaining descriptions, commands or IDs."""
    if not isinstance(value, list) or len(value) > 4096:
        return None
    for entry in value:
        if not isinstance(entry, dict) or not _identifier(entry.get("id")):
            return None
        if scheduled:
            if (not isinstance(entry.get("recurring"), bool)
                    or not isinstance(entry.get("schedule"), str) or not entry["schedule"]):
                return None
        elif any(not isinstance(entry.get(key), str) or not entry[key] for key in ("type", "status")):
            return None
    return len(value)


def stop_state(background: int | None, scheduled: int | None) -> str:
    # Stop ends the current response, not necessarily the session's background
    # work. Missing registry data is not evidence that those tasks are finished.
    if (background is not None and background > 0) or (scheduled is not None and scheduled > 0):
        return "running"
    return "completed" if background == 0 and scheduled == 0 else "unknown"


def record_event(payload: dict, *, state_dir: Path | str | None = None,
                 received_ns: int | None = None) -> bool:
    """Save only event name, safe identifiers, status, time and project name."""
    if not isinstance(payload, dict):
        return False
    event = payload.get("hook_event_name")
    if not isinstance(event, str):
        return False
    # Tool hooks also fire inside subagents with the *main* session_id. Their
    # lifecycle must not overwrite the parent session's waiting/end state.
    if payload.get("agent_id"):
        return False
    if event == "Notification":
        notification = payload.get("notification_type")
        status = NOTIFICATION_STATES.get(notification) if isinstance(notification, str) else None
    else:
        status = EVENT_STATES.get(event)
    background = scheduled = None
    if event == "Stop":
        background = _work_count(payload.get("background_tasks"))
        scheduled = _work_count(payload.get("session_crons"), scheduled=True)
        status = stop_state(background, scheduled)
    if event == "SessionStart" and payload.get("source") == "compact":
        # Compaction can occur in an active turn, so this is not proof of idle.
        status = "unknown"
    session = _identifier(payload.get("session_id"))
    if not status or not session:
        return False
    stamp_ns = received_ns if received_ns is not None else time.time_ns()
    root = Path(state_dir) if state_dir is not None else state_directory()
    directory = root / "claude-hooks" / hashlib.sha256(session.encode()).hexdigest()
    directory.mkdir(parents=True, exist_ok=True)
    minimal = {
        "schema": 2, "session_id": session, "event": event, "status": status,
        "updated_at": _iso(utc_now()), "project": project_name(payload.get("cwd")),
    }
    prompt = _identifier(payload.get("prompt_id"))
    if prompt:
        minimal["prompt_id"] = prompt
    if event == "Stop":
        if background is not None:
            minimal["background_count"] = background
        if scheduled is not None:
            minimal["scheduled_count"] = scheduled
    # Independent files prevent asynchronous hooks overwriting one another out
    # of order. The receive-time prefix determines the newest observation.
    name = f"state-{stamp_ns:020d}-{uuid.uuid4().hex}.json"
    temporary = directory / (name + ".tmp")
    destination = directory / name
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(minimal, handle, ensure_ascii=False, separators=(",", ":"))
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    # Keep a small amount of state for safe concurrent publication, not history.
    for old in sorted(directory.glob("state-*.json"), reverse=True)[4:]:
        try:
            old.unlink()
        except OSError:
            pass
    return True


def hook_configuration(*, state_dir: str | None = None, python_executable: str | None = None) -> dict:
    # Official exec form avoids shell interpretation and works with paths that
    # contain spaces, quotes or shell metacharacters on all supported platforms.
    arguments = [str(Path(python_executable or sys.executable).resolve()).replace("\\", "/"),
                 str(Path(__file__).resolve()).replace("\\", "/")]
    selected_state = Path(state_dir).expanduser().resolve() if state_dir else state_directory().resolve()
    arguments.extend(["--state-dir", str(selected_state).replace("\\", "/"), "--monitor-hook", HOOK_MARKER])
    hooks = {}
    for event in [*EVENT_STATES, "Notification"]:
        entry = {"hooks": [{"type": "command", "command": arguments[0], "args": arguments[1:], "async": True}]}
        if event == "Notification":
            entry["matcher"] = "^(" + "|".join(NOTIFICATION_STATES) + ")$"
        hooks[event] = [entry]
    return {"hooks": hooks}


def main(argv=None) -> int:
    received_ns = time.time_ns()
    parser = argparse.ArgumentParser(description="Claude Code 的只读状态 hook；不自动安装")
    parser.add_argument("--state-dir", help="Agent Monitor 的状态根目录")
    parser.add_argument("--print-config", action="store_true", help="输出供手动合并的配置片段")
    parser.add_argument("--monitor-hook", choices=[HOOK_MARKER], help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.print_config:
        print(json.dumps(hook_configuration(state_dir=args.state_dir), ensure_ascii=False, indent=2))
        return 0
    try:
        data = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        if len(data) > MAX_INPUT_BYTES:
            return 0
        payload = json.loads(data)
        record_event(payload, state_dir=args.state_dir, received_ns=received_ns)
    except (OSError, ValueError, TypeError, RecursionError):
        # Monitoring must not interfere with the user's coding session.
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
