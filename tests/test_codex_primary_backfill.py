"""Production-shaped synthetic fixtures; never read local tool data."""
from datetime import timedelta

import pytest

from agent_monitor import collector
from test_compressed_collector import NOW, encode, homes, records, row, write


def journal(home, rank, session, *, meta=None, stamp=None, compressed=False, raw=None):
    if raw is None:
        events = records(session, **(meta or {}))
        if stamp is not None:
            events[-1]["timestamp"] = collector._iso(stamp)
        raw = encode(events)
    return write(home, f"rank-{rank:03}", raw, compressed=compressed,
                 mtime=NOW - timedelta(seconds=rank))


def observe_windows(monkeypatch):
    requests, full = [], []
    read = collector._read_window

    def observed(path, **options):
        kind = options.get("_codex_candidate")
        if path.name.startswith("rank-"):
            requests.append((path, kind))
        window = read(path, **options)
        if path.name.startswith("rank-"):
            full.append((path, kind))
        return window

    monkeypatch.setattr(collector, "_read_window", observed)
    return requests, full


def test_backfill_only_uses_proven_vacancies_and_keeps_prior_rows(homes, monkeypatch):
    codex, claude = homes
    name = "already-visible"
    old_stamp = NOW - timedelta(seconds=30)
    journal(codex, 0, name, stamp=old_stamp,
            raw=encode([row("session_meta", {"id": name}, old_stamp),
                        row("event_msg", {"type": "task_complete"}, old_stamp)]))
    journal(codex, 1, "child-parent", meta={"parent_thread_id": "synthetic-parent"})
    journal(codex, 2, "child-source", meta={"source": {"subagent": {}}})
    journal(codex, 3, "extra-zstd", compressed=True)
    journal(codex, 4, "not-enough", raw=b'{"type":"session_meta","payload":{"id":"partial"}}')
    journal(codex, 5, "other-child", meta={"parent_thread_id": "synthetic-parent"})
    duplicate = journal(codex, 6, name)
    plain = journal(codex, 7, "recovered")
    journal(codex, 7, "compressed-sibling", compressed=True)
    trailing = journal(codex, 8, "unvisited")
    seen, full = observe_windows(monkeypatch)

    original = collector.collect_snapshot(codex, claude, limit=3)["tasks"]
    assert len(original) == 1
    seen.clear()
    full.clear()
    tasks = collector.collect_snapshot(codex, claude, limit=3, codex_primary_backfill=True)["tasks"]
    assert {task["id"] for task in tasks} == {"codex:already-visible", "codex:recovered"}
    assert original[0] in tasks  # Newer duplicate cannot overwrite the previously visible task.
    assert [path for path, kind in full if kind == "primary"] == [duplicate, plain]
    assert trailing not in {path for path, _ in seen}
    assert not any(path.name.endswith(".zst") for path, _ in seen)
    assert all(task["output"] == "" for task in tasks)


@pytest.mark.parametrize("unknown_heads", (0, collector.CODEX_PRIMARY_LOOKAHEAD))
def test_backfill_has_separate_head_and_reused_window_caps(homes, monkeypatch, unknown_heads):
    codex, claude = homes
    for index in range(50):
        journal(codex, index, f"child-{index}", meta={"parent_thread_id": "synthetic-parent"})
    for index in range(unknown_heads):
        journal(codex, 50 + index, "unverified", raw=b'{"type":"event_msg","payload":{}}\n')
    for index in range(collector.CODEX_PRIMARY_LOOKAHEAD + 1):
        journal(codex, 50 + unknown_heads + index, f"main-{index}")
    seen, full = observe_windows(monkeypatch)

    tasks = collector.collect_snapshot(codex, claude, codex_primary_backfill=True)["tasks"]
    expected_windows = 0 if unknown_heads else collector.CODEX_PRIMARY_BACKFILL_SLOTS
    expected_heads = unknown_heads or collector.CODEX_PRIMARY_BACKFILL_SLOTS
    assert sum(kind == "ordinary" for _, kind in seen) == 50
    assert sum(kind == "ordinary" for _, kind in full) == 0
    assert sum(kind == "primary" for _, kind in seen) == expected_heads
    assert sum(kind == "primary" for _, kind in full) == expected_windows == len(tasks)


def test_legacy_ordinary_and_compressed_child_do_not_vacate_plain_slots(homes, monkeypatch):
    codex, claude = homes
    journal(codex, 0, "legacy", raw=encode([row("event_msg", {"type": "task_complete"})]))
    journal(codex, 1, "compressed-child", compressed=True, meta={"parent_thread_id": "synthetic-parent"})
    journal(codex, 2, "backup-must-not-be-read")
    seen, _ = observe_windows(monkeypatch)
    old = collector.collect_snapshot(codex, claude, limit=2)["tasks"]
    assert len(old) == 1
    assert collector.collect_snapshot(codex, claude, limit=2, codex_primary_backfill=True)["tasks"] == old
    assert not any(kind == "primary" for _, kind in seen)


def test_replaced_backup_is_denied_before_head_bytes(homes, monkeypatch):
    codex, claude = homes
    journal(codex, 0, "child", meta={"parent_thread_id": "synthetic-parent"})
    backup = journal(codex, 1, "backup")
    replacement = codex / "replacement"  # Different file identity and outside the candidate directory.
    replacement.write_bytes(encode(records("unapproved")))
    checked, checked_heads = [], []
    identity = collector._plain_codex_identity
    head = collector._gate_codex_candidate

    def replace(path, base):
        expected = identity(path, base)
        assert path == backup and expected is not None
        replacement.replace(path)
        checked.append(path)
        return expected

    def gate(prefix, *, require_primary):
        checked_heads.append(require_primary)
        return head(prefix, require_primary=require_primary)

    monkeypatch.setattr(collector, "_plain_codex_identity", replace)
    monkeypatch.setattr(collector, "_gate_codex_candidate", gate)
    assert collector.collect_snapshot(codex, claude, limit=1, codex_primary_backfill=True)["tasks"] == []
    assert checked == [backup] and checked_heads == [False]


def test_backfilled_rows_do_not_reduce_original_running_waiting_protection():
    existing = [collector._task("claude", str(i), "Synthetic", "running" if i % 2 else "waiting",
                                NOW - timedelta(seconds=i), "") for i in range(50)]
    extra = collector._task("codex", "extra", "Synthetic", "completed", NOW + timedelta(seconds=1), "")
    items = {task["id"]: task for task in (*existing, extra)}
    assert collector._published_tasks(items, 50, {extra["id"]}) == existing
