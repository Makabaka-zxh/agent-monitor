"""Computer connector for Agent Monitor, with a local status-only mode.

The saved bearer token authenticates this connector to the monitor hub only.
Codex and Claude Code credentials are never read, copied, or transmitted here.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import ssl
import sys
import tempfile
import threading
import time
from typing import Any, Callable
import urllib.error
import urllib.parse
import urllib.request


REQUEST_TIMEOUT = 10
HEARTBEAT_INTERVAL = 5
MAX_RETRY_DELAY = 30
MAX_RESPONSE_BYTES = 1024 * 1024
CONFIG_FILENAME = "config.json"
QR_PAIRING_SECONDS = 600
QR_ID_PATTERN = r"[A-Za-z0-9_-]{32}"


class RemoteAgentError(Exception):
    """A safe, user-facing connector error with no credentials in its message."""


class UnauthorizedError(RemoteAgentError):
    """The hub has revoked, or no longer recognizes, this connector."""


class PairingRequestError(RemoteAgentError):
    """A terminal QR pairing failure that must not be retried automatically."""


class _ConnectorTerminated(BaseException):
    """Leave the polling loop through its normal worker-cleanup path."""


@contextmanager
def _termination_handler():
    # launchd sends SIGTERM when stopping a user agent. Raising only on the
    # first signal lets the finally block finish cancelling owned reply children.
    # Embedders running the connector on another thread retain their handlers.
    if threading.current_thread() is not threading.main_thread():
        yield
        return
    previous = signal.getsignal(signal.SIGTERM)
    stopping = False

    def terminate(_signum, _frame):
        nonlocal stopping
        if not stopping:
            stopping = True
            raise _ConnectorTerminated()

    signal.signal(signal.SIGTERM, terminate)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


def platform_name() -> str:
    import platform

    name = platform.system()
    return "macOS" if name == "Darwin" else name


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RemoteAgentError("服务器返回了重定向，连接已拒绝；请使用服务的最终地址")


def normalize_server(server: str, allow_insecure_lan: bool = False) -> str:
    """Require HTTPS, except loopback or explicitly opted-in private IPs."""
    if not isinstance(server, str) or not server or re.search(r"[\s\x00-\x1f\x7f]", server):
        raise RemoteAgentError("服务器地址无效")
    try:
        parsed = urllib.parse.urlsplit(server)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise RemoteAgentError("服务器地址无效") from exc
    if (
        parsed.scheme not in {"http", "https"}
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise RemoteAgentError("请填写不带账号、路径、查询参数的 HTTP(S) 服务地址")
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        address = None
    is_loopback = hostname.lower() == "localhost" or bool(address and address.is_loopback)
    is_private_ip = bool(
        address
        and any(
            address in network
            for network in (
                ipaddress.ip_network("10.0.0.0/8"),
                ipaddress.ip_network("172.16.0.0/12"),
                ipaddress.ip_network("192.168.0.0/16"),
                ipaddress.ip_network("fc00::/7"),
            )
            if address.version == network.version
        )
    )
    if parsed.scheme == "http" and not (
        is_loopback or (allow_insecure_lan and is_private_ip)
    ):
        raise RemoteAgentError(
            "远程连接需要 HTTPS；本地开发可用 localhost HTTP。"
            "局域网测试须明确提供私网 IP 并加 --allow-insecure-lan"
        )
    netloc = f"[{hostname}]" if ":" in hostname else hostname
    if port is not None:
        netloc += f":{port}"
    return urllib.parse.urlunsplit((parsed.scheme, netloc, "", "", ""))


@dataclass
class AgentConfig:
    server: str
    device_id: str
    token: str = field(repr=False)
    name: str = ""
    sync_output: bool = False
    allow_insecure_lan: bool = False


def default_state_dir() -> Path:
    return Path.cwd() / ".state" / "agent"


def _safe_token(value: Any) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[\x21-\x7e]{1,8192}", value):
        raise RemoteAgentError("服务返回的连接凭据无效，请重新配对")
    return value


def _nonempty_text(value: Any, label: str, max_length: int = 256) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > max_length
        or re.search(r"[\x00-\x1f\x7f]", value)
    ):
        raise RemoteAgentError(f"{label}无效")
    return value.strip()


def _output_setting(data: dict[str, Any], default: bool = False) -> bool:
    value = data.get("sync_output", default)
    if not isinstance(value, bool):
        raise RemoteAgentError("服务返回的输出同步设置无效")
    return value


def save_config(config: AgentConfig, state_dir: Path) -> None:
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload = {
        "version": 1,
        "server": config.server,
        "device_id": config.device_id,
        "token": config.token,
        "name": config.name,
        "sync_output": config.sync_output,
        "allow_insecure_lan": config.allow_insecure_lan,
    }
    temporary: Path | None = None
    try:
        descriptor, filename = tempfile.mkstemp(prefix=".config-", suffix=".tmp", dir=state_dir)
        temporary = Path(filename)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            os.chmod(temporary, 0o600)
            json.dump(payload, output, ensure_ascii=False, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, state_dir / CONFIG_FILENAME)
        temporary = None
        os.chmod(state_dir / CONFIG_FILENAME, 0o600)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def load_config(state_dir: Path) -> AgentConfig:
    try:
        raw = (Path(state_dir) / CONFIG_FILENAME).read_text(encoding="utf-8")
        data = json.loads(raw)
    except FileNotFoundError as exc:
        raise RemoteAgentError("尚未配对，请先运行 pair") from exc
    except (OSError, ValueError) as exc:
        raise RemoteAgentError("无法读取连接设置，请检查状态目录或重新配对") from exc
    if not isinstance(data, dict) or data.get("version") != 1:
        raise RemoteAgentError("连接设置格式无效，请重新配对")
    allow_insecure = data.get("allow_insecure_lan", False)
    if not isinstance(allow_insecure, bool):
        raise RemoteAgentError("连接设置格式无效，请重新配对")
    return AgentConfig(
        server=normalize_server(data.get("server"), allow_insecure),
        device_id=_nonempty_text(data.get("device_id"), "电脑编号"),
        token=_safe_token(data.get("token")),
        name=data.get("name", "") if isinstance(data.get("name", ""), str) else "",
        sync_output=_output_setting(data),
        allow_insecure_lan=allow_insecure,
    )


class HubClient:
    def __init__(self, server: str, allow_insecure_lan: bool = False):
        self.server = normalize_server(server, allow_insecure_lan)
        self.opener = urllib.request.build_opener(
            NoRedirectHandler(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )

    def post(self, endpoint: str, payload: dict[str, Any], token: str | None = None) -> dict[str, Any]:
        qr_endpoint = endpoint == "/api/agent/pairing/start" or bool(
            re.fullmatch(rf"/api/agent/pairing/{QR_ID_PATTERN}/poll", endpoint)
        )
        if endpoint not in {"/api/agent/register", "/api/agent/heartbeat", "/api/agent/usage", "/api/agent/results", "/api/agent/results/file", "/api/agent/results/status",
                            "/api/agent/replies/poll", "/api/agent/replies/update"} and not qr_endpoint:
            raise RemoteAgentError("不支持的服务接口")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + _safe_token(token)
        try:
            body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise RemoteAgentError("采集结果格式无效，本次未上传") from exc
        request = urllib.request.Request(self.server + endpoint, body, headers, method="POST")
        try:
            with self.opener.open(request, timeout=REQUEST_TIMEOUT) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as exc:
            exc.close()
            if qr_endpoint and exc.code in {400, 401, 403, 404, 409, 410, 422}:
                message = {
                    401: "配对验证失败，请重新运行 pair-qr 生成二维码",
                    403: "配对未获允许；手机可能已拒绝，请重新运行 pair-qr",
                    404: "配对请求不存在或服务尚不支持扫码连接，请检查服务版本",
                    410: "二维码已过期或凭据已领取，请重新运行 pair-qr",
                }.get(exc.code, "配对请求无效，请检查电脑名称与服务设置后重新运行 pair-qr")
                raise PairingRequestError(message) from exc
            if exc.code == 401:
                raise UnauthorizedError("连接凭据已失效，请在工作台生成新配对码并重新运行 pair") from exc
            raise RemoteAgentError(f"服务器暂时无法接收同步（HTTP {exc.code}）") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise RemoteAgentError("无法连接服务器，请检查地址、网络和证书") from exc
        if len(raw) > MAX_RESPONSE_BYTES:
            raise RemoteAgentError("服务器响应过大，连接已拒绝")
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeError) as exc:
            raise RemoteAgentError("服务器返回了无法识别的数据") from exc
        if not isinstance(result, dict):
            raise RemoteAgentError("服务器响应格式无效")
        return result


def pair(
    server: str,
    code: str,
    name: str,
    state_dir: Path,
    allow_insecure_lan: bool = False,
) -> AgentConfig:
    client = HubClient(server, allow_insecure_lan)
    name = _nonempty_text(name, "电脑名称", 120)
    code = _nonempty_text(code, "配对码", 200)
    result = client.post(
        "/api/agent/register", {"code": code, "name": name, "platform": platform_name()}
    )
    config = AgentConfig(
        server=client.server,
        device_id=_nonempty_text(result.get("device_id"), "电脑编号"),
        token=_safe_token(result.get("token")),
        name=name,
        sync_output=_output_setting(result),
        allow_insecure_lan=allow_insecure_lan,
    )
    save_config(config, state_dir)
    return config


def _write_pairing_qr(url: str, request_id: str, state_dir: Path) -> Path:
    try:
        import qrcode
        from qrcode.image.pil import PilImage
    except ImportError as exc:
        raise RemoteAgentError("扫码连接需要二维码组件，请先安装项目 requirements.txt 中的依赖") from exc
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
    qr.add_data(url)
    qr.make(fit=True)
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state_dir / f"pairing-{request_id}.png"
    with path.open("wb") as output:
        os.chmod(path, 0o600)
        qr.make_image(image_factory=PilImage, fill_color="black", back_color="white").save(output)
    print(f"二维码已保存：{path.resolve()}", flush=True)
    if sys.stdout.isatty():
        try:
            qr.print_ascii(out=sys.stdout, invert=True)
        except UnicodeError:
            print("请打开上面的 PNG 图片扫码。", flush=True)
    return path


def pair_qr(
    server: str,
    name: str,
    state_dir: Path,
    *,
    sleep: Callable[[float], None] | None = None,
    monotonic: Callable[[], float] | None = None,
) -> AgentConfig:
    """Wait for explicit phone approval; the retrieval secret never leaves memory."""
    server = normalize_server(server)
    if urllib.parse.urlsplit(server).scheme != "https":
        raise RemoteAgentError("扫码连接仅支持 HTTPS，请使用手机可访问的工作台正式地址")
    name = _nonempty_text(name, "电脑名称", 60)
    # Check dependencies before creating a single-use request on the hub.
    try:
        import qrcode  # noqa: F401
        from qrcode.image.pil import PilImage  # noqa: F401
    except ImportError as exc:
        raise RemoteAgentError("扫码连接需要二维码组件，请先安装项目 requirements.txt 中的依赖") from exc
    client = HubClient(server)
    sleep, monotonic = sleep or time.sleep, monotonic or time.monotonic
    deadline = monotonic() + QR_PAIRING_SECONDS
    result = client.post("/api/agent/pairing/start", {"name": name, "platform": platform_name()})
    request_id = result.get("request_id")
    secret = result.get("poll_secret")
    if not isinstance(request_id, str) or not re.fullmatch(QR_ID_PATTERN, request_id):
        raise RemoteAgentError("服务返回的配对编号无效，请重新生成二维码")
    if not isinstance(secret, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", secret):
        raise RemoteAgentError("服务返回的配对验证信息无效，请重新生成二维码")
    expected_url = server + "/#/pairing-confirm/" + request_id
    if result.get("verification_url") != expected_url:
        raise RemoteAgentError("扫码确认地址与连接的工作台不一致，请使用工作台正式 HTTPS 地址")
    interval = result.get("interval", 3)
    if type(interval) is not int or not 3 <= interval <= 30:
        raise RemoteAgentError("服务返回的配对等待设置无效")
    _write_pairing_qr(expected_url, request_id, Path(state_dir))
    print("用手机扫描二维码，核对电脑名称后点“连接”。二维码 10 分钟内有效。", flush=True)
    print(f"也可在手机打开：{expected_url}", flush=True)
    print("等待手机确认；按 Ctrl+C 取消等待。", flush=True)
    retry_delay = interval
    while True:
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise PairingRequestError("二维码已过期，请重新运行 pair-qr")
        sleep(min(retry_delay, remaining))
        if monotonic() >= deadline:
            raise PairingRequestError("二维码已过期，请重新运行 pair-qr")
        try:
            result = client.post(f"/api/agent/pairing/{request_id}/poll", {"poll_secret": secret})
        except PairingRequestError:
            raise
        except RemoteAgentError:
            # No raw exception/body output: a response could contain credentials.
            retry_delay = min(MAX_RETRY_DELAY, max(HEARTBEAT_INTERVAL, retry_delay * 2))
            print(f"暂时无法确认连接，{retry_delay} 秒后重试。", file=sys.stderr, flush=True)
            continue
        status = result.get("status")
        if status == "pending":
            retry_delay = interval
            continue
        if status != "approved":
            raise PairingRequestError("服务返回的配对状态无效，请重新运行 pair-qr")
        config = AgentConfig(
            server=server,
            device_id=_nonempty_text(result.get("device_id"), "电脑编号"),
            token=_safe_token(result.get("token")),
            name=name,
            sync_output=_output_setting(result),
        )
        save_config(config, state_dir)
        return config


def _collect_snapshot(*, include_output: bool) -> dict[str, Any]:
    from .collector import collect_snapshot

    return collect_snapshot(include_output=include_output)


def heartbeat(client: HubClient, config: AgentConfig, snapshot: dict[str, Any]) -> bool:
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("tasks"), list):
        raise RemoteAgentError("采集结果格式无效，本次未上传")
    sources = snapshot.get("sources", {})
    if not isinstance(sources, (list, dict)):
        raise RemoteAgentError("采集结果格式无效，本次未上传")
    result = client.post(
        "/api/agent/heartbeat", {"tasks": snapshot["tasks"], "sources": sources}, token=config.token
    )
    return _output_setting(result, config.sync_output)


class ResultSync:
    """Incremental, bounded transfer of this computer's final deliverables.

    The hub may request offsets only for files already discovered from a final
    answer. It can never request a local path. At most four chunks transfer per
    heartbeat, so a large attachment does not stop status updates for minutes.
    """
    def __init__(self, local=None):
        from .task_results import LocalResults
        self.local = local or LocalResults()
        self.done = {}
        self.cursor = 0

    def sync(self, client, config, snapshot):
        from .task_results import CHUNK_BYTES, HEX_ID, MAX_FILES
        import base64
        if not config.sync_output:
            self.done.clear()
            return
        tasks = [task for task in snapshot.get("tasks", []) if isinstance(task, dict)
                 and isinstance(task.get("id"), str) and isinstance(task.get("final_result_id"), str)
                 and HEX_ID.fullmatch(task["final_result_id"])]
        if not tasks:
            return
        status = client.post("/api/agent/results/status", {"results": [
            {"source_id": task["id"], "result_id": task["final_result_id"]} for task in tasks[:100]]}, token=config.token)
        needed = status.get("needed_results")
        task_ids = {task["id"] for task in tasks}
        if (not isinstance(needed, list) or len(needed) > 100
                or any(not isinstance(value, str) or value not in task_ids for value in needed)):
            raise RemoteAgentError("服务请求了未授权结果，已拒绝")
        eligible = [task for task in tasks if task["id"] in needed]
        if not eligible:
            return
        self.cursor %= len(eligible)
        selected = eligible[self.cursor:self.cursor + 2]
        self.cursor = (self.cursor + len(selected)) % len(eligible)
        chunk_budget = 4
        for task in selected:
            final = self.local.read(task["id"])
            if not final or final["result_id"] != task["final_result_id"]:
                continue
            files = [{key: file[key] for key in ("id", "name", "size", "mime", "sha256")} for file in final["files"]]
            response = client.post("/api/agent/results", {
                "source_id": task["id"], "tool": task["tool"],
                **{key: final[key] for key in ("result_id", "text", "completed_at", "truncated")}, "files": files,
            }, token=config.token)
            requested = response.get("needed_files")
            if not isinstance(requested, list) or len(requested) > MAX_FILES:
                raise RemoteAgentError("服务返回的文件同步状态无效")
            known = {file["id"]: file for file in files}
            complete = True
            for wanted in requested:
                if not isinstance(wanted, dict) or wanted.get("id") not in known:
                    raise RemoteAgentError("服务请求了未授权文件，已拒绝")
                file = known[wanted["id"]]
                offset = wanted.get("offset")
                if isinstance(offset, bool) or not isinstance(offset, int) or not 0 <= offset <= file["size"]:
                    raise RemoteAgentError("服务返回的文件分段无效")
                if not chunk_budget:
                    complete = False
                    break
                # Revalidate and hash the opened file immediately before transfer.
                try:
                    content, _ = self.local.file(task["id"], final["result_id"], file["id"])
                except Exception:
                    raise RemoteAgentError("配套文件已变化，暂不上传") from None
                first = True
                while chunk_budget and (offset < len(content) or first and not content):
                    first = False
                    data = content[offset:offset + CHUNK_BYTES]
                    answer = client.post("/api/agent/results/file", {
                        "source_id": task["id"], "result_id": final["result_id"], "file_id": file["id"],
                        "offset": offset, "data": base64.b64encode(data).decode("ascii"),
                    }, token=config.token)
                    next_offset = answer.get("offset")
                    if isinstance(next_offset, bool) or next_offset != offset + len(data):
                        raise RemoteAgentError("服务返回的文件分段无效")
                    offset = next_offset
                    chunk_budget -= 1
                    if offset == len(content) and answer.get("ready") is not True:
                        raise RemoteAgentError("文件尚未通过校验")
                if offset < len(content):
                    complete = False
            if complete:
                self.done[task["id"]] = task["final_result_id"]
        current_ids = {task["id"] for task in tasks}
        self.done = {key: value for key, value in self.done.items() if key in current_ids}


def run(
    state_dir: Path,
    *,
    once: bool = False,
    status_only: bool = False,
    allow_replies: bool = True,
    collect: Callable[..., dict[str, Any]] | None = None,
    sleep: Callable[[float], None] | None = None,
) -> None:
    with _termination_handler():
        try:
            _run(state_dir, once=once, status_only=status_only, allow_replies=allow_replies,
                 collect=collect, sleep=sleep)
        except _ConnectorTerminated:
            return


def _run(
    state_dir: Path,
    *,
    once: bool,
    status_only: bool,
    allow_replies: bool = True,
    collect: Callable[..., dict[str, Any]] | None,
    sleep: Callable[[float], None] | None,
) -> None:
    config = load_config(state_dir)
    client = HubClient(config.server, config.allow_insecure_lan)
    production_collector = collect is None
    collect = collect or _collect_snapshot
    sleep = sleep or time.sleep
    result_sync = None if status_only else ResultSync()
    retry_delay = HEARTBEAT_INTERVAL
    replies = None
    usage = None
    try:
        if production_collector and not once:
            from .usage_remote import RemoteUsageWorker
            usage = RemoteUsageWorker(HubClient(config.server, config.allow_insecure_lan), config, state_dir)
        # Reply execution is an independent local permission. Status-only remains
        # the ceiling even if replies were enabled by the embedding application.
        if production_collector and not once and not status_only and allow_replies:
            from .remote_replies import RemoteReplyWorker
            replies = RemoteReplyWorker(HubClient(config.server, config.allow_insecure_lan), config, state_dir)
        while True:
            try:
                try:
                    snapshot = collect(include_output=config.sync_output and not status_only)
                    if status_only:
                        # Enforce this local ceiling at the upload boundary too;
                        # a collector change must not accidentally include output.
                        fields = {"id", "tool", "title", "status", "updated_at", "project", "status_source"}
                        if not isinstance(snapshot, dict) or not isinstance(snapshot.get("tasks"), list):
                            raise RemoteAgentError("采集结果格式无效，本次未上传")
                        if not all(isinstance(task, dict) for task in snapshot["tasks"]):
                            raise RemoteAgentError("采集结果格式无效，本次未上传")
                        snapshot = {**snapshot, "tasks": [
                            {key: value for key, value in task.items() if key in fields}
                            for task in snapshot["tasks"]
                        ]}
                except (OSError, ValueError, TypeError) as exc:
                    raise RemoteAgentError("暂时无法读取本机任务状态") from exc
                sync_output = heartbeat(client, config, snapshot)
                if usage is not None:
                    usage.start()
                if replies is not None:
                    replies.start()
                if not status_only and sync_output != config.sync_output:
                    config.sync_output = sync_output
                    save_config(config, state_dir)
                try:
                    if result_sync is not None:
                        result_sync.sync(client, config, snapshot)
                except UnauthorizedError:
                    raise
                except RemoteAgentError as exc:
                    # Final files are supplementary; a failed transfer must never
                    # prevent heartbeat recovery or hide current task state.
                    print(f"最终结果稍后重试：{exc}。", file=sys.stderr, flush=True)
            except UnauthorizedError:
                raise
            except RemoteAgentError as exc:
                if once:
                    raise
                print(f"同步暂时失败：{exc}。{retry_delay} 秒后重试。", file=sys.stderr, flush=True)
                sleep(retry_delay)
                retry_delay = min(MAX_RETRY_DELAY, retry_delay * 2)
                continue
            if once:
                print(f"已同步 {len(snapshot['tasks'])} 个任务。", flush=True)
                return
            retry_delay = HEARTBEAT_INTERVAL
            sleep(HEARTBEAT_INTERVAL)
    finally:
        if replies is not None:
            replies.close()
        if usage is not None:
            usage.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Agent Monitor 电脑连接器：同步任务状态，支持仅状态模式")
    commands = parser.add_subparsers(dest="command", required=True)
    pair_parser = commands.add_parser("pair", help="使用一次性配对码绑定本机")
    pair_parser.add_argument("--server", required=True, help="工作台服务地址，例如 https://monitor.example.com")
    pair_parser.add_argument("--code", required=True, help="工作台生成的 10 分钟有效一次性配对码")
    pair_parser.add_argument("--name", required=True, help="方便辨认的电脑名称")
    pair_parser.add_argument("--state-dir", type=Path, default=default_state_dir())
    pair_parser.add_argument("--allow-insecure-lan", action="store_true", help="仅供私网 IP 的 HTTP 局域网测试")
    qr_parser = commands.add_parser("pair-qr", help="由手机扫码确认绑定本机")
    qr_parser.add_argument("--server", required=True, help="手机可访问的工作台正式 HTTPS 地址")
    qr_parser.add_argument("--name", required=True, help="方便辨认的电脑名称，最多 60 字")
    qr_parser.add_argument("--state-dir", type=Path, default=default_state_dir())
    run_parser = commands.add_parser("run", help="每 5 秒同步一次任务状态")
    run_parser.add_argument("--state-dir", type=Path, default=default_state_dir())
    run_parser.add_argument("--once", action="store_true", help="只同步一次后退出")
    run_parser.add_argument("--status-only", action="store_true", help="本次仅同步任务状态，不上传结果或文件，也不接收回复")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "pair":
            pair(args.server, args.code, args.name, args.state_dir, args.allow_insecure_lan)
            print("电脑配对成功。运行 run 开始同步；连接凭据已保存到本机状态目录。")
        elif args.command == "pair-qr":
            pair_qr(args.server, args.name, args.state_dir)
            print("手机已确认，电脑配对成功。运行 run 开始同步；连接凭据已保存到本机状态目录。")
        else:
            run(args.state_dir, once=args.once, status_only=args.status_only)
    except UnauthorizedError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except RemoteAgentError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except OSError:
        print("无法保存本机连接设置，请检查状态目录的写入权限。", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("电脑连接器已停止。", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
