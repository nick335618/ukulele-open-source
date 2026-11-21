#include "ble_link.h"
#include "ble_settings_cache.h"

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <esp_gatt_common_api.h>

// ---- UUID（自定义，App 端按此匹配）----
#define UUID_SERVICE "6b1e0001-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_STATUS  "6b1e0002-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_EVENT   "6b1e0003-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_CMD     "6b1e0004-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_TABLE   "6b1e0005-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_SPECTRUM "6b1e0006-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_WAVE    "6b1e0007-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_SONG    "6b1e0008-8f3a-4c2b-9d5e-1a2b3c4d5e6f"
#define UUID_SONG_STATUS "6b1e0009-8f3a-4c2b-9d5e-1a2b3c4d5e6f"

static BleLink *g_ble = nullptr;
static BLEServer *g_audioServer = nullptr;
static BLECharacteristic *g_chStatus = nullptr;
static BLECharacteristic *g_chEvent = nullptr;
static BLECharacteristic *g_chTable = nullptr;
static BLECharacteristic *g_chSpectrum = nullptr;
static BLECharacteristic *g_chWave = nullptr;
static BLECharacteristic *g_chSong = nullptr;
static BLECharacteristic *g_chSongStatus = nullptr;
static BleSettingsCache g_settingsCache;
static portMUX_TYPE g_settingsMux = portMUX_INITIALIZER_UNLOCKED;
static bool g_analysisNotifyAccepted=false;
static bool g_statusNotifyAccepted=false;
class AnalysisNotifyCB : public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic*,Status status,uint32_t) override {
    // Core 2.0.14 reports this synchronously inside notify().
    g_analysisNotifyAccepted=(status==SUCCESS_NOTIFY);
  }
};

class SettingsCB : public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic*,Status status,uint32_t) override {
    g_statusNotifyAccepted=(status==SUCCESS_NOTIFY);
  }
  void onRead(BLECharacteristic* c) override {
    uint8_t bytes[20];size_t length;
    portENTER_CRITICAL(&g_settingsMux);
    length=g_settingsCache.read(bytes,sizeof(bytes));
    portEXIT_CRITICAL(&g_settingsMux);
    c->setValue(bytes,length);
  }
};

// ---- 服务器连接回调 ----
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { if (g_ble) g_ble->setConnectedFlag(true); }
  void onDisconnect(BLEServer *srv) override {
    if (g_ble) g_ble->setConnectedFlag(false);
    if (srv) srv->startAdvertising();          // 断连后重新广播
  }
};

// ---- 命令回调 ----
class CmdCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    // 注意：ESP32 core 2.0.x 的 BLECharacteristic::getValue() 返回 std::string（不是 Arduino String）
    std::string v = c->getValue();
    if (!v.empty() && g_ble) g_ble->setCmd(String(v.c_str()));
  }
};

class SongCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string v = c->getValue();
    if (!v.empty() && g_ble)
      g_ble->setSongPacket(reinterpret_cast<const uint8_t *>(v.data()), v.size());
  }
};

