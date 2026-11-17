"""Versioned anonymous wire: synthetic databases, no network or account reads."""
import copy
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from agent_monitor.usage_ledger import UsageLedger, _hash, _iso


NOW = datetime(2026, 10, 27, 8, tzinfo=timezone.utc)


@pytest.fixture
def ledgers(tmp_path):
    opened = []

    def make(name):
        ledger = UsageLedger(tmp_path / (name + ".sqlite3"))
        opened.append(ledger)
        return ledger

    yield make
    for ledger in reversed(opened):
        ledger.close()


def provider(ledger, tool="claude", sources=None):
    return next(row for row in ledger.summary(NOW, sources)["providers"] if row["tool"] == tool)


def meter(bucket="account", *, model=None, minutes=10080, percent=25, kind="weekly_all"):
    return {"kind": kind, "bucket_id": bucket, "bucket_label": "Sonnet 4.6" if model else "全部模型", "model": model,
            "window_minutes": minutes, "used_percentage": percent, "resets_at": None}


def observe(ledger, meters, seconds=0, complete=True):
    return ledger.ingest_claude_quota({"observed_at": _iso(NOW + timedelta(seconds=seconds)), "quota_windows": meters,
                                      "snapshot_complete": complete}, NOW)


def batch(*, events=(), quotas=(), snapshots=()):
    return {"version": 2, "events": list(events), "quotas": list(quotas), "quota_snapshots": list(snapshots)}


def accept(ledger, payload, source="one"):
    result = ledger.ingest_batch(source, payload, NOW)
    assert result == {"version": 2, "accepted": sum(len(payload.get(key, [])) for key in ("events", "quotas", "quota_snapshots")),
                      "rejected": 0}
    return result


def legacy(payload):
    result = copy.deepcopy(payload)
    result["version"] = 1
    result.pop("quota_snapshots", None)
    for event in result.get("events", []):
        for key in ("model", "model_conflict", "model_input_tokens", "model_output_tokens"):
            event.pop(key, None)
    for quota in result.get("quotas", []):
        for key in ("model", "bucket_id", "bucket_label", "present"):
            quota.pop(key, None)
    return result


def claude_event(ledger, identity="response-one", model="claude-sonnet-4-6", final=True, output=20):
    row = {"type": "assistant", "timestamp": _iso(NOW), "sessionId": "synthetic-session", "message": {
        "id": identity, "model": model, "stop_reason": "end_turn" if final else None,
        "content": [{"text": "PRIVATE_TEXT_NOT_FOR_WIRE"}],
        "usage": {"input_tokens": 10, "output_tokens": output, "cache_read_input_tokens": 100, "cache_creation_input_tokens": 40}}}
    with ledger.lock, ledger.db:
        ledger._record(row, "claude", {}, Path("synthetic.jsonl"), NOW)


def codex_events(ledger):
    meta = {}
    rows = [{"type": "session_meta", "timestamp": _iso(NOW - timedelta(minutes=1)), "payload": {"id": "synthetic-codex"}}]
    for seconds, model, total, last in ((-20, "gpt-5.6", (100, 20), (100, 20)),
                                      (-10, "gpt-6.1", (110, 40), (10, 20))):
        rows.append({"type": "turn_context", "payload": {"model": model}})
        rows.append({"type": "event_msg", "timestamp": _iso(NOW + timedelta(seconds=seconds)), "payload": {
            "type": "token_count", "info": {
                "total_token_usage": {"input_tokens": total[0], "output_tokens": total[1], "total_tokens": sum(total)},
                "last_token_usage": {"input_tokens": last[0], "output_tokens": last[1], "total_tokens": sum(last)}}}})
    with ledger.lock, ledger.db:
        for row in rows:
            ledger._record(row, "codex", meta, Path("synthetic.jsonl"), NOW)


