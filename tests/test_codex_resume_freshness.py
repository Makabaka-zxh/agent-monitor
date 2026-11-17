"""Do not confuse owned Codex resume metadata with concurrent task writes."""
from dataclasses import replace
import json

import pytest

from agent_monitor import local_executor as le


SESSION = "00000000-0000-4000-8000-000000000001"
PROFILE = {"filesystem": {":root": "read"}, "network": {"enabled": False}}
BASE = b'{"ordinal":9,"type":"event_msg","payload":{"type":"task_complete"}}\n'


@pytest.fixture
def journal(tmp_path):
    path = tmp_path / "session.jsonl"
    path.write_bytes(BASE)
    target = le._Target("codex:" + SESSION, "codex", SESSION, path, tmp_path, le._stamp(path),
                        "synthetic-codex", PROFILE, "fixture-model", "low", status="completed")
    marker = le._command(target)[1]
    resume = {"model": "fixture-model", "modelProvider": "openai", "approvalPolicy": "untrusted",
              "approvalsReviewer": "user", "activePermissionProfile": {"id": marker, "extends": None},
              "sandbox": {"networkAccess": False}, "serviceTier": "priority",
              "reasoningEffort": "ultra", "collaborationMode": {"mode": "default", "settings": {
                  "model": "fixture-model", "reasoning_effort": "ultra", "developer_instructions": None}},
              "disabledPluginIds": [], "cwd": str(tmp_path), "runtimeWorkspaceRoots": [str(tmp_path)]}
    event = {"timestamp": "2026-11-16T11:49:09.464Z", "ordinal": 10, "type": "event_msg", "payload": {
        "type": "thread_settings_applied", "thread_id": SESSION, "thread_settings": {
            "model": resume["model"], "model_provider_id": resume["modelProvider"],
            "service_tier": resume["serviceTier"], "approval_policy": resume["approvalPolicy"],
            "approvals_reviewer": resume["approvalsReviewer"], "requested_approvals_reviewer": "user",
            "active_permission_profile": {"id": marker}, "cwd": str(tmp_path),
            "runtime_workspace_roots": [str(tmp_path)], "reasoning_effort": resume["reasoningEffort"],
            "collaboration_mode": resume["collaborationMode"], "disabled_plugin_ids": [],
            "permission_profile": {"type": "managed", "network": "restricted", "file_system": {
                "type": "restricted", "entries": [{"access": "read", "path": {
                    "type": "special", "value": {"kind": "root"}}}]} }}}}
    return target, resume, event


def append(path, value):
    raw = (json.dumps(value) + "\n").encode("utf-8")
    with path.open("ab") as handle:
        handle.write(raw)


def test_matches_owned_resume_profile_and_only_rebaselines_once(journal, monkeypatch):
    target, resumed, event = journal
    checkpoint = le._codex_resume_checkpoint(target)
    assert checkpoint is not None
    assert le._command(target)[1] != resumed["activePermissionProfile"]["id"]
    append(target.path, event)
    runner = le.LocalReplyExecutor(lambda _: None)
    monkeypatch.setattr(le, "_inspect", lambda *_args, **_kw: replace(target, stamp=le._stamp(target.path)))
    try:
        fresh = runner._fresh_after_codex_resume(target, checkpoint, resumed)
        assert fresh != target and replace(fresh, stamp=target.stamp) == target
        append(target.path, {"type": "event_msg", "payload": {"type": "task_started"}})
        with pytest.raises(le.ExecutionIssue, match="changed"):
            runner._fresh(fresh)
    finally:
        runner.close()


@pytest.mark.parametrize("instructions_bytes,allowed", [(10_634, True), (32 * 1024, False)])
def test_large_verified_plan_settings_still_have_a_strict_record_bound(journal, instructions_bytes, allowed):
    target, resumed, event = journal
    checkpoint = le._codex_resume_checkpoint(target)
    mode = resumed["collaborationMode"]
    mode["mode"] = "plan"
    mode["settings"]["developer_instructions"] = "a" * instructions_bytes
    append(target.path, event)
    fresh = replace(target, stamp=le._stamp(target.path))
    assert le._codex_resume_metadata(target, fresh, checkpoint, resumed) is allowed


@pytest.mark.parametrize("changed", ["wrong-thread", "wrong-child", "wrong-workspace", "wrong-effort", "extra-field",
                                    "another-turn", "previous-edit", "truncated", "another-profile", "status", "inherited-profile"])
def test_non_resume_changes_never_rebaseline(journal, monkeypatch, changed):
    target, resumed, event = journal
    checkpoint = le._codex_resume_checkpoint(target)
    settings = event["payload"]["thread_settings"]
    if changed == "wrong-thread":
        event["payload"]["thread_id"] = "00000000-0000-4000-8000-000000000002"
    elif changed == "wrong-child":
        settings["active_permission_profile"]["id"] = le._command(target)[1]
    elif changed == "wrong-workspace":
        settings["runtime_workspace_roots"] = [str(target.cwd / "elsewhere")]
    elif changed == "wrong-effort":
        settings["reasoning_effort"] = "low"
    elif changed == "extra-field":
        settings["unknown_setting"] = True
    elif changed == "previous-edit":
        target.path.write_bytes(BASE.replace(b"task_complete", b"other_content"))
    elif changed == "another-profile":
        settings["permission_profile"]["network"] = "enabled"
    elif changed == "inherited-profile":
        resumed["activePermissionProfile"]["extends"] = ":workspace"
    if changed == "truncated":
        target.path.write_bytes(BASE[:-1])
    append(target.path, event)
    if changed == "another-turn":
        append(target.path, {"type": "event_msg", "payload": {"type": "task_started"}})
    current = replace(target, stamp=le._stamp(target.path), status="idle" if changed == "status" else target.status)
    runner = le.LocalReplyExecutor(lambda _: None)
    monkeypatch.setattr(le, "_inspect", lambda *_args, **_kw: current)
    try:
        with pytest.raises(le.ExecutionIssue, match="changed"):
            runner._fresh_after_codex_resume(target, checkpoint, resumed)
    finally:
        runner.close()
