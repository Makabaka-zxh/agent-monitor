"""Small background reply channel; only the hub's own typed task requests."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import threading
import time

ID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
TERMINAL = {'succeeded', 'failed', 'blocked', 'uncertain'}
MAX_LEDGER = 50_000


def _http_status(error):
    seen = set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        status = getattr(error, 'status', None) or getattr(error, 'code', None)
        if isinstance(status, int):
            return status
        error = error.__cause__
    return None


class RemoteReplyWorker:
    def __init__(self, client, config, state_dir, *, executor_factory=None, tool_finder=None):
        self.client, self.config = client, config
        self.path = Path(state_dir) / 'reply-ledger.json'
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.thread = None
        self.update_cursor = 0
        self.closed_executor = False
        self.ledger = {}
        if self.path.exists():
            value = json.loads(self.path.read_text(encoding='utf-8'))
            if not isinstance(value, dict) or len(value) > MAX_LEDGER:
                raise ValueError('Invalid reply ledger')
            for key, item in value.items():
                if not ID.fullmatch(key) or not isinstance(item, dict):
                    raise ValueError('Invalid reply ledger')
                self.ledger[key] = item
                if item.get('state') in {'received', 'dispatching', 'running'}:
                    item.update(state='uncertain', error_code='restarted', pending=True)
        if executor_factory is None or tool_finder is None:
            from .local_executor import LocalReplyExecutor, supported_tools
            executor_factory = executor_factory or LocalReplyExecutor
            tool_finder = tool_finder or supported_tools
        self.tool_finder = tool_finder
        self.executor = executor_factory(emit=self._emit)

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        with open(temporary, 'w', encoding='utf-8') as file:
            json.dump(self.ledger, file)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, self.path)

    def _emit(self, event):
        command_id = event.get('command_id', event.get('id', ''))
        with self.lock:
            item = self.ledger.get(command_id)
            if not item:
                return
            if item.get('state') in TERMINAL or event.get('state') not in {'running', *TERMINAL}:
                return
            item.update(state=event['state'], error_code=event.get('error_code', ''), pending=True, updated=time.time())
            self._save()

    def _post(self, endpoint, payload):
        try:
            return self.client.post(endpoint, payload, token=self.config.token)
        except Exception as error:
            from .remote_agent import UnauthorizedError
            if isinstance(error, UnauthorizedError) or _http_status(error) == 401:
                self._revoke()
            raise

    def _close_executor(self):
        with self.lock:
            if self.closed_executor:
                return
            self.closed_executor = True
        self.executor.close()

    def _revoke(self):
        self.stop.set()
        try:
            with self.lock:
                for item in self.ledger.values():
                    if item.get('state') in {'received', 'dispatching', 'running'}:
                        item.update(state='uncertain', error_code='revoked', pending=True, updated=time.time())
                self._save()
        finally:
            self._close_executor()

    def _flush_one(self):
        # One failing old acknowledgement cannot starve polling or newer updates.
        with self.lock:
            updates = [(key, dict(value)) for key, value in self.ledger.items() if value.get('pending')]
            if not updates:
                return
            key, item = updates[self.update_cursor % len(updates)]
            self.update_cursor += 1
        try:
            response = self._post('/api/agent/replies/update', {'id': key, 'state': item['state'],
                                  'error_code': item.get('error_code', '')})
        except Exception as error:
            if self.stop.is_set():
                raise
            if _http_status(error) not in {404, 410}:
                return
            # This record was retired (or belongs to a previous pairing). Keep
            # the local dedup tombstone but do not retry an impossible update.
            with self.lock:
                if self.ledger.get(key) == item:
                    self.ledger[key]['pending'] = False
                    self.ledger[key]['retired'] = True
                    self._save()
            self.executor.cancel(key)
            return
        with self.lock:
            if self.ledger.get(key) == item:
                self.ledger[key]['pending'] = False
                self._save()
        if item['state'] == 'running' and response.get('state') != 'running':
            self.executor.cancel(key)

    def _tick(self):
        if self.stop.is_set():
            return
        # Poll cancellation before old bookkeeping. Slow stale updates cannot
        # prevent this channel from noticing revocation on the following tick.
        result = self._post('/api/agent/replies/poll', {'supported_tools': self.tool_finder()})
        canceled = result.get('cancel_ids', [])
        if not isinstance(canceled, list):
            canceled = []
        for command_id in canceled[:100]:
            if isinstance(command_id, str) and ID.fullmatch(command_id):
                self.executor.cancel(command_id)
        command = result.get('command')
        if isinstance(command, dict):
            self._receive(command, canceled)
        if not self.stop.is_set():
            self._flush_one()

    def _receive(self, command, canceled):
        command_id = command.get('id', '')
        if not isinstance(command_id, str) or not ID.fullmatch(command_id):
            return
        with self.lock:
            # Durable receipt precedes launch. A duplicate never invokes the CLI again.
            if command_id in self.ledger:
                self.ledger[command_id]['pending'] = True
                self._save()
                return
            # Match the hub's permanent id tombstones. Refuse new receipts at
            # capacity instead of making an old command executable again.
            if len(self.ledger) >= MAX_LEDGER:
                return
            self.ledger[command_id] = {'state': 'received', 'pending': False, 'updated': time.time()}
            self._save()
        source = command.get('source_id')
        prompt = command.get('text')
        if not isinstance(source, str) or not isinstance(prompt, str) or not 1 <= len(prompt) <= 8000:
            self._emit({'command_id': command_id, 'state': 'blocked'})
            return
        if command_id in canceled or self.stop.is_set():
            self._emit({'command_id': command_id, 'state': 'blocked', 'error_code': 'revoked'})
            return
        try:
            acknowledgement = self._post('/api/agent/replies/update', {'id': command_id, 'state': 'running', 'error_code': ''})
            # The hub may have revoked or timed out this dispatch while its
            # response travelled. An unknown/lost ACK is never permission to run.
            if acknowledgement.get('state') != 'running' or self.stop.is_set():
                state = acknowledgement.get('state')
                self._emit({'command_id': command_id, 'state': state if state in TERMINAL else 'uncertain'})
                return
            with self.lock:
                if self.stop.is_set() or self.ledger[command_id]['state'] != 'received':
                    return
                self.ledger[command_id].update(state='running', pending=False, updated=time.time())
                self._save()
        except Exception:
            self._emit({'command_id': command_id, 'state': 'uncertain'})
            if self.stop.is_set():
                raise
            return
        if self.stop.is_set():
            return
        try:
            outcome = self.executor.start(command_id, source, prompt)
        except Exception:
            self._emit({'command_id': command_id, 'state': 'uncertain'})
            return
        if isinstance(outcome, dict) and outcome.get('state') in {'succeeded', 'failed', 'blocked', 'uncertain'}:
            self._emit(outcome)

    def _run(self):
        while not self.stop.is_set():
            try:
                self._tick()
                delay = 1
            except Exception:
                # Never emit raw network/tool exceptions, prompts or token values.
                delay = 5
            self.stop.wait(delay)

    def start(self):
        if self.thread is None:
            self.thread = threading.Thread(target=self._run, name='monitor-replies', daemon=True)
            self.thread.start()

    def close(self):
        self.stop.set()
        self._close_executor()
        if self.thread and self.thread is not threading.current_thread():
            self.thread.join(timeout=12)
