"""Portable checks for private launchd configuration and native bundle metadata."""
import importlib.util
from pathlib import Path
import plistlib
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


service = module("collector_service")
app = module("build_app")


class PackagingTests(unittest.TestCase):
    def test_private_capture_keeps_proxy_casing_and_drops_unrelated_credentials(self):
        result = service.captured_environment(Path("/Users/example"), {
            "HTTPS_PROXY": "http://upper.invalid:1234", "https_proxy": "http://lower.invalid:4567",
            "CODEX_HOME": "/Users/example/custom codex", "SOME_SECRET": "must-not-copy", "PATH": "/tmp/untrusted"})
        self.assertEqual(result["HTTPS_PROXY"], "http://upper.invalid:1234")
        self.assertEqual(result["https_proxy"], "http://lower.invalid:4567")
        self.assertEqual(result["CODEX_HOME"], "/Users/example/custom codex")
        self.assertNotIn("SOME_SECRET", result)
        self.assertNotIn("/tmp/untrusted", result["PATH"])

    def test_upgrade_from_sparse_ssh_environment_preserves_previous_capture(self):
        previous = {"HTTPS_PROXY": "http://retained.invalid", "CLAUDE_CONFIG_DIR": "/Users/example/custom claude"}
        result = service.captured_environment(Path("/Users/example"), {}, previous)
        self.assertEqual(result["HTTPS_PROXY"], previous["HTTPS_PROXY"])
        self.assertEqual(result["CLAUDE_CONFIG_DIR"], previous["CLAUDE_CONFIG_DIR"])
        result = service.captured_environment(Path("/Users/example"), {"HTTPS_PROXY": ""}, previous)
        self.assertEqual(result["HTTPS_PROXY"], "")

    def test_launchd_has_explicit_state_and_never_contains_proxy_or_tokens(self):
        home = Path("/Users/example person")
        runtime = service.paths(home)["releases"] / ("a" * 32)
        config = service.launch_agent(home, runtime)
        parsed = plistlib.loads(plistlib.dumps(config))
        self.assertIn("-I", parsed["ProgramArguments"])
        index = parsed["ProgramArguments"].index("--state-dir")
        self.assertEqual(parsed["ProgramArguments"][index + 1], str(service.paths(home)["state"]))
        self.assertEqual(parsed["WorkingDirectory"], str(runtime))
        self.assertEqual(parsed["Umask"], 0o077)
        self.assertEqual(set(parsed["EnvironmentVariables"]), {"HOME", "PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE"})
        self.assertFalse(parsed["KeepAlive"]["SuccessfulExit"])

    def test_invalid_environment_is_rejected_before_persistence(self):
        with self.assertRaises(ValueError):
            service.captured_environment(Path("/Users/example"), {"HTTPS_PROXY": "abc\x00def"})

    def test_native_bundle_uses_registered_account_return_and_macos_floor(self):
        info = plistlib.loads(plistlib.dumps(app.app_info()))
        self.assertEqual(info["CFBundleExecutable"], "Monitor")
        self.assertEqual(info["CFBundleIdentifier"], "com.agentmonitor.mac")
        self.assertEqual(info["CFBundleShortVersionString"], "1.0.0")
        self.assertEqual(info["CFBundleVersion"], "6")
        self.assertEqual(info["LSMinimumSystemVersion"], "14.0")
        self.assertEqual(info["CFBundleURLTypes"][0]["CFBundleURLSchemes"], ["agentmonitor"])


if __name__ == "__main__":
    unittest.main()
