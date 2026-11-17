package com.agentmonitor.live;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native presentation only. Account state, networking, filtering and navigation belong to the Activity. */
public final class NativePages {
    private NativePages() { }
    public interface OnChoice { void choose(String key); }
    public interface TaskCallbacks { void open(JSONObject task); void archive(JSONObject task); }
    public interface OnDevice { void open(JSONObject device); }

    /** Changing rows or counts never changes the selected filter. */
    public static final class Segments {
        private final NativeUi ui;
        private final LinearLayout root;
        private final String[] keys, labels;
        private final TextView[] buttons;
        private final boolean primary;
        private String selected = "";
        private int[] counts;
        public Segments(NativeUi ui, String[] keys, String[] labels, OnChoice onChoice) {
            this(ui, keys, labels, true, onChoice);
        }
        public Segments(NativeUi ui, String[] keys, String[] labels, boolean primary, OnChoice onChoice) {
            if (keys.length == 0 || keys.length != labels.length) throw new IllegalArgumentException("Segment keys and labels must match");
            this.ui = ui; this.keys = keys.clone(); this.labels = labels.clone(); this.primary = primary;
            root = new SegmentRow();
            buttons = new TextView[keys.length];
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                TextView button = new SegmentButton(); button.setText(labels[i]); button.setGravity(Gravity.CENTER);
                button.setTextSize(primary ? 15 : 14); button.setTypeface(ui.regular);
                button.setIncludeFontPadding(false); button.setLineSpacing(ui.dp(3), 1f);
                button.setMinHeight(ui.dp(48)); button.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
                button.setMaxLines(1); button.setHorizontallyScrolling(false); button.setEllipsize(TextUtils.TruncateAt.END);
                button.setAutoSizeTextTypeUniformWithConfiguration(12, primary ? 15 : 14, 1, TypedValue.COMPLEX_UNIT_SP);
                button.setContentDescription(labels[i]); button.setFocusable(true); button.setClickable(true);
                button.setBackground(ui.ripple(Color.TRANSPARENT, 10));
                ui.pressFeedback(button, .985f);
                button.setOnClickListener(v -> { if (!key.equals(selected)) { select(key); if (onChoice != null) onChoice.choose(key); } });
                button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true); info.setChecked(host.isSelected());
                    }
                });
                buttons[i] = button; root.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            select(keys[0]);
        }
        public LinearLayout view() { return root; }
        public String selected() { return selected; }
        public void select(String key) {
            boolean found = false; for (String candidate : keys) if (candidate.equals(key)) found = true;
            if (!found || selected.equals(key)) return;
            selected = key;
            for (int i = 0; i < keys.length; i++) {
                boolean active = keys[i].equals(key); TextView button = buttons[i];
                button.setSelected(active); button.setTextColor(active ? ui.text : ui.muted);
                button.setTypeface(active ? ui.medium : ui.regular);
                button.invalidate();
            }
        }
        public void counts(int[] values) {
            if (java.util.Arrays.equals(counts, values)) return;
            counts = values == null ? null : values.clone();
            for (int i = 0; i < buttons.length; i++) {
                String label = labels[i] + (counts != null && counts.length > i ? " " + counts[i] : "");
                setText(buttons[i], label); buttons[i].setContentDescription(label);
            }
        }

        /** The visual mark stays compact; the entire segment remains a 48 dp target. */
        private final class SegmentButton extends TextView {
            private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
            SegmentButton() { super(ui.activity); }
            @Override protected void onDraw(Canvas canvas) {
                if (isSelected()) {
                    if (primary) {
                        mark.setColor(ui.text);
                        float half = ui.dp(12), bottom = getHeight() - ui.dp(1);
                        canvas.drawRoundRect(getWidth() / 2f - half, bottom - ui.dp(2), getWidth() / 2f + half, bottom, ui.dp(1), ui.dp(1), mark);
                    } else {
                        mark.setColor(ui.soft);
                        float width = Math.min(getWidth() - ui.dp(8), getPaint().measureText(getText().toString()) + ui.dp(20));
                        float height = Math.min(getHeight() - ui.dp(12), getLineHeight() * getLineCount() + ui.dp(12));
                        canvas.drawRoundRect((getWidth() - width) / 2f, (getHeight() - height) / 2f,
                                (getWidth() + width) / 2f, (getHeight() + height) / 2f, ui.dp(8), ui.dp(8), mark);
                    }
                }
                super.onDraw(canvas);
            }
        }

        /** Size the whole group together so a bold selection cannot change one segment's height. */
        private final class SegmentRow extends LinearLayout {
            private final Paint sizing = new Paint(Paint.ANTI_ALIAS_FLAG);
            SegmentRow() { super(ui.activity); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); }
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                if (buttons != null && width > 0 && View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
                    sizing.setTypeface(ui.medium);
                    sizing.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
                    int slot = width / buttons.length;
                    int lines = 1;
                    for (TextView button : buttons) {
                        if (sizing.measureText(button.getText().toString()) > slot - button.getPaddingLeft() - button.getPaddingRight()) { lines = 2; break; }
                    }
                    // SP honors the user's font scale. At large sizes, let every segment grow together.
                    sizing.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, primary ? 15 : 14, getResources().getDisplayMetrics()));
                    int height = Math.max(ui.dp(48), (int) Math.ceil(sizing.getFontSpacing() * lines)
                            + ui.dp(20) + (lines - 1) * ui.dp(3));
                    for (TextView button : buttons) {
                        if (button.getMaxLines() != lines) button.setMaxLines(lines);
                        button.getLayoutParams().height = height;
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }
    }

    public static ListView list(NativeUi ui, BaseAdapter adapter) {
        ListView result = new ListView(ui.activity); result.setDivider(null); result.setDividerHeight(0);
        result.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); result.setVerticalScrollBarEnabled(false);
        result.setClipToPadding(false); result.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(16));
        result.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); result.setAdapter(adapter);
        return result;
    }

    public static final class TaskAdapter extends BaseAdapter {
        private final NativeUi ui;
        private final TaskCallbacks callbacks;
        private final List<JSONObject> rows = new ArrayList<>();
        private final Map<String, JSONObject> devices = new HashMap<>();
        private final Map<String, JSONObject> latestTasks = new HashMap<>();
        private boolean stale;
        private String signature = "";
        public TaskAdapter(NativeUi ui, TaskCallbacks callbacks) { this.ui = ui; this.callbacks = callbacks; }
        public void update(JSONArray tasks, JSONArray computers, boolean stale) {
            String next = NativeListPresentation.tasksKey(tasks, computers, stale, System.currentTimeMillis() / 60000);
            boolean changed = !next.equals(signature); signature = next; this.stale = stale;
            rows.clear(); devices.clear(); latestTasks.clear();
            if (tasks != null) for (int i = 0; i < tasks.length(); i++) { JSONObject item = tasks.optJSONObject(i); if (item != null) { rows.add(item); latestTasks.put(item.optString("id"), item); } }
            if (computers != null) for (int i = 0; i < computers.length(); i++) { JSONObject item = computers.optJSONObject(i); if (item != null && !devices.containsKey(item.optString("id"))) devices.put(item.optString("id"), item); }
            if (changed) notifyDataSetChanged();
        }
        @Override public int getCount() { return rows.size(); }
        @Override public JSONObject getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return stableId(getItem(position).optString("id")); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TaskHolder holder;
            if (recycled == null || !(recycled.getTag() instanceof TaskHolder)) { holder = new TaskHolder(); recycled = holder.outer; recycled.setTag(holder); }
            else holder = (TaskHolder) recycled.getTag();
            holder.bind(getItem(position)); return recycled;
        }
        private final class TaskHolder {
            final LinearLayout outer = ui.column(), card = ui.column();
            final LinearLayout toolBadge = ui.taskIdentity("", "");
            final TextView title = ui.text("", 17, false), status = ui.text("", 12, false), updated = ui.text("", 12, false);
            JSONObject current;
            TaskHolder() {
                outer.addView(card, matchWrap());
                card.setPadding(0, ui.dp(20), 0, ui.dp(20));
                card.setMinimumHeight(ui.dp(48));
                card.setBackground(ui.ripple(Color.TRANSPARENT, 10)); card.setFocusable(true); card.setClickable(true);
                ui.pressFeedback(card, .995f);
                card.setOnClickListener(v -> { JSONObject latest = latest(); if (latest != null && callbacks != null) callbacks.open(latest); });
                card.setAccessibilityDelegate(NativeUi.buttonAccessibility());
                card.addView(toolBadge, matchWrap()); card.addView(ui.space(10));
                title.setTypeface(ui.medium);
                title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                card.addView(title, matchWrap()); card.addView(ui.space(12));
                LinearLayout bottom = ui.row();
                status.setPadding(0, ui.dp(4), 0, ui.dp(4)); status.setMaxLines(1);
                bottom.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                updated.setTextColor(ui.muted); updated.setGravity(Gravity.END); updated.setMaxLines(1); updated.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams updatedParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); updatedParams.setMargins(ui.dp(8), 0, 0, 0); bottom.addView(updated, updatedParams);
                card.addView(bottom, matchWrap());
                View divider = new View(ui.activity); divider.setBackgroundColor(ui.line);
                divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                outer.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
                for (View info : new View[]{toolBadge, title, status, updated}) info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            JSONObject latest() { return current == null ? null : latestTasks.get(current.optString("id")); }
            void bind(JSONObject task) {
                current = task; JSONObject computer = devices.get(task.optString("device_id"));
                String titleValue = value(task, "title", "未命名任务"), deviceName = value(computer, "name", "未知电脑");
                String state = status(task, computer, stale); setText(title, titleValue);
                ui.bindTaskIdentity(toolBadge, task.optString("tool"), deviceName);
                bindStatus(ui, status, state);
                setText(updated, relative(task.optString("updated_at")));
                card.setContentDescription(titleValue + "，" + toolName(task.optString("tool")) + "，" + deviceName + "，" + statusLabel(state));
            }
        }
    }

    public static final class DeviceAdapter extends BaseAdapter {
        private final NativeUi ui; private final OnDevice callback;
        private final List<JSONObject> rows = new ArrayList<>(); private boolean stale; private String signature = "";
        public DeviceAdapter(NativeUi ui, OnDevice callback) { this.ui = ui; this.callback = callback; }
        public void update(JSONArray computers, boolean stale) {
            String next = NativeListPresentation.devicesKey(computers, stale, System.currentTimeMillis() / 60000);
            boolean changed = !next.equals(signature); signature = next; this.stale = stale; rows.clear();
            if (computers != null) for (int i = 0; i < computers.length(); i++) { JSONObject item = computers.optJSONObject(i); if (item != null) rows.add(item); }
            if (changed) notifyDataSetChanged();
        }
        @Override public int getCount() { return rows.size(); }
        @Override public JSONObject getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return stableId(getItem(position).optString("id")); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            DeviceHolder holder;
            if (recycled == null || !(recycled.getTag() instanceof DeviceHolder)) { holder = new DeviceHolder(); recycled = holder.outer; recycled.setTag(holder); }
            else holder = (DeviceHolder) recycled.getTag();
            holder.bind(getItem(position)); return recycled;
        }
        private final class DeviceHolder {
            final LinearLayout outer = ui.column(), card = ui.card();
            final TextView title = ui.text("", 16, true), platform = ui.text("", 13, false), status = ui.text("", 12, false), lastSeen = ui.text("", 12, false);
            JSONObject current;
            DeviceHolder() {
                outer.setPadding(0, 0, 0, ui.dp(10)); outer.addView(card, matchWrap());
                card.setBackground(ui.borderedRipple(ui.surface, 20)); card.setFocusable(true); card.setClickable(true); ui.pressFeedback(card, .995f);
                card.setOnClickListener(v -> { if (current != null && callback != null) { for (JSONObject item : rows) if (item.optString("id").equals(current.optString("id"))) { callback.open(item); break; } } }); card.setAccessibilityDelegate(NativeUi.buttonAccessibility());
                LinearLayout top = ui.row(); LinearLayout badge = ui.row(); badge.setGravity(Gravity.CENTER); badge.setBackground(ui.rounded(ui.soft, 12)); badge.addView(ui.icon("monitor", 22)); top.addView(badge, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
                LinearLayout words = ui.column(); LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); wordsParams.setMargins(ui.dp(10), 0, ui.dp(8), 0); top.addView(words, wordsParams);
                title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); platform.setTextColor(ui.muted); platform.setMaxLines(1); platform.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(title, matchWrap()); words.addView(ui.space(6)); words.addView(platform, matchWrap()); top.addView(ui.icon("chevron", 20));
                card.addView(top, matchWrap()); card.addView(ui.space(14)); LinearLayout bottom = ui.row();
                status.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); bottom.addView(status);
                lastSeen.setTextColor(ui.muted); lastSeen.setGravity(Gravity.END); lastSeen.setMaxLines(1); lastSeen.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); timeParams.setMargins(ui.dp(8), 0, 0, 0); bottom.addView(lastSeen, timeParams); card.addView(bottom, matchWrap());
                for (View info : new View[]{title, platform, status, lastSeen}) info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            void bind(JSONObject device) {
                current = device; String name = value(device, "name", "未命名电脑");
                String state = stale ? "unknown" : device.optBoolean("online") ? "online" : "offline";
                setText(title, name); setText(platform, value(device, "platform", "电脑")); bindStatus(ui, status, state);
                setText(lastSeen, "online".equals(state) ? "" : relative(device.optString("last_seen")));
                card.setContentDescription(name + "，" + value(device, "platform", "电脑") + "，" + statusLabel(state));
            }
        }
    }

    /** Persistent detail views; updates do not recreate a ScrollView or move its selection. */
    public static final class TaskDetail {
        public final LinearLayout view;
        private final NativeUi ui;
        private final TextView note, state, time, project;
        final LinearLayout projectRow, errorSection, noteSection;
        private final LinearLayout errorCard;
        private final TextView errorUnavailable, errorSummary, errorLocation, errorSuggestion, errorEvidence;
        public TaskDetail(NativeUi ui) {
            this.ui = ui; view = ui.column();
            LinearLayout statusLine = ui.row();
            state = ui.text("", 13, false); state.setPadding(0, ui.dp(4), 0, ui.dp(4));
            statusLine.addView(state, new LinearLayout.LayoutParams(-2, -2));
            time = ui.text("", 12); time.setTextColor(ui.muted); time.setGravity(Gravity.END);
            LinearLayout.LayoutParams stamp = new LinearLayout.LayoutParams(0, -2, 1);
            stamp.setMarginStart(ui.dp(10)); statusLine.addView(time, stamp);
            view.addView(statusLine, matchWrap());
            noteSection = ui.column(); noteSection.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
            noteSection.setBackground(ui.rounded(ui.soft, 16));
            TextView noteHeading = ui.heading("状态备注", 14); noteHeading.setTextColor(ui.muted);
            noteSection.addView(noteHeading, matchWrap()); noteSection.addView(ui.space(8));
            note = ui.text("", 14); note.setTextColor(ui.warning);
            noteSection.addView(note, matchWrap()); noteSection.setVisibility(View.GONE);
            project = ui.text("", 15);
            projectRow = fact("所属项目", project); projectRow.setPadding(0, 0, 0, ui.dp(14));
            errorSection = ui.column(); errorSection.addView(ui.space(16));
            errorUnavailable = ui.text("", 13, false); errorUnavailable.setTextColor(ui.muted);
            errorUnavailable.setPadding(ui.dp(4), 0, ui.dp(4), 0); errorSection.addView(errorUnavailable, matchWrap());
            errorCard = ui.card(); LinearLayout errorHeading = ui.row();
            NativeUi.Icon errorIcon = (NativeUi.Icon) ui.icon("error", 20); errorIcon.setInk(ui.danger); errorHeading.addView(errorIcon);
            TextView errorTitle = ui.heading("错误分析", 18); LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); headingParams.setMarginStart(ui.dp(8)); errorHeading.addView(errorTitle, headingParams);
            errorCard.addView(errorHeading, matchWrap()); errorCard.addView(ui.space(12));
            errorSummary = ui.text("", 16, true); errorCard.addView(errorSummary, matchWrap());
            errorCard.addView(ui.space(16)); errorLocation = ui.text("", 14); errorCard.addView(fact("出错位置", errorLocation), matchWrap());
            errorCard.addView(ui.space(14)); errorSuggestion = ui.text("", 14); errorCard.addView(fact("处理建议", errorSuggestion), matchWrap());
            errorCard.addView(ui.space(14)); errorEvidence = ui.text("", 12, false); errorEvidence.setTextColor(ui.muted); errorCard.addView(errorEvidence, matchWrap());
            errorSection.addView(errorCard, matchWrap()); errorSection.setVisibility(View.GONE);
        }
        public LinearLayout view() { return view; }
        private LinearLayout fact(String label, TextView value) {
            LinearLayout result = ui.column();
            TextView key = ui.text(label, 13); key.setTextColor(ui.muted);
            result.addView(key, matchWrap()); result.addView(ui.space(8));
            result.addView(value, matchWrap()); return result;
        }
        public void update(JSONObject task, JSONObject device, boolean stale) { update(task, device, stale, true); }
        public void update(JSONObject task, JSONObject device, boolean stale, boolean syncOutput) {
            String code = status(task, device, stale); bindStatus(ui, state, code);
            String noteText = "unknown".equals(code) ? "当前状态未知，显示最后收到的记录" : "waiting".equals(code) ? "任务正在等待回答或批准" : "";
            setText(note, noteText); noteSection.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);
            String updated = absolute(value(task, "updated_at", ""));
            setText(time, "尚未更新".equals(updated) ? updated : "更新于 " + updated);
            String projectValue = value(task, "project", ""); setText(project, projectValue); projectRow.setVisibility(projectValue.isEmpty() ? View.GONE : View.VISIBLE);
            NativeErrorPresentation.Detail error = NativeErrorPresentation.from(task, syncOutput, code);
            errorSection.setVisibility(error.visible ? View.VISIBLE : View.GONE);
            errorUnavailable.setVisibility(error.hasAnalysis ? View.GONE : View.VISIBLE);
            errorCard.setVisibility(error.hasAnalysis ? View.VISIBLE : View.GONE);
            setText(errorUnavailable, error.hasAnalysis ? "" : error.summary);
            setText(errorSummary, error.summary); setText(errorLocation, error.location); setText(errorSuggestion, error.suggestion); setText(errorEvidence, error.sourceLabel);
        }
    }

    public static LinearLayout empty(NativeUi ui, String title, String icon) {
        LinearLayout root = ui.column(); root.setGravity(Gravity.CENTER); root.setPadding(ui.dp(20), ui.dp(36), ui.dp(20), ui.dp(36));
        root.addView(ui.icon(icon, 32)); root.addView(ui.space(14)); TextView label = ui.text(title, 15, false); label.setGravity(Gravity.CENTER); label.setTextColor(ui.muted); root.addView(label, matchWrap()); return root;
    }
    public static String status(JSONObject task, JSONObject device, boolean stale) {
        if (task == null || device == null || stale || !device.optBoolean("online", false)) return "unknown";
        String raw = task.optString("status");
        switch (raw) { case "running": case "waiting": case "completed": case "error": case "idle": return raw; default: return "unknown"; }
    }
    public static String statusLabel(String status) {
        switch (status) { case "running": return "执行中"; case "waiting": return "等待批准"; case "completed": return "本轮结束"; case "error": return "执行出错"; case "idle": return "空闲"; case "online": return "在线"; case "offline": return "离线"; default: return "状态未知"; }
    }
    public static String toolName(String tool) { return NativeListPresentation.toolLabel(tool); }
    private static int statusColor(NativeUi ui, String state) { return "waiting".equals(state) ? ui.warning : "error".equals(state) ? ui.danger : "running".equals(state) || "online".equals(state) ? ui.accent : ui.muted; }
    private static void bindStatus(NativeUi ui, TextView label, String state) {
        boolean attention = "waiting".equals(state) || "error".equals(state);
        setText(label, statusLabel(state)); label.setTextColor(attention ? statusColor(ui, state) : ui.muted);
        label.setTypeface(attention ? ui.medium : ui.regular);
        if (!state.equals(label.getTag())) {
            int fill = "waiting".equals(state) ? (ui.dark ? 0xFF423B2C : 0xFFF3EBDC) : (ui.dark ? 0xFF443237 : 0xFFF5E4E4);
            label.setPadding(attention ? ui.dp(8) : 0, ui.dp(4), attention ? ui.dp(8) : 0, ui.dp(4));
            label.setBackground(attention ? ui.rounded(fill, 8) : null); label.setTag(state);
        }
    }
    private static void setText(TextView view, String value) { if (!TextUtils.equals(view.getText(), value)) view.setText(value); }
    private static String rawValue(JSONObject object, String name) { return object == null || object.isNull(name) ? "" : object.optString(name, ""); }
    private static String value(JSONObject object, String name, String fallback) { if (object == null || object.isNull(name)) return fallback; String result = object.optString(name, "").trim(); return result.isEmpty() ? fallback : result; }
    private static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private static long stableId(String id) { long result = 0xcbf29ce484222325L; for (int i = 0; i < id.length(); i++) { result ^= id.charAt(i); result *= 0x100000001b3L; } return result; }
    public static String relative(String iso) {
        try {
            long age = Math.max(0, System.currentTimeMillis() - Instant.parse(iso).toEpochMilli());
            if (age < 60000) return "刚刚"; if (age < 3600000) return (age / 60000) + " 分钟前";
            if (age < 86400000) return (age / 3600000) + " 小时前"; if (age < 604800000) return (age / 86400000) + " 天前";
            return DateTimeFormatter.ofPattern("M月d日").withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
        } catch (Exception invalid) { return "尚未更新"; }
    }
    private static String absolute(String iso) { try { return DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso)); } catch (Exception invalid) { return "尚未更新"; } }
}
