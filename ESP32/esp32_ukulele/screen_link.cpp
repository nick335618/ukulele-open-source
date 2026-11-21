// ============================================================
// screen_link.cpp — TJC 串口屏链路实现
// ============================================================
#include "screen_link.h"

// 空闲多久算一帧结束。115200 8N1 下一个字节约 0.087 ms；
// 屏幕的 printh 是连续输出的，而两条 printh 语句之间会有明显间隙，
// 取 4 ms（约 4 个字节时间）既能收全又能分开相邻的 printh。
static const uint32_t GAP_MS = 2;
// 115200 下传输本身很快。新屏在 opt/check 页每 100 ms 请求一帧，
// 一帧现在包含 vra0..3、vdg0..3、vev、vbeat 共 10 条变量；
// 8 ms 间隔会把发送能力压到 125 条/秒，参数回读时容易把队列越积越多。
// 2 ms 仍保留一个脚本处理窗口，同时给实时刷新和参数回读留出余量。
static const uint32_t TX_GAP_MS = 2;

namespace {
// Sleeping displays ignore normal parameter writes. Wake must go first.
// The recovery sequence has priority over queued writes but does not drop them.
const char *const kAlwaysOnCommands[] = {
    "sleep=0", "thsp=0", "ussp=0", "thup=1", "wup=255", "sendxy=1", "dim=100"};
const uint8_t kAlwaysOnCommandCount = sizeof(kAlwaysOnCommands) / sizeof(kAlwaysOnCommands[0]);
}

void ScreenLink::setPowerSleeping(bool asleep){
  if(asleep==powerSleeping_)return;
  powerSleeping_=asleep;alwaysOnStep_=0;sleepStep_=0;
  txRd_=txWr_; // Stale page/slider writes must not execute after wake.
  if(asleep)sleepStep_=1;
  else keepAwake();
}

void ScreenLink::begin(int txPin, int rxPin, uint32_t baud) {
  ser_ = &Serial1;                       // UART1：UART0 是 USB 调试口，UART2 给了 FPGA
  ser_->begin(baud, SERIAL_8N1, rxPin, txPin);
  len_ = 0;
  booted_ = false;
  txWr_ = txRd_ = 0;
  nextTxMs_ = 0;
  alwaysOnStep_ = 0;
  sleepStep_ = 0;
  // 上电先清一次缓冲，丢掉屏幕在 ESP32 复位前残留的字节
  delay(50);
  while (ser_->available()) ser_->read();
}

void ScreenLink::sendBytes(const uint8_t *b, int n) {
  if (!ser_ || n <= 0) return;
  ser_->write(b, n);
  txBytes += n;
}

void ScreenLink::raw(const char *s) {
  if (!ser_ || !s) return;
  enqueueRaw(s);
}

bool ScreenLink::tryScopeCommand(const char *text,uint32_t now){
  if(!ser_ || powerSleeping_ || alwaysOnStep_ || sleepStep_ || txRd_!=txWr_ ||
     getPending_ || (int32_t)(now-nextTxMs_)<0)return false;
  const size_t n=strlen(text);
  if(ser_->availableForWrite()<int(n+3))return false;
  sendBytes((const uint8_t*)text,n);
  const uint8_t end[]={255,255,255};sendBytes(end,3);
  nextTxMs_=now+TX_GAP_MS;
  return true;
}

void ScreenLink::enqueueRaw(const char *s) {
  if (!ser_ || !s) return;
  // Coalesce unsent controller-variable writes, not queries or frame-latch
  // telemetry. A dragged app slider should not leave obsolete settings queued.
  const char *eq = strchr(s, '=');
  const bool control = (s[0]=='v' && eq) || strncmp(s, "vavol=", 6) == 0 ||
                       strncmp(s, "vmute=", 6) == 0 ||
                       strncmp(s, "vpset=", 6) == 0 ||
                       strncmp(s, "vatimbre=", 9) == 0 || strncmp(s, "vled", 4) == 0;
  if (eq && control) {
    const size_t keyLen = (size_t)(eq - s) + 1;
    for (uint8_t i = txRd_; i != txWr_; i = (uint8_t)((i + 1) % TX_CMD_Q)) {
      if (strncmp(txq_[i].text, s, keyLen) == 0) {
        size_t n = strlen(s);
        if (n >= sizeof(txq_[i].text)) n = sizeof(txq_[i].text) - 1;
        memcpy(txq_[i].text, s, n);
        txq_[i].text[n] = 0;
        txq_[i].len = (uint8_t)n;
        return;
      }
    }
  }
  const uint8_t next = (uint8_t)((txWr_ + 1) % TX_CMD_Q);
  if (next == txRd_) {
    txQueueDrops++;
    return;
  }
  size_t n = strlen(s);
  if (n >= sizeof(txq_[0].text)) n = sizeof(txq_[0].text) - 1;
  memcpy(txq_[txWr_].text, s, n);
  txq_[txWr_].text[n] = 0;
  txq_[txWr_].len = (uint8_t)n;
  txWr_ = next;
}

