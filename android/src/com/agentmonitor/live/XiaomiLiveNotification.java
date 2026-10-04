package com.agentmonitor.live;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/**
 * Adds Xiaomi's documented notification template without changing standard notification behavior.
 *
 * API: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 * Template library linked there, 2026-01-29 edition: pages 3/31/39 (expanded template 2),
 * 76/95/96 (island template 1), and 110 (small-island icon).
 * Back-screen rendering is managed by Xiaomi: documentation/detail?pId=2144.
 *
 * A positive capability query is not proof of Xiaomi developer onboarding or scene approval.
 * The business string identifies our own scene for statistics; it is not a permission or an
 * approved taxi/media scene. Xiaomi still controls acceptance and rendering (pId=2132).
 * No task title, state, result, account data, action, or arbitrary icon enters this OEM payload.
 */
final class XiaomiLiveNotification {
    static final String PARAM = "miui.focus.param";
    static final String PICS = "miui.focus.pics";
    static final String TOOL_ICON = "miui.focus.pic_monitor_tool";
    private static final String BUSINESS = "agent_monitor";

    private XiaomiLiveNotification() { }

    /** True means extras were attached, never that the OS displayed an island or rear-screen card. */
    static boolean apply(Context context, Notification notification, DeviceBrand brand,
                         XiaomiLiveCapabilities capabilities, String tool, boolean ongoing) {
        String payload = payload(brand, capabilities, tool, ongoing);
        if (payload == null || context == null || notification == null) return false;
        try {
            // Only bundled provider artwork is accepted; caller-supplied bitmaps could contain data.
            Icon icon = Icon.createWithResource(context,
                    "claude".equals(tool) ? R.drawable.claude : R.drawable.codex);
            Bundle pictures = new Bundle();
            pictures.putParcelable(TOOL_ICON, icon);
            attach(notification, payload, pictures);
            if (notification.publicVersion != null) {
                attach(notification.publicVersion, payload, pictures);
            }
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            // Native ongoing notifications remain the fallback. No alternate display is created.
            return false;
        }
    }

    private static void attach(Notification notification, String payload, Bundle pictures) {
        if (notification.extras == null) notification.extras = new Bundle();
        notification.extras.putString(PARAM, payload);
        notification.extras.putBundle(PICS, new Bundle(pictures));
    }

    /** Pure, fail-closed policy. Unknown future protocols need verification before enabling. */
    static String payload(DeviceBrand brand, XiaomiLiveCapabilities capabilities, String tool,
                          boolean ongoing) {
        if (brand != DeviceBrand.XIAOMI || !ongoing || capabilities == null
                || capabilities.protocolVersion != 3
                || capabilities.island != XiaomiLiveCapabilities.State.ENABLED
                || capabilities.focusPermission != XiaomiLiveCapabilities.State.ENABLED) return null;
        // Deliberately use an exact allowlist instead of accepting arbitrary display strings.
        final String label;
        if ("codex".equals(tool)) label = "Codex";
        else if ("claude".equals(tool)) label = "Claude";
        else return null;

        // Every interpolated value is a constant or an allowlisted provider label. All OEM
        // surfaces, including an unlocked island, receive the same minimal public content.
        // Required secondary text identifies this app; it never describes the monitored task.
        return "{\"param_v2\":{\"protocol\":1,\"business\":\"" + BUSINESS
                + "\",\"updatable\":true,\"islandFirstFloat\":false,\"enableFloat\":false,"
                + "\"filterWhenNoPermission\":false,\"aodTitle\":\"" + label
                + "\",\"aodPic\":\"" + TOOL_ICON + "\","
                + "\"baseInfo\":{\"type\":2,\"title\":\"" + label + "\",\"content\":\"Monitor\"},"
                + "\"picInfo\":{\"type\":1,\"pic\":\"" + TOOL_ICON
                + "\",\"picDark\":\"" + TOOL_ICON + "\"},"
                + "\"param_island\":{\"islandProperty\":1,\"bigIslandArea\":{"
                + "\"imageTextInfoLeft\":{\"type\":1,\"textInfo\":{\"title\":\"" + label + "\"}}},"
                + "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"" + TOOL_ICON + "\"}}}}}";
    }
}
