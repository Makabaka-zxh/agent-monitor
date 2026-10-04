"""Launcher-only tests: fake databases and servers; never collect local sessions."""

import asyncio
import importlib.machinery
import importlib.util
import json
import logging
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import types

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "run-public.pyw"
LOADER = importlib.machinery.SourceFileLoader("windowless_launcher", str(SCRIPT))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
launcher = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = launcher
LOADER.exec_module(launcher)
windows_only = pytest.mark.skipif(os.name != "nt", reason="Win32 launcher semantics")


@pytest.fixture
def project(tmp_path):
    (tmp_path / "agent_monitor").mkdir()
    (tmp_path / "agent_monitor" / "__main__.py").touch()
    state = tmp_path / ".state" / "hub"
    state.mkdir(parents=True)
    (state / "monitor.sqlite3").touch()  # Existence check only, never opened by these tests.
    return tmp_path


def config(project, port=8766):
    return launcher.settings("https://monitor.example.test", None, port, project)


def status(config):
    return json.loads((config.state_dir / launcher.STATUS_FILE).read_text(encoding="utf-8"))


def test_settings_preserve_existing_state_independent_of_cwd(project, tmp_path, monkeypatch):
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    monkeypatch.chdir(elsewhere)
    result = launcher.settings("https://Monitor.Example.Test:443/", ".state/hub", 8766, project)
    assert result.state_dir == (project / ".state/hub").resolve()
    assert result.public_url == "https://monitor.example.test"
    assert result.hostname == "monitor.example.test"
    assert result.port == 8766
    assert not (elsewhere / ".state").exists()


@pytest.mark.parametrize("url", [
    "http://monitor.example.test", "https://user:secret@monitor.example.test",
    "https://monitor.example.test/path", "https://monitor.example.test/?code=private",
    "https://monitor.example.test/#token", "https://monitor.example.test:0",
    "https://monitor.example.test:65536", "https://monitor.example.test\n",
    "https://*.example.test", "https://monitor%2eexample.test", "https://",
])
def test_settings_reject_invalid_public_origin(project, url):
    with pytest.raises(launcher.LauncherError, match="invalid-https-origin"):
        launcher.settings(url, None, 8766, project)


@pytest.mark.parametrize("port", [0, -1, 65536, True, "8766"])
def test_settings_reject_invalid_port(project, port):
    with pytest.raises(launcher.LauncherError, match="invalid-port"):
        config(project, port)


def test_settings_do_not_create_missing_state_or_database(project):
    missing = project / "new-state"
    with pytest.raises(launcher.LauncherError, match="existing-state-directory-required"):
        launcher.settings("https://monitor.example.test", missing, 8766, project)
    assert not missing.exists()
    empty = project / "empty"
    empty.mkdir()
    with pytest.raises(launcher.LauncherError, match="existing-database-required"):
        launcher.settings("https://monitor.example.test", empty, 8766, project)
    assert not (empty / "monitor.sqlite3").exists()


@windows_only
def test_lock_blocks_other_launcher_and_powershell_then_releases(project):
    current = config(project)
    powershell = shutil.which("powershell.exe")
    assert powershell
    command = (
        "try { $handle = [System.IO.File]::Open($env:MONITOR_TEST_LOCK, "
        "[System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, "
        "[System.IO.FileShare]::None); $handle.Dispose(); exit 0 } catch { exit 3 }"
    )
    environment = {**os.environ, "MONITOR_TEST_LOCK": str(current.state_dir / launcher.LOCK_FILE)}
    with launcher.PublicLock(current.state_dir):
        with pytest.raises(launcher.LauncherError, match="state-already-in-use") as caught:
            with launcher.PublicLock(current.state_dir):
                pytest.fail("a second launcher acquired the same state")
        assert caught.value.exit_code == 3
        blocked = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", command],
                                 env=environment, capture_output=True, timeout=10,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        assert blocked.returncode == 3
    released = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", command],
                              env=environment, capture_output=True, timeout=10,
                              creationflags=subprocess.CREATE_NO_WINDOW)
    assert released.returncode == 0
    with launcher.PublicLock(current.state_dir):
        pass


