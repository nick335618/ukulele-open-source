package com.ukulele.monitor;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.*;import android.graphics.pdf.PdfRenderer;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import java.util.concurrent.*;import com.ukulele.monitor.ui.*;
/** In-app private original viewer plus explicit, editable offline recognition draft. */
public final class ScoreDocumentActivity extends Activity {
 ScoreRepository repo;ScoreRepository.Item item;ImageSheet sheet;TextView pageLabel;int page,pageCount=1,rotation,generation;boolean destroyed;ExecutorService worker=Executors.newSingleThreadExecutor();UiKit kit;EditText activeNotationEditor;String restoredNotation;float uiScale=1f;
 @Override public void onCreate(Bundle state){super.onCreate(state);try{repo=new ScoreRepository(this);item=repo.get(getIntent().getStringExtra("id"));if(state!=null){page=state.getInt("page");rotation=state.getInt("rotation");restoredNotation=state.getString("notation_editing");}
  // 跟随用户在设置里选的「界面字号」：本页原先完全不读该设置，
  // 导致从主界面进入这里时字体会突然变小，与全局设定不一致。
  try{uiScale=fontScale(SettingsStore.store(this).textScale());}catch(Exception ignored){uiScale=1f;}
  kit=UiKit.create(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(kit.dp(16),kit.dp(14),kit.dp(16),kit.dp(14));root.setBackgroundColor(AppTheme.colors().bgRoot);
  TextView title=scaled(kit.title(item.title));root.addView(title);sheet=new ImageSheet(this);root.addView(sheet,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout bar=new LinearLayout(this);pageLabel=scaled(kit.body(""));bar.addView(button("‹",()->{if(page>0){page--;render();}}));bar.addView(pageLabel,new LinearLayout.LayoutParams(0,-2,1));bar.addView(new Space(this),new LinearLayout.LayoutParams(kit.dp(8),1));bar.addView(button("›",()->{if(page+1<pageCount){page++;render();}}));bar.addView(new Space(this),new LinearLayout.LayoutParams(kit.dp(8),1));bar.addView(button("旋转",()->{rotation=(rotation+90)%360;render();}));root.addView(bar);
  LinearLayout actions=new LinearLayout(this);addAction(actions,button("识别乐谱",this::chooseRecognition),true);addAction(actions,button("校对 / 编辑",this::editDraft),false);LinearLayout.LayoutParams actionsLp=new LinearLayout.LayoutParams(-1,-2);actionsLp.topMargin=kit.dp(10);root.addView(actions,actionsLp);
  LinearLayout guideActions=new LinearLayout(this);addAction(guideActions,button("生成指引",this::generateGuide),true);addAction(guideActions,button("返回",this::finish),false);LinearLayout.LayoutParams guideLp=new LinearLayout.LayoutParams(-1,-2);guideLp.topMargin=kit.dp(10);root.addView(guideActions,guideLp);if(MidiOctaveEditor.supports(item))root.addView(button("整体升降八度",()->MidiOctaveEditor.show(this,repo,item.id,()->{setResult(RESULT_OK,new Intent().putExtra("id",item.id));finish();})));
  setContentView(root);render();
 }catch(Exception e){Toast.makeText(this,"打开原谱失败："+e.getMessage(),Toast.LENGTH_LONG).show();finish();}}
 /** 字号档位 → 倍率，与 MainActivity 保持一致。 */
 static float fontScale(int scale){if(scale==SettingsStore.TEXT_SCALE_SMALL)return .88f;if(scale==SettingsStore.TEXT_SCALE_LARGE)return 1.16f;return 1f;}
 /** 按用户字号档位重设字号（UiKit 内部字号固定，签名不可改，只能在取回后缩放）。 */
 TextView scaled(TextView v){if(v!=null&&uiScale!=1f){float d=getResources().getDisplayMetrics().scaledDensity;if(d>0f)v.setTextSize(v.getTextSize()/d*uiScale);}return v;}
 // 按钮：字号随档位缩放；去掉 maxLines(2) 硬限行数 —— 长标签（如“生成可用部分并练习”）
 // 在大字号下会被截断且没有省略号。minHeight 会让按钮自然变高。
 TextView button(String text,Runnable fn){TextView v=scaled(kit.textBold(text,15,AppTheme.colors().accent));v.setGravity(Gravity.CENTER);v.setPadding(kit.dp(12),kit.dp(14),kit.dp(12),kit.dp(14));v.setMinHeight(kit.dp(48));v.setBackground(kit.ripple(text.equals("生成指引")?AppTheme.colors().accent:AppTheme.colors().surface2,14));if(text.equals("生成指引"))v.setTextColor(AppTheme.colors().bgRoot);v.setOnClickListener(w->fn.run());return v;}
 void addAction(LinearLayout row,TextView button,boolean gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(gap)lp.rightMargin=kit.dp(10);row.addView(button,lp);}
 void generateGuide(){if(new File(repo.folder(item.id),"jianpu.txt").exists()){editNotation();return;}try{String text=new String(ScoreRepository.readAtomic(new File(repo.folder(item.id),"draft.txt")),"UTF-8");confirmGuide(text,null);}catch(Exception e){com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("先识别乐谱").setMessage("识别并校对音符后即可直接生成跟弹与灯光指引，不需要导出MIDI。").setPositiveButton("识别乐谱",(d,w)->chooseRecognition()).setNegativeButton("返回",null).show();}}
 void confirmGuide(final String text,final AlertDialog editor){
  try{
   ScoreGuideBuilder.Built result=ScoreGuideBuilder.build(text);
   // 正文里含页级警告（可能很长），原来用 setMessage —— 对话框正文区不滚动，
   // 超出部分会被永久截断。现在走 AppDialogs：正文可滚动、字号跟随主题与设置。
   StringBuilder body=new StringBuilder();
   body.append("已读取 ").append(soundingNotes(result.draft.notes)).append(" 个发声音符，指引时长 ")
       .append(String.format(java.util.Locale.ROOT,"%.1f",result.timeline.durationMs/1000.0)).append(" 秒。")
       .append(result.mapped.warning()).append("。");
   String trans=transposeSuggestion(result.draft.notes);
   if(trans!=null&&!trans.isEmpty())body.append("\n").append(trans);
   if(item.warning!=null&&!item.warning.isEmpty())body.append("\n").append(item.warning);
   body.append("\n\n请确认原谱的音高、节奏与反复已校对。");
   final boolean partial=result.mapped.outside+result.mapped.overflow>0;
   AppDialogs.message(this,"生成曲谱指引",body.toString(),
     partial?"生成可用部分并练习":"生成指引并练习",
     new android.content.DialogInterface.OnClickListener(){
       @Override public void onClick(android.content.DialogInterface d,int w){saveGuide(text,editor,true);}
     },
     "返回校对",
     new android.content.DialogInterface.OnClickListener(){
       @Override public void onClick(android.content.DialogInterface d,int w){if(editor==null)editDraft();}
     },
     "生成并保存",
     new android.content.DialogInterface.OnClickListener(){
       @Override public void onClick(android.content.DialogInterface d,int w){saveGuide(text,editor,false);}
     });
  }catch(Exception e){Toast.makeText(this,e.getMessage(),1).show();}
 }
 static String transposeSuggestion(java.util.List<ScorePitch.Note> notes){boolean need=false;for(ScorePitch.Note n:notes)if(n.midi>=0){need|=n.midi<60;if(n.midi+12<60||n.midi+12>86)return "";}return need?"可在简谱调整中升高一个八度，完整适配本琴音域；原谱保持不变。":"";}
 static int soundingNotes(java.util.List<ScorePitch.Note> notes){int count=0;for(ScorePitch.Note note:notes)if(note.midi>=0)count++;return count;}
 void saveGuide(String text,AlertDialog editor,boolean practice){try{ScoreGuideBuilder.save(repo,item.id,text);setResult(RESULT_OK,new Intent().putExtra("id",item.id).putExtra("guide_action",practice?"practice":"select"));if(editor!=null&&editor.isShowing())editor.dismiss();Toast.makeText(this,"指引已生成，原谱保留",0).show();finish();}catch(Exception e){Toast.makeText(this,"生成失败："+e.getMessage(),1).show();}}
 Bitmap pageBitmap(int number)throws Exception{
  Bitmap bitmap;
  if(item.mime.equals("application/pdf")){try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(repo.source(item.id),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){pageCount=pdf.getPageCount();if(pageCount==0)throw new IOException("PDF没有页面");try(PdfRenderer.Page p=pdf.openPage(Math.min(number,pageCount-1))){float factor=Math.min(1600f/p.getWidth(),2200f/p.getHeight());bitmap=Bitmap.createBitmap(Math.max(1,(int)(p.getWidth()*factor)),Math.max(1,(int)(p.getHeight()*factor)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}}}
  else{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(repo.source(item.id).getPath(),o);if(o.outWidth<1)throw new IOException("不是支持的照片/PDF");o.inSampleSize=Math.max(1,(int)Math.floor(Math.max(o.outWidth/1600.0,o.outHeight/2200.0)));o.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeFile(repo.source(item.id).getPath(),o);if(bitmap==null)throw new IOException("照片解码失败");float factor=Math.min(1f,Math.min(1600f/bitmap.getWidth(),2200f/bitmap.getHeight()));if(factor<1f){Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*factor)),Math.max(1,Math.round(bitmap.getHeight()*factor)),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}}
  if(!item.mime.equals("application/pdf")){
   float[] paper=OpenCvPaper.outline(bitmap);
   if(paper!=null){float w1=(float)Math.hypot(paper[2]-paper[0],paper[3]-paper[1]),w2=(float)Math.hypot(paper[4]-paper[6],paper[5]-paper[7]),h1=(float)Math.hypot(paper[6]-paper[0],paper[7]-paper[1]),h2=(float)Math.hypot(paper[4]-paper[2],paper[5]-paper[3]);int pw=Math.max(40,Math.round((w1+w2)/2)),ph=Math.max(40,Math.round((h1+h2)/2));Matrix transform=new Matrix();float[] dest={0,0,pw-1,0,pw-1,ph-1,0,ph-1};if(transform.setPolyToPoly(paper,0,dest,0,4)){Bitmap corrected=Bitmap.createBitmap(pw,ph,Bitmap.Config.ARGB_8888);corrected.eraseColor(Color.WHITE);new Canvas(corrected).drawBitmap(bitmap,transform,new Paint(Paint.FILTER_BITMAP_FLAG));bitmap.recycle();bitmap=corrected;}}
  }
  if(bitmap.hasAlpha()){Bitmap paper=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);paper.eraseColor(Color.WHITE);new Canvas(paper).drawBitmap(bitmap,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));bitmap.recycle();bitmap=paper;}
  if(rotation!=0){Matrix m=new Matrix();m.postRotate(rotation);Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}return bitmap;
 }
 void render(){final int token=++generation;pageLabel.setText("正在加载…");worker.execute(()->{try{Bitmap bitmap=pageBitmap(page);runOnUiThread(()->{if(destroyed||token!=generation){bitmap.recycle();return;}sheet.setBitmap(bitmap);pageLabel.setText((page+1)+" / "+pageCount);if(restoredNotation!=null){String saved=restoredNotation;restoredNotation=null;editNotation(saved);}});}catch(Exception e){runOnUiThread(()->{if(!destroyed){pageLabel.setText("原谱已保留");Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});}});}
 void chooseRecognition(){
  java.util.List<AppDialogs.Item> rows=new java.util.ArrayList<>();
  rows.add(new AppDialogs.Item("简谱 · 单旋律","读取调号、拍号、八度点、附点、减时线、延音线与休止",false));
  rows.add(new AppDialogs.Item("尤克里里四线 TAB","保留原指法与配套五线谱节奏",false));
  rows.add(new AppDialogs.Item("吉他六线 TAB","六线谱，低音音域可能超出可用范围",false));
  rows.add(new AppDialogs.Item("五线谱 · 高音谱号","按高音谱号音高读取",false));
  rows.add(new AppDialogs.Item("五线谱 · 低音谱号","按低音谱号音高读取",false));
  AppDialogs.list(this,"选择谱型",rows,new AppDialogs.OnPick(){
   @Override public void picked(int type){recognitionOptions(type);}
  },null,null,null,null);
 }

 /** 选定谱型后填写速度与调号，再开始识别。 */
 void recognitionOptions(final int type){
  LinearLayout form=new LinearLayout(this);form.setOrientation(1);
  EditText tempo=new EditText(this);tempo.setHint("速度 BPM（四分音符每分钟）");tempo.setInputType(2);tempo.setText("120");
  EditText key=new EditText(this);key.setHint("简谱调号：自动，或 F4 / D4 等");key.setText(type==0?"自动":"C4");
  AppDialogs.themeInput(tempo,15,false);AppDialogs.themeInput(key,15,false);
  LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.bottomMargin=kit.dp(10);
  form.addView(tempo,fp);
  form.addView(key,new LinearLayout.LayoutParams(-1,-2));
  java.util.List<AppDialogs.Action> actions=new java.util.ArrayList<>();
  actions.add(new AppDialogs.Action("取消",null,false));
  actions.add(new AppDialogs.Action("识别全部页",new android.content.DialogInterface.OnClickListener(){
   @Override public void onClick(android.content.DialogInterface d,int w){startRecognition(type,tempo,key,true);}
  },false));
  actions.add(new AppDialogs.Action("识别当前页",new android.content.DialogInterface.OnClickListener(){
   @Override public void onClick(android.content.DialogInterface d,int w){startRecognition(type,tempo,key,false);}
  },true));
  AppDialogs.form(this,"离线识谱草稿",
   wrapForm(form,"简谱可尝试读取调号、拍号、八度点、附点、减时线、延音线和休止。反复、连音和装饰音须校对；草稿不是已确认的演奏曲谱。四线 TAB 保留原指法与配套五线谱节奏。"),
   actions,22);
 }

 private View wrapForm(View form,String note){
  LinearLayout box=new LinearLayout(this);box.setOrientation(1);
  TextView hint=scaled(kit.text(note,13,AppTheme.colors().textSecondary));
  hint.setLineSpacing(kit.dp(3),1f);
  box.addView(hint,new LinearLayout.LayoutParams(-1,-2));
  TextView gap=new TextView(this);box.addView(gap,new LinearLayout.LayoutParams(1,kit.dp(12)));
  box.addView(form,new LinearLayout.LayoutParams(-1,-2));
  return box;
 }

 private void startRecognition(int type,EditText tempo,EditText key,boolean all){
  try{
   int bpm=Integer.parseInt(tempo.getText().toString());
   int tonic=recognitionTonic(type,key.getText().toString());
   if(bpm<30||bpm>240||tonic<-1)throw new Exception();
   recognize(type,tonic,bpm,all);
  }catch(Exception e){Toast.makeText(this,all?"参数不正确":"请输入30—240 BPM和有效音名",0).show();}
 }
 void recognize(int type,int tonic,int bpm,boolean all){final ProgressDialog progress=com.ukulele.monitor.ui.AppDialogs.progress(this,"正在本机识谱…");final int first=all?0:page,count=all?pageCount:page+1;final Rect crop=all?null:sheet.visibleCrop();
 worker.execute(()->{try{if(count-first>40)throw new IOException("超过40页，请分段识别");ArrayList<ScorePitch.Note> notes=new ArrayList<>();String warning="";StringBuilder numbered=new StringBuilder();int unresolved=0;boolean needsReview=false;long offset=0;int activeTonic=tonic;List<OfflineScoreRecognizer.Glyph> templates=templates(type);
  for(int n=first;n<count;n++){Bitmap bmp=pageBitmap(n);if(crop!=null&&crop.width()>10&&crop.height()>10&&crop.right<=bmp.getWidth()&&crop.bottom<=bmp.getHeight()){Bitmap cut=Bitmap.createBitmap(bmp,crop.left,crop.top,crop.width(),crop.height());if(cut!=bmp){bmp.recycle();bmp=cut;}}try{int[] pixels=new int[bmp.getWidth()*bmp.getHeight()];bmp.getPixels(pixels,0,bmp.getWidth(),0,0,bmp.getWidth(),bmp.getHeight());OfflineScoreRecognizer.Result result=OfflineScoreRecognizer.recognize(pixels,bmp.getWidth(),bmp.getHeight(),type,activeTonic,bpm,templates);if(activeTonic<0&&result.detectedTonic>=0)activeTonic=result.detectedTonic;for(ScorePitch.Note note:result.notes)notes.add(new ScorePitch.Note(offset+note.time,note.midi,note.preferredColumn));if(type==OfflineScoreRecognizer.NUMERIC&&result.notation!=null){String pageText=result.notation;if(numbered.length()>0){pageText=pageText.replaceAll("(?m)^#.*$","").replaceAll("(?m)^BPM=.*$","").replaceAll("(?m)^反复=.*$","").replaceAll("(?m)^拍号=.*$","");}numbered.append(pageText).append('\n');unresolved+=result.unresolved;}offset+=result.durationMs;needsReview|=result.requiresReview;warning+=(warning.isEmpty()?"":"\n")+"第"+(n+1)+"页："+result.warning;}finally{bmp.recycle();}}
  String text=(unresolved>0?"# unresolved="+unresolved+"\n":"")+ScoreDraft.text(notes,offset);backupDraft("draft.txt");backupDraft("jianpu.txt");if(type==OfflineScoreRecognizer.NUMERIC)ScoreRepository.atomic(new File(repo.folder(item.id),"jianpu.txt"),numbered.toString().getBytes("UTF-8"));else if(new File(repo.folder(item.id),"jianpu.txt").exists()&&!new File(repo.folder(item.id),"jianpu.txt").delete())throw new IOException("无法切换谱型，原草稿已保留");ScoreRepository.atomic(new File(repo.folder(item.id),"draft.txt"),text.getBytes("UTF-8"));item=repo.get(item.id);item.bpm=bpm;item.type="离线草稿";item.status="待校对";item.warning=warning;repo.write(item);
  final boolean review=needsReview;runOnUiThread(()->{if(!destroyed){progress.dismiss();if(review)editDraft();else confirmGuide(text,null);}});
 }catch(Exception e){runOnUiThread(()->{if(!destroyed){progress.dismiss();com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("原谱已保留").setMessage("没有生成可靠草稿："+e.getMessage()+"。可以继续查看原谱或手动录入校对。").setPositiveButton("手动校对",(d,w)->{if(type==OfflineScoreRecognizer.NUMERIC&&!new File(repo.folder(item.id),"jianpu.txt").exists())editNotation(manualNotation(tonic,bpm));else editDraft();}).setNegativeButton("返回",null).show();}});}});}
 List<OfflineScoreRecognizer.Glyph> templates(int type){ArrayList<OfflineScoreRecognizer.Glyph> result;try{result=type==OfflineScoreRecognizer.NUMERIC?new JianpuRecognizer.Templates(JianpuDigitModel.read(getAssets().open("jianpu_digit_model.txt"))):new ArrayList<>();}catch(IOException error){throw new IllegalStateException("简谱数字模型缺失或损坏",error);}for(Typeface font:new Typeface[]{Typeface.DEFAULT,Typeface.SERIF,Typeface.MONOSPACE,Typeface.DEFAULT_BOLD,Typeface.create(Typeface.SERIF,Typeface.BOLD)})for(int number=0;number<=9;number++){
  Bitmap b=Bitmap.createBitmap(80,90,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);Canvas c=new Canvas(b);Paint p=new Paint();p.setColor(Color.BLACK);p.setTypeface(font);p.setTextSize(60);c.drawText(""+number,10,70,p);int[] pix=new int[7200];b.getPixels(pix,0,80,0,0,80,90);boolean[] ink=new boolean[7200];int x0=80,y0=90,x1=0,y1=0;for(int i=0;i<pix.length;i++)if((pix[i]&255)<165){ink[i]=true;x0=Math.min(x0,i%80);x1=Math.max(x1,i%80);y0=Math.min(y0,i/80);y1=Math.max(y1,i/80);}result.add(new OfflineScoreRecognizer.Glyph(number,OfflineScoreRecognizer.normalized(ink,80,new OfflineScoreRecognizer.Box(x0,y0,x1-x0+1,y1-y0+1))));b.recycle();}try{OfflineScoreRecognizer.loadTemplates(getAssets().open("score_digit_templates.txt"),result);}catch(IOException e){throw new IllegalStateException("识谱模板缺失",e);}if(type==OfflineScoreRecognizer.NUMERIC)try{JianpuRecognizer.loadTemplates(getAssets().open("jianpu_templates.txt"),result);}catch(IOException e){throw new IllegalStateException("简谱模板缺失",e);}return result;}
 static int recognitionTonic(int type,String text){if(type==OfflineScoreRecognizer.NUMERIC&&text.trim().equals("自动"))return -1;int midi=ScoreDraft.pitch(text);return midi<0?-2:midi;}
 static int parseNote(String text){return ScoreDraft.pitch(text);}
 void editDraft(){
  if(new File(repo.folder(item.id),"jianpu.txt").exists()){editNotation();return;}
  if(new File(repo.folder(item.id),"draft.txt").exists()){editEventDraft();return;}
  java.util.List<AppDialogs.Item> rows=new java.util.ArrayList<>();
  rows.add(new AppDialogs.Item("简谱数字与节奏","1—7 表示音级，0 休止，- 延长一拍",false));
  rows.add(new AppDialogs.Item("音名与毫秒时间（高级）","直接写 C4、E4 与毫秒时间，适合精确校正",false));
  AppDialogs.list(this,"创建校对稿",rows,new AppDialogs.OnPick(){
   @Override public void picked(int w){if(w==0)editNotation(manualNotation(-1,120));else editEventDraft();}
  },null,null,null,null);
 }
 static String manualNotation(int tonic,int bpm){return "# 手动校对稿：请先确认主音、节奏，再替换 ?\n1="+ScorePitch.name(tonic>=0?tonic:60)+"\nBPM="+bpm+"\n反复=否\n?\n";}
 void editEventDraft(){EditText editor=new EditText(this);editor.setGravity(Gravity.TOP);editor.setMinLines(8);editor.setHint("0,C4\n500,E4\n1000,G4");try{editor.setText(new String(ScoreRepository.readAtomic(new File(repo.folder(item.id),"draft.txt")),"UTF-8"));}catch(Exception ignored){}
  ScrollView box=new ScrollView(this);box.addView(editor);AlertDialog dialog=com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("校对音高、时间与指法").setMessage(item.warning==null?"":item.warning).setView(box).setNegativeButton("保存草稿",(d,w)->saveDraftText(editor.getText().toString())).setNeutralButton("整体移调",null).setPositiveButton("生成指引",null).create();dialog.setOnShowListener(w->{dialog.getButton(-3).setOnClickListener(v->com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("整体移调（原谱不变，指法重新映射）").setItems(new String[]{"降低一个八度","升高一个八度","升高两个八度"},(d,which)->{int delta=which==0?-12:which==1?12:24;try{StringBuilder transposed=new StringBuilder();for(String line:editor.getText().toString().split("\\r?\\n")){if(line.trim().isEmpty()||line.trim().startsWith("#")){transposed.append(line).append('\n');continue;}String[] parts=line.trim().split("[,，\\s]+");if(parts.length>1&&parts[1].equalsIgnoreCase("REST")){transposed.append(line).append('\n');continue;}int note=parseNote(parts[1])+delta;if(note<0||note>127)throw new Exception();transposed.append(parts[0]).append(',').append(ScorePitch.name(note)).append('\n');}editor.setText(transposed.toString());}catch(Exception e){Toast.makeText(this,"无法移调，请先修正草稿格式",0).show();}}).setNegativeButton("取消",null).show());dialog.getButton(-1).setOnClickListener(v->confirmGuide(editor.getText().toString(),dialog));});dialog.show();}
 void backupDraft(String name)throws Exception{File source=new File(repo.folder(item.id),name);if(source.exists())ScoreRepository.atomic(new File(repo.folder(item.id),"previous-"+name),ScoreRepository.readAtomic(source));}
 void editNotation(){editNotation(null);}
 void editNotation(String restored){
  if(sheet.bitmap==null){Toast.makeText(this,"原谱正在加载，请稍后",0).show();return;}
  LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(kit.dp(12),kit.dp(8),kit.dp(12),kit.dp(8));box.setBackgroundColor(AppTheme.colors().bgRoot);
  ImageSheet original=new ImageSheet(this);if(sheet.bitmap!=null)original.setBitmap(sheet.bitmap.copy(Bitmap.Config.ARGB_8888,false));box.addView(original,new LinearLayout.LayoutParams(-1,0,1));
  EditText editor=new EditText(this);editor.setGravity(Gravity.TOP);editor.setTextSize(18);editor.setTypeface(Typeface.MONOSPACE);editor.setTextColor(AppTheme.colors().textPrimary);editor.setBackground(kit.ripple(AppTheme.colors().surface2,14));editor.setPadding(kit.dp(12),kit.dp(10),kit.dp(12),kit.dp(10));editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
  if(restored!=null)editor.setText(restored);else try{editor.setText(new String(ScoreRepository.readAtomic(new File(repo.folder(item.id),"jianpu.txt")),"UTF-8"));}catch(Exception ignored){}activeNotationEditor=editor;box.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
  TextView save=button("保存" ,()->{}),adjust=button("调整",()->{}),generate=button("生成指引",()->{});LinearLayout tools=new LinearLayout(this);for(TextView action:new TextView[]{save,adjust,generate}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(action!=generate)lp.rightMargin=kit.dp(8);tools.addView(action,lp);}LinearLayout.LayoutParams toolLp=new LinearLayout.LayoutParams(-1,-2);toolLp.topMargin=kit.dp(10);box.addView(tools,toolLp);
  AlertDialog dialog=com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("简谱校对").setView(box).create();
  dialog.setOnDismissListener(d->{original.release();if(activeNotationEditor==editor)activeNotationEditor=null;});dialog.setOnShowListener(d->{dialog.getWindow().setLayout(-1,-1);save.setOnClickListener(v->{try{ScoreRepository.atomic(new File(repo.folder(item.id),"jianpu.txt"),editor.getText().toString().getBytes("UTF-8"));Toast.makeText(this,"校对稿已保存",0).show();dialog.dismiss();}catch(Exception e){Toast.makeText(this,"保存失败："+e.getMessage(),1).show();}});
   generate.setOnClickListener(v->{try{ScoreNotation.Parsed parsed=ScoreNotation.parse(editor.getText().toString());ScoreRepository.atomic(new File(repo.folder(item.id),"jianpu.txt"),editor.getText().toString().getBytes("UTF-8"));confirmGuide(parsed.csv(),dialog);}catch(Exception e){com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("请修正校对稿").setMessage(e.getMessage()).setPositiveButton("返回编辑",null).show();}});
   adjust.setOnClickListener(v->com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("简谱调整").setItems(new String[]{"播放段落顺序","启用 / 关闭反复","整体升高八度","整体降低八度","恢复上次校对稿","符号帮助"},(d1,which)->{
    String text=editor.getText().toString();if(which==0){EditText order=new EditText(this);order.setHint("如 A1 A2 B B；留空按谱面顺序");com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("段落顺序").setView(order).setPositiveButton("应用",(dd,ww)->editor.setText(setting(editor.getText().toString(),"顺序",order.getText().toString()))).setNegativeButton("取消",null).show();}
    else if(which==1)com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("按谱面反复记号展开").setItems(new String[]{"是","否"},(dd,ww)->editor.setText(setting(editor.getText().toString(),"反复",ww==0?"是":"否"))).show();
    else if(which==2||which==3){try{java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^1=(.+)$").matcher(text);StringBuffer shifted=new StringBuffer();while(m.find()){int pitch=ScoreDraft.pitch(m.group(1).trim())+(which==2?12:-12);if(pitch<0||pitch>127)throw new Exception();m.appendReplacement(shifted,java.util.regex.Matcher.quoteReplacement("1="+ScorePitch.name(pitch)));}m.appendTail(shifted);editor.setText(shifted.toString());}catch(Exception e){Toast.makeText(this,"请先确认主音设置",0).show();}}
    else if(which==4){try{editor.setText(new String(ScoreRepository.readAtomic(new File(repo.folder(item.id),"previous-jianpu.txt")),"UTF-8"));}catch(Exception e){Toast.makeText(this,"没有上次校对稿",0).show();}}
    else com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("简谱符号").setMessage("1—7表示音级，0休止，-延长一拍。\n1'是高八度，5,是低八度。\n1/2是八分音符，1/4是十六分；2.是附点。\n1~ 1连接同音，只需弹一次。\n|: 1 2 :|表示反复两次。\n?是未确认区域，可改为一个或多个音符；生成指引前必须补齐。\nBPM按四分音符计；升降八度前先核对1=主音。\n段落用A1:、A2:、B:标记，顺序可设A1 A2 B B。\n反复与顺序会同时生效，请避免重复展开。原谱可缩放拖动对照。\n完整规则见帮助页。") .setPositiveButton("知道了",null).show();
   }).show());
  });dialog.show();
 }
 static String setting(String text,String name,String value){java.util.regex.Pattern p=java.util.regex.Pattern.compile("(?m)^"+java.util.regex.Pattern.quote(name)+"=.*$");java.util.regex.Matcher m=p.matcher(text);String replacement=name+"="+value;return m.find()?m.replaceAll(java.util.regex.Matcher.quoteReplacement(replacement)):replacement+"\n"+text;}
 void saveDraftText(String text){try{ScoreRepository.atomic(new File(repo.folder(item.id),"draft.txt"),text.getBytes("UTF-8"));Toast.makeText(this,"草稿已保留",0).show();}catch(Exception e){Toast.makeText(this,"草稿保存失败",0).show();}}
 @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("page",page);b.putInt("rotation",rotation);if(activeNotationEditor!=null)b.putString("notation_editing",activeNotationEditor.getText().toString());}
 @Override protected void onDestroy(){destroyed=true;generation++;worker.shutdown();if(sheet!=null)sheet.release();super.onDestroy();}
 static final class ImageSheet extends View {
  Bitmap bitmap;Matrix matrix=new Matrix();ScaleGestureDetector scale;GestureDetector gesture;float zoom=1;
  ImageSheet(Context c){super(c);scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){float factor=d.getScaleFactor();float target=Math.max(.25f,Math.min(10,zoom*factor));matrix.postScale(target/zoom,target/zoom,d.getFocusX(),d.getFocusY());zoom=target;invalidate();return true;}});gesture=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){public boolean onDown(android.view.MotionEvent e){return true;}public boolean onScroll(android.view.MotionEvent e,android.view.MotionEvent f,float x,float y){matrix.postTranslate(-x,-y);invalidate();return true;}public boolean onDoubleTap(android.view.MotionEvent e){fit();return true;}});}
  Rect visibleCrop(){if(bitmap==null||zoom<=1.05f)return null;Matrix inverse=new Matrix();if(!matrix.invert(inverse))return null;RectF rect=new RectF(0,0,getWidth(),getHeight());inverse.mapRect(rect);return new Rect(Math.max(0,(int)rect.left),Math.max(0,(int)rect.top),Math.min(bitmap.getWidth(),(int)rect.right),Math.min(bitmap.getHeight(),(int)rect.bottom));}
  void setBitmap(Bitmap b){Bitmap old=bitmap;bitmap=b;fit();if(old!=null&&old!=b)old.recycle();}void release(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
  void fit(){if(bitmap==null)return;float s=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());matrix.reset();matrix.postScale(s,s);matrix.postTranslate((getWidth()-bitmap.getWidth()*s)/2,(getHeight()-bitmap.getHeight()*s)/2);zoom=1;invalidate();}
  protected void onSizeChanged(int w,int h,int a,int b){fit();}protected void onDraw(Canvas c){c.drawColor(Color.rgb(20,26,34));if(bitmap!=null)c.drawBitmap(bitmap,matrix,new Paint(Paint.FILTER_BITMAP_FLAG));}public boolean onTouchEvent(android.view.MotionEvent e){scale.onTouchEvent(e);if(!scale.isInProgress())gesture.onTouchEvent(e);return true;}
 }
}
