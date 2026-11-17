"""Bounded local continuation of an explicitly selected, inactive agent session.

Prompts travel only over child stdin. No shell, credential parsing, permission
grants, retries, forks, global settings changes, or tool-output forwarding.
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import stat
import subprocess
import threading
import time
from typing import Callable
import uuid

from . import collector
from .task_results import resolve_local_session, _unlinked
from .failure_reasons import (
    FAILURE_LABELS, failure_code_for_antigravity, failure_code_for_claude,
    failure_code_for_codex, has_codex_error_info,
)
from .model_capabilities import antigravity_catalog_rows, normalize_catalog, selected_settings
from .provider_controls import normalize_request, UnsupportedControl
from .session_controls import ModelSelection, reply_fingerprint

MAX_PROMPT_CHARS = 8_000
MAX_LINE_BYTES = 1024 * 1024
MAX_STREAM_BYTES = 32 * 1024 * 1024
MAX_COMMANDS = 512
RESUME_JOURNAL_SAMPLE_BYTES = 64 * 1024
RESUME_JOURNAL_EVENT_BYTES = 32 * 1024
INACTIVE = frozenset({"idle", "completed", "error"})
REASONS = {
    "unavailable": "这台电脑暂时无法续接这个会话",
    "busy": "任务正在执行或等待处理，请先在电脑上完成当前操作",
    "unknown": "当前任务状态尚未确认，请稍后刷新",
    "changed": "任务状态已变化，请刷新后重新发送",
    "permissions": "暂时无法完整保留这个会话的权限设置，请在电脑上继续",
    "approval": "这一步需要在电脑上确认权限或补充信息",
    "cli": "这台电脑尚未找到可用的代理程序",
    "invalid": "回复内容或任务标识无效",
    "closed": "电脑端服务正在关闭",
    "duplicate": "这条回复已经处理过，请刷新查看结果",
    "declined": "已拒绝本次操作，本轮已停止",
    "cancelled": "本次回复已停止，部分操作可能已经执行",
    "timeout": "等待执行结果超时，请在电脑上确认后再继续",
    "protocol": "未能确认这轮回复的执行结果，请在电脑上查看",
    "failed": "代理未能完成本轮回复，请在电脑上查看",
}
REASONS.update(FAILURE_LABELS)


class ExecutionIssue(Exception):
    def __init__(self, code: str, state: str = "blocked"):
        self.code, self.state = code, state
        super().__init__(code)


def _same_path(a, b) -> bool:
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


def _codex_approval_path(value, cwd):
    return (isinstance(value, str) and bool(value) and "\x00" not in value
            and Path(value).is_absolute() and ".." not in Path(value).parts and _same_path(value, cwd))


def _codex_local_approval_environment(environments, cwd):
    # Codex reserves "local"; a registered remote environment cannot use it.
    # Only the selection reported by this owned child on the verified resume
    # is authority. Neither a request field nor a matching path is enough alone.
    if not isinstance(environments, list) or len(environments) != 1:
        return False
    env = environments[0]
    if not isinstance(env, dict) or set(env) != {"environmentId", "cwd", "runtimeWorkspaceRoots"}:
        return False
    roots = env.get("runtimeWorkspaceRoots")
    return (env.get("environmentId") == "local" and _codex_approval_path(env.get("cwd"), cwd)
            and isinstance(roots, list) and len(roots) == 1 and _codex_approval_path(roots[0], cwd))


def _stamp(path: Path) -> tuple:
    return _stat_stamp(path.stat())


def _stat_stamp(value):
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns


def _codex_store_cli() -> str | None:
    """Resolve the console CLI in the current user's registered Codex Store package."""
    import ctypes
    from ctypes import wintypes as w

    try:
        # Use AppModel's current-user registration, including non-default install
        # volumes. Do not search WindowsApps, executable aliases, or user caches.
        api = ctypes.WinDLL("kernelbase.dll", winmode=0x800)  # LOAD_LIBRARY_SEARCH_SYSTEM32
        packages = api.GetPackagesByPackageFamily
        packages.argtypes = [w.LPCWSTR, ctypes.POINTER(w.UINT), ctypes.POINTER(w.LPWSTR),
                             ctypes.POINTER(w.UINT), w.LPWSTR]
        names = (w.LPWSTR * 32)()
        buffer = ctypes.create_unicode_buffer(8192)
        count, length = w.UINT(len(names)), w.UINT(len(buffer))
        if packages("OpenAI.Codex_2p2nqsd0c76g0", ctypes.byref(count), names,
                    ctypes.byref(length), buffer) != 0:
            return None
        main_packages = [name for name in names[:count.value] if name and re.fullmatch(
            r"OpenAI\.Codex_(?:[0-9]{1,5}\.){3}[0-9]{1,5}_(?:x64|arm64)__2p2nqsd0c76g0", name)]
        if len(main_packages) != 1:
            return None
        name = main_packages[0]
        origin = w.UINT()
        get_origin = api.GetStagedPackageOrigin
        get_origin.argtypes = [w.LPCWSTR, ctypes.POINTER(w.UINT)]
        if get_origin(name, ctypes.byref(origin)) != 0 or origin.value != 3:  # PackageOrigin_Store
            return None
        location = ctypes.create_unicode_buffer(32768)
        length = w.UINT(len(location))
        get_path = api.GetPackagePathByFullName
        get_path.argtypes = [w.LPCWSTR, ctypes.POINTER(w.UINT), w.LPWSTR]
        if get_path(name, ctypes.byref(length), location) != 0:
            return None
        root = Path(location.value)
        cli = root / "app" / "resources" / "codex.exe"
        if (not root.is_absolute() or root.drive.startswith("\\\\") or ".." in root.parts
                or root.name.casefold() != name.casefold()
                or not _unlinked(cli) or not cli.is_file()):
            return None
        # The package also contains the similarly named Electron GUI. Verify the
        # fixed resource really is a console PE for the registered architecture.
        with cli.open("rb") as stream:
            dos = stream.read(64)
            if len(dos) != 64 or dos[:2] != b"MZ":
                return None
            stream.seek(int.from_bytes(dos[60:64], "little"))
            pe = stream.read(94)
        machine = 0x8664 if "_x64__" in name else 0xAA64
        flags = int.from_bytes(pe[22:24], "little")
        if (len(pe) == 94 and pe[:4] == b"PE\0\0" and int.from_bytes(pe[4:6], "little") == machine
                and flags & 0x0002 and not flags & 0x2000  # executable image, not a DLL
                and int.from_bytes(pe[20:22], "little") >= 70
                and pe[24:26] == b"\x0b\x02" and pe[92:94] == b"\x03\x00"):
            return str(cli)
    except (AttributeError, OSError, ValueError):
        pass  # Missing/unsupported AppModel, inaccessible package, or a concurrent update.
    return None


def _windows_exe(path: Path) -> str | None:
    try:
        if path.is_file():
            target = path.resolve(strict=True)
            if target.suffix.casefold() == ".exe" and not _same_path(target.parent, Path.cwd()):
                return str(target)
    except (OSError, RuntimeError):
        pass
    return None


