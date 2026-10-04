package com.agentmonitor.live;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Explicit server selection; origins are never inferred from an untrusted incoming link. */
public final class ServerSettings {
    private static boolean initialized;
    private static String startupOrigin = "";
    interface OriginCleanup {
        boolean clearSession();
        boolean acknowledge();
    }
    private ServerSettings() { }
    static String origin(Context context) {
        try { return NativeWebPolicy.canonicalOrigin(context.getSharedPreferences("monitor_server_v1", Context.MODE_PRIVATE).getString("origin", "")); }
        catch (RuntimeException invalid) { return ""; }
    }
    static synchronized void initialize(Context context) {
        // One process uses one origin, including requests already queued by background workers.
        if (initialized) return;
        SharedPreferences preferences = context.getSharedPreferences("monitor_server_v1", Context.MODE_PRIVATE);
        startupOrigin = prepareOrigin(origin(context), preferences.getBoolean("pending_origin_change", false), new OriginCleanup() {
            public boolean clearSession() {
                try { SessionStore.write(context, new JSONObject()); return true; }
                catch (Exception unavailable) { return false; }
            }
            public boolean acknowledge() { return preferences.edit().remove("pending_origin_change").commit(); }
        });
        NativeWebPolicy.ORIGIN = startupOrigin;
        NativeApi.ORIGIN = startupOrigin;
        initialized = true;
    }
    static String prepareOrigin(String saved, boolean pending, OriginCleanup cleanup) {
        // The previous process may have persisted a late retired token after the address was saved.
        // Do not publish the new origin until both cleanup and its durable acknowledgement succeed.
        try { if (pending && (!cleanup.clearSession() || !cleanup.acknowledge())) return ""; }
        catch (RuntimeException failure) { return ""; }
        return NativeWebPolicy.canonicalOrigin(saved);
    }
    static boolean ready(String initial, String saved) { return !initial.isEmpty() && initial.equals(saved); }
    static boolean storedConnection(JSONObject session) {
        return !session.optString("reader_token").isEmpty() || session.optBoolean("logout_pending")
                || session.optJSONObject("pairing") != null || !session.optString("retired_reader").isEmpty()
                || session.optJSONArray("retired_readers") != null && session.optJSONArray("retired_readers").length() > 0;
    }
    static boolean legacyConnection(String initial, String saved, JSONObject session) {
        return initial.isEmpty() && saved.isEmpty() && storedConnection(session);
    }
    public static boolean configured(Context context) { return ready(startupOrigin, origin(context)); }
    static boolean requiresRestart(Context context) { return !startupOrigin.equals(origin(context)); }
    static boolean legacyConnection(Context context) { return legacyConnection(startupOrigin, origin(context), SessionStore.read(context)); }
    private static boolean hasConnection(Context context) {
        return storedConnection(SessionStore.read(context)) || NativeConnection.isBusy();
    }
    static void closeForRestart(Activity activity) {
        activity.finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private static void restartBody(Activity activity, NativeUi ui, LinearLayout content) {
        content.removeAllViews();
        content.addView(ui.text("地址已保存。关闭后重新打开 Monitor，新地址才会生效。", 15));
        content.addView(ui.space(16));
        content.addView(ui.button("关闭应用", () -> closeForRestart(activity)), new LinearLayout.LayoutParams(-1,-2));
    }
    public static LivePresentationSheet show(Activity activity, Runnable resetLegacy) {
        NativeUi ui = new NativeUi(activity);
        LivePresentationSheet sheet = new LivePresentationSheet(activity, "服务器地址");
        LinearLayout content = ui.column();
        if (requiresRestart(activity)) {
            restartBody(activity, ui, content); sheet.body(content); sheet.show(); return sheet;
        }
        if (legacyConnection(activity)) {
            content.addView(ui.text("检测到旧版连接，但没有保存服务器地址。请先清除本机旧连接，再设置地址并重新登录。", 15));
            content.addView(ui.space(16));
            content.addView(ui.button("清除旧连接…", () -> { sheet.dismiss(); resetLegacy.run(); }), new LinearLayout.LayoutParams(-1,-2));
            sheet.body(content); sheet.show(); return sheet;
        }
        EditText address = new EditText(activity);
        address.setSingleLine(true); address.setTextColor(ui.text); address.setHintTextColor(ui.muted);
        address.setTextSize(15); address.setTypeface(ui.regular); address.setHint("https://monitor.example.com");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(origin(activity)); address.setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14));
        address.setBackground(ui.bordered(ui.bg,16)); address.setContentDescription("HTTPS 服务器地址");
        content.addView(address,new LinearLayout.LayoutParams(-1,-2)); content.addView(ui.space(16));
        TextView message=ui.text("填写你部署的 Monitor 地址。",14); message.setTextColor(ui.muted);
        content.addView(message); content.addView(ui.space(16));
        TextView save=ui.button("保存", () -> {
            String candidate = NativeWebPolicy.canonicalOrigin(address.getText().toString().trim());
            if (candidate.isEmpty()) { message.setText("请使用 HTTPS 地址，不含路径、参数或非标准端口。"); return; }
            if (candidate.equals(origin(activity))) { sheet.dismiss(); return; }
            if (hasConnection(activity)) { message.setText("更换服务器前，请先完成退出登录并取消待确认的连接。"); return; }
            if (!activity.getSharedPreferences("monitor_server_v1",Context.MODE_PRIVATE).edit()
                    .putString("origin",candidate).putBoolean("pending_origin_change",true).commit()) {
                message.setText("保存失败，请重试。"); return;
            }
            // Do not retarget any worker in this process, even after a successful local logout.
            restartBody(activity, ui, content);
        });
        if(hasConnection(activity)) { address.setEnabled(false); save.setVisibility(View.GONE); message.setText("更换服务器前，请先完成退出登录。"); }
        content.addView(save,new LinearLayout.LayoutParams(-1,-2)); sheet.body(content); sheet.show(); return sheet;
    }
}
