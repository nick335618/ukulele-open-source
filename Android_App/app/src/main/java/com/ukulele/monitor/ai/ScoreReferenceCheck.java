package com.ukulele.monitor.ai;

import java.util.*;

/** Reference clues are not proof of the same arrangement; adoption always needs confirmation. */
public final class ScoreReferenceCheck {
    private ScoreReferenceCheck(){}
    private static String body(String s){try{List<String> p=ScorePageComposer.split(s);return p.isEmpty()?s:String.join("\n",p);}catch(Exception e){return s;}}
    public static boolean hasWrittenNotation(String text){
        String s=body(text==null?"":text);
        boolean numericHeader=s.matches("(?s).*(?:1\\s*[=＝]\\s*[A-Ga-g]|BPM\\s*[:=]).*");
        boolean abcHeaders=s.matches("(?s).*?(?m)^\\s*X:\\s*\\d+.*")&&s.matches("(?s).*?(?m)^\\s*K:\\s*[^\\n]+.*");
        return (numericHeader&&JianpuParser.parse(s).ok())||(abcHeaders&&AbcParser.parse(s).ok())||(UkuleleTabParser.looksLikeTab(s)&&UkuleleTabParser.parse(s).ok());
    }
    private static int tonic(String key){int i="CDEFGAB".indexOf(Character.toUpperCase(key.charAt(0)));int n=i<0?0:new int[]{0,2,4,5,7,9,11}[i];if(key.endsWith("#"))n++;if(key.endsWith("b"))n--;return(n+12)%12;}
    public static String compare(String original,String reference){
        JianpuParser.ParseResult a=JianpuParser.parse(body(original)),b=JianpuParser.parse(body(reference));
        if(!a.ok()||!b.ok())return "格式不同或原稿尚不完整，请试听并对照原谱核对，不能自动认定同一版本。";
        int n=Math.min(256,a.notes.size()),m=Math.min(256,b.notes.size());int[] previous=new int[m+1];
        for(int i=0;i<n;i++){int[] next=new int[m+1];int x=a.notes.get(i).isRest()?-1:(a.notes.get(i).midi-tonic(a.key)+120)%12;
            for(int j=0;j<m;j++){int y=b.notes.get(j).isRest()?-1:(b.notes.get(j).midi-tonic(b.key)+120)%12;next[j+1]=x==y?previous[j]+1:Math.max(previous[j+1],next[j]);}previous=next;
        }
        return "原稿：1="+a.key+" · "+a.meter+"；网页：1="+b.key+" · "+b.meter+"\n级数顺序线索覆盖 "+previous[m]+"/"+n+"（最多比较前256音，忽略八度与时值，不是准确率）。\n请核对是否同一首、同一段与同一编配；调号不同时不会自动改调。";
    }
}
