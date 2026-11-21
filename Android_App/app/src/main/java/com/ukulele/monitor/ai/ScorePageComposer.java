package com.ukulele.monitor.ai;

import java.util.*;
import java.util.regex.*;

/** Explicit page boundaries prevent a missing page from silently becoming a complete score. */
public final class ScorePageComposer {
    private static final Pattern PAGE=Pattern.compile("(?m)^%%PAGE:(\\d+)\\s*$");
    private ScorePageComposer(){}
    public static List<String> split(String text){
        ArrayList<String> out=new ArrayList<>();Matcher m=PAGE.matcher(text==null?"":text);
        int start=-1, expected=1;
        while(m.find()){
            int page=Integer.parseInt(m.group(1));
            if(page!=expected++)throw new IllegalArgumentException("分页标记必须从 %%PAGE:1 连续编号");
            if(start<0&&!text.substring(0,m.start()).trim().isEmpty())throw new IllegalArgumentException("请保留开头的分页标记");
            if(start>=0)out.add(text.substring(start,m.start()).trim());
            start=m.end();
        }
        if(start>=0){out.add(text.substring(start).trim());if(out.size()>36)throw new IllegalArgumentException("识谱最多36段，请分批导入");}
        return out;
    }
    public static AiScoreAssistant.ScoreDraft join(String title,List<AiScoreAssistant.ScoreDraft> drafts){
        if(drafts==null||drafts.isEmpty())throw new IllegalArgumentException("没有分页结果");
        StringBuilder text=new StringBuilder(),events=new StringBuilder();boolean all=true;int missing=0,omitted=0;
        for(int i=0;i<drafts.size();i++){
            AiScoreAssistant.ScoreDraft d=drafts.get(i);
            if(d!=null)omitted+=d.omittedNotes;
            text.append("%%PAGE:").append(i+1).append('\n').append(d==null?"?":d.transcription).append("\n\n");
            if(d==null||d.eventsText.trim().isEmpty()){all=false;missing++;}
            else events.append(d.eventsText.trim()).append('\n');
        }
        LinkedHashSet<String> methods=new LinkedHashSet<>();
        for(AiScoreAssistant.ScoreDraft d:drafts)if(d!=null&&!d.source.isEmpty())methods.add(d.source);
        return new AiScoreAssistant.ScoreDraft(title,text.toString().trim(),all?events.toString().trim():"",
                "分段转录 · "+drafts.size()+"段"+(missing>0?" · "+missing+"段待校对":" · 请核对")+"\n"+String.join("\n",methods),null,false,"","","",omitted);
    }
}
