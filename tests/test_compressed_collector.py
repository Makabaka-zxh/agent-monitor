"""Synthetic compressed sessions only; never inspect local user histories."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
from unittest.mock import patch

import pytest
import zstandard

from agent_monitor import collector, compressed_logs
from agent_monitor.task_results import LocalResults, resolve_local_session


NOW = datetime(2026, 10, 17, 8, tzinfo=timezone.utc)


def row(kind, payload, stamp=None):
    return {"type": kind, "payload": payload, "timestamp": collector._iso(stamp or NOW)}


def records(session="synthetic-main", **meta):
    return [row("session_meta", {"id": session, "cwd": "/synthetic/project", **meta}),
            row("event_msg", {"type": "task_started"}),
            row("event_msg", {"type": "task_complete"})]


def encode(items):
    return b"".join(json.dumps(item, ensure_ascii=False).encode() + b"\n" for item in items)


def write(home, name, raw, *, compressed=True, mtime=None):
    path = home / "sessions" / (name + (".jsonl.zst" if compressed else ".jsonl"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(zstandard.ZstdCompressor(write_checksum=True).compress(raw) if compressed else raw)
    stamp = (mtime or NOW).timestamp()
    os.utime(path, (stamp, stamp))
    return path


@pytest.fixture
def homes(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_MONITOR_STATE_DIR", str(tmp_path / "state"))
    monkeypatch.setattr(collector, "utc_now", lambda: NOW)
    return tmp_path / "codex", tmp_path / "claude"


def test_compressed_main_keeps_index_title_and_no_private_content(homes):
    codex, claude = homes
    data = records()
    data.insert(1, row("event_msg", {"type": "user_message", "message": "synthetic private prompt"}))
    data.insert(2, row("response_item", {"type": "function_call_output", "output": "synthetic secret"}))
    write(codex, "different-file-id", encode(data))
    (codex / "session_index.jsonl").write_text(json.dumps({"id": "synthetic-main", "thread_name": "Monitor agent"}) + "\n")
    snapshot = collector.collect_snapshot(codex, claude)
    assert len(snapshot["tasks"]) == 1
    task = snapshot["tasks"][0]
    assert task["id"] == "codex:synthetic-main" and task["title"] == "Monitor agent"
    assert task["status"] == "completed" and task["output"] == ""
    assert "synthetic private" not in json.dumps(snapshot) and "synthetic secret" not in json.dumps(snapshot)


@pytest.mark.parametrize("meta", [{"parent_thread_id": "synthetic-parent"}, {"source": {"subagent": "synthetic"}}])
def test_compressed_subagents_remain_excluded(homes, meta):
    codex, claude = homes
    write(codex, "child", encode(records("synthetic-child", **meta)))
    write(codex, "main", encode(records()))
    assert [t["id"] for t in collector.collect_snapshot(codex, claude)["tasks"]] == ["codex:synthetic-main"]


def test_pairs_do_not_hide_fiftieth_main_and_logical_segments_do_not_duplicate(homes):
    codex, claude = homes
    for index in range(50):
        raw = encode(records(f"main-{index}"))
        write(codex, f"file-{index:02}", raw, compressed=False)
        write(codex, f"file-{index:02}", raw)
    snapshot = collector.collect_snapshot(codex, claude)
    assert {t["id"] for t in snapshot["tasks"]} == {f"codex:main-{i}" for i in range(50)}
    # An additional physical segment consumes a candidate, but not a task ID.
    later = records("main-49")
    later[-1] = row("event_msg", {"type": "turn_failed"}, NOW + timedelta(seconds=1))
    write(codex, "latest-segment", encode(later), mtime=NOW + timedelta(seconds=1))
    tasks = collector.collect_snapshot(codex, claude)["tasks"]
    assert len(tasks) == 49 and len({t["id"] for t in tasks}) == 49
    assert next(t for t in tasks if t["id"] == "codex:main-49")["status"] == "error"


def test_recent_fifty_still_include_subagents_without_backfill(homes):
    codex, claude = homes
    for index in range(50):
        write(codex, f"child-{index}", encode(records(f"child-{index}", parent_thread_id="synthetic-parent")), compressed=index % 2 == 0)
    write(codex, "older-main", encode(records()), mtime=NOW - timedelta(days=1))
    assert collector.collect_snapshot(codex, claude)["tasks"] == []


def test_final_merged_limit_and_zero_limit_remain(homes):
    codex, claude = homes
    for index in range(50):
        write(codex, f"main-{index}", encode(records(f"codex-main-{index}")))
    project = claude / "projects" / "synthetic"
    project.mkdir(parents=True)
    for index in range(10):
        (project / f"main-{index}.jsonl").write_bytes(encode([{"sessionId": f"claude-main-{index}", "type": "custom-title",
                                                             "customTitle": "Synthetic", "timestamp": collector._iso(NOW)}]))
    assert len(collector.collect_snapshot(codex, claude, limit=100)["tasks"]) == 50
    assert collector.collect_snapshot(codex, claude, limit=0)["tasks"] == []


def test_plain_priority_and_compression_transition_keep_one_identity(homes):
    codex, claude = homes
    raw = encode(records())
    ordinary = write(codex, "main", raw, compressed=False)
    before = collector.collect_snapshot(codex, claude)["tasks"]
    compressed = write(codex, "main", raw)
    assert collector.collect_snapshot(codex, claude)["tasks"] == before
    ordinary.unlink()  # Synthetic transition only.
    assert collector.collect_snapshot(codex, claude)["tasks"] == before
    write(codex, "main", raw, compressed=False)
    compressed.write_bytes(b"invalid synthetic compressed copy")
    assert collector.collect_snapshot(codex, claude)["tasks"] == before


@pytest.mark.parametrize("padding", [0, 280_000, 1_100_000])
@pytest.mark.parametrize("suffix", [b"", b'{"type":"unfinished"'])
def test_decoded_head_tail_and_status_equal_plain(homes, padding, suffix):
    codex, _ = homes
    raw = encode([records()[0], row("event_msg", {"type": "task_started"}),
                  row("response_item", {"type": "function_call_output", "output": "中" * padding}),
                  row("event_msg", {"type": "task_complete"})]) + suffix
    ordinary = write(codex, "main", raw, compressed=False)
    compressed = write(codex, "main", raw)
    assert collector._read_window(ordinary) == collector._read_window(compressed)
    assert collector.parse_codex_session(ordinary, now=NOW) == collector.parse_codex_session(compressed, now=NOW)


def test_compressed_recent_recovery_decodes_once(homes):
    codex, _ = homes
    raw = encode([records()[0], row("event_msg", {"type": "task_started"}),
                  row("response_item", {"type": "function_call_output", "output": "x" * 560_000})])
    compressed = write(codex, "recovery", raw)
    with patch.object(compressed_logs, "read_zstd_window", wraps=compressed_logs.read_zstd_window) as reader:
        task = collector.parse_codex_session(compressed, now=NOW)
    assert task["status"] == "running"
    assert reader.call_count == 1


def test_incomplete_compressed_main_is_unknown_not_completed_or_output(homes):
    codex, claude = homes
    raw = encode(records())
    path = write(codex, "main", raw)
    path.write_bytes(path.read_bytes()[:-1])
    tasks = collector.collect_snapshot(codex, claude, include_output=True)
    assert len(tasks["tasks"]) == 1
    assert tasks["tasks"][0]["status"] == "unknown"
    assert tasks["tasks"][0]["output"] == ""
    assert "1 个压缩日志未能完整读取" in tasks["sources"][0]["detail"]
    assert str(path) not in json.dumps(tasks["sources"])


def test_failed_prefix_cannot_promote_subagent_or_invent_main(homes):
    codex, claude = homes
    for name, raw in [("child", encode(records("synthetic-child", parent_thread_id="synthetic-parent"))),
                      ("no-meta", encode(records()[1:]))]:
        path = write(codex, name, raw)
        path.write_bytes(path.read_bytes()[:-1])
    assert collector.collect_snapshot(codex, claude)["tasks"] == []


def test_work_budget_retains_main_as_unknown_without_second_decode(homes):
    codex, claude = homes
    raw = encode([*records(), row("response_item", {"type": "function_call_output", "output": "x" * 600_000})])
    write(codex, "main", raw)
    with patch.object(compressed_logs, "MAX_DECODED_BYTES", 256 * 1024):
        result = collector.collect_snapshot(codex, claude)
    assert len(result["tasks"]) == 1 and result["tasks"][0]["status"] == "unknown"


def test_compressed_task_result_resolves_metadata_and_final(homes, tmp_path):
    codex, claude = homes
    data = records(cwd=str(tmp_path))
    data.insert(-1, row("response_item", {"type": "message", "role": "assistant", "phase": "final",
                                        "content": [{"type": "output_text", "text": "Synthetic final"}]}))
    path = write(codex, "different-name", encode(data))
    resolved = resolve_local_session("codex:synthetic-main", codex, claude)
    assert resolved is not None and resolved.path == path
    result = LocalResults(codex_home=codex, claude_home=claude).read("codex:synthetic-main")
    assert result["text"] == "Synthetic final" and result["files"] == []
    path.write_bytes(path.read_bytes()[:-1])
    assert LocalResults(codex_home=codex, claude_home=claude).read("codex:synthetic-main") is None


def test_recompressed_growth_does_not_keep_a_cached_old_final(homes, tmp_path):
    codex, claude = homes
    data = records(cwd=str(tmp_path))
    data.insert(-1, row("response_item", {"type": "message", "role": "assistant", "phase": "final",
                                        "content": [{"type": "output_text", "text": "Synthetic old final"}]}))
    path = write(codex, "main", encode(data))
    local = LocalResults(codex_home=codex, claude_home=claude)
    assert local.read("codex:synthetic-main")["text"] == "Synthetic old final"
    before = path.stat()
    replacement = encode([records(cwd=str(tmp_path))[0],
                          row("event_msg", {"type": "task_started"}),
                          row("response_item", {"type": "function_call_output", "output": list(range(2000))})])
    path.write_bytes(zstandard.ZstdCompressor().compress(replacement))
    assert path.stat().st_ino == before.st_ino and path.stat().st_size > before.st_size
    assert local.read("codex:synthetic-main") is None
