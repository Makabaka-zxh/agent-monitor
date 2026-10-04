"""Anonymous timings use synthetic data and temporary files, never a live hub."""
import asyncio
import json
import logging
from pathlib import Path
import threading
import time

from fastapi.testclient import TestClient
import pytest

from agent_monitor import request_diagnostics as diag
from agent_monitor.server import create_app


TRACE = "94a70ba2-17e0-47ad-8d17-7f6c5c142509"
PRIVATE = "synthetic-private-sentinel"


def scope(**changes):
    result = {"type": "http", "method": "GET", "path": "/api/native/workbench",
              "headers": [(b"x-monitor-trace", TRACE.encode()), (b"authorization", PRIVATE.encode())],
              "query_string": PRIVATE.encode(), "client": (PRIVATE, 1234)}
    result.update(changes)
    return result


class Capture:
    def __init__(self):
        self.records = []

    def emit(self, *args):
        self.records.append(args)


def exercise(app, *, request_scope=None, sink=None, reject_send=None):
    sink = sink or Capture()
    sent = []

    async def receive():
        return {"type": "http.request", "body": PRIVATE.encode()}

    async def send(message):
        if reject_send == message["type"]:
            raise OSError(PRIVATE)
        sent.append(message)

    asyncio.run(diag.RequestTimingMiddleware(app, sink)(request_scope or scope(), receive, send))
    return sink, sent


async def ok(scope, receive, send):
    await send({"type": "http.response.start", "status": 200,
                "headers": [(b"set-cookie", PRIVATE.encode())]})
    await send({"type": "http.response.body", "body": PRIVATE.encode(), "more_body": True})
    await send({"type": "http.response.body", "body": b""})


def records(path):
    return [json.loads(line) for line in path.read_text(encoding="ascii").splitlines()]


def test_normal_response_is_unchanged_and_records_only_fixed_schema():
    sink, sent = exercise(ok)
    assert [item[2] for item in sink.records] == ["arrival", "response_start", "end"]
    assert [item[3] for item in sink.records] == [0, 200, 200]
    assert [item[5] for item in sink.records] == [False, False, True]
    assert all(item[0] == "workbench" and item[1] == TRACE and item[4] >= 0 for item in sink.records)
    assert [item[4] for item in sink.records] == sorted(item[4] for item in sink.records)
    assert sent[0]["headers"] == [(b"set-cookie", PRIVATE.encode())]
    assert sent[1]["body"] == PRIVATE.encode() and sent[2]["body"] == b""
    assert PRIVATE not in repr(sink.records)


@pytest.mark.parametrize("route,label", diag.ROUTES.items())
def test_only_three_fixed_route_labels(route, label):
    sink, _ = exercise(ok, request_scope=scope(path=route))
    assert all(item[0] == label for item in sink.records)


@pytest.mark.parametrize("changes", [
    {"method": "POST"}, {"type": "websocket"}, {"path": "/api/health"},
    {"path": "/api/native/workbench/"}, {"path": "/api/native/tasks/result"},
    {"path": "/api/native/account"}, {"path": "/api/native/workbench-secret"},
    {"headers": []}, {"headers": [(b"x-monitor-trace", b"")]},
    {"headers": [(b"x-monitor-trace", TRACE.upper().encode())]},
    {"headers": [(b"x-monitor-trace", TRACE.replace("47ad", "17ad").encode())]},
    {"headers": [(b"x-monitor-trace", TRACE.replace("8d17", "7d17").encode())]},
    {"headers": [(b"x-monitor-trace", (TRACE + "\n").encode())]},
    {"headers": [(b"x-monitor-trace", b"x" * 8192)]},
    {"headers": [(b"x-monitor-trace", b"\xff" * 36)]},
    {"headers": [(b"x-monitor-trace", TRACE.encode()), (b"x-monitor-trace", TRACE.encode())]},
])
def test_ineligible_requests_collect_nothing(changes):
    sink, sent = exercise(ok, request_scope=scope(**changes))
    assert not sink.records and len(sent) == 3


