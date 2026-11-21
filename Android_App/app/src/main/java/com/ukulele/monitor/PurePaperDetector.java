package com.ukulele.monitor;
import java.util.Arrays;
/** Pure Java page corner detection: the OpenCV free replacement for the previous OpenCvPaper pipeline.
 *  The core works on plain {@code int[] ARGB + width + height} so it can be verified on a desktop JVM;
 *  {@link OpenCvPaper#outline} only forwards {@code Bitmap.getPixels}. Semantics preserved:
 *  1) downscale so the longest side is at most 700; 2) RGBA2GRAY fixed point, 5x5 Gaussian (sigma 1.1,
 *  reflect101), Canny(35,100) (Sobel 3x3, replicate, L1 magnitude) and 3x3 dilation; 3) largest
 *  8-connected edge component whose outer border simplifies (epsilon = 2% of the closed perimeter) to
 *  exactly four convex vertices, covers 20%..92% of the frame and keeps every corner off the frame
 *  border; 4) tl/tr/br/bl by the x+y / x-y extremes, four distinct points; 5) the page must be brighter
 *  than its surroundings (mean inner minus outer gray &gt; 25) or more colourful (chroma &gt; 12).
 *  Any uncertainty returns null and the caller keeps the uncorrected photo. */
final class PurePaperDetector {
 private PurePaperDetector(){}
 /** Longest side of the working image; the value the OpenCV build resized to. */
 private static final int MAX_SIDE=700;
 private static final int CANNY_LOW=35,CANNY_HIGH=100;
 /** approxPolyDP epsilon as a fraction of the closed contour perimeter. */
 private static final double EPSILON_RATIO=.02;
 private static final double MIN_AREA_RATIO=.20,MAX_AREA_RATIO=.92;
 /** Corner rejection band: x<2, y<2, x>w-3, y>h-3, exactly as the OpenCV version. */
 private static final int EDGE_NEAR=2,EDGE_FAR=3;
 private static final double CONTRAST_GRAY=25,CONTRAST_CHROMA=12;
 private static final int CONTRAST_SAMPLES=8;
 private static final double GAUSSIAN_SIGMA=1.1;
 private static final double TAN_22=0.41421356237309504880,TAN_67=1/0.41421356237309504880;
 /** 8 neighbourhood, clockwise in image coordinates (y downwards) starting east. */
 private static final int[] DX={1,1,0,-1,-1,-1,0,1},DY={0,1,1,1,0,-1,-1,-1};
 private static final double[] GAUSS=gaussianKernel(5,GAUSSIAN_SIGMA);

 /** @return tl.x,tl.y,tr.x,tr.y,br.x,br.y,bl.x,bl.y in original pixels, or null when unsure. */
 static float[] detect(int[] pixels,int width,int height){return detect(pixels,width,height,null);}

 /** @param log optional explanation of a null result, may be null. */
 static float[] detect(int[] pixels,int width,int height,StringBuilder log){
  if(pixels==null||width<16||height<16||pixels.length<width*height){if(log!=null)log.append("invalid input");return null;}
  try{
   double scale=Math.min(1,MAX_SIDE/(double)Math.max(width,height));
   int sw=(int)Math.round(width*scale),sh=(int)Math.round(height*scale);
   // An absurd aspect ratio would distort the working image; the OpenCV build failed to resize
   // there as well, so stay conservative and keep the whole photo.
   if(sw<16||sh<16){if(log!=null)log.append("degenerate aspect ratio");return null;}
   byte[] edges=canny(gaussian5(resizeGray(pixels,width,height,sw,sh),sw,sh),sw,sh);dilate3(edges,sw,sh);
   int[] quad=largestPageQuad(edges,sw,sh,log);
   if(quad==null)return null;
   if(!contrastingPageEdge(pixels,width,height,quad,scale)){if(log!=null)log.append("page edge does not contrast with its surroundings");return null;}
   float[] result=new float[8];for(int i=0;i<8;i++)result[i]=(float)(quad[i]/scale);return result;
  }catch(RuntimeException failure){if(log!=null)log.append("failed: ").append(failure);return null;}
  catch(OutOfMemoryError failure){if(log!=null)log.append("out of memory");return null;}
 }

