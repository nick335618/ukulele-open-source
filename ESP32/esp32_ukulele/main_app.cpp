// ============================================================
// ESP32 侧主程序（ESP32-WROOM-32E）
//   功能：与 FPGA 建立 SPI 帧链路 → 解析演奏状态/拨弦事件
//         → BLE 上报手机 App；接收 App 命令 → 阈值标定与下发
//         串口屏（TJC3224T124_011）↔ ESP32（UART1 115200）
//   接线：ESP32 GPIO18(SCK)、GPIO19(MISO)、GPIO23(MOSI)、GPIO5(CS) → FPGA
//         ESP32 GPIO22(TX) → 屏幕 RX(2脚)   ESP32 GPIO21(RX) ← 屏幕 TX(3脚)
//         屏幕 4 脚 = 5V（必须外部 5V 供电，不是 3.3V）；GND 共地
//         GND 共地；均为 3.3V 直连
//   协议：docs/UART帧协议规范.md、docs/TJC串口屏协议速查.md
// ============================================================
#include <Arduino.h>
#include <Preferences.h>      // ★ v80：功能④的校正参数存到 NVS（掉电不丢）
#include <Wire.h>
#include "uke_board_power.h"

#include "ble_link.h"
#include "gvlink.h"
#include "fpgaspi.h"
#include "screen_link.h"
#include "audio_analysis.h"
#include "audio_analysis_send.h"
#include "screen_scope.h"
#include "power_manager.h"
#include <esp_system.h>
#include "song_player.h"
#include "performance_controller.h"
#include "audio_control_mapping.h"
#include "gesture_control.h"
#include "capo_control.h"
#include "model_control.h"
#include "touch_control.h"
#include "fx_control.h"

BleLink gBle;
ScreenLink gScreen;
GvLink gGv;
FpgaSpi gSpi;        // ★ ESP32<->FPGA 的 SPI 字节管道（替代原 UART）
PerformanceController gPerformance;
static uint8_t gPerformanceTicket=0;
static bool gMigrationNeeded=false;
static bool fpgaFresh(){return gGv.status.valid && millis()-gGv.status.ms<350;}
static void performanceApply(const PerformanceConfig&,bool);
static void performanceRequest(uint8_t);
static bool performanceStore(const PerformanceConfig&);
static bool performanceLoad(PerformanceConfig&,bool&);
static void pushPerformanceToScreen();
PowerManager gPower;
static UkeBoardPower<TwoWire> gBoardPower(Wire,Wire1);
static bool gPowerScreenModern=false,gPowerFirstTouch=false;
static bool gScreenBatterySupported=false;
static uint32_t gPowerUiBlockUntil=0,gPowerScreenChangedMs=0;
static int gScreenSleepReported=-1,gScreenDimReported=-1;
static void powerChanged();
static void powerTick(uint32_t);
static void pushPowerState(uint32_t,bool=false);
static void onScreenPower(bool);
static void onScreenPowerCommand(const uint8_t*,int);
static void onScreenPress();
static bool powerInputAllowed(){uint32_t n=millis();if(gPowerUiBlockUntil && (int32_t)(n-gPowerUiBlockUntil)>=0)gPowerUiBlockUntil=0;if(!gPower.playable() || gPowerUiBlockUntil)return false;gPower.activity(n);return true;}
SongPlayer gSongs;

// 屏幕返回错误码时直接打印出来。原来虽然有计数器，但没有挂回调，
// 调试时只能看到“变量不对”而不知道是屏幕拒绝了哪一条指令。
static bool gScreenModern=false;
static uint32_t gLastScreenCapProbe=0;
static void onScreenErr(uint8_t code) {
  if(gScreen.queryPending() && strcmp(gScreen.lastGetName(),"vpmode")==0){
    gScreenModern=false;Serial.println("[screen] Legacy HMI: mode variables unavailable; basic controls retained.");
  }
  static uint32_t lastLog=0;if(millis()-lastLog<2000)return;lastLog=millis();
  Serial.printf("[屏幕] 回报码 0x%02X（累计 %lu）\n",
                (unsigned)code, (unsigned long)gScreen.tjcErrors);
}

// ---------------- 屏幕操作 → 下发 FPGA ----------------
// 目标换成了同学的 gv_host 工程，它的协议是：AA CMD LEN PAYLOAD... CRC（异或）
//   0x06 GAIN   [主音量 0..255]     ← 功能②（gv_host 里本来就实现了）
//   0x07 TIMBRE [音色索引 0..1]     ← 功能①（本次给 gv_host 新增的）
// 屏幕上的音量是 0..100，这里换算成 0..255。
static void sendVolumeToFpga(uint8_t vol100) {
  if (vol100 > 100) vol100 = 100;
  uint8_t v = masterGainForVolume(vol100);
  gGv.setGain(v);
  Serial.printf("[链路] 音量下发 gv_host: %u%% → %u/255\n", (unsigned)vol100, (unsigned)v);
}

static void sendTimbreToFpga(uint8_t idx) {
  idx = (idx == 1) ? 1 : 0;
  gGv.setTimbre(idx);
  Serial.printf("[链路] 音色索引下发 gv_host: %u\n", (unsigned)idx);
}

// ---------------- LED 灯阵：屏幕配置 / NVS / FPGA ----------------
// 屏幕发送模式参数，App 还可发送 72 灯亮度和呼吸配置。
// ESP32 负责事务校验、状态同步与 NVS；FPGA 计算 PWM 并驱动 LP5860。
struct LedConfigState {
  uint8_t mode = 0;       // 0 off, 1 constant, 2 breathe, 3 progressive,
                          // 4 dragon, 5 ripple, 6 event sparkle
  uint8_t axis = 0;       // 0 rows (CS), 1 columns (SW)
  uint8_t speed = 24;     // phase increment (0 = freeze)
  uint8_t brightness = 180;
  uint8_t flags = 0;      // bit0 = mode-6 guide glow
};

static LedConfigState gLed;
static LedBitmapControl gBitmap;
static uint32_t gLedLastSendMs = 0;
static bool gLedSavePending = false;
static uint32_t gLedChangedMs = 0;

static void clampLedConfig() {
  if (gLed.mode > 6 && gLed.mode!=8) gLed.mode = 0;
  gLed.axis &= 1;
}

static void pushLedConfigToScreen() {
  char cmd[32];
  snprintf(cmd, sizeof(cmd), "vledmode=%u", (unsigned)gLed.mode);
  gScreen.raw(cmd);
  snprintf(cmd, sizeof(cmd), "vledaxis=%u", (unsigned)gLed.axis);
  gScreen.raw(cmd);
  snprintf(cmd, sizeof(cmd), "vledspeed=%u", (unsigned)gLed.speed);
  gScreen.raw(cmd);
  snprintf(cmd, sizeof(cmd), "vledbri=%u", (unsigned)gLed.brightness);
  gScreen.raw(cmd);
  snprintf(cmd, sizeof(cmd), "vledflags=%u", (unsigned)gLed.flags);
  gScreen.raw(cmd);
}

static void sendLedConfigToFpga(bool quiet = false) {
  clampLedConfig();
  if(gLed.mode==8){
    gBitmap.desired.level=gLed.brightness;
    gGv.setLedConfig(8,gLed.axis,gLed.speed,gLed.brightness,gLed.flags);
  }else gGv.setLedConfig(gLed.mode, gLed.axis, gLed.speed, gLed.brightness, gLed.flags);
  gLedLastSendMs = millis();
  if (!quiet) Serial.printf("[灯阵] 下发 mode=%u axis=%u speed=%u brightness=%u flags=0x%02X\n",
                (unsigned)gLed.mode, (unsigned)gLed.axis, (unsigned)gLed.speed,
                (unsigned)gLed.brightness, (unsigned)gLed.flags);
}

static SongLedRestore currentLedRestore() {
  SongLedRestore r;
  r.mode = gLed.mode;
  r.axis = gLed.axis;
  r.speed = gLed.speed;
  r.brightness = gLed.brightness;
  r.flags = gLed.flags;
  return r;
}

static void saveLedConfigToFlash() {
  Preferences p;
  if (!p.begin("ledcfg", false)) {
    Serial.println("[灯阵] ✗ 打开 NVS 失败，配置未保存");
    return;
  }
  p.putUChar("mode", gLed.mode);
  p.putUChar("axis", gLed.axis);
  p.putUChar("speed", gLed.speed);
  p.putUChar("level", gLed.brightness);
  p.putUChar("flags", gLed.flags);
  p.end();
}

static void loadLedConfigFromFlash() {
  Preferences p;
  if (p.begin("ledcfg", true)) {
    gLed.mode       = p.getUChar("mode", gLed.mode);
    gLed.axis       = p.getUChar("axis", gLed.axis);
    gLed.speed      = p.getUChar("speed", gLed.speed);
    gLed.brightness = p.getUChar("level", gLed.brightness);
    gLed.flags      = p.getUChar("flags", gLed.flags);
    p.end();
  }
  Preferences b;
  if(b.begin("ledbitmap",true)){
    uint8_t raw[148]={0};LedBitmap stored;
    const char* key=b.getBytesLength("v2")==148?"v2":"v1";size_t len=b.getBytesLength(key);
    if((len==148||len==13)&&b.getBytes(key,raw,len)==len&&stored.decode(raw,len)){
      gBitmap.desired=stored;gBitmap.saved=stored;gBitmap.hasSaved=true;
    }else if(gLed.mode==8)gLed.mode=0;
    b.end();
  }else if(gLed.mode==8)gLed.mode=0;
  clampLedConfig();
  Serial.printf("[灯阵] 读取配置 mode=%u axis=%u speed=%u brightness=%u flags=0x%02X\n",
                (unsigned)gLed.mode, (unsigned)gLed.axis, (unsigned)gLed.speed,
                (unsigned)gLed.brightness, (unsigned)gLed.flags);
}

// 屏幕帧：AA 17 mode axis speed brightness flags（固定 7 字节，无 FF 结尾）
static void onScreenLedCfg(const uint8_t *f, int n) {
  if(!powerInputAllowed())return;
  if (!f || n < 7 || f[0] != 0xAA || f[1] != 0x17) return;
  gBitmap.cancel();
  gLed.mode       = f[2];
  gLed.axis       = f[3] & 1;
  gLed.speed      = f[4];
  gLed.brightness = f[5];
  gLed.flags      = f[6];
  clampLedConfig();
  gLedSavePending = true;
  gLedChangedMs = millis();
  sendLedConfigToFpga();
  pushLedConfigToScreen();
}

// 两套音色模型和参数表均在 FPGA ROM 中；ESP32 仅发送音色选择命令。
static void downloadTimbre(uint8_t bankIdx) {
  sendTimbreToFpga(bankIdx);
}

// ---------------- 功能④：屏幕光电校正（★ v80 全参数版）----------------
// 屏幕侧方案见 docs/屏幕光电校正方案.md。两跳：
//   屏幕 --AA 0A 05 op ch v_lo v_mid v_hi crc seq--> ESP32
//        --0x08 OPTOCFG（阈值/增益）/ 0x0A OPTGLOB（全局参数）--> FPGA
//   FPGA --0x84 OPTEL--> 屏幕（四路实时 raw/dg）
//   FPGA --0x86 OPTPARAM--> 屏幕（当前 16 个参数 → 变量 vp0..vp15）
//
// ★★ v80 为什么必须改（这是功能④原来只做了一半的地方）：
//   旧版 AA 0A 的 op 只有"阈值±/增益±/加载预设/回读请求"，而 FPGA 里
//   slow/fast/cm_cap/margin/persist/cool/blank/rearm 这 8 个是**编译期常量**
//   ⇒ PC 上位机 opto_tuner.py 的四档预设里，真正生效的只有 th/gain，
//     四档预设**在硬件上没有任何区别**；而且 0x08 只写不读 ⇒ 屏幕显示不出当前值。
//   现在：FPGA 加 0x0A OPTGLOB / 0x0B OPTGET / 0x86 OPTPARAM，
//         屏幕这一帧的语义也跟着扩成"任意参数读写 + 预设 + 存 Flash"。
//
// 帧格式：**仍然是固定 10 字节，一个字节都没多加**（屏幕侧只改脚本，不用改控件）：
//   AA 0A 05 op ch v_lo v_mid v_hi crc seq
//     val24 = v_lo | (v_mid << 8) | (v_hi << 16)      ← 小端；最大参数 20 位，够用
//     crc   = 0A ^ 05 ^ op ^ ch ^ v_lo ^ v_mid ^ v_hi
//   op 0 = 写一个参数：ch = pid（0..15，表见 gvlink.h）
//   op 1 = 请求回读：ESP32 发 0x0B → 收到 0x86 后把 16 个值写到屏幕变量 vp0..vp15
//   op 2 = 加载预设：ch = 预设号 0..3（下发该档**全部 17 个**参数）
//   op 3 = 保存当前参数到 ESP32 Flash（NVS，掉电不丢）
//   op 4 = 从 Flash 读回并下发
// op5临时默认、op6默认并保存、op7模式；均由统一控制器确认。
//
// 默认值和四个预设由 config/performance_defaults.json 统一生成（pid顺序见gvlink.h）：
//   th0..3, g0..3, slow, fast, cm_cap, margin, persist, cool, blank, rearm
#define kOptoPresets PERFORMANCE_PRESETS
static const char* kOptoPresetNames[4]={"灵敏优先","均衡（团队默认）","抗误报","抗环境光"};
static uint8_t currentOptoPreset(){
  if(!fpgaFresh() || !gPerformance.fresh(millis()))return 0xFF;
  for(uint8_t p=0;p<4;p++)if(memcmp(gPerformance.actual.values,kOptoPresets[p],sizeof(kOptoPresets[p]))==0)return p;
  return 0xFF;
}
#define OPT_PRESET_DEFAULT 1
static constexpr uint32_t OPT_CFG_VERSION=4;

// 0x86 到了就推给屏幕。gvlink 的 dispatch 没有回调口，所以用"时间戳变化"当通知。
static uint32_t gLastOptParamMs = 0;
static uint32_t gLastOptParamSeq = 0;
static bool     gOptParamWant   = false;
static uint32_t gOptParamReqMs  = 0;
static bool     gOptParamRetried = false;

static void requestOptoParamsForScreen(){
  gPerformance.start(PerformanceController::QUERY,gPerformance.desired,++gPerformanceTicket,millis(),fpgaFresh());
}

static void pushOptoParamsToScreen() {
  if(gPower.state==PowerManager::SLEEP)return;
  char cmd[24];
  const uint8_t preset = currentOptoPreset();
  // Existing preset-page timer already renders vpset. Do not invent a preset
  // label for custom parameters; vp* still reports their actual values.
  {
    snprintf(cmd, sizeof(cmd), "vpset=%u", (unsigned)preset);
    gScreen.raw(cmd);
  }
  // 新屏的 Program.s 声明了 vp0..vp16；v2的0x86覆盖完整17项。
  // 新协议的pid16和模式均从FPGA真实回读；否则不允许保存。
  // 全部实际参数一起写回屏幕，载入NVS后
  // “首事件锁定”按钮会显示旧值。
  for (int i = 0; i < OPT_PID_COUNT; i++) {
    snprintf(cmd, sizeof(cmd), "vp%d=%u", i, (unsigned)gGv.param.v[i]);
    gScreen.raw(cmd);
  }
  Serial.printf("[光电] 参数已上屏 vp0..vp16：th=%u/%u/%u/%u g=%u/%u/%u/%u first=%u\n",
                (unsigned)gGv.param.v[0], (unsigned)gGv.param.v[1],
                (unsigned)gGv.param.v[2], (unsigned)gGv.param.v[3],
                (unsigned)gGv.param.v[4], (unsigned)gGv.param.v[5],
                (unsigned)gGv.param.v[6], (unsigned)gGv.param.v[7],
                (unsigned)gGv.param.v[OPT_PID_FIRST]);
}

// ---- op 3 / op 4：存到 / 读回 ESP32 的 Flash（NVS）----
//   ★ 为什么放在 ESP32 而不是 FPGA：FPGA 的 BSRAM 掉电就没了（这也是音色表要
//     由ROM初始化保留音色数据）。校正参数是"整定一次、用很久"的东西，
//     放 ESP32 的 NVS 里最省事，上电后自动下发即可。
static void performanceApply(const PerformanceConfig& c,bool modeOnly){
  if(!modeOnly)gGv.setOptoParams(c.values,17);
  gGv.setPlayMode(c.mode);
}
static void performanceRequest(uint8_t token){gGv.requestOptoParams(token);}
static bool performanceStore(const PerformanceConfig& c){
  if(!c.valid())return false;
  Preferences pref;if(!pref.begin("optocfg",false))return false;
  PerformanceRecord record,check;record.encode(c);
  bool ok=pref.putBytes("state4",&record,sizeof(record))==sizeof(record);
  ok=ok && pref.getBytes("state4",&check,sizeof(check))==sizeof(check);
  pref.end();PerformanceConfig decoded;
  ok=ok && check.decode(decoded) && decoded.equals(c);
  Serial.println(ok?"[演奏] 配置和模式已校验保存":"[演奏] NVS写入或读回校验失败");
  return ok;
}
static bool performanceLoad(PerformanceConfig& c,bool& migration){
  migration=false;Preferences pref;if(!pref.begin("optocfg",true))return false;
  const size_t size=pref.getBytesLength("state4");
  if(size){PerformanceRecord rec;bool ok=size==sizeof(rec) && pref.getBytes("state4",&rec,sizeof(rec))==sizeof(rec);
    pref.end();return ok && rec.decode(c);}
  const size_t oldSize=pref.getBytesLength("p");const uint32_t oldVersion=pref.getUInt("ver",0);
  uint32_t old[17]={0};bool ok=(oldSize==64 || oldSize==68) && pref.getBytes("p",old,oldSize)==oldSize;
  pref.end();if(!ok || !migratePerformance(old,oldSize,oldVersion,c))return false;
  migration=true;gMigrationNeeded=true;return true;
}
static void optoSaveToFlash(){
  gPerformance.start(PerformanceController::SAVE,gPerformance.desired,++gPerformanceTicket,millis(),fpgaFresh());
}
static bool optoLoadFromFlash(){
  return gPerformance.start(PerformanceController::LOAD,gPerformance.desired,++gPerformanceTicket,millis(),fpgaFresh());
}
static void pushPerformanceToScreen(){
 if(!gScreenModern)return;
 char cmd[40];const uint32_t now=millis();const bool online=fpgaFresh();
 const bool real=online && gPerformance.fresh(now);
 const char* names[]={"vpmwant","vpmode","vfpga","vcfgready","vopstatus","vopack","voptype","vdet","vacc","vemit","voq","voverflow","vemitcnt","vdetectcnt","vacceptcnt"};
 unsigned values[]={gPerformance.desired.mode,(unsigned)(real?gPerformance.actual.mode:255),online,
   (unsigned)(real&&gPerformance.confirmed(now)),gPerformance.result,gPerformance.ticket,gPerformance.operation,
   (unsigned)(real?gGv.param.detected:0),(unsigned)(real?gGv.param.accepted:0),
   (unsigned)(real?gGv.param.emitted:0),(unsigned)(real?gGv.param.depth:0),(unsigned)(real?gGv.param.overflow:0),
   (unsigned)(real?gGv.param.emittedCount:0),(unsigned)(real?gGv.param.detectedCount:0),(unsigned)(real?gGv.param.acceptedCount:0)};
 for(unsigned i=0;i<sizeof(values)/sizeof(values[0]);i++){snprintf(cmd,sizeof(cmd),"%s=%u",names[i],values[i]);gScreen.raw(cmd);}
}

