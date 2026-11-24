"""Small background reply channel; only the hub's own typed task requests."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import threading
import time

from .session_controls import ModelSelection, reply_fingerprint


class _RemoteInteractions:
    """A decision travels only back to the original owned executor callback."""
    supports_adoption_upgrade = True
    def __init__(self, worker): self.worker = worker

    def __call__(self, request, cancelled):
        worker = self.worker
        command_id, source_id = request.get('command_id'), request.get('source_id')
        payload = {k: v for k, v in request.items() if k not in {'command_id', 'source_id'}}
        with worker.lock:
            entry = worker.ledger.get(command_id, {})
            if entry.get('state') != 'running': return None
        if cancelled.is_set() or worker.stop.is_set(): return None
        worker._post('/api/agent/controls/request', {'command_id': command_id, 'source_id': source_id, 'request': payload})
        while not cancelled.is_set() and not worker.stop.is_set() and time.time() < request['expires_at']:
            response = worker._post('/api/agent/controls/poll', {'request_id': request['id'], 'revision': request['revision']})
            decision = response.get('decision')
            if isinstance(decision, dict) and response.get('state') == 'submitted': return decision
            if response.get('state') not in {'pending', 'submitted'}: return None
            cancelled.wait(.5)
        return None

    def resolve(self, request_id, revision, state, *, outcome=""):
        payload = {'request_id': request_id, 'revision': revision, 'state': state}
        if outcome:
            payload['outcome'] = outcome
        self.worker._post('/api/agent/controls/resolve', payload)

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
    def __init__(self, client, config, state_dir, *, executor_factory=None, tool_finder=None, catalog_provider=None):
        self.client, self.config = client, config
        self.path = Path(state_dir) / 'reply-ledger.json'
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.thread = None
        self.update_cursor = 0
        self.closed_executor = False
        self.ledger = {}
        default_executor = executor_factory is None
        self.catalog_pending = set()
        self.catalog_threads = set()
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
        if catalog_provider is None:
            from .local_executor import discover_reply_catalog
            catalog_provider = lambda source: discover_reply_catalog(source, cancelled=self.stop)
        self.catalog_provider = catalog_provider
        self.interactions = _RemoteInteractions(self)
        self.executor = executor_factory(emit=self._emit, interaction_handler=self.interactions,
            catalog_provider=catalog_provider, report_applied=True) if default_executor else executor_factory(emit=self._emit)
        if hasattr(self.executor, 'report_applied'):
            self.executor.report_applied = True

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
            if isinstance(event.get('applied'), dict):
                item['applied'] = dict(event['applied'])
            if isinstance(event.get('created_source_id'), str) and event['created_source_id']:
                item['created_source_id'] = event['created_source_id']
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
            update_payload = {'id': key, 'state': item['state'], 'error_code': item.get('error_code', '')}
            if isinstance(item.get('applied'), dict):
                update_payload['applied'] = item['applied']
            if isinstance(item.get('created_source_id'), str) and item['created_source_id']:
                update_payload['created_source_id'] = item['created_source_id']
            response = self._post('/api/agent/replies/update', update_payload)
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
        self._request_catalogs(result.get('catalog_requests', []))
        if not self.stop.is_set():
            self._flush_one()

    def _receive(self, command, canceled):
        command_id = command.get('id', '')
        if not isinstance(command_id, str) or not ID.fullmatch(command_id):
            return
        selection = command.get('selection')
        try:
            chosen = ModelSelection.model_validate(selection) if selection is not None else None
            fingerprint = reply_fingerprint(command.get('text'), chosen)
        except (ValueError, TypeError):
            fingerprint, chosen = None, None
        with self.lock:
            # Durable receipt precedes launch. A duplicate never invokes the CLI again.
            if command_id in self.ledger:
                old = self.ledger[command_id]
                if old.get('fingerprint') and (old['fingerprint'] != fingerprint or old.get('source_id') != command.get('source_id')):
                    old['changed_duplicate_rejected'] = True
                self.ledger[command_id]['pending'] = True
                self._save()
                return
            # Match the hub's permanent id tombstones. Refuse new receipts at
            # capacity instead of making an old command executable again.
            if len(self.ledger) >= MAX_LEDGER:
                return
            self.ledger[command_id] = {'state': 'received', 'pending': False, 'updated': time.time(),
                                      'fingerprint': fingerprint, 'source_id': command.get('source_id')}
            self._save()
        source = command.get('source_id')
        prompt = command.get('text')
        if fingerprint is None or not isinstance(source, str) or not isinstance(prompt, str) or not 1 <= len(prompt) <= 8000:
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
            outcome = (self.executor.start(command_id, source, prompt, selection=chosen.model_dump()) if chosen else
                       self.executor.start(command_id, source, prompt))
        except Exception:
            self._emit({'command_id': command_id, 'state': 'uncertain'})
            return
        if isinstance(outcome, dict) and outcome.get('state') in {'succeeded', 'failed', 'blocked', 'uncertain'}:
            self._emit(outcome)

    def _request_catalogs(self, requests):
        if not isinstance(requests, list) or self.stop.is_set(): return
        for source_id in requests[:4]:
            if not isinstance(source_id, str) or not re.fullmatch(r'(codex|claude):[0-9a-f-]{36}', source_id): continue
            with self.lock:
                if source_id in self.catalog_pending or len(self.catalog_pending) >= 4: continue
                self.catalog_pending.add(source_id)
            def discover(source=source_id):
                try:
                    catalog = self.catalog_provider(source)
                    if not self.stop.is_set():
                        self._post('/api/agent/controls/catalog', {'source_id': source, 'catalog': catalog})
                except Exception:
                    pass  # No provider payload, account field or exception enters the ledger.
                finally:
                    with self.lock:
                        self.catalog_pending.discard(source)
                        self.catalog_threads.discard(threading.current_thread())
            thread = threading.Thread(target=discover, name='monitor-catalog', daemon=True)
            with self.lock: self.catalog_threads.add(thread)
            thread.start()

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
        deadline = time.monotonic() + 5
        with self.lock: catalogs = list(self.catalog_threads)
        for thread in catalogs:
            if thread is not threading.current_thread(): thread.join(timeout=max(0, deadline-time.monotonic()))
