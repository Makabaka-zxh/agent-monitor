package com.agentmonitor.live;

import java.util.Locale;

/** Pure matching tests with synthetic display fields; no account, files or network. */
public final class NativeTaskSearchPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static boolean match(String query, String title, String project, String device) {
        return NativeTaskSearchPolicy.matches(query, title, project, device);
    }
    public static void main(String[] args) {
        bounds(); whitespaceAndLiteralTerms(); caseAndCompatibility(); identityBoundaries();
        System.out.println("NativeTaskSearchPolicyTest: " + checks + " checks passed");
    }
    private static void bounds() {
        check(NativeTaskSearchPolicy.MAX_QUERY_LENGTH == 120, "query has an explicit UTF-16 limit");
        check("".equals(NativeTaskSearchPolicy.boundedQuery(null)), "null query is empty");
        for (String text : new String[]{"", "  原文  ", "MiXeD", "ＡＢＣ", "词\n词", "\ud83d\ude80"})
            check(text.equals(NativeTaskSearchPolicy.boundedQuery(text)), "display text is not normalized or trimmed");
        check(NativeTaskSearchPolicy.boundedQuery(repeat('a', 121)).equals(repeat('a', 120)), "long query is bounded");
        check(NativeTaskSearchPolicy.boundedQuery(repeat('a', 119) + "\ud83d\ude80tail").equals(repeat('a', 119)), "boundary never keeps half of a surrogate pair");
        check(NativeTaskSearchPolicy.boundedQuery(repeat('a', 118) + "\ud83d\ude80tail").equals(repeat('a', 118) + "\ud83d\ude80"), "whole supplementary character exactly fits");
        check(NativeTaskSearchPolicy.boundedQuery(repeat('a', 119) + "中tail").length() == 120, "BMP boundary preserved");
        check(match(repeat('a', 120) + "ignored", repeat('a', 120), "", ""), "matching applies the same bound as display state");
    }
    private static void whitespaceAndLiteralTerms() {
        for (String query : new String[]{null, "", " ", "\t\n\r", "\u0085", "\u00a0", "\u1680", "\u2003", "\u202f", "\u3000", " \u00a0\u3000\t"}) {
            check(!NativeTaskSearchPolicy.isSearching(query), "Unicode whitespace alone is not an active search");
            check(match(query, null, null, null), "empty query preserves all records");
        }
        check(match("修复 登录", "修复登录流程", "其他", "电脑"), "Chinese terms match one field");
        check(match("修复 Alpha 电脑", "修复登录流程", "Alpha/服务", "测试电脑"), "AND terms may span the three allowed fields");
        check(match("Alpha\u00a0Beta\u3000Gamma", "alpha", "beta", "gamma"), "Unicode whitespace separates terms");
        check(match("alpha alpha", "Alpha", "", ""), "duplicate term does not require duplicate occurrences");
        check(!match("alpha absent", "Alpha", "Beta", "Gamma"), "every term must match");
        check(!match("foobar", "foo", "bar", ""), "one word is never fabricated across field boundaries");
        for (String literal : new String[]{".*", "[abc]", "a+b", "(x)", "a?b", "^task$", "a|b", "C:\\work"}) {
            check(match(literal, "prefix " + literal + " suffix", "", ""), "punctuation is literal, not regex");
            check(!match(literal, "unrelated task", "", ""), "regex-like text cannot match arbitrary titles");
        }
        check(NativeTaskSearchPolicy.isSearching("中"), "Chinese query active");
        check(NativeTaskSearchPolicy.isSearching("\ud83d\ude80"), "supplementary query active");
        check(match("\ud83d\ude80", "发射\ud83d\ude80", "", ""), "supplementary code points match literally");
        check(!match("x", null, null, null), "missing fields do not invent searchable values");
    }
    private static void caseAndCompatibility() {
        check(match("TLS MONITOR", "Fix tls", "Monitor", ""), "case-insensitive matching");
        check(match("ＭＯＮＩＴＯＲ", "monitor", "", ""), "full-width query normalizes with NFKC");
        check(match("monitor", "Ｍｏｎｉｔｏｒ", "", ""), "full-width source normalizes with NFKC");
        check(match("fi", "\ufb01le", "", ""), "compatibility ligature folded");
        check(match("caf\u00e9", "cafe\u0301", "", ""), "canonical composed and decomposed text matches");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check(match("TITLE", "title", "", ""), "device locale does not change ROOT case folding");
        } finally { Locale.setDefault(original); }
    }
    private static void identityBoundaries() {
        check("  Search ".equals(NativeTaskSearchPolicy.restoreQuery("  Search ", "synthetic-a", "synthetic-a")), "same nonempty identity restores original display text");
        check(NativeTaskSearchPolicy.restoreQuery(repeat('q', 140), "synthetic-a", "synthetic-a").length() == 120, "restored state is bounded");
        for (String[] pair : new String[][]{{null, null}, {"", ""}, {"synthetic-a", null}, {null, "synthetic-a"}, {"synthetic-a", ""}, {"", "synthetic-a"}, {"synthetic-a", "synthetic-b"}})
            check("".equals(NativeTaskSearchPolicy.restoreQuery("private query", pair[0], pair[1])), "unknown, logged-out or changed identity cannot restore query");
        check("".equals(NativeTaskSearchPolicy.restoreQuery(null, "synthetic-a", "synthetic-a")), "missing saved query harmless");
    }
    private static String repeat(char c, int count) { char[] text = new char[count]; java.util.Arrays.fill(text, c); return new String(text); }
}
