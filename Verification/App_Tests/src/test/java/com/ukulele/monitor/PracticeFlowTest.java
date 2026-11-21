package com.ukulele.monitor;
import android.app.*;import android.bluetooth.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;
import com.ukulele.monitor.ui.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.shadows.*;import org.robolectric.util.ReflectionHelpers;import org.robolectric.shadow.api.Shadow;
import java.io.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.*;import java.time.Duration;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class}) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class PracticeFlowTest {
 @Implements(value=ScorePreviewPlayer.class,isInAndroidSdk=false) public static class PreviewShadow {
  static int starts;static double rate;static boolean active;
  @Implementation protected void start(ScorePreviewPlayer.Source source,double speed,String name){starts++;rate=speed;active=true;}
  @Implementation protected void stop(){active=false;}
  @Implementation protected boolean isActive(){return active;}
  @Implementation protected void setSpeed(double value){rate=value;}
 }
 MainActivity host;View practice;byte[] song;
 Object call(String name)throws Exception{return call(name,new Class<?>[0]);}
 Object call(String name,Class<?>[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(host,args);}catch(InvocationTargetException e){throw new RuntimeException(name,e.getCause());}}
 void field(String name,Object value){ReflectionHelpers.setField(host,name,value);}
 <T>T field(String name){return ReflectionHelpers.getField(host,name);}
 void advance(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
 @Before public void setup()throws Exception{
  PreviewShadow.starts=0;PreviewShadow.active=false;AppTheme.light();host=Robolectric.buildActivity(MainActivity.class).get();host.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
  field("kit",UiKit.create(host));field("settings",SettingsStore.store(host));field("uiScale",1f);field("feedback",new Feedback(host));field("scoreRepo",new ScoreRepository(host));field("scoreRecords",new PracticeScoreRecords(new File(host.getFilesDir(),"score-test")));field("funStore",new com.ukulele.monitor.fun.FunStore(host));field("activityVisible",true);field("scoringEnabled",false);
  org.json.JSONArray json=new org.json.JSONArray(new String(Files.readAllBytes(Paths.get("../Inputs/song_library_index.json")),"UTF-8"));ArrayList<SongLibrary.Song> songs=new ArrayList<>();for(int i=0;i<json.length();i++)songs.add(new SongLibrary.Song(json.getJSONObject(i)));ReflectionHelpers.setStaticField(SongLibrary.class,"cached",Collections.unmodifiableList(songs));
  ((SettingsStore)field("settings")).setGuideLandingRight(false);((SettingsStore)field("settings")).setGuideStringsReversed(false);practice=(View)call("buildSongPracticePage");song=GuideEventText.legacy("0,1,0,200\n5000,0,0,0\n");field("selectedSongId","test:one");field("selectedSongName","测试曲目");field("selectedSongBytes",song);SongTimeline timeline=SongTimeline.parse(song);field("selectedTimeline",timeline);((SongGuideView)field("songGuideView")).setTimeline(timeline);call("refreshPracticeSummary");
  FrameLayout pageHost=new FrameLayout(host);field("pageHost",pageHost);pageHost.addView(practice);host.setContentView(pageHost);field("headerTitle",new TextView(host));field("headerSubtitle",new TextView(host));
  View[] pages=field("pages");pages[3]=practice;pages[2]=new ScrollView(host);field("currentPage",2);
 }
 @After public void cleanup()throws Exception{call("cancelFullCountdown");Handler handler=field("main");handler.removeCallbacksAndMessages(null);ScorePreviewPlayer player=field("auditionPlayer");if(player!=null)player.close();java.util.concurrent.ExecutorService worker=field("scoreWorker");worker.shutdownNow();}
 View find(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View v=find(g.getChildAt(i),text);if(v!=null)return v;}}return null;}
 Rect rect(View v,View root){Rect r=new Rect();v.getDrawingRect(r);((ViewGroup)root).offsetDescendantRectToMyCoords(v,r);return r;}
 void layout(View root,int w,int h){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}
 void png(View root,String name)throws Exception{Bitmap b=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));java.nio.file.Path path=Paths.get("../Screenshots/"+name+".png");Files.createDirectories(path.getParent());try(OutputStream out=Files.newOutputStream(path)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
 void clickDialog(String text){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);View b=find(d.getWindow().getDecorView(),text);assertNotNull(text,b);b.performClick();}
 void fakeDevice(){field("bleReady",true);field("gatt",Shadow.newInstanceOf(BluetoothGatt.class));field("commandChar",new BluetoothGattCharacteristic(UUID.randomUUID(),BluetoothGattCharacteristic.PROPERTY_WRITE,0));field("songWriteBusy",true);field("songTempoSupported",true);field("uploadedSongId","test:one");}
 @Test public void practiceLayoutAndFullscreenCoordinates()throws Exception{
  for(int width:new int[]{320,360,393})for(float scale:new float[]{1f,1.18f}){
   field("uiScale",scale);View root=(View)call("buildSongPracticePage");layout(root,width,850);
   View start=find(root,"开始练习"),stop=find(root,"停止练习"),device=find(root,"开始设备指引"),audition=find(root,"试听参考"),guide=find(root,"跟弹练习");
   assertTrue(rect(device,root).top-rect(start,root).bottom>=8);assertTrue(rect(stop,root).left-rect(start,root).right>=8);assertTrue(rect(audition,root).top>rect(device,root).bottom);assertTrue(rect(guide,root).top>rect(audition,root).bottom);
   assertNull(find(root,"评分挑战"));assertNotNull(find(root,"练习评分"));assertNotNull(find(root,"无指引练习"));assertNotNull(find(root,"设备指引"));assertNotNull(find(root,"全屏跟弹"));png(root,"practice-top-"+width+"-"+scale);View body=((ScrollView)root).getChildAt(0);body.setBackgroundColor(AppTheme.colors().bgRoot);layout(body,width,body.getMeasuredHeight());png(body,"practice-entire-"+width+"-"+scale);
   LinearLayout grid=(LinearLayout)call("buildFullPracticeControls");layout(grid,Math.max(640,width*2),112);
   String[][] labels={{"开始灯光指引","开始无灯光指引","弦序 1→4"},{"暂停 / 继续","重新开始","左侧基准"}};
   for(int row=0;row<2;row++)for(int col=0;col<3;col++){
    TextView b=(TextView)find(grid,labels[row][col]);assertNotNull(labels[row][col],b);assertTrue("button height "+b.getHeight(),b.getHeight()>=44);assertEquals(rect(find(grid,labels[row][0]),grid).top,rect(b,grid).top);if(col>0)assertTrue(rect(b,grid).left-rect(find(grid,labels[row][col-1]),grid).right>=8);
   }assertTrue(rect(find(grid,labels[1][0]),grid).top-rect(find(grid,labels[0][0]),grid).bottom>=8);png(grid,"fullscreen-grid-"+width+"-"+scale);
  }
 }
 @Test public void auditionWaitsForChoiceAndCancelStaysSilent()throws Exception{
  call("startScoreAudition");assertEquals(0,PreviewShadow.starts);assertNull(field("auditionPlayer"));clickDialog("取消");advance(500);assertEquals(0,PreviewShadow.starts);assertEquals(2,(int)field("currentPage"));
  call("startScoreAudition");assertEquals(0,PreviewShadow.starts);clickDialog("否，留在本页");assertEquals(1,PreviewShadow.starts);assertEquals(2,(int)field("currentPage"));
  call("startScoreAudition");assertEquals(1,PreviewShadow.starts);clickDialog("是，进入跟弹");assertEquals(2,PreviewShadow.starts);assertEquals(3,(int)field("currentPage"));
 }
 @Test public void auditionChoiceRejectsChangedSong()throws Exception{call("startScoreAudition");field("selectedSongId","changed");clickDialog("否，留在本页");assertEquals(0,PreviewShadow.starts);}
 @Test public void everyLocalEntryUsesOneCountdownAndStopCancels()throws Exception{
  for(String action:new String[]{"开始练习","无指引练习"}){
   find(practice,action).performClick();assertFalse("already running "+action,(boolean)field("guideRunning"));advance(0);assertTrue(((PracticeCountdown)field("fullCountdown")).active());assertEquals(View.VISIBLE,((TextView)field("practiceCountdownLabel")).getVisibility());advance(((PracticeCountdown)field("fullCountdown")).remainingMillis(android.os.SystemClock.uptimeMillis())-1);assertFalse("early start "+action,(boolean)field("guideRunning"));advance(1);assertTrue("did not start "+action,(boolean)field("guideRunning"));assertEquals(0d,(double)field("guideElapsedBase"),0d);call("stopPractice");
  }
  find(practice,"开始练习").performClick();advance(1000);find(practice,"停止练习").performClick();advance(4000);assertFalse((boolean)field("guideRunning"));
 }
 @Test public void replaceCountdownChangeSongAndOfflineCancel()throws Exception{
  call("requestLocalPractice");advance(2000);call("requestLocalPractice");advance(1000);assertFalse((boolean)field("guideRunning"));advance(2000);assertTrue((boolean)field("guideRunning"));call("stopPractice");
  call("requestLocalPractice");advance(1000);field("selectedSongId","changed");advance(3000);assertFalse((boolean)field("guideRunning"));
  field("selectedSongId","test:one");fakeDevice();call("startFullScreenGuide");advance(1000);field("bleReady",false);advance(3000);assertNull(field("queuedPlayId"));assertFalse(((PracticeCountdown)field("fullCountdown")).active());
 }
 @Test public void deviceStartOnlyQueuesAfterThreeSeconds()throws Exception{
  fakeDevice();ControlCommandQueue queue=field("commandQueue");call("startFullScreenGuide");advance(2999);assertNull(field("queuedPlayId"));assertTrue(queue.isEmpty());advance(1);assertEquals("test:one",field("queuedPlayId"));assertTrue(queue.peek().startsWith("SP1:0,1000,"));assertFalse((boolean)field("guideRunning"));
 }
 @Test public void speedSliderAndAcknowledgementMatch()throws Exception{
  java.util.List<SeekBar> bars=field("practiceRateBars");assertEquals(7,bars.get(0).getMax());call("setPracticeSpeed",new Class[]{double.class},.5d);assertEquals(.5,(double)field("guideSpeed"),0);assertEquals(1,bars.get(0).getProgress());
  fakeDevice();field("guideDeviceReference",true);field("hardwarePlayingId","test:one");call("setPracticeSpeed",new Class[]{double.class},1.5d);assertEquals(.5,(double)field("guideSpeed"),0);assertFalse(bars.get(0).isEnabled());assertTrue(((TextView)((java.util.List<?>)field("practiceRateLabels")).get(0)).getText().toString().contains("等待设备确认"));
  call("clearSongControlPending");call("applyGuideRate",new Class[]{double.class},1.5d);assertEquals(5,bars.get(0).getProgress());assertTrue(bars.get(0).isEnabled());
 }
 @Test public void scoreToggleAndDirectionsAreInlineAndSynchronized()throws Exception{
  Switch sw=field("scoringSwitch");sw.setChecked(true);assertTrue((boolean)field("scoringEnabled"));sw.setChecked(false);assertFalse((boolean)field("scoringEnabled"));
  call("toggleGuideStrings");assertNotNull(find(practice,"弦序 4→1"));call("toggleGuideDirection");assertNotNull(find(practice,"右侧基准"));
 }
 @Test public void referenceLibraryAndEffectLayoutStillMatch()throws Exception{
  for(int width:new int[]{320,393}){
   View root=(View)call("buildSongPage");layout(root,width,850);View practice=find(root,"练习这首曲子"),play=find(root,"▶ 试听乐谱"),stop=find(root,"停止试听");assertTrue(rect(play,root).top-rect(practice,root).bottom>=12);assertTrue(rect(stop,root).left-rect(play,root).right>=10);png(root,"library-"+width);
  }
  LinearLayout root=new LinearLayout(host);root.setOrientation(1);root.setBackgroundColor(AppTheme.colors().bgRoot);root.setPadding(20,0,20,0);call("addChainControls",new Class[]{LinearLayout.class},root);layout(root,393,570);CheckBox[] buttons=field("chainChecks");assertEquals(4,buttons.length);assertTrue(rect(buttons[1],root).top>rect(buttons[0],root).bottom);assertNull(find(root,"应用组合"));png(root,"homepage-effects");
 }

 void freshNotes(int seq){byte[] d=new byte[20];d[0]=(byte)0xA1;d[1]=1;d[2]=1;d[17]=(byte)seq;d[18]=(byte)seq;for(int i=0;i<4;i++)d[8+i]=67;((PlayedNoteTracker)field("playedNoteTracker")).accept(LiveNoteState.parse(d),SystemClock.uptimeMillis());}
 SongPlaybackState playback(int op,int token,int state,int position,int rev){byte[] d=new byte[20];d[0]=(byte)0x91;d[1]=1;d[2]=(byte)op;d[4]=(byte)token;d[5]=(byte)(token>>8);d[6]=1;d[8]=(byte)state;d[10]=(byte)1000;d[11]=3;d[12]=(byte)position;d[13]=(byte)(position>>8);d[18]=(byte)rev;return SongPlaybackState.parse(d);}
 @Test public void scoringUsesSingleCountdownAndDeviceClock()throws Exception{
  fakeDevice();((Switch)field("scoringSwitch")).setChecked(true);freshNotes(1);call("requestLocalPractice");advance(2999);assertNull(field("practiceScorer"));freshNotes(2);advance(1);assertNotNull("scoreHint="+field("scoreHint")+" mode="+((TextView)field("songGuideModeLabel")).getText(),field("practiceScorer"));assertEquals(0L,(long)field("scoreElapsedBase"));assertEquals(0L,(long)call("scoringElapsed"));call("stopPractice");
  field("deviceStopRequested",false);field("hardwarePlayingId",null);field("deviceSongState",null);call("startFullScreenGuide");advance(2999);freshNotes(3);advance(1);int token=field("pendingSongControlToken");assertTrue(token>0);call("applyDeviceSongState",new Class[]{SongPlaybackState.class},playback(0,token,1,0,1));assertTrue((boolean)field("guideDeviceReference"));assertTrue((boolean)call("scoringActive"));
  call("applyDeviceSongState",new Class[]{SongPlaybackState.class},playback(255,0,2,200,2));assertTrue((boolean)field("scorePaused"));long at=(long)call("scoringElapsed");advance(100);assertEquals(at,(long)call("scoringElapsed"));
  call("applyDeviceSongState",new Class[]{SongPlaybackState.class},playback(255,0,1,200,3));assertFalse((boolean)field("scorePaused"));assertEquals(200L,(long)call("scoringElapsed"));
 }
 @Test public void confirmedUploadMayStartOutsideFullscreenButStopInvalidatesPostedStart()throws Exception{
  fakeDevice();field("uploadedSongId",null);field("pendingUploadId","test:one");field("pendingUploadName","测试曲目");field("fullGuideAfterUploadId","test:one");field("songStatusLabel",new TextView(host));field("uploadDeviceConfirmed",true);field("uploadTransportDone",false);call("finishSongUpload");assertFalse(((PracticeCountdown)field("fullCountdown")).active());
  field("uploadTransportDone",true);call("finishSongUpload");call("stopPractice");advance(0);assertFalse(((PracticeCountdown)field("fullCountdown")).active());
  field("pendingUploadId","test:one");field("pendingUploadName","测试曲目");field("fullGuideAfterUploadId","test:one");call("finishSongUpload");advance(0);assertTrue(((PracticeCountdown)field("fullCountdown")).active());
 }
 @Test public void fullScreenKeepsPlotAndRestoresParent()throws Exception{
  SongGuideView view=field("songGuideView");ViewGroup before=(ViewGroup)view.getParent();int index=before.indexOfChild(view);
  call("openFullScreen",new Class[]{View.class,boolean.class},view,true);Dialog dialog=field("fullScreenDialog");View root=dialog.getWindow().getDecorView();layout(root,800,393);assertTrue("plot height "+view.getHeight(),view.getHeight()>=160);assertNotNull(find(root,"开始无灯光指引"));png(root,"fullscreen-practice");
  Switch full=field("fullScoringSwitch");full.setChecked(true);assertTrue(((Switch)field("scoringSwitch")).isChecked());dialog.dismiss();advance(0);assertSame(before,view.getParent());assertEquals(index,before.indexOfChild(view));assertNull(field("fullScreenDialog"));
 }

 @Test public void shortPromptHasNoGiantBlankAndActionsFit()throws Exception{
  call("startScoreAudition");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();View root=d.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.AT_MOST));root.layout(0,0,320,root.getMeasuredHeight());assertTrue(root.getHeight()<280);String[] labels={"取消","否，留在本页","是，进入跟弹"};for(int i=0;i<labels.length;i++){View v=find(root,labels[i]);assertNotNull(v);assertTrue(rect(v,root).left>=0);assertTrue(rect(v,root).right<=320);if(i>0)assertTrue(rect(v,root).left-rect(find(root,labels[i-1]),root).right>=8);}png(root,"audition-choice");
 }
}
