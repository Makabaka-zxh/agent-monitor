"""Large synthetic streams only; never open local user logs or databases."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace
from unittest.mock import patch

import pytest
import zstandard

from agent_monitor import collector, compressed_logs as logs
from agent_monitor.task_results import LocalResults


NOW = datetime(2026, 10, 18, 8, tzinfo=timezone.utc)
MIB = 1024 * 1024
PADDING_BYTES = 34 * MIB


def row(kind, payload, stamp=NOW):
    return (json.dumps({"type": kind, "payload": payload, "timestamp": collector._iso(stamp)},
                       ensure_ascii=False).encode("utf-8") + b"\n")


def clear_cache():
    # Existing readers have no cache; the new reader must start each test cold.
    clear = getattr(logs, "cache_clear", None)
    if clear is not None:
        clear()


@pytest.fixture(autouse=True)
def isolate_sources(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_MONITOR_STATE_DIR", str(tmp_path / "state"))
    monkeypatch.setattr(collector, "utc_now", lambda: NOW)
    clear_cache()
    yield
    clear_cache()


def write_large_pair(root, *, identity="synthetic-large-main", parent=None, final=True):
    """Write >32 MiB as bounded raw blocks between checksummed Zstd frames.

    Raw blocks make compressed input exceed the former 8 MiB limit without
    holding an incompressible giant fixture in memory. Each padding row is
    complete JSONL. The frame has no declared content size and a 128 KiB window.
    """
    sessions = root / "sessions"
    sessions.mkdir(parents=True, exist_ok=True)
    ordinary = sessions / "synthetic-large.jsonl"
    packed = sessions / "synthetic-large.jsonl.zst"
    metadata = {"id": identity, "cwd": str(root)}
    if parent is not None:
        metadata["parent_thread_id"] = parent
    prefix = row("session_meta", metadata) + row("event_msg", {"type": "task_started"})
    suffix = row("response_item", {"type": "message", "role": "assistant", "phase": "final",
                 "content": [{"type": "output_text", "text": "Synthetic large final"}]}) if final else b""
    suffix += row("event_msg", {"type": "task_complete"})
    before = b'{"type":"response_item","payload":{"type":"function_call_output","output":"'
    after = b'"}}\n'
    padding = before + b"x" * (64 * 1024 - len(before) - len(after)) + after
    count = PADDING_BYTES // len(padding)
    compressor = zstandard.ZstdCompressor(write_checksum=True)
    with ordinary.open("wb") as plain, packed.open("wb") as compressed:
        plain.write(prefix)
        compressed.write(compressor.compress(prefix))
        compressed.write(b"\x28\xb5\x2f\xfd\x00\x38")
        for index in range(count):
            plain.write(padding)
            compressed.write(((len(padding) << 3) | int(index == count - 1)).to_bytes(3, "little"))
            compressed.write(padding)
        plain.write(suffix)
        compressed.write(compressor.compress(suffix))
    for path in (ordinary, packed):
        os.utime(path, (NOW.timestamp(), NOW.timestamp()))
    assert packed.stat().st_size > 8 * MIB
    assert ordinary.stat().st_size > 32 * MIB
    return ordinary, packed


def write_small(root, name, identity, *, parent=None, stamp=NOW, completed=True):
    sessions = root / "sessions"
    sessions.mkdir(parents=True, exist_ok=True)
    path = sessions / (name + ".jsonl.zst")
    metadata = {"id": identity, "cwd": str(root)}
    if parent is not None:
        metadata["parent_thread_id"] = parent
    raw = row("session_meta", metadata, stamp)
    raw += row("event_msg", {"type": "task_complete" if completed else "turn_failed"}, stamp)
    path.write_bytes(zstandard.ZstdCompressor(write_checksum=True).compress(raw))
    os.utime(path, (stamp.timestamp(), stamp.timestamp()))
    return path


def anonymous_projection(task):
    assert task is not None
    assert all(task.get(key, "") == "" for key in ("output", "final_result_id", "final_result_at"))
    return task["id"], task["updated_at"], task["status"]


def test_large_real_frame_window_and_parser_match_plain_with_bounded_buffers(tmp_path):
    ordinary, packed = write_large_pair(tmp_path / "codex")
    original_append = logs._Window.append
    retained = []

    def append(window, data):
        assert len(data) <= logs.MAX_BLOCK_BYTES
        result = original_append(window, data)
        assert len(window.head) <= window.head_bytes
        assert len(window.tail) <= window.max_bytes
        retained.append(len(window.head) + len(window.tail))
        return result

    with patch.object(logs._Window, "append", append):
        assert collector._read_window(packed) == collector._read_window(ordinary)
    assert max(retained) <= collector.HEAD_BYTES + collector.MAX_LOG_BYTES
    clear_cache()
    with patch.object(logs, "read_zstd_window", wraps=logs.read_zstd_window) as reader:
        actual = collector.parse_codex_session(packed, now=NOW)
    assert reader.call_count == 1
    assert actual == collector.parse_codex_session(ordinary, now=NOW)
    assert actual["status"] == "completed"


@pytest.mark.parametrize("constant,limit,reason", [
    ("MAX_COMPRESSED_BYTES", 8 * MIB, "compressed_limit"),
    ("MAX_DECODED_BYTES", 32 * MIB, "decoded_limit"),
])
def test_same_large_stream_exceeds_old_budget_but_new_default_completes(tmp_path, constant, limit, reason):
    _, packed = write_large_pair(tmp_path / "codex")
    with patch.object(logs, constant, limit):
        with pytest.raises(logs.ZstdReadError) as caught:
            logs.read_zstd_window(packed, head_bytes=collector.HEAD_BYTES, max_bytes=collector.MAX_LOG_BYTES)
        assert caught.value.reason == reason
        diagnostics = {}
        task = collector.parse_codex_session(packed, now=NOW, include_output=True, diagnostics=diagnostics)
    assert diagnostics == {"compressed_incomplete": 1}
    assert task["id"] == "codex:synthetic-large-main" and task["status"] == "unknown"
    anonymous_projection(task)
    clear_cache()
    task = collector.parse_codex_session(packed, now=NOW)
    assert task["status"] == "completed"


@pytest.mark.parametrize("damage", ["checksum", "truncate"])
def test_damaged_large_tail_cannot_reuse_completed_state_or_final(tmp_path, damage):
    codex = tmp_path / "codex"
    _, packed = write_large_pair(codex)
    local = LocalResults(codex_home=codex, claude_home=tmp_path / "no-claude")
    with patch.object(collector, "_recent_files", return_value=([packed], 1, False)):
        assert local.read("codex:synthetic-large-main")["text"] == "Synthetic large final"
    assert collector.parse_codex_session(packed, now=NOW)["status"] == "completed"
    with packed.open("r+b") as stream:
        stream.seek(-1, os.SEEK_END)
        if damage == "checksum":
            last = stream.read(1)
            stream.seek(-1, os.SEEK_END)
            stream.write(bytes([last[0] ^ 1]))
        else:
            stream.truncate()
    stamp = NOW.timestamp() + 1
    os.utime(packed, (stamp, stamp))
    diagnostics = {}
    task = collector.parse_codex_session(packed, now=NOW, include_output=True, diagnostics=diagnostics)
    assert diagnostics == {"compressed_incomplete": 1}
    assert task["status"] == "unknown"
    anonymous_projection(task)
    # Resolve only the synthetic compressed source; its plain comparison file
    # otherwise correctly wins the production discovery sibling rule.
    with patch.object(collector, "_recent_files", return_value=([packed], 1, False)):
        assert local.read("codex:synthetic-large-main") is None


def test_large_subagent_is_excluded_after_complete_read_and_budget_failure(tmp_path):
    _, packed = write_large_pair(tmp_path / "codex", identity="synthetic-child", parent="synthetic-parent")
    assert collector.parse_codex_session(packed, now=NOW) is None
    clear_cache()
    with patch.object(logs, "MAX_COMPRESSED_BYTES", 8 * MIB):
        assert collector.parse_codex_session(packed, now=NOW) is None


def test_actual_parser_and_collect_snapshot_merge_repeated_large_sources(tmp_path):
    codex, claude = tmp_path / "codex", tmp_path / "no-claude"
    _, packed = write_large_pair(codex)
    other = write_small(codex, "synthetic-other", "synthetic-other")
    newer = write_small(codex, "synthetic-newer-segment", "synthetic-large-main",
                        stamp=NOW + timedelta(seconds=1), completed=False)
    expected = dict((task[0], task[1:]) for task in (
        anonymous_projection(collector.parse_codex_session(other, now=NOW)),
        anonymous_projection(collector.parse_codex_session(newer, now=NOW))))
    for reverse in (False, True):
        clear_cache()
        selected = [packed, other, packed, newer, other, newer]
        if reverse:
            selected.reverse()

        def recent(base, limit, *, claude=False):
            assert limit == 50
            assert base == (tmp_path / "no-claude/projects" if claude else codex / "sessions")
            return ([], 0, False) if claude else (selected, len(selected), False)

        # This is the real parser + reader + merge. Only external discovery,
        # index titles and hooks are replaced, never parse_codex_session.
        with patch.object(collector, "_recent_files", recent), \
             patch.object(collector, "_codex_titles", return_value={}), \
             patch.object(collector, "_hook_tasks", return_value=[]):
            tasks = collector.collect_snapshot(codex, claude, include_output=False, limit=50)["tasks"]
        actual = [anonymous_projection(task) for task in tasks]
        assert len(actual) == len({task[0] for task in actual}) == len(expected)
        assert {task[0]: task[1:] for task in actual} == expected


def test_large_source_keeps_fifty_candidate_cap_and_rejected_children_are_not_refilled(tmp_path):
    codex = tmp_path / "codex"
    ordinary, packed = write_large_pair(codex)
    # Keep the large compressed path as the sole form in this synthetic case.
    ordinary.unlink()
    for index in range(49):
        write_small(codex, f"synthetic-child-{index:02}", f"synthetic-child-{index:02}", parent="synthetic-parent")
    older = write_small(codex, "synthetic-older-main", "synthetic-older-main", stamp=NOW - timedelta(days=1))
    selected, count, capped = collector._recent_files(codex / "sessions", 50)
    assert len(selected) == 50 and count == 51 and not capped
    assert packed in selected and older not in selected
    tasks = collector.collect_snapshot(codex, tmp_path / "no-claude")["tasks"]
    assert [task["id"] for task in tasks] == ["codex:synthetic-large-main"]


def test_file_deadline_checked_after_bounded_decode_and_failure_not_cached(tmp_path):
    path = write_small(tmp_path / "codex", "deadline", "synthetic-deadline")
    clock = [0.0]
    original_append = logs._Window.append

    def append(window, data):
        original_append(window, data)
        clock[0] = logs.MAX_FILE_SECONDS + 1

    with patch.object(logs.time, "monotonic", side_effect=lambda: clock[0]), \
         patch.object(logs._Window, "append", append):
        with pytest.raises(logs.ZstdReadError) as caught:
            logs.read_zstd_window(path, head_bytes=64, max_bytes=1024)
    assert caught.value.reason == "time_limit"
    assert not hasattr(caught.value, "tail")
    assert logs.cache_info()["entries"] == 0
    assert logs.read_zstd_window(path, head_bytes=64, max_bytes=1024)[0]
    assert logs.cache_info()["decodes"] == 2


@pytest.mark.parametrize("limit_kind,reason", [
    ("compressed", "scan_input_limit"), ("decoded", "scan_decoded_limit"),
])
def test_shared_budget_accumulates_across_files_and_prefix_has_separate_allowance(tmp_path, limit_kind, reason):
    first = write_small(tmp_path / "codex", "first", "synthetic-first")
    second = write_small(tmp_path / "codex", "second", "synthetic-second")
    raw = zstandard.ZstdDecompressor().decompress(first.read_bytes())
    arguments = {"compressed_limit": 1024 * 1024, "decoded_limit": 1024 * 1024, "seconds": 10}
    arguments[limit_kind + "_limit"] = first.stat().st_size if limit_kind == "compressed" else len(raw)
    budget = logs.ScanBudget(**arguments)
    with logs.collection_budget(budget):
        assert logs.read_zstd_window(first, head_bytes=64, max_bytes=1024)[0] == raw
        with pytest.raises(logs.ZstdReadError) as caught:
            logs.read_zstd_window(second, head_bytes=64, max_bytes=1024)
        assert caught.value.reason == reason
        # A spent full-scan allowance must not prevent bounded identification.
        assert logs.read_zstd_head(second, 64).startswith(b'{"type": "session_meta"')
    assert budget.compressed_used <= budget.compressed_limit
    assert budget.decoded_used <= budget.decoded_limit
    assert logs.cache_info()["entries"] == 1


def test_scan_deadline_spans_multiple_reads_and_context_is_reset(tmp_path):
    first = write_small(tmp_path / "codex", "first", "synthetic-first")
    second = write_small(tmp_path / "codex", "second", "synthetic-second")
    clock = [0.0]
    with patch.object(logs.time, "monotonic", side_effect=lambda: clock[0]):
        budget = logs.ScanBudget(seconds=1)
        with logs.collection_budget(budget):
            logs.read_zstd_window(first, head_bytes=64, max_bytes=1024)
            clock[0] = 2.0
            with pytest.raises(logs.ZstdReadError) as caught:
                logs.read_zstd_window(second, head_bytes=64, max_bytes=1024)
            assert caught.value.reason == "scan_time_limit"
            assert logs.read_zstd_head(second, 64)
        assert logs.read_zstd_window(second, head_bytes=64, max_bytes=1024)[0]


def test_collect_snapshot_spent_budget_preserves_remaining_main_identity_without_backfill(tmp_path):
    codex, claude = tmp_path / "codex", tmp_path / "no-claude"
    first = write_small(codex, "first", "synthetic-first")
    second = write_small(codex, "second", "synthetic-second")
    child = write_small(codex, "child", "synthetic-child", parent="synthetic-parent")
    unselected = write_small(codex, "older", "synthetic-older", stamp=NOW - timedelta(days=1))
    selected = [first, second, child]

    def recent(base, limit, *, claude=False):
        assert limit == 50
        return ([], 0, False) if claude else (selected, 4, False)

    with patch.object(logs, "MAX_SCAN_COMPRESSED_BYTES", first.stat().st_size), \
         patch.object(collector, "_recent_files", recent), \
         patch.object(collector, "_codex_titles", return_value={}), \
         patch.object(collector, "_hook_tasks", return_value=[]):
        tasks = collector.collect_snapshot(codex, claude, include_output=False)["tasks"]
    assert {task["id"]: task["status"] for task in tasks} == {
        "codex:synthetic-first": "completed", "codex:synthetic-second": "unknown"}
    assert unselected not in selected
    assert all(anonymous_projection(task) for task in tasks)


def test_prefix_limits_are_independent_of_large_full_read_limits(tmp_path):
    path = tmp_path / "prefix.jsonl.zst"
    skip = (0x184D2A50).to_bytes(4, "little") + (300 * 1024).to_bytes(4, "little")
    with path.open("wb") as stream:
        stream.write(skip)
        for _ in range(300):
            stream.write(b"x" * 1024)
        stream.write(zstandard.ZstdCompressor(write_checksum=True).compress(b"synthetic\n"))
    with pytest.raises(logs.ZstdReadError) as caught:
        logs.read_zstd_head(path, 64)
    assert caught.value.reason == "compressed_limit"
    assert logs.read_zstd_window(path, head_bytes=64, max_bytes=1024) == (b"synthetic\n", b"synthetic\n", False)
    assert logs.cache_info()["prefix_decodes"] == 1


def test_verified_cache_avoids_redecode_but_reopens_and_checks_source(tmp_path):
    path = write_small(tmp_path / "codex", "cached", "synthetic-cache")
    original = logs.read_zstd_window(path, head_bytes=64, max_bytes=1024)
    with patch.object(logs, "_decode", side_effect=AssertionError("cached stream was decoded")), \
         patch.object(logs.os, "fstat", wraps=os.fstat) as descriptor_stat:
        assert logs.read_zstd_window(path, head_bytes=64, max_bytes=1024) == original
    assert descriptor_stat.call_count >= 2
    assert logs.cache_info()["decodes"] == logs.cache_info()["hits"] == 1


@pytest.mark.parametrize("byte_limit,entry_limit,expected_entries", [(45, 8, 1), (1024, 2, 2)])
def test_verified_cache_has_byte_and_entry_caps_and_evicts_oldest(tmp_path, byte_limit, entry_limit, expected_entries):
    paths = []
    with patch.object(logs, "MAX_CACHE_BYTES", byte_limit), patch.object(logs, "MAX_CACHE_ENTRIES", entry_limit):
        for index in range(3):
            path = tmp_path / f"cache-{index}.jsonl.zst"
            path.write_bytes(zstandard.ZstdCompressor(write_checksum=True).compress(bytes([65 + index]) * 24))
            paths.append(path)
            logs.read_zstd_window(path, head_bytes=16, max_bytes=64)
        info = logs.cache_info()
        assert info["entries"] == expected_entries
        assert info["bytes"] <= byte_limit
        logs.read_zstd_window(paths[0], head_bytes=16, max_bytes=64)
        assert logs.cache_info()["decodes"] == info["decodes"] + 1
        assert logs.cache_info()["bytes"] <= byte_limit


def test_warm_cache_cannot_bypass_reparse_rejection(tmp_path):
    path = write_small(tmp_path / "codex", "safe", "synthetic-safe")
    logs.read_zstd_window(path, head_bytes=64, max_bytes=1024)
    original_lstat = Path.lstat

    def lstat(target, *args, **kwargs):
        if target == path:
            return SimpleNamespace(st_mode=stat.S_IFREG, st_file_attributes=0x400)
        return original_lstat(target, *args, **kwargs)

    with patch.object(Path, "lstat", lstat), patch.object(Path, "open", side_effect=AssertionError("unsafe open")):
        with pytest.raises(logs.ZstdReadError) as caught:
            logs.read_zstd_window(path, head_bytes=64, max_bytes=1024)
    assert (caught.value.reason, caught.value.head) == ("unsafe_source", b"")
    assert logs.cache_info()["entries"] == 0


def test_same_size_rewrite_with_restored_mtime_checks_descriptor_change_time(tmp_path):
    path = tmp_path / "rewritten.jsonl.zst"

    def frame(raw):
        return b"\x28\xb5\x2f\xfd\x00\x38" + ((len(raw) << 3) | 1).to_bytes(3, "little") + raw

    path.write_bytes(frame(b"first synthetic payload\n"))
    first = logs.read_zstd_window(path, head_bytes=16, max_bytes=64)
    named_before = path.lstat()
    with path.open("rb") as stream:
        fd_before = os.fstat(stream.fileno())
    path.write_bytes(frame(b"other synthetic payload\n"))
    os.utime(path, ns=(named_before.st_atime_ns, named_before.st_mtime_ns))
    assert path.stat().st_size == named_before.st_size
    original_lstat, original_fstat = Path.lstat, os.fstat

    def lstat(target, *args, **kwargs):
        # Model Windows birth time: named metadata is unchanged by this rewrite.
        return named_before if target == path else original_lstat(target, *args, **kwargs)

    def fstat(descriptor):
        info = original_fstat(descriptor)
        return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=getattr(info, "st_file_attributes", 0),
                               st_dev=info.st_dev, st_ino=info.st_ino, st_size=info.st_size,
                               st_mtime_ns=info.st_mtime_ns, st_ctime_ns=fd_before.st_ctime_ns + 1)

    with patch.object(Path, "lstat", lstat), patch.object(logs.os, "fstat", fstat):
        actual = logs.read_zstd_window(path, head_bytes=16, max_bytes=64)
    assert actual == (b"other synthetic payload\n", b"other synthetic payload\n", False)
    assert actual != first and logs.cache_info()["decodes"] == 2


def test_cached_bytes_recalculate_lifecycle_status_using_current_time(tmp_path):
    path = tmp_path / "status.jsonl.zst"
    raw = row("session_meta", {"id": "synthetic-active", "cwd": str(tmp_path)})
    raw += row("event_msg", {"type": "task_started"})
    path.write_bytes(zstandard.ZstdCompressor(write_checksum=True).compress(raw))
    old = (NOW - timedelta(days=1)).timestamp()
    os.utime(path, (old, old))  # Both calls use the ordinary retained window.
    first = collector.parse_codex_session(path, now=NOW)
    later = collector.parse_codex_session(path, now=NOW + timedelta(minutes=10))
    assert first["status"] == "running" and later["status"] == "unknown"
    assert first["id"] == later["id"] and first["updated_at"] == later["updated_at"]
    assert logs.cache_info()["decodes"] == logs.cache_info()["hits"] == 1