// 屏幕发来的 AA 0A 帧：AA 0A 05 op ch v_lo v_mid v_hi crc seq
static void onOptoCfgFrame(const uint8_t* f,int n){
 if(!f || n!=10 || f[0]!=0xAA)return;
 const bool modern=f[1]==0x1A && f[2]==6;
 if(!modern && !(f[1]==0x0A && f[2]==5))return;
 const uint8_t op=f[3],pid=f[4],seq=f[modern?8:9];
 const uint32_t value=(uint32_t)f[5]|((uint32_t)f[6]<<8)|((uint32_t)f[7]<<16);
 const uint8_t crc=f[1]^f[2]^op^pid^f[5]^f[6]^f[7]^(modern?seq:0);
 if(crc!=f[modern?9:8])return;
 if(op!=1 && !powerInputAllowed())return;
 PerformanceConfig next=gPerformance.desired;
 PerformanceController::Operation operation=PerformanceController::QUERY;
 switch(op){
  case 0:if(pid>=17 || value>PerformanceConfig::maximum(pid)){gPerformance.reject(PerformanceController::PARAM,seq);pushPerformanceToScreen();return;}next.values[pid]=value;operation=PerformanceController::PARAM;break;
  case 1:operation=PerformanceController::QUERY;break;
  case 2:if(pid>3)return;memcpy(next.values,kOptoPresets[pid],sizeof(next.values));operation=PerformanceController::PRESET;break;
  case 3:operation=PerformanceController::SAVE;break;
  case 4:operation=PerformanceController::LOAD;break;
  case 5:memcpy(next.values,PERFORMANCE_FACTORY,sizeof(next.values));operation=PerformanceController::DEFAULT_TEMP;break;
  case 6:next=PerformanceConfig();operation=PerformanceController::FACTORY_SAVE;break;
  case 7:if(value>1){gPerformance.reject(PerformanceController::MODE,seq);pushPerformanceToScreen();return;}next.mode=(uint8_t)value;operation=PerformanceController::MODE;break;
  default:return;
 }
 gPerformance.start(operation,next,seq,millis(),fpgaFresh());pushPerformanceToScreen();
}

// 屏幕要一帧新数据（AA 0B）→ 把最近一帧 0x84 的数值写成 TJC 变量赋值。
// ★为什么写变量而不是写控件文本：变量是全局的，不受"当前停在哪一页"限制，
//   也不需要改控件的 vscope —— 这是 docs/屏幕ESP32链路.md 用血换来的教训。
static uint32_t gLastOptelSent = 0;
static uint32_t gLastNoOptelLog = 0;
static uint16_t gScreenBeat = 0;

static void onSeqReq(uint8_t seq) {
  char cmd[32];

  // AA 0B 是屏幕的实时刷新请求。vbeat 每次请求都递增，屏幕据此判断
  // ESP32 链路是否仍在工作；不能依赖 optel.valid，否则 FPGA 尚未发出
  // 第一帧 0x84 时，屏幕会被误判为离线。
  gScreenBeat++;
  snprintf(cmd, sizeof(cmd), "vbeat=%u", (unsigned)gScreenBeat);
  gScreen.raw(cmd);

  const bool haveOptel = fpgaFresh() && gGv.optel.valid && millis()-gGv.optel.ms<350;
  pushPerformanceToScreen();
  for (int i = 0; i < 4; i++) {
    const unsigned raw = haveOptel ? (unsigned)gGv.optel.raw[i] : 0U;
    snprintf(cmd, sizeof(cmd), "vra%d=%u", i, raw);
    gScreen.raw(cmd);
  }
  for (int i = 0; i < 4; i++) {
    const unsigned dg = haveOptel ? (unsigned)gGv.optel.dg[i] : 0U;
    snprintf(cmd, sizeof(cmd), "vdg%d=%u", i, dg);
    gScreen.raw(cmd);
  }

  // 位定义与屏幕侧一致：bit0=G、bit1=E、bit2=C、bit3=A。
  // 无有效 0x84 帧时主动清零，避免屏幕继续显示上一根弦的触发状态。
  snprintf(cmd, sizeof(cmd), "vev=%u", haveOptel ? (unsigned)gGv.optel.ev : 0U);
  gScreen.raw(cmd);

  if (!haveOptel) {
    const uint32_t now = millis();
    if (gLastNoOptelLog == 0 || (now - gLastNoOptelLog) >= 1000) {
      gLastNoOptelLog = now;
      Serial.printf("[光电] 屏幕要数据(seq=%u) 但还没收到 0x84 帧\n", (unsigned)seq);
    }
    return;
  }
  gLastOptelSent = millis();
  // 每 20 次打一条日志，免得刷屏
  static uint32_t cnt = 0;
  if ((++cnt % 20) == 1) {
    Serial.printf("[光电] 已上屏 raw=%u/%u/%u/%u dg=%u/%u/%u/%u ev=%02X\n",
                  (unsigned)gGv.optel.raw[0], (unsigned)gGv.optel.raw[1],
                  (unsigned)gGv.optel.raw[2], (unsigned)gGv.optel.raw[3],
                  (unsigned)gGv.optel.dg[0], (unsigned)gGv.optel.dg[1],
                  (unsigned)gGv.optel.dg[2], (unsigned)gGv.optel.dg[3],
                  (unsigned)gGv.optel.ev);
  }
}

// ---------------- 功能④ 的离屏自检（'x'）----------------
// 不依赖屏幕控件、也不依赖 FPGA 回话，把每条软件通路各走一遍：
//   ① op=0 写全局参数（pid≥8）→ 0x0A OPTGLOB
//   ② op=0 写通道参数（pid<8） → 0x08 OPTOCFG
//   ③ op=2 加载预设          → 17 个参数一起下发
//   ④ op=1 请求回读          → 0x0B（真机上 0x86 回来后自动上屏）
// 直接合成数据喂进处理函数，绕过串口。
static void cmdInjectOpto() {
  Serial.println("\n=== 功能④ 离屏自检（v80 全参数版）===");

  uint8_t f[10];
  // ---- ① 写一个**全局**参数：pid=8(slow) ← 131072 = 0x020000 ----
  {
    const uint8_t op = 0, pid = OPT_PID_SLOW;
    const uint32_t val = 131072;
    f[0] = 0xAA; f[1] = 0x0A; f[2] = 0x05; f[3] = op; f[4] = pid;
    f[5] = (uint8_t)(val & 0xFF);
    f[6] = (uint8_t)((val >> 8) & 0xFF);
    f[7] = (uint8_t)((val >> 16) & 0xFF);
    f[8] = 0x0A ^ 0x05 ^ op ^ pid ^ f[5] ^ f[6] ^ f[7];   // 与屏幕侧同一公式
    f[9] = 0x01;
    Serial.print("① 写全局 pid=8(slow) <- 131072 : ");
    for (int i = 0; i < 10; i++) Serial.printf("%02X ", f[i]);
    Serial.println();
    onOptoCfgFrame(f, 10);
  }
  // ---- ② 写一个**每通道**参数：pid=4(gain0) ← 72（走 0x08 那条支路）----
  {
    const uint8_t op = 0, pid = OPT_PID_G0;
    const uint32_t val = 72;
    f[0] = 0xAA; f[1] = 0x0A; f[2] = 0x05; f[3] = op; f[4] = pid;
    f[5] = (uint8_t)(val & 0xFF); f[6] = 0; f[7] = 0;
    f[8] = 0x0A ^ 0x05 ^ op ^ pid ^ f[5] ^ f[6] ^ f[7];
    f[9] = 0x02;
    Serial.print("② 写通道 pid=4(gain0) <- 72    : ");
    for (int i = 0; i < 10; i++) Serial.printf("%02X ", f[i]);
    Serial.println("（0x08 一次写阈值+增益，必须带上当前阈值，否则会把阈值冲成 0）");
    onOptoCfgFrame(f, 10);
  }
  // ---- ③ 加载预设 2（抗误报）——17 个参数一起下发 ----
  {
    const uint8_t op = 2, ps = 2;
    f[0] = 0xAA; f[1] = 0x0A; f[2] = 0x05; f[3] = op; f[4] = ps;
    f[5] = 0; f[6] = 0; f[7] = 0;
    f[8] = 0x0A ^ 0x05 ^ op ^ ps;                          // 三个值字节都是 0
    f[9] = 0x03;
    Serial.print("③ 加载预设 2(抗误报)           : ");
    for (int i = 0; i < 10; i++) Serial.printf("%02X ", f[i]);
    Serial.println();
    onOptoCfgFrame(f, 10);
  }
  // ---- ④ 请求回读 ----
  {
    const uint8_t op = 1, ch = 0;
    f[0] = 0xAA; f[1] = 0x0A; f[2] = 0x05; f[3] = op; f[4] = ch;
    f[5] = 0; f[6] = 0; f[7] = 0;
    f[8] = 0x0A ^ 0x05 ^ op ^ ch;
    f[9] = 0x04;
    Serial.print("④ 请求回读（0x0B）             : ");
    for (int i = 0; i < 10; i++) Serial.printf("%02X ", f[i]);
    Serial.println("（0x86 回来后会把 vp0..vp16 推上屏）");
    onOptoCfgFrame(f, 10);
  }
  // ---- ⑤ 合成一帧 0x84 遥测，走一遍"实时值上屏"通路 ----
  gGv.optel.raw[0] = 2500; gGv.optel.raw[1] = 2511;
  gGv.optel.raw[2] = 2497; gGv.optel.raw[3] = 2500;
  gGv.optel.dg[0] = 108; gGv.optel.dg[1] = 86;
  gGv.optel.dg[2] = 121; gGv.optel.dg[3] = 95;
  gGv.optel.ev = 0x05;
  gGv.optel.valid = true;
  onSeqReq(0x5A);

  Serial.println("=== 自检结束：上面应看到 17 条参数下发日志 + 上屏日志 ===\n");
}

// ---------------- 功能③：屏幕状态 ----------------

// 当前音色索引（0=尤克里里，1=吉他）。
static uint8_t gCurTimbre = 0;
// ★ 2026-09-23 晚：上电自动灌音色的一次性标志（见 setup 末尾与 loop 开头）
static bool    gTimbreAutoDone = false;

// ---------------- 串口屏状态 ----------------
static uint8_t gCurVolume = DEFAULT_BOOT_VOLUME;
static uint32_t gLastVolReqMs = 0;
static const uint32_t SCREEN_VOL_POLL_MS = 200;
static uint32_t gLastVolWriteMs = 0;
static uint8_t gWroteVol = 0xFF;          // 最近一次下发的音量（0xFF = 还没发过）
static uint8_t gScreenReportedVol = 0xFF; // 屏幕回读的音量
static bool gScreenInitDone = false;
static uint8_t gScreenTestStage = 0;      // 屏幕闭环自检状态机
static uint32_t gScreenTestT0 = 0;
static bool gScreenTestResult = false;
static uint8_t gScreenTestSavedVol = DEFAULT_BOOT_VOLUME;
static uint8_t gScreenTestProbeVol = 33;

static void bitmapTick(uint32_t now){
 static uint32_t seen=0,storeAt=0,lastNotify=0,lastRestore=0;static uint16_t revision=0;
 static uint8_t snapshot[156]={0},previous[156]={0},part=12;static bool connected=false;
 if(gBitmap.assembler.receiving&&uint32_t(now-gBitmap.assembler.started)>6000){
  gBitmap.client=gBitmap.assembler.ticket;gBitmap.result=4;gBitmap.assembler.receiving=false;
 }
 if(seen!=gGv.bitmap.seq){seen=gGv.bitmap.seq;bool pending=gBitmap.waiting;auto& v=gGv.bitmap;
  gBitmap.observe(v.error,v.cursor,v.level,v.mode,v.flags,v.crc,v.active,v.generation,v.caps,v.token,now);
  if(pending&&!gBitmap.waiting&&gBitmap.result==2){
   gLed.mode=8;gLed.brightness=gBitmap.desired.level;gLedLastSendMs=now;
   gLedSavePending=true;gLedChangedMs=now;pushLedConfigToScreen();storeAt=now;
  }
 }
 // Restore the saved pattern after FPGA-only reset, never steal a song's LEDs.
 if(!gBitmap.waiting&&!gBitmap.assembler.receiving&&gLed.mode==8&&gBitmap.hasSaved&&gBitmap.fresh(now)&&gBitmap.saved.supported(gGv.bitmap.caps)&&
    (!gBitmap.actualKnown)&&!gBitmap.needStore&&now-lastRestore>3000&&gPower.playable()&&!gSongs.playing()){
  LedBitmap restore=gBitmap.saved;restore.level=gLed.brightness;
  lastRestore=now;gBitmap.request(restore,0,now,fpgaFresh(),true,false);
 }
 gBitmap.tick(now,fpgaFresh(),gPower.playable(),gSongs.playing(),
  [](uint8_t op,uint8_t index,uint8_t a,uint8_t b,uint8_t c,uint16_t token){return gGv.ledBitmapCommand(op,index,a,b,c,token);});
 if(gBitmap.needStore&&now-storeAt>=650){
  Preferences p;bool ok=false;uint8_t raw[148],back[148];gBitmap.confirmed.encode(raw);
  if(p.begin("ledbitmap",false)){ok=p.putBytes("v2",raw,148)==148&&p.getBytesLength("v2")==148&&p.getBytes("v2",back,148)==148&&!memcmp(raw,back,148);p.end();}
  gBitmap.finishStore(ok);
  if(!ok)Serial.println("[灯阵] 已生效配置写入或回读 NVS 失败");
 }
 if(!gBle.connected()){connected=false;part=12;return;}
 if(part>=12){
  bool fresh=fpgaFresh()&&gBitmap.fresh(now);
  uint8_t f=(fresh?1:0)|(gBitmap.known?2:0)|(fresh&&gBitmap.captured()?4:0)|
   (gBitmap.hasSaved&&gBitmap.saved==gBitmap.desired?8:0)|(gSongs.playing()?16:0)|(gPower.playable()?32:0)|(gBitmap.actualKnown?64:0);
  uint8_t body[156]={gBitmap.result,f,uint8_t(fresh?gBitmap.mode:255),gBitmap.actual.level,gBitmap.client,gBitmap.caps,
   uint8_t(gBitmap.activeCrc),uint8_t(gBitmap.activeCrc>>8),uint8_t(gBitmap.generation),uint8_t(gBitmap.generation>>8),0,0};
  if(gBitmap.actualKnown)memcpy(body+12,gBitmap.actual.data,144);
  bool changed=memcmp(body,previous,156)!=0;
  if(connected&&!changed&&now-lastNotify<900)return;
  memcpy(snapshot,body,156);memcpy(previous,body,156);++revision;part=0;
 }
 if(connected&&now-lastNotify<18)return;
 uint8_t packet[20]={0xA7,2,uint8_t(revision),uint8_t(revision>>8),part,12};
 memcpy(packet+6,snapshot+part*13,13);for(int i=0;i<19;i++)packet[19]^=packet[i];
 if(gBle.notifyStatus(packet,20)){++part;connected=true;lastNotify=now;}
}

static CapoControl gCapo;
static bool gCapoStateRequested=false;
static void capoSend(uint8_t n,uint8_t token){gGv.capoCommand(gGv.capo.seq?n:255,token);}
static bool capoStore(uint8_t n){Preferences p;if(!p.begin("capo",false))return false;
 uint8_t b[3]={1,n,uint8_t(n^0xa5)};uint8_t readback[3]={0};bool ok=p.putBytes("config",b,3)==3&&p.getBytes("config",readback,3)==3&&!memcmp(b,readback,3);p.end();return ok;}
static uint8_t capoLoad(){Preferences p;if(!p.begin("capo",true))return 0;uint8_t b[3]={0};
 bool ok=p.getBytesLength("config")==3&&p.getBytes("config",b,3)==3;p.end();
 return ok&&b[0]==1&&b[1]<=12&&b[2]==uint8_t(b[1]^0xa5)?b[1]:0;}
static void pushCapoState(uint32_t now){
 static uint8_t prev[13]={0};static uint16_t rev=0;static uint32_t last=0;static bool connected=false;
 if(!gBle.connected()){connected=false;return;}
 bool fresh=fpgaFresh()&&gCapo.fresh(now);
 uint8_t flags=(fresh?1:0)|(fresh&&gCapo.actual==gCapo.desired?2:0)|(gCapo.saved==gCapo.desired?4:0)|(gCapo.result==1?8:0);
 uint8_t b[13]={0xab,2,gCapo.desired,uint8_t(fresh?gCapo.actual:255),gCapo.result,flags,
  uint8_t(gCapo.client),uint8_t(gCapo.client>>8),0,0,uint8_t(fresh?12:0),uint8_t(!fresh?0:gGv.capo.version>=3?7:gGv.capo.version>=2?3:0),0};
 bool changed=memcmp(prev,b,13)!=0;
 if(connected&&now-last<30)return;
 if(connected&&!changed&&!gCapoStateRequested&&now-last<500)return;
 if(changed)++rev;memcpy(prev,b,13);b[8]=rev;b[9]=rev>>8;
 for(unsigned i=0;i<12;++i)b[12]^=b[i];
 if(!gBle.notifyStatus(b,13)){gCapoStateRequested=true;return;}
 connected=true;last=now;gCapoStateRequested=false;
}

static ModelControl gModel;
static bool gModelStateRequested=false;
// Model polling and envelope readback share the FPGA 0x91 response stream.
static bool modelReplyPending=false;
static uint8_t modelWireToken=0;
static uint32_t modelWireStarted=0;
static void modelSend(uint8_t n,uint8_t token){
  modelReplyPending=true;modelWireToken=token;modelWireStarted=millis();
  gGv.modelCommand(gGv.model.seq?n:255,token);
}
static bool modelStore(uint8_t n){Preferences p;if(!p.begin("model",false))return false;
 uint8_t b[3]={1,n,uint8_t(n^0xa5)};uint8_t readback[3]={0};bool ok=p.putBytes("config",b,3)==3&&p.getBytes("config",readback,3)==3&&!memcmp(b,readback,3);p.end();return ok;}
