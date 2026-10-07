"""Anonymous timings use synthetic data and temporary files, never a live hub."""
import asyncio
from contextvars import copy_context
from itertools import count
import json
import logging
from pathlib import Path
import threading
import time

from fastapi.testclient import TestClient
import pytest
from starlette.concurrency import run_in_threadpool

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
    assert [item[2] for item in sink.records] == ["arrival", "response_ready", "response_start", "end"]
    assert [item[3] for item in sink.records] == [0, 200, 200, 200]
    assert [item[5] for item in sink.records] == [False, False, False, True]
    assert all(item[0] == "workbench" and item[1] == TRACE and item[4] >= 0 for item in sink.records)
    assert [item[4] for item in sink.records] == sorted(item[4] for item in sink.records)
    assert all(type(item[6]) is int and type(item[7]) is int for item in sink.records)
    assert all(item[6] > 0 and item[7] > 0 for item in sink.records)
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
    assert len(entries) == 4
    assert all(set(item) == {"route", "trace", "phase", "status", "elapsed_ms", "completed",
                             "wall_clock_ms", "monotonic_ns"} for item in entries)
    assert PRIVATE not in writer.path.read_text()
    writer.emit("workbench", TRACE, "end", 200, 1, True)
    writer.close()
    assert len(records(writer.path)) == 4


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
    assert attempts == ["arrival", "response_ready", "response_start", "end"]
    assert len(records(writer.path)) == 3 and PRIVATE not in writer.path.read_text()


def test_rotation_stays_within_two_files_and_one_mib(tmp_path, monkeypatch):
    # Small per-file budget forces several rotations quickly without huge fixtures.
    monkeypatch.setattr(diag, "FILE_BYTES", 700)
    writer = diag.RequestTimings(tmp_path)
    record = {"route": "workbench", "trace": TRACE, "phase": "arrival", "status": 0,
              "elapsed_ms": 0, "completed": False, "wall_clock_ms": 1, "monotonic_ns": 1}
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
    assert [item["phase"] for item in entries if item["phase"] in diag.RESPONSE_PHASES] == [
        "arrival", "response_ready", "response_start", "end"]
    assert entries[-1]["status"] == 401 and entries[-1]["completed"] is True
    assert PRIVATE not in writer.path.read_text()


def test_explicit_app_off_switch(tmp_path):
    app = create_app(tmp_path, collect_local=False, request_diagnostics=False)
    with TestClient(app) as client:
        assert client.get("/api/native/workbench", headers={"X-Monitor-Trace": TRACE}).status_code == 401
    assert app.state.request_timings._thread is None
    assert not (tmp_path / diag.FILE_NAME).exists()


@pytest.mark.parametrize("phase", [PRIVATE, "/private/path", "arrival", "response_ready", "end",
                                    None, True, 123, [], {"phase": "handler_start"}])
def test_stage_api_rejects_arbitrary_or_reserved_phases_without_payloads(phase):
    async def marked(scope, receive, send):
        diag.mark_request_phase(phase)
        await ok(scope, receive, send)

    sink, _ = exercise(marked)
    assert [item[2] for item in sink.records] == ["arrival", "response_ready", "response_start", "end"]
    assert PRIVATE not in repr(sink.records)


def test_stage_marks_are_bounded_once_per_phase_and_noop_outside_request():
    diag.mark_request_phase("handler_start")

    async def marked(scope, receive, send):
        for _ in range(1000):
            diag.mark_request_phase("handler_start")
        for phase in sorted(diag.STAGE_PHASES - {"handler_start"}):
            diag.mark_request_phase(phase)
        await ok(scope, receive, send)

    sink, _ = exercise(marked)
    before = list(sink.records)
    diag.mark_request_phase("usage_start")
    assert sink.records == before
    phases = [item[2] for item in sink.records]
    assert len(phases) == len(diag.PHASES) and set(phases) == diag.PHASES
    assert len(phases) == len(set(phases))


@pytest.mark.parametrize("key,value", [
    ("wall_clock_ms", PRIVATE), ("monotonic_ns", PRIVATE),
    ("wall_clock_ms", True), ("monotonic_ns", False),
    ("wall_clock_ms", -1), ("monotonic_ns", -1),
    ("wall_clock_ms", 1.5), ("monotonic_ns", 1.5),
    ("wall_clock_ms", 2**63), ("monotonic_ns", 2**63),
])
def test_sink_rejects_non_integer_or_unbounded_clock_fields(tmp_path, key, value):
    writer = diag.RequestTimings(tmp_path)
    writer.start()
    writer.emit("workbench", TRACE, "arrival", 0, 0, **{key: value})
    writer.close()
    assert not writer.path.exists()


def test_wall_clock_rollback_cannot_reverse_monotonic_elapsed(monkeypatch):
    monotonic = count(100_000_000, 1_000_000)
    walls = iter([10_000_000_000, 9_000_000_000, 8_000_000_000, 7_000_000_000])
    monkeypatch.setattr(diag.time, "monotonic_ns", lambda: next(monotonic))
    monkeypatch.setattr(diag.time, "time_ns", lambda: next(walls))
    sink, _ = exercise(ok)
    assert [item[6] for item in sink.records] == [10000, 9000, 8000, 7000]
    assert [item[4] for item in sink.records] == [1, 2, 3, 4]
    assert [item[7] for item in sink.records] == [101_000_000, 102_000_000, 103_000_000, 104_000_000]


