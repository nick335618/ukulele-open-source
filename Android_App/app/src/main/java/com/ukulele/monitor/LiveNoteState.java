package com.ukulele.monitor;

/** Real FPGA finger positions and last audio-submission MIDI per ADC channel. */
final class LiveNoteState {
    final boolean online;
    final int eventMask,automaticMask;
    final int sequence, millis16;
    final int[] frets=new int[4],midi=new int[4],counts=new int[4];
    private static final int[] OPEN={67,64,60,69};
    private static final int[] ORDER={0,2,1,3};
    private LiveNoteState(byte[] d){
        online=d[2]==1;eventMask=d[16]&15;automaticMask=d[1]==2?(d[16]&255)>>>4:0;
        sequence=d[17]&255;millis16=(d[18]&255)|((d[19]&255)<<8);
        for(int i=0;i<4;i++){frets[i]=d[4+i]&255;midi[i]=d[8+i]&255;counts[i]=d[12+i]&255;}
    }
    static LiveNoteState parse(byte[] d){
        if(d==null || d.length!=20 || (d[0]&255)!=0xA1 || (d[1]!=1&&d[1]!=2) || (d[2]&255)>1 || (d[3]&255)>3 || (d[1]==1&&(d[16]&255)>15))return null;
        for(int i=0;i<4;i++){
            int fret=d[4+i]&255,note=d[8+i]&255;
            if(fret>18 && !(d[2]==0 && fret==255))return null;
            if(note>127 && note!=255)return null;
        }
        return new LiveNoteState(d);
    }
    int newEventMask(LiveNoteState old){
        if(!online)return 0;
        int mask=eventMask;
        if(old!=null)for(int i=0;i<4;i++)if(midi[i]!=255 && counts[i]!=old.counts[i])mask|=1<<i;
        return mask;
    }
    /** Counter changes prove a new audio submission; cached flags do not. */
    int newAudioEventMask(LiveNoteState old){
        if(!online||old==null||!old.online)return 0;
        int mask=0;
        for(int i=0;i<4;i++)if(midi[i]!=255&&counts[i]!=old.counts[i])mask|=1<<i;
        return mask;
    }
    String newPlayedNotes(LiveNoteState old){
        if(!online)return "";
        StringBuilder text=new StringBuilder();
        for(int i:ORDER)if(midi[i]!=255 && (old==null || counts[i]!=old.counts[i]))append(text,name(midi[i]));
        return text.toString();
    }
    String changedFingers(LiveNoteState old){return changedFingers(old,0);}
    String changedFingers(LiveNoteState old,int capo){
        if(!online)return "";
        StringBuilder text=new StringBuilder();
        for(int i:ORDER)if(frets[i]<=18 && old!=null && frets[i]!=old.frets[i])append(text,name(OPEN[i]+Math.max(frets[i],CapoMapping.clamp(capo))));
        return text.toString();
    }
    private static void append(StringBuilder b,String s){if(b.length()>0)b.append(" / ");b.append(s);}
    static String name(int midi){String[] n={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};return n[midi%12]+(midi/12-1);}
}
