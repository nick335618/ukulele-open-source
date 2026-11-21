package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import com.ukulele.monitor.fun.ChordLibrary;
import com.ukulele.monitor.fun.TunerMath;

/**
 * 和弦指法图：4 根竖弦（左→右 G / C / E / A）、横向品位、弦枕、按弦圆点与品位数字、和弦名。
 *
 * <p>纯 Canvas 绘制，尺寸自适应（按控件高宽分配弦距与品位行高），颜色取自
 * {@link AppTheme#colors()}。{@link #onDraw(Canvas)} 不创建新对象：品位数字、弦名等文案都在
 * {@link #setChord(ChordLibrary.Chord)} 中预生成。
 *
 * <p>数据容错：和弦为 null、frets 长度不足、品位越界时退化为「未选择」占位，不抛异常。
 */
public final class ChordDiagramView extends View {

    private static final String EMPTY = "选择一个和弦，查看指法";
    private static final String NO_ROOM = "空间不足";
    private static final String EMPTY_TITLE = "未选择和弦";
    private static final String LEGEND = "○ 空弦    × 闷弦";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    /** 每根弦的显示品位数（1 起，0 表示不按）；与 4 根弦一一对应。 */
    private final int[] fretRows = new int[4];
    /** 圆点内数字（显示品位），未按弦为 null。 */
    private final String[] fretLabels = new String[4];
    private final boolean[] muted = new boolean[4];
    private final boolean[] open = new boolean[4];

    private final float pad;
    private final float density;
    private final float nameText, stringLabelText, baseFretText, dotText, legendText;
    private final float cornerRadius, nutStroke, fretStroke, stringStroke;
    private final float dotRadiusMax, openRadius, muteArm;

    private boolean hasChord;
    private boolean nutAtTop = true;
    private int displayRows = 4;
    private String chordName = EMPTY_TITLE;
    private String baseFretLabel = "";

    public ChordDiagramView(Context context) {
        super(context);
        float resolvedDensity = 1f;
        try {
            resolvedDensity = getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            // 1x 兜底。
        }
        density = resolvedDensity;
        pad = 14f * density;
        nameText = 16f * density;
        stringLabelText = 12f * density;
        baseFretText = 9f * density;
        dotText = 9f * density;
        legendText = 9f * density;
        cornerRadius = 14f * density;
        nutStroke = 3.5f * density;
        fretStroke = 1f * density;
        stringStroke = 1.2f * density;
        dotRadiusMax = 11f * density;
        openRadius = 3.6f * density;
        muteArm = 3.6f * density;
        boldPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setContentDescription("和弦指法图：未选择和弦");
        setFocusable(false);
    }

