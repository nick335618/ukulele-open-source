package com.ukulele.monitor.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.ukulele.monitor.ui.AppTheme.Colors;

/**
 * Design-system factory for Ukulele Studio.
 *
 * <p>Frozen contract: {@code docs/团队简报与接口契约.md} section 2.2. Method signatures must not
 * change. Everything here is built from platform views only - no AndroidX, no Material, no
 * third-party code.
 *
 * <p>Shape tokens: card 18 / inner element 14 / button 14 / pill 999. Spacing steps:
 * 4 / 8 / 12 / 16 / 20 / 24. Page padding 20, card padding 16-14, card gap 12.
 *
 * <p>All sizes go through {@link #dp(float)} / {@link #sp(float)} - never raw pixels.
 * Colors always come from {@link AppTheme#colors()}.
 */
public abstract class UiKit extends ContextWrapper {

    /** Cards. */
    public static final float RADIUS_CARD = 18f;
    /** Buttons, inputs, inner elements. */
    public static final float RADIUS_INNER = 14f;
    /** Capsules. */
    public static final float RADIUS_PILL = 999f;

    protected UiKit(Context base) {
        super(base);
    }

    /**
     * Factory used by MainActivity: {@code kit = UiKit.create(this);}.
     *
     * <p>The class stays abstract per contract 2.2, so the returned instance is an anonymous
     * subclass.
     *
     * <p>No double text scaling: {@link #sp(float)} does not read SettingsStore. The app text
     * scale is applied by MainActivity before the value is passed in, and multiplying by
     * scaledDensity here is exactly what {@code TextView.setTextSize(COMPLEX_UNIT_SP, v)} does,
     * so {@code kit.text(s, 14 * uiScale, c)} and the legacy {@code text(s, 14, c)} render at the
     * same pixel size.
     */
    public static UiKit create(Context base) {
        return new UiKit(base) { };
    }

    // ------------------------------------------------------------------ metrics

