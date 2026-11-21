#pragma once
#include <stdint.h>
// Independent of sensor presets/audio parameters. One correlated transaction at a time.
class CapoControl {
 public:
  uint8_t desired=0,saved=0,actual=255,caps=0,result=0;
  uint16_t client=0;
  bool waiting=false,known=false;
  uint32_t observed=0;
  void (*send)(uint8_t,uint8_t)=nullptr;
  bool (*store)(uint8_t)=nullptr;
  void begin(uint8_t mask){desired=saved=mask<=12?mask:0;}
  bool fresh(uint32_t now)const{return known && uint32_t(now-observed)<1500;}
  bool request(uint8_t mask,uint16_t id,uint32_t now,bool online){
    client=id;
    if(mask>12){result=3;return false;}
    if(!online){result=5;return false;}
    desired=mask;manual_=true;requestStarted_=now;waiting=false;attempts_=0;next_=now;result=1;return true;
  }
  void observe(uint8_t mask,uint8_t token,uint8_t support,uint32_t now){
    if(!waiting || token!=token_ || mask>12 || support!=12)return;
    waiting=false;known=true;actual=mask;caps=support;observed=now;attempts_=0;next_=now+1000;
    if(sent_!=255 && actual==desired){
      if(manual_){result=2;
        if(saved!=desired){if(store && store(desired))saved=desired;else result=6;}
      }else if(result==0 || result==5)result=2;
      manual_=false;
    }
    if(actual!=desired)next_=now;
  }
  void tick(uint32_t now,bool online){
    if(!online){
      if(waiting || manual_)result=5;
      waiting=false;known=false;actual=255;caps=0;desired=saved;manual_=false;attempts_=0;next_=now;return;
    }
    if(manual_ && uint32_t(now-requestStarted_)>=2000){
      result=4;manual_=false;waiting=false;desired=saved;known=false;actual=255;caps=0;attempts_=0;next_=now+3000;return;
    }
    if(waiting){
      if(uint32_t(now-started_)<600)return;
      waiting=false;
      if(attempts_>=3){
        if(manual_ || !known)result=4;
        manual_=false;desired=saved;known=false;actual=255;caps=0;attempts_=0;next_=now+3000;return;
      }
      next_=now;
    }
    if(int32_t(now-next_)<0 || !send)return;
    sent_=(known && actual==desired && !manual_)?255:desired;
    ++token_;waiting=true;started_=now;++attempts_;send(sent_,token_);
  }
 private:
  bool manual_=false;
  uint8_t token_=0,sent_=255,attempts_=0;
  uint32_t started_=0,next_=0,requestStarted_=0;
};
