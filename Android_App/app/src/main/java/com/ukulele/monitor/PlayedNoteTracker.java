package com.ukulele.monitor;

import java.util.ArrayList;

/** Counts refer to notes actually submitted to the FPGA audio engine. Finger changes never score. */
final class PlayedNoteTracker {
    static final long FRESH_MS = 700;
    static final class Batch {
        final int[] midi;
        final String fault;
        final boolean fresh;
        Batch(int[] notes, String error, boolean uniqueFrame) { midi=notes;fault=error;fresh=uniqueFrame; }
    }
    private LiveNoteState previous;
    private long receivedAt = -1;

    boolean ready(long now) { return previous!=null && previous.online && receivedAt>=0 && now>=receivedAt && now-receivedAt<=FRESH_MS; }
    void reset() { previous=null;receivedAt=-1; }

    Batch accept(LiveNoteState next,long now) {
        if(next==null||!next.online){reset();return new Batch(new int[0],"FPGA音符反馈离线",false);}
        if(previous==null){previous=next;receivedAt=now;return new Batch(new int[0],null,true);}
        if(next.sequence==previous.sequence && next.millis16==previous.millis16){
            boolean same=next.automaticMask==previous.automaticMask&&java.util.Arrays.equals(next.counts,previous.counts)&&java.util.Arrays.equals(next.midi,previous.midi)&&java.util.Arrays.equals(next.frets,previous.frets);
            return new Batch(new int[0],same?null:"重复反馈序号的内容不一致",false);
        }
        String fault=null;
        if(((next.millis16-previous.millis16)&65535)>32768)fault="设备反馈时钟重新开始";
        ArrayList<Integer> notes=new ArrayList<>();
        for(int i=0;i<4;i++){
            int delta=(next.counts[i]-previous.counts[i])&255;
            if((next.automaticMask&(1<<i))!=0)continue;
            if(delta>1)fault="拨弦反馈计数跳跃，无法还原中间音符";
            else if(delta==1){if(next.midi[i]>127)fault="拨弦音高反馈无效";else notes.add(next.midi[i]);}
        }
        previous=next;receivedAt=fault==null?now:-1;
        int[] result=new int[fault==null?notes.size():0];for(int i=0;i<result.length;i++)result[i]=notes.get(i);
        return new Batch(result,fault,true);
    }
}
