"""Local-only Windows connector permission ceiling; never derived from account preferences."""
from __future__ import annotations

from contextlib import contextmanager
import json
import os
from pathlib import Path
import tempfile

MODES = ("status-only", "results", "full")
SCHEMA = 1
DEFAULT_MODE = "status-only"
MAX_BYTES = 1024


def validate_mode(value: object) -> str:
    if not isinstance(value, str) or value not in MODES:
        raise ValueError("Invalid local connector mode")
    return value


def _no_symlinks(path: Path) -> None:
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError("Symbolic link in local mode path")


def read_mode(path: Path) -> str:
    _no_symlinks(path)
    if not path.exists():
        return DEFAULT_MODE
    if not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise ValueError("Invalid local mode file")
    raw = path.read_bytes()
    if len(raw) > MAX_BYTES:
        raise ValueError("Local mode file too large")
    value = json.loads(raw.decode("utf-8"))
    if (
        not isinstance(value, dict)
        or set(value) != {"version", "mode"}
        or type(value["version"]) is not int
        or value["version"] != SCHEMA
    ):
        raise ValueError("Unsupported local mode file")
    return validate_mode(value["mode"])


def write_mode(path: Path, mode: str) -> None:
    mode = validate_mode(mode)
    _no_symlinks(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".mode-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write((json.dumps({"version": SCHEMA, "mode": mode}) + "\n").encode("utf-8"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def mode_lock(base: Path):
    import msvcrt

    _no_symlinks(base)
    base.mkdir(parents=True, exist_ok=True)
    lock_path = base / "mode-change.lock"
    if lock_path.is_symlink():
        raise ValueError("Invalid mode lock")
    with open(lock_path, "a+b") as handle:
        handle.seek(0)
        if handle.tell() == 0:
            handle.write(b"1")
            handle.flush()
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise RuntimeError("Another local mode change is in progress") from None
        try:
            yield
        finally:
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            except OSError:
                pass


def run_options(mode: str) -> dict[str, bool]:
    mode = validate_mode(mode)
    return {"status_only": mode == "status-only", "allow_replies": mode == "full"}