def find_executable(tool: str) -> str | None:
    """Resolve native executables, never execute a Windows shell/npm shim."""
    if tool not in {"codex", "claude", "antigravity"}:
        return None
    binary = "agy" if tool == "antigravity" else tool
    entries = [p for p in os.environ.get("PATH", "").split(os.pathsep)
               if p and Path(p).is_absolute() and not _same_path(p, Path.cwd())]
    search = os.pathsep.join(entries)
    if os.name != "nt":
        found = shutil.which(binary, path=search)
        if found and Path(found).is_file():
            return str(Path(found).resolve())
        if tool == "antigravity":
            local_bin = Path.home() / ".local" / "bin" / "agy"
            if _unlinked(local_bin) and local_bin.is_file() and os.access(local_bin, os.X_OK):
                return str(local_bin.resolve())
        return None
    # shutil.which("codex.exe") on Windows may append PATHEXT and return
    # codex.exe.cmd, or prepend the current directory. Only probe exact names.
    for directory in entries:
        found = _windows_exe(Path(directory) / (binary + ".exe"))
        if found:
            return found
    if tool == "antigravity":
        appdata = os.environ.get("LOCALAPPDATA", "")
        if appdata and Path(appdata).is_absolute() and _unlinked(Path(appdata)):
            return _windows_exe(Path(appdata) / "agy" / "bin" / "agy.exe")
        return None
    shim = shutil.which(tool, path=search)
    if shim and Path(shim).is_absolute() and not _same_path(Path(shim).parent, Path.cwd()):
        base = Path(shim).parent
        if tool == "claude":
            candidates = [base / "node_modules/@anthropic-ai/claude-code/bin/claude.exe"]
        else:
            candidates = [base / ("node_modules/@openai/" + package + "/vendor/" + arch + "/codex/codex.exe")
                          for package in ("codex", "codex-win32-x64", "codex-win32-arm64")
                          for arch in ("x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc")]
        for candidate in candidates:
            found = _windows_exe(candidate)
            if found:
                return found
    return _codex_store_cli() if tool == "codex" else None


def supported_tools() -> list[str]:
    return [tool for tool in ("codex", "claude", "antigravity") if find_executable(tool)]


def _permission_profile(context: dict, cwd: Path) -> dict:
    """Translate only understood local access entries; retain read-only carveouts."""
    profile = context.get("permission_profile")
    filesystem = {}
    if isinstance(profile, dict):
        if profile.get("type") != "managed" or profile.get("network") not in {"restricted", "enabled"}:
            raise ExecutionIssue("permissions")
        fs = profile.get("file_system")
        if not isinstance(fs, dict) or fs.get("type") != "restricted" or not isinstance(fs.get("entries"), list):
            raise ExecutionIssue("permissions")
        entries = fs["entries"]
        actual = context.get("file_system_sandbox_policy")
        if actual is not None and (not isinstance(actual, dict) or actual.get("kind") != "restricted"
                                   or actual.get("entries") != entries):
            raise ExecutionIssue("permissions")
        if not entries or len(entries) > 256:
            raise ExecutionIssue("permissions")
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) - {"path", "access", "missing_path_behavior"}:
                raise ExecutionIssue("permissions")
            access, path = entry.get("access"), entry.get("path")
            if access not in {"read", "write", "deny"} or not isinstance(path, dict):
                raise ExecutionIssue("permissions")
            if entry.get("missing_path_behavior") not in {None, "skip"}:
                raise ExecutionIssue("permissions")
            if path.get("type") == "path" and set(path) == {"type", "path"}:
                key = path["path"]
                if not isinstance(key, str) or not Path(key).is_absolute() or any(c in key for c in "*?\x00\n\r"):
                    raise ExecutionIssue("permissions")
                if key.startswith(("\\\\", "//")):
                    raise ExecutionIssue("permissions")
            elif path.get("type") == "special" and set(path) == {"type", "value"}:
                value = path["value"]
                if not isinstance(value, dict) or set(value) != {"kind"} or value["kind"] not in {"root", "slash_tmp", "tmpdir"}:
                    raise ExecutionIssue("permissions")
                key = ":" + value["kind"]
                if key == ":root" and access != "read":
                    raise ExecutionIssue("permissions")
            else:
                raise ExecutionIssue("permissions")
            previous = filesystem.get(key)
            # Conflicting duplicates are reduced, never broadened.
            rank = {"deny": 0, "read": 1, "write": 2}
            filesystem[key] = min((previous, access), key=lambda x: rank[x]) if previous else access
    else:
        policy = context.get("sandbox_policy")
        if not isinstance(policy, dict) or policy.get("type") not in {"read-only", "workspace-write"}:
            raise ExecutionIssue("permissions")
        if set(policy) - {"type", "writable_roots", "network_access", "exclude_tmpdir_env_var", "exclude_slash_tmp"}:
            raise ExecutionIssue("permissions")
        filesystem[":root"] = "read"
        if policy["type"] == "workspace-write":
            roots = [str(cwd), *policy.get("writable_roots", [])]
            for root in roots:
                if not isinstance(root, str) or not Path(root).is_absolute() or any(c in root for c in "*?\x00\r\n"):
                    raise ExecutionIssue("permissions")
                filesystem[root] = "write"
                for protected in (".git", ".agents", ".codex"):
                    filesystem[str(Path(root) / protected)] = "read"
            if not policy.get("exclude_tmpdir_env_var", False):
                filesystem[":tmpdir"] = "write"
            if not policy.get("exclude_slash_tmp", False):
                filesystem[":slash_tmp"] = "write"
    if not filesystem:
        raise ExecutionIssue("permissions")
    return {"filesystem": filesystem, "network": {"enabled": False}}


@dataclass(frozen=True)
class _Target:
    source_id: str
    tool: str
    session_id: str
    path: Path
    cwd: Path
    stamp: tuple
    executable: str
    profile: dict | None = None
    model: str | None = None
    effort: str | None = None
    claude_mode: str | None = None
    claude_original_mode: str | None = None
    directory_identity: tuple = ()
    status: str = ""


def _codex_resume_checkpoint(target):
    """Remember the existing end of an already verified, plain Codex journal."""
    if target.path.suffix != ".jsonl" or not _unlinked(target.path):
        return None
    size = target.stamp[2]
    offset = max(0, size - RESUME_JOURNAL_SAMPLE_BYTES)
    if size <= 0:
        return None
    try:
        with target.path.open("rb") as handle:
            info = os.fstat(handle.fileno())
            if not stat.S_ISREG(info.st_mode) or _stat_stamp(info) != target.stamp:
                return None
            handle.seek(offset)
            tail = handle.read(size - offset)
            if len(tail) != size - offset or not tail.endswith(b"\n") or _stamp(target.path) != target.stamp:
                return None
        return offset, hashlib.sha256(tail).digest()
    except (OSError, ValueError):
        return None


def _no_duplicate_json_fields(pairs):
    value = dict(pairs)
    if len(value) != len(pairs):
        raise ValueError("duplicate journal fields")
    return value


