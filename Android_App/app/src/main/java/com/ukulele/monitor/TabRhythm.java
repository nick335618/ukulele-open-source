package com.ukulele.monitor;
import java.util.*;
/** Rhythm from the aligned staff, never from horizontal engraving distances. */
final class TabRhythm {
 static final class Value {double beats;int headY,stemX,stemEnd,flags;boolean hollow,dotted;}
 static Value read(boolean[] ink,boolean[] clean,int w,int h,int x,int[] rows,List<OfflineScoreRecognizer.Box> boxes){
  int gap=rows[1]-rows[0],half=Math.max(3,(int)(gap*.6)),height=Math.max(3,(int)(gap*.65));
  int best=-1,cy=0;
  for(int step=-4;step<=12;step++){int y=rows[4]-Math.round(step*gap/2f),count=0;
   for(int yy=Math.max(0,y-height/2);yy<Math.min(h,y+(height+1)/2);yy++)for(int xx=Math.max(0,x-half);xx<=Math.min(w-1,x+half);xx++)if(clean[yy*w+xx])count++;
   if(count>best){best=count;cy=y;}
  }
  if(best<(2*half+1)*height*.22)return null;
  Value v=new Value();v.headY=cy;
  int center=0,total=0,ledgerProbe=Math.max(4,(int)(gap*.8));for(int yy=Math.max(0,cy-height/4);yy<=Math.min(h-1,cy+height/4);yy++){boolean horizontal=x-ledgerProbe>0&&x+ledgerProbe<w&&ink[yy*w+x-ledgerProbe]&&ink[yy*w+x+ledgerProbe];if(horizontal)continue;for(int xx=Math.max(0,x-half/2);xx<=Math.min(w-1,x+half/2);xx++){total++;if(ink[yy*w+xx])center++;}}
  v.hollow=center<total*.48||holeCount(ink,w,h,x,cy,half+3,height/2+4)>=Math.max(3,gap*gap*.015);
  int bestRun=0,stemX=x,stemStart=cy,stemEnd=cy;
  for(int xx=Math.max(0,x-half-2);xx<=Math.min(w-1,x+half+3);xx++){int run=0,start=0;
   for(int yy=Math.max(0,cy-5*gap);yy<=Math.min(h-1,cy+5*gap);yy++){
    if(ink[yy*w+xx]){if(run==0)start=yy;run++;}else{if(run>bestRun&&start<=cy+gap/2&&yy>=cy-gap/2){bestRun=run;stemX=xx;stemStart=start;stemEnd=yy-1;}run=0;}
   }
  }
  if(bestRun<gap*1.8){if(!v.hollow)return null;v.beats=4;return v;}
  boolean up=cy-stemStart>stemEnd-cy;v.stemX=stemX;v.stemEnd=up?stemStart:stemEnd;
  int runs=0;
  int from=up?stemStart-2:stemEnd-(int)(gap*1.7),to=up?stemStart+(int)(gap*1.7):stemEnd+2;
  for(int side:new int[]{-1,1}){int probe=Math.max(0,Math.min(w-1,stemX+side*Math.max(4,(int)(gap*.55)))),countRuns=0,length=0;for(int yy=Math.max(0,from);yy<=Math.min(h-1,to);yy++){
   boolean staff=false;for(int row:rows)if(Math.abs(yy-row)<=2&&!(row>=3&&row+3<h&&ink[(row-3)*w+probe]&&ink[(row+3)*w+probe]))staff=true;
   boolean black=!staff&&ink[yy*w+probe];if(black)length++;else{if(length>=2)countRuns++;length=0;}
  }if(length>=2)countRuns++;runs=Math.max(runs,countRuns);}
  v.flags=Math.min(3,runs);v.beats=v.hollow?2:1.0/(1<<v.flags);
  for(OfflineScoreRecognizer.Box b:boxes)if(b.w>=2&&b.h>=2&&b.w<gap*.6&&b.h<gap*.6&&b.w>b.h*.6&&b.w<b.h*1.6&&b.x>x+gap*.65&&b.x<x+gap*1.9&&Math.abs(b.y+b.h/2-cy)<gap*.8){v.dotted=true;break;}
  if(v.dotted)v.beats*=1.5;
  return v;
 }
 static int holeCount(boolean[] ink,int w,int h,int cx,int cy,int rx,int ry){int x0=Math.max(0,cx-rx),y0=Math.max(0,cy-ry),ww=Math.min(w-1,cx+rx)-x0+1,hh=Math.min(h-1,cy+ry)-y0+1;boolean[] seen=new boolean[ww*hh];int[] queue=new int[seen.length];int head=0,tail=0;for(int y=0;y<hh;y++)for(int x=0;x<ww;x++)if((x==0||x==ww-1||y==0||y==hh-1)&&!ink[(y0+y)*w+x0+x]){int i=y*ww+x;if(!seen[i]){seen[i]=true;queue[tail++]=i;}}
  while(head<tail){int i=queue[head++],x=i%ww,y=i/ww;for(int d=0;d<4;d++){int xx=x+(d==0?-1:d==1?1:0),yy=y+(d==2?-1:d==3?1:0);if(xx>=0&&xx<ww&&yy>=0&&yy<hh){int j=yy*ww+xx;if(!seen[j]&&!ink[(y0+yy)*w+x0+xx]){seen[j]=true;queue[tail++]=j;}}}}
  int holes=0;for(int y=0;y<hh;y++)for(int x=0;x<ww;x++)if(!seen[y*ww+x]&&!ink[(y0+y)*w+x0+x])holes++;return holes;
 }
 static final class Rest {int x;double beats;Rest(int a,double b){x=a;beats=b;}}
 static final class Beat {int x;double beats;Beat(int a,double b){x=a;beats=b;}}
 static List<Integer> bars(boolean[] ink,int w,int[] rows){List<Integer> out=new ArrayList<>();int height=rows[rows.length-1]-rows[0]+1,start=-1;for(int x=0;x<=w;x++){int black=0;if(x<w)for(int y=rows[0];y<=rows[rows.length-1];y++)if(ink[y*w+x])black++;boolean line=x<w&&black>height*.85;if(line){if(start<0)start=x;}else if(start>=0){int center=(start+x-1)/2;if(out.isEmpty()||center-out.get(out.size()-1)>(rows[1]-rows[0])*.75)out.add(center);start=-1;}}return out;}
 static int repeats(List<OfflineScoreRecognizer.Box> boxes,int[] rows,List<Integer> bars){int gap=rows[1]-rows[0],count=0;for(OfflineScoreRecognizer.Box a:boxes){int ax=a.x+a.w/2,ay=a.y+a.h/2;if(a.w<2||a.h<2||a.w>gap*.4||a.h>gap*.4||Math.abs(ay-rows[0]-gap*.5)>gap*.25)continue;for(OfflineScoreRecognizer.Box b:boxes){int bx=b.x+b.w/2,by=b.y+b.h/2;if(b.w<2||b.h<2||b.w>gap*.4||b.h>gap*.4||Math.abs(ax-bx)>3||Math.abs(by-rows[2]-gap*.5)>gap*.25)continue;for(int bar:bars)if(Math.abs(bar-ax)<gap){count++;break;}}}return count;}
 static void measures(List<Beat> beats,List<Integer> bars,List<Double> totals){for(int i=1;i<bars.size();i++){double sum=0;for(Beat b:beats)if(b.x>=bars.get(i-1)&&b.x<bars.get(i))sum+=b.beats;totals.add(sum);}}
 static int inconsistentMeasures(List<Double> totals){if(totals.size()<3)return 0;Map<Integer,Integer> frequencies=new HashMap<>();int mode=0,count=0;for(double t:totals){int n=(int)Math.round(t*8);if(n<8||n>96)continue;int k=frequencies.getOrDefault(n,0)+1;frequencies.put(n,k);if(k>count){mode=n;count=k;}}if(mode==0)return totals.size();int issues=0;for(int i=0;i<totals.size();i++){int n=(int)Math.round(totals.get(i)*8);if(n!=mode&&!(i==0&&n>0&&n<mode)&&!(i==totals.size()-1&&n>0&&n<mode))issues++;}return issues;}
 static List<Rest> rests(boolean[] clean,int w,int[] rows,List<OfflineScoreRecognizer.Box> boxes,List<OfflineScoreRecognizer.Box> notes){
  List<Rest> result=new ArrayList<>();if(rows==null)return result;int gap=rows[1]-rows[0];
  for(OfflineScoreRecognizer.Box b:boxes){if(b.w<gap*.8||b.w>gap*1.7||b.h<gap*.18||b.h>gap*.65)continue;int cy=b.y+b.h/2,cx=b.x+b.w/2;double beats=0;
   if(Math.abs(cy-(rows[2]-gap*.25))<gap*.2)beats=2;else if(Math.abs(cy-(rows[1]+gap*.25))<gap*.2)beats=4;else continue;
   boolean aligned=false;for(OfflineScoreRecognizer.Box n:notes)if(Math.abs(n.x+n.w/2-cx)<gap*.9){aligned=true;break;}if(aligned)continue;
   int black=0;for(int y=b.y;y<b.y+b.h;y++)for(int x=b.x;x<b.x+b.w;x++)if(clean[y*w+x])black++;if(black>b.w*b.h*.8)result.add(new Rest(cx,beats));
  }Collections.sort(result,(a,b)->Integer.compare(a.x,b.x));return result;
 }
 static int[] precedingStaff(List<Integer> peaks,int[] tab){
  int[] found=null;
  for(int i=0;i+5<=peaks.size();i++){int gap=peaks.get(i+1)-peaks.get(i);if(gap<5||peaks.get(i+4)>=tab[0])continue;boolean good=true;
   for(int j=1;j<5;j++)good&=Math.abs(peaks.get(i+j)-peaks.get(i)-j*gap)<gap*.25+2;
   if(good&&tab[0]-peaks.get(i+4)<gap*10){found=new int[5];for(int j=0;j<5;j++)found[j]=peaks.get(i+j);}
  }return found;
 }
}
