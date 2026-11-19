"""Desktop-only presentation and pairing; no task collection or account credentials.

The widget reads the existing anonymous usage ledger in SQLite read-only mode.
Pairing uses the connector's HTTPS transport and the same phone approval protocol.
Only the verification URL is exposed by the pairing view; secrets stay private.
"""
from __future__ import annotations

from datetime import datetime, timezone
import math
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import threading
import time
from urllib.parse import urlsplit

from . import remote_agent as connector
from .usage_ledger import UsageLedger, _model, _bucket_label
from .usage_statusline import safe_path


class _PairingCancelled(Exception):
    pass


def _save_new_config(config, state_dir, *, cancelled=lambda: False, commit_lock=None):
    """Publish an already complete protected config without replacing an existing one."""
    state_dir = Path(state_dir)
    if not safe_path(state_dir):
        raise connector.RemoteAgentError("连接设置目录不可用")
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    destination = state_dir / connector.CONFIG_FILENAME
    if not safe_path(destination) or destination.exists():
        raise connector.RemoteAgentError("已有连接设置，未覆盖原配对")
    staging = tempfile.TemporaryDirectory(prefix=".desktop-pair-", dir=state_dir)
    committed, cleaned = False, True
    try:
        connector.save_config(config, Path(staging.name))
        # Hard-link publication is atomic and fails if another process paired first.
        # There is deliberately no overwrite/copy fallback.
        with commit_lock or threading.Lock():
            if cancelled():
                raise _PairingCancelled()
            os.link(Path(staging.name) / connector.CONFIG_FILENAME, destination)
            committed = True
    finally:
        try:
            staging.cleanup()
        except OSError:
            if not committed:
                raise
            # Publication already succeeded. A cleanup error must not imply an
            # unpaired computer or encourage another token claim.
            cleaned = False
    return cleaned


class PairingSession:
    """One user-requested QR, bounded by the connector's ten-minute deadline."""
    def __init__(self, state_dir, *, client_factory=connector.HubClient,
                 clock=time.monotonic, save_new=_save_new_config):
        self.state_dir = Path(state_dir)
        self._factory, self._clock, self._save = client_factory, clock, save_new
        self._lock = threading.Lock()
        self._commit_lock = threading.Lock()
        self._cancel = threading.Event()
        self._cleanup_verified = True
        self._secret = None
        self._client = None
        self._request = None
        self._url = None
        self._name = None
        self._deadline = self._next = 0.0
        self._interval = self._delay = 3
        self.status = "idle"

    def view(self):
        with self._lock:
            remaining = max(0, math.ceil(self._deadline - self._clock())) if self.status in {"pending", "retrying"} else 0
            return {"status": self.status, "verification_url": self._url if remaining else None,
                    "seconds_remaining": remaining, "temporary_cleanup_verified": self._cleanup_verified}

    def _finish(self, status):
        self.status = status
        self._secret = self._url = self._request = None

    def cancel(self):
        # Cancellation before publication wins; a completed publication remains
        # approved. Never hold this lock while waiting for the state lock.
        with self._commit_lock:
            self._cancel.set()
        with self._lock:
            if self.status not in {"approved", "expired", "failed", "existing"}:
                self._finish("cancelled")

    def start(self, server, name):
        with self._lock:
            if self.status != "idle":
                raise connector.RemoteAgentError("请先结束当前配对")
            if not safe_path(self.state_dir / connector.CONFIG_FILENAME) or (self.state_dir / connector.CONFIG_FILENAME).exists():
                self._finish("existing")
                return
            server = connector.normalize_server(server)
            if urlsplit(server).scheme != "https":
                raise connector.RemoteAgentError("请使用 HTTPS 服务地址")
            self._name = connector._nonempty_text(name, "电脑别名", 60)
            self._client = self._factory(server)
            self._deadline = self._clock() + connector.QR_PAIRING_SECONDS
            self.status = "starting"
        try:
            result = self._client.post("/api/agent/pairing/start", {"name": self._name, "platform": connector.platform_name()})
            request, secret, interval = result.get("request_id"), result.get("poll_secret"), result.get("interval", 3)
            if (not isinstance(request, str) or not re.fullmatch(connector.QR_ID_PATTERN, request)
                    or not isinstance(secret, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", secret)
                    or type(interval) is not int or not 3 <= interval <= 30
                    or result.get("verification_url") != server + "/#/pairing-confirm/" + request):
                raise connector.PairingRequestError("配对响应无效")
            with self._lock:
                if self._cancel.is_set():
                    self._finish("cancelled")
                elif self._clock() >= self._deadline:
                    self._finish("expired")
                else:
                    self._request, self._secret = request, secret
                    self._url = result["verification_url"]
                    self._interval = self._delay = interval
                    self._next = self._clock() + interval
                    self.status = "pending"
        except Exception:
            with self._lock:
                self._finish("cancelled" if self._cancel.is_set() else "failed")

    def poll(self):
        with self._lock:
            if self._cancel.is_set() or self.status not in {"pending", "retrying"}:
                return
            now = self._clock()
            if now >= self._deadline:
                self._finish("expired")
                return
            if now < self._next:
                return
            request, secret = self._request, self._secret
            self._next = now + self._delay
        try:
            result = self._client.post(f"/api/agent/pairing/{request}/poll", {"poll_secret": secret})
            with self._lock:
                if self._cancel.is_set():
                    self._finish("cancelled")
                    return
                if self._clock() >= self._deadline:
                    self._finish("expired")
                    return
                if result.get("status") == "pending":
                    self.status, self._delay = "pending", self._interval
                    self._next = self._clock() + self._delay
                    return
                if result.get("status") != "approved":
                    raise connector.PairingRequestError("配对未获允许")
                try:
                    config = connector.AgentConfig(server=self._client.server,
                        device_id=connector._nonempty_text(result.get("device_id"), "电脑编号"),
                        token=connector._safe_token(result.get("token")), name=self._name,
                        sync_output=connector._output_setting(result))
                    self._cleanup_verified = self._save(config, self.state_dir,
                        cancelled=self._cancel.is_set, commit_lock=self._commit_lock) is not False
                except _PairingCancelled:
                    self._finish("cancelled")
                    return
                except Exception:
                    raise connector.PairingRequestError("配对信息未保存；原有设置未覆盖") from None
                self._finish("approved")
        except connector.PairingRequestError:
            with self._lock:
                self._finish("failed")
        except connector.RemoteAgentError:
            with self._lock:
                if self._cancel.is_set():
                    self._finish("cancelled")
                else:
                    self._delay = min(connector.MAX_RETRY_DELAY, max(5, self._delay * 2))
                    self._next = self._clock() + self._delay
                    self.status = "retrying"
        except Exception:
            with self._lock:
                self._finish("failed")


def read_usage(path, *, now=None):
    """Reuse summary aggregation without __init__, scans, schema writes or migrations."""
    path = Path(path).absolute()
    if not safe_path(path) or not path.is_file():
        return None
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=0.5)
    try:
        db.execute("PRAGMA query_only=ON")
        db.row_factory = sqlite3.Row
        ledger = object.__new__(UsageLedger)
        ledger.db, ledger.lock = db, threading.RLock()
        return ledger.summary(now=now, device_ids=["local"])
    except (sqlite3.Error, ValueError, TypeError, KeyError):
        return None
    finally:
        db.close()