static uint8_t modelLoad(){Preferences p;if(!p.begin("model",true))return 0;uint8_t b[3]={0};
 bool ok=p.getBytesLength("config")==3&&p.getBytes("config",b,3)==3;p.end();
 return ok&&b[0]==1&&b[1]<=3&&b[2]==uint8_t(b[1]^0xa5)?b[1]:0;}
static void pushModelState(uint32_t now){
 static uint8_t prev[13]={0};static uint16_t rev=0;static uint32_t last=0;static bool connected=false;
 if(!gBle.connected()){connected=false;return;}
 bool fresh=fpgaFresh()&&gModel.fresh(now);
 uint8_t flags=(fresh?1:0)|(fresh&&gModel.actual==gModel.desired?2:0)|(gModel.saved==gModel.desired?4:0)|(gModel.result==1?8:0);
 uint8_t b[13]={0xad,1,gModel.desired,uint8_t(fresh?gModel.actual:255),gModel.result,flags,
  uint8_t(gModel.client),uint8_t(gModel.client>>8),0,0,uint8_t(fresh?gModel.caps:0),0,0};
 bool changed=memcmp(prev,b,13)!=0;
 if(connected&&now-last<30)return;
 if(connected&&!changed&&!gModelStateRequested&&now-last<500)return;
 if(changed)++rev;memcpy(prev,b,13);b[8]=rev;b[9]=rev>>8;
 for(unsigned i=0;i<12;++i)b[12]^=b[i];
 if(!gBle.notifyStatus(b,13)){gModelStateRequested=true;return;}
 connected=true;last=now;gModelStateRequested=false;
}

static uint16_t envelopeClient=0;
static constexpr uint8_t ENVELOPE_FACTORY=235;
static uint8_t envelopeResult=0,envelopeOp=0,envelopeBank=0,envelopeKey=0,envelopeValue=ENVELOPE_FACTORY,envelopeToken=0;
static uint32_t envelopeStarted=0;
static bool envelopeRetried=false,envelopeSent=false;
static bool envelopeReady(uint32_t now){
  return fpgaFresh() && gPower.playable() && gGv.model.version==4 &&
      gGv.model.seq && now-gGv.model.ms<1700;
}
static void requestEnvelope(uint8_t op,uint8_t bank,uint8_t key,uint8_t value,uint16_t ticket,uint32_t now){
  if(!envelopeReady(now)){envelopeClient=ticket;envelopeResult=4;return;}
  if(envelopeResult==1){
    if(ticket==0)return;  // A screen gesture never interrupts an in-flight app edit.
    if(envelopeClient!=0&&envelopeOp!=0)return;
  }
  gPower.activity(now);
  envelopeClient=ticket;envelopeBank=bank;envelopeKey=key;envelopeValue=value;envelopeOp=op;
  envelopeStarted=now;envelopeResult=1;envelopeRetried=false;envelopeSent=false;
}
static void envelopeTick(uint32_t now){
  if(envelopeResult==1 && !envelopeSent){
    if(!envelopeReady(now)||now-envelopeStarted>=2000)envelopeResult=4;
    else if(!modelReplyPending){
      envelopeSent=true;envelopeStarted=now;
      gGv.envelopeCommand(envelopeOp,envelopeBank,envelopeKey,envelopeValue,++envelopeToken);
    }
  }else if(envelopeResult==1){
    const bool matches=gGv.envelopeToken==envelopeToken&&gGv.envelopeBank==envelopeBank&&gGv.envelopeKey==envelopeKey;
    const bool valueOk=envelopeOp==0||(envelopeOp==1?gGv.envelopeValue==envelopeValue:gGv.envelopeMask==0);
    if(envelopeReady(now)&&matches&&gGv.envelopeFull)envelopeResult=6;
    else if(envelopeReady(now)&&matches&&valueOk)envelopeResult=2;
    else if(!envelopeReady(now)||now-envelopeStarted>=2000)envelopeResult=4;
    else if(now-envelopeStarted>=600&&!envelopeRetried){
      envelopeRetried=true;gGv.envelopeCommand(envelopeOp,envelopeBank,envelopeKey,envelopeValue,envelopeToken);
    }
  }
  static uint8_t previous[13]={0};
  static uint32_t sent=0;
  static bool connected=false;
  if(!gBle.connected()){connected=false;envelopeClient=0;return;}
  if(gGv.model.version!=4&&envelopeClient==0)return;
  const bool fresh=envelopeReady(now);
  const uint32_t mask=fresh?gGv.envelopeMask:0;
  uint8_t b[14]={0xb5,3,uint8_t(fresh),uint8_t(fresh?gGv.envelopeBank:0),uint8_t(fresh?gGv.envelopeKey:0),
      uint8_t(fresh?gGv.envelopeValue:ENVELOPE_FACTORY),uint8_t(envelopeClient),uint8_t(envelopeClient>>8),envelopeResult,
      uint8_t(mask),uint8_t(mask>>8),uint8_t(mask>>16),uint8_t(mask>>24),0};
  if(connected&&!memcmp(b,previous,13)&&now-sent<750)return;
  for(unsigned i=0;i<13;i++)b[13]^=b[i];
  if(gBle.notifyStatus(b,14)){memcpy(previous,b,13);sent=now;connected=true;}
}

static ChainControl gChain;
static bool gChainStateRequested=false;
static void chainSend(uint8_t op,const ChainConfig& c,uint8_t token){gGv.chainCommand(gGv.chain.seq?op:0,c,token);}
static bool chainStore(const ChainConfig& c){Preferences p;if(!p.begin("fxchain",false))return false;uint8_t b[11];c.encode(b);uint8_t readback[11]={0};bool ok=p.putBytes("config",b,11)==11&&p.getBytes("config",readback,11)==11&&!memcmp(b,readback,11);p.end();return ok;}
static ChainConfig chainLoad(){ChainConfig c;Preferences p;if(p.begin("fxchain",true)){uint8_t b[11];if(p.getBytesLength("config")==11&&p.getBytes("config",b,11)==11)c.decode(b,11);p.end();}return c;}
static void pushChainState(uint32_t now){
 static uint8_t previous[20]={0};static uint16_t revision=0;static uint32_t last=0;static bool connected=false;
 if(!gBle.connected()){connected=false;return;}bool fresh=fpgaFresh()&&gChain.fresh(now);
 uint8_t b[20]={0xae,1,uint8_t(gChain.actual.enabled),gChain.actual.mask,
   gChain.actual.amount[0],gChain.actual.amount[1],gChain.actual.amount[2],gChain.actual.amount[3],gChain.actual.target,
   gChain.actualMask,uint8_t((fresh?16:0)|(fresh?gChain.flags:0)),gChain.result,
   uint8_t(gChain.client),uint8_t(gChain.client>>8),0,0,uint8_t(fresh?gChain.armed:0),127,0,0};
 bool changed=memcmp(previous,b,20)!=0;if(connected&&now-last<40)return;
 if(connected&&!changed&&!gChainStateRequested&&now-last<500)return;
 if(changed)++revision;memcpy(previous,b,20);b[14]=revision;b[15]=revision>>8;for(unsigned i=0;i<19;++i)b[19]^=b[i];
 if(!gBle.notifyStatus(b,20)){gChainStateRequested=true;return;}last=now;connected=true;gChainStateRequested=false;
}
static FxControl gFx;
static bool gFxStateRequested=false,gFxScreenAvailable=false;
static void fxSend(uint8_t op,const FxConfig& c,uint16_t token){if(gGv.fx.seq==0 || gGv.fx.version!=3)gGv.fxCommand(0,0,0,token);else gGv.fxCommand(op,c.mode,c.strength,token);}
static bool fxStore(const FxConfig& c){Preferences p;if(!p.begin("audiofx",false))return false;uint8_t b[6];c.encode(b);bool ok=p.putBytes("config",b,6)==6;p.end();return ok;}
static FxConfig fxLoad(){FxConfig c;Preferences p;if(!p.begin("audiofx",true))return c;uint8_t b[6]={0};if(p.getBytesLength("config")==6&&p.getBytes("config",b,6)==6)c.decode(b,6);p.end();return c;}
static void fxRequest(const FxConfig& c,uint16_t ticket){
 ChainConfig off=gChain.desired;off.enabled=false;gChain.request(off,0,millis(),fpgaFresh());gChainStateRequested=true;
 gPower.activity(millis());gFx.request(c,ticket,millis(),fpgaFresh()&&gPower.playable());gFxStateRequested=true;
}
static void pushFxState(uint32_t now){
 static uint8_t previous[16]={0};static uint16_t revision=0;static uint32_t last=0;static bool connected=false;
 bool fresh=fpgaFresh()&&gFx.fresh(now);
 if(gFxScreenAvailable){static uint32_t screenAt=0;if(now-screenAt>=250){screenAt=now;
   const char* names[]={"vfxmode","vfxwet","vfxresult","vfxfresh","vfxack","vfxbeat"};
   unsigned values[]={unsigned(fresh?gFx.actual:255),gFx.desired.strength,gFx.result,unsigned(fresh?1:0),gFx.client,unsigned(now&65535)};
   char cmd[40];for(unsigned i=0;i<6;++i){snprintf(cmd,sizeof(cmd),"%s=%u",names[i],values[i]);gScreen.raw(cmd);}}}
 if(!gBle.connected()){connected=false;return;}
 uint8_t flags=(fresh?1:0)|(fresh&&gFx.applied()?2:0)|(gFx.saved==gFx.desired?4:0)|(gFx.result==1?8:0);
 uint8_t b[16]={0xA6,3,gFx.desired.mode,gFx.desired.strength,uint8_t(fresh?gFx.actual:255),uint8_t(fresh?gFx.gain:0),flags,gFx.result,
   uint8_t(gFx.client),uint8_t(gFx.client>>8),0,0,uint8_t(fresh?gFx.flags:0),uint8_t(fresh?(gGv.fx.version==3?15:0):0),0,0};
 bool changed=memcmp(previous,b,16)!=0;
 if(connected&&now-last<30)return;
 if(connected&&!changed&&!gFxStateRequested&&now-last<500)return;
 if(changed)++revision;memcpy(previous,b,16);b[10]=revision;b[11]=revision>>8;
 for(unsigned i=0;i<15;++i)b[15]^=b[i];
 if(!gBle.notifyStatus(b,16)){gFxStateRequested=true;return;}
 connected=true;last=now;gFxStateRequested=false;
}

static TouchControl gTouch;
static bool gTouchStateRequested=false;
static uint32_t gTouchBleAccepted=0,gTouchBleRejected=0;
static uint8_t gTouchLastPacket[20]={0};
static void touchSend(uint8_t op,const TouchConfig& c,uint16_t token){gGv.touchCommand(op,c.threshold,c.fast,c.grace,token);}
static bool touchStore(const TouchConfig& c){Preferences p;if(!p.begin("touchctl",false))return false;
 uint8_t b[8];c.encode(b);bool ok=p.putBytes("config",b,8)==8;p.end();return ok;}
static TouchConfig touchLoad(){TouchConfig c;Preferences p;if(!p.begin("touchctl",true))return c;
 uint8_t b[8]={0};if(p.getBytesLength("config")==8 && p.getBytes("config",b,8)==8)c.decode(b,8);p.end();return c;}
static void pushTouchState(uint32_t now){
 static uint8_t previous[20]={0};static uint16_t revision=0;static uint32_t last=0;static bool connected=false;
 static uint32_t attemptAt=0;static bool attempted=false;
 if(!gBle.connected()){connected=false;attempted=false;return;}
 bool fresh=fpgaFresh() && gTouch.fresh(now);
 uint8_t flags=(fresh?1:0)|(fresh&&gTouch.applied()?2:0)|(gTouch.saved==gTouch.desired?4:0)|(gTouch.result==1?8:0);
 uint8_t b[20]={0xA5,1,uint8_t(gTouch.desired.threshold),uint8_t(gTouch.desired.threshold>>8),gTouch.desired.fast,gTouch.desired.grace,
 uint8_t(gTouch.actual.threshold),uint8_t(gTouch.actual.threshold>>8),uint8_t(fresh?gTouch.actual.fast:255),uint8_t(fresh?gTouch.actual.grace:255),
 flags,gTouch.result,uint8_t(gTouch.client),uint8_t(gTouch.client>>8),0,0,uint8_t(fresh?gTouch.sensorFlags:0),7,0,0};
 bool changed=memcmp(previous,b,20)!=0;
 if(connected&&!gTouchStateRequested&&!changed&&now-last<500)return;if(connected&&now-last<30)return;
 if(attempted && uint32_t(now-attemptAt)<30)return;
 attempted=true;attemptAt=now;
 if(changed)++revision;memcpy(previous,b,20);b[14]=revision;b[15]=revision>>8;
 for(unsigned i=0;i<19;++i)b[19]^=b[i];memcpy(gTouchLastPacket,b,20);
 if(!gBle.notifyStatus(b,20)){++gTouchBleRejected;gTouchStateRequested=true;return;}
 ++gTouchBleAccepted;last=now;connected=true;gTouchStateRequested=false;
}
static GestureControl gGesture;
static bool gGestureStateRequested=false,gGestureClientV2=false;
static void gestureSend(uint8_t mask,uint8_t token){gGv.gestureCommand(mask,token);}
static bool gestureStore(uint8_t mask){
 Preferences p;if(!p.begin("gesture",false))return false;
 uint8_t bytes[3]={2,mask,(uint8_t)(mask^0xA5)};
 bool ok=p.putBytes("config",bytes,sizeof(bytes))==sizeof(bytes);p.end();return ok;
}
static uint8_t gestureLoad(){
 Preferences p;if(!p.begin("gesture",true))return 7;
 uint8_t b[3]={0};bool ok=p.getBytesLength("config")==3 && p.getBytes("config",b,3)==3;p.end();
 if(!ok || b[2]!=uint8_t(b[1]^0xA5))return 7;
 if(b[0]==1 && b[1]<=3)return b[1]|4;
 return b[0]==2 && b[1]<=7 ? b[1] : 7;
}
static void pushGestureState(uint32_t now){
 static uint8_t previous[13]={0};static uint16_t revision=0;
 static uint32_t last=0;static bool wasConnected=false;
 if(!gBle.connected()){wasConnected=false;gGestureClientV2=false;return;}
 bool fresh=fpgaFresh() && gGesture.fresh(now);
 const bool modern=gGestureClientV2 && gGesture.caps==7;
 const uint8_t supported=modern?7:3;
 uint8_t flags=(fresh?1:0)|((fresh && gGesture.applied())?2:0)|
   ((gGesture.saved==gGesture.desired)?4:0)|(gGesture.result==1?8:0);
 uint8_t b[13]={0xA4,uint8_t(modern?2:1),uint8_t(gGesture.desired&supported),
   uint8_t(fresh?(gGesture.actual&supported):255),gGesture.result,flags,
   uint8_t(gGesture.client),uint8_t(gGesture.client>>8),0,0,uint8_t(fresh?supported:0),0,0};
 bool changed=memcmp(previous,b,13)!=0;
 if(wasConnected && !gGestureStateRequested && !changed && now-last<500)return;
 if(wasConnected && now-last<30)return;
 if(changed)++revision;memcpy(previous,b,13);b[8]=(uint8_t)revision;b[9]=(uint8_t)(revision>>8);
 for(unsigned i=0;i<12;i++)b[12]^=b[i];gBle.notifyStatus(b,13);
 last=now;wasConnected=true;gGestureStateRequested=false;
}

// Separate from the legacy 12-byte performance heartbeat: requested and actual settings
// must reach the phone even with the FPGA disconnected. Fits the default MTU.
// A0,version,volume,timbre,LED[5],flags,revision[2],appliedGain,appliedTimbre,
// optoPreset, reserved. flags: bit0 FPGA fresh, bit1 song playing,
// bit2 FPGA gain/timbre agree. LED fields describe requested settings, not a
// successful LP5860 write or a measured lamp state.
static bool gControlStateRequested = false;
static void pushControlStateToBle(uint32_t now) {
  static uint8_t previous[20] = {0};
  static bool sent = false, wasConnected = false;
  static uint16_t revision = 0;
  static uint32_t lastSentMs = 0;
  if (!gBle.connected()) { wasConnected = false; return; }
  const bool fresh = gGv.status.valid && (now - gGv.status.ms < 350);
  const uint8_t gain = engineGainForVolume(gCurVolume);
  const uint8_t preset = currentOptoPreset();
  const bool real=fresh && gPerformance.fresh(now);
  const uint8_t flags=(fresh?1:0)|(gSongs.playing()?2:0)|
    ((fresh && gGv.status.gain==gain && gGv.status.timbre==gCurTimbre)?4:0)|
    ((real && gPerformance.confirmed(now))?8:0)|((gPerformance.waiting && gPerformance.result==1)?16:0);
  uint8_t p[20]={0xA0,2,gCurVolume,gCurTimbre,gLed.mode,gLed.axis,gLed.speed,gLed.brightness,gLed.flags,
    flags,0,0,(uint8_t)(fresh?gGv.status.gain:255),(uint8_t)(fresh?gGv.status.timbre:255),preset,
    gPerformance.desired.mode,(uint8_t)(real?gPerformance.actual.mode:255),
    (uint8_t)((gPerformance.operation<<4)|(gPerformance.result&15)),gPerformance.ticket,
    (uint8_t)(real?((gGv.param.detected<<4)|gGv.param.emitted):0)};
  const bool changed = !sent || memcmp(previous, p, sizeof(p)) != 0;
  if (wasConnected && !gControlStateRequested && !changed && now - lastSentMs < 500) return;
  if (wasConnected && now - lastSentMs < 30) return;
  if (changed) ++revision;
  memcpy(previous, p, sizeof(p)); // cache without revision to compare settings
  p[10] = (uint8_t)revision;
  p[11] = (uint8_t)(revision >> 8);
  gBle.notifyStatus(p, sizeof(p));
  sent = wasConnected = true;
  gControlStateRequested = false;
  lastSentMs = now;
}

static void onScreenReset() {
  Serial.println("[屏幕] 收到 00 00 00 FF FF FF（屏幕清零/分帧标记）");
}

static void onScreenBoot() {
  if(gPower.state==PowerManager::SLEEP){gScreen.setPowerSleeping(false);gScreen.setPowerSleeping(true);return;}
  Serial.println("[屏幕] 开机成功（88 FF FF FF）");
  gScreen.keepAwake();gScreen.raw("sendxy=1");
  gScreen.setVolume(gCurVolume);
  gScreen.setTimbreNameIdx(gCurTimbre);
  pushLedConfigToScreen();
  sendLedConfigToFpga();
  gScreen.gotoPage("main");
  requestOptoParamsForScreen();
  Serial.printf("[屏幕] 已下发 音量=%u/音色=%u + 切到 main\n",
                (unsigned)gCurVolume, (unsigned)gCurTimbre);
}

