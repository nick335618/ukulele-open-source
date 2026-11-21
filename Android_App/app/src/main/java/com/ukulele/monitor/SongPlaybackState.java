package com.ukulele.monitor;

/** Actual ESP32 song clock; separate from controller/LED animation settings. */
public final class SongPlaybackState {
    public final int op,result,token,session,state,rate,emitted,revision;
    public final long positionMs;
    public final boolean instrument;
    private SongPlaybackState(byte[] p){instrument=p[1]==2&&p[9]==1;op=u(p[2]);result=u(p[3]);token=w(p,4);session=w(p,6);state=u(p[8]);rate=w(p,10);emitted=w(p,16);revision=w(p,18);positionMs=u(p[12])|(u(p[13])<<8)|(u(p[14])<<16)|((long)u(p[15])<<24);}
    private static int u(byte b){return b&255;}
    private static int w(byte[]p,int i){return u(p[i])|(u(p[i+1])<<8);}
    public static boolean validRate(int value){return value>=250&&value<=2000&&value%250==0;}
    public static SongPlaybackState parse(byte[]p){
        if(p==null||p.length!=20||u(p[0])!=0x91||(p[1]!=1&&p[1]!=2)||(p[1]==1&&p[9]!=0)||(p[1]==2&&(p[9]<0||p[9]>1)))return null;
        SongPlaybackState s=new SongPlaybackState(p);
        if((s.op>4&&s.op!=255)||s.result>3||s.state>2||!validRate(s.rate)||s.emitted>10921)return null;
        if(s.state!=0&&s.session==0)return null;
        if(s.op==255&&(s.token!=0||s.result!=0))return null;
        return s;
    }
    public static String command(int op,int rate,int token){
        if(op<0||op>4||!validRate(rate)||token<0||token>65535||(token==0&&op!=4))throw new IllegalArgumentException("曲谱控制参数错误");
        return "SP1:"+op+","+rate+","+token;
    }
    public long predictedPosition(long receivedAt,long now){return positionMs+(state==1?Math.max(0,now-receivedAt)*rate/1000:0);}
    public static boolean newer(int revision,int previous){int delta=(revision-previous)&65535;return delta>0&&delta<32768;}
}
