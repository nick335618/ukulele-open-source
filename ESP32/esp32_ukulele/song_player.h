// song_player.h — ESP32 离线尤克里里曲谱存储与 LED 指引
//
// 曲谱文件格式：
//   8 字节头：'U' 'K' 'S' '1', version=1/2, reserved=0, event_count(uint16 LE)
//   每个事件 6 字节：delta_ms(uint16 LE), column_mask, fret(0..18; 0=open), level, flags
// BLE 上传采用分片包：BEGIN / DATA / END；完整文件落盘后才替换正式曲谱。
#pragma once

#ifdef SONG_PLAYER_HOST_TEST
#include "song_player_host.h"
#else
#include <Arduino.h>
#include <FS.h>
#include "ble_link.h"
#include "gvlink.h"
#endif
#include "song_playback_clock.h"
#include "guide_envelope.h"
#include <stdlib.h>

struct SongLedRestore {
  uint8_t mode = 0;
  uint8_t axis = 0;
  uint8_t speed = 24;
  uint8_t brightness = 180;
  uint8_t flags = 0;
};

class SongPlayer {
 public:
  bool begin();
  ~SongPlayer(){free(songBytes_);}
  bool softGuide=true;
  bool instrument() const{return instrument_;}
  void setInstrument(bool value){instrument_=value;}
  void instrumentChord(GvLink &gv,uint8_t op,const uint8_t *frets=nullptr);


  // 主循环中消费一个 BLE 曲谱包；返回 false 表示包格式或写入校验失败。
  bool handlePacket(const uint8_t *p, size_t n, BleLink &ble);

  bool play(GvLink &gv, const SongLedRestore &restore, BleLink &ble,uint16_t rate=1000);
  void control(uint8_t op,uint16_t rate,uint16_t token,GvLink &gv,const SongLedRestore &restore,BleLink &ble);
  void playbackStatus(BleLink &ble,uint8_t op=4,uint8_t result=0,uint16_t token=0);
  void stop(GvLink &gv, const SongLedRestore &restore, BleLink &ble,
            uint8_t statusCode = 0x12);
  void clear(BleLink &ble);
  void tick(GvLink &gv, const SongLedRestore &restore, BleLink &ble,
            uint32_t now);

  bool uploading() const { return uploading_; }
  bool playing() const { return playing_; }
  bool mounted() const { return mounted_; }
  uint16_t eventCount() const { return eventCount_; }

 private:
  struct Event {
    uint16_t delta;
    uint8_t cols;
    uint8_t row;
    uint8_t level;
    uint8_t flags;
  };

  struct Group { uint8_t frets[4]={31,31,31,31}; uint8_t level=0,levels[4]={0,0,0,0},hold=0; uint16_t endIndex=0; };
  uint8_t *songBytes_=nullptr;
  GuideEnvelope envelope_[4];uint8_t shownLevels_[4]={0,0,0,0};
  uint32_t noteEnd(uint8_t col,uint16_t index,uint8_t fret,uint32_t when)const;
  void renderGuide(GvLink &gv,BleLink &ble,uint32_t pos,bool force=false);
  bool readEvent(Event &e);
  bool readGroup();
  bool validateFile(File &f);
  void publishGuide(GvLink &gv,BleLink &ble,const uint8_t frets[4],uint8_t level,const uint8_t *levels=nullptr);
  bool loadHeader();
  void status(BleLink &ble, uint8_t code, uint32_t value);
  static uint32_t crc32Update(uint32_t crc, const uint8_t *p, size_t n);

  uint8_t formatVersion_=1; // v2 carries exact note ends/repeat gaps, no inferred dimming
  bool instrument_=false;uint16_t noteToken_=0;uint32_t leaseMs_=0;
  bool mounted_ = false;
  bool uploading_ = false;
  bool playing_ = false;
  uint32_t uploadTotal_ = 0;
  uint32_t uploadExpectedCrc_ = 0;
  uint32_t uploadOffset_ = 0;
  uint32_t uploadCrc_ = 0xFFFFFFFFu;
  uint16_t eventCount_ = 0;
  uint16_t eventIndex_ = 0;
  uint32_t nextDueMs_ = 0,lastPlaybackStatusMs_=0;
  uint16_t emittedIndex_=0,session_=0,lastControlToken_=0,lastControlRate_=0,statusRevision_=0;
  uint8_t lastControlOp_=255;
  bool haveControl_=false;
  Group pending_{};
  Event carry_{};
  bool haveCarry_=false,haveGroup_=false,repeatGapApplied_=false,finishing_=false;
  uint8_t currentFrets_[4]={31,31,31,31},currentLevel_=0;
  uint32_t currentGroupMs_=0,finishAtMs_=0,lastGuidePublishMs_=0;
  uint16_t guideSequence_=0;
  SongPlaybackClock clock_;
  File uploadFile_;
  File playFile_;
};
