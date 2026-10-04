"""One-process Windows GUI launcher for the existing personal hub.

Task Scheduler should execute the existing virtual environment's pythonw.exe
directly. No subprocess, console, watchdog, registration, or database creation.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
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
import sys
from urllib.parse import urlsplit, urlunsplit

PROJECT_ROOT = Path(__file__).resolve().parents[1]
STATUS_FILE = "launcher-status.json"
LOCK_FILE = ".public-run.lock"


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


def serve(config: Settings, listener: socket.socket, started):
    sys.path.insert(0, str(PROJECT_ROOT))
    import uvicorn
    from agent_monitor.server import create_app
    # pythonw has no standard streams; prevent request/exception body logging.
    logging.disable(logging.CRITICAL)
    app = create_app(config.state_dir, collect_local=True, public_url=config.public_url,
                     allowed_hosts=["localhost", "127.0.0.1", "[::1]", config.hostname])
    server_config = uvicorn.Config(app, host="127.0.0.1", port=config.port, workers=1,
                                   access_log=False, proxy_headers=False, log_config=None,
                                   use_colors=False, log_level="warning",
                                   timeout_graceful_shutdown=30)

    class ObservedServer(uvicorn.Server):
        async def startup(self, sockets=None):
            await super().startup(sockets=sockets)
            if self.started:
                started()

        async def shutdown(self, sockets=None):
            # Record the transition before Uvicorn closes its listening socket.
            # A stale "running" record must not stand in for shutdown progress.
            try:
                write_status(config.state_dir, "stopping")
            except OSError:
                # Failure to update diagnostics must not prevent shutdown.
                pass
            # The configured deadline bounds HTTP connection/task draining only;
            # Uvicorn's lifespan and Python worker-thread exit remain separate.
            await super().shutdown(sockets=sockets)

    server = ObservedServer(server_config)
    server.run(sockets=[listener])
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
