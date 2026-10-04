package com.agentmonitor.live;
final class TrackingService {
    static final int NOTIFICATION_ID = 15;
    static int clearAllRestores;
    static boolean untilTaskEnd;
    static long endsAt;
}
final class XiaomiLiveCapabilities {
    enum State { UNKNOWN, ENABLED, DISABLED }
    int protocolVersion = -1;
    State island = State.UNKNOWN, focusPermission = State.UNKNOWN;
    static XiaomiLiveCapabilities value = new XiaomiLiveCapabilities();
    static XiaomiLiveCapabilities cached() { return value; }
}
final class XiaomiLiveNotification {
    static final String PARAM = "miui.focus.param", PICS = "miui.focus.pics", TOOL_ICON = "miui.focus.pic_monitor_tool";
}
final class R { static final class drawable { static final int codex = 1, claude = 2; } }
