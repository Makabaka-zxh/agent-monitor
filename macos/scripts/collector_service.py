#!/usr/bin/env python3
"""Install/manage this user's Monitor collector. Local access defaults to status-only."""
from __future__ import annotations

import argparse
import importlib.util
from datetime import datetime, timezone
import json
import os
import ipaddress
import re
from pathlib import Path
import plistlib
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
import urllib.parse

LABEL = "com.agentmonitor.collector"
OWNER = "monitor-macos-collector-v1"
PROXY_KEYS = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
              "http_proxy", "https_proxy", "all_proxy", "no_proxy")
CAPTURE_KEYS = (*PROXY_KEYS, "CODEX_HOME", "CLAUDE_CONFIG_DIR")

_policy_spec = importlib.util.spec_from_file_location("monitor_collector_policy", Path(__file__).with_name("collector_policy.py"))
policy = importlib.util.module_from_spec(_policy_spec)
_policy_spec.loader.exec_module(policy)


def normalize_server_origin(value: str) -> str:
    """Match the native App's HTTPS root-origin boundary; never infer a server."""
    if (not isinstance(value, str) or not value or len(value) > 512
            or any(ord(char) <= 32 or ord(char) >= 127 for char in value)
            or '%' in value or '\\' in value):
        raise ValueError("Configure an HTTPS server origin")
    parsed = urllib.parse.urlsplit(value)
    host, port = parsed.hostname, parsed.port
    if (parsed.scheme != 'https' or not host or parsed.username is not None or parsed.password is not None
            or '?' in value or '#' in value or parsed.path not in ('', '/') or parsed.netloc.endswith(':')
            or not re.fullmatch(r'(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]+)?', parsed.netloc)
            or port is not None and not 1 <= port <= 65535):
        raise ValueError("Configure an HTTPS server origin")
    if ':' in host:
        ipaddress.IPv6Address(host)
        host = '[' + host.lower() + ']'
    elif len(host) > 253 or not all(re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?', part)
                                     for part in host.split('.')):
        raise ValueError("Configure an HTTPS server origin")
    return 'https://' + host.lower() + (f':{port}' if port not in (None, 443) else '')


def paired_server_matches(locations: dict[str, Path], server: str) -> bool:
    expected = normalize_server_origin(server)
    config = locations['state'] / 'config.json'
    owned_path(config)
    if not config.is_file() or config.stat().st_size > 64 * 1024:
        return False
    try:
        data = json.loads(config.read_text(encoding='utf-8'))
        return isinstance(data, dict) and normalize_server_origin(data.get('server')) == expected
    except (OSError, ValueError, TypeError):
        return False


def require_paired_server(locations: dict[str, Path], server: str | None) -> None:
    # A CLI start without --server uses its existing bound pairing. Native callers always supply it.
    if server is not None and not paired_server_matches(locations, server):
        raise ValueError("Pair this Mac with the App's configured server first")


def paths(home: Path) -> dict[str, Path]:
    base = home / "Library" / "Application Support" / "Monitor" / "collector"
    return {"base": base, "state": base / "agent", "logs": base / "logs", "bin": base / "bin", "hooks": base / "hook-state",
            "releases": base / "releases", "current": base / "current.json",
            "environment": base / "environment.json", "owner": base / "managed-install.json", "mode": base / "mode.json",
            "plist": home / "Library" / "LaunchAgents" / f"{LABEL}.plist"}


def _mise_owned(path: Path, uid: int, *, directory: bool = False) -> bool:
    info = path.stat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    return expected_type(info.st_mode) and info.st_uid == uid and not info.st_mode & 0o022


def _mise_executable(path: Path, runtime: Path, uid: int) -> bool:
    # npm's bin/codex is normally a symlink into this runtime's lib/node_modules.
    # Accept that layout, but never a target or writable parent outside it.
    if path.lstat().st_uid != uid:
        return False
    target = path.resolve(strict=True)
    if not target.is_relative_to(runtime) or not _mise_owned(target, uid) or not os.access(target, os.X_OK):
        return False
    parent = target.parent
    while parent != runtime:
        if not _mise_owned(parent, uid, directory=True):
            return False
        parent = parent.parent
    return True


