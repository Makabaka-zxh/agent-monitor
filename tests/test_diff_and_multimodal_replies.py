from __future__ import annotations

import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import subprocess
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from agent_monitor.local_executor import _stage_reply_images
from agent_monitor.native_access import challenge_for
from agent_monitor.server import create_app
from agent_monitor.session_controls import ImageAttachment, reply_fingerprint
from agent_monitor.task_results import LocalSession
from agent_monitor.workspace_diff import collect_workspace_diff, parse_unified_diff

TINY_PNG = base64.b64encode(
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
    b"\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
).decode("ascii")

TINY_JPEG = base64.b64encode(
    b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9"
).decode("ascii")


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def test_parse_unified_diff_filters_secrets_and_counts_lines():
    raw = "\n".join([
        "diff --git a/.env b/.env",
        "new file mode 100644",
        "--- /dev/null",
        "+++ b/.env",
        "@@ -0,0 +1,1 @@",
        "+API_KEY=super_secret_do_not_expose",
        "diff --git a/src/app.py b/src/app.py",
        "index 1111111..2222222 100644",
        "--- a/src/app.py",
        "+++ b/src/app.py",
        "@@ -10,3 +10,4 @@ def greet():",
        "     x = 1",
        "-    return x",
        "+    y = x + 2",
        "+    return y",
    ])
    parsed = parse_unified_diff(raw, scope="working_tree")
    assert parsed is not None
    assert parsed["scope"] == "working_tree"
    assert parsed["files_changed"] == 1
    assert parsed["additions"] == 2
    assert parsed["deletions"] == 1
    assert [f["path"] for f in parsed["files"]] == ["src/app.py"]
    lines = parsed["files"][0]["hunks"][0]["lines"]
    assert [ln["kind"] for ln in lines] == ["ctx", "del", "add", "add"]
    assert "super_secret_do_not_expose" not in json.dumps(parsed)


def test_collect_workspace_diff_working_tree_and_last_commit(tmp_path: Path):
    repo = tmp_path / "demo_repo"
    repo.mkdir()
    _git(repo, "init")
    _git(repo, "config", "user.email", "dev@example.invalid")
    _git(repo, "config", "user.name", "Dev")

    main_py = repo / "main.py"
    main_py.write_text("def hello():\n    return 'old'\n", encoding="utf-8")
    _git(repo, "add", "main.py")
    _git(repo, "commit", "-m", "init")

    # 1. Working tree modification + untracked file + ignored secret .env
    started_iso = datetime.now(timezone.utc).isoformat()
    main_py.write_text("def hello():\n    return 'new'\n", encoding="utf-8")
    (repo / "helper.py").write_text("VALUE = 42\n", encoding="utf-8")
    (repo / ".env").write_text("SECRET=123\n", encoding="utf-8")

    wt_diff = collect_workspace_diff(repo)
    assert wt_diff is not None
    assert wt_diff["scope"] == "working_tree"
    paths = {f["path"] for f in wt_diff["files"]}
    assert "main.py" in paths
    assert "helper.py" in paths
    assert ".env" not in paths

    # 2. Commit changes -> working tree clean -> falls back to last_commit within turn window
    (repo / ".env").unlink()
    _git(repo, "add", "main.py", "helper.py")
    _git(repo, "commit", "-m", "feat: update hello")
    completed_iso = datetime.now(timezone.utc).isoformat()

    commit_diff = collect_workspace_diff(repo, started_at=started_iso, completed_at=completed_iso)
    assert commit_diff is not None
    assert commit_diff["scope"] == "last_commit"
    assert {f["path"] for f in commit_diff["files"]} == {"main.py", "helper.py"}


def test_image_attachment_validation_and_fingerprint_compatibility():
    base_fp = reply_fingerprint("fix layout", None)
    assert reply_fingerprint("fix layout", None, []) == base_fp

    img = ImageAttachment(mime="image/png", data=TINY_PNG)
    img_fp = reply_fingerprint("fix layout", None, [img])
    assert img_fp != base_fp

    # Reject non-image / SVG / mismatched header bytes
    bad_svg = base64.b64encode(b"<svg onload=alert(1)></svg>").decode("ascii")
    with pytest.raises(ValueError):
        ImageAttachment(mime="image/png", data=bad_svg)
    with pytest.raises(ValueError):
        ImageAttachment(mime="image/png", data=TINY_JPEG)


