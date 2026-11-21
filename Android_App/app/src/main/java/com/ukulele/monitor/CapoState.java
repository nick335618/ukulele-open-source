package com.ukulele.monitor;
final class CapoState {
 final int desired,actual,result,flags,ticket,revision,caps,version,features;
 private CapoState(byte[] d){version=d[1]&255;features=d[11]&255;desired=d[2]&255;actual=d[3]&255;result=d[4]&255;flags=d[5]&255;
  ticket=(d[6]&255)|((d[7]&255)<<8);revision=(d[8]&255)|((d[9]&255)<<8);caps=d[10]&255;}
 static CapoState parse(byte[] d){
  if(d==null||d.length!=13||(d[0]&255)!=0xab||(d[1]!=1&&d[1]!=2)||(d[1]==1&&d[11]!=0)||(d[1]==2&&((d[11]&255)!=0&&(d[11]&255)!=3&&(d[11]&255)!=7)))return null;
  int crc=0;for(int i=0;i<12;i++)crc^=d[i]&255;if(crc!=(d[12]&255))return null;
  int actual=d[3]&255,flags=d[5]&255;
  if((d[2]&255)>12||(actual>12&&actual!=255)||(d[4]&255)>6||flags>15||(d[10]!=0&&d[10]!=12))return null;
  if((flags&1)!=0&&(actual>12||d[10]!=12))return null;
  if((flags&2)!=0&&((flags&1)==0||d[2]!=d[3]))return null;
  return new CapoState(d);
 }
 boolean acceptsAfter(CapoState old){return old==null||((revision-old.revision)&65535)<32768;}
 boolean physicalCapo(){return version>=2&&(features&3)==3;}
 boolean fresh(){return (flags&1)!=0;}
 String status(){return result==1?"正在设置变调夹":result==4?"设置超时，请重试":result==6?"已生效，保存失败":!fresh()?"等待配套 FPGA 回读":!physicalCapo()?"此固件仅支持移调，请更新配套FPGA与ESP32":actual==0?"未夹 · 原调":"已夹第 "+actual+" 品 · 夹持区触摸已屏蔽";}
}