def _codex_resume_metadata(target, fresh, checkpoint, resumed):
    """Accept one event written by the particular owned app-server resume."""
    size, new_size = target.stamp[2], fresh.stamp[2]
    if (checkpoint is None or target.stamp[:2] != fresh.stamp[:2]
            or not 0 < new_size - size <= RESUME_JOURNAL_EVENT_BYTES):
        return False
    offset, digest = checkpoint
    try:
        if not _unlinked(target.path):
            return False
        with target.path.open("rb") as handle:
            info = os.fstat(handle.fileno())
            if not stat.S_ISREG(info.st_mode) or _stat_stamp(info) != fresh.stamp:
                return False
            handle.seek(offset)
            previous = handle.read(size - offset)
            data = handle.read(new_size - size + 1)
            if (_stat_stamp(os.fstat(handle.fileno())) != fresh.stamp or _stamp(target.path) != fresh.stamp
                    or len(previous) != size - offset or hashlib.sha256(previous).digest() != digest
                    or len(data) != new_size - size or not data.endswith(b"\n") or data.count(b"\n") != 1):
                return False
        event = json.loads(data, object_pairs_hook=_no_duplicate_json_fields)
        if (not isinstance(event, dict) or set(event) != {"type", "timestamp", "ordinal", "payload"}
                or event["type"] != "event_msg" or type(event["ordinal"]) is not int or event["ordinal"] < 0
                or not isinstance(event["timestamp"], str)
                or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z", event["timestamp"])):
            return False
        payload = event["payload"]
        if (not isinstance(payload, dict) or set(payload) != {"type", "thread_id", "thread_settings"}
                or payload["type"] != "thread_settings_applied" or payload["thread_id"] != target.session_id):
            return False
        settings = payload["thread_settings"]
        bindings = {"model": "model", "model_provider_id": "modelProvider", "service_tier": "serviceTier",
                    "approval_policy": "approvalPolicy", "approvals_reviewer": "approvalsReviewer",
                    "reasoning_effort": "reasoningEffort", "collaboration_mode": "collaborationMode",
                    "disabled_plugin_ids": "disabledPluginIds"}
        if (not isinstance(settings, dict) or set(settings) != set(bindings) | {
                "cwd", "runtime_workspace_roots", "permission_profile", "requested_approvals_reviewer",
                "active_permission_profile"}
                or any(settings[k] != resumed.get(v) for k, v in bindings.items())
                or settings["requested_approvals_reviewer"] != "user" or settings["approval_policy"] != "untrusted"
                or settings["approvals_reviewer"] != "user" or not isinstance(settings["cwd"], str)
                or not _same_path(settings["cwd"], target.cwd)):
            return False
        # The app-server response can serialize `extends: null`; the journal
        # omits it. This child uses a fresh profile with no inherited settings.
        active = resumed.get("activePermissionProfile")
        marker = active.get("id") if isinstance(active, dict) else None
        if (not isinstance(marker, str) or not re.fullmatch(r"monitor_reply_[0-9a-f]{24}", marker)
                or set(active) - {"id", "extends"} or active.get("extends") is not None
                or settings["active_permission_profile"] != {"id": marker}):
            return False
        roots, echo_roots = settings["runtime_workspace_roots"], resumed.get("runtimeWorkspaceRoots")
        if (not isinstance(roots, list) or not isinstance(echo_roots, list) or len(roots) != len(echo_roots)
                or not all(isinstance(a, str) and isinstance(b, str) and _same_path(a, b)
                           for a, b in zip(roots, echo_roots))):
            return False
        profile = settings["permission_profile"]
        return (isinstance(profile, dict) and set(profile) == {"type", "file_system", "network"}
                and profile["network"] == "restricted"
                and _permission_profile({"permission_profile": profile}, target.cwd) == target.profile)
    except (OSError, ValueError, TypeError, RecursionError, ExecutionIssue):
        return False


def _antigravity_base_dir(path: Path) -> Path:
    return path.parents[1] if len(path.parents) >= 2 and path.parent.name == "conversations" else path.parent


def _antigravity_locked(base_dir: Path, session_id: str, conv_db: Path) -> bool:
    """Detect active Desktop/CLI writers via SQLite sidecars or presence lock."""
    for suffix in ("-wal", "-shm", "-journal"):
        if conv_db.with_name(conv_db.name + suffix).exists():
            return True
    lock_path = base_dir / "presence" / f"{session_id}.lock"
    if not lock_path.exists():
        return False
    if not _unlinked(lock_path) or not lock_path.is_file():
        return True
    try:
        with lock_path.open("r+b") as handle:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        return False
    except OSError:
        return True


def _resolve_target(source_id, resolver):
    if not isinstance(source_id, str) or len(source_id) > 200:
        raise ExecutionIssue("invalid")
    resolved = resolver(source_id)
    if resolved is None or resolved.source_id != source_id or resolved.tool not in {"codex", "claude", "antigravity"}:
        raise ExecutionIssue("unavailable")
    try:
        if str(uuid.UUID(resolved.session_id)) != resolved.session_id.lower() or source_id != resolved.tool + ":" + resolved.session_id:
            raise ExecutionIssue("invalid")
        if not _unlinked(resolved.path) or not _unlinked(resolved.cwd) or not resolved.cwd.is_dir():
            raise ExecutionIssue("unavailable")
        return resolved
    except (OSError, ValueError, TypeError) as exc:
        raise ExecutionIssue("unavailable") from exc


def _inspect(source_id, *, resolver=resolve_local_session, status_reader=None, executable_finder=find_executable) -> _Target:
    resolved = _resolve_target(source_id, resolver)
    try:
        before = _stamp(resolved.path)
        if resolved.tool == "antigravity":
            base_dir = _antigravity_base_dir(resolved.path)
            if _antigravity_locked(base_dir, resolved.session_id, resolved.path):
                raise ExecutionIssue("busy")
        if status_reader:
            task = status_reader(resolved)
        elif resolved.tool == "codex":
            task = collector.parse_codex_session(resolved.path, include_output=False)
        elif resolved.tool == "antigravity":
            from .antigravity import scan_antigravity
            scanned = scan_antigravity(base=_antigravity_base_dir(resolved.path), limit=100)
            task = next((t for t in (scanned or []) if t.get("id") == source_id), None)
        else:
            snapshot = collector.collect_snapshot(include_output=False)
            task = next((t for t in snapshot["tasks"] if t["id"] == source_id and t.get("status_source") == "hook"), None)
        if not task or task.get("id") != source_id or task.get("tool") != resolved.tool:
            raise ExecutionIssue("unknown")
        if task.get("status") not in INACTIVE:
            raise ExecutionIssue("busy" if task.get("status") in {"running", "waiting"} else "unknown")
        profile = model = effort = claude_mode = original_mode = None
        if resolved.tool == "codex":
            _, rows, _ = collector._read_window(resolved.path, max_bytes=1024 * 1024)
            contexts = [r["payload"] for r in rows if r.get("type") == "turn_context" and isinstance(r.get("payload"), dict)]
            if not contexts or not isinstance(contexts[-1].get("cwd"), str) or not _same_path(contexts[-1]["cwd"], resolved.cwd):
                raise ExecutionIssue("permissions")
            profile = _permission_profile(contexts[-1], resolved.cwd)
            model = contexts[-1].get("model")
            if model is not None and (not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9._:/-]{1,120}", model)):
                raise ExecutionIssue("permissions")
            effort = contexts[-1].get("effort")
            if effort not in {None, "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"}:
                raise ExecutionIssue("permissions")
        elif resolved.tool == "antigravity":
            model, effort = _catalog_observation(resolved)
            if _antigravity_locked(_antigravity_base_dir(resolved.path), resolved.session_id, resolved.path):
                raise ExecutionIssue("busy")
        else:
            _, rows, _ = collector._read_window(resolved.path, max_bytes=1024 * 1024)
            modes = [row["permissionMode"] for row in rows if "permissionMode" in row]
            if not modes or modes[-1] not in {"plan", "manual", "default", "dontAsk", "auto", "acceptEdits", "bypassPermissions"}:
                raise ExecutionIssue("permissions")
            claude_mode = "plan" if modes[-1] == "plan" else "dontAsk"
            original_mode = modes[-1]
            observed_models = [r.get("message", {}).get("model") for r in rows
                               if isinstance(r.get("message"), dict)]
            model = next((m for m in reversed(observed_models) if isinstance(m, str) and
                          re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}", m)), None)
        if before != _stamp(resolved.path):
            raise ExecutionIssue("changed")
        executable = executable_finder(resolved.tool)
        if not executable:
            raise ExecutionIssue("cli")
        directory = resolved.cwd.stat()
        return _Target(source_id, resolved.tool, resolved.session_id, resolved.path, resolved.cwd, before, executable,
                       profile, model, effort, claude_mode, original_mode, (directory.st_dev, directory.st_ino), task["status"])
    except (OSError, ValueError, TypeError, RecursionError) as exc:
        raise ExecutionIssue("unavailable") from exc


def inspect_reply_target(source_id: str) -> dict:
    try:
        target = _inspect(source_id)
        return {"available": True, "reason": "", "tool": target.tool, "session_id": target.session_id, "cwd": str(target.cwd)}
    except ExecutionIssue as exc:
        return {"available": False, "reason": REASONS[exc.code]}


def _child_environment() -> dict:
    # Do not forward parent agent IDs, API keys, bearer tokens or nested-Claude flags.
    # Preserve the user's inherited proxy routing exactly, including lowercase
    # precedence and NO_PROXY exclusions; a continuation must not switch exits.
    allowed = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
               "HOME", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA",
               "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "CODEX_HOME", "CLAUDE_CONFIG_DIR",
               "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"}
    result = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    result["PYTHONUNBUFFERED"] = "1"
    return result


def _toml(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=True)
    if isinstance(value, dict):
        return "{ " + ", ".join(_toml(k) + " = " + _toml(v) for k, v in value.items()) + " }"
    raise ValueError("Unsupported profile value")


def _command(target: _Target, *, interactive=False, settings=None) -> tuple[list[str], str | None]:
    if target.tool == "antigravity":
        base_dir = _antigravity_base_dir(target.path)
        return [target.executable, "acp", "--gemini-dir", str(base_dir.parent),
                "--app-data-dir", base_dir.name, "--cli_dir_name", "antigravity-cli", "--disable-telemetry"], None
    if target.tool == "claude":
        mode = target.claude_original_mode if interactive else target.claude_mode
        if mode not in ({"default", "plan", "dontAsk", "acceptEdits"} if interactive else {"plan", "dontAsk"}):
            raise ExecutionIssue("permissions")
        argv = [target.executable, "--print", "--resume", target.session_id, "--output-format", "stream-json",
                "--verbose", "--permission-mode", mode]
        if mode == "plan":
            argv += ["--disallowedTools", "ExitPlanMode"]
        if interactive:
            argv += ["--input-format", "stream-json", "--permission-prompt-tool", "stdio"]
        if settings:
            argv += ["--model", settings["model"]]
            if settings.get("effort"): argv += ["--effort", settings["effort"]]
        return argv, None
    # Unique to the owned child. Another process using the same effective
    # permissions cannot produce matching resume metadata for this attempt.
    name = "monitor_reply_" + uuid.uuid4().hex[:24]
    return [target.executable, "-c", "permissions." + name + "=" + _toml(target.profile),
            "-c", "default_permissions=" + _toml(name), "app-server", "--stdio"], name


class _WindowsJob:
    """A kill-on-close job is attached before the suspended child can execute."""
    def __init__(self):
        import ctypes
        from ctypes import wintypes as w
        self.c = ctypes
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_longlong), ("job_time", ctypes.c_longlong),
                        ("flags", w.DWORD), ("min_ws", ctypes.c_size_t), ("max_ws", ctypes.c_size_t),
                        ("active_limit", w.DWORD), ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]
        class Io(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]
        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", Io), ("process_memory", ctypes.c_size_t),
                        ("job_memory", ctypes.c_size_t), ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]
        self.k.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
        self.k.CreateJobObjectW.restype = w.HANDLE
        self.k.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        self.k.SetInformationJobObject.restype = w.BOOL
        self.k.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
        self.k.AssignProcessToJobObject.restype = w.BOOL
        self.k.CloseHandle.argtypes = [w.HANDLE]
        self.handle = self.k.CreateJobObjectW(None, None)
        if not self.handle:
            raise OSError("Cannot create child ownership job")
        limits = Extended(); limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.k.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close(); raise OSError("Cannot limit child ownership job")

    def attach_and_resume(self, proc):
        from ctypes import wintypes as w
        if not self.k.AssignProcessToJobObject(self.handle, w.HANDLE(int(proc._handle))):
            raise OSError("Cannot own child process tree")
        # Popen closes the primary thread handle. Resume the owned, suspended process
        # only after assignment; this avoids a child escaping the job before attach.
        ntdll = self.c.WinDLL("ntdll")
        ntdll.NtResumeProcess.argtypes = [w.HANDLE]
        ntdll.NtResumeProcess.restype = self.c.c_long
        if ntdll.NtResumeProcess(w.HANDLE(int(proc._handle))) != 0:
            raise OSError("Cannot resume owned child")

    def close(self):
        if self.handle:
            self.k.CloseHandle(self.handle)
            self.handle = None


