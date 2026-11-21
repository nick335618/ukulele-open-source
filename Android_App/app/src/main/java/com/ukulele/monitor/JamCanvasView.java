package com.ukulele.monitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import java.util.ArrayDeque;

final class JamCanvasView extends View {
    private static final int LIMIT=144;
    private static final long BLOOM_MS=980;
    private static final int[] COLORS={0xffffb66f,0xffef98b2,0xffffd28e,0xffc3a0ff,0xff83d9c7,0xffa6baff,
        0xffe8a5fb,0xff8addff,0xfff38b9f,0xff93efd9,0xfff8a281,0xffb3adfa};
    private final ArrayDeque<Stroke> strokes=new ArrayDeque<>();
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final Path[] ridges={new Path(),new Path(),new Path()};
    private LinearGradient sky,sea;
    private RadialGradient moonGlow;
    private int serial;
    private long last=-1;
    private float cursor;
    private Stroke previous;
    private boolean frozen;

    private static final class Stroke {
        final float x,y,px,py,size;
        final int pitch,model,color,seed;
        final long time;
        final boolean connected;
        Stroke(float x,int note,int model,int serial,long time,float pulse,Stroke last,boolean connect){
            this.x=x;
            y=.15f+.47f*(1-Math.max(0,Math.min(39,note-54))/39f);
            size=.031f+.022f*pulse;
            pitch=note;this.model=model;
            color=COLORS[Math.floorMod(note,12)];
            seed=serial;this.time=time;connected=connect;
            px=connect?last.x:x;py=connect?last.y:y;
        }
    }

    JamCanvasView(Context context){
        super(context);
        setContentDescription("琴声画境。旋律形成光轨，和弦在同一时刻展开，四种音色对应不同笔触。");
    }

    void addNotes(int[] notes,int model,long now){
        if(frozen||notes.length==0)return;
        long gap=last<0?380:Math.max(0,now-last);
        float breath=Math.min(1,gap/760f);
        cursor=(cursor+(last<0?.055f:.021f+.058f*breath))%1;
        float x=.075f+cursor*.85f;
        boolean connect=previous!=null && x>previous.x && gap<=1500;
        Stroke lead=null;
        for(int note:notes){
            if(strokes.size()==LIMIT)strokes.removeFirst();
            Stroke stroke=new Stroke(x,note,model,serial++,now,breath,previous,connect);
            if(lead==null||stroke.pitch>lead.pitch)lead=stroke;
            strokes.addLast(stroke);
        }
        previous=lead;last=now;
        invalidate();
    }

