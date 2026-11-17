"""Anonymous synthetic runtime failures; no provider/client invocation."""
import json
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from agent_monitor import claude_hook, collector
from agent_monitor.error_analysis import ErrorAnalysis
from agent_monitor.failure_reasons import failure_code_for_codex, failure_code_for_claude, runtime_analysis
from agent_monitor.server import Task
from agent_monitor.store import Store

NOW = datetime(2026, 11, 1, 1, 0, tzinfo=timezone.utc)


@pytest.mark.parametrize("info,code", [
    ("UsageLimitExceeded", "quota_exhausted"), ("usageLimitExceeded", "quota_exhausted"),
    ("usage_limit_exceeded", "quota_exhausted"), ("contextWindowExceeded", "context_limit"),
    ("Unauthorized", "authentication_failed"), ("sandboxError", "permission_denied"),
    ("BadRequest", "invalid_request"), ("internalServerError", "service_unavailable"),
    ({"httpConnectionFailed": {"httpStatusCode": 429}}, "rate_limit"),
    ({"httpConnectionFailed": {"httpStatusCode": 401}}, "authentication_failed"),
    ({"httpConnectionFailed": {"httpStatusCode": 403}}, "permission_denied"),
    ({"httpConnectionFailed": {"httpStatusCode": 503}}, "service_unavailable"),
    ({"httpConnectionFailed": {"httpStatusCode": "429"}}, "network_error"),
    ("responseStreamDisconnected", "network_error"),
    ("Other", ""), ({"unexpected": "quota_exhausted"}, ""),
    ({"usageLimitExceeded": {}, "other": {}}, ""),
])
def test_exact_structured_codex_codes_only(info, code):
    error = {"codexErrorInfo": info, "message": "synthetic confidential error text"}
    assert failure_code_for_codex(error) == code
    assert failure_code_for_codex({"error": error}) == code
    assert "confidential" not in json.dumps(runtime_analysis(code))


@pytest.mark.parametrize("error", ["UsageLimitExceeded", None, [],
    {"message": "UsageLimitExceeded"}, {"error": "insufficient_quota"},
    {"codexErrorInfo": "UsageLimitExceeded", "codex_error_info": "Unauthorized"},
    {"codexErrorInfo": "UsageLimitExceeded", "codex_error_info": "unknown"}])
def test_no_free_text_inference_or_conflict_elision(error):
    assert failure_code_for_codex(error) == ""


@pytest.mark.parametrize("name,code", [
    ("rate_limit", "rate_limit"), ("overloaded", "service_unavailable"),
    ("authentication_failed", "authentication_failed"), ("oauth_org_not_allowed", "account_restricted"),
    ("account_on_hold", "account_restricted"), ("billing_error", "billing_error"),
    ("invalid_request", "invalid_request"), ("model_not_found", "model_unavailable"),
    ("server_error", "service_unavailable"), ("max_output_tokens", "output_limit"),
    ("cloud_credential_error", "authentication_failed"), ("unknown", ""),
    ("quota is exhausted", ""), ([], ""),
])
def test_claude_explicit_type_and_unknown(name, code):
    assert failure_code_for_claude(name) == code
    if code:
        assert ErrorAnalysis.model_validate(runtime_analysis(code)).source == "runtime_event"


def event(kind, index, **fields):
    return {"type": "event_msg", "timestamp": (NOW + timedelta(seconds=index)).isoformat(),
            "payload": {"type": kind, **fields}}


def parse(tmp_path, records, consent=True):
    path = tmp_path / "synthetic.jsonl"
    rows = [{"type": "session_meta", "payload": {"id": "synthetic", "cwd": "synthetic"}}, *records]
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    return collector.parse_codex_session(path, include_output=consent, now=NOW + timedelta(seconds=20))


def test_no_final_answer_required_for_current_error_type(tmp_path):
    records = [event("turn_started", 0, turn_id="one"),
               event("error", 1, turn_id="one", codex_error_info="UsageLimitExceeded", message="private synthetic"),
               event("turn_failed", 2, turn_id="one")]
    task = parse(tmp_path, records)
    assert task["failure_code"] == "quota_exhausted"
    assert task["output"] == "" and task["error_analysis"]["location"] is None
    assert task["error_analysis"]["source"] == "runtime_event"
    assert "private synthetic" not in json.dumps(task)
    hidden = parse(tmp_path, records, consent=False)
    assert hidden["failure_code"] == "" and hidden["error_analysis"] is None


@pytest.mark.parametrize("tail", [
    [event("turn_failed", 2, turn_id="two")],
    [event("turn_failed", 2)],
    [event("turn_complete", 2, turn_id="one")],
    [event("turn_started", 2, turn_id="two"), event("turn_failed", 3, turn_id="two")],
])
def test_old_error_never_carried_to_unbound_or_new_turn(tmp_path, tail):
    task = parse(tmp_path, [event("error", 1, turn_id="one", codex_error_info="UsageLimitExceeded"), *tail])
    assert task["failure_code"] == "" and task["error_analysis"] is None


