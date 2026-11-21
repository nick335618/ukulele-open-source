package com.ukulele.monitor;
import android.graphics.*;import android.view.*;import com.ukulele.monitor.ui.*;
import org.junit.*;import static org.junit.Assert.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.util.ReflectionHelpers;
import java.io.*;import java.nio.file.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScopeRenderTest {
 @Test public void completeFramesDisplayImmediatelyAndFastFramesKeepLastSpectrum(){
  AudioScopeView v=new AudioScopeView(RuntimeEnvironment.getApplication());AudioAnalysisFrame f=new AudioAnalysisFrame();f.receivedAtMs=100;f.bins[40]=180;f.pcm[1]=.375f;v.update(f);
  assertEquals(180,((int[])ReflectionHelpers.getField(v,"bins"))[40]);assertEquals(.375f,((float[])ReflectionHelpers.getField(v,"pcm"))[1],0);
  f.scopeOnly=true;f.receivedAtMs=120;f.bins[40]=0;f.pcm[1]=-.25f;v.update(f);assertEquals(180,((int[])ReflectionHelpers.getField(v,"bins"))[40]);assertEquals(-.25f,((float[])ReflectionHelpers.getField(v,"pcm"))[1],0);f.pcm[1]=1;assertEquals(-.25f,((float[])ReflectionHelpers.getField(v,"pcm"))[1],0);
 }
 @Test public void allPlotsClipExtremeValuesAndRenderBothThemesAtThreeSizes()throws Exception{
  for(boolean dark:new boolean[]{false,true})for(int w:new int[]{320,393,800})for(int mode=0;mode<3;mode++){
   if(dark)AppTheme.dark();else AppTheme.light();AudioScopeView v=new AudioScopeView(RuntimeEnvironment.getApplication());v.setMode(mode);AudioAnalysisFrame f=new AudioAnalysisFrame();f.peakBin=37;f.receivedAtMs=100;
   for(int i=0;i<2048;i++)f.bins[i]=(int)(190*Math.exp(-Math.pow((i-37)/2.,2)));
   for(int i=0;i<256;i++){f.pcm[i]=(float)(1.1*Math.sin(i*.19));f.xy[i]=(float)Math.sin(i*.13);}
   v.update(f);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(330,View.MeasureSpec.EXACTLY));v.layout(0,0,w,330);
   Bitmap b=Bitmap.createBitmap(w,330,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));assertEquals(0,Color.alpha(b.getPixel(0,0)));assertNotEquals(0,Color.alpha(b.getPixel(w/2,165)));
   java.nio.file.Path p=Paths.get("../Screenshots/analysis-"+mode+"-"+w+"-"+dark+".png");Files.createDirectories(p.getParent());try(OutputStream o=Files.newOutputStream(p)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,o));}
  }
 }
}
