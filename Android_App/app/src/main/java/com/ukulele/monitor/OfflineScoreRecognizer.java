package com.ukulele.monitor;
import java.util.*;
import java.io.*;
/** Conservative on-device printed-score draft recognition. No network, no hidden octave folding. */
final class OfflineScoreRecognizer {
 static final int NUMERIC=0,TAB4=1,TAB6=2,TREBLE=3,BASS=4;
 static final class Glyph {final int digit;final boolean[] pixels;Glyph(int n,boolean[] p){digit=n;pixels=p;}}
 static final class Box {int x,y,w,h;Box(int a,int c,int e,int f){x=a;y=c;w=e;h=f;}}
 static final class Result {final ArrayList<ScorePitch.Note> notes=new ArrayList<>();final ArrayList<JianpuRecognizer.Token> numericTokens=new ArrayList<>();int uncertain,systems,rhythmRead,rhythmMissing,repeatSigns,measureIssues,detectedTonic=-1,meterNumerator,meterDenominator;long durationMs;boolean requiresReview;String warning,notation;int unresolved;}
 static final int GW=16,GH=24;
 static void loadTemplates(InputStream input,List<Glyph> into)throws IOException{try(BufferedReader r=new BufferedReader(new InputStreamReader(input,"UTF-8"))){String s;int count=0;while((s=r.readLine())!=null){if(s.startsWith("#")||s.trim().isEmpty())continue;if(s.length()!=GW*GH+2||s.charAt(1)!=':'||s.charAt(0)<'0'||s.charAt(0)>'9'||++count>500)throw new IOException("识谱模板格式错误");boolean[] bits=new boolean[GW*GH];for(int i=0;i<bits.length;i++){char c=s.charAt(i+2);if(c!='0'&&c!='1')throw new IOException("识谱模板数据错误");bits[i]=c=='1';}into.add(new Glyph(s.charAt(0)-'0',bits));}}}
 static boolean[] normalized(boolean[] ink,int width,Box b){boolean[] out=new boolean[GW*GH];for(int y=0;y<GH;y++)for(int x=0;x<GW;x++){int sx=b.x+Math.min(b.w-1,x*b.w/GW),sy=b.y+Math.min(b.h-1,y*b.h/GH);out[y*GW+x]=ink[sy*width+sx];}return out;}
 static int digit(boolean[] normalized,List<Glyph> templates){int best=-1;double distance=1;
  for(Glyph g:templates){if(g.digit>9)continue;int mismatch=0,union=0;for(int i=0;i<normalized.length;i++){if(normalized[i]!=g.pixels[i])mismatch++;if(normalized[i]||g.pixels[i])union++;}double d=mismatch/(double)Math.max(1,union);if(d<distance){distance=d;best=g.digit;}}
  return distance<.45?best:-1;
 }
 static int gray(int color){int value=(((color>>16)&255)*30+((color>>8)&255)*59+(color&255)*11)/100;int alpha=color>>>24;return alpha==255?value:(value*alpha+255*(255-alpha)+127)/255;}
 static Result recognize(int[] pixels,int w,int h,int type,int tonic,int bpm,List<Glyph> templates){
  if(w<40||h<40||w*h>5000000)throw new IllegalArgumentException("图像尺寸不适合识谱");
  if(type==NUMERIC)return JianpuRecognizer.recognize(pixels,w,h,tonic,bpm,templates);
  boolean[] ink=new boolean[w*h];for(int i=0;i<ink.length;i++){int c=pixels[i];ink[i]=gray(c)<165;}
  ArrayList<Integer> peaks=new ArrayList<>();for(int y=0;y<h;y++){int longest=0,run=0,total=0;for(int x=0;x<w;x++){run=ink[y*w+x]?run+1:0;longest=Math.max(longest,run);if(ink[y*w+x])total++;}if(total>w*.55||longest>w*.55){if(peaks.isEmpty()||y-peaks.get(peaks.size()-1)>3)peaks.add(y);}}
  int lines=type==TAB4?4:type==TAB6?6:5;ArrayList<int[]> systems=new ArrayList<>();
  if(type!=NUMERIC)for(int i=0;i+lines<=peaks.size();){int gap=peaks.get(i+1)-peaks.get(i);boolean regular=gap>=5;for(int j=1;j<lines;j++)regular&=Math.abs(peaks.get(i+j)-peaks.get(i)-j*gap)<gap*.25+2;
   if(regular){int end=i+lines;while(end<peaks.size()&&Math.abs(peaks.get(end)-peaks.get(end-1)-gap)<gap*.25+2)end++;if(end-i==lines){int[] rows=new int[lines];for(int j=0;j<lines;j++)rows[j]=peaks.get(i+j);systems.add(rows);}i=end;}else i++;
  }
  if(type!=NUMERIC&&systems.isEmpty())throw new IllegalArgumentException("没有找到完整谱线，请裁正图片并核对谱型");
  int removalRun=Math.max(12,w/100);if(peaks.size()>1){int smallest=h;for(int i=1;i<peaks.size();i++)if(peaks.get(i)-peaks.get(i-1)>4)smallest=Math.min(smallest,peaks.get(i)-peaks.get(i-1));removalRun=Math.max(removalRun,smallest*2);}
  boolean[] clean=ink.clone();for(Integer y:peaks)for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+2);yy++){
   int localRun=removalRun;if(type==TAB4||type==TAB6)for(int[] system:systems)for(int row:system)if(row==y)localRun=Math.max(12,(int)((system[1]-system[0])*.8));
   int start=-1;for(int x=0;x<=w;x++){if(x<w&&ink[yy*w+x]){if(start<0)start=x;}else{if(start>=0&&x-start>localRun)for(int xx=start;xx<x;xx++)clean[yy*w+xx]=false;start=-1;}}
  }
  // Bridge the thin line-removal gaps in digit/note stems; do not dilate unrelated symbols.
  for(int y=2;y<h-2;y++)for(int x=0;x<w;x++)if(ink[y*w+x]&&!clean[y*w+x]&&clean[(y-2)*w+x]&&clean[(y+2)*w+x]){clean[(y-1)*w+x]=ink[(y-1)*w+x];clean[y*w+x]=true;clean[(y+1)*w+x]=ink[(y+1)*w+x];}
  ArrayList<Box> boxes=components(clean,w,h);Collections.sort(boxes,(a,c)->a.y/Math.max(12,h/100)==c.y/Math.max(12,h/100)?Integer.compare(a.x,c.x):Integer.compare(a.y,c.y));
  Result out=new Result();out.systems=systems.size();double quarter=60000.0/Math.max(30,Math.min(240,bpm)),cursor=0;
  if(type==TAB4||type==TAB6){
   int[] tuning=type==TAB4?new int[]{69,64,60,67}:new int[]{64,59,55,50,45,40};
   List<Double> measureTotals=new ArrayList<>();
   for(int[] rows:systems){int gap=rows[1]-rows[0];int left=w;for(int x=0;x<w;x++)if(ink[rows[rows.length-1]*w+x]){left=x;break;}ArrayList<Box> candidates=new ArrayList<>();for(Box q:boxes)if(q.x>=left&&q.y+q.h/2>=rows[0]-gap/2&&q.y+q.h/2<=rows[rows.length-1]+gap/2&&q.h>=gap*.35&&q.h<gap*1.8){if(q.w<gap*1.1)candidates.add(q);else if(q.x>left+gap*2.7&&q.w<gap*2)out.uncertain++;}
    int[] staff=TabRhythm.precedingStaff(peaks,rows);List<TabRhythm.Rest> rests=TabRhythm.rests(clean,w,staff,boxes,candidates);List<TabRhythm.Beat> beats=new ArrayList<>();for(TabRhythm.Rest r:rests)beats.add(new TabRhythm.Beat(r.x,r.beats));List<Integer> bars=TabRhythm.bars(ink,w,rows);out.repeatSigns+=TabRhythm.repeats(boxes,rows,bars);int restIndex=0;Collections.sort(candidates,(a,c)->Integer.compare(a.x,c.x));int previousX=-10000;long at=Math.round(cursor);
    for(int k=0;k<candidates.size();k++){Box q=candidates.get(k);int n=digit(normalized(clean,w,q),templates);if(Boolean.getBoolean("score.debug"))System.out.println("BOX "+q.x+","+q.y+","+q.w+","+q.h+" n="+n);if(n<0){out.uncertain++;continue;}int center=q.y+q.h/2;int row=0;for(int j=1;j<rows.length;j++)if(Math.abs(rows[j]-center)<Math.abs(rows[row]-center))row=j;
     if(k+1<candidates.size()){Box next=candidates.get(k+1);if(Math.abs(next.y-q.y)<q.h*.3&&next.x-q.x-q.w>=0&&next.x-q.x-q.w<q.h*.35){int d=digit(normalized(clean,w,next),templates);if(d>=0){n=n*10+d;k++;}}}
     if(n>24){out.uncertain++;continue;}if(q.x-previousX>gap*.8){while(restIndex<rests.size()&&rests.get(restIndex).x<q.x){cursor+=quarter*rests.get(restIndex++).beats;}at=Math.round(cursor);TabRhythm.Value rhythm=staff==null?null:TabRhythm.read(ink,clean,w,h,q.x+q.w/2,staff,boxes);if(rhythm!=null)out.rhythmRead++;else out.rhythmMissing++;double duration=rhythm==null?1:rhythm.beats;cursor+=quarter*duration;beats.add(new TabRhythm.Beat(q.x,duration));previousX=q.x;if(Boolean.getBoolean("score.debug")&&rhythm!=null)System.out.println("RHYTHM "+q.x+" cy="+rhythm.headY+" stem="+rhythm.stemX+","+rhythm.stemEnd+" hollow="+rhythm.hollow+" flags="+rhythm.flags+" dot="+rhythm.dotted+" beats="+rhythm.beats);}out.notes.add(new ScorePitch.Note(at,tuning[row]+n,type==TAB4?3-row:-1));
    }while(restIndex<rests.size())cursor+=quarter*rests.get(restIndex++).beats;TabRhythm.measures(beats,bars,measureTotals);
   }out.measureIssues=TabRhythm.inconsistentMeasures(measureTotals);
  }else{
   for(int[] rows:systems){int gap=rows[1]-rows[0];ArrayList<Box> candidates=new ArrayList<>();for(Box q:boxes)if(q.y+q.h>=rows[0]-gap*3&&q.y<=rows[4]+gap*3&&q.w>=gap*.65&&q.w<=gap*2.1&&q.h>=gap*.4&&q.h<gap*6)candidates.add(q);
    Collections.sort(candidates,(a,c)->Integer.compare(a.x,c.x));int previousX=-10000;long at=Math.round(cursor);
    for(Box q:candidates){int nh=Math.max(3,(int)(gap*.7)),nw=Math.max(4,(int)(gap*1.15)),bestCount=0,headY=q.y;for(int yy=q.y;yy<=q.y+q.h-nh;yy++){int count=0;for(int y=yy;y<yy+nh&&y<h;y++)for(int x=q.x;x<Math.min(w,q.x+nw);x++)if(clean[y*w+x])count++;if(count>bestCount){bestCount=count;headY=yy;}}
     if(bestCount<nw*nh*.38){out.uncertain++;continue;}int steps=Math.round((rows[4]-(headY+nh/2f))*2/gap);int diatonic=(type==TREBLE?30:18)+steps; // E4 or G2 bottom line
     int octave=Math.floorDiv(diatonic,7);int pc=new int[]{0,2,4,5,7,9,11}[Math.floorMod(diatonic,7)];int midi=12*(octave+1)+pc;
     if(q.x-previousX>gap*.7){at=Math.round(cursor);cursor+=quarter;previousX=q.x;}out.notes.add(new ScorePitch.Note(at,midi));
    }
   }
  }
  if(out.notes.isEmpty())throw new IllegalArgumentException("没有可靠音符，原谱已保留；可手动录入校对");
  out.durationMs=Math.round(cursor);out.requiresReview=out.uncertain>0||out.rhythmMissing>0||out.repeatSigns>0||out.measureIssues>0||out.rhythmRead==0;out.warning=(out.requiresReview?"需人工校对 · ":"基本音高/节奏结构检查通过 · ")+(out.rhythmRead>0?"配套节奏读取"+out.rhythmRead+"处":"没有可确认的谱面时值，等拍时间仅为占位")+(out.rhythmMissing>0?" · "+out.rhythmMissing+"处节奏未确认":"")+(out.uncertain>0?" · "+out.uncertain+"个区域未识别":"")+(out.repeatSigns>0?" · 有反复符号，尚未展开":"")+(out.measureIssues>0?" · "+out.measureIssues+"个小节时值不一致":"")+"；保存前仍需对照原谱";return out;
 }
 static ArrayList<Box> components(boolean[] ink,int w,int h){boolean[] seen=new boolean[ink.length];int[] stack=new int[ink.length];ArrayList<Box> result=new ArrayList<>();for(int i=0;i<ink.length;i++)if(ink[i]&&!seen[i]){int tail=1,head=0;stack[0]=i;seen[i]=true;int minX=i%w,maxX=minX,minY=i/w,maxY=minY;while(head<tail){int p=stack[head++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int xx=x+dx,yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h)continue;int n=yy*w+xx;if(ink[n]&&!seen[n]){seen[n]=true;stack[tail++]=n;}}}if(tail>=2)result.add(new Box(minX,minY,maxX-minX+1,maxY-minY+1));}return result;}
}
