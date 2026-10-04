import copy
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from agent_monitor.usage_ledger import UsageLedger, MAX_LINE, _event_key, _iso, read_claude_quota_file


class UsageLedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.codex = self.base / "codex"
        self.claude = self.base / "claude"
        self.now = datetime(2026, 9, 25, 12, 0, tzinfo=timezone.utc)
        self.ledger = UsageLedger(self.base / "usage.sqlite3")
        self.addCleanup(self.ledger.close)

    def meta(self, session="session-one", stamp=None, **extra):
        return {"type": "session_meta", "timestamp": _iso(stamp or self.now - timedelta(hours=1)),
                "payload": {"id": session, **extra}}

    def codex_record(self, inp=100, out=20, stamp=None, last=None, quota=None):
        usage = {"input_tokens": inp, "output_tokens": out, "total_tokens": inp + out,
                 "cached_input_tokens": inp // 2, "cache_write_input_tokens": 0, "reasoning_output_tokens": 2}
        return {"type": "event_msg", "timestamp": _iso(stamp or self.now), "payload": {
            "type": "token_count", "info": {"total_token_usage": usage, "last_token_usage": last or usage.copy()},
            "rate_limits": quota}}

    def claude_record(self, response="msg-one", session="session-one", output=20, final=True, stamp=None, **usage):
        return {"type": "assistant", "sessionId": session, "timestamp": _iso(stamp or self.now),
                "message": {"id": response, "stop_reason": "end_turn" if final else None,
                            "content": [{"text": "PRIVATE RAW OUTPUT MUST NEVER LEAVE"}],
                            "usage": {"input_tokens": 10, "output_tokens": output,
                                      "cache_read_input_tokens": 100, "cache_creation_input_tokens": 40,
                                      "cache_creation": {"ephemeral_5m_input_tokens": 40}, **usage}}}

    def write(self, records, tool="codex", filename="session.jsonl", mode="wb"):
        path = self.codex / "sessions" / filename if tool == "codex" else self.claude / "projects" / "project" / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open(mode) as handle:
            for record in records:
                handle.write(json.dumps(record).encode() + b"\n")
        return path

    def scan(self, **extra):
        return self.ledger.scan(codex_home=self.codex, claude_home=self.claude, now=self.now, **extra)

    def provider(self, tool="codex", now=None):
        return next(p for p in self.ledger.summary(now or self.now)["providers"] if p["tool"] == tool)

    def test_empty_is_unknown_not_zero(self):
        self.scan()
        self.assertIsNone(self.provider()["today_tokens"])
        self.assertEqual(self.provider()["coverage"], "unavailable")
        self.assertIsNone(self.ledger.task_usage("codex", "missing")["total_tokens"])

    def test_codex_cumulative_not_last_or_cache_double_sum(self):
        self.write([self.meta(), self.codex_record(100, 20, self.now - timedelta(minutes=2)),
                    self.codex_record(150, 40, self.now - timedelta(minutes=1)),
                    self.codex_record(150, 40)])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 190)
        self.assertEqual(self.ledger.task_usage("codex", "codex:session-one")["total_tokens"], 190)
        self.assertEqual(self.ledger.task_usage("codex", "session-one")["cached_input_tokens"], 75)
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 190)

    def test_codex_context_estimate_does_not_advance(self):
        rec = self.codex_record(100, 20)
        rec["payload"]["info"]["last_token_usage"] = {"total_tokens": 39520, "input_tokens": 0, "output_tokens": 0}
        self.write([self.meta(), self.codex_record(100, 20, self.now - timedelta(minutes=1)), rec])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)

    def test_missing_baseline_keeps_session_total_but_not_today_initial(self):
        rec = self.codex_record(500, 100, self.now - timedelta(minutes=2), last={"input_tokens": 5, "output_tokens": 1, "total_tokens": 6})
        self.write([self.meta(), rec])
        self.scan()
        self.assertIsNone(self.provider()["today_tokens"])
        self.assertEqual(self.ledger.task_usage("codex", "session-one")["total_tokens"], 600)
        self.write([self.codex_record(550, 120)], mode="ab")
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 70)

    def test_replay_then_current_does_not_charge_again(self):
        first = self.codex_record(100, 20, self.now - timedelta(minutes=3))
        second = self.codex_record(150, 40, self.now - timedelta(minutes=2))
        self.write([self.meta(), first, second])
        self.scan()
        self.write([first, second, self.codex_record(170, 50)], mode="ab")
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 220)

    def test_ambiguous_reset_never_invents_new_epoch(self):
        self.write([self.meta(), self.codex_record(100, 20, self.now - timedelta(minutes=3)),
                    self.codex_record(5, 3, self.now - timedelta(minutes=2)), self.codex_record(200, 50)])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)
        self.assertIsNone(self.ledger.task_usage("codex", "session-one")["total_tokens"])
        self.assertEqual(self.ledger.summary(self.now)["scan"]["ambiguous_resets"], 1)

    def test_inherited_fork_excludes_parent_and_first_unknown_baseline(self):
        earlier = self.now - timedelta(hours=2)
        self.write([self.meta(forked_from_id="parent"), self.codex_record(100, 20, earlier),
                    self.codex_record(150, 40, self.now - timedelta(minutes=1)), self.codex_record(170, 50)])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 30)
        self.assertEqual(self.ledger.task_usage("codex", "session-one")["total_tokens"], 30)
        self.assertIsNone(self.ledger.task_usage("codex", "session-one")["cached_input_tokens"])

    def test_paginated_child_with_own_first_counter(self):
        self.write([self.meta(parent_thread_id="parent", history_mode="paginated"), self.codex_record()])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)

    def test_independent_basis_not_repeated_on_later_export_pages(self):
        self.write([self.meta(), self.codex_record(100, 20, self.now - timedelta(minutes=1)), self.codex_record(150, 40)])
        self.scan()
        first = self.ledger.export_batch(1)
        self.assertEqual(first["events"][0]["basis"], "independent")
        self.ledger.ack_export(first["cursor"])
        second = self.ledger.export_batch(1)
        self.assertEqual(second["events"][0]["basis"], "own")
        with_ledger = UsageLedger(self.base / "new-hub.sqlite3")
        self.addCleanup(with_ledger.close)
        with_ledger.ingest_batch("device-a", second, self.now)
        self.assertIsNone(with_ledger.summary(self.now)["providers"][0]["today_tokens"])

    def test_midnight_gap_not_assigned_to_new_day(self):
        before = datetime(2026, 9, 24, 15, 59, tzinfo=timezone.utc)
        after = datetime(2026, 9, 24, 16, 1, tzinfo=timezone.utc)
        self.write([self.meta(stamp=before - timedelta(minutes=1)), self.codex_record(100, 20, before),
                    self.codex_record(150, 40, after), self.codex_record(170, 50, after + timedelta(minutes=1))])
        self.scan()
        self.assertEqual(self.provider(now=before)["today_tokens"], 120)
        self.assertIsNone(self.provider(now=after)["today_tokens"])
        self.assertEqual(self.provider(now=after + timedelta(minutes=1))["today_tokens"], 30)
        self.assertEqual(self.ledger.summary(self.now)["timezone"], "Asia/Shanghai")

    def test_claude_stream_replaces_final_and_preserves_cache_semantics(self):
        self.write([self.claude_record(output=7, final=False, stamp=self.now - timedelta(minutes=1)),
                    self.claude_record(output=727), self.claude_record(output=727)], "claude")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 877)
        task = self.ledger.task_usage("claude", "claude:session-one")
        self.assertEqual(task["total_tokens"], 877)
        self.assertEqual(task["cache_write_tokens"], 40)
        self.write([self.claude_record(output=7, final=False)], "claude", mode="ab")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 877)

    def test_claude_provisional_not_charged_to_day_until_final(self):
        before = datetime(2026, 9, 24, 15, 59, tzinfo=timezone.utc)
        after = datetime(2026, 9, 24, 16, 1, tzinfo=timezone.utc)
        self.write([self.claude_record(final=False, stamp=before)], "claude")
        self.scan()
        self.assertIsNone(self.provider("claude", before)["today_tokens"])
        self.assertEqual(self.provider("claude")["pending_requests"], 1)
        self.write([self.claude_record(final=True, output=50, stamp=after)], "claude", mode="ab")
        self.scan()
        self.assertIsNone(self.provider("claude", before)["today_tokens"])
        self.assertEqual(self.provider("claude", after)["today_tokens"], 200)

    def test_subagents_in_provider_but_not_root_own_total(self):
        self.write([self.claude_record(response="msg-root")], "claude")
        self.write([self.claude_record(response="msg-child", output=30)], "claude", "session/subagents/agent-a.jsonl")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 350)
        self.assertEqual(self.ledger.task_usage("claude", "session-one")["total_tokens"], 170)

    def test_claude_missing_cache_remains_unknown_not_zero(self):
        rec = self.claude_record()
        del rec["message"]["usage"]["cache_creation_input_tokens"]
        self.write([rec], "claude")
        self.scan()
        self.assertIsNone(self.provider("claude")["today_tokens"])
        self.assertIsNone(self.ledger.task_usage("claude", "session-one")["total_tokens"])
        self.assertIsNone(self.ledger.task_usage("claude", "session-one")["cache_write_tokens"])

    def test_bad_numbers_and_timestamps_rejected(self):
        records = []
        for bad in (-1, True, 1.5, "100", 10**30):
            rec = self.claude_record(response=f"msg-{bad}", output=bad)
            records.append(rec)
        rec = self.claude_record(response="future", stamp=self.now + timedelta(hours=1))
        records.append(rec)
        rec = self.codex_record()
        rec["payload"]["info"]["total_token_usage"]["cached_input_tokens"] = -3
        self.write([self.meta(), rec])
        self.write(records, "claude")
        self.scan()
        self.assertIsNone(self.provider("claude")["today_tokens"])
        self.assertIsNone(self.provider()["today_tokens"])

    def test_final_conflict_flags_but_does_not_sum_or_replace(self):
        self.write([self.claude_record(output=20), self.claude_record(output=50)], "claude")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 170)
        self.assertEqual(self.ledger.summary(self.now)["scan"]["usage_conflicts"], 1)

    def test_partial_line_and_small_byte_budget_resume(self):
        path = self.write([], "claude")
        raw = json.dumps(self.claude_record()).encode()
        path.write_bytes(raw[:-5])
        for _ in range(30):
            report = self.scan(budget_bytes=27)
            self.assertLessEqual(report["bytes_read"], 27)
        self.assertIsNone(self.provider("claude")["today_tokens"])
        with path.open("ab") as handle:
            handle.write(raw[-5:] + b"\n")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 170)

    def test_restart_rereads_partial_line_safely(self):
        path = self.write([], "claude")
        raw = json.dumps(self.claude_record()).encode()
        path.write_bytes(raw[:-2])
        self.scan()
        second = UsageLedger(self.base / "usage.sqlite3")
        self.addCleanup(second.close)
        with path.open("ab") as handle:
            handle.write(raw[-2:] + b"\n")
        second.scan(self.codex, self.claude, now=self.now)
        self.assertEqual(self.provider("claude")["today_tokens"], 170)

    def test_oversized_line_skips_then_recovers_with_budget(self):
        path = self.write([], "claude")
        path.write_bytes(b'{"content":"' + b"x" * (MAX_LINE + 500) + b'"}\n' + json.dumps(self.claude_record()).encode() + b"\n")
        for _ in range(6):
            self.scan(budget_bytes=65536)
        self.assertEqual(self.provider("claude")["today_tokens"], 170)
        self.assertGreaterEqual(self.ledger.summary(self.now)["scan"]["oversized_lines"], 1)

    def test_truncation_then_replay_deduplicates(self):
        one = self.claude_record(response="one")
        two = self.claude_record(response="two")
        path = self.write([one, two], "claude")
        self.scan()
        path.write_bytes(json.dumps(one).encode() + b"\n")
        self.scan()
        self.write([two, self.claude_record(response="three")], "claude", mode="ab")
        self.scan()
        self.assertEqual(self.provider("claude")["today_tokens"], 510)
        self.assertGreaterEqual(self.ledger.summary(self.now)["scan"]["file_restarts"], 1)

    def test_archive_copy_and_export_two_devices_deduplicate(self):
        self.write([self.meta(), self.codex_record()])
        path = self.codex / "archived_sessions" / "copy.jsonl"
        path.parent.mkdir(parents=True)
        path.write_bytes((self.codex / "sessions" / "session.jsonl").read_bytes())
        self.write([self.claude_record()], "claude")
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)
        batch = self.ledger.export_batch()
        hub = UsageLedger(self.base / "hub.sqlite3")
        self.addCleanup(hub.close)
        self.assertEqual(hub.ingest_batch("pc-one", batch, self.now)["rejected"], 0)
        hub.ingest_batch("pc-two", batch, self.now)
        self.assertEqual(hub.summary(self.now)["providers"][0]["today_tokens"], 120)
        self.assertEqual(hub.summary(self.now)["providers"][1]["today_tokens"], 170)
        self.assertEqual(hub.task_usage("codex", "session-one", device_id="pc-two")["total_tokens"], 120)
        self.assertIsNone(hub.task_usage("codex", "session-one", device_id="missing")["total_tokens"])
        self.assertEqual(hub.export_batch()["events"], [])

    def test_quota_independent_from_missing_token_info_weekly_primary(self):
        quota = {"limit_id": "codex", "primary": {"used_percent": 73, "window_minutes": 10080,
                                                  "resets_at": int(self.now.timestamp()) + 100}, "secondary": None}
        rec = self.codex_record(quota=quota)
        rec["payload"]["info"] = None
        self.write([self.meta(), rec])
        self.scan()
        window = self.provider()["quotas"][0]
        self.assertEqual(window["window_minutes"], 10080)
        self.assertEqual(window["label"], "7 天")
        self.assertEqual(window["remaining_percent"], 27)
        self.assertFalse(window["stale"])
        expired = self.provider(now=self.now + timedelta(seconds=101))["quotas"][0]
        self.assertTrue(expired["stale"])
        self.assertEqual(expired["remaining_percent"], 27)
        self.assertEqual(expired["availability"], "expired")

    def test_claude_statusline_only_numbers_scoped_quotas(self):
        payload = {"observed_at": _iso(self.now), "rate_limits": {
            "five_hour": {"used_percentage": 0, "resets_at": int(self.now.timestamp()) + 10000},
            "seven_day": {"used_percentage": 70, "resets_at": int(self.now.timestamp()) + 100000}},
            "context_window": {"total_input_tokens": 900000}, "cwd": "SECRET PATH"}
        self.assertEqual(self.ledger.ingest_claude_quota(payload, self.now)["accepted"], 2)
        windows = self.provider("claude")["quotas"]
        self.assertEqual(windows[0]["remaining_percent"], 100)
        self.assertIsNone(self.provider("claude")["today_tokens"])
        self.assertTrue(self.provider("claude", self.now + timedelta(seconds=901))["quotas"][0]["stale"])
        batch = self.ledger.export_batch()
        hub = UsageLedger(self.base / "hub.sqlite3")
        self.addCleanup(hub.close)
        hub.ingest_batch("one", batch, self.now)
        hub.ingest_batch("two", batch, self.now)
        self.assertEqual(len(hub.summary(self.now)["providers"][1]["quotas"]), 4)
        self.assertNotIn("SECRET", json.dumps(batch))

    def test_invalid_quota_null_is_not_zero(self):
        for bad in (None, True, -1, 101, float("inf"), "20"):
            result = self.ledger.ingest_claude_quota({"observed_at": _iso(self.now), "rate_limits": {
                "five_hour": {"used_percentage": bad, "resets_at": int(self.now.timestamp()) + 200}}}, self.now)
            self.assertEqual(result["rejected"], 1)
        self.assertEqual(self.provider("claude")["quotas"], [])

    def test_export_allowlist_ack_and_forged_fields(self):
        self.write([self.claude_record()], "claude")
        self.scan()
        batch = self.ledger.export_batch()
        encoded = json.dumps(batch)
        self.assertNotIn("PRIVATE", encoded)
        self.assertNotIn("session-one", encoded)
        self.assertNotIn(str(self.base), encoded)
        hub = UsageLedger(self.base / "hub.sqlite3")
        self.addCleanup(hub.close)
        for bad in (True, -1, "1", 10**30):
            forged = copy.deepcopy(batch)
            forged["events"][0]["input_tokens"] = bad
            self.assertEqual(hub.ingest_batch("device", forged, self.now)["rejected"], 1)
        forged = copy.deepcopy(batch)
        forged["events"] *= 201
        self.assertEqual(hub.ingest_batch("device", forged, self.now)["rejected"], 1)
        forged = copy.deepcopy(batch)
        forged["events"][0]["event_key"] = "x" * 100000
        self.assertEqual(hub.ingest_batch("device", forged, self.now)["rejected"], 1)
        self.ledger.ack_export(batch["cursor"])
        self.assertEqual(self.ledger.export_batch()["events"], [])

    def test_symlink_not_followed(self):
        outside = self.base / "outside.jsonl"
        outside.write_text(json.dumps(self.claude_record()) + "\n")
        target = self.claude / "projects" / "link.jsonl"
        target.parent.mkdir(parents=True)
        try:
            os.symlink(outside, target)
        except OSError:
            self.skipTest("symlinks unavailable on this Windows account")
        self.scan()
        self.assertIsNone(self.provider("claude")["today_tokens"])

    def test_summary_only_current_devices_and_empty_scope(self):
        self.write([self.claude_record()], "claude")
        self.scan()
        hub = UsageLedger(self.base / "hub.sqlite3")
        self.addCleanup(hub.close)
        hub.ingest_batch("removed-device", self.ledger.export_batch(), self.now)
        self.assertIsNone(hub.summary(self.now, device_ids=["current-device"])["providers"][1]["today_tokens"])
        hub.ingest_batch("current-device", self.ledger.export_batch(), self.now)
        self.assertEqual(hub.summary(self.now, device_ids=["current-device"])["providers"][1]["today_tokens"], 170)
        self.assertIsNone(hub.summary(self.now, device_ids=[])["providers"][1]["today_tokens"])
        self.assertEqual(hub.summary(self.now, device_ids=[])["scan"], {})

    def test_discovery_recent_file_ahead_of_old_history(self):
        old = self.write([self.meta(session="old"), self.codex_record()], filename="2024/01/01/old.jsonl")
        current = self.write([self.meta(session="new"), self.codex_record()], filename="2026/09/25/new.jsonl")
        os.utime(old, (self.now.timestamp() - 30 * 86400,) * 2)
        os.utime(current, (self.now.timestamp(),) * 2)
        with patch("agent_monitor.usage_ledger.MAX_ENTRIES", 1):
            self.scan()
        self.assertEqual(self.ledger.task_usage("codex", "new")["total_tokens"], 120)

    def test_finalization_cannot_replace_a_valid_vector_with_incompatible_input(self):
        self.write([self.claude_record(final=False, output=5),
                    self.claude_record(output=30, input_tokens=500)], "claude")
        self.scan()
        self.assertEqual(self.ledger.task_usage("claude", "session-one")["input_tokens"], 10)
        self.assertIsNone(self.provider("claude")["today_tokens"])

    def test_numeric_quota_reader_uses_config_directory_and_bounds_file(self):
        home = self.base / "custom-claude"
        home.mkdir()
        path = home / "monitor-quota.json"
        payload = {"observed_at": _iso(self.now), "rate_limits": {"five_hour": {
            "used_percentage": 50, "resets_at": int(self.now.timestamp()) + 100}}}
        path.write_text(json.dumps(payload), encoding="utf-8")
        with patch.dict(os.environ, {"CLAUDE_CONFIG_DIR": str(home)}):
            self.assertEqual(read_claude_quota_file(), payload)
            path.write_bytes(b"x" * 16385)
            self.assertIsNone(read_claude_quota_file())
            path.write_text('{"rate_limits":{},"rate_limits":{}}')
            self.assertIsNone(read_claude_quota_file())

    def test_numeric_quota_reader_never_opens_unsafe_ancestor(self):
        home = self.base / "custom-claude"
        home.mkdir()
        (home / "monitor-quota.json").write_text('{"rate_limits":{}}')
        with patch("agent_monitor.usage_statusline.safe_path", return_value=False), patch("agent_monitor.usage_ledger.os.open") as opened:
            self.assertIsNone(read_claude_quota_file(home))
            opened.assert_not_called()

    def quota_record(self, used, stamp=None):
        return {"type": "event_msg", "timestamp": _iso(stamp or self.now), "payload": {
            "type": "token_count", "info": None, "rate_limits": {"limit_id": "codex",
            "primary": {"used_percent": used, "window_minutes": 300,
                        "resets_at": int(self.now.timestamp()) + 10000}, "secondary": None}}}

    def test_quota_tail_bypasses_huge_backfill_without_charging_tail_counter(self):
        old_stamp = self.now - timedelta(hours=1)
        path = self.write([self.meta(stamp=old_stamp - timedelta(minutes=1)),
                           self.codex_record(100, 20, stamp=old_stamp), self.quota_record(10, old_stamp)])
        latest = self.codex_record(900000, 100000)
        latest["payload"]["rate_limits"] = self.quota_record(70)["payload"]["rate_limits"]
        with path.open("ab") as handle:
            handle.seek(0, 2)
            # A sparse large transcript reproduces the slow accounting cursor
            # without persisting real conversation material in the test fixture.
            handle.truncate(200 * 1024 * 1024)
        with path.open("r+b") as handle:
            handle.seek(0, 2)
            handle.write(b"\n" + json.dumps(latest).encode() + b"\n")
        report = self.scan()
        self.assertGreater(report["backlog_files"], 0)
        self.assertLessEqual(report["quota_tail_bytes"], 2 * 1024 * 1024)
        self.assertEqual(report["quota_tail_files"], 1)
        quota = self.provider()["quotas"][0]
        self.assertEqual(quota["used_percent"], 70)
        self.assertEqual(quota["observed_at"], _iso(self.now))
        self.assertFalse(quota["stale"])
        self.assertEqual(self.provider()["today_tokens"], 120)
        self.assertEqual(self.ledger.task_usage("codex", "session-one")["total_tokens"], 120)
        self.assertEqual(self.ledger.db.execute("SELECT count(*) FROM usage_events").fetchone()[0], 1)
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)

    def test_quota_tail_no_quota_does_not_invent_freshness(self):
        old_stamp = self.now - timedelta(hours=1)
        path = self.write([self.meta(stamp=old_stamp), self.quota_record(30, old_stamp)])
        with path.open("ab") as handle:
            handle.write(b" " * (3 * 1024 * 1024) + b"\n")
            handle.write(json.dumps({"type": "event_msg", "timestamp": _iso(self.now),
                                     "payload": {"type": "token_count", "rate_limits": None}}).encode() + b"\n")
        self.scan()
        quota = self.provider()["quotas"][0]
        self.assertEqual(quota["used_percent"], 30)
        self.assertEqual(quota["observed_at"], _iso(old_stamp))
        self.assertTrue(quota["stale"])
        self.assertIsNone(self.provider()["today_tokens"])

    def test_quota_tail_discards_parseable_first_fragment_and_unfinished_last_line(self):
        path = self.write([self.meta()])
        false_first = json.dumps(self.quota_record(99, self.now + timedelta(seconds=1))).encode()
        valid = json.dumps(self.quota_record(25)).encode()
        unfinished = json.dumps(self.quota_record(98, self.now + timedelta(seconds=2))).encode()
        tail = false_first + b"\n" + valid + b"\n" + unfinished
        with path.open("ab") as handle:
            # The first tail fragment is valid-looking JSON, but its actual
            # line began much earlier and is invalid. The final line is valid
            # JSON without a newline and may still be being appended.
            handle.write(b'{"long_unfinished_prefix":"' + b"x" * (3 * 1024 * 1024) + tail)
        with patch("agent_monitor.usage_ledger.QUOTA_TAIL_BYTES", len(tail)):
            self.scan()
        self.assertEqual(self.provider()["quotas"][0]["used_percent"], 25)
        self.assertIsNone(self.provider()["today_tokens"])

    def test_quota_tail_file_count_and_byte_caps_and_safe_path(self):
        for index in range(12):
            path = self.write([self.meta(session=f"session-{index}")], filename=f"{index}.jsonl")
            with path.open("ab") as handle:
                handle.write(b" " * (300 * 1024) + b"\n" + json.dumps(self.quota_record(index)).encode() + b"\n")
        report = self.scan()
        self.assertEqual(report["quota_tail_files"], 8)
        self.assertLessEqual(report["quota_tail_bytes"], 2 * 1024 * 1024)
        before = self.provider()["quotas"]
        with patch("agent_monitor.usage_ledger._safe", return_value=False), patch("agent_monitor.usage_ledger.os.open") as opened:
            result = self.ledger._scan_quota_tails(self.now, float("inf"))
            self.assertEqual(result, {"bytes_read": 0, "files_checked": 0})
            opened.assert_not_called()
        self.assertEqual(self.provider()["quotas"], before)

    def test_quota_fast_export_bypasses_large_outbox_without_mutating_fifo(self):
        self.write([self.claude_record(response=f"historical-{i}") for i in range(220)], "claude")
        self.scan()
        payload = {"observed_at": _iso(self.now), "rate_limits": {"five_hour": {
            "used_percentage": 60, "resets_at": int(self.now.timestamp()) + 1000}}}
        self.ledger.ingest_claude_quota(payload, self.now)
        fifo_before = self.ledger.export_batch(200)
        self.assertEqual(len(fifo_before["events"]), 200)
        self.assertEqual(fifo_before["quotas"], [])
        fast = self.ledger.export_quota_batch()
        self.assertEqual(fast["events"], [])
        self.assertEqual(fast["quotas"][0]["used_percent"], 60)
        self.assertNotIn("cursor", fast)
        self.assertEqual(self.ledger.export_batch(200), fifo_before)
        hub = UsageLedger(self.base / "quota-hub.sqlite3")
        self.addCleanup(hub.close)
        hub.ingest_batch("mac", fast, self.now)
        hub.ingest_batch("mac", fast, self.now)
        self.assertEqual(len(hub.summary(self.now)["providers"][1]["quotas"]), 1)
        self.assertIsNone(hub.summary(self.now)["providers"][1]["today_tokens"])
        self.assertEqual(hub.export_quota_batch()["quotas"], [])
        self.ledger.ack_export(fifo_before["cursor"])
        remainder = self.ledger.export_batch()
        self.assertEqual(len(remainder["events"]), 20)
        self.assertEqual(len(remainder["quotas"]), 1)

    def test_first_own_header_survives_copied_parent_header_and_replay(self):
        start = self.now - timedelta(minutes=2)
        owner = self.meta("child", start, source={"subagent": "parent"}, history_mode="paginated")
        copied = self.meta("parent", start, timestamp=_iso(self.now - timedelta(hours=1)))
        self.write([self.meta("parent"), self.codex_record(1000, 200)], filename="parent.jsonl")
        self.write([owner, copied, self.codex_record(100, 20, self.now - timedelta(minutes=1)),
                    owner, self.codex_record(150, 40)], filename="child.jsonl")
        self.scan()
        self.assertEqual(self.ledger.task_usage("codex", "child")["total_tokens"], 190)
        self.assertEqual(self.ledger.task_usage("codex", "parent")["total_tokens"], 1200)
        self.assertEqual(self.provider()["today_tokens"], 1390)
        children = [event for event in self.ledger.export_batch()["events"] if event["total_tokens"] in (120, 190)]
        self.assertEqual([event["basis"] for event in children], ["independent", "own"])

    def test_owner_payload_timestamp_prevents_rewritten_outer_time_dropping_usage(self):
        owner = self.meta("owner", self.now + timedelta(minutes=1), timestamp=_iso(self.now - timedelta(minutes=1)))
        self.write([owner, self.codex_record()])
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 120)

    def test_invalid_own_header_does_not_adopt_later_copied_parent(self):
        self.write([self.meta(session=""), self.meta("parent"), self.codex_record()])
        self.scan()
        self.assertIsNone(self.provider()["today_tokens"])
        self.assertIsNone(self.ledger.task_usage("codex", "parent")["total_tokens"])

    def resume_fixture(self, *, midnight=False):
        owner = "11111111-1111-4111-8111-111111111111"
        physical = "22222222-2222-4222-8222-222222222222"
        start = self.now - timedelta(hours=1)
        anchor_at = self.now - timedelta(minutes=50)
        continuation = self.now - timedelta(minutes=30)
        if midnight:
            start = datetime(2026, 9, 24, 15, 0, tzinfo=timezone.utc)
            anchor_at = datetime(2026, 9, 24, 15, 59, tzinfo=timezone.utc)
            continuation = datetime(2026, 9, 24, 16, 1, tzinfo=timezone.utc)
        parent = self.write([self.meta(owner, start), self.codex_record(100, 20, anchor_at)],
                            filename=f"rollout-{owner}_{physical}.jsonl")
        cutoff = parent.stat().st_size
        old_tail = self.codex_record(500, 100, anchor_at + timedelta(seconds=20))
        with parent.open("ab") as handle:
            handle.write(json.dumps(old_tail).encode() + b"\n")
        header = self.meta(owner, continuation, timestamp=_iso(continuation), history_base={
            "thread_id": physical, "end_ordinal_exclusive": 2, "end_byte_offset": cutoff})
        segment = self.write([header, self.meta("copied-ancestor", continuation),
                    self.codex_record(100, 20, anchor_at),
                    self.codex_record(150, 40, continuation + timedelta(seconds=10)),
                    self.codex_record(170, 50, continuation + timedelta(seconds=20))],
                    filename=f"rollout-{owner}_33333333-3333-4333-8333-333333333333.jsonl")
        return owner, parent, segment, header

    def test_history_base_counts_only_segment_delta_and_preserves_actual_old_tail(self):
        owner, parent, segment, header = self.resume_fixture()
        archived = self.codex / "archived_sessions" / segment.name
        archived.parent.mkdir(parents=True)
        archived.write_bytes(segment.read_bytes())
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 700)  # old 600 + (220 - base 120)
        self.assertEqual(self.ledger.task_usage("codex", owner)["total_tokens"], 700)
        batch = self.ledger.export_batch()
        branch = [event for event in batch["events"] if event.get("counter_key")]
        self.assertEqual(len(branch), 2)
        self.assertEqual({event["anchor_input"] for event in branch}, {100})
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 700)
        hub = UsageLedger(self.base / "segment-hub.sqlite3")
        self.addCleanup(hub.close)
        self.assertEqual(hub.ingest_batch("computer-one", batch, self.now)["rejected"], 0)
        self.assertEqual(hub.ingest_batch("computer-two", batch, self.now)["rejected"], 0)
        self.assertEqual(hub.summary(self.now)["providers"][0]["today_tokens"], 700)
        self.assertEqual(hub.task_usage("codex", owner, device_id="computer-two")["total_tokens"], 700)
        self.assertIsNone(hub.task_usage("codex", owner)["cached_input_tokens"])

    def test_history_base_cross_midnight_initial_gap_is_unallocated(self):
        owner, parent, segment, header = self.resume_fixture(midnight=True)
        self.scan()
        after = datetime(2026, 9, 24, 16, 2, tzinfo=timezone.utc)
        self.assertEqual(self.provider(now=after)["today_tokens"], 30)
        self.assertIsNone(self.provider(now=after - timedelta(hours=1))["today_tokens"])
        self.assertEqual(self.provider(now=after - timedelta(minutes=2, seconds=1))["today_tokens"], 600)

    def test_task_indexes_upgrade_scaled_ledger_without_changing_source_scope(self):
        owner, *_ = self.resume_fixture()
        self.scan()
        batch = self.ledger.export_batch()
        path = self.base / "indexed-hub.sqlite3"
        hub = UsageLedger(path)
        try:
            self.assertEqual(hub.ingest_batch("newer-pc", batch, self.now)["rejected"], 0)
            # A second device has only the first point in each counter stream.
            # Its answer must not borrow the newer device's larger counters.
            earlier = {}
            for event in batch["events"]:
                earlier.setdefault(event.get("counter_key") or event["session_key"], event)
            hub.ingest_batch("older-pc", {"version": 1, "events": list(earlier.values())}, self.now)

            template = next(event for event in batch["events"] if not event.get("counter_key"))
            for start in range(0, 3000, 200):
                noise = []
                for index in range(start, start + 200):
                    event = dict(template, session_key=f"{index + 1:064x}")
                    event["event_key"] = _event_key(event)
                    noise.append(event)
                self.assertEqual(hub.ingest_batch("unrelated-pc", {"version": 1, "events": noise}, self.now),
                                 {"accepted": 200, "rejected": 0})
            self.assertGreaterEqual(hub.db.execute("SELECT count(*) FROM usage_events").fetchone()[0], 3000)

            # Simulate an already populated pre-index ledger, including the
            # legacy nullable owner fallback. Reopening must add both indexes.
            hub.db.execute("UPDATE usage_sessions SET owner_key=NULL WHERE session_key=?", (template["session_key"],))
            hub.db.execute("DROP INDEX usage_sessions_effective_owner")
            hub.db.execute("DROP INDEX usage_events_counter_time")
            hub.db.commit()
            scopes = (None, "newer-pc", "older-pc", "unrelated-pc", "missing-pc")
            before = {scope: hub.task_usage("codex", owner, now=self.now, device_id=scope) for scope in scopes}
            self.assertEqual([before[scope]["total_tokens"] for scope in scopes], [700, 700, 190, None, None])
            hub.close()
            hub = UsageLedger(path)

            plans = []
            def trace(sql):
                if sql.startswith("SELECT") and "COALESCE(" in sql:
                    plans.extend(row[3] for row in hub.db.execute("EXPLAIN QUERY PLAN " + sql))
            hub.db.set_trace_callback(trace)
            try:
                after = {scope: hub.task_usage("codex", owner, now=self.now, device_id=scope) for scope in scopes}
            finally:
                hub.db.set_trace_callback(None)
            self.assertEqual(after, before)
            self.assertTrue(any("usage_sessions_effective_owner" in plan for plan in plans), plans)
            self.assertTrue(any("usage_events_counter_time" in plan for plan in plans), plans)
            self.assertFalse(any("SCAN s" in plan or "TEMP B-TREE" in plan for plan in plans), plans)
        finally:
            hub.close()

    def test_unknown_segment_anchor_can_count_later_deltas_without_unblocking_old_stream(self):
        owner = "own"
        self.write([self.meta(owner), self.codex_record(100, 20, self.now - timedelta(minutes=10)),
                    self.codex_record(5, 3, self.now - timedelta(minutes=9))], filename="old.jsonl")
        self.write([self.meta(owner, self.now - timedelta(minutes=8), history_base={
            "thread_id": "44444444-4444-4444-8444-444444444444", "end_byte_offset": 1000, "end_ordinal_exclusive": 2}),
            self.codex_record(150, 40, self.now - timedelta(minutes=7)), self.codex_record(170, 50)], filename="new.jsonl")
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 150)  # original 120 + known new delta 30
        self.assertIsNone(self.ledger.task_usage("codex", owner)["total_tokens"])
        self.assertEqual(self.ledger.db.execute("SELECT count(*) FROM usage_sessions WHERE blocked=1").fetchone()[0], 1)

    def test_late_parent_discovery_keeps_initial_segment_gap_partial(self):
        owner, parent, segment, header = self.resume_fixture()
        source = parent.read_bytes()
        parent.unlink()
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 30)
        parent.write_bytes(source)
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 630)  # no fabricated retroactive 70
        branches = [event for event in self.ledger.export_batch()["events"] if event.get("counter_key")]
        self.assertTrue(all(event["anchor_at"] is None for event in branches))

    def test_invalid_segment_anchor_boundary_keeps_first_gap_unknown(self):
        owner, parent, segment, header = self.resume_fixture()
        header["payload"]["history_base"]["end_byte_offset"] -= 1  # middle of record, not newline
        segment.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in (
            header, self.codex_record(150, 40, self.now - timedelta(minutes=1)), self.codex_record(170, 50))))
        self.scan()
        self.assertEqual(self.provider()["today_tokens"], 630)
        branch = [event for event in self.ledger.export_batch()["events"] if event.get("counter_key")]
        self.assertTrue(all(event["anchor_at"] is None for event in branch))

    def test_latest_malformed_parent_counter_must_not_fall_back_to_older_anchor(self):
        owner, parent, segment, header = self.resume_fixture()
        broken = self.codex_record(200, 40, self.now - timedelta(minutes=40))
        broken["payload"]["info"]["total_token_usage"]["total_tokens"] = 999
        parent.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in (
            self.meta(owner), self.codex_record(100, 20, self.now - timedelta(minutes=50)), broken)))
        header["payload"]["history_base"]["end_byte_offset"] = parent.stat().st_size
        segment.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in (
            header, self.codex_record(150, 40, self.now - timedelta(minutes=1)), self.codex_record(170, 50))))
        self.scan()
        branch = [event for event in self.ledger.export_batch()["events"] if event.get("counter_key")]
        self.assertTrue(all(event["anchor_at"] is None for event in branch))
        self.assertEqual(self.provider()["today_tokens"], 150)

    def test_quota_only_parent_record_does_not_obscure_verified_anchor(self):
        owner, parent, segment, header = self.resume_fixture()
        parent.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in (
            self.meta(owner), self.codex_record(100, 20, self.now - timedelta(minutes=50)),
            self.quota_record(20, self.now - timedelta(minutes=40)))))
        header["payload"]["history_base"]["end_byte_offset"] = parent.stat().st_size
        segment.write_bytes(b"".join(json.dumps(record).encode() + b"\n" for record in (
            header, self.codex_record(150, 40, self.now - timedelta(minutes=1)), self.codex_record(170, 50))))
        self.scan()
        branch = [event for event in self.ledger.export_batch()["events"] if event.get("counter_key")]
        self.assertTrue(all(event["anchor_input"] == 100 for event in branch))
        self.assertEqual(self.provider()["today_tokens"], 220)

    def test_anchor_probe_has_independent_byte_and_file_bounds(self):
        self.resume_fixture()
        with patch("agent_monitor.usage_ledger.HISTORY_ANCHOR_BYTES", 64), patch("agent_monitor.usage_ledger.HISTORY_ANCHOR_FILES", 1):
            report = self.scan()
        self.assertLessEqual(report["history_anchor_bytes"], 64)
        self.assertLessEqual(report["history_anchor_files"], 1)
        self.assertEqual(self.provider()["today_tokens"], 630)
        self.assertEqual(self.ledger.summary(self.now)["scan"]["history_anchor_bytes"], report["history_anchor_bytes"])

    def test_anchor_deadline_does_not_accept_incomplete_duplicate_verification(self):
        owner, parent, segment, header = self.resume_fixture()
        duplicate = self.codex / "archived_sessions" / parent.name
        duplicate.parent.mkdir(parents=True)
        duplicate.write_bytes(parent.read_bytes())
        self.scan()
        self.ledger._scan_deadline = 2
        self.ledger._anchor_bytes = self.ledger._anchor_files = 0
        from agent_monitor.usage_ledger import _hash
        with patch("agent_monitor.usage_ledger.time.monotonic", side_effect=[1, 3]):
            anchor = self.ledger._history_anchor(header["payload"]["history_base"], _hash("codex", owner),
                header["timestamp"], segment, self.now)
        self.assertIsNone(anchor)

    def test_segment_wire_rejects_unproven_or_malformed_anchor(self):
        self.resume_fixture()
        self.scan()
        event = next(event for event in self.ledger.export_batch()["events"] if event.get("counter_key"))
        for field, invalid in (("anchor_input", -1), ("anchor_output", True), ("anchor_at", _iso(self.now)),
                               ("anchor_input", event["input_tokens"] + 1), ("counter_key", "invalid")):
            with self.subTest(field=field, invalid=invalid):
                broken = copy.deepcopy(event)
                broken[field] = invalid
                self.assertIsNone(UsageLedger._validate_event(broken, self.now))

    def test_parser_migration_replays_local_codex_only_and_preserves_remote_rows(self):
        self.write([self.meta("shared"), self.codex_record(100, 20)], filename="shared.jsonl")
        self.write([self.meta("local-only"), self.codex_record(200, 50)], filename="local.jsonl")
        self.write([self.claude_record()], "claude")
        self.scan()
        shared = next(event for event in self.ledger.export_batch()["events"] if event["tool"] == "codex" and event["total_tokens"] == 120)
        self.ledger.ingest_batch("remote-computer", {"version": 1, "events": [shared]}, self.now)
        before = dict(self.ledger.db.execute("SELECT * FROM usage_events WHERE event_key=?", (shared["event_key"],)).fetchone())
        claude_before = dict(self.ledger.db.execute("SELECT * FROM usage_events WHERE tool='claude'").fetchone())
        with self.ledger.db:
            self.ledger.db.execute("UPDATE usage_sessions SET blocked=1,own_total=NULL")
            self.ledger.db.execute("UPDATE usage_meta SET value='1' WHERE key='local_codex_parser_version'")
        repaired = UsageLedger(self.base / "usage.sqlite3")
        self.addCleanup(repaired.close)
        self.assertEqual(dict(repaired.db.execute("SELECT * FROM usage_events WHERE event_key=?", (shared["event_key"],)).fetchone()), before)
        self.assertEqual(dict(repaired.db.execute("SELECT * FROM usage_events WHERE tool='claude'").fetchone()), claude_before)
        self.assertEqual(repaired.db.execute("SELECT count(*) FROM usage_events WHERE tool='codex'").fetchone()[0], 1)
        self.assertEqual(repaired.db.execute("SELECT sum(offset) FROM usage_files WHERE tool='codex'").fetchone()[0], 0)
        self.assertEqual(repaired.task_usage("codex", "shared")["total_tokens"], 120)
        self.assertTrue(all(event["tool"] == "claude" for event in repaired.export_batch()["events"]))
        repaired.scan(self.codex, self.claude, now=self.now)
        self.assertEqual(repaired.summary(self.now)["providers"][0]["today_tokens"], 370)
        offsets = repaired.db.execute("SELECT sum(offset) FROM usage_files").fetchone()[0]
        again = UsageLedger(self.base / "usage.sqlite3")
        self.addCleanup(again.close)
        self.assertEqual(again.db.execute("SELECT sum(offset) FROM usage_files").fetchone()[0], offsets)
        self.assertEqual(again.summary(self.now)["scan"]["local_codex_rebuilds"], 1)

    def test_recent_append_does_not_wait_behind_over_128_caught_up_recent_files(self):
        paths = [self.write([self.claude_record(response=f"msg-{i}")], "claude", f"{i}.jsonl") for i in range(160)]
        for path in paths:
            os.utime(path, (self.now.timestamp(),) * 2)
        for _ in range(6):
            self.scan()
            if self.provider("claude")["today_tokens"] == 160 * 170:
                break
        self.assertEqual(self.provider("claude")["today_tokens"], 160 * 170)
        with self.ledger.db:
            self.ledger.db.execute("UPDATE usage_files SET scanned_at=0")
            self.ledger.db.execute("UPDATE usage_files SET scanned_at=1e19 WHERE path=?", (str(paths[-1]),))
        with paths[-1].open("ab") as handle:
            handle.write(json.dumps(self.claude_record(response="fresh-append")).encode() + b"\n")
        os.utime(paths[-1], (self.now.timestamp() + 1,) * 2)
        report = self.scan(budget_bytes=1024)
        self.assertLessEqual(report["bytes_read"], 1024)
        self.assertEqual(self.provider("claude")["today_tokens"], 161 * 170)

    def test_recent_drain_reserves_byte_budget_for_old_history(self):
        old = self.write([self.meta("old")], filename="old.jsonl")
        hot = self.write([self.meta("hot")], filename="hot.jsonl")
        for path in (old, hot):
            with path.open("ab") as handle:
                handle.truncate(8 * 1024 * 1024)
        os.utime(old, (self.now.timestamp() - 30 * 86400,) * 2)
        os.utime(hot, (self.now.timestamp(),) * 2)
        report = self.scan(budget_bytes=1024 * 1024)
        self.assertLessEqual(report["bytes_read"], 1024 * 1024)
        old_offset = self.ledger.db.execute("SELECT offset FROM usage_files WHERE path=?", (str(old),)).fetchone()[0]
        self.assertGreater(old_offset, 0)

    def test_active_large_logs_for_both_tools_advance_each_round_during_rebuild(self):
        # A rebuilt ledger contains hundreds of older cursors, including large
        # files inside the hot shortlist. The running root/child Codex logs and
        # Claude must not wait for all those earlier scanned_at values.
        def large(tool, name, age, records):
            path = self.write(records, tool, name)
            with path.open("ab") as handle:
                handle.truncate(16 * 1024 * 1024)
            os.utime(path, (self.now.timestamp() - age,) * 2)
            return path

        for index in range(160):
            path = self.write([self.meta(f"done-{index}")], filename=f"done-{index}.jsonl")
            os.utime(path, (self.now.timestamp() - 86400,) * 2)
        for index in range(32):
            large("codex", f"recent-replay-{index}.jsonl", 3600 + index,
                  [self.meta(f"recent-replay-{index}")])
        old = [large("codex", f"history-{index}.jsonl", 30 * 86400 + index,
                     [self.meta(f"history-{index}")]) for index in range(40)]
        active = [large("codex", f"active-{index}.jsonl", 20 + index,
                        [self.meta(f"active-{index}"), self.codex_record(inp=100 + index)]) for index in range(2)]
        active.append(large("claude", "active-claude.jsonl", 10,
                            [self.claude_record(session="active-claude")]))
        roots = (("codex", self.codex / "sessions"), ("claude", self.claude / "projects"))
        self.ledger._discover(roots, float("inf"), self.now)
        self.assertGreater(self.ledger.db.execute("SELECT count(*) FROM usage_files").fetchone()[0], 128)
        # Completed files still occupy the oldest fair queue; live logs appear
        # recently scanned, and discovery has not refreshed them for 10 min.
        with self.ledger.db:
            self.ledger.db.execute("UPDATE usage_files SET offset=size WHERE path LIKE '%done-%'")
            for path in active:
                self.ledger.db.execute("UPDATE usage_files SET scanned_at=1e19,mtime=? WHERE path=?",
                                       (self.now.timestamp() - 600, str(path)))
        previous = {str(path): 0 for path in active}
        previous_old = 0
        with patch.object(self.ledger, "_discover", return_value=(0, 0)):
            for cycle in range(3):
                # Metadata refresh, not a full directory walk or tail read,
                # must notice new writes for both tools on each round.
                for index, path in enumerate(active):
                    os.utime(path, (self.now.timestamp() - index + cycle,) * 2)
                report = self.scan()
                self.assertLessEqual(report["bytes_read"], 8 * 1024 * 1024)
                for path in active:
                    row = self.ledger.db.execute("SELECT offset,mtime,meta FROM usage_files WHERE path=?", (str(path),)).fetchone()
                    self.assertGreater(row["offset"], previous[str(path)], (cycle, path.name))
                    self.assertGreater(row["mtime"], self.now.timestamp() - 60)
                    previous[str(path)] = row["offset"]
                old_offset = sum(self.ledger.db.execute("SELECT offset FROM usage_files WHERE path=?", (str(path),)).fetchone()[0]
                                 for path in old)
                self.assertGreater(old_offset, previous_old, cycle)
                previous_old = old_offset
        # The prefix events were parsed; no synthetic end-of-file baseline.
        self.assertEqual(self.provider("codex")["today_tokens"], 241)
        self.assertEqual(self.provider("claude")["today_tokens"], 170)

    def test_active_reservation_keeps_small_budget_and_total_deadline(self):
        paths = []
        for tool in ("codex", "claude"):
            path = self.write([], tool, "large.jsonl")
            with path.open("ab") as handle:
                handle.truncate(4 * 1024 * 1024)
            os.utime(path, (self.now.timestamp(),) * 2)
            paths.append(path)
        report = self.scan(budget_bytes=32)
        self.assertLessEqual(report["bytes_read"], 32)
        # Incomplete bounded reads remain in RAM at their durable line cursor.
        self.assertTrue(all(self.ledger._pending.get(str(path)) for path in paths))
        read_file = self.ledger._scan_file
        deadlines = []
        def observed(row, budget, deadline, now):
            deadlines.append(deadline)
            return read_file(row, budget, deadline, now)
        with patch("agent_monitor.usage_ledger.time.monotonic", return_value=100.0), \
                patch.object(self.ledger, "_scan_file", side_effect=observed):
            report = self.scan(budget_bytes=32)
        self.assertTrue(deadlines)
        self.assertTrue(all(deadline <= 102.0 for deadline in deadlines))
        self.assertLessEqual(report["bytes_read"], 32)

    def test_history_empty_is_90_ordered_unknown_days_with_bounded_payload(self):
        queries = []
        self.ledger.db.set_trace_callback(queries.append)
        result = self.ledger.summary(self.now)
        self.ledger.db.set_trace_callback(None)
        for provider in result["providers"]:
            history = provider["history"]
            self.assertEqual(history["timezone"], "Asia/Shanghai")
            self.assertEqual(history["start_day"], "2026-06-28")
            self.assertEqual(history["end_day"], "2026-09-25")
            self.assertEqual(len(history["days"]), 90)
            self.assertEqual([item["day"] for item in history["days"]], sorted({item["day"] for item in history["days"]}))
            self.assertTrue(all(item["tokens"] is None and item["coverage"] == "unavailable" for item in history["days"]))
        # Live snapshots carry only the fixed daily aggregates, with one range
        # query per provider; no per-day queries or transcript data.
        self.assertEqual(sum("GROUP BY day" in query for query in queries), 2)
        self.assertLess(len(json.dumps(result, separators=(",", ":")).encode()), 16000)

    def test_history_preserves_real_zero_and_unknown_cache_data(self):
        zero = self.claude_record(response="zero", input_tokens=0, output=0,
                                  cache_read_input_tokens=0, cache_creation_input_tokens=0)
        unknown = self.claude_record(response="unknown", stamp=self.now - timedelta(days=1))
        del unknown["message"]["usage"]["cache_creation_input_tokens"]
        self.write([zero, unknown], "claude")
        self.scan()
        days = self.provider("claude")["history"]["days"]
        self.assertEqual(days[-1], {"day": "2026-09-25", "tokens": 0, "coverage": "partial"})
        self.assertEqual(days[-2], {"day": "2026-09-24", "tokens": None, "coverage": "unavailable"})
        self.assertIsNone(days[-3]["tokens"])

    def test_history_window_excludes_old_and_future_records(self):
        stamps = [self.now - timedelta(days=90), self.now - timedelta(days=89), self.now,
                  self.now + timedelta(minutes=1), self.now + timedelta(days=1)]
        self.write([self.claude_record(response=f"window-{index}", stamp=stamp)
                    for index, stamp in enumerate(stamps)], "claude")
        # Store all records legitimately, then request an earlier summary.
        self.ledger.scan(self.codex, self.claude, now=self.now + timedelta(days=1))
        provider = self.provider("claude")
        history = provider["history"]
        self.assertEqual(len(history["days"]), 90)
        self.assertEqual(history["days"][0]["tokens"], 170)
        self.assertEqual(history["days"][-1]["tokens"], 170)
        self.assertEqual(provider["today_tokens"], history["days"][-1]["tokens"])
        # Once the recorded timestamp is reached, header and chart both gain
        # that observed contribution; future clock skew never splits them.
        later = self.provider("claude", self.now + timedelta(minutes=1))
        self.assertEqual(later["today_tokens"], 340)
        self.assertEqual(later["today_tokens"], later["history"]["days"][-1]["tokens"])
        self.assertEqual(sum(item["tokens"] or 0 for item in history["days"]), 340)
        self.assertNotIn("2026-06-27", {item["day"] for item in history["days"]})
        self.assertNotIn("2026-09-26", {item["day"] for item in history["days"]})

    def test_history_calendar_boundaries_use_shanghai_midnight(self):
        cases = [
            (datetime(2026, 9, 27, 16, 0, tzinfo=timezone.utc), "2026-09-27", "2026-09-28"),
            (datetime(2026, 9, 30, 16, 0, tzinfo=timezone.utc), "2026-09-30", "2026-10-01"),
            (datetime(2026, 12, 31, 16, 0, tzinfo=timezone.utc), "2026-12-31", "2027-01-01"),
            (datetime(2028, 2, 29, 16, 0, tzinfo=timezone.utc), "2028-02-29", "2028-03-01"),
        ]
        for index, (midnight, previous, today) in enumerate(cases):
            with self.subTest(today=today):
                self.write([self.claude_record(response=f"before-{index}", stamp=midnight - timedelta(seconds=1)),
                            self.claude_record(response=f"after-{index}", output=21, stamp=midnight)],
                           "claude", f"calendar-{index}.jsonl")
                self.ledger.scan(self.codex, self.claude, now=midnight)
                history = self.provider("claude", midnight)["history"]
                self.assertEqual(history["end_day"], today)
                self.assertEqual(history["days"][-2], {"day": previous, "tokens": 170, "coverage": "partial"})
                self.assertEqual(history["days"][-1], {"day": today, "tokens": 171, "coverage": "partial"})
                self.assertEqual(len(history["days"]), 90)

    def test_history_uses_codex_contributions_not_cumulative_or_last_totals(self):
        self.write([self.meta(), self.codex_record(100, 20, self.now - timedelta(minutes=2)),
                    self.codex_record(150, 40, self.now - timedelta(minutes=1)),
                    self.codex_record(150, 40)])
        self.scan()
        self.assertEqual(self.provider()["history"]["days"][-1]["tokens"], 190)
        self.assertTrue(all(item["tokens"] is None for item in self.provider()["history"]["days"][:-1]))

    def test_history_source_scope_and_copied_event_deduplication(self):
        hub = UsageLedger(self.base / "history-hub.sqlite3")
        self.addCleanup(hub.close)
        self.write([self.claude_record(response="shared", stamp=self.now - timedelta(days=1))], "claude")
        self.scan()
        shared = self.ledger.export_batch()
        hub.ingest_batch("computer-a", shared, self.now)
        hub.ingest_batch("computer-b", shared, self.now)
        self.ledger.ack_export(shared["cursor"])
        for source, output in (("computer-a", 21), ("computer-b", 22)):
            self.write([self.claude_record(response=source, output=output)], "claude", mode="ab")
            self.scan()
            batch = self.ledger.export_batch()
            hub.ingest_batch(source, batch, self.now)
            self.ledger.ack_export(batch["cursor"])
        for scope, yesterday, today in ((["computer-a"], 170, 171), (["computer-b"], 170, 172),
                                         (["computer-a", "computer-b"], 170, 343),
                                         (["removed-computer"], None, None), ([], None, None)):
            with self.subTest(scope=scope):
                days = hub.summary(self.now, device_ids=scope)["providers"][1]["history"]["days"]
                self.assertEqual(days[-2]["tokens"], yesterday)
                self.assertEqual(days[-1]["tokens"], today)
                self.assertEqual(days[-1]["coverage"], "partial" if today is not None else "unavailable")
                self.assertTrue(all(item["tokens"] is None for item in days[:-2]))
        unscoped = hub.summary(self.now)["providers"][1]
        self.assertEqual(unscoped["history"]["days"][-2]["tokens"], 170)
        self.assertEqual(unscoped["quotas"], [])  # Tokens never invent quota percentages.


if __name__ == "__main__":
    unittest.main()