class _Process:
    def __init__(self, argv, cwd, *, popen=subprocess.Popen):
        self.job = _WindowsJob() if os.name == "nt" else None
        self.proc = None
        self.closed = False
        try:
            options = {"cwd": str(cwd), "env": _child_environment(), "shell": False,
                       "stdin": subprocess.PIPE, "stdout": subprocess.PIPE, "stderr": subprocess.PIPE, "bufsize": 0}
            if os.name == "nt":
                options["creationflags"] = subprocess.CREATE_NO_WINDOW | 0x4  # CREATE_SUSPENDED
            else:
                options["start_new_session"] = True
            self.proc = popen(argv, **options)
            if self.job:
                self.job.attach_and_resume(self.proc)
        except BaseException:
            self.close()
            raise

    def close(self, *, graceful=False):
        if self.closed:
            return
        self.closed = True
        proc = self.proc
        if proc is None:
            if self.job: self.job.close()
            return
        if graceful:
            try:
                proc.stdin.close()
                proc.wait(timeout=2)
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass
        if self.job:
            self.job.close()
        else:
            # A group can outlive its leader (for example an MCP subprocess).
            try: os.killpg(proc.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                if proc.poll() is None: proc.terminate()
        if proc.poll() is None:
            try: proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                if not self.job:
                    try: os.killpg(proc.pid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError, OSError): pass
                proc.kill(); proc.wait(timeout=2)
        if not self.job:
            try: os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError): pass
        for stream in (proc.stdin, proc.stdout, proc.stderr):
            try: stream.close()
            except (OSError, ValueError): pass


class _Transport:
    def __init__(self, process, cancelled, timeout):
        self.process, self.cancelled = process, cancelled
        self.deadline = time.monotonic() + timeout
        self.events = queue.Queue(maxsize=64)
        self.done = threading.Event()
        self.over_limit = threading.Event()
        self.count = 0
        self.count_lock = threading.Lock()
        for stream, stdout in ((process.proc.stdout, True), (process.proc.stderr, False)):
            threading.Thread(target=self._read, args=(stream, stdout), daemon=True).start()

    def _read(self, stream, stdout):
        try:
            while not self.done.is_set():
                line = stream.readline(MAX_LINE_BYTES + 1)
                if not line:
                    if stdout: self._put(None)
                    return
                with self.count_lock:
                    self.count += len(line)
                    excessive = self.count > MAX_STREAM_BYTES or len(line) > MAX_LINE_BYTES
                if excessive:
                    self.over_limit.set(); return
                if stdout: self._put(line)
        except (OSError, ValueError):
            if stdout: self._put(None)

    def _put(self, value):
        while not self.done.is_set():
            try: self.events.put(value, timeout=.1); return
            except queue.Full: pass

    def check(self):
        if self.cancelled.is_set(): raise ExecutionIssue("cancelled", "uncertain")
        if time.monotonic() >= self.deadline: raise ExecutionIssue("timeout", "uncertain")
        if self.over_limit.is_set(): raise ExecutionIssue("protocol", "uncertain")

    def send_bytes(self, data, *, close=False):
        result = queue.Queue(maxsize=1)
        def write():
            try:
                stream = self.process.proc.stdin
                remaining = memoryview(data)
                while remaining:
                    count = stream.write(remaining)
                    if not count: raise BrokenPipeError()
                    remaining = remaining[count:]
                stream.flush()
                if close: stream.close()
                result.put(None)
            except (OSError, ValueError) as exc: result.put(exc)
        threading.Thread(target=write, daemon=True).start()
        while True:
            self.check()
            try:
                if result.get(timeout=.1) is not None: raise ExecutionIssue("protocol", "uncertain")
                return
            except queue.Empty: pass

    def send(self, message):
        self.send_bytes((json.dumps(message, separators=(",", ":"), ensure_ascii=False) + "\n").encode())

    def next(self, *, wait=True):
        while True:
            self.check()
            try: line = self.events.get(timeout=.1)
            except queue.Empty:
                if not wait: return None
                continue
            if line is None: raise ExecutionIssue("protocol", "uncertain")
            try: value = json.loads(line)
            except (ValueError, UnicodeError, RecursionError): raise ExecutionIssue("protocol", "uncertain")
            if not isinstance(value, dict): raise ExecutionIssue("protocol", "uncertain")
            return value


def _reject_request(transport, message):
    method = message.get("method", "")
    if "id" not in message or not isinstance(method, str) or not method:
        return False
    response = {"id": message["id"]}
    if "jsonrpc" in message:
        response["jsonrpc"] = "2.0"
    if method in {"item/commandExecution/requestApproval", "item/fileChange/requestApproval"}:
        response["result"] = {"decision": "cancel"}
    elif method == "item/permissions/requestApproval":
        response["result"] = {"permissions": {}, "scope": "turn"}
    elif method == "mcpServer/elicitation/request":
        response["result"] = {"action": "cancel", "content": None}
    elif method == "session/request_permission":
        response["result"] = {"outcome": {"outcome": "cancelled"}}
    elif method == "elicitation/create":
        response["result"] = {"action": "cancel"}
    else:
        response["error"] = {"code": -32601, "message": "Interactive requests require the desktop client"}
    transport.send(response)
    raise ExecutionIssue("approval")