@pytest.mark.parametrize("send_headers", [False, True])
def test_app_exception_propagates_without_recording_exception_text(send_headers):
    sink = Capture()

    async def broken(scope, receive, send):
        if send_headers:
            await send({"type": "http.response.start", "status": 200})
        raise RuntimeError(PRIVATE)

    with pytest.raises(RuntimeError, match=PRIVATE):
        exercise(broken, sink=sink)
    assert sink.records[-1][2] == "end" and sink.records[-1][5] is False
    assert sink.records[-1][3] == (200 if send_headers else 0)
    assert PRIVATE not in repr(sink.records)


def test_send_failure_does_not_claim_completed_response():
    sink = Capture()
    with pytest.raises(OSError, match=PRIVATE):
        exercise(ok, sink=sink, reject_send="http.response.body")
    assert sink.records[-1][2] == "end" and sink.records[-1][5] is False


def test_response_completion_precedes_background_work_and_never_duplicates_end():
    sink = Capture()

    async def background(scope, receive, send):
        await ok(scope, receive, send)
        assert sink.records[-1][2] == "end" and sink.records[-1][5] is True
        raise RuntimeError(PRIVATE)

    with pytest.raises(RuntimeError):
        exercise(background, sink=sink)
    assert [item[2] for item in sink.records].count("end") == 1


def test_cancellation_is_not_swallowed_and_missing_final_body_is_incomplete():
    sink = Capture()

    async def cancelled(scope, receive, send):
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        exercise(cancelled, sink=sink)
    assert sink.records[-1][5] is False

    async def missing(scope, receive, send):
        await send({"type": "http.response.start", "status": 200})

    sink, _ = exercise(missing)
    assert sink.records[-1][2] == "end" and sink.records[-1][5] is False


def test_diagnostic_sink_failure_cannot_change_http_response():
    class Broken:
        def emit(self, *args):
            raise OSError(PRIVATE)

    _, sent = exercise(ok, sink=Broken())
    assert len(sent) == 3 and sent[0]["status"] == 200


def test_writer_lifecycle_and_privacy_independent_of_python_logging(tmp_path):
    writer = diag.RequestTimings(tmp_path)
    writer.emit("workbench", TRACE, "arrival", 0, 0)
    assert not writer.path.exists()
    previous = logging.root.manager.disable
    try:
        logging.disable(logging.CRITICAL)
        writer.start()
        exercise(ok, sink=writer)
        writer.close()
    finally:
        logging.disable(previous)
    assert not writer._thread.is_alive()
    entries = records(writer.path)
    assert len(entries) == 3
    assert all(set(item) == {"route", "trace", "phase", "status", "elapsed_ms", "completed"} for item in entries)
    assert PRIVATE not in writer.path.read_text()
    writer.emit("workbench", TRACE, "end", 200, 1, True)
    writer.close()
    assert len(records(writer.path)) == 3


def test_queue_saturation_drops_instead_of_blocking_request(tmp_path, monkeypatch):
    writer = diag.RequestTimings(tmp_path)
    entered, release = threading.Event(), threading.Event()

    def slow_write(record):
        entered.set()
        assert release.wait(5)

    monkeypatch.setattr(writer, "_write", slow_write)
    writer.start()
    try:
        writer.emit("workbench", TRACE, "arrival", 0, 0)
        assert entered.wait(1)
        for _ in range(diag.QUEUE_RECORDS + 30):
            writer.emit("workbench", TRACE, "arrival", 0, 0)
        assert writer._queue.qsize() == diag.QUEUE_RECORDS
        started = time.monotonic()
        _, sent = exercise(ok, sink=writer)
        assert time.monotonic() - started < 0.5 and len(sent) == 3
        assert writer._queue.qsize() == diag.QUEUE_RECORDS
    finally:
        release.set()
        writer.close()
    assert not writer._thread.is_alive()


