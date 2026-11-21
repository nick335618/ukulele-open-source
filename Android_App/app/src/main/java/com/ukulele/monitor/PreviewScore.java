package com.ukulele.monitor;

import java.util.*;

/** Offline preview score. MIDI parsing preserves pitches before ukulele mapping. */
final class PreviewScore {
    static final class Note {
        final double start,end;final int pitch,velocity;
        Note(double a,double b,int p,int v){start=a;end=Math.max(a+.005,b);pitch=p;velocity=v;}
    }
    final List<Note> notes;final double duration,musicalDuration;
    PreviewScore(List<Note> n){this(n,0);}
    PreviewScore(List<Note> n,double minimumEnd){
        n.sort(Comparator.comparingDouble(a->a.start));notes=Collections.unmodifiableList(n);
        double end=minimumEnd;for(Note x:n)end=Math.max(end,x.end);musicalDuration=end;duration=end+.08;
        if(n.isEmpty())throw new IllegalArgumentException("没有可试听音符");
        if(end>7200||n.size()>100000)throw new IllegalArgumentException("曲谱过长，暂不支持试听");
    }
    static PreviewScore fromTimeline(SongTimeline t){
        int[] open={67,60,64,69};ArrayList<Note> out=new ArrayList<>();
        for(SongTimeline.Span s:t.spans)out.add(new Note(s.startMs/1000d,s.endMs/1000d,open[s.column]+s.fret,100));
        return new PreviewScore(out,t.durationMs/1000d);
    }
    PreviewScore shifted(int semitones){
        if(semitones==0)return this;
        ArrayList<Note> shifted=new ArrayList<>();
        for(Note note:notes){
            int pitch=note.pitch+semitones;
            if(pitch<0||pitch>127)throw new IllegalArgumentException("移调后超出 MIDI 音高范围");
            shifted.add(new Note(note.start,note.end,pitch,note.velocity));
        }
        return new PreviewScore(shifted,musicalDuration);
    }
    static boolean isMidi(byte[] b){return b!=null&&b.length>=4&&b[0]=='M'&&b[1]=='T'&&b[2]=='h'&&b[3]=='d';}
    private static final class Edge {
        long tick;int channel,pitch,value,kind,order;double seconds;
        Edge(long t,int k,int c,int p,int v,int o){tick=t;kind=k;channel=c;pitch=p;value=v;order=o;}
    }
    private static final class Tempo {long tick;int us;Tempo(long t,int u){tick=t;us=u;}}
    private static final class Reader {
        final byte[] b;int p,end;
        Reader(byte[] data,int a,int e){b=data;p=a;end=e;}
        int u8(){if(p>=end)throw new IllegalArgumentException("MIDI数据截断");return b[p++]&255;}
        int u16(){return (u8()<<8)|u8();}
        long u32(){return ((long)u16()<<16)|u16();}
        long vlq(){long v=0;for(int i=0;i<4;i++){int x=u8();v=(v<<7)|(x&127);if(x<128)return v;}throw new IllegalArgumentException("MIDI变长数值错误");}
        void skip(long n){if(n<0||n>end-p)throw new IllegalArgumentException("MIDI区块长度错误");p+=(int)n;}
        boolean tag(String s){for(int i=0;i<4;i++)if(u8()!=s.charAt(i))return false;return true;}
    }
    static PreviewScore fromMidi(byte[] bytes){
        if(!isMidi(bytes)||bytes.length>32*1024*1024)throw new IllegalArgumentException("MIDI文件无效");
        Reader r=new Reader(bytes,0,bytes.length);r.skip(4);long h=r.u32();if(h<6||h>r.end-r.p)throw new IllegalArgumentException("MIDI头部错误");
        int fmt=r.u16(),tracks=r.u16(),division=r.u16();r.skip(h-6);
        if(fmt>1||tracks<1||tracks>256||division==0||(fmt==0&&tracks!=1))throw new IllegalArgumentException("支持标准MIDI格式0/1");
        ArrayList<Edge> edges=new ArrayList<>();ArrayList<Tempo> tempi=new ArrayList<>();tempi.add(new Tempo(0,500000));long lastTick=0;
        for(int tr=0;tr<tracks;tr++){
            if(!r.tag("MTrk"))throw new IllegalArgumentException("MIDI轨道错误");long length=r.u32();if(length>r.end-r.p)throw new IllegalArgumentException("MIDI轨道截断");
            Reader q=new Reader(bytes,r.p,r.p+(int)length);r.skip(length);long tick=0;int running=0;
            while(q.p<q.end){
                tick+=q.vlq();lastTick=Math.max(lastTick,tick);int status=q.u8(),first=-1;
                if(status<128){if(running==0)throw new IllegalArgumentException("MIDI运行状态错误");first=status;status=running;}
                if(status==255){int kind=q.u8();long n=q.vlq();if(n>q.end-q.p)throw new IllegalArgumentException("MIDI元事件截断");
                    if(kind==81&&n==3){int us=(q.u8()<<16)|(q.u8()<<8)|q.u8();if(us==0)throw new IllegalArgumentException("MIDI速度错误");tempi.add(new Tempo(tick,us));}
                    else q.skip(n);if(tempi.size()>200000)throw new IllegalArgumentException("MIDI速度事件过多");if(kind==47)break;continue;
                }
                if(status==240||status==247){running=0;q.skip(q.vlq());continue;}
                if(status<128||status>=240)throw new IllegalArgumentException("不支持的MIDI事件");running=status;
                int kind=status&240,channel=status&15,a=first<0?q.u8():first,b=(kind==192||kind==208)?0:q.u8();
                if(a>127||b>127)throw new IllegalArgumentException("MIDI音符范围错误");
                if(kind==144||kind==128)edges.add(new Edge(tick,kind==144&&b>0?1:0,channel,a,b,edges.size()));
                else if(kind==176&&(a==64||a==120||a==123))edges.add(new Edge(tick,a==64?2:3,channel,0,b,edges.size()));
                if(edges.size()>200000)throw new IllegalArgumentException("MIDI事件过多");
            }
        }
        edges.sort(Comparator.comparingLong((Edge e)->e.tick).thenComparingInt(e->e.order));tempi.sort(Comparator.comparingLong(t->t.tick));
        int tempo=500000,ti=0;long cursor=0;double seconds=0,perTick=0;
        if((division&32768)!=0){int fps=-(byte)(division>>8),sub=division&255;if((fps!=24&&fps!=25&&fps!=29&&fps!=30)||sub==0)throw new IllegalArgumentException("MIDI时间格式错误");perTick=1d/((fps==29?29.97:fps)*sub);}
        for(Edge e:edges){
            if(perTick>0)e.seconds=e.tick*perTick;
            else{while(ti<tempi.size()&&tempi.get(ti).tick<=e.tick){Tempo t=tempi.get(ti++);seconds+=(t.tick-cursor)*tempo/(division*1000000d);cursor=t.tick;tempo=t.us;}e.seconds=seconds+(e.tick-cursor)*tempo/(division*1000000d);}
            if(e.seconds>7200)throw new IllegalArgumentException("MIDI超过2小时");
        }
        double scoreEnd;
        if(perTick>0)scoreEnd=lastTick*perTick;
        else{while(ti<tempi.size()&&tempi.get(ti).tick<=lastTick){Tempo t=tempi.get(ti++);seconds+=(t.tick-cursor)*tempo/(division*1000000d);cursor=t.tick;tempo=t.us;}scoreEnd=seconds+(lastTick-cursor)*tempo/(division*1000000d);}
        @SuppressWarnings("unchecked") ArrayDeque<Edge>[] active=(ArrayDeque<Edge>[])new ArrayDeque<?>[2048];
        @SuppressWarnings("unchecked") ArrayList<Edge>[] held=(ArrayList<Edge>[])new ArrayList<?>[16];
        boolean[] pedal=new boolean[16];ArrayList<Note> notes=new ArrayList<>();double last=0;
        for(Edge e:edges){last=e.seconds;int key=e.channel*128+e.pitch;
            if(e.kind==1){if(active[key]==null)active[key]=new ArrayDeque<>();active[key].add(e);}
            else if(e.kind==0){if(active[key]!=null&&!active[key].isEmpty()){Edge on=active[key].remove();if(pedal[e.channel]){if(held[e.channel]==null)held[e.channel]=new ArrayList<>();held[e.channel].add(on);}else notes.add(new Note(on.seconds,e.seconds,on.pitch,on.value));}}
            else if(e.kind==2){pedal[e.channel]=e.value>=64;if(!pedal[e.channel]&&held[e.channel]!=null){for(Edge on:held[e.channel])notes.add(new Note(on.seconds,e.seconds,on.pitch,on.value));held[e.channel].clear();}}
            else{pedal[e.channel]=false;for(int p=0;p<128;p++){ArrayDeque<Edge> q=active[e.channel*128+p];if(q!=null)while(!q.isEmpty()){Edge on=q.remove();notes.add(new Note(on.seconds,e.seconds,on.pitch,on.value));}}
                if(held[e.channel]!=null){for(Edge on:held[e.channel])notes.add(new Note(on.seconds,e.seconds,on.pitch,on.value));held[e.channel].clear();}}
        }
        for(ArrayDeque<Edge> q:active)if(q!=null)for(Edge on:q)notes.add(new Note(on.seconds,Math.max(last,on.seconds)+.5,on.pitch,on.value));
        for(ArrayList<Edge> q:held)if(q!=null)for(Edge on:q)notes.add(new Note(on.seconds,Math.max(last,on.seconds)+.5,on.pitch,on.value));
        return new PreviewScore(notes,scoreEnd);
    }
}
