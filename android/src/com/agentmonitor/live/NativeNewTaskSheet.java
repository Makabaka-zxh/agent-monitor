package com.agentmonitor.live;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native sheet for starting a brand-new coding session on an online computer's
 * verified workspace directory (Codex, Claude Code, or Antigravity).
 */
public final class NativeNewTaskSheet {
    private static final Pattern PROJECT_ID_RE = Pattern.compile("^[0-9a-f]{24}$");
    private static final int MAX_PROMPT_LENGTH = 8000;

    public interface Host {
        boolean connected();
        void refreshNow();
        void openCreatedTask(String taskId);
        void notice(String message);
    }

    public static final class ProjectOption {
        public final String id;
        public final String name;
        public final List<String> tools;

        ProjectOption(String id, String name, List<String> tools) {
            this.id = id;
            this.name = name;
            this.tools = Collections.unmodifiableList(tools);
        }
    }

    public static final class DeviceOption {
        public final String id;
        public final String name;
        public final String platform;
        public final List<String> supportedTools;
        public final List<ProjectOption> projects;

        DeviceOption(String id, String name, String platform,
                     List<String> supportedTools, List<ProjectOption> projects) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.supportedTools = Collections.unmodifiableList(supportedTools);
            this.projects = Collections.unmodifiableList(projects);
        }
    }

    public static List<String> parseTools(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(array.length(), 6); i++) {
            String tool = array.optString(i, "");
            if (("codex".equals(tool) || "claude".equals(tool) || "antigravity".equals(tool)) && !out.contains(tool)) {
                out.add(tool);
            }
        }
        return out;
    }

    public static List<ProjectOption> parseProjects(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<ProjectOption> out = new ArrayList<>();
        for (int i = 0; i < Math.min(array.length(), 24); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            String name = LivePresentation.clean(item.optString("name", ""), 80);
            if (!PROJECT_ID_RE.matcher(id).matches() || name.isEmpty()) continue;
            out.add(new ProjectOption(id, name, parseTools(item.optJSONArray("tools"))));
        }
        return out;
    }

    public static List<DeviceOption> eligibleDevices(JSONObject snapshot, boolean stale) {
        if (snapshot == null || stale) return Collections.emptyList();
        JSONArray devices = snapshot.optJSONArray("devices");
        if (devices == null || devices.length() == 0) return Collections.emptyList();
        List<DeviceOption> out = new ArrayList<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev == null || !dev.optBoolean("online")) continue;
            String id = dev.optString("id", "").trim();
            if (id.isEmpty()) continue;
            List<String> tools = parseTools(dev.optJSONArray("supported_tools"));
            List<ProjectOption> projects = parseProjects(dev.optJSONArray("projects"));
            if (tools.isEmpty() || projects.isEmpty()) continue;
            String name = LivePresentation.clean(dev.optString("name", ""), 60);
            String platform = LivePresentation.clean(dev.optString("platform", ""), 40);
            out.add(new DeviceOption(id, name.isEmpty() ? "在线电脑" : name, platform.isEmpty() ? "电脑" : platform, tools, projects));
        }
        return out;
    }

    public static String defaultToolFor(List<String> supportedTools, List<String> projectTools) {
        if (supportedTools == null || supportedTools.isEmpty()) return "";
        if (projectTools != null) {
            for (String preferred : projectTools) {
                if (supportedTools.contains(preferred)) return preferred;
            }
        }
        return supportedTools.get(0);
    }

    public static String emptyEligibilityHint(JSONObject snapshot, boolean stale) {
        if (snapshot == null) return "正在同步工作台状态，请稍后重试";
        if (stale) return "当前状态尚未刷新，请确认连接后再新建任务";
        JSONArray devices = snapshot.optJSONArray("devices");
        if (devices == null || devices.length() == 0) return "请先连接一台在线电脑后再新建任务";
        boolean anyOnline = false;
        boolean anyTools = false;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dev = devices.optJSONObject(i);
            if (dev != null && dev.optBoolean("online")) {
                anyOnline = true;
                if (!parseTools(dev.optJSONArray("supported_tools")).isEmpty()) {
                    anyTools = true;
                }
            }
        }
        if (!anyOnline) return "当前没有在线电脑，请先开启电脑端连接器";
        if (!anyTools) return "在线电脑尚未启用远程回复或未安装 Codex / Claude / Antigravity CLI";
        return "在线电脑暂无已识别的历史项目目录，请先在电脑端打开一次目标项目";
    }

    private final Activity activity;
    private final NativeUi ui;
    private final String token;
    private final Host host;
    private final List<DeviceOption> devices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LivePresentationSheet sheet;
    private LivePresentationSheet subSheet;
    private DeviceOption selectedDevice;
    private ProjectOption selectedProject;
    private String selectedTool = "";
    private final TextView deviceValue, projectValue, statusView, submitButton;
    private final LinearLayout toolRow;
    private final EditText promptInput;
    private boolean submitting;
    private boolean closed;
    private String activeRequestId = "";
    private String activeSyntheticTaskId = "";
    private int pollAttempts;

    public NativeNewTaskSheet(Activity activity, NativeUi ui, List<DeviceOption> devices,
                              String preferredDeviceId, String token, Host host) {
        this.activity = activity;
        this.ui = ui;
        this.devices = devices;
        this.token = token;
        this.host = host;
        this.sheet = new LivePresentationSheet(activity, "新建远程任务", "关闭新建任务");

        selectedDevice = devices.get(0);
        if (preferredDeviceId != null && !preferredDeviceId.isEmpty()) {
            for (DeviceOption candidate : devices) {
                if (preferredDeviceId.equals(candidate.id)) {
                    selectedDevice = candidate;
                    break;
                }
            }
        }
        selectedProject = selectedDevice.projects.get(0);
        selectedTool = defaultToolFor(selectedDevice.supportedTools, selectedProject.tools);

        LinearLayout form = ui.column();
        TextView intro = ui.text("选择在线电脑与历史活跃项目目录，直接远程拉起新对话", 13);
        intro.setTextColor(ui.muted);
        form.addView(intro, wrap());
        form.addView(ui.space(14));

        deviceValue = ui.text("", 15, true);
        LinearLayout deviceCard = selectorRow("目标电脑", deviceValue, devices.size() > 1, this::pickDevice);
        form.addView(deviceCard, wrap());
        form.addView(ui.space(10));

        projectValue = ui.text("", 15, true);
        LinearLayout projectCard = selectorRow("项目目录", projectValue, true, this::pickProject);
        form.addView(projectCard, wrap());
        form.addView(ui.space(14));

        TextView toolHeading = ui.text("代理工具", 13);
        toolHeading.setTextColor(ui.muted);
        form.addView(toolHeading, wrap());
        form.addView(ui.space(8));
        toolRow = ui.row();
        form.addView(toolRow, wrap());
        form.addView(ui.space(14));

        TextView promptHeading = ui.text("首条任务指令", 13);
        promptHeading.setTextColor(ui.muted);
        form.addView(promptHeading, wrap());
        form.addView(ui.space(8));

        promptInput = new EditText(activity);
        promptInput.setHint("描述要完成的新任务，例如：检查并修复测试失败…");
        promptInput.setHintTextColor(ui.muted);
        promptInput.setTextColor(ui.text);
        promptInput.setTextSize(15);
        promptInput.setTypeface(ui.regular);
        promptInput.setMinLines(3);
        promptInput.setMaxLines(7);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PROMPT_LENGTH)});
        promptInput.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        promptInput.setBackground(ui.bordered(ui.surface, 16));
        promptInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateControls(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        form.addView(promptInput, wrap());

        statusView = ui.text("", 13);
        statusView.setTextColor(ui.muted);
        statusView.setPadding(0, ui.dp(10), 0, 0);
        statusView.setVisibility(View.GONE);
        form.addView(statusView, wrap());
        form.addView(ui.space(16));

        submitButton = ui.button("发起新任务", true, this::submit);
        form.addView(submitButton, wrap());

        sheet.body(form);
        sheet.setOnDismissListener(ignored -> dispose());
        refreshSelectionUi();
    }

    public void show() {
        sheet.show();
    }

    public void dismiss() {
        if (!closed) sheet.dismiss();
    }

    private LinearLayout selectorRow(String label, TextView valueView, boolean interactive, Runnable onTap) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        box.setBackground(interactive ? ui.borderedRipple(ui.surface, 16) : ui.bordered(ui.surface, 16));
        LinearLayout top = ui.row();
        TextView key = ui.text(label, 12);
        key.setTextColor(ui.muted);
        top.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        if (interactive) {
            TextView change = ui.text("切换 ▾", 12);
            change.setTextColor(ui.accent);
            top.addView(change);
        }
        box.addView(top, wrap());
        box.addView(ui.space(4));
        valueView.setMaxLines(1);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(valueView, wrap());
        if (interactive) {
            box.setClickable(true);
            box.setFocusable(true);
            box.setAccessibilityDelegate(NativeUi.buttonAccessibility());
            box.setOnClickListener(v -> {
                if (!submitting && onTap != null) onTap.run();
            });
        }
        return box;
    }

    private void refreshSelectionUi() {
        deviceValue.setText(selectedDevice.name + " · " + selectedDevice.platform);
        projectValue.setText(selectedProject.name + (selectedDevice.projects.size() > 1 ? "（共 " + selectedDevice.projects.size() + " 个项目）" : ""));
        toolRow.removeAllViews();
        for (int i = 0; i < selectedDevice.supportedTools.size(); i++) {
            final String tool = selectedDevice.supportedTools.get(i);
            boolean active = tool.equals(selectedTool);
            LinearLayout chip = ui.row();
            chip.setGravity(Gravity.CENTER);
            chip.setMinimumHeight(ui.dp(44));
            chip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            chip.setBackground(ui.ripple(active ? ui.soft : ui.surface, 14));
            chip.addView(ui.brand(tool, 16));
            TextView label = ui.text(NativeListPresentation.toolLabel(tool), 13, active);
            label.setTextColor(active ? ui.text : ui.muted);
            label.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(ui.dp(6));
            chip.addView(label, lp);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setAccessibilityDelegate(NativeUi.buttonAccessibility());
            chip.setOnClickListener(v -> {
                if (submitting || tool.equals(selectedTool)) return;
                selectedTool = tool;
                refreshSelectionUi();
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) chipParams.setMarginStart(ui.dp(8));
            toolRow.addView(chip, chipParams);
        }
        updateControls();
    }

    private void updateControls() {
        boolean hasPrompt = promptInput.getText() != null && !promptInput.getText().toString().trim().isEmpty();
        boolean canSubmit = !submitting && hasPrompt && !selectedTool.isEmpty() && selectedProject != null;
        submitButton.setEnabled(canSubmit);
        submitButton.setAlpha(canSubmit ? 1f : 0.5f);
        submitButton.setText(submitting ? "正在创建会话…" : "发起新任务");
        promptInput.setEnabled(!submitting);
    }

    private void pickDevice() {
        if (devices.size() <= 1 || submitting) return;
        dismissSubSheet();
        LivePresentationSheet picker = new LivePresentationSheet(activity, "选择目标电脑", "关闭电脑选择");
        subSheet = picker;
        for (DeviceOption dev : devices) {
            boolean active = dev.id.equals(selectedDevice.id);
            picker.choice(dev.name + " · " + dev.platform, dev.projects.size() + " 个项目", active, () -> {
                selectedDevice = dev;
                selectedProject = dev.projects.get(0);
                selectedTool = defaultToolFor(dev.supportedTools, selectedProject.tools);
                refreshSelectionUi();
            });
        }
        picker.setOnDismissListener(ignored -> { if (subSheet == picker) subSheet = null; });
        picker.show();
    }

    private void pickProject() {
        if (submitting) return;
        dismissSubSheet();
        LivePresentationSheet picker = new LivePresentationSheet(activity, "选择项目目录", "关闭项目选择");
        subSheet = picker;
        picker.note("仅列出该电脑上已验证存在的历史活跃工作区目录");
        for (ProjectOption proj : selectedDevice.projects) {
            boolean active = proj.id.equals(selectedProject.id);
            StringBuilder hint = new StringBuilder();
            for (int i = 0; i < proj.tools.size(); i++) {
                if (i > 0) hint.append(" / ");
                hint.append(NativeListPresentation.toolLabel(proj.tools.get(i)));
            }
            picker.choice(proj.name, hint.toString(), active, () -> {
                selectedProject = proj;
                selectedTool = defaultToolFor(selectedDevice.supportedTools, proj.tools);
                refreshSelectionUi();
            });
        }
        picker.setOnDismissListener(ignored -> { if (subSheet == picker) subSheet = null; });
        picker.show();
    }

    private void submit() {
        if (submitting || closed || !host.connected()) return;
        String prompt = promptInput.getText() == null ? "" : promptInput.getText().toString().trim();
        if (prompt.isEmpty() || selectedProject == null || selectedTool.isEmpty()) return;
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(promptInput.getWindowToken(), 0);

        submitting = true;
        pollAttempts = 0;
        activeRequestId = UUID.randomUUID().toString();
        activeSyntheticTaskId = selectedDevice.id + ":" + selectedTool + ":new_" + selectedProject.id;
        showStatus("正在向 " + selectedDevice.name + " 发送新建指令…", false);
        updateControls();

        final String reqId = activeRequestId;
        final String devId = selectedDevice.id;
        final String projId = selectedProject.id;
        final String tool = selectedTool;
        io.execute(() -> {
            JSONObject resp = null;
            NativeApi.Failure err = null;
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", devId);
                body.put("project_id", projId);
                body.put("tool", tool);
                body.put("request_id", reqId);
                body.put("text", prompt);
                resp = NativeApi.call("POST", "/api/native/tasks/start", body, token);
            } catch (NativeApi.Failure failure) {
                err = failure;
            } catch (Exception invalid) {
                err = new NativeApi.Failure(0, "发送请求格式无效");
            }
            final JSONObject finalResp = resp;
            final NativeApi.Failure finalErr = err;
            main.post(() -> {
                if (closed || !reqId.equals(activeRequestId)) return;
                if (finalErr != null || finalResp == null) {
                    submitting = false;
                    showStatus(finalErr != null ? finalErr.getMessage() : "暂时无法发起新任务，请重试", true);
                    updateControls();
                    return;
                }
                handleReplyStatus(finalResp);
            });
        });
    }

    private void handleReplyStatus(JSONObject status) {
        if (closed) return;
        String createdTaskId = status.optString("created_task_id", "").trim();
        String state = status.optString("state", "");
        String reason = status.optString("reason", "");
        if (!createdTaskId.isEmpty()) {
            host.refreshNow();
            dismiss();
            host.openCreatedTask(createdTaskId);
            return;
        }
        if ("blocked".equals(state) || "failed".equals(state) || "uncertain".equals(state)) {
            submitting = false;
            showStatus(reason.isEmpty() ? "电脑端未能启动新会话，请检查后重试" : reason, true);
            updateControls();
            return;
        }
        if ("succeeded".equals(state)) {
            host.refreshNow();
            host.notice("新任务已在电脑端启动并完成首轮响应");
            dismiss();
            return;
        }
        pollAttempts++;
        if (pollAttempts > 32) {
            host.refreshNow();
            host.notice("新任务已发送至电脑，正在后台拉起");
            dismiss();
            return;
        }
        showStatus("running".equals(state) ? "电脑已接收，正在初始化新会话…" : "等待电脑接收新建指令…", false);
        final String reqId = activeRequestId;
        final String taskId = activeSyntheticTaskId;
        main.postDelayed(() -> {
            if (closed || !submitting || !reqId.equals(activeRequestId)) return;
            io.execute(() -> {
                JSONObject polled = null;
                try {
                    String path = NativeApi.taskPath("reply", taskId, "request_id", reqId);
                    if (!path.isEmpty()) polled = NativeApi.call("GET", path, null, token);
                } catch (Exception ignored) { }
                final JSONObject next = polled;
                main.post(() -> {
                    if (closed || !submitting || !reqId.equals(activeRequestId)) return;
                    if (next != null) handleReplyStatus(next);
                    else handleReplyStatus(new JSONObject());
                });
            });
        }, 450);
    }

    private void showStatus(String text, boolean warning) {
        statusView.setText(text);
        statusView.setTextColor(warning ? ui.warning : ui.muted);
        statusView.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void dismissSubSheet() {
        if (subSheet != null) {
            LivePresentationSheet s = subSheet;
            subSheet = null;
            s.dismiss();
        }
    }

    private void dispose() {
        if (closed) return;
        closed = true;
        submitting = false;
        dismissSubSheet();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
}
