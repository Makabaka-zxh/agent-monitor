"""Synthetic, isolated control-channel authentication and lifecycle coverage."""
import json
import threading
import time
import uuid

from fastapi import HTTPException
import pytest

from agent_monitor.task_replies import TaskReplies
from test_task_replies import hub, send, SESSION


def controls(hub):
    return hub[0].state.task_replies.controls


def catalog(**changes):
    result = {'provider': 'codex', 'catalog_revision': 'version-1', 'valid_until': time.time() + 200,
              'models': [{'id': 'test-model', 'label': 'Test model', 'efforts': ['low', 'high']}],
              'current_model': 'test-model', 'current_effort': 'low'}
    result.update(changes)
    return result


def selection(**changes):
    result = {'provider': 'codex', 'catalog_revision': 'version-1', 'model': 'test-model',
              'effort': 'high', 'apply_at': 'next_message'}
    result.update(changes)
    return result


def publish_catalog(hub, value=None):
    device, _, source = hub[3].partition(':')
    controls(hub).publish_catalog(device, source, catalog() if value is None else value)


def live(hub):
    data, response = send(hub)
    assert response.status_code == 200
    hub[0].state.task_replies._tick()
    return data['request_id']


def pending(kind='approval', **changes):
    value = {'id': str(uuid.uuid4()), 'revision': 'revision-1', 'kind': kind, 'provider': 'codex',
             'session_id': SESSION, 'turn_id': 'synthetic-turn', 'title': 'Review action',
             'detail': 'Synthetic operation', 'operation': 'print synthetic output',
             'questions': [], 'expires_at': time.time() + 200}
    if kind == 'question':
        value['questions'] = [{'id': 'question', 'text': 'Choose an option', 'multiple': False,
                              'allows_text': True, 'options': [
                                  {'id': 'one', 'label': 'One', 'description': ''},
                                  {'id': 'two', 'label': 'Two', 'description': ''}]}]
        value['operation'] = ''
    value.update(changes)
    return value


def publish(hub, command, value):
    device, _, source = hub[3].partition(':')
    return controls(hub).publish_request(device, command, source, value)


def decision(hub, value, **changes):
    data = {'task_id': hub[3], 'request_id': value['id'], 'revision': value['revision'],
            'decision_id': str(uuid.uuid4()), 'decision': 'allow_once', 'answers': []}
    data.update(changes)
    return data


def submit(hub, data, headers=None):
    return hub[1].post('/api/native/tasks/controls/decision', headers=hub[2] if headers is None else headers, json=data)


def test_workbench_shows_only_this_readers_actionable_request_kind(hub):
    app, client, headers, task_id, _, _, reader = hub
    def card(auth):
        response = client.get('/api/native/workbench', headers=auth)
        assert response.status_code == 200
        return next(t for t in response.json()['tasks'] if t['id'] == task_id)

    command = live(hub)
    assert 'pending_interaction_kind' not in card(headers)
    approval, question = pending(), pending('question')
    publish(hub, command, approval)
    assert card(headers)['pending_interaction_kind'] == 'approval'
    publish(hub, command, question)
    task = card(headers)
    assert task['pending_interaction_kind'] == 'question'
    assert question['questions'][0]['text'] not in json.dumps(task)
    assert 'pending_interaction_kind' not in card(reader())
    app.state.store.archive_task(task_id, True)
    assert 'pending_interaction_kind' not in card(headers)
    app.state.store.archive_task(task_id, False)
    assert submit(hub, decision(hub, question, decision='answer', answers=[{'question_id':'question', 'choices':['two']}])).status_code == 200
    assert card(headers)['pending_interaction_kind'] == 'approval'
    with app.state.store.lock, app.state.store.db:
        app.state.store.db.execute('UPDATE task_interactions SET expires_at=0 WHERE id=?', (approval['id'],))
    assert 'pending_interaction_kind' not in card(headers)


@pytest.mark.parametrize('mode', ['missing', 'cookie', 'read_only', 'foreign_origin'])
def test_controls_require_native_full_app_and_do_not_discover_without_auth(hub, mode):
    called = []
    controls(hub).catalog_provider = lambda source: called.append(source)
    auth = ({} if mode in ('missing', 'cookie') else hub[6]('read_only') if mode == 'read_only'
            else {**hub[2], 'Origin': 'https://elsewhere.test'})
    if mode == 'missing':
        hub[1].cookies.clear()
    response = hub[1].get('/api/native/tasks/controls', headers=auth, params={'task_id': hub[3]})
    assert response.status_code in (401, 403)
    assert submit(hub, decision(hub, pending()), headers=auth).status_code in (401, 403)
    assert not called


