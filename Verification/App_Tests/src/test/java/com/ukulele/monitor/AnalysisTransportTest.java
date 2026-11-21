package com.ukulele.monitor;
import org.junit.*;import static org.junit.Assert.*;import java.nio.file.*;import java.util.*;
public class AnalysisTransportTest {
 byte[] input(int c)throws Exception{return Files.readAllBytes(Paths.get("../Inputs/analysis-"+c+".bin"));}
 int word(int a,int b){return (short)((a&255)|((b&255)<<8));}
 @Test public void allGoldenStreamsRestoreEveryBinAnd16BitSample()throws Exception{
  AudioAnalysisFrame p=new AudioAnalysisFrame();for(int c=0;c<5;c++){byte[] all=input(c);boolean done=false;for(int i=0;i<all.length;i+=20){done=p.accept(Arrays.copyOfRange(all,i,i+20));assertEquals(i==all.length-20,done);}
   assertTrue(done);assertEquals(c==4,p.scopeOnly);if(c!=4)for(int i=0;i<2048;i++)assertEquals(c==0?0:c==1?(i%100==0?100:0):c==2?i%193:(i*19+i/3)%193,p.bins[i]);
   for(int i=0;i<256;i++){assertEquals(word((i*2*37+c)%256,((i*2+1)*37+c)%256)/32768f,p.pcm[i],0);assertEquals(word((255-i*2*11+c)&255,(255-(i*2+1)*11+c)&255)/32768f,p.xy[i],0);}
  }
 }
 @Test public void compressedCorruptionMissingReorderedAndPaddingNeverPublish()throws Exception{
  byte[] all=input(1);for(int at=20;at<all.length;at++){byte[] broken=all.clone();broken[at]^=1;AudioAnalysisFrame p=new AudioAnalysisFrame();boolean done=false;for(int i=0;i<broken.length;i+=20)done|=p.accept(Arrays.copyOfRange(broken,i,i+20));assertFalse("corrupt "+at,done);}
  for(int skip=20;skip<all.length-20;skip+=20){AudioAnalysisFrame p=new AudioAnalysisFrame();boolean done=false;for(int i=0;i<all.length;i+=20)if(i!=skip)done|=p.accept(Arrays.copyOfRange(all,i,i+20));assertFalse(done);}
 }
 @Test public void viewSwitchHeaderResynchronizesWithoutOldPartialLeak()throws Exception{
  AudioAnalysisFrame p=new AudioAnalysisFrame();byte[] a=input(2),b=input(4);for(int i=0;i<a.length/2/20*20;i+=20)assertFalse(p.accept(Arrays.copyOfRange(a,i,i+20)));boolean done=false;for(int i=0;i<b.length;i+=20)done=p.accept(Arrays.copyOfRange(b,i,i+20));assertTrue(done);assertTrue(p.scopeOnly);assertEquals(5,p.sequence());
 }
}