 /** Bilinear RGBA downscale followed by the RGBA2GRAY fixed point conversion, as the OpenCV build did. */
 private static byte[] resizeGray(int[] pixels,int w,int h,int sw,int sh){
  byte[] out=new byte[sw*sh];double fx=w/(double)sw,fy=h/(double)sh;
  for(int y=0;y<sh;y++){
   double sy=(y+.5)*fy-.5;if(sy<0)sy=0;else if(sy>h-1)sy=h-1;
   int y0=(int)sy,y1=y0+1<h?y0+1:y0,row0=y0*w,row1=y1*w;double wy=sy-y0;
   for(int x=0;x<sw;x++){
    double sx=(x+.5)*fx-.5;if(sx<0)sx=0;else if(sx>w-1)sx=w-1;
    int x0=(int)sx,x1=x0+1<w?x0+1:x0;double wx=sx-x0;
    int c00=pixels[row0+x0],c01=pixels[row0+x1],c10=pixels[row1+x0],c11=pixels[row1+x1];
    double r=mix(c00>>>16&255,c01>>>16&255,c10>>>16&255,c11>>>16&255,wx,wy);
    double g=mix(c00>>>8&255,c01>>>8&255,c10>>>8&255,c11>>>8&255,wx,wy);
    double b=mix(c00&255,c01&255,c10&255,c11&255,wx,wy);
    out[y*sw+x]=(byte)(((int)(r*4899+g*9617+b*1868+8192)>>14)&255);
   }
  }
  return out;
 }
 private static double mix(int a,int b,int c,int d,double wx,double wy){double top=a+(b-a)*wx,bottom=c+(d-c)*wx;return top+(bottom-top)*wy;}

