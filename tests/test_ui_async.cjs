// Real Chromium DOM, isolated APIs, and optional local streaming fixtures.
// No production hub or external network is used.
// Usage: node tests/test_ui_async.cjs [web/app.js]
// PLAYWRIGHT_MODULE can point to an existing Playwright installation.
// PLAYWRIGHT_CHANNEL can select an installed browser (for example msedge).
// QR_PYTHON can select a Python environment with this project's requirements.
// QR fixtures come from the shipping CLI encoder and are decoded by real jsQR.
// UI_SCREENSHOT_DIR writes three isolated 320px previews; UI_SCREENSHOTS_ONLY=1 skips regression cases.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const appPath = path.resolve(process.argv[2] || path.join(__dirname, '../web/app.js'));
const webRoot = path.dirname(appPath);
const documents = {
  '/': { contentType: 'text/html; charset=utf-8', body: fs.readFileSync(path.join(webRoot, 'index.html')) },
  '/app.js': { contentType: 'text/javascript; charset=utf-8', body: fs.readFileSync(appPath) },
  '/features.js': { contentType: 'text/javascript; charset=utf-8', body: fs.readFileSync(path.join(webRoot, 'features.js')) },
  '/assets/vendor/jsQR-1.4.0.js': { contentType: 'text/javascript; charset=utf-8', body: fs.readFileSync(path.join(webRoot, 'assets/vendor/jsQR-1.4.0.js')) },
  '/style.css': { contentType: 'text/css; charset=utf-8', body: fs.readFileSync(path.join(webRoot, 'style.css')) },
};
const ORIGIN = 'https://monitor.example.test';
const NOW = Date.UTC(2026, 8, 12, 12, 0, 0);
const iso = (offset = 0) => new Date(NOW + offset).toISOString();
const QR_ID = 'A'.repeat(32);
const QR_PATH = `/api/pairing/requests/${QR_ID}`;
const NATIVE_ID = 'N'.repeat(32);
const NATIVE_PATH = `/api/native/pairing/${NATIVE_ID}`;
let qrPng, foreignQrPng;
const clone = (value) => JSON.parse(JSON.stringify(value));
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const defer = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};
async function until(predicate, message, timeout = 3500) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(message);
    await delay(10);
  }
}
function initialSnapshot() {
  return {
    server_time: iso(),
    devices: [{ id: 'test-computer', name: '隔离测试电脑', platform: 'Windows', online: true, local: true, last_seen: iso(), sources: [] }],
    tasks: [
      { id: 'codex-one', device_id: 'test-computer', tool: 'codex', title: 'Codex 测试任务', status: 'running', updated_at: iso(-120000), output: '', preview: '', project: 'test-project', status_source: 'local_log' },
      { id: 'claude-one', device_id: 'test-computer', tool: 'claude', title: 'Claude 测试任务', status: 'unknown', updated_at: iso(-300000), output: '', preview: '', project: 'test-project', status_source: 'local_log' },
    ],
  };
}

async function boot(browser, options = {}) {
  const origin = options.origin || ORIGIN;
  const context = await browser.newContext({ viewport: options.viewport || { width: 390, height: 844 }, reducedMotion: 'reduce', serviceWorkers: 'block' });
  const page = await context.newPage();
  page.setDefaultTimeout(3500);
  await page.clock.install({ time: new Date(NOW) });
  await page.clock.pauseAt(new Date(NOW + 1000));
  const mock = {
    authenticated: options.authenticated ?? true, username: 'isolated-user', csrf: 'isolated-csrf-one',
    profile: { display_name: '测试用户', avatar: '' },
    preferences: { tool_filter: 'all', sync_output: false }, snapshot: options.snapshot || initialSnapshot(),
    requests: [], unexpected: [], pageErrors: [], gates: [], closing: false,
    loginPreferences: { tool_filter: 'codex', sync_output: false },
    pairingRequests: { [QR_ID]: { request_id: QR_ID, name: '扫码测试电脑', platform: 'Windows', status: 'pending', expires_at: iso(600000) } },
    nativeRequests: { [NATIVE_ID]: { request_id: NATIVE_ID, device_name: '实况测试手机', status: 'pending', expires_at: iso(600000) } },
    nativeDevices: [],
  };
  const user = () => ({ username: mock.username, ...clone(mock.profile) });
  const queues = new Map();
  page.on('pageerror', (error) => mock.pageErrors.push(error.message));
  const count = (method, pathname) => mock.requests.filter((item) => item.method === method && item.pathname === pathname).length;
  function holdNext(method, pathname) {
    const started = defer(), response = defer(), finished = defer();
    const gate = {
      started: started.promise, pending: true,
      async reply(body, status = 200, commitPreferences = false) {
        gate.pending = false;
        response.resolve({ body, status, commitPreferences });
        await finished.promise;
      },
      async failNetwork() {
        gate.pending = false;
        response.resolve({ abort: true });
        await finished.promise;
      },
      cancel() { response.resolve({ body: { detail: 'Test cleanup' }, status: 503 }); },
      begin: started.resolve, finish: finished.resolve, response: response.promise,
    };
    const key = `${method} ${pathname}`;
    if (!queues.has(key)) queues.set(key, []);
    queues.get(key).push(gate);
    mock.gates.push(gate);
    return gate;
  }
  function ordinaryReply(method, pathname, body) {
    if (pathname === '/api/bootstrap') return { needs_setup: false, google_login: options.googleEnabled ?? false };
    if (pathname === '/api/login' && method === 'POST') {
      mock.authenticated = true;
      mock.csrf = 'isolated-csrf-new-session';
      mock.username = body.username;
      mock.profile = { display_name: '新会话用户', avatar: '' };
      mock.preferences = clone(mock.loginPreferences);
      return { user: user(), csrf_token: mock.csrf };
    }
    if (!mock.authenticated) return { unauthorized: true };
    if (pathname === '/api/me') return { user: user(), csrf_token: mock.csrf, preferences: clone(mock.preferences) };
    if (pathname === '/api/snapshot') return clone(mock.snapshot);
    if (pathname === '/api/preferences' && method === 'PATCH') {
      Object.assign(mock.preferences, body);
      return { preferences: clone(mock.preferences) };
    }
    if (pathname === '/api/profile' && method === 'PATCH') {
      Object.assign(mock.profile, body);
      return { user: user() };
    }
    const archive = pathname.match(/^\/api\/tasks\/([^/]+)\/archive$/);
    if (archive && method === 'PATCH') {
      const task = mock.snapshot.tasks.find((item) => item.id === decodeURIComponent(archive[1]));
      if (task) task.archived = body.archived;
      return { ok: true, archived: body.archived };
    }
    const pairing = pathname.match(/^\/api\/pairing\/requests\/([^/]+)(?:\/(approve|reject))?$/);
    if (pairing) {
      const entry = mock.pairingRequests[decodeURIComponent(pairing[1])];
      if (entry && !pairing[2] && method === 'GET') return clone(entry);
      if (entry && pairing[2] && method === 'POST') {
        entry.status = pairing[2] === 'approve' ? 'approved' : 'denied';
        return { ok: true, status: entry.status };
      }
    }
    if (pathname === '/api/logout' && method === 'POST') { mock.authenticated = false; return {}; }
    if (pathname === '/api/sessions') return { sessions: [{ id: 'session-current', name: '测试浏览器', current: true, last_seen: iso() }] };
    if (pathname === '/api/native/devices' && method === 'GET') return { devices: clone(mock.nativeDevices) };
    if (pathname.startsWith('/api/native/devices/') && method === 'DELETE') {
      mock.nativeDevices = mock.nativeDevices.filter((entry) => entry.id !== pathname.split('/').at(-1));
      return {};
    }
    const native = pathname.match(/^\/api\/native\/pairing\/([^/]+)(?:\/(approve|reject))?$/);
    if (native) {
      const entry = mock.nativeRequests[native[1]];
      if (entry && !native[2] && method === 'GET') return clone(entry);
      if (entry && native[2] && method === 'POST') {
        entry.status = native[2] === 'approve' ? 'approved' : 'rejected';
        return { status: entry.status };
      }
    }
    mock.unexpected.push(`${method} ${pathname}`);
    return { unsupported: true };
  }
  // Requests stay isolated. Only explicitly queued synthetic streaming tests
  // may reach their own ephemeral 127.0.0.1 server; nothing reaches the real hub.
  await page.route('**/*', async (route) => {
    let gate;
    try {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== origin) {
        mock.unexpected.push(`external request: ${url.origin}${url.pathname}`);
        await route.abort();
        return;
      }
      if (documents[url.pathname]) {
        let document=documents[url.pathname];
        if (options.exposeTestApi && url.pathname==='/app.js') {
          const source=document.body.toString();
          const marker=/  bootstrap\(\);\r?\n\}\)\(\);/;
          assert.match(source,marker);
          // Test-only export in this isolated response; the shipped file remains private.
          document={...document,body:source.replace(marker,'  window.__testApi = api;\n  bootstrap();\n})();')};
        }
        await route.fulfill(document); return;
      }
      if (url.pathname.startsWith('/assets/')) {
        await route.fulfill(url.pathname.endsWith('.css')
          ? { contentType: 'text/css', body: '/* Fonts intentionally isolated. */' }
          : { contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="#777"/></svg>' });
        return;
      }
      if (url.pathname === '/manifest.webmanifest') {
        await route.fulfill({ contentType: 'application/manifest+json', body: '{"name":"Isolated UI test","start_url":"/"}' });
        return;
      }
      if (!url.pathname.startsWith('/api/')) {
        mock.unexpected.push(`unmocked asset: ${url.pathname}`);
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      const record = { method: request.method(), pathname: url.pathname, body: request.postData() ? request.postDataJSON() : null, headers: request.headers() };
      mock.requests.push(record);
      if (record.method==='GET' && options.streamServer?.hasNext(url.pathname)) {
        await route.continue(); return;
      }
      gate = queues.get(`${record.method} ${record.pathname}`)?.shift();
      let result;
      if (gate) {
        gate.begin(record);
        result = await gate.response;
        if (result.commitPreferences && result.status === 200) ordinaryReply(record.method, record.pathname, record.body);
      } else {
        const body = ordinaryReply(record.method, record.pathname, record.body);
        result = { status: body.unauthorized ? 401 : body.unsupported ? 500 : 200, body };
      }
      if (result.abort) await route.abort('failed');
      else await route.fulfill({ status: result.status, contentType: 'application/json', body: JSON.stringify(result.body) });
    } catch (error) {
      if (!mock.closing) mock.pageErrors.push(`route: ${error.message}`);
    } finally { gate?.finish(); }
  });
  const startupGates = options.deferStartup ? {
    bootstrap:holdNext('GET','/api/bootstrap'), me:holdNext('GET','/api/me'), snapshot:holdNext('GET','/api/snapshot'),
  } : null;
  const initialSnapshotGate = startupGates?.snapshot || (options.deferInitialSnapshot ? holdNext('GET', '/api/snapshot') : null);
  await page.goto(`${origin}/#/${options.route || 'tasks'}`);
  if (startupGates) {
    await until(()=>['/api/bootstrap','/api/me'].every((pathname)=>count('GET',pathname)===1),'Both startup identity requests must begin before either response is released');
    assert.equal(count('GET','/api/snapshot'),0,'Unknown authentication must not prefetch task data');
  } else if (mock.authenticated) {
    await until(() => count('GET', '/api/snapshot') >= 1, 'Initial snapshot was not fetched');
    await page.waitForFunction(() => document.querySelector('#connection').hidden && !document.querySelector('#navigation').hidden);
    // The initial loading view intentionally has no disconnected banner.
    // Let the actual snapshot replace it before advancing the polling clock.
    if (!initialSnapshotGate) await page.waitForFunction(() => !document.querySelector('#main [role="status"] .spinner'));
  } else await page.locator('#auth-form').waitFor();
  // Run after the app's synchronous guard. Preserve whether it blocked the
  // gesture, then prevent every test gesture from launching an external app.
  await page.evaluate(() => {
    window.__nativeClicks = [];
    document.addEventListener('click', (event) => {
      const link = event.target.closest('a[data-native-action]');
      if (!link) return;
      window.__nativeClicks.push({ href: link.getAttribute('href'), blocked: event.defaultPrevented, trusted: event.isTrusted });
      event.preventDefault();
    });
  });
  async function settle() {
    await page.clock.runFor(1);
    await page.evaluate(() => Promise.resolve());
    await delay(25);
  }
  async function routeTo(value) {
    const titles = { tasks: '工作台', account: '我的', profile: '个人资料', sync: '个人同步服务', pairing: '添加电脑', sessions: '登录设备', devices: '我的电脑', scanner: '扫一扫', 'pairing-confirm': '确认电脑', 'native-connect': '连接实况 App' };
    await page.evaluate((name) => { location.hash = `#/${name}`; }, value);
    const expected = value.startsWith('native-connect') ? ['连接实况 App', '连接 Monitor'] : [titles[value.split('/')[0]]];
    await page.waitForFunction((titles) => titles.includes(document.querySelector('#header h1')?.textContent), expected);
  }
  async function emitNative(detail) {
    await page.evaluate((value) => window.dispatchEvent(new CustomEvent('agentmonitor:native', { detail: value })), detail);
    await settle();
  }
  async function nativeClicks() { return page.evaluate(() => window.__nativeClicks); }
  async function taskDetail(id = 'codex-one') {
    await page.evaluate((value) => { location.hash = `#/task/${encodeURIComponent(value)}`; }, id);
    await page.locator('[data-page="tasks"]').first().waitFor();
    await settle();
  }
  async function poll() {
    const before = count('GET', '/api/snapshot');
    await page.clock.runFor(5100);
    await until(() => count('GET', '/api/snapshot') > before, 'Scheduled snapshot request did not start');
    await settle();
  }
  async function logout() {
    await routeTo('account');
    await page.locator('[data-action="logout"]').click();
    await page.locator('#confirm-action').click();
    await page.locator('#auth-form').waitFor();
  }
  async function loginAgain() {
    await page.locator('[name="username"]').fill('isolated-new-user');
    await page.locator('[name="password"]').fill('Only-a-fake-test-password');
    await page.locator('#auth-form [type="submit"]').click();
    await page.locator('#navigation').waitFor();
  }
  async function close() {
    mock.closing = true;
    for (const gate of mock.gates) if (gate.pending) gate.cancel();
    await context.close();
  }
  return { page, mock, holdNext, count, routeTo, poll, settle, logout, loginAgain, close, user, emitNative, nativeClicks, taskDetail, initialSnapshotGate, startupGates, streamServer:options.streamServer };
}