@windows_only
def test_powershell_exclusive_lock_blocks_windowless_launcher(project):
    current = config(project)
    command = (
        "$handle = [System.IO.File]::Open($env:MONITOR_TEST_LOCK, "
        "[System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, "
        "[System.IO.FileShare]::None); try { [Console]::WriteLine('locked'); "
        "[Console]::Out.Flush(); [Console]::ReadLine() | Out-Null } finally { $handle.Dispose() }"
    )
    with subprocess.Popen(
        [shutil.which("powershell.exe"), "-NoProfile", "-NonInteractive", "-Command", command],
        env={**os.environ, "MONITOR_TEST_LOCK": str(current.state_dir / launcher.LOCK_FILE)},
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    ) as process:
        try:
            assert process.stdout.readline().strip() == "locked"
            with pytest.raises(launcher.LauncherError, match="state-already-in-use"):
                with launcher.PublicLock(current.state_dir):
                    pass
        finally:
            process.communicate("release\n", timeout=10)
    assert process.returncode == 0
    with launcher.PublicLock(current.state_dir):
        pass


def test_bound_socket_is_exclusive_and_released():
    with launcher.reserved_socket(0) as reserved:
        address = reserved.getsockname()
        assert address[0] == "127.0.0.1"
        assert not reserved.get_inheritable()
        with pytest.raises(launcher.LauncherError, match="loopback-port-unavailable"):
            with launcher.reserved_socket(address[1]):
                pytest.fail("an occupied port was rebound")
    with launcher.reserved_socket(address[1]):
        pass


@windows_only
def test_run_holds_lock_and_supplies_bound_socket_throughout_server_lifetime(project):
    with launcher.reserved_socket(0) as available:
        current = config(project, available.getsockname()[1])
    observed = []

    def fake_server(received, listener, started):
        assert received is current
        assert listener.getsockname() == ("127.0.0.1", current.port)
        assert status(current)["phase"] == "starting"
        with pytest.raises(launcher.LauncherError):
            with launcher.PublicLock(current.state_dir):
                pass
        with pytest.raises(launcher.LauncherError):
            with launcher.reserved_socket(current.port):
                pass
        started()
        assert status(current)["phase"] == "running"
        observed.append(listener)

    assert launcher.run(current, fake_server) == 0
    assert status(current)["phase"] == "stopped"
    assert observed[0].fileno() == -1
    with launcher.PublicLock(current.state_dir), launcher.reserved_socket(current.port):
        pass


@windows_only
def test_duplicate_run_does_not_overwrite_running_status(project):
    current = config(project)
    launcher.write_status(current.state_dir, "running")
    before = (current.state_dir / launcher.STATUS_FILE).read_bytes()
    with launcher.PublicLock(current.state_dir):
        assert launcher.run(current, lambda *_: pytest.fail("server was started")) == 3
    assert (current.state_dir / launcher.STATUS_FILE).read_bytes() == before


@windows_only
def test_occupied_port_fails_before_server_or_collector(project):
    with launcher.reserved_socket(0) as busy:
        busy.listen()
        current = config(project, busy.getsockname()[1])
        assert launcher.run(current, lambda *_: pytest.fail("server was started")) == 4
    assert status(current)["phase"] == "failed"
    assert status(current)["reason"] == "loopback-port-unavailable"
    assert (current.state_dir / "monitor.sqlite3").stat().st_size == 0


@windows_only
def test_failure_records_only_fixed_lifecycle_fields_and_exception_type(project):
    with launcher.reserved_socket(0) as available:
        current = config(project, available.getsockname()[1])

    def failing_server(*_):
        raise RuntimeError("private-password https://example.test/callback?code=private-code")

    assert launcher.run(current, failing_server) == 1
    recorded = status(current)
    assert set(recorded) == {"phase", "time", "pid", "reason", "exception_type"}
    assert recorded["phase"] == "failed"
    assert recorded["reason"] == "unexpected-exit"
    assert recorded["exception_type"] == "RuntimeError"
    assert "private" not in json.dumps(recorded)
    assert not list(current.state_dir.glob(".launcher-status-*.tmp"))
    with launcher.PublicLock(current.state_dir), launcher.reserved_socket(current.port):
        pass


