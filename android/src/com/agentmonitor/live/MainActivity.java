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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
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
    // Visible task reads do not queue behind account requests or local image work.
    private final ExecutorService workbenchReader = Executors.newSingleThreadExecutor();
    private final Set<String> mutations = new HashSet<>();
    private final NativeWorkbenchPolicy.RevisionGate dataRevision = new NativeWorkbenchPolicy.RevisionGate();
    private final Map<String, Parcelable> listPositions = new HashMap<>();
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private int workbenchHeaderScroll;
    private NativeUi ui;
    private NativeSystemBars systemBars;
    private NativeBingWallpaper bingWallpaper;
    private NativeBingWallpaper.Result wallpaperResult;
    private NativeAppearancePage appearancePage;
    private final LiveActivitySettings liveSettings = new LiveActivitySettings(this, view -> { if (ui != null) ui.applyFont(view); });
    private LinearLayout root, pageHeader, titleBar, titleHeading, titleActions, headerMeta, pageColumn;
    private FrameLayout stage, body;
    private NativeGlassNavigation bottomBar;
    private View fixedBottomAction;
    private int listBaseBottomPadding;
    private boolean imeVisible;
    private boolean overlayHeader;
    private boolean integratedHeader;
    private Drawable compactHeaderSurface;
    private boolean opaqueCompactHeader;
    private float compactTitleAlpha;
    private float expandedTitleAlpha;
    private int expandedRootOpening;
    private String renderedPage = "", currentSettingsSheet = "", pendingSettingsSheet = "";
    private Dialog settingsSheetDialog;
    private View pageRule;
    private TextView title, connection, trackingButton, trackingNote, archiveButton, emptyLabel, deviceStatus, profileSaveButton, avatarPickButton;
    private TextView loginStatus, loginNote, loginButton, loginCheckButton;
    private LinearLayout emptyStateContainer, syncDiagnosticsBody;
    private TextView emptyShowAllButton, taskPendingEntry;
    private LivePresentationSheet taskPendingSheet;
    private boolean focusPendingOnOpen;
    private NativeSyncDiagnostics.Sample syncDiagnosticsSample = NativeSyncDiagnostics.empty();
    private long firstWorkbenchStartedElapsed;
    private TextView taskSearchSummary;
    private TextView expandedRootTitle, taskSearchAffordanceLabel;
    private View taskSearchAffordance;
    private TextView taskHeadingTitle;
    private LinearLayout taskHeadingTool;
    private EditText taskSearchInput;
    private View taskSearchClear, taskSearchToggle;
    private LinearLayout taskSearchPanel;
    private android.animation.ValueAnimator taskSearchAnimation;
    private boolean taskSearchExpanded;
    private NativeSearchPresentationPolicy.FocusRestore pendingTaskSearchFocus;
    private LinearLayout accountCard;
    private TextView accountName, accountRetry;
    private ImageView accountAvatar;
    private View accountChevron;
    private boolean accountFailed;
    private ListView list;
    private ScrollView scroll;
    private NativePages.TaskAdapter taskAdapter;
    private NativePages.DeviceAdapter deviceAdapter;
    private static final String[] TASK_SOURCES = NativeScreenPolicy.toolFilters();
    private static final String[] TASK_SCOPES = {"active", "all", "archived"};
    private static final String[] TASK_SCOPE_LABELS = {"进行中", "全部任务", "已归档"};
    private TextView taskScopeFilter, taskSourceFilter;
    private int[] taskSourceCounts;
    private LivePresentationSheet taskFilterSheet;
    private NativePages.TaskDetail taskDetail;
    private NativeTaskResults taskResults;
    private NativeUsagePage nativeUsage;
    private Bundle usagePageState;
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
    private String taskQuery = "";
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

    private final Runnable refreshWorkspace = this::fetchSnapshot;
    private LinearLayout pairingCodeArea;

    private final Runnable heartbeat = new Runnable() { @Override public void run() {
        if (!visible || isDestroyed()) return;
        NativeConnection.tick(MainActivity.this);
        String error = NativeConnection.consumeError(); if (!error.isEmpty()) notice(error);
        reconcileConnection(); updateLogin();
        if (!loadedToken.isEmpty() && page.equals("usage") && SystemClock.elapsedRealtime() >= nextUsage) fetchUsage();
        if (nativeUsage != null) nativeUsage.tick();
        updatePairingCodeExpiry();
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
            pageEpoch = saved.getLong("page_epoch", 0); taskReturnDevice = saved.getString("task_return_device", ""); pendingAvatarUri = saved.getString("avatar_uri", "");
            Parcelable position = saved.getParcelable("list_position"); if (position != null) listPositions.put(pageKey(), position);
            scrollPositions.put(pageKey(), saved.getInt("scroll_y", 0));
            workbenchHeaderScroll = saved.getInt("workbench_header_scroll", 0);
        }
        root = ui.column(); root.setBackground(ui.background());
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.bg));
        systemBars = NativeSystemBars.attach(getWindow(), root, ui.headerTopColor(), ui.bg);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            systemBars.onInsets(insets);
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                imeVisible = insets.isVisible(WindowInsets.Type.ime());
                int safeBottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom);
                view.setPadding(bars.left, bars.top, bars.right, Math.max(safeBottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            } else {
                imeVisible = insets.getSystemWindowInsetBottom() > ui.dp(160);
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            updateNavigationInsets();
            return insets;
        });
        pageHeader = ui.column();
        pageHeader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleBar = ui.row(); pageHeader.addView(titleBar, wrap());
        headerMeta = ui.column(); pageHeader.addView(headerMeta, wrap());
        pageRule = new View(this);
        pageRule.setBackgroundColor(ui.line);
        pageRule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
        ruleParams.setMarginStart(ui.dp(20)); ruleParams.setMarginEnd(ui.dp(20));
        pageHeader.addView(pageRule, ruleParams);
        stage = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Give the scroll viewport its starting clearance in the same measure pass.
                measureChildWithMargins(pageHeader, widthSpec, 0, heightSpec, 0);
                int height = pageHeader.getMeasuredHeight();
                // Every scrolling page starts below its measured header and can move behind its tint.
                // A collapsing root owns its opening in the content and only reserves a pinned panel.
                boolean scrollsBehindHeader = scroll != null || list != null;
                ((FrameLayout.LayoutParams) body.getLayoutParams()).topMargin = scrollsBehindHeader ? 0 : height;
                boolean pinned = integratedHeader && headerNeedsClearance();
                updateContentPadding(scrollsBehindHeader && (!integratedHeader || pinned) ? height : 0);
                updateExpandedRootSpacing(pinned);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        body = new FrameLayout(this); stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(pageHeader, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        bottomBar = new NativeGlassNavigation(ui, body, root, NativeNavigationPolicy.tabForPage(page, taskReturnDevice), key -> { if (!page.equals(key)) navigate(key, ""); });
        FrameLayout.LayoutParams navigation = new FrameLayout.LayoutParams(ui.dp(320), ui.dp(72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navigation.bottomMargin = ui.dp(10); stage.addView(bottomBar, navigation);
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> { if (right - left != oldRight - oldLeft) updateNavigationInsets(); });
        setContentView(root); root.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) { backCallback = this::back; getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback); }
        JSONObject store = SessionStore.read(this); if (fullConnection(store)) loadedToken = store.optString("reader_token");
        loadSavedSyncDiagnostics();
        if (saved != null) {
            taskQuery = NativeTaskSearchPolicy.restoreQuery(saved.getString("task_query", ""),
                    saved.getString("state_fingerprint", ""), tokenFingerprint(loadedToken));
            taskSearchExpanded = NativeSearchPresentationPolicy.restoreExpanded(saved.getBoolean("task_search_expanded"),
                    taskQuery, saved.getString("state_fingerprint", ""), tokenFingerprint(loadedToken));
            pendingTaskSearchFocus = NativeSearchPresentationPolicy.restoreFocus(saved.getString("page", "tasks"),
                    saved.getBoolean("task_search_expanded"), saved.getBoolean("task_search_focused"),
                    saved.getString("state_fingerprint", ""), tokenFingerprint(loadedToken), pageEpoch,
                    saved.getInt("task_search_selection_start", -1), saved.getInt("task_search_selection_end", -1));
            if (!loadedToken.isEmpty() && tokenFingerprint(loadedToken).equals(saved.getString("state_fingerprint", ""))) usagePageState = saved.getBundle("usage_page_state");
            pendingSettingsSheet = NativeNavigationPolicy.restorableSettingsSheet(page, saved.getString("settings_sheet", ""),
                    saved.getString("state_fingerprint", ""), tokenFingerprint(loadedToken));
        }
        UsageAlerts.sync(this);
        if (saved != null && !tokenFingerprint(loadedToken).equals(saved.getString("state_fingerprint", ""))) { profileDraft = false; draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = ""; taskReturnDevice = ""; selectedId = ""; page = "tasks"; listPositions.clear(); scrollPositions.clear(); workbenchHeaderScroll = 0; }
        if (saved != null && !loadedToken.isEmpty() && tokenFingerprint(loadedToken).equals(saved.getString("picker_fingerprint", ""))) { pickerToken = loadedToken; pickerPage = saved.getString("picker_page", ""); pickerEpoch = saved.getLong("picker_epoch", -1); }
        openNotification(getIntent()); handleReturn(getIntent()); showPage(false);
    }
    private static String safePage(String value) { return NativeNavigationPolicy.safePage(value); }
    private String pageKey() { return page + ":" + selectedId; }
    private boolean workspacePage() { return NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.WORKBENCH; }
    private boolean accountPage() { return NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.ACCOUNT; }
    private void loadCurrentPage() {
        if (!workspacePage()) handler.removeCallbacks(refreshWorkspace);
        NativeWorkbenchPolicy.loadPage(page, visible, !loadedToken.isEmpty(), this::scheduleSnapshot, () -> {
            if (!usageBusy) { nextUsage = 0; fetchUsage(); }
        }, this::fetchAccount);
    }
    private boolean rootPage() { return NativeNavigationPolicy.rootPage(page); }
    private boolean fullConnection(JSONObject store) { return ServerSettings.configured(this) && !store.optString("reader_token").equals(invalidToken) && NativeScreenPolicy.connected(store.optString("mode"), store.optBoolean("logout_pending"), store.optBoolean("native_ready") || store.optBoolean("cookie_ready"), store.optString("reader_token"), NativeApi.time(store.optString("expires_at")), System.currentTimeMillis()); }
    private boolean valid(String token, long epoch) {
        if (isDestroyed() || isFinishing() || epoch != sessionEpoch || !token.equals(loadedToken)) return false;
        JSONObject store = SessionStore.read(this);
        return token.equals(store.optString("reader_token")) && fullConnection(store);
    }
    private boolean samePage(long revision) { return !isDestroyed() && revision == pageEpoch; }
    private void reconcileConnection() {
        JSONObject store = SessionStore.read(this); String token = fullConnection(store) ? store.optString("reader_token") : "";
        if (token.equals(loadedToken)) return;
        sessionEpoch++; actionRevision++; pageEpoch++; pendingTaskSearchFocus = null; loadedToken = token; taskQuery = ""; taskSearchExpanded = false; taskReturnDevice = ""; selectedId = ""; page = "tasks"; snapshot = null; account = null; lastSuccess = 0; nextSnapshot = 0; failed = false;
        focusPendingOnOpen = false; firstWorkbenchStartedElapsed = 0; loadSavedSyncDiagnostics();
        usageSummary = null; usagePageState = null; nativeUsage = null; usageBusy = false; usageFailed = false; nextUsage = 0;
        usageRequestRevision = 0; usageAcceptedRevision = 0; usageAlertRevision = 0;
        UsageAlerts.sync(this);
        MonitorWidgetUpdater.refreshAll(this);
        snapshotBusy = false; accountBusy = false; filterBusy = false; filterDirty = false; apiCooldownUntil = 0; avatarBusy = false; avatarGeneration++; mutations.clear(); listPositions.clear(); scrollPositions.clear(); workbenchHeaderScroll = 0; cancelDuration(); clearNotificationRequest();
        // A direct account switch has the same private-state boundary as logout.
        profileDraft = false; draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = "";
        pickerToken = ""; pickerPage = ""; pickerEpoch = -1; taskResultsState = null;
        pendingDocumentResult = null; hasPendingDocumentResult = false;
        showPage(false); loadCurrentPage();
    }
    private boolean request(String method, String path, JSONObject payload, Reply done) {
        final String token = loadedToken; final long epoch = sessionEpoch, revision = pageEpoch;
        final boolean readsWorkspace = method.equals("GET") && path.equals("/api/native/workbench");
        if (token.isEmpty() || !valid(token, epoch)) {
            if (readsWorkspace) workbenchReadSkipped(epoch);
            return false;
        }
        if (SystemClock.elapsedRealtime() < apiCooldownUntil) { done.done(null, revision); return true; }
        ExecutorService executor = readsWorkspace ? workbenchReader : worker;
        executor.execute(() -> {
            JSONObject value = null; NativeApi.Failure failure = null;
            try { value = NativeApi.call(method, path, payload, token); } catch (NativeApi.Failure error) { failure = error; }
            final JSONObject result = value; final NativeApi.Failure error = failure;
            handler.post(() -> {
                if (!valid(token, epoch)) {
                    if (readsWorkspace) workbenchReadSkipped(epoch);
                    return;
                }
                if (error != null && (error.status == 401 || error.status == 403)) { invalidate(token); return; }
                if (error != null && error.status == 429) apiCooldownUntil = Math.max(apiCooldownUntil, SystemClock.elapsedRealtime() + error.retryAfterMs);
                if (error != null && !method.equals("GET") && samePage(revision) && visible) notice(error.getMessage());
                done.done(error == null ? result : null, revision);
            });
        });
        return true;
    }
    private void mutate(String key, String method, String path, JSONObject payload, Reply done) {
        if (mutations.contains(key) || loadedToken.isEmpty()) return;
        mutations.add(key); dataRevision.started(NativeWorkbenchPolicy.Change.WORKSPACE);
        request(method, path, payload, (result, revision) -> {
            mutations.remove(key); dataRevision.finished(NativeWorkbenchPolicy.Change.WORKSPACE); nextSnapshot = 0;
            if (result != null) done.done(result, revision);
            scheduleSnapshot();
        });
    }
    private void invalidate(String token) { disconnectLocally(token); if (visible) notice("登录已失效，请重新登录"); }
    private boolean canReadWorkspace() {
        return visible && !isDestroyed() && !isFinishing() && workspacePage() && !loadedToken.isEmpty() && !snapshotBusy;
    }
    private void scheduleSnapshot() {
        handler.removeCallbacks(refreshWorkspace);
        if (!canReadWorkspace()) return;
        long now = SystemClock.elapsedRealtime();
        long delay = NativeWorkbenchPolicy.snapshotDelay(now, nextSnapshot, apiCooldownUntil);
        if (delay == 0) fetchSnapshot();
        else handler.postDelayed(refreshWorkspace, delay);
    }
    private void workbenchReadSkipped(long epoch) {
        // Ignore an old session's callback without disturbing a newer read.
        if (epoch != sessionEpoch) return;
        snapshotBusy = false;
        nextSnapshot = SystemClock.elapsedRealtime() + 1000;
        scheduleSnapshot();
    }
    private void refreshWorkspaceNow() {
        nextSnapshot = 0;
        scheduleSnapshot();
    }
    private void fetchSnapshot() {
        if (!canReadWorkspace()) return;
        long started = SystemClock.elapsedRealtime();
        if (NativeWorkbenchPolicy.snapshotDelay(started, nextSnapshot, apiCooldownUntil) > 0) { scheduleSnapshot(); return; }
        final boolean wasFirstScreen = snapshot == null;
        if (wasFirstScreen && firstWorkbenchStartedElapsed == 0) firstWorkbenchStartedElapsed = started;
        snapshotBusy = true; final long requestedData = dataRevision.capture();
        final long requestedUsage = ++usageRequestRevision;
        updateConnection();
        if (snapshot == null && emptyLabel != null) emptyLabel.setText("正在读取");
        request("GET", "/api/native/workbench", null, (value, revision) -> {
            snapshotBusy = false;
            if (!dataRevision.accepts(requestedData)) {
                logWorkbench("discard_epoch", value); updateConnection(); refreshWorkspaceNow(); return;
            }
            nextSnapshot = NativeWorkbenchPolicy.nextSnapshot(started, SystemClock.elapsedRealtime(), value != null);
            if (value == null) { logWorkbench("failure", null); failed = true; }
            else {
                snapshot = value; failed = false; lastSuccess = SystemClock.elapsedRealtime(); JSONObject preferences = value.optJSONObject("preferences");
                MonitorWidgetUpdater.cacheWorkbench(this, value, loadedToken);
                acceptUsage(value.optJSONObject("usage"), requestedUsage);
                logWorkbench("accepted", value);
                if (preferences != null) applyOutputPreference(preferences.optBoolean("sync_output"));
                if (!filterInitialized && preferences != null) { tool = NativeScreenPolicy.tool(preferences.optString("tool_filter")); filterInitialized = true; saveFilters(); updateTaskFilterLabels(); }
            }
            updateConnection(); updateWorkspace();
            if (value != null) {
                long anchor = wasFirstScreen && firstWorkbenchStartedElapsed > 0 ? firstWorkbenchStartedElapsed : started;
                firstWorkbenchStartedElapsed = 0;
                recordSyncDiagnostics(value, Math.max(0, SystemClock.elapsedRealtime() - anchor), wasFirstScreen);
            }
            scheduleSnapshot();
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
        accountBusy = true; accountFailed = false; updateAccountHeader(); final long requestedData = dataRevision.capture();
        request("GET", "/api/native/account", null, (value, revision) -> {
            accountBusy = false;
            if (!dataRevision.accepts(requestedData)) { if (visible && accountPage()) handler.postDelayed(this::fetchAccount, 300); return; }
            if (value != null) {
                account = value; JSONObject preferences = value.optJSONObject("preferences"); if (preferences != null) applyOutputPreference(preferences.optBoolean("sync_output"));
                if (page.equals("account")) updateAccountHeader();
                else if (page.equals("sessions") || page.equals("sync") || (page.equals("profile") && !profileDraft)) { savePosition(); showPage(false); }
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
        MonitorWidgetUpdater.cacheUsage(this, value, loadedToken);
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
        pendingTaskSearchFocus = null; pendingSettingsSheet = "";
        savePosition(); captureDraft(); if (destination.equals("task")) taskReturnDevice = page.equals("device") ? selectedId : "";
        page = safePage(destination); selectedId = id == null ? "" : id; pageEpoch++; actionRevision++; cancelDuration(); clearNotificationRequest();
        showPage(true); loadCurrentPage();
    }
    private void savePosition() {
        if (list != null) listPositions.put(pageKey(), list.onSaveInstanceState());
        if (page.equals("tasks")) workbenchHeaderScroll = currentWorkbenchHeaderScroll();
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
    }
    private int currentWorkbenchHeaderScroll() {
        return list == null || !list.isInTouchMode() || list.getFirstVisiblePosition() != 0
                ? 0 : firstListRowOffset(list);
    }
    private void restorePosition() {
        if (list != null && listPositions.containsKey(pageKey())) {
            list.onRestoreInstanceState(listPositions.get(pageKey()));
            // Native list state does not preserve a partial offset in the first fixed row.
            final ListView target = list;
            final int offset = page.equals("tasks") ? workbenchHeaderScroll : 0;
            if (offset > 0) target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    target.removeOnLayoutChangeListener(this);
                    if (list == target && target.getFirstVisiblePosition() == 0 && target.isInTouchMode()) {
                        int delta = offset - currentWorkbenchHeaderScroll();
                        if (delta > 0) target.scrollListBy(delta);
                    }
                }
            });
        }
        if (scroll != null) {
            final ScrollView target = scroll;
            int y = scrollPositions.containsKey(pageKey()) ? scrollPositions.get(pageKey()) : 0;
            target.post(() -> { if (scroll == target) target.scrollTo(0, y); });
        }
    }

    private void showPage(boolean animate) {
        if (body == null) return;
        String previousPage = renderedPage; renderedPage = page;
        pairingCodeArea = null;
        if (!page.equals(NativeNavigationPolicy.settingsSheetParent(pendingSettingsSheet))) pendingSettingsSheet = "";
        if (!page.equals("tasks") || loadedToken.isEmpty()) pendingTaskSearchFocus = null;
        if (taskSearchInput != null && taskSearchInput.hasFocus()) hideTaskSearchKeyboard();
        cancelSearchAnimation();
        if (taskSearchPanel != null) pageHeader.removeView(taskSearchPanel);
        taskSearchInput = null; taskSearchClear = null; taskSearchSummary = null; taskSearchPanel = null; taskSearchToggle = null;
        expandedRootTitle = null; expandedRootOpening = 0; taskSearchAffordance = null; taskSearchAffordanceLabel = null;
        UsageAlerts.dismissSettings(this); liveSettings.dismiss(); currentSettingsSheet = ""; settingsSheetDialog = null;
        if (helpDialog != null && (loadedToken.isEmpty() || !pageKey().equals(helpPage))) dismissHelp();
        dismissTaskFilterSheet(); dismissTaskPendingSheet();
        if (taskResults != null) { taskResults.dispose(); taskResults = null; }
        taskHeadingTitle = null; taskHeadingTool = null;
        if (nativeUsage != null) usagePageState = nativeUsage.saveState(); nativeUsage = null; taskUsage = null;
        list = null; scroll = null; fixedBottomAction = null; taskAdapter = null; deviceAdapter = null; taskScopeFilter = null; taskSourceFilter = null; taskPendingEntry = null; taskSourceCounts = null; taskDetail = null; trackingButton = null; trackingNote = null; archiveButton = null; emptyStateContainer = null; emptyLabel = null; emptyShowAllButton = null; syncDiagnosticsBody = null; deviceStatus = null; nameInput = null; avatarView = null; outputSwitch = null; profileSaveButton = null; avatarPickButton = null;
        loginStatus = null; loginNote = null; loginButton = null; loginCheckButton = null;
        accountCard = null; accountName = null; accountRetry = null; accountAvatar = null; accountChevron = null;
        body.animate().cancel(); body.setAlpha(1); body.setTranslationY(0); body.setTranslationX(0); body.removeAllViews(); titleBar.removeAllViews();
        titleBar.animate().cancel(); titleBar.setAlpha(1); titleBar.setTranslationX(0); titleBar.setTranslationY(0);
        titleHeading = null; titleActions = null; connection = null; appearancePage = null;
        headerMeta.removeAllViews(); headerMeta.setVisibility(View.GONE);
        overlayHeader = !loadedToken.isEmpty() && rootPage();
        integratedHeader = overlayHeader && usesCollapsingTitle();
        compactTitleAlpha = 0f; expandedTitleAlpha = 1f;
        pageRule.setVisibility(integratedHeader ? View.INVISIBLE : View.VISIBLE);
        pageRule.setAlpha(1f);
        // Do not pass taps on fixed toolbar gaps into a row passing beneath them.
        pageHeader.setClickable(!integratedHeader);
        pageHeader.setFocusable(false);
        updateHeaderSurface();
        headerMeta.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(overlayHeader ? 6 : 16));
        // The back glyph remains aligned with the reading column inside its 48 dp hit area.
        int titlePadding = ui.dp(overlayHeader ? (page.equals("tasks") || page.equals("usage") ? 4 : 2) : 12);
        titleBar.setPadding(ui.dp(!loadedToken.isEmpty() && !rootPage() ? 8 : 20), titlePadding,
                ui.dp(overlayHeader ? 14 : rootPage() ? 20 : 10), titlePadding);
        if (loadedToken.isEmpty()) { buildLogin(); updateNavigationInsets(); return; }
        if (!rootPage()) addTitleAction(ui.iconButton("back", "返回", this::back));
        titleHeading = ui.column(); titleHeading.setGravity(Gravity.CENTER_VERTICAL); titleHeading.setMinimumHeight(ui.dp(48));
        title = ui.heading(pageTitle(), rootPage() ? 23 : page.equals("task") ? 18 : 20);
        title.setMaxLines(2); title.setMinHeight(ui.dp(48)); title.setGravity(Gravity.CENTER_VERTICAL); titleHeading.addView(title, wrap());
        if (integratedHeader) title.setAlpha(0f);
        connection = ui.text("", 14, false); connection.setTextColor(ui.warning);
        int warningInset = overlayHeader ? ui.dp(10) : 0;
        // A healthy connection has no placeholder; warnings occupy their own readable line.
        connection.setVisibility(View.GONE); headerMeta.addView(connection, wrap());
        connection.setBackground(ui.ripple(overlayHeader ? ui.surface : Color.TRANSPARENT, 12));
        connection.setPadding(warningInset, ui.dp(6), warningInset, ui.dp(6));
        connection.setOnClickListener(v -> {
            if (connection == null || v != connection) return;
            if (connection.getVisibility() == View.VISIBLE && !snapshotBusy) refreshWorkspaceNow();
        });
        connection.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (host.isClickable()) info.setClassName("android.widget.Button");
            }
        });
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleHeading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        titleHeading.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (android.os.Build.VERSION.SDK_INT >= 28) info.setHeading(true);
                if (host.isClickable()) info.setClassName("android.widget.Button");
            }
        });
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.gravity = Gravity.CENTER_VERTICAL; titleBar.addView(titleHeading, headingParams);
        if (page.equals("tasks") || page.equals("devices") || page.equals("account")) {
            titleActions = ui.headerActions();
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
            actionParams.gravity = Gravity.CENTER_VERTICAL; actionParams.leftMargin = ui.dp(12); titleBar.addView(titleActions, actionParams);
            addTitleAction(ui.headerIconButton("scan", "扫码连接电脑", this::scanComputerPairing));
            if (page.equals("tasks")) {
                taskSearchToggle = ui.headerIconButton("search", "展开搜索", () -> setTaskSearchExpanded(!taskSearchExpanded, true));
                addTitleAction(taskSearchToggle); updateSearchToggle();
            }
        }
        buildNavigation();
        switch (page) {
            case "tasks": buildTasks(); break; case "devices": buildDevices(); break; case "task": buildTask(); break; case "device": buildDevice(); break;
            case "account": buildAccount(); break;
            case "settings": case "settings-appearance": case "settings-background": case "settings-notifications": case "settings-support": case "settings-security": buildSettings(); break;
            case "profile": buildProfile(); break; case "sync": buildSync(); break; case "sync-diagnostics": buildSyncDiagnostics(); break; case "sessions": buildSessions(); break; case "usage": buildUsage(); break;
            case "pairing": buildPairing(); break; case "pairing-confirm": buildPairingConfirm(); break; default: break;
        }
        updateConnection(); updateWorkspace(); updateNavigationInsets(); restorePosition();
        settleCollapsingTitleBeforeDraw();
        if (animate) { ui.transitionPage(body, previousPage, page, taskReturnDevice); ui.transitionPage(titleBar, previousPage, page, taskReturnDevice); }
        bottomBar.refreshBackdrop();
    }
    private boolean usesCollapsingTitle() { return page.equals("tasks") || page.equals("usage"); }
    private void addExpandedRootTitle(LinearLayout column) {
        setVerticalPadding(column, 0, column.getPaddingBottom());
        TextView heading = ui.heading(pageTitle(), 34);
        heading.setGravity(Gravity.CENTER);
        heading.setMinHeight(ui.dp(108));
        // Keep the existing vertical space and center the title with equal side insets.
        heading.setPadding(ui.dp(24), ui.dp(21), ui.dp(24), ui.dp(20) + ui.dp(22) - ui.dp(21));
        column.addView(heading, wrap());
        expandedRootTitle = heading;
        updateCollapsingTitle(0);
    }
    private void updateExpandedRootSpacing(boolean pinned) {
        if (!integratedHeader || expandedRootTitle == null) return;
        // Fold the old unpainted clearance into the hero. Keeping its total height and total text
        // padding unchanged relative to the space added also preserves the next row at large fonts.
        int extraToolbarPadding = 2 * ui.dp(8) - titleBar.getPaddingTop() - titleBar.getPaddingBottom();
        int unpaintedClearance = extraToolbarPadding;
        if (!pinned) unpaintedClearance += titleBar.getMeasuredHeight() + pageRule.getLayoutParams().height;
        int opening = Math.max(0, ui.dp(page.equals("tasks") ? 8 : 10) + unpaintedClearance);
        if (expandedRootOpening == opening) return;
        expandedRootOpening = opening;
        int textInsets = ui.dp(20) + ui.dp(22) + opening;
        expandedRootTitle.setMinHeight(ui.dp(108) + opening);
        setVerticalPadding(expandedRootTitle, textInsets / 2, textInsets - textInsets / 2);
    }
    private void updateListTitle(ListView target) {
        if (list != target || expandedRootTitle == null || target.getChildCount() == 0) return;
        updateCollapsingTitle(target.getFirstVisiblePosition() == 0
                ? firstListRowOffset(target) : Integer.MAX_VALUE);
    }
    private void settleCollapsingTitleBeforeDraw() {
        final View heading = expandedRootTitle;
        if (heading == null) return;
        final ViewTreeObserver tree = stage.getViewTreeObserver();
        tree.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (tree.isAlive()) tree.removeOnPreDrawListener(this);
                else stage.getViewTreeObserver().removeOnPreDrawListener(this);
                if (expandedRootTitle == heading) {
                    if (list != null) updateListTitle(list);
                    else if (scroll != null) updateCollapsingTitle(scroll.getScrollY());
                }
                return true;
            }
        });
    }
    private static int firstListRowOffset(ListView target) {
        View header = target.getChildAt(0);
        return header == null ? 0 : Math.max(0, target.getPaddingTop() - header.getTop());
    }
    private void updateCollapsingTitle(int offset) {
        if (expandedRootTitle == null || title == null) return;
        int extent = expandedRootTitle.getHeight() - expandedRootOpening;
        if (extent <= 0) extent = expandedRootTitle.getMinHeight() - expandedRootOpening;
        float progress = Math.max(0f, Math.min(1f, offset / (float) Math.max(1, extent)));
        boolean motion = ui.motionEnabled();
        compactTitleAlpha = motion ? ramp(progress, .10f, .57f) : progress < .32f ? 0f : 1f;
        expandedTitleAlpha = motion ? 1f - ramp(progress, .05f, .55f) : 1f - compactTitleAlpha;
        updateIntegratedHeader();
    }
    private boolean headerNeedsClearance() {
        return (taskSearchPanel != null && taskSearchPanel.getVisibility() == View.VISIBLE)
                || headerMeta.getVisibility() == View.VISIBLE;
    }
    private void updateHeaderSurface() {
        opaqueCompactHeader = ui.reducedTransparencyEnabled();
        compactHeaderSurface = ui.compactHeaderMaterial(opaqueCompactHeader);
        pageHeader.setBackground(compactHeaderSurface);
        if (integratedHeader) updateIntegratedHeader();
        systemBars.setColors(integratedHeader ? ui.bg : ui.headerTopColor(), ui.bg);
    }
    private void updateIntegratedHeader() {
        if (!integratedHeader) return;
        boolean pinned = headerNeedsClearance();
        float chrome = pinned ? 1f : compactTitleAlpha;
        boolean compactOwnsHeading = compactTitleAlpha >= .45f;
        boolean compactOwnsControls = pinned || compactOwnsHeading;
        // Keep the expanded hero clear; when compact controls take over, their opaque backdrop must not fade.
        if (opaqueCompactHeader) chrome = compactOwnsControls ? 1f : 0f;
        if (compactHeaderSurface != null) compactHeaderSurface.setAlpha(Math.round(chrome * 255f));
        if (title != null) title.setAlpha(compactTitleAlpha);
        pageRule.setAlpha(chrome);
        pageRule.setVisibility(chrome > 0f ? View.VISIBLE : View.INVISIBLE);
        // Make the active chrome a tap shield; at the expanded top the transparent overlay passes touches through.
        pageHeader.setClickable(compactOwnsControls);
        if (titleActions != null) {
            // Fade the title and its surface without dimming the active search button.
            titleActions.setVisibility(compactOwnsControls ? View.VISIBLE : View.INVISIBLE);
        }
        if (titleHeading != null) titleHeading.setImportantForAccessibility(compactOwnsHeading ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (expandedRootTitle != null) {
            expandedRootTitle.setImportantForAccessibility(compactOwnsHeading ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            expandedRootTitle.setAlpha(expandedTitleAlpha);
        }
    }
    private static float ramp(float value, float start, float end) {
        float t = Math.max(0f, Math.min(1f, (value - start) / (end - start)));
        return t * t * (3f - 2f * t);
    }
    /** Keep controls centered on the title, independently of connection messages. */
    private void addTitleAction(View action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        params.gravity = Gravity.CENTER_VERTICAL; (titleActions == null ? titleBar : titleActions).addView(action, params);
    }
    private String pageTitle() { String settings = NativeNavigationPolicy.settingsTitle(page); if (!settings.isEmpty()) return settings; switch (page) { case "tasks": return "工作台"; case "devices": return "我的电脑"; case "account": return "我的"; case "profile": return "个人资料"; case "sync": return "个人同步"; case "sync-diagnostics": return "同步诊断"; case "sessions": return "登录设备"; case "usage": return "用量"; case "pairing": return "添加电脑"; case "pairing-confirm": return "确认电脑"; case "device": return "电脑详情"; default: return "任务详情"; } }
    private void buildNavigation() {
        bottomBar.setSelected(NativeNavigationPolicy.tabForPage(page, taskReturnDevice), true);
        updateNavigationInsets();
    }
    private int navigationClearance() { return loadedToken.isEmpty() || imeVisible ? 0 : ui.dp(96); }
    private void updateNavigationInsets() {
        if (bottomBar == null) return;
        boolean show = !loadedToken.isEmpty() && !imeVisible;
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
        int available = stage.getWidth() > 0 ? stage.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(ui.dp(320), Math.max(ui.dp(168), available - ui.dp(48)));
        if (params.width != width) { params.width = width; bottomBar.setLayoutParams(params); }
    }
    private void updateContentPadding(int top) {
        int clearance = navigationClearance();
        if (scroll != null) setVerticalPadding(scroll, top, clearance);
        if (fixedBottomAction != null) setVerticalPadding(fixedBottomAction, fixedBottomAction.getPaddingTop(), ui.dp(8) + clearance);
        if (list != null) setVerticalPadding(list, top, listBaseBottomPadding + (fixedBottomAction == null ? clearance : 0));
    }
    private static void setVerticalPadding(View view, int top, int bottom) {
        if (view.getPaddingTop() != top || view.getPaddingBottom() != bottom) {
            view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(), bottom);
        }
    }
    private LinearLayout content() {
        ScrollView view = new ScrollView(this); view.setFillViewport(false); view.setClipToPadding(false); view.setVerticalScrollBarEnabled(false);
        LinearLayout column = ui.column(); column.setPadding(ui.dp(20), ui.dp(overlayHeader && usesCollapsingTitle() ? 4 : overlayHeader ? 10 : 16), ui.dp(20), ui.dp(28)); view.addView(column); body.addView(view, new FrameLayout.LayoutParams(-1, -1)); scroll = view; pageColumn = column;
        if (overlayHeader && usesCollapsingTitle()) {
            addExpandedRootTitle(column);
            view.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (scroll == view) updateCollapsingTitle(y); });
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (scroll == view) updateCollapsingTitle(view.getScrollY()); });
        }
        return column;
    }
    private void buildLogin() {
        bottomBar.setVisibility(View.GONE); title = ui.text("Monitor", 24, true); titleBar.addView(title);
        LinearLayout column = content(); column.addView(ui.space(32)); column.addView(ui.monitorBrand(72)); column.addView(ui.space(20)); column.addView(ui.text("你的工作，\n随时在眼前", 28, true)); column.addView(ui.space(28));
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
            loginStatus.setText("旧连接需要重新设置"); loginNote.setText("先清除本机旧连接，再填写服务器地址");
            loginButton.setText("处理旧连接"); loginButton.setEnabled(true); loginButton.setAlpha(1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (ServerSettings.requiresRestart(this)) {
            loginStatus.setText("地址已保存"); loginNote.setText("关闭后重新打开 Monitor，即可使用新地址");
            loginButton.setText("完成服务器设置"); loginButton.setEnabled(true); loginButton.setAlpha(1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (SessionStore.read(this).optBoolean("logout_pending")) {
            loginStatus.setText("完成退出"); loginNote.setText("本机已退出，联网后完成撤销");
            loginButton.setText(logoutBusy ? "正在完成退出…" : "完成退出"); loginButton.setEnabled(!logoutBusy); loginButton.setAlpha(logoutBusy ? .5f : 1f);
            loginCheckButton.setVisibility(View.GONE); return;
        }
        if (!ServerSettings.configured(this)) {
            loginStatus.setText("连接你的电脑"); loginNote.setText("先填写部署好的 HTTPS 服务器地址");
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
        column.setFocusableInTouchMode(true);
        LinearLayout filters = ui.column(); filters.setPadding(0, ui.dp(4), 0, ui.dp(6));
        addExpandedRootTitle(filters);
        addTaskSearchAffordance(filters);
        LinearLayout choices = ui.row();
        taskScopeFilter = taskFilterButton(false);
        taskSourceFilter = taskFilterButton(true);
        choices.addView(taskScopeFilter, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(0, -2, 1);
        sourceParams.setMarginStart(ui.dp(8));
        choices.addView(taskSourceFilter, sourceParams);
        taskPendingEntry = ui.button("", false, this::showPendingTasksSheet);
        taskPendingEntry.setSingleLine(true);
        taskPendingEntry.setTextSize(13);
        taskPendingEntry.setPaddingRelative(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        taskPendingEntry.setMinHeight(ui.dp(40));
        taskPendingEntry.setTextColor(ui.warning);
        taskPendingEntry.setBackground(ui.ripple(ui.dark ? 0xFF423B2C : 0xFFF3EBDC, 12));
        taskPendingEntry.setVisibility(View.GONE);
        LinearLayout.LayoutParams pendingParams = new LinearLayout.LayoutParams(-2, -2);
        pendingParams.setMarginStart(ui.dp(8));
        choices.addView(taskPendingEntry, pendingParams);
        filters.addView(choices, wrap());
        updateTaskFilterLabels();
        buildTaskSearch(filters);
        taskAdapter = new NativePages.TaskAdapter(ui, taskCallbacks()); list = NativePages.list(ui, null);
        list.setItemsCanFocus(true);
        list.addHeaderView(filters, null, false);
        attachList(column);
        list.setAdapter(taskAdapter);
        final ListView workbench = list;
        workbench.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { updateListTitle(workbench); }
        });
        workbench.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateListTitle(workbench));
        column.requestFocus();
    }
    private void addTaskSearchAffordance(LinearLayout filters) {
        LinearLayout action = ui.row();
        action.setBackground(ui.bordered(ui.surface, 16));
        action.setPaddingRelative(ui.dp(14), 0, ui.dp(14), 0);
        action.setMinimumHeight(ui.dp(52));
        View icon = ui.icon("search", 20);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(icon);
        taskSearchAffordanceLabel = ui.text("搜索任务、项目或电脑", 15, false);
        taskSearchAffordanceLabel.setSingleLine(true);
        taskSearchAffordanceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        taskSearchAffordanceLabel.setGravity(Gravity.CENTER_VERTICAL);
        taskSearchAffordanceLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(0, -2, 1);
        label.setMarginStart(ui.dp(10)); action.addView(taskSearchAffordanceLabel, label);
        action.setOnClickListener(v -> focusOrExpandTaskSearch());
        action.setFocusable(true);
        action.setAccessibilityDelegate(NativeUi.buttonAccessibility());
        LinearLayout.LayoutParams row = wrap(); row.bottomMargin = ui.dp(10); filters.addView(action, row);
        taskSearchAffordance = action;
        updateSearchToggle();
    }
    private TextView taskFilterButton(boolean source) {
        TextView button = ui.quietButton("", null);
        button.setTextColor(ui.text);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        button.setOnClickListener(v -> showTaskFilterSheet(button, source));
        return button;
    }
    private void updateTaskFilterLabels() {
        if (taskScopeFilter == null || taskSourceFilter == null) return;
        bindTaskFilterLabel(taskScopeFilter, "状态：" + TASK_SCOPE_LABELS[filterIndex(TASK_SCOPES, scope)]);
        bindTaskFilterLabel(taskSourceFilter, "来源：" + NativeScreenPolicy.toolLabel(NativeScreenPolicy.tool(tool)));
    }
    private static int filterIndex(String[] keys, String selected) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(selected)) return i;
        return 0;
    }
    private static void bindTaskFilterLabel(TextView button, String label) {
        if (!android.text.TextUtils.equals(button.getContentDescription(), label)) {
            button.setText(label + "\u00a0▾");
            button.setContentDescription(label);
        }
    }
    private void showTaskFilterSheet(TextView anchor, boolean source) {
        if (!visible || isFinishing() || isDestroyed() || !page.equals("tasks") || loadedToken.isEmpty()
                || anchor != (source ? taskSourceFilter : taskScopeFilter)) return;
        dismissTaskFilterSheet();
        final long requestedPage = pageEpoch;
        String[] keys = source ? TASK_SOURCES : TASK_SCOPES;
        int selected = filterIndex(keys, source ? tool : scope);
        LivePresentationSheet dialog = new LivePresentationSheet(this, source ? "任务来源" : "任务状态", "关闭筛选");
        taskFilterSheet = dialog;
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            String count = source && taskSourceCounts != null && i < taskSourceCounts.length
                    ? Integer.toString(taskSourceCounts[i]) : "";
            dialog.choice(source ? NativeScreenPolicy.toolLabel(key) : TASK_SCOPE_LABELS[i], count, i == selected, () -> {
                if (!visible || !samePage(requestedPage) || taskFilterSheet != dialog
                        || anchor != (source ? taskSourceFilter : taskScopeFilter)
                        || key.equals(source ? tool : scope)) return;
                if (source) { tool = key; filterInitialized = true; }
                else scope = key;
                saveFilters();
                updateTaskFilterLabels();
                updateWorkspace();
                if (list != null) list.setSelection(0);
                if (source) persistFilter();
            });
        }
        dialog.setOnDismissListener(ignored -> { if (taskFilterSheet == dialog) taskFilterSheet = null; });
        try { dialog.show(); }
        catch (RuntimeException failure) { dismissTaskFilterSheet(); notice("暂时无法打开筛选，请重试"); }
    }
    private void dismissTaskFilterSheet() {
        if (taskFilterSheet == null) return;
        LivePresentationSheet dialog = taskFilterSheet;
        taskFilterSheet = null;
        dialog.dismiss();
    }
    private void showPendingTasksSheet() {
        if (!visible || isFinishing() || isDestroyed() || !page.equals("tasks") || loadedToken.isEmpty()) return;
        java.util.List<JSONObject> pending = NativeWorkbenchOverview.pendingTasks(snapshot, stale());
        if (pending.isEmpty()) { notice("当前没有待处理的任务"); return; }
        dismissTaskPendingSheet();
        final long requestedPage = pageEpoch;
        LivePresentationSheet dialog = new LivePresentationSheet(this, "待处理 · " + pending.size(), "关闭待处理列表");
        taskPendingSheet = dialog;
        if (!"all".equals(tool) || !"all".equals(scope) || NativeTaskSearchPolicy.isSearching(taskQuery)) {
            dialog.note("列出所有在线电脑上等你批准或回复选项的任务；直接进入处理不会改动当前工作台筛选");
        }
        LinearLayout listColumn = ui.column();
        for (int i = 0; i < pending.size(); i++) {
            final JSONObject item = pending.get(i);
            final String id = item.optString("id");
            JSONObject computer = findDevice(item.optString("device_id"));
            View row = NativePages.pendingTaskRow(ui, item, computer, () -> {
                if (!visible || !samePage(requestedPage)) return;
                dismissTaskPendingSheet();
                openPendingTask(id);
            });
            LinearLayout.LayoutParams rowParams = wrap();
            if (i + 1 < pending.size()) rowParams.bottomMargin = ui.dp(10);
            listColumn.addView(row, rowParams);
        }
        dialog.body(listColumn);
        dialog.setOnDismissListener(ignored -> { if (taskPendingSheet == dialog) taskPendingSheet = null; });
        try { dialog.show(); }
        catch (RuntimeException failure) { dismissTaskPendingSheet(); notice("暂时无法打开待处理列表，请重试"); }
    }
    private void dismissTaskPendingSheet() {
        if (taskPendingSheet == null) return;
        LivePresentationSheet dialog = taskPendingSheet;
        taskPendingSheet = null;
        dialog.dismiss();
    }
    private void openPendingTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        focusPendingOnOpen = true;
        navigate("task", taskId);
    }
    private void resetAllTaskFilters() {
        if (!visible || loadedToken.isEmpty()) return;
        tool = "all";
        scope = "all";
        taskQuery = "";
        filterInitialized = true;
        if (taskSearchInput != null && taskSearchInput.length() > 0) taskSearchInput.setText("");
        if (taskSearchExpanded) setTaskSearchExpanded(false, false);
        saveFilters();
        updateTaskFilterLabels();
        persistFilter();
        listPositions.remove(pageKey());
        workbenchHeaderScroll = 0;
        updateWorkspace();
        if (list != null) list.setSelection(0);
    }
    private void buildTaskSearch(LinearLayout filters) {
        taskSearchPanel = ui.column();
        taskSearchPanel.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(6));
        LinearLayout row = ui.row(); row.setBackground(ui.bordered(ui.surface, 16)); row.setPadding(ui.dp(4), 0, ui.dp(2), 0);
        View searchIcon = ui.icon("search", 20); LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)); iconParams.leftMargin = ui.dp(10); row.addView(searchIcon, iconParams);
        final EditText field = input("搜索任务、项目或电脑", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        taskSearchInput = field; field.setSingleLine(true); field.setSaveEnabled(false); field.setBackgroundColor(Color.TRANSPARENT);
        field.setMinHeight(ui.dp(50)); field.setPadding(ui.dp(10), ui.dp(10), ui.dp(4), ui.dp(10)); field.setTextSize(15);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(NativeTaskSearchPolicy.MAX_QUERY_LENGTH)});
        field.setContentDescription("搜索任务、项目或电脑"); field.setText(taskQuery);
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        taskSearchClear = ui.iconButton("close", "清空搜索", () -> { if (taskSearchInput == field) field.setText(""); });
        taskSearchClear.setVisibility(taskQuery.isEmpty() ? View.INVISIBLE : View.VISIBLE); row.addView(taskSearchClear);
        taskSearchPanel.addView(row, wrap()); taskSearchPanel.setVisibility(taskSearchExpanded ? View.VISIBLE : View.GONE); pageHeader.addView(taskSearchPanel, pageHeader.indexOfChild(pageRule), wrap());
        taskSearchSummary = ui.text("", 12); taskSearchSummary.setTextColor(ui.muted);
        taskSearchSummary.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(6)); taskSearchSummary.setSingleLine(true); taskSearchSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        taskSearchSummary.setVisibility(View.GONE); taskSearchSummary.setMinHeight(ui.dp(48)); taskSearchSummary.setBackground(ui.ripple(Color.TRANSPARENT, 10));
        taskSearchSummary.setOnClickListener(v -> focusOrExpandTaskSearch()); taskSearchSummary.setAccessibilityDelegate(NativeUi.buttonAccessibility()); filters.addView(taskSearchSummary, wrap());
        final long searchSession = sessionEpoch, searchPage = pageEpoch;
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { }
            public void afterTextChanged(Editable text) {
                if (taskSearchInput != field || !page.equals("tasks") || loadedToken.isEmpty()
                        || sessionEpoch != searchSession || pageEpoch != searchPage) return;
                taskQuery = NativeTaskSearchPolicy.boundedQuery(text.toString());
                updateSearchToggle();
                taskSearchClear.setVisibility(taskQuery.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                // Search owns only the displayed rows, never a network read or saved server preference.
                listPositions.remove(pageKey()); workbenchHeaderScroll = 0; updateWorkspace(); if (list != null) list.setSelection(0);
            }
        });
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH || taskSearchInput != field) return false;
            hideTaskSearchKeyboard(); return true;
        });
    }
    private void updateSearchToggle() {
        if (taskSearchToggle == null) return;
        boolean filtered = NativeTaskSearchPolicy.isSearching(taskQuery);
        ui.setHeaderIconSelected(taskSearchToggle, taskSearchExpanded || filtered);
        taskSearchToggle.setContentDescription(taskSearchExpanded ? "收起搜索，保留当前关键词"
                : filtered ? "展开搜索，当前关键词：" + taskQuery : "展开搜索");
        if (Build.VERSION.SDK_INT >= 30) taskSearchToggle.setStateDescription(taskSearchExpanded ? "已展开" : "已收起");
        if (taskSearchAffordance != null) {
            // Keep the same ListView header size while the separate editor opens.
            String label = taskSearchExpanded ? "回到搜索" : filtered ? taskQuery : "搜索任务、项目或电脑";
            if (!android.text.TextUtils.equals(taskSearchAffordanceLabel.getText(), label)) taskSearchAffordanceLabel.setText(label);
            taskSearchAffordanceLabel.setTextColor(filtered && !taskSearchExpanded ? ui.text : ui.muted);
            taskSearchAffordance.setContentDescription(filtered && !taskSearchExpanded ? "编辑搜索，当前关键词：" + taskQuery : label);
        }
    }
    private void focusOrExpandTaskSearch() {
        if (taskSearchExpanded) focusTaskSearch(taskSearchInput);
        else setTaskSearchExpanded(true, true);
    }
    private void focusTaskSearch(EditText field) {
        if (field == null || taskSearchInput != field || !taskSearchExpanded || !visible || !field.requestFocus()) return;
        field.post(() -> {
            if (taskSearchInput != field || !taskSearchExpanded || !visible || !field.hasFocus()) return;
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }
    private void cancelSearchAnimation() {
        if (taskSearchAnimation != null) { taskSearchAnimation.removeAllListeners(); taskSearchAnimation.removeAllUpdateListeners(); taskSearchAnimation.cancel(); taskSearchAnimation = null; }
    }
    private void setTaskSearchExpanded(boolean expanded, boolean userAction) {
        if (taskSearchPanel == null || taskSearchInput == null || !page.equals("tasks")) return;
        final LinearLayout panel = taskSearchPanel; final EditText field = taskSearchInput;
        int fromHeight = panel.getVisibility() == View.VISIBLE ? panel.getHeight() : 0;
        cancelSearchAnimation(); taskSearchExpanded = expanded; updateSearchToggle();
        if (!expanded) hideTaskSearchKeyboard();
        panel.setVisibility(View.VISIBLE);
        int width = ((View) panel.getParent()).getWidth() - ((View) panel.getParent()).getPaddingLeft() - ((View) panel.getParent()).getPaddingRight();
        if (width > 0) panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int targetHeight = expanded ? panel.getMeasuredHeight() : 0;
        Runnable complete = () -> {
            if (taskSearchPanel != panel || taskSearchExpanded != expanded) return;
            panel.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT; panel.setAlpha(1f); panel.setVisibility(expanded ? View.VISIBLE : View.GONE); panel.requestLayout();
            updateIntegratedHeader();
            if (expanded && userAction) focusTaskSearch(field);
        };
        if (!ui.motionEnabled() || width <= 0 || fromHeight == targetHeight) complete.run();
        else {
            taskSearchAnimation = android.animation.ValueAnimator.ofInt(fromHeight, targetHeight); taskSearchAnimation.setDuration(190);
            taskSearchAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            taskSearchAnimation.addUpdateListener(value -> { panel.getLayoutParams().height = (Integer) value.getAnimatedValue(); panel.requestLayout(); });
            taskSearchAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { taskSearchAnimation = null; complete.run(); } });
            taskSearchAnimation.start();
        }
        updateIntegratedHeader();
        updateWorkspace();
    }
    private void hideTaskSearchKeyboard() {
        pendingTaskSearchFocus = null;
        if (taskSearchInput == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(taskSearchInput.getWindowToken(), 0);
        taskSearchInput.clearFocus();
    }
    private void restoreTaskSearchFocus() {
        final EditText field = taskSearchInput;
        if (pendingTaskSearchFocus == null || field == null) return;
        int[] selection = pendingTaskSearchFocus.take(page, taskSearchExpanded, tokenFingerprint(loadedToken),
                pageEpoch, visible && !isFinishing() && !isDestroyed(), hasWindowFocus(), field.length());
        if (!pendingTaskSearchFocus.pending()) pendingTaskSearchFocus = null;
        // Restore editing focus/selection, not an unconditional request to open the IME.
        if (selection != null && field.requestFocus()) field.setSelection(selection[0], selection[1]);
    }
    private NativePages.TaskCallbacks taskCallbacks() { return new NativePages.TaskCallbacks() { public void open(JSONObject task) { navigate("task", task.optString("id")); } public void archive(JSONObject task) { MainActivity.this.archive(task); } }; }
    private void attachList(LinearLayout column) {
        listBaseBottomPadding = list.getPaddingBottom();
        FrameLayout area = new FrameLayout(this); column.addView(area, new LinearLayout.LayoutParams(-1, 0, 1)); area.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyStateContainer = ui.column();
        emptyStateContainer.setGravity(Gravity.CENTER);
        emptyStateContainer.setPadding(ui.dp(16), ui.dp(30), ui.dp(16), ui.dp(30));
        emptyLabel = ui.text("正在读取", 16, false); emptyLabel.setTextColor(ui.muted); emptyLabel.setGravity(Gravity.CENTER);
        emptyStateContainer.addView(emptyLabel, wrap());
        emptyShowAllButton = ui.button("查看全部", false, this::resetAllTaskFilters);
        emptyShowAllButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams showAllParams = new LinearLayout.LayoutParams(-2, -2);
        showAllParams.topMargin = ui.dp(14);
        emptyStateContainer.addView(emptyShowAllButton, showAllParams);
        if (page.equals("tasks") || page.equals("device")) {
            // A list footer keeps the filters or device identity visible when there are no tasks.
            FrameLayout emptyRow = new FrameLayout(this);
            emptyRow.addView(emptyStateContainer, new FrameLayout.LayoutParams(-1, -2));
            list.addFooterView(emptyRow, null, false);
        } else {
            area.addView(emptyStateContainer, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(emptyStateContainer);
        }
        emptyLabel.setOnClickListener(v -> {
            if (emptyShowAllButton != null && emptyShowAllButton.getVisibility() == View.VISIBLE) return;
            if (page.equals("tasks") && NativeTaskSearchPolicy.isSearching(taskQuery) && snapshot != null) return;
            if (!snapshotBusy) refreshWorkspaceNow();
        });
    }
    private void buildDevices() {
        LinearLayout column = ui.column(); body.addView(column, new FrameLayout.LayoutParams(-1, -1)); deviceAdapter = new NativePages.DeviceAdapter(ui, device -> navigate("device", device.optString("id"))); list = NativePages.list(ui, deviceAdapter); attachList(column);
        LinearLayout action = ui.column(); action.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8));
        action.addView(ui.button("扫码连接电脑", this::scanComputerPairing), wrap());
        action.addView(ui.space(4));
        action.addView(ui.quietButton("其他配对方式", () -> navigate("pairing", "")), wrap());
        column.addView(action, wrap()); fixedBottomAction = action;
    }
    private void buildTask() {
        LinearLayout column = content(); JSONObject task = findTask(selectedId), device = task == null ? null : findDevice(task.optString("device_id"));
        if (task == null) { placeholder(column, snapshot == null ? "正在读取任务" : "未找到这条任务"); return; }
        taskHeadingTool = ui.taskIdentity(task.optString("tool"), device == null ? "未知电脑" : device.optString("name", "未知电脑"));
        column.addView(taskHeadingTool, wrap());
        column.addView(ui.space(12));
        taskHeadingTitle = ui.heading(task.optString("title", "未命名任务"), 26);
        taskHeadingTitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        taskHeadingTitle.setLineSpacing(ui.dp(6), 1.08f);
        column.addView(taskHeadingTitle, wrap());
        column.addView(ui.space(16));
        taskDetail = new NativePages.TaskDetail(ui);
        column.addView(taskDetail.view(), wrap());
        final String resultTaskId = selectedId, resultToken = loadedToken; final long resultSession = sessionEpoch, resultPage = pageEpoch;
        final boolean interactive = NativeSessionControlPolicy.provider(task.optString("tool"));
        taskResults = new NativeTaskResults(ui, resultTaskId, resultToken, interactive, new NativeTaskResults.Host() {
            public boolean connected() { return valid(resultToken, resultSession) && samePage(resultPage) && page.equals("task") && resultTaskId.equals(selectedId); }
            public boolean visible() { return MainActivity.this.visible; }
            public void failure(NativeApi.Failure failure) { if (failure.status == 401 || failure.status == 403) invalidate(resultToken); }
            public void notice(String message) { if (MainActivity.this.visible) MainActivity.this.notice(message); }
        }, taskResultsState); taskResultsState = null;
        final NativeTaskResults results = taskResults;
        LinearLayout.LayoutParams attentionSpace = wrap(); attentionSpace.topMargin = ui.dp(16); attentionSpace.bottomMargin = ui.dp(20);
        if (interactive) {
            column.addView(results.attentionView, attentionSpace);
            if (focusPendingOnOpen) {
                final ScrollView targetScroll = scroll;
                final View targetAttention = results.attentionView;
                results.attentionView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        if (!focusPendingOnOpen || scroll != targetScroll || !page.equals("task")) {
                            targetAttention.removeOnLayoutChangeListener(this);
                            return;
                        }
                        if (targetAttention.getVisibility() == View.VISIBLE && b - t > 0) {
                            focusPendingOnOpen = false;
                            targetAttention.removeOnLayoutChangeListener(this);
                            targetScroll.post(() -> {
                                if (scroll == targetScroll) targetScroll.smoothScrollTo(0, Math.max(0, targetAttention.getTop() - ui.dp(12)));
                            });
                        }
                    }
                });
            }
        } else {
            focusPendingOnOpen = false;
        }
        column.addView(taskDetail.errorSection, wrap());
        column.addView(ui.space(24));
        column.addView(taskResults.view, wrap());
        taskResults.update(task, syncOutput());
        if (hasPendingDocumentResult) { hasPendingDocumentResult = false; taskResults.documentResult(pendingDocumentCode, pendingDocumentResult); pendingDocumentResult = null; }
        column.addView(ui.space(28));
        View toolsDivider = new View(this); toolsDivider.setBackgroundColor(ui.line);
        column.addView(toolsDivider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        column.addView(ui.space(18));
        TextView toolsHeading = ui.text("任务工具", 13, true); toolsHeading.setTextColor(ui.muted);
        if (Build.VERSION.SDK_INT >= 28) toolsHeading.setAccessibilityHeading(true);
        column.addView(toolsHeading, wrap());
        column.addView(ui.space(8));
        if (interactive) column.addView(menu("settings", "模型设置", results::showModelSettings), wrap());
        LinearLayout trackingSection = ui.column();
        trackingSection.addView(ui.heading("实况跟踪", 16), wrap());
        TextView trackingDescription = ui.text("在系统实况中查看这项任务的进度", 14);
        trackingDescription.setTextColor(ui.muted);
        LinearLayout.LayoutParams descriptionSpace = wrap(); descriptionSpace.topMargin = ui.dp(8);
        trackingSection.addView(trackingDescription, descriptionSpace);
        trackingButton = ui.button("开始跟踪", false, () -> {
            if (selectedId.equals(TrackingService.trackedId)) {
                TrackingService.requestStop(this, TrackingService.generation);
                updateTracking();
            } else chooseTrackingDuration(selectedId, loadedToken, ++actionRevision);
        });
        LinearLayout.LayoutParams follow = wrap(); follow.topMargin = ui.dp(14);
        trackingSection.addView(trackingButton, follow);
        trackingNote = ui.text("", 13);
        trackingNote.setTextColor(ui.muted);
        trackingNote.setPadding(0, ui.dp(10), 0, 0);
        trackingSection.addView(trackingNote, wrap());
        LinearLayout.LayoutParams trackingSpace = wrap(); trackingSpace.topMargin = ui.dp(18);
        column.addView(trackingSection, trackingSpace);
        column.addView(ui.space(28));
        TextView informationHeading = ui.text("任务信息", 13, true); informationHeading.setTextColor(ui.muted);
        if (Build.VERSION.SDK_INT >= 28) informationHeading.setAccessibilityHeading(true);
        column.addView(informationHeading, wrap());
        column.addView(ui.space(10));
        LinearLayout.LayoutParams noteSpace = wrap(); noteSpace.bottomMargin = ui.dp(12);
        column.addView(taskDetail.noteSection, noteSpace);
        column.addView(taskDetail.projectRow, wrap());
        if (interactive) {
            taskUsage = new NativeUsagePage.TaskCard(ui, this::showHelp);
            taskUsage.update(task);
            column.addView(taskUsage.view, wrap());
        }
        if (device != null) {
            column.addView(ui.space(8));
            column.addView(menu("devices", "查看电脑", () -> navigate("device", task.optString("device_id"))), wrap());
        }
        column.addView(ui.space(8));
        archiveButton = ui.button("归档任务", false, () -> { JSONObject current = findTask(selectedId); if (current != null) archive(current); });
        archiveButton.setBackground(ui.ripple(ui.bg, 12));
        archiveButton.setTextColor(ui.muted);
        column.addView(archiveButton, wrap());
    }
    private void buildDevice() {
        JSONObject computer = findDevice(selectedId); if (computer == null) { placeholder(content(), snapshot == null ? "正在读取电脑" : "未找到这台电脑"); return; }
        LinearLayout column = ui.column(); body.addView(column, new FrameLayout.LayoutParams(-1, -1)); LinearLayout heading = ui.column(); heading.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(12));
        heading.addView(ui.text(computer.optString("name", "电脑"), 21, true), wrap()); heading.addView(ui.space(8)); deviceStatus = ui.text("", 14, false); deviceStatus.setTextColor(ui.muted); heading.addView(deviceStatus, wrap());
        // Keep the device identity in the same scroll surface as its tasks, including the empty state.
        taskAdapter = new NativePages.TaskAdapter(ui, taskCallbacks()); list = NativePages.list(ui, null);
        heading.setPadding(0, ui.dp(10), 0, ui.dp(12));
        list.addHeaderView(heading, null, false);
        attachList(column);
        list.setAdapter(taskAdapter);
        if (!computer.optBoolean("local")) {
            LinearLayout action = ui.column(); action.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8));
            TextView remove = ui.button("移除电脑", false, () -> confirm("移除这台电脑？", "移除后，这台电脑需重新配对\n电脑上的任务不受影响", "移除", () -> mutate("remove:" + computer.optString("id"), "DELETE", "/api/native/computers/" + computer.optString("id"), null, (value, revision) -> { if (samePage(revision)) navigate("devices", ""); })));
            remove.setTextColor(ui.danger); action.addView(remove, wrap()); column.addView(action, wrap()); fixedBottomAction = action;
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
            if (taskDetail != null) {
                if (task == null) { showPage(false); return; }
                JSONObject computer = findDevice(task.optString("device_id"));
                if (taskHeadingTitle != null && !android.text.TextUtils.equals(taskHeadingTitle.getText(), task.optString("title", "未命名任务"))) taskHeadingTitle.setText(task.optString("title", "未命名任务"));
                String computerName = computer == null ? "未知电脑" : computer.optString("name");
                if (taskHeadingTool != null) ui.bindTaskIdentity(taskHeadingTool, task.optString("tool"), computerName);
                taskDetail.update(task, computer, stale(), syncOutput()); if (taskUsage != null) taskUsage.update(task); if (taskResults != null) taskResults.update(task, syncOutput()); archiveButton.setText(task.optBoolean("archived") ? "恢复任务" : "归档任务");
            }
            updateTracking(); return;
        }
        if (page.equals("device")) {
            JSONObject device = findDevice(selectedId);
            if (deviceStatus == null && device != null) { showPage(false); return; }
            if (deviceStatus != null && device != null) {
                String sources = NativeListPresentation.availableSources(device.optJSONArray("sources"));
                String status = device.optString("platform", "电脑") + " · " + (stale() ? "状态未知" : device.optBoolean("online") ? "在线" : "离线");
                String label = sources.isEmpty() ? status : status + "\n任务来源：" + sources;
                if (!android.text.TextUtils.equals(deviceStatus.getText(), label)) deviceStatus.setText(label);
            }
        }
        if (taskPendingEntry != null) {
            int waitingCount = NativeWorkbenchOverview.waitingCount(snapshot, stale());
            if (waitingCount > 0) {
                String pendingLabel = "待处理 " + waitingCount;
                if (!android.text.TextUtils.equals(taskPendingEntry.getText(), pendingLabel)) taskPendingEntry.setText(pendingLabel);
                taskPendingEntry.setContentDescription(pendingLabel + "，查看所有电脑待批准或待回答的任务");
                taskPendingEntry.setVisibility(View.VISIBLE);
            } else {
                taskPendingEntry.setVisibility(View.GONE);
            }
        }
        int uncertainSelectedTasks = 0;
        if (taskAdapter != null) {
            NativeTaskListModel.Result selected = NativeTaskListModel.select(tasks(), devices(), tool, scope,
                    page.equals("device") ? selectedId : null, taskQuery, stale());
            uncertainSelectedTasks = selected.uncertainSelectedTasks;
            taskAdapter.update(selected.tasks, devices(), stale()); taskSourceCounts = selected.toolCounts;
            if (taskSearchSummary != null) {
                boolean searching = NativeTaskSearchPolicy.isSearching(taskQuery);
                taskSearchSummary.setVisibility(searching ? View.VISIBLE : View.GONE);
                if (searching) {
                    String matchCount = snapshot == null ? "等待读取" : "匹配 " + selected.tasks.length() + " 项";
                    taskSearchSummary.setText(matchCount);
                    taskSearchSummary.setContentDescription("搜索条件：" + taskQuery + "，" + matchCount + "，点按展开搜索");
                }
            }
        }
        if (deviceAdapter != null) deviceAdapter.update(devices(), stale());
        if (emptyLabel != null) {
            JSONArray computers = devices(); int online = 0;
            for (int i = 0; i < computers.length(); i++) { JSONObject computer = computers.optJSONObject(i); if (computer != null && computer.optBoolean("online")) online++; }
            NativeTaskListModel.HiddenSummary hidden = (page.equals("tasks") || page.equals("device")) && snapshot != null && taskAdapter != null
                    ? NativeTaskListModel.hiddenSummary(tasks(), computers,
                        page.equals("device") ? "all" : tool,
                        page.equals("device") ? "all" : scope,
                        page.equals("device") ? selectedId : null,
                        page.equals("device") ? "" : taskQuery,
                        stale(), taskAdapter.getCount())
                    : null;
            boolean showHiddenSummary = hidden != null && hidden.hiddenTasks > 0
                    && page.equals("tasks")
                    && !(scope.equals("active") && stale());
            if (showHiddenSummary) {
                emptyLabel.setText(hidden.message);
                if (emptyShowAllButton != null) emptyShowAllButton.setVisibility(View.VISIBLE);
                emptyLabel.setClickable(false);
            } else {
                if (emptyShowAllButton != null) emptyShowAllButton.setVisibility(View.GONE);
                emptyLabel.setText(snapshot == null ? failed ? "暂时无法读取，点此重试" : "正在读取" : page.equals("devices") ? "还没有连接电脑" : page.equals("tasks") && scope.equals("active") ? NativeWorkbenchPolicy.emptyActive(stale(), computers.length(), online, uncertainSelectedTasks) : page.equals("tasks") && scope.equals("archived") ? "暂无归档任务" : "暂无任务");
                boolean searching = page.equals("tasks") && NativeTaskSearchPolicy.isSearching(taskQuery) && snapshot != null;
                if (searching && !(scope.equals("active") && (stale() || uncertainSelectedTasks > 0)))
                    emptyLabel.setText("没有匹配的任务\n试试其他关键词，或调整来源和状态筛选");
                emptyLabel.setClickable(!searching);
            }
            if ((page.equals("tasks") || page.equals("device")) && emptyStateContainer != null) {
                emptyStateContainer.setVisibility(taskAdapter.getCount() == 0 ? View.VISIBLE : View.GONE);
            }
        }
    }
    private void updateConnection() {
        if (connection == null) return;
        boolean coolingDown = SystemClock.elapsedRealtime() < apiCooldownUntil;
        String message = NativeScreenPolicy.connectionMessage(stale(), failed, snapshotBusy, coolingDown);
        boolean show = !loadedToken.isEmpty() && workspacePage() && snapshot != null && !message.isEmpty();
        connection.setVisibility(show ? View.VISIBLE : View.GONE);
        int warningVisibility = show ? View.VISIBLE : View.GONE;
        if (headerMeta.getVisibility() != warningVisibility) {
            headerMeta.setVisibility(warningVisibility);
            updateIntegratedHeader();
        }
        boolean retry = show && !snapshotBusy && !coolingDown;
        titleHeading.setClickable(false); titleHeading.setFocusable(false);
        connection.setClickable(retry); connection.setFocusable(retry); connection.setMinimumHeight(retry ? ui.dp(48) : 0);
        connection.setContentDescription(show ? message : null);
        titleHeading.setContentDescription(title.getText());
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
        LinearLayout column = content();
        // Keep the header and every menu mounted while account data arrives.
        accountCard = ui.row();
        accountCard.setPaddingRelative(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(12));
        accountCard.setMinimumHeight(ui.dp(78));
        accountAvatar = avatar("", 48);
        accountAvatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        accountCard.addView(accountAvatar);
        LinearLayout identity = ui.column();
        identity.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        accountName = ui.text("", 19, true);
        accountName.setMaxLines(2);
        ui.applyControlSpacing(accountName);
        accountName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(accountName, wrap());
        TextView profileLabel = ui.body("个人资料", 13);
        ui.applyControlSpacing(profileLabel);
        LinearLayout.LayoutParams profileParams = wrap();
        profileParams.topMargin = ui.dp(3);
        identity.addView(profileLabel, profileParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(ui.dp(12)); params.setMarginEnd(ui.dp(4));
        accountCard.addView(identity, params);
        FrameLayout action = new FrameLayout(this); action.setMinimumWidth(ui.dp(48)); action.setMinimumHeight(ui.dp(48));
        accountChevron = ui.icon("chevron", 20);
        accountChevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.addView(accountChevron, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        accountRetry = ui.text("重试", 15, true); accountRetry.setGravity(Gravity.CENTER); accountRetry.setMinWidth(ui.dp(48)); accountRetry.setMinHeight(ui.dp(48));
        accountRetry.setPadding(ui.dp(4), 0, ui.dp(4), 0); accountRetry.setBackground(ui.ripple(Color.TRANSPARENT, 12)); accountRetry.setTextColor(ui.accent);
        accountRetry.setContentDescription("重试读取账号"); accountRetry.setAccessibilityDelegate(NativeUi.buttonAccessibility()); accountRetry.setOnClickListener(v -> fetchAccount());
        // INVISIBLE reserves the same measured retry target at every font scale.
        action.addView(accountRetry, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); accountCard.addView(action, new LinearLayout.LayoutParams(-2, -2));
        accountCard.setBackground(ui.ripple(ui.soft, 24));
        accountCard.setOnClickListener(v -> navigate("profile", ""));
        accountCard.setFocusable(true);
        accountCard.setAccessibilityDelegate(NativeUi.buttonAccessibility());
        column.addView(accountCard, wrap());
        updateAccountHeader();
        addAccountSection(column, "设备与同步", menuGroup(
                accountMenu("devices", "我的电脑", () -> navigate("devices", "")),
                accountMenu("cloud", "个人同步", () -> navigate("sync", "")),
                accountMenu("refresh", "同步诊断", () -> navigate("sync-diagnostics", "")),
                accountMenu("shield", "登录设备", () -> navigate("sessions", ""))));
        addAccountSection(column, "应用", menuGroup(
                accountMenu("settings", "设置", () -> navigate("settings", ""))));
        if (!TrackingService.trackedId.isEmpty()) {
            View stop = accountMenu("close", "停止跟踪", () -> {
                TrackingService.requestStop(this, TrackingService.generation);
                savePosition(); showPage(false);
            });
            addAccountSection(column, "实况跟踪", menuGroup(stop));
        }
    }
    private void addAccountSection(LinearLayout column, String label, View group) {
        TextView heading = ui.heading(label, 13);
        heading.setTextColor(ui.muted);
        heading.setPaddingRelative(ui.dp(4), 0, ui.dp(4), 0);
        LinearLayout.LayoutParams section = wrap();
        section.topMargin = ui.dp(16); section.bottomMargin = ui.dp(8);
        column.addView(heading, section);
        column.addView(group, wrap());
    }
    private View accountMenu(String icon, String label, Runnable action) {
        LinearLayout row = ui.row();
        row.setPaddingRelative(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        row.setMinimumHeight(ui.dp(56));
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(ui.rounded(ui.soft, 12));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        badge.addView(ui.icon(icon, 20), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        TextView name = ui.text(label, 16, false);
        ui.applyControlSpacing(name);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(ui.dp(12)); textParams.setMarginEnd(ui.dp(8));
        row.addView(name, textParams);
        View chevron = ui.icon("chevron", 18);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        row.setFocusable(true);
        row.setContentDescription(label);
        row.setAccessibilityDelegate(NativeUi.buttonAccessibility());
        row.setOnClickListener(v -> action.run());
        return row;
    }
    private void updateAccountHeader() {
        if (!page.equals("account") || accountCard == null) return;
        boolean unavailable = account == null, retry = unavailable && accountFailed && !accountBusy;
        String name = unavailable ? (retry ? "暂时无法读取账号" : "正在读取账号") : user().optString("display_name", user().optString("username", "我的账号"));
        accountName.setText(name); accountName.setTextColor(unavailable ? ui.muted : ui.text);
        accountCard.setContentDescription("个人资料，" + name);
        String picture = unavailable ? "" : user().optString("avatar");
        if (!picture.equals(accountAvatar.getTag())) bindAvatar(accountAvatar, picture);
        accountRetry.setEnabled(retry); accountRetry.setFocusable(retry); accountRetry.setVisibility(retry ? View.VISIBLE : View.INVISIBLE);
        accountChevron.setVisibility(retry ? View.INVISIBLE : View.VISIBLE);
    }
    private View menu(String icon, String label, Runnable action) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(16), ui.dp(14), ui.dp(14), ui.dp(14)); row.setMinimumHeight(ui.dp(60)); row.setBackground(ui.ripple(ui.surface, 20)); row.addView(ui.icon(icon, 22));
        TextView text = ui.text(label, 16, false); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(ui.dp(14), 0, ui.dp(10), 0); row.addView(text, params); row.addView(ui.icon("chevron", 20));
        row.setFocusable(true); row.setContentDescription(label); row.setAccessibilityDelegate(NativeUi.buttonAccessibility()); row.setOnClickListener(v -> action.run()); return row;
    }
    private LinearLayout menuGroup(View... rows) {
        LinearLayout group = ui.column(); group.setBackground(ui.rounded(ui.surface, 22)); group.setClipToOutline(true);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View separator = new View(this); separator.setBackgroundColor(ui.line); separator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))); line.leftMargin = ui.dp(52); line.rightMargin = ui.dp(16); group.addView(separator, line);
            }
            rows[i].setBackground(ui.ripple(Color.TRANSPARENT, 0)); group.addView(rows[i], wrap());
        }
        return group;
    }
    private void buildUsage() {
        LinearLayout column = content(); nativeUsage = new NativeUsagePage(ui, this::showHelp, () -> { nextUsage = 0; fetchUsage(); }, () -> UsageAlerts.showSettings(this));
        nativeUsage.restoreState(usagePageState); nativeUsage.update(usageSummary, usageBusy, usageFailed); column.addView(nativeUsage.view, wrap());
    }
    private void buildSettings() {
        LinearLayout column = content();
        switch (page) {
            case "settings-background":
                appearancePage = new NativeAppearancePage(ui, mode -> {
                    if (!visible || !page.equals("settings-background") || isFinishing() || isDestroyed()) return;
                    NativeAppearancePreferences.setMode(this, mode);
                    savePosition(); recreate();
                });
                column.addView(appearancePage.view, wrap());
                if (wallpaperResult != null) appearancePage.show(wallpaperResult);
                break;
            case "settings-appearance":
                settingsSection(column, "应用外观");
                column.addView(menuGroup(menu("image", "应用背景", () -> navigate("settings-background", ""))), wrap());
                column.addView(ui.space(24));
                settingsSection(column, "实况展示");
                column.addView(menuGroup(
                        menu("bell", "显示内容与样式", () -> showSettingsSheet("display")),
                        menu("shield", "锁屏隐私", () -> showSettingsSheet("privacy"))), wrap());
                if (Build.VERSION.SDK_INT >= 36) {
                    column.addView(ui.space(20));
                    column.addView(menuGroup(menu("settings", "系统实况设置", () -> liveSettings.openPromotionSettings())), wrap());
                }
                settingsNote(column, "显示内容与锁屏隐私分别保存\n保留原来的选择");
                break;
            case "settings-notifications":
                settingsSection(column, "通知与运行");
                column.addView(menuGroup(
                        menu("bell", "通知权限与实况", () -> showSettingsSheet("notifications")),
                        menu("refresh", "后台运行", () -> showSettingsSheet("background"))), wrap());
                column.addView(ui.space(20)); settingsSection(column, "用量");
                column.addView(menuGroup(menu("usage", "用量提醒", () -> showSettingsSheet("alerts"))), wrap());
                break;
            case "settings-support":
                settingsSection(column, "连接");
                column.addView(menuGroup(menu("link", "服务器地址", this::showServerSettings)), wrap());
                column.addView(ui.space(20)); settingsSection(column, "应用");
                column.addView(menuGroup(
                        menu("devices", "系统适配与帮助", () -> showSettingsSheet("system-help")),
                        menu("refresh", "应用更新", () -> startActivity(new Intent(this, NativeUpdateActivity.class)))), wrap());
                break;
            case "settings-security":
                settingsSection(column, "当前手机");
                column.addView(menuGroup(menu("close", "退出登录", () -> confirm("退出 Monitor？", "这台手机会断开账号连接，并结束当前实况\n电脑上的任务不受影响", "退出", this::logout))), wrap());
                settingsNote(column, "退出会断开这台手机并结束当前实况\n电脑上的任务不受影响");
                break;
            default:
                column.addView(menuGroup(
                        menu("bell", "显示与隐私", () -> navigate("settings-appearance", "")),
                        menu("usage", "通知与提醒", () -> navigate("settings-notifications", "")),
                        menu("link", "连接与适配", () -> navigate("settings-support", "")),
                        menu("shield", "账号与安全", () -> navigate("settings-security", ""))), wrap());
                break;
        }
    }
    private void settingsNote(LinearLayout column, String value) {
        TextView note = ui.body(value.trim(), 13); note.setTextColor(ui.muted);
        LinearLayout.LayoutParams params = wrap(); params.topMargin = ui.dp(14); params.leftMargin = ui.dp(4); params.rightMargin = ui.dp(4); column.addView(note, params);
    }
    private void refreshWallpaper() {
        if (!"bing".equals(ui.backgroundMode) || !visible || isFinishing() || isDestroyed()) return;
        if (bingWallpaper == null) bingWallpaper = new NativeBingWallpaper(getApplicationContext());
        bingWallpaper.load(result -> {
            if (!visible || isFinishing() || isDestroyed() || !"bing".equals(ui.backgroundMode)) return;
            wallpaperResult = result;
            root.setBackground(new NativeAppBackground(ui.bg, ui.dark, result.bitmap));
            if (bottomBar != null) bottomBar.refreshBackdrop();
            if (appearancePage != null) appearancePage.show(result);
        });
    }
    private void showSettingsSheet(String key) {
        if (!visible || loadedToken.isEmpty() || !page.equals(NativeNavigationPolicy.settingsSheetParent(key))) return;
        currentSettingsSheet = key; settingsSheetDialog = null;
        switch (key) {
            case "display": liveSettings.showPresentation(); break;
            case "privacy": liveSettings.showPrivacySettings(); break;
            case "notifications": liveSettings.showNotificationSettings(); break;
            case "background": liveSettings.showBackgroundHelp(); break;
            case "alerts": settingsSheetDialog = UsageAlerts.showSettings(this); break;
            case "system-help": liveSettings.showSystemHelp(); break;
            default: currentSettingsSheet = ""; break;
        }
    }
    private String showingSettingsSheet() {
        boolean showing = settingsSheetDialog != null && settingsSheetDialog.isShowing() || liveSettings.isShowing();
        return showing && page.equals(NativeNavigationPolicy.settingsSheetParent(currentSettingsSheet)) ? currentSettingsSheet : "";
    }
    private void restoreSettingsSheet() {
        if (pendingSettingsSheet.isEmpty()) return;
        final String key = pendingSettingsSheet; pendingSettingsSheet = "";
        final long revision = pageEpoch;
        handler.post(() -> { if (visible && samePage(revision)) showSettingsSheet(key); });
    }
    private void settingsSection(LinearLayout column, String label) {
        TextView heading = ui.text(label, 12, true); heading.setTextColor(ui.muted);
        heading.setPadding(ui.dp(4), 0, 0, 0); column.addView(heading, wrap()); column.addView(ui.space(10));
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
            if (checked) confirm("同步最终结果？", "开启后，可在已登录设备查看最终结果和下载配套文件", "开启", save); else save.run();
        });
        card.addView(outputSwitch, wrap()); column.addView(card, wrap()); column.addView(ui.space(20));
        TextView state = ui.text("任务状态和已连接电脑会随账号同步", 15, false); state.setTextColor(ui.muted); column.addView(state, wrap());
    }
    private void loadSavedSyncDiagnostics() {
        String fp = tokenFingerprint(loadedToken);
        if (fp.isEmpty()) { syncDiagnosticsSample = NativeSyncDiagnostics.empty(); return; }
        android.content.SharedPreferences prefs = getSharedPreferences("native_sync_diag", MODE_PRIVATE);
        if (!fp.equals(prefs.getString("fingerprint", ""))) { syncDiagnosticsSample = NativeSyncDiagnostics.empty(); return; }
        syncDiagnosticsSample = new NativeSyncDiagnostics.Sample(
                prefs.getLong("first_screen_ms", -1),
                prefs.getLong("last_refresh_ms", -1),
                prefs.getLong("computer_completed_ms", 0),
                prefs.getLong("server_received_ms", 0),
                prefs.getLong("phone_displayed_ms", 0));
    }
    private void recordSyncDiagnostics(JSONObject workbenchSnapshot, long durationMs, boolean wasFirstScreen) {
        syncDiagnosticsSample = NativeSyncDiagnostics.recordWorkbench(
                syncDiagnosticsSample, workbenchSnapshot, durationMs, System.currentTimeMillis(), wasFirstScreen);
        String fp = tokenFingerprint(loadedToken);
        if (!fp.isEmpty()) {
            getSharedPreferences("native_sync_diag", MODE_PRIVATE).edit()
                    .putString("fingerprint", fp)
                    .putLong("first_screen_ms", syncDiagnosticsSample.firstScreenMs)
                    .putLong("last_refresh_ms", syncDiagnosticsSample.lastRefreshMs)
                    .putLong("computer_completed_ms", syncDiagnosticsSample.computerCompletedMs)
                    .putLong("server_received_ms", syncDiagnosticsSample.serverReceivedMs)
                    .putLong("phone_displayed_ms", syncDiagnosticsSample.phoneDisplayedMs)
                    .apply();
        }
        if (page.equals("sync-diagnostics")) renderSyncDiagnostics();
    }
    private void buildSyncDiagnostics() {
        LinearLayout column = content();
        syncDiagnosticsBody = ui.column();
        column.addView(syncDiagnosticsBody, wrap());
        renderSyncDiagnostics();
    }
    private void renderSyncDiagnostics() {
        if (syncDiagnosticsBody == null || !page.equals("sync-diagnostics")) return;
        syncDiagnosticsBody.removeAllViews();
        NativeSyncDiagnostics.Sample sample = syncDiagnosticsSample;
        LinearLayout summaryCard = ui.card();
        summaryCard.addView(ui.heading("首屏与刷新耗时", 16), wrap());
        summaryCard.addView(ui.space(12));
        addDiagnosticRow(summaryCard, "最近一次首屏耗时", NativeSyncDiagnostics.formatDuration(sample.firstScreenMs), true);
        summaryCard.addView(ui.space(10));
        addDiagnosticRow(summaryCard, "最近一次刷新耗时", NativeSyncDiagnostics.formatDuration(sample.lastRefreshMs), false);
        syncDiagnosticsBody.addView(summaryCard, wrap());
        syncDiagnosticsBody.addView(ui.space(14));

        LinearLayout timelineCard = ui.card();
        timelineCard.addView(ui.heading("三段时间点（本机时区）", 16), wrap());
        timelineCard.addView(ui.space(12));
        addDiagnosticRow(timelineCard, "电脑完成", NativeSyncDiagnostics.formatTimestamp(sample.computerCompletedMs, null), false);
        timelineCard.addView(ui.space(10));
        addDiagnosticRow(timelineCard, "服务收到", NativeSyncDiagnostics.formatTimestamp(sample.serverReceivedMs, null), false);
        timelineCard.addView(ui.space(10));
        addDiagnosticRow(timelineCard, "手机显示", NativeSyncDiagnostics.formatTimestamp(sample.phoneDisplayedMs, null), true);
        timelineCard.addView(ui.space(14));
        View divider = new View(this); divider.setBackgroundColor(ui.line);
        timelineCard.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))));
        timelineCard.addView(ui.space(12));
        addDiagnosticRow(timelineCard, "电脑 → 服务", NativeSyncDiagnostics.formatLeg(sample.computerCompletedMs, sample.serverReceivedMs), false);
        timelineCard.addView(ui.space(8));
        addDiagnosticRow(timelineCard, "服务 → 手机", NativeSyncDiagnostics.formatLeg(sample.serverReceivedMs, sample.phoneDisplayedMs), false);
        syncDiagnosticsBody.addView(timelineCard, wrap());
        syncDiagnosticsBody.addView(ui.space(14));

        LinearLayout diagnosisCard = ui.card();
        diagnosisCard.addView(ui.heading("分段定位说明", 16), wrap());
        diagnosisCard.addView(ui.space(8));
        TextView explain = ui.text(NativeSyncDiagnostics.diagnosis(sample), 14, false);
        explain.setTextColor(ui.muted);
        diagnosisCard.addView(explain, wrap());
        syncDiagnosticsBody.addView(diagnosisCard, wrap());
        syncDiagnosticsBody.addView(ui.space(18));

        final TextView probeButton = ui.button(snapshotBusy ? "正在测速同步…" : "立即测速同步一次", false, null);
        probeButton.setEnabled(!snapshotBusy);
        probeButton.setOnClickListener(v -> {
            if (snapshotBusy || loadedToken.isEmpty()) return;
            probeButton.setEnabled(false);
            probeButton.setText("正在测速同步…");
            final long probeStarted = SystemClock.elapsedRealtime();
            final boolean wasFirst = snapshot == null;
            final long requestedData = dataRevision.capture();
            final long requestedUsage = ++usageRequestRevision;
            request("GET", "/api/native/workbench", null, (value, revision) -> {
                if (value != null && dataRevision.accepts(requestedData)) {
                    snapshot = value; failed = false; lastSuccess = SystemClock.elapsedRealtime();
                    MonitorWidgetUpdater.cacheWorkbench(this, value, loadedToken);
                    acceptUsage(value.optJSONObject("usage"), requestedUsage);
                    recordSyncDiagnostics(value, Math.max(0, SystemClock.elapsedRealtime() - probeStarted), wasFirst);
                } else if (samePage(revision)) {
                    notice("同步测速未成功，请稍后重试");
                    renderSyncDiagnostics();
                }
            });
        });
        syncDiagnosticsBody.addView(probeButton, wrap());
    }
    private void addDiagnosticRow(LinearLayout parent, String label, String value, boolean emphasize) {
        LinearLayout row = ui.row();
        TextView keyView = ui.text(label, 14, false);
        keyView.setTextColor(ui.muted);
        row.addView(keyView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView valView = ui.text(value, 14, emphasize);
        valView.setTextColor(emphasize ? ui.accent : ui.text);
        valView.setGravity(Gravity.END);
        LinearLayout.LayoutParams valParams = new LinearLayout.LayoutParams(-2, -2);
        valParams.setMarginStart(ui.dp(12));
        row.addView(valView, valParams);
        parent.addView(row, wrap());
    }
    private void applyOutputPreference(boolean enabled) {
        try {
            if (account != null) { JSONObject pref = account.optJSONObject("preferences"); if (pref == null) { pref = new JSONObject(); account.put("preferences", pref); } pref.put("sync_output", enabled); }
            if (snapshot != null) { JSONObject pref = snapshot.optJSONObject("preferences"); if (pref == null) { pref = new JSONObject(); snapshot.put("preferences", pref); } pref.put("sync_output", enabled); }
            if (!enabled) { JSONArray all = tasks(); for (int i = 0; i < all.length(); i++) { JSONObject task = all.optJSONObject(i); if (task != null) { task.put("output", ""); task.put("preview", ""); task.remove("error_analysis"); } } }
        } catch (Exception ignored) { }
        if (!enabled && taskDetail != null && page.equals("task")) {
            JSONObject task = findTask(selectedId);
            if (task != null) {
                taskDetail.update(task, findDevice(task.optString("device_id")), stale(), false);
                if (taskResults != null) taskResults.update(task, false);
            }
        }
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
            String message = parent || current ? "这项操作也会让当前手机退出登录，并结束实况" : "退出后，该设备需要重新登录";
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
    private void showAccountFailure() {
        if (page.equals("account")) { accountFailed = true; updateAccountHeader(); return; }
        body.removeAllViews(); LinearLayout column = content(); placeholder(column, "暂时无法读取账号"); column.addView(ui.button("重试", false, this::fetchAccount), wrap());
    }

    private void buildPairing() {
        LinearLayout column = content();
        TextView hint = ui.body("在电脑端打开配对二维码", 15);
        column.addView(hint, wrap()); column.addView(ui.space(12));
        column.addView(ui.button("扫码连接", this::scanComputerPairing), wrap());
        column.addView(ui.space(8));

        LinearLayout alternatives = ui.column();
        alternatives.setVisibility(activePairingCode() ? View.VISIBLE : View.GONE);
        TextView otherWays = ui.quietButton("其他方式", null);
        otherWays.setOnClickListener(view -> {
            boolean opening = alternatives.getVisibility() != View.VISIBLE;
            boolean hideEditor = !opening && alternatives.findFocus() instanceof EditText;
            alternatives.setVisibility(opening ? View.VISIBLE : View.GONE);
            if (hideEditor) {
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(alternatives.getWindowToken(), 0);
                otherWays.requestFocus();
            }
            setPairingDisclosure(otherWays, opening);
        });
        setPairingDisclosure(otherWays, alternatives.getVisibility() == View.VISIBLE);
        column.addView(otherWays, wrap()); column.addView(alternatives, wrap());

        EditText link = input("电脑端生成的配对链接", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        link.setSingleLine(true); link.setSaveEnabled(false);
        link.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        link.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        link.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)});
        link.setContentDescription("电脑端生成的配对链接");
        LinearLayout manualLink = ui.column(); manualLink.setVisibility(View.GONE);
        Runnable enterLink = () -> {
            manualLink.setVisibility(View.VISIBLE);
            if (link.requestFocus()) link.post(() -> {
                if (!visible || !link.isShown() || !link.hasFocus()) return;
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(link, InputMethodManager.SHOW_IMPLICIT);
            });
        };
        Runnable checkLink = () -> {
            if (!openComputerPairing(link.getText().toString(), false)) link.setError("请使用当前个人服务生成的电脑配对链接");
        };
        link.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            checkLink.run(); return true;
        });
        alternatives.addView(ui.button("粘贴配对链接并核对", false, () -> {
            if (!openComputerPairing(pasteComputerPairingLink(), false)) {
                enterLink.run(); link.setError("剪贴板中没有当前个人服务的配对链接");
            }
        }), wrap());
        alternatives.addView(ui.quietButton("手动输入链接", enterLink), wrap());
        manualLink.addView(link, wrap()); manualLink.addView(ui.quietButton("核对电脑", checkLink), wrap());
        alternatives.addView(manualLink, wrap());
        alternatives.addView(ui.space(14));
        alternatives.addView(ui.heading("在电脑上输入配对码", 16), wrap());
        alternatives.addView(ui.body("配对码 10 分钟内有效，仅限使用一次", 13), wrap());
        alternatives.addView(ui.space(8));
        pairingCodeArea = ui.column(); alternatives.addView(pairingCodeArea, wrap());
        renderPairingCode();
    }
    private void setPairingDisclosure(TextView toggle, boolean expanded) {
        toggle.setText(expanded ? "其他方式  ▴" : "其他方式  ▾");
        toggle.setContentDescription(Build.VERSION.SDK_INT >= 30 ? "其他方式" : expanded ? "其他方式，已展开" : "其他方式，已收起");
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(expanded ? "已展开" : "已收起");
    }
    private void scanComputerPairing() {
        pickerToken = loadedToken; pickerPage = pageKey(); pickerEpoch = pageEpoch;
        try { startActivityForResult(new Intent(this, NativeQrScanner.class), 23); }
        catch (Exception failure) { notice("暂时无法打开相机"); }
    }
    /** Inspect only an explicitly requested, plain-text clipboard item. */
    private String pasteComputerPairingLink() {
        if (!visible || !page.equals("pairing")) return null;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return null;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() != 1 || !clip.getDescription().hasMimeType("text/plain")) return null;
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            return item.getIntent() == null && item.getUri() == null && text != null && text.length() <= 1024 ? text.toString() : null;
        } catch (RuntimeException unavailable) { return null; }
    }
    private boolean openComputerPairing(String text, boolean scannerResult) {
        // Activity results may arrive before onResume; the scanner callback already checks token, page and epoch.
        if ((!visible && !scannerResult) || isFinishing() || isDestroyed() || (!scannerResult && !page.equals("pairing"))
                || loadedToken.isEmpty() || text == null || text.length() > 1024) return false;
        String requestId = NativeScreenPolicy.pairingId(text);
        if (requestId == null) return false;
        View focused = getCurrentFocus();
        if (focused instanceof EditText) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        navigate("pairing-confirm", requestId);
        return true;
    }
    private boolean activePairingCode() {
        return !pairingCode.isEmpty() && NativeApi.time(pairingExpires) > System.currentTimeMillis();
    }
    private void updatePairingCodeExpiry() {
        if (pairingCode.isEmpty() || activePairingCode()) return;
        pairingCode = ""; pairingExpires = "";
        if (pairingCodeArea != null) renderPairingCode();
    }
    private void renderPairingCode() {
        if (pairingCodeArea == null || !page.equals("pairing")) return;
        pairingCodeArea.removeAllViews();
        boolean active = activePairingCode();
        if (active) {
            LinearLayout card = ui.card(); TextView code = ui.heading(pairingCode, 22);
            code.setLetterSpacing(.07f); code.setGravity(Gravity.CENTER); card.addView(code, wrap());
            card.addView(ui.quietButton("复制配对码", this::copyComputerPairingCode), wrap());
            pairingCodeArea.addView(card, wrap()); pairingCodeArea.addView(ui.space(6));
        }
        pairingCodeArea.addView(ui.button(active ? "重新生成配对码" : "生成配对码", false, () ->
                mutate("pairing-code", "POST", "/api/native/computers/pairing", null, (value, revision) -> {
                    String code = value.optString("code"), expires = value.optString("expires_at");
                    if (!code.matches("[A-F0-9]{12}") || NativeApi.time(expires) <= System.currentTimeMillis()) {
                        if (visible && samePage(revision)) notice("返回的配对码无效，请重试");
                        return;
                    }
                    pairingCode = code; pairingExpires = expires;
                    if (pairingCodeArea != null) renderPairingCode();
                })), wrap());
    }
    private void copyComputerPairingCode() {
        if (!visible || !page.equals("pairing")) return;
        if (!activePairingCode()) { updatePairingCodeExpiry(); notice("配对码已过期，请重新生成"); return; }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("Monitor 配对码", pairingCode);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        try { clipboard.setPrimaryClip(clip); notice("配对码已复制"); }
        catch (RuntimeException unavailable) { notice("暂时无法复制配对码"); }
    }
    private void buildPairingConfirm() {
        LinearLayout column = content(); if (!NativeApi.validId(selectedId)) { placeholder(column, "配对链接无效"); return; }
        placeholder(column, "正在读取电脑信息"); String requestId = selectedId;
        request("GET", "/api/native/computers/pairing/" + requestId, null, (result, revision) -> {
            if (!samePage(revision)) return; column.removeAllViews();
            if (result == null || !requestId.equals(result.optString("request_id"))) {
                placeholder(column, "暂时无法读取配对请求。若二维码已过期，请在电脑端重新生成。");
                column.addView(ui.button("重试", false, () -> showPage(false)), wrap()); return;
            }
            String status = result.optString("status"), name = result.optString("name", "新电脑"), platform = result.optString("platform", "电脑"), expires = result.optString("expires_at");
            boolean includeReader = result.optBoolean("include_reader");
            boolean includeCollector = !result.has("include_collector") || result.optBoolean("include_collector");
            if (title != null) {
                title.setText(includeReader && includeCollector ? "确认登录并连接" : includeReader ? "确认桌面端登录" : "确认电脑");
            }
            LinearLayout card = ui.card(); card.addView(ui.icon("monitor", 28)); card.addView(ui.space(10));
            card.addView(ui.heading(name, 20), wrap()); card.addView(ui.body(platform, 15), wrap());
            column.addView(card, wrap()); column.addView(ui.space(14));
            if (!status.equals("pending") || NativeApi.time(expires) <= System.currentTimeMillis()) {
                if (status.equals("approved") || status.equals("consumed")) {
                    placeholder(column, status.equals("approved") ? "已确认，电脑端将自动完成登录或连接" : "这台电脑已完成连接");
                    column.addView(ui.button("查看电脑", false, () -> navigate("devices", "")), wrap());
                } else {
                    placeholder(column, status.equals("denied") ? "已拒绝此配对请求" : "配对已过期，请在电脑端重新发起");
                    column.addView(ui.button("返回添加电脑", false, () -> navigate("pairing", "")), wrap());
                }
                return;
            }
            String explain = includeReader && includeCollector
                    ? "确认后，这台电脑将同时登录 Monitor 桌面端完整工作台，并连接本机任务采集器。仅连接你认得的电脑。"
                    : includeReader
                    ? "确认后，这台电脑的 Monitor 桌面端将登录完整工作台，可查看任务、用量并管理设置。"
                    : "仅连接你认得的电脑。确认后，这台电脑可以向个人服务同步任务。";
            String approveLabel = includeReader && includeCollector ? "确认登录并连接" : includeReader ? "确认登录桌面端" : "连接这台电脑";
            column.addView(ui.body(explain, 14), wrap());
            column.addView(ui.space(12));
            TextView approve = ui.button(approveLabel, null), reject = ui.quietButton("拒绝", null);
            approve.setOnClickListener(v -> decideComputerPairing(requestId, expires, true, approveLabel, approve, reject));
            reject.setOnClickListener(v -> decideComputerPairing(requestId, expires, false, approveLabel, approve, reject));
            boolean ready = !mutations.contains("pairing-decision:" + requestId);
            approve.setEnabled(ready); reject.setEnabled(ready);
            column.addView(approve, wrap()); column.addView(reject, wrap());
        });
    }
    private void decideComputerPairing(String requestId, String expires, boolean approved, String approveLabel, TextView approve, TextView reject) {
        if (!visible || !page.equals("pairing-confirm") || !requestId.equals(selectedId) || loadedToken.isEmpty()) return;
        if (NativeApi.time(expires) <= System.currentTimeMillis()) { notice("配对已过期，请在电脑端重新发起"); return; }
        String key = "pairing-decision:" + requestId;
        if (!mutations.add(key)) return;
        approve.setEnabled(false); reject.setEnabled(false);
        (approved ? approve : reject).setText(approved ? "正在确认…" : "正在拒绝…");
        boolean handled = request("POST", "/api/native/computers/pairing/" + requestId + (approved ? "/approve" : "/reject"), null, (result, revision) -> {
            mutations.remove(key);
            if (!samePage(revision)) {
                if (visible && page.equals("pairing-confirm") && requestId.equals(selectedId)) showPage(false);
                return;
            }
            if (result != null && result.optBoolean("ok") && result.optString("status").equals(approved ? "approved" : "denied")) {
                if (approved) { nextSnapshot = 0; if (visible) notice("已确认，电脑端将自动完成登录或连接"); }
                navigate(approved ? "devices" : "pairing", "");
                return;
            }
            resetPairingDecision(approve, reject, approveLabel);
        });
        if (!handled) {
            mutations.remove(key);
            resetPairingDecision(approve, reject, approveLabel);
        }
    }
    private void resetPairingDecision(TextView approve, TextView reject, String approveLabel) {
        approve.setText(approveLabel); reject.setText("拒绝");
        approve.setEnabled(true); reject.setEnabled(true);
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
        if (trackingNote != null) {
            if (!android.text.TextUtils.equals(trackingNote.getText(), detail)) trackingNote.setText(detail);
            trackingNote.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        }
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
        confirmation.note("这会退出本机旧登录、停止跟踪和用量提醒，并关闭应用\n重新打开后可设置服务器地址\n\n原服务器上的连接不会被远程撤销，请到原服务的设备管理中移除旧设备");
        confirmation.action("清除并关闭应用", this::clearLegacyConnection);
        confirmation.action("取消", () -> { });
        confirmation.show();
    }
    private void clearLegacyConnection() {
        // This path is exclusively for credentials whose original server is unknown.
        // Never enqueue revocation or associate those credentials with the next server.
        if (!ServerSettings.legacyConnection(this)) { updateLogin(); return; }
        try { SessionStore.write(this, new JSONObject()); }
        catch (Exception failure) { notice("未能清除旧连接，请重试"); return; }
        visible = false; handler.removeCallbacks(heartbeat); handler.removeCallbacks(refreshWorkspace);
        TrackingService.requestStop(this, TrackingService.generation); UsageAlerts.sync(this); MonitorWidgetUpdater.refreshAll(this);
        sessionEpoch++; pageEpoch++; actionRevision++; loadedToken = ""; invalidToken = "";
        snapshot = null; account = null; usageSummary = null; profileDraft = false; taskQuery = "";
        syncDiagnosticsSample = NativeSyncDiagnostics.empty(); firstWorkbenchStartedElapsed = 0; focusPendingOnOpen = false;
        draftName = ""; draftAvatar = ""; pendingAvatarUri = ""; pairingCode = ""; pairingExpires = "";
        mutations.clear(); listPositions.clear(); scrollPositions.clear(); workbenchHeaderScroll = 0; clearNotificationRequest();
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
                else if (visible) notice("本机已退出，连接尚未在服务端撤销\n联网后请点「完成退出」");
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
        if (requestCode == 23) { if (!openComputerPairing(data.getStringExtra(NativeQrScanner.EXTRA_TEXT), true)) notice("这个二维码不是当前个人服务的电脑配对链接"); return; }
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
        if (intent != null && MonitorWidgetUpdater.ACTION_OPEN_USAGE.equals(intent.getAction())) {
            setIntent(new Intent(this, MainActivity.class)); page = "usage"; selectedId = ""; taskReturnDevice = ""; pageEpoch++; nextUsage = 0; return true;
        }
        if (intent != null && MonitorWidgetUpdater.ACTION_OPEN_TASKS.equals(intent.getAction())) {
            setIntent(new Intent(this, MainActivity.class)); page = "tasks"; selectedId = ""; taskReturnDevice = ""; pageEpoch++; nextSnapshot = 0; return true;
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
        if (page.equals("tasks") && taskSearchExpanded) { setTaskSearchExpanded(false, false); return; }
        NativeNavigationPolicy.BackTarget target = NativeNavigationPolicy.backTarget(page, taskReturnDevice);
        if (target == null) finish(); else navigate(target.page, target.id);
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); savePosition(); captureDraft(); dismissTaskFilterSheet(); dismissTaskPendingSheet(); dismissHelp(); setIntent(intent); if (openNotification(intent)) { cancelDuration(); clearNotificationRequest(); showPage(false); loadCurrentPage(); } else handleReturn(intent); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        if (taskResults != null) saved.putBundle("task_results", taskResults.saveState());
        if (nativeUsage != null) usagePageState = nativeUsage.saveState();
        if (!loadedToken.isEmpty() && usagePageState != null) saved.putBundle("usage_page_state", usagePageState);
        String settingsSheet = showingSettingsSheet();
        saved.putString("settings_sheet", settingsSheet.isEmpty() ? pendingSettingsSheet : settingsSheet);
        captureDraft(); saved.putString("page", page); saved.putString("id", selectedId); saved.putString("tool", tool); saved.putString("scope", scope);
        saved.putString("task_query", loadedToken.isEmpty() ? "" : NativeTaskSearchPolicy.boundedQuery(taskQuery));
        saved.putBoolean("task_search_expanded", !loadedToken.isEmpty() && taskSearchExpanded);
        boolean editingSearch = !loadedToken.isEmpty() && page.equals("tasks") && taskSearchExpanded
                && taskSearchInput != null && taskSearchInput.hasFocus();
        saved.putBoolean("task_search_focused", editingSearch);
        if (editingSearch) {
            saved.putInt("task_search_selection_start", taskSearchInput.getSelectionStart());
            saved.putInt("task_search_selection_end", taskSearchInput.getSelectionEnd());
        }
        if (profileDraft) { saved.putBoolean("profile_draft", true); saved.putString("draft_name", draftName); saved.putString("draft_avatar", draftAvatar); }
        if (list != null) saved.putParcelable("list_position", list.onSaveInstanceState());
        if (page.equals("tasks")) saved.putInt("workbench_header_scroll", currentWorkbenchHeaderScroll());
        if (scroll != null) saved.putInt("scroll_y", scroll.getScrollY());
        saved.putLong("page_epoch", pageEpoch); saved.putString("picker_fingerprint", tokenFingerprint(pickerToken)); saved.putString("picker_page", pickerPage); saved.putLong("picker_epoch", pickerEpoch);
        saved.putString("task_return_device", taskReturnDevice); saved.putString("avatar_uri", pendingAvatarUri); saved.putString("state_fingerprint", tokenFingerprint(loadedToken)); super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; reconcileConnection(); updateLogin(); resumeNotificationRequest(); handler.removeCallbacks(heartbeat); loadCurrentPage(); handler.post(heartbeat);
        refreshWallpaper();
        UsageAlerts.sync(this);
        if (!pendingAvatarUri.isEmpty() && !avatarBusy && !loadedToken.isEmpty() && page.equals("profile")) readAvatar(Uri.parse(pendingAvatarUri));
        restoreTaskSearchFocus(); restoreSettingsSheet();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && systemBars != null) updateHeaderSurface();
        if (hasFocus) restoreTaskSearchFocus();
    }
    @Override protected void onPause() { pendingSettingsSheet = showingSettingsSheet(); visible = false; dismissTaskFilterSheet(); dismissTaskPendingSheet(); if (bingWallpaper != null) bingWallpaper.cancel(); if (taskResults != null) taskResults.pause(); captureDraft(); savePosition(); dismissHelp(); dismissServerSettings(); UsageAlerts.dismissSettings(this); cancelDuration(); liveSettings.dismiss(); handler.removeCallbacks(heartbeat); handler.removeCallbacks(refreshWorkspace); super.onPause(); }
    @Override protected void onDestroy() {
        if (bingWallpaper != null) bingWallpaper.close();
        visible = false; dismissTaskFilterSheet(); dismissTaskPendingSheet(); cancelSearchAnimation(); if (taskResults != null) { taskResults.dispose(); taskResults = null; } sessionEpoch++; pageEpoch++; actionRevision++; dismissHelp(); dismissServerSettings(); UsageAlerts.dismissSettings(this); cancelDuration(); liveSettings.dismiss(); clearNotificationRequest(); handler.removeCallbacksAndMessages(null); workbenchReader.shutdown(); worker.shutdown();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback); super.onDestroy();
    }
    private static String tokenFingerprint(String token) {
        if (token == null || token.isEmpty()) return "";
        try { byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP); } catch (Exception failure) { return ""; }
    }
}
