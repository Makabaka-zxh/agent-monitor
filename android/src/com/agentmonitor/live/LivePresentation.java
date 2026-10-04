package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/** Whitelisted, bounded display text. Missing usage is omitted instead of rendered as zero. */
final class LivePresentation {
    static final long QUOTA_FRESH_MS = 15 * 60 * 1000L;
    static final int CAPSULE_LINE_UNITS = 32;
    static final class Options {
        final boolean title, computer, elapsed, session, today, quota;
        Options(boolean title, boolean computer, boolean elapsed, boolean session, boolean today, boolean quota) {
            this.title = title; this.computer = computer; this.elapsed = elapsed;
            this.session = session; this.today = today; this.quota = quota;
        }
    }
    static final class Data {
        String computer = "";
        Long session, today;
        boolean sessionPartial, todayPartial;
        final List<Quota> quotas = new ArrayList<>();
    }
    static final class Quota {
        final String label, source;
        final double remaining;
        final long reset, observed;
        Quota(String label, double remaining, long reset, long observed) {
            this(label, "", remaining, reset, observed);
        }
        Quota(String label, String source, double remaining, long reset, long observed) {
            this.source = source;
            this.label = label; this.remaining = remaining; this.reset = reset; this.observed = observed;
        }
    }
    static Data read(JSONObject snapshot, JSONObject task, String tool) {
        Data result = new Data();
        if (snapshot == null || task == null) return result;
        JSONArray devices = snapshot.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && !task.optString("device_id").isEmpty()
                    && task.optString("device_id").equals(device.optString("id"))) {
                result.computer = clean(device.optString("name"), 32); break;
            }
        }
        JSONObject usage = task.optJSONObject("usage");
        if (usage != null) {
            result.session = tokens(usage.opt("total_tokens"));
            result.sessionPartial = !"complete".equals(usage.optString("coverage"));
        }
        JSONObject all = snapshot.optJSONObject("usage");
        JSONArray providers = all == null ? null : all.optJSONArray("providers");
        if (providers == null) return result;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject provider = providers.optJSONObject(i);
            if (provider == null || !tool.equals(provider.optString("tool"))) continue;
            result.today = tokens(provider.opt("today_tokens"));
            result.todayPartial = !"complete".equals(provider.optString("coverage"));
            JSONArray quotas = provider.optJSONArray("quotas");
            if (quotas != null) for (int j = 0; j < Math.min(quotas.length(), 32); j++) {
                JSONObject quota = quotas.optJSONObject(j);
                if (quota == null || quota.optBoolean("stale", true)) continue;
                String taskDevice = task.optString("device_id");
                if (taskDevice.isEmpty() || !taskDevice.equals(quota.optString("source_device_id"))) continue;
                Object remainingValue = quota.opt("remaining_percent"), resetValue = quota.opt("resets_at");
                if (!(remainingValue instanceof Number) || !(resetValue instanceof Number)) continue;
                double remaining = ((Number) remainingValue).doubleValue(), reset = ((Number) resetValue).doubleValue();
                if (!Double.isFinite(remaining) || remaining < 0 || remaining > 100 || !Double.isFinite(reset)
                        || reset <= 0 || reset != Math.floor(reset) || reset > Long.MAX_VALUE / 1000L) continue;
                long observed;
                try { observed = Instant.parse(quota.optString("observed_at")).toEpochMilli(); }
                catch (RuntimeException invalid) { continue; }
                String label = clean(quota.optString("label"), 16);
                Object windowValue = quota.opt("window_minutes");
                if (windowValue instanceof Number) {
                    double minutes = ((Number) windowValue).doubleValue();
                    if (Double.isFinite(minutes) && minutes > 0 && minutes <= 366L * 1440 && minutes == Math.floor(minutes)) {
                        long window = (long) minutes;
                        label = window % 1440 == 0 ? window / 1440 + "天" : window % 60 == 0 ? window / 60 + "时" : window + "分";
                    }
                }
                String source = clean(quota.optString("source_name"), 12);
                label = label.isEmpty() ? "额度" : label;
                result.quotas.add(new Quota(label, source, remaining, (long) reset * 1000L, observed));
            }
            break;
        }
        return result;
    }
    static String title(String title, String brand, Options options) {
        String cleanTitle = clean(title, 96);
        return options.title && !cleanTitle.isEmpty() ? cleanTitle : brand;
    }
    static String expanded(String brand, String state, Data data, Options options, long elapsedMillis, long now) {
        List<String> lines = new ArrayList<>();
        String usage = "";
        boolean partial = false;
        boolean sessionShown = options.session && data.session != null, todayShown = options.today && data.today != null;
        if (sessionShown) { usage = "本次" + number(data.session); partial = data.sessionPartial; }
        if (todayShown) {
            usage += (usage.isEmpty() ? "" : "·") + "今日" + number(data.today); partial |= data.todayPartial;
        }
        // Some OEM capsules reveal only two body lines. Put opted-in useful numbers first.
        if (!usage.isEmpty()) lines.add(sessionShown && todayShown ? (partial ? "已记 " : "") + usage : (partial ? "已记Token " : "Token ") + usage);
        List<Quota> fresh = new ArrayList<>();
        if (options.quota) for (Quota quota : data.quotas) {
            if (now - quota.observed > QUOTA_FRESH_MS || quota.observed - now > 60000 || quota.reset <= now) continue;
            fresh.add(quota);
        }
        fresh.sort(Comparator.comparingDouble((Quota quota) -> quota.remaining).thenComparingLong(quota -> quota.reset));
        for (int i = 0; i < Math.min(2, fresh.size()); i++) {
            Quota quota = fresh.get(i);
            String label = fit(quota.label.replace("小时", "时").replace("分钟", "分").replace(" ", ""), 6);
            String remaining = percent(quota.remaining).replace("不足 ", "<").replace("超过 ", ">");
            String core = label + "余" + remaining + "·" + duration(quota.reset - now, true) + "重置";
            // Already restricted to this task's device. Its optional computer row carries
            // attribution; repeating the name here clips the reset in a two-line capsule.
            lines.add(core);
        }
        String stateLine = fit(clean(brand, 24) + " · " + clean(state, 48), CAPSULE_LINE_UNITS);
        String elapsed = options.elapsed ? "已跟踪" + duration(Math.max(0, elapsedMillis), false) : "";
        String context = options.computer ? fit(data.computer, elapsed.isEmpty() ? CAPSULE_LINE_UNITS : CAPSULE_LINE_UNITS - width(elapsed) - 4) : "";
        context = join(context, elapsed);
        // With no available usage, preserve the original state-first presentation.
        if (lines.isEmpty()) { lines.add(stateLine); if (!context.isEmpty()) lines.add(context); }
        else { if (!context.isEmpty()) lines.add(context); lines.add(stateLine); }
        StringBuilder result = new StringBuilder();
        for (String line : lines) { if (result.length() > 0) result.append('\n'); result.append(line); }
        return result.toString();
    }
    static String clean(String value, int limit) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length() && out.length() < limit; ) {
            int point = value.codePointAt(index); index += Character.charCount(point);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT
                    || Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
            } else if (out.length() + Character.charCount(point) <= limit) out.appendCodePoint(point);
        }
        return out.toString().trim();
    }
    private static Long tokens(Object value) {
        if (!(value instanceof Number)) return null;
        double numeric = ((Number) value).doubleValue();
        if (!Double.isFinite(numeric) || numeric < 0 || numeric != Math.floor(numeric) || numeric > 9007199254740991d) return null;
        return ((Number) value).longValue();
    }
    private static String number(long value) {
        if (value < 10000) return String.valueOf(value);
        long divisor = value < 100000000L ? 10000L : value < 1000000000000L ? 100000000L : 1000000000000L;
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), divisor == 1000000000000L ? 0 : 1, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + (divisor == 10000L ? "万" : divisor == 100000000L ? "亿" : "万亿");
    }
    static String percent(double value) {
        if (value > 0 && value < 1) return "不足 1%";
        if (value > 99 && value < 100) return "超过 99%";
        return String.format(Locale.ROOT, "%.0f%%", Math.floor(value));
    }
    private static String join(String first, String second) { return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second; }
    private static String duration(long millis, boolean countdown) {
        long minutes = countdown ? (millis + 59999) / 60000 : millis / 60000;
        if (minutes == 0) return "不足1分";
        if (minutes < 60) return minutes + "分";
        long hours = minutes / 60;
        if (hours < 24) return hours + "时" + (minutes % 60 == 0 ? "" : minutes % 60 + "分");
        return hours / 24 + "天" + (hours % 24 == 0 ? "" : hours % 24 + "时");
    }
    // Approximate full-width glyph budget; never cut a surrogate pair or the numeric meaning.
    static int width(String value) {
        int size = 0;
        for (int i = 0; i < value.length();) { int point = value.codePointAt(i); i += Character.charCount(point); size += point < 128 ? 1 : 2; }
        return size;
    }
    private static String fit(String value, int units) {
        if (width(value) <= units) return value;
        StringBuilder out = new StringBuilder(); int size = 0;
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i), needed = point < 128 ? 1 : 2;
            if (size + needed > units - 2) break;
            out.appendCodePoint(point); size += needed; i += Character.charCount(point);
        }
        return out.toString().trim() + "…";
    }
}
