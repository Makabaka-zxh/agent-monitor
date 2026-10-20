"""Synthetic evidence only; no real logs, configurations, or service requests."""
import copy
from datetime import datetime, timedelta, timezone
import json
import secrets

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from agent_monitor import collector
from agent_monitor.error_analysis import ErrorAnalysis, analyze_final_text
from agent_monitor.server import Task, create_app
from agent_monitor.native_access import challenge_for
from agent_monitor.store import Store


NOW = datetime(2026, 10, 20, 12, 0, tzinfo=timezone.utc)
REPORT = 'File "C:\\synthetic\\workspace\\worker.py", line 27\nSyntaxError: invalid syntax'


def event(kind, offset=0, **extra):
    return {"type": "event_msg", "timestamp": (NOW + timedelta(seconds=offset)).isoformat(),
            "payload": {"type": kind, **extra}}


def final(text=REPORT, offset=1):
    return {"type": "response_item", "timestamp": (NOW + timedelta(seconds=offset)).isoformat(),
            "payload": {"type": "message", "role": "assistant", "phase": "final",
                        "content": [{"type": "output_text", "text": text}]}}


def codex(tmp_path, records, *, consent=True, child=False):
    meta = {"type": "session_meta", "payload": {"id": "synthetic-session", "cwd": "synthetic-project"}}
    if child:
        meta["payload"]["parent_thread_id"] = "synthetic-parent"
    path = tmp_path / "synthetic.jsonl"
    path.write_text("\n".join(json.dumps(row) for row in [meta, *records]) + "\n", encoding="utf-8")
    return collector.parse_codex_session(path, include_output=consent, now=NOW + timedelta(seconds=10))


def task_payload():
    return {"id": "codex:synthetic", "tool": "codex", "title": "Synthetic failure", "status": "error",
            "updated_at": NOW.isoformat(), "output": REPORT, "error_analysis": analyze_final_text(REPORT)}


def test_explicit_report_has_fixed_explanation_and_basename_only():
    result = analyze_final_text(REPORT)
    assert result["category"] == "syntax" and result["confidence"] == "reported"
    assert result["source"] == "final_output"
    assert result["location"] == {"file": "worker.py", "line": 27, "column": None, "step": "编译或解析"}
    assert "尚未独立验证根因" in result["summary"]
    assert "synthetic" not in json.dumps(result) and "C:" not in json.dumps(result)
    assert ErrorAnalysis.model_validate(result).model_dump() == result


@pytest.mark.parametrize(("text", "category"), [
    ("ModuleNotFoundError: synthetic_module", "dependency"),
    ("PermissionError: synthetic denial", "permission"),
    ("AuthenticationError: synthetic auth", "authentication"),
    ("HTTP 429: synthetic rate limit", "rate_limit"),
    ("ConnectionError: synthetic connect", "network"),
    ("TimeoutError: synthetic timeout", "timeout"),
    ("AssertionError: synthetic expectation", "test_failure"),
])
def test_categories_report_evidence_without_copying_raw_message(text, category):
    result = analyze_final_text(text)
    assert result["category"] == category
    assert result["location"] is None
    assert "synthetic" not in json.dumps(result)


def test_compiler_location_has_column_but_never_a_url_or_absolute_directory():
    result = analyze_final_text("/synthetic/src/card.ts:42:7 error TS2322: synthetic detail")
    assert result["location"] == {"file": "card.ts", "line": 42, "column": 7, "step": "编译或解析"}


def test_multiple_frames_are_not_misrepresented_as_a_known_fault_location():
    result = analyze_final_text('File "/synthetic/a.py", line 1\nFile "/synthetic/b.py", line 2\nAssertionError: synthetic')
    assert result["category"] == "test_failure" and result["location"] is None


@pytest.mark.parametrize("text", ["The task failed for an unspecified reason.",
                                  "SyntaxError: synthetic\nPermissionError: synthetic"])
def test_unknown_or_conflicting_diagnostics_are_explicitly_unconfirmed(text):
    result = analyze_final_text(text)
    assert result["category"] == result["confidence"] == "unknown"
    assert result["location"] is None
    assert "尚未确认" in result["summary"]


@pytest.mark.parametrize("text", ["There was no SyntaxError.", "If HTTP 429 occurs, wait.",
                                  "已修复 SyntaxError: synthetic", "可能是权限不足。"])
def test_negated_historical_or_hypothetical_mentions_are_not_reported_as_cause(text):
    assert analyze_final_text(text)["confidence"] == "unknown"


def test_analysis_is_bounded_and_invalid_numeric_positions_do_not_crash():
    result = analyze_final_text('SyntaxError: synthetic\nFile "a.py", line ' + "9" * 11000)
    assert result["location"] is None
    assert analyze_final_text("x" * 12000 + "SyntaxError")["category"] == "unknown"


