package com.ukulele.monitor;
import android.graphics.Bitmap;
/** Page corners from edge geometry; uncertain detections retain the entire image.
 *  Pure Java since the OpenCV native libraries (~140 MB) were removed: the detection core lives in
 *  {@link PurePaperDetector} and is exercised on a desktop JVM with synthetic images.
 *  The signature and the "null means uncertain, keep the uncorrected photo" contract are unchanged. */
final class OpenCvPaper {
 private OpenCvPaper(){}
 /** @return tl.x,tl.y,tr.x,tr.y,br.x,br.y,bl.x,bl.y in bitmap pixels, or null when unsure. */
 static float[] outline(Bitmap bitmap){
  if(bitmap==null)return null;
  try{
   if(bitmap.isRecycled())return null;
   int width=bitmap.getWidth(),height=bitmap.getHeight();
   if(width<16||height<16)return null;
   int[] pixels=new int[width*height];
   bitmap.getPixels(pixels,0,width,0,0,width,height);
   return PurePaperDetector.detect(pixels,width,height);
  }catch(RuntimeException failure){return null;}
  catch(OutOfMemoryError failure){return null;}
 }
}
