"""Preview or explicitly apply a narrow, reversible Claude hook configuration.

Dry-run output never includes existing setting values or existing commands.
Only Agent Monitor's marked handlers are replaced/removed; all other settings
and hooks are preserved. No command in a settings file is executed here.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import stat
import sys
import time
import uuid
from pathlib import Path

from .claude_hook import HOOK_MARKER, hook_configuration
from .collector import state_directory

MAX_SETTINGS_BYTES = 2 * 1024 * 1024


class InstallError(ValueError):
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InstallError("设置中存在重复 JSON 字段；为避免覆盖，请先修正该文件。")
        result[key] = value
    return result


def read_settings(path: Path) -> tuple[bytes, dict]:
    if path.is_symlink():
        raise InstallError("不直接修改符号链接设置文件，请明确选择实际设置文件。")
    try:
        with path.open("rb") as handle:
            raw = handle.read(MAX_SETTINGS_BYTES + 1)
    except FileNotFoundError:
        return b"", {}
    if len(raw) > MAX_SETTINGS_BYTES:
        raise InstallError("设置文件过大，未进行修改。")
    try:
        data = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise InstallError("设置不是有效的 UTF-8 JSON，未进行修改。") from exc
    if not isinstance(data, dict):
        raise InstallError("设置的顶层必须是 JSON 对象，未进行修改。")
    return raw, data


def _owned(handler) -> bool:
    if not isinstance(handler, dict) or handler.get("type") != "command":
        return False
    args = handler.get("args")
    if not isinstance(args, list) or not args or not isinstance(args[0], str):
        return False
    script = args[0].replace("\\", "/")
    marker = any(args[i:i + 2] == ["--monitor-hook", HOOK_MARKER] for i in range(len(args) - 1))
    if marker and script.rsplit("/", 1)[-1] == "claude_hook.py":
        return True
    # Recognize this repository's earlier unmarked --print-config form too.
    return script.casefold() == str(Path(__file__).with_name("claude_hook.py").resolve()).replace("\\", "/").casefold()


def merge_hooks(settings: dict, configuration: dict, *, remove=False) -> tuple[dict, dict]:
    if not isinstance(settings, dict):
        raise InstallError("设置的顶层必须是 JSON 对象。")
    if not remove and (settings.get("disableAllHooks") is True or settings.get("allowManagedHooksOnly") is True):
        raise InstallError("当前设置禁用了用户 hooks；安装器不会更改该开关。")
    merged = copy.deepcopy(settings)
    existing = merged.get("hooks", {})
    if not isinstance(existing, dict):
        raise InstallError("现有 hooks 字段必须是对象，未进行修改。")
    hooks = {}
    removed = {}
    for event, entries in existing.items():
        if not isinstance(entries, list):
            raise InstallError("现有 hook 事件必须是数组，未进行修改。")
        retained = []
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("hooks"), list):
                raise InstallError("现有 hook 结构无法安全合并，未进行修改。")
            others = [handler for handler in entry["hooks"] if not _owned(handler)]
            count = len(entry["hooks"]) - len(others)
            if count:
                removed[event] = removed.get(event, 0) + count
            if others or not count:
                retained.append(entry | {"hooks": others})
        if retained or not entries:
            hooks[event] = retained
    appended = {} if remove else copy.deepcopy(configuration["hooks"])
    for event, entries in appended.items():
        hooks.setdefault(event, []).extend(entries)
    if hooks:
        merged["hooks"] = hooks
    elif "hooks" in merged and removed:
        del merged["hooks"]
    elif "hooks" in settings:
        merged["hooks"] = hooks
    return merged, {"removed_monitor_handlers": removed, "append": appended}


def prepare_plan(path: Path, *, state_dir=None, python_executable=None, remove=False) -> tuple[dict, dict, bytes]:
    raw, current = read_settings(path)
    python_path = Path(python_executable or sys.executable).expanduser().resolve()
    if not remove and not python_path.is_file():
        raise InstallError("指定 Python 解释器不存在，未进行修改。")
    configuration = hook_configuration(state_dir=state_dir, python_executable=str(python_path))
    merged, change = merge_hooks(current, configuration, remove=remove)
    changed = merged != current
    summary = {
        "settings_path": str(path),
        "state_dir": str(Path(state_dir).expanduser().resolve() if state_dir else state_directory().resolve()),
        "expected_sha256": hashlib.sha256(raw).hexdigest(),
        "changed": changed,
        "action": "remove" if remove else "install",
        "diff": change if changed else {"removed_monitor_handlers": {}, "append": {}},
    }
    return summary, merged, raw


def _write_private(path: Path, data: bytes, mode: int = 0o600):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def apply_plan(path: Path, expected_sha256: str, *, state_dir=None, python_executable=None, remove=False) -> dict:
    summary, merged, raw = prepare_plan(path, state_dir=state_dir, python_executable=python_executable, remove=remove)
    if not isinstance(expected_sha256, str) or summary["expected_sha256"] != expected_sha256:
        raise InstallError("设置自预览后发生变化，未写入；请重新预览。")
    if not summary["changed"]:
        return summary | {"applied": False, "backup": None}
    path.parent.mkdir(parents=True, exist_ok=True)
    backup = path.with_name(f"{path.name}.agent-monitor-{time.time_ns()}-{uuid.uuid4().hex[:8]}.bak") if path.exists() else None
    temporary = path.with_name(f".{path.name}.agent-monitor-{uuid.uuid4().hex}.tmp")
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    encoded = (json.dumps(merged, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if b"\r\n" in raw:
        encoded = encoded.replace(b"\n", b"\r\n")
    if raw.startswith(b"\xef\xbb\xbf"):
        encoded = b"\xef\xbb\xbf" + encoded
    try:
        _write_private(temporary, encoded, mode)
        # Avoid silently overwriting a settings edit made during preparation.
        current_raw, _ = read_settings(path)
        if hashlib.sha256(current_raw).hexdigest() != expected_sha256:
            raise InstallError("设置在写入前发生变化，未写入；请重新预览。")
        if backup:
            _write_private(backup, raw, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return summary | {"applied": True, "backup": str(backup) if backup else None}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="预览或合并安装 Agent Monitor 的 Claude 状态 hooks")
    default_home = Path(os.environ.get("CLAUDE_CONFIG_DIR") or (Path.home() / ".claude"))
    parser.add_argument("--settings", type=Path, default=default_home / "settings.json")
    parser.add_argument("--state-dir", help="Agent Monitor 状态根目录，需与采集器一致")
    parser.add_argument("--python-executable", help="运行 hook 的现有 Python 3.12+ 解释器")
    parser.add_argument("--remove", action="store_true", help="仅移除本工具的 hooks，保留其他设置")
    parser.add_argument("--apply", action="store_true", help="明确执行预览过的变更，写入前备份")
    parser.add_argument("--expected-sha256", help="预览输出中的当前设置摘要，用于防止冲突覆盖")
    args = parser.parse_args(argv)
    path = args.settings.expanduser().absolute()
    try:
        options = {"state_dir": args.state_dir, "python_executable": args.python_executable, "remove": args.remove}
        if args.apply:
            if not args.expected_sha256:
                raise InstallError("--apply 必须同时提供预览得到的 --expected-sha256。")
            result = apply_plan(path, args.expected_sha256, **options)
        else:
            result = prepare_plan(path, **options)[0]
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, InstallError, TypeError, RecursionError) as exc:
        # Do not echo existing settings, hook commands, or JSON error fragments.
        print(str(exc) if isinstance(exc, InstallError) else "无法安全读取或写入目标设置，未完成安装。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
