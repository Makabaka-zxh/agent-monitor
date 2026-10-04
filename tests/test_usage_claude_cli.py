from __future__ import annotations

import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from agent_monitor import usage_claude_cli as cli
from agent_monitor.usage_ledger import UsageLedger


NOW = datetime(2026, 9, 26, 2, tzinfo=timezone.utc)
PRIVATE = "DO_NOT_RETAIN_ACCOUNT_OR_MESSAGE"


def row(kind="session", percent=25, reset="2026-09-26T04:00:00Z", **extra):
    return {"kind": kind, "percent": percent, "resets_at": reset, **extra}


def wire(rows=None, *, result=None, reports=1):
    lines = [{"type": "system", "session_id": PRIVATE},
             {"type": "auth_status", "output": PRIVATE}]
    for _ in range(reports):
        lines.append({"type": "assistant", "session_id": PRIVATE,
                      "message": {"content": [{"type": "text", "text": PRIVATE}]},
                      "usage_report": {"session": {"secret": PRIVATE},
                                       "rate_limits": {"limits": [row()] if rows is None else rows}}})
    lines.append({"type": "result", "subtype": "success", "local_command": "usage",
                  "num_turns": 0, "duration_api_ms": 0, "result": PRIVATE, **(result or {})})
    return b"\n".join(json.dumps(line).encode() for line in lines)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def monotonic(self):
        return self.t

    def utcnow(self):
        return NOW + timedelta(seconds=self.t - 1000)


