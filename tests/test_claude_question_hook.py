"""Exercise a real hook subprocess, the authenticated phone APIs, and the original hook stdout."""
import json
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid

import pytest

from agent_monitor import claude_question_hook as hook
from test_task_replies import SESSION, hub


@pytest.fixture
def desktop(hub):
    app, client, auth, old_task, task, _, reader = hub
    app.state.task_replies.controls.catalog_provider = lambda _: None
    store = app.state.store
    # The existing test hub enables its fake local collector after construction.
    with store.db:
        store.db.execute("INSERT INTO claude_question_host VALUES(1,?)", (hook.local_principal(),))
    source = "claude:" + SESSION
    device = old_task.partition(":")[0]
    store.ingest(device, [{**task, "tool": "claude", "id": source, "status": "running"}], [])
    task_id = device + ":" + source
    db_path = store.db.execute("PRAGMA database_list").fetchone()["file"]
    reader_id = client.get("/api/native/account", headers=auth).json()["current_reader_id"]
    event = {"session_id": SESSION, "tool_use_id": "toolu_test", "hook_event_name": "PreToolUse",
             "tool_name": "AskUserQuestion", "tool_input": {"questions": [
                 {"question": "Choose a theme", "header": "Theme", "multiSelect": False,
                  "options": [{"label": "Light", "description": "First"}, {"label": "Dark", "description": "Second"}]}]}}
    return store, client, auth, task_id, Path(db_path), reader_id, event, reader


@pytest.mark.parametrize("decision", ["answer", "deny", "revoke"])
def test_desktop_original_tool_mobile_flow(desktop, decision):
    store, client, auth, task_id, path, reader_id, event, reader = desktop
    other = reader()
    config = StringIO()
    with redirect_stdout(config):
        assert hook.main(["--database", str(path), "--print-config", "--reader-id=" + reader_id]) == 0
    entry = json.loads(config.getvalue())["hooks"]["PreToolUse"][0]
    assert entry["matcher"] == "^AskUserQuestion$"
    command = entry["hooks"][0]
    proc = subprocess.Popen([command["command"], *command["args"]], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, encoding="utf-8", cwd=path.parent)
    try:
        proc.stdin.write(json.dumps(event, indent=2))
        proc.stdin.close()
        proc.stdin = None
        timeout = time.monotonic() + 55
        card = []
        while time.monotonic() < timeout and proc.poll() is None:
            card = client.get("/api/native/tasks/controls", headers=auth, params={"task_id": task_id}).json()["pending"]
            if card:
                break
            time.sleep(.12)
        assert card and proc.poll() is None
        def badge(headers):
            tasks = client.get("/api/native/workbench", headers=headers).json()["tasks"]
            return next(t for t in tasks if t["id"] == task_id).get("pending_interaction_kind")
        assert badge(auth) == "question" and badge(other) is None
        assert not client.get("/api/native/tasks/controls", headers=other, params={"task_id": task_id}).json()["pending"]
        item = card[0]
        assert item["kind"] == "question" and item["turn_id"] == event["tool_use_id"]
        assert [option["label"] for option in item["questions"][0]["options"]] == ["Light", "Dark"]
        payload = {"task_id": task_id, "request_id": item["id"], "revision": item["revision"],
                   "decision_id": str(uuid.uuid4()), "decision": "answer" if decision == "revoke" else decision,
                   "answers": [] if decision == "deny" else [{"question_id": "q0", "choices": ["o1"]}]}
        assert client.post("/api/native/tasks/controls/decision", headers=other, json=payload).status_code == 404
        if decision == "revoke":
            with store.lock, store.db:
                store.db.execute("DELETE FROM native_readers WHERE id=?", (reader_id,))
        else:
            assert client.post("/api/native/tasks/controls/decision", headers=auth, json=payload).json()["state"] == "submitted"
        out, err = proc.communicate(timeout=25)
        assert proc.returncode == 0 and not err
        if decision == "answer":
            assert json.loads(out) == {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "allow",
                "updatedInput": {**event["tool_input"], "answers": {"Choose a theme": "Dark"}}}}
            assert client.post("/api/native/tasks/controls/decision", headers=auth, json=payload).json()["state"] == "forwarded"
        else:
            assert out == ""
        assert not store.db.execute("SELECT 1 FROM task_replies").fetchone()
        retry = StringIO()
        hook.handle(store, reader_id, event, retry)
        assert retry.getvalue() == ""
        assert store.db.execute("SELECT payload,decision FROM task_interactions WHERE id=?", (item["id"],)).fetchone()[:] == ("", "")
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=5)


