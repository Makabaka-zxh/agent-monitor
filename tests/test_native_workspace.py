"""Native workbench authority, safe payloads, and shared business behavior."""
import base64
from datetime import datetime, timezone
from io import BytesIO
import json
import secrets
import time

from fastapi.testclient import TestClient
from PIL import Image
import pytest

from agent_monitor.native_access import challenge_for
from agent_monitor.server import COOKIE, create_app
from agent_monitor.store import Store, digest

ORIGIN = "https://native-workspace.example.test"
ACCOUNT = {"username": "native-workspace-owner", "password": "Synthetic-native-password-123"}
UUID = "00000000-0000-4000-8000-000000000000"
REQUEST_ID = "A" * 32
OPERATIONS = [
    ("GET", "/api/native/workbench", None),
    ("GET", "/api/native/usage", None),
    ("GET", "/api/native/account", None),
    ("GET", "/api/native/profile", None),
    ("PATCH", "/api/native/profile", {"display_name": "Changed"}),
    ("PATCH", "/api/native/preferences", {"tool_filter": "codex"}),
    ("PATCH", "/api/native/tasks/archive", {"task_id": UUID + ":task", "archived": True}),
    ("POST", "/api/native/computers/pairing", None),
    ("GET", "/api/native/computers/pairing/" + REQUEST_ID, None),
    ("POST", "/api/native/computers/pairing/" + REQUEST_ID + "/approve", None),
    ("POST", "/api/native/computers/pairing/" + REQUEST_ID + "/reject", None),
    ("DELETE", "/api/native/computers/" + UUID, None),
    ("DELETE", "/api/native/sessions/" + UUID, None),
    ("DELETE", "/api/native/connections/" + REQUEST_ID, None),
]


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["native-workspace.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 20000)) as browser:
        response = browser.post("/api/login", json=ACCOUNT)
        assert response.status_code == 200
        native = TestClient(app, base_url=ORIGIN, client=("127.0.0.2", 20001))
        try:
            yield app, browser, native, {"Origin": ORIGIN, "X-CSRF-Token": response.json()["csrf_token"]}
        finally:
            native.close()


def claim(hub, mode="full_app", browser=None, csrf=None):
    _, owner, native, headers = hub
    verifier = secrets.token_urlsafe(32)
    start = native.post("/api/native/pairing/start", json={
        "device_name": "Native Android", "code_challenge": challenge_for(verifier), "mode": mode,
    })
    assert start.status_code == 200, start.text
    path = "/api/native/pairing/" + start.json()["request_id"]
    assert (browser or owner).post(path + "/approve", headers=csrf or headers,
        json={"mode": mode, "consent_version": mode + "_v1"}).status_code == 200
    result = native.post(path + "/poll", json={"code_verifier": verifier})
    assert result.status_code == 200
    return result.json(), verifier


def auth(claimed):
    return {"Authorization": "Bearer " + claimed["reader_token"]}


def run_operations(client, headers, expected):
    for method, path, body in OPERATIONS:
        response = client.request(method, path, headers=headers, json=body)
        assert response.status_code == expected, (method, path, response.text)
        assert "set-cookie" not in response.headers


@pytest.mark.parametrize("credential", ["missing", "browser_cookie", "browser_token", "read_only", "malformed"])
def test_all_new_routes_reject_non_full_app_authority_without_business_reads(hub, monkeypatch, credential):
    app, browser, native, _ = hub
    headers, client = {}, native
    if credential == "browser_cookie":
        client = browser
    elif credential == "browser_token":
        headers = {"Authorization": "Bearer " + browser.cookies.get(COOKIE)}
    elif credential == "read_only":
        headers = auth(claim(hub, "read_only")[0])
    elif credential == "malformed":
        headers = {"Authorization": "Bearer nrd_missing"}
    before = app.state.store.profile()
    monkeypatch.setattr(app.state.store, "profile", lambda: pytest.fail("Unauthorized profile read"))
    monkeypatch.setattr(app.state.store, "snapshot", lambda: pytest.fail("Unauthorized workbench read"))
    run_operations(client, headers, 403 if credential == "read_only" else 401)
    monkeypatch.undo()
    assert app.state.store.profile() == before


def test_full_app_works_without_web_ticket_exchange_or_cookie_and_fast_poll_has_no_avatar(hub):
    app, browser, native, _ = hub
    claimed, _ = claim(hub)
    store = app.state.store
    local = store.ensure_local("电脑", "Windows")
    store.update_preferences({"sync_output": True})
    store.ingest(local, [{"id": "task/with:characters", "title": "真实任务结构", "tool": "codex",
        "status": "running", "updated_at": datetime.now(timezone.utc).isoformat(), "project": "Project",
        "output": "private-output", "preview": "summary", "status_source": "local_log"}],
        [{"tool": "codex", "available": True, "mode": "local_log", "detail": "available"}])
    result = native.get("/api/native/workbench", headers=auth(claimed))
    assert result.status_code == 200 and "set-cookie" not in result.headers
    data = result.json()
    assert "user" not in data and "avatar" not in data
    assert "output" not in data["tasks"][0]  # Result body is fetched only when opening its task.
    assert data["tasks"][0]["device_name"] == "电脑"
    assert data["devices"][0]["platform"] == "Windows" and data["devices"][0]["local"]
    assert data["generated_at"] == data["server_time"]
    account = native.get("/api/native/account", headers=auth(claimed)).json()
    assert account["user"]["username"] == ACCOUNT["username"]
    assert account["google_linked"] is False and account["google_login"] is False
    assert sum(item["current"] for item in account["native_devices"]) == 1
    assert sum(item["authorizes_current"] for item in account["sessions"]) == 1
    assert not native.cookies
    assert store.db.execute("SELECT COUNT(*) FROM sessions WHERE native_reader_id IS NOT NULL").fetchone()[0] == 0
    serial = json.dumps(account)
    for secret in (claimed["reader_token"], claimed["web_session_ticket"], browser.cookies.get(COOKIE), "token_hash", "csrf", "password"):
        assert secret not in serial
    assert native.get("/api/me", headers=auth(claimed)).status_code == 401


def test_profile_avatar_and_archive_share_browser_validation_and_store(hub):
    app, browser, native, _ = hub
    claimed, _ = claim(hub)
    headers = auth(claimed)
    png = BytesIO()
    Image.new("RGB", (20, 20), "blue").save(png, format="PNG")
    avatar = "data:image/png;base64," + base64.b64encode(png.getvalue()).decode()
    result = native.patch("/api/native/profile", headers=headers, json={"display_name": "  Native 昵称  ", "avatar": avatar})
    assert result.status_code == 200
    assert result.json()["user"]["display_name"] == "Native 昵称"
    assert result.json()["user"]["avatar"].startswith("data:image/jpeg;base64,")
    assert browser.get("/api/me").json()["user"] == result.json()["user"]
    for body in ({"username": "forbidden"}, {"display_name": "\nname"}, {"avatar": "https://attacker.test/image"}, {"avatar": None}):
        assert native.patch("/api/native/profile", headers=headers, json=body).status_code == 422
    assert native.patch("/api/native/profile", headers=headers, json={"avatar": ""}).json()["user"]["avatar"] == ""
    assert native.patch("/api/native/preferences", headers=headers, json={"tool_filter": "claude"}).json()["tool_filter"] == "claude"
    assert native.patch("/api/native/preferences", headers=headers, json={"arbitrary": True}).status_code == 422
    ids = []
    for name in ("A", "B"):
        code = app.state.store.create_pairing()["code"]
        device = app.state.store.register(code, name, "Windows")
        app.state.store.ingest(device["device_id"], [{"id": "same/id:task", "title": name, "tool": "codex", "status": "running",
            "updated_at": datetime.now(timezone.utc).isoformat()}], [])
        ids.append(device["device_id"] + ":same/id:task")
    body = {"task_id": ids[0], "archived": True}
    assert native.patch("/api/native/tasks/archive", headers=headers, json=body).json() == {"id": ids[0], "archived": True}
    tasks = {item["id"]: item for item in browser.get("/api/snapshot").json()["tasks"]}
    assert tasks[ids[0]]["archived"] and not tasks[ids[1]]["archived"]
    assert native.patch("/api/native/tasks/archive", headers=headers, json={**body, "archived": False}).json()["archived"] is False
    assert native.patch("/api/native/tasks/archive", headers=headers, json={**body, "archived": "false"}).status_code == 422
    assert native.patch("/api/native/tasks/archive", headers=headers, json={**body, "task_id": "x" * 501}).status_code == 422
    assert native.patch("/api/native/tasks/archive", headers=headers, json={**body, "task_id": "missing:task"}).status_code == 404


def test_native_pairing_confirms_exact_request_and_removes_only_remote_computer(hub):
    app, browser, native, _ = hub
    headers = auth(claim(hub)[0])
    code = native.post("/api/native/computers/pairing", headers=headers).json()["code"]
    device = browser.post("/api/agent/register", json={"code": code, "name": "远程", "platform": "Windows"}).json()
    assert native.delete("/api/native/computers/" + device["device_id"], headers=headers).status_code == 200
    assert app.state.store.agent_device(device["token"]) is None
    local = app.state.store.ensure_local("服务电脑", "Windows")
    assert native.delete("/api/native/computers/" + local, headers=headers).status_code == 409
    for approve in (True, False):
        pairing = browser.post("/api/agent/pairing/start", json={"name": "扫码电脑", "platform": "Linux"}).json()
        path = "/api/native/computers/pairing/" + pairing["request_id"]
        details = native.get(path, headers=headers)
        assert details.status_code == 200 and details.json()["name"] == "扫码电脑"
        assert "poll_secret" not in details.text and pairing["poll_secret"] not in details.text
        decision = native.post(path + ("/approve" if approve else "/reject"), headers=headers)
        assert decision.status_code == 200
        assert native.post(path + "/approve", headers=headers).status_code == 409
        result = browser.post("/api/agent/pairing/" + pairing["request_id"] + "/poll", json={"poll_secret": pairing["poll_secret"]})
        assert result.status_code == (200 if approve else 403)
    assert native.get("/api/native/computers/pairing/bad-id", headers=headers).status_code == 404


@pytest.mark.parametrize("revoke", ["parent_logout", "parent_expire", "reader_expire", "native_self", "native_from_sibling", "parent_from_native", "separate_connection"])
def test_revocation_prevents_all_workspace_reads_and_writes(hub, tmp_path, revoke):
    app, browser, native, csrf = hub
    claimed, _ = claim(hub)
    headers = auth(claimed)
    store = app.state.store
    parent = store.session(browser.cookies.get(COOKIE))
    reader = store.db.execute("SELECT * FROM native_readers WHERE token_hash=?", (digest(claimed["reader_token"]),)).fetchone()
    if revoke == "parent_logout":
        assert browser.post("/api/logout", headers=csrf).status_code == 200
    elif revoke in {"parent_expire", "reader_expire"}:
        table, key = ("sessions", parent["id"]) if revoke == "parent_expire" else ("native_readers", reader["id"])
        with store.lock, store.db:
            store.db.execute(f"UPDATE {table} SET expires_at=? WHERE id=?", (time.time() - 1, key))
    elif revoke == "native_self":
        assert native.delete("/api/native/connections/" + reader["id"], headers=headers).status_code == 200
    elif revoke == "native_from_sibling":
        sibling, _ = claim(hub)
        assert native.delete("/api/native/connections/" + reader["id"], headers=auth(sibling)).status_code == 200
        assert native.get("/api/native/workbench", headers=auth(sibling)).status_code == 200
    elif revoke == "parent_from_native":
        assert native.delete("/api/native/sessions/" + parent["id"], headers=headers).status_code == 200
    else:
        other = Store(tmp_path / "monitor.sqlite3")
        try:
            other.revoke_session(parent["id"])
        finally:
            other.close()
    run_operations(native, headers, 401)
    assert store.profile()["display_name"] == ACCOUNT["username"]


def test_expired_grandparent_invalidates_full_app_approved_by_legacy_child(hub):
    app, browser, native, _ = hub
    first, verifier = claim(hub)
    parent = app.state.store.session(browser.cookies.get(COOKIE))
    exchange = native.post("/api/native/web-session", headers=auth(first),
        json={"ticket": first["web_session_ticket"], "code_verifier": verifier})
    assert exchange.status_code == 200
    with TestClient(app, base_url=ORIGIN) as child:
        child.cookies.update(exchange.cookies)
        csrf = {"Origin": ORIGIN, "X-CSRF-Token": child.get("/api/me").json()["csrf_token"]}
        descendant, _ = claim(hub, browser=child, csrf=csrf)
        assert native.get("/api/native/workbench", headers=auth(descendant)).status_code == 200
        with app.state.store.lock, app.state.store.db:
            app.state.store.db.execute("UPDATE sessions SET expires_at=? WHERE id=?", (time.time() - 1, parent["id"]))
        run_operations(native, auth(descendant), 401)


def test_other_personal_hub_token_and_cross_origin_never_authorize_operations(hub, tmp_path):
    app, browser, native, _ = hub
    claimed, _ = claim(hub)
    headers = auth(claimed)
    # This product has one account per personal hub, not a shared multi-user DB.
    # A credential from a different owner's hub must not authenticate here.
    foreign = create_app(tmp_path / "other-owner", public_url=ORIGIN, allowed_hosts=["native-workspace.example.test"])
    foreign.state.store.setup("other-owner", "Synthetic-other-password-123")
    with TestClient(foreign, base_url=ORIGIN) as stranger:
        run_operations(stranger, headers, 401)
        assert foreign.state.store.profile()["display_name"] == "other-owner"
    run_operations(native, {**headers, "Origin": "https://attacker.test"}, 403)
    assert app.state.store.profile()["display_name"] == ACCOUNT["username"]
