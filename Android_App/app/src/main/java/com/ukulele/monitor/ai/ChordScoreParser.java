package com.ukulele.monitor.ai;

import java.util.*;
import java.util.regex.*;
import com.ukulele.monitor.fun.ChordLibrary;

/** Editable chord bars, with explicit tempo/meter; never replace an unknown chord by silence. */
public final class ChordScoreParser {
    public static final class Result {
        public final String transcription,events,error;public final int notes;public final long durationMs;
        Result(String t,String e,String x,int n,long ms){transcription=t;events=e;error=x;notes=n;durationMs=ms;}
        public boolean ok(){return error==null&&notes>0;}
    }
    private ChordScoreParser(){}
    public static boolean looksLike(String s){return s!=null&&(s.contains("CHORDS:")||s.contains("和弦谱")||s.contains("PATTERN:"));}
    public static String canonical(List<String> names,int bpm,String meter,String pattern){
        return "CHORDS:\nBPM:"+bpm+"\nMETER:"+meter+"\nPATTERN:"+(pattern==null||pattern.trim().isEmpty()?"D D D D":pattern)+"\n"+String.join(" | ",names);
    }
    public static Result parse(String raw){
        String text=raw==null?"":raw.trim();int bpm=96,num=4,den=4;String pattern="D D D D";
        ArrayList<String> bars=new ArrayList<>();
        try{
            StringBuilder body=new StringBuilder();
            for(String line:text.split("\\r?\\n")){
                String t=line.trim();if(t.isEmpty()||t.startsWith("#")||t.equals("CHORDS:"))continue;
                if(t.startsWith("BPM:")){bpm=Integer.parseInt(t.substring(4).trim());continue;}
                if(t.startsWith("METER:")){String[] m=t.substring(6).trim().split("/");if(m.length!=2)throw new IllegalArgumentException("拍号应为3/4、4/4或6/8");num=Integer.parseInt(m[0]);den=Integer.parseInt(m[1]);continue;}
                if(t.startsWith("PATTERN:")){pattern=t.substring(8).trim();continue;}
                // Old editable drafts used this heading and a space-separated bar list.
                if(t.startsWith("和弦谱")){Matcher b=Pattern.compile("BPM\\s+(\\d+)").matcher(t);if(b.find())bpm=Integer.parseInt(b.group(1));continue;}
                body.append(t).append(' ');
            }
            if(bpm<20||bpm>400||num<1||num>12||(den!=2&&den!=4&&den!=8&&den!=16))throw new IllegalArgumentException("速度或拍号超出范围");
            String b=body.toString().trim();
            if(b.contains("|")){for(String v:b.split("\\|"))if(!v.trim().isEmpty())bars.add(v.trim());}
            else for(String v:b.split("\\s+"))if(!v.isEmpty())bars.add(v);
            if(bars.isEmpty()||bars.size()>512)throw new IllegalArgumentException("请输入1至512小节和弦");
            String slots=pattern.replaceAll("\\s+","");
            int strokes=0;for(char ch:slots.toCharArray()){
                if("DdUu上下".indexOf(ch)>=0)strokes++;
                else if(ch!='-'&&ch!='·')throw new IllegalArgumentException("节奏型请使用D/U（扫弦）和-（空拍）");
            }
            if(strokes==0||strokes>16||slots.length()>32)throw new IllegalArgumentException("节奏型最多32格、16次扫弦");
            Map<String,ChordLibrary.Chord> library=new HashMap<>();for(ChordLibrary.Chord c:ChordLibrary.all())library.put(key(c.name),c);
            StringBuilder events=new StringBuilder();int notes=0;long elapsed=0;
            for(String bar:bars){
                String[] names=bar.split("\\s+");
                long barStart=elapsed;
                long barEnd=barStart+Math.round(60000.0/bpm*num*4/den);
                for(int k=0;k<names.length;k++){
                    ChordLibrary.Chord c=library.get(key(names[k]));
                    if(c==null)throw new IllegalArgumentException("未支持和弦："+names[k]+"，请校对或换成和弦库中的指法");
                    long sectionStart=barStart+Math.round((barEnd-barStart)*(double)k/names.length);
                    long sectionEnd=barStart+Math.round((barEnd-barStart)*(double)(k+1)/names.length);
                    for(int j=0;j<slots.length();j++){
                        long next=sectionStart+Math.round((sectionEnd-sectionStart)*(double)(j+1)/slots.length());
                        if("-·".indexOf(slots.charAt(j))<0)for(int string=0;string<4;string++)if(c.frets[string]>=0){append(events,0,1<<string,c.frets[string],210);notes++;}
                        append(events,next-elapsed,0,0,0);elapsed=next;
                    }
                }
            }
            if(events.toString().split("\\n").length>10922)throw new IllegalArgumentException("曲谱过长，请分段导入");
            return new Result(text,events.toString().trim(),null,notes,elapsed);
        }catch(Exception e){return new Result(text,"",e.getMessage(),0,0);}
    }
    private static String key(String s){return s.trim().replace("♯","#").replace("♭","b").toLowerCase(Locale.ROOT);}
    private static void append(StringBuilder s,long delta,int mask,int fret,int level){
        while(delta>60000){s.append("60000,0,0,0,0\n");delta-=60000;}
        s.append(Math.max(0,delta)).append(',').append(mask).append(',').append(fret).append(',').append(level).append(",0\n");
    }
}
