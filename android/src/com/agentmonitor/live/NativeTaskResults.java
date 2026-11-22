package com.agentmonitor.live;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
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
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final int PREVIEW_CHARACTERS = 600;
    private static final long DRAFT_SAVE_DELAY_MS = 350;
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
    private final Runnable saveDraft = this::persistDraft;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    public final LinearLayout view;
    /** Mount once near the task heading; this is not a child of the result/reply body. */
    public final LinearLayout attentionView;
    private final boolean interactive;
    private final LinearLayout outputActions, fileRows, replyCard;
    private final TextView resultText, resultNote, expand, collapseAtEnd, txtButton, copyButton, replyStatus, replyAvailability, sendButton, replyToggle, replyReceiptBadge, replyAppliedLabel;
    private final EditText replyInput;
    private final View resultHeading, refreshButton;
    private final ResultRefresh resultRefresh = new ResultRefresh();
    private final NativeSessionControls controls;
    private JSONObject result;
    private String resultSignature = "", snapshotMarker = "", fullText = "", requestId = "", submittedText = "", commandState = "", commandReason = "", capabilityReason = "";
    private boolean syncEnabled, replyBusy, sendBusy, downloadBusy, expanded, replyAvailable, knownReply, loadingDraft, filesPending, commandDelivered;
    private boolean replyExpanded;
    private ViewTreeObserver replyRevealTree, resultRevealTree;
    private ViewTreeObserver.OnPreDrawListener replyReveal, resultReveal;
    private volatile boolean disposed;
    private long replyRefreshAt, submittedAt;
    private Download pendingDownload;
    private String downloadFeedback = "";
    private String submittedSelection = "";
    private String settingsReceiptText = "";
    private String lastContinuationState = "";
    private boolean submittedSelectionInvalid;

    public NativeTaskResults(NativeUi ui, String taskId, String token, boolean interactive, Host host, Bundle saved) {
        this.ui = ui; this.activity = ui.activity; this.taskId = taskId; this.token = token; this.host = host; this.interactive = interactive;
        preferences = activity.getSharedPreferences("native_replies", Activity.MODE_PRIVATE);
        preferencesKey = hash(token) + ":" + hash(taskId);
        requestId = preferences.getString(preferencesKey + ":id", ""); submittedText = preferences.getString(preferencesKey + ":text", "");
        commandState = preferences.getString(preferencesKey + ":state", "");
        commandDelivered = preferences.getBoolean(preferencesKey + ":delivered", false);
        settingsReceiptText = preferences.getString(preferencesKey + ":settings_receipt", "");
        submittedSelection = preferences.getString(preferencesKey + ":submitted_selection", "");
        if (!submittedSelection.isEmpty()) try { submittedSelectionInvalid = !NativeSessionControlPolicy.selectionSnapshot(new JSONObject(submittedSelection)); } catch (Exception invalid) { submittedSelectionInvalid = true; }
        submittedAt = preferences.getLong(preferencesKey + ":created_at", 0);
        if (!requestId.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) { requestId = ""; submittedText = ""; commandState = ""; commandDelivered = false; settingsReceiptText = ""; }
        view = ui.column();
        controls = interactive ? new NativeSessionControls(ui, taskId, host, (method, path, value, done) -> request(method, path, value, done::done), preferences, preferencesKey, this::updateSend) : null;
        attentionView = interactive ? controls.view : ui.column();
        LinearLayout resultBody = ui.column();
        LinearLayout title = ui.row(); resultHeading = title;
        title.addView(ui.heading("最终结果", 20), new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = ui.iconButton("refresh", "刷新最终结果", () -> { resultRefresh.restart(SystemClock.elapsedRealtime()); fetchResult(); fetchReply(true); });
        title.addView(refreshButton);
        resultBody.addView(title, wrap());
        expand = resultToggle("展开全文");
        resultBody.addView(expand, wrap());
        resultBody.addView(ui.space(12));
        resultText = ui.text("输出同步已关闭", 16);
        resultText.setTextIsSelectable(true); resultText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); resultText.setLineSpacing(ui.dp(6), 1.1f); resultText.setTextColor(ui.muted);
        resultBody.addView(resultText, wrap());
        collapseAtEnd = resultToggle("收起全文");
        resultBody.addView(collapseAtEnd, wrap());
        outputActions = ui.row(); outputActions.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); copyButton = quietAction("复制", this::copy); txtButton = quietAction("保存 TXT", this::chooseTxt);
        for (TextView action : new TextView[]{copyButton, txtButton}) {
            action.setMinWidth(ui.dp(48)); action.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            outputActions.addView(action, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams actionsSpace = wrap(); actionsSpace.topMargin = ui.dp(4); resultBody.addView(outputActions, actionsSpace); outputActions.setVisibility(View.GONE);
        resultNote = ui.text("", 13); resultNote.setTextColor(ui.warning); resultNote.setPadding(0, ui.dp(12), 0, 0); resultNote.setVisibility(View.GONE); resultBody.addView(resultNote, wrap());
        view.addView(resultBody, wrap());
        fileRows = ui.column(); view.addView(fileRows, wrap()); if (interactive) view.addView(ui.space(24));
        View replyDivider = new View(activity); replyDivider.setBackgroundColor(ui.line); if (interactive) view.addView(replyDivider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout replyHeader = ui.row(); replyHeader.setMinimumHeight(ui.dp(48));
        replyHeader.addView(ui.heading("回复", 18), new LinearLayout.LayoutParams(0, -2, 1));
        replyToggle = ui.button("继续此会话", this::toggleReply); replyHeader.addView(replyToggle, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams replyHeaderSpace = wrap(); replyHeaderSpace.topMargin = ui.dp(18); if (interactive) view.addView(replyHeader, replyHeaderSpace);
        replyStatus = ui.text("", 13, false); replyStatus.setTextColor(ui.muted);
        replyStatus.setPadding(0, ui.dp(8), 0, ui.dp(4)); if (interactive) view.addView(replyStatus, wrap());
        replyAppliedLabel = ui.text("", 13, true); replyAppliedLabel.setTextColor(ui.accent);
        replyAppliedLabel.setPadding(0, ui.dp(2), 0, ui.dp(6)); replyAppliedLabel.setVisibility(View.GONE);
        if (interactive) view.addView(replyAppliedLabel, wrap());
        replyAvailability = ui.text("", 13, false); replyAvailability.setTextColor(ui.muted);
        replyAvailability.setPadding(0, ui.dp(6), 0, ui.dp(8)); if (interactive) view.addView(replyAvailability, wrap());
        replyCard = ui.column(); replyInput = new EditText(activity); replyInput.setTypeface(ui.regular); replyInput.setTextColor(ui.text); replyInput.setHintTextColor(ui.muted);
        replyInput.setTextSize(16); replyInput.setLineSpacing(ui.dp(6), 1.08f); replyInput.setHint("继续这个任务…"); replyInput.setMinLines(2); replyInput.setMaxLines(8); replyInput.setGravity(Gravity.TOP | Gravity.START);
        replyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        replyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)}); replyInput.setBackground(ui.bordered(ui.surface, 14)); replyInput.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        replyInput.setText(preferences.getString(preferencesKey + ":draft", draftFallback()));
        replyCard.addView(replyInput, wrap());
        sendButton = ui.button("发送回复", this::send);
        LinearLayout.LayoutParams sendSpace = new LinearLayout.LayoutParams(-1, -2); sendSpace.gravity = Gravity.END; sendSpace.topMargin = ui.dp(10);
        replyCard.addView(sendButton, sendSpace);
        replyReceiptBadge = ui.text("", 13, true);
        replyReceiptBadge.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        replyReceiptBadge.setBackground(ui.rounded(ui.soft, 10));
        replyReceiptBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeSpace = wrap(); badgeSpace.topMargin = ui.dp(10);
        replyCard.addView(replyReceiptBadge, badgeSpace);
        if (interactive) view.addView(replyCard, wrap());
        replyInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!loadingDraft) updateSend(); }
            public void afterTextChanged(Editable text) {
                if (loadingDraft) return;
                main.removeCallbacks(saveDraft);
                main.postDelayed(saveDraft, DRAFT_SAVE_DELAY_MS);
            }
        });
        if (saved != null && taskId.equals(saved.getString("task")) && hash(token).equals(saved.getString("connection"))) {
            expanded = saved.getBoolean("expanded"); replyExpanded = saved.getBoolean("reply_expanded");
            pendingDownload = Download.restore(saved.getBundle("download"));
        }
        updateSend();
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private TextView quietAction(String label, Runnable action) {
        TextView button = ui.button(label, false, action);
        button.setTypeface(ui.regular); button.setTextSize(14); button.setTextColor(ui.muted);
        button.setBackground(ui.ripple(android.graphics.Color.TRANSPARENT, 10));
        return button;
    }
    private TextView resultToggle(String label) {
        TextView action = quietAction(label, null);
        action.setOnClickListener(this::toggleResult);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setPadding(0, ui.dp(12), 0, ui.dp(12)); action.setVisibility(View.GONE);
        return action;
    }
    private ScrollView resultScroll() {
        for (ViewParent parent = resultText.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }
    private void toggleResult(View action) {
        if (!current() || !host.visible() || expand.getVisibility() != View.VISIBLE) return;
        cancelResultReveal();
        boolean fromEnd = action == collapseAtEnd;
        ScrollView scroll = expanded ? resultScroll() : null;
        int anchorY = 0;
        if (scroll != null && !fromEnd) {
            int[] location = new int[2];
            action.getLocationInWindow(location);
            anchorY = location[1];
        }
        expanded = !expanded;
        renderText();
        if (scroll != null && scroll.isAttachedToWindow()) revealResultPreview(scroll, fromEnd, anchorY);
    }
    private void revealResultPreview(ScrollView scroll, boolean fromEnd, int anchorY) {
        resultRevealTree = scroll.getViewTreeObserver();
        resultReveal = new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (resultReveal != this) return true;
                cancelResultReveal();
                if (expanded || !current() || !host.visible() || !expand.isShown()
                        || !scroll.isAttachedToWindow() || resultScroll() != scroll) return true;
                int[] location = new int[2];
                scroll.getLocationInWindow(location);
                int top = location[1] + scroll.getPaddingTop();
                int bottom = location[1] + scroll.getHeight() - scroll.getPaddingBottom() - expand.getHeight();
                int preferred = fromEnd ? top : Math.max(top, Math.min(bottom, anchorY));
                // From the footer, bring the heading and the start of the preview below the top bar.
                View target = fromEnd ? resultHeading : expand;
                target.getLocationInWindow(location);
                int before = scroll.getScrollY();
                scroll.scrollBy(0, location[1] - preferred);
                return scroll.getScrollY() == before;
            }
        };
        resultRevealTree.addOnPreDrawListener(resultReveal);
        scroll.postInvalidateOnAnimation();
    }
    private void cancelResultReveal() {
        if (resultReveal == null) return;
        ViewTreeObserver observer = resultRevealTree.isAlive() ? resultRevealTree : view.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(resultReveal);
        resultReveal = null; resultRevealTree = null;
    }
    private boolean replyNeedsAttention() {
        return NativeReplyPresentationPolicy.needsAttention(sendBusy, unresolved(), commandState);
    }
    private void toggleReply() {
        if (!interactive || replyNeedsAttention()) return;
        if (!replyExpanded) { openReplyFromUser(); return; }
        replyExpanded = false;
        cancelReplyReveal();
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(replyInput.getWindowToken(), 0);
        replyInput.clearFocus();
        persistDraft();
        updateSend();
    }
    private void openReplyFromUser() {
        if (!interactive || !current() || !host.visible() || !replyCard.isAttachedToWindow()) return;
        boolean opening = replyCard.getVisibility() != View.VISIBLE;
        if (!replyNeedsAttention()) replyExpanded = true;
        updateSend();
        if (opening) ui.reveal(replyCard);
        // Only an intentional tap schedules focus. Refresh and state restoration
        // may show the editor without opening the keyboard.
        cancelReplyReveal();
        replyRevealTree = replyCard.getViewTreeObserver();
        replyReveal = new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (replyReveal != this) return true;
                cancelReplyReveal();
                if (!current() || !host.visible() || !replyCard.isShown()) return true;
                View target = replyInput.isEnabled() ? replyInput : replyStatus;
                if (!target.isShown() || target.getWidth() == 0 || target.getHeight() == 0) return true;
                target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), false);
                if (target == replyInput && replyInput.hasWindowFocus() && replyInput.requestFocus()) {
                    InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                    if (keyboard != null) keyboard.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT);
                }
                return true;
            }
        };
        replyRevealTree.addOnPreDrawListener(replyReveal);
        replyCard.postInvalidateOnAnimation();
    }
    private void cancelReplyReveal() {
        if (replyReveal == null) return;
        ViewTreeObserver observer = replyRevealTree.isAlive() ? replyRevealTree : replyCard.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(replyReveal);
        replyReveal = null; replyRevealTree = null;
    }
    private boolean current() { return !disposed && host.connected(); }
    public void showModelSettings() { if (interactive) controls.openSettings(); }
    private void setText(TextView target, String text) { if (!android.text.TextUtils.equals(target.getText(), text)) target.setText(text); }
    public void update(JSONObject task, boolean syncOutput) {
        if (disposed) return;
        if (interactive) controls.update(task);
        boolean syncChanged = syncEnabled != syncOutput;
        if (syncChanged) {
            syncEnabled = syncOutput; resultRefresh.reset();
            if (!syncEnabled) { result = null; fullText = ""; resultSignature = ""; fileRows.removeAllViews(); outputActions.setVisibility(View.GONE); expand.setVisibility(View.GONE); collapseAtEnd.setVisibility(View.GONE); setText(resultText, "输出同步已关闭"); resultText.setTextColor(ui.muted); setResultNote(""); }
            else if (result == null) setText(resultText, "正在读取最终结果");
        }
        String marker = task.optString("final_result_id") + "|" + task.optString("final_result_at") + "|" + task.optString("status");
        boolean changed = !marker.equals(snapshotMarker); snapshotMarker = marker;
        String cont = task.optString("continuation_state");
        boolean continuationChanged = !cont.equals(lastContinuationState);
        lastContinuationState = cont;
        if (syncEnabled && (syncChanged || changed)) resultRefresh.expect(marker, task.optString("final_result_id"), SystemClock.elapsedRealtime());
        if (interactive && unresolved()) resultRefresh.rememberReply(requestId, result == null ? "" : result.optString("result_id"));
        if (syncEnabled) fetchResult();
        if (!knownReply || changed || continuationChanged) {
            if (continuationChanged && "continuable".equals(cont)) replyRefreshAt = 0;
            fetchReply(changed || continuationChanged);
        }
    }
    public void tick() {
        if (!current() || !host.visible()) return;
        if (interactive) controls.tick();
        long now = SystemClock.elapsedRealtime();
        if (syncEnabled) {
            if (resultRefresh.expired(now)) setResultNote("结果尚未同步完成，点右上角重试");
            fetchResult();
        }
        if (now >= replyRefreshAt) fetchReply(false);
    }
    private String draftFallback() { return commandState.equals("succeeded") ? "" : submittedText; }
    private void persistDraft() {
        if (!interactive) return;
        main.removeCallbacks(saveDraft);
        if (disposed) return;
        String key = preferencesKey + ":draft";
        String current = replyInput.getText().toString();
        if (NativeReplyPresentationPolicy.draftNeedsWrite(preferences.getString(key, null), current, draftFallback())) {
            preferences.edit().putString(key, current).apply();
        }
    }
    public void pause() {
        cancelReplyReveal();
        cancelResultReveal();
        if (interactive) controls.pause();
        persistDraft();
    }
    public Bundle saveState() {
        persistDraft();
        Bundle saved = new Bundle(); saved.putString("task", taskId); saved.putString("connection", hash(token)); saved.putBoolean("expanded", expanded);
        saved.putBoolean("reply_expanded", replyExpanded);
        if (pendingDownload != null) saved.putBundle("download", pendingDownload.save()); return saved;
    }
    public void dispose() { pause(); if (interactive) controls.dispose(); disposed = true; resultRefresh.reset(); main.removeCallbacksAndMessages(null); io.shutdownNow(); }
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
            setText(resultText, result.optString("reason", "本轮还没有最终结果")); resultText.setTextColor(ui.muted); expand.setVisibility(View.GONE); collapseAtEnd.setVisibility(View.GONE);
        } else renderText();
        outputActions.setVisibility(fullText.isEmpty() ? View.GONE : View.VISIBLE);
        setResultNote(result.optBoolean("truncated") ? "结果超出同步上限，完整内容请在电脑查看" : "");
        fileRows.removeAllViews(); filesPending = false; JSONArray files = result.optJSONArray("files");
        if (files != null && files.length() > 0) {
            fileRows.addView(ui.space(18)); fileRows.addView(ui.heading("配套文件", 16), wrap()); fileRows.addView(ui.space(6));
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i); if (file == null) continue;
                final Download download = fileDownload(file); if (download == null) continue;
                TextView button = quietAction(download.name, () -> choose(download)); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setMaxLines(2); button.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                button.setPadding(0, ui.dp(12), 0, ui.dp(12));
                boolean ready = file.optBoolean("ready", true); if (!ready) filesPending = true; button.setEnabled(ready); button.setAlpha(ready ? 1f : .5f); button.setContentDescription((ready ? "保存文件：" : "文件同步中：") + download.name);
                if (!ready) button.setText(download.name + " · 同步中"); fileRows.addView(button, wrap());
            }
        }
        updateDownloadButtons();
    }
    private void renderText() {
        if (fullText.isEmpty()) return;
        int end = NativeResultPreview.end(fullText, PREVIEW_CHARACTERS);
        boolean longText = end < fullText.length();
        setText(resultText, longText && !expanded ? fullText.substring(0, end) + "\n…" : fullText); resultText.setTextColor(ui.text);
        expand.setVisibility(longText ? View.VISIBLE : View.GONE); setText(expand, expanded ? "收起全文" : "展开全文");
        // Render the end control with the expanded text; no geometry from an unfinished layout is needed.
        collapseAtEnd.setVisibility(longText && expanded ? View.VISIBLE : View.GONE);
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
        if (!interactive) return;
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
    private boolean canRetryOriginal() { long age = System.currentTimeMillis() - submittedAt; return !submittedSelectionInvalid && commandState.equals("not_found") && !submittedText.isEmpty() && submittedAt > 0 && age >= 0 && age <= 24L * 60 * 60 * 1000; }
    private void consumeCommand(JSONObject value) {
        String state = value.optString("state"); if (state.isEmpty()) return;
        boolean changed = !commandState.equals(state); commandState = state;
        String id = value.optString("request_id", value.optString("id"));
        if (requestId.isEmpty() && id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) requestId = id;
        boolean delivered = !NativeSessionControlPolicy.nullableText(value, "claimed_at").isEmpty()
                || "running".equals(state) || "succeeded".equals(state);
        String nextSettingsReceipt = buildSettingsReceipt(value.optJSONObject("applied"), value.optJSONObject("requested_selection"));
        if (!nextSettingsReceipt.isEmpty()) settingsReceiptText = nextSettingsReceipt;
        boolean receiptChanged = delivered != commandDelivered || !nextSettingsReceipt.isEmpty();
        commandDelivered = delivered;
        if ((changed || receiptChanged) && !requestId.isEmpty()) {
            preferences.edit().putString(preferencesKey + ":id", requestId)
                    .putString(preferencesKey + ":state", state)
                    .putBoolean(preferencesKey + ":delivered", commandDelivered)
                    .putString(preferencesKey + ":settings_receipt", settingsReceiptText).apply();
        }
        commandReason = NativeReplyPresentationPolicy.commandReason(commandReason, changed, value.optString("reason"));
        resultRefresh.reply(id.isEmpty() ? requestId : id, state,
                result == null ? "" : result.optString("result_id"), changed, SystemClock.elapsedRealtime());
        if ("succeeded".equals(state) && changed) {
            if (replyInput.getText().toString().trim().equals(submittedText)) {
                main.removeCallbacks(saveDraft);
                loadingDraft = true;
                replyInput.setText("");
                loadingDraft = false;
                preferences.edit().remove(preferencesKey + ":draft").apply();
            }
        }
    }
    private String buildSettingsReceipt(JSONObject applied, JSONObject requested) {
        JSONObject source = applied != null && !NativeSessionControlPolicy.nullableText(applied, "model").isEmpty()
                || applied != null && !NativeSessionControlPolicy.nullableText(applied, "effort").isEmpty()
                || applied != null && (!NativeSessionControlPolicy.nullableText(applied, "service_tier").isEmpty()
                    || !NativeSessionControlPolicy.nullableText(applied, "collaboration_mode").isEmpty())
                ? applied : null;
        boolean verified = source != null;
        if (source == null) {
            source = requested;
            if (source == null && !submittedSelectionInvalid && !submittedSelection.isEmpty()) {
                try { source = new JSONObject(submittedSelection); } catch (Exception ignored) { source = null; }
            }
        }
        if (source == null) return "";
        String modelId = NativeSessionControlPolicy.nullableText(source, "model");
        String effortId = NativeSessionControlPolicy.nullableText(source, "effort");
        boolean fast = "priority".equals(NativeSessionControlPolicy.nullableText(source, "service_tier"));
        boolean plan = "plan".equals(NativeSessionControlPolicy.nullableText(source, "collaboration_mode"));
        return NativeReplyPresentationPolicy.settingsReceipt(
                modelId.isEmpty() ? "" : NativeUsageFormat.modelName(modelId),
                effortId.isEmpty() ? "" : NativeSessionControlPolicy.effortLabel(effortId),
                fast, plan, verified);
    }
    private void updateSend() {
        if (!interactive) return;
        boolean pending = unresolved();
        controls.setReplyLocked(sendBusy || pending);
        controls.setLastAppliedSummary(settingsReceiptText.startsWith("本轮实际使用：") ? settingsReceiptText : "");
        replyInput.setEnabled(!sendBusy && !pending); replyInput.setAlpha(sendBusy || pending ? .7f : 1f);
        boolean retry = pending && canRetryOriginal();
        boolean selectionReady = controls.canSendReply();
        String receiptTrail = requestId.isEmpty() && !sendBusy
                ? "" : NativeReplyPresentationPolicy.replyReceipt(sendBusy, commandState, commandDelivered);
        String baseStatus = NativeReplyPresentationPolicy.commandStatus(sendBusy, pending, commandState, retry, commandReason);
        String availability = NativeReplyPresentationPolicy.capabilityStatus(sendBusy, pending, knownReply, replyAvailable, selectionReady, capabilityReason);
        boolean attention = replyNeedsAttention();
        boolean showReply = NativeReplyPresentationPolicy.expanded(replyExpanded, attention);
        String combinedStatus = !showReply && !receiptTrail.isEmpty()
                ? (baseStatus.isEmpty() ? receiptTrail : receiptTrail + " · " + baseStatus)
                : baseStatus;
        setText(replyStatus, combinedStatus); replyStatus.setVisibility(combinedStatus.isEmpty() ? View.GONE : View.VISIBLE);
        setText(replyAppliedLabel, settingsReceiptText);
        replyAppliedLabel.setVisibility(settingsReceiptText.isEmpty() ? View.GONE : View.VISIBLE);
        setText(replyReceiptBadge, receiptTrail);
        replyReceiptBadge.setTextColor("failed".equals(commandState) ? ui.danger
                : "blocked".equals(commandState) ? ui.warning : commandDelivered ? ui.accent : ui.text);
        replyReceiptBadge.setVisibility(showReply && !receiptTrail.isEmpty() ? View.VISIBLE : View.GONE);
        setText(replyAvailability, availability); replyAvailability.setVisibility(availability.isEmpty() ? View.GONE : View.VISIBLE);
        replyStatus.setTextColor(commandState.equals("failed") ? ui.danger
                : commandState.equals("blocked") || commandState.equals("uncertain") || commandState.equals("not_found") ? ui.warning : ui.muted);
        setText(sendButton, pending ? retry ? "重试原回复" : "查询发送结果" : "发送回复");
        boolean enabled = !sendBusy && (pending ? !replyBusy : selectionReady && knownReply && replyAvailable && !replyInput.getText().toString().trim().isEmpty());
        sendButton.setEnabled(enabled); sendButton.setAlpha(enabled ? 1f : .5f);
        boolean hasDraft = replyInput.length() > 0;
        replyCard.setVisibility(showReply ? View.VISIBLE : View.GONE);
        String closedLabel = hasDraft ? "继续草稿" : "继续此会话";
        setText(replyToggle, showReply ? "收起" : closedLabel);
        replyToggle.setContentDescription(showReply ? "收起回复输入框，保留草稿" : closedLabel + "，展开回复输入框");
        boolean toggleReady = showReply || hasDraft || (knownReply && replyAvailable);
        replyToggle.setAlpha(toggleReady ? 1f : .65f);
        if (!Boolean.valueOf(showReply).equals(replyToggle.getTag())) {
            replyToggle.setTextColor(showReply ? ui.muted : ui.dark ? ui.bg : android.graphics.Color.WHITE);
            replyToggle.setBackground(ui.ripple(showReply ? android.graphics.Color.TRANSPARENT : ui.accent, showReply ? 10 : 16));
            replyToggle.setTag(showReply);
        }
        replyToggle.setVisibility(attention ? View.GONE : View.VISIBLE);
    }
    private void send() {
        if (!interactive) return;
        if (!current() || !host.visible() || sendBusy) return;
        boolean retry = unresolved() && canRetryOriginal();
        if (unresolved() && !retry) { fetchReply(true); updateSend(); return; }
        String draft = replyInput.getText().toString();
        String text = retry ? submittedText : draft.trim(); if (text.isEmpty() || !retry && (!knownReply || !replyAvailable)) return;
        JSONObject selected = null;
        if (!retry) {
            try { selected = controls.selectionForSend(); submittedSelection = selected == null ? "" : selected.toString(); submittedSelectionInvalid = false; }
            catch (IllegalStateException stale) { host.notice("模型选项已变化，请重新选择或继承电脑设置"); return; }
            requestId = UUID.randomUUID().toString(); submittedAt = System.currentTimeMillis();
            commandDelivered = false;
            settingsReceiptText = buildSettingsReceipt(null, selected);
        } submittedText = text; commandState = "";
        // Commit the id before transport. A lost response can be queried after recreation, never resent blindly.
        SharedPreferences.Editor record = preferences.edit().putString(preferencesKey + ":id", requestId)
            .putString(preferencesKey + ":text", text).putString(preferencesKey + ":draft", draft)
            .putString(preferencesKey + ":submitted_selection", submittedSelection)
            .putBoolean(preferencesKey + ":delivered", commandDelivered)
            .putString(preferencesKey + ":settings_receipt", settingsReceiptText)
            .putString(preferencesKey + ":state", "").putLong(preferencesKey + ":created_at", submittedAt);
        boolean consumeTurnOptions = !retry && controls.attachTurnOptionConsumption(record, selected);
        if (!record.commit()) { if (!retry) requestId = ""; else commandState = "not_found"; host.notice("无法保存发送记录，请稍后重试"); updateSend(); return; }
        if (consumeTurnOptions) controls.turnOptionsConsumed(selected);
        main.removeCallbacks(saveDraft);
        commandReason = "";
        resultRefresh.rememberReply(requestId, result == null ? "" : result.optString("result_id"));
        sendBusy = true; updateSend(); final String sendingId = requestId;
        JSONObject payload = new JSONObject(); try { payload.put("task_id", taskId).put("text", text).put("request_id", sendingId); if (!submittedSelection.isEmpty()) payload.put("selection", new JSONObject(submittedSelection)); } catch (Exception invalid) { sendBusy = false; submittedSelectionInvalid = true; commandState = "uncertain"; updateSend(); return; }
        request("POST", "/api/native/tasks/reply", payload, (value, failure) -> {
            sendBusy = false; if (!sendingId.equals(requestId)) return;
            if (failure == null) {
                JSONObject command = value.optJSONObject("command"); consumeCommand(command == null ? value : command);
                if (commandState.isEmpty()) commandState = "uncertain";
            } else commandState = "uncertain";
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
