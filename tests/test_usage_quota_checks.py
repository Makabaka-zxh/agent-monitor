"""Public quota-check metadata stays separate from observations and diagnostics."""
import json
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from unittest.mock import MagicMock

import pytest

from agent_monitor import usage_service
from agent_monitor.usage_claude_cli import ClaudeQuotaRead
from agent_monitor.usage_service import UsageService


LOCAL = {"id": "local-pc", "name": "Local", "local": True}
CHECK_KEYS = {"tool", "source_device_id", "source_name", "state", "last_attempt_at", "retry_after_seconds"}


@pytest.fixture
def service(tmp_path, monkeypatch):
    reader = MagicMock()
    reader.poll.return_value = ClaudeQuotaRead("no_live_limits", retry_after_seconds=600)
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    store = MagicMock()
    store.snapshot.return_value = {"devices": [LOCAL], "tasks": []}
    value = UsageService(tmp_path, store, True)
    try:
        yield value
    finally:
        value.close()


def test_only_detail_exposes_checks_after_source_published(service):
    assert service.detail_summary()["quota_checks"] == []
    service.refresh()
    plain = service.summary()
    detail = service.detail_summary()
    assert detail.pop("quota_checks") == [{"tool": "claude", "source_device_id": "local-pc",
        "source_name": "Local", "state": "waiting", "last_attempt_at": None, "retry_after_seconds": 300}]
    assert detail == plain
    assert "quota_checks" not in service.enrich({"tasks": []})["usage"]
    copied = service.detail_summary()
    copied["quota_checks"][0]["source_name"] = "Edited"
    copied["providers"].clear()
    assert service.detail_summary()["quota_checks"][0]["source_name"] == "Local"
    assert service.summary() == plain


@pytest.mark.parametrize("enabled,reader", [(False, None), (False, MagicMock()), (True, None)])
def test_disabled_or_unconfigured_returns_empty_checks(tmp_path, monkeypatch, enabled, reader):
    monkeypatch.setattr(usage_service, "_claude_quota_reader", lambda *args: reader)
    store = MagicMock()
    store.snapshot.return_value = {"devices": [LOCAL], "tasks": []}
    value = UsageService(tmp_path, store, enabled)
    try:
        value.refresh()
        assert value.detail_summary()["quota_checks"] == []
    finally:
        value.close()


@pytest.mark.parametrize("status,expected", [
    ("no_live_limits", "no_live_data"), ("no_numeric_windows", "no_live_data"),
    ("not_logged_in", "sign_in_required"), ("api_key_only", "sign_in_required"),
    ("invalid_pin", "update_required"), ("pin_mismatch", "update_required"),
    ("version_mismatch", "update_required"), ("executable_unavailable", "update_required"),
    ("timeout", "unavailable"), ("auth_unavailable", "unavailable"), ("invalid_report", "unavailable"),
    ("unsupported_provider", "unavailable"), ("unsupported_account", "unavailable"),
    ("PRIVATE secret@example.test /secret/path", "unavailable"), ({"PRIVATE": "token"}, "unavailable"),
    (None, "unavailable"), (True, "unavailable"), ("ok", "unavailable"),
])
def test_state_allowlist_never_exposes_raw_diagnostics_or_updates_usage(service, status, expected):
    service.refresh()
    before = service.summary()
    service.claude_quota_reader.last_phase = "PRIVATE secret@example.test /secret/path"
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead(status, retry_after_seconds=600)
    started = datetime.now(timezone.utc)
    assert service._collect_claude_quota() == 600
    check = service.detail_summary()["quota_checks"][0]
    assert set(check) == CHECK_KEYS and check["state"] == expected
    attempted = datetime.fromisoformat(check["last_attempt_at"].replace("Z", "+00:00"))
    assert started.timestamp() - .001 <= attempted.timestamp() <= datetime.now(timezone.utc).timestamp()
    assert check["retry_after_seconds"] == 600
    assert service.summary() == before
    encoded = json.dumps(service.detail_summary())
    assert not any(value in encoded for value in ("PRIVATE", "secret@", "/secret", "last_phase", "completed_at"))


@pytest.mark.parametrize("delay,expected", [(0, 300), (-3, 300), (600, 600), (999999, 3600),
    (True, 300), (None, 300), ("PRIVATE", 300), ({"secret": "PRIVATE"}, 300)])
def test_retry_delay_is_a_bounded_integer(service, delay, expected):
    service.refresh()
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead("no_live_limits", retry_after_seconds=delay)
    assert service._collect_claude_quota() == expected
    assert service.detail_summary()["quota_checks"][0]["retry_after_seconds"] == expected


def test_success_and_failure_attempt_times_do_not_replace_observed_at(service):
    now = datetime.now(timezone.utc)
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead("ok", {
        "observed_at": now.isoformat(), "rate_limits": {
            "five_hour": {"used_percentage": 42, "resets_at": int(now.timestamp()) + 3600}}}, 300)
    service._collect_claude_quota()
    original = service.summary()
    check = service.detail_summary()["quota_checks"][0]
    assert check["state"] == "updated" and check["last_attempt_at"] is not None
    assert original["providers"][1]["quotas"][0]["used_percent"] == 42
    for status in ("no_live_limits", "timeout", "not_logged_in"):
        service.claude_quota_reader.poll.return_value = ClaudeQuotaRead(status, retry_after_seconds=600)
        service._collect_claude_quota()
        assert service.summary() == original
        assert "quota_checks" not in service.enrich({"tasks": []})["usage"]