def settled_catalog(hub):
    deadline = time.monotonic() + 5
    while True:
        response = hub[1].get('/api/native/tasks/controls', headers=hub[2], params={'task_id': hub[3]})
        assert response.status_code == 200
        result = response.json()
        if result['catalog_status'] != 'pending' or time.monotonic() >= deadline:
            return result
        time.sleep(.05)


def test_catalog_read_only_discovery_is_cached_and_does_not_start_a_turn(hub):
    calls = []
    controls(hub).catalog_provider = lambda source: calls.append(source) or catalog()
    for _ in range(2):
        result = settled_catalog(hub)
        assert result['capabilities']['model_settings'] is True
        assert result['catalog']['catalog_revision'] == 'version-1'
        assert result['pending'] == []
    assert len(calls) == 1 and not hub[5]
    assert hub[0].state.store.db.execute('SELECT COUNT(*) FROM task_replies').fetchone()[0] == 0


@pytest.mark.parametrize("state", ["running", "waiting"])
def test_busy_session_can_list_models_while_reply_remains_blocked(hub, state):
    device, _, _ = hub[3].partition(":")
    hub[0].state.store.ingest(device, [dict(hub[4], status=state)], [])
    controls(hub).catalog_provider = lambda _: catalog()
    result = settled_catalog(hub)
    assert result["catalog_status"] == "ready" and result["capabilities"]["model_settings"]
    assert hub[0].state.task_replies._base_capability(hub[3])[-1] == "busy"
    assert not hub[5]


def test_unavailable_catalog_announced_without_polling_provider_on_every_read(hub):
    calls = []
    controls(hub).catalog_provider = lambda source: calls.append(source)
    for _ in range(2):
        result = settled_catalog(hub)
        assert result["catalog_status"] == "unavailable" and not result["capabilities"]["model_settings"]
    assert len(calls) == 1


@pytest.mark.parametrize('change', [
    {'provider': 'claude'}, {'catalog_revision': ''}, {'valid_until': time.time() - 1},
    {'valid_until': time.time() + 10000}, {'current_model': 'bad model'}, {'current_effort': 'bad effort'},
    {'permissionMode': 'bypassPermissions'},
    {'models': [{'id': 'm', 'label': 'M', 'efforts': ['high', 'high']}]},
])
def test_catalog_rejects_wrong_scope_unknown_settings_and_expiry(hub, change):
    with pytest.raises(HTTPException) as exc:
        publish_catalog(hub, catalog(**change))
    assert exc.value.status_code == 422


def test_unknown_catalog_cannot_authorize_model_selection(hub):
    assert send(hub, selection=selection())[1].status_code == 409
    assert not hub[5]


def test_observed_versioned_model_does_not_expand_or_invalidate_catalog(hub):
    publish_catalog(hub, catalog(current_model='retired-model-20200101', current_effort='observed_level'))
    result = hub[1].get('/api/native/tasks/controls', headers=hub[2], params={'task_id': hub[3]}).json()['catalog']
    assert result['current_model'] == 'retired-model-20200101'
    assert result['models'][0]['id'] == 'test-model'
    assert send(hub, selection=selection(model='retired-model-20200101'))[1].status_code == 409
    assert send(hub, selection=selection(effort='observed_level'))[1].status_code == 409
    assert send(hub, selection=selection())[1].status_code == 200


@pytest.mark.parametrize('change', [{'provider': 'claude'}, {'catalog_revision': 'old'},
                                  {'model': 'unknown'}, {'effort': 'maximum'}])
def test_selection_is_validated_against_actual_catalog(hub, change):
    publish_catalog(hub)
    assert send(hub, selection=selection(**change))[1].status_code == 409


def test_selection_immutable_replay_and_local_executor_propagation(hub):
    publish_catalog(hub)
    received = []
    class Executor:
        def __init__(self, emit): pass
        def start(self, command_id, source_id, text, *, selection):
            received.append(selection)
            return {'state': 'running'}
        def close(self): pass
    hub[0].state.task_replies.executor_factory = Executor
    data, response = send(hub, selection=selection())
    assert response.status_code == 200
    assert hub[1].post('/api/native/tasks/reply', headers=hub[2], json=data).status_code == 200
    assert hub[1].post('/api/native/tasks/reply', headers=hub[2], json={**data, 'selection': selection(effort='low')}).status_code == 409
    assert hub[1].post('/api/native/tasks/reply', headers=hub[2], json={**data, 'selection': None}).status_code == 409
    hub[0].state.task_replies._tick()
    assert received == [data['selection']]


