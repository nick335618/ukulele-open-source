package com.ukulele.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 从原有 UKS1 帧读取本地指引，不改写上传数据。无 Android 依赖，便于校验曲库。 */
public final class SongTimeline {
    public static final class Event {
        public final long timeMs;
        public final int deltaMs, mask, fret, level, flags;
        public final boolean continuation;
        Event(long time, int delta, int columns, int row, int brightness, int eventFlags,boolean carry) {
            timeMs = time; deltaMs = delta; mask = columns; fret = row; level = brightness; flags = eventFlags;
            continuation=carry;
        }
        public boolean attack(){return level>0&&mask!=0&&!continuation;}
    }

    /** delta=0 的多行事件属于同一时刻，可在画面中一起显示不同弦/品位。 */
    public static final class Step {
        public final long timeMs;
        public final List<Event> events;
        Step(long time, List<Event> rows) {
            timeMs = time; events = Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    public final List<Event> events;
    public final List<Step> steps;
    public final long durationMs;
    public final int noteCount;
    public final List<Span> spans;
    public static final class Span {
        public final long startMs,endMs;public final int column,fret,level;
        Span(long start,long end,int c,Event e){startMs=start;endMs=end;column=c;fret=e.fret;level=e.level;}
    }

    private SongTimeline(List<Event> rows, List<Step> groups, long duration, int notes,boolean exact) {
        events = Collections.unmodifiableList(rows);
        steps = Collections.unmodifiableList(groups);
        durationMs = duration; noteCount = notes;
        ArrayList<Span> intervals=new ArrayList<>();Event[] active=new Event[4];long[] start=new long[4];
        for(Step s:groups){Event[] target=new Event[4];boolean rest=false;for(Event e:s.events){if(e.mask==0)rest=true;if(e.level>0)for(int c=0;c<4;c++)if((e.mask&(1<<c))!=0){if(exact&&target[c]!=null)throw new IllegalArgumentException("同拍快照弦位重复");if(target[c]==null||target[c].fret<e.fret)target[c]=e;}}
            if(exact&&rest)for(Event e:target)if(e!=null)throw new IllegalArgumentException("同拍快照不能混合休止和发声");
            for(int c=0;c<4;c++){
                if(target[c]!=null&&target[c].continuation&&(active[c]==null||active[c].fret!=target[c].fret))throw new IllegalArgumentException("延续音没有对应的活动音符");
                if(active[c]!=null&&(target[c]==null||target[c].fret!=active[c].fret||!target[c].continuation)){if(s.timeMs>start[c])intervals.add(new Span(start[c],s.timeMs,c,active[c]));active[c]=null;}
                if(active[c]==null&&target[c]!=null){active[c]=target[c];start[c]=s.timeMs;}
            }
        }
        for(int c=0;c<4;c++)if(active[c]!=null&&duration>start[c])intervals.add(new Span(start[c],duration,c,active[c]));
        intervals.sort((a,b)->Long.compare(a.startMs,b.startMs));spans=Collections.unmodifiableList(intervals);
    }

    public static SongTimeline parse(byte[] data) {
        if (data == null || data.length < 8 || data.length > 65535) throw new IllegalArgumentException("UKS1 数据长度错误");
        if (data[0] != 'U' || data[1] != 'K' || data[2] != 'S' || data[3] != '1' || (data[4] != 1&&data[4]!=2) || data[5] != 0)
            throw new IllegalArgumentException("UKS1 头部/版本错误");
        int count = unsigned(data[6]) | (unsigned(data[7]) << 8);
        if (count == 0 || data.length != 8 + count * 6) throw new IllegalArgumentException("UKS1 事件数量与长度不一致");
        ArrayList<Event> rows = new ArrayList<>(count);
        ArrayList<Step> groups = new ArrayList<>();
        ArrayList<Event> sameTime = new ArrayList<>();
        long time = 0, groupTime = -1;
        int notes = 0;
        for (int offset = 8; offset < data.length; offset += 6) {
            int delta = unsigned(data[offset]) | (unsigned(data[offset + 1]) << 8);
            int mask = unsigned(data[offset + 2]), fret = unsigned(data[offset + 3]);
            int level = unsigned(data[offset + 4]), flags = unsigned(data[offset + 5]);
            if (mask > 15 || fret > 18) throw new IllegalArgumentException("UKS1 弦位/品位超出原协议范围");
            if(data[4]==2&&(flags!=0&&flags!=128||mask==0&&(level!=0||flags!=0)||mask!=0&&level==0))throw new IllegalArgumentException("精确时值事件标记错误");
            time += delta;
            if(time>7200000)throw new IllegalArgumentException("曲谱超过2小时");
            Event event = new Event(time, delta, mask, fret, level, flags,data[4]==2&&(flags&128)!=0);
            if (time != groupTime) {
                if (!sameTime.isEmpty()) groups.add(new Step(groupTime, sameTime));
                sameTime.clear(); groupTime = time;
            }
            sameTime.add(event); rows.add(event);
            if (event.attack()) notes += Integer.bitCount(mask);
        }
        if (!sameTime.isEmpty()) groups.add(new Step(groupTime, sameTime));
        // The device holds a final lit group for 500 musical ms when a raw
        // UKS file omits its ending REST. Keep local practice on the same clock.
        long duration=time;
        if(!groups.isEmpty())for(Event e:groups.get(groups.size()-1).events)
            if(e.level>0&&e.mask!=0){if(data[4]==2)throw new IllegalArgumentException("精确时值曲谱缺少末尾熄灯");duration=time+500;break;}
        return new SongTimeline(rows, groups, duration, notes,data[4]==2);
    }

    private static int unsigned(byte value) { return value & 255; }

    /** 最近到达的整组事件；歌曲开始前返回 null。熄灯事件仍保留在组内。 */
    public Step currentStepAt(long elapsedMs) {
        int lo = 0, hi = steps.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (steps.get(mid).timeMs <= elapsedMs) { found = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return found < 0 ? null : steps.get(found);
    }

    public List<Event> upcoming(long elapsedMs, long horizonMs) {
        ArrayList<Event> upcoming = new ArrayList<>();
        for (Event event : events) {
            if (event.timeMs > elapsedMs + horizonMs) break;
            if (event.timeMs >= elapsedMs && event.attack()) upcoming.add(event);
        }
        return upcoming;
    }
}