@pytest.mark.parametrize("status", ["busy", "throttled", "disabled"])
def test_skipped_poll_does_not_create_or_renew_attempt(service, status):
    service.refresh()
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead(status, retry_after_seconds=600)
    initial = service.detail_summary()["quota_checks"]
    service._collect_claude_quota()
    assert service.detail_summary()["quota_checks"] == initial
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead("no_live_limits", retry_after_seconds=1200)
    service._collect_claude_quota()
    recorded = service.detail_summary()["quota_checks"]
    service.claude_quota_reader.poll.return_value = ClaudeQuotaRead(status, retry_after_seconds=600)
    service._collect_claude_quota()
    assert service.detail_summary()["quota_checks"] == recorded


def test_exception_is_sanitized_and_diagnostic_write_failure_does_not_drop_check(service, monkeypatch):
    service.refresh()
    monkeypatch.setattr(usage_service, "atomic_write", MagicMock(side_effect=OSError("PRIVATE diagnostic failure")))
    service.claude_quota_reader.poll.side_effect = RuntimeError("PRIVATE account/password /path")
    assert service._collect_claude_quota() == 300
    check = service.detail_summary()["quota_checks"][0]
    assert check["state"] == "unavailable" and check["last_attempt_at"] is not None
    assert set(check) == CHECK_KEYS and "PRIVATE" not in json.dumps(check)


def test_detail_never_reads_private_diagnostic_file(service):
    service.refresh()
    service._claude_status_path.write_text(json.dumps({"status": "ok", "phase": "PRIVATE",
        "completed_at": "PRIVATE", "retry_after_seconds": 1, "accepted": 2}))
    assert service.detail_summary()["quota_checks"][0]["state"] == "waiting"
    service._collect_claude_quota()
    recorded = service.detail_summary()["quota_checks"]
    service._claude_status_path.write_text("PRIVATE malformed diagnostic")
    assert service.detail_summary()["quota_checks"] == recorded


def test_initial_poll_may_finish_before_first_local_source_is_identified(service):
    service.store.snapshot.return_value = {"devices": [], "tasks": []}
    service.refresh()
    service._collect_claude_quota()
    assert service.detail_summary()["quota_checks"] == []
    service.store.snapshot.return_value = {"devices": [LOCAL], "tasks": []}
    service.refresh()
    check = service.detail_summary()["quota_checks"][0]
    assert check["state"] == "no_live_data" and check["last_attempt_at"] is not None


@pytest.mark.parametrize("latest_fails", [False, True])
def test_revocation_invalidates_late_refresh_even_if_latest_aggregation_fails(service, monkeypatch, latest_fails):
    service.refresh()
    service._collect_claude_quota()
    entered, release = threading.Event(), threading.Event()
    summary = service.ledger.summary
    calls = 0

    def blocked_summary(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            entered.set()
            assert release.wait(3)
        elif latest_fails:
            raise RuntimeError("synthetic failed aggregation")
        return summary(*args, **kwargs)

    monkeypatch.setattr(service.ledger, "summary", blocked_summary)
    with ThreadPoolExecutor(max_workers=1) as pool:
        older = pool.submit(service.refresh)
        assert entered.wait(1)
        try:
            service.store.snapshot.return_value = {"devices": [], "tasks": []}
            if latest_fails:
                with pytest.raises(RuntimeError):
                    service.refresh()
            else:
                service.refresh()
            assert service.detail_summary()["quota_checks"] == []
        finally:
            release.set()
        older.result(timeout=1)
    assert service.detail_summary()["quota_checks"] == []


def test_revoked_source_does_not_transfer_late_poll_to_replacement(service):
    service.refresh()
    entered, release = threading.Event(), threading.Event()

    def blocked_poll():
        entered.set()
        assert release.wait(3)
        return ClaudeQuotaRead("no_live_limits", retry_after_seconds=600)

    service.claude_quota_reader.poll.side_effect = blocked_poll
    with ThreadPoolExecutor(max_workers=1) as pool:
        older = pool.submit(service._collect_claude_quota)
        assert entered.wait(1)
        try:
            service.store.snapshot.return_value = {"devices": [], "tasks": []}
            service.refresh()
            assert service.detail_summary()["quota_checks"] == []
            service.store.snapshot.return_value = {"devices": [{**LOCAL, "id": "replacement", "name": "New"}], "tasks": []}
            service.refresh()
        finally:
            release.set()
        assert older.result(timeout=1) == 600
    check = service.detail_summary()["quota_checks"][0]
    assert check["source_device_id"] == "replacement" and check["source_name"] == "New"
    assert check["state"] == "waiting" and check["last_attempt_at"] is None
