"""Bounded usage work, separate from the five-second task status collector."""
from __future__ import annotations

import asyncio
import copy
import json
import logging
import os
import re
import stat
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from .usage_claude_cli import ClaudeCliQuotaReader
from .usage_ledger import SHANGHAI, UsageLedger, read_claude_quota_file
from .usage_statusline import atomic_write, decode, safe_path


_CLAUDE_QUOTA_STATUSES = frozenset({
    "ok", "disabled", "busy", "throttled", "invalid_pin", "unsafe_executable",
    "executable_unavailable", "pin_mismatch", "version_mismatch", "timeout",
    "launch_failed", "containment_failed", "output_limit", "read_failed", "runner_failed",
    "invalid_runner_result", "auth_unavailable", "not_logged_in", "api_key_only",
    "unsupported_provider", "unsupported_account", "official_command_failed",
    "local_execution_unverified", "no_usage_report", "ambiguous_reports", "invalid_output",
    "invalid_observation_time", "no_live_limits", "invalid_report", "ambiguous_windows",
    "no_numeric_windows", "reader_failed", "ingest_failed", "refresh_failed",
})
_CLAUDE_QUOTA_PHASES = frozenset({"pin", "version", "auth", "usage", "complete"})


def _claude_quota_reader(state_dir, enabled):
    """Only this dedicated, explicit local config can opt in to the pinned CLI."""
    if enabled is not True:
        return None
    path = Path(state_dir) / "claude-quota-cli.json"
    try:
        if not safe_path(path):
            return None
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_size > 4096:
            return None
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as handle:
            opened = os.fstat(handle.fileno())
            if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or not safe_path(path):
                return None
            raw = handle.read(4097)
        if len(raw) > 4096:
            return None
        data = decode(raw)
        if not isinstance(data, dict) or set(data) != {"enabled", "executable", "expected_sha256"} or data["enabled"] is not True:
            return None
        executable, expected = data["executable"], data["expected_sha256"]
        if (not isinstance(executable, str) or not executable or len(executable) > 2048
                or any(ord(char) < 32 for char in executable) or not Path(executable).is_absolute()
                or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected)):
            return None
        return ClaudeCliQuotaReader(Path(executable), expected.lower(), enabled=True)
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        return None  # No inferred path, credentials, fallback executable or raw diagnostics.


