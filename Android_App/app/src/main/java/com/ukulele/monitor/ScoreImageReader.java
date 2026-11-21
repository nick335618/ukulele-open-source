package com.ukulele.monitor;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import com.ukulele.monitor.ai.ScoreImageDocument;
import com.ukulele.monitor.ai.AiScoreAssistant;
import java.io.*;
import java.util.*;

/** Bounded rasterization; originals are retained separately from AI's JPEG pages. */
final class ScoreImageReader {
    static final int MAX_SIDE=2200,MAX_PAGES=6;
    static boolean isPdf(byte[] b){return b!=null&&b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-';}
    static int pdfPages(Context context,byte[] bytes)throws Exception{
        File f=temp(context,bytes);
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)) {return pdf.getPageCount();}
        finally{f.delete();}
    }
    static ScoreImageDocument read(Context context,byte[] bytes,String mime,String name,int first,int last)throws Exception{
        if(bytes==null||bytes.length==0||bytes.length>32*1024*1024)throw new IOException("文件为空或超过32MB");
        ArrayList<byte[]> images=new ArrayList<>();int total=1;
        ArrayList<AiScoreAssistant.ScoreDraft> local=new ArrayList<>();
        ArrayList<String> hints=new ArrayList<>();ArrayList<byte[]> sections=new ArrayList<>();ArrayList<Boolean> numeric=new ArrayList<>();ArrayList<AiScoreAssistant.ScoreDraft> sectionLocal=new ArrayList<>();
        ArrayList<OfflineScoreRecognizer.Glyph> glyphs=new ArrayList<>();
        try{OfflineScoreRecognizer.loadTemplates(context.getAssets().open("score_digit_templates.txt"),glyphs);}catch(IOException ignored){}
        if(isPdf(bytes)){
            File f=temp(context,bytes);
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){
                total=pdf.getPageCount();
                if(first<1||last<first||last>total||last-first+1>MAX_PAGES)throw new IOException("每批请选择连续1至6页，不能超出PDF页数");
                for(int i=first-1;i<last;i++)try(PdfRenderer.Page page=pdf.openPage(i)){
                    double k=Math.min(3.0,MAX_SIDE/(double)Math.max(page.getWidth(),page.getHeight()));
                    Bitmap b=Bitmap.createBitmap(Math.max(1,(int)Math.round(page.getWidth()*k)),Math.max(1,(int)Math.round(page.getHeight()*k)),Bitmap.Config.ARGB_8888);
                    try{new Canvas(b).drawColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);byte[] whole=jpeg(b);images.add(whole);local.add(plan(context,b,glyphs,whole,sections,numeric,sectionLocal,hints));}finally{b.recycle();}
                }
            }finally{f.delete();}
            mime="application/pdf";
        }else{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("无法读取图片");
            int scale=1;while(bounds.outWidth/scale>MAX_SIDE*2||bounds.outHeight/scale>MAX_SIDE*2)scale*=2;
            BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=scale;
            Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
            if(b==null)throw new IOException("图片解码失败");
            try{
                Matrix m=new Matrix();
                try{
                    ExifInterface exif=new ExifInterface(new ByteArrayInputStream(bytes));
                    int o=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                    switch(o){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
                }catch(IOException ignored){}
                if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b){b.recycle();b=rotated;}}
                float k=Math.min(1,MAX_SIDE/(float)Math.max(b.getWidth(),b.getHeight()));
                if(k<1){Bitmap small=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*k)),Math.max(1,Math.round(b.getHeight()*k)),true);if(small!=b){b.recycle();b=small;}}
                byte[] whole=jpeg(b);images.add(whole);
                local.add(plan(context,b,glyphs,whole,sections,numeric,sectionLocal,hints));
            }finally{b.recycle();}
            first=last=1;
            if(mime==null||!mime.startsWith("image/"))mime="image/jpeg";
        }
        // A mixed TAB/staff/numbered PDF keeps page boundaries for the existing page composer.
        if(numeric.contains(true)&&numeric.contains(false)){sections.clear();sections.addAll(images);numeric.clear();sectionLocal.clear();hints.clear();for(int n=0;n<images.size();n++){numeric.add(false);sectionLocal.add(local.get(n));hints.add("");}}
        return new ScoreImageDocument(name,mime,bytes,images,first,total,local,sections,numeric,sectionLocal,hints);
    }
    private static AiScoreAssistant.ScoreDraft plan(Context context,Bitmap b,List<OfflineScoreRecognizer.Glyph> glyphs,byte[] whole,List<byte[]> sections,List<Boolean> numeric,List<AiScoreAssistant.ScoreDraft> local,List<String> hints)throws IOException{
        AiScoreAssistant.ScoreDraft candidate=preflight(b,glyphs);
        if(candidate==null)try{
            List<OfflineScoreRecognizer.Glyph> templates=new JianpuRecognizer.Templates(JianpuDigitModel.read(context.getAssets().open("jianpu_digit_model.txt")));
            templates.addAll(glyphs);JianpuRecognizer.loadTemplates(context.getAssets().open("jianpu_templates.txt"),templates);
            int w=b.getWidth(),h=b.getHeight();int[] pixels=new int[w*h];b.getPixels(pixels,0,w,0,0,w,h);
            NumberedPhotoLayout.Plan layout=NumberedPhotoLayout.detect(pixels,w,h,templates);List<int[]> regions=layout==null?java.util.Collections.emptyList():layout.regions;
            // Reserve one section for every remaining original PDF page.
            if(!regions.isEmpty()&&sections.size()+regions.size()<=36-MAX_PAGES){
                ArrayList<byte[]> complete=new ArrayList<>();
                for(int[] region:regions){int head=region[0],top=region[1],bottom=region[2],x0=region.length>3?region[3]:0,x1=region.length>4?region[4]:w;int musicHeight=Math.round((bottom-top)*w/(float)(x1-x0));Bitmap part=Bitmap.createBitmap(w,head+16+musicHeight,Bitmap.Config.ARGB_8888);
                    try{Canvas canvas=new Canvas(part);canvas.drawColor(Color.WHITE);if(head>0)canvas.drawBitmap(b,new Rect(0,0,w,head),new Rect(0,0,w,head),null);canvas.drawBitmap(b,new Rect(x0,top,x1,bottom),new Rect(0,head+16,w,part.getHeight()),null);complete.add(jpeg(part));}finally{part.recycle();}}
                for(int n=0;n<complete.size();n++){sections.add(complete.get(n));numeric.add(true);local.add(null);hints.add(layout.hints.get(n));}
                return null;
            }
        }catch(RuntimeException|OutOfMemoryError ignored){}
        sections.add(whole);numeric.add(false);local.add(candidate);hints.add("");return candidate;
    }
    private static AiScoreAssistant.ScoreDraft preflight(Bitmap b,List<OfflineScoreRecognizer.Glyph> glyphs){
        if(glyphs.isEmpty())return null;
        Bitmap small=b;
        try{
            double k=Math.min(1,1800.0/Math.max(b.getWidth(),b.getHeight()));
            if(k<1)small=Bitmap.createScaledBitmap(b,Math.max(1,(int)Math.round(b.getWidth()*k)),Math.max(1,(int)Math.round(b.getHeight()*k)),true);
            int w=small.getWidth(),h=small.getHeight();int[] pixels=new int[w*h];small.getPixels(pixels,0,w,0,0,w,h);
            return LocalTabPreflight.inspect(pixels,w,h,120,glyphs);
        }catch(OutOfMemoryError memory){return null;}
        finally{if(small!=b)small.recycle();}
    }
    private static File temp(Context c,byte[] bytes)throws Exception{
        File file=File.createTempFile("ai_score_",".pdf",c.getCacheDir());
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}catch(Exception e){file.delete();throw e;}return file;
    }
    private static byte[] jpeg(Bitmap b)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();if(!b.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("无法转换谱面");
        if(out.size()>10*1024*1024)throw new IOException("单页过大，请降低图片尺寸");return out.toByteArray();
    }
}
