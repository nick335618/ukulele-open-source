package com.ukulele.monitor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

/** Captures confirmed note attacks on the device clock. Note ends are estimated. */
final class PerformanceTake {
    private static final int LIMIT_MS=120000, LIMIT_NOTES=1024;
    private final ArrayList<Note> notes=new ArrayList<>();
    private final Note[] held=new Note[128];
    private int previous=-1, elapsed=0;
    private boolean active=true;
    private static final class Note {
        int pitch, start, end;
        Note(int pitch,int start){this.pitch=pitch;this.start=start;end=start+330;}
    }
    private static final class Event {
        final int at,pitch,velocity;
        Event(int at,int pitch,int velocity){this.at=at;this.pitch=pitch;this.velocity=velocity;}
    }
    boolean active(){return active;}
    void stop(){active=false;}
    int count(){return notes.size();}
    void accept(PlayedNoteTracker.Batch batch,int deviceMs){
        if(!active)return;
        if(batch.fault!=null){stop();return;}
        if(!batch.fresh)return;
        if(previous>=0)elapsed+=(deviceMs-previous)&65535;
        previous=deviceMs;
        if(elapsed>=LIMIT_MS){stop();return;}
        for(int pitch:batch.midi){
            if(held[pitch]!=null && held[pitch].start==elapsed)continue;
            if(held[pitch]!=null)held[pitch].end=Math.min(held[pitch].end,elapsed);
            Note n=new Note(pitch,elapsed);held[pitch]=n;notes.add(n);
            if(notes.size()>=LIMIT_NOTES){stop();return;}
        }
    }
    byte[] midi() throws IOException {
        if(notes.isEmpty())throw new IOException("没有收到拨弦音符");
        ArrayList<Event> events=new ArrayList<>();
        int first=notes.get(0).start;
        for(Note n:notes){
            events.add(new Event(n.start-first,n.pitch,84));
            events.add(new Event(n.end-first,n.pitch,0));
        }
        events.sort(Comparator.comparingInt((Event e)->e.at).thenComparingInt(e->e.velocity));
        ByteArrayOutputStream track=new ByteArrayOutputStream();
        track.write(new byte[]{0,(byte)255,81,3,7,(byte)161,32}); // 500 ms per quarter
        int previousEvent=0;
        for(Event e:events){
            vlq(track,e.at-previousEvent);track.write(0x90);track.write(e.pitch);track.write(e.velocity);
            previousEvent=e.at;
        }
        track.write(new byte[]{0,(byte)255,47,0});
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        DataOutputStream midi=new DataOutputStream(data);
        midi.writeBytes("MThd");midi.writeInt(6);midi.writeShort(0);midi.writeShort(1);midi.writeShort(500);
        midi.writeBytes("MTrk");midi.writeInt(track.size());track.writeTo(midi);
        return data.toByteArray();
    }
    private static void vlq(ByteArrayOutputStream out,int n){
        if(n>=128)vlqHigh(out,n>>>7);
        out.write(n&127);
    }
    private static void vlqHigh(ByteArrayOutputStream out,int n){
        if(n>=128)vlqHigh(out,n>>>7);
        out.write((n&127)|128);
    }
}
