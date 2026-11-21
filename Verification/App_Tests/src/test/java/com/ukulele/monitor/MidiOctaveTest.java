package com.ukulele.monitor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,shadows={PracticeFlowTest.PreviewShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class MidiOctaveTest {
    private byte[] midi(int pitch)throws Exception{
        byte[] track={0,(byte)0xff,0x51,3,7,(byte)0xa1,0x20,
            0,(byte)0x90,(byte)pitch,90,120,(byte)0x80,(byte)pitch,0,
            120,(byte)0xff,0x2f,0};
        ByteArrayOutputStream raw=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(raw);
        out.writeBytes("MThd");out.writeInt(6);out.writeShort(0);out.writeShort(1);out.writeShort(120);
        out.writeBytes("MTrk");out.writeInt(track.length);out.write(track);return raw.toByteArray();
    }
    @Test public void transposesBeforeDiscardingAndPreservesRhythm()throws Exception{
        byte[] raw=midi(48);
        assertEquals(1,MidiGuide.convert(raw).outside);
        ScorePitch.Result raised=MidiGuide.convert(raw,12);
        assertEquals(0,raised.outside);assertEquals(0,raised.overflow);
        SongTimeline timeline=SongTimeline.parse(raised.events);
        assertEquals(1,timeline.noteCount);
        PreviewScore preview=PreviewScore.fromMidi(raw).shifted(12);
        assertEquals(60,preview.notes.get(0).pitch);
        PreviewScore guide=PreviewScore.fromTimeline(timeline);
        assertEquals(preview.notes.get(0).pitch,guide.notes.get(0).pitch);
        assertEquals(preview.notes.get(0).start,guide.notes.get(0).start,.001);
        assertEquals(preview.notes.get(0).end,guide.notes.get(0).end,.001);
        assertEquals(preview.musicalDuration,guide.musicalDuration,.001);
        assertEquals(60,PreviewScore.fromMidi(midi(72)).shifted(-12).notes.get(0).pitch);
    }
    @Test public void persistsOctaveWithOriginalAndRecoversFromStaleEventCache()throws Exception{
        ScoreRepository repo=new ScoreRepository(RuntimeEnvironment.getApplication());
        byte[] original=midi(48);ScoreRepository.Item item=repo.importFile("低八度.mid","audio/midi",original);
        repo.setEvents(item,MidiGuide.convert(original).events,"音域不全",1);
        repo.setMidiTranspose(item.id,12);
        ScoreRepository restored=new ScoreRepository(RuntimeEnvironment.getApplication());
        assertEquals(12,restored.get(item.id).midiTranspose);
        assertArrayEquals(original,ScoreRepository.readAtomic(restored.source(item.id)));
        assertEquals(60,PreviewScore.fromTimeline(SongTimeline.parse(restored.events(item.id))).notes.get(0).pitch);
        ScoreRepository.atomic(new java.io.File(repo.folder(item.id),"events.uks"),new byte[]{0});
        assertEquals(60,PreviewScore.fromTimeline(SongTimeline.parse(restored.events(item.id))).notes.get(0).pitch);
        repo.rename(item.id,"重命名");repo.trash(item.id);repo.restore(item.id);
        assertEquals(12,repo.get(item.id).midiTranspose);
        repo.setMidiTranspose(item.id,0);
        assertEquals(1,repo.get(item.id).outside);
        assertEquals(0,SongTimeline.parse(repo.events(item.id)).noteCount);
    }
    @Test public void migratesOldMetadataAndRejectsInvalidOctaves()throws Exception{
        ScoreRepository repo=new ScoreRepository(RuntimeEnvironment.getApplication());
        ScoreRepository.Item item=repo.importFile("原调.mid","audio/midi",midi(60));
        JSONObject old=item.json();old.remove("midiTranspose");
        assertEquals(0,ScoreRepository.Item.parse(old).midiTranspose);
        try{repo.setMidiTranspose(item.id,7);fail("partial octave accepted");}catch(Exception expected){}
        assertEquals(0,repo.get(item.id).midiTranspose);
        repo.setMidiTranspose(item.id,-12);
        assertEquals(1,repo.get(item.id).outside);
        repo.setMidiTranspose(item.id,0);
        assertEquals(60,PreviewScore.fromTimeline(SongTimeline.parse(repo.events(item.id))).notes.get(0).pitch);
    }
    @Test public void preservesExistingCustomGuideUntilOctaveIsExplicitlySelected()throws Exception{
        ScoreRepository repo=new ScoreRepository(RuntimeEnvironment.getApplication());
        ScoreRepository.Item item=repo.importFile("校对过.mid","audio/midi",midi(48));
        byte[] corrected=MidiGuide.convert(midi(64)).events;
        repo.setEvents(item,corrected,"已校对",0);
        assertArrayEquals(corrected,repo.events(item.id));
        repo.setMidiTranspose(item.id,12);
        assertEquals(60,PreviewScore.fromTimeline(SongTimeline.parse(repo.events(item.id))).notes.get(0).pitch);
    }
    @Test public void changedOctaveStopsOldAuditionAndInvalidatesUploadedBytes()throws Exception{
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            ScorePreviewPlayer player=new ScorePreviewPlayer(h.host,new ScorePreviewPlayer.Listener(){public void state(String s){}});
            h.field("selectedSongLabel",new android.widget.TextView(h.host));
            h.field("songMetaLabel",new android.widget.TextView(h.host));
            h.field("songStatusLabel",new android.widget.TextView(h.host));
            h.field("auditionPlayer",player);PracticeFlowTest.PreviewShadow.active=true;
            h.field("uploadedSongId","test:one");h.field("uploadDeviceConfirmed",true);
            h.field("pendingUploadId","test:one");
            java.util.ArrayDeque<byte[]> queue=h.field("songQueue");queue.add(new byte[]{3});
            byte[] changed=GuideEventText.legacy("0,1,12,200\n5000,0,0,0\n");
            h.call("selectSong",new Class[]{String.class,String.class,byte[].class,SongTimeline.class,String.class,boolean.class},
                "test:one","升八度",changed,SongTimeline.parse(changed),"",true);
            assertFalse(PracticeFlowTest.PreviewShadow.active);
            assertNull(h.field("uploadedSongId"));assertNull(h.field("pendingUploadId"));
            assertFalse((boolean)h.field("uploadDeviceConfirmed"));assertTrue(queue.isEmpty());
        }finally{h.cleanup();}
    }
}
