package com.agentmonitor.live;

/**
 * Classifies removal of one notification presentation within a bounded session.
 * Callers serialize all events and preserve the original session deadline.
 */
public final class NotificationRecoveryPolicy {
    public static final long CLASSIFY_WAIT_MS = 2000L;
    public enum Decision { IGNORE, WAIT, RESTORE, END }

    private final long epoch, deadline;
    private final String sessionNonce;
    private String presentationNonce;
    private boolean pending, ended;
    private long pendingUntil;

    public NotificationRecoveryPolicy(long epoch, String sessionNonce, String presentationNonce, long deadline) {
        if (sessionNonce == null || sessionNonce.isEmpty() || presentationNonce == null || presentationNonce.isEmpty()) {
            throw new IllegalArgumentException("Session and presentation nonces must not be empty");
        }
        this.epoch = epoch;
        this.sessionNonce = sessionNonce;
        this.presentationNonce = presentationNonce;
        this.deadline = deadline;
    }

    public long epoch() { return epoch; }
    public String sessionNonce() { return sessionNonce; }
    public String presentationNonce() { return presentationNonce; }
    public boolean isPending() { return pending; }
    public boolean isEnded() { return ended; }

    /** Explicit stop follows the session even after its presentation changes. */
    public boolean matchesSession(long candidateEpoch, String candidateSession) {
        return !ended && epoch == candidateEpoch && sessionNonce.equals(candidateSession);
    }
    public boolean matches(long candidateEpoch, String candidateSession, String candidatePresentation) {
        return matchesSession(candidateEpoch, candidateSession) && presentationNonce.equals(candidatePresentation);
    }

    public void end() { ended = true; pending = false; pendingUntil = 0; }

    public Decision onDismiss(long candidateEpoch, String candidateSession, String candidatePresentation,
                              long now, boolean listenerReady) {
        if (!matches(candidateEpoch, candidateSession, candidatePresentation)) return Decision.IGNORE;
        if (expired(now) || !listenerReady) return terminate();
        if (!pending) {
            pending = true;
            long classificationLimit = now > Long.MAX_VALUE - CLASSIFY_WAIT_MS
                    ? Long.MAX_VALUE : now + CLASSIFY_WAIT_MS;
            pendingUntil = Math.min(deadline, classificationLimit);
        }
        return Decision.WAIT;
    }

    public Decision onRemoved(long candidateEpoch, String candidateSession, String candidatePresentation,
                              boolean clearAll, long now, boolean eligible, String nextPresentationNonce) {
        if (!matches(candidateEpoch, candidateSession, candidatePresentation)) return Decision.IGNORE;
        if (expired(now) || !clearAll || !eligible || nextPresentationNonce == null
                || nextPresentationNonce.isEmpty() || presentationNonce.equals(nextPresentationNonce)) {
            return terminate();
        }
        // Rotate before callers repost, so late deletion callbacks cannot end the new presentation.
        presentationNonce = nextPresentationNonce;
        pending = false;
        pendingUntil = 0;
        return Decision.RESTORE;
    }

    public Decision tick(long now) {
        if (ended) return Decision.IGNORE;
        return expired(now) ? terminate() : Decision.IGNORE;
    }

    private boolean expired(long now) { return now >= deadline || (pending && now >= pendingUntil); }
    private Decision terminate() { end(); return Decision.END; }
}