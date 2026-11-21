package com.ukulele.monitor.ai;

import java.util.*;
import java.util.regex.*;

/** Separates written notes from prose. No language model is allowed to complete missing notes. */
public final class JianpuText {
    public static final class Result {
        public final String text;public final boolean cleaned,barDraft,visibleOrder;public final int octave;
        Result(String s,boolean c,boolean b,int o,boolean v){text=s;cleaned=c;barDraft=b;octave=o;visibleOrder=v;}
    }
    private JianpuText(){}
    private static String remove(String s,Pattern p){return p.matcher(s).replaceAll(" ");}
    public static Result normalize(String raw){
        String text=raw==null?"":raw.replace('\uFEFF',' ').replace('＝','=').replace('：',':').replace('｜','|').replace('，',',').replace('’','\'').replace('‘','\'').replace('·','.').replace('•','.').replace('—','-').replace('–','-');
        for(int i=0;i<=9;i++)text=text.replace((char)('０'+i),(char)('0'+i));
        text=text.replaceAll("(?m)^\\s*#(?:\\s|$).*$","");
        boolean draft=Pattern.compile("(?im)^\\s*TIMING\\s*[:=]\\s*BAR_DRAFT\\s*$").matcher(text).find();
        boolean visible=Pattern.compile("(?im)^\\s*REPEAT\\s*[:=]\\s*VISIBLE\\s*$").matcher(text).find();
        int octave=0;Matcher shift=Pattern.compile("(?im)^\\s*OCTAVE\\s*[:=]\\s*([+-]?\\d+)\\s*$").matcher(text);if(shift.find()){octave=Integer.parseInt(shift.group(1));if(octave< -2||octave>2)throw new IllegalArgumentException("整首八度调整范围-2至2");}
        text=text.replaceAll("(?im)^\\s*(?:TIMING|OCTAVE|REPEAT)\\s*[:=].*$","");
        // Full-line canonical headers must not erase notes that share a pasted paragraph.
        Pattern keyP=Pattern.compile("(?i)(?<![0-9])1\\s*=\\s*([A-G])([#b♯♭]?)([3-6]?)(?:调)?");
        Pattern bpmP=Pattern.compile("(?i)\\bBPM\\s*[:=]\\s*(\\d{1,3})");
        Pattern meterP=Pattern.compile("(?<![0-9/])([1-9][0-9]?)\\s*/\\s*(2|4|8|16)(?:拍)?(?![0-9/])");
        Matcher k=keyP.matcher(text),b=bpmP.matcher(text),m=meterP.matcher(text);
        String key=k.find()?k.group(1).toUpperCase(Locale.ROOT)+k.group(2).replace('♯','#').replace('♭','b')+k.group(3):"C";
        String bpm=b.find()?b.group(1):"96",meter=m.find()?m.group(1)+"/"+m.group(2):"4/4";
        boolean mixed=Pattern.compile("[\\p{IsHan}]").matcher(text).find()||text.contains("《")||text.contains("（");
        if(!mixed){
            text=text.replace('(',' ').replace(')',' ');
            text=text.replaceAll("(?m)^\\s*#(?:\\s|$).*$","");
            StringBuilder compact=new StringBuilder();
            for(String line:text.split("\\r?\\n")){
                if(line.trim().matches("(?i)(?:BPM\\s*[:=]\\s*\\d{1,3}|1\\s*=\\s*[A-G][#b]?[3-6]?|[1-9][0-9]?/(?:2|4|8|16))"))compact.append(line);
                else{
                    Matcher inlineKey=keyP.matcher(line),inlineBpm=bpmP.matcher(line);boolean hadHeader=inlineKey.find()||inlineBpm.find();
                    inlineKey=keyP.matcher(line);while(inlineKey.find())compact.append("1=").append(inlineKey.group(1)).append(inlineKey.group(2)).append(inlineKey.group(3)).append('\n');
                    inlineBpm=bpmP.matcher(line);while(inlineBpm.find())compact.append("BPM:").append(inlineBpm.group(1)).append('\n');
                    String rest=remove(remove(line,keyP),bpmP);
                    Matcher inlineMeter=Pattern.compile("^\\s*((?:[2-9]|1[0-6])/(?:2|4|8|16))(?=\\s|$)").matcher(rest);
                    if(hadHeader&&inlineMeter.find()){compact.append(inlineMeter.group(1)).append('\n');rest=rest.substring(inlineMeter.end());}
                    compact.append(rest.replaceAll("(?<=[0-7.,'_~])(?=[0-7])"," "));
                }
                compact.append('\n');
            }
            text=compact.toString();
            return new Result(text.trim(),false,draft,octave,visible);
        }
        String body=meterP.matcher(remove(remove(text,keyP),bpmP)).replaceFirst(" ").replaceAll("(?m)^\\s*[1-9][0-9]?/(?:2|4|8|16)\\s*$"," ");
        body=body.replaceAll("《[^》]*》|（[^）]*[\\p{IsHan}a-zA-Z][^）]*）|\\([^)]*[\\p{IsHan}a-zA-Z][^)]*\\)"," ").replaceAll("[()（）]"," ");
        body=body.replaceAll("(?im)^\\s*#(?:\\s|$).*$","").replaceAll("(?i)\\b[A-G][0-9]?\\s*:"," ");
        body=body.replaceAll("[\\p{IsHan}]+|(?i)(?!b(?=[0-7]))[a-z]+","\n");
        StringBuilder notes=new StringBuilder();
        for(String line:body.split("\\r?\\n")){
            String v=line.replaceAll("(?<!\\|):(?!\\|)|[：、；;！!\"“”]"," ").replace('？','?').trim();
            // Isolated dates/counts in surrounding lyrics are not note rows.
            int digits=0;for(char ch:v.toCharArray())if(ch>='0'&&ch<='7')digits++;
            if(digits<2&&!v.contains("|"))continue;
            if(!v.matches("[0-9?#b',_./~|:\\-\\s]+"))continue;
            notes.append(v).append('\n');
        }
        // A dot between adjacent degrees belongs to the left token; degree digits stay separate.
        String music=notes.toString().replaceAll("(?<=[0-7.,'_~])(?=[0-7])"," ");
        return new Result("BPM:"+bpm+"\n1="+key+"\n"+meter+"\n"+music.trim(),true,draft,octave,visible);
    }
}