    /**
     * 设置当前和弦；传 null 显示「选择和弦」占位。
     *
     * <p>{@code frets} 顺序固定 {G, C, E, A}：-1 闷弦、0 空弦、n 品位。
     */
    public void setChord(ChordLibrary.Chord chord) {
        boolean valid = chord != null && chord.frets != null && chord.frets.length >= 4;
        if (!valid) {
            hasChord = false;
            chordName = EMPTY_TITLE;
            baseFretLabel = "";
            nutAtTop = true;
            displayRows = 4;
            for (int i = 0; i < 4; i++) {
                fretRows[i] = 0;
                fretLabels[i] = null;
                muted[i] = false;
                open[i] = false;
            }
            setContentDescription("和弦指法图：未选择和弦");
            invalidate();
            return;
        }

        boolean nut = chord.baseFret <= 1;
        int base = nut ? 1 : chord.baseFret;
        int maxRow = 0;
        for (int i = 0; i < 4; i++) {
            int fret = chord.frets[i];
            muted[i] = fret < 0;
            open[i] = fret == 0;
            fretRows[i] = 0;
            fretLabels[i] = null;
            if (fret > 0) {
                // 相对显示行：base 品位于第一行；数据异常时夹紧到 1..9 保证图能画出来。
                int row = fret - base + 1;
                if (row < 1) {
                    row = 1;
                }
                if (row > 9) {
                    row = 9;
                }
                fretRows[i] = row;
                fretLabels[i] = Integer.toString(row);
                if (row > maxRow) {
                    maxRow = row;
                }
            }
        }
        displayRows = Math.max(4, maxRow);
        nutAtTop = nut;
        baseFretLabel = nut ? "" : (base + " 品起");
        String name = chord.displayName;
        if (name == null || name.length() == 0) {
            name = chord.name;
        }
        chordName = (name == null || name.length() == 0) ? EMPTY_TITLE : name;
        hasChord = true;
        setContentDescription("和弦指法图：" + chordName);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultWidth = (int) (160f * density) + 1;
        int defaultHeight = (int) (200f * density) + 1;
        setMeasuredDimension(resolveSize(defaultWidth, widthMeasureSpec),
                resolveSize(defaultHeight, heightMeasureSpec));
    }

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
        try {
            save = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(0f, 0f, width, height, cornerRadius, cornerRadius,
                    Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawColor(theme.surface);
            drawFrame(canvas, theme, width, height);
        } catch (Throwable ignored) {
            // 自绘容错：坏数据 / 极端尺寸只影响本帧内容。
        } finally {
            if (save >= 0) {
                canvas.restoreToCount(save);
            }
        }
    }

    private void drawFrame(Canvas canvas, AppTheme.Colors theme, int width, int height) {
        // 自适应：卡片实际尺寸可能只有 104x124dp，按最短边整体缩放几何与字号。
        float scale = Math.min(1f, Math.min(width / (160f * density), height / (200f * density)));
        if (scale < 0.62f) {
            scale = 0.62f;
        }
        float margin = Math.max(6f * density, pad * scale);
        float nameSize = Math.max(11f * density, nameText * scale);
        float labelSize = Math.max(8f * density, stringLabelText * scale);
        float baseSize = Math.max(7f * density, baseFretText * scale);
        float symbolRadius = Math.max(2.6f * density, openRadius * scale);
        float muteLength = Math.max(2.6f * density, muteArm * scale);
        float dotMax = Math.max(6f * density, dotRadiusMax * scale);
        float dotTextSize = Math.max(7f * density, dotText * scale);

        if (!hasChord) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(nameSize * 0.9f);
            textPaint.setColor(theme.textSecondary);
            canvas.drawText(EMPTY, width * 0.5f, height * 0.5f, textPaint);
            return;
        }

        // ---- 和弦名（左上角；过宽时逐步缩小字号，绝不溢出） ----
        boldPaint.setStyle(Paint.Style.FILL);
        boldPaint.setTextAlign(Paint.Align.LEFT);
        float maxNameWidth = width - margin * 2f;
        boldPaint.setTextSize(nameSize);
        while (nameSize > 10f * density && boldPaint.measureText(chordName) > maxNameWidth) {
            nameSize -= 0.5f * density;
            boldPaint.setTextSize(nameSize);
        }
        boldPaint.setColor(theme.textPrimary);
        float nameBaseline = margin + nameSize;
        canvas.drawText(chordName, margin, nameBaseline, boldPaint);

