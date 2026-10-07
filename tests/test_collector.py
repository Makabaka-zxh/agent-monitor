import contextlib
import io
import json
import os
import stat
import hashlib
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from agent_monitor import collector, claude_hook, install_claude_hooks


class RecentFilesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def file(self, relative, modified):
        path = self.base / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{}\n", encoding="utf-8")
        os.utime(path, (modified, modified))
        return path

    def test_recent_fifty_sorted_by_mtime_and_path_and_zero_limit(self):
        paths = [self.file(f"day/session-{index:03}.jsonl", 1000 + index // 2) for index in range(62)]
        self.file("day/ignored.txt", 2000)
        (self.base / "empty.jsonl").mkdir()
        expected = sorted(paths, key=lambda path: (path.stat().st_mtime, str(path)), reverse=True)[:50]
        self.assertEqual(collector._recent_files(self.base, 50), (expected, 62, False))
        self.assertEqual(collector._recent_files(self.base, 0), ([], 62, False))

    def test_claude_stops_at_project_level_but_codex_remains_recursive(self):
        root = self.file("root.jsonl", 1000)
        project = self.file("project/session.jsonl", 1001)
        child = self.file("project/session/subagents/child.jsonl", 1002)
        self.assertEqual(collector._recent_files(self.base, 50, claude=True), ([project, root], 2, False))
        self.assertEqual(collector._recent_files(self.base, 50), ([child, project, root], 3, False))

    def test_unchanged_discovery_never_caches_append_replace_truncate_or_delete(self):
        first = self.file("first.jsonl", 1000)
        second = self.file("second.jsonl", 1001)
        self.assertEqual(collector._recent_files(self.base, 1)[0], [second])
        with first.open("ab") as handle:
            handle.write(b"{}\n")
        os.utime(first, (1002, 1002))
        self.assertEqual(collector._recent_files(self.base, 1)[0], [first])
        first.write_bytes(b"")
        os.utime(first, (1003, 1003))
        self.assertEqual(collector._recent_files(self.base, 50), ([first, second], 2, False))
        replacement = self.file("replacement.tmp", 1004)
        replacement.replace(second)
        self.assertEqual(collector._recent_files(self.base, 1)[0], [second])
        second.unlink()
        self.assertEqual(collector._recent_files(self.base, 50), ([first], 1, False))
        first.unlink()
        self.assertEqual(collector._recent_files(self.base, 50), ([], 0, False))

    def entry(self, name, *, directory=False, link=False, reparse=False, broken=False, modified=1000):
        result = SimpleNamespace(name=name, path=str(self.base / name), stat_calls=0)
        result.is_symlink = lambda: link
        result.is_dir = lambda *, follow_symlinks: directory
        def metadata(*, follow_symlinks):
            self.assertFalse(follow_symlinks)
            result.stat_calls += 1
            if link:
                raise AssertionError("A symlink must be discarded before querying its target")
            if broken:
                raise FileNotFoundError("Synthetic file disappeared")
            return SimpleNamespace(st_mode=stat.S_IFDIR if directory else stat.S_IFREG,
                                   st_file_attributes=0x400 if reparse else 0, st_mtime=modified)
        result.stat = metadata
        return result

    def test_entries_reuse_metadata_and_isolate_links_reparse_and_disappearing_files(self):
        entries = [self.entry("ordinary.jsonl"), self.entry("gone.jsonl", broken=True),
                   self.entry("file-link.jsonl", link=True), self.entry("dir-link", directory=True, link=True),
                   self.entry("file-reparse.jsonl", reparse=True), self.entry("junction", directory=True, reparse=True),
                   self.entry("ignored.txt")]
        with patch.object(collector.os, "scandir", return_value=contextlib.nullcontext(iter(entries))) as scan:
            with patch.object(Path, "is_file", side_effect=AssertionError("Must use DirEntry metadata")):
                self.assertEqual(collector._recent_files(self.base, 50), ([self.base / "ordinary.jsonl"], 1, False))
        self.assertEqual(scan.call_count, 1)
        self.assertEqual([entry.stat_calls for entry in entries], [1, 1, 0, 0, 1, 1, 0])

    def test_twenty_thousand_cap_closes_enumerator_without_reading_more_entries(self):
        consumed, closed = [], []
        def entries():
            for index in range(collector.MAX_DISCOVERED_FILES + 1):
                consumed.append(index)
                yield self.entry(f"session-{index:05}.jsonl", modified=index)
        @contextlib.contextmanager
        def scan(directory):
            try:
                yield entries()
            finally:
                closed.append(directory)
        with patch.object(collector.os, "scandir", scan):
            files, count, capped = collector._recent_files(self.base, 50)
        self.assertEqual(count, 20_000)
        self.assertTrue(capped)
        self.assertEqual(len(consumed), 20_000)
        self.assertEqual(closed, [self.base])
        self.assertEqual([path.name for path in files], [f"session-{index:05}.jsonl" for index in range(19999, 19949, -1)])

    def test_inaccessible_directory_does_not_hide_its_siblings(self):
        (self.base / "blocked").mkdir()
        expected = self.file("readable/session.jsonl", 1000)
        real_scan = os.scandir
        def scan(directory):
            if Path(directory).name == "blocked":
                raise PermissionError("Synthetic denied directory")
            return real_scan(directory)
        with patch.object(collector.os, "scandir", scan):
            self.assertEqual(collector._recent_files(self.base, 50), ([expected], 1, False))

    def test_root_and_queued_directory_reparse_points_are_not_entered(self):
        reparse = SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400)
        with patch.object(Path, "lstat", return_value=reparse), patch.object(collector.os, "scandir") as scan:
            self.assertEqual(collector._recent_files(self.base, 50), ([], 0, False))
            scan.assert_not_called()
        real_lstat = Path.lstat
        def lstat(path):
            return reparse if path.name == "changed-to-link" else real_lstat(path)
        with patch.object(Path, "lstat", lstat), patch.object(collector.os, "scandir",
                return_value=contextlib.nullcontext(iter([self.entry("changed-to-link", directory=True)]))) as scan:
            self.assertEqual(collector._recent_files(self.base, 50), ([], 0, False))
            self.assertEqual(scan.call_count, 1)


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.codex = self.base / "codex"
        self.claude = self.base / "claude"
        self.state = self.base / "state"
        self.now = datetime(2026, 9, 10, 12, 0, tzinfo=timezone.utc)
        self.env = patch.dict(os.environ, {"AGENT_MONITOR_STATE_DIR": str(self.state)})
        self.env.start()
        self.addCleanup(self.env.stop)

    def line(self, kind, payload, stamp=None):
        return {"timestamp": collector._iso(stamp or self.now), "type": kind, "payload": payload}

    def write(self, records, *, tool="codex", session="test-session", suffix=b""):
        path = (self.codex / "sessions" / "2026" / "09" / "10" / f"{session}.jsonl"
                if tool == "codex" else self.claude / "projects" / "demo" / f"{session}.jsonl")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"".join(json.dumps(r).encode() + b"\n" for r in records) + suffix)
        return path

    def meta(self, session="test-session", **extra):
        return self.line("session_meta", {"id": session, "cwd": "C:\\private\\demo", **extra})

    def test_assistant_prose_does_not_complete_a_running_turn(self):
        path = self.write([self.meta(), self.line("event_msg", {"type": "task_started"}),
                           self.line("response_item", {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Everything completed successfully"}]})])
        task = collector.parse_codex_session(path, now=self.now)
        self.assertEqual(task["status"], "running")
        self.assertEqual(task["output"], "")
        self.assertNotIn("Everything", task["preview"])

    def test_explicit_end_of_turn_and_new_user_invalidates_it(self):
        records = [self.meta(), self.line("event_msg", {"type": "task_complete"})]
        task = collector.parse_codex_session(self.write(records), now=self.now)
        self.assertEqual(task["status"], "completed")
        self.assertEqual(task["preview"], "本轮已结束")
        records.append(self.line("event_msg", {"type": "user_message", "message": "next request"}))
        self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "unknown")

    def test_completion_failure_is_not_reported_as_success(self):
        failures = ({"error": {"code": "other", "http_status": 401}},
                    {"error": "authentication_failed"}, {"status": "failed"},
                    {"status": "completed", "error": {"code": "other"}})
        for kind in ("task_complete", "turn_complete"):
            for failure in failures:
                with self.subTest(kind=kind, failure=failure):
                    records = [self.meta(), self.line("event_msg", {"type": "task_started", "turn_id": "one"}),
                               self.line("event_msg", {"type": "item_completed", "turn_id": "one"}),
                               self.line("event_msg", {"type": kind, "turn_id": "one",
                                         "last_agent_message": None, **failure})]
                    task = collector.parse_codex_session(self.write(records), now=self.now)
                    self.assertEqual(task["status"], "error")
                    self.assertEqual(task["preview"], "本轮遇到错误")

    def test_completion_without_failure_keeps_legacy_compatibility(self):
        for kind in ("task_complete", "turn_complete"):
            for details in ({}, {"error": None}, {"error": {}}, {"error": ""},
                            {"status": "completed", "error": None}):
                with self.subTest(kind=kind, details=details):
                    task = collector.parse_codex_session(self.write([
                        self.meta(), self.line("event_msg", {"type": kind, **details})]), now=self.now)
                    self.assertEqual(task["status"], "completed")

    def test_new_turn_recovers_after_failed_completion(self):
        for kind in ("task_complete", "turn_complete"):
            with self.subTest(kind=kind):
                records = [self.meta(), self.line("event_msg", {"type": kind, "turn_id": "one",
                           "error": {"code": "other"}})]
                self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "error")
                records.append(self.line("event_msg", {"type": "task_started", "turn_id": "two"}))
                self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "running")
                records.append(self.line("event_msg", {"type": kind, "turn_id": "two"}))
                self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "completed")

    def test_old_running_and_waiting_evidence_becomes_unknown(self):
        for kind in ("task_started", "exec_approval_request"):
            with self.subTest(kind=kind):
                path = self.write([self.meta(), self.line("event_msg", {"type": kind}, self.now - timedelta(minutes=6))])
                # A freshly modified file is not evidence of a live worker.
                os.utime(path, None)
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")

    def test_repeated_snapshot_recomputes_expiry_and_observes_new_final_and_replacement(self):
        path = self.write([self.meta(), self.line("event_msg", {"type": "task_started"})])
        for elapsed, expected in ((0, "running"), (301, "unknown")):
            with patch.object(collector, "utc_now", return_value=self.now + timedelta(seconds=elapsed)):
                self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], expected)
        with path.open("ab") as handle:
            handle.write(json.dumps(self.line("event_msg", {"type": "task_complete", "last_agent_message": "Synthetic final"})).encode() + b"\n")
        task = collector.collect_snapshot(self.codex, self.claude, include_output=True)["tasks"][0]
        self.assertEqual((task["status"], task["output"]), ("completed", "Synthetic final"))
        self.assertEqual(collector.collect_snapshot(self.codex, self.claude, include_output=False)["tasks"][0]["output"], "")
        self.write([self.meta(), self.line("event_msg", {"type": "exec_approval_request"})])
        for elapsed, expected in ((0, "waiting"), (301, "unknown")):
            with patch.object(collector, "utc_now", return_value=self.now + timedelta(seconds=elapsed)):
                self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], expected)
        path.unlink()
        self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"], [])

    def test_truncated_tail_does_not_change_state(self):
        path = self.write([self.meta(), self.line("event_msg", {"type": "task_started"})],
                          suffix=b'{"type":"event_msg","payload":{"type":"task_complete"')
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "running")

    def test_large_file_window_does_not_reuse_old_terminal_event(self):
        path = self.write([self.meta(), self.line("event_msg", {"type": "task_complete"})])
        with path.open("ab") as handle:
            handle.write((json.dumps(self.line("event_msg", {"type": "token_count", "dummy": "x" * 1024})).encode() + b"\n") * 400)
            handle.write(json.dumps(self.line("response_item", {"type": "message", "role": "assistant", "content": []})).encode() + b"\n")
        head, tail, gap = collector._read_window(path)
        self.assertTrue(gap)
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")
        self.assertTrue(head and tail)

    def test_recent_structured_call_recovers_activity_after_start_leaves_window(self):
        private = "PRIVATE-TOOL-INPUT-NOT-EXPORTED"
        for kind in ("function_call", "custom_tool_call"):
            with self.subTest(kind=kind):
                path = self.write([self.meta(), self.line("event_msg", {"type": "task_started"})])
                call = self.line("response_item", {"type": kind, "call_id": "sample-call",
                    "name": "sample_tool", "arguments": private, "input": private})
                with path.open("ab") as handle:
                    filler = self.line("event_msg", {"type": "token_count", "dummy": "x" * 1024})
                    handle.write((json.dumps(filler).encode() + b"\n") * 400)
                    handle.write(json.dumps(call).encode() + b"\n")
                head, tail, gap = collector._read_window(path)
                self.assertTrue(gap)
                self.assertTrue(any(row.get("payload", {}).get("type") == "task_started" for row in head))
                self.assertFalse(any(row.get("payload", {}).get("type") == "task_started" for row in tail))
                for include_output in (False, True):
                    task = collector.parse_codex_session(path, now=self.now, include_output=include_output)
                    self.assertEqual(task["status"], "running")
                    self.assertEqual(task["updated_at"], collector._iso(self.now))
                    self.assertEqual(task["output"], "")
                    self.assertNotIn(private, json.dumps(task))

    def test_structured_call_requires_valid_timestamp_and_stays_bounded_in_time(self):
        cases = ((None, "unknown"), ("invalid-timestamp", "unknown"), (True, "unknown"),
                 (collector._iso(self.now), "running"),
                 (collector._iso(self.now - timedelta(seconds=collector.STALE_SECONDS)), "running"),
                 (collector._iso(self.now - timedelta(seconds=collector.STALE_SECONDS + 1)), "unknown"),
                 (collector._iso(self.now + timedelta(seconds=61)), "unknown"))
        for kind in ("function_call", "custom_tool_call"):
            for stamp, expected in cases:
                with self.subTest(kind=kind, stamp=stamp):
                    call = {"type": "response_item", "payload": {"type": kind, "call_id": "sample-call", "name": "sample_tool"}}
                    if stamp is not None:
                        call["timestamp"] = stamp
                    path = self.write([self.meta(), call])
                    os.utime(path, None)
                    self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], expected)

    def test_explicit_terminal_after_structured_call_wins_over_later_outputs(self):
        endings = {"task_complete": "completed", "turn_complete": "completed", "error": "error",
                   "turn_failed": "error", "task_failed": "error", "turn_aborted": "idle", "shutdown_complete": "idle"}
        for kind in ("function_call", "custom_tool_call"):
            for event, expected in endings.items():
                with self.subTest(kind=kind, event=event):
                    records = [self.meta(), self.line("response_item", {"type": kind}),
                               self.line("event_msg", {"type": event}),
                               self.line("response_item", {"type": "function_call_output", "output": "late tool result"}),
                               self.line("response_item", {"type": "custom_tool_call_output", "output": "late custom result"}),
                               self.line("event_msg", {"type": "item_completed", "item": {"type": "Reasoning"}})]
                    self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], expected)

    def test_isolated_outputs_and_completed_items_cannot_recover_running(self):
        records = [self.meta()]
        for record in (self.line("response_item", {"type": "function_call_output", "output": "tool result"}),
                       self.line("response_item", {"type": "custom_tool_call_output", "output": "custom result"}),
                       self.line("event_msg", {"type": "item_completed", "item": {"type": "Reasoning"}})):
            records.append(record)
            self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "unknown")

    def test_approval_after_call_remains_waiting_until_another_call(self):
        for kind in ("function_call", "custom_tool_call"):
            with self.subTest(kind=kind):
                records = [self.meta(), self.line("response_item", {"type": kind}),
                           self.line("event_msg", {"type": "exec_approval_request"}),
                           self.line("response_item", {"type": "function_call_output", "output": "late result"}),
                           self.line("event_msg", {"type": "item_completed"})]
                self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "waiting")
                records.append(self.line("response_item", {"type": kind}))
                self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "running")

    def test_tool_invocation_requires_a_complete_json_line(self):
        for kind in ("function_call", "custom_tool_call"):
            with self.subTest(kind=kind):
                call = self.line("response_item", {"type": kind, "call_id": "sample-call", "name": "sample_tool"})
                path = self.write([self.meta(), self.line("event_msg", {"type": "exec_approval_request"})],
                                  suffix=json.dumps(call).encode())
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "waiting")
                with path.open("ab") as handle:
                    handle.write(b"\n")
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "running")

    def test_structured_activity_does_not_bypass_subagent_filter(self):
        for source in ({"parent_thread_id": "parent"}, {"source": {"subagent": {"thread_spawn": {"parent_thread_id": "parent"}}}}):
            for kind in ("function_call", "custom_tool_call"):
                with self.subTest(source=source, kind=kind):
                    path = self.write([self.meta("child", **source), self.line("response_item", {"type": kind})], session="child")
                    self.assertIsNone(collector.parse_codex_session(path, now=self.now))

    def test_model_response_metadata_recovers_activity_after_large_output_gap(self):
        for payload in ({"type": "reasoning", "summary": [{"text": "PRIVATE-REASONING"}]},
                        {"type": "message", "role": "assistant", "phase": "commentary",
                         "content": [{"type": "output_text", "text": "PRIVATE-COMMENTARY"}]}):
            with self.subTest(kind=payload["type"]):
                path = self.write([self.meta(), self.line("event_msg", {"type": "task_started"})])
                with path.open("ab") as handle:
                    large_output = self.line("response_item", {"type": "function_call_output",
                                             "output": "x" * (collector.MAX_LOG_BYTES * 2)})
                    handle.write(json.dumps(large_output).encode() + b"\n")
                    handle.write(json.dumps(self.line("response_item", payload)).encode() + b"\n")
                _, tail, gap = collector._read_window(path)
                self.assertTrue(gap)
                self.assertEqual(len(tail), 1)
                task = collector.parse_codex_session(path, now=self.now)
                self.assertEqual(task["status"], "running")
                self.assertEqual(task["output"], "")
                self.assertNotIn("PRIVATE", json.dumps(task))

    def test_model_activity_cannot_override_waiting_or_terminal_states(self):
        states = {"exec_approval_request": "waiting", "apply_patch_approval_request": "waiting",
                  "request_user_input": "waiting", "task_complete": "completed", "error": "error", "turn_aborted": "idle"}
        for event, expected in states.items():
            for payload in ({"type": "reasoning"},
                            {"type": "message", "role": "assistant", "phase": "commentary"}):
                with self.subTest(event=event, kind=payload["type"]):
                    records = [self.meta(), self.line("event_msg", {"type": event}),
                               self.line("response_item", payload)]
                    self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], expected)

    def test_explicit_terminal_after_model_activity_takes_precedence(self):
        for payload in ({"type": "reasoning"},
                        {"type": "message", "role": "assistant", "phase": "commentary"}):
            for event, expected in (("task_complete", "completed"), ("turn_failed", "error"), ("shutdown_complete", "idle")):
                with self.subTest(event=event, kind=payload["type"]):
                    records = [self.meta(), self.line("response_item", payload),
                               self.line("event_msg", {"type": event})]
                    self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], expected)

    def test_model_activity_requires_a_valid_recent_timestamp(self):
        for payload in ({"type": "reasoning"},
                        {"type": "message", "role": "assistant", "phase": "commentary"}):
            for stamp in (None, True, "not-a-time", collector._iso(self.now - timedelta(seconds=collector.STALE_SECONDS + 1)),
                          collector._iso(self.now + timedelta(seconds=61))):
                with self.subTest(kind=payload["type"], stamp=stamp):
                    record = {"type": "response_item", "payload": payload}
                    if stamp is not None:
                        record["timestamp"] = stamp
                    task = collector.parse_codex_session(self.write([self.meta(), record]), now=self.now)
                    self.assertEqual(task["status"], "unknown")
                    records = [self.meta(), record, self.line("response_item",
                               {"type": "message", "role": "assistant", "phase": "final"})]
                    self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "unknown")

    def test_only_explicit_assistant_commentary_message_can_recover_activity(self):
        for payload in ({"type": "message", "role": "user", "phase": "commentary"},
                        {"type": "message", "role": "assistant", "phase": "final"},
                        {"type": "message", "role": "assistant"},
                        {"type": "message", "phase": "commentary"}):
            with self.subTest(payload=payload):
                task = collector.parse_codex_session(self.write([self.meta(), self.line("response_item", payload)]), now=self.now)
                self.assertEqual(task["status"], "unknown")
        # An item's completion notification is not a model response item.
        task = collector.parse_codex_session(self.write([self.meta(),
            self.line("event_msg", {"type": "item_completed", "item": {"type": "Reasoning"}})]), now=self.now)
        self.assertEqual(task["status"], "unknown")

    def test_partial_model_response_line_is_not_activity(self):
        for payload in ({"type": "reasoning"},
                        {"type": "message", "role": "assistant", "phase": "commentary"}):
            with self.subTest(kind=payload["type"]):
                record = self.line("response_item", payload)
                path = self.write([self.meta()], suffix=json.dumps(record).encode())
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")
                with path.open("ab") as handle:
                    handle.write(b"\n")
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "running")

    def test_large_file_read_budget_is_enforced(self):
        path = self.write([self.meta()])
        with path.open("ab") as handle:
            handle.write(b"x" * (collector.MAX_LOG_BYTES * 3))
        underlying = path.open("rb")

        class Meter:
            count = 0

            def __enter__(self):
                return self

            def __exit__(self, *args):
                underlying.close()

            def fileno(self):
                return underlying.fileno()

            def seek(self, *args):
                return underlying.seek(*args)

            def read(self, size):
                chunk = underlying.read(size)
                self.count += len(chunk)
                return chunk

        meter = Meter()
        with patch.object(Path, "open", return_value=meter):
            collector._read_window(path)
        self.assertLessEqual(meter.count, collector.MAX_LOG_BYTES)

    def recovery_log(self, before=(), after=(), *, result_bytes=562_766, suffix=b"", meta=None):
        # Keep identity in the head, but the recent call outside that head.
        # The large result size matches the observed screenshot-output failure.
        path = self.write([meta or self.meta(), self.line("event_msg", {
            "type": "token_count", "padding": "x" * collector.MAX_CODEX_RECOVERY_BYTES}),
            *before, self.line("response_item", {"type": "custom_tool_call_output",
                "output": "PRIVATE-RESULT" + "x" * result_bytes}), *after], suffix=suffix)
        os.utime(path, (self.now.timestamp(), self.now.timestamp()))
        return path

    def test_large_result_recovers_recent_call_even_when_result_is_eof(self):
        for kind in ("function_call", "custom_tool_call"):
            for after in ([], [self.line("event_msg", {"type": "token_count"})]):
                with self.subTest(kind=kind, followup=bool(after)):
                    path = self.recovery_log([self.line("response_item", {"type": kind},
                        self.now - timedelta(seconds=3))], after)
                    _, tail, gap = collector._read_window(path)
                    self.assertTrue(gap)
                    self.assertEqual(len(tail), len(after))
                    for include_output in (False, True):
                        task = collector.parse_codex_session(path, now=self.now, include_output=include_output)
                        self.assertEqual(task["status"], "running")
                        self.assertEqual(task["output"], "")
                        self.assertNotIn("PRIVATE-RESULT", json.dumps(task))

    def test_recovery_preserves_terminal_and_approval_events_before_large_result(self):
        for kind, expected in (("task_complete", "completed"), ("turn_failed", "error"),
                ("turn_aborted", "idle"), ("exec_approval_request", "waiting"),
                ("request_user_input", "waiting")):
            with self.subTest(kind=kind):
                path = self.recovery_log([self.line("response_item", {"type": "custom_tool_call"}),
                    self.line("event_msg", {"type": kind})],
                    [self.line("event_msg", {"type": "token_count"})])
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], expected)

    def test_recovery_cannot_use_isolated_output_or_mtime_as_activity(self):
        for after in ([], [self.line("event_msg", {"type": "item_completed"})]):
            with self.subTest(followup=bool(after)):
                task = collector.parse_codex_session(self.recovery_log(after=after), now=self.now)
                self.assertEqual(task["status"], "unknown")

    def test_recovery_new_user_prompt_invalidates_prior_terminal_state(self):
        path = self.recovery_log([self.line("response_item", {"type": "custom_tool_call"}),
            self.line("event_msg", {"type": "task_complete"}),
            self.line("event_msg", {"type": "user_message"})])
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")

    def test_recovery_does_not_reuse_head_lifecycle_across_remaining_gap(self):
        for kind in ("task_started", "task_complete", "exec_approval_request"):
            with self.subTest(kind=kind):
                path = self.write([self.meta(), self.line("event_msg", {"type": kind}),
                    self.line("event_msg", {"type": "token_count", "padding": "x" * collector.MAX_CODEX_RECOVERY_BYTES}),
                    self.line("response_item", {"type": "custom_tool_call_output", "output": "x" * 562_766})])
                os.utime(path, (self.now.timestamp(), self.now.timestamp()))
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")

    def test_recovery_keeps_activity_expiry_and_future_timestamp_limits(self):
        for stamp in (self.now - timedelta(seconds=301), self.now + timedelta(seconds=61)):
            with self.subTest(stamp=stamp):
                path = self.recovery_log([self.line("response_item", {"type": "custom_tool_call"}, stamp)])
                self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")

    def test_recovery_does_not_bridge_an_oversized_record_or_head_gap(self):
        path = self.recovery_log([self.line("response_item", {"type": "custom_tool_call"})],
            [self.line("event_msg", {"type": "token_count"})],
            result_bytes=collector.MAX_CODEX_RECOVERY_BYTES * 2)
        _, tail, gap = collector._read_window(path, max_bytes=collector.MAX_CODEX_RECOVERY_BYTES)
        self.assertTrue(gap)
        self.assertEqual(len(tail), 1)
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "unknown")

    def test_recovery_waits_for_terminal_record_newline(self):
        terminal = json.dumps(self.line("event_msg", {"type": "task_complete"})).encode()
        path = self.recovery_log([self.line("response_item", {"type": "custom_tool_call"})], suffix=terminal)
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "running")
        with path.open("ab") as handle:
            handle.write(b"\n")
        self.assertEqual(collector.parse_codex_session(path, now=self.now)["status"], "completed")

    def test_known_states_old_files_and_subagents_keep_ordinary_read_budget(self):
        cases = (("known", "running"), ("old", "unknown"), ("subagent", None))
        for name, expected in cases:
            with self.subTest(case=name):
                after = [self.line("response_item", {"type": "custom_tool_call"})] if name == "known" else []
                meta = self.meta("child", parent_thread_id="parent") if name == "subagent" else None
                path = self.recovery_log(after=after, meta=meta)
                if name == "old":
                    old = (self.now - timedelta(minutes=6)).timestamp()
                    os.utime(path, (old, old))
                with patch.object(collector, "_read_window", wraps=collector._read_window) as reader:
                    task = collector.parse_codex_session(path, now=self.now)
                self.assertEqual(task["status"] if task else None, expected)
                self.assertEqual(reader.call_count, 1)

    def test_codex_recovery_total_read_budget_is_bounded(self):
        path = self.recovery_log(result_bytes=collector.MAX_CODEX_RECOVERY_BYTES * 2)
        original_open = Path.open
        reads = []

        class Meter:
            def __init__(self, handle):
                self.handle = handle

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.handle.close()

            def fileno(self):
                return self.handle.fileno()

            def seek(self, *args):
                return self.handle.seek(*args)

            def read(self, size):
                chunk = self.handle.read(size)
                reads.append(len(chunk))
                return chunk

        with patch.object(Path, "open", lambda this, *a, **kw: Meter(original_open(this, *a, **kw))):
            task = collector.parse_codex_session(path, now=self.now)
        self.assertEqual(task["status"], "unknown")
        self.assertGreater(sum(reads), collector.MAX_LOG_BYTES)
        self.assertLessEqual(sum(reads), collector.MAX_LOG_BYTES + collector.MAX_CODEX_RECOVERY_BYTES)

    def test_default_snapshot_has_no_prompt_output_or_absolute_project_path(self):
        secret = "DO-NOT-EXPORT-PRIVATE-PROMPT"
        self.write([self.meta(), self.line("event_msg", {"type": "task_complete", "last_agent_message": secret}),
                    self.line("response_item", {"type": "message", "role": "user", "content": [{"type": "input_text", "text": secret}]})])
        with patch.object(collector, "utc_now", return_value=self.now):
            snapshot = collector.collect_snapshot(self.codex, self.claude)
        serialized = json.dumps(snapshot)
        self.assertNotIn(secret, serialized)
        self.assertNotIn("private", serialized)
        self.assertEqual(snapshot["tasks"][0]["project"], "demo")

    def test_output_opt_in_only_includes_assistant_text(self):
        path = self.write([self.meta(), self.line("response_item", {"type": "message", "role": "assistant", "phase": "final", "content": [{"type": "output_text", "text": "visible answer"}, {"type": "reasoning", "text": "hidden reasoning"}]}),
                           self.line("response_item", {"type": "function_call_output", "output": "secret tool result"})])
        task = collector.parse_codex_session(path, include_output=True, now=self.now)
        self.assertEqual(task["output"], "visible answer")
        self.assertNotIn("secret", json.dumps(task))

    def test_claude_end_turn_text_remains_unknown_without_hooks(self):
        path = self.write([{"type": "assistant", "sessionId": "claude-1", "cwd": "/private/demo", "timestamp": collector._iso(self.now),
                            "message": {"stop_reason": "end_turn", "content": [{"type": "text", "text": "All done"}]}}], tool="claude")
        task = collector.parse_claude_session(path)
        self.assertEqual(task["status"], "unknown")
        self.assertEqual(task["output"], "")

    def test_explicit_wait_and_resume_and_failure(self):
        records = [self.meta(), self.line("event_msg", {"type": "exec_approval_request"})]
        self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "waiting")
        records.append(self.line("event_msg", {"type": "exec_command_begin"}))
        self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "running")
        records.append(self.line("event_msg", {"type": "error"}))
        self.assertEqual(collector.parse_codex_session(self.write(records), now=self.now)["status"], "error")

    def test_limit_and_subagent_exclusion(self):
        self.write([self.meta("child", parent_thread_id="parent")], session="child")
        self.write([self.meta("parent")], session="parent")
        result = collector.collect_snapshot(self.codex, self.claude, limit=50)
        self.assertEqual([task["id"] for task in result["tasks"]], ["codex:parent"])
        self.assertEqual(collector.collect_snapshot(self.codex, self.claude, limit=0)["tasks"], [])

    def test_missing_sources_are_not_available(self):
        result = collector.collect_snapshot(self.codex, self.claude)
        self.assertEqual(result["tasks"], [])
        self.assertEqual([s["available"] for s in result["sources"]], [False, False])

    def test_hook_records_minimal_state_and_restores_status(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            stored = claude_hook.record_event({"hook_event_name": "PermissionRequest", "session_id": "claude-1", "cwd": "C:\\private\\demo",
                                               "prompt": "private prompt", "tool_input": {"api_key": "private credential"}, "transcript_path": "/private/chat"})
        self.assertTrue(stored)
        records = list(self.state.rglob("*.json"))
        self.assertEqual(len(records), 1)
        text = records[0].read_text(encoding="utf-8")
        self.assertNotIn("private", text)
        with patch.object(collector, "utc_now", return_value=self.now):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "waiting")
        self.assertEqual(task["status_source"], "hook")

    def test_newer_transcript_invalidates_old_completed_hook(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now - timedelta(minutes=2)):
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "background_tasks": [], "session_crons": []})
        self.write([{"type": "user", "sessionId": "claude-1", "timestamp": collector._iso(self.now), "message": {"content": "new prompt"}}], tool="claude")
        with patch.object(collector, "utc_now", return_value=self.now):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "unknown")
        self.assertEqual(task["status_source"], "local_log")

    def test_hook_does_not_accept_paths_or_arbitrary_notifications(self):
        self.assertFalse(claude_hook.record_event({"hook_event_name": "Stop", "session_id": "../../other"}))
        self.assertFalse(claude_hook.record_event({"hook_event_name": "Notification", "session_id": "ok", "notification_type": "auth_success"}))
        self.assertFalse(self.state.exists())

    def test_hook_configuration_is_background_and_does_not_install(self):
        config = claude_hook.hook_configuration(state_dir=str(self.state))
        for handlers in config["hooks"].values():
            self.assertTrue(handlers[0]["hooks"][0]["async"])
            self.assertIsInstance(handlers[0]["hooks"][0]["args"], list)
        self.assertFalse(self.state.exists())

    def test_hook_retention_and_delayed_old_event_do_not_replace_newest(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            for index in range(6):
                claude_hook.record_event({"hook_event_name": "Stop" if index == 5 else "UserPromptSubmit", "session_id": "claude-1", "background_tasks": [], "session_crons": []}, received_ns=index + 10)
            claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "claude-1"}, received_ns=1)
        self.assertEqual(len(list(self.state.rglob("*.json"))), 4)
        with patch.object(collector, "utc_now", return_value=self.now):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "completed")

    def test_hook_input_failure_is_silent_success(self):
        stdin = type("Input", (), {"buffer": io.BytesIO(b"not-json")})()
        output = io.StringIO()
        with patch("sys.stdin", stdin), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(claude_hook.main([]), 0)
        self.assertEqual(output.getvalue(), "")

    def test_late_metadata_assistant_and_tool_result_do_not_erase_stop(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "prompt_id": "prompt-one", "background_tasks": [], "session_crons": []})
        self.write([
            {"type": "assistant", "sessionId": "claude-1", "timestamp": collector._iso(self.now + timedelta(seconds=1)), "message": {"content": [{"type": "text", "text": "late final output"}]}},
            {"type": "user", "sessionId": "claude-1", "timestamp": collector._iso(self.now + timedelta(seconds=2)), "message": {"content": [{"type": "tool_result", "content": "late result"}]}},
            {"type": "custom-title", "sessionId": "claude-1", "timestamp": collector._iso(self.now + timedelta(seconds=3)), "customTitle": "Renamed task"},
        ], tool="claude")
        with patch.object(collector, "utc_now", return_value=self.now + timedelta(seconds=4)):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "completed")
        self.assertEqual(task["status_source"], "hook")
        self.assertEqual(task["title"], "Renamed task")
        self.assertEqual(task["output"], "")
        self.assertFalse(any(key.startswith("_") for key in task))

    def test_same_prompt_flushed_late_keeps_hook_but_new_prompt_invalidates(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "PermissionRequest", "session_id": "claude-1", "prompt_id": "prompt-one"})
        record = {"type": "user", "sessionId": "claude-1", "timestamp": collector._iso(self.now + timedelta(seconds=1)), "promptId": "prompt-one", "message": {"content": "submitted prompt"}}
        self.write([record], tool="claude")
        with patch.object(collector, "utc_now", return_value=self.now + timedelta(seconds=2)):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "waiting")
        record["promptId"] = "prompt-two"
        self.write([record], tool="claude")
        with patch.object(collector, "utc_now", return_value=self.now + timedelta(seconds=2)):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "unknown")

    def test_subagent_hooks_do_not_overwrite_parent_session(self):
        self.assertFalse(claude_hook.record_event({"hook_event_name": "PostToolUse", "session_id": "main-session", "agent_id": "child-agent"}))
        self.assertFalse(self.state.exists())

    def test_five_minute_silence_never_becomes_completed(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "claude-1"})
        with patch.object(collector, "utc_now", return_value=self.now + timedelta(minutes=6)):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "unknown")

    def test_compaction_session_start_is_not_idle(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "SessionStart", "source": "compact", "session_id": "claude-1"})
        with patch.object(collector, "utc_now", return_value=self.now):
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
        self.assertEqual(task["status"], "unknown")

    def test_real_generated_hook_command_covers_lifecycle_silently(self):
        config = claude_hook.hook_configuration(state_dir=str(self.state))
        for event, expected in (("UserPromptSubmit", "running"), ("PermissionRequest", "waiting"), ("Stop", "completed"), ("StopFailure", "error"), ("SessionEnd", "idle")):
            handler = config["hooks"][event][0]["hooks"][0]
            payload = {"hook_event_name": event, "session_id": "synthetic-session", "prompt_id": "prompt-test", "cwd": str(self.base / "sample"), "last_assistant_message": "PRIVATE-NOT-STORED", "background_tasks": [], "session_crons": []}
            result = subprocess.run([handler["command"], *handler["args"]], input=json.dumps(payload).encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertEqual((result.returncode, result.stdout, result.stderr), (0, b"", b""))
            task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
            self.assertEqual(task["status"], expected)
            self.assertNotIn("PRIVATE-NOT-STORED", json.dumps(task))

    def test_stop_with_background_work_or_scheduled_wakeup_stays_running_until_empty(self):
        pending = {"id": "child-1", "type": "subagent", "status": "running",
                   "description": "PRIVATE-DESCRIPTION", "command": "PRIVATE-COMMAND"}
        scheduled = {"id": "wake-1", "schedule": "0 9 * * *", "recurring": False, "prompt": "PRIVATE-PROMPT"}
        for index, (background, crons) in enumerate((([pending], []), ([], [scheduled]), ([pending], [scheduled]))):
            with self.subTest(background=bool(background), scheduled=bool(crons)):
                payload = {"hook_event_name": "Stop", "session_id": "claude-1", "background_tasks": background, "session_crons": crons}
                with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
                    self.assertTrue(claude_hook.record_event(payload, received_ns=100 + index * 2))
                    task = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]
                    self.assertEqual(task["status"], "running")
                    self.assertEqual(task["status_source"], "hook")
                    payload.update(background_tasks=[], session_crons=[])
                    claude_hook.record_event(payload, received_ns=101 + index * 2)
                    self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "completed")
        saved = "".join(path.read_text() for path in self.state.rglob("*.json"))
        for private in ("PRIVATE-", "child-1", "wake-1", "0 9 * * *"):
            self.assertNotIn(private, saved)
        self.assertIn('"background_count"', saved)
        self.assertIn('"scheduled_count"', saved)

    def test_stop_absent_or_malformed_registry_is_not_completion(self):
        pending = {"id": "task-1", "type": "shell", "status": "running"}
        cases = [({}, "unknown"), ({"background_tasks": []}, "unknown"), ({"session_crons": []}, "unknown"),
                 ({"background_tasks": None, "session_crons": []}, "unknown"),
                 ({"background_tasks": "empty", "session_crons": []}, "unknown"),
                 ({"background_tasks": {}, "session_crons": []}, "unknown"),
                 ({"background_tasks": [None], "session_crons": []}, "unknown"),
                 ({"background_tasks": [{}], "session_crons": []}, "unknown"),
                 ({"background_tasks": [], "session_crons": [{"id": "cron"}]}, "unknown"),
                 ({"background_tasks": [pending]}, "running"),
                 ({"background_tasks": [pending], "session_crons": None}, "running"),
                 ({"background_tasks": [], "session_crons": []}, "completed")]
        with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
            for index, (registry, expected) in enumerate(cases):
                with self.subTest(case=index):
                    claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", **registry}, received_ns=100 + index)
                    self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], expected)

    def test_idle_notification_never_overwrites_permission_wait_or_pending_stop(self):
        for event in ({"hook_event_name": "PermissionRequest"}, {"hook_event_name": "UserPromptSubmit"},
                      {"hook_event_name": "Stop", "background_tasks": [{"id": "task-1", "type": "MCP task", "status": "running"}], "session_crons": []}):
            with self.subTest(event=event["hook_event_name"]), patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
                claude_hook.record_event({"session_id": "claude-1", **event})
                before = collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"]
                self.assertFalse(claude_hook.record_event({"hook_event_name": "Notification", "session_id": "claude-1", "notification_type": "idle_prompt"}))
                self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], before)

    def test_legacy_completion_without_registry_is_unknown_and_idle_notification_is_ignored(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "background_tasks": [], "session_crons": []}, received_ns=10)
            path = next(self.state.rglob("state-*.json"))
            legacy = json.loads(path.read_text())
            legacy.update(schema=1, status="completed")
            legacy.pop("background_count"); legacy.pop("scheduled_count")
            path.write_text(json.dumps(legacy))
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "unknown")
            claude_hook.record_event({"hook_event_name": "PermissionRequest", "session_id": "claude-1"}, received_ns=20)
            legacy["event"] = "Notification"
            path.with_name("state-00000000000000000030-legacy.json").write_text(json.dumps(legacy))
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "waiting")

    def test_late_stop_and_notification_from_previous_prompt_do_not_finish_new_prompt(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
            for index, prompt in enumerate(("prompt-one", "prompt-two")):
                claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "claude-1", "prompt_id": prompt}, received_ns=10 + index)
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "prompt_id": "prompt-one", "background_tasks": [], "session_crons": []}, received_ns=12)
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "running")
            claude_hook.record_event({"hook_event_name": "Notification", "session_id": "claude-1", "prompt_id": "prompt-one", "notification_type": "permission_prompt"}, received_ns=13)
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "running")
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "prompt_id": "prompt-two", "background_tasks": [], "session_crons": []}, received_ns=14)
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "completed")

    def test_subagent_stop_cannot_finish_parent_waiting_for_its_result(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "main", "background_tasks": [{"id": "child", "type": "subagent", "status": "running"}], "session_crons": []})
            self.assertFalse(claude_hook.record_event({"hook_event_name": "SubagentStop", "session_id": "main", "agent_id": "child", "background_tasks": [], "session_crons": []}))
            self.assertFalse(claude_hook.record_event({"hook_event_name": "Stop", "session_id": "main", "agent_id": "child", "background_tasks": [], "session_crons": []}))
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "running")
        with patch.object(collector, "utc_now", return_value=self.now + timedelta(minutes=6)):
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "unknown")

    def test_late_previous_stop_cannot_win_after_prompt_start_leaves_retained_window(self):
        with patch.object(claude_hook, "utc_now", return_value=self.now), patch.object(collector, "utc_now", return_value=self.now):
            claude_hook.record_event({"hook_event_name": "UserPromptSubmit", "session_id": "claude-1", "prompt_id": "new"}, received_ns=1)
            for index in range(2, 6):
                claude_hook.record_event({"hook_event_name": "PostToolUse", "session_id": "claude-1", "prompt_id": "new"}, received_ns=index)
            claude_hook.record_event({"hook_event_name": "Stop", "session_id": "claude-1", "prompt_id": "old", "background_tasks": [], "session_crons": []}, received_ns=6)
            self.assertEqual(len(list(self.state.rglob("*.json"))), 4)
            self.assertEqual(collector.collect_snapshot(self.codex, self.claude)["tasks"][0]["status"], "running")


class HookInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.settings = self.base / "settings.json"
        self.state = self.base / "monitor-state"
        self.original = {
            "env": {"PRIVATE_SETTING": "DO-NOT-PRINT-OR-REPLACE"},
            "permissions": {"allow": ["Read(*)"]},
            "hooks": {"Stop": [{"matcher": "", "hooks": [{"type": "command", "command": "existing-custom-command"}]}], "UnknownEvent": []},
        }
        self.settings.write_text(json.dumps(self.original, indent=4), encoding="utf-8")

    def plan(self, **kwargs):
        return install_claude_hooks.prepare_plan(self.settings, state_dir=str(self.state), **kwargs)

    def test_preview_preserves_existing_settings_and_contains_only_our_diff(self):
        original_bytes = self.settings.read_bytes()
        summary, merged, raw = self.plan()
        self.assertEqual(raw, original_bytes)
        self.assertEqual(self.settings.read_bytes(), original_bytes)
        self.assertEqual(merged["env"], self.original["env"])
        self.assertEqual(merged["permissions"], self.original["permissions"])
        self.assertEqual(merged["hooks"]["Stop"][0], self.original["hooks"]["Stop"][0])
        self.assertEqual(merged["hooks"]["UnknownEvent"], [])
        self.assertNotIn("DO-NOT-PRINT", json.dumps(summary))
        self.assertNotIn("existing-custom-command", json.dumps(summary))
        self.assertEqual(list(self.base.iterdir()), [self.settings])

    def test_apply_backs_up_original_and_repeated_install_is_noop(self):
        original_bytes = self.settings.read_bytes()
        summary, _, _ = self.plan()
        applied = install_claude_hooks.apply_plan(self.settings, summary["expected_sha256"], state_dir=str(self.state))
        self.assertTrue(applied["applied"])
        self.assertEqual(Path(applied["backup"]).read_bytes(), original_bytes)
        installed = json.loads(self.settings.read_text(encoding="utf-8"))
        self.assertEqual(installed["env"], self.original["env"])
        second, _, _ = self.plan()
        self.assertFalse(second["changed"])
        self.assertFalse(install_claude_hooks.apply_plan(self.settings, second["expected_sha256"], state_dir=str(self.state))["applied"])
        self.assertEqual(len(list(self.base.glob("*.bak"))), 1)

    def test_apply_rejects_changed_settings_without_overwriting(self):
        summary, _, _ = self.plan()
        self.settings.write_text('{"new_setting": true}', encoding="utf-8")
        with self.assertRaises(install_claude_hooks.InstallError):
            install_claude_hooks.apply_plan(self.settings, summary["expected_sha256"], state_dir=str(self.state))
        self.assertEqual(json.loads(self.settings.read_text()), {"new_setting": True})
        self.assertEqual(len(list(self.base.glob("*.bak"))), 0)

    def test_remove_only_our_handlers_preserves_mixed_entries(self):
        config = claude_hook.hook_configuration(state_dir=str(self.state))
        own = config["hooks"]["Stop"][0]["hooks"][0]
        mixed = {"hooks": {"Stop": [{"matcher": "something", "timeout": 9, "hooks": [own, {"type": "command", "command": "other"}]}]}, "custom": [1, 2]}
        removed, _ = install_claude_hooks.merge_hooks(mixed, config, remove=True)
        self.assertEqual(removed, {"hooks": {"Stop": [{"matcher": "something", "timeout": 9, "hooks": [{"type": "command", "command": "other"}]}]}, "custom": [1, 2]})

    def test_invalid_json_duplicate_keys_and_disabled_hooks_are_not_changed(self):
        for content in ('{"hooks":', '{"hooks":{},"hooks":{}}', '{"hooks":[]}', '{"disableAllHooks":true}'):
            with self.subTest(content=content):
                self.settings.write_text(content, encoding="utf-8")
                with self.assertRaises(install_claude_hooks.InstallError):
                    self.plan()
                self.assertEqual(self.settings.read_text(encoding="utf-8"), content)

    def test_cli_apply_requires_preview_hash(self):
        before = self.settings.read_bytes()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = install_claude_hooks.main(["--settings", str(self.settings), "--apply"])
        self.assertEqual(result, 1)
        self.assertEqual(self.settings.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
