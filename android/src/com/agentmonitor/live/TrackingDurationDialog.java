package com.agentmonitor.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Native countdown wheels. A duration is committed only by the explicit start button. */
public final class TrackingDurationDialog {
    private static final int UNTIL_TASK_END = -1;
    private static final int DEFAULT_MINUTES = 30;
    private static final int MAX_MINUTES = 23 * 60 + 59;
    // NumberPicker can report IDLE before its final 800 ms adjustment animation ends.
    private static final long WHEEL_SETTLE_MS = 900L;

    private TrackingDurationDialog() { }

    public interface Callback {
        void confirm(int selection, int timedMinutes);
    }

    /** Returns an already visible dialog. Cancellation never invokes onConfirm. */
    public static Dialog show(Activity activity, NativeUi ui, int preferredModeMinutes,
                              int preferredTimedMinutes, Callback onConfirm, Runnable onDismiss) {
        Sheet sheet = new Sheet(activity, ui, preferredModeMinutes, preferredTimedMinutes, onConfirm, onDismiss);
        sheet.show();
        return sheet.dialog;
    }

    private static boolean validMinutes(int minutes) { return minutes >= 1 && minutes <= MAX_MINUTES; }

    private static final class Sheet {
        final Activity activity;
        final NativeUi ui;
        final Dialog dialog;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Callback onConfirm;
        final Runnable onDismiss;
        final LinearLayout timedContent;
        final LinearLayout untilContent;
        final Wheel hours;
        final Wheel minutes;
        final TextView start;
        final TextView validation;
        final ScrollView scroll;
        AlertDialog helpDialog;
        boolean untilTaskEnd;
        boolean dismissed;
        boolean committed;
        int lastTimedMinutes;
        long settleAfter;
        final Runnable settled = this::updateConfirmation;

        Sheet(Activity activity, NativeUi ui, int preferredModeMinutes, int preferredTimedMinutes,
              Callback onConfirm, Runnable onDismiss) {
            this.activity = activity;
            this.ui = ui;
            this.onConfirm = onConfirm;
            this.onDismiss = onDismiss;
            untilTaskEnd = preferredModeMinutes == UNTIL_TASK_END;
            lastTimedMinutes = validMinutes(preferredModeMinutes) ? preferredModeMinutes
                    : validMinutes(preferredTimedMinutes) ? preferredTimedMinutes : DEFAULT_MINUTES;
            dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
            dialog.setCanceledOnTouchOutside(true);

            LinearLayout content = ui.column();
            content.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(20));
            content.setBackground(ui.bordered(ui.surface, 24));
            content.setClipToOutline(true);

            LinearLayout header = ui.row();
            TextView title = ui.text("跟踪时长", 20, true);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            header.addView(ui.iconButton("info", "跟踪时长说明", this::showHelp));
            content.addView(header);
            content.addView(ui.space(12));

            NativePages.Segments modes = new NativePages.Segments(ui,
                    new String[]{"timed", "until"}, new String[]{"定时", "直至任务结束"}, key -> {
                untilTaskEnd = "until".equals(key);
                showMode();
            });
            modes.select(untilTaskEnd ? "until" : "timed");
            content.addView(modes.view(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(ui.space(22));

            timedContent = ui.column();
            LinearLayout labels = ui.row();
            addWheelLabel(labels, "小时");
            addWheelLabel(labels, "分钟");
            timedContent.addView(labels);
            timedContent.addView(ui.space(6));

            float numberSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28,
                    activity.getResources().getDisplayMetrics());
            int wheelHeight = Math.max(ui.dp(196), Math.round(numberSize * 4.4f));
            int selectionHeight = Math.max(ui.dp(56), Math.round(numberSize * 1.65f));
            FrameLayout wheelArea = new FrameLayout(activity);
            View highlight = new View(activity);
            highlight.setBackground(ui.rounded(ui.soft, 18));
            highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams highlightParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, selectionHeight, Gravity.CENTER);
            wheelArea.addView(highlight, highlightParams);

