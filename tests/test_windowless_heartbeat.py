"""Anonymous isolated loop-stall tests; no real app, logs, config or scheduler."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import types

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "run-public.pyw"
WINDOWS = pytest.mark.skipif(os.name != "nt", reason="Windows exclusive socket and state lock")


def load_launcher():
    loader = importlib.machinery.SourceFileLoader("heartbeat_fixture_launcher", str(SCRIPT))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    loader.exec_module(module)
    return module


def test_default_thresholds_are_conservative_and_not_cli_options():
    launcher = load_launcher()
    assert launcher.HEARTBEAT_STALL_SECONDS == 120.0
    assert launcher.HEARTBEAT_CONFIRM_SECONDS == 30.0
    assert launcher.HEARTBEAT_POLL_SECONDS == 5.0
    assert launcher.HEARTBEAT_EXIT_CODE != 0
    text = SCRIPT.read_text(encoding="utf-8")
    assert 'add_argument("--heartbeat' not in text


def test_stall_requires_timeout_and_continuously_observed_confirmation(tmp_path):
    launcher = load_launcher()
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    watcher._progress = watcher._baseline = watcher._last_poll = 0
    for tick in range(5, 150, 5):
        assert not watcher._poll(tick)
    assert watcher._poll(150)
    watcher._progress = 150.1
    assert not watcher._poll(155)
    assert watcher._suspect_since is None


@pytest.mark.parametrize("gap_time", [1000, -1])
def test_observer_gap_or_clock_reversal_requires_fresh_full_window(tmp_path, gap_time):
    launcher = load_launcher()
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    watcher._progress = watcher._baseline = watcher._last_poll = 0
    for tick in range(5, 145, 5):
        assert not watcher._poll(tick)
    assert not watcher._poll(gap_time)
    for tick in range(5, 150, 5):
        assert not watcher._poll(gap_time + tick)
    assert watcher._poll(gap_time + 155)


def test_stop_disarms_and_joins_monitor_thread(tmp_path, monkeypatch):
    launcher = load_launcher()
    monkeypatch.setattr(launcher, "HEARTBEAT_POLL_SECONDS", 0.01)
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    watcher.start()
    watcher.stop()
    watcher.stop()
    assert not watcher._thread.is_alive()
    assert not watcher._poll(time.monotonic() + 1000)
    with pytest.raises(RuntimeError):
        watcher.start()


def test_monitor_thread_start_failure_is_nonzero_launcher_failure(tmp_path, monkeypatch):
    launcher = load_launcher()

    def fail_start(_):
        raise RuntimeError("synthetic detail excluded")

    monkeypatch.setattr(threading.Thread, "start", fail_start)
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    with pytest.raises(launcher.LauncherError) as caught:
        watcher.start()
    assert caught.value.exit_code == 1
    assert caught.value.reason == "heartbeat-monitor-unavailable"
    watcher.stop()
    assert watcher._thread is None


@pytest.mark.parametrize("race", ["stop", "progress"])
def test_diagnostic_final_race_does_not_exit_resumed_or_stopped_loop(tmp_path, monkeypatch, race):
    launcher = load_launcher()
    monkeypatch.setattr(launcher, "HEARTBEAT_POLL_SECONDS", 0.005)
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    monkeypatch.setattr(watcher, "_poll", lambda _: True)
    exits = []
    monkeypatch.setattr(launcher.os, "_exit", exits.append)
    observed = threading.Event()

    def final_race():
        if race == "stop":
            watcher._stopped.set()
        else:
            watcher.beat()
        observed.set()

    monkeypatch.setattr(watcher, "_record_failure", final_race)
    watcher.start()
    assert observed.wait(1)
    watcher.stop()
    assert exits == [] and not watcher._thread.is_alive()


def test_failure_receipt_contains_only_fixed_anonymous_fields(tmp_path):
    launcher = load_launcher()
    launcher.write_heartbeat_failure(tmp_path)
    data = json.loads((tmp_path / launcher.HEARTBEAT_FAILURE_FILE).read_text(encoding="utf-8"))
    assert set(data) == {"schema", "event", "time", "stall_seconds", "confirmation_seconds", "exit_code"}
    assert data["event"] == "event-loop-stalled"
    assert len(json.dumps(data)) < 1024
    assert not (tmp_path / ".launcher-heartbeat-failure.tmp").exists()


class OneObservation:
    def __init__(self):
        self.waits = 0

    def wait(self, _):
        self.waits += 1
        return self.waits > 1

    def is_set(self):
        return False


def test_progress_just_after_poll_prevents_exit(tmp_path, monkeypatch):
    launcher = load_launcher()
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    watcher._stopped = OneObservation()
    exits = []
    monkeypatch.setattr(launcher.os, "_exit", exits.append)

    def poll_then_resume(_):
        watcher._progress += 1
        return True

    monkeypatch.setattr(watcher, "_poll", poll_then_resume)
    watcher._watch()
    assert exits == [] and watcher._writer is None


def test_observer_gap_during_diagnostic_attempt_prevents_exit(tmp_path, monkeypatch):
    launcher = load_launcher()
    watcher = launcher.EventLoopHeartbeat(tmp_path)
    watcher._stopped = OneObservation()
    watcher._progress = watcher._baseline = 0
    watcher._last_poll = 145
    watcher._suspect_since = 120
    clock = [150]
    exits = []
    monkeypatch.setattr(launcher.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(launcher.os, "_exit", exits.append)
    monkeypatch.setattr(watcher, "_record_failure", lambda: clock.__setitem__(0, 1000))
    watcher._watch()
    assert exits == [] and watcher._baseline == 1000
    assert watcher._suspect_since is None and not watcher._writer.is_alive()


def child(mode, state_dir, port, output):
    """Uses real Uvicorn/Windows loop plus a synthetic in-memory ASGI app."""
    guard = threading.Timer(6.0, lambda: os._exit(91))
    guard.daemon = True
    guard.start()
    import uvicorn

    launcher = load_launcher()
    launcher.HEARTBEAT_POLL_SECONDS = 0.1
    launcher.HEARTBEAT_STALL_SECONDS = 0.5
    launcher.HEARTBEAT_CONFIRM_SECONDS = 0.3
    launcher.HEARTBEAT_DIAGNOSTIC_SECONDS = 0.2
    result = {"synthetic_only": True, "mode": mode, "private_store_imported": False,
              "launcher_sha256": hashlib.sha256(SCRIPT.read_bytes()).hexdigest()}
    server_ref = []

    async def app(scope, receive, send):
        if scope["type"] == "lifespan":
            result["lifespan_entered"] = True
            output.write_text(json.dumps(result), encoding="utf-8")
            while True:
                message = await receive()
                if message["type"] == "lifespan.startup":
                    result["lifespan_startup_received"] = True
                    output.write_text(json.dumps(result), encoding="utf-8")
                    await send({"type": "lifespan.startup.complete"})
                elif message["type"] == "lifespan.shutdown":
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        else:
            await send({"type": "http.response.start", "status": 200,
                        "headers": [(b"content-length", b"2")]})
            await send({"type": "http.response.body", "body": b"ok"})

    sys.modules["agent_monitor.server"] = types.SimpleNamespace(create_app=lambda *a, **k: app)
    if mode == "diagnostics-blocked":
        launcher.write_heartbeat_failure = lambda _: threading.Event().wait()
    if mode == "diagnostics-error":
        def fail_write(_):
            raise OSError("synthetic detail excluded")
        launcher.write_heartbeat_failure = fail_write
    if mode == "monitor-start-failure":
        original_start = threading.Thread.start
        def thread_start(worker):
            if worker.name == "monitor-loop-heartbeat":
                raise RuntimeError("synthetic detail excluded")
            return original_start(worker)
        threading.Thread.start = thread_start
    if mode == "diagnostic-start-failure":
        original_start = threading.Thread.start
        def thread_start(worker):
            if worker.name == "monitor-heartbeat-diagnostic":
                raise RuntimeError("synthetic detail excluded")
            return original_start(worker)
        threading.Thread.start = thread_start

    async def scenario(server):
        result["real_windows_proactor"] = isinstance(asyncio.get_running_loop(), asyncio.ProactorEventLoop)
        result["scenario_entered"] = True
        output.write_text(json.dumps(result), encoding="utf-8")
        while not (state_dir / "begin-fixture").exists():
            await asyncio.sleep(0.01)
        if mode in {"stalled", "diagnostics-blocked", "diagnostics-error", "diagnostic-start-failure"}:
            threading.Event().wait()  # Main loop really stops, monitor thread can run.
        elif mode == "short-delay":
            time.sleep(0.15)
            await asyncio.sleep(0.9)
        else:
            await asyncio.sleep(1.1)  # More than the test stall/confirmation window.
        server.should_exit = True

    original = uvicorn.Server
    class ProbeServer(original):
        async def startup(self, sockets=None):
            await super().startup(sockets=sockets)
            asyncio.create_task(scenario(self))
    uvicorn.Server = ProbeServer
    config = launcher.Settings("https://fixture.example.test", state_dir, port, "fixture.example.test")
    result["entered_launcher"] = True
    output.write_text(json.dumps(result), encoding="utf-8")
    code = launcher.run(config)
    result["launcher_exit_code"] = code
    result["private_store_imported"] = "agent_monitor.store" in sys.modules
    result["monitor_threads_remaining"] = sum(t.name.startswith("monitor-") for t in threading.enumerate())
    result["final_reason"] = json.loads((state_dir / launcher.STATUS_FILE).read_text(encoding="utf-8"))["reason"]
    output.write_text(json.dumps(result), encoding="utf-8")
    guard.cancel()
    return code


def probe(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.15) as connection:
            connection.sendall(b"GET / HTTP/1.1\r\nHost: fixture.example.test\r\nConnection: close\r\n\r\n")
            reply = b""
            while chunk := connection.recv(1024):
                reply += chunk
            return reply.startswith(b"HTTP/1.1 200") and reply.endswith(b"ok")
    except OSError:
        return False


def run_fixture(tmp_path, mode):
    launcher = load_launcher()
    state = tmp_path / "synthetic-state"
    state.mkdir(exist_ok=True)
    output = tmp_path / f"{mode}-anonymous-child.json"
    with launcher.reserved_socket(0) as available:
        port = available.getsockname()[1]
    assert port != 8766
    started = time.monotonic()
    result = {"synthetic_only": True, "mode": mode,
              "launcher_sha256": hashlib.sha256(SCRIPT.read_bytes()).hexdigest()}
    bootstrap = ("import runpy,sys;sys.path.insert(0,sys.argv[1]);"
                 "script=sys.argv[2];sys.argv=sys.argv[2:];runpy.run_path(script,run_name='__main__')")
    with subprocess.Popen([sys._base_executable, "-I", "-B", "-c", bootstrap,
                           str(Path(pytest.__file__).resolve().parents[1]),
                           str(Path(__file__).resolve()), "--child",
                           "--mode", mode, "--state", str(state), "--port", str(port), "--output", str(output)],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          creationflags=subprocess.CREATE_NO_WINDOW) as process:
        try:
            deadline = time.monotonic() + 8
            while process.poll() is None and time.monotonic() < deadline and not probe(port):
                time.sleep(0.02)
            if mode != "monitor-start-failure":
                assert process.poll() is None and probe(port)
                result["initial_http_ok"] = True
                with pytest.raises(launcher.LauncherError) as locked:
                    with launcher.PublicLock(state):
                        pytest.fail("second fixture acquired live state")
                assert locked.value.exit_code == 3
                result["duplicate_launcher_blocked"] = True
                (state / "begin-fixture").touch()
                started = time.monotonic()
                if mode in {"stalled", "diagnostics-blocked", "diagnostics-error", "diagnostic-start-failure"}:
                    time.sleep(0.12)
                    result["alive_but_http_unresponsive_observed"] = process.poll() is None and not probe(port)
            stdout, stderr = process.communicate(timeout=8)
            assert not stdout and not stderr
            result["exit_code"] = process.returncode
            result["elapsed_after_begin_seconds"] = round(time.monotonic() - started, 3)
            if output.exists():
                result["child"] = json.loads(output.read_text(encoding="utf-8"))
            receipt = state / launcher.HEARTBEAT_FAILURE_FILE
            if receipt.exists():
                result["heartbeat_receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            with launcher.PublicLock(state), launcher.reserved_socket(port):
                result["state_lock_and_owned_port_released"] = True
            result["health_after_exit_false"] = not probe(port)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)
            # Only synthetic known fixture files, never retain raw status PID.
            for name in (launcher.STATUS_FILE, launcher.LOCK_FILE, launcher.EVENTS_FILE,
                         launcher.HEARTBEAT_FAILURE_FILE, ".launcher-heartbeat-failure.tmp", "begin-fixture"):
                (state / name).unlink(missing_ok=True)
    (tmp_path / f"{mode}-anonymous-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


@WINDOWS
@pytest.mark.parametrize("mode", ["normal", "short-delay"])
def test_real_healthy_or_briefly_delayed_loop_is_not_terminated(tmp_path, mode):
    result = run_fixture(tmp_path, mode)
    assert result["exit_code"] == 0
    assert result["child"]["monitor_threads_remaining"] == 0
    assert result["child"]["real_windows_proactor"]
    assert not result["child"]["private_store_imported"]
    assert result["state_lock_and_owned_port_released"] and result["health_after_exit_false"]
    assert "heartbeat_receipt" not in result


@WINDOWS
@pytest.mark.parametrize("mode", ["stalled", "diagnostics-blocked", "diagnostics-error", "diagnostic-start-failure"])
def test_real_stalled_loop_exits_despite_unavailable_diagnostics(tmp_path, mode):
    result = run_fixture(tmp_path, mode)
    assert result["alive_but_http_unresponsive_observed"]
    assert result["exit_code"] == 70
    assert 0.45 <= result["elapsed_after_begin_seconds"] < 3
    assert result["state_lock_and_owned_port_released"] and result["health_after_exit_false"]
    if mode == "stalled":
        assert result["heartbeat_receipt"]["event"] == "event-loop-stalled"
    successor = run_fixture(tmp_path, "normal")
    assert successor["initial_http_ok"] and successor["duplicate_launcher_blocked"]
    assert successor["exit_code"] == 0


@WINDOWS
def test_real_monitor_thread_start_failure_exits_and_releases_resources(tmp_path):
    result = run_fixture(tmp_path, "monitor-start-failure")
    assert result["exit_code"] == 1
    assert result["child"]["final_reason"] == "heartbeat-monitor-unavailable"
    assert result["child"]["monitor_threads_remaining"] == 0
    assert result["state_lock_and_owned_port_released"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(child(args.mode, args.state, args.port, args.output))
