"""Integration tests for the personal hub's authentication and device boundaries."""

from datetime import datetime, timezone
from http.cookies import SimpleCookie
import json
import time

from fastapi.testclient import TestClient
import pytest

from agent_monitor.server import COOKIE, create_app
from agent_monitor.store import ONLINE_SECONDS


ACCOUNT = {"username": "monitor-owner", "password": "integration-password-123"}
SOURCES = [{"tool": "codex", "available": True, "mode": "local_log", "detail": "状态记录可用"}]


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, collect_local=False)
    with TestClient(app, client=("127.0.0.1", 12345)) as owner:
        response = owner.post("/api/setup", json={**ACCOUNT, "device_name": "当前手机"})
        assert response.status_code == 200, response.text
        yield app, owner, {"X-CSRF-Token": response.json()["csrf_token"]}


def make_task(*, source_id="shared-session-id", title="任务标题", status="running", output="", preview=""):
    return {
        "id": source_id,
        "tool": "codex",
        "title": title,
        "status": status,
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "output": output,
        "preview": preview,
        "project": "example-project",
        "status_source": "local_log",
    }


def pair_device(owner, csrf, *, name="办公室电脑"):
    pairing = owner.post("/api/pairing", headers=csrf)
    assert pairing.status_code == 200, pairing.text
    response = owner.post(
        "/api/agent/register",
        json={"code": pairing.json()["code"], "name": name, "platform": "Windows"},
    )
    assert response.status_code == 200, response.text
    return response.json()


def send_heartbeat(client, device, tasks):
    return client.post(
        "/api/agent/heartbeat",
        headers={"Authorization": "Bearer " + device["token"]},
        json={"tasks": tasks, "sources": SOURCES},
    )


def test_two_viewers_share_preferences_and_can_revoke_a_session(hub):
    app, phone, phone_csrf = hub
    with TestClient(app, client=("127.0.0.1", 12346)) as tablet:
        login = tablet.post("/api/login", json={**ACCOUNT, "device_name": "平板"})
        assert login.status_code == 200
        tablet_csrf = {"X-CSRF-Token": login.json()["csrf_token"]}
        assert tablet.cookies.get(COOKIE) != phone.cookies.get(COOKIE)

        changed = phone.patch("/api/preferences", headers=phone_csrf, json={"tool_filter": "claude", "sync_output": True})
        assert changed.status_code == 200
        assert tablet.get("/api/me").json()["preferences"] == {"tool_filter": "claude", "sync_output": True}
        assert tablet.patch("/api/preferences", headers=tablet_csrf, json={"tool_filter": "codex"}).status_code == 200
        assert phone.get("/api/me").json()["preferences"]["tool_filter"] == "codex"

        sessions = phone.get("/api/sessions").json()["sessions"]
        assert len(sessions) == 2
        assert sum(item["current"] for item in sessions) == 1
        assert all(not ({"token", "token_hash", "csrf", "password"} & item.keys()) for item in sessions)
        tablet_id = next(item["id"] for item in sessions if item["name"] == "平板")
        assert phone.delete(f"/api/sessions/{tablet_id}", headers=phone_csrf).status_code == 200
        assert tablet.get("/api/me").status_code == 401
        assert tablet.patch("/api/preferences", headers=tablet_csrf, json={"sync_output": False}).status_code == 401
        assert phone.get("/api/me").status_code == 200


def test_pairing_code_is_single_use_and_expiring(hub):
    app, owner, csrf = hub
    pairing = owner.post("/api/pairing", headers=csrf).json()
    payload = {"code": pairing["code"], "name": "电脑 A", "platform": "Windows"}
    first = owner.post("/api/agent/register", json=payload)
    assert first.status_code == 200
    assert first.json()["sync_output"] is False
    assert owner.post("/api/agent/register", json=payload).status_code == 401
    assert len(owner.get("/api/snapshot").json()["devices"]) == 1

    expired = owner.post("/api/pairing", headers=csrf).json()
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute("UPDATE pairing SET expires_at=?", (time.time() - 1,))
    assert owner.post("/api/agent/register", json={**payload, "code": expired["code"]}).status_code == 401


