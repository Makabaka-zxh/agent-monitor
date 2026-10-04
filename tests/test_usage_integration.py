"""Synthetic worker checks; no real home scan, network or credentials."""
import asyncio
import copy
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from agent_monitor.usage_remote import RemoteUsageWorker
from agent_monitor.usage_service import UsageService


class OneCycleStop:
    def __init__(self):
        self.stopped = False

    def is_set(self):
        return self.stopped

    def wait(self, seconds):
        self.stopped = True

    def set(self):
        self.stopped = True


class UsageIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.snapshot = {"devices": [{"id": "pc-local", "name": "Local computer", "local": True},
                                     {"id": "pc-remote", "name": "Remote computer", "local": False}],
                         "tasks": [{"id": "pc-local:codex:session-one", "device_id": "pc-local", "tool": "codex"},
                                   {"id": "pc-remote:claude:session-two", "device_id": "pc-remote", "tool": "claude"}]}
        self.store = MagicMock()
        self.store.snapshot.side_effect = lambda: copy.deepcopy(self.snapshot)

    def fake_summary(self):
        return {"day": "2026-09-25", "coverage": "partial", "providers": [
            {"tool": "codex", "today_tokens": 12, "quotas": [{"source_device_id": "local", "used_percent": 15}]},
            {"tool": "claude", "today_tokens": 35, "quotas": [{"source_device_id": "pc-remote", "used_percent": 70}]}]}

    def service(self):
        ledger = MagicMock()
        ledger.summary.side_effect = lambda *args, **kwargs: self.fake_summary()
        ledger.task_usage.return_value = {"total_tokens": 12, "coverage": "partial"}
        with patch("agent_monitor.usage_service.UsageLedger", return_value=ledger):
            service = UsageService(self.home, self.store, True)
        return service, ledger

    def test_refresh_scopes_sources_and_maps_local_task_ids(self):
        service, ledger = self.service()
        service.refresh()
        self.assertIn("device_ids", ledger.summary.call_args.kwargs)
        self.assertEqual(set(ledger.summary.call_args.kwargs["device_ids"]), {"pc-local", "pc-remote", "local"})
        ledger.task_usage.assert_any_call("codex", "codex:session-one", device_id="local")
        ledger.task_usage.assert_any_call("claude", "claude:session-two", device_id="pc-remote")
        actual = service.summary()
        self.assertEqual(actual["providers"][0]["quotas"][0]["source_device_id"], "pc-local")
        self.assertEqual(actual["providers"][1]["quotas"][0]["source_name"], "Remote computer")

    def test_hot_summary_and_enrich_never_touch_ledger_or_alias_cache(self):
        service, ledger = self.service()
        service.refresh()
        ledger.reset_mock()
        summary = service.summary()
        summary["providers"][0]["today_tokens"] = 9000
        enriched = service.enrich(copy.deepcopy(self.snapshot))
        enriched["tasks"][0]["usage"]["total_tokens"] = 9000
        self.assertEqual(service.summary()["providers"][0]["today_tokens"], 12)
        self.assertEqual(service.enrich(copy.deepcopy(self.snapshot))["tasks"][0]["usage"]["total_tokens"], 12)
        self.assertEqual(ledger.mock_calls, [])

    def test_disabled_local_collection_still_refreshes_remote_cache(self):
        service, ledger = self.service()
        service.enabled = False
        service.collect()
        ledger.scan.assert_not_called()
        ledger.task_usage.assert_called()

    def test_collection_error_does_not_replace_cached_success(self):
        service, ledger = self.service()
        service.refresh()
        before = service.summary()
        ledger.scan.side_effect = RuntimeError("synthetic error, no private data")
        with self.assertLogs("agent_monitor", level="WARNING") as logged:
            service.collect()
        self.assertEqual(service.summary(), before)
        self.assertNotIn("private data", " ".join(logged.output))

    def test_service_cancellation_waits_for_own_worker(self):
        service, ledger = self.service()
        entered, release, finished = threading.Event(), threading.Event(), threading.Event()

        def slow_collect():
            entered.set()
            release.wait(2)
            finished.set()

        service.collect = slow_collect

        async def exercise():
            task = asyncio.create_task(service.run())
            await asyncio.to_thread(entered.wait, 1)
            task.cancel()
            await asyncio.sleep(0)
            self.assertFalse(task.done())
            release.set()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            self.assertTrue(finished.is_set())

        asyncio.run(exercise())

    def worker_once(self, result=None, error=None, quotas=None, responses=None):
        client = MagicMock()
        client.post.return_value = result
        if error:
            client.post.side_effect = error
        if responses is not None:
            client.post.side_effect = responses
        worker = RemoteUsageWorker(client, SimpleNamespace(token="synthetic-test-token"), self.home)
        worker.stop = OneCycleStop()
        ledger = MagicMock()
        ledger.export_quota_batch.return_value = {"version": 1, "events": [], "quotas": quotas or []}
        ledger.export_batch.return_value = {"version": 1, "events": [{"event_key": "opaque"}], "quotas": [], "cursor": 19}
        with patch("agent_monitor.usage_remote.UsageLedger", return_value=ledger), patch("agent_monitor.usage_remote.Path.home", return_value=self.home):
            worker.run()
        return worker, ledger, client

    def test_remote_ack_only_after_explicit_success(self):
        worker, ledger, client = self.worker_once({"accepted": 1, "rejected": 0})
        ledger.ack_export.assert_called_once_with(19)
        ledger.close.assert_called_once()
        client.post.assert_called_once()
        self.assertEqual(client.post.call_args.args[0], "/api/agent/usage")

    def test_remote_failure_and_partial_rejection_preserve_outbox(self):
        for response in ({"accepted": 0, "rejected": 1}, {"accepted": 0, "rejected": 0}, {}, {"accepted": True, "rejected": 0}):
            with self.subTest(response=response):
                worker, ledger, client = self.worker_once(response)
                ledger.ack_export.assert_not_called()
                ledger.close.assert_called_once()
        worker, ledger, client = self.worker_once(error=OSError("synthetic transport failure"))
        ledger.ack_export.assert_not_called()
        ledger.close.assert_called_once()

    def test_remote_quota_sent_before_backfill_without_acking_its_cursor(self):
        quotas = [{"tool": "codex", "used_percent": 75, "observed_at": "2026-09-25T14:30:00.000Z"}]
        worker, ledger, client = self.worker_once(quotas=quotas, responses=[
            {"accepted": 1, "rejected": 0}, {"accepted": 1, "rejected": 0}])
        self.assertEqual(client.post.call_count, 2)
        self.assertEqual(client.post.call_args_list[0].args[1]["quotas"], quotas)
        self.assertEqual(client.post.call_args_list[0].args[1]["events"], [])
        self.assertNotIn("cursor", client.post.call_args_list[0].args[1])
        self.assertEqual(client.post.call_args_list[1].args[1]["cursor"], 19)
        ledger.ack_export.assert_called_once_with(19)

    def test_failed_quota_fast_path_still_sends_and_acknowledges_token_batch(self):
        worker, ledger, client = self.worker_once(quotas=[{"tool": "codex"}], responses=[
            OSError("synthetic quota transport failure"), {"accepted": 1, "rejected": 0}])
        self.assertEqual(client.post.call_count, 2)
        ledger.ack_export.assert_called_once_with(19)
        ledger.close.assert_called_once()

    def test_successful_fast_quota_never_acks_failed_accounting_batch(self):
        worker, ledger, client = self.worker_once(quotas=[{"tool": "codex"}], responses=[
            {"accepted": 1, "rejected": 0}, OSError("synthetic accounting failure")])
        self.assertEqual(client.post.call_count, 2)
        ledger.ack_export.assert_not_called()


if __name__ == "__main__":
    unittest.main()
