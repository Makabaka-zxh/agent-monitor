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
    static final int TITLE_LINE_UNITS = 48;
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
        return options.title && !cleanTitle.isEmpty() ? fit(cleanTitle, TITLE_LINE_UNITS) : brand;
    }
    /** One provenance line, shared by task headers and notification surfaces. */
    static String identity(String brand, String computer, boolean showComputer) {
        String source = clean(brand, 24), name = showComputer ? clean(computer, 96) : "";
        return source + (name.isEmpty() ? "" : " · " + name);
    }
    static String compactIdentity(String brand, String computer, boolean showComputer) {
        return fit(identity(brand, computer, showComputer), TITLE_LINE_UNITS);
    }
    /** Public text is an exact, finite vocabulary, never a task or server-supplied sentence. */
    static String publicState(String state) {
        if (state == null) return "状态待确认";
        switch (state) {
            case "执行中": return "执行中";
            case "等待批准":
            case "等待批准，请在电脑上处理": return "等待批准";
            case "正在更新任务状态":
            case "正在确认任务状态":
            case "正在确认": return "正在确认";
            case "更新暂停，正在重连":
            case "正在重连": return "正在重连";
            case "本轮结束": return "本轮结束";
            case "任务已归档，已停止跟踪":
            case "任务已归档": return "任务已归档";
            case "电脑已离线，已停止跟踪":
            case "电脑已离线": return "电脑已离线";
            case "执行出错，请打开工作台查看":
            case "执行出错": return "执行出错";
            case "任务已空闲，已停止跟踪":
            case "任务已空闲": return "任务已空闲";
            case "连接中断，已停止跟踪":
            case "连接已中断": return "连接已中断";
            case "状态未能确认，已停止跟踪":
            case "状态未确认，跟踪已停止": return "状态未确认，跟踪已停止";
            case "系统已结束本次跟踪":
            case "系统已结束跟踪": return "系统已结束跟踪";
            case "已停止跟踪":
            case "跟踪已结束": return "跟踪已结束";
            default:
                // TrackingPolicy produces this exact numeric sentence for a bounded duration.
                if (state.matches("[1-9][0-9]{0,3} 分钟跟踪已结束")) {
                    int minutes = Integer.parseInt(state.substring(0, state.indexOf(' ')));
                    if (minutes <= 1439) return "跟踪已结束";
                }
                return "状态待确认";
        }
    }
    /** State always leads; optional duration uses the existing monotonic tracking clock. */
    static String statusLine(String state, boolean elapsed, long elapsedMillis) {
        String label = publicState(state);
        if (!elapsed) return label;
        String time = "已跟踪 " + duration(Math.max(0, elapsedMillis), false);
        // Keep state and complete elapsed meaning together; compact a long stopped-state label.
        if (width(label + " · " + time) > CAPSULE_LINE_UNITS && "状态未确认，跟踪已停止".equals(label)) label = "跟踪已停止";
        return fit(label, Math.max(4, CAPSULE_LINE_UNITS - width(time) - 3)) + " · " + time;
    }
    static String expanded(String brand, String state, Data data, Options options, long elapsedMillis, long now) {
        List<String> lines = new ArrayList<>();
        lines.add(statusLine(state, options.elapsed, elapsedMillis));
        String usage = "";
        boolean partial = false;
        boolean sessionShown = options.session && data.session != null, todayShown = options.today && data.today != null;
        if (sessionShown) { usage = "本次 " + number(data.session); partial = data.sessionPartial; }
        if (todayShown) {
            usage += (usage.isEmpty() ? "" : " · ") + "今日 " + number(data.today); partial |= data.todayPartial;
        }
        if (!usage.isEmpty()) {
            String line = sessionShown && todayShown ? (partial ? "已记 " : "") + usage : (partial ? "已记 Token · " : "Token · ") + usage;
            // Keep all digits and coverage semantics. Spaces are optional only for extreme values.
            if (width(line) > CAPSULE_LINE_UNITS) line = line.replace(" · ", "·").replace("本次 ", "本次").replace("今日 ", "今日");
            if (width(line) > CAPSULE_LINE_UNITS) line = line.replace("已记 Token · ", "已记Token ").replace("Token · ", "Token ");
            lines.add(line);
        }
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
            String core = label + "余 " + remaining + " · " + duration(quota.reset - now, true) + "后重置";
            if (width(core) > CAPSULE_LINE_UNITS) core = label + "余" + remaining + "·" + duration(quota.reset - now, true) + "重置";
            // Already restricted to this task's device. Its optional identity header carries
            // attribution; repeating the name here clips the reset in a two-line capsule.
            lines.add(core);
        }
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