def test_current_failed_turn_can_analyze_only_its_explicit_final(tmp_path):
    task = codex(tmp_path, [event("turn_started"), final(), event("turn_failed", 2)])
    assert task["status"] == "error" and task["error_analysis"] == analyze_final_text(REPORT)


@pytest.mark.parametrize("closing", ["task_complete", "turn_complete"])
def test_failed_closing_event_with_legacy_final_is_supported(tmp_path, closing):
    task = codex(tmp_path, [event("turn_started"), event(closing, 2, status="failed", last_agent_message=REPORT)])
    assert task["error_analysis"]["category"] == "syntax"


def test_default_consent_discards_analysis_and_output(tmp_path):
    task = codex(tmp_path, [event("turn_started"), final(), event("turn_failed", 2)], consent=False)
    assert task["status"] == "error" and task["output"] == "" and task["error_analysis"] is None


@pytest.mark.parametrize("records", [
    [final(), event("turn_failed", 2)],  # Boundary absent from bounded window.
    [event("turn_started"), final(), event("turn_complete", 2), event("turn_started", 3), event("turn_failed", 4)],
    [event("turn_started"), final(), event("turn_complete", 2), event("turn_failed", 4)],
    [event("turn_started"), final(), event("user_message", 2, message="synthetic prompt"), event("turn_failed", 3)],
])
def test_retained_old_final_is_never_analyzed_for_a_new_failure(tmp_path, records):
    task = codex(tmp_path, records)
    assert task["status"] == "error" and task["output"] == REPORT
    assert task["error_analysis"] is None


def test_raw_failure_payload_tool_results_and_reasoning_are_not_analyzed(tmp_path):
    task = codex(tmp_path, [event("turn_started"),
        {"type": "response_item", "payload": {"type": "function_call_output", "output": REPORT}},
        {"type": "response_item", "payload": {"type": "reasoning", "text": REPORT}},
        event("turn_failed", 2, error=REPORT)])
    assert task["status"] == "error" and task["error_analysis"] is None and task["output"] == ""


def test_delayed_old_final_timestamp_cannot_explain_current_failure(tmp_path):
    task = codex(tmp_path, [event("turn_started", 3), final(offset=1), event("turn_failed", 4)])
    assert task["status"] == "error" and task["error_analysis"] is None


def test_nonfailed_turn_and_subagent_do_not_get_diagnostics(tmp_path):
    records = [event("turn_started"), final(), event("turn_complete", 2)]
    assert codex(tmp_path, records)["error_analysis"] is None
    assert codex(tmp_path, [*records, event("turn_failed", 3)], child=True) is None


@pytest.mark.parametrize("with_current_prompt", [True, False])
def test_claude_requires_current_prompt_final_and_failing_hook(tmp_path, monkeypatch, with_current_prompt):
    home = tmp_path / "claude"
    project = home / "projects" / "synthetic-project"
    project.mkdir(parents=True)
    rows = [{"type": "user", "sessionId": "synthetic", "timestamp": NOW.isoformat(),
             "promptId": "synthetic-prompt", "message": {"content": "synthetic request"}},
            {"type": "assistant", "sessionId": "synthetic", "timestamp": (NOW + timedelta(seconds=1)).isoformat(),
             "message": {"stop_reason": "end_turn", "content": [{"type": "text", "text": REPORT}]}}]
    if not with_current_prompt:
        rows.append({"type": "user", "sessionId": "synthetic", "timestamp": (NOW + timedelta(seconds=2)).isoformat(),
                     "promptId": "synthetic-prompt", "message": {"content": "later synthetic request"}})
    (project / "synthetic.jsonl").write_text("\n".join(map(json.dumps, rows)) + "\n", encoding="utf-8")
    hook = collector._task("claude", "synthetic", "", "error", NOW + timedelta(seconds=3), "", source="hook")
    hook["_prompt_id"] = "synthetic-prompt"
    monkeypatch.setattr(collector, "_hook_tasks", lambda *_: [dict(hook)])
    snapshot = collector.collect_snapshot(tmp_path / "codex", home, include_output=True)
    task = snapshot["tasks"][0]
    assert task["status"] == "error"
    assert bool(task["error_analysis"]) == with_current_prompt