def test_v2_roundtrip_model_scopes_and_opaque_metadata_without_private_fields(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source)
    observe(source, [meter(), meter("sonnet", model="claude-sonnet-4-6", percent=70, kind="weekly_scoped")])
    payload = source.export_batch()
    assert payload["version"] == 2
    assert "PRIVATE" not in str(payload) and "synthetic-session" not in str(payload)
    accept(hub, payload)
    accept(hub, payload)
    accept(hub, payload, "two")
    data = provider(hub, sources=["one", "two"])
    assert data["history"]["days"][-1]["tokens"] == 170
    assert data["history"]["days"][-1]["models"] == [{"model": "claude-sonnet-4-6", "tokens": 170}]
    assert len(data["quotas"]) == 4
    assert {row["model"] for row in data["quotas"]} == {None, "claude-sonnet-4-6"}
    assert all(row["bucket_id"] and len(row["bucket_id"]) == 64 for row in data["quotas"])
    # Imported observations never become outbound local data.
    assert not hub.export_batch()["events"] and not hub.export_quota_batch()["quotas"]
    assert not hub.export_quota_batch()["quota_snapshots"]


def test_v1_still_works_but_cannot_silently_ack_v2_fields(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source); observe(source, [meter()])
    payload = source.export_batch()
    assert hub.ingest_batch("one", legacy(payload), NOW) == {"accepted": 2, "rejected": 0}
    mixed = copy.deepcopy(payload); mixed["version"] = 1
    assert hub.ingest_batch("one", mixed, NOW) == {"accepted": 0, "rejected": 1}
    mixed.pop("quota_snapshots")
    assert hub.ingest_batch("one", mixed, NOW) == {"accepted": 0, "rejected": 2}
    assert provider(hub)["history"]["days"][-1]["models"] == []
    accept(hub, payload)
    assert provider(hub)["history"]["days"][-1]["models"] == [{"model": "claude-sonnet-4-6", "tokens": 170}]


def test_codex_v2_cumulative_difference_and_duplicate_models_do_not_double_count(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    codex_events(source)
    payload = source.export_batch()
    accept(hub, payload); accept(hub, payload)
    day = provider(hub, "codex")["history"]["days"][-1]
    assert day["tokens"] == 150 and day["unknown_model_tokens"] == 0
    assert {item["model"]: item["tokens"] for item in day["models"]} == {"gpt-5.6": 120, "gpt-6.1": 30}


def test_v1_codex_upgrade_reconstructs_components_not_just_same_total(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    codex_events(source)
    payload = source.export_batch()
    assert hub.ingest_batch("one", legacy(payload), NOW)["accepted"] == 2
    wrong = copy.deepcopy(payload["events"][1]); wrong.update(model_input_tokens=20, model_output_tokens=10)
    accept(hub, batch(events=[wrong]))
    assert provider(hub, "codex")["history"]["days"][-1]["models"] == []
    accept(hub, payload)
    assert sum(row["tokens"] for row in provider(hub, "codex")["history"]["days"][-1]["models"]) == 150


def test_complete_empty_snapshot_clears_only_its_authenticated_source_and_tool(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter(), meter("sonnet", model="claude-sonnet-4-6", kind="weekly_scoped")], -30)
    before = source.export_quota_batch()
    accept(hub, before, "one"); accept(hub, before, "two")
    observe(source, [], -10)
    snapshot = source.export_quota_batch()["quota_snapshots"][0]
    snapshot["device_id"] = "two"  # Ignored: authority belongs to the caller.
    accept(hub, batch(snapshots=[snapshot]), "one")
    assert provider(hub, sources=["one"])["quotas"] == []
    assert len(provider(hub, sources=["two"])["quotas"]) == 2
    accept(hub, before, "one")
    assert provider(hub, sources=["one"])["quotas"] == []


def test_snapshot_first_allows_same_timestamp_active_pages_only(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter(), meter("sonnet", model="claude-sonnet-4-6", kind="weekly_scoped")])
    exported = source.export_quota_batch()
    accept(hub, batch(snapshots=exported["quota_snapshots"]))
    assert provider(hub)["quotas"] == []
    for quota in reversed(exported["quotas"]):
        accept(hub, batch(quotas=[quota]))
    assert len(provider(hub)["quotas"]) == 2
    unknown = {**exported["quotas"][0], "key": _hash("not-in-complete-snapshot")}
    accept(hub, batch(quotas=[unknown]))
    old = {**unknown, "observed_at": _iso(NOW - timedelta(seconds=1))}
    accept(hub, batch(quotas=[old]))
    assert len(provider(hub)["quotas"]) == 2


