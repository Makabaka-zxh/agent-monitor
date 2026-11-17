"""Provider-owned protocol regression tests, entirely synthetic child pipes."""
import json
from pathlib import Path
import threading
import time

import pytest

from agent_monitor import local_executor as le
from agent_monitor.model_capabilities import normalize_catalog
from test_local_executor import Child, Input, SESSION, target

CODEX_MODELS = [{'model': 'synthetic-model', 'displayName': 'Synthetic model',
                 'supportedReasoningEfforts': [{'reasoningEffort': 'high'}]}]
CLAUDE_MODELS = [{'value': 'synthetic-model', 'displayName': 'Synthetic model',
                  'supportsEffort': True, 'supportedEffortLevels': ['high']}]


class StreamInput(Input):
    def write(self, value):
        data = bytes(value)
        self.bytes.extend(data)
        self.pending.extend(data)
        while b'\n' in self.pending:
            line, _, tail = self.pending.partition(b'\n')
            self.pending[:] = tail
            self.owner.receive(json.loads(line))
        return len(data)
    def close(self): self.closed = True


class InteractiveChild(Child):
    def __init__(self, argv, cwd, *, scenario='approval'):
        super().__init__(argv, cwd)
        self.scenario = scenario
        self.proc.stdin = StreamInput(self)

    def finish(self):
        if self.tool == 'codex':
            turn = {'id': 'turn-1', 'status': 'completed', 'error': None}
            if self.scenario == 'modern-local' and any(
                    r.get('id') == 'server-1' and r.get('result') == {'decision': 'cancel'} for r in self.requests):
                turn['status'] = 'interrupted'
            if self.scenario == 'quota':
                turn.update(status='failed', error={'codexErrorInfo': 'UsageLimitExceeded', 'message': 'PRIVATE ERROR'})
            if self.scenario == 'unknown-final':
                self.proc.stdout.put({'method': 'error', 'params': {'threadId': SESSION, 'turnId': 'turn-1',
                    'error': {'codexErrorInfo': 'UsageLimitExceeded'}}})
                turn.update(status='failed', error={'codexErrorInfo': 'UnknownFutureCode', 'message': 'PRIVATE ERROR'})
            self.proc.stdout.put({'method': 'turn/completed', 'params': {'threadId': SESSION, 'turn': turn}})
        else:
            if self.scenario == 'unknown-final':
                self.proc.stdout.put({'type': 'assistant', 'session_id': SESSION, 'error': 'rate_limit'})
                self.proc.stdout.put({'type': 'assistant', 'session_id': SESSION, 'error': 'unknown_future_code'})
                self.proc.stdout.put({'type': 'result', 'session_id': SESSION, 'subtype': 'success', 'is_error': True})
                return
            self.proc.stdout.put({'type': 'result', 'session_id': SESSION, 'subtype': 'success', 'is_error': False})

    def offer(self):
        if self.scenario in {'success', 'quota', 'unknown-final'}: self.finish(); return
        if self.tool == 'codex':
            params = {'threadId': SESSION, 'turnId': 'turn-1', 'itemId': 'i1', 'command': 'synthetic-operation', 'cwd': str(self.cwd),
                      'availableDecisions': ['accept', 'decline']}
            if self.scenario == 'foreign': params['turnId'] = 'foreign'
            if self.scenario == 'permissions': params['additionalPermissions'] = {'network': {'enabled': True}}
            if self.scenario.startswith('modern-'):
                proposal = ['synthetic-private-rule']
                params.update(environmentId='local', kind='command', startedAtMs=100,
                              proposedExecpolicyAmendment=proposal, availableDecisions=[
                    'accept', 'cancel', {'acceptWithExecpolicyAmendment': {'execpolicy_amendment': proposal}}])
                if self.scenario == 'modern-foreign-item': params['environmentId'] = 'remote'
                if self.scenario == 'modern-foreign-cwd': params['cwd'] = str(self.cwd.parent)
                if self.scenario == 'modern-missing-item-env': params.pop('environmentId')
            method = 'item/commandExecution/requestApproval'
            if self.scenario in {'file-change', 'file-foreign'}:
                self.proc.stdout.put({'method': 'item/started', 'params': {'threadId': SESSION,
                    'turnId': 'turn-1' if self.scenario == 'file-change' else 'foreign',
                    'item': {'id': 'i1', 'type': 'fileChange', 'changes': [
                        {'path': '/synthetic', 'kind': {'type': 'update'}, 'diff': 'synthetic diff'}]}}})
                method = 'item/fileChange/requestApproval'
                params = {'threadId': SESSION, 'turnId': 'turn-1', 'itemId': 'i1'}
            self.proc.stdout.put({'id': 'server-1', 'method': method, 'params': params})
            if self.scenario in {'cancel-race', 'early-cancel'}:
                self.proc.stdout.put({'method': 'serverRequest/resolved', 'params': {'threadId': SESSION, 'requestId': 'server-1'}})
                self.finish()
        else:
            self.proc.stdout.put({'type': 'control_request', 'request_id': 'server-1', 'request': {
                'subtype': 'can_use_tool', 'tool_name': 'SyntheticTool', 'tool_use_id': 'tool-1', 'input': {'path': 'synthetic'}}})
            if self.scenario == 'cancel-race':
                self.proc.stdout.put({'type': 'control_cancel_request', 'request_id': 'server-1'})
                self.finish()

    def receive(self, message):
        method = message.get('method')
        if method == 'thread/resume' and self.scenario.startswith('modern-'):
            self.requests.append(message)
            path = str(self.cwd)
            environment = {'environmentId': 'local', 'cwd': path, 'runtimeWorkspaceRoots': [path]}
            environments = [environment]
            if self.scenario == 'modern-foreign-session': environment['environmentId'] = 'remote'
            if self.scenario == 'modern-foreign-root': environment['runtimeWorkspaceRoots'] = [str(Path(path).parent)]
            if self.scenario == 'modern-multi': environments.append({**environment, 'environmentId': 'remote'})
            if self.scenario == 'modern-no-proven-session': environments = None
            result = {'thread': {'id': SESSION, 'status': {'type': 'idle'}, 'environments': environments},
                      'cwd': path, 'activePermissionProfile': {'id': self.profile}, 'approvalPolicy': 'untrusted',
                      'approvalsReviewer': 'user', 'sandbox': {'type': 'workspaceWrite', 'networkAccess': False}}
            self.proc.stdout.put({'id': 2, 'result': result})
            return
        if self.tool == 'codex' and method in {'initialize', 'initialized', 'thread/resume'}:
            return super().receive(message)
        self.requests.append(message)
        if method == 'model/list':
            self.proc.stdout.put({'id': message['id'], 'result': {'data': CODEX_MODELS, 'nextCursor': None}})
        elif method == 'collaborationMode/list':
            self.proc.stdout.put({'id': message['id'], 'error': {'code': -32601, 'message': 'unsupported'}})
        elif method == 'turn/start':
            if self.scenario == 'early-cancel': self.offer()
            self.proc.stdout.put({'id': 3, 'result': {'turn': {'id': 'turn-1', 'status': 'inProgress'}}})
            if self.scenario != 'early-cancel': self.offer()
        elif message.get('id') == 'server-1':
            if self.scenario == 'duplicate' and message.get('result', {}).get('decision') == 'accept':
                self.scenario = 'approval'
                self.offer()
            else: self.finish()
        elif message.get('type') == 'control_request' and message.get('request', {}).get('subtype') == 'initialize':
            self.proc.stdout.put({'type': 'control_response', 'response': {'subtype': 'success',
                'request_id': message['request_id'], 'response': {'models': CLAUDE_MODELS, 'account': {'private': 'never emit'}}}})
        elif message.get('type') == 'user':
            mode = self.argv[self.argv.index('--permission-mode')+1]
            self.proc.stdout.put({'type': 'system', 'subtype': 'init', 'session_id': SESSION,
                'permissionMode': mode, 'model': 'synthetic-model'})
            self.offer()
        elif message.get('type') == 'control_response': self.finish()


