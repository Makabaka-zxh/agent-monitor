"""Opt-in service integration with synthetic readers; no real CLI or home access."""
import asyncio
import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from unittest.mock import MagicMock

import httpx
import pytest

from agent_monitor import collector, server, usage_service
from agent_monitor.usage_claude_cli import ClaudeQuotaRead
from agent_monitor.usage_service import UsageService, _claude_quota_reader


def config(tmp_path, **changes):
    return {"enabled": True, "executable": str(tmp_path / "claude.exe"),
            "expected_sha256": "A" * 64, **changes}


def save_config(tmp_path, data):
    (tmp_path / "claude-quota-cli.json").write_text(json.dumps(data), encoding="utf-8")


def store():
    result = MagicMock()
    result.snapshot.return_value = {"devices": [{"id": "local-pc", "name": "Local", "local": True}], "tasks": []}
    return result


@pytest.mark.parametrize("changes", [
    {"enabled": False}, {"enabled": 1}, {"enabled": "true"},
    {"executable": "claude.exe"}, {"executable": None}, {"executable": ""},
    {"executable": "C:\\pinned.exe\x00"}, {"expected_sha256": ""},
    {"expected_sha256": "x" * 64}, {"expected_sha256": True}, {"extra": "ignored?"},
])
def test_config_invalid_never_constructs_reader(tmp_path, monkeypatch, changes):
    constructor = MagicMock()
    monkeypatch.setattr(usage_service, "ClaudeCliQuotaReader", constructor)
    save_config(tmp_path, config(tmp_path, **changes))
    assert _claude_quota_reader(tmp_path, True) is None
    constructor.assert_not_called()


def test_config_requires_explicit_local_opt_in_and_strict_bounded_file(tmp_path, monkeypatch):
    constructor = MagicMock()
    monkeypatch.setattr(usage_service, "ClaudeCliQuotaReader", constructor)
    assert _claude_quota_reader(tmp_path, True) is None
    save_config(tmp_path, config(tmp_path))
    assert _claude_quota_reader(tmp_path, False) is None
    assert _claude_quota_reader(tmp_path, True) is constructor.return_value
    constructor.assert_called_once_with(tmp_path / "claude.exe", "a" * 64, enabled=True)
    constructor.reset_mock()
    path = tmp_path / "claude-quota-cli.json"
    for raw in (b"x" * 4097, b"[]", b"{}", b"not json",
                ('{"enabled": false, ' + json.dumps(config(tmp_path))[1:]).encode()):
        path.write_bytes(raw)
        assert _claude_quota_reader(tmp_path, True) is None
    save_config(tmp_path, config(tmp_path))
    monkeypatch.setattr(usage_service, "safe_path", lambda path: False)
    assert _claude_quota_reader(tmp_path, True) is None
    constructor.assert_not_called()


def test_disabled_service_starts_no_reader_or_executor(tmp_path, monkeypatch):
    save_config(tmp_path, config(tmp_path))
    constructor = MagicMock()
    monkeypatch.setattr(usage_service, "ClaudeCliQuotaReader", constructor)
    service = UsageService(tmp_path, store(), False)
    try:
        asyncio.run(service.run_claude_quota())
        assert service._collect_claude_quota() == 300
        assert service._claude_executor is None
        assert not (tmp_path / "claude-quota-status.json").exists()
        constructor.assert_not_called()
    finally:
        service.close()


def test_only_success_updates_numeric_snapshot_and_failure_keeps_observation(tmp_path, monkeypatch):
    reader = MagicMock()
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    now = datetime.now(timezone.utc)
    snapshot = {"observed_at": now.isoformat(), "rate_limits": {
        "five_hour": {"used_percentage": 42, "resets_at": int(now.timestamp()) + 3600}}}
    try:
        reader.poll.return_value = ClaudeQuotaRead("ok", snapshot, 300)
        assert service._collect_claude_quota() == 300
        before = service.summary()
        quota = before["providers"][1]["quotas"][0]
        assert quota["used_percent"] == 42
        assert quota["source_device_id"] == "local-pc"
        for status in ("unauthenticated", "timeout", "throttled", "disabled"):
            # A failed result must remain ignored even if a buggy reader were
            # to attach data. Do not renew its observed_at or invalidate cache.
            reader.poll.return_value = ClaudeQuotaRead(status, snapshot, 600)
            assert service._collect_claude_quota() == 600
            assert service.summary() == before
        reader.poll.return_value = ClaudeQuotaRead("ok", None, 300)
        assert service._collect_claude_quota() == 300
        assert service.summary() == before
        reader.poll.side_effect = RuntimeError("PRIVATE CLI OUTPUT / auth data")
        assert service._collect_claude_quota() == 300
        assert service.summary() == before
    finally:
        service.close()


