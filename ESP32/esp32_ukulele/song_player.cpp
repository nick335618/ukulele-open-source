#include "song_player.h"

#ifndef SONG_PLAYER_HOST_TEST
#include <FS.h>
#include <LittleFS.h>
#endif

namespace {
constexpr const char *kSongPath = "/song.bin";
constexpr const char *kTempPath = "/song.tmp";
constexpr uint32_t kMaxSongBytes = 65535u;
constexpr uint16_t kHeaderSize = 8;
constexpr uint16_t kEventSize = 6;
}

bool SongPlayer::begin() {
  mounted_ = LittleFS.begin(true);
  if (!mounted_) return false;
  if (!loadHeader()) eventCount_ = 0;
  return true;
}

uint32_t SongPlayer::crc32Update(uint32_t crc, const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n; ++i) {
    crc ^= p[i];
    for (uint8_t b = 0; b < 8; ++b)
      crc = (crc >> 1) ^ (0xEDB88320u & (-(int32_t)(crc & 1u)));
  }
  return crc;
}

void SongPlayer::status(BleLink &ble, uint8_t code, uint32_t value) {
  uint8_t p[6] = {0x90, code,
                  (uint8_t)(value & 0xFF), (uint8_t)((value >> 8) & 0xFF),
                  (uint8_t)((value >> 16) & 0xFF), (uint8_t)((value >> 24) & 0xFF)};
  ble.notifySongStatus(p, sizeof(p));
}

bool SongPlayer::handlePacket(const uint8_t *p, size_t n, BleLink &ble) {
  if (!p || n == 0) return false;
  const uint8_t type = p[0];
  if (type == 0x01) { // BEGIN: type,total_len(2),crc32(4)
    if (n < 7) { status(ble, 0xE1, 0); return false; }
    const uint32_t total = (uint32_t)p[1] | ((uint32_t)p[2] << 8);
    const uint32_t crc = (uint32_t)p[3] | ((uint32_t)p[4] << 8) |
                         ((uint32_t)p[5] << 16) | ((uint32_t)p[6] << 24);
    if (!mounted_ || playing_ || total < kHeaderSize || total > kMaxSongBytes) {
      status(ble, 0xE2, total); return false;
    }
    if (uploadFile_) uploadFile_.close();
    LittleFS.remove(kTempPath);
    uploadFile_ = LittleFS.open(kTempPath, "w");
    if (!uploadFile_) { status(ble, 0xE3, 0); return false; }
    uploadTotal_ = total;
    uploadExpectedCrc_ = crc;
    uploadOffset_ = 0;
    uploadCrc_ = 0xFFFFFFFFu;
    uploading_ = true;
    status(ble, 0x01, 0);
    return true;
  }

  if (type == 0x02) { // DATA: type,offset(2),data...
    if (!uploading_ || n < 4) { status(ble, 0xE4, uploadOffset_); return false; }
    const uint32_t off = (uint32_t)p[1] | ((uint32_t)p[2] << 8);
    const size_t bytes = n - 3;
    if (off != uploadOffset_ || uploadOffset_ + bytes > uploadTotal_) {
      status(ble, 0xE5, uploadOffset_); return false;
    }
    if (uploadFile_.write(p + 3, bytes) != bytes) {
      status(ble, 0xE6, uploadOffset_); return false;
    }
    uploadCrc_ = crc32Update(uploadCrc_, p + 3, bytes);
    uploadOffset_ += bytes;
    status(ble, 0x02, uploadOffset_);
    return true;
  }

  if (type == 0x03) { // END
    if (!uploading_) { status(ble, 0xE7, 0); return false; }
    uploadFile_.flush();
    uploadFile_.close();
    const uint32_t got = uploadCrc_ ^ 0xFFFFFFFFu;
    bool ok = (uploadOffset_ == uploadTotal_) && (got == uploadExpectedCrc_);
    if (ok) {
      File f=LittleFS.open(kTempPath,"r");ok=f && validateFile(f);if(f)f.close();
    }
    if(ok){
      // Keep a recoverable old song until the validated replacement is in place.
      LittleFS.remove("/song.bak");
      bool hadOld=LittleFS.rename(kSongPath,"/song.bak");
      ok=LittleFS.rename(kTempPath,kSongPath);
      if(!ok && hadOld)LittleFS.rename("/song.bak",kSongPath);
      if(ok)LittleFS.remove("/song.bak");
    }
    if(!ok)LittleFS.remove(kTempPath);
    uploading_ = false;
    if (ok) {
      loadHeader();
      status(ble, 0x03, uploadOffset_);
    } else {
      status(ble, 0xE8, uploadOffset_);
    }
    return ok;
  }

  status(ble, 0xEF, type);
  return false;
}

