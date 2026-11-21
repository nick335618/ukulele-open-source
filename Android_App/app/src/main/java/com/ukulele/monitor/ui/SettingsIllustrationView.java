package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Resolution-independent setting illustrations; preference previews read the live store. */
public final class SettingsIllustrationView extends View {
    private final String kind;
    private final SettingsStore settings;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private AppTheme.Colors colors;

    public SettingsIllustrationView(Context context, String kind, SettingsStore settings) {
        super(context); this.kind = kind; this.settings = settings;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private void rounded(Canvas c, float l, float t, float r, float b, float radius, int color) {
        p.setStyle(Paint.Style.FILL); p.setColor(color); box.set(l,t,r,b); c.drawRoundRect(box,radius,radius,p);
    }
    private void line(Canvas c, float x, float y, float xx, float yy, int color, float width) {
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x,y,xx,yy,p); p.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c, String value, float x, float y, float size, int color) {
        p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTypeface(android.graphics.Typeface.DEFAULT);
        c.drawText(value,x,y,p);
    }
    private void dot(Canvas c,float x,float y,float radius,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,radius,p);}
    private void arrow(Canvas c,float x,float y,float xx,float yy,int color){
        line(c,x,y,xx,yy,color,2); double angle=Math.atan2(yy-y,xx-x);
        line(c,xx,yy,xx-(float)Math.cos(angle-.6)*7,yy-(float)Math.sin(angle-.6)*7,color,2);
        line(c,xx,yy,xx-(float)Math.cos(angle+.6)*7,yy-(float)Math.sin(angle+.6)*7,color,2);
    }
    private void phone(Canvas c,float x,float y,float w,float h,AppTheme.Colors palette,boolean selected){
        rounded(c,x-2,y-2,x+w+2,y+h+2,14,selected?colors.accent:colors.stroke);
        rounded(c,x,y,x+w,y+h,12,palette.bgRoot);
        rounded(c,x+7,y+13,x+w-7,y+35,5,palette.surface2);
        rounded(c,x+12,y+19,x+20,y+27,3,palette.accent);
        line(c,x+27,y+22,x+w-14,y+22,palette.textSecondary,2);
        for(int i=0;i<3;i++)rounded(c,x+7,y+43+i*16,x+w-7,y+54+i*16,4,palette.surface2);
        rounded(c,x+w/2-10,y+h-8,x+w/2+10,y+h-5,2,palette.textTertiary);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c); colors=AppTheme.colors();
        float zoom=Math.min(getWidth()/300f,getHeight()/152f);
        c.save(); c.translate((getWidth()-300*zoom)/2,(getHeight()-152*zoom)/2); c.scale(zoom,zoom);
        rounded(c,0,0,300,152,14,colors.surface2);
        switch(kind){
            case "appearance": appearance(c);break;
            case "display": display(c);break;
            case "gestures": gestures(c);break;
            case "connection": connection(c);break;
            case "power": power(c);break;
            case "touch": touch(c);break;
            case "opto": optical(c);break;
            case "feedback": feedback(c);break;
            case "storage": storage(c);break;
        }
        c.restore();
    }
    private void appearance(Canvas c){
        String theme=settings.themeMode();
        phone(c,17,16,62,100,AppTheme.darkColors(),SettingsStore.THEME_DARK.equals(theme));
        phone(c,94,16,62,100,AppTheme.lightColors(),SettingsStore.THEME_LIGHT.equals(theme));
        text(c,"深色",35,136,11,colors.textSecondary);text(c,"浅色",112,136,11,colors.textSecondary);
        text(c,"字号预览",185,36,11,colors.textSecondary);
        String[] names={"小","标准","大"};
        for(int i=0;i<3;i++)text(c,names[i],185,61+i*28,12+i*3,i==settings.textScale()?colors.accent:colors.textTertiary);
        if(SettingsStore.THEME_SYSTEM.equals(theme))text(c,"跟随系统",181,141,11,colors.accent);
    }
    private void display(Canvas c){
        text(c,"跟弹画面示例",16,26,11,colors.textSecondary);
        for(int i=0;i<4;i++)line(c,22,47+i*18,278,47+i*18,colors.strokeStrong,1.5f);
        String[][] labels={{"C","D","E"},{"do","re","mi"},{"0","2","4"}};
        String[] notes=labels[settings.noteStyle()];
        for(int i=0;i<3;i++){
            rounded(c,48+i*72,48+i*12,96+i*72,76+i*12,8,colors.accentSoft);
            text(c,notes[i],61+i*72,67+i*12,15,colors.accent);
        }
        if(settings.showFingering()){dot(c,26,101,5,colors.warn);text(c,"指法提示",16,135,11,colors.warn);}
        text(c,new String[]{"音名显示","唱名显示","品位数字"}[settings.noteStyle()],217,135,11,colors.textSecondary);
    }
    private void gestures(Canvas c){
        rounded(c,12,12,144,140,10,colors.surface);rounded(c,156,12,288,140,10,colors.surface);
        text(c,"滑音",26,35,13,colors.textPrimary);text(c,"颤音",170,35,13,colors.textPrimary);
        for(int i=0;i<4;i++)line(c,25,54+i*14,130,54+i*14,colors.strokeStrong,1);
        for(int i=0;i<4;i++)line(c,37+i*27,50,37+i*27,100,colors.strokeStrong,1);
        dot(c,47,68,7,colors.accent);arrow(c,60,68,115,68,colors.accent);
        Path wave=new Path();
        for(int i=0;i<=100;i++){float x=171+i,y=78+(float)Math.sin(i*.15)*13;if(i==0)wave.moveTo(x,y);else wave.lineTo(x,y);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setColor(colors.warn);c.drawPath(wave,p);p.setStyle(Paint.Style.FILL);
        text(c,"沿同弦移动品位",25,124,10,colors.textSecondary);text(c,"原品位轻揉琴弦",170,124,10,colors.textSecondary);
    }
    private void connection(Canvas c){
        phone(c,24,22,54,98,colors,false);
        for(int i=0;i<4;i++)dot(c,105+i*23,72,3,colors.accent);
        rounded(c,217,32,253,122,14,colors.accentSoft);
        rounded(c,228,14,242,66,4,colors.accent);dot(c,235,88,10,colors.surface2);
        for(int i=0;i<4;i++)line(c,230+i*3,23,230+i*3,111,colors.textSecondary,.7f);
        text(c,"手机",40,140,11,colors.textSecondary);text(c,"蓝牙连接",113,107,11,colors.accent);text(c,"尤克里里",213,140,11,colors.textSecondary);
    }
    private void power(Canvas c){
        phone(c,28,18,64,100,colors,true);
        rounded(c,204,16,272,120,14,colors.stroke);rounded(c,206,18,270,118,12,colors.bgRoot);
        dot(c,237,65,18,colors.warn);dot(c,245,57,18,colors.bgRoot);
        arrow(c,114,68,185,68,colors.textTertiary);
        text(c,"正常演奏",34,140,11,colors.textSecondary);text(c,"自动休眠",215,140,11,colors.textSecondary);
        text(c,"空闲一段时间",116,97,10,colors.textSecondary);
    }
    private void touch(Canvas c){
        text(c,"揉弦变化",16,27,12,colors.textPrimary);
        line(c,26,105,276,105,colors.strokeStrong,1);
        float[] heights={15,25,20,49,36,65,52,30,18,43};
        for(int i=0;i<heights.length;i++)rounded(c,29+i*24,105-heights[i],41+i*24,105,4,heights[i]>44?colors.accent:colors.surface4);
        for(int x=24;x<278;x+=12)line(c,x,61,x+6,61,colors.warn,1.5f);
        text(c,"触发门限",222,52,10,colors.warn);text(c,"超过门限的变化才触发颤音",26,132,11,colors.textSecondary);
    }
    private void optical(Canvas c){
        rounded(c,22,45,61,104,10,colors.surface4);rounded(c,239,45,278,104,10,colors.surface4);
        for(int x=73;x<234;x+=14)line(c,x,75,x+7,75,colors.accent,2);
        rounded(c,143,25,153,121,5,colors.warn);arrow(c,164,40,196,40,colors.warn);
        text(c,"发射",29,128,11,colors.textSecondary);text(c,"接收",246,128,11,colors.textSecondary);
        text(c,"琴弦拨动改变光路",96,143,11,colors.textSecondary);
    }
    private void feedback(Canvas c){
        phone(c,47,20,59,102,colors,false);
        int vibration=settings.hapticsEnabled()?colors.accent:colors.textTertiary;
        line(c,30,52,23,65,vibration,2);line(c,23,65,30,79,vibration,2);
        line(c,123,52,130,65,vibration,2);line(c,130,65,123,79,vibration,2);
        int sound=settings.soundEnabled()?colors.warn:colors.textTertiary;
        rounded(c,202,57,219,88,4,sound);
        Path speaker=new Path();speaker.moveTo(214,59);speaker.lineTo(234,45);speaker.lineTo(234,99);speaker.lineTo(214,86);speaker.close();p.setColor(sound);c.drawPath(speaker,p);
        if(settings.soundEnabled()){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);box.set(224,49,258,95);c.drawArc(box,-65,130,false,p);p.setStyle(Paint.Style.FILL);}
        text(c,settings.hapticsEnabled()?"震动已开启":"震动已关闭",43,142,11,colors.textSecondary);
        text(c,settings.soundEnabled()?"提示音已开启":"提示音已关闭",186,142,11,colors.textSecondary);
    }
    private void storage(Canvas c){
        rounded(c,30,30,101,118,12,colors.accentSoft);
        rounded(c,45,32,86,57,4,colors.accent);
        rounded(c,43,76,88,117,6,colors.surface);
        arrow(c,121,56,181,56,colors.accent);arrow(c,181,96,121,96,colors.warn);
        rounded(c,201,32,270,116,12,colors.surface4);
        for(int i=0;i<3;i++)line(c,216,53+i*19,255,53+i*19,colors.textSecondary,2);
        text(c,"已保存配置",32,139,11,colors.textSecondary);text(c,"乐器当前参数",201,139,11,colors.textSecondary);
    }
}