def test_io_failure_is_isolated_and_writer_can_recover(tmp_path, monkeypatch):
    writer = diag.RequestTimings(tmp_path)
    write = writer._write
    attempts = []

    def fail_then_write(record):
        attempts.append(record["phase"])
        if len(attempts) == 1:
            raise OSError(PRIVATE)
        write(record)

    monkeypatch.setattr(writer, "_write", fail_then_write)
    writer.start()
    exercise(ok, sink=writer)
    writer.close()
    assert attempts == ["arrival", "response_start", "end"]
    assert len(records(writer.path)) == 2 and PRIVATE not in writer.path.read_text()


def test_rotation_stays_within_two_files_and_one_mib(tmp_path, monkeypatch):
    # Small per-file budget forces several rotations quickly without huge fixtures.
    monkeypatch.setattr(diag, "FILE_BYTES", 700)
    writer = diag.RequestTimings(tmp_path)
    record = {"route": "workbench", "trace": TRACE, "phase": "arrival", "status": 0,
              "elapsed_ms": 0, "completed": False}
    for _ in range(25):
        writer._write(record)
    assert {path.name for path in tmp_path.iterdir()} == {diag.FILE_NAME, diag.FILE_NAME + ".1"}
    assert all(path.stat().st_size <= 700 for path in tmp_path.iterdir())
    assert sum(path.stat().st_size for path in tmp_path.iterdir()) <= 1400
    assert records(writer.path) and records(writer.backup)
    writer.backup.write_bytes(b"x" * 701)
    writer._write(record)
    assert sum(path.stat().st_size for path in tmp_path.iterdir()) <= 1400


@pytest.mark.parametrize("args", [
    ("private-route", TRACE, "arrival", 0, 0),
    ("workbench", PRIVATE, "arrival", 0, 0),
    ("workbench", TRACE, PRIVATE, 0, 0),
    ("workbench", TRACE, "arrival", PRIVATE, 0),
    ("workbench", TRACE, "arrival", 0, PRIVATE),
    ("workbench", TRACE, "arrival", 0, -1),
    ("workbench", TRACE, "arrival", 0, 2**64),
])
def test_sink_revalidates_schema(args, tmp_path):
    writer = diag.RequestTimings(tmp_path)
    writer.start()
    writer.emit(*args)
    writer.close()
    assert not writer.path.exists()


def test_off_switch_and_disabled_lifecycle_create_no_files(tmp_path, monkeypatch):
    monkeypatch.delenv("MONITOR_REQUEST_DIAGNOSTICS", raising=False)
    assert diag.configured(None) is True
    for value in ("0", "false", "unexpected"):
        monkeypatch.setenv("MONITOR_REQUEST_DIAGNOSTICS", value)
        assert diag.configured(None) is False
    assert diag.configured(True) is True and diag.configured(False) is False
    writer = diag.RequestTimings(tmp_path, enabled=False)
    writer.start()
    exercise(ok, sink=writer)
    writer.close()
    assert writer._thread is None and not list(tmp_path.iterdir())


def test_real_hub_lifespan_records_auth_rejection_without_credentials(tmp_path):
    app = create_app(tmp_path, collect_local=False)
    writer = app.state.request_timings
    assert writer._thread is None
    with TestClient(app) as client:
        response = client.get("/api/native/workbench", headers={"X-Monitor-Trace": TRACE,
                                                               "Authorization": PRIVATE})
        assert response.status_code == 401
        assert writer._thread.is_alive()
        assert client.get("/api/health", headers={"X-Monitor-Trace": TRACE}).status_code == 200
    assert not writer._thread.is_alive()
    entries = records(writer.path)
    assert len(entries) == 3 and entries[-1]["status"] == 401 and entries[-1]["completed"] is True
    assert PRIVATE not in writer.path.read_text()


def test_explicit_app_off_switch(tmp_path):
    app = create_app(tmp_path, collect_local=False, request_diagnostics=False)
    with TestClient(app) as client:
        assert client.get("/api/native/workbench", headers={"X-Monitor-Trace": TRACE}).status_code == 401
    assert app.state.request_timings._thread is None
    assert not (tmp_path / diag.FILE_NAME).exists()
