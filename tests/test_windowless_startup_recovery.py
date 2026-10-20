"""Real startup failures and fresh-process recovery using synthetic state only."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import asynccontextmanager
import hashlib
import importlib.util
import json
import logging
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import types

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "run-public.pyw"
WINDOWS = pytest.mark.skipif(os.name != "nt", reason="real Windows launcher lifecycle")


def helpers():
    # Shared test-only loader and strict anonymous journal validation. Importing
    # this file neither creates the real Monitor app nor runs its child harness.
    path = Path(__file__).with_name("test_windowless_listener_recovery.py")
    spec = importlib.util.spec_from_file_location("synthetic_listener_test_helpers", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def random_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
        candidate.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        candidate.bind(("127.0.0.1", 0))
        port = candidate.getsockname()[1]
    assert port != 8766
    return port


def child(mode, state, port, output):
    if os.name != "nt" or not 1 <= port <= 65535 or port == 8766:
        return 90
    import uvicorn

    support = helpers()
    launcher = support._load_launcher(SCRIPT)
    state.mkdir(parents=True, exist_ok=True)
    result = {
        "synthetic_only": True,
        "mode": mode,
        "launcher_sha256": hashlib.sha256(SCRIPT.read_bytes()).hexdigest(),
        "lifespan_starts": 0,
        "lifespan_stops": 0,
        "factory_calls": 0,
        "http_ok": False,
        "phases": [],
    }

    def save():
        output.write_text(json.dumps(result, indent=2), encoding="utf-8")

    def timed_out():
        result["watchdog_expired"] = True
        save()
        os._exit(91)

    guard = threading.Timer(10, timed_out)
    guard.daemon = True
    guard.start()
    original_status = launcher.write_status

    def observed_status(state_dir, phase, **kwargs):
        original_status(state_dir, phase, **kwargs)
        result["phases"].append(phase)

    launcher.write_status = observed_status

    async def synthetic_app(scope, receive, send):
        if scope["type"] == "lifespan":
            while True:
                message = await receive()
                if message["type"] == "lifespan.startup":
                    result["lifespan_starts"] += 1
                    if mode == "startup-failed":
                        await send({"type": "lifespan.startup.failed", "message": "SYNTHETIC_PRIVATE_MESSAGE"})
                        return
                    await send({"type": "lifespan.startup.complete"})
                elif message["type"] == "lifespan.shutdown":
                    result["lifespan_stops"] += 1
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        else:
            assert scope["type"] == "http"
            await send({"type": "http.response.start", "status": 200,
                        "headers": [(b"content-length", b"2")]})
            await send({"type": "http.response.body", "body": b"ok"})

    def create_app(*args, **kwargs):
        result["factory_calls"] += 1
        if mode == "startup-raises":
            from starlette.applications import Starlette

            @asynccontextmanager
            async def failing_lifespan(app):
                result["lifespan_starts"] += 1
                raise RuntimeError("SYNTHETIC_PRIVATE_MESSAGE")
                yield  # pragma: no cover -- async contextmanager protocol

            # Starlette implements the same lifespan failure wrapper used by
            # FastAPI. A bare ASGI raise alone has different auto-mode semantics.
            return Starlette(lifespan=failing_lifespan)
        return synthetic_app

    sys.modules["agent_monitor.server"] = types.SimpleNamespace(create_app=create_app)
    real_server = uvicorn.Server
    probe = None

    async def check_http(server):
        writer = None
        try:
            result["real_windows_proactor"] = isinstance(asyncio.get_running_loop(), asyncio.ProactorEventLoop)
            reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", port), 0.5)
            writer.write(b"GET /fixture HTTP/1.1\r\nHost: fixture.example.test\r\nConnection: close\r\n\r\n")
            await writer.drain()
            response = await asyncio.wait_for(reader.read(), 0.5)
            result["http_ok"] = response.startswith(b"HTTP/1.1 200") and response.endswith(b"ok")
        except BaseException as error:
            result["probe_error_type"] = type(error).__name__
        finally:
            if writer is not None:
                writer.close()
                try:
                    await writer.wait_closed()
                except OSError:
                    pass
            server.should_exit = True

    class ProbeServer(real_server):
        async def startup(self, sockets=None):
            nonlocal probe
            result["real_windows_proactor"] = isinstance(asyncio.get_running_loop(), asyncio.ProactorEventLoop)
            await super().startup(sockets=sockets)
            if self.started:
                probe = asyncio.create_task(check_http(self))

    uvicorn.Server = ProbeServer
    try:
        config = launcher.Settings("https://fixture.example.test", state, port, "fixture.example.test")
        result["launcher_exit_code"] = launcher.run(config)
        status = json.loads((state / launcher.STATUS_FILE).read_text(encoding="utf-8"))
        result["final_phase"] = status["phase"]
        result["final_reason"] = status["reason"]
        result["events"] = support._anonymous_events(state / launcher.EVENTS_FILE)
        result["journal_contains_private_message"] = "SYNTHETIC_PRIVATE_MESSAGE" in (state / launcher.EVENTS_FILE).read_text(encoding="utf-8")
        result["private_store_imported"] = any(name in sys.modules for name in (
            "agent_monitor.storage", "agent_monitor.store", "agent_monitor.task_replies", "agent_monitor.collector"))
        result["probe_completed"] = probe is None or probe.done()
        with launcher.PublicLock(state):
            result["state_lock_released"] = True
        with launcher.reserved_socket(port):
            result["owned_port_released"] = True
    except BaseException as error:
        result["harness_error_type"] = type(error).__name__
    finally:
        guard.cancel()
        uvicorn.Server = real_server
        save()
    return 0 if "harness_error_type" not in result and "probe_error_type" not in result else 92


def run_child(tmp_path, mode, *, state=None, port=None):
    state = state or (tmp_path / "synthetic-state")
    port = port if port is not None else random_port()
    output = tmp_path / f"{mode}-anonymous-result.json"
    completed = subprocess.run([
        sys.executable, "-I", "-B", str(Path(__file__).resolve()), "--child", "--mode", mode,
        "--state", str(state), "--port", str(port), "--output", str(output),
    ], capture_output=True, timeout=14, env={**os.environ, "TEMP": str(tmp_path), "TMP": str(tmp_path)},
        creationflags=subprocess.CREATE_NO_WINDOW)
    result = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
    assert completed.returncode == 0, {"returncode": completed.returncode, "result": result}
    assert not completed.stdout and not completed.stderr
    assert result["synthetic_only"] and result["real_windows_proactor"]
    assert result["factory_calls"] == result["lifespan_starts"] == 1
    assert result["state_lock_released"] and result["owned_port_released"] and result["probe_completed"]
    assert result["journal_contains_private_message"] is False and result["private_store_imported"] is False
    return result


@WINDOWS
@pytest.mark.parametrize("mode", ["startup-failed", "startup-raises"])
def test_real_lifespan_failure_records_failure_and_releases_owned_resources(tmp_path, mode):
    result = run_child(tmp_path, mode)
    assert result["launcher_exit_code"] != 0
    assert result["final_phase"] == "failed" and result["final_reason"] == "server-start-failed"
    assert "running" not in result["phases"]
    assert result["http_ok"] is False and result["lifespan_stops"] == 0
    assert [event["event"] for event in result["events"]] == ["starting", "startup-failed"]


@WINDOWS
def test_fresh_process_recovers_same_synthetic_state_and_retains_failure_journal(tmp_path):
    state = tmp_path / "shared-synthetic-state"
    port = random_port()
    failed = run_child(tmp_path, "startup-raises", state=state, port=port)
    assert failed["launcher_exit_code"] != 0 and failed["final_reason"] == "server-start-failed"
    recovered = run_child(tmp_path, "normal", state=state, port=port)
    assert recovered["launcher_exit_code"] == 0 and recovered["final_phase"] == "stopped"
    assert recovered["http_ok"] and recovered["lifespan_stops"] == 1
    assert recovered["phases"] == ["starting", "running", "stopping", "stopped"]
    assert recovered["events"][:len(failed["events"])] == failed["events"]
    assert [event["event"] for event in recovered["events"]] == [
        "starting", "startup-failed", "starting", "started", "stopping", "stopped"]


def test_startup_returning_without_started_flag_records_specific_failure(tmp_path, monkeypatch):
    launcher = helpers()._load_launcher(SCRIPT)

    class NotStartedServer:
        def __init__(self, config):
            self.started = self.should_exit = False

        async def startup(self, sockets=None):
            return

        def run(self, sockets=None):
            asyncio.run(self.startup(sockets=sockets))

    monkeypatch.setitem(sys.modules, "uvicorn", types.SimpleNamespace(
        Config=lambda *args, **kwargs: object(), Server=NotStartedServer))
    monkeypatch.setitem(sys.modules, "agent_monitor.server", types.SimpleNamespace(create_app=lambda *args, **kwargs: object()))
    previous_logging = logging.root.manager.disable
    previous_path = sys.path[:]
    try:
        with launcher.reserved_socket(0) as listener:
            port = listener.getsockname()[1]
            assert port != 8766
            config = launcher.Settings("https://fixture.example.test", tmp_path, port, "fixture.example.test")
            with pytest.raises(launcher.LauncherError, match="server-start-failed"):
                launcher.serve(config, listener, lambda: pytest.fail("never-started server must not mark running"))
            assert listener.fileno() == -1
        events = helpers()._anonymous_events(tmp_path / launcher.EVENTS_FILE)
        assert [event["event"] for event in events] == ["starting", "startup-failed"]
    finally:
        logging.disable(previous_logging)
        sys.path[:] = previous_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--child", action="store_true", required=True)
    parser.add_argument("--mode", choices=["startup-failed", "startup-raises", "normal"], required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(child(args.mode, args.state.resolve(), args.port, args.output.resolve()))
