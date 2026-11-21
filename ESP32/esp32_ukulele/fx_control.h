#pragma once
#include <stdint.h>
struct FxConfig {
 uint8_t mode=0,strength=64;
 bool valid()const{return mode<=4&&strength<=((mode==2||mode==3)?96:127);}
 bool operator==(const FxConfig& b)const{return mode==b.mode&&strength==b.strength;}
 bool operator!=(const FxConfig& b)const{return !(*this==b);}
 static uint16_t crc(const uint8_t* p,unsigned n){uint16_t c=0xffff;while(n--){c^=uint16_t(*p++)<<8;for(unsigned j=0;j<8;++j)c=c&0x8000?(c<<1)^0x1021:c<<1;}return c;}
 void encode(uint8_t b[6])const{b[0]=3;b[1]=mode;b[2]=strength;b[3]=0;uint16_t c=crc(b,4);b[4]=c;b[5]=c>>8;}
 bool decode(const uint8_t* b,unsigned n){
  if(n!=6||(b[0]!=1&&b[0]!=2&&b[0]!=3)||b[3]||crc(b,4)!=(uint16_t(b[4])|(uint16_t(b[5])<<8)))return false;
  FxConfig c;c.mode=b[1];c.strength=b[2];
  if(b[0]<3){if(c.mode>(b[0]==1?2:4)||c.strength>3)return false;c.strength*=32;if(c.mode==1||c.mode==4)c.mode=0;}
  if(!c.valid())return false;*this=c;return true;
 }

};
inline bool parseFxCommand(const char* s,FxConfig& c,uint16_t& ticket){
 if(!s||s[0]!='F'||s[1]!='X'||s[2]!='3'||s[3]!=':')return false;
 const char* p=s+4;uint32_t v[3]={0,0,0};
 for(unsigned i=0;i<3;++i){if(*p<'0'||*p>'9')return false;unsigned digits=0;
  do{unsigned digit=*p-'0';if(++digits>5||v[i]>(65535-digit)/10)return false;v[i]=v[i]*10+digit;++p;}while(*p>='0'&&*p<='9');
  if(i<2){if(*p++!=',')return false;}else if(*p)return false;
 }if(v[0]>4||v[1]>127)return false;c.mode=v[0];c.strength=v[1];ticket=v[2];return c.valid();
}
class FxControl {
 public:
 FxConfig desired,saved,configured;
 uint8_t actual=255,gain=0,flags=0,result=0;uint16_t client=0;
 bool known=false,waiting=false;uint32_t observed=0;
 void (*send)(uint8_t,const FxConfig&,uint16_t)=nullptr;
 bool (*store)(const FxConfig&)=nullptr;
 void begin(const FxConfig& c){desired=saved=c.valid()?c:FxConfig();}
 bool fresh(uint32_t now)const{return known&&uint32_t(now-observed)<1800;}
 bool applied()const{return known&&configured==desired&&actual==desired.mode&&(flags&4)&&!(flags&8);}
 bool request(const FxConfig& c,uint16_t id,uint32_t now,bool online){client=id;if(!c.valid()){result=3;return false;}if(!online){result=5;return false;}
  desired=c;manual_=true;requested_=now;waiting=false;tries_=0;next_=now;result=1;return true;}
 void observe(const FxConfig& c,uint8_t mode,uint8_t level,uint8_t f,uint16_t token,uint32_t now){
  if(!waiting||token!=token_||!c.valid()||mode>4||level>((mode==2||mode==3)?96:127)||(f&~15))return;
  waiting=false;known=true;configured=c;actual=mode;gain=level;flags=f;observed=now;tries_=0;next_=now+800;
  if(applied()){
   if(manual_){result=2;if(saved!=desired){if(store&&store(desired))saved=desired;else result=6;}}
   else if(result==0||result==5||result==4)result=2;
   manual_=false;
  }else next_=now+100;
 }
 void tick(uint32_t now,bool online){
  if(!online){if(waiting||manual_)result=5;waiting=false;known=false;actual=255;flags=0;manual_=false;desired=saved;tries_=0;next_=now;return;}
  if(manual_&&uint32_t(now-requested_)>=3500){result=4;manual_=false;waiting=false;desired=saved;known=false;tries_=0;next_=now+3000;return;}
  if(waiting){if(uint32_t(now-started_)<600)return;waiting=false;if(++tries_>=3){result=4;manual_=false;known=false;actual=255;desired=saved;tries_=0;next_=now+3000;return;}}
  if(int32_t(now-next_)<0||!send)return;
  ++token_;waiting=true;started_=now;
  send(known&&configured==desired?0:1,desired,token_);
 }
 private:
 bool manual_=false;uint8_t tries_=0;uint16_t token_=0;uint32_t requested_=0,next_=0,started_=0;
};
