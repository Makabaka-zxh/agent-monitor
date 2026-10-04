"""Google linking/login boundaries; synthetic accounts and no provider network."""
import base64
import hashlib
from http.cookies import SimpleCookie
from io import BytesIO
import json
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock
from urllib.parse import parse_qs, urlsplit

from fastapi.testclient import TestClient
from PIL import Image
import pytest
import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from google.auth import crypt, jwt
from google.auth.exceptions import GoogleAuthError

from agent_monitor import google_login as google
from agent_monitor.server import create_app

ORIGIN = "https://monitor.example.test"
ACCOUNT = {"username": "owner@example.test", "password": "Synthetic-test-password-123"}
CONFIG = {
    "client_id": "123456-test.apps.googleusercontent.com",
    "client_secret": "synthetic-client-secret-never-real",
    "redirect_uri": ORIGIN + "/api/auth/google/callback",
}
IDENTITY = {"sub": "123456789012345678901", "email": ACCOUNT["username"],
            "email_verified": True, "name": "Test Owner"}


def write_config(path, value=None):
    (path / "google-oauth.json").write_text(json.dumps(CONFIG if value is None else value), encoding="utf-8")


@pytest.fixture
def hub(tmp_path):
    write_config(tmp_path)
    app = create_app(tmp_path, collect_local=False, public_url=ORIGIN,
                     allowed_hosts=["monitor.example.test"])
    app.state.store.setup(**ACCOUNT)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 22000)) as client:
        response = client.post("/api/login", json=ACCOUNT, headers={"Origin": ORIGIN})
        assert response.status_code == 200
        yield app, client, {"Origin": ORIGIN, "X-CSRF-Token": response.json()["csrf_token"]}, tmp_path


def begin(client, mode="login", headers=None, **fields):
    response = client.post("/api/auth/google/start", json={"mode": mode, **fields},
                           headers=headers or {"Origin": ORIGIN})
    assert response.status_code == 200, response.text
    query = {key: values[0] for key, values in parse_qs(urlsplit(response.json()["url"]).query).items()}
    return response, query


def finish(client, query, **changes):
    return client.get("/api/auth/google/callback", params={"state": query["state"],
                      "code": "synthetic-authorization-code", **changes}, follow_redirects=False)


def provider(monkeypatch, claims=None):
    exchange = Mock(return_value=dict(IDENTITY if claims is None else claims))
    avatar = Mock(return_value=None)
    monkeypatch.setattr(google, "exchange_identity", exchange)
    monkeypatch.setattr(google, "google_avatar", avatar)
    return exchange, avatar


def linked(store, subject=IDENTITY["sub"]):
    with store.lock, store.db:
        store.db.execute("INSERT INTO google_identity VALUES (1,?,?)", (subject, IDENTITY["email"]))


@pytest.mark.parametrize("value", [[], None, {"web": []}, {"web": None}, {},
                                        {"client_id": 12, "client_secret": "x"},
                                        {**CONFIG, "client_id": "attacker.example"}])
def test_invalid_config_is_disabled_without_exceptions(tmp_path, value):
    (tmp_path / "google-oauth.json").write_text(json.dumps(value), encoding="utf-8")
    assert google.load_config(tmp_path, ORIGIN) is None


def test_config_requires_https_and_valid_file_but_accepts_google_download_shape(tmp_path):
    assert google.load_config(tmp_path, ORIGIN) is None
    write_config(tmp_path, {"web": CONFIG})
    assert google.load_config(tmp_path, ORIGIN + "/") == CONFIG
    assert google.load_config(tmp_path, "http://localhost:8765") is None
    (tmp_path / "google-oauth.json").write_text("{truncated", encoding="utf-8")
    assert google.load_config(tmp_path, ORIGIN) is None


def test_disabled_configuration_does_not_offer_or_start_login(hub):
    _, client, _, path = hub
    (path / "google-oauth.json").unlink()
    assert client.get("/api/bootstrap").json()["google_login"] is False
    response = client.post("/api/auth/google/start", json={"mode": "login"})
    assert response.status_code == 503
    assert CONFIG["client_secret"] not in response.text


