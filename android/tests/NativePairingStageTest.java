package com.agentmonitor.live;

/** Offline reconstruction of one-use handoff phases across Activity/process interruption. */
public final class NativePairingStageTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        PairingGate gate = new PairingGate();
        check(gate.acquire(), "original Activity owns whole handoff");
        String phase = "pending";
        for (int i = 0; i < 100; i++) { check(!gate.acquire(), "recreated Activity cannot issue competing one-use claim"); check("wait".equals(NativePairingStage.action(phase, true, 1000, 60000)), "in-flight request keeps waiting"); }
        // Claim has been encrypted to disk before any exchange. A recreated owner must not poll it again.
        phase = "claimed";
        check("wait".equals(NativePairingStage.action(phase, true, 1000, 60000)), "claim persistence still covered by same gate");
        gate.release(); // simulate process ending after durable claim but before exchange
        check("exchange".equals(NativePairingStage.action(phase, false, 1000, 60000)), "new process resumes saved ticket instead of polling consumed request");
        check("reauthorize".equals(NativePairingStage.action(phase, false, 60000, 60000)), "expired ticket requires explicit connection");
        check(gate.acquire(), "restored claim can acquire ownership");
        phase = "exchanging";
        for (int i = 0; i < 100; i++) check("wait".equals(NativePairingStage.action(phase, true, 2000, 60000)), "Activity recreation cannot repeat exchange or bypass pending Cookie callback");
        gate.release(); // simulate process loss after request was sent, response outcome unknown
        check("reauthorize".equals(NativePairingStage.action(phase, false, 3000, 60000)), "uncertain one-use exchange never automatically retried");
        check(!NativePairingStage.connected("read_only", true, false, "existing-reader", 60000, 1000), "legacy read-only does not silently gain full access");
        check(!NativePairingStage.connected("full_app", false, false, "claimed-reader", 60000, 1000), "claimed reader and pending Cookie do not announce connected");
        check(NativePairingStage.connected("full_app", true, false, "ready-reader", 60000, 1000), "persisted Cookie completion enables full connection");
        check(!NativePairingStage.connected("full_app", true, true, "ready-reader", 60000, 1000), "offline logout cannot restore sensitive session after reopening");
        check(!NativePairingStage.connected("full_app", true, false, "ready-reader", 1000, 1000), "expired session no longer connected");
        check(!NativePairingStage.connected("full_app", true, false, "", 60000, 1000), "missing reader cannot be connected");
        System.out.println("NativePairingStageTest: " + checks + " checks passed");
    }
}