def test_same_task_source_id_is_isolated_by_agent_token(hub):
    _, owner, csrf = hub
    device_a = pair_device(owner, csrf, name="电脑 A")
    device_b = pair_device(owner, csrf, name="电脑 B")
    assert device_a["token"] != device_b["token"]
    assert send_heartbeat(owner, device_a, [make_task(title="A 的任务")]).status_code == 200
    assert send_heartbeat(owner, device_b, [make_task(title="B 的任务", status="waiting")]).status_code == 200

    snapshot = owner.get("/api/snapshot").json()
    assert len(snapshot["tasks"]) == 2
    assert len({task["id"] for task in snapshot["tasks"]}) == 2
    by_device = {task["device_id"]: task for task in snapshot["tasks"]}
    assert by_device[device_a["device_id"]]["title"] == "A 的任务"
    assert by_device[device_b["device_id"]]["title"] == "B 的任务"

    # Even an extra claimed device identifier cannot redirect an authenticated heartbeat.
    forged = owner.post(
        "/api/agent/heartbeat",
        headers={"Authorization": "Bearer " + device_a["token"]},
        json={"device_id": device_b["device_id"], "tasks": [], "sources": SOURCES},
    )
    assert forged.status_code in {200, 422}
    remaining = owner.get("/api/snapshot").json()["tasks"]
    assert next(task for task in remaining if task["device_id"] == device_b["device_id"])["title"] == "B 的任务"
    assert all(device_a["token"] not in json.dumps(item) and device_b["token"] not in json.dumps(item) for item in snapshot["devices"])


def test_disabling_output_clears_stored_payload_and_rejects_late_output(hub):
    app, owner, csrf = hub
    device = pair_device(owner, csrf)
    secret = "private-output-marker-not-a-credential"
    assert owner.patch("/api/preferences", headers=csrf, json={"sync_output": True}).status_code == 200
    assert send_heartbeat(owner, device, [make_task(output=secret, preview=secret)]).status_code == 200
    assert owner.get("/api/snapshot").json()["tasks"][0]["output"] == secret

    assert owner.patch("/api/preferences", headers=csrf, json={"sync_output": False}).status_code == 200
    response = owner.get("/api/snapshot")
    assert secret not in response.text
    assert response.json()["tasks"][0]["output"] == ""
    with app.state.store.lock:
        persisted = app.state.store.db.execute("SELECT payload FROM tasks").fetchall()
    assert all(secret not in row["payload"] for row in persisted)
    assert all(json.loads(row["payload"])["output"] == "" for row in persisted)

    # A remote collector may have started a collection before the preference changed.
    late = send_heartbeat(owner, device, [make_task(output=secret, preview=secret)])
    assert late.status_code == 200 and late.json()["sync_output"] is False
    assert secret not in owner.get("/api/snapshot").text
    with app.state.store.lock:
        assert secret not in app.state.store.db.execute("SELECT payload FROM tasks").fetchone()["payload"]


def test_deleting_device_revokes_agent_and_removes_only_its_tasks(hub):
    _, owner, csrf = hub
    device_a = pair_device(owner, csrf, name="待移除电脑")
    device_b = pair_device(owner, csrf, name="保留电脑")
    assert send_heartbeat(owner, device_a, [make_task()]).status_code == 200
    assert send_heartbeat(owner, device_b, [make_task()]).status_code == 200
    assert owner.delete(f"/api/devices/{device_a['device_id']}", headers=csrf).status_code == 200
    assert send_heartbeat(owner, device_a, [make_task()]).status_code == 401
    snapshot = owner.get("/api/snapshot").json()
    assert [device["id"] for device in snapshot["devices"]] == [device_b["device_id"]]
    assert {task["device_id"] for task in snapshot["tasks"]} == {device_b["device_id"]}
    assert send_heartbeat(owner, device_b, [make_task()]).status_code == 200


