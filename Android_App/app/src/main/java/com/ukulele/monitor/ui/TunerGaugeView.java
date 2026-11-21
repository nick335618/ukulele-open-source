package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.ukulele.monitor.fun.TunerMath;

import java.util.Locale;

/**
 * 调音表：半圆音分刻度盘 + 平滑指针 + 四弦目标频率 + 实时 Hz / 状态。
 *
 * <p>纯 Canvas 绘制，颜色全部取自 {@link AppTheme#colors()}，深浅主题共用同一套几何。
 *
 * <p><b>性能约定</b>：{@link #onDraw(Canvas)} 内不创建任何新对象（Paint/Path/RectF 全部是
 * 字段，文案在 {@link #setFrequency(double, boolean)} 里格式化好缓存），只有「指针过渡」或
 * 「空闲呼吸」需要动画时才 {@code postInvalidateOnAnimation()}；数据静止时不再自刷新。
 *
 * <p>对外 API 只有 {@link #TunerGaugeView(Context)}、{@link #setFrequency(double, boolean)}、
 * {@link #setIdle(boolean)}，都由主线程调用。
 */
public final class TunerGaugeView extends View {

    private static final String TITLE = "调音表 · 实时音高";
    private static final String WAITING = "等待设备信号";
    private static final String IDLE_HZ = "—";
    private static final String UNIT_HZ = "Hz";
    private static final String[] TARGET_TEXT = {"392.00", "261.63", "329.63", "440.00"};
    private static final String[] TICK_LABELS = {"-50", "0", "+50"};

    /** 空闲呼吸的角速度：一个来回 2.2 秒。 */
    private static final float BREATH_PERIOD_MS = 2200f;
    /** 指针时间常数：越小越跟手，越大越平滑。 */
    private static final float NEEDLE_TIME_MS = 75f;

    // ---------------------------------------------------------------- 绘制资源（只创建一次）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();

    private final float pad;
    private final float density;
    private final float titleBaseline;
    private final float textTitle, textHz, textUnit, textCents, textStatus;
    private final float textString, textTarget, textTick;
    private final float chipRadius;
    private final float arcStroke, zoneStroke, tickWidth, needleWidth;
    private final float pivotRadius, pivotCoreRadius;
    private final float cornerRadius;

    // ---------------------------------------------------------------- 状态

    private boolean attached;
    private boolean idle = true;
    private boolean hasSignal;
    private double lastHz;
    private double liveCents;
    private int nearestIndex = -1;
    private float targetFraction;
    private float displayFraction;
    private float breathPhase;
    private long lastFrameMs;

    private String hzText = IDLE_HZ;
    private String centsText = "";
    private String statusText = WAITING;

    public TunerGaugeView(Context context) {
        super(context);
        float resolvedDensity = 1f;
        try {
            resolvedDensity = getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            // 极端情况下用 1x 兜底，绝不让构造抛异常。
        }
        density = resolvedDensity;
        pad = 14f * density;
        titleBaseline = pad + 10f * density;
        textTitle = 12f * density;
        textHz = 30f * density;
        textUnit = 12f * density;
        textCents = 11f * density;
        textStatus = 14f * density;
        textString = 12f * density;
        textTarget = 9f * density;
        textTick = 9f * density;
        chipRadius = 9f * density;
        arcStroke = 1.4f * density;
        zoneStroke = 5f * density;
        tickWidth = 1.2f * density;
        needleWidth = 2.6f * density;
        pivotRadius = 5f * density;
        pivotCoreRadius = 2.1f * density;
        cornerRadius = 14f * density;
        boldPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setContentDescription("调音表：实时音高、音分偏差与目标弦");
        setFocusable(false);
    }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 设置最新频率。
     *
     * @param hz    频率（Hz）；非法值（<=0、NaN、Inf）视为无信号
     * @param fresh 是否为本次新数据；false 表示数据过期/不可用，视图进入空闲呼吸
     */
    public void setFrequency(double hz, boolean fresh) {
        if (!fresh || !isValidFrequency(hz)) {
            enterIdle();
            invalidate();
            return;
        }
        applyFrequency(hz);
        invalidate();
    }

