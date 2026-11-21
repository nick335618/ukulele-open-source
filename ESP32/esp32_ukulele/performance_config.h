#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// Team calibrated defaults, matching authoritative modal60 reset values.
static const uint32_t PERFORMANCE_FACTORY[17]={97,97,97,97,68,48,78,71,262144,8192,0,10,20,7000,90000,40,50000};
static const uint32_t PERFORMANCE_PRESETS[4][17]={
 {60,60,60,60,80,80,80,80,131072,4096,0,4,10,4000,45000,40,500000},
 {97,97,97,97,68,48,78,71,262144,8192,0,10,20,7000,90000,40,50000},
 {180,180,180,180,64,64,64,64,524288,16384,150,25,40,12000,180000,40,500000},
 {120,120,120,120,64,64,64,64,131072,8192,250,10,20,7000,90000,40,500000}
};
struct PerformanceConfig {
 uint32_t values[17];uint8_t mode;
 PerformanceConfig(){memcpy(values,PERFORMANCE_FACTORY,sizeof(values));mode=0;}
 bool equals(const PerformanceConfig& x)const {
  return mode==x.mode && memcmp(values,x.values,sizeof(values))==0;
 }
 static uint32_t maximum(unsigned p){
  return p<4?4095:p<8?255:(p==8||p==9||p==14||p==16)?1048575:p==10?262143:(p==11||p==12)?255:65535;
 }
 bool valid()const {
  if(mode>1)return false;
  for(unsigned i=0;i<17;++i)if(values[i]>maximum(i))return false;
  return values[8]!=0 && values[9]!=0;
 }
};
inline uint32_t performanceCrc(const void* source,size_t n){
 const uint8_t* p=(const uint8_t*)source;uint32_t c=0xFFFFFFFFu;
 for(size_t i=0;i<n;++i){c^=p[i];for(int b=0;b<8;++b)c=(c>>1)^((c&1)?0xEDB88320u:0);}
 return c^0xFFFFFFFFu;
}
struct PerformanceRecord {
 uint32_t magic,version,values[17],mode,crc;
 void encode(const PerformanceConfig& c){
  magic=0x554B5034;version=4;memcpy(values,c.values,sizeof(values));mode=c.mode;
  crc=performanceCrc(this,offsetof(PerformanceRecord,crc));
 }
 bool decode(PerformanceConfig& c)const {
  if(magic!=0x554B5034 || version!=4 || mode>1 || crc!=performanceCrc(this,offsetof(PerformanceRecord,crc)))return false;
  memcpy(c.values,values,sizeof(values));c.mode=(uint8_t)mode;return c.valid();
 }
};
inline bool migratePerformance(const uint32_t* p,size_t bytes,uint32_t version,PerformanceConfig& out){
 if(version>3 || (bytes!=64 && bytes!=68))return false;
 out=PerformanceConfig();memcpy(out.values,p,bytes);out.mode=0;return out.valid();
}
