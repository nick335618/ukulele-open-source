#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
// FPGA-owned FFT results. ESP32 validates/reassembles and forwards only.
// Never concatenate sample blocks and never auto-normalise spectrum frames.
struct AudioAnalysis {
 uint8_t bins[2048]={0},pcm[512]={0},xy[512]={0},meta[14]={0};
 uint16_t seq=0,next=0,sum=0;uint8_t phase=0;
 bool active=false,complete=false,scopeOnly=false;
 static uint16_t u16(const uint8_t*p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
 void reset(){active=complete=false;next=0;phase=0;sum=0;}
 bool accept(const uint8_t*p,size_t n,uint16_t requested){
  if(n!=20||(p[0]!=0xA1&&p[0]!=0xA3)||p[1]>4||u16(p+2)!=requested)return false;
  uint16_t off=u16(p+4);
  if(p[1]==0){
   if(off||u16(p+6)!=(p[0]==0xA3?256:4096)||p[8]!=0x80||p[9]!=0xF0||p[10]!=0xFA||p[11]!=2||
      u16(p+12)!=1024||u16(p+14)>2047||p[16]!=1||p[17]!=2||p[18]!=3||p[19]!=32)return false;
   reset();memcpy(meta,p+6,14);seq=requested;scopeOnly=p[0]==0xA3;active=true;phase=scopeOnly?2:1;return true;
  }
  if(!active||p[0]!=(scopeOnly?0xA3:0xA1)||p[1]!=phase||off!=next)return false;
  if(phase==3){
   if(off||u16(p+6)!=sum)return false;
   for(int i=8;i<20;i++)if(p[i])return false;
   active=false;complete=true;return true;
  }
  const unsigned size=phase==1?2048:512;
  uint8_t*dst=phase==1?bins:(phase==2?pcm:xy);
  for(unsigned i=0;i<14;i++){
   if(off+i<size){if(phase==1 && p[6+i]>192)return false;dst[off+i]=p[6+i];sum+=p[6+i];}
   else if(p[6+i])return false;
  }
  next+=14;if(next>=size){next=0;phase=phase==1?2:(phase==2?4:3);}
  return true;
 }
 // 0 header + 147 spectrum + 37 PCM + 1 end = 223 default-MTU packets.
 void packet(unsigned ordinal,uint8_t*p)const{
  memset(p,0,20);p[0]=scopeOnly?0xA3:0xA1;p[2]=seq;p[3]=seq>>8;
  if(!ordinal){memcpy(p+6,meta,14);return;}
  if(ordinal==(scopeOnly?75:222)){p[1]=3;p[6]=sum;p[7]=sum>>8;return;}
  if(scopeOnly)ordinal+=147;
  unsigned off,size;const uint8_t*src;
  if(ordinal<=147){p[1]=1;off=(ordinal-1)*14;size=2048;src=bins;}
  else if(ordinal<185){p[1]=2;off=(ordinal-148)*14;size=512;src=pcm;}
  else {p[1]=4;off=(ordinal-185)*14;size=512;src=xy;}
  p[4]=off;p[5]=off>>8;for(unsigned i=0;i<14 && off+i<size;i++)p[6+i]=src[off+i];
 }
};