class ParseUsageTests(unittest.TestCase):
    def test_only_numeric_windows_survive_and_ingest(self):
        read = cli.parse_usage_output(wire([row(percent=0), row("weekly_all", 87.5)]), NOW)
        self.assertEqual(read.status, "ok")
        self.assertEqual(set(read.snapshot), {"observed_at", "rate_limits"})
        self.assertEqual(set(read.snapshot["rate_limits"]), {"five_hour", "seven_day"})
        self.assertNotIn(PRIVATE, json.dumps(asdict(read)))
        with tempfile.TemporaryDirectory() as directory:
            ledger = UsageLedger(Path(directory) / "ledger.sqlite3")
            try:
                self.assertEqual(ledger.ingest_claude_quota(read.snapshot, now=NOW),
                                 {"accepted": 2, "rejected": 0})
            finally:
                ledger.close()

    def test_scoped_and_future_meters_are_not_mixed_with_totals(self):
        rows = [row(), row("weekly_all", 63), row("weekly_sonnet", 99),
                row("weekly_scoped", 99, scope={"model": {"display_name": PRIVATE}}),
                row("session", 90, scope={"surface": {"display_name": PRIVATE}})]
        read = cli.parse_usage_output(wire(rows), NOW)
        self.assertEqual(read.status, "ok")
        self.assertEqual(read.snapshot["rate_limits"]["five_hour"]["used_percentage"], 25)
        self.assertEqual(read.snapshot["rate_limits"]["seven_day"]["used_percentage"], 63)

    def test_duplicate_unscoped_window_rejects_whole_report(self):
        for duplicate in (row(), row(percent=80)):
            with self.subTest(duplicate=duplicate):
                read = cli.parse_usage_output(wire([row(), row("weekly_all"), duplicate]), NOW)
                self.assertEqual(read.status, "ambiguous_windows")
                self.assertIsNone(read.snapshot)

    def test_nullable_reset_is_not_invented_and_week_survives(self):
        read = cli.parse_usage_output(wire([row(reset=None), row("weekly_all")]), NOW)
        self.assertEqual(read.status, "ok")
        self.assertIsNone(read.snapshot["rate_limits"]["five_hour"]["resets_at"])
        self.assertIsInstance(read.snapshot["rate_limits"]["seven_day"]["resets_at"], int)
        with tempfile.TemporaryDirectory() as directory:
            ledger = UsageLedger(Path(directory) / "ledger.sqlite3")
            try:
                self.assertEqual(ledger.ingest_claude_quota(read.snapshot, now=NOW)["accepted"], 2)
            finally:
                ledger.close()

    def test_invalid_percentage_and_reset_fail_closed(self):
        for value in (True, False, -1, 101, "42", None, 10**500):
            with self.subTest(percent=value):
                self.assertIsNone(cli.parse_usage_output(wire([row(percent=value)]), NOW).snapshot)
        for value in (True, 1790000000, "bad", "2026-09-26T04:00:00", "2026-09-26T04:00:00.5",
                      "1970-01-01T00:00:00Z", "1969-12-31T23:59:59.5Z", "9999-12-31T23:59:59.5Z"):
            with self.subTest(reset=value):
                self.assertIsNone(cli.parse_usage_output(wire([row(reset=value)]), NOW).snapshot)
        missing = row(); del missing["resets_at"]
        self.assertEqual(cli.parse_usage_output(wire([missing]), NOW).status, "invalid_report")

    def test_fractional_resets_round_up_without_rejecting_other_windows(self):
        whole = int(datetime(2026, 9, 26, 4, tzinfo=timezone.utc).timestamp())
        for value, expected in (("2026-09-26T04:00:00.5Z", whole + 1),
                                ("2026-09-26T04:00:00.000001Z", whole + 1),
                                ("2026-09-26T04:00:00.999999Z", whole + 1),
                                ("2026-09-26T12:00:00.5+08:00", whole + 1),
                                ("2026-09-26T00:00:00.5-04:00", whole + 1),
                                ("2026-09-26T04:00:00.000000Z", whole)):
            with self.subTest(reset=value):
                read = cli.parse_usage_output(wire([row(reset=value), row("weekly_all", 37.5)]), NOW)
                self.assertEqual(read.status, "ok")
                self.assertEqual(read.snapshot["rate_limits"]["five_hour"]["resets_at"], expected)
                self.assertEqual(read.snapshot["rate_limits"]["seven_day"]["used_percentage"], 37.5)

    def test_fractional_resets_ingest_and_do_not_expire_early(self):
        reset = datetime(2026, 9, 26, 4, tzinfo=timezone.utc)
        now = reset + timedelta(microseconds=400000)
        read = cli.parse_usage_output(wire([row(percent=62.5, reset="2026-09-26T04:00:00.5Z"),
                                            row("weekly_all", 29, reset=None)]), now)
        self.assertEqual(read.status, "ok")
        with tempfile.TemporaryDirectory() as directory:
            ledger = UsageLedger(Path(directory) / "ledger.sqlite3")
            try:
                self.assertEqual(ledger.ingest_claude_quota(read.snapshot, now=now),
                                 {"accepted": 2, "rejected": 0})
                quotas = ledger.summary(now=now)["providers"][1]["quotas"]
                self.assertEqual(len(quotas), 2)
                self.assertEqual(quotas[0]["resets_at"], int(reset.timestamp()) + 1)
                self.assertEqual(quotas[0]["remaining_percent"], 37.5)
                self.assertEqual(datetime.fromisoformat(quotas[0]["observed_at"].replace("Z", "+00:00")), now)
                self.assertEqual(quotas[0]["availability"], "observed")
                self.assertFalse(quotas[0]["stale"])
                self.assertIsNone(quotas[1]["resets_at"])
                expired = ledger.summary(now=reset + timedelta(seconds=1))["providers"][1]["quotas"][0]
                self.assertEqual(expired["availability"], "expired")
                self.assertTrue(expired["stale"])
            finally:
                ledger.close()

    def test_sanitized_official_fractional_report_replay(self):
        # Only numeric windows from the authenticated diagnostic are retained.
        # The provider-specific scoped meter must not become a total.
        read = cli.parse_usage_output(wire([
            row(percent=9, reset="2026-09-26T07:19:59.989519+00:00"),
            row("weekly_all", 75, reset="2026-10-02T00:59:59.989540+00:00"),
            row("other", 66, reset="2026-10-02T00:59:59.989711+00:00", scope={"model": {}}),
        ]), NOW)
        self.assertEqual(read.status, "ok")
        self.assertEqual(read.snapshot["rate_limits"], {
            "five_hour": {"used_percentage": 9, "resets_at": int(datetime(2026, 9, 26, 7, 20, tzinfo=timezone.utc).timestamp())},
            "seven_day": {"used_percentage": 75, "resets_at": int(datetime(2026, 10, 2, 1, tzinfo=timezone.utc).timestamp())},
        })

    def test_expired_window_is_not_reset_to_zero(self):
        read = cli.parse_usage_output(wire([row(percent=100, reset="2026-09-25T04:00:00Z")]), NOW)
        self.assertEqual(read.status, "ok")
        self.assertEqual(read.snapshot["rate_limits"]["five_hour"]["used_percentage"], 100)
        self.assertLess(read.snapshot["rate_limits"]["five_hour"]["resets_at"], NOW.timestamp())

    def test_result_must_prove_zero_model_calls(self):
        for bad in ({"num_turns": 1}, {"num_turns": False}, {"duration_api_ms": 1},
                    {"duration_api_ms": False}, {"local_command": "other"},
                    {"subtype": "error"}, {"is_error": True}):
            with self.subTest(result=bad):
                read = cli.parse_usage_output(wire(result=bad), NOW)
                self.assertEqual(read.status, "local_execution_unverified")
                self.assertIsNone(read.snapshot)

    def test_ambiguous_missing_and_nested_reports_do_not_ingest(self):
        self.assertEqual(cli.parse_usage_output(wire(reports=2), NOW).status, "ambiguous_reports")
        self.assertEqual(cli.parse_usage_output(wire(reports=0), NOW).status, "no_usage_report")
        self.assertEqual(cli.parse_usage_output(wire([]), NOW).status, "no_numeric_windows")
        nested = wire().replace(b'"usage_report":', b'"usageReport":')
        self.assertEqual(cli.parse_usage_output(nested, NOW).status, "no_usage_report")
        only_scoped = cli.parse_usage_output(wire([row("weekly_scoped")]), NOW)
        self.assertIsNone(only_scoped.snapshot)

    def test_limits_null_and_nonfinite_duplicate_json_are_unknown(self):
        raw = wire().replace(b'"limits": [{"kind"', b'"limits": [{"kind"')
        events = [json.loads(line) for line in raw.splitlines()]
        events[2]["usage_report"]["rate_limits"]["limits"] = None
        self.assertEqual(cli.parse_usage_output(b"\n".join(json.dumps(e).encode() for e in events), NOW).status, "no_live_limits")
        for invalid in (b'{"type":"result","type":"assistant"}', b'{"x":NaN}', b'not-json'):
            self.assertEqual(cli.parse_usage_output(invalid, NOW).status, "invalid_output")

    def test_output_and_line_caps(self):
        self.assertEqual(cli.parse_usage_output(b" " * (cli.MAX_OUTPUT + 1), NOW).status, "output_limit")
        self.assertEqual(cli.parse_usage_output(b'{"x":"' + b"x" * cli.MAX_LINE + b'"}', NOW).status, "output_limit")


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "claude.exe"
        self.path.write_bytes(b"fixed executable fixture")
        self.sha = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.clock = Clock()
        self.calls = []
        self.auth = {"loggedIn": True, "authMethod": "claude.ai", "email": PRIVATE}
        self.usage = cli.CommandResult(0, wire())

    def tearDown(self):
        self.directory.cleanup()

    def runner(self, args, timeout):
        self.calls.append((args, timeout))
        if args[-1] == "--version":
            return cli.CommandResult(0, b"2.1.281 (Claude Code)\n")
        if "auth" in args:
            return cli.CommandResult(0 if self.auth.get("loggedIn") else 1, json.dumps(self.auth).encode())
        return self.usage

    def reader(self, **kwargs):
        return cli.ClaudeCliQuotaReader(self.path, self.sha, enabled=True,
                                       monotonic=self.clock.monotonic, utcnow=self.clock.utcnow,
                                       runner=self.runner, **kwargs)

    def test_disabled_default_has_no_io(self):
        with patch.object(cli, "_run_command") as process, patch.object(Path, "open") as opened:
            read = cli.ClaudeCliQuotaReader().poll()
        self.assertEqual(read.status, "disabled")
        process.assert_not_called(); opened.assert_not_called()

    def test_pin_and_version_fail_closed_without_fallback(self):
        for path, sha in ((Path("claude.exe"), self.sha), (self.path, "x"), (self.path, "0"*64)):
            with self.subTest(path=path, sha=sha):
                reader = cli.ClaudeCliQuotaReader(path, sha, enabled=True, runner=self.runner)
                self.assertIsNone(reader.poll().snapshot)
                self.assertEqual(self.calls, [])
        reader = self.reader(version="2.1.282")
        self.assertEqual(reader.poll().status, "invalid_pin")
        self.assertEqual(self.calls, [])
        reader = self.reader(); reader._runner = lambda *args: cli.CommandResult(0, b"2.1.282 (Claude Code)")
        self.assertEqual(reader.poll().status, "version_mismatch")

    def test_unsafe_path_never_executes(self):
        with patch.object(cli, "safe_path", return_value=False):
            self.assertEqual(self.reader().poll().status, "unsafe_executable")
        self.assertFalse(self.calls)

    def test_unlogged_and_api_auth_do_not_start_usage(self):
        for auth, expected in (({"loggedIn": False, "authMethod": "none"}, "not_logged_in"),
                               ({"loggedIn": True, "authMethod": "api_key"}, "api_key_only"),
                               ({"loggedIn": True, "authMethod": "third_party"}, "unsupported_provider")):
            self.auth = auth; self.calls.clear()
            self.assertEqual(self.reader().poll().status, expected)
            self.assertEqual(len(self.calls), 2)
            self.assertFalse(any("-p" in args for args, _ in self.calls))

    def test_commands_are_explicit_and_bounded(self):
        read = self.reader().poll()
        self.assertEqual(read.status, "ok")
        args, timeout = self.calls[-1]
        self.assertEqual(args[0], str(self.path))
        self.assertEqual(args[-2:], ["-p", "/usage"])
        self.assertIn("--safe-mode", args); self.assertIn("--no-session-persistence", args)
        self.assertIn("--strict-mcp-config", args)
        self.assertEqual(args[args.index("--tools")+1], "")
        self.assertEqual(json.loads(args[args.index("--settings")+1]), {"disableAllHooks": True})
        self.assertLessEqual(timeout, 45)
        self.assertEqual(self.reader().last_phase, "idle")

    def test_five_minute_floor_failure_backoff_and_success_reset(self):
        reader = self.reader()
        self.usage = cli.CommandResult(None, error="timeout")
        for delay in (300, 600, 1200, 2400, 3600, 3600):
            read = reader.poll()
            self.assertEqual(read.status, "timeout")
            self.assertEqual(read.retry_after_seconds, delay)
            n = len(self.calls)
            self.clock.t += delay-1
            self.assertEqual(reader.poll().status, "throttled")
            self.assertEqual(len(self.calls), n)
            self.clock.t += 1
        self.usage = cli.CommandResult(0, wire())
        self.assertEqual(reader.poll().retry_after_seconds, 300)

    def test_failure_never_returns_fresh_snapshot_or_changes_previous(self):
        reader = self.reader(); first = reader.poll()
        old = json.dumps(first.snapshot, sort_keys=True)
        self.clock.t += 300; self.usage = cli.CommandResult(1, PRIVATE.encode())
        failed = reader.poll()
        self.assertEqual(failed.status, "official_command_failed")
        self.assertIsNone(failed.snapshot)
        self.assertEqual(json.dumps(first.snapshot, sort_keys=True), old)

    def test_parent_deadline_covers_all_commands(self):
        def slow(args, timeout):
            answer = self.runner(args, timeout)
            self.clock.t += 18
            return answer
        reader = self.reader(); reader._runner = slow
        self.assertEqual(reader.poll().status, "timeout")
        self.assertEqual([timeout for _, timeout in self.calls], [45, 27, 9])
        self.assertEqual(reader.last_phase, "usage")

    def test_slow_official_usage_can_finish_within_shared_budget(self):
        def slow(args, timeout):
            answer = self.runner(args, timeout)
            self.clock.t += 1 if "--version" in args or "auth" in args else 30
            return answer
        reader = self.reader(); reader._runner = slow
        self.assertEqual(reader.poll().status, "ok")
        self.assertEqual([timeout for _, timeout in self.calls], [45, 44, 43])
        self.assertEqual(reader.last_phase, "complete")

    def test_auth_timeout_is_classified_without_starting_usage(self):
        def fail_auth(args, timeout):
            if "auth" in args:
                return cli.CommandResult(None, error="timeout")
            return self.runner(args, timeout)
        reader = self.reader(); reader._runner = fail_auth
        self.assertEqual(reader.poll().status, "timeout")
        self.assertEqual(reader.last_phase, "auth")
        self.assertFalse(any("/usage" in args for args, _ in self.calls))

    def test_changed_binary_between_checks_prevents_query(self):
        def change(args, timeout):
            answer = self.runner(args, timeout)
            if "auth" in args:
                self.path.write_bytes(b"different executable")
            return answer
        reader = self.reader(); reader._runner = change
        self.assertEqual(reader.poll().status, "pin_mismatch")
        self.assertEqual(len(self.calls), 2)

    def test_concurrent_poll_does_not_launch_twice(self):
        entered = threading.Event(); release = threading.Event()
        def blocked(args, timeout):
            entered.set(); release.wait(3)
            return self.runner(args, timeout)
        reader = self.reader(); reader._runner = blocked
        result = []
        thread = threading.Thread(target=lambda: result.append(reader.poll()))
        thread.start()
        try:
            self.assertTrue(entered.wait(1))
            self.assertEqual(reader.poll().status, "busy")
        finally:
            release.set(); thread.join(3)
        self.assertEqual(result[0].status, "ok")
        self.assertEqual(len(self.calls), 3)

    def test_no_raw_error_or_account_data_in_results_or_console(self):
        reader = self.reader()
        def broken(*_):
            raise RuntimeError(PRIVATE)
        reader._runner = broken
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            read = reader.poll()
        self.assertEqual(read.status, "runner_failed")
        self.assertNotIn(PRIVATE, json.dumps(asdict(read)))
        self.assertEqual(out.getvalue()+err.getvalue(), "")
        self.assertNotIn(PRIVATE, repr(cli.CommandResult(0, PRIVATE.encode())))


class ChildContainmentTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Windows suspended/job assignment regression")
    def test_delayed_job_assignment_cannot_spawn_uncontained_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory)/"owned-child-marker.txt"
            ran = Path(directory)/"parent-started.txt"
            child = "import time,pathlib;time.sleep(1.2);pathlib.Path(" + repr(str(marker)) + ").write_text('test')"
            parent = ("import pathlib,subprocess,sys,time;pathlib.Path(" + repr(str(ran))
                      + ").write_text('started');subprocess.Popen([sys.executable,'-c',"
                      + repr(child) + "]);time.sleep(3)")
            original = cli._WindowsJob.assign
            def delayed(job, process):
                time.sleep(.5)
                self.assertFalse(ran.exists(), "child ran before job assignment")
                return original(job, process)
            with patch.object(cli._WindowsJob, "assign", delayed):
                result = cli._run_command([sys.executable, "-c", parent], 1)
            self.assertEqual(result.error, "timeout")
            self.assertTrue(ran.exists(), "test did not exercise resumed parent")
            time.sleep(1.5)
            self.assertFalse(marker.exists())

    @unittest.skipUnless(os.name == "nt", "Windows suspended process fail-closed cleanup")
    def test_resume_failure_never_executes_suspended_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory)/"must-not-start.txt"
            command = "import pathlib;pathlib.Path(" + repr(str(marker)) + ").write_text('started')"
            with patch.object(cli._WindowsJob, "resume", side_effect=OSError("fixture failure")):
                result = cli._run_command([sys.executable, "-c", command], 1)
            self.assertEqual(result.error, "containment_failed")
            self.assertFalse(marker.exists())

    def test_transport_only_keeps_stdout(self):
        result = cli._run_command([sys.executable, "-c", "import sys;print('ok');sys.stderr.write('PRIVATE')"], 3)
        self.assertIsNone(result.error)
        self.assertEqual(result.stdout.strip(), b"ok")

    def test_combined_output_cap_discards_everything(self):
        result = cli._run_command([sys.executable, "-c", "import sys;sys.stderr.write('x'*1500000)"], 3)
        self.assertEqual(result.error, "output_limit")
        self.assertEqual(result.stdout, b"")

    def test_timeout_kills_owned_process(self):
        started = time.monotonic()
        result = cli._run_command([sys.executable, "-c", "import time;time.sleep(20)"], 1)
        self.assertEqual(result.error, "timeout")
        self.assertEqual(result.stdout, b"")
        self.assertLess(time.monotonic()-started, 2)

    def test_timeout_does_not_leave_child_to_write_later(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory)/"must-not-exist.txt"
            child = "import time,pathlib;time.sleep(2);pathlib.Path(" + repr(str(marker)) + ").write_text('orphan')"
            parent = "import subprocess,sys,time;subprocess.Popen([sys.executable,'-c'," + repr(child) + "]);time.sleep(20)"
            result = cli._run_command([sys.executable, "-c", parent], 1)
            self.assertEqual(result.error, "timeout")
            time.sleep(2.1)
            self.assertFalse(marker.exists())

    def test_normal_parent_exit_also_cleans_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory)/"must-not-exist.txt"
            child = "import time,pathlib;time.sleep(2);pathlib.Path(" + repr(str(marker)) + ").write_text('orphan')"
            parent = "import subprocess,sys;subprocess.Popen([sys.executable,'-c'," + repr(child) + "]);print('ok')"
            result = cli._run_command([sys.executable, "-c", parent], 3)
            self.assertIsNone(result.error)
            self.assertEqual(result.stdout.strip(), b"ok")
            time.sleep(2.1)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
