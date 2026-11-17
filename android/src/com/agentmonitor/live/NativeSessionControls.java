package com.agentmonitor.live;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
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
    private AlertDialog settingsDialog, optionDialog;
    private final Map<String, QuestionDraft> drafts = new LinkedHashMap<>();
    private JSONObject response, selection, fastNext, planNext, records = new JSONObject();
    private boolean fetching, sending, replyLocked, disposed, selectionCorrupt, fastCorrupt, planCorrupt, recordsCorrupt, waiting, controlsReadFailed;
    private long nextFetch, observedAt, retryAt;
    private String renderKey = "", settingsKey = "", feedback = "";

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
    public void update(JSONObject task) { waiting = "waiting".equals(task.optString("status")); tick(); }
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
        if (optionDialog != null) optionDialog.dismiss();
        if (settingsDialog != null) settingsDialog.dismiss();
    }
    public void dispose() { disposed = true; drafts.clear(); response = null; selection = null; fastNext = null; planNext = null; records = new JSONObject(); }
    public void setReplyLocked(boolean value) { if (replyLocked == value) return; replyLocked = value; renderSettings(); }
    public boolean canSendReply() {
        return !selectionCorrupt && !fastCorrupt && !planCorrupt
                && (selection == null || validCatalog() && NativeSessionControlPolicy.selection(selection, catalog(), now()))
                && (fastNext == null && planNext == null || validCatalog()
                    && NativeSessionControlPolicy.oneTurnSettings(catalog(), selection, fastNext, planNext, now()) != null);
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
        if (!active() || settingsDialog != null) return;
        ScrollView scroll = new ScrollView(ui.activity);
        settingsView = ui.column();
        settingsView.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8));
        scroll.addView(settingsView);
        settingsKey = "";
        renderSettings();
        settingsDialog = new AlertDialog.Builder(ui.activity).setTitle("模型设置")
            .setView(scroll).setPositiveButton("完成", null).create();
        settingsDialog.setOnDismissListener(dialog -> { settingsDialog = null; settingsView = null; });
        settingsDialog.show();
        tick();
    }

    private String modelName(JSONObject model) {
        return NativeUsageFormat.modelName(model.optString("label", model.optString("id")));
    }
    private void optionButton(String label, Runnable action) {
        TextView button = ui.button(label, false, action);
        button.setEnabled(!replyLocked); button.setAlpha(replyLocked ? .5f : 1);
        settingsView.addView(button, spaced(8));
    }
    private void renderSettings() {
        if (settingsView == null) return;
        JSONObject catalog = catalog();
        NativeSessionControlPolicy.CatalogState state = catalogState();
        boolean valid = state == NativeSessionControlPolicy.CatalogState.READY;
        String message = NativeSessionControlPolicy.catalogMessage(state, response);
        // Renewing only the expiry should not rebuild buttons during a touch.
        String catalogOptions = !valid ? "" : NativeSessionControlPolicy.nullableText(catalog, "provider") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "catalog_revision") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "current_model") + "\n"
                + NativeSessionControlPolicy.nullableText(catalog, "current_effort") + "\n"
                + String.valueOf(catalog.optJSONArray("collaboration_modes")) + "\n"
                + String.valueOf(catalog.optJSONArray("models"));
        String signature = catalogOptions + state + message + String.valueOf(selection) + selectionCorrupt
                + String.valueOf(fastNext) + fastCorrupt + String.valueOf(planNext) + planCorrupt + replyLocked;
        if (signature.equals(settingsKey)) return; settingsKey = signature;
        settingsView.removeAllViews();
        settingsView.addView(note("此任务的回复设置"), wrap());
        String selectedId = NativeSessionControlPolicy.nullableText(selection, "model");
        if (valid) {
            String current = NativeSessionControlPolicy.nullableText(catalog, "current_model");
            JSONObject currentModel = NativeSessionControlPolicy.model(catalog, current);
            String effort = NativeSessionControlPolicy.nullableText(catalog, "current_effort");
            if (!current.isEmpty()) settingsView.addView(note("最近使用  " + (currentModel == null ? NativeUsageFormat.modelName(current) : modelName(currentModel)) +
                (effort.isEmpty() ? "" : " · " + NativeSessionControlPolicy.effortLabel(effort))), spaced(8));
            JSONObject model = NativeSessionControlPolicy.effortModel(catalog, selection, selectionCorrupt, now());
            String selectedLabel = selection == null ? "继承电脑设置" : model == null ? "重新选择" : modelName(model);
            optionButton("模型 · " + (selectionCorrupt ? "重新选择" : selectedLabel), this::chooseModel);
            if (model != null && model.optJSONArray("efforts").length() > 0) {
                String selectedEffort = NativeSessionControlPolicy.nullableText(selection, "effort");
                optionButton("思考强度 · " + (selection == null ? "继承电脑设置" : selectedEffort.isEmpty() ? "模型默认" : NativeSessionControlPolicy.effortLabel(selectedEffort)), this::chooseEffort);
            } else {
                settingsView.addView(note(model == null ? "请先选择模型，再查看可用的思考强度" : "此模型未提供可选的思考强度"), spaced(10));
            }
            if (NativeSessionControlPolicy.stageFast(catalog, selection, selectionCorrupt, now()) != null) {
                boolean fast = NativeSessionControlPolicy.fastReply(catalog, selection, fastNext, now()) != null;
                optionButton("速度 · " + (fast ? "快速" : "继承电脑设置"), () -> chooseOneTurn(false));
            } else if (NativeSessionControlPolicy.fastModel(catalog, selection, selectionCorrupt, now()) != null) {
                settingsView.addView(note("当前思考强度无法用于快速模式，请先选择可用的思考强度"), spaced(8));
            }
            if (NativeSessionControlPolicy.stagePlan(catalog, selection, selectionCorrupt, now()) != null) {
                boolean plan = NativeSessionControlPolicy.planReply(catalog, selection, planNext, now()) != null;
                optionButton("回复模式 · " + (plan ? "计划" : "继承电脑设置"), () -> chooseOneTurn(true));
                settingsView.addView(note("计划只用于下一次回复；Codex 提出的选项会出现在任务详情"), spaced(8));
            } else if (NativeSessionControlPolicy.advertisesPlan(catalog)) {
                settingsView.addView(note("请先选择可用的模型和思考强度，再使用计划模式"), spaced(8));
            }
        } else {
            settingsView.addView(ui.text(message, 15, false), spaced(14));
            if (!selectedId.isEmpty()) settingsView.addView(note("上次选择  " + NativeUsageFormat.modelName(selectedId)), spaced(8));
            if (state != NativeSessionControlPolicy.CatalogState.LOADING) {
                settingsView.addView(ui.button("重新获取", false, () -> {
                    if (fetching) host.notice("正在读取模型列表");
                    else if (sending || SystemClock.elapsedRealtime() < Math.max(retryAt, nextFetch)) host.notice("请稍后再试");
                    else refresh();
                }), spaced(12));
            }
        }
        if (fastCorrupt || fastNext != null && (!valid || NativeSessionControlPolicy.fastReply(catalog, selection, fastNext, now()) == null))
            settingsView.addView(note("上次的快速模式选择需要重新确认"), spaced(8));
        if (planCorrupt || planNext != null && (!valid || NativeSessionControlPolicy.planReply(catalog, selection, planNext, now()) == null))
            settingsView.addView(note("上次的计划模式选择需要重新确认"), spaced(8));
        if (selection != null || selectionCorrupt || fastNext != null || fastCorrupt || planNext != null || planCorrupt)
            optionButton("恢复为继承电脑设置", () -> saveSelection(null, true));
        if (replyLocked) settingsView.addView(note("等待已发送的回复确认后即可修改"), spaced(10));
        if (valid) settingsView.addView(note(NativeSessionControlPolicy.fastModel(catalog, selection, selectionCorrupt, now()) == null
                ? "选择保存在手机上，随下次回复提交，此会话后续可能继续沿用"
                : "模型和思考强度保存在手机上，随回复提交。快速模式只用于下一次回复"), spaced(10));
    }
    private void chooseModel() {
        if (!active() || replyLocked || !validCatalog()) { refresh(); return; }
        final JSONObject catalog = copy(catalog()); final JSONArray models = catalog.optJSONArray("models");
        String[] labels = new String[models.length() + 1]; labels[0] = "继承电脑设置";
        int selectedIndex = selection == null && !selectionCorrupt ? 0 : -1;
        boolean selectedValid = selection != null && NativeSessionControlPolicy.selection(selection, catalog, now());
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            labels[i + 1] = modelName(item);
            if (selectedValid && item.optString("id").equals(selection.optString("model"))) selectedIndex = i + 1;
        }
        java.util.Set<String> ambiguous = new java.util.HashSet<>(), seen = new java.util.HashSet<>();
        for (String label : labels) if (!seen.add(label)) ambiguous.add(label);
        for (int i = 1; i < labels.length; i++) if (ambiguous.contains(labels[i])) labels[i] += " · " + models.optJSONObject(i - 1).optString("id");
        optionDialog = new AlertDialog.Builder(ui.activity).setTitle("下次回复的模型").setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
            dialog.dismiss();
            if (!active() || replyLocked || !validCatalog() || !catalog.optString("catalog_revision").equals(catalog().optString("catalog_revision"))) { host.notice("模型选项已变化，请重新打开选择"); return; }
            if (which == 0) { saveSelection(null, true); return; }
            String id = models.optJSONObject(which - 1).optString("id");
            if (selection != null && id.equals(selection.optString("model")) && NativeSessionControlPolicy.selection(selection, catalog(), now())) return;
            try { saveSelection(override(catalog, id, null), true); }
            catch (Exception invalid) { host.notice("无法保存模型选择"); }
        }).setNegativeButton("取消", null).show();
    }
    private JSONObject override(JSONObject catalog, String model, String effort) throws org.json.JSONException {
        return new JSONObject().put("provider", catalog.optString("provider")).put("catalog_revision", catalog.optString("catalog_revision"))
            .put("model", model).put("effort", effort == null ? JSONObject.NULL : effort).put("apply_at", "next_message");
    }
    private void chooseEffort() {
        if (!active() || replyLocked || !validCatalog()) return;
        final JSONObject observed = copy(catalog()), original = copy(selection);
        JSONObject model = NativeSessionControlPolicy.effortModel(observed, original, selectionCorrupt, now());
        if (model == null) return;
        final String id = model.optString("id");
        final JSONArray efforts = model.optJSONArray("efforts");
        String[] labels = new String[efforts.length() + 1];
        labels[0] = original == null ? "继承电脑设置" : "使用模型默认值";
        String previous = NativeSessionControlPolicy.nullableText(original, "effort");
        int selectedIndex = 0;
        for (int i = 0; i < efforts.length(); i++) {
            labels[i + 1] = NativeSessionControlPolicy.effortLabel(efforts.optString(i));
            if (previous.equals(efforts.optString(i))) selectedIndex = i + 1;
        }
        optionDialog = new AlertDialog.Builder(ui.activity).setTitle(modelName(model) + " · 思考强度").setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
            dialog.dismiss();
            JSONObject latest = NativeSessionControlPolicy.effortModel(catalog(), selection, selectionCorrupt, now());
            if (!active() || replyLocked || !String.valueOf(original).equals(String.valueOf(selection)) || !validCatalog() ||
                    latest == null || !id.equals(latest.optString("id"))) { host.notice("模型选项已变化，请重新选择"); return; }
            if (original == null && which == 0) return;
            try { saveSelection(override(observed, id, which == 0 ? null : efforts.optString(which - 1))); }
            catch (Exception invalid) { host.notice("无法保存思考强度"); }
        }).setNegativeButton("取消", null).show();
    }
    private JSONObject stageOneTurn(JSONObject catalog, JSONObject selected, boolean plan) {
        long time = now();
        return plan ? NativeSessionControlPolicy.stagePlan(catalog, selected, selectionCorrupt, time)
                : NativeSessionControlPolicy.stageFast(catalog, selected, selectionCorrupt, time);
    }
    private JSONObject oneTurnPreference(boolean plan) { return plan ? planNext : fastNext; }
    private void chooseOneTurn(boolean plan) {
        if (!active() || replyLocked) return;
        String label = plan ? "回复模式" : "速度选项";
        if (!validCatalog()) {
            host.notice("正在重新确认" + label + "，请稍后重试"); refresh(); return;
        }
        JSONObject observed = copy(catalog()), original = copy(selection), originalOption = copy(oneTurnPreference(plan));
        JSONObject proposed = stageOneTurn(observed, original, plan);
        if (proposed == null) { host.notice(label + "已变化，请重新选择"); renderSettings(); return; }
        boolean activeChoice = (plan ? NativeSessionControlPolicy.planReply(observed, original, originalOption, now())
                : NativeSessionControlPolicy.fastReply(observed, original, originalOption, now())) != null;
        optionDialog = new AlertDialog.Builder(ui.activity).setTitle(plan ? "下次回复的模式" : "下次回复的速度")
            .setSingleChoiceItems(new String[]{"继承电脑设置", plan ? "计划" : "快速"}, activeChoice ? 1 : 0, (dialog, which) -> {
                if (!active() || replyLocked || which < 0 || which > 1) { dialog.dismiss(); return; }
                AlertDialog picker = (AlertDialog) dialog;
                if (!validCatalog()) {
                    picker.getListView().setItemChecked(activeChoice ? 1 : 0, true);
                    picker.setTitle(label + "暂不可用，请稍后重试");
                    refresh();
                    return;
                }
                JSONObject latest = stageOneTurn(catalog(), selection, plan);
                if (latest == null || !proposed.optString("model").equals(latest.optString("model"))
                        || !proposed.optString("catalog_revision").equals(latest.optString("catalog_revision"))
                        || !String.valueOf(original).equals(String.valueOf(selection))
                        || !String.valueOf(originalOption).equals(String.valueOf(oneTurnPreference(plan)))) {
                    dialog.dismiss(); renderSettings(); host.notice(label + "已变化，请重新选择"); return;
                }
                JSONObject selected = which == 0 ? null : latest;
                try {
                    String preferenceKey = key + (plan ? ":plan_next" : ":fast_next");
                    SharedPreferences.Editor editor = preferences.edit();
                    if (selected == null) editor.remove(preferenceKey); else editor.putString(preferenceKey, selected.toString());
                    if (!editor.commit()) { picker.getListView().setItemChecked(activeChoice ? 1 : 0, true); host.notice("无法保存设置，请稍后再试"); return; }
                } catch (Exception invalid) { picker.getListView().setItemChecked(activeChoice ? 1 : 0, true); host.notice("无法保存" + label); return; }
                if (plan) { planNext = selected; planCorrupt = false; }
                else { fastNext = selected; fastCorrupt = false; }
                dialog.dismiss(); renderSettings(); changed.run();
            }).setNegativeButton("取消", null).show();
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
        String signature = String.valueOf(pending) + capabilities().toString() + NativeSessionControlPolicy.nullableText(response, "reason") + fresh() + expiry + needsAction + sending + feedback + waiting + recordsCorrupt + records.toString();
        if (signature.equals(renderKey)) return; renderKey = signature;
        view.removeAllViews();
        boolean hasPending = pending != null && pending.length() > 0;
        if (!hasPending && !waiting && feedback.isEmpty()) { view.setVisibility(View.GONE); return; }
        view.setVisibility(View.VISIBLE);
        LinearLayout heading = ui.row(); heading.addView(ui.text(needsAction ? "待你处理" : "交互记录", 16, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(ui.iconButton("refresh", "刷新提问和批准状态", this::refresh)); view.addView(heading, wrap());
        if (!feedback.isEmpty()) view.addView(note(feedback), spaced(8));
        if (recordsCorrupt) view.addView(note("原发送记录无法确认，手机已停用操作；请到电脑核对"), spaced(8));
        if (!hasPending) {
            String reason = response == null ? "正在确认任务的交互能力" : NativeSessionControlPolicy.nullableText(response, "reason");
            view.addView(note(reason.isEmpty() ? "尚未收到可在手机处理的请求\n可先到电脑查看" : reason), spaced(8));
        } else if (pending.length() > 20) view.addView(note("请求数量超出显示范围，请到电脑查看"), spaced(8));
        else {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (int i = 0; i < pending.length(); i++) {
                JSONObject request = pending.optJSONObject(i);
                if (!NativeSessionControlPolicy.request(request) || seen.put(request.optString("id"), true) != null) { view.addView(note("请求信息不完整或重复，已停用操作，请到电脑查看"), spaced(10)); continue; }
                addRequest(request);
            }
        }
        view.addView(ui.space(24));
    }
    private void addRequest(JSONObject request) {
        LinearLayout card = ui.card(); view.addView(card, spaced(12));
        card.addView(ui.toolBadge(request.optString("provider")), wrap());
        card.addView(ui.text(request.optString("title"), 17, true), spaced(8));
        if (!request.optString("detail").isEmpty()) { TextView detail = ui.text(request.optString("detail"), 14, false); detail.setTextIsSelectable(true); card.addView(detail, spaced(10)); }
        card.addView(note(NativeSessionControlPolicy.stateLabel(request, now())), spaced(10));
        if (NativeSessionControlPolicy.terminal(request)) return;
        String id = request.optString("id"), fingerprint = digest(NativeSessionControlPolicy.fingerprint(request));
        JSONObject record = records.optJSONObject(id);
        boolean enabled = active() && fresh() && !sending && !recordsCorrupt && NativeSessionControlPolicy.actionable(request, capabilities(), now());
        if ("approval".equals(request.optString("kind"))) {
            card.addView(ui.text("本次操作", 12, true), spaced(14));
            TextView operation = ui.text(request.optString("operation"), 15, false); operation.setTextIsSelectable(true); operation.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); card.addView(operation, spaced(8));
            card.addView(note("允许后，电脑可执行上面列出的操作；此批准不适用于其他请求"), spaced(10));
        }
        if (record != null) {
            boolean unchanged = fingerprint.equals(record.optString("fingerprint"));
            card.addView(note(unchanged ? "已保存本次发送记录；重复发送会沿用同一决定，不会改换选项" : "请求内容已变化，原决定已停用\n请在电脑确认新请求"), spaced(12));
            JSONObject payload = record.optJSONObject("payload");
            if (unchanged && payload != null) card.addView(ui.text(decisionSummary(request, payload), 14, false), spaced(10));
            TextView retry = ui.button("重试原决定", false, () -> sendDecision(request, payload, fingerprint, true));
            retry.setEnabled(enabled && unchanged && NativeSessionControlPolicy.decision(request, payload, taskId, now())); retry.setAlpha(retry.isEnabled() ? 1 : .5f);
            card.addView(retry, spaced(12)); return;
        }
        if (!enabled) {
            if ("pending".equals(request.optString("state"))) card.addView(note(fresh() ? "手机暂不能处理这个请求，请到电脑查看" : "状态尚未确认，请先刷新"), spaced(10));
            return;
        }
        if ("approval".equals(request.optString("kind"))) {
            TextView allow = ui.button("仅允许这一次", () -> confirmApproval(request, fingerprint)); card.addView(allow, spaced(16));
            card.addView(ui.button("拒绝", false, () -> sendDecision(request, payload(request, "deny", new JSONArray()), fingerprint, false)), spaced(8));
        } else addQuestions(card, request, fingerprint);
    }
    private void confirmApproval(JSONObject request, String fingerprint) {
        if (!usable(request, fingerprint)) return;
        TextView content = ui.text(request.optString("operation") + "\n\n允许后，电脑可执行以上操作，仅限这一次请求", 15, false);
        content.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8)); content.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(ui.activity); scroll.addView(content);
        new AlertDialog.Builder(ui.activity).setTitle("确认本次批准").setView(scroll).setNegativeButton("取消", null)
            .setPositiveButton("仅允许这一次", (dialog, which) -> sendDecision(request, payload(request, "allow_once", new JSONArray()), fingerprint, false)).show();
    }
    private void addQuestions(LinearLayout card, JSONObject request, String fingerprint) {
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
            for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.optJSONObject(j); final String optionId = option.optString("id");
                CompoundButton button = question.optBoolean("multiple") ? new CheckBox(ui.activity) : new RadioButton(ui.activity);
                button.setTypeface(ui.regular); button.setTextSize(15); button.setTextColor(ui.text); button.setButtonTintList(ColorStateList.valueOf(ui.accent)); button.setMinHeight(ui.dp(48));
                button.setText(option.optString("label")); button.setChecked(selected.contains(optionId)); button.setTag(optionId); buttons.add(button);
                button.setContentDescription(option.optString("label") + (option.optString("description").isEmpty() ? "" : "，" + option.optString("description")));
                button.setOnClickListener(v -> {
                    bound.text.remove(questionId); if (custom[0] != null) custom[0].setText("");
                    if (!question.optBoolean("multiple")) { selected.clear(); selected.add(optionId); for (CompoundButton other : buttons) other.setChecked(optionId.equals(other.getTag())); }
                    else if (button.isChecked()) { if (!selected.contains(optionId)) selected.add(optionId); } else selected.remove(optionId);
                }); card.addView(button, spaced(6));
                if (!option.optString("description").isEmpty()) { TextView description = note(option.optString("description")); description.setPadding(ui.dp(36), 0, 0, 0); card.addView(description, wrap()); }
            }
            if (question.optBoolean("allows_text")) {
                EditText input = new EditText(ui.activity); custom[0] = input; input.setTypeface(ui.regular); input.setTextSize(15); input.setTextColor(ui.text); input.setHintTextColor(ui.muted); input.setHint("自定义回答（代替选项）"); input.setContentDescription(question.optString("text") + "，自定义回答，代替上方选项");
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)}); input.setMinLines(2); input.setText(bound.text.get(questionId));
                input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void afterTextChanged(Editable text) { bound.text.put(questionId, text.toString()); if (!text.toString().trim().isEmpty()) { selected.clear(); for (CompoundButton option : buttons) option.setChecked(false); } } public void onTextChanged(CharSequence s, int start, int before, int count) { } }); card.addView(input, spaced(8));
            }
        }
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
        sending = true; render();
        transport.request("POST", "/api/native/tasks/controls/decision", copy(payload), (value, failure) -> {
            sending = false; observedAt = 0; feedback = failure == null ? "决定已发送，正在确认电脑是否收到" : "发送结果尚未确认；先刷新，重试会使用原决定";
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
}
