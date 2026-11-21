package com.ukulele.monitor;

import java.util.ArrayList;

/** Pure note-on grading; every expected note can be judged once, including chord members. */
final class PracticeScorer {
    static final int RULE_VERSION=2;
    /** Grading tolerance is independent of visible note lengths and LED gate durations.
     * Adjacent attack midpoints still bound the accepted input window. */
    static final int TOKEN_MIN_MS=120, TOKEN_MAX_MS=300;
    static final double TOKEN_RATIO=1.0;
    /**
     * 设备反馈延迟补偿（毫秒），把判定窗口整体前移。
     *
     * 拨弦反馈是在手机**收到 BLE 通知**的那一刻被计时的（见 PlayedNoteTracker.accept(notes, now)），
     * 而通知到达时已经晚于实际拨弦 20~150ms。于是在拍点上弹也会被判成偏晚 ——
     * 这正是「明明弹对了却算错」的直接原因。
     *
     * 用户在「设置 → 评分宽容」里选择档位；评分读取这个值，
     * 它不再改变真实音长方块的边界或灯光时间。
     */
    private static volatile long inputLeadMs=80;
    static void setInputLead(long lead){inputLeadMs=Math.max(0,Math.min(300,lead));}
    static long inputLead(){return inputLeadMs;}
    /**
     * 档位 → 毫秒：准时 / 标准 / 宽松。
     *
     * 这里刻意只吃 int 而不引用 SettingsStore —— 本类要能在纯 JVM 上跑测试
     * （tools/verify_scoring），一旦依赖 ui 包就会把 Android 类拖进来导致编译失败。
     * 档位常量与 SettingsStore.LEAD_* 一一对应，由调用方负责映射。
     */
    static long leadForSetting(int setting){
        if(setting==0)return 0;      // LEAD_STRICT
        if(setting==2)return 160;    // LEAD_RELAXED
        return 80;                   // LEAD_NORMAL
    }
    static final int PENDING=0,CORRECT=1,WRONG=2,MISSED=3;
    static final class Cue { final int midi;int state;Cue(int pitch){midi=pitch;} }
    static final class Group {
        final long time;long open,close;final ArrayList<Cue> cues=new ArrayList<>();
        Group(long at){time=at;}
    }
    static final class Snapshot {
        final int total,correct,wrong,missed,extra;
        final boolean finished,aborted;
        final String reason;
        Snapshot(PracticeScorer s){total=s.total;correct=s.correct;wrong=s.wrong;missed=s.missed;extra=s.extra;finished=s.finished;aborted=s.aborted;reason=s.reason;}
        int judged(){return correct+wrong+missed;}
        double score(){return total==0?0:correct*100.0/total;}
        int hundredths(){if(total==0)return 0;int rounded=(int)Math.round(correct*10000.0/total);return correct<total?Math.min(9999,rounded):10000;}
        boolean recordable(){return finished&&!aborted&&total>0;}
    }
    static final class Judgement { int correct,wrong,extra; }
    private final ArrayList<Group> groups=new ArrayList<>();
    final long durationMs;
    private int total,correct,wrong,missed,extra,expire;
    private boolean finished,aborted;
    private String reason="";
    private long lastElapsed=-Long.MAX_VALUE;

    PracticeScorer(SongTimeline song,double speed){
        if(song==null||!Double.isFinite(speed)||speed<.25||speed>2)throw new IllegalArgumentException("评分速度无效");
        for(SongTimeline.Step step:song.steps){
            long at=Math.round(step.timeMs/speed);Group g=!groups.isEmpty()&&groups.get(groups.size()-1).time==at?groups.get(groups.size()-1):new Group(at);
            for(SongTimeline.Event event:step.events)if(event.attack())for(int c=0;c<4;c++)if((event.mask&(1<<c))!=0){g.cues.add(new Cue(ScorePitch.OPEN[c]+event.fret));total++;}
            if(!g.cues.isEmpty()&&(groups.isEmpty()||groups.get(groups.size()-1)!=g))groups.add(g);
        }
        if(total==0)throw new IllegalArgumentException("曲谱没有可评分音符");
        for(int i=0;i<groups.size();i++){
            Group g=groups.get(i);
            long toNext=i+1<groups.size()?groups.get(i+1).time-g.time:TOKEN_MAX_MS;
            long token=Math.max(TOKEN_MIN_MS,Math.min(TOKEN_MAX_MS,Math.round(TOKEN_MIN_MS+toNext*TOKEN_RATIO)));
            g.open=Math.max(0,g.time-token);g.close=g.time+token;
            if(i>0)g.open=Math.max(g.open,(groups.get(i-1).time+g.time)/2+1);
            if(i+1<groups.size())g.close=Math.min(g.close,(g.time+groups.get(i+1).time)/2);
        }
        durationMs=Math.max(Math.round(song.durationMs/speed),groups.get(groups.size()-1).close+1);
    }
    Snapshot snapshot(){return new Snapshot(this);}
    void abort(String why){if(finished||aborted)return;aborted=true;reason=why==null?"本轮中止":why;}
    void advance(long elapsed){
        if(finished||aborted||elapsed<0)return;
        if(elapsed<lastElapsed){abort("练习时钟回退");return;}lastElapsed=elapsed;
        while(expire<groups.size()&&elapsed>groups.get(expire).close){for(Cue cue:groups.get(expire++).cues)if(cue.state==PENDING){cue.state=MISSED;missed++;}}
        if(elapsed>=durationMs){for(Group g:groups)for(Cue cue:g.cues)if(cue.state==PENDING){cue.state=MISSED;missed++;}finished=true;}
    }
    Judgement accept(long elapsed,int[] played){
        // 用「补偿后的时刻」判定：把设备反馈的传输延迟扣掉，窗口因此整体前移，
        // 让「略晚」不再等于「判错」。
        return accept(elapsed,played,inputLeadMs);
    }
    Judgement accept(long elapsed,int[] played,long leadMs){
        advance(elapsed);Judgement j=new Judgement();if(elapsed<0||finished||aborted||played==null)return j;
        long judged=elapsed-leadMs;
        int low=0,high=groups.size()-1;Group g=null;
        while(low<=high){int m=(low+high)>>>1;Group v=groups.get(m);if(judged<v.open)high=m-1;else if(judged>v.close)low=m+1;else{g=v;break;}}
        boolean[] used=new boolean[played.length];
        // Match every correct chord member before assigning wrong notes; channel order must not steal a match.
        if(g!=null)for(int i=0;i<played.length;i++)for(Cue cue:g.cues)if(cue.state==PENDING&&cue.midi==played[i]){cue.state=CORRECT;correct++;j.correct++;used[i]=true;break;}
        for(int i=0;i<played.length;i++)if(!used[i]){
            Cue pending=null;if(g!=null)for(Cue cue:g.cues)if(cue.state==PENDING){pending=cue;break;}
            if(pending!=null){pending.state=WRONG;wrong++;j.wrong++;}else{extra++;j.extra++;}
        }
        return j;
    }
}