bool SongPlayer::validateFile(File &f){
  if(!f || f.size()<kHeaderSize || f.size()>kMaxSongBytes)return false;
  uint8_t h[kHeaderSize]={0};if(!f.seek(0,SeekSet)||f.read(h,sizeof(h))!=sizeof(h))return false;
  uint16_t count=uint16_t(h[6])|(uint16_t(h[7])<<8);
  if(h[0]!='U'||h[1]!='K'||h[2]!='S'||h[3]!='1'||(h[4]!=1&&h[4]!=2)||h[5]!=0||!count||8u+uint32_t(count)*6u!=f.size())return false;
  uint32_t duration=0;uint8_t previous[4]={31,31,31,31},next[4]={31,31,31,31};bool restGroup=false;
  for(uint16_t i=0;i<count;++i){uint8_t b[6];if(f.read(b,6)!=6||b[2]>15||b[3]>18)return false;
    if(h[4]==2 && ((b[5]!=0&&b[5]!=128) || (!b[2]&&(b[4]||b[5])) || (b[2]&&!b[4]) || (i==count-1&&(b[2]||b[4]))))return false;
    if(h[4]==2){if(b[0]||b[1]){for(uint8_t c=0;c<4;++c){previous[c]=next[c];next[c]=31;}restGroup=false;}
      if(!b[2]){for(uint8_t c=0;c<4;++c)if(next[c]!=31)return false;restGroup=true;}
      else {if(restGroup)return false;for(uint8_t c=0;c<4;++c)if(b[2]&(1u<<c)){if(next[c]!=31 || (b[5]==128&&previous[c]!=b[3]))return false;next[c]=b[3];}}
    }
    duration+=uint16_t(b[0])|(uint16_t(b[1])<<8);if(duration>7200000u)return false;}
  return true;
}

bool SongPlayer::loadHeader(){
  if(!mounted_)return false;
  File f=LittleFS.open(kSongPath,"r");bool ok=validateFile(f);
  if(ok){uint8_t h[8];f.seek(0,SeekSet);f.read(h,8);eventCount_=uint16_t(h[6])|(uint16_t(h[7])<<8);formatVersion_=h[4];}
  if(f)f.close();return ok;
}

bool SongPlayer::readEvent(Event &e) {
  if (!playFile_ || eventIndex_ >= eventCount_) return false;
  uint8_t b[kEventSize] = {0};
  if(!songBytes_)return false;memcpy(b,songBytes_+kHeaderSize+eventIndex_*kEventSize,kEventSize);
  e.delta = (uint16_t)b[0] | ((uint16_t)b[1] << 8);
  if(b[2]>15 || b[3]>18)return false;
  e.cols = b[2];
  e.row = b[3];
  e.level = b[4];
  e.flags = b[5];
  ++eventIndex_;
  return true;
}