async function streamingFixture() {
  const pending=[];
  const server=http.createServer((request,response)=>{
    const index=pending.findIndex((gate)=>request.method==='GET' && request.url===gate.pathname);
    if (index<0) { response.writeHead(404); response.end(); return; }
    const gate=pending.splice(index,1)[0];
    gate.response=response;
    response.on('close',()=>{gate.cancelled=!response.writableFinished;});
    response.writeHead(gate.status,{'Content-Type':'application/json','Cache-Control':'no-store','Connection':'close'});
    response.flushHeaders();
    response.write(' '); // Real headers and a body chunk arrive; JSON is deliberately unfinished.
    gate.begin();
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  return {
    origin:`http://127.0.0.1:${server.address().port}`,
    hasNext:(pathname)=>pending.some((gate)=>gate.pathname===pathname),
    next(pathname='/api/snapshot',status=200) {
      const started=defer();
      const gate={pathname,status,started:started.promise,begin:started.resolve,cancelled:false,response:null,
        finish(body) { if (!gate.response?.destroyed) gate.response.end(JSON.stringify(body)); }
      };
      pending.push(gate); return gate;
    },
    async close() { server.closeAllConnections(); await new Promise((resolve)=>server.close(resolve)); },
  };
}

async function withApp(browser, test, options = {}) {
  const streamServer=options.streamBody ? await streamingFixture() : null;
  let app;
  let deadline;
  try {
    app=await boot(browser,streamServer ? {...options,origin:streamServer.origin,streamServer}:options);
    // Bound even an expected API request that never arrives after a regression.
    await Promise.race([
      test(app),
      new Promise((_, reject) => { deadline = setTimeout(() => reject(new Error('Browser regression exceeded 12 seconds')), 12000); }),
    ]);
    assert.deepEqual(app.mock.unexpected, [], 'All resources/API calls must be isolated and explicitly mocked');
    assert.deepEqual(app.mock.pageErrors, [], 'No browser script or intercepted-request errors');
  } finally { clearTimeout(deadline); await app?.close(); await streamServer?.close(); }
}
async function assertFilter(page, expected) {
  await page.locator('[data-task-view="all"]').first().click();
  await page.locator(`[data-filter="${expected}"][aria-pressed="true"]`).waitFor();
  await page.waitForFunction((filter) => {
    const titles = [...document.querySelectorAll('.task-title')].map((node) => node.textContent);
    return filter === 'all' ? titles.length === 2 : titles.length === 1 && titles[0] === (filter === 'claude' ? 'Claude 测试任务' : 'Codex 测试任务');
  }, expected);
  const titles = await page.locator('.task-title').allTextContents();
  if (expected === 'all') assert.equal(titles.length, 2);
  else assert.deepEqual(titles, [expected === 'claude' ? 'Claude 测试任务' : 'Codex 测试任务']);
}

async function delayedOutputAfterNavigation(app, success) {
  const { page, holdNext, routeTo, settle, count } = app;
  await routeTo('sync');
  const pending = holdNext('PATCH', '/api/preferences');
  const original = await page.locator('#sync-output').elementHandle();
  await page.locator('#sync-output').check();
  assert.deepEqual((await pending.started).body, { sync_output: true });
  await routeTo('account');
  await routeTo('sync');
  const current = await page.locator('#sync-output').elementHandle();
  assert.equal(await original.evaluate((element) => element.isConnected), false);
  assert.equal(await current.evaluate((element) => element.disabled), true);
  // Keep the ensuing refresh pending so the save completion itself must unlock it.
  if (success) holdNext('GET', '/api/snapshot');
  await pending.reply(success ? {} : { detail: '模拟保存失败' }, success ? 200 : 503, success);
  await settle();
  await page.waitForFunction(() => !document.querySelector('#sync-output').disabled);
  assert.equal(await current.evaluate((element) => element === document.querySelector('#sync-output')), true);
  assert.equal(await current.evaluate((element) => element.checked), success);
  assert.equal(count('PATCH', '/api/preferences'), 1);
}
async function delayedPairingAfterNavigation({ page, holdNext, routeTo, settle, count }) {
  await routeTo('pairing');
  await page.locator('.manual-pairing > summary').click();
  const pending = holdNext('POST', '/api/pairing');
  await page.locator('[data-action="create-pairing"]').click();
  await pending.started;
  await routeTo('account');
  const accountMarkup = await page.locator('#main').innerHTML();
  await pending.reply({ code: 'PAIR-TEST-123', expires_at: iso(600000) });
  await settle();
  assert.equal(await page.locator('#header h1').textContent(), '我的');
  assert.equal(await page.locator('#main').innerHTML(), accountMarkup);
  await routeTo('pairing');
  await page.locator('.manual-pairing > summary').click();
  await page.getByText('PAIR-TEST-123', { exact: true }).waitFor();
  assert.equal(count('POST', '/api/pairing'), 1);
}
async function unchangedPollPreservesNodes({ page, poll, routeTo }) {
  assert.equal(await page.locator('[data-filter]').count(), 3);
  assert.equal(await page.locator('#navigation button').count(), 3);
  const references = await page.evaluateHandle(() => ({
    cards: [...document.querySelectorAll('button.task')],
    filters: [...document.querySelectorAll('[data-filter]')],
    nav: document.querySelector('#navigation'),
    navButtons: [...document.querySelectorAll('#navigation button')],
  }));
  await poll();
  await poll();
  const retained = await references.evaluate((saved) => ({
    cards: saved.cards.every((element, index) => element === document.querySelectorAll('button.task')[index]),
    filters: saved.filters.every((element, index) => element === document.querySelectorAll('[data-filter]')[index]),
    nav: saved.nav === document.querySelector('#navigation'),
    navButtons: saved.navButtons.every((element, index) => element === document.querySelectorAll('#navigation button')[index]),
  }));
  assert.deepEqual(retained, { cards: true, filters: true, nav: true, navButtons: true });
  await routeTo('account');
  await routeTo('devices');
  assert.equal(await references.evaluate((saved) => saved.navButtons.every((element, index) => element === document.querySelectorAll('#navigation button')[index])), true, 'Navigation buttons survive route changes');
}
async function changedCardPatchedInPlace({ page, mock, poll }) {
  const references = await page.evaluateHandle(() => {
    const changed = document.querySelector('button.task[data-id="codex-one"]');
    const untouched = document.querySelector('button.task[data-id="claude-one"]');
    return { changed, untouched, untouchedMarkup: untouched.outerHTML, filter: document.querySelector('.filter'), navigation: document.querySelector('#navigation') };
  });
  mock.snapshot.tasks[0].status = 'completed';
  mock.snapshot.tasks[0].updated_at = iso();
  await poll();
  await page.locator('button.task[data-id="codex-one"] .status').filter({ hasText: '本轮结束' }).waitFor();
  const result = await references.evaluate((saved) => ({
    changedRetained: saved.changed === document.querySelector('button.task[data-id="codex-one"]'),
    untouchedRetained: saved.untouched === document.querySelector('button.task[data-id="claude-one"]'),
    untouchedMarkup: saved.untouched.outerHTML === saved.untouchedMarkup,
    filterRetained: saved.filter === document.querySelector('.filter'),
    navRetained: saved.navigation === document.querySelector('#navigation'),
    updatedTime: saved.changed.textContent.includes('刚刚'),
  }));
  assert.deepEqual(result, { changedRetained: true, untouchedRetained: true, untouchedMarkup: true, filterRetained: true, navRetained: true, updatedTime: true });
}
async function optimisticFilterCoalesces({ page, mock, holdNext, count, settle, poll }) {
  const first = holdNext('PATCH', '/api/preferences');
  const last = holdNext('PATCH', '/api/preferences');
  await page.locator('[data-filter="claude"]').click();
  await first.started;
  await assertFilter(page, 'claude');
  await page.locator('[data-filter="all"]').click();
  await assertFilter(page, 'all');
  await page.locator('[data-filter="codex"]').click();
  await assertFilter(page, 'codex');
  assert.equal(count('PATCH', '/api/preferences'), 1, 'Save requests are serialized while UI remains interactive');
  await first.reply({}, 200, true);
  assert.deepEqual((await last.started).body, { tool_filter: 'codex' });
  await assertFilter(page, 'codex');
  await last.reply({}, 200, true);
  await settle();
  assert.equal(mock.preferences.tool_filter, 'codex');
  assert.deepEqual(mock.requests.filter((request) => request.method === 'PATCH').map((request) => request.body.tool_filter), ['claude', 'codex']);
  await poll();
  await assertFilter(page, 'codex');
}
async function failedFilterRemainsSelected({ page, mock, holdNext, settle, poll }) {
  const pending = holdNext('PATCH', '/api/preferences');
  await page.locator('[data-filter="claude"]').click();
  await pending.started;
  await assertFilter(page, 'claude');
  await pending.reply({ detail: '模拟筛选保存失败' }, 503);
  await settle();
  await assertFilter(page, 'claude');
  assert.equal(mock.preferences.tool_filter, 'all');
  assert.equal(await page.locator('#notice').isVisible(), true);
  assert.match(await page.locator('#notice').textContent(), /暂未同步/);
  // A failed save must also release the queue for the next selection.
  await page.locator('[data-filter="codex"]').click();
  await assertFilter(page, 'codex');
  await until(() => mock.preferences.tool_filter === 'codex', 'Filter queue stayed blocked after a failed save');
  await poll();
  await assertFilter(page, 'codex');
}
async function anotherDevicesPreferenceCannotChangeCurrentView({ page, mock, settle, poll }) {
  await page.locator('[data-filter="claude"]').click();
  await until(() => mock.preferences.tool_filter === 'claude', 'The filter PATCH did not commit');
  await settle();
  await assertFilter(page, 'claude');
  // A second device saves its own default while this view remains open.
  mock.preferences.tool_filter = 'all';
  await poll();
  await assertFilter(page, 'claude');
  mock.preferences.tool_filter = 'codex';
  await poll();
  await assertFilter(page, 'claude');
  // A fresh bootstrap still restores the shared default.
  await page.reload();
  await assertFilter(page, 'codex');
}
async function selectingCurrentAllPinsThisView({ page, mock, count, poll }) {
  await assertFilter(page, 'all');
  await page.locator('[data-filter="all"]').click();
  assert.equal(count('PATCH', '/api/preferences'), 0, 'Selecting the existing value needs no redundant write');
  mock.preferences.tool_filter = 'claude';
  await poll();
  await assertFilter(page, 'all');
  assert.equal(count('PATCH', '/api/preferences'), 0, 'A local view choice does not continuously rewrite another device\'s default');
  await page.reload();
  await assertFilter(page, 'claude');
}
async function unauthorizedFilterSaveClearsRetry({ page, mock, holdNext, count, settle, loginAgain, poll }) {
  const pending = holdNext('PATCH', '/api/preferences');
  await page.locator('[data-filter="claude"]').click();
  await pending.started;
  mock.authenticated = false;
  await pending.reply({ detail: '模拟当前会话过期' }, 401);
  await page.locator('#auth-form').waitFor();
  const requestsAfterLogout = mock.requests.length;
  await page.clock.runFor(15000);
  await settle();
  assert.equal(mock.requests.length, requestsAfterLogout, 'The logged-out session must not poll or retry its filter save');
  await loginAgain();
  await assertFilter(page, 'codex');
  mock.preferences.tool_filter = 'all';
  await poll();
  await assertFilter(page, 'all');
  assert.equal(count('PATCH', '/api/preferences'), 1, 'Login clears both the previous local choice and its pending save');
}
async function offlineFilterRetriesLatestAfterRefresh({ page, mock, holdNext, count, settle, poll }) {
  const first = holdNext('PATCH', '/api/preferences');
  await page.locator('[data-filter="claude"]').click();
  await first.started;
  await first.failNetwork();
  await settle();
  await assertFilter(page, 'claude');
  assert.equal(count('PATCH', '/api/preferences'), 1, 'A failed save must not spin in an immediate retry loop');

  const second = holdNext('PATCH', '/api/preferences');
  await page.locator('[data-filter="codex"]').click();
  assert.deepEqual((await second.started).body, { tool_filter: 'codex' });
  await second.failNetwork();
  await settle();
  await assertFilter(page, 'codex');
  assert.equal(mock.preferences.tool_filter, 'all', 'Both writes failed; the server still has its old default');
  assert.equal(count('PATCH', '/api/preferences'), 2);

  const recovery = holdNext('PATCH', '/api/preferences');
  await poll();
  assert.deepEqual((await recovery.started).body, { tool_filter: 'codex' }, 'A successful refresh retries only the latest selection');
  await assertFilter(page, 'codex');
  assert.equal(mock.preferences.tool_filter, 'all', 'The old /me value must not change the view while its retry is pending');
  await recovery.reply({}, 200, true);
  await settle();
  assert.equal(mock.preferences.tool_filter, 'codex');
  await poll();
  await assertFilter(page, 'codex');
  assert.deepEqual(mock.requests.filter((request) => request.method === 'PATCH').map((request) => request.body.tool_filter), ['claude', 'codex', 'codex']);
}
async function stalePreferencesCannotUndoSavedFilter({ page, mock, holdNext, settle, poll }, refreshDuringSave) {
  // Capture what /me saw before the PATCH was committed. Deliver it only
  // after the save has completed, when filterSaving is already false.
  const previousMe = { user: { username: mock.username }, csrf_token: mock.csrf, preferences: clone(mock.preferences) };
  const staleMe = holdNext('GET', '/api/me');
  let save;
  if (refreshDuringSave) {
    save = holdNext('PATCH', '/api/preferences');
    await page.locator('[data-filter="codex"]').click();
    await save.started;
    await assertFilter(page, 'codex');
    await poll();
    await staleMe.started;
    await save.reply({}, 200, true);
  } else {
    await poll();
    await staleMe.started;
    await page.locator('[data-filter="codex"]').click();
  }
  await until(() => mock.preferences.tool_filter === 'codex', 'The filter PATCH did not commit');
  await settle();
  await assertFilter(page, 'codex');
  await staleMe.reply(previousMe);
  await settle();
  await assertFilter(page, 'codex');
  await poll();
  await assertFilter(page, 'codex');
}
async function oldSnapshotCannotOverwriteNewLogin({ page, mock, holdNext, poll, logout, loginAgain, settle }) {
  const oldSnapshot = clone(mock.snapshot);
  oldSnapshot.tasks[0].title = 'OLD-SESSION-SNAPSHOT-MUST-NOT-RETURN';
  const pending = holdNext('GET', '/api/snapshot');
  await poll();
  await pending.started;
  await logout();
  await loginAgain();
  const evidence = await page.evaluateHandle(() => {
    const result = { leaked: false };
    const observe = () => { if (document.querySelector('#main').textContent.includes('OLD-SESSION-SNAPSHOT-MUST-NOT-RETURN')) result.leaked = true; };
    result.observer = new MutationObserver(observe);
    result.observer.observe(document.querySelector('#main'), { subtree: true, childList: true, characterData: true });
    observe();
    return result;
  });
  await pending.reply(oldSnapshot);
  await settle();
  assert.equal(await evidence.evaluate((value) => value.leaked), false);
  assert.equal(await page.locator('#auth-form').count(), 0);
  await poll();
  await assertFilter(page, 'codex');
  assert.equal(await evidence.evaluate((value) => value.leaked), false, 'Even a transient old-session render is forbidden');
  await evidence.evaluate((value) => value.observer.disconnect());
}
async function oldUnauthorizedResponseCannotLogoutNewLogin({ page, holdNext, routeTo, logout, loginAgain, settle }) {
  const pending = holdNext('GET', '/api/sessions');
  await routeTo('sessions');
  await pending.started;
  await logout();
  await loginAgain();
  await assertFilter(page, 'codex');
  await pending.reply({ detail: '模拟旧会话过期' }, 401);
  await settle();
  assert.equal(await page.locator('#auth-form').count(), 0, 'A late 401 belongs only to the session that started it');
  assert.equal(await page.locator('#navigation').isVisible(), true);
  await assertFilter(page, 'codex');
}

function statusSnapshot() {
  const snapshot = initialSnapshot(), base = snapshot.tasks[0];
  snapshot.tasks = [
    ['running', 'codex', false], ['waiting', 'claude', false], ['error', 'claude', false],
    ['completed', 'codex', false], ['unknown', 'claude', false], ['idle', 'codex', false],
    ['running', 'claude', true],
  ].map(([status, tool, archived], index) => ({ ...base, id: `status-${index}`, title: `${status}-${index}`, status, tool, archived }));
  return snapshot;
}
async function taskIds(page) {
  return page.locator('button.task').evaluateAll((cards) => cards.map((card) => card.dataset.id).sort());
}
async function taskView(page, value) {
  await page.locator(`.task-views [data-task-view="${value}"]`).click();
  await page.locator(`.task-views [data-task-view="${value}"][aria-pressed="true"]`).waitFor();
}
async function activeAndBrandFiltersCompose({ page, count, poll }) {
  assert.equal(await page.locator('[data-task-view="active"][aria-pressed="true"]').count(), 1);
  assert.deepEqual(await taskIds(page), ['status-0', 'status-1', 'status-2']);
  assert.deepEqual(await page.locator('.filter .count').allTextContents(), ['3', '2', '1']);
  await page.locator('[data-filter="claude"]').click();
  assert.deepEqual(await taskIds(page), ['status-1', 'status-2']);
  await taskView(page, 'all');
  assert.deepEqual(await taskIds(page), ['status-1', 'status-2', 'status-4']);
  assert.deepEqual(await page.locator('.filter .count').allTextContents(), ['6', '3', '3']);
  await taskView(page, 'archived');
  assert.deepEqual(await taskIds(page), ['status-6']);
  await poll();
  assert.deepEqual(await taskIds(page), ['status-6']);
  await page.locator('[data-filter="codex"]').click();
  assert.deepEqual(await taskIds(page), []);
  assert.equal(await page.locator('[data-task-view="archived"][aria-pressed="true"]').count(), 1);
  assert.equal(count('PATCH', '/api/tasks/status-6/archive'), 0, 'Changing filters must not archive or restore anything');
}
async function archiveSurvivesOldSnapshotsAndCanRestore({ page, mock, holdNext, poll, settle }) {
  const original = clone(mock.snapshot);
  const stale = holdNext('GET', '/api/snapshot');
  await poll(); await stale.started;
  const save = holdNext('PATCH', '/api/tasks/codex-one/archive');
  await page.locator('[data-action="archive-task"][data-id="codex-one"]').click();
  assert.deepEqual((await save.started).body, { archived: true });
  assert.deepEqual(await taskIds(page), [], 'Archiving immediately removes a task from active');
  await save.reply({ ok: true, archived: true }, 200, true);
  await stale.reply(original); await settle();
  assert.deepEqual(await taskIds(page), [], 'An earlier snapshot cannot resurrect the archived task');
  await taskView(page, 'all');
  assert.deepEqual(await taskIds(page), ['claude-one']);
  await taskView(page, 'archived');
  assert.deepEqual(await taskIds(page), ['codex-one']);
  const beforeRestore = clone(mock.snapshot);
  const staleRestore = holdNext('GET', '/api/snapshot');
  await poll(); await staleRestore.started;
  const restore = holdNext('PATCH', '/api/tasks/codex-one/archive');
  await page.locator('[data-action="archive-task"][data-id="codex-one"]').click();
  assert.deepEqual((await restore.started).body, { archived: false });
  await restore.reply({ ok: true, archived: false }, 200, true);
  await staleRestore.reply(beforeRestore); await settle();
  assert.deepEqual(await taskIds(page), []);
  await taskView(page, 'active');
  assert.deepEqual(await taskIds(page), ['codex-one']);
  await poll();
  assert.deepEqual(await taskIds(page), ['codex-one']);
}
async function rejectedArchiveRollsBack({ page, holdNext, settle }) {
  const save = holdNext('PATCH', '/api/tasks/codex-one/archive');
  await page.locator('[data-action="archive-task"][data-id="codex-one"]').click();
  await save.started;
  assert.deepEqual(await taskIds(page), []);
  await save.reply({ detail: '隔离测试：归档保存失败' }, 503);
  await settle();
  assert.deepEqual(await taskIds(page), ['codex-one']);
  await taskView(page, 'archived');
  assert.deepEqual(await taskIds(page), []);
  assert.match(await page.locator('#notice').textContent(), /归档保存失败/);
}
async function freshArchiveChangesFromAnotherDeviceApply({ page, mock, poll, settle }) {
  await page.locator('[data-action="archive-task"][data-id="codex-one"]').click();
  await until(() => mock.snapshot.tasks[0].archived === true, 'Archive did not commit');
  await settle();
  assert.deepEqual(await taskIds(page), []);
  // Another device restores it after our save, before the next fresh poll.
  mock.snapshot.tasks[0].archived = false;
  await poll();
  assert.deepEqual(await taskIds(page), ['codex-one'], 'Local archive protection must not hide a later remote restoration');
  mock.snapshot.tasks[0].archived = true;
  await poll();
  assert.deepEqual(await taskIds(page), []);
}
async function uploadAvatar(page) {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.width = 80; canvas.height = 120;
    const context = canvas.getContext('2d'); context.fillStyle = '#cc7755'; context.fillRect(0, 0, 80, 120);
    return canvas.toDataURL('image/png');
  });
  await page.locator('#avatar-file').setInputFiles({ name: 'isolated-avatar.png', mimeType: 'image/png', buffer: Buffer.from(dataUrl.split(',')[1], 'base64') });
  await page.waitForFunction(() => document.querySelector('.avatar-editor img')?.src.startsWith('data:image/jpeg;base64,') && !document.querySelector('#display-name').disabled);
  const dimensions = await page.locator('.avatar-editor img').evaluate(async (image) => { await image.decode(); return [image.naturalWidth, image.naturalHeight]; });
  assert.deepEqual(dimensions, [256, 256], 'The real image helper crops/resizes the uploaded image before saving');
}
async function profileDraftAndDelayedSave({ page, mock, user, holdNext, routeTo, poll, settle }) {
  await routeTo('profile');
  await page.locator('#display-name').fill('我的新昵称');
  await uploadAvatar(page);
  const editor = await page.locator('#display-name').elementHandle();
  await page.locator('#display-name').focus();
  await poll();
  assert.equal(await page.locator('#display-name').inputValue(), '我的新昵称');
  assert.equal(await editor.evaluate((node) => document.activeElement === node), true, 'Polling must not steal editing focus');
  const save = holdNext('PATCH', '/api/profile');
  await page.locator('#profile-form [type="submit"]').click();
  const request = await save.started;
  assert.equal(request.body.display_name, '我的新昵称');
  assert.match(request.body.avatar, /^data:image\/jpeg;base64,/);
  assert.equal(request.headers['x-csrf-token'], mock.csrf);
  await routeTo('account');
  assert.equal(await page.locator('.profile h2').textContent(), '测试用户');
  await save.reply({ user: { ...user(), ...request.body } }, 200, true);
  await settle();
  assert.equal(await page.locator('#header h1').textContent(), '我的', 'Profile save must not reopen the editor');
  assert.equal(await page.locator('.profile h2').textContent(), '我的新昵称');
  assert.equal(await page.locator('.profile .avatar img').getAttribute('src'), request.body.avatar);
  await poll();
  assert.equal(await page.locator('.profile h2').textContent(), '我的新昵称');
}
async function staleMeCannotUndoProfileSave({ page, mock, user, holdNext, routeTo, poll, settle }) {
  const previousMe = { user: user(), csrf_token: mock.csrf, preferences: clone(mock.preferences) };
  const stale = holdNext('GET', '/api/me');
  await poll(); await stale.started;
  await routeTo('profile');
  await page.locator('#display-name').fill('应保留的新昵称');
  await page.locator('#profile-form [type="submit"]').click();
  await page.waitForFunction(() => document.querySelector('.profile h2')?.textContent === '应保留的新昵称');
  await stale.reply(previousMe); await settle();
  assert.equal(await page.locator('.profile h2').textContent(), '应保留的新昵称', 'A /me request started before editing cannot undo the saved profile');
}
async function oldProfileSaveCannotCrossSession(app, status) {
  const { page, holdNext, routeTo, logout, loginAgain, settle } = app;
  await routeTo('profile');
  await page.locator('#display-name').fill('OLD-PROFILE-MUST-NOT-RETURN');
  const save = holdNext('PATCH', '/api/profile');
  await page.locator('#profile-form [type="submit"]').click(); await save.started;
  await logout(); await loginAgain();
  await routeTo('account');
  assert.equal(await page.locator('.profile h2').textContent(), '新会话用户');
  await save.reply(status === 200 ? { user: { username: 'isolated-user', display_name: 'OLD-PROFILE-MUST-NOT-RETURN', avatar: '' } } : { detail: '旧会话已过期' }, status);
  await settle();
  assert.equal(await page.locator('#auth-form').count(), 0);
  assert.equal(await page.locator('#header h1').textContent(), '我的');
  assert.equal(await page.locator('.profile h2').textContent(), '新会话用户');
  await routeTo('profile');
  assert.equal(await page.locator('#display-name').inputValue(), '新会话用户');
  assert.equal(await page.locator('#display-name').isEnabled(), true);
}
async function qrImageDecodesWithoutAutoApproval({ page, count, routeTo, poll }) {
  await routeTo('scanner');
  await page.locator('#qr-file').setInputFiles({ name: 'real-cli-pairing.png', mimeType: 'image/png', buffer: qrPng });
  await page.getByText('扫码测试电脑', { exact: true }).waitFor();
  assert.equal(new URL(page.url()).hash, `#/pairing-confirm/${QR_ID}`);
  assert.equal(count('GET', QR_PATH), 1);
  assert.equal(count('POST', `${QR_PATH}/approve`), 0, 'Decoding a QR only loads the confirmation details');
  await poll();
  assert.equal(count('POST', `${QR_PATH}/approve`), 0, 'Polling does not automatically approve the computer');
  await page.locator('[data-action="approve-pairing"]').click();
  await page.getByText('已确认连接', { exact: true }).waitFor();
  assert.equal(count('POST', `${QR_PATH}/approve`), 1);
}
async function foreignAndSecretUrlsAreRejected({ page, mock, count, routeTo }) {
  await routeTo('scanner');
  await page.locator('#qr-file').setInputFiles({ name: 'foreign-cli-pairing.png', mimeType: 'image/png', buffer: foreignQrPng });
  await page.locator('#notice').waitFor({ state: 'visible' });
  assert.equal(new URL(page.url()).hash, '#/scanner', 'A real decoded QR for another service must not navigate');
  await page.locator('.manual-pairing > summary').click();
  for (const url of [
    `https://other.example.test/#/pairing-confirm/${QR_ID}`,
    `${ORIGIN}/?poll_secret=SHOULD-NOT-BE-ACCEPTED#/pairing-confirm/${QR_ID}`,
    `${ORIGIN}/#/pairing-confirm/${QR_ID}?secret=SHOULD-NOT-BE-ACCEPTED`,
  ]) {
    await page.locator('#pairing-link').fill(url);
    await page.locator('[data-action="open-pairing-link"]').click();
    assert.equal(new URL(page.url()).hash, '#/scanner');
    assert.equal(await page.locator('#notice').isVisible(), true);
  }
  assert.equal(count('GET', QR_PATH), 0);
  assert.equal(mock.requests.filter((request) => request.pathname.includes('/pairing/requests/')).length, 0);
}
async function qrRejectIsExplicitAndLateApprovalDoesNotNavigate({ page, count, routeTo, holdNext, settle }) {
  await routeTo(`pairing-confirm/${QR_ID}`);
  await page.locator('[data-action="reject-pairing"]').waitFor();
  assert.equal(count('POST', `${QR_PATH}/reject`), 0);
  await page.locator('[data-action="reject-pairing"]').click();
  await page.getByText('已取消配对', { exact: true }).waitFor();
  assert.equal(count('POST', `${QR_PATH}/reject`), 1);
  assert.equal(count('POST', `${QR_PATH}/approve`), 0);
  await routeTo('scanner');
  const request = holdNext('GET', QR_PATH);
  await routeTo(`pairing-confirm/${QR_ID}`); await request.started;
  await request.reply({ request_id: QR_ID, name: '扫码测试电脑', platform: 'Windows', expires_at: iso(600000), status: 'pending' });
  await page.locator('[data-action="approve-pairing"]').waitFor();
  const approved = holdNext('POST', `${QR_PATH}/approve`);
  await page.locator('[data-action="approve-pairing"]').click(); await approved.started;
  await routeTo('account');
  await approved.reply({ ok: true, status: 'approved' }); await settle();
  assert.equal(await page.locator('#header h1').textContent(), '我的');
}
async function loginPreservesPairingDeepLink({ page, count, loginAgain, mock }) {
  assert.equal(count('GET', QR_PATH), 0, 'Pairing details are private until login');
  assert.equal(new URL(page.url()).hash, `#/pairing-confirm/${QR_ID}`);
  await loginAgain();
  await page.getByText('扫码测试电脑', { exact: true }).waitFor();
  assert.equal(new URL(page.url()).hash, `#/pairing-confirm/${QR_ID}`);
  assert.equal(count('GET', QR_PATH), 1);
  assert.equal(count('POST', `${QR_PATH}/approve`), 0);
  await page.locator('[data-action="approve-pairing"]').click();
  await page.getByText('已确认连接', { exact: true }).waitFor();
  const approval = mock.requests.find((item) => item.pathname === `${QR_PATH}/approve`);
  assert.equal(approval.headers['x-csrf-token'], 'isolated-csrf-new-session');
}
async function oldQrDetailsCannotReopenPage({ page, holdNext, routeTo, settle }) {
  const pending = holdNext('GET', QR_PATH);
  await routeTo(`pairing-confirm/${QR_ID}`); await pending.started;
  await routeTo('account');
  await pending.reply({ request_id: QR_ID, name: 'LATE-QR-DETAILS', platform: 'Windows', expires_at: iso(600000), status: 'pending' });
  await settle();
  assert.equal(await page.locator('#header h1').textContent(), '我的');
  assert.equal(await page.getByText('LATE-QR-DETAILS', { exact: true }).count(), 0);
}
async function pairingApprovalSurvivesReturnAndOlderDetails({ page, mock, holdNext, routeTo, settle, count }) {
  const oldDetails = clone(mock.pairingRequests[QR_ID]);
  await routeTo(`pairing-confirm/${QR_ID}`);
  await page.locator('[data-action="approve-pairing"]').waitFor();
  const approval = holdNext('POST', `${QR_PATH}/approve`);
  await page.locator('[data-action="approve-pairing"]').click(); await approval.started;
  await routeTo('account');
  const reload = holdNext('GET', QR_PATH);
  await routeTo(`pairing-confirm/${QR_ID}`); await reload.started;
  // On re-entry, the details have been cleared and are still being fetched.
  await approval.reply({ ok: true, status: 'approved' }, 200, true); await settle();
  await page.getByText('已确认连接', { exact: true }).waitFor();
  await reload.reply(oldDetails); await settle();
  assert.equal(await page.getByText('已确认连接', { exact: true }).count(), 1);
  assert.equal(await page.locator('[data-action="approve-pairing"]').count(), 0, 'A stale GET must not offer a second approval');
  assert.equal(count('POST', `${QR_PATH}/approve`), 1);
}
async function approvalForPreviousQrDoesNotCancelNextQrLoad({ page, mock, holdNext, routeTo, settle }) {
  const secondId = 'B'.repeat(32), secondPath = `/api/pairing/requests/${secondId}`;
  const secondDetails = { ...clone(mock.pairingRequests[QR_ID]), request_id: secondId, name: '另一台电脑' };
  mock.pairingRequests[secondId] = secondDetails;
  await routeTo(`pairing-confirm/${QR_ID}`);
  await page.locator('[data-action="approve-pairing"]').waitFor();
  const approval = holdNext('POST', `${QR_PATH}/approve`);
  await page.locator('[data-action="approve-pairing"]').click(); await approval.started;
  const second = holdNext('GET', secondPath);
  await routeTo(`pairing-confirm/${secondId}`); await second.started;
  await approval.reply({ ok: true, status: 'approved' }, 200, true); await settle();
  await second.reply(secondDetails); await settle();
  await page.getByText('另一台电脑', { exact: true }).waitFor();
  assert.equal(new URL(page.url()).hash, `#/pairing-confirm/${secondId}`);
  assert.equal(await page.locator('[data-action="approve-pairing"]').isEnabled(), true);
  assert.equal(await page.getByText('已确认连接', { exact: true }).count(), 0);
}

