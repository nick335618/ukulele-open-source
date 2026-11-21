package com.ukulele.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import com.ukulele.monitor.ui.AppTheme;

final class BatteryGaugeView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt=new Path();
    private int level=-1;
    private boolean charging;

    BatteryGaugeView(Context context){
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        bolt.moveTo(18,4);
        bolt.lineTo(12.5f,12);
        bolt.lineTo(16.1f,12);
        bolt.lineTo(15,18);
        bolt.lineTo(21.5f,9.5f);
        bolt.lineTo(17.4f,9.5f);
        bolt.close();
    }

    void setState(int level,boolean charging){
        if(this.level==level&&this.charging==charging)return;
        this.level=level;
        this.charging=charging;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth()/38f,getHeight()/22f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppTheme.colors().stroke);
        canvas.drawRoundRect(1,2.5f,34,19.5f,4,4,paint);
        if(level>0){
            paint.setColor(AppTheme.colors().accent);
            int bars=Math.min(4,(level+24)/25);
            for(int i=0;i<bars;i++){
                float left=3.5f+i*7;
                canvas.drawRoundRect(left,5,left+5.5f,17,1,1,paint);
            }
        }
        paint.setColor(AppTheme.colors().textSecondary);
        canvas.drawRoundRect(35,8,38,14,1,1,paint);
        paint.setStrokeWidth(1.4f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(1,2.5f,34,19.5f,4,4,paint);
        if(charging){
            paint.setColor(AppTheme.colors().surface);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2.3f);
            canvas.drawPath(bolt,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AppTheme.colors().textPrimary);
            canvas.drawPath(bolt,paint);
        }
        canvas.restore();
    }
}