def test_start_link_requires_session_csrf_and_same_origin(hub):
    _, client, csrf, _ = hub
    assert client.post("/api/auth/google/start", json={"mode": "link"}).status_code == 403
    assert client.post("/api/auth/google/start", json={"mode": "link"},
                       headers={**csrf, "Origin": "https://attacker.example"}).status_code == 403
    assert client.post("/api/auth/google/start", json={"mode": "login"},
                       headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
    assert client.post("/api/auth/google/start", json={"mode": "login"},
                       headers={"Origin": "https://attacker.example"}).status_code == 403
    begin(client, "link", csrf)
    client.cookies.clear()
    assert client.post("/api/auth/google/start", json={"mode": "link"}, headers=csrf).status_code == 401


def test_flow_cookie_pkce_and_provider_url_do_not_expose_secrets(hub, monkeypatch):
    app, client, csrf, _ = hub
    exchange, _ = provider(monkeypatch)
    response, query = begin(client, "link", csrf)
    assert urlsplit(response.json()["url"]).netloc == "accounts.google.com"
    assert query["scope"] == "openid email profile"
    assert query["code_challenge_method"] == "S256"
    assert query["redirect_uri"] == CONFIG["redirect_uri"]
    assert all(len(query[key]) >= 32 for key in ("state", "nonce", "code_challenge"))
    assert "client_secret" not in query and "code_verifier" not in query
    assert CONFIG["client_secret"] not in response.text
    cookie = SimpleCookie(response.headers["set-cookie"])[google.FLOW_COOKIE]
    assert cookie["httponly"] and cookie["secure"]
    assert cookie["samesite"].lower() == "lax"
    assert cookie["path"] == "/api/auth/google"
    # A cross-site top-level callback omits the Strict App cookie in browsers.
    # The already-authorized flow must carry the still-live server session.
    flow_secret = client.cookies.get(google.FLOW_COOKIE)
    client.cookies.clear()
    client.cookies.set(google.FLOW_COOKIE, flow_secret, path="/api/auth/google")
    result = finish(client, query)
    assert result.status_code == 303
    code, verifier, nonce, config = exchange.call_args.args
    assert code == "synthetic-authorization-code" and nonce == query["nonce"]
    assert config == CONFIG
    expected = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    assert expected == query["code_challenge"]
    assert app.state.store.db.execute("SELECT subject FROM google_identity").fetchone()[0] == IDENTITY["sub"]
    app_cookie = SimpleCookie()
    for header in result.headers.get_list("set-cookie"):
        app_cookie.load(header)
    assert app_cookie[google.APP_COOKIE]["samesite"].lower() == "strict"
    assert app_cookie[google.APP_COOKIE]["httponly"] and app_cookie[google.APP_COOKIE]["secure"]
    assert result.headers["location"] == "/#/account"
    assert result.headers["cache-control"] == "no-store"
    assert result.headers["referrer-policy"] == "no-referrer"


@pytest.mark.parametrize("failure", ["state", "browser", "expired", "missing_browser"])
def test_state_browser_binding_and_expiry_reject_before_exchange(hub, monkeypatch, failure):
    _, client, _, _ = hub
    exchange, _ = provider(monkeypatch)
    _, query = begin(client)
    if failure == "state":
        query["state"] = "different-state"
    elif failure in {"browser", "missing_browser"}:
        client.cookies.clear()
        if failure == "browser":
            client.cookies.set(google.FLOW_COOKIE, "different-browser-secret", path="/api/auth/google")
    else:
        now = time.time()
        monkeypatch.setattr(google.time, "time", lambda: now + google.FLOW_SECONDS + 1)
    response = finish(client, query)
    assert response.status_code == 400
    assert "synthetic-authorization-code" not in response.text
    exchange.assert_not_called()


def test_callback_cannot_be_replayed_even_after_exchange_failure(hub, monkeypatch):
    _, client, _, _ = hub
    exchange, _ = provider(monkeypatch)
    exchange.side_effect = ValueError("secret access_token and private authorization code")
    _, query = begin(client)
    flow_secret = client.cookies.get(google.FLOW_COOKIE)
    first = finish(client, query)
    assert first.status_code == 400
    assert "secret access_token" not in first.text
    client.cookies.set(google.FLOW_COOKIE, flow_secret, path="/api/auth/google")
    assert finish(client, query).status_code == 400
    assert exchange.call_count == 1


def test_matching_email_never_claims_unlinked_account(hub, monkeypatch):
    app, client, _, _ = hub
    exchange, avatar = provider(monkeypatch)
    client.cookies.clear()
    _, query = begin(client)
    response = finish(client, query)
    assert response.status_code == 403
    assert app.state.store.db.execute("SELECT COUNT(*) FROM google_identity").fetchone()[0] == 0
    assert google.APP_COOKIE not in response.cookies
    avatar.assert_not_called()
    assert exchange.call_count == 1


def test_link_session_revocation_between_start_and_callback_is_respected(hub, monkeypatch):
    app, client, csrf, _ = hub
    provider(monkeypatch)
    _, query = begin(client, "link", csrf)
    app.state.store.revoke_session(app.state.store.session(client.cookies.get(google.APP_COOKIE))["id"])
    response = finish(client, query)
    assert response.status_code == 401
    assert app.state.store.db.execute("SELECT COUNT(*) FROM google_identity").fetchone()[0] == 0


@pytest.mark.parametrize("mode,expected", [("login", 403), ("link", 409)])
def test_different_subject_cannot_replace_or_login_by_same_email(hub, monkeypatch, mode, expected):
    app, client, csrf, _ = hub
    linked(app.state.store, "999999999999999999999")
    _, avatar = provider(monkeypatch)
    _, query = begin(client, mode, csrf)
    response = finish(client, query)
    assert response.status_code == expected
    assert app.state.store.db.execute("SELECT subject FROM google_identity").fetchone()[0] == "999999999999999999999"
    avatar.assert_not_called()


def test_linked_identity_logs_into_same_account_and_syncs_profile(hub, monkeypatch):
    app, client, _, _ = hub
    linked(app.state.store)
    provider(monkeypatch)
    app.state.store.update_preferences({"tool_filter": "codex"})
    client.cookies.clear()
    _, query = begin(client)
    response = finish(client, query)
    assert response.status_code == 303
    me = client.get("/api/me").json()
    assert me["user"]["username"] == ACCOUNT["username"]
    assert me["user"]["display_name"] == IDENTITY["name"]
    assert me["preferences"]["tool_filter"] == "codex"
    assert "sub" not in me["user"] and "email" not in me["user"]


def test_provider_error_is_fixed_text_and_not_echoed(hub, monkeypatch):
    _, client, _, _ = hub
    exchange, _ = provider(monkeypatch)
    _, query = begin(client)
    response = finish(client, query, error='<script>private-provider-error</script>')
    assert response.status_code == 400
    assert "private-provider-error" not in response.text and "<script>" not in response.text
    exchange.assert_not_called()


@pytest.mark.parametrize("path", [
    "https://attacker.example/", "//attacker.example/", "/#/account", "/api/snapshot",
    "/#/native-connect/" + "a" * 31, "/#/native-connect/" + "a" * 33,
    "/#/native-connect/" + "a" * 32 + "?next=evil",
    "/#/native-connect/" + "a" * 32 + "\n",
    "/#/native-connect/" + "a" * 31 + "'", "/#/native-connect/" + "a" * 31 + "\\",
    "/%23/native-connect/" + "a" * 32,
])
def test_google_rejects_untrusted_native_return_paths(hub, path):
    _, client, _, _ = hub
    response = client.post("/api/auth/google/start", json={"mode": "login", "return_path": path})
    assert response.status_code == 422
    assert path not in response.text
    assert google.FLOW_COOKIE not in response.cookies


def test_google_login_returns_to_validated_native_consent_without_auto_approval(hub, monkeypatch):
    app, client, _, _ = hub
    linked(app.state.store)
    provider(monkeypatch)
    verifier = "a" * 43
    from agent_monitor.native_access import challenge_for
    pair = client.post("/api/native/pairing/start", json={
        "device_name": "测试 Android", "code_challenge": challenge_for(verifier),
    }).json()
    path = "/#/native-connect/" + pair["request_id"]
    client.cookies.clear()
    _, query = begin(client, return_path=path)
    assert path not in json.dumps(query)  # Kept in server flow, not forwarded to Google.
    response = finish(client, query, return_path="https://attacker.example/")
    assert response.status_code == 303 and response.headers["location"] == path
    assert client.get("/api/native/pairing/" + pair["request_id"]).json()["status"] == "pending"
    assert client.post("/api/native/pairing/" + pair["request_id"] + "/poll",
                       json={"code_verifier": verifier}).json() == {"status": "pending"}


@pytest.mark.parametrize("failure", ["cancel", "exchange"])
def test_google_native_error_keeps_only_validated_return_path(hub, monkeypatch, failure):
    _, client, _, _ = hub
    exchange, _ = provider(monkeypatch)
    path = "/#/native-connect/" + "a" * 32
    _, query = begin(client, return_path=path)
    if failure == "exchange":
        exchange.side_effect = ValueError("private-callback-data")
        response = finish(client, query)
    else:
        response = finish(client, query, error="private-callback-data")
    assert response.status_code == 400
    assert 'href="' + path + '"' in response.text
    assert "private-callback-data" not in response.text


def test_client_configuration_change_invalidates_pending_flow(hub, monkeypatch):
    _, client, _, path = hub
    exchange, _ = provider(monkeypatch)
    _, query = begin(client)
    write_config(path, {**CONFIG, "client_id": "changed.apps.googleusercontent.com"})
    assert finish(client, query).status_code == 400
    exchange.assert_not_called()


def mock_exchange_network(monkeypatch, claims=None):
    session = MagicMock()
    session.__enter__.return_value = session
    response = Mock(status_code=200, content=b'{"id_token":"synthetic-signed-jwt"}')
    response.json.return_value = {"id_token": "synthetic-signed-jwt"}
    session.post.return_value = response
    monkeypatch.setattr(google.requests, "Session", Mock(return_value=session))
    session.certificate_transport = Mock()
    monkeypatch.setattr(google, "GoogleRequest", Mock(return_value=session.certificate_transport))
    verifier = Mock(return_value={**IDENTITY, "nonce": "expected-nonce"} if claims is None else claims)
    monkeypatch.setattr(google, "verify_oauth2_token", verifier)
    return session, response, verifier


def test_exchange_passes_expected_audience_to_google_verifier_and_binds_nonce(monkeypatch):
    session, _, verifier = mock_exchange_network(monkeypatch)
    result = google.exchange_identity("test-code", "test-verifier", "expected-nonce", CONFIG)
    assert result["sub"] == IDENTITY["sub"]
    token, transport, audience = verifier.call_args.args
    assert token == "synthetic-signed-jwt" and audience == CONFIG["client_id"]
    assert callable(transport)
    transport(url="https://www.googleapis.com/oauth2/v1/certs", timeout=120)
    assert session.certificate_transport.call_args.kwargs["timeout"] == 10
    kwargs = session.post.call_args.kwargs
    assert session.post.call_args.args == (google.TOKEN_ENDPOINT,)
    assert kwargs["data"]["code_verifier"] == "test-verifier"
    assert kwargs["data"]["redirect_uri"] == CONFIG["redirect_uri"]
    assert kwargs["allow_redirects"] is False and kwargs["timeout"] == 15


@pytest.mark.parametrize("claims", [
    {**IDENTITY, "nonce": "wrong-nonce"}, {**IDENTITY, "nonce": None},
    {**IDENTITY, "nonce": "expected-nonce", "sub": ""},
    {**IDENTITY, "nonce": "expected-nonce", "sub": 123},
    {**IDENTITY, "nonce": "expected-nonce", "email_verified": "true"},
    {**IDENTITY, "nonce": "expected-nonce", "email_verified": False},
])
def test_exchange_rejects_invalid_identity_claims(monkeypatch, claims):
    mock_exchange_network(monkeypatch, claims)
    with pytest.raises(ValueError):
        google.exchange_identity("test-code", "test-verifier", "expected-nonce", CONFIG)


@pytest.mark.parametrize("cause", ["signature", "audience", "issuer", "expired"])
def test_google_verifier_failure_is_never_accepted(monkeypatch, cause):
    _, _, verifier = mock_exchange_network(monkeypatch)
    verifier.side_effect = ValueError("untrusted " + cause)
    with pytest.raises(ValueError):
        google.exchange_identity("test-code", "test-verifier", "expected-nonce", CONFIG)


@pytest.fixture(scope="module")
def signing_material():
    # Generated only for these tests; never used by production or a real account.
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Agent Monitor synthetic test")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(1)
            .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=1))
            .sign(key, hashes.SHA256()))
    private_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption())
    return crypt.RSASigner.from_string(private_pem, key_id="synthetic-key"), cert.public_bytes(serialization.Encoding.PEM).decode()


