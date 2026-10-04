"""Opt-in Claude statusline receiver. No network, credentials or transcript reads.

Documented input: https://code.claude.com/docs/en/statusline
Only the two numeric subscription windows are retained. A user's existing
statusline is executed as its original shell command, with unchanged input,
inherited stdout/stderr, environment, cwd and exit status. Input for quota
parsing is bounded; oversized input is still streamed to the original command.
"""
from __future__ import annotations

import argparse
import contextlib
import json
import math
import os
import signal
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

MAX_INPUT = 256 * 1024
MAX_FILE = 16384
MAX_CONFIG = 256 * 1024
UTC = timezone.utc


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def decode(raw):
    return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))


def safe_path(path: Path):
    """Reject links/reparse points in every existing component, including parents."""
    current = path.absolute()
    while True:
        try:
            info = current.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                return False
        except FileNotFoundError:
            pass
        except OSError:
            return False
        if current == current.parent:
            return True
        current = current.parent


def sanitize(payload, observed=None):
    if not isinstance(payload, dict):
        return None
    observed = observed or datetime.now(UTC)
    if not isinstance(observed, datetime) or observed.tzinfo is None:
        raise ValueError("observation must have timezone")
    limits = payload.get("rate_limits")
    if limits is not None and not isinstance(limits, dict):
        return None
    windows = {}
    for slot in ("five_hour", "seven_day"):
        window = (limits or {}).get(slot)
        if not isinstance(window, dict):
            continue
        used, resets = window.get("used_percentage"), window.get("resets_at")
        if (type(used) not in (int, float) or not 0 <= used <= 100 or not math.isfinite(used)
                or type(resets) not in (int, float) or not 0 < resets <= 253402300799
                or not math.isfinite(resets) or resets != int(resets)):
            continue
        # Expired windows remain expired; never manufacture a new zero reading.
        windows[slot] = {"used_percentage": used, "resets_at": int(resets)}
    return {"observed_at": observed.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z"),
            "rate_limits": windows}


@contextlib.contextmanager
def file_lock(path: Path, timeout=0.075):
    if not safe_path(path):
        raise OSError("unsafe lock path")
    handle = path.open("a+b")
    acquired = False
    try:
        if handle.seek(0, 2) == 0:
            handle.write(b"0"); handle.flush()
        until = time.monotonic() + timeout
        while not acquired:
            try:
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
            except OSError:
                if time.monotonic() >= until:
                    raise TimeoutError("lock busy")
                time.sleep(.005)
        yield
    finally:
        if acquired:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_UN)
        handle.close()


def atomic_write(path: Path, raw: bytes, mode=0o600):
    if not safe_path(path):
        raise OSError("unsafe output path")
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + "-", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            if os.name != "nt":
                os.fchmod(handle.fileno(), mode)
            handle.write(raw); handle.flush(); os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def record(payload, output: Path, observed=None):
    value = sanitize(payload, observed)
    if value is None or not safe_path(output) or not output.parent.is_dir():
        return False
    with file_lock(output.with_suffix(output.suffix + ".lock")):
        try:
            with output.open("rb") as handle:
                previous = decode(handle.read(MAX_FILE + 1))
            stamp = previous.get("observed_at") if isinstance(previous, dict) else None
            old = datetime.fromisoformat(stamp.replace("Z", "+00:00")) if isinstance(stamp, str) else None
            incoming = datetime.fromisoformat(value["observed_at"].replace("Z", "+00:00"))
            if old is not None and old.tzinfo is not None and old >= incoming:
                return False
        except (OSError, ValueError, UnicodeError, RecursionError):
            pass
        atomic_write(output, json.dumps(value, allow_nan=False, separators=(",", ":")).encode("utf-8"))
    return True


def load_config(path: Path):
    if not safe_path(path):
        raise ValueError("unsafe config")
    with path.open("rb") as handle:
        raw = handle.read(MAX_CONFIG + 1)
    if len(raw) > MAX_CONFIG:
        raise ValueError("config too large")
    value = decode(raw)
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ValueError("invalid config")
    command = value.get("original_command")
    if command is not None and (not isinstance(command, str) or not command or "\0" in command):
        raise ValueError("invalid original command")
    return value


def original_argv(config):
    command = config.get("original_command")
    if command is None:
        return None
    shell = config.get("shell_executable")
    if not isinstance(shell, str) or not Path(shell).is_absolute() or not Path(shell).is_file():
        raise ValueError("configured shell unavailable")
    kind = config.get("shell_kind")
    if kind in ("bash", "posix"):
        return [shell, "-c", command]
    if kind == "powershell":
        return [shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command]
    raise ValueError("unknown original shell")


def run(config, input_stream, *, stdout=None, stderr=None):
    observed = datetime.now(UTC)
    argv = original_argv(config)
    child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr, shell=False) if argv else None
    captured = bytearray()
    oversized = False
    forwarding = child is not None
    try:
        while True:
            chunk = input_stream.read(65536 if child else MAX_INPUT + 1)
            if not chunk:
                break
            if not oversized:
                if len(captured) + len(chunk) <= MAX_INPUT:
                    captured.extend(chunk)
                else:
                    oversized = True; captured.clear()
            if forwarding:
                try:
                    child.stdin.write(chunk)
                except (BrokenPipeError, OSError):
                    forwarding = False
            if not child:
                break
    finally:
        if child:
            try:
                child.stdin.close()
            except (BrokenPipeError, OSError):
                pass
    if not oversized:
        try:
            output = Path(config["quota_path"])
            record(decode(bytes(captured)), output, observed)
        except (OSError, ValueError, KeyError, TypeError, UnicodeError, RecursionError):
            pass  # Capture failure must never change the user's statusline output/exit.
    if child:
        return child.wait()
    target = stdout if stdout is not None else sys.stdout.buffer
    target.write(b"Claude Code\n"); target.flush()
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Monitor numeric Claude statusline capture")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        return run(load_config(args.config), sys.stdin.buffer)
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        return 1  # Do not echo configuration, commands, or input on failure.


if __name__ == "__main__":
    result = main()
    if result < 0 and os.name != "nt":
        signal.signal(-result, signal.SIG_DFL)
        os.kill(os.getpid(), -result)
    raise SystemExit(result)
