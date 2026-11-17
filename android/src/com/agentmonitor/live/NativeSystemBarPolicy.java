package com.agentmonitor.live;

/** Pure color decisions for the opaque protection actually drawn behind system icons. */
public final class NativeSystemBarPolicy {
    private NativeSystemBarPolicy() {}

    public static int opaque(int color) { return color | 0xff000000; }

    /** Select the higher-contrast black/white icon treatment, independently for each bar. */
    public static boolean darkIcons(int color) {
        double light = luminance(color);
        return (light + 0.05) / 0.05 >= 1.05 / (light + 0.05);
    }

    static double luminance(int color) {
        return 0.2126 * linear((color >>> 16) & 255)
                + 0.7152 * linear((color >>> 8) & 255) + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