            LinearLayout wheels = ui.row();
            wheels.setGravity(Gravity.CENTER);
            hours = wheel(23, "小时", lastTimedMinutes / 60, numberSize);
            minutes = wheel(59, "分钟", lastTimedMinutes % 60, numberSize);
            wheels.addView(hours, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            wheels.addView(minutes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            wheelArea.addView(wheels, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            timedContent.addView(wheelArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wheelHeight));

            validation = ui.text("请选择至少 1 分钟", 13);
            validation.setTextColor(ui.warning);
            validation.setGravity(Gravity.CENTER);
            validation.setPadding(0, ui.dp(4), 0, 0);
            validation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            validation.setVisibility(View.INVISIBLE);
            timedContent.addView(validation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(timedContent);

            untilContent = ui.column();
            untilContent.setGravity(Gravity.CENTER);
            untilContent.setPadding(ui.dp(12), ui.dp(34), ui.dp(12), ui.dp(34));
            untilContent.setMinimumHeight(wheelHeight + ui.dp(35));
            FrameLayout iconBadge = new FrameLayout(activity);
            iconBadge.setBackground(ui.rounded(ui.soft, 22));
            iconBadge.addView(ui.icon("check", 30), new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
            untilContent.addView(iconBadge, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
            untilContent.addView(ui.space(22));
            TextView untilTitle = ui.text("任务结束时自动停止", 17, true);
            untilTitle.setGravity(Gravity.CENTER);
            untilContent.addView(untilTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(untilContent);

            content.addView(ui.space(22));
            LinearLayout actions = ui.row();
            TextView cancel = ui.button("取消", false, dialog::dismiss);
            actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View gap = new View(activity);
            actions.addView(gap, new LinearLayout.LayoutParams(ui.dp(12), 1));
            start = ui.button("开始跟踪", true, this::confirm);
            actions.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.65f));
            content.addView(actions);

            scroll = new ScrollView(activity) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int maxHeight = Math.max(ui.dp(120), availableBounds().height() - ui.dp(24));
                    int parentSize = MeasureSpec.getSize(heightMeasureSpec);
                    if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && parentSize > 0) maxHeight = Math.min(maxHeight, parentSize);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
                }
            };
            scroll.setBackground(ui.rounded(ui.surface, 24));
            scroll.setClipToOutline(true);
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(scroll);
            dialog.setOnDismissListener(ignored -> {
                dismissed = true;
                handler.removeCallbacksAndMessages(null);
                if (helpDialog != null) { helpDialog.dismiss(); helpDialog = null; }
                if (onDismiss != null) onDismiss.run();
            });
            showMode();
        }

        void addWheelLabel(LinearLayout parent, String label) {
            TextView view = ui.text(label, 14);
            view.setTextColor(ui.muted);
            view.setGravity(Gravity.CENTER);
            parent.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }

        Wheel wheel(int maximum, String label, int value, float size) {
            Context context = new ContextThemeWrapper(activity, ui.dark ? R.style.TrackingWheelDark : R.style.TrackingWheelLight);
            Wheel wheel = new Wheel(context, this::wheelMoved);
            wheel.setMinValue(0);
            wheel.setMaxValue(maximum);
            wheel.setWrapSelectorWheel(false);
            wheel.setFormatter(number -> String.format(Locale.ROOT, "%02d", number));
            wheel.setValue(value);
            wheel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            wheel.setContentDescription(label);
            wheel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            wheel.setBackgroundColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                wheel.setSelectionDividerHeight(0);
                wheel.setTextColor(ui.muted);
                wheel.setTextSize(size);
            }
            stylePickerChildren(wheel);
            wheel.setOnValueChangedListener((picker, before, after) -> wheelMoved());
            wheel.setOnScrollListener((picker, state) -> {
                wheel.scrollState = state;
                wheelMoved();
            });
            return wheel;
        }