def test_offline_device_has_unknown_status_and_restores_on_heartbeat(hub):
    app, owner, csrf = hub
    device = pair_device(owner, csrf)
    assert send_heartbeat(owner, device, [make_task(status="running")]).status_code == 200
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time() - ONLINE_SECONDS - 1, device["device_id"]))
    snapshot = owner.get("/api/snapshot").json()
    assert snapshot["devices"][0]["online"] is False
    assert snapshot["tasks"][0]["status"] == "unknown"
    assert snapshot["tasks"][0]["last_known_status"] == "running"
    assert snapshot["tasks"][0]["stale"] is True
    assert send_heartbeat(owner, device, [make_task(status="completed")]).status_code == 200
    refreshed = owner.get("/api/snapshot").json()
    assert refreshed["devices"][0]["online"] is True
    assert refreshed["tasks"][0]["status"] == "completed"
    assert refreshed["tasks"][0]["stale"] is False


def test_unauthenticated_api_does_not_expose_or_modify_account_data(hub):
    app, owner, csrf = hub
    device = pair_device(owner, csrf)
    marker = "private-task-title"
    assert send_heartbeat(owner, device, [make_task(title=marker)]).status_code == 200
    anonymous = TestClient(app, client=("127.0.0.1", 12347))
    try:
        for method, path, body in [
            ("GET", "/api/me", None),
            ("GET", "/api/snapshot", None),
            ("GET", "/api/sessions", None),
            ("PATCH", "/api/preferences", {"sync_output": True}),
            ("POST", "/api/pairing", None),
            ("POST", "/api/logout", None),
            ("DELETE", "/api/sessions/does-not-exist", None),
            ("DELETE", f"/api/devices/{device['device_id']}", None),
        ]:
            response = anonymous.request(method, path, json=body)
            assert response.status_code == 401, (path, response.text)
            assert marker not in response.text and device["token"] not in response.text
            assert response.headers.get("cache-control") == "no-store"
        assert anonymous.get("/api/bootstrap").json() == {"needs_setup": False, "google_login": False}
        assert send_heartbeat(anonymous, {"token": "invalid-token"}, [make_task()]).status_code == 401
        assert owner.get("/api/me").json()["preferences"]["sync_output"] is False
        assert len(owner.get("/api/snapshot").json()["devices"]) == 1
    finally:
        anonymous.close()


def test_authenticated_mutations_require_session_csrf_and_same_origin(hub):
    _, owner, csrf = hub
    change = {"sync_output": True}
    for headers in [
        {},
        {"X-CSRF-Token": "wrong-token"},
        {**csrf, "Origin": "https://untrusted.example"},
        {**csrf, "Sec-Fetch-Site": "cross-site"},
    ]:
        response = owner.patch("/api/preferences", headers=headers, json=change)
        assert response.status_code == 403
    assert owner.get("/api/me").json()["preferences"]["sync_output"] is False
    assert owner.patch("/api/preferences", headers={**csrf, "Origin": "http://testserver"}, json=change).status_code == 200


def test_logout_invalidates_copied_cookie(hub):
    app, owner, csrf = hub
    copied_cookie = owner.cookies.get(COOKIE)
    assert owner.post("/api/logout", headers=csrf).status_code == 200
    assert owner.get("/api/me").status_code == 401
    client = TestClient(app, client=("127.0.0.1", 12348))
    try:
        client.cookies.set(COOKIE, copied_cookie)
        assert client.get("/api/me").status_code == 401
    finally:
        client.close()


