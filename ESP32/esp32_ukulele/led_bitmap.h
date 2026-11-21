#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
// Profile v2: 72 * [brightness, breathing | rate<<1 | phase<<3 | pulse<<7].
// G/C/E/A columns, upper-to-lower 18 LED rows. No audio or touch memory used.
struct LedBitmap {
 uint8_t data[144]={0};uint8_t level=180;
 static uint16_t crc(const uint8_t* p,size_t n){uint16_t c=65535;while(n--){c^=uint16_t(*p++)<<8;for(int j=0;j<8;j++)c=(c<<1)^((c&32768)?0x1021:0);}return c;}
 uint16_t crc()const{return crc(data,144);}
 bool valid()const{return true;}
 bool hasPulse()const{for(int i=1;i<144;i+=2)if(data[i]&128)return true;return false;}
 bool supported(uint8_t caps)const{return (caps&7)==7&&(!hasPulse()||(caps&8));}
 bool operator==(const LedBitmap& b)const{return level==b.level&&!memcmp(data,b.data,144);}
 bool operator!=(const LedBitmap& b)const{return !(*this==b);}
 static int hex(char c){return c>='0'&&c<='9'?c-'0':c>='A'&&c<='F'?c-'A'+10:c>='a'&&c<='f'?c-'a'+10:-1;}
 static int hx(const char* p){int a=hex(p[0]),b=hex(p[1]);return a<0||b<0?-1:(a*16+b);}
 static int b64(char c){const char* a="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";const char* p=strchr(a,c);return c&&p?int(p-a):-1;}
 void encode(uint8_t out[148])const{out[0]=2;out[1]=level;memcpy(out+2,data,144);uint16_t c=crc(out,146);out[146]=c;out[147]=c>>8;}
 bool decode(const uint8_t* p,size_t n){
  if(n==13&&p[0]==1&&crc(p,11)==uint16_t(p[11]|(p[12]<<8))){level=p[1];memset(data,0,144);for(int i=0;i<72;i++)data[2*i]=(p[2+i/8]&(1<<(i%8)))?255:0;return true;}
  if(n!=148||p[0]!=2||crc(p,146)!=uint16_t(p[146]|(p[147]<<8)))return false;
  LedBitmap b;b.level=p[1];memcpy(b.data,p+2,144);if(!b.valid())return false;*this=b;return true;
 }
};
struct LedProfileAssembler {
 LedBitmap draft;uint8_t cursor=0,ticket=0;uint16_t expected=0;uint32_t started=0;bool receiving=false;
 // 1 accepted fragment, 2 complete valid profile, -1 rejected, 0 unrelated.
 int accept(const char* p,uint32_t now){
  if(!p)return 0;size_t n=strlen(p);
  if(n==12&&!strncmp(p,"LPB:",4)){
   int l=LedBitmap::hx(p+4),t=LedBitmap::hx(p+6),a=LedBitmap::hx(p+8),b=LedBitmap::hx(p+10);
   if(l<0||t<=0||a<0||b<0)return -1;
   draft=LedBitmap();draft.level=l;ticket=t;expected=(a<<8)|b;cursor=0;started=now;receiving=true;return 1;
  }
  if(!strncmp(p,"LP2:",4)){
   if(n!=20||!receiving||now-started>6000||LedBitmap::hx(p+18)!=ticket)return -1;
   int off=LedBitmap::hx(p+16);if(off<0||off>135||off%9)return -1;uint8_t raw[9];
   for(int i=0;i<3;i++){int a=LedBitmap::b64(p[4+i*4]),b=LedBitmap::b64(p[5+i*4]),c=LedBitmap::b64(p[6+i*4]),d=LedBitmap::b64(p[7+i*4]);if(a<0||b<0||c<0||d<0)return -1;raw[i*3]=(a<<2)|(b>>4);raw[i*3+1]=(b<<4)|(c>>2);raw[i*3+2]=(c<<6)|d;}
   if(off+9==cursor)return memcmp(draft.data+off,raw,9)?-1:1;
   if(off!=cursor)return -1;memcpy(draft.data+off,raw,9);cursor+=9;return 1;
  }
  if(!strncmp(p,"LPC:",4)){
   if(n!=6||!receiving||now-started>6000||LedBitmap::hx(p+4)!=ticket||cursor!=144||!draft.valid()||draft.crc()!=expected)return -1;
   receiving=false;return 2;
  }
  return 0;
 }
};
struct LedBitmapControl {
 LedBitmap desired,actual,saved,confirmed;LedProfileAssembler assembler;
 uint16_t token=0,activeCrc=0,generation=0;uint8_t client=0,result=0,mode=255,flags=0,cursor=0,error=0,stage=0,offset=0,caps=0;
 bool known=false,waiting=false,needStore=false,hasSaved=false,actualKnown=false,sent=false;
 uint32_t readMs=0,start=0,queryMs=0,sendMs=0;
 void cancel(){if(waiting){waiting=false;result=6;}else if(assembler.receiving){client=assembler.ticket;result=6;}assembler.receiving=false;}
 // Persist the last FPGA-confirmed snapshot, never a newer in-flight draft.
 void finishStore(bool ok){
  needStore=false;
  if(ok){saved=confirmed;hasSaved=true;}
  else if(!waiting&&desired==confirmed&&result==2)result=7;
 }
 uint16_t nextToken(){++token;if(!token)++token;return token;}
 bool fresh(uint32_t now)const{return known&&uint32_t(now-readMs)<1600;}
 bool captured()const{return known&&actualKnown&&mode==8&&(flags&32)&&actual==desired;}
 bool request(const LedBitmap& b,uint8_t ticket,uint32_t now,bool online,bool awake,bool busy){
  if(busy||waiting){if(!waiting){client=ticket;result=6;}return false;}
  client=ticket;if(!online||!awake){result=5;return false;}if(!b.valid()){result=3;return false;}
  desired=b;result=1;waiting=true;start=now;queryMs=sendMs=now-1000;stage=1;offset=0;sent=false;nextToken();return true;
 }
 void observe(uint8_t e,uint8_t cur,uint8_t level,uint8_t m,uint8_t f,uint16_t crc,uint16_t active,uint16_t gen,uint8_t capabilities,uint16_t echo,uint32_t now){
  mode=m;flags=f;cursor=cur;error=e;activeCrc=crc;generation=gen;caps=capabilities;known=true;readMs=now;
  actualKnown=(f&16)&&((crc==desired.crc())||(hasSaved&&crc==saved.crc()));
  if(actualKnown){actual=(crc==desired.crc())?desired:saved;actual.level=level;}
  if(!waiting||echo!=token||!sent)return;
  if(stage==1 && (f&4)&&cur==0){stage=2;offset=0;sent=false;}
  else if(stage==2 && cur==offset+3){offset+=3;stage=offset==144?3:2;sent=false;}
  else if(stage>=3&&active==token&&crc==desired.crc()&&captured()){
   confirmed=actual;waiting=false;result=2;needStore=true;
  }
 }
 template<class Send>void tick(uint32_t now,bool online,bool awake,bool busy,Send send){
  if(waiting){
   if(!online||!awake||busy){waiting=false;result=busy?6:5;return;}
   if(uint32_t(now-start)>12000){waiting=false;result=4;return;}
   if(!sent||uint32_t(now-sendMs)>=250){
    uint8_t idx=stage==1?desired.level:stage==2?offset:144;uint16_t c=desired.crc();
    uint8_t a=stage==2?desired.data[offset]:stage==3?uint8_t(c):0;
    uint8_t b=stage==2?desired.data[offset+1]:stage==3?uint8_t(c>>8):0;
    uint8_t d=stage==2?desired.data[offset+2]:0;
    if(send(stage,idx,a,b,d,token)){sent=true;sendMs=now;}
   }else if(uint32_t(now-queryMs)>=20){if(send(0,0,0,0,0,token))queryMs=now;}
  }else if(online&&uint32_t(now-queryMs)>=600){if(send(0,0,0,0,0,token))queryMs=now;}
 }
};
