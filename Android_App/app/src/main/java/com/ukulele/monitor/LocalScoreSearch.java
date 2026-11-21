package com.ukulele.monitor;
import android.content.Context;
import com.ukulele.monitor.ai.AiScoreAssistant;
import java.util.*;
/** Search validated bundled scores before asking a model or crawling the web. */
public final class LocalScoreSearch {
 private LocalScoreSearch(){}
 public static List<AiScoreAssistant.ScoreDraft> find(Context c,String query,int limit)throws Exception{
  String q=(query==null?"":query).trim().replaceFirst("^(请|帮我|我想|我要|给我|搜索|找一下|查找|找谱|找|弹奏|弹|练习)+", "").replaceFirst("(的曲谱|的乐谱|曲谱|乐谱|简谱|四线谱|尤克里里谱)$", "").trim();
  ArrayList<AiScoreAssistant.ScoreDraft> result=new ArrayList<>();if(q.isEmpty())return result;
  for(SongLibrary.Song s:SongLibrary.search(SongLibrary.load(c),q)){
   if(result.size()>=Math.max(1,Math.min(8,limit)))break;
   int relevance=s.index.score(q);if(relevance<300)continue;
   byte[] raw=SongLibrary.events(c,s.asset);SongTimeline t=SongTimeline.parse(raw);
   if(t.noteCount!=s.noteCount||t.durationMs!=s.durationMs)continue;
   String csv=GuideEventText.format(raw,(relevance<600?"近似曲名匹配，请试听确认 · ":"本地曲库 · ")+s.title+" · "+s.subtitle+" · "+s.difficulty+"\n"+s.arrangement);

   result.add(new AiScoreAssistant.ScoreDraft(s.title,csv,csv,"离线曲库检索 · "+s.license,null,true,s.sourceUrl,"内置练习曲库","local-"+s.id));
  }
  return result;
 }
}
