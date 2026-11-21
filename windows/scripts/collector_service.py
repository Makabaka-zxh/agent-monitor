#!/usr/bin/env python3
"""Install/manage this Windows user's Monitor collector. Local access defaults to status-only."""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid

OWNER = "monitor-windows-collector-v1"
RUN_KEY_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE_NAME = "MonitorCollector"
PROXY_KEYS = (
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
)
CAPTURE_KEYS = (*PROXY_KEYS, "CODEX_HOME", "CLAUDE_CONFIG_DIR")

_policy_spec = importlib.util.spec_from_file_location("monitor_win_collector_policy", Path(__file__).with_name("collector_policy.py"))
policy = importlib.util.module_from_spec(_policy_spec)
_policy_spec.loader.exec_module(policy)


def normalize_server_origin(value: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 512
        or any(ord(char) <= 32 or ord(char) >= 127 for char in value)
        or "%" in value
        or "\\" in value
    ):
        raise ValueError("Configure an HTTPS server origin")
    parsed = urllib.parse.urlsplit(value)
    host, port = parsed.hostname, parsed.port
    if (
        parsed.scheme != "https"
        or not host
        or parsed.username is not None
        or parsed.password is not None
        or "?" in value
        or "#" in value
        or parsed.path not in ("", "/")
        or parsed.netloc.endswith(":")
        or not re.fullmatch(r"(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]+)?", parsed.netloc)
        or port is not None and not 1 <= port <= 65535
    ):
        raise ValueError("Configure an HTTPS server origin")
    if ":" in host:
        ipaddress.IPv6Address(host)
        host = "[" + host.lower() + "]"
    elif len(host) > 253 or not all(
        re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", part) for part in host.split(".")
    ):
        raise ValueError("Configure an HTTPS server origin")
    return "https://" + host.lower() + (f":{port}" if port not in (None, 443) else "")


def paths(local_app_data: Path | None = None) -> dict[str, Path]:
    root = local_app_data or Path(os.environ.get("LOCALAPPDATA") or (Path.home() / "AppData" / "Local"))
    base = root / "Monitor" / "collector"
    return {
        "base": base,
        "state": base / "agent",
        "logs": base / "logs",
        "bin": base / "bin",
        "hooks": base / "hook-state",
        "releases": base / "releases",
        "current": base / "current.json",
        "environment": base / "environment.json",
        "owner": base / "managed-install.json",
        "mode": base / "mode.json",
    }


def write_private(path: Path, data: bytes) -> None:
    if path.is_symlink():
        raise ValueError(f"Refusing symbolic link: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".monitor-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def json_bytes(value: dict) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def paired_server_matches(locations: dict[str, Path], server: str) -> bool:
    expected = normalize_server_origin(server)
    config = locations["state"] / "config.json"
    if not config.is_file() or config.is_symlink() or config.stat().st_size > 64 * 1024:
        return False
    try:
        data = json.loads(config.read_text(encoding="utf-8"))
        return isinstance(data, dict) and normalize_server_origin(data.get("server")) == expected
    except (OSError, ValueError, TypeError):
        return False


def require_paired_server(locations: dict[str, Path], server: str | None) -> None:
    if server is not None and not paired_server_matches(locations, server):
        raise ValueError("Pair this Windows PC with the App's configured server first")


def require_install(locations: dict[str, Path]) -> None:
    if not locations["owner"].is_file():
        raise ValueError("Collector is not installed")
    if json.loads(locations["owner"].read_text(encoding="utf-8")).get("owner") != OWNER:
        raise ValueError("This collector directory belongs to another installation")


def current_runtime(locations: dict[str, Path]) -> Path:
    require_install(locations)
    release = json.loads(locations["current"].read_text(encoding="utf-8")).get("release", "")
    if not isinstance(release, str) or len(release) != 32 or any(c not in "0123456789abcdef" for c in release):
        raise ValueError("Invalid installed release")
    runtime = locations["releases"] / release
    if runtime.resolve().parent != locations["releases"].resolve():
        raise ValueError("Invalid installed runtime")
    if json.loads((runtime / "managed-release.json").read_text(encoding="utf-8")).get("owner") != OWNER:
        raise ValueError("Unmanaged runtime")
    return runtime


def collector_locked(locations: dict[str, Path]) -> bool:
    import msvcrt

    lock_path = locations["state"] / "collector.lock"
    if not lock_path.exists():
        return False
    try:
        with open(lock_path, "a+b") as handle:
            handle.seek(0)
            if handle.tell() == 0:
                return False
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                return True
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            return False
    except OSError:
        return True


def _set_autostart(command_line: str | None) -> None:
    if sys.platform != "win32":
        return
    import winreg

    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY_PATH) as key:
        if command_line:
            winreg.SetValueEx(key, RUN_VALUE_NAME, 0, winreg.REG_SZ, command_line)
        else:
            try:
                winreg.DeleteValue(key, RUN_VALUE_NAME)
            except FileNotFoundError:
                pass


