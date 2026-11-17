package com.agentmonitor.live;

import java.util.ArrayList;
import java.util.List;

/** Menu placement only; it grants no permission and never writes a preference. */
final class LiveSettingsSections {
    enum Section {
        DISPLAY("实况显示"), NOTIFICATIONS("通知与后台"), SYSTEM_HELP("系统适配与帮助");
        final String title;
        Section(String title) { this.title = title; }
    }

    enum Entry {
        PRESENTATION("显示内容"), SYSTEM_LIVE("系统实况设置"),
        NOTIFICATION_PERMISSION("通知权限"), TRACKING_CHANNEL("任务跟踪通知"), BACKGROUND("后台运行"),
        XIAOMI_ISLAND("小米超级岛"), XIAOMI_BACK_SCREEN("妙享背屏"),
        OPPO_LIVE("流体云"), SAMSUNG_LIVE("三星实况帮助"), CLEAR_ALL("清理时保留实况");
        final String label;
        Entry(String label) { this.label = label; }
    }

    private LiveSettingsSections() { }

    static Entry[] entries(Section section, DeviceBrand brand, int sdk) {
        List<Entry> result = new ArrayList<>();
        switch (section) {
            case DISPLAY:
                result.add(Entry.PRESENTATION);
                if (sdk >= 36) result.add(Entry.SYSTEM_LIVE);
                break;
            case NOTIFICATIONS:
                result.add(Entry.NOTIFICATION_PERMISSION);
                result.add(Entry.TRACKING_CHANNEL);
                if (brand == DeviceBrand.XIAOMI || brand.isOppoFamily()) result.add(Entry.BACKGROUND);
                break;
            case SYSTEM_HELP:
                if (brand == DeviceBrand.XIAOMI) {
                    result.add(Entry.XIAOMI_ISLAND);
                    result.add(Entry.XIAOMI_BACK_SCREEN);
                } else if (brand.isOppoFamily()) {
                    result.add(Entry.OPPO_LIVE);
                } else if (brand == DeviceBrand.SAMSUNG && sdk >= 36) {
                    result.add(Entry.SAMSUNG_LIVE);
                }
                result.add(Entry.CLEAR_ALL);
                break;
            default: throw new IllegalArgumentException("Unknown settings section");
        }
        return result.toArray(new Entry[0]);
    }
}