static void onScreenTimbre(uint8_t idx) {
  if(!powerInputAllowed())return;
  const uint32_t now=millis();
  if(gGv.model.seq){
    gModel.request(idx,0,now,fpgaFresh()&&gModel.fresh(now));
  }else if(idx<2){
    gCurTimbre=idx;
    gScreen.setTimbreNameIdx(idx);
    downloadTimbre(idx);
  }
  gModelStateRequested=true;
}

// ---------------- 功能④：`page_cal` 那 5 个老按钮（AA 04 xx）----------------
// ★ 逆向事实（docs/屏幕工程解析.md §3.3 第 5 页）——**映射必须服从这些事实**：
//     b0 → printh AA 04 00; page page0      ← 00 是"返回主页"的**伴随帧**，不是命令
//     b1 → printh AA 04 00; page page_timbre ← 同上
//     b2 → printh AA 04 02
//     b3 → printh AA 04 03
//     b4 → printh AA 04 04
//     b5 → printh AA 04 01
//     tm0（定时器）**周期性地** printh AA 04 01   ← ★ 这条决定了 01 绝不能映射成"动作"
//   所以：
//     · 00 只能当"用户在离开校准页"的信号（本函数里只记一下，不做动作）；
//     · 01 是心跳 ⇒ 用它做"顺手刷新一次参数回读"，但必须**限速**
//       （即使高速串口也限速，避免重复回读占用屏幕脚本）；
//     · 02/03/04 才当真正的按钮 ⇒ 映射到三档预设。
//   ⚠ 这三个按钮在 .HMI 里的**原始标签未知**（逆向拿不到文字），下面的映射是
//     "先给一个可用且无破坏性的解释"。等你看到屏幕实物后，按标签调整这个 switch 即可。
static void onScreenCal(uint8_t cmd) {
  if(cmd!=1 && !powerInputAllowed())return;
  static uint32_t lastHb = 0;

  if (cmd == 0x00) {                       // 离开校准页（b0/b1 的伴随帧）
    Serial.println("[屏幕] AA 04 00（离开校准页，无动作）");
    return;
  }
  if (cmd == 0x01) {                       // tm0 周期心跳 → 限速发起一次参数回读
    const uint32_t now = millis();
    if ((now - lastHb) < 2000) return;     // ★ 限速 2 秒，避免心跳重复刷新参数
    lastHb = now;
    gGv.requestOptoParams();
    gOptParamWant  = true;
    gOptParamReqMs = now;
    gOptParamRetried = false;
    return;
  }
  if (cmd >= 0x02 && cmd <= 0x04) {        // 三个真按钮 → 预设 0/1/2
    const uint8_t ps = (uint8_t)(cmd - 0x02);          // 02→0 灵敏优先, 03→1 均衡, 04→2 抗误报
    PerformanceConfig next=gPerformance.desired;memcpy(next.values,kOptoPresets[ps],sizeof(next.values));
    gPerformance.start(PerformanceController::PRESET,next,++gPerformanceTicket,millis(),fpgaFresh());
    Serial.printf("[屏幕] AA 04 %02X → 加载预设 %u = %s\n",
                  (unsigned)cmd, (unsigned)ps, kOptoPresetNames[ps]);
    gOptParamWant  = true;
    gOptParamReqMs = millis();
    gOptParamRetried = false;
    gGv.requestOptoParams();               // 让屏幕自己显示新值
    return;
  }
  Serial.printf("[屏幕] AA 04 %02X（未定义，已忽略）\n", (unsigned)cmd);
}

static void onScreenBootAnim(uint8_t code) {
  Serial.printf("[屏幕] 开机动画事件 AA %02X\n", (unsigned)code);
}

static void onScreenValue(long v) {
  if(strcmp(gScreen.lastGetName(),"vbatmv")==0){gScreenBatterySupported=v>=0 && v<=8700;return;}
  if(strcmp(gScreen.lastGetName(),"vpowerstate")==0){gPowerScreenModern=v>=0 && v<=6;gScreen.setWakePage(255);powerChanged();return;}
  if(strcmp(gScreen.lastGetName(),"sleep")==0){
    if(millis()-gPowerScreenChangedMs>=200 && (v==0 || v==1)){
      // A poll of the still-bright display during entry is not a user wake.
      if(v==0 && gPower.state==PowerManager::ENTERING){gScreenSleepReported=0;gPower.screenObserved(gScreenDimReported>0,millis());}
      else onScreenPower(v==1);
    }
    return;
  }
  if(strcmp(gScreen.lastGetName(),"dim")==0){
    if(v>=0 && v<=100 && millis()-gPowerScreenChangedMs>=200){
      gScreenDimReported=(int)v;
      if(v==0)onScreenPower(true);else if(gScreenSleepReported==0)gPower.screenObserved(true,millis());
    }return;
  }

  if(!gPower.playable() && strcmp(gScreen.lastGetName(),"vavol")==0)return;
  if(strcmp(gScreen.lastGetName(),"vpmode")==0){
    gScreenModern=(v==0 || v==1 || v==255);
    return;
  }
  // get vavol（或调试用全局变量）的回复：0x71 + 4 字节小端
  // 读回只记录，并用它判断"是不是用户在屏幕上拖了滑块"；不能把一次
  // 未初始化的页面控件读数当成音量。
  if (v >= 0 && v <= 1000) {
    Serial.printf("[屏幕] %s 读回 = %ld\n", gScreen.lastGetName(), v);
    // ★只对 vavol 这条做音量同步：71 回复里不含变量名，不判断的话
    //   vatimbre 等其它全局变量的读回也会被当成"音量变了"。
    bool isVol = (strcmp(gScreen.lastGetName(), "vavol") == 0);
    if (isVol && v <= 100) {
      gScreenReportedVol = (uint8_t)v;
      if (gWroteVol != 0xFF && gScreenReportedVol != gWroteVol &&
          (millis() - gLastVolWriteMs) > 250) {
        Serial.printf("[屏幕] （音量被屏幕侧改动: %u -> %u）\n",
                      (unsigned)gCurVolume, (unsigned)gScreenReportedVol);
        if(!powerInputAllowed()){gScreen.setVolume(gCurVolume);return;}
        gCurVolume = gScreenReportedVol;   // 用户拖动 → 以屏幕为准
        sendVolumeToFpga(gCurVolume);      // ★转给 FPGA 执行
        gWroteVol = gCurVolume;             // 已接受这次改动，避免每次轮询重复下发
        gLastVolWriteMs = millis();
      } else if (gWroteVol == 0xFF) {
        gCurVolume = gScreenReportedVol;   // 还没下发过 → 采纳屏幕的值
        sendVolumeToFpga(gCurVolume);
        gWroteVol = gCurVolume;
        gLastVolWriteMs = millis();
      }
    } else if (strcmp(gScreen.lastGetName(), "vatimbre") == 0) {
      // A variable query is a display snapshot, not a new model selection.
      if(!gGv.model.seq)// A variable query is a display snapshot, not a new model selection.
      if(!gGv.model.seq)// A variable query is a display snapshot, not a new model selection.
      if(!gGv.model.seq)// A variable query is a display snapshot, not a new model selection.
      if(!gGv.model.seq)// A variable query is a display snapshot, not a new model selection.
      if(!gGv.model.seq)gCurTimbre = (v == 1) ? 1 : 0;
    }
  } else {
    Serial.printf("[屏幕] %s 回复 = %ld\n", gScreen.lastGetName(), v);
  }
}

// The 320x240 BackFix HMI has an unused slot on the right of the tone row.
static uint32_t screenBatteryProbeAt=0,screenBatteryPageAt=0;
static uint8_t screenBatteryStep=255,screenBatteryPercent=255,screenBatteryCharge=0;
static bool screenBatteryProbe=false;

static void onScreenPage(uint8_t page) {
  if(page!=1){
    screenBatteryStep=255;
  }else if(screenBatteryProbe && millis()-screenBatteryProbeAt<500){
    screenBatteryPageAt=millis();
    screenBatteryPercent=gBoardPower.percent(gBoardPower.millivolts);
    screenBatteryCharge=gBoardPower.charge;
    screenBatteryStep=0;
  }
  screenBatteryProbe=false;
  Serial.printf("[屏幕] 当前页号 = %u\n", (unsigned)page);
}

static void onScreenTouch(uint8_t page, uint8_t ctrl, uint8_t ev) {
  if(ev==1)onScreenPress();
  Serial.printf("[屏幕] 触摸 page=%u ctrl=%u ev=%u\n",
                (unsigned)page, (unsigned)ctrl, (unsigned)ev);
}


// ---------------- BLE 上报节流 ----------------
static uint32_t lastBleMs = 0;
static uint32_t lastStatusCountSeen = 0;

static void pushStatusToBle(uint32_t now) {
  if(!gPower.playable())return;
  if (!gBle.connected()) return;
  if ((now - lastBleMs) < 100) return;                  // 10 Hz 节流
  if (gGv.statusCount == lastStatusCountSeen) return; // 没有新的 SPI 状态帧
  lastStatusCountSeen = gGv.statusCount;
  lastBleMs = now;
  uint8_t p[12] = {0};
  p[0] = (uint8_t)(gGv.status.ms & 0xFF);
  p[1] = (uint8_t)((gGv.status.ms >> 8) & 0xFF);
  p[2] = (uint8_t)((gGv.status.ms >> 16) & 0xFF);
  p[3] = (uint8_t)((gGv.status.ms >> 24) & 0xFF);
  p[4] = gGv.status.note;                              // SPI 版没有旧品位位图
  p[8] = 0x0F;                                         // FPGA 状态帧已收到
  p[9] = (uint8_t)(((gGv.status.valid && now - gGv.status.ms < 350) ? 0x01 : 0x00));
  gBle.notifyStatus(p, sizeof(p));
  return;
}

static void pushEventsToBle() {
  if(!gPower.playable())return;
  static uint32_t sentFeedback=0;
  if(gBle.connected() && gGv.optel.noteFeedback && gGv.optel.seq!=sentFeedback){
    sentFeedback=gGv.optel.seq;
    const bool fresh=fpgaFresh() && millis()-gGv.optel.ms<350;
    uint8_t n[20]={0xA1,gGv.optel.feedbackVersion,(uint8_t)(fresh?1:0),gGv.status.timbre};
    for(int i=0;i<4;i++){n[4+i]=fresh?gGv.optel.frets[i]:255;n[8+i]=fresh?gGv.optel.midi[i]:255;n[12+i]=gGv.optel.counts[i];}
    n[16]=fresh?(gGv.optel.ev&15)|(gGv.optel.automatic<<4):0;n[17]=(uint8_t)gGv.optel.seq;
    n[18]=(uint8_t)gGv.optel.ms;n[19]=(uint8_t)(gGv.optel.ms>>8);
    gBle.notifyStatus(n,sizeof(n));
    return;
  }
  if(gGv.optel.noteFeedback)return;

  // SPI 光电帧的 ev 是四路事件位图；这里只在事件位图变化时上报一次，
  // 保持 BLE 事件特征的 9 字节格式，同时避免把 0x84 遥测周期当成拨弦事件。
  static uint8_t lastEv = 0;
  const uint8_t ev = gGv.optel.valid ? gGv.optel.ev : 0;
  const uint8_t rising = (uint8_t)(ev & (uint8_t)~lastEv);
  lastEv = ev;
  if (rising) {
    uint8_t p[9] = {0};
    p[0] = (uint8_t)(gGv.optel.ms & 0xFF);
    p[1] = (uint8_t)(gGv.optel.ms >> 8);
    p[2] = (uint8_t)(gGv.optel.ms >> 16);
    p[3] = (uint8_t)(gGv.optel.ms >> 24);
    for (uint8_t i = 0; i < 4; i++) {
      if (rising & (1u << i)) { p[4] = i; break; }
    }
    p[6] = gGv.status.gain;
    uint16_t peak = 0;
    for (int i = 0; i < 4; i++) if (gGv.optel.dg[i] > peak) peak = gGv.optel.dg[i];
    p[7] = (uint8_t)peak;
    p[8] = (uint8_t)(peak >> 8);
    gBle.notifyEvent(p, sizeof(p));
  }
  return;
}

// Full-band spectrum: generated in FPGA, validated/relayed here.
static AudioAnalysis gAnalysis,gAnalysisRx;
static uint16_t analysisTicket=0;
static uint32_t analysisRequestedMs=0;
static bool analysisWaiting=false;
static AudioAnalysisSender analysisSender;
static bool analysisPrimed=false;
static bool analysisCompact=false,analysisWantScope=false,analysisScopeFallback=false;
static int analysisSelection=-1;
static bool analysisSlowFallback=false;
static uint32_t analysisErrors=0,analysisBadStatus=0;
static ScreenScope gScreenScope;
static uint32_t screenToneHeartbeat=0;
static bool screenToneOpen=false;
static int screenToneDrawn=-1;
static bool screenEnvelopeOpen=false;
static const char* envelopeLabel(unsigned i,unsigned v){
  static const char* const attack[]={"ATK 0.50x","ATK 0.67x","ATK 0.80x","ATK 1.00x","ATK 1.33x","ATK 2.00x","ATK 2.67x","ATK 4.00x"};
  static const char* const body[]={"BODY 0.5x","BODY 1x","BODY 1.5x","BODY 2x"};
  static const char* const force[]={"PLUCK 25%","PLUCK 31%","PLUCK 38%","PLUCK 44%","PLUCK 50%","PLUCK 63%","PLUCK 75%","PLUCK 100%"};
  return i==0?attack[v]:i==1?body[v]:force[v];
}

static bool screenToneActive(uint32_t now){
  return screenToneOpen && now-screenToneHeartbeat<1000;
}

