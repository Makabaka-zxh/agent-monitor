import json
import secrets
import time
import uuid

from fastapi.testclient import TestClient
import pytest

from agent_monitor.server import create_app
from agent_monitor.native_access import challenge_for
from agent_monitor.task_replies import TaskReplies
from agent_monitor.store import ONLINE_SECONDS, iso

ORIGIN = 'https://replies.test'
SESSION = '00000000-0000-4000-8000-000000000001'


@pytest.fixture
def hub(tmp_path):
    app = create_app(tmp_path, public_url=ORIGIN, allowed_hosts=['replies.test'])
    store = app.state.store
    account = {'username': 'reply-owner', 'password': 'Synthetic-reply-password-123'}
    store.setup(**account)
    client = TestClient(app, base_url=ORIGIN)
    login = client.post('/api/login', json=account).json()
    csrf = {'Origin': ORIGIN, 'X-CSRF-Token': login['csrf_token']}
    def reader(mode='full_app'):
        verifier = secrets.token_urlsafe(32)
        start = client.post('/api/native/pairing/start', json={'device_name':'reply-test', 'mode':mode, 'code_challenge':challenge_for(verifier)}).json()
        path = '/api/native/pairing/' + start['request_id']
        assert client.post(path + '/approve', headers=csrf, json={'mode':mode,'consent_version':mode+'_v1'}).status_code == 200
        token = client.post(path+'/poll', json={'code_verifier':verifier}).json()['reader_token']
        return {'Authorization':'Bearer '+token}
    headers = reader()
    device = store.ensure_local('Test computer', 'Windows')
    source = 'codex:' + SESSION
    task = {'id':source, 'tool':'codex', 'title':'Test task', 'status':'completed', 'updated_at':iso(), 'output':'', 'preview':'', 'project':'Test'}
    store.ingest(device, [task], [])
    app.state.task_replies.local_enabled = True
    app.state.task_replies.inspector = lambda _: {'available':True}
    starts = []
    class Executor:
        def __init__(self, emit): self.emit = emit
        def start(self, command_id, source_id, prompt):
            starts.append((command_id,source_id,prompt))
            return {'state':'running'}
        def cancel(self, command_id): pass
        def close(self): pass
    app.state.task_replies.executor_factory = Executor
    try:
        yield app, client, headers, device+':'+source, task, starts, reader
    finally:
        app.state.task_replies.close()
        client.close()
        store.close()


def send(hub, **changes):
    app, client, headers, task_id, *_ = hub
    data = {'request_id':str(uuid.uuid4()), 'task_id':task_id, 'text':'Reply safely'}
    data.update(changes)
    return data, client.post('/api/native/tasks/reply', headers=headers, json=data)


def test_idempotency_conflict_and_no_text_in_status(hub):
    app, client, headers, task_id, _, starts, _ = hub
    data, response = send(hub)
    assert response.status_code == 200
    assert response.json()['state'] == 'queued'
    assert 'text' not in response.json() and 'prompt' not in response.json()
    again = client.post('/api/native/tasks/reply', headers=headers, json=data)
    assert again.json()['id'] == data['request_id']
    assert client.post('/api/native/tasks/reply', headers=headers, json={**data,'text':'different'}).status_code == 409
    app.state.task_replies._tick()
    app.state.task_replies._tick()
    assert len(starts) == 1
    status = client.get('/api/native/tasks/reply', headers=headers, params={'task_id':task_id,'request_id':data['request_id']}).json()
    assert status['state'] == 'running' and status['delivery_ms'] >= 0
    app.state.task_replies.update(data['request_id'], 'succeeded')
    app.state.task_replies.update(data['request_id'], 'running')
    status = client.get('/api/native/tasks/reply', headers=headers, params={'task_id':task_id,'request_id':data['request_id']}).json()
    assert status['state'] == 'succeeded'


