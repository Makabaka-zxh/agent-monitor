"""Local Antigravity Language Server quota & fast model catalog reader.

Connects strictly to current-user loopback (127.0.0.1) LanguageServerService
instances spawned by Antigravity Desktop. Account identity fields (name, email,
avatar, CSRF token) never leave this module or enter any database/log.
"""
from __future__ import annotations

import ctypes
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import struct
import subprocess
import urllib.error
import urllib.request
from typing import Callable

UTC = timezone.utc
GET_USER_STATUS_PATH = "/exa.language_server_pb.LanguageServerService/GetUserStatus"
RETRIEVE_USER_QUOTA_SUMMARY_PATH = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary"
MAX_STATUS_BYTES = 512 * 1024
_CSRF_RE = re.compile(r"--csrf_token(?:=|\s+)([A-Za-z0-9_-]{8,256})")
_HTTPS_PORT_RE = re.compile(r"--https_server_port(?:=|\s+)(\d{1,5})")
_EXT_PORT_RE = re.compile(r"--extension_server_port(?:=|\s+)(\d{1,5})")
_EFFORT_LABELS = {
    "low": "low",
    "medium": "medium",
    "high": "high",
    "xhigh": "xhigh",
}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _ordered_ports(raw_ports: list[int], cmd: str) -> list[int]:
    preferred = []
    https_match = _HTTPS_PORT_RE.search(cmd)
    if https_match:
        port = int(https_match.group(1))
        if 0 < port <= 65535:
            preferred.append(port)
    ext_match = _EXT_PORT_RE.search(cmd)
    ext_port = int(ext_match.group(1)) if ext_match else 0
    middle = []
    tail = []
    for port in raw_ports:
        if not (0 < port <= 65535) or port in preferred or port in middle or port in tail:
            continue
        if port == ext_port:
            tail.append(port)
        else:
            middle.append(port)
    return preferred + middle + tail


