"""Bounded, read-only adapters for local coding-agent session logs.

The adapters never open auth files, issue commands to either agent, or infer
successful work from prose. ``completed`` means an explicit end-of-turn event.
Local formats can change; unrecognized lifecycle data deliberately stays unknown.
"""
from __future__ import annotations

import hashlib
import heapq
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path, PureWindowsPath
from typing import Any

MAX_SESSION_FILES = 50  # per tool, including files later rejected as subagents
MAX_DISCOVERED_FILES = 20_000
MAX_LOG_BYTES = 256 * 1024
MAX_CODEX_RECOVERY_BYTES = 1024 * 1024
HEAD_BYTES = 64 * 1024
STALE_SECONDS = 5 * 60
OUTPUT_CHAR_LIMIT = 2_000
STATUS_PREVIEWS = {
    "running": "正在处理任务",
    "waiting": "等待确认或补充信息",
    "completed": "本轮已结束",
    "error": "本轮遇到错误",
    "unknown": "当前状态暂时无法确认",
    "idle": "会话未在执行",
}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _timestamp(value: Any) -> datetime | None:
    try:
        if isinstance(value, bool):
            return None
        if isinstance(value, (float, int)):
            if value > 10_000_000_000:
                value /= 1000
            return datetime.fromtimestamp(value, timezone.utc)
        if isinstance(value, str):
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
    except (ValueError, TypeError, OverflowError, OSError):
        pass
    return None


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _text(value: Any, limit: int = 120) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.replace("\x00", "").split())[:limit]


def project_name(value: Any) -> str:
    """Windows paths must also be stripped correctly on a Linux relay."""
    if not isinstance(value, str) or not value:
        return ""
    value = value.rstrip("/\\")
    if "\\" in value or re.match(r"^[A-Za-z]:", value):
        name = PureWindowsPath(value).name
    else:
        name = Path(value).name
    return _text(name, 80)


def state_directory() -> Path:
    custom = os.environ.get("AGENT_MONITOR_STATE_DIR")
    return Path(custom).expanduser() if custom else Path(__file__).resolve().parents[1] / "state"


def _json_lines(data: bytes) -> list[dict[str, Any]]:
    rows = []
    for line in data.splitlines(keepends=True):
        # A writer may still be appending the final line. Do not consume it yet.
        if not line.endswith(b"\n"):
            continue
        try:
            record = json.loads(line)
            if isinstance(record, dict):
                rows.append(record)
        except (ValueError, UnicodeError, RecursionError):
            continue
    return rows


def _read_window(path: Path, *, max_bytes: int = MAX_LOG_BYTES) -> tuple[list[dict], list[dict], bool]:
    """Read one bounded window; never combine head/tail states across its gap."""
    with path.open("rb") as handle:
        size = os.fstat(handle.fileno()).st_size
        if size <= max_bytes:
            records = _json_lines(handle.read(max_bytes))
            return records, records, False
        head = _json_lines(handle.read(HEAD_BYTES))
        start = size - (max_bytes - HEAD_BYTES)
        handle.seek(start)
        tail = handle.read(max_bytes - HEAD_BYTES)
        # First line may begin midway through UTF-8 / JSON. Always discard it.
        newline = tail.find(b"\n")
        tail = tail[newline + 1:] if newline >= 0 else b""
        return head, _json_lines(tail), True


def _recent_files(base: Path, limit: int, *, claude: bool = False) -> tuple[list[Path], int, bool]:
    candidates: list[tuple[float, str, Path]] = []
    discovered = 0
    capped = False
    if not base.is_dir():
        return [], 0, False
    # No symlink traversal; do not wander into linked projects or auth stores.
    for directory, dirs, names in os.walk(base, followlinks=False):
        dirs[:] = [name for name in dirs if not (Path(directory) / name).is_symlink()]
        if claude:
            # Only projects/<project>/<session>.jsonl, excluding subagents.
            if Path(directory) != base:
                dirs[:] = []
        for name in names:
            if not name.endswith(".jsonl"):
                continue
            path = Path(directory) / name
            try:
                if path.is_symlink() or not path.is_file():
                    continue
                item = (path.stat().st_mtime, str(path), path)
            except OSError:
                continue
            discovered += 1
            if limit and len(candidates) < limit:
                heapq.heappush(candidates, item)
            elif limit and item > candidates[0]:
                heapq.heapreplace(candidates, item)
            if discovered >= MAX_DISCOVERED_FILES:
                capped = True
                break
        if capped:
            break
    return [item[2] for item in sorted(candidates, reverse=True)], discovered, capped


