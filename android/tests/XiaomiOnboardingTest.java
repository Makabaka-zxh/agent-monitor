package com.agentmonitor.live;

import android.content.Context;
import android.os.Bundle;

/** Only synthetic application metadata; no platform registration or real account. */
public final class XiaomiOnboardingTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        String key = "com.xiaomi.xms.APP_ID";
        check(!XiaomiOnboarding.configured((Bundle) null), "missing metadata unconfigured");
        check(!XiaomiOnboarding.configured(new Bundle()), "missing key unconfigured");
        for (Object value : new Object[]{null, "", " ", "\t", "\n", "\u00a0", "\u3000", "id with space", "id\n", "id\u0000", 123, true,
                new Object() { public String toString() { throw new AssertionError("Do not stringify app metadata"); } }}) {
            check(!XiaomiOnboarding.configured(new Bundle().put(key, value)), "wrong type or whitespace/control metadata is not configured");
        }
        Bundle valid = new Bundle().put(key, "synthetic-platform-id").put("private-unused-key", "private-account-value");
        check(XiaomiOnboarding.configured(valid), "nonempty synthetic string configured");
        check(valid.readKeys.size() == 1 && key.equals(valid.readKeys.get(0)), "only fixed App ID key is read");
        check(XiaomiOnboarding.state(null) == XiaomiOnboarding.State.UNKNOWN && !XiaomiOnboarding.configured((Context) null), "missing context is unknown");
        Context context = new Context();
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.UNCONFIGURED, "metadata absent from own package");
        context.app.metaData = valid;
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.CONFIGURED && XiaomiOnboarding.configured(context), "own package typed string is configured only");
        context.packages.fail = true;
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.UNKNOWN && !XiaomiOnboarding.configured(context), "query failure never claims configuration");
        context.packages.fail = false; context.packages.linkage = true;
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.UNKNOWN, "older API linkage failure is unknown");
        context.packages.linkage = false; context.packages.app = null;
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.UNKNOWN, "missing application info is unknown");
        context.packages.app = context.app; context.app.metaData = new Bundle(); context.app.metaData.broken = true;
        check(XiaomiOnboarding.state(context) == XiaomiOnboarding.State.UNKNOWN, "unreadable metadata is unknown");
        System.out.println("XiaomiOnboardingTest: " + checks + " checks passed (configuration only, never OEM approval)");
    }
}
