package com.ukulele.monitor.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * Global palette holder for Ukulele Studio.
 *
 * <p>Frozen contract: {@code docs/团队简报与接口契约.md} section 2.1. Every view pulls its
 * colors from {@link #colors()}; nothing else may hardcode a palette value.
 *
 * <p>The hex values below are the single source of truth for Java code and MUST stay in sync
 * with {@code res/values/colors.xml} (which carries the same tokens for XML resources).
 * They are duplicated on purpose: the palette has to be readable without a Context.
 */
public final class AppTheme {

    /** Immutable palette. Field names are frozen - custom views resolve them by name. */
    public static final class Colors {
        public final int bgRoot, surface, surface2, surface3, surface4;
        public final int stroke, strokeStrong;
        public final int textPrimary, textSecondary, textTertiary;
        public final int accent, accentDim, accentSoft;
        public final int warn, danger, success, ledOn, ledOff;

        public Colors(int bgRoot, int surface, int surface2, int surface3, int surface4,
                      int stroke, int strokeStrong,
                      int textPrimary, int textSecondary, int textTertiary,
                      int accent, int accentDim, int accentSoft,
                      int warn, int danger, int success, int ledOn, int ledOff) {
            this.bgRoot = bgRoot;
            this.surface = surface;
            this.surface2 = surface2;
            this.surface3 = surface3;
            this.surface4 = surface4;
            this.stroke = stroke;
            this.strokeStrong = strokeStrong;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textTertiary = textTertiary;
            this.accent = accent;
            this.accentDim = accentDim;
            this.accentSoft = accentSoft;
            this.warn = warn;
            this.danger = danger;
            this.success = success;
            this.ledOn = ledOn;
            this.ledOff = ledOff;
        }
    }

    /** Dark palette - the app default. */
    private static final Colors DARK = new Colors(
            0xFF070D18, 0xFF101A2C, 0xFF17263C, 0xFF1F3149, 0xFF27405F,
            0xFF22344F, 0xFF2E4662,
            0xFFF1F6FC, 0xFFA7BAD3, 0xFF6E86A6,
            0xFF36DAD4, 0xFF0E4F58, 0xFF123C44,
            0xFFFFC152, 0xFFFF7070, 0xFF36EBA6, 0xFFFFC152, 0xFF2A3D57);

    /**
     * Light palette - identical semantics, "_light" values from colors.xml.
     *
     * accent/led_on/led_off/text_tertiary were darkened against colors.xml's first
     * draft so that graphical elements clear the WCAG 3:1 bar and small text clears
     * 4.5:1 on the light surfaces (see docs/UI优化验证报告.md contrast table).
     */
    private static final Colors LIGHT = new Colors(
            0xFFF4F7FB, 0xFFFFFFFF, 0xFFEDF2F8, 0xFFE2EAF4, 0xFFD6E0EC,
            0xFFD6E0EC, 0xFFC3D0E0,
            0xFF0C1726, 0xFF4C6180, 0xFF6E86A6,
            0xFF0B807B, 0xFFA9E6E2, 0xFFDCF5F3,
            0xFFB4761A, 0xFFC4342F, 0xFF12855A, 0xFFA66A00, 0xFFA9BACD);

    public enum Style {
        DARK_MODE("dark", "深海", DARK, 1f),
        LIGHT_MODE("light", "晨白", LIGHT, 1f),
        SYSTEM("system", "跟随系统", LIGHT, 1f),
        CARTOON("cartoon", "橘子汽水", palette(0xfffff6df, 0xfffffbef, 0xffa9440b, false), 1.32f),
        ANIME("anime", "动漫 · 紫藤", palette(0xfff3eeff, 0xfffcf8ff, 0xff693da0, false), 1.12f),
        CYBER("cyber", "赛博 · 夜行", palette(0xff100a21, 0xff1f1034, 0xff53f8dc, true), .42f),
        MECHANICAL("mechanical", "机械 · 钨钢", palette(0xff141a20, 0xff212b33, 0xfff8bd55, true), .58f),
        PINK("pink", "粉色 · 樱雾", palette(0xffffedf4, 0xfffff8fc, 0xff983561, false), 1.24f),
        RED("red", "红色 · 赤霞", palette(0xfffaebe8, 0xfffff6f4, 0xffa32c33, false), .92f),
        BLUE("blue", "蓝色 · 潮汐", palette(0xffe9f3ff, 0xfff5faff, 0xff235caa, false), 1.05f);

        public final String id, label;
        public final Colors preview;
        final float corners;

        Style(String id, String label, Colors preview, float corners) {
            this.id=id; this.label=label; this.preview=preview; this.corners=corners;
        }

        public static Style fromId(String id) {
            for (Style style : values()) if (style.id.equals(id)) return style;
            return DARK_MODE;
        }
    }

    private static Colors palette(int root, int panel, int accent, boolean dark) {
        Colors base = dark ? DARK : LIGHT;
        return new Colors(root, panel, blend(panel,accent,.065f), blend(panel,accent,.13f),
                blend(panel,accent,.18f), blend(panel,accent,.19f), blend(panel,accent,.29f),
                base.textPrimary, base.textSecondary, base.textSecondary, accent,
                blend(panel,accent,.31f), blend(panel,accent,.17f),
                base.warn, base.danger, base.success, base.ledOn, base.ledOff);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a)*(1-t)+Color.red(b)*t),
                Math.round(Color.green(a)*(1-t)+Color.green(b)*t),
                Math.round(Color.blue(a)*(1-t)+Color.blue(b)*t));
    }

    private static float corners = 1f;
    private static Style activeStyle;

    public static void applyStyle(Style style, boolean systemDark) {
        sColors = style == Style.SYSTEM && systemDark ? DARK : style.preview;
        corners = style.corners;
        activeStyle=style;
    }

    public static float cornerScale() { return corners; }
    public static Style style() { return activeStyle==null?Style.DARK_MODE:activeStyle; }

    /** Never null: starts dark, per contract. */
    private static volatile Colors sColors = DARK;

    private AppTheme() {
    }

    /** Switch the active palette to the dark set. */
    public static void dark() {
        sColors = DARK; corners = 1f; activeStyle=Style.DARK_MODE;
    }

    /** Switch the active palette to the light set. */
    public static void light() {
        sColors = LIGHT; corners = 1f; activeStyle=Style.LIGHT_MODE;
    }

    /** Assign a palette directly. A null argument falls back to the dark set (never stores null). */
    public static void setColors(Colors c) {
        sColors = (c == null) ? DARK : c; corners = 1f; activeStyle=null;
    }

    /** Active palette. Never null. */
    public static Colors colors() {
        return sColors;
    }

    /** The canonical dark palette (handy for previews / persisting a choice). */
    public static Colors darkColors() {
        return DARK;
    }

    /** The canonical light palette. */
    public static Colors lightColors() {
        return LIGHT;
    }

    /** True when the active palette has a light root background. */
    public static boolean isLight() {
        return isLightPalette(sColors);
    }

    /** Returns {@code color} with the given alpha fraction (0..1) applied. */
    public static int withAlpha(int color, float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        int a = Math.round(255f * f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Applies the active palette to an Activity window: status bar, navigation bar and window
     * background, plus the light/dark status-bar icon flag. Safe to call repeatedly (e.g. after
     * a theme switch) and with a null Activity.
     */
    public static void applyWindow(Activity a) {
        if (a != null) applyWindow(a.getWindow());
    }

    /** Dialogs need the same system-bar contrast as the activity. */
    public static void applyWindow(Window window) {
        if (window == null) {
            return;
        }
        Colors c = colors();
        window.setStatusBarColor(c.bgRoot);
        window.setNavigationBarColor(c.bgRoot);
        window.setBackgroundDrawable(new ColorDrawable(c.bgRoot));

        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
        boolean lightUi = isLightPalette(c);
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (lightUi) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (lightUi) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    private static boolean isLightPalette(Colors c) {
        // Perceived luminance of the sRGB root color: robust for arbitrary custom palettes,
        // so applyWindow() also works after setColors() with a non-standard palette.
        double r = Color.red(c.bgRoot) / 255.0;
        double g = Color.green(c.bgRoot) / 255.0;
        double b = Color.blue(c.bgRoot) / 255.0;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) > 0.5;
    }
}
