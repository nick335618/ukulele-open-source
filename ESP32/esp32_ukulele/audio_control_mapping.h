#pragma once
#include <stdint.h>
static constexpr uint8_t DEFAULT_BOOT_VOLUME = 20;

// modal60 touch_recog GAIN_DEF=160. 0x81 reports gain_w, NOT vol_q8:
// gain_w = (touch gain * vol_q8) >> 8. Keep the original volume curve.
static inline uint8_t masterGainForVolume(uint8_t volume){
  if(volume>100)volume=100;
  return (uint8_t)(((unsigned)volume*255u+50u)/100u);
}
static inline uint8_t engineGainForVolume(uint8_t volume){
  return (uint8_t)(((unsigned)masterGainForVolume(volume)*160u)>>8);
}
