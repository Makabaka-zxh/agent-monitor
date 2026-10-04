"""Account appearance, reversible archives, and explicit QR pairing consent."""
import base64
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
import time
from urllib.parse import quote, urlsplit

from fastapi.testclient import TestClient
from PIL import Image, PngImagePlugin
import pytest

from agent_monitor.profile import AVATAR_BYTES
from agent_monitor.server import create_app
from agent_monitor.store import PairingRequestError, Store

ORIGIN = "https://monitor.example.test"
ACCOUNT = {"username": "profile-owner", "password": "Only-a-test-password-123"}


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, collect_local=False, public_url=ORIGIN,
                     allowed_hosts=["monitor.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 20000)) as owner:
        login = owner.post("/api/login", json={**ACCOUNT, "device_name": "测试手机"})
        assert login.status_code == 200
        yield app, owner, {"X-CSRF-Token": login.json()["csrf_token"], "Origin": ORIGIN}


def avatar(fmt="PNG", size=(24, 24), **save_args):
    result = BytesIO()
    Image.new("RGB", size, "#638090").save(result, format=fmt, **save_args)
    mime = {"JPEG": "jpeg", "PNG": "png", "WEBP": "webp"}[fmt]
    return "data:image/" + mime + ";base64," + base64.b64encode(result.getvalue()).decode()


def start_pair(owner, name="新电脑"):
    response = owner.post("/api/agent/pairing/start", json={"name": name, "platform": "Windows"})
    assert response.status_code == 200, response.text
    return response.json()


def poll_pair(owner, pairing, **changes):
    return owner.post(f"/api/agent/pairing/{pairing['request_id']}/poll",
                      json={"poll_secret": pairing["poll_secret"], **changes})


def task(source_id="same-source", **changes):
    return {"id": source_id, "tool": "codex", "title": "隔离测试任务", "status": "running",
            "updated_at": "2026-01-01T00:00:00+00:00", **changes}


def register(owner, csrf):
    code = owner.post("/api/pairing", headers=csrf).json()["code"]
    response = owner.post("/api/agent/register", json={"code": code, "name": "测试电脑", "platform": "Windows"})
    assert response.status_code == 200
    return response.json()


def ingest(owner, device, tasks):
    response = owner.post("/api/agent/heartbeat", headers={"Authorization": "Bearer " + device["token"]},
                          json={"tasks": tasks, "sources": []})
    assert response.status_code == 200, response.text


def test_profile_persists_across_sessions_and_store_restart(hub, tmp_path):
    app, owner, csrf = hub
    original = owner.get("/api/me").json()["user"]
    assert original == {"username": ACCOUNT["username"], "display_name": ACCOUNT["username"], "avatar": ""}
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text("Comment", "PRIVATE-METADATA-MUST-DISAPPEAR")
    response = owner.patch("/api/profile", headers=csrf,
                           json={"display_name": "  小牧  ", "avatar": avatar(pnginfo=metadata)})
    assert response.status_code == 200, response.text
    user = response.json()["user"]
    assert user["username"] == ACCOUNT["username"] and user["display_name"] == "小牧"
    decoded = base64.b64decode(user["avatar"].split(",", 1)[1])
    assert len(decoded) <= AVATAR_BYTES and b"PRIVATE-METADATA" not in decoded
    with Image.open(BytesIO(decoded)) as picture:
        assert picture.format == "JPEG"
        picture.verify()
    tablet = TestClient(app, base_url=ORIGIN)
    try:
        login = tablet.post("/api/login", json=ACCOUNT)
        assert login.status_code == 200 and login.json()["user"] == user
        assert tablet.get("/api/me").json()["user"] == user
    finally:
        tablet.close()
    reopened = Store(tmp_path / "monitor.sqlite3")
    try:
        assert reopened.profile() == user
        assert reopened.verify_password(**ACCOUNT)
    finally:
        reopened.close()
    assert owner.patch("/api/profile", headers=csrf, json={"avatar": ""}).json()["user"] == {**user, "avatar": ""}
    assert owner.get("/api/me").json()["preferences"] == {"tool_filter": "all", "sync_output": False}


@pytest.mark.parametrize("fmt", ["JPEG", "PNG", "WEBP"])
def test_avatar_supported_formats_are_decoded_and_normalized(hub, fmt):
    _, owner, csrf = hub
    response = owner.patch("/api/profile", headers=csrf, json={"avatar": avatar(fmt)})
    assert response.status_code == 200
    assert response.json()["user"]["avatar"].startswith("data:image/jpeg;base64,")


@pytest.mark.parametrize("changes", [
    {"display_name": " "}, {"display_name": "x" * 41}, {"display_name": "x\nname"},
    {"display_name": "x\u202ename"}, {"username": "someone-else"}, {"avatar": None},
    {"avatar": "https://example.com/picture.png"},
    {"avatar": "data:image/svg+xml;base64,PHN2Zy8+"},
    {"avatar": "data:image/png;base64,aGVsbG8="},
    {"avatar": "data:image/png;base64,%%%"},
    {"avatar": "data:image/png;base64," + base64.b64encode(b"x" * (AVATAR_BYTES + 1)).decode()},
])
def test_invalid_profile_payloads_do_not_change_account(hub, changes):
    _, owner, csrf = hub
    original = owner.get("/api/me").json()["user"]
    response = owner.patch("/api/profile", headers=csrf, json=changes)
    assert response.status_code == 422
    assert len(response.text) < 300
    assert owner.get("/api/me").json()["user"] == original


def test_avatar_rejects_mime_mismatch_dimensions_and_animation(hub):
    _, owner, csrf = hub
    for value in [avatar("JPEG").replace("image/jpeg", "image/png"), avatar(size=(1025, 8))]:
        assert owner.patch("/api/profile", headers=csrf, json={"avatar": value}).status_code == 422
    animated = BytesIO()
    Image.new("RGB", (10, 10), "red").save(animated, format="PNG", save_all=True,
        append_images=[Image.new("RGB", (10, 10), "blue")], duration=100, loop=0)
    value = "data:image/png;base64," + base64.b64encode(animated.getvalue()).decode()
    assert owner.patch("/api/profile", headers=csrf, json={"avatar": value}).status_code == 422


def test_profile_and_archive_require_session_origin_and_csrf(hub):
    app, owner, csrf = hub
    device = register(owner, csrf)
    ingest(owner, device, [task()])
    task_id = owner.get("/api/snapshot").json()["tasks"][0]["id"]
    endpoint = "/api/tasks/" + quote(task_id, safe="") + "/archive"
    anonymous = TestClient(app, base_url=ORIGIN)
    try:
        for url, payload in [("/api/profile", {"display_name": "new"}), (endpoint, {"archived": True})]:
            assert anonymous.patch(url, json=payload).status_code == 401
            assert owner.patch(url, json=payload).status_code == 403
            assert owner.patch(url, headers={**csrf, "Origin": "https://attacker.test"}, json=payload).status_code == 403
    finally:
        anonymous.close()
    assert owner.get("/api/snapshot").json()["tasks"][0]["archived"] is False


def test_archive_is_per_device_survives_collection_and_restores(hub, tmp_path):
    app, owner, csrf = hub
    first, second = register(owner, csrf), register(owner, csrf)
    source_id = "source:with/slash %?雪"
    for device in (first, second):
        ingest(owner, device, [task(source_id)])
    task_id = first["device_id"] + ":" + source_id
    endpoint = "/api/tasks/" + quote(task_id, safe="") + "/archive"
    assert owner.patch(endpoint, headers=csrf, json={"archived": True}).json() == {"id": task_id, "archived": True}
    ingest(owner, first, [task(source_id, title="新的采集内容", archived=False)])
    tasks = owner.get("/api/snapshot").json()["tasks"]
    assert next(t for t in tasks if t["device_id"] == first["device_id"])["archived"] is True
    assert next(t for t in tasks if t["device_id"] == second["device_id"])["archived"] is False
    ingest(owner, first, [])
    ingest(owner, first, [task(source_id)])
    reopened = Store(tmp_path / "monitor.sqlite3")
    try:
        assert next(t for t in reopened.snapshot()["tasks"] if t["id"] == task_id)["archived"] is True
    finally:
        reopened.close()
    assert owner.patch(endpoint, headers=csrf, json={"archived": False}).json()["archived"] is False
    assert owner.patch(endpoint, headers=csrf, json={"archived": "true"}).status_code == 422
    assert owner.patch("/api/tasks/missing:task/archive", headers=csrf, json={"archived": True}).status_code == 404
    assert owner.delete("/api/devices/" + first["device_id"], headers=csrf).status_code == 200
    assert app.state.store.db.execute("SELECT COUNT(*) FROM task_archive WHERE device_id=?", (first["device_id"],)).fetchone()[0] == 0


def test_qr_requires_explicit_phone_consent_and_independent_poll_secret(hub):
    app, owner, csrf = hub
    pairing = start_pair(owner)
    request_id = pairing["request_id"]
    assert len(pairing["poll_secret"]) >= 40
    assert pairing["verification_url"] == ORIGIN + "/#/pairing-confirm/" + request_id
    assert pairing["poll_secret"] not in pairing["verification_url"]
    assert "token" not in pairing
    path = "/api/pairing/requests/" + request_id
    anonymous = TestClient(app, base_url=ORIGIN)
    try:
        assert anonymous.get(path).status_code == 401
        assert anonymous.post(path + "/approve", headers=csrf).status_code == 401
    finally:
        anonymous.close()
    details = owner.get(path).json()
    assert details["name"] == "新电脑" and details["platform"] == "Windows" and details["status"] == "pending"
    assert not ({"poll_secret", "poll_hash", "token", "token_hash"} & details.keys())
    assert poll_pair(owner, pairing).json()["status"] == "pending"
    assert owner.get("/api/snapshot").json()["devices"] == []
    assert owner.post(path + "/approve").status_code == 403
    assert owner.post(path + "/approve", headers={**csrf, "Origin": "https://attacker.test"}).status_code == 403
    assert owner.post(path + "/approve", headers=csrf).json()["status"] == "approved"
    assert owner.post(path + "/approve", headers=csrf).status_code == 409
    assert owner.get("/api/snapshot").json()["devices"] == []
    assert poll_pair(owner, pairing, poll_secret="wrong-secret-" * 4).status_code == 401
    claimed = poll_pair(owner, pairing)
    assert claimed.status_code == 200 and claimed.json()["status"] == "approved"
    device = claimed.json()
    assert device["sync_output"] is False
    assert poll_pair(owner, pairing).status_code == 410
    assert owner.get(path).json()["status"] == "consumed"
    assert app.state.store.agent_device(device["token"]) == device["device_id"]
    saved = dict(app.state.store.db.execute("SELECT * FROM qr_pairing WHERE id=?", (request_id,)).fetchone())
    assert pairing["poll_secret"] not in json.dumps(saved) and device["token"] not in json.dumps(saved)
    ingest(owner, device, [task()])
    assert len(owner.get("/api/snapshot").json()["devices"]) == 1


def test_qr_expiry_rejection_and_cross_request_secret(hub):
    app, owner, csrf = hub
    first, second = start_pair(owner, "电脑 A"), start_pair(owner, "电脑 B")
    assert poll_pair(owner, first, poll_secret=second["poll_secret"]).status_code == 401
    denied_path = "/api/pairing/requests/" + first["request_id"]
    assert owner.post(denied_path + "/reject", headers=csrf).json()["status"] == "denied"
    assert poll_pair(owner, first).status_code == 403
    assert owner.post(denied_path + "/approve", headers=csrf).status_code == 409
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute("UPDATE qr_pairing SET expires_at=? WHERE id=?", (time.time() - 1, second["request_id"]))
    expired_path = "/api/pairing/requests/" + second["request_id"]
    assert owner.get(expired_path).status_code == 410
    assert owner.post(expired_path + "/approve", headers=csrf).status_code == 410
    assert poll_pair(owner, second).status_code == 410
    assert owner.get("/api/snapshot").json()["devices"] == []


def test_qr_single_claim_is_atomic(hub, tmp_path):
    app, owner, csrf = hub
    pairing = start_pair(owner)
    owner.post("/api/pairing/requests/" + pairing["request_id"] + "/approve", headers=csrf)
    def claim(store):
        try:
            return store.poll_qr_pairing(pairing["request_id"], pairing["poll_secret"])["status"]
        except PairingRequestError as error:
            return error.status
    second_connection = Store(tmp_path / "monitor.sqlite3")
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(claim, (app.state.store, second_connection)))
    finally:
        second_connection.close()
    assert sorted(results, key=str) == [410, "approved"]
    assert len(owner.get("/api/snapshot").json()["devices"]) == 1