void ScreenLink::pumpTx(uint32_t now) {
  if (!ser_ || (int32_t)(now - nextTxMs_) < 0) return;
  const char *text;
  uint8_t len;
  char wakePageCommand[24];
  if (alwaysOnStep_ != 0) {
    text = kAlwaysOnCommands[alwaysOnStep_ - 1];
    len = (uint8_t)strlen(text);
    if (++alwaysOnStep_ > kAlwaysOnCommandCount) alwaysOnStep_ = 0;
  } else if(sleepStep_ != 0) {
    // Never leave the wake configuration behind a blocked numeric query or
    // a full telemetry queue. Configure touch before the final sleep=1.
    const char *const commands[]={"thsp=0","ussp=0","thup=1","sendxy=1",nullptr,"sleep=1"};
    if(sleepStep_==5){snprintf(wakePageCommand,sizeof(wakePageCommand),"wup=%u",wakePage_);text=wakePageCommand;}
    else text=commands[sleepStep_-1];
    len=(uint8_t)strlen(text);
    if(++sleepStep_>6)sleepStep_=0;
  } else {
    if (txRd_ == txWr_) return;
    TxCmd &c = txq_[txRd_];
    // 0x71 has no request name. Associate it at actual transmission and
    // serialize numeric queries, rather than changing owner on enqueue.
    if(strncmp(c.text,"get ",4)==0){
      if(getPending_ && now-getSentMs_<500)return;
      strncpy(lastGetName_,c.text+4,sizeof(lastGetName_)-1);
      lastGetName_[sizeof(lastGetName_)-1]=0;
      getPending_=true;getSentMs_=now;lastGetMs_=0;
    }
    text = c.text;
    len = c.len;
    txRd_ = (uint8_t)((txRd_ + 1) % TX_CMD_Q);
  }
  ser_->write((const uint8_t *)text, len);
  const uint8_t tail[3] = {0xFF, 0xFF, 0xFF};
  ser_->write(tail, 3);
  txBytes += (uint32_t)len + 3;
  nextTxMs_ = now + TX_GAP_MS;
}

void ScreenLink::keepAwake() {
  // Touch/App wake cancels even an incompletely transmitted sleep transaction.
  sleepStep_=0;powerSleeping_=false;
  alwaysOnStep_ = 1;
}

void ScreenLink::gotoPage(const char *page) {
  if (!page) return;
  char s[64];
  snprintf(s, sizeof(s), "page %s", page);
  enqueueRaw(s);
}

void ScreenLink::setText(const char *ctrl, const char *ascii) {
  if (!ctrl || !ascii) return;
  char s[128];
  snprintf(s, sizeof(s), "%s.txt=\"%s\"", ctrl, ascii);
  enqueueRaw(s);
}

void ScreenLink::setNum(const char *ctrl, long v) {
  if (!ctrl) return;
  char s[40];
  snprintf(s, sizeof(s), "%s.val=%ld", ctrl, v);
  raw(s);
}

void ScreenLink::setTextP(const char *page, const char *ctrl, const char *ascii) {
  if (!page || !ctrl || !ascii) return;
  char s[128];
  snprintf(s, sizeof(s), "%s.%s.txt=\"%s\"", page, ctrl, ascii);
  enqueueRaw(s);
}

void ScreenLink::setNumP(const char *page, const char *ctrl, long v) {
  if (!page || !ctrl) return;
  char s[56];
  snprintf(s, sizeof(s), "%s.%s.val=%ld", page, ctrl, v);
  raw(s);
}