def test_fast_path_and_fifo_pagination_do_not_truncate_complete_membership(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    windows = [meter(f"model-{index}", model=f"claude-sonnet-{index}", kind="weekly_scoped") for index in range(64)]
    observe(source, windows)
    fast = source.export_quota_batch(limit=20)
    assert len(fast["quotas"]) == 20
    assert len(fast["quota_snapshots"][0]["active_keys"]) == 64
    accept(hub, batch(snapshots=fast["quota_snapshots"]))
    accept(hub, fast)
    pages = []
    while True:
        page = source.export_batch(limit=9)
        if not page["cursor"]:
            break
        pages.append(page)
        source.ack_export(page["cursor"])
    for page in reversed(pages):
        accept(hub, page)
    assert len(provider(hub)["quotas"]) == 64


def test_scoped_known_keys_retract_legacy_null_bucket_without_touching_other_bucket(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    limits = {"limit_id": "codex", "primary": {"used_percent": 20, "window_minutes": 300}, "secondary": None}
    with source.lock, source.db:
        source._codex_quotas(limits, _iso(NOW - timedelta(seconds=30)), NOW)
    original = source.export_quota_batch()
    assert hub.ingest_batch("one", legacy(original), NOW)["accepted"] == 1
    with source.lock, source.db:
        source._codex_quotas({"limit_id": "codex", "primary": None, "secondary": None}, _iso(NOW - timedelta(seconds=10)), NOW)
    snapshot = source.export_quota_batch()["quota_snapshots"][0]
    assert len(snapshot["known_keys"]) == 2 and snapshot["active_keys"] == []
    accept(hub, batch(snapshots=[snapshot]))
    assert provider(hub, "codex")["quotas"] == []
    # Retry of legacy payload cannot resurrect its now-absent primary.
    hub.ingest_batch("one", legacy(original), NOW)
    assert provider(hub, "codex")["quotas"] == []
    another = ledgers("another-hub")
    renamed_bucket = {**original["quotas"][0], "bucket_id": _hash("other-bucket")}
    accept(another, batch(quotas=[renamed_bucket]))
    accept(another, batch(snapshots=[snapshot]))
    assert len(provider(another, "codex")["quotas"]) == 1


@pytest.mark.parametrize("change", [
    {"complete": False}, {"complete": 1}, {"complete": None},
    {"active_keys": None}, {"active_keys": [True]},
    {"active_keys": ["x" * 64, "x" * 64]},
    {"active_keys": [_hash(i) for i in range(65)]},
    {"known_keys": ["bad"]}, {"bucket_id": "private raw identifier"},
])
def test_invalid_or_partial_snapshot_never_removes_or_advances_watermark(ledgers, change):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter()], -20)
    exported = source.export_quota_batch()
    accept(hub, batch(quotas=exported["quotas"]))
    snapshot = {**exported["quota_snapshots"][0], "observed_at": _iso(NOW), "active_keys": [], **change}
    result = hub.ingest_batch("one", batch(snapshots=[snapshot]), NOW)
    assert result == {"version": 2, "accepted": 0, "rejected": 1}
    assert len(provider(hub)["quotas"]) == 1
    assert hub.db.execute("SELECT count(*) FROM usage_quota_snapshots").fetchone()[0] == 0


def test_same_timestamp_conflicting_active_membership_rejects_without_overwrite(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter()])
    exported = source.export_quota_batch()
    accept(hub, exported)
    conflicting = {**exported["quota_snapshots"][0], "active_keys": []}
    result = hub.ingest_batch("one", batch(snapshots=[conflicting]), NOW)
    assert result["rejected"] == 1 and result["accepted"] == 0
    assert len(provider(hub)["quotas"]) == 1
    accept(hub, exported)


def test_bad_numeric_member_prevents_complete_snapshot_deletion_in_same_batch(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter()], -20)
    exported = source.export_quota_batch()
    accept(hub, exported)
    malformed = {**exported["quotas"][0], "used_percent": "not-numeric", "observed_at": _iso(NOW)}
    removed = {**exported["quota_snapshots"][0], "active_keys": [], "observed_at": _iso(NOW)}
    result = hub.ingest_batch("one", batch(quotas=[malformed], snapshots=[removed]), NOW)
    assert result == {"version": 2, "accepted": 0, "rejected": 2}
    assert len(provider(hub)["quotas"]) == 1