bool SongPlayer::readGroup(){
  Event e;
  if(haveCarry_){e=carry_;haveCarry_=false;}else if(!readEvent(e))return false;
  pending_=Group();nextDueMs_+=e.delta;
  for(;;){
    // All zero-delta rows describe one simultaneous chord. A same-string
    // conflict uses its highest physical fret, never serial last-wins.
    if(e.level && e.cols){
      if(e.level>pending_.level)pending_.level=e.level;
      for(uint8_t c=0;c<4;++c)if(e.cols&(1u<<c))
        if(pending_.frets[c]==31 || e.row>pending_.frets[c]){
          pending_.frets[c]=e.row;pending_.levels[c]=e.level;
          if(e.flags&128)pending_.hold|=1u<<c;else pending_.hold&=~(1u<<c);
        }
    }
    pending_.endIndex=eventIndex_;
    if(eventIndex_>=eventCount_)break;
    if(!readEvent(e))return false;
    if(e.delta){carry_=e;haveCarry_=true;break;}
  }
  haveGroup_=true;repeatGapApplied_=false;return true;
}

void SongPlayer::publishGuide(GvLink &gv,BleLink &ble,const uint8_t frets[4],uint8_t level,const uint8_t *levels){
  uint8_t copy[4];for(uint8_t c=0;c<4;++c)copy[c]=levels?levels[c]:level;
  gv.setLedGuideLevels(frets,copy);lastGuidePublishMs_=millis();++guideSequence_;
  // Fits the default BLE MTU: version, session, sequence, all four frets,
  // one brightness and XOR integrity check. This reports the ESP-submitted
  // target; it is not proof of LP5860 physical illumination.
  uint8_t p[17]={0x90,0x16,2,uint8_t(session_),uint8_t(session_>>8),
    uint8_t(guideSequence_),uint8_t(guideSequence_>>8),frets[0],frets[1],frets[2],frets[3],copy[0],copy[1],copy[2],copy[3],0,0};
  for(uint8_t i=0;i<16;++i)p[16]^=p[i];ble.notifySongStatus(p,sizeof(p));
}

uint32_t SongPlayer::noteEnd(uint8_t col,uint16_t index,uint8_t fret,uint32_t when)const{
  while(index<eventCount_){
    const uint8_t *p=songBytes_+8u+uint32_t(index)*6u;
    when+=uint16_t(p[0])|(uint16_t(p[1])<<8);
    uint8_t next=31;bool hold=false;
    do{
      p=songBytes_+8u+uint32_t(index)*6u;
      if((p[2]&(1u<<col))&&p[4]&&(next==31||p[3]>next)){next=p[3];hold=(p[5]&128)!=0;}
      ++index;
    }while(index<eventCount_ && songBytes_[8u+uint32_t(index)*6u]==0 && songBytes_[9u+uint32_t(index)*6u]==0);
    if(next!=fret||!hold)return when;
  }
  return when+500u;
}
void SongPlayer::renderGuide(GvLink &gv,BleLink &ble,uint32_t pos,bool force){
  uint8_t levels[4];bool changed=force;
  for(uint8_t c=0;c<4;++c){levels[c]=envelope_[c].at(pos,clock_.rate(),softGuide);if(levels[c]!=shownLevels_[c])changed=true;}
  // Match the LP5860's 50 Hz frame clock; never flood the shared SPI link.
  if(changed&&(force||uint32_t(millis()-lastGuidePublishMs_)>=20)){
    for(uint8_t c=0;c<4;++c)shownLevels_[c]=levels[c];publishGuide(gv,ble,currentFrets_,currentLevel_,levels);
  }
}

void SongPlayer::instrumentChord(GvLink &gv,uint8_t op,const uint8_t *frets){
  if(op==1)++noteToken_; // Leases during a long pause must not consume note identities.
  uint8_t p[8]={1,op,uint8_t(noteToken_),uint8_t(noteToken_>>8),31,31,31,31};
  if(frets)for(uint8_t c=0;c<4;++c)p[4+c]=frets[c];
  gv.sendCmd(0x28,p,8);leaseMs_=millis();
}

