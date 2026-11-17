package com.agentmonitor.live;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
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
    private final TextView compactMode, expandedMode, lockedMode, previewTitle, previewStatus, previewBody, provider, styleName, help, surfaceHint;
    private final LinearLayout card, toolChoice, customChoice, customFields;
    private final View detailsRule, customChevron;
    private final ImageView brand;
    private final Switch lockscreenDetails;
    private final Switch[] toggles = new Switch[LivePresentationPreferences.KEYS.length];
    private final TextView[] presetButtons = new TextView[3];
    private final LivePresentationPreferences.Preset[] presets = {
            LivePresentationPreferences.Preset.SIMPLE, LivePresentationPreferences.Preset.BALANCED,
            LivePresentationPreferences.Preset.RICH};
    private String tool = "codex";
    private LivePreviewModel.Surface surface = LivePreviewModel.Surface.COMPACT;
    private boolean binding, fieldsExpanded;

    LivePresentationEditor(Activity activity, String helpText) {
        this.activity = activity; ui = new NativeUi(activity);
        preview = ui.column(); controls = ui.column();
        LinearLayout modes = ui.row();
        compactMode = choice("收起", () -> { surface = LivePreviewModel.Surface.COMPACT; render(); });
        expandedMode = choice("展开", () -> { surface = LivePreviewModel.Surface.EXPANDED; render(); });
        lockedMode = choice("锁屏", () -> { surface = LivePreviewModel.Surface.LOCKSCREEN; render(); });
        modes.addView(compactMode, new LinearLayout.LayoutParams(0, -2, 1));
        modes.addView(expandedMode, new LinearLayout.LayoutParams(0, -2, 1));
        modes.addView(lockedMode, new LinearLayout.LayoutParams(0, -2, 1));
        preview.addView(modes); preview.addView(ui.space(12));

        card = ui.column(); card.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(18));
        card.setBackground(ui.bordered(ui.surface, 20));
        LinearLayout identity = ui.row();
        toolChoice = ui.row(); toolChoice.setMinimumHeight(ui.dp(48));
        toolChoice.setBackground(ui.ripple(android.graphics.Color.TRANSPARENT, 12));
        toolChoice.setFocusable(true); toolChoice.setClickable(true);
        toolChoice.setAccessibilityDelegate(NativeUi.buttonAccessibility()); ui.pressFeedback(toolChoice, .98f);
        toolChoice.setOnClickListener(view -> { tool = "codex".equals(tool) ? "claude" : "codex"; render(); });
        brand = ui.brand(tool, 20); toolChoice.addView(brand);
        provider = ui.text("", 12, true); provider.setMaxLines(2);
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, -2, 1);
        identityParams.leftMargin = ui.dp(8); identityParams.rightMargin = ui.dp(8);
        toolChoice.addView(provider, identityParams);
        toolChoice.addView(ui.icon("chevron", 16));
        LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(0, -2, 1); toolParams.rightMargin = ui.dp(12);
        identity.addView(toolChoice, toolParams);
        TextView sample = ui.text("示例", 12); sample.setTextColor(ui.muted); identity.addView(sample);
        card.addView(identity);
        previewTitle = ui.text("", 20, true); previewTitle.setMaxLines(2); previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        previewTitle.setLineSpacing(ui.dp(4), 1f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2); titleParams.topMargin = ui.dp(6);
        card.addView(previewTitle, titleParams);
        LinearLayout state = ui.row();
        // The preview model deliberately uses a fixed running example; this dot has no separate semantics.
        View statusDot = new View(activity); statusDot.setBackground(ui.rounded(Color.parseColor(ui.dark ? "#9BB6A8" : "#6C907D"), 3));
        statusDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5)); dotParams.rightMargin = ui.dp(7);
        state.addView(statusDot, dotParams);
        previewStatus = ui.text("", 14, true);
        state.addView(previewStatus, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2); stateParams.topMargin = ui.dp(8);
        card.addView(state, stateParams);
        detailsRule = separator();
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, ui.dp(1)); ruleParams.topMargin = ui.dp(14);
        card.addView(detailsRule, ruleParams);
        previewBody = ui.text("", 13); previewBody.setTextColor(ui.muted); previewBody.setLineSpacing(ui.dp(6), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2); bodyParams.topMargin = ui.dp(10);
        card.addView(previewBody, bodyParams); preview.addView(card);
        surfaceHint = ui.text("", 12); surfaceHint.setTextColor(ui.muted); surfaceHint.setLineSpacing(ui.dp(2), 1f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2); hintParams.topMargin = ui.dp(10);
        preview.addView(surfaceHint, hintParams); preview.addView(ui.space(14));

        TextView densityLabel = ui.text("内容密度", 12, true); densityLabel.setTextColor(ui.muted);
        controls.addView(densityLabel); controls.addView(ui.space(10));
        LinearLayout presetRow = ui.row();
        String[] presetLabels = {"简洁", "均衡", "丰富"};
        for (int index = 0; index < presets.length; index++) {
            final LivePresentationPreferences.Preset preset = presets[index];
            TextView button = choice(presetLabels[index], () -> {
                LivePresentationPreferences.save(activity, LivePresentationPreferences.presetOptions(preset));
                render();
            });
            presetButtons[index] = button; presetRow.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        controls.addView(presetRow); controls.addView(ui.space(12));
        controls.addView(separator(), new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout optionsHeading = ui.row();
        customChoice = ui.row(); customChoice.setMinimumHeight(ui.dp(52));
        customChoice.setFocusable(true); customChoice.setClickable(true);
        customChoice.setBackground(ui.ripple(Color.TRANSPARENT, 10));
        customChoice.setAccessibilityDelegate(NativeUi.buttonAccessibility());
        customChoice.setOnClickListener(view -> { fieldsExpanded = !fieldsExpanded; bindCustomFields(); });
        TextView optionsTitle = ui.text("自定义内容", 14, true);
        optionsTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        customChoice.addView(optionsTitle, new LinearLayout.LayoutParams(0, -2, 1));
        styleName = ui.text("", 12); styleName.setTextColor(ui.muted);
        styleName.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        customChoice.addView(styleName);
        customChevron = ui.icon("chevron", 14);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)); chevronParams.leftMargin = ui.dp(8);
        customChoice.addView(customChevron, chevronParams);
        optionsHeading.addView(customChoice, new LinearLayout.LayoutParams(0, -2, 1));
        help = ui.text(helpText, 13); help.setTextColor(ui.muted); help.setVisibility(View.GONE);
        help.setLineSpacing(ui.dp(4), 1f);
        help.setPadding(0, 0, 0, ui.dp(12));
        TextView helpToggle = ui.quietButton("预览说明", null);
        helpToggle.setOnClickListener(view -> {
            boolean opening = help.getVisibility() != View.VISIBLE;
            help.setVisibility(opening ? View.VISIBLE : View.GONE);
            helpToggle.setText(opening ? "收起说明" : "预览说明");
        });
        optionsHeading.addView(helpToggle);
        controls.addView(optionsHeading); controls.addView(help);
        customFields = ui.column(); customFields.setVisibility(View.GONE);
        String[] labels = {"任务名称", "电脑名称", "已跟踪时长", "本对话 Token", "今日 Token", "额度与重置时间"};
        for (int index = 0; index < toggles.length; index++) {
            final String key = LivePresentationPreferences.KEYS[index];
            Switch row = new Switch(activity);
            row.setText(labels[index]); row.setTextColor(ui.muted); row.setTextSize(14); row.setTypeface(ui.regular);
            row.setShowText(false); row.setSwitchPadding(ui.dp(16)); row.setMinHeight(ui.dp(48));
            row.setPadding(0, ui.dp(6), 0, ui.dp(6));
            row.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, ui.muted}));
            row.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.soft, ui.line}));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (binding) return;
                LivePresentationPreferences.set(activity, key, checked); render();
            });
            toggles[index] = row; customFields.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        controls.addView(customFields);
        controls.addView(separator(), new LinearLayout.LayoutParams(-1, ui.dp(1)));
        controls.addView(ui.space(16));
        lockscreenDetails = new Switch(activity);
        lockscreenDetails.setText("锁屏显示任务详情"); lockscreenDetails.setTextColor(ui.text);
        lockscreenDetails.setTextSize(15); lockscreenDetails.setTypeface(ui.regular);
        lockscreenDetails.setShowText(false); lockscreenDetails.setSwitchPadding(ui.dp(16));
        lockscreenDetails.setMinHeight(ui.dp(48)); lockscreenDetails.setPadding(0, ui.dp(6), 0, ui.dp(6));
        lockscreenDetails.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, ui.muted}));
        lockscreenDetails.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.soft, ui.line}));
        lockscreenDetails.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            LivePresentationPreferences.setLockscreenDetails(activity, checked); render();
        });
        controls.addView(lockscreenDetails, new LinearLayout.LayoutParams(-1, -2));
        TextView privacy = ui.text("默认仅显示工具、状态和所选时长\n开启后，所选详情会作为锁屏公开内容；关闭可隐藏详情\n系统仍可禁止通知显示", 12);
        privacy.setTextColor(ui.muted); privacy.setLineSpacing(ui.dp(3), 1f);
        controls.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        render(false);
    }

    private TextView choice(String label, Runnable action) {
        TextView result = ui.button(label, false, action); result.setTextSize(13);
        result.setTypeface(ui.regular);
        result.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8)); return result;
    }
    private View separator() {
        View result = new View(activity); result.setBackgroundColor(ui.line);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    private void bindCustomFields() {
        customFields.setVisibility(fieldsExpanded ? View.VISIBLE : View.GONE);
        customChoice.setSelected(fieldsExpanded);
        customChoice.setContentDescription((fieldsExpanded ? "收起自定义内容" : "展开自定义内容") + "，" + styleName.getText());
        customChevron.setRotation(fieldsExpanded ? 270f : 90f);
    }
    private void selected(TextView button, boolean selected) {
        button.setSelected(selected); button.setTextColor(selected ? ui.text : ui.muted);
        button.setBackground(ui.ripple(selected ? ui.soft : Color.TRANSPARENT, 10));
    }
    private void render() { render(true); }
    private void render(boolean animate) {
        LivePresentation.Options options = LivePresentationPreferences.options(activity);
        boolean showDetails = LivePresentationPreferences.lockscreenDetails(activity);
        LivePreviewModel.Preview sample = LivePreviewModel.render(tool, options, surface, true, showDetails);
        ui.bindBrand(brand, tool); provider.setText(sample.identity);
        provider.setTextColor("claude".equals(tool) ? Color.parseColor(ui.dark ? "#CDA991" : "#96664F") : ui.accent);
        previewTitle.setMaxLines(surface == LivePreviewModel.Surface.COMPACT ? 1 : 2);
        previewTitle.setText(sample.title); previewTitle.setVisibility(sample.title.equals(sample.brand) ? View.GONE : View.VISIBLE);
        int firstBreak = sample.body.indexOf('\n');
        previewStatus.setText(firstBreak < 0 ? sample.body : sample.body.substring(0, firstBreak));
        String details = firstBreak < 0 ? "" : sample.body.substring(firstBreak + 1);
        previewBody.setText(details); previewBody.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        detailsRule.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        selected(compactMode, surface == LivePreviewModel.Surface.COMPACT);
        selected(expandedMode, surface == LivePreviewModel.Surface.EXPANDED);
        selected(lockedMode, surface == LivePreviewModel.Surface.LOCKSCREEN);
        toolChoice.setContentDescription("示例 " + sample.identity + "，切换为 " + ("codex".equals(tool) ? "Claude Code" : "Codex"));
        surfaceHint.setText(surface == LivePreviewModel.Surface.COMPACT ? "顶部显示工具和所选电脑；日常先看任务与状态，展开后查看用量和额度"
                : surface == LivePreviewModel.Surface.EXPANDED ? "按所选内容展示；没有数据的项目会自动省略"
                : showDetails ? "当前已开启锁屏公开详情" : "锁屏保留状态摘要，任务详情已隐藏");
        LivePresentationPreferences.Preset matching = LivePresentationPreferences.matchingPreset(options);
        for (int index = 0; index < presets.length; index++) selected(presetButtons[index], matching == presets[index]);
        int selectedCount = 0;
        binding = true;
        try {
            for (int index = 0; index < toggles.length; index++) {
                boolean enabled = LivePresentationPreferences.enabled(activity, LivePresentationPreferences.KEYS[index]);
                toggles[index].setChecked(enabled); if (enabled) selectedCount++;
            }
            lockscreenDetails.setChecked(showDetails);
        }
        finally { binding = false; }
        styleName.setText("已选 " + selectedCount + " / " + toggles.length + " 项");
        bindCustomFields();
        card.animate().cancel(); card.setAlpha(1f);
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            card.setAlpha(.82f); card.animate().alpha(1f).setDuration(140).start();
        }
    }
}
