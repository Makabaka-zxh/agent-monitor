package com.agentmonitor.live;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Account-scoped cache, network refresh coordinator, and {@link RemoteViews} renderer
 * for Monitor 2×2, 2×4, and 4×4 home screen widgets.
 */
public final class MonitorWidgetUpdater {
    public static final String ACTION_WIDGET_REFRESH = "com.agentmonitor.live.ACTION_WIDGET_REFRESH";
    public static final String ACTION_OPEN_TASKS = "com.agentmonitor.live.OPEN_TASKS";
    public static final String ACTION_OPEN_USAGE = "com.agentmonitor.live.OPEN_USAGE";

    private static final String CACHE_PREFS = "monitor_widget_cache";
    private static final String MODE_PREFS = "monitor_widget_modes";
    private static final long NETWORK_MIN_INTERVAL_MS = 20_000L;
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);

    private MonitorWidgetUpdater() { }

    private static SharedPreferences cachePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences modePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE);
    }

    public static String getWidgetMode(Context context, int appWidgetId) {
        return MonitorWidgetPolicy.sanitizeMode(modePrefs(context).getString("mode_" + appWidgetId, MonitorWidgetPolicy.MODE_AUTO));
    }

    public static void setWidgetMode(Context context, int appWidgetId, String mode) {
        modePrefs(context).edit().putString("mode_" + appWidgetId, MonitorWidgetPolicy.sanitizeMode(mode)).apply();
    }

    public static void deleteWidgetModes(Context context, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) return;
        SharedPreferences.Editor editor = modePrefs(context).edit();
        for (int id : appWidgetIds) editor.remove("mode_" + id);
        editor.apply();
    }

    private static String connectedToken(Context context) {
        return UsageAlerts.connectedToken(context.getApplicationContext());
    }

    private static String sha256(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            return "";
        }
    }

    private static synchronized void reconcileAccountCache(Context context, String currentAccount) {
        SharedPreferences cache = cachePrefs(context);
        String savedAccount = cache.getString("account", "");
        if (!currentAccount.equals(savedAccount)) {
            cache.edit()
                    .putString("account", currentAccount)
                    .remove("workbench")
                    .remove("usage")
                    .remove("updated_at")
                    .apply();
        }
    }

    public static synchronized void cacheWorkbench(Context context, JSONObject workbench, String expectedToken) {
        if (context == null || workbench == null || expectedToken == null || expectedToken.isEmpty()) return;
        Context app = context.getApplicationContext();
        String activeToken = connectedToken(app);
        if (activeToken.isEmpty() || !activeToken.equals(expectedToken)) return;
        String account = sha256(activeToken);
        reconcileAccountCache(app, account);
        cachePrefs(app).edit()
                .putString("account", account)
                .putString("workbench", workbench.toString())
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
        refreshAll(app);
    }

    public static synchronized void cacheUsage(Context context, JSONObject usage, String expectedToken) {
        if (context == null || usage == null || expectedToken == null || expectedToken.isEmpty()) return;
        Context app = context.getApplicationContext();
        String activeToken = connectedToken(app);
        if (activeToken.isEmpty() || !activeToken.equals(expectedToken)) return;
        String account = sha256(activeToken);
        reconcileAccountCache(app, account);
        cachePrefs(app).edit()
                .putString("account", account)
                .putString("usage", usage.toString())
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
        refreshAll(app);
    }

    public static boolean hasAnyInstances(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return false;
            return countIds(manager, context, MonitorWidgetSmallProvider.class) > 0
                    || countIds(manager, context, MonitorWidgetMediumProvider.class) > 0
                    || countIds(manager, context, MonitorWidgetLargeProvider.class) > 0;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static int countIds(AppWidgetManager manager, Context context, Class<?> providerClass) {
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, providerClass));
        return ids == null ? 0 : ids.length;
    }

    public static MonitorWidgetPolicy.Model previewModel(Context context, String mode) {
        Context app = context.getApplicationContext();
        String token = connectedToken(app);
        boolean connected = !token.isEmpty() && ServerSettings.configured(app);
        reconcileAccountCache(app, connected ? sha256(token) : "");
        SharedPreferences cache = cachePrefs(app);
        JSONObject workbench = parseJson(cache.getString("workbench", null));
        JSONObject usage = parseJson(cache.getString("usage", null));
        return MonitorWidgetPolicy.evaluate(
                mode,
                connected,
                workbench,
                usage,
                MonitorWidgetBridge.current(),
                System.currentTimeMillis());
    }

    public static void refreshAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        AppWidgetManager manager;
        try {
            manager = AppWidgetManager.getInstance(app);
        } catch (RuntimeException unavailable) {
            return;
        }
        if (manager == null) return;

        int[] smallIds = manager.getAppWidgetIds(new ComponentName(app, MonitorWidgetSmallProvider.class));
        int[] mediumIds = manager.getAppWidgetIds(new ComponentName(app, MonitorWidgetMediumProvider.class));
        int[] largeIds = manager.getAppWidgetIds(new ComponentName(app, MonitorWidgetLargeProvider.class));
        if ((smallIds == null || smallIds.length == 0)
                && (mediumIds == null || mediumIds.length == 0)
                && (largeIds == null || largeIds.length == 0)) {
            return;
        }

        String token = connectedToken(app);
        boolean connected = !token.isEmpty() && ServerSettings.configured(app);
        reconcileAccountCache(app, connected ? sha256(token) : "");

        SharedPreferences cache = cachePrefs(app);
        JSONObject workbench = connected ? parseJson(cache.getString("workbench", null)) : null;
        JSONObject usage = connected ? parseJson(cache.getString("usage", null)) : null;
        MonitorWidgetBridge.TrackingSnapshot tracking = MonitorWidgetBridge.current();
        long now = System.currentTimeMillis();

        if (smallIds != null) {
            for (int id : smallIds) {
                MonitorWidgetPolicy.Model model = MonitorWidgetPolicy.evaluate(
                        getWidgetMode(app, id), connected, workbench, usage, tracking, now);
                manager.updateAppWidget(id, buildSmallViews(app, id, model));
            }
        }
        if (mediumIds != null) {
            for (int id : mediumIds) {
                MonitorWidgetPolicy.Model model = MonitorWidgetPolicy.evaluate(
                        getWidgetMode(app, id), connected, workbench, usage, tracking, now);
                manager.updateAppWidget(id, buildMediumViews(app, id, model));
            }
        }
        if (largeIds != null) {
            for (int id : largeIds) {
                MonitorWidgetPolicy.Model model = MonitorWidgetPolicy.evaluate(
                        getWidgetMode(app, id), connected, workbench, usage, tracking, now);
                manager.updateAppWidget(id, buildLargeViews(app, id, model));
            }
        }
    }

    public static void refreshFromNetworkAsync(
            Context context,
            boolean manualTrigger,
            BroadcastReceiver.PendingResult pendingResult) {
        if (context == null) {
            if (pendingResult != null) pendingResult.finish();
            return;
        }
        final Context app = context.getApplicationContext();
        // Paint cached state immediately so widgets never appear blank while network reads run.
        refreshAll(app);

        final String token = connectedToken(app);
        if (token.isEmpty() || !ServerSettings.configured(app) || !hasAnyInstances(app)) {
            if (pendingResult != null) pendingResult.finish();
            return;
        }

        long lastUpdated = cachePrefs(app).getLong("updated_at", 0L);
        long age = System.currentTimeMillis() - lastUpdated;
        if (!manualTrigger && lastUpdated > 0L && age >= 0L && age < NETWORK_MIN_INTERVAL_MS) {
            if (pendingResult != null) pendingResult.finish();
            return;
        }

        if (!REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            if (pendingResult != null) pendingResult.finish();
            return;
        }

        REFRESH_EXECUTOR.execute(() -> {
            try {
                ServerSettings.initialize(app);
                JSONObject workbench = NativeApi.call("GET", "/api/native/workbench", null, token);
                if (workbench != null && token.equals(connectedToken(app))) {
                    cacheWorkbench(app, workbench, token);
                }
            } catch (NativeApi.Failure ignored) {
                // Keep showing last verified cache on transient network errors.
            } catch (RuntimeException ignored) {
            } finally {
                REFRESH_IN_FLIGHT.set(false);
                if (pendingResult != null) {
                    try { pendingResult.finish(); } catch (RuntimeException ignored) { }
                }
            }
        });
    }

    private static RemoteViews buildSmallViews(Context context, int appWidgetId, MonitorWidgetPolicy.Model model) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_small);
        views.setImageViewResource(R.id.widget_brand_icon, brandDrawable(model.brandTool));
        views.setTextViewText(R.id.widget_badge, model.smallBadge);
        views.setTextColor(R.id.widget_badge, toneColor(context, model.badgeTone));

        views.setTextViewText(R.id.widget_primary_value, model.primaryValue);
        views.setTextViewTextSize(
                R.id.widget_primary_value,
                TypedValue.COMPLEX_UNIT_SP,
                model.primaryCompactTitle ? 15.5f : 20.5f);
        views.setTextColor(
                R.id.widget_primary_value,
                context.getColor(model.valueWarning ? R.color.widget_warning : R.color.widget_text));

        views.setTextViewText(R.id.widget_title, model.title);
        views.setTextViewText(R.id.widget_footer, model.footer);

        views.setOnClickPendingIntent(R.id.widget_root, contentIntent(context, appWidgetId, model));
        views.setOnClickPendingIntent(R.id.widget_config_btn, configIntent(context, appWidgetId));
        return views;
    }

    private static RemoteViews buildMediumViews(Context context, int appWidgetId, MonitorWidgetPolicy.Model model) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_medium);
        views.setImageViewResource(R.id.widget_brand_icon, brandDrawable(model.brandTool));
        views.setTextViewText(R.id.widget_badge, model.wideBadge);
        views.setTextColor(R.id.widget_badge, toneColor(context, model.badgeTone));

        views.setTextViewText(R.id.widget_primary_value, model.primaryValue);
        views.setTextViewTextSize(
                R.id.widget_primary_value,
                TypedValue.COMPLEX_UNIT_SP,
                model.primaryCompactTitle ? 16.5f : 22f);
        views.setTextColor(
                R.id.widget_primary_value,
                context.getColor(model.valueWarning ? R.color.widget_warning : R.color.widget_text));

        views.setTextViewText(R.id.widget_title, model.title);
        views.setViewVisibility(R.id.widget_progress, model.showProgress ? View.VISIBLE : View.GONE);
        if (model.showProgress) {
            views.setProgressBar(R.id.widget_progress, 100, model.progress, false);
        }

        views.setTextViewText(R.id.widget_side_label, model.sideLabel);
        views.setTextViewText(R.id.widget_side_value, model.sideValue);
        views.setTextViewText(R.id.widget_footer, model.footer);

        views.setOnClickPendingIntent(R.id.widget_root, contentIntent(context, appWidgetId, model));
        views.setOnClickPendingIntent(
                R.id.widget_refresh_btn,
                refreshIntent(context, appWidgetId, MonitorWidgetMediumProvider.class));
        views.setOnClickPendingIntent(R.id.widget_config_btn, configIntent(context, appWidgetId));
        return views;
    }

    private static RemoteViews buildLargeViews(Context context, int appWidgetId, MonitorWidgetPolicy.Model model) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_large);
        views.setImageViewResource(R.id.widget_brand_icon, brandDrawable(model.brandTool));
        views.setTextViewText(R.id.widget_badge, model.wideBadge);
        views.setTextColor(R.id.widget_badge, toneColor(context, model.badgeTone));

        views.setTextViewText(R.id.widget_primary_value, model.primaryValue);
        views.setTextViewTextSize(
                R.id.widget_primary_value,
                TypedValue.COMPLEX_UNIT_SP,
                model.primaryCompactTitle ? 17.5f : 23f);
        views.setTextColor(
                R.id.widget_primary_value,
                context.getColor(model.valueWarning ? R.color.widget_warning : R.color.widget_text));

        views.setTextViewText(R.id.widget_title, model.title);
        views.setViewVisibility(R.id.widget_progress, model.showProgress ? View.VISIBLE : View.GONE);
        if (model.showProgress) {
            views.setProgressBar(R.id.widget_progress, 100, model.progress, false);
        }
        views.setTextViewText(R.id.widget_footer, model.footer);

        views.setTextViewText(R.id.widget_section_heading, model.sectionHeading);
        bindOptionalRow(views, R.id.widget_row_1, model.row1);
        bindOptionalRow(views, R.id.widget_row_2, model.row2);
        bindOptionalRow(views, R.id.widget_row_3, model.row3);

        views.setOnClickPendingIntent(R.id.widget_root, contentIntent(context, appWidgetId, model));
        views.setOnClickPendingIntent(
                R.id.widget_refresh_btn,
                refreshIntent(context, appWidgetId, MonitorWidgetLargeProvider.class));
        views.setOnClickPendingIntent(R.id.widget_config_btn, configIntent(context, appWidgetId));
        return views;
    }

    private static void bindOptionalRow(RemoteViews views, int viewId, String text) {
        boolean visible = text != null && !text.trim().isEmpty();
        views.setViewVisibility(viewId, visible ? View.VISIBLE : View.GONE);
        if (visible) views.setTextViewText(viewId, text);
    }

    private static int brandDrawable(String tool) {
        if ("codex".equals(tool)) return R.drawable.codex;
        if ("claude".equals(tool)) return R.drawable.claude;
        if ("antigravity".equals(tool)) return R.drawable.antigravity;
        return R.drawable.app_icon;
    }

    private static int toneColor(Context context, int tone) {
        switch (tone) {
            case MonitorWidgetPolicy.TONE_CLAUDE: return context.getColor(R.color.widget_claude);
            case MonitorWidgetPolicy.TONE_RUNNING: return context.getColor(R.color.widget_running);
            case MonitorWidgetPolicy.TONE_WARNING: return context.getColor(R.color.widget_warning);
            default: return context.getColor(R.color.widget_accent);
        }
    }

    private static PendingIntent contentIntent(Context context, int appWidgetId, MonitorWidgetPolicy.Model model) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (MonitorWidgetPolicy.TARGET_TASK.equals(model.clickTarget)) {
            String uri = NativeNavigationPolicy.notificationUri(model.clickTaskId, Math.max(0L, model.clickGeneration));
            if (uri != null) {
                intent.setAction(NativeNavigationPolicy.NOTIFICATION_ACTION).setData(Uri.parse(uri));
            } else {
                intent.setAction(ACTION_OPEN_TASKS)
                        .setData(Uri.parse("agentmonitor://widget-open/tasks/" + appWidgetId));
            }
        } else if (MonitorWidgetPolicy.TARGET_USAGE.equals(model.clickTarget)) {
            intent.setAction(ACTION_OPEN_USAGE)
                    .setData(Uri.parse("agentmonitor://widget-open/usage/" + appWidgetId));
        } else if (MonitorWidgetPolicy.TARGET_TASKS.equals(model.clickTarget)) {
            intent.setAction(ACTION_OPEN_TASKS)
                    .setData(Uri.parse("agentmonitor://widget-open/tasks/" + appWidgetId));
        } else {
            intent.setData(Uri.parse("agentmonitor://widget-open/home/" + appWidgetId));
        }
        return PendingIntent.getActivity(
                context,
                100000 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent configIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, MonitorWidgetConfigActivity.class)
                .setData(Uri.parse("agentmonitor://widget-config/" + appWidgetId))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                200000 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refreshIntent(Context context, int appWidgetId, Class<?> providerClass) {
        Intent intent = new Intent(context, providerClass)
                .setAction(ACTION_WIDGET_REFRESH)
                .setData(Uri.parse("agentmonitor://widget-refresh/" + appWidgetId))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        return PendingIntent.getBroadcast(
                context,
                300000 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static JSONObject parseJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception invalid) { return null; }
    }
}
