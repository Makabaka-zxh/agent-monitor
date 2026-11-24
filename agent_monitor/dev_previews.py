"""Bounded, read-only local dev server port and preview host discovery.

Never opens network connections to discovered ports, never reads process
command-line arguments or environment variables, and never blocks collection.
"""
from __future__ import annotations

import ctypes
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

HOST_PATTERN = re.compile(
    r"^(?:[0-9]{1,3}(?:\.[0-9]{1,3}){3}|[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)$"
)
PROCESS_CLEAN = re.compile(r"[^a-z0-9._-]+")
PROJECT_ID_RE = re.compile(r"^[0-9a-f]{24}$")

_CGNAT_NET = ipaddress.ip_network("100.64.0.0/10")

_NON_WEB_PORTS = frozenset({
    22, 53, 135, 139, 445, 1080, 1081, 1433, 1521, 1883, 2181, 2375, 2376,
    3306, 3389, 4317, 4318, 5040, 5432, 5672, 5900, 5985, 5986, 6379, 6443,
    7680, 7890, 7891, 7892, 7893, 7897, 7898, 7899, 8118, 8766, 9092, 9222,
    9229, 10808, 10809, 10810, 11211, 11434, 20170, 20171, 20172, 27017, 27018,
})

_COMMON_DEV_PORTS = frozenset({
    1313, 2015, 3000, 3001, 3002, 3003, 4000, 4173, 4200, 4321, 5000, 5001,
    5173, 5174, 5175, 5500, 6006, 7860, 8000, 8001, 8080, 8081, 8501, 8787,
    8788, 9000,
})

_DEV_PROCESSES = frozenset({
    "node", "nodejs", "bun", "deno", "python", "python3", "uvicorn", "gunicorn",
    "hypercorn", "daphne", "flask", "django", "streamlit", "gradio", "ruby",
    "puma", "rails", "go", "cargo", "trunk", "dotnet", "java", "gradle", "php",
    "php-cgi", "caddy", "nginx", "hugo", "zola", "mkdocs", "vite", "next-server",
    "esbuild", "workerd", "wrangler", "air",
})

_DENY_PROCESSES = frozenset({
    "system", "svchost", "lsass", "services", "wininit", "csrss", "smss",
    "spoolsv", "searchindexer", "msmpeng", "sihost", "explorer", "dwm",
    "taskhostw", "runtimebroker", "applicationframehost", "widgetservice",
    "onedrive", "spotify", "discord", "steam", "steamwebhelper", "wechat",
    "weixin", "qq", "dingtalk", "feishu", "lark", "slack", "teams", "msedge",
    "chrome", "firefox", "safari", "brave", "opera", "arc", "code", "cursor",
    "windsurf", "agy", "antigravity", "codex", "claude", "adb", "monitor",
    "tailscaled", "tailscale", "tailscale-ipn", "clash", "clash-verge",
    "verge-mihomo", "mihomo", "flclash", "v2ray", "xray", "sing-box",
    "proxifier", "sunloginclient", "todesk", "rustdesk", "parsec", "syncthing",
    "docker", "com.docker.backend", "vpnkit", "wslrelay", "iphlpsvc",
    "nvcontainer", "lghub_agent",
})

_CACHE_LOCK = threading.Lock()
_PORT_CACHE: tuple[float, tuple, list[dict]] = (0.0, (), [])
_HOST_CACHE: tuple[float, str, list[str]] = (0.0, "", [])


def sanitize_process_name(raw: str | None) -> str:
    if not isinstance(raw, str) or not raw.strip():
        return ""
    name = raw.strip().replace("\\", "/").rsplit("/", 1)[-1].lower()
    if name.endswith(".exe"):
        name = name[:-4]
    cleaned = PROCESS_CLEAN.sub("", name)[:32].strip("._-")
    return cleaned


def _is_denied_process(name: str) -> bool:
    if not name:
        return False
    if name in _DENY_PROCESSES:
        return True
    return name.startswith(("language_server", "jetbrains", "idea64", "pycharm", "webstorm", "goland", "clion"))


def valid_preview_host(value: str | None) -> str:
    if not isinstance(value, str):
        return ""
    host = value.strip().rstrip(".").lower()
    if not host or len(host) > 120 or not HOST_PATTERN.fullmatch(host):
        return ""
    try:
        addr = ipaddress.ip_address(host)
        if not isinstance(addr, ipaddress.IPv4Address):
            return ""
        if addr.is_loopback or addr.is_link_local or addr.is_multicast or addr.is_unspecified:
            return ""
        if addr in _CGNAT_NET or addr.is_private:
            return str(addr)
        return ""
    except ValueError:
        if host.endswith(".ts.net") and host.count(".") >= 2:
            return host
        return ""


