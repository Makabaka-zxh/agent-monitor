package com.agentmonitor.live;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native colors, typography, geometry, symbols and bounded motion. */
public final class NativeUi {
    public static final float CARD_RADIUS = 22f;
    public static final float CONTROL_RADIUS = 16f;
    public final Activity activity;
    public final boolean dark;
    public final String backgroundMode;
    public final int bg, surface, text, muted, accent, line, soft, warning, danger;
    public final Typeface regular, medium, strong;

    public NativeUi(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        backgroundMode = NativeAppearancePreferences.mode(activity);
        bg = NativeAppearancePolicy.background(backgroundMode, dark);
        surface = color(dark ? "#212A34" : "#FFFFFF");
        text = color(dark ? "#F2F5F8" : "#202831");
        muted = color(dark ? "#A9B5C2" : "bing".equals(backgroundMode) ? "#4D5C6B" : "#5F6B78");
        accent = color(dark ? "#AFC8DF" : "bing".equals(backgroundMode) ? "#375974" : "#466987");
        line = color(dark ? "#354453" : "#E2E6EA");
        soft = color(dark ? "#2D3A48" : "#E7EDF2");
        warning = color(dark ? "#E1BE88" : "bing".equals(backgroundMode) ? "#735019" : "#866022");
        danger = color(dark ? "#EDABAB" : "bing".equals(backgroundMode) ? "#963737" : "#A44343");
        Typeface loaded;
        try { loaded = Typeface.createFromAsset(activity.getAssets(), "fonts/MiSans-Regular.ttf"); }
        catch (RuntimeException unavailable) { loaded = Typeface.create("sans-serif", Typeface.NORMAL); }
        regular = loaded;
        medium = android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(loaded, 500, false)
                : Typeface.create(loaded, Typeface.BOLD);
        strong = android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(loaded, 600, false)
                : Typeface.create(loaded, Typeface.BOLD);
    }

