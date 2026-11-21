package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 应用启动加载动画：品牌与琴弦入场 → 进度推进 → 收尾 → 整屏淡出后回调。
 *
 * <p>为什么是自绘 View：工程只有平台 API，没有属性动画库；而且启动页需要琴身、拨弦波纹、
 * 上升音点这些组件无法直接拼出的画面。整个动画只有一个 Paint / 一个 RectF / 三条复用 Path，
 * onDraw 内不创建任何对象，启动开销保持在最低。
 *
 * <p>时间轴（{@link SystemClock#uptimeMillis()} 是唯一基准，避免系统时间被修改时跳帧）：
 * <ul>
 *   <li>0-300ms：尤克里里剪影、应用名淡入，四根琴弦依次拨动；</li>
 *   <li>300-1500ms：进度 0→90%，状态文案轮换；</li>
 *   <li>1500-1800ms：进度补满到 100%，文案固定「准备就绪」；</li>
 *   <li>1800-2100ms：整屏淡出，最后一帧后回调 {@link Listener#onFinished()} 恰好一次。</li>
 * </ul>
 * 开始 500ms 后轻触任意位置可跳过：跳过只是把淡出起点提前，仍然走同一条结束路径，
 * 因此回调次数与正常结束完全一致。
 *
 * <p>颜色：启动页是固定深色的品牌场景，因此取暗色主题令牌（{@link AppTheme#darkColors()}）
 * 而不是当前主题 —— 否则浅色主题的深色文字会落在深蓝背景上不可读。
 */
public final class SplashOverlay extends View {

    /** 动画结束回调；主线程、恰好一次，{@link #cancel()} 后不再回调。 */
    public interface Listener {
        void onFinished();
    }

    // ------------------------------------------------------------ 时间轴
    private static final long INTRO_MS = 300L;
    private static final long PROGRESS_END_MS = 1500L;
    private static final long READY_MS = 1800L;
    private static final long FADE_MS = 300L;
    /** 开始后这段时间之前触摸只被吞掉，不触发跳过（防止误触让启动页一闪而过）。 */
    private static final long SKIP_AFTER_MS = 500L;
    /** 底部提示语轮换周期。 */
    private static final long TIP_SLOT_MS = 700L;

    // ------------------------------------------------------------ 文案（常量，onDraw 里零分配）
    private static final String STATUS_WAKE = "正在唤醒琴弦…";
    private static final String STATUS_LIBRARY = "正在加载曲库…";
    private static final String STATUS_LIGHT = "正在校准灯光…";
    private static final String STATUS_STAGE = "正在准备演奏台…";
    private static final String STATUS_READY = "准备就绪";
    private static final String SKIP_HINT = "轻触跳过";
    private static final String DEFAULT_NAME = "Ukulele Studio";
    private static final String[] STATUS = {STATUS_WAKE, STATUS_LIBRARY, STATUS_LIGHT, STATUS_STAGE, STATUS_READY};
    private static final String[] DEFAULT_TIPS = {
            "先连上你的尤克里里，灯阵会随拨弦亮起",
            "慢练三遍，比快练一遍更有效",
            "练习前先把四根弦调准，听感会好很多",
            "节拍器是练习节奏最省心的老师",
            "手腕放松，扫弦的声音会更干净",
            "每天十分钟，胜过周末突击两小时"
    };
    /** 百分比文本预先造好：进度每帧都可能变，避免在 onDraw 里做 int→String 的分配。 */
    private static final String[] PERCENT = new String[101];

    static {
        for (int i = 0; i <= 100; i++) {
            PERCENT[i] = i + "%";
        }
    }

    // ------------------------------------------------------------ 品牌色（取自暗色令牌，深色场景固定）
    private static final AppTheme.Colors DARK = AppTheme.darkColors();
    private static final int TRACK = AppTheme.withAlpha(DARK.textSecondary, 0.18f);
    private static final int[] BG_COLORS = {DARK.bgRoot, 0xFF0A1B2A, 0xFF06101A};
    private static final float[] BG_POSITIONS = {0f, 0.55f, 1f};
    private static final int[] GLOW_COLORS = {
            AppTheme.withAlpha(DARK.accent, 0.22f),
            AppTheme.withAlpha(DARK.accent, 0.07f),
            0x00000000
    };
    private static final float[] GLOW_POSITIONS = {0f, 0.45f, 1f};

    // ------------------------------------------------------------ 上升粒子（种子一次性算好）
    private static final int PARTICLE_COUNT = 22;
    private final float[] particleX = new float[PARTICLE_COUNT];
    private final float[] particlePhase = new float[PARTICLE_COUNT];
    private final float[] particleSpeed = new float[PARTICLE_COUNT];
    private final float[] particleSize = new float[PARTICLE_COUNT];
    private final boolean[] particleIsNote = new boolean[PARTICLE_COUNT];

    // ------------------------------------------------------------ 画笔与复用对象
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bodyPath = new Path();
    private final Path tmpPath = new Path();
    private final Path stringPath = new Path();
    private final RectF rect = new RectF();
    private Typeface boldFace;
    private float density = 1f;

    // ------------------------------------------------------------ 状态
    private String appName = DEFAULT_NAME;
    private String versionLabel = "";
    private String[] tips = DEFAULT_TIPS;
    private Listener listener;
    private boolean running;
    private long startAt;
    /** 淡出起点的相对毫秒；正常结束是 READY_MS，跳过后提前为当时的 elapsed。 */
    private long fadeStartRel = READY_MS;
    private boolean skipped;
    private float progress;
    private int statusIndex;
    private int tipIndex;
    private float tipAlpha = 1f;
    private float introAlpha;
    private float globalAlpha = 1f;
    private Listener pendingListener;
    private final Runnable finishDeadline = this::finish;

    // ------------------------------------------------------------ 几何（onSizeChanged 计算一次）
    private float widthPx;
    private float heightPx;
    private float cx;
    private float nameBaseline;
    private float versionBaseline;
    private float barLeft;
    private float barRight;
    private float barTop;
    private float barWidth;
    private float barHeight;
    private float statusBaseline;
    private float tipBaseline;
    private float skipBaseline;
    private float lowerR;
    private float upperR;
    private float lowerCy;
    private float upperCy;
    private float logoBottom;
    private float neckW;
    private float neckTop;
    private float headW;
    private float headTop;
    private float headBottom;
    private float stringTop;
    private float stringGap;
    private float bridgeY;
    private float soundCy;
    private float soundR;
    private float glowCy;
    private float glowR;
    private Shader bgShader;
    private Shader glowShader;

    /** 结束通知只投递一次；用字段复用 Runnable，避免结束时再分配。 */
    private final Runnable finishNotify = new Runnable() {
        @Override
        public void run() {
            Listener l = pendingListener;
            pendingListener = null;
            if (l != null) {
                try {
                    l.onFinished();
                } catch (Throwable ignored) {
                    // 回调是调用方代码：它的异常不能反过来弄崩启动页
                }
            }
        }
    };

    public SplashOverlay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        if (!(density > 0f)) {
            density = 1f;
        }
        boldFace = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        setContentDescription("应用启动加载动画，轻触任意位置可跳过");
        setFocusable(false);
        setElevation(16f*density);
        setClickable(false);
        // 粒子参数用正弦哈希一次性生成，onDraw 里只做加减乘除
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleX[i] = hash(i, 1);
            particlePhase[i] = hash(i, 2);
            particleSpeed[i] = 0.00013f + hash(i, 3) * 0.00018f;
            particleSize[i] = 1.8f + hash(i, 4) * 2.6f;
            particleIsNote[i] = hash(i, 5) > 0.58f;
        }
    }

    /** 主线程调用：从头开始播放动画。重复调用会静默重启动画，旧回调作废。 */
    public void start(String appName, String versionName, String[] tips, Listener listener) {
        this.appName = (appName == null || appName.trim().isEmpty()) ? DEFAULT_NAME : appName;
        this.versionLabel = buildVersionLabel(versionName);
        this.tips = sanitizeTips(tips);
        this.listener = listener;
        this.pendingListener = null;
        removeCallbacks(finishNotify);
        removeCallbacks(finishDeadline);
        this.skipped = false;
        this.fadeStartRel = READY_MS;
        this.tipIndex = 0;
        this.tipAlpha = 1f;
        this.progress = 0f;
        this.statusIndex = 0;
        this.introAlpha = .45f;
        this.globalAlpha = 1f;
        this.startAt = SystemClock.uptimeMillis();
        this.running = true;
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        if (getAlpha() != 1f) {
            setAlpha(1f);
        }
        postDelayed(finishDeadline, READY_MS + FADE_MS);
        invalidate();
    }

    /** 停止动画与一切回调（不回调 listener）。重复调用安全。 */
    public void cancel() {
        running = false;
        listener = null;
        pendingListener = null;
        skipped = false;
        removeCallbacks(finishNotify);
        removeCallbacks(finishDeadline);
        // 覆盖层是透明的：重绘一帧空内容，避免 Cancel 后残留最后一帧画面
        invalidate();
    }

    /** 是否仍在播放（含淡出阶段；回调投递后为 false）。 */
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------ 生命周期 / 输入

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // 窗口退到后台时停掉动画与回调，避免不可见时还在空转
        if (visibility != VISIBLE) {
            cancel();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            cancel();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!running) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            long elapsed = SystemClock.uptimeMillis() - startAt;
            if (elapsed >= SKIP_AFTER_MS && elapsed < fadeStartRel) {
                // 跳过＝立即进入淡出；时间轴后续仍统一走到 finish()，保证回调只发生一次
                fadeStartRel = elapsed;
                skipped = true;
                removeCallbacks(finishDeadline);
                postDelayed(finishDeadline, FADE_MS);
                invalidate();
            }
        }
        // 播放期间吞掉所有触摸，不把点击漏给下层界面
        return true;
    }

    // ------------------------------------------------------------ 尺寸与几何

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        try {
            buildGeometry(w, h);
        } catch (Throwable ignored) {
            // 尺寸异常时保持上一次几何；绘制端有下限保护，不会崩溃
        }
    }

    private void buildGeometry(int w, int h) {
        widthPx = w;
        heightPx = h;
        cx = w * 0.5f;
        nameBaseline = h * 0.635f;
        versionBaseline = nameBaseline + dp(21f);
        barHeight = Math.max(dp(5f), dp(6f));
        barWidth = Math.min(w * 0.68f, dp(320f));
        barLeft = cx - barWidth * 0.5f;
        barRight = barLeft + barWidth;
        barTop = h * 0.72f;
        statusBaseline = barTop + barHeight + dp(21f);
        tipBaseline = h - dp(58f);
        skipBaseline = h - dp(22f);

        // 琴身大小：既受宽度限制，也要给应用名留出空间
        float available = nameBaseline - dp(110f);
        lowerR = Math.max(dp(10f), Math.min(w * 0.16f, available / 5.3f));
        upperR = lowerR * 0.68f;
        logoBottom = nameBaseline - dp(44f);
        lowerCy = logoBottom - lowerR;
        upperCy = lowerCy - lowerR * 1.28f;
        neckW = lowerR * 0.82f;
        neckTop = upperCy - lowerR * 2.4f;
        headBottom = neckTop + lowerR * 0.10f;
        headTop = headBottom - lowerR * 0.60f;
        headW = lowerR * 1.06f;
        stringTop = headBottom + lowerR * 0.02f;
        stringGap = lowerR * 0.24f;
        bridgeY = lowerCy + lowerR * 0.62f;
        soundCy = upperCy + lowerR * 0.16f;
        soundR = lowerR * 0.30f;
        glowCy = (headTop + logoBottom) * 0.5f;
        glowR = Math.max(lowerR * 3.4f, w * 0.30f);
        buildShaders(w, h);
        buildBodyPath();
    }

    private void buildShaders(int w, int h) {
        bgShader = new LinearGradient(0f, 0f, 0f, Math.max(1f, h),
                BG_COLORS, BG_POSITIONS, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(cx, glowCy, Math.max(1f, glowR),
                GLOW_COLORS, GLOW_POSITIONS, Shader.TileMode.CLAMP);
    }

    /** 琴身＝上下两个圆弧的并集；用并集而不是两个圆叠画，避免内部出现描边接缝。 */
    private void buildBodyPath() {
        bodyPath.reset();
        rect.set(cx - lowerR, lowerCy - lowerR, cx + lowerR, lowerCy + lowerR);
        bodyPath.addOval(rect, Path.Direction.CW);
        tmpPath.reset();
        rect.set(cx - upperR, upperCy - upperR, cx + upperR, upperCy + upperR);
        tmpPath.addOval(rect, Path.Direction.CW);
        bodyPath.op(tmpPath, Path.Op.UNION);
    }

    // ------------------------------------------------------------ 绘制主循环

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long elapsed = now - startAt;
        if (elapsed < 0) {
            elapsed = 0;
        }
        if (elapsed >= fadeStartRel + FADE_MS) {
            finish();
            return;
        }
        postInvalidateOnAnimation();
        updatePhase(elapsed);

        int layerAlpha = (int) (globalAlpha * 255f + 0.5f);
        if (layerAlpha < 0) {
            layerAlpha = 0;
        } else if (layerAlpha > 255) {
            layerAlpha = 255;
        }
        int save;
        if (layerAlpha < 255) {
            save = canvas.saveLayerAlpha(0f, 0f, w, h, layerAlpha);
        } else {
            save = canvas.save();
        }
        try {
            drawBackground(canvas, w, h, elapsed);
            drawParticles(canvas, elapsed);
            drawUkulele(canvas, elapsed);
            drawBrand(canvas, elapsed);
            drawProgress(canvas);
            drawBottom(canvas, elapsed);
        } catch (Throwable ignored) {
            // 自绘 View 的绘制异常不应拖垮启动流程；时间轴会在后续帧照常收尾
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** 由 elapsed 推出本帧所有动画量（纯计算，不分配对象）。 */
    private void updatePhase(long elapsed) {
        float intro;
        float p;
        int status;
        if (elapsed < INTRO_MS) {
            intro = .45f + .55f * easeOutCubic(elapsed / (float) INTRO_MS);
            p = 0f;
            status = 0;
        } else if (elapsed < PROGRESS_END_MS) {
            intro = 1f;
            float k = (elapsed - INTRO_MS) / (float) (PROGRESS_END_MS - INTRO_MS);
            p = 0.9f * easeOutCubic(k);
            status = 1 + Math.min(2, (int) (k * 3f));
        } else if (elapsed < READY_MS) {
            intro = 1f;
            float k = Math.min(1f, (elapsed - PROGRESS_END_MS) / 150f);
            p = 0.9f + 0.1f * easeOutCubic(k);
            status = 4;
        } else {
            intro = 1f;
            p = 1f;
            status = 4;
        }
        if (skipped) {
            p = 1f;
            status = 4;
        }
        progress = p;
        statusIndex = status;
        introAlpha = intro;

        if (tips != null && tips.length > 0) {
            long slot = elapsed / TIP_SLOT_MS;
            int idx = (int) (slot % tips.length);
            if (idx < 0 || idx >= tips.length) {
                idx = 0;
            }
            tipIndex = idx;
            long local = elapsed % TIP_SLOT_MS;
            float a = 1f;
            if (local < 160L) {
                a = local / 160f;
            } else if (local > TIP_SLOT_MS - 200L) {
                a = (TIP_SLOT_MS - local) / 200f;
            }
            tipAlpha = clamp01(a);
        } else {
            tipIndex = 0;
            tipAlpha = 0f;
        }

        if (elapsed > fadeStartRel) {
            float k = (elapsed - fadeStartRel) / (float) FADE_MS;
            k = clamp01(k);
            // 先慢后快：画面内容保持可读到最后，再干净地消失
            globalAlpha = 1f - k * k;
        } else {
            globalAlpha = 1f;
        }
    }

    private void finish() {
        if (!running) {
            return;
        }
        running = false;
        removeCallbacks(finishDeadline);
        Listener l = listener;
        listener = null;
        invalidate();
        if (l != null) {
            pendingListener = l;
            post(finishNotify);
        }
    }

    // ------------------------------------------------------------ 背景与粒子

    private void drawBackground(Canvas canvas, int w, int h, long elapsed) {
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(DARK.bgRoot);
        canvas.drawRect(0f, 0f, w, h, paint);
        if (bgShader != null) {
            paint.setShader(bgShader);
            canvas.drawRect(0f, 0f, w, h, paint);
            paint.setShader(null);
        }
        if (glowShader != null) {
            // 青色柔光轻微呼吸，启动页因此不是一张静止的底图
            float pulse = 0.80f + 0.20f * (float) Math.sin(elapsed * 0.0035);
            paint.setShader(glowShader);
            paint.setAlpha((int) (255f * (0.55f + 0.45f * pulse)));
            canvas.drawCircle(cx, glowCy, glowR, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }
    }

    /** 上升的音符 / 光点：从底部缓缓升起，首尾淡入淡出。 */
    private void drawParticles(Canvas canvas, long elapsed) {
        float baseAlpha = clamp01(elapsed / 260f);
        if (baseAlpha <= 0.01f) {
            return;
        }
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float u = (elapsed * particleSpeed[i] + particlePhase[i]) % 1f;
            if (u < 0f) {
                u += 1f;
            }
            float fade = (float) Math.sin(u * Math.PI);
            if (fade <= 0.02f) {
                continue;
            }
            float y = heightPx * 0.97f - u * (heightPx * 0.88f);
            float sway = (float) Math.sin(elapsed * 0.0016f + particlePhase[i] * 6.2832f) * dp(11f);
            float x = widthPx * (0.10f + particleX[i] * 0.80f) + sway;
            float size = particleSize[i] * density;
            float a = fade * baseAlpha;
            if (particleIsNote[i]) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(AppTheme.withAlpha(DARK.textSecondary, a * 0.50f));
                rect.set(x - size, y - size * 0.70f, x + size, y + size * 0.70f);
                canvas.drawOval(rect, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(dp(0.8f), size * 0.30f));
                canvas.drawLine(x + size * 0.80f, y, x + size * 0.80f, y - size * 3.0f, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(AppTheme.withAlpha(DARK.accent, a * 0.55f));
                canvas.drawCircle(x, y, size, paint);
            }
        }
        paint.setStrokeWidth(1f);
    }

    // ------------------------------------------------------------ 尤克里里剪影

    private void drawUkulele(Canvas canvas, long elapsed) {
        if (lowerR <= 0f) {
            return;
        }
        float a = introAlpha;
        if (a <= 0.01f) {
            return;
        }
        float scale = 0.94f + 0.06f * a;
        float centerY = (headTop + logoBottom) * 0.5f;
        int save = canvas.save();
        canvas.scale(scale, scale, cx, centerY);

        // 琴颈：先画，底部伸进琴身，被琴身覆盖后自然收口
        fillPaint(AppTheme.withAlpha(DARK.surface3, a));
        rect.set(cx - neckW * 0.5f, neckTop, cx + neckW * 0.5f, upperCy + upperR * 0.4f);
        canvas.drawRoundRect(rect, neckW * 0.35f, neckW * 0.35f, paint);

        // 琴头与四个弦钮
        fillPaint(AppTheme.withAlpha(DARK.surface4, a));
        rect.set(cx - headW * 0.5f, headTop, cx + headW * 0.5f, headBottom + lowerR * 0.10f);
        canvas.drawRoundRect(rect, headW * 0.28f, headW * 0.28f, paint);
        fillPaint(AppTheme.withAlpha(DARK.accent, 0.75f * a));
        for (int i = 0; i < 4; i++) {
            boolean left = (i & 1) == 0;
            float px = left ? cx - headW * 0.62f : cx + headW * 0.62f;
            float py = headTop + lowerR * (0.20f + 0.22f * (i >> 1));
            canvas.drawCircle(px, py, Math.max(dp(1.6f), lowerR * 0.045f), paint);
        }

        // 琴身：上下双圆弧的并集
        fillPaint(AppTheme.withAlpha(DARK.surface2, a));
        canvas.drawPath(bodyPath, paint);
        strokePaint(AppTheme.withAlpha(DARK.accent, 0.55f * a), Math.max(dp(1.2f), lowerR * 0.030f));
        canvas.drawPath(bodyPath, paint);

        // 音孔：琴身的视觉中心
        fillPaint(AppTheme.withAlpha(0xFF050C16, a));
        canvas.drawCircle(cx, soundCy, soundR, paint);
        strokePaint(AppTheme.withAlpha(DARK.accent, 0.35f * a), Math.max(dp(1f), lowerR * 0.024f));
        canvas.drawCircle(cx, soundCy, soundR, paint);

        // 琴桥：琴弦在这里收束
        fillPaint(AppTheme.withAlpha(DARK.surface4, a));
        rect.set(cx - lowerR * 0.46f, bridgeY - dp(1.5f), cx + lowerR * 0.46f, bridgeY + dp(2.5f));
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        strokePaint(AppTheme.withAlpha(DARK.accent, 0.30f * a), Math.max(dp(0.8f), lowerR * 0.02f));
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);

        drawStrings(canvas, elapsed);
        canvas.restoreToCount(save);
    }

    /** 四根竖直琴弦：逐根延迟被「拨到」，之后振幅随时间指数衰减。 */
    private void drawStrings(Canvas canvas, long elapsed) {
        float length = bridgeY - stringTop;
        if (length <= 0f) {
            return;
        }
        float alpha = introAlpha;
        float maxDx = dp(5.5f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1f), dp(1.15f)));
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int s = 0; s < 4; s++) {
            float baseX = cx + (s - 1.5f) * stringGap;
            // 每根弦晚 55ms 被拨到，形成一次轻轻扫过的听感/观感
            float local = elapsed - (INTRO_MS * 0.35f + s * 55f);
            float amp = 0f;
            if (local > 0f && local < 1400f) {
                amp = (float) Math.exp(-local / 380f) * (float) Math.sin(local * 0.028f);
            }
            stringPath.reset();
            int segments = 16;
            for (int i = 0; i <= segments; i++) {
                float u = i / (float) segments;
                float dx = amp * maxDx * (float) Math.sin(u * Math.PI);
                float x = baseX + dx;
                float y = stringTop + u * length;
                if (i == 0) {
                    stringPath.moveTo(x, y);
                } else {
                    stringPath.lineTo(x, y);
                }
            }
            paint.setColor(AppTheme.withAlpha(DARK.textPrimary, 0.78f * alpha));
            canvas.drawPath(stringPath, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1f);
    }

    // ------------------------------------------------------------ 品牌文字 / 进度 / 底部

    private void drawBrand(Canvas canvas, long elapsed) {
        // 应用名比 logo 略晚浮现，视线先落在琴上再落到名字上
        float a = clamp01((elapsed - 100f) / 220f);
        if (a <= 0.01f) {
            return;
        }
        float slide = (1f - a) * dp(10f);
        drawText(canvas, appName, cx, nameBaseline + slide,
                widthPx - dp(56f), 27f, AppTheme.withAlpha(DARK.textPrimary, a), 0, true);
        if (versionLabel != null && versionLabel.length() > 0) {
            drawText(canvas, versionLabel, cx, versionBaseline + slide,
                    widthPx - dp(56f), 12f, AppTheme.withAlpha(DARK.textSecondary, a * 0.9f), 0, false);
        }
    }

    private void drawProgress(Canvas canvas) {
        if (barWidth <= 0f) {
            return;
        }
        // 进度区随品牌一起入场，避免第一帧先闪出光秃秃的进度条
        float intro = introAlpha;
        float radius = barHeight * 0.5f;
        fillPaint(mulAlpha(TRACK, intro));
        rect.set(barLeft, barTop, barRight, barTop + barHeight);
        canvas.drawRoundRect(rect, radius, radius, paint);

        int pct = Math.round(progress * 100f);
        if (pct < 0) {
            pct = 0;
        } else if (pct > 100) {
            pct = 100;
        }
        float fillRight = barLeft + (barRight - barLeft) * progress;
        if (fillRight - barLeft > 0.5f) {
            // 填充下方先铺一层更宽更淡的 accent，模拟灯光溢出的光晕
            fillPaint(AppTheme.withAlpha(DARK.accent, 0.20f * intro));
            rect.set(barLeft, barTop - barHeight * 0.9f,
                    fillRight + barHeight * 1.6f, barTop + barHeight * 1.9f);
            canvas.drawRoundRect(rect, radius * 2f, radius * 2f, paint);
            fillPaint(AppTheme.withAlpha(DARK.accent, intro));
            rect.set(barLeft, barTop, fillRight, barTop + barHeight);
            canvas.drawRoundRect(rect, radius, radius, paint);
            // 进度头的光点：让推进有「能量经过」的感觉
            fillPaint(AppTheme.withAlpha(DARK.accent, 0.30f * intro));
            canvas.drawCircle(fillRight, barTop + radius, barHeight * 1.5f, paint);
            fillPaint(AppTheme.withAlpha(DARK.accent, intro));
            canvas.drawCircle(fillRight, barTop + radius, barHeight * 0.55f, paint);
        }

        drawText(canvas, STATUS[statusIndex], barLeft, statusBaseline,
                barWidth * 0.74f, 12f, AppTheme.withAlpha(DARK.textSecondary, 0.95f * intro), 1, false);
        drawText(canvas, PERCENT[pct], barRight, statusBaseline,
                barWidth * 0.24f, 12f, AppTheme.withAlpha(DARK.accent, intro), 2, true);
    }

    private void drawBottom(Canvas canvas, long elapsed) {
        float intro = introAlpha;
        if (tips != null && tips.length > 0) {
            int idx = tipIndex;
            if (idx < 0 || idx >= tips.length) {
                idx = 0;
            }
            String tip = tips[idx];
            if (tip != null && tip.length() > 0 && tipAlpha > 0.01f) {
                drawText(canvas, tip, cx, tipBaseline, widthPx - dp(56f), 11f,
                        AppTheme.withAlpha(DARK.textSecondary, tipAlpha * 0.92f * intro), 0, false);
            }
        }
        // 跳过提示在可跳过后才浮现，避免一上来就催用户点掉
        float skipAlpha = clamp01((elapsed - 380f) / 240f) * 0.85f * intro;
        if (skipAlpha > 0.01f) {
            drawText(canvas, SKIP_HINT, widthPx - dp(20f), skipBaseline,
                    dp(110f), 10f, AppTheme.withAlpha(DARK.textTertiary, skipAlpha), 2, false);
        }
    }

    // ------------------------------------------------------------ 绘制工具

    private void fillPaint(int color) {
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void strokePaint(int color, float width) {
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, width));
        paint.setColor(color);
    }

    /**
     * 绘制单行文字：超宽时先缩字号，缩到下限仍超宽就按槽位裁剪。
     * 不在 onDraw 里做 substring 截断 —— 拼接「…」会分配新字符串。
     *
     * @param align 0 居中，1 左对齐，2 右对齐
     */
    private void drawText(Canvas canvas, String text, float anchorX, float baseline,
                          float maxWidth, float sizeDp, int color, int align, boolean bold) {
        if (text == null || text.length() == 0 || maxWidth <= 0f) {
            return;
        }
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(bold ? boldFace : null);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(color);
        float maxSize = sizeDp * density;
        float minSize = maxSize * 0.72f;
        paint.setTextSize(maxSize);
        while (paint.measureText(text) > maxWidth && paint.getTextSize() > minSize) {
            paint.setTextSize(paint.getTextSize() - Math.max(1f, density * 0.5f));
        }
        float textW = paint.measureText(text);
        float left = align == 0 ? anchorX - textW * 0.5f : (align == 1 ? anchorX : anchorX - textW);
        int height = getHeight();
        if (height <= 0) {
            height = 1;
        }
        int save = canvas.save();
        if (align == 0) {
            canvas.clipRect(anchorX - maxWidth * 0.5f, 0f, anchorX + maxWidth * 0.5f, height);
        } else if (align == 1) {
            canvas.clipRect(anchorX, 0f, anchorX + maxWidth, height);
        } else {
            canvas.clipRect(anchorX - maxWidth, 0f, anchorX, height);
        }
        canvas.drawText(text, left, baseline, paint);
        canvas.restoreToCount(save);
    }

    // ------------------------------------------------------------ 小工具

    private float dp(float value) {
        return value * density;
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        return v > 1f ? 1f : v;
    }

    /** 在已有 alpha 上再乘一个系数（与 AppTheme.withAlpha 的「覆盖 alpha」语义互补）。 */
    private static int mulAlpha(int color, float fraction) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * clamp01(fraction));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static float easeOutCubic(float k) {
        float v = clamp01(k);
        float inv = 1f - v;
        return 1f - inv * inv * inv;
    }

    /** 稳定伪随机：同一 index/salt 永远同一值，粒子布局无需每帧随机。 */
    private static float hash(int index, int salt) {
        double v = Math.sin(index * 12.9898 + salt * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private static String buildVersionLabel(String versionName) {
        if (versionName == null) {
            return "";
        }
        String trimmed = versionName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first == 'v' || first == 'V') {
            return trimmed;
        }
        return "v" + trimmed;
    }

    private static String[] sanitizeTips(String[] source) {
        if (source == null || source.length == 0) {
            return DEFAULT_TIPS;
        }
        int count = Math.min(source.length, 16);
        String[] copy = new String[count];
        int filled = 0;
        for (int i = 0; i < count; i++) {
            String tip = source[i];
            if (tip != null && tip.trim().length() > 0) {
                copy[filled++] = tip;
            }
        }
        if (filled == 0) {
            return DEFAULT_TIPS;
        }
        if (filled == count) {
            return copy;
        }
        String[] compact = new String[filled];
        System.arraycopy(copy, 0, compact, 0, filled);
        return compact;
    }
}
