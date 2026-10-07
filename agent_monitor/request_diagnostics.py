"""Bounded, anonymous native request timings; never an HTTP access log.

Only three exact GET routes with a valid UUID4 trace header are eligible. Clients
must generate a new random trace per request, never derive it from identity; the
server validates its format but cannot prove a caller's freshness or provenance.
No request/response objects or exception text leave the middleware. The writer
is independent of logging/stdout, which the windowless launcher disables.
"""
from __future__ import annotations

from contextvars import ContextVar
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
STAGE_PHASES = frozenset({
    "handler_start", "store_lock_wait", "store_lock_acquired",
    "transaction_start", "transaction_acquired", "transaction_end",
    "snapshot_start", "snapshot_end", "usage_start", "usage_end", "handler_end",
})
RESPONSE_PHASES = frozenset({"arrival", "response_ready", "response_start", "end"})
PHASES = STAGE_PHASES | RESPONSE_PHASES
MAX_CLOCK = 2**63 - 1
_current_timing: ContextVar[_RequestTiming | None] = ContextVar("monitor_request_timing", default=None)


def mark_request_phase(phase: str) -> None:
    """Mark one fixed stage in the current eligible request, without payloads.

    AnyIO worker threads inherit the request context. Each phase is accepted at
    most once; copied contexts become inert when the response/request ends.
    No active request, an unknown phase, or diagnostic failure is a no-op.
    """
    try:
        if type(phase) is not str or phase not in STAGE_PHASES:
            return
        timing = _current_timing.get()
        if timing is not None:
            timing.record(phase)
    except Exception:
        pass


class _RequestTiming:
    """Only a sink, validated labels and clocks; never retains an HTTP object."""

    def __init__(self, sink, route, trace):
        self.sink, self.route, self.trace = sink, route, trace
        self.started = time.monotonic_ns()
        self._lock = threading.Lock()
        self._closed = False
        self._seen = set()

    def record(self, phase, status=0, completed=False):
        try:
            # Keep the critical section bounded; disk I/O only occurs in the
            # sink's independent writer. Do not wait on concurrent diagnostics.
            if not self._lock.acquire(blocking=False):
                return
            try:
                if self._closed or phase in self._seen:
                    return
                self._seen.add(phase)
                monotonic_ns = time.monotonic_ns()
                wall_clock_ms = time.time_ns() // 1_000_000
            finally:
                self._lock.release()
            self.sink.emit(self.route, self.trace, phase, status,
                           max(0, (monotonic_ns - self.started) // 1_000_000), completed,
                           wall_clock_ms, monotonic_ns)
        except Exception:
            pass

    def close(self):
        # Copies held by a child task or worker share this lifecycle flag.
        self._closed = True


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

    def emit(self, route, trace, phase, status, elapsed_ms, completed=False,
             wall_clock_ms=None, monotonic_ns=None):
        if not self._accepting.is_set():
            return
        # Rebuild only the fixed schema; no dictionary/exception can be logged.
        try:
            if wall_clock_ms is None:
                wall_clock_ms = time.time_ns() // 1_000_000
            if monotonic_ns is None:
                monotonic_ns = time.monotonic_ns()
            if (type(route) is not str or route not in ROUTES.values() or type(trace) is not str
                    or not TRACE_PATTERN.fullmatch(trace)
                    or type(phase) is not str or phase not in PHASES
                    or type(status) is not int or status != 0 and not 100 <= status <= 599
                    or type(elapsed_ms) is not int or not 0 <= elapsed_ms <= MAX_CLOCK
                    or type(wall_clock_ms) is not int or not 0 <= wall_clock_ms <= MAX_CLOCK
                    or type(monotonic_ns) is not int or not 0 <= monotonic_ns <= MAX_CLOCK
                    or type(completed) is not bool):
                return
            record = {"route": route, "trace": trace, "phase": phase, "status": status,
                      "elapsed_ms": elapsed_ms, "completed": completed,
                      "wall_clock_ms": wall_clock_ms, "monotonic_ns": monotonic_ns}
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
    response_ready precedes ASGI header send; handler_end to response_ready also
    includes framework scheduling/serialization, not just JSON encoding.
    Wall clocks allow approximate cross-device alignment but may jump or skew;
    elapsed_ms and ordering use the server's own monotonic clock only.
    """

    def __init__(self, app, sink: RequestTimings):
        self.app = app
        self.sink = sink

    async def __call__(self, scope, receive, send):
        try:
            identity = eligible(scope)
        except Exception:
            identity = None
        # Mask any outer/copied request even for ineligible nested dispatches.
        try:
            timing = _RequestTiming(self.sink, *identity) if identity is not None else None
        except Exception:
            timing = None
        context = _current_timing.set(timing)
        if timing is None:
            try:
                return await self.app(scope, receive, send)
            finally:
                _current_timing.reset(context)
        status, completed, ended = 0, False, False

        def record(phase):
            timing.record(phase, status, completed)

        async def observed_send(message):
            nonlocal status, completed, ended
            if message["type"] == "http.response.start":
                candidate = message.get("status", 0)
                candidate = candidate if type(candidate) is int and 100 <= candidate <= 599 else 0
                timing.record("response_ready", candidate)
            await send(message)
            if message["type"] == "http.response.start":
                status = candidate
                record("response_start")
            elif message["type"] == "http.response.body" and not message.get("more_body", False) and not ended:
                completed = True
                ended = True
                record("end")
                timing.close()

        record("arrival")
        try:
            return await self.app(scope, receive, observed_send)
        finally:
            if not ended:
                record("end")
            timing.close()
            _current_timing.reset(context)