    public int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    public float sp(float value) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, activity.getResources().getDisplayMetrics()); }
    private static int color(String value) { return Color.parseColor(value); }
    public LinearLayout column() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    public LinearLayout row() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.HORIZONTAL); result.setGravity(Gravity.CENTER_VERTICAL); return result; }
    public TextView text(String value, float size, boolean bold) {
        TextView result = new TextView(activity);
        result.setText(value == null ? "" : value); result.setTextColor(text);
        result.setTextSize(size); result.setTypeface(bold ? strong : regular);
        result.setIncludeFontPadding(false); applyBodySpacing(result);
        return result;
    }
    public TextView text(String value, float size) { return text(value, size, false); }
    public TextView text(String value) { return text(value, 15, false); }
    /** Reading copy shares a comfortable rhythm without imposing fixed text heights. */
    public void applyBodySpacing(TextView view) { view.setLineSpacing(dp(6), 1.12f); }
    /** Labels and controls keep their natural line height, including when they wrap. */
    public void applyControlSpacing(TextView view) { view.setLineSpacing(0, 1f); }
    public TextView body(String value, float size) {
        TextView result = text(value, size); result.setTextColor(muted); return result;
    }
    public TextView body(String value) { return body(value, 15); }
    public TextView heading(String value, int size) {
        TextView result = text(value, size, true);
        applyControlSpacing(result);
        if (android.os.Build.VERSION.SDK_INT >= 28) result.setAccessibilityHeading(true);
        return result;
    }
    public void applyFont(View view) {
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            Typeface current = label.getTypeface();
            if (android.os.Build.VERSION.SDK_INT >= 28 && current != null)
                label.setTypeface(Typeface.create(regular, current.getWeight(), current.isItalic()));
            else label.setTypeface(Typeface.create(regular, current == null ? Typeface.NORMAL : current.getStyle()));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) applyFont(((ViewGroup) view).getChildAt(i));
    }
    public Drawable rounded(int fill, float radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    public Drawable bordered(int fill, float radius) {
        GradientDrawable drawable = (GradientDrawable) rounded(fill, radius); drawable.setStroke(dp(1), line); return drawable;
    }
    /** A quiet light field behind navigation, fading into the solid reading surface. */
    public Drawable headerLight() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{headerTopColor(), "bing".equals(backgroundMode) ? (0xe6000000 | (bg & 0x00ffffff)) : bg});
    }
    /** A translucent tint over live content, with an opaque accessibility fallback. */
    public Drawable compactHeaderMaterial() {
        return compactHeaderMaterial(reducedTransparencyEnabled());
    }
    Drawable compactHeaderMaterial(boolean opaque) {
        int tint = headerTopColor();
        if (opaque) return new ColorDrawable(tint);
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xe8000000 | (tint & 0x00ffffff),
                        0xdd000000 | (tint & 0x00ffffff), 0xa8000000 | (bg & 0x00ffffff)});
    }
    public int headerTopColor() { return NativeAppearancePolicy.header(backgroundMode, dark); }
    /** Each header action owns its material; the group adds no second capsule or shadow. */
    public LinearLayout headerActions() {
        return row();
    }
    public Drawable ripple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x25FFFFFF : 0x12466987), rounded(fill, radius), rounded(Color.WHITE, radius));
    }
    public Drawable borderedRipple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x20FFFFFF : 0x12466987), bordered(fill, radius), rounded(Color.WHITE, radius));
    }
    public LinearLayout card() {
        LinearLayout result = column(); result.setPadding(dp(16), dp(16), dp(16), dp(16));
        result.setBackground(bordered(surface, CARD_RADIUS)); return result;
    }
    public View space(float height) { View result = new View(activity); result.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return result; }
    public TextView button(String label, Runnable action) { return button(label, true, action); }
    public TextView button(String label, boolean primary, Runnable action) {
        TextView result = text(label, 15, true); result.setTypeface(medium); result.setGravity(Gravity.CENTER);
        applyControlSpacing(result);
        result.setPadding(dp(16), dp(12), dp(16), dp(12)); result.setMinHeight(dp(48));
        result.setTextColor(primary ? (dark ? bg : Color.WHITE) : accent);
        result.setBackground(ripple(primary ? accent : soft, CONTROL_RADIUS));
        result.setFocusable(true); result.setClickable(true); result.setOnClickListener(v -> { if (action != null) action.run(); });
        pressFeedback(result, .98f);
        result.setAccessibilityDelegate(buttonAccessibility());
        return result;
    }
    /** A secondary action keeps its touch target without competing with page content. */
    public TextView quietButton(String label, Runnable action) {
        TextView result = button(label, false, action);
        result.setTextSize(14); result.setTextColor(muted);
        result.setBackground(ripple(Color.TRANSPARENT, 10));
        result.setPadding(dp(12), dp(10), dp(12), dp(10));
        return result;
    }
    public View iconButton(String name, String description, Runnable action) {
        Icon result = new Icon(name, accent);
        result.setMinimumWidth(dp(48)); result.setMinimumHeight(dp(48));
        result.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); result.setBackground(ripple(Color.TRANSPARENT, 16));
        result.setContentDescription(description); result.setFocusable(true); result.setClickable(true);
        result.setTooltipText(description);
        result.setOnClickListener(v -> { if (action != null) action.run(); }); result.setAccessibilityDelegate(buttonAccessibility());
        pressFeedback(result, .96f);
        return result;
    }
    /** A 36 dp quiet surface and a 20 dp glyph inside a 48 dp accessible touch target. */
    public View headerIconButton(String name, String description, Runnable action) {
        Icon result = (Icon) iconButton(name, description, action);
        result.glyphSize = 20;
        setHeaderIconSelected(result, false);
        return result;
    }
    /** Selection changes the same inset material rather than replacing it with a full-size fill. */
    public void setHeaderIconSelected(View action, boolean selected) {
        action.setSelected(selected);
        if (action instanceof Icon) ((Icon) action).setInk(selected ? accent : muted);
        int material = selected ? soft : Color.TRANSPARENT;
        action.setBackground(new InsetDrawable(ripple(material, 18), dp(6)));
    }
    public View icon(String name, int size) {
        Icon result = new Icon(name, muted); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public ImageView brand(String tool, int size) {
        ImageView result = new ImageView(activity); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setScaleType(ImageView.ScaleType.FIT_CENTER); bindBrand(result, tool);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public ImageView monitorBrand(int size) {
        ImageView result = new ImageView(activity); result.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        result.setScaleType(ImageView.ScaleType.FIT_CENTER); result.setImageResource(R.drawable.monitor_brand);
        result.setContentDescription("Monitor"); return result;
    }
    public void bindBrand(ImageView view, String tool) {
        int resource = "claude".equals(tool) ? R.drawable.claude : "codex".equals(tool) ? R.drawable.codex
                : "antigravity".equals(tool) ? R.drawable.antigravity : R.drawable.app_icon;
        Object last = view.getTag(); if (!(last instanceof Integer) || ((Integer)last) != resource) { view.setImageResource(resource); view.setTag(resource); }
    }
    /** Text is always present: source identity never relies on icon recognition or color. */
    public LinearLayout toolBadge(String tool) {
        LinearLayout result = row(); result.setPadding(0, dp(4), 0, dp(4));
        result.addView(brand(tool, 16));
        TextView label = text("", 12); label.setMaxLines(1); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        words.setMarginStart(dp(6)); result.addView(label, words); bindToolBadge(result, tool);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return result;
    }
    public void bindToolBadge(LinearLayout badge, String tool) {
        String source = NativeScreenPolicy.toolIndex(tool) > 0 ? tool : "other";
        if (source.equals(badge.getTag())) return;
        bindBrand((ImageView) badge.getChildAt(0), source);
        TextView label = (TextView) badge.getChildAt(1); label.setText(NativeListPresentation.toolLabel(source));
        boolean claude = "claude".equals(source);
        int ink = claude ? color(dark ? "#CDA991" : "#96664F") : accent;
        if ("other".equals(source)) ink = muted;
        label.setTextColor(ink); badge.setBackground(null); badge.setTag(source);
    }
    public LinearLayout taskIdentity(String tool, String computer) {
        LinearLayout result = toolBadge(tool);
        TextView label = (TextView) result.getChildAt(1);
        LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        words.setMarginStart(dp(6)); label.setLayoutParams(words);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        bindTaskIdentity(result, tool, computer); return result;
    }
    public void bindTaskIdentity(LinearLayout badge, String tool, String computer) {
        bindToolBadge(badge, tool);
        String brand = NativeListPresentation.toolLabel(tool);
        String identity = LivePresentation.identity(brand, computer, true);
        TextView label = (TextView) badge.getChildAt(1);
        if (!android.text.TextUtils.equals(label.getText(), identity) || !(label.getText() instanceof android.text.Spanned)) {
            android.text.SpannableString styled = new android.text.SpannableString(identity);
            styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, brand.length(), 0);
            if (identity.length() > brand.length()) styled.setSpan(new android.text.style.ForegroundColorSpan(muted), brand.length(), identity.length(), 0);
            label.setText(styled);
        }
        badge.setContentDescription(identity);
    }
    public boolean motionEnabled() { return ValueAnimator.areAnimatorsEnabled(); }
    public boolean highContrastEnabled() { return secureFlag("high_text_contrast_enabled"); }
    /** Android has no universal reduce-transparency switch; honor readable vendor settings too. */
    public boolean reducedTransparencyEnabled() {
        return highContrastEnabled() || secureFlag("accessibility_reduce_transparency")
                || secureFlag("reduce_transparency") || systemFlag("accessibility_reduce_transparency")
                || systemFlag("reduce_transparency");
    }
    private boolean secureFlag(String key) {
        try { return Settings.Secure.getInt(activity.getContentResolver(), key, 0) != 0; }
        catch (RuntimeException unavailable) { return false; }
    }
    private boolean systemFlag(String key) {
        try { return Settings.System.getInt(activity.getContentResolver(), key, 0) != 0; }
        catch (RuntimeException unavailable) { return false; }
    }
    /** Uses drawable-state animations, leaving scrolling and accessibility gestures untouched. */
    public void pressFeedback(View view, float pressedScale) {
        if (!motionEnabled()) { view.setStateListAnimator(null); return; }
        StateListAnimator states = new StateListAnimator();
        ObjectAnimator press = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale), PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale));
        press.setDuration(100); press.setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f));
        ObjectAnimator release = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        release.setDuration(220); release.setInterpolator(new PathInterpolator(.16f, 1f, .3f, 1f));
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, press);
        states.addState(new int[]{}, release); view.setStateListAnimator(states);
    }
    /** A short, local transition for newly opened content; refreshing rows never calls this. */
    public void reveal(View view) {
        view.animate().cancel(); view.setAlpha(1f); view.setTranslationY(0f);
        if (!motionEnabled()) return;
        view.setAlpha(.9f); view.setTranslationY(dp(6));
        view.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f)).start();
    }
    /** New page content only: no data reload, outgoing capture or animation of live row refreshes. */
    public void transitionPage(View incoming, String previous, String next) {
        transitionPage(incoming, previous, next, "");
    }
    public void transitionPage(View incoming, String previous, String next, String taskReturnDevice) {
        incoming.animate().cancel(); incoming.setAlpha(1f); incoming.setTranslationX(0f); incoming.setTranslationY(0f);
        if (!motionEnabled()) return;
        int direction = NativeNavigationPolicy.transitionDirection(previous, next, taskReturnDevice);
        if (direction == 0) return;
        incoming.setTranslationX(dp(direction > 0 ? 10 : -10));
        incoming.setAlpha(.9f);
        incoming.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(240)
                .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f)).start();
    }
    public static View.AccessibilityDelegate buttonAccessibility() {
        return new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
            }
        };
    }
    public Drawable background() {
        return new ColorDrawable(bg);
    }

    /** Code-owned line icons have consistent optical weight and no font-dependent glyphs. */
    public final class Icon extends View {
        private String name; private int ink; private int glyphSize = 24;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String name, int ink) { super(activity); this.name = name == null ? "" : name; this.ink = ink; }
        public void setName(String name) { String next = name == null ? "" : name; if (!this.name.equals(next)) { this.name = next; invalidate(); } }
        public void setInk(int ink) { if (this.ink != ink) { this.ink = ink; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); float size = Math.min(Math.min(getWidth(), getHeight()), dp(glyphSize));
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f); canvas.scale(size / 24f, size / 24f);
            paint.setColor(ink); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            switch (name) {
                case "back": path.moveTo(14.5f, 5.5f); path.lineTo(8, 12); path.lineTo(14.5f, 18.5f); break;
                case "chevron": path.moveTo(9.5f, 6.5f); path.lineTo(15, 12); path.lineTo(9.5f, 17.5f); break;
                case "chevron-down": path.moveTo(6.5f, 9.5f); path.lineTo(12, 15); path.lineTo(17.5f, 9.5f); break;
                case "chevron-up": path.moveTo(6.5f, 14.5f); path.lineTo(12, 9); path.lineTo(17.5f, 14.5f); break;
                case "more": paint.setStyle(Paint.Style.FILL); canvas.drawCircle(6, 12, 1.65f, paint); canvas.drawCircle(12, 12, 1.65f, paint); canvas.drawCircle(18, 12, 1.65f, paint); paint.setStyle(Paint.Style.STROKE); break;
                case "plus": path.moveTo(12, 5.5f); path.lineTo(12, 18.5f); path.moveTo(5.5f, 12); path.lineTo(18.5f, 12); break;
                case "close": path.moveTo(6.5f, 6.5f); path.lineTo(17.5f, 17.5f); path.moveTo(17.5f, 6.5f); path.lineTo(6.5f, 17.5f); break;
                case "check": path.moveTo(5, 12.5f); path.lineTo(9.5f, 17); path.lineTo(19, 7.5f); break;
                case "search": canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); path.moveTo(15.5f, 15.5f); path.lineTo(20, 20); break;
                case "usage": canvas.drawRoundRect(new RectF(4, 13, 7.5f, 20), 1.6f, 1.6f, paint); canvas.drawRoundRect(new RectF(10.25f, 8.5f, 13.75f, 20), 1.6f, 1.6f, paint); canvas.drawRoundRect(new RectF(16.5f, 4, 20, 20), 1.6f, 1.6f, paint); break;
                case "error": canvas.drawCircle(12, 12, 8.5f, paint); path.moveTo(12, 7.5f); path.lineTo(12, 12.5f); path.moveTo(12, 16.5f); path.lineTo(12, 16.6f); break;
                case "account": case "user": canvas.drawCircle(12, 7.5f, 3.5f, paint); path.moveTo(4.5f, 20); path.cubicTo(4.5f, 12.5f, 19.5f, 12.5f, 19.5f, 20); path.close(); break;
                case "monitor": case "devices": canvas.drawRoundRect(new RectF(3.5f, 4, 20.5f, 16), 2.5f, 2.5f, paint); path.moveTo(12, 16); path.lineTo(12, 20); path.moveTo(8.5f, 20); path.lineTo(15.5f, 20); break;
                case "tasks": canvas.drawRoundRect(new RectF(4.5f, 3.5f, 19.5f, 20.5f), 3, 3, paint); path.moveTo(8, 8); path.lineTo(8.1f, 8); path.moveTo(11.5f, 8); path.lineTo(16, 8); path.moveTo(8, 12); path.lineTo(8.1f, 12); path.moveTo(11.5f, 12); path.lineTo(16, 12); path.moveTo(8, 16); path.lineTo(8.1f, 16); path.moveTo(11.5f, 16); path.lineTo(14.5f, 16); break;
                case "archive": canvas.drawRoundRect(new RectF(3.5f, 4, 20.5f, 8.5f), 1.5f, 1.5f, paint); path.moveTo(5, 8.5f); path.lineTo(5, 18); path.quadTo(5, 20, 7, 20); path.lineTo(17, 20); path.quadTo(19, 20, 19, 18); path.lineTo(19, 8.5f); path.moveTo(9.5f, 12.5f); path.lineTo(14.5f, 12.5f); break;
                case "restore": canvas.drawArc(new RectF(4.5f, 4.5f, 20, 20), -80, 295, false, paint); path.moveTo(4, 3.5f); path.lineTo(4, 9); path.lineTo(9.5f, 9); path.moveTo(12.25f, 8.5f); path.lineTo(12.25f, 12.5f); path.lineTo(15, 14); break;
                case "refresh": canvas.drawArc(new RectF(4, 4, 20, 20), 45, 280, false, paint); path.moveTo(20, 4); path.lineTo(20, 9); path.lineTo(15, 9); break;
                case "settings": path.moveTo(4, 7); path.lineTo(7, 7); path.moveTo(12, 7); path.lineTo(20, 7); canvas.drawCircle(9.5f, 7, 2.5f, paint); path.moveTo(4, 17); path.lineTo(12, 17); path.moveTo(17, 17); path.lineTo(20, 17); canvas.drawCircle(14.5f, 17, 2.5f, paint); break;
                case "help": canvas.drawCircle(12, 12, 8.5f, paint); path.moveTo(9.5f, 9); path.cubicTo(9.5f, 5.8f, 15.7f, 6.3f, 14.8f, 9.5f); path.cubicTo(14.4f, 11, 12, 11, 12, 13); path.moveTo(12, 16.5f); path.lineTo(12, 16.6f); break;
                case "bell": path.moveTo(6, 10); path.cubicTo(6, 3, 18, 3, 18, 10); path.lineTo(18, 14); path.quadTo(18, 15.5f, 19.5f, 17); path.lineTo(4.5f, 17); path.quadTo(6, 15.5f, 6, 14); path.close(); canvas.drawArc(new RectF(9, 16, 15, 22), 35, 110, false, paint); break;
                case "image": canvas.drawRoundRect(new RectF(3.5f, 4, 20.5f, 20), 3, 3, paint); canvas.drawCircle(8.5f, 9, 1.5f, paint); path.moveTo(4, 17); path.lineTo(10.5f, 11.5f); path.lineTo(14.5f, 15.5f); path.lineTo(17, 13.5f); path.lineTo(20, 16); break;
                case "cloud": path.moveTo(7.5f, 18.5f); path.cubicTo(1.5f, 18.5f, 1.5f, 10, 7.1f, 10); path.cubicTo(7.1f, 2.7f, 18, 2.7f, 18, 10); path.cubicTo(23, 10.5f, 22.5f, 18.5f, 17, 18.5f); path.close(); break;
                case "shield": path.moveTo(12, 3.5f); path.quadTo(16, 5.5f, 20, 6.5f); path.lineTo(19.5f, 12.5f); path.cubicTo(19, 16.5f, 16, 19, 12, 21); path.cubicTo(8, 19, 5, 16.5f, 4.5f, 12.5f); path.lineTo(4, 6.5f); path.quadTo(8, 5.5f, 12, 3.5f); path.close(); break;
                case "link": path.moveTo(9, 14.5f); path.cubicTo(6.5f, 15.5f, 4, 13.5f, 4, 11); path.cubicTo(4, 8.5f, 6, 7, 8.5f, 7); path.lineTo(10.5f, 7); path.moveTo(15, 9.5f); path.cubicTo(17.5f, 8.5f, 20, 10.5f, 20, 13); path.cubicTo(20, 15.5f, 18, 17, 15.5f, 17); path.lineTo(13.5f, 17); path.moveTo(8.5f, 12); path.lineTo(15.5f, 12); break;
                case "globe": canvas.drawCircle(12, 12, 8.5f, paint); path.moveTo(3.8f, 12); path.lineTo(20.2f, 12); canvas.drawOval(new RectF(8.2f, 3.5f, 15.8f, 20.5f), paint); break;
                case "scan": path.moveTo(8, 4); path.lineTo(6, 4); path.quadTo(4, 4, 4, 6); path.lineTo(4, 8); path.moveTo(16, 4); path.lineTo(18, 4); path.quadTo(20, 4, 20, 6); path.lineTo(20, 8); path.moveTo(20, 16); path.lineTo(20, 18); path.quadTo(20, 20, 18, 20); path.lineTo(16, 20); path.moveTo(8, 20); path.lineTo(6, 20); path.quadTo(4, 20, 4, 18); path.lineTo(4, 16); path.moveTo(7, 12); path.lineTo(17, 12); break;
                case "logout": path.moveTo(10, 4); path.lineTo(6, 4); path.quadTo(4, 4, 4, 6); path.lineTo(4, 18); path.quadTo(4, 20, 6, 20); path.lineTo(10, 20); path.moveTo(10, 12); path.lineTo(20, 12); path.moveTo(16.5f, 8.5f); path.lineTo(20, 12); path.lineTo(16.5f, 15.5f); break;
                case "": break;
                default: canvas.drawRoundRect(new RectF(4, 4, 20, 20), 5, 5, paint); path.moveTo(8, 12); path.lineTo(16, 12); break;
            }
            canvas.drawPath(path, paint); canvas.restore();
        }
    }
}