def mise_node_bins(home: Path) -> list[Path]:
    """Discover one unambiguous, user-owned Codex/Node runtime without a shell."""
    try:
        uid = os.getuid()
        root = home.resolve(strict=True)
        if not _mise_owned(root, uid, directory=True):
            return []
        # Only version aliases below this fixed root may be symlinks. A redirected
        # .local/mise root must not expand the discovery boundary to another tree.
        for component in (".local", "share", "mise", "installs", "node"):
            root = root / component
            if root.resolve(strict=True) != root or not _mise_owned(root, uid, directory=True):
                return []
        candidates = set()
        for alias in root.iterdir():
            try:
                runtime = alias.resolve(strict=True)
                if alias.lstat().st_uid != uid or runtime.parent != root or not _mise_owned(runtime, uid, directory=True):
                    continue
                binary = runtime / "bin"
                if any(":" in part for part in binary.parts[1:]) or binary.resolve(strict=True) != binary or not _mise_owned(binary, uid, directory=True):
                    continue
                if all(_mise_executable(binary / name, runtime, uid) for name in ("node", "codex")):
                    candidates.add(binary)
            except (OSError, RuntimeError, ValueError):
                continue
        # Multiple aliases are common; multiple independent runtimes need an
        # explicit choice. Do not infer that 'latest' is the user's selection.
        return list(candidates) if len(candidates) == 1 else []
    except (AttributeError, OSError, RuntimeError, ValueError):
        return []


def captured_environment(home: Path, source: dict[str, str], previous: dict[str, str] | None = None) -> dict[str, str]:
    # An SSH/non-login shell might omit settings; preserve the previous private capture.
    result = {key: value for key, value in (previous or {}).items() if key in CAPTURE_KEYS}
    for key in CAPTURE_KEYS:
        if key in source:
            value = source[key]
            if "\x00" in value or len(value) > 32768:
                raise ValueError(f"Invalid {key} setting")
            if value and key in ("CODEX_HOME", "CLAUDE_CONFIG_DIR") and not value.startswith("/"):
                value = str(Path(value).expanduser().absolute())
            result[key] = value
    result["PATH"] = ":".join(map(str, [home / ".local/bin", home / ".cargo/bin", home / ".npm-global/bin",
        Path("/opt/homebrew/bin"), Path("/usr/local/bin"), Path("/usr/bin"), Path("/bin"), Path("/usr/sbin"), Path("/sbin"),
        *mise_node_bins(home)]))
    return result


def launch_agent(home: Path, runtime: Path) -> dict:
    locations = paths(home)
    return {"Label": LABEL, "MonitorManaged": OWNER,
            "ProgramArguments": [str(runtime / "venv/bin/python"), "-I", str(runtime / "scripts/collector_runner.py"),
                                 "run", "--state-dir", str(locations["state"])],
            "WorkingDirectory": str(runtime), "RunAtLoad": True,
            "KeepAlive": {"SuccessfulExit": False}, "ThrottleInterval": 30,
            "ProcessType": "Background", "Umask": 0o077,
            "StandardOutPath": "/dev/null", "StandardErrorPath": "/dev/null",
            "EnvironmentVariables": {"HOME": str(home), "PYTHONUNBUFFERED": "1", "PYTHONDONTWRITEBYTECODE": "1"}}


def owned_path(path: Path) -> None:
    if path.is_symlink():
        raise ValueError(f"Refusing symbolic link: {path.name}")
    if path.exists() and path.stat().st_uid != os.getuid():
        raise ValueError(f"Path is not owned by this user: {path.name}")


def private_dir(path: Path) -> None:
    owned_path(path)
    if not path.exists():
        path.mkdir(mode=0o700, parents=True)
    if not path.is_dir():
        raise ValueError("Expected a private directory")
    path.chmod(0o700)


def write_private(path: Path, data: bytes) -> None:
    owned_path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".monitor-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def json_bytes(value: dict) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def require_install(locations: dict[str, Path]) -> None:
    for key in ("base", "state", "releases", "owner"):
        owned_path(locations[key])
    if json.loads(locations["owner"].read_text(encoding="utf-8")).get("owner") != OWNER:
        raise ValueError("This collector directory belongs to another installation")


def current_runtime(locations: dict[str, Path]) -> Path:
    require_install(locations)
    owned_path(locations["current"])
    release = json.loads(locations["current"].read_text(encoding="utf-8")).get("release", "")
    if not isinstance(release, str) or len(release) != 32 or any(c not in "0123456789abcdef" for c in release):
        raise ValueError("Invalid installed release")
    runtime = locations["releases"] / release
    owned_path(runtime)
    if runtime.resolve().parent != locations["releases"].resolve():
        raise ValueError("Invalid installed runtime")
    if json.loads((runtime / "managed-release.json").read_text()).get("owner") != OWNER:
        raise ValueError("Unmanaged runtime")
    return runtime


def service_result(*arguments: str) -> subprocess.CompletedProcess:
    return subprocess.run(["/bin/launchctl", *arguments], capture_output=True, text=True, check=False)


