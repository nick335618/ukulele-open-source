package com.ukulele.monitor;
/** A7 v2 fragmented snapshot, 20-byte BLE packets at MTU23. Publish complete snapshots only. */
final class LedBitmapState {
 final int result,flags,mode,level,ticket,revision,caps;final byte[] mask;final LedProfile profile;
 private LedBitmapState(byte[] b,int rev){result=b[0]&255;flags=b[1]&255;mode=b[2]&255;level=b[3]&255;ticket=b[4]&255;caps=b[5]&255;revision=rev;byte[] d=java.util.Arrays.copyOfRange(b,12,156);profile=new LedProfile(d,level);mask=new byte[9];for(int i=0;i<72;i++)if(d[i*2]!=0)mask[i/8]|=1<<(i%8);}
 boolean fresh(){return (flags&3)==3&&(caps&7)==7;}boolean applied(){return fresh()&&(flags&4)!=0&&mode==8;}
 static int nextTicket(int previous,int echoed){int next=previous%255+1;return next==echoed?next%255+1:next;}
 boolean confirms(int request,LedProfile expected){return ticket==request&&(result==2||result==7)&&applied()&&expected!=null&&level==expected.level&&java.util.Arrays.equals(profile.data,expected.data);}
 boolean newer(LedBitmapState old){int n=old==null?1:(revision-old.revision)&65535;return old==null||(n>0&&n<32768);}
 String label(){if((flags&2)==0)return "等待配套固件返回灯阵状态";if(!fresh())return "FPGA离线或版本不匹配";switch(result){case 1:return "正在应用 · 等待FPGA确认";case 3:return "配置校验失败，请重试";case 4:return "应用超时，请重试";case 5:return "设备不可用，请连接并唤醒";case 6:return "请先停止曲谱灯光指引";case 7:return "已应用 · 设备保存失败";}if((flags&16)!=0)return "曲谱指引中 · 结束后恢复灯效";if(applied())return (flags&8)!=0?"自定义灯光已应用并保存":"已应用 · 正在保存";return "当前使用其他灯效";}
 static final class Assembler {
  private int revision=-1,parts;private long started;private final byte[] body=new byte[156];
  synchronized LedBitmapState accept(byte[] p,long now){
   if(p==null||p.length!=20||(p[0]&255)!=0xA7||p[1]!=2||p[5]!=12||(p[4]&255)>=12)return null;
   int x=0;for(byte b:p)x^=b&255;if(x!=0)return null;int r=(p[2]&255)|((p[3]&255)<<8),part=p[4]&255;
   if(r!=revision){if(revision>=0&&((r-revision)&65535)>=32768)return null;revision=r;parts=0;started=now;}
   if(now-started>3000){parts=0;started=now;}
   if((parts&(1<<part))!=0){for(int i=0;i<13;i++)if(body[part*13+i]!=p[6+i]){parts=0;return null;}}
   System.arraycopy(p,6,body,part*13,13);parts|=1<<part;if(parts!=4095)return null;
   if((body[0]&255)>7||(body[1]&128)!=0||((body[2]&255)>8&&(body[2]&255)!=255)||body[10]!=0||body[11]!=0)return null;
   try{LedBitmapState b=new LedBitmapState(body,revision);if((b.flags&64)!=0&&LedProfile.crc(b.profile.data)!=((body[6]&255)|((body[7]&255)<<8)))return null;return b;}catch(IllegalArgumentException e){return null;}
  }
  synchronized void clear(){revision=-1;parts=0;}
 }
}