void SongPlayer::playbackStatus(BleLink &ble,uint8_t op,uint8_t result,uint16_t token){
  uint32_t now=millis();clock_.advance(now);uint32_t pos=clock_.position();
  uint16_t rate=clock_.rate();++statusRevision_;
  uint8_t p[20]={0x91,2,op,result,uint8_t(token),uint8_t(token>>8),
    uint8_t(session_),uint8_t(session_>>8),uint8_t(!playing_?0:(clock_.paused()?2:1)),uint8_t(instrument_),
    uint8_t(rate),uint8_t(rate>>8),uint8_t(pos),uint8_t(pos>>8),uint8_t(pos>>16),uint8_t(pos>>24),
    uint8_t(emittedIndex_),uint8_t(emittedIndex_>>8),uint8_t(statusRevision_),uint8_t(statusRevision_>>8)};
  ble.notifySongStatus(p,sizeof(p));lastPlaybackStatusMs_=now;
}

bool SongPlayer::play(GvLink &gv,const SongLedRestore &restore,BleLink &ble,uint16_t rate){
  if(!SongPlaybackClock::validRate(rate) || uploading_ || !mounted_ || !loadHeader() || !eventCount_){
    status(ble,0xE9,0);return false;
  }
  if(playing_)stop(gv,restore,ble,0x12);
  playFile_=LittleFS.open(kSongPath,"r");
  if(!playFile_){status(ble,0xEA,0);return false;}
  if(!playFile_.seek(kHeaderSize,SeekSet)){playFile_.close();status(ble,0xEB,0);return false;}
  free(songBytes_);songBytes_=static_cast<uint8_t*>(malloc(playFile_.size()));
  if(!songBytes_||!playFile_.seek(0,SeekSet)||playFile_.read(songBytes_,playFile_.size())!=playFile_.size()){
    free(songBytes_);songBytes_=nullptr;playFile_.close();status(ble,0xEA,0);return false;
  }
  for(uint8_t c=0;c<4;++c){envelope_[c]=GuideEnvelope();shownLevels_[c]=0;}
  eventIndex_=0;emittedIndex_=0;nextDueMs_=0;haveCarry_=false;haveGroup_=false;finishing_=false;
  currentLevel_=0;for(uint8_t c=0;c<4;++c)currentFrets_[c]=31;
  if(!readGroup()){free(songBytes_);songBytes_=nullptr;playFile_.close();status(ble,0xEC,0);return false;}
  clock_.start(millis(),rate);playing_=true;if(++session_==0)++session_;
  publishGuide(gv,ble,currentFrets_,0);status(ble,0x10,eventCount_);return true;
}

void SongPlayer::control(uint8_t op,uint16_t rate,uint16_t token,GvLink &gv,const SongLedRestore &restore,BleLink &ble){
  uint8_t result=0;
  if(op>5 || !SongPlaybackClock::validRate(rate) || (!token && op!=4))result=2;
  else if(op!=4 && haveControl_ && token==lastControlToken_){
    // Repeating the same accepted command is idempotent, including PLAY.
    result=(op==lastControlOp_ && rate==lastControlRate_)?0:2;
  }else{
    // Settle due note ends before freezing the clock; otherwise pausing on
    // a REST boundary could hold the previous lit frame for the entire pause.
    if(op==2 && playing_ && !clock_.paused())tick(gv,restore,ble,millis());
    uint32_t now=millis();clock_.advance(now);
    if(op==0 || op==5){bool audio=op==5;if(playing_)stop(gv,restore,ble);instrument_=audio;if(!play(gv,restore,ble,rate))result=3;}
    else if(op!=4 && !playing_)result=1;
    else if(op==1)clock_.rate(now,rate);
    else if(op==2)clock_.pause(now);
    else if(op==3)clock_.resume(now);
    if(!result && op!=4){haveControl_=true;lastControlToken_=token;lastControlOp_=op;lastControlRate_=rate;}
  }
  playbackStatus(ble,op==5?0:op,result,token);
}

void SongPlayer::stop(GvLink &gv,const SongLedRestore &restore,BleLink &ble,uint8_t statusCode){
  if(instrument_)instrumentChord(gv,0);instrument_=false;
  clock_.stop(millis());playing_=false;
  if(playFile_)playFile_.close();free(songBytes_);songBytes_=nullptr;
  currentLevel_=0;for(uint8_t c=0;c<4;++c)currentFrets_[c]=31;
  haveGroup_=finishing_=false;publishGuide(gv,ble,currentFrets_,0);
  gv.setLedConfig(restore.mode,restore.axis,restore.speed,restore.brightness,restore.flags);
  status(ble,statusCode,emittedIndex_);playbackStatus(ble);
}

