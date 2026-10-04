"""Synthetic transcripts and files only; no user sessions or credentials."""
import base64
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import secrets
import time
import zipfile

from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from agent_monitor import collector
from agent_monitor.native_access import challenge_for
from agent_monitor.server import COOKIE, create_app
from agent_monitor.task_results import (CHUNK_BYTES, MAX_RESULT_CHARS, FileChunk, FinalUpload, LocalResults, ResultStatus,
    TaskResults, extract_final, referenced_paths, resolve_local_session, sha256, _safe_archive)

ORIGIN = "https://results.example.test"
ACCOUNT = {"username": "synthetic-result-owner", "password": "Synthetic-results-password-123"}


def row(kind, payload, stamp=None):
    return {"type": kind, "payload": payload, "timestamp": stamp or "2026-09-14T08:00:00.000Z"}


def final(text="Final only", phase="final", stamp=None):
    return row("response_item", {"type": "message", "role": "assistant", "phase": phase,
                                "content": [{"type": "output_text", "text": text}]}, stamp)


@pytest.fixture
def logs(tmp_path):
    root = tmp_path / "project"
    root.mkdir()
    codex = tmp_path / "codex"
    claude = tmp_path / "claude"
    path = codex / "sessions" / "test-session.jsonl"
    path.parent.mkdir(parents=True)
    started = datetime.now(timezone.utc) - timedelta(minutes=1)
    ended = datetime.now(timezone.utc)
    start, end = collector._iso(started), collector._iso(ended)
    file = root / "deliverable.txt"
    file.write_text("Synthetic downloadable content", encoding="utf-8")
    os.utime(file, (ended.timestamp(), ended.timestamp()))
    text = "完成了。\n\n[下载配套文件](" + str(file).replace("\\", "/") + ")"
    records = [row("session_meta", {"id": "test-session", "cwd": str(root)}, start),
               row("event_msg", {"type": "task_started"}, start), final(text, stamp=end),
               row("event_msg", {"type": "task_complete", "last_agent_message": text}, end)]
    path.write_bytes(b"".join(json.dumps(value).encode() + b"\n" for value in records))
    return {"root": root, "codex": codex, "claude": claude, "path": path, "file": file, "records": records,
            "text": text, "source_id": "codex:test-session", "local": LocalResults(codex_home=codex, claude_home=claude)}


@pytest.fixture
def hub(tmp_path, logs):
    app = create_app(tmp_path / "state", public_url=ORIGIN, allowed_hosts=["results.example.test"])
    store = app.state.store
    store.setup(**ACCOUNT)
    store.update_preferences({"sync_output": True})
    service = app.state.task_results
    service.local = logs["local"]
    with TestClient(app, base_url=ORIGIN) as browser:
        login = browser.post("/api/login", json=ACCOUNT)
        csrf = {"Origin": ORIGIN, "X-CSRF-Token": login.json()["csrf_token"]}
        with TestClient(app, base_url=ORIGIN, client=("127.0.0.2", 12345)) as native:
            verifier = secrets.token_urlsafe(32)
            start = native.post("/api/native/pairing/start", json={"device_name": "Synthetic", "mode": "full_app", "code_challenge": challenge_for(verifier)}).json()
            path = "/api/native/pairing/" + start["request_id"]
            assert browser.post(path + "/approve", headers=csrf, json={"mode": "full_app", "consent_version": "full_app_v1"}).status_code == 200
            claimed = native.post(path + "/poll", json={"code_verifier": verifier}).json()
            auth = {"Authorization": "Bearer " + claimed["reader_token"]}
            device = store.ensure_local("Synthetic computer", "Windows")
            task = collector.parse_codex_session(logs["path"], include_output=True)
            store.ingest(device, [task], [])
            yield {"app": app, "store": store, "service": service, "browser": browser, "native": native, "auth": auth,
                   "csrf": csrf, "task_id": device + ":" + task["id"], "claimed": claimed}


@pytest.mark.parametrize("phase", ["analysis", "commentary", "", None, "summary"])
def test_nonfinal_messages_never_become_results(phase):
    records = [final("Never expose this", phase), row("event_msg", {"type": "agent_message", "message": "progress"}),
               row("response_item", {"type": "reasoning", "content": "reasoning"}),
               row("response_item", {"type": "function_call_output", "output": "tool output"})]
    assert extract_final(records, "codex") is None