 private static double[] gaussianKernel(int size,double sigma){
  double[] kernel=new double[size];double sum=0,half=(size-1)/2.0;
  for(int i=0;i<size;i++){double d=i-half;kernel[i]=Math.exp(-(d*d)/(2*sigma*sigma));sum+=kernel[i];}
  for(int i=0;i<size;i++)kernel[i]/=sum;return kernel;
 }
 /** Separable 5x5 Gaussian with BORDER_REFLECT_101, the border rule of Imgproc.GaussianBlur. */
 private static byte[] gaussian5(byte[] gray,int w,int h){
  int n=w*h;byte[] rows=new byte[n];
  for(int y=0;y<h;y++){int row=y*w;for(int x=0;x<w;x++){double sum=0;for(int k=0;k<5;k++)sum+=GAUSS[k]*(gray[row+reflect101(x+k-2,w)]&255);rows[row+x]=(byte)clamp255((int)(sum+.5));}}
  byte[] out=new byte[n];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){double sum=0;for(int k=0;k<5;k++)sum+=GAUSS[k]*(rows[reflect101(y+k-2,h)*w+x]&255);out[y*w+x]=(byte)clamp255((int)(sum+.5));}
  return out;
 }
 private static int reflect101(int index,int size){if(index<0)index=-index;if(index>=size)index=2*size-2-index;if(index<0)index=0;if(index>=size)index=size-1;return index;}
 private static int clamp255(int value){return value<0?0:(value>255?255:value);}
 private static int clampIndex(int value,int limit){return value<0?0:(value>=limit?limit-1:value);}

 /** Sobel 3x3 (BORDER_REPLICATE) + L1 magnitude + non maximum suppression + 8-connected hysteresis. */
 private static byte[] canny(byte[] gray,int w,int h){
  int n=w*h;short[] gx=new short[n],gy=new short[n];int[] magnitude=new int[n];
  for(int y=0;y<h;y++){
   int up=(y>0?y-1:0)*w,mid=y*w,down=(y<h-1?y+1:h-1)*w;
   for(int x=0;x<w;x++){
    int left=x>0?x-1:0,right=x<w-1?x+1:w-1;
    int p00=gray[up+left]&255,p01=gray[up+x]&255,p02=gray[up+right]&255;
    int p10=gray[mid+left]&255,p12=gray[mid+right]&255;
    int p20=gray[down+left]&255,p21=gray[down+x]&255,p22=gray[down+right]&255;
    int dx=(p02-p00)+2*(p12-p10)+(p22-p20),dy=(p20-p00)+2*(p21-p01)+(p22-p02);
    gx[mid+x]=(short)dx;gy[mid+x]=(short)dy;magnitude[mid+x]=Math.abs(dx)+Math.abs(dy);
   }
  }
  byte[] state=new byte[n];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int i=y*w+x,m=magnitude[i];if(m<=CANNY_LOW)continue;
   int ax=Math.abs(gx[i]),ay=Math.abs(gy[i]),sx,sy;
   if(ay<=ax*TAN_22){sx=1;sy=0;}else if(ay>=ax*TAN_67){sx=0;sy=1;}else if(gx[i]*gy[i]>0){sx=1;sy=1;}else{sx=1;sy=-1;}
   int m1=magnitude[clampIndex(y-sy,h)*w+clampIndex(x-sx,w)],m2=magnitude[clampIndex(y+sy,h)*w+clampIndex(x+sx,w)];
   if((m>m1&&m>=m2)||(m>=m1&&m>m2))state[i]=(byte)(m>CANNY_HIGH?2:1);
  }
  int[] stack=new int[1024];int sp=0;
  for(int i=0;i<n;i++)if(state[i]==2){if(sp==stack.length)stack=Arrays.copyOf(stack,sp*2);stack[sp++]=i;}
  while(sp>0){
   int index=stack[--sp],y=index/w,x=index-y*w;
   for(int k=0;k<8;k++){
    int nx=x+DX[k],ny=y+DY[k];if(nx<0||ny<0||nx>=w||ny>=h)continue;
    int next=ny*w+nx;
    if(state[next]==1){state[next]=2;if(sp==stack.length)stack=Arrays.copyOf(stack,sp*2);stack[sp++]=next;}
   }
  }
  for(int i=0;i<n;i++)state[i]=(byte)(state[i]==2?1:0);
  return state;
 }
 /** 3x3 full dilation; the border reads as background, the rule Imgproc.dilate applies by default. */
 private static void dilate3(byte[] f,int w,int h){
  byte[] src=f.clone();
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   byte value=0;
   for(int dy=-1;dy<=1&&value==0;dy++){int ny=y+dy;if(ny<0||ny>=h)continue;int row=ny*w;
    for(int dx=-1;dx<=1;dx++){int nx=x+dx;if(nx<0||nx>=w)continue;if(src[row+nx]!=0){value=1;break;}}}
   f[y*w+x]=value;
  }
 }
 /** Largest edge component that looks like a photographed page; corners ordered tl,tr,br,bl. */
 private static int[] largestPageQuad(byte[] edges,int w,int h,StringBuilder log){
  int total=w*h;boolean[] seen=new boolean[total];int[] stack=new int[4096];
  double bestArea=0;int[] best=null;
  int components=0,smallBox=0,notQuad=0,nonConvex=0,areaLow=0,areaHigh=0,clippedCount=0,degenerate=0;
  for(int seed=0;seed<total;seed++){
   if(edges[seed]==0||seen[seed])continue;
   components++;
   int sp=0;stack[sp++]=seed;seen[seed]=true;
   int minX=w,minY=h,maxX=-1,maxY=-1,count=0;
   while(sp>0){
    int index=stack[--sp],y=index/w,x=index-y*w;count++;
    if(x<minX)minX=x;if(x>maxX)maxX=x;if(y<minY)minY=y;if(y>maxY)maxY=y;
    for(int k=0;k<8;k++){
     int nx=x+DX[k],ny=y+DY[k];if(nx<0||ny<0||nx>=w||ny>=h)continue;
     int next=ny*w+nx;
     if(edges[next]!=0&&!seen[next]){seen[next]=true;if(sp==stack.length)stack=Arrays.copyOf(stack,sp*2);stack[sp++]=next;}
    }
   }
   if(count<24)continue;
   // A polygon inside this bounding box can never reach the 20% area floor: safe early skip.
   if((long)(maxX-minX+1)*(maxY-minY+1)<total*MIN_AREA_RATIO){smallBox++;continue;}
   int[] trace=traceOuter(edges,w,h,seed%w,seed/w,count);
   if(trace.length<24)continue;
   int[] polygon=approxClosed(trace,trace.length/2,EPSILON_RATIO*tracePerimeter(trace,trace.length/2));
   if(polygon==null||polygon.length!=8){notQuad++;continue;}
   if(!convexQuad(polygon)){nonConvex++;continue;}
   double area=polygonArea(polygon);
   if(area<total*MIN_AREA_RATIO){areaLow++;continue;}
   if(area>total*MAX_AREA_RATIO){areaHigh++;continue;}
   if(area<=bestArea)continue;
   boolean clipped=false;
   for(int i=0;i<4&&!clipped;i++){int x=polygon[i*2],y=polygon[i*2+1];clipped=x<EDGE_NEAR||y<EDGE_NEAR||x>w-EDGE_FAR||y>h-EDGE_FAR;}
   if(clipped){clippedCount++;continue;}
   int[] ordered=orderCorners(polygon);
   if(ordered==null){degenerate++;continue;}
   best=ordered;bestArea=area;
  }
  if(log!=null){
   if(best!=null)log.append("quad area ").append(Math.round(bestArea)).append('/').append(total).append(" (").append(Math.round(100*bestArea/total)).append("%)");
   else log.append("no page quad (components=").append(components).append(" smallBox=").append(smallBox).append(" notQuad=").append(notQuad).append(" nonConvex=").append(nonConvex).append(" areaLow=").append(areaLow).append(" areaHigh=").append(areaHigh).append(" clipped=").append(clippedCount).append(" degenerate=").append(degenerate).append(')');
  }
  return best;
 }
 /** Moore neighbour tracing of the outer border of one 8-connected component. The walk starts at the
  *  seed with its west neighbour (background) as the backtrack and searches clockwise from there, so
  *  it hugs the outside of the component; the first return to the seed closes the walk, which is the
  *  outer border of a simple closed edge loop such as a page outline.
  *  @return flat x,y pairs; the seed must be the first component pixel in raster order. */
 private static int[] traceOuter(byte[] f,int w,int h,int sx,int sy,int count){
  int[] trace=new int[1024];int n=0,x=sx,y=sy,dir=4; // the seed's west neighbour is background
  trace[n++]=x;trace[n++]=y;
  int guard=4*count+64;
  while(guard-->0){
   int found=-1;
   for(int k=1;k<=8;k++){
    int nd=(dir+k)&7,nx=x+DX[nd],ny=y+DY[nd];
    if(nx<0||ny<0||nx>=w||ny>=h||f[ny*w+nx]==0)continue;
    found=nd;break;
   }
   if(found<0)break;
   x+=DX[found];y+=DY[found];dir=(found+4)&7;
   if(x==sx&&y==sy)break;
   if(n+2>trace.length)trace=Arrays.copyOf(trace,n*2);
   trace[n++]=x;trace[n++]=y;
  }
  return Arrays.copyOf(trace,n);
 }
 /** Douglas-Peucker simplification of a closed contour, the role of approxPolyDP(closed=true). */
 private static int[] approxClosed(int[] trace,int n,double epsilon){
  if(n<8)return null;
  double[] cx=new double[n+1],cy=new double[n+1];
  for(int i=0;i<n;i++){cx[i]=trace[i*2];cy[i]=trace[i*2+1];}
  cx[n]=cx[0];cy[n]=cy[0];
  // Split the ring at the point farthest from the start; both anchors always survive.
  int split=0;double farthest=-1;
  for(int i=1;i<n;i++){double dx=cx[i]-cx[0],dy=cy[i]-cy[0],d=dx*dx+dy*dy;if(d>farthest){farthest=d;split=i;}}
  if(split<1)return null;
  boolean[] keep=new boolean[n+1];keep[0]=keep[split]=keep[n]=true;
  keepCorners(cx,cy,0,split,epsilon,keep);keepCorners(cx,cy,split,n,epsilon,keep);
  int count=0;for(int i=0;i<n;i++)if(keep[i])count++;
  if(count<3||count>64)return null;
  int[] polygon=new int[count*2];int at=0;
  for(int i=0;i<n;i++)if(keep[i]){polygon[at++]=(int)cx[i];polygon[at++]=(int)cy[i];}
  return polygon;
 }
 private static void keepCorners(double[] xs,double[] ys,int from,int to,double epsilon,boolean[] keep){
  int[] stack=new int[4*(to-from+2)];int sp=0;stack[sp++]=from;stack[sp++]=to;
  while(sp>0){
   int b=stack[--sp],a=stack[--sp];
   if(b<=a+1)continue;
   double dx=xs[b]-xs[a],dy=ys[b]-ys[a],length=Math.hypot(dx,dy),worst=-1;int at=-1;
   for(int i=a+1;i<b;i++){
    double d=length<1e-9?Math.hypot(xs[i]-xs[a],ys[i]-ys[a]):Math.abs((xs[i]-xs[a])*dy-(ys[i]-ys[a])*dx)/length;
    if(d>worst){worst=d;at=i;}
   }
   if(at>a&&worst>epsilon){keep[at]=true;stack[sp++]=a;stack[sp++]=at;stack[sp++]=at;stack[sp++]=b;}
  }
 }
 private static double tracePerimeter(int[] trace,int n){
  double sum=0;
  for(int i=0;i<n;i++){int j=(i+1)%n;sum+=Math.hypot(trace[j*2]-trace[i*2],trace[j*2+1]-trace[i*2+1]);}
  return sum;
 }
 private static double polygonArea(int[] polygon){
  int n=polygon.length/2;double twice=0;
  for(int i=0;i<n;i++){int j=(i+1)%n;twice+=polygon[i*2]*(double)polygon[j*2+1]-polygon[j*2]*(double)polygon[i*2+1];}
  return Math.abs(twice)*.5;
 }
 /** All four turns must go the same way: the role of Imgproc.isContourConvex for a quad. */
 private static boolean convexQuad(int[] p){
  int positive=0,negative=0;
  for(int i=0;i<4;i++){
   int a=i*2,b=((i+1)%4)*2,c=((i+2)%4)*2;
   double cross=(p[b]-p[a])*(double)(p[c+1]-p[b+1])-(p[b+1]-p[a+1])*(double)(p[c]-p[b]);
   if(cross>1e-9)positive++;else if(cross<-1e-9)negative++;
  }
  return positive==4||negative==4;
 }
 /** tl,tr,br,bl by the x+y / x-y extremes; null unless the four corners are distinct. */
 private static int[] orderCorners(int[] p){
  int tl=0,tr=0,br=0,bl=0;
  for(int i=1;i<4;i++){
   if(p[i*2]+p[i*2+1]<p[tl*2]+p[tl*2+1])tl=i;
   if(p[i*2]-p[i*2+1]>p[tr*2]-p[tr*2+1])tr=i;
   if(p[i*2]+p[i*2+1]>p[br*2]+p[br*2+1])br=i;
   if(p[i*2]-p[i*2+1]<p[bl*2]-p[bl*2+1])bl=i;
  }
  if(tl==tr||tl==br||tl==bl||tr==br||tr==bl||br==bl)return null;
  return new int[]{p[tl*2],p[tl*2+1],p[tr*2],p[tr*2+1],p[br*2],p[br*2+1],p[bl*2],p[bl*2+1]};
 }
 /** The page has to stand out from its surroundings, otherwise the whole image is kept. */
 private static boolean contrastingPageEdge(int[] pixels,int width,int height,int[] quad,double scale){
  double cx=0,cy=0;
  for(int i=0;i<4;i++){cx+=quad[i*2]/scale/4;cy+=quad[i*2+1]/scale/4;}
  double innerGray=0,outerGray=0,innerChroma=0,outerChroma=0;int samples=0;
  for(int edge=0;edge<4;edge++){
   int a=edge*2,b=((edge+1)%4)*2;
   for(int step=2;step<9;step++){
    double f=step/10.0,x=(quad[a]*(1-f)+quad[b]*f)/scale,y=(quad[a+1]*(1-f)+quad[b+1]*f)/scale;
    int ix=(int)Math.round(x+(cx-x)*.04),iy=(int)Math.round(y+(cy-y)*.04);
    int ox=(int)Math.round(x-(cx-x)*.04),oy=(int)Math.round(y-(cy-y)*.04);
    if(ix<0||iy<0||ox<0||oy<0||ix>=width||ox>=width||iy>=height||oy>=height)continue;
    int inside=pixels[iy*width+ix],outside=pixels[oy*width+ox];
    innerGray+=gray(inside);outerGray+=gray(outside);
    innerChroma+=chroma(inside);outerChroma+=chroma(outside);
    samples++;
   }
  }
  return samples>=CONTRAST_SAMPLES&&(innerGray/samples-outerGray/samples>CONTRAST_GRAY||innerChroma/samples-outerChroma/samples>CONTRAST_CHROMA);
 }
 /** Same luminance the offline recogniser uses, so the contrast test keeps its old meaning. */
 private static int gray(int color){
  int value=(((color>>16)&255)*30+((color>>8)&255)*59+(color&255)*11)/100,alpha=color>>>24;
  return alpha==255?value:(value*alpha+255*(255-alpha)+127)/255;
 }
 private static int chroma(int c){int r=c>>16&255,g=c>>8&255,b=c&255;return Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));}
}