def test_queued_selection_is_rechecked_before_dispatch(hub):
    publish_catalog(hub)
    data, response = send(hub, selection=selection())
    assert response.status_code == 200
    device, _, source = hub[3].partition(':')
    controls(hub).publish_catalog(device, source, None)
    hub[0].state.task_replies._tick()
    row = hub[0].state.store.db.execute('SELECT state,reason_code FROM task_replies WHERE id=?', (data['request_id'],)).fetchone()
    assert tuple(row) == ('blocked', 'settings_changed') and not hub[5]


@pytest.mark.parametrize('receipt', ['forwarded', 'acknowledged'])
def test_approval_lifecycle_single_delivery_and_exact_retry_after_receipt(hub, receipt):
    command, value = live(hub), pending()
    publish(hub, command, value)
    data = decision(hub, value)
    assert submit(hub, data).json()['state'] == 'submitted'
    assert submit(hub, data).json()['state'] == 'submitted'
    assert submit(hub, {**data, 'decision': 'deny'}).status_code == 409
    device = hub[3].partition(':')[0]
    with pytest.raises(HTTPException) as exc:
        controls(hub).resolve(device, value['id'], value['revision'], receipt)
    assert exc.value.status_code == 409
    first = controls(hub).poll_decision(device, value['id'], value['revision'])
    assert first == {'state': 'submitted', 'decision': {k: data[k] for k in ('decision_id', 'decision', 'answers')}}
    assert controls(hub).poll_decision(device, value['id'], value['revision'])['decision'] is None
    assert controls(hub).resolve(device, value['id'], value['revision'], receipt)['state'] == receipt
    assert submit(hub, data).json()['state'] == receipt
    row = hub[0].state.store.db.execute('SELECT payload,decision,decision_hash FROM task_interactions').fetchone()
    assert row['payload'] == row['decision'] == '' and len(row['decision_hash']) == 64


def test_interaction_not_visible_or_actionable_to_another_reader(hub):
    command, value = live(hub), pending()
    publish(hub, command, value)
    other = hub[6]()
    result = hub[1].get('/api/native/tasks/controls', headers=other, params={'task_id': hub[3]}).json()
    assert result['pending'] == []
    assert submit(hub, decision(hub, value), headers=other).status_code == 404


@pytest.mark.parametrize('change', ['expiry', 'reader_revoked', 'offline', 'archived', 'source_finished', 'restart', 'cancelled'])
def test_stale_channel_fails_closed_and_drops_content(hub, change):
    command, value = live(hub), pending()
    publish(hub, command, value)
    store = hub[0].state.store
    if change == 'expiry':
        with store.lock, store.db: store.db.execute('UPDATE task_interactions SET expires_at=0')
    elif change == 'reader_revoked':
        with store.lock, store.db: store.db.execute('UPDATE native_readers SET expires_at=0')
    elif change == 'offline':
        with store.lock, store.db: store.db.execute('UPDATE devices SET last_seen=0')
    elif change == 'archived':
        store.archive_task(hub[3], True)
    elif change == 'source_finished':
        hub[0].state.task_replies.update(command, 'failed')
    elif change == 'restart':
        TaskReplies(store)
    else:
        controls(hub).resolve(hub[3].partition(':')[0], value['id'], value['revision'], 'cancelled')
    device = hub[3].partition(':')[0]
    outcome = controls(hub).poll_decision(device, value['id'], value['revision'])
    assert outcome['state'] in ('expired', 'cancelled') and outcome['decision'] is None
    assert submit(hub, decision(hub, value)).status_code in (401, 403, 409)
    row = store.db.execute('SELECT payload,decision FROM task_interactions').fetchone()
    assert tuple(row) == ('', '')


@pytest.mark.parametrize('change', [{'session_id': 'other'}, {'provider': 'claude'}, {'expires_at': time.time() - 1},
                                  {'expires_at': time.time() + 10000}, {'operation': ''}, {'revision': ''},
                                  {'title': ' '}, {'turn_id': ' '}, {'id': ' '}])
def test_published_request_must_be_bound_complete_and_fresh(hub, change):
    command = live(hub)
    with pytest.raises(HTTPException) as exc:
        publish(hub, command, pending(**change))
    assert exc.value.status_code in (409, 422)


