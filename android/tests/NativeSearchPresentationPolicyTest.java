package com.agentmonitor.live;

public final class NativeSearchPresentationPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("search visibility boundary " + checks); }
    public static void main(String[] args) {
        for (String query : new String[]{null, "", " ", "monitor", "alpha beta", "搜索", "ＡＢＣ"}) {
            for (boolean expanded : new boolean[]{false, true}) {
                check(!NativeSearchPresentationPolicy.restoreExpanded(expanded, query, "a", "b"));
                check(!NativeSearchPresentationPolicy.restoreExpanded(expanded, query, "", ""));
                check(!NativeSearchPresentationPolicy.restoreExpanded(expanded, query, null, null));
                check(!NativeSearchPresentationPolicy.restoreExpanded(expanded, query, null, "a"));
                check(NativeSearchPresentationPolicy.restoreExpanded(expanded, query, "a", "a") == (expanded || query != null && !query.isEmpty()));
            }
        }
        // Visibility transitions never normalize, clear, or substitute a user's query.
        String query = "  MiXeD 搜索  ";
        check(NativeSearchPresentationPolicy.restoreExpanded(false, query, "a", "a"));
        check(query.equals("  MiXeD 搜索  "));
        editingFocus();
        System.out.println("NativeSearchPresentationPolicyTest: " + checks + " checks passed");
    }

    private static NativeSearchPresentationPolicy.FocusRestore editing(int start, int end) {
        return NativeSearchPresentationPolicy.restoreFocus("tasks", true, true, "a", "a", 7, start, end);
    }

    private static void editingFocus() {
        // A visible restored query is not itself permission to focus the input.
        for (String page : new String[]{null, "", "usage", "task", "account"})
            check(NativeSearchPresentationPolicy.restoreFocus(page, true, true, "a", "a", 7, 1, 2) == null);
        check(NativeSearchPresentationPolicy.restoreFocus("tasks", false, true, "a", "a", 7, 1, 2) == null);
        check(NativeSearchPresentationPolicy.restoreFocus("tasks", true, false, "a", "a", 7, 1, 2) == null);
        for (String identity : new String[]{null, "", "b"})
            check(NativeSearchPresentationPolicy.restoreFocus("tasks", true, true, "a", identity, 7, 1, 2) == null);

        NativeSearchPresentationPolicy.FocusRestore restore = editing(1, 4);
        check(restore.take("tasks", true, "a", 7, false, true, 6) == null);
        check(restore.pending());
        int[] selection = restore.take("tasks", true, "a", 7, true, true, 6);
        check(selection[0] == 1 && selection[1] == 4);
        check(!restore.pending());
        // A later resume/window-focus event cannot steal focus again.
        check(restore.take("tasks", true, "a", 7, true, true, 6) == null);
        restore = editing(4, 1);
        check(restore.take("tasks", true, "a", 7, true, false, 6) == null);
        selection = restore.take("tasks", true, "a", 7, true, true, 6);
        check(selection[0] == 4 && selection[1] == 1);

        // Leaving then returning, collapsing then opening, and account changes cancel intent.
        for (int changed = 0; changed < 4; changed++) {
            restore = editing(1, 2);
            check(restore.take(changed == 0 ? "usage" : "tasks", changed != 1,
                    changed == 2 ? "b" : "a", changed == 3 ? 8 : 7, false, false, 6) == null);
            check(!restore.pending());
            check(restore.take("tasks", true, "a", 7, true, true, 6) == null);
        }
        // Android selection offsets use UTF-16 positions; clamp to the restored bounded text.
        selection = editing(-1, 999).take("tasks", true, "a", 7, true, true, 3);
        check(selection[0] == 3 && selection[1] == 3);
        selection = editing(9, 1).take("tasks", true, "a", 7, true, true, 0);
        check(selection[0] == 0 && selection[1] == 0);
        selection = editing(2, 2).take("tasks", true, "a", 7, true, true, "\ud83d\ude00x".length());
        check(selection[0] == 2 && selection[1] == 2);
    }
}
