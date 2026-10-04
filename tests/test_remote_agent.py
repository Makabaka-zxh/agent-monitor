from contextlib import contextmanager, redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from agent_monitor import remote_agent as agent


@contextmanager
def fake_hub(responder):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            requests.append((self.path, dict(self.headers), payload))
            status, headers, response = responder(self.path, payload)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class RemoteAgentTests(unittest.TestCase):
    def test_platform_uses_macos_without_changing_other_platform_names(self):
        for system, expected in (("Darwin", "macOS"), ("Windows", "Windows"), ("Linux", "Linux")):
            with self.subTest(system=system), patch("platform.system", return_value=system):
                self.assertEqual(agent.platform_name(), expected)
        with tempfile.TemporaryDirectory() as temporary, patch("platform.system", return_value="Darwin"):
            with patch.object(agent.HubClient, "post", return_value={"device_id": "mac", "token": "secret"}) as post:
                agent.pair("https://monitor.example.com", "12345678", "Mac mini", Path(temporary))
            self.assertEqual(post.call_args.args[1]["platform"], "macOS")

    def test_status_only_cannot_be_enabled_by_saved_or_server_output_preference(self):
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("https://monitor.example.com", "mac", "secret", sync_output=True), state_dir)
            before = (state_dir / agent.CONFIG_FILENAME).read_bytes()
            snapshot = {"tasks": [{"id": "codex:example", "tool": "codex", "title": "测试任务",
                                   "status": "completed", "updated_at": "2026-09-18T00:00:00Z",
                                   "preview": "private-preview", "output": "private-output",
                                   "final_result_id": "a" * 64, "final_result_at": "private-time",
                                   "future_result_field": {"private": "private-extra"}}], "sources": []}
            waits = []

            def finish_after_two(_delay):
                waits.append(_delay)
                if len(waits) == 2:
                    raise KeyboardInterrupt

            with patch.object(agent, "_collect_snapshot", return_value=snapshot) as collect, \
                    patch.object(agent.HubClient, "post", return_value={"sync_output": True}) as post, \
                    patch.object(agent, "ResultSync") as results, \
                    patch("agent_monitor.usage_remote.RemoteUsageWorker") as usage, \
                    patch("agent_monitor.remote_replies.RemoteReplyWorker") as replies:
                with self.assertRaises(KeyboardInterrupt):
                    agent.run(state_dir, status_only=True, allow_replies=True, sleep=finish_after_two)
            self.assertEqual([call.kwargs for call in collect.call_args_list], [{"include_output": False}] * 2)
            results.assert_not_called()
            replies.assert_not_called()
            usage.assert_called_once()
            self.assertEqual(usage.return_value.start.call_count, 2)
            usage.return_value.close.assert_called_once_with()
            self.assertEqual(post.call_count, 2)
            for call in post.call_args_list:
                self.assertEqual(call.args[0], "/api/agent/heartbeat")
                uploaded = call.args[1]["tasks"][0]
                self.assertEqual(set(uploaded), {"id", "tool", "title", "status", "updated_at"})
                self.assertNotIn("private-", json.dumps(call.args[1]))
            self.assertEqual((state_dir / agent.CONFIG_FILENAME).read_bytes(), before)
            self.assertEqual(snapshot["tasks"][0]["output"], "private-output")

    def test_results_and_usage_sync_with_replies_disabled_or_default_enabled(self):
        content = b"generated attachment for result sync\n"
        source_id, result_id, file_id = "codex:example", "a" * 64, "b" * 64
        file = {"id": file_id, "name": "report.txt", "size": len(content), "mime": "text/plain",
                "sha256": hashlib.sha256(content).hexdigest()}
        final = {"result_id": result_id, "text": "最终结果", "completed_at": "2026-09-28T00:00:00Z",
                 "truncated": False, "files": [file]}
        snapshot = {"tasks": [{"id": source_id, "tool": "codex", "title": "测试任务",
                               "status": "completed", "final_result_id": result_id}], "sources": []}

        def respond(endpoint, payload, token=None):
            self.assertEqual(token, "secret")
            if endpoint == "/api/agent/heartbeat":
                return {"sync_output": True}
            if endpoint == "/api/agent/results/status":
                return {"needed_results": [source_id]}
            if endpoint == "/api/agent/results":
                return {"needed_files": [{"id": file_id, "offset": 0}]}
            if endpoint == "/api/agent/results/file":
                return {"offset": len(content), "ready": True}
            self.fail(f"Unexpected endpoint: {endpoint}")

        # Omitted allow_replies exercises the unchanged full-mode default.
        for options, replies_enabled in (({"allow_replies": False}, False), ({}, True)):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temporary:
                state_dir = Path(temporary)
                agent.save_config(agent.AgentConfig("https://monitor.example.com", "mac", "secret",
                                                     sync_output=True), state_dir)
                with patch.object(agent, "_collect_snapshot", return_value=snapshot) as collect, \
                        patch.object(agent.HubClient, "post", side_effect=respond) as post, \
                        patch("agent_monitor.task_results.LocalResults") as local, \
                        patch("agent_monitor.usage_remote.RemoteUsageWorker") as usage, \
                        patch("agent_monitor.remote_replies.RemoteReplyWorker") as replies:
                    local.return_value.read.return_value = final
                    local.return_value.file.return_value = (content, file)
                    with self.assertRaises(KeyboardInterrupt):
                        agent.run(state_dir, sleep=Mock(side_effect=KeyboardInterrupt), **options)

                collect.assert_called_once_with(include_output=True)
                self.assertEqual([call.args[0] for call in post.call_args_list], [
                    "/api/agent/heartbeat", "/api/agent/results/status", "/api/agent/results",
                    "/api/agent/results/file",
                ])
                uploaded = post.call_args_list[2].args[1]
                self.assertEqual(uploaded["source_id"], source_id)
                self.assertEqual(uploaded["result_id"], result_id)
                self.assertEqual(uploaded["text"], final["text"])
                self.assertEqual(uploaded["files"], [file])
                chunk = post.call_args_list[3].args[1]
                self.assertEqual(chunk["file_id"], file_id)
                self.assertEqual(chunk["offset"], 0)
                self.assertEqual(base64.b64decode(chunk["data"]), content)
                local.return_value.read.assert_called_once_with(source_id)
                local.return_value.file.assert_called_once_with(source_id, result_id, file_id)
                usage.assert_called_once()
                usage.return_value.start.assert_called_once_with()
                usage.return_value.close.assert_called_once_with()
                if replies_enabled:
                    replies.assert_called_once()
                    replies.return_value.start.assert_called_once_with()
                    replies.return_value.close.assert_called_once_with()
                else:
                    replies.assert_not_called()

    def test_status_only_cli_forwards_local_option(self):
        with patch.object(agent, "run") as run:
            self.assertEqual(agent.main(["run", "--once", "--status-only", "--state-dir", "test-state"]), 0)
        run.assert_called_once_with(Path("test-state"), once=True, status_only=True)

    def test_sigterm_stops_workers_and_restores_previous_handler(self):
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("https://monitor.example.com", "mac", "secret"), state_dir)
            previous = object()
            current = [previous]
            registrations = []

            def register(signum, handler):
                self.assertEqual(signum, signal.SIGTERM)
                registrations.append(handler)
                current[0] = handler

            def terminate(_delay):
                current[0](signal.SIGTERM, None)

            worker = Mock()
            # A second SIGTERM during cancellation must not abort cleanup.
            worker.close.side_effect = lambda: current[0](signal.SIGTERM, None)
            with patch.object(agent.signal, "getsignal", return_value=previous), \
                    patch.object(agent.signal, "signal", side_effect=register), \
                    patch.object(agent, "_collect_snapshot", return_value={"tasks": [], "sources": []}), \
                    patch.object(agent.HubClient, "post", return_value={"sync_output": False}), \
                    patch("agent_monitor.usage_remote.RemoteUsageWorker"), \
                    patch("agent_monitor.remote_replies.RemoteReplyWorker", return_value=worker):
                agent.run(state_dir, sleep=terminate)
            worker.start.assert_called_once_with()
            worker.close.assert_called_once_with()
            self.assertIs(current[0], previous)
            self.assertEqual(len(registrations), 2)

    def test_sigterm_handler_restored_on_failure_and_untouched_off_main_thread(self):
        previous = signal.getsignal(signal.SIGTERM)
        with patch.object(agent, "_run", side_effect=agent.RemoteAgentError("test failure")):
            with self.assertRaises(agent.RemoteAgentError):
                agent.run(Path("unused"))
        self.assertIs(signal.getsignal(signal.SIGTERM), previous)
        with patch.object(agent.threading, "current_thread", return_value=object()), \
                patch.object(agent.signal, "signal") as set_signal, patch.object(agent, "_run") as run:
            agent.run(Path("unused"), status_only=True)
        set_signal.assert_not_called()
        run.assert_called_once()

    def test_url_security(self):
        for url in ["https://example.com", "http://localhost:8000/", "http://127.0.0.1:8000", "http://[::1]:8000"]:
            with self.subTest(url=url):
                self.assertTrue(agent.normalize_server(url))
        for url in ["http://192.168.1.2", "http://10.2.3.4", "http://[fd00::1]"]:
            with self.subTest(url=url):
                with self.assertRaises(agent.RemoteAgentError):
                    agent.normalize_server(url)
                self.assertEqual(agent.normalize_server(url, True), url)
        for url in [
            "http://8.8.8.8", "http://example.com", "http://office.local", "http://0.0.0.0",
            "http://169.254.169.254", "http://[::]", "http://[2001:db8::1]", "ftp://localhost",
            "https://user:secret@example.com", "https://example.com/a", "https://example.com?q=1",
            "https://example.com#fragment", "https://example.com:0", "https://example.com:99999",
            "https://example.com\n", "https://", "", None,
        ]:
            with self.subTest(url=url):
                with self.assertRaises(agent.RemoteAgentError):
                    agent.normalize_server(url, True)

    def test_pair_and_once_heartbeat_do_not_print_token(self):
        secret = "test-monitor-connector-token"

        def responder(path, payload):
            if path == "/api/agent/register":
                return 200, {}, {"device_id": "computer-1", "token": secret, "sync_output": False}
            return 200, {}, {"sync_output": True}

        with tempfile.TemporaryDirectory() as temporary, fake_hub(responder) as (url, requests):
            state_dir = Path(temporary) / "agent"
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(agent.main(["pair", "--server", url, "--code", "123456", "--name", "家里电脑", "--state-dir", str(state_dir)]), 0)
                with patch.object(agent, "_collect_snapshot", return_value={"tasks": [{"id": "task-1", "status": "running"}], "sources": {"codex": "available"}}) as collect:
                    self.assertEqual(agent.main(["run", "--once", "--state-dir", str(state_dir)]), 0)
                    collect.assert_called_once_with(include_output=False)
            self.assertNotIn(secret, stdout.getvalue() + stderr.getvalue())
            config = agent.load_config(state_dir)
            self.assertNotIn(secret, repr(config))
            self.assertTrue(config.sync_output)
            self.assertEqual(requests[0][2]["name"], "家里电脑")
            self.assertEqual(requests[0][2]["code"], "123456")
            self.assertNotIn("Authorization", requests[0][1])
            self.assertEqual(requests[1][1]["Authorization"], f"Bearer {secret}")
            self.assertEqual(set(requests[1][2]), {"tasks", "sources"})
            if os.name != "nt":
                self.assertEqual((state_dir / agent.CONFIG_FILENAME).stat().st_mode & 0o777, 0o600)

    def test_redirect_is_never_followed(self):
        for code in [301, 302, 303, 307, 308]:
            with self.subTest(code=code):
                with fake_hub(lambda path, payload: (code, {"Location": "/redirect-target"}, {})) as (url, requests):
                    with self.assertRaises(agent.RemoteAgentError):
                        agent.HubClient(url).post("/api/agent/heartbeat", {"tasks": []}, token="secret")
                    self.assertEqual(len(requests), 1)

    def test_revoked_token_exits_and_does_not_retry(self):
        with tempfile.TemporaryDirectory() as temporary, fake_hub(lambda path, payload: (401, {}, {"error": "revoked"})) as (url, requests):
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig(url, "computer-1", "secret"), state_dir)
            stderr = io.StringIO()
            with redirect_stderr(stderr), patch.object(agent, "_collect_snapshot", return_value={"tasks": [], "sources": {}}):
                self.assertEqual(agent.main(["run", "--state-dir", str(state_dir)]), 2)
            self.assertIn("重新运行 pair", stderr.getvalue())
            self.assertEqual(len(requests), 1)

    def test_server_output_preference_applies_to_next_collection(self):
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("http://localhost", "computer-1", "secret"), state_dir)
            seen = []

            def collect(*, include_output):
                seen.append(include_output)
                return {"tasks": [], "sources": {}}

            with patch.object(agent.HubClient, "post", side_effect=[{"sync_output": True}, {"sync_output": False}]):
                with self.assertRaises(KeyboardInterrupt):
                    agent.run(state_dir, collect=collect, sleep=lambda _: None if len(seen) < 2 else (_ for _ in ()).throw(KeyboardInterrupt()))
            self.assertEqual(seen, [False, True])
            self.assertFalse(agent.load_config(state_dir).sync_output)

    def test_retry_delay_caps_at_thirty_seconds(self):
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("http://localhost", "computer-1", "secret"), state_dir)
            delays = []

            def sleep(delay):
                delays.append(delay)
                if len(delays) == 5:
                    raise KeyboardInterrupt

            with patch.object(agent.HubClient, "post", side_effect=agent.RemoteAgentError("网络错误")), redirect_stderr(io.StringIO()):
                with self.assertRaises(KeyboardInterrupt):
                    agent.run(state_dir, collect=lambda **_: {"tasks": [], "sources": {}}, sleep=sleep)
            self.assertEqual(delays, [5, 10, 20, 30, 30])

    def test_insecure_opt_in_is_persisted_and_invalid_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("http://192.168.1.2", "device", "secret", allow_insecure_lan=True), state_dir)
            self.assertTrue(agent.load_config(state_dir).allow_insecure_lan)
            saved = json.loads((state_dir / agent.CONFIG_FILENAME).read_text())
            saved["server"] = "http://8.8.8.8"
            (state_dir / agent.CONFIG_FILENAME).write_text(json.dumps(saved))
            with self.assertRaises(agent.RemoteAgentError):
                agent.load_config(state_dir)

    def test_qr_waits_for_phone_approval_and_keeps_secret_out_of_files(self):
        from PIL import Image

        request_id = "A" * 32
        poll_secret = "retrieval-secret-" + "B" * 32
        agent_token = "approved-monitor-device-token"
        verification_url = f"https://monitor.example.com/#/pairing-confirm/{request_id}"
        responses = [
            {"request_id": request_id, "poll_secret": poll_secret, "verification_url": verification_url, "interval": 3},
            {"status": "pending", "interval": 3},
            {"status": "approved", "device_id": "computer-qr", "token": agent_token, "sync_output": False},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            sleeps = []

            def wait(delay):
                sleeps.append(delay)
                self.assertFalse((state_dir / agent.CONFIG_FILENAME).exists())
                png = state_dir / f"pairing-{request_id}.png"
                with Image.open(png) as image:
                    self.assertEqual(image.format, "PNG")
                    self.assertEqual(image.width, image.height)
                    self.assertGreater(image.width, 100)
                for file in state_dir.iterdir():
                    self.assertNotIn(poll_secret.encode(), file.read_bytes())
                    self.assertNotIn(agent_token.encode(), file.read_bytes())

            output = io.StringIO()
            with patch.object(agent.HubClient, "post", side_effect=responses) as post, patch.object(agent.time, "sleep", side_effect=wait), redirect_stdout(output):
                result = agent.main(["pair-qr", "--server", "https://monitor.example.com", "--name", "办公室电脑", "--state-dir", str(state_dir)])
            self.assertEqual(result, 0)
            config = agent.load_config(state_dir)
            self.assertEqual(sleeps, [3, 3])
            self.assertEqual(config.device_id, "computer-qr")
            self.assertEqual(agent.load_config(state_dir).token, agent_token)
            self.assertEqual(post.call_args_list[0].args[1]["name"], "办公室电脑")
            for call in post.call_args_list[1:]:
                self.assertEqual(call.args, (f"/api/agent/pairing/{request_id}/poll", {"poll_secret": poll_secret}))
                self.assertFalse(call.kwargs)
            self.assertIn(verification_url, output.getvalue())
            self.assertNotIn(poll_secret, output.getvalue())
            self.assertNotIn(agent_token, output.getvalue())
            self.assertNotIn(poll_secret, (state_dir / agent.CONFIG_FILENAME).read_text(encoding="utf-8"))

    def test_qr_errors_are_terminal_and_do_not_echo_untrusted_body(self):
        for status, text in [(401, "配对验证失败"), (403, "未获允许"), (410, "已过期")]:
            with self.subTest(status=status), fake_hub(lambda path, payload: (status, {}, {"detail": "do-not-print-secret"})) as (url, requests):
                with self.assertRaisesRegex(agent.PairingRequestError, text) as error:
                    agent.HubClient(url).post(f"/api/agent/pairing/{'A' * 32}/poll", {"poll_secret": "S" * 43})
                self.assertNotIn("do-not-print-secret", str(error.exception))
                self.assertEqual(len(requests), 1)
                self.assertNotIn("Authorization", requests[0][1])

    def test_qr_rejects_http_cross_origin_and_secret_bearing_urls_before_writing(self):
        request_id, poll_secret = "A" * 32, "B" * 43
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            with patch.object(agent.HubClient, "post") as post:
                for server in ["http://localhost", "http://192.168.1.2", "http://monitor.example.com"]:
                    with self.subTest(server=server), self.assertRaises(agent.RemoteAgentError):
                        agent.pair_qr(server, "电脑", state_dir)
                post.assert_not_called()
            for url in [
                f"https://other.example.com/#/pairing-confirm/{request_id}",
                f"https://monitor.example.com/#/pairing-confirm/{request_id}?secret={poll_secret}",
                f"https://monitor.example.com/?poll_secret={poll_secret}#/pairing-confirm/{request_id}",
            ]:
                with self.subTest(url=url), patch.object(agent.HubClient, "post", return_value={
                    "request_id": request_id, "poll_secret": poll_secret, "verification_url": url, "interval": 3,
                }):
                    with self.assertRaises(agent.RemoteAgentError):
                        agent.pair_qr("https://monitor.example.com", "电脑", state_dir)
                    self.assertEqual(list(state_dir.iterdir()), [])

    def test_qr_denial_timeout_and_interrupt_preserve_existing_configuration(self):
        request_id = "A" * 32
        start = {"request_id": request_id, "poll_secret": "B" * 43,
                 "verification_url": f"https://monitor.example.com/#/pairing-confirm/{request_id}", "interval": 3}
        with tempfile.TemporaryDirectory() as temporary:
            state_dir = Path(temporary)
            agent.save_config(agent.AgentConfig("https://old.example.com", "old-device", "old-token"), state_dir)
            before = (state_dir / agent.CONFIG_FILENAME).read_bytes()
            with patch.object(agent.HubClient, "post", side_effect=[start, agent.PairingRequestError("手机已拒绝")]) as post, redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(agent.PairingRequestError, "拒绝"):
                    agent.pair_qr("https://monitor.example.com", "电脑", state_dir, sleep=lambda _: None)
                self.assertEqual(post.call_count, 2)
            elapsed = [0]

            def advance(delay):
                elapsed[0] += delay

            with patch.object(agent.HubClient, "post", side_effect=lambda endpoint, payload: start if endpoint.endswith("start") else {"status": "pending"}) as post, redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(agent.PairingRequestError, "过期"):
                    agent.pair_qr("https://monitor.example.com", "电脑", state_dir, sleep=advance, monotonic=lambda: elapsed[0])
                self.assertEqual(elapsed[0], 600)
                self.assertEqual(post.call_count, 200)
            with patch.object(agent.HubClient, "post", return_value=start), redirect_stdout(io.StringIO()):
                with self.assertRaises(KeyboardInterrupt):
                    agent.pair_qr("https://monitor.example.com", "电脑", state_dir,
                                  sleep=lambda _: (_ for _ in ()).throw(KeyboardInterrupt()))
            self.assertEqual((state_dir / agent.CONFIG_FILENAME).read_bytes(), before)

    def test_qr_poll_transport_and_redirects_keep_secret_off_url(self):
        request_id, secret = "A" * 32, "B" * 43
        with fake_hub(lambda path, payload: (200, {}, {"status": "pending", "interval": 3})) as (url, requests):
            result = agent.HubClient(url).post(f"/api/agent/pairing/{request_id}/poll", {"poll_secret": secret})
            self.assertEqual(result["status"], "pending")
            self.assertEqual(requests[0][2], {"poll_secret": secret})
            self.assertNotIn(secret, requests[0][0])
        with fake_hub(lambda path, payload: (307, {"Location": "https://other.example.com/"}, {})) as (url, requests):
            with self.assertRaises(agent.RemoteAgentError):
                agent.HubClient(url).post(f"/api/agent/pairing/{request_id}/poll", {"poll_secret": secret})
            self.assertEqual(len(requests), 1)


if __name__ == "__main__":
    unittest.main()
