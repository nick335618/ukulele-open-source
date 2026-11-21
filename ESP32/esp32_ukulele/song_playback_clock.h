#pragma once
#include <stdint.h>
#include <stddef.h>

// Musical time is in microseconds. A wall millisecond adds ratePermille
// musical microseconds: fractional speed never rounds each event separately.
class SongPlaybackClock {
 public:
  static bool validRate(unsigned rate){return rate>=250 && rate<=2000 && rate%250==0;}
  void start(uint32_t now,uint16_t rate){units_=0;last_=now;rate_=rate;running_=true;paused_=false;}
  void advance(uint32_t now){
    uint32_t dt=now-last_;
    // Reject an accidentally stale loop timestamp; normal millis wrap works.
    if(dt>0x7FFFFFFFu)return;
    if(running_ && !paused_)units_+=(uint64_t)dt*rate_;
    last_=now;
  }
  bool rate(uint32_t now,uint16_t value){if(!validRate(value))return false;advance(now);rate_=value;return true;}
  void pause(uint32_t now){advance(now);if(running_)paused_=true;}
  void resume(uint32_t now){last_=now;if(running_)paused_=false;}
  void stop(uint32_t now){advance(now);running_=paused_=false;}
  uint32_t position()const{return (uint32_t)(units_/1000);}
  uint16_t rate()const{return rate_;}
  bool paused()const{return paused_;}
 private:
  uint64_t units_=0;uint32_t last_=0;uint16_t rate_=1000;
  bool running_=false,paused_=false;
};

struct SongPlaybackCommand {
 uint8_t op=0;uint16_t rate=1000,token=0;
 static bool parse(const char*s,SongPlaybackCommand&c){
  if(!s || s[0]!='S'||s[1]!='P'||s[2]!='1'||s[3]!=':'||s[4]<'0'||s[4]>'5'||s[5]!=',')return false;
  c.op=s[4]-'0';unsigned i=6,rate=0,token=0,digits=0;
  while(s[i]>='0'&&s[i]<='9'){rate=rate*10+s[i++]-'0';if(++digits>4||rate>2000)return false;}
  if(!digits||s[i++]!=','||!SongPlaybackClock::validRate(rate))return false;
  digits=0;
  while(s[i]>='0'&&s[i]<='9'){token=token*10+s[i++]-'0';if(++digits>5||token>65535)return false;}
  if(!digits||s[i] || (!token&&c.op!=4))return false;
  c.rate=rate;c.token=token;return true;
 }
};