def service_loaded() -> bool:
    return service_result("print", f"gui/{os.getuid()}/{LABEL}").returncode == 0


def collector_locked(locations: dict[str, Path]) -> bool:
    import fcntl
    lock = locations["state"] / "collector.lock"
    owned_path(lock)
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        return False
    finally:
        os.close(descriptor)


def validate_plist(path: Path) -> None:
    owned_path(path)
    content = plistlib.loads(path.read_bytes())
    if content.get("Label") != LABEL or content.get("MonitorManaged") != OWNER:
        raise ValueError("Refusing to modify an unmanaged LaunchAgent")


def stop(locations: dict[str, Path]) -> None:
    if locations["plist"].exists():
        validate_plist(locations["plist"])
    elif service_loaded():
        raise RuntimeError("Loaded collector has no owned LaunchAgent file")
    if service_loaded() and locations["plist"].exists():
        result = service_result("bootout", f"gui/{os.getuid()}", str(locations["plist"]))
        if result.returncode != 0:
            raise RuntimeError("Could not stop this user's collector")
    deadline = time.monotonic() + 15
    while collector_locked(locations):
        if time.monotonic() >= deadline:
            raise RuntimeError("Previous collector is still stopping; retry later")
        time.sleep(.2)


def start(locations: dict[str, Path]) -> None:
    current_runtime(locations)
    validate_plist(locations["plist"])
    if not (locations["state"] / "config.json").is_file():
        raise ValueError("尚未配对，请先运行 pair")
    if not service_loaded():
        result = service_result("bootstrap", f"gui/{os.getuid()}", str(locations["plist"]))
        if result.returncode != 0:
            raise RuntimeError("Could not load LaunchAgent; log in to the Mac desktop and retry")
    result = service_result("kickstart", f"gui/{os.getuid()}/{LABEL}")
    if result.returncode != 0:
        raise RuntimeError("Could not start the collector")
    deadline = time.monotonic() + 10
    stable = 0
    while time.monotonic() < deadline:
        result = service_result("print", f"gui/{os.getuid()}/{LABEL}")
        running = result.returncode == 0 and any(line.strip() == "state = running" for line in result.stdout.splitlines())
        stable = stable + 1 if running and collector_locked(locations) else 0
        if stable >= 3:
            return
        time.sleep(.2)
    raise RuntimeError("Collector did not become ready; check its private log")