def test_qr_start_and_poll_are_rate_limited(hub):
    _, owner, _ = hub
    pairing = start_pair(owner)
    for _ in range(4):
        start_pair(owner)
    assert owner.post("/api/agent/pairing/start", json={"name": "too-many", "platform": "Windows"}).status_code == 429
    for _ in range(30):
        assert poll_pair(owner, pairing).status_code == 200
    response = poll_pair(owner, pairing)
    assert response.status_code == 429 and "Retry-After" in response.headers


def test_qr_needs_https_configuration(tmp_path):
    app = create_app(tmp_path)
    app.state.store.setup(**ACCOUNT)
    with TestClient(app) as client:
        assert client.post("/api/agent/pairing/start", json={"name": "电脑", "platform": "Windows"}).status_code == 503


def test_schema_upgrade_preserves_existing_login_preferences_and_tasks(tmp_path):
    path = tmp_path / "old-monitor.sqlite3"
    original = Store(path)
    original.setup(**ACCOUNT)
    original.update_preferences({"tool_filter": "claude", "sync_output": True})
    device = original.register(original.create_pairing()["code"], "原有电脑", "Windows")
    original.ingest(device["device_id"], [task(output="existing test output")], [])
    token, _ = original.new_session("原有手机")
    with original.lock, original.db:
        for table in ("profile", "task_archive", "qr_pairing"):
            original.db.execute("DROP TABLE " + table)
    original.close()
    migrated = Store(path)
    try:
        assert migrated.verify_password(**ACCOUNT)
        assert migrated.session(token)
        assert migrated.profile()["display_name"] == ACCOUNT["username"]
        assert migrated.preferences() == {"tool_filter": "claude", "sync_output": True}
        assert migrated.agent_device(device["token"]) == device["device_id"]
        item = migrated.snapshot()["tasks"][0]
        assert item["archived"] is False and item["output"] == "existing test output"
    finally:
        migrated.close()
