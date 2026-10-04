package com.agentmonitor.live;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Opt-in quota notices. No permanent service or precise-alarm permission. */
public final class UsageAlerts {
    static final int JOB_ID = 47018;
    static final String CHANNEL = "monitor_usage_v1";
    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static Dialog settings;
    private UsageAlerts() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("usage_alerts", Context.MODE_PRIVATE); }
    static String connectedToken(Context c) {
        JSONObject s = SessionStore.read(c);
        return NativeScreenPolicy.connected(s.optString("mode"), s.optBoolean("logout_pending"),
                s.optBoolean("native_ready") || s.optBoolean("cookie_ready"), s.optString("reader_token"),
                NativeApi.time(s.optString("expires_at")), System.currentTimeMillis()) ? s.optString("reader_token") : "";
    }
    private static boolean permitted(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if ((Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false) && !connectedToken(c).isEmpty() && permitted(c); }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte v : bytes) b.append(String.format(Locale.ROOT, "%02x", v & 255));
            return b.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public static synchronized void sync(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        String token = connectedToken(c), account = token.isEmpty() ? "" : hash(token);
        SharedPreferences p = prefs(c);
        if (!account.equals(p.getString("account", ""))) {
            // Never carry notice identities or delivered notification content to another login.
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications())
                if (n.getTag() != null && n.getTag().startsWith("usage:")) nm.cancel(n.getTag(), n.getId());
            p.edit().putString("account", account).remove("sent").apply();
        }
        if (!enabled(c)) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(c, UsageAlertJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L).build());
    }
    public static synchronized void observe(Context context, JSONObject usage, String expectedToken) {
        Context c = context.getApplicationContext();
        if (usage == null || expectedToken == null || expectedToken.isEmpty()
                || !expectedToken.equals(connectedToken(c)) || !enabled(c)) return;
        SharedPreferences p = prefs(c);
        String account = hash(expectedToken);
        if (!account.equals(p.getString("account", ""))) { sync(c); }
        JSONObject sent;
        try { sent = new JSONObject(p.getString("sent", "{}")); } catch (Exception invalid) { sent = new JSONObject(); }
        long now = System.currentTimeMillis();
        List<String> remove = new ArrayList<>();
        // Values are the source window's reset time, not the delivery time: a long quota
        // window must not re-alert just because a fixed local retention interval elapsed.
        Iterator<String> it = sent.keys(); while (it.hasNext()) { String key = it.next(); if (sent.optLong(key) < now - 86400000L) remove.add(key); }
        for (String key : remove) sent.remove(key);
        JSONArray providers = usage.optJSONArray("providers");
        int delivered = 0;
        if (providers == null) return;
        for (int i = 0; i < Math.min(4, providers.length()); i++) {
            JSONObject provider = providers.optJSONObject(i); if (provider == null) continue;
            String tool = provider.optString("tool"); if (!"claude".equals(tool) && !"codex".equals(tool)) continue;
            JSONArray quotas = provider.optJSONArray("quotas"); if (quotas == null) continue;
            for (int j = 0; j < Math.min(20, quotas.length()); j++) {
                JSONObject q = quotas.optJSONObject(j); if (q == null) continue;
                Object amount = q.opt("remaining_percent");
                double remaining = amount instanceof Number ? ((Number) amount).doubleValue() : Double.NaN;
                long resets = positiveWhole(q.opt("resets_at")), windowMinutes = positiveWhole(q.opt("window_minutes"));
                long observed = NativeApi.time(q.optString("observed_at"));
                String source = q.optString("key"); if (source.isEmpty() || source.length() > 256) continue;
                String identity = hash(UsageAlertPolicy.windowIdentity(account, tool, source, q.optString("source_device_id"), resets));
                int pending = UsageAlertPolicy.eligibleKinds(remaining, observed, resets, windowMinutes, q.optBoolean("stale", true),
                        p.getInt("low", 10), p.getInt("reset", 15), now,
                        sent.has(identity + ":low"), sent.has(identity + ":reset"));
                boolean low = (pending & 1) != 0, reset = (pending & 2) != 0;
                if ((!low && !reset) || delivered >= 4) continue;
                String text = low ? "额度剩余 " + LivePresentation.percent(remaining) : "";
                if (reset) text += (text.isEmpty() ? "" : " · ") + "预计 " + Math.max(1, (resets - now / 1000 + 59) / 60) + " 分钟后重置";
                String label = LivePresentation.clean(q.optString("label", ""), 60);
                String device = LivePresentation.clean(q.optString("source_name", ""), 60);
                String detail = (device + " " + label).trim();
                if (reset && !UsageAlertPolicy.fresh(remaining, observed, resets, q.optBoolean("stale", true), now))
                    detail += (detail.isEmpty() ? "" : " · ") + "依据上次记录";
                try {
                    if (!expectedToken.equals(connectedToken(c)) || !permitted(c)) return;
                    notify(c, tool, identity, text, detail);
                    if (low) sent.put(identity + ":low", resets * 1000L);
                    if (reset) sent.put(identity + ":reset", resets * 1000L);
                    delivered++;
                } catch (RuntimeException | JSONException ignored) { }
            }
        }
        if (sent.length() > 200) { // Bounded private state; discard windows that reset earliest.
            List<String> keys = new ArrayList<>(); Iterator<String> all = sent.keys(); while (all.hasNext()) keys.add(all.next());
            final JSONObject ordered = sent; keys.sort(Comparator.comparingLong(k -> ordered.optLong(k)));
            for (int i = 0; i < keys.size() - 200; i++) sent.remove(keys.get(i));
        }
        if (expectedToken.equals(connectedToken(c))) p.edit().putString("sent", sent.toString()).apply();
    }
    private static long positiveWhole(Object value) {
        if (!(value instanceof Number)) return 0;
        double numeric = ((Number) value).doubleValue();
        return Double.isFinite(numeric) && numeric > 0 && numeric <= 9007199254740991d && numeric == Math.floor(numeric)
                ? ((Number) value).longValue() : 0;
    }
    private static void notify(Context c, String tool, String identity, String text, String detail) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ensureChannel(nm);
        boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        nm.notify("usage:" + identity, UsageAlertNotification.ID,
                UsageAlertNotification.real(c, CHANNEL, tool, text, detail, locked));
    }
    private static void ensureChannel(NotificationManager nm) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "额度提醒", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET); nm.createNotificationChannel(channel);
    }
    private static synchronized void sendTest(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String token = connectedToken(activity);
        if (token.isEmpty()) { Toast.makeText(activity, "请先登录 Monitor", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 25);
            Toast.makeText(activity, "允许通知后，再点一次发送测试提醒", Toast.LENGTH_LONG).show(); return;
        }
        if (!permitted(activity)) { openNotificationSettings(activity); return; }
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            ensureChannel(manager);
            if (!token.equals(connectedToken(activity)) || !permitted(activity)) return;
            boolean locked = activity.getSystemService(KeyguardManager.class).isKeyguardLocked();
            // Explicit test action is independent of enabled/threshold/sent preferences.
            // Its fixed tag replaces earlier tests, and the usage: prefix lets account cleanup remove it.
            manager.notify(UsageAlertNotification.TEST_TAG, UsageAlertNotification.ID,
                    UsageAlertNotification.test(activity, CHANNEL, locked));
            Toast.makeText(activity, "测试提醒已发送", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "暂时无法发送，请检查通知设置", Toast.LENGTH_LONG).show();
        }
    }
    private static void openNotificationSettings(Activity activity) {
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            Intent target = new Intent(manager.areNotificationsEnabled() && channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                    ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            if (Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS.equals(target.getAction())) target.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
            activity.startActivity(target);
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "请在系统设置中打开 Monitor 的通知权限", Toast.LENGTH_LONG).show();
        }
    }
    public static void dismissSettings(Activity activity) {
        if (owner.get() == activity && settings != null) { settings.dismiss(); settings = null; owner.clear(); }
    }
    public static Dialog showSettings(Activity activity) {
        dismissSettings(activity);
        NativeUi ui = new NativeUi(activity); SharedPreferences p = prefs(activity);
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout column = ui.column(); column.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        column.setBackground(ui.bordered(ui.surface, 24)); column.addView(ui.text("额度提醒", 20, true)); column.addView(ui.space(16));
        Switch toggle = new Switch(activity); toggle.setText("开启提醒"); toggle.setTextColor(ui.text); toggle.setTypeface(ui.regular);
        toggle.setMinHeight(ui.dp(48)); toggle.setChecked(p.getBoolean("enabled", false)); column.addView(toggle);
        toggle.setOnCheckedChangeListener((v, checked) -> {
            p.edit().putBoolean("enabled", checked).apply(); sync(activity);
            if (checked && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 25);
        });
        column.addView(ui.space(12)); column.addView(ui.text("额度低于", 14));
        choices(ui, column, p, "low", new int[]{0, 5, 10, 20}, new String[]{"关闭", "5%", "10%", "20%"}, 10);
        column.addView(ui.space(16)); column.addView(ui.text("重置前", 14));
        choices(ui, column, p, "reset", new int[]{0, 5, 15, 30}, new String[]{"关闭", "5 分钟", "15 分钟", "30 分钟"}, 15);
        column.addView(ui.space(16)); TextView note = ui.text("按最近获取的真实额度提醒。后台检查由系统安排，省电状态下可能延后。锁屏隐藏额度提醒。", 13); note.setTextColor(ui.muted); column.addView(note);
        if (!permitted(activity)) { column.addView(ui.space(8)); column.addView(ui.button("系统通知设置", false, () -> openNotificationSettings(activity))); }
        column.addView(ui.space(10)); column.addView(ui.button("发送测试提醒", false, () -> sendTest(activity)));
        column.addView(ui.space(16)); column.addView(ui.button("完成", () -> { sync(activity); dialog.dismiss(); }));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(column); dialog.setContentView(scroll);
        owner = new WeakReference<>(activity); settings = dialog; dialog.show();
        Window w = dialog.getWindow(); if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            w.setDimAmount(.22f); WindowManager.LayoutParams a = w.getAttributes();
            a.width = Math.min(ui.dp(520), activity.getResources().getDisplayMetrics().widthPixels - ui.dp(24));
            a.height = ViewGroup.LayoutParams.WRAP_CONTENT; a.y = ui.dp(12); w.setAttributes(a);
        }
        return dialog;
    }
    private static void choices(NativeUi ui, LinearLayout parent, SharedPreferences p, String key, int[] values, String[] labels, int fallback) {
        LinearLayout row = ui.row(); final TextView[] buttons = new TextView[values.length];
        Runnable paint = () -> { for (int i = 0; i < values.length; i++) buttons[i].setBackground(ui.rounded(p.getInt(key, fallback) == values[i] ? ui.soft : ui.surface, 12)); };
        for (int i = 0; i < values.length; i++) {
            final int value = values[i]; TextView button = ui.button(labels[i], false, () -> { p.edit().putInt(key, value).apply(); paint.run(); });
            button.setTextSize(13); button.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)); buttons[i] = button;
            row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        paint.run(); parent.addView(row);
    }
}