static void drawScreenTones(uint32_t now){
  if(!screenToneActive(now) || !gPower.playable())return;
  const bool ready=fpgaFresh() && gModel.fresh(now);
  const unsigned actual=ready?gModel.actual:4;
  const bool newBank=ready && gModel.caps==7;
  const bool pending=ready && gModel.result==1;
  const bool envReady=envelopeReady(now);
  if(!envReady)screenEnvelopeOpen=false;
  const unsigned env=gGv.envelopeValue;
  const bool envWaiting=envelopeResult==1;
  const int key=screenEnvelopeOpen?64+int(env)+256*gGv.envelopeKey+8192*gGv.envelopeBank+32768*envWaiting+65536*(envelopeResult==6):actual+(newBank?8:0)+(pending?16:0)+(envReady?32:0);
  if(screenToneDrawn==key)return;
  screenToneDrawn=key;
  const char *oldNames[]={"UKULELE","GUITAR","METAL","SPRING"};
  const char *newNames[]={"UKULELE","GUITAR","KALIMBA","HALO BELL"};
  const char **names=ready&&!newBank?oldNames:newNames;
  char cmd[80],caption[28];
  const char* notes[]={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
  if(screenEnvelopeOpen){
    unsigned k=gGv.envelopeKey;
    const char* const bankNames[]={"UKE","GTR","KAL","BELL"};
    snprintf(caption,sizeof(caption),"%s %s%u%s",bankNames[gGv.envelopeBank],notes[k%12],4+k/12,envWaiting?" SYNC":envelopeResult==6?" FULL":" > NEXT");
  }
  snprintf(cmd,sizeof(cmd),"xstr 15,39,%u,23,0,32743,2179,0,1,1,\"%s\"",
           envReady?204:290,screenEnvelopeOpen?caption:pending?"SWITCHING":ready?names[actual]:"WAIT FPGA");
  gScreen.raw(cmd);
  if(envReady){
    snprintf(cmd,sizeof(cmd),"xstr 225,39,80,23,0,65535,8585,1,1,1,\"%s\"",screenEnvelopeOpen?"TONES":"ENV");
    gScreen.raw(cmd);
  }
  for(unsigned i=0;i<4;++i){
    const char* text=names[i];
    if(screenEnvelopeOpen){
      if(i==0)text=envelopeLabel(0,env&7);
      if(i==1)text=gGv.envelopeBank<2?envelopeLabel(1,(env>>3)&3):"BODY N/A";
      if(i==2)text=envelopeLabel(2,env>>5);
      if(i==3)text="RESET NOTE";
    }
    snprintf(cmd,sizeof(cmd),"xstr %u,%u,140,48,0,65535,%u,1,1,1,\"%s\"",
             15+150*(i%2),72+58*(i/2),!screenEnvelopeOpen&&i==actual?2348:8585,text);
    gScreen.raw(cmd);
  }
}
static bool scopeNext=false;
static void onScreenScope(uint8_t mode){
  if(mode<3)screenToneOpen=false;
  if(mode==3){gScreenScope.stop();if(gPower.playable())gScreen.gotoPage("main");return;}
  if(mode==4){if(gPower.playable() && gScreenScope.mode<3)gScreenScope.heartbeat=millis();return;}
  if(gPower.playable())gScreenScope.select(mode,millis());
}
static void resetAudioAnalysis(){gAnalysis.reset();gAnalysisRx.reset();analysisWaiting=false;analysisPrimed=false;analysisSender.reset();analysisRequestedMs=millis();}
static void feedAnalysis(const uint8_t*p,size_t n){
  if(!analysisWaiting || !gPower.playable())return;
  if(!gAnalysisRx.accept(p,n,analysisTicket)){
    if(gSpi.clockHz()>250000){analysisSlowFallback=true;gSpi.setClock(250000);}
    gAnalysisRx.reset();analysisWaiting=false;analysisRequestedMs=millis();return;
  }
  if(gAnalysisRx.complete)analysisWaiting=false;
}
static void processWaveForSpectrum(uint32_t now){
  if(gSpi.clockHz()>250000 && (gGv.errFrames!=analysisErrors || gSpi.badStatus!=analysisBadStatus)){
    analysisSlowFallback=true;gSpi.setClock(250000);gAnalysisRx.reset();
    analysisWaiting=false;analysisRequestedMs=now;
  }
  const bool screen=gPower.playable() && gScreenScope.active(now);
  if(!gBle.connected()){analysisCompact=false;analysisWantScope=false;analysisSelection=-1;}
  const bool ble=gBle.connected() && analysisSelection!=3;
  if(!gPower.playable() || !fpgaFresh() || (!ble && !screen)){
    gSpi.setClock(250000);
    if(!gBle.connected()){analysisSlowFallback=false;analysisCompact=false;analysisWantScope=false;analysisScopeFallback=false;analysisSelection=-1;}
    analysisErrors=gGv.errFrames;analysisBadStatus=gSpi.badStatus;
    resetAudioAnalysis();return;
  }
  if(screen)gScreenScope.pump([now](const char *cmd){return gScreen.tryScopeCommand(cmd,now);});
  if(!ble && !analysisSender.done())analysisSender.reset();
  analysisErrors=gGv.errFrames;analysisBadStatus=gSpi.badStatus;
  if(!analysisSlowFallback)gSpi.setClock(1000000);
  // Separate RX/TX snapshots allow the next FPGA capture while BLE publishes
  // the preceding block; neither snapshot can overwrite the other.
  bool promoted=gAnalysisRx.complete && analysisSender.done();
  if(promoted){
    if(screen)gScreenScope.load(gAnalysisRx,now);
    if(ble && (analysisWantScope ? gAnalysisRx.scopeOnly || analysisScopeFallback || !screen : !gAnalysisRx.scopeOnly)){
      gAnalysis=gAnalysisRx;
      analysisSender.begin(&gAnalysis,analysisCompact,analysisSelection);
    }
    gAnalysisRx.reset();
  }
  if(analysisWaiting && scopeNext && !analysisScopeFallback && now-analysisRequestedMs>500){analysisScopeFallback=true;gAnalysisRx.reset();analysisWaiting=false;analysisPrimed=false;}
  if(analysisWaiting && now-analysisRequestedMs>3000){gAnalysisRx.reset();analysisWaiting=false;analysisRequestedMs=now;}
  if(!analysisWaiting && !gAnalysisRx.complete && (!analysisPrimed || promoted || now-analysisRequestedMs>=700)){
    const bool wantsScope=(ble&&analysisWantScope)||(screen&&gScreenScope.mode!=0);
    const bool wantsFft=(ble&&!analysisWantScope)||(screen&&gScreenScope.mode==0);
    scopeNext=!analysisScopeFallback && wantsScope && (!wantsFft || !scopeNext);
    gAnalysisRx.reset();analysisWaiting=true;analysisPrimed=true;analysisRequestedMs=now;
    gGv.requestAnalysis(++analysisTicket,scopeNext);
  }
  analysisSender.pump(gAnalysis,gBle.analysisPacketCapacity(),now,
                     [](const uint8_t*p,size_t n){return gBle.notifySpectrum(p,n);});
}

// ---------------- Coupled screen / FPGA standby ----------------
static bool powerMinutesValid(unsigned m){return m==0||m==3||m==5||m==10||m==15||m==30;}
static void powerSend(uint8_t op,uint16_t id,uint16_t epoch){gGv.powerCommand(op,id,epoch);}
static bool powerSensorsReady(bool enabled,uint32_t now){return gBoardPower.infraredReady(enabled,now);}
static void pushBatteryState(uint32_t now,bool force){
  static uint32_t sent=0,previous=0xffffffff;
  const uint16_t mv=gBoardPower.millivolts;
  const uint8_t charge=gBoardPower.charge,infrared=gBoardPower.infrared;
  const uint32_t state=mv | (uint32_t(charge)<<16) | (uint32_t(infrared)<<24);
  if(!gBle.connected() || (!force && now-sent<2000 && (state==previous || now-sent<250)))return;
  uint8_t packet[8]={0xb4,1,uint8_t(mv),uint8_t(mv>>8),gBoardPower.percent(mv),charge,infrared,0};
  for(unsigned i=0;i<7;i++)packet[7]^=packet[i];
  if(gBle.notifyStatus(packet,sizeof(packet))){sent=now;previous=state;}
}
static void pushPowerState(uint32_t now,bool force){
  static uint32_t sent=0;static uint16_t last=65535;
  if(!gBle.connected())return;
  if(!force && last==gPower.revision && now-sent<1000)return;
  uint8_t p[20]={0xA2,1,gPower.state,(uint8_t)(gPower.wantedSleep?4:0),gPower.result,gPower.origin,
    (uint8_t)gPower.clientSeq,(uint8_t)(gPower.clientSeq>>8),
    (uint8_t)gPower.revision,(uint8_t)(gPower.revision>>8),gPower.countdown,(uint8_t)(gPower.known?15:0),
    (uint8_t)gPower.done,(uint8_t)(gPower.done>>8),(uint8_t)gPower.fault,(uint8_t)(gPower.fault>>8),
    (uint8_t)gPower.epoch,(uint8_t)(gPower.epoch>>8),gPower.reason,(uint8_t)(gPower.timeoutMs/60000)};
  gBle.notifyStatus(p,20);sent=now;last=gPower.revision;
}
static void powerChanged(){
  const uint32_t now=millis();
  static uint8_t previous=255;
  const bool transition=previous!=gPower.state;
  if(gPower.state==PowerManager::ENTERING && gPower.fpgaAcked && !gScreen.powerSleeping()){
    gPowerScreenChangedMs=now;gScreen.setPowerSleeping(true);
  }
  if(transition){
    gPowerScreenChangedMs=now;
    if(gPower.state==PowerManager::ENTERING || gPower.state==PowerManager::WAKING){gScreenSleepReported=-1;gScreenDimReported=-1;}
    if(gPower.state==PowerManager::SLEEP){gScreen.setPowerSleeping(true);resetAudioAnalysis();}
    else if(gPower.state==PowerManager::WAKING || gPower.state==PowerManager::FAULT){
      gScreen.setPowerSleeping(false);gScreen.keepAwake();resetAudioAnalysis();
    }else if(gPower.state==PowerManager::DIM)gScreen.setBacklight(20);
    else if(gPower.playable()){
      gScreen.setBacklight(100);
      if(previous>=PowerManager::ENTERING){
        gScreen.setVolume(gCurVolume);gScreen.setTimbreNameIdx(gCurTimbre);
        pushLedConfigToScreen();pushPerformanceToScreen();
        if(gPowerScreenModern)gScreen.gotoPage("main");
        gPowerUiBlockUntil=now+200;
      }
    }
    if(gPower.state==PowerManager::ENTERING){
      screenToneOpen=false;gScreenScope.stop();
      if(gSongs.playing())gSongs.stop(gGv,currentLedRestore(),gBle);
      resetAudioAnalysis();
    }
    previous=gPower.state;
    Serial.printf("[POWER] state=%u result=%u desired=%u id=%u epoch=%u\n",gPower.state,gPower.result,gPower.wantedSleep,gPower.id,gPower.epoch);
  }
  if(gPowerScreenModern && gPower.state!=PowerManager::SLEEP){
    char c[48];
    const char*names[]={"vpowerstate","vpowerresult","vpowerack","vcountdown","vsleepmin","vpowercap"};
    unsigned values[]={gPower.state,gPower.result,gPower.clientSeq,gPower.countdown,(unsigned)(gPower.timeoutMs/60000),(unsigned)(gPower.known?15:0)};
    for(unsigned i=0;i<6;i++){snprintf(c,sizeof(c),"%s=%u",names[i],values[i]);gScreen.raw(c);}
    if(transition && gPower.state==PowerManager::NOTICE){screenToneOpen=false;gScreenScope.stop();gScreen.gotoPage("power_notice");}
    // The installed HMI has power_notice but no active power_wake page (old
    // cached objects in the HMI file are not compiled pages). Reuse the notice
    // page for wake/fault, with Continue handling both cancel and wake.
    if(transition && (gPower.state==PowerManager::WAKING || gPower.state==PowerManager::FAULT))gScreen.gotoPage("power_notice");
  }
  pushPowerState(now,true);
}
static bool configurePower(unsigned minutes,uint8_t source,uint16_t seq){
  if(!powerMinutesValid(minutes)){gPower.reject(source,seq,3);return false;}
  Preferences p;bool ok=p.begin("power",false);
  if(ok){ok=p.putUInt("mins",minutes)==4 && p.getUInt("mins",99)==minutes;p.end();}
  if(ok)gPower.configure((uint8_t)minutes,millis(),source,seq);
  else gPower.reject(source,seq,3);
  return ok;
}
static void onScreenPress(){
  screenBatteryStep=255;screenBatteryProbe=false;
  const uint32_t now=millis();
  if(gPower.playable()){gPower.activity(now);return;}
  if(gPower.state==PowerManager::ENTERING || gPower.state==PowerManager::SLEEP || gPower.state==PowerManager::FAULT){
    gPowerFirstTouch=true;gPowerUiBlockUntil=now+600;
    gPower.request(false,1,0,now,fpgaFresh());
  }
}
static void onScreenPower(bool asleep){
  uint32_t now=millis();
  if(asleep){
    if(gPower.state==PowerManager::WAKING && now-gPowerScreenChangedMs<1000){gPower.screenObserved(false,now);gScreen.keepAwake();return;}
    if(gPower.known && gPower.state!=PowerManager::ENTERING && gPower.state!=PowerManager::SLEEP)gPower.request(true,1,0,now,fpgaFresh());
    else if(!gPower.known)gScreen.keepAwake(); // Unsupported legacy FPGA must not strand the user.
  }else if(gPower.state==PowerManager::SLEEP || gPower.state==PowerManager::ENTERING){
    gPowerFirstTouch=true;gPowerUiBlockUntil=now+600;
    gPower.request(false,1,0,now,fpgaFresh());
  }
  gScreenSleepReported=asleep?1:0;
  gPower.screenObserved(!asleep && gScreenDimReported>0,now);
}
static void onScreenPowerCommand(const uint8_t*p,int n){
  if(!p || n!=9)return;
  uint16_t seq=(uint16_t)p[5]|((uint16_t)p[6]<<8);uint8_t op=p[4];
  if(op==0)pushPowerState(millis(),true);
  else if(op==1)gPower.request(true,1,seq,millis(),fpgaFresh(),gSongs.uploading());
  else if(op==2){
    // The wake page's automatic request must not steal the App transaction ID.
    if(gPower.waiting && !gPower.wantedSleep && gPower.origin==2){powerChanged();return;}
    gPower.request(false,1,seq,millis(),fpgaFresh());
  }
  else if(op==3){
    // An old TFT's Continue button still sends Cancel after countdown reaches
    // zero. Treat it as Wake once entry has begun, rather than ignoring it.
    gPower.continueUsing(1,seq,millis(),fpgaFresh());
  }
  else if(op==4)configurePower(p[7],1,seq);
}
static void tickScreenBattery(uint32_t now){
  if(!gScreenInitDone || !gPowerScreenModern || !gPower.playable()){
    screenBatteryStep=255;screenBatteryProbe=false;return;
  }
  if(now-screenBatteryProbeAt>=1500 && !gScreen.queryPending()){
    screenBatteryProbeAt=now;screenBatteryProbe=true;
    gScreen.requestPage();
  }
  if(screenBatteryStep==255 || now-screenBatteryPageAt>=180)return;
  const bool charging=screenBatteryCharge==2 || screenBatteryCharge==3;
  const unsigned width=screenBatteryPercent<=100?(screenBatteryPercent*23u+50u)/100u:0u;
  char cmd[72];
  switch(screenBatteryStep){
    case 0: snprintf(cmd,sizeof(cmd),"fill 232,29,82,21,6306");break;
    case 1: snprintf(cmd,sizeof(cmd),"draw 234,34,260,46,50712");break;
    case 2: snprintf(cmd,sizeof(cmd),"fill 261,38,3,5,50712");break;
    case 3:
      if(width)snprintf(cmd,sizeof(cmd),"fill 236,36,%u,9,32743",width);
      else {++screenBatteryStep;return;}
      break;
    case 4:
      if(screenBatteryPercent<=100)
        snprintf(cmd,sizeof(cmd),"xstr 267,29,46,21,1,65535,6306,2,1,1,\"%u%%\"",unsigned(screenBatteryPercent));
      else snprintf(cmd,sizeof(cmd),"xstr 267,29,46,21,1,50712,6306,2,1,1,\"--\"");
      break;
    case 5: if(!charging){screenBatteryStep=255;return;}snprintf(cmd,sizeof(cmd),"line 249,35,246,40,65535");break;
    case 6: snprintf(cmd,sizeof(cmd),"line 246,40,250,40,65535");break;
    case 7: snprintf(cmd,sizeof(cmd),"line 250,40,247,45,65535");break;
    default: screenBatteryStep=255;return;
  }
  if(gScreen.tryScopeCommand(cmd,now))++screenBatteryStep;
}

static void powerTick(uint32_t now){
  static uint32_t seen=0,lastScreenProbe=0,lastCap=0,lastNotes=0;
  static uint32_t batteryProbe=0,lastBatteryPush=0;
  static uint8_t counts[4]={0},frets[4]={0};static bool initialized=false;
  if(!gPower.waiting)gPower.screenRequired=gScreen.lastRxMs!=0;
  if(gGv.power.seq!=seen){seen=gGv.power.seq;gPower.observe(gGv.power,now);}
  if(gGv.optel.seq!=lastNotes){
    lastNotes=gGv.optel.seq;
    if(initialized && gPower.playable())for(int i=0;i<4;i++)
      if(counts[i]!=gGv.optel.counts[i] || frets[i]!=gGv.optel.frets[i]){gPower.activity(now);break;}
    for(int i=0;i<4;i++){counts[i]=gGv.optel.counts[i];frets[i]=gGv.optel.frets[i];}
    initialized=true;
  }
  bool busy=gSongs.playing() || gSongs.uploading() || gLedSavePending ||
    (gPerformance.waiting && (gPerformance.operation==3 || gPerformance.operation==4 || gPerformance.operation==6));
  gPower.tick(now,fpgaFresh(),busy);
  if(gPower.playable() || gPower.state==PowerManager::SLEEP)gBoardPower.pollInfrared(gPower.playable(),now);
  gBoardPower.pollBattery(now);
  pushBatteryState(now,false);
  if(gScreenInitDone && !gScreen.queryPending() && now-gPowerScreenChangedMs>=250 && now-lastScreenProbe>=(gPower.waiting?100u:1000u)){
    lastScreenProbe=now;
    // Polling the display never counts as use. It also catches lost native notifications.
    static bool checkDim=false;checkDim=!checkDim;gScreen.requestVar(checkDim ? "dim" : "sleep");
  }
  if(gScreenInitDone && !gPowerScreenModern && !gScreen.queryPending() && now-lastCap>=15000){
    lastCap=now;gScreen.requestVar("vpowerstate");
  }
  if(gScreenInitDone && gPowerScreenModern && gPower.playable() && !gScreen.queryPending()){
    if(!gScreenBatterySupported && now-batteryProbe>=15000){
      batteryProbe=now;gScreen.requestVar("vbatmv");
    }else if(gScreenBatterySupported && now-lastBatteryPush>=2000){
      lastBatteryPush=now;
      char cmd[28];
      snprintf(cmd,sizeof(cmd),"vbatmv=%u",gBoardPower.millivolts);gScreen.raw(cmd);
      snprintf(cmd,sizeof(cmd),"vbatpc=%u",gBoardPower.percent(gBoardPower.millivolts));gScreen.raw(cmd);
      snprintf(cmd,sizeof(cmd),"vcharge=%u",gBoardPower.charge);gScreen.raw(cmd);
    }
  }
  tickScreenBattery(now);
  pushPowerState(now);
}

// ---------------- 控制台命令 ----------------
static void printHelp() {
  Serial.println(" LED_STAT: verified LED register/data diagnostics (read-only)");
  Serial.println(" POWER_STAT / POWER_SLEEP / POWER_WAKE: standby diagnostics (no sensor bypass)");
  Serial.println();
  Serial.println("=== ESP32 帧链路控制台 ===");
  Serial.println(" h  帮助");
  Serial.println(" i  当前 SPI/FPGA、串口屏与 BLE 链路信息");
  Serial.println(" s  打印真实四弦品位与 MIDI 音名反馈");
  Serial.println(" p  请求 FPGA 参数回读（只读探活）");
  Serial.println(" t  打印 FPGA 实际光电参数");
  Serial.println(" f  恢复并保存光电出厂配置（等待真实结果）");
  Serial.println(" x  功能④离屏自检（合成 AA 0A / 0x84 帧，不依赖屏幕和接线）");
  Serial.println(" w  SPI 线路自检（收发计数 + FPGA 命令计数）");
  Serial.println(" v  屏幕变量读写自检（写 vavol/vatimbre 再逐个读回比对）");
  Serial.println(" q  屏幕命令探测器（逐条发候选指令，看屏幕怎么回）");
  Serial.println(" k  屏幕链路自检（写临时 vavol、读回并恢复，打印屏幕统计）");
  Serial.println();
}

// ---------------- SPI 链路自检（'w'）----------------
// 原来的实现是"采样 GPIO25 看线上有没有数据"（那时是 UART）。现在改成 SPI，
// 换成**真正测两个方向**：先记下 FPGA 报的 cmd_cnt，发一条命令，再看它有没有 +1。
//   · 能收到 0x81 帧本身     ⇒ FPGA → ESP32 通
//   · cmd_cnt 涨             ⇒ ESP32 → FPGA 通（命令被收到并通过 CRC）
// 两个方向都通才算链路 OK。这比"看电平跳不跳"信息量大得多。
static void cmdWireCheck() {
  Serial.println("\n=== SPI 链路自检 ===");
  Serial.printf("  引脚: SCK=%d MISO=%d MOSI=%d CS=%d  @250kHz 模式0\n",
                FpgaSpi::PIN_SCK, FpgaSpi::PIN_MISO, FpgaSpi::PIN_MOSI, FpgaSpi::PIN_CS);
  Serial.printf("  事务数=%lu  发=%lu 收=%lu  队列剩余=%d  丢弃=%lu\n",
                (unsigned long)gSpi.transactions, (unsigned long)gSpi.txBytes,
                (unsigned long)gSpi.rxBytes, gSpi.txPending(),
                (unsigned long)gSpi.txDrop);
  // ★ 状态字节非法次数：MISO 悬空（线没接/CS 没接）时会被上拉读到 0xFF，
  //   这个计数会飞快增长 —— 是最直接的线不对判据。
  Serial.printf("  状态字节非法 %lu 次%s\n", (unsigned long)gSpi.badStatus,
                gSpi.badStatus > 100 ? "  <== MISO 悬空/没接（或被上拉成全 1）" : "");

  // ---- 方向 1：FPGA -> ESP32 ----
  // ★ 先打一份收到的原始字节：状态字节合法但帧解析不出来时，只有看字节才能分清
  //   是"数据内容错"还是"解析器错"（踩过：MISO 没驱动时只说"不通"，看不出哪一头的问题）
  Serial.print("  收到的前 24 字节: ");
  int dumped = 0;
  {
    uint32_t td = millis();
    while (dumped < 24 && (millis() - td) < 200) {
      gSpi.poll();
      int b = gSpi.read();
      if (b < 0) { delay(1); continue; }
      Serial.printf("%02X ", (unsigned)b);
      dumped++;
    }
  }
  if (dumped == 0) Serial.print("(一个都没有)");
  Serial.println();

  gGv.status.valid = false;
  uint32_t t0 = millis();
  while (!gGv.status.valid && (millis() - t0) < 500) { gSpi.poll(); gGv.poll(); delay(1); }
  bool rxOk = gGv.status.valid;
  Serial.printf("  [FPGA->ESP32] %s", rxOk ? "通" : "不通");
  if (rxOk) {
    Serial.printf("（收到 0x81: note=%u gain=%u timbre=%u cmd_cnt=%u）",
                  gGv.status.note, gGv.status.gain, gGv.status.timbre, gGv.status.cmdCnt);
  }
  Serial.println();

  // ---- 方向 2：ESP32 -> FPGA ----
  uint8_t before = gGv.status.cmdCnt;
  Serial.printf("  [ESP32->FPGA] 发一条 GAIN 命令，看 cmd_cnt %u -> ?\n", before);
  sendVolumeToFpga(gCurVolume);          // 内容无所谓，只要是合法命令
  bool txOk = false;
  t0 = millis();
  while ((millis() - t0) < 800) {
    gSpi.poll(); gGv.poll();
    if (gGv.status.cmdCnt != before) { txOk = true; break; }
    delay(1);
  }
  Serial.printf("  [ESP32->FPGA] %s", txOk ? "通" : "不通");
  if (txOk) Serial.printf("（cmd_cnt 已变为 %u）", gGv.status.cmdCnt);
  else      Serial.print("（cmd_cnt 没变 -- 查 MOSI/SCK/CS 三根线、共地、CS 是否接对）");
  Serial.println();

  Serial.printf("\n  结论: %s\n\n",
                (rxOk && txOk) ? "两个方向都通 -> SPI 链路 OK"
                               : (rxOk ? "只能收不能发 -> 查 ESP32->FPGA 那三根线"
                                       : "收不到任何数据 -> 查 MISO / CS / 共地"));
}

// 把候选写法逐条发出去，记录屏幕的反应（回复/错误/沉默）。
// 这样不用靠猜，也不用一直让用户盯屏幕。
static const char *kProbe[][2] = {
    {"若回复 66 <页号> 说明 sendme 可用",        "sendme"},
    {"标准背光指令（全局，语法确定）",            "dim=50"},
    {"背光恢复",                                  "dim=100"},
    {"读音量全局变量",                            "get vavol"},
    {"读音色全局变量",                            "get vatimbre"},
    {"读实时心跳",                                "get vbeat"},
    {"读触发位图",                                "get vev"},
    {"写音量全局变量（临时）",                    "vavol=88"},
    {"写音色全局变量（临时）",                    "vatimbre=1"},
    {"切到主页",                                  "page main"},
    {"切到音色页",                                "page tone"},
    {"切到音量页",                                "page vol"},
    {"切到光电页",                                "page opt"},
    {"切到增益页",                                "page gain"},
    {"切到预设页",                                "page preset"},
    {"切到高级页",                                "page adv"},
    {"切到保存页",                                "page save"},
    {"切到自检页",                                "page check"},
    {"切回主页",                                  "page main"},
};
static const int kProbeN = sizeof(kProbe) / sizeof(kProbe[0]);
static int gProbeIdx = -1;
static uint32_t gProbeT0 = 0;
static char gProbeReply[96];

static void onScreenAny(const uint8_t *b, int n) {
  if(b&&n==7&&b[0]==0xAA&&b[1]==0x23){
    uint8_t x=0;for(unsigned i=0;i<6;++i)x^=b[i];
    if(x==b[6] && ((b[2]<=4&&b[3]<=((b[2]==2||b[2]==3)?96:127))||(b[2]==255&&b[3]==0))){
      gFxScreenAvailable=true;gFxStateRequested=true;
      if(b[2]<=4&&powerInputAllowed()){FxConfig c;c.mode=b[2];c.strength=b[3];fxRequest(c,uint16_t(b[4])|(uint16_t(b[5])<<8));}
    }return;
  }
  if(b && n==5 && b[0]==0xaa && b[1]==0x2d && b[2]==0x54 && b[3]<=1 && b[4]==(0x79^b[3])){
    if(b[3]){screenToneOpen=false;return;}
    const uint32_t now=millis();
    if(!screenToneActive(now)){screenToneDrawn=-1;screenEnvelopeOpen=false;}
    screenToneOpen=true;
    screenToneHeartbeat=now;
    gScreenScope.stop();
    drawScreenTones(now);
    return;
  }
  if(b && n==9 && (b[0]==0x67 || b[0]==0x68)){
    if(b[5])onScreenPress();
    else if(gPowerFirstTouch){gPowerFirstTouch=false;gPowerUiBlockUntil=millis()+200;}
    else if(b[0]==0x67 && gPower.playable() && screenToneActive(millis())){
      const unsigned x=unsigned(b[1])*256+b[2],y=unsigned(b[3])*256+b[4];
      const uint32_t now=millis();
      if(x>=225 && x<305 && y>=39 && y<62 && envelopeReady(now)){
        screenEnvelopeOpen=!screenEnvelopeOpen;
        if(screenEnvelopeOpen&&gModel.actual<4&&gModel.actual!=gGv.envelopeBank)
          requestEnvelope(0,gModel.actual,gGv.envelopeKey,ENVELOPE_FACTORY,0,now);
        drawScreenTones(now);
      }else if(screenEnvelopeOpen && x>=15 && x<219 && y>=39 && y<62){
        if(envelopeResult!=1 && powerInputAllowed())requestEnvelope(0,gGv.envelopeBank,(gGv.envelopeKey+1)%28,ENVELOPE_FACTORY,0,now);
      }else if(x>=15 && x<305 && y>=72 && y<178 && (x-15)%150<140 && (y-72)%58<48){
        const unsigned index=((y-72)/58)*2+(x-15)/150;
        if(screenEnvelopeOpen){
          if(envelopeResult!=1 && powerInputAllowed()){
            if(index<3 && (index!=1||gGv.envelopeBank<2)){
              unsigned shift=index==0?0:index==1?3:5,mask=index==1?3:7,base=gGv.envelopeValue;
              unsigned next=(((base>>shift)&mask)+1)&mask;
              requestEnvelope(1,gGv.envelopeBank,gGv.envelopeKey,(base&~(mask<<shift))|(next<<shift),0,now);
            }else if(index==3)requestEnvelope(1,gGv.envelopeBank,gGv.envelopeKey,ENVELOPE_FACTORY,0,now);
          }
        }else onScreenTimbre(index);
      }
    }
    else if(b[0]==0x67 && gPower.playable() && gScreenScope.active(millis())){
      const unsigned x=unsigned(b[1])*256+b[2],y=unsigned(b[3])*256+b[4];
      if(y>=201 && y<232 && x>=14 && x<306){
        const unsigned offset=x-14,mode=offset/75;
        if(offset%75<67){
          onScreenScope(mode);
          if(mode<3){
            const char *titles[]={"FFT / 0-8 kHz","PCM / 5.22 ms","XY / 0.328 ms"};
            char cmd[64];
            snprintf(cmd,sizeof(cmd),"xstr 17,32,286,18,0,32743,2179,0,1,1,\"%s\"",titles[mode]);
            gScreen.raw(cmd);
          }
        }
      }
    }
  }

  // 记录最近一帧的原始字节，供探测器判定
  int p = 0;
  for (int i = 0; i < n && p < (int)sizeof(gProbeReply) - 4; i++) {
    p += snprintf(gProbeReply + p, sizeof(gProbeReply) - p, "%02X ", b[i]);
  }
}

static void cmdProbe() {
  if (gProbeIdx >= 0) {
    Serial.println("（探测器正在运行）");
    return;
  }
  gProbeIdx = 0;
  gProbeT0 = millis();
  Serial.printf("\n=== 屏幕命令探测器：共 %d 条，每条等 400ms 看反应 ===\n", kProbeN);
  gProbeReply[0] = 0;
  gScreen.raw(kProbe[0][1]);
  Serial.printf("[%02d] 发: %-32s (%s)\n", 0, kProbe[0][1], kProbe[0][0]);
}

static void probeTick(uint32_t now) {
  if (gProbeIdx < 0) return;
  if (now - gProbeT0 < 400) return;
  Serial.printf("     回: %s\n", gProbeReply[0] ? gProbeReply : "（无回复）");
  gProbeIdx++;
  if (gProbeIdx >= kProbeN) {
    gProbeIdx = -1;
    Serial.println("=== 探测器结束（结果请连同上面每条的“回:”一起看）===\n");
    return;
  }
  gProbeReply[0] = 0;
  gScreen.raw(kProbe[gProbeIdx][1]);
  Serial.printf("[%02d] 发: %-32s (%s)\n", gProbeIdx, kProbe[gProbeIdx][1],
                kProbe[gProbeIdx][0]);
}

// ---------------- 屏幕变量读写自检（'v' 触发）----------------
// 逐个"写进去 → 读回来"比对，用来确认哪些变量名/写法是有效的。
// 新屏只有 vavol / vatimbre 这两条可写的用户变量；旧屏的 vachord 已移除。
static const int kVarCount = 2;
static const char *kVarName[kVarCount] = {"vavol", "vatimbre"};
static const long  kVarWrite[kVarCount] = {55, 1};
static int gVarStage = 0;
static int gVarIdx = 0;
static uint32_t gVarT0 = 0;
static long gVarRead[kVarCount] = {-1, -1};

static void cmdVarTest() {
  if (gVarStage != 0) {
    Serial.println("（变量自检正在运行）");
    return;
  }
  // ★必须等上电初始化（vavol=20/vatimbre=0，开机 3 秒后跑一次）完成再测，
  //   否则测试写入会被那次初始化覆盖 —— 实测踩过：写 55 却读回 70，
  //   看着像"写入无效"，其实是自己人打自己人。
  if (!gScreenInitDone) {
    Serial.println("请等上电初始化完成（开机 3 秒后）再按 v");
    return;
  }
  gVarStage = 1;
  gVarIdx = 0;
  gVarT0 = millis();
  gVarRead[0] = gVarRead[1] = -1;
  Serial.println("\n=== 屏幕变量读写自检：写 vavol=55 / vatimbre=1 再逐个读回 ===");
  // 先回到新屏主页停掉音量页定时器，再按队列顺序写两个全局变量，
  // 避免 vol 页脚本在读回前覆盖 vavol。
  // gotoPage 不能排在变量写入后，否则屏幕脚本可能在读回前覆盖变量。
  gScreen.gotoPage("main");
  char s[40];
  for (int i = 0; i < kVarCount; i++) {
    snprintf(s, sizeof(s), "%s=%ld", kVarName[i], kVarWrite[i]);
    gScreen.raw(s);
  }
  Serial.println("已写: vavol=55  vatimbre=1");
}

static void varTestTick(uint32_t now) {
  if (gVarStage == 0) return;
  switch (gVarStage) {
    case 1:                              // 等写入生效后开始逐个读
      if (now - gVarT0 > 1200) {
        gVarStage = 2;
        gVarT0 = now;
        gScreen.requestVar(kVarName[gVarIdx]);
      }
      break;
    case 2:                              // 等回复
      if (gScreen.lastGetMs() != 0 && (now - gVarT0) < 800) {
        gVarRead[gVarIdx] = gScreen.lastGetValue();
        gVarStage = 3;
        gVarT0 = now;
      } else if (now - gVarT0 > 800) {
        gVarRead[gVarIdx] = -1;          // 超时
        gVarStage = 3;
        gVarT0 = now;
      }
      break;
    case 3:                              // 下一个变量
      if (now - gVarT0 > 250) {
        gVarIdx++;
        if (gVarIdx < kVarCount) {
          gVarStage = 2;
          gVarT0 = now;
          gScreen.requestVar(kVarName[gVarIdx]);
        } else {
          gVarStage = 4;
          gVarT0 = now;
          Serial.println("变量                    写入   读回   结论");
          for (int i = 0; i < kVarCount; i++) {
            bool ok = (gVarRead[i] == kVarWrite[i]);
            Serial.printf("  %-18s %5ld  %5s  %s\n", kVarName[i], kVarWrite[i],
                          gVarRead[i] < 0 ? "超时" : String(gVarRead[i]).c_str(),
                          ok ? "✓ 可读写" : (gVarRead[i] < 0 ? "✗ 无回复" : "✗ 值不符"));
          }
        }
      }
      break;
    case 4:                              // 恢复我方状态
      if (now - gVarT0 > 400) {
        gVarStage = 0;
        gScreen.setVolume(gCurVolume);
        gScreen.setTimbreNameIdx(gCurTimbre);
        Serial.println("已恢复 vavol/vatimbre\n");
      }
      break;
    default: break;
  }
}

// 屏幕链路自检：闭环验证新屏的全局变量 vavol。
// 写一个临时值、恢复原值，再 get vavol；不依赖当前页面上的控件名。
static void cmdScreenTest() {
  Serial.printf("屏幕在线 : %s\n", gScreen.ready() ? "是" : "否");
  Serial.printf("  RX 字节 %lu  TX 字节 %lu  收帧 %lu  未识别 %lu  TJC错误 %lu\n",
                (unsigned long)gScreen.rxBytes, (unsigned long)gScreen.txBytes,
                (unsigned long)gScreen.frames, (unsigned long)gScreen.badFrames,
                (unsigned long)gScreen.tjcErrors);
  Serial.printf("  当前音色 %u   我方音量 %u   屏幕回读 %s\n",
                (unsigned)gCurTimbre, (unsigned)gCurVolume,
                gScreenReportedVol == 0xFF ? "未读到" : String((unsigned)gScreenReportedVol).c_str());
  if (gScreenTestStage == 0) {
    gScreenTestSavedVol = gCurVolume;
    gScreenTestProbeVol = (gCurVolume == 33) ? 34 : 33;
    gScreenTestStage = 1;
    Serial.printf("开始屏幕闭环自检（vavol=%u → %u → 恢复）…\n",
                  (unsigned)gScreenTestSavedVol, (unsigned)gScreenTestProbeVol);
  } else {
    Serial.println("（自检正在进行中）");
  }
}

static void cmdInfo() {
  Serial.println("---- 当前 SPI 链路 ----");
  Serial.printf("FPGA: %s, status_frames=%lu good=%lu bad=%lu age_ms=%lu\n",
    fpgaFresh()?"ONLINE":"OFFLINE",(unsigned long)gGv.statusCount,
    (unsigned long)gGv.okFrames,(unsigned long)gGv.errFrames,
    (unsigned long)(millis()-gGv.status.ms));
  Serial.printf("SPI: hz=%lu tx=%lu rx=%lu dropped=%lu bad_status=%lu pending=%d\n",
    (unsigned long)gSpi.clockHz(),(unsigned long)gSpi.txBytes,(unsigned long)gSpi.rxBytes,
    (unsigned long)gSpi.txDrop,(unsigned long)gSpi.badStatus,gSpi.txPending());
  Serial.printf("Screen: ready=%u age_ms=%lu BLE=%u\n",gScreen.ready(),
    (unsigned long)(millis()-gScreen.lastRxMs),gBle.connected());
}

static void cmdStatus() {
  if(!fpgaFresh() || !gGv.optel.noteFeedback || millis()-gGv.optel.ms>=350){
    Serial.println("[状态] FPGA 音符反馈离线或过期");return;
  }
  // The feedback protocol is A/E/C/G, unlike visual G/C/E/A order.
  Serial.printf("四弦品位 A/E/C/G: %u %u %u %u; MIDI: %u %u %u %u; accepted=0x%02X\n",
    gGv.optel.frets[0],gGv.optel.frets[1],gGv.optel.frets[2],gGv.optel.frets[3],
    gGv.optel.midi[0],gGv.optel.midi[1],gGv.optel.midi[2],gGv.optel.midi[3],gGv.optel.ev);
}

static void cmdPing() {
  // A read-only query: the old GvLink::ping() disables vibrato.
  gGv.requestOptoParams();
  Serial.println("[探活] 已请求 FPGA 参数回读；发送请求不代表收到应答，使用 i 查看实际链路");
}

static void cmdLegacyCalibration() {
  Serial.println("[兼容] 旧 UART CDC 标定协议已停用；请用 App/屏幕光电参数页，或 OPTO_SAVE/OPTO_LOAD/OPTO_DEFAULT/OPTO_FACTORY");
}

static void cmdDumpTable() {
  if(!fpgaFresh() || !gGv.param.complete || millis()-gGv.param.ms>=1000){
    gGv.requestOptoParams();Serial.println("[参数] 已请求新回读，稍后再输入 t；不显示过期镜像");return;
  }
  Serial.printf("[参数] FPGA 实际模式=%u\n",gGv.param.mode);
  for(unsigned pid=0;pid<OPT_PID_COUNT;++pid)
    Serial.printf("pid%u=%lu\n",pid,(unsigned long)gGv.param.v[pid]);
}

static void cmdTimbreReload() {
  Serial.printf("[音色] 重发 FPGA ROM 音色选择 idx=%u\n",gCurTimbre);
  downloadTimbre(gCurTimbre);
}

static void cmdFactory() {
  PerformanceConfig next;
  gPerformance.start(PerformanceController::FACTORY_SAVE,next,++gPerformanceTicket,millis(),fpgaFresh());
  Serial.println("[出厂] 已交给统一状态管理，成功以 FPGA 回读和 NVS 保存结果为准");
}

static bool padConsolePending=false;
// Explicitly requested, bounded read-only optical logging; inactive at boot.
// These are telemetry samples, not the ADC-rate peak at the detection instant.
static uint32_t optoWatchUntil=0,optoWatchSeen=0,optoWatchPrint=0;
static uint16_t optoWatchMax[4]={0,0,0,0};
static void optoConsoleStat(){
  const auto& p=gGv.param;
  Serial.printf("OPTO_STAT fresh=%u mode=%u param_age=%lu th=%lu/%lu/%lu/%lu gain=%lu/%lu/%lu/%lu persist=%lu cool=%lu detected=%u accepted=%u emitted=%u last_peak=%u\n",
    fpgaFresh()&&p.valid&&p.complete,p.mode,(unsigned long)(millis()-p.ms),
    (unsigned long)p.v[0],(unsigned long)p.v[1],(unsigned long)p.v[2],(unsigned long)p.v[3],
    (unsigned long)p.v[4],(unsigned long)p.v[5],(unsigned long)p.v[6],(unsigned long)p.v[7],
    (unsigned long)p.v[12],(unsigned long)p.v[13],p.detectedCount,p.acceptedCount,p.emittedCount,p.peak);
}
static void optoConsoleTick(uint32_t now){
  if(!optoWatchUntil)return;
  if(int32_t(now-optoWatchUntil)>=0){optoWatchUntil=0;Serial.printf("OPTO_END telemetry_max=%u/%u/%u/%u\n",optoWatchMax[0],optoWatchMax[1],optoWatchMax[2],optoWatchMax[3]);return;}
  const auto& v=gGv.optel;
  if(!fpgaFresh()||!v.valid||uint32_t(now-v.ms)>350||v.seq==optoWatchSeen)return;
  optoWatchSeen=v.seq;
  for(unsigned i=0;i<4;++i)if(v.dg[i]>optoWatchMax[i])optoWatchMax[i]=v.dg[i];
  if(uint32_t(now-optoWatchPrint)<50&&!v.ev)return;
  optoWatchPrint=now;
  Serial.printf("OPTO t=%lu raw=%u/%u/%u/%u dg=%u/%u/%u/%u ev=%02X notes=%u/%u/%u/%u counts=%u/%u/%u/%u\n",
    (unsigned long)v.ms,v.raw[0],v.raw[1],v.raw[2],v.raw[3],v.dg[0],v.dg[1],v.dg[2],v.dg[3],v.ev,
    v.midi[0],v.midi[1],v.midi[2],v.midi[3],v.counts[0],v.counts[1],v.counts[2],v.counts[3]);
}
static uint8_t padConsoleToken=0,padConsoleChannel=0;
static uint32_t padConsoleAt=0,padConsoleSeen=0;
static void padConsoleTick(uint32_t now){
 if(!padConsolePending)return;
 const auto &v=gGv.padDiag;
 if(v.seq!=padConsoleSeen && v.token==padConsoleToken && v.channel==padConsoleChannel){
  padConsolePending=false;
  Serial.printf("PAD ch=%u raw=%u base=%u delta=%u over=%u hit=%u valid=%u pad_ok=%u sample=%u ids=%02X ready=%u token=%u age=%lu version=%u gesture=%u vib_flags=%02X owner=%u amp=%u threshold=%u model=%u\n",v.channel,v.raw,v.baseline,v.delta,v.flags&1,(v.flags>>1)&1,(v.flags>>2)&1,(v.flags>>3)&1,v.flags>>7,v.ids,v.ready,v.token,(unsigned long)(now-v.ms),v.version,v.gesture,v.vibratoFlags,v.owner,v.amplitude,v.threshold,v.model);
 }else if(uint32_t(now-padConsoleAt)>=2000){padConsolePending=false;Serial.println("PAD timeout: selected-channel diagnostic requires paired FPGA firmware");}
}
static bool ledConsolePending=false;
static uint8_t ledConsoleToken=0;
static uint32_t ledConsoleAt=0,ledConsoleSeen=0;
static void ledConsoleTick(uint32_t now){
 if(!ledConsolePending)return;
 if(gGv.ledDiag.seq!=ledConsoleSeen && gGv.ledDiag.token==ledConsoleToken){
  const auto &d=gGv.ledDiag;ledConsolePending=false;
  Serial.printf("LED_STAT flags=%02X control_errors=%u pwm_errors=%u bad_col=%u chip=%02X initial=%02X cfg3=%02X global=%02X tx_crc=%04X rx_crc=%04X fault=%02X age=%lu token=%u\n",d.flags,d.controlErrors,d.pwmErrors,d.badColumn,d.chip,d.initial,d.config3,d.global,d.writeCRC,d.readCRC,d.fault,(unsigned long)(now-d.ms),d.token);
 }else if((uint32_t)(now-ledConsoleAt)>=2000){ledConsolePending=false;Serial.println("LED_STAT timeout: no matching FPGA reply; update paired FPGA/ESP32 firmware");}
}

static void handleConsole() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\r' || c == '\n') {
      line.trim();
      if (line.length()) {
        if(line=="OPTO_STAT"){optoConsoleStat();line="";continue;}
        if(line=="BATTERY_STAT"){
          Serial.printf("BATTERY_STAT mv=%u estimate=%u charge=%u charger_pins=%u infrared=%u charger_read_failures=%u\n",
                        gBoardPower.millivolts,gBoardPower.percent(gBoardPower.millivolts),
                        gBoardPower.charge,gBoardPower.chargePins,gBoardPower.infrared,gBoardPower.chargeReadFailures);
          line="";continue;
        }
        if(line.startsWith("OPTO_WATCH:")){
          const char* p=line.c_str()+11;char* end=nullptr;unsigned long seconds=strtoul(p,&end,10);
          if(!*p||*p<'0'||*p>'9'||*end||seconds>30)Serial.println("OPTO invalid: OPTO_WATCH:0 through OPTO_WATCH:30");
          else{optoWatchUntil=seconds?millis()+uint32_t(seconds)*1000:0;optoWatchSeen=gGv.optel.seq;optoWatchPrint=0;memset(optoWatchMax,0,sizeof(optoWatchMax));optoConsoleStat();}
          line="";continue;
        }
        if(line=="FX_STAT") {Serial.printf("FX actual=%u configured=%u,%u gain=%u flags=%u fresh=%u result=%u\n",gFx.actual,gFx.configured.mode,gFx.configured.strength,gFx.gain,gFx.flags,fpgaFresh()&&gFx.fresh(millis()),gFx.result);line="";continue;}
        else if(line.startsWith("FX:")){Serial.println("FX legacy command rejected; use paired FX3 firmware/app");line="";continue;}
        else if(line.startsWith("FX3:")){FxConfig c;uint16_t ticket=0;if(parseFxCommand(line.c_str(),c,ticket))fxRequest(c,uint16_t(ticket|32768));else Serial.println("FX invalid");line="";continue;}
        else if(line=="TOUCH_STAT") {
          Serial.printf("TOUCH_STAT build=%s_%s ble=%u fpga_online=%u known=%u fresh=%u desired=%u,%u,%u actual=%u,%u,%u sensor_flags=%u result=%u waiting=%u fpga_seq=%lu fpga_age=%lu ble_accepted=%lu ble_rejected=%lu packet=",__DATE__,__TIME__,gBle.connected(),fpgaFresh(),gTouch.known,gTouch.fresh(millis()),gTouch.desired.threshold,gTouch.desired.fast,gTouch.desired.grace,gTouch.actual.threshold,gTouch.actual.fast,gTouch.actual.grace,gTouch.sensorFlags,gTouch.result,gTouch.waiting,(unsigned long)gGv.touch.seq,(unsigned long)(millis()-gGv.touch.ms),(unsigned long)gTouchBleAccepted,(unsigned long)gTouchBleRejected);
          for(unsigned i=0;i<20;++i)Serial.printf("%02X",gTouchLastPacket[i]);Serial.println();
          line="";continue;
        }
        if(line=="GESTURE_STAT"){
          Serial.printf("GESTURE_STAT desired=%u actual=%u caps=%u known=%u fresh=%u waiting=%u result=%u fpga_seq=%lu fpga_age=%lu\n",gGesture.desired,gGesture.actual,gGesture.caps,gGesture.known,gGesture.fresh(millis()),gGesture.waiting,gGesture.result,(unsigned long)gGv.gesture.seq,(unsigned long)(millis()-gGv.gesture.ms));
          line="";continue;
        }
        if(line.startsWith("PAD:")){
          const char *n=line.c_str()+4;char *end=nullptr;
          unsigned long channel=strtoul(n,&end,10);
          if(!*n||*n<'0'||*n>'9'||*end||channel>=97)Serial.println("PAD invalid: use PAD:0 through PAD:96");
          else if(padConsolePending)Serial.println("PAD pending");
          else if(!fpgaFresh())Serial.println("PAD offline");
          else{padConsoleChannel=uint8_t(channel);++padConsoleToken;padConsoleSeen=gGv.padDiag.seq;padConsoleAt=millis();padConsolePending=true;gGv.requestPadDiag(padConsoleChannel,padConsoleToken);}
          line="";continue;
        }
        if(line=="LED_STAT") {
          if(ledConsolePending)Serial.println("LED_STAT pending");
          else if(!fpgaFresh())Serial.println("LED_STAT offline");
          else{++ledConsoleToken;ledConsoleSeen=gGv.ledDiag.seq;ledConsoleAt=millis();ledConsolePending=true;gGv.requestLedDiag(ledConsoleToken);}
          line="";continue;
        }
        if(line=="POWER_STAT") {
          Serial.printf("POWER_STAT state=%u result=%u desired=%u waiting=%u supported=%u id=%u epoch=%u fpga_state=%u fpga_result=%u done=%04X fault=%04X lcd_sleep=%d lcd_dim=%d fpga_online=%u lcd_age=%lu\n",gPower.state,gPower.result,gPower.wantedSleep,gPower.waiting,gPower.known,gPower.id,gPower.epoch,gGv.power.state,gGv.power.result,gPower.done,gPower.fault,gScreenSleepReported,gScreenDimReported,fpgaFresh(),(unsigned long)(millis()-gScreen.lastRxMs));
          line="";continue;
        }
        if(line=="POWER_SLEEP" || line=="POWER_WAKE") {
          gPower.request(line=="POWER_SLEEP",0,0,millis(),fpgaFresh(),gSongs.uploading());
          line="";continue;
        }
        char cmd = (char)tolower(line[0]);
        switch (cmd) {
          case 'h': printHelp(); break;
          case 'i': cmdInfo(); break;
          case 's': cmdStatus(); break;
          case 'p': cmdPing(); break;
          case 'c': cmdLegacyCalibration(); break;
          case 'd': cmdLegacyCalibration(); break;
          case 'u': cmdLegacyCalibration(); break;
          case 't': cmdDumpTable(); break;
          case 'f': cmdFactory(); break;
          case 'k': cmdScreenTest(); break;
          case 'q': cmdProbe(); break;
          case 'v': cmdVarTest(); break;
          case 'w': cmdWireCheck(); break;
          case 'x': cmdInjectOpto(); break;
          case 'y': cmdTimbreReload(); break;   // 重发音色选择，不传输音频或模型表
          default:  Serial.println("未知命令，按 h 查看帮助"); break;
        }
      }
      line = "";
    } else if (line.length() < 64) {
      line += c;
    }
  }
}

