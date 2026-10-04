package com.agentmonitor.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Local display choices; they never grant data access or change tracking lifetime. */
public final class LivePresentationPreferences {
    private static final String FILE = "live_presentation_v1";
    public static final String TITLE = "task_title", COMPUTER = "computer", ELAPSED = "elapsed",
            SESSION_TOKENS = "session_tokens", TODAY_TOKENS = "today_tokens", QUOTA = "quota";
    public static final String[] KEYS = {TITLE, COMPUTER, ELAPSED, SESSION_TOKENS, TODAY_TOKENS, QUOTA};
    public enum Preset { SIMPLE, BALANCED, RICH, CUSTOM }

    private LivePresentationPreferences() { }
    public static boolean enabled(Context context, String key) {
        if (!known(key)) return false;
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, defaultEnabled(Build.MANUFACTURER, key));
    }
    static boolean defaultEnabled(String manufacturer, String key) {
        return TITLE.equals(key) || ELAPSED.equals(key)
                || ("oppo".equalsIgnoreCase(manufacturer) && known(key));
    }
    public static void set(Context context, String key, boolean enabled) {
        if (!known(key)) return;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply();
        TrackingService.refreshPresentation();
    }
    /** Presets map to the same six existing keys; no new preference or migration is needed. */
    static LivePresentation.Options presetOptions(Preset preset) {
        if (preset == Preset.SIMPLE) return new LivePresentation.Options(false, false, false, false, false, false);
        if (preset == Preset.BALANCED) return new LivePresentation.Options(true, false, true, false, false, false);
        if (preset == Preset.RICH) return new LivePresentation.Options(true, true, true, true, true, true);
        throw new IllegalArgumentException("Custom is a match result, not a preset to apply");
    }
    static Preset matchingPreset(LivePresentation.Options options) {
        if (options == null) return Preset.CUSTOM;
        if (!options.title && !options.computer && !options.elapsed && !options.session && !options.today && !options.quota) return Preset.SIMPLE;
        if (options.title && !options.computer && options.elapsed && !options.session && !options.today && !options.quota) return Preset.BALANCED;
        if (options.title && options.computer && options.elapsed && options.session && options.today && options.quota) return Preset.RICH;
        return Preset.CUSTOM;
    }
    /** Apply a complete selection atomically, then update an existing tracking notification once. */
    static void save(Context context, LivePresentation.Options options) {
        if (options == null) throw new IllegalArgumentException("Display options are required");
        SharedPreferences.Editor editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit();
        editor.putBoolean(TITLE, options.title).putBoolean(COMPUTER, options.computer)
                .putBoolean(ELAPSED, options.elapsed).putBoolean(SESSION_TOKENS, options.session)
                .putBoolean(TODAY_TOKENS, options.today).putBoolean(QUOTA, options.quota).apply();
        TrackingService.refreshPresentation();
    }
    static LivePresentation.Options options(Context context) {
        return new LivePresentation.Options(enabled(context, TITLE), enabled(context, COMPUTER), enabled(context, ELAPSED),
                enabled(context, SESSION_TOKENS), enabled(context, TODAY_TOKENS), enabled(context, QUOTA));
    }
    private static boolean known(String key) {
        for (String item : KEYS) if (item.equals(key)) return true;
        return false;
    }
}
