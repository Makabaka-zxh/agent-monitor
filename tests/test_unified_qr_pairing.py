"""Tests for unified one-scan QR pairing (desktop login + collector binding)."""
from __future__ import annotations

import json
import secrets

import pytest
from fastapi.testclient import TestClient

from agent_monitor.native_access import challenge_for
from agent_monitor.server import COOKIE, create_app

ORIGIN = "https://monitor.example.test"
ACCOUNT = {"username": "owner", "password": "correct-horse-battery-staple"}


def _pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    return verifier, challenge_for(verifier)


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=["monitor.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN) as browser, TestClient(app, base_url=ORIGIN) as phone, TestClient(app, base_url=ORIGIN) as desktop:
        login = browser.post("/api/login", json={**ACCOUNT, "device_name": "测试浏览器"})
        assert login.status_code == 200
        csrf = {"X-CSRF-Token": login.json()["csrf_token"]}
        phone_verifier, phone_challenge = _pkce_pair()
        start = phone.post(
            "/api/native/pairing/start",
            json={"device_name": "测试手机", "mode": "full_app", "code_challenge": phone_challenge},
        ).json()
        approve = browser.post(
            f"/api/native/pairing/{start['request_id']}/approve",
            headers=csrf,
            json={"mode": "full_app", "consent_version": "full_app_v1"},
        )
        assert approve.status_code == 200
        claimed = phone.post(
            f"/api/native/pairing/{start['request_id']}/poll",
            json={"code_verifier": phone_verifier},
        ).json()
        phone_headers = {"Authorization": f"Bearer {claimed['reader_token']}"}
        yield app, browser, phone, phone_headers, desktop, csrf


def test_unified_one_scan_issues_reader_token_and_collector_code(hub):
    app, _, phone, phone_headers, desktop, _ = hub
    verifier, challenge = _pkce_pair()
    started = desktop.post(
        "/api/agent/pairing/start",
        json={
            "name": "办公电脑",
            "platform": "Windows",
            "include_collector": True,
            "issue_collector_code": True,
            "include_reader": True,
            "reader_name": "Monitor · Windows (办公电脑)",
            "code_challenge": challenge,
        },
    )
    assert started.status_code == 200
    payload = started.json()
    assert payload["include_collector"] is True
    assert payload["include_reader"] is True
    assert payload["verification_url"] == f"{ORIGIN}/#/pairing-confirm/{payload['request_id']}"

    details = phone.get(f"/api/native/computers/pairing/{payload['request_id']}", headers=phone_headers)
    assert details.status_code == 200
    detail_json = details.json()
    assert detail_json["include_collector"] is True
    assert detail_json["include_reader"] is True
    assert detail_json["reader_name"] == "Monitor · Windows (办公电脑)"
    assert "poll_secret" not in details.text
    assert "challenge_hash" not in details.text

    # Pending poll checks verifier before disclosing status
    pending = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"], "code_verifier": verifier},
    )
    assert pending.status_code == 200 and pending.json()["status"] == "pending"

    # Phone approves single QR code
    approved = phone.post(f"/api/native/computers/pairing/{payload['request_id']}/approve", headers=phone_headers)
    assert approved.status_code == 200

    # Wrong PKCE verifier fails closed with 401 and does NOT consume the pairing
    wrong_verifier, _ = _pkce_pair()
    bad_poll = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"], "code_verifier": wrong_verifier},
    )
    assert bad_poll.status_code == 401

    # Missing PKCE verifier fails closed with 401
    missing_poll = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"]},
    )
    assert missing_poll.status_code == 401

    # Legitimate desktop client polls with matching PKCE verifier
    claimed = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"], "code_verifier": verifier},
    )
    assert claimed.status_code == 200
    result = claimed.json()
    assert result["status"] == "approved"
    assert result["reader_token"].startswith("nrd_")
    assert result["reader_mode"] == "full_app"
    assert len(result["collector_code"]) == 12

    # Replay poll returns 410
    replay = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"], "code_verifier": verifier},
    )
    assert replay.status_code == 410

    # No plaintext secrets stored in qr_pairing row
    saved = dict(app.state.store.db.execute("SELECT * FROM qr_pairing WHERE id=?", (payload["request_id"],)).fetchone())
    serialized = json.dumps(saved)
    assert payload["poll_secret"] not in serialized
    assert verifier not in serialized
    assert result["reader_token"] not in serialized
    assert result["collector_code"] not in serialized

    # Desktop reader_token can immediately read /api/native/workbench & /api/native/account
    desktop_headers = {"Authorization": f"Bearer {result['reader_token']}"}
    wb = desktop.get("/api/native/workbench", headers=desktop_headers)
    assert wb.status_code == 200
    assert wb.json()["mode"] == "full_app"

    # Collector one-time code can register the computer collector cleanly
    reg = desktop.post(
        "/api/agent/register",
        json={"code": result["collector_code"], "name": "办公电脑", "platform": "Windows"},
    )
    assert reg.status_code == 200
    assert app.state.store.agent_device(reg.json()["token"]) == reg.json()["device_id"]
    # Second use of collector_code is rejected
    assert desktop.post(
        "/api/agent/register",
        json={"code": result["collector_code"], "name": "办公电脑", "platform": "Windows"},
    ).status_code == 401