def test_latest_final_survives_next_turn_and_excludes_other_blocks():
    records = [final("old answer"), final("latest answer"), final("progress", "commentary"),
               row("event_msg", {"type": "user_message", "message": "private prompt"})]
    records[1]["payload"]["content"] += [{"type": "reasoning", "text": "hidden"}, {"type": "tool_result", "text": "tool"}]
    result = extract_final(records, "codex")
    assert result["text"] == "latest answer"
    assert "private" not in json.dumps(result)


def test_legacy_explicit_final_and_incomplete_metadata_fail_closed():
    assert extract_final([row("event_msg", {"type": "task_complete", "last_agent_message": "Legacy final"})], "codex")["text"] == "Legacy final"
    bad = final("no timestamp"); bad["timestamp"] = "invalid"
    assert extract_final([bad], "codex") is None


@pytest.mark.parametrize("reason", [None, "tool_use", "max_tokens", "stop_sequence", "end_turn"])
def test_claude_only_end_turn_text_not_thinking_or_tools(reason):
    record = {"type": "assistant", "timestamp": "2026-09-14T08:00:00Z", "message": {"stop_reason": reason,
              "content": [{"type": "thinking", "thinking": "private"}, {"type": "tool_use", "input": "private"}, {"type": "text", "text": "Final answer"}]}}
    result = extract_final([record], "claude")
    assert (result and result["text"]) == ("Final answer" if reason == "end_turn" else None)
    record["isSidechain"] = True
    assert extract_final([record], "claude") is None


def test_long_final_is_explicitly_truncated_and_partial_lines_are_not_consumed():
    record = final("文" * (MAX_RESULT_CHARS + 1))
    result = extract_final([record], "codex")
    assert len(result["text"]) == MAX_RESULT_CHARS and result["truncated"]
    assert collector._json_lines(json.dumps(record).encode()) == []


def test_resolver_metadata_and_deliverable_registration(logs):
    session = resolve_local_session(logs["source_id"], logs["codex"], logs["claude"])
    assert session.path == logs["path"] and session.cwd == logs["root"]
    result = logs["local"].read(logs["source_id"])
    assert result["text"] == logs["text"] and len(result["files"]) == 1
    file = result["files"][0]
    content, metadata = logs["local"].file(logs["source_id"], result["result_id"], file["id"])
    assert content == logs["file"].read_bytes() and metadata["sha256"] == sha256(content)
    assert file["name"] == "deliverable.txt" and len(file["id"]) == 64
    assert resolve_local_session("codex:missing", logs["codex"], logs["claude"]) is None


@pytest.mark.parametrize("source", ["codex:../test-session", "codex:test-session/other", "other:test-session", "codex:", "test-session"])
def test_resolver_rejects_untrusted_source_identifiers(logs, source):
    assert resolve_local_session(source, logs["codex"], logs["claude"]) is None


@pytest.mark.parametrize("metadata", [{"id": "wrong"}, {"parent_thread_id": "parent"}, {"source": {"subagent": {}}}, {"cwd": "relative"}])
def test_resolver_rejects_metadata_mismatch_or_subagent(logs, metadata):
    records = logs["records"]
    records[0]["payload"].update(metadata)
    logs["path"].write_bytes(b"".join(json.dumps(value).encode() + b"\n" for value in records))
    assert logs["local"].read(logs["source_id"]) is None


@pytest.mark.parametrize("value", ["../outside.txt", "state/account.txt", ".env.txt", ".ssh/id_rsa", "secrets/report.txt",
                                     "credentials.csv", "auth.json", "a.pem", "file:///tmp/x.txt", "https://site.test/a.pdf", "//server/share/a.txt"])
def test_reference_paths_deny_traversal_secrets_and_external_targets(logs, value):
    assert referenced_paths("[Download](" + value + ")", logs["root"]) == []


def test_file_changed_since_registration_is_not_downloaded(logs):
    final = logs["local"].read(logs["source_id"])
    metadata = final["files"][0]
    before = logs["file"].stat()
    logs["file"].write_bytes(b"x" * before.st_size)
    os.utime(logs["file"], ns=(before.st_atime_ns, before.st_mtime_ns))
    with pytest.raises(HTTPException) as error:
        logs["local"].file(logs["source_id"], final["result_id"], metadata["id"])
    assert error.value.status_code == 409


def test_old_unmodified_source_file_not_offered_as_generated_attachment(logs):
    os.utime(logs["file"], (1, 1))
    assert logs["local"].read(logs["source_id"])["files"] == []