def _default_id(path: Path) -> str:
    match = re.search(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", path.stem)
    return match.group(0) if match else hashlib.sha256(path.name.encode()).hexdigest()[:24]


def _task(tool: str, session: str, title: str, status: str, updated: datetime,
          project: str, output: str = "", source: str = "local_log") -> dict:
    return {
        "id": f"{tool}:{session}", "tool": tool,
        "title": title or f"{'Codex' if tool == 'codex' else 'Claude Code'} 会话 {session[:8]}",
        "status": status, "updated_at": _iso(updated),
        "preview": STATUS_PREVIEWS[status], "output": output[:OUTPUT_CHAR_LIMIT],
        "project": project, "status_source": source,
    }


def _assistant_text(content: Any) -> str:
    if isinstance(content, str):
        return content[:OUTPUT_CHAR_LIMIT]
    if not isinstance(content, list):
        return ""
    # Never include tool inputs/results, reasoning, attachments or user prompts.
    texts = [item.get("text", "") for item in content if isinstance(item, dict)
             and item.get("type") in {"text", "output_text"} and isinstance(item.get("text"), str)]
    return "\n".join(texts)[:OUTPUT_CHAR_LIMIT]


def _codex_titles(home: Path) -> dict[str, str]:
    try:
        head, tail, gap = _read_window(home / "session_index.jsonl")
    except OSError:
        return {}
    result = {}
    for record in (head + tail if gap else tail):
        session = _text(record.get("id"), 160)
        title = _text(record.get("thread_name"))
        if session and title:
            result[session] = title
    return result


def parse_codex_session(path: Path, *, titles: dict[str, str] | None = None,
                        include_output: bool = False, now: datetime | None = None) -> dict | None:
    now = now or utc_now()
    head, tail, gap = _read_window(path)
    task = _parse_codex_window(path, head, tail, titles=titles, include_output=include_output, now=now)
    if task is not None and task["status"] == "unknown" and gap:
        # A single screenshot/tool result can occupy the entire 192 KiB tail.
        # mtime only gates extra I/O, including when no complete tail row remains;
        # it is never activity evidence. Old files keep the ordinary 256 KiB cap.
        modified = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
        if -60 <= (now - modified).total_seconds() <= STALE_SECONDS:
            head, tail, _ = _read_window(path, max_bytes=MAX_CODEX_RECOVERY_BYTES)
            # Start again from unknown over this complete, contiguous tail.
            # Do not retain state from the first read or bridge its omitted bytes.
            task = _parse_codex_window(path, head, tail, titles=titles, include_output=include_output, now=now)
    return task


def _parse_codex_window(path: Path, head: list[dict], tail: list[dict], *,
                        titles: dict[str, str] | None, include_output: bool, now: datetime) -> dict | None:
    session, project = _default_id(path), ""
    for record in head:
        payload = record.get("payload")
        if record.get("type") == "session_meta" and isinstance(payload, dict):
            source = payload.get("source")
            if payload.get("parent_thread_id") or (isinstance(source, dict) and "subagent" in source):
                return None
            session = _text(payload.get("id") or payload.get("session_id"), 160) or session
            project = project_name(payload.get("cwd"))
            break
    if not head and not tail:
        return None
    status = "unknown"
    evidence: datetime | None = None
    latest: datetime | None = None
    output = ""
    active_events = {"task_started", "turn_started", "exec_command_begin", "exec_command_output_delta",
                     "exec_command_end", "mcp_tool_call_begin", "mcp_tool_call_end", "patch_apply_begin",
                     "patch_apply_end", "agent_message_delta", "agent_message_content_delta"}
    waiting_events = {"exec_approval_request", "apply_patch_approval_request", "request_permissions",
                      "request_user_input", "elicitation_request"}
    for record in tail:
        payload = record.get("payload")
        if not isinstance(payload, dict):
            continue
        stamp = _timestamp(record.get("timestamp")) or _timestamp(payload.get("timestamp"))
        if stamp and (latest is None or stamp > latest):
            latest = stamp
        kind = payload.get("type")
        if record.get("type") == "event_msg":
            if kind in active_events:
                status, evidence = "running", stamp
            elif kind in waiting_events:
                status, evidence = "waiting", stamp
            elif kind in {"task_complete", "turn_complete"}:
                # A closed turn can carry a failure (for example an API
                # authentication error) without a separate error event.
                failed = bool(payload.get("error")) or payload.get("status") == "failed"
                status, evidence = ("error" if failed else "completed"), stamp
            elif kind in {"error", "turn_failed", "task_failed"}:
                status, evidence = "error", stamp
            elif kind in {"turn_aborted", "shutdown_complete"}:
                status, evidence = "idle", stamp
            elif kind == "user_message" and status in {"completed", "error", "idle"}:
                status, evidence = "unknown", stamp
            elif kind in {"agent_message", "item_completed"} and status == "running":
                # Keep an already-proven run fresh, never use text to end it.
                evidence = stamp or evidence
        if record.get("type") == "response_item" and kind in {"function_call", "custom_tool_call"} and stamp is not None:
            # Desktop logs may retain a tool invocation after the turn-start
            # event falls outside the bounded tail. The invocation is activity;
            # its output or an item-completed event alone cannot start a run.
            status, evidence = "running", stamp
        if (record.get("type") == "response_item" and stamp is not None and status in {"unknown", "running"}
                and -60 <= (now - stamp).total_seconds() <= STALE_SECONDS):
            if kind == "reasoning" or (kind == "message" and payload.get("role") == "assistant"
                                       and payload.get("phase") == "commentary"):
                # Model-generated response metadata also proves recent activity
                # after a window gap. It cannot dismiss an approval wait or
                # revive a terminated turn; no text content is interpreted.
                status, evidence = "running", stamp
        if record.get("type") == "response_item" and kind == "message":
            if payload.get("role") == "user" and status in {"completed", "error", "idle"}:
                status, evidence = "unknown", stamp
            if payload.get("role") == "assistant":
                if status == "running":
                    evidence = stamp or evidence
        if record.get("type") == "turn_context" and not project:
            project = project_name(payload.get("cwd"))
    if status in {"running", "waiting"} and (evidence is None or (now - evidence).total_seconds() > STALE_SECONDS
                                             or (evidence - now).total_seconds() > 60):
        status = "unknown"
    # mtime orders discovery only. It never establishes a lifecycle state.
    updated = latest or datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    task = _task("codex", session, (titles or {}).get(session, ""), status, updated, project)
    _add_final_preview(task, tail, "codex", include_output)
    return task


def _claude_session(path: Path, *, include_output: bool = False) -> tuple[dict | None, dict]:
    head, tail, gap = _read_window(path)
    if not head and not tail:
        return None, {}
    session, project, title, output = _default_id(path), "", "", ""
    latest: datetime | None = None
    latest_prompt: datetime | None = None
    latest_prompt_id = ""
    for record in (head + tail if gap else tail):
        if record.get("isSidechain") is True:
            continue
        session = _text(record.get("sessionId"), 160) or session
        project = project_name(record.get("cwd")) or project
        kind = record.get("type")
        if kind == "custom-title":
            title = _text(record.get("customTitle")) or title
        elif kind == "ai-title" and not title:
            title = _text(record.get("aiTitle"))
        stamp = _timestamp(record.get("timestamp"))
        if stamp and (latest is None or stamp > latest):
            latest = stamp
        # Only an actual new user prompt invalidates a prior lifecycle hook.
        # Metadata, assistant output and tool results can be flushed *after*
        # Stop; the official hook protocol explicitly documents that lag.
        message = record.get("message")
        if kind == "user" and stamp and isinstance(message, dict) and not any(
            record.get(flag) for flag in ("isMeta", "isCompactSummary", "isVisibleInTranscriptOnly", "toolUseResult")
        ):
            content = message.get("content")
            is_prompt = isinstance(content, str) or (isinstance(content, list) and any(
                isinstance(block, dict) and block.get("type") in {"text", "image"} for block in content
            ) and not any(isinstance(block, dict) and block.get("type") == "tool_result" for block in content))
            if is_prompt and (latest_prompt is None or stamp >= latest_prompt):
                latest_prompt = stamp
                latest_prompt_id = _text(record.get("promptId") or record.get("prompt_id"), 160)
    updated = latest or datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    # assistant.stop_reason=end_turn can precede later tool work / hook blocking.
    # Plain transcripts deliberately never claim the current run is complete.
    task = _task("claude", session, title, "unknown", updated, project)
    _add_final_preview(task, tail, "claude", include_output)
    return task, {
        "prompt_at": latest_prompt, "prompt_id": latest_prompt_id,
    }


def parse_claude_session(path: Path, *, include_output: bool = False) -> dict | None:
    return _claude_session(path, include_output=include_output)[0]


def _add_final_preview(task: dict, records: list[dict], tool: str, include_output: bool):
    # Import lazily so the final-output module may reuse our bounded log reader.
    from .task_results import extract_final
    final = extract_final(records, tool) if include_output else None
    task["output"] = final["text"][:OUTPUT_CHAR_LIMIT] if final else ""
    task["final_result_id"] = final["result_id"] if final else ""
    task["final_result_at"] = final["completed_at"] if final else ""


def _hook_tasks(limit: int, now: datetime) -> list[dict]:
    base = state_directory() / "claude-hooks"
    if not base.is_dir():
        return []
    directories = []
    try:
        for path in base.iterdir():
            if path.is_dir() and not path.is_symlink():
                directories.append((path.stat().st_mtime, str(path), path))
            if len(directories) >= MAX_DISCOVERED_FILES:
                break
    except OSError:
        return []
    tasks = []
    for _, _, directory in sorted(directories, reverse=True)[:limit]:
        try:
            files = sorted(directory.glob("state-*.json"), reverse=True)[:4]
            records = []
            for path in files:
                if path.is_symlink():
                    continue
                with path.open("rb") as handle:
                    data = json.loads(handle.read(4096))
                if not isinstance(data, dict) or data.get("schema") not in {1, 2}:
                    continue
                records.append(data)
            # An async Stop handler from the preceding prompt can start late.
            # Current prompt/tool lifecycle evidence takes precedence when IDs
            # conflict, even after UserPromptSubmit leaves the bounded window.
            activity_events = {"UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure",
                               "PermissionRequest", "Elicitation", "ElicitationResult", "StopFailure"}
            active_prompt = next((_text(record.get("prompt_id"), 160) for record in records
                                  if record.get("event") in activity_events and record.get("prompt_id")), "")
            for data in records:
                session = _text(data.get("session_id"), 160)
                status = data.get("status")
                stamp = _timestamp(data.get("updated_at"))
                if not session or status not in STATUS_PREVIEWS or stamp is None:
                    continue
                if data.get("event") in {"Stop", "Notification"}:
                    prompt_id = _text(data.get("prompt_id"), 160)
                    if active_prompt and prompt_id and prompt_id != active_prompt:
                        continue
                if data.get("event") == "Stop":
                    # Older snapshots discarded the background registry. Do
                    # not preserve their unsupported claim of completion.
                    from .claude_hook import stop_state
                    def count(key):
                        value = data.get(key) if data.get("schema") == 2 else None
                        return value if type(value) is int and 0 <= value <= 4096 else None
                    status = stop_state(count("background_count"), count("scheduled_count"))
                elif data.get("event") == "Notification" and status == "completed":
                    # Legacy idle notifications do not prove background tasks
                    # have finished and must not replace a real lifecycle event.
                    continue
                if status in {"running", "waiting"} and ((now - stamp).total_seconds() > STALE_SECONDS
                                                        or (stamp - now).total_seconds() > 60):
                    status = "unknown"
                task = _task("claude", session, "", status, stamp,
                             project_name(data.get("project")), source="hook")
                task["_prompt_id"] = _text(data.get("prompt_id"), 160)
                tasks.append(task)
                break
        except (OSError, ValueError, TypeError, RecursionError):
            continue
    return tasks


def collect_snapshot(codex_home=None, claude_home=None, include_output=False, limit=50) -> dict:
    """Return a serializable read-only snapshot; ``limit`` caps total tasks at 50.

    Each tool examines at most ``limit`` recent JSONL files, <=256 KiB per file.
    Claude hook state lives in this app's state directory, never in Claude's.
    Default output is empty; titles and project basenames remain visible.
    """
    limit = min(MAX_SESSION_FILES, max(0, int(limit)))
    codex_home = Path(codex_home).expanduser() if codex_home is not None else Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex")))
    claude_home = Path(claude_home).expanduser() if claude_home is not None else Path(os.environ.get("CLAUDE_CONFIG_DIR", str(Path.home() / ".claude")))
    now = utc_now()
    tasks: dict[str, dict] = {}
    claude_prompts: dict[str, dict] = {}
    sources = []
    for tool, home in (("codex", codex_home), ("claude", claude_home)):
        base = home / ("sessions" if tool == "codex" else "projects")
        files, count, capped = _recent_files(base, limit, claude=tool == "claude")
        titles = _codex_titles(home) if tool == "codex" and limit else {}
        unreadable = 0
        for path in files:
            try:
                if tool == "codex":
                    task = parse_codex_session(path, titles=titles, include_output=include_output, now=now)
                else:
                    task, prompt = _claude_session(path, include_output=include_output)
                    if task:
                        claude_prompts[task["id"]] = prompt
                if task and (task["id"] not in tasks or tasks[task["id"]]["updated_at"] < task["updated_at"]):
                    tasks[task["id"]] = task
            except (OSError, ValueError, TypeError, RecursionError):
                unreadable += 1
        detail = f"发现 {count} 个日志；常规读取最近 {len(files)} 个，每个最多 256 KB。"
        if tool == "claude":
            detail += "普通日志不能确认当前运行状态；启用可选状态 hooks 后可补充。"
        else:
            detail += "仅依据明确生命周期事件；子代理不单列。"
            detail += "近期截断且状态未知的日志会额外回读，单轮每文件总读取上限为 1.25 MiB。"
        if capped:
            detail += "目录枚举达到上限，可能未覆盖全部会话。"
        if unreadable:
            detail += f" {unreadable} 个日志暂时不可读。"
        sources.append({"tool": tool, "available": base.is_dir(), "mode": "local_log", "detail": detail})
    hooks = _hook_tasks(limit, now) if limit else []
    for hook in hooks:
        hook_prompt = hook.pop("_prompt_id", "")
        existing = tasks.get(hook["id"])
        if existing:
            prompt = claude_prompts.get(hook["id"], {})
            prompt_time = prompt.get("prompt_at")
            same_prompt = bool(hook_prompt and hook_prompt == prompt.get("prompt_id"))
            # New prompts invalidate missed hooks; late writes within the same
            # prompt and unrelated metadata must not erase authoritative state.
            if not same_prompt and prompt_time and prompt_time > _timestamp(hook["updated_at"]):
                continue
            hook["title"] = existing["title"]
            hook["output"] = existing["output"]
            hook["final_result_id"] = existing.get("final_result_id", "")
            hook["final_result_at"] = existing.get("final_result_at", "")
            hook["project"] = existing["project"] or hook["project"]
        tasks[hook["id"]] = hook
    if hooks:
        sources[1]["mode"] = "local_log+hook"
        sources[1]["available"] = True
        sources[1]["detail"] += f" 已读到 {len(hooks)} 个会话的本地 hook 状态；超过 5 分钟无活动的运行/等待状态会转为未知。"
    return {"tasks": sorted(tasks.values(), key=lambda task: task["updated_at"], reverse=True)[:limit], "sources": sources}