void ScreenLink::setVolume(uint8_t v) {
  if (v > 100) v = 100;
  char s[24];
  // ★只用新屏的命名全局变量 vavol。屏幕页面脚本会自行刷新数字、滑块和进度条。
  snprintf(s, sizeof(s), "vavol=%u", (unsigned)v);
  raw(s);
  raw(v == 0 ? "vmute=1" : "vmute=0");
}

void ScreenLink::setTimbreNameIdx(uint8_t idx) {
  char s[24];
  // vatimbre 同 vavol，是命名全局变量；中文音色名由屏幕页面脚本按它回显，
  // ESP32 只发索引、绝不发中文（屏幕工程是 GBK，Arduino 只能发 UTF-8）。
  snprintf(s, sizeof(s), "vatimbre=%u", (unsigned)(idx & 3));
  raw(s);
}

void ScreenLink::setChordVar(const char *ascii) {
  if (!ascii) return;
  char s[40];
  snprintf(s, sizeof(s), "vachord=%s", ascii);   // 试探：命名变量赋值不加引号
  raw(s);
}

void ScreenLink::requestVolume() {
  // ★读【命名变量 vavol】，不依赖 vol 页是否当前显示。
  requestVar("vavol");
}

void ScreenLink::requestVar(const char *name) {
  if (!name) return;
  lastGetMs_ = 0;                    // 0 = 还没收到回复
  char s[40];
  snprintf(s, sizeof(s), "get %s", name);
  raw(s);
}

void ScreenLink::requestPage() {
  raw("sendme");          // 屏幕回 66 <页号>，用来确认切页到底有没有生效
}

void ScreenLink::setBacklight(uint8_t v) {
  if (v > 100) v = 100;
  char s[20];
  snprintf(s, sizeof(s), "dim=%u", (unsigned)v);
  raw(s);
}

// 屏幕工程自定义帧（printh 手写）的【固定长度】。
// ★必须按长度即时派发，不能只靠空闲间隔：实测两条 printh 紧挨着时会粘包
//   （AA 01 02 紧跟 AA 04 01 → 被当成一帧，后半条丢失）。
//   selftest_screen.py 的用例 7 就是复现这个的。
static int aaFrameLen(uint8_t sub) {
  switch (sub) {
    case 0x01: return 3;   // AA 01 xx  音色选择
    case 0x02: return 3;   // AA 02 xx  音量上报（预留）
    case 0x04: return 3;   // AA 04 xx  校准命令
    case 0x03: return 2;   // AA 03     开机动画满值
    // ★ 光电校正页（功能④）：这两条是【固定长度】，长度字节 0x05 是常量而非真实长度，
    //   所以这里直接返回固定值、不做长度校验（见 docs/屏幕光电校正方案.md §4.3）
    case 0x1C: return 9; // power: version/op/sequence16/flags + XOR
    case 0x2D: return 5; // scope: AA 2D 53 mode xor
    case 0x1A: return 10; // six-byte payload includes request sequence
    case 0x0A: return 10;  // AA 0A 05 op ch th_lo th_hi gain crc seq
    case 0x0B: return 3;   // AA 0B seq
    case 0x17: return 7;   // AA 17 mode axis speed brightness flags
    default:   return 0;   // 未知子类型 → 退回空闲间隔分帧
  }
}

// TJC 错误码 → 中文说明（来自 docs/TJC串口屏协议速查.md 的错误码表）
static const char *tjcErrText(uint8_t c) {
  switch (c) {
    case 0x00: return "无效指令";
    case 0x01: return "控件不存在";
    case 0x02: return "控件名无效";
    case 0x03: return "无效操作";
    case 0x04: return "控件未赋值";
    case 0x11: return "赋值/操作失败";
    case 0x1A: return "变量/控件不存在（常见：控件不在当前页）";
    case 0x1B: return "变量不支持该操作";
    case 0x1C: return "赋值超出范围";
    case 0x1D: return "字符串赋值长度超限";
    case 0x23: return "无效的页";
    case 0x24: return "串口缓冲区溢出（指令发太快/太长）";
    case 0xFD: return "透传完成";
    case 0xFE: return "透传就绪";
    default:   return nullptr;
  }
}