class Handler:
    def __init__(self, decision='allow_once', callback=None):
        self.requests, self.resolved = [], []
        self.decision, self.callback = decision, callback
    def __call__(self, request, cancelled):
        self.requests.append(request)
        if self.callback: self.callback()
        return {'decision': self.decision}
    def resolve(self, request_id, revision, state): self.resolved.append(state)


def options(target, tool, children, scenario):
    def factory(argv, cwd):
        child = InteractiveChild(argv, cwd, scenario=scenario)
        children.append(child)
        return child
    return {'resolver': lambda source: target.resolved(tool), 'status_reader': lambda value: {
        'id': value.source_id, 'tool': value.tool, 'status': 'completed'},
        'executable_finder': lambda selected: 'synthetic-'+selected+'.exe', 'process_factory': factory}


def execute(target, tool='codex', scenario='approval', handler=None, selection=None, catalog_provider=None):
    if tool == 'claude':
        row = json.loads(target.path.read_text())
        row['permissionMode'] = 'default'
        target.path.write_text(json.dumps(row)+'\n')
    children, events = [], []
    complete = threading.Event()
    def emit(event):
        events.append(event)
        if event['state'] != 'running': complete.set()
    executor = le.LocalReplyExecutor(emit, interaction_handler=handler or Handler(), timeout_seconds=2,
        catalog_provider=catalog_provider, **options(target, tool, children, scenario))
    response = executor.start('command-1', tool+':'+SESSION, 'synthetic prompt', selection=selection)
    if response['state'] == 'running': assert complete.wait(4), events
    executor.close()
    return events, children


