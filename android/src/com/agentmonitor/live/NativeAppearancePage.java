package com.agentmonitor.live;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Background choices and public image attribution; no task or account data. */
public final class NativeAppearancePage {
    public interface Select { void choose(String mode); }
    public final LinearLayout view;
    private final NativeUi ui;
    private final TextView status, attribution, imageLink;
    private String link = "";

    public NativeAppearancePage(NativeUi ui, Select select) {
        this.ui = ui; view = ui.column();
        TextView introduction = ui.text("背景风格", 20, true);
        view.addView(introduction, wrap()); view.addView(ui.space(8));
        TextView note = ui.text("应用内生效 · 跟随系统深浅", 14);
        note.setContentDescription("背景仅用于 Monitor，深浅随系统切换");
        note.setTextColor(ui.muted); view.addView(note, wrap()); view.addView(ui.space(24));
        view.addView(ui.heading("纯色", 15), wrap()); view.addView(ui.space(10));
        for (String mode : NativeAppearancePolicy.modes()) {
            if (mode.equals("bing")) { view.addView(ui.space(14)); view.addView(ui.heading("每日风景", 15), wrap()); view.addView(ui.space(10)); }
            view.addView(option(mode, select), wrap()); view.addView(ui.space(8));
        }
        status = ui.text("bing".equals(ui.backgroundMode) ? "正在读取每日壁纸" : "选择后，每天打开应用时更新壁纸", 14); status.setTextColor(ui.muted);
        LinearLayout.LayoutParams gap = wrap(); gap.topMargin = ui.dp(12); view.addView(status, gap);
        attribution = ui.text("", 12); attribution.setTextColor(ui.muted); attribution.setVisibility(View.GONE);
        LinearLayout.LayoutParams creditSpace = wrap(); creditSpace.topMargin = ui.dp(8); view.addView(attribution, creditSpace);
        imageLink = ui.quietButton("图片详情 · Bing", () -> {
            if (link.isEmpty()) return;
            try { ui.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
            catch (RuntimeException unavailable) { status.setText("暂时无法打开图片详情"); }
        });
        imageLink.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); imageLink.setVisibility(View.GONE);
        view.addView(imageLink, wrap());
    }

    private View option(String mode, Select select) {
        boolean selected = mode.equals(ui.backgroundMode), bing = mode.equals("bing");
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(76));
        row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        row.setBackground(ui.borderedRipple(ui.surface, 20));
        View swatch = new View(ui.activity);
        if (bing) {
            android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xff94c3c2, 0xff7193ba, 0xffcfbda3});
            gradient.setCornerRadius(ui.dp(12)); swatch.setBackground(gradient);
        } else swatch.setBackground(ui.bordered(NativeAppearancePolicy.background(mode, ui.dark), 12));
        swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(swatch, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
        LinearLayout words = ui.column(); words.addView(ui.text(NativeAppearancePolicy.label(mode), 16, selected), wrap());
        if (bing) { TextView subtitle = ui.text("每天一张，离线保留上次图片", 12); subtitle.setTextColor(ui.muted);
            LinearLayout.LayoutParams sub = wrap(); sub.topMargin = ui.dp(5); words.addView(subtitle, sub); }
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, -2, 1); text.leftMargin = ui.dp(14); text.rightMargin = ui.dp(10); row.addView(words, text);
        TextView check = ui.text(selected ? "已选" : "", 12, true); check.setTextColor(ui.accent); row.addView(check);
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setSelected(selected); row.setFocusable(true); row.setContentDescription(NativeAppearancePolicy.label(mode) + (selected ? "，已选择" : ""));
        row.setAccessibilityDelegate(NativeUi.buttonAccessibility()); row.setOnClickListener(v -> { if (!selected) select.choose(mode); });
        return row;
    }

    public void show(NativeBingWallpaper.Result result) {
        if (!"bing".equals(ui.backgroundMode) || result == null) return;
        boolean picture = result.bitmap != null;
        switch (result.state) {
            case "updated": case "cached": status.setText("每日壁纸已启用 · 自动柔化，保持文字清晰"); break;
            case "restricted": status.setText("今日图片暂不可用于壁纸" + (picture ? "，继续使用上次图片" : "，暂用纯色背景")); break;
            default: status.setText(picture ? "暂时无法更新，继续使用上次图片" : "暂时无法读取壁纸，先使用纯色背景"); break;
        }
        String credit = result.copyright == null ? "" : result.copyright;
        attribution.setText(credit); attribution.setVisibility(credit.isEmpty() ? View.GONE : View.VISIBLE);
        link = !picture || result.link == null ? "" : result.link;
        imageLink.setVisibility(link.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
}
