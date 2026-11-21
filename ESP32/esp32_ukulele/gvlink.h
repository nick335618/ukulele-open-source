// ============================================================
// gvlink.h — ESP32 ↔ gv_host（FPGA）链路
//
// ★ 2026-09-23：物理层从 UART 改成 **SPI**（用户/同学要求，引脚见 fpgaspi.h）。
//   帧协议**完全不变**（下面所有字节格式一行没动），只换了收发通道：
//     原来：Serial2（GPIO25/26 @115200）
//     现在：FpgaSpi（GPIO18/19/23/5，SPI 模式 0，2 字节事务）
//
// gv_host 的协议（见 gv_host/src/cmd_decoder.v 与 host/PROTOCOL.md）：
//   帧格式： 0xAA | CMD | LEN | PAYLOAD[0..LEN-1] | CRC
//   CRC  = CMD ^ LEN ^ PAYLOAD[0] ^ ... ^ PAYLOAD[LEN-1]   （简单异或）
//
//   下行（ESP32 → FPGA）
//     0x01 NOTE      [音库号 0..32]
//     0x02 VIB       [on]
//     0x03 VIB_SET   [rate_x10, depth_cents]
//     0x04 GLIDE_SET [en, ms_hi, ms_lo]
//     0x05 GLIDE     [target]
//     0x06 GAIN      [主音量 0..255]           ← 功能②
//     0x07 TIMBRE    [音色索引 0..3]           ← 功能①
//     0x08 OPTOCFG   [ch, th_lo, th_hi, gain]  ← 功能④ 每通道阈值/增益
//     0x09 TONEWR    [sel, alo, ahi, d0..d3]   ← 功能① 音色数据（小端）
//     0x0A OPTGLOB   [pid, b0, b1, b2]         ← ★ v80 功能④ 全局参数（小端，取低位）
//     0x0B OPTGET    []                        ← ★ v80 请求一帧 0x86 参数回读
//     0x0C OPTFIRST  [b0, b1, b2]              ← 首事件锁定窗（20 位时钟数）
//     0x17 LEDCFG    [mode, axis, speed, brightness, flags]
//     0x18 LEDGUIDE  [column_mask, row, level, flags]
//   上行（FPGA → ESP32）
//     0x81 状态帧 LEN=5：[音库号, 音量, 音色, flags, cmd_cnt]
//     0x84 光电帧 LEN=17：[raw0..3(2B×4), dg0..3(2B×4), ev]
//     0x86 参数帧 LEN=30：[全部 16 个光电参数]  ← ★ v80，按需（0x0B 触发）
//     0x87 波形帧 LEN=26：[seq, decim, 24×signed8 audio samples]
//
// ★ v80：为什么要有 0x0A / 0x86 —— 功能④"把上位机的校正方案搬上屏幕"原来只搬了一半：
//   PC 上位机 opto_tuner.py 的四档预设里，每档除了 th/gain 还有 8 个全局参数
//   （slow / fast / cm_cap / margin / persist / cool / blank / rearm），
//   而 FPGA 里这 8 个是编译期常量 ⇒ 屏幕换预设时它们纹丝不动，
//   四档预设**在硬件上没有任何区别**；而且 0x08 只写不读，屏幕显示不出当前值。
//   现在：0x0A 逐条写全局参数，0x0B 请求、0x86 一次回读全部 16 个参数。
// ============================================================
#pragma once
#include <Arduino.h>

#include "fpgaspi.h"
#include "power_protocol.h"
#include "performance_config.h"

// ★ v80：光电参数编号表（ESP32 / 屏幕 / FPGA 三边**必须**用同一张表）
//   pid 0..3  = th0..th3    12 位
//   pid 4..7  = gain0..3     8 位
//   pid 8..15 = 全局参数（与 0x0A 的 pid 一一对应）：
//     8 slow(20) 9 fast(20) 10 cm_cap(18) 11 margin(8)
//     12 persist(8) 13 cool(16) 14 blank(20) 15 rearm(16)
//   pid 16 = 首事件锁定窗（0x0C，20 位时钟数，50 MHz 下 500000≈10 ms）
#define OPT_PID_COUNT   17
#define OPT_GLOB_BASE   8             // pid 8..15 走 0x0A OPTGLOB；pid16 走 0x0C
#define OPT_PID_TH0     0
#define OPT_PID_G0      4
#define OPT_PID_SLOW    8
#define OPT_PID_FAST    9
#define OPT_PID_CMCAP   10
#define OPT_PID_MARGIN  11
#define OPT_PID_PERSIST 12
#define OPT_PID_COOL    13
#define OPT_PID_BLANK   14
#define OPT_PID_REARM   15
#define OPT_PID_FIRST   16

struct GvStatus {
  uint8_t note = 0;      // 音库号 0..32
  uint8_t gain = 0;      // 当前音量（已含力度）
  uint8_t timbre = 0;    // 音色索引
  uint8_t flags = 0;
  uint8_t cmdCnt = 0;    // ★ v64：FPGA 已收到并校验通过的命令条数（验 ESP32→FPGA 方向用）
  uint32_t ms = 0;       // 收到时间
  bool valid = false;
};