def test_hardlink_not_exported(logs, tmp_path):
    alias = tmp_path / "hardlink.txt"
    try:
        os.link(logs["file"], alias)
    except OSError:
        pytest.skip("hardlinks unavailable")
    assert logs["local"].read(logs["source_id"])["files"] == []


def test_symlink_not_exported(logs, tmp_path):
    external = tmp_path / "outside.txt"
    external.write_text("Do not export")
    logs["file"].unlink()
    try:
        logs["file"].symlink_to(external)
    except OSError:
        pytest.skip("symlinks unavailable without Windows privilege")
    assert logs["local"].read(logs["source_id"])["files"] == []


def test_windows_reparse_component_rejected_without_link_privilege(logs, monkeypatch):
    from types import SimpleNamespace
    original = Path.lstat
    def lstat(path, *args, **kwargs):
        actual = original(path, *args, **kwargs)
        if path == logs["root"]:
            return SimpleNamespace(st_mode=actual.st_mode, st_file_attributes=0x400)
        return actual
    monkeypatch.setattr(Path, "lstat", lstat)
    assert logs["local"].read(logs["source_id"])["files"] == []
    assert resolve_local_session(logs["source_id"], logs["codex"], logs["claude"]) is None


@pytest.mark.parametrize("suffix", [".apk", ".aab"])
def test_generated_android_package_is_downloadable_without_execution(logs, suffix):
    package = logs["root"] / ("monitor-test" + suffix)
    with zipfile.ZipFile(package, "w") as archive:
        archive.writestr("AndroidManifest.xml", "synthetic package manifest")
        archive.writestr("classes.dex", b"synthetic inert bytes")
    stamp = collector._timestamp(logs["records"][-1]["timestamp"]).timestamp()
    os.utime(package, (stamp, stamp))
    text = "[安装包](" + package.as_posix() + ")"
    records = logs["records"][:2] + [final(text, stamp=logs["records"][-1]["timestamp"])]
    logs["path"].write_bytes(b"".join(json.dumps(value).encode() + b"\n" for value in records))
    result = logs["local"].read(logs["source_id"])
    assert len(result["files"]) == 1 and result["files"][0]["name"].endswith(suffix)
    data, _ = logs["local"].file(logs["source_id"], result["result_id"], result["files"][0]["id"])
    assert data == package.read_bytes()


def test_removed_workspace_keeps_final_text_but_not_attachments(logs):
    logs["file"].unlink()
    logs["root"].rmdir()
    result = logs["local"].read(logs["source_id"])
    assert result["text"] == logs["text"] and result["files"] == []
    assert resolve_local_session(logs["source_id"], logs["codex"], logs["claude"]) is None


def test_verified_final_survives_large_next_turn_but_not_transcript_truncation(logs):
    from agent_monitor.task_results import MAX_RESULT_SCAN_BYTES
    result = logs["local"].read(logs["source_id"])
    with logs["path"].open("ab") as handle:
        handle.write(json.dumps(row("response_item", {"type": "function_call_output", "output": "x" * (MAX_RESULT_SCAN_BYTES + 100)})).encode() + b"\n")
    assert logs["local"].read(logs["source_id"])["result_id"] == result["result_id"]
    logs["path"].write_bytes(json.dumps(logs["records"][0]).encode() + b"\n")
    assert logs["local"].read(logs["source_id"]) is None


@pytest.mark.parametrize("name", ["../secret.txt", ".env", "private/credentials.json", "state/token.txt", "/absolute.txt"])
def test_zip_sensitive_members_rejected(name):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(name, "synthetic")
    assert not _safe_archive(buffer.getvalue(), ".zip")


def test_native_result_and_downloads_are_complete_and_private(hub, logs):
    client, auth, task_id = hub["native"], hub["auth"], hub["task_id"]
    response = client.get("/api/native/tasks/result", params={"task_id": task_id}, headers=auth)
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["available"] and data["text"] == logs["text"] and data["txt_sha256"] == sha256(logs["text"].encode())
    assert "_session" not in response.text and "_paths" not in response.text and "reader_token" not in response.text
    params = {"task_id": task_id, "result_id": data["result_id"]}
    download = client.get("/api/native/tasks/result.txt", params=params, headers=auth)
    assert download.status_code == 200 and download.content == logs["text"].encode("utf-8")
    assert "attachment" in download.headers["content-disposition"] and download.headers["cache-control"] == "no-store"
    file = data["files"][0]
    artifact = client.get("/api/native/tasks/result/file", params={**params, "file_id": file["id"]}, headers=auth)
    assert artifact.content == logs["file"].read_bytes() and artifact.headers["x-content-sha256"] == file["sha256"]
    for field in ("result_id", "file_id"):
        invalid = {**params, "file_id": file["id"], field: "../private"}
        assert client.get("/api/native/tasks/result/file", params=invalid, headers=auth).status_code == 404