@pytest.mark.parametrize('tool', ['codex', 'claude'])
@pytest.mark.parametrize('decision', ['allow_once', 'deny'])
def test_owned_request_routes_exact_decision_and_ack(target, tool, decision):
    handler = Handler(decision)
    events, children = execute(target, tool=tool, handler=handler)
    assert events[-1]['state'] == 'succeeded'
    assert len(handler.requests) == 1 and handler.resolved == ['forwarded']
    assert handler.requests[0]['session_id'] == SESSION
    assert handler.requests[0]['command_id'] == 'command-1'
    assert len(children) == 1 and children[0].close_calls == 1
    if tool == 'claude':
        assert children[0].argv[children[0].argv.index('--permission-mode')+1] == 'default'
        assert '--input-format' in children[0].argv
    assert 'synthetic-operation' not in json.dumps(events)


@pytest.mark.parametrize('tool', ['codex', 'claude'])
def test_provider_cancel_precedes_phone_decision(target, tool):
    handler = Handler()
    events, children = execute(target, tool=tool, scenario='cancel-race', handler=handler)
    assert events[-1]['state'] == 'succeeded'
    assert handler.resolved == ['cancelled']
    assert not any(r.get('id') == 'server-1' or r.get('type') == 'control_response' for r in children[0].requests)


@pytest.mark.parametrize('scenario', ['foreign', 'permissions'])
def test_foreign_turn_and_extra_grants_never_reach_phone(target, scenario):
    handler = Handler()
    events, children = execute(target, scenario=scenario, handler=handler)
    assert events[-1]['state'] == 'blocked'
    assert handler.requests == []
    assert not any(r.get('result', {}).get('decision') == 'accept' for r in children[0].requests)


@pytest.mark.parametrize(('mobile_decision', 'native_decision', 'state', 'error_code'), [
    ('allow_once', 'accept', 'succeeded', ''), ('deny', 'cancel', 'failed', 'declined')])
def test_modern_local_approval_effect_with_exact_native_decision(target, mobile_decision, native_decision, state, error_code):
    handler = Handler(mobile_decision)
    events, children = execute(target, scenario='modern-local', handler=handler)
    assert events[-1]['state'] == state and events[-1]['error_code'] == error_code
    assert len(handler.requests) == 1 and handler.resolved == ['forwarded']
    assert '拒绝会停止本轮' in handler.requests[0]['detail']
    assert 'synthetic-private-rule' not in json.dumps(handler.requests)
    assert [r for r in children[0].requests if r.get('id') == 'server-1'] == [
        {'id': 'server-1', 'result': {'decision': native_decision}}]


@pytest.mark.parametrize('scenario', [
    'modern-foreign-session', 'modern-foreign-item', 'modern-foreign-cwd',
    'modern-foreign-root', 'modern-missing-item-env', 'modern-multi', 'modern-no-proven-session'])
def test_modern_command_unproven_environment_never_reaches_phone(target, scenario):
    handler = Handler()
    events, children = execute(target, scenario=scenario, handler=handler)
    assert events[-1]['state'] == 'blocked' and events[-1]['error_code'] == 'approval'
    assert not handler.requests and not handler.resolved
    assert [r for r in children[0].requests if r.get('id') == 'server-1'] == [
        {'id': 'server-1', 'result': {'decision': 'cancel'}}]


def test_duplicate_wire_request_not_reapproved(target):
    handler = Handler()
    events, children = execute(target, scenario='duplicate', handler=handler)
    assert events[-1]['state'] == 'blocked' and len(handler.requests) == 1
    assert sum(r.get('result', {}).get('decision') == 'accept' for r in children[0].requests) == 1


def test_failure_retains_typed_reason_without_raw_details(target):
    events, _ = execute(target, scenario='quota')
    assert events[-1]['error_code'] == 'quota_exhausted'
    assert 'PRIVATE' not in json.dumps(events)


