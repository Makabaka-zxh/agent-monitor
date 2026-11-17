package com.agentmonitor.live;

import java.util.Arrays;
import java.util.EnumSet;

/** Grouping must preserve existing brand/API eligibility without adding permissions. */
public final class LiveSettingsSectionsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static boolean has(LiveSettingsSections.Entry[] entries, LiveSettingsSections.Entry entry) {
        return Arrays.asList(entries).contains(entry);
    }
    public static void main(String[] args) {
        for (DeviceBrand brand : DeviceBrand.values()) {
            for (int sdk : new int[]{28, 35, 36, 37}) {
                LiveSettingsSections.Entry[] display = LiveSettingsSections.entries(LiveSettingsSections.Section.DISPLAY, brand, sdk);
                LiveSettingsSections.Entry[] notifications = LiveSettingsSections.entries(LiveSettingsSections.Section.NOTIFICATIONS, brand, sdk);
                LiveSettingsSections.Entry[] help = LiveSettingsSections.entries(LiveSettingsSections.Section.SYSTEM_HELP, brand, sdk);
                EnumSet<LiveSettingsSections.Entry> all = EnumSet.noneOf(LiveSettingsSections.Entry.class);
                for (LiveSettingsSections.Entry[] page : new LiveSettingsSections.Entry[][]{display, notifications, help}) {
                    for (LiveSettingsSections.Entry entry : page) check(all.add(entry), "each existing action has one primary settings location");
                }
                check(display[0] == LiveSettingsSections.Entry.PRESENTATION, "display starts with preview and content choices");
                check(display.length == (sdk >= 36 ? 2 : 1), "permission and OEM guidance cannot clutter display settings");
                check(has(display, LiveSettingsSections.Entry.SYSTEM_LIVE) == (sdk >= 36), "system promotion keeps API 36 boundary");
                check(has(notifications, LiveSettingsSections.Entry.NOTIFICATION_PERMISSION)
                        && has(notifications, LiveSettingsSections.Entry.TRACKING_CHANNEL), "notification permission and channel remain reachable for every device");
                check(has(notifications, LiveSettingsSections.Entry.BACKGROUND) == (brand == DeviceBrand.XIAOMI || brand.isOppoFamily()), "background guidance retains former brand eligibility");
                check(has(help, LiveSettingsSections.Entry.CLEAR_ALL), "removal-listener help remains available without enabling it");
                check(has(help, LiveSettingsSections.Entry.XIAOMI_ISLAND) == (brand == DeviceBrand.XIAOMI)
                        && has(help, LiveSettingsSections.Entry.XIAOMI_BACK_SCREEN) == (brand == DeviceBrand.XIAOMI), "Xiaomi guidance stays device-specific");
                check(has(help, LiveSettingsSections.Entry.OPPO_LIVE) == brand.isOppoFamily(), "all Oppo family brands keep fluid-cloud guidance");
                check(has(help, LiveSettingsSections.Entry.SAMSUNG_LIVE) == (brand == DeviceBrand.SAMSUNG && sdk >= 36), "Samsung guidance keeps both original guards");
                int expected = 4 + (sdk >= 36 ? 1 : 0)
                        + (brand == DeviceBrand.XIAOMI ? 3 : brand.isOppoFamily() ? 2 : brand == DeviceBrand.SAMSUNG && sdk >= 36 ? 1 : 0);
                check(all.size() == expected, "splitting pages does not lose or add existing actions");
                display[0] = LiveSettingsSections.Entry.CLEAR_ALL;
                check(LiveSettingsSections.entries(LiveSettingsSections.Section.DISPLAY, brand, sdk)[0] == LiveSettingsSections.Entry.PRESENTATION,
                        "caller cannot alter another screen's entries");
            }
        }
        System.out.println("LiveSettingsSectionsTest: " + checks + " checks passed");
    }
}