class UsageService:
    def __init__(self, state_dir, store, enabled):
        self.ledger = UsageLedger(Path(state_dir) / "usage.sqlite3")
        self.store, self.enabled = store, enabled
        self.lock = threading.Lock()
        self._refresh_revision = 0
        # No ledger aggregation or task reads before the server can listen.
        # Incomplete history must not replace a client's previous full chart.
        self.cached = {"day": datetime.now(SHANGHAI).date().isoformat(), "timezone": "Asia/Shanghai",
                       "coverage": "unavailable", "providers": [], "scan": {}, "source": "local_logs",
                       "semantics": "observed_tokens_including_cache", "loading": True,
                       "history_included": False}
        self.tasks = {}
        # Public check metadata is independent of both numeric observations and
        # the private diagnostic file. Bind it only to a published local source.
        self._quota_source = None
        self._quota_source_identity = None
        self._quota_source_seen = False
        self._quota_source_generation = 0
        self._claude_check = None
        self.claude_quota_reader = _claude_quota_reader(state_dir, enabled)
        self._claude_status_path = Path(state_dir) / "claude-quota-status.json"
        # A potentially slow CLI never occupies the collector/API's executor.
        self._claude_executor = (ThreadPoolExecutor(max_workers=1, thread_name_prefix="monitor-claude-quota")
                                 if self.claude_quota_reader is not None else None)

    def refresh(self):
        # Compute without holding the cache lock. Some callers already hold
        # Store.lock (for example source removal), so a second long-lived
        # refresh mutex around Store.snapshot would invert those locks.
        with self.lock:
            self._refresh_revision += 1
            revision = self._refresh_revision
        snapshot = self.store.snapshot()
        names = {d["id"]: d["name"] for d in snapshot["devices"]}
        local_device = next((d for d in snapshot["devices"] if d.get("local")), None)
        local = local_device["id"] if local_device else "local"
        check_source = ({"source_device_id": local_device["id"], "source_name": local_device["name"]}
                        if local_device else None)
        with self.lock:
            if revision == self._refresh_revision:
                identity = local_device["id"] if local_device else None
                if self._quota_source_seen and identity != self._quota_source_identity:
                    self._quota_source_generation += 1
                    self._claude_check = None
                    # Source revocation must also take effect if the following
                    # aggregation fails; an earlier refresh cannot restore it.
                    self._quota_source = None
                self._quota_source_identity = identity
                self._quota_source_seen = self._quota_source_seen or local_device is not None
        summary = self.ledger.summary(device_ids=[*names, "local"])
        for provider in summary.get("providers", []):
            for quota in provider.get("quotas", []):
                source = quota.get("source_device_id")
                if not source or source == "local":
                    quota["source_device_id"] = local
                    source = local
                quota["source_name"] = names.get(source, "电脑")
        tasks = {}
        for task in snapshot["tasks"]:
            source_id = task.get("source_id") or task["id"][len(task["device_id"]) + 1:]
            tasks[task["id"]] = self.ledger.task_usage(task["tool"], source_id,
                device_id="local" if task["device_id"] == local else task["device_id"])
        with self.lock:
            # A later refresh, including one that failed, invalidates earlier
            # in-flight builds. Never reintroduce a removed source or replace
            # a newer cache with a late result from another worker.
            if revision == self._refresh_revision:
                summary.update(loading=False, history_included=True)
                self.cached, self.tasks = summary, tasks
                self._quota_source = check_source

    def _refresh_safely(self):
        try:
            self.refresh()
        except Exception as exc:
            logging.getLogger("agent_monitor").warning("Usage refresh unavailable (%s)", type(exc).__name__)

    def collect(self):
        try:
            if self.enabled:
                self.ledger.scan(budget_bytes=8 * 1024 * 1024)
                quota = read_claude_quota_file()
                if quota is not None:
                    self.ledger.ingest_claude_quota(quota)
        except Exception as exc:
            logging.getLogger("agent_monitor").warning("Usage collection unavailable (%s)", type(exc).__name__)
        # A failed log scan must not hide an existing persisted ledger.
        self._refresh_safely()

    async def run(self):
        initial = True
        while True:
            # Publish persisted statistics before spending time on the first
            # history scan. Both phases run off the event loop.
            operation = self._refresh_safely if initial else self.collect
            worker = asyncio.get_running_loop().run_in_executor(None, operation)
            cancelled = False
            while True:
                try:
                    await asyncio.shield(worker)
                except asyncio.CancelledError:
                    cancelled = True
                    if worker.cancelled():
                        raise
                    continue
                break
            if cancelled:
                raise asyncio.CancelledError
            if initial:
                initial = False
            else:
                await asyncio.sleep(30)

    def _record_claude_status(self, status, retry, accepted, completed_at=None):
        if not self.enabled or self.claude_quota_reader is None:
            return
        try:
            phase = getattr(self.claude_quota_reader, "last_phase", None)
            value = {"status": status if isinstance(status, str) and status in _CLAUDE_QUOTA_STATUSES else "reader_failed",
                     "phase": phase if isinstance(phase, str) and phase in _CLAUDE_QUOTA_PHASES else "unknown",
                     "completed_at": completed_at or datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                     "retry_after_seconds": min(3600, max(300, retry)) if type(retry) is int else 300,
                     "accepted": accepted if type(accepted) is int and 0 <= accepted <= 2 else 0}
            atomic_write(self._claude_status_path, json.dumps(value, allow_nan=False, separators=(",", ":")).encode("ascii"))
        except Exception:
            # Local diagnostics are best effort, never part of quota ingestion.
            pass

    def _collect_claude_quota(self):
        if not self.enabled or self.claude_quota_reader is None:
            return 300
        with self.lock:
            source_generation = self._quota_source_generation
        stage, accepted_count, completed_at = "reader_failed", 0, None
        try:
            result = self.claude_quota_reader.poll()
            completed_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            delay = result.retry_after_seconds
            delay = min(3600, max(300, delay)) if type(delay) is int else 300
            if result.status == "ok" and isinstance(result.snapshot, dict):
                stage = "ingest_failed"
                accepted = self.ledger.ingest_claude_quota(result.snapshot)
                accepted_count = accepted.get("accepted", 0)
            # Record before summary rebuilding, so a slow refresh cannot hide
            # whether the bounded CLI attempt completed and stored readings.
            self._record_claude_check(result.status, delay, accepted_count, completed_at, source_generation)
            self._record_claude_status(result.status, delay, accepted_count, completed_at)
            stage = "refresh_failed"
            if accepted_count > 0:
                self.refresh()
            return delay
        except Exception as exc:
            self._record_claude_check(stage, 300, accepted_count, completed_at, source_generation)
            self._record_claude_status(stage, 300, accepted_count, completed_at)
            # Never log CLI output, local paths or provider authentication data.
            logging.getLogger("agent_monitor").warning("Claude quota collection unavailable (%s)", type(exc).__name__)
            return 300

    def _record_claude_check(self, status, retry, accepted, completed_at, source_generation):
        # A skipped poll is not a new attempt. Never promote raw diagnostics to
        # the API, including unrecognized strings or malformed result types.
        if isinstance(status, str) and status in {"busy", "throttled", "disabled"}:
            return
        state = "unavailable"
        if isinstance(status, str):
            if status == "ok" and type(accepted) is int and 0 < accepted <= 2:
                state = "updated"
            elif status in {"no_live_limits", "no_numeric_windows"}:
                state = "no_live_data"
            elif status in {"not_logged_in", "api_key_only"}:
                state = "sign_in_required"
            elif status in {"invalid_pin", "pin_mismatch", "version_mismatch", "executable_unavailable"}:
                state = "update_required"
        value = {"state": state,
                 "last_attempt_at": completed_at or datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                 "retry_after_seconds": min(3600, max(300, retry)) if type(retry) is int else 300}
        with self.lock:
            if source_generation == self._quota_source_generation:
                self._claude_check = value

    async def run_claude_quota(self):
        while self.enabled and self.claude_quota_reader is not None:
            worker = asyncio.get_running_loop().run_in_executor(self._claude_executor, self._collect_claude_quota)
            cancelled = False
            while True:
                try:
                    delay = await asyncio.shield(worker)
                except asyncio.CancelledError:
                    # Cancelling the coroutine cannot stop its polling thread.
                    # Drain the reader's bounded attempt before ledger shutdown.
                    cancelled = True
                    if worker.cancelled():
                        raise
                    continue
                break
            if cancelled:
                raise asyncio.CancelledError
            await asyncio.sleep(delay)

    def summary(self):
        with self.lock:
            return copy.deepcopy(self.cached)

    def detail_summary(self):
        """Full-authority usage screen only; no private diagnostic-file reads."""
        with self.lock:
            result = copy.deepcopy(self.cached)
            result["quota_checks"] = []
            if self.enabled and self.claude_quota_reader is not None and self._quota_source is not None:
                check = self._claude_check or {"state": "waiting", "last_attempt_at": None,
                                                "retry_after_seconds": 300}
                result["quota_checks"].append({"tool": "claude", **self._quota_source, **check})
            return result

    def enrich(self, snapshot):
        with self.lock:
            # History belongs to the dedicated usage screen. Keep frequent
            # workspace/live snapshots small, including for older clients.
            compact = {key: value for key, value in self.cached.items() if key != "providers"}
            compact["providers"] = [{key: value for key, value in provider.items() if key != "history"}
                                    for provider in self.cached.get("providers", [])]
            compact["history_included"] = False
            snapshot["usage"] = copy.deepcopy(compact)
            for task in snapshot.get("tasks", []):
                if task["id"] in self.tasks:
                    task["usage"] = copy.deepcopy(self.tasks[task["id"]])
        return snapshot

    def close(self):
        if self._claude_executor is not None:
            self._claude_executor.shutdown(wait=True)
        self.ledger.close()
