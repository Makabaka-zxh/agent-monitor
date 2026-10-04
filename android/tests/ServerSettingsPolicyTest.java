package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;

/** Offline origin-transition and legacy-session decisions; no UI, disk or network. */
public final class ServerSettingsPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String old = "https://old.example.com", next = "https://next.example.com";
        check(!ServerSettings.ready("", ""), "fresh install has no active origin");
        check(!ServerSettings.ready("", next), "first save requires a new process");
        check(ServerSettings.ready(next, next), "reopened process uses saved origin");
        check(!ServerSettings.ready(old, next), "changing saved origin cannot retarget current process");
        check(!ServerSettings.ready(old, ""), "removing stored origin disables current login UI");
        check(ServerSettings.ready(old, old), "unchanged configured process remains connected");
        JSONObject empty = new JSONObject();
        check(!ServerSettings.storedConnection(empty), "clean logout permits address editing");
        check(!ServerSettings.legacyConnection("", "", empty), "fresh install does not need destructive reset");
        check(!ServerSettings.storedConnection(new JSONObject().put("retired_readers", new JSONArray())), "empty retired queue is not a credential");
        JSONObject[] legacy = {
                new JSONObject().put("reader_token", "synthetic-reader"),
                new JSONObject().put("logout_pending", true),
                new JSONObject().put("pairing", new JSONObject().put("phase", "pending")),
                new JSONObject().put("retired_reader", "synthetic-retired"),
                new JSONObject().put("retired_readers", new JSONArray().put("synthetic-retired"))
        };
        for (JSONObject session : legacy) {
            check(ServerSettings.storedConnection(session), "every existing credential stage blocks ordinary origin edits");
            check(ServerSettings.legacyConnection("", "", session), "originless legacy state offers local reset");
            check(!ServerSettings.legacyConnection(old, old, session), "configured session must use normal remote logout");
            check(!ServerSettings.legacyConnection(old, next, session), "origin transition cannot bypass ordinary logout");
            check(!ServerSettings.legacyConnection("", next, session), "pending first origin is not silently bound to old credentials");
        }
        check(!ServerSettings.storedConnection(new JSONObject().put("pairing_notice", "reauthorize")), "informational login notice does not block setup");
        Cleanup untouched = new Cleanup();
        check(next.equals(ServerSettings.prepareOrigin(next, false, untouched)), "unchanged configured origin preserves login");
        check(untouched.order.length() == 0, "ordinary startup never clears a saved login");
        Cleanup lateRetirement = new Cleanup();
        check(next.equals(ServerSettings.prepareOrigin(next, true, lateRetirement)), "pending switch activates only after cleanup");
        check("clear,ack,".equals(lateRetirement.order.toString()), "session is cleared before durable acknowledgement");
        Cleanup failedSession = new Cleanup(); failedSession.clear = false;
        check(ServerSettings.prepareOrigin(next, true, failedSession).isEmpty(), "failed session clearing leaves origin disabled");
        check("clear,".equals(failedSession.order.toString()), "failed clearing retains pending marker for retry");
        Cleanup failedAck = new Cleanup(); failedAck.ack = false;
        check(ServerSettings.prepareOrigin(next, true, failedAck).isEmpty(), "failed acknowledgement also leaves origin disabled");
        check("clear,ack,".equals(failedAck.order.toString()), "acknowledgement cannot precede credential erasure");
        Cleanup restart = new Cleanup();
        check(next.equals(ServerSettings.prepareOrigin(next, true, restart)), "next startup can safely retry cleanup after failure");
        Cleanup exception = new Cleanup(); exception.fail = true;
        check(ServerSettings.prepareOrigin(next, true, exception).isEmpty(), "storage exception fails closed");
        check(ServerSettings.prepareOrigin("http://next.example.com", false, untouched).isEmpty(), "invalid saved origin never activates");
        System.out.println("ServerSettingsPolicyTest: " + checks + " checks passed");
    }
    private static final class Cleanup implements ServerSettings.OriginCleanup {
        final StringBuilder order = new StringBuilder();
        boolean clear = true, ack = true, fail;
        public boolean clearSession() {
            order.append("clear,"); if (fail) throw new IllegalStateException("synthetic storage failure"); return clear;
        }
        public boolean acknowledge() { order.append("ack,"); return ack; }
    }
}
