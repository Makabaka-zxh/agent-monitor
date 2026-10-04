"""Native readers: explicit consent, PKCE, strict scope and revocation."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import secrets
import time

from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from agent_monitor import native_access
from agent_monitor.native_access import NativeAccess, challenge_for
from agent_monitor.server import COOKIE, create_app
from agent_monitor.store import ONLINE_SECONDS, Store, digest, iso

ORIGIN = "https://monitor.example.test"
ACCOUNT = {"username": "native-owner", "password": "Native-test-password-123"}


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["monitor.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 20000)) as owner:
        login = owner.post("/api/login", json=ACCOUNT)
        assert login.status_code == 200
        native = TestClient(app, base_url=ORIGIN, client=("127.0.0.2", 20001))
        try:
            yield app, owner, native, {"Origin": ORIGIN, "X-CSRF-Token": login.json()["csrf_token"]}
        finally:
            native.close()


def begin(native, name="测试 Android"):
    verifier = secrets.token_urlsafe(32)
    response = native.post("/api/native/pairing/start", json={"device_name": name, "code_challenge": challenge_for(verifier)})
    assert response.status_code == 200, response.text
    return response.json(), verifier


def endpoint(pair):
    return "/api/native/pairing/" + pair["request_id"]


def poll(native, pair, verifier):
    return native.post(endpoint(pair) + "/poll", json={"code_verifier": verifier})


def reader(hub, name="测试 Android"):
    _, owner, native, csrf = hub
    pair, verifier = begin(native, name)
    assert owner.post(endpoint(pair) + "/approve", headers=csrf).json() == {"status": "approved"}
    result = poll(native, pair, verifier)
    assert result.status_code == 200, result.text
    return result.json(), pair, verifier


def auth(token):
    return {"Authorization": "Bearer " + token}


def test_pairing_requires_explicit_browser_consent_and_never_returns_verifier(hub):
    app, owner, native, csrf = hub
    pair, verifier = begin(native)
    assert set(pair) == {"request_id", "verification_url", "expires_at", "interval"}
    assert len(pair["request_id"]) == 32 and pair["interval"] == 3
    assert pair["verification_url"] == ORIGIN + "/#/native-connect/" + pair["request_id"]
    assert verifier not in json.dumps(pair) and challenge_for(verifier) not in json.dumps(pair)
    assert 595 <= datetime.fromisoformat(pair["expires_at"]).timestamp() - time.time() <= 600
    assert native.get(endpoint(pair)).status_code == 401
    details = owner.get(endpoint(pair)).json()
    assert details == {"request_id": pair["request_id"], "device_name": "测试 Android",
                       "status": "pending", "expires_at": pair["expires_at"],
                       "mode": "read_only", "scopes": ["task_status"], "consent_version": "read_only_v1"}
    assert poll(native, pair, verifier).json() == {"status": "pending"}
    assert owner.post(endpoint(pair) + "/approve").status_code == 403
    assert owner.post(endpoint(pair) + "/approve", headers={**csrf, "Origin": "https://attacker.test"}).status_code == 403
    assert native.post(endpoint(pair) + "/approve", headers=csrf).status_code == 401
    assert poll(native, pair, verifier).json() == {"status": "pending"}
    assert owner.post(endpoint(pair) + "/approve", headers=csrf).json() == {"status": "approved"}
    result = poll(native, pair, verifier).json()
    assert set(result) == {"status", "reader_token", "expires_at"}
    assert result["status"] == "approved" and result["reader_token"].startswith("nrd_")
    with app.state.store.lock:
        pairing = dict(app.state.store.db.execute("SELECT * FROM native_pairings").fetchone())
        stored = dict(app.state.store.db.execute("SELECT * FROM native_readers").fetchone())
    assert pairing["challenge_hash"] == digest(challenge_for(verifier))
    assert stored["token_hash"] == digest(result["reader_token"])
    assert verifier not in json.dumps(pairing) and challenge_for(verifier) not in json.dumps(pairing)
    assert result["reader_token"] not in json.dumps(stored)
    assert poll(native, pair, verifier).status_code == 410
    assert owner.post(endpoint(pair) + "/approve", headers=csrf).status_code == 409


def test_wrong_pkce_cannot_observe_or_claim_then_correct_verifier_still_works(hub):
    _, owner, native, csrf = hub
    pair, verifier = begin(native)
    wrong = secrets.token_urlsafe(32)
    assert poll(native, pair, wrong).status_code == 401
    assert owner.post(endpoint(pair) + "/approve", headers=csrf).status_code == 200
    assert poll(native, pair, wrong).status_code == 401
    assert poll(native, pair, verifier).json()["status"] == "approved"


def test_rejection_expiration_and_parent_revocation_before_claim(hub):
    app, owner, native, csrf = hub
    pair, verifier = begin(native)
    assert owner.post(endpoint(pair) + "/reject", headers=csrf).json() == {"status": "rejected"}
    assert poll(native, pair, verifier).status_code == 403
    expired, expired_verifier = begin(native)
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute("UPDATE native_pairings SET expires_at=? WHERE id=?", (time.time() - 1, expired["request_id"]))
    assert owner.get(endpoint(expired)).status_code == 410
    assert poll(native, expired, expired_verifier).status_code == 410
    revoked, revoked_verifier = begin(native)
    assert owner.post(endpoint(revoked) + "/approve", headers=csrf).status_code == 200
    assert owner.post("/api/logout", headers=csrf).status_code == 200
    assert poll(native, revoked, revoked_verifier).status_code == 410


@pytest.mark.parametrize("changes", [
    {"device_name": " "}, {"device_name": "x" * 61}, {"device_name": "fake\nname"},
    {"device_name": "fake\u202ename"}, {"code_challenge": "x" * 42},
    {"code_challenge": "x" * 44}, {"code_challenge": "A" * 42 + "B"},
    {"code_challenge": "!" * 43}, {"reader_token": "should-not-be-accepted"},
])
def test_invalid_start_rejected_without_echoing_sensitive_payload(hub, changes):
    app, _, native, _ = hub
    payload = {"device_name": "手机", "code_challenge": challenge_for("x" * 43), **changes}
    result = native.post("/api/native/pairing/start", json=payload)
    assert result.status_code == 422
    assert len(result.text) < 300
    with app.state.store.lock:
        assert app.state.store.db.execute("SELECT COUNT(*) FROM native_pairings").fetchone()[0] == 0


@pytest.mark.parametrize("verifier", ["x" * 42, "x" * 129, "!" * 43])
def test_invalid_verifier_format_rejected(hub, verifier):
    _, _, native, _ = hub
    pair, _ = begin(native)
    assert poll(native, pair, verifier).status_code == 422


def test_native_snapshot_is_minimal_and_reuses_offline_and_archive_status(hub):
    app, owner, native, _ = hub
    result, _, _ = reader(hub)
    store = app.state.store
    store.update_preferences({"sync_output": True})
    store.update_profile({"display_name": "小牧"})
    device_id = store.ensure_local("测试电脑", "secret-platform")
    task = {"id": "test-source", "title": "测试任务", "tool": "codex", "status": "waiting",
            "updated_at": iso(), "preview": "PRIVATE-PREVIEW", "output": "PRIVATE-OUTPUT",
            "project": "PRIVATE-PROJECT", "status_source": "local_log"}
    store.ingest(device_id, [task], [{"tool": "codex", "detail": "PRIVATE-SOURCE-LOG"}])
    store.archive_task(device_id + ":test-source", True)
    response = native.get("/api/native/snapshot", headers=auth(result["reader_token"]))
    assert response.status_code == 200
    snapshot = response.json()
    assert set(snapshot) == {"user", "devices", "tasks", "generated_at"}
    assert snapshot["user"] == {"display_name": "小牧", "avatar": ""}
    assert set(snapshot["devices"][0]) == {"id", "name", "online"}
    assert set(snapshot["tasks"][0]) == {"id", "title", "tool", "status", "updated_at", "device_id", "device_name", "archived"}
    assert snapshot["tasks"][0]["archived"] is True
    assert snapshot["tasks"][0]["device_name"] == "测试电脑"
    assert snapshot["tasks"][0]["status"] == "waiting"
    assert "PRIVATE" not in response.text and "secret-platform" not in response.text
    with store.lock, store.db:
        store.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time() - ONLINE_SECONDS - 1, device_id))
    snapshot = native.get("/api/native/snapshot", headers=auth(result["reader_token"])).json()
    assert snapshot["devices"][0]["online"] is False
    assert snapshot["tasks"][0]["status"] == store.snapshot()["tasks"][0]["status"] == "unknown"
    assert datetime.fromisoformat(snapshot["generated_at"]).tzinfo is not None


def test_reader_credentials_cannot_authenticate_web_writes_or_agent_upload(hub):
    app, owner, native, _ = hub
    result, _, _ = reader(hub)
    headers = auth(result["reader_token"])
    assert native.get("/api/native/snapshot", headers=headers).status_code == 200
    for path in ("/api/me", "/api/snapshot", "/api/sessions", "/api/native/devices"):
        assert native.get(path, headers=headers).status_code == 401
    assert native.patch("/api/preferences", headers=headers, json={"sync_output": True}).status_code == 401
    assert native.patch("/api/profile", headers=headers, json={"display_name": "changed"}).status_code == 401
    assert native.post("/api/pairing", headers=headers).status_code == 401
    assert native.post("/api/agent/heartbeat", headers=headers, json={"tasks": [], "sources": []}).status_code == 401
    assert native.delete("/api/native/devices/" + "a" * 32, headers=headers).status_code == 401
    assert owner.get("/api/native/snapshot").status_code == 401  # Cookie is not a reader.
    assert native.get("/api/native/snapshot", headers=auth(owner.cookies.get(COOKIE))).status_code == 401
    code = app.state.store.create_pairing()["code"]
    agent = app.state.store.register(code, "agent", "Windows")
    assert native.get("/api/native/snapshot", headers=auth(agent["token"])).status_code == 401


def test_browser_lists_and_revokes_reader_with_csrf_and_origin(hub):
    _, owner, native, csrf = hub
    result, _, _ = reader(hub)
    listing = owner.get("/api/native/devices").json()
    assert set(listing) == {"devices"} and len(listing["devices"]) == 1
    item = listing["devices"][0]
    assert set(item) == {"id", "name", "last_seen", "expires_at", "mode", "scopes"}
    assert result["reader_token"] not in json.dumps(listing)
    path = "/api/native/devices/" + item["id"]
    assert owner.delete(path).status_code == 403
    assert owner.delete(path, headers={**csrf, "Origin": "https://attacker.test"}).status_code == 403
    assert owner.delete(path, headers=csrf).json() == {"ok": True}
    assert native.get("/api/native/snapshot", headers=auth(result["reader_token"])).status_code == 401
    assert owner.get("/api/native/devices").json() == {"devices": []}
    assert owner.get("/api/me").status_code == 200


def test_reader_can_revoke_only_its_current_credential(hub):
    _, owner, native, _ = hub
    first, _, _ = reader(hub, "第一台")
    second, _, _ = reader(hub, "第二台")
    assert native.delete("/api/native/session", headers=auth(first["reader_token"])).json() == {"ok": True}
    assert native.get("/api/native/snapshot", headers=auth(first["reader_token"])).status_code == 401
    assert native.get("/api/native/snapshot", headers=auth(second["reader_token"])).status_code == 200
    assert owner.get("/api/me").status_code == 200


@pytest.mark.parametrize("mode", ["logout", "revoke", "expire", "reader-expire"])
def test_parent_session_and_reader_expiry_immediately_invalidate_access(hub, mode):
    app, owner, native, csrf = hub
    result, _, _ = reader(hub)
    store = app.state.store
    parent = store.session(owner.cookies.get(COOKIE))
    token = result["reader_token"]
    with store.lock:
        expires_at = store.db.execute("SELECT expires_at FROM native_readers WHERE token_hash=?", (digest(token),)).fetchone()[0]
    assert expires_at <= parent["expires_at"]
    assert expires_at <= time.time() + native_access.READER_SECONDS
    assert result["expires_at"] == iso(expires_at)
    if mode == "logout":
        assert owner.post("/api/logout", headers=csrf).status_code == 200
    elif mode == "revoke":
        store.revoke_session(parent["id"])
    else:
        with store.lock, store.db:
            table, identity = ("sessions", parent["id"]) if mode == "expire" else ("native_readers", store.db.execute("SELECT id FROM native_readers WHERE token_hash=?", (digest(token),)).fetchone()[0])
            store.db.execute(f"UPDATE {table} SET expires_at=? WHERE id=?", (time.time() - 1, identity))
    assert native.get("/api/native/snapshot", headers=auth(token)).status_code == 401
    assert native.delete("/api/native/session", headers=auth(token)).status_code == 401


def test_claim_is_atomic_across_separate_sqlite_connections_and_persists(hub, tmp_path):
    app, owner, native, csrf = hub
    pair, verifier = begin(native)
    assert owner.post(endpoint(pair) + "/approve", headers=csrf).status_code == 200
    second_store = Store(tmp_path / "monitor.sqlite3")
    second = NativeAccess(second_store)

    def claim(access):
        try:
            return 200, access.poll(pair["request_id"], verifier)
        except HTTPException as error:
            return error.status_code, None

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(claim, [app.state.native_access, second]))
        assert sorted(result[0] for result in results) == [200, 410]
        approved = next(result[1] for result in results if result[0] == 200)
        assert second.snapshot(approved["reader_token"])["user"]["display_name"] == ACCOUNT["username"]
        with second_store.lock:
            assert second_store.db.execute("SELECT COUNT(*) FROM native_readers").fetchone()[0] == 1
    finally:
        second_store.close()


def test_pairing_and_reader_counts_are_bounded(hub, monkeypatch):
    app, owner, native, csrf = hub
    monkeypatch.setattr(native_access, "MAX_PAIRINGS", 2)
    one, first_verifier = begin(native)
    two, second_verifier = begin(native)
    overflow = native.post("/api/native/pairing/start", json={"device_name": "overflow", "code_challenge": challenge_for("a" * 43)})
    assert overflow.status_code == 429 and "Retry-After" in overflow.headers
    monkeypatch.setattr(native_access, "MAX_READERS", 1)
    for pair in (one, two):
        assert owner.post(endpoint(pair) + "/approve", headers=csrf).status_code == 200
    first_token = poll(native, one, first_verifier).json()["reader_token"]
    assert poll(native, two, second_verifier).status_code == 429
    assert native.delete("/api/native/session", headers=auth(first_token)).status_code == 200
    assert poll(native, two, second_verifier).status_code == 200


def test_start_and_poll_rate_limits_return_retry_after(hub):
    _, _, native, _ = hub
    pair, verifier = begin(native)
    for _ in range(4):
        begin(native)
    limited = native.post("/api/native/pairing/start", json={"device_name": "limited", "code_challenge": challenge_for("b" * 43)})
    assert limited.status_code == 429 and int(limited.headers["Retry-After"]) > 0
    for _ in range(30):
        assert poll(native, pair, verifier).status_code == 200
    limited = poll(native, pair, verifier)
    assert limited.status_code == 429 and int(limited.headers["Retry-After"]) > 0


def test_start_requires_https_configuration_and_existing_account(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as client:
        payload = {"device_name": "手机", "code_challenge": challenge_for("c" * 43)}
        assert client.post("/api/native/pairing/start", json=payload).status_code == 503
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["monitor.example.test"])
    with TestClient(app, base_url=ORIGIN) as client:
        assert client.post("/api/native/pairing/start", json=payload).status_code == 409


def test_unknown_reader_credentials_are_rate_limited_before_database_write(hub, monkeypatch):
    app, _, native, _ = hub
    monkeypatch.setattr(native_access, "READER_REQUESTS_PER_IP", 5)
    statements = []
    app.state.store.db.set_trace_callback(statements.append)
    try:
        for index in range(5):
            method = native.get if index % 2 == 0 else native.delete
            path = "/api/native/snapshot" if index % 2 == 0 else "/api/native/session"
            assert method(path, headers=auth("nrd_" + secrets.token_urlsafe(32))).status_code == 401
        limited = native.get("/api/native/snapshot", headers=auth("nrd_" + secrets.token_urlsafe(32)))
        assert limited.status_code == 429 and int(limited.headers["Retry-After"]) > 0
        assert not any(query.lstrip().upper().startswith(("UPDATE", "INSERT", "DELETE")) for query in statements)
    finally:
        app.state.store.db.set_trace_callback(None)
