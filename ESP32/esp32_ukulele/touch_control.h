#pragma once
#include <stdint.h>
struct TouchConfig {
 uint16_t threshold=360; uint8_t fast=0,grace=0;
 bool valid()const{return threshold>=120 && threshold<=2400 && fast<=1 && grace<=60;}
 bool operator==(const TouchConfig& b)const{return threshold==b.threshold&&fast==b.fast&&grace==b.grace;}
 bool operator!=(const TouchConfig& b)const{return !(*this==b);}
 static uint16_t crc(const uint8_t* p,unsigned n){uint16_t c=0xFFFF;while(n--){c^=uint16_t(*p++)<<8;for(unsigned j=0;j<8;++j)c=c&0x8000?(c<<1)^0x1021:c<<1;}return c;}
 void encode(uint8_t b[8])const{b[0]=1;b[1]=threshold;b[2]=threshold>>8;b[3]=fast;b[4]=grace;b[5]=0;uint16_t c=crc(b,6);b[6]=c;b[7]=c>>8;}
 bool decode(const uint8_t* b,unsigned n){if(n!=8||b[0]!=1||b[5]!=0||crc(b,6)!=(uint16_t(b[6])|(uint16_t(b[7])<<8)))return false;
  TouchConfig t;t.threshold=uint16_t(b[1])|(uint16_t(b[2])<<8);t.fast=b[3];t.grace=b[4];if(!t.valid())return false;*this=t;return true;}
};
// Exact decimal grammar; reject signs, whitespace, overflow and trailing data.
// Longest legitimate command is TC:2400,1,60,65535 (18 bytes).
static inline bool parseTouchCommand(const char* text,TouchConfig& out,uint16_t& ticket){
 if(!text)return false;
 unsigned length=0;while(text[length]){if(++length>18)return false;}
 if(length<10||text[0]!='T'||text[1]!='C'||text[2]!=':')return false;
 const char* p=text+3;uint32_t values[4]={0,0,0,0};
 for(unsigned i=0;i<4;++i){
  if(*p<'0'||*p>'9')return false;
  do{unsigned digit=*p-'0';if(values[i]>(65535-digit)/10)return false;
     values[i]=values[i]*10+digit;++p;}while(*p>='0'&&*p<='9');
  if(i<3){if(*p++!=',')return false;}else if(*p)return false;
 }
 if(values[1]>1||values[2]>60)return false;
 TouchConfig next;next.threshold=values[0];next.fast=values[1];next.grace=values[2];
 if(!next.valid())return false;
 out=next;ticket=values[3];return true;
}
class TouchControl {
 public:
 TouchConfig desired,saved,actual;
 uint8_t result=0,sensorFlags=0; uint16_t client=0; bool known=false,waiting=false;
 uint32_t observed=0;
 void (*send)(uint8_t,const TouchConfig&,uint16_t)=nullptr;
 bool (*store)(const TouchConfig&)=nullptr;
 void begin(const TouchConfig& c){desired=saved=c.valid()?c:TouchConfig();}
 bool fresh(uint32_t now)const{return known&&uint32_t(now-observed)<1800;}
 bool applied()const{return known && (sensorFlags&7)==1 && actual==desired;}
 bool request(const TouchConfig& c,uint16_t id,uint32_t now,bool online){client=id;if(!c.valid()){result=3;return false;}if(!online){result=5;return false;}
  desired=c;manual_=true;requested_=now;waiting=false;tries_=0;next_=now;result=1;return true;}
 void observe(const TouchConfig& c,uint8_t requestedFast,uint16_t token,uint8_t flags,uint32_t now){
  if(!waiting||token!=token_||!c.valid()||requestedFast>1||(flags&~7))return;
  waiting=false;known=true;actual=c;sensorFlags=flags;observed=now;tries_=0;next_=now+((flags&2)?100:800);
  if(applied() && requestedFast==desired.fast){
   if(manual_){result=2;if(saved!=desired){if(store&&store(desired))saved=desired;else result=6;}}
   else if(result==0||result==5)result=2;
   manual_=false;
  }else next_=now+100;
 }
 void tick(uint32_t now,bool online){
  if(!online){if(waiting||manual_)result=5;waiting=false;known=false;sensorFlags=0;manual_=false;desired=saved;tries_=0;next_=now;return;}
  if(manual_&&uint32_t(now-requested_)>=4000){result=4;manual_=false;waiting=false;desired=saved;known=false;tries_=0;next_=now+3000;return;}
  if(waiting){if(uint32_t(now-started_)<600)return;waiting=false;if(++tries_>=3){result=4;manual_=false;known=false;desired=saved;tries_=0;next_=now+3000;return;}}
  if(int32_t(now-next_)<0||!send)return;
  ++token_;waiting=true;started_=now;
  send(known && actual==desired ? 0 : 1,desired,token_);
 }
 private:
 bool manual_=false;uint8_t tries_=0;uint16_t token_=0;uint32_t requested_=0,next_=0,started_=0;
};
