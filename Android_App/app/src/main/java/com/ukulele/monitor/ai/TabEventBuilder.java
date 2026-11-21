package com.ukulele.monitor.ai;

import java.util.*;

/** A TAB fingering is already arranged: preserve each original string, including unisons. */
public final class TabEventBuilder {
    public static final class Result {
        public final String events,error;public final int notes;public final long durationMs;public final boolean estimatedRhythm;
        Result(String e,String x,int n,long t){this(e,x,n,t,true);}
        Result(String e,String x,int n,long t,boolean estimated){events=e;error=x;notes=n;durationMs=t;estimatedRhythm=estimated;}
        public boolean ok(){return error==null&&notes>0;}
    }
    private TabEventBuilder(){}
    public static String canonical(String source){
        String plain=source.indexOf('<')>=0?UkuleleTabParser.htmlToText(source):source;
        StringBuilder headers=new StringBuilder();
        java.util.regex.Matcher bpm=java.util.regex.Pattern.compile("(?i)\\bBPM\\s*[:=]?\\s*(\\d{2,3})").matcher(plain);
        headers.append("BPM:").append(bpm.find()?bpm.group(1):"120").append('\n');
        for(String line:plain.split("\\r?\\n"))if(line.trim().startsWith("DURATIONS:"))headers.append(line.trim()).append('\n');
        return headers+UkuleleTabParser.extractTabText(plain);
    }
    public static Result build(String text){
        if(text==null||text.contains("?")||text.contains("…"))return new Result("","TAB仍有未确定音符",0,0);
        StringBuilder s=new StringBuilder();int count=0;long pending=0,duration=0;
        List<UkuleleTabParser.Column> columns=UkuleleTabParser.parseColumns(text);
        ArrayList<Double> beats=null;int bpm=120,index=0;
        try{
            for(String line:text.split("\\r?\\n")){
                if(line.trim().matches("(?i)BPM\\s*[:=].*"))bpm=Integer.parseInt(line.replaceFirst("(?i)^\\s*BPM\\s*[:=]\\s*", "").trim());
                if(line.trim().startsWith("DURATIONS:")){
                    beats=new ArrayList<>();for(String token:line.trim().substring(10).split("[,，\\s]+")){
                        if(token.isEmpty())continue;String[] fraction=token.split("/");double b=Double.parseDouble(fraction[0]);if(fraction.length==2)b/=Double.parseDouble(fraction[1]);
                        if(!Double.isFinite(b)||b<.0625||b>32||fraction.length>2)throw new IllegalArgumentException();beats.add(b);
                    }
                }
            }
            if(bpm<20||bpm>400||beats!=null&&beats.size()!=columns.size())return new Result("","DURATIONS时值数量须与TAB发音列数一致，BPM范围20—400",0,0);
        }catch(Exception e){return new Result("","时值格式应为DURATIONS:1,0.5,0.5,2（四分音符为1拍）",0,0);}
        double accumulated=0;
        for(UkuleleTabParser.Column col:columns){
            if(col.invalidFrets>0)return new Result("","TAB有超出0—18品的数字，请校对",0,0);
            long ms=col.durationMs;
            if(beats!=null){accumulated+=60000.0/bpm*beats.get(index);ms=Math.round(accumulated)-duration;}index++;
            duration+=ms;
            if(col.midis.isEmpty()){pending+=ms;continue;}
            boolean first=true;int used=0;
            for(int i=0;i<col.midis.size();i++){
                int c=col.strings.get(i),f=col.frets.get(i);
                if(c<0||c>3||f<0||f>18||(used&(1<<c))!=0)return new Result("","TAB同列指法冲突或超出18品",0,0);
                append(s,first?pending:0,1<<c,f,210);first=false;pending=0;used|=1<<c;count++;
            }
            append(s,ms,0,0,0);

        }
        if(pending>0)append(s,pending,0,0,0);
        if(s.toString().split("\\n").length>10922)return new Result("","曲谱过长，请分段导入",0,0);
        return new Result(s.toString().trim(),count==0?"TAB没有可演奏音符":null,count,duration,beats==null);
    }
    private static void append(StringBuilder s,long delta,int mask,int fret,int level){
        while(delta>60000){s.append("60000,0,0,0,0\n");delta-=60000;}
        s.append(delta).append(',').append(mask).append(',').append(fret).append(',').append(level).append(",0\n");
    }
}
