package com.ukulele.monitor;
import java.util.*;import java.io.ByteArrayOutputStream;
/** V2 full-state snapshots: flag 0x80 carries a held note without another attack. */
final class GuideSong {
 static final class Note {long start,end;final int column,fret,level;Note(long a,long b,int c,int f,int l){start=a;end=b;column=c;fret=f;level=l;}}
 static byte[] upgrade(byte[] raw){SongTimeline t=SongTimeline.parse(raw);if(raw[4]==2)return raw;ArrayList<Note> n=new ArrayList<>();for(SongTimeline.Span s:t.spans)n.add(new Note(s.startMs,s.endMs,s.column,s.fret,s.level));return encode(n,t.durationMs);}
 /** LED-only projection: preserve attack times and score end; blank repeated frets before their next attack.
  * V2 source/preview/scoring data must remain unchanged. Carries merge into one span, so held chord tones do not blink.
  */
 static byte[] forLights(byte[] raw){SongTimeline t=SongTimeline.parse(raw);ArrayList<Note> notes=new ArrayList<>();for(SongTimeline.Span s:t.spans)notes.add(new Note(s.startMs,s.endMs,s.column,s.fret,s.level));return encode(notes,t.durationMs);}
 static byte[] encode(List<Note> notes,long duration){
  if(duration<0||duration>7200000)throw new IllegalArgumentException("曲谱时长超出范围");
  for(int c=0;c<4;c++){ArrayList<Note> lane=new ArrayList<>();for(Note n:notes)if(n.column==c)lane.add(n);lane.sort(Comparator.comparingLong(n->n.start));
   for(int i=0;i+1<lane.size();i++){Note a=lane.get(i),b=lane.get(i+1);a.end=Math.min(a.end,b.start);if(a.fret==b.fret){long gap=Math.min(60,(b.start-a.start)/2);a.end=Math.min(a.end,Math.max(a.start+1,b.start-gap));}}
  }
  TreeMap<Long,List<Note>> starts=new TreeMap<>();TreeSet<Long> times=new TreeSet<>();times.add(duration);
  for(Note n:notes){if(n.start<0||n.end>duration||n.end<=n.start||n.column<0||n.column>3||n.fret<0||n.fret>18)throw new IllegalArgumentException("音符时值/弦位错误");starts.computeIfAbsent(n.start,k->new ArrayList<>()).add(n);times.add(n.start);times.add(n.end);}
  ArrayList<byte[]> rows=new ArrayList<>();Note[] active=new Note[4];long previous=0;
  for(long at:times){
   while(at-previous>65535){previous+=65535;snapshot(rows,65535,active,0);}
   for(int c=0;c<4;c++)if(active[c]!=null&&active[c].end<=at)active[c]=null;
   int attacks=0;List<Note> on=starts.get(at);if(on!=null)for(Note n:on){active[n.column]=n;attacks|=1<<n.column;}
   snapshot(rows,(int)(at-previous),active,attacks);previous=at;
  }
  if(rows.size()>10921)throw new IllegalArgumentException("精确时值曲谱超出设备容量，请分段导入");
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] h={'U','K','S','1',2,0,(byte)rows.size(),(byte)(rows.size()>>8)};out.write(h,0,h.length);for(byte[] r:rows)out.write(r,0,r.length);return out.toByteArray();
 }
 private static void snapshot(List<byte[]> rows,int delta,Note[] active,int attacks){boolean first=true;for(int c=0;c<4;c++)if(active[c]!=null){Note n=active[c];int d=first?delta:0;rows.add(new byte[]{(byte)d,(byte)(d>>8),(byte)(1<<c),(byte)n.fret,(byte)n.level,(byte)((attacks&(1<<c))!=0?0:128)});first=false;}
  if(first)rows.add(new byte[]{(byte)delta,(byte)(delta>>8),0,0,0,0});}
}
