package com.ukulele.monitor;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,shadows={PracticeFlowTest.PreviewShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class RecorderRecoveryTest {
    private SongPlaybackState state(int state,int revision){
        byte[] p=new byte[20];p[0]=(byte)0x91;p[1]=2;p[2]=(byte)255;p[6]=1;
        p[8]=(byte)state;p[9]=1;p[10]=(byte)1000;p[11]=3;p[18]=(byte)revision;
        return SongPlaybackState.parse(p);
    }
    @Test public void naturalEndReleasesRecorderAndStopButtonWorks()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            h.fakeDevice();h.freshNotes(1);h.call("buildTakeCard");
            h.field("instrumentIntent",true);h.field("activeDeviceSession",1);
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},state(1,1));
            assertFalse((boolean)h.call("canScoreNow"));
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},state(0,2));
            assertFalse((boolean)h.field("instrumentIntent"));
            assertTrue((boolean)h.call("canScoreNow"));
            TextView record=h.field("takeRecord");assertTrue(record.performClick());
            assertTrue(((PerformanceTake)h.field("performanceTake")).active());
            assertEquals("停止录奏",record.getText().toString());
            assertTrue(record.performClick());
            assertFalse(((PerformanceTake)h.field("performanceTake")).active());
        }finally{h.cleanup();}
    }
    @Test public void pausedAutoplayRemainsBlockedUntilConfirmedStopped()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            h.fakeDevice();h.freshNotes(1);h.field("instrumentIntent",true);
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},state(2,1));
            assertFalse((boolean)h.call("canScoreNow"));
            assertTrue(((String)h.call("recordingBlockReason")).contains("自动播放"));
            h.field("deviceStopRequested",true);
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},state(0,2));
            assertTrue((boolean)h.call("canScoreNow"));
        }finally{h.cleanup();}
    }
    @Test public void allFourTimbresAcceptActualNoteFeedback(){
        for(int model=0;model<4;model++){
            byte[] p=new byte[20];p[0]=(byte)0xa1;p[1]=1;p[2]=1;p[3]=(byte)model;
            for(int i=0;i<4;i++)p[8+i]=67;
            PlayedNoteTracker tracker=new PlayedNoteTracker();
            assertNotNull(LiveNoteState.parse(p));tracker.accept(LiveNoteState.parse(p),0);
            p[12]=1;p[17]=1;p[18]=20;
            PlayedNoteTracker.Batch batch=tracker.accept(LiveNoteState.parse(p),20);
            assertArrayEquals(new int[]{67},batch.midi);
            PerformanceTake take=new PerformanceTake();take.accept(batch,20);assertEquals(1,take.count());
            p[3]=4;assertNull(LiveNoteState.parse(p));
        }
    }
}
