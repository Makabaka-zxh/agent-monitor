package com.agentmonitor.live;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared by Activity instances; held until the one-use poll result is durably saved. */
public final class PairingGate {
    private final AtomicBoolean held = new AtomicBoolean();
    public boolean acquire() { return held.compareAndSet(false, true); }
    public void release() { held.set(false); }
}