def _process_image_path(pid: int) -> Path | None:
    if sys.platform != "win32" or pid <= 0:
        return None
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    handle = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return None
    try:
        size = ctypes.c_ulong(4096)
        buffer = ctypes.create_unicode_buffer(size.value)
        if ctypes.windll.kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
            return Path(buffer.value[: size.value])
        return None
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)


def _is_managed_collector_process(pid: int, locations: dict[str, Path]) -> bool:
    image = _process_image_path(pid)
    if image is None or not image.name.lower().startswith("python"):
        return False
    try:
        resolved_image = image.resolve()
        resolved_releases = locations["releases"].resolve()
        return resolved_image.is_relative_to(resolved_releases)
    except OSError:
        return False


def _terminate_pid(pid: int) -> None:
    PROCESS_TERMINATE = 0x0001
    handle = ctypes.windll.kernel32.OpenProcess(PROCESS_TERMINATE, False, pid)
    if handle:
        try:
            ctypes.windll.kernel32.TerminateProcess(handle, 0)
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)


def stop(locations: dict[str, Path], *, clear_autostart: bool = True) -> None:
    if clear_autostart:
        _set_autostart(None)
    pid_file = locations["state"] / "collector.pid"
    if pid_file.is_file() and collector_locked(locations):
        try:
            pid = int(pid_file.read_text(encoding="utf-8").strip())
            if pid > 0 and _is_managed_collector_process(pid, locations):
                _terminate_pid(pid)
        except (OSError, ValueError):
            pass
    deadline = time.monotonic() + 10
    while collector_locked(locations):
        if time.monotonic() >= deadline:
            raise RuntimeError("Previous collector is still stopping; retry later")
        time.sleep(0.2)
    pid_file.unlink(missing_ok=True)


def start(locations: dict[str, Path]) -> None:
    runtime = current_runtime(locations)
    if not (locations["state"] / "config.json").is_file():
        raise ValueError("尚未配对，请先运行 pair")
    pythonw = runtime / "venv" / "Scripts" / "pythonw.exe"
    if not pythonw.is_file():
        pythonw = runtime / "venv" / "Scripts" / "python.exe"
    runner = runtime / "scripts" / "collector_runner.py"
    args = [str(pythonw), "-I", str(runner), "run", "--state-dir", str(locations["state"])]
    autostart_cmd = f'"{pythonw}" -I "{runner}" run --state-dir "{locations["state"]}"'
    _set_autostart(autostart_cmd)
    if collector_locked(locations):
        return
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000) | getattr(subprocess, "DETACHED_PROCESS", 0x00000008)
    subprocess.Popen(
        args,
        cwd=str(runtime),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=creationflags,
    )
    deadline = time.monotonic() + 10
    stable = 0
    while time.monotonic() < deadline:
        stable = stable + 1 if collector_locked(locations) else 0
        if stable >= 2:
            return
        time.sleep(0.2)
    raise RuntimeError("Collector did not become ready; check its private log")