// ★ 0x84 OPTEL：光电遥测帧（供屏幕显示四路光强/凹坑）
//   载荷 17 字节：raw0..3 (2B×4, 小端) + dg0..3 (2B×4, 小端) + ev (1B)
//   dgN = 增益后凹坑（就是拿去跟阈值比的量），有符号但正常按无符号读
struct GvOptel {
  uint16_t raw[4] = {0, 0, 0, 0};
  uint16_t dg[4]  = {0, 0, 0, 0};
  uint8_t  ev     = 0;
  uint8_t frets[4]={0},midi[4]={255,255,255,255},counts[4]={0};
  bool noteFeedback=false;uint8_t automatic=0,feedbackVersion=1;
  uint32_t seq=0;
  uint32_t ms     = 0;
  bool     valid  = false;
};

// ★ 0x87：FPGA 最终音频样本的低速抽取帧。
//   seq / decim 后跟 24 个有符号 8-bit 样本；ESP32 将多帧拼成 FFT 窗口。
struct GvWave {
  uint8_t seq = 0;
  uint8_t decim = 16;
  int8_t samples[24] = {0};
  uint32_t ms = 0;
  bool valid = false;
};

// ★ v80：0x86 OPTPARAM：**当前生效的全部 16 个光电参数**（屏幕校正页的"当前值"显示）
struct GvParam {
  // ★★ 镜像的**上电初值必须与 FPGA 的复位值一致**（`top_gv_host.v` 的复位段）。
  //   为什么必须：`0x08 OPTOCFG` 一次写"阈值 + 增益"两个量，写其中一个时必须带上
  //   另一个的**当前值**。如果这里还是全 0（还没成功回读过），那么用户第一次拖阈值滑块
  //   就会把增益按 0 发下去 —— 屏幕上表现为"刚进校正页调一下，灵敏度突然就没了"。
  //   （这是代码复核发现的真问题：原实现 `= {0}`。）
  //   数值来源：与当前 FPGA 的“抗误报”上电档保持一致；否则屏幕第一次
  //   只改阈值时会把另一项（增益）用旧镜像一起写回去。
  uint32_t v[OPT_PID_COUNT];
  GvParam(){memcpy(v,PERFORMANCE_FACTORY,sizeof(v));}
  bool complete=false;
  uint8_t version=1,mode=0xFF,token=0,detected=0,accepted=0,emitted=0,depth=0;
  uint16_t overflow=0,detectedCount=0,acceptedCount=0,emittedCount=0,peak=0;
  uint32_t ms = 0;
  // A sequence is more reliable than comparing millisecond timestamps:
  // a fast 0x86 response can arrive in the same millisecond as the prior one.
  uint32_t seq = 0;
  bool     valid = false;
  uint32_t get(int pid) const {
    return (pid >= 0 && pid < OPT_PID_COUNT) ? v[pid] : 0;
  }
};

struct GvPadDiag {uint8_t channel=0,token=0,flags=0,ids=0,ready=0;uint16_t raw=0,baseline=0,delta=0,amplitude=0,threshold=0;uint8_t version=0,gesture=0,vibratoFlags=0,owner=0,model=0;uint32_t seq=0,ms=0;};
struct GvFx {uint8_t version=0,mode=0,strength=2,actual=255,gain=0,flags=0;uint16_t token=0;uint32_t seq=0,ms=0;};
struct GvTouch {uint16_t threshold=360,token=0;uint8_t requestedFast=0,actualFast=0,grace=0,flags=0;uint32_t seq=0,ms=0;};
struct GvCapo {uint8_t version=0,semitones=0,token=0,caps=0;uint32_t seq=0,ms=0;};
struct GvGesture {uint8_t mask=255,token=0,caps=0;uint32_t seq=0,ms=0;};

#include "led_diagnostics.h"
#include "led_bitmap.h"
#include "chain_control.h"

class GvLink {
 public:
  void begin(FpgaSpi *spi);       // ★ SPI 版：把字节管道的指针交给它
  void poll();                    // 从 SPI 取字节喂进帧解析器

  void sendCmd(uint8_t cmd, const uint8_t *pay, uint8_t len);
  void setGain(uint8_t v);        // 0..255
  void setTimbre(uint8_t idx);    // 0..3
  void setLedConfig(uint8_t mode, uint8_t axis, uint8_t speed,
                    uint8_t brightness, uint8_t flags = 0);
  // 离线曲目指引：视觉列位图 bit0..3 + 行位 0..17 + PWM 亮度。
  void setLedGuideLevels(const uint8_t frets[4],const uint8_t levels[4]);
  void setLedGuideFrets(const uint8_t frets[4], uint8_t level);
  void setLedGuide(uint8_t colMask, uint8_t row, uint8_t level,
                   uint8_t flags = 0);
  void note(uint8_t voice);       // 0..32
  void ping();                    // 探活（发 VIB 关）

