package com.ukulele.monitor;
/** 20-byte independent state, fits an ATT payload at the minimum MTU. */
final class TouchState {
 final int threshold,fast,grace,actualThreshold,actualFast,actualGrace,flags,result,ticket,revision,sensorFlags;
 private TouchState(byte[] d){threshold=u16(d,2);fast=d[4]&255;grace=d[5]&255;actualThreshold=u16(d,6);actualFast=d[8]&255;actualGrace=d[9]&255;
  flags=d[10]&255;result=d[11]&255;ticket=u16(d,12);revision=u16(d,14);sensorFlags=d[16]&255;}
 static int u16(byte[] b,int i){return(b[i]&255)|((b[i+1]&255)<<8);}
 static boolean valid(int th,int fast,int grace){return th>=120&&th<=2400&&fast<=1&&grace<=60;}
 static TouchState parse(byte[] d){
  if(d==null||d.length!=20||(d[0]&255)!=0xA5||d[1]!=1||d[17]!=7||d[18]!=0)return null;
  int x=0;for(int i=0;i<19;i++)x^=d[i]&255;if(x!=(d[19]&255))return null;
  TouchState s=new TouchState(d);if(!valid(s.threshold,s.fast,s.grace)||s.flags>15||s.result>6||s.sensorFlags>7)return null;
  if(s.fresh()){if(!valid(s.actualThreshold,s.actualFast,s.actualGrace))return null;}
  else if(s.actualFast!=255||s.actualGrace!=255||s.sensorFlags!=0)return null;
  if((s.flags&2)!=0&&(!s.fresh()||s.sensorFlags!=1||s.threshold!=s.actualThreshold||s.fast!=s.actualFast||s.grace!=s.actualGrace))return null;
  return s;
 }
 boolean fresh(){return(flags&1)!=0;}
 boolean waiting(){return(flags&8)!=0;}
 boolean acceptsAfter(TouchState old){return old==null||((revision-old.revision)&65535)<32768;}
 private String statusIssue(){
  if(result==1||((sensorFlags&2)!=0))return "正在应用 · 等待电容芯片确认";
  if(result==4)return "设置确认超时，请检查触摸板连接";
  if(result==3)return "参数无效，请重新选择";
  if(!fresh()||result==5)return "设备离线或尚未确认触摸状态";
  if((sensorFlags&4)!=0)return "电容转换档位校验失败";
  if((sensorFlags&1)==0)return "触摸板尚未就绪";
  return null;
 }
 private String saveStatus(){return result==6?" · 保存失败":(flags&4)!=0?" · 已保存":"";}
 String thresholdStatus(){
  String issue=statusIssue();
  return issue!=null?issue:"当前生效 "+actualThreshold+saveStatus();
 }
 String status(){
  String issue=statusIssue();
  return issue!=null?issue:"已生效 · DECIM "+(actualFast==1?64:128)+" · "+
    (actualGrace==0?"立即取音":"换品等待上限 "+actualGrace+" ms")+saveStatus();
 }
}
