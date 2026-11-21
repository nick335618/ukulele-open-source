package com.ukulele.monitor;
import org.junit.*;import static org.junit.Assert.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class}) @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class InstrumentPlaybackTest {
 PracticeFlowTest h;@Before public void init()throws Exception{h=new PracticeFlowTest();h.setup();}@After public void end()throws Exception{h.cleanup();}
 @Test public void playbackProtocolVersionAndSourceAreStrict(){byte[] p={(byte)0x91,2,0,0,1,0,1,0,1,1,(byte)232,3,0,0,0,0,0,0,1,0};assertTrue(SongPlaybackState.parse(p).instrument);p[9]=2;assertNull(SongPlaybackState.parse(p));p[1]=1;p[9]=1;assertNull(SongPlaybackState.parse(p));p[9]=0;assertFalse(SongPlaybackState.parse(p).instrument);}
 @Test public void autoplayUsesCountdownAndRealStartAcknowledgement()throws Exception{
 h.fakeDevice();byte[] c={(byte)0xab,2,0,0,2,7,1,0,1,0,12,7,0};for(int i=0;i<12;i++)c[12]^=c[i];h.call("applyCapoState",new Class[]{CapoState.class},CapoState.parse(c));h.field("songTempoSupported",false);h.call("startInstrumentPlayback");assertTrue((boolean)h.field("instrumentIntent"));assertFalse((boolean)h.call("canScoreNow"));h.advance(2999);assertNull(h.field("queuedPlayId"));h.advance(1);ControlCommandQueue q=h.field("commandQueue");assertTrue(q.peek().startsWith("SP1:5,"));assertFalse((boolean)h.field("guideRunning"));
 int token=h.field("pendingSongControlToken");
 byte[] prior={(byte)0x91,2,(byte)255,0,0,0,2,0,1,0,(byte)232,3,0,0,0,0,0,0,1,0};
 h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},SongPlaybackState.parse(prior));
 assertTrue((boolean)h.field("instrumentIntent"));assertEquals(token,(int)h.field("pendingSongControlToken"));
 byte[] ack={(byte)0x91,2,0,0,(byte)token,(byte)(token>>8),3,0,1,1,(byte)232,3,0,0,0,0,0,0,2,0};
 h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},SongPlaybackState.parse(ack));
 assertTrue((boolean)h.field("guideRunning"));assertEquals(3,(int)h.field("activeDeviceSession"));assertNull(h.field("practiceScorer"));
 h.call("stopPractice");assertFalse(q.peek().startsWith("SP1:5,"));
 }
 @Test public void modeIsExplicitAndSurvivesTempoButHumanPracticeResetsIt()throws Exception{h.field("instrumentIntent",true);h.call("applyGuideRate",new Class[]{double.class},1.5);assertTrue((boolean)h.field("instrumentIntent"));h.call("requestLocalPractice");assertFalse((boolean)h.field("instrumentIntent"));}
}