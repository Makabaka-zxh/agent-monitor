"""First usage refresh must not gate server readiness or invent a zero bill."""
import asyncio
import copy
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from unittest.mock import MagicMock

import httpx
import pytest

from agent_monitor import server, usage_service
from agent_monitor.usage_ledger import UsageLedger
from agent_monitor.usage_service import UsageService


def local_store():
    store = MagicMock()
    store.snapshot.return_value = {"devices": [{"id": "local-pc", "name": "Local", "local": True}], "tasks": []}
    return store


async def wait_until_ready(service):
    while service.summary()["loading"]:
        await asyncio.sleep(.005)


def test_constructor_never_aggregates_or_reads_tasks(tmp_path, monkeypatch):
    summary = MagicMock(side_effect=AssertionError("constructor aggregated usage"))
    monkeypatch.setattr(UsageLedger, "summary", summary)
    store = local_store()
    service = UsageService(tmp_path, store, False)
    try:
        pending = service.summary()
        assert pending["loading"] is True and pending["history_included"] is False
        assert pending["coverage"] == "unavailable" and pending["providers"] == []
        assert pending["scan"] == {} and "observed_at" not in pending
        assert "today_tokens" not in pending  # No made-up zero during loading.
        original = {"devices": [{"id": "view-device"}], "tasks": [{"id": "task-one"}],
                    "preferences": {"tool_filter": "codex"}}
        enriched = service.enrich(copy.deepcopy(original))
        assert {key: enriched[key] for key in original} == original
        assert enriched["usage"]["history_included"] is False
        enriched["usage"]["providers"].append({"tool": "made-up"})
        assert service.summary()["providers"] == []
        summary.assert_not_called()
        store.snapshot.assert_not_called()
    finally:
        service.close()


def test_server_starts_and_answers_health_while_first_summary_is_blocked(tmp_path, monkeypatch):
    entered, release = threading.Event(), threading.Event()
    original = UsageLedger.summary
    worker_threads = []

    def blocked(ledger, *args, **kwargs):
        worker_threads.append(threading.get_ident())
        entered.set()
        assert release.wait(5), "test did not release first summary"
        return original(ledger, *args, **kwargs)

    monkeypatch.setattr(UsageLedger, "summary", blocked)
    # Protect the test itself from regressing into a synchronous constructor.
    with ThreadPoolExecutor(max_workers=1) as pool:
        creating = pool.submit(server.create_app, tmp_path, collect_local=False)
        try:
            app = creating.result(timeout=2)
            assert not entered.is_set()
        except BaseException:
            release.set()
            raise

    async def exercise():
        main_thread = threading.get_ident()
        lifecycle = app.router.lifespan_context(app)
        try:
            await asyncio.wait_for(lifecycle.__aenter__(), 1)
            assert await asyncio.to_thread(entered.wait, 1)
            assert all(thread != main_thread for thread in worker_threads)
            assert app.state.usage.summary()["history_included"] is False
            assert app.state.usage.summary()["loading"] is True
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
                assert (await asyncio.wait_for(client.get("/api/health"), 1)).status_code == 200
            release.set()
            await asyncio.wait_for(wait_until_ready(app.state.usage), 1)
            actual = app.state.usage.summary()
            assert actual["history_included"] is True and actual["loading"] is False
            assert all(provider["today_tokens"] is None for provider in actual["providers"])
            assert len(actual["providers"][0]["history"]["days"]) == 90
            assert app.state.usage.enrich({"tasks": []})["usage"]["history_included"] is False
        finally:
            release.set()
            await asyncio.wait_for(lifecycle.__aexit__(None, None, None), 2)

    asyncio.run(exercise())


@pytest.mark.parametrize("initial_read_fails", [False, True])
def test_persisted_usage_loads_before_backfill_and_survives_failed_scan(tmp_path, monkeypatch, initial_read_fails):
    now = datetime.now(timezone.utc)
    persisted = UsageLedger(tmp_path / "usage.sqlite3")
    persisted.ingest_claude_quota({"observed_at": now.isoformat(), "rate_limits": {
        "five_hour": {"used_percentage": 37, "resets_at": int(now.timestamp()) + 3600}}}, now=now)
    persisted.close()
    service = UsageService(tmp_path, local_store(), True)
    entered, release = threading.Event(), threading.Event()
    initial_summary = service.ledger.summary
    calls = 0

    def maybe_failed_summary(*args, **kwargs):
        nonlocal calls
        calls += 1
        if initial_read_fails and calls == 1:
            raise RuntimeError("PRIVATE synthetic first-read error")
        return initial_summary(*args, **kwargs)

    def failed_scan(**kwargs):
        entered.set()
        assert release.wait(5)
        raise OSError("PRIVATE synthetic scan failure")

    monkeypatch.setattr(service.ledger, "summary", maybe_failed_summary)
    monkeypatch.setattr(service.ledger, "scan", failed_scan)
    no_cli = MagicMock()
    monkeypatch.setattr(usage_service, "read_claude_quota_file", no_cli)

    async def exercise():
        worker = asyncio.create_task(service.run())
        try:
            assert await asyncio.to_thread(entered.wait, 1)
            # Persisted data is published before the first scan can complete.
            assert service.summary()["loading"] is initial_read_fails
            release.set()
            await asyncio.wait_for(wait_until_ready(service), 1)
            actual = service.summary()
            assert actual["loading"] is False and actual["history_included"] is True
            quota = actual["providers"][1]["quotas"][0]
            assert quota["used_percent"] == 37 and quota["remaining_percent"] == 63
            assert quota["source_device_id"] == "local-pc"
            assert datetime.fromisoformat(quota["observed_at"].replace("Z", "+00:00")).timestamp() <= now.timestamp()
            no_cli.assert_not_called()  # The failed scan never reaches any home-file read.
        finally:
            release.set()
            worker.cancel()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(worker, 2)
    try:
        asyncio.run(exercise())
    finally:
        service.close()
