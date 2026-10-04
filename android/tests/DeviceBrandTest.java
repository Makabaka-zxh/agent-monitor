package com.agentmonitor.live;

import java.util.Locale;

public final class DeviceBrandTest {
    private static int checks;
    private static void check(boolean actual, String message) {
        checks++;
        if (!actual) throw new AssertionError(message);
    }
    private static void detected(String manufacturer, String brand, DeviceBrand expected) {
        check(DeviceBrand.detect(manufacturer, brand) == expected,
                "brand routing: manufacturer=" + manufacturer + ", brand=" + brand);
    }
    public static void main(String[] args) {
        detected("Xiaomi", "Xiaomi", DeviceBrand.XIAOMI);
        detected("Xiaomi", "Redmi", DeviceBrand.XIAOMI);
        detected("Xiaomi", "POCO", DeviceBrand.XIAOMI);
        detected("unknown", "Redmi", DeviceBrand.XIAOMI);
        detected("POCO", null, DeviceBrand.XIAOMI);
        detected("OPPO", "OPPO", DeviceBrand.OPPO);
        detected("OPPO", "OnePlus", DeviceBrand.ONEPLUS);
        detected("OPPO", "realme", DeviceBrand.REALME);
        detected("OnePlus", "unknown", DeviceBrand.ONEPLUS);
        detected("realme", "", DeviceBrand.REALME);
        detected("Samsung", "samsung", DeviceBrand.SAMSUNG);
        detected(" Samsung ", null, DeviceBrand.SAMSUNG);
        detected("test", " XIAOMI ", DeviceBrand.XIAOMI);
        detected(null, null, DeviceBrand.GENERIC);
        detected("", "", DeviceBrand.GENERIC);
        detected("Google", "google", DeviceBrand.GENERIC);
        detected("xiaomi-compatible", "not-oppo", DeviceBrand.GENERIC);
        detected("hmd", "samsungish", DeviceBrand.GENERIC);
        detected("MI", "Mi", DeviceBrand.GENERIC);
        detected("hongkong", "M610BB", DeviceBrand.GENERIC);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            detected("XIAOMI", "REDMI", DeviceBrand.XIAOMI);
        } finally { Locale.setDefault(previous); }
        for (DeviceBrand item : DeviceBrand.values()) {
            check(item.isOppoFamily() == (item == DeviceBrand.OPPO || item == DeviceBrand.ONEPLUS || item == DeviceBrand.REALME),
                    "OPPO-family guidance stays confined to matching brands");
            check(!item.settingsTitle().isEmpty(), "every device has a settings title");
        }
        check("实况通知".equals(DeviceBrand.GENERIC.settingsTitle()), "unknown devices retain generic settings");
        System.out.println("DeviceBrandTest: " + checks + " checks passed");
    }
}
