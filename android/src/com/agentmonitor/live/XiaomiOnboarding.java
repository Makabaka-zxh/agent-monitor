package com.agentmonitor.live;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Local build configuration only; neither an OEM grant nor proof of rendering. */
final class XiaomiOnboarding {
    private static final String APP_ID = "com.xiaomi.xms.APP_ID";
    enum State { UNCONFIGURED, CONFIGURED, UNKNOWN }

    static State state(Context context) {
        if (context == null) return State.UNKNOWN;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            if (app == null) return State.UNKNOWN;
            return configured(app.metaData) ? State.CONFIGURED : State.UNCONFIGURED;
        } catch (Exception | LinkageError unavailable) {
            return State.UNKNOWN;
        }
    }

    static boolean configured(Context context) { return state(context) == State.CONFIGURED; }

    static boolean configured(Bundle metadata) {
        if (metadata == null) return false;
        Object value = metadata.get(APP_ID);
        if (!(value instanceof String)) return false;
        String id = (String) value;
        if (id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private XiaomiOnboarding() { }
}
