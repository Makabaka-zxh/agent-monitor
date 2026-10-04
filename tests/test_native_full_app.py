"""Explicit full-app consent and a one-use PKCE/reader-bound cookie handoff."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from http.cookies import SimpleCookie
import json
import secrets
import time

from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from agent_monitor.native_access import NativeAccess, challenge_for
from agent_monitor.server import COOKIE, create_app
from agent_monitor.store import Store, digest

ORIGIN = "https://monitor.example.test"
ACCOUNT = {"username": "full-app-owner", "password": "Synthetic-full-app-password-123"}
CONSENT = {"mode": "full_app", "consent_version": "full_app_v1"}


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["monitor.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN) as browser:
        login = browser.post("/api/login", json=ACCOUNT)
        assert login.status_code == 200
        native = TestClient(app, base_url=ORIGIN)
        webview = TestClient(app, base_url=ORIGIN)
        try:
            yield app, browser, native, webview, {"Origin": ORIGIN, "X-CSRF-Token": login.json()["csrf_token"]}
        finally:
            native.close()
            webview.close()


def start(native, mode="full_app"):
    verifier = secrets.token_urlsafe(32)
    response = native.post("/api/native/pairing/start", json={
        "device_name": "完整 App 测试", "code_challenge": challenge_for(verifier), "mode": mode,
    })
    assert response.status_code == 200, response.text
    return response.json(), verifier


def path(pair, action=""):
    return "/api/native/pairing/" + pair["request_id"] + action


def full_claim(hub, *, browser=None, csrf=None):
    _, owner, native, _, headers = hub
    pair, verifier = start(native)
    response = (browser or owner).post(path(pair, "/approve"), headers=csrf or headers, json=CONSENT)
    assert response.status_code == 200, response.text
    claim = native.post(path(pair, "/poll"), json={"code_verifier": verifier})
    assert claim.status_code == 200, claim.text
    return claim.json(), verifier


def bearer(token):
    return {"Authorization": "Bearer " + token}


def exchange(native, claim, verifier, **changes):
    return native.post("/api/native/web-session", headers=bearer(claim["reader_token"]), json={
        "ticket": claim["web_session_ticket"], "code_verifier": verifier, **changes,
    })


def connect(hub):
    app, browser, native, webview, csrf = hub
    claim, verifier = full_claim(hub)
    response = exchange(native, claim, verifier)
    assert response.status_code == 200, response.text
    webview.cookies.update(response.cookies)
    me = webview.get("/api/me")
    assert me.status_code == 200
    return claim, verifier, {"Origin": ORIGIN, "X-CSRF-Token": me.json()["csrf_token"]}


def test_full_scope_requires_exact_explicit_consent_and_cannot_change_mode(hub):
    app, browser, native, _, csrf = hub
    pair, verifier = start(native)
    detail = browser.get(path(pair)).json()
    assert detail["mode"] == "full_app"
    assert detail["scopes"] == ["task_status", "workspace_read", "workspace_write"]
    assert detail["consent_version"] == "full_app_v1"
    for body in (None, {}, {"mode": "full_app"}, {**CONSENT, "consent_version": "full_app_v0"},
                 {"mode": "read_only", "consent_version": "read_only_v1"}):
        response = browser.post(path(pair, "/approve"), headers=csrf, json=body) if body is not None else browser.post(path(pair, "/approve"), headers=csrf)
        assert response.status_code == 403
    assert native.post(path(pair, "/poll"), json={"code_verifier": verifier}).json() == {"status": "pending"}
    assert browser.post(path(pair, "/approve"), json=CONSENT).status_code == 403
    assert browser.post(path(pair, "/approve"), headers={**csrf, "Origin": "https://attacker.test"}, json=CONSENT).status_code == 403
    assert browser.post(path(pair, "/approve"), headers=csrf, json=CONSENT).status_code == 200
    old, _ = start(native, "read_only")
    assert browser.post(path(old, "/approve"), headers=csrf, json=CONSENT).status_code == 403
    assert browser.get(path(old)).json()["mode"] == "read_only"
    with app.state.store.lock:
        assert app.state.store.db.execute("SELECT COUNT(*) FROM native_readers").fetchone()[0] == 0


def test_ticket_handoff_sets_cookie_without_json_secrets_and_keeps_csrf(hub):
    app, browser, native, webview, _ = hub
    claim, verifier = full_claim(hub)
    assert claim["mode"] == "full_app"
    assert 55 <= datetime.fromisoformat(claim["web_session_ticket_expires_at"]).timestamp() - time.time() <= 60
    with app.state.store.lock:
        ticket_row = dict(app.state.store.db.execute("SELECT * FROM native_web_tickets").fetchone())
    assert ticket_row["ticket_hash"] == digest(claim["web_session_ticket"])
    assert ticket_row["proof_hash"] == digest(challenge_for(verifier))
    assert verifier not in json.dumps(ticket_row) and claim["web_session_ticket"] not in json.dumps(ticket_row)
    response = exchange(native, claim, verifier)
    assert response.status_code == 200
    assert set(response.json()) == {"ok", "expires_at"}
    assert all(secret not in response.text for secret in (verifier, claim["reader_token"], claim["web_session_ticket"]))
    cookie = SimpleCookie(response.headers["set-cookie"])[COOKIE]
    assert cookie["httponly"] and cookie["secure"] and cookie["samesite"].lower() == "strict" and cookie["path"] == "/"
    assert response.headers["cache-control"] == "no-store"
    webview.cookies.update(response.cookies)
    me = webview.get("/api/me").json()
    assert me["user"]["username"] == ACCOUNT["username"]
    assert webview.patch("/api/preferences", json={"tool_filter": "codex"}).status_code == 403
    headers = {"Origin": ORIGIN, "X-CSRF-Token": me["csrf_token"]}
    assert webview.patch("/api/preferences", headers=headers, json={"tool_filter": "codex"}).status_code == 200
    assert browser.get("/api/me").json()["preferences"]["tool_filter"] == "codex"
    assert exchange(native, claim, verifier).status_code == 410
    native.cookies.clear()
    assert native.get("/api/snapshot", headers=bearer(claim["reader_token"])).status_code == 401
    assert native.get("/api/native/snapshot", headers=bearer(claim["reader_token"])).status_code == 200
    assert browser.get("/api/native/devices").json()["devices"][0]["mode"] == "full_app"


def test_ticket_requires_matching_reader_and_verifier_and_no_get_exchange(hub):
    _, _, native, _, _ = hub
    first, verifier = full_claim(hub)
    second, second_verifier = full_claim(hub)
    assert exchange(native, first, secrets.token_urlsafe(32)).status_code == 401
    assert exchange(native, second, verifier, ticket=first["web_session_ticket"]).status_code == 401
    assert native.post("/api/native/web-session", json={"ticket": first["web_session_ticket"], "code_verifier": verifier}).status_code == 401
    assert native.post("/api/native/web-session", headers={**bearer(first["reader_token"]), "Origin": "https://attacker.test"},
                       json={"ticket": first["web_session_ticket"], "code_verifier": verifier}).status_code == 403
    get = native.get("/api/native/web-session", params={"ticket": first["web_session_ticket"]})
    assert get.status_code in {404, 405} and "set-cookie" not in get.headers
    assert exchange(native, first, verifier).status_code == 200
    assert exchange(native, second, second_verifier).status_code == 200


def test_legacy_reader_never_receives_ticket_or_exchanges_full_cookie(hub):
    app, browser, native, _, csrf = hub
    pair, verifier = start(native, "read_only")
    assert browser.post(path(pair, "/approve"), headers=csrf).status_code == 200
    old = native.post(path(pair, "/poll"), json={"code_verifier": verifier}).json()
    assert set(old) == {"status", "reader_token", "expires_at"}
    response = native.post("/api/native/web-session", headers=bearer(old["reader_token"]),
                           json={"ticket": "nwt_" + secrets.token_urlsafe(32), "code_verifier": verifier})
    assert response.status_code == 403 and "set-cookie" not in response.headers
    with app.state.store.lock:
        assert app.state.store.db.execute("SELECT COUNT(*) FROM native_web_tickets").fetchone()[0] == 0
    assert native.get("/api/native/snapshot", headers=bearer(old["reader_token"])).status_code == 200


def test_expired_ticket_cannot_issue_cookie_and_has_no_renewal_endpoint(hub):
    app, _, native, _, _ = hub
    claim, verifier = full_claim(hub)
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute("UPDATE native_web_tickets SET expires_at=?", (time.time() - 1,))
    response = exchange(native, claim, verifier)
    assert response.status_code == 410 and "set-cookie" not in response.headers
    assert native.get("/api/native/snapshot", headers=bearer(claim["reader_token"])).status_code == 200
    with app.state.store.lock:
        assert app.state.store.db.execute("SELECT COUNT(*) FROM sessions WHERE native_reader_id IS NOT NULL").fetchone()[0] == 0


@pytest.mark.parametrize("action", ["web_logout", "web_revoke", "reader_revoke", "native_disconnect", "parent_logout", "parent_expire", "reader_expire"])
def test_managed_web_cookie_and_reader_revocation_are_linked(hub, action):
    app, browser, native, webview, csrf = hub
    claim, _, web_csrf = connect(hub)
    store = app.state.store
    parent = store.session(browser.cookies.get(COOKIE))
    child = store.session(webview.cookies.get(COOKIE))
    reader_id = child["native_reader_id"]
    if action == "web_logout":
        assert webview.post("/api/logout", headers=web_csrf).status_code == 200
    elif action == "web_revoke":
        assert browser.delete("/api/sessions/" + child["id"], headers=csrf).status_code == 200
    elif action == "reader_revoke":
        assert browser.delete("/api/native/devices/" + reader_id, headers=csrf).status_code == 200
    elif action == "native_disconnect":
        assert native.delete("/api/native/session", headers=bearer(claim["reader_token"])).status_code == 200
    elif action == "parent_logout":
        assert browser.post("/api/logout", headers=csrf).status_code == 200
    else:
        table, identity = ("sessions", parent["id"]) if action == "parent_expire" else ("native_readers", reader_id)
        with store.lock, store.db:
            store.db.execute(f"UPDATE {table} SET expires_at=? WHERE id=?", (time.time() - 1, identity))
    assert webview.get("/api/me").status_code == 401
    assert native.get("/api/native/snapshot", headers=bearer(claim["reader_token"])).status_code == 401
    if action not in {"parent_logout", "parent_expire"}:
        assert browser.get("/api/me").status_code == 200
    with store.lock:
        assert store.db.execute("PRAGMA foreign_key_check").fetchall() == []


@pytest.mark.parametrize("action", ["logout", "expire", "native_revoke"])
def test_parent_invalidated_before_ticket_exchange_never_issues_cookie(hub, action):
    app, browser, native, _, csrf = hub
    claim, verifier = full_claim(hub)
    if action == "logout":
        browser.post("/api/logout", headers=csrf)
    elif action == "native_revoke":
        native.delete("/api/native/session", headers=bearer(claim["reader_token"]))
    else:
        parent = app.state.store.session(browser.cookies.get(COOKIE))
        with app.state.store.lock, app.state.store.db:
            app.state.store.db.execute("UPDATE sessions SET expires_at=? WHERE id=?", (time.time() - 1, parent["id"]))
    response = exchange(native, claim, verifier)
    assert response.status_code == 401 and "set-cookie" not in response.headers


def test_nested_native_sessions_cascade_without_cycles_or_revoking_root_browser(hub):
    app, browser, native, webview, _ = hub
    first, _, web_csrf = connect(hub)
    second, second_verifier = full_claim(hub, browser=webview, csrf=web_csrf)
    response = exchange(native, second, second_verifier)
    assert response.status_code == 200
    second_cookie = response.cookies.get(COOKIE)
    assert app.state.store.session(second_cookie)
    assert webview.post("/api/logout", headers=web_csrf).status_code == 200
    assert app.state.store.session(second_cookie) is None
    assert browser.get("/api/me").status_code == 200
    for claim in (first, second):
        assert native.get("/api/native/snapshot", headers=bearer(claim["reader_token"])).status_code == 401
    with app.state.store.lock:
        assert app.state.store.db.execute("SELECT COUNT(*) FROM native_readers").fetchone()[0] == 0
        assert app.state.store.db.execute("PRAGMA foreign_key_check").fetchall() == []


def test_ticket_exchange_is_atomic_across_connections(hub, tmp_path):
    app, _, _, _, _ = hub
    claim, verifier = full_claim(hub)
    second_store = Store(tmp_path / "monitor.sqlite3")
    second = NativeAccess(second_store)

    def exchange_one(access):
        try:
            token, _ = access.exchange_web_session(claim["reader_token"], claim["web_session_ticket"], verifier)
            return 200, token
        except HTTPException as error:
            return error.status_code, None

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(exchange_one, [app.state.native_access, second]))
        assert sorted(status for status, _ in results) == [200, 410]
        token = next(token for status, token in results if status == 200)
        assert second_store.session(token)
        with second_store.lock:
            assert second_store.db.execute("SELECT COUNT(*) FROM sessions WHERE native_reader_id IS NOT NULL").fetchone()[0] == 1
            assert second_store.db.execute("SELECT COUNT(*) FROM native_web_tickets").fetchone()[0] == 0
    finally:
        second_store.close()


def test_old_database_migration_preserves_sessions_and_readonly_reader(tmp_path):
    store = Store(tmp_path / "legacy.sqlite3")
    try:
        store.setup(**ACCOUNT)
        parent_token, _ = store.new_session("旧浏览器")
        parent = store.session(parent_token)
        old_reader = "nrd_" + secrets.token_urlsafe(32)
        with store.lock, store.db:
            store.db.execute("""CREATE TABLE native_readers (id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,last_seen REAL NOT NULL,expires_at REAL NOT NULL,
                parent_session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE)""")
            store.db.execute("INSERT INTO native_readers VALUES (?,?,?,?,?,?)", (
                secrets.token_urlsafe(24), digest(old_reader), "旧原生只读", time.time(), parent["expires_at"], parent["id"]))
        access = NativeAccess(store)
        NativeAccess(store)  # Idempotent migration on a database already upgraded.
        assert store.session(parent_token)["native_reader_id"] is None
        assert access.snapshot(old_reader)["user"]["display_name"] == ACCOUNT["username"]
        assert access.devices()["devices"][0]["mode"] == "read_only"
        with pytest.raises(HTTPException) as caught:
            access.exchange_web_session(old_reader, "nwt_" + secrets.token_urlsafe(32), "v" * 43)
        assert caught.value.status_code == 403
        fresh_token, _ = store.new_session("迁移后浏览器")
        assert store.session(fresh_token)
        store.revoke_session(parent["id"])
        with store.lock:
            assert store.db.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        store.close()