  // ★ v80 功能④：写一个光电参数（自动选 0x08 还是 0x0A），并按位宽钳位
  void setOptoParam(uint8_t pid, uint32_t val);
  void setOptoParams(const uint32_t *vals, int n);   // 批量（预设用）
  void requestOptoParams(uint8_t token=0);
  void setPlayMode(uint8_t mode);                          // 0x0B → 回来一帧 0x86

  static uint32_t optParamMax(uint8_t pid);          // 该参数的位宽上限（钳位用）

  void gestureCommand(uint8_t mask,uint8_t token);
  GvGesture gesture;
  GvCapo capo,model;
  uint8_t envelopeBank=0,envelopeKey=0,envelopeValue=235,envelopeToken=0;
  uint32_t envelopeMask=0;
  bool envelopeFull=false;
  void envelopeCommand(uint8_t op,uint8_t bank,uint8_t key,uint8_t value,uint8_t token){
    if(op<=3&&bank<4&&key<28){uint8_t p[]={4,op,bank,key,value,token};sendCmd(0x25,p,6);}
  }
  void modelCommand(uint8_t n,uint8_t token){if(n<=3||n==255){uint8_t p[3]={1,n,token};sendCmd(0x25,p,3);}}
  void capoCommand(uint8_t n,uint8_t token){if(n<=12||n==255){uint8_t p[3]={1,n,token};sendCmd(0x24,p,3);}}
  void requestLedDiag(uint8_t token);
  GvLedDiag ledDiag;
  struct {uint8_t error=0,cursor=0,level=0,mode=255,flags=0,caps=0;uint16_t crc=0,active=0,generation=0,token=0;uint32_t ms=0,seq=0;} bitmap;
  bool ledBitmapCommand(uint8_t op,uint8_t index,uint8_t a,uint8_t b,uint8_t c,uint16_t token);
  GvTouch touch;
  GvFx fx;
  struct {ChainConfig config;uint8_t token=0,mask=0,flags=0,armed=0;uint32_t seq=0,ms=0;} chain;
  void chainCommand(uint8_t op,const ChainConfig& c,uint8_t token){
    if(!c.valid()||op>1)return;uint8_t p[8]={1,uint8_t(!op?255:c.enabled?c.mask:254),c.amount[0],c.amount[1],c.amount[2],c.amount[3],c.target,token};sendCmd(0x27,p,8);
  }
  GvPadDiag padDiag;
  void requestPadDiag(uint8_t channel,uint8_t token){if(channel<97){uint8_t p[3]={2,channel,token};sendCmd(0x22,p,3);}}
  void fxCommand(uint8_t op,uint8_t mode,uint8_t strength,uint16_t token);
  void touchCommand(uint8_t op,uint16_t threshold,uint8_t fast,uint8_t grace,uint16_t token){
    uint8_t p[8]={1,op,uint8_t(threshold),uint8_t(threshold>>8),fast,grace,uint8_t(token),uint8_t(token>>8)};
    if(op<=1 && (op==0 || (threshold>=120 && threshold<=2400 && fast<=1 && grace<=60)))sendCmd(0x1E,p,8);
  }
  void powerCommand(uint8_t op,uint16_t id,uint16_t epoch);
  FpgaPowerState power;
  GvStatus status;
  GvOptel  optel;                // ★ 0x84 光电遥测（屏幕数值上屏用）
  GvWave   wave;                 // ★ 0x87 最终音频时域样本
  // Feed every validated block, even when poll() parses several in one pass.
  void (*onAnalysis)(const uint8_t *,size_t) = nullptr;
  void requestAnalysis(uint16_t token,bool scope=false){uint8_t p[3]={uint8_t(scope?2:1),uint8_t(token),uint8_t(token>>8)};sendCmd(0x1D,p,3);}
  void (*onWave)(const GvWave &) = nullptr;
  GvParam  param;                // ★ v80 0x86 参数回读
  volatile uint32_t okFrames = 0, errFrames = 0;
  volatile uint32_t statusCount = 0;  // 已解析的 0x81 状态帧数
  volatile uint32_t waveCount = 0;    // 已解析的 0x87 波形帧数

 private:
  void dispatch(uint8_t cmd, const uint8_t *pay, uint8_t len);
  FpgaSpi *spi_ = nullptr;
  uint8_t st_ = 0;                // 0=等同步 1=CMD 2=LEN 3=DATA 4=CRC
  uint8_t code_ = 0, len_ = 0, cnt_ = 0, crc_ = 0;
  // v2载荷缓冲64字节，支持50字节真实配置回读；旧30字节只标记不完整。
  // 旧版载荷缓冲不足时解析器会
  //   "长度异常 → errFrames++ 并重同步"，把每一帧参数回读都当坏帧丢掉
  //   （表现为屏幕上永远读不到当前值，而 FPGA 那边日志显示帧明明发出去了）。
  uint8_t pay_[64] = {0};
};