def test_storage_consent_recheck_and_revocation_purge_existing_analysis(tmp_path):
    store = Store(tmp_path / "synthetic.sqlite3")
    try:
        device = store.ensure_local("Synthetic", "Test")
        task = task_payload()
        store.ingest(device, [task], [])
        assert store.snapshot()["tasks"][0]["error_analysis"] is None
        store.update_preferences({"sync_output": True})
        store.ingest(device, [task], [])
        assert store.snapshot()["tasks"][0]["error_analysis"] == task["error_analysis"]
        store.update_preferences({"sync_output": False})
        stored = store.snapshot()["tasks"][0]
        assert stored["output"] == "" and stored["error_analysis"] is None
        # A snapshot collected before revocation cannot put the payload back.
        store.ingest(device, [task], [])
        assert store.snapshot()["tasks"][0]["error_analysis"] is None
    finally:
        store.close()


@pytest.mark.parametrize("alteration", ["summary", "location", "nonerror", "extra", "mismatched_output"])
def test_storage_drops_unbound_or_arbitrary_remote_analysis(tmp_path, alteration):
    store = Store(tmp_path / "synthetic.sqlite3")
    try:
        device = store.ensure_local("Synthetic", "Test")
        store.update_preferences({"sync_output": True})
        task = copy.deepcopy(task_payload())
        if alteration == "summary":
            task["error_analysis"]["summary"] = "arbitrary content"
        elif alteration == "location":
            task["error_analysis"]["location"]["file"] = "C:/synthetic/private.py"
        elif alteration == "nonerror":
            task["status"] = "running"
        elif alteration == "extra":
            task["error_analysis"]["raw"] = "arbitrary content"
        else:
            task["output"] = "different final answer"
        store.ingest(device, [task], [])
        assert store.snapshot()["tasks"][0]["error_analysis"] is None
    finally:
        store.close()


def test_offline_snapshot_does_not_present_a_historic_error_as_current(tmp_path):
    store = Store(tmp_path / "synthetic.sqlite3")
    try:
        device = store.ensure_local("Synthetic", "Test")
        store.update_preferences({"sync_output": True})
        store.ingest(device, [task_payload()], [])
        with store.lock, store.db:
            store.db.execute("UPDATE devices SET last_seen=0 WHERE id=?", (device,))
        task = store.snapshot()["tasks"][0]
        assert task["status"] == "unknown" and task["last_known_status"] == "error"
        assert task["error_analysis"] is None
    finally:
        store.close()


def test_api_contract_accepts_canonical_analysis_and_rejects_raw_extensions():
    task = task_payload()
    assert Task.model_validate(task).model_dump()["error_analysis"] == task["error_analysis"]
    task["error_analysis"]["raw_error"] = "not part of this protocol"
    with pytest.raises(ValidationError):
        Task.model_validate(task)


def test_browser_native_and_minimal_snapshots_share_consent_without_expanding_read_only_scope(tmp_path):
    origin = "https://synthetic-error.example.test"
    account = {"username": "synthetic-owner", "password": "Synthetic-error-password-123"}
    app = create_app(tmp_path / "synthetic-state", public_url=origin, allowed_hosts=["synthetic-error.example.test"])
    store = app.state.store
    store.setup(**account)
    with TestClient(app, base_url=origin) as browser:
        native = TestClient(app, base_url=origin)
        login = browser.post("/api/login", json=account)
        assert login.status_code == 200
        verifier = secrets.token_urlsafe(32)
        started = native.post("/api/native/pairing/start", json={"device_name": "Synthetic reader",
                              "code_challenge": challenge_for(verifier), "mode": "full_app"})
        assert started.status_code == 200
        path = "/api/native/pairing/" + started.json()["request_id"]
        approved = browser.post(path + "/approve", headers={"Origin": origin, "X-CSRF-Token": login.json()["csrf_token"]},
                                json={"mode": "full_app", "consent_version": "full_app_v1"})
        assert approved.status_code == 200
        claimed = native.post(path + "/poll", json={"code_verifier": verifier})
        assert claimed.status_code == 200
        headers = {"Authorization": "Bearer " + claimed.json()["reader_token"]}
        device = store.ensure_local("Synthetic", "Test")
        store.update_preferences({"sync_output": True})
        store.ingest(device, [task_payload()], [])
        assert browser.get("/api/snapshot").json()["tasks"][0]["error_analysis"] == analyze_final_text(REPORT)
        workbench = native.get("/api/native/workbench", headers=headers)
        assert workbench.status_code == 200
        item = workbench.json()["tasks"][0]
        assert item["error_analysis"] == analyze_final_text(REPORT) and "output" not in item
        minimal = native.get("/api/native/snapshot", headers=headers)
        assert minimal.status_code == 200 and "error_analysis" not in minimal.json()["tasks"][0]
        store.update_preferences({"sync_output": False})
        assert browser.get("/api/snapshot").json()["tasks"][0]["error_analysis"] is None
        assert native.get("/api/native/workbench", headers=headers).json()["tasks"][0]["error_analysis"] is None
        native.close()