def _rpc_response(transport, wanted, pending=None, *, error_mapper=None):
    while True:
        message = transport.next()
        if pending is not None and "id" in message and isinstance(message.get("method"), str):
            if len(pending) >= 64: raise ExecutionIssue("protocol", "uncertain")
            pending.append(message)
            continue
        _reject_request(transport, message)
        if message.get("id") == wanted:
            if "error" in message:
                code = error_mapper(message.get("error")) if error_mapper else ""
                raise ExecutionIssue(code or "failed", "failed")
            if not isinstance(message.get("result"), dict): raise ExecutionIssue("protocol", "uncertain")
            return message["result"]
        if pending is not None and isinstance(message.get("method"), str):
            if len(pending) >= 64: raise ExecutionIssue("protocol", "uncertain")
            pending.append(message)


@dataclass
class _Run:
    command_id: str
    source_id: str
    cancelled: threading.Event = field(default_factory=threading.Event)
    thread: threading.Thread | None = None
    state: str = "running"
    reason: str = ""
    error_code: str = ""
    fingerprint: str = ""
    selection: dict | None = None


class LocalReplyExecutor:
    def __init__(self, emit: Callable[[dict], None], *, timeout_seconds=1800, resolver=resolve_local_session,
                 status_reader=None, executable_finder=find_executable, process_factory=_Process,
                 interaction_handler=None, catalog_provider=None):
        self.emit = emit
        self.timeout = max(.05, min(float(timeout_seconds), 3600))
        self.inspect_options = {"resolver": resolver, "status_reader": status_reader, "executable_finder": executable_finder}
        self.process_factory = process_factory
        self.interaction_handler, self.catalog_provider = interaction_handler, catalog_provider
        self.lock = threading.Lock()
        self.runs = {}
        self.closed = False

    def _result(self, run):
        return {"command_id": run.command_id, "state": run.state, "reason": run.reason, "error_code": run.error_code}

    def _emit(self, value):
        try: self.emit(dict(value))
        except Exception: pass  # A UI/store callback cannot grant permissions or restart work.

    def start(self, command_id: str, source_id: str, prompt: str, *, selection=None) -> dict:
        if not isinstance(command_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", command_id):
            return {"command_id": "", "state": "blocked", "reason": REASONS["invalid"]}
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_CHARS or "\x00" in prompt:
            return {"command_id": command_id, "state": "blocked", "reason": REASONS["invalid"]}
        try:
            chosen = ModelSelection.model_validate(selection) if selection is not None else None
            fingerprint = hashlib.sha256((str(source_id) + "\0" + reply_fingerprint(prompt, chosen)).encode()).hexdigest()
        except (ValueError, TypeError):
            return {"command_id": command_id, "state": "blocked", "reason": REASONS["invalid"], "error_code": "invalid"}
        with self.lock:
            if command_id in self.runs:
                if self.runs[command_id].fingerprint != fingerprint:
                    return {"command_id": command_id, "state": "blocked", "reason": REASONS["duplicate"], "error_code": "duplicate"}
                return self._result(self.runs[command_id])
            if self.closed or len(self.runs) >= MAX_COMMANDS:
                return {"command_id": command_id, "state": "blocked", "reason": REASONS["closed" if self.closed else "duplicate"]}
            if any(r.source_id == source_id and r.state == "running" for r in self.runs.values()):
                return {"command_id": command_id, "state": "blocked", "reason": REASONS["busy"]}
            run = _Run(command_id, source_id, fingerprint=fingerprint,
                       selection=chosen.model_dump() if chosen else None)
            self.runs[command_id] = run
        try: target = _inspect(source_id, **self.inspect_options)
        except ExecutionIssue as exc:
            with self.lock: run.state, run.reason, run.error_code = exc.state, REASONS[exc.code], exc.code
            return self._result(run)
        with self.lock:
            if self.closed or run.cancelled.is_set():
                run.state, run.reason, run.error_code = "blocked", REASONS["closed"], "closed"
                return self._result(run)
            run.thread = threading.Thread(target=self._execute, args=(run, target, prompt), daemon=True,
                                          name="monitor-reply-" + command_id[:12])
            run.thread.start()
            return self._result(run)

    def _fresh(self, target):
        fresh = _inspect(target.source_id, **self.inspect_options)
        if fresh != target: raise ExecutionIssue("changed")

    def _fresh_after_codex_resume(self, target, checkpoint, resumed):
        if checkpoint is not None:
            try:
                changed = _stamp(target.path) != target.stamp
            except (OSError, ValueError):
                raise ExecutionIssue("changed") from None
            if changed:
                fresh = _inspect(target.source_id, **self.inspect_options)
                if (replace(fresh, stamp=target.stamp) != target
                        or not _codex_resume_metadata(target, fresh, checkpoint, resumed)):
                    raise ExecutionIssue("changed")
                target = fresh
        self._fresh(target)
        return target

    def _execute(self, run, target, prompt):
        process = transport = None
        state, reason, code = "uncertain", REASONS["protocol"], "protocol"
        try:
            self._fresh(target)
            if run.cancelled.is_set(): raise ExecutionIssue("cancelled", "uncertain")
            settings = None
            if run.selection:
                catalog = (self.catalog_provider(target.source_id) if self.catalog_provider else
                           discover_reply_catalog(target.source_id, process_factory=self.process_factory, **self.inspect_options))
                try: settings = selected_settings(run.selection, catalog, provider=target.tool, session_id=target.session_id)
                except (ValueError, TypeError, KeyError): raise ExecutionIssue("changed")
                self._fresh(target)
                if run.cancelled.is_set(): raise ExecutionIssue("cancelled", "uncertain")
            interactive = self.interaction_handler is not None
            argv, profile_name = _command(target, interactive=interactive, settings=settings)
            process = self.process_factory(argv, target.cwd)
            transport = _Transport(process, run.cancelled, self.timeout)
            self._emit(self._result(run))
            if target.tool == "codex": self._codex(transport, target, profile_name, prompt, run, settings)
            elif target.tool == "antigravity": self._antigravity(transport, target, prompt, run, settings)
            elif interactive: self._claude_controlled(transport, target, prompt, run, settings)
            else: self._claude(transport, target, prompt)
            state, reason, code = "succeeded", "", ""
        except ExecutionIssue as exc:
            state, reason, code = exc.state, REASONS[exc.code], exc.code
        except Exception:
            state, code = ("uncertain", "protocol") if process else ("blocked", "unavailable")
            reason = REASONS[code]
        finally:
            if transport: transport.done.set()
            if process:
                try: process.close(graceful=state == "succeeded")
                except (OSError, ValueError, subprocess.TimeoutExpired): state, reason, code = "uncertain", REASONS["protocol"], "protocol"
            with self.lock: run.state, run.reason, run.error_code = state, reason, code
            self._emit(self._result(run))

    def _codex(self, transport, target, profile, prompt, run, settings=None):
        transport.send({"id": 1, "method": "initialize", "params": {
            "clientInfo": {"name": "agent_monitor_local_reply", "version": "1"}, "capabilities": {"experimentalApi": True}}})
        _rpc_response(transport, 1)
        transport.send({"method": "initialized", "params": {}})
        collaboration_modes = None
        if settings:
            try:
                rows = _codex_model_rows(transport)
                collaboration_modes = _codex_collaboration_modes(transport)
                current = normalize_catalog("codex", rows, collaboration_modes=collaboration_modes)
                settings = selected_settings(run.selection, current, provider="codex", session_id=target.session_id)
            except (ValueError, KeyError, TypeError): raise ExecutionIssue("changed")
        params = {"threadId": target.session_id, "cwd": str(target.cwd), "permissions": profile,
                  "approvalPolicy": "untrusted", "approvalsReviewer": "user", "excludeTurns": True}
        if target.model: params["model"] = target.model
        checkpoint = _codex_resume_checkpoint(target)
        transport.send({"id": 2, "method": "thread/resume", "params": params})
        resumed = _rpc_response(transport, 2)
        thread = resumed.get("thread", {})
        if thread.get("id") != target.session_id or not isinstance(resumed.get("cwd"), str) or not _same_path(resumed["cwd"], target.cwd):
            raise ExecutionIssue("protocol", "uncertain")
        if thread.get("status", {}).get("type") != "idle": raise ExecutionIssue("busy")
        if resumed.get("activePermissionProfile", {}).get("id") != profile or resumed.get("approvalPolicy") != "untrusted" or resumed.get("approvalsReviewer") != "user":
            raise ExecutionIssue("permissions")
        if resumed.get("sandbox", {}).get("networkAccess", True) is not False:
            raise ExecutionIssue("permissions")
        target = self._fresh_after_codex_resume(target, checkpoint, resumed)
        environments = thread.get("environments")
        local_approval_environment = _codex_local_approval_environment(environments, target.cwd)
        params = {"threadId": target.session_id, "input": [{"type": "text", "text": prompt}],
                  "cwd": str(target.cwd), "permissions": profile, "approvalPolicy": "untrusted", "approvalsReviewer": "user",
                  "clientUserMessageId": run.command_id}
        # next_message is the application time, not a promised expiry. The
        # provider may retain these settings for subsequent turns in this thread.
        if settings:
            params["model"] = settings["model"]
            if "effort" in settings: params["effort"] = settings["effort"]
            if "service_tier" in settings: params["serviceTierForTurn"] = settings["service_tier"]
        elif target.effort: params["effort"] = target.effort
        mode = settings.get("collaboration_mode") if settings else None
        active_mode = resumed.get("collaborationMode")
        if mode is None and isinstance(active_mode, dict) and active_mode.get("mode") == "plan":
            # Some provider versions restore the prior Plan. Limit Monitor's
            # opt-in to one turn; ordinary replies retain the verified profile.
            if collaboration_modes is None:
                collaboration_modes = _codex_collaboration_modes(transport)
            if "plan" not in collaboration_modes:
                raise ExecutionIssue("changed")
            mode = "default"
        if mode is not None:
            active_settings = active_mode.get("settings") if isinstance(active_mode, dict) else None
            active_settings = active_settings if isinstance(active_settings, dict) else {}
            model = params.get("model") or resumed.get("model") or target.model or active_settings.get("model")
            effort = params.get("effort")
            if not settings:
                effort = effort or resumed.get("reasoningEffort") or active_settings.get("reasoning_effort")
            if not isinstance(model, str) or len(model) > 120 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*(?:\[[A-Za-z0-9._-]+\])?", model):
                raise ExecutionIssue("changed")
            if effort is not None and (not isinstance(effort, str) or not re.fullmatch(r"[a-z0-9_]{1,32}", effort)):
                raise ExecutionIssue("changed")
            # Codex gives collaborationMode precedence over top-level model
            # and effort. Repeat the validated choice here without custom instructions.
            params["collaborationMode"] = {"mode": mode, "settings": {
                "model": model, "reasoning_effort": effort, "developer_instructions": None}}
            self._fresh(target)
        transport.send({"id": 3, "method": "turn/start", "params": params})
        pending = []
        started = _rpc_response(transport, 3, pending)
        turn_id = started.get("turn", {}).get("id")
        if not isinstance(turn_id, str) or not turn_id: raise ExecutionIssue("protocol", "uncertain")
        seen, previews = {}, {}
        failure_code = ""
        stopped_by_phone = False
        while True:
            message = pending.pop(0) if pending else transport.next()
            if "id" in message and isinstance(message.get("method"), str):
                if not self.interaction_handler: _reject_request(transport, message)
                control_params = message.get("params")
                if not isinstance(control_params, dict): _reject_request(transport, message)
                local_command = False
                if message["method"] == "item/commandExecution/requestApproval" and environments is not None:
                    local_command = (local_approval_environment and control_params.get("environmentId") == "local"
                                     and _codex_approval_path(control_params.get("cwd"), target.cwd))
                    if not local_command: _reject_request(transport, message)
                item_id = control_params.get("itemId")
                preview = previews.get(item_id) if isinstance(item_id, str) else None
                stopped_by_phone = self._interactive(transport, target, run, turn_id, message, pending, seen,
                    file_preview=preview, codex_local_command=local_command) or stopped_by_phone
                continue
            mp = message.get("params", {})
            if message.get("method") == "item/started" and mp.get("threadId") == target.session_id and mp.get("turnId") == turn_id:
                item = mp.get("item", {})
                if item.get("type") == "fileChange" and isinstance(item.get("id"), str):
                    changes = item.get("changes")
                    if isinstance(changes, list) and 0 < len(changes) <= 64:
                        preview = json.dumps(changes, ensure_ascii=False)
                        if len(preview) <= 8000: previews[item["id"]] = preview
            if message.get("method") == "error" and mp.get("threadId") == target.session_id and mp.get("turnId") == turn_id:
                failure_code = failure_code_for_codex(mp.get("error")) or ""
            if message.get("method") != "turn/completed": continue
            params = message.get("params", {})
            turn = params.get("turn", {})
            if params.get("threadId") != target.session_id or turn.get("id") != turn_id:
                raise ExecutionIssue("protocol", "uncertain")
            if turn.get("status") == "completed" and not turn.get("error"): return
            final_error = turn.get("error")
            if stopped_by_phone and turn.get("status") == "interrupted" and not final_error:
                raise ExecutionIssue("declined", "failed")
            final_code = failure_code_for_codex(final_error)
            if has_codex_error_info(final_error): failure_code = final_code
            raise ExecutionIssue(final_code or failure_code or "failed",
                                 "failed" if turn.get("status") == "failed" else "uncertain")

    def _claude(self, transport, target, prompt):
        self._fresh(target)
        transport.send_bytes(prompt.encode("utf-8"), close=True)
        initialized = False
        while True:
            message = transport.next()
            session = message.get("session_id")
            if session is not None and session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
            if message.get("type") == "system" and message.get("subtype") == "init":
                if session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
                if message.get("permissionMode") != target.claude_mode: raise ExecutionIssue("permissions")
                initialized = True
            if message.get("type") != "result": continue
            if not initialized or session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
            if message.get("permission_denials"): raise ExecutionIssue("approval")
            if message.get("subtype") == "success" and message.get("is_error") is False: return
            raise ExecutionIssue(failure_code_for_claude(message.get("error_type")) or "failed", "failed")

    def _directory_current(self, target):
        if not _unlinked(target.cwd) or not target.cwd.is_dir(): raise ExecutionIssue("changed")
        value = target.cwd.stat()
        if (value.st_dev, value.st_ino) != target.directory_identity: raise ExecutionIssue("changed")

    def _resolve_control(self, request, state):
        callback = getattr(self.interaction_handler, "resolve", None)
        if callback:
            try: callback(request.public["id"], request.public["revision"], state)
            except Exception: pass  # A lost acknowledgement must not replay an action.

    def _interactive(self, transport, target, run, turn_id, message, pending, seen, *, file_preview=None,
                     codex_local_command=False, prompt_rpc_id=None):
        request = None
        canceled = threading.Event()
        acknowledged = False
        try:
            request = normalize_request(target.tool, message, command_id=run.command_id,
                source_id=target.source_id, session_id=target.session_id, turn_id=turn_id, file_preview=file_preview,
                codex_local_command=codex_local_command)
            key = (type(request.wire_id), request.wire_id)
            if key in seen or len(seen) >= 128: raise UnsupportedControl("duplicate provider request")
            seen[key] = request.public["revision"]
        except (UnsupportedControl, ValueError, TypeError):
            if target.tool in {"codex", "antigravity"}: _reject_request(transport, message)
            transport.send({"type": "control_response", "response": {"subtype": "error",
                "request_id": message.get("request_id"), "error": "Request requires the desktop client"}})
            raise ExecutionIssue("approval")
        completed = queue.Queue(maxsize=1)
        queued = list(pending)
        pending.clear()
        def ask():
            try: completed.put(self.interaction_handler(dict(request.public), canceled))
            except Exception: completed.put(None)
        threading.Thread(target=ask, daemon=True, name="monitor-decision").start()
        try:
            while True:
                transport.check()
                if time.time() >= request.public["expires_at"]: raise ExecutionIssue("approval")
                # Consume provider resolution/cancellation before accepting a phone reply.
                incoming = queued.pop(0) if queued else transport.next(wait=False)
                if incoming is not None:
                    params = incoming.get("params", {})
                    resolved = (target.tool == "codex" and incoming.get("method") == "serverRequest/resolved"
                        and params.get("threadId") == target.session_id and params.get("requestId") == request.wire_id)
                    resolved = resolved or (target.tool == "claude" and incoming.get("type") == "control_cancel_request"
                        and incoming.get("request_id") == request.wire_id)
                    ended = ((target.tool == "codex" and incoming.get("method") == "turn/completed") or
                             (target.tool == "claude" and incoming.get("type") == "result") or
                             (target.tool == "antigravity" and prompt_rpc_id is not None
                              and incoming.get("id") == prompt_rpc_id and "method" not in incoming))
                    if resolved: return
                    if len(pending) >= 64: raise ExecutionIssue("protocol", "uncertain")
                    pending.append(incoming)
                    if ended: return
                    continue
                try: decision = completed.get_nowait()
                except queue.Empty: continue
                if canceled.is_set() or run.cancelled.is_set(): raise ExecutionIssue("cancelled", "uncertain")
                if decision is None: raise ExecutionIssue("approval")
                self._directory_current(target)
                try: response = request.response(decision)
                except UnsupportedControl: raise ExecutionIssue("approval")
                if time.time() >= request.public["expires_at"]: raise ExecutionIssue("approval")
                transport.send(response)
                acknowledged = True
                # Writing the original channel proves forwarding, not provider
                # acceptance and certainly not successful tool execution.
                self._resolve_control(request, "forwarded")
                return ((target.tool == "codex" and response.get("result", {}).get("decision") == "cancel") or
                        (target.tool == "antigravity" and decision.get("decision") == "deny"))
        finally:
            pending.extend(queued)
            canceled.set()
            if not acknowledged: self._resolve_control(request, "cancelled")

    def _antigravity(self, transport, target, prompt, run, settings=None):
        capabilities = {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}
        if self.interaction_handler:
            capabilities["elicitation"] = {"form": {}}
        transport.send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": 1, "clientInfo": {"name": "agent_monitor_local_reply", "version": "1"},
            "clientCapabilities": capabilities}})
        _rpc_response(transport, 1, error_mapper=failure_code_for_antigravity)
        transport.send({"jsonrpc": "2.0", "id": 2, "method": "session/resume", "params": {
            "sessionId": target.session_id, "cwd": str(target.cwd), "mcpServers": []}})
        resumed = _rpc_response(transport, 2, error_mapper=failure_code_for_antigravity)
        if resumed.get("sessionId") != target.session_id:
            raise ExecutionIssue("protocol", "uncertain")
        modes = resumed.get("modes")
        mode = modes.get("currentModeId") if isinstance(modes, dict) else None
        if mode not in {"default", "accept-edits", "plan"}:
            raise ExecutionIssue("permissions")
        if settings:
            try:
                rows, cur_model, cur_effort = antigravity_catalog_rows(resumed)
                current = normalize_catalog("antigravity", rows, current_model=cur_model, current_effort=cur_effort)
                settings = selected_settings(run.selection, current, provider="antigravity", session_id=target.session_id)
            except (ValueError, KeyError, TypeError):
                raise ExecutionIssue("changed")
        self._fresh(target)
        rpc_id = 3
        if not self.interaction_handler and mode == "accept-edits":
            _antigravity_set_config(transport, rpc_id, target.session_id, "mode", "default")
            rpc_id += 1
        if settings:
            _antigravity_set_config(transport, rpc_id, target.session_id, "model", settings["model"])
            rpc_id += 1
            if "effort" in settings:
                _antigravity_set_config(transport, rpc_id, target.session_id, "thought_level", settings["effort"])
                rpc_id += 1
        self._directory_current(target)
        prompt_id = rpc_id
        transport.send({"jsonrpc": "2.0", "id": prompt_id, "method": "session/prompt", "params": {
            "sessionId": target.session_id, "prompt": [{"type": "text", "text": prompt}]}})
        pending, seen = [], {}
        stopped_by_phone = False
        while True:
            message = pending.pop(0) if pending else transport.next()
            if "id" in message and isinstance(message.get("method"), str):
                if not self.interaction_handler:
                    _reject_request(transport, message)
                stopped_by_phone = self._interactive(transport, target, run, run.command_id, message, pending, seen,
                    prompt_rpc_id=prompt_id) or stopped_by_phone
                continue
            if isinstance(message.get("method"), str):
                mp = message.get("params")
                if isinstance(mp, dict) and mp.get("sessionId") not in {None, target.session_id}:
                    raise ExecutionIssue("protocol", "uncertain")
                continue
            if message.get("id") != prompt_id:
                continue
            if "error" in message:
                code = failure_code_for_antigravity(message.get("error"))
                raise ExecutionIssue(code or "failed", "failed")
            result = message.get("result")
            if not isinstance(result, dict):
                raise ExecutionIssue("protocol", "uncertain")
            stop_reason = result.get("stopReason")
            try:
                transport.send({"jsonrpc": "2.0", "id": prompt_id + 1, "method": "session/close",
                                "params": {"sessionId": target.session_id}})
            except ExecutionIssue:
                pass
            if stop_reason == "end_turn":
                return
            if stopped_by_phone and stop_reason in {"cancelled", "refusal"}:
                raise ExecutionIssue("declined", "failed")
            raise ExecutionIssue("failed", "failed")

    def _claude_controlled(self, transport, target, prompt, run, settings):
        initialized = _claude_initialize(transport)
        if settings:
            try:
                current = normalize_catalog("claude", initialized.get("models"))
                selected_settings(run.selection, current, provider="claude", session_id=target.session_id)
            except (ValueError, KeyError, TypeError): raise ExecutionIssue("changed")
        self._fresh(target)
        transport.send({"type": "user", "session_id": target.session_id, "uuid": run.command_id,
                        "message": {"role": "user", "content": prompt}})
        pending, seen = [], {}
        session_initialized = False
        failure_code = ""
        while True:
            message = pending.pop(0) if pending else transport.next()
            session = message.get("session_id")
            if session is not None and session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
            if message.get("type") == "system" and message.get("subtype") == "init":
                if session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
                if message.get("permissionMode") != target.claude_original_mode: raise ExecutionIssue("permissions")
                # init.model is an observation, not an echo of the CLI's model
                # alias. Do not abort an already-started turn for a valid alias
                # resolution, or infer a mapping/applied effort from this field.
                session_initialized = True
            if message.get("type") == "control_request":
                if not session_initialized: raise ExecutionIssue("protocol", "uncertain")
                self._interactive(transport, target, run, run.command_id, message, pending, seen)
                continue
            if message.get("type") == "assistant":
                if "error" in message: failure_code = failure_code_for_claude(message.get("error"))
            if message.get("type") != "result": continue
            if not session_initialized or session != target.session_id: raise ExecutionIssue("protocol", "uncertain")
            if message.get("permission_denials"): raise ExecutionIssue("approval")
            if message.get("subtype") == "success" and message.get("is_error") is False: return
            if "error_type" in message: failure_code = failure_code_for_claude(message.get("error_type"))
            raise ExecutionIssue(failure_code or "failed", "failed")

    def cancel(self, command_id: str) -> bool:
        with self.lock:
            run = self.runs.get(command_id)
            if run is None or run.state != "running": return False
            run.cancelled.set()
            return True

    def close(self):
        with self.lock:
            self.closed = True
            active = [run for run in self.runs.values() if run.state == "running"]
            for run in active: run.cancelled.set()
        for run in active:
            if run.thread is not None and run.thread is not threading.current_thread(): run.thread.join(timeout=5)