def _host_sort_key(host: str) -> tuple[int, int, str]:
    if host.endswith(".ts.net"):
        return (0, 0, host)
    try:
        addr = ipaddress.IPv4Address(host)
    except ValueError:
        return (5, 0, host)
    last_octet_gateway = 1 if host.endswith(".1") else 0
    if addr in _CGNAT_NET:
        return (1, 0, host)
    if addr in ipaddress.ip_network("192.168.0.0/16"):
        if host.startswith(("192.168.56.", "192.168.137.")):
            return (4, last_octet_gateway, host)
        return (2, last_octet_gateway, host)
    if addr in ipaddress.ip_network("10.0.0.0/8"):
        return (3, last_octet_gateway, host)
    if addr in ipaddress.ip_network("172.16.0.0/12"):
        if host.startswith("172.17."):
            return (4, 1, host)
        return (3, last_octet_gateway, host)
    return (4, last_octet_gateway, host)


def _run_quiet(argv: list[str], timeout: float = 0.8) -> str:
    options: dict = {
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.DEVNULL,
        "text": True,
        "encoding": "utf-8",
        "errors": "replace",
        "timeout": timeout,
        "shell": False,
    }
    if os.name == "nt":
        options["creationflags"] = subprocess.CREATE_NO_WINDOW
    completed = subprocess.run(argv, check=False, **options)
    return completed.stdout or ""


def _tailscale_hosts() -> list[str]:
    candidates = []
    exe = shutil.which("tailscale")
    if not exe and os.name == "nt":
        program_files = os.environ.get("PROGRAMFILES", r"C:\Program Files")
        fallback = Path(program_files) / "Tailscale" / "tailscale.exe"
        if fallback.is_file():
            exe = str(fallback)
    elif not exe and sys.platform == "darwin":
        for mac_path in ("/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale",
                         "/Applications/Tailscale.app/Contents/MacOS/Tailscale"):
            if Path(mac_path).is_file():
                exe = mac_path
                break
    if not exe:
        return candidates
    try:
        raw = _run_quiet([exe, "status", "--json"], timeout=0.7)
        data = json.loads(raw) if raw else {}
        if not isinstance(data, dict) or data.get("BackendState") != "Running":
            return candidates
        self_node = data.get("Self")
        if not isinstance(self_node, dict):
            return candidates
        dns_name = valid_preview_host(self_node.get("DNSName"))
        if dns_name:
            candidates.append(dns_name)
        for ip_text in self_node.get("TailscaleIPs") or []:
            valid = valid_preview_host(ip_text)
            if valid:
                candidates.append(valid)
    except Exception:
        pass
    return candidates


def _interface_ipv4_hosts() -> list[str]:
    found: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addr = info[4][0] if info and len(info) >= 5 and info[4] else ""
            valid = valid_preview_host(addr)
            if valid:
                found.append(valid)
    except OSError:
        pass
    if os.name != "nt":
        ip_bin = shutil.which("ip")
        if ip_bin:
            try:
                out = _run_quiet([ip_bin, "-4", "-o", "addr", "show"], timeout=0.5)
                for match in re.finditer(r"\binet\s+([0-9]{1,3}(?:\.[0-9]{1,3}){3})/", out):
                    valid = valid_preview_host(match.group(1))
                    if valid:
                        found.append(valid)
            except Exception:
                pass
        else:
            ifconfig_bin = shutil.which("ifconfig")
            if ifconfig_bin:
                try:
                    out = _run_quiet([ifconfig_bin], timeout=0.5)
                    for match in re.finditer(r"\binet\s+([0-9]{1,3}(?:\.[0-9]{1,3}){3})\b", out):
                        valid = valid_preview_host(match.group(1))
                        if valid:
                            found.append(valid)
                except Exception:
                    pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("192.0.2.1", 80))
            primary = valid_preview_host(probe.getsockname()[0])
            if primary:
                found.insert(0, primary)
        finally:
            probe.close()
    except OSError:
        pass
    return found