def _discover_windows() -> list[tuple[int, str, list[int]]]:
    from ctypes import wintypes as w

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    ntdll = ctypes.WinDLL("ntdll")
    iphlp = ctypes.WinDLL("iphlpapi")

    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [
            ("dwSize", w.DWORD), ("cntUsage", w.DWORD), ("th32ProcessID", w.DWORD),
            ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", w.DWORD),
            ("cntThreads", w.DWORD), ("th32ParentProcessID", w.DWORD),
            ("pcPriClassBase", w.LONG), ("dwFlags", w.DWORD), ("szExeFile", w.WCHAR * 260),
        ]

    class UNICODE_STRING(ctypes.Structure):
        _fields_ = [("Length", w.USHORT), ("MaximumLength", w.USHORT), ("Buffer", ctypes.c_void_p)]

    k32.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
    k32.CreateToolhelp32Snapshot.restype = w.HANDLE
    k32.Process32FirstW.argtypes = [w.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.Process32FirstW.restype = w.BOOL
    k32.Process32NextW.argtypes = [w.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.Process32NextW.restype = w.BOOL
    k32.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    k32.OpenProcess.restype = w.HANDLE
    k32.CloseHandle.argtypes = [w.HANDLE]
    k32.CloseHandle.restype = w.BOOL

    snap = k32.CreateToolhelp32Snapshot(2, 0)
    invalid = w.HANDLE(-1).value
    if not snap or snap == invalid:
        return []
    candidates: list[tuple[int, str]] = []
    try:
        pe = PROCESSENTRY32W(dwSize=ctypes.sizeof(PROCESSENTRY32W))
        if k32.Process32FirstW(snap, ctypes.byref(pe)):
            while True:
                exe = pe.szExeFile.lower()
                if exe.startswith("language_server"):
                    proc = k32.OpenProcess(0x1000, False, pe.th32ProcessID)
                    if proc:
                        try:
                            needed = w.ULONG(0)
                            ntdll.NtQueryInformationProcess(proc, 60, None, 0, ctypes.byref(needed))
                            if 0 < needed.value <= 65536:
                                buf = ctypes.create_string_buffer(needed.value)
                                if ntdll.NtQueryInformationProcess(proc, 60, buf, needed.value, ctypes.byref(needed)) == 0:
                                    us = UNICODE_STRING.from_buffer(buf)
                                    if us.Buffer and us.Length:
                                        cmd = ctypes.wstring_at(us.Buffer, us.Length // 2)
                                        if _CSRF_RE.search(cmd):
                                            candidates.append((int(pe.th32ProcessID), cmd))
                        finally:
                            k32.CloseHandle(proc)
                if not k32.Process32NextW(snap, ctypes.byref(pe)):
                    break
    finally:
        k32.CloseHandle(snap)

    if not candidates:
        return []

    ports_by_pid: dict[int, list[int]] = {}
    table_size = w.DWORD(0)
    iphlp.GetExtendedTcpTable(None, ctypes.byref(table_size), False, 2, 3, 0)
    if 0 < table_size.value <= 4 * 1024 * 1024:
        buf = ctypes.create_string_buffer(table_size.value)
        if iphlp.GetExtendedTcpTable(buf, ctypes.byref(table_size), False, 2, 3, 0) == 0:
            raw = buf.raw
            count = struct.unpack_from("<I", raw, 0)[0]
            max_rows = min(count, (len(raw) - 4) // 24)
            for idx in range(max_rows):
                _, local_addr, local_port, _, _, owner_pid = struct.unpack_from("<6I", raw, 4 + idx * 24)
                if socket.inet_ntoa(struct.pack("<I", local_addr)) == "127.0.0.1":
                    ports_by_pid.setdefault(int(owner_pid), []).append(socket.ntohs(local_port & 0xFFFF))

    results = []
    for pid, cmd in candidates:
        csrf_match = _CSRF_RE.search(cmd)
        if not csrf_match:
            continue
        ordered = _ordered_ports(ports_by_pid.get(pid, []), cmd)
        if ordered:
            results.append((pid, csrf_match.group(1), ordered))
    return results


def _linux_loopback_ports(pid: int) -> list[int]:
    inodes: set[str] = set()
    fd_dir = Path(f"/proc/{pid}/fd")
    try:
        for entry in os.scandir(fd_dir):
            try:
                target = os.readlink(entry.path)
            except OSError:
                continue
            if target.startswith("socket:[") and target.endswith("]"):
                inodes.add(target[8:-1])
    except OSError:
        return []
    if not inodes:
        return []
    ports: list[int] = []
    try:
        lines = Path(f"/proc/{pid}/net/tcp").read_text(encoding="ascii", errors="ignore").splitlines()[1:]
    except OSError:
        return []
    for line in lines:
        parts = line.split()
        if len(parts) < 10 or parts[3] != "0A" or parts[9] not in inodes:
            continue
        addr_hex, _, port_hex = parts[1].partition(":")
        if addr_hex == "0100007F":
            try:
                ports.append(int(port_hex, 16))
            except ValueError:
                continue
    return ports


def _discover_posix() -> list[tuple[int, str, list[int]]]:
    ps_bin = shutil.which("ps") or ("/bin/ps" if Path("/bin/ps").is_file() else None)
    if not ps_bin:
        return []
    try:
        proc = subprocess.run(
            [ps_bin, "-ax", "-o", "pid=,uid=,command="],
            capture_output=True, text=True, timeout=1.5, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    if proc.returncode != 0 or not proc.stdout:
        return []
    my_uid = os.getuid()
    candidates: list[tuple[int, str]] = []
    for line in proc.stdout.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) != 3:
            continue
        try:
            pid, uid = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        cmd = parts[2]
        if uid != my_uid or "language_server" not in cmd or not _CSRF_RE.search(cmd):
            continue
        candidates.append((pid, cmd))
    if not candidates:
        return []

    lsof_bin = shutil.which("lsof")
    if not lsof_bin:
        for fallback in ("/usr/sbin/lsof", "/usr/bin/lsof"):
            if Path(fallback).is_file():
                lsof_bin = fallback
                break

    results = []
    for pid, cmd in candidates[:4]:
        csrf_match = _CSRF_RE.search(cmd)
        if not csrf_match:
            continue
        raw_ports: list[int] = []
        if lsof_bin:
            try:
                lsof = subprocess.run(
                    [lsof_bin, "-Pan", "-p", str(pid), "-iTCP", "-sTCP:LISTEN"],
                    capture_output=True, text=True, timeout=1.5, check=False,
                )
                for row in (lsof.stdout or "").splitlines():
                    for match in re.finditer(r"127\.0\.0\.1:(\d{1,5})\b", row):
                        raw_ports.append(int(match.group(1)))
            except (OSError, subprocess.SubprocessError):
                pass
        if not raw_ports:
            raw_ports.extend(_linux_loopback_ports(pid))
        ordered = _ordered_ports(raw_ports, cmd)
        if ordered:
            results.append((pid, csrf_match.group(1), ordered))
    return results


def discover_local_language_servers() -> list[tuple[int, str, list[int]]]:
    try:
        return _discover_windows() if os.name == "nt" else _discover_posix()
    except Exception:
        return []


def _scrub_cascade_config(raw: dict) -> dict | None:
    user_status = raw.get("userStatus")
    if not isinstance(user_status, dict):
        return None
    cascade = user_status.get("cascadeModelConfigData")
    if not isinstance(cascade, dict):
        return None
    configs = cascade.get("clientModelConfigs")
    if not isinstance(configs, list) or not 0 < len(configs) <= 256:
        return None
    clean_configs = []
    for item in configs:
        if not isinstance(item, dict):
            continue
        model_id = item.get("modelId")
        label = item.get("label")
        if not isinstance(model_id, str) or not model_id or len(model_id) > 120:
            continue
        entry: dict = {"modelId": model_id, "label": label if isinstance(label, str) and len(label) <= 120 else model_id}
        alias = item.get("modelOrAlias")
        if isinstance(alias, dict) and isinstance(alias.get("model"), str) and len(alias["model"]) <= 120:
            entry["aliasModel"] = alias["model"]
        quota = item.get("quotaInfo")
        if isinstance(quota, dict):
            q_clean: dict = {}
            if "remainingFraction" in quota and type(quota["remainingFraction"]) in (int, float) and math.isfinite(quota["remainingFraction"]):
                q_clean["remainingFraction"] = float(quota["remainingFraction"])
            if isinstance(quota.get("resetTime"), str) and len(quota["resetTime"]) <= 64:
                q_clean["resetTime"] = quota["resetTime"]
            if q_clean:
                entry["quotaInfo"] = q_clean
        clean_configs.append(entry)
    if not clean_configs:
        return None
    default_alias = None
    override = cascade.get("defaultOverrideModelConfig")
    if isinstance(override, dict):
        alias = override.get("modelOrAlias")
        if isinstance(alias, dict) and isinstance(alias.get("model"), str) and len(alias["model"]) <= 120:
            default_alias = alias["model"]
    return {"clientModelConfigs": clean_configs, "defaultAliasModel": default_alias}


def _scrub_quota_summary(raw: dict) -> dict | None:
    resp = raw.get("response") if isinstance(raw.get("response"), dict) else raw
    if not isinstance(resp, dict):
        return None
    groups = resp.get("groups")
    if not isinstance(groups, list) or not 0 < len(groups) <= 32:
        return None
    clean_groups = []
    for grp in groups:
        if not isinstance(grp, dict):
            continue
        disp = grp.get("displayName")
        disp_str = disp.strip()[:120] if isinstance(disp, str) else ""
        buckets = grp.get("buckets")
        if not isinstance(buckets, list) or not 0 < len(buckets) <= 16:
            continue
        clean_buckets = []
        for b in buckets:
            if not isinstance(b, dict):
                continue
            bid = b.get("bucketId")
            bid_str = bid.strip()[:64] if isinstance(bid, str) else ""
            win = b.get("window")
            win_str = win.strip().lower()[:24] if isinstance(win, str) else ""
            disabled = b.get("disabled") is True
            reset_str = b["resetTime"] if isinstance(b.get("resetTime"), str) and len(b["resetTime"]) <= 64 else None
            rem_val: float | None = None
            if "remainingFraction" in b:
                rf = b["remainingFraction"]
                if type(rf) in (int, float) and math.isfinite(rf) and 0.0 <= rf <= 1.0:
                    rem_val = float(rf)
                else:
                    continue
            elif reset_str is not None and not disabled:
                # Protobuf proto3 JSON omits remainingFraction when 0.0 (exhausted).
                rem_val = 0.0
            elif not disabled:
                continue
            entry: dict = {
                "bucketId": bid_str,
                "window": win_str,
                "disabled": disabled,
            }
            if rem_val is not None:
                entry["remainingFraction"] = rem_val
            if reset_str is not None:
                entry["resetTime"] = reset_str
            clean_buckets.append(entry)
        if clean_buckets:
            clean_groups.append({"displayName": disp_str, "buckets": clean_buckets})
    return {"groups": clean_groups} if clean_groups else None


def _call_local_rpc(
    rpc_path: str,
    sanitizer: Callable[[dict], dict | None],
    *,
    discover: Callable[[], list[tuple[int, str, list[int]]]] = discover_local_language_servers,
    timeout: float = 1.0,
) -> dict | None:
    servers = discover()
    if not servers:
        return None
    tls = ssl.create_default_context()
    tls.check_hostname = False
    tls.verify_mode = ssl.CERT_NONE
    https_opener = urllib.request.build_opener(_NoRedirect(), urllib.request.HTTPSHandler(context=tls))
    http_opener = urllib.request.build_opener(_NoRedirect())
    for _pid, csrf, ports in servers[:4]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Connect-Protocol-Version": "1",
            "X-Codeium-Csrf-Token": csrf,
        }
        for port in ports[:8]:
            if not (0 < port <= 65535):
                continue
            for scheme, opener in (("https", https_opener), ("http", http_opener)):
                url = f"{scheme}://127.0.0.1:{port}{rpc_path}"
                req = urllib.request.Request(url, data=b"{}", headers=headers, method="POST")
                try:
                    with opener.open(req, timeout=timeout) as resp:
                        raw_bytes = resp.read(MAX_STATUS_BYTES + 1)
                    if len(raw_bytes) > MAX_STATUS_BYTES:
                        continue
                    parsed = json.loads(raw_bytes)
                    if isinstance(parsed, dict):
                        scrubbed = sanitizer(parsed)
                        if scrubbed is not None:
                            return scrubbed
                except (urllib.error.URLError, TimeoutError, OSError, ValueError, UnicodeError):
                    continue
    return None


def fetch_antigravity_quota_summary(
    *,
    discover: Callable[[], list[tuple[int, str, list[int]]]] = discover_local_language_servers,
    timeout: float = 1.0,
) -> dict | None:
    """Query local LanguageServerService/RetrieveUserQuotaSummary for both 5-hour and weekly quotas."""
    return _call_local_rpc(RETRIEVE_USER_QUOTA_SUMMARY_PATH, _scrub_quota_summary, discover=discover, timeout=timeout)


def fetch_antigravity_user_status(
    *,
    discover: Callable[[], list[tuple[int, str, list[int]]]] = discover_local_language_servers,
    timeout: float = 1.0,
) -> dict | None:
    """Query local LanguageServerService/GetUserStatus and scrub all account PII immediately."""
    return _call_local_rpc(GET_USER_STATUS_PATH, _scrub_cascade_config, discover=discover, timeout=timeout)


def _parse_reset_epoch(value: str | None) -> int | None:
    if not isinstance(value, str) or not value or len(value) > 64:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            return None
        epoch = int(dt.astimezone(UTC).timestamp())
        return epoch if 946684800 <= epoch <= 253402300799 else None
    except (ValueError, OverflowError):
        return None


def _pool_window_minutes(resets_at: int | None, now_epoch: int) -> int:
    if resets_at is None:
        return 300
    delta = resets_at - now_epoch
    if delta > 25 * 3600:
        return 10080
    if delta > 6 * 3600:
        return 1440
    return 300


def _classify_pools(pools: list[tuple[float, int | None, list[str]]], now_epoch: int) -> list[dict]:
    if not pools:
        return []
    if len(pools) == 1:
        used_pct, resets_at, models = pools[0]
        all_gemini = all(m.startswith("gemini-") for m in models)
        all_claude = all(m.startswith("claude-") for m in models)
        if all_gemini:
            bucket_id, label = "gemini", "Gemini"
        elif all_claude:
            bucket_id, label = "claude", "Claude"
        else:
            bucket_id, label = "all", "全部模型"
        minutes = _pool_window_minutes(resets_at, now_epoch)
        return [{
            "kind": f"pool_{bucket_id}",
            "bucket_id": bucket_id,
            "bucket_label": label,
            "model": models[0] if len(models) == 1 else None,
            "used_percentage": used_pct,
            "window_minutes": minutes,
            "resets_at": resets_at,
        }]

    gemini_pool_count = sum(1 for _, _, ms in pools if all(m.startswith("gemini-") for m in ms))
    windows: list[dict] = []
    seen_buckets: set[str] = set()
    for idx, (used_pct, resets_at, models) in enumerate(pools):
        has_gemini = any(m.startswith("gemini-") for m in models)
        has_claude = any(m.startswith("claude-") for m in models)
        has_gpt = any(m.startswith(("gpt-", "o1", "o3", "o4")) for m in models)
        single_model = models[0] if len(models) == 1 else None

        if has_gemini and not has_claude and not has_gpt:
            if gemini_pool_count == 1:
                bucket_id, label = "gemini", "Gemini"
            elif all("flash" in m for m in models):
                bucket_id, label = "gemini_flash", "Gemini Flash"
            elif any("ultra" in m for m in models):
                bucket_id, label = "gemini_ultra", "Gemini Ultra"
            else:
                bucket_id, label = "gemini_pro", "Gemini Pro"
        elif has_claude and has_gpt and not has_gemini:
            bucket_id, label = "claude_gpt_oss", "Claude / GPT-OSS"
        elif has_claude and not has_gemini and not has_gpt:
            bucket_id, label = "claude", "Claude"
        elif has_gpt and not has_gemini and not has_claude:
            bucket_id, label = "gpt_oss", "GPT-OSS"
        else:
            bucket_id, label = f"shared_{idx + 1}", "全部模型"

        if bucket_id in seen_buckets:
            bucket_id = f"{bucket_id}_{idx + 1}"
        seen_buckets.add(bucket_id)
        minutes = _pool_window_minutes(resets_at, now_epoch)
        windows.append({
            "kind": f"pool_{bucket_id}",
            "bucket_id": bucket_id,
            "bucket_label": None if single_model else label,
            "model": single_model,
            "used_percentage": used_pct,
            "window_minutes": minutes,
            "resets_at": resets_at,
        })
    return windows


def _classify_summary_groups(groups: list[dict], now_epoch: int) -> list[dict]:
    if not groups:
        return []

    def is_gemini_group(grp: dict) -> bool:
        dn = grp.get("displayName", "").lower()
        if "gemini" in dn:
            return True
        b_ids = [b.get("bucketId", "").lower() for b in grp.get("buckets", []) if isinstance(b, dict)]
        return bool(b_ids) and all(bid.startswith("gemini") for bid in b_ids)

    gemini_group_count = sum(1 for g in groups if is_gemini_group(g))
    windows: list[dict] = []
    seen_slots: set[tuple[str, str]] = set()

    for idx, grp in enumerate(groups):
        dn = grp.get("displayName", "").lower()
        buckets = grp.get("buckets", [])
        b_ids = [b.get("bucketId", "").lower() for b in buckets if isinstance(b, dict)]
        if is_gemini_group(grp):
            if gemini_group_count == 1:
                group_slug, group_label = "gemini", "Gemini"
            elif "flash" in dn or any("flash" in bid for bid in b_ids):
                group_slug, group_label = "gemini_flash", "Gemini Flash"
            elif "ultra" in dn or any("ultra" in bid for bid in b_ids):
                group_slug, group_label = "gemini_ultra", "Gemini Ultra"
            else:
                group_slug, group_label = "gemini_pro", "Gemini Pro"
        elif ("claude" in dn and "gpt" in dn) or any(bid.startswith("3p") for bid in b_ids):
            group_slug, group_label = "claude_gpt_oss", "Claude / GPT-OSS"
        elif "claude" in dn or any("claude" in bid for bid in b_ids):
            group_slug, group_label = "claude", "Claude"
        elif "gpt" in dn or any("gpt" in bid for bid in b_ids):
            group_slug, group_label = "gpt_oss", "GPT-OSS"
        else:
            group_slug, group_label = f"shared_{idx + 1}", "全部模型"

        # Inspect sibling weekly bucket first so disabled 5h buckets reflect weekly exhaustion accurately.
        weekly_used: float | None = None
        weekly_reset: int | None = None
        for b in buckets:
            win = b.get("window", "")
            if win in ("weekly", "7d", "seven_day", "1w") and not b.get("disabled"):
                rf = b.get("remainingFraction")
                if type(rf) in (int, float):
                    weekly_used = round(max(0.0, min(100.0, (1.0 - float(rf)) * 100.0)), 1)
                weekly_reset = _parse_reset_epoch(b.get("resetTime"))
                break

        for b in buckets:
            win = b.get("window", "")
            resets_at = _parse_reset_epoch(b.get("resetTime"))
            if win in ("5h", "5_hour", "five_hour"):
                slot_prefix, minutes = "five_hour", 300
            elif win in ("weekly", "7d", "seven_day", "1w"):
                slot_prefix, minutes = "seven_day", 10080
            elif win in ("1d", "24h", "daily"):
                slot_prefix, minutes = "one_day", 1440
            else:
                minutes = _pool_window_minutes(resets_at, now_epoch)
                slot_prefix = "seven_day" if minutes >= 10080 else "one_day" if minutes >= 1440 else "five_hour"

            if b.get("disabled") is True:
                if slot_prefix == "five_hour" and weekly_used is not None and weekly_used >= 99.9 and weekly_reset is not None:
                    used_pct = 100.0
                    resets_at = weekly_reset
                else:
                    continue
            else:
                rf = b.get("remainingFraction")
                if type(rf) not in (int, float):
                    continue
                used_pct = round(max(0.0, min(100.0, (1.0 - float(rf)) * 100.0)), 1)

            slot = f"{slot_prefix}_{group_slug}"
            if (slot, group_slug) in seen_slots:
                continue
            seen_slots.add((slot, group_slug))
            windows.append({
                "kind": slot,
                "bucket_id": group_slug,
                "bucket_label": group_label,
                "model": None,
                "used_percentage": used_pct,
                "window_minutes": minutes,
                "resets_at": resets_at,
            })

    return windows


def read_antigravity_quota(
    *,
    now: datetime | float | int | None = None,
    antigravity_home: Path | str | None = None,
    summary_fetcher: Callable[[], dict | None] | None = None,
    status_fetcher: Callable[[], dict | None] = fetch_antigravity_user_status,
) -> dict | None:
    """Return validated official Antigravity quota pools (both 5h and weekly when available)."""
    if now is None:
        current = datetime.now(UTC)
    elif isinstance(now, (int, float)) and not isinstance(now, bool):
        current = datetime.fromtimestamp(now, UTC)
    elif isinstance(now, datetime) and now.tzinfo is not None:
        current = now.astimezone(UTC)
    else:
        raise ValueError("now must be timezone-aware")

    use_live_default = summary_fetcher is None and status_fetcher is fetch_antigravity_user_status
    if use_live_default:
        ag_dir = Path(antigravity_home) if antigravity_home is not None else (Path.home() / ".gemini" / "antigravity")
        if not ag_dir.is_dir():
            return None

    now_epoch = int(current.timestamp())
    observed_iso = current.isoformat(timespec="milliseconds").replace("+00:00", "Z")

    active_summary_fetcher = fetch_antigravity_quota_summary if use_live_default else summary_fetcher
    summary_windows: list[dict] = []
    if active_summary_fetcher is not None:
        summary_data = active_summary_fetcher()
        if isinstance(summary_data, dict) and isinstance(summary_data.get("groups"), list):
            summary_windows = _classify_summary_groups(summary_data["groups"], now_epoch)

    if summary_windows:
        has_gemini = any(str(w.get("bucket_id", "")).startswith("gemini") for w in summary_windows)
        if not has_gemini:
            # Merge fallback Gemini pool if RetrieveUserQuotaSummary only returned 3p buckets.
            fallback = _read_status_pools(status_fetcher, now_epoch)
            for fw in fallback:
                if str(fw.get("bucket_id", "")).startswith("gemini"):
                    summary_windows.append(fw)
        return {
            "observed_at": observed_iso,
            "snapshot_complete": True,
            "quota_windows": summary_windows,
        }

    fallback_windows = _read_status_pools(status_fetcher, now_epoch)
    if not fallback_windows:
        return None
    return {
        "observed_at": observed_iso,
        "snapshot_complete": True,
        "quota_windows": fallback_windows,
    }


def _read_status_pools(status_fetcher: Callable[[], dict | None], now_epoch: int) -> list[dict]:
    status = status_fetcher()
    if not isinstance(status, dict):
        return []
    configs = status.get("clientModelConfigs")
    if not isinstance(configs, list) or not configs:
        return []

    grouped: dict[tuple[float, int | None], list[str]] = {}
    for item in configs:
        if not isinstance(item, dict):
            continue
        model_id = item.get("modelId")
        quota = item.get("quotaInfo")
        if not isinstance(model_id, str) or not isinstance(quota, dict):
            continue
        resets_at = _parse_reset_epoch(quota.get("resetTime"))
        if "remainingFraction" in quota:
            rem = quota["remainingFraction"]
            if type(rem) not in (int, float) or not math.isfinite(rem) or not 0.0 <= rem <= 1.0:
                continue
            remaining = float(rem)
        elif resets_at is not None:
            # Protobuf proto3 JSON omits remainingFraction when its double value is 0.0 (exhausted).
            remaining = 0.0
        else:
            continue
        key = (round(remaining, 6), resets_at)
        models = grouped.setdefault(key, [])
        if model_id.lower() not in models:
            models.append(model_id.lower())

    if not grouped:
        return []

    pools = [(round(max(0.0, min(100.0, (1.0 - rem) * 100.0)), 1), resets_at, models)
             for (rem, resets_at), models in grouped.items()]
    return _classify_pools(pools, now_epoch)


def _split_effort_variant(model_id: str, label: str) -> tuple[str, str, str] | None:
    """Split ('gemini-4-neon-high', 'Gemini 4 Neon (High)') -> ('gemini-4-neon', 'Gemini 4 Neon', 'high')."""
    dash = model_id.rfind("-")
    if dash <= 0:
        return None
    suffix = model_id[dash + 1:].lower()
    canonical = _EFFORT_LABELS.get(suffix)
    if canonical is None:
        return None
    paren_match = re.match(r"^(.*\S)\s+\(([A-Za-z0-9_-]+)\)\s*$", label)
    if not paren_match or paren_match.group(2).lower() != canonical:
        return None
    return model_id[:dash], paren_match.group(1), canonical


def antigravity_fast_catalog_rows(status_payload: dict) -> tuple[list[dict], str | None, str | None]:
    """Derive exact ACP-equivalent catalog rows from scrubbed GetUserStatus config data."""
    if not isinstance(status_payload, dict):
        raise ValueError("unsupported model catalog")
    configs = status_payload.get("clientModelConfigs")
    if not isinstance(configs, list) or not 0 < len(configs) <= 256:
        raise ValueError("unsupported model catalog")

    parsed_items: list[tuple[dict, tuple[str, str, str] | None]] = []
    base_counts: dict[str, int] = {}
    for item in configs:
        if not isinstance(item, dict) or not isinstance(item.get("modelId"), str) or not isinstance(item.get("label"), str):
            raise ValueError("unsupported model catalog")
        split = _split_effort_variant(item["modelId"], item["label"])
        parsed_items.append((item, split))
        if split is not None:
            base_counts[split[0]] = base_counts.get(split[0], 0) + 1

    rows_by_id: dict[str, dict] = {}
    ordered_ids: list[str] = []
    default_alias = status_payload.get("defaultAliasModel")
    current_model: str | None = None
    current_effort: str | None = None

    for item, split in parsed_items:
        is_default = isinstance(default_alias, str) and item.get("aliasModel") == default_alias
        if split is not None and base_counts.get(split[0], 0) >= 2:
            base_id, base_label, effort = split
            entry = rows_by_id.get(base_id)
            if entry is None:
                entry = {
                    "value": base_id,
                    "displayName": base_label,
                    "supportsEffort": True,
                    "supportedEffortLevels": [],
                }
                rows_by_id[base_id] = entry
                ordered_ids.append(base_id)
            if effort not in entry["supportedEffortLevels"]:
                entry["supportedEffortLevels"].append(effort)
            if is_default:
                current_model, current_effort = base_id, effort
        else:
            model_id = item["modelId"]
            if model_id not in rows_by_id:
                rows_by_id[model_id] = {
                    "value": model_id,
                    "displayName": item["label"],
                    "supportsEffort": False,
                    "supportedEffortLevels": [],
                }
                ordered_ids.append(model_id)
            if is_default:
                current_model, current_effort = model_id, None

    return [rows_by_id[mid] for mid in ordered_ids], current_model, current_effort