async function nativeConfirmationIsExplicit({page, mock, routeTo, count}, approve) {
  mock.nativeRequests[NATIVE_ID].device_name = '<img src=x onerror=alert(1)> 测试手机';
  await routeTo(`native-connect/${NATIVE_ID}`);
  await page.getByText(mock.nativeRequests[NATIVE_ID].device_name, {exact:true}).waitFor();
  assert.equal(await page.locator('.pairing-confirm-card img').count(), 0);
  assert.equal(count('POST', `${NATIVE_PATH}/approve`), 0);
  assert.equal(await page.getByRole('link', {name:'返回实况 App'}).count(), 0);
  await page.locator(`[data-action="${approve ? 'approve' : 'reject'}-native"]`).click();
  await page.getByText(approve ? '已确认连接' : '已取消连接', {exact:true}).waitFor();
  const request = mock.requests.find((entry) => entry.pathname === `${NATIVE_PATH}/${approve ? 'approve' : 'reject'}`);
  assert.equal(request.headers['x-csrf-token'], mock.csrf);
  assert.equal(request.body, null, 'Legacy read-only approval must not silently request full App permissions');
  assert.equal(new URL(page.url()).hash, `#/native-connect/${NATIVE_ID}`, 'No automatic external-app navigation');
  if (approve) assert.equal(await page.getByRole('link', {name:'返回实况 App'}).getAttribute('href'), `agentmonitor://paired?request_id=${NATIVE_ID}`);
  else assert.equal(await page.getByRole('link', {name:'返回实况 App'}).count(), 0);
}
async function nativeLoginPreservesRequest({page, loginAgain, count}) {
  await loginAgain();
  await page.getByText('实况测试手机', {exact:true}).waitFor();
  assert.equal(new URL(page.url()).hash, `#/native-connect/${NATIVE_ID}`);
  assert.equal(count('POST', `${NATIVE_PATH}/approve`), 0);
}
async function nativeGoogleReturnPath({page, holdNext}) {
  const pending = holdNext('POST', '/api/auth/google/start');
  await page.locator('[data-action="google-login"]').click();
  const request = await pending.started;
  assert.deepEqual(request.body, {mode:'login', return_path:`/#/native-connect/${NATIVE_ID}`});
  await pending.reply({detail:'Isolated Google response'}, 503);
  await page.locator('#notice').waitFor({state:'visible'});
  assert.match(await page.locator('#notice').textContent(), /Isolated Google response/);
}
async function nativeApprovalSurvivesStaleDetails({page, mock, routeTo, holdNext, settle}) {
  const previous = clone(mock.nativeRequests[NATIVE_ID]);
  await routeTo(`native-connect/${NATIVE_ID}`);
  await page.locator('[data-action="approve-native"]').waitFor();
  const approval = holdNext('POST', `${NATIVE_PATH}/approve`);
  await page.locator('[data-action="approve-native"]').click(); await approval.started;
  await routeTo('account');
  const oldGet = holdNext('GET', NATIVE_PATH);
  await routeTo(`native-connect/${NATIVE_ID}`); await oldGet.started;
  await approval.reply({status:'approved'}, 200, true); await settle();
  await page.getByRole('link', {name:'返回实况 App'}).waitFor();
  await oldGet.reply(previous); await settle();
  assert.equal(await page.locator('[data-action="approve-native"]').count(), 0);
  assert.equal(await page.getByRole('link', {name:'返回实况 App'}).count(), 1);
}
async function nativePreviousApprovalCannotReplaceNext({page, mock, routeTo, holdNext, settle}) {
  const nextId = 'R'.repeat(32), nextPath = `/api/native/pairing/${nextId}`;
  const next = {...clone(mock.nativeRequests[NATIVE_ID]), request_id:nextId, device_name:'另一台实况手机'};
  mock.nativeRequests[nextId] = next;
  await routeTo(`native-connect/${NATIVE_ID}`); await page.locator('[data-action="approve-native"]').waitFor();
  const approval = holdNext('POST', `${NATIVE_PATH}/approve`);
  await page.locator('[data-action="approve-native"]').click(); await approval.started;
  const loading = holdNext('GET', nextPath);
  await routeTo(`native-connect/${nextId}`); await loading.started;
  await approval.reply({status:'approved'}, 200, true); await loading.reply(next); await settle();
  await page.getByText('另一台实况手机', {exact:true}).waitFor();
  assert.equal(await page.locator('[data-action="approve-native"]').isEnabled(), true);
  assert.equal(await page.getByRole('link', {name:'返回实况 App'}).count(), 0);
}
async function nativeExpiryDisablesApproval({page, mock, routeTo, count}) {
  mock.nativeRequests[NATIVE_ID].expires_at = iso(5000);
  await routeTo(`native-connect/${NATIVE_ID}`); await page.locator('[data-action="approve-native"]').waitFor();
  await page.clock.runFor(6100);
  await page.getByText('连接链接已过期', {exact:true}).waitFor();
  assert.equal(await page.locator('[data-action="approve-native"]').count(), 0);
  assert.equal(count('POST', `${NATIVE_PATH}/approve`), 0);
}
async function nativeDeviceRevocation({page, mock, routeTo, count}) {
  mock.nativeDevices = [{id:NATIVE_ID, name:'已连接的实况手机', last_seen:iso(), expires_at:iso(100000)}];
  await routeTo('sessions'); await page.getByText('已连接的实况手机', {exact:true}).waitFor();
  await page.locator('[data-action="revoke-native"]').click();
  assert.equal(count('DELETE', `/api/native/devices/${NATIVE_ID}`), 0);
  await page.locator('#confirm-action').click();
  await page.getByText('已连接的实况手机', {exact:true}).waitFor({state:'detached'});
  const request = mock.requests.find((entry) => entry.method === 'DELETE' && entry.pathname === `/api/native/devices/${NATIVE_ID}`);
  assert.equal(request.headers['x-csrf-token'], mock.csrf);
}
async function nativeDeviceListCannotCrossSession({page, routeTo, holdNext, logout, loginAgain, settle}) {
  const previous = holdNext('GET', '/api/native/devices');
  await routeTo('sessions'); await previous.started;
  await logout(); await loginAgain();
  await routeTo('sessions'); await page.getByText('测试浏览器', {exact:true}).waitFor();
  await previous.reply({devices:[{id:NATIVE_ID, name:'旧会话私有实况设备', last_seen:iso(), expires_at:iso(100000)}]});
  await settle();
  assert.equal(await page.getByText('旧会话私有实况设备', {exact:true}).count(), 0);
}

