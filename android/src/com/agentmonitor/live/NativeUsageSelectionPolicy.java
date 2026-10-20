package com.agentmonitor.live;

import java.time.LocalDate;

/** Same-account display choices only: no views, credentials, usage values or requests. */
public final class NativeUsageSelectionPolicy {
    private NativeUsageSelectionPolicy() { }

    public static final class Selection {
        public LocalDate week, day;
        public String source = "";
    }

    public static final class Calendar {
        public final LocalDate week, day;
        private Calendar(LocalDate week, LocalDate day) { this.week = week; this.day = day; }
    }

    public static Selection restore(String week, String day, String source) {
        Selection selection = new Selection();
        selection.week = NativeUsagePresentation.date(week);
        selection.day = NativeUsagePresentation.date(day);
        selection.source = source(source);
        return selection;
    }

    public static String source(String value) {
        if (value == null || value.length() > 1024) return "";
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return "";
        return value;
    }

    /** Loading/absent history can show today without erasing a restored selection. */
    public static Calendar calendar(Selection selection, LocalDate start, LocalDate today, boolean hasHistory) {
        LocalDate week = NativeUsagePresentation.clampWeek(hasHistory ? selection.week : null, start, today);
        LocalDate day = NativeUsagePresentation.selection(hasHistory ? selection.day : null, week, start, today);
        if (hasHistory) { selection.week = week; selection.day = day; }
        return new Calendar(week, day);
    }

    /** Keep a valid source; an authoritative new snapshot may remove it. */
    public static String sourceAfterSnapshot(String selected, String[] available, String preferred, boolean snapshotAvailable) {
        String current = source(selected);
        if (!snapshotAvailable) return current;
        if (available == null || available.length == 0) return "";
        for (String candidate : available) if (current.equals(candidate) && !current.isEmpty()) return current;
        String fallback = source(preferred);
        for (String candidate : available) if (fallback.equals(candidate) && !fallback.isEmpty()) return fallback;
        for (String candidate : available) if (!source(candidate).isEmpty()) return candidate;
        return "";
    }
}
