package com.ukulele.monitor;
final class ModelState {
 final int desired,actual,result,flags,ticket,revision,caps;
 private ModelState(byte[] d){desired=d[2]&255;actual=d[3]&255;result=d[4]&255;flags=d[5]&255;
  ticket=(d[6]&255)|((d[7]&255)<<8);revision=(d[8]&255)|((d[9]&255)<<8);caps=d[10]&255;}
 static ModelState parse(byte[] d){
  if(d==null||d.length!=13||(d[0]&255)!=0xad||d[1]!=1||d[11]!=0)return null;
  int crc=0;for(int i=0;i<12;i++)crc^=d[i]&255;if(crc!=(d[12]&255))return null;
  int actual=d[3]&255,flags=d[5]&255;
  if((d[2]&255)>3||(actual>3&&actual!=255)||(d[4]&255)>6||flags>15||(d[10]!=0&&d[10]!=3&&d[10]!=7))return null;
  if((flags&1)!=0&&(actual>3||(d[10]!=3&&d[10]!=7)))return null;
  if((flags&2)!=0&&((flags&1)==0||d[2]!=d[3]))return null;
  return new ModelState(d);
 }
 boolean acceptsAfter(ModelState old){return old==null||((revision-old.revision)&65535)<32768;}
 boolean fresh(){return (flags&1)!=0;}
 static String name(int index,boolean modern){
  switch(index){
   case 0:return "尤克里里";
   case 1:return "吉他合成";
   case 2:return modern?"拇指琴":"金属拨片";
   case 3:return modern?"光晕钟":"弹簧 Duang";
   default:return "等待 FPGA 音色回读";
  }
 }
 String status(){return result==1?"正在切换音色":result==4?"设置超时，请重试":result==6?"已生效，保存失败":!fresh()?"等待配套 FPGA 回读":"当前音色 · "+name(actual,caps==7);}
}