// ---------------- 初始化与主循环 ----------------
void setup() {
  Serial.begin(115200);
  gBoardPower.begin(millis());
  delay(300);
  Serial.println();
  Serial.println("ESP32 电子尤克里里 · SPI 控制与状态同步");

  // ★ 对端是 gv_host：**SPI**（用户/同学要求），引脚见 fpgaspi.h
  //   SCK=18 MISO=19 MOSI=23 CS=5 @250kHz，SPI 模式 0，固定 2 字节事务
  //   帧协议与原来的 UART 版逐字节相同（AA CMD LEN PAYLOAD... CRC）
  // 250 kHz 给 FPGA 的同步从机和杜邦线留最大裕量；带宽仍高于旧 UART，
  // 也足够承载状态/光电遥测和参数回读。
  gSpi.begin(250000);
  gGv.begin(&gSpi);
  gGv.onAnalysis = feedAnalysis;
  Serial.println("链路: gv_host @SPI 250kHz (SCK18/MISO19/MOSI23/CS5) —— 音量0x06/音色0x07/光电0x08/真实参数回读0x0B");

  // ---- 串口屏（UART1，115200 8N1）----
  gScreen.begin(22, 21, 115200);  // ★ TX=GPIO22 -> 屏幕 RX，RX=GPIO21 <- 屏幕 TX
                                  //   （2026-09-23 按同学《GPIO分配与选型说明》挪脚：
                                  //    18/19 让给 SPI；屏幕走 UART1 + GPIO 交换矩阵，任意脚可用）
  gScreen.onReset = onScreenReset;
  gScreen.onBoot = onScreenBoot;
  gPerformance.hooks(performanceApply,performanceRequest,performanceStore,performanceLoad);
  PerformanceConfig saved;bool migration=false;
  if(performanceLoad(saved,migration))gPerformance.desired=saved;
  gScreen.onTimbre = onScreenTimbre;
  gScreen.onCal = onScreenCal;
  gScreen.onBootAnim = onScreenBootAnim;
  gScreen.onValue = onScreenValue;
  gScreen.onPage = onScreenPage;
  gScreen.onTouch = onScreenTouch;
  gScreen.onAny = onScreenAny;
  gScreen.onErr = onScreenErr;
  gScreen.onOptoCfg = onOptoCfgFrame;   // ★ 功能④：屏幕参数帧
  gScreen.onSeqReq  = onSeqReq;         // ★ 功能④：刷屏握手
  gScreen.onLedCfg  = onScreenLedCfg;   // LED 灯阵模式
  {Preferences p;if(p.begin("guide",true)){gSongs.softGuide=p.getBool("soft",true);p.end();}}
  gModel.begin(modelLoad());gModel.send=modelSend;gModel.store=modelStore;
  gCapo.begin(capoLoad());gCapo.send=capoSend;gCapo.store=capoStore;
  gChain.begin(chainLoad());gChain.send=chainSend;gChain.store=chainStore;
  gFx.begin(fxLoad());gFx.send=fxSend;gFx.store=fxStore;
  gTouch.begin(touchLoad());gTouch.send=touchSend;gTouch.store=touchStore;
  gGesture.begin(gestureLoad());gGesture.send=gestureSend;gGesture.store=gestureStore;
  gPower.begin(millis(),(uint16_t)esp_random());gPower.send=powerSend;gPower.changed=powerChanged;
  gPower.sensorsReady=powerSensorsReady;
  {Preferences p;if(p.begin("power",true)){unsigned m=p.getUInt("mins",5);if(powerMinutesValid(m))gPower.timeoutMs=m*60000u;p.end();}}
  gScreen.onPowerState=onScreenPower;gScreen.onPowerCommand=onScreenPowerCommand;
  gScreen.onScope=onScreenScope;
  gScreen.raw("sendxy=1");
  gScreen.keepAwake();               // 也能唤醒在 ESP32 启动前已休眠的屏幕
  Serial.println("屏幕: UART1 TX=GPIO22 -> 屏幕RX, RX=GPIO21 <- 屏幕TX @115200");
  Serial.println("     屏幕 4 脚必须接外部 5V（不是 3.3V）");

  gBle.begin("Ukulele-ESP32");
  Serial.println("BLE 已启动，设备名 Ukulele-ESP32");

  if (gSongs.begin()) {
    Serial.printf("曲谱存储: LittleFS 已挂载，当前事件数=%u\n",
                  (unsigned)gSongs.eventCount());
  } else {
    Serial.println("曲谱存储: LittleFS 挂载失败，曲谱上传/播放不可用");
  }

  // FPGA ROM 保存两套音色；上电延迟恢复选择、灯光及 NVS 参数。
  gTimbreAutoDone = false;
  loadLedConfigFromFlash();

  printHelp();
}