def test_legacy_numeric_refresh_preserves_known_model_but_explicit_v2_null_clears(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter("sonnet", model="claude-sonnet-4-6", kind="weekly_scoped")], -20)
    exported = source.export_quota_batch()
    accept(hub, exported)
    quota = {**exported["quotas"][0], "observed_at": _iso(NOW - timedelta(seconds=10)), "used_percent": 40}
    hub.ingest_batch("one", legacy(batch(quotas=[quota])), NOW)
    assert provider(hub)["quotas"][0]["model"] == "claude-sonnet-4-6"
    assert provider(hub)["quotas"][0]["bucket_label"] == "Sonnet 4.6"
    accept(hub, batch(quotas=[{**quota, "observed_at": _iso(NOW), "model": None, "bucket_label": None}]))
    assert provider(hub)["quotas"][0]["model"] is None


def test_old_provisional_cannot_change_final_model_or_propagate_conflict(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source)
    payload = source.export_batch()
    accept(hub, payload)
    for changes in ({"model": "claude-opus-4-6"}, {"model": None, "model_conflict": True}):
        provisional = {**payload["events"][0], **changes, "final": False}
        accept(hub, batch(events=[provisional]))
    assert provider(hub)["history"]["days"][-1]["models"] == [{"model": "claude-sonnet-4-6", "tokens": 170}]


def test_final_model_conflict_stays_unknown_across_wire_and_retries(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source)
    original = source.export_batch()
    accept(hub, original)
    claude_event(source, model="claude-opus-4-6")
    conflict = source.export_batch()
    assert conflict["events"][0]["model_conflict"] is True and conflict["events"][0]["model"] is None
    accept(hub, conflict); accept(hub, original)
    day = provider(hub)["history"]["days"][-1]
    assert day["tokens"] == 170 and day["models"] == [] and day["unknown_model_tokens"] == 170


def test_imported_snapshot_never_enters_local_export_and_empty_snapshot_has_a_cursor(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [])
    payload = source.export_batch()
    assert payload["cursor"] > 0 and not payload["events"] and not payload["quotas"] and payload["quota_snapshots"]
    accept(hub, payload)
    assert not hub.export_batch()["quota_snapshots"] and not hub.export_quota_batch()["quota_snapshots"]


def test_v2_entry_count_includes_snapshots_and_rejects_malformed_version(ledgers):
    hub = ledgers("hub")
    for version in (True, "2", 3, None):
        assert hub.ingest_batch("one", {"version": version}, NOW) == {"accepted": 0, "rejected": 1}
    value = {"tool": "claude", "bucket_id": "*", "complete": True, "observed_at": _iso(NOW), "active_keys": []}
    assert hub.ingest_batch("one", batch(snapshots=[value] * 201), NOW) == {"version": 2, "accepted": 0, "rejected": 1}


def test_new_local_conflict_is_requeued_after_ack_and_remote_model_becomes_unknown(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source)
    original = source.export_batch()
    accept(hub, original); source.ack_export(original["cursor"])
    assert source.export_batch()["events"] == []
    claude_event(source, output=21)  # Same final response, contradictory vector.
    changed = source.export_batch()
    assert changed["cursor"] and len(changed["events"]) == 1
    assert changed["events"][0]["event_key"] == original["events"][0]["event_key"]
    assert changed["events"][0]["model_conflict"] is True
    accept(hub, changed); source.ack_export(changed["cursor"])
    day = provider(hub)["history"]["days"][-1]
    assert day["tokens"] == 170 and day["unknown_model_tokens"] == 170 and day["models"] == []
    claude_event(source, output=21)
    assert source.export_batch()["events"] == []