def test_failure_logs_only_exception_type(tmp_path, monkeypatch, caplog):
    reader = MagicMock()
    reader.poll.side_effect = RuntimeError("PRIVATE RAW AUTH OUTPUT")
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    try:
        assert service._collect_claude_quota() == 300
        assert "RuntimeError" in caplog.text
        assert "PRIVATE" not in caplog.text and "AUTH" not in caplog.text
    finally:
        service.close()


@pytest.mark.parametrize("status,expected", [("not_logged_in", "not_logged_in"), ("timeout", "timeout"),
    ("containment_failed", "containment_failed"), ("invalid_report", "invalid_report"),
    ("PRIVATE account@example.test /path token", "reader_failed"), ({"secret": "PRIVATE"}, "reader_failed")])
def test_local_status_contains_only_whitelisted_diagnostics(tmp_path, monkeypatch, status, expected):
    reader = MagicMock()
    reader.poll.return_value = ClaudeQuotaRead(status, {"raw": "PRIVATE"}, 600)
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    try:
        before = datetime.now(timezone.utc)
        assert service._collect_claude_quota() == 600
        after = datetime.now(timezone.utc)
        raw = (tmp_path / "claude-quota-status.json").read_text()
        result = json.loads(raw)
        assert set(result) == {"status", "phase", "completed_at", "retry_after_seconds", "accepted"}
        assert result["status"] == expected and result["retry_after_seconds"] == 600 and result["accepted"] == 0
        assert result["phase"] == "unknown"
        assert result["completed_at"].endswith("Z")
        stamp = datetime.fromisoformat(result["completed_at"].replace("Z", "+00:00"))
        assert before.timestamp() - .001 <= stamp.timestamp() <= after.timestamp()
        assert "PRIVATE" not in raw and "account@example" not in raw and str(tmp_path) not in raw
        assert "completed_at" not in json.dumps(service.summary())
    finally:
        service.close()


@pytest.mark.parametrize("phase,expected", [(value, value) for value in ("pin", "version", "auth", "usage", "complete")]
    + [(None, "unknown"), ("PRIVATE account@example.test /path token", "unknown"),
       ({"secret": "PRIVATE"}, "unknown"), (True, "unknown")])
def test_status_phase_is_whitelisted_without_changing_timeout_result(tmp_path, monkeypatch, phase, expected):
    reader = MagicMock()
    reader.last_phase = phase
    reader.poll.return_value = ClaudeQuotaRead("timeout", retry_after_seconds=1200)
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    try:
        before = service.summary()
        assert service._collect_claude_quota() == 1200
        raw = (tmp_path / "claude-quota-status.json").read_text()
        recorded = json.loads(raw)
        assert recorded["status"] == "timeout" and recorded["phase"] == expected
        assert recorded["accepted"] == 0 and recorded["retry_after_seconds"] == 1200
        assert "PRIVATE" not in raw and "account@example" not in raw and "/path" not in raw
        assert service.summary() == before and "phase" not in json.dumps(service.summary())
    finally:
        service.close()


