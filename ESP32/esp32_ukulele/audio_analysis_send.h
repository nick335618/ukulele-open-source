#pragma once
#include "audio_analysis.h"
struct AudioAnalysisSender {
 unsigned ordinal=223,total=223,length=0;uint32_t lastMs=0;bool compact=false,selected=false,selectedScope=false;
 uint8_t encoded[3072];uint16_t checksum=0;
 bool done()const{return ordinal>=total;}
 void reset(){ordinal=total=223;}
 static uint16_t crc(uint16_t v,uint8_t x){v^=uint16_t(x)<<8;for(int b=0;b<8;b++)v=(v&0x8000)?(v<<1)^0x1021:v<<1;return v;}
 void begin(const AudioAnalysis* frame=nullptr,bool useCompact=false,int selection=-1){
  ordinal=0;selected=frame&&selection>=0;
  selectedScope=selected&&selection!=0;
  compact=selected||(frame&&useCompact&&!frame->scopeOnly);total=frame&&frame->scopeOnly?76:223;
  if(!compact)return;length=0;
  if(!selected || selection==0){
   for(unsigned i=0;i<2048;){unsigned run=0;while(i+run<2048&&frame->bins[i+run]==0&&run<255)++run;
    if(run>=3){encoded[length++]=255;encoded[length++]=run;i+=run;}else encoded[length++]=frame->bins[i++];}
  }
  if(!selected || selection!=0){memcpy(encoded+length,frame->pcm,512);length+=512;}
  if(!selected){memcpy(encoded+length,frame->xy,512);length+=512;}
  checksum=0xffff;for(unsigned i=0;i<length;i++)checksum=crc(checksum,encoded[i]);total=2+(length+13)/14;
 }
 void packet(const AudioAnalysis& frame,unsigned index,uint8_t* p)const{
  if(!compact){frame.packet(index,p);return;}
  memset(p,0,20);p[0]=selected?0xa4:0xa2;p[2]=frame.seq;p[3]=frame.seq>>8;
  if(!index){
   memcpy(p+6,frame.meta,14);
   if(selectedScope){p[6]=0;p[7]=1;p[14]=p[15]=0;}
   return;
  }
  if(index==total-1){p[1]=3;p[6]=length;p[7]=length>>8;p[8]=checksum;p[9]=checksum>>8;return;}
  p[1]=1;unsigned at=(index-1)*14;p[4]=at;p[5]=at>>8;for(unsigned j=0;j<14&&at+j<length;j++)p[6+j]=encoded[at+j];
 }
 template<class Notify> bool pump(const AudioAnalysis&frame,size_t capacity,uint32_t now,Notify notify){
  if(done()||uint32_t(now-lastMs)<1)return false;lastMs=now;
  size_t count=capacity<1?1:(capacity>12?12:capacity);if(count>total-ordinal)count=total-ordinal;
  uint8_t bytes[240];for(size_t i=0;i<count;i++)packet(frame,ordinal+i,bytes+i*20);
  if(!notify(bytes,count*20))return false;ordinal+=count;return true;
 }
};
