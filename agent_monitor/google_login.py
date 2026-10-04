"""Google OIDC login explicitly linked to the existing personal account."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import threading
import time
from pathlib import Path
from urllib.parse import urlencode, urlsplit

import requests
from fastapi import HTTPException, Request, Response
from fastapi.responses import HTMLResponse, RedirectResponse
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2.id_token import verify_oauth2_token
from pydantic import BaseModel, Field, field_validator
from typing import Literal

from .profile import normalize_avatar
from .store import SESSION_SECONDS, digest

AUTHORIZATION = 'https://accounts.google.com/o/oauth2/v2/auth'
TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token'
FLOW_COOKIE = 'agent_monitor_google_flow'
APP_COOKIE = 'agent_monitor_session'
FLOW_SECONDS = 600


class GoogleStart(BaseModel):
    mode: Literal['login', 'link'] = 'login'
    return_path: str | None = Field(default=None, max_length=64)

    @field_validator('return_path')
    @classmethod
    def native_return_only(cls, value):
        if value is not None and not re.fullmatch(r'/#/native-connect/[A-Za-z0-9_-]{32}', value):
            raise ValueError('登录返回位置无效')
        return value


def load_config(state_dir: Path, public_url: str | None):
    if not public_url or not public_url.startswith('https://'):
        return None
    path = state_dir / 'google-oauth.json'
    try:
        if path.stat().st_size > 65536:
            return None
        data = json.loads(path.read_text(encoding='utf-8-sig'))
        if not isinstance(data, dict):
            return None
        data = data.get('web', data)
        if not isinstance(data, dict):
            return None
        client_id, client_secret = data['client_id'], data['client_secret']
        if not isinstance(client_id, str) or not client_id.endswith('.apps.googleusercontent.com') or not isinstance(client_secret, str) or len(client_secret) < 12:
            return None
        return {'client_id': client_id, 'client_secret': client_secret,
                'redirect_uri': public_url.rstrip('/') + '/api/auth/google/callback'}
    except (OSError, ValueError, KeyError, TypeError):
        return None


def exchange_identity(code: str, verifier: str, nonce: str, config: dict) -> dict:
    """Verify Google's signature, issuer, audience, expiry, nonce and stable ID."""
    with requests.Session() as client:
        result = client.post(TOKEN_ENDPOINT, data={
            'grant_type': 'authorization_code', 'code': code, 'code_verifier': verifier,
            'client_id': config['client_id'], 'client_secret': config['client_secret'],
            'redirect_uri': config['redirect_uri'],
        }, timeout=15, allow_redirects=False)
        if result.status_code != 200 or len(result.content) > 65536:
            raise ValueError('Google token exchange failed')
        token = result.json().get('id_token')
        if not isinstance(token, str) or len(token) > 32768:
            raise ValueError('Missing ID token')
        # google-auth verifies cryptographic signature and aud/iss/exp claims.
        transport = GoogleRequest(session=client)
        def bounded_transport(*args, **kwargs):
            return transport(*args, **{**kwargs, 'timeout': 10})
        claims = verify_oauth2_token(token, bounded_transport, config['client_id'])
    if not isinstance(claims.get('nonce'), str) or not hmac.compare_digest(claims['nonce'], nonce):
        raise ValueError('Invalid nonce')
    if not isinstance(claims.get('sub'), str) or not 1 <= len(claims['sub']) <= 255:
        raise ValueError('Invalid Google identity')
    if claims.get('email_verified') is not True:
        raise ValueError('Unverified email')
    return claims


def google_avatar(picture: str | None) -> str | None:
    """Fetch only a signed Google profile photo, with no redirects or metadata."""
    if not isinstance(picture, str) or len(picture) > 2048:
        return None
    try:
        url = urlsplit(picture)
        if url.scheme != 'https' or url.username or url.password or url.port not in {None, 443} or not (url.hostname or '').endswith('.googleusercontent.com'):
            return None
        with requests.get(picture, timeout=10, stream=True, allow_redirects=False) as response:
            if response.status_code != 200:
                return None
            mime = response.headers.get('Content-Type', '').split(';')[0].lower()
            if mime not in {'image/png', 'image/jpeg', 'image/webp'}:
                return None
            content = bytearray()
            for chunk in response.iter_content(8192):
                content.extend(chunk)
                if len(content) > 256 * 1024:
                    return None
            return normalize_avatar('data:' + mime + ';base64,' + base64.b64encode(content).decode('ascii'))
    except (requests.RequestException, ValueError):
        return None


