#pragma once
#include <stdint.h>
#include <stddef.h>
struct FpgaPowerState {
  uint8_t state,result,reason,caps;uint16_t id,epoch,done,fault,elapsed;
  uint32_t ms,seq;bool valid;
  FpgaPowerState():state(255),result(0),reason(0),caps(0),id(0),epoch(0),done(0),fault(0),elapsed(0),ms(0),seq(0),valid(false){}
  static uint16_t word(const uint8_t*p){return (uint16_t)p[0]|((uint16_t)p[1]<<8);}
  bool decode(const uint8_t*p,size_t n){
    if(!p || n!=16 || p[0]!=1 || p[15]!=0 || p[2]>6 || p[3]>1 ||
       (word(p+8)&~31u) || (word(p+10)&~7u) || !(p[1]==0 || p[1]==3 || p[1]==4 || p[1]==5 || p[1]==6) || (p[14]&0xF0))return false;
    state=p[1];result=p[2];reason=p[3];id=word(p+4);epoch=word(p+6);
    done=word(p+8);fault=word(p+10);elapsed=word(p+12);caps=p[14];valid=true;return true;
  }
};
