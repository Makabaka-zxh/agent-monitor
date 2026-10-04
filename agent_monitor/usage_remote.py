"""Low-priority numeric usage sync. Never occupies the status heartbeat loop."""
from __future__ import annotations

import threading
from pathlib import Path
from .usage_ledger import UsageLedger, read_claude_quota_file


class RemoteUsageWorker:
    def __init__(self, client, config, state_dir):
        self.client, self.config = client, config
        self.path = Path(state_dir) / "usage.sqlite3"
        self.stop = threading.Event()
        self.thread = None

    def start(self):
        if self.thread is None:
            self.thread = threading.Thread(target=self.run, name="monitor-usage", daemon=True)
            self.thread.start()

    def run(self):
        ledger = UsageLedger(self.path)
        try:
            while not self.stop.is_set():
                try:
                    ledger.scan(budget_bytes=8 * 1024 * 1024)
                    quota = read_claude_quota_file()
                    if quota is not None:
                        ledger.ingest_claude_quota(quota)
                    # Quota freshness must not queue behind the first historical
                    # token backfill. This timestamp-idempotent snapshot has no
                    # accounting cursor and is never acknowledged separately.
                    try:
                        quota_batch = ledger.export_quota_batch(limit=20)
                        if not self.stop.is_set() and quota_batch.get("quotas"):
                            self.client.post("/api/agent/usage", quota_batch, token=self.config.token)
                    except Exception:
                        # A failed optional fast snapshot must not prevent the
                        # normal FIFO batch (or the independent status loop).
                        pass
                    batch = ledger.export_batch(limit=200)
                    if not self.stop.is_set() and (batch.get("events") or batch.get("quotas")):
                        result = self.client.post("/api/agent/usage", batch, token=self.config.token)
                        expected = len(batch.get("events", [])) + len(batch.get("quotas", []))
                        if (type(result.get("accepted")) is int and result["accepted"] == expected
                                and type(result.get("rejected")) is int and result["rejected"] == 0):
                            ledger.ack_export(batch["cursor"])
                except Exception:
                    # Connection errors and an older hub are supplementary;
                    # the status loop handles authentication and connectivity.
                    pass
                self.stop.wait(30)
        finally:
            ledger.close()

    def close(self):
        self.stop.set()
        if self.thread:
            # Transport has bounded deadlines; the ledger is closed only by its
            # owner after its final scan/request, never by the caller mid-write.
            self.thread.join(timeout=35)
