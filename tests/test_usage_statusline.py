from __future__ import annotations

import concurrent.futures
import hashlib
import importlib.util
import io
import json
import os
import shlex
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from agent_monitor import usage_statusline as capture
from agent_monitor.usage_ledger import UsageLedger

INSTALLER = Path(__file__).resolve().parents[1] / "scripts" / "install-usage-statusline.py"
spec = importlib.util.spec_from_file_location("monitor_statusline_installer", INSTALLER)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
NOW = datetime(2026, 9, 25, 8, 0, tzinfo=timezone.utc)


def payload(used=25, reset=None):
    return {"rate_limits": {"five_hour": {"used_percentage": used, "resets_at": int((NOW + timedelta(hours=1)).timestamp()) if reset is None else reset}},
            "session_id": "never-save-me", "transcript_path": "private", "secret": "must-not-survive"}


class UsageStatuslineTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.output = self.root / "monitor-quota.json"

    def tearDown(self):
        self.directory.cleanup()

    def test_whitelist_and_zero(self):
        result = capture.sanitize(payload(0), NOW)
        self.assertEqual(set(result), {"observed_at", "rate_limits"})
        self.assertEqual(result["rate_limits"]["five_hour"], {"used_percentage": 0, "resets_at": int((NOW + timedelta(hours=1)).timestamp())})
        self.assertNotIn("never-save", json.dumps(result))
        self.assertEqual(capture.sanitize({}, NOW)["rate_limits"], {})
        self.assertIsNone(capture.sanitize([]))
        self.assertIsNone(capture.sanitize({"rate_limits": "bad"}))

    def test_invalid_numbers_are_not_zero(self):
        for bad in [True, False, "50", None, -1, 101, 10 ** 500, float("inf"), float("nan")]:
            with self.subTest(bad=bad):
                self.assertEqual(capture.sanitize(payload(bad), NOW)["rate_limits"], {})
        for bad in [True, "1790000000", None, -1, 1.2, 1790000000000, 10 ** 500, float("inf")]:
            value = payload(); value["rate_limits"]["five_hour"]["resets_at"] = bad
            with self.subTest(reset=bad): self.assertEqual(capture.sanitize(value, NOW)["rate_limits"], {})

    def test_two_windows_independent_and_no_gateway_guess(self):
        value = payload(7.5)
        value["rate_limits"]["seven_day"] = {"used_percentage": 99, "resets_at": int((NOW + timedelta(days=2)).timestamp())}
        value["rate_limits"]["spend_limit"] = {"used_percentage": 250, "resets_at": int(NOW.timestamp())}
        result = capture.sanitize(value, NOW)
        self.assertEqual(set(result["rate_limits"]), {"five_hour", "seven_day"})
        self.assertEqual(result["rate_limits"]["five_hour"]["used_percentage"], 7.5)

    def test_expired_is_recorded_as_expired_and_not_reset(self):
        capture.record(payload(100, int((NOW - timedelta(seconds=1)).timestamp())), self.output, NOW)
        result = json.loads(self.output.read_text())
        self.assertEqual(result["rate_limits"]["five_hour"]["used_percentage"], 100)
        ledger = UsageLedger(self.root / "ledger.sqlite3")
        try:
            self.assertEqual(ledger.ingest_claude_quota(result, now=NOW)["accepted"], 1)
            quota = next(p for p in ledger.summary(now=NOW)["providers"] if p["tool"] == "claude")["quotas"][0]
            self.assertTrue(quota["stale"])
            self.assertEqual(quota["availability"], "expired")
            self.assertEqual(quota["remaining_percent"], 0)
        finally: ledger.close()

    def test_older_concurrent_snapshot_cannot_overwrite_newer(self):
        self.assertTrue(capture.record(payload(35), self.output, NOW + timedelta(seconds=1)))
        self.assertFalse(capture.record(payload(12), self.output, NOW))
        self.assertEqual(json.loads(self.output.read_text())["rate_limits"]["five_hour"]["used_percentage"], 35)

    def test_atomic_concurrent_writes(self):
        capture.record(payload(0), self.output, NOW)
        errors = []
        stop = threading.Event()
        def reader():
            while not stop.is_set():
                try:
                    json.loads(self.output.read_bytes())
                except PermissionError:
                    pass  # Windows may transiently deny replace/open sharing.
                except Exception as error: errors.append(type(error).__name__)
        reading = threading.Thread(target=reader); reading.start()
        def write(index):
            try: return capture.record(payload(index), self.output, NOW + timedelta(microseconds=index))
            except OSError: return False  # Receiver may skip a contended sample without delaying the UI.
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                list(pool.map(write, range(1, 21)))
        finally: stop.set(); reading.join()
        capture.record(payload(21), self.output, NOW + timedelta(seconds=1))
        self.assertEqual(errors, [])
        self.assertEqual(json.loads(self.output.read_text())["rate_limits"]["five_hour"]["used_percentage"], 21)
        self.assertFalse(list(self.root.glob("*.tmp")))

    def test_broken_existing_json_recovers_and_duplicates_reject(self):
        self.output.write_bytes(b'{"broken":')
        self.assertTrue(capture.record(payload(), self.output, NOW))
        with self.assertRaises(ValueError): capture.decode(b'{"rate_limits":{},"rate_limits":{}}')
        with self.assertRaises(ValueError): capture.decode(b'{"x":NaN}')

    def test_simple_fallback_never_claims_a_balance(self):
        out = io.BytesIO()
        config = {"original_command": None, "quota_path": str(self.output)}
        self.assertEqual(capture.run(config, io.BytesIO(json.dumps(payload()).encode()), stdout=out), 0)
        self.assertEqual(out.getvalue(), b"Claude Code\n")
        self.assertTrue(self.output.is_file())

    def test_bad_and_oversized_input_do_not_pollute_quota(self):
        config = {"original_command": None, "quota_path": str(self.output)}
        for raw in [b"{broken", b"x" * (capture.MAX_INPUT + 1)]:
            self.assertEqual(capture.run(config, io.BytesIO(raw), stdout=io.BytesIO()), 0)
            self.assertFalse(self.output.exists())

    def test_original_stdin_stdout_stderr_exit_are_preserved(self):
        kind, shell = installer.shell_choice()
        if kind not in ("bash", "posix"): self.skipTest("byte-exact shell fixture uses bash")
        raw = json.dumps(payload() | {"text": "$(echo injected) `test` ; 中文"}, ensure_ascii=False).encode()
        code = "import sys; value=sys.stdin.buffer.read(); sys.stdout.buffer.write(value); sys.stderr.buffer.write(b'original-stderr'); raise SystemExit(7)"
        command = shlex.join([str(Path(sys.executable).resolve()).replace("\\", "/"), "-c", code])
        config = {"original_command": command, "shell_kind": kind, "shell_executable": shell, "quota_path": str(self.output)}
        with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
            self.assertEqual(capture.run(config, io.BytesIO(raw), stdout=out, stderr=err), 7)
            out.seek(0); err.seek(0)
            self.assertEqual(out.read(), raw); self.assertEqual(err.read(), b"original-stderr")

    def test_oversized_original_input_is_streamed_unchanged_without_capture(self):
        kind, shell = installer.shell_choice()
        if kind not in ("bash", "posix"): self.skipTest("byte-exact shell fixture uses bash")
        raw = b"x" * (capture.MAX_INPUT * 2 + 3)
        code = "import sys,hashlib; sys.stdout.write(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
        command = shlex.join([str(Path(sys.executable).resolve()).replace("\\", "/"), "-c", code])
        config = {"original_command": command, "shell_kind": kind, "shell_executable": shell, "quota_path": str(self.output)}
        with tempfile.TemporaryFile() as out:
            self.assertEqual(capture.run(config, io.BytesIO(raw), stdout=out), 0)
            out.seek(0); self.assertEqual(out.read().decode(), hashlib.sha256(raw).hexdigest())
        self.assertFalse(self.output.exists())

    def test_preview_exact_patch_and_backup(self):
        settings = self.root / "settings.json"
        raw = b'\xef\xbb\xbf{\r\n "env": { "UNCHANGED_SECRET" : "value" },\r\n "statusLine": {"type":"command", "command":"printf old", "padding": 3},\r\n "custom": [1, 2]\r\n}\r\n'
        settings.write_bytes(raw)
        plan, changed, config = installer.prepare(settings)
        self.assertEqual(settings.read_bytes(), raw)
        self.assertFalse((self.root / "monitor-statusline.json").exists())
        old_span = installer.members(raw.decode("utf-8-sig"))[0]["statusLine"]
        text = raw.decode("utf-8-sig"); status = text[old_span[0]:old_span[1]]
        a, b = installer.members(status)[0]["command"]
        expected = text[:old_span[0] + a] + json.dumps(plan["new_statusLine_command"], ensure_ascii=False) + text[old_span[0] + b:]
        self.assertEqual(changed, b'\xef\xbb\xbf' + expected.encode())
        self.assertEqual(config["original_command"], "printf old")
        self.assertNotIn("UNCHANGED_SECRET", json.dumps(plan))
        applied = installer.apply(settings, plan["expected_sha256"])
        self.assertEqual(Path(applied["backup"]).read_bytes(), raw)
        self.assertEqual(settings.read_bytes(), changed)
        self.assertFalse(installer.prepare(settings)[0]["changed"])

    def test_empty_install_conflict_and_duplicate_settings(self):
        settings = self.root / "settings.json"
        plan, changed, config = installer.prepare(settings)
        self.assertIsNone(config["original_command"])
        self.assertEqual(capture.decode(changed)["statusLine"]["type"], "command")
        settings.write_text('{"new_setting":true}', encoding="utf-8")
        with self.assertRaises(ValueError): installer.apply(settings, plan["expected_sha256"])
        self.assertEqual(settings.read_text(), '{"new_setting":true}')
        settings.write_text('{"a":1,"a":2}', encoding="utf-8")
        with self.assertRaises(ValueError): installer.prepare(settings)

    def test_shell_argument_quoting(self):
        args = ["/tmp/test a'$(touch nope)`x`/python", "--config", "/tmp/配置 file.json"]
        self.assertEqual(shlex.split(installer.quote_command(args, "bash")), args)
        powershell = installer.quote_command(args, "powershell")
        self.assertTrue(powershell.startswith("& '"))
        self.assertIn("a''$(touch nope)", powershell)

    def test_link_output_is_not_followed(self):
        target = self.root / "untouched.json"; target.write_text("untouched")
        try: self.output.symlink_to(target)
        except OSError: self.skipTest("symlink creation is not permitted")
        self.assertFalse(capture.record(payload(), self.output, NOW))
        self.assertEqual(target.read_text(), "untouched")


if __name__ == "__main__": unittest.main()
