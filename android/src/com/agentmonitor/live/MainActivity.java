package com.agentmonitor.live;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native views and HTTPS requests; the external browser is only an account authorization surface. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Set<String> mutations = new HashSet<>();
    private final NativeWorkbenchPolicy.RevisionGate dataRevision = new NativeWorkbenchPolicy.RevisionGate();
    private final Map<String, Parcelable> listPositions = new HashMap<>();
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private NativeUi ui;
    private final LiveActivitySettings liveSettings = new LiveActivitySettings(this, view -> { if (ui != null) ui.applyFont(view); });
    private LinearLayout root, titleBar, titleHeading, bottomBar, pageColumn;
    private FrameLayout body;
    private TextView title, connection, trackingButton, trackingNote, archiveButton, emptyLabel, deviceStatus, profileSaveButton, avatarPickButton;
    private TextView loginStatus, loginNote, loginButton, loginCheckButton;
    private ListView list;
    private ScrollView scroll;
    private NativePages.TaskAdapter taskAdapter;
    private NativePages.DeviceAdapter deviceAdapter;
    private NativePages.Segments toolSegments, scopeSegments;
    private NativePages.TaskDetail taskDetail;
    private NativeTaskResults taskResults;
    private NativeUsagePage nativeUsage;
    private NativeUsagePage.TaskCard taskUsage;
    private JSONObject usageSummary;
    private boolean usageBusy, usageFailed;
    private long nextUsage;
    private long usageRequestRevision, usageAcceptedRevision, usageAlertRevision;
    private Bundle taskResultsState;
    private Intent pendingDocumentResult;
    private int pendingDocumentCode;
    private boolean hasPendingDocumentResult;
    private JSONObject snapshot, account;
    private boolean visible, snapshotBusy, accountBusy, failed, logoutBusy, filterInitialized, profileDraft, lastStale = true, filterBusy, filterDirty, avatarBusy;
    private long sessionEpoch, pageEpoch, lastSuccess, nextSnapshot, actionRevision, apiCooldownUntil, filterRetryAt, avatarGeneration;
    private String loadedToken = "", invalidToken = "", page = "tasks", selectedId = "", tool = "all", scope = "active";
    private String draftName = "", draftAvatar = "", pickerToken = "", pickerPage = "", pairingCode = "", pairingExpires = "", taskReturnDevice = "", pendingAvatarUri = "";
    private long pickerEpoch;
    private EditText nameInput;
    private ImageView avatarView;
    private Switch outputSwitch;
    private boolean updatingSwitch;
    private Dialog durationDialog;
    private LivePresentationSheet serverSettingsDialog;
    private NativeHelpDialog helpDialog;
    private String helpPage = "";
    private String notificationTask, notificationToken, notificationPage;
    private long notificationRevision;
    private int notificationDuration = TrackingPolicy.DEFAULT_DURATION_MINUTES;
    private android.window.OnBackInvokedCallback backCallback;
    private interface Reply { void done(JSONObject value, long requestedPage); }

    private final Runnable heartbeat = new Runnable() { @Override public void run() {
        if (!visible || isDestroyed()) return;
        NativeConnection.tick(MainActivity.this);
        String error = NativeConnection.consumeError(); if (!error.isEmpty()) notice(error);
        reconcileConnection(); updateLogin();
        if (!loadedToken.isEmpty() && workspacePage() && SystemClock.elapsedRealtime() >= nextSnapshot) fetchSnapshot();
        if (!loadedToken.isEmpty() && page.equals("usage") && SystemClock.elapsedRealtime() >= nextUsage) fetchUsage();
        if (nativeUsage != null) nativeUsage.tick();
        if (filterDirty && !filterBusy && SystemClock.elapsedRealtime() >= filterRetryAt) flushFilter();
        boolean nowStale = stale(); if (lastStale != nowStale) { lastStale = nowStale; updateWorkspace(); }
        updateConnection(); updateTracking(); if (taskResults != null) taskResults.tick(); handler.postDelayed(this, 1000);
    }};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); ui = new NativeUi(this);
        taskResultsState = saved == null ? null : saved.getBundle("task_results");
        tool = NativeScreenPolicy.tool(getSharedPreferences("native_ui", MODE_PRIVATE).getString("tool", "all"));
        scope = NativeScreenPolicy.scope(getSharedPreferences("native_ui", MODE_PRIVATE).getString("scope", "active"));
        filterInitialized = getSharedPreferences("native_ui", MODE_PRIVATE).contains("tool");
        if (saved != null) {
            page = safePage(saved.getString("page", "tasks")); selectedId = saved.getString("id", "");
            tool = NativeScreenPolicy.tool(saved.getString("tool", tool)); scope = NativeScreenPolicy.scope(saved.getString("scope", scope));
            profileDraft = saved.getBoolean("profile_draft"); draftName = saved.getString("draft_name", ""); draftAvatar = saved.getString("draft_avatar", "");
            pairingCode = saved.getString("pairing_code", ""); pairingExpires = saved.getString("pairing_expires", "");
            pageEpoch = saved.getLong("page_epoch", 0); taskReturnDevice = saved.getString("task_return_device", ""); pendingAvatarUri = saved.getString("avatar_uri", "");
            Parcelable position = saved.getParcelable("list_position"); if (position != null) listPositions.put(pageKey(), position);
            scrollPositions.put(pageKey(), saved.getInt("scroll_y", 0));
        }
        root = ui.column(); root.setBackground(ui.background());
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(ui.bg);
        if (!ui.dark) root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        titleBar = ui.row(); titleBar.setPadding(ui.dp(18), ui.dp(6), ui.dp(8), ui.dp(4)); root.addView(titleBar, wrap());
        body = new FrameLayout(this); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomBar = ui.row(); bottomBar.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); root.addView(bottomBar, wrap()); setContentView(root); root.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) { backCallback = this::back; getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback); }
        JSONObject store = SessionStore.read(this); if (fullConnection(store)) loadedToken = store.optString("reader_token");
        UsageAlerts.sync(this);
        if (saved != null && !tokenFingerprint(loadedToken).equals(saved.getString("state_fingerprint", ""))) { profileDraft = false; draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = ""; listPositions.clear(); scrollPositions.clear(); }
        if (saved != null && !loadedToken.isEmpty() && tokenFingerprint(loadedToken).equals(saved.getString("picker_fingerprint", ""))) { pickerToken = loadedToken; pickerPage = saved.getString("picker_page", ""); pickerEpoch = saved.getLong("picker_epoch", -1); }
        openNotification(getIntent()); handleReturn(getIntent()); showPage(false);
    }
    private static String safePage(String value) { return value != null && value.matches("tasks|task|devices|device|account|profile|sync|sessions|usage|pairing|pairing-confirm") ? value : "tasks"; }
    private String pageKey() { return page + ":" + selectedId; }
    private boolean workspacePage() { return NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.WORKBENCH; }
    private boolean accountPage() { return NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.ACCOUNT; }
    private void loadCurrentPage() {
        NativeWorkbenchPolicy.loadPage(page, visible, !loadedToken.isEmpty(), () -> {
            // Repeated lifecycle callbacks must not erase a running read's next-poll time.
            if (!snapshotBusy) { nextSnapshot = 0; fetchSnapshot(); }
        }, () -> {
            if (!usageBusy) { nextUsage = 0; fetchUsage(); }
        }, this::fetchAccount);
    }
    private boolean rootPage() { return page.equals("tasks") || page.equals("devices") || page.equals("account"); }
    private boolean fullConnection(JSONObject store) { return ServerSettings.configured(this) && !store.optString("reader_token").equals(invalidToken) && NativeScreenPolicy.connected(store.optString("mode"), store.optBoolean("logout_pending"), store.optBoolean("native_ready") || store.optBoolean("cookie_ready"), store.optString("reader_token"), NativeApi.time(store.optString("expires_at")), System.currentTimeMillis()); }
    private boolean valid(String token, long epoch) { return !isDestroyed() && !isFinishing() && epoch == sessionEpoch && token.equals(loadedToken) && fullConnection(SessionStore.read(this)) && token.equals(SessionStore.token(this)); }
    private boolean samePage(long revision) { return !isDestroyed() && revision == pageEpoch; }
    private void reconcileConnection() {
        JSONObject store = SessionStore.read(this); String token = fullConnection(store) ? store.optString("reader_token") : "";
        if (token.equals(loadedToken)) return;
        sessionEpoch++; actionRevision++; pageEpoch++; loadedToken = token; snapshot = null; account = null; lastSuccess = 0; nextSnapshot = 0; failed = false;
        usageSummary = null; usageBusy = false; usageFailed = false; nextUsage = 0;
        usageRequestRevision = 0; usageAcceptedRevision = 0; usageAlertRevision = 0;
        UsageAlerts.sync(this);
        snapshotBusy = false; accountBusy = false; filterBusy = false; filterDirty = false; apiCooldownUntil = 0; avatarBusy = false; avatarGeneration++; mutations.clear(); listPositions.clear(); scrollPositions.clear(); cancelDuration(); clearNotificationRequest();
        if (token.isEmpty()) { profileDraft = false; draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = ""; page = "tasks"; selectedId = ""; }
        showPage(false); loadCurrentPage();
    }
    private void request(String method, String path, JSONObject payload, Reply done) {
        final String token = loadedToken; final long epoch = sessionEpoch, revision = pageEpoch;
        if (token.isEmpty() || !valid(token, epoch)) return;
        if (SystemClock.elapsedRealtime() < apiCooldownUntil) { done.done(null, revision); return; }
        worker.execute(() -> {
            JSONObject value = null; NativeApi.Failure failure = null;
            try { value = NativeApi.call(method, path, payload, token); } catch (NativeApi.Failure error) { failure = error; }
            final JSONObject result = value; final NativeApi.Failure error = failure;
            handler.post(() -> {
                if (!valid(token, epoch)) return;
                if (error != null && (error.status == 401 || error.status == 403)) { invalidate(token); return; }
                if (error != null && error.status == 429) apiCooldownUntil = Math.max(apiCooldownUntil, SystemClock.elapsedRealtime() + error.retryAfterMs);
                if (error != null && !method.equals("GET") && samePage(revision) && visible) notice(error.getMessage());
                done.done(error == null ? result : null, revision);
            });
        });
    }
    private void mutate(String key, String method, String path, JSONObject payload, Reply done) {
        if (mutations.contains(key) || loadedToken.isEmpty()) return;
        mutations.add(key); dataRevision.started(NativeWorkbenchPolicy.Change.WORKSPACE);
        request(method, path, payload, (result, revision) -> {
            mutations.remove(key); dataRevision.finished(NativeWorkbenchPolicy.Change.WORKSPACE); nextSnapshot = 0;
            if (result != null) done.done(result, revision);
            if (visible && !loadedToken.isEmpty() && workspacePage()) fetchSnapshot();
        });
    }
    private void invalidate(String token) { disconnectLocally(token); if (visible) notice("登录已失效，请重新登录"); }
    private void fetchSnapshot() {
        if (!visible || !workspacePage() || loadedToken.isEmpty() || snapshotBusy || SystemClock.elapsedRealtime() < apiCooldownUntil) return;
        snapshotBusy = true; final long requestedData = dataRevision.capture(); nextSnapshot = SystemClock.elapsedRealtime() + 5000;
        final long requestedUsage = ++usageRequestRevision;
        updateConnection();
        if (snapshot == null && emptyLabel != null) emptyLabel.setText("正在读取");
        request("GET", "/api/native/workbench", null, (value, revision) -> {
            snapshotBusy = false;
            if (!dataRevision.accepts(requestedData)) { logWorkbench("discard_epoch", value); nextSnapshot = 0; updateConnection(); return; }
            if (value == null) { logWorkbench("failure", null); failed = true; nextSnapshot = Math.max(apiCooldownUntil, SystemClock.elapsedRealtime() + 7000); }
            else {
                snapshot = value; failed = false; lastSuccess = SystemClock.elapsedRealtime(); JSONObject preferences = value.optJSONObject("preferences");
                acceptUsage(value.optJSONObject("usage"), requestedUsage);
                logWorkbench("accepted", value);
                if (preferences != null) applyOutputPreference(preferences.optBoolean("sync_output"));
                if (!filterInitialized && preferences != null) { tool = NativeScreenPolicy.tool(preferences.optString("tool_filter")); filterInitialized = true; saveFilters(); if (toolSegments != null) toolSegments.select(tool); }
            }
            updateConnection(); updateWorkspace();
        });
    }
    private void logWorkbench(String outcome, JSONObject value) {
        // Fixed labels and aggregate numbers only: no identities or payload text.
        if (!outcome.equals("accepted") && !outcome.equals("discard_epoch") && !outcome.equals("failure")) return;
        JSONArray computers = value == null ? null : value.optJSONArray("devices");
        JSONArray items = value == null ? null : value.optJSONArray("tasks");
        int online = 0, offline = 0; int[] states = new int[8];
        // Counts may be partial for an oversized response; the total counts make that visible.
        for (int i = 0; computers != null && i < Math.min(computers.length(), 2000); i++) {
            JSONObject item = computers.optJSONObject(i);
            if (item != null && item.optBoolean("online")) online++; else offline++;
        }
        for (int i = 0; items != null && i < Math.min(items.length(), 2000); i++) {
            JSONObject item = items.optJSONObject(i); String status = item == null ? "" : item.optString("status");
            if (item == null || item.isNull("status") || status.isEmpty()) { states[6]++; continue; }
            switch (status) {
                case "running": states[0]++; break; case "waiting": states[1]++; break;
                case "completed": states[2]++; break; case "error": states[3]++; break;
                case "idle": states[4]++; break; case "unknown": states[5]++; break;
                default: states[7]++; break;
            }
        }
        long generated = value == null ? 0 : NativeApi.time(value.optString("generated_at", value.optString("server_time")));
        // Age can include server/phone clock skew; report it without negative durations or timestamps.
        long now = System.currentTimeMillis(); boolean clockAhead = generated > now;
        String age = generated <= 0 ? "unavailable" : String.valueOf(Math.max(0, now - generated));
        try {
            android.util.Log.i("MonitorWorkbench", "outcome=" + outcome
                    + " devices=" + (computers == null ? -1 : computers.length()) + " online=" + online + " offline=" + offline
                    + " tasks=" + (items == null ? -1 : items.length()) + " running=" + states[0] + " waiting=" + states[1]
                    + " completed=" + states[2] + " error=" + states[3] + " idle=" + states[4] + " unknown=" + states[5]
                    + " missing=" + states[6] + " other=" + states[7] + " server_age_ms=" + age + " server_clock_ahead=" + clockAhead);
        } catch (RuntimeException unavailable) { /* Diagnostics must not affect the workbench. */ }
    }
    private void fetchAccount() {
        if (!visible || !accountPage() || loadedToken.isEmpty() || accountBusy) return;
        accountBusy = true; final long requestedData = dataRevision.capture();
        request("GET", "/api/native/account", null, (value, revision) -> {
            accountBusy = false;
            if (!dataRevision.accepts(requestedData)) { if (visible && accountPage()) handler.postDelayed(this::fetchAccount, 300); return; }
            if (value != null) {
                account = value; JSONObject preferences = value.optJSONObject("preferences"); if (preferences != null) applyOutputPreference(preferences.optBoolean("sync_output"));
                if (page.equals("account") || page.equals("sessions") || page.equals("sync") || (page.equals("profile") && !profileDraft)) { savePosition(); showPage(false); }
            }
            else if (account == null && (page.equals("account") || page.equals("profile") || page.equals("sync") || page.equals("sessions"))) showAccountFailure();
        });
    }
    private void acceptUsage(JSONObject value, long revision) {
        if (value == null) return;
        if (revision >= usageAlertRevision) {
            usageAlertRevision = revision;
            UsageAlerts.observe(this, value, loadedToken);
        }
        // Compact workbench values feed reminders, never the history cache or
        // its revision gate, even when navigation overlaps an in-flight read.
        if (!value.optBoolean("history_included", true)) return;
        if (revision < usageAcceptedRevision) return;
        usageAcceptedRevision = revision;
        usageSummary = value; usageFailed = false;
        if (nativeUsage != null) nativeUsage.update(usageSummary, usageBusy, false);
    }
    private void fetchUsage() {
        if (!visible || !page.equals("usage") || loadedToken.isEmpty() || usageBusy || SystemClock.elapsedRealtime() < apiCooldownUntil) return;
        usageBusy = true; nextUsage = SystemClock.elapsedRealtime() + 30000;
        final long requestedUsage = ++usageRequestRevision;
        if (nativeUsage != null) nativeUsage.update(usageSummary, true, usageFailed);
        request("GET", "/api/native/usage", null, (value, revision) -> {
            usageBusy = false; nextUsage = Math.max(apiCooldownUntil, SystemClock.elapsedRealtime() + 30000);
            if (value == null) { if (requestedUsage >= usageAcceptedRevision) usageFailed = true; } else acceptUsage(value, requestedUsage);
            if (nativeUsage != null) nativeUsage.update(usageSummary, false, usageFailed);
        });
    }
    private void navigate(String destination, String id) {
        savePosition(); captureDraft(); if (destination.equals("task")) taskReturnDevice = page.equals("device") ? selectedId : "";
        page = safePage(destination); selectedId = id == null ? "" : id; pageEpoch++; actionRevision++; cancelDuration(); clearNotificationRequest();
        showPage(true); loadCurrentPage();
    }
    private void savePosition() { if (list != null) listPositions.put(pageKey(), list.onSaveInstanceState()); if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY()); }
    private void restorePosition() { if (list != null && listPositions.containsKey(pageKey())) list.onRestoreInstanceState(listPositions.get(pageKey())); if (scroll != null) { final ScrollView target = scroll; int y = scrollPositions.containsKey(pageKey()) ? scrollPositions.get(pageKey()) : 0; target.post(() -> target.scrollTo(0, y)); } }

    private void showPage(boolean animate) {
        if (body == null) return;
        UsageAlerts.dismissSettings(this);
        if (helpDialog != null && (loadedToken.isEmpty() || !pageKey().equals(helpPage))) dismissHelp();
        if (taskResults != null) { taskResults.dispose(); taskResults = null; }
        nativeUsage = null; taskUsage = null;
        list = null; scroll = null; taskAdapter = null; deviceAdapter = null; toolSegments = null; scopeSegments = null; taskDetail = null; trackingButton = null; trackingNote = null; archiveButton = null; emptyLabel = null; deviceStatus = null; nameInput = null; avatarView = null; outputSwitch = null; profileSaveButton = null; avatarPickButton = null;
        loginStatus = null; loginNote = null; loginButton = null; loginCheckButton = null;
        body.animate().cancel(); body.setAlpha(1); body.setTranslationY(0); body.removeAllViews(); titleBar.removeAllViews(); bottomBar.removeAllViews();
        titleHeading = null; connection = null;
        // A 24 dp icon is centered in its 48 dp touch target: 6 + 12 = 18 dp visual inset.
        titleBar.setPadding(ui.dp(!loadedToken.isEmpty() && !rootPage() ? 6 : 18), ui.dp(6), ui.dp(8), ui.dp(4));
        if (loadedToken.isEmpty()) { buildLogin(); return; }
        if (!rootPage()) titleBar.addView(ui.iconButton("back", "返回", this::back));
        titleHeading = ui.column(); titleHeading.setGravity(Gravity.CENTER_VERTICAL); titleHeading.setMinimumHeight(ui.dp(48));
        titleHeading.setBackground(ui.ripple(Color.TRANSPARENT, 14));
        title = ui.text(pageTitle(), rootPage() ? 24 : 19, true); title.setMaxLines(2); titleHeading.addView(title, wrap());
        connection = ui.text(" ", 12, false); connection.setTextColor(ui.warning); connection.setMinLines(1); connection.setSingleLine(true);
        connection.setEllipsize(android.text.TextUtils.TruncateAt.END); connection.setPadding(0, ui.dp(2), 0, 0);
        // Reserve a measured SP line, including at large system fonts, in both connection states.
        connection.setVisibility(View.INVISIBLE); titleHeading.addView(connection, wrap());
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); connection.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleHeading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        titleHeading.setOnClickListener(v -> {
            if (connection == null || connection.getVisibility() != View.VISIBLE || snapshotBusy || SystemClock.elapsedRealtime() < apiCooldownUntil) { updateConnection(); return; }
            loadCurrentPage();
        });
        titleHeading.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (host.isClickable()) info.setClassName("android.widget.Button");
            }
        });
        titleBar.addView(titleHeading, new LinearLayout.LayoutParams(0, -2, 1)); titleBar.addView(ui.iconButton("info", "页面说明", this::help)); buildNavigation();
        switch (page) {
            case "tasks": buildTasks(); break; case "devices": buildDevices(); break; case "task": buildTask(); break; case "device": buildDevice(); break;
            case "account": buildAccount(); break; case "profile": buildProfile(); break; case "sync": buildSync(); break; case "sessions": buildSessions(); break; case "usage": buildUsage(); break;
            case "pairing": buildPairing(); break; case "pairing-confirm": buildPairingConfirm(); break; default: break;
        }
        updateConnection(); updateWorkspace(); restorePosition();
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) { body.setAlpha(.6f); body.setTranslationY(ui.dp(6)); body.animate().alpha(1).translationY(0).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start(); }
    }
    private String pageTitle() { switch (page) { case "tasks": return "工作台"; case "devices": return "我的电脑"; case "account": return "我的"; case "profile": return "个人资料"; case "sync": return "个人同步"; case "sessions": return "登录设备"; case "usage": return "用量"; case "pairing": return "添加电脑"; case "pairing-confirm": return "确认电脑"; case "device": return "电脑详情"; default: return "任务详情"; } }
    private void buildNavigation() {
        bottomBar.setVisibility(View.VISIBLE); String selected = page.equals("devices") || page.equals("device") ? "devices" : page.equals("tasks") || page.equals("task") ? "tasks" : "account";
        for (String key : new String[]{"tasks", "devices", "account"}) {
            LinearLayout item = ui.column(); item.setGravity(Gravity.CENTER); item.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); item.setMinimumHeight(ui.dp(52));
            boolean active = selected.equals(key); item.setBackground(ui.ripple(active ? ui.surface : Color.TRANSPARENT, 18));
            NativeUi.Icon icon = (NativeUi.Icon) ui.icon(key, 21); icon.setInk(active ? ui.accent : ui.muted); item.addView(icon); item.addView(ui.space(3));
            TextView text = ui.text(key.equals("tasks") ? "任务" : key.equals("devices") ? "电脑" : "我的", 12, active); text.setTextColor(active ? ui.accent : ui.muted); text.setGravity(Gravity.CENTER); item.addView(text);
            item.setContentDescription(text.getText()); item.setSelected(active); item.setFocusable(true); item.setOnClickListener(v -> { if (!page.equals(key)) navigate(key, ""); });
            item.setAccessibilityDelegate(NativeUi.buttonAccessibility()); bottomBar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }
    private LinearLayout content() {
        ScrollView view = new ScrollView(this); view.setFillViewport(false); view.setClipToPadding(false); view.setVerticalScrollBarEnabled(false);
        LinearLayout column = ui.column(); column.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(22)); view.addView(column); body.addView(view, new FrameLayout.LayoutParams(-1, -1)); scroll = view; pageColumn = column; return column;
    }
    private void buildLogin() {
        bottomBar.setVisibility(View.GONE); title = ui.text("Monitor", 24, true); titleBar.addView(title);
        LinearLayout column = content(); column.addView(ui.space(32)); column.addView(ui.brand("codex", 44)); column.addView(ui.space(20)); column.addView(ui.text("你的工作，\n随时在眼前。", 28, true)); column.addView(ui.space(28));
        loginStatus = ui.text("", 17, true); column.addView(loginStatus, wrap()); column.addView(ui.space(8));
        loginNote = ui.text("", 14, false); loginNote.setTextColor(ui.muted); column.addView(loginNote, wrap()); column.addView(ui.space(24));
        loginButton = ui.button("登录 Monitor", () -> {
            if (ServerSettings.legacyConnection(this)) { showServerSettings(); return; }
            if (SessionStore.read(this).optBoolean("logout_pending")) { logout(); return; }
            if (!ServerSettings.configured(this)) { showServerSettings(); return; }
            if (!NativeConnection.loginState(this).primaryEnabled) return;
            NativeConnection.start(this, url -> { if (visible && !isDestroyed()) external(url); updateLogin(); });
            updateLogin();
        }); column.addView(loginButton, wrap());
        loginCheckButton = ui.button("我已确认，检查连接", false, () -> { NativeConnection.check(this); updateLogin(); });
        LinearLayout.LayoutParams checkParams = wrap(); checkParams.topMargin = ui.dp(12); column.addView(loginCheckButton, checkParams);
        column.addView(ui.space(18)); column.addView(ui.button("服务器地址", false, this::showServerSettings), wrap());
        column.addView(ui.space(10)); column.addView(ui.button("应用更新", false, () -> startActivity(new Intent(this, NativeUpdateActivity.class))), wrap());
        updateLogin();
    }
    private void updateLogin() {
        if (!loadedToken.isEmpty() || loginButton == null) return;
        if (ServerSettings.legacyConnection(this)) {
            loginStatus.setText("旧连接需要重新设置"); loginNote.setText("先清除本机旧连接，再填写服务器地址。");
            loginButton.setText("处理旧连接"); loginButton.setEnabled(true); loginButton.setAlpha(1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (ServerSettings.requiresRestart(this)) {
            loginStatus.setText("地址已保存"); loginNote.setText("关闭后重新打开 Monitor，即可使用新地址。");
            loginButton.setText("完成服务器设置"); loginButton.setEnabled(true); loginButton.setAlpha(1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (SessionStore.read(this).optBoolean("logout_pending")) {
            loginStatus.setText("完成退出"); loginNote.setText("本机已退出，联网后完成撤销。");
            loginButton.setText(logoutBusy ? "正在完成退出…" : "完成退出"); loginButton.setEnabled(!logoutBusy); loginButton.setAlpha(logoutBusy ? .5f : 1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (!ServerSettings.configured(this)) {
            loginStatus.setText("连接你的电脑"); loginNote.setText("先填写部署好的 HTTPS 服务器地址。");
            loginButton.setText("设置服务器地址"); loginButton.setEnabled(true); loginButton.setAlpha(1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        NativeConnection.LoginState state = NativeConnection.loginState(this);
        if (!android.text.TextUtils.equals(loginStatus.getText(), state.heading)) loginStatus.setText(state.heading);
        if (!android.text.TextUtils.equals(loginNote.getText(), state.note)) loginNote.setText(state.note);
        if (!android.text.TextUtils.equals(loginButton.getText(), state.primary)) loginButton.setText(state.primary);
        loginButton.setEnabled(state.primaryEnabled); loginButton.setAlpha(state.primaryEnabled ? 1f : .5f);
        loginCheckButton.setVisibility(state.secondary.isEmpty() ? View.GONE : View.VISIBLE);
        if (!android.text.TextUtils.equals(loginCheckButton.getText(), state.secondary)) loginCheckButton.setText(state.secondary);
        loginCheckButton.setEnabled(state.secondaryEnabled); loginCheckButton.setAlpha(state.secondaryEnabled ? 1f : .5f);
    }
    private void buildTasks() {
        LinearLayout column = ui.column(); body.addView(column, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout filters = ui.column(); filters.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(6));
        toolSegments = new NativePages.Segments(ui, new String[]{"all", "claude", "codex"}, new String[]{"全部", "Claude Code", "Codex"}, key -> { tool = key; filterInitialized = true; saveFilters(); updateWorkspace(); if (list != null) list.setSelection(0); persistFilter(); }); toolSegments.select(tool); filters.addView(toolSegments.view(), wrap()); filters.addView(ui.space(8));
        scopeSegments = new NativePages.Segments(ui, new String[]{"active", "all", "archived"}, new String[]{"进行中", "全部任务", "已归档"}, key -> { scope = key; saveFilters(); updateWorkspace(); if (list != null) list.setSelection(0); }); scopeSegments.select(scope); filters.addView(scopeSegments.view(), wrap()); column.addView(filters, wrap());
        taskAdapter = new NativePages.TaskAdapter(ui, taskCallbacks()); list = NativePages.list(ui, taskAdapter); attachList(column);
    }
    private NativePages.TaskCallbacks taskCallbacks() { return new NativePages.TaskCallbacks() { public void open(JSONObject task) { navigate("task", task.optString("id")); } public void archive(JSONObject task) { MainActivity.this.archive(task); } }; }
    private void attachList(LinearLayout column) {
        FrameLayout area = new FrameLayout(this); column.addView(area, new LinearLayout.LayoutParams(-1, 0, 1)); area.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyLabel = ui.text("正在读取", 17, false); emptyLabel.setTextColor(ui.muted); emptyLabel.setGravity(Gravity.CENTER); emptyLabel.setPadding(ui.dp(26), ui.dp(30), ui.dp(26), ui.dp(30)); area.addView(emptyLabel, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(emptyLabel);
        emptyLabel.setOnClickListener(v -> { nextSnapshot = 0; fetchSnapshot(); });
    }
    private void buildDevices() {
        LinearLayout column = ui.column(); body.addView(column, new FrameLayout.LayoutParams(-1, -1)); deviceAdapter = new NativePages.DeviceAdapter(ui, device -> navigate("device", device.optString("id"))); list = NativePages.list(ui, deviceAdapter); attachList(column);
        LinearLayout action = ui.column(); action.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8)); action.addView(ui.button("添加电脑", false, () -> navigate("pairing", "")), wrap()); column.addView(action, wrap());
    }
    private void buildTask() {
        LinearLayout column = content(); JSONObject task = findTask(selectedId), device = task == null ? null : findDevice(task.optString("device_id"));
        if (task == null) { placeholder(column, snapshot == null ? "正在读取任务" : "未找到这条任务"); return; }
        LinearLayout heading = ui.row(); heading.setGravity(Gravity.TOP); heading.addView(ui.brand(task.optString("tool"), 34));
        LinearLayout words = ui.column(); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(ui.dp(14), 0, 0, 0); heading.addView(words, params);
        TextView name = ui.text(task.optString("title", "未命名任务"), 20, true); words.addView(name, wrap()); words.addView(ui.space(9)); TextView source = ui.text(device == null ? "未知电脑" : device.optString("name"), 14, false); source.setTextColor(ui.muted); words.addView(source, wrap()); column.addView(heading, wrap()); column.addView(ui.space(24));
        trackingButton = ui.button("开始跟踪", () -> { if (selectedId.equals(TrackingService.trackedId)) { TrackingService.requestStop(this, TrackingService.generation); updateTracking(); } else chooseTrackingDuration(selectedId, loadedToken, ++actionRevision); }); column.addView(trackingButton, wrap());
        trackingNote = ui.text("", 13, false); trackingNote.setTextColor(ui.muted); trackingNote.setPadding(0, ui.dp(10), 0, ui.dp(20)); column.addView(trackingNote, wrap());
        taskDetail = new NativePages.TaskDetail(ui); column.addView(taskDetail.view(), wrap()); column.addView(ui.space(20));
        taskUsage = new NativeUsagePage.TaskCard(ui, this::showHelp); taskUsage.update(task); column.addView(taskUsage.view, wrap()); column.addView(ui.space(20));
        final String resultTaskId = selectedId, resultToken = loadedToken; final long resultSession = sessionEpoch, resultPage = pageEpoch;
        taskResults = new NativeTaskResults(ui, resultTaskId, resultToken, new NativeTaskResults.Host() {
            public boolean connected() { return valid(resultToken, resultSession) && samePage(resultPage) && page.equals("task") && resultTaskId.equals(selectedId); }
            public boolean visible() { return MainActivity.this.visible; }
            public void failure(NativeApi.Failure failure) { if (failure.status == 401 || failure.status == 403) invalidate(resultToken); }
            public void notice(String message) { if (MainActivity.this.visible) MainActivity.this.notice(message); }
        }, taskResultsState); taskResultsState = null;
        column.addView(taskResults.view, wrap()); column.addView(ui.space(24));
        taskResults.update(task, syncOutput());
        if (hasPendingDocumentResult) { hasPendingDocumentResult = false; taskResults.documentResult(pendingDocumentCode, pendingDocumentResult); pendingDocumentResult = null; }
        archiveButton = ui.button("归档任务", false, () -> { JSONObject current = findTask(selectedId); if (current != null) archive(current); }); column.addView(archiveButton, wrap()); column.addView(ui.space(12));
        if (device != null) column.addView(ui.button("查看电脑", false, () -> navigate("device", task.optString("device_id"))), wrap());
    }
    private void buildDevice() {
        JSONObject computer = findDevice(selectedId); if (computer == null) { placeholder(content(), snapshot == null ? "正在读取电脑" : "未找到这台电脑"); return; }
        LinearLayout column = ui.column(); body.addView(column, new FrameLayout.LayoutParams(-1, -1)); LinearLayout heading = ui.column(); heading.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(12));
        heading.addView(ui.text(computer.optString("name", "电脑"), 21, true), wrap()); heading.addView(ui.space(8)); deviceStatus = ui.text("", 14, false); deviceStatus.setTextColor(ui.muted); heading.addView(deviceStatus, wrap()); column.addView(heading, wrap());
        taskAdapter = new NativePages.TaskAdapter(ui, taskCallbacks()); list = NativePages.list(ui, taskAdapter); attachList(column);
        if (!computer.optBoolean("local")) {
            LinearLayout action = ui.column(); action.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8));
            TextView remove = ui.button("移除电脑", false, () -> confirm("移除这台电脑？", "移除后，这台电脑需重新配对。电脑上的任务不受影响。", "移除", () -> mutate("remove:" + computer.optString("id"), "DELETE", "/api/native/computers/" + computer.optString("id"), null, (value, revision) -> { if (samePage(revision)) navigate("devices", ""); })));
            remove.setTextColor(ui.danger); action.addView(remove, wrap()); column.addView(action, wrap());
        }
    }
    private boolean stale() { return NativeScreenPolicy.stale(lastSuccess, SystemClock.elapsedRealtime(), failed); }
    private JSONArray tasks() { return snapshot == null ? new JSONArray() : array(snapshot, "tasks"); }
    private JSONArray devices() { return snapshot == null ? new JSONArray() : array(snapshot, "devices"); }
    private JSONObject findTask(String id) { return find(tasks(), id); }
    private JSONObject findDevice(String id) { return find(devices(), id); }
    private static JSONObject find(JSONArray values, String id) { for (int i = 0; i < values.length(); i++) { JSONObject item = values.optJSONObject(i); if (item != null && id.equals(item.optString("id"))) return item; } return null; }
    private static JSONArray array(JSONObject object, String key) { JSONArray result = object == null ? null : object.optJSONArray(key); return result == null ? new JSONArray() : result; }
    private boolean syncOutput() { JSONObject pref = snapshot == null ? null : snapshot.optJSONObject("preferences"); if (pref == null && account != null) pref = account.optJSONObject("preferences"); return pref != null && pref.optBoolean("sync_output"); }
    private void updateWorkspace() {
        if (!workspacePage() || loadedToken.isEmpty()) return;
        if (page.equals("task")) {
            JSONObject task = findTask(selectedId); if (taskDetail == null && task != null) { showPage(false); return; }
            if (taskDetail != null) { if (task == null) { showPage(false); return; } taskDetail.update(task, findDevice(task.optString("device_id")), stale(), syncOutput()); if (taskUsage != null) taskUsage.update(task); if (taskResults != null) taskResults.update(task, syncOutput()); archiveButton.setText(task.optBoolean("archived") ? "恢复任务" : "归档任务"); }
            updateTracking(); return;
        }
        if (page.equals("device")) { JSONObject device = findDevice(selectedId); if (deviceStatus == null && device != null) { showPage(false); return; } if (deviceStatus != null && device != null) deviceStatus.setText(device.optString("platform", "电脑") + " · " + (stale() ? "状态未知" : device.optBoolean("online") ? "在线" : "离线")); }
        int uncertainSelectedTasks = 0;
        if (taskAdapter != null) {
            JSONArray shown = new JSONArray(); int[] counts = {0, 0, 0}; JSONArray all = tasks();
            for (int i = 0; i < all.length(); i++) {
                JSONObject task = all.optJSONObject(i); if (task == null) continue; JSONObject device = findDevice(task.optString("device_id")); boolean unknown = stale() || device == null || !device.optBoolean("online");
                if (NativeWorkbenchPolicy.uncertainTask(tool, task.optString("tool"), task.optString("status"), task.optBoolean("archived"), unknown)) uncertainSelectedTasks++;
                if (page.equals("device")) { if (selectedId.equals(task.optString("device_id")) && !task.optBoolean("archived")) shown.put(task); }
                else {
                    boolean inScope = NativeScreenPolicy.includes("all", scope, task.optString("tool"), task.optString("status"), task.optBoolean("archived"), unknown);
                    if (inScope) { counts[0]++; if (task.optString("tool").equals("claude")) counts[1]++; if (task.optString("tool").equals("codex")) counts[2]++; }
                    if (NativeScreenPolicy.includes(tool, scope, task.optString("tool"), task.optString("status"), task.optBoolean("archived"), unknown)) shown.put(task);
                }
            }
            taskAdapter.update(shown, devices(), stale()); if (toolSegments != null) toolSegments.counts(counts);
        }
        if (deviceAdapter != null) deviceAdapter.update(devices(), stale());
        if (emptyLabel != null) {
            JSONArray computers = devices(); int online = 0;
            for (int i = 0; i < computers.length(); i++) { JSONObject computer = computers.optJSONObject(i); if (computer != null && computer.optBoolean("online")) online++; }
            emptyLabel.setText(snapshot == null ? failed ? "暂时无法读取，点此重试" : "正在读取" : page.equals("devices") ? "还没有连接电脑" : page.equals("tasks") && scope.equals("active") ? NativeWorkbenchPolicy.emptyActive(stale(), computers.length(), online, uncertainSelectedTasks) : page.equals("tasks") && scope.equals("archived") ? "暂无归档任务" : "暂无任务");
        }
    }
    private void updateConnection() {
        if (connection == null) return;
        boolean coolingDown = SystemClock.elapsedRealtime() < apiCooldownUntil;
        String message = NativeScreenPolicy.connectionMessage(stale(), failed, snapshotBusy, coolingDown);
        boolean show = !loadedToken.isEmpty() && workspacePage() && snapshot != null && !message.isEmpty();
        connection.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        boolean retry = show && !snapshotBusy && !coolingDown;
        titleHeading.setClickable(retry); titleHeading.setFocusable(retry);
        connection.setContentDescription(show ? message : null);
        titleHeading.setContentDescription(title.getText() + (show ? "，" + message : ""));
        if (show) {
            connection.setText(message);
            connection.setTextColor(coolingDown || failed && !snapshotBusy ? ui.warning : ui.muted);
        }
    }
    private void saveFilters() { getSharedPreferences("native_ui", MODE_PRIVATE).edit().putString("tool", tool).putString("scope", scope).apply(); }
    private void persistFilter() { filterDirty = true; filterRetryAt = SystemClock.elapsedRealtime() + 350; }
    private void flushFilter() {
        if (!visible || loadedToken.isEmpty() || filterBusy || !filterDirty || SystemClock.elapsedRealtime() < apiCooldownUntil) return;
        final String desired = tool; filterBusy = true; dataRevision.started(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        request("PATCH", "/api/native/preferences", json("tool_filter", desired), (result, revision) -> {
            filterBusy = false; dataRevision.finished(NativeWorkbenchPolicy.Change.TOOL_FILTER); filterDirty = result == null || !tool.equals(desired);
            filterRetryAt = result == null ? Math.max(apiCooldownUntil, SystemClock.elapsedRealtime() + 15000) : SystemClock.elapsedRealtime();
        });
    }
    private void archive(JSONObject task) {
        String id = task.optString("id"); boolean archived = !task.optBoolean("archived");
        mutate("archive:" + id, "PATCH", "/api/native/tasks/archive", json("task_id", id, "archived", archived), (value, revision) -> { JSONObject current = findTask(id); if (current != null) try { current.put("archived", archived); } catch (Exception ignored) { } if (archived && id.equals(TrackingService.trackedId)) TrackingService.requestStop(this, TrackingService.generation); if (samePage(revision)) updateWorkspace(); });
    }

    private JSONObject user() { JSONObject value = account == null ? null : account.optJSONObject("user"); return value == null ? new JSONObject() : value; }
    private void buildAccount() {
        LinearLayout column = content(); if (account == null) { placeholder(column, "正在读取账号"); column.addView(ui.space(16)); column.addView(menu("refresh", "应用更新", () -> startActivity(new Intent(this, NativeUpdateActivity.class))), wrap()); return; }
        LinearLayout profile = ui.card(); profile.setOrientation(LinearLayout.HORIZONTAL); profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.addView(avatar(user().optString("avatar"), 52)); TextView name = ui.text(user().optString("display_name", user().optString("username", "我的账号")), 20, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(ui.dp(16), 0, ui.dp(6), 0); profile.addView(name, params); profile.addView(ui.icon("chevron", 24));
        profile.setBackground(ui.ripple(ui.surface, 20)); profile.setOnClickListener(v -> navigate("profile", "")); profile.setContentDescription("个人资料，" + name.getText()); profile.setFocusable(true); column.addView(profile, wrap()); column.addView(ui.space(20));
        column.addView(menu("tasks", "用量", () -> navigate("usage", "")), wrap()); column.addView(ui.space(10));
        column.addView(menu("cloud", "个人同步", () -> navigate("sync", "")), wrap()); column.addView(ui.space(10));
        column.addView(menu("shield", "登录设备", () -> navigate("sessions", "")), wrap()); column.addView(ui.space(10));
        column.addView(menu("link", "添加电脑", () -> navigate("pairing", "")), wrap()); column.addView(ui.space(10));
        column.addView(menu("bell", "系统实况设置", () -> liveSettings.show()), wrap());
        column.addView(ui.space(10)); column.addView(menu("refresh", "应用更新", () -> startActivity(new Intent(this, NativeUpdateActivity.class))), wrap());
        if (!TrackingService.trackedId.isEmpty()) { column.addView(ui.space(10)); column.addView(menu("close", "停止跟踪", () -> { TrackingService.requestStop(this, TrackingService.generation); showPage(false); }), wrap()); }
        column.addView(ui.space(24)); column.addView(ui.button("退出登录", false, () -> confirm("退出 Monitor？", "这台手机会断开账号连接，并结束当前实况。电脑上的任务不受影响。", "退出", this::logout)), wrap());
    }
    private View menu(String icon, String label, Runnable action) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12)); row.setMinimumHeight(ui.dp(56)); row.setBackground(ui.ripple(ui.surface, 20)); row.addView(ui.icon(icon, 22));
        TextView text = ui.text(label, 15, false); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(ui.dp(14), 0, 0, 0); row.addView(text, params); row.addView(ui.icon("chevron", 22));
        row.setFocusable(true); row.setContentDescription(label); row.setAccessibilityDelegate(NativeUi.buttonAccessibility()); row.setOnClickListener(v -> action.run()); return row;
    }
    private void buildUsage() {
        LinearLayout column = content(); nativeUsage = new NativeUsagePage(ui, this::showHelp, () -> { nextUsage = 0; fetchUsage(); }, () -> UsageAlerts.showSettings(this));
        nativeUsage.update(usageSummary, usageBusy, usageFailed); column.addView(nativeUsage.view, wrap());
    }
    private ImageView avatar(String value, int size) {
        ImageView image = new ImageView(this); image.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(size), ui.dp(size))); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(ui.rounded(ui.soft, size / 2f)); image.setClipToOutline(true); image.setContentDescription("头像"); bindAvatar(image, value); return image;
    }
    private void bindAvatar(ImageView target, String value) {
        target.setTag(value); target.setImageResource(R.drawable.app_icon); final long epoch = sessionEpoch;
        if (value == null || value.isEmpty()) return;
        worker.execute(() -> { Bitmap decoded = NativeAvatar.decode(value); handler.post(() -> { if (!isDestroyed() && epoch == sessionEpoch && value.equals(target.getTag()) && decoded != null) target.setImageBitmap(decoded); else if (decoded != null) decoded.recycle(); }); });
    }
    private void buildProfile() {
        LinearLayout column = content(); if (account == null && !profileDraft) { placeholder(column, "正在读取资料"); return; }
        if (!profileDraft) { draftName = user().optString("display_name", user().optString("username")); draftAvatar = user().optString("avatar"); profileDraft = true; }
        LinearLayout picture = ui.column(); picture.setGravity(Gravity.CENTER_HORIZONTAL); avatarView = avatar(draftAvatar, 80); picture.addView(avatarView); picture.addView(ui.space(14));
        avatarPickButton = ui.button(avatarBusy ? "正在处理头像…" : "更换头像", false, this::pickAvatar); avatarPickButton.setEnabled(!avatarBusy); picture.addView(avatarPickButton, wrap()); picture.addView(ui.space(10));
        picture.addView(ui.button("移除头像", false, () -> { if (avatarBusy) { notice("头像正在处理，请稍候"); return; } draftAvatar = ""; bindAvatar(avatarView, ""); }), wrap()); column.addView(picture, wrap()); column.addView(ui.space(24));
        TextView label = ui.text("昵称", 15, true); column.addView(label, wrap()); column.addView(ui.space(10));
        nameInput = input("你的名字", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); nameInput.setText(draftName); nameInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)}); nameInput.setSingleLine(true); column.addView(nameInput, wrap()); column.addView(ui.space(20));
        profileSaveButton = ui.button("保存资料", this::saveProfile); profileSaveButton.setEnabled(!avatarBusy); profileSaveButton.setAlpha(avatarBusy ? .5f : 1f); column.addView(profileSaveButton, wrap()); column.addView(ui.space(24));
        if (account != null && account.optBoolean("google_login")) column.addView(menu("link", account.optBoolean("google_linked") ? "管理 Google 账号" : "连接 Google 账号", () -> external(NativeApi.ORIGIN + "/#/profile")), wrap());
    }
    private void captureDraft() { if (page.equals("profile") && nameInput != null) draftName = nameInput.getText().toString(); }
    private void saveProfile() {
        if (avatarBusy) { notice("头像正在处理，请稍候"); return; }
        captureDraft(); String name = draftName.trim(); if (name.isEmpty() || name.length() > 40) { notice("昵称需为 1–40 个字符"); return; }
        final String submittedAvatar = draftAvatar, submittedName = draftName;
        mutate("profile", "PATCH", "/api/native/profile", json("display_name", name, "avatar", submittedAvatar), (result, revision) -> {
            JSONObject updated = result.optJSONObject("user"); if (updated != null && account != null) try { account.put("user", updated); } catch (Exception ignored) { }
            // Edits made while saving remain a draft. A late save never redirects another page.
            captureDraft(); if (draftName.equals(submittedName) && draftAvatar.equals(submittedAvatar)) profileDraft = false;
            if (samePage(revision)) { notice("资料已保存"); if (!profileDraft) navigate("account", ""); }
        });
    }
    private void pickAvatar() {
        if (avatarBusy) return;
        pickerToken = loadedToken; pickerPage = pageKey(); pickerEpoch = pageEpoch;
        try { startActivityForResult(NativeAvatar.pickerIntent(), 22); } catch (Exception failure) { notice("暂时无法打开相册"); }
    }
    private void buildSync() {
        LinearLayout column = content(); if (account == null) { placeholder(column, "正在读取同步设置"); return; }
        LinearLayout card = ui.card(); outputSwitch = new Switch(this); outputSwitch.setText("同步任务输出"); outputSwitch.setTextColor(ui.text); outputSwitch.setTypeface(ui.regular); outputSwitch.setTextSize(15); outputSwitch.setMinHeight(ui.dp(48)); outputSwitch.setChecked(syncOutput());
        outputSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return; updatingSwitch = true; outputSwitch.setChecked(syncOutput()); updatingSwitch = false;
            Runnable save = () -> mutate("sync", "PATCH", "/api/native/preferences", json("sync_output", checked), (result, revision) -> {
                applyOutputPreference(checked); if (samePage(revision) && outputSwitch != null) { updatingSwitch = true; outputSwitch.setChecked(checked); updatingSwitch = false; }
            });
            if (checked) confirm("同步最终结果？", "开启后，可在已登录设备查看最终结果和下载配套文件。", "开启", save); else save.run();
        });
        card.addView(outputSwitch, wrap()); column.addView(card, wrap()); column.addView(ui.space(20));
        TextView state = ui.text("任务状态和已连接电脑会随账号同步。", 15, false); state.setTextColor(ui.muted); column.addView(state, wrap());
    }
    private void applyOutputPreference(boolean enabled) {
        try {
            if (account != null) { JSONObject pref = account.optJSONObject("preferences"); if (pref == null) { pref = new JSONObject(); account.put("preferences", pref); } pref.put("sync_output", enabled); }
            if (snapshot != null) { JSONObject pref = snapshot.optJSONObject("preferences"); if (pref == null) { pref = new JSONObject(); snapshot.put("preferences", pref); } pref.put("sync_output", enabled); }
            if (!enabled) { JSONArray all = tasks(); for (int i = 0; i < all.length(); i++) { JSONObject task = all.optJSONObject(i); if (task != null) { task.put("output", ""); task.put("preview", ""); } } }
        } catch (Exception ignored) { }
    }
    private void buildSessions() {
        LinearLayout column = content(); if (account == null) { placeholder(column, "正在读取登录设备"); return; }
        JSONArray readers = array(account, "native_devices"), browsers = array(account, "sessions");
        if (readers.length() > 0) { column.addView(ui.text("App", 17, true), wrap()); column.addView(ui.space(12)); }
        for (int i = 0; i < readers.length(); i++) { JSONObject device = readers.optJSONObject(i); if (device != null) sessionCard(column, device, true); }
        if (browsers.length() > 0) { column.addView(ui.space(12)); column.addView(ui.text("浏览器", 17, true), wrap()); column.addView(ui.space(12)); }
        for (int i = 0; i < browsers.length(); i++) { JSONObject device = browsers.optJSONObject(i); if (device != null) sessionCard(column, device, false); }
        column.addView(ui.button("刷新设备", false, this::fetchAccount), wrap());
    }
    private void sessionCard(LinearLayout column, JSONObject device, boolean nativeDevice) {
        String id = device.optString("id"), name = device.optString("name", device.optString("device_name", "未命名设备"));
        boolean current = device.optBoolean("current"), parent = !nativeDevice && device.optBoolean("authorizes_current");
        LinearLayout card = ui.card(); card.addView(ui.text(name, 16, true), wrap()); card.addView(ui.space(8));
        TextView detail = ui.text(current ? "当前设备" : parent ? "授权了这台手机" : NativePages.relative(device.optString("last_seen")), 13, false); detail.setTextColor(ui.muted); card.addView(detail, wrap()); card.addView(ui.space(16));
        card.addView(ui.button(current && nativeDevice ? "退出这台手机" : "退出此设备", false, () -> {
            String message = parent || current ? "这项操作也会让当前手机退出登录，并结束实况。" : "退出后，该设备需要重新登录。";
            confirm("退出「" + name + "」？", message, "退出", () -> {
                if (nativeDevice && current) { logout(); return; }
                final String token = loadedToken;
                mutate("session:" + id, "DELETE", "/api/native/" + (nativeDevice ? "connections/" : "sessions/") + id, null, (value, revision) -> {
                    if (parent || current) { disconnectLocally(token); return; }
                    if (samePage(revision)) fetchAccount();
                });
            });
        }), wrap()); column.addView(card, wrap()); column.addView(ui.space(12));
    }
    private void showAccountFailure() { body.removeAllViews(); LinearLayout column = content(); placeholder(column, "暂时无法读取账号"); column.addView(ui.button("重试", false, this::fetchAccount), wrap()); }

    private void buildPairing() {
        LinearLayout column = content(); column.addView(menu("scan", "扫码配对", () -> {
            pickerToken = loadedToken; pickerPage = pageKey(); pickerEpoch = pageEpoch;
            try { startActivityForResult(new Intent(this, NativeQrScanner.class), 23); } catch (Exception failure) { notice("暂时无法打开相机"); }
        }), wrap()); column.addView(ui.space(12));
        column.addView(menu("link", "输入配对链接", this::pastePairing), wrap()); column.addView(ui.space(28));
        column.addView(ui.text("使用配对码", 18, true), wrap()); column.addView(ui.space(12));
        if (!pairingCode.isEmpty() && NativeApi.time(pairingExpires) > System.currentTimeMillis()) {
            LinearLayout card = ui.card(); TextView code = ui.text(pairingCode, 24, true); code.setLetterSpacing(.09f); code.setGravity(Gravity.CENTER); code.setTextIsSelectable(true); card.addView(code, wrap()); card.addView(ui.space(16));
            card.addView(ui.button("复制配对码", false, () -> { ClipboardManager manager = getSystemService(ClipboardManager.class); if (manager != null) { manager.setPrimaryClip(ClipData.newPlainText("Monitor 配对码", pairingCode)); notice("配对码已复制"); } }), wrap()); column.addView(card, wrap()); column.addView(ui.space(14));
        }
        column.addView(ui.button(pairingCode.isEmpty() ? "生成配对码" : "重新生成", false, () -> mutate("pairing-code", "POST", "/api/native/computers/pairing", null, (value, revision) -> {
            String code = value.optString("code"), expires = value.optString("expires_at");
            if (!code.matches("[A-F0-9]{12}") || NativeApi.time(expires) <= System.currentTimeMillis()) { if (samePage(revision)) notice("返回的配对码无效，请重试"); return; }
            pairingCode = code; pairingExpires = expires; if (samePage(revision)) { savePosition(); showPage(false); }
        })), wrap());
    }
    private void pastePairing() {
        EditText field = input("粘贴电脑上的配对链接", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); field.setMaxLines(4);
        LinearLayout area = ui.column(); area.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); area.addView(field, wrap());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("输入配对链接").setView(area).setNegativeButton("取消", null).setPositiveButton("继续", null).create();
        dialog.setOnShowListener(target -> { ui.applyFont(dialog.getWindow().getDecorView()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String id = NativeScreenPolicy.pairingId(field.getText().toString()); if (id == null) { field.setError("请使用这台个人服务生成的配对链接"); return; } dialog.dismiss(); navigate("pairing-confirm", id);
        }); }); dialog.show();
    }
    private void buildPairingConfirm() {
        LinearLayout column = content(); if (!NativeApi.validId(selectedId)) { placeholder(column, "配对链接无效"); return; }
        placeholder(column, "正在读取电脑信息"); String requestId = selectedId;
        request("GET", "/api/native/computers/pairing/" + requestId, null, (result, revision) -> {
            if (!samePage(revision)) return; column.removeAllViews();
            if (result == null) { placeholder(column, "暂时无法读取配对请求"); column.addView(ui.button("重试", false, () -> showPage(false)), wrap()); return; }
            String status = result.optString("status"), name = result.optString("name", "新电脑"), platform = result.optString("platform", "电脑"), expires = result.optString("expires_at");
            LinearLayout card = ui.card(); card.addView(ui.icon("monitor", 34)); card.addView(ui.space(20)); card.addView(ui.text(name, 20, true), wrap()); card.addView(ui.space(10)); TextView label = ui.text(platform, 15, false); label.setTextColor(ui.muted); card.addView(label, wrap()); column.addView(card, wrap()); column.addView(ui.space(24));
            if (!status.equals("pending") || NativeApi.time(expires) <= System.currentTimeMillis()) { placeholder(column, status.equals("approved") ? "这台电脑已配对" : "配对请求已失效"); return; }
            column.addView(ui.button("连接这台电脑", () -> {
                if (NativeApi.time(expires) <= System.currentTimeMillis()) { notice("配对请求已过期，请在电脑重新发起"); return; }
                mutate("approve:" + requestId, "POST", "/api/native/computers/pairing/" + requestId + "/approve", null, (value, actionPage) -> { if (samePage(actionPage)) { notice("电脑已连接"); navigate("devices", ""); } });
            }), wrap()); column.addView(ui.space(12));
            column.addView(ui.button("拒绝", false, () -> mutate("reject:" + requestId, "POST", "/api/native/computers/pairing/" + requestId + "/reject", null, (value, actionPage) -> { if (samePage(actionPage)) navigate("pairing", ""); })), wrap());
        });
    }
    private void external(String url) {
        if (!(url.equals(NativeApi.ORIGIN + "/#/profile") || url.matches(java.util.regex.Pattern.quote(NativeApi.ORIGIN) + "/#/native-connect/[A-Za-z0-9_-]{32}"))) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(new Intent(intent).setPackage("com.android.chrome")); }
        catch (Exception unavailable) { try { startActivity(intent); } catch (Exception failure) { notice("请先安装或启用浏览器"); } }
    }
    private EditText input(String hint, int type) {
        EditText result = new EditText(this); result.setHint(hint); result.setInputType(type); result.setTextColor(ui.text); result.setHintTextColor(ui.muted); result.setTextSize(15); result.setTypeface(ui.regular);
        result.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); result.setMinHeight(ui.dp(48)); result.setBackground(ui.bordered(ui.surface, 18)); return result;
    }
    private void placeholder(LinearLayout column, String text) { LinearLayout placeholder = NativePages.empty(ui, text, "tasks"); column.addView(placeholder, wrap()); }
    private void notice(String message) { if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void confirm(String title, String message, String positive, Runnable action) {
        final long revision = pageEpoch, epoch = sessionEpoch; final String token = loadedToken;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton(positive, (target, which) -> { if (samePage(revision) && valid(token, epoch)) action.run(); }).create(); dialog.show(); ui.applyFont(dialog.getWindow().getDecorView());
    }
    private void help() {
        if (!visible || isFinishing() || isDestroyed() || helpDialog != null) return;
        String message;
        switch (page) {
            case "tasks": message = "按工具和状态筛选任务。\n\n“进行中”包括执行、等待批准和出错的任务，离线记录不会显示为正在执行。"; break;
            case "task": message = "查看任务状态和最终结果，也可下载文件或继续回复。\n\n开始跟踪后，进度会显示在系统实况中。展示方式由系统通知设置决定。"; break;
            case "devices": case "device": message = "已连接的电脑随账号同步。\n\n电脑保持开机、采集端运行时，任务状态会自动更新。"; break;
            case "pairing": message = "扫描电脑采集端的二维码，或生成配对码后在电脑端输入。\n\n配对码 10 分钟内有效，只能使用一次。"; break;
            case "pairing-confirm": message = "确认电脑名称和平台后连接。\n\n这台电脑随后可向你的个人服务同步任务。"; break;
            case "profile": message = "头像和昵称随账号同步。\n\n连接 Google 账号时，会打开浏览器完成验证。"; break;
            case "sessions": message = "退出设备会撤销它的登录权限。\n\n退出曾授权当前手机的浏览器，也会让这台手机退出。"; break;
            case "sync": message = "开启后，同步最终结果与配套文件。\n\n关闭后，清除服务中的结果副本，仅保留任务状态。"; break;
            case "usage": message = NativeUsagePage.HELP; break;
            default: message = "管理个人账号、登录设备和系统实况。\n\nCodex 与 Claude Code 的账号由各台电脑管理。";
        }
        showHelp(pageTitle(), message);
    }
    private void showHelp(String heading, String message) {
        if (!visible || isFinishing() || isDestroyed() || helpDialog != null || loadedToken.isEmpty()) return;
        NativeHelpDialog dialog = new NativeHelpDialog(this, ui, heading, message);
        helpDialog = dialog; helpPage = pageKey();
        dialog.setOnDismissListener(ignored -> { if (helpDialog == dialog) { helpDialog = null; helpPage = ""; } });
        try { dialog.show(); }
        catch (RuntimeException failure) { dismissHelp(); notice("暂时无法打开说明，请重试"); }
    }
    private void dismissHelp() { if (helpDialog != null) { NativeHelpDialog dialog = helpDialog; helpDialog = null; helpPage = ""; dialog.dismiss(); } }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private static JSONObject json(Object... values) { JSONObject object = new JSONObject(); try { for (int i = 0; i + 1 < values.length; i += 2) object.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) { } return object; }

    private void updateTracking() {
        if (trackingButton == null || !page.equals("task")) return;
        boolean active = selectedId.equals(TrackingService.trackedId); JSONObject task = findTask(selectedId), device = task == null ? null : findDevice(task.optString("device_id"));
        boolean runnable = task != null && !stale() && device != null && TrackingPolicy.confirmedRunning(true, task.optBoolean("archived"), device.optBoolean("online"), task.optString("status"));
        trackingButton.setText(active ? "停止跟踪" : "开始跟踪"); trackingButton.setEnabled(active || runnable); trackingButton.setAlpha(active || runnable ? 1f : .5f);
        String detail = active ? TrackingService.trackingMessage : runnable ? "" : "任务确认执行中后可开启实况";
        if (active && TrackingService.untilTaskEnd) detail = "直至任务结束";
        else if (active && TrackingService.endsAt > 0) {
            long minutes = Math.max(1, (TrackingService.endsAt - SystemClock.elapsedRealtime() + 59999) / 60000);
            detail = "剩余 " + (minutes >= 60 ? (minutes / 60) + " 小时" + (minutes % 60 > 0 ? " " + (minutes % 60) + " 分钟" : "") : minutes + " 分钟");
        }
        if (trackingNote != null && !android.text.TextUtils.equals(trackingNote.getText(), detail)) trackingNote.setText(detail);
    }
    private boolean canTrack(String token, long revision, String expectedPage) { return visible && revision == actionRevision && pageKey().equals(expectedPage) && page.equals("task") && valid(token, sessionEpoch); }
    private void chooseTrackingDuration(String id, String token, long revision) {
        final String expectedPage = pageKey(); if (!canTrack(token, revision, expectedPage) || stale()) return;
        int preferred = TrackingPolicy.DEFAULT_DURATION_MINUTES, preferredTimed;
        try { preferred = getSharedPreferences("tracking_preferences", MODE_PRIVATE).getInt("duration_minutes", preferred); } catch (ClassCastException ignored) { }
        preferred = TrackingPolicy.sanitizeDurationMinutes(preferred);
        preferredTimed = preferred > 0 ? preferred : TrackingPolicy.DEFAULT_DURATION_MINUTES;
        try { preferredTimed = getSharedPreferences("tracking_preferences", MODE_PRIVATE).getInt("timer_minutes", preferredTimed); } catch (ClassCastException ignored) { }
        if (preferredTimed < 1 || preferredTimed > TrackingPolicy.MAX_DURATION_MINUTES) preferredTimed = TrackingPolicy.DEFAULT_DURATION_MINUTES;
        final TrackingPolicy.DurationChoice choice = new TrackingPolicy.DurationChoice(preferred);
        final Dialog[] shown = new Dialog[1];
        try {
            shown[0] = TrackingDurationDialog.show(this, ui, preferred, preferredTimed, (selection, timedMinutes) -> {
                choice.select(selection); int duration = choice.confirm();
                if (duration == 0 || !canTrack(token, revision, expectedPage)) return;
                getSharedPreferences("tracking_preferences", MODE_PRIVATE).edit().putInt("duration_minutes", duration).putInt("timer_minutes", timedMinutes).apply();
                verifyTrack(id, token, revision, expectedPage, duration);
            }, () -> { choice.cancel(); if (durationDialog == shown[0]) durationDialog = null; });
            durationDialog = shown[0];
        } catch (RuntimeException failure) { choice.cancel(); notice("暂时无法打开时长选择，请重试"); }
    }
    private void verifyTrack(String id, String token, long revision, String expectedPage, int requestedDuration) {
        if (!canTrack(token, revision, expectedPage)) return; final int duration = TrackingPolicy.sanitizeDurationMinutes(requestedDuration);
        request("GET", "/api/native/snapshot", null, (result, requestedPage) -> {
            if (!canTrack(token, revision, expectedPage)) return;
            if (result == null) { notice("无法确认任务状态，请稍后重试"); return; }
            JSONObject task = NativeApi.task(result, id);
            if (task == null || !TrackingPolicy.confirmedRunning(true, task.optBoolean("archived"), NativeApi.online(result, task), task.optString("status"))) { notice("任务当前未确认在执行，请更新后重试"); return; }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationTask = id; notificationToken = token; notificationRevision = revision; notificationPage = expectedPage; notificationDuration = duration; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 24); return;
            }
            NotificationManager manager = getSystemService(NotificationManager.class); if (manager == null || !manager.areNotificationsEnabled()) { notice("请先在系统设置中允许通知"); return; }
            try {
                NotificationChannel channel = manager.getNotificationChannel(TrackingService.CHANNEL);
                if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    notice("任务跟踪通知已关闭，请开启后重新开始跟踪");
                    liveSettings.openTrackingChannelSettings(); return;
                }
                startForegroundService(new Intent(this, TrackingService.class).setAction("track").putExtra("task_id", id).putExtra("title", task.optString("title")).putExtra("tool", task.optString("tool")).putExtra("duration_minutes", duration));
            }
            catch (Exception failure) { notice("暂时无法开始跟踪，请稍后重试"); }
        });
    }
    private void cancelDuration() { if (durationDialog != null) durationDialog.dismiss(); }
    private void clearNotificationRequest() { notificationTask = null; notificationToken = null; notificationPage = null; }
    private void resumeNotificationRequest() {
        if (!visible || notificationTask == null || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return;
        String id = notificationTask, token = notificationToken, expectedPage = notificationPage; long revision = notificationRevision; int duration = notificationDuration;
        clearNotificationRequest(); verifyTrack(id, token, revision, expectedPage, duration);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 24) { if (results.length != 1 || results[0] != PackageManager.PERMISSION_GRANTED) { clearNotificationRequest(); notice("未开启通知，此次没有开始跟踪"); } else resumeNotificationRequest(); }
        if (code == 25) UsageAlerts.sync(this);
    }
    private void dismissServerSettings() { if (serverSettingsDialog != null) { serverSettingsDialog.dismiss(); serverSettingsDialog = null; } }
    private void showServerSettings() {
        dismissServerSettings();
        serverSettingsDialog = ServerSettings.show(this, this::confirmLegacyDisconnect);
    }
    private void confirmLegacyDisconnect() {
        dismissServerSettings();
        if (!ServerSettings.legacyConnection(this)) { updateLogin(); return; }
        LivePresentationSheet confirmation = new LivePresentationSheet(this, "清除本机旧连接？");
        serverSettingsDialog = confirmation;
        confirmation.note("这会退出本机旧登录、停止跟踪和用量提醒，并关闭应用。重新打开后可设置服务器地址。\n\n原服务器上的连接不会被远程撤销，请到原服务的设备管理中移除旧设备。");
        confirmation.action("清除并关闭应用", this::clearLegacyConnection);
        confirmation.action("取消", () -> { });
        confirmation.show();
    }
    private void clearLegacyConnection() {
        // This path is exclusively for credentials whose original server is unknown.
        // Never enqueue revocation or associate those credentials with the next server.
        if (!ServerSettings.legacyConnection(this)) { updateLogin(); return; }
        try { SessionStore.write(this, new JSONObject()); }
        catch (Exception failure) { notice("未能清除旧连接，请重试。"); return; }
        visible = false; handler.removeCallbacks(heartbeat);
        TrackingService.requestStop(this, TrackingService.generation); UsageAlerts.sync(this);
        sessionEpoch++; pageEpoch++; actionRevision++; loadedToken = ""; invalidToken = "";
        snapshot = null; account = null; usageSummary = null; profileDraft = false;
        draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = "";
        mutations.clear(); listPositions.clear(); scrollPositions.clear(); clearNotificationRequest();
        if (taskResults != null) { taskResults.dispose(); taskResults = null; }
        // Wait for the cookie store's durable clear before ending this process and its old workers.
        WebSessionCookies.clear(() -> ServerSettings.closeForRestart(this));
    }
    private void disconnectLocally(String token) { invalidToken = token; try { NativeConnection.invalidate(this, token); } catch (Exception failure) { notice("连接已失效，暂时无法清理本机登录信息"); } TrackingService.requestStop(this, TrackingService.generation); reconcileConnection(); }
    private void logout() {
        if (logoutBusy) return; final String token;
        synchronized (SessionStore.class) {
            JSONObject store = SessionStore.read(this); token = store.optString("reader_token"); if (token.isEmpty()) { reconcileConnection(); return; }
            try { store.put("logout_pending", true).put("native_ready", false).put("cookie_ready", false); SessionStore.write(this, store); }
            catch (Exception failure) { notice("无法安全保存退出状态，请稍后重试"); return; }
        }
        NativeConnection.cancel(this); logoutBusy = true; TrackingService.requestStop(this, TrackingService.generation); reconcileConnection();
        worker.execute(() -> {
            NativeApi.Failure failure = null;
            try { NativeApi.call("DELETE", "/api/native/session", null, token); } catch (NativeApi.Failure error) { if (error.status != 401) failure = error; }
            final NativeApi.Failure error = failure;
            handler.post(() -> {
                logoutBusy = false; if (isDestroyed()) return; JSONObject current = SessionStore.read(this); if (!token.equals(current.optString("reader_token")) || !current.optBoolean("logout_pending")) return;
                if (error == null) { try { NativeConnection.finishLogout(this, token); showPage(false); } catch (Exception failureSaving) { notice("已撤销连接，请再点一次完成退出"); } }
                else if (visible) notice("本机已退出，连接尚未在服务端撤销。联网后请点「完成退出」。");
            });
        });
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NativeTaskResults.SAVE_DOCUMENT) {
            if (taskResults != null) taskResults.documentResult(resultCode, data);
            else if (taskResultsState != null && page.equals("task")) { pendingDocumentResult = data; pendingDocumentCode = resultCode; hasPendingDocumentResult = true; }
            return;
        }
        if (resultCode != RESULT_OK || data == null || !pickerToken.equals(loadedToken) || !pageKey().equals(pickerPage) || pageEpoch != pickerEpoch) return;
        if (requestCode == 23) { String id = NativeScreenPolicy.pairingId(data.getStringExtra(NativeQrScanner.EXTRA_TEXT)); if (id == null) notice("这个二维码不是当前服务的配对链接"); else navigate("pairing-confirm", id); return; }
        if (requestCode != 22 || !page.equals("profile")) return;
        Uri uri = data.getData(); if (uri == null || !"content".equals(uri.getScheme()) || (data.getClipData() != null && data.getClipData().getItemCount() != 1)) { notice("请选择一张图片"); return; }
        readAvatar(uri);
    }
    private void readAvatar(Uri uri) {
        if (avatarBusy || !page.equals("profile")) return;
        avatarBusy = true; pendingAvatarUri = uri.toString(); final long selectedGeneration = ++avatarGeneration;
        updateAvatarBusy();
        final String token = loadedToken; final long epoch = sessionEpoch, revision = pageEpoch;
        worker.execute(() -> {
            NativeAvatar.Result avatar = null; String failure = "";
            try { avatar = NativeAvatar.fromUri(getApplicationContext(), uri); } catch (Exception error) { failure = error.getMessage() == null ? "无法读取这张图片" : error.getMessage(); }
            final NativeAvatar.Result selected = avatar; final String error = failure;
            handler.post(() -> {
                if (!valid(token, epoch) || selectedGeneration != avatarGeneration) { if (selected != null) selected.bitmap.recycle(); return; }
                avatarBusy = false; pendingAvatarUri = ""; updateAvatarBusy();
                if (selected == null) { notice(error); return; }
                draftAvatar = selected.dataUrl; profileDraft = true; if (samePage(revision) && page.equals("profile") && avatarView != null) { avatarView.setTag(draftAvatar); avatarView.setImageBitmap(selected.bitmap); } else selected.bitmap.recycle();
            });
        });
    }
    private void updateAvatarBusy() {
        if (profileSaveButton != null) { profileSaveButton.setEnabled(!avatarBusy); profileSaveButton.setAlpha(avatarBusy ? .5f : 1f); }
        if (avatarPickButton != null) { avatarPickButton.setEnabled(!avatarBusy); avatarPickButton.setText(avatarBusy ? "正在处理头像…" : "更换头像"); }
    }
    private boolean openNotification(Intent intent) {
        if (intent != null && "com.agentmonitor.live.OPEN_USAGE".equals(intent.getAction())) {
            page = "usage"; selectedId = ""; pageEpoch++; nextUsage = 0; return true;
        }
        if (intent == null || !NativeNavigationPolicy.NOTIFICATION_ACTION.equals(intent.getAction())) return false;
        String target = NativeNavigationPolicy.notificationUrl(intent.getAction(), intent.getDataString()); setIntent(new Intent(this, MainActivity.class));
        if (target == null || SessionStore.read(this).optBoolean("logout_pending")) return false;
        try { String encoded = target.substring((NativeApi.ORIGIN + "/#/task/").length()); page = "task"; taskReturnDevice = ""; selectedId = URLDecoder.decode(encoded, "UTF-8"); pageEpoch++; actionRevision++; return true; } catch (Exception failure) { return false; }
    }
    private void handleReturn(Intent intent) {
        Uri uri = intent == null ? null : intent.getData(); if (uri == null || !"agentmonitor".equals(uri.getScheme()) || !"paired".equals(uri.getHost())) return;
        JSONObject pending = SessionStore.read(this).optJSONObject("pairing");
        try {
            boolean valid = uri.getUserInfo() == null && uri.getPort() == -1 && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getFragment() == null && uri.getQueryParameterNames().size() == 1 && uri.getQueryParameters("request_id").size() == 1 && pending != null && NativeApi.validId(pending.optString("request_id")) && pending.optString("request_id").equals(uri.getQueryParameter("request_id"));
            setIntent(new Intent(this, MainActivity.class)); if (valid) NativeConnection.tick(this); else notice("这个返回链接不属于当前连接");
        } catch (Exception failure) { notice("返回链接无效"); }
    }
    private void back() {
        if (helpDialog != null && helpDialog.isShowing()) { helpDialog.cancel(); return; }
        if (durationDialog != null && durationDialog.isShowing()) { cancelDuration(); return; }
        if (page.equals("task")) { if (taskReturnDevice.isEmpty()) navigate("tasks", ""); else navigate("device", taskReturnDevice); }
        else if (page.equals("device")) navigate("devices", ""); else if (page.equals("pairing-confirm")) navigate("pairing", ""); else if (!rootPage()) navigate("account", ""); else finish();
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); savePosition(); captureDraft(); dismissHelp(); setIntent(intent); if (openNotification(intent)) { cancelDuration(); clearNotificationRequest(); showPage(false); loadCurrentPage(); } else handleReturn(intent); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        if (taskResults != null) saved.putBundle("task_results", taskResults.saveState());
        captureDraft(); saved.putString("page", page); saved.putString("id", selectedId); saved.putString("tool", tool); saved.putString("scope", scope);
        if (profileDraft) { saved.putBoolean("profile_draft", true); saved.putString("draft_name", draftName); saved.putString("draft_avatar", draftAvatar); }
        if (list != null) saved.putParcelable("list_position", list.onSaveInstanceState()); if (scroll != null) saved.putInt("scroll_y", scroll.getScrollY());
        saved.putString("pairing_code", pairingCode); saved.putString("pairing_expires", pairingExpires);
        saved.putLong("page_epoch", pageEpoch); saved.putString("picker_fingerprint", tokenFingerprint(pickerToken)); saved.putString("picker_page", pickerPage); saved.putLong("picker_epoch", pickerEpoch);
        saved.putString("task_return_device", taskReturnDevice); saved.putString("avatar_uri", pendingAvatarUri); saved.putString("state_fingerprint", tokenFingerprint(loadedToken)); super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; reconcileConnection(); updateLogin(); resumeNotificationRequest(); handler.removeCallbacks(heartbeat); loadCurrentPage(); handler.post(heartbeat);
        UsageAlerts.sync(this);
        if (!pendingAvatarUri.isEmpty() && !avatarBusy && !loadedToken.isEmpty() && page.equals("profile")) readAvatar(Uri.parse(pendingAvatarUri));
    }
    @Override protected void onPause() { visible = false; if (taskResults != null) taskResults.pause(); captureDraft(); savePosition(); dismissHelp(); dismissServerSettings(); UsageAlerts.dismissSettings(this); cancelDuration(); liveSettings.dismiss(); handler.removeCallbacks(heartbeat); super.onPause(); }
    @Override protected void onDestroy() {
        visible = false; if (taskResults != null) { taskResults.dispose(); taskResults = null; } sessionEpoch++; pageEpoch++; actionRevision++; dismissHelp(); dismissServerSettings(); UsageAlerts.dismissSettings(this); cancelDuration(); liveSettings.dismiss(); clearNotificationRequest(); handler.removeCallbacksAndMessages(null); worker.shutdown();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback); super.onDestroy();
    }
    private static String tokenFingerprint(String token) {
        if (token == null || token.isEmpty()) return "";
        try { byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP); } catch (Exception failure) { return ""; }
    }
}