void ScreenLink::poll() {
  if (!ser_) return;
  while (ser_->available() > 0) {
    uint8_t b = (uint8_t)ser_->read();
    rxBytes++;
    lastRxMs = millis();
    lastByteMs_ = lastRxMs;

    if (len_ < BUF_MAX) {
      buf_[len_++] = b;
    } else {
      memmove(buf_, buf_ + 1, BUF_MAX - 1);
      buf_[BUF_MAX - 1] = b;
      badFrames++;
    }

    // 判据 1：见到 FF FF FF 结尾 → 立刻收帧（TJC 自己的回复都是这个格式）
    if (len_ >= 3 && buf_[len_ - 3] == 0xFF && buf_[len_ - 2] == 0xFF &&
        buf_[len_ - 1] == 0xFF) {
      const int n = len_;
      len_ = 0;
      dispatch(buf_, n);
      continue;
    }

    // 判据 2：AA 自定义帧按已知长度即时收帧（防粘包）
    if (len_ >= 2 && buf_[0] == 0xAA) {
      int want = aaFrameLen(buf_[1]);
      if (want > 0 && len_ == want) {
        const int n = len_;
        len_ = 0;
        dispatch(buf_, n);
        continue;
      }
    }

    // 判据 3：孤立的 FF FF FF（前面那条 AA 帧已按长度收掉，这里只剩终止符）
    //        直接丢掉，不算错误
    if (len_ == 3 && buf_[0] == 0xFF && buf_[1] == 0xFF && buf_[2] == 0xFF) {
      len_ = 0;
      continue;
    }
  }

  // 判据 4：空闲间隔收帧（兜底，处理未知/变长载荷）
  if (len_ > 0 && (millis() - lastByteMs_) >= GAP_MS) {
    const int n = len_;
    len_ = 0;
    dispatch(buf_, n);
  }
  // Drain replies before changing query ownership or transmitting a queued
  // sleep command. A touch arriving in this poll can cancel sleep immediately.
  // A lost numeric reply must not leave main_app unable to probe sleep/dim.
  if(getPending_ && millis()-getSentMs_>=500){getPending_=false;lastGetName_[0]=0;}
  pumpTx(millis());
}

