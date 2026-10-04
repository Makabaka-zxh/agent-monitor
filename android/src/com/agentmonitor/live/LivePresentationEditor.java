package com.agentmonitor.live;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** An in-app content sample, never a notification or an OEM rendering assertion. */
final class LivePresentationEditor {
    final LinearLayout preview, controls;
    private final Activity activity;
    private final NativeUi ui;
    private final TextView expandedMode, lockedMode, toolButton, previewTitle, previewBody, styleName, help;
    private final ImageView brand;
    private final Switch[] toggles = new Switch[LivePresentationPreferences.KEYS.length];
    private final TextView[] presetButtons = new TextView[3];
    private final LivePresentationPreferences.Preset[] presets = {
            LivePresentationPreferences.Preset.SIMPLE, LivePresentationPreferences.Preset.BALANCED,
            LivePresentationPreferences.Preset.RICH};
    private String tool = "codex";
    private boolean locked, binding;

    LivePresentationEditor(Activity activity, String helpText) {
        this.activity = activity; ui = new NativeUi(activity);
        preview = ui.column(); controls = ui.column();
        LinearLayout toolbar = ui.row();
        LinearLayout modes = ui.row(); modes.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        modes.setBackground(ui.rounded(ui.bg, 16));
        expandedMode = choice("展开", () -> { locked = false; render(); });
        lockedMode = choice("锁屏", () -> { locked = true; render(); });
        modes.addView(expandedMode, new LinearLayout.LayoutParams(0, -2, 1));
        modes.addView(lockedMode, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(modes, new LinearLayout.LayoutParams(0, -2, 1.4f));
        toolButton = choice("Codex", () -> { tool = "codex".equals(tool) ? "claude" : "codex"; render(); });
        LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(0, -2, 1);
        toolParams.leftMargin = ui.dp(8); toolbar.addView(toolButton, toolParams);
        preview.addView(toolbar); preview.addView(ui.space(10));

        LinearLayout card = ui.column(); card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.bordered(ui.bg, 20)); card.setMinimumHeight(ui.dp(142));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleRow = ui.row();
        brand = ui.brand(tool, 28); titleRow.addView(brand);
        previewTitle = ui.text("", 15, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = ui.dp(10); titleParams.rightMargin = ui.dp(8);
        titleRow.addView(previewTitle, titleParams);
        TextView sample = ui.text("示例", 12); sample.setTextColor(ui.muted); titleRow.addView(sample);
        card.addView(titleRow);
        previewBody = ui.text("", 13); previewBody.setTextColor(ui.muted);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2); bodyParams.topMargin = ui.dp(8);
        card.addView(previewBody, bodyParams); preview.addView(card); preview.addView(ui.space(14));

        LinearLayout presetRow = ui.row(); presetRow.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        presetRow.setBackground(ui.rounded(ui.bg, 16));
        String[] presetLabels = {"简洁", "均衡", "丰富"};
        for (int index = 0; index < presets.length; index++) {
            final LivePresentationPreferences.Preset preset = presets[index];
            TextView button = choice(presetLabels[index], () -> {
                LivePresentationPreferences.save(activity, LivePresentationPreferences.presetOptions(preset));
                render();
            });
            presetButtons[index] = button; presetRow.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        controls.addView(presetRow); controls.addView(ui.space(6));
        LinearLayout optionsHeading = ui.row();
        TextView optionsTitle = ui.text("解锁时显示", 14, true);
        optionsHeading.addView(optionsTitle, new LinearLayout.LayoutParams(0, -2, 1));
        styleName = ui.text("", 12); styleName.setTextColor(ui.muted); optionsHeading.addView(styleName);
        help = ui.text(helpText, 14); help.setTextColor(ui.muted); help.setVisibility(View.GONE);
        help.setPadding(0, 0, 0, ui.dp(12));
        optionsHeading.addView(ui.iconButton("info", "预览说明", () -> {
            help.setVisibility(help.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }));
        controls.addView(optionsHeading); controls.addView(help);
        String[] labels = {"任务名称", "电脑名称", "跟踪时长", "本对话 Token", "今日 Token", "额度与重置时间"};
        for (int index = 0; index < toggles.length; index++) {
            final String key = LivePresentationPreferences.KEYS[index];
            Switch row = new Switch(activity);
            row.setText(labels[index]); row.setTextColor(ui.text); row.setTextSize(15); row.setTypeface(ui.regular);
            row.setShowText(false); row.setSwitchPadding(ui.dp(16)); row.setMinHeight(ui.dp(48));
            row.setPadding(0, ui.dp(6), 0, ui.dp(6));
            row.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, ui.muted}));
            row.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.soft, ui.line}));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (binding) return;
                LivePresentationPreferences.set(activity, key, checked); render();
            });
            toggles[index] = row; controls.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        render();
    }

    private TextView choice(String label, Runnable action) {
        TextView result = ui.button(label, false, action); result.setTextSize(13);
        result.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8)); return result;
    }
    private void selected(TextView button, boolean selected) {
        button.setSelected(selected); button.setTextColor(selected ? ui.accent : ui.muted);
        button.setBackground(ui.ripple(selected ? ui.soft : android.graphics.Color.TRANSPARENT, 13));
    }
    private void render() {
        LivePresentation.Options options = LivePresentationPreferences.options(activity);
        LivePreviewModel.Preview sample = LivePreviewModel.render(tool, options, locked);
        ui.bindBrand(brand, tool); previewTitle.setText(sample.title); previewBody.setText(sample.body);
        previewBody.setVisibility(sample.body.isEmpty() ? View.GONE : View.VISIBLE);
        selected(expandedMode, !locked); selected(lockedMode, locked);
        toolButton.setText("claude".equals(tool) ? "Claude Code" : "Codex");
        toolButton.setContentDescription("示例工具 " + toolButton.getText() + "，切换为 " + ("codex".equals(tool) ? "Claude Code" : "Codex"));
        selected(toolButton, false);
        LivePresentationPreferences.Preset matching = LivePresentationPreferences.matchingPreset(options);
        for (int index = 0; index < presets.length; index++) selected(presetButtons[index], matching == presets[index]);
        styleName.setText(matching == LivePresentationPreferences.Preset.CUSTOM ? "自定义" : "");
        binding = true;
        try { for (int index = 0; index < toggles.length; index++) toggles[index].setChecked(LivePresentationPreferences.enabled(activity, LivePresentationPreferences.KEYS[index])); }
        finally { binding = false; }
    }
}
