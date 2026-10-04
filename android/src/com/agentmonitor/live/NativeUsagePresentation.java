package com.agentmonitor.live;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Calendar weeks describe recorded tokens, never a provider's rolling quota. */
public final class NativeUsagePresentation {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private NativeUsagePresentation() { }
    public static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException invalid) { return null; }
    }
    public static LocalDate today(long now) { return Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate(); }
    public static LocalDate monday(LocalDate day) { return day.minusDays(day.getDayOfWeek().getValue() - 1); }
    public static LocalDate clampWeek(LocalDate selected, LocalDate start, LocalDate today) {
        LocalDate lower = monday(start), upper = monday(today);
        LocalDate result = selected == null ? upper : monday(selected);
        return result.isBefore(lower) ? lower : result.isAfter(upper) ? upper : result;
    }
    public static LocalDate selection(LocalDate selected, LocalDate week, LocalDate start, LocalDate end) {
        LocalDate lower = week.isBefore(start) ? start : week;
        LocalDate upper = week.plusDays(6).isAfter(end) ? end : week.plusDays(6);
        if (selected != null && !selected.isBefore(lower) && !selected.isAfter(upper)) return selected;
        return upper;
    }
    public static double ratio(Long value, long maximum) {
        if (value == null || value < 0 || maximum <= 0) return 0;
        return Math.max(0, Math.min(1, (double) value / maximum));
    }
    public static Double remaining(Double left, Double used) {
        Double valid = NativeUsageFormat.percent(left);
        if (valid != null) return valid;
        valid = NativeUsageFormat.percent(used);
        return valid == null ? null : 100 - valid;
    }
    public static int quotaRank(boolean hasPercent, boolean stale, long reset, long now) {
        if (!hasPercent) return 0;
        if (NativeUsageFormat.expired(reset, now)) return 1;
        return stale ? 2 : 3;
    }
    public static String range(LocalDate week) { return shortDay(week) + " – " + shortDay(week.plusDays(6)); }
    public static String shortDay(LocalDate day) { return day.getMonthValue() + "." + day.getDayOfMonth(); }
    public static String exact(Long value) { return value == null ? "—" : NativeUsageFormat.exact(value); }
}
