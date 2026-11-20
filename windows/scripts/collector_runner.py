#!/usr/bin/env python3
"""Private Windows background collector entry point. Never print credentials."""
from __future__ import annotations

import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import sys

ENV_KEYS = frozenset({
    "PATH", "CODEX_HOME", "CLAUDE_CONFIG_DIR",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
})

_policy_spec = importlib.util.spec_from_file_location("monitor_win_collector_policy", Path(__file__).with_name("collector_policy.py"))
policy = importlib.util.module_from_spec(_policy_spec)
_policy_spec.loader.exec_module(policy)


class LogStream:
    def __init__(self, logger: logging.Logger):
        self.logger = logger
        self.pending = ""

    def write(self, value: str) -> int:
        self.pending += value
        while "\n" in self.pending:
            line, self.pending = self.pending.split("\n", 1)
            if line.strip():
                self.logger.info("%s", line[:8192])
        if len(self.pending) > 8192:
            self.logger.info("%s", self.pending[:8192])
            self.pending = ""
        return len(value)

    def flush(self) -> None:
        if self.pending.strip():
            self.logger.info("%s", self.pending[:8192])
        self.pending = ""


def load_environment(state: Path) -> None:
    path = state.parent / "environment.json"
    if not path.is_file() or path.is_symlink():
        return
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, dict) or set(values) - ENV_KEYS:
        raise ValueError("Invalid environment keys")
    for key, value in values.items():
        if not isinstance(value, str) or "\x00" in value or len(value) > 32768:
            raise ValueError("Invalid environment value")
        if key in ("CODEX_HOME", "CLAUDE_CONFIG_DIR") and not value:
            os.environ.pop(key, None)
            continue
        os.environ[key] = value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("run", "pair"))
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--server", default=os.environ.get("MONITOR_SERVER_URL") or "https://monitor.example.com")
    parser.add_argument("--name", default=os.environ.get("COMPUTERNAME") or "Windows PC")
    parser.add_argument("--code-stdin", action="store_true")
    args = parser.parse_args()
    if sys.platform != "win32":
        parser.error("This service entry point is for Windows")
    state = args.state_dir
    if not state.is_absolute() or state.is_symlink() or not state.is_dir():
        parser.error("Invalid state directory")
    runtime = Path(__file__).resolve().parent.parent
    sys.path.insert(0, str(runtime))
    try:
        load_environment(state)
        os.environ["AGENT_MONITOR_STATE_DIR"] = str(state.parent / "hook-state")
        from agent_monitor import remote_agent
    except Exception:
        print("连接器配置或运行环境不可用，请重新运行安装器。", file=sys.stderr)
        return 1

    if args.command == "pair":
        try:
            if args.code_stdin:
                code = sys.stdin.readline(129).strip()
                if not code or len(code) > 128:
                    raise remote_agent.RemoteAgentError("配对码无效")
                remote_agent.pair(args.server, code, args.name, state)
            else:
                remote_agent.pair_qr(args.server, args.name, state)
            print("电脑配对成功，凭据已保存在本机。")
            return 0
        except remote_agent.RemoteAgentError as error:
            print(str(error), file=sys.stderr)
            return 1

    import msvcrt

    lock_path = state / "collector.lock"
    pid_path = state / "collector.pid"
    with open(lock_path, "a+b") as lock:
        lock.seek(0)
        if lock.tell() == 0:
            lock.write(b"1")
            lock.flush()
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            return 0
        try:
            pid_path.write_text(str(os.getpid()), encoding="utf-8")
            log_dir = state.parent / "logs"
            log_dir.mkdir(parents=True, exist_ok=True)
            logger = logging.getLogger("monitor.collector")
            logger.setLevel(logging.INFO)
            handler = RotatingFileHandler(
                log_dir / "collector.log", maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8"
            )
            handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
            logger.addHandler(handler)
            stream = LogStream(logger)
            try:
                with redirect_stdout(stream), redirect_stderr(stream):
                    if not (state / "config.json").is_file():
                        logger.info("尚未配对，请运行配对命令。")
                        return 0
                    mode = policy.read_mode(state.parent / "mode.json")
                    logger.info("连接器已启动，本机模式：%s。", mode)
                    remote_agent.run(state, **policy.run_options(mode))
                    logger.info("连接器已停止。")
                    return 0
            except remote_agent.UnauthorizedError:
                logger.info("连接授权已失效；请重新配对。")
                return 0
            except Exception as error:
                logger.error("连接器异常退出：%s", type(error).__name__)
                return 1
            finally:
                stream.flush()
                handler.close()
                logger.removeHandler(handler)
        finally:
            pid_path.unlink(missing_ok=True)
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            except OSError:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
