package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The home hero's small illustration follows the selected theme. */
public final class ThemeStamp extends View {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF box=new RectF();
    public ThemeStamp(Context context){
        super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas c){
        float side=Math.min(getWidth(),getHeight());
        if(side<=0)return;
        c.save();c.scale(side/48,side/48);
        AppTheme.Colors palette=AppTheme.colors();
        int accent=palette.accent;
        ink.setColor(palette.accentSoft);ink.setStyle(Paint.Style.FILL);
        c.drawCircle(24,24,22,ink);
        ink.setStrokeWidth(2);ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);ink.setColor(accent);
        switch(AppTheme.style()){
            case CARTOON:
                c.drawCircle(24,25,12,ink);c.drawCircle(15,15,5,ink);c.drawCircle(33,15,5,ink);
                ink.setColor(palette.surface);c.drawCircle(20,24,1.4f,ink);c.drawCircle(28,24,1.4f,ink);
                c.drawArc(20,23,28,31,10,160,false,ink);break;
            case ANIME:
                diamond(c,21,24,11,accent);diamond(c,35,13,4,accent);diamond(c,33,33,4,accent);break;
            case CYBER:
                ink.setStyle(Paint.Style.STROKE);
                path.reset();path.moveTo(11,33);path.lineTo(17,20);path.lineTo(24,25);path.lineTo(32,14);path.lineTo(39,14);
                c.drawPath(path,ink);c.drawLine(11,37,32,37,ink);c.drawLine(29,31,38,31,ink);
                diamond(c,32,14,3,accent);break;
            case MECHANICAL:
                ink.setStyle(Paint.Style.STROKE);c.drawCircle(24,24,9,ink);c.drawCircle(24,24,4,ink);
                for(int i=0;i<8;i++){c.rotate(45,24,24);c.drawLine(24,10,24,14,ink);}break;
            case PINK:
                for(int i=0;i<5;i++){c.rotate(72,24,24);box.set(20,10,28,24);c.drawOval(box,ink);}
                c.drawCircle(24,24,2.8f,ink);break;
            case RED:
                c.drawCircle(24,21,9,ink);ink.setColor(palette.accentSoft);
                c.drawRect(12,22,36,36,ink);ink.setColor(accent);c.drawLine(13,26,35,26,ink);
                c.drawLine(17,30,31,30,ink);c.drawLine(21,34,27,34,ink);break;
            case BLUE:
                ink.setStyle(Paint.Style.STROKE);
                for(int i=0;i<3;i++){
                    int y=17+i*7;path.reset();path.moveTo(10,y);path.quadTo(16,y-7,24,y);
                    path.quadTo(32,y+7,38,y);c.drawPath(path,ink);
                }break;
            default:
                ink.setTextSize(30);ink.setTextAlign(Paint.Align.CENTER);c.drawText("♪",24,34,ink);break;
        }
        c.restore();
    }
    private void diamond(Canvas c,float x,float y,float radius,int color){
        ink.setStyle(Paint.Style.FILL);ink.setColor(color);
        path.reset();path.moveTo(x,y-radius);path.lineTo(x+radius*.55f,y);path.lineTo(x,y+radius);
        path.lineTo(x-radius*.55f,y);path.close();c.drawPath(path,ink);
    }
}
