// ============================================================
// screen_link.h — TJC3224T124_011 串口屏链路（UART1，115200 8N1）
//
// 屏幕工程自带的协议（从 屏幕代码.HMI 里逆向出来的，见 docs/屏幕工程解析.md）：
//
//   【屏幕 → ESP32】
//     00 00 00 FF FF FF          屏幕清零/分帧标记（Program.s 上电发一次）
//     88 FF FF FF                TJC 开机成功
//     AA 01 00..03               音色 0/1/2/3（尤克里里/吉他/电音/风琴）
//     AA 03                      开机动画满值
//     AA 04 00..04               校准页命令
//     71 xx xx xx xx FF FF FF    get 数值变量 的回复（4 字节小端）
//     65 page ctrl ev FF FF FF   触摸事件（若控件勾了"发送键值"）
//
//   【ESP32 → 屏幕】ASCII 指令 + FF FF FF 结束（TJC 标准字符串指令协议）
  //     page main / vavol=70 / dim=100
//
// ★ 分帧要点：AA 开头的 printh 帧【没有】结束符，而 TJC 回复【有】FF FF FF。
//   所以两种判据都要用：见到 FF FF FF 立刻收帧；否则靠空闲间隔收帧。
//
// ★ 中文陷阱：屏幕工程是 GBK 编码，Arduino 只能发 UTF-8，所以
//   【ESP32 不要往屏幕发中文】。音色名等中文已写死在页面脚本里，只发索引即可。
// ============================================================
#pragma once
#include <Arduino.h>

class ScreenLink {
 public:
  static const int BUF_MAX = 64;

  void begin(int txPin = 22, int rxPin = 21, uint32_t baud = 115200);
  void poll();

  // ---------------- 下行指令（自动补 FF FF FF）----------------
  void setPowerSleeping(bool asleep);
  void setWakePage(uint8_t page){wakePage_=page;}
  bool powerSleeping()const{return powerSleeping_;}
  void raw(const char *s);                 // 直接发一条 ASCII 指令
  bool tryScopeCommand(const char *text,uint32_t now);
  void gotoPage(const char *page);         // page main
  void setText(const char *ctrl, const char *ascii);   // ctrl.txt="..."（务必传 ASCII）
  void setNum(const char *ctrl, long v);   // ctrl.val=N

  // ★页名限定版：TJC 支持 "page.ctrl.attr" 跨页寻址（官方手册例子 main.t0.txt="Hello"）。
  //   用它就不必先切页，也不会因为"控件不在当前页"而报 1A。
  void setTextP(const char *page, const char *ctrl, const char *ascii);
  void setNumP(const char *page, const char *ctrl, long v);

  void setVolume(uint8_t v);               // vavol 全局变量
  void setBacklight(uint8_t v);            // dim=     （0~100，全局，与页面无关）
  void keepAwake();                        // 唤醒、关闭自动休眠、恢复 100% 背光
  void setTimbreNameIdx(uint8_t idx);      // vatimbre 全局变量
  void setChordVar(const char *ascii);     // 旧屏兼容接口，新屏不使用
  void requestVolume();                    // get vavol → 回复走 onValue
  void requestPage();                      // sendme → 回复 66 <页号> 走 onPage

  // ★带归属的 get：TJC 的 71 回复里【不含变量名】，所以必须自己记住"最后问的是谁"，
  //   否则收到一个数值根本不知道是哪条 get 的应答（调试时会张冠李戴）。
  void requestVar(const char *name);
  const char *lastGetName() const { return lastGetName_; }   // 最后一条 get 的变量名
  long lastGetValue() const { return lastGetValue_; }        // 它回的值
  uint32_t lastGetMs() const { return lastGetMs_; }
  bool queryPending() const { return getPending_; }

  // ---------------- 上行事件回调 ----------------
  // 注意：这些回调在 poll() 里被调用，别在里面做长阻塞
  void (*onReset)() = nullptr;             // 收到 00 00 00 FF FF FF
  void (*onBoot)() = nullptr;              // 收到 88 FF FF FF
  void (*onBootAnim)(uint8_t code) = nullptr;  // AA 03
  void (*onTimbre)(uint8_t idx) = nullptr;     // AA 01 idx
  void (*onCal)(uint8_t cmd) = nullptr;        // AA 04 cmd
  void (*onValue)(long v) = nullptr;           // 71 vvvv
  void (*onStr)(const char *s) = nullptr;      // 70 <字符串>
  void (*onPage)(uint8_t page) = nullptr;      // 66 <页号>（sendme 的回复）
  void (*onTouch)(uint8_t page, uint8_t ctrl, uint8_t ev) = nullptr;  // 65 ...
  void (*onErr)(uint8_t code) = nullptr;       // TJC 错误码
  void (*onPowerState)(bool asleep) = nullptr;
  void (*onPowerCommand)(const uint8_t*,int) = nullptr;
  void (*onScope)(uint8_t) = nullptr;
  void (*onAny)(const uint8_t *b, int n) = nullptr;   // 任何收到的帧（命令探测器用）
  // ★ 光电校正页（功能④）的两条画作者自定义帧
  //   AA 0A 05 op ch th_lo th_hi gain crc seq   —— 固定 10 字节，光电参数请求
  //   AA 0B seq                                 —— 固定 3 字节，要一帧新数据
  //   AA 17 mode axis speed brightness flags    —— 固定 7 字节，LED 灯阵
  void (*onOptoCfg)(const uint8_t *f, int n) = nullptr;
  void (*onSeqReq)(uint8_t seq) = nullptr;
  void (*onLedCfg)(const uint8_t *f, int n) = nullptr;

  // ---------------- 统计（调试用）----------------
  volatile uint32_t rxBytes = 0, txBytes = 0, frames = 0, badFrames = 0;
  volatile uint32_t tjcErrors = 0;
  volatile uint32_t txQueueDrops = 0;
  volatile uint32_t lastRxMs = 0;
  char lastGetName_[16] = {0};
  volatile long lastGetValue_ = 0;
  volatile uint32_t lastGetMs_ = 0;
  // ★收到任意一帧有效数据就算在线。不能只看 0x88 开机帧：
  //   屏幕的 Program.s 只在上电广播一次，若屏幕先于 ESP32 上电就永远收不到。
  bool ready() const { return booted_ || frames > 0; }

 private:
  void dispatch(uint8_t *b, int n);
  void sendBytes(const uint8_t *b, int n);
  void enqueueRaw(const char *s);
  void pumpTx(uint32_t now);

  HardwareSerial *ser_ = nullptr;
  uint8_t buf_[BUF_MAX];
  int len_ = 0;
  uint32_t lastByteMs_ = 0;
  bool booted_ = false;
  bool powerSleeping_=false;
  uint8_t wakePage_=255;
  bool getPending_ = false;
  uint32_t getSentMs_ = 0;
  static const uint8_t TX_CMD_Q = 64;
  struct TxCmd { char text[64]; uint8_t len; };
  TxCmd txq_[TX_CMD_Q];
  uint8_t txWr_ = 0, txRd_ = 0;
  uint32_t nextTxMs_ = 0;
  uint8_t alwaysOnStep_ = 0;               // 优先发送唤醒指令；0：正常队列
  uint8_t sleepStep_ = 0;                  // Priority sleep transaction, cancellable by wake.
};
