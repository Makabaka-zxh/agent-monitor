package com.agentmonitor.live;

import android.content.Context;

/** Separate on-device visual preference, independent of login and private server settings. */
public final class NativeAppearancePreferences {
    private NativeAppearancePreferences() {}
    public static String mode(Context context) {
        return NativeAppearancePolicy.mode(context.getSharedPreferences("native_appearance", Context.MODE_PRIVATE)
                .getString("background", "cloud"));
    }
    public static void setMode(Context context, String mode) {
        context.getSharedPreferences("native_appearance", Context.MODE_PRIVATE).edit()
                .putString("background", NativeAppearancePolicy.mode(mode)).apply();
    }
}
