package com.ukulele.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import com.ukulele.monitor.ui.AppTheme;

final class EnvelopeCurveView extends View {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private float[] samples;
    private int duration=600;

    EnvelopeCurveView(Context context){super(context);}
    void setPreview(float[] values,int ms,int midi){
        samples=values;duration=ms;
        setContentDescription(EnvelopeState.name(midi)+" 的模型包络，以原厂峰值为百分之百，横轴时间，纵轴相对幅度");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        AppTheme.Colors c=AppTheme.colors();
        float dp=getResources().getDisplayMetrics().density;
        float left=33*dp,right=getWidth()-14*dp,top=25*dp,bottom=getHeight()-26*dp;
        if(right<=left||bottom<=top)return;
        canvas.drawColor(c.surface2);
        float ceiling=1f;
        if(samples!=null)for(float sample:samples)if(sample>ceiling)ceiling=(float)Math.ceil(sample*4)/4;
        ink.setStyle(Paint.Style.FILL);ink.setTextSize(10*dp);ink.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<=4;i++){
            float x=left+(right-left)*i/4;
            ink.setStrokeWidth(dp);ink.setColor(c.strokeStrong);canvas.drawLine(x,top,x,bottom,ink);
            ink.setColor(c.textSecondary);canvas.drawText(String.valueOf(duration*i/4),x,bottom+16*dp,ink);
            float y=bottom-(bottom-top)*i/4;
            ink.setColor(c.strokeStrong);canvas.drawLine(left,y,right,y,ink);
            if(i==0||i==4){ink.setColor(c.textSecondary);canvas.drawText(i==0?"0":Math.round(ceiling*100)+"%",left-17*dp,y+3*dp,ink);}
        }
        if(samples!=null){
            path.reset();
            for(int i=0;i<samples.length;i++){
                float x=left+(right-left)*i/(samples.length-1),y=bottom-(bottom-top)*samples[i]/ceiling;
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            ink.setStrokeJoin(Paint.Join.ROUND);ink.setStrokeWidth(2.4f*dp);ink.setStyle(Paint.Style.STROKE);
            ink.setColor(c.accent);canvas.drawPath(path,ink);ink.setStyle(Paint.Style.FILL);
            path.lineTo(right,bottom);path.lineTo(left,bottom);path.close();
            ink.setColor(AppTheme.withAlpha(c.accent,.17f));canvas.drawPath(path,ink);
        }
        ink.setTextAlign(Paint.Align.LEFT);ink.setColor(c.textSecondary);canvas.drawText("模型预览",left,15*dp,ink);
        ink.setTextAlign(Paint.Align.RIGHT);canvas.drawText("ms",right,15*dp,ink);
    }
}
