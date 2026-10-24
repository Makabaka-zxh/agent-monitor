package com.agentmonitor.live;

/** Search presentation is local state; it never changes the query. */
public final class NativeSearchPresentationPolicy {
    private NativeSearchPresentationPolicy() { }

    public static boolean restoreExpanded(boolean expanded, String query, String savedIdentity, String currentIdentity) {
        if (currentIdentity == null || currentIdentity.isEmpty() || !currentIdentity.equals(savedIdentity)) return false;
        // A restored filter must be visible even if it was folded before recreation.
        return expanded || !NativeTaskSearchPolicy.boundedQuery(query).isEmpty();
    }

    /** A one-use editing intent, separate from merely making a saved query visible. */
    public static final class FocusRestore {
        private final String identity;
        private final long pageRevision;
        private final int start, end;
        private boolean pending = true;

        private FocusRestore(String identity, long pageRevision, int start, int end) {
            this.identity = identity; this.pageRevision = pageRevision; this.start = start; this.end = end;
        }

        public boolean pending() { return pending; }

        public int[] take(String page, boolean expanded, String currentIdentity, long currentRevision,
                          boolean resumed, boolean windowFocused, int textLength) {
            if (!pending) return null;
            if (!"tasks".equals(page) || !expanded || !identity.equals(currentIdentity)
                    || pageRevision != currentRevision) { pending = false; return null; }
            // Lifecycle and window-focus callbacks can arrive in either order.
            if (!resumed || !windowFocused) return null;
            pending = false;
            int length = Math.max(0, textLength);
            return new int[]{offset(start, length), offset(end, length)};
        }

        private static int offset(int saved, int length) {
            return saved < 0 ? length : Math.min(saved, length);
        }
    }

    public static FocusRestore restoreFocus(String savedPage, boolean expanded, boolean focused,
                                             String savedIdentity, String currentIdentity, long pageRevision,
                                             int selectionStart, int selectionEnd) {
        if (!"tasks".equals(savedPage) || !expanded || !focused || currentIdentity == null
                || currentIdentity.isEmpty() || !currentIdentity.equals(savedIdentity)) return null;
        return new FocusRestore(currentIdentity, pageRevision, selectionStart, selectionEnd);
    }
}
