from fastapi.testclient import TestClient

from agent_monitor.server import create_app


def test_whitespace_does_not_create_an_unusable_account(tmp_path):
    with TestClient(create_app(tmp_path), client=("127.0.0.1", 2000)) as client:
        response = client.post("/api/setup", json={"username": " a ", "password": "A-long-test-password"})
        assert response.status_code == 422
        assert client.get("/api/bootstrap").json()["needs_setup"] is True


def test_validation_never_echoes_submitted_password(tmp_path):
    with TestClient(create_app(tmp_path), client=("127.0.0.1", 2000)) as client:
        password = "private-short"
        response = client.post("/api/login", json={"username": "x", "password": password})
        assert response.status_code == 422
        assert password not in response.text
        assert "input" not in response.text