def _claude_initialize(transport):
    transport.send({"type": "control_request", "request_id": "monitor-initialize",
                    "request": {"subtype": "initialize", "hooks": None}})
    for _ in range(64):
        message = transport.next()
        if message.get("type") == "control_request": raise ExecutionIssue("approval")
        if message.get("type") != "control_response": continue
        response = message.get("response", {})
        if response.get("request_id") != "monitor-initialize": raise ExecutionIssue("protocol", "uncertain")
        if response.get("subtype") != "success" or response.get("pending_permission_requests"):
            raise ExecutionIssue("protocol", "uncertain")
        if not isinstance(response.get("response"), dict): raise ExecutionIssue("protocol", "uncertain")
        return response["response"]
    raise ExecutionIssue("protocol", "uncertain")


def _codex_model_rows(transport):
    rows, cursor, cursors = [], None, set()
    for page in range(8):
        transport.send({"id": 20 + page, "method": "model/list", "params": {"cursor": cursor, "limit": 64}})
        response = _rpc_response(transport, 20 + page)
        if not isinstance(response.get("data"), list): raise ValueError("invalid catalog")
        rows.extend(response["data"])
        if len(rows) > 256: raise ValueError("catalog too large")
        cursor = response.get("nextCursor")
        if cursor is None: return rows
        if not isinstance(cursor, str) or not cursor or cursor in cursors: raise ValueError("invalid cursor")
        cursors.add(cursor)
    raise ValueError("catalog too large")