@pytest.mark.parametrize("variant", ["valid", "audience", "issuer", "expired", "signature", "nonce"])
def test_real_google_verifier_with_local_signed_tokens(monkeypatch, signing_material, variant):
    signer, certificate = signing_material
    now = int(time.time())
    claims = {**IDENTITY, "nonce": "expected-nonce", "aud": CONFIG["client_id"],
              "iss": "https://accounts.google.com", "iat": now - 10, "exp": now + 300}
    if variant == "audience":
        claims["aud"] = "another-client.apps.googleusercontent.com"
    elif variant == "issuer":
        claims["iss"] = "https://attacker.example"
    elif variant == "expired":
        claims.update(iat=now - 600, exp=now - 300)
    elif variant == "nonce":
        claims["nonce"] = "another-browser-flow"
    token = jwt.encode(signer, claims).decode()
    if variant == "signature":
        header, payload, signature = token.split(".")
        signature = ("A" if signature[0] != "A" else "B") + signature[1:]
        token = ".".join([header, payload, signature])
    session = MagicMock()
    session.__enter__.return_value = session
    response = Mock(status_code=200, content=b"synthetic-token-response")
    response.json.return_value = {"id_token": token}
    session.post.return_value = response
    monkeypatch.setattr(google.requests, "Session", Mock(return_value=session))
    transport = Mock(return_value=SimpleNamespace(status=200,
                     data=json.dumps({"synthetic-key": certificate}).encode()))
    monkeypatch.setattr(google, "GoogleRequest", Mock(return_value=transport))
    # Keep the actual google-auth verify_oauth2_token implementation here.
    if variant == "valid":
        result = google.exchange_identity("local-code", "local-verifier", "expected-nonce", CONFIG)
        assert result["sub"] == IDENTITY["sub"]
    else:
        with pytest.raises((ValueError, GoogleAuthError)):
            google.exchange_identity("local-code", "local-verifier", "expected-nonce", CONFIG)
    assert transport.call_count == 1
    assert transport.call_args.kwargs["timeout"] == 10


