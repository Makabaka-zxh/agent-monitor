"use strict";

(() => {
  const $ = (selector) => document.querySelector(selector);
  const main = $("#main"), header = $("#header"), navigation = $("#navigation");
  const connection = $("#connection"), notice = $("#notice");
  const renderedMarkup = new WeakMap();
  const renderedViews = new WeakMap();
  const nodeKey = (node) => node?.nodeType === 1 ? (node.getAttribute("data-key") || node.id || "") : "";
  const sameKind = (node, next) => node && node.nodeType === next.nodeType && node.nodeName === next.nodeName;
  function patchNode(node, next) {
    if (node.isEqualNode(next) && node.tagName !== "INPUT") return;
    if (node.nodeType !== 1) {
      if (node.nodeValue !== next.nodeValue) node.nodeValue = next.nodeValue;
      return;
    }
    for (const attribute of Array.from(node.attributes)) {
      if (!next.hasAttribute(attribute.name)) node.removeAttribute(attribute.name);
    }
    for (const attribute of Array.from(next.attributes)) {
      if (node.getAttribute(attribute.name) !== attribute.value) node.setAttribute(attribute.name, attribute.value);
    }
    if (node.tagName === "INPUT" && node.type === "checkbox") node.checked = next.checked;
    patchChildren(node, next);
  }
  function patchChildren(parent, nextParent) {
    const keyed = new Map(Array.from(parent.childNodes).filter(nodeKey).map((node) => [nodeKey(node), node]));
    let cursor = parent.firstChild;
    for (const next of Array.from(nextParent.childNodes)) {
      const key = nodeKey(next);
      let node = key ? keyed.get(key) : (!nodeKey(cursor) && sameKind(cursor, next) ? cursor : null);
      if (!sameKind(node, next)) node = null;
      if (!node) {
        node = next.cloneNode(true);
        parent.insertBefore(node, cursor);
      } else {
        if (node !== cursor) parent.insertBefore(node, cursor);
        patchNode(node, next);
      }
      cursor = node.nextSibling;
    }
    while (cursor) {
      const obsolete = cursor;
      cursor = cursor.nextSibling;
      obsolete.remove();
    }
  }
  function setMarkup(element, markup) {
    const view = element === main || element === header ? (state.user ? `${state.route.page}:${state.route.id}` : "auth") : "persistent";
    const sameView = renderedViews.get(element) === view;
    if (sameView && renderedMarkup.get(element) === markup) return;
    if (sameView) {
      const template = document.createElement("template");
      template.innerHTML = markup;
      patchChildren(element, template.content);
    } else element.innerHTML = markup;
    renderedViews.set(element, view);
    renderedMarkup.set(element, markup);
  }
  const setMain = (markup) => setMarkup(main, markup);
  const setHeader = (markup) => setMarkup(header, markup);
  const setNavigation = (markup) => setMarkup(navigation, markup);
  const setConnection = (markup) => setMarkup(connection, markup);
  const routePositions = new Map();
  const routeKey = (route) => `${route.page}:${route.id}`;
  const rootTabs = ["tasks", "devices", "account"];
  const routePages = [...rootTabs, "task", "device", "profile", "sync", "sessions", "pairing", "scanner", "pairing-confirm", "native-connect"];
  let navigationEntry = null, pendingNavigation = null;
  history.scrollRestoration = "manual";
  const activeMotions = new WeakMap();
  function animateRoute() {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    for (const target of [header, main]) {
      const bounds = target.getBoundingClientRect();
      if (!bounds.height || bounds.bottom <= 0 || bounds.top >= window.innerHeight) continue;
      activeMotions.get(target)?.cancel();
      activeMotions.set(target, target.animate([
        { opacity: 0.65, transform: "translateY(6px)" },
        { opacity: 1, transform: "translateY(0)" }
      ], { duration: 200, easing: "cubic-bezier(.2,.8,.2,1)" }));
    }
  }
  const state = {
    user: null, csrf: "", needsSetup: false, connected: false, snapshot: null, snapshotError: false,
    preferences: { tool_filter: "all", sync_output: false }, sessions: null,
    pairing: null, lastSuccess: 0, route: { page: "tasks", id: "" },
    loading: true, busy: false, poll: null, pollInFlight: false, sessionGeneration: 0, bootstrapRevision: 0, bootstrapRequest: null,
    preferenceRevision: 0, filterSaving: false, pendingFilter: null, localFilter: null,
    filterNotice: null, taskView: "active", profileDraft: null, profileBusy: false,
    googleEnabled: false, pairingRequest: null, pairingError: "", pairingRevision: 0,
    profileRevision: 0, archiveRevision: 0, archiveOverrides: new Map(),
    nativeSessions: [], nativeSessionsError: false, sessionsRevision: 0,
    nativeRequest: null, nativeError: "", nativeRevision: 0, nativeBusy: false,
    nativePresentation: null
  };
  const toolNames = { all: "全部", claude: "Claude Code", codex: "Codex" };
  const statuses = {
    running: "执行中", waiting: "等待批准", completed: "本轮结束",
    error: "执行出错", unknown: "状态未知", idle: "空闲", stale: "状态已过期"
  };
  const paths = {
    archive: '<rect x="3" y="3" width="18" height="4" rx="1"/><path d="M5 7v13h14V7M9 12h6"/>',
    restore: '<path d="M3 10a9 9 0 1 1 2 9M3 4v6h6M12 7v5l3 2"/>',
    scan: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M7 12h10"/>',
    tasks: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
    monitor: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
    laptop: '<rect x="5" y="4" width="14" height="12" rx="2"/><path d="m5 16-3 4h20l-3-4"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    cloud: '<path d="M6 18a5 5 0 1 1 1-9.9 6 6 0 0 1 11.6 1.8A4.2 4.2 0 0 1 18 18H6Z"/>',
    shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m15 5-7 7 7 7"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    link: '<path d="m10 13 4-4m-7 6-1 1a4 4 0 0 0 6 6l3-3a4 4 0 0 0 0-6M17 9l1-1a4 4 0 0 0-6-6L9 5a4 4 0 0 0 0 6" transform="translate(0 0) scale(.95)"/>',
    check: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M19 8a8 8 0 0 0-13-3L4 8m1 8a8 8 0 0 0 13 3l2-3"/>',
    output: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m6 8 3 3-3 3m6 1h5"/>',
    logout: '<path d="M9 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4m6-15 6 7-6 7m-6-7h12"/>',
    copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>'
  };
  const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const icon = (name, extra = "") => `<svg class="icon ${extra}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.monitor}</svg>`;
  const brand = (tool) => `<span class="brand" aria-hidden="true"><img src="/assets/${tool === "claude" ? "claude" : "codex"}-official.png" width="34" height="34" alt=""></span>`;
  const encoded = (value) => esc(encodeURIComponent(String(value)));
  const device = (id) => state.snapshot?.devices?.find((d) => String(d.id) === String(id));
  const tasks = () => Array.isArray(state.snapshot?.tasks) ? state.snapshot.tasks : [];
  const devices = () => Array.isArray(state.snapshot?.devices) ? state.snapshot.devices : [];
  const matches = (task) => state.preferences.tool_filter === "all" || task.tool === state.preferences.tool_filter;
  const dataOld = () => !state.connected || Date.now() - state.lastSuccess > 16000;
  const stale = (task) => dataOld() || !device(task.device_id)?.online;
  const currentStatus = (task) => stale(task) ? "stale" : (Object.hasOwn(statuses, task.status) ? task.status : "unknown");
  const needsAttention = (task) => !stale(task) && ["waiting", "error"].includes(task.status);
  const pill = (status) => `<span class="status ${Object.hasOwn(statuses, status) ? status : "unknown"}">${statuses[status] || statuses.unknown}</span>`;
  const onlineCount = () => dataOld() ? 0 : devices().filter((d) => d.online).length;
  const nativeActive = () => Boolean(state.nativePresentation?.connected && state.nativePresentation.taskId
    && state.nativePresentation.endsAt > 0 && state.nativePresentation.status !== "idle");
  const canTrack = (task) => Boolean(state.user && state.nativePresentation?.connected && task
    && !task.archived && currentStatus(task) === "running");
  const nativeSummary = () => {
    if (!nativeActive()) return "";
    const labels = { tracking: "跟踪中", confirming: "正在确认", reconnecting: "正在重连" };
    const minutes = Math.max(0, Math.ceil((state.nativePresentation.endsAt - Date.now()) / 60000));
    return `${labels[state.nativePresentation.status]}${minutes ? ` · ${minutes} 分钟` : ""}`;
  };
  function nativeLink(action, label, glyph = "bell", taskId = "", classes = "button full spaced", menuRow = false) {
    const href = `agentmonitor://${action}${taskId ? `?task_id=${encodeURIComponent(taskId)}` : ""}`;
    const content = menuRow ? `${icon(glyph)}<div class="grow"><h3>${esc(label)}</h3></div>${icon("chevron", "chevron")}` : icon(glyph) + esc(label);
    return `<a class="${classes}"${menuRow ? ' style="text-decoration:none"' : ""} data-native-action="${action}" data-key="native-${action}"${taskId ? ` data-id="${encoded(taskId)}"` : ""} href="${esc(href)}">${content}</a>`;
  }
  function trackingControl(task) {
    if (!state.nativePresentation) return "";
    if (!state.nativePresentation.connected) return nativeLink("connect", "连接实况", "link", "", "button secondary full spaced");
    if (nativeActive() && state.nativePresentation.taskId === String(task?.id || state.route.id)) {
      return nativeLink("stop", "停止跟踪", "bell", state.nativePresentation.taskId, "button secondary full spaced");
    }
    return canTrack(task) ? nativeLink("track", "开始跟踪", "bell", String(task.id)) : "";
  }
  function parseNativePresentation(value) {
    const keys = ["version", "connected", "taskId", "endsAt", "status"];
    if (!value || typeof value !== "object" || Array.isArray(value)
      || Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))
      || value.version !== 1 || typeof value.connected !== "boolean"
      || typeof value.taskId !== "string" || value.taskId.length > 512 || /[\u0000-\u001f\u007f]/.test(value.taskId)
      || !Number.isSafeInteger(value.endsAt) || value.endsAt < 0 || value.endsAt > 8640000000000000
      || !["idle", "tracking", "confirming", "reconnecting"].includes(value.status)) return null;
    try { encodeURIComponent(value.taskId); } catch (_) { return null; }
    return { version: 1, connected: value.connected, taskId: value.taskId, endsAt: value.endsAt, status: value.status };
  }
  window.addEventListener("agentmonitor:native", (event) => {
    if (window !== window.top || event.target !== window || !(event instanceof CustomEvent)) return;
    const next = parseNativePresentation(event.detail);
    if (!next) return;
    const first = state.nativePresentation === null;
    if (JSON.stringify(next) === JSON.stringify(state.nativePresentation)) return;
    // Presentation only: the native host independently validates its credential.
    state.nativePresentation = next;
    if (state.user && ["task", "account", "profile"].includes(state.route.page)) render();
    else if (!state.user && first && !state.loading) renderAuth();
  });
  const absoluteTime = (value) => {
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date.toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false }) : "尚无记录";
  };
  const serverNow = () => {
    const timestamp = new Date(state.snapshot?.server_time).getTime();
    return Number.isFinite(timestamp) && state.lastSuccess
      ? timestamp + Math.max(0, Date.now() - state.lastSuccess)
      : Date.now();
  };
  const relativeTime = (value) => {
    const timestamp = new Date(value).getTime();
    if (!Number.isFinite(timestamp)) return "尚无记录";
    const age = Math.max(0, serverNow() - timestamp);
    if (age < 60000) return "刚刚";
    if (age < 3600000) return `${Math.floor(age / 60000)} 分钟前`;
    if (age < 86400000) return `${Math.floor(age / 3600000)} 小时前`;
    return absoluteTime(value);
  };

  async function api(path, { method = "GET", body, signal, deferUnauthorized = false, timeoutMs = 10000 } = {}) {
    const generation = state.sessionGeneration;
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && state.csrf) headers["X-CSRF-Token"] = state.csrf;
    const controller = new AbortController();
    let timedOut = false;
    const cancel = () => controller.abort();
    if (signal?.aborted) cancel();
    else signal?.addEventListener("abort", cancel, { once: true });
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    let response, data;
    try {
      response = await fetch(path, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "same-origin", cache: "no-store", signal: controller.signal
      });
      data = response.status === 204 ? {} : await response.json().catch((error) => {
        // Preserve invalid-JSON handling, but never swallow an aborted or failed
        // body read as a successful empty response.
        if (!controller.signal.aborted && error.name === "SyntaxError") return {};
        throw error;
      });
      if (controller.signal.aborted) throw new DOMException("Aborted", "AbortError");
    } catch (error) {
      const failure = new Error(timedOut ? "连接超时，请检查个人同步服务是否运行。"
        : controller.signal.aborted || error.name === "AbortError" ? "请求已取消。"
        : "无法连接个人同步服务，请检查网络和电脑是否开机。");
      failure.kind = timedOut ? "timeout" : controller.signal.aborted || error.name === "AbortError" ? "cancelled" : "network";
      throw failure;
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", cancel);
    }
    if (!response.ok) {
      const error = new Error(typeof data.detail === "string" ? data.detail : "请求未完成，请稍后重试。");
      error.status = response.status;
      if (!deferUnauthorized && response.status === 401 && state.user && generation === state.sessionGeneration) {
        clearSession();
        renderAuth("登录已过期，请重新登录本 App 账号。");
      }
      throw error;
    }
    return data;
  }

  function showError(message) {
    notice.replaceChildren();
    const text = document.createTextNode(message);
    notice.append(text);
    const dismiss = document.createElement("button");
    dismiss.type = "button";
    dismiss.textContent = "关闭提示";
    dismiss.addEventListener("click", () => { notice.hidden = true; });
    notice.append(dismiss);
    notice.hidden = false;
    return text;
  }
  function clearSession() {
    cancelBootstrap();
    closeHelp(false);
    state.sessionGeneration += 1;
    state.bootstrapRevision += 1; state.loading = false;
    state.user = null; state.csrf = ""; state.snapshot = null; state.snapshotError = false; state.sessions = null;
    state.pairing = null; state.connected = false; state.lastSuccess = 0;
    state.filterSaving = false; state.pendingFilter = null; state.localFilter = null; state.filterNotice = null;
    stopCamera(); state.profileDraft = null; state.profileBusy = false; state.pairingRequest = null; state.archiveOverrides.clear(); state.taskView = "active";
    state.nativeSessions = []; state.nativeSessionsError = false; state.sessionsRevision += 1;
    state.nativeRequest = null; state.nativeError = ""; state.nativeRevision += 1; state.nativeBusy = false;
    routePositions.clear();
    navigationEntry = null; pendingNavigation = null;
    state.preferences = { tool_filter: "all", sync_output: false };
    clearTimeout(state.poll);
    navigation.hidden = true; connection.hidden = true; notice.hidden = true;
  }
  function renderConnection() {
    connection.hidden = !state.user || (state.snapshot === null && (!state.snapshotError || ["tasks", "task", "devices", "device"].includes(state.route.page))) || (!dataOld() && state.snapshot !== null);
    if (connection.hidden) return;
    setConnection(state.snapshot
      ? `连接中断，当前状态已过期。<button type="button" data-action="refresh">重新连接</button>`
      : `尚未连接到个人服务。<button type="button" data-action="refresh">重新连接</button>`);
  }
  function readRoute() {
    const [page, id] = location.hash.replace(/^#\/?/, "").split("/");
    let decoded = "";
    try { decoded = decodeURIComponent(id || ""); } catch (_) { /* Invalid links fall back to the empty state. */ }
    return { page: routePages.includes(page) ? page : "tasks", id: decoded };
  }
  const routeHash = (route) => `#/${route.page}${route.id ? `/${encodeURIComponent(route.id)}` : ""}`;
  const defaultTab = (route) => ["tasks", "task"].includes(route.page) ? "tasks"
    : ["devices", "device", "pairing", "scanner", "pairing-confirm"].includes(route.page) ? "devices" : "account";
  const validRoute = (route) => route && routePages.includes(route.page) && typeof route.id === "string" && route.id.length <= 2048;
  function readNavigation() {
    const entry = history.state?.monitorNavigation;
    if (!entry || entry.version !== 1 || !rootTabs.includes(entry.tab) || !validRoute(entry.route)
      || !Array.isArray(entry.parents) || entry.parents.length > 32 || !entry.parents.every(validRoute)
      || routeKey(entry.route) !== routeKey(readRoute())) return null;
    return entry;
  }
  function writeNavigation(route, parents, tab, replace = false) {
    navigationEntry = { version: 1, route: { ...route }, parents: parents.map((parent) => ({ ...parent })), tab };
    history[replace ? "replaceState" : "pushState"]({ monitorNavigation: navigationEntry }, "", routeHash(route));
  }
  function initializeNavigation(route = readRoute(), fresh = false) {
    const restored = fresh ? null : readNavigation();
    if (restored) navigationEntry = restored;
    else {
      const tab = defaultTab(route), root = { page: tab, id: "" };
      writeNavigation(root, [], tab, true);
      if (routeKey(route) !== routeKey(root)) writeNavigation(route, [root], tab);
    }
    state.route = route;
  }
  function activateRoute(route) {
    closeHelp(false); stopCamera();
    const previous = state.route;
    routePositions.set(routeKey(previous), window.scrollY);
    state.route = route;
    if (!state.user) return;
    render();
    window.scrollTo({ top: routePositions.get(routeKey(route)) || 0, behavior: "instant" });
    if (routeKey(previous) !== routeKey(route)) animateRoute();
    main.focus({ preventScroll: true });
    if (route.page === "sessions") loadSessions();
    if (route.page === "pairing-confirm") loadPairingRequest();
    if (route.page === "native-connect") loadNativeRequest();
  }
  function selectTab(page) {
    const route = { page, id: "" };
    if (pendingNavigation) { pendingNavigation = { kind: "tab", route }; return; }
    if (navigationEntry?.parents.length) {
      pendingNavigation = { kind: "tab", route };
      history.go(-navigationEntry.parents.length);
    } else {
      writeNavigation(route, [], page, true);
      activateRoute(route);
    }
  }
  function go(page, id = "") {
    if (rootTabs.includes(page) && !id) { selectTab(page); return; }
    if (pendingNavigation) return;
    const route = { page, id: String(id) };
    if (routeKey(route) === routeKey(state.route)) { render(); return; }
    const parents = navigationEntry ? [...navigationEntry.parents, navigationEntry.route] : [state.route];
    const existing = parents.findLastIndex((parent) => routeKey(parent) === routeKey(route));
    if (existing >= 0) {
      pendingNavigation = { kind: "back" };
      history.go(-(parents.length - existing));
      return;
    }
    // Collapse a pathological loop to its root without unbounded history metadata.
    if (parents.length > 32) { selectTab(navigationEntry?.tab || defaultTab(route)); return; }
    writeNavigation(route, parents, navigationEntry?.tab || defaultTab(route));
    activateRoute(route);
  }
  function goBack(fallback) {
    if (pendingNavigation) return;
    if (navigationEntry?.parents.length) {
      pendingNavigation = { kind: "back" }; history.back();
    } else {
      const tab = defaultTab(fallback);
      writeNavigation(fallback, [], tab, true); activateRoute(fallback);
    }
  }
  function followHistory() {
    const route = readRoute(), restored = readNavigation();
    if (!state.user) { state.route = route; return; }
    if (pendingNavigation?.kind === "tab") {
      // A second tap can replace the destination while traversal is pending.
      if (restored?.parents.length) { history.go(-restored.parents.length); return; }
      const target = pendingNavigation.route; pendingNavigation = null;
      writeNavigation(target, [], target.page, true); activateRoute(target); return;
    }
    pendingNavigation = null;
    if (restored) {
      if (routeKey(state.route) === routeKey(route) && JSON.stringify(navigationEntry) === JSON.stringify(restored)) return;
      navigationEntry = restored;
    } else {
      // Native notification links and ordinary hash deep links enter through
      // the same route path, retaining a known in-app parent when available.
      const root = rootTabs.includes(route.page) && !route.id;
      const parents = root ? [] : navigationEntry ? [...navigationEntry.parents, navigationEntry.route] : [];
      writeNavigation(route, parents.slice(0, 32), root ? route.page : navigationEntry?.tab || defaultTab(route), true);
    }
    activateRoute(route);
  }
  function renderNav() {
    navigation.hidden = !state.user;
    const page = state.route.page;
    const current = navigationEntry?.tab || defaultTab(state.route);
    setNavigation([["tasks", "tasks", "任务"], ["devices", "monitor", "电脑"], ["account", "user", "我的"]].map(([key, glyph, label]) =>
      `<button type="button" data-key="nav-${key}" data-page="${key}" ${key === current ? 'aria-current="page"' : ""}>${icon(glyph)}<span>${label}</span></button>`).join(""));
    const index = String(["tasks", "devices", "account"].indexOf(current));
    if (navigation.style.getPropertyValue("--active-index") !== index) navigation.style.setProperty("--active-index", index);
  }
  const back = (page, label, id = "") => {
    const parent = navigationEntry?.parents.at(-1);
    const labels = { tasks: "工作台", devices: "我的电脑", account: "我的", device: "电脑详情", task: "任务详情", pairing: "添加电脑", scanner: "扫一扫", profile: "个人资料", sync: "个人同步服务", sessions: "登录设备", "pairing-confirm": "确认电脑", "native-connect": "连接 App" };
    return `<button type="button" class="back" data-back="true" data-page="${parent?.page || page}" ${parent?.id || id ? `data-id="${encoded(parent?.id || id)}"` : ""}>${icon("back")}${parent ? labels[parent.page] || label : label}</button>`;
  };
  const helpButton = (key) => `<button type="button" class="info-button" data-help="${esc(key)}" aria-label="查看页面说明" aria-haspopup="dialog" aria-controls="help-dialog">${icon("info")}</button>`;
  const titleRow = (title, key) => `<div class="title-row"><h1>${esc(title)}</h1>${helpButton(key)}</div>`;
  const helpDialog = $("#help-dialog");
  let helpReturn = null;
  function helpContent(key) {
    const task = tasks().find((item) => String(item.id) === state.route.id);
    const computer = device(key === "task" ? task?.device_id : state.route.id);
    const pages = {
      tasks: ["工作台说明", ["进行中显示正在执行、等待批准和需要处理的错误；归档只整理本 App 列表，不会停止或删除电脑上的会话。", "状态由已连接电脑提供。状态未知或过期时，请回到电脑确认。", "等待批准的操作，需要在对应电脑上处理。"]],
      task: ["任务说明", [task?.status_source === "hook" ? "状态来自电脑上的任务事件。" : "状态根据电脑上的本地记录判断；没有明确记录时显示未知。", "“本轮结束”表示响应已结束，是否完成仍需检查结果。"]],
      devices: ["电脑连接", ["每台电脑配对一次，任务会汇总到工作台。", "电脑离线后只保留最后记录，不能据此确认任务仍在运行。"]],
      device: ["电脑说明", [sourceSummary(computer?.sources) + "。", computer?.local ? "这台电脑运行个人同步服务，请在电脑端管理服务。" : "移除只会断开绑定，不会停止电脑上的任务。"]],
      account: ["账号说明", ["换手机后，登录同一账号即可恢复已绑定电脑与偏好。", "Codex 和 Claude Code 继续使用电脑上的登录，本 App 不同步它们的密码。", "字体：MiSans，由小米提供。"]],
      sync: ["同步说明", ["电脑、任务状态与偏好保存在这个个人服务中。其他手机需访问同一服务地址。", "在工作台切换筛选后自动保存，下次登录会恢复。"]],
      sessions: ["登录设备说明", ["退出设备后，该设备需重新登录才能查看任务。", "电脑上的任务不受影响；电脑连接在“我的电脑”中管理。"]],
      pairing: ["配对说明", ["新电脑必须能访问服务地址。localhost 或 127.0.0.1 只指向本机，跨电脑连接需使用可访问的地址。", "连接程序读取任务记录，不复制 Codex 或 Claude Code 的登录凭据。"]],
      profile: ["个人资料", ["头像会在本机裁剪后保存到个人服务，同一账号的设备可同步。", "关联 Google 后可用该账号登录，并获取头像和名字。"]],
      scanner: ["扫码配对", ["在电脑连接程序选择扫码配对，再扫描它显示的二维码。", "确认电脑名称后才会建立连接。"]],
      "pairing-confirm": ["确认电脑", ["请核对电脑名称，确认后这台电脑会同步 Codex 和 Claude Code 的任务状态。", "这不会登录或复制 AI 工具的账号。"]],
      "native-connect": state.nativeRequest?.mode === "full_app"
        ? ["连接 Monitor", ["这台设备将登录完整工作台：查看任务和已同步结果、下载配套文件、回复可续接的任务、归档任务，以及管理个人资料、电脑和登录设备。", "系统实况通知取决于设备支持情况；需要批准的操作仍在任务所在电脑上确认。", "退出这台 App、撤销此连接，或批准连接的浏览器登录失效，都会结束本次 App 连接；电脑上的任务继续运行。"]]
        : ["连接实况 App", ["确认后，这台手机只可查看任务状态，并在你主动跟踪时显示通知。", "可在“登录设备”中断开。退出本次授权所用的浏览器账号后，实况 App 也需重新连接。"]],
      auth: ["登录说明", ["这是本 App 的个人账号，与 Codex 或 Claude Code 账号分开。", "首次建立账号需在运行个人服务的电脑上操作，其他设备使用同一账号登录。", "账号为 3–32 个字符，密码为 12–128 个字符。"]]
    };
    if (key === "device" && Array.isArray(computer?.sources)) {
      pages.device[1][0] = computer.sources.slice(0, 2).map((source) => `${toolNames[source.tool] || source.tool}：${source.detail || (source.available ? "可读取任务记录" : "未找到任务记录")}`).join("\n") || "尚未收到工具状态。";
    }
    if (state.nativePresentation && ["tasks", "task", "account"].includes(key)) {
      pages[key][1].push("在任务详情中开始跟踪，并在手机上选择时长，可随时停止。实况显示由系统通知设置决定。");
    }
    return pages[key] || pages.tasks;
  }
  function openHelp(key, opener) {
    const [title, paragraphs] = helpContent(key);
    $("#help-title").textContent = title;
    const content = $("#help-content");
    content.replaceChildren();
    paragraphs.forEach((text) => {
      const paragraph = document.createElement("p");
      paragraph.textContent = text;
      content.append(paragraph);
    });
    helpReturn = { opener, key, restore: true, hash: location.hash };
    if (!helpDialog.open) helpDialog.showModal();
  }
  function closeHelp(restore = true) {
    if (helpReturn) helpReturn.restore = restore;
    if (helpDialog.open) helpDialog.close();
  }
  helpDialog.addEventListener("close", () => {
    if (helpDialog.open) return;
    const target = helpReturn;
    helpReturn = null;
    $("#help-content").replaceChildren();
    if (!target?.restore || target.hash !== location.hash) return;
    const opener = target.opener.isConnected ? target.opener : Array.from(document.querySelectorAll("[data-help]")).find((button) => button.dataset.help === target.key);
    opener?.focus({ preventScroll: true });
  });
  helpDialog.addEventListener("click", (event) => {
    if (event.target !== helpDialog) return;
    const rect = helpDialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeHelp();
  });
  function heading(title, subtitle = "", backHtml = "") {
    setHeader(`${backHtml}${titleRow(title, state.route.page)}${subtitle ? `<p class="subtitle">${esc(subtitle)}</p>` : ""}`);
  }
  const empty = (title, description = "", glyph = "tasks", action = "") => `<div class="empty">${icon(glyph)}<h2>${esc(title)}</h2>${description ? `<p>${esc(description)}</p>` : ""}${action}</div>`;
  function snapshotPlaceholder(label = "任务") {
    if (state.snapshot !== null) return "";
    return state.snapshotError
      ? empty(`暂时无法读取${label}`, "", "cloud", '<button type="button" class="button secondary" data-action="refresh">重试</button>')
      : `<div class="empty" role="status"><span class="spinner" aria-hidden="true"></span><h2>正在读取${label}</h2></div>`;
  }
  const section = (key, title, list) => list.length ? `<section data-key="${key}">${title ? `<h2 class="section-label">${title}</h2>` : ""}<div class="stack" data-key="task-list">${list.map(taskCard).join("")}</div></section>` : "";
  function filter(scope) {
    const keys = ["all", "claude", "codex"];
    return `<div class="filter glass" data-key="tool-filter" style="--active-index:${keys.indexOf(state.preferences.tool_filter)}" role="group" aria-label="按工具筛选">${keys.map((key) => `<button type="button" data-key="filter-${key}" data-filter="${key}" aria-pressed="${state.preferences.tool_filter === key}"><span>${toolNames[key]}</span><span class="count" aria-hidden="true">${scope.filter((t) => key === "all" || t.tool === key).length}</span></button>`).join("")}</div>`;
  }
  function taskCard(task) {
    const status = currentStatus(task);
    return `<div class="task-entry" data-key="entry-${encoded(task.id)}"><button type="button" class="card card-button task" data-key="task-${encoded(task.id)}" data-page="task" data-id="${encoded(task.id)}" aria-label="${esc(`${task.title || "未命名任务"}，${toolNames[task.tool] || task.tool}，${device(task.device_id)?.name || "未知电脑"}，${statuses[status]}`)}"><div class="task-top">${brand(task.tool)}<div class="task-main"><h3 class="task-title">${esc(task.title || "未命名任务")}</h3><p class="source">${esc(device(task.device_id)?.name || "未知电脑")}</p></div>${icon("chevron", "chevron")}</div><div class="task-body compact"><div class="meta-row">${pill(status)}<span>${esc(relativeTime(task.updated_at))}</span></div></div></button><button type="button" class="archive-quick" data-action="archive-task" data-id="${encoded(task.id)}" aria-label="${task.archived ? "恢复" : "归档"}：${esc(task.title)}" title="${task.archived ? "恢复任务" : "归档任务"}">${icon(task.archived ? "restore" : "archive")}</button></div>`;
  }
  function taskViews() {
    return `<div class="task-views" data-key="task-views" role="group" aria-label="按任务状态筛选">${[["active","进行中"],["all","全部任务"],["archived","已归档"]].map(([key,label]) => `<button type="button" data-task-view="${key}" data-key="view-${key}" aria-pressed="${state.taskView === key}">${label}</button>`).join("")}</div>`;
  }
  function inTaskView(task) {
    if (state.taskView === "archived") return Boolean(task.archived);
    if (task.archived) return false;
    return state.taskView === "all" || ["running", "waiting", "error"].includes(currentStatus(task));
  }
  function renderTasks() {
    const all = tasks(), scope = all.filter(inTaskView), scoped = scope.filter(matches);
    const attention = scoped.filter(needsAttention);
    heading("工作台");
    const blank = state.taskView === "active" ? empty("暂无进行中的任务", "", "tasks", '<button class="button secondary" type="button" data-task-view="all">查看全部任务</button>') : state.taskView === "archived" ? empty("暂无归档任务") : empty("暂无任务");
    setMain(`${filter(scope)}${taskViews()}${snapshotPlaceholder() || (!scoped.length ? blank : section("attention", "需要关注", attention) + section("other", attention.length ? "其他任务" : "", scoped.filter((t) => !needsAttention(t))))}`);
  }
  function renderTask() {
    if (state.snapshot === null) {
      heading("任务详情", "", back("tasks", "工作台"));
      setMain(snapshotPlaceholder() + trackingControl(null)); return;
    }
    const task = tasks().find((t) => String(t.id) === state.route.id);
    if (!task) {
      heading("任务详情", "", back("tasks", "工作台"));
      setMain(empty("未找到这条任务", state.snapshot ? "返回工作台查看。" : "正在读取任务。", "tasks") + trackingControl(null));
      return;
    }
    const computer = device(task.device_id);
    setHeader(`${back("tasks", "工作台")}<div class="detail-heading">${brand(task.tool)}<h1>${esc(task.title || "未命名任务")}</h1>${helpButton("task")}</div><p class="subtitle">${esc(computer?.name || "未知电脑")}</p>`);
    const statusNote = stale(task) ? '<p class="inline-note warning">状态已过期，以下为最后收到的记录。</p>' : task.status === "waiting" ? `<p class="inline-note warning">请到 ${esc(computer?.name || "这台电脑")} 处理批准请求。</p>` : "";
    const output = state.preferences.sync_output ? task.output : "";
    const live = nativeActive() && state.nativePresentation.taskId === String(task.id)
      ? `<div><dt>实况</dt><dd>${esc(nativeSummary())}</dd></div>` : "";
    setMain(`${statusNote}<div class="card"><dl class="details"><div><dt>状态</dt><dd>${pill(currentStatus(task))}</dd></div><div><dt>时间</dt><dd>${esc(absoluteTime(task.updated_at))}</dd></div>${live}${task.project ? `<div><dt>项目</dt><dd>${esc(task.project)}</dd></div>` : ""}</dl></div>${trackingControl(task)}<h2 class="section-label">最近输出</h2><div class="card"><pre class="output ${output ? "" : "empty-output"}">${esc(output || (state.preferences.sync_output ? "暂无输出。" : "输出同步已关闭。"))}</pre></div>${!state.preferences.sync_output ? '<button type="button" class="text-button" data-page="sync">设置输出同步</button>' : ""}<button type="button" class="button secondary full spaced" data-action="archive-task" data-id="${encoded(task.id)}">${icon(task.archived ? "restore" : "archive")}${task.archived ? "恢复任务" : "归档任务"}</button>${computer ? `<button type="button" class="button secondary full spaced" data-page="device" data-id="${encoded(computer.id)}">${icon("monitor")}查看电脑</button>` : ""}`);
  }
  function sourceSummary(sources) {
    if (!Array.isArray(sources)) return "暂未收到工具状态";
    const available = sources.filter((s) => s.available).map((s) => toolNames[s.tool] || s.tool);
    return available.length ? available.join(" · ") : "尚未找到可读取的任务记录";
  }
  function deviceCard(computer) {
    const online = !dataOld() && computer.online;
    return `<button type="button" class="card card-button" data-key="device-${encoded(computer.id)}" data-page="device" data-id="${encoded(computer.id)}"><div class="device-top"><span class="row-icon">${icon(/mac|laptop/i.test(computer.platform) ? "laptop" : "monitor")}</span><div class="grow"><h2>${esc(computer.name)}</h2><p class="source">${esc(computer.platform || "电脑")}</p></div>${icon("chevron", "chevron")}</div><div class="device-state compact"><span class="status ${online ? "completed" : "stale"}">${online ? "在线" : dataOld() ? "状态未知" : "离线"}</span>${!online ? `<span class="source">${esc(relativeTime(computer.last_seen))}</span>` : ""}</div></button>`;
  }
  function renderDevices() {
    heading("我的电脑");
    if (state.snapshot === null) { setMain(snapshotPlaceholder("电脑")); return; }
    setMain(`<div class="stack">${devices().map(deviceCard).join("")}</div>${!devices().length ? empty("还没有连接电脑", "", "monitor") : ""}<button type="button" class="button secondary full spaced" data-page="pairing">${icon("plus")}添加电脑</button>`);
  }
  function renderDevice() {
    if (state.snapshot === null) {
      heading("电脑详情", "", back("devices", "我的电脑"));
      setMain(snapshotPlaceholder("电脑")); return;
    }
    const computer = device(state.route.id);
    if (!computer) {
      heading("电脑详情", "", back("devices", "我的电脑"));
      setMain(empty("未找到这台电脑", "返回电脑列表查看。", "monitor"));
      return;
    }
    const scope = tasks().filter((t) => String(t.device_id) === String(computer.id));
    const shown = scope.filter((task) => !task.archived).filter(matches);
    const online = !dataOld() && computer.online;
    heading(computer.name, `${computer.platform || "电脑"} · ${online ? "在线" : dataOld() ? "状态未知" : "离线"}`, back("devices", "我的电脑"));
    setMain(`${!online ? `<p class="inline-note warning">最后连接 ${esc(relativeTime(computer.last_seen))}，当前任务状态已过期。</p>` : ""}${filter(scope)}${shown.length ? `<div class="stack">${shown.map(taskCard).join("")}</div>` : empty("暂无任务")}${!computer.local ? `<button type="button" class="button danger full spaced" data-action="remove-device" data-id="${encoded(computer.id)}">移除电脑</button>` : ""}`);
  }
  const menu = (page, glyph, title) => `<button type="button" class="card card-button menu-row compact" data-page="${page}">${icon(glyph)}<div class="grow"><h3>${title}</h3></div>${icon("chevron", "chevron")}</button>`;
  const avatarMarkup = (value, extra = "") => `<span class="avatar ${extra}">${/^data:image\/(jpeg|png|webp);base64,/.test(value || "") ? `<img src="${esc(value)}" alt="头像">` : icon("user")}</span>`;
  function renderAccount() {
    heading("我的");
    const native = state.nativePresentation;
    const menuClasses = "card card-button menu-row compact";
    const controls = native ? `${!native.connected ? nativeLink("connect", "连接实况", "link", "", menuClasses, true) : ""}${nativeActive() ? nativeLink("stop", "停止跟踪", "bell", native.taskId, menuClasses, true) : ""}${nativeLink("settings", "系统实况设置", "bell", "", menuClasses, true)}` : "";
    const logout = native?.connected ? nativeLink("logout", "退出登录", "logout", "", "button secondary full spaced")
      : '<button type="button" class="button secondary full spaced" data-action="logout">' + icon("logout") + '退出登录</button>';
    setMain(`<button type="button" class="profile profile-button" data-page="profile" aria-label="个人资料">${avatarMarkup(state.user.avatar)}<span class="grow"><h2>${esc(state.user.display_name || state.user.username)}</h2></span>${icon("chevron","chevron")}</button><div class="stack">${menu("sync", "cloud", "个人同步服务")}${menu("sessions", "shield", "登录设备")}${menu("pairing", "link", "添加电脑")}${controls}</div>${logout}`);
  }
  function renderProfile() {
    heading("个人资料", "", back("account", "我的"));
    const draft = state.profileDraft ||= {display_name: state.user.display_name || state.user.username, avatar: state.user.avatar || ""};
    const google = state.googleEnabled ? (state.nativePresentation
      ? nativeLink("google-profile", "关联 Google 账号", "user", "", "button secondary full spaced google-button")
      : '<button type="button" class="button secondary full spaced google-button" data-action="google-link">关联 Google 账号</button>') : "";
    setMain(`<form id="profile-form" class="profile-form"><div class="avatar-editor">${avatarMarkup(draft.avatar,"large")}<label class="button secondary file-button">更换头像<input type="file" id="avatar-file" accept="image/jpeg,image/png,image/webp" ${state.profileBusy ? "disabled" : ""}></label>${draft.avatar ? '<button type="button" class="text-button" data-action="remove-avatar">移除头像</button>' : ""}</div><label class="field"><span>昵称</span><input name="display_name" id="display-name" maxlength="40" required value="${esc(draft.display_name)}" autocomplete="nickname" ${state.profileBusy ? "disabled" : ""}></label><button type="submit" class="button full" ${state.profileBusy ? "disabled" : ""}>${state.profileBusy ? "正在保存" : "保存"}</button></form>${google}`);
  }
  function renderSync() {
    heading("个人同步服务", "", back("account", "我的"));
    setMain(`<div class="card sync-summary">${icon("cloud")}<h2>${dataOld() ? "未连接" : "已连接"}</h2></div><div class="stack spaced"><label class="card setting" for="sync-output"><div class="grow"><h3>同步最近输出</h3><p>保存到个人服务，供手机查看；可能包含代码和项目内容。关闭后清除已同步输出。</p></div><input class="switch" type="checkbox" id="sync-output" ${state.preferences.sync_output ? "checked" : ""} ${state.busy ? "disabled" : ""}></label><div class="card setting"><h3 class="grow">默认筛选</h3><span class="setting-value">${toolNames[state.preferences.tool_filter] || "全部"}</span></div></div>`);
  }
  function renderSessions() {
    heading("登录设备", "", back("account", "我的"));
    const readers = state.nativeSessions.map((session) => `<div class="card"><div class="session-card">${icon("phone")}<div class="grow"><h3>${esc(session.name || "App 设备")}</h3><p>${esc(relativeTime(session.last_seen))}</p></div><span class="badge">${session.mode === "full_app" ? "完整 App" : "实况"}</span></div><div class="session-actions"><button type="button" class="text-button" data-action="revoke-native" data-id="${encoded(session.id)}">${session.mode === "full_app" ? "退出这台 App" : "断开实况设备"}</button></div></div>`).join("");
    setMain(state.sessions === null ? empty("正在读取", "", "shield") : `<div class="stack">${state.sessions.map((session) => `<div class="card"><div class="session-card">${icon("phone")}<div class="grow"><h3>${esc(session.name || "未命名设备")}</h3><p>${esc(relativeTime(session.last_seen))}</p></div>${session.current ? '<span class="badge">当前设备</span>' : ""}</div>${!session.current ? `<div class="session-actions"><button type="button" class="text-button" data-action="revoke-session" data-id="${encoded(session.id)}">退出此设备</button></div>` : ""}</div>`).join("")}${readers}</div>${state.nativeSessionsError ? '<button type="button" class="button secondary full spaced" data-action="reload-sessions">重试读取实况设备</button>' : ""}`);
  }
  function renderPairing() {
    heading("添加电脑", "", back("devices", "我的电脑"));
    const pairing = state.pairing;
    const active = pairing && new Date(pairing.expires_at).getTime() > Date.now();
    setMain(`<button type="button" class="button full" data-page="scanner">${icon("scan")}扫一扫</button><details class="manual-pairing"><summary>使用配对码</summary><ol class="steps"><li>在电脑端连接程序中填写服务地址。</li><li>输入配对码，完成连接。</li></ol><dl class="card pairing-address"><dt>服务地址</dt><dd>${esc(location.origin)}</dd></dl>${active ? `<div class="pairing-code" aria-label="一次性配对码">${esc(pairing.code)}</div><p class="footnote" id="pairing-expiry">${esc(absoluteTime(pairing.expires_at))} 失效 · 仅限一次</p><button type="button" class="button secondary full" data-action="copy-code">${icon("copy")}复制配对码</button>` : `<button type="button" class="button full" data-action="create-pairing">${icon("link")}${pairing ? "配对码已过期，重新生成" : "生成配对码"}</button><p class="footnote">10 分钟有效 · 仅限一次</p>`}</details><button type="button" class="button secondary full spaced" data-page="devices">查看电脑</button>`);
  }
  function render() {
    if (!state.user) return;
    const focused = document.activeElement;
    const focusKey = focused?.tagName === "BUTTON" ? { ...focused.dataset } : null;
    const focusedInputId = focused?.tagName === "INPUT" ? focused.id : "";
    const handlers = { tasks: renderTasks, task: renderTask, devices: renderDevices, device: renderDevice, account: renderAccount, sync: renderSync, sessions: renderSessions, pairing: renderPairing, profile: renderProfile, scanner: renderScanner, "pairing-confirm": renderPairingConfirm, "native-connect": renderNativeConnect };
    (handlers[state.route.page] || renderTasks)();
    renderNav(); renderConnection();
    if (focusKey && !focused.isConnected) {
      const match = Array.from(document.querySelectorAll("button")).find((button) => Object.keys(focusKey).length && Object.entries(focusKey).every(([key, value]) => button.dataset[key] === value));
      match?.focus({ preventScroll: true });
    } else if (focusedInputId && !focused.isConnected) {
      document.getElementById(focusedInputId)?.focus({ preventScroll: true });
    }
  }
  async function loadSessions() {
    const generation = state.sessionGeneration, revision = ++state.sessionsRevision;
    const [browser, native] = await Promise.allSettled([api("/api/sessions"), api("/api/native/devices")]);
    if (generation !== state.sessionGeneration || revision !== state.sessionsRevision || !state.user) return;
    if (browser.status === "fulfilled") state.sessions = Array.isArray(browser.value.sessions) ? browser.value.sessions : [];
    else showError(browser.reason.message);
    state.nativeSessionsError = native.status !== "fulfilled";
    if (native.status === "fulfilled") state.nativeSessions = Array.isArray(native.value.devices) ? native.value.devices : [];
    if (state.route.page === "sessions") render();
  }
  async function refresh(knownMe = null, prefetchedSnapshot = null) {
    if (!state.user || state.pollInFlight) return;
    state.pollInFlight = true;
    if (state.snapshot === null && state.snapshotError) { state.snapshotError = false; render(); }
    const generation = state.sessionGeneration;
    const preferenceRevision = state.preferenceRevision;
    const profileRevision = state.profileRevision, archiveRevision = state.archiveRevision;
    try {
      const snapshot = prefetchedSnapshot ? prefetchedSnapshot.then((result) => {
        if (result.error) throw result.error;
        return result.value;
      }) : api("/api/snapshot");
      const [data, me] = await Promise.all([snapshot, knownMe ? Promise.resolve(knownMe) : api("/api/me")]);
      if (generation !== state.sessionGeneration) return;
      if (!Array.isArray(data.devices) || !Array.isArray(data.tasks)) throw new Error("服务返回的数据不完整，正在等待下一次更新。");
      state.csrf = me.csrf_token || state.csrf;
      if (!state.profileBusy && profileRevision === state.profileRevision) state.user = { ...state.user, ...me.user };
      // A response started before a local edit must not overwrite that edit.
      if (preferenceRevision === state.preferenceRevision && !state.busy && !state.filterSaving) {
        state.preferences = { ...state.preferences, ...me.preferences };
        if (!Object.hasOwn(toolNames, state.preferences.tool_filter)) state.preferences.tool_filter = "all";
        // Cloud defaults initialize a view; they must not undo a choice made here.
        if (state.localFilter !== null) state.preferences.tool_filter = state.localFilter;
      }
      for (const task of data.tasks) {
        const local = state.archiveOverrides.get(task.id);
        if (local) {
          if (!local.pending && archiveRevision === state.archiveRevision) state.archiveOverrides.delete(task.id);
          else task.archived = local.archived;
        }
      }
      state.snapshot = data; state.snapshotError = false; state.lastSuccess = Date.now(); state.connected = true;
      if (!state.preferences.sync_output) {
        state.snapshot.tasks.forEach((task) => { task.output = ""; task.preview = ""; });
      }
      if (["tasks", "task", "devices", "device", "account"].includes(state.route.page) || (state.route.page === "sync" && !state.busy)) render();
      else {
        // Update a focused switch in place rather than replacing its DOM node.
        const outputSwitch = $("#sync-output");
        if (outputSwitch && !state.busy) outputSwitch.checked = state.preferences.sync_output;
        renderConnection();
      }
      // Retry only after a successful refresh, never in an immediate failure loop.
      flushFilter();
    } catch (error) {
      if (generation !== state.sessionGeneration || !state.user) return;
      if (error.status === 401) {
        clearSession(); renderAuth("登录已过期，请重新登录本 App 账号。"); return;
      }
      state.connected = false;
      if (state.snapshot === null) state.snapshotError = true;
      if (["tasks", "task", "devices", "device", "account", "sync"].includes(state.route.page)) render();
      renderConnection();
    } finally {
      state.pollInFlight = false;
      scheduleRefresh();
    }
  }
  function scheduleRefresh() {
    clearTimeout(state.poll);
    if (state.user && !document.hidden) state.poll = setTimeout(() => refresh(), 5000);
  }
  function renderAuth(error = "") {
    closeHelp(false);
    setHeader(""); navigation.hidden = true; connection.hidden = true;
    const local = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
    if (state.needsSetup && !local) {
      setMain(`<div class="auth"><img class="auth-mark" src="/assets/app-icon.svg" alt="">${titleRow("建立账号", "auth")}<p class="subtitle">请先在服务电脑建立账号，再回来登录。</p><button type="button" class="button secondary full spaced" data-action="bootstrap">重新检查</button></div>`);
      return;
    }
    const native = state.nativePresentation && !state.needsSetup;
    const connect = native ? nativeLink("connect", "连接工作台", "link") : "";
    const google = state.googleEnabled ? (native
      ? nativeLink("connect", "使用 Google 登录", "user", "", "button secondary full spaced google-button")
      : '<button type="button" class="button secondary full spaced google-button" data-action="google-login">使用 Google 登录</button>') : "";
    setMain(`<section class="auth"><img class="auth-mark" src="/assets/app-icon.svg" alt="">${titleRow(state.needsSetup ? "建立账号" : "登录", "auth")}${connect}<form class="auth-form" id="auth-form"><label class="field"><span>邮箱 / 账号</span><input name="username" type="text" inputmode="email" enterkeyhint="next" required minlength="3" maxlength="32" autocomplete="username" autocapitalize="none" autocorrect="off" spellcheck="false"></label><label class="field"><span>${state.needsSetup ? "密码 · 至少 12 位" : "密码"}</span><input name="password" type="password" enterkeyhint="next" required minlength="12" maxlength="128" autocomplete="${state.needsSetup ? "new-password" : "current-password"}"></label><label class="field"><span>设备名称</span><input name="device_name" type="text" enterkeyhint="done" required maxlength="60" autocomplete="off" value="${esc(/Android|iPhone|iPad|Mobile/i.test(navigator.userAgent) ? "我的手机" : "我的浏览器")}"></label><p class="form-error" id="auth-error" role="alert" ${error ? "" : "hidden"}>${esc(error)}</p><button type="submit" class="button full">${state.needsSetup ? "建立账号" : "登录"}</button></form>${google}</section>`);
  }
  function cancelBootstrap() {
    const request = state.bootstrapRequest;
    state.bootstrapRequest = null;
    if (request) { clearTimeout(request.feedback); request.controller.abort(); }
  }
  function startupMessage(request, title) {
    if (state.bootstrapRequest !== request || request.controller.signal.aborted) return;
    setHeader(""); navigation.hidden = true; connection.hidden = true;
    setMain(`<div class="empty" role="status"><span class="spinner" aria-hidden="true"></span><h2>${esc(title)}</h2><button type="button" class="button secondary" data-action="bootstrap">重新连接</button></div>`);
  }
  function startupDelay(signal) {
    return new Promise((resolve, reject) => {
      let timer;
      const cancel = () => { clearTimeout(timer); signal.removeEventListener("abort", cancel); reject(Object.assign(new Error("请求已取消。"), { kind: "cancelled" })); };
      if (signal.aborted) { cancel(); return; }
      signal.addEventListener("abort", cancel, { once: true });
      timer = setTimeout(() => { signal.removeEventListener("abort", cancel); resolve(); }, 750);
    });
  }
  async function startupRead(path, request) {
    // Only the two startup GETs retry. Mutations never pass through this helper.
    for (let attempt = 0; ; attempt += 1) {
      try { return await api(path, { signal: request.controller.signal, deferUnauthorized: true, timeoutMs: 25000 }); }
      catch (error) {
        if (request.controller.signal.aborted || attempt || !["network", "timeout"].includes(error.kind)) throw error;
        clearTimeout(request.feedback);
        startupMessage(request, "正在重新连接");
        await startupDelay(request.controller.signal);
      }
    }
  }
  async function bootstrap({ afterLogin = false } = {}) {
    cancelBootstrap();
    clearTimeout(state.poll);
    state.loading = true;
    const revision = ++state.bootstrapRevision, generation = state.sessionGeneration;
    const current = () => revision === state.bootstrapRevision && generation === state.sessionGeneration;
    const request = { controller: new AbortController(), feedback: null };
    state.bootstrapRequest = request;
    startupMessage(request, "正在连接");
    request.feedback = setTimeout(() => startupMessage(request, "连接较慢，正在等待"), 5000);
    try {
      const [info, session] = await Promise.all([
        afterLogin ? Promise.resolve({ needs_setup: false, google_login: state.googleEnabled }) : startupRead("/api/bootstrap", request),
        startupRead("/api/me", request).then((value) => ({ value }), (error) => ({ error }))
      ]);
      if (!current()) return;
      state.needsSetup = Boolean(info.needs_setup); state.googleEnabled = Boolean(info.google_login);
      if (!state.needsSetup) {
        try {
          if (session.error) throw session.error;
          const me = session.value;
          state.user = me.user; state.csrf = me.csrf_token;
          state.preferences = { ...state.preferences, ...me.preferences };
          if (!Object.hasOwn(toolNames, state.preferences.tool_filter)) state.preferences.tool_filter = "all";
          if (afterLogin) {
            const requested = readRoute();
            state.route = ["pairing-confirm", "native-connect"].includes(requested.page) ? requested : { page: "tasks", id: "" };
            closeHelp(false); initializeNavigation(state.route, true);
          } else initializeNavigation();
          render();
          if (state.route.page === "sessions") loadSessions();
          if (state.route.page === "pairing-confirm") loadPairingRequest();
          if (state.route.page === "native-connect") loadNativeRequest();
          refresh(me);
          return;
        } catch (error) {
          if (error.status !== 401) throw error;
          clearSession();
        }
      }
      renderAuth();
    } catch (error) {
      if (!current()) return;
      setHeader("");
      setMain(empty("暂时无法连接", error.message, "cloud", '<button type="button" class="button secondary" data-action="bootstrap">重新连接</button>'));
    } finally {
      if (state.bootstrapRequest === request) cancelBootstrap();
      if (current()) state.loading = false;
    }
  }
  async function login(form) {
    if (state.busy || !form.reportValidity()) return;
    cancelBootstrap();
    state.bootstrapRevision += 1; state.loading = false;
    state.busy = true;
    const submit = form.querySelector('[type="submit"]');
    submit.disabled = true;
    const fields = new FormData(form);
    const errorLabel = $("#auth-error"); errorLabel.hidden = true;
    try {
      await api(state.needsSetup ? "/api/setup" : "/api/login", {
        method: "POST", timeoutMs: 25000, body: { username: String(fields.get("username")).trim(), password: String(fields.get("password")), device_name: String(fields.get("device_name")).trim() }
      });
      form.reset();
      state.needsSetup = false;
      // The write has succeeded. Recovery now only restores its HttpOnly-cookie
      // session; never ask the transport to submit the login again.
      await bootstrap({ afterLogin: true });
    } catch (error) {
      if (errorLabel.isConnected) { errorLabel.textContent = error.message; errorLabel.hidden = false; renderedMarkup.delete(main); }
      else if (state.user) showError(error.message);
    } finally { state.busy = false; if (submit.isConnected) submit.disabled = false; }
  }
  async function confirmAction(title, description, label) {
    const dialog = $("#confirm-dialog");
    $("#confirm-title").textContent = title;
    $("#confirm-description").textContent = description;
    $("#confirm-action").textContent = label;
    dialog.returnValue = "cancel";
    return new Promise((resolve) => {
      dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true });
      dialog.showModal();
    });
  }
  async function savePreferences(update) {
    const generation = state.sessionGeneration;
    state.preferenceRevision += 1;
    try {
      await api("/api/preferences", { method: "PATCH", body: update });
      if (generation !== state.sessionGeneration) return;
      state.preferences = { ...state.preferences, ...update };
      if (update.sync_output === false && state.snapshot) {
        state.snapshot.tasks.forEach((task) => { task.output = ""; task.preview = ""; });
      }
    } finally { if (generation === state.sessionGeneration) state.preferenceRevision += 1; }
  }
  function selectFilter(value) {
    if (!Object.hasOwn(toolNames, value)) return;
    state.localFilter = value;
    if (value === state.preferences.tool_filter) {
      flushFilter();
      return;
    }
    state.pendingFilter = value;
    state.preferences.tool_filter = value;
    state.preferenceRevision += 1;
    render();
    flushFilter();
  }
  async function flushFilter() {
    if (!state.user || state.filterSaving || state.pendingFilter === null) return;
    const generation = state.sessionGeneration;
    state.filterSaving = true;
    try {
      while (state.pendingFilter !== null && generation === state.sessionGeneration) {
        const requested = state.pendingFilter;
        state.pendingFilter = null;
        try {
          await api("/api/preferences", { method: "PATCH", body: { tool_filter: requested } });
          if (generation !== state.sessionGeneration) return;
          if (state.pendingFilter === null && state.filterNotice?.parentNode === notice) {
            notice.hidden = true;
            state.filterNotice = null;
          }
        } catch (error) {
          if (generation !== state.sessionGeneration) return;
          // A failed preference sync must not change the visible task filter.
          // Keep only the latest choice, including one clicked during this save.
          if (state.pendingFilter === null) state.pendingFilter = requested;
          if (state.filterNotice?.parentNode !== notice) {
            state.filterNotice = showError("筛选已切换，偏好暂未同步，稍后自动重试。");
          }
          break;
        }
        state.preferenceRevision += 1;
      }
    } finally {
      if (generation === state.sessionGeneration) {
        state.filterSaving = false;
        state.preferenceRevision += 1;
      }
    }
  }
  let cameraStream = null, cameraTimer = null, cameraGeneration = 0, cancelCameraReady = null, cameraCleanup = null;
  let cameraState = "idle", cameraMessage = "扫描电脑上显示的配对二维码";
  function updateCameraControls() {
    const button = $('[data-action="start-camera"]');
    if (button) {
      button.textContent = { idle: "打开相机", opening: "取消打开", playing: "关闭相机", error: "重试相机" }[cameraState];
      button.dataset.cameraState = cameraState;
      button.setAttribute("aria-pressed", String(cameraState === "playing"));
    }
    if ($("#scan-message")) $("#scan-message").textContent = cameraMessage;
  }
  function stopCamera() {
    cameraGeneration += 1; clearTimeout(cameraTimer);
    cancelCameraReady?.(); cancelCameraReady = null;
    cameraCleanup?.(); cameraCleanup = null;
    cameraStream?.getTracks().forEach((track) => track.stop()); cameraStream = null;
    const video = $("#scan-video");
    if (video) { video.pause(); video.srcObject = null; }
    cameraState = "idle"; cameraMessage = "扫描电脑上显示的配对二维码";
    updateCameraControls();
  }
  function renderScanner() {
    heading("扫一扫", "", back("pairing", "添加电脑"));
    setMain(`<div class="scanner-frame"><video id="scan-video" muted playsinline aria-label="配对二维码取景框"></video><span class="scan-guide" aria-hidden="true"></span></div><p id="scan-message" class="scan-message" role="status">扫描电脑上显示的配对二维码</p><button type="button" class="button full" data-action="start-camera">打开相机</button><div class="scan-options"><label class="text-button file-button">从相册识别<input id="qr-file" type="file" accept="image/*"></label><button type="button" class="text-button" data-page="pairing">使用配对码</button></div><details class="manual-pairing"><summary>输入配对链接</summary><label class="field"><span>电脑显示的链接</span><input id="pairing-link" type="url" inputmode="url" autocomplete="off" placeholder="https://…"></label><button type="button" class="button secondary full spaced" data-action="open-pairing-link">继续</button></details>`);
    updateCameraControls();
  }
  function acceptScan(text) {
    const id = window.MonitorFeatures.pairingId(text);
    stopCamera(); go("pairing-confirm", id);
  }
  async function startCamera() {
    if (["opening", "playing"].includes(cameraState)) {
      stopCamera(); cameraMessage = "相机已关闭"; updateCameraControls(); return;
    }
    stopCamera();
    const generation = cameraGeneration;
    cameraState = "opening"; cameraMessage = "正在打开相机…"; updateCameraControls();
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error("此浏览器无法使用相机，请从相册识别或使用配对码。");
      const stream = await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:"environment"}},audio:false});
      if (generation !== cameraGeneration || state.route.page !== "scanner" || document.hidden) { stream.getTracks().forEach((track) => track.stop()); return; }
      cameraStream = stream;
      const video = $("#scan-video"); video.srcObject = stream;
      // A granted stream or resolved play() alone does not prove a usable
      // preview. Wait for actual playback and a decoded frame with dimensions.
      const ready = await new Promise((resolve, reject) => {
        let settled = false, played = false;
        const events = ["playing", "loadeddata", "canplay", "resize"];
        const cleanup = () => {
          clearTimeout(timeout);
          events.forEach((event) => video.removeEventListener(event, check));
          video.removeEventListener("error", failed);
          if (cancelCameraReady === cancel) cancelCameraReady = null;
        };
        const finish = (error, success = false) => {
          if (settled) return;
          settled = true; cleanup();
          if (error) reject(error); else resolve(success);
        };
        const check = (event) => {
          if (event?.type === "playing") played = true;
          if (played && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && video.videoWidth > 0
            && video.videoHeight > 0 && !video.paused && !video.ended) finish(null, true);
        };
        const failed = () => finish(new Error("相机预览未开始，请重试。"));
        const cancel = () => finish(null);
        const timeout = setTimeout(failed, 10000);
        cancelCameraReady = cancel;
        events.forEach((event) => video.addEventListener(event, check));
        video.addEventListener("error", failed);
        video.muted = true; video.playsInline = true;
        Promise.resolve().then(() => video.play()).then(() => check(), (error) => finish(error));
      });
      if (!ready || generation !== cameraGeneration || state.route.page !== "scanner" || document.hidden) return;
      cameraState = "playing"; cameraMessage = "扫描电脑上显示的配对二维码"; updateCameraControls();
      const interrupted = () => {
        if (generation !== cameraGeneration) return;
        stopCamera(); cameraState = "error"; cameraMessage = "相机预览已中断，请重试。"; updateCameraControls();
      };
      video.addEventListener("error", interrupted);
      stream.getVideoTracks().forEach((track) => track.addEventListener("ended", interrupted));
      cameraCleanup = () => {
        video.removeEventListener("error", interrupted);
        stream.getVideoTracks().forEach((track) => track.removeEventListener("ended", interrupted));
      };
      const scan = async () => {
        if (generation !== cameraGeneration || state.route.page !== "scanner") return;
        try {
          const text = await window.MonitorFeatures.readQr(video);
          if (generation !== cameraGeneration) return;
          if (text) { acceptScan(text); return; }
        } catch (error) {
          if (generation === cameraGeneration) { cameraMessage = error.message; updateCameraControls(); }
        }
        if (generation === cameraGeneration) cameraTimer = setTimeout(scan, 300);
      };
      scan();
    } catch (error) {
      if (generation !== cameraGeneration) return;
      const streamGranted = cameraStream !== null;
      stopCamera();
      cameraState = "error";
      cameraMessage = error.name === "NotAllowedError" && !streamGranted ? "未开启相机权限，可从相册识别或使用配对码。" : "相机预览未开始，请重试。";
      if (!navigator.mediaDevices?.getUserMedia) cameraMessage = "此浏览器无法使用相机，请从相册识别或使用配对码。";
      updateCameraControls();
    }
  }
  async function scanImage(file) {
    if (!file) return;
    const generation = state.sessionGeneration;
    try {
      if (file.size > 15 * 1024 * 1024) throw new Error("图片过大，请选择二维码截图。");
      const bitmap = await createImageBitmap(file);
      let text;
      try { text = await window.MonitorFeatures.readQr(bitmap); } finally { bitmap.close(); }
      if (generation !== state.sessionGeneration || state.route.page !== "scanner") return;
      if (!text) throw new Error("未识别到二维码，请换一张清晰的图片。");
      acceptScan(text);
    } catch (error) { if (generation === state.sessionGeneration) showError(error.message); }
  }
  async function loadPairingRequest() {
    const id = state.route.id, generation = state.sessionGeneration;
    const revision = ++state.pairingRevision;
    state.pairingRequest = null; state.pairingError = ""; render();
    try {
      const result = await api(`/api/pairing/requests/${encodeURIComponent(id)}`);
      if (generation !== state.sessionGeneration || revision !== state.pairingRevision || state.route.page !== "pairing-confirm" || state.route.id !== id) return;
      state.pairingRequest = result;
    } catch (error) {
      if (generation !== state.sessionGeneration || revision !== state.pairingRevision || state.route.page !== "pairing-confirm" || state.route.id !== id) return;
      state.pairingError = error.message;
    }
    render();
  }
  function renderPairingConfirm() {
    heading("确认电脑", "", back("pairing", "添加电脑"));
    const request = state.pairingRequest;
    if (!request) { setMain(empty(state.pairingError || "正在读取电脑信息", "", "monitor")); return; }
    const active = request.status === "pending" && new Date(request.expires_at).getTime() > Date.now();
    const done = ["approved", "consumed"].includes(request.status);
    setMain(`<div class="card pairing-confirm-card">${icon("monitor")}<h2>${esc(request.name)}</h2><p class="subtitle">${esc(request.platform)}</p></div>${active ? `<div class="stack spaced"><button class="button full" type="button" data-action="approve-pairing" ${state.busy ? "disabled" : ""}>连接这台电脑</button><button class="button secondary full" type="button" data-action="reject-pairing" ${state.busy ? "disabled" : ""}>取消配对</button></div>` : `<div class="empty"><h2>${done ? "已确认连接" : request.status === "denied" ? "已取消配对" : "二维码已过期"}</h2>${done ? '<p>电脑连接程序将自动完成连接。</p>' : '<p>请在电脑上重新发起配对。</p>'}<button type="button" class="button secondary" data-page="devices">查看电脑</button></div>`}`);
  }
  async function decidePairing(approve) {
    if (state.busy || !state.pairingRequest) return;
    const id = state.route.id, generation = state.sessionGeneration;
    const confirmedRequest = { ...state.pairingRequest };
    state.busy = true; render();
    try {
      await api(`/api/pairing/requests/${encodeURIComponent(id)}/${approve ? "approve" : "reject"}`, {method:"POST"});
      if (generation !== state.sessionGeneration) return;
      if (state.route.page === "pairing-confirm" && state.route.id === id) {
        state.pairingRevision += 1;
        state.pairingRequest = { ...confirmedRequest, status: approve ? "approved" : "denied" };
      }
    } catch (error) { if (generation === state.sessionGeneration) showError(error.message); }
    finally { if (generation === state.sessionGeneration) { state.busy = false; if (state.route.page === "pairing-confirm") render(); } }
  }
  async function loadNativeRequest() {
    const id = state.route.id, generation = state.sessionGeneration, revision = ++state.nativeRevision;
    state.nativeRequest = null; state.nativeError = "";
    if (!/^[A-Za-z0-9_-]{32}$/.test(id)) { state.nativeError = "连接链接无效，请在实况 App 中重新发起。"; render(); return; }
    render();
    try {
      const result = await api(`/api/native/pairing/${encodeURIComponent(id)}`);
      if (generation !== state.sessionGeneration || revision !== state.nativeRevision || state.route.page !== "native-connect" || state.route.id !== id) return;
      state.nativeRequest = result;
    } catch (error) {
      if (generation !== state.sessionGeneration || revision !== state.nativeRevision || state.route.page !== "native-connect" || state.route.id !== id) return;
      state.nativeError = error.message;
    }
    render();
  }
  function renderNativeConnect() {
    const request = state.nativeRequest;
    const full = request?.mode === "full_app", appName = full ? "Monitor" : "实况 App";
    heading(full ? "连接 Monitor" : "连接实况 App", "", back("account", "我的"));
    if (!request) { setMain(empty(state.nativeError || "正在读取设备信息", "", "phone", state.nativeError ? '<button type="button" class="button secondary" data-action="reload-native">重试</button>' : "")); return; }
    const active = request.status === "pending" && new Date(request.expires_at).getTime() > Date.now();
    const done = ["approved", "consumed"].includes(request.status);
    const consent = nativeConsent(request);
    const permissions = full ? '<div class="card spaced"><h3>完整工作台</h3><p class="spaced">查看任务与结果、下载文件、回复和归档任务，管理资料、同步设置与已连接设备。</p></div>' : "";
    setMain(`<div class="card pairing-confirm-card">${icon("phone")}<h2>${esc(request.device_name)}</h2>${full ? "" : '<p class="subtitle">只读查看状态与跟踪任务</p>'}</div>${permissions}${active && !consent ? '<div class="empty"><h2>连接权限已更新</h2><p>请更新 App 后重新发起连接。</p></div>' : ""}${active ? `<div class="stack spaced">${consent ? `<button class="button full" type="button" data-action="approve-native" ${state.nativeBusy ? "disabled" : ""}>连接这台设备</button>` : ""}<button class="button secondary full" type="button" data-action="reject-native" ${state.nativeBusy ? "disabled" : ""}>取消连接</button></div>` : `<div class="empty"><h2>${done ? "已确认连接" : request.status === "rejected" ? "已取消连接" : "连接链接已过期"}</h2>${done ? `<a class="button full" href="agentmonitor://paired?request_id=${encodeURIComponent(state.route.id)}">返回${appName === "Monitor" ? " Monitor" : "实况 App"}</a>` : `<p>请在${appName === "Monitor" ? " Monitor " : "实况 App "}中重新发起连接。</p>`}</div>`}`);
  }
  function nativeConsent(request) {
    const mode = request?.mode === undefined ? "read_only" : request.mode;
    const scopes = mode === "read_only" ? ["task_status"] : mode === "full_app"
      ? ["task_status", "workspace_read", "workspace_write"] : null;
    if (!scopes) return null;
    const version = mode === "full_app" ? "full_app_v1" : "read_only_v1";
    if (request.consent_version !== undefined && request.consent_version !== version) return null;
    if (mode === "full_app" && (request.consent_version !== version || !Array.isArray(request.scopes))) return null;
    if (request.scopes !== undefined && (!Array.isArray(request.scopes) || request.scopes.length !== scopes.length
      || new Set(request.scopes).size !== scopes.length || !request.scopes.every((scope) => scopes.includes(scope)))) return null;
    return { mode, consent_version: version };
  }
  async function decideNative(approve) {
    if (state.nativeBusy || !state.nativeRequest || state.nativeRequest.status !== "pending") return;
    if (new Date(state.nativeRequest.expires_at).getTime() <= Date.now()) { render(); return; }
    const consent = nativeConsent(state.nativeRequest);
    if (approve && !consent) { showError("连接权限已更新，请在 App 中重新发起连接。"); return; }
    const id = state.route.id, generation = state.sessionGeneration, confirmed = { ...state.nativeRequest };
    state.nativeBusy = true; render();
    try {
      await api(`/api/native/pairing/${encodeURIComponent(id)}/${approve ? "approve" : "reject"}`, {
        method: "POST", body: approve && consent?.mode === "full_app" ? consent : undefined
      });
      if (generation !== state.sessionGeneration) return;
      if (state.route.page === "native-connect" && state.route.id === id) {
        state.nativeRevision += 1;
        state.nativeRequest = { ...confirmed, status: approve ? "approved" : "rejected" };
      }
    } catch (error) { if (generation === state.sessionGeneration) showError(error.message); }
    finally { if (generation === state.sessionGeneration) { state.nativeBusy = false; if (state.route.page === "native-connect") render(); } }
  }
  async function changeAvatar(file) {
    if (!file || state.profileBusy) return;
    const generation = state.sessionGeneration;
    state.profileBusy = true;
    try {
      const avatar = await window.MonitorFeatures.avatar(file);
      if (generation === state.sessionGeneration && state.profileDraft) state.profileDraft.avatar = avatar;
    } catch (error) { if (generation === state.sessionGeneration) showError(error.message); }
    finally { if (generation === state.sessionGeneration) { state.profileBusy = false; if (state.route.page === "profile") render(); } }
  }
  async function saveProfile(form) {
    if (state.profileBusy || !form.reportValidity()) return;
    const generation = state.sessionGeneration;
    state.profileDraft.display_name = String(new FormData(form).get("display_name")).trim();
    if (!state.profileDraft.display_name) { showError("请填写昵称。"); return; }
    state.profileBusy = true; state.profileRevision += 1; render();
    try {
      const result = await api("/api/profile", {method:"PATCH",body:{...state.profileDraft}});
      if (generation !== state.sessionGeneration) return;
      state.user = result.user; state.profileDraft = null;
      if (state.route.page === "profile") go("account"); else if (state.route.page === "account") render();
    } catch (error) { if (generation === state.sessionGeneration) showError(error.message); }
    finally { if (generation === state.sessionGeneration) { state.profileBusy = false; state.profileRevision += 1; if (state.route.page === "profile") render(); } }
  }
  async function archiveTask(id, archived) {
    const task = tasks().find((item) => String(item.id) === id);
    if (!task || state.archiveOverrides.get(id)?.pending) return;
    const value = archived ?? !task.archived, previous = Boolean(task.archived), generation = state.sessionGeneration;
    const change = {archived:value,pending:true};
    state.archiveRevision += 1;
    state.archiveOverrides.set(id,change); task.archived = value; render();
    try {
      await api(`/api/tasks/${encodeURIComponent(id)}/archive`, {method:"PATCH",body:{archived:value}});
      if (generation !== state.sessionGeneration) return;
      change.pending = false;
      state.archiveRevision += 1;
      if (state.route.page === "task" && state.route.id === id) go("tasks");
    } catch (error) {
      if (generation !== state.sessionGeneration) return;
      state.archiveOverrides.delete(id);
      state.archiveRevision += 1;
      const current = tasks().find((item) => String(item.id) === id);
      if (current) current.archived = previous;
      render(); showError(error.message);
    }
  }
  async function startGoogle(mode) {
    if (state.busy || state.nativePresentation) return;
    state.busy = true;
    try {
      const payload = {mode};
      const requested = readRoute();
      if (requested.page === "native-connect" && /^[A-Za-z0-9_-]{32}$/.test(requested.id)) payload.return_path = `/#/native-connect/${requested.id}`;
      const result = await api("/api/auth/google/start", {method:"POST",body:payload});
      const url = new URL(result.url);
      if (url.origin !== "https://accounts.google.com") throw new Error("Google 登录地址无效。");
      location.assign(url.href);
    } catch (error) { showError(error.message); state.busy = false; }
  }
  document.addEventListener("input", (event) => {
    if (event.target.id === "display-name" && state.profileDraft) state.profileDraft.display_name = event.target.value;
  });

  async function handleAction(action, button) {
    if (action === "start-camera") { startCamera(); return; }
    if (action === "open-pairing-link") { try { acceptScan($("#pairing-link").value.trim()); } catch (error) { showError(error.message); } return; }
    if (action === "approve-pairing" || action === "reject-pairing") { decidePairing(action === "approve-pairing"); return; }
    if (action === "approve-native" || action === "reject-native") { decideNative(action === "approve-native"); return; }
    if (action === "reload-native") { loadNativeRequest(); return; }
    if (action === "reload-sessions") { loadSessions(); return; }
    if (action === "remove-avatar") { if (!state.profileBusy) { state.profileDraft.avatar = ""; render(); } return; }
    if (action === "archive-task") { archiveTask(decodeURIComponent(button.dataset.id)); return; }
    if (action === "google-login" || action === "google-link") { startGoogle(action === "google-link" ? "link" : "login"); return; }
    if (action === "bootstrap") { bootstrap(); return; }
    if (action === "refresh") { refresh(); return; }
    if (state.busy) return;
    const id = decodeURIComponent(button.dataset.id || "");
    if (action === "logout" && !await confirmAction("退出当前登录？", "下次打开时需重新登录。已连接电脑上的任务会继续运行。", "退出登录")) return;
    if (action === "revoke-session" && !await confirmAction("退出这台登录设备？", "这台设备将无法继续查看任务，需要重新登录。电脑上的任务会继续运行。", "退出此设备")) return;
    if (action === "revoke-native") {
      const full = state.nativeSessions.find((session) => String(session.id) === id)?.mode === "full_app";
      if (!await confirmAction(full ? "退出这台 App？" : "断开这台实况设备？", full
        ? "这台 App 将退出工作台并停止实况通知，下次使用需重新连接。电脑上的任务会继续运行。"
        : "这台设备将停止获取任务状态，下次使用需重新连接。", full ? "退出 App" : "断开设备")) return;
    }
    if (action === "remove-device" && !await confirmAction("移除这台电脑？", "这台电脑的绑定和已同步记录将被移除。电脑上的任务会继续运行，重新连接需要再次配对。", "移除电脑")) return;
    state.busy = true; button.disabled = true;
    try {
      if (action === "logout") {
        await api("/api/logout", { method: "POST" });
        clearSession(); renderAuth();
      } else if (action === "revoke-session") {
        await api(`/api/sessions/${encodeURIComponent(id)}`, { method: "DELETE" });
        await loadSessions();
      } else if (action === "revoke-native") {
        await api(`/api/native/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
        await loadSessions();
      } else if (action === "remove-device") {
        await api(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
        if (state.snapshot) {
          state.snapshot.devices = devices().filter((d) => String(d.id) !== id);
          state.snapshot.tasks = tasks().filter((t) => String(t.device_id) !== id);
        }
        go("devices"); refresh();
      } else if (action === "create-pairing") {
        state.pairing = await api("/api/pairing", { method: "POST" });
        if (state.user && state.route.page === "pairing") renderPairing();
      } else if (action === "copy-code") {
        if (!navigator.clipboard) throw new Error("当前浏览器无法直接复制，请选中配对码手动复制。");
        try { await navigator.clipboard.writeText(state.pairing?.code || ""); }
        catch (_) { throw new Error("暂时无法复制，请选中配对码手动复制。"); }
        button.textContent = "已复制配对码";
        renderedMarkup.delete(main);
      }
    } catch (error) { if (state.user) showError(error.message); }
    finally { state.busy = false; if (button.isConnected) button.disabled = false; }
  }
  document.addEventListener("click", (event) => {
    const link = event.target.closest("a[data-native-action]");
    if (!link) return;
    const action = link.dataset.nativeAction;
    let id = "";
    try { id = decodeURIComponent(link.dataset.id || ""); } catch (_) { event.preventDefault(); return; }
    let allowed = Boolean(state.nativePresentation) && ["connect", "track", "stop", "settings", "logout", "google-profile"].includes(action);
    if (action === "track") allowed = allowed && canTrack(tasks().find((task) => String(task.id) === id));
    if (action === "stop") allowed = allowed && Boolean(state.user) && nativeActive() && state.nativePresentation.taskId === id;
    if (action === "logout") allowed = allowed && Boolean(state.user) && state.nativePresentation.connected;
    if (action === "google-profile") allowed = allowed && Boolean(state.user);
    const expected = `agentmonitor://${action}${["track", "stop"].includes(action) ? `?task_id=${encodeURIComponent(id)}` : ""}`;
    if (!allowed || link.getAttribute("href") !== expected) event.preventDefault();
    // Let a valid, direct link gesture reach the host. No async scheme launch.
  });
  document.addEventListener("click", async (event) => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    if (button.dataset.taskView) {
      state.taskView = button.dataset.taskView; render(); return;
    }
    if (button.dataset.help) { openHelp(button.dataset.help, button); return; }
    if (button.dataset.action === "close-help") { closeHelp(); return; }
    if (button.dataset.back) { goBack({ page: button.dataset.page, id: decodeURIComponent(button.dataset.id || "") }); return; }
    if (button.dataset.page) { go(button.dataset.page, decodeURIComponent(button.dataset.id || "")); return; }
    if (button.dataset.filter) {
      selectFilter(button.dataset.filter);
    } else if (button.dataset.action) handleAction(button.dataset.action, button);
  });
  document.addEventListener("submit", (event) => {
    if (event.target.id === "profile-form") { event.preventDefault(); saveProfile(event.target); return; }
    if (event.target.id === "auth-form") { event.preventDefault(); login(event.target); }
  });
  document.addEventListener("change", async (event) => {
    if (event.target.id === "avatar-file") { changeAvatar(event.target.files?.[0]); return; }
    if (event.target.id === "qr-file") { scanImage(event.target.files?.[0]); return; }
    if (event.target.id !== "sync-output") return;
    const input = event.target;
    if (state.busy) { input.checked = state.preferences.sync_output; return; }
    state.busy = true; input.disabled = true;
    try { await savePreferences({ sync_output: input.checked }); refresh(); }
    catch (error) { if (state.user) showError(error.message); }
    finally {
      state.busy = false;
      const currentInput = $("#sync-output");
      if (currentInput) {
        currentInput.checked = state.preferences.sync_output;
        currentInput.disabled = false;
      }
    }
  });
  window.addEventListener("popstate", followHistory);
  window.addEventListener("hashchange", followHistory);
  document.addEventListener("visibilitychange", () => {
    clearTimeout(state.poll);
    if (document.hidden) stopCamera();
    if (!document.hidden && state.user) {
      if (dataOld()) { state.connected = false; render(); }
      refresh();
      if (state.route.page === "sessions") loadSessions();
      if (state.route.page === "native-connect") loadNativeRequest();
    }
  });
  window.addEventListener("offline", () => { state.connected = false; if (state.user) render(); });
  window.addEventListener("pagehide", stopCamera);
  window.addEventListener("online", () => { if (state.busy || state.bootstrapRequest) return; if (state.user) refresh(); else bootstrap(); });
  setInterval(() => {
    if (document.hidden || !state.user) return;
    if (state.pairing && state.route.page === "pairing" && new Date(state.pairing.expires_at).getTime() <= Date.now() && $(".pairing-code")) renderPairing();
    if (state.route.page === "native-connect" && state.nativeRequest?.status === "pending" && new Date(state.nativeRequest.expires_at).getTime() <= Date.now() && $('[data-action="approve-native"]')) renderNativeConnect();
    if (state.connected && dataOld()) { state.connected = false; render(); }
    if (nativeActive() && ["task", "account"].includes(state.route.page)) render();
  }, 3000);
  if ("serviceWorker" in navigator && window.isSecureContext) {
    window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
  }
  bootstrap();
})();
