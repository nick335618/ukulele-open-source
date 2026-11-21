package com.ukulele.monitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Atomic, local best scores. Exact score contents/rate/rules form the identity, never the title. */
final class PracticeScoreRecords {
    static final class Record {
        int correct,total,attempts;long completedAt;
        int hundredths(){if(total==0)return 0;int rounded=(int)Math.round(correct*10000.0/total);return correct<total?Math.min(9999,rounded):10000;}
    }
    private final File root;
    PracticeScoreRecords(File directory){root=directory;root.mkdirs();}
    static String key(byte[] score,double rate)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(score);
        digest.update(("\npractice-v"+PracticeScorer.RULE_VERSION+"\n"+Double.toString(rate)).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private File file(String key){if(key==null||!key.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("成绩编号无效");return new File(root,key+".json");}
    synchronized Record read(String key)throws Exception {
        File f=file(key);Record r=new Record();if(!f.exists()&&!new File(f.getPath()+".bak").exists())return r;
        JSONObject j=new JSONObject(new String(ScoreRepository.readAtomic(f),StandardCharsets.UTF_8));
        if(j.getInt("version")!=1||!key.equals(j.getString("key")))throw new IllegalArgumentException("成绩记录格式错误");
        r.correct=j.getInt("correct");r.total=j.getInt("total");r.attempts=j.getInt("attempts");r.completedAt=j.getLong("completedAt");
        if(r.correct<0||r.total<=0||r.correct>r.total||r.attempts<=0)throw new IllegalArgumentException("成绩记录内容错误");return r;
    }
    synchronized Record save(String key,PracticeScorer.Snapshot score,long now)throws Exception {
        if(score==null||!score.recordable())throw new IllegalArgumentException("中止或未完成的练习不能保存成绩");
        Record r=read(key);if(r.total!=0&&r.total!=score.total)throw new IllegalArgumentException("曲谱计分规则不一致");
        if(r.total==0||score.correct>r.correct){r.correct=score.correct;r.total=score.total;r.completedAt=now;}
        r.attempts=(int)Math.min(Integer.MAX_VALUE,r.attempts+1L);
        JSONObject j=new JSONObject().put("version",1).put("key",key).put("correct",r.correct).put("total",r.total).put("attempts",r.attempts).put("completedAt",r.completedAt);
        ScoreRepository.atomic(file(key),j.toString().getBytes(StandardCharsets.UTF_8));return r;
    }
}
