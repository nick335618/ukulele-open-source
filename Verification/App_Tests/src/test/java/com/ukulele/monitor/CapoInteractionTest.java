package com.ukulele.monitor;
import android.graphics.*;import android.view.*;import android.widget.*;import com.ukulele.monitor.ui.*;
import org.junit.*;import static org.junit.Assert.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.shadows.ShadowAlertDialog;
import java.nio.file.*;import java.util.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class}) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class CapoInteractionTest {
 PracticeFlowTest h;
 @Before public void setup()throws Exception{h=new PracticeFlowTest();h.setup();}
 @After public void cleanup()throws Exception{h.cleanup();}
 byte[] packet(int n,int rev,int ticket,int version,int features){byte[] b={(byte)0xab,(byte)version,(byte)n,(byte)n,2,7,(byte)ticket,(byte)(ticket>>8),(byte)rev,(byte)(rev>>8),12,(byte)features,0};for(int i=0;i<12;i++)b[12]^=b[i];return b;}
 void receive(int n,int rev,int ticket)throws Exception{h.call("applyCapoState",new Class[]{CapoState.class},CapoState.parse(packet(n,rev,ticket,2,3)));}
 void controls()throws Exception{LinearLayout panel=new LinearLayout(h.host);panel.setOrientation(1);h.call("addCapoControls",new Class[]{LinearLayout.class},panel);}
 @Test public void legacyCapabilityCannotEnablePhysicalCapoAndReservedBitsAreChecked()throws Exception{
  assertNotNull(CapoState.parse(packet(3,1,1,1,0)));assertNull(CapoState.parse(packet(3,1,1,1,3)));assertNull(CapoState.parse(packet(3,1,1,2,4)));
  controls();h.fakeDevice();h.call("applyCapoState",new Class[]{CapoState.class},CapoState.parse(packet(3,1,1,1,0)));assertFalse(((View)h.field("capoApply")).isEnabled());
  receive(3,2,1);assertTrue(((View)h.field("capoApply")).isEnabled());assertEquals(3,(int)h.call("boardCapo"));
 }
 @Test public void draftDoesNotMoveActualBoardAndMatchingReadbackApplies()throws Exception{
  controls();h.fakeDevice();receive(3,1,1);assertEquals(3,(int)h.call("activeCapo"));
  ((View)h.field("capoPlus")).performClick();assertEquals(4,((SeekBar)h.field("capoBar")).getProgress());assertEquals(3,(int)h.call("activeCapo"));
  receive(3,2,1);assertEquals(4,((SeekBar)h.field("capoBar")).getProgress());h.call("requestCapo",new Class[]{int.class},4);int ticket=h.field("capoTicket");assertTrue((boolean)h.field("capoPending"));
  receive(4,3,ticket);assertFalse((boolean)h.field("capoPending"));assertEquals(4,(int)h.call("boardCapo"));assertFalse((boolean)h.field("capoDraftDirty"));
  h.advance(2100);assertFalse(((View)h.field("capoApply")).isEnabled());assertEquals(4,(int)h.call("activeCapo"));
 }
 void verifyText(View v){
  if(v instanceof TextView){TextView t=(TextView)v;if(t.getLayout()!=null){for(int i=0;i<t.getLineCount();i++){assertEquals(t.getText()+" ellipsized",0,t.getLayout().getEllipsisCount(i));assertTrue(t.getText()+" text width",t.getLayout().getLineWidth(i)<=t.getWidth()-t.getCompoundPaddingLeft()-t.getCompoundPaddingRight()+2);}assertTrue(t.getText()+" clipped vertically",t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom()+2);}}
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)verifyText(((ViewGroup)v).getChildAt(i));
 }
 @Test public void capoAndOneToneSelectorFitCompactScreensAndLargeText()throws Exception{
  for(int w:new int[]{320,393})for(float scale:new float[]{1f,1.45f}){
   h.field("uiScale",scale);LinearLayout p=new LinearLayout(h.host);p.setOrientation(1);p.setBackgroundColor(AppTheme.colors().bgRoot);
   h.call("addModelControls",new Class[]{LinearLayout.class},p);h.call("addCapoControls",new Class[]{LinearLayout.class},p);((SeekBar)h.field("capoBar")).setProgress(12);
   p.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));p.layout(0,0,w,p.getMeasuredHeight());verifyText(p);
   ArrayList<TextView> tones=h.field("modelChoices");assertEquals(4,tones.size());for(TextView t:tones){assertTrue(t.getHeight()>=48);assertTrue(t.getLineCount()>=2);}
   assertTrue(h.rect(h.field("capoReset"),p).top-h.rect(h.field("capoApply"),p).bottom>=8);h.png(p,"capo-tone-"+w+"-"+scale);
  }
  View page=(View)h.call("buildDashboardPage");assertNull(h.field("legacyToneRow"));assertNull(h.find(page,"试听选中音色"));assertNull(h.find(page,"应用到乐器"));
 }
 @Test public void allPhysicalGuideAndFlashPositionsRespectCapoAndOff(){
  for(int n=0;n<=12;n++)for(int f=0;f<=18;f++)for(int row=0;row<18;row++){
   int expected=(f==0?row>=n:f+n<=18&&(row==f+n-1||row==f+n))?161:0;assertEquals(expected,CapoMapping.guidePixel(f,row,161,n));assertEquals(0,CapoMapping.marker(row,n,161,0));
  }
  StrumFlashState flash=new StrumFlashState();flash.accept(15,new int[]{70,67,63,72},100,3);for(int c=0;c<4;c++)for(int r=0;r<18;r++)assertEquals(r>=3?161:0,flash.pixel(c,r,161,101,3));
  flash.accept(2,new int[]{70,69,63,72},1000,3);assertEquals(161,flash.pixel(2,4,161,1001,3));assertEquals(0,flash.pixel(2,3,161,1001,3));
 }
 @Test public void outOfNeckGuidanceIsExplainedBeforeUploadOrCountdown()throws Exception{
  controls();h.fakeDevice();receive(12,1,1);byte[] song=GuideEventText.legacy("0,1,10,200\n1000,0,0,0\n");h.field("selectedSongBytes",song);h.field("selectedTimeline",SongTimeline.parse(song));
  h.call("startFullScreenGuide");assertNotNull(ShadowAlertDialog.getLatestAlertDialog());assertFalse(((PracticeCountdown)h.field("fullCountdown")).active());assertNull(h.field("queuedPlayId"));
 }
}
