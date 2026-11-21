package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** LED预览：与FPGA逐点使用相同PWM公式、50Hz时钟和真实拨弦事件。 */
public final class LedPreviewView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private static final String[] STRING_NAMES = {"G", "C", "E", "A"};

    // ---- 与 setParams 一一对应的参数（旧实现直接读 MainActivity 字段）----
    private int ledMode = 0;
    private final byte[] customProfile=new byte[144];
    public void setCustomProfile(byte[] b){if(b!=null&&b.length==144){System.arraycopy(b,0,customProfile,0,144);invalidate();}}
    private int customPixel(int col,int row){int i=col*18+row;int m=customProfile[i*2+1]&255;int v=(customProfile[i*2]&255)*(ledBrightness+1)>>8;
      if((m&1)!=0){int phase=(int)((SystemClock.elapsedRealtime()/20*164)&65535);int x=((phase>>(5+((m>>1)&3)))+((m>>3)<<4))&255;int w=(m&128)!=0?(x<32?(31-x)*8:0):(x<128?x*2:(255-x)*2);v=v*(w+1)>>8;}return v;}
    private int ledAxis = 0;
    private int ledSpeed = 24;
    private int ledBrightness = 180;
    private int ledFlags = 0;
    private boolean ledLandscape = true;
    // ---- guide 覆盖状态 ----
    private final int[] guideFrets = {31,31,31,31};
    private int guideLevel = 0;
    private final int[] guideLevels={0,0,0,0};
    // ---- 自刷新开关：默认 true，仅在可见时按20ms更新 ----
    private boolean animating = true;
    private final LedFrameClock frameClock=new LedFrameClock();
    private boolean sleeping;
    public void setSleeping(boolean value){frameClock.advance(SystemClock.elapsedRealtime(),ledSpeed,animating && !sleeping);sleeping=value;invalidate();}
    private final StrumFlashState strumFlash=new StrumFlashState();
    private boolean guideActive;
    private int capo;
    public void setCapo(int value){int next=com.ukulele.monitor.CapoMapping.clamp(value);if(next!=capo){capo=next;strumFlash.clear();invalidate();}}


    public LedPreviewView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("LED 灯阵预览，横向 18 × 4 或竖向 4 × 18");
    }

    /** mode/axis/speed/brightness/flags/landscape，语义与旧 MainActivity 字段一致。 */
    public void setParams(int mode, int axis, int speed, int brightness, int flags, boolean landscape) {
        frameClock.advance(SystemClock.elapsedRealtime(),ledSpeed,animating && !sleeping);
        if(mode!=ledMode || axis!=ledAxis || speed!=ledSpeed || brightness!=ledBrightness || flags!=ledFlags)clearGuide();
        if(mode!=ledMode)strumFlash.clear();
        ledMode = mode; ledAxis = axis; ledSpeed = speed; ledBrightness = brightness; ledFlags = flags; ledLandscape = landscape;
        invalidate();
    }

    /** Legacy single-fret update; all four strings are still replaced atomically. */
    public void setGuide(int cols,int fret,int level){
        int[] f={31,31,31,31};for(int c=0;c<4;c++)if((cols&(1<<c))!=0)f[c]=fret;setGuideFrets(f,level);
    }
    public void setGuideFrets(int[] frets,int level){
        if(frets==null||frets.length!=4)return;
        for(int f:frets)if(f<0||(f>18&&f!=31))return;
        java.util.Arrays.fill(guideLevels,Math.max(0,Math.min(255,level)));System.arraycopy(frets,0,guideFrets,0,4);guideLevel=Math.max(0,Math.min(255,level));guideActive=true;invalidate();
    }
    public void setGuideLevels(int[] frets,int[] levels){
        if(levels==null||levels.length!=4)return;setGuideFrets(frets,0);
        for(int c=0;c<4;c++)guideLevels[c]=Math.max(0,Math.min(255,levels[c]));invalidate();
    }
    public void clearGuide(){guideActive=false;java.util.Arrays.fill(guideFrets,31);guideLevel=0;invalidate();}

    /** Actual submitted notes, ADC G/E/C/A order; independent expiry per string. */
    public void setStrumNotes(int mask,int[] midi){strumFlash.accept(mask,midi,SystemClock.elapsedRealtime(),capo);invalidate();}
    public void clearStrumEvents(){strumFlash.clear();invalidate();}

    /** 关动画时停止 postInvalidateDelayed(20) 自刷新，但仍允许一次重绘。 */
    public void setAnimating(boolean value) { frameClock.advance(SystemClock.elapsedRealtime(),ledSpeed,animating && !sleeping);animating = value; invalidate(); }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static double channel(int value) { double s = value / 255.0; return s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4); }

    private static double luminance(int color) { return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) + .0722 * channel(Color.blue(color)); }

    private static double ratio(int a, int b) { double la = luminance(a), lb = luminance(b), hi = Math.max(la, lb), lo = Math.min(la, lb); return (hi + .05) / (lo + .05); }

    /** 浅色标签/描边定界用的加深（不改令牌，仅在浅色主题内局部使用）。 */
    private static int shade(int color, float factor) { return Color.rgb((int) (Color.red(color) * factor), (int) (Color.green(color) * factor), (int) (Color.blue(color) * factor)); }

    /** 小号标签：暗色用 textTertiary；浅色下 textTertiary 对白底仅 2.77:1，自动升为 textSecondary。 */
    private int labelColor(AppTheme.Colors c) { return ratio(c.textTertiary, c.surface) >= 3.5 ? c.textTertiary : c.textSecondary; }

    @Override protected void onDraw(Canvas c) {
        AppTheme.Colors colors = AppTheme.colors();
        int w = getWidth(), h = getHeight(); c.drawColor(colors.surface);
        boolean darkTheme = luminance(colors.bgRoot) < .35;
        p.setStyle(Paint.Style.FILL); p.setTextSize(dp(11)); p.setColor(labelColor(colors));
        c.drawText("LED MATRIX  ·  " + (ledLandscape ? "横向 18 × 4" : "竖向 4 × 18"), dp(16), dp(22), p);

        boolean horizontal = ledLandscape; int across = horizontal ? 18 : 4; int down = horizontal ? 4 : 18;
        float top = dp(42), left = horizontal ? dp(38) : dp(24), right = dp(18), bottom = dp(30);
        float gapX = horizontal ? dp(4) : dp(7), gapY = horizontal ? dp(9) : dp(4);
        float cellW = (w - left - right - gapX * (across - 1)) / across;
        float cellH = (h - top - bottom - gapY * (down - 1)) / down;
        long now=SystemClock.elapsedRealtime();
        int phase=frameClock.advance(now,ledSpeed,animating && !sleeping);


        // 灯阵框：1dp 描边定义边界，格子直接落在 surface 上（与旧实现一致，保证熄灭格可辨）
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(colors.stroke);
        rect.set(left - dp(6), top - dp(6), w - right + dp(6), h - bottom + dp(6));
        c.drawRoundRect(rect, dp(10), dp(10), p);
        p.setStyle(Paint.Style.FILL);

        float radius = Math.min(dp(4), Math.min(cellW, cellH) * .34f);
        for (int x = 0; x < across; ++x) for (int y = 0; y < down; ++y) {
            int row = horizontal ? x : y; int col = horizontal ? y : x;
            int intensity=sleeping||ledMode==0?0:guideActive?com.ukulele.monitor.CapoMapping.guidePixel(guideFrets[col],row,guideLevels[col],capo):ledMode==8?customPixel(col,row):ledMode==6?strumFlash.pixel(col,row,ledBrightness,now,capo):LedEffectMath.pixel(ledMode,ledAxis,ledBrightness,phase,col,row,0,ledFlags,0,-1,0);
            if(!sleeping)intensity=Math.max(intensity,com.ukulele.monitor.CapoMapping.marker(row,capo,ledBrightness,ledMode));
            boolean guide=guideActive && intensity>0;
            int color = intensity > 0 ? (guide ? colors.warn : (ledMode == 1 ? colors.accent : colors.ledOn)) : colors.ledOff; int alpha=255;
            float mix=Math.max(0,Math.min(255,intensity))/255f;
            int shown=Color.rgb((int)(Color.red(colors.ledOff)+(Color.red(color)-Color.red(colors.ledOff))*mix),
                (int)(Color.green(colors.ledOff)+(Color.green(color)-Color.green(colors.ledOff))*mix),
                (int)(Color.blue(colors.ledOff)+(Color.blue(color)-Color.blue(colors.ledOff))*mix));
            p.setColor(shown); rect.set(left + x * (cellW + gapX), top + y * (cellH + gapY), left + x * (cellW + gapX) + cellW, top + y * (cellH + gapY) + cellH); c.drawRoundRect(rect, radius, radius, p);
            // 浅色主题：点亮的 ledOn/accent 对白底仅 2.8~2.9:1，补 1dp 加深描边把格子边缘拉到 3:1 以上
            if (!darkTheme && intensity >= 64) { int edge = shade(color, .55f); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(Color.argb(alpha, Color.red(edge), Color.green(edge), Color.blue(edge))); c.drawRoundRect(rect, radius, radius, p); p.setStyle(Paint.Style.FILL); }
        }

        // 坐标标签：10sp 小号次级色
        p.setColor(labelColor(colors)); p.setTextSize(dp(10));
        if (horizontal) {
            for (int y = 0; y < 4; ++y) c.drawText(STRING_NAMES[y], dp(14), top + y * (cellH + gapY) + cellH / 2 + dp(4), p);
            for (int x = 0; x < 18; x += 3) c.drawText(String.valueOf(x+1), left + x * (cellW + gapX), h - dp(10), p);
        } else {
            // 竖向：左侧补全 0..17 行号，底部补全 4 个弦名标签
            for (int y = 0; y < 18; y += 3) c.drawText(String.valueOf(y+1), dp(6), top + y * (cellH + gapY) + cellH / 2 + dp(3.5f), p);
            for (int col = 0; col < 4; ++col) c.drawText(STRING_NAMES[col], left + col * (cellW + gapX) + cellW / 2 - dp(3), h - dp(10), p);
        }
        if (animating && !sleeping) postInvalidateDelayed(20);
    }
}