void ScreenLink::dispatch(uint8_t *b, int n) {
  if(n==5 && b[0]==0xaa && b[1]==0x2d && b[2]==0x53){
    if(b[3]<=4 && b[4]==(0x2d^0x53^b[3])){++frames;if(onScope)onScope(b[3]);}
    else ++badFrames;
    return;
  }
  if(n==9 && b[0]==0xAA && b[1]==0x1C && b[2]==5){
    uint8_t c=0;for(int i=1;i<8;i++)c^=b[i];
    if(c==b[8] && b[3]==1 && b[4]<=4 && (b[4]==4 || b[7]==0)){if(onPowerCommand)onPowerCommand(b,n);}
    else ++badFrames;
    return;
  }

  if (n <= 0) return;

  // 孤立的 FF FF FF：AA 自定义帧已按长度先收走，只剩下它的终止符 → 直接丢弃。
  // 不算错误、不计帧数（否则每次带终止符的 AA 帧都会多记一条垃圾）。
  if (n == 3 && b[0] == 0xFF && b[1] == 0xFF && b[2] == 0xFF) return;

  frames++;

  // 去掉屏幕的 00 00 00 FF FF FF 分帧/清零标记
  if (n == 6 && b[0] == 0x00 && b[1] == 0x00 && b[2] == 0x00 &&
      b[3] == 0xFF && b[4] == 0xFF && b[5] == 0xFF) {
    if (onReset) onReset();
    return;
  }
  if (n > 6 && b[0] == 0x00 && b[1] == 0x00 && b[2] == 0x00 &&
      b[3] == 0xFF && b[4] == 0xFF && b[5] == 0xFF) {
    b += 6;                      // 剥掉前缀，继续解析后面的真实载荷
    n -= 6;
    if (onReset) onReset();
  }

  // TJC 开机成功 0x88（可能带 FF FF FF 尾巴，也可能已被上面收帧逻辑吃掉）
  if (b[0] == 0x88) {
    booted_ = true;
    if (onBoot) onBoot();
    return;
  }

  // TJC automatic sleep/wake notifications. Old screen firmware may still
  // contain thsp=20; recover it without waiting for a touch or a new upload.
  if (n == 4 && b[1] == 0xFF && b[2] == 0xFF && b[3] == 0xFF &&
      (b[0] == 0x86 || b[0] == 0x87)) {
    if(onPowerState){
      powerSleeping_=b[0]==0x86;
      if(!powerSleeping_)sleepStep_=0;
      onPowerState(powerSleeping_);
    }
    else if (b[0] == 0x86) {
      keepAwake();
      Serial.println("[屏幕] 自动休眠通知：已安排唤醒并恢复常亮");
    }
    // A wake notification must not schedule another sleep=0 sequence.
    if (onAny) onAny(b, n);
    return;
  }

  // TJC 数值变量回复：71 + 4 字节小端
  if (b[0] == 0x71 && n >= 5) {
    if(!getPending_)lastGetName_[0]=0;
    long v = (long)b[1] | ((long)b[2] << 8) | ((long)b[3] << 16) | ((long)b[4] << 24);
    lastGetValue_ = v;
    lastGetMs_ = millis();
    if (onAny) onAny(b, n);
    if (onValue) onValue(v);
    getPending_=false;
    return;
  }

  // TJC 错误码（屏幕用这些回复"指令没被执行"）。
  // 变量/控件不存在时屏幕会回 1A；保留错误码打印，便于联调新页面。
  if (n >= 1 && tjcErrText(b[0])) {
    tjcErrors++;
    if (onErr) onErr(b[0]);
    getPending_=false;
    if (onAny) onAny(b, n);
    // Preserve counters without blocking SPI/BLE on an old HMI's error burst.
    static uint32_t lastErrorLog=0;
    if(millis()-lastErrorLog>=2000){lastErrorLog=millis();
      Serial.printf("[屏幕] TJC 错误 0x%02X: %s\n", b[0], tjcErrText(b[0]));}
    return;
  }

  // Coordinate reports include sleep-mode touch (0x68). They are valid
  // protocol frames, not unknown traffic; the first press is wake-only.
  if(n==9 && (b[0]==0x67 || b[0]==0x68) && b[5]<=1 &&
     b[6]==0xFF && b[7]==0xFF && b[8]==0xFF){
    if(onAny)onAny(b,n);
    return;
  }

  // TJC 触摸事件：65 page ctrl ev
  if (b[0] == 0x65 && n >= 4) {
    if (onTouch) onTouch(b[1], b[2], b[3]);
    return;
  }

  // 屏幕当前页号回复（sendme）：66 + page_id
  if (b[0] == 0x66 && n >= 2) {
    if (onAny) onAny(b, n);
    if (onPage) onPage(b[1]);
    return;
  }

  // TJC 字符串变量回复：70 + 数据
  if (b[0] == 0x70) {
    if (onAny) onAny(b, n);
    if (onStr) onStr((const char *)(b + 1));
    return;
  }

  // 屏幕工程自定义的应用层帧（作者用 printh 手写）
  if (b[0] == 0xAA && n >= 2) {
    if (b[1] == 0x01 && n >= 3) {           // 音色选择
      if (onTimbre) onTimbre(b[2] & 0x03);
      return;
    }
    if (b[1] == 0x04 && n >= 3) {           // 校准命令
      if (onCal) onCal(b[2]);
      return;
    }
    if (b[1] == 0x03) {                     // 开机动画满值
      if (onBootAnim) onBootAnim(0x03);
      return;
    }
    if (b[1] == 0x02 && n >= 3) {           // 预留：音量上报（需在屏幕侧加 printh AA 02）
      long v = b[2];
      if (onValue) onValue(v);
      return;
    }
    if(b[1]==0x1A && n==10 && b[2]==6){if(onOptoCfg)onOptoCfg(b,n);return;}
    if (b[1] == 0x0A) {                     // ★ 光电参数请求（功能④）
      if (onOptoCfg) onOptoCfg(b, n);
      return;
    }
    if (b[1] == 0x0B) {                     // ★ 要一帧新数据（功能④的刷屏握手）
      if (onSeqReq) onSeqReq(n >= 3 ? b[2] : 0);
      return;
    }
    if (b[1] == 0x17 && n >= 7) {           // ★ LED 灯阵模式
      if (onLedCfg) onLedCfg(b, n);
      return;
    }
  }

  // 其它：记一笔并打印原始字节，便于第一次联调时对照
  badFrames++;
  if (onAny) onAny(b, n);
  Serial.printf("[屏幕] 未识别帧 %d 字节:", n);
  for (int i = 0; i < n; i++) Serial.printf(" %02X", b[i]);
  Serial.println();
}
