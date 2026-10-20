package com.agentmonitor.live;

/** Search visibility is local presentation state; it never changes the query. */
public final class NativeSearchPresentationPolicy {
    private NativeSearchPresentationPolicy() { }

    public static boolean restoreExpanded(boolean expanded, String query, String savedIdentity, String currentIdentity) {
        if (currentIdentity == null || currentIdentity.isEmpty() || !currentIdentity.equals(savedIdentity)) return false;
        // A restored filter must be visible even if it was folded before recreation.
        return expanded || !NativeTaskSearchPolicy.boundedQuery(query).isEmpty();
    }
}
