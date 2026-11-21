package com.ukulele.monitor;
import android.graphics.*;import android.view.*;import android.widget.*;import com.ukulele.monitor.ui.*;
import org.junit.*;import static org.junit.Assert.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.util.ReflectionHelpers;
import java.io.*;import java.nio.file.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class}) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class AudioUpgradeTest extends PracticeFlowTest {
 byte[] crc(byte[] b){b[b.length-1]=0;for(int i=0;i<b.length-1;i++)b[b.length-1]^=b[i];return b;}
 byte[] scalar(int type,int n,int rev){byte[] b={(byte)type,1,(byte)n,(byte)n,2,7,1,0,(byte)rev,(byte)(rev>>8),(byte)(type==0xab?12:3),0,0};if(type==0xab){b[1]=2;b[11]=3;}return crc(b);}
 byte[] chain(){return crc(new byte[]{(byte)0xae,1,1,15,127,96,96,127,1,15,23,2,1,0,1,0,1,127,0,0});}
 @Test public void protocolRejectsEverySingleBitErrorAndChecksReservedRanges(){
  byte[] b=scalar(0xab,12,65535);assertNotNull(CapoState.parse(b));for(int i=0;i<b.length*8;i++){byte[] c=b.clone();c[i/8]^=1<<(i%8);assertNull(CapoState.parse(c));}
  assertTrue(CapoState.parse(scalar(0xab,1,0)).acceptsAfter(CapoState.parse(b)));assertFalse(CapoState.parse(b).acceptsAfter(CapoState.parse(scalar(0xab,1,0))));
  byte[] c=b.clone();c[11]=4;assertNull(CapoState.parse(crc(c)));c=b.clone();c[2]=13;assertNull(CapoState.parse(crc(c)));
  byte[] m=scalar(0xad,3,1);assertNotNull(ModelState.parse(m));for(int i=0;i<m.length*8;i++){c=m.clone();c[i/8]^=1<<(i%8);assertNull(ModelState.parse(c));}
  b=chain();assertNotNull(ChainState.parse(b));for(int i=0;i<b.length*8;i++){c=b.clone();c[i/8]^=1<<(i%8);assertNull(ChainState.parse(c));}
  b[5]=97;assertNull(ChainState.parse(crc(b)));assertEquals(19,ChainState.command(15,new int[]{127,96,96,127},5,32767).length());
 }
 @Test public void commandQueueKeepsInflightAndCoalescesControls(){ControlCommandQueue q=new ControlCommandQueue();q.offer("CAPO:1,1",false);q.offer("CAPO:2,2",true);q.offer("CAPO:3,3",true);q.offer("MODEL:2,1",true);q.offer("MODEL:3,2",true);assertEquals(3,q.size());assertEquals("CAPO:1,1",q.poll());assertEquals("CAPO:3,3",q.poll());assertEquals("MODEL:3,2",q.poll());}
 @Test public void controlsRequireRealFreshStateAndShowFirmwareLimits()throws Exception{
  LinearLayout panel=new LinearLayout(host);panel.setOrientation(1);call("addModelControls",new Class[]{LinearLayout.class},panel);call("addChainControls",new Class[]{LinearLayout.class},panel);call("addCapoControls",new Class[]{LinearLayout.class},panel);
  assertFalse(((View)field("capoApply")).isEnabled());assertTrue(((SeekBar)field("capoBar")).isEnabled());assertFalse(((View)field("chainRoute")).isEnabled());fakeDevice();call("receiveModel",new Class[]{ModelState.class},ModelState.parse(scalar(0xad,3,1)));call("receiveChain",new Class[]{ChainState.class},ChainState.parse(chain()));call("applyCapoState",new Class[]{CapoState.class},CapoState.parse(scalar(0xab,5,1)));
  assertEquals(5,(int)call("activeCapo"));assertEquals(5,((SeekBar)field("capoBar")).getProgress());assertEquals(12,((SeekBar)field("capoBar")).getMax());SeekBar[] bars=field("chainBars");assertEquals(96,bars[1].getMax());assertEquals(127,bars[3].getMax());assertTrue(((View)field("chainRoute")).isEnabled());
  layout(panel,393,1450);png(panel,"audio-upgrade-controls");advance(2100);assertFalse(((View)field("chainRoute")).isEnabled());assertFalse(((View)field("capoApply")).isEnabled());
 }
 @Test public void guideRendersBothDirectionsThemesAndBackSeek()throws Exception{
  SongTimeline t=SongTimeline.parse(GuideEventText.legacy("0,1,0,200\n300,1,0,200\n600,5,3,200\n1800,0,0,0\n"));
  for(boolean dark:new boolean[]{false,true}){if(dark)AppTheme.dark();else AppTheme.light();for(boolean right:new boolean[]{false,true}){SongGuideView v=new SongGuideView(host);v.setTimeline(t);v.setLandingRight(right);v.setElapsed(700);v.setStringsReversed(right);layout(v,800,350);png(v,"guide-"+dark+"-"+right);v.setElapsed(0);Bitmap b=Bitmap.createBitmap(800,350,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));}}
 }
}