def test_reply_endpoint_accepts_images_with_empty_text_and_wipes_db_blob(tmp_path: Path):
    origin = "https://multimodal.test"
    app = create_app(tmp_path / "hub", collect_local=False, public_url=origin, allowed_hosts=["multimodal.test"])
    with TestClient(app, base_url=origin) as client:
        store = app.state.store
        account = {"username": "tester_user", "password": "CorrectHorseBatteryStaple1!"}
        store.setup(**account)
        login = client.post("/api/login", json=account).json()
        csrf = {"Origin": origin, "X-CSRF-Token": login["csrf_token"]}
        verifier = secrets.token_urlsafe(32)
        start_pair = client.post(
            "/api/native/pairing/start",
            json={"device_name": "PhoneReader", "mode": "full_app", "code_challenge": challenge_for(verifier)},
        ).json()
        pair_path = "/api/native/pairing/" + start_pair["request_id"]
        assert client.post(pair_path + "/approve", headers=csrf, json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
        native_token = client.post(pair_path + "/poll", json={"code_verifier": verifier}).json()["reader_token"]

        pair = store.create_pairing()
        reg = store.register(pair["code"], "DevPC", "Windows")
        dev_id, agent_token = reg["device_id"], reg["token"]

        hb = client.post(
            "/api/agent/heartbeat",
            headers={"Authorization": f"Bearer {agent_token}"},
            json={
                "tasks": [{
                    "id": "codex:11111111-2222-3333-4444-555555555555",
                    "tool": "codex",
                    "title": "UI Polish",
                    "status": "completed",
                    "updated_at": datetime.now(timezone.utc).isoformat(),
                }],
                "sources": [{"tool": "codex", "available": True, "mode": "local_log", "detail": "ok"}],
            },
        )
        assert hb.status_code == 200

        # Agent reports codex reply capability
        poll0 = client.post(
            "/api/agent/replies/poll",
            headers={"Authorization": f"Bearer {agent_token}"},
            json={"supported_tools": ["codex"]},
        )
        assert poll0.status_code == 200

        req_uuid = str(uuid.uuid4())
        task_id = f"{dev_id}:codex:11111111-2222-3333-4444-555555555555"
        send = client.post(
            "/api/native/tasks/reply",
            headers={"Authorization": f"Bearer {native_token}"},
            json={
                "task_id": task_id,
                "request_id": req_uuid,
                "text": "",
                "images": [{"mime": "image/png", "data": TINY_PNG}],
            },
        )
        assert send.status_code == 200
        assert send.json()["state"] == "queued"

        # Agent claims the reply -> receives validated images, and DB wipes transient image bytes
        poll1 = client.post(
            "/api/agent/replies/poll",
            headers={"Authorization": f"Bearer {agent_token}"},
            json={"supported_tools": ["codex"]},
        )
        assert poll1.status_code == 200
        cmd = poll1.json()["command"]
        assert cmd is not None
        assert cmd["id"] == req_uuid
        assert cmd["text"] == "请结合附带的图片查看并处理。"
        assert len(cmd["images"]) == 1
        assert cmd["images"][0]["mime"] == "image/png"

        row = store.db.execute("SELECT prompt, images FROM task_replies WHERE id=?", (req_uuid,)).fetchone()
        assert row["prompt"] == ""
        assert row["images"] == ""


def test_stage_reply_images_uses_git_ignored_dir_and_cleans_up(tmp_path: Path):
    ws = tmp_path / "ws"
    (ws / ".git").mkdir(parents=True)
    img = ImageAttachment(mime="image/png", data=TINY_PNG)
    stage_dir, paths = _stage_reply_images(ws, "cmd-123", [img])
    assert stage_dir is not None
    assert stage_dir.is_relative_to(ws / ".git")
    assert len(paths) == 1
    assert paths[0].is_file()
    assert paths[0].read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
