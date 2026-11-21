package com.ukulele.monitor;

/** Deterministic practice classification, not a certified exam grade. */
final class ScoreAnalysis {
 final String difficulty;final int notes,maxFret,maxChord;final double notesPerSecond;final long duration;
 private ScoreAnalysis(String d,int n,int f,int c,double r,long t){difficulty=d;notes=n;maxFret=f;maxChord=c;notesPerSecond=r;duration=t;}
 static ScoreAnalysis of(byte[] bytes){
  SongTimeline t=SongTimeline.parse(bytes);int fret=0,chord=0,jump=0,previous=0;
  for(SongTimeline.Step step:t.steps){int count=0;for(SongTimeline.Event e:step.events)if(e.level>0&&e.mask!=0){fret=Math.max(fret,e.fret);count+=Integer.bitCount(e.mask);jump=Math.max(jump,Math.abs(e.fret-previous));previous=e.fret;}chord=Math.max(chord,count);}
  double r=t.noteCount/Math.max(1.0,t.durationMs/1000.0);
  String d=(fret>10||chord>=3||r>4||jump>7)?"挑战":(fret>5||chord>=2||r>2||jump>4)?"进阶":"入门";
  return new ScoreAnalysis(d,t.noteCount,fret,chord,r,t.durationMs);
 }
}
