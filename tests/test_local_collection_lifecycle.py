"""Local collection must not block HTTP scheduling or outlive its Store."""
import asyncio
from datetime import datetime, timezone
import threading

import httpx
import pytest

from agent_monitor import collector, server


def snapshot(status="running"):
    return {"tasks": [{"id": "synthetic-task", "tool": "codex", "title": "Synthetic task",
                       "status": status, "updated_at": datetime.now(timezone.utc).isoformat()}],
            "sources": []}


def app_for_collection(tmp_path, monkeypatch):
    app = server.create_app(tmp_path, collect_local=True)

    async def idle_reply_worker():
        # Isolate the collector's lifetime from the independently tested reply worker.
        await asyncio.Event().wait()

    monkeypatch.setattr(app.state.task_replies, "run", idle_reply_worker)
    return app


@pytest.mark.parametrize("blocked_step", ["ensure_local", "preferences", "ingest"])
def test_collection_store_lock_does_not_block_http_event_loop(tmp_path, monkeypatch, blocked_step):
    app = app_for_collection(tmp_path, monkeypatch)
    store = app.state.store

    async def exercise():
        loop = asyncio.get_running_loop()
        event_thread = threading.get_ident()
        entered, ingested = asyncio.Event(), asyncio.Event()
        release, watchdog = threading.Event(), threading.Event()
        seen = []

        def wrap(name, operation):
            def invoke(*args, **kwargs):
                seen.append((name, threading.get_ident()))
                if name == blocked_step:
                    with store.lock:
                        loop.call_soon_threadsafe(entered.set)
                        if not release.wait(3):
                            watchdog.set()
                        result = operation(*args, **kwargs)
                else:
                    result = operation(*args, **kwargs)
                if name == "ingest":
                    loop.call_soon_threadsafe(ingested.set)
                return result
            return invoke

        for name in ("ensure_local", "preferences", "ingest"):
            monkeypatch.setattr(store, name, wrap(name, getattr(store, name)))
        monkeypatch.setattr(collector, "collect_snapshot", wrap("scan", lambda **kwargs: snapshot()))
        monkeypatch.setattr(server.Heartbeat, "model_validate", wrap("validate", server.Heartbeat.model_validate))

        async with app.router.lifespan_context(app):
            try:
                await asyncio.wait_for(entered.wait(), 1)
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                            base_url="http://testserver") as client:
                    result = await asyncio.wait_for(client.get("/api/health"), 1)
                assert result.status_code == 200 and result.json()["ok"] is True
                # The response must arrive while the collector still holds its
                # lock, not merely after the watchdog releases a blocked loop.
                assert not watchdog.is_set()
                assert not release.is_set()
            finally:
                release.set()
            await asyncio.wait_for(ingested.wait(), 1)
            assert store.snapshot()["tasks"][0]["status"] == "running"
        assert {name for name, _ in seen} >= {"ensure_local", "preferences", "scan", "validate", "ingest"}
        assert all(thread != event_thread for _, thread in seen)

    asyncio.run(exercise())


