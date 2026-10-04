package com.agentmonitor.live;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Final answers and explicit user actions, separate from the frequent status snapshot. */
public final class NativeTaskResults {
    public static final int SAVE_DOCUMENT = 24;
    private static final int PREVIEW_CHARACTERS = 8000;
    public interface Host {
        boolean connected();
        boolean visible();
        void failure(NativeApi.Failure failure);
        void notice(String message);
    }
    private interface Received { void done(JSONObject value, NativeApi.Failure failure); }
    private final NativeUi ui;
    private final Activity activity;
    private final Host host;
    private final String taskId, token, preferencesKey;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    public final LinearLayout view;
    private final LinearLayout outputActions, fileRows, replyCard;
    private final TextView resultText, resultNote, expand, txtButton, copyButton, replyStatus, sendButton;
    private final EditText replyInput;
    private final View refreshButton;
    private final ResultRefresh resultRefresh = new ResultRefresh();
    private JSONObject result;
    private String resultSignature = "", snapshotMarker = "", fullText = "", requestId = "", submittedText = "", commandState = "", capabilityReason = "";
    private boolean syncEnabled, replyBusy, sendBusy, downloadBusy, expanded, replyAvailable, knownReply, loadingDraft, filesPending;
    private volatile boolean disposed;
    private long replyRefreshAt, submittedAt;
    private Download pendingDownload;
    private String downloadFeedback = "";

