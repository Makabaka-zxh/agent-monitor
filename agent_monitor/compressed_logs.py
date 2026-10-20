"""Bounded Zstandard windows; no decoded log content is written to disk.

The small framing parser only separates headers/blocks; libzstd validates and
decodes them. A block expands to at most 128 KiB, unlike decompressobj's general
unbounded return value. Every complete frame must reach ``eof`` (including its
checksum). stream_reader alone does not reliably reject a truncated last frame.
"""
from __future__ import annotations

import importlib
import os
import stat
import threading
import time
from collections import OrderedDict
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path

MAX_COMPRESSED_BYTES = 128 * 1024 * 1024
MAX_DECODED_BYTES = 1024 * 1024 * 1024
MAX_DECODER_WINDOW_BYTES = 8 * 1024 * 1024
MAX_WINDOW_BYTES = 4 * 1024 * 1024
MAX_BLOCK_BYTES = 128 * 1024
MAX_FRAMES = 4096
MAX_BLOCKS = 16384
MAX_FILE_SECONDS = 4.0
MAX_SCAN_COMPRESSED_BYTES = 512 * 1024 * 1024
MAX_SCAN_DECODED_BYTES = 2 * 1024 * 1024 * 1024
MAX_SCAN_SECONDS = 8.0
MAX_PREFIX_COMPRESSED_BYTES = 256 * 1024
MAX_PREFIX_DECODED_BYTES = 256 * 1024
MAX_PREFIX_SECONDS = 0.25
MAX_CACHE_BYTES = 16 * 1024 * 1024
MAX_CACHE_ENTRIES = 64
_MAGIC = 0xFD2FB528
_REPARSE = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class ZstdReadError(ValueError):
    """Fixed reason plus an unverified decoded prefix, never a usable tail.

``head`` is only for identifying a session and rejecting subagents. It does not
prove frame integrity or lifecycle state. A changed/unsafe source has no head.
The exception's printable value never includes content or filesystem paths.
    """

    def __init__(self, reason: str, head: bytes = b"") -> None:
        super().__init__(reason)
        self.reason = reason
        self.head = head


class ScanBudget:
    """Shared compressed work for one collection, never a candidate limit.

    Input counts actual bytes read; decoded work reserves each block's upper
    bound before decoding. Deadlines are cooperative at bounded I/O/decode
    boundaries and cannot interrupt a blocking operating-system read.
    """

    def __init__(self, *, compressed_limit=None, decoded_limit=None, seconds=None):
        self.compressed_limit = MAX_SCAN_COMPRESSED_BYTES if compressed_limit is None else compressed_limit
        self.decoded_limit = MAX_SCAN_DECODED_BYTES if decoded_limit is None else decoded_limit
        seconds = MAX_SCAN_SECONDS if seconds is None else seconds
        if (type(self.compressed_limit) is not int or self.compressed_limit < 0
                or type(self.decoded_limit) is not int or self.decoded_limit < 0
                or isinstance(seconds, bool) or not isinstance(seconds, (int, float))
                or not 0 <= seconds < float("inf")):
            raise ValueError("invalid_budget")
        self.deadline = time.monotonic() + seconds
        self.compressed_used = 0
        self.decoded_used = 0
        self._lock = threading.RLock()

    def check(self) -> None:
        if time.monotonic() >= self.deadline:
            raise ZstdReadError("scan_time_limit")

    def input_allowance(self, size: int) -> None:
        with self._lock:
            self.check()
            if self.compressed_used + size > self.compressed_limit:
                raise ZstdReadError("scan_input_limit")

    def charge_input(self, size: int) -> None:
        with self._lock:
            self.compressed_used += size
            self.check()

    def charge_decoded(self, size: int) -> None:
        with self._lock:
            self.check()
            if self.decoded_used + size > self.decoded_limit:
                raise ZstdReadError("scan_decoded_limit")
            self.decoded_used += size


_SCAN_BUDGET: ContextVar[ScanBudget | None] = ContextVar("compressed_log_scan_budget", default=None)


@contextmanager
def collection_budget(budget: ScanBudget | None = None):
    """Apply a fresh shared budget to full reads in this collection context.

    Metadata-only reads deliberately use their own much smaller budget, so a
    spent scan can still identify remaining main candidates as unknown without
    dropping them or backfilling from older files.
    """
    budget = ScanBudget() if budget is None else budget
    if not isinstance(budget, ScanBudget):
        raise ValueError("invalid_budget")
    token = _SCAN_BUDGET.set(budget)
    try:
        yield budget
    finally:
        _SCAN_BUDGET.reset(token)


