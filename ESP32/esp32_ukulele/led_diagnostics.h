#pragma once
#include <stdint.h>
#include <stddef.h>
struct GvLedDiag {
 uint8_t token=0,flags=0,chip=0,initial=0,config3=0,global=0,controlErrors=0,pwmErrors=0,badColumn=0,fault=0;
 uint16_t writeCRC=0,readCRC=0;uint32_t seq=0,ms=0;
};
inline bool parseLedDiagnostics(const uint8_t *p,size_t n,GvLedDiag &out){
 if(!p||n!=16||p[0]!=1||(p[2]&0xC0)!=0||p[9]>3||p[15]!=0)return false;
 if((p[2]&2)!=0 && (p[3]!=1||p[4]!=0x50||p[5]!=0x59||p[6]!=0xFF))return false;
 GvLedDiag d=out;d.token=p[1];d.flags=p[2];d.chip=p[3];d.initial=p[4];d.config3=p[5];d.global=p[6];
 d.controlErrors=p[7];d.pwmErrors=p[8];d.badColumn=p[9];d.writeCRC=(uint16_t)p[10]|((uint16_t)p[11]<<8);
 d.readCRC=(uint16_t)p[12]|((uint16_t)p[13]<<8);d.fault=p[14];out=d;return true;
}
