"use strict";
const SHELL_CACHE = "agent-monitor-shell-v19";
const SHELL = ["/", "/index.html", "/app.js", "/features.js", "/style.css", "/manifest.webmanifest", "/assets/vendor/jsQR-1.4.0.js", "/assets/app-icon.svg", "/assets/app-icon-192.png", "/assets/app-icon-512.png", "/assets/apple-touch-icon.png", "/assets/claude-official.png", "/assets/codex-official.png", "/assets/misans.css"];
self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL.map((path) => new Request(path, { cache: "reload" })))).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((names) => Promise.all(names.filter((name) => name.startsWith("agent-monitor-shell-") && name !== SHELL_CACHE).map((name) => caches.delete(name)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (event) => {
  const request = event.request, url = new URL(request.url);
  // Account, task, output, pairing and device data always go directly to the server.
  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;
  const staticAsset = SHELL.includes(url.pathname) || /^\/assets\/[A-Za-z0-9_./-]+\.(?:woff2?|ttf|png|svg|css)$/.test(url.pathname);
  if (!staticAsset) return;
  event.respondWith(caches.open(SHELL_CACHE).then(async (cache) => {
    // The worker version owns the entire shell, including navigation query variants.
    const key = url.pathname;
    const cached = await cache.match(key);
    if (cached) return cached;
    return fetch(request).then((response) => {
      if (response.ok && response.type === "basic") {
        const copy = response.clone();
        event.waitUntil(cache.put(key, copy));
      }
      return response;
    }).catch(async () => (request.mode === "navigate" ? await cache.match("/") : null) || Response.error());
  }));
});
