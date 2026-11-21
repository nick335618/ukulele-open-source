#pragma once
#include <stdint.h>
// Musical-time envelope: pause freezes it and tempo changes preserve position.
struct GuideEnvelope {
  uint32_t start=0,end=0;
  uint8_t peak=0;
  static uint8_t smooth(uint32_t x,uint32_t span){
    if(!span||x>=span)return 255;
    uint32_t t=x*255u/span;
    return uint8_t(t*t*(765u-2u*t)/65025u);
  }
  uint8_t at(uint32_t pos,uint16_t rate,bool soft)const{
    if(!peak||pos<start||pos>=end)return 0;
    uint32_t length=end-start;
    if(!soft||uint64_t(length)*1000u/(rate?rate:1000u)<100u)return peak; // short notes stay legible
    uint32_t attack=length/5u,release=length/3u;
    uint32_t aCap=uint32_t(rate)*80u/1000u,rCap=uint32_t(rate)*180u/1000u;
    if(attack>aCap)attack=aCap;if(release>rCap)release=rCap;
    uint8_t factor=255;
    if(pos-start<attack)factor=smooth(pos-start,attack);
    if(end-pos<release){uint8_t v=smooth(end-pos,release);if(v<factor)factor=v;}
    return uint8_t(uint32_t(peak)*factor/255u);
  }
};