def test_account_setup_rejects_nonlocal_client_and_existing_account(hub):
    app, owner, _ = hub
    remote = TestClient(app, client=("192.168.1.50", 12349))
    try:
        assert remote.post("/api/setup", json=ACCOUNT).status_code == 403
        assert owner.post("/api/setup", json=ACCOUNT).status_code == 409
        assert owner.get("/api/me").json()["user"]["username"] == ACCOUNT["username"]
    finally:
        remote.close()


def test_public_service_rejects_initial_setup_even_via_loopback_proxy(tmp_path):
    public_url = "https://monitor.example.test"
    app = create_app(tmp_path, collect_local=False, public_url=public_url, allowed_hosts=["monitor.example.test"])
    with TestClient(app, base_url=public_url, client=("127.0.0.1", 12345)) as proxied:
        response = proxied.post("/api/setup", headers={"Origin": public_url}, json=ACCOUNT)
        assert response.status_code == 403
        assert not app.state.store.has_account()
        assert proxied.get("/api/bootstrap").json() == {"needs_setup": True, "google_login": False}
        assert proxied.get("/api/me").status_code == 401
        assert COOKIE not in proxied.cookies


def test_public_https_login_sets_secure_cookie_for_locally_initialized_account(tmp_path):
    public_url = "https://monitor.example.test"
    app = create_app(tmp_path, collect_local=False, public_url=public_url, allowed_hosts=["monitor.example.test"])
    # Account creation is performed on the host before any browser login.
    app.state.store.setup(ACCOUNT["username"], ACCOUNT["password"])
    with TestClient(app, base_url=public_url, client=("127.0.0.1", 12345)) as phone:
        response = phone.post("/api/login", headers={"Origin": public_url}, json=ACCOUNT)
        assert response.status_code == 200
        cookie = SimpleCookie()
        cookie.load(response.headers["set-cookie"])
        assert cookie[COOKIE]["secure"] is True
        assert cookie[COOKIE]["httponly"] is True
        assert cookie[COOKIE]["samesite"].lower() == "strict"
        assert phone.get("/api/me").json()["user"]["username"] == ACCOUNT["username"]


def test_public_origin_works_behind_http_proxy_without_trusting_forwarded_headers(tmp_path):
    public_url = "https://monitor.example.test"
    app = create_app(tmp_path, collect_local=False, public_url=public_url,
                     allowed_hosts=["monitor.example.test", "127.0.0.1"])
    app.state.store.setup(ACCOUNT["username"], ACCOUNT["password"])
    with TestClient(app, base_url="http://127.0.0.1", client=("127.0.0.1", 12345)) as proxy:
        response = proxy.post("/api/login", headers={"Host": "monitor.example.test", "Origin": public_url}, json=ACCOUNT)
        assert response.status_code == 200
        cookie = SimpleCookie()
        cookie.load(response.headers["set-cookie"])
        assert cookie[COOKIE]["secure"] is True
        # The browser sends its Secure cookie to the HTTPS edge; the edge forwards
        # it to the loopback HTTP service. Never weaken the cookie for this test.
        headers = {"Host": "monitor.example.test", "Origin": public_url,
                   "Cookie": f"{COOKIE}={cookie[COOKIE].value}",
                   "X-CSRF-Token": response.json()["csrf_token"]}
        assert proxy.patch("/api/preferences", headers=headers, json={"tool_filter": "codex"}).status_code == 200
        assert proxy.get("/api/me", headers=headers).json()["preferences"]["tool_filter"] == "codex"
        for untrusted in [
            {"Origin": "http://127.0.0.1"},
            {"Origin": "https://untrusted.example", "X-Forwarded-Host": "untrusted.example", "X-Forwarded-Proto": "https"},
        ]:
            assert proxy.patch("/api/preferences", headers={**headers, **untrusted}, json={"tool_filter": "claude"}).status_code == 403
        assert proxy.get("/api/snapshot", headers={**headers, "Host": "untrusted.example"}).status_code == 400
        assert proxy.get("/api/me", headers=headers).json()["preferences"]["tool_filter"] == "codex"