def test_response_ready_is_before_send_and_start_only_after_success():
    sink = Capture()
    with pytest.raises(OSError, match=PRIVATE):
        exercise(ok, sink=sink, reject_send="http.response.start")
    assert [item[2] for item in sink.records] == ["arrival", "response_ready", "end"]
    assert sink.records[1][3] == 200
    assert sink.records[-1][3] == 0 and sink.records[-1][5] is False


def test_anyio_worker_context_propagates_and_concurrent_requests_stay_separate():
    second_trace = "04a70ba2-17e0-47ad-8d17-7f6c5c142509"
    barrier = threading.Barrier(2)
    sink = Capture()

    def worker(phases):
        barrier.wait(timeout=3)
        for phase in phases:
            diag.mark_request_phase(phase)

    async def app(request_scope, receive, send):
        diag.mark_request_phase("handler_start")
        phases = ("snapshot_start", "snapshot_end") if request_scope["headers"][0][1] == TRACE.encode() else ("usage_start", "usage_end")
        await run_in_threadpool(worker, phases)
        diag.mark_request_phase("handler_end")
        await ok(request_scope, receive, send)

    async def run():
        async def receive():
            return {"type": "http.request", "body": b""}

        async def send(message):
            pass

        middleware = diag.RequestTimingMiddleware(app, sink)
        await asyncio.gather(
            middleware(scope(), receive, send),
            middleware(scope(headers=[(b"x-monitor-trace", second_trace.encode())]), receive, send))

    asyncio.run(run())
    grouped = {trace: [record for record in sink.records if record[1] == trace] for trace in (TRACE, second_trace)}
    for trace, phases in ((TRACE, ["snapshot_start", "snapshot_end"]),
                          (second_trace, ["usage_start", "usage_end"])):
        rows = grouped[trace]
        assert [row[2] for row in rows] == ["arrival", "handler_start", *phases, "handler_end",
                                          "response_ready", "response_start", "end"]
        assert [row[4] for row in rows] == sorted(row[4] for row in rows)


def test_copied_context_cannot_emit_after_response_or_failed_request():
    copied = []

    async def app(scope, receive, send):
        copied.append(copy_context())
        await ok(scope, receive, send)
        # Background tasks after a completed response are deliberately excluded.
        diag.mark_request_phase("usage_start")

    sink, _ = exercise(app)
    before = list(sink.records)
    copied[0].run(diag.mark_request_phase, "snapshot_start")
    assert sink.records == before
    assert not any(row[2] in diag.STAGE_PHASES for row in sink.records)

    async def broken(scope, receive, send):
        copied.append(copy_context())
        raise RuntimeError(PRIVATE)

    failed = Capture()
    with pytest.raises(RuntimeError, match=PRIVATE):
        exercise(broken, sink=failed)
    before = list(failed.records)
    copied[1].run(diag.mark_request_phase, "handler_start")
    diag.mark_request_phase("handler_start")
    assert failed.records == before


def test_ineligible_nested_request_masks_and_restores_eligible_context():
    async def inner(scope, receive, send):
        diag.mark_request_phase("usage_start")
        await ok(scope, receive, send)

    async def app(request_scope, receive, send):
        diag.mark_request_phase("handler_start")
        async def inner_send(message):
            pass
        await diag.RequestTimingMiddleware(inner, Capture())(scope(path="/private/path"), receive, inner_send)
        diag.mark_request_phase("handler_end")
        await ok(request_scope, receive, send)

    sink, _ = exercise(app)
    assert [row[2] for row in sink.records if row[2] in diag.STAGE_PHASES] == ["handler_start", "handler_end"]


def test_real_authenticated_workbench_propagates_stages_through_middleware_and_worker(tmp_path):
    from agent_monitor.native_access import challenge_for

    origin = "https://diagnostics.example.test"
    account = {"username": "diagnostics-owner", "password": "Synthetic-diagnostics-pass-123"}
    verifier = "A" * 43
    app = create_app(tmp_path, public_url=origin, allowed_hosts=["diagnostics.example.test"])
    app.state.store.setup(**account)
    with TestClient(app, base_url=origin) as client:
        login = client.post("/api/login", json=account)
        assert login.status_code == 200
        headers = {"Origin": origin, "X-CSRF-Token": login.json()["csrf_token"]}
        pair = client.post("/api/native/pairing/start", json={"device_name": PRIVATE,
            "code_challenge": challenge_for(verifier), "mode": "full_app"})
        assert pair.status_code == 200
        pair_path = "/api/native/pairing/" + pair.json()["request_id"]
        assert client.post(pair_path + "/approve", headers=headers,
            json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
        claimed = client.post(pair_path + "/poll", json={"code_verifier": verifier})
        assert claimed.status_code == 200
        reader = claimed.json()["reader_token"]
        response = client.get("/api/native/workbench", headers={"X-Monitor-Trace": TRACE,
                                                               "Authorization": "Bearer " + reader})
        assert response.status_code == 200 and response.json()["mode"] == "full_app"
    entries = records(app.state.request_timings.path)
    assert [row["phase"] for row in entries] == [
        "arrival", "handler_start", "store_lock_wait", "store_lock_acquired", "transaction_start",
        "transaction_acquired", "snapshot_start", "snapshot_end", "usage_start", "usage_end",
        "transaction_end", "handler_end", "response_ready", "response_start", "end"]
    assert all(row["trace"] == TRACE and row["route"] == "workbench" for row in entries)
    text = app.state.request_timings.path.read_text()
    assert PRIVATE not in text and reader not in text and verifier not in text
    assert origin not in text and account["username"] not in text and account["password"] not in text
