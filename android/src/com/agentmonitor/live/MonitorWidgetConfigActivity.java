package com.agentmonitor.live;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Native Monitor Family Design configuration screen for adding or reconfiguring
 * 2×2, 2×4, and 4×4 home screen widgets.
 */
public final class MonitorWidgetConfigActivity extends Activity {
    private static final String[] MODES = {
        MonitorWidgetPolicy.MODE_AUTO,
        MonitorWidgetPolicy.MODE_FIXED_QUOTA,
        MonitorWidgetPolicy.MODE_FIXED_TOKENS,
        MonitorWidgetPolicy.MODE_FIXED_TASKS
    };
    private static final String[] MODE_TITLES = {
        "自动更新（推荐）",
        "固定显示 · 官方剩余额度",
        "固定显示 · 今日本地 Token",
        "固定显示 · 实时项目动态"
    };
    private static final String[] MODE_NOTES = {
        "空闲时自动比较各工具剩余官方额度，显示最低一项并附下次重置时间（无官方额度时显示今日 Token）；开启任务实况跟踪后自动切换显示跟踪项目与实时进度。",
        "始终优先显示剩余百分比最低的官方额度窗口、进度条与下次重置倒计时，不受任务跟踪状态切换影响。",
        "始终汇总展示北京时间今日已采集的本地 Token 总量，以及 Codex、Claude Code、Antigravity 的分项用量。",
        "始终显示当前正在实况跟踪或执行中、等待批准的项目名称、所属电脑与任务状态。"
    };

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private String selectedMode = MonitorWidgetPolicy.MODE_AUTO;
    private NativeUi ui;
    private final LinearLayout[] optionCards = new LinearLayout[MODES.length];
    private final TextView[] optionBadges = new TextView[MODES.length];
    private TextView previewBadge;
    private TextView previewPrimary;
    private TextView previewSubtitle;
    private TextView previewFooter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        if (intent != null) {
            appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        selectedMode = savedInstanceState != null
                ? MonitorWidgetPolicy.sanitizeMode(savedInstanceState.getString("selected_mode"))
                : MonitorWidgetUpdater.getWidgetMode(this, appWidgetId);

        ui = new NativeUi(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(ui.bg));

        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.bg);
        NativeSystemBars bars = NativeSystemBars.attach(getWindow(), root, ui.bg, ui.bg);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            bars.onInsets(insets);
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            } else {
                view.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = ui.column();
        column.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(28));

        TextView heading = ui.heading("小组件显示方式", 24);
        column.addView(heading, wrap());
        column.addView(ui.space(6));
        column.addView(ui.body("选择该桌面小组件的内容策略，后续也可随时点按小组件右上角设置图标修改。", 14), wrap());
        column.addView(ui.space(18));

        // Live preview card in authentic Monitor Family Design
        LinearLayout previewCard = ui.card();
        previewBadge = ui.text("", 12, true);
        previewBadge.setTextColor(ui.accent);
        previewCard.addView(previewBadge, wrap());
        previewCard.addView(ui.space(8));
        previewPrimary = ui.heading("", 22);
        previewCard.addView(previewPrimary, wrap());
        previewCard.addView(ui.space(4));
        previewSubtitle = ui.text("", 13.5f);
        previewCard.addView(previewSubtitle, wrap());
        previewCard.addView(ui.space(6));
        previewFooter = ui.body("", 12);
        previewCard.addView(previewFooter, wrap());
        column.addView(previewCard, wrap());
        column.addView(ui.space(18));

        for (int i = 0; i < MODES.length; i++) {
            final int index = i;
            final String mode = MODES[i];
            LinearLayout card = ui.column();
            card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout headerRow = ui.row();
            TextView title = ui.text(MODE_TITLES[i], 15.5f, true);
            headerRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = ui.text("", 12, true);
            badge.setGravity(Gravity.END);
            headerRow.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(headerRow, wrap());
            card.addView(ui.space(6));

            TextView desc = ui.body(MODE_NOTES[i], 13);
            card.addView(desc, wrap());
            card.setOnClickListener(v -> {
                selectedMode = mode;
                updateSelectionUi();
            });

            optionCards[index] = card;
            optionBadges[index] = badge;
            column.addView(card, wrap());
            column.addView(ui.space(10));
        }

        column.addView(ui.space(8));
        TextView confirm = ui.button("保存并应用到桌面", this::saveSelection);
        column.addView(confirm, wrap());
        column.addView(ui.space(8));
        TextView cancel = ui.quietButton("取消", this::finish);
        column.addView(cancel, wrap());

        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.requestApplyInsets();
        updateSelectionUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("selected_mode", selectedMode);
        super.onSaveInstanceState(outState);
    }

    private void updateSelectionUi() {
        for (int i = 0; i < MODES.length; i++) {
            boolean active = MODES[i].equals(selectedMode);
            optionCards[i].setBackground(ui.borderedRipple(active ? ui.soft : ui.surface, 20));
            optionBadges[i].setText(active ? "已选择" : "");
            optionBadges[i].setTextColor(ui.accent);
            optionBadges[i].setVisibility(active ? View.VISIBLE : View.GONE);
        }
        MonitorWidgetPolicy.Model preview = MonitorWidgetUpdater.previewModel(this, selectedMode);
        previewBadge.setText("当前预览 · " + preview.wideBadge);
        previewPrimary.setText(preview.primaryValue);
        previewSubtitle.setText(preview.title);
        previewFooter.setText(preview.footer);
    }

    private void saveSelection() {
        MonitorWidgetUpdater.setWidgetMode(this, appWidgetId, selectedMode);
        MonitorWidgetUpdater.refreshFromNetworkAsync(this, true, null);
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
