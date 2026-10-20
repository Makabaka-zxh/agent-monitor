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
        System.out.println("NativeSearchPresentationPolicyTest: " + checks + " checks passed");
    }
}