        // ---- 纵向布局：弦名 / 空弦闷弦符号 / 品位格 ----
        float labelBaseline = nameBaseline + Math.max(10f * density, 15f * density * scale);
        float symbolCenterY = labelBaseline + Math.max(8f * density, 12f * density * scale);
        float gridTop = symbolCenterY + Math.max(7f * density, 10f * density * scale);
        boolean showLegend = height >= 240f * density;
        float gridBottom = height - margin - (showLegend ? 14f * density : 0f);
        float gridHeight = gridBottom - gridTop;
        float left = margin + 5f * density;
        float right = width - margin;
        float spacing = (right - left) / 3f;
        if (width < 72f * density || height < 96f * density
                || gridHeight < 30f * density || spacing < 12f * density) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(nameSize * 0.9f);
            textPaint.setColor(theme.textSecondary);
            canvas.drawText(NO_ROOM, width * 0.5f, height * 0.5f, textPaint);
            return;
        }
        int rows = displayRows;
        float rowHeight = gridHeight / rows;
        float dotRadius = Math.min(dotMax, Math.min(spacing, rowHeight) * 0.32f);

        // ---- 弦名 G / C / E / A（与物理列顺序一致） ----
        boldPaint.setTextAlign(Paint.Align.CENTER);
        boldPaint.setTextSize(labelSize);
        boldPaint.setColor(theme.textPrimary);
        for (int i = 0; i < 4; i++) {
            float stringX = left + i * spacing;
            canvas.drawText(TunerMath.STRING_NAMES[i], stringX, labelBaseline, boldPaint);
        }

        // ---- 空弦 ○ / 闷弦 × ----
        for (int i = 0; i < 4; i++) {
            float stringX = left + i * spacing;
            if (open[i]) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.3f * density);
                paint.setColor(theme.textSecondary);
                canvas.drawCircle(stringX, symbolCenterY, symbolRadius, paint);
            } else if (muted[i]) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(theme.danger);
                canvas.drawLine(stringX - muteLength, symbolCenterY - muteLength,
                        stringX + muteLength, symbolCenterY + muteLength, paint);
                canvas.drawLine(stringX - muteLength, symbolCenterY + muteLength,
                        stringX + muteLength, symbolCenterY - muteLength, paint);
            }
        }

        // ---- 品位横线 / 弦枕 ----
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int row = 1; row <= rows; row++) {
            float y = gridTop + row * rowHeight;
            paint.setStrokeWidth(fretStroke);
            paint.setColor(theme.textTertiary);
            canvas.drawLine(left, y, right, y, paint);
        }
        if (nutAtTop) {
            // 开放把位：顶部是弦枕，加粗表示。
            paint.setStrokeWidth(nutStroke);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setColor(theme.textPrimary);
            canvas.drawLine(left, gridTop, right, gridTop, paint);
        } else {
            paint.setStrokeWidth(fretStroke);
            paint.setColor(theme.textTertiary);
            canvas.drawLine(left, gridTop, right, gridTop, paint);
            // 非开放把位：在左侧标出起始品位。
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setTextSize(baseSize);
            textPaint.setColor(theme.textSecondary);
            canvas.drawText(baseFretLabel, left - 4f * density,
                    gridTop + rowHeight * 0.5f + baseSize * 0.35f, textPaint);
        }

        // ---- 4 根竖弦 ----
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stringStroke);
        paint.setColor(theme.textSecondary);
        for (int i = 0; i < 4; i++) {
            float stringX = left + i * spacing;
            canvas.drawLine(stringX, gridTop, stringX, gridBottom, paint);
        }

        // ---- 按弦圆点 + 品位数字 ----
        for (int i = 0; i < 4; i++) {
            int row = fretRows[i];
            if (row <= 0) {
                continue;
            }
            float dotY = gridTop + (row - 0.5f) * rowHeight;
            if (dotY > gridBottom) {
                continue;
            }
            float dotX = left + i * spacing;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(theme.accent);
            canvas.drawCircle(dotX, dotY, dotRadius, paint);
            String label = fretLabels[i];
            if (label != null && dotRadius >= 4f * density) {
                textPaint.setStyle(Paint.Style.FILL);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setTextSize(Math.min(dotTextSize * 1.15f, dotRadius * 1.5f));
                textPaint.setColor(theme.bgRoot);
                canvas.drawText(label, dotX, dotY + dotRadius * 0.36f, textPaint);
            }
        }

        // ---- 图例（空间足够时才画） ----
        if (showLegend) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(legendText);
            textPaint.setColor(theme.textTertiary);
            canvas.drawText(LEGEND, width * 0.5f, height - 6f * density, textPaint);
        }
    }
}
