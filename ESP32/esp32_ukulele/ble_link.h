// ============================================================
// ble_link.h — BLE 4.2 服务（ESP32-WROOM-32E 内置蓝牙）
//   手机 App 可通过 BLE 订阅演奏状态/事件，并下发命令
//   UUID 见 ble_link.cpp（自定义 128 位 UUID，便于 App 端识别）
// ============================================================
#pragma once
#include <Arduino.h>
#include <freertos/FreeRTOS.h>

class BleLink {
 public:
  void begin(const char *name = "Ukulele-ESP32");

  bool connected() const { return connected_; }
  size_t analysisPacketCapacity() const;

  bool notifyStatus(const uint8_t *d, size_t n);   // true only when BLE stack accepts notification
  void notifyEvent(const uint8_t *d, size_t n);    // 拨弦事件
  void notifyTable(const uint8_t *d, size_t n);    // 阈值表逐通道回传
  bool notifySpectrum(const uint8_t *d, size_t n); // true only after stack accepts; retry otherwise
  void notifyWave(const uint8_t *d, size_t n);     // 低速实时波形
  void notifySongStatus(const uint8_t *d, size_t n); // 曲谱传输确认

  bool hasCommand() const { return cmdCount_ != 0; }
  String takeCommand();                            // 取走并清空

  bool hasSongPacket() const { return songReady_; }
  size_t takeSongPacket(uint8_t *out, size_t cap);
  // BLE 写入特征的二进制曲谱分片由回调暂存，主循环再消费，避免在
  // NimBLE/Arduino BLE 的回调线程里访问 LittleFS。
  void setSongPacket(const uint8_t *d, size_t n);

  void setConnectedFlag(bool v) { connected_ = v; }
  void setCmd(const String &s);

 private:
  volatile bool connected_ = false;
  static const uint8_t CMD_Q = 16;
  static const size_t CMD_MAX = 96;
  volatile uint8_t cmdCount_ = 0;
  char cmdBuf_[CMD_Q][CMD_MAX] = {{0}};
  volatile bool songReady_ = false;
  uint8_t songBuf_[256] = {0};
  size_t songLen_ = 0;
  portMUX_TYPE cmdMux_ = portMUX_INITIALIZER_UNLOCKED;
};