    boolean isFrozen(){return frozen;}
    boolean hasArt(){return !strokes.isEmpty();}
    void setFrozen(boolean value){frozen=value;}
    void clear(){strokes.clear();serial=0;last=-1;cursor=0;previous=null;invalidate();}

    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
        if(w<=0||h<=0)return;
        sky=new LinearGradient(0,0,w*.23f,h*.65f,new int[]{0xff0c1029,0xff303456,0xff614c66},null,Shader.TileMode.CLAMP);
        sea=new LinearGradient(0,h*.61f,0,h,new int[]{0xff303f58,0xff17283d,0xff101827},null,Shader.TileMode.CLAMP);
        moonGlow=new RadialGradient(w*.69f,h*.22f,w*.40f,new int[]{0x66797ab1,0x22797ab1,0x00797ab1},null,Shader.TileMode.CLAMP);
        for(int band=0;band<ridges.length;band++){
            Path ridge=ridges[band];ridge.reset();
            float base=(.59f+.023f*band)*h;
            ridge.moveTo(0,base);
            for(int i=0;i<=38;i++){
                float z=i/38f;
                float contour=(float)(Math.sin(z*(8+band*2)+band)*.029+Math.sin(z*21+band*1.4)*.012
                    +Math.sin(z*49+band*2)*.004);
                ridge.lineTo(z*w,base+(contour-.04f*Math.abs(z-.64f))*h);
            }
            ridge.lineTo(w,h*.67f);ridge.lineTo(0,h*.67f);ridge.close();
        }
    }

    @Override protected void onDraw(Canvas canvas){
        if(render(canvas,SystemClock.uptimeMillis(),true)&&isShown())postInvalidateOnAnimation();
    }

    Bitmap snapshot(){
        if(getWidth()<1||getHeight()<1||!hasArt())return null;
        Bitmap bitmap=Bitmap.createBitmap(getWidth(),getHeight(),Bitmap.Config.ARGB_8888);
        render(new Canvas(bitmap),SystemClock.uptimeMillis()+BLOOM_MS,false);
        return bitmap;
    }

    private boolean render(Canvas canvas,long now,boolean emptyMessage){
        float w=getWidth(),h=getHeight();
        if(w<=0||h<=0)return false;
        ink.setStyle(Paint.Style.FILL);ink.setColor(Color.WHITE);ink.setShader(sky);
        canvas.drawRect(0,0,w,h,ink);
        ink.setShader(moonGlow);canvas.drawRect(0,0,w,h*.57f,ink);ink.setShader(null);
        ink.setColor(0xffead4bc);canvas.drawCircle(w*.69f,h*.22f,w*.045f,ink);
        for(int i=0;i<91;i++){
            float x=(i*53%193)/193f*w,y=(i*23%181)/181f*h*.52f;
            ink.setColor((i%5==0?0xff:0x80)<<24|0xd9d9f6);
            canvas.drawCircle(x,y,w*(i%8==0?.0027f:.0013f),ink);
        }
        ink.setColor(0xff393c5c);canvas.drawPath(ridges[0],ink);
        ink.setColor(0xff242f49);canvas.drawPath(ridges[1],ink);
        ink.setColor(0xff192639);canvas.drawPath(ridges[2],ink);
        ink.setColor(Color.WHITE);ink.setShader(sea);canvas.drawRect(0,h*.642f,w,h,ink);ink.setShader(null);
        ink.setStrokeWidth(Math.max(1,w*.003f));
        for(int i=0;i<27;i++){
            float y=(.65f+(i*17%43)*.0077f)*h,x=(i*43%97)*w/97f;
            float length=w*(.025f+(i%8)*.012f);
            ink.setColor(i%5==0?0x55c899b6:0x3d8ba8cb);
            canvas.drawLine(x,y,Math.min(w,x+length),y,ink);
        }
        boolean changing=false;
        for(Stroke mark:strokes){
            float p=Math.min(1,Math.max(0,now-mark.time)/(float)BLOOM_MS);
            changing|=p<1;
            drawStroke(canvas,mark,p,w,h);
        }
        if(strokes.isEmpty()&&emptyMessage){
            ink.setColor(0xfff1e8f1);ink.setTextAlign(Paint.Align.CENTER);
            ink.setTextSize(17*getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("等待第一声拨弦",w/2,h*.44f,ink);
        }
        return changing;
    }

    private void drawStroke(Canvas canvas,Stroke mark,float p,float w,float h){
        float reveal=1-(1-p)*(1-p)*(1-p);
        float x=mark.x*w,y=mark.y*h,r=w*mark.size*(.15f+reveal*.85f);
        float px=mark.px*w,py=mark.py*h;
        int rgb=mark.color&0x00ffffff;
        ink.setStrokeCap(Paint.Cap.ROUND);
        if(mark.connected){
            float ex=px+(x-px)*reveal,ey=py+(y-py)*reveal;
            path.reset();path.moveTo(px,py);
            path.cubicTo(px+(ex-px)*.5f,py,px+(ex-px)*.5f,ey,ex,ey);
            ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(w*.017f);
            ink.setColor(0x28_000000|rgb);canvas.drawPath(path,ink);
            ink.setStrokeWidth(Math.max(1,w*.0035f));ink.setColor(0xaa_000000|rgb);canvas.drawPath(path,ink);
        }
        ink.setStyle(Paint.Style.FILL);
        for(int i=4;i>0;i--){
            ink.setColor((22+(4-i)*6)<<24|rgb);
            canvas.drawCircle(x,y,r*(.56f+i*.32f),ink);
        }
        ink.setStrokeWidth(Math.max(1,w*.004f));ink.setColor(0xc0_000000|rgb);
        switch(mark.model){
            case 0:
                for(int i=0;i<5;i++){
                    double a=i*1.256637+.35*mark.seed;
                    float dx=(float)Math.cos(a)*r,dy=(float)Math.sin(a)*r;
                    path.reset();path.moveTo(x,y);
                    path.quadTo(x+dx*.6f-dy*.44f,y+dy*.6f+dx*.44f,x+dx,y+dy);
                    path.quadTo(x+dx*.6f+dy*.44f,y+dy*.6f-dx*.44f,x,y);
                    canvas.drawPath(path,ink);
                }
                break;
            case 1:
                ink.setStyle(Paint.Style.STROKE);
                for(int i=0;i<3;i++){
                    float offset=(i-1)*r*.35f;
                    path.reset();path.moveTo(x-r*1.42f,y+offset);
                    path.cubicTo(x-r*.62f,y-r+offset,x+r*.46f,y+r+offset,x+r*1.42f,y+offset);
                    canvas.drawPath(path,ink);
                }
                ink.setStyle(Paint.Style.FILL);
                break;
            case 2:
                path.reset();path.moveTo(x,y-r);path.lineTo(x+r*.63f,y);path.lineTo(x,y+r);
                path.lineTo(x-r*.63f,y);path.close();canvas.drawPath(path,ink);
                ink.setColor(0xd0ffffff);canvas.drawLine(x,y-r*.8f,x,y+r*.75f,ink);
                break;
            case 3:
                ink.setStyle(Paint.Style.STROKE);
                for(int i=1;i<=3;i++){
                    float rr=r*i*.54f;
                    ink.setColor((200-i*38)<<24|rgb);canvas.drawCircle(x,y,rr,ink);
                }
                ink.setStyle(Paint.Style.FILL);
                break;
            default: break;
        }
        ink.setColor(0xeafff6eb);canvas.drawCircle(x,y,Math.max(1,r*.14f),ink);
        for(int i=0;i<6;i++){
            double a=(mark.seed*.87+i*2.4);
            float rr=r*(1.05f+i*.19f),dx=(float)Math.cos(a)*rr,dy=(float)Math.sin(a)*rr;
            ink.setColor((135-i*13)<<24|rgb);
            canvas.drawCircle(x+dx,y+dy,w*(i%3==0?.003f:.0015f),ink);
        }
        float water=.69f+(.62f-mark.y)*.49f;
        for(int i=0;i<4;i++){
            float width=r*(1.35f-i*.26f);
            ink.setColor((85-i*15)<<24|rgb);ink.setStrokeWidth(Math.max(1,w*.0025f));
            canvas.drawLine(x-width,(water+i*.010f)*h,x+width,(water+i*.010f)*h,ink);
        }
    }
}
