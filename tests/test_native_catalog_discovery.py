"""Offline tests of the native endpoint with synthetic tasks and provider."""

import secrets
import threading
import time

import pytest
from fastapi.testclient import TestClient

from agent_monitor.native_access import challenge_for
from agent_monitor.server import create_app
from agent_monitor.store import iso
from agent_monitor.task_interactions import MAX_LOCAL_CATALOG_DISCOVERIES


ORIGIN = "https://catalog.test"
ROUTE = "/api/native/tasks/controls"
ACCOUNT = {"username": "catalog-owner", "password": "Synthetic-test-catalog-password"}


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, collect_local=False, public_url=ORIGIN, allowed_hosts=["catalog.test"])
    store = app.state.store
    replies = app.state.task_replies
    replies.local_enabled = True
    store.setup(**ACCOUNT)
    device_id = store.ensure_local("Synthetic catalog computer", "Windows")
    sources = [f"codex:00000000-0000-4000-8000-{i:012}" for i in range(4)]
    store.ingest(device_id, [{"id": source, "tool": "codex", "title": "Synthetic", "status": "completed",
                             "updated_at": iso()} for source in sources], [])
    # A plain TestClient exercises HTTP without starting application lifespan
    # workers for collection, usage, or reply delivery.
    client = TestClient(app, base_url=ORIGIN)
    try:
        login = client.post("/api/login", json=ACCOUNT)
        assert login.status_code == 200
        verifier = secrets.token_urlsafe(32)
        start = client.post("/api/native/pairing/start", json={"device_name": "Synthetic phone",
            "mode": "full_app", "code_challenge": challenge_for(verifier)})
        path = "/api/native/pairing/" + start.json()["request_id"]
        assert client.post(path + "/approve", headers={"Origin": ORIGIN, "X-CSRF-Token": login.json()["csrf_token"]},
                           json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
        token = client.post(path + "/poll", json={"code_verifier": verifier}).json()["reader_token"]
        yield app, client, {"Authorization": "Bearer " + token}, device_id, sources
    finally:
        replies.close()
        app.state.usage.close()
        client.close()
        store.close()


def get(hub, source, *, authenticated=True):
    _, client, headers, device_id, _ = hub
    return client.get(ROUTE, headers=headers if authenticated else {}, params={"task_id": device_id + ":" + source})


def catalog_for(source):
    return {"provider": "codex", "catalog_revision": "rev-" + source[-1], "valid_until": time.time() + 120,
            "models": [{"id": "test-model", "label": "Synthetic model", "efforts": ["medium"]}]}


def wait_for_callback(controls, keys=None):
    end = time.monotonic() + 3
    while time.monotonic() < end:
        with controls.store.lock:
            if not (controls.local_catalog_discoveries if keys is None else controls.local_catalog_discoveries & keys):
                return
        time.sleep(0.005)
    pytest.fail("synthetic catalog worker did not finish")


def test_slow_catalog_returns_pending_then_ready_without_repeat_or_double_limit(hub, monkeypatch):
    app, _, _, device_id, sources = hub
    source = sources[0]
    controls = app.state.task_replies.controls
    entered, release = threading.Event(), threading.Event()
    calls = []

    def provider(passed_source):
        calls.append(passed_source)
        entered.set()
        if not release.wait(4):
            raise TimeoutError
        return catalog_for(passed_source)

    controls.catalog_provider = provider
    limits = app.state.native_access.limits
    check = limits.check
    charged = []

    def record_charge(key, *args, **kwargs):
        charged.append(key[0])
        return check(key, *args, **kwargs)

    monkeypatch.setattr(limits, "check", record_charge)
    try:
        assert get(hub, source, authenticated=False).status_code == 401
        assert not calls
        charged.clear()
        first = get(hub, source)
        assert first.status_code == 200
        assert first.json()["catalog_status"] == "pending"
        assert first.json()["catalog"] is None
        assert first.json()["capabilities"]["model_settings"] is False
        assert charged == ["reader-global", "reader-ip", "snapshot"]
        assert entered.wait(2)
        assert not release.is_set()
        for _ in range(3):
            assert get(hub, source).json()["catalog_status"] == "pending"
        assert calls == [source]
        release.set()
        wait_for_callback(controls)
        ready = get(hub, source).json()
        assert ready["catalog_status"] == "ready"
        assert ready["capabilities"]["model_settings"] is True
        assert ready["catalog"]["catalog_revision"] == "rev-0"
        assert get(hub, source).json() == ready
        assert calls == [source]

        with controls.store.lock:
            controls.catalogs[(device_id, source)]["valid_until"] = 0
        assert get(hub, source).json()["catalog_status"] == "unavailable"  # cooldown survives expiration
        assert calls == [source]
    finally:
        release.set()
        wait_for_callback(controls)


def test_capacity_retries_on_next_poll_and_caches_by_exact_task(hub):
    app, _, _, device_id, sources = hub
    controls = app.state.task_replies.controls
    events = {source: (threading.Event(), threading.Event()) for source in sources}

    def provider(source):
        entered, release = events[source]
        entered.set()
        if not release.wait(4):
            raise TimeoutError
        return catalog_for(source)

    controls.catalog_provider = provider
    running = sources[:MAX_LOCAL_CATALOG_DISCOVERIES]
    another = sources[len(running)]
    try:
        for source in running:
            assert get(hub, source).json()["catalog_status"] == "pending"
            assert events[source][0].wait(2)
        assert get(hub, another).json()["catalog_status"] == "pending"
        assert not events[another][0].is_set()
        with controls.store.lock:
            assert (device_id, another) not in controls.catalog_attempts
        first = running[0]
        events[first][1].set()
        wait_for_callback(controls, {(device_id, first)})
        assert get(hub, first).json()["catalog"]["catalog_revision"] == "rev-0"
        assert get(hub, another).json()["catalog_status"] == "pending"
        assert events[another][0].wait(2)
        events[another][1].set()
        wait_for_callback(controls, {(device_id, another)})
        assert get(hub, another).json()["catalog"]["catalog_revision"] == "rev-" + another[-1]
    finally:
        for _, release in events.values():
            release.set()
        wait_for_callback(controls)


def test_provider_failure_has_cooldown_and_recovers_on_next_demand(hub):
    app, _, _, device_id, sources = hub
    controls = app.state.task_replies.controls
    source = sources[0]
    calls = []

    def provider(passed_source):
        calls.append(passed_source)
        if len(calls) == 1:
            raise ValueError("private synthetic error must not cross the API")
        return catalog_for(passed_source)

    controls.catalog_provider = provider
    assert get(hub, source).json()["catalog_status"] == "pending"
    wait_for_callback(controls)
    failed = get(hub, source)
    assert failed.json()["catalog_status"] == "unavailable"
    assert "private synthetic error" not in failed.text
    assert calls == [source]
    with controls.store.lock:
        controls.catalog_attempts[device_id, source] = (0, False)
    assert get(hub, source).json()["catalog_status"] == "pending"
    wait_for_callback(controls)
    assert get(hub, source).json()["catalog_status"] == "ready"
    assert calls == [source, source]


def test_remote_catalog_stays_pending_until_connector_publishes(hub):
    app, _, _, device_id, sources = hub
    replies = app.state.task_replies
    controls = replies.controls
    source = sources[0]
    calls = []
    controls.catalog_provider = calls.append
    with controls.store.lock, controls.store.db:
        controls.store.db.execute("UPDATE devices SET local=0 WHERE id=?", (device_id,))
        replies._capabilities[device_id] = (["codex"], time.time())

    assert get(hub, source).json()["catalog_status"] == "pending"
    with controls.store.lock:
        assert controls.take_catalog_requests(device_id) == [source]
    assert get(hub, source).json()["catalog_status"] == "pending"
    with controls.store.lock:
        assert controls.take_catalog_requests(device_id) == []
    controls.publish_catalog(device_id, source, catalog_for(source))
    assert get(hub, source).json()["catalog_status"] == "ready"
    assert not calls
