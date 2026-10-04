package com.agentmonitor.live;

public final class XiaomiLiveCapabilitiesTest {
    private static int checks;
    private static void check(boolean good, String label) {
        checks++;
        if (!good) throw new AssertionError(label);
    }
    private static final class Probe implements XiaomiLiveCapabilities.Probe {
        final int protocol, failures;
        final Boolean island, focus;
        int calls;
        Probe(int protocol, Boolean island, Boolean focus, int failures) {
            this.protocol = protocol; this.island = island; this.focus = focus; this.failures = failures;
        }
        @Override public int protocolVersion() {
            calls++;
            if ((failures & 1) != 0) throw new SecurityException("synthetic settings denial");
            return protocol;
        }
        @Override public Boolean islandFeature() throws Exception {
            calls++;
            if ((failures & 2) != 0) throw new ReflectiveOperationException("synthetic hidden-API restriction");
            return island;
        }
        @Override public Boolean focusPermission() {
            calls++;
            if ((failures & 4) != 0) throw new IllegalArgumentException("synthetic missing provider");
            return focus;
        }
    }
    public static void main(String[] args) {
        XiaomiLiveCapabilities empty = XiaomiLiveCapabilities.cached();
        check(empty.protocolVersion == -1 && empty.island == XiaomiLiveCapabilities.State.UNKNOWN
                && empty.focusPermission == XiaomiLiveCapabilities.State.UNKNOWN,
                "startup does not assume capabilities or restore an old authorization");
        XiaomiLiveCapabilities denied = XiaomiLiveCapabilities.read(new Probe(3, true, false, 0));
        check(denied.protocolVersion == 3 && denied.island == XiaomiLiveCapabilities.State.ENABLED
                && denied.focusPermission == XiaomiLiveCapabilities.State.DISABLED,
                "capable island hardware does not imply application permission");
        XiaomiLiveCapabilities disabled = XiaomiLiveCapabilities.read(new Probe(3, false, true, 0));
        check(disabled.island == XiaomiLiveCapabilities.State.DISABLED
                && disabled.focusPermission == XiaomiLiveCapabilities.State.ENABLED,
                "allowed focus notifications do not imply island feature is enabled");
        XiaomiLiveCapabilities missing = XiaomiLiveCapabilities.read(new Probe(0, null, null, 0));
        check(missing.protocolVersion == 0 && missing.island == XiaomiLiveCapabilities.State.UNKNOWN
                && missing.focusPermission == XiaomiLiveCapabilities.State.UNKNOWN,
                "absent provider values stay unknown rather than denied");
        XiaomiLiveCapabilities restricted = XiaomiLiveCapabilities.read(new Probe(3, true, true, 2));
        check(restricted.protocolVersion == 3 && restricted.island == XiaomiLiveCapabilities.State.UNKNOWN
                && restricted.focusPermission == XiaomiLiveCapabilities.State.ENABLED,
                "restricted property reflection cannot erase successful independent observations");
        XiaomiLiveCapabilities unreachable = XiaomiLiveCapabilities.read(new Probe(3, true, true, 4));
        check(unreachable.focusPermission == XiaomiLiveCapabilities.State.UNKNOWN
                && unreachable.island == XiaomiLiveCapabilities.State.ENABLED,
                "failed permission provider is not interpreted as denial");
        XiaomiLiveCapabilities unreadable = XiaomiLiveCapabilities.read(new Probe(3, true, true, 1));
        check(unreadable.protocolVersion == -1 && unreadable.focusPermission == XiaomiLiveCapabilities.State.ENABLED,
                "unreadable protocol does not suppress permission query");
        Probe allFail = new Probe(3, true, true, 7);
        XiaomiLiveCapabilities failures = XiaomiLiveCapabilities.read(allFail);
        check(allFail.calls == 3 && failures.protocolVersion == -1
                && failures.island == XiaomiLiveCapabilities.State.UNKNOWN
                && failures.focusPermission == XiaomiLiveCapabilities.State.UNKNOWN,
                "all unavailable queries degrade safely and each is attempted once");
        check(XiaomiLiveCapabilities.read(new Probe(-9, true, true, 0)).protocolVersion == -1,
                "invalid negative protocol is unknown");
        check(XiaomiLiveCapabilities.read(new Probe(4, true, true, 0)).protocolVersion == 4,
                "future protocol is preserved for the adapter to decide, never rewritten as supported v3");
        XiaomiLiveCapabilities linkage = XiaomiLiveCapabilities.read(new XiaomiLiveCapabilities.Probe() {
            @Override public int protocolVersion() { throw new NoClassDefFoundError("synthetic unsupported class"); }
            @Override public Boolean islandFeature() { throw new NoSuchMethodError("synthetic unsupported method"); }
            @Override public Boolean focusPermission() { return true; }
        });
        check(linkage.protocolVersion == -1 && linkage.island == XiaomiLiveCapabilities.State.UNKNOWN
                && linkage.focusPermission == XiaomiLiveCapabilities.State.ENABLED,
                "platform linkage failure cannot crash the query or erase a successful permission observation");
        check(XiaomiLiveCapabilities.cached() == empty, "pure query evaluation does not grant or mutate cached permissions");
        System.out.println("XiaomiLiveCapabilitiesTest: " + checks + " checks passed");
    }
}
