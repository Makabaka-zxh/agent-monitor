"""Unit tests for Windows collector policy and service metadata."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import pytest

WIN_SCRIPTS = Path(__file__).resolve().parents[1] / "windows" / "scripts"


def _load(name: str):
    spec = importlib.util.spec_from_file_location(f"win_{name}", WIN_SCRIPTS / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_windows_server_origin_normalization():
    service = _load("collector_service")
    assert service.normalize_server_origin("https://Monitor.Example.com") == "https://monitor.example.com"
    assert service.normalize_server_origin("https://monitor.example.com:8443/") == "https://monitor.example.com:8443"
    with pytest.raises(ValueError):
        service.normalize_server_origin("http://monitor.example.com")
    with pytest.raises(ValueError):
        service.normalize_server_origin("https://monitor.example.com/subpath")


def test_windows_captured_environment_filters_unrelated_secrets():
    service = _load("collector_service")
    env = service.captured_environment(
        {
            "HTTPS_PROXY": "http://proxy.invalid:8080",
            "CODEX_HOME": "C:\\Users\\example\\.codex",
            "UNRELATED_TOKEN": "should-not-capture",
            "PATH": "C:\\Windows\\System32",
        },
        previous={"CLAUDE_CONFIG_DIR": "C:\\Users\\example\\.claude"},
    )
    assert env["HTTPS_PROXY"] == "http://proxy.invalid:8080"
    assert env["CODEX_HOME"] == "C:\\Users\\example\\.codex"
    assert env["CLAUDE_CONFIG_DIR"] == "C:\\Users\\example\\.claude"
    assert "UNRELATED_TOKEN" not in env


def test_windows_mode_read_write_and_status_json(tmp_path, capsys):
    policy = _load("collector_policy")
    service = _load("collector_service")
    mode_file = tmp_path / "mode.json"
    assert policy.read_mode(mode_file) == "status-only"
    policy.write_mode(mode_file, "full")
    assert policy.read_mode(mode_file) == "full"
    assert policy.run_options("full") == {"status_only": False, "allow_replies": True}

    if sys.platform == "win32":
        locs = service.paths(tmp_path)
        for key in ("base", "state", "logs", "releases", "bin", "hooks"):
            locs[key].mkdir(parents=True, exist_ok=True)
        locs["owner"].write_text(json.dumps({"owner": service.OWNER}), encoding="utf-8")
        policy.write_mode(locs["mode"], "results")
        service.status(locs, "https://monitor.example.com")
        out = json.loads(capsys.readouterr().out)
        assert out["installed"] is True
        assert out["paired"] is False
        assert out["server_scope_version"] == 1
        assert out["mode"] == "results"
        assert set(out["supported_modes"]) == {"status-only", "results", "full"}


def test_windows_stop_never_terminates_unmanaged_recycled_pid(tmp_path, monkeypatch):
    import os

    service = _load("collector_service")
    locs = service.paths(tmp_path)
    for key in ("base", "state", "logs", "releases", "bin", "hooks"):
        locs[key].mkdir(parents=True, exist_ok=True)
    pid_file = locs["state"] / "collector.pid"
    pid_file.write_text(str(os.getpid()), encoding="utf-8")

    terminated: list[int] = []
    monkeypatch.setattr(service, "_terminate_pid", lambda pid: terminated.append(pid))
    # Even if lock appears held transiently, current test Python is outside locs["releases"]
    calls = {"count": 0}

    def fake_locked(_locations):
        calls["count"] += 1
        return calls["count"] == 1

    monkeypatch.setattr(service, "collector_locked", fake_locked)
    service.stop(locs, clear_autostart=False)
    assert terminated == []
    assert not pid_file.exists()
