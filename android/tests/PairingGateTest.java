package com.agentmonitor.live;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the production gate across two Activity-like owners and a delayed one-use reply. */
public final class PairingGateTest {
    public static void main(String[] args) throws Exception {
        PairingGate gate = new PairingGate();
        CountDownLatch requested = new CountDownLatch(1), allowResponse = new CountDownLatch(1), saved = new CountDownLatch(1);
        AtomicInteger pollRequests = new AtomicInteger();
        AtomicReference<String> storage = new AtomicReference<>("pending");
        Thread destroyedActivity = new Thread(() -> {
            if (!gate.acquire()) throw new AssertionError("first request must acquire");
            pollRequests.incrementAndGet(); requested.countDown();
            try { allowResponse.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            storage.set("reader connection saved"); // persistence precedes release, as in Activity
            gate.release(); saved.countDown();
        });
        destroyedActivity.setDaemon(true); destroyedActivity.start(); if (!requested.await(2, TimeUnit.SECONDS)) throw new AssertionError("first request did not start");
        for (int i = 0; i < 100; i++) {
            if (gate.acquire()) throw new AssertionError("a recreated Activity duplicated an in-flight one-use poll");
        }
        allowResponse.countDown(); if (!saved.await(2, TimeUnit.SECONDS)) throw new AssertionError("response did not complete"); destroyedActivity.join(2000);
        if (!"reader connection saved".equals(storage.get()) || pollRequests.get() != 1) throw new AssertionError("late response lost connection");
        if (!gate.acquire()) throw new AssertionError("completed poll failed to release gate");
        gate.release();
        System.out.println("PairingGateTest: delayed response + 100 competing lifecycle attempts passed");
    }
}

