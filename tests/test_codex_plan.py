"""Next-reply Plan uses the native, locally advertised collaboration preset."""
from types import SimpleNamespace

import pytest

from agent_monitor import local_executor as le
from agent_monitor.model_capabilities import normalize_catalog, selected_settings
from agent_monitor.session_controls import ModelSelection, reply_fingerprint
from test_codex_quick_tier import RecordingTransport, codex_row, selection
from test_task_replies import hub, send


def test_catalog_and_queued_reply_preserve_only_explicit_plan(hub):
    app, client, headers, task_id, *_ = hub
    device, _, source = task_id.partition(":")
    controls = app.state.task_replies.controls
    old = normalize_catalog("codex", [codex_row()])
    current = normalize_catalog("codex", [codex_row()], collaboration_modes=("plan",))
    assert "collaboration_modes" not in old and old["catalog_revision"] != current["catalog_revision"]
    with pytest.raises(ValueError):
        ModelSelection.model_validate(selection(current, provider="claude", collaboration_mode="plan"))
    omission = ModelSelection.model_validate(selection(current))
    explicit_null = ModelSelection.model_validate(selection(current, collaboration_mode=None))
    assert omission.model_dump() == explicit_null.model_dump() and "collaboration_mode" not in omission.model_dump()
    assert reply_fingerprint("synthetic", omission) == reply_fingerprint("synthetic", explicit_null)
    controls.publish_catalog(device, source, old)
    assert send(hub, selection=selection(old, collaboration_mode="plan"))[1].status_code == 409
    controls.publish_catalog(device, source, current)
    catalog = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": task_id}).json()["catalog"]
    assert catalog["collaboration_modes"] == ["plan"]
    request, response = send(hub, selection=selection(catalog, collaboration_mode="plan"))
    assert response.status_code == 200
    changed = client.post("/api/native/tasks/reply", headers=headers, json={**request, "selection": selection(catalog)})
    assert changed.status_code == 409
    with controls.store.lock, controls.store.db:
        assert app.state.task_replies._claim(device)["selection"]["collaboration_mode"] == "plan"


def test_provider_ignoring_optional_mode_request_falls_back_to_legacy_models(monkeypatch):
    observed = []
    fake_time = iter((0, 0, 0.5, 1.0))
    monkeypatch.setattr(le.time, "monotonic", lambda: next(fake_time))
    transport = SimpleNamespace(send=lambda event: observed.append(event), next=lambda **kwargs: None)
    assert le._codex_collaboration_modes(transport) == ()
    assert observed[0]["method"] == "collaborationMode/list"


@pytest.mark.parametrize("mode_present", [True, False])
def test_live_compatible_plan_uses_nested_choice_without_permission_changes(tmp_path, monkeypatch, mode_present):
    target = SimpleNamespace(session_id="synthetic-session", cwd=tmp_path, path=tmp_path/"missing.jsonl", model="desktop-model", effort="low")
    rows = [codex_row()]
    supported = ("plan", "default") if mode_present else ("plan",)
    catalog = normalize_catalog("codex", rows, collaboration_modes=("plan",))
    choice = selection(catalog, collaboration_mode="plan")
    settings = selected_settings(choice, catalog, provider="codex", session_id=target.session_id)
    transport = RecordingTransport(target, rows, modes=supported)
    runner = le.LocalReplyExecutor(lambda value: None)
    monkeypatch.setattr(runner, "_fresh", lambda current: None)
    run = SimpleNamespace(command_id="test-plan", selection=choice)
    try:
        if not mode_present:
            with pytest.raises(le.ExecutionIssue) as err:
                runner._codex(transport, target, "fixture-profile", "Synthetic prompt", run, settings)
            assert err.value.code == "changed"
            assert not any(r["method"] == "turn/start" for r in transport.requests)
            return
        runner._codex(transport, target, "fixture-profile", "Synthetic prompt", run, settings)
        resume, turn = (request["params"] for request in transport.requests[-2:])
        assert "collaborationMode" not in resume
        assert turn["collaborationMode"] == {"mode": "plan", "settings": {
            "model": "test-model", "reasoning_effort": "medium", "developer_instructions": None}}
        for key in ("permissions", "approvalPolicy", "approvalsReviewer"):
            assert turn[key] == resume[key]
        assert "serviceTierForTurn" not in turn and "serviceTier" not in turn
    finally:
        runner.close()


@pytest.mark.parametrize("active_mode", [None, "default", "plan"])
def test_an_ordinary_reply_does_not_repeat_plan_when_native_resume_keeps_it(tmp_path, monkeypatch, active_mode):
    target = SimpleNamespace(session_id="synthetic-session", cwd=tmp_path, path=tmp_path/"missing.jsonl", model="test-model", effort="medium")
    transport = RecordingTransport(target, [codex_row()], modes=("plan", "default"), active_mode=active_mode)
    runner = le.LocalReplyExecutor(lambda value: None)
    monkeypatch.setattr(runner, "_fresh", lambda current: None)
    try:
        runner._codex(transport, target, "fixture-profile", "Synthetic prompt", SimpleNamespace(command_id="ordinary"))
        turn = transport.requests[-1]["params"]
        if active_mode == "plan":
            assert turn["collaborationMode"] == {"mode": "default", "settings": {
                "model": "test-model", "reasoning_effort": "medium", "developer_instructions": None}}
        else:
            assert "collaborationMode" not in turn
            assert not any(request["method"] == "collaborationMode/list" for request in transport.requests)
        assert turn["permissions"] == "fixture-profile" and turn["approvalPolicy"] == "untrusted"
        assert turn["approvalsReviewer"] == "user"
    finally:
        runner.close()