    /** 强制进入 / 退出空闲态。退出时若存在最近一次有效频率，则恢复到该读数。 */
    public void setIdle(boolean value) {
        if (!value && hasSignal && isValidFrequency(lastHz)) {
            applyFrequency(lastHz);
        } else {
            enterIdle();
        }
        invalidate();
    }

    // ---------------------------------------------------------------- 数据

    private void applyFrequency(double hz) {
        int index = TunerMath.nearestString(hz);
        if (index < 0) {
            enterIdle();
            return;
        }
        hasSignal = true;
        lastHz = hz;
        idle = false;
        nearestIndex = index;
        liveCents = TunerMath.cents(hz, TunerMath.TARGET_HZ[index]);
        targetFraction = (float) TunerMath.needleFraction(liveCents);
        if (Float.isNaN(targetFraction) || Float.isInfinite(targetFraction)) {
            targetFraction = 0f;
        }
        hzText = formatHz(hz);
        centsText = formatCents(liveCents);
        statusText = TunerMath.statusLabel(liveCents);
    }

    private void enterIdle() {
        idle = true;
        nearestIndex = -1;
        targetFraction = 0f;
        hzText = IDLE_HZ;
        centsText = "";
        statusText = WAITING;
    }

    private static boolean isValidFrequency(double hz) {
        return hz > 0.0 && !Double.isNaN(hz) && !Double.isInfinite(hz);
    }

    private static String formatHz(double hz) {
        try {
            return String.format(Locale.ROOT, hz >= 1000.0 ? "%.0f" : "%.2f", hz);
        } catch (Throwable ignored) {
            return IDLE_HZ;
        }
    }

