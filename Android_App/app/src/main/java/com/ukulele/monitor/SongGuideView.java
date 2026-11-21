package com.ukulele.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.ukulele.monitor.ui.AppTheme;

/** 四弦滚动时间指引；评分由实际 FPGA 拨弦反馈驱动的 PracticeScorer 提供。 */
final class SongGuideView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path marker = new Path();
    private final int[] colors = new int[4];
    /**
     * 弦序：1=A(最细/最高)、2=E、3=C、4=G —— 从上到下按 1/2/3/4 排列。
     * 原来用 A/E/C/G 字母，且自下而上排列；字母既占地方又不如数字一眼可读。
     */
    private final String[] numbers = {"1", "2", "3", "4"};
    private SongTimeline timeline;
    private long[] prefixEnd=new long[0];
    private long elapsedMs;
    private int deviceMask, deviceFret = -1;
    private long deviceReceivedAt;
    private String feedback="";
    private int feedbackColor;
    private long feedbackReceivedAt;
    private boolean darkTheme = true;
    private boolean landingRight,stringsReversed;
    private int controlInset;

    void setControlInset(int width) {
        controlInset = width;
        invalidate();
    }
    private int countdown;
    private boolean audition;
    private GuideScene scene=GuideScene.CLASSIC;
    private android.graphics.LinearGradient nightSky,seaGlass,stage,candySky;
    private final int[] candyColors = {0xffffc5e6,0xffcfc1ff,0xffb3f1d8,0xffffdf9d};
    private final long[] candyNext = new long[4], candyBite = new long[4];
    private static final int BITE_MS = 230;
    private final int[] starColors = {0xff70eadc,0xff92b1ff,0xffffcf85,0xfff7aacc};

    void setScene(GuideScene value){
        scene=value;
        setLandingRight(landingRight);
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
        if(w<=0||h<=0)return;
        nightSky=new android.graphics.LinearGradient(0,0,w,h,0xff10142e,0xff233658,android.graphics.Shader.TileMode.CLAMP);
        seaGlass=new android.graphics.LinearGradient(0,0,w,h,0xff102c45,0xff106464,android.graphics.Shader.TileMode.CLAMP);
        stage=new android.graphics.LinearGradient(0,0,w,h,0xff281631,0xff4a3a5a,android.graphics.Shader.TileMode.CLAMP);
        candySky=new android.graphics.LinearGradient(0,0,w,h,0xff27203f,0xff55334e,android.graphics.Shader.TileMode.CLAMP);
    }

    void setAudition(boolean value){audition=value;invalidate();}
    void setCountdown(int value){countdown=Math.max(0,value);invalidate();}
    void setStringsReversed(boolean value){stringsReversed=value;invalidate();}
    private int laneFor(int column){return com.ukulele.monitor.ui.GuideLayout.laneForColumn(column,stringsReversed);}
    private float contentLeft,contentRight;
    private static final long LOOKAHEAD_MS = 4000;

    SongGuideView(Context context) { super(context); setContentDescription("四弦横向滚动跟弹指引：从上到下为 1(A)、2(E)、3(C)、4(G) 弦，方块数字表示品位，竖线表示到达时刻"); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private int capo;
    void setCapo(int value){capo=CapoMapping.clamp(value);invalidate();}
    void setTimeline(SongTimeline song) { timeline = song;
        prefixEnd=new long[song==null?0:song.spans.size()];long maxEnd=0;
        for(int i=0;i<prefixEnd.length;i++){maxEnd=Math.max(maxEnd,song.spans.get(i).endMs);prefixEnd[i]=maxEnd;}
        elapsedMs = 0;feedback="";clearDeviceEvent(); invalidate(); }
    void setFeedback(String value,int color){feedback=value;feedbackColor=color;feedbackReceivedAt=android.os.SystemClock.uptimeMillis();invalidate();}
    void setElapsed(long elapsed) { elapsedMs = Math.max(0, elapsed); invalidate(); }
    void setDeviceEvent(int mask, int fret) { deviceMask = mask; deviceFret = fret; deviceReceivedAt = android.os.SystemClock.uptimeMillis(); invalidate(); }
    void setDeviceFrets(int[] frets,int level){int mask=0;for(int c=0;c<4;c++)if(level>0&&frets[c]!=31)mask|=1<<c;setDeviceEvent(mask,mask==0?-1:0);}
    void setDeviceFrets(int[] frets,int[] levels){int mask=0;for(int c=0;c<4;c++)if(levels[c]>0&&frets[c]!=31)mask|=1<<c;setDeviceEvent(mask,mask==0?-1:0);}
    void clearDeviceEvent() { deviceMask = 0; deviceFret = -1; deviceReceivedAt = 0; invalidate(); }
    void setLandingRight(boolean right) {
        landingRight = right;
        setContentDescription((scene.label+"，") + (right ? "右侧基准，音符从左向右" : "左侧基准，音符从右向左") + "，方块数字表示品位");
        invalidate();
    }
    boolean isLandingRight() { return landingRight; }

    /** Presentation only: elapsed time, score windows and device events are unchanged. */
    static float timelineX(float left, float right, boolean rightBaseline, float futureFraction) {
        return rightBaseline ? right - futureFraction * (right - left) : left + futureFraction * (right - left);
    }

    /** 四弦四色语义保留：1(A)=青，2(E)=蓝，3(C)=琥珀，4(G)=粉；浅色主题改用同色系更深一档保证可读。 */
    private void refreshPalette() {
        AppTheme.Colors c = AppTheme.colors();
        int bg = c.bgRoot;
        darkTheme = (Color.red(bg) + Color.green(bg) + Color.blue(bg)) < 384;
        colors[0] = darkTheme ? c.accent : 0xff0a6e6a;
        colors[1] = darkTheme ? 0xff8cacff : 0xff3358c8;
        colors[2] = darkTheme ? c.warn : 0xff8a5a12;
        colors[3] = darkTheme ? 0xfff69ca6 : 0xffb3455f;
        if(stringsReversed){int t=colors[0];colors[0]=colors[3];colors[3]=t;t=colors[1];colors[1]=colors[2];colors[2]=t;}
    }

    private static int fade(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }

    private static double channel(int value) { double s = value / 255.0; return s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4); }

    private static double luminance(int color) { return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) + .0722 * channel(Color.blue(color)); }

    /** 音符块数字取对比度更高的一侧：暗色主题下弦色偏亮 → 深色数字；浅色主题按实际亮度自动选择。 */
    private static int readableOn(int background) {
        double l = luminance(background);
        double onWhite = 1.05 / (l + .05);
        double onDark = (l + .05) / (luminance(0xff08111f) + .05);
        return onDark >= onWhite ? 0xff08111f : 0xffffffff;
    }

    private static double contrast(int a, int b) { double la = luminance(a), lb = luminance(b), hi = Math.max(la, lb), lo = Math.min(la, lb); return (hi + .05) / (lo + .05); }

    /** 小号标签：暗色用 textTertiary；浅色下 textTertiary 对白底仅 2.77:1，自动升为 textSecondary。 */
    private int labelColor(AppTheme.Colors c) { return contrast(c.textTertiary, c.surface) >= 3.5 ? c.textTertiary : c.textSecondary; }

    /**
     * 横向时间轴布局。
     *
     * 原来的排法是「四条弦泳道横着铺满宽度、时间沿高度向上流动」：屏幕宽被四等分成四条泳道，
     * 于是**横向只有 4 个时间位置**，4000ms 全挤在约 370dp 的高度里 —— 一次只能看到寥寥几个音，
     * 看上去很赶。横屏时长度方向是宽度，所以现在改成：
     *   纵向 = 四条弦（从上到下 1/2/3/4），横向 = 时间（左→右流动）。
     * 这样时间轴拿到约 760dp，是原来的两倍多，同样 4000ms 内能排下明显更多的音符。
     */
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        AppTheme.Colors c = AppTheme.colors();
        refreshPalette();
        if(scene!=GuideScene.CLASSIC){drawLanterns(canvas);return;}
        canvas.drawColor(c.surface);

        float width = getWidth(), height = getHeight();
        float labelW = dp(36);                       // 左侧弦号列
        float headerH = com.ukulele.monitor.ui.GuideLayout.headerHeight(height,dp(52),dp(24));                      // 独立两行：当前指法、时间刻度
        float trackLeft = labelW;
        float trackRight = width - dp(10);
        contentLeft=trackLeft;contentRight=trackRight;
        float markerX = landingRight ? trackRight : trackLeft;
        float direction = landingRight ? -1f : 1f;
        float laneTop = headerH;
        float laneH = Math.max(1f, (height - headerH - dp(6)) / 4f);
        // 音符半径同时受弦间距与时间轴长度约束：横屏很宽时不会撑成巨块，竖屏预览时也放得下
        float timeSpanForSize = Math.max(dp(40), trackRight - trackLeft);
        // 上限 dp(22)：横屏很宽时音符不会撑成巨块；跨度不足时（主页竖屏预览）自动缩小。
        float radius = Math.min(laneH / 2f - dp(3), Math.min(dp(22), timeSpanForSize / 20f));
        radius = Math.max(1f,Math.min(radius,laneH*.42f));
        int accentStrong = darkTheme ? c.accent : 0xff0a6e6a;
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // ---- 四条弦泳道（纵向），极低对比交替底色 + 弦色左标记 ----
        for (int i = 0; i < 4; i++) {
            float top = laneTop + i * laneH;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i % 2 == 0 ? c.surface2 : c.surface3);
            bounds.set(labelW + dp(2), top + dp(2), trackRight, top + laneH - dp(2));
            canvas.drawRoundRect(bounds, dp(9), dp(9), paint);

            // 弦号：数字为主（1/2/3/4），小字补「弦」以避免与品位数字混淆
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(colors[i]); paint.setTextSize(Math.min(dp(17),laneH*.65f));
            canvas.drawText(String.valueOf(com.ukulele.monitor.ui.GuideLayout.numberAtLane(i,stringsReversed)), labelW / 2f - dp(3), top + laneH / 2f + dp(2), paint);
            paint.setColor(labelColor(c)); paint.setTextSize(dp(8));
            if(laneH>=dp(28))canvas.drawText("弦", labelW / 2f - dp(3), top + laneH / 2f + dp(12), paint);
        }

        // ---- 时间竖线 + 秒刻度（沿宽度铺开） ----
        float timeSpan = Math.max(dp(40), trackRight - trackLeft);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(c.stroke); paint.setStrokeWidth(dp(1));
        for (int second = 1; second <= 4; second++) {
            float x = timelineX(trackLeft, trackRight, landingRight, second / 4f);
            canvas.drawLine(x, laneTop, x, laneTop + 4 * laneH, paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(labelColor(c)); paint.setTextSize(dp(10));
        // 秒刻度从 1s 起：0s 就在落点线本身，标签会与左侧弦号打架
        for (int second = 1; second <= 4; second++) {
            float x = timelineX(trackLeft, trackRight, landingRight, second / 4f);
            float labelX = Math.max(trackLeft + dp(12), Math.min(x, trackRight - dp(12)));
            if (controlInset == 0 || labelX + dp(14) <= width - controlInset)
                canvas.drawText(second + "s", labelX, headerH*.80f, paint);
        }

        // ---- 落点线：主题强调色竖线 + 底光 + 上下小三角 ----
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(fade(accentStrong, 48)); paint.setStrokeWidth(dp(9));
        canvas.drawLine(markerX, laneTop + dp(2), markerX, laneTop + 4 * laneH - dp(2), paint);
        paint.setColor(accentStrong); paint.setStrokeWidth(dp(3));
        canvas.drawLine(markerX, laneTop, markerX, laneTop + 4 * laneH, paint);
        paint.setStyle(Paint.Style.FILL);
        marker.reset(); marker.moveTo(markerX - dp(6), laneTop + dp(2)); marker.lineTo(markerX + dp(6), laneTop + dp(2)); marker.lineTo(markerX, laneTop + dp(12)); marker.close();
        canvas.drawPath(marker, paint);
        marker.reset(); marker.moveTo(markerX - dp(6), laneTop + 4 * laneH - dp(2)); marker.lineTo(markerX + dp(6), laneTop + 4 * laneH - dp(2)); marker.lineTo(markerX, laneTop + 4 * laneH - dp(12)); marker.close();
        canvas.drawPath(marker, paint);

        // ---- 当前应弹：画在顶部中间，替代原来挤在底部的“品格”文字 ----
        String nowText = "";
        if (timeline != null) {
            SongTimeline.Step current = timeline.currentStepAt(elapsedMs);
            if (current != null) {
                StringBuilder sb = new StringBuilder();
                for (SongTimeline.Event event : current.events) {
                    if (event.level == 0) continue;
                    for (int i = 0; i < 4; i++) if ((event.mask & (1 << i)) != 0) {
                        if (sb.length() > 0) sb.append("   ");
                        sb.append(4-i).append("弦 ").append(event.fret == 0 ? (capo==0?"空弦":"空弦 @"+capo+"品") : CapoMapping.physical(event.fret,capo)+" 品");
                    }
                }
                nowText = sb.length()==0&&elapsedMs<timeline.durationMs?"休止":sb.toString();
            }
        }

        // ---- 音符：时间越靠右越晚 ----
        if (timeline != null) {
            canvas.save();
            canvas.clipRect(trackLeft, laneTop, trackRight, laneTop + 4 * laneH);
            paint.setTextAlign(Paint.Align.CENTER);
            // Both boundaries are musical deadlines, shared with the uploaded LED stream.
            // Scoring tolerance and BLE input compensation never widen or shift a duration block.
            // Prefix maxima let long scores skip completed spans in O(log n),
            // while preserving overlaps, held chord tones and backwards seeks.
            for(int index=firstVisibleSpan();index<timeline.spans.size();index++){
                SongTimeline.Span note=timeline.spans.get(index);
                if(note.startMs>elapsedMs+LOOKAHEAD_MS)break;
                if(note.endMs<=elapsedMs)continue;
                float start=timelineX(trackLeft,trackRight,landingRight,(note.startMs-elapsedMs)/(float)LOOKAHEAD_MS);
                float end=timelineX(trackLeft,trackRight,landingRight,(note.endMs-elapsedMs)/(float)LOOKAHEAD_MS);
                float center=(start+end)/2f,half=Math.abs(end-start)/2f;
                drawNote(canvas,laneFor(note.column),note.fret,center,laneTop+laneFor(note.column)*laneH+laneH/2f,radius,half,half,note.startMs<=elapsedMs);
            }
            canvas.restore();
        }

        // ---- 顶部文字：左边「现在弹什么」，右边「秒刻度」已画 ----
        if (countdown == 0 && !nowText.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER); paint.setColor(accentStrong); paint.setTextSize(dp(16));
            float maxWidth = timeSpan - dp(16);
            float size = Math.min(dp(16),headerH*.35f);paint.setTextSize(size);
            while (size > dp(9) && paint.measureText(nowText) > maxWidth) { size -= dp(0.5f); paint.setTextSize(size); }
            canvas.drawText(nowText, trackLeft + timeSpan / 2f, headerH*.36f, paint);
        }

        // ---- 设备事件圆环：画在该音符真实的时间位置上，2500ms 时效内显示 ----
        if (deviceFret >= 0 && android.os.SystemClock.uptimeMillis() - deviceReceivedAt < 2500) {
            paint.setStyle(Paint.Style.STROKE);
            float ringX = markerX + direction * dp(2);
            for (int i = 0; i < 4; i++) if ((deviceMask & (1 << i)) != 0) {
                float cy = laneTop + laneFor(i) * laneH + laneH / 2f;
                paint.setStrokeWidth(dp(1)); paint.setColor(fade(c.textPrimary, 70));
                canvas.drawCircle(ringX, cy, radius + dp(4), paint);
                paint.setStrokeWidth(dp(2)); paint.setColor(c.textPrimary);
                canvas.drawCircle(ringX, cy, radius + dp(1), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        drawOverlays(canvas,c,width,laneTop,laneTop+4*laneH,accentStrong);
    }

    private int firstVisibleSpan() { return firstVisibleSpan(elapsedMs); }

    private int firstVisibleSpan(long at){
        int lo=0,hi=prefixEnd.length;
        while(lo<hi){
            int mid=(lo+hi)>>>1;
            if(prefixEnd[mid]<=at)lo=mid+1;else hi=mid;
        }
        return lo;
    }

    private void drawLanterns(Canvas canvas){
        float width=getWidth(),height=getHeight();
        float left=dp(36),right=width-dp(14),top=dp(controlInset > 0 ? 50 : 25),bottom=height-dp(8);
        if(right-left<dp(20)||bottom-top<dp(30))return;
        boolean ensemble=scene==GuideScene.ENSEMBLE,tide=scene==GuideScene.TIDE;
        boolean candy = scene == GuideScene.CANDY;
        float linePadding=dp(ensemble||candy ? 39 : 17);
        float lineX=landingRight?right-linePadding:left+linePadding;
        float horizon=landingRight?left+dp(12):right-dp(12);
        float laneHeight=(bottom-top)/4f;
        float radius=Math.min(dp(16),laneHeight*.30f);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setShader(candy ? candySky : ensemble ? stage : tide ? seaGlass : nightSky);
        canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
        paint.setColor(0xffc6d7f8);
        for(int i=0;i<57;i++){
            paint.setAlpha(38+i%7*14);
            canvas.drawCircle((i*41%113)/113f*width,(i*31%101)/101f*height,dp(i%6==0?1.35f:.65f),paint);
        }
        paint.setAlpha(255);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(11));paint.setColor(0xffdceaff);
        if (countdown == 0) canvas.drawText(candy ? "糖豆派对  /  到嘴边时拨弦" :
                (ensemble?"乐队":tide?"潮汐":"星河")+"  /  音符触线时拨弦",width/2,dp(16),paint);
        if (candy) {
            java.util.Arrays.fill(candyNext, Long.MAX_VALUE);
            java.util.Arrays.fill(candyBite, Long.MAX_VALUE);
        }
        int arrived=0;
        if(ensemble&&timeline!=null){
            SongTimeline.Step step=timeline.currentStepAt(elapsedMs);
            if(step!=null&&elapsedMs-step.timeMs<180){
                for(SongTimeline.Event event:step.events)if(event.attack())arrived|=event.mask;
            }
        }
        for(int lane=0;lane<4;lane++){
            int avatar=stringsReversed?3-lane:lane;
            int color=(candy ? candyColors : starColors)[avatar];
            float y=top+(lane+.5f)*laneHeight;
            paint.setTextSize(Math.min(dp(15),laneHeight*.43f));paint.setColor(color);
            canvas.drawText((stringsReversed?4-lane:lane+1)+"弦",dp(18),y+dp(4),paint);
            paint.setColor(fade(color,65));paint.setStrokeWidth(dp(1));
            if(tide){
                marker.reset();marker.moveTo(left,y);
                for(int wave=0;wave<6;wave++){
                    float step=(right-left)/6f,x=left+wave*step;
                    marker.quadTo(x+step/2,y+(wave%2==0?-1:1)*radius*.40f,x+step,y);
                }
                paint.setStyle(Paint.Style.STROKE);canvas.drawPath(marker,paint);paint.setStyle(Paint.Style.FILL);
            } else if (candy) {
                for (int dot=0;dot<=16;dot++)
                    canvas.drawCircle(lineX+(horizon-lineX)*dot/16f,y,dp(dot%4==0?2:1.1f),paint);
            } else canvas.drawLine(left,y,right,y,paint);
            if (!candy) { paint.setColor(fade(color,85));canvas.drawCircle(lineX,y,radius*1.23f,paint); }
            if(ensemble){
                float avatarX=lineX+(landingRight?1:-1)*radius*1.85f;
                boolean landed=(arrived&(1<<(3-avatar)))!=0;
                if(landed){
                    paint.setColor(fade(color,60));canvas.drawCircle(avatarX,y,radius*1.25f,paint);
                }
                drawAvatar(canvas,avatar,avatarX,y-(landed?radius*.10f:0),radius*.82f,color);
            }
        }
        paint.setColor(0xffe4efff);paint.setStrokeWidth(dp(1.5f));
        canvas.drawLine(lineX,top,lineX,bottom,paint);
        if(timeline!=null){
            canvas.save();canvas.clipRect(left,top,right,bottom);
            int first=candy ? firstVisibleSpan(Math.max(0,elapsedMs-BITE_MS)) : firstVisibleSpan();
            int last=first;
            while(last<timeline.spans.size()&&timeline.spans.get(last).startMs<=elapsedMs+LOOKAHEAD_MS)last++;
            // Nearest cues are drawn last so a fast repeated fret always exposes its next attack.
            for(int i=last-1;i>=first;i--){
                SongTimeline.Span note=timeline.spans.get(i);
                int lane=laneFor(note.column),color=(candy ? candyColors : starColors)[3-note.column];
                long until=note.startMs-elapsedMs;
                boolean playing=until<=0;
                if (candy) {
                    if (playing) candyBite[lane] = Math.min(candyBite[lane], -until);
                    else candyNext[lane] = Math.min(candyNext[lane], until);
                }
                if (note.endMs<=elapsedMs) continue;
                float x=lineX+(horizon-lineX)*Math.max(0,until)/(float)LOOKAHEAD_MS;
                float y=top+(lane+.5f)*laneHeight;
                float end=lineX+(horizon-lineX)*Math.min(LOOKAHEAD_MS,note.endMs-elapsedMs)/(float)LOOKAHEAD_MS;
                paint.setStrokeWidth(dp(3));paint.setColor(fade(color,playing?143:86));
                canvas.drawLine(x,y,end,y,paint);
                if (candy && playing) continue;
                paint.setColor(fade(color,playing?75:37));canvas.drawCircle(x,y,radius*1.42f,paint);
                float size=playing?radius:radius*.81f;
                paint.setColor(color);
                if (candy) {
                    bounds.set(x-size,y-size*.80f,x+size,y+size*.80f);
                    canvas.drawRoundRect(bounds,size*.58f,size*.58f,paint);
                    paint.setColor(fade(0xffffffff,136));
                    canvas.drawCircle(x-size*.48f,y-size*.43f,size*.14f,paint);
                } else if(tide){
                    bounds.set(x-size,y-size*.73f,x+size,y+size*.73f);
                    canvas.drawRoundRect(bounds,size*.38f,size*.38f,paint);
                    canvas.drawCircle(x,y-size*.7f,size*.23f,paint);
                }else if(ensemble){
                    bounds.set(x-size,y-size,x+size,y+size);
                    canvas.drawRoundRect(bounds,size*.32f,size*.32f,paint);
                    if(playing && elapsedMs-note.startMs<185){
                        float spark=1+(elapsedMs-note.startMs)/185f;
                        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));
                        paint.setColor(fade(color,(int)(155/spark)));canvas.drawCircle(lineX,y,size*spark,paint);
                        paint.setStyle(Paint.Style.FILL);
                    }
                }else canvas.drawCircle(x,y,size,paint);
                int fret=CapoMapping.physical(note.fret,capo);
                String label=fret==31?"×":fret==0?"空":String.valueOf(fret);
                paint.setTextSize(size*.92f);paint.setColor(0xff111c34);
                canvas.drawText(label,x,y-(paint.ascent()+paint.descent())/2f,paint);
            }
            canvas.restore();
        }
        if (candy) {
            for (int lane=0;lane<4;lane++) {
                int color=candyColors[stringsReversed?3-lane:lane];
                drawCandyAvatar(canvas,lineX,top+(lane+.5f)*laneHeight,radius*1.12f,color,candyNext[lane],candyBite[lane]);
            }
        }
        drawOverlays(canvas,AppTheme.colors(),width,top,bottom,0xffdae8ff);
    }

    private void drawCandyAvatar(Canvas canvas,float lineX,float y,float radius,int color,long incoming,long eaten) {
        float facing=landingRight ? -1 : 1;
        float x=lineX-facing*radius*.68f;
        float squeeze=eaten<BITE_MS ? .14f*(float)Math.sin(Math.PI*eaten/BITE_MS) : 0;
        float halfHeight=radius*(1-squeeze);
        float mouth=Math.max(incoming<150 ? 1-incoming/150f : 0, eaten<130 ? 1-eaten/130f : 0);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fade(0xff20172f,130));
        bounds.set(x-radius,y-halfHeight+dp(2),x+radius,y+halfHeight+dp(2));
        canvas.drawRoundRect(bounds,radius*.73f,radius*.73f,paint);
        paint.setColor(color);
        bounds.set(x-radius,y-halfHeight,x+radius,y+halfHeight);
        canvas.drawRoundRect(bounds,radius*.74f,radius*.74f,paint);
        paint.setColor(fade(0xffffffff,166));
        canvas.drawCircle(x-facing*radius*.44f,y-halfHeight*.57f,radius*.17f,paint);
        paint.setColor(0xff30203e);
        canvas.drawCircle(x-facing*radius*.24f,y-halfHeight*.22f,radius*.10f,paint);
        canvas.drawCircle(x+facing*radius*.29f,y-halfHeight*.32f,radius*.10f,paint);
        float mouthX=lineX-facing*radius*.15f;
        float mouthY=y+radius*.25f, mouthH=radius*(.10f+.32f*mouth);
        bounds.set(mouthX-radius*.30f,mouthY-mouthH,mouthX+radius*.30f,mouthY+mouthH);
        canvas.drawOval(bounds,paint);
        if (eaten<BITE_MS) {
            float travel=eaten/(float)BITE_MS;
            paint.setColor(fade(color,(int)(235*(1-travel))));
            for (int i=0;i<5;i++) {
                float spread=(i-2)*.60f*radius;
                canvas.drawCircle(lineX+facing*(.24f+travel*(i*.27f+.55f))*radius,
                        y+spread*(.36f+travel),dp(1.2f)+radius*.09f*(1-travel),paint);
            }
        }
    }

    private void drawAvatar(Canvas canvas,int kind,float x,float y,float r,int color){
        paint.setColor(color);
        if(kind==0||kind==1){
            marker.reset();marker.moveTo(x-r*.94f,y-r*.4f);marker.lineTo(x-r*.86f,y-r*(kind==0?1.35f:1.02f));
            marker.lineTo(x-r*.15f,y-r*.7f);marker.lineTo(x+r*.17f,y-r*.7f);
            marker.lineTo(x+r*.86f,y-r*(kind==0?1.35f:1.02f));marker.lineTo(x+r*.94f,y-r*.4f);marker.close();
            canvas.drawPath(marker,paint);
        }else if(kind==2){
            paint.setStrokeWidth(dp(1.8f));canvas.drawLine(x,y-r*.7f,x,y-r*1.22f,paint);
            canvas.drawCircle(x,y-r*1.22f,r*.23f,paint);
        }else{
            for(int i=-1;i<=1;i++)canvas.drawCircle(x+i*r*.51f,y+r*.56f,r*.31f,paint);
        }
        bounds.set(x-r,y-r*.8f,x+r,y+r*.77f);
        canvas.drawRoundRect(bounds,r*(kind==2?.31f:.66f),r*.61f,paint);
        paint.setColor(0xff281a34);paint.setStrokeWidth(Math.max(1,r*.10f));
        canvas.drawCircle(x-r*.36f,y-r*.12f,r*.10f,paint);
        canvas.drawCircle(x+r*.36f,y-r*.12f,r*.10f,paint);
        bounds.set(x-r*.20f,y-r*.04f,x+r*.20f,y+r*.42f);
        paint.setStyle(Paint.Style.STROKE);canvas.drawArc(bounds,28,124,false,paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawOverlays(Canvas canvas,AppTheme.Colors c,float width,float top,float bottom,int accentStrong){
        if(!feedback.isEmpty()&&android.os.SystemClock.uptimeMillis()-feedbackReceivedAt<900){
            paint.setStyle(Paint.Style.FILL);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.CENTER);
            float size=dp(getWidth()>dp(500)?26:18);paint.setTextSize(size);while(size>dp(10)&&paint.measureText(feedback)>width-dp(56)){size-=dp(1);paint.setTextSize(size);}
            float labelWidth=paint.measureText(feedback)+dp(32),centerY=top+(bottom-top)/2;
            bounds.set((width-labelWidth)/2,centerY-dp(23),(width+labelWidth)/2,centerY+dp(9));
            paint.setColor(c.surface3);canvas.drawRoundRect(bounds,dp(12),dp(12),paint);paint.setColor(feedbackColor);canvas.drawText(feedback,width/2,centerY,paint);
        }
        if (countdown > 0) {
            // Keep the full score visible while the player gets into position.
            float center = width * .5f;
            float y = dp(4);
            float height = dp(controlInset > 0 ? 28 : 20);
            bounds.set(center - dp(52), y, center + dp(52), y + height);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(scene == GuideScene.CLASSIC ? c.accentSoft : 0xe52b4b65);
            canvas.drawRoundRect(bounds, height / 2, height / 2, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(controlInset > 0 ? 15 : 12));
            paint.setColor(scene == GuideScene.CLASSIC ? accentStrong : 0xffe8f4ff);
            canvas.drawText("准备  " + countdown, center, y + (height - paint.ascent() - paint.descent()) / 2, paint);
        }
    }

    /**
     * 单个音符：横向方块、独立竖线及品位数字。
     *
     * 以实际音符开始、结束为左右边界，重复音保留与设备相同的熄灯间隙，
     * 区分连续重复拨弦。仅改变绘制边界，不改音符时刻或评分判定。
     */
    private void drawNote(Canvas canvas, int laneIndex, int fret, float x, float centerY, float radius, float before, float after, boolean playing) {
        if(fret!=0)fret=CapoMapping.physical(fret,capo);
        if(x+after<=contentLeft || x-before>=contentRight)return;
        paint.setColor(playing?colors[laneIndex]:fade(colors[laneIndex],darkTheme?170:200)); paint.setStyle(Paint.Style.FILL);
        bounds.set(x - before, centerY - radius, x + after, centerY + radius);
        canvas.drawRoundRect(bounds, radius * .35f, radius * .35f, paint);
        if(playing){
            paint.setStyle(Paint.Style.STROKE);paint.setColor(fade(readableOn(colors[laneIndex]),170));paint.setStrokeWidth(dp(1.5f));
            canvas.drawRoundRect(bounds,radius*.35f,radius*.35f,paint);paint.setStyle(Paint.Style.FILL);
        }
        // Mark the attack independently from the duration body.
        paint.setColor(fade(readableOn(colors[laneIndex]),190));paint.setStrokeWidth(dp(1.5f));paint.setStrokeCap(Paint.Cap.BUTT);
        float edge=landingRight?bounds.right-dp(1):bounds.left+dp(1);
        if(bounds.width()>dp(4))canvas.drawLine(edge,centerY-radius*.70f,edge,centerY+radius*.70f,paint);
        paint.setColor(readableOn(colors[laneIndex]));
        paint.setTextAlign(Paint.Align.CENTER);
        String text = fret==31?"超界":fret==0&&capo>0?"空":String.valueOf(fret);
        float size = radius * (text.length() > 1 ? 1.0f : 1.25f);
        paint.setTextSize(size);
        // 数字只需要容得下自己：块变宽不代表数字要跟着变宽
        while (size > dp(8) && paint.measureText(text) > Math.min(radius * 1.7f,before+after-dp(4))) { size -= dp(0.5f); paint.setTextSize(size); }
        // 垂直居中：用字体度量而不是固定魔数，换字号也不会偏
        Paint.FontMetrics fm = paint.getFontMetrics();
        // Keep the complete fret numeral inside the visible part of an edge block.
        float halfText=paint.measureText(text)/2,visibleLeft=Math.max(x-before,contentLeft),visibleRight=Math.min(x+after,contentRight);
        if(visibleRight-visibleLeft>=2*halfText+dp(4)){
            float textX=Math.max(visibleLeft+halfText+dp(2),Math.min(x,visibleRight-halfText-dp(2)));
            canvas.drawText(text,textX,centerY-(fm.ascent+fm.descent)/2f,paint);
        }
    }
}