void BleLink::begin(const char *name) {
  g_ble = this;

  BLEDevice::init(name);
  BLEDevice::setMTU(247); // 25-byte wave notifications and 183-byte song chunks.
  BLEServer *srv = BLEDevice::createServer();
  g_audioServer=srv;
  srv->setCallbacks(new ServerCB());

  // 6 notify characteristics each need declaration/value/CCCD (18 handles),
  // plus 2 write characteristics (4) and the service (1). The default 15
  // handles cannot register this service completely.
  BLEService *svc = srv->createService(BLEUUID(UUID_SERVICE), 32);

  g_chStatus = svc->createCharacteristic(UUID_STATUS, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  g_chStatus->setCallbacks(new SettingsCB());
  g_chStatus->addDescriptor(new BLE2902());

  g_chEvent = svc->createCharacteristic(UUID_EVENT, BLECharacteristic::PROPERTY_NOTIFY);
  g_chEvent->addDescriptor(new BLE2902());

  g_chTable = svc->createCharacteristic(UUID_TABLE, BLECharacteristic::PROPERTY_NOTIFY);
  g_chTable->addDescriptor(new BLE2902());

  g_chSpectrum = svc->createCharacteristic(UUID_SPECTRUM, BLECharacteristic::PROPERTY_NOTIFY);
  g_chSpectrum->setCallbacks(new AnalysisNotifyCB());
  g_chSpectrum->addDescriptor(new BLE2902());

  g_chWave = svc->createCharacteristic(UUID_WAVE, BLECharacteristic::PROPERTY_NOTIFY);
  g_chWave->addDescriptor(new BLE2902());

  g_chSongStatus = svc->createCharacteristic(UUID_SONG_STATUS, BLECharacteristic::PROPERTY_NOTIFY);
  g_chSongStatus->addDescriptor(new BLE2902());

  BLECharacteristic *chCmd =
      svc->createCharacteristic(UUID_CMD, BLECharacteristic::PROPERTY_WRITE |
                                               BLECharacteristic::PROPERTY_WRITE_NR);
  chCmd->setCallbacks(new CmdCB());

  g_chSong = svc->createCharacteristic(UUID_SONG, BLECharacteristic::PROPERTY_WRITE |
                                                   BLECharacteristic::PROPERTY_WRITE_NR);
  g_chSong->setCallbacks(new SongCB());

  svc->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(UUID_SERVICE);
  adv->setScanResponse(true);
  adv->start();
}

void BleLink::setCmd(const String &s) {
  if (s.length() >= CMD_MAX) return; // do not execute a truncated command
  const char *family = s.startsWith("VOLUME:") ? "VOLUME:" :
                       s.startsWith("TIMBRE:") ? "TIMBRE:" :
                       s.startsWith("LM1:") ? "LM1:" : s.startsWith("LED:") ? "LED:" : s.startsWith("PLAYMODE:") ? "PLAYMODE:" : s.startsWith("GESTURE:") ? "GESTURE:" : nullptr;
  bool full = false;
  portENTER_CRITICAL(&cmdMux_);
  // Keep order across different controls and song operations. Only unhandled
  // values for the same slider/control are superseded by their latest value.
  if (family) {
    for (uint8_t i = 0; i < cmdCount_;) {
      if (strncmp(cmdBuf_[i], family, strlen(family)) == 0) {
        for (uint8_t j = i + 1; j < cmdCount_; ++j)
          memcpy(cmdBuf_[j - 1], cmdBuf_[j], CMD_MAX);
        --cmdCount_;
      } else ++i;
    }
  }
  size_t n = s.length();
  if (cmdCount_ < CMD_Q) {
    memcpy(cmdBuf_[cmdCount_], s.c_str(), n);
    cmdBuf_[cmdCount_][n] = 0;
    ++cmdCount_;
  } else full = true;
  portEXIT_CRITICAL(&cmdMux_);
  if (full) Serial.println("[BLE] 命令队列已满，该命令未执行");
}

String BleLink::takeCommand() {
  char tmp[CMD_MAX] = {0};
  portENTER_CRITICAL(&cmdMux_);
  if (cmdCount_) {
    memcpy(tmp, cmdBuf_[0], CMD_MAX);
    for (uint8_t i = 1; i < cmdCount_; ++i)
      memcpy(cmdBuf_[i - 1], cmdBuf_[i], CMD_MAX);
    --cmdCount_;
  }
  portEXIT_CRITICAL(&cmdMux_);
  return String(tmp);
}

bool BleLink::notifyStatus(const uint8_t *d, size_t n) {
  portENTER_CRITICAL(&g_settingsMux);
  g_settingsCache.remember(d,n);
  portEXIT_CRITICAL(&g_settingsMux);
  if (!connected_ || !g_chStatus || !g_audioServer || !d || !n) return false;
  uint16_t conn=g_audioServer->getConnId();
  uint16_t mtu=g_audioServer->getPeerMTU(conn);
  if(mtu<23 || n>mtu-3 || !esp_ble_get_cur_sendable_packets_num(conn))return false;
  auto cccd=static_cast<BLE2902*>(g_chStatus->getDescriptorByUUID(BLEUUID(uint16_t(0x2902))));
  if(!cccd || !cccd->getNotifications())return false;
  g_statusNotifyAccepted=false;
  g_chStatus->setValue((uint8_t *)d, n);
  g_chStatus->notify();
  return g_statusNotifyAccepted;
}

void BleLink::notifyEvent(const uint8_t *d, size_t n) {
  if (!connected_ || !g_chEvent) return;
  g_chEvent->setValue((uint8_t *)d, n);
  g_chEvent->notify();
}

void BleLink::notifyTable(const uint8_t *d, size_t n) {
  if (!connected_ || !g_chTable) return;
  g_chTable->setValue((uint8_t *)d, n);
  g_chTable->notify();
}

bool BleLink::notifySpectrum(const uint8_t *d, size_t n) {
  if (!connected_ || !g_chSpectrum || !g_audioServer || !d || !n) return false;
  uint16_t conn=g_audioServer->getConnId();
  uint16_t mtu=g_audioServer->getPeerMTU(conn);
  if(mtu<23 || n>mtu-3 || n%20 || !esp_ble_get_cur_sendable_packets_num(conn))return false;
  auto cccd=static_cast<BLE2902*>(g_chSpectrum->getDescriptorByUUID(BLEUUID(uint16_t(0x2902))));
  if(!cccd || !cccd->getNotifications())return false;
  // Arduino notify() returns void; its synchronous status callback carries success.
  g_analysisNotifyAccepted=false;
  g_chSpectrum->setValue(const_cast<uint8_t*>(d),n);g_chSpectrum->notify();
  return g_analysisNotifyAccepted;
}

size_t BleLink::analysisPacketCapacity() const {
  if(!connected_ || !g_audioServer)return 1;
  uint16_t mtu=g_audioServer->getPeerMTU(g_audioServer->getConnId());
  if(mtu<23)return 1;
  size_t packets=(mtu-3)/20;return packets>12 ? 12 : packets;
}

void BleLink::notifyWave(const uint8_t *d, size_t n) {
  if (!connected_ || !g_chWave) return;
  g_chWave->setValue((uint8_t *)d, n);
  g_chWave->notify();
}

void BleLink::notifySongStatus(const uint8_t *d, size_t n) {
  if (!connected_ || !g_chSongStatus) return;
  g_chSongStatus->setValue((uint8_t *)d, n);
  g_chSongStatus->notify();
}

void BleLink::setSongPacket(const uint8_t *d, size_t n) {
  if (!d || n == 0) return;
  portENTER_CRITICAL(&cmdMux_);
  if (n > sizeof(songBuf_)) n = sizeof(songBuf_);
  memcpy(songBuf_, d, n);
  songLen_ = n;
  songReady_ = true;
  portEXIT_CRITICAL(&cmdMux_);
}

size_t BleLink::takeSongPacket(uint8_t *out, size_t cap) {
  if (!out || cap == 0) return 0;
  portENTER_CRITICAL(&cmdMux_);
  if (!songReady_) {
    portEXIT_CRITICAL(&cmdMux_);
    return 0;
  }
  size_t n = songLen_ < cap ? songLen_ : cap;
  memcpy(out, songBuf_, n);
  songLen_ = 0;
  songReady_ = false;
  portEXIT_CRITICAL(&cmdMux_);
  return n;
}