void SongPlayer::clear(BleLink &ble){
  if(playing_){status(ble,0xE2,0);return;}instrument_=false;
  clock_.stop(millis());playing_=false;
  if(playFile_)playFile_.close();free(songBytes_);songBytes_=nullptr;if(uploadFile_)uploadFile_.close();
  LittleFS.remove(kSongPath);LittleFS.remove(kTempPath);eventCount_=0;emittedIndex_=0;eventIndex_=0;
  status(ble,0x13,0);playbackStatus(ble);
}

void SongPlayer::tick(GvLink &gv,const SongLedRestore &restore,BleLink &ble,uint32_t now){
  if(!playing_ || !playFile_)return;
  if(instrument_ && millis()-leaseMs_>=80)instrumentChord(gv,2);
  clock_.advance(now);uint32_t pos=clock_.position();
  if(!clock_.paused()){
    if(finishing_ && pos>=finishAtMs_){stop(gv,restore,ble,0x11);return;}
    // Consume entire simultaneous groups before publishing. Never cap at
    // eight individual records, which used to split a chord across frames.
    for(uint8_t guard=0;guard<8 && haveGroup_ && pos>=nextDueMs_;++guard){
      uint8_t attacks[4]={31,31,31,31};bool hasAttack=false;
      for(uint8_t c=0;c<4;++c){
        if(pending_.frets[c]==31){envelope_[c].peak=0;}
        else if(currentFrets_[c]!=pending_.frets[c] || !(pending_.hold&(1u<<c))){
          attacks[c]=pending_.frets[c];hasAttack=true;
          envelope_[c].start=nextDueMs_;envelope_[c].end=noteEnd(c,pending_.endIndex,pending_.frets[c],nextDueMs_);
          envelope_[c].peak=pending_.levels[c];
        }
        currentFrets_[c]=pending_.frets[c];
      }
      if(instrument_ && hasAttack){for(uint8_t c=0;c<4;++c)if(attacks[c]!=31 && attacks[c]+gv.capo.semitones>18){stop(gv,restore,ble,0xED);return;}}
      if(instrument_ && hasAttack)instrumentChord(gv,1,attacks);
      currentLevel_=pending_.level;currentGroupMs_=nextDueMs_;emittedIndex_=pending_.endIndex;
      renderGuide(gv,ble,pos,true);haveGroup_=false;
      if(emittedIndex_>=eventCount_){
        if(!currentLevel_){stop(gv,restore,ble,0x11);return;}
        finishing_=true;finishAtMs_=currentGroupMs_+500;break;
      }
      if(!readGroup()){stop(gv,restore,ble,0xED);return;}
    }
    if(!repeatGapApplied_)renderGuide(gv,ble,pos);
    if(formatVersion_==1 && haveGroup_ && !repeatGapApplied_ && pos<nextDueMs_){
      // Blank only retriggered strings BEFORE their next beat. Onset times
      // are unchanged. Gap is 60 real ms, capped at half the preceding beat.
      uint32_t gap=uint32_t(clock_.rate())*60u/1000u;
      uint32_t half=(nextDueMs_-currentGroupMs_)/2u;if(gap>half)gap=half;
      if(gap && pos>=nextDueMs_-gap){
        uint8_t shown[4];bool changed=false;
        for(uint8_t c=0;c<4;++c){shown[c]=currentFrets_[c];
          if(currentLevel_ && pending_.level && shown[c]!=31 && shown[c]==pending_.frets[c]){shown[c]=31;changed=true;}}
        if(changed)publishGuide(gv,ble,shown,currentLevel_);
        repeatGapApplied_=true;
      }
    }
  }
  if(now-lastPlaybackStatusMs_>=100)playbackStatus(ble,255);
}
