"""Public and status-only readers cannot obtain account usage data."""
import secrets
import time
from unittest.mock import MagicMock
from fastapi.testclient import TestClient
from agent_monitor.native_access import challenge_for
from agent_monitor.server import create_app


def claim(browser, native, csrf, mode):
    verifier = secrets.token_urlsafe(32)
    r = native.post('/api/native/pairing/start', json={'device_name': 'Usage test', 'mode': mode, 'code_challenge': challenge_for(verifier)})
    assert r.status_code == 200
    path = '/api/native/pairing/' + r.json()['request_id']
    assert browser.post(path + '/approve', headers={'X-CSRF-Token': csrf}, json={'mode':mode,'consent_version':mode + '_v1'}).status_code == 200
    return {'Authorization':'Bearer ' + native.post(path + '/poll', json={'code_verifier':verifier}).json()['reader_token']}


def test_usage_authority_and_cache(tmp_path):
    app = create_app(tmp_path, public_url='https://localhost')
    credentials = {'username':'usage-test', 'password':'Usage-test-only-12345'}
    app.state.store.setup(**credentials)
    native = TestClient(app, base_url='https://localhost', client=('127.0.0.2', 20001))
    with TestClient(app, base_url='https://localhost', client=('127.0.0.1', 20000)) as browser:
        csrf = browser.post('/api/login', json=credentials).json()['csrf_token']
        assert browser.get('/api/native/usage').status_code == 401
        readonly = claim(browser, native, csrf, 'read_only')
        full = claim(browser, native, csrf, 'full_app')
        assert native.get('/api/native/usage', headers=readonly).status_code == 403
        assert 'usage' not in native.get('/api/native/snapshot', headers=readonly).json()
        app.state.usage.refresh()
        response = native.get('/api/native/usage', headers=full)
        assert response.status_code == 200
        assert response.headers['cache-control'] == 'no-store'
        assert response.json()['providers'][0]['today_tokens'] is None
        full_usage = response.json()
        assert full_usage['history_included'] is True
        assert len(full_usage['providers'][0]['history']['days']) == 90
        assert full_usage['quota_checks'] == []
        for path in ('/api/native/workbench', '/api/native/snapshot'):
            compact = native.get(path, headers=full).json()['usage']
            assert compact['history_included'] is False
            assert 'quota_checks' not in compact
            assert all('history' not in provider for provider in compact['providers'])
            assert compact['providers'] == [{key: value for key, value in provider.items() if key != 'history'}
                                           for provider in full_usage['providers']]
        # A compact snapshot must not mutate the cached full chart data.
        assert native.get('/api/native/usage', headers=full).json() == full_usage
        assert native.get('/api/native/usage', headers={**full,'Origin':'https://evil.example'}).status_code == 403
        assert native.post('/api/agent/usage', json={'version':1,'events':[],'quotas':[]}, headers=full).status_code == 401
        assert browser.post('/api/agent/usage', json={}).status_code == 401
    native.close()


def test_paired_usage_does_not_block_heartbeats_or_reach_other_apis(tmp_path):
    import concurrent.futures
    app = create_app(tmp_path)
    with TestClient(app) as client:
        store = app.state.store
        registered = store.register(store.create_pairing()['code'], 'test computer', 'test')
        headers = {'Authorization':'Bearer ' + registered['token']}
        payload = {'version':1,'events':[],'quotas':[]}
        assert client.post('/api/agent/usage', headers=headers, json=payload).json() == {'accepted':0,'rejected':0}
        assert client.get('/api/native/usage', headers=headers).status_code == 401
        assert client.post('/api/agent/usage', headers=headers, json={'version':1,'events':[{'path':'/private','output':'never'}]}).json()['rejected'] == 1
        # A ledger scan or ingestion lock must not hold the status Store lock.
        with concurrent.futures.ThreadPoolExecutor() as pool:
            with app.state.usage.ledger.lock:
                pending = pool.submit(client.post, '/api/agent/usage', headers=headers, json=payload)
                response = pool.submit(client.post, '/api/agent/heartbeat', headers=headers,
                                       json={'tasks':[],'sources':[]}).result(timeout=2)
                assert response.status_code == 200
            assert pending.result(timeout=2).status_code == 200
        store.remove_device(registered['device_id'])
        assert client.post('/api/agent/usage', headers=headers, json=payload).status_code == 401


def test_check_details_require_full_native_authority_and_never_enter_compact_snapshots(tmp_path, monkeypatch):
    app = create_app(tmp_path, public_url='https://localhost')
    credentials = {'username': 'check-test', 'password': 'Check-test-only-12345'}
    app.state.store.setup(**credentials)
    details = MagicMock(return_value={'providers': [], 'history_included': True, 'quota_checks': [{
        'tool': 'claude', 'source_device_id': 'local-pc', 'source_name': 'Local',
        'state': 'no_live_data', 'last_attempt_at': '2026-09-26T14:23:31.000Z', 'retry_after_seconds': 600}]})
    monkeypatch.setattr(app.state.usage, 'detail_summary', details)
    native = TestClient(app, base_url='https://localhost', client=('127.0.0.2', 20001))
    try:
        with TestClient(app, base_url='https://localhost', client=('127.0.0.1', 20000)) as browser:
            csrf = browser.post('/api/login', json=credentials).json()['csrf_token']
            readonly = claim(browser, native, csrf, 'read_only')
            full = claim(browser, native, csrf, 'full_app')
            registered = app.state.store.register(app.state.store.create_pairing()['code'], 'test computer', 'test')
            agent = {'Authorization': 'Bearer ' + registered['token']}
            for client, headers, expected in ((browser, {}, 401), (native, {}, 401),
                    (native, readonly, 403), (native, agent, 401),
                    (native, {**full, 'Origin': 'https://evil.example'}, 403)):
                assert client.get('/api/native/usage', headers=headers).status_code == expected
            details.assert_not_called()
            response = native.get('/api/native/usage', headers=full)
            assert response.status_code == 200 and response.json() == details.return_value
            assert response.headers['cache-control'] == 'no-store'
            details.assert_called_once_with()
            for path in ('/api/native/workbench', '/api/native/snapshot'):
                assert 'quota_checks' not in native.get(path, headers=full).json()['usage']
            assert 'usage' not in native.get('/api/native/snapshot', headers=readonly).json()
            assert browser.post('/api/logout', headers={'X-CSRF-Token': csrf}).status_code == 200
            assert native.get('/api/native/usage', headers=full).status_code == 401
            details.assert_called_once_with()
    finally:
        native.close()
