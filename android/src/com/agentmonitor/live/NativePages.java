package com.agentmonitor.live;

import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
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
        private String selected = "";
        private int[] counts;
        public Segments(NativeUi ui, String[] keys, String[] labels, OnChoice onChoice) {
            if (keys.length == 0 || keys.length != labels.length) throw new IllegalArgumentException("Segment keys and labels must match");
            this.ui = ui; this.keys = keys.clone(); this.labels = labels.clone();
            root = new SegmentRow(); root.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            root.setBackground(ui.bordered(ui.dark ? 0xB0252E33 : 0xB8FFFFFF, 20));
            buttons = new TextView[keys.length];
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                TextView button = ui.text(labels[i], 14, false); button.setGravity(Gravity.CENTER);
                button.setMinHeight(ui.dp(48)); button.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
                button.setMaxLines(1); button.setHorizontallyScrolling(false); button.setEllipsize(TextUtils.TruncateAt.END);
                button.setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_SP);
                button.setContentDescription(labels[i]); button.setFocusable(true); button.setClickable(true);
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
                button.setBackground(ui.ripple(active ? ui.surface : Color.TRANSPARENT, 16));
            }
        }
        public void counts(int[] values) {
            counts = values == null ? null : values.clone();
            for (int i = 0; i < buttons.length; i++) {
                String label = labels[i] + (counts != null && counts.length > i ? " " + counts[i] : "");
                setText(buttons[i], label); buttons[i].setContentDescription(label);
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
                    sizing.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
                    int height = Math.max(ui.dp(48), (int) Math.ceil(sizing.getFontSpacing() * lines)
                            + ui.dp(16) + (lines - 1) * ui.dp(2));
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
        result.setClipToPadding(false); result.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(16));
        result.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); result.setAdapter(adapter);
        return result;
    }

    public static final class TaskAdapter extends BaseAdapter {
        private final NativeUi ui;
        private final TaskCallbacks callbacks;
        private final List<JSONObject> rows = new ArrayList<>();
        private final Map<String, JSONObject> devices = new HashMap<>();
        private boolean stale;
        private String signature = "";
        public TaskAdapter(NativeUi ui, TaskCallbacks callbacks) { this.ui = ui; this.callbacks = callbacks; }
        public void update(JSONArray tasks, JSONArray computers, boolean stale) {
            String next = String.valueOf(tasks) + "|" + String.valueOf(computers) + "|" + stale + "|" + System.currentTimeMillis() / 60000;
            if (next.equals(signature)) return; signature = next; this.stale = stale;
            rows.clear(); devices.clear();
            if (tasks != null) for (int i = 0; i < tasks.length(); i++) { JSONObject item = tasks.optJSONObject(i); if (item != null) rows.add(item); }
            if (computers != null) for (int i = 0; i < computers.length(); i++) { JSONObject item = computers.optJSONObject(i); if (item != null) devices.put(item.optString("id"), item); }
            notifyDataSetChanged();
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
            final LinearLayout outer = ui.column(), card = ui.card();
            final ImageView brand = ui.brand("", 32);
            final TextView title = ui.text("", 16, true), source = ui.text("", 13, false), status = ui.text("", 12, false), updated = ui.text("", 12, false);
            final NativeUi.Icon archive;
            JSONObject current;
            TaskHolder() {
                outer.setPadding(0, 0, 0, ui.dp(10)); outer.addView(card, matchWrap());
                card.setPadding(ui.dp(16), ui.dp(16), ui.dp(8), ui.dp(8));
                card.setBackground(ui.ripple(ui.surface, 20)); card.setFocusable(true); card.setClickable(true);
                card.setOnClickListener(v -> { if (current != null && callbacks != null) callbacks.open(current); });
                card.setAccessibilityDelegate(NativeUi.buttonAccessibility());
                LinearLayout top = ui.row(); top.setGravity(Gravity.TOP); top.addView(brand);
                LinearLayout words = ui.column(); LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); wordParams.setMargins(ui.dp(10), 0, 0, 0);
                top.addView(words, wordParams); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                source.setTextColor(ui.muted); source.setMaxLines(1); source.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(title, matchWrap()); words.addView(ui.space(6)); words.addView(source, matchWrap());
                View chevron = ui.icon("chevron", 20); LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)); chevronParams.setMargins(ui.dp(4), ui.dp(2), ui.dp(4), 0); top.addView(chevron, chevronParams);
                card.addView(top, matchWrap()); card.addView(ui.space(6));
                LinearLayout bottom = ui.row();
                status.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); status.setMaxLines(1);
                bottom.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                updated.setTextColor(ui.muted); updated.setGravity(Gravity.END); updated.setMaxLines(1); updated.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams updatedParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); updatedParams.setMargins(ui.dp(8), 0, 0, 0); bottom.addView(updated, updatedParams);
                archive = (NativeUi.Icon) ui.iconButton("archive", "归档任务", () -> { if (current != null && callbacks != null) callbacks.archive(current); });
                archive.setInk(ui.muted); bottom.addView(archive); card.addView(bottom, matchWrap());
                for (View info : new View[]{title, source, status, updated}) info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            void bind(JSONObject task) {
                current = task; JSONObject computer = devices.get(task.optString("device_id"));
                String titleValue = value(task, "title", "未命名任务"), deviceName = value(computer, "name", "未知电脑");
                String state = status(task, computer, stale); setText(title, titleValue); setText(source, deviceName);
                ui.bindBrand(brand, task.optString("tool")); bindStatus(ui, status, state);
                setText(updated, relative(task.optString("updated_at")));
                boolean archived = task.optBoolean("archived"); archive.setName(archived ? "restore" : "archive");
                archive.setContentDescription((archived ? "恢复：" : "归档：") + titleValue);
                card.setContentDescription(titleValue + "，" + toolName(task.optString("tool")) + "，" + deviceName + "，" + statusLabel(state));
            }
        }
    }

    public static final class DeviceAdapter extends BaseAdapter {
        private final NativeUi ui; private final OnDevice callback;
        private final List<JSONObject> rows = new ArrayList<>(); private boolean stale; private String signature = "";
        public DeviceAdapter(NativeUi ui, OnDevice callback) { this.ui = ui; this.callback = callback; }
        public void update(JSONArray computers, boolean stale) {
            String next = String.valueOf(computers) + "|" + stale + "|" + System.currentTimeMillis() / 60000;
            if (next.equals(signature)) return; signature = next; this.stale = stale; rows.clear();
            if (computers != null) for (int i = 0; i < computers.length(); i++) { JSONObject item = computers.optJSONObject(i); if (item != null) rows.add(item); }
            notifyDataSetChanged();
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
                card.setBackground(ui.ripple(ui.surface, 20)); card.setFocusable(true); card.setClickable(true);
                card.setOnClickListener(v -> { if (current != null && callback != null) callback.open(current); }); card.setAccessibilityDelegate(NativeUi.buttonAccessibility());
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
        private final LinearLayout projectRow;
        public TaskDetail(NativeUi ui) {
            this.ui = ui; view = ui.column();
            note = ui.text("", 13, false); note.setTextColor(ui.warning); note.setPadding(0, 0, 0, ui.dp(12)); view.addView(note, matchWrap());
            LinearLayout facts = ui.card(); state = ui.text("", 15, true); time = ui.text("", 13, false); project = ui.text("", 13, false);
            facts.addView(fact("状态", state), matchWrap()); facts.addView(ui.space(14)); facts.addView(fact("更新", time), matchWrap());
            projectRow = fact("项目", project); projectRow.setPadding(0, ui.dp(14), 0, 0); facts.addView(projectRow, matchWrap());
            view.addView(facts, matchWrap());
        }
        public LinearLayout view() { return view; }
        private LinearLayout fact(String label, TextView value) {
            LinearLayout result = ui.row(); result.setGravity(Gravity.TOP); TextView key = ui.text(label, 13, false); key.setTextColor(ui.muted);
            result.addView(key, new LinearLayout.LayoutParams(ui.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)); result.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); return result;
        }
        public void update(JSONObject task, JSONObject device, boolean stale) { update(task, device, stale, true); }
        public void update(JSONObject task, JSONObject device, boolean stale, boolean syncOutput) {
            String code = status(task, device, stale); setText(state, statusLabel(code)); state.setTextColor(statusColor(ui, code));
            String noteText = "unknown".equals(code) ? "状态已过期，显示最后收到的记录。" : "waiting".equals(code) ? "请到电脑处理批准请求。" : "";
            setText(note, noteText); note.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);
            setText(time, absolute(value(task, "updated_at", ""))); String projectValue = value(task, "project", ""); setText(project, projectValue); projectRow.setVisibility(projectValue.isEmpty() ? View.GONE : View.VISIBLE);
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
    public static String toolName(String tool) { return "claude".equals(tool) ? "Claude Code" : "codex".equals(tool) ? "Codex" : "任务"; }
    private static int statusColor(NativeUi ui, String state) { return "waiting".equals(state) ? ui.warning : "error".equals(state) ? ui.danger : "running".equals(state) || "online".equals(state) ? ui.accent : ui.muted; }
    private static void bindStatus(NativeUi ui, TextView label, String state) {
        setText(label, statusLabel(state)); label.setTextColor(statusColor(ui, state));
        int fill = "waiting".equals(state) ? (ui.dark ? 0xFF423B2C : 0xFFF3EBDC) : "error".equals(state) ? (ui.dark ? 0xFF443237 : 0xFFF5E4E4) : "running".equals(state) || "online".equals(state) ? ui.soft : (ui.dark ? 0xFF333D42 : 0xFFEAF0F1);
        if (!state.equals(label.getTag())) { label.setBackground(ui.rounded(fill, 10)); label.setTag(state); }
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