def captured_environment(source: dict[str, str], previous: dict[str, str] | None = None) -> dict[str, str]:
    result = {key: value for key, value in (previous or {}).items() if key in CAPTURE_KEYS}
    for key in CAPTURE_KEYS:
        if key in source:
            value = source[key]
            if "\x00" in value or len(value) > 32768:
                raise ValueError(f"Invalid {key} setting")
            result[key] = value
    path_value = source.get("PATH") or os.environ.get("PATH") or ""
    if "\x00" not in path_value and len(path_value) <= 32768:
        result["PATH"] = path_value
    return result


def install(project: Path, locations: dict[str, Path] | None = None) -> None:
    locations = locations or paths()
    project = project.resolve()
    source = project / "agent_monitor"
    if not (source / "remote_agent.py").is_file():
        raise ValueError("Source directory does not contain the Monitor connector")
    if locations["base"].exists() and locations["owner"].exists():
        require_install(locations)
    for key in ("base", "state", "logs", "releases", "bin", "hooks"):
        locations[key].mkdir(parents=True, exist_ok=True)
    if not locations["owner"].exists():
        write_private(locations["owner"], json_bytes({"owner": OWNER}))
    policy.read_mode(locations["mode"])
    was_running = collector_locked(locations)
    previous = {}
    if locations["environment"].is_file():
        previous = json.loads(locations["environment"].read_text(encoding="utf-8"))
    environment = captured_environment(dict(os.environ), previous)
    release = uuid.uuid4().hex
    runtime = locations["releases"] / release
    runtime.mkdir(parents=True, exist_ok=True)
    write_private(runtime / "managed-release.json", json_bytes({"owner": OWNER, "created": datetime.now(timezone.utc).isoformat()}))
    (runtime / "agent_monitor").mkdir(parents=True, exist_ok=True)
    for path in source.glob("*.py"):
        if path.is_symlink():
            raise ValueError("Connector sources must be regular files")
        shutil.copyfile(path, runtime / "agent_monitor" / path.name)
    (runtime / "scripts").mkdir(parents=True, exist_ok=True)
    for script_name in ("collector_runner.py", "collector_service.py", "collector_policy.py"):
        shutil.copyfile(Path(__file__).with_name(script_name), runtime / "scripts" / script_name)
    requirements = Path(__file__).with_name("requirements-connector.txt")
    shutil.copyfile(requirements, runtime / "requirements-connector.txt")
    subprocess.run([sys.executable, "-m", "venv", str(runtime / "venv")], check=True)
    python = runtime / "venv" / "Scripts" / "python.exe"
    subprocess.run(
        [str(python), "-m", "pip", "install", "--disable-pip-version-check", "--only-binary=:all:", "-r", str(runtime / "requirements-connector.txt")],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    subprocess.run([str(python), "-I", "-c", "import fastapi, qrcode, PIL, zstandard"], check=True)
    stop(locations, clear_autostart=False)
    write_private(locations["environment"], json_bytes(environment))
    write_private(locations["current"], json_bytes({"release": release}))
    controller = locations["bin"] / "monitor-collector.cmd"
    cmd_text = f'@echo off\r\n"{python}" -I "{runtime / "scripts" / "collector_service.py"}" %*\r\n'
    write_private(controller, cmd_text.encode("utf-8"))
    if (locations["state"] / "config.json").is_file() and was_running:
        start(locations)
        print("连接器已更新并启动，本机同步范围保持不变。")
    elif (locations["state"] / "config.json").is_file():
        print("连接器已更新，本机同步范围和暂停状态保持不变。")
    else:
        print("连接器已安装。运行 pair 完成一次性配对后，会自动开始同步任务状态。")


def configure_mode(locations: dict[str, Path], mode: str) -> None:
    mode = policy.validate_mode(mode)
    current_runtime(locations)
    with policy.mode_lock(locations["base"]):
        previous = policy.read_mode(locations["mode"])
        if previous == mode:
            return
        was_running = collector_locked(locations)
        stop(locations, clear_autostart=not was_running)
        try:
            policy.write_mode(locations["mode"], mode)
            if was_running:
                start(locations)
        except Exception:
            try:
                stop(locations, clear_autostart=not was_running)
                policy.write_mode(locations["mode"], previous)
                if was_running:
                    start(locations)
            except Exception:
                raise RuntimeError("Mode change failed; recovery requires inspection") from None
            raise RuntimeError("Mode change failed; previous policy restored") from None


def status(locations: dict[str, Path], server: str | None = None) -> None:
    installed = locations["owner"].is_file()
    state = "running" if (installed and collector_locked(locations)) else "stopped"
    mode = policy.read_mode(locations["mode"]) if installed else policy.DEFAULT_MODE
    paired = (locations["state"] / "config.json").is_file()
    if paired and server is not None:
        paired = paired_server_matches(locations, server)
    print(json.dumps({
        "installed": installed,
        "paired": paired,
        "server_scope_version": 1,
        "service": state,
        "mode": mode,
        "mode_schema": policy.SCHEMA,
        "supported_modes": list(policy.MODES),
    }, ensure_ascii=False))


def main() -> int:
    parser = argparse.ArgumentParser(description="Monitor Windows 电脑连接器（当前用户、本机同步范围）")
    parser.add_argument("command", choices=("install", "pair", "start", "stop", "status", "mode", "uninstall"))
    parser.add_argument("--set", dest="set_mode", choices=policy.MODES)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--server", default=os.environ.get("MONITOR_SERVER_URL"))
    parser.add_argument("--name", default=os.environ.get("COMPUTERNAME") or "Windows PC")
    parser.add_argument("--code-stdin", action="store_true")
    args = parser.parse_args()
    if args.server is not None:
        try:
            args.server = normalize_server_origin(args.server)
        except ValueError:
            parser.error("请提供不带账号、路径或查询参数的 HTTPS 服务器地址")
    if args.command == "pair" and args.server is None:
        parser.error("配对需要 --server 或 MONITOR_SERVER_URL 指定 HTTPS 服务器地址")
    if (args.command == "mode") != (args.set_mode is not None):
        parser.error("mode 需要 --set；其他命令不接受 --set")
    if sys.platform != "win32" or sys.version_info < (3, 12):
        parser.error("需要 Windows 和 Python 3.12 或更新版本")
    locations = paths()
    try:
        if args.command == "install":
            install(args.source, locations)
        elif args.command == "status":
            status(locations, args.server)
        elif args.command == "mode":
            require_paired_server(locations, args.server)
            configure_mode(locations, args.set_mode)
            status(locations, args.server)
        else:
            runtime = current_runtime(locations)
            if args.command == "pair":
                stop(locations, clear_autostart=False)
                python = runtime / "venv" / "Scripts" / "python.exe"
                command = [
                    str(python), "-I", str(runtime / "scripts" / "collector_runner.py"),
                    "pair", "--state-dir", str(locations["state"]), "--server", args.server, "--name", args.name,
                ]
                if args.code_stdin:
                    command.append("--code-stdin")
                subprocess.run(command, check=True)
                start(locations)
                print("已开始同步任务状态。")
            elif args.command == "start":
                require_paired_server(locations, args.server)
                start(locations)
                print("连接器已启动。")
            elif args.command == "stop":
                stop(locations, clear_autostart=True)
                print("连接器已停止。")
            elif args.command == "uninstall":
                stop(locations, clear_autostart=True)
                print("后台自启已移除；本机配对、运行文件和日志均保留。")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"操作未完成（{type(error).__name__}）。请检查当前用户权限及 Python 环境。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
