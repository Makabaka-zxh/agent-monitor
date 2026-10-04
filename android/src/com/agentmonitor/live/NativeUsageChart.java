package com.agentmonitor.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;

/** A complete week fits one screen; tall columns provide generous tap targets. */
public final class NativeUsageChart extends LinearLayout {
    public interface Selection { void select(LocalDate day); }
    private final NativeUi ui;
    private final LinearLayout[] columns = new LinearLayout[7];
    private final Bar[] bars = new Bar[7];
    private final LocalDate[] days = new LocalDate[7];
    public NativeUsageChart(NativeUi ui, LocalDate week, Long[] amounts, LocalDate start,
                            LocalDate end, LocalDate selected, Selection selection) {
        super(ui.activity); this.ui = ui;
        setOrientation(LinearLayout.HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        long maximum = 0; for (Long amount : amounts) if (amount != null) maximum = Math.max(maximum, amount);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            final LocalDate day = week.plusDays(i); days[i] = day;
            LinearLayout column = ui.column(); columns[i] = column;
            column.setGravity(Gravity.CENTER);
            column.setPadding(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(7));
            boolean availableDay = !day.isBefore(start) && !day.isAfter(end);
            column.setEnabled(availableDay); column.setFocusable(availableDay); column.setClickable(availableDay);
            column.setAlpha(availableDay ? 1f : .3f);
            column.setAccessibilityDelegate(NativeUi.buttonAccessibility());
            column.setContentDescription(day + "，" + (amounts[i] == null ? "未提供记录" : NativeUsageFormat.exact(amounts[i]) + " Token"));
            if (availableDay) column.setOnClickListener(v -> { select(day); selection.select(day); });
            Bar bar = new Bar(amounts[i], maximum); bars[i] = bar;
            column.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(96)));
            TextView weekday = ui.text(names[i], 13); weekday.setTextColor(ui.muted); weekday.setGravity(Gravity.CENTER);
            weekday.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            column.addView(ui.space(8)); column.addView(weekday, new LinearLayout.LayoutParams(-1, -2));
            TextView date = ui.text(String.valueOf(day.getDayOfMonth()), 12); date.setGravity(Gravity.CENTER); date.setTextColor(ui.muted);
            date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); column.addView(ui.space(3)); column.addView(date);
            addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        }
        select(selected);
    }
    public void select(LocalDate selected) {
        for (int i = 0; i < days.length; i++) {
            boolean active = days[i].equals(selected); columns[i].setSelected(active);
            columns[i].setBackground(ui.ripple(active ? ui.soft : android.graphics.Color.TRANSPARENT, 12));
            bars[i].selected = active; bars[i].invalidate();
        }
    }
    private final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Long amount; private final long maximum; private boolean selected;
        Bar(Long amount, long maximum) { super(ui.activity); this.amount = amount; this.maximum = maximum; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float bottom = getHeight() - ui.dp(3), width = Math.min(ui.dp(22), getWidth() - ui.dp(10));
            paint.setColor(selected ? ui.accent : ui.dark ? 0xFF7BADA1 : 0xFF93B8AD);
            if (amount == null || amount == 0) {
                paint.setColor(ui.muted); paint.setTypeface(ui.regular); paint.setTextSize(ui.dp(13)); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(amount == null ? "—" : "0", getWidth() / 2f, bottom, paint); return;
            }
            float height = Math.max(ui.dp(5), (float) (NativeUsagePresentation.ratio(amount, maximum) * (getHeight() - ui.dp(10))));
            float left = (getWidth() - width) / 2f;
            canvas.drawRoundRect(new RectF(left, bottom - height, left + width, bottom), ui.dp(6), ui.dp(6), paint);
        }
    }
}