@pytest.mark.parametrize('tool', ['codex', 'claude'])
def test_capability_discovery_never_sends_user_turn_or_reads_config(target, tool):
    children = []
    catalog = le.discover_reply_catalog(tool+':'+SESSION, timeout_seconds=.7,
        **options(target, tool, children, 'success'))
    assert catalog['models'][0]['efforts'] == ['high']
    assert len(children) == 1 and children[0].close_calls == 1
    assert not any(r.get('type') == 'user' or r.get('method') in {'turn/start', 'thread/resume', 'config/read'}
                   for r in children[0].requests)
    assert 'never emit' not in json.dumps(catalog)


@pytest.mark.parametrize("tool", ["codex", "claude"])
def test_discovery_does_not_require_idle_session_or_replayable_permissions(target, tool):
    target.path.write_text('{}\n')
    children = []
    opts = options(target, tool, children, 'success')
    opts['status_reader'] = lambda _: pytest.fail("listing models must not inspect reply readiness")
    result = le.discover_reply_catalog(tool + ':' + SESSION, timeout_seconds=.7, **opts)
    assert result['models'][0]['id'] == 'synthetic-model'
    assert result['current_model'] is None and result['current_effort'] is None
    child, = children
    assert '--resume' not in child.argv and SESSION not in child.argv
    assert not any(row.get('method') in {'turn/start', 'thread/resume'} or row.get('type') == 'user' for row in child.requests)


@pytest.mark.parametrize('mode', ['manual', 'auto', 'bypassPermissions'])
def test_unsupported_claude_permission_mode_does_not_start_process(target, mode):
    row = json.loads(target.path.read_text()); row['permissionMode'] = mode
    target.path.write_text(json.dumps(row)+'\n')
    children, events = [], []
    done = threading.Event()
    def emit(value):
        events.append(value)
        if value['state'] != 'running': done.set()
    executor = le.LocalReplyExecutor(emit, interaction_handler=Handler(),
        **options(target, 'claude', children, 'success'))
    executor.start('command-1', 'claude:'+SESSION, 'synthetic')
    assert done.wait(2)
    executor.close()
    assert children == [] and events[-1]['error_code'] == 'permissions'


@pytest.mark.parametrize('tool', ['codex', 'claude'])
def test_selection_revalidated_and_applied_when_next_message_starts(target, tool):
    rows = CODEX_MODELS if tool == 'codex' else CLAUDE_MODELS
    catalog = normalize_catalog(tool, rows)
    selection = {'provider': tool, 'catalog_revision': catalog['catalog_revision'],
                 'model': 'synthetic-model', 'effort': 'high', 'apply_at': 'next_message'}
    events, children = execute(target, tool=tool, scenario='success', selection=selection,
        catalog_provider=lambda source: catalog)
    assert events[-1]['state'] == 'succeeded'
    child = children[0]
    if tool == 'codex':
        params = next(r['params'] for r in child.requests if r.get('method') == 'turn/start')
        assert params['model'] == 'synthetic-model' and params['effort'] == 'high'
    else:
        assert child.argv[child.argv.index('--effort')+1] == 'high'
        assert child.argv[child.argv.index('--permission-mode')+1] == 'default'
    assert not any(r.get('method') == 'config/write' for r in child.requests)


def test_stale_selection_never_starts_paid_turn(target):
    catalog = normalize_catalog('codex', CODEX_MODELS)
    selection = {'provider': 'codex', 'catalog_revision': 'stale', 'model': 'synthetic-model'}
    events, children = execute(target, scenario='success', selection=selection, catalog_provider=lambda source: catalog)
    assert events[-1]['error_code'] == 'changed' and children == []


def test_resolution_queued_before_start_response_prevents_late_approval(target):
    handler = Handler()
    events, children = execute(target, scenario='early-cancel', handler=handler)
    assert events[-1]['state'] == 'succeeded'
    assert handler.resolved == ['cancelled']
    assert not any(r.get('id') == 'server-1' for r in children[0].requests)


@pytest.mark.parametrize('scenario,allowed', [('file-change', True), ('file-foreign', False)])
def test_file_changes_require_preview_from_same_turn_and_item(target, scenario, allowed):
    handler = Handler()
    events, _ = execute(target, scenario=scenario, handler=handler)
    if allowed:
        assert events[-1]['state'] == 'succeeded'
        assert 'synthetic diff' in handler.requests[0]['operation']
        assert '/synthetic' in handler.requests[0]['operation']
    else:
        assert events[-1]['state'] == 'blocked' and handler.requests == []


@pytest.mark.parametrize('tool', ['codex', 'claude'])
def test_explicit_unknown_error_cannot_inherit_previous_known_reason(target, tool):
    events, _ = execute(target, tool=tool, scenario='unknown-final')
    assert events[-1]['state'] == 'failed' and events[-1]['error_code'] == 'failed'
