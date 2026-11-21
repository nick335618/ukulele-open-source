#pragma once
#include <stdint.h>
#include <string.h>
#include "fx_control.h"
struct ChainConfig {
 bool enabled=false;uint8_t mask=0,amount[4]={64,48,48,64},target=0;
 bool valid()const{return mask<=15&&amount[0]<=127&&amount[1]<=96&&amount[2]<=96&&amount[3]<=127&&target<=5;}
 bool operator==(const ChainConfig& b)const{return enabled==b.enabled&&(!enabled||(mask==b.mask&&target==b.target&&!memcmp(amount,b.amount,4)));}
 bool operator!=(const ChainConfig& b)const{return !(*this==b);}
 void encode(uint8_t b[11])const{b[0]=1;b[1]=enabled;b[2]=mask;memcpy(b+3,amount,4);b[7]=target;b[8]=0;uint16_t c=FxConfig::crc(b,9);b[9]=c;b[10]=c>>8;}
 bool decode(const uint8_t*b,unsigned n){if(n!=11||b[0]!=1||b[1]>1||b[8]||FxConfig::crc(b,9)!=(uint16_t(b[9])|(uint16_t(b[10])<<8)))return false;
   ChainConfig c;c.enabled=b[1];c.mask=b[2];memcpy(c.amount,b+3,4);c.target=b[7];if(!c.valid())return false;*this=c;return true;}
};
inline bool parseChainCommand(const char* s,ChainConfig& c,uint16_t& ticket){
 if(!s||strlen(s)!=19||strncmp(s,"XC:",3))return false;uint8_t b[8]={0};
 for(unsigned i=0;i<16;++i){char x=s[3+i];int v=x>='0'&&x<='9'?x-'0':x>='A'&&x<='F'?x-'A'+10:-1;if(v<0)return false;b[i/2]=(b[i/2]<<4)|v;}
 if(b[0]>15&&b[0]!=254)return false;c.enabled=b[0]!=254;c.mask=c.enabled?b[0]:0;memcpy(c.amount,b+1,4);c.target=b[5];ticket=(uint16_t(b[6])<<8)|b[7];return c.valid()&&ticket>0&&ticket<=32767;
}
class ChainControl {
 public:
 ChainConfig desired,saved,actual;bool known=false,waiting=false;uint8_t flags=0,actualMask=0,armed=0,result=0;uint16_t client=0;uint32_t observed=0;
 void (*send)(uint8_t,const ChainConfig&,uint8_t)=nullptr;bool (*store)(const ChainConfig&)=nullptr;
 void begin(const ChainConfig& c){desired=saved=c.valid()?c:ChainConfig();}
 bool fresh(uint32_t now)const{return known&&uint32_t(now-observed)<1800;}
 bool applied()const{return known&&actual==desired&&!(flags&8)&&(!desired.enabled||(flags&6)==6);}
 bool request(const ChainConfig& c,uint16_t id,uint32_t now,bool online){client=id;if(!c.valid()){result=3;return false;}if(!online){result=5;return false;}
  desired=c;manual_=true;startedRequest_=now;waiting=false;tries_=0;next_=now;result=1;forceSet_=c.enabled&&c.target==1;return true;}
 void observe(const ChainConfig& c,uint8_t mask,uint8_t f,uint8_t a,uint8_t token,uint32_t now){
  if(!waiting||token!=token_||!c.valid()||mask>15||f>15||a>1)return;waiting=false;known=true;actual=c;actualMask=mask;flags=f;armed=a;observed=now;tries_=0;next_=now+800;
  if(sent_==1)forceSet_=false;
  if(applied()&&!forceSet_){if(manual_){result=2;if(saved!=desired){if(store&&store(desired))saved=desired;else result=6;}}else if(!result||result==4||result==5)result=2;manual_=false;}
  else next_=now+100;
 }
 void tick(uint32_t now,bool online){
  if(!online){if(waiting||manual_)result=5;known=false;waiting=false;flags=0;manual_=false;forceSet_=false;desired=saved;tries_=0;next_=now;return;}
  if(manual_&&uint32_t(now-startedRequest_)>=5000){result=4;waiting=false;known=false;manual_=false;forceSet_=false;desired=saved;tries_=0;next_=now+3000;return;}
  if(waiting){if(uint32_t(now-sentAt_)<600)return;waiting=false;if(++tries_>=3){result=4;known=false;manual_=false;forceSet_=false;desired=saved;tries_=0;next_=now+3000;return;}}
  if(int32_t(now-next_)<0||!send)return;sent_=known&&(actual!=desired||forceSet_)?1:0;waiting=true;sentAt_=now;++token_;send(sent_,desired,token_);
 }
 private:bool manual_=false,forceSet_=false;uint8_t token_=0,tries_=0,sent_=0;uint32_t sentAt_=0,startedRequest_=0,next_=0;
};
