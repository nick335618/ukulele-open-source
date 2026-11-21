package com.ukulele.monitor;

import java.util.*;

/** Phone games only; no hardware feedback, formal score or BLE command is fabricated. */
final class PracticeGameLogic {
    static final int[] INTERVALS={0,4,7,12};
    static final String[] LABELS={"同一个音","大三度 · 4半音","纯五度 · 7半音","高八度 · 12半音"};
    static final class Ear {final int base,answer;Ear(Random r){base=60+r.nextInt(8);answer=r.nextInt(INTERVALS.length);}int target(){return base+INTERVALS[answer];}}
    static long[] rhythm(Random r,int round){
        int count=round<2?4:5+r.nextInt(2);long half=round<2?375:300;long[] onsets=new long[count];
        for(int i=1;i<count;i++)onsets[i]=onsets[i-1]+half*(round<2?2:1+r.nextInt(2));
        return onsets;
    }
    static int rhythmScore(long[] expected,List<Long> taps){
        if(expected==null||expected.length<2||taps==null||taps.size()<2)return 0;
        double sum=0;int intervals=expected.length-1;
        for(int i=1;i<Math.min(taps.size(),expected.length);i++){
            long dt=taps.get(i)-taps.get(i-1),want=expected[i]-expected[i-1];
            if(dt<=0||want<=0)continue;
            double error=Math.abs(dt-want),tolerance=Math.max(100,want*.28);
            sum+=Math.max(0,1-Math.max(0,error-35)/tolerance);
        }
        return (int)Math.round(100*sum/intervals);
    }
}
