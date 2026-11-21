package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import com.ukulele.monitor.AudioAnalysisFrame;
import com.ukulele.monitor.AudioDisplayMath;
import com.ukulele.monitor.AudioPlotGeometry;
import java.util.Arrays;
import java.util.Locale;

/** Full-rate FPGA audio: spectrum, time trace, and delayed-signal XY display. */
public final class AudioScopeView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path outline = new Path();
    private final float[] pcm = new float[256];
    private final int[] bins = new int[2048];
    private long lastFrameAt,frameInterval;
    private int triggerStart;
    private int mode, scale=1, band, xyDelay=32;
    private boolean spectrumFresh, pcmFresh, triggered=true;
    private double peak;
    private String standbyLabel="等待完整分析帧 · 需配套 FPGA 与 ESP32";
    public void setStandbyLabel(String label){standbyLabel=label;invalidate();}
    public AudioScopeView(Context c) {
        super(c);
        setContentDescription("FPGA 原始音频分析。轻触图表切换时基或延迟");
        setOnClickListener(v->{
            if(mode==1){triggered=!triggered;invalidate();}
            if(mode==2){xyDelay=xyDelay==32?8:xyDelay*2;invalidate();}
        });
    }
    public void setMode(int value) {
        if((mode==0)!=(value==0))clear();
        mode=value;invalidate();
    }
    public void toggleBand() { band=(band+1)%3;invalidate(); }
    public void toggleScale() { scale = scale == 8 ? 1 : scale * 2; invalidate(); }
    public void update(AudioAnalysisFrame frame) {
        if(mode==0?!frame.scopeOnly:frame.hasPcm){
            if(lastFrameAt>0 && frame.receivedAtMs>lastFrameAt)frameInterval=frame.receivedAtMs-lastFrameAt;
            lastFrameAt=frame.receivedAtMs;
        }
        if(frame.hasPcm){System.arraycopy(frame.pcm,0,pcm,0,pcm.length);triggerStart=AudioDisplayMath.triggerStart(pcm);pcmFresh=true;}
        if(!frame.scopeOnly){System.arraycopy(frame.bins,0,bins,0,bins.length);peak=frame.peakHz();spectrumFresh=true;}
        postInvalidateOnAnimation();
    }
    public void clear() { spectrumFresh=pcmFresh=false;Arrays.fill(pcm,0);Arrays.fill(bins,0);lastFrameAt=frameInterval=0;peak=0;invalidate(); }
    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
    /** Fit glyphs to a measured slot, including baseline and horizontal margins. */
    private void text(Canvas c,String value,float anchor,float baseline,int color,float size,
                      float minX,float maxX,int align) {
        if(maxX<=minX || value==null)return;
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(size));float available=maxX-minX;
        while(p.measureText(value)>available && p.getTextSize()>dp(8))p.setTextSize(p.getTextSize()-dp(.5f));
        if(p.measureText(value)>available){
            while(!value.isEmpty() && p.measureText(value+"…")>available)value=value.substring(0,value.length()-1);
            value=value.isEmpty()?"":value+"…";
        }
        float width=p.measureText(value);
        float x=align==0?anchor-width/2:(align>0?anchor-width:anchor);
        x=Math.max(minX,Math.min(maxX-width,x));
        Paint.FontMetrics metrics=p.getFontMetrics();
        float y=Math.max(-metrics.top,Math.min(getHeight()-metrics.bottom,baseline));
        int save=c.save();c.clipRect(minX,0,maxX,getHeight());c.drawText(value,x,y,p);c.restoreToCount(save);
    }
    private static String amplitude(float n){String s=String.format(Locale.ROOT,"%.3f",n);while(s.endsWith("0"))s=s.substring(0,s.length()-1);return s.endsWith(".")?s.substring(0,s.length()-1):s;}
    private String frequency(float hz){return hz>=1000?String.format(Locale.ROOT,"%.1fk",hz/1000).replace(".0k","k"):String.format(Locale.ROOT,"%.0f",hz);}
    @Override protected void onDraw(Canvas c) {
        AppTheme.Colors t=AppTheme.colors();int outer=c.save();
        outline.reset();outline.addRoundRect(0,0,getWidth(),getHeight(),dp(14),dp(14),Path.Direction.CW);
        c.clipPath(outline);
        try {
            c.drawColor(t.surface);
            float margin=dp(12),maxText=getWidth()-margin;
            if(getWidth()<dp(110)||getHeight()<dp(150)){
                text(c,"全屏查看图表",margin,getHeight()/2,t.textSecondary,10,margin,maxText,-1);return;
            }
            String title=mode==0?"频谱 · FPGA 4096 点 FFT":mode==1?"波形 · 原始 PCM":"李萨如 · 延迟音频 XY";
            String subtitle=mode==0?"dBFS · 显示下限 −72 dBFS":mode==1?"振幅 ×"+scale+" · 点图切换时基":"X/Y 同一音频 · ×"+scale+" · 点图换延迟";
            text(c,title,margin,dp(22),t.textPrimary,12,margin,maxText,-1);
            text(c,subtitle,margin,dp(42),t.textSecondary,10,margin,maxText,-1);
            float left=dp(48),right=getWidth()-dp(18),top=dp(62),bottom=getHeight()-dp(68);
            if(mode==2){float side=Math.min(right-left,bottom-top),cx=(left+right)/2,cy=(top+bottom)/2;left=cx-side/2;right=cx+side/2;top=cy-side/2;bottom=cy+side/2;}
            if(right-left<dp(24)||bottom-top<dp(24))return;
            float stroke=dp(1.8f);AudioPlotGeometry plot=new AudioPlotGeometry(left,top,right,bottom,stroke);
            int xDivisions=right-left<dp(220)?2:4;
            p.setStyle(Paint.Style.STROKE);p.setColor(t.stroke);p.setStrokeWidth(dp(1));
            for(int i=0;i<=4;i++){
                c.drawLine(left,top+(bottom-top)*i/4,right,top+(bottom-top)*i/4,p);
            }
            for(int i=0;i<=xDivisions;i++)c.drawLine(left+(right-left)*i/xDivisions,top,left+(right-left)*i/xDivisions,bottom,p);
            float maxHz=band==0?2000:band==1?8000:24414.0625f;
            int wavePoints=triggered?192:256;
            // Measured labels occupy slots separate from the plot and footer.
            for(int i=0;i<=4;i++){
                String label=mode==0?String.valueOf(-18*i):amplitude((1-i/2f)/scale);
                text(c,label,left-dp(7),top+(bottom-top)*i/4+dp(3),t.textSecondary,10,margin,left-dp(7),1);
            }
            for(int i=0;i<=xDivisions;i++){
                String tick=mode==0?frequency(maxHz*i/xDivisions):mode==1
                    ? String.format(Locale.ROOT,"%.2f",(wavePoints-1)*1000d*i/(48828.125*xDivisions))
                    : amplitude((2f*i/xDivisions-1)/scale);
                float x=left+(right-left)*i/xDivisions,slot=(right-left)/xDivisions;
                text(c,tick,x,bottom+dp(18),t.textSecondary,10,Math.max(left,x-slot/2),Math.min(right,x+slot/2),0);
            }
            String footer=mode==0?(peak>0?String.format(Locale.ROOT,"峰值 %.1f Hz · Δf 11.92 Hz",peak):"峰值 — · Δf 11.92 Hz"):
                mode==1?(triggered?"时间 ms · 192 点触发视窗":"时间 ms · 256 点完整采样"):
                String.format(Locale.ROOT,"延迟 %.3f ms · %d 对采样",xyDelay*1000d/48828.125,pcm.length-xyDelay);
            text(c,footer,margin,getHeight()-dp(30),t.textSecondary,10,margin,maxText,-1);
            String timing=frameInterval>0?String.format(Locale.ROOT,"数据 %.1f 帧/秒 · Fs 48,828.125 Hz",1000d/frameInterval):"Fs 48,828.125 Hz";
            text(c,timing,margin,getHeight()-dp(12),t.textSecondary,9,margin,maxText,-1);
            if(!(mode==0?spectrumFresh:pcmFresh)){text(c,standbyLabel,(left+right)/2,(top+bottom)/2,t.textSecondary,11,left+dp(4),right-dp(4),0);return;}
            path.reset();
            if(mode==0){
                int last=Math.min(2047,(int)Math.floor(maxHz*4096/48828.125));
                int columns=Math.max(1,Math.min(last+1,(int)(plot.right-plot.left)));
                for(int i=0;i<columns;i++){
                    int start=i*(last+1)/columns,end=(i+1)*(last+1)/columns;
                    float x=plot.x((start+end-1)*0.5f*(48828.125f/4096)/maxHz);
                    float y=plot.y(AudioPlotGeometry.spectrumHeight(AudioDisplayMath.peakBetween(bins,start,end)));
                    if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                // A checked frame is displayed immediately; no artificial spectrum lag.
            }else if(mode==1){
                int start=triggered?triggerStart:0;
                for(int i=0;i<wavePoints;i++){float x=plot.x(i/(wavePoints-1f)),y=plot.amplitudeY(pcm[i+start]*scale);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
            }else{
                for(int i=xyDelay;i<pcm.length;i++){float x=plot.x((pcm[i]*scale+1)/2),y=plot.amplitudeY(pcm[i-xyDelay]*scale);if(i==xyDelay)path.moveTo(x,y);else path.lineTo(x,y);}
            }
            int brightness=((t.bgRoot>>16)&255)+((t.bgRoot>>8)&255)+(t.bgRoot&255);
            p.setColor(brightness>384?0xff0b7f7a:t.accent);p.setStrokeWidth(stroke);p.setStyle(Paint.Style.STROKE);
            // Clip complete strokes as well as all data coordinates; no frame-edge overdraw.
            int save=c.save();c.clipRect(left,top,right,bottom);c.drawPath(path,p);c.restoreToCount(save);
        } finally {c.restoreToCount(outer);}
    }
}
