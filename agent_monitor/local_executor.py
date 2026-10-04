"""Bounded local continuation of an explicitly selected, inactive agent session.

Prompts travel only over child stdin. No shell, credential parsing, permission
grants, retries, forks, global settings changes, or tool-output forwarding.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
from typing import Callable
import uuid

from . import collector
from .task_results import resolve_local_session, _unlinked

MAX_PROMPT_CHARS = 8_000
MAX_LINE_BYTES = 1024 * 1024
MAX_STREAM_BYTES = 32 * 1024 * 1024
MAX_COMMANDS = 512
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
    "cancelled": "本次回复已停止，部分操作可能已经执行",
    "timeout": "等待执行结果超时，请在电脑上确认后再继续",
    "protocol": "未能确认这轮回复的执行结果，请在电脑上查看",
    "failed": "代理未能完成本轮回复，请在电脑上查看",
}


class ExecutionIssue(Exception):
    def __init__(self, code: str, state: str = "blocked"):
        self.code, self.state = code, state
        super().__init__(code)


def _same_path(a, b) -> bool:
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


def _stamp(path: Path) -> tuple:
    value = path.stat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns


def find_executable(tool: str) -> str | None:
    """Resolve native executables, never execute a Windows shell/npm shim."""
    if tool not in {"codex", "claude"}:
        return None
    entries = [p for p in os.environ.get("PATH", "").split(os.pathsep)
               if p and Path(p).is_absolute() and not _same_path(p, Path.cwd())]
    search = os.pathsep.join(entries)
    found = shutil.which(tool + (".exe" if os.name == "nt" else ""), path=search)
    if found and Path(found).is_file():
        return str(Path(found).resolve())
    if os.name != "nt":
        return None
    shim = shutil.which(tool, path=search)
    if not shim:
        return None
    base = Path(shim).parent
    if tool == "claude":
        candidates = [base / "node_modules/@anthropic-ai/claude-code/bin/claude.exe"]
    else:
        candidates = [base / ("node_modules/@openai/" + package + "/vendor/" + arch + "/codex/codex.exe")
                      for package in ("codex", "codex-win32-x64", "codex-win32-arm64")
                      for arch in ("x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc")]
    return next((str(p.resolve()) for p in candidates if p.is_file()), None)


def supported_tools() -> list[str]:
    return [tool for tool in ("codex", "claude") if find_executable(tool)]


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


def _inspect(source_id, *, resolver=resolve_local_session, status_reader=None, executable_finder=find_executable) -> _Target:
    if not isinstance(source_id, str) or len(source_id) > 200:
        raise ExecutionIssue("invalid")
    resolved = resolver(source_id)
    if resolved is None or resolved.source_id != source_id or resolved.tool not in {"codex", "claude"}:
        raise ExecutionIssue("unavailable")
    try:
        if str(uuid.UUID(resolved.session_id)) != resolved.session_id.lower() or source_id != resolved.tool + ":" + resolved.session_id:
            raise ExecutionIssue("invalid")
        if not _unlinked(resolved.path) or not _unlinked(resolved.cwd) or not resolved.cwd.is_dir():
            raise ExecutionIssue("unavailable")
        before = _stamp(resolved.path)
        if status_reader:
            task = status_reader(resolved)
        elif resolved.tool == "codex":
            task = collector.parse_codex_session(resolved.path, include_output=False)
        else:
            snapshot = collector.collect_snapshot(include_output=False)
            task = next((t for t in snapshot["tasks"] if t["id"] == source_id and t.get("status_source") == "hook"), None)
        if not task or task.get("id") != source_id or task.get("tool") != resolved.tool:
            raise ExecutionIssue("unknown")
        if task.get("status") not in INACTIVE:
            raise ExecutionIssue("busy" if task.get("status") in {"running", "waiting"} else "unknown")
        profile = model = effort = claude_mode = None
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
        else:
            _, rows, _ = collector._read_window(resolved.path, max_bytes=1024 * 1024)
            modes = [row["permissionMode"] for row in rows if "permissionMode" in row]
            if not modes or modes[-1] not in {"plan", "manual", "default", "dontAsk", "auto", "acceptEdits", "bypassPermissions"}:
                raise ExecutionIssue("permissions")
            claude_mode = "plan" if modes[-1] == "plan" else "dontAsk"
        if before != _stamp(resolved.path):
            raise ExecutionIssue("changed")
        executable = executable_finder(resolved.tool)
        if not executable:
            raise ExecutionIssue("cli")
        return _Target(source_id, resolved.tool, resolved.session_id, resolved.path, resolved.cwd, before, executable, profile, model, effort, claude_mode)
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


def _command(target: _Target) -> tuple[list[str], str | None]:
    if target.tool == "claude":
        if target.claude_mode not in {"plan", "dontAsk"}:
            raise ExecutionIssue("permissions")
        argv = [target.executable, "--print", "--resume", target.session_id, "--output-format", "stream-json",
                "--verbose", "--permission-mode", target.claude_mode]
        if target.claude_mode == "plan":
            argv += ["--disallowedTools", "ExitPlanMode"]
        return argv, None
    digest = hashlib.sha256(json.dumps(target.profile, sort_keys=True).encode()).hexdigest()[:24]
    name = "monitor_reply_" + digest
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

    def next(self):
        while True:
            self.check()
            try: line = self.events.get(timeout=.1)
            except queue.Empty: continue
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
    if method in {"item/commandExecution/requestApproval", "item/fileChange/requestApproval"}:
        response["result"] = {"decision": "cancel"}
    elif method == "item/permissions/requestApproval":
        response["result"] = {"permissions": {}, "scope": "turn"}
    elif method == "mcpServer/elicitation/request":
        response["result"] = {"action": "cancel", "content": None}
    else:
        response["error"] = {"code": -32601, "message": "Interactive requests require the desktop client"}
    transport.send(response)
    raise ExecutionIssue("approval")


def _rpc_response(transport, wanted, pending=None):
    while True:
        message = transport.next()
        _reject_request(transport, message)
        if message.get("id") == wanted:
            if "error" in message: raise ExecutionIssue("failed", "failed")
            if not isinstance(message.get("result"), dict): raise ExecutionIssue("protocol", "uncertain")
            return message["result"]
        if pending is not None and message.get("method") == "turn/completed":
            if len(pending) >= 8: raise ExecutionIssue("protocol", "uncertain")
            pending.append(message)


@dataclass
class _Run:
    command_id: str
    source_id: str
    cancelled: threading.Event = field(default_factory=threading.Event)
    thread: threading.Thread | None = None
    state: str = "running"
    reason: str = ""


class LocalReplyExecutor:
    def __init__(self, emit: Callable[[dict], None], *, timeout_seconds=1800, resolver=resolve_local_session,
                 status_reader=None, executable_finder=find_executable, process_factory=_Process):
        self.emit = emit
        self.timeout = max(.05, min(float(timeout_seconds), 3600))
        self.inspect_options = {"resolver": resolver, "status_reader": status_reader, "executable_finder": executable_finder}
        self.process_factory = process_factory
        self.lock = threading.Lock()
        self.runs = {}
        self.closed = False

    def _result(self, run):
        return {"command_id": run.command_id, "state": run.state, "reason": run.reason}

    def _emit(self, value):
        try: self.emit(dict(value))
        except Exception: pass  # A UI/store callback cannot grant permissions or restart work.

    def start(self, command_id: str, source_id: str, prompt: str) -> dict:
        if not isinstance(command_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", command_id):
            return {"command_id": "", "state": "blocked", "reason": REASONS["invalid"]}
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_CHARS or "\x00" in prompt:
            return {"command_id": command_id, "state": "blocked", "reason": REASONS["invalid"]}
        with self.lock:
            if command_id in self.runs: return self._result(self.runs[command_id])
            if self.closed or len(self.runs) >= MAX_COMMANDS:
                return {"command_id": command_id, "state": "blocked", "reason": REASONS["closed" if self.closed else "duplicate"]}
            if any(r.source_id == source_id and r.state == "running" for r in self.runs.values()):
                return {"command_id": command_id, "state": "blocked", "reason": REASONS["busy"]}
            run = _Run(command_id, source_id)
            self.runs[command_id] = run
        try: target = _inspect(source_id, **self.inspect_options)
        except ExecutionIssue as exc:
            with self.lock: run.state, run.reason = exc.state, REASONS[exc.code]
            return self._result(run)
        with self.lock:
            if self.closed or run.cancelled.is_set():
                run.state, run.reason = "blocked", REASONS["closed"]
                return self._result(run)
            run.thread = threading.Thread(target=self._execute, args=(run, target, prompt), daemon=True,
                                          name="monitor-reply-" + command_id[:12])
            run.thread.start()
            return self._result(run)

    def _fresh(self, target):
        fresh = _inspect(target.source_id, **self.inspect_options)
        if fresh != target: raise ExecutionIssue("changed")

    def _execute(self, run, target, prompt):
        process = transport = None
        state, reason = "uncertain", REASONS["protocol"]
        try:
            self._fresh(target)
            if run.cancelled.is_set(): raise ExecutionIssue("cancelled", "uncertain")
            argv, profile_name = _command(target)
            process = self.process_factory(argv, target.cwd)
            transport = _Transport(process, run.cancelled, self.timeout)
            self._emit(self._result(run))
            if target.tool == "codex": self._codex(transport, target, profile_name, prompt, run.command_id)
            else: self._claude(transport, target, prompt)
            state, reason = "succeeded", ""
        except ExecutionIssue as exc:
            state, reason = exc.state, REASONS[exc.code]
        except Exception:
            state, reason = ("uncertain", REASONS["protocol"]) if process else ("blocked", REASONS["unavailable"])
        finally:
            if transport: transport.done.set()
            if process:
                try: process.close(graceful=state == "succeeded")
                except (OSError, ValueError, subprocess.TimeoutExpired): state, reason = "uncertain", REASONS["protocol"]
            with self.lock: run.state, run.reason = state, reason
            self._emit(self._result(run))

    def _codex(self, transport, target, profile, prompt, command_id):
        transport.send({"id": 1, "method": "initialize", "params": {
            "clientInfo": {"name": "agent_monitor_local_reply", "version": "1"}, "capabilities": {"experimentalApi": True}}})
        _rpc_response(transport, 1)
        transport.send({"method": "initialized", "params": {}})
        params = {"threadId": target.session_id, "cwd": str(target.cwd), "permissions": profile,
                  "approvalPolicy": "untrusted", "approvalsReviewer": "user", "excludeTurns": True}
        if target.model: params["model"] = target.model
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
        self._fresh(target)
        params = {"threadId": target.session_id, "input": [{"type": "text", "text": prompt}],
                  "cwd": str(target.cwd), "permissions": profile, "approvalPolicy": "untrusted", "approvalsReviewer": "user",
                  "clientUserMessageId": command_id}
        if target.effort: params["effort"] = target.effort
        transport.send({"id": 3, "method": "turn/start", "params": params})
        pending = []
        started = _rpc_response(transport, 3, pending)
        turn_id = started.get("turn", {}).get("id")
        if not isinstance(turn_id, str) or not turn_id: raise ExecutionIssue("protocol", "uncertain")
        while True:
            message = pending.pop(0) if pending else transport.next()
            _reject_request(transport, message)
            if message.get("method") != "turn/completed": continue
            params = message.get("params", {})
            turn = params.get("turn", {})
            if params.get("threadId") != target.session_id or turn.get("id") != turn_id:
                raise ExecutionIssue("protocol", "uncertain")
            if turn.get("status") == "completed" and not turn.get("error"): return
            raise ExecutionIssue("failed", "failed" if turn.get("status") == "failed" else "uncertain")

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
            raise ExecutionIssue("failed", "failed")

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