class _WorkBudget:
    def __init__(self, prefix_only: bool):
        self.input_limit = MAX_PREFIX_COMPRESSED_BYTES if prefix_only else MAX_COMPRESSED_BYTES
        self.decoded_limit = MAX_PREFIX_DECODED_BYTES if prefix_only else MAX_DECODED_BYTES
        self.deadline = time.monotonic() + (MAX_PREFIX_SECONDS if prefix_only else MAX_FILE_SECONDS)
        self.shared = None if prefix_only else _SCAN_BUDGET.get()

    def check(self) -> None:
        if time.monotonic() >= self.deadline:
            raise ZstdReadError("time_limit")
        if self.shared is not None:
            self.shared.check()


_CACHE: OrderedDict[tuple, tuple] = OrderedDict()
_CACHE_BYTES = 0
_CACHE_LOCK = threading.RLock()
_CACHE_STATS = {"hits": 0, "misses": 0, "decodes": 0, "prefix_decodes": 0}


def cache_clear() -> None:
    """Discard only this process's verified compressed-window cache."""
    global _CACHE_BYTES
    with _CACHE_LOCK:
        _CACHE.clear()
        _CACHE_BYTES = 0
        for name in _CACHE_STATS:
            _CACHE_STATS[name] = 0


def cache_info() -> dict[str, int]:
    with _CACHE_LOCK:
        return {"entries": len(_CACHE), "bytes": _CACHE_BYTES, **_CACHE_STATS}


def _cache_drop_path(path_key: str) -> None:
    global _CACHE_BYTES
    with _CACHE_LOCK:
        for key in list(_CACHE):
            if key[0] == path_key:
                _CACHE_BYTES -= _CACHE.pop(key)[-1]


def _cache_get(key, initial, opened_initial, ancestors):
    global _CACHE_BYTES
    with _CACHE_LOCK:
        entry = _CACHE.get(key)
        if entry is None:
            _CACHE_STATS["misses"] += 1
            return None
        if entry[:3] != (initial, opened_initial, ancestors):
            _CACHE_BYTES -= _CACHE.pop(key)[-1]
            _CACHE_STATS["misses"] += 1
            return None
        _CACHE.move_to_end(key)
        _CACHE_STATS["hits"] += 1
        return entry[3]


def _cache_put(key, initial, opened_initial, ancestors, result) -> None:
    global _CACHE_BYTES
    head, tail, _ = result
    size = len(head) + (0 if head is tail else len(tail))
    if size > MAX_CACHE_BYTES or MAX_CACHE_BYTES <= 0 or MAX_CACHE_ENTRIES <= 0:
        return
    with _CACHE_LOCK:
        previous = _CACHE.pop(key, None)
        if previous is not None:
            _CACHE_BYTES -= previous[-1]
        while _CACHE and (len(_CACHE) >= MAX_CACHE_ENTRIES or _CACHE_BYTES + size > MAX_CACHE_BYTES):
            _, previous = _CACHE.popitem(last=False)
            _CACHE_BYTES -= previous[-1]
        _CACHE[key] = (initial, opened_initial, ancestors, result, size)
        _CACHE_BYTES += size


class _CachedWindow:
    def __init__(self, result, head_bytes):
        self._result = result
        self.head = result[0][:head_bytes]

    def result(self):
        return self._result


def _fingerprint(info: os.stat_result) -> tuple[int, ...]:
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _regular(info: os.stat_result) -> bool:
    return stat.S_ISREG(info.st_mode) and not getattr(info, "st_file_attributes", 0) & _REPARSE


def _ancestors(path: Path) -> tuple[tuple[int, int], ...]:
    identities = []
    for parent in reversed(path.parents):
        info = parent.lstat()
        if (not stat.S_ISDIR(info.st_mode)
                or getattr(info, "st_file_attributes", 0) & _REPARSE):
            raise ZstdReadError("unsafe_source")
        # Directory timestamps can change when unrelated files are written.
        # Only a changed directory identity or unsafe directory blocks a read.
        identities.append((info.st_dev, info.st_ino))
    return tuple(identities)


class _Source:
    def __init__(self, handle, size: int, work: _WorkBudget):
        self.handle = handle
        self.size = size
        self.used = 0
        self.work = work

    def read(self, size: int) -> bytes:
        self.work.check()
        if self.used + size > self.work.input_limit:
            raise ZstdReadError("compressed_limit")
        if self.used + size > self.size:
            raise ZstdReadError("truncated")
        if self.work.shared is not None:
            # A budget can also be explicitly shared by concurrent callers.
            # Keep admission and actual charging together around this one
            # bounded read, so two callers cannot spend the same allowance.
            with self.work.shared._lock:
                self.work.shared.input_allowance(size)
                data = self.handle.read(size)
                self.work.shared.charge_input(len(data))
        else:
            data = self.handle.read(size)
        self.used += len(data)
        self.work.check()
        if len(data) != size:
            raise ZstdReadError("truncated")
        return data

    def skip(self, size: int) -> None:
        # Skippable metadata counts against the same input budget. Never seek
        # over an unbounded claimed length or allocate that length at once.
        while size:
            chunk = min(size, MAX_BLOCK_BYTES)
            self.read(chunk)
            size -= chunk