def test_hook_timeout_declines_unsupported_sources_without_writing_a_decision(desktop):
    store, client, auth, task_id, path, reader_id, event, reader = desktop
    out = StringIO()
    for change in ({"tool_name": "Bash"}, {"agent_id": "subagent"}, {"hook_event_name": "PermissionRequest"}):
        hook.handle(store, reader_id, {**event, **change}, out, wait=.1, interval=.01)
    reader("read_only")
    restricted_id = store.db.execute("SELECT id FROM native_readers WHERE mode='read_only'").fetchone()["id"]
    hook.handle(store, restricted_id, event, out, wait=.1, interval=.01)
    assert store.db.execute("SELECT COUNT(*) FROM task_interactions").fetchone()[0] == 0
    hook.handle(store, reader_id, event, out, wait=.1, interval=.01)
    assert out.getvalue() == ""
    assert store.db.execute("SELECT state,delivered FROM task_interactions").fetchone()[:] == ("expired", 0)


def test_hook_config_preserves_virtualenv_python_path(desktop, tmp_path, monkeypatch, capsys):
    *_, path, reader_id, event, reader = desktop
    venv_python = tmp_path / "venv" / "bin" / "python"
    venv_python.parent.mkdir(parents=True)
    try:
        venv_python.symlink_to(Path(sys.executable))
    except OSError:
        # Windows runners can lack symlink privileges; reproduce POSIX realpath
        # for just this virtualenv executable while leaving other paths intact.
        real_resolve = Path.resolve
        interpreter = Path(sys.executable)
        monkeypatch.setattr(Path, "resolve", lambda path, *args, **kwargs:
                            interpreter if path == venv_python else real_resolve(path, *args, **kwargs))
    monkeypatch.setattr(sys, "executable", str(venv_python))
    assert hook.main(["--database", str(path), "--print-config", "--reader-id=" + reader_id]) == 0
    config = json.loads(capsys.readouterr().out)
    assert config["hooks"]["PreToolUse"][0]["hooks"][0]["command"] == venv_python.as_posix()


@pytest.mark.parametrize("unavailable", ["foreign_host", "archived", "offline", "managed_turn"])
def test_ineligible_desktop_hook_does_not_publish(desktop, unavailable):
    store, _, _, task_id, path, reader_id, event, _ = desktop
    device, _, source = task_id.partition(":")
    with store.db:
        if unavailable == "foreign_host":
            store.db.execute("UPDATE claude_question_host SET owner='other-host-same-os-user'")
        elif unavailable == "archived":
            store.archive_task(task_id, True)
        elif unavailable == "offline":
            store.db.execute("UPDATE devices SET last_seen=0 WHERE id=?", (device,))
        else:
            store.db.execute("""INSERT INTO task_replies(id,reader_id,task_id,device_id,source_id,tool,prompt,prompt_hash,
                state,queued_at,expires_at) VALUES(?,?,?,?,?,'claude','','','running',?,?)""",
                (str(uuid.uuid4()), reader_id, task_id, device, source, time.time(), time.time() + 60))
    out = StringIO()
    hook.handle(store, reader_id, event, out, wait=.05, interval=.01)
    assert not out.getvalue()
    assert not store.db.execute("SELECT 1 FROM task_interactions").fetchone()


def test_closed_original_stdout_never_replays_phone_answer(desktop):
    store, client, auth, task_id, path, reader_id, event, _ = desktop
    closed = threading.Event()
    class Gone:
        def write(self, line):
            raise BrokenPipeError()

    def waiting_hook():
        peer = hook.Store(path, existing_only=True)
        try:
            hook.handle(peer, reader_id, event, Gone(), wait=20, interval=.025)
        except BrokenPipeError:
            closed.set()
        finally:
            peer.close()

    worker = threading.Thread(target=waiting_hook)
    worker.start()
    try:
        deadline = time.monotonic() + 10
        card = []
        while not card and time.monotonic() < deadline:
            card = client.get("/api/native/tasks/controls", headers=auth, params={"task_id": task_id}).json()["pending"]
            if not card:
                time.sleep(.03)
        assert card
        item, = card
        submit = {"task_id": task_id, "request_id": item["id"], "revision": item["revision"],
                  "decision_id": str(uuid.uuid4()), "decision": "answer", "answers": [{"question_id": "q0", "choices": ["o1"]}]}
        assert client.post("/api/native/tasks/controls/decision", headers=auth, json=submit).json()["state"] == "submitted"
    finally:
        worker.join(22)
    assert closed.is_set()
    assert store.db.execute("SELECT state,delivered,payload,decision FROM task_interactions").fetchone()[:] == ("cancelled", 1, "", "")
    retry = StringIO()
    hook.handle(store, reader_id, event, retry)
    assert not retry.getvalue()
