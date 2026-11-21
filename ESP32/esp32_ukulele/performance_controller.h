#pragma once
#include "performance_config.h"

// All operations use real, versioned FPGA snapshots and unique read tokens.
// Result:0 idle,1 pending,2 success,3 failure,4 timeout,5 offline,6 busy.
class PerformanceController {
 public:
 enum Operation {QUERY=1,PRESET=2,SAVE=3,LOAD=4,DEFAULT_TEMP=5,FACTORY_SAVE=6,MODE=7,PARAM=8,RECONCILE=9};
 typedef void (*Apply)(const PerformanceConfig&,bool);
 typedef void (*Request)(uint8_t);
 typedef bool (*Store)(const PerformanceConfig&);
 typedef bool (*LoadFn)(PerformanceConfig&,bool&);
 PerformanceController():known(false),waiting(false),result(0),operation(0),ticket(0),token(0),lastObserved(0),
  lastSent(0),started(0),lastPoll(0),persist(false),modeOnly(false),expected(false),silent(false),runningOp(0),runningTicket(0),apply(0),request(0),store(0),load(0){}
 PerformanceConfig desired,actual;
 bool known,waiting;uint8_t result,operation,ticket,token;
 void reject(Operation op,uint8_t seq,uint8_t status=3){operation=op;ticket=seq;result=status;}
 uint32_t lastObserved;
 void hooks(Apply a,Request q,Store s,LoadFn l){apply=a;request=q;store=s;load=l;}
 bool fresh(uint32_t now)const{return known && now-lastObserved<1500;}
 bool confirmed(uint32_t now)const{return fresh(now)&&actual.equals(desired)&&(!waiting||silent);}
 bool start(Operation op,const PerformanceConfig& proposed,uint8_t seq,uint32_t now,bool online,bool background=false){
  if(waiting && (persist || op==SAVE || op==LOAD || op==FACTORY_SAVE)){operation=op;ticket=seq;result=6;return false;}
  PerformanceConfig next=proposed;bool migration=false;
  if(op==LOAD && (!load || !load(next,migration))){operation=op;ticket=seq;result=3;return false;}
  if(!next.valid()){operation=op;ticket=seq;result=3;return false;}
  if(op!=QUERY && op!=SAVE)desired=next;
  if(!background){operation=op;ticket=seq;result=1;}
  runningOp=op;runningTicket=seq;silent=background;
  persist=op==SAVE||op==FACTORY_SAVE||migration;modeOnly=op==MODE;
  if(!online){waiting=false;result=5;return false;}
  waiting=true;expected=false;started=now;lastSent=now;nextToken();
  if(op!=QUERY && op!=SAVE && apply)apply(desired,modeOnly);
  if(request)request(token);
  return true;
 }
 void observe(const uint32_t* v,uint8_t mode,uint8_t queryToken,bool complete,uint32_t now){
  if(!complete || mode>1){known=false;return;}
  PerformanceConfig c;memcpy(c.values,v,sizeof(c.values));c.mode=mode;
  if(!c.valid()){known=false;return;}
  actual=c;known=true;lastObserved=now;
  if(waiting && queryToken==token)expected=true;
 }
 void tick(uint32_t now,bool online){
  if(waiting){
   if(expected){
    expected=false;
    const bool matches=runningOp==QUERY||runningOp==SAVE||actual.equals(desired);
    if(matches){
     if(runningOp==SAVE)desired=actual;
     uint8_t outcome=(!persist || (store&&store(actual)))?2:3;
     if(!silent){operation=runningOp;ticket=runningTicket;result=outcome;}
     waiting=false;lastPoll=now;return;
    }
   }
   if(now-started>=1500){waiting=false;if(!silent){operation=runningOp;ticket=runningTicket;result=online?4:5;}lastPoll=now;return;}
   if(now-lastSent>=350){lastSent=now;nextToken();
    if(runningOp!=QUERY && runningOp!=SAVE && apply)apply(desired,modeOnly);
    if(request)request(token);
   }
  }else if(online && now-lastPoll>=500){
   lastPoll=now;
   // Poll independent of UI, reconnect and cached data. Once a reset is
   // observed, restore the desired configuration without saving it anew.
   if(fresh(now)&&!actual.equals(desired))start(RECONCILE,desired,ticket,now,true,true);
   else start(QUERY,desired,ticket,now,true,true);
  }
 }
 private:
 uint32_t lastSent,started,lastPoll;bool persist,modeOnly,expected,silent;
 uint8_t runningOp,runningTicket;
 Apply apply;Request request;Store store;LoadFn load;
 void nextToken(){++token;if(token==0)++token;}
};