def test_observed_local_model_enrichment_requeues_acknowledged_event_without_new_usage(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source, model=None)
    original = source.export_batch()
    accept(hub, original); source.ack_export(original["cursor"])
    assert source.export_batch()["events"] == []
    claude_event(source, model="claude-sonnet-4-6")
    enriched = source.export_batch()
    assert enriched["cursor"] and len(enriched["events"]) == 1
    assert enriched["events"][0]["event_key"] == original["events"][0]["event_key"]
    accept(hub, enriched)
    day = provider(hub)["history"]["days"][-1]
    assert day["tokens"] == 170 and day["unknown_model_tokens"] == 0
    assert day["models"] == [{"model": "claude-sonnet-4-6", "tokens": 170}]


def test_later_unchanged_codex_counter_does_not_assign_current_model_to_unknown_history(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    codex_events(source)
    original = source.export_batch()
    hub.ingest_batch("one", legacy(original), NOW)
    later = {**original["events"][1], "occurred_at": _iso(NOW)}
    accept(hub, batch(events=[later]))
    assert provider(hub, "codex")["history"]["days"][-1]["models"] == []


@pytest.mark.parametrize("value", [[], {}, True, None])
def test_malformed_tool_and_basis_are_rejections_not_exceptions(ledgers, value):
    source, hub = ledgers("source"), ledgers("hub")
    claude_event(source); observe(source, [meter()])
    exported = source.export_batch()
    event, quota, snapshot = exported["events"][0], exported["quotas"][0], exported["quota_snapshots"][0]
    for payload in (batch(events=[{**event, "tool": value}]), batch(events=[{**event, "basis": value}]),
                    batch(quotas=[{**quota, "tool": value}]), batch(snapshots=[{**snapshot, "tool": value}]),
                    batch(quotas=[{**quota, "bucket_id": value if value is not None else []}]),
                    batch(snapshots=[{**snapshot, "active_keys": [value]}])):
        assert hub.ingest_batch("one", payload, NOW) == {"version": 2, "accepted": 0, "rejected": 1}


def test_equal_timestamp_different_known_membership_is_not_silent_success(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    with source.lock, source.db:
        source._codex_quotas({"primary": {"used_percent": 10, "window_minutes": 10080}, "secondary": None}, _iso(NOW), NOW)
    exported = source.export_quota_batch()
    accept(hub, exported)
    conflicting = {**exported["quota_snapshots"][0]}
    conflicting["known_keys"] = conflicting["active_keys"]
    assert hub.ingest_batch("one", batch(snapshots=[conflicting]), NOW) == {"version": 2, "accepted": 0, "rejected": 1}
    assert len(provider(hub, "codex")["quotas"]) == 1


def test_same_stamp_legacy_quota_can_enrich_missing_metadata_without_refreshing_numbers(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter("sonnet", model="claude-sonnet-4-6", kind="weekly_scoped")])
    exported = source.export_quota_batch()
    assert hub.ingest_batch("one", legacy(exported), NOW)["accepted"] == 1
    assert provider(hub)["quotas"][0]["model"] is None
    accept(hub, exported)
    enriched = provider(hub)["quotas"][0]
    assert enriched["model"] == "claude-sonnet-4-6" and enriched["bucket_label"] == "Sonnet 4.6"
    assert enriched["observed_at"] == exported["quotas"][0]["observed_at"]
    for changes in ({"used_percent": 99}, {"window_minutes": 300}, {"resets_at": 1800000000},
                    {"model": "claude-opus-4-6"}, {"bucket_id": _hash("other")}, {"bucket_label": "Opus 4.6"}):
        accept(hub, batch(quotas=[{**exported["quotas"][0], **changes}]))
        assert provider(hub)["quotas"][0] == enriched


def test_same_stamp_enrichment_cannot_bypass_empty_membership_watermark(ledgers):
    source, hub = ledgers("source"), ledgers("hub")
    observe(source, [meter("sonnet", model="claude-sonnet-4-6", kind="weekly_scoped")], -20)
    exported = source.export_quota_batch()
    hub.ingest_batch("one", legacy(exported), NOW)
    removal = {**exported["quota_snapshots"][0], "observed_at": _iso(NOW), "active_keys": []}
    accept(hub, batch(snapshots=[removal]))
    revived = {**exported["quotas"][0], "observed_at": _iso(NOW)}
    accept(hub, batch(quotas=[revived]))
    assert provider(hub)["quotas"] == []