@pytest.mark.parametrize("url", [
    "http://lh3.googleusercontent.com/image", "https://googleusercontent.com/image",
    "https://notgoogleusercontent.com/image", "https://lh3.googleusercontent.com.evil.test/image",
    "https://127.0.0.1/image", "https://user:password@lh3.googleusercontent.com/image",
    "https://lh3.googleusercontent.com:444/image", "file:///private/image.png",
])
def test_avatar_untrusted_hosts_never_issue_requests(monkeypatch, url):
    request = Mock(side_effect=AssertionError("untrusted avatar fetch"))
    monkeypatch.setattr(google.requests, "get", request)
    assert google.google_avatar(url) is None
    request.assert_not_called()


def test_avatar_google_host_is_bounded_normalized_and_never_redirected(monkeypatch):
    picture = BytesIO()
    Image.new("RGB", (16, 16), "blue").save(picture, format="PNG")
    response = MagicMock(status_code=200, headers={"Content-Type": "image/png"})
    response.__enter__.return_value = response
    response.iter_content.return_value = [picture.getvalue()]
    request = Mock(return_value=response)
    monkeypatch.setattr(google.requests, "get", request)
    result = google.google_avatar("https://lh3.googleusercontent.com/synthetic-photo")
    assert result.startswith("data:image/jpeg;base64,")
    assert request.call_args.kwargs == {"timeout": 10, "stream": True, "allow_redirects": False}
    response.status_code = 302
    assert google.google_avatar("https://lh3.googleusercontent.com/synthetic-photo") is None
    response.status_code = 200
    response.iter_content.return_value = [b"x" * (256 * 1024 + 1)]
    assert google.google_avatar("https://lh3.googleusercontent.com/synthetic-photo") is None