def test_status_success_is_written_before_refresh_and_then_atomically_replaced(tmp_path, monkeypatch):
    reader = MagicMock()
    reader.last_phase = "complete"
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    path = tmp_path / "claude-quota-status.json"
    now = datetime.now(timezone.utc)
    reader.poll.return_value = ClaudeQuotaRead("ok", {"observed_at": now.isoformat(), "rate_limits": {
        "five_hour": {"used_percentage": 42, "resets_at": int(now.timestamp()) + 3600}}, "private": "NEVER WRITE"}, 300)
    refresh = service.refresh
    checked = []

    def refresh_after_diagnostic():
        recorded = json.loads(path.read_text())
        assert recorded["status"] == "ok" and recorded["accepted"] == 1 and recorded["phase"] == "complete"
        checked.append(True)
        refresh()

    monkeypatch.setattr(service, "refresh", refresh_after_diagnostic)
    try:
        assert service._collect_claude_quota() == 300
        assert checked == [True]
        previous = path.read_bytes()
        assert b"NEVER WRITE" not in previous and b"rate_limits" not in previous
        replace = os.replace
        replaced = []

        def checked_replace(temporary, destination):
            assert path.read_bytes() == previous
            assert destination == path
            assert json.loads(temporary.read_bytes())["status"] == "not_logged_in"
            replaced.append(True)
            replace(temporary, destination)

        monkeypatch.setattr(usage_service.os, "replace", checked_replace)
        reader.poll.return_value = ClaudeQuotaRead("not_logged_in", retry_after_seconds=600)
        assert service._collect_claude_quota() == 600
        assert replaced == [True]
        assert json.loads(path.read_text())["status"] == "not_logged_in"
        assert not list(tmp_path.glob(".claude-quota-status.json-*.tmp"))
        assert service.summary()["providers"][1]["quotas"][0]["used_percent"] == 42
    finally:
        service.close()


def test_status_write_failure_does_not_drop_success_or_change_retry(tmp_path, monkeypatch):
    reader = MagicMock()
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    now = datetime.now(timezone.utc)
    reader.poll.return_value = ClaudeQuotaRead("ok", {"observed_at": now.isoformat(), "rate_limits": {
        "seven_day": {"used_percentage": 75, "resets_at": None}}}, 300)
    monkeypatch.setattr(usage_service, "atomic_write", MagicMock(side_effect=OSError("PRIVATE write failure")))
    try:
        assert service._collect_claude_quota() == 300
        before = service.summary()
        assert before["providers"][1]["quotas"][0]["used_percent"] == 75
        reader.poll.return_value = ClaudeQuotaRead("timeout", retry_after_seconds=1200)
        assert service._collect_claude_quota() == 1200
        assert service.summary() == before
        assert not (tmp_path / "claude-quota-status.json").exists()
    finally:
        service.close()


@pytest.mark.parametrize("phase,expected,accepted", [("poll", "reader_failed", 0),
    ("ingest", "ingest_failed", 0), ("refresh", "refresh_failed", 1)])
def test_status_errors_are_fixed_enums_without_raw_exception(tmp_path, monkeypatch, phase, expected, accepted):
    reader = MagicMock()
    reader.last_phase = "usage"
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    now = datetime.now(timezone.utc)
    reader.poll.return_value = ClaudeQuotaRead("ok", {"observed_at": now.isoformat(), "rate_limits": {
        "five_hour": {"used_percentage": 9, "resets_at": None}}}, 300)
    failure = MagicMock(side_effect=RuntimeError("PRIVATE /path/auth token"))
    if phase == "poll":
        reader.poll.side_effect = failure
    elif phase == "ingest":
        monkeypatch.setattr(service.ledger, "ingest_claude_quota", failure)
    else:
        monkeypatch.setattr(service, "refresh", failure)
    try:
        assert service._collect_claude_quota() == 300
        raw = (tmp_path / "claude-quota-status.json").read_text()
        recorded = json.loads(raw)
        assert recorded["status"] == expected and recorded["accepted"] == accepted
        assert recorded["phase"] == "usage"
        assert "PRIVATE" not in raw and "/path" not in raw and "token" not in raw
    finally:
        service.close()


@pytest.mark.parametrize("delay,expected", [(0, 300), (600, 600), (3600, 3600), (999999, 3600), (True, 300)])
def test_loop_honors_bounded_retry_delay_without_rapid_repoll(tmp_path, monkeypatch, delay, expected):
    reader = MagicMock()
    reader.poll.return_value = ClaudeQuotaRead("unauthenticated", retry_after_seconds=delay)
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    service = UsageService(tmp_path, store(), True)
    waits = []

    async def stop_at_sleep(seconds):
        waits.append(seconds)
        raise asyncio.CancelledError

    monkeypatch.setattr(usage_service.asyncio, "sleep", stop_at_sleep)
    try:
        with pytest.raises(asyncio.CancelledError):
            asyncio.run(service.run_claude_quota())
        assert waits == [expected]
        reader.poll.assert_called_once()
    finally:
        service.close()


