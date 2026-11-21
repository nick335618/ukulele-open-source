package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * 彩纸庆祝覆盖层：{@link #burst()} 触发一次约 1.4 秒的彩纸爆发。
 *
 * <p>为什么是自绘 View：没有任何第三方动画/粒子库，而且庆祝层必须叠在现有页面之上且
 * 不拦截任何触摸。这里只画 90 片带重力、旋转、横向摆动的纸片，透明背景、不消费事件。
 *
 * <p>性能约定：所有数组与 RectF 都在构造时分配，onDraw 内只做数学运算；每帧用
 * postInvalidateOnAnimation 驱动，结束后不再自刷新（{@link #isRunning()} 变 false）。
 * 颜色用 {@link AppTheme#colors()} 的 accent/warn/success/danger/textPrimary 加少量白，
 * 每次 burst 时缓存进调色板，避免动画途中切主题导致颜色跳变。
 */
public final class ConfettiView extends View {

    private static final int COUNT = 90;
    private static final long DURATION_MS = 1400L;
    /** 最后 400ms 整体淡出，纸片不会突然消失。 */
    private static final long FADE_START_MS = 1000L;
    private static final int PALETTE_BRAND_COUNT = 5;
    private static final int PALETTE_SIZE = PALETTE_BRAND_COUNT + 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Random random = new Random();

    // 每片彩纸的状态：位置、速度、旋转、尺寸、摆动相位、调色板下标
    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] vx = new float[COUNT];
    private final float[] vy = new float[COUNT];
    private final float[] rotation = new float[COUNT];
    private final float[] spin = new float[COUNT];
    private final float[] halfWidth = new float[COUNT];
    private final float[] halfHeight = new float[COUNT];
    private final float[] swayPhase = new float[COUNT];
    private final int[] colorIndex = new int[COUNT];

    private final int[] palette = new int[PALETTE_SIZE];
    private final int[] faded = new int[PALETTE_SIZE];

    private boolean running;
    private boolean spawned;
    private boolean timeArmed;
    private long startAt;
    private long lastAt;
    private float density = 1f;

    public ConfettiView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        if (!(density > 0f)) {
            density = 1f;
        }
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setContentDescription("彩纸庆祝动画");
    }

    /** 主线程调用：开始一次彩纸爆发；重复调用会重新开始。 */
    public void burst() {
        running = true;
        spawned = false;
        timeArmed = false;
        startAt = SystemClock.uptimeMillis();
        lastAt = startAt;
        if (getWidth() > 0 && getHeight() > 0) {
            spawn();
        }
        invalidate();
    }

    /** 是否仍在播放；结束后自动变 false。 */
    public boolean isRunning() {
        return running;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // burst() 可能发生在首次布局/可见之前：尺寸就绪后再真正发射
        if (running && !spawned) {
            try {
                spawn();
            } catch (Throwable ignored) {
                running = false;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        spawned = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) {
            return;
        }
        try {
            drawFrame(canvas);
        } catch (Throwable ignored) {
            // 自绘异常不应拖垮页面：本轮就此停止并清空画面，等下一次 burst() 重新开始
            running = false;
            spawned = false;
            invalidate();
        }
    }

    private void drawFrame(Canvas canvas) {
        if (!spawned) {
            spawn();
            if (!spawned) {
                postInvalidateOnAnimation();
                return;
            }
        }
        int w = getWidth();
        int h = getHeight();
        long now = SystemClock.uptimeMillis();
        long elapsed = now - startAt;
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        if (elapsed >= DURATION_MS) {
            // 最后一帧：清空自身内容并停止自刷新
            running = false;
            spawned = false;
            invalidate();
            return;
        }
        postInvalidateOnAnimation();

        float dt = (now - lastAt) / 1000f;
        if (dt <= 0f) {
            dt = 1f / 60f;
        } else if (dt > 0.05f) {
            dt = 0.05f; // 主线程卡顿后不让纸片瞬移
        }
        lastAt = now;
        step(dt, now);

        float alpha = 1f;
        if (elapsed > FADE_START_MS) {
            alpha = 1f - (elapsed - FADE_START_MS) / (float) (DURATION_MS - FADE_START_MS);
            if (alpha < 0f) {
                alpha = 0f;
            }
        }
        for (int i = 0; i < PALETTE_SIZE; i++) {
            faded[i] = AppTheme.withAlpha(palette[i], alpha);
        }

        float margin = dp(40f);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < COUNT; i++) {
            if (py[i] > h + margin || py[i] < -margin || px[i] < -margin || px[i] > w + margin) {
                continue; // 画面外的纸片不必进入保存/旋转/绘制流程
            }
            paint.setColor(faded[colorIndex[i]]);
            int save = canvas.save();
            canvas.translate(px[i], py[i]);
            canvas.rotate(rotation[i]);
            rect.set(-halfWidth[i], -halfHeight[i], halfWidth[i], halfHeight[i]);
            canvas.drawRoundRect(rect, halfHeight[i] * 0.30f, halfHeight[i] * 0.30f, paint);
            canvas.restoreToCount(save);
        }
    }

    /** 初始化并发射全部纸片：左右两簇从画面下部向内上方喷出。 */
    private void spawn() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        cachePalette();

        for (int i = 0; i < COUNT; i++) {
            boolean fromLeft = (i & 1) == 0;
            px[i] = w * (fromLeft ? 0.10f : 0.90f) + (random.nextFloat() - 0.5f) * dp(24f);
            py[i] = h * (0.86f + random.nextFloat() * 0.07f);
            // 左簇朝右上、右簇朝左上，两股纸流在画面中部交叉
            double angle = fromLeft
                    ? -Math.PI / 2 + 0.10 + random.nextDouble() * 0.75
                    : -Math.PI / 2 - 0.10 - random.nextDouble() * 0.75;
            float speed = dp(520f + random.nextFloat() * 620f);
            vx[i] = (float) Math.cos(angle) * speed;
            vy[i] = (float) Math.sin(angle) * speed;
            rotation[i] = random.nextFloat() * 360f;
            spin[i] = (random.nextFloat() - 0.5f) * 1040f;
            float longStrip = random.nextFloat() > 0.82f ? 1.6f : 1f;
            halfWidth[i] = dp(2.2f + random.nextFloat() * 2.0f);
            halfHeight[i] = dp(3.2f + random.nextFloat() * 3.0f) * longStrip;
            swayPhase[i] = random.nextFloat() * 6.2832f;
            int pick = random.nextInt(12);
            // 约 1/12 白纸，其余在 5 个品牌令牌里均匀取
            colorIndex[i] = pick == 0 ? PALETTE_BRAND_COUNT : pick % PALETTE_BRAND_COUNT;
        }
        spawned = true;
        if (!timeArmed) {
            long now = SystemClock.uptimeMillis();
            startAt = now;
            lastAt = now;
            timeArmed = true;
        }
    }

    /** 一步物理积分：重力 + 空气阻力 + 横向摆动。 */
    private void step(float dt, long now) {
        // 纸片阻力大：重力比自由落体收敛得慢一点，落地过程才像纸而不像石子
        float gravity = dp(780f);
        float verticalDrag = 1.10f;
        float swaySpeed = dp(26f);
        for (int i = 0; i < COUNT; i++) {
            vy[i] = (vy[i] + gravity * dt) * (1f - verticalDrag * dt);
            vx[i] *= (1f - 0.80f * dt);
            rotation[i] += spin[i] * dt;
            // 纸张左右飘的幅度用正弦速度叠加，看起来像被气流托着
            float sway = (float) Math.sin(now * 0.004f + swayPhase[i]) * swaySpeed;
            px[i] += (vx[i] + sway) * dt;
            py[i] += vy[i] * dt;
        }
    }

    private void cachePalette() {
        AppTheme.Colors c = AppTheme.colors();
        palette[0] = c.accent;
        palette[1] = c.warn;
        palette[2] = c.success;
        palette[3] = c.danger;
        palette[4] = c.textPrimary;
        palette[5] = Color.WHITE;
    }

    private float dp(float value) {
        return value * density;
    }
}
