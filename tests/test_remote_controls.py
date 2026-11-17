"""Remote control wire tests: a fake hub, no network or provider execution."""
import json
import threading
import time
from types import SimpleNamespace

from agent_monitor.remote_replies import RemoteReplyWorker
from agent_monitor.model_capabilities import normalize_catalog

ID = '11111111-1111-4111-8111-111111111111'
SOURCE = 'codex:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'


class Executor:
    def __init__(self, emit): self.starts = []; self.cancelled = []; self.closed = False
    def start(self, *args, **kwargs): self.starts.append((args, kwargs)); return {'state': 'running'}
    def cancel(self, value): self.cancelled.append(value)
    def close(self): self.closed = True


class Hub:
    def __init__(self): self.calls = []; self.state = 'submitted'; self.catalog_event = threading.Event()
    def post(self, endpoint, payload, **kwargs):
        self.calls.append((endpoint, payload))
        if endpoint == '/api/agent/controls/poll':
            return {'state': self.state, 'decision': {'decision': 'allow_once', 'decision_id': 'decision1'}}
        if endpoint == '/api/agent/controls/catalog': self.catalog_event.set(); return {}
        return {'state': 'running'}


def worker(tmp_path, hub, catalog_provider=None):
    return RemoteReplyWorker(hub, SimpleNamespace(token='synthetic'), tmp_path,
        executor_factory=Executor, tool_finder=lambda: ['codex'], catalog_provider=catalog_provider)


def test_selection_survives_dispatch_and_changed_id_never_relaunches(tmp_path):
    hub = Hub(); client = worker(tmp_path, hub)
    selection = {'provider': 'codex', 'catalog_revision': 'catalog1', 'model': 'synthetic-model', 'effort': 'high', 'apply_at': 'next_message'}
    command = {'id': ID, 'source_id': SOURCE, 'text': 'synthetic prompt', 'selection': selection}
    client._receive(command, [])
    assert client.executor.starts[0][1]['selection'] == selection
    client._receive({**command, 'selection': {**selection, 'effort': 'low'}}, [])
    assert len(client.executor.starts) == 1
    assert client.ledger[ID]['changed_duplicate_rejected'] is True
    assert 'synthetic prompt' not in client.path.read_text()
    client.close()


def test_bad_selection_blocks_before_running_ack(tmp_path):
    hub = Hub(); client = worker(tmp_path, hub)
    client._receive({'id': ID, 'source_id': SOURCE, 'text': 'synthetic', 'selection': {'arbitrary': True}}, [])
    assert client.executor.starts == [] and hub.calls == []
    client.close()


def test_request_poll_and_forwarded_receipt_stay_bound(tmp_path):
    hub = Hub(); client = worker(tmp_path, hub)
    client.ledger[ID] = {'state': 'running'}
    request = {'id': 'request1', 'revision': 'revision1', 'command_id': ID, 'source_id': SOURCE,
               'session_id': SOURCE.split(':')[1], 'provider': 'codex', 'turn_id': 'turn1',
               'kind': 'approval', 'expires_at': time.time()+10}
    assert client.interactions(request, threading.Event())['decision'] == 'allow_once'
    client.interactions.resolve('request1', 'revision1', 'forwarded')
    assert [p for p, _ in hub.calls] == ['/api/agent/controls/request', '/api/agent/controls/poll', '/api/agent/controls/resolve']
    submitted = hub.calls[0][1]
    assert submitted['command_id'] == ID and submitted['source_id'] == SOURCE
    assert 'command_id' not in submitted['request']
    assert hub.calls[-1][1]['state'] == 'forwarded'
    client.close()


def test_source_cancelled_or_expired_never_uses_old_decision(tmp_path):
    hub = Hub(); client = worker(tmp_path, hub)
    client.ledger[ID] = {'state': 'running'}
    request = {'id': 'request1', 'revision': 'revision1', 'command_id': ID, 'source_id': SOURCE, 'expires_at': time.time()+10}
    cancelled = threading.Event(); cancelled.set()
    assert client.interactions(request, cancelled) is None and hub.calls == []
    hub.state = 'cancelled'
    assert client.interactions(request, threading.Event()) is None
    client.close()


def test_catalog_discovery_on_demand_only_and_anonymous_payload(tmp_path):
    hub = Hub(); seen = []
    catalog = normalize_catalog('codex', [{'model': 'synthetic-model', 'displayName': 'Synthetic', 'supportedReasoningEfforts': []}])
    def discover(source): seen.append(source); return catalog
    client = worker(tmp_path, hub, discover)
    client._request_catalogs([SOURCE])
    assert hub.catalog_event.wait(2)
    assert seen == [SOURCE]
    assert hub.calls[0] == ('/api/agent/controls/catalog', {'source_id': SOURCE, 'catalog': catalog})
    assert client.executor.starts == []
    client.close()
