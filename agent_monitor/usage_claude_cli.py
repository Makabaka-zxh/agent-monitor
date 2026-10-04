"""Opt-in, pinned official Claude /usage reader; no credential-file access.

Claude 2.1.281 handles /usage as a built-in local command. Its documented
usage_report is an assistant wrapper field (SDK changelog 0.3.273/0.3.277).
This reader never submits other prompts, guesses endpoints, logs raw output,
changes Claude settings, or falls back to another executable/version.
"""
from __future__ import annotations

import hashlib
import math
import os
import re
import signal
import stat
import subprocess
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .usage_statusline import decode, safe_path


SUPPORTED_VERSION = "2.1.281"
MIN_INTERVAL = 300
MAX_BACKOFF = 3600
TOTAL_TIMEOUT = 45
MAX_OUTPUT = 1024 * 1024
MAX_LINE = 256 * 1024
MAX_LIMITS = 64
WINDOWS = {"session": "five_hour", "weekly_all": "seven_day"}
SESSION_SAFETY_ARGS = ["--safe-mode", "--settings", '{"disableAllHooks":true}']


@dataclass(frozen=True)
class ClaudeQuotaRead:
    status: str
    snapshot: dict | None = None
    retry_after_seconds: int = 0


@dataclass(frozen=True)
class CommandResult:
    """Internal process transport. Raw stdout must never be logged/persisted."""
    returncode: int | None
    stdout: bytes = field(default=b"", repr=False)
    error: str | None = None


class _WindowsJob:
    """Kill only this command's process tree when its job handle is closed."""
    def __init__(self):
        import ctypes
        from ctypes import wintypes

        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                        ("flags", wintypes.DWORD), ("min_working", ctypes.c_size_t),
                        ("max_working", ctypes.c_size_t), ("process_limit", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                        ("scheduling", wintypes.DWORD)]

        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", ctypes.c_uint64 * 6),
                        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t)]

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        kernel.SetInformationJobObject.restype = wintypes.BOOL
        kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle.restype = wintypes.BOOL
        self.kernel = kernel
        self.handle = kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise OSError("job creation failed")
        info = Extended()
        info.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
            self.close()
            raise OSError("job configuration failed")

    def assign(self, process):
        if not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
            raise OSError("job assignment failed")

    def resume(self, process):
        """Resume only this initially suspended child's threads, after assignment.

        Popen closes the primary thread handle. Use documented Toolhelp/OpenThread
        APIs to recover owned thread handles; verify ownership again on the handle
        before resuming, so a recycled thread id cannot target another process.
        """
        import ctypes
        from ctypes import wintypes

        class ThreadEntry(ctypes.Structure):
            _fields_ = [("size", wintypes.DWORD), ("usage", wintypes.DWORD),
                        ("thread_id", wintypes.DWORD), ("owner_pid", wintypes.DWORD),
                        ("base_priority", wintypes.LONG), ("delta_priority", wintypes.LONG),
                        ("flags", wintypes.DWORD)]

        kernel = self.kernel
        kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        kernel.Thread32First.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.Thread32First.restype = wintypes.BOOL
        kernel.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        kernel.Thread32Next.restype = wintypes.BOOL
        kernel.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenThread.restype = wintypes.HANDLE
        kernel.GetProcessIdOfThread.argtypes = [wintypes.HANDLE]
        kernel.GetProcessIdOfThread.restype = wintypes.DWORD
        kernel.ResumeThread.argtypes = [wintypes.HANDLE]
        kernel.ResumeThread.restype = wintypes.DWORD
        snapshot = kernel.CreateToolhelp32Snapshot(0x4, 0)  # TH32CS_SNAPTHREAD
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            raise OSError("thread snapshot failed")
        owned = []
        try:
            entry = ThreadEntry()
            entry.size = ctypes.sizeof(entry)
            more = kernel.Thread32First(snapshot, ctypes.byref(entry))
            while more:
                if entry.owner_pid == process.pid:
                    owned.append(entry.thread_id)
                entry.size = ctypes.sizeof(entry)
                more = kernel.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            kernel.CloseHandle(snapshot)
        if not owned:
            raise OSError("owned thread missing")
        resumed = False
        for thread_id in owned:
            handle = kernel.OpenThread(0x0002 | 0x0800, False, thread_id)
            if not handle:
                raise OSError("owned thread unavailable")
            try:
                if kernel.GetProcessIdOfThread(handle) != process.pid:
                    raise OSError("thread ownership changed")
                count = kernel.ResumeThread(handle)
                if count == 0xFFFFFFFF:
                    raise OSError("owned thread resume failed")
                resumed = resumed or count > 0
            finally:
                kernel.CloseHandle(handle)
        if not resumed:
            raise OSError("child was not suspended")

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