@pytest.mark.parametrize("credential", ["none", "cookie", "cookie_bearer", "invalid", "cross_origin", "read_only", "revoked"])
def test_result_authority_checked_before_local_io(hub, monkeypatch, credential):
    client, auth = hub["native"], {}
    if credential == "cookie":
        client = hub["browser"]
    elif credential == "cookie_bearer":
        auth = {"Authorization": "Bearer " + hub["browser"].cookies.get(COOKIE)}
    elif credential == "invalid":
        auth = {"Authorization": "Bearer nrd_invalid"}
    elif credential in {"cross_origin", "read_only", "revoked"}:
        auth = hub["auth"].copy()
        if credential == "cross_origin":
            auth["Origin"] = "https://attacker.test"
        elif credential == "read_only":
            with hub["store"].lock, hub["store"].db:
                hub["store"].db.execute("UPDATE native_readers SET mode='read_only'")
        else:
            with hub["store"].lock, hub["store"].db:
                hub["store"].db.execute("DELETE FROM native_readers")
    monkeypatch.setattr(hub["service"].local, "read", lambda _: pytest.fail("unauthorized filesystem read"))
    for endpoint in ("/api/native/tasks/result", "/api/native/tasks/result.txt", "/api/native/tasks/result/file"):
        response = client.get(endpoint, headers=auth, params={"task_id": hub["task_id"], "result_id": "a" * 64, "file_id": "b" * 64})
        assert response.status_code == (403 if credential in {"cross_origin", "read_only"} else 401), response.text


def test_filesystem_io_does_not_hold_store_lock_and_revoke_during_read_wins(hub, monkeypatch):
    original = hub["service"].local.read
    def read(source):
        assert not hub["store"].lock._is_owned()
        result = original(source)
        with hub["store"].lock, hub["store"].db:
            hub["store"].db.execute("DELETE FROM native_readers")
        return result
    monkeypatch.setattr(hub["service"].local, "read", read)
    response = hub["native"].get("/api/native/tasks/result", params={"task_id": hub["task_id"]}, headers=hub["auth"])
    assert response.status_code == 401 and "完成了" not in response.text


def register_remote(hub, source="codex:remote-session"):
    store = hub["store"]
    paired = store.register(store.create_pairing()["code"], "Remote synthetic", "Linux")
    store.ingest(paired["device_id"], [{"id": source, "tool": "codex", "title": "Synthetic remote", "status": "completed", "updated_at": collector._iso(datetime.now(timezone.utc))}], [])
    return paired, {"Authorization": "Bearer " + paired["token"]}


def upload_payload(content=b"Remote document", source="codex:remote-session"):
    stamp, text = "2026-09-14T08:00:00.000Z", "Remote final answer"
    result_id = sha256(("codex\n" + stamp + "\n" + text).encode())
    return {"source_id": source, "tool": "codex", "result_id": result_id, "text": text, "completed_at": stamp,
            "truncated": False, "files": [{"id": "f" * 64, "name": "remote.txt", "size": len(content), "mime": "text/plain", "sha256": sha256(content)}]}


def test_remote_final_and_files_upload_without_paths_then_download(hub):
    remote, auth = register_remote(hub)
    content = b"Remote document"
    payload = upload_payload(content)
    response = hub["native"].post("/api/agent/results", headers=auth, json=payload)
    assert response.status_code == 200, response.text
    assert response.json()["needed_files"] == [{"id": "f" * 64, "offset": 0}]
    task_id = remote["device_id"] + ":" + payload["source_id"]
    view = hub["native"].get("/api/native/tasks/result", headers=hub["auth"], params={"task_id": task_id}).json()
    assert view["available"] and not view["files"][0]["ready"]
    chunk = {"source_id": payload["source_id"], "result_id": payload["result_id"], "file_id": "f" * 64,
             "offset": 0, "data": base64.b64encode(content).decode()}
    answer = hub["native"].post("/api/agent/results/file", headers=auth, json=chunk)
    assert answer.status_code == 200 and answer.json()["ready"], answer.text
    assert hub["native"].post("/api/agent/results/file", headers=auth, json=chunk).json()["ready"]
    download = hub["native"].get("/api/native/tasks/result/file", headers=hub["auth"], params={"task_id": task_id, "result_id": payload["result_id"], "file_id": "f" * 64})
    assert download.status_code == 200 and download.content == content
    assert hub["native"].post("/api/agent/results", headers=auth, json=payload).json()["needed_files"] == []