const nativeIdle = (connected = true) => ({ version: 1, connected, taskId: '', endsAt: 0, status: 'idle' });
const nativeTracking = (taskId = 'codex-one', status = 'tracking') => ({ version: 1, connected: true, taskId, endsAt: NOW + 900000, status });
const fullConsent = () => ({ mode: 'full_app', consent_version: 'full_app_v1', scopes: ['task_status', 'workspace_read', 'workspace_write'] });

async function nativeBridgeIsStrictAndNotInferred({page, emitNative, routeTo, taskDetail}) {
  await page.evaluate(() => {
    localStorage.setItem('native', 'true');
    history.replaceState(null, '', '/?native=1#/tasks');
    Object.defineProperty(navigator, 'userAgent', { value: 'AgentMonitor Android Native WebView' });
  });
  await taskDetail();
  assert.equal(await page.locator('[data-native-action]').count(), 0, 'Hints cannot create native capability');
  for (const detail of [null, [], {}, {...nativeIdle(), version: 2}, {...nativeIdle(), connected: 1},
    {...nativeIdle(), reader_token: 'must-never-be-rendered'}, {...nativeIdle(), endsAt: -1},
    {...nativeIdle(), endsAt: 1.5}, {...nativeIdle(), status: 'approved'}, {...nativeIdle(), taskId: 'bad\n'},
    {...nativeIdle(), taskId: '\ud800'}, {...nativeIdle(), taskId: 'a'.repeat(513)}]) {
    await emitNative(detail);
    assert.equal(await page.locator('[data-native-action]').count(), 0);
  }
  await page.evaluate((detail) => document.dispatchEvent(new CustomEvent('agentmonitor:native', {detail, bubbles:true})), nativeIdle());
  assert.equal(await page.locator('[data-native-action]').count(), 0, 'A document-bubbled event is not the host window event');
  await emitNative(nativeIdle(false));
  await page.getByRole('link', {name:'连接实况', exact:true}).waitFor();
  assert.equal(await page.locator('[data-native-action="track"]').count(), 0);
  await routeTo('account');
  assert.equal(await page.locator('[data-native-action="settings"]').count(), 1);
  assert.equal(await page.locator('[data-action="logout"]').count(), 1, 'Unconnected native keeps ordinary browser logout');
  assert.equal(await page.getByText('must-never-be-rendered').count(), 0);
  assert.equal(await page.evaluate(() => Object.values(localStorage).some((value) => value.includes('taskId'))), false);
}

