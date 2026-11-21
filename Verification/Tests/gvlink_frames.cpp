#include "gvlink.h"
#include <cassert>
#include <deque>
#include <vector>
#include <cstdio>
HardwareSerial Serial,Serial1;
static std::deque<uint8_t> rx;
static std::vector<uint8_t> tx;
uint32_t millis(){return 1234;}
void delay(uint32_t){}
int FpgaSpi::read(){if(rx.empty())return -1;int b=rx.front();rx.pop_front();return b;}
void FpgaSpi::sendByte(uint8_t b){tx.push_back(b);}
int FpgaSpi::txPending()const{return 0;}
static void frame(GvLink& g,uint8_t cmd,const std::vector<uint8_t>& pay,bool valid=true){
 uint8_t crc=cmd^uint8_t(pay.size());rx.push_back(0xaa);rx.push_back(cmd);rx.push_back(uint8_t(pay.size()));
 for(auto b:pay){rx.push_back(b);crc^=b;}rx.push_back(crc^uint8_t(valid?0:1));g.poll();
}
int main(){
 FpgaSpi spi;GvLink g;g.begin(&spi);
 std::vector<uint8_t> p(30);p[17]=0xf2;
 for(int i=0;i<4;i++){p[18+i]=i;p[22+i]=60+i;p[26+i]=i+1;}
 frame(g,0x84,p);assert(g.optel.valid&&g.optel.feedbackVersion==2&&g.optel.automatic==15);
 for(int i=0;i<4;i++)assert(g.optel.frets[i]==i&&g.optel.midi[i]==60+i&&g.optel.counts[i]==i+1);
 auto seq=g.optel.seq;p[22]=99;frame(g,0x84,p,false);assert(g.optel.seq==seq&&g.optel.midi[0]==60);
 p[17]=1;frame(g,0x84,p);assert(g.optel.feedbackVersion==1&&g.optel.automatic==0&&g.optel.midi[0]==99);
 p.resize(17);frame(g,0x84,p);assert(!g.optel.noteFeedback&&g.optel.automatic==0);
 std::vector<uint8_t> d={2,96,17,0x87,0x40,0x9c,0,0x7d,0x40,0x1f,0,1,7,0x71,0,0x4d,1,120,0,3};
 frame(g,0x8e,d);assert(g.padDiag.version==2&&g.padDiag.channel==96&&g.padDiag.raw==40000&&g.padDiag.delta==8000);
 assert(g.padDiag.gesture==7&&g.padDiag.vibratoFlags==0x71&&g.padDiag.amplitude==333&&g.padDiag.threshold==120&&g.padDiag.model==3);
 seq=g.padDiag.seq;d[1]=97;frame(g,0x8e,d);assert(g.padDiag.seq==seq);
 d[1]=0;d[14]=4;frame(g,0x8e,d);assert(g.padDiag.seq==seq);
 d.resize(12);d[0]=1;frame(g,0x8e,d);assert(g.padDiag.version==1&&g.padDiag.gesture==0&&g.padDiag.amplitude==0);
 puts("PASS real ESP32 GvLink parser v1/v2 optical feedback, CRC rejection, 20-byte diagnostics, aux96/invalid97, legacy compatibility");
}