def test_serve_passes_same_socket_and_keeps_security_and_local_collection(project, monkeypatch):
    seen = {}
    started = []

    def fake_app(state_dir, **kwargs):
        seen["app"] = (state_dir, kwargs)
        return "fake-app"

    def fake_config(app, **kwargs):
        seen["config"] = (app, kwargs)
        return object()

    class FakeServer:
        def __init__(self, config):
            self.started = False

        async def startup(self, sockets=None):
            seen["sockets"] = sockets
            self.started = True

        def run(self, sockets=None):
            asyncio.run(self.startup(sockets=sockets))

    monkeypatch.setitem(sys.modules, "uvicorn", types.SimpleNamespace(Config=fake_config, Server=FakeServer))
    monkeypatch.setitem(sys.modules, "agent_monitor.server", types.SimpleNamespace(create_app=fake_app))
    old_logging_disable = logging.root.manager.disable
    old_path = sys.path[:]
    current = config(project)
    try:
        with launcher.reserved_socket(0) as listener:
            launcher.serve(current, listener, lambda: started.append(True))
            assert seen["sockets"] == [listener]
    finally:
        logging.disable(old_logging_disable)
        sys.path[:] = old_path
    assert started == [True]
    assert seen["app"] == (current.state_dir, {
        "collect_local": True, "public_url": current.public_url,
        "allowed_hosts": ["localhost", "127.0.0.1", "[::1]", current.hostname],
    })
    assert seen["config"] == ("fake-app", {
        "host": "127.0.0.1", "port": current.port, "workers": 1, "access_log": False,
        "proxy_headers": False, "log_config": None, "use_colors": False, "log_level": "warning",
        "timeout_graceful_shutdown": 30,
    })


@pytest.mark.parametrize("status_write_fails", [False, True])
def test_serve_marks_stopping_before_socket_shutdown_without_blocking_cleanup(project, monkeypatch, status_write_fails):
    current = config(project)
    seen = []
    original_write_status = launcher.write_status

    def write_status(state_dir, phase, **kwargs):
        if phase == "stopping" and status_write_fails:
            raise OSError("synthetic-private-path-must-not-be-logged")
        original_write_status(state_dir, phase, **kwargs)

    class FakeServer:
        def __init__(self, config):
            self.started = False

        async def startup(self, sockets=None):
            self.started = True

        async def shutdown(self, sockets=None):
            recorded = status(current)
            assert recorded["phase"] == ("running" if status_write_fails else "stopping")
            assert set(recorded) == {"phase", "time", "pid", "reason", "exception_type"}
            assert recorded["reason"] == recorded["exception_type"] == ""
            seen.extend(sockets)

        def run(self, sockets=None):
            async def lifecycle():
                await self.startup(sockets=sockets)
                await self.shutdown(sockets=sockets)
            asyncio.run(lifecycle())

    monkeypatch.setattr(launcher, "write_status", write_status)
    monkeypatch.setitem(sys.modules, "uvicorn", types.SimpleNamespace(
        Config=lambda *args, **kwargs: object(), Server=FakeServer))
    monkeypatch.setitem(sys.modules, "agent_monitor.server", types.SimpleNamespace(
        create_app=lambda *args, **kwargs: "fake-app"))
    old_logging_disable = logging.root.manager.disable
    old_path = sys.path[:]
    try:
        with launcher.reserved_socket(0) as listener:
            launcher.serve(current, listener, lambda: launcher.write_status(current.state_dir, "running"))
            assert seen == [listener]
    finally:
        logging.disable(old_logging_disable)
        sys.path[:] = old_path


@windows_only
def test_real_pythonw_no_console_smoke_stops_before_importing_server(project):
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    assert pythonw.is_file()
    with launcher.reserved_socket(0) as busy:
        busy.listen()
        current = config(project, busy.getsockname()[1])
        result = subprocess.run([
            str(pythonw), str(SCRIPT), "--public-url", current.public_url,
            "--state-dir", str(current.state_dir), "--port", str(current.port),
        ], capture_output=True, timeout=10)
    assert result.returncode == 4  # Runtime/SID checks passed; only the deliberate port collision stopped it.
    assert not result.stdout and not result.stderr
    assert status(current)["reason"] == "loopback-port-unavailable"
    assert (current.state_dir / "monitor.sqlite3").stat().st_size == 0