def discover_preview_hosts(*, public_url: str | None = None, force: bool = False) -> list[str]:
    """Return up to 6 reachable Tailscale / LAN preview hosts for this computer."""
    global _HOST_CACHE
    pub_key = (public_url or "").strip()
    now = time.monotonic()
    with _CACHE_LOCK:
        cached_at, cached_pub, cached_hosts = _HOST_CACHE
        if not force and cached_pub == pub_key and now - cached_at < 60.0:
            return list(cached_hosts)
    hosts: list[str] = []
    if pub_key:
        try:
            from urllib.parse import urlsplit
            parsed_host = valid_preview_host(urlsplit(pub_key).hostname)
            if parsed_host:
                hosts.append(parsed_host)
        except ValueError:
            pass
    hosts.extend(_tailscale_hosts())
    hosts.extend(_interface_ipv4_hosts())
    deduped = sorted(set(hosts), key=_host_sort_key)
    # Prefer real LAN addresses over virtual .1 adapter gateways when non-.1 LAN exists.
    has_non_gateway_lan = any(not h.endswith(".1") and not h.endswith(".ts.net")
                              and ipaddress.IPv4Address(h) not in _CGNAT_NET
                              for h in deduped if not h.endswith(".ts.net"))
    if has_non_gateway_lan:
        deduped = [
            h for h in deduped
            if h.endswith(".ts.net") or ipaddress.IPv4Address(h) in _CGNAT_NET or not h.endswith(".1")
        ]
    result = deduped[:6]
    with _CACHE_LOCK:
        _HOST_CACHE = (time.monotonic(), pub_key, list(result))
    return result


def classify_bind_address(address: str) -> str | None:
    """Return 'network' or 'loopback' for a local listener bind address, or None if invalid."""
    raw = (address or "").strip()
    if not raw:
        return None
    if raw in {"*", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0"}:
        return "network"
    if raw.startswith("[") and raw.endswith("]"):
        raw = raw[1:-1]
    if "%" in raw:
        raw = raw.split("%", 1)[0]
    if raw.lower().startswith("::ffff:"):
        raw = raw[7:]
    try:
        ip = ipaddress.ip_address(raw)
    except ValueError:
        return None
    if ip.is_loopback:
        return "loopback"
    if ip.is_unspecified:
        return "network"
    if isinstance(ip, ipaddress.IPv4Address) and (ip in _CGNAT_NET or ip.is_private):
        return "network"
    if isinstance(ip, ipaddress.IPv6Address) and ip.is_private:
        return "network"
    return None


def _match_project_id(candidate_path: str | Path | None, normalized_projects: list[tuple[str, str]]) -> str:
    if not candidate_path or not normalized_projects:
        return ""
    try:
        norm = os.path.normcase(os.path.abspath(str(candidate_path)))
    except (OSError, ValueError):
        return ""
    sep = os.sep
    for prefix, project_id in normalized_projects:
        if norm == prefix or norm.startswith(prefix + sep):
            return project_id
    return ""


def _win_process_image(pid: int) -> str:
    if pid <= 4:
        return ""
    try:
        from ctypes import wintypes as w
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        kernel32.OpenProcess.restype = w.HANDLE
        kernel32.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
        kernel32.QueryFullProcessImageNameW.restype = w.BOOL
        kernel32.CloseHandle.argtypes = [w.HANDLE]
        handle = kernel32.OpenProcess(0x1000, False, int(pid))
        if not handle:
            return ""
        try:
            size = w.DWORD(2048)
            buf = ctypes.create_unicode_buffer(2048)
            if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
                return buf.value
            return ""
        finally:
            kernel32.CloseHandle(handle)
    except Exception:
        return ""


def _parse_host_port(endpoint: str) -> tuple[str, int] | None:
    text = (endpoint or "").strip()
    if not text or ":" not in text:
        return None
    host, _, port_str = text.rpartition(":")
    if not host or not port_str.isdigit():
        return None
    port = int(port_str)
    if not 1 <= port <= 65535:
        return None
    return host, port


def _scan_windows_listeners() -> list[tuple[int, str, int]]:
    out = _run_quiet(["netstat", "-ano"], timeout=0.9)
    listeners: list[tuple[int, str, int]] = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0].upper() != "TCP" or parts[3].upper() != "LISTENING":
            continue
        if not parts[4].isdigit():
            continue
        parsed = _parse_host_port(parts[1])
        if parsed is None:
            continue
        scope = classify_bind_address(parsed[0])
        if scope:
            listeners.append((parsed[1], scope, int(parts[4])))
    return listeners