def test_pending_cannot_be_reconstructed_from_logs_or_unclaimed_command(hub):
    value = pending()
    with pytest.raises(HTTPException):
        publish(hub, str(uuid.uuid4()), value)
    data, response = send(hub)
    assert response.status_code == 200
    with pytest.raises(HTTPException):
        publish(hub, data['request_id'], value)
    assert hub[0].state.store.db.execute('SELECT COUNT(*) FROM task_interactions').fetchone()[0] == 0


def test_request_revision_turn_and_operation_are_immutable(hub):
    command, value = live(hub), pending()
    publish(hub, command, value)
    assert publish(hub, command, value)['state'] == 'pending'
    for key in ('revision', 'turn_id', 'operation'):
        with pytest.raises(HTTPException) as exc:
            publish(hub, command, {**value, key: 'changed'})
        assert exc.value.status_code == 409


@pytest.mark.parametrize('answers', [[], [{'question_id': 'unknown', 'choices': ['one']}],
    [{'question_id': 'question', 'choices': ['unknown']}], [{'question_id': 'question', 'choices': ['one', 'two']}],
    [{'question_id': 'question', 'choices': ['one', 'one']}], [{'question_id': 'question', 'choices': [], 'text': ' '}],
    [{'question_id': 'question', 'choices': ['one'], 'text': 'Alternative'}],
    [{'question_id': 'question', 'choices': []}],
    [{'question_id': 'question', 'choices': ['x' * 81]}],
    [{'question_id': 'question', 'choices': [], 'text': 'bad\x00text'}]])
def test_questions_require_complete_explicit_valid_answers(hub, answers):
    command, value = live(hub), pending('question')
    publish(hub, command, value)
    assert submit(hub, decision(hub, value, decision='answer', answers=answers)).status_code == 422
    assert submit(hub, decision(hub, value)).status_code == 422


def test_question_answer_is_not_approval_and_multiple_requires_capability(hub):
    command, value = live(hub), pending('question')
    value['questions'][0]['multiple'] = True
    publish(hub, command, value)
    data = decision(hub, value, decision='answer', answers=[{'question_id': 'question', 'choices': ['one', 'two'], 'text': None}])
    assert submit(hub, data).json()['state'] == 'submitted'
    result = controls(hub).poll_decision(hub[3].partition(':')[0], value['id'], value['revision'])
    assert result['decision']['decision'] == 'answer'


def test_decision_id_cannot_be_reused_for_another_request(hub):
    command = live(hub)
    first, second = pending(), pending()
    publish(hub, command, first)
    publish(hub, command, second)
    data = decision(hub, first)
    assert submit(hub, data).status_code == 200
    assert submit(hub, decision(hub, second, decision_id=data['decision_id'])).status_code == 409


def test_remote_catalog_request_and_owned_request_routes(hub):
    app, client, headers, _, task, *_ = hub
    store = app.state.store
    one = store.register(store.create_pairing()['code'], 'Remote synthetic', 'Windows')
    two = store.register(store.create_pairing()['code'], 'Other synthetic', 'Windows')
    agent = {'Authorization': 'Bearer ' + one['token']}
    other = {'Authorization': 'Bearer ' + two['token']}
    store.ingest(one['device_id'], [task], [])
    task_id = one['device_id'] + ':' + task['id']
    client.post('/api/agent/replies/poll', headers=agent, json={'supported_tools': ['codex']})
    result = client.get('/api/native/tasks/controls', headers=headers, params={'task_id': task_id}).json()
    assert result['catalog'] is None
    assert client.post('/api/agent/replies/poll', headers=other, json={}).json()['catalog_requests'] == []
    assert client.post('/api/agent/replies/poll', headers=agent, json={'supported_tools': ['codex']}).json()['catalog_requests'] == [task['id']]
    body = {'source_id': task['id'], 'catalog': catalog()}
    assert client.post('/api/agent/controls/catalog', headers=other, json=body).status_code == 404
    assert client.post('/api/agent/controls/catalog', headers=agent, json=body).status_code == 200
    data, response = send(hub, task_id=task_id, selection=selection())
    assert response.status_code == 200
    claim = client.post('/api/agent/replies/poll', headers=agent, json={'supported_tools': ['codex']}).json()['command']
    assert claim['selection'] == data['selection']
    value = pending()
    body = {'command_id': data['request_id'], 'source_id': task['id'], 'request': value}
    assert client.post('/api/agent/controls/request', headers=other, json=body).status_code == 409
    assert client.post('/api/agent/controls/request', headers=agent, json=body).status_code == 200
    choice = decision(hub, value, task_id=task_id)
    assert submit(hub, choice).json()['state'] == 'submitted'
    poll = {'request_id': value['id'], 'revision': value['revision']}
    assert client.post('/api/agent/controls/poll', headers=other, json=poll).status_code == 404
    assert client.post('/api/agent/controls/resolve', headers=other, json={**poll, 'state': 'acknowledged'}).status_code == 404
    assert client.post('/api/agent/controls/poll', headers=agent, json=poll).json()['decision']['decision'] == 'allow_once'
    assert client.post('/api/agent/controls/resolve', headers=agent, json={**poll, 'state': 'acknowledged'}).json()['state'] == 'acknowledged'


