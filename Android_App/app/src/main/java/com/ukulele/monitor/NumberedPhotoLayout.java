package com.ukulele.monitor;
import java.util.*;

/** Uses local geometry only to enlarge numbered-score rows; OCR guesses never become trusted notes. */
final class NumberedPhotoLayout {
    static final class Plan {final List<int[]> regions;final List<String> hints;Plan(List<int[]> r,List<String> h){regions=r;hints=h;}}
    static Plan detect(int[] pixels,int w,int h,List<OfflineScoreRecognizer.Glyph> templates){
        try{
            OfflineScoreRecognizer.Result r=OfflineScoreRecognizer.recognize(pixels,w,h,OfflineScoreRecognizer.NUMERIC,60,96,templates);
            TreeMap<Integer,int[]> rows=new TreeMap<>();
            for(JianpuRecognizer.Token t:r.numericTokens){int[] b=rows.get(t.row);if(b==null){b=new int[]{t.y,t.y+t.h,t.h,0};rows.put(t.row,b);}b[0]=Math.min(b[0],t.y);b[1]=Math.max(b[1],t.y+t.h);b[2]=Math.max(b[2],t.h);b[3]++;}
            if(rows.size()<2||rows.size()>32||r.numericTokens.size()<16)return null;
            ArrayList<int[]> bands=new ArrayList<>(rows.values());ArrayList<int[]> out=new ArrayList<>();int group=1;
            int header=Math.max(0,bands.get(0)[0]-(int)(bands.get(0)[2]*1.6));
            if(header>h*.3)return null;
            ArrayList<String> lines=new ArrayList<>();
            for(String line:r.notation.split("\\r?\\n"))if(line.trim().matches("[0-7?#b',/._~|:\\-\\s]+")&&line.contains("|"))lines.add(line);
            ArrayList<String> hints=new ArrayList<>();
            for(int i=0;i<bands.size();i+=group){int[] a=bands.get(i),b=bands.get(Math.min(bands.size()-1,i+group-1));
                if(a[3]<3||b[3]<3)return null;
                out.add(new int[]{header,Math.max(header,a[0]-(int)(a[2]*1.4)),Math.min(h,b[1]+(int)(b[2]*.9))});
                StringBuilder hint=new StringBuilder("BPM:96\n1="+(r.detectedTonic<0?"C4":ScorePitch.name(r.detectedTonic))+"\n"+(r.meterNumerator>0?r.meterNumerator+"/"+r.meterDenominator:"4/4")+"\n");
                if(lines.size()==bands.size())for(int q=i;q<Math.min(bands.size(),i+group);q++)hint.append(lines.get(q)).append('\n');
                hints.add(lines.size()==bands.size()?hint.toString():"");
            }
            // Dense rows are read in two enlarged halves, cut at an existing measure boundary.
            ArrayList<int[]> enlarged=new ArrayList<>();ArrayList<String> enlargedHints=new ArrayList<>();
            for(int row=0;row<out.size();row++){
                String hint=hints.get(row),music=lines.size()==bands.size()?lines.get(row):"";
                ArrayList<JianpuRecognizer.Token> tokens=new ArrayList<>();int rowId=new ArrayList<>(rows.keySet()).get(row);
                for(JianpuRecognizer.Token t:r.numericTokens)if(t.row==rowId)tokens.add(t);
                tokens.sort((a,b)->Integer.compare(a.x,b.x));
                int split=-1,leftCount=0,best=Integer.MAX_VALUE;
                if(tokens.size()>=18&&com.ukulele.monitor.ai.NumberedHint.count(music)==tokens.size())for(int q=0;q<music.length()-1;q++)if(music.charAt(q)=='|'){
                    int n=com.ukulele.monitor.ai.NumberedHint.count(music.substring(0,q));int difference=Math.abs(tokens.size()-2*n);
                    if(n>3&&n<tokens.size()-3&&difference<best){best=difference;split=q+1;leftCount=n;}
                }
                if(split<0){enlarged.add(out.get(row));enlargedHints.add(hint);continue;}
                int cut=(tokens.get(leftCount-1).x+tokens.get(leftCount-1).w+tokens.get(leftCount).x)/2;
                int[] v=out.get(row);enlarged.add(new int[]{v[0],v[1],v[2],0,cut});enlarged.add(new int[]{v[0],v[1],v[2],cut,w});
                String metadata=hint.substring(0,hint.indexOf(music));enlargedHints.add(metadata+music.substring(0,split));enlargedHints.add(metadata+music.substring(split));
            }
            return new Plan(enlarged,enlargedHints);
        }catch(RuntimeException ignored){return null;}
    }
}