def _scan_linux_listeners(normalized_projects: list[tuple[str, str]]) -> list[dict]:
    inode_map: dict[str, tuple[int, str]] = {}
    for table, ipv6 in (("/proc/net/tcp", False), ("/proc/net/tcp6", True)):
        p = Path(table)
        if not p.is_file():
            continue
        try:
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()[1:4096]
        except OSError:
            continue
        for line in lines:
            cols = line.split()
            if len(cols) < 10 or cols[3] != "0A":
                continue
            local_hex = cols[1]
            addr_hex, _, port_hex = local_hex.partition(":")
            try:
                port = int(port_hex, 16)
                raw_bytes = bytes.fromhex(addr_hex)
                if not ipv6 and len(raw_bytes) == 4:
                    addr = str(ipaddress.IPv4Address(raw_bytes[::-1]))
                elif ipv6 and len(raw_bytes) == 16:
                    words = [raw_bytes[i:i + 4][::-1] for i in range(0, 16, 4)]
                    addr = str(ipaddress.IPv6Address(b"".join(words)))
                else:
                    continue
            except ValueError:
                continue
            scope = classify_bind_address(addr)
            if not scope or not 1024 <= port <= 65535:
                continue
            inode = cols[9]
            if inode and inode != "0":
                prev = inode_map.get(inode)
                if prev is None or scope == "network":
                    inode_map[inode] = (port, scope)
    if not inode_map:
        return []
    uid = os.getuid() if hasattr(os, "getuid") else None
    pid_info: dict[str, tuple[str, str]] = {}
    proc_root = Path("/proc")
    try:
        entries = list(proc_root.iterdir())[:2048]
    except OSError:
        entries = []
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            if uid is not None and entry.stat().st_uid != uid:
                continue
            fd_dir = entry / "fd"
            matched_inodes = []
            for fd in fd_dir.iterdir():
                try:
                    target = os.readlink(fd)
                except OSError:
                    continue
                if target.startswith("socket:[") and target.endswith("]"):
                    ino = target[8:-1]
                    if ino in inode_map:
                        matched_inodes.append(ino)
            if not matched_inodes:
                continue
            comm = sanitize_process_name((entry / "comm").read_text(encoding="utf-8", errors="replace"))
            try:
                cwd = os.readlink(entry / "cwd")
            except OSError:
                cwd = ""
            proj_id = _match_project_id(cwd, normalized_projects)
            for ino in matched_inodes:
                pid_info[ino] = (comm, proj_id)
        except OSError:
            continue
    grouped: dict[int, dict] = {}
    for ino, (port, scope) in inode_map.items():
        if ino not in pid_info:
            continue
        proc_name, proj_id = pid_info[ino]
        cur = grouped.get(port)
        if cur is None:
            grouped[port] = {"port": port, "bind_scope": scope, "process": proc_name, "project_id": proj_id}
        else:
            if scope == "network":
                cur["bind_scope"] = "network"
            if not cur["process"] and proc_name:
                cur["process"] = proc_name
            if not cur["project_id"] and proj_id:
                cur["project_id"] = proj_id
    return list(grouped.values())


def _scan_darwin_listeners(normalized_projects: list[tuple[str, str]]) -> list[dict]:
    lsof_bin = shutil.which("lsof") or ("/usr/sbin/lsof" if Path("/usr/sbin/lsof").is_file() else "")
    if not lsof_bin:
        return []
    out = _run_quiet([lsof_bin, "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcn"], timeout=0.9)
    pid = 0
    proc_name = ""
    raw_listeners: list[tuple[int, str, int, str]] = []
    candidate_pids: set[int] = set()
    for line in out.splitlines():
        if not line:
            continue
        tag, value = line[0], line[1:]
        if tag == "p" and value.isdigit():
            pid = int(value)
            proc_name = ""
        elif tag == "c":
            proc_name = sanitize_process_name(value)
        elif tag == "n" and pid > 0:
            parsed = _parse_host_port(value)
            if parsed is None or not 1024 <= parsed[1] <= 65535:
                continue
            scope = classify_bind_address(parsed[0])
            if scope:
                raw_listeners.append((parsed[1], scope, pid, proc_name))
                candidate_pids.add(pid)
    pid_projects: dict[int, str] = {}
    if candidate_pids and normalized_projects:
        pid_arg = ",".join(str(p) for p in sorted(candidate_pids)[:32])
        try:
            cwd_out = _run_quiet([lsof_bin, "-a", "-d", "cwd", "-p", pid_arg, "-F", "pn"], timeout=0.6)
            cur_pid = 0
            for line in cwd_out.splitlines():
                if line.startswith("p") and line[1:].isdigit():
                    cur_pid = int(line[1:])
                elif line.startswith("n") and cur_pid > 0:
                    matched = _match_project_id(line[1:], normalized_projects)
                    if matched:
                        pid_projects[cur_pid] = matched
        except Exception:
            pass
    grouped: dict[int, dict] = {}
    for port, scope, lpid, pname in raw_listeners:
        proj_id = pid_projects.get(lpid, "")
        cur = grouped.get(port)
        if cur is None:
            grouped[port] = {"port": port, "bind_scope": scope, "process": pname, "project_id": proj_id}
        else:
            if scope == "network":
                cur["bind_scope"] = "network"
            if not cur["process"] and pname:
                cur["process"] = pname
            if not cur["project_id"] and proj_id:
                cur["project_id"] = proj_id
    return list(grouped.values())


