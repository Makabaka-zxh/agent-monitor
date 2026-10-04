package com.agentmonitor.live;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
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
import android.widget.TextView;

/** A quiet, native help sheet using the same type, surfaces and geometry as the workbench. */
public final class NativeHelpDialog extends Dialog {
    private final Activity activity;
    private final NativeUi ui;
    private final ScrollView sheet;
    private boolean closing;

    public NativeHelpDialog(Activity activity, NativeUi ui, String title, String message) {
        super(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        this.activity = activity;
        this.ui = ui;
        setCanceledOnTouchOutside(true);

        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(20));
        content.setBackground(ui.bordered(ui.surface, 24));

        LinearLayout header = ui.row();
        TextView heading = ui.text(title, 20, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View close = ui.iconButton("close", "关闭说明", this::cancel);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        // Align the drawn icon to the body inset while preserving its full touch target.
        closeParams.rightMargin = -ui.dp(12);
        header.addView(close, closeParams);
        content.addView(header);
        content.addView(ui.space(12));

        TextView body = ui.text(message, 15);
        body.setTextColor(ui.muted);
        body.setLineSpacing(ui.dp(4), 1.12f);
        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(ui.space(24));
        content.addView(ui.button("知道了", false, this::cancel),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sheet = new ScrollView(activity) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = Math.max(ui.dp(80), availableBounds().height() - ui.dp(24));
                int parent = MeasureSpec.getSize(heightMeasureSpec);
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && parent > 0) available = Math.min(available, parent);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
            }
        };
        sheet.setBackground(ui.rounded(ui.surface, 24));
        sheet.setClipToOutline(true);
        sheet.setFillViewport(false);
        sheet.setVerticalScrollBarEnabled(false);
        sheet.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        if (Build.VERSION.SDK_INT >= 28) sheet.setAccessibilityPaneTitle(title);
        sheet.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sheet);
    }

    @Override public void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        closing = false;
        super.show();
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.22f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = Math.min(ui.dp(520), Math.max(ui.dp(120), availableBounds().width() - ui.dp(24)));
        attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        attributes.y = ui.dp(12);
        attributes.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 30) attributes.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        window.setAttributes(attributes);
        if (ValueAnimator.areAnimatorsEnabled()) {
            sheet.setAlpha(0f);
            sheet.setTranslationY(ui.dp(18));
            sheet.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f)).start();
        } else {
            sheet.setAlpha(1f);
            sheet.setTranslationY(0);
        }
    }

    /** User dismissal eases out; lifecycle dismissal remains immediate. */
    @Override public void cancel() {
        if (closing || !isShowing()) return;
        closing = true;
        sheet.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || activity.isFinishing() || activity.isDestroyed()) {
            super.cancel();
            return;
        }
        sheet.animate().alpha(0f).translationY(ui.dp(12)).setDuration(140)
                .setInterpolator(new PathInterpolator(.4f, 0f, 1f, 1f))
                .withEndAction(() -> NativeHelpDialog.super.cancel()).start();
    }

    @Override public void onBackPressed() { cancel(); }

    @Override public void dismiss() {
        closing = true;
        sheet.animate().withEndAction(null);
        sheet.animate().cancel();
        super.dismiss();
    }

    private Rect availableBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.left += insets.left;
            bounds.top += insets.top;
            bounds.right -= insets.right;
            bounds.bottom -= insets.bottom;
            return bounds;
        }
        Rect bounds = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return new Rect(0, 0,
                activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels);
        return bounds;
    }
}
