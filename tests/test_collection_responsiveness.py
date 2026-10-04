"""Exercise the local collector's scheduling with synthetic data only."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import threading

import httpx

from agent_monitor import collector, server


def collection_app(tmp_path, monkeypatch):
    app = server.create_app(tmp_path, collect_local=True)

    async def idle_replies():
        await asyncio.Event().wait()

    monkeypatch.setattr(app.state.task_replies, "run", idle_replies)
    return app


def synthetic_snapshot():
    return {"tasks": [{"id": "synthetic-task", "tool": "codex", "title": "Synthetic task",
                       "status": "running", "updated_at": datetime.now(timezone.utc).isoformat()}],
            "sources": []}


def test_slow_scan_keeps_database_http_reads_responsive_without_overlapping_rounds(tmp_path, monkeypatch):
    app = collection_app(tmp_path, monkeypatch)

    async def exercise():
        loop = asyncio.get_running_loop()
        entered = asyncio.Event()
        release, watchdog = threading.Event(), threading.Event()
        calls = 0

        def slow_scan(**kwargs):
            nonlocal calls
            calls += 1
            loop.call_soon_threadsafe(entered.set)
            if not release.wait(12):
                watchdog.set()
            return synthetic_snapshot()

        monkeypatch.setattr(collector, "collect_snapshot", slow_scan)
        async with app.router.lifespan_context(app):
            try:
                await asyncio.wait_for(entered.wait(), 1)
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                            base_url="http://testserver") as client:
                    # Unlike /api/health, bootstrap needs the same SQLite Store.
                    response = await asyncio.wait_for(client.get("/api/bootstrap"), 1)
                assert response.status_code == 200
                assert response.json()["needs_setup"] is True
                assert not watchdog.is_set() and not release.is_set()
                # A slow filesystem cannot launch a second collector every five
                # seconds; the next round must await completion of this one.
                await asyncio.sleep(5.1)
                assert calls == 1
            finally:
                release.set()
        assert not watchdog.is_set()

    asyncio.run(exercise())


def test_shutdown_drains_queued_executor_work_before_closing_sqlite(tmp_path, monkeypatch):
    app = collection_app(tmp_path, monkeypatch)
    store = app.state.store

    async def exercise():
        loop = asyncio.get_running_loop()
        executor = ThreadPoolExecutor(max_workers=1)
        loop.set_default_executor(executor)
        occupied = asyncio.Event()
        release, watchdog = threading.Event(), threading.Event()
        order = []
        close = store.close

        def occupy_executor():
            loop.call_soon_threadsafe(occupied.set)
            if not release.wait(5):
                watchdog.set()

        def scan(**kwargs):
            assert "closed" not in order
            order.append("scan")
            # Access the actual temporary database, not a stub Store.
            assert store.has_account() is False
            return synthetic_snapshot()

        def close_store():
            assert store.snapshot()["tasks"][0]["status"] == "running"
            close()
            order.append("closed")

        monkeypatch.setattr(collector, "collect_snapshot", scan)
        monkeypatch.setattr(store, "close", close_store)
        blocker = loop.run_in_executor(None, occupy_executor)
        lifecycle = app.router.lifespan_context(app)
        closing = None
        try:
            await asyncio.wait_for(occupied.wait(), 1)
            await lifecycle.__aenter__()
            # Let collect_loop enqueue its worker behind the occupied executor.
            await asyncio.sleep(0)
            closing = asyncio.create_task(lifecycle.__aexit__(None, None, None))
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            assert order == [] and not closing.done()
        finally:
            release.set()
            await asyncio.wait_for(blocker, 1)
            if closing is not None:
                await asyncio.wait_for(closing, 2)
        assert order == ["scan", "closed"] and not watchdog.is_set()

    asyncio.run(exercise())