    public NativeTaskResults(NativeUi ui, String taskId, String token, Host host, Bundle saved) {
        this.ui = ui; this.activity = ui.activity; this.taskId = taskId; this.token = token; this.host = host;
        preferences = activity.getSharedPreferences("native_replies", Activity.MODE_PRIVATE);
        preferencesKey = hash(token) + ":" + hash(taskId);
        requestId = preferences.getString(preferencesKey + ":id", ""); submittedText = preferences.getString(preferencesKey + ":text", "");
        commandState = preferences.getString(preferencesKey + ":state", "");
        submittedAt = preferences.getLong(preferencesKey + ":created_at", 0);
        if (!requestId.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) { requestId = ""; submittedText = ""; commandState = ""; }
        view = ui.column();
        LinearLayout title = ui.row(); title.addView(ui.text("最终结果", 16, true), new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = ui.iconButton("refresh", "刷新最终结果", () -> { resultRefresh.restart(SystemClock.elapsedRealtime()); fetchResult(); fetchReply(true); }); title.addView(refreshButton);
        view.addView(title, wrap()); view.addView(ui.space(8));
        LinearLayout resultCard = ui.card(); resultText = ui.text("输出同步已关闭", 15, false);
        resultText.setTextIsSelectable(true); resultText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); resultText.setLineSpacing(ui.dp(5), 1.08f); resultText.setTextColor(ui.muted);
        resultCard.addView(resultText, wrap());
        expand = ui.button("展开全文", false, () -> { expanded = !expanded; renderText(); }); expand.setVisibility(View.GONE);
        LinearLayout.LayoutParams expandSpace = wrap(); expandSpace.topMargin = ui.dp(16); resultCard.addView(expand, expandSpace);
        view.addView(resultCard, wrap());
        outputActions = ui.row(); copyButton = ui.button("复制", false, this::copy); txtButton = ui.button("保存 TXT", false, this::chooseTxt);
        outputActions.addView(copyButton, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(0, -2, 1); txtParams.leftMargin = ui.dp(10); outputActions.addView(txtButton, txtParams);
        LinearLayout.LayoutParams actionsSpace = wrap(); actionsSpace.topMargin = ui.dp(12); view.addView(outputActions, actionsSpace); outputActions.setVisibility(View.GONE);
        resultNote = ui.text("", 13, false); resultNote.setTextColor(ui.warning); resultNote.setPadding(0, ui.dp(12), 0, 0); resultNote.setVisibility(View.GONE); view.addView(resultNote, wrap());
        fileRows = ui.column(); view.addView(fileRows, wrap()); view.addView(ui.space(24));
        view.addView(ui.text("回复", 16, true), wrap()); view.addView(ui.space(12));
        replyCard = ui.card(); replyInput = new EditText(activity); replyInput.setTypeface(ui.regular); replyInput.setTextColor(ui.text); replyInput.setHintTextColor(ui.muted);
        replyInput.setTextSize(15); replyInput.setHint("继续这个任务…"); replyInput.setMinLines(3); replyInput.setMaxLines(8); replyInput.setGravity(Gravity.TOP | Gravity.START);
        replyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        replyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)}); replyInput.setBackgroundColor(android.graphics.Color.TRANSPARENT); replyInput.setPadding(0, ui.dp(4), 0, ui.dp(12));
        replyInput.setText(preferences.getString(preferencesKey + ":draft", commandState.equals("succeeded") ? "" : submittedText));
        replyCard.addView(replyInput, wrap()); replyStatus = ui.text("正在确认是否可回复", 14, false); replyStatus.setTextColor(ui.muted);
        replyStatus.setPadding(0, 0, 0, ui.dp(14)); replyCard.addView(replyStatus, wrap());
        sendButton = ui.button("发送回复", this::send); replyCard.addView(sendButton, wrap()); view.addView(replyCard, wrap());
        replyInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!loadingDraft) updateSend(); }
            public void afterTextChanged(Editable text) { }
        });
        if (saved != null && taskId.equals(saved.getString("task")) && hash(token).equals(saved.getString("connection"))) {
            expanded = saved.getBoolean("expanded"); pendingDownload = Download.restore(saved.getBundle("download"));
        }
        updateSend();
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private boolean current() { return !disposed && host.connected(); }
    private void setText(TextView target, String text) { if (!android.text.TextUtils.equals(target.getText(), text)) target.setText(text); }
    public void update(JSONObject task, boolean syncOutput) {
        if (disposed) return;
        boolean syncChanged = syncEnabled != syncOutput;
        if (syncChanged) {
            syncEnabled = syncOutput; resultRefresh.reset();
            if (!syncEnabled) { result = null; fullText = ""; resultSignature = ""; fileRows.removeAllViews(); outputActions.setVisibility(View.GONE); expand.setVisibility(View.GONE); setText(resultText, "输出同步已关闭"); resultText.setTextColor(ui.muted); setResultNote(""); }
            else if (result == null) setText(resultText, "正在读取最终结果");
        }
        String marker = task.optString("final_result_id") + "|" + task.optString("final_result_at") + "|" + task.optString("status");
        boolean changed = !marker.equals(snapshotMarker); snapshotMarker = marker;
        if (syncEnabled && (syncChanged || changed)) resultRefresh.expect(marker, task.optString("final_result_id"), SystemClock.elapsedRealtime());
        if (unresolved()) resultRefresh.rememberReply(requestId, result == null ? "" : result.optString("result_id"));
        if (syncEnabled) fetchResult();
        if (!knownReply || changed) fetchReply(changed);
    }
    public void tick() {
        if (!current() || !host.visible()) return;
        long now = SystemClock.elapsedRealtime();
        if (syncEnabled) {
            if (resultRefresh.expired(now)) setResultNote("结果尚未同步完成，点右上角重试");
            fetchResult();
        }
        if (now >= replyRefreshAt) fetchReply(false);
    }
    public void pause() {
        if (!disposed) preferences.edit().putString(preferencesKey + ":draft", replyInput.getText().toString()).apply();
    }
    public Bundle saveState() {
        Bundle saved = new Bundle(); saved.putString("task", taskId); saved.putString("connection", hash(token)); saved.putBoolean("expanded", expanded);
        if (pendingDownload != null) saved.putBundle("download", pendingDownload.save()); return saved;
    }
    public void dispose() { pause(); disposed = true; resultRefresh.reset(); main.removeCallbacksAndMessages(null); io.shutdownNow(); }
    private void request(String method, String path, JSONObject payload, Received done) {
        if (!current()) return;
        io.execute(() -> {
            JSONObject value = null; NativeApi.Failure failure = null;
            try { value = NativeApi.call(method, path, payload, token); } catch (NativeApi.Failure error) { failure = error; }
            final JSONObject body = value; final NativeApi.Failure error = failure;
            main.post(() -> { if (!current()) return; if (error != null && (error.status == 401 || error.status == 403)) { host.failure(error); return; } done.done(body, error); });
        });
    }
    private void fetchResult() {
        if (!current() || !host.visible() || !syncEnabled) return;
        final long generation = resultRefresh.startRequest(SystemClock.elapsedRealtime());
        if (generation < 0) return;
        refreshButton.setEnabled(false);
        request("GET", NativeApi.taskPath("result", taskId), null, (value, failure) -> {
            boolean fresh = resultRefresh.finishRequest(generation); refreshButton.setEnabled(true);
            if (!fresh || !syncEnabled) return;
            if (failure != null) {
                resultRefresh.failed(SystemClock.elapsedRealtime(), failure.retryAfterMs);
                if (result == null) { setText(resultText, "暂时无法读取最终结果"); resultText.setTextColor(ui.muted); }
                setResultNote("读取失败，点右上角重试"); return;
            }
            result = value; renderResult();
            resultRefresh.received(value.optString("result_id"), value.optBoolean("available"), filesPending);
            if (resultRefresh.awaitingText) setResultNote("正在确认最新结果，稍后自动更新");
        });
    }
    private void renderResult() {
        if (result == null) return;
        String signature = result.toString();
        if (signature.equals(resultSignature)) { setResultNote(downloadFeedback.isEmpty() ? result.optBoolean("truncated") ? "结果超出同步上限，完整内容请在电脑查看" : "" : downloadFeedback); return; }
        resultSignature = signature;
        fullText = result.optBoolean("available") ? result.optString("text") : "";
        if (fullText.isEmpty()) {
            setText(resultText, result.optString("reason", "本轮还没有最终结果")); resultText.setTextColor(ui.muted); expand.setVisibility(View.GONE);
        } else renderText();
        outputActions.setVisibility(fullText.isEmpty() ? View.GONE : View.VISIBLE);
        setResultNote(result.optBoolean("truncated") ? "结果超出同步上限，完整内容请在电脑查看" : "");
        fileRows.removeAllViews(); filesPending = false; JSONArray files = result.optJSONArray("files");
        if (files != null && files.length() > 0) {
            fileRows.addView(ui.space(24)); fileRows.addView(ui.text("配套文件", 16, true), wrap()); fileRows.addView(ui.space(12));
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i); if (file == null) continue;
                final Download download = fileDownload(file); if (download == null) continue;
                TextView button = ui.button(download.name, false, () -> choose(download)); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setMaxLines(2); button.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                boolean ready = file.optBoolean("ready", true); if (!ready) filesPending = true; button.setEnabled(ready); button.setAlpha(ready ? 1f : .5f); button.setContentDescription((ready ? "保存文件：" : "文件同步中：") + download.name);
                if (!ready) button.setText(download.name + " · 同步中"); fileRows.addView(button, wrap()); fileRows.addView(ui.space(10));
            }
        }
        updateDownloadButtons();
    }
    private void renderText() {
        if (fullText.isEmpty()) return;
        boolean longText = fullText.length() > PREVIEW_CHARACTERS;
        int end = Math.min(fullText.length(), PREVIEW_CHARACTERS); if (end > 0 && end < fullText.length() && Character.isHighSurrogate(fullText.charAt(end - 1))) end--;
        setText(resultText, longText && !expanded ? fullText.substring(0, end) + "\n…" : fullText); resultText.setTextColor(ui.text);
        expand.setVisibility(longText ? View.VISIBLE : View.GONE); expand.setText(expanded ? "收起全文" : "展开全文");
    }
    private void setResultNote(String message) { setText(resultNote, message); resultNote.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE); }
    private void copy() {
        if (!current() || fullText.isEmpty()) return;
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class); if (clipboard == null) return;
        try { clipboard.setPrimaryClip(ClipData.newPlainText("最终结果", fullText)); }
        catch (RuntimeException unavailable) { host.notice("暂时无法复制，请保存 TXT"); return; }
        if (android.os.Build.VERSION.SDK_INT < 33) host.notice("已复制最终结果");
    }
    private Download fileDownload(JSONObject file) {
        String path = NativeApi.taskPath("result/file", taskId, "result_id", result.optString("result_id"), "file_id", file.optString("id"));
        long size = file.optLong("size", -1); String digest = file.optString("sha256");
        if (!NativeApi.allowedDownload(path) || size < 0 || size > NativeApi.MAX_DOWNLOAD_BYTES || !digest.matches("[0-9a-f]{64}")) return null;
        return new Download(path, safeName(file.optString("name"), "附件"), safeMime(file.optString("mime")), size, digest);
    }
    private void chooseTxt() {
        if (result == null || fullText.isEmpty()) return;
        String path = NativeApi.taskPath("result.txt", taskId, "result_id", result.optString("result_id"));
        long size = result.optLong("txt_size", -1); String digest = result.optString("txt_sha256");
        if (!NativeApi.allowedDownload(path) || size < 0 || size > 800000 || !digest.matches("[0-9a-f]{64}")) { host.notice("请刷新结果后再保存"); return; }
        choose(new Download(path, "最终结果.txt", "text/plain", size, digest));
    }
    private void choose(Download item) {
        if (!current() || !host.visible() || pendingDownload != null || downloadBusy || !syncEnabled) return;
        pendingDownload = item; downloadFeedback = ""; updateDownloadButtons();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(item.mime).putExtra(Intent.EXTRA_TITLE, item.name);
        try { activity.startActivityForResult(intent, SAVE_DOCUMENT); }
        catch (RuntimeException unavailable) { pendingDownload = null; updateDownloadButtons(); host.notice("暂时无法打开保存位置"); }
    }
    public void documentResult(int resultCode, Intent data) {
        final Download selected = pendingDownload; pendingDownload = null; updateDownloadButtons();
        if (selected == null || resultCode != Activity.RESULT_OK || data == null || !current() || !syncEnabled) return;
        final Uri uri = data.getData();
        if (uri == null || !"content".equals(uri.getScheme()) || data.getClipData() != null && data.getClipData().getItemCount() != 1) { host.notice("保存位置无效"); return; }
        downloadBusy = true; downloadFeedback = "正在保存文件…"; setResultNote(downloadFeedback); updateDownloadButtons();
        io.execute(() -> {
            NativeApi.Failure failure = null; boolean opened = false;
            try {
                if (!current()) throw new NativeApi.Failure(0, "下载已取消");
                try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new NativeApi.Failure(0, "无法写入这个位置"); opened = true;
                    NativeApi.download(selected.path, token, output, selected.size, selected.sha256, () -> !current());
                }
            } catch (NativeApi.Failure error) { failure = error; }
            catch (Exception error) { failure = new NativeApi.Failure(0, "保存失败，请检查可用空间和位置"); }
            if (failure != null && opened) {
                // ACTION_CREATE_DOCUMENT creates a new document. Remove only that incomplete file.
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), uri); } catch (Exception ignored) { }
            }
            final NativeApi.Failure error = failure;
            main.post(() -> {
                if (!current()) return; downloadBusy = false; downloadFeedback = error == null ? "已保存 " + selected.name : error.getMessage();
                updateDownloadButtons(); setResultNote(downloadFeedback); if (host.visible()) host.notice(downloadFeedback);
                if (error != null && (error.status == 401 || error.status == 403)) host.failure(error);
            });
        });
    }
    private void updateDownloadButtons() {
        boolean ready = !downloadBusy && pendingDownload == null; txtButton.setEnabled(ready); txtButton.setAlpha(ready ? 1 : .5f);
        for (int i = 0; i < fileRows.getChildCount(); i++) { View child = fileRows.getChildAt(i); if (child instanceof TextView && child.getContentDescription() != null && child.getContentDescription().toString().startsWith("保存文件：")) { child.setEnabled(ready); child.setAlpha(ready ? 1 : .5f); } }
    }
    private void fetchReply(boolean force) {
        if (!current() || !host.visible() || replyBusy || sendBusy || !force && SystemClock.elapsedRealtime() < replyRefreshAt) return;
        replyBusy = true; replyRefreshAt = SystemClock.elapsedRealtime() + (unresolved() ? 3000 : 10000);
        String requested = unresolved() ? requestId : "";
        String path = requested.isEmpty() ? NativeApi.taskPath("reply", taskId) : NativeApi.taskPath("reply", taskId, "request_id", requested);
        request("GET", path, null, (value, failure) -> {
            replyBusy = false; if (!requested.isEmpty() && !requested.equals(requestId)) return;
            if (failure != null) { replyAvailable = false; knownReply = false; capabilityReason = "暂时无法确认，请稍后重试"; replyRefreshAt = SystemClock.elapsedRealtime() + Math.max(5000, failure.retryAfterMs); updateSend(); return; }
            knownReply = true; if (value.has("available")) replyAvailable = value.optBoolean("available"); capabilityReason = value.optString("reason");
            JSONObject latest = value.optJSONObject("latest");
            if (latest == null && value.has("state")) latest = value;
            if (latest != null && (requestId.isEmpty() || requestId.equals(latest.optString("request_id", latest.optString("id", requested))))) consumeCommand(latest);
            updateSend();
        });
    }
    private boolean unresolved() { return !requestId.isEmpty() && !commandState.equals("succeeded") && !commandState.equals("failed") && !commandState.equals("blocked"); }
    private boolean canRetryOriginal() { long age = System.currentTimeMillis() - submittedAt; return commandState.equals("not_found") && !submittedText.isEmpty() && submittedAt > 0 && age >= 0 && age <= 24L * 60 * 60 * 1000; }
    private void consumeCommand(JSONObject value) {
        String state = value.optString("state"); if (state.isEmpty()) return;
        boolean changed = !commandState.equals(state); commandState = state;
        String id = value.optString("request_id", value.optString("id"));
        if (requestId.isEmpty() && id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) requestId = id;
        if (changed && !requestId.isEmpty()) preferences.edit().putString(preferencesKey + ":id", requestId).putString(preferencesKey + ":state", state).apply();
        String reason = value.optString("reason"); if (!reason.isEmpty()) capabilityReason = reason;
        resultRefresh.reply(id.isEmpty() ? requestId : id, state,
                result == null ? "" : result.optString("result_id"), changed, SystemClock.elapsedRealtime());
        if ("succeeded".equals(state) && changed) {
            if (replyInput.getText().toString().trim().equals(submittedText)) { loadingDraft = true; replyInput.setText(""); loadingDraft = false; preferences.edit().remove(preferencesKey + ":draft").apply(); }
        }
    }
    private void updateSend() {
        boolean pending = unresolved();
        replyInput.setEnabled(!sendBusy && !pending); replyInput.setAlpha(sendBusy || pending ? .7f : 1f);
        String status;
        if (sendBusy) status = "正在发送…";
        else if (commandState.equals("dispatching")) status = "等待电脑确认";
        else if (commandState.equals("queued")) status = "等待电脑接收";
        else if (commandState.equals("running")) status = "正在回复";
        else if (commandState.equals("succeeded")) status = "回复完成";
        else if (commandState.equals("blocked")) status = capabilityReason.isEmpty() ? "需要在电脑处理批准请求" : capabilityReason;
        else if (commandState.equals("failed")) status = capabilityReason.isEmpty() ? "回复未完成" : capabilityReason;
        else if (commandState.equals("not_found")) status = canRetryOriginal() ? "电脑还没有接收这条回复" : "这条回复无法确认，请到电脑查看";
        else if (commandState.equals("uncertain") || pending) status = "发送结果尚未确认，请先查询";
        else status = knownReply ? replyAvailable ? "" : capabilityReason.isEmpty() ? "当前任务暂不可回复" : capabilityReason : capabilityReason.isEmpty() ? "正在确认是否可回复" : capabilityReason;
        setText(replyStatus, status); replyStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        sendButton.setText(pending ? canRetryOriginal() ? "重试原回复" : "查询发送结果" : "发送回复");
        boolean enabled = !sendBusy && (pending ? !replyBusy : knownReply && replyAvailable && !replyInput.getText().toString().trim().isEmpty());
        sendButton.setEnabled(enabled); sendButton.setAlpha(enabled ? 1f : .5f);
    }
    private void send() {
        if (!current() || !host.visible() || sendBusy) return;
        boolean retry = unresolved() && canRetryOriginal();
        if (unresolved() && !retry) { fetchReply(true); updateSend(); return; }
        String text = retry ? submittedText : replyInput.getText().toString().trim(); if (text.isEmpty() || !retry && (!knownReply || !replyAvailable)) return;
        if (!retry) { requestId = UUID.randomUUID().toString(); submittedAt = System.currentTimeMillis(); } submittedText = text; commandState = "";
        // Commit the id before transport. A lost response can be queried after recreation, never resent blindly.
        if (!preferences.edit().putString(preferencesKey + ":id", requestId).putString(preferencesKey + ":text", text).putString(preferencesKey + ":draft", text).putString(preferencesKey + ":state", "").putLong(preferencesKey + ":created_at", submittedAt).commit()) { if (!retry) requestId = ""; else commandState = "not_found"; host.notice("无法保存发送记录，请稍后重试"); updateSend(); return; }
        resultRefresh.rememberReply(requestId, result == null ? "" : result.optString("result_id"));
        sendBusy = true; updateSend(); final String sendingId = requestId;
        JSONObject payload = new JSONObject(); try { payload.put("task_id", taskId).put("text", text).put("request_id", sendingId); } catch (Exception impossible) { sendBusy = false; updateSend(); return; }
        request("POST", "/api/native/tasks/reply", payload, (value, failure) -> {
            sendBusy = false; if (!sendingId.equals(requestId)) return;
            if (failure == null) {
                JSONObject command = value.optJSONObject("command"); consumeCommand(command == null ? value : command);
                if (commandState.isEmpty()) commandState = "uncertain";
            } else { commandState = "uncertain"; capabilityReason = failure.getMessage(); }
            replyRefreshAt = 0; updateSend(); fetchReply(true);
        });
    }
    private static String safeName(String value, String fallback) {
        String name = value == null ? "" : value.replaceAll("[\\\\/\\p{Cntrl}\\p{Cf}]", "_").replaceAll("^[. ]+|[. ]+$", "");
        return name.isEmpty() ? fallback : name.length() > 180 ? name.substring(0, 180) : name;
    }
    private static String safeMime(String value) { return value != null && value.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? value : "application/octet-stream"; }
    private static String hash(String value) {
        try { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString(); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /** One bounded wait per snapshot change, explicit refresh, or completed reply. */
    static final class ResultRefresh {
        static final long INTERVAL_MS = 5000, WINDOW_MS = 120000;
        private long generation, deadline, retryAt;
        private boolean busy, pending, waitingForReply;
        boolean awaitingText;
        private String expectedMarker = "", expectedId = "", replyId = "", replyBaseline = "", replySnapshotBaseline = "", completedReplyId = "";
        void expect(String marker, String id, long now) {
            if (marker.equals(expectedMarker)) return;
            expectedMarker = marker; expectedId = id; restart(now);
        }
        void restart(long now) {
            generation++; pending = true; awaitingText = true;
            deadline = now + WINDOW_MS; // Keep request spacing and Retry-After across new observations.
        }
        void reset() {
            generation++; pending = false; awaitingText = false; waitingForReply = false;
            expectedMarker = ""; expectedId = ""; replyId = ""; replyBaseline = ""; replySnapshotBaseline = ""; completedReplyId = "";
            // Keep an outstanding request occupied until its callback releases it.
        }
        long startRequest(long now) {
            if (busy || !pending || now >= deadline || now < retryAt) return -1;
            busy = true; retryAt = now + INTERVAL_MS; return generation;
        }
        boolean finishRequest(long requestGeneration) { busy = false; return requestGeneration == generation; }
        void failed(long now, long retryAfterMs) { pending = true; retryAt = now + Math.max(INTERVAL_MS, retryAfterMs); }
        boolean expired(long now) { return pending && now >= deadline; }
        void received(String id, boolean available, boolean filesPending) {
            if (available && !id.isEmpty() && waitingForReply && !id.equals(replyBaseline) && !id.equals(replySnapshotBaseline)) waitingForReply = false;
            // The API can precede the snapshot. Display its response, but keep
            // checking until the snapshot's exact identity also agrees.
            awaitingText = !available || id.isEmpty() || !expectedId.isEmpty() && !expectedId.equals(id) || waitingForReply;
            pending = awaitingText || filesPending;
        }
        void rememberReply(String id, String displayedId) {
            if (id.isEmpty() || id.equals(replyId)) return;
            replyId = id; replyBaseline = displayedId; replySnapshotBaseline = expectedId;
        }
        void reply(String id, String state, String displayedId, boolean changed, long now) {
            if (id.isEmpty()) return;
            if (state.equals("queued") || state.equals("dispatching") || state.equals("running")) rememberReply(id, displayedId);
            if (!state.equals("succeeded") || !changed || id.equals(completedReplyId)) return;
            // A historical success without an observed pending reply only needs
            // the ordinary snapshot refresh, not a result newer than itself.
            completedReplyId = id; waitingForReply = id.equals(replyId); restart(now);
        }
    }
    private static final class Download {
        final String path, name, mime, sha256; final long size;
        Download(String path, String name, String mime, long size, String sha256) { this.path = path; this.name = name; this.mime = mime; this.size = size; this.sha256 = sha256; }
        Bundle save() { Bundle saved = new Bundle(); saved.putString("path", path); saved.putString("name", name); saved.putString("mime", mime); saved.putLong("size", size); saved.putString("sha256", sha256); return saved; }
        static Download restore(Bundle saved) {
            if (saved == null) return null; String path = saved.getString("path", ""), digest = saved.getString("sha256", ""); long size = saved.getLong("size", -1);
            if (!NativeApi.allowedDownload(path) || !digest.matches("[0-9a-f]{64}") || size < 0 || size > NativeApi.MAX_DOWNLOAD_BYTES) return null;
            return new Download(path, safeName(saved.getString("name"), "附件"), safeMime(saved.getString("mime")), size, digest);
        }
    }
}
