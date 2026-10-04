"""Bounded, anonymous native request timings; never an HTTP access log.

Only three exact GET routes with a valid UUID4 trace header are eligible. Clients
must generate a new random trace per request, never derive it from identity; the
server validates its format but cannot prove a caller's freshness or provenance.
No request/response objects or exception text leave the middleware. The writer
is independent of logging/stdout, which the windowless launcher disables.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from queue import Empty, Queue
import re
import threading
import time


ROUTES = {
    "/api/native/workbench": "workbench",
    "/api/native/snapshot": "live_snapshot",
    "/api/native/usage": "usage",
}
TRACE_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
FILE_BYTES = 512 * 1024
QUEUE_RECORDS = 256
FILE_NAME = "request-timing.jsonl"


def configured(value: bool | None) -> bool:
    """Opt out without changing launcher arguments; unknown values fail closed."""
    if value is not None:
        return value is True
    return os.environ.get("MONITOR_REQUEST_DIAGNOSTICS", "1") == "1"


def eligible(scope):
    if scope.get("type") != "http" or scope.get("method") != "GET":
        return None
    route = ROUTES.get(scope.get("path"))
    if route is None:
        return None
    values = [value for name, value in scope.get("headers", ()) if name.lower() == b"x-monitor-trace"]
    if len(values) != 1 or len(values[0]) != 36:
        return None
    try:
        trace = values[0].decode("ascii")
    except (UnicodeError, AttributeError):
        return None
    return (route, trace) if TRACE_PATTERN.fullmatch(trace) else None


class RequestTimings:
    """Drop instead of blocking requests; at most 256 small records + 1 MiB disk.

    Files are opened only by the daemon worker. Each write closes its handle, so
    shutdown does not leave a file open. Shutdown drains healthy writes, with a
    bounded join if the operating system itself stalls a filesystem operation.
    No network or retry loop is used by this diagnostic sink.
    Queue saturation and I/O failure can drop records: an absent arrival is not
    proof that the request never reached the server.
    """

    def __init__(self, state_dir: Path, *, enabled=True):
        self.enabled = enabled
        self.path = Path(state_dir) / FILE_NAME
        self.backup = self.path.with_name(FILE_NAME + ".1")
        self._queue = Queue(maxsize=QUEUE_RECORDS)
        self._accepting = threading.Event()
        self._stopping = threading.Event()
        self._thread = None

    def start(self):
        if not self.enabled or self._thread is not None:
            return
        try:
            self._thread = threading.Thread(target=self._run, name="MonitorRequestTimings", daemon=True)
            self._accepting.set()
            self._thread.start()
        except Exception:
            self._accepting.clear()
            self._thread = None

    def emit(self, route, trace, phase, status, elapsed_ms, completed=False):
        if not self._accepting.is_set():
            return
        # Rebuild only the fixed schema; no dictionary/exception can be logged.
        try:
            if (route not in ROUTES.values() or not isinstance(trace, str)
                    or not TRACE_PATTERN.fullmatch(trace)
                    or phase not in {"arrival", "response_start", "end"}
                    or type(status) is not int or status != 0 and not 100 <= status <= 599
                    or type(elapsed_ms) is not int or not 0 <= elapsed_ms <= 2**63 - 1
                    or type(completed) is not bool):
                return
            record = {"route": route, "trace": trace, "phase": phase, "status": status,
                      "elapsed_ms": elapsed_ms, "completed": completed}
            self._queue.put_nowait(record)
        except Exception:
            # Diagnostic failure (including a full queue) must never fail a request.
            return

    def close(self):
        self._accepting.clear()
        self._stopping.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)

    def _run(self):
        while not self._stopping.is_set() or not self._queue.empty():
            try:
                record = self._queue.get(timeout=0.05)
            except Empty:
                continue
            try:
                self._write(record)
            except Exception:
                pass
            finally:
                self._queue.task_done()

    def _write(self, record):
        encoded = (json.dumps(record, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")
        if len(encoded) > 1024:
            return
        # Dedicated files only. Refuse links, and trim an oversized old diagnostic
        # file rather than preserving it as an unbounded rotation backup.
        for path in (self.path, self.backup):
            if path.is_symlink():
                return
            if path.exists() and path.stat().st_size > FILE_BYTES:
                with path.open("wb"):
                    pass
        if self.path.exists() and self.path.stat().st_size + len(encoded) > FILE_BYTES:
            os.replace(self.path, self.backup)
        descriptor = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_BINARY", 0), 0o600)
        with os.fdopen(descriptor, "ab") as output:
            output.write(encoded)


class RequestTimingMiddleware:
    """Observe ASGI send completion without buffering or changing HTTP messages.

    completed=True means the final body send returned, not that the client has
    received or decoded it. Post-response background work is outside this timing.
    """

    def __init__(self, app, sink: RequestTimings):
        self.app = app
        self.sink = sink

    async def __call__(self, scope, receive, send):
        try:
            identity = eligible(scope)
        except Exception:
            identity = None
        if identity is None:
            return await self.app(scope, receive, send)
        route, trace = identity
        started = time.monotonic_ns()
        status, completed, ended = 0, False, False

        def record(phase):
            try:
                self.sink.emit(route, trace, phase, status,
                               max(0, (time.monotonic_ns() - started) // 1_000_000), completed)
            except Exception:
                pass

        async def observed_send(message):
            nonlocal status, completed, ended
            await send(message)
            if message["type"] == "http.response.start":
                candidate = message.get("status", 0)
                status = candidate if type(candidate) is int and 100 <= candidate <= 599 else 0
                record("response_start")
            elif message["type"] == "http.response.body" and not message.get("more_body", False) and not ended:
                completed = True
                ended = True
                record("end")

        record("arrival")
        try:
            return await self.app(scope, receive, observed_send)
        finally:
            if not ended:
                record("end")
