package com.agentmonitor.live;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Native, scrollable settings sheet sharing the workbench's MiSans and surface geometry. */
final class LivePresentationSheet extends Dialog {
    private final Activity activity;
    private final NativeUi ui;
    private final LinearLayout content;
    private final LinearLayout panel;
    private final LinearLayout pinned;
    private final LinearLayout header;
    private final ScrollView sheet;
    private View previewView;
    private boolean closing;

    LivePresentationSheet(Activity activity, String title) {
        super(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        this.activity = activity; ui = new NativeUi(activity);
        setCanceledOnTouchOutside(true);
        panel = new LinearLayout(activity) {
            @Override protected void onMeasure(int width, int height) {
                int available = Math.max(ui.dp(80), availableBounds().height() - ui.dp(24));
                int parent = MeasureSpec.getSize(height);
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED && parent > 0) available = Math.min(available, parent);
                adaptPreview(MeasureSpec.getSize(width), available);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(20));
        panel.setBackground(ui.bordered(ui.surface, 24)); panel.setClipToOutline(true);
        content = ui.column();
        header = ui.row();
        TextView heading = ui.text(title, 20, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View close = ui.iconButton("close", "关闭设置", this::cancel);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        closeParams.rightMargin = -ui.dp(12);
        header.addView(close, closeParams); panel.addView(header); panel.addView(ui.space(8));
        pinned = ui.column(); panel.addView(pinned, new LinearLayout.LayoutParams(-1, -2));
        sheet = new ScrollView(activity);
        sheet.setVerticalScrollBarEnabled(false); sheet.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        if (Build.VERSION.SDK_INT >= 28) sheet.setAccessibilityPaneTitle(title);
        sheet.addView(content, new ScrollView.LayoutParams(-1, -2));
        panel.addView(sheet, new LinearLayout.LayoutParams(-1, -2)); setContentView(panel);
    }
    void pin(View view) { previewView = view; pinned.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    void body(View view) { content.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    /** Keep at least two control rows reachable; small/landscape/large-font layouts scroll together. */
    private void adaptPreview(int width, int availableHeight) {
        if (previewView == null || width <= 0) return;
        int innerWidth = Math.max(1, width - panel.getPaddingLeft() - panel.getPaddingRight());
        int exactWidth = View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY);
        int naturalHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        header.measure(exactWidth, naturalHeight); previewView.measure(exactWidth, naturalHeight);
        int space = availableHeight - panel.getPaddingTop() - panel.getPaddingBottom() - header.getMeasuredHeight() - ui.dp(8);
        boolean shouldPin = previewView.getMeasuredHeight() + ui.dp(96) <= space;
        ViewGroup destination = shouldPin ? pinned : content;
        if (previewView.getParent() != destination) {
            ((ViewGroup) previewView.getParent()).removeView(previewView);
            destination.addView(previewView, 0, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    void action(String text, Runnable action) {
        TextView row = ui.button(text, false, () -> { dismiss(); action.run(); });
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2)); content.addView(ui.space(8));
    }
    void toggle(String text, String key) {
        Switch row = new Switch(activity);
        row.setText(text); row.setTextColor(ui.text); row.setTextSize(15); row.setTypeface(ui.regular);
        row.setShowText(false); row.setSwitchPadding(ui.dp(16));
        row.setMinHeight(ui.dp(52)); row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        row.setChecked(LivePresentationPreferences.enabled(activity, key));
        row.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ui.accent, ui.muted}));
        row.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ui.soft, ui.line}));
        row.setOnCheckedChangeListener((button, checked) -> LivePresentationPreferences.set(activity, key, checked));
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    void note(String text) {
        content.addView(ui.space(12)); TextView note = ui.text(text, 14); note.setTextColor(ui.muted);
        note.setLineSpacing(ui.dp(3), 1.1f); content.addView(note, new LinearLayout.LayoutParams(-1, -2));
        content.addView(ui.space(12));
    }
    void status(String label, String value) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(48)); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        TextView name = ui.text(label, 15);
        TextView state = ui.text(value, 15); state.setTextColor(ui.muted);
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.rightMargin = ui.dp(12);
        row.addView(name, nameParams);
        row.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        // Keep label and value together for screen readers; neither is a control.
        row.setContentDescription(label + "，" + value);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    @Override public void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        closing = false; super.show(); Window window = getWindow(); if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(.22f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = Math.min(ui.dp(520), Math.max(ui.dp(120), availableBounds().width() - ui.dp(24)));
        attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT; attributes.y = ui.dp(12); attributes.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) attributes.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        window.setAttributes(attributes);
        if (ValueAnimator.areAnimatorsEnabled()) {
            panel.setAlpha(0f); panel.setTranslationY(ui.dp(18));
            panel.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
    }
    @Override public void cancel() {
        if (closing) return; closing = true;
        if (!isShowing() || activity.isFinishing() || activity.isDestroyed() || !ValueAnimator.areAnimatorsEnabled()) { super.cancel(); return; }
        panel.animate().alpha(0f).translationY(ui.dp(12)).setDuration(140).setInterpolator(new PathInterpolator(.4f, 0f, 1f, 1f))
                .withEndAction(() -> LivePresentationSheet.super.cancel()).start();
    }
    @Override public void onBackPressed() { cancel(); }
    @Override public void dismiss() { closing = true; panel.animate().withEndAction(null); panel.animate().cancel(); super.dismiss(); }
    private Rect availableBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.left += insets.left; bounds.top += insets.top; bounds.right -= insets.right; bounds.bottom -= insets.bottom; return bounds;
        }
        Rect bounds = new Rect(); activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return new Rect(0, 0, activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels);
        return bounds;
    }
}