def test_reader_only_qr_pairing_and_parent_revocation_cascade(hub):
    _, browser, phone, phone_headers, desktop, csrf = hub
    verifier, challenge = _pkce_pair()
    started = desktop.post(
        "/api/agent/pairing/start",
        json={
            "name": "本机桌面端",
            "platform": "macOS",
            "include_collector": False,
            "include_reader": True,
            "reader_name": "Monitor · macOS",
            "code_challenge": challenge,
        },
    )
    assert started.status_code == 200
    payload = started.json()
    assert payload["include_collector"] is False and payload["include_reader"] is True

    assert phone.post(f"/api/native/computers/pairing/{payload['request_id']}/approve", headers=phone_headers).status_code == 200
    claimed = desktop.post(
        f"/api/agent/pairing/{payload['request_id']}/poll",
        json={"poll_secret": payload["poll_secret"], "code_verifier": verifier},
    ).json()
    assert "collector_code" not in claimed and "token" not in claimed
    desktop_headers = {"Authorization": f"Bearer {claimed['reader_token']}"}
    assert desktop.get("/api/native/workbench", headers=desktop_headers).status_code == 200

    # Logging out the root browser session cascades revocation to the QR-minted desktop reader
    assert browser.post("/api/logout", headers=csrf).status_code == 200
    assert desktop.get("/api/native/workbench", headers=desktop_headers).status_code == 401


def test_collector_code_only_qr_and_validation_guards(hub):
    _, browser, _, _, desktop, csrf = hub
    # Invalid combinations rejected with 422
    assert desktop.post(
        "/api/agent/pairing/start",
        json={"name": "电脑", "platform": "Windows", "include_collector": False, "include_reader": False},
    ).status_code == 422
    assert desktop.post(
        "/api/agent/pairing/start",
        json={"name": "电脑", "platform": "Windows", "include_collector": True, "include_reader": True},
    ).status_code == 422
    _, challenge = _pkce_pair()
    assert desktop.post(
        "/api/agent/pairing/start",
        json={"name": "电脑", "platform": "Windows", "include_collector": True, "include_reader": False, "code_challenge": challenge},
    ).status_code == 422

    # Collector-only QR with issue_collector_code=True approved from web browser
    started = desktop.post(
        "/api/agent/pairing/start",
        json={"name": "副机", "platform": "macOS", "include_collector": True, "issue_collector_code": True, "include_reader": False},
    ).json()
    assert browser.post(f"/api/pairing/requests/{started['request_id']}/approve", headers=csrf).status_code == 200
    claimed = desktop.post(
        f"/api/agent/pairing/{started['request_id']}/poll",
        json={"poll_secret": started["poll_secret"]},
    ).json()
    assert "reader_token" not in claimed
    assert len(claimed["collector_code"]) == 12