async function nativeTrackIsDirectAndWaitsForHost({page, mock, emitNative, taskDetail, nativeClicks, poll}) {
  const id = 'codex-one:task /?&中文';
  mock.snapshot.tasks[0].id = id;
  await poll(); await taskDetail(id); await emitNative(nativeIdle());
  const start = page.getByRole('link', {name:'开始跟踪', exact:true});
  assert.equal(await start.getAttribute('href'), `agentmonitor://track?task_id=${encodeURIComponent(id)}`);
  await start.click();
  assert.deepEqual((await nativeClicks()).at(-1), {href:`agentmonitor://track?task_id=${encodeURIComponent(id)}`, blocked:false, trusted:true});
  assert.equal(await page.locator('[data-native-action="stop"]').count(), 0, 'Clicking cannot fabricate an active host state');
  await emitNative(nativeTracking(id));
  await page.getByRole('link', {name:'停止跟踪', exact:true}).waitFor();
  await page.getByText('跟踪中 · 15 分钟', {exact:true}).waitFor();
  const before = await page.locator('[data-native-action="stop"]').elementHandle();
  await poll();
  assert.equal(await before.evaluate((node) => node === document.querySelector('[data-native-action="stop"]')), true);
  await emitNative(nativeTracking(id, 'confirming'));
  await page.getByText('正在确认 · 15 分钟', {exact:true}).waitFor();
  await emitNative(nativeTracking(id, 'reconnecting'));
  await page.getByText('正在重连 · 15 分钟', {exact:true}).waitFor();
  assert.equal(mock.requests.some((r) => r.pathname.startsWith('/api/native/snapshot') || r.method !== 'GET'), false, 'Native gestures never issue a web API mutation or reader request');
}

async function nativeTrackRequiresCurrentOnlineRunning({page, mock, emitNative, taskDetail, poll}) {
  await taskDetail(); await emitNative(nativeIdle());
  await page.locator('[data-native-action="track"]').waitFor();
  for (const change of [{status:'unknown'}, {status:'waiting'}, {status:'completed'}, {status:'idle'}, {status:'error'}, {archived:true}]) {
    Object.assign(mock.snapshot.tasks[0], {status:'running', archived:false}, change);
    await poll();
    assert.equal(await page.locator('[data-native-action="track"]').count(), 0, JSON.stringify(change));
  }
  Object.assign(mock.snapshot.tasks[0], {status:'running', archived:false});
  mock.snapshot.devices[0].online = false;
  await poll(); assert.equal(await page.locator('[data-native-action="track"]').count(), 0);
  mock.snapshot.devices[0].online = true;
  await poll(); await page.locator('[data-native-action="track"]').waitFor();
}
async function nativeTrackingDurationComesFromHost({page,emitNative,taskDetail}) {
  await taskDetail(); await emitNative(nativeIdle());
  const start=page.getByRole('link',{name:'开始跟踪',exact:true});
  assert.equal(await start.getAttribute('href'),'agentmonitor://track?task_id=codex-one','Duration selection stays in the native app');
  await page.locator('#header [data-help]').click();
  assert.match(await page.locator('#help-content').textContent(),/在手机上选择时长/);
  assert.doesNotMatch(await page.locator('#help-content').textContent(),/最多跟踪 15 分钟/);
  await page.getByRole('button',{name:'知道了',exact:true}).click();
  for (const minutes of [15,30,60,120]) {
    const now=await page.evaluate(()=>Date.now());
    await emitNative({...nativeTracking(),endsAt:now+minutes*60000});
    await page.getByText(`跟踪中 · ${minutes} 分钟`,{exact:true}).waitFor();
    assert.equal(await page.getByRole('link',{name:'停止跟踪',exact:true}).getAttribute('href'),'agentmonitor://stop?task_id=codex-one');
    assert.equal(await start.count(),0);
  }
  const now=await page.evaluate(()=>Date.now());
  await emitNative({...nativeTracking(),endsAt:now+119*60000+1000});
  await page.getByText('跟踪中 · 120 分钟',{exact:true}).waitFor();
  await page.clock.runFor(3100);
  await page.getByText('跟踪中 · 119 分钟',{exact:true}).waitFor();
  await emitNative(nativeIdle());
  await start.waitFor();
  assert.equal(await page.getByRole('link',{name:'停止跟踪',exact:true}).count(),0);
}

async function nativeStopSurvivesMissingTaskAndStaleLink({page, mock, emitNative, routeTo, taskDetail, poll, nativeClicks}) {
  await emitNative(nativeTracking()); await taskDetail();
  mock.snapshot.tasks = []; await poll();
  await page.getByRole('link', {name:'停止跟踪', exact:true}).waitFor();
  await routeTo('tasks'); await routeTo('account');
  const stop = page.getByRole('link', {name:'停止跟踪', exact:true});
  assert.equal(await stop.getAttribute('href'), 'agentmonitor://stop?task_id=codex-one');
  assert.equal(await stop.evaluate((node) => node.parentElement === document.querySelector('[data-native-action="settings"]').parentElement), true);
  await page.evaluate(() => { window.__oldStop = document.querySelector('[data-native-action="stop"]').cloneNode(true); });
  await emitNative(nativeTracking('claude-two'));
  assert.equal(await stop.getAttribute('href'), 'agentmonitor://stop?task_id=claude-two');
  await page.evaluate(() => { document.body.append(window.__oldStop); window.__oldStop.click(); window.__oldStop.remove(); });
  assert.equal((await nativeClicks()).at(-1).blocked, true, 'An old link must not stop a different current task');
  await stop.click();
  assert.deepEqual((await nativeClicks()).at(-1), {href:'agentmonitor://stop?task_id=claude-two', blocked:false, trusted:true});
  await emitNative({...nativeTracking('claude-two'), endsAt: NOW - 1});
  assert.equal(await stop.count(), 1, 'Stop remains possible until the host confirms it has stopped');
  await emitNative(nativeIdle());
  assert.equal(await page.locator('[data-native-action="stop"]').count(), 0);
  assert.equal(await page.locator('[data-native-action="settings"]').count(), 1);
}

async function nativeLogoutUsesOnlyTheHost({page, emitNative, routeTo, count, nativeClicks, logout}) {
  await emitNative(nativeIdle()); await routeTo('account');
  assert.equal(await page.locator('[data-action="logout"]').count(), 0);
  await page.locator('[data-native-action="logout"]').click();
  assert.deepEqual((await nativeClicks()).at(-1), {href:'agentmonitor://logout', blocked:false, trusted:true});
  assert.equal(count('POST', '/api/logout'), 0);
  await emitNative(nativeIdle(false));
  assert.equal(await page.locator('[data-native-action="logout"]').count(), 0);
  await logout();
  assert.equal(count('POST', '/api/logout'), 1, 'A normal password session remains independently usable');
}

async function nativeGoogleUsesExternalConnectionFlow({page, mock, emitNative, nativeClicks, loginAgain, routeTo, settle}) {
  await emitNative(nativeIdle(false));
  assert.equal(await page.locator('#auth-form').count(), 1, 'A native signal does not authenticate the page');
  await page.getByRole('link', {name:'连接工作台', exact:true}).click();
  await page.getByRole('link', {name:'使用 Google 登录', exact:true}).click();
  assert.equal((await nativeClicks()).every((click) => click.href === 'agentmonitor://connect' && !click.blocked && click.trusted), true);
  // A stale browser button must not POST Google start after the host signal.
  await page.evaluate(() => { const button=document.createElement('button'); button.dataset.action='google-login'; document.body.append(button); button.click(); button.remove(); });
  await settle();
  assert.equal(mock.requests.some((r) => r.pathname === '/api/auth/google/start'), false);
  await loginAgain(); await routeTo('profile');
  const google = page.getByRole('link', {name:'关联 Google 账号', exact:true});
  assert.equal(await google.getAttribute('href'), 'agentmonitor://google-profile');
  await google.click();
  assert.equal((await nativeClicks()).at(-1).blocked, false);
  assert.equal(mock.requests.some((r) => r.pathname === '/api/auth/google/start'), false);
}

async function nativeFullAppConsentIsExplicit({page, mock, routeTo, count}) {
  Object.assign(mock.nativeRequests[NATIVE_ID], fullConsent());
  await routeTo(`native-connect/${NATIVE_ID}`);
  await page.getByRole('heading', {name:'完整工作台', exact:true}).waitFor();
  assert.match(await page.locator('#main').textContent(), /查看任务与结果.*下载文件.*回复和归档任务.*管理资料.*同步设置.*已连接设备/);
  assert.equal(count('POST', `${NATIVE_PATH}/approve`), 0);
  await page.locator('[data-action="approve-native"]').click();
  await page.getByRole('link', {name:'返回 Monitor', exact:true}).waitFor();
  const request = mock.requests.find((r) => r.pathname === `${NATIVE_PATH}/approve`);
  assert.deepEqual(request.body, {mode:'full_app', consent_version:'full_app_v1'});
  assert.equal(request.headers['x-csrf-token'], mock.csrf);
  assert.equal(await page.getByRole('link', {name:'返回 Monitor', exact:true}).getAttribute('href'), `agentmonitor://paired?request_id=${NATIVE_ID}`);
}

async function nativeConsentFailsClosed({page, mock, routeTo, count}) {
  const invalid = [
    {...fullConsent(), consent_version:'full_app_v2'},
    {...fullConsent(), scopes:['task_status','workspace_read']},
    {...fullConsent(), scopes:[...fullConsent().scopes, 'remote_execute']},
    {...fullConsent(), scopes:['task_status','workspace_read','workspace_read']},
    {...fullConsent(), mode:'future_app'}, {...fullConsent(), mode:null},
    {mode:'full_app'}, {mode:'read_only', scopes:['task_status','workspace_write'], consent_version:'read_only_v1'},
  ];
  for (let index=0; index<invalid.length; index++) {
    const id = String.fromCharCode(65+index).repeat(32);
    mock.nativeRequests[id] = {...clone(mock.nativeRequests[NATIVE_ID]), request_id:id, ...invalid[index]};
    try { await routeTo(`native-connect/${id}`); }
    catch (error) { throw new Error(`Invalid consent case ${index}: ${JSON.stringify({title:await page.locator('#header').textContent(), errors:mock.pageErrors})}`, {cause:error}); }
    await page.getByText('连接权限已更新', {exact:true}).waitFor();
    assert.equal(await page.locator('[data-action="approve-native"]').count(), 0);
    assert.equal(await page.locator('[data-action="reject-native"]').isEnabled(), true);
    assert.equal(count('POST', `/api/native/pairing/${id}/approve`), 0);
  }
  await page.locator('[data-action="reject-native"]').click();
  await page.getByText('已取消连接', {exact:true}).waitFor();
}

async function nativeFullDeviceRevocationIsClear({page, mock, routeTo, count}) {
  mock.nativeDevices = [{id:NATIVE_ID, name:'已连接 Monitor', ...fullConsent(), last_seen:iso(), expires_at:iso(100000)}];
  await routeTo('sessions'); await page.getByText('完整 App', {exact:true}).waitFor();
  await page.getByRole('button', {name:'退出这台 App', exact:true}).click();
  await page.getByText('退出这台 App？', {exact:true}).waitFor();
  assert.equal(count('DELETE', `/api/native/devices/${NATIVE_ID}`), 0);
  await page.locator('#confirm-action').click();
  await page.getByText('已连接 Monitor', {exact:true}).waitFor({state:'detached'});
  assert.equal(count('DELETE', `/api/native/devices/${NATIVE_ID}`), 1);
}

async function nativeViewsFitSmallScreen({page, mock, emitNative, taskDetail, routeTo, settle}) {
  await emitNative(nativeTracking()); await taskDetail();
  for (const view of ['task', 'account', 'consent']) {
    if (view === 'account') await routeTo('account');
    if (view === 'consent') { Object.assign(mock.nativeRequests[NATIVE_ID], fullConsent()); await routeTo(`native-connect/${NATIVE_ID}`); await page.getByRole('heading', {name:'完整工作台', exact:true}).waitFor(); }
    await settle();
    const size = await page.evaluate(() => ({scroll:document.documentElement.scrollWidth, viewport:innerWidth}));
    assert.equal(size.scroll, size.viewport, `No horizontal overflow on native ${view}`);
    if (process.env.UI_SCREENSHOT_DIR) {
      fs.mkdirSync(path.resolve(process.env.UI_SCREENSHOT_DIR), {recursive:true});
      await page.screenshot({path:path.resolve(process.env.UI_SCREENSHOT_DIR, `ui-native-${view}.png`)});
    }
  }
}