def _codex_collaboration_modes(transport):
    """Require a native Plan preset and a native way back to ordinary replies."""
    transport.send({"id": 31, "method": "collaborationMode/list", "params": {}})
    # A CLI missing the optional method may return an RPC error or no result.
    # Preserve that CLI's legacy model catalog and ordinary replies either way.
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        event = transport.next(wait=False)
        if event is None: continue
        _reject_request(transport, event)
        if event.get("id") != 31: continue
        value = event.get("result")
        rows = value.get("data") if isinstance(value, dict) and "error" not in event else None
        if not isinstance(rows, list) or len(rows) > 32:
            return ()
        modes = {row.get("mode") for row in rows if isinstance(row, dict) and isinstance(row.get("mode"), str)}
        return ("plan",) if {"plan", "default"} <= modes else ()
    return ()


def _antigravity_set_config(transport, rpc_id, session_id, config_id, value):
    transport.send({"jsonrpc": "2.0", "id": rpc_id, "method": "session/set_config_option",
                    "params": {"sessionId": session_id, "configId": config_id, "value": value}})
    result = _rpc_response(transport, rpc_id, error_mapper=failure_code_for_antigravity)
    options = result.get("configOptions")
    if not isinstance(options, list):
        raise ExecutionIssue("protocol", "uncertain")
    matched = next((o for o in options if isinstance(o, dict) and o.get("id") == config_id), None)
    if not isinstance(matched, dict) or matched.get("currentValue") != value:
        raise ExecutionIssue("changed")


