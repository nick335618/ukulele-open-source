package com.ukulele.monitor;
/** Submitted LED target; v2 keeps four independent envelopes within the default MTU. */
final class LedGuideFrame {
 final int session,sequence,level;final int[] frets,levels;
 private LedGuideFrame(byte[] d){session=u(d[3])|(u(d[4])<<8);sequence=u(d[5])|(u(d[6])<<8);
   frets=new int[]{u(d[7]),u(d[8]),u(d[9]),u(d[10])};levels=new int[4];int peak=0;
   for(int c=0;c<4;c++){levels[c]=u(d[u(d[2])==1?11:11+c]);peak=Math.max(peak,levels[c]);}level=peak;
 }
 static LedGuideFrame parse(byte[] d){
   if(d==null||d.length<3||u(d[0])!=0x90||u(d[1])!=0x16)return null;
   int ver=u(d[2]);if(ver==1?(d.length!=14||d[12]!=0):ver==2?(d.length!=17||d[15]!=0):true)return null;
   int crc=0;for(byte b:d)crc^=u(b);if(crc!=0)return null;
   for(int i=7;i<=10;i++)if(u(d[i])>18&&u(d[i])!=31)return null;
   return new LedGuideFrame(d);
 }
 int mask(){int m=0;for(int c=0;c<4;c++)if(levels[c]>0&&frets[c]!=31)m|=1<<c;return m;}
 boolean follows(LedGuideFrame old){return old==null||(session==old.session?SongPlaybackState.newer(sequence,old.sequence):SongPlaybackState.newer(session,old.session));}
 private static int u(byte b){return b&255;}
}