class _Window:
    def __init__(self, head_bytes: int, max_bytes: int):
        self.head_bytes = head_bytes
        self.max_bytes = max_bytes
        self.head = bytearray()
        self.tail = bytearray()
        self.total = 0

    def append(self, data: bytes) -> None:
        self.total += len(data)
        self.head.extend(data[:max(0, self.head_bytes - len(self.head))])
        self.tail.extend(data)
        if len(self.tail) > self.max_bytes:
            del self.tail[:-self.max_bytes]

    def result(self) -> tuple[bytes, bytes, bool]:
        if self.total <= self.max_bytes:
            complete = bytes(self.tail)
            return complete, complete, False
        tail_size = self.max_bytes - self.head_bytes
        tail = bytes(self.tail[-tail_size:]) if tail_size else b""
        return bytes(self.head), tail, True


def _decode(source: _Source, window: _Window, zstd, *, prefix_only: bool) -> None:
    frames = blocks = 0
    while source.used < source.size:
        frames += 1
        if frames > MAX_FRAMES:
            raise ZstdReadError("frame_limit")
        magic_bytes = source.read(4)
        magic = int.from_bytes(magic_bytes, "little")
        if 0x184D2A50 <= magic <= 0x184D2A5F:
            source.skip(int.from_bytes(source.read(4), "little"))
            continue
        if magic != _MAGIC:
            raise ZstdReadError("invalid_data")
        descriptor = source.read(1)
        value = descriptor[0]
        single = bool(value & 0x20)
        size_flag = value >> 6
        size_bytes = (1 if single else 0) if not size_flag else (2, 4, 8)[size_flag - 1]
        dictionary_bytes = (0, 1, 2, 4)[value & 3]
        header = magic_bytes + descriptor + source.read((0 if single else 1) + dictionary_bytes + size_bytes)
        parameters = zstd.get_frame_parameters(header)
        if parameters.window_size > MAX_DECODER_WINDOW_BYTES:
            raise ZstdReadError("window_limit")
        if parameters.dict_id:
            raise ZstdReadError("dictionary_required")
        block_max = min(MAX_BLOCK_BYTES, parameters.window_size)
        # backend_c 0.25.0 passes this value to ZSTD_DCtx_setMaxWindowSize in
        # bytes. The explicit header check above also bounds other backends.
        decoder = zstd.ZstdDecompressor(max_window_size=MAX_DECODER_WINDOW_BYTES).decompressobj()
        source.work.check()
        if decoder.decompress(header):
            raise ZstdReadError("invalid_data")
        source.work.check()
        while True:
            blocks += 1
            if blocks > MAX_BLOCKS:
                raise ZstdReadError("block_limit")
            block_header = source.read(3)
            block = int.from_bytes(block_header, "little")
            last, kind, size = bool(block & 1), (block >> 1) & 3, block >> 3
            if kind == 3 or size > block_max:
                raise ZstdReadError("invalid_data")
            # Compressed block output cannot be known cheaply beforehand. Stop
            # conservatively rather than decode beyond the work budget (at most
            # one block's unused allowance). Raw/RLE lengths are exact.
            output_bound = block_max if kind == 2 else size
            if window.total + output_bound > source.work.decoded_limit:
                raise ZstdReadError("decoded_limit")
            payload = source.read(1 if kind == 1 else size)
            if source.work.shared is not None:
                source.work.shared.charge_decoded(output_bound)
            source.work.check()
            data = decoder.decompress(block_header + payload)
            source.work.check()
            if len(data) > output_bound or decoder.unused_data:
                raise ZstdReadError("invalid_data")
            window.append(data)
            source.work.check()
            if prefix_only and len(window.head) >= window.head_bytes:
                return
            if last:
                if parameters.has_checksum:
                    if decoder.decompress(source.read(4)):
                        raise ZstdReadError("invalid_data")
                    source.work.check()
                if not decoder.eof or decoder.unused_data:
                    raise ZstdReadError("truncated")
                break
            if decoder.eof:
                raise ZstdReadError("invalid_data")
    if not frames:
        raise ZstdReadError("truncated")