@pytest.mark.parametrize('mode', ['missing','cookie','read_only','foreign_origin'])
def test_reply_authentication_boundaries(hub, mode):
    _, client, headers, task_id, _, starts, reader = hub
    auth = {} if mode in ('missing','cookie') else reader('read_only') if mode == 'read_only' else {**headers,'Origin':'https://evil.test'}
    if mode == 'missing': client.cookies.clear()
    data = {'request_id':str(uuid.uuid4()), 'task_id':task_id, 'text':'Not authorized'}
    assert client.get('/api/native/tasks/reply', headers=auth, params={'task_id':task_id}).status_code in (401,403)
    assert client.post('/api/native/tasks/reply', headers=auth, json=data).status_code in (401,403)
    assert not starts


def test_unknown_id_and_other_reader_do_not_leak(hub):
    _, client, headers, task_id, _, _, reader = hub
    data, response = send(hub)
    assert response.status_code == 200
    for auth, request_id in [(headers,str(uuid.uuid4())), (reader(),data['request_id'])]:
        result = client.get('/api/native/tasks/reply',headers=auth,params={'task_id':task_id,'request_id':request_id})
        assert result.json() == {'found':False,'state':'not_found'}


@pytest.mark.parametrize('change', ['running','waiting','unknown','offline','archived','expired_reader'])
def test_state_change_after_enqueue_does_not_launch(hub, change):
    app, _, _, task_id, task, starts, _ = hub
    data, response = send(hub)
    assert response.status_code == 200
    store = app.state.store
    device = task_id.partition(':')[0]
    if change in ('running','waiting','unknown'):
        store.ingest(device, [{**task,'status':change}], [])
    elif change == 'offline':
        with store.lock,store.db: store.db.execute('UPDATE devices SET last_seen=0')
    elif change == 'archived': store.archive_task(task_id,True)
    else:
        with store.lock,store.db: store.db.execute('UPDATE native_readers SET expires_at=0')
    app.state.task_replies._tick()
    row = store.db.execute('SELECT state,prompt FROM task_replies WHERE id=?',(data['request_id'],)).fetchone()
    assert row['state'] == 'blocked' and row['prompt'] == '' and not starts


def test_restart_marks_uncertain_and_never_replays(hub):
    app, _, _, _, _, starts, _ = hub
    data, response = send(hub)
    assert response.status_code == 200
    recovered = TaskReplies(app.state.store,local_enabled=True,executor_factory=app.state.task_replies.executor_factory)
    recovered._tick()
    row = app.state.store.db.execute('SELECT state,prompt FROM task_replies WHERE id=?',(data['request_id'],)).fetchone()
    assert row['state'] == 'uncertain' and row['prompt'] == '' and not starts
    recovered.close()


def test_expired_queue_and_single_inflight(hub):
    app, _, _, _, _, starts, _ = hub
    data, _ = send(hub)
    assert send(hub)[1].status_code == 409
    with app.state.store.lock,app.state.store.db:
        app.state.store.db.execute('UPDATE task_replies SET expires_at=0')
    app.state.task_replies._tick()
    assert not starts
    assert app.state.store.db.execute('SELECT reason_code FROM task_replies').fetchone()[0] == 'expired'


def test_remote_device_cannot_claim_or_update_another_computer(hub):
    app, client, headers, _, task, _, _ = hub
    store = app.state.store
    one = store.register(store.create_pairing()['code'], 'Remote one', 'Windows')
    two = store.register(store.create_pairing()['code'], 'Remote two', 'Windows')
    a = {'Authorization':'Bearer '+one['token']}
    b = {'Authorization':'Bearer '+two['token']}
    store.ingest(one['device_id'],[task],[])
    assert client.post('/api/agent/replies/poll',headers=a,json={'supported_tools':['codex']}).status_code == 200
    remote_task = one['device_id']+':'+task['id']
    data, response = send(hub,task_id=remote_task)
    assert response.status_code == 200
    assert client.post('/api/agent/replies/poll',headers=b,json={'supported_tools':['codex']}).json()['command'] is None
    claim = client.post('/api/agent/replies/poll',headers=a,json={'supported_tools':['codex']}).json()['command']
    assert claim['id'] == data['request_id'] and claim['text'] == data['text']
    assert client.post('/api/agent/replies/poll',headers=a,json={'supported_tools':['codex']}).json()['command'] is None
    update = {'id':data['request_id'],'state':'succeeded'}
    assert client.post('/api/agent/replies/update',headers=b,json=update).status_code == 404
    assert client.post('/api/agent/replies/update',headers=a,json=update).json()['state'] == 'succeeded'