async function installCameraFixture(page) {
  await page.evaluate(() => {
    const camera = window.__camera = { requests:[], streams:[], plays:[], scans:0, pendingScan:null };
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {configurable:true, value:(constraints) => new Promise((resolve,reject) => camera.requests.push({constraints,resolve,reject}))});
    HTMLMediaElement.prototype.play = function() {
      return new Promise((resolve,reject) => camera.plays.push({video:this,resolve,reject}));
    };
    window.MonitorFeatures.readQr = () => { camera.scans++; return new Promise((resolve,reject) => { camera.pendingScan={resolve,reject}; }); };
    camera.grant = (index) => {
      const canvas=document.createElement('canvas'); canvas.width=32; canvas.height=32;
      canvas.getContext('2d').fillRect(0,0,32,32);
      const stream=canvas.captureStream(0);
      camera.streams[index]=stream; camera.requests[index].resolve(stream);
    };
    camera.ready = (index, playing=true) => {
      const video=camera.plays[index].video;
      for (const [key,value] of Object.entries({readyState:2,videoWidth:32,videoHeight:32,paused:false,ended:false})) Object.defineProperty(video,key,{configurable:true,value});
      video.dispatchEvent(new Event(playing ? 'playing' : 'loadeddata'));
      camera.plays[index].resolve();
    };
  });
}
async function cameraOpenRequest(app) {
  await app.routeTo('scanner'); await installCameraFixture(app.page);
  await app.page.getByRole('button',{name:'打开相机',exact:true}).click();
  await app.page.waitForFunction(() => window.__camera.requests.length === 1);
  await app.page.getByRole('button',{name:'取消打开',exact:true}).waitFor();
}
async function cameraGrant(app, index=0) {
  await app.page.evaluate((i) => window.__camera.grant(i), index);
  await app.page.waitForFunction((count) => window.__camera.plays.length === count, index+1);
}
async function cameraPreviewRequiresDecodedPlayback(app) {
  const {page,poll,settle}=app;
  await cameraOpenRequest(app); await cameraGrant(app);
  assert.equal(await page.locator('[data-camera-state="playing"]').count(), 0, 'A granted stream is not a preview');
  await page.evaluate(() => window.__camera.plays[0].resolve()); await settle();
  assert.equal(await page.locator('[data-camera-state="playing"]').count(), 0, 'Resolved play() without decoded frames is insufficient');
  await page.evaluate(() => window.__camera.ready(0,false)); await settle();
  assert.equal(await page.locator('[data-camera-state="playing"]').count(), 0, 'Loaded frames alone do not prove playback');
  await page.evaluate(() => window.__camera.ready(0));
  await page.getByRole('button',{name:'关闭相机',exact:true}).waitFor();
  assert.equal(await page.locator('[data-action="start-camera"]').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.evaluate(() => window.__camera.scans),1);
  const video=await page.locator('#scan-video').elementHandle();
  await poll();
  assert.equal(await video.evaluate((node) => node===document.querySelector('#scan-video')),true);
  await page.getByRole('button',{name:'关闭相机',exact:true}).waitFor();
  await page.getByRole('button',{name:'关闭相机',exact:true}).click();
  assert.equal(await page.evaluate(() => window.__camera.streams[0].getTracks().every((track)=>track.readyState==='ended')),true);
  assert.equal(await page.locator('#scan-video').evaluate((node)=>node.srcObject),null);
  await page.getByRole('button',{name:'打开相机',exact:true}).waitFor();
  await page.evaluate((link) => window.__camera.pendingScan.resolve(link),`${ORIGIN}/#/pairing-confirm/${QR_ID}`);
  await settle();
  assert.equal(new URL(page.url()).hash,'#/scanner','A decoded result arriving after close cannot navigate');
}
async function cameraCancellationStopsLateGrantedStream(app) {
  const {page,settle}=app;
  await cameraOpenRequest(app);
  await page.getByRole('button',{name:'取消打开',exact:true}).click();
  await page.evaluate(() => window.__camera.grant(0)); await settle();
  assert.equal(await page.evaluate(() => window.__camera.streams[0].getTracks()[0].readyState),'ended');
  assert.equal(await page.evaluate(() => window.__camera.plays.length),0);
  await page.getByRole('button',{name:'打开相机',exact:true}).waitFor();
}
async function cameraPreviousPlaybackCannotAffectRestart(app) {
  const {page,settle}=app;
  await cameraOpenRequest(app); await cameraGrant(app);
  await page.getByRole('button',{name:'取消打开',exact:true}).click();
  await page.getByRole('button',{name:'打开相机',exact:true}).click();
  await page.waitForFunction(()=>window.__camera.requests.length===2);
  await cameraGrant(app,1);
  await page.evaluate(() => { window.__camera.plays[0].reject(new Error('old play rejected')); }); await settle();
  assert.equal(await page.evaluate(() => window.__camera.streams[1].getTracks()[0].readyState),'live');
  await page.getByRole('button',{name:'取消打开',exact:true}).waitFor();
  await page.evaluate(()=>window.__camera.ready(1));
  await page.getByRole('button',{name:'关闭相机',exact:true}).waitFor();
  assert.equal(await page.locator('#scan-message').textContent(),'扫描电脑上显示的配对二维码');
}
async function cameraLateGrantAfterNavigationIsDiscarded(app) {
  const {page,routeTo,settle}=app;
  await cameraOpenRequest(app); await routeTo('account');
  await page.evaluate(()=>window.__camera.grant(0)); await settle();
  assert.equal(await page.evaluate(()=>window.__camera.streams[0].getTracks()[0].readyState),'ended');
  assert.equal(await page.evaluate(()=>window.__camera.plays.length),0);
  await routeTo('scanner');
  await page.getByRole('button',{name:'打开相机',exact:true}).waitFor();
  assert.equal(await page.locator('#scan-video').evaluate((node)=>node.srcObject),null);
}
async function cameraBackgroundStopsWithoutAutorestart(app) {
  const {page,settle}=app;
  await cameraOpenRequest(app); await cameraGrant(app);
  await page.evaluate(()=>window.__camera.ready(0));
  await page.getByRole('button',{name:'关闭相机',exact:true}).waitFor();
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
  assert.equal(await page.evaluate(()=>window.__camera.streams[0].getTracks()[0].readyState),'ended');
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));});
  await settle();
  assert.equal(await page.evaluate(()=>window.__camera.requests.length),1,'Foreground return requires a new gesture');
  await page.getByRole('button',{name:'打开相机',exact:true}).waitFor();
}
async function cameraPlaybackFailureAndTimeoutAllowRetry(app, timeout) {
  const {page,settle}=app;
  await cameraOpenRequest(app); await cameraGrant(app);
  if (timeout) await page.clock.runFor(10100);
  else await page.evaluate(()=>window.__camera.plays[0].reject(new DOMException('Playback denied','NotAllowedError')));
  await page.getByRole('button',{name:'重试相机',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>window.__camera.streams[0].getTracks()[0].readyState),'ended');
  assert.equal(await page.locator('[data-camera-state="playing"]').count(),0);
  assert.equal(await page.locator('#scan-message').textContent(),'相机预览未开始，请重试。','A granted stream followed by playback denial is not camera-permission denial');
  await page.evaluate(()=>window.__camera.ready(0)); await settle();
  await page.getByRole('button',{name:'重试相机',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>window.__camera.scans),0);
  await page.getByRole('button',{name:'重试相机',exact:true}).click();
  await page.waitForFunction(()=>window.__camera.requests.length===2);
}
async function cameraEndedTrackClearsPreview(app) {
  const {page}=app;
  await cameraOpenRequest(app); await cameraGrant(app);
  await page.evaluate(()=>window.__camera.ready(0));
  await page.getByRole('button',{name:'关闭相机',exact:true}).waitFor();
  await page.evaluate(()=>window.__camera.streams[0].getTracks()[0].dispatchEvent(new Event('ended')));
  await page.getByRole('button',{name:'重试相机',exact:true}).waitFor();
  assert.equal(await page.locator('#scan-video').evaluate((node)=>node.srcObject),null);
  assert.equal(await page.locator('#scan-message').textContent(),'相机预览已中断，请重试。');
}
async function cameraPermissionDeniedAllowsRetry(app) {
  const {page}=app;
  await cameraOpenRequest(app);
  await page.evaluate(()=>window.__camera.requests[0].reject(new DOMException('Permission denied','NotAllowedError')));
  await page.getByRole('button',{name:'重试相机',exact:true}).waitFor();
  assert.match(await page.locator('#scan-message').textContent(),/未开启相机权限/);
  assert.equal(await page.evaluate(()=>window.__camera.plays.length),0);
  await page.getByRole('button',{name:'重试相机',exact:true}).click();
  await page.waitForFunction(()=>window.__camera.requests.length===2);
}

