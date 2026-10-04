package com.agentmonitor.live;

/** Persistent handoff phases; an uncertain exchange is never automatically retried. */
public final class NativePairingStage {
    private NativePairingStage() {}
    public static boolean connected(String mode, boolean cookieReady, boolean logoutPending, String token, long expiry, long now) {
        return "full_app".equals(mode) && cookieReady && !logoutPending && token != null && !token.isEmpty() && expiry > now;
    }
    public static String action(String phase, boolean busy, long now, long ticketExpires) {
        if (busy) return "wait";
        if ("pending".equals(phase)) return "poll";
        if ("claimed".equals(phase)) return now < ticketExpires ? "exchange" : "reauthorize";
        if ("exchanging".equals(phase)) return "reauthorize";
        return "none";
    }
}