@pytest.mark.parametrize('tool,label', [('codex', 'Codex'), ('claude', 'Claude Code')])
@pytest.mark.parametrize('capability', ['never_reported', 'expired', 'missing_tool'])
def test_remote_reply_readiness_reports_channel_or_missing_program(hub, monkeypatch, tool, label, capability):
    app, client, headers, _, task, starts, _ = hub
    store = app.state.store
    remote = store.register(store.create_pairing()['code'], 'Mac mini', 'macOS')
    device = remote['device_id']
    agent = {'Authorization': 'Bearer ' + remote['token']}
    remote_task = {**task, 'id': tool + ':' + SESSION, 'tool': tool}
    store.ingest(device, [remote_task], [])
    task_id = device + ':' + remote_task['id']
    now = time.time()
    monkeypatch.setattr('agent_monitor.task_replies.time.time', lambda: now)
    if capability == 'expired':
        # Task heartbeat is fresh, while the reply channel reaches the exact
        # expiry boundary. Its old tool list must not look like a missing CLI.
        app.state.task_replies._capabilities[device] = ([], now - ONLINE_SECONDS)
    elif capability == 'missing_tool':
        other = 'claude' if tool == 'codex' else 'codex'
        assert client.post('/api/agent/replies/poll', headers=agent,
                           json={'supported_tools': [other]}).status_code == 200
    expected = (f'电脑连接器未找到 {label} 命令程序，请在电脑上安装或配置后重试'
                if capability == 'missing_tool' else '回复连接尚未就绪，请检查电脑端的回复设置与连接')
    response = client.get('/api/native/tasks/reply', headers=headers, params={'task_id': task_id})
    assert response.status_code == 200
    assert response.json() == {'available': False, 'reason': expected, 'latest': None}
    _, denied = send(hub, task_id=task_id)
    assert denied.status_code == 409 and denied.json()['detail'] == expected
    assert store.db.execute('SELECT COUNT(*) FROM task_replies').fetchone()[0] == 0
    assert not starts
    # A fresh report with the required program restores availability without
    # changing account permissions or creating a reply during the check.
    assert client.post('/api/agent/replies/poll', headers=agent,
                       json={'supported_tools': [tool]}).status_code == 200
    assert client.get('/api/native/tasks/reply', headers=headers,
                      params={'task_id': task_id}).json()['available'] is True
    assert store.db.execute('SELECT COUNT(*) FROM task_replies').fetchone()[0] == 0


def test_reply_capability_messages_preserve_unknown_task_rejection(hub):
    app, client, headers, task_id, task, starts, _ = hub
    app.state.store.ingest(task_id.partition(':')[0], [{**task, 'status': 'unknown'}], [])
    status = client.get('/api/native/tasks/reply', headers=headers, params={'task_id': task_id}).json()
    assert status['available'] is False and status['reason'] == '任务正在运行或等待确认'
    assert send(hub)[1].status_code == 409 and not starts
    assert app.state.store.db.execute('SELECT COUNT(*) FROM task_replies').fetchone()[0] == 0


def test_local_reply_disabled_does_not_request_connector_upgrade(hub):
    app, client, headers, task_id, _, starts, _ = hub
    app.state.task_replies.local_enabled = False
    status = client.get('/api/native/tasks/reply', headers=headers, params={'task_id': task_id}).json()
    assert status['available'] is False
    assert status['reason'] == '这台电脑尚未启用远程回复，请在电脑端检查回复设置'
    _, denied = send(hub)
    assert denied.status_code == 409 and denied.json()['detail'] == status['reason']
    assert not starts


@pytest.mark.parametrize('field,value', [('text',' '), ('text','x'*16001), ('text','bad\x00text'), ('request_id','../bad'), ('cwd','C:/')])
def test_invalid_inputs(hub,field,value):
    _, result = send(hub,**{field:value})
    assert result.status_code == 422