def _number(value):
    return type(value) in (int, float) and math.isfinite(value)


def present_usage(summary, *, now=None):
    """Only validated public model names and numeric local-source usage reach Tk."""
    now = time.time() if now is None else now
    if not isinstance(summary, dict) or not isinstance(summary.get("providers"), list):
        return []
    cards = []
    for tool in ("codex", "claude", "antigravity"):
        provider = next((p for p in summary["providers"] if isinstance(p, dict) and p.get("tool") == tool), None)
        if provider is None:
            continue
        total = provider.get("today_tokens")
        total = total if type(total) is int and total >= 0 else None
        quotas = []
        for q in provider.get("quotas", []) if isinstance(provider.get("quotas"), list) else []:
            if not isinstance(q, dict) or q.get("source_device_id") not in (None, "local"):
                continue
            used, minutes, reset = q.get("used_percent"), q.get("window_minutes"), q.get("resets_at")
            if (not _number(used) or not 0 <= used <= 100 or type(minutes) is not int or minutes <= 0
                    or q.get("availability") == "expired" or (_number(reset) and reset > 0 and reset <= now)):
                continue
            if reset is not None and (not _number(reset) or reset < 0):
                continue
            model = _model(q.get("model"), tool)
            bucket = _bucket_label(q.get("bucket_label"))
            window = f"{minutes // 1440} 天" if minutes % 1440 == 0 else f"{minutes // 60} 小时" if minutes % 60 == 0 else f"{minutes} 分钟"
            quotas.append({"label": " · ".join(filter(None, [model or bucket, window])),
                           "used_percent": used, "stale": q.get("stale") is True})
        shares = []
        history = provider.get("history", {})
        days = history.get("days", []) if isinstance(history, dict) else []
        day = next((d for d in days if isinstance(d, dict) and d.get("day") == summary.get("day")), None)
        if day and type(day.get("tokens")) is int and day["tokens"] > 0 and isinstance(day.get("models"), list):
            denominator = day["tokens"]
            models = day["models"]
            clean, seen, valid = [], set(), True
            for row in models:
                if not isinstance(row, dict):
                    valid = False
                    break
                model, tokens = _model(row.get("model"), tool), row.get("tokens")
                if model is None or model in seen or type(tokens) is not int or tokens < 0:
                    valid = False
                    break
                seen.add(model)
                clean.append((model, tokens))
            known = sum(tokens for _, tokens in clean)
            unknown = day.get("unknown_model_tokens")
            if valid and clean and type(unknown) is int and unknown >= 0 and known + unknown == denominator:
                shares = [{"label": model, "tokens": tokens, "percent": tokens * 100 / denominator} for model, tokens in clean if tokens]
                if shares and unknown:
                    shares.append({"label": "其他模型", "tokens": unknown, "percent": unknown * 100 / denominator})
        if total is not None or quotas or shares:
            title = "Codex" if tool == "codex" else "Antigravity" if tool == "antigravity" else "Claude Code"
            cards.append({"tool": tool, "title": title,
                          "today_tokens": total, "quotas": quotas, "models": shares})
    return cards


def demo_summary():
    """No real account, config, logs, disk or network in demo mode."""
    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    return {"day": day, "providers": [
        {"tool": "codex", "today_tokens": 126000, "quotas": [
            {"window_minutes": 10080, "used_percent": 28, "source_device_id": "local"}],
         "history": {"days": [{"day": day, "tokens": 126000, "models": [
             {"model": "gpt-5.4", "tokens": 84000}, {"model": "gpt-5.3-codex", "tokens": 35000}], "unknown_model_tokens": 7000}]}},
        {"tool": "claude", "today_tokens": 48000, "quotas": [
            {"window_minutes": 300, "used_percent": 0, "source_device_id": "local"},
            {"window_minutes": 10080, "used_percent": 42, "model": "claude-sonnet-4-6", "source_device_id": "local"}],
         "history": {"days": [{"day": day, "tokens": 48000, "models": [
             {"model": "claude-sonnet-4-6", "tokens": 48000}], "unknown_model_tokens": 0}]}}]}
