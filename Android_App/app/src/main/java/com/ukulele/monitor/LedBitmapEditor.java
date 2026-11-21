package com.ukulele.monitor;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.ukulele.monitor.ui.*;
import org.json.JSONObject;
import java.util.*;

/** Fullscreen instrument-shaped editor. Local preview never sends a device command. */
final class LedBitmapEditor extends Dialog {
 interface Listener { boolean apply(LedProfile profile); void refresh(); void ai(); }
 final Activity host; final UiKit kit; final Listener listener; final SharedPreferences prefs;
 final byte[] data=new byte[144]; final boolean[] selected=new boolean[72];
 int level=180; boolean aiBusy=false; boolean multi=false,waitingRead=false,paused=false,binding=false,wide=false;
 int focus=0; long freezeFrame=0; String draftName="本地草稿";
 TextView aiButton,count,status,apply,selection,lightSwitch,breathButton,previewButton,multiButton,brightnessText,masterText;
 SeekBar pixelBar,levelBar; Grid grid; LinearLayout root;
 final Runnable animate=new Runnable(){public void run(){if(isShowing()){grid.invalidate();grid.postDelayed(this,40);}}};
 int dp(float n){return kit.dp(n);} LedProfile profile(){return new LedProfile(data,level);}
 TextView button(String s,Runnable r){TextView t=kit.textBold(s,13,AppTheme.colors().textPrimary);t.setGravity(Gravity.CENTER);t.setMaxLines(1);t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setMinHeight(dp(wide?36:42));t.setPadding(dp(10),dp(7),dp(10),dp(7));t.setBackground(kit.ripple(AppTheme.colors().surface2,12));t.setOnClickListener(v->r.run());return t;}
 void row(LinearLayout parent,TextView... buttons){LinearLayout r=new LinearLayout(host);r.setGravity(Gravity.CENTER_VERTICAL);for(int i=0;i<buttons.length;i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(8);r.addView(buttons[i],p);}LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(6);parent.addView(r,p);}
 LedBitmapEditor(Activity h,LedProfile initial,Listener l){
  super(h,android.R.style.Theme_Material_Light_NoActionBar);host=h;listener=l;kit=UiKit.create(h);prefs=h.getSharedPreferences("custom_led_profiles_v2",0);
  LedProfile p=initial!=null?initial:LedProfile.restore(prefs.getString("draft",""));if(p!=null){System.arraycopy(p.data,0,data,0,144);level=p.level;}
  selected[0]=true;requestWindowFeature(Window.FEATURE_NO_TITLE);wide=h.getResources().getDisplayMetrics().widthPixels>h.getResources().getDisplayMetrics().heightPixels;
  build();setOnDismissListener(d->{grid.removeCallbacks(animate);prefs.edit().putString("draft",profile().stored()).apply();host.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
 }
 void build(){
  if(grid!=null)grid.removeCallbacks(animate);
  root=new LinearLayout(host){@Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(w>0&&h>0&&(w>h)!=wide){wide=w>h;post(()->{if(isShowing()){build();grid.post(animate);}});}}};
  root.setOrientation(1);root.setPadding(dp(wide?16:14),dp(6),dp(wide?16:14),dp(8));root.setBackgroundColor(AppTheme.colors().bgRoot);
  LinearLayout header=new LinearLayout(host);header.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout titles=new LinearLayout(host);titles.setOrientation(1);titles.addView(kit.textBold("灯光工作室",wide?20:23,AppTheme.colors().textPrimary));count=kit.text("",12,AppTheme.colors().textSecondary);titles.addView(count);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
  header.addView(button(wide?"竖屏":"横屏",()->host.setRequestedOrientation(wide?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)));
  TextView done=button("完成",this::dismiss);LinearLayout.LayoutParams end=new LinearLayout.LayoutParams(-2,-2);end.leftMargin=dp(8);header.addView(done,end);root.addView(header);
  aiButton=button(aiBusy?"设计中…":"✦ AI 设计",()->listener.ai());aiButton.setEnabled(!aiBusy);row(root,aiButton,button("我的配置",this::loadPattern),button("更多操作",this::more));
  grid=new Grid();LinearLayout workArea=new LinearLayout(host);if(wide){LinearLayout.LayoutParams area=new LinearLayout.LayoutParams(-1,0,1);area.topMargin=dp(8);area.bottomMargin=dp(6);root.addView(workArea,area);workArea.addView(grid,new LinearLayout.LayoutParams(0,-1,1));}else{LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,0,1);gp.topMargin=dp(8);gp.bottomMargin=dp(6);root.addView(grid,gp);}
  LinearLayout panel=new LinearLayout(host);panel.setOrientation(1);panel.setPadding(dp(12),dp(6),dp(12),dp(6));panel.setBackground(kit.bg(AppTheme.colors().surface,16));
  LinearLayout line=new LinearLayout(host);line.setGravity(Gravity.CENTER_VERTICAL);selection=kit.textBold("",13,AppTheme.colors().textPrimary);if(wide)panel.addView(selection);else line.addView(selection,new LinearLayout.LayoutParams(0,-2,1));
  multiButton=button("多选",()->{multi=!multi;changed();});multiButton.setMinWidth(dp(76));line.addView(multiButton);lightSwitch=button("点亮",()->{boolean on=data[2*focus]==0;for(int i=0;i<72;i++)if(selected[i])data[i*2]=(byte)(on?255:0);changed();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(8);line.addView(lightSwitch,lp);panel.addView(line);
  LinearLayout adjust=new LinearLayout(host);adjust.setGravity(Gravity.CENTER_VERTICAL);if(wide)adjust.setOrientation(1);brightnessText=kit.text("亮度",12,AppTheme.colors().textSecondary);adjust.addView(brightnessText,new LinearLayout.LayoutParams(wide?-1:dp(78),-2));pixelBar=kit.seekBar(AppTheme.colors().accent);pixelBar.setMax(255);adjust.addView(pixelBar,wide?new LinearLayout.LayoutParams(-1,dp(32)):new LinearLayout.LayoutParams(0,dp(36),1));breathButton=button("常亮 ▾",this::editBreath);adjust.addView(breathButton);panel.addView(adjust);
  pixelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){if(user&&!binding){for(int i=0;i<72;i++)if(selected[i])data[i*2]=(byte)n;changed();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
  if(wide){ScrollView inspector=new ScrollView(host);inspector.setFillViewport(false);inspector.addView(panel);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(220),-1);ip.leftMargin=dp(12);workArea.addView(inspector,ip);}else root.addView(panel);
  LinearLayout master=new LinearLayout(host);master.setGravity(Gravity.CENTER_VERTICAL);masterText=kit.text("",12,AppTheme.colors().textSecondary);master.addView(masterText,new LinearLayout.LayoutParams(dp(94),-2));levelBar=kit.seekBar(AppTheme.colors().accent);levelBar.setMax(255);levelBar.setProgress(level);master.addView(levelBar,new LinearLayout.LayoutParams(0,dp(34),1));previewButton=button(paused?"继续预览":"暂停预览",this::togglePreview);master.addView(previewButton);root.addView(master);
  levelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){if(user){level=n;changed();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
  status=kit.text("",12,AppTheme.colors().textSecondary);status.setMaxLines(2);status.setOnClickListener(v->{if(!previewNote.isEmpty())AppDialogs.message(host,"设计说明",previewNote);});status.setPadding(0,dp(2),0,dp(2));root.addView(status);
  apply=button("应用到乐器",this::applyDevice);apply.setTextColor(AppTheme.colors().surface);apply.setBackground(kit.ripple(AppTheme.colors().accent,12));row(root,button("保存配置",this::savePattern),apply);setContentView(root);changed();if(lastStatus!=null)update(lastStatus,lastEnabled,null);
 }
 String lastStatus="未连接 · 可编辑、预览与保存",previewNote="";boolean lastEnabled=false;
 @Override public void show(){super.show();Window w=getWindow();if(w!=null){w.setLayout(-1,-1);AppTheme.applyWindow(w);}grid.post(animate);}
 @Override public void dismiss(){prefs.edit().putString("draft",profile().stored()).apply();super.dismiss();}
 void readDevice(){waitingRead=true;listener.refresh();status.setText("正在读取乐器配置…");}
 void applyDevice(){if(listener.apply(profile())){status.setText("正在发送 · 等待乐器确认");apply.setEnabled(false);}}
 void update(String s,boolean enabled,LedProfile p){lastStatus=s;lastEnabled=enabled;status.setText((previewNote.isEmpty()?"":(previewNote.length()>20?previewNote.substring(0,20)+"…":previewNote)+"\n")+s);apply.setEnabled(enabled);apply.setAlpha(enabled?1:.45f);if(waitingRead&&p!=null){waitingRead=false;load(p);}}
 void load(LedProfile p){System.arraycopy(p.data,0,data,0,144);level=p.level;levelBar.setProgress(level);paused=false;previewButton.setText("暂停预览");changed();}
 void setAiBusy(boolean busy){aiBusy=busy;aiButton.setText(busy?"设计中…":"✦ AI 设计");aiButton.setEnabled(!busy);}
 void importAi(LedProfile.Plan plan){load(plan.profile);draftName="AI 设计";previewNote="AI预览 · "+plan.reason;update(lastStatus,lastEnabled,null);changed();}
 void fill(int v){for(int i=0;i<72;i++)data[i*2]=(byte)v;changed();}
 void choose(int i){if(multi){selected[i]=!selected[i];boolean any=false;for(boolean s:selected)any|=s;if(!any)selected[i]=true;}else{Arrays.fill(selected,false);selected[i]=true;}focus=i;changed();}
 void selectString(int s){Arrays.fill(selected,false);for(int i=s*18;i<s*18+18;i++)selected[i]=true;focus=s*18;multi=true;changed();}
 void selectFret(int r){Arrays.fill(selected,false);for(int s=0;s<4;s++)selected[s*18+r]=true;focus=r;multi=true;changed();}
 void changed(){int lit=0,n=0;for(int i=0;i<72;i++){if(data[2*i]!=0)lit++;if(selected[i])n++;}if(!selected[focus])for(int i=0;i<72;i++)if(selected[i]){focus=i;break;}
  count.setText(lit+" / 72 点亮  ·  "+draftName);selection.setText(n==1?"GCEA".charAt(focus/18)+"弦 · 第"+(focus%18+1)+"灯":"已选择 "+n+" 颗灯");
  multiButton.setText(multi?"结束多选":"多选");multiButton.setTextColor(multi?AppTheme.colors().accent:AppTheme.colors().textPrimary);
  int v=data[2*focus]&255;lightSwitch.setText(v==0?"点亮":"关闭");brightnessText.setText("亮度 "+Math.round(v*100f/255)+"%");binding=true;pixelBar.setProgress(v);binding=false;masterText.setText("总亮度 "+Math.round(level*100f/255)+"%");
  int m=data[2*focus+1]&255;breathButton.setText((m&1)==0?"常亮 ▾":((m&128)!=0?"短闪 ":"呼吸 ")+(1<<((m>>1)&3))+"s ▾");grid.invalidate();
 }
 void togglePreview(){paused=!paused;if(paused)freezeFrame=SystemClock.elapsedRealtime()/20;previewButton.setText(paused?"继续预览":"暂停预览");grid.invalidate();}
 void examples(){AppDialogs.list(host,"灵感预览",Arrays.asList(new AppDialogs.Item("星空与流星","星光错峰闪烁，一束流星划过",false),new AppDialogs.Item("流星雨","四弦错开的亮点与拖尾",false),new AppDialogs.Item("GOWIN","横屏18×4字样",false)),i->importAi(LedDesignCompiler.sample(i==0?"starfield_meteor":i==1?"meteor":"text")),"取消",null,null,null);}
 void more(){List<AppDialogs.Item> items=Arrays.asList(new AppDialogs.Item("灵感预览","星空、流星雨与GOWIN字样",false),new AppDialogs.Item("全部点亮",null,false),new AppDialogs.Item("全部关闭",null,false),new AppDialogs.Item("选择全部72灯",null,false),new AppDialogs.Item("读取乐器配置","覆盖当前编辑内容",false),new AppDialogs.Item("使用方法","点灯位编辑，点弦名选整弦，点数字选同一排",false));AppDialogs.list(host,"灯光操作",items,i->{if(i==0){examples();return;}i--;if(i==0)fill(255);if(i==1)fill(0);if(i==2){Arrays.fill(selected,true);multi=true;changed();}if(i==3)readDevice();if(i==4)AppDialogs.message(host,"编辑灯阵","点击灯位，在下方调节开关、亮度和呼吸。点弦名选择整弦，点灯位编号选择一排。多选可以组合任意灯位。所有改动先在手机预览，点击应用到乐器后才发送。横屏能更清楚地查看图案与流动方向。");},"取消",null,null,null);}
 void editBreath(){int m=data[focus*2+1]&255;List<AppDialogs.Item> items=new ArrayList<>();items.add(new AppDialogs.Item("常亮","保持当前亮度",false,(m&1)==0?"已选":null));for(int r=0;r<4;r++)items.add(new AppDialogs.Item((1<<r)+" 秒呼吸",r==0?"轻快闪烁":r==3?"舒缓起伏":"平滑明暗变化",false,(m&1)!=0&&((m>>1)&3)==r?"已选":null));for(int r=0;r<4;r++)items.add(new AppDialogs.Item((1<<r)+" 秒星光短闪","亮点渐灭，保留熄灭间隔",false));items.add(new AppDialogs.Item("同步 / 流动方向","调整选区的呼吸相位",false));AppDialogs.list(host,"灯光动态",items,i->{if(i==9){editPhase();return;}for(int n=0;n<72;n++)if(selected[n])data[2*n+1]=(byte)((data[2*n+1]&120)|(i==0?0:i<=4?1|((i-1)<<1):128|1|((i-5)<<1)));changed();},"取消",null,null,null);}
 void editPhase(){List<AppDialogs.Item> items=Arrays.asList(new AppDialogs.Item("同步呼吸","所有选中灯同时明暗",false),new AppDialogs.Item("正向流动","从第1灯向第18灯流动",false),new AppDialogs.Item("反向流动","从第18灯向第1灯流动",false),new AppDialogs.Item("交错星光","选中灯位错峰明暗",false));AppDialogs.list(host,"选区相位",items,i->{for(int n=0;n<72;n++)if(selected[n]){int phase=i==0?0:i==1?(16-n%18)%16:i==2?n%18%16:(n*7+n/18*3)%16;data[2*n+1]=(byte)((data[2*n+1]&135)|(phase<<3));}changed();},"取消",null,null,null);}
 JSONObject patterns(){try{return new JSONObject(prefs.getString("patterns","{}"));}catch(Exception e){return new JSONObject();}}
 void savePattern(){AppDialogs.prompt(host,"保存灯光配置","",s->{s=s.trim();JSONObject p=patterns();if(s.isEmpty()||s.length()>20){toast("请输入1—20字名称");return;}if(p.length()>=24&&!p.has(s)){toast("最多保存24个配置");return;}try{p.put(s,profile().stored());prefs.edit().putString("patterns",p.toString()).apply();draftName=s;changed();toast("已保存到手机");}catch(Exception e){toast("保存失败");}});}
 void loadPattern(){JSONObject p=patterns();ArrayList<String> names=new ArrayList<>();Iterator<String> it=p.keys();while(it.hasNext())names.add(it.next());Collections.sort(names);List<AppDialogs.Item> items=new ArrayList<>();for(String name:names)items.add(new AppDialogs.Item(name,"载入后可预览和编辑",false));if(items.isEmpty()){toast("还没有保存配置，先设计一幅灯光吧");return;}AppDialogs.list(host,"我的灯光配置",items,i->{String name=names.get(i);AppDialogs.list(host,name,Arrays.asList(new AppDialogs.Item("载入预览",null,false),new AppDialogs.Item("删除配置",null,true)),k->{if(k==1){p.remove(name);prefs.edit().putString("patterns",p.toString()).apply();}else{LedProfile v=LedProfile.restore(p.optString(name));if(v!=null){load(v);draftName=name;previewNote="本地预览 · 尚未应用";update(lastStatus,lastEnabled,null);changed();}}},"取消",null,null,null);},"取消",null,null,null);}
 void toast(String s){Toast.makeText(host,s,Toast.LENGTH_SHORT).show();}
 final class Grid extends View {
  final Paint p=new Paint(3);final RectF rect=new RectF();Grid(){super(host);setContentDescription("72灯可编辑灯阵，点击灯位编辑，点击弦名或编号批量选择");setFocusable(true);}
  LedMatrixGeometry geometry(){return new LedMatrixGeometry(getWidth(),getHeight(),getResources().getDisplayMetrics().density);}
  @Override protected void onMeasure(int ws,int hs){int desired=dp(430);setMeasuredDimension(resolveSize(dp(340),ws),resolveSize(desired,hs));}
  void text(Canvas c,String s,float x,float y,int color,float size){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y-(p.ascent()+p.descent())/2,p);}
  @Override protected void onDraw(Canvas c){super.onDraw(c);AppTheme.Colors a=AppTheme.colors();c.drawColor(a.surface);LedMatrixGeometry g=geometry();LedProfile model=profile();long frame=paused?freezeFrame:SystemClock.elapsedRealtime()/20;
   for(int r=0;r<18;r++){int idx=r;float x=g.horizontal?g.x(idx):dp(13),y=g.horizontal?dp(14):g.y(idx);text(c,""+(r+1),x,y,a.textSecondary,dp(g.horizontal?10:11));}
   for(int s=0;s<4;s++){float x=g.horizontal?dp(19):g.x(s*18),y=g.horizontal?g.y(s*18):dp(14);text(c,"GCEA".charAt(s)+"弦",x,y,a.accent,dp(12));}
   for(int s=0;s<4;s++){p.setColor(a.stroke);p.setStrokeWidth(dp(1));if(g.horizontal)c.drawLine(g.left,g.y(s*18),getWidth()-dp(12),g.y(s*18),p);else c.drawLine(g.x(s*18),g.top,g.x(s*18),getHeight()-dp(10),p);}
   for(int i=0;i<72;i++){float x=g.x(i),y=g.y(i),w=g.cellW*(g.horizontal?.80f:.72f),h=g.cellH*.76f;float rad=Math.min(dp(9),Math.min(w,h)/3);rect.set(x-w/2,y-h/2,x+w/2,y+h/2);int intensity=model.pwm(i,frame);float t=intensity/255f;
    p.setStyle(Paint.Style.FILL);p.setColor(blend(a.surface2,a.accent,t));c.drawRoundRect(rect,rad,rad,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(selected[i]?2:1));p.setColor(selected[i]?a.accent:a.strokeStrong);c.drawRoundRect(rect,rad,rad,p);p.setStyle(Paint.Style.FILL);
    if(selected[i]){p.setColor(a.accent);c.drawCircle(x+w/2-dp(3),y-h/2+dp(3),dp(3),p);}
    if(!g.horizontal&&g.cellH>=dp(21))text(c,(data[2*i]&255)==0?"·":(data[2*i+1]&1)!=0?"∿":"•",x,y,intensity>150?a.surface:a.textSecondary,Math.min(dp(15),h*.7f));
   }
  }
  int blend(int a,int b,float t){return Color.rgb((int)(Color.red(a)+(Color.red(b)-Color.red(a))*t),(int)(Color.green(a)+(Color.green(b)-Color.green(a))*t),(int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));}
  @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_UP){LedMatrixGeometry g=geometry();int i=g.hit(e.getX(),e.getY());if(i>=0)choose(i);else{int s=g.stringHeader(e.getX(),e.getY()),r=g.fretHeader(e.getX(),e.getY());if(s>=0)selectString(s);else if(r>=0)selectFret(r);}performClick();return true;}return true;}
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
