from __future__ import annotations

import argparse
import ipaddress
from pathlib import Path
from urllib.parse import urlsplit

import uvicorn

from .server import create_app


def main():
    parser = argparse.ArgumentParser(description="Agent Monitor 个人同步服务")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--state-dir", type=Path, default=Path(".state/hub"))
    parser.add_argument("--no-local", action="store_true", help="仅作为同步服务，不采集本机")
    parser.add_argument("--public-url", help="HTTPS 反向代理的完整来源，例如 https://monitor.example.com")
    parser.add_argument("--allow-insecure-lan", action="store_true", help="仅用于可信局域网临时测试；账号与数据将通过明文HTTP传输")
    parser.add_argument("--allowed-host", action="append", default=[], help="允许访问的额外域名或局域网IP")
    args = parser.parse_args()
    loopback = args.host in {"localhost", "127.0.0.1", "::1"}
    if args.public_url:
        parsed = urlsplit(args.public_url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.path not in {"", "/"} or parsed.query or parsed.fragment or parsed.username:
            parser.error("public-url 必须是 HTTPS 来源地址，不含路径、账号或查询参数")
    elif not loopback and not args.allow_insecure_lan:
        parser.error("远程访问需配置 HTTPS public-url；临时局域网测试可显式使用 --allow-insecure-lan")
    allowed = ["localhost", "127.0.0.1", "[::1]", *args.allowed_host]
    if args.host not in {"0.0.0.0", "::"}:
        allowed.append(args.host)
    if args.public_url:
        allowed.append(urlsplit(args.public_url).hostname)
    print(f"Agent Monitor 已启动：http://127.0.0.1:{args.port}", flush=True)
    print("首次请在这台电脑打开页面建立 App 账号。采集仅限本地会话记录。", flush=True)
    if args.allow_insecure_lan:
        print("当前为临时局域网 HTTP 测试，请勿将此端口暴露到公网。", flush=True)
    app = create_app(args.state_dir, collect_local=not args.no_local, public_url=args.public_url, allowed_hosts=allowed)
    uvicorn.run(app, host=args.host, port=args.port, access_log=False, proxy_headers=False, log_level="warning")


if __name__ == "__main__":
    main()
