package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 波形 + 频谱可视化（wave[24] / spectrum[16] / peakHz / sampleRate）。
 *
 * <p>取值映射与旧 MainActivity.VisualizerView 逐位等价：
 * 波形为 24 点折线（x=(i-1)*w/23f → i*w/23f，mid=h*.27f，amp=h*.21f），
 * 频谱为 16 根柱（高 Math.min(h*.38f, spectrum[i]*h*.00135f)，gap=3dp，底部留 13dp）。
 * 视觉上升级为主题化配色、弱化网格、14dp 内圆角与两层描边的轻微发光。</p>
 */
public final class VisualizerView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] wave = new float[24];
    private final int[] spectrum = new int[16];
    private int peakHz;
    private int sampleRate;
    /** 数据到达后绘制；停止到达时不保留旧频谱。 */
    private boolean analyzing = false;
    private boolean spectrumFresh;
    private String standbyLabel="等待数据";
    public void setStandbyLabel(String label){standbyLabel=label;invalidate();}

    public VisualizerView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("音频波形与频谱可视化");
    }

    /** 每帧数据：波形 24 点、频谱 16 根、峰值频率、采样率。不隐式改动 analyzing 状态。 */
    public void setData(float[] wave, int[] spectrum, int peakHz, int sampleRate) {
        if (wave != null) { int n = Math.min(wave.length, this.wave.length); for (int i = 0; i < n; ++i) this.wave[i] = wave[i]; }
        if (spectrum != null) { int n = Math.min(spectrum.length, this.spectrum.length); for (int i = 0; i < n; ++i) this.spectrum[i] = spectrum[i]; }
        this.peakHz = peakHz; this.sampleRate = sampleRate;
        invalidate();
    }

    /** 控制等待数据/正常绘制。 */
    public void setAnalyzing(boolean analyzing) { this.analyzing = analyzing; invalidate(); }

    public void setSpectrumFresh(boolean fresh) { spectrumFresh = fresh; invalidate(); }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static int fade(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }

    private static double channel(int value) { double s = value / 255.0; return s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4); }

    private static double luminance(int color) { return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) + .0722 * channel(Color.blue(color)); }

    private static double ratio(int a, int b) { double la = luminance(a), lb = luminance(b), hi = Math.max(la, lb), lo = Math.min(la, lb); return (hi + .05) / (lo + .05); }

    private static boolean isDark(AppTheme.Colors c) { return luminance(c.bgRoot) < .35; }

    /** 小号标签：暗色用 textTertiary；浅色下 textTertiary 对白底仅 2.77:1，自动升为 textSecondary。 */
    private int labelColor(AppTheme.Colors c) { return ratio(c.textTertiary, c.surface) >= 3.5 ? c.textTertiary : c.textSecondary; }

    /** 浅色下 accent 对白底仅 2.91:1，改用同色系更深一档保证线条可读。 */
    private int strongAccent(AppTheme.Colors c) { return isDark(c) ? c.accent : 0xff0b7f7a; }

    @Override protected void onDraw(Canvas c) {
        AppTheme.Colors colors = AppTheme.colors();
        int w = getWidth(), h = getHeight();
        c.drawColor(colors.surface);

        // 频谱区底衬（卡片内层，圆角统一 14dp）
        p.setStyle(Paint.Style.FILL); p.setColor(colors.surface2); rect.set(0, h * .52f, w, h); c.drawRoundRect(rect, dp(14), dp(14), p);

        // 网格弱化：surface3 低对比
        p.setStrokeWidth(1); p.setStyle(Paint.Style.STROKE); p.setColor(colors.surface3);
        for (int i = 1; i < 5; ++i) c.drawLine(0, h * i / 5f, w, h * i / 5f, p);
        for (int i = 1; i < 8; ++i) c.drawLine(w * i / 8f, 0, w * i / 8f, h, p);

        int label = labelColor(colors);
        p.setTextAlign(Paint.Align.LEFT);
        if (analyzing) {
            // 波形：两层描边模拟轻微发光（宽而淡的底光 + 实线）
            int line = strongAccent(colors);
            float mid = h * .27f, amp = h * .21f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(6)); p.setColor(fade(line, 44));
            for (int i = 1; i < wave.length; ++i) c.drawLine((i - 1) * w / 23f, mid - wave[i - 1] * amp, i * w / 23f, mid - wave[i] * amp, p);
            p.setStrokeWidth(dp(2)); p.setColor(line);
            for (int i = 1; i < wave.length; ++i) c.drawLine((i - 1) * w / 23f, mid - wave[i - 1] * amp, i * w / 23f, mid - wave[i] * amp, p);
            p.setStyle(Paint.Style.FILL); p.setColor(label); p.setTextSize(dp(11)); c.drawText("WAVEFORM", dp(14), dp(20), p);

            // 频谱：16 根柱
            p.setColor(colors.success); float gap = dp(3); float barW = (w - gap * 17) / 16f;
            for (int i = 0; spectrumFresh && i < 16; ++i) { float bh = Math.min(h * .38f, spectrum[i] * h * .00135f); rect.set(gap + i * (barW + gap), h - dp(13) - bh, gap + i * (barW + gap) + barW, h - dp(13)); c.drawRoundRect(rect, dp(3), dp(3), p); }
            p.setColor(label); p.setTextSize(dp(11)); c.drawText("SPECTRUM", dp(14), h * .58f + dp(15), p); c.drawText((!spectrumFresh ? "等待频谱数据" : peakHz == 0 ? "低于显示底限 · Fs " + sampleRate + " Hz" : "峰值 " + peakHz + " Hz   ·   Fs " + sampleRate + " Hz"), dp(14), h - dp(9), p);
        } else {
            p.setStyle(Paint.Style.FILL); p.setColor(label); p.setTextSize(dp(11)); c.drawText("WAVEFORM", dp(14), dp(20), p);
            p.setColor(label); p.setTextSize(dp(12)); p.setTextAlign(Paint.Align.CENTER);
            c.drawText(standbyLabel, w / 2f, h * .5f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        // 卡片内框：14dp 圆角，与 LED 预览卡片风格一致
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(colors.stroke);
        rect.set(dp(.5f), dp(.5f), w - dp(.5f), h - dp(.5f)); c.drawRoundRect(rect, dp(14), dp(14), p);
    }
}
