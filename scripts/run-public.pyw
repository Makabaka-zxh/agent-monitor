"""One-process Windows GUI launcher for the existing personal hub.

Task Scheduler should execute the existing virtual environment's pythonw.exe
directly. No subprocess, console, registration, or database creation.
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager, ExitStack
import ctypes
from ctypes import wintypes
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import re
import socket
import stat
import sys
import threading
import time
from urllib.parse import urlsplit, urlunsplit

PROJECT_ROOT = Path(__file__).resolve().parents[1]
STATUS_FILE = "launcher-status.json"
LOCK_FILE = ".public-run.lock"
EVENTS_FILE = "launcher-events.json"
LISTENER_RECOVERY_LIMIT = 3
LISTENER_RECOVERY_TIMEOUT = 5.0
LISTENER_EVENT_LIMIT = 32
LISTENER_EVENT_READ_LIMIT = 65536
HEARTBEAT_POLL_SECONDS = 5.0
HEARTBEAT_STALL_SECONDS = 120.0
HEARTBEAT_CONFIRM_SECONDS = 30.0
HEARTBEAT_DIAGNOSTIC_SECONDS = 0.5
HEARTBEAT_EXIT_CODE = 70
HEARTBEAT_FAILURE_FILE = "launcher-heartbeat-failure.json"
_EVENT_NAMES = frozenset({
    "starting", "started", "startup-failed", "accept-failed", "listener-missing",
    "recovery-started", "recovery-restored", "recovery-failed", "recovery-exhausted",
    "stopping", "stopped", "server-failed",
})
_ERROR_NAMES = frozenset({"", "OSError", "TimeoutError", "CancelledError", "RuntimeError", "Exception"})


class LauncherError(Exception):
    def __init__(self, reason: str, exit_code: int = 2):
        super().__init__(reason)
        self.reason, self.exit_code = reason, exit_code


@dataclass(frozen=True)
class Settings:
    public_url: str
    state_dir: Path
    port: int
    hostname: str


def settings(public_url: str, state_dir: str | Path | None, port: int,
             project_root: Path = PROJECT_ROOT) -> Settings:
    if not (project_root / "agent_monitor" / "__main__.py").is_file():
        raise LauncherError("invalid-project-layout")
    candidate = Path(state_dir) if state_dir else Path(".state/hub")
    if not candidate.is_absolute():
        candidate = project_root / candidate
    try:
        resolved = candidate.resolve(strict=True)
    except OSError:
        raise LauncherError("existing-state-directory-required") from None
    if not resolved.is_dir() or not (resolved / "monitor.sqlite3").is_file():
        raise LauncherError("existing-database-required")
    if type(port) is not int or not 1 <= port <= 65535:
        raise LauncherError("invalid-port")
    try:
        if not public_url or re.search(r"[\s\x00-\x1f\x7f]", public_url):
            raise ValueError
        parsed = urlsplit(public_url)
        host, public_port = parsed.hostname, parsed.port
        if (parsed.scheme != "https" or not host or parsed.username is not None
                or parsed.password is not None or parsed.path not in {"", "/"}
                or parsed.query or parsed.fragment or any(c in host for c in "\\%*")):
            raise ValueError
        if public_port is not None and not 1 <= public_port <= 65535:
            raise ValueError
        host = host.encode("idna").decode("ascii").lower()
        authority = f"[{host}]" if ":" in host else host
        if public_port not in {None, 443}:
            authority += f":{public_port}"
        origin = urlunsplit(("https", authority, "", "", ""))
    except (TypeError, ValueError, UnicodeError):
        raise LauncherError("invalid-https-origin") from None
    return Settings(origin, resolved, port, host)


def windows_runtime():
    if os.name != "nt" or sys.version_info < (3, 12):
        raise LauncherError("windows-python-3.12-required")
    if Path(sys.executable).name.lower() != "pythonw.exe":
        raise LauncherError("use-pythonw-exe-directly")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.GetConsoleWindow.restype = wintypes.HWND
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    if kernel.GetConsoleWindow():
        raise LauncherError("console-attached")
    security = ctypes.WinDLL("advapi32", use_last_error=True)
    security.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    security.OpenProcessToken.restype = wintypes.BOOL
    security.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID,
                                              wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    security.GetTokenInformation.restype = wintypes.BOOL
    security.IsWellKnownSid.argtypes = [wintypes.LPVOID, ctypes.c_int]
    security.IsWellKnownSid.restype = wintypes.BOOL
    token = wintypes.HANDLE()
    if not security.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise LauncherError("current-user-unavailable")
    try:
        length = wintypes.DWORD()
        security.GetTokenInformation(token, 1, None, 0, ctypes.byref(length))
        if not 0 < length.value < 65536:
            raise LauncherError("current-user-unavailable")
        buffer = ctypes.create_string_buffer(length.value)
        if not security.GetTokenInformation(token, 1, buffer, length, ctypes.byref(length)):
            raise LauncherError("current-user-unavailable")
        # TOKEN_USER starts with SID_AND_ATTRIBUTES, whose first field is PSID.
        sid = ctypes.cast(buffer, ctypes.POINTER(wintypes.LPVOID)).contents.value
        if any(security.IsWellKnownSid(sid, kind) for kind in (22, 23, 24)):
            raise LauncherError("interactive-user-required")
    finally:
        kernel.CloseHandle(token)


class PublicLock:
    """Uses the same Win32 exclusive share mode as run-public.ps1."""
    def __init__(self, state_dir: Path):
        self.path, self.handle = state_dir / LOCK_FILE, None

    def __enter__(self):
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                      wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
        kernel.CreateFileW.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel = kernel
        # GENERIC_READ|GENERIC_WRITE, share=NONE, OPEN_ALWAYS, normal attributes.
        handle = kernel.CreateFileW(str(self.path), 0xC0000000, 0, None, 4, 0x80, None)
        if handle == ctypes.c_void_p(-1).value:
            reason = "state-already-in-use" if ctypes.get_last_error() in {32, 33} else "state-lock-unavailable"
            raise LauncherError(reason, 3)
        self.handle = handle
        return self

    def __exit__(self, *args):
        if self.handle is not None:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


@contextmanager
def reserved_socket(port: int):
    connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if os.name == "nt":
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        connection.set_inheritable(False)
        try:
            connection.bind(("127.0.0.1", port))
        except OSError:
            raise LauncherError("loopback-port-unavailable", 4) from None
        yield connection
    finally:
        connection.close()


def write_status(state_dir: Path, phase: str, *, reason: str = "", error: BaseException | None = None):
    # Fixed fields only: never serialize exception messages, argv, URLs,
    # HTTP requests, OAuth callback parameters, or account/connector data.
    payload = {"phase": phase, "time": datetime.now(timezone.utc).isoformat(),
               "pid": os.getpid(), "reason": reason,
               "exception_type": type(error).__name__ if error else ""}
    temporary = state_dir / f".launcher-status-{os.getpid()}.tmp"
    try:
        with temporary.open("w", encoding="utf-8") as output:
            os.chmod(temporary, 0o600)
            json.dump(payload, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, state_dir / STATUS_FILE)
    finally:
        temporary.unlink(missing_ok=True)


def _safe_error_name(error):
    if error is None:
        return ""
    for kind in (asyncio.CancelledError, TimeoutError, OSError, RuntimeError):
        if isinstance(error, kind):
            return kind.__name__
    return "Exception"


def _safe_error_number(error, name):
    try:
        value = getattr(error, name, None)
    except Exception:
        return None
    return value if type(value) is int and -(2 ** 31) <= value < 2 ** 32 else None


def _regular_journal(path):
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode)
            or getattr(metadata, "st_file_attributes", 0) & 0x400):
        raise OSError("unsafe-lifecycle-journal")
    return metadata


def _journal_fingerprint(metadata):
    return (metadata.st_dev, metadata.st_ino, metadata.st_size,
            metadata.st_mtime_ns, metadata.st_ctime_ns)


class LifecycleEvents:
    """A bounded journal containing only validated, anonymous lifecycle facts."""
    def __init__(self, state_dir):
        self.state_dir = state_dir
        self.events = []
        try:
            path = state_dir / EVENTS_FILE
            before = _regular_journal(path)
            with path.open("rb") as source:
                opened = os.fstat(source.fileno())
                # Windows named-stat and descriptor-stat ctime can describe
                # different clocks. Compare each fingerprint only to its own
                # kind, using dev/ino for the cross-kind identity check.
                if (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
                    raise OSError("changed-lifecycle-journal")
                raw = source.read(LISTENER_EVENT_READ_LIMIT + 1)
                if (_journal_fingerprint(opened) != _journal_fingerprint(os.fstat(source.fileno()))
                        or _journal_fingerprint(before) != _journal_fingerprint(_regular_journal(path))):
                    raise OSError("changed-lifecycle-journal")
        except FileNotFoundError:
            return
        if len(raw) > LISTENER_EVENT_READ_LIMIT:
            return
        try:
            previous = json.loads(raw)
        except (UnicodeError, ValueError):
            return
        if (type(previous) is not dict or set(previous) != {"schema", "events"}
                or type(previous["schema"]) is not int or previous["schema"] != 1
                or type(previous["events"]) is not list):
            return
        self.events = [event for event in previous["events"][-LISTENER_EVENT_LIMIT:]
                       if self._valid(event)]

    @staticmethod
    def _valid(event):
        if type(event) is not dict or set(event) != {
                "event", "time", "attempt", "exception_type", "errno", "winerror"}:
            return False
        if (type(event["event"]) is not str or event["event"] not in _EVENT_NAMES
                or type(event["exception_type"]) is not str or event["exception_type"] not in _ERROR_NAMES
                or type(event["attempt"]) is not int or not 0 <= event["attempt"] <= LISTENER_RECOVERY_LIMIT
                or type(event["time"]) is not str or not re.fullmatch(
                    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?\+00:00", event["time"])):
            return False
        try:
            datetime.fromisoformat(event["time"])
        except ValueError:
            return False
        return all(value is None or (type(value) is int and -(2 ** 31) <= value < 2 ** 32)
                   for value in (event["errno"], event["winerror"]))

    def record(self, event, *, attempt=0, error=None):
        record = {"event": event, "time": datetime.now(timezone.utc).isoformat(),
                  "attempt": attempt,
                  "exception_type": _safe_error_name(error),
                  "errno": _safe_error_number(error, "errno"),
                  "winerror": _safe_error_number(error, "winerror")}
        if not self._valid(record):
            raise ValueError("invalid-lifecycle-event")
        events = (self.events + [record])[-LISTENER_EVENT_LIMIT:]
        temporary = self.state_dir / f".launcher-events-{os.getpid()}.tmp"
        created = False
        try:
            try:
                _regular_journal(self.state_dir / EVENTS_FILE)
            except FileNotFoundError:
                pass
            with temporary.open("x", encoding="utf-8") as output:
                created = True
                os.chmod(temporary, 0o600)
                json.dump({"schema": 1, "events": events}, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.state_dir / EVENTS_FILE)
            self.events = events
        finally:
            if created:
                temporary.unlink(missing_ok=True)


def listener_healthy(listener, port):
    # Proactor can close the raw socket without clearing Server.is_serving().
    # Check the owned socket itself; never probe or acquire an unrelated port.
    try:
        return (listener.fileno() >= 0
                and listener.getsockname() == ("127.0.0.1", port)
                and listener.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN) == 1)
    except OSError:
        return False


def write_heartbeat_failure(state_dir):
    """Best-effort fixed facts; called off the monitor thread before hard exit."""
    payload = {"schema": 1, "event": "event-loop-stalled",
               "time": datetime.now(timezone.utc).isoformat(),
               "stall_seconds": HEARTBEAT_STALL_SECONDS,
               "confirmation_seconds": HEARTBEAT_CONFIRM_SECONDS,
               "exit_code": HEARTBEAT_EXIT_CODE}
    temporary = state_dir / ".launcher-heartbeat-failure.tmp"
    created = False
    try:
        try:
            _regular_journal(state_dir / HEARTBEAT_FAILURE_FILE)
        except FileNotFoundError:
            pass
        with temporary.open("x", encoding="utf-8") as output:
            created = True
            os.chmod(temporary, 0o600)
            json.dump(payload, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, state_dir / HEARTBEAT_FAILURE_FILE)
    finally:
        if created:
            temporary.unlink(missing_ok=True)


class EventLoopHeartbeat:
    """Observe loop progress without relying on that loop to run a timeout.

    This is deliberately an in-process thread, not a host/whole-process
    watchdog. Python must still be able to schedule the monitor thread.
    """
    def __init__(self, state_dir):
        self.state_dir = state_dir
        self._stopped = threading.Event()
        self._thread = None
        self._writer = None
        self._progress = self._baseline = self._last_poll = time.monotonic()
        self._suspect_since = None

    def beat(self):
        # Assignment does not wait for a lock possibly held by a stuck thread.
        self._progress = time.monotonic()

    def start(self):
        if self._thread is not None or self._stopped.is_set():
            raise RuntimeError("heartbeat-already-started-or-stopped")
        self._progress = self._baseline = self._last_poll = time.monotonic()
        worker = threading.Thread(target=self._watch, name="monitor-loop-heartbeat", daemon=True)
        try:
            worker.start()
        except RuntimeError:
            self._stopped.set()
            raise LauncherError("heartbeat-monitor-unavailable", 1) from None
        self._thread = worker

    def stop(self):
        self._stopped.set()
        worker = self._thread
        if worker is not None and worker is not threading.current_thread():
            # In normal operation Event.wait wakes immediately. During a
            # terminal fault, the diagnostic wait is separately bounded.
            worker.join(HEARTBEAT_DIAGNOSTIC_SECONDS + 1.0)

    def _poll(self, now):
        if self._stopped.is_set():
            return False
        gap = now - self._last_poll
        self._last_poll = now
        if gap < 0 or gap > 3 * HEARTBEAT_POLL_SECONDS:
            # Sleep, suspend or a monitor scheduling gap is not proof that
            # the event loop had time to run. Require a fresh full interval.
            self._baseline, self._suspect_since = now, None
            return False
        if now - max(self._progress, self._baseline) < HEARTBEAT_STALL_SECONDS:
            self._suspect_since = None
            return False
        if self._suspect_since is None:
            self._suspect_since = now
            return False
        return now - self._suspect_since >= HEARTBEAT_CONFIRM_SECONDS

    def _record_failure(self):
        try:
            write_heartbeat_failure(self.state_dir)
        except Exception:
            # A disk error must not strand an unresponsive serving process.
            pass

    def _watch(self):
        while not self._stopped.wait(HEARTBEAT_POLL_SECONDS):
            confirmed_progress = self._progress
            if not self._poll(time.monotonic()):
                continue
            if self._progress != confirmed_progress:
                continue
            try:
                if self._writer is None or not self._writer.is_alive():
                    writer = threading.Thread(target=self._record_failure,
                                              name="monitor-heartbeat-diagnostic", daemon=True)
                    writer.start()
                    self._writer = writer
                # At most one diagnostic writer exists even if the loop
                # resumes while a previous diagnostic filesystem call hangs.
                self._writer.join(HEARTBEAT_DIAGNOSTIC_SECONDS)
            except RuntimeError:
                pass
            # Shutdown or actual loop progress wins the last race with the
            # bounded diagnostic attempt. Its record describes an observation,
            # not proof that a process exit occurred.
            if self._stopped.is_set():
                return
            if self._progress != confirmed_progress or not self._poll(time.monotonic()):
                self._suspect_since = None
                continue
            os._exit(HEARTBEAT_EXIT_CODE)
            return


def serve(config: Settings, listener: socket.socket, started):
    sys.path.insert(0, str(PROJECT_ROOT))
    import uvicorn
    from agent_monitor.server import create_app
    # pythonw has no standard streams; prevent request/exception body logging.
    logging.disable(logging.CRITICAL)
    try:
        events = LifecycleEvents(config.state_dir)
        events.record("starting")
    except OSError:
        raise LauncherError("listener-diagnostics-unavailable", 1) from None
    app = create_app(config.state_dir, collect_local=True, public_url=config.public_url,
                     allowed_hosts=["localhost", "127.0.0.1", "[::1]", config.hostname])
    server_config = uvicorn.Config(app, host="127.0.0.1", port=config.port, workers=1,
                                   access_log=False, proxy_headers=False, log_config=None,
                                   use_colors=False, log_level="warning",
                                   timeout_graceful_shutdown=30)

    class ObservedServer(uvicorn.Server):
        def __init__(self, configuration):
            super().__init__(configuration)
            self._listener = listener
            self._active_server = None
            self._recovery_attempts = 0
            self._pending_confirmation = False
            self._terminal_failure = ""
            self._intentional_shutdown = False
            self._observed_loop = None
            self._previous_handler = None
            self._installed_handler = None
            self._heartbeat = EventLoopHeartbeat(config.state_dir)

        def _diagnostics_unavailable(self):
            self._terminal_failure = "listener-diagnostics-unavailable"
            self.should_exit = True
            # A stale running marker is worse than an absent marker. The state
            # lock is held, and this file belongs to this launcher instance.
            try:
                (config.state_dir / STATUS_FILE).unlink(missing_ok=True)
            except OSError:
                pass

        def _status(self, phase, *, reason=""):
            try:
                write_status(config.state_dir, phase, reason=reason)
                return True
            except OSError:
                self._diagnostics_unavailable()
                return False

        def _event(self, event, error=None):
            try:
                events.record(event, attempt=self._recovery_attempts, error=error)
                return True
            except OSError:
                self._diagnostics_unavailable()
                return False

        def _fail(self, reason, event, error=None):
            if self._terminal_failure:
                self.should_exit = True
                return
            self._terminal_failure = reason
            self.should_exit = True
            if self._event(event, error):
                self._status("failed", reason=reason)

        def _mark_running(self, event):
            if self._terminal_failure or not self._event(event):
                return
            try:
                started()
            except OSError:
                self._diagnostics_unavailable()

        def _loop_error(self, loop, context):
            # Never forward arbitrary contexts: they can contain request bodies,
            # URLs, paths, account names, exception messages and tracebacks.
            if self._intentional_shutdown or self._terminal_failure:
                return
            candidate = context.get("socket")
            try:
                bound_listener = (candidate is not None and self._listener.fileno() >= 0
                                  and candidate.fileno() == self._listener.fileno()
                                  and candidate.getsockname() == ("127.0.0.1", config.port))
            except (OSError, AttributeError):
                bound_listener = False
            if context.get("message") == "Accept failed on a socket" and bound_listener:
                # Proactor calls us BEFORE closing the socket. Let its callback
                # finish, then recover on a later tick after checking the socket.
                self._event("accept-failed", context.get("exception"))

        def _close_owned(self):
            self._intentional_shutdown = True
            self._heartbeat.stop()
            for owned_server in getattr(self, "servers", ()):
                try:
                    owned_server.close()
                except Exception:
                    # Still close every raw socket owned by this launcher.
                    pass
            for owned_socket in (self._listener, listener):
                try:
                    owned_socket.close()
                except OSError:
                    pass
            loop = self._observed_loop
            if loop is not None and loop.get_exception_handler() is self._installed_handler:
                loop.set_exception_handler(self._previous_handler)

        async def serve(self, sockets=None):
            try:
                await super().serve(sockets=sockets)
            except BaseException as error:
                if not self._terminal_failure and not self._intentional_shutdown:
                    self._fail("server-unexpected-exit" if self.started else "server-start-failed",
                               "server-failed" if self.started else "startup-failed", error)
                raise
            finally:
                # This runs while the event loop still exists, before Runner's
                # cancellation/executor shutdown can wait for application work.
                self._close_owned()

        async def startup(self, sockets=None):
            self._observed_loop = asyncio.get_running_loop()
            self._previous_handler = self._observed_loop.get_exception_handler()
            self._installed_handler = self._loop_error
            self._observed_loop.set_exception_handler(self._installed_handler)
            try:
                await super().startup(sockets=sockets)
            except BaseException as error:
                self._fail("server-start-failed", "startup-failed", error)
                raise
            if self.started:
                self._active_server = self.servers[-1] if getattr(self, "servers", None) else None
                if listener_healthy(self._listener, config.port):
                    try:
                        self._heartbeat.start()
                    except LauncherError as error:
                        self._fail(error.reason, "startup-failed")
                        raise
                    self._mark_running("started")
                else:
                    self._fail("server-start-failed", "startup-failed")
            else:
                self._fail("server-start-failed", "startup-failed")

        async def _recover_listener(self):
            if self._recovery_attempts >= LISTENER_RECOVERY_LIMIT:
                self._fail("listener-recovery-exhausted", "recovery-exhausted")
                return
            self._recovery_attempts += 1
            if not self._status("recovering") or not self._event("recovery-started"):
                return
            replacement = None
            try:
                if self._active_server is None:
                    raise RuntimeError("missing-owned-server")
                # Retain old Server objects for Uvicorn's final drain of their
                # existing clients. Closing a listener does not close clients.
                self._active_server.close()
                replacement = recovery_sockets.enter_context(reserved_socket(config.port))

                def create_protocol():
                    return self.config.http_protocol_class(
                        config=self.config, server_state=self.server_state,
                        app_state=self.lifespan.state, _loop=self._observed_loop)

                replacement_server = await asyncio.wait_for(
                    self._observed_loop.create_server(
                        create_protocol, sock=replacement, ssl=self.config.ssl,
                        backlog=self.config.backlog),
                    timeout=LISTENER_RECOVERY_TIMEOUT)
                self.servers.append(replacement_server)
                self._listener = replacement
                self._active_server = replacement_server
                self._pending_confirmation = True
            except asyncio.CancelledError:
                if replacement is not None:
                    replacement.close()
                self._fail("listener-recovery-failed", "recovery-failed")
                raise
            except Exception as error:
                if replacement is not None:
                    replacement.close()
                self._fail("listener-recovery-failed", "recovery-failed", error)

        async def on_tick(self, counter):
            self._heartbeat.beat()
            # Normal shutdown always wins, including while a rebind awaits its
            # independent confirmation. Do not recover deliberate socket closure.
            if await super().on_tick(counter):
                return True
            if self._terminal_failure or self._intentional_shutdown:
                return True
            healthy = listener_healthy(self._listener, config.port)
            if self._pending_confirmation:
                self._pending_confirmation = False
                if healthy:
                    self._mark_running("recovery-restored")
                else:
                    self._fail("listener-recovery-failed", "recovery-failed")
            elif not healthy:
                if self._event("listener-missing"):
                    await self._recover_listener()
            return bool(self.should_exit)

        async def shutdown(self, sockets=None):
            self._intentional_shutdown = True
            self._heartbeat.stop()
            # Record the transition before Uvicorn closes its listening socket.
            # A stale "running" record must not stand in for shutdown progress.
            if not self._terminal_failure:
                if self._event("stopping"):
                    self._status("stopping")
            # The configured deadline bounds HTTP connection/task draining only;
            # Uvicorn's lifespan and Python worker-thread exit remain separate.
            await super().shutdown(sockets=sockets)
            if not self._terminal_failure:
                self._event("stopped")

    with ExitStack() as recovery_sockets:
        server = ObservedServer(server_config)
        try:
            try:
                server.run(sockets=[listener])
            except BaseException:
                if server._terminal_failure:
                    raise LauncherError(server._terminal_failure, 1) from None
                raise
        finally:
            # Uvicorn does not call shutdown if startup/main_loop raises. Own
            # socket cleanup still occurs, and Runner cancels lifespan tasks.
            server._close_owned()
        if server._terminal_failure:
            raise LauncherError(server._terminal_failure, 1)
    if not server.started:
        raise LauncherError("server-start-failed", 1)


def run(config: Settings, server=serve) -> int:
    try:
        with PublicLock(config.state_dir):
            try:
                write_status(config.state_dir, "starting")
                with reserved_socket(config.port) as listener:
                    server(config, listener, lambda: write_status(config.state_dir, "running"))
                write_status(config.state_dir, "stopped")
                return 0
            except BaseException as error:
                code = error.exit_code if isinstance(error, LauncherError) else (
                    130 if isinstance(error, KeyboardInterrupt) else 1)
                reason = error.reason if isinstance(error, LauncherError) else "unexpected-exit"
                try:
                    write_status(config.state_dir, "failed", reason=reason, error=error)
                except OSError:
                    pass
                return code
    except LauncherError as error:
        # Do not overwrite the running instance's status when its lock is held.
        return error.exit_code


def main(argv=None) -> int:
    # No console is allocated or attached, even temporarily. Libraries that
    # expect stream objects receive null streams instead of pythonw's None.
    previous = sys.stdout, sys.stderr
    with open(os.devnull, "w", encoding="utf-8") as sink:
        sys.stdout = sys.stderr = sink
        try:
            parser = argparse.ArgumentParser(description="Agent Monitor windowless public launcher")
            parser.add_argument("--public-url", required=True)
            parser.add_argument("--state-dir", default=None)
            parser.add_argument("--port", type=int, default=8766)
            args = parser.parse_args(argv)
            config = settings(args.public_url, args.state_dir, args.port)
            windows_runtime()
            return run(config)
        except LauncherError as error:
            return error.exit_code
        except SystemExit as error:
            return error.code if isinstance(error.code, int) else 2
        except Exception:
            return 1
        finally:
            sys.stdout, sys.stderr = previous


if __name__ == "__main__":
    raise SystemExit(main())
