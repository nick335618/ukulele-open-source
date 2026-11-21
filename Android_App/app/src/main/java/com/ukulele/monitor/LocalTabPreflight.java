package com.ukulele.monitor;

import com.ukulele.monitor.ai.AiScoreAssistant;
import com.ukulele.monitor.ai.TabEventBuilder;
import java.util.*;

/** Reuse the existing offline TAB/rhythm reader only when every structural check passes. */
final class LocalTabPreflight {
    static AiScoreAssistant.ScoreDraft inspect(int[] pixels,int w,int h,int bpm,List<OfflineScoreRecognizer.Glyph> glyphs){
        try{return draft(OfflineScoreRecognizer.recognize(pixels,w,h,OfflineScoreRecognizer.TAB4,60,bpm,glyphs),bpm);}
        catch(RuntimeException error){return null;}
    }
    static AiScoreAssistant.ScoreDraft draft(OfflineScoreRecognizer.Result r,int bpm){
        if(r==null||r.requiresReview||r.notes.size()<4||r.rhythmRead==0||r.rhythmMissing!=0||r.uncertain!=0||r.measureIssues!=0||r.repeatSigns!=0||r.notes.get(0).time!=0)return null;
        StringBuilder[] rows={new StringBuilder("G|"),new StringBuilder("C|"),new StringBuilder("E|"),new StringBuilder("A|")};
        StringBuilder text=new StringBuilder(),durations=new StringBuilder();int attacks=0;
        for(int i=0;i<r.notes.size();){
            long at=r.notes.get(i).time;int j=i;String[] frets={null,null,null,null};int width=1;
            while(j<r.notes.size()&&r.notes.get(j).time==at){ScorePitch.Note n=r.notes.get(j++);int c=n.preferredColumn;
                if(c<0||c>3||frets[c]!=null||n.midi<ScorePitch.OPEN[c]||n.midi>ScorePitch.OPEN[c]+18)return null;
                frets[c]=String.valueOf(n.midi-ScorePitch.OPEN[c]);width=Math.max(width,frets[c].length());
            }
            long end=j<r.notes.size()?r.notes.get(j).time:r.durationMs;double beats=(end-at)*bpm/60000.0;
            if(beats<.0625||beats>32)return null;
            if(durations.length()>0)durations.append(',');durations.append(String.format(Locale.ROOT,"%.6f",beats));
            for(int c=0;c<4;c++){String f=frets[c]==null?"":frets[c];rows[c].append(f);for(int k=f.length();k<width;k++)rows[c].append('-');rows[c].append('-');}
            if(++attacks%16==0||j==r.notes.size())for(int c=0;c<4;c++){text.append(rows[c]).append("|\n");rows[c]=new StringBuilder("GCEA".charAt(c)+"|");}
            i=j;
        }
        String transcription="BPM:"+bpm+"\nDURATIONS:"+durations+"\n"+text;
        TabEventBuilder.Result built=TabEventBuilder.build(transcription);
        if(!built.ok()||built.notes!=r.notes.size()||Math.abs(built.durationMs-r.durationMs)>2)return null;
        return new AiScoreAssistant.ScoreDraft("",transcription,built.events,"本机TAB与配套五线时值 · 速度暂按"+bpm+" BPM，可在转录中修改；请核对原谱",null,false,"");
    }
}
