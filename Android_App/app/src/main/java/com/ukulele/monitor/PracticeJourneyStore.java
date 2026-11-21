package com.ukulele.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.util.*;

/** Growth rewards are separate from formal accuracy scores and only follow completed real practice. */
final class PracticeJourneyStore {
    static final class Snapshot {
        final int xp,dailyXp,runs,correct,best,earBest,rhythmBest;final boolean[] days;
        Snapshot(int xp,int daily,int runs,int correct,int best,int ear,int rhythm,boolean[] days){this.xp=xp;dailyXp=daily;this.runs=runs;this.correct=correct;this.best=best;earBest=ear;rhythmBest=rhythm;this.days=days;}
        int level(){return 1+xp/200;}
        int quests(){return (runs>0?1:0)+(correct>=30?1:0)+(best>=8000?1:0);}
    }
    static final class Reward {final int xp;final boolean saved,eligible;Reward(int x,boolean s,boolean e){xp=x;saved=s;eligible=e;}}
    private final SharedPreferences prefs;
    PracticeJourneyStore(Context c){prefs=c.getSharedPreferences("uk_practice_journey_v1",0);}
    PracticeJourneyStore(SharedPreferences p){prefs=p;}
    synchronized Snapshot snapshot(){return snapshot(LocalDate.now());}
    synchronized Snapshot snapshot(LocalDate date){
        boolean same=date.toString().equals(prefs.getString("day",""));boolean[] days=new boolean[7];
        for(int i=0;i<7;i++)days[i]=prefs.getInt("runs_"+date.minusDays(6-i),0)>0;
        return new Snapshot(Math.max(0,prefs.getInt("xp",0)),same?prefs.getInt("daily_xp",0):0,same?prefs.getInt("runs",0):0,same?prefs.getInt("correct",0):0,same?prefs.getInt("best",0):0,prefs.getInt("ear_best",0),prefs.getInt("rhythm_best",0),days);
    }
    synchronized Reward record(String attempt,int total,int correct,long duration,boolean complete,boolean newBest){return record(attempt,total,correct,duration,complete,newBest,LocalDate.now());}
    synchronized Reward record(String attempt,int total,int correct,long duration,boolean complete,boolean newBest,LocalDate date){
        if(!complete||total<8||correct<0||correct>total||duration<10000||attempt==null||!attempt.matches("[a-zA-Z0-9_-]{1,128}"))return new Reward(0,true,false);
        String recent=prefs.getString("recent","");if(Arrays.asList(recent.split(",")).contains(attempt))return new Reward(0,true,true);
        Snapshot old=snapshot(date);int earned=correct==0?0:(int)Math.min(80L,correct*2L)+(correct*100L>=total*60L?10:0)+(newBest?10:0);
        earned=Math.min(earned,Math.max(0,500-old.dailyXp));
        ArrayList<String> ids=new ArrayList<>(Arrays.asList(recent.split(",")));ids.remove("");ids.add(attempt);while(ids.size()>32)ids.remove(0);
        SharedPreferences.Editor e=prefs.edit().putString("day",date.toString()).putString("recent",String.join(",",ids))
                .putInt("xp",(int)Math.min(1000000000L,old.xp+(long)earned)).putInt("daily_xp",old.dailyXp+earned)
                .putInt("runs",Math.min(10000,old.runs+1)).putInt("correct",(int)Math.min(1000000L,old.correct+(long)correct))
                .putInt("best",Math.max(old.best,(int)Math.round(correct*10000.0/total)))
                .putInt("runs_"+date,prefs.getInt("runs_"+date,0)+1);
        for(String key:prefs.getAll().keySet())if(key.startsWith("runs_"))try{if(LocalDate.parse(key.substring(5)).isBefore(date.minusDays(6)))e.remove(key);}catch(Exception ignored){}
        boolean saved=e.commit();return new Reward(saved?earned:0,saved,true);
    }
    synchronized void gameBest(boolean rhythm,int score){String key=rhythm?"rhythm_best":"ear_best";prefs.edit().putInt(key,Math.max(prefs.getInt(key,0),Math.max(0,Math.min(100,score)))).apply();}
    static String stars(int hundredths){return hundredths>=9500?"★★★":hundredths>=8000?"★★☆":hundredths>=6000?"★☆☆":"☆☆☆";}
}
