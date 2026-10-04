"""Local-only connector permission ceiling; never derived from account preferences."""
from __future__ import annotations

from contextlib import contextmanager
import json
import os
from pathlib import Path
import stat
import tempfile

MODES = ("status-only", "results", "full")
SCHEMA = 1
DEFAULT_MODE = "status-only"
MAX_BYTES = 1024


def validate_mode(value: object) -> str:
    if not isinstance(value, str) or value not in MODES:
        raise ValueError("Invalid local connector mode")
    return value


def _owned(path: Path, *, directory: bool = False) -> None:
    # Follow no symbolic-link component, including a replaced private parent.
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError("Symbolic link in local mode path")
    item = path.stat()
    expected = stat.S_ISDIR(item.st_mode) if directory else stat.S_ISREG(item.st_mode)
    if not expected or item.st_uid != os.getuid() or item.st_mode & 0o077:
        raise ValueError("Local mode path must be private and owned")


def read_mode(path: Path) -> str:
    _owned(path.parent, directory=True)
    if path.is_symlink():
        raise ValueError("Invalid local mode path")
    if not path.exists():
        return DEFAULT_MODE
    _owned(path)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or info.st_size > MAX_BYTES):
            raise ValueError("Invalid local mode file")
        raw = source.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("Local mode file too large")
    value = json.loads(raw)
    if (not isinstance(value, dict) or set(value) != {"version", "mode"}
            or type(value["version"]) is not int or value["version"] != SCHEMA):
        raise ValueError("Unsupported local mode file")
    return validate_mode(value["mode"])


def write_mode(path: Path, mode: str) -> None:
    mode = validate_mode(mode)
    _owned(path.parent, directory=True)
    if path.is_symlink():
        raise ValueError("Invalid local mode path")
    if path.exists():
        _owned(path)
    descriptor, name = tempfile.mkstemp(prefix=".mode-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write((json.dumps({"version": SCHEMA, "mode": mode}) + "\n").encode())
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def mode_lock(base: Path):
    import fcntl
    _owned(base, directory=True)
    path = base / "mode-change.lock"
    if path.is_symlink():
        raise ValueError("Invalid mode lock")
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise ValueError("Invalid mode lock")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another local mode change is in progress") from None
        yield
    finally:
        os.close(descriptor)


def run_options(mode: str) -> dict[str, bool]:
    mode = validate_mode(mode)
    return {"status_only": mode == "status-only", "allow_replies": mode == "full"}
