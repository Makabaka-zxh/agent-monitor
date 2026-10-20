"""Synthetic discovery tests; no compressed-content or live-source reads."""
import contextlib
import os
from pathlib import Path
import shutil
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from agent_monitor import collector


class CompressedDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp(prefix="monitor-compressed-discovery-"))

    def tearDown(self):
        target = self.base.resolve()
        self.assertEqual(target.parent, Path(tempfile.gettempdir()).resolve())
        self.assertTrue(target.name.startswith("monitor-compressed-discovery-"))
        self.assertFalse(self.base.is_symlink())
        self.assertFalse(getattr(self.base, "is_junction", lambda: False)())
        shutil.rmtree(target)

    def file(self, relative, modified):
        path = self.base / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"synthetic discovery fixture")
        os.utime(path, (modified, modified))
        return path

    def entry(self, name, modified, *, link=False, reparse=False):
        result = SimpleNamespace(name=name, path=str(self.base / name), stat_calls=0)
        result.is_symlink = lambda: link
        result.is_dir = lambda *, follow_symlinks: False
        def metadata(*, follow_symlinks):
            self.assertFalse(follow_symlinks)
            result.stat_calls += 1
            if link:
                raise AssertionError("Do not query a rejected link")
            return SimpleNamespace(st_mode=stat.S_IFREG, st_file_attributes=0x400 if reparse else 0, st_mtime=modified)
        result.stat = metadata
        return result

    def test_plain_wins_in_both_enumeration_orders_and_uses_plain_mtime(self):
        compressed = self.entry("same.jsonl.zst", 3000)
        plain = self.entry("same.jsonl", 1000)
        other = self.entry("other.jsonl", 2000)
        for entries in ((compressed, other, plain), (plain, other, compressed)):
            with self.subTest(order=entries[0].name):
                with patch.object(collector.os, "scandir", return_value=contextlib.nullcontext(iter(entries))):
                    self.assertEqual(collector._recent_files(self.base, 1), ([self.base / "other.jsonl"], 2, False))
                with patch.object(collector.os, "scandir", return_value=contextlib.nullcontext(iter(entries))):
                    self.assertEqual(collector._recent_files(self.base, 50),
                                     ([self.base / "other.jsonl", self.base / "same.jsonl"], 2, False))

    def test_codex_recurses_and_pairs_only_same_directory_with_fifty_candidate_cap(self):
        expected = []
        for index in range(55):
            expected.append(self.file(f"day/session-{index:03}.jsonl.zst", 1000 + index))
        plain = self.file("day/session-054.jsonl", 1054)
        expected[-1] = plain
        separate = self.file("another/session-054.jsonl.zst", 2000)
        self.file("day/ignored.jsonl.zst.tmp", 3000)
        expected.append(separate)
        files, count, capped = collector._recent_files(self.base, 50)
        self.assertEqual(count, 56)
        self.assertFalse(capped)
        self.assertEqual(files, sorted(expected, key=lambda p: (p.stat().st_mtime, str(p)), reverse=True)[:50])
        self.assertEqual(collector._recent_files(self.base, 0), ([], 56, False))

    def test_claude_keeps_plain_only_and_original_directory_depth(self):
        root = self.file("root.jsonl", 1000)
        project = self.file("project/session.jsonl", 1001)
        self.file("project/session.jsonl.zst", 1002)
        self.file("project/compressed-only.jsonl.zst", 1003)
        self.file("project/deeper/child.jsonl", 1004)
        self.assertEqual(collector._recent_files(self.base, 50, claude=True), ([project, root], 2, False))

    def test_timestamp_ties_use_the_logical_filename(self):
        compressed = self.file("same.jsonl.zst", 1000)
        later_name = self.file("same.jsonl.jsonl", 1000)
        self.assertEqual(collector._recent_files(self.base, 2), ([later_name, compressed], 2, False))
        plain = self.file("same.jsonl", 1000)
        self.assertEqual(collector._recent_files(self.base, 2), ([later_name, plain], 2, False))

    def test_compressed_links_and_reparse_are_rejected_without_plain_stat_regression(self):
        entries = [self.entry("plain.jsonl", 1), self.entry("compressed.jsonl.zst", 2),
                   self.entry("linked.jsonl.zst", 3, link=True),
                   self.entry("reparse.jsonl.zst", 4, reparse=True)]
        with patch.object(collector.os, "scandir", return_value=contextlib.nullcontext(iter(entries))):
            with patch.object(Path, "is_file", side_effect=AssertionError("Use DirEntry metadata")):
                self.assertEqual(collector._recent_files(self.base, 50),
                                 ([self.base / "compressed.jsonl.zst", self.base / "plain.jsonl"], 2, False))
        self.assertEqual([entry.stat_calls for entry in entries], [1, 1, 0, 1])

    def test_scan_cap_counts_physical_pairs_and_closes_before_next_entry(self):
        consumed, closed = [], []
        def entries():
            for index in range(collector.MAX_DISCOVERED_FILES + 1):
                consumed.append(index)
                suffix = ".jsonl.zst" if index % 2 == 0 else ".jsonl"
                yield self.entry(f"session-{index // 2:05}{suffix}", index // 2)
        @contextlib.contextmanager
        def scan(directory):
            try:
                yield entries()
            finally:
                closed.append(directory)
        with patch.object(collector.os, "scandir", scan):
            files, count, capped = collector._recent_files(self.base, 50)
        self.assertEqual(len(consumed), 20_000)
        self.assertEqual(closed, [self.base])
        self.assertEqual(count, 10_000)
        self.assertTrue(capped)
        self.assertEqual([path.name for path in files], [f"session-{index:05}.jsonl" for index in range(9999, 9949, -1)])

    def test_compressed_rejected_subagent_uses_selection_slot_without_refill(self):
        home = self.base / "codex"
        for index in range(51):
            self.file(f"codex/sessions/{index:03}.jsonl.zst", 1000 + index)
        seen = []
        def parse(path, **kwargs):
            seen.append(path.name)
            if path.name == "050.jsonl.zst":
                return None  # A selected child is rejected by the content parser.
            return {"id": path.name, "updated_at": "2026-01-01T00:00:00Z"}
        with patch.object(collector, "parse_codex_session", side_effect=parse), \
                patch.object(collector, "_codex_titles", return_value={}), \
                patch.object(collector, "_hook_tasks", return_value=[]):
            snapshot = collector.collect_snapshot(home, self.base / "no-claude")
        self.assertEqual(len(seen), 50)
        self.assertNotIn("000.jsonl.zst", seen)
        self.assertEqual(len(snapshot["tasks"]), 49)

    def test_fallback_identity_is_unchanged_when_compressed(self):
        for name in ("legacy.jsonl", "rollout-11111111-1111-4111-8111-111111111111.jsonl"):
            with self.subTest(name=name):
                self.assertEqual(collector._default_id(Path(name)), collector._default_id(Path(name + ".zst")))
        self.assertNotEqual(collector._default_id(Path("first.jsonl.zst")), collector._default_id(Path("second.jsonl.zst")))


if __name__ == "__main__":
    unittest.main()
