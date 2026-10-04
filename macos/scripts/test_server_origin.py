"""Offline server boundary and persisted pairing checks; no launchd or network access."""
import importlib.util
import io
from contextlib import redirect_stdout
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("collector_service", Path(__file__).with_name("collector_service.py"))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServerOriginTests(unittest.TestCase):
    def test_canonical_https_roots(self):
        for value, expected in [
            ("https://monitor.example.com", "https://monitor.example.com"),
            ("HTTPS://Monitor.Example.com:443/", "https://monitor.example.com"),
            ("https://monitor.example.com:8443/", "https://monitor.example.com:8443"),
            ("https://127.0.0.1:8443", "https://127.0.0.1:8443"),
            ("https://[2001:DB8::1]:443/", "https://[2001:db8::1]"),
        ]:
            with self.subTest(value=value):
                self.assertEqual(service.normalize_server_origin(value), expected)

    def test_unsafe_or_missing_origins_fail_closed(self):
        for value in [None, "", " https://monitor.example.com", "https://monitor.example.com\n", "http://monitor.example.com",
                      "https://user:secret@monitor.example.com", "https://monitor.example.com/path", "https://monitor.example.com//",
                      "https://monitor.example.com?", "https://monitor.example.com#", "https://monitor.example.com:0",
                      "https://monitor.example.com:65536", "https://monitor.example.com:", "https://monitor.example.com.",
                      "https://monitor..example.com", "https://-monitor.example.com", "https://monitor_example.com",
                      "https://monitor.example.com\\evil", "https://monitor%2eexample.com", "https://服务器.example.com",
                      "https://[:::]", "https://[::1]suffix", "https://[fe80::1%25en0]", "https://monitor.example.com:999999999999999999999", "https://" + "a" * 64 + ".example.com"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                service.normalize_server_origin(value)

    def test_existing_pairing_only_matches_its_configured_server(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(service, "owned_path") as owner_check:
            locations = {"state": Path(directory)}
            config = Path(directory) / "config.json"
            expected = "https://monitor.example.com"
            self.assertFalse(service.paired_server_matches(locations, expected))
            for data in [{}, [], {"server": "http://monitor.example.com"}, {"server": "https://other.example.com"},
                         {"server": "https://monitor.example.com:8443"}]:
                config.write_text(json.dumps(data), encoding="utf-8")
                self.assertFalse(service.paired_server_matches(locations, expected))
                with self.assertRaises(ValueError):
                    service.require_paired_server(locations, expected)
            config.write_text(json.dumps({"server": "HTTPS://MONITOR.example.com:443/", "token": "synthetic-only"}), encoding="utf-8")
            self.assertTrue(service.paired_server_matches(locations, expected))
            service.require_paired_server(locations, expected)
            owner_check.assert_called_with(config)
            config.write_text("x" * (65536 + 1), encoding="utf-8")
            self.assertFalse(service.paired_server_matches(locations, expected))
            config.write_text("{", encoding="utf-8")
            self.assertFalse(service.paired_server_matches(locations, expected))

    def test_status_reports_only_pairing_for_the_requested_origin(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(service, "owned_path"), \
                patch.object(service.os, "getuid", return_value=1000, create=True), \
                patch.object(service, "service_result", return_value=subprocess.CompletedProcess([], 1)):
            folder = Path(directory)
            locations = {"state": folder, "owner": folder / "absent-owner", "mode": folder / "mode.json"}
            (folder / "config.json").write_text(json.dumps({"server": "https://one.example.com", "token": "synthetic-secret"}), encoding="utf-8")
            for server, expected in [("https://one.example.com", True), ("https://two.example.com", False)]:
                output = io.StringIO()
                with redirect_stdout(output):
                    service.status(locations, server)
                value = json.loads(output.getvalue())
                self.assertEqual(value["paired"], expected)
                self.assertEqual(value["server_scope_version"], 1)
                self.assertNotIn("synthetic-secret", output.getvalue())


if __name__ == "__main__":
    unittest.main()
