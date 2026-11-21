#pragma once
#include "power_protocol.h"
// Hardware-acknowledged power control; activity never includes telemetry/polls.
class PowerManager {
 public:
  enum State {RUN=0,DIM=1,NOTICE=2,ENTERING=3,SLEEP=4,WAKING=5,FAULT=6};
  typedef void(*Send)(uint8_t,uint16_t,uint16_t);
  typedef void(*Changed)();
  Send send;Changed changed;
  bool (*sensorsReady)(bool, uint32_t)=nullptr;
  bool screenRequired=true;
  uint8_t state,result,origin,reason,countdown;uint16_t clientSeq,revision,epoch,id,done,fault;
  uint32_t timeoutMs,dimMs;bool known,wantedSleep,waiting,fpgaAcked,screenAwake;
  PowerManager():send(0),changed(0),state(RUN),result(0),origin(0),reason(0),countdown(0),
    clientSeq(0),revision(0),epoch(1),id(0),done(0),fault(0),timeoutMs(300000),dimMs(90000),
    known(false),wantedSleep(false),waiting(false),fpgaAcked(false),screenAwake(true),lastActivity(0),started(0),lastSend(0),lastPoll(0),lastObserved(0),leaseUntil(0),firstReply(true),leaseActive(false){}
  bool playable()const{return state<=NOTICE;}
  bool supported()const{return known;}
  void begin(uint32_t now,uint16_t session){epoch=session?session:1;lastActivity=now;}
  void activity(uint32_t now){if(playable()){lastActivity=now;if(state!=RUN){state=RUN;countdown=0;notify();}}}
  void practice(uint32_t now,bool active){leaseActive=active;leaseUntil=now+30000;activity(now);}
  bool practiceActive(uint32_t now)const{return leaseActive && (int32_t)(leaseUntil-now)>0;}
  bool request(bool sleep,uint8_t src,uint16_t seq,uint32_t now,bool online,bool busy=false){
    origin=src;clientSeq=seq;reason=sleep?(src==0?1:2):3;
    if(sleep && busy){result=6;notify();return false;}
    if(!known){result=3;notify();return false;}
    wantedSleep=sleep;
    if(!online){state=FAULT;result=5;waiting=false;notify();return false;}
    if((sleep && state==SLEEP && !screenAwake)||(!sleep && state==RUN && screenAwake)){result=2;notify();return true;}
    if(waiting && ((sleep && state==ENTERING)||(!sleep && state==WAKING))){notify();return true;}
    state=sleep?ENTERING:WAKING;countdown=0;result=1;waiting=true;fpgaAcked=false;started=now;lastSend=now;
    if(++id==0)++id;
    wakeCommandSent=false;
    if(sleep){if(send)send(1,id,epoch);}
    else tryWake(now);
    notify();return true;
  }
  void reject(uint8_t src,uint16_t seq,uint8_t code){origin=src;clientSeq=seq;result=code;notify();}
  void configure(uint8_t minutes,uint32_t now,uint8_t src,uint16_t seq){timeoutMs=(uint32_t)minutes*60000;origin=src;clientSeq=seq;result=2;activity(now);notify();}
  void cancel(uint32_t now){if(state==NOTICE || state==DIM){state=RUN;countdown=0;result=2;activity(now);notify();}}
  void continueUsing(uint8_t src,uint16_t seq,uint32_t now,bool online){
    // Older notice pages send Cancel even after entry. That intent is always
    // usable, but never completes wake without the two hardware confirmations.
    if(!playable()){
      if(waiting && !wantedSleep && origin==2){notify();return;}
      request(false,src,seq,now,online);
    }else{origin=src;clientSeq=seq;result=2;activity(now);notify();}
  }
  void observe(const FpgaPowerState&p,uint32_t now){
    if(!p.valid || p.epoch!=epoch || p.id!=id)return;
    bool masksChanged=done!=p.done || fault!=p.fault;
    known=(p.caps&3)==3;lastObserved=now;done=p.done;fault=p.fault;
    if(!known)return;
    if(firstReply){
      firstReply=false;
      if(p.state==4){state=ENTERING;wantedSleep=true;result=1;waiting=true;fpgaAcked=true;started=now;notify();return;}
      if(p.state==6){state=FAULT;result=p.result;notify();return;}
      notify();
    }
    if(waiting){
      if(p.state==(wantedSleep?4:0) && p.result==2){
        fpgaAcked=true;
        if(canFinish(now))finish(now);else notify();
      }else if(p.state==6 || p.result==3 || p.result==4){state=FAULT;result=p.result;waiting=false;notify();}
    }else if(state==FAULT && result==5 && !wantedSleep && p.state==0 && screenAwake){
      state=RUN;result=2;lastActivity=now;notify();
    }else if(wantedSleep && ((state==SLEEP && p.state==0) || (state==FAULT && result==5))){
      // FPGA-only reconfiguration must not silently leave a dark but active instrument.
      state=RUN;request(true,0,clientSeq,now,true);
    }else if(masksChanged)notify();
  }
  void screenObserved(bool awake,uint32_t now){
    bool wasAwake=screenAwake;screenAwake=awake;
    if(waiting && !wantedSleep && awake && !wasAwake)tryWake(now);
    if(waiting && canFinish(now))finish(now);
  }
  void tick(uint32_t now,bool online,bool busy){
    if(leaseActive && (int32_t)(now-leaseUntil)>=0)leaseActive=false;
    if(known && !online && !waiting && state!=FAULT){state=FAULT;result=5;notify();}
    if(waiting){
      if(canFinish(now))finish(now);
      else if(now-started>=5000){waiting=false;state=FAULT;result=online?4:5;notify();}
      else if(!wantedSleep)tryWake(now);
      else if(!fpgaAcked && now-lastSend>=500){lastSend=now;if(send)send(1,id,epoch);}
    }
    if(!waiting && now-lastPoll>=1000){lastPoll=now;if(++id==0)++id;if(send)send(0,id,epoch);}
    if(!known || !online || waiting || !playable())return;
    if(busy || practiceActive(now)){activity(now);return;}
    uint32_t idle=now-lastActivity;
    if(timeoutMs && idle>=timeoutMs){request(true,0,0,now,true);return;}
    if(timeoutMs && timeoutMs>=10000 && idle>=timeoutMs-10000){
      uint8_t left=(uint8_t)((timeoutMs-idle+999)/1000);
      if(state!=NOTICE || countdown!=left){state=NOTICE;countdown=left;notify();}
    }else if(dimMs && idle>=dimMs && state==RUN){state=DIM;notify();}
  }
 private:
  uint32_t lastActivity,started,lastSend,lastPoll,lastObserved,leaseUntil;bool firstReply,leaseActive;
  bool wakeCommandSent=false;
  void tryWake(uint32_t now){
    if(fpgaAcked || (wakeCommandSent && now-lastSend<500))return;
    if(sensorsReady && !sensorsReady(true,now))return;
    if(screenRequired && !screenAwake)return;
    lastSend=now;wakeCommandSent=true;if(send)send(2,id,epoch);
  }
  bool canFinish(uint32_t now){
    return fpgaAcked && (!sensorsReady || sensorsReady(!wantedSleep,now)) &&
      (!screenRequired || screenAwake!=wantedSleep);
  }
  void finish(uint32_t now){state=wantedSleep?SLEEP:RUN;waiting=false;result=2;countdown=0;lastActivity=now;notify();}
  void notify(){++revision;if(changed)changed();}
};
