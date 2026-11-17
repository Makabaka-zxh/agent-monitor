package com.agentmonitor.live;

import java.text.BreakIterator;
import java.util.Locale;

/** Cuts the on-screen preview only. Copy and save still use the unchanged result. */
public final class NativeResultPreview {
    private static final int MAX_LINES = 12;

    private NativeResultPreview() { }

    public static int end(String text, int maxCharacters) {
        int limit = Math.min(text.length(), Math.max(0, maxCharacters));
        int lineBreaks = 0, lastLineBreak = -1;
        boolean hasContent = false;
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (ch != '\n' && ch != '\r') {
                if (!Character.isWhitespace(ch)) hasContent = true;
                continue;
            }
            int next = i + 1;
            if (ch == '\r' && next < text.length() && text.charAt(next) == '\n') next++;
            if (hasContent) {
                lastLineBreak = i;
                if (next < text.length() && ++lineBreaks == MAX_LINES) return i;
            }
            i = next - 1;
        }
        if (limit == text.length() || limit == 0) return limit;
        // Prefer a nearby complete line. An early newline must not hide the main body.
        if (lastLineBreak >= limit - limit / 3) return lastLineBreak;
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        return characters.isBoundary(limit) ? limit : Math.max(0, characters.preceding(limit));
    }
}
