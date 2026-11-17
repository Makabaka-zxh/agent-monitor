"""Exact authorized source lookup for a recovered synthetic Codex task."""
from datetime import timedelta

import pytest

from agent_monitor import collector, local_executor, task_results
from test_codex_primary_backfill import journal
from test_compressed_collector import NOW, encode, homes, row, write

SESSION = "01900000-0000-7000-8000-000000000001"
SOURCE = "codex:" + SESSION
STAMP = "rollout-2026-10-17T08-00-00-"


def fill_primary_window(codex):
    for index in range(50):
        journal(codex, index, f"child-{index}", meta={"parent_thread_id": "synthetic-parent"})


def older(codex, workspace, *, filename=STAMP + SESSION, sid=SESSION, rank=50, compressed=False,
          raw=None, child=False):
    if raw is None:
        meta = {"id": sid, "cwd": str(workspace)}
        if child:
            meta["source"] = {"subagent": {}}
        raw = encode([row("session_meta", meta),
                      row("event_msg", {"type": "task_complete", "last_agent_message": "Synthetic final"})])
    return write(codex, filename, raw, compressed=compressed, mtime=NOW - timedelta(seconds=rank))


def test_recovered_source_supports_result_and_reply_target(homes, tmp_path, monkeypatch):
    codex, claude = homes
    workspace = tmp_path / "existing-project"
    workspace.mkdir()
    fill_primary_window(codex)
    other = older(codex, workspace, filename=STAMP + "01900000-0000-7000-8000-000000000002", sid="unrelated")
    path = older(codex, workspace, rank=51)
    assert {task["id"] for task in collector.collect_snapshot(codex, claude, codex_primary_backfill=True)["tasks"]} == {
        SOURCE, "codex:unrelated"}
    head, extra = collector._read_head, task_results._read_extra_codex_head
    ordinary_reads, extra_reads = [], []

    def read_head(candidate):
        ordinary_reads.append(candidate)
        return head(candidate)

    def exact_extra(candidate, base):
        extra_reads.append(candidate)
        return extra(candidate, base)

    monkeypatch.setattr(collector, "_read_head", read_head)
    monkeypatch.setattr(task_results, "_read_extra_codex_head", exact_extra)
    results = task_results.LocalResults(codex_home=codex, claude_home=claude)
    assert results.read(SOURCE)["text"] == "Synthetic final"
    assert len(ordinary_reads) == 50 and extra_reads == [path] and other not in ordinary_reads
    resolved = local_executor._resolve_target(SOURCE, lambda source: task_results.resolve_local_session(source, codex, claude))
    assert (resolved.path, resolved.cwd, resolved.session_id) == (path, workspace, SESSION)
    assert collector.MAX_SESSION_FILES == 50


@pytest.mark.parametrize("scenario,extra_count", [
    ("wrong-metadata", 1), ("subagent", 1), ("incomplete", 1), ("no-workspace", 1),
    ("substring", 0), ("compressed", 0), ("outside", 0), ("ambiguous", 0),
])
def test_extra_resolution_rejects_nonmatching_or_unverified_paths(homes, tmp_path, monkeypatch, scenario, extra_count):
    codex, claude = homes
    workspace = tmp_path / "existing-project"
    if scenario != "no-workspace":
        workspace.mkdir()
    fill_primary_window(codex)
    options = {}
    if scenario == "wrong-metadata":
        options["sid"] = "not-the-request"
    elif scenario == "subagent":
        options["child"] = True
    elif scenario == "incomplete":
        options["raw"] = encode([row("session_meta", {"id": SESSION, "cwd": str(workspace)})]).rstrip(b"\n")
    elif scenario == "substring":
        options["filename"] = STAMP + SESSION + "-substring"
    elif scenario == "compressed":
        options["compressed"] = True
    elif scenario == "outside":
        for index in range(50, 50 + collector.CODEX_PRIMARY_LOOKAHEAD):
            journal(codex, index, "unrelated")
        options["rank"] = 50 + collector.CODEX_PRIMARY_LOOKAHEAD
    elif scenario == "ambiguous":
        older(codex, workspace, filename="rollout-2026-10-16T08-00-00-" + SESSION, rank=51)
    target = older(codex, workspace, **options)
    extra_reads = []
    extra = task_results._read_extra_codex_head

    def exact_extra(path, base):
        extra_reads.append(path)
        return extra(path, base)

    monkeypatch.setattr(task_results, "_read_extra_codex_head", exact_extra)
    assert task_results.resolve_local_session(SOURCE, codex, claude, require_workspace=False) is None
    assert extra_reads == [target] * extra_count


def test_existing_recent_legacy_metadata_and_result_rules_take_precedence(homes, tmp_path):
    codex, claude = homes
    workspace = tmp_path / "removed-original-project"
    legacy = older(codex, workspace, filename="legacy-without-exact-id", rank=0)
    for index in range(1, 50):
        journal(codex, index, "child", meta={"parent_thread_id": "synthetic-parent"})
    existing = tmp_path / "different-project"
    existing.mkdir()
    older(codex, existing, rank=50)
    resolved = task_results.resolve_local_session(SOURCE, codex, claude, require_workspace=False)
    assert resolved.path == legacy and resolved.cwd == workspace
