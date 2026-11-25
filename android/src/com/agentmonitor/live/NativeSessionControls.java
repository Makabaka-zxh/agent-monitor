package com.agentmonitor.live;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, source-backed task interactions. The server remains authoritative for every decision. */
public final class NativeSessionControls {
    public interface Received { void done(JSONObject value, NativeApi.Failure failure); }
    public interface Transport { void request(String method, String path, JSONObject value, Received done); }
    private final NativeUi ui;
    private final NativeTaskResults.Host host;
    private final Transport transport;
    private final SharedPreferences preferences;
    private final String key, taskId;
    private final Runnable changed;
    /** One stable pending-interaction root, mounted separately from the task's result/reply body. */
    public final LinearLayout view;
    private LinearLayout settingsView;
    private LivePresentationSheet settingsSheet, approvalSheet;
    private final Map<String, QuestionDraft> drafts = new LinkedHashMap<>();
    private final Map<String, DecisionMemo> sessionMemos = new LinkedHashMap<>();
    private JSONObject response, selection, fastNext, planNext, records = new JSONObject();
    private boolean fetching, sending, replyLocked, disposed, selectionCorrupt, fastCorrupt, planCorrupt, recordsCorrupt, waiting, controlsReadFailed;
    private long nextFetch, observedAt, retryAt;
    private String renderKey = "", settingsKey = "", feedback = "", pendingKind = "", sendingRequestId = "", lastAppliedSummary = "";

