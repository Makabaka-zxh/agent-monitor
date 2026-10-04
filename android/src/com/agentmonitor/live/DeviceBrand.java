package com.agentmonitor.live;

import java.util.Locale;

/** Device routing only: a brand never proves that an OEM notification surface is supported. */
enum DeviceBrand {
    XIAOMI("小米"), OPPO("OPPO"), ONEPLUS("一加"), REALME("realme"), SAMSUNG("三星"), GENERIC("");

    final String label;

    DeviceBrand(String label) { this.label = label; }

    static DeviceBrand detect(String manufacturer, String brand) {
        // Some sub-brands use a parent manufacturer's name. Prefer the advertised brand.
        DeviceBrand advertised = fromName(brand);
        return advertised != GENERIC ? advertised : fromName(manufacturer);
    }

    private static DeviceBrand fromName(String value) {
        String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "xiaomi": case "redmi": case "poco": return XIAOMI;
            case "oppo": return OPPO;
            case "oneplus": return ONEPLUS;
            case "realme": return REALME;
            case "samsung": return SAMSUNG;
            default: return GENERIC;
        }
    }

    boolean isOppoFamily() { return this == OPPO || this == ONEPLUS || this == REALME; }

    String settingsTitle() { return this == GENERIC ? "实况通知" : label + "实况设置"; }
}
