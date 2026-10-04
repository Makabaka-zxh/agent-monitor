package com.agentmonitor.live;

public final class NativeWorkbenchPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        filterSaveDoesNotDiscardTaskRead();
        workspaceWritesRejectSpanningReads();
        interleavedWritesKeepTheirBoundaries();
        emptyStatesDistinguishServiceAndComputer();
        coldLaunchRequestsOnlyVisibleData();
        navigationAndNotificationReadsStayScoped();
        pauseResumeAndLoginChangesRespectCurrentPage();
        System.out.println("NativeWorkbenchPolicyTest: " + checks + " checks passed");
    }
    private static final class PageReads {
        final java.util.List<String> requests = new java.util.ArrayList<>();
        void load(String page, boolean visible, boolean connected) {
            NativeWorkbenchPolicy.loadPage(page, visible, connected,
                    () -> requests.add("workbench"), () -> requests.add("usage"), () -> requests.add("account"));
        }
        void expect(String... expected) {
            check(requests.equals(java.util.Arrays.asList(expected)), "visible-page dispatch sequence: " + requests);
            requests.clear();
        }
    }
    private static void coldLaunchRequestsOnlyVisibleData() {
        PageReads reads = new PageReads();
        for (String page : new String[]{"tasks", "task", "devices", "device"}) {
            reads.load(page, true, true);
            reads.expect("workbench");
            check(NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.WORKBENCH,
                    "workspace cold start does not wait for profile/avatar/account data");
        }
        reads.load("usage", true, true); reads.expect("usage");
        for (String page : new String[]{"account", "profile", "sync", "sessions"}) {
            reads.load(page, true, true); reads.expect("account");
            check(NativeWorkbenchPolicy.pageRead(page) == NativeWorkbenchPolicy.PageRead.ACCOUNT,
                    "account consumers still fetch their own dependencies on a cold start");
        }
        for (String page : new String[]{"pairing", "pairing-confirm", "", "future-page", null}) {
            reads.load(page, true, true); reads.expect();
        }
    }
    private static void navigationAndNotificationReadsStayScoped() {
        PageReads reads = new PageReads();
        // OPEN_USAGE uses this same dispatcher, so it cannot prefetch a workbench
        // snapshot or account payload before loading the requested history chart.
        reads.load("tasks", true, true);
        reads.load("usage", true, true);
        reads.load("task", true, true);
        reads.expect("workbench", "usage", "workbench");
        // Deferring account data is safe only if its actual consumers fetch it.
        reads.load("account", true, true);
        reads.load("profile", true, true);
        reads.load("sync", true, true);
        reads.load("sessions", true, true);
        reads.load("pairing", true, true);
        reads.load("pairing-confirm", true, true);
        reads.expect("account", "account", "account", "account");
    }
    private static void pauseResumeAndLoginChangesRespectCurrentPage() {
        PageReads reads = new PageReads();
        for (String page : new String[]{"tasks", "usage", "account", "profile", "sync", "sessions", "pairing-confirm"}) {
            reads.load(page, false, true); // Paused activity, credential still exists.
            reads.load(page, true, false); // Logged out or pairing not approved yet.
            reads.load(page, false, false);
            reads.expect();
        }
        reads.load("tasks", true, true);
        reads.load("tasks", false, true);
        reads.load("tasks", true, true);
        reads.expect("workbench", "workbench");
        // Reconcile after a changed login selects the current page, not both the
        // account and workbench endpoints. Pausing cannot queue future requests.
        reads.load("usage", true, false);
        reads.load("usage", true, true);
        reads.load("usage", false, true);
        reads.load("usage", true, true);
        reads.expect("usage", "usage");
        reads.load("profile", true, false);
        reads.load("profile", true, true);
        reads.expect("account");
    }
    private static void filterSaveDoesNotDiscardTaskRead() {
        NativeWorkbenchPolicy.RevisionGate gate = new NativeWorkbenchPolicy.RevisionGate();
        long beforeFilter = gate.capture();
        gate.started(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        check(gate.accepts(beforeFilter), "in-flight task GET survives start of tool-filter save");
        long duringFilter = gate.capture();
        gate.finished(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        check(gate.accepts(beforeFilter), "task GET survives completed tool-filter save");
        check(gate.accepts(duringFilter), "task GET begun during filter save remains independent");
        for (int i = 0; i < 8; i++) {
            gate.started(NativeWorkbenchPolicy.Change.TOOL_FILTER);
            gate.finished(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        }
        check(gate.accepts(beforeFilter), "rapid filter changes and retries cannot starve task refresh");
    }
    private static void workspaceWritesRejectSpanningReads() {
        NativeWorkbenchPolicy.RevisionGate gate = new NativeWorkbenchPolicy.RevisionGate();
        long beforeWrite = gate.capture();
        gate.started(NativeWorkbenchPolicy.Change.WORKSPACE);
        check(!gate.accepts(beforeWrite), "task GET spanning a real write start is rejected");
        long duringWrite = gate.capture();
        gate.finished(NativeWorkbenchPolicy.Change.WORKSPACE);
        check(!gate.accepts(beforeWrite), "write completion does not make earlier GET current again");
        check(!gate.accepts(duringWrite), "task GET spanning real write completion is rejected");
        check(gate.accepts(gate.capture()), "next GET can recover after real mutation completes");
    }
    private static void interleavedWritesKeepTheirBoundaries() {
        NativeWorkbenchPolicy.RevisionGate gate = new NativeWorkbenchPolicy.RevisionGate();
        gate.started(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        long beforeArchive = gate.capture();
        gate.started(NativeWorkbenchPolicy.Change.WORKSPACE);
        gate.finished(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        check(!gate.accepts(beforeArchive), "filter callback cannot undo archive invalidation");
        long duringArchive = gate.capture();
        gate.started(NativeWorkbenchPolicy.Change.WORKSPACE);
        check(!gate.accepts(duringArchive), "second real mutation invalidates a read during the first");
        long beforeFirstCompletion = gate.capture();
        gate.finished(NativeWorkbenchPolicy.Change.WORKSPACE);
        check(!gate.accepts(beforeFirstCompletion), "first write result still invalidates overlapping reads");
        long beforeSecondCompletion = gate.capture();
        gate.finished(NativeWorkbenchPolicy.Change.WORKSPACE);
        check(!gate.accepts(beforeSecondCompletion), "second write result has its own boundary");
        long recovered = gate.capture();
        gate.started(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        gate.finished(NativeWorkbenchPolicy.Change.TOOL_FILTER);
        check(gate.accepts(recovered), "filter-only retry remains independent after real writes");
        boolean rejected = false;
        try { gate.started(null); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "uncategorized mutation is not silently treated as a filter");
    }
    private static void emptyStatesDistinguishServiceAndComputer() {
        check("连接中，暂时无法确认进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(true, 2, 2, 0)), "old HTTP snapshot cannot claim no work");
        check("连接中，暂时无法确认进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(true, 2, 0, 2)), "HTTP staleness takes priority over remembered offline devices");
        check("电脑已离线，暂时无法确认进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(false, 2, 0, 2)), "fresh service response can still contain only offline computers");
        check("还没有连接电脑".equals(NativeWorkbenchPolicy.emptyActive(false, 0, 0, 0)), "no computer is distinct from an offline computer");
        check("暂时无法确认进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(false, 2, 1, 1)), "one online computer does not hide an uncertain selected task");
        check("暂无进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(false, 2, 2, 0)), "fresh known-empty state is retained");
        check("暂无进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(false, 2, 0, 0)), "unrelated offline computers do not change an empty selected scope");
        check("暂时无法确认进行中的任务".equals(NativeWorkbenchPolicy.emptyActive(false, 0, 0, 1)), "task with a missing computer is not a clean empty result");
        for (String selected : new String[]{"all", "codex", "claude"}) {
            for (String actual : new String[]{"codex", "claude"}) {
                boolean matches = selected.equals("all") || selected.equals(actual);
                for (String uncertain : new String[]{"unknown", "future_status", "", null}) {
                    check(NativeWorkbenchPolicy.uncertainTask(selected, actual, uncertain, false, false) == matches, "only unknown tasks in the selected tool affect the empty state");
                }
                for (String known : new String[]{"running", "waiting", "error", "idle", "completed"}) {
                    check(!NativeWorkbenchPolicy.uncertainTask(selected, actual, known, false, false), "fresh known task is not classified as uncertain");
                    check(NativeWorkbenchPolicy.uncertainTask(selected, actual, known, false, true) == matches, "missing or offline source cannot confirm the selected task state");
                }
                check(!NativeWorkbenchPolicy.uncertainTask(selected, actual, "unknown", true, true), "archived tasks never make the active empty state uncertain");
            }
        }
        check(!NativeScreenPolicy.includes("codex", "active", "codex", "running", false, true), "cached stale running is still excluded from confirmed activity");
    }
}