@pytest.mark.parametrize("failure", ["scan", "validation"])
def test_collection_failure_preserves_last_snapshot_and_next_round_recovers(tmp_path, monkeypatch, caplog, failure):
    app = app_for_collection(tmp_path, monkeypatch)
    store = app.state.store
    local_id = store.ensure_local("Synthetic computer", "Test")
    store.ingest(local_id, snapshot("completed")["tasks"], [])

    async def exercise():
        loop = asyncio.get_running_loop()
        failed, recovered = asyncio.Event(), asyncio.Event()
        calls = 0
        warning = server.logger.warning
        ingest = store.ingest

        def collect(**kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                if failure == "scan":
                    raise OSError("SYNTHETIC_PRIVATE_PATH_MUST_NOT_BE_LOGGED")
                return {"tasks": [{"id": "invalid"}], "sources": []}
            return snapshot()

        def log(*args):
            warning(*args)
            loop.call_soon_threadsafe(failed.set)

        def ingest_recovered(*args):
            ingest(*args)
            loop.call_soon_threadsafe(recovered.set)

        monkeypatch.setattr(collector, "collect_snapshot", collect)
        monkeypatch.setattr(server.logger, "warning", log)
        monkeypatch.setattr(store, "ingest", ingest_recovered)
        async with app.router.lifespan_context(app):
            await asyncio.wait_for(failed.wait(), 1)
            assert store.snapshot()["tasks"][0]["status"] == "completed"
            await asyncio.wait_for(recovered.wait(), 7)
            assert store.snapshot()["tasks"][0]["status"] == "running"
            assert calls == 2
        assert "SYNTHETIC_PRIVATE_PATH" not in caplog.text
        expected_error = "OSError" if failure == "scan" else "ValidationError"
        assert expected_error in caplog.text

    asyncio.run(exercise())


def test_output_sync_revocation_during_collection_is_rechecked_at_ingest(tmp_path, monkeypatch):
    app = app_for_collection(tmp_path, monkeypatch)
    store = app.state.store
    store.update_preferences({"sync_output": True})

    async def exercise():
        loop = asyncio.get_running_loop()
        scanning, ingested = asyncio.Event(), asyncio.Event()
        release, watchdog = threading.Event(), threading.Event()
        ingest = store.ingest

        def collect(*, include_output):
            assert include_output is True
            loop.call_soon_threadsafe(scanning.set)
            if not release.wait(3):
                watchdog.set()
            value = snapshot()
            value["tasks"][0].update(output="Synthetic final answer", final_result_id="a" * 64,
                                     final_result_at=datetime.now(timezone.utc).isoformat())
            return value

        def ingest_checked(*args):
            ingest(*args)
            loop.call_soon_threadsafe(ingested.set)

        monkeypatch.setattr(collector, "collect_snapshot", collect)
        monkeypatch.setattr(store, "ingest", ingest_checked)
        async with app.router.lifespan_context(app):
            try:
                await asyncio.wait_for(scanning.wait(), 1)
                store.update_preferences({"sync_output": False})
            finally:
                release.set()
            await asyncio.wait_for(ingested.wait(), 1)
            task = store.snapshot()["tasks"][0]
            assert task["output"] == task["final_result_id"] == task["final_result_at"] == ""
            assert task["status"] == "running" and not watchdog.is_set()

    asyncio.run(exercise())


@pytest.mark.parametrize("fail_after_cancel", [False, True])
def test_repeated_cancel_drains_worker_before_closing_store(tmp_path, monkeypatch, caplog, fail_after_cancel):
    app = app_for_collection(tmp_path, monkeypatch)
    store = app.state.store

    async def exercise():
        loop = asyncio.get_running_loop()
        entered = asyncio.Event()
        release, finished, closed, watchdog = (threading.Event() for _ in range(4))
        calls = []
        close, ingest = store.close, store.ingest

        def delayed_ingest(*args):
            calls.append("ingest")
            loop.call_soon_threadsafe(entered.set)
            try:
                if not release.wait(3):
                    watchdog.set()
                # Exercise actual SQLite access after shutdown was requested.
                assert not closed.is_set()
                ingest(*args)
                if fail_after_cancel:
                    raise OSError("SYNTHETIC_PRIVATE_FAILURE_MUST_NOT_BE_LOGGED")
            finally:
                finished.set()

        def close_store():
            assert finished.is_set()
            close()
            closed.set()

        monkeypatch.setattr(collector, "collect_snapshot", lambda **kwargs: snapshot())
        monkeypatch.setattr(store, "ingest", delayed_ingest)
        monkeypatch.setattr(store, "close", close_store)
        lifecycle = app.router.lifespan_context(app)
        await lifecycle.__aenter__()
        closing = None
        try:
            await asyncio.wait_for(entered.wait(), 1)
            collection = next(task for task in asyncio.all_tasks()
                              if task.get_coro().__qualname__.endswith(".<locals>.collect_loop"))
            collection.cancel()
            await asyncio.sleep(0)
            collection.cancel()
            await asyncio.sleep(0)
            assert not collection.done()
            closing = asyncio.create_task(lifecycle.__aexit__(None, None, None))
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            assert not closing.done() and not closed.is_set()
        finally:
            release.set()
            if closing is None:
                closing = asyncio.create_task(lifecycle.__aexit__(None, None, None))
            await asyncio.wait_for(closing, 2)
        assert finished.is_set() and closed.is_set() and not watchdog.is_set()
        assert calls == ["ingest"]
        assert "SYNTHETIC_PRIVATE_FAILURE" not in caplog.text
        if fail_after_cancel:
            assert "Local collection unavailable (OSError)" in caplog.text

    asyncio.run(exercise())
