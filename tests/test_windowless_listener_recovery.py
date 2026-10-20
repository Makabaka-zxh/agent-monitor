"""Isolated real Windows accept-loop tests; no Monitor store or live endpoint.

The child harness replaces only create_app with an in-memory ASGI fixture and
injects a failed Future into the next accept for its own random-port socket.
Windows' actual Proactor callback closes the actual socket. Uvicorn and the
launcher lifecycle remain real. The CLI also supports a frozen old launcher.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import errno
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
WINDOWS = pytest.mark.skipif(os.name != "nt", reason="real Windows Proactor accept path")
EVENT_NAMES = {"starting", "started", "startup-failed", "accept-failed", "listener-missing",
               "recovery-started", "recovery-restored", "recovery-failed", "recovery-exhausted",
               "stopping", "stopped", "server-failed"}


def _anonymous_events(path):
    raw = path.read_bytes()
    assert len(raw) <= 65536
    journal = json.loads(raw)
    assert type(journal) is dict and set(journal) == {"schema", "events"}
    assert type(journal["schema"]) is int and journal["schema"] == 1
    assert type(journal["events"]) is list and len(journal["events"]) <= 32
    summary = []
    for event in journal["events"]:
        assert type(event) is dict and set(event) == {"event", "time", "attempt", "exception_type", "errno", "winerror"}
        assert event["event"] in EVENT_NAMES
        assert event["exception_type"] in {"", "OSError", "TimeoutError", "CancelledError", "RuntimeError", "Exception"}
        assert datetime.fromisoformat(event["time"]).tzinfo == timezone.utc
        assert type(event["attempt"]) is int and 0 <= event["attempt"] <= 3
        for name in ("errno", "winerror"):
            assert event[name] is None or (type(event[name]) is int and -(2 ** 31) <= event[name] < 2 ** 32)
        summary.append({name: event[name] for name in ("event", "attempt", "exception_type", "errno", "winerror")})
    return summary


def _load_launcher(path):
    loader = importlib.machinery.SourceFileLoader("synthetic_listener_launcher", str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    loader.exec_module(module)
    return module


def _child(launcher_path: Path, mode: str, output: Path):
    """Runs in a disposable child; emits only fixed synthetic observations."""
    if os.name != "nt":
        return 90
    import uvicorn

    output.parent.mkdir(parents=True, exist_ok=True)
    state_dir = output.parent / "synthetic-state"
    state_dir.mkdir()
    result = {
        "synthetic_only": True,
        "mode": mode,
        "launcher_sha256": hashlib.sha256(launcher_path.read_bytes()).hexdigest(),
        "python_version": ".".join(map(str, sys.version_info[:3])),
        "uvicorn_version": uvicorn.__version__,
        "lifespan_starts": 0,
        "lifespan_stops": 0,
        "app_factory_calls": 0,
        "accept_faults_injected": 0,
        "accept_errors_observed": 0,
        "tick_count": 0,
        "private_store_imported": False,
    }
    launcher = _load_launcher(launcher_path)
    handles = {"fault_socket": None, "blocker": None, "probe_task": None}

    def save():
        output.write_text(json.dumps(result, indent=2), encoding="utf-8")

    def watchdog():
        result["watchdog_expired"] = True
        save()
        os._exit(91)

    guard = threading.Timer(14.0, watchdog)
    guard.daemon = True
    guard.start()

    async def synthetic_app(scope, receive, send):
        if scope["type"] == "lifespan":
            while True:
                message = await receive()
                if message["type"] == "lifespan.startup":
                    result["lifespan_starts"] += 1
                    await send({"type": "lifespan.startup.complete"})
                elif message["type"] == "lifespan.shutdown":
                    result["lifespan_stops"] += 1
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        elif scope["type"] == "http":
            await send({"type": "http.response.start", "status": 200,
                        "headers": [(b"content-type", b"text/plain"), (b"content-length", b"2")]})
            await send({"type": "http.response.body", "body": b"ok"})

    def synthetic_create_app(*args, **kwargs):
        result["app_factory_calls"] += 1
        return synthetic_app

    # This substitution happens before launcher.serve imports the real module.
    sys.modules["agent_monitor.server"] = types.SimpleNamespace(create_app=synthetic_create_app)
    real_server = uvicorn.Server

    with launcher.reserved_socket(0) as free:
        port = free.getsockname()[1]
    if port == 8766:
        raise AssertionError("reserved_fixture_port")
    config = launcher.Settings("https://fixture.example.test", state_dir, port, "fixture.example.test")

    def phase():
        return json.loads((state_dir / launcher.STATUS_FILE).read_text(encoding="utf-8"))["phase"]

    async def http_probe():
        writer = None
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", port), 0.4)
            writer.write(b"GET /synthetic HTTP/1.1\r\nHost: fixture.example.test\r\nConnection: close\r\n\r\n")
            await writer.drain()
            reply = await asyncio.wait_for(reader.read(), 0.4)
            return reply.startswith(b"HTTP/1.1 200") and reply.endswith(b"ok")
        except (OSError, asyncio.TimeoutError):
            return False
        finally:
            if writer is not None:
                writer.close()
                try:
                    await writer.wait_closed()
                except OSError:
                    pass

    def live_socket(server):
        sockets = [sock for entry in server.servers for sock in entry.sockets or ()]
        assert len(sockets) == 1
        return sockets[0]

    def occupy_port():
        blocker = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        blocker.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            blocker.bind(("127.0.0.1", port))
            blocker.listen()
        except OSError:
            blocker.close()
            result["blocker_bound"] = False
        else:
            handles["blocker"] = blocker
            result["blocker_bound"] = True

    async def inject(server, kind, *, blocker=False, shutdown=False):
        loop = asyncio.get_running_loop()
        target_fd = live_socket(server).fileno()
        original_accept = loop._proactor.accept
        armed = True

        def injected_accept(sock):
            nonlocal armed
            if armed and sock.fileno() == target_fd:
                armed = False
                loop._proactor.accept = original_accept
                handles["fault_socket"] = sock
                result["accept_faults_injected"] += 1
                failed = loop.create_future()

                def after_failure(_):
                    if shutdown:
                        server.should_exit = True
                    if blocker:
                        # Runs after the actual Proactor callback closes sock.
                        loop.call_soon(occupy_port)

                failed.add_done_callback(after_failure)
                if kind == "cancel":
                    failed.cancel()
                else:
                    failed.set_exception(OSError(errno.EMFILE, "synthetic accept failure"))
                return failed
            return original_accept(sock)

        loop._proactor.accept = injected_accept
        # One real connection completes the pending accept and advances the
        # standard-library accept loop to our one-shot failure.
        await http_probe()
        deadline = loop.time() + 1.5
        while (handles["fault_socket"] is None or handles["fault_socket"].fileno() != -1) and loop.time() < deadline:
            await asyncio.sleep(0.01)
        loop._proactor.accept = original_accept
        assert not armed and handles["fault_socket"].fileno() == -1
        result["actual_proactor_closed_socket"] = True

    async def wait_recovered(server):
        deadline = asyncio.get_running_loop().time() + 2.5
        while asyncio.get_running_loop().time() < deadline and not server.should_exit:
            if phase() == "running" and await http_probe():
                current = live_socket(server)
                return current.getsockname() == ("127.0.0.1", port) and current.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN) == 1
            await asyncio.sleep(0.04)
        return False

    async def scenario(server):
        try:
            loop = asyncio.get_running_loop()
            result["real_windows_proactor"] = isinstance(loop, asyncio.ProactorEventLoop)
            assert result["real_windows_proactor"]
            result["initial_http_ok"] = await http_probe()
            assert result["initial_http_ok"] and phase() == "running"
            initial_state = server.server_state
            initial_lifespan = server.lifespan
            prior_handler = loop.get_exception_handler()

            def observe_accept_error(event_loop, context):
                if context.get("message") == "Accept failed on a socket":
                    result["accept_errors_observed"] += 1
                if prior_handler is not None:
                    prior_handler(event_loop, context)

            loop.set_exception_handler(observe_accept_error)
            if mode == "normal":
                server.should_exit = True
                return
            if mode == "recovery-timeout":
                launcher.LISTENER_RECOVERY_TIMEOUT = 0.15

                async def stalled_create_server(*args, **kwargs):
                    assert kwargs["sock"].getsockname() == ("127.0.0.1", port)
                    await asyncio.Event().wait()

                loop.create_server = stalled_create_server
            if mode == "diagnostics-failed":
                original_record = launcher.LifecycleEvents.record

                def failing_diagnostics(journal, event, **kwargs):
                    if event == "listener-missing":
                        raise OSError("synthetic private message must not be emitted")
                    return original_record(journal, event, **kwargs)

                launcher.LifecycleEvents.record = failing_diagnostics
            kind = "cancel" if mode.endswith("cancel") else "oserror"
            if mode.startswith("old-"):
                await inject(server, kind)
                ticks = result["tick_count"]
                await asyncio.sleep(0.45)
                result["http_failed_after_loss"] = not await http_probe()
                result["main_loop_continued"] = result["tick_count"] > ticks
                result["running_status_after_loss"] = phase() == "running"
                result["did_not_request_exit"] = not server.should_exit
                result["uvicorn_started_still_true"] = server.started
                result["asyncio_server_is_serving_still_true"] = all(entry.is_serving() for entry in server.servers)
                result["old_alive_without_listener_reproduced"] = all(result[key] for key in (
                    "http_failed_after_loss", "main_loop_continued", "running_status_after_loss", "did_not_request_exit"))
                server.should_exit = True
            elif mode == "occupied":
                await inject(server, kind, blocker=True)
            elif mode == "shutdown-priority":
                await inject(server, kind, shutdown=True)
            elif mode in {"recovery-timeout", "diagnostics-failed"}:
                await inject(server, kind)
            else:
                rounds = 4 if mode == "exhaustion" else 1
                recovered = 0
                for index in range(rounds):
                    handles["fault_socket"] = None
                    await inject(server, kind)
                    if mode == "exhaustion" and index == rounds - 1:
                        break
                    if not await wait_recovered(server):
                        raise AssertionError("fixture_recovery_not_observed")
                    recovered += 1
                result["successful_recoveries"] = recovered
                result["same_server_state"] = server.server_state is initial_state
                result["same_lifespan"] = server.lifespan is initial_lifespan
                if mode != "exhaustion":
                    server.should_exit = True
        except BaseException as error:
            result["probe_error_type"] = type(error).__name__
            server.should_exit = True

    class ProbeServer(real_server):
        async def startup(self, sockets=None):
            await super().startup(sockets=sockets)
            if handles["probe_task"] is None:
                handles["probe_task"] = asyncio.create_task(scenario(self))

        async def on_tick(self, counter):
            result["tick_count"] += 1
            return await super().on_tick(counter)

    uvicorn.Server = ProbeServer
    began = time.monotonic()
    try:
        result["launcher_exit_code"] = launcher.run(config)
        recorded = json.loads((state_dir / launcher.STATUS_FILE).read_text(encoding="utf-8"))
        result["final_phase"] = recorded["phase"]
        result["final_reason"] = recorded["reason"]
        event_path = state_dir / "launcher-events.json"
        if event_path.exists():
            result["events"] = _anonymous_events(event_path)
            result["journal_schema_and_bounds_valid"] = True
        result["private_store_imported"] = "agent_monitor.store" in sys.modules
        task = handles["probe_task"]
        result["probe_completed"] = task is not None and task.done()
        if handles["blocker"] is None:
            with launcher.reserved_socket(port):
                result["owned_port_released"] = True
    finally:
        if handles["blocker"] is not None:
            handles["blocker"].close()
        guard.cancel()
        uvicorn.Server = real_server
        result["elapsed_seconds"] = round(time.monotonic() - began, 3)
        save()
    return 0 if "probe_error_type" not in result else 92


def _run_fixture(tmp_path, mode):
    output = tmp_path / "anonymous-result.json"
    environment = {**os.environ, "TEMP": str(tmp_path), "TMP": str(tmp_path)}
    completed = subprocess.run(
        [sys.executable, "-I", "-B", str(Path(__file__).resolve()), "--child", "--launcher", str(SCRIPT),
         "--mode", mode, "--output", str(output)],
        capture_output=True, timeout=18, env=environment,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    result = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
    assert completed.returncode == 0, {"returncode": completed.returncode, "result": result}
    assert not completed.stdout and not completed.stderr
    assert result["real_windows_proactor"] and result["initial_http_ok"]
    assert result["lifespan_starts"] == result["lifespan_stops"] == result["app_factory_calls"] == 1
    assert result["private_store_imported"] is False
    assert result["probe_completed"]
    assert result["journal_schema_and_bounds_valid"]
    if mode != "occupied":
        assert result["owned_port_released"]
    return result


@WINDOWS
@pytest.mark.parametrize("mode", ["recovery-oserror", "recovery-cancel"])
def test_real_proactor_accept_failure_recovers_same_port_and_application(tmp_path, mode):
    result = _run_fixture(tmp_path, mode)
    assert result["actual_proactor_closed_socket"]
    assert result["successful_recoveries"] == 1
    assert result["same_server_state"] and result["same_lifespan"]
    assert result["launcher_exit_code"] == 0
    assert result["final_phase"] == "stopped"
    assert result["accept_errors_observed"] == (0 if mode.endswith("cancel") else 1)
    events = [entry["event"] for entry in result["events"]]
    assert events.count("listener-missing") == events.count("recovery-started") == events.count("recovery-restored") == 1
    assert events.count("accept-failed") == result["accept_errors_observed"]


@WINDOWS
def test_repeated_real_socket_loss_exhausts_bounded_recovery(tmp_path):
    result = _run_fixture(tmp_path, "exhaustion")
    assert result["accept_faults_injected"] == 4
    assert result["successful_recoveries"] == 3
    assert result["launcher_exit_code"] != 0
    assert result["final_phase"] == "failed"
    assert result["final_reason"] == "listener-recovery-exhausted"
    events = [entry["event"] for entry in result["events"]]
    assert events.count("recovery-started") == events.count("recovery-restored") == 3
    assert events.count("recovery-exhausted") == 1


@WINDOWS
def test_recovery_does_not_take_over_another_socket_on_same_random_port(tmp_path):
    result = _run_fixture(tmp_path, "occupied")
    assert result["blocker_bound"]
    assert result["launcher_exit_code"] != 0
    assert result["final_phase"] == "failed"
    assert result["final_reason"] == "listener-recovery-failed"
    events = [entry["event"] for entry in result["events"]]
    assert events.count("recovery-failed") == 1 and "recovery-restored" not in events


@WINDOWS
def test_stalled_rebind_is_bounded_and_releases_owned_socket(tmp_path):
    result = _run_fixture(tmp_path, "recovery-timeout")
    assert result["launcher_exit_code"] != 0
    assert result["final_phase"] == "failed"
    assert result["final_reason"] == "listener-recovery-failed"
    failures = [event for event in result["events"] if event["event"] == "recovery-failed"]
    assert len(failures) == 1 and failures[0]["exception_type"] == "TimeoutError"
    assert result["elapsed_seconds"] < 3


@WINDOWS
def test_unwritable_loss_diagnostics_fail_closed_without_stale_running(tmp_path):
    result = _run_fixture(tmp_path, "diagnostics-failed")
    assert result["launcher_exit_code"] != 0
    assert result["final_phase"] == "failed"
    assert result["final_reason"] == "listener-diagnostics-unavailable"
    assert not any(event["event"].startswith("recovery-") for event in result["events"])


@WINDOWS
@pytest.mark.parametrize("mode", ["normal", "shutdown-priority"])
def test_normal_shutdown_wins_over_listener_repair(tmp_path, mode):
    result = _run_fixture(tmp_path, mode)
    assert result["launcher_exit_code"] == 0
    assert result["final_phase"] == "stopped"
    events = [entry["event"] for entry in result["events"]]
    assert not any(event.startswith("recovery-") for event in events)
    assert "listener-missing" not in events
    assert events[-2:] == ["stopping", "stopped"]


def test_journal_is_bounded_across_launcher_instances_and_excludes_messages(tmp_path):
    launcher = _load_launcher(SCRIPT)
    journal = launcher.LifecycleEvents(tmp_path)
    for _ in range(35):
        journal.record("accept-failed", error=OSError(24, "SYNTHETIC_PRIVATE_MESSAGE"))
    recreated = launcher.LifecycleEvents(tmp_path)
    assert len(recreated.events) == 32
    recreated.record("starting")
    events = _anonymous_events(tmp_path / launcher.EVENTS_FILE)
    assert len(events) == 32
    assert events[-1]["event"] == "starting"
    assert all(event["errno"] == 24 for event in events[:-1])
    assert "SYNTHETIC_PRIVATE_MESSAGE" not in (tmp_path / launcher.EVENTS_FILE).read_text(encoding="utf-8")
    assert not list(tmp_path.glob(".launcher-events-*.tmp"))


def test_journal_reads_when_named_and_handle_ctime_clocks_differ(tmp_path, monkeypatch):
    launcher = _load_launcher(SCRIPT)
    launcher.LifecycleEvents(tmp_path).record("started")
    real_fstat = launcher.os.fstat

    def differing_descriptor_clock(fd):
        actual = real_fstat(fd)
        fields = {name: getattr(actual, name) for name in (
            "st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")}
        fields["st_ctime_ns"] += 10_000_000
        return types.SimpleNamespace(**fields)

    monkeypatch.setattr(launcher.os, "fstat", differing_descriptor_clock)
    recreated = launcher.LifecycleEvents(tmp_path)
    assert len(recreated.events) == 1 and recreated.events[0]["event"] == "started"


@pytest.mark.parametrize("contents", [
    b"not-json", b"x" * 65537,
    b'{"schema":true,"events":[]}',
    b'{"schema":1,"events":[],"private":"SYNTHETIC_PRIVATE_MESSAGE"}',
    b'{"schema":1,"events":[{"event":"started","private":"SYNTHETIC_PRIVATE_MESSAGE"}]}',
], ids=["invalid-json", "oversized", "boolean-schema", "extra-field", "invalid-event"])
def test_journal_rejects_untrusted_history_and_overwrites_only_strict_schema(tmp_path, contents):
    launcher = _load_launcher(SCRIPT)
    path = tmp_path / launcher.EVENTS_FILE
    path.write_bytes(contents)
    journal = launcher.LifecycleEvents(tmp_path)
    assert journal.events == []
    journal.record("starting")
    assert [event["event"] for event in _anonymous_events(path)] == ["starting"]
    assert "SYNTHETIC_PRIVATE_MESSAGE" not in path.read_text(encoding="utf-8")


def test_socket_health_requires_actual_listening_socket_on_expected_loopback_port():
    launcher = _load_launcher(SCRIPT)
    with launcher.reserved_socket(0) as listener:
        port = listener.getsockname()[1]
        assert not launcher.listener_healthy(listener, port)
        listener.listen()
        assert launcher.listener_healthy(listener, port)
        assert not launcher.listener_healthy(listener, port + 1 if port < 65535 else port - 1)
        listener.close()
        assert not launcher.listener_healthy(listener, port)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true", required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--mode", choices=["old-oserror", "old-cancel", "recovery-oserror", "recovery-cancel",
                                           "exhaustion", "occupied", "normal", "shutdown-priority",
                                           "recovery-timeout", "diagnostics-failed"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(_child(args.launcher.resolve(), args.mode, args.output.resolve()))