def _identity(value):
    # Reading can update access time; that must not invalidate a verified pin.
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def _run_command(args: list[str], timeout: float) -> CommandResult:
    """Bound time and combined output; drain but never retain stderr."""
    if timeout <= 0:
        return CommandResult(None, error="timeout")
    timeout = min(TOTAL_TIMEOUT, timeout)
    deadline = time.monotonic() + timeout
    # CREATE_SUSPENDED closes the gap where an unassigned child could spawn an
    # uncontained descendant before AssignProcessToJobObject completes.
    flags = (subprocess.CREATE_NO_WINDOW | 0x00000004) if os.name == "nt" else 0
    job = None
    try:
        if os.name == "nt":
            job = _WindowsJob()
    except OSError:
        return CommandResult(None, error="containment_failed")
    try:
        process = subprocess.Popen(args, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   shell=False, creationflags=flags,
                                   start_new_session=os.name != "nt",
                                   cwd=str(Path(args[0]).parent))
    except OSError:
        if job:
            job.close()
        return CommandResult(None, error="launch_failed")
    containment_error = "containment_failed"
    try:
        if job:
            job.assign(process)
            if time.monotonic() >= deadline - .25:
                containment_error = "timeout"
                raise OSError("deadline before resume")
            job.resume(process)
    except OSError:
        try:
            process.kill()
        except OSError:
            pass
        if job:
            job.close()
        try:
            process.wait(timeout=max(.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            pass
        process.stdout.close()
        process.stderr.close()
        return CommandResult(process.returncode, error=containment_error)

    def kill_tree():
        if job:
            job.close()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except OSError:
                pass
    output = bytearray()
    total = 0
    lock = threading.Lock()
    overflow = threading.Event()
    failed = threading.Event()

    def drain(pipe, retain):
        nonlocal total
        try:
            while True:
                chunk = pipe.read(8192)
                if not chunk:
                    return
                with lock:
                    total += len(chunk)
                    if total > MAX_OUTPUT:
                        overflow.set()
                    elif retain and not overflow.is_set():
                        output.extend(chunk)
        except (OSError, ValueError):
            failed.set()

    readers = [threading.Thread(target=drain, args=(process.stdout, True), daemon=True),
               threading.Thread(target=drain, args=(process.stderr, False), daemon=True)]
    for reader in readers:
        reader.start()
    timed_out = False
    while process.poll() is None:
        if overflow.is_set() or failed.is_set() or time.monotonic() >= deadline - .25:
            timed_out = not overflow.is_set() and not failed.is_set()
            kill_tree()
            break
        time.sleep(min(.02, max(.001, deadline - time.monotonic())))
    try:
        process.wait(timeout=max(.001, deadline - time.monotonic()))
    except subprocess.TimeoutExpired:
        timed_out = True
    kill_tree()  # Also remove any descendants left after an ordinary exit.
    for reader in readers:
        reader.join(timeout=max(0, (deadline - time.monotonic()) / 2))
    readers_alive = any(reader.is_alive() for reader in readers)
    if not readers_alive:
        process.stdout.close()
        process.stderr.close()
    if timed_out or readers_alive:
        return CommandResult(process.returncode, error="timeout")
    if overflow.is_set():
        return CommandResult(process.returncode, error="output_limit")
    if failed.is_set():
        return CommandResult(process.returncode, error="read_failed")
    return CommandResult(process.returncode, bytes(output))


def _auth_state(raw: bytes) -> str:
    """Allowlist official auth-status classes; discard all identity/path fields."""
    try:
        value = decode(raw)
    except (ValueError, UnicodeError, RecursionError):
        return "auth_unavailable"
    if not isinstance(value, dict) or type(value.get("loggedIn")) is not bool:
        return "auth_unavailable"
    if not value["loggedIn"]:
        return "not_logged_in"
    method = value.get("authMethod")
    if method in ("api_key", "api_key_helper"):
        return "api_key_only"
    if method == "third_party":
        return "unsupported_provider"
    if method in ("claude.ai", "oauth_token"):
        return "authenticated"
    return "unsupported_account"


def _numeric(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _report_windows(report: dict) -> tuple[str, dict | None]:
    rate_limits = report.get("rate_limits")
    rows = rate_limits.get("limits") if isinstance(rate_limits, dict) else None
    if rows is None:
        return "no_live_limits", None
    if not isinstance(rows, list) or len(rows) > MAX_LIMITS:
        return "invalid_report", None
    windows = {}
    for row in rows:
        if not isinstance(row, dict):
            return "invalid_report", None
        kind = row.get("kind")
        if not isinstance(kind, str) or kind not in WINDOWS:
            # New/scoped meters must never be relabelled as an account total.
            continue
        if row.get("scope") is not None:
            continue
        slot = WINDOWS[kind]
        if slot in windows:
            # Even identical duplicate rows may represent different accounts.
            return "ambiguous_windows", None
        used, stamp = row.get("percent"), row.get("resets_at")
        if not _numeric(used) or not 0 <= used <= 100:
            return "invalid_report", None
        if "resets_at" not in row:
            return "invalid_report", None
        seconds = None
        if stamp is not None:  # Official schema is required-but-nullable.
            if not isinstance(stamp, str) or len(stamp) > 40:
                return "invalid_report", None
            try:
                reset = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
                if reset.tzinfo is None:
                    raise ValueError("invalid reset")
                # The official ISO timestamp may include fractional seconds.
                # Ledger timestamps are integral; round up so its reset never
                # appears earlier than the observed server reset.
                seconds = math.ceil(reset.timestamp())
                if not 0 < seconds <= 253402300799:
                    raise ValueError("invalid reset")
            except (ValueError, OverflowError, OSError):
                return "invalid_report", None
        windows[slot] = {"used_percentage": used, "resets_at": int(seconds) if seconds is not None else None}
    return ("ok", windows) if windows else ("no_numeric_windows", None)


def parse_usage_output(raw: bytes, observed_at: datetime) -> ClaudeQuotaRead:
    """Accept one official report and one successful zero-model-call result."""
    if len(raw) > MAX_OUTPUT:
        return ClaudeQuotaRead("output_limit")
    reports = []
    results = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        if len(line) > MAX_LINE:
            return ClaudeQuotaRead("output_limit")
        try:
            event = decode(line)
        except (ValueError, UnicodeError, RecursionError):
            return ClaudeQuotaRead("invalid_output")
        if not isinstance(event, dict):
            return ClaudeQuotaRead("invalid_output")
        if event.get("type") == "assistant" and "usage_report" in event:
            reports.append(event["usage_report"])
        elif event.get("type") == "result":
            results.append(event)
        # All body, session/account identifiers and other event data are dropped.
    if len(results) != 1:
        return ClaudeQuotaRead("local_execution_unverified")
    result = results[0]
    if not (result.get("local_command") == "usage" and result.get("subtype") == "success"
            and result.get("is_error") is not True
            and type(result.get("num_turns")) is int and result["num_turns"] == 0
            and type(result.get("duration_api_ms")) is int and result["duration_api_ms"] == 0):
        return ClaudeQuotaRead("local_execution_unverified")
    if not reports:
        return ClaudeQuotaRead("no_usage_report")
    if len(reports) != 1 or not isinstance(reports[0], dict):
        return ClaudeQuotaRead("ambiguous_reports")
    status, windows = _report_windows(reports[0])
    if windows is None:
        return ClaudeQuotaRead(status)
    if not isinstance(observed_at, datetime) or observed_at.tzinfo is None:
        return ClaudeQuotaRead("invalid_observation_time")
    return ClaudeQuotaRead("ok", {"observed_at": observed_at.astimezone(timezone.utc).isoformat(
        timespec="microseconds").replace("+00:00", "Z"), "rate_limits": windows})


class ClaudeCliQuotaReader:
    """A service-owned reader; poll never refreshes a snapshot on failure.

    The service may call poll on its normal loop. This instance also enforces a
    five-minute floor and failure backoff (5, 10, 20, 40, 60 minutes). Reuse the
    instance across calls. Disabled is the default and starts no subprocess.
    """

    def __init__(self, executable: Path | str | None = None, expected_sha256: str = "", *,
                 enabled: bool = False, version: str = SUPPORTED_VERSION,
                 monotonic: Callable[[], float] = time.monotonic,
                 utcnow: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
                 runner: Callable[[list[str], float], CommandResult] | None = None):
        self.executable = Path(executable) if executable is not None else None
        self.expected_sha256 = expected_sha256.lower() if isinstance(expected_sha256, str) else ""
        self.version = version
        self.enabled = enabled is True
        self._monotonic = monotonic
        self._utcnow = utcnow
        self._runner = runner or _run_command
        self._next_attempt = 0.0
        self._failures = 0
        self._lock = threading.Lock()
        self.last_phase = "idle"

    def _pin(self, deadline):
        path = self.executable
        if (path is None or not path.is_absolute() or path.suffix.lower() != ".exe"
                or self.version != SUPPORTED_VERSION
                or not re.fullmatch(r"[0-9a-f]{64}", self.expected_sha256)):
            return "invalid_pin", None
        if not safe_path(path):
            return "unsafe_executable", None
        try:
            before = path.stat()
            if not stat.S_ISREG(before.st_mode):
                return "invalid_pin", None
            digest = hashlib.sha256()
            with path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    if self._monotonic() >= deadline:
                        return "timeout", None
                    digest.update(chunk)
            after = path.stat()
        except OSError:
            return "executable_unavailable", None
        if _identity(before) != _identity(after) or digest.hexdigest() != self.expected_sha256:
            return "pin_mismatch", None
        return "ok", _identity(after)

    def _invoke(self, tail, deadline):
        remaining = deadline - self._monotonic()
        if remaining <= 0:
            return CommandResult(None, error="timeout")
        try:
            result = self._runner([str(self.executable), *tail], remaining)
        except Exception:
            # Exceptions can contain paths/credentials/raw child output.
            return CommandResult(None, error="runner_failed")
        if self._monotonic() >= deadline:
            return CommandResult(None, error="timeout")
        if not isinstance(result, CommandResult) or not isinstance(result.stdout, bytes):
            return CommandResult(None, error="invalid_runner_result")
        if len(result.stdout) > MAX_OUTPUT:
            return CommandResult(result.returncode, error="output_limit")
        if result.error not in (None, "timeout", "launch_failed", "output_limit", "read_failed", "containment_failed"):
            return CommandResult(result.returncode, error="runner_failed")
        return result

    def _read(self, deadline):
        self.last_phase = "pin"
        status, identity = self._pin(deadline)
        if status != "ok":
            return ClaudeQuotaRead(status)
        self.last_phase = "version"
        version = self._invoke(["--version"], deadline)
        if version.error:
            return ClaudeQuotaRead(version.error)
        if version.returncode != 0 or version.stdout.strip() != b"2.1.281 (Claude Code)":
            return ClaudeQuotaRead("version_mismatch")
        self.last_phase = "auth"
        auth = self._invoke([*SESSION_SAFETY_ARGS, "auth", "status", "--json"], deadline)
        if auth.error:
            return ClaudeQuotaRead(auth.error)
        auth_state = _auth_state(auth.stdout)
        if auth_state != "authenticated":
            return ClaudeQuotaRead(auth_state)
        if auth.returncode != 0:
            return ClaudeQuotaRead("auth_unavailable")
        try:
            if _identity(self.executable.stat()) != identity:
                return ClaudeQuotaRead("pin_mismatch")
        except OSError:
            return ClaudeQuotaRead("executable_unavailable")
        self.last_phase = "usage"
        command = self._invoke([*SESSION_SAFETY_ARGS, "--no-session-persistence", "--strict-mcp-config",
                                "--tools", "", "--permission-mode", "dontAsk", "--output-format",
                                "stream-json", "--verbose", "-p", "/usage"], deadline)
        if command.error:
            return ClaudeQuotaRead(command.error)
        if command.returncode != 0:
            return ClaudeQuotaRead("official_command_failed")
        result = parse_usage_output(command.stdout, self._utcnow())
        self.last_phase = "complete"
        return result

    def poll(self) -> ClaudeQuotaRead:
        if not self.enabled:
            return ClaudeQuotaRead("disabled")
        if not self._lock.acquire(blocking=False):
            return ClaudeQuotaRead("busy", retry_after_seconds=MIN_INTERVAL)
        try:
            now = self._monotonic()
            if now < self._next_attempt:
                return ClaudeQuotaRead("throttled", retry_after_seconds=math.ceil(self._next_attempt - now))
            try:
                result = self._read(now + TOTAL_TIMEOUT)
            except Exception:
                result = ClaudeQuotaRead("reader_failed")
            if result.status == "ok":
                self._failures = 0
                delay = MIN_INTERVAL
            else:
                self._failures = min(5, self._failures + 1)
                delay = min(MAX_BACKOFF, MIN_INTERVAL * 2 ** (self._failures - 1))
            self._next_attempt = self._monotonic() + delay
            return ClaudeQuotaRead(result.status, result.snapshot, delay)
        finally:
            self._lock.release()