def _read(path: Path, *, head_bytes: int, max_bytes: int, prefix_only: bool) -> _Window | _CachedWindow:
    if (type(head_bytes) is not int or type(max_bytes) is not int
            or not 0 < head_bytes <= max_bytes <= MAX_WINDOW_BYTES):
        raise ValueError("invalid_window")
    window = _Window(head_bytes, max_bytes)
    work = _WorkBudget(prefix_only)
    try:
        zstd = importlib.import_module("zstandard")
    except ImportError:
        raise ZstdReadError("dependency_missing") from None
    handle = None
    initial = None
    opened_initial = None
    ancestor_initial = None
    path_key = None
    cache_key = None
    cached = None
    reason = None
    try:
        # Normalize dot segments without resolving/following any symlinks.
        path = Path(os.path.abspath(path))
        path_key = os.path.normcase(str(path))
        cache_key = (path_key, head_bytes, max_bytes)
        work.check()
        ancestor_initial = _ancestors(path)
        before = path.lstat()
        if not _regular(before):
            raise ZstdReadError("unsafe_source")
        initial = _fingerprint(before)
        handle = path.open("rb", buffering=0)
        opened = os.fstat(handle.fileno())
        opened_initial = _fingerprint(opened)
        # Windows Python can expose birth time through lstat.st_ctime and
        # change time through fstat.st_ctime. Compare their common identity,
        # size and mtime here; compare each full fingerprint with itself later.
        if not _regular(opened) or opened_initial[:4] != initial[:4]:
            raise ZstdReadError("source_changed")
        work.check()
        if not prefix_only:
            # lstat alone is insufficient on Windows, where its ctime can be
            # birth time. Even a hit must open safely and compare the fd's
            # independent change-time baseline, then revalidate in finally.
            cached = _cache_get(cache_key, initial, opened_initial, ancestor_initial)
        if cached is not None:
            window = _CachedWindow(cached, head_bytes)
        else:
            with _CACHE_LOCK:
                _CACHE_STATS["prefix_decodes" if prefix_only else "decodes"] += 1
            _decode(_Source(handle, opened.st_size, work), window, zstd, prefix_only=prefix_only)
        work.check()
    except ZstdReadError as error:
        reason = error.reason
    except zstd.ZstdError:
        reason = "invalid_data"
    except OSError:
        reason = "source_io"
    finally:
        if handle is not None:
            try:
                after = path.lstat()
                opened = os.fstat(handle.fileno())
                if (not _regular(after) or not _regular(opened)
                        or _fingerprint(after) != initial or _fingerprint(opened) != opened_initial
                        or _ancestors(path) != ancestor_initial):
                    reason = "source_changed"
            except (OSError, ZstdReadError):
                reason = "source_changed"
            finally:
                handle.close()
    if reason is None:
        try:
            work.check()
        except ZstdReadError as error:
            reason = error.reason
    if reason is not None:
        if path_key is not None and reason in {"unsafe_source", "source_changed"}:
            _cache_drop_path(path_key)
        head = b"" if reason in {"unsafe_source", "source_changed"} else bytes(window.head)
        raise ZstdReadError(reason, head) from None
    if not prefix_only and cached is None:
        result = window.result()
        _cache_put(cache_key, initial, opened_initial, ancestor_initial, result)
        return _CachedWindow(result, head_bytes)
    return window


def read_zstd_window(path: Path, *, head_bytes: int, max_bytes: int) -> tuple[bytes, bytes, bool]:
    """Return a complete bounded window, or raise without a lifecycle tail.

Small logs return the same complete bytes twice. For a larger decoded log,
``gap`` is true and tail is raw: callers must discard its first partial line.
Retained buffers are at most head_bytes + max_bytes, plus one 128 KiB block;
    the decoder has an independently bounded 8 MiB history window. All frames,
    including appended frames/skippable metadata, must be complete within the
    input, decoded-work and cooperative time budgets. Verified finite windows
    can be reused only while safely opened source fingerprints remain unchanged.
    """
    return _read(path, head_bytes=head_bytes, max_bytes=max_bytes, prefix_only=False).result()


def read_zstd_head(path: Path, max_bytes: int = 64 * 1024) -> bytes:
    """Read metadata prefix only; reaching max_bytes does not validate frame EOF.

    Stops after the first block supplying the requested prefix (up to 128 KiB of
    decoded work per block), with independent 256 KiB input/decoded and 0.25 s
    cooperative budgets. This must never infer lifecycle completion.
    """
    return bytes(_read(path, head_bytes=max_bytes, max_bytes=max_bytes, prefix_only=True).head)
