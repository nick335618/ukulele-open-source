#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// The 12-byte performance heartbeat must not replace the settings read value.
class BleSettingsCache {
 public:
  BleSettingsCache():length_(0){}
  bool remember(const uint8_t* data,size_t size){
    if(!data || size<2 || data[0]!=0xA0 ||
       !((data[1]==1 && size==16)||(data[1]==2 && size==20)))return false;
    memcpy(bytes_,data,size);length_=size;return true;
  }
  size_t read(uint8_t* out,size_t capacity)const{
    if(!out || capacity<length_)return 0;
    memcpy(out,bytes_,length_);return length_;
  }
 private:
  uint8_t bytes_[20];size_t length_;
};