    public int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    public float dpf(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /** Scaled pixels: user font scale is already folded in by scaledDensity. */
    public int sp(float value) {
        return (int) (value * getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }

    /** System status bar height, with a 24dp fallback when the dimen is unavailable. */
    public int statusBarHeight() {
        Resources res = getResources();
        int id = res.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = res.getDimensionPixelSize(id);
            if (h > 0) {
                return h;
            }
        }
        return dp(24f);
    }

    // ------------------------------------------------------------------ drawables

    public GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dpf(radiusDp * AppTheme.cornerScale()));
        return d;
    }

    public GradientDrawable bg(int color, float radiusDp, int strokeColor, float strokeWidthPx) {
        GradientDrawable d = bg(color, radiusDp);
        int w = Math.round(strokeWidthPx);
        d.setStroke(w < 1 ? 1 : w, strokeColor);
        return d;
    }

    public GradientDrawable gradient(int topColor, int bottomColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topColor, bottomColor});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dpf(radiusDp * AppTheme.cornerScale()));
        return d;
    }

    /** Card background: surface fill + hairline stroke, 18dp radius. */
    public Drawable card() {
        Colors c = AppTheme.colors();
        return bg(c.surface, RADIUS_CARD, c.stroke, dpf(1f));
    }

    /** One step brighter than {@link #card()} with a stronger hairline. */
    public Drawable cardElevated() {
        Colors c = AppTheme.colors();
        return bg(c.surface2, RADIUS_CARD, c.strokeStrong, dpf(1f));
    }

    /**
     * Capsule background. Selected = accent fill; unselected = surface2 fill, so the caller can
     * pair it with bgRoot / textSecondary text respectively.
     */
    public Drawable pill(boolean selected, int accentColor) {
        Colors c = AppTheme.colors();
        return bg(selected ? accentColor : c.surface2, RADIUS_PILL);
    }

    /**
     * Rounded background with a press ripple whose colour is picked from {@code baseColor}
     * (see {@link #pressColorFor(int)}: accent-filled surfaces get a light wash so the
     * press is actually visible).
     */
    public Drawable ripple(int baseColor, float radiusDp) {
        return rippleOver(bg(baseColor, radiusDp), radiusDp, baseColor);
    }

    public ColorStateList tint(int color) {
        return ColorStateList.valueOf(color);
    }

    // ------------------------------------------------------------------ text

    public TextView text(String value, float sizeSp, int color) {
        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(sizeSp));
        v.setTextColor(color);
        v.setIncludeFontPadding(true);
        return v;
    }

    public TextView textBold(String value, float sizeSp, int color) {
        TextView v = text(value, sizeSp, color);
        v.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return v;
    }

    /** 18sp bold, textPrimary. */
    public TextView title(String value) {
        return textBold(value, 18f, AppTheme.colors().textPrimary);
    }

    /**
     * 14sp bold textSecondary section label with a 3dp x 14dp rounded accent bar on the left
     * (implemented as the left compound drawable so the frozen TextView return type is kept).
     * Carries its own padding: 16dp top, 8dp bottom.
     */
    public TextView sectionHeader(String value) {
        Colors c = AppTheme.colors();
        TextView v = textBold(value, 14f, c.textSecondary);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(0, dp(16f), 0, dp(8f));
        GradientDrawable bar = bg(c.accent, 2f);
        bar.setSize(dp(3f), dp(14f));
        v.setCompoundDrawablesWithIntrinsicBounds(bar, null, null, null);
        v.setCompoundDrawablePadding(dp(8f));
        return v;
    }

    /** 13sp textSecondary. */
    public TextView body(String value) {
        return text(value, 13f, AppTheme.colors().textSecondary);
    }

    /** 11sp textTertiary. */
    public TextView caption(String value) {
        return text(value, 11f, AppTheme.colors().textTertiary);
    }

    /** 20sp bold textPrimary. */
    public TextView metricValue(String value) {
        return textBold(value, 20f, AppTheme.colors().textPrimary);
    }

    /** Small capsule label. */
    public TextView badge(String value, int bgColor, int textColor) {
        TextView v = textBold(value, 11f, textColor);
        v.setGravity(Gravity.CENTER);
        v.setBackground(bg(bgColor, RADIUS_PILL));
        v.setPadding(dp(8f), dp(3f), dp(8f), dp(3f));
        return v;
    }

    // ------------------------------------------------------------------ buttons

    /** 46dp tall, 14dp radius, centered, bold 14sp, ripple press state. */
    public TextView action(String label, int bgColor, int textColor) {
        TextView v = textBold(label, 14f, textColor);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(46f));
        v.setPadding(dp(16f), dp(12f), dp(16f), dp(12f));
        v.setBackground(ripple(bgColor, RADIUS_INNER));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** surface2 fill + textPrimary. */
    public TextView actionGhost(String label) {
        Colors c = AppTheme.colors();
        return action(label, c.surface2, c.textPrimary);
    }

    /** Transparent fill, stroke outline, textSecondary. */
    public TextView actionOutline(String label) {
        Colors c = AppTheme.colors();
        TextView v = textBold(label, 14f, c.textSecondary);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(46f));
        v.setPadding(dp(16f), dp(12f), dp(16f), dp(12f));
        Drawable outline = bg(Color.TRANSPARENT, RADIUS_INNER, c.stroke, dpf(1f));
        v.setBackground(rippleOver(outline, RADIUS_INNER));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** Capsule chip: selected = accent fill + bgRoot text, else surface2 + textSecondary. */
    public TextView chip(String label, boolean selected) {
        TextView v = textBold(label, 12f, AppTheme.colors().textPrimary);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(32f));
        v.setPadding(dp(14f), dp(7f), dp(14f), dp(7f));
        v.setClickable(true);
        v.setFocusable(true);
        styleChip(v, selected);
        return v;
    }

    public void styleChip(TextView chip, boolean selected) {
        if (chip == null) {
            return;
        }
        Colors c = AppTheme.colors();
        chip.setBackground(pill(selected, c.accent));
        chip.setTextColor(selected ? c.bgRoot : c.textSecondary);
    }

    /**
     * One segment of a 40dp segmented control, uniform 14dp radius. Use
     * {@link #segment(String, boolean, int, int)} when the group must read as one connected
     * control (outer corners 14dp, inner corners 4dp).
     */
    public TextView segment(String label, boolean selected) {
        return segment(label, selected, -1, -1);
    }

    /**
     * Segment with its position inside the group. {@code index}/{@code count} only affect the
     * corner radii - pass -1/-1 for the uniform variant.
     */
    public TextView segment(String label, boolean selected, int index, int count) {
        TextView v = textBold(label, 13f, AppTheme.colors().textPrimary);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(40f));
        v.setPadding(dp(14f), dp(10f), dp(14f), dp(10f));
        v.setClickable(true);
        v.setFocusable(true);
        v.setTag(new SegmentSlot(index, count));
        styleSegment(v, selected);
        return v;
    }

    /** Re-applies the selected/unselected look in place (keeps the segmented-group corners). */
    public void styleSegment(TextView segment, boolean selected) {
        if (segment == null) {
            return;
        }
        Colors c = AppTheme.colors();
        int index = -1;
        int count = -1;
        Object tag = segment.getTag();
        if (tag instanceof SegmentSlot) {
            index = ((SegmentSlot) tag).index;
            count = ((SegmentSlot) tag).count;
        }
        segment.setBackground(segmentBg(selected, index, count));
        segment.setTextColor(selected ? c.bgRoot : c.textSecondary);
    }

    // ------------------------------------------------------------------ layout

    /** 1dp hairline divider, MATCH_PARENT wide by default. */
    public View divider() {
        View v = new View(this);
        v.setBackgroundColor(AppTheme.colors().stroke);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)));
        return v;
    }

    /** Fixed-size spacer; a non-positive axis means "wrap content" in that axis. */
    public View space(float wDp, float hDp) {
        View v = new View(this);
        int w = wDp > 0f ? dp(wDp) : ViewGroup.LayoutParams.WRAP_CONTENT;
        int h = hDp > 0f ? dp(hDp) : ViewGroup.LayoutParams.WRAP_CONTENT;
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    /** Horizontal container, children vertically centered. */
    public LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    /** Vertical container. */
    public LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    public FrameLayout frame() {
        return new FrameLayout(this);
    }

    /** Vertical card container with the card background and 16/14 padding (MATCH_PARENT wide). */
    public LinearLayout card(Context ctx) {
        return cardContainer(ctx != null ? ctx : this);
    }

    /** Same as {@link #card(Context)} bound to this UiKit context. */
    public LinearLayout cardLinear() {
        return cardContainer(this);
    }

    /** Scrollable page body. */
    public ScrollView scroller(View content, int bgColor) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.setBackgroundColor(bgColor);
        if (content != null) {
            s.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return s;
    }

    /** 6dp tall rounded progress rail: surface3 track + colored fill. */
    public ProgressBar linearProgress(int color) {
        Colors c = AppTheme.colors();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(100);
        bar.setProgress(0);
        // Custom layered rail: the token colors live in the drawable itself, so no tint list
        // is applied here (a tint would recolor the track layer as well).
        GradientDrawable track = bg(c.surface3, 3f);
        GradientDrawable fill = bg(color, 3f);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)));
        bar.setMinimumHeight(dp(6f));
        return bar;
    }

    /** 40dp tall seek bar: accent progress + accent thumb, no split track, inset so it never hugs the edges. */
    public SeekBar seekBar(int color) {
        Colors c = AppTheme.colors();
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(0);
        sb.setProgressTintList(tint(color));
        sb.setProgressBackgroundTintList(tint(c.surface3));
        sb.setThumbTintList(tint(color));
        sb.setSplitTrack(false);
        sb.setPadding(dp(10f), 0, dp(10f), 0);
        sb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)));
        return sb;
    }

    /** Platform Switch with accent thumb/track via tint lists only - no custom drawables. */
    public Switch toggle(boolean checked) {
        Colors c = AppTheme.colors();
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setShowText(false);
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{c.accent, c.surface4}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{c.accentDim, c.surface3}));
        sw.setMinimumWidth(dp(48f));
        sw.setMinHeight(dp(32f));
        sw.setGravity(Gravity.CENTER_VERTICAL);
        return sw;
    }

    /**
     * 56dp minimum-height settings row: title 14sp textPrimary over an optional subtitle 12sp
     * textSecondary, with {@code trailing} pinned right.
     *
     * <p>The row intentionally installs no click listener and never marks itself clickable: when
     * {@code trailing} is a Switch the switch keeps the whole touch target, and in every other
     * case the caller decides by calling {@code setOnClickListener(...)} on the returned
     * container (which also activates the built-in ripple).
     */
    public LinearLayout settingRow(String title, String subtitle, View trailing) {
        Colors c = AppTheme.colors();
        LinearLayout r = row();
        r.setMinimumHeight(dp(56f));
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16f), dp(10f), dp(14f), dp(10f));
        r.setBackground(ripple(Color.TRANSPARENT, RADIUS_INNER));

        LinearLayout col = column();
        col.addView(text(title == null ? "" : title, 14f, c.textPrimary));
        if (subtitle != null && subtitle.length() > 0) {
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2f);
            col.addView(text(subtitle, 12f, c.textSecondary), subLp);
        }
        r.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12f);
            r.addView(trailing, lp);
        }
        return r;
    }

    /** Settings row whose trailing is right-aligned 13sp bold accent text. */
    public LinearLayout settingRowText(String title, String subtitle, String valueText) {
        LinearLayout r = settingRow(title, subtitle, null);
        TextView value = textBold(valueText == null ? "" : valueText, 13f, AppTheme.colors().accent);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12f);
        r.addView(value, lp);
        return r;
    }

    // ------------------------------------------------------------------ internals

    private LinearLayout cardContainer(Context ctx) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(card());
        v.setPadding(dp(16f), dp(14f), dp(16f), dp(14f));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private RippleDrawable rippleOver(Drawable content, float radiusDp) {
        GradientDrawable mask = bg(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(pressColor()), content, mask);
    }

    private RippleDrawable rippleOver(Drawable content, float radiusDp, int baseColor) {
        GradientDrawable mask = bg(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(pressColorFor(baseColor)), content, mask);
    }

    /** Accent at 12% alpha - the standard press feedback color on neutral surfaces. */
    private int pressColor() {
        return AppTheme.withAlpha(AppTheme.colors().accent, 0.12f);
    }

    /**
     * Press overlay colour, chosen from the colour it sits on.
     *
     * A single accent-tinted overlay is nearly invisible on the accent-filled primary
     * buttons (accent over accent), which makes those taps feel unresponsive. Filled
     * buttons therefore get a light wash instead, so every control shows a clear press.
     * Purely a press-state change: no layout or behaviour is affected.
     */
    private int pressColorFor(int baseColor) {
        Colors c = AppTheme.colors();
        if (baseColor == c.accent || baseColor == c.accentDim) {
            return AppTheme.withAlpha(c.bgRoot, AppTheme.isLight() ? 0.16f : 0.28f);
        }
        if (baseColor == c.surface3 || baseColor == c.surface4) {
            return AppTheme.withAlpha(c.textPrimary, 0.10f);
        }
        return pressColor();
    }

    private GradientDrawable segmentBg(boolean selected, int index, int count) {
        Colors c = AppTheme.colors();
        GradientDrawable d = bg(selected ? c.accent : c.surface2, RADIUS_INNER);
        if (count > 1 && index >= 0 && index < count) {
            boolean first = index == 0;
            boolean last = index == count - 1;
            float inner = 4f;
            float tl = first ? RADIUS_INNER : inner;
            float tr = last ? RADIUS_INNER : inner;
            float br = last ? RADIUS_INNER : inner;
            float bl = first ? RADIUS_INNER : inner;
            d.setCornerRadii(new float[]{
                    dpf(tl), dpf(tl), dpf(tr), dpf(tr),
                    dpf(br), dpf(br), dpf(bl), dpf(bl)});
        }
        return d;
    }

    /** Corner hint stored on a segment so {@link #styleSegment(TextView, boolean)} can restyle it. */
    private static final class SegmentSlot {
        final int index;
        final int count;

        SegmentSlot(int index, int count) {
            this.index = index;
            this.count = count;
        }
    }
}