    private static String formatCents(double cents) {
        try {
            return String.format(Locale.ROOT, "%+.1f 音分", cents);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 生命周期 / 尺寸

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrameMs = 0L;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        // 停止一切自刷新：onDraw 只在 attached 时才继续 postInvalidateOnAnimation。
        attached = false;
        lastFrameMs = 0L;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultWidth = (int) (320f * density) + 1;
        int defaultHeight = (int) (220f * density) + 1;
        setMeasuredDimension(resolveSize(defaultWidth, widthMeasureSpec),
                resolveSize(defaultHeight, heightMeasureSpec));
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        AppTheme.Colors theme = AppTheme.colors();
        if (theme == null) {
            return;
        }
        int save = -1;
        boolean animate = false;
        try {
            save = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(0f, 0f, width, height, cornerRadius, cornerRadius,
                    Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawColor(theme.surface);
            animate = drawFrame(canvas, theme, width, height);
        } catch (Throwable ignored) {
            // 自绘视图容错：一帧画错也不能把主线程带崩。
        } finally {
            if (save >= 0) {
                canvas.restoreToCount(save);
            }
        }
        if (animate && attached && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    /** 绘制一帧；返回 true 表示下一帧还需要重绘（指针过渡或空闲呼吸）。 */
    private boolean drawFrame(Canvas canvas, AppTheme.Colors theme, int width, int height) {
        long now = SystemClock.uptimeMillis();
        float deltaMs = lastFrameMs == 0L ? 16f : Math.min(64f, (float) (now - lastFrameMs));
        if (deltaMs <= 0f) {
            deltaMs = 16f;
        }
        lastFrameMs = now;

        // ---- 1. 目标指针位置：空闲时缓慢呼吸，有信号时平滑过渡到音分位置 ----
        boolean animate = false;
        if (idle) {
            breathPhase += deltaMs / BREATH_PERIOD_MS;
            if (breathPhase >= 1f) {
                breathPhase -= 1f;
            }
            targetFraction = (float) (Math.sin(breathPhase * (float) (Math.PI * 2.0)) * 0.035);
        }
        if (Float.isNaN(displayFraction) || Float.isInfinite(displayFraction)) {
            displayFraction = 0f;
        }
        if (Float.isNaN(targetFraction) || Float.isInfinite(targetFraction)) {
            targetFraction = 0f;
        }
        float difference = targetFraction - displayFraction;
        if (Math.abs(difference) > 0.0005f) {
            float step = Math.min(1f, deltaMs / NEEDLE_TIME_MS);
            displayFraction += difference * step;
            if (Math.abs(targetFraction - displayFraction) < 0.001f) {
                displayFraction = targetFraction;
            } else {
                animate = true;
            }
        } else {
            displayFraction = targetFraction;
        }
        if (idle) {
            animate = true;
        }

        // ---- 2. 布局：上方标题，中间半圆表盘，下方 Hz / 状态 / 四弦目标 ----
        float margin = pad;
        float rowTop = height - 44f * density;
        float rowBottom = rowTop + 30f * density;
        float statusBaseline = rowTop - 12f * density;
        float hzBaseline = statusBaseline - 30f * density;
        float pivotMaxY = hzBaseline - 34f * density;
        float gaugeTop = titleBaseline + 8f * density;
        float radiusX = width * 0.5f - 26f * density;
        float radiusY = pivotMaxY - gaugeTop;
        float radius = Math.min(radiusX, radiusY);
        boolean compact = width < 180f * density || height < 150f * density
                || radius < 38f * density;

        // ---- 3. 标题（两种模式下都画） ----
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(textTitle);
        textPaint.setColor(theme.textSecondary);
        canvas.drawText(TITLE, margin, titleBaseline, textPaint);

        if (compact) {
            drawCompact(canvas, theme, width, height);
            return animate;
        }

        float cx = width * 0.5f;
        float cy = gaugeTop + radius;

        // ---- 4. 半圆表盘：底弧 + ±5 / ±10 分区着色 ----
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(arcStroke);
        paint.setColor(theme.textTertiary);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(rect, 180f, 180f, false, paint);

        paint.setStrokeWidth(zoneStroke);
        // 角度映射：-50 音分在 180°，+50 音分在 360°，1 音分 = 1.8°。
        paint.setColor(AppTheme.withAlpha(theme.warn, 0.38f));
        canvas.drawArc(rect, 252f, 9f, false, paint);   // -10..-5 音分
        canvas.drawArc(rect, 279f, 9f, false, paint);   // +5..+10 音分
        paint.setColor(AppTheme.withAlpha(theme.success, 0.42f));
        canvas.drawArc(rect, 261f, 18f, false, paint);  // -5..+5 音分（准区）

        // ---- 5. 刻度：每 10 音分一格（共 11 格），-50 / 0 / +50 带数字 ----
        paint.setStrokeWidth(tickWidth);
        paint.setColor(theme.textSecondary);
        for (int i = 0; i <= 10; i++) {
            double angle = Math.toRadians(180.0 + i * 18.0);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            boolean major = i == 0 || i == 5 || i == 10;
            float inner = radius - (major ? 6f : 3f) * density;
            float outer = radius + (major ? 3f : 2f) * density;
            canvas.drawLine(cx + cos * inner, cy + sin * inner,
                    cx + cos * outer, cy + sin * outer, paint);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textTick);
        textPaint.setColor(theme.textSecondary);
        for (int i = 0; i <= 2; i++) {
            double angle = Math.toRadians(180.0 + i * 90.0);
            float labelRadius = radius + 11f * density;
            float lx = cx + (float) Math.cos(angle) * labelRadius;
            float ly = cy + (float) Math.sin(angle) * labelRadius;
            canvas.drawText(TICK_LABELS[i], lx, ly + textTick * 0.35f, textPaint);
        }

        // ---- 6. 指针：从圆心指向当前音分，颜色随「准 / 偏差」变化 ----
        int needleColor;
        if (idle) {
            needleColor = theme.textTertiary;
        } else if (Math.abs(liveCents) <= 5.0) {
            needleColor = theme.success;
        } else if (Math.abs(liveCents) <= 10.0) {
            needleColor = theme.warn;
        } else {
            needleColor = theme.danger;
        }
        double needleAngle = Math.toRadians(180.0 + (displayFraction + 1.0) * 90.0);
        float needleLength = radius * 0.92f;
        float tipX = cx + (float) Math.cos(needleAngle) * needleLength;
        float tipY = cy + (float) Math.sin(needleAngle) * needleLength;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(needleWidth);
        paint.setColor(needleColor);
        canvas.drawLine(cx, cy, tipX, tipY, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, pivotRadius, paint);
        paint.setColor(theme.surface);
        canvas.drawCircle(cx, cy, pivotCoreRadius, paint);

        // ---- 7. 大字 Hz（附 Hz 单位） ----
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(textHz);
        textPaint.setColor(idle ? theme.textTertiary : theme.textPrimary);
        float hzWidth = textPaint.measureText(hzText);
        float hzLeft = cx - hzWidth * 0.5f;
        canvas.drawText(hzText, hzLeft, hzBaseline, textPaint);
        if (!idle) {
            textPaint.setTextSize(textUnit);
            textPaint.setColor(theme.textSecondary);
            canvas.drawText(UNIT_HZ, hzLeft + hzWidth + 3f * density,
                    hzBaseline - 2f * density, textPaint);
        }

        // ---- 8. 状态 + 音分偏差（居中一行；太窄时省略音分） ----
        int statusColor = idle ? theme.textSecondary
                : (Math.abs(liveCents) <= TunerMath.IN_TUNE_CENTS ? theme.success : theme.warn);
        boldPaint.setTextSize(textStatus);
        boldPaint.setStyle(Paint.Style.FILL);
        float statusWidth = boldPaint.measureText(statusText);
        float centsWidth = 0f;
        textPaint.setTextSize(textCents);
        if (!idle && centsText.length() > 0) {
            centsWidth = textPaint.measureText(centsText);
        }
        float gap = centsWidth > 0f ? 8f * density : 0f;
        float totalWidth = statusWidth + gap + centsWidth;
        if (totalWidth > width - margin * 2f) {
            centsWidth = 0f;
            gap = 0f;
            totalWidth = statusWidth;
        }
        float startX = cx - totalWidth * 0.5f;
        boldPaint.setTextAlign(Paint.Align.LEFT);
        boldPaint.setColor(statusColor);
        canvas.drawText(statusText, startX, statusBaseline, boldPaint);
        if (centsWidth > 0f) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(theme.textSecondary);
            canvas.drawText(centsText, startX + statusWidth + gap, statusBaseline, textPaint);
        }

        // ---- 9. 四弦目标 G / C / E / A：最近弦高亮 ----
        float cellWidth = (width - margin * 2f) / 4f;
        for (int i = 0; i < 4; i++) {
            float left = margin + i * cellWidth + 2f * density;
            float right = margin + (i + 1) * cellWidth - 2f * density;
            rect.set(left, rowTop, right, rowBottom);
            boolean selected = !idle && i == nearestIndex;
            if (selected) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(theme.accent);
                canvas.drawRoundRect(rect, chipRadius, chipRadius, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f * density);
                paint.setColor(AppTheme.withAlpha(theme.textTertiary, 0.55f));
                canvas.drawRoundRect(rect, chipRadius, chipRadius, paint);
            }
            int nameColor = selected ? theme.bgRoot : theme.textPrimary;
            int targetColor = selected ? theme.bgRoot : theme.textSecondary;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(textString);
            textPaint.setColor(nameColor);
            float centerX = (left + right) * 0.5f;
            canvas.drawText(TunerMath.STRING_NAMES[i], centerX,
                    rowTop + 13f * density, textPaint);
            textPaint.setTextSize(textTarget);
            textPaint.setColor(targetColor);
            canvas.drawText(TARGET_TEXT[i], centerX, rowTop + 25f * density, textPaint);
        }
        return animate;
    }

    /** 尺寸过小时的降级绘制：只保留大字 Hz 与状态，绝不留白或抛异常。 */
    private void drawCompact(Canvas canvas, AppTheme.Colors theme, int width, int height) {
        float centerX = width * 0.5f;
        float centerY = height * 0.5f;
        float size = Math.min(26f * density, height * 0.30f);
        if (size < 12f * density) {
            size = 12f * density;
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(size);
        textPaint.setColor(idle ? theme.textTertiary : theme.textPrimary);
        canvas.drawText(hzText, centerX, centerY, textPaint);
        int statusColor = idle ? theme.textSecondary
                : (Math.abs(liveCents) <= TunerMath.IN_TUNE_CENTS ? theme.success : theme.warn);
        textPaint.setTextSize(12f * density);
        textPaint.setColor(statusColor);
        canvas.drawText(statusText, centerX, centerY + size * 0.5f + 16f * density, textPaint);
    }
}