def test_local_handler_wait_does_not_hold_database_lock(hub):
    command, value = live(hub), pending()
    device, _, source = hub[3].partition(':')
    result, errors = [], []
    cancelled = threading.Event()
    def run():
        try:
            result.append(controls(hub).local_handler({**value, 'command_id': command, 'source_id': source}, cancelled))
        except Exception as exc:
            errors.append(type(exc).__name__)
    worker = threading.Thread(target=run)
    worker.start()
    try:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            with hub[0].state.store.lock:
                exists = hub[0].state.store.db.execute('SELECT 1 FROM task_interactions').fetchone()
            if exists:
                break
            time.sleep(.01)
        assert exists
        data = decision(hub, value, decision='deny')
        assert submit(hub, data).status_code == 200
        worker.join(2)
        assert not worker.is_alive() and not errors and result[0]['decision'] == 'deny'
        controls(hub).local_handler.resolve(value['id'], value['revision'], 'acknowledged')
        assert controls(hub).poll_decision(device, value['id'], value['revision'])['state'] == 'acknowledged'
    finally:
        cancelled.set()
        worker.join(2)


def test_concurrent_decisions_have_one_winner(hub):
    from concurrent.futures import ThreadPoolExecutor
    command, value = live(hub), pending()
    publish(hub, command, value)
    choices = [decision(hub, value, decision=choice) for choice in ('allow_once', 'deny')]
    with ThreadPoolExecutor(max_workers=2) as pool:
        codes = list(pool.map(lambda data: submit(hub, data).status_code, choices))
    assert sorted(codes) == [200, 409]
    device = hub[3].partition(':')[0]
    with ThreadPoolExecutor(max_workers=2) as pool:
        received = list(pool.map(lambda _: controls(hub).poll_decision(device, value['id'], value['revision'])['decision'], range(2)))
    assert sum(item is not None for item in received) == 1


def test_legacy_reply_id_cannot_be_reused_to_add_a_selection(hub):
    import hashlib
    data, response = send(hub)
    assert response.status_code == 200
    store = hub[0].state.store
    with store.lock, store.db:
        store.db.execute('UPDATE task_replies SET request_schema=1,prompt_hash=?', (hashlib.sha256(data['text'].encode()).hexdigest(),))
    assert hub[1].post('/api/native/tasks/reply', headers=hub[2], json=data).status_code == 200
    assert hub[1].post('/api/native/tasks/reply', headers=hub[2], json={**data, 'selection': selection()}).status_code == 409


def test_failure_reason_is_preserved_and_unknown_raw_error_not_exposed(hub):
    from agent_monitor.failure_reasons import FAILURE_LABELS
    command = live(hub)
    code = next(iter(FAILURE_LABELS))
    result = hub[0].state.task_replies.update(command, 'failed', code)
    assert result['reason'] == FAILURE_LABELS[code]
    command = live(hub)
    result = hub[0].state.task_replies.update(command, 'failed', 'raw-private-text')
    assert 'raw-private-text' not in json.dumps(result)


def test_pending_remains_visible_above_newer_terminal_receipts(hub):
    command, active = live(hub), pending()
    publish(hub, command, active)
    device = hub[3].partition(':')[0]
    for _ in range(13):
        item = pending()
        publish(hub, command, item)
        controls(hub).resolve(device, item['id'], item['revision'], 'cancelled')
    result = hub[1].get('/api/native/tasks/controls', headers=hub[2], params={'task_id': hub[3]}).json()
    assert result['pending'][0]['id'] == active['id']
    assert result['pending'][0]['state'] == 'pending'
    assert len(result['pending']) == 12


def test_shutdown_cannot_publish_new_request_after_cleanup(hub):
    command = live(hub)
    hub[0].state.task_replies.close()
    with pytest.raises(HTTPException) as exc:
        publish(hub, command, pending())
    assert exc.value.status_code == 409
    assert hub[0].state.store.db.execute('SELECT COUNT(*) FROM task_interactions').fetchone()[0] == 0