void loop() {
  // ★ 上电 3 秒后执行一次音色恢复，
  //   同时等待 FPGA 的 100ms POR 和链路稳定，再恢复已保存的光电参数。
  if (!gTimbreAutoDone && millis() > 3000) {
    gTimbreAutoDone = true;
    Serial.printf("[音色] 上电恢复 FPGA 音色索引 %u\n",
                  (unsigned)gCurTimbre);
    if(!gGv.model.seq)downloadTimbre(gCurTimbre);
    // 灯阵参数由 ESP32 持久化；等 FPGA POR 和音色选择命令结束后再发，
    // 这样 0x17 不会与上电初始化事务互相挤占共享 SPI。
    sendLedConfigToFpga();
    gSpi.flush();
    // 恢复上次保存的光电参数，并等待 FPGA 实际回读。
    //   原因同音色：FPGA 的 o_th*/o_g*/o_glob* 上电是默认值，用户整定好的那套
    //   存在 ESP32 的 NVS 里，不主动下发就不会生效（表现为"校正白做了"）。
    if (!optoLoadFromFlash()) {
      Serial.println("[光电] 用 FPGA 上电默认参数（团队均衡档）；"
                     "在屏幕校正页点一次「保存」即可固化当前参数");
    }
    // 把 FPGA 实际生效的参数镜像到屏幕 vp0..vp16。即使没有 NVS，
    // 也要走一次 0x86 回读，避免屏幕初值与综合后的 FPGA 默认值漂移。
    requestOptoParamsForScreen();
  }
  gSpi.poll();                    // 跑 SPI 事务（收发字节）
  gGv.poll();                     // 把收到的字节喂进帧解析器
  gScreen.poll();

  // BLE 曲谱写特征只负责把分片交给主循环；文件系统操作放在这里，
  // 避免阻塞 BLE 回调线程，也不会把触摸扫描的串口回调打断。
  if (gBle.hasSongPacket()) {
    uint8_t packet[256];
    const size_t n = gBle.takeSongPacket(packet, sizeof(packet));
    if (n) gSongs.handlePacket(packet, n, gBle);
  }

  const uint32_t now = millis();
  ledConsoleTick(now);padConsoleTick(now);optoConsoleTick(now);
  powerTick(now);

  // FPGA reconfiguration clears its LED registers while the screen/ESP32
  // still show the saved selection. Reapply the small, idempotent command
  // without writing NVS. Song guidance owns mode 7 until playback ends.
  if (gTimbreAutoDone && !gSongs.playing() && !gBitmap.waiting &&
      (uint32_t)(now - gLedLastSendMs) >= 3000) {
    sendLedConfigToFpga(true);
  }

  // ---- ★ v80 功能④：0x86 参数回读帧到了就推上屏 ----
  //   gvlink 的 dispatch 没有回调口，所以用"param.ms 变了"当通知（等价于"新帧到了"）。
  //   500ms 超时保护：0x0B 发出去没回应（FPGA 没烧新固件 / SPI 不通）时不要永远挂着。
  static uint32_t performanceSeen=0;
  if(gGv.param.seq!=performanceSeen){
    performanceSeen=gGv.param.seq;
    gPerformance.observe(gGv.param.v,gGv.param.mode,gGv.param.token,gGv.param.complete,now);
    if(!gGv.param.complete && gPerformance.waiting){gPerformance.waiting=false;gPerformance.result=3;}
    if(gGv.param.complete)pushOptoParamsToScreen();
  }
  gPerformance.tick(now,fpgaFresh());
  static uint32_t gestureSeen=0;
  if(gestureSeen!=gGv.gesture.seq){gestureSeen=gGv.gesture.seq;
    gGesture.observe(gGv.gesture.mask,gGv.gesture.token,gGv.gesture.caps,now);}
  gGesture.tick(now,fpgaFresh());
  static uint32_t touchSeen=0;
  if(touchSeen!=gGv.touch.seq){touchSeen=gGv.touch.seq;TouchConfig c;c.threshold=gGv.touch.threshold;c.fast=gGv.touch.actualFast;c.grace=gGv.touch.grace;
    gTouch.observe(c,gGv.touch.requestedFast,gGv.touch.token,gGv.touch.flags,now);}
  gTouch.tick(now,fpgaFresh());
  static uint32_t fxSeen=0;
  if(fxSeen!=gGv.fx.seq){fxSeen=gGv.fx.seq;FxConfig c;c.mode=gGv.fx.mode;c.strength=gGv.fx.strength;
    gFx.observe(c,gGv.fx.actual,gGv.fx.gain,gGv.fx.flags,gGv.fx.token,now);}
  static uint32_t capoSeen=0;if(capoSeen!=gGv.capo.seq){capoSeen=gGv.capo.seq;gCapo.observe(gGv.capo.semitones,gGv.capo.token,gGv.capo.caps,now);}
  static uint32_t modelSeen=0;if(modelSeen!=gGv.model.seq){modelSeen=gGv.model.seq;
    if(modelReplyPending && gGv.model.token==modelWireToken)modelReplyPending=false;
    gModel.observe(gGv.model.semitones,gGv.model.token,gGv.model.caps,now);
    if(gModel.fresh(now)){uint8_t bank=gModel.actual;
      if(gCurTimbre!=bank){gCurTimbre=bank;gScreen.setTimbreNameIdx(bank);}}
  }
  if(!fpgaFresh() || now-modelWireStarted>=600)modelReplyPending=false;
  if(envelopeResult!=1)gModel.tick(now,fpgaFresh());
  drawScreenTones(now);
  gCapo.tick(now,fpgaFresh());
  static uint32_t chainSeen=0;if(chainSeen!=gGv.chain.seq){chainSeen=gGv.chain.seq;gChain.observe(gGv.chain.config,gGv.chain.mask,gGv.chain.flags,gGv.chain.armed,gGv.chain.token,now);}
  gChain.tick(now,fpgaFresh());
  if(!gChain.desired.enabled)gFx.tick(now,fpgaFresh());
  if(gMigrationNeeded && fpgaFresh() && gPerformance.confirmed(now) && !gPerformance.waiting){
    if(performanceStore(gPerformance.actual))gMigrationNeeded=false;
  }
  static uint32_t lastAudioSync=0;
  if(gPower.playable() && fpgaFresh() && now-lastAudioSync>=500){
    lastAudioSync=now;
    const uint8_t wanted=masterGainForVolume(gCurVolume);
    if(gGv.status.gain!=engineGainForVolume(gCurVolume))gGv.setGain(wanted);
    if(!gGv.model.seq && gGv.status.timbre!=gCurTimbre)gGv.setTimbre(gCurTimbre);
  }


  probeTick(now);
  varTestTick(now);

  // ---- 屏幕闭环自检状态机（'k' 触发）----
  // 新屏把音量放在全局变量 vavol，测试不依赖 vol 页的 h0 控件。
  switch (gScreenTestStage) {
    case 1:                                   // 写临时 vavol，让用户肉眼确认动态写入
      gScreenTestStage = 2;
      gScreenTestT0 = now;
      gScreen.setVolume(gScreenTestProbeVol);
      Serial.printf("[自检] 1/3 已写 vavol=%u\n", (unsigned)gScreenTestProbeVol);
      break;
    case 2:
      if (now - gScreenTestT0 > 1200) {
        gScreenTestStage = 3;
        gScreenTestT0 = now;
        gScreen.setVolume(gScreenTestSavedVol);
        Serial.printf("[自检] 2/3 已恢复 vavol=%u\n", (unsigned)gScreenTestSavedVol);
      }
      break;
    case 3:
      if (now - gScreenTestT0 > 1200) {
        gScreenTestStage = 0;
        gScreen.requestVolume();              // get vavol 读回
        Serial.println("[自检] 3/3 结束（已发 get vavol 读回，应等于原值）");
      }
      break;
    default: break;
  }

  // 上电 3 秒后下发一次初始状态（不依赖开机帧）。
  // 用已验证可靠的【命名全局变量】方式：vavol / vatimbre。
  if (!gScreenInitDone && now > 3000) {
    gScreenInitDone = true;
    if(gPower.playable())gScreen.keepAwake();             // 开机帧漏收或上电时序不同时，补一次常亮配置
    gScreen.setVolume(gCurVolume);
    gScreen.setTimbreNameIdx(gCurTimbre);
    gWroteVol = gCurVolume;
    gLastVolWriteMs = now;
    sendVolumeToFpga(gCurVolume);        // 开机把音量同步给 FPGA
    if(!gGv.model.seq)sendTimbreToFpga(gCurTimbre);
    Serial.printf("[屏幕] 已下发初始状态：vavol=%u vatimbre=%u\n",
                  (unsigned)gCurVolume, (unsigned)gCurTimbre);
  }

  // Probe the extended HMI only after initialization, with serialized get ownership.
  if(gScreenInitDone && gVarStage==0 && gScreenTestStage==0 && !gScreen.queryPending() &&
     (!gLastScreenCapProbe || now-gLastScreenCapProbe>=10000)){
    gLastScreenCapProbe=now;gScreen.requestVar("vpmode");
  }

  // 音量页只修改屏幕全局变量 vavol，不主动发二进制帧。定期 get 这个
  // 全局变量，才能把用户在屏幕上拖动/按键后的音量同步到 FPGA；get
  // 是异步回复，onScreenValue() 会用 lastGetName() 区分它与其它读回。
  // 变量读写自检会连续读取 vatimbre；这段时间暂停 vavol 轮询，
  // 避免多个 0x71 回复互相覆盖 lastGetName()。
  if (gPower.playable() && gScreenInitDone && !gScreen.queryPending() && gVarStage == 0 && gScreenTestStage == 0 &&
      (now - gLastVolReqMs) >= SCREEN_VOL_POLL_MS) {
    gLastVolReqMs = now;
    gScreen.requestVolume();
  }

  if (gBle.hasCommand()) {
    String c = gBle.takeCommand();
    c.trim();
    Serial.printf("[BLE 命令] %s\n", c.c_str());
    unsigned powerSeq=0,powerMinutes=0;int powerConsumed=-1;
    if(c=="POWER:QUERY") {pushPowerState(now,true);pushBatteryState(now,true);}
    else if(sscanf(c.c_str(),"POWER:WAKE,%u%n",&powerSeq,&powerConsumed)==1 && powerSeq<=65535 && powerConsumed==(int)c.length()){gPower.request(false,2,(uint16_t)powerSeq,now,fpgaFresh());}
    else if(sscanf(c.c_str(),"POWER:SLEEP,%u%n",&powerSeq,&powerConsumed)==1 && powerSeq<=65535 && powerConsumed==(int)c.length()){gPower.request(true,2,(uint16_t)powerSeq,now,fpgaFresh(),gSongs.uploading());}
    else if(sscanf(c.c_str(),"POWERCFG:%u,%u%n",&powerMinutes,&powerSeq,&powerConsumed)==2 && powerSeq<=65535 && powerConsumed==(int)c.length()){configurePower(powerMinutes,2,(uint16_t)powerSeq);}
    else if(c=="LM_GET"){gBitmap.queryMs=now-1000;}
    else if(c=="GUIDE_FADE_GET"||c=="GUIDE_FADE:0"||c=="GUIDE_FADE:1"){
      uint8_t result=0;
      if(c!="GUIDE_FADE_GET"){
        bool wanted=c.endsWith("1");Preferences p;
        if(!p.begin("guide",false))result=1;
        else {if(p.putBool("soft",wanted)!=1)result=1;p.end();}
        if(!result)gSongs.softGuide=wanted;
      }
      uint8_t b[5]={0xac,1,uint8_t(gSongs.softGuide),result,0};for(uint8_t i=0;i<4;++i)b[4]^=b[i];gBle.notifyStatus(b,5);
    }
    else if(c.startsWith("ENV:")){
      unsigned op=4,bank=4,midi=0,value=256,id=0;int consumed=-1;
      if(sscanf(c.c_str(),"ENV:%u,%u,%u,%u,%u%n",&op,&bank,&midi,&value,&id,&consumed)==5 &&
         op<=3&&bank<4&&midi>=60&&midi<=87&&value<256&&id>0&&id<=32767&&consumed==(int)c.length()){
        requestEnvelope(uint8_t(op),uint8_t(bank),uint8_t(midi-60),uint8_t(value),uint16_t(id),now);
      }
    }
    else if(c=="XC_GET"){gChainStateRequested=true;}
    else if(c=="ANALYSIS2:0" || c=="ANALYSIS2:1" || c=="ANALYSIS2:2"){
      analysisSelection=-1;analysisCompact=true;analysisWantScope=c!="ANALYSIS2:0";
    }
    else if(c.length()==11 && c.startsWith("ANALYSIS3:") && c[10]>='0' && c[10]<='3'){
      const int mode=c[10]-'0';
      if(mode!=analysisSelection){
        analysisSelection=mode;
        analysisWantScope=mode==1 || mode==2;
        analysisSender.reset();
        analysisPrimed=false;
      }
    }
    else if(c=="MODEL_GET"){gModelStateRequested=true;}
    else if(c=="CAPO_GET"){gCapoStateRequested=true;}
    else if(c=="FX_GET"){gFxStateRequested=true;}
    else if(c=="TOUCH_GET"){gTouchStateRequested=true;}
    else if(c=="GESTURE_GET" || c=="GESTURE_GET2"){
      if(c=="GESTURE_GET2")gGestureClientV2=true;
      gGestureStateRequested=true;
    }
    else if(c=="PRACTICE:1"){gPower.practice(now,true);}
    else if(c=="PRACTICE:0"){gPower.practice(now,false);}
    else if(c.startsWith("SP1:")){
      SongPlaybackCommand command;
      if(!SongPlaybackCommand::parse(c.c_str(),command))gSongs.playbackStatus(gBle,4,2,0);
      else if((command.op!=4&&!gPower.playable()) ||
              (command.op==5&&(!fpgaFresh()||!gCapo.fresh(now)||gGv.capo.version<3))){
        gSongs.playbackStatus(gBle,command.op==5?0:command.op,2,command.token);
      }else gSongs.control(command.op,command.rate,command.token,gGv,currentLedRestore(),gBle);
    }
    else if(!gPower.playable() && c!="STATE" && c!="INFO" && c!="SONG_INFO" && c!="SONG_STOP" && !c.startsWith("SP1:4,")){
      pushPowerState(now,true);Serial.println("[POWER] control blocked until wake is confirmed");
    }
    else if (c == "PING") {
      cmdPing();
    } else if (c == "INFO") {
      cmdInfo();
    } else if (c == "STATE") {
      gControlStateRequested = true;
    } else if (c == "CAL") {
      cmdLegacyCalibration();
    } else if (c == "DEFAULT") {
      cmdLegacyCalibration();
    } else if (c == "UPLOAD") {
      cmdLegacyCalibration();
    } else if (c == "TABLE") {
      cmdDumpTable();
    } else if (c == "FACTORY") {
      PerformanceConfig next;gPerformance.start(PerformanceController::FACTORY_SAVE,next,++gPerformanceTicket,now,fpgaFresh());
    } else if (c.startsWith("VOLUME:")) {
      gPower.activity(now);
      int v = c.substring(7).toInt();
      if (v < 0) v = 0;
      if (v > 100) v = 100;
      gCurVolume = (uint8_t)v;
      gScreen.setVolume(gCurVolume);
      gWroteVol = gCurVolume;
      gLastVolWriteMs = now;
      sendVolumeToFpga(gCurVolume);
    } else if (c.startsWith("TIMBRE:")) {
      int v = c.substring(7).toInt();
      if (v < 0) v = 0;
      if (v > 1) v = 1;
      onScreenTimbre((uint8_t)v);
    } else if(c.startsWith("LPB:")||c.startsWith("LP2:")||c.startsWith("LPC:")){
      if(gBitmap.waiting||gSongs.playing()){if(!gBitmap.waiting)gBitmap.result=6;}
      else {int r=gBitmap.assembler.accept(c.c_str(),now);
        if(r<0){gBitmap.result=3;gBitmap.client=gBitmap.assembler.ticket;gBitmap.assembler.receiving=false;}
        else if(r==1&&c.startsWith("LPB:")){gBitmap.client=gBitmap.assembler.ticket;gBitmap.result=1;}
        else if(r==2){gPower.activity(now);gBitmap.request(gBitmap.assembler.draft,gBitmap.assembler.ticket,now,fpgaFresh()&&gBitmap.assembler.draft.supported(gGv.bitmap.caps),gPower.playable(),false);}
      }
    } else if (c.startsWith("LED:")) {
      gPower.activity(now);
      int m = 0, a = 0, s = 24, b = 180, f = 0;
      if (sscanf(c.c_str(), "LED:%d,%d,%d,%d,%d", &m, &a, &s, &b, &f) == 5) {
        gBitmap.cancel();
        gLed.mode = (uint8_t)(m==8?8:(m < 0 ? 0 : (m > 6 ? 6 : m)));
        gLed.axis = (uint8_t)(a & 1);
        gLed.speed = (uint8_t)(s < 0 ? 0 : (s > 255 ? 255 : s));
        gLed.brightness = (uint8_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
        gLed.flags = (uint8_t)(f & 0xFF);
        gLedSavePending = true;
        gLedChangedMs = now;
        sendLedConfigToFpga();
        pushLedConfigToScreen();
      } else {
        Serial.println("LED 命令格式: LED:mode,axis,speed,brightness,flags");
      }
    } else if(c.startsWith("XC:")){
      ChainConfig next;uint16_t ticket=0;
      if(parseChainCommand(c.c_str(),next,ticket)){gPower.activity(now);gChain.request(next,ticket,now,fpgaFresh()&&gPower.playable()&&gChain.fresh(now));}
      else gChain.result=3;gChainStateRequested=true;
    } else if(c.startsWith("MODEL:")){
      unsigned n=0,id=0;int consumed=0;
      if(sscanf(c.c_str(),"MODEL:%u,%u%n",&n,&id,&consumed)==2&&n<=3&&id>0&&id<=32767&&consumed==(int)c.length()){
        gPower.activity(now);gModel.request(uint8_t(n),uint16_t(id),now,fpgaFresh()&&gPower.playable()&&gModel.fresh(now));
      }else gModel.result=3;gModelStateRequested=true;
    } else if(c.startsWith("CAPO:")){
      unsigned n=255,id=0;int consumed=-1;
      if(sscanf(c.c_str(),"CAPO:%u,%u%n",&n,&id,&consumed)==2&&n<=12&&id>0&&id<=32767&&consumed==(int)c.length()){
        gPower.activity(now);gCapo.request(uint8_t(n),uint16_t(id),now,fpgaFresh()&&gPower.playable()&&gCapo.fresh(now));
      }else gCapo.result=3;
      gCapoStateRequested=true;
    } else if(c.startsWith("FX:")){
      gFx.result=3;gFxStateRequested=true;
    } else if(c.startsWith("FX3:")){
      FxConfig next;uint16_t seq=0;if(parseFxCommand(c.c_str(),next,seq)&&seq>0&&seq<=32767)fxRequest(next,seq);else {gFx.result=3;gFxStateRequested=true;}
    } else if(c.startsWith("TC:")){
      TouchConfig next;uint16_t seq=0;
      if(parseTouchCommand(c.c_str(),next,seq)){
        gPower.activity(now);gTouch.request(next,seq,now,fpgaFresh());
      }else gTouch.result=3;
      gTouchStateRequested=true;
    } else if(c.startsWith("GESTURE:")){
      unsigned mask=255,seq=0;int consumed=-1;
      const bool modern=gGestureClientV2 && gGesture.caps==7;
      if(sscanf(c.c_str(),"GESTURE:%u,%u%n",&mask,&seq,&consumed)==2 && mask<=(modern?7u:3u) && seq<=65535 && consumed==(int)c.length()){
        if(!modern)mask|=gGesture.desired&4;
        gPower.activity(now);gGesture.request((uint8_t)mask,(uint16_t)seq,now,fpgaFresh());
      }else{gGesture.result=3;}
      gGestureStateRequested=true;
    } else if(c.startsWith("PLAYMODE:")){
      gPower.activity(now);
      int value=-1;unsigned seq=++gPerformanceTicket;
      int fields=sscanf(c.c_str(),"PLAYMODE:%d,%u",&value,&seq);
      if(fields>=1 && value>=0 && value<=1){PerformanceConfig next=gPerformance.desired;next.mode=(uint8_t)value;
        gPerformance.start(PerformanceController::MODE,next,(uint8_t)(128|(seq&127)),now,fpgaFresh());}
    } else if(c.startsWith("OPTO_PRESET:")){
      gPower.activity(now);
      int ps=-1;if(sscanf(c.c_str(),"OPTO_PRESET:%d",&ps)==1 && ps>=0 && ps<=3){
        PerformanceConfig next=gPerformance.desired;memcpy(next.values,kOptoPresets[ps],sizeof(next.values));
        gPerformance.start(PerformanceController::PRESET,next,++gPerformanceTicket,now,fpgaFresh());}
    } else if(c=="OPTO_GET"){
      requestOptoParamsForScreen();
    } else if(c=="OPTO_SAVE"){
      optoSaveToFlash();
    } else if(c=="OPTO_LOAD"){
      optoLoadFromFlash();
    } else if(c=="OPTO_DEFAULT" || c=="OPTO_FACTORY"){
      PerformanceConfig next=gPerformance.desired;memcpy(next.values,PERFORMANCE_FACTORY,sizeof(next.values));
      if(c=="OPTO_FACTORY")next.mode=0;
      gPerformance.start(c=="OPTO_FACTORY"?PerformanceController::FACTORY_SAVE:PerformanceController::DEFAULT_TEMP,next,++gPerformanceTicket,now,fpgaFresh());
    } else if (c == "SONG_PLAY") {
      gSongs.play(gGv, currentLedRestore(), gBle);
    } else if (c == "SONG_STOP") {
      gSongs.stop(gGv, currentLedRestore(), gBle);
    } else if (c == "SONG_CLEAR") {
      gSongs.clear(gBle);
    } else if (c == "SONG_INFO") {
      uint8_t p[6] = {0x90, 0x14, (uint8_t)(gSongs.eventCount() & 0xFF),
                      (uint8_t)(gSongs.eventCount() >> 8),
                      (uint8_t)gSongs.playing(), (uint8_t)gSongs.mounted()};
      gBle.notifySongStatus(p, sizeof(p));gSongs.playbackStatus(gBle);
    } else {
      Serial.println("支持: PING / INFO / STATE / TABLE / FACTORY / OPTO_SAVE / OPTO_LOAD / OPTO_DEFAULT / OPTO_FACTORY / SONG_PLAY / SONG_STOP / SONG_CLEAR / SONG_INFO");
    }
  }

  if(gSongs.instrument() && (!gBle.connected() || !fpgaFresh() || !gPower.playable()))gSongs.stop(gGv,currentLedRestore(),gBle);
  gSongs.tick(gGv, currentLedRestore(), gBle, millis());

  // Debounce five NVS writes until the LED controls stop moving. The live
  // command and both displays are updated first; intermediate edits need not
  // consume flash writes or delay subsequent controls.
  if (gLedSavePending && now - gLedChangedMs >= 600) {
    gLedSavePending = false;
    saveLedConfigToFlash();
  }
  bitmapTick(millis());envelopeTick(millis());
  pushChainState(millis());pushModelState(millis());pushCapoState(millis());pushFxState(millis());pushGestureState(millis());pushTouchState(millis());
  pushControlStateToBle(millis());
  pushStatusToBle(millis());
  pushEventsToBle();
  processWaveForSpectrum(millis());
  handleConsole();
  if(gPower.state==PowerManager::SLEEP)delay(4);
}

