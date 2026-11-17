"""Synthetic local fixtures only: model denominators and observed quota windows."""
import json
import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from agent_monitor.usage_ledger import UsageLedger, _iso


class UsageModelsWindowsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.now = datetime(2026, 10, 27, 8, tzinfo=timezone.utc)
        self.ledger = UsageLedger(self.base / "usage.sqlite3")
        self.addCleanup(self.ledger.close)
        self.meta = {}

    def provider(self, tool="codex", ledger=None, devices=None):
        return next(item for item in (ledger or self.ledger).summary(self.now, devices)["providers"] if item["tool"] == tool)

    def day(self, tool="codex", ledger=None, devices=None):
        return self.provider(tool, ledger, devices)["history"]["days"][-1]

    def record(self, row, tool="codex"):
        with self.ledger.lock, self.ledger.db:
            self.ledger._record(row, tool, self.meta, self.base / "synthetic.jsonl", self.now)

    def context(self, model):
        self.record({"type": "turn_context", "payload": {"model": model}})

    def begin(self):
        self.record({"type": "session_meta", "timestamp": _iso(self.now - timedelta(hours=1)),
                     "payload": {"id": "synthetic-session"}})

    def counter(self, inp, out, *, last=None, seconds=0, **extra):
        total = {"input_tokens": inp, "output_tokens": out, "total_tokens": inp + out}
        response = total if last is None else {"input_tokens": last[0], "output_tokens": last[1], "total_tokens": sum(last)}
        self.record({"type": "event_msg", "timestamp": _iso(self.now - timedelta(seconds=seconds)),
                     "payload": {"type": "token_count", "info": {"total_token_usage": total, "last_token_usage": response}, **extra}})

    def claude(self, identity, model, *, output=20, final=True):
        self.record({"type": "assistant", "timestamp": _iso(self.now), "sessionId": "synthetic-session",
                     "message": {"id": identity, "model": model, "stop_reason": "end_turn" if final else None,
                                 "usage": {"input_tokens": 10, "output_tokens": output,
                                           "cache_read_input_tokens": 100, "cache_creation_input_tokens": 40}}}, "claude")

    def codex_limits(self, data, seconds=0):
        with self.ledger.lock, self.ledger.db:
            self.ledger._codex_quotas(data, _iso(self.now - timedelta(seconds=seconds)), self.now)

    def window(self, minutes, used=20):
        return {"used_percent": used, "window_minutes": minutes, "resets_at": int(self.now.timestamp()) + 20000}

    def claude_snapshot(self, windows, *, seconds=0, complete=True):
        return self.ledger.ingest_claude_quota({"observed_at": _iso(self.now - timedelta(seconds=seconds)),
                                              "quota_windows": windows, "snapshot_complete": complete}, self.now)

    def scoped_window(self, kind="session", bucket="account", model=None, used=20):
        return {"kind": kind, "bucket_id": bucket, "bucket_label": "Sonnet" if model else "全部模型", "model": model,
                "window_minutes": 300 if kind == "session" else 10080, "used_percentage": used, "resets_at": None}

    def test_weekly_primary_never_creates_a_five_hour_window(self):
        self.codex_limits({"limit_id": "codex", "primary": self.window(10080), "secondary": None})
        quotas = self.provider()["quotas"]
        self.assertEqual([item["window_minutes"] for item in quotas], [10080])
        self.assertIsNone(quotas[0]["model"])

    def test_complete_new_snapshot_removes_absent_window_without_zero_observation(self):
        self.codex_limits({"primary": self.window(300), "secondary": self.window(10080)}, seconds=20)
        self.codex_limits({"primary": None, "secondary": self.window(10080, 30)})
        self.assertEqual([item["window_minutes"] for item in self.provider()["quotas"]], [10080])
        exported = self.ledger.export_quota_batch()["quotas"]
        self.assertEqual([row["window_minutes"] for row in exported if row["present"]], [10080])
        removed = [row for row in exported if not row["present"]]
        self.assertEqual(len(removed), 1)
        self.assertIsNone(removed[0]["used_percent"])
        # An older complete or partial record cannot restore that window.
        self.codex_limits({"primary": self.window(300, 99), "secondary": self.window(10080)}, seconds=10)
        self.codex_limits({"primary": self.window(300, 99)}, seconds=5)
        self.assertEqual([item["window_minutes"] for item in self.provider()["quotas"]], [10080])

    def test_partial_malformed_or_missing_limits_do_not_remove(self):
        self.codex_limits({"primary": self.window(300), "secondary": self.window(10080)}, seconds=20)
        for data in ({"primary": self.window(10080)}, {"primary": None},
                     {"primary": None, "secondary": {"used_percent": "bad", "window_minutes": 10080}}, None):
            self.codex_limits(data)
            self.assertEqual(len(self.provider()["quotas"]), 2)

    def test_same_duration_buckets_stay_distinct_and_bucket_is_not_model(self):
        self.codex_limits({"limit_id": "codex", "limit_name": "Codex", "primary": self.window(10080), "secondary": None})
        self.codex_limits({"limit_id": "other", "limit_name": "GPT-5.6", "primary": self.window(10080, 40), "secondary": None})
        quotas = self.provider()["quotas"]
        self.assertEqual(len({item["bucket_id"] for item in quotas}), 2)
        self.assertEqual({item["bucket_label"] for item in quotas}, {"Codex", "GPT-5.6"})
        self.assertTrue(all(item["model"] is None for item in quotas))

    def test_claude_complete_empty_clears_but_unknown_and_partial_do_not(self):
        self.claude_snapshot([self.scoped_window(), self.scoped_window("weekly_all")], seconds=30)
        self.assertEqual(self.claude_snapshot([], seconds=20, complete=False), {"accepted": 0, "rejected": 0})
        self.assertEqual(len(self.provider("claude")["quotas"]), 2)
        self.ledger.ingest_claude_quota({"observed_at": _iso(self.now - timedelta(seconds=10)), "rate_limits": {"seven_day": {"used_percentage": 40, "resets_at": None}}}, self.now)
        self.assertEqual(len(self.provider("claude")["quotas"]), 2)
        self.claude_snapshot([])
        self.assertEqual(self.provider("claude")["quotas"], [])
        self.claude_snapshot([self.scoped_window()], seconds=1)
        self.assertEqual(self.provider("claude")["quotas"], [])

    def test_claude_model_meter_does_not_replace_account_meter(self):
        self.claude_snapshot([self.scoped_window("weekly_all"),
                             self.scoped_window("weekly_scoped", "sonnet", "claude-sonnet-4-6", 80)])
        quotas = self.provider("claude")["quotas"]
        self.assertEqual(len(quotas), 2)
        self.assertEqual({item["model"] for item in quotas}, {None, "claude-sonnet-4-6"})
        self.assertEqual(len({item["key"] for item in quotas}), 2)

    def test_invalid_complete_snapshot_cannot_remove_and_other_source_is_untouched(self):
        self.claude_snapshot([self.scoped_window()], seconds=20)
        invalid = self.scoped_window("weekly_all", used=-1)
        self.assertEqual(self.claude_snapshot([invalid])["rejected"], 1)
        self.assertEqual(len(self.provider("claude")["quotas"]), 1)
        self.ledger.ingest_claude_quota({"observed_at": _iso(self.now), "quota_windows": [], "snapshot_complete": True}, self.now, "another-source")
        self.assertEqual(len(self.provider("claude")["quotas"]), 1)

    def test_codex_model_switch_only_attributes_exact_response_deltas(self):
        self.begin(); self.context("gpt-5.6")
        self.counter(100, 20, seconds=20)
        self.context("gpt-6.1")
        self.counter(140, 30, last=(40, 10), seconds=10)
        self.counter(180, 40, last=(20, 5))  # Multiple responses were skipped: unknown.
        day = self.day()
        self.assertEqual(day["tokens"], 220)
        self.assertEqual({item["model"]: item["tokens"] for item in day["models"]}, {"gpt-5.6": 120, "gpt-6.1": 50})
        self.assertEqual(day["unknown_model_tokens"], 50)

    def test_missing_turn_model_clears_previous_and_unknown_is_in_denominator(self):
        self.begin(); self.context("gpt-5.6"); self.counter(100, 20, seconds=20)
        self.context(None); self.counter(120, 30, last=(20, 10))
        self.assertEqual(self.day()["models"], [{"model": "gpt-5.6", "tokens": 120}])
        self.assertEqual(self.day()["unknown_model_tokens"], 30)

    def test_copied_codex_event_never_counts_twice_or_relabels_after_model_switch(self):
        self.begin(); self.context("gpt-5.6"); self.counter(100, 20, seconds=10)
        self.context("gpt-6.1"); self.counter(100, 20)
        day = self.day()
        self.assertEqual(day["tokens"], 120)
        self.assertEqual(day["models"], [])  # Contradictory labels are unknown.
        self.assertEqual(day["unknown_model_tokens"], 120)

    def test_claude_tokens_include_cache_and_model_conflict_remains_unknown(self):
        self.claude("one", "claude-sonnet-4-6")
        self.claude("two", "claude-opus-4-6")
        self.claude("unknown", None)
        self.claude("one", "claude-sonnet-4-6")
        self.assertEqual(self.day("claude")["tokens"], 510)
        self.assertEqual(self.day("claude")["unknown_model_tokens"], 170)
        self.claude("one", "claude-opus-4-6")
        self.claude("one", "claude-sonnet-4-6")
        self.assertEqual(self.day("claude")["tokens"], 510)
        self.assertEqual(self.day("claude")["models"], [{"model": "claude-opus-4-6", "tokens": 170}])
        self.assertEqual(self.day("claude")["unknown_model_tokens"], 340)

    def test_claude_provisional_model_is_not_daily_usage_until_final(self):
        self.claude("one", "claude-sonnet-4-6", final=False, output=2)
        self.assertIsNone(self.day("claude")["tokens"])
        self.claude("one", "claude-sonnet-4-6")
        self.assertEqual(self.day("claude")["models"], [{"model": "claude-sonnet-4-6", "tokens": 170}])

    def test_unrecognized_model_strings_never_leave_parser(self):
        self.claude("one", "PRIVATE / ACCOUNT / TASK")
        self.begin(); self.context("PRIVATE / ACCOUNT / TASK"); self.counter(10, 2)
        self.assertNotIn("PRIVATE", json.dumps(self.ledger.summary(self.now)))
        self.assertNotIn("PRIVATE", json.dumps(self.ledger.export_batch()))
        self.assertEqual(self.day()["unknown_model_tokens"], 12)

    def test_empty_model_data_is_unknown_not_fake_zero(self):
        for tool in ("codex", "claude"):
            self.assertEqual(self.day(tool)["models"], [])
            self.assertIsNone(self.day(tool)["unknown_model_tokens"])

    def test_model_groups_are_bounded_without_changing_total(self):
        for index in range(40):
            self.claude(str(index), f"claude-sonnet-test-{index}")
        day = self.day("claude")
        self.assertEqual(len(day["models"]), 32)
        self.assertEqual(day["tokens"], 6800)
        self.assertEqual(sum(row["tokens"] for row in day["models"]) + day["unknown_model_tokens"], 6800)

    def test_same_total_wrong_response_components_cannot_gain_model_on_replay(self):
        self.begin(); self.context(None); self.counter(100, 20, seconds=20)
        self.context("gpt-5.6"); self.counter(110, 40, last=(20, 10), seconds=10)
        self.counter(110, 40, last=(20, 10))
        self.assertEqual(self.day()["tokens"], 150)
        self.assertEqual(self.day()["models"], [])
        self.assertEqual(self.day()["unknown_model_tokens"], 150)

    def test_authorized_v2_wire_keeps_model_meters_distinct_and_removal_explicit(self):
        self.claude("one", "claude-sonnet-4-6")
        self.claude_snapshot([self.scoped_window("weekly_all"),
                             self.scoped_window("weekly_scoped", "sonnet", "claude-sonnet-4-6", 80)])
        self.codex_limits({"limit_id": "model-bucket", "limit_name": "GPT-5.6", "primary": self.window(10080), "secondary": None})
        for batch in (self.ledger.export_batch(), self.ledger.export_quota_batch()):
            self.assertEqual(batch["version"], 2)
            self.assertTrue(batch["quota_snapshots"])
            self.assertEqual(len(batch["quotas"]), 3)
            for quota in batch["quotas"]:
                self.assertTrue({"model", "bucket_id", "bucket_label", "present"} <= quota.keys())
            for event in batch["events"]:
                self.assertEqual(event["model"], "claude-sonnet-4-6")
        self.claude_snapshot([], seconds=-1)
        for batch in (self.ledger.export_batch(), self.ledger.export_quota_batch()):
            self.assertTrue(all(not row["present"] and row["used_percent"] is None for row in batch["quotas"] if row["tool"] == "claude"))

    def test_official_window_limit_matches_parser_and_never_truncates_complete_snapshot(self):
        for count in (21, 64):
            windows = [self.scoped_window("weekly_scoped", f"model-{index}", f"claude-sonnet-test-{index}")
                       for index in range(count)]
            self.assertEqual(self.claude_snapshot(windows, seconds=64 - count), {"accepted": count, "rejected": 0})
            self.assertEqual(len(self.provider("claude")["quotas"]), count)
        windows.append(self.scoped_window("weekly_scoped", "model-64", "claude-sonnet-test-64"))
        self.assertEqual(self.claude_snapshot(windows), {"accepted": 0, "rejected": 1})
        self.assertEqual(len(self.provider("claude")["quotas"]), 64)

    def test_local_model_upgrade_keeps_legacy_import_unknown_without_count_changes(self):
        self.claude("one", "claude-sonnet-4-6")
        batch = self.ledger.export_batch()
        # A genuine v1 source has no model fields. Keep that compatibility
        # distinct from silently accepting v2 metadata under a v1 envelope.
        batch["version"] = 1
        batch.pop("quota_snapshots")
        for event in batch["events"]:
            for field in ("model", "model_conflict", "model_input_tokens", "model_output_tokens"):
                event.pop(field, None)
        hub = UsageLedger(self.base / "synthetic-hub.sqlite3")
        self.addCleanup(hub.close)
        hub.ingest_batch("source-one", batch, self.now)
        hub.ingest_batch("source-two", batch, self.now)
        day = self.day("claude", hub, ["source-one", "source-two"])
        self.assertEqual(day["tokens"], 170)
        self.assertEqual(day["models"], [])
        self.assertEqual(day["unknown_model_tokens"], 170)

    def test_malformed_kind_and_huge_numeric_window_fail_closed_without_deleting(self):
        self.claude_snapshot([self.scoped_window()], seconds=20)
        for kind in ([], {}, True, None):
            bad = {**self.scoped_window(), "kind": kind}
            self.assertEqual(self.claude_snapshot([bad])["rejected"], 1)
        self.assertEqual(self.claude_snapshot([{**self.scoped_window(), "used_percentage": 10 ** 500}])["rejected"], 1)
        self.assertEqual(len(self.provider("claude")["quotas"]), 1)

    def test_malformed_explicit_bucket_is_not_default_account_or_removal(self):
        self.codex_limits({"primary": self.window(300), "secondary": None}, seconds=20)
        self.codex_limits({"limit_id": [], "primary": None, "secondary": None})
        self.assertEqual(len(self.provider()["quotas"]), 1)

    def test_upgrade_keeps_existing_totals_without_backfilling_guessed_models(self):
        self.claude("one", None)
        self.codex_limits({"primary": self.window(10080), "secondary": None})
        original = self.ledger.db
        legacy_path = self.base / "legacy.sqlite3"
        legacy = sqlite3.connect(legacy_path)
        original.backup(legacy)
        for name in ("model", "model_input_tokens", "model_output_tokens", "model_conflict"):
            legacy.execute(f"ALTER TABLE usage_events DROP COLUMN {name}")
        for name in ("bucket_id", "bucket_label", "model", "present"):
            legacy.execute(f"ALTER TABLE usage_quotas DROP COLUMN {name}")
        legacy.commit(); legacy.close()
        upgraded = UsageLedger(legacy_path)
        self.addCleanup(upgraded.close)
        self.assertEqual(self.day("claude", upgraded)["tokens"], 170)
        self.assertEqual(self.day("claude", upgraded)["models"], [])
        self.assertEqual(self.day("claude", upgraded)["unknown_model_tokens"], 170)
        self.assertEqual([row["window_minutes"] for row in self.provider("codex", upgraded)["quotas"]], [10080])

    def test_unrecognized_bucket_label_is_not_retained_as_account_or_task_text(self):
        self.codex_limits({"limit_id": "codex", "limit_name": "Codex PRIVATE ACCOUNT NAME",
                           "primary": self.window(10080), "secondary": None})
        self.assertIsNone(self.provider()["quotas"][0]["bucket_label"])
        self.assertNotIn("PRIVATE", json.dumps(self.ledger.summary(self.now)))
