"""Preview by default; apply only the reviewed statusLine.command with a backup.

No existing statusline command is run by this installer. Other settings retain
their exact bytes, including credentials/proxy settings that are never printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_monitor.usage_statusline import atomic_write, decode, file_lock, load_config, safe_path

MAX_SETTINGS = 2 * 1024 * 1024


def read_settings(path):
    if not safe_path(path):
        raise ValueError("Settings path contains a link or reparse point.")
    try:
        with path.open("rb") as handle:
            raw = handle.read(MAX_SETTINGS + 1)
    except FileNotFoundError:
        return b"", {}
    if len(raw) > MAX_SETTINGS:
        raise ValueError("Settings file exceeds the safe size limit.")
    try:
        value = decode(raw)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise ValueError("Settings must be unambiguous UTF-8 JSON; no changes made.") from exc
    if not isinstance(value, dict):
        raise ValueError("Settings must be an object.")
    return raw, value


def members(text):
    """Locate JSON member value spans, without reparsing/reserializing neighbors."""
    decoder = json.JSONDecoder()
    position = 0
    while text[position].isspace(): position += 1
    if text[position] != "{": raise ValueError("Expected object")
    position += 1
    result = {}
    while True:
        while text[position].isspace(): position += 1
        if text[position] == "}": return result, position
        key, position = decoder.raw_decode(text, position)
        while text[position].isspace(): position += 1
        if text[position] != ":": raise ValueError("Expected member")
        position += 1
        while text[position].isspace(): position += 1
        begin = position
        _, position = decoder.raw_decode(text, position)
        result[key] = (begin, position)
        while text[position].isspace(): position += 1
        if text[position] == "}": return result, position
        if text[position] != ",": raise ValueError("Expected separator")
        position += 1


def patch_member(text, key, value):
    spans, end = members(text)
    encoded = json.dumps(value, ensure_ascii=False)
    if key in spans:
        start, finish = spans[key]
        return text[:start] + encoded + text[finish:]
    newline = "\r\n" if "\r\n" in text else "\n"
    insertion = ("," if spans else "") + newline + "  " + json.dumps(key) + ": " + encoded + newline
    return text[:end] + insertion + text[end:]


def patch_command(raw, command):
    bom = raw.startswith(b"\xef\xbb\xbf")
    text = raw.decode("utf-8-sig") if raw else "{}\n"
    spans, _ = members(text)
    if "statusLine" not in spans:
        text = patch_member(text, "statusLine", {"type": "command", "command": command})
    else:
        begin, finish = spans["statusLine"]
        text = text[:begin] + patch_member(text[begin:finish], "command", command) + text[finish:]
    return (b"\xef\xbb\xbf" if bom else b"") + text.encode("utf-8")


def shell_choice(kind=None, executable=None):
    if executable:
        shell = Path(executable).expanduser().resolve()
        if not shell.is_file() or kind is None:
            raise ValueError("An existing shell executable and shell kind are required.")
        return kind, str(shell)
    if kind is not None:
        raise ValueError("Specify --shell-executable with --shell-kind.")
    if os.name == "nt":
        git = shutil.which("git")
        if git:
            candidate = Path(git).resolve().parent.parent / "bin" / "bash.exe"
            if candidate.is_file(): return "bash", str(candidate)
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if powershell: return "powershell", str(Path(powershell).resolve())
    else:
        bash = shutil.which("bash")
        if bash: return "bash", str(Path(bash).resolve())
    raise ValueError("Could not identify Claude's shell; supply its executable and kind.")


def quote_command(args, shell_kind):
    args = [str(value).replace("\\", "/") for value in args]
    if shell_kind == "powershell":
        return "& " + " ".join("'" + value.replace("'", "''") + "'" for value in args)
    return " ".join(shlex.quote(value) for value in args)


def prepare(settings, *, python=None, shell_kind=None, shell_executable=None):
    settings = settings.expanduser().absolute()
    raw, value = read_settings(settings)
    status = value.get("statusLine")
    if "statusLine" in value and (not isinstance(status, dict) or status.get("type") != "command"):
        raise ValueError("Existing statusLine is not a command; leave it unchanged.")
    if value.get("disableAllHooks") is True or value.get("allowManagedHooksOnly") is True:
        raise ValueError("User statuslines are disabled; this installer does not alter that policy.")
    original = status.get("command") if status else None
    if original is not None and (not isinstance(original, str) or not original or "\0" in original):
        raise ValueError("Existing statusLine command is invalid; leave it unchanged.")
    config_path = settings.parent / "monitor-statusline.json"
    if config_path.exists():
        existing = load_config(config_path)
        if original == existing.get("wrapper_command"):
            return {"changed": False, "settings": str(settings), "expected_sha256": hashlib.sha256(raw).hexdigest()}, raw, existing
    interpreter = Path(python or sys.executable).expanduser().resolve()
    if not interpreter.is_file(): raise ValueError("Python executable does not exist.")
    kind, shell = shell_choice(shell_kind, shell_executable)
    wrapper = Path(__file__).resolve().parents[1] / "agent_monitor" / "usage_statusline.py"
    command = quote_command([interpreter, wrapper, "--config", config_path], kind)
    config = {"schema": 1, "original_command": original, "shell_kind": kind,
              "shell_executable": shell, "quota_path": str(settings.parent / "monitor-quota.json"),
              "wrapper_command": command}
    result = patch_command(raw, command)
    # Confirm the exact text patch changes only this narrowly owned setting.
    check = decode(result)
    before_other = {k: v for k, v in value.items() if k != "statusLine"}
    after_other = {k: v for k, v in check.items() if k != "statusLine"}
    if before_other != after_other: raise ValueError("Unexpected neighboring settings change.")
    if status and {k: v for k, v in status.items() if k != "command"} != {k: v for k, v in check["statusLine"].items() if k != "command"}:
        raise ValueError("Unexpected neighboring statusLine change.")
    return {"changed": result != raw, "settings": str(settings), "config_path": str(config_path),
            "quota_path": config["quota_path"], "expected_sha256": hashlib.sha256(raw).hexdigest(),
            "existing_command_preserved": original is not None, "shell_kind": kind,
            "new_statusLine_command": command}, result, config


def apply(settings, expected_sha256, **options):
    settings = settings.expanduser().absolute()
    if not safe_path(settings): raise ValueError("Unsafe settings path.")
    settings.parent.mkdir(parents=True, exist_ok=True)
    with file_lock(settings.with_name(".monitor-statusline-install.lock"), timeout=1):
        plan, changed, config = prepare(settings, **options)
        if plan["expected_sha256"] != expected_sha256:
            raise ValueError("Settings changed since preview; preview again.")
        if not plan["changed"]: return plan | {"applied": False}
        raw, _ = read_settings(settings)
        suffix = ".monitor-statusline-" + str(time.time_ns()) + ".bak"
        backup = settings.with_name(settings.name + suffix)
        # Exclusive backup creation prevents any prior backup from being overwritten.
        if raw:
            with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as handle: handle.write(raw)
        config_path = Path(plan["config_path"])
        if config_path.exists():
            with config_path.open("rb") as handle: old_config = handle.read(256 * 1024 + 1)
            with os.fdopen(os.open(config_path.with_name(config_path.name + suffix), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as handle: handle.write(old_config)
        atomic_write(config_path, json.dumps(config, ensure_ascii=False, indent=2).encode("utf-8"))
        latest, _ = read_settings(settings)
        if hashlib.sha256(latest).hexdigest() != expected_sha256:
            raise ValueError("Settings changed while preparing; original settings retained.")
        atomic_write(settings, changed, settings.stat().st_mode & 0o777 if settings.exists() else 0o600)
        return plan | {"applied": True, "backup": str(backup) if raw else None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, default=Path.home() / ".claude" / "settings.json")
    parser.add_argument("--python", help="Existing Python interpreter")
    parser.add_argument("--shell-kind", choices=("bash", "posix", "powershell"))
    parser.add_argument("--shell-executable")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--expected-sha256")
    args = parser.parse_args(argv)
    options = {"python": args.python, "shell_kind": args.shell_kind, "shell_executable": args.shell_executable}
    try:
        if args.apply and not args.expected_sha256: raise ValueError("Apply requires the preview's --expected-sha256.")
        result = apply(args.settings, args.expected_sha256, **options) if args.apply else prepare(args.settings, **options)[0]
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, UnicodeError, RecursionError):
        print("Could not safely prepare/apply the statusline configuration. No original commands or setting values are logged.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