        // Construction used the scoped MiSans theme, so the native wheel paint already has MiSans.
        void stylePickerChildren(View view) {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                text.setTypeface(ui.medium);
                text.setTextColor(ui.text);
                text.setTextSize(28);
                text.setIncludeFontPadding(false);
                text.setPadding(0, 0, 0, 0);
            }
            if (view instanceof EditText) {
                EditText input = (EditText) view;
                input.setShowSoftInputOnFocus(false);
                input.setCursorVisible(false);
                input.setLongClickable(false);
                input.setTextIsSelectable(false);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) stylePickerChildren(group.getChildAt(i));
            }
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.25f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = Math.min(ui.dp(520), availableBounds().width() - ui.dp(24));
            attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            attributes.y = ui.dp(12);
            attributes.windowAnimations = 0;
            if (Build.VERSION.SDK_INT >= 30) attributes.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            window.setAttributes(attributes);
            // No custom animation: wheel physics and system motion preferences remain native.
        }

        Rect availableBounds() {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
                Rect bounds = new Rect(metrics.getBounds());
                android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                bounds.left += insets.left; bounds.top += insets.top;
                bounds.right -= insets.right; bounds.bottom -= insets.bottom;
                return bounds;
            }
            Rect visible = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            if (visible.width() <= 0 || visible.height() <= 0) return new Rect(0, 0,
                    activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels);
            return visible;
        }

        void showMode() {
            timedContent.setVisibility(untilTaskEnd ? View.GONE : View.VISIBLE);
            untilContent.setVisibility(untilTaskEnd ? View.VISIBLE : View.GONE);
            updateConfirmation();
        }

        void wheelMoved() {
            if (dismissed || committed) return;
            settleAfter = SystemClock.uptimeMillis() + WHEEL_SETTLE_MS;
            updateConfirmation();
        }

        boolean wheelsIdle() {
            return !hours.touching && !minutes.touching
                    && hours.scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE
                    && minutes.scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE
                    && SystemClock.uptimeMillis() >= settleAfter;
        }

        void updateConfirmation() {
            if (dismissed || committed || start == null) return;
            handler.removeCallbacks(settled);
            int value = hours.getValue() * 60 + minutes.getValue();
            boolean idle = wheelsIdle();
            if (idle && validMinutes(value)) lastTimedMinutes = value;
            boolean enabled = untilTaskEnd || (idle && validMinutes(value));
            start.setEnabled(enabled);
            start.setAlpha(enabled ? 1f : .42f);
            validation.setVisibility(!validMinutes(value) && idle ? View.VISIBLE : View.INVISIBLE);
            if (!idle) handler.postDelayed(settled, Math.max(60L, Math.min(250L, settleAfter - SystemClock.uptimeMillis())));
        }

        void confirm() {
            if (dismissed || committed) return;
            int value = hours.getValue() * 60 + minutes.getValue();
            if (!untilTaskEnd && (!wheelsIdle() || !validMinutes(value))) { updateConfirmation(); return; }
            if (wheelsIdle() && validMinutes(value)) lastTimedMinutes = value;
            int selection = untilTaskEnd ? UNTIL_TASK_END : value;
            committed = true;
            start.setEnabled(false);
            try { if (onConfirm != null) onConfirm.confirm(selection, lastTimedMinutes); }
            finally { dialog.dismiss(); }
        }

        void showHelp() {
            if (helpDialog != null || dismissed) return;
            AlertDialog help = new AlertDialog.Builder(new ContextThemeWrapper(activity,
                    ui.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert))
                    .setTitle("跟踪时长")
                    .setMessage("定时：到时或任务停止运行时结束；状态无法确认超过 45 秒也会停止。\n\n直至任务结束：等待批准或在线任务状态待确认时继续关注，执行恢复后更新实况。任务完成、失败、空闲、归档或电脑离线时停止。\n\n连接中断、任务记录缺失或状态无效超过 45 秒会停止。Android 15 及以上可能限制持续后台运行时长；系统结束时会提示。")
                    .setPositiveButton("知道了", null)
                    .create();
            helpDialog = help;
            help.setOnDismissListener(ignored -> { if (helpDialog == help) helpDialog = null; });
            help.setOnShowListener(ignored -> {
                Window window = help.getWindow();
                if (window != null) ui.applyFont(window.getDecorView());
                help.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
            });
            help.show();
        }
    }

    /** Keeps confirmation disabled across touch, keyboard, accessibility and fling settling. */
    private static final class Wheel extends NumberPicker {
        final Runnable motion;
        int scrollState = OnScrollListener.SCROLL_STATE_IDLE;
        boolean touching;

        Wheel(Context context, Runnable motion) { super(context); this.motion = motion; }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                motion.run();
            }
            boolean result = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                motion.run();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return result;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) motion.run();
            return super.dispatchKeyEvent(event);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) motion.run();
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
