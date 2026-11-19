package com.agentmonitor.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
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
    public static final String HELP = "Token 按北京时间统计已连接电脑采集到的记录，可能有历史缺口，并非完整账单\n柱状图以周一至周日为一周，最多查看最近 90 天；— 表示未提供，0 表示已记录为零\n点选日期可查看准确数字\n\n模型构成是所选日期各模型占已采集 Token 总量的比例，其他模型也计入分母；不是官方额度消耗比例\n未提供模型记录时不显示该区块\n\n官方额度显示服务方实际返回的窗口及额度组，已用百分比只使用真实读数，不用 Token 推算\n未提供的窗口或模型不显示，也不按套餐名称猜测\n额度按来源电脑分别查看，各电脑可能登录不同账号，不能合并\n模型专属或共享额度组与全账号窗口分别计量\n\n未过期的旧读数会标为上次已用\n无有效读数或已到重置时间的窗口不显示，收到新的有效额度后再显示；不会自动当作已恢复\n缓存读取已包含在 Token 总量中，不重复相加";
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
    private LinearLayout officialSection, localSection;
    private JSONObject summary;
    private boolean busy, failed;
    private static final class Selection {
        NativeUsageSelectionPolicy.Selection saved = new NativeUsageSelectionPolicy.Selection();
        boolean pickerOpen;
    }
    private static final class Source {
        final String key, name; final List<JSONObject> windows = new ArrayList<>();
        Source(String key, String name) { this.key = key; this.name = name; }
    }
    public NativeUsagePage(NativeUi ui, Help help, Runnable retry, Runnable reminders) {
        this.ui = ui; this.help = help; this.retry = retry; this.reminders = reminders; view = ui.column(); render();
    }
    /** The Activity owns the identity boundary and discards this on account changes. */
    public Bundle saveState() {
        Bundle saved = new Bundle();
        for (String tool : new String[]{"codex", "claude", "antigravity"}) {
            Selection state = selections.get(tool); if (state == null) continue;
            Bundle item = new Bundle();
            if (state.saved.week != null) item.putString("week", state.saved.week.toString());
            if (state.saved.day != null) item.putString("day", state.saved.day.toString());
            item.putString("source", NativeUsageSelectionPolicy.source(state.saved.source)); saved.putBundle(tool, item);
        }
        return saved;
    }
    public void restoreState(Bundle saved) {
        selections.clear();
        for (String tool : new String[]{"codex", "claude", "antigravity"}) {
            Bundle item = saved == null ? null : saved.getBundle(tool); if (item == null) continue;
            Selection state = new Selection();
            state.saved = NativeUsageSelectionPolicy.restore(item.getString("week", ""), item.getString("day", ""), item.getString("source", ""));
            selections.put(tool, state);
        }
        render();
    }
    public void update(JSONObject value, boolean busy, boolean failed) {
        this.busy = busy; this.failed = failed;
        String next = value == null ? "" : value.toString(); summary = value;
        if (!next.equals(signature)) { signature = next; render(); }
        updateStatus(); tick();
    }
    public void tick() {
        long now = System.currentTimeMillis();
        for (ProviderCard card : cards) { card.updateAmount(now); card.updateQuotas(now); }
        for (QuotaRow quota : quotas) quota.update(now);
        updateSectionVisibility(); updateStatus();
    }
    private void render() {
        view.removeAllViews(); cards.clear(); quotas.clear();
        status = ui.text("", 13); status.setTextColor(ui.muted);
        LinearLayout.LayoutParams statusLayout = wrap(); statusLayout.bottomMargin = ui.dp(16); view.addView(status, statusLayout);
        officialSection = ui.column(); TextView officialTitle = ui.heading("官方额度", 20);
        officialTitle.setContentDescription("官方额度 · 服务方实际读数，各窗口独立已用百分比");
        officialSection.addView(officialTitle, wrap());
        view.addView(officialSection, wrap());
        localSection = ui.column(); TextView localTitle = ui.heading("本地 Token", 20);
        localTitle.setContentDescription("本地使用 · 已记录 Token，与官方额度分别统计，可能有历史缺口");
        localSection.addView(localTitle, wrap());
        view.addView(localSection, wrap());
        JSONArray providers = summary == null ? null : summary.optJSONArray("providers");
        for (String tool : new String[]{"codex", "claude", "antigravity"}) {
            JSONObject data = null;
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject candidate = providers.optJSONObject(i);
                if (candidate != null && tool.equals(candidate.optString("tool"))) { data = candidate; break; }
            }
            ProviderCard card = new ProviderCard(tool, data); cards.add(card);
            LinearLayout.LayoutParams quotaLayout = wrap(); quotaLayout.topMargin = ui.dp(20); officialSection.addView(card.quotaView, quotaLayout);
            LinearLayout.LayoutParams localLayout = wrap(); localLayout.topMargin = ui.dp(24); localSection.addView(card.view, localLayout);
        }
        LinearLayout actions = ui.row();
        refresh = ui.quietButton("刷新用量", retry); actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(ui.quietButton("提醒设置", reminders), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionsLayout = wrap(); actionsLayout.topMargin = ui.dp(24); view.addView(actions, actionsLayout); tick();
    }
    private void updateSectionVisibility() {
        boolean official = false, local = false;
        for (ProviderCard card : cards) {
            official |= card.quotaView.getVisibility() == View.VISIBLE;
            local |= card.view.getVisibility() == View.VISIBLE;
        }
        officialSection.setVisibility(official ? View.VISIBLE : View.GONE);
        localSection.setVisibility(local ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams spacing = (LinearLayout.LayoutParams) localSection.getLayoutParams();
        int gap = official ? ui.dp(36) : 0;
        if (spacing.topMargin != gap) { spacing.topMargin = gap; localSection.setLayoutParams(spacing); }
    }
    private void updateStatus() {
        String text = failed ? summary == null ? "暂时无法读取用量" : "连接暂不可用，显示上次记录"
                : busy ? "正在读取用量" : summary == null ? "等待用量记录"
                : officialSection.getVisibility() != View.VISIBLE && localSection.getVisibility() != View.VISIBLE ? "暂无可用用量记录" : "";
        setText(status, text); status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); status.setTextColor(failed ? ui.warning : ui.muted);
        refresh.setEnabled(!busy); refresh.setAlpha(busy ? .55f : 1f); setText(refresh, busy ? "正在更新" : "刷新用量");
    }
    private final class ProviderCard {
        final String tool;
        final LinearLayout view = ui.column(), quotaView = ui.card(), chartArea = ui.column(), modelArea = ui.column(), quotaArea = ui.column();
        final JSONObject data; final Selection state;
        final TextView caption = ui.text("", 13), amount = ui.text("—", 24, true), unit = ui.text("Token", 13);
        final Map<String, Long> history = new LinkedHashMap<>();
        final Map<String, JSONObject> modelHistory = new LinkedHashMap<>();
        final List<Source> sources = new ArrayList<>();
        String modelsDay = "";
        long nextQuotaExpiry = Long.MAX_VALUE;
        LocalDate start, today, displayedWeek, displayedDay; boolean hasHistory, hasRecordedHistory; TextView weekName;
        ProviderCard(String tool, JSONObject data) {
            this.tool = tool; this.data = data; Selection found = selections.get(tool);
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
                        if (date != null && !date.isBefore(start) && !date.isAfter(today) && !date.isAfter(upper)) {
                            history.put(date.toString(), "unavailable".equals(day.optString("coverage")) ? null : number(day, "tokens"));
                            modelHistory.put(date.toString(), day);
                        }
                    }
                }
            }
            for (Long recorded : history.values()) if (recorded != null) { hasRecordedHistory = true; break; }
            quotaView.addView(providerHeading(tool, "官方额度说明"), wrap()); quotaView.addView(quotaArea, wrap());
            view.addView(providerHeading(tool, "本地使用说明"), wrap()); view.addView(ui.space(16));
            caption.setTextColor(ui.muted); unit.setTextColor(ui.muted);
            // Let long totals wrap at the user's font scale instead of shrinking the main number.
            view.addView(caption, wrap()); view.addView(ui.space(12)); view.addView(amount, wrap()); view.addView(ui.space(8)); view.addView(unit, wrap());
            view.addView(ui.space(20)); view.addView(chartArea, wrap()); renderWeek();
            view.addView(modelArea, wrap()); view.setVisibility(hasRecordedHistory ? View.VISIBLE : View.GONE);
            renderQuotas();
        }
        private LinearLayout providerHeading(String tool, String description) {
            LinearLayout heading = ui.row(); heading.addView(ui.brand(tool, 24));
            TextView name = ui.heading("codex".equals(tool) ? "Codex" : "antigravity".equals(tool) ? "Antigravity" : "Claude Code", 17);
            LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(0, -2, 1); words.setMarginStart(ui.dp(8)); heading.addView(name, words);
            TextView details = ui.quietButton(description,
                    () -> help.show(name.getText().toString(), providerHelp(tool, data, findSource(state.saved.source))));
            details.setContentDescription("查看" + name.getText() + description);
            heading.addView(details);
            return heading;
        }
        private void renderWeek() {
            chartArea.removeAllViews(); today = NativeUsagePresentation.today(System.currentTimeMillis());
            NativeUsageSelectionPolicy.Calendar calendar = NativeUsageSelectionPolicy.calendar(state.saved, start, today, hasHistory);
            displayedWeek = calendar.week; displayedDay = calendar.day;
            LinearLayout nav = ui.row();
            View previous = ui.iconButton("back", "查看上一周", () -> moveWeek(-1));
            previous.setEnabled(displayedWeek.isAfter(NativeUsagePresentation.monday(start))); previous.setAlpha(previous.isEnabled() ? 1f : .3f); nav.addView(previous);
            weekName = ui.text("", 14); weekName.setTypeface(ui.medium); weekName.setTextColor(ui.muted); weekName.setGravity(Gravity.CENTER); weekName.setMinHeight(ui.dp(48));
            weekName.setBackground(ui.ripple(android.graphics.Color.TRANSPARENT, 12)); weekName.setFocusable(true); weekName.setClickable(true);
            weekName.setAccessibilityDelegate(NativeUi.buttonAccessibility()); weekName.setContentDescription("返回本周");
            weekName.setOnClickListener(v -> { LocalDate current = NativeUsagePresentation.today(System.currentTimeMillis()); state.saved.week = NativeUsagePresentation.monday(current); state.saved.day = current; renderWeek(); });
            nav.addView(weekName, new LinearLayout.LayoutParams(0, -2, 1));
            View next = ui.iconButton("chevron", "查看下一周", () -> moveWeek(1));
            next.setEnabled(displayedWeek.isBefore(NativeUsagePresentation.monday(today))); next.setAlpha(next.isEnabled() ? 1f : .3f); nav.addView(next);
            chartArea.addView(nav, wrap());
            Long[] amounts = new Long[7]; for (int i = 0; i < 7; i++) amounts[i] = history.get(displayedWeek.plusDays(i).toString());
            chartArea.addView(new NativeUsageChart(ui, displayedWeek, amounts, start, today, displayedDay, day -> { state.saved.week = displayedWeek; state.saved.day = day; displayedDay = day; updateAmount(System.currentTimeMillis()); }), wrap());
            updateAmount(System.currentTimeMillis());
        }
        private void moveWeek(int delta) { state.saved.week = displayedWeek.plusWeeks(delta); state.saved.day = null; renderWeek(); }
        void updateAmount(long now) {
            LocalDate current = NativeUsagePresentation.today(now);
            setText(caption, displayedDay.equals(current) ? "今日已记录" : NativeUsagePresentation.shortDay(displayedDay) + " 已记录");
            Long tokens = history.get(displayedDay.toString()); setText(amount, NativeUsagePresentation.exact(tokens));
            setText(unit, tokens != null ? "Token" : !hasHistory && busy ? "正在读取历史" : "暂无可用记录");
            if (weekName != null) setText(weekName, (displayedWeek.equals(NativeUsagePresentation.monday(current)) ? "本周 · " : "") + NativeUsagePresentation.range(displayedWeek));
            renderModels();
        }
        private void renderModels() {
            String dayKey = displayedDay.toString(); if (dayKey.equals(modelsDay)) return;
            modelsDay = dayKey; modelArea.removeAllViews();
            JSONObject day = modelHistory.get(dayKey); JSONArray models = day == null ? null : day.optJSONArray("models");
            int count = models == null ? 0 : models.length();
            if (count == 0 || count > 100) { modelArea.setVisibility(View.GONE); return; }
            String[] names = new String[count]; Long[] values = new Long[count];
            for (int i = 0; i < count; i++) {
                JSONObject model = models.optJSONObject(i); if (model == null) continue;
                names[i] = model.optString("model", ""); values[i] = number(model, "tokens");
            }
            NativeUsagePresentation.ModelShare[] shares = NativeUsagePresentation.modelShares(names, values,
                    number(day, "unknown_model_tokens"), history.get(dayKey));
            modelArea.setVisibility(shares.length == 0 ? View.GONE : View.VISIBLE); if (shares.length == 0) return;
            modelArea.addView(ui.space(24)); TextView modelTitle = ui.heading("模型 Token 占比", 16);
            modelTitle.setContentDescription("模型构成 · 所选日期各模型占已采集 Token 总量的比例，其他模型也计入分母，不是官方额度消耗比例");
            modelArea.addView(modelTitle, wrap());
            Map<String, String> variantNotes = NativeUsagePresentation.modelVariantNotes(shares);
            for (NativeUsagePresentation.ModelShare share : shares) {
                modelArea.addView(ui.space(20)); LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
                TextView name = ui.text(share.displayName, 14);
                LinearLayout.LayoutParams modelName = new LinearLayout.LayoutParams(0, -2, 1); modelName.setMarginEnd(ui.dp(12)); row.addView(name, modelName);
                TextView proportion = ui.text(NativeUsageFormat.percentage(share.percent), 16); proportion.setTypeface(ui.medium); proportion.setGravity(Gravity.END); row.addView(proportion);
                modelArea.addView(row, wrap()); modelArea.addView(ui.space(8));
                String detail = NativeUsageFormat.exact(share.tokens) + " Token";
                if (variantNotes.containsKey(share.model)) detail += " · " + variantNotes.get(share.model);
                TextView total = ui.text(detail, 13); total.setTextColor(ui.muted); modelArea.addView(total, wrap());
            }
        }
        private void collectSources(long now) {
            sources.clear(); nextQuotaExpiry = Long.MAX_VALUE;
            Map<String, Source> found = new LinkedHashMap<>(); JSONArray windows = data == null ? null : data.optJSONArray("quotas");
            for (int i = 0; windows != null && i < Math.min(windows.length(), 200); i++) {
                JSONObject quota = windows.optJSONObject(i); if (quota == null) continue;
                long reset = NativeUsageFormat.epochMillis(quota.opt("resets_at"));
                if (!NativeUsagePresentation.quotaVisible(NativeUsageFormat.percent(quota.opt("remaining_percent")),
                        NativeUsageFormat.percent(quota.opt("used_percent")), reset, now)) continue;
                nextQuotaExpiry = NativeUsagePresentation.nextQuotaExpiry(nextQuotaExpiry, reset, now);
                String id = quota.optString("source_device_id").trim();
                // Missing source identity cannot authorize merging by a display name.
                String key = id.isEmpty() ? "unknown:" + quota.optString("key") + ":" + i : "device:" + id;
                Source source = found.get(key);
                if (source == null) { String name = quota.optString("source_name").trim(); source = new Source(key, name.isEmpty() ? "来源未命名" : name); found.put(key, source); }
                source.windows.add(quota);
            }
            sources.addAll(found.values());
            String[] available = new String[sources.size()]; Source best = null;
            for (int index = 0; index < sources.size(); index++) {
                Source item = sources.get(index); available[index] = item.key;
                if (best == null || sourceScore(item, now) > sourceScore(best, now)) best = item;
            }
            String next = NativeUsageSelectionPolicy.sourceAfterSnapshot(state.saved.source, available, best == null ? "" : best.key, summary != null);
            if (!next.equals(state.saved.source) || sources.size() <= 1) state.pickerOpen = false;
            state.saved.source = next;
        }
        private long sourceScore(Source source, long now) {
            long best = 0;
            for (JSONObject quota : source.windows) best = Math.max(best, quotaRank(quota, now) * 100000000000000L + NativeApi.time(quota.optString("observed_at")));
            return best;
        }
        private Source findSource(String key) { for (Source source : sources) if (source.key.equals(key)) return source; return null; }
        void updateQuotas(long now) { if (now >= nextQuotaExpiry) renderQuotas(now); }
        private void renderQuotas() { renderQuotas(System.currentTimeMillis()); }
        private void renderQuotas(long now) {
            collectSources(now);
            for (int i = quotas.size() - 1; i >= 0; i--) if (quotas.get(i).owner == this) quotas.remove(i);
            quotaArea.removeAllViews(); Source source = findSource(state.saved.source);
            if (source == null) {
                String fallback = emptyQuotaNote(tool, hasRecordedHistory);
                quotaView.setVisibility(fallback.isEmpty() ? View.GONE : View.VISIBLE);
                if (!fallback.isEmpty()) {
                    quotaArea.addView(ui.space(12));
                    TextView note = ui.text(fallback, 13);
                    note.setTextColor(ui.muted);
                    quotaArea.addView(note, wrap());
                }
                return;
            }
            quotaView.setVisibility(View.VISIBLE);
            if (source != null) {
                LinearLayout choices = ui.column(); choices.setVisibility(state.pickerOpen ? View.VISIBLE : View.GONE);
                TextView choose = ui.quietButton("来源 · " + source.name + (sources.size() > 1 ? "  ▾" : ""),
                        () -> { if (sources.size() > 1) { state.pickerOpen = !state.pickerOpen; choices.setVisibility(state.pickerOpen ? View.VISIBLE : View.GONE); } else help.show("额度来源", source.name + "\n\n额度来自这台电脑登录的账号\n不同电脑的百分比不合并\n\n" + windowsHelp(source)); });
                choose.setTextSize(13); choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); choose.setPadding(0, ui.dp(10), 0, ui.dp(10)); quotaArea.addView(choose, wrap());
                if (sources.size() > 1) for (Source item : sources) {
                    TextView choice = ui.quietButton((item.key.equals(state.saved.source) ? "✓  " : "") + item.name,
                            () -> { state.saved.source = item.key; state.pickerOpen = false; renderQuotas(); });
                    choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    if (item.key.equals(state.saved.source)) { choice.setTextColor(ui.accent); choice.setBackground(ui.ripple(ui.soft, 10)); }
                    choices.addView(choice, wrap());
                }
                quotaArea.addView(choices, wrap()); quotaArea.addView(ui.space(12));
            }
            List<JSONObject> windows = visibleWindows(source, now);
            for (int i = 0; i < windows.size(); i++) {
                if (i > 0) quotaArea.addView(ui.space(24));
                QuotaRow row = new QuotaRow(this, quotaLabel(windows.get(i)), windows.get(i));
                quotaArea.addView(row.view, wrap()); quotas.add(row); row.update(now);
            }
        }
    }
    private List<JSONObject> visibleWindows(Source source, long now) {
        Map<String, JSONObject> best = new LinkedHashMap<>(); int ordinal = 0;
        if (source != null) for (JSONObject candidate : source.windows) {
            if (!NativeUsagePresentation.quotaVisible(NativeUsageFormat.percent(candidate.opt("remaining_percent")),
                    NativeUsageFormat.percent(candidate.opt("used_percent")), NativeUsageFormat.epochMillis(candidate.opt("resets_at")), now)) continue;
            // Same-duration windows from different model/usage buckets are independent.
            String bucket = candidate.optString("bucket_id").trim(), key = candidate.optString("key").trim();
            String identity = NativeUsagePresentation.quotaIdentity(bucket, key, candidate.optLong("window_minutes"), ordinal);
            ordinal++; JSONObject previous = best.get(identity);
            if (previous == null || quotaRank(candidate, now) > quotaRank(previous, now)
                    || quotaRank(candidate, now) == quotaRank(previous, now) && NativeApi.time(candidate.optString("observed_at")) > NativeApi.time(previous.optString("observed_at"))) best.put(identity, candidate);
        }
        return new ArrayList<>(best.values());
    }
    private String quotaLabel(JSONObject data) {
        String model = data.opt("model") instanceof String ? LivePresentation.clean(data.optString("model"), 120) : "";
        String bucket = data.opt("bucket_label") instanceof String ? LivePresentation.clean(data.optString("bucket_label"), 120) : "";
        String label = NativeUsageFormat.window(data.optLong("window_minutes"), "额度窗口");
        // The provider's meter label is presentation only; it never infers a model identity.
        String name = NativeUsageFormat.modelName(bucket.isEmpty() ? model : bucket);
        return name.isEmpty() ? label : name + " · " + label;
    }
    private int quotaRank(JSONObject data, long now) {
        Double left = NativeUsageFormat.percent(data.opt("remaining_percent")), used = NativeUsageFormat.percent(data.opt("used_percent"));
        return NativeUsagePresentation.quotaRank(left != null || used != null, stale(data, now), NativeUsageFormat.epochMillis(data.opt("resets_at")), now);
    }
    private boolean stale(JSONObject data, long now) {
        return failed || data.optBoolean("stale", true) || !UsageAlertPolicy.observationFresh(NativeApi.time(data.optString("observed_at")), now);
    }
    private String windowsHelp(Source source) {
        StringBuilder message = new StringBuilder("各窗口分别计量，不能合并为一个余额\n"); long now = System.currentTimeMillis();
        for (JSONObject window : visibleWindows(source, now)) {
            long reset = NativeUsageFormat.epochMillis(window.opt("resets_at")); boolean old = stale(window, now);
            message.append("\n").append(quotaLabel(window)).append("\n")
                    .append(NativeUsageFormat.remaining(NativeUsageFormat.percent(window.opt("remaining_percent")), NativeUsageFormat.percent(window.opt("used_percent")), old, reset, now)).append("\n")
                    .append(NativeUsageFormat.reset(reset, now, old));
            String at = readableTime(window.optString("observed_at")); if (!at.isEmpty()) message.append("\n记录于 ").append(at);
            message.append("\n");
        }
        return message.toString();
    }
    private final class QuotaRow {
        final ProviderCard owner; final LinearLayout view = ui.column(); final TextView title, remaining, reset; final Meter meter;
        final JSONObject data; final String label;
        QuotaRow(ProviderCard owner, String label, JSONObject data) {
            this.owner = owner; this.label = label; this.data = data;
            title = ui.text(label, 14); title.setTypeface(ui.medium);
            remaining = ui.text("", 26, true);
            view.addView(title, wrap()); view.addView(ui.space(12)); view.addView(remaining, wrap());
            view.addView(ui.space(12)); meter = new Meter(); view.addView(meter, new LinearLayout.LayoutParams(-1, ui.dp(5)));
            view.addView(ui.space(12)); reset = ui.text("", 13); reset.setTextColor(ui.muted); view.addView(reset, wrap());
        }
        void update(long now) {
            if (data == null) { view.setVisibility(View.GONE); return; }
            long resetTime = NativeUsageFormat.epochMillis(data.opt("resets_at")); boolean old = stale(data, now);
            Double left = NativeUsageFormat.percent(data.opt("remaining_percent")), used = NativeUsageFormat.percent(data.opt("used_percent"));
            boolean visible = NativeUsagePresentation.quotaVisible(left, used, resetTime, now);
            view.setVisibility(visible ? View.VISIBLE : View.GONE); if (!visible) return;
            Double value = NativeUsagePresentation.used(used, left);
            setText(title, label);
            String prefix = old ? "上次已用 " : "已用 ";
            String reading = prefix + NativeUsageFormat.percentage(value);
            if (!reading.contentEquals(remaining.getText())) {
                android.text.SpannableString styled = new android.text.SpannableString(reading);
                styled.setSpan(new android.text.style.RelativeSizeSpan(14f / 26f), 0, prefix.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                remaining.setText(styled);
            }
            remaining.setTextColor(old ? ui.muted : value != null && value >= 90 ? ui.warning : ui.text);
            setText(reset, NativeUsageFormat.reset(resetTime, now, old)); meter.set(value, old);
        }
    }
    private final class Meter extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); Double value; boolean old = true;
        Meter() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(Double next, boolean stale) { if ((value == null ? next != null : !value.equals(next)) || old != stale) { value = next; old = stale; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float radius = getHeight() / 2f; paint.setColor(ui.line);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, paint);
            if (value != null && value > 0) { paint.setColor(old ? ui.muted : value >= 90 ? ui.warning : ui.accent); paint.setAlpha(old ? 110 : 255);
                canvas.drawRoundRect(new RectF(0, 0, (float) (getWidth() * value / 100), getHeight()), radius, radius, paint); paint.setAlpha(255); }
        }
    }
    private String emptyQuotaNote(String tool, boolean hasRecordedHistory) {
        JSONArray checks = summary == null ? null : summary.optJSONArray("quota_checks");
        for (int i = 0; checks != null && i < Math.min(checks.length(), 20); i++) {
            JSONObject check = checks.optJSONObject(i);
            if (check == null || !tool.equals(check.optString("tool"))) continue;
            String name = LivePresentation.clean(check.optString("source_name"), 60);
            String prefix = name.isEmpty() ? "" : "来源 · " + name + "\n";
            return prefix + NativeUsageFormat.quotaCheck(check.optString("state"));
        }
        if (!hasRecordedHistory) return "";
        if ("claude".equals(tool)) return "暂未收到官方额度读数 · 在电脑端登录 Claude Code 或开启状态栏同步后显示";
        if ("antigravity".equals(tool)) return "暂未收到官方额度读数 · 打开电脑端 Antigravity 后自动同步";
        return "";
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
        return message + "\n\n额度记录时间与读取尝试时间分别显示；尝试读取不会让旧额度变成新数据\n\n" + HELP;
    }
    private static void setText(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    public static final class TaskCard {
        public final LinearLayout view;
        private final TextView value;
        private JSONObject usage;
        public TaskCard(NativeUi ui, Help help) {
            view = ui.row(); view.setPadding(0, ui.dp(10), 0, ui.dp(10)); view.setVisibility(View.GONE);
            LinearLayout words = ui.column(); TextView heading = ui.text("本次对话已记录", 13); heading.setTextColor(ui.muted); words.addView(heading, wrap()); words.addView(ui.space(6));
            value = ui.text("", 18, true); words.addView(value, wrap()); view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView details = ui.quietButton("查看明细", () -> help.show("对话用量", taskHelp(usage)));
            details.setContentDescription("查看对话 Token 明细");
            view.addView(details);
        }
        public void update(JSONObject task) {
            usage = task == null ? null : task.optJSONObject("usage"); Long total = number(usage, "total_tokens");
            view.setVisibility(total == null ? View.GONE : View.VISIBLE);
            String text = total == null ? "" : NativeUsageFormat.compact(total) + " Token" + ("complete".equals(usage.optString("coverage")) ? "" : " · 部分记录");
            if (!text.contentEquals(value.getText())) value.setText(text);
        }
    }
    private static String taskHelp(JSONObject usage) {
        if (usage == null) return "这台电脑暂未提供此对话的用量记录\n未提供不代表没有使用";
        String message = "总量：" + NativeUsageFormat.exact(number(usage, "total_tokens")) + " Token"
                + "\n输入：" + NativeUsageFormat.exact(number(usage, "input_tokens"))
                + "\n输出：" + NativeUsageFormat.exact(number(usage, "output_tokens"))
                + "\n缓存读取：" + NativeUsageFormat.exact(number(usage, "cached_input_tokens"))
                + "\n缓存写入：" + NativeUsageFormat.exact(number(usage, "cache_write_tokens"));
        String time = readableTime(usage.optString("observed_at")); if (!time.isEmpty()) message += "\n最近记录：" + time;
        return message + "\n\n这是已采集到的对话记录，可能不是完整历史\n缓存相关分类依工具定义，总量由采集端去重计算，不把分类重复相加";
    }
    private static String readableTime(String value) {
        long time = NativeApi.time(value); if (time <= 0) return "";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai")); return format.format(new java.util.Date(time)) + "（北京时间）";
    }
    private static Long number(JSONObject object, String key) { return object == null ? null : NativeUsageFormat.count(object.opt(key)); }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
}