def install(home: Path, project: Path) -> None:
    locations = paths(home)
    project = project.resolve()
    source = project / "agent_monitor"
    if not (source / "remote_agent.py").is_file():
        raise ValueError("Source directory does not contain the Monitor connector")
    private_dir(locations["base"].parent)
    if locations["base"].exists():
        if not locations["owner"].exists():
            raise ValueError("Refusing to overwrite an unowned collector directory")
        require_install(locations)
    private_dir(locations["base"])
    if not locations["owner"].exists():
        write_private(locations["owner"], json_bytes({"owner": OWNER}))
    for key in ("state", "logs", "releases", "bin", "hooks"):
        private_dir(locations[key])
    # Missing policy on an old installation remains status-only. Never infer or
    # reset local consent from the server's output preference during an upgrade.
    policy.read_mode(locations["mode"])
    if locations["plist"].exists():
        validate_plist(locations["plist"])
    was_loaded = service_loaded()
    previous = {}
    if locations["environment"].exists():
        owned_path(locations["environment"])
        previous = json.loads(locations["environment"].read_text(encoding="utf-8"))
    environment = captured_environment(home, dict(os.environ), previous)
    release = uuid.uuid4().hex
    runtime = locations["releases"] / release
    private_dir(runtime)
    write_private(runtime / "managed-release.json", json_bytes({"owner": OWNER, "created": datetime.now(timezone.utc).isoformat()}))
    (runtime / "agent_monitor").mkdir(mode=0o700)
    for path in source.glob("*.py"):
        if path.is_symlink():
            raise ValueError("Connector sources must be regular files")
        shutil.copyfile(path, runtime / "agent_monitor" / path.name)
    (runtime / "scripts").mkdir(mode=0o700)
    shutil.copyfile(Path(__file__).with_name("collector_runner.py"), runtime / "scripts/collector_runner.py")
    shutil.copyfile(Path(__file__), runtime / "scripts/collector_service.py")
    shutil.copyfile(Path(__file__).with_name("collector_policy.py"), runtime / "scripts/collector_policy.py")
    requirements = Path(__file__).with_name("requirements-connector.txt")
    shutil.copyfile(requirements, runtime / "requirements-connector.txt")
    subprocess.run([sys.executable, "-m", "venv", str(runtime / "venv")], check=True)
    python = runtime / "venv/bin/python"
    # Package-manager error messages can include private proxy/index settings.
    subprocess.run([str(python), "-m", "pip", "install", "--disable-pip-version-check", "--only-binary=:all:",
                    "-r", str(runtime / "requirements-connector.txt")], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([str(python), "-I", "-c", "import fastapi, qrcode, PIL"], check=True)
    stop(locations)
    write_private(locations["environment"], json_bytes(environment))
    write_private(locations["current"], json_bytes({"release": release}))
    locations["plist"].parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    owned_path(locations["plist"].parent)
    write_private(locations["plist"], plistlib.dumps(launch_agent(home, runtime), sort_keys=False))
    controller = locations["bin"] / "monitor-collector"
    script = "#!/bin/sh\nexec " + shlex.quote(str(python)) + " -I " + shlex.quote(str(runtime / "scripts/collector_service.py")) + ' "$@"\n'
    write_private(controller, script.encode("utf-8"))
    controller.chmod(0o700)
    if (locations["state"] / "config.json").is_file() and was_loaded:
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
        was_loaded = service_loaded()
        # stop() waits for the runner lock. Its SIGTERM cleanup first cancels its
        # own reply children, so no old worker survives a stricter local policy.
        stop(locations)
        try:
            policy.write_mode(locations["mode"], mode)
            if was_loaded:
                start(locations)
        except Exception:
            # Preserve paired credentials and restore the prior policy. Do not
            # start a service that the user had already paused.
            try:
                stop(locations)
                policy.write_mode(locations["mode"], previous)
                if was_loaded:
                    start(locations)
            except Exception:
                raise RuntimeError("Mode change failed; recovery requires inspection") from None
            raise RuntimeError("Mode change failed; previous policy restored") from None


def status(locations: dict[str, Path], server: str | None = None) -> None:
    installed = locations["owner"].is_file()
    result = service_result("print", f"gui/{os.getuid()}/{LABEL}")
    state = "stopped"
    if result.returncode == 0:
        state = "loaded"
        for line in result.stdout.splitlines():
            if line.strip() == "state = running":
                state = "running"
                break
    mode = policy.read_mode(locations["mode"]) if installed else policy.DEFAULT_MODE
    paired = (locations["state"] / "config.json").is_file()
    if paired and server is not None:
        paired = paired_server_matches(locations, server)
    print(json.dumps({"installed": installed, "paired": paired, "server_scope_version": 1,
                      "service": state, "mode": mode, "mode_schema": policy.SCHEMA,
                      "supported_modes": list(policy.MODES)}, ensure_ascii=False))


def main() -> int:
    parser = argparse.ArgumentParser(description="Monitor macOS 电脑连接器（当前用户、本机同步范围）")
    parser.add_argument("command", choices=("install", "pair", "start", "stop", "status", "mode", "uninstall"))
    parser.add_argument("--set", dest="set_mode", choices=policy.MODES)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--server", default=os.environ.get("MONITOR_SERVER_URL"))
    parser.add_argument("--name", default="Mac mini")
    parser.add_argument("--code-stdin", action="store_true", help="从标准输入读取一次性配对码；默认显示扫码配对")
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
    if sys.platform != "darwin" or sys.version_info < (3, 12):
        parser.error("需要 macOS 和 Python 3.12 或更新版本")
    if os.geteuid() == 0:
        parser.error("请用当前用户运行，不要使用 sudo")
    os.umask(0o077)
    home = Path.home()
    locations = paths(home)
    try:
        if args.command == "install":
            install(home, args.source)
        elif args.command == "status":
            status(locations, args.server)
        elif args.command == "mode":
            require_paired_server(locations, args.server)
            configure_mode(locations, args.set_mode)
            status(locations, args.server)
        else:
            runtime = current_runtime(locations)
            if args.command == "pair":
                stop(locations)
                command = [str(runtime / "venv/bin/python"), "-I", str(runtime / "scripts/collector_runner.py"),
                           "pair", "--state-dir", str(locations["state"]), "--server", args.server, "--name", args.name]
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
                stop(locations)
                print("连接器已停止。")
            elif args.command == "uninstall":
                stop(locations)
                if locations["plist"].exists():
                    validate_plist(locations["plist"])
                    locations["plist"].unlink()
                print("后台服务已移除；本机配对、运行文件和日志均保留。")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        # Do not echo command arguments or subprocess/environment values.
        print(f"操作未完成（{type(error).__name__}）。请检查当前用户权限、Python 环境及桌面登录状态。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