async function clickTab(page, tab) {
  await page.locator(`#navigation [data-page="${tab}"]`).click();
  await page.waitForFunction((name)=>location.hash===`#/${name}` && document.querySelector(`#navigation [data-page="${name}"]`).getAttribute('aria-current')==='page',tab);
}
async function openComputerTask(page) {
  await clickTab(page,'devices');
  await page.locator('#main [data-page="device"]').first().click();
  await page.waitForFunction(()=>location.hash==='#/device/test-computer');
  await page.locator('#main [data-page="task"][data-id="codex-one"]').click();
  await page.waitForFunction(()=>location.hash==='#/task/codex-one');
}
async function appNavigationTabsDoNotGrowHistory({page}) {
  const before=await page.evaluate(()=>history.length);
  for (const tab of ['devices','account','tasks','devices','tasks','account','tasks']) await clickTab(page,tab);
  assert.equal(await page.evaluate(()=>history.length),before,'Switching root tabs replaces the current root entry');
  assert.deepEqual(await page.evaluate(()=>history.state.monitorNavigation.parents),[]);
}
async function appProfileEntryIsSingleAndDiscoverable({page}) {
  await page.emulateMedia({reducedMotion:'no-preference'});
  await clickTab(page,'account');
  const profile=page.getByRole('button',{name:'个人资料',exact:true});
  assert.equal(await profile.count(),1,'The avatar/name is the sole personal-profile entry');
  assert.equal(await profile.evaluate((node)=>node.classList.contains('profile-button')),true);
  await profile.click();
  await page.locator('#profile-form').waitFor();
  assert.equal(await page.locator('#header h1').textContent(),'个人资料');
  await page.locator('#header [data-back]').click();
  await page.waitForFunction(()=>location.hash==='#/account');
}
async function appNavigationReturnsToComputerSource({page}) {
  await openComputerTask(page);
  assert.equal(await page.locator('#navigation [data-page="devices"]').getAttribute('aria-current'),'page');
  await page.getByRole('button',{name:'电脑详情',exact:true}).click();
  await page.waitForFunction(()=>location.hash==='#/device/test-computer');
  assert.equal(await page.locator('#navigation [data-page="devices"]').getAttribute('aria-current'),'page');
  // A page-level back popped the task; browser/native back must now pop the computer.
  await page.goBack();
  await page.waitForFunction(()=>location.hash==='#/devices');
  assert.equal(await page.locator('#header h1').textContent(),'我的电脑');
}
async function appNavigationSystemBackFollowsDetailStack({page}) {
  await openComputerTask(page);
  await page.goBack(); await page.waitForFunction(()=>location.hash==='#/device/test-computer');
  await page.goBack(); await page.waitForFunction(()=>location.hash==='#/devices');
  assert.equal(await page.locator('#navigation [aria-current="page"]').getAttribute('data-page'),'devices');
}
async function appNavigationRapidTabsClearDetailStack({page}) {
  await openComputerTask(page);
  await page.evaluate(()=>{
    document.querySelector('#navigation [data-page="account"]').click();
    document.querySelector('#navigation [data-page="devices"]').click();
  });
  await page.waitForFunction(()=>location.hash==='#/devices' && history.state.monitorNavigation.parents.length===0);
  await page.locator('#main [data-page="device"]').first().click();
  await page.waitForFunction(()=>location.hash==='#/device/test-computer');
  await page.locator('#header [data-back]').click();
  await page.waitForFunction(()=>location.hash==='#/devices');
  await page.goBack();
  assert.equal(page.url(),'about:blank','The next native/browser back leaves the app rather than revisiting prior tabs/details');
}
async function appNavigationColdTaskLinkHasParent({page}) {
  await page.getByText('Codex 测试任务',{exact:true}).waitFor();
  assert.equal(await page.locator('#navigation [aria-current="page"]').getAttribute('data-page'),'tasks');
  await page.locator('#header [data-back]').click();
  await page.waitForFunction(()=>location.hash==='#/tasks');
  assert.equal(await page.locator('#header h1').textContent(),'工作台');
}
function scrollingSnapshot() {
  const snapshot=initialSnapshot();
  snapshot.devices=Array.from({length:18},(_,i)=>({...snapshot.devices[0],id:i ? `computer-${i}`:'test-computer',name:`测试电脑 ${i}`}));
  snapshot.tasks=Array.from({length:24},(_,i)=>({...snapshot.tasks[0],id:`codex-${i}`,title:`测试任务 ${i}`}));
  return snapshot;
}
async function appNavigationKeepsTabScrollPositions({page}) {
  await page.evaluate(()=>window.scrollTo(0,700));
  const taskScroll=await page.evaluate(()=>scrollY);
  assert.equal(taskScroll,700);
  await clickTab(page,'devices');
  await page.evaluate(()=>window.scrollTo(0,430));
  await clickTab(page,'tasks');
  assert.equal(await page.evaluate(()=>scrollY),taskScroll);
  await clickTab(page,'devices');
  assert.equal(await page.evaluate(()=>scrollY),430);
  const card=page.locator('#main [data-page="device"]').nth(4);
  await card.click();
  await page.waitForFunction(()=>location.hash==='#/device/computer-4');
  await page.locator('#header [data-back]').click();
  await page.waitForFunction(()=>location.hash==='#/devices');
  assert.equal(await page.evaluate(()=>scrollY),430,'Returning to the source list keeps its scroll position');
}
async function appLoadingNeverShowsFalseEmpty({page,initialSnapshotGate,settle}, route) {
  await initialSnapshotGate.started;
  const label=route.startsWith('device') ? '电脑':'任务';
  await page.getByText(`正在读取${label}`,{exact:true}).waitFor();
  for (const text of ['还没有连接电脑','未找到这台电脑','未找到这条任务','暂无任务','暂无进行中的任务']) assert.equal(await page.getByText(text,{exact:true}).count(),0,text);
  assert.equal(await page.locator('#connection').isVisible(),false,'Initial loading is not a disconnected warning');
  await initialSnapshotGate.reply(initialSnapshot()); await settle();
  await page.getByText(`正在读取${label}`,{exact:true}).waitFor({state:'detached'});
  const actual=route==='devices' ? '隔离测试电脑' : route.startsWith('device/') ? '隔离测试电脑' : 'Codex 测试任务';
  assert.ok(await page.getByText(actual,{exact:true}).count()>0);
}
async function appLoadingFailureRetriesBeforeShowingEmpty({page,initialSnapshotGate,holdNext,settle}) {
  await initialSnapshotGate.failNetwork(); await settle();
  await page.getByText('暂时无法读取电脑',{exact:true}).waitFor();
  assert.equal(await page.getByText('还没有连接电脑',{exact:true}).count(),0);
  const retry=holdNext('GET','/api/snapshot');
  await page.getByRole('button',{name:'重试',exact:true}).click(); await retry.started;
  await page.getByText('正在读取电脑',{exact:true}).waitFor();
  await retry.reply({server_time:iso(),devices:[],tasks:[]}); await settle();
  await page.getByText('还没有连接电脑',{exact:true}).waitFor();
  assert.equal(await page.getByText('暂时无法读取电脑',{exact:true}).count(),0);
}
async function startupParallelSnapshotWaitsForAuthentication({page,mock,startupGates,user,count,settle,poll}) {
  const snapshot=clone(mock.snapshot); snapshot.tasks[0].title='并行启动测试任务';
  assert.equal(await page.locator('#navigation').isVisible(),false);
  assert.equal(count('GET','/api/snapshot'),0);
  await startupGates.bootstrap.reply({needs_setup:false,google_login:false}); await settle();
  assert.equal(await page.locator('#navigation').isVisible(),false);
  await startupGates.me.reply({user:user(),csrf_token:mock.csrf,preferences:mock.preferences}); await settle();
  await startupGates.snapshot.started;
  await startupGates.snapshot.reply(snapshot); await settle();
  await page.getByText('并行启动测试任务',{exact:true}).waitFor();
  assert.equal(count('GET','/api/snapshot'),1,'Authenticated startup starts one task request');
  await poll();
  assert.equal(count('GET','/api/snapshot'),2,'The next snapshot remains one ordinary scheduled poll');
  assert.equal(count('GET','/api/me'),2);
}
async function startupUnauthorizedMeCannotRevealPrefetchedData({page,mock,startupGates,settle,count}) {
  mock.snapshot.tasks[0].title='不可展示的旧启动任务';
  await startupGates.bootstrap.reply({needs_setup:false,google_login:false});
  await startupGates.me.reply({detail:'Unauthorized'},401); await settle();
  await page.locator('#auth-form').waitFor();
  assert.equal(await page.getByText('不可展示的旧启动任务',{exact:true}).count(),0);
  assert.equal(await page.locator('#navigation').isVisible(),false);
  await page.clock.runFor(5100);
  assert.equal(count('GET','/api/snapshot'),0,'Failed authentication does not fetch tasks or start background polling');
}
async function startupAnonymousDoesNotFetchTasksBeforeLogin({page,mock,initialSnapshotGate,loginAgain,settle,count}) {
  assert.equal(count('GET','/api/snapshot'),0);
  mock.snapshot.tasks[0].title='新登录任务';
  await loginAgain(); await initialSnapshotGate.started;
  await initialSnapshotGate.reply(clone(mock.snapshot)); await settle();
  await page.getByText('新登录任务',{exact:true}).waitFor();
  assert.equal(count('POST','/api/login'),1);
  assert.equal(count('GET','/api/snapshot'),1);
  assert.equal(await page.locator('#auth-form').count(),0);
  assert.equal(await page.locator('#navigation').isVisible(),true);
}
async function startupSupersededRequestsCannotLogOutNewStartup({page,mock,startupGates,settle}) {
  await startupGates.bootstrap.reply({detail:'Initial bootstrap failed'},503);
  await page.getByRole('button',{name:'重新连接',exact:true}).waitFor();
  await page.getByRole('button',{name:'重新连接',exact:true}).click();
  await startupGates.snapshot.started;
  await startupGates.snapshot.reply(clone(mock.snapshot));
  await page.getByText('Codex 测试任务',{exact:true}).waitFor();
  await startupGates.me.reply({detail:'Old startup unauthorized'},401);
  await settle();
  assert.equal(await page.locator('#auth-form').count(),0);
  assert.equal(await page.getByText('Codex 测试任务',{exact:true}).count(),1);
  assert.equal(await page.getByText('失败启动的旧任务',{exact:true}).count(),0);
}
async function startupAdoptedUnauthorizedSnapshotStillExpiresSession({page,mock,startupGates,user,settle,count}) {
  await startupGates.bootstrap.reply({needs_setup:false,google_login:false});
  await startupGates.me.reply({user:user(),csrf_token:mock.csrf,preferences:mock.preferences});
  await page.getByText('正在读取任务',{exact:true}).waitFor();
  await startupGates.snapshot.reply({detail:'Session expired'},401); await settle();
  await page.locator('#auth-form').waitFor();
  assert.equal(await page.locator('#navigation').isVisible(),false,'An adopted 401 must keep the normal expiry behavior');
  await page.clock.runFor(5100);
  assert.equal(count('GET','/api/snapshot'),1);
}
async function startupSlowIdentityCanFinishAfterTenSeconds({page,startupGates,count,settle}) {
  await startupGates.bootstrap.reply({needs_setup:false,google_login:false});
  await page.clock.runFor(12700); await settle();
  await page.getByText('连接较慢，正在等待',{exact:true}).waitFor();
  assert.equal(await page.getByText('暂时无法连接',{exact:true}).count(),0);
  await startupGates.me.reply({detail:'Unauthorized'},401); await settle();
  await page.locator('#auth-form').waitFor();
  assert.equal(count('GET','/api/me'),1);
  assert.equal(count('GET','/api/snapshot'),0);
}
async function startupTimeoutRetriesOnlyFailedRead({page,startupGates,holdNext,count,settle}) {
  await startupGates.me.reply({detail:'Unauthorized'},401);
  const retry=holdNext('GET','/api/bootstrap');
  await page.clock.runFor(25050); await settle();
  await page.getByText('正在重新连接',{exact:true}).waitFor();
  await page.clock.runFor(800); await retry.started;
  await retry.reply({needs_setup:false,google_login:false}); await settle();
  await page.locator('#auth-form').waitFor();
  await startupGates.bootstrap.reply({needs_setup:true,google_login:false}); await settle();
  assert.equal(count('GET','/api/bootstrap'),2);
  assert.equal(count('GET','/api/me'),1,'A successful 401 is retained, not retried');
  assert.equal(count('GET','/api/snapshot'),0);
  assert.equal(await page.getByText('建立账号',{exact:true}).count(),0,'A timed-out old response cannot overwrite the retry');
}
async function startupTransportRetryIsBounded({page,startupGates,holdNext,count,settle}) {
  await startupGates.me.reply({detail:'Unauthorized'},401);
  const retry=holdNext('GET','/api/bootstrap');
  await startupGates.bootstrap.failNetwork(); await settle();
  await page.clock.runFor(800); await retry.started;
  await retry.failNetwork(); await settle();
  await page.getByText('暂时无法连接',{exact:true}).waitFor();
  await page.clock.runFor(60000); await settle();
  assert.equal(count('GET','/api/bootstrap'),2,'Transport recovery stops after one retry');
  assert.equal(count('GET','/api/me'),1);
}
async function startupManualReconnectCancelsOldBackoff({page,mock,startupGates,count,settle}) {
  await startupGates.bootstrap.failNetwork(); await settle();
  await page.getByRole('button',{name:'重新连接',exact:true}).click();
  await startupGates.snapshot.started;
  await startupGates.snapshot.reply(clone(mock.snapshot)); await settle();
  await page.getByText('Codex 测试任务',{exact:true}).waitFor();
  await startupGates.me.reply({detail:'Old unauthorized'},401);
  await page.clock.runFor(1000); await settle();
  assert.equal(count('GET','/api/bootstrap'),2,'Cancelled backoff must not start a third request');
  assert.equal(count('GET','/api/me'),2);
  assert.equal(await page.locator('#auth-form').count(),0);
}
async function startupHttpFailureDoesNotRetry({page,startupGates,count,settle}) {
  await startupGates.bootstrap.reply({detail:'Service unavailable'},503); await settle();
  await page.getByText('暂时无法连接',{exact:true}).waitFor();
  await page.clock.runFor(26000); await settle();
  assert.equal(count('GET','/api/bootstrap'),1);
  assert.equal(count('GET','/api/me'),1);
  assert.equal(count('GET','/api/snapshot'),0);
}
async function loginSuccessReadRecoveryNeverReposts({page,mock,holdNext,count,settle}) {
  const first=holdNext('GET','/api/me'), retry=holdNext('GET','/api/me');
  await page.locator('[name="username"]').fill('isolated-new-user');
  await page.locator('[name="password"]').fill('Only-a-fake-test-password');
  await page.locator('#auth-form [type="submit"]').click();
  await first.started; await first.failNetwork(); await settle();
  await page.clock.runFor(800); await retry.started;
  await retry.failNetwork(); await settle();
  await page.getByText('暂时无法连接',{exact:true}).waitFor();
  assert.equal(await page.locator('#auth-form').count(),0,'A successful write must leave the login form');
  assert.equal(count('POST','/api/login'),1);
  await page.getByRole('button',{name:'重新连接',exact:true}).click();
  await page.locator('[data-action="approve-native"]').waitFor();
  assert.equal(count('POST','/api/login'),1,'Recovery restores the session with GETs only');
  assert.equal(count('POST',NATIVE_PATH+'/approve'),0);
  assert.match(page.url(),new RegExp('#/native-connect/'+NATIVE_ID+'$'));
}
async function loginWriteFailureNeverRetries({page,holdNext,count,settle}) {
  const login=holdNext('POST','/api/login');
  await page.locator('[name="username"]').fill('isolated-new-user');
  await page.locator('[name="password"]').fill('Only-a-fake-test-password');
  await page.locator('#auth-form [type="submit"]').click();
  await login.started; await login.failNetwork(); await settle();
  await page.clock.runFor(60000); await settle();
  assert.equal(count('POST','/api/login'),1);
  assert.equal(await page.locator('#auth-form').count(),1);
  assert.equal(await page.locator('#auth-error').isVisible(),true);
}
async function startupOnlineEventsDoNotDuplicateRequests({page,startupGates,count,settle}) {
  await page.evaluate(()=>{window.dispatchEvent(new Event('online'));window.dispatchEvent(new Event('online'));});
  await settle();
  assert.equal(count('GET','/api/bootstrap'),1);
  assert.equal(count('GET','/api/me'),1);
  await startupGates.bootstrap.reply({needs_setup:false,google_login:false});
  await startupGates.me.reply({detail:'Unauthorized'},401); await settle();
  await page.locator('#auth-form').waitFor();
}
async function beginBodyPoll(app,status=200) {
  const gate=app.streamServer.next('/api/snapshot',status);
  const headers=app.page.waitForResponse((response)=>new URL(response.url()).pathname==='/api/snapshot');
  await app.poll(); await gate.started; await headers;
  return gate;
}
async function responseBodyWithinDeadlineUpdatesNormally(app) {
  const {page,mock,settle}=app;
  const gate=await beginBodyPoll(app);
  await page.clock.runFor(9000);
  assert.equal(gate.cancelled,false);
  const snapshot=clone(mock.snapshot);snapshot.tasks[0].title='完整正文到达后的任务';
  gate.finish(snapshot); await settle();
  await page.getByText('完整正文到达后的任务',{exact:true}).waitFor();
  assert.equal(gate.cancelled,false);
}
async function responseBodyTimeoutReleasesPollForRetry(app) {
  const {page,mock,settle,count}=app;
  const gate=await beginBodyPoll(app);
  await page.clock.runFor(10050); await settle();
  await until(()=>gate.cancelled,'The browser must abort a real unfinished response body');
  await page.locator('#connection button[data-action="refresh"]').waitFor();
  const before=count('GET','/api/snapshot');
  mock.snapshot.tasks[0].title='正文超时后重试成功';
  await page.locator('#connection button[data-action="refresh"]').click();
  await page.getByText('正文超时后重试成功',{exact:true}).waitFor();
  assert.equal(count('GET','/api/snapshot'),before+1,'pollInFlight was released for a fresh request');
  assert.equal(await page.locator('#connection').isVisible(),false);
}
async function responseBodyOldSessionCannotAffectNewLogin(app,timeout) {
  const {page,mock,logout,loginAgain,settle,poll}=app;
  const gate=await beginBodyPoll(app,401);
  await logout(); mock.snapshot.tasks[0].title='新会话独立任务'; await loginAgain();
  if (timeout) { await page.clock.runFor(10050); await until(()=>gate.cancelled,'Old body must still time out'); }
  else gate.finish({detail:'Old response expired'});
  await settle();
  assert.equal(await page.locator('#auth-form').count(),0,'An old-session body result must not log out a new login');
  await poll(); await page.getByText('新会话独立任务',{exact:true}).waitFor();
}
async function responseBodyExternalAbortAndDeadline(app,timeout) {
  const {page,streamServer,settle}=app;
  const gate=streamServer.next('/api/test-body');
  const headers=page.waitForResponse((response)=>new URL(response.url()).pathname==='/api/test-body');
  await page.evaluate(()=>{
    window.__external=new AbortController();window.__requestResult=null;
    window.__testApi('/api/test-body',{signal:window.__external.signal})
      .then(()=>{window.__requestResult={ok:true};},(error)=>{window.__requestResult={error:error.message};});
  });
  await gate.started; await headers;
  if (timeout) await page.clock.runFor(10050);
  else await page.evaluate(()=>window.__external.abort());
  await settle(); await until(()=>gate.cancelled,'Merged signal must cancel the body transport');
  await page.waitForFunction(()=>window.__requestResult!==null);
  const result=await page.evaluate(()=>window.__requestResult);
  assert.match(result.error,timeout ? /连接超时/ : /请求已取消/);
  assert.equal(await page.evaluate(()=>window.__external.signal.aborted),!timeout,'Internal deadline must not mutate the caller controller');
}
async function responseBodyCompletedRequestDetachesCancellation(app) {
  const {page,streamServer,settle}=app;
  const gate=streamServer.next('/api/test-body');
  await page.evaluate(()=>{
    const original=window.fetch;
    window.fetch=(url,options)=>{if(String(url)==='/api/test-body')window.__requestSignal=options.signal;return original(url,options);};
    window.__external=new AbortController();window.__requestResult=null;
    window.__testApi('/api/test-body',{signal:window.__external.signal})
      .then((value)=>{window.__requestResult=value;},(error)=>{window.__requestResult={error:error.message};});
  });
  await gate.started; gate.finish({ok:true});
  await page.waitForFunction(()=>window.__requestResult?.ok===true);
  await page.evaluate(()=>window.__external.abort());
  await page.clock.runFor(10050); await settle();
  assert.equal(await page.evaluate(()=>window.__requestSignal.aborted),false,'Successful body reads remove both the deadline and external listener');
}
async function responseBodyAlreadyAbortedSignalNeverStartsTransport(app) {
  const {page,count}=app;
  await page.evaluate(()=>{
    const external=new AbortController();external.abort();window.__requestResult=null;
    window.__testApi('/api/test-body',{signal:external.signal})
      .then(()=>{window.__requestResult={ok:true};},(error)=>{window.__requestResult={error:error.message};});
  });
  await page.waitForFunction(()=>window.__requestResult!==null);
  assert.match(await page.evaluate(()=>window.__requestResult.error),/请求已取消/);
  assert.equal(count('GET','/api/test-body'),0);
}

