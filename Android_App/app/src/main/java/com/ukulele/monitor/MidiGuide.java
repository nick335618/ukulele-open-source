package com.ukulele.monitor;
import java.util.*;
/** Uses the existing full MIDI decoder, including note-off, tempo and sustain. */
final class MidiGuide {
 static ScorePitch.Result convert(byte[] raw){return convert(raw,0);}
 static ScorePitch.Result convert(byte[] raw,int semitones){return convert(raw,semitones,false);}
 static ScorePitch.Result convert(byte[] raw,int semitones,boolean balanced){return convert(PreviewScore.fromMidi(raw).shifted(semitones),balanced);}
 static ScorePitch.Result convert(PreviewScore score){return convert(score,false);}
 static ScorePitch.Result convert(PreviewScore score,boolean balanced){
  int[] usage=new int[4];int lastFret=-1,lastPitch=-1,lastString=-1;
  ArrayList<GuideSong.Note> out=new ArrayList<>();GuideSong.Note[] active=new GuideSong.Note[4];int outside=0,overflow=0;
  List<PreviewScore.Note> all=score.notes;
  for(int i=0;i<all.size();){long at=Math.round(all.get(i).start*1000);ArrayList<PreviewScore.Note> group=new ArrayList<>();int j=i;
   while(j<all.size()&&Math.round(all.get(j).start*1000)==at){PreviewScore.Note n=all.get(j++);if(ScorePitch.column(n.pitch)<0)outside++;else group.add(n);}
   for(int c=0;c<4;c++)if(active[c]!=null&&active[c].end<=at)active[c]=null;
   int[] costs=new int[16];Arrays.fill(costs,100000);costs[0]=0;int[][] chosen=new int[16][4];for(int[] p:chosen)Arrays.fill(p,-1);
   int minimumUse=Arrays.stream(usage).min().orElse(0);
   for(int q=0;q<group.size();q++){int[] next=costs.clone();int[][] pick=new int[16][];for(int m=0;m<16;m++)pick[m]=chosen[m].clone();
    for(int m=0;m<16;m++)if(costs[m]<100000)for(int c=0;c<4;c++){int f=group.get(q).pitch-ScorePitch.OPEN[c];if((m&(1<<c))!=0||f<0||f>18)continue;int nm=m|1<<c;
     int fingeringCost=f;
     if(balanced){
      fingeringCost=2*f+(lastFret<0?0:Math.abs(f-lastFret))+8*Math.min(8,usage[c]-minimumUse);
      if(group.size()==1&&group.get(q).pitch==lastPitch&&c!=lastString)fingeringCost+=100;
     }
     int cost=costs[m]+fingeringCost+(active[c]==null||active[c].fret==f?0:1000);if(cost<next[nm]){next[nm]=cost;pick[nm]=chosen[m].clone();pick[nm][c]=q;}}
    costs=next;chosen=pick;
   }
   int best=0;for(int m=1;m<16;m++)if(costs[m]<100000&&(Integer.bitCount(m)>Integer.bitCount(best)||Integer.bitCount(m)==Integer.bitCount(best)&&costs[m]<costs[best]))best=m;
   overflow+=group.size()-Integer.bitCount(best);
   for(int c=0;c<4;c++)if(chosen[best][c]>=0){if(active[c]!=null)active[c].end=at;PreviewScore.Note n=group.get(chosen[best][c]);GuideSong.Note added=new GuideSong.Note(at,Math.max(at+1,Math.round(n.end*1000)),c,n.pitch-ScorePitch.OPEN[c],Math.max(1,Math.min(255,n.velocity*2)));active[c]=added;out.add(added);usage[c]++;
    if(group.size()==1){lastPitch=n.pitch;lastString=c;lastFret=added.fret;}
   }
   i=j;
  }
  long end=Math.round(score.musicalDuration*1000);byte[] events=GuideSong.encode(out,end);return new ScorePitch.Result(events,outside,overflow,all.size());
 }
}
