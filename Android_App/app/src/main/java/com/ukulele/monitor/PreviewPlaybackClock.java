package com.ukulele.monitor;
import java.util.ArrayList;

/** Map frames actually consumed by AudioTrack to score time, including buffered speed changes. */
final class PreviewPlaybackClock {
    private static final class Segment {
        final long frame; final double ms,speed;
        Segment(long f,double t,double s){frame=f;ms=t;speed=s;}
    }
    private final ArrayList<Segment> segments=new ArrayList<>();
    private final double duration;
    private long wraps,lastRaw,lastFrame; private double position;
    PreviewPlaybackClock(double totalMs,double speed){duration=totalMs;segment(0,0,speed);}
    synchronized void segment(long frame,double ms,double speed){
        if(frame<0||ms<0||!Double.isFinite(ms)||!Double.isFinite(speed)||speed<.25||speed>2)throw new IllegalArgumentException("Invalid playback segment");
        if(!segments.isEmpty()&&frame<segments.get(segments.size()-1).frame)throw new IllegalArgumentException("Playback frames reversed");
        if(!segments.isEmpty()&&frame==segments.get(segments.size()-1).frame)segments.remove(segments.size()-1);
        segments.add(new Segment(frame,ms,speed));
    }
    synchronized double consumed(int playbackHead){
        long raw=playbackHead&0xffffffffL;
        if(raw<lastRaw&&lastRaw-raw>0x80000000L)wraps+=0x100000000L;
        lastRaw=raw;long frame=Math.max(lastFrame,wraps+raw);lastFrame=frame;
        int lo=0,hi=segments.size()-1;
        while(lo<hi){int mid=(lo+hi+1)>>>1;if(segments.get(mid).frame<=frame)lo=mid;else hi=mid-1;}
        Segment s=segments.get(lo);position=Math.max(position,Math.min(duration,s.ms+(frame-s.frame)*1000d/PreviewPcm.RATE*s.speed));
        return position;
    }
    synchronized double speed(){int i=segments.size()-1;while(i>0&&segments.get(i).frame>lastFrame)i--;return segments.get(i).speed;}
}
