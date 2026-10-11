package com.agentmonitor.live;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local literal matching of the task fields already present in the workbench. */
public final class NativeTaskSearchPolicy {
    public static final int MAX_QUERY_LENGTH = 120;

    /** Keep the user's display text; never split a supplementary character at the limit. */
    public static String boundedQuery(String raw) {
        if (raw == null) return "";
        if (raw.length() <= MAX_QUERY_LENGTH) return raw;
        int end = MAX_QUERY_LENGTH;
        if (Character.isHighSurrogate(raw.charAt(end - 1)) && Character.isLowSurrogate(raw.charAt(end))) end--;
        return raw.substring(0, end);
    }

    public static boolean isSearching(String raw) { return compile(raw).terms.length != 0; }

    /** Saved UI state belongs only to the same nonempty account identity. */
    public static String restoreQuery(String raw, String savedIdentity, String currentIdentity) {
        return savedIdentity != null && !savedIdentity.isEmpty() && savedIdentity.equals(currentIdentity)
                ? boundedQuery(raw) : "";
    }

    public static boolean matches(String query, String title, String project, String deviceName) {
        return compile(query).matches(title, project, deviceName);
    }

    static Query compile(String raw) {
        String normalized = normalize(boundedQuery(raw));
        List<String> terms = new ArrayList<>(); int start = 0;
        for (int index = 0; index <= normalized.length(); index++) {
            if (index == normalized.length() || normalized.charAt(index) == ' ') {
                if (index > start) terms.add(normalized.substring(start, index));
                start = index + 1;
            }
        }
        return new Query(terms.toArray(new String[0]));
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(folded.length());
        for (int index = 0; index < folded.length();) {
            int point = folded.codePointAt(index); index += Character.charCount(point);
            if (point == 0x85 || Character.isWhitespace(point) || Character.isSpaceChar(point)) result.append(' ');
            else result.appendCodePoint(point);
        }
        return result.toString();
    }

    static final class Query {
        private final String[] terms;
        private Query(String[] terms) { this.terms = terms; }
        boolean matches(String title, String project, String deviceName) {
            if (terms.length == 0) return true;
            String first = normalize(title), second = normalize(project), third = normalize(deviceName);
            for (String term : terms)
                if (!first.contains(term) && !second.contains(term) && !third.contains(term)) return false;
            return true;
        }
    }

    private NativeTaskSearchPolicy() { }
}
