package com.ukulele.monitor;
import java.util.*;import java.io.ByteArrayOutputStream;
/** UKS1 guide columns are G/C/E/A, distinct from raw ADC channel order. */
final class ScorePitch {
 static final int[] OPEN={67,60,64,69};
 static final class Note {final long time;final int midi,preferredColumn,level;Note(long t,int n){this(t,n,-1,180);}Note(long t,int n,int c){this(t,n,c,180);}Note(long t,int n,int c,int l){time=t;midi=n;preferredColumn=c;level=Math.max(1,Math.min(255,l));}}
 static int column(int midi){int best=-1,fret=100;for(int c=0;c<4;c++){int f=midi-OPEN[c];if(f>=0&&f<=18&&f<fret){best=c;fret=f;}}return best;}
 static String name(int n){return new String[]{"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"}[Math.floorMod(n,12)]+(Math.floorDiv(n,12)-1);}
 static final class Result {final byte[] events;final int outside,overflow,total;Result(byte[] b,int o,int c,int n){events=b;outside=o;overflow=c;total=n;}String warning(){return outside==0&&overflow==0?"音域完整":("音域/指法不全 · "+outside+"音超出C4—D#6，"+overflow+"音无法分配独立弦");}}
 static Result convert(List<Note> source){return convert(source,-1);}
 static Result convert(List<Note> source,long durationMs){
  if(durationMs>7200000)throw new IllegalArgumentException("曲谱时长超出范围");
  ArrayList<Note> notes=new ArrayList<>(source);Collections.sort(notes,(a,b)->Long.compare(a.time,b.time));ArrayList<byte[]> rows=new ArrayList<>();long previous=0,end=0;int outside=0,overflow=0;
  for(int i=0;i<notes.size();){long at=notes.get(i).time;if(at<0||at>7200000)throw new IllegalArgumentException("音符时间超出范围");int j=i;boolean clear=false;ArrayList<Note> group=new ArrayList<>();while(j<notes.size()&&notes.get(j).time==at){Note n=notes.get(j++);if(n.midi<0){clear=true;}else if(column(n.midi)<0){outside++;clear=true;}else group.add(n);}
   int[] costs=new int[16];Arrays.fill(costs,100000);costs[0]=0;int[][] choices=new int[16][4];for(int[] row:choices)Arrays.fill(row,-1);
   for(int n=0;n<group.size();n++){int[] next=costs.clone();int[][] pick=new int[16][];for(int m=0;m<16;m++)pick[m]=choices[m].clone();
    for(int m=0;m<16;m++)if(costs[m]<100000)for(int c=0;c<4;c++){int fret=group.get(n).midi-OPEN[c];if((m&(1<<c))!=0||fret<0||fret>18||(group.get(n).preferredColumn>=0&&group.get(n).preferredColumn!=c))continue;int nm=m|(1<<c),cost=costs[m]+fret;
     if(cost<next[nm]){next[nm]=cost;pick[nm]=choices[m].clone();pick[nm][c]=n;}}
    costs=next;choices=pick;
   }
   int best=0;for(int m=1;m<16;m++)if(costs[m]<100000&&(Integer.bitCount(m)>Integer.bitCount(best)||(Integer.bitCount(m)==Integer.bitCount(best)&&costs[m]<costs[best])))best=m;
   overflow+=group.size()-Integer.bitCount(best);boolean first=true;
   for(int c=0;c<4;c++)if(choices[best][c]>=0){Note n=group.get(choices[best][c]);emit(rows,first?at-previous:0,1<<c,n.midi-OPEN[c],n.level);previous=at;first=false;}
   if(group.isEmpty()&&clear){emit(rows,at-previous,0,0,0);previous=at;}end=Math.max(end,at);i=j;
  }
  // Preserve elapsed time across skipped notes; never compress an unavailable passage.
  if(durationMs>=0&&durationMs<end)throw new IllegalArgumentException("曲谱时长早于最后一个音符");emit(rows,Math.max(0,(durationMs>=0?durationMs:end+500)-previous),0,0,0);
  if(rows.size()>10921)throw new IllegalArgumentException("曲谱超出设备容量");ByteArrayOutputStream out=new ByteArrayOutputStream();out.write('U');out.write('K');out.write('S');out.write('1');out.write(1);out.write(0);out.write(rows.size()&255);out.write(rows.size()>>8);
  for(byte[] row:rows)out.write(row,0,row.length);return new Result(out.toByteArray(),outside,overflow,source.size());
 }
 static void emit(List<byte[]> rows,long delta,int mask,int fret,int level){while(delta>65535){rows.add(new byte[]{(byte)255,(byte)255,0,0,0,0});delta-=65535;}rows.add(new byte[]{(byte)delta,(byte)(delta>>8),(byte)mask,(byte)fret,(byte)level,0});}
}
