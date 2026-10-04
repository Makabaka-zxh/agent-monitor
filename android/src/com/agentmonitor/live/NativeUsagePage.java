package com.agentmonitor.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native calendar history and source-scoped quotas. Ticks never rebuild charts. */
public final class NativeUsagePage {
    public static final String HELP = "Token 按北京时间统计已连接电脑采集到的记录，可能有历史缺口，并非完整账单。柱状图以周一至周日为一周，最多查看最近 90 天；— 表示未提供，0 表示已记录为零。点选日期可查看准确数字。\n\n套餐周额度是服务方提供的滚动窗口，与图表的日历周不同。百分比只使用服务方的真实读数，不用 Token 推算。额度按来源电脑分别查看，各电脑可能登录不同账号，不能合并。\n\n旧读数会标为上次记录。到达重置时间后等待新额度确认，不会自动显示为已恢复。缓存读取已包含在 Token 总量中，不重复相加。";
    public interface Help { void show(String title, String message); }
    public final LinearLayout view;
    private final NativeUi ui;
    private final Help help;
    private final Runnable retry, reminders;
    private final Map<String, Selection> selections = new LinkedHashMap<>();
    private final List<ProviderCard> cards = new ArrayList<>();
    private final List<QuotaRow> quotas = new ArrayList<>();
    private String signature = "";
    private TextView status, refresh;
    private JSONObject summary;
    private boolean busy, failed;
    private static final class Selection { LocalDate week, day; String source = ""; boolean pickerOpen; }
    private static final class Source {
        final String key, name; final List<JSONObject> windows = new ArrayList<>();
        Source(String key, String name) { this.key = key; this.name = name; }
    }
    public NativeUsagePage(NativeUi ui, Help help, Runnable retry, Runnable reminders) {
        this.ui = ui; this.help = help; this.retry = retry; this.reminders = reminders; view = ui.column(); render();
    }
    public void update(JSONObject value, boolean busy, boolean failed) {
        this.busy = busy; this.failed = failed;
        if (value == null) selections.clear();
        String next = value == null ? "" : value.toString(); summary = value;
        if (!next.equals(signature)) { signature = next; render(); }
        updateStatus(); tick();
    }
    public void tick() {
        long now = System.currentTimeMillis();
        for (ProviderCard card : cards) card.updateAmount(now);
        for (QuotaRow quota : quotas) quota.update(now);
    }
    private void render() {
        view.removeAllViews(); cards.clear(); quotas.clear();
        status = ui.text("", 14); status.setTextColor(ui.muted); view.addView(status, wrap()); view.addView(ui.space(12));
        JSONArray providers = summary == null ? null : summary.optJSONArray("providers");
        for (String tool : new String[]{"codex", "claude"}) {
            JSONObject data = null;
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject candidate = providers.optJSONObject(i);
                if (candidate != null && tool.equals(candidate.optString("tool"))) { data = candidate; break; }
            }
            ProviderCard card = new ProviderCard(tool, data); cards.add(card); view.addView(card.view, wrap()); view.addView(ui.space(14));
        }
        refresh = ui.button("刷新用量", false, retry); view.addView(refresh, wrap());
        view.addView(ui.space(10)); view.addView(ui.button("提醒设置", false, reminders), wrap()); updateStatus(); tick();
    }
    private void updateStatus() {
        String text = failed ? summary == null ? "暂时无法读取用量" : "连接暂不可用，显示上次记录"
                : busy ? "正在读取用量" : summary == null ? "等待用量记录" : "";
        setText(status, text); status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); status.setTextColor(failed ? ui.warning : ui.muted);
        refresh.setEnabled(!busy); refresh.setAlpha(busy ? .55f : 1f); setText(refresh, busy ? "正在更新" : "刷新用量");
    }
    private final class ProviderCard {
        final LinearLayout view = ui.card(), chartArea = ui.column(), quotaArea = ui.column();
        final JSONObject data; final Selection state;
        final TextView caption = ui.text("", 14), amount = ui.text("—", 26, true), unit = ui.text("Token", 13);
        final Map<String, Long> history = new LinkedHashMap<>();
        final List<Source> sources = new ArrayList<>();
        LocalDate start, today; boolean hasHistory; TextView weekName;
        ProviderCard(String tool, JSONObject data) {
            this.data = data; Selection found = selections.get(tool);
            if (found == null) { found = new Selection(); selections.put(tool, found); }
            state = found; today = NativeUsagePresentation.today(System.currentTimeMillis()); start = today;
            JSONObject series = data == null ? null : data.optJSONObject("history");
            if (series != null) {
                LocalDate lower = NativeUsagePresentation.date(series.optString("start_day"));
                LocalDate upper = NativeUsagePresentation.date(series.optString("end_day"));
                hasHistory = lower != null && upper != null && !lower.isAfter(upper) && !lower.isAfter(today);
                if (hasHistory) {
                    start = lower.isBefore(today.minusDays(89)) ? today.minusDays(89) : lower;
                    JSONArray days = series.optJSONArray("days");
                    for (int i = 0; days != null && i < Math.min(days.length(), 90); i++) {
                        JSONObject day = days.optJSONObject(i); if (day == null) continue;
                        LocalDate date = NativeUsagePresentation.date(day.optString("day"));
                        if (date != null && !date.isBefore(start) && !date.isAfter(today) && !date.isAfter(upper))
                            history.put(date.toString(), "unavailable".equals(day.optString("coverage")) ? null : number(day, "tokens"));
                    }
                }
            }
            LinearLayout heading = ui.row(); heading.addView(ui.brand(tool, 28));
            TextView name = ui.text(tool.equals("codex") ? "Codex" : "Claude Code", 17, true);
            LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(0, -2, 1); words.leftMargin = ui.dp(10); heading.addView(name, words);
            heading.addView(ui.iconButton("info", "查看" + name.getText() + "用量说明", () -> help.show(name.getText().toString(), providerHelp(tool, data, findSource(state.source)))));
            view.addView(heading, wrap()); view.addView(ui.space(8));
            caption.setTextColor(ui.muted); unit.setTextColor(ui.muted); amount.setSingleLine(true);
            amount.setAutoSizeTextTypeUniformWithConfiguration(18, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            view.addView(caption, wrap()); view.addView(ui.space(7)); view.addView(amount, wrap()); view.addView(ui.space(5)); view.addView(unit, wrap());
            view.addView(ui.space(10)); view.addView(chartArea, wrap()); renderWeek();
            view.addView(ui.space(14)); View divider = new View(ui.activity); divider.setBackgroundColor(ui.line); view.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
            view.addView(ui.space(12)); view.addView(quotaArea, wrap()); collectSources(); renderQuotas();
        }
        private void renderWeek() {
            chartArea.removeAllViews(); today = NativeUsagePresentation.today(System.currentTimeMillis());
            state.week = NativeUsagePresentation.clampWeek(state.week, start, today);
            state.day = NativeUsagePresentation.selection(state.day, state.week, start, today);
            LinearLayout nav = ui.row();
            View previous = ui.iconButton("back", "查看上一周", () -> moveWeek(-1));
            previous.setEnabled(state.week.isAfter(NativeUsagePresentation.monday(start))); previous.setAlpha(previous.isEnabled() ? 1f : .3f); nav.addView(previous);
            weekName = ui.text("", 14, true); weekName.setGravity(Gravity.CENTER); weekName.setMinHeight(ui.dp(48));
            weekName.setBackground(ui.ripple(android.graphics.Color.TRANSPARENT, 12)); weekName.setFocusable(true); weekName.setClickable(true);
            weekName.setAccessibilityDelegate(NativeUi.buttonAccessibility()); weekName.setContentDescription("返回本周");
            weekName.setOnClickListener(v -> { LocalDate current = NativeUsagePresentation.today(System.currentTimeMillis()); state.week = NativeUsagePresentation.monday(current); state.day = current; renderWeek(); });
            nav.addView(weekName, new LinearLayout.LayoutParams(0, -2, 1));
            View next = ui.iconButton("chevron", "查看下一周", () -> moveWeek(1));
            next.setEnabled(state.week.isBefore(NativeUsagePresentation.monday(today))); next.setAlpha(next.isEnabled() ? 1f : .3f); nav.addView(next);
            chartArea.addView(nav, wrap());
            Long[] amounts = new Long[7]; for (int i = 0; i < 7; i++) amounts[i] = history.get(state.week.plusDays(i).toString());
            chartArea.addView(new NativeUsageChart(ui, state.week, amounts, start, today, state.day, day -> { state.day = day; updateAmount(System.currentTimeMillis()); }), wrap());
            updateAmount(System.currentTimeMillis());
        }
        private void moveWeek(int delta) { state.week = state.week.plusWeeks(delta); state.day = null; renderWeek(); }
        void updateAmount(long now) {
            LocalDate current = NativeUsagePresentation.today(now);
            setText(caption, state.day.equals(current) ? "今日已记录" : NativeUsagePresentation.shortDay(state.day) + " 已记录");
            Long tokens = history.get(state.day.toString()); setText(amount, NativeUsagePresentation.exact(tokens));
            setText(unit, tokens != null ? "Token" : !hasHistory && busy ? "正在读取历史" : "暂无可用记录");
            if (weekName != null) setText(weekName, (state.week.equals(NativeUsagePresentation.monday(current)) ? "本周 · " : "") + NativeUsagePresentation.range(state.week));
        }
        private void collectSources() {
            Map<String, Source> found = new LinkedHashMap<>(); JSONArray windows = data == null ? null : data.optJSONArray("quotas");
            for (int i = 0; windows != null && i < Math.min(windows.length(), 200); i++) {
                JSONObject quota = windows.optJSONObject(i); if (quota == null) continue;
                String id = quota.optString("source_device_id").trim();
                // Missing source identity cannot authorize merging by a display name.
                String key = id.isEmpty() ? "unknown:" + quota.optString("key") + ":" + i : "device:" + id;
                Source source = found.get(key);
                if (source == null) { String name = quota.optString("source_name").trim(); source = new Source(key, name.isEmpty() ? "来源未命名" : name); found.put(key, source); }
                source.windows.add(quota);
            }
            sources.addAll(found.values());
            if (findSource(state.source) == null) state.pickerOpen = false;
            if (sources.size() <= 1) state.pickerOpen = false;
            if (findSource(state.source) == null && !sources.isEmpty()) {
                Source best = sources.get(0); for (Source item : sources) if (sourceScore(item) > sourceScore(best)) best = item;
                state.source = best.key;
            }
        }
        private long sourceScore(Source source) {
            long best = 0, now = System.currentTimeMillis();
            for (JSONObject quota : source.windows) best = Math.max(best, quotaRank(quota, now) * 100000000000000L + NativeApi.time(quota.optString("observed_at")));
            return best;
        }
        private Source findSource(String key) { for (Source source : sources) if (source.key.equals(key)) return source; return null; }
        private void renderQuotas() {
            for (int i = quotas.size() - 1; i >= 0; i--) if (quotas.get(i).owner == this) quotas.remove(i);
            quotaArea.removeAllViews(); Source source = findSource(state.source);
            if (source != null) {
                LinearLayout choices = ui.column(); choices.setVisibility(state.pickerOpen ? View.VISIBLE : View.GONE);
                TextView choose = ui.button("来源 · " + source.name + (sources.size() > 1 ? "  ▾" : ""), false,
                        () -> { if (sources.size() > 1) { state.pickerOpen = !state.pickerOpen; choices.setVisibility(state.pickerOpen ? View.VISIBLE : View.GONE); } else help.show("额度来源", source.name + "\n\n额度来自这台电脑登录的账号。不同电脑的百分比不合并。\n\n" + windowsHelp(source)); });
                choose.setTextSize(14); choose.setMaxLines(2); quotaArea.addView(choose, wrap());
                if (sources.size() > 1) for (Source item : sources) {
                    choices.addView(ui.space(6)); choices.addView(ui.button((item.key.equals(state.source) ? "✓  " : "") + item.name, false,
                            () -> { state.source = item.key; state.pickerOpen = false; renderQuotas(); }), wrap());
                }
                quotaArea.addView(choices, wrap()); quotaArea.addView(ui.space(14));
            }
            JSONObject week = pick(source, 10080), fiveHours = pick(source, 300);
            QuotaRow weekly = new QuotaRow(this, "周额度", week, true); quotaArea.addView(weekly.view, wrap()); quotas.add(weekly);
            quotaArea.addView(ui.space(16)); QuotaRow shortWindow = new QuotaRow(this, "5 小时额度", fiveHours, false);
            quotaArea.addView(shortWindow.view, wrap()); quotas.add(shortWindow);
            if (source != null && source.windows.size() > (week == null ? 0 : 1) + (fiveHours == null ? 0 : 1)) {
                quotaArea.addView(ui.space(10)); quotaArea.addView(ui.button("全部额度窗口", false, () -> help.show("额度窗口 · " + source.name, windowsHelp(source))), wrap());
            }
            weekly.update(System.currentTimeMillis()); shortWindow.update(System.currentTimeMillis());
        }
    }
    private JSONObject pick(Source source, long minutes) {
        JSONObject best = null; long now = System.currentTimeMillis();
        if (source != null) for (JSONObject candidate : source.windows) {
            if (candidate.optLong("window_minutes") != minutes) continue;
            if (best == null || quotaRank(candidate, now) > quotaRank(best, now)
                    || quotaRank(candidate, now) == quotaRank(best, now) && NativeApi.time(candidate.optString("observed_at")) > NativeApi.time(best.optString("observed_at"))) best = candidate;
        }
        return best;
    }
    private int quotaRank(JSONObject data, long now) {
        Double left = NativeUsageFormat.percent(data.opt("remaining_percent")), used = NativeUsageFormat.percent(data.opt("used_percent"));
        return NativeUsagePresentation.quotaRank(left != null || used != null, stale(data, now), NativeUsageFormat.epochMillis(data.opt("resets_at")), now);
    }
    private boolean stale(JSONObject data, long now) {
        return failed || data.optBoolean("stale", true) || !UsageAlertPolicy.observationFresh(NativeApi.time(data.optString("observed_at")), now);
    }
    private String windowsHelp(Source source) {
        StringBuilder message = new StringBuilder("各窗口分别计量，不能合并为一个余额。\n"); long now = System.currentTimeMillis();
        for (JSONObject window : source.windows) {
            long reset = NativeUsageFormat.epochMillis(window.opt("resets_at")); boolean old = stale(window, now);
            message.append("\n").append(NativeUsageFormat.window(window.optLong("window_minutes"), window.optString("label"))).append("\n")
                    .append(NativeUsageFormat.remaining(NativeUsageFormat.percent(window.opt("remaining_percent")), NativeUsageFormat.percent(window.opt("used_percent")), old, reset, now)).append("\n")
                    .append(NativeUsageFormat.reset(reset, now, old));
            String at = readableTime(window.optString("observed_at")); if (!at.isEmpty()) message.append("\n记录于 ").append(at);
            message.append("\n");
        }
        return message.toString();
    }
    private final class QuotaRow {
        final ProviderCard owner; final LinearLayout view = ui.column(); final TextView title, remaining, reset; final Meter meter;
        final JSONObject data; final String label; final boolean weekly;
        QuotaRow(ProviderCard owner, String label, JSONObject data, boolean weekly) {
            this.owner = owner; this.label = label; this.data = data; this.weekly = weekly;
            LinearLayout heading = ui.row(); title = ui.text(label, 14, true); title.setMaxLines(2);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); remaining = ui.text("", 14, true); remaining.setGravity(Gravity.END); heading.addView(remaining);
            view.addView(heading, wrap()); view.addView(ui.space(9)); meter = new Meter(); view.addView(meter, new LinearLayout.LayoutParams(-1, ui.dp(8)));
            view.addView(ui.space(7)); reset = ui.text("", 13); reset.setTextColor(ui.muted); view.addView(reset, wrap());
        }
        void update(long now) {
            if (data == null) { setText(title, label); setText(remaining, "未提供"); remaining.setTextColor(ui.muted); setText(reset, "等待电脑提供额度"); meter.set(null, true); return; }
            long resetTime = NativeUsageFormat.epochMillis(data.opt("resets_at")); boolean old = stale(data, now), expired = NativeUsageFormat.expired(resetTime, now);
            Double left = NativeUsageFormat.percent(data.opt("remaining_percent")), used = NativeUsageFormat.percent(data.opt("used_percent"));
            Double value = NativeUsagePresentation.remaining(left, used);
            setText(title, weekly && used != null && !expired ? "周额度" + (old ? " · 上次已用 " : "已用 ") + NativeUsageFormat.percentage(used) : label);
            setText(remaining, NativeUsageFormat.remaining(left, used, old, resetTime, now).replace("暂未提供", "未提供"));
            remaining.setTextColor(old || expired ? ui.muted : value != null && value <= 10 ? ui.warning : ui.text);
            setText(reset, NativeUsageFormat.reset(resetTime, now, old)); meter.set(expired ? null : value, old);
        }
    }
    private final class Meter extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); Double value; boolean old = true;
        Meter() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(Double next, boolean stale) { if ((value == null ? next != null : !value.equals(next)) || old != stale) { value = next; old = stale; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float radius = getHeight() / 2f; paint.setColor(ui.line);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, paint);
            if (value != null && value > 0) { paint.setColor(old ? ui.muted : value <= 10 ? ui.warning : ui.accent); paint.setAlpha(old ? 110 : 255);
                canvas.drawRoundRect(new RectF(0, 0, (float) (getWidth() * value / 100), getHeight()), radius, radius, paint); paint.setAlpha(255); }
        }
    }
    private String providerHelp(String tool, JSONObject data, Source source) {
        String day = summary == null ? "" : summary.optString("day");
        String message = (NativeUsageFormat.day(System.currentTimeMillis()).equals(day) ? "今日已记录：" : "上次已记录：") + NativeUsageFormat.exact(number(data, "today_tokens")) + " Token";
        Long sessions = number(data, "session_count"); if (sessions != null) message += "\n来自 " + sessions + " 个对话";
        String time = readableTime(data == null ? "" : data.optString("observed_at")); if (!time.isEmpty()) message += "\n最近记录：" + time;
        if (source != null) message += "\n\n额度来源：" + source.name + "\n" + windowsHelp(source);
        JSONArray checks = summary == null ? null : summary.optJSONArray("quota_checks");
        for (int i = 0; checks != null && i < Math.min(checks.length(), 20); i++) {
            JSONObject check = checks.optJSONObject(i);
            if (check == null || !tool.equals(check.optString("tool"))
                    || !NativeUsageFormat.quotaCheckSource(source == null ? null : source.key, check.optString("source_device_id"))) continue;
            String name = LivePresentation.clean(check.optString("source_name"), 60);
            message += "\n\n额度读取" + (name.isEmpty() ? "" : " · " + name) + "\n" + NativeUsageFormat.quotaCheck(check.optString("state"));
            String attempt = readableTime(check.optString("last_attempt_at"));
            if (!attempt.isEmpty()) message += "\n尝试于 " + attempt;
            long interval = check.optLong("retry_after_seconds");
            String state = check.optString("state");
            if (interval >= 300 && interval <= 3600 && (state.equals("no_live_data") || state.equals("unavailable")))
                message += "\n后续按 " + (interval + 59) / 60 + " 分钟间隔重试";
        }
        return message + "\n\n额度记录时间与读取尝试时间分别显示；尝试读取不会让旧额度变成新数据。\n\n" + HELP;
    }
    private static void setText(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    public static final class TaskCard {
        public final LinearLayout view;
        private final TextView value;
        private JSONObject usage;
        public TaskCard(NativeUi ui, Help help) {
            view = ui.card(); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = ui.column(); TextView heading = ui.text("本次对话已记录", 15, true); words.addView(heading, wrap()); words.addView(ui.space(7));
            value = ui.text("暂未提供", 15); value.setTextColor(ui.muted); words.addView(value, wrap()); view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            view.addView(ui.iconButton("info", "查看对话 Token 明细", () -> help.show("对话用量", taskHelp(usage))));
        }
        public void update(JSONObject task) {
            usage = task == null ? null : task.optJSONObject("usage"); Long total = number(usage, "total_tokens");
            String text = total == null ? "暂未提供" : NativeUsageFormat.compact(total) + " Token" + ("complete".equals(usage.optString("coverage")) ? "" : " · 部分记录");
            if (!text.contentEquals(value.getText())) value.setText(text);
        }
    }
    private static String taskHelp(JSONObject usage) {
        if (usage == null) return "这台电脑暂未提供此对话的用量记录。未提供不代表没有使用。";
        String message = "总量：" + NativeUsageFormat.exact(number(usage, "total_tokens")) + " Token"
                + "\n输入：" + NativeUsageFormat.exact(number(usage, "input_tokens"))
                + "\n输出：" + NativeUsageFormat.exact(number(usage, "output_tokens"))
                + "\n缓存读取：" + NativeUsageFormat.exact(number(usage, "cached_input_tokens"))
                + "\n缓存写入：" + NativeUsageFormat.exact(number(usage, "cache_write_tokens"));
        String time = readableTime(usage.optString("observed_at")); if (!time.isEmpty()) message += "\n最近记录：" + time;
        return message + "\n\n这是已采集到的对话记录，可能不是完整历史。缓存相关分类依工具定义，总量由采集端去重计算，不把分类重复相加。";
    }
    private static String readableTime(String value) {
        long time = NativeApi.time(value); if (time <= 0) return "";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai")); return format.format(new java.util.Date(time)) + "（北京时间）";
    }
    private static Long number(JSONObject object, String key) { return object == null ? null : NativeUsageFormat.count(object.opt(key)); }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
}
