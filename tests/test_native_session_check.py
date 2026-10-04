"""Payload-free native heartbeat keeps the existing authentication boundary."""
import secrets
import time

from fastapi.testclient import TestClient
import pytest

from agent_monitor import native_access
from agent_monitor.native_access import challenge_for
from agent_monitor.server import COOKIE, create_app
from agent_monitor.store import digest

ORIGIN = "https://session-check.example.test"
ACCOUNT = {"username": "session-check-owner", "password": "Synthetic-session-check-password-123"}


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["session-check.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 21000)) as browser:
        login = browser.post("/api/login", json=ACCOUNT)
        assert login.status_code == 200
        native = TestClient(app, base_url=ORIGIN, client=("127.0.0.2", 21001))
        try:
            yield app, browser, native, {"Origin": ORIGIN, "X-CSRF-Token": login.json()["csrf_token"]}
        finally:
            native.close()


def connect(hub, mode="read_only"):
    _, browser, native, csrf = hub
    verifier = secrets.token_urlsafe(32)
    start = native.post("/api/native/pairing/start", json={
        "device_name": "Synthetic Android", "code_challenge": challenge_for(verifier), "mode": mode,
    })
    assert start.status_code == 200
    path = "/api/native/pairing/" + start.json()["request_id"]
    approved = browser.post(path + "/approve", headers=csrf,
                            json={"mode": mode, "consent_version": mode + "_v1"})
    assert approved.status_code == 200
    claim = native.post(path + "/poll", json={"code_verifier": verifier})
    assert claim.status_code == 200
    return claim.json(), verifier


def bearer(token):
    return {"Authorization": "Bearer " + token}


@pytest.mark.parametrize("mode", ["read_only", "full_app"])
def test_check_returns_empty_204_without_loading_workspace_or_extending_expiry(hub, monkeypatch, mode):
    app, _, native, _ = hub
    claim, _ = connect(hub, mode)
    store = app.state.store
    token_hash = digest(claim["reader_token"])
    with store.lock, store.db:
        store.db.execute("UPDATE native_readers SET last_seen=1 WHERE token_hash=?", (token_hash,))
        before = dict(store.db.execute("SELECT * FROM native_readers WHERE token_hash=?", (token_hash,)).fetchone())

    def workspace_read_forbidden():
        pytest.fail("A session heartbeat must not load tasks or profile data")

    monkeypatch.setattr(store, "snapshot", workspace_read_forbidden)
    monkeypatch.setattr(store, "profile", workspace_read_forbidden)
    started = time.time()
    response = native.get("/api/native/session", headers=bearer(claim["reader_token"]))
    assert response.status_code == 204
    assert response.content == b""
    assert response.headers["Cache-Control"] == "no-store"
    assert "Set-Cookie" not in response.headers
    with store.lock:
        after = dict(store.db.execute("SELECT * FROM native_readers WHERE token_hash=?", (token_hash,)).fetchone())
    assert started <= after["last_seen"] <= time.time()
    assert {key: value for key, value in after.items() if key != "last_seen"} == {
        key: value for key, value in before.items() if key != "last_seen"
    }


@pytest.mark.parametrize("credential", ["none", "browser_cookie", "browser_bearer", "unknown_reader", "malformed_reader"])
def test_check_requires_native_reader_bearer_and_does_not_write_for_invalid_credentials(hub, credential):
    app, browser, native, _ = hub
    client, headers = native, {}
    if credential == "browser_cookie":
        client = browser
    elif credential == "browser_bearer":
        headers = bearer(browser.cookies.get(COOKIE))
    elif credential == "unknown_reader":
        headers = bearer("nrd_" + secrets.token_urlsafe(32))
    elif credential == "malformed_reader":
        headers = bearer("nrd_invalid")
    statements = []
    app.state.store.db.set_trace_callback(statements.append)
    try:
        response = client.get("/api/native/session", headers=headers)
    finally:
        app.state.store.db.set_trace_callback(None)
    assert response.status_code == 401
    assert "Set-Cookie" not in response.headers
    assert not any(sql.lstrip().upper().startswith(("UPDATE", "INSERT", "DELETE")) for sql in statements)


@pytest.mark.parametrize("action", [
    "parent_logout", "parent_revoke", "parent_expire", "reader_expire",
    "reader_revoke", "native_disconnect", "web_logout", "account_removed",
])
def test_check_immediately_rejects_revocation_and_expiry(hub, action):
    app, browser, native, csrf = hub
    claim, verifier = connect(hub, "full_app")
    headers = bearer(claim["reader_token"])
    store = app.state.store
    parent = store.session(browser.cookies.get(COOKIE))
    with store.lock:
        reader_id = store.db.execute("SELECT id FROM native_readers WHERE token_hash=?", (digest(claim["reader_token"]),)).fetchone()[0]
    assert native.get("/api/native/session", headers=headers).status_code == 204

    if action == "parent_logout":
        assert browser.post("/api/logout", headers=csrf).status_code == 200
    elif action == "parent_revoke":
        store.revoke_session(parent["id"])
    elif action in {"parent_expire", "reader_expire"}:
        table, identity = ("sessions", parent["id"]) if action == "parent_expire" else ("native_readers", reader_id)
        with store.lock, store.db:
            store.db.execute(f"UPDATE {table} SET expires_at=? WHERE id=?", (time.time() - 1, identity))
    elif action == "reader_revoke":
        assert browser.delete("/api/native/devices/" + reader_id, headers=csrf).status_code == 200
    elif action == "native_disconnect":
        assert native.delete("/api/native/session", headers=headers).status_code == 200
    elif action == "web_logout":
        exchange = native.post("/api/native/web-session", headers=headers, json={
            "ticket": claim["web_session_ticket"], "code_verifier": verifier,
        })
        assert exchange.status_code == 200
        webview = TestClient(app, base_url=ORIGIN)
        try:
            webview.cookies.update(exchange.cookies)
            me = webview.get("/api/me")
            assert me.status_code == 200
            assert webview.post("/api/logout", headers={"Origin": ORIGIN, "X-CSRF-Token": me.json()["csrf_token"]}).status_code == 200
        finally:
            webview.close()
        assert browser.get("/api/me").status_code == 200
    else:
        with store.lock, store.db:
            store.db.execute("DELETE FROM account")
    assert native.get("/api/native/session", headers=headers).status_code == 401
    assert native.get("/api/native/snapshot", headers=headers).status_code == 401


def test_check_and_snapshot_share_the_existing_reader_limit(hub):
    _, _, native, _ = hub
    claim, _ = connect(hub)
    headers = bearer(claim["reader_token"])
    # Switching between lightweight and full reads cannot double the budget.
    for index in range(120):
        path = "/api/native/session" if index % 2 == 0 else "/api/native/snapshot"
        response = native.get(path, headers=headers)
        assert response.status_code == (204 if index % 2 == 0 else 200)
    for path in ("/api/native/session", "/api/native/snapshot"):
        response = native.get(path, headers=headers)
        assert response.status_code == 429
        assert int(response.headers["Retry-After"]) > 0


def test_check_keeps_shared_ip_limit_for_rotating_invalid_tokens(hub, monkeypatch):
    _, _, native, _ = hub
    monkeypatch.setattr(native_access, "READER_REQUESTS_PER_IP", 5)
    for index in range(5):
        path = "/api/native/session" if index % 2 == 0 else "/api/native/snapshot"
        assert native.get(path, headers=bearer("nrd_" + secrets.token_urlsafe(32))).status_code == 401
    response = native.get("/api/native/session", headers=bearer("nrd_" + secrets.token_urlsafe(32)))
    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) > 0