function createCliQrFixture(origin = ORIGIN) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-monitor-ui-qr-'));
  const python = process.env.QR_PYTHON || path.resolve(webRoot, '../../../work/.venv/Scripts/python.exe');
  const filename = path.join(directory, `pairing-${QR_ID}.png`);
  try {
    // Run the shipping CLI's local encoder. Never start pairing or contact a hub.
    execFileSync(python, ['-c', 'from pathlib import Path; import sys; from agent_monitor.remote_agent import _write_pairing_qr; _write_pairing_qr(sys.argv[1],sys.argv[2],Path(sys.argv[3]))', `${origin}/#/pairing-confirm/${QR_ID}`, QR_ID, directory], {
      cwd: path.dirname(webRoot), env: { ...process.env, PYTHONIOENCODING: 'utf-8' }, encoding: 'utf8', timeout: 10000,
    });
    return fs.readFileSync(filename);
  } finally {
    // Delete only our exact generated file and then the now-empty temporary directory.
    if (fs.existsSync(filename)) fs.unlinkSync(filename);
    fs.rmdirSync(directory);
  }
}

async function captureSmallPreviews(browser, directory) {
  directory = path.resolve(directory);
  fs.mkdirSync(directory, { recursive: true });
  const app = await boot(browser, { snapshot: statusSnapshot(), viewport: { width: 320, height: 720 } });
  try {
    for (const route of ['tasks', 'profile', 'scanner']) {
      await app.routeTo(route);
      await app.settle();
      const width = await app.page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, viewport: innerWidth }));
      assert.equal(width.scroll, width.viewport, `No horizontal overflow on ${route} at 320px`);
      const filename = path.join(directory, `ui-small-${route}.png`);
      await app.page.screenshot({ path: filename });
      console.log(`SCREENSHOT ${filename}`);
    }
    assert.deepEqual(app.mock.unexpected, []);
    assert.deepEqual(app.mock.pageErrors, []);
  } finally { await app.close(); }
}

(async () => {
  qrPng = createCliQrFixture();
  foreignQrPng = createCliQrFixture('https://other.example.test');
  const channel = process.env.PLAYWRIGHT_CHANNEL || (fs.existsSync(chromium.executablePath()) ? undefined : 'msedge');
  const browser = await chromium.launch({ headless: true, ...(channel ? { channel } : {}), args: ['--disable-background-networking', '--disable-component-update', '--no-first-run'] });
  const originalCases = [
    ['delayed output save unlocks the current switch after navigation', (app) => delayedOutputAfterNavigation(app, true)],
    ['failed output save rolls back the current switch after navigation', (app) => delayedOutputAfterNavigation(app, false)],
    ['delayed pairing response preserves the current page', delayedPairingAfterNavigation],
    ['unchanged polling preserves cards, filters, and navigation nodes', unchangedPollPreservesNodes],
    ['status/time changes patch only the corresponding card in place', changedCardPatchedInPlace],
    ['filters update immediately and coalesce delayed saves to the latest choice', optimisticFilterCoalesces],
    ['failed filter save keeps the view selected and releases the queue', failedFilterRemainsSelected],
    ['another device cannot override this view until a fresh bootstrap', anotherDevicesPreferenceCannotChangeCurrentView],
    ['clicking the already selected all filter still pins this view', selectingCurrentAllPinsThisView],
    ['network failures preserve the latest view and retry it after recovery', offlineFilterRetriesLatestAfterRefresh],
    ['an unauthorized filter save clears the session and pending retries', unauthorizedFilterSaveClearsRetry],
    ['preferences fetched before a filter change cannot undo its completed save', (app) => stalePreferencesCannotUndoSavedFilter(app, false)],
    ['preferences fetched during a filter save cannot undo it after completion', (app) => stalePreferencesCannotUndoSavedFilter(app, true)],
    ['old snapshot cannot overwrite a new login', oldSnapshotCannotOverwriteNewLogin],
    ['old unauthorized response cannot log out a new login', oldUnauthorizedResponseCannotLogoutNewLogin],
  ];
  const cases = [
    ...originalCases.map(([name, test]) => [name, async (app) => { await taskView(app.page, 'all'); await test(app); }]),
    ['active/brand/archive filters compose and default excludes finished/unknown/idle', activeAndBrandFiltersCompose, { snapshot: statusSnapshot() }],
    ['archive and restore survive snapshots requested before either change', archiveSurvivesOldSnapshotsAndCanRestore],
    ['a rejected archive returns the task to its previous view', rejectedArchiveRollsBack],
    ['fresh archive changes from another device are not masked by local history', freshArchiveChangesFromAnotherDeviceApply],
    ['avatar/nickname drafts survive polling and save safely after navigation', profileDraftAndDelayedSave],
    ['old me response cannot undo a completed profile save', staleMeCannotUndoProfileSave],
    ['old successful profile save cannot leak into a new session', (app) => oldProfileSaveCannotCrossSession(app, 200)],
    ['old unauthorized profile save cannot log out a new session', (app) => oldProfileSaveCannotCrossSession(app, 401)],
    ['shipping CLI PNG decodes with real jsQR and requires explicit approval', qrImageDecodesWithoutAutoApproval],
    ['foreign and secret-bearing QR links are rejected without API calls', foreignAndSecretUrlsAreRejected],
    ['QR rejection is explicit and delayed approval preserves the current route', qrRejectIsExplicitAndLateApprovalDoesNotNavigate],
    ['login preserves the phone pairing deep link until explicit confirmation', loginPreservesPairingDeepLink, { authenticated: false, route: `pairing-confirm/${QR_ID}` }],
    ['delayed QR details cannot reopen a page after navigation', oldQrDetailsCannotReopenPage],
    ['approval survives returning to the same QR and an older details response', pairingApprovalSurvivesReturnAndOlderDetails],
    ['approval for a previous QR must not cancel the next QR details load', approvalForPreviousQrDoesNotCancelNextQrLoad],
    ['native connection requires explicit approval and escapes device names', (app) => nativeConfirmationIsExplicit(app, true)],
    ['native connection can be rejected without launching another app', (app) => nativeConfirmationIsExplicit(app, false)],
    ['login preserves the native connection request', nativeLoginPreservesRequest, {authenticated:false, route:`native-connect/${NATIVE_ID}`}],
    ['Google login carries only the intended native confirmation return path', nativeGoogleReturnPath, {authenticated:false, googleEnabled:true, route:`native-connect/${NATIVE_ID}`}],
    ['native approval survives older details after same-link navigation', nativeApprovalSurvivesStaleDetails],
    ['previous native approval cannot replace the next device request', nativePreviousApprovalCannotReplaceNext],
    ['expired native links stop offering approval', nativeExpiryDisablesApproval],
    ['native reader revocation requires confirmation and CSRF', nativeDeviceRevocation],
    ['old native devices cannot leak into a new browser session', nativeDeviceListCannotCrossSession],
    ['WebView bridge rejects malformed metadata and cannot be inferred from hints', nativeBridgeIsStrictAndNotInferred],
    ['WebView tracking is a direct gesture and waits for native confirmation', nativeTrackIsDirectAndWaitsForHost],
    ['WebView tracking only starts for current online unarchived running tasks', nativeTrackRequiresCurrentOnlineRunning],
    ['WebView tracking duration and countdown follow native metadata without changing the link protocol',nativeTrackingDurationComesFromHost],
    ['WebView stopping survives missing tasks and blocks outdated task links', nativeStopSurvivesMissingTaskAndStaleLink],
    ['WebView logout uses native revocation while disconnected passwords retain web logout', nativeLogoutUsesOnlyTheHost],
    ['WebView Google actions stay in the external connection flow without authenticating JS', nativeGoogleUsesExternalConnectionFlow, {authenticated:false, googleEnabled:true}],
    ['WebView full App consent discloses permissions and posts its exact version', nativeFullAppConsentIsExplicit],
    ['WebView unknown or expanded permission contracts cannot be approved', nativeConsentFailsClosed],
    ['WebView full App device revocation clearly differs from read-only tracking', nativeFullDeviceRevocationIsClear],
    ['WebView detail account and consent fit a 320px screen', nativeViewsFitSmallScreen, {viewport:{width:320,height:720}}],
    ['camera preview waits for decoded playback and explicit close stops scanning', cameraPreviewRequiresDecodedPlayback],
    ['camera cancellation stops a stream granted after cancellation', cameraCancellationStopsLateGrantedStream],
    ['camera old playback rejection cannot interrupt a restarted preview', cameraPreviousPlaybackCannotAffectRestart],
    ['camera late grant after navigation never attaches or changes the new page', cameraLateGrantAfterNavigationIsDiscarded],
    ['camera entering background stops and foreground does not automatically restart', cameraBackgroundStopsWithoutAutorestart],
    ['camera playback rejection stops the stream and allows retry', (app)=>cameraPlaybackFailureAndTimeoutAllowRetry(app,false)],
    ['camera missing playback times out and ignores late readiness', (app)=>cameraPlaybackFailureAndTimeoutAllowRetry(app,true)],
    ['camera ended capture track clears the preview and offers retry', cameraEndedTrackClearsPreview],
    ['camera permission denial accurately offers retry without claiming playback failure', cameraPermissionDeniedAllowsRetry],
    ['app navigation root tabs never grow browser history',appNavigationTabsDoNotGrowHistory],
    ['app profile has one discoverable avatar entry with a working return path',appProfileEntryIsSingleAndDiscoverable],
    ['app navigation task returns to its computer source and preserves its tab',appNavigationReturnsToComputerSource],
    ['app navigation native/browser back follows the actual detail stack',appNavigationSystemBackFollowsDetailStack],
    ['app navigation rapid tab taps clear details and retain the latest destination',appNavigationRapidTabsClearDetailStack],
    ['app navigation cold task deep link has a usable parent',appNavigationColdTaskLinkHasParent,{route:'task/codex-one'}],
    ['app navigation tab and source list scroll positions survive switching',appNavigationKeepsTabScrollPositions,{snapshot:scrollingSnapshot()}],
    ...['tasks','task/codex-one','devices','device/test-computer'].map((route)=>[`app loading ${route} never claims missing data before its snapshot`,(app)=>appLoadingNeverShowsFalseEmpty(app,route),{route,deferInitialSnapshot:true}]),
    ['app loading a failed initial snapshot retries before displaying a real empty result',appLoadingFailureRetriesBeforeShowingEmpty,{route:'devices',deferInitialSnapshot:true}],
    ['startup begins identity reads together and fetches tasks only after authentication',startupParallelSnapshotWaitsForAuthentication,{deferStartup:true}],
    ['startup unauthorized identity cannot fetch tasks or begin polling',startupUnauthorizedMeCannotRevealPrefetchedData,{deferStartup:true}],
    ['startup anonymous task request begins only after successful login',startupAnonymousDoesNotFetchTasksBeforeLogin,{authenticated:false,deferInitialSnapshot:true}],
    ['startup superseded identity and snapshot cannot overwrite a successful retry',startupSupersededRequestsCannotLogOutNewStartup,{deferStartup:true}],
    ['startup adopted unauthorized snapshot still expires the confirmed session',startupAdoptedUnauthorizedSnapshotStillExpiresSession,{deferStartup:true}],
    ['startup slow identity can finish after the former ten-second deadline',startupSlowIdentityCanFinishAfterTenSeconds,{deferStartup:true}],
    ['startup deadline retries only its failed read and ignores its late response',startupTimeoutRetriesOnlyFailedRead,{deferStartup:true}],
    ['startup transport recovery stops after one retry',startupTransportRetryIsBounded,{deferStartup:true}],
    ['startup manual reconnect cancels obsolete retry timers and identity responses',startupManualReconnectCancelsOldBackoff,{deferStartup:true}],
    ['startup HTTP errors are not retried as transport failures',startupHttpFailureDoesNotRetry,{deferStartup:true}],
    ['startup recovery after successful login uses GET only and preserves native pairing',loginSuccessReadRecoveryNeverReposts,{authenticated:false,route:`native-connect/${NATIVE_ID}`}],
    ['startup login write transport failure is never automatically reposted',loginWriteFailureNeverRetries,{authenticated:false}],
    ['startup online events do not start overlapping attempts',startupOnlineEventsDoNotDuplicateRequests,{deferStartup:true}],
    ['response body may arrive after its headers within the original deadline',responseBodyWithinDeadlineUpdatesNormally,{streamBody:true}],
    ['response body timeout closes real transport and releases polling for retry',responseBodyTimeoutReleasesPollForRetry,{streamBody:true}],
    ['response body late old-session 401 cannot log out a new session',(app)=>responseBodyOldSessionCannotAffectNewLogin(app,false),{streamBody:true}],
    ['response body old-session timeout cannot block the new session permanently',(app)=>responseBodyOldSessionCannotAffectNewLogin(app,true),{streamBody:true}],
    ['response body external cancellation remains effective after response headers',(app)=>responseBodyExternalAbortAndDeadline(app,false),{streamBody:true,exposeTestApi:true}],
    ['response body caller signal does not disable the internal ten-second deadline',(app)=>responseBodyExternalAbortAndDeadline(app,true),{streamBody:true,exposeTestApi:true}],
    ['response body completion removes timeout and external abort listener',responseBodyCompletedRequestDetachesCancellation,{streamBody:true,exposeTestApi:true}],
    ['response body already-aborted caller cannot begin a request',responseBodyAlreadyAbortedSignalNeverStartsTransport,{streamBody:true,exposeTestApi:true}],
  ];
  const runCases = process.env.UI_SCREENSHOTS_ONLY === '1' ? [] : cases.filter(([name]) => !process.env.UI_TEST_MATCH || name.includes(process.env.UI_TEST_MATCH));
  let passed = 0;
  try {
    for (const [name, test, options] of runCases) {
      try { await withApp(browser, test, options); passed++; console.log(`PASS ${name}`); }
      catch (error) { console.error(`FAIL ${name}\n${error.stack || error}`); }
    }
    if (process.env.UI_SCREENSHOT_DIR) await captureSmallPreviews(browser, process.env.UI_SCREENSHOT_DIR);
  } finally { await browser.close(); }
  if (runCases.length) console.log(`${passed}/${runCases.length} real-browser tests passed (${channel || 'bundled Chromium'}).`);
  if (passed !== runCases.length) process.exitCode = 1;
})().catch((error) => { console.error(error); process.exitCode = 1; });
