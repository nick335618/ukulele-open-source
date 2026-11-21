package com.ukulele.monitor;

import android.os.SystemClock;
import android.widget.FrameLayout;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,shadows={PracticeFlowTest.PreviewShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaybackFixTest {
    @Test public void actualChengduKeepsPitchAndAttacksWithSelectableFingering()throws Exception{
        org.junit.Assume.assumeTrue("Optional private MIDI fixture",Files.isRegularFile(Paths.get("../Inputs/chengdu.mid")));
        byte[] raw=Files.readAllBytes(Paths.get("../Inputs/chengdu.mid"));
        PreviewScore source=PreviewScore.fromMidi(raw);
        assertEquals(365,source.notes.size());
        assertEquals(362,MidiGuide.convert(raw).outside);
        SongTimeline low=SongTimeline.parse(MidiGuide.convert(raw,24,false).events);
        ScorePitch.Result result=MidiGuide.convert(raw,24,true);
        assertEquals(0,result.outside);assertEquals(0,result.overflow);
        SongTimeline balanced=SongTimeline.parse(result.events);
        assertEquals(365,low.noteCount);assertEquals(365,balanced.noteCount);
        Set<Integer> defaultColumns=new HashSet<>(),columns=new HashSet<>();
        for(SongTimeline.Span s:low.spans)defaultColumns.add(s.column);
        for(SongTimeline.Span s:balanced.spans)columns.add(s.column);
        assertEquals(1,defaultColumns.size());assertTrue(columns.size()>1);
        PreviewScore mapped=PreviewScore.fromTimeline(balanced),expected=source.shifted(24);
        assertEquals(expected.notes.size(),mapped.notes.size());
        for(int i=0;i<expected.notes.size();i++){
            assertEquals(expected.notes.get(i).pitch,mapped.notes.get(i).pitch);
            assertEquals(expected.notes.get(i).start,mapped.notes.get(i).start,.0011);
        }
        ScoreRepository repo=new ScoreRepository(org.robolectric.RuntimeEnvironment.getApplication());
        ScoreRepository.Item item=repo.importFile("成都.mid","audio/midi",raw);
        repo.setMidiTranspose(item.id,24);repo.setMidiFingering(item.id,true);
        ScoreRepository reloaded=new ScoreRepository(org.robolectric.RuntimeEnvironment.getApplication());
        assertTrue(reloaded.get(item.id).midiBalanced);assertEquals(24,reloaded.get(item.id).midiTranspose);
        assertArrayEquals(result.events,reloaded.events(item.id));
        System.out.println("Chengdu 365 attacks preserved, default columns="+defaultColumns+", balanced columns="+columns);
    }
    @Test public void automaticPlaybackCountdownSurvivesPageChange()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            h.fakeDevice();h.field("instrumentIntent",true);h.field("currentPage",3);
            android.view.View[] pages=h.field("pages");pages[0]=new FrameLayout(h.host);
            h.call("beginFullCountdown",new Class[]{boolean.class},true);h.advance(1000);
            h.call("showPage",new Class[]{int.class},0);
            assertTrue(((PracticeCountdown)h.field("fullCountdown")).active());
            long remaining=((PracticeCountdown)h.field("fullCountdown")).remainingMillis(SystemClock.uptimeMillis());
            h.advance(remaining-1);assertNull(h.field("queuedPlayId"));h.advance(1);
            assertEquals("test:one",h.field("queuedPlayId"));
            ControlCommandQueue queue=h.field("commandQueue");int plays=0;String command;
            while((command=queue.poll())!=null)if(command.startsWith("SP1:5,"))plays++;
            assertEquals(1,plays);
        }finally{h.cleanup();}
    }
    @Test public void leavingLocalPracticeStillCancelsLocalCountdown()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            h.field("currentPage",3);android.view.View[] pages=h.field("pages");pages[0]=new FrameLayout(h.host);
            h.call("beginFullCountdown",new Class[]{boolean.class},false);h.advance(1000);
            h.call("showPage",new Class[]{int.class},0);h.advance(3000);
            assertFalse(((PracticeCountdown)h.field("fullCountdown")).active());
            assertFalse((boolean)h.field("guideRunning"));
        }finally{h.cleanup();}
    }
    private byte[] frame(int sequence,int count,int autoMask){
        byte[] p=new byte[20];p[0]=(byte)0xa1;p[1]=2;p[2]=1;p[3]=3;
        for(int i=0;i<4;i++)p[8+i]=67;p[12]=(byte)count;p[16]=(byte)(autoMask<<4);
        p[17]=(byte)sequence;p[18]=(byte)(sequence*20);return p;
    }
    @Test public void actualAutomaticNotesDisplayButNeverBecomeUserRecordings(){
        PlayedNoteTracker tracker=new PlayedNoteTracker();LiveNoteState a=LiveNoteState.parse(frame(1,0,0));
        tracker.accept(a,0);LiveNoteState b=LiveNoteState.parse(frame(2,1,1));
        assertEquals("G4",b.newPlayedNotes(a));assertEquals(1,b.newAudioEventMask(a));
        assertEquals(0,tracker.accept(b,20).midi.length);
        assertEquals(0,tracker.accept(b,21).midi.length);
        LiveNoteState c=LiveNoteState.parse(frame(3,2,0));
        assertArrayEquals(new int[]{67},tracker.accept(c,40).midi);
        byte[] old=frame(4,3,0);old[1]=1;assertNotNull(LiveNoteState.parse(old));
        old[16]=16;assertNull(LiveNoteState.parse(old));
    }
    @Test public void realNotificationsUpdateDashboardDuringAutomaticPlayback()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            h.fakeDevice();h.field("instrumentIntent",true);
            android.widget.TextView label=new android.widget.TextView(h.host);h.field("statusText",label);
            java.util.UUID status=org.robolectric.util.ReflectionHelpers.getStaticField(MainActivity.class,"STATUS");
            h.call("parseNotify",new Class[]{java.util.UUID.class,byte[].class},status,frame(1,0,0));h.advance(0);
            h.call("parseNotify",new Class[]{java.util.UUID.class,byte[].class},status,frame(2,1,1));h.advance(0);
            assertEquals("G4",h.field("currentNote"));assertTrue(label.getText().toString().startsWith("自动播放"));
            byte[] next=frame(3,2,1);next[8]=69;
            h.call("parseNotify",new Class[]{java.util.UUID.class,byte[].class},status,next);h.advance(0);
            assertEquals("A4",h.field("currentNote"));
            assertEquals(0,((PlayedNoteTracker)h.field("playedNoteTracker")).accept(LiveNoteState.parse(next),SystemClock.uptimeMillis()).midi.length);
        }finally{h.cleanup();}
    }
}