@pytest.mark.parametrize("ending", ["error", "turn_failed", "turn_complete"])
def test_delayed_previous_turn_error_cannot_end_current_turn(tmp_path, ending):
    task = parse(tmp_path, [event("turn_started", 0, turn_id="new"),
                          event(ending, 1, turn_id="old", status="failed", codex_error_info="UsageLimitExceeded")])
    assert task["status"] == "running"
    assert task["failure_code"] == "" and task["error_analysis"] is None


@pytest.mark.parametrize("diagnostics", [{"codex_error_info": "unknown"}, {"codex_error_info": None},
    {"codex_error_info": "UsageLimitExceeded", "codexErrorInfo": "Unauthorized"}])
def test_explicit_unknown_or_conflicting_error_replaces_old_code(tmp_path, diagnostics):
    task = parse(tmp_path, [event("error", 0, turn_id="one", codex_error_info="UsageLimitExceeded"),
                          event("turn_failed", 1, turn_id="one", **diagnostics)])
    assert task["status"] == "error"
    assert task["failure_code"] == "" and task["error_analysis"] is None


def test_claude_hook_saves_only_fixed_enum_and_recovery_clears(monkeypatch, tmp_path):
    state = tmp_path / "state"
    monkeypatch.setattr(collector, "state_directory", lambda: state)
    monkeypatch.setattr(collector, "utc_now", lambda: NOW)
    monkeypatch.setattr(claude_hook, "utc_now", lambda: NOW)
    assert claude_hook.record_event({"hook_event_name": "StopFailure", "session_id": "synthetic",
        "error": "billing_error", "error_details": "private synthetic detail",
        "last_assistant_message": "private synthetic message"}, state_dir=state, received_ns=1)
    raw = next((state / "claude-hooks").rglob("state-*.json")).read_text(encoding="utf-8")
    assert "private synthetic" not in raw and json.loads(raw)["failure_code"] == "billing_error"
    def snapshot(consent):
        return collector.collect_snapshot(tmp_path / "codex", tmp_path / "claude", include_output=consent)["tasks"][0]
    assert snapshot(True)["error_analysis"]["category"] == "billing"
    assert snapshot(False)["failure_code"] == ""
    assert snapshot(False)["error_analysis"] is None
    assert claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "synthetic"}, state_dir=state, received_ns=2)
    assert snapshot(True)["failure_code"] == "" and snapshot(True)["error_analysis"] is None


def test_storage_rebuilds_fixed_summary_and_purges_on_revocation_and_offline(tmp_path):
    store = Store(tmp_path / "synthetic.sqlite3")
    try:
        device = store.ensure_local("Synthetic", "Test")
        store.update_preferences({"sync_output": True})
        task = {"id": "codex:synthetic", "tool": "codex", "title": "Synthetic", "status": "error",
                "updated_at": NOW.isoformat(), "output": "", "failure_code": "quota_exhausted",
                "error_analysis": {"summary": "untrusted text"}}
        store.ingest(device, [task], [])
        received = store.snapshot()["tasks"][0]
        assert received["error_analysis"] == runtime_analysis("quota_exhausted")
        with store.lock, store.db: store.db.execute("UPDATE devices SET last_seen=0 WHERE id=?", (device,))
        assert store.snapshot()["tasks"][0]["failure_code"] == ""
        store.update_preferences({"sync_output": False})
        store.ingest(device, [task], [])
        assert store.snapshot()["tasks"][0]["failure_code"] == ""
        assert store.snapshot()["tasks"][0]["error_analysis"] is None
        task["failure_code"] = "arbitrary raw message"
        with pytest.raises(ValidationError): Task.model_validate(task)
    finally:
        store.close()


@pytest.mark.parametrize("transcript", [False, True])
def test_delayed_failure_for_old_prompt_does_not_replace_new_prompt(monkeypatch, tmp_path, transcript):
    state = tmp_path / "state"
    monkeypatch.setattr(collector, "state_directory", lambda: state)
    monkeypatch.setattr(collector, "utc_now", lambda: NOW)
    monkeypatch.setattr(claude_hook, "utc_now", lambda: NOW)
    if transcript:
        folder = tmp_path / "claude" / "projects" / "synthetic"
        folder.mkdir(parents=True)
        record = {"type": "user", "sessionId": "synthetic", "promptId": "new",
                  "timestamp": (NOW - timedelta(seconds=1)).isoformat(),
                  "message": {"content": "synthetic prompt"}}
        (folder / "synthetic.jsonl").write_text(json.dumps(record) + "\n", encoding="utf-8")
    else:
        claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "synthetic",
                                  "prompt_id": "new"}, state_dir=state, received_ns=1)
    claude_hook.record_event({"hook_event_name": "StopFailure", "session_id": "synthetic",
                             "prompt_id": "old", "error": "billing_error"}, state_dir=state, received_ns=2)
    task = collector.collect_snapshot(tmp_path / "codex", tmp_path / "claude", include_output=True)["tasks"][0]
    assert task["status"] != "error"
    assert task["failure_code"] == "" and task["error_analysis"] is None
