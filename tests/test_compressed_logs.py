import importlib
import os
import stat
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import zstandard as zstd

from agent_monitor import compressed_logs as logs


class CompressedLogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "synthetic.jsonl.zst"

    def packed(self, data, **options):
        return zstd.ZstdCompressor(write_checksum=True, **options).compress(data)

    def read(self, packed, *, head=16, maximum=64):
        self.path.write_bytes(packed)
        return logs.read_zstd_window(self.path, head_bytes=head, max_bytes=maximum)

    def failure(self, packed, reason=None):
        with self.assertRaises(logs.ZstdReadError) as caught:
            self.read(packed)
        if reason:
            self.assertEqual(caught.exception.reason, reason)
        self.assertEqual(str(caught.exception), caught.exception.reason)
        self.assertNotIn(str(self.path), str(caught.exception))
        self.assertLessEqual(len(caught.exception.head), 16)
        return caught.exception

    @staticmethod
    def raw_frame(parts, *, rle=False):
        # Unknown content size, 128 KiB decoder window, no checksum. libzstd
        # still validates each manually framed synthetic raw/RLE block.
        data = bytearray(b"\x28\xb5\x2f\xfd\x00\x38")
        for index, part in enumerate(parts):
            length = part if rle else len(part)
            header = (length << 3) | (2 if rle else 0) | (index == len(parts) - 1)
            data.extend(header.to_bytes(3, "little"))
            data.extend(b"x" if rle else part)
        return bytes(data)

    def test_small_complete_bytes_are_identical_and_no_gap(self):
        raw = '{"type":"session_meta","text":"合成"}\n'.encode()
        head, tail, gap = self.read(self.packed(raw))
        self.assertEqual(head, raw)
        self.assertIs(head, tail)
        self.assertFalse(gap)

    def test_exact_window_and_one_byte_over(self):
        for size in (64, 65):
            with self.subTest(size=size):
                raw = bytes(range(size))
                head, tail, gap = self.read(self.packed(raw))
                self.assertEqual(head, raw if size == 64 else raw[:16])
                self.assertEqual(tail, raw if size == 64 else raw[-48:])
                self.assertEqual(gap, size > 64)

    def test_tail_is_raw_including_partial_utf8_and_line(self):
        raw = ("合成行\n" * 40).encode()
        head, tail, gap = self.read(self.packed(raw), head=17, maximum=66)
        self.assertEqual((head, tail, gap), (raw[:17], raw[-49:], True))

    def test_multiblock_compressed_stream_without_content_size(self):
        raw = b'{"synthetic":true}\n' * 50000
        self.assertEqual(self.read(self.packed(raw, write_content_size=False)),
                         (raw[:16], raw[-48:], True))

    def test_raw_and_rle_blocks(self):
        for packed, raw in ((self.raw_frame([b"first", b"second"]), b"firstsecond"),
                            (self.raw_frame([131072, 1000], rle=True), b"x" * 132072)):
            with self.subTest(size=len(raw)):
                self.assertEqual(zstd.ZstdDecompressor().stream_reader(packed).read(), raw)
                head, tail, gap = self.read(packed)
                self.assertEqual(head, raw[:16] if gap else raw)
                self.assertEqual(tail, raw[-48:] if gap else raw)

    def test_multiple_frames_and_skippable_metadata(self):
        skip = (0x184D2A5F).to_bytes(4, "little") + (9).to_bytes(4, "little") + b"synthetic"
        raw = b"head\n" + b"tail\n"
        self.assertEqual(self.read(skip + self.packed(b"head\n") + skip + self.packed(b"tail\n") + skip),
                         (raw, raw, False))

    def test_empty_frame_is_complete_but_empty_file_is_not(self):
        self.assertEqual(self.read(self.packed(b"")), (b"", b"", False))
        self.failure(b"", "truncated")

    def test_each_truncation_boundary_is_rejected(self):
        packed = self.packed(b'{"type":"session_meta"}\n' + b"x" * 100)
        for stop in range(len(packed)):
            with self.subTest(stop=stop):
                self.failure(packed[:stop])

    def test_missing_checksum_is_not_success_even_if_all_output_decoded(self):
        packed = self.packed(b"x" * 200000)
        for removed in (1, 4):
            with self.subTest(removed=removed):
                self.failure(packed[:-removed], "truncated")

    def test_trailing_garbage_and_partial_second_frame_are_rejected(self):
        complete = self.packed(b"session\n")
        for tail in (b"junk", b"x", self.packed(b"tail\n")[:-1]):
            with self.subTest(length=len(tail)):
                error = self.failure(complete + tail)
                self.assertTrue(error.head.startswith(b"session\n"))

    def test_checksum_corruption_has_no_lifecycle_tail(self):
        packed = bytearray(self.packed(b"session_meta\ncompleted\n"))
        packed[-1] ^= 1
        error = self.failure(bytes(packed), "invalid_data")
        self.assertEqual(error.head, b"session_meta\ncom")
        self.assertFalse(hasattr(error, "tail"))

    def test_reserved_header_and_block_are_rejected(self):
        self.failure(b"\x28\xb5\x2f\xfd\x08\x38", "invalid_data")
        self.failure(b"\x28\xb5\x2f\xfd\x00\x38\x07\x00\x00", "invalid_data")

    def test_oversized_block_rejected_without_payload_read(self):
        invalid = b"\x28\xb5\x2f\xfd\x00\x38" + ((131073 << 3) | 1).to_bytes(3, "little")
        self.failure(invalid, "invalid_data")

    def test_skippable_truncation_and_size_budget(self):
        skip = (0x184D2A50).to_bytes(4, "little")
        self.failure(skip + (10).to_bytes(4, "little") + b"x", "truncated")
        with patch.object(logs, "MAX_COMPRESSED_BYTES", 10):
            self.failure(skip + (20).to_bytes(4, "little") + b"x" * 20, "compressed_limit")

    def test_compressed_budget_preserves_only_prefix(self):
        first = self.packed(b"session_meta\n")
        with patch.object(logs, "MAX_COMPRESSED_BYTES", len(first)):
            error = self.failure(first + self.packed(b"completed\n"), "compressed_limit")
        self.assertEqual(error.head, b"session_meta\n")

    def test_decoded_work_budget_checked_before_next_block(self):
        packed = self.raw_frame([b"0123456789", b"abcdefghij", b"completed!"])
        original = logs._Window.append
        output_sizes = []
        def append(window, data):
            output_sizes.append(len(data))
            return original(window, data)
        with patch.object(logs, "MAX_DECODED_BYTES", 20), patch.object(logs._Window, "append", append):
            error = self.failure(packed, "decoded_limit")
        self.assertEqual(sum(output_sizes), 20)
        self.assertEqual(error.head, b"0123456789abcdef")

    def test_compressed_block_uses_conservative_output_bound(self):
        with patch.object(logs, "MAX_DECODED_BYTES", 10):
            self.failure(self.packed(b"x" * 1000), "decoded_limit")

    def test_window_limit_and_dictionary_are_checked_before_decode(self):
        with patch.object(logs, "MAX_DECODER_WINDOW_BYTES", 1024):
            self.failure(self.packed(b"x" * 200000), "window_limit")
        # Single segment, dictionary ID 1, content size 0; no dictionary is read.
        self.failure(b"\x28\xb5\x2f\xfd\x21\x01\x00\x01\x00\x00", "dictionary_required")

    def test_frame_and_block_counts_bound_zero_output_work(self):
        with patch.object(logs, "MAX_FRAMES", 1):
            self.failure(self.packed(b"") * 2, "frame_limit")
        with patch.object(logs, "MAX_BLOCKS", 1):
            self.failure(self.raw_frame([b"", b""]), "block_limit")

    def test_head_only_is_bounded_and_does_not_claim_checksum_verification(self):
        self.path.write_bytes(self.packed(b"x" * 200000)[:-4])
        self.assertEqual(logs.read_zstd_head(self.path, 32), b"x" * 32)
        with self.assertRaises(logs.ZstdReadError):
            logs.read_zstd_window(self.path, head_bytes=16, max_bytes=64)

    def test_head_only_short_log_validates_available_frame(self):
        self.path.write_bytes(self.packed(b"short\n"))
        self.assertEqual(logs.read_zstd_head(self.path), b"short\n")
        self.path.write_bytes(self.packed(b"short\n")[:-1])
        with self.assertRaises(logs.ZstdReadError):
            logs.read_zstd_head(self.path)

    def test_invalid_window_arguments_rejected_before_open(self):
        for head, maximum in ((0, 64), (-1, 64), (65, 64), (True, 64), (1, 1.5), (1, logs.MAX_WINDOW_BYTES + 1)):
            with self.subTest(head=head, maximum=maximum), patch.object(Path, "open", side_effect=AssertionError):
                with self.assertRaisesRegex(ValueError, "^invalid_window$"):
                    logs.read_zstd_window(self.path, head_bytes=head, max_bytes=maximum)

    def test_no_tail_capacity_is_supported(self):
        raw = b"x" * 100
        self.assertEqual(self.read(self.packed(raw), head=64, maximum=64), (raw[:64], b"", True))

    def test_four_mebibyte_result_window_is_supported(self):
        raw = b"synthetic\n" * 430000
        maximum = 4 * 1024 * 1024
        self.assertEqual(logs.MAX_WINDOW_BYTES, maximum)
        head, tail, gap = self.read(self.packed(raw), head=64 * 1024, maximum=maximum)
        self.assertEqual((head, tail, gap), (raw[:64 * 1024], raw[-(maximum - 64 * 1024):], True))

    def test_dependency_missing_is_lazy_and_fixed_reason(self):
        with patch.object(importlib, "import_module", side_effect=ImportError("private synthetic path")):
            error = self.failure(b"", "dependency_missing")
        self.assertEqual(error.head, b"")

    def test_directory_symlink_or_reparse_rejected_before_open(self):
        original = Path.lstat
        for info in (SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0),
                     SimpleNamespace(st_mode=stat.S_IFLNK, st_file_attributes=0),
                     SimpleNamespace(st_mode=stat.S_IFREG, st_file_attributes=0x400)):
            def metadata(path, *args, **kwargs):
                return info if path == self.path else original(path, *args, **kwargs)
            with patch.object(Path, "lstat", metadata), patch.object(Path, "open", side_effect=AssertionError):
                with self.assertRaises(logs.ZstdReadError) as caught:
                    logs.read_zstd_window(self.path, head_bytes=16, max_bytes=64)
                self.assertEqual((caught.exception.reason, caught.exception.head), ("unsafe_source", b""))

    def test_changed_source_after_decode_discards_entire_prefix(self):
        original = logs._decode
        def change(source, window, zstd_module, *, prefix_only):
            original(source, window, zstd_module, prefix_only=prefix_only)
            with self.path.open("ab") as handle:
                handle.write(b"changed")
        with patch.object(logs, "_decode", change):
            error = self.failure(self.packed(b"session_meta\n"), "source_changed")
        self.assertEqual(error.head, b"")

    def test_source_changed_during_failed_decode_also_discards_prefix(self):
        original = logs._decode
        def change(source, window, zstd_module, *, prefix_only):
            try:
                original(source, window, zstd_module, prefix_only=prefix_only)
            finally:
                with self.path.open("ab") as handle:
                    handle.write(b"changed")
        with patch.object(logs, "_decode", change):
            error = self.failure(self.packed(b"session_meta\n")[:-1], "source_changed")
        self.assertEqual(error.head, b"")

    def test_open_identity_mismatch_never_decodes(self):
        self.path.write_bytes(self.packed(b"session_meta\n"))
        real = self.path.stat()
        different = SimpleNamespace(st_mode=real.st_mode, st_file_attributes=0, st_dev=real.st_dev,
                                    st_ino=real.st_ino + 1, st_size=real.st_size,
                                    st_mtime_ns=real.st_mtime_ns, st_ctime_ns=real.st_ctime_ns)
        with patch.object(logs.os, "fstat", return_value=different), patch.object(logs, "_decode", side_effect=AssertionError):
            with self.assertRaises(logs.ZstdReadError) as caught:
                logs.read_zstd_window(self.path, head_bytes=16, max_bytes=64)
        self.assertEqual((caught.exception.reason, caught.exception.head), ("source_changed", b""))

    def test_windows_path_and_descriptor_ctime_have_independent_baselines(self):
        self.path.write_bytes(self.packed(b"session_meta\n"))
        real = self.path.stat()
        descriptor = SimpleNamespace(st_mode=real.st_mode, st_file_attributes=0, st_dev=real.st_dev,
                                     st_ino=real.st_ino, st_size=real.st_size,
                                     st_mtime_ns=real.st_mtime_ns, st_ctime_ns=real.st_ctime_ns + 1)
        with patch.object(logs.os, "fstat", return_value=descriptor):
            self.assertEqual(logs.read_zstd_window(self.path, head_bytes=16, max_bytes=64),
                             (b"session_meta\n", b"session_meta\n", False))

    def test_ancestor_link_or_reparse_is_rejected_before_open(self):
        original = Path.lstat
        for mode, attributes in ((stat.S_IFLNK, 0), (stat.S_IFDIR, 0x400)):
            def metadata(path, *args, **kwargs):
                if path == self.path.parent:
                    return SimpleNamespace(st_mode=mode, st_file_attributes=attributes)
                return original(path, *args, **kwargs)
            with self.subTest(mode=mode), patch.object(Path, "lstat", metadata), patch.object(Path, "open", side_effect=AssertionError):
                with self.assertRaises(logs.ZstdReadError) as caught:
                    logs.read_zstd_window(self.path, head_bytes=16, max_bytes=64)
                self.assertEqual((caught.exception.reason, caught.exception.head), ("unsafe_source", b""))

    def test_ancestor_becomes_link_or_changes_identity_discards_prefix(self):
        original_stat = Path.lstat
        original_decode = logs._decode
        for replacement in ("symlink", "reparse", "directory"):
            changed = False
            def metadata(path, *args, **kwargs):
                info = original_stat(path, *args, **kwargs)
                if changed and path == self.path.parent:
                    return SimpleNamespace(st_mode=stat.S_IFLNK if replacement == "symlink" else stat.S_IFDIR,
                                           st_file_attributes=0x400 if replacement == "reparse" else 0,
                                           st_dev=info.st_dev, st_ino=info.st_ino + 1)
                return info
            def decode(*args, **kwargs):
                nonlocal changed
                original_decode(*args, **kwargs)
                changed = True
            with self.subTest(replacement=replacement), patch.object(Path, "lstat", metadata), patch.object(logs, "_decode", decode):
                error = self.failure(self.packed(b"session_meta\n"), "source_changed")
                self.assertEqual(error.head, b"")

    def test_unrelated_ancestor_timestamp_change_does_not_reject(self):
        original = Path.lstat
        def metadata(path, *args, **kwargs):
            info = original(path, *args, **kwargs)
            if path == self.path.parent:
                return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0,
                                       st_dev=info.st_dev, st_ino=info.st_ino,
                                       st_mtime_ns=object(), st_ctime_ns=object())
            return info
        with patch.object(Path, "lstat", metadata):
            self.assertEqual(self.read(self.packed(b"session_meta\n")),
                             (b"session_meta\n", b"session_meta\n", False))


if __name__ == "__main__":
    unittest.main()