def install_google_routes(app, store, state_dir, public_url, authenticated, same_origin):
    state_dir = Path(state_dir)
    with store.lock, store.db:
        store.db.execute('CREATE TABLE IF NOT EXISTS google_identity (id INTEGER PRIMARY KEY CHECK(id=1), subject TEXT UNIQUE NOT NULL, email TEXT NOT NULL)')
    flows: dict[str, dict] = {}
    flow_lock = threading.Lock()

    def enabled():
        return load_config(state_dir, public_url) is not None

    @app.post('/api/auth/google/start')
    def begin(payload: GoogleStart, request: Request, response: Response):
        same_origin(request)
        config = load_config(state_dir, public_url)
        if not config:
            raise HTTPException(503, 'Google 登录尚未配置，请先使用 App 账号登录')
        owner = authenticated(request) if payload.mode == 'link' else None
        if not store.has_account():
            raise HTTPException(403, '请先在电脑上建立 App 账号')
        now = time.time()
        state, browser_secret, nonce, verifier = [secrets.token_urlsafe(32) for _ in range(4)]
        with flow_lock:
            for key in [key for key, value in flows.items() if value['expires'] <= now]:
                del flows[key]
            if len(flows) >= 100:
                raise HTTPException(429, '登录请求过多，请稍后重试')
            flows[digest(state)] = {'browser': digest(browser_secret), 'nonce': nonce, 'verifier': verifier,
                'mode': payload.mode, 'session_id': owner['id'] if owner else None, 'expires': now + FLOW_SECONDS,
                'client_id': config['client_id'], 'return_path': payload.return_path or '/#/account'}
        response.set_cookie(FLOW_COOKIE, browser_secret, max_age=FLOW_SECONDS, httponly=True,
                            secure=True, samesite='lax', path='/api/auth/google')
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
        return {'url': AUTHORIZATION + '?' + urlencode({
            'client_id': config['client_id'], 'redirect_uri': config['redirect_uri'], 'response_type': 'code',
            'scope': 'openid email profile', 'state': state, 'nonce': nonce,
            'code_challenge': challenge, 'code_challenge_method': 'S256', 'prompt': 'select_account',
        })}

    def error_page(message: str, status=400, return_path='/#/account'):
        # Messages are fixed application strings, never OAuth codes or raw errors.
        # return_path comes only from the validated, browser-bound server flow.
        response = HTMLResponse('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Google 登录</title><body><main><h1>Google 登录未完成</h1><p>' + message + '</p><a href="' + return_path + '">返回 App</a></main></body></html>', status_code=status)
        response.delete_cookie(FLOW_COOKIE, path='/api/auth/google', secure=True, httponly=True, samesite='lax')
        return response

    @app.get('/api/auth/google/callback')
    def callback(request: Request):
        state, browser_secret = request.query_params.get('state', ''), request.cookies.get(FLOW_COOKIE, '')
        if not state or len(state) > 128 or not browser_secret or len(browser_secret) > 128:
            return error_page('登录验证已过期，请返回 App 重试。')
        with flow_lock:
            flow = flows.get(digest(state))
            if not flow or flow['expires'] <= time.time() or not hmac.compare_digest(flow['browser'], digest(browser_secret)):
                return error_page('登录验证已过期，请返回 App 重试。')
            del flows[digest(state)]
        def flow_error(message, status=400):
            return error_page(message, status, flow['return_path'])
        if request.query_params.get('error'):
            return flow_error('已取消 Google 授权。')
        code = request.query_params.get('code', '')
        config = load_config(state_dir, public_url)
        if not config or config['client_id'] != flow['client_id'] or not 1 <= len(code) <= 8192:
            return flow_error('登录配置已变化，请返回 App 重试。')
        try:
            claims = exchange_identity(code, flow['verifier'], flow['nonce'], config)
        except Exception:
            # No provider payload, token, code, or raw exception is displayed/logged.
            return flow_error('暂时无法验证 Google 账号，请返回 App 重试。')
        subject = claims['sub']
        with store.lock, store.db:
            linked = store.db.execute('SELECT subject FROM google_identity WHERE id=1').fetchone()
            if flow['mode'] == 'link':
                owner = store.db.execute('SELECT id FROM sessions WHERE id=? AND expires_at>?', (flow['session_id'], time.time())).fetchone()
                if not owner:
                    return flow_error('App 登录已失效，请重新登录后再关联。', 401)
                if linked and not hmac.compare_digest(linked['subject'], subject):
                    return flow_error('此 App 已关联另一个 Google 账号。', 409)
                store.db.execute('INSERT OR REPLACE INTO google_identity VALUES (1,?,?)', (subject, str(claims.get('email', ''))[:320]))
            elif not linked or not hmac.compare_digest(linked['subject'], subject):
                return flow_error('此 Google 账号尚未关联。请先用现有 App 账号登录，在“个人资料”中关联。', 403)
        changes = {}
        name = str(claims.get('name', '')).strip()[:40]
        if name:
            changes['display_name'] = name
        picture = google_avatar(claims.get('picture'))
        if picture:
            changes['avatar'] = picture
        if changes:
            store.update_profile(changes)
        token, _ = store.new_session('Google 登录设备')
        response = RedirectResponse(flow['return_path'], status_code=303)
        response.set_cookie(APP_COOKIE, token, max_age=SESSION_SECONDS, httponly=True, secure=True, samesite='strict', path='/')
        response.delete_cookie(FLOW_COOKIE, path='/api/auth/google', secure=True, httponly=True, samesite='lax')
        return response

    return enabled
