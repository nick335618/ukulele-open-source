package com.ukulele.monitor.ui;
import android.content.Context;import android.graphics.*;import android.view.View;
import com.ukulele.monitor.CapoMapping;

/** A measured diagram of the actual 18-fret board; controls below select the draft. */
public final class CapoFretboardView extends View {
 private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private int fret;
 public CapoFretboardView(Context c){super(c);setContentDescription("模拟变调夹指板，左侧琴枕，右侧琴身");}
 public void setFret(int n){fret=CapoMapping.clamp(n);setContentDescription("变调夹预览："+(fret==0?"未夹，18品可用":"第"+fret+"品，"+(fret+1)+"至18品可用"));invalidate();}
 private float d(float n){return n*getResources().getDisplayMetrics().density;}
 private void label(Canvas c,String s,float x,float y,int color,float size,Paint.Align align){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(d(size));p.setTextAlign(align);c.drawText(s,x,y,p);}
 @Override protected void onDraw(Canvas c){
  AppTheme.Colors t=AppTheme.colors();float l=d(29),r=getWidth()-d(56),top=d(41),bottom=getHeight()-d(30),step=(r-l)/18f;
  c.drawColor(t.surface);if(r<=l||bottom<=top)return;
  p.setColor(t.surface3);c.drawRoundRect(l,top,r,bottom,d(8),d(8),p);
  if(fret>0){p.setColor((t.accent&0x00ffffff)|0x33000000);c.drawRect(l,top,l+step*fret,bottom,p);}
  p.setColor(t.stroke);p.setStrokeWidth(d(1));for(int i=0;i<=18;i++)c.drawLine(l+i*step,top,l+i*step,bottom,p);
  String[] strings={"G","C","E","A"};int[] open={67,60,64,69};
  for(int i=0;i<4;i++){float y=top+(bottom-top)*(i+.5f)/4;p.setColor(t.textTertiary);p.setStrokeWidth(d(1.1f));c.drawLine(l,y,r,y,p);label(c,strings[i],d(8),y+d(4),t.textPrimary,12,Paint.Align.LEFT);label(c,CapoMapping.note(open[i]+fret),getWidth()-d(3),y+d(4),t.accent,12,Paint.Align.RIGHT);}
  p.setColor(t.textSecondary);p.setStrokeWidth(d(3));c.drawLine(l,top,l,bottom,p);
  if(fret>0){float x=l+step*fret;p.setColor(t.accent);c.drawRoundRect(x-d(3),top-d(3),x+d(3),bottom+d(3),d(3),d(3),p);label(c,fret+"品",x,top-d(12),t.accent,13,Paint.Align.CENTER);}
  else label(c,"未夹 · 原调",l,top-d(12),t.textSecondary,13,Paint.Align.LEFT);
  for(int i=0;i<=18;i+=3)label(c,String.valueOf(i),l+i*step,bottom+d(18),t.textSecondary,10,Paint.Align.CENTER);
 }
}