def filter_dev_port_candidates(
    entries: list[dict],
    *,
    exclude_ports: set[int] | frozenset[int] = frozenset({8766}),
) -> list[dict]:
    """Filter raw listener dicts down to genuine local web/dev preview ports."""
    accepted: list[dict] = []
    for item in entries:
        port = item.get("port")
        scope = item.get("bind_scope")
        if type(port) is not int or not 1024 <= port <= 65535 or port in exclude_ports:
            continue
        if scope not in {"network", "loopback"}:
            continue
        proc = sanitize_process_name(item.get("process"))
        if _is_denied_process(proc):
            continue
        proj_id = item.get("project_id") if isinstance(item.get("project_id"), str) else ""
        if proj_id and not PROJECT_ID_RE.fullmatch(proj_id):
            proj_id = ""
        if port in _NON_WEB_PORTS and not proj_id:
            continue
        is_dev = bool(
            proj_id
            or (proc in _DEV_PROCESSES and port < 49152)
            or (not proc and port in _COMMON_DEV_PORTS)
            or (proc == "pythonw" and port in _COMMON_DEV_PORTS)
        )
        if not is_dev:
            continue
        accepted.append({
            "port": port,
            "bind_scope": scope,
            "process": proc,
            "project_id": proj_id,
        })
    accepted.sort(key=lambda d: (0 if d["project_id"] else 1, 0 if d["bind_scope"] == "network" else 1, d["port"]))
    return accepted[:12]


def discover_dev_ports(
    projects_by_path: dict[Path, str] | None = None,
    *,
    exclude_ports: set[int] | frozenset[int] = frozenset({8766}),
    force: bool = False,
) -> list[dict]:
    """Discover up to 12 active local dev web ports without blocking collection."""
    global _PORT_CACHE
    excluded = frozenset(int(p) for p in exclude_ports if isinstance(p, int))
    normalized_projects: list[tuple[str, str]] = []
    for raw_path, proj_id in (projects_by_path or {}).items():
        if isinstance(proj_id, str) and PROJECT_ID_RE.fullmatch(proj_id):
            try:
                norm = os.path.normcase(os.path.abspath(str(raw_path))).rstrip(os.sep)
                if norm:
                    normalized_projects.append((norm, proj_id))
            except (OSError, ValueError):
                continue
    # Longest path first so nested sub-projects match their most specific workspace.
    normalized_projects.sort(key=lambda pair: len(pair[0]), reverse=True)
    cache_key = (tuple(sorted(excluded)), tuple(proj_id for _, proj_id in normalized_projects[:32]))
    now = time.monotonic()
    with _CACHE_LOCK:
        cached_at, cached_key, cached_ports = _PORT_CACHE
        if not force and cached_key == cache_key and now - cached_at < 10.0:
            return [dict(item) for item in cached_ports]
    raw_entries: list[dict] = []
    try:
        if os.name == "nt":
            win_rows = _scan_windows_listeners()
            by_port: dict[int, dict] = {}
            pid_cache: dict[int, tuple[str, str]] = {}
            for port, scope, pid in win_rows:
                if not 1024 <= port <= 65535 or port in excluded:
                    continue
                if pid not in pid_cache:
                    if len(pid_cache) >= 96:
                        pid_cache[pid] = ("", "")
                    else:
                        img = _win_process_image(pid)
                        pid_cache[pid] = (
                            sanitize_process_name(img),
                            _match_project_id(img, normalized_projects),
                        )
                proc_name, proj_id = pid_cache[pid]
                cur = by_port.get(port)
                if cur is None:
                    by_port[port] = {"port": port, "bind_scope": scope, "process": proc_name, "project_id": proj_id}
                else:
                    if scope == "network":
                        cur["bind_scope"] = "network"
                    if not cur["process"] and proc_name:
                        cur["process"] = proc_name
                    if not cur["project_id"] and proj_id:
                        cur["project_id"] = proj_id
            raw_entries = list(by_port.values())
        elif sys.platform == "darwin":
            raw_entries = _scan_darwin_listeners(normalized_projects)
        else:
            raw_entries = _scan_linux_listeners(normalized_projects)
    except Exception:
        raw_entries = []
    filtered = filter_dev_port_candidates(raw_entries, exclude_ports=excluded)
    with _CACHE_LOCK:
        _PORT_CACHE = (time.monotonic(), cache_key, [dict(item) for item in filtered])
    return filtered