def _catalog_observation(resolved):
    """A missing or changing observation cannot invalidate the provider's list."""
    try:
        model = effort = None
        if resolved.tool == "antigravity":
            transcript = (_antigravity_base_dir(resolved.path) / "brain" / resolved.session_id
                          / ".system_generated" / "logs" / "transcript.jsonl")
            if not _unlinked(transcript) or not transcript.is_file():
                return None, None
            before = _stamp(transcript)
            _, rows, _ = collector._read_window(transcript, max_bytes=256 * 1024)
            for row in reversed(rows):
                if row.get("type") == "PLANNER_RESPONSE" and row.get("source") == "MODEL" and isinstance(row.get("model"), str):
                    model = row["model"]
                    break
            if before != _stamp(transcript):
                return None, None
        else:
            before = _stamp(resolved.path)
            _, rows, _ = collector._read_window(resolved.path, max_bytes=1024 * 1024)
            if resolved.tool == "codex":
                for row in reversed(rows):
                    context = row.get("payload")
                    if row.get("type") == "turn_context" and isinstance(context, dict):
                        if isinstance(context.get("cwd"), str) and _same_path(context["cwd"], resolved.cwd):
                            model, effort = context.get("model"), context.get("effort")
                        break
            else:
                for row in reversed(rows):
                    message = row.get("message")
                    if row.get("sessionId") == resolved.session_id and not row.get("isSidechain") and isinstance(message, dict) and message.get("model"):
                        model = message["model"]
                        break
            if before != _stamp(resolved.path):
                return None, None
        valid_model = isinstance(model, str) and len(model) <= 120 and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*(?:\[[A-Za-z0-9._-]+\])?", model)
        valid_effort = isinstance(effort, str) and re.fullmatch(r"[a-z0-9_]{1,32}", effort)
        return model if valid_model else None, effort if valid_model and valid_effort else None
    except (OSError, ValueError, TypeError):
        pass
    return None, None


def discover_reply_catalog(source_id, *, resolver=resolve_local_session, status_reader=None,
                           executable_finder=find_executable, process_factory=_Process, timeout_seconds=15, cancelled=None):
    """Capability handshake only. No user message, turn/start, or config read/write.

    An unavailable/unsupported catalog is represented as None, never synthesized.
    The subprocess is owned and closed even when its handshake fails.
    """
    process = transport = None
    try:
        target = _resolve_target(source_id, resolver)
        executable = executable_finder(target.tool)
        if not executable:
            return None
        # Listing capabilities does not open the live session or send a turn.
        if target.tool == "codex":
            argv = [executable, "app-server", "--stdio"]
        elif target.tool == "antigravity":
            base_dir = _antigravity_base_dir(target.path)
            argv = [executable, "acp", "--gemini-dir", str(base_dir.parent),
                    "--app-data-dir", base_dir.name, "--cli_dir_name", "antigravity-cli", "--disable-telemetry"]
        else:
            argv = [executable, "--print", "--input-format", "stream-json", "--output-format", "stream-json",
                    "--verbose", "--no-session-persistence", "--permission-mode", "dontAsk", "--tools", "",
                    "--strict-mcp-config", "--settings", '{"disableAllHooks":true}']
        process = process_factory(argv, target.cwd)
        transport = _Transport(process, cancelled or threading.Event(), min(30, max(.05, timeout_seconds)))
        modes = ()
        reported_model = reported_effort = None
        if target.tool == "codex":
            transport.send({"id": 1, "method": "initialize", "params": {
                "clientInfo": {"name": "agent_monitor_capabilities", "version": "1"}, "capabilities": {"experimentalApi": True}}})
            _rpc_response(transport, 1)
            transport.send({"method": "initialized", "params": {}})
            rows = _codex_model_rows(transport)
            modes = _codex_collaboration_modes(transport)
        elif target.tool == "antigravity":
            transport.send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": 1, "clientInfo": {"name": "agent_monitor_capabilities", "version": "1"},
                "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}}})
            _rpc_response(transport, 1)
            transport.send({"jsonrpc": "2.0", "id": 2, "method": "session/resume", "params": {
                "sessionId": target.session_id, "cwd": str(target.cwd), "mcpServers": []}})
            resumed = _rpc_response(transport, 2)
            if resumed.get("sessionId") != target.session_id:
                return None
            rows, reported_model, reported_effort = antigravity_catalog_rows(resumed)
            try:
                transport.send({"jsonrpc": "2.0", "id": 3, "method": "session/close",
                                "params": {"sessionId": target.session_id}})
            except ExecutionIssue:
                pass
        else:
            rows = _claude_initialize(transport).get("models")
        model, effort = _catalog_observation(target)
        if target.tool == "antigravity" and model is None:
            model, effort = reported_model, reported_effort
        return normalize_catalog(target.tool, rows, current_model=model, current_effort=effort,
                                 collaboration_modes=modes)
    except Exception:
        return None
    finally:
        if transport: transport.done.set()
        if process:
            try: process.close()
            except (OSError, ValueError, subprocess.TimeoutExpired): pass