def test_remote_cannot_impersonate_other_computer_or_native_user(hub):
    remote, auth = register_remote(hub)
    payload = upload_payload(source="codex:test-session")
    assert hub["native"].post("/api/agent/results", headers=auth, json=payload).status_code == 404
    payload = upload_payload()
    for wrong_auth in ({}, hub["auth"]):
        assert hub["native"].post("/api/agent/results", headers=wrong_auth, json=payload).status_code == 401
    assert hub["native"].get("/api/native/tasks/result", headers=auth, params={"task_id": hub["task_id"]}).status_code == 401


@pytest.mark.parametrize("change", [{"result_id": "a" * 64}, {"files": [{"id": "f" * 64, "name": "../secret.txt", "size": 1, "mime": "text/plain", "sha256": "a" * 64}]}, {"path": "/private"}])
def test_remote_malformed_result_and_path_fields_rejected(hub, change):
    _, auth = register_remote(hub)
    response = hub["native"].post("/api/agent/results", headers=auth, json={**upload_payload(), **change})
    assert response.status_code == 422


def test_output_off_revokes_result_reads_and_uploads(hub):
    _, auth = register_remote(hub)
    hub["store"].update_preferences({"sync_output": False})
    response = hub["native"].get("/api/native/tasks/result", headers=hub["auth"], params={"task_id": hub["task_id"]})
    assert response.status_code == 409 and "同步" in response.json()["detail"]
    assert hub["native"].post("/api/agent/results", headers=auth, json=upload_payload()).status_code == 409


def test_bad_file_checksum_and_cross_file_ids_fail(hub):
    _, auth = register_remote(hub)
    payload = upload_payload(b"abc")
    assert hub["native"].post("/api/agent/results", headers=auth, json=payload).status_code == 200
    chunk = {"source_id": payload["source_id"], "result_id": payload["result_id"], "file_id": "f" * 64, "offset": 0, "data": base64.b64encode(b"bad").decode()}
    assert hub["native"].post("/api/agent/results/file", headers=auth, json=chunk).status_code == 409
    chunk["file_id"] = "a" * 64
    assert hub["native"].post("/api/agent/results/file", headers=auth, json=chunk).status_code == 404


def test_bad_partial_upload_resets_so_a_correct_retry_can_finish(hub):
    _, auth = register_remote(hub)
    payload = upload_payload(b"abcdef")
    client = hub["native"]
    assert client.post("/api/agent/results", headers=auth, json=payload).status_code == 200
    chunk = {"source_id": payload["source_id"], "result_id": payload["result_id"], "file_id": "f" * 64,
             "offset": 0, "data": base64.b64encode(b"bad").decode()}
    assert client.post("/api/agent/results/file", headers=auth, json=chunk).status_code == 200
    assert client.post("/api/agent/results/file", headers=auth, json={**chunk, "offset": 3}).status_code == 409
    assert client.post("/api/agent/results", headers=auth, json=payload).json()["needed_files"][0]["offset"] == 0
    correct = {**chunk, "data": base64.b64encode(b"abcdef").decode()}
    assert client.post("/api/agent/results/file", headers=auth, json=correct).json()["ready"] is True


