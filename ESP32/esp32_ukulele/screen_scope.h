#pragma once
#include <stdio.h>
#include <stdlib.h>
#include "audio_analysis.h"

// 320x240 TJC. All conversions happen outside the FPGA audio path.
class ScreenScope {
 public:
  uint8_t mode=3;
  uint32_t heartbeat=0;
  bool active(uint32_t now) const {return mode<3 && now-heartbeat<1800;}
  void stop(){mode=3;cursor=limit=0;}
  void select(uint8_t next,uint32_t now){
    if(next!=mode){cursor=limit=0;mode=next;}
    heartbeat=now;
  }
  void load(const AudioAnalysis &frame,uint32_t now){
    if(cursor<limit || now-shownAt<155 || (mode==0 && frame.scopeOnly))return;
    shownAt=now;
    cursor=0;
    const unsigned count=mode==0?64:mode==1?96:72;
    limit=count+2;
    const int lag=16;
    for(unsigned i=0;i<count;i++){
      if(mode==0){
        // Peak hold over each screen column, preserving narrow partials.
        int peak=48;
        for(unsigned b=i*671/count;b<(i+1)*671/count;b++)
          if(frame.bins[b]>peak)peak=frame.bins[b];
        points[i]={uint16_t(25+i*271/(count-1)),uint16_t(189-(peak-48)*134/144)};
      }else if(mode==1){
        unsigned first=i*256/count,end=(i+1)*256/count;
        int value=pcm(frame,first);
        for(unsigned s=first+1;s<end;s++){
          int sample=pcm(frame,s);
          if(abs(sample)>abs(value))value=sample;
        }
        points[i]={uint16_t(25+i*271/(count-1)),scaleY(value)};
      }else{
        unsigned s=lag+i*(255-lag)/(count-1);
        points[i]={uint16_t(160+pcm(frame,s)*66/32768),scaleY(pcm(frame,s-lag))};
      }
    }
  }
  template<class Send> void pump(Send send){
    if(cursor>=limit)return;
    char command[48];
    if(!cursor)snprintf(command,sizeof(command),"fill 24,54,273,137,4355");
    else if(cursor==1)snprintf(command,sizeof(command),"line 25,122,296,122,12712");
    else if(cursor==2)snprintf(command,sizeof(command),"line 160,54,160,190,12712");
    else{
      const auto &a=points[cursor-3],&b=points[cursor-2];
      snprintf(command,sizeof(command),"line %u,%u,%u,%u,%u",a.x,a.y,b.x,b.y,
               mode==0?65184:mode==1?2047:32743);
    }
    if(send(command))++cursor;
  }
  bool drawing()const{return cursor<limit;}
 private:
  struct Point{uint16_t x,y;};
  Point points[96];
  unsigned cursor=0,limit=0;
  uint32_t shownAt=0;
  static int pcm(const AudioAnalysis &f,unsigned index){return int16_t(AudioAnalysis::u16(f.pcm+2*index));}
  static uint16_t scaleY(int value){return uint16_t(122-value*66/32768);}
};