    public NativeSessionControls(NativeUi ui, String taskId, NativeTaskResults.Host host, Transport transport,
                                 SharedPreferences preferences, String key, Runnable changed) {
        this.ui = ui; this.taskId = taskId; this.host = host; this.transport = transport;
        this.preferences = preferences; this.key = key + ":controls"; this.changed = changed;
        view = ui.column(); view.setVisibility(View.GONE);
        String stored = preferences.getString(this.key + ":selection", "");
        if (!stored.isEmpty()) try { selection = new JSONObject(stored); } catch (Exception invalid) { selectionCorrupt = true; }
        String storedFast = preferences.getString(this.key + ":fast_next", "");
        if (!storedFast.isEmpty()) try { fastNext = new JSONObject(storedFast); } catch (Exception invalid) { fastCorrupt = true; }
        String storedPlan = preferences.getString(this.key + ":plan_next", "");
        if (!storedPlan.isEmpty()) try { planNext = new JSONObject(storedPlan); } catch (Exception invalid) { planCorrupt = true; }
        String savedRecords = preferences.getString(this.key + ":decisions", "");
        if (!savedRecords.isEmpty()) try {
            records = new JSONObject(savedRecords); if (records.length() > 64) recordsCorrupt = true;
            java.util.Iterator<String> ids = records.keys();
            while (ids.hasNext()) {
                String id = ids.next(); JSONObject record = records.optJSONObject(id), payload = record == null ? null : record.optJSONObject("payload");
                if (record == null || !record.optString("fingerprint").matches("[0-9a-f]{64}") || payload == null || !id.equals(payload.optString("request_id")) || !taskId.equals(payload.optString("task_id")) || !payload.optString("decision_id").matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) recordsCorrupt = true;
            }
        } catch (Exception invalid) { recordsCorrupt = true; }
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private long now() { return System.currentTimeMillis() / 1000; }
    private boolean active() { return !disposed && host.connected() && host.visible(); }
    private boolean fresh() { return response != null && observedAt > 0 && SystemClock.elapsedRealtime() - observedAt <= 15000; }
    public void update(JSONObject task) {
        String kind = NativeListPresentation.pendingKind(task);
        if (!kind.equals(pendingKind)) { pendingKind = kind; nextFetch = 0; }
        waiting = "waiting".equals(task.optString("status")) || !kind.isEmpty();
        tick();
    }
    public void tick() {
        if (!active()) return;
        if (!fetching && !sending && SystemClock.elapsedRealtime() >= nextFetch) refresh();
        render(); renderSettings();
    }
    public void refresh() {
        if (!active() || fetching || sending || SystemClock.elapsedRealtime() < retryAt) return;
        fetching = true; nextFetch = SystemClock.elapsedRealtime() + 5000;
        transport.request("GET", NativeApi.taskPath("controls", taskId), null, (value, failure) -> {
            fetching = false; if (!active()) { observedAt = 0; return; }
            controlsReadFailed = failure != null;
            if (failure != null) { observedAt = 0; feedback = "暂时无法确认请求状态，请刷新后再操作"; retryAt = SystemClock.elapsedRealtime() + Math.max(0, failure.retryAfterMs); nextFetch = Math.max(SystemClock.elapsedRealtime() + 5000, retryAt); }
            else { response = value; observedAt = SystemClock.elapsedRealtime(); feedback = ""; pruneFinished(); }
            render(); renderSettings(); changed.run();
        });
    }
    public void pause() {
        observedAt = 0; nextFetch = 0;
        if (approvalSheet != null) { approvalSheet.dismiss(); approvalSheet = null; }
        if (settingsSheet != null) { settingsSheet.dismiss(); settingsSheet = null; settingsView = null; }
    }
    public void dispose() { disposed = true; drafts.clear(); sessionMemos.clear(); response = null; selection = null; fastNext = null; planNext = null; records = new JSONObject(); }
    public void setReplyLocked(boolean value) { if (replyLocked == value) return; replyLocked = value; renderSettings(); }
    public void setLastAppliedSummary(String summary) {
        String next = summary == null ? "" : summary;
        if (next.equals(lastAppliedSummary)) return;
        lastAppliedSummary = next;
        renderSettings();
    }
    public boolean canSendReply() {
        return !selectionCorrupt && !fastCorrupt && !planCorrupt
                && (selection == null || validCatalog() && NativeSessionControlPolicy.selection(selection, catalog(), now()))
                && (fastNext == null && planNext == null || validCatalog()
                    && NativeSessionControlPolicy.oneTurnSettings(catalog(), selection, fastNext, planNext, now()) != null);
    }
    public String compactCapsuleLabel() {
        if (!canSendReply()) return "模型需重选 ▾";
        JSONObject catalog = catalog();
        boolean valid = validCatalog();
        boolean fast = valid && NativeSessionControlPolicy.fastReply(catalog, selection, fastNext, now()) != null;
        boolean plan = valid && NativeSessionControlPolicy.planReply(catalog, selection, planNext, now()) != null;
        if (selection == null) {
            String base = "继承电脑";
            if (valid) {
                String current = NativeSessionControlPolicy.nullableText(catalog, "current_model");
                if (!current.isEmpty()) {
                    JSONObject cm = NativeSessionControlPolicy.model(catalog, current);
                    String formatted = cm == null ? NativeUsageFormat.modelName(current) : modelName(cm);
                    if (!formatted.isEmpty() && formatted.length() <= 18) base = formatted;
                }
            }
            if (fast) base += " · 快";
            if (plan) base += " · 计划";
            return base + " ▾";
        }
        String modelId = NativeSessionControlPolicy.nullableText(selection, "model");
        JSONObject model = valid ? NativeSessionControlPolicy.model(catalog, modelId) : null;
        String label = model != null ? modelName(model) : NativeUsageFormat.modelName(modelId);
        if (label.isEmpty()) label = "已选模型";
        String effort = NativeSessionControlPolicy.nullableText(selection, "effort");
        if (!effort.isEmpty()) label += " · " + NativeSessionControlPolicy.effortLabel(effort);
        if (fast) label += " · 快";
        if (plan) label += " · 计划";
        return label + " ▾";
    }
    public JSONObject selectionForSend() {
        if (!canSendReply()) throw new IllegalStateException("模型选项已变化，请重新选择或改为继承电脑设置");
        if (fastNext == null && planNext == null) return copy(selection);
        JSONObject result = NativeSessionControlPolicy.oneTurnSettings(catalog(), selection, fastNext, planNext, now());
        if (result == null) throw new IllegalStateException("下次回复的选项已变化");
        return result;
    }
    /** Clear only the one-turn choices in the recorded reply, atomically with the record. */
    public boolean attachTurnOptionConsumption(SharedPreferences.Editor record, JSONObject snapshot) {
        boolean speed = NativeSessionControlPolicy.fastRequested(snapshot), plan = NativeSessionControlPolicy.planRequested(snapshot);
        if (speed) record.remove(key + ":fast_next");
        if (plan) record.remove(key + ":plan_next");
        return speed || plan;
    }
    public void turnOptionsConsumed(JSONObject snapshot) {
        if (NativeSessionControlPolicy.fastRequested(snapshot)) { fastNext = null; fastCorrupt = false; }
        if (NativeSessionControlPolicy.planRequested(snapshot)) { planNext = null; planCorrupt = false; }
        renderSettings();
    }
    private JSONObject capabilities() { JSONObject value = response == null ? null : response.optJSONObject("capabilities"); return value == null ? new JSONObject() : value; }
    private JSONObject catalog() { return response == null ? null : response.optJSONObject("catalog"); }
    private NativeSessionControlPolicy.CatalogState catalogState() {
        return NativeSessionControlPolicy.catalogState(response, fresh(), controlsReadFailed, now());
    }
    private boolean validCatalog() { return catalogState() == NativeSessionControlPolicy.CatalogState.READY; }
    public void openSettings() {
        if (!active() || settingsSheet != null) return;
        LivePresentationSheet sheet = new LivePresentationSheet(ui.activity, "模型与回复偏好", "关闭模型设置");
        settingsSheet = sheet;
        settingsView = ui.column();
        settingsKey = "";
        renderSettings();
        sheet.body(settingsView);
        sheet.setOnDismissListener(dialog -> {
            if (settingsSheet == sheet) {
                settingsSheet = null;
                settingsView = null;
            }
        });
        sheet.show();
        tick();
    }

    private String modelName(JSONObject model) {
        return NativeUsageFormat.modelName(model.optString("label", model.optString("id")));
    }

    private TextView sectionLabel(String title) {
        TextView label = ui.text(title, 12, true);
        label.setTextColor(ui.muted);
        label.setPadding(ui.dp(2), 0, ui.dp(2), 0);
        return label;
    }

    private View buildModelRow(String title, String subtitle, boolean selected, boolean enabled, Runnable onSelect) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(48));
        row.setPaddingRelative(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
        row.setBackground(ui.ripple(selected ? ui.soft : Color.TRANSPARENT, 12));
        row.setSelected(selected);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : .5f);
        row.setFocusable(enabled);
        row.setClickable(enabled);
        row.setContentDescription(subtitle.isEmpty() ? title : title + "，" + subtitle);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View hostView, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(hostView, info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(hostView.isSelected());
            }
        });

        LinearLayout words = ui.column();
        TextView name = ui.text(title, 15, selected);
        name.setTextColor(selected ? ui.accent : ui.text);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        words.addView(name, wrap());
        if (!subtitle.isEmpty()) {
            TextView sub = ui.text(subtitle, 12, false);
            sub.setTextColor(ui.muted);
            sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams subLp = wrap();
            subLp.topMargin = ui.dp(2);
            words.addView(sub, subLp);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        if (selected) {
            NativeUi.Icon check = (NativeUi.Icon) ui.icon("check", 18);
            check.setInk(ui.accent);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
            checkLp.setMarginStart(ui.dp(10));
            row.addView(check, checkLp);
        }
        if (enabled && onSelect != null) {
            row.setOnClickListener(v -> onSelect.run());
        }
        return row;
    }

    private View buildTurnToggleCard(String title, String subtitle, boolean activeChoice, boolean enabled, Runnable onToggle) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(50));
        row.setPaddingRelative(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
        row.setBackground(ui.borderedRipple(activeChoice ? ui.soft : ui.bg, 14));
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : .5f);
        row.setFocusable(enabled);
        row.setClickable(enabled);
        row.setAccessibilityDelegate(NativeUi.buttonAccessibility());

        LinearLayout words = ui.column();
        TextView name = ui.text(title, 14, true);
        name.setTextColor(activeChoice ? ui.accent : ui.text);
        words.addView(name, wrap());
        TextView desc = ui.text(subtitle, 12, false);
        desc.setTextColor(ui.muted);
        LinearLayout.LayoutParams dLp = wrap(); dLp.topMargin = ui.dp(2);
        words.addView(desc, dLp);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        TextView pill = ui.text(activeChoice ? "已开启" : "默认", 12, activeChoice);
        pill.setTextColor(activeChoice ? (ui.dark ? ui.bg : Color.WHITE) : ui.muted);
        pill.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        pill.setBackground(ui.rounded(activeChoice ? ui.accent : ui.soft, 10));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
        pLp.setMarginStart(ui.dp(10));
        row.addView(pill, pLp);

        if (enabled && onToggle != null) row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private void renderSettings() {
        if (settingsView == null) return;
        JSONObject catalog = catalog();
        NativeSessionControlPolicy.CatalogState state = catalogState();
        boolean valid = state == NativeSessionControlPolicy.CatalogState.READY;
        String message = NativeSessionControlPolicy.catalogMessage(state, response);
        // Renewing only the expiry should not rebuild controls during a touch.
        String catalogOptions = !valid ? "" : NativeSessionControlPolicy.nullableText(catalog, "provider") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "catalog_revision") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "current_model") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "current_effort") + "\n"
                + String.valueOf(catalog.optJSONArray("collaboration_modes")) + "\n"
                + String.valueOf(catalog.optJSONArray("models"));
        String signature = catalogOptions + state + message + String.valueOf(selection) + selectionCorrupt
                + String.valueOf(fastNext) + fastCorrupt + String.valueOf(planNext) + planCorrupt + replyLocked + lastAppliedSummary;
        if (signature.equals(settingsKey)) return;
        settingsKey = signature;
        settingsView.removeAllViews();

        if (!lastAppliedSummary.isEmpty()) {
            TextView appliedNote = ui.text(lastAppliedSummary, 12, true);
            appliedNote.setTextColor(ui.accent);
            appliedNote.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            appliedNote.setBackground(ui.rounded(ui.soft, 12));
            settingsView.addView(appliedNote, wrap());
            settingsView.addView(ui.space(10));
        }

        String selectedId = NativeSessionControlPolicy.nullableText(selection, "model");
        if (valid) {
            final JSONObject snapshotCatalog = copy(catalog);
            String current = NativeSessionControlPolicy.nullableText(catalog, "current_model");
            JSONObject currentModel = NativeSessionControlPolicy.model(catalog, current);
            String currentEffort = NativeSessionControlPolicy.nullableText(catalog, "current_effort");
            String desktopCurrentDesc = current.isEmpty()
                    ? "不改动电脑端原有模型配置"
                    : "电脑当前：" + (currentModel == null ? NativeUsageFormat.modelName(current) : modelName(currentModel))
                        + (currentEffort.isEmpty() ? "" : " · " + NativeSessionControlPolicy.effortLabel(currentEffort));

            // Section 1: Codex / Claude style inline Model Picker List
            settingsView.addView(sectionLabel("模型选择"), wrap());
            settingsView.addView(ui.space(6));

            LinearLayout modelGroup = ui.column();
            modelGroup.setBackground(ui.bordered(ui.bg, 16));
            modelGroup.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            modelGroup.setClipToOutline(true);

            boolean inheritSelected = selection == null && !selectionCorrupt;
            modelGroup.addView(buildModelRow("继承电脑当前设置", desktopCurrentDesc, inheritSelected, !replyLocked,
                    () -> selectModelInline(snapshotCatalog, null)), wrap());

            final JSONArray models = snapshotCatalog.optJSONArray("models");
            boolean selectedValid = selection != null && NativeSessionControlPolicy.selection(selection, catalog, now());
            java.util.Set<String> ambiguous = new java.util.HashSet<>(), seen = new java.util.HashSet<>();
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject item = models.optJSONObject(i);
                    if (item == null) continue;
                    String nm = modelName(item);
                    if (!seen.add(nm)) ambiguous.add(nm);
                }
                for (int i = 0; i < models.length(); i++) {
                    final JSONObject item = models.optJSONObject(i);
                    if (item == null) continue;
                    final String itemId = item.optString("id");
                    String display = modelName(item);
                    String sub = ambiguous.contains(display) ? itemId : "";
                    boolean itemSelected = selectedValid && itemId.equals(selectedId);

                    View divider = new View(ui.activity);
                    divider.setBackgroundColor(ui.line);
                    LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(0.6f)));
                    divLp.setMarginStart(ui.dp(12));
                    divLp.setMarginEnd(ui.dp(12));
                    modelGroup.addView(divider, divLp);

                    modelGroup.addView(buildModelRow(display, sub, itemSelected, !replyLocked,
                            () -> selectModelInline(snapshotCatalog, itemId)), wrap());
                }
            }
            settingsView.addView(modelGroup, wrap());

            // Section 2: Inline Segmented Effort Bar (Codex / Claude style)
            JSONObject effortModel = NativeSessionControlPolicy.effortModel(catalog, selection, selectionCorrupt, now());
            if (effortModel != null && effortModel.optJSONArray("efforts") != null && effortModel.optJSONArray("efforts").length() > 0) {
                final JSONObject originalSel = copy(selection);
                final String targetModelId = effortModel.optString("id");
                final JSONArray efforts = effortModel.optJSONArray("efforts");
                String prevEffort = NativeSessionControlPolicy.nullableText(selection, "effort");

                settingsView.addView(ui.space(14));
                settingsView.addView(sectionLabel("思考强度"), wrap());
                settingsView.addView(ui.space(6));

                LinearLayout effortTrack = ui.row();
                effortTrack.setBackground(ui.rounded(ui.soft, 14));
                effortTrack.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));

                int totalPills = efforts.length() + 1;
                for (int i = 0; i < totalPills; i++) {
                    final String effortVal = i == 0 ? null : efforts.optString(i - 1);
                    String pillText = i == 0 ? "默认" : NativeSessionControlPolicy.effortLabel(effortVal);
                    boolean pillActive = i == 0 ? prevEffort.isEmpty() : prevEffort.equals(effortVal);

                    TextView pillBtn = ui.button(pillText, false, () -> selectEffortInline(snapshotCatalog, originalSel, targetModelId, effortVal));
                    pillBtn.setTextSize(13);
                    pillBtn.setMinHeight(ui.dp(38));
                    pillBtn.setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(7));
                    pillBtn.setBackground(pillActive ? ui.bordered(ui.surface, 11) : ui.ripple(Color.TRANSPARENT, 11));
                    pillBtn.setTextColor(pillActive ? ui.accent : ui.muted);
                    pillBtn.setTypeface(pillActive ? ui.strong : ui.regular);
                    pillBtn.setEnabled(!replyLocked);
                    pillBtn.setAlpha(replyLocked ? .5f : 1f);
                    effortTrack.addView(pillBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                }
                settingsView.addView(effortTrack, wrap());
            }

            // Section 3: Next-Turn Mode Switches (Fast / Plan)
            boolean canStageFast = NativeSessionControlPolicy.stageFast(catalog, selection, selectionCorrupt, now()) != null;
            boolean canStagePlan = NativeSessionControlPolicy.stagePlan(catalog, selection, selectionCorrupt, now()) != null;
            if (canStageFast || canStagePlan) {
                settingsView.addView(ui.space(14));
                settingsView.addView(sectionLabel("下一轮附加模式"), wrap());
                settingsView.addView(ui.space(6));
                if (canStageFast) {
                    boolean fastActive = NativeSessionControlPolicy.fastReply(catalog, selection, fastNext, now()) != null;
                    settingsView.addView(buildTurnToggleCard("Fast 快速层", "仅下一条回复启用优先快速推理", fastActive, !replyLocked,
                            () -> toggleOneTurnInline(false)), wrap());
                }
                if (canStagePlan) {
                    if (canStageFast) settingsView.addView(ui.space(8));
                    boolean planActive = NativeSessionControlPolicy.planReply(catalog, selection, planNext, now()) != null;
                    settingsView.addView(buildTurnToggleCard("Plan 规划模式", "仅下一条回复先输出方案或提问确认", planActive, !replyLocked,
                            () -> toggleOneTurnInline(true)), wrap());
                }
            } else if (NativeSessionControlPolicy.fastModel(catalog, selection, selectionCorrupt, now()) != null) {
                settingsView.addView(note("当前思考强度不支持 Fast 快速层，切换思考强度后即可启用"), spaced(10));
            }
        } else {
            LinearLayout statusPod = ui.column();
            statusPod.setBackground(ui.bordered(ui.bg, 16));
            statusPod.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
            statusPod.addView(ui.text(message, 14, false), wrap());
            if (!selectedId.isEmpty()) {
                statusPod.addView(note("上次选择：" + NativeUsageFormat.modelName(selectedId)), spaced(6));
            }
            if (state != NativeSessionControlPolicy.CatalogState.LOADING) {
                statusPod.addView(ui.button("重新获取模型列表", false, () -> {
                    if (fetching) host.notice("正在读取模型列表");
                    else if (sending || SystemClock.elapsedRealtime() < Math.max(retryAt, nextFetch)) host.notice("请稍后再试");
                    else refresh();
                }), spaced(12));
            }
            settingsView.addView(statusPod, wrap());
        }

        if (fastCorrupt || fastNext != null && (!valid || NativeSessionControlPolicy.fastReply(catalog, selection, fastNext, now()) == null))
            settingsView.addView(note("上次的快速模式选择需要重新确认"), spaced(8));
        if (planCorrupt || planNext != null && (!valid || NativeSessionControlPolicy.planReply(catalog, selection, planNext, now()) == null))
            settingsView.addView(note("上次的计划模式选择需要重新确认"), spaced(8));
        if (replyLocked)
            settingsView.addView(note("等待上一条回复确认后即可修改模型设置"), spaced(8));

        boolean hasAnyOverride = selection != null || selectionCorrupt || fastNext != null || fastCorrupt || planNext != null || planCorrupt;
        if (hasAnyOverride && (!valid || selectionCorrupt || fastCorrupt || planCorrupt)) {
            TextView resetBtn = ui.button("恢复继承电脑默认设置", false, () -> saveSelection(null, true));
            resetBtn.setEnabled(!replyLocked);
            resetBtn.setAlpha(replyLocked ? .5f : 1f);
            settingsView.addView(resetBtn, spaced(10));
        }

        settingsView.addView(note("更改保存在手机端，随下一条回复发往电脑"), spaced(12));
        TextView doneBtn = ui.button("完成", () -> {
            if (settingsSheet != null) settingsSheet.dismiss();
        });
        settingsView.addView(doneBtn, spaced(12));
    }

    private void selectModelInline(JSONObject snapshotCatalog, String modelId) {
        if (!active() || replyLocked || !validCatalog()
                || !snapshotCatalog.optString("catalog_revision").equals(catalog().optString("catalog_revision"))) {
            host.notice("模型目录已更新，请重新选择");
            refresh();
            return;
        }
        if (modelId == null) {
            saveSelection(null, true);
            return;
        }
        if (selection != null && modelId.equals(selection.optString("model"))
                && NativeSessionControlPolicy.selection(selection, catalog(), now())) {
            return;
        }
        try {
            saveSelection(override(snapshotCatalog, modelId, null), true);
        } catch (Exception invalid) {
            host.notice("无法保存模型选择");
        }
    }

    private JSONObject override(JSONObject catalog, String model, String effort) throws org.json.JSONException {
        return new JSONObject().put("provider", catalog.optString("provider")).put("catalog_revision", catalog.optString("catalog_revision"))
            .put("model", model).put("effort", effort == null ? JSONObject.NULL : effort).put("apply_at", "next_message");
    }

    private void selectEffortInline(JSONObject observed, JSONObject original, String modelId, String effortValue) {
        if (!active() || replyLocked || !validCatalog()) return;
        JSONObject latest = NativeSessionControlPolicy.effortModel(catalog(), selection, selectionCorrupt, now());
        if (!String.valueOf(original).equals(String.valueOf(selection))
                || latest == null || !modelId.equals(latest.optString("id"))) {
            host.notice("模型选项已变化，请重新选择");
            renderSettings();
            return;
        }
        if (original == null && effortValue == null) return;
        try {
            saveSelection(override(observed, modelId, effortValue));
        } catch (Exception invalid) {
            host.notice("无法保存思考强度");
        }
    }

    private JSONObject stageOneTurn(JSONObject catalog, JSONObject selected, boolean plan) {
        long time = now();
        return plan ? NativeSessionControlPolicy.stagePlan(catalog, selected, selectionCorrupt, time)
                : NativeSessionControlPolicy.stageFast(catalog, selected, selectionCorrupt, time);
    }
    private JSONObject oneTurnPreference(boolean plan) { return plan ? planNext : fastNext; }

    private void toggleOneTurnInline(boolean plan) {
        if (!active() || replyLocked) return;
        String label = plan ? "规划模式" : "快速模式";
        if (!validCatalog()) {
            host.notice("正在重新确认" + label + "，请稍后重试");
            refresh();
            return;
        }
        JSONObject latestCatalog = catalog();
        JSONObject proposed = stageOneTurn(latestCatalog, selection, plan);
        if (proposed == null) {
            host.notice(label + "已变化，请重新选择");
            renderSettings();
            return;
        }
        boolean currentlyActive = (plan
                ? NativeSessionControlPolicy.planReply(latestCatalog, selection, planNext, now())
                : NativeSessionControlPolicy.fastReply(latestCatalog, selection, fastNext, now())) != null;
        JSONObject nextVal = currentlyActive ? null : proposed;
        try {
            String preferenceKey = key + (plan ? ":plan_next" : ":fast_next");
            SharedPreferences.Editor editor = preferences.edit();
            if (nextVal == null) editor.remove(preferenceKey);
            else editor.putString(preferenceKey, nextVal.toString());
            if (!editor.commit()) {
                host.notice("无法保存设置，请稍后再试");
                return;
            }
        } catch (Exception invalid) {
            host.notice("无法保存" + label);
            return;
        }
        if (plan) { planNext = nextVal; planCorrupt = false; }
        else { fastNext = nextVal; fastCorrupt = false; }
        renderSettings();
        changed.run();
    }
    private void saveSelection(JSONObject selected) { saveSelection(selected, false); }

    private void saveSelection(JSONObject selected, boolean clearTurnOptions) {
        if (!active() || replyLocked) return;
        if (selected != null && (!validCatalog() || !NativeSessionControlPolicy.selection(selected, catalog(), now()))) {
            host.notice("模型选项已变化，请重新选择"); return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (selected == null) editor.remove(key + ":selection"); else editor.putString(key + ":selection", selected.toString());
        if (clearTurnOptions) editor.remove(key + ":fast_next").remove(key + ":plan_next");
        if (!editor.commit()) { host.notice("无法保存设置，请稍后再试"); return; }
        selection = copy(selected); selectionCorrupt = false;
        if (clearTurnOptions) { fastNext = null; fastCorrupt = false; planNext = null; planCorrupt = false; }
        renderSettings(); changed.run();
    }
    private void render() {
        JSONArray pending = response == null ? null : response.optJSONArray("pending");
        StringBuilder expiry = new StringBuilder();
        boolean needsAction = false;
        if (pending != null) for (int i = 0; i < Math.min(20, pending.length()); i++) { JSONObject request = pending.optJSONObject(i); expiry.append(NativeSessionControlPolicy.actionable(request, capabilities(), now())); needsAction |= NativeSessionControlPolicy.pending(request, now()); }
        String signature = String.valueOf(pending) + capabilities().toString() + NativeSessionControlPolicy.nullableText(response, "reason") + fresh() + expiry + needsAction + sending + sendingRequestId + feedback + waiting + pendingKind + recordsCorrupt + records.toString() + sessionMemos.size();
        if (signature.equals(renderKey)) return; renderKey = signature;
        view.removeAllViews();
        boolean hasPending = pending != null && pending.length() > 0;
        if (!hasPending && !waiting && feedback.isEmpty()) { view.setVisibility(View.GONE); return; }
        view.setVisibility(View.VISIBLE);
        LinearLayout containerCard = ui.card();
        containerCard.setBackground(ui.bordered(ui.surface, 20));
        containerCard.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        LinearLayout heading = ui.row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.text(needsAction || !pendingKind.isEmpty() && response == null ? "待你处理" : "交互记录", 16, true), new LinearLayout.LayoutParams(0, -2, 1));
        View refBtn = ui.iconButton("refresh", "刷新提问和批准状态", this::refresh);
        refBtn.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        refBtn.setMinimumWidth(ui.dp(36)); refBtn.setMinimumHeight(ui.dp(36));
        heading.addView(refBtn);
        containerCard.addView(heading, wrap());
        if (!feedback.isEmpty()) containerCard.addView(note(feedback), spaced(8));
        if (recordsCorrupt) containerCard.addView(note("原发送记录无法确认，手机已停用操作；请到电脑核对"), spaced(8));
        if (!hasPending) {
            String reason = response == null && "question".equals(pendingKind) ? "正在读取电脑提出的问题和选项…"
                    : response == null ? "正在确认任务的交互能力…" : NativeSessionControlPolicy.nullableText(response, "reason");
            containerCard.addView(note(reason.isEmpty() ? "尚未收到可在手机处理的请求，可先到电脑查看" : reason), spaced(8));
            view.addView(containerCard, wrap());
        } else if (pending.length() > 20) {
            containerCard.addView(note("请求数量超出显示范围，请到电脑查看"), spaced(8));
            view.addView(containerCard, wrap());
        } else {
            view.addView(containerCard, wrap());
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (int i = 0; i < pending.length(); i++) {
                JSONObject request = pending.optJSONObject(i);
                if (!NativeSessionControlPolicy.request(request) || seen.put(request.optString("id"), true) != null) { view.addView(note("请求信息不完整或重复，已停用操作，请到电脑查看"), spaced(10)); continue; }
                addRequest(request);
            }
        }
    }
    private void addRequest(JSONObject request) {
        String id = request.optString("id");
        DecisionMemo memo = sessionMemos.get(id);
        JSONObject record = records.optJSONObject(id);
        boolean terminal = NativeSessionControlPolicy.terminal(request);
        String shownTitle = request.optString("title");
        if (terminal && "请求已结束".equals(shownTitle) && memo != null && !memo.title.isEmpty()) {
            shownTitle = memo.title;
        }
        LinearLayout card = ui.card(); view.addView(card, spaced(12));
        card.addView(ui.toolBadge(request.optString("provider")), wrap());
        card.addView(ui.text(shownTitle, 17, true), spaced(8));
        if (!request.optString("detail").isEmpty()) { TextView detail = ui.text(request.optString("detail"), 14, false); detail.setTextIsSelectable(true); card.addView(detail, spaced(10)); }
        card.addView(note(NativeSessionControlPolicy.stateLabel(request, now())), spaced(10));
        String receipt = NativeSessionControlPolicy.receiptTrail(
                request, sending && id.equals(sendingRequestId), record != null);
        if (terminal) {
            String summary = memo != null && !memo.summary.isEmpty()
                    ? memo.summary
                    : NativeSessionControlPolicy.decisionKindLabel(NativeSessionControlPolicy.nullableText(request, "decision_kind"));
            if (!summary.isEmpty()) {
                card.addView(ui.text(summary, 14, false), spaced(10));
            }
            if (!receipt.isEmpty()) addReceiptBadge(card, receipt, request);
            return;
        }
        String fingerprint = digest(NativeSessionControlPolicy.fingerprint(request));
        boolean enabled = active() && fresh() && !sending && !recordsCorrupt && NativeSessionControlPolicy.actionable(request, capabilities(), now());
        if ("approval".equals(request.optString("kind"))) {
            card.addView(ui.text("本次操作", 12, true), spaced(14));
            TextView operation = ui.text(request.optString("operation"), 15, false); operation.setTextIsSelectable(true); operation.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); card.addView(operation, spaced(8));
            card.addView(note("允许后，电脑可执行上面列出的操作；此批准不适用于其他请求"), spaced(10));
        }
        if (record != null || "submitted".equals(request.optString("state"))) {
            if (record != null) {
                boolean unchanged = fingerprint.equals(record.optString("fingerprint"));
                JSONObject payload = record.optJSONObject("payload");
                if (unchanged && payload != null) card.addView(ui.text(decisionSummary(request, payload), 14, false), spaced(10));
                if ("pending".equals(request.optString("state"))) {
                    card.addView(note(unchanged ? "已保存本次发送记录；重复发送会沿用同一决定，不会改换选项" : "请求内容已变化，原决定已停用\n请在电脑确认新请求"), spaced(10));
                    TextView retry = ui.button("重试原决定", false, () -> sendDecision(request, payload, fingerprint, true));
                    retry.setEnabled(enabled && unchanged && NativeSessionControlPolicy.decision(request, payload, taskId, now())); retry.setAlpha(retry.isEnabled() ? 1 : .5f);
                    card.addView(retry, spaced(12));
                }
            } else if (memo != null && !memo.summary.isEmpty()) {
                card.addView(ui.text(memo.summary, 14, false), spaced(10));
            }
            if (!receipt.isEmpty()) addReceiptBadge(card, receipt, request);
            return;
        }
        if (!enabled) {
            if ("pending".equals(request.optString("state"))) card.addView(note(fresh() ? "手机暂不能处理这个请求，请到电脑查看" : "状态尚未确认，请先刷新"), spaced(10));
            if ("question".equals(request.optString("kind"))) addQuestions(card, request, fingerprint, false);
            return;
        }
        if ("approval".equals(request.optString("kind"))) {
            TextView allow = ui.button("仅允许这一次", () -> confirmApproval(request, fingerprint)); card.addView(allow, spaced(16));
            card.addView(ui.button("拒绝", false, () -> sendDecision(request, payload(request, "deny", new JSONArray()), fingerprint, false)), spaced(8));
        } else addQuestions(card, request, fingerprint, true);
    }
    private void addReceiptBadge(LinearLayout card, String receipt, JSONObject request) {
        String outcome = NativeSessionControlPolicy.nullableText(request, "outcome");
        boolean rejected = "rejected".equals(outcome) || receipt.contains("已拒绝");
        boolean adopted = "adopted".equals(outcome) || receipt.contains("已采用");
        TextView badge = ui.text(receipt, 13, true);
        badge.setTextColor(rejected ? ui.warning : adopted ? ui.accent : ui.text);
        badge.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        badge.setBackground(ui.rounded(rejected ? (ui.dark ? 0xFF423B2C : 0xFFF3EBDC) : ui.soft, 10));
        card.addView(badge, spaced(12));
    }
    private void confirmApproval(JSONObject request, String fingerprint) {
        if (!usable(request, fingerprint)) return;
        if (approvalSheet != null) { approvalSheet.dismiss(); approvalSheet = null; }
        LivePresentationSheet sheet = new LivePresentationSheet(ui.activity, "确认本次批准", "取消并关闭");
        approvalSheet = sheet;
        LinearLayout body = ui.column();

        LinearLayout codePod = ui.column();
        codePod.setBackground(ui.bordered(ui.bg, 14));
        codePod.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        TextView opText = ui.text(request.optString("operation"), 14, false);
        opText.setTextIsSelectable(true);
        opText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        opText.setLineSpacing(ui.dp(4), 1.12f);
        codePod.addView(opText, wrap());
        body.addView(codePod, wrap());

        body.addView(note("允许后，电脑将执行以上操作，仅限这一次请求生效。"), spaced(12));
        body.addView(ui.space(18));

        LinearLayout actions = ui.row();
        TextView cancelBtn = ui.button("取消", false, sheet::cancel);
        cancelBtn.setTextColor(ui.muted);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, -2, 1);
        cancelLp.setMarginEnd(ui.dp(10));
        actions.addView(cancelBtn, cancelLp);

        TextView allowBtn = ui.button("仅允许这一次", () -> {
            sheet.dismiss();
            sendDecision(request, payload(request, "allow_once", new JSONArray()), fingerprint, false);
        });
        actions.addView(allowBtn, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions, wrap());

        sheet.body(body);
        sheet.setOnDismissListener(ignored -> { if (approvalSheet == sheet) approvalSheet = null; });
        sheet.show();
    }
    private void addQuestions(LinearLayout card, JSONObject request, String fingerprint, boolean enabled) {
        QuestionDraft draft = drafts.get(fingerprint);
        if (draft == null) { draft = new QuestionDraft(); drafts.put(fingerprint, draft); }
        final QuestionDraft bound = draft;
        JSONArray questions = request.optJSONArray("questions");
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i); String questionId = question.optString("id");
            card.addView(ui.text(question.optString("text"), 15, true), spaced(16));
            List<String> choices = bound.choices.get(questionId); if (choices == null) { choices = new ArrayList<>(); bound.choices.put(questionId, choices); }
            final List<String> selected = choices; final List<CompoundButton> buttons = new ArrayList<>();
            final EditText[] custom = new EditText[1];
            JSONArray options = question.optJSONArray("options");
            if (options.length() > 1) card.addView(note(question.optBoolean("multiple") ? "可多选" : "单选"), spaced(6));
            for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.optJSONObject(j); final String optionId = option.optString("id");
                CompoundButton button = question.optBoolean("multiple") ? new CheckBox(ui.activity) : new RadioButton(ui.activity);
                button.setTypeface(ui.regular); button.setTextSize(15); button.setTextColor(ui.text); button.setButtonTintList(ColorStateList.valueOf(ui.accent)); button.setMinHeight(ui.dp(48));
                button.setText(option.optString("label")); button.setChecked(selected.contains(optionId)); button.setTag(optionId); buttons.add(button);
                button.setEnabled(enabled);
                button.setContentDescription(option.optString("label") + (option.optString("description").isEmpty() ? "" : "，" + option.optString("description")));
                button.setOnClickListener(v -> {
                    bound.text.remove(questionId); if (custom[0] != null) custom[0].setText("");
                    if (!question.optBoolean("multiple")) { selected.clear(); selected.add(optionId); for (CompoundButton other : buttons) other.setChecked(optionId.equals(other.getTag())); }
                    else if (button.isChecked()) { if (!selected.contains(optionId)) selected.add(optionId); } else selected.remove(optionId);
                }); card.addView(button, spaced(6));
                if (!option.optString("description").isEmpty()) { TextView description = note(option.optString("description")); description.setPadding(ui.dp(36), 0, 0, 0); card.addView(description, wrap()); }
            }
            if (question.optBoolean("allows_text") && enabled) {
                EditText input = new EditText(ui.activity); custom[0] = input; input.setTypeface(ui.regular); input.setTextSize(15); input.setTextColor(ui.text); input.setHintTextColor(ui.muted); input.setHint("自定义回答（代替选项）"); input.setContentDescription(question.optString("text") + "，自定义回答，代替上方选项");
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)}); input.setMinLines(2); input.setText(bound.text.get(questionId));
                input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void afterTextChanged(Editable text) { bound.text.put(questionId, text.toString()); if (!text.toString().trim().isEmpty()) { selected.clear(); for (CompoundButton option : buttons) option.setChecked(false); } } public void onTextChanged(CharSequence s, int start, int before, int count) { } }); card.addView(input, spaced(8));
            }
        }
        if (!enabled) return;
        card.addView(ui.button("发送回答", () -> {
            JSONArray answers = new JSONArray();
            try {
                for (int i = 0; i < questions.length(); i++) { JSONObject question = questions.optJSONObject(i); String id = question.optString("id"); JSONArray choices = new JSONArray(); for (String value : bound.choices.get(id)) choices.put(value); String text = bound.text.get(id); answers.put(new JSONObject().put("question_id", id).put("choices", choices).put("text", text == null || text.trim().isEmpty() ? JSONObject.NULL : text.trim())); }
                if (!NativeSessionControlPolicy.answers(request, answers)) { host.notice("请为每个问题选择选项或填写允许的文字回答"); return; }
                sendDecision(request, payload(request, "answer", answers), fingerprint, false);
            } catch (Exception invalid) { host.notice("回答未能确认，请检查后重试"); }
        }), spaced(16));
    }
    private JSONObject find(String id) {
        JSONArray pending = response == null ? null : response.optJSONArray("pending"); JSONObject found = null;
        if (pending != null) for (int i = 0; i < pending.length(); i++) { JSONObject value = pending.optJSONObject(i); if (value != null && id.equals(value.optString("id"))) { if (found != null) return null; found = value; } }
        return found;
    }
    private void pruneFinished() {
        if (recordsCorrupt) return;
        JSONArray pending = response == null ? null : response.optJSONArray("pending"); if (pending == null) return;
        JSONObject next = copy(records); boolean changed = false;
        for (int i = 0; i < pending.length(); i++) {
            JSONObject request = pending.optJSONObject(i);
            if (NativeSessionControlPolicy.request(request) && NativeSessionControlPolicy.terminal(request)) {
                JSONObject old = next.optJSONObject(request.optString("id"));
                if (old != null) { drafts.remove(old.optString("fingerprint")); next.remove(request.optString("id")); changed = true; }
            }
        }
        if (changed && preferences.edit().putString(key + ":decisions", next.toString()).commit()) records = next;
    }
    private boolean usable(JSONObject request, String fingerprint) {
        JSONObject latest = find(request.optString("id"));
        boolean valid = active() && fresh() && !sending && !recordsCorrupt && NativeSessionControlPolicy.actionable(latest, capabilities(), now()) && fingerprint.equals(digest(NativeSessionControlPolicy.fingerprint(latest)));
        if (!valid) { host.notice("请求状态已变化，请刷新后确认"); refresh(); } return valid;
    }
    private JSONObject payload(JSONObject request, String action, JSONArray answers) {
        try { return new JSONObject().put("task_id", taskId).put("request_id", request.optString("id")).put("revision", request.optString("revision")).put("decision_id", UUID.randomUUID().toString()).put("decision", action).put("answers", answers); }
        catch (Exception invalid) { return null; }
    }
    private void sendDecision(JSONObject request, JSONObject payload, String fingerprint, boolean retry) {
        if (!usable(request, fingerprint) || !NativeSessionControlPolicy.decision(request, payload, taskId, now())) return;
        String id = request.optString("id"); JSONObject record = records.optJSONObject(id);
        if (retry && (record == null || !fingerprint.equals(record.optString("fingerprint")) || !payload.toString().equals(String.valueOf(record.optJSONObject("payload")))) || !retry && record != null) { host.notice("已有发送记录，请先确认原决定的状态"); return; }
        try {
            if (!retry) {
                if (records.length() >= 64) { host.notice("待确认的发送记录过多，请先在电脑确认结果"); return; }
                JSONObject next = copy(records); next.put(id, new JSONObject().put("fingerprint", fingerprint).put("payload", payload));
                if (!preferences.edit().putString(key + ":decisions", next.toString()).commit()) { host.notice("无法保存决定，尚未发送"); return; }
                records = next;
            }
        } catch (Exception invalid) { host.notice("无法保存决定，尚未发送"); return; }
        sessionMemos.put(id, new DecisionMemo(request.optString("title"), decisionSummary(request, payload)));
        sending = true; sendingRequestId = id; render();
        transport.request("POST", "/api/native/tasks/controls/decision", copy(payload), (value, failure) -> {
            sending = false; sendingRequestId = ""; observedAt = 0; feedback = failure == null ? "" : "发送结果尚未确认；先刷新，重试会使用原决定";
            render(); nextFetch = 0; refresh();
        });
    }
    private String decisionSummary(JSONObject request, JSONObject payload) {
        String action = payload.optString("decision");
        if ("allow_once".equals(action)) return "原决定：仅允许这一次";
        if ("deny".equals(action)) return "原决定：拒绝";
        StringBuilder text = new StringBuilder("原回答");
        JSONArray questions = request.optJSONArray("questions"), answers = payload.optJSONArray("answers");
        if (questions != null && answers != null) for (int i = 0; i < Math.min(questions.length(), answers.length()); i++) {
            JSONObject question = questions.optJSONObject(i), answer = answers.optJSONObject(i); if (question == null || answer == null) continue;
            text.append("\n").append(question.optString("text")).append("：");
            if (!answer.isNull("text")) text.append(answer.optString("text"));
            JSONArray choices = answer.optJSONArray("choices"), options = question.optJSONArray("options");
            if (choices != null && options != null) for (int j = 0; j < choices.length(); j++) for (int k = 0; k < options.length(); k++) { JSONObject option = options.optJSONObject(k); if (choices.optString(j).equals(option.optString("id"))) { if (j > 0) text.append("、"); text.append(option.optString("label")); } }
        }
        return text.toString();
    }
    private TextView note(String value) { TextView text = ui.text(value, 13, false); text.setTextColor(ui.muted); return text; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams params = wrap(); params.topMargin = ui.dp(top); return params; }
    private static JSONObject copy(JSONObject value) { if (value == null) return null; try { return new JSONObject(value.toString()); } catch (Exception invalid) { throw new IllegalStateException("Invalid JSON", invalid); } }
    private static String digest(String value) {
        try { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class QuestionDraft {
        final Map<String, List<String>> choices = new LinkedHashMap<>();
        final Map<String, String> text = new LinkedHashMap<>();
    }
    private static final class DecisionMemo {
        final String title, summary;
        DecisionMemo(String title, String summary) {
            this.title = title == null ? "" : title;
            this.summary = summary == null ? "" : summary;
        }
    }
}