def test_cache_eviction_retains_text_and_user_view_requests_file_again(hub, monkeypatch):
    from agent_monitor import task_results
    monkeypatch.setattr(task_results, "MAX_CACHE_BYTES", 40)
    remotes = []
    for index in range(2):
        source = "codex:cache-" + str(index)
        remote, auth = register_remote(hub, source)
        content = b"x" * 20
        payload = upload_payload(content, source)
        assert hub["native"].post("/api/agent/results", headers=auth, json=payload).status_code == 200
        chunk = {"source_id": source, "result_id": payload["result_id"], "file_id": "f" * 64, "offset": 0, "data": base64.b64encode(content).decode()}
        assert hub["native"].post("/api/agent/results/file", headers=auth, json=chunk).status_code == 200
        remotes.append((remote, auth, payload))
    # Force the next bounded chunk to evict an older file, keeping its answer.
    monkeypatch.setattr(task_results, "MAX_CACHE_BYTES", 30)
    remote, auth = register_remote(hub, "codex:cache-third")
    payload = upload_payload(b"z" * 10, "codex:cache-third")
    assert hub["native"].post("/api/agent/results", headers=auth, json=payload).status_code == 200
    chunk = {"source_id": payload["source_id"], "result_id": payload["result_id"], "file_id": "f" * 64, "offset": 0, "data": base64.b64encode(b"z" * 10).decode()}
    assert hub["native"].post("/api/agent/results/file", headers=auth, json=chunk).status_code == 200
    first, first_auth, first_payload = remotes[0]
    status = {"results": [{"source_id": first_payload["source_id"], "result_id": first_payload["result_id"]}]}
    assert hub["native"].post("/api/agent/results/status", headers=first_auth, json=status).json()["needed_results"] == []
    result = hub["native"].get("/api/native/tasks/result", headers=hub["auth"], params={"task_id": first["device_id"] + ":" + first_payload["source_id"]}).json()
    assert result["text"] == first_payload["text"] and not result["files"][0]["ready"]
    assert hub["native"].post("/api/agent/results/status", headers=first_auth, json=status).json()["needed_results"] == [first_payload["source_id"]]


def test_unupgraded_remote_does_not_offer_fake_downloads(hub):
    remote, _ = register_remote(hub)
    response = hub["native"].get("/api/native/tasks/result", headers=hub["auth"], params={"task_id": remote["device_id"] + ":codex:remote-session"})
    assert response.status_code == 200 and not response.json()["available"] and response.json()["files"] == []


def test_connector_uploads_once_and_resumes_large_files_without_arbitrary_paths(hub, logs):
    from agent_monitor.remote_agent import AgentConfig, ResultSync
    remote, _ = register_remote(hub, logs["source_id"])
    content = b"safe-generated-data" * ((CHUNK_BYTES * 5) // 19 + 1)
    logs["file"].write_bytes(content)
    end = collector._timestamp(logs["records"][-1]["timestamp"]).timestamp()
    os.utime(logs["file"], (end, end))
    task = collector.parse_codex_session(logs["path"], include_output=True)
    sent = []
    class Client:
        def post(self, path, payload, token=None):
            sent.append((path, payload))
            with hub["store"].lock, hub["store"].db:
                if path == "/api/agent/results/status":
                    return hub["service"].status(remote["device_id"], ResultStatus.model_validate(payload))
                if path == "/api/agent/results":
                    return hub["service"].upload(remote["device_id"], FinalUpload.model_validate(payload))
                return hub["service"].upload_chunk(remote["device_id"], FileChunk.model_validate(payload))
    sync = ResultSync(logs["local"])
    config = AgentConfig(ORIGIN, remote["device_id"], remote["token"], sync_output=True)
    snapshot = {"tasks": [task]}
    sync.sync(Client(), config, snapshot)
    assert len([path for path, _ in sent if path.endswith("/file")]) == 4
    assert task["id"] not in sync.done
    sync.sync(Client(), config, snapshot)
    assert sync.done[task["id"]] == task["final_result_id"]
    count = len(sent)
    sync.sync(Client(), config, snapshot)
    assert len(sent) == count + 1 and sent[-1][0] == "/api/agent/results/status"
    for _, payload in sent:
        assert not any(key in payload for key in ("path", "cwd", "_paths", "_session"))
    viewed = hub["service"].result(remote["device_id"] + ":" + task["id"])
    assert viewed["files"][0]["ready"] and viewed["files"][0]["sha256"] == sha256(content)


@pytest.mark.parametrize("file_request", [{"id": "not-an-offered-file", "offset": 0}, {"id": None, "offset": 0}, {"id": "f" * 64, "offset": -1}])
def test_connector_rejects_hub_requesting_non_registered_file(logs, file_request):
    from agent_monitor.remote_agent import AgentConfig, RemoteAgentError, ResultSync
    task = collector.parse_codex_session(logs["path"], include_output=True)
    class Client:
        def post(self, path, payload, token=None):
            if path == "/api/agent/results/status":
                return {"needed_results": [task["id"]]}
            assert path == "/api/agent/results"
            return {"needed_files": [file_request]}
    with pytest.raises(RemoteAgentError):
        ResultSync(logs["local"]).sync(Client(), AgentConfig(ORIGIN, "synthetic", "token", sync_output=True), {"tasks": [task]})
