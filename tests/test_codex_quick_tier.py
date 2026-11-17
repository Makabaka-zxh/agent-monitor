"""Provider protocol and native API tests; no real Codex turns or accounts."""

from collections import deque
import hashlib
import json
from types import SimpleNamespace

import pytest

from agent_monitor import local_executor as le
from agent_monitor.model_capabilities import normalize_catalog, selected_settings
from agent_monitor.session_controls import ModelSelection, reply_fingerprint
from test_task_replies import hub, send


def codex_row(model="test-model", **metadata):
    return {"model": model, "displayName": "Synthetic " + model,
            "supportedReasoningEfforts": [{"reasoningEffort": "medium"}], **metadata}


PRIORITY = {"id": "priority", "name": "Fast", "description": "Synthetic provider tier"}


def selection(catalog, **changed):
    return {"provider": "codex", "catalog_revision": catalog["catalog_revision"], "model": "test-model",
            "effort": "medium", "apply_at": "next_message", **changed}


def test_tier_requires_canonical_per_model_provider_metadata_and_changes_revision():
    rows = [codex_row(), codex_row("other-model", additionalSpeedTiers=["fast"], defaultServiceTier="priority"),
            codex_row("hidden-model", hidden=True, serviceTiers=[PRIORITY])]
    old_shape = normalize_catalog("codex", rows)
    assert all("service_tiers" not in row for row in old_shape["models"])
    legacy = {"provider": "codex", "models": [
        {"id": "other-model", "label": "Synthetic other-model", "efforts": ["medium"]},
        {"id": "test-model", "label": "Synthetic test-model", "efforts": ["medium"]},
    ]}
    legacy_revision = hashlib.sha256(json.dumps(legacy, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    assert old_shape["catalog_revision"] == legacy_revision
    rows[0]["serviceTiers"] = [PRIORITY, {"id": "fast"}, {"id": "ultra"}]
    catalog = normalize_catalog("codex", rows)
    options = {model["id"]: model for model in catalog["models"]}
    assert options["test-model"]["service_tiers"] == ["priority"]
    assert "service_tiers" not in options["other-model"] and "hidden-model" not in options
    assert catalog["catalog_revision"] != legacy_revision
    claude = normalize_catalog("claude", [{"value": "test-model", "displayName": "Claude", "serviceTiers": [PRIORITY]}])
    assert "service_tiers" not in claude["models"][0]


def test_omission_and_null_keep_old_fingerprint_and_never_select_priority_implicitly():
    catalog = normalize_catalog("codex", [codex_row(serviceTiers=[PRIORITY])])
    old = selection(catalog)
    explicit_null = selection(catalog, service_tier=None)
    legacy_fingerprint = hashlib.sha256(json.dumps({"text": "synthetic", "selection": old}, sort_keys=True,
        separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    for data in (old, explicit_null):
        parsed = ModelSelection.model_validate(data)
        assert parsed.model_dump() == old
        assert json.loads(parsed.model_dump_json()) == old
        assert reply_fingerprint("synthetic", parsed) == legacy_fingerprint
        assert selected_settings(parsed, catalog, provider="codex", session_id="fixture") == {
            "model": "test-model", "effort": "medium"}
    quick = ModelSelection.model_validate(selection(catalog, service_tier="priority"))
    assert reply_fingerprint("synthetic", quick) != legacy_fingerprint
    with pytest.raises(ValueError):
        ModelSelection.model_validate(selection(catalog, service_tier="fast"))
    with pytest.raises(ValueError):
        ModelSelection.model_validate(selection(catalog, provider="claude", service_tier="priority"))


def test_native_catalog_and_send_accept_priority_only_for_the_exact_model(hub):
    app, client, headers, task_id, *_ = hub
    device, _, source = task_id.partition(":")
    controls = app.state.task_replies.controls
    catalog = normalize_catalog("codex", [codex_row(serviceTiers=[PRIORITY]), codex_row("other-model")])
    controls.publish_catalog(device, source, catalog)
    response = client.get("/api/native/tasks/controls", headers=headers, params={"task_id": task_id})
    assert response.status_code == 200
    options = {item["id"]: item for item in response.json()["catalog"]["models"]}
    assert options["test-model"]["service_tiers"] == ["priority"]
    assert "service_tiers" not in options["other-model"]
    _, legacy = send(hub, selection=selection(catalog, service_tier="fast"))
    assert legacy.status_code == 422
    _, unavailable = send(hub, selection=selection(catalog, model="other-model", service_tier="priority"))
    assert unavailable.status_code == 409
    request, queued = send(hub, selection=selection(catalog))
    assert queued.status_code == 200
    repeat = client.post("/api/native/tasks/reply", headers=headers,
                         json={**request, "selection": selection(catalog, service_tier=None)})
    assert repeat.status_code == 200 and repeat.json()["id"] == queued.json()["id"]
    altered = client.post("/api/native/tasks/reply", headers=headers,
                          json={**request, "selection": selection(catalog, service_tier="priority")})
    assert altered.status_code == 409
    replies = app.state.task_replies
    with controls.store.lock, controls.store.db:
        assert replies._claim(device)["selection"] == selection(catalog)
    replies.update(queued.json()["id"], "succeeded")
    _, priority = send(hub, selection=selection(catalog, service_tier="priority"))
    assert priority.status_code == 200 and priority.json()["state"] == "queued"
    with controls.store.lock, controls.store.db:
        assert replies._claim(device)["selection"]["service_tier"] == "priority"


class RecordingTransport:
    def __init__(self, target, rows, *, modes=(), active_mode=None):
        self.target, self.rows = target, rows
        self.modes, self.active_mode = modes, active_mode
        self.requests = []
        self.responses = deque()

    def send(self, rpc):
        self.requests.append(rpc)
        method = rpc["method"]
        if method == "initialize":
            result = {}
        elif method == "initialized":
            return
        elif method == "model/list":
            result = {"data": self.rows, "nextCursor": None}
        elif method == "collaborationMode/list":
            if not self.modes:
                self.responses.append({"id": rpc["id"], "error": {"code": -32601, "message": "unsupported"}})
                return
            result = {"data": [{"name": mode.title(), "mode": mode} for mode in self.modes]}
        elif method == "thread/resume":
            result = {"thread": {"id": self.target.session_id, "status": {"type": "idle"}},
                      "cwd": str(self.target.cwd), "approvalPolicy": "untrusted", "approvalsReviewer": "user",
                      "activePermissionProfile": {"id": "fixture-profile"}, "sandbox": {"networkAccess": False}}
            if self.active_mode is not None:
                result["collaborationMode"] = {"mode": self.active_mode, "settings": {
                    "model": self.target.model, "reasoning_effort": self.target.effort}}
        elif method == "turn/start":
            result = {"turn": {"id": "fixture-turn", "status": "inProgress"}}
        else:
            pytest.fail("unexpected provider method")
        self.responses.append({"id": rpc["id"], "result": result})
        if method == "turn/start":
            self.responses.append({"method": "turn/completed", "params": {"threadId": self.target.session_id,
                "turn": {"id": "fixture-turn", "status": "completed"}}})

    def next(self, *, wait=True):
        return self.responses.popleft() if wait or self.responses else None


@pytest.mark.parametrize("tier,advertised", [("priority", True), (None, True), ("priority", False)])
def test_rpc_uses_fresh_capability_and_never_sets_a_persistent_tier(tmp_path, monkeypatch, tier, advertised):
    target = SimpleNamespace(session_id="fixture-session", cwd=tmp_path, path=tmp_path/"missing.jsonl", model="test-model", effort="medium")
    old_rows = [codex_row(serviceTiers=[PRIORITY])]
    initial = normalize_catalog("codex", old_rows)
    choose = selection(initial, service_tier=tier)
    old_settings = selected_settings(choose, initial, provider="codex", session_id=target.session_id)
    actual = RecordingTransport(target, old_rows if advertised else [codex_row()])
    runner = le.LocalReplyExecutor(lambda value: None)
    monkeypatch.setattr(runner, "_fresh", lambda current: None)
    run = SimpleNamespace(command_id="fixture-id", selection=choose)
    try:
        if not advertised:
            with pytest.raises(le.ExecutionIssue) as blocked:
                runner._codex(actual, target, "fixture-profile", "Synthetic prompt", run, old_settings)
            assert blocked.value.code == "changed"
            assert [request["method"] for request in actual.requests] == ["initialize", "initialized", "model/list", "collaborationMode/list"]
            return
        runner._codex(actual, target, "fixture-profile", "Synthetic prompt", run, old_settings)
        resume, turn = (request["params"] for request in actual.requests[-2:])
        assert "serviceTier" not in resume and "serviceTierForTurn" not in resume and "serviceTier" not in turn
        assert turn.get("serviceTierForTurn") == tier
        if tier is None:
            assert "serviceTierForTurn" not in turn
        assert turn["model"] == "test-model" and turn["effort"] == "medium"
        for key in ("permissions", "approvalPolicy", "approvalsReviewer"):
            assert turn[key] == resume[key]
    finally:
        runner.close()