@pytest.mark.parametrize("latest", ["ordinary", "source_removed", "failed"])
def test_late_refresh_cannot_replace_latest_or_revive_a_removed_source(tmp_path, monkeypatch, latest):
    local_store = store()
    service = UsageService(tmp_path, local_store, False)
    before = service.summary()
    local = {"id": "local-pc", "name": "Local", "local": True}
    removed = {"id": "removed-pc", "name": "Removed", "local": False}
    local_store.snapshot.return_value = {"devices": [local, removed], "tasks": []}
    entered, release = threading.Event(), threading.Event()
    calls = 0

    def summary(*args, device_ids=None, **kwargs):
        nonlocal calls
        calls += 1
        index = calls
        if index == 1:
            entered.set()
            assert release.wait(3)
        elif latest == "failed":
            raise RuntimeError("synthetic newer refresh failed")
        return {"providers": [{"tool": "claude", "today_tokens": index,
                               "quotas": [{"source_device_id": source} for source in device_ids if source != "local"]}]}

    monkeypatch.setattr(service.ledger, "summary", summary)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            older = pool.submit(service.refresh)
            assert entered.wait(1)
            try:
                # Cache reads stay responsive even while a build is blocked.
                assert service.summary() == before
                if latest == "source_removed":
                    local_store.snapshot.return_value = {"devices": [local], "tasks": []}
                if latest == "failed":
                    with pytest.raises(RuntimeError):
                        service.refresh()
                    expected = before
                else:
                    service.refresh()
                    expected = service.summary()
                    assert expected["providers"][0]["today_tokens"] == 2
                    if latest == "source_removed":
                        assert {item["source_device_id"] for item in expected["providers"][0]["quotas"]} == {"local-pc"}
            finally:
                release.set()
            older.result(timeout=1)
            assert service.summary() == expected
    finally:
        service.close()


def test_server_slow_quota_is_independent_and_shutdown_drains_before_close(tmp_path, monkeypatch):
    save_config(tmp_path, config(tmp_path))
    reader = MagicMock()
    monkeypatch.setattr(usage_service, "ClaudeCliQuotaReader", lambda *args, **kwargs: reader)
    monkeypatch.setattr(collector, "collect_snapshot", lambda **kwargs: {"tasks": [], "sources": []})
    app = server.create_app(tmp_path, collect_local=True)

    async def exercise():
        loop = asyncio.get_running_loop()
        # With only one shared executor thread, a default-executor quota poll
        # would block the original collector. The dedicated executor must not.
        loop.set_default_executor(ThreadPoolExecutor(max_workers=1))
        entered, collected = asyncio.Event(), asyncio.Event()
        release, finished = threading.Event(), threading.Event()
        order = []
        close = app.state.usage.ledger.close

        def poll():
            loop.call_soon_threadsafe(entered.set)
            assert release.wait(5), "synthetic quota poll release timed out"
            order.append("poll-finished")
            finished.set()
            return ClaudeQuotaRead("unauthenticated", retry_after_seconds=300)

        def collect():
            assert app.state.store.has_account() is False
            loop.call_soon_threadsafe(collected.set)

        def close_ledger():
            assert finished.is_set()
            order.append("ledger-closed")
            close()

        reader.poll.side_effect = poll
        monkeypatch.setattr(app.state.usage, "collect", collect)
        monkeypatch.setattr(app.state.usage.ledger, "close", close_ledger)
        lifecycle = app.router.lifespan_context(app)
        closing = None
        try:
            await lifecycle.__aenter__()
            await asyncio.wait_for(entered.wait(), 1)
            await asyncio.wait_for(collected.wait(), 1)
            assert not finished.is_set()
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                response = await asyncio.wait_for(client.get("/api/bootstrap"), 1)
            assert response.status_code == 200
            assert response.json()["needs_setup"] is True
            # Repeated cancellation must not close SQLite under the CLI worker.
            closing = asyncio.create_task(lifecycle.__aexit__(None, None, None))
            await asyncio.sleep(.03)
            closing.cancel()
            await asyncio.sleep(.03)
            assert not closing.done() and order == []
            reader.poll.assert_called_once()
        finally:
            release.set()
            if closing is None:
                closing = asyncio.create_task(lifecycle.__aexit__(None, None, None))
            await asyncio.wait_for(closing, 2)
        assert order == ["poll-finished", "ledger-closed"]

    asyncio.run(exercise())
