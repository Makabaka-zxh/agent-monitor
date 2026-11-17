package com.agentmonitor.live;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static Double used(Double used, Double left) {
        Double valid = NativeUsageFormat.percent(used);
        if (valid != null) return valid;
        valid = NativeUsageFormat.percent(left);
        return valid == null ? null : 100 - valid;
    }
    /** Missing or expired readings do not describe an available quota window. */
    public static boolean quotaVisible(Double left, Double used, long reset, long now) {
        return remaining(left, used) != null && !NativeUsageFormat.expired(reset, now);
    }
    /** Unknown resets have no scheduled expiry; known future resets trigger quota-only refresh. */
    public static long nextQuotaExpiry(long current, long reset, long now) {
        return reset > now && reset > 0 ? Math.min(current, reset) : current;
    }
    public static String quotaIdentity(String bucket, String key, long minutes, int ordinal) {
        String group = bucket == null ? "" : bucket.trim(), window = key == null ? "" : key.trim();
        // Length prefixes avoid ambiguous concatenation of provider-issued keys.
        return group.length() + ":" + group + window.length() + ":" + window + ":" + minutes
                + (group.isEmpty() && window.isEmpty() ? ":" + ordinal : "");
    }
    public static final class ModelShare {
        public final String model;
        public final String displayName;
        public final long tokens;
        public final double percent;
        private ModelShare(String model, long tokens, long total) {
            this.model = model; this.tokens = tokens; this.percent = (double) tokens / total * 100;
            this.displayName = model == null ? "未识别模型" : NativeUsageFormat.modelName(model);
        }
    }
    /** Distinct snapshots may share a product name; keep their recorded amounts separate. */
    public static Map<String, String> modelVariantNotes(ModelShare[] shares) {
        Map<String, Integer> nameCounts = new HashMap<>(), variantCounts = new HashMap<>();
        for (ModelShare share : shares) {
            if (share.model == null) continue;
            String variant = NativeUsageFormat.modelVariant(share.model);
            nameCounts.merge(share.displayName, 1, Integer::sum);
            variantCounts.merge(share.displayName + "\n" + variant, 1, Integer::sum);
        }
        Map<String, String> notes = new HashMap<>();
        for (ModelShare share : shares) {
            if (share.model == null || nameCounts.get(share.displayName) < 2) continue;
            String variant = NativeUsageFormat.modelVariant(share.model);
            notes.put(share.model, variantCounts.get(share.displayName + "\n" + variant) == 1 ? variant : share.model);
        }
        return notes;
    }
    /** Shares use one day's recorded tokens, including the explicitly unattributed remainder. */
    public static ModelShare[] modelShares(String[] names, Long[] values, Long unknown, Long total) {
        if (names == null || values == null || names.length == 0 || names.length != values.length
                || names.length > 100 || total == null || total <= 0 || unknown == null || unknown < 0) return new ModelShare[0];
        List<ModelShare> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); long sum = unknown;
        for (int i = 0; i < names.length; i++) {
            String model = names[i]; Long tokens = values[i];
            if (model == null || model.trim().isEmpty() || model.length() > 120 || !model.equals(model.trim())
                    || tokens == null || tokens < 0 || !seen.add(model)) return new ModelShare[0];
            for (int at = 0; at < model.length(); at++) if (Character.isISOControl(model.charAt(at))) return new ModelShare[0];
            if (tokens > Long.MAX_VALUE - sum) return new ModelShare[0];
            sum += tokens; result.add(new ModelShare(model, tokens, total));
        }
        // A partial/mismatched breakdown must not be normalized into an apparent complete split.
        if (sum != total) return new ModelShare[0];
        result.sort((a, b) -> Long.compare(b.tokens, a.tokens));
        if (unknown > 0) result.add(new ModelShare(null, unknown, total));
        return result.toArray(new ModelShare[0]);
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
