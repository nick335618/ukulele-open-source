// ============================================================
// gvlink.cpp — ESP32 ↔ gv_host 链路实现（★ SPI 版）
//
// 物理层是 FpgaSpi（2 字节事务，见 fpgaspi.h / spi_slave_bytes.v）。
// 帧协议与 UART 版**完全相同**，只是把 write()/available() 换成
// sendByte()/read()。解析器状态机一行没改。
// ============================================================
#include "gvlink.h"

void GvLink::begin(FpgaSpi *spi) {
  spi_ = spi;
  st_ = 0; cnt_ = 0;
  // 丢掉上电时可能残留的字节（FPGA 一上电就开始发 0x81/0x84）
  if (spi_) {
    delay(20);
    while (spi_->read() >= 0) { /* drain */ }
  }
}

void GvLink::sendCmd(uint8_t cmd, const uint8_t *pay, uint8_t len) {
  if (!spi_) return;
  uint8_t crc = cmd ^ len;
  spi_->sendByte(0xAA);
  spi_->sendByte(cmd);
  spi_->sendByte(len);
  for (uint8_t i = 0; i < len; i++) {
    spi_->sendByte(pay[i]);
    crc ^= pay[i];
  }
  spi_->sendByte(crc);
  // ★ 不再像 UART 那样立刻 flush：SPI 事务由 poll() 驱动，主循环会尽快把队列推出去。
  //   （立刻 flush 会阻塞主循环；需要"发完再继续"的场合可显式调 spi_->flush()）
}

void GvLink::gestureCommand(uint8_t mask,uint8_t token){
 if(mask>7 && mask!=255)return;
 // Fall back to a v1 probe after stale v2 replies, including FPGA-only rollback.
 const bool modern=gesture.caps==7 && uint32_t(millis()-gesture.ms)<1500;
 uint8_t p[3]={uint8_t(modern?2:1),uint8_t(mask==255||modern?mask:mask&3),token};
 sendCmd(0x1C,p,3);
}

void GvLink::fxCommand(uint8_t op,uint8_t mode,uint8_t strength,uint16_t token){
 if(op>1||mode>4||strength>((mode==2||mode==3)?96:127))return;
 uint8_t p[6]={3,op,mode,strength,uint8_t(token),uint8_t(token>>8)};sendCmd(0x21,p,6);
}
void GvLink::requestLedDiag(uint8_t token){uint8_t p[2]={1,token};sendCmd(0x1F,p,2);}

bool GvLink::ledBitmapCommand(uint8_t op,uint8_t index,uint8_t a,uint8_t b,uint8_t c,uint16_t token){
 if(op>3||!spi_||spi_->txPending()>400)return false;
 uint8_t p[8]={2,op,index,a,b,c,uint8_t(token),uint8_t(token>>8)};sendCmd(0x23,p,8);return true;
}
void GvLink::setGain(uint8_t v)          { sendCmd(0x06, &v, 1); }
void GvLink::setTimbre(uint8_t idx)      { uint8_t v = idx & 3; sendCmd(0x07, &v, 1); }
void GvLink::setLedConfig(uint8_t mode, uint8_t axis, uint8_t speed,
                          uint8_t brightness, uint8_t flags) {
  uint8_t pay[5] = {mode, (uint8_t)(axis & 1), speed, brightness, flags};
  sendCmd(0x17, pay, 5);
}
void GvLink::setLedGuideLevels(const uint8_t f[4],const uint8_t levels[4]){
 uint32_t packed=0;for(uint8_t c=0;c<4;++c){if(f[c]>18&&f[c]!=31)return;packed|=uint32_t(f[c])<<(c*5);}
 uint8_t p[8]={1,uint8_t(packed),uint8_t(packed>>8),uint8_t(packed>>16),levels[0],levels[1],levels[2],levels[3]};sendCmd(0x26,p,8);
}
void GvLink::setLedGuideFrets(const uint8_t frets[4],uint8_t level){
  uint8_t pay[6]={1,31,31,31,31,level};
  for(uint8_t c=0;c<4;++c){if(frets[c]>18 && frets[c]!=31)return;pay[c+1]=frets[c];}
  sendCmd(0x20,pay,sizeof(pay)); // one atomic four-string frame; outer XOR CRC
}
void GvLink::setLedGuide(uint8_t colMask, uint8_t row, uint8_t level,
                         uint8_t flags) {
  uint8_t pay[4] = {(uint8_t)(colMask & 0x0F),
                    (uint8_t)(row > 18 ? 18 : row), level, flags};
  sendCmd(0x18, pay, 4);
}
void GvLink::note(uint8_t voice)         { uint8_t v = voice & 0x3F; sendCmd(0x01, &v, 1); }
void GvLink::ping()                      { uint8_t v = 0; sendCmd(0x02, &v, 1); }  // VIB off = 探活

// ---------------- ★ v80 功能④：光电参数读写 ----------------
// 该参数的位宽上限（与 FPGA 侧寄存器宽度**必须**一致，见 top_gv_host.v 的 o_th*/o_glob*）：
//   th 12 位、gain 8 位、slow/fast/blank 20 位、cm_cap 18 位、
//   margin/persist 8 位、cool/rearm 16 位。
// ★ 为什么必须钳位而不是直接截断：屏幕上的数值控件可以让用户输入任意数，
//   越界值截断后**不会报错**，只会让参数变成一个看不出所以然的值
//   （例如 threshold 输入 5000 → 截成 904，屏幕上却还显示 5000）。
uint32_t GvLink::optParamMax(uint8_t pid) {
  switch (pid) {
    case 0: case 1: case 2: case 3:      return 0xFFF;      // th0..th3   12 位
    case 4: case 5: case 6: case 7:      return 0xFF;       // gain0..3    8 位
    case 8:  /* slow  */                 return 0xFFFFF;    // 20 位
    case 9:  /* fast  */                 return 0xFFFFF;    // 20 位
    case 10: /* cm_cap */                return 0x3FFFF;    // 18 位
    case 11: /* margin */                return 0xFF;       // 8 位
    case 12: /* persist */               return 0xFF;       // 8 位
    case 13: /* cool */                  return 0xFFFF;     // 16 位
    case 14: /* blank */                 return 0xFFFFF;    // 20 位
    case 15: /* rearm */                 return 0xFFFF;     // 16 位
    case OPT_PID_FIRST:                   return 0xFFFFF;    // 20 位，50 MHz 时钟数
    default:                             return 0xFF;
  }
}

void GvLink::setOptoParam(uint8_t pid, uint32_t val) {
  if (pid >= OPT_PID_COUNT) return;
  uint32_t mx = optParamMax(pid);
  if (val > mx) val = mx;

  if (pid < OPT_GLOB_BASE) {
    // ---- pid 0..7：每通道阈值/增益 → 0x08 [ch, th_lo, th_hi, gain]（老命令，保持兼容）----
    // ★ 陷阱：0x08 是"阈值 + 增益**一起**写"的一条命令，没有"只写其中一个"的形式。
    //   所以想改阈值时必须把**当前增益**一并带上，否则 gain 字段填 0 会把增益清零
    //   （屏幕上表现为"调了下阈值，灵敏度突然没了"）。增益同理，要带上当前阈值。
    //   这就是本地镜像 param.v[] 存在的意义。
    const uint8_t ch = (pid < OPT_PID_G0) ? pid : (uint8_t)(pid - OPT_PID_G0);
    const uint8_t thPid = (uint8_t)(OPT_PID_TH0 + ch);
    const uint8_t gPid  = (uint8_t)(OPT_PID_G0 + ch);
    const uint32_t th = (pid < OPT_PID_G0) ? val : param.v[thPid];
    const uint32_t g  = (pid < OPT_PID_G0) ? param.v[gPid] : val;
    uint8_t pay[4];
    pay[0] = ch;
    pay[1] = (uint8_t)(th & 0xFF);
    pay[2] = (uint8_t)((th >> 8) & 0x0F);
    pay[3] = (uint8_t)(g & 0xFF);
    sendCmd(0x08, pay, 4);
    // Confirmed mirror is updated only by FPGA replies.
  } else if (pid < OPT_PID_FIRST) {
    // ---- pid 8..15：全局参数 → 0x0A [pid, b0, b1, b2]（小端，取低位）----
    uint8_t pay[4];
    pay[0] = pid;
    pay[1] = (uint8_t)(val & 0xFF);
    pay[2] = (uint8_t)((val >> 8) & 0xFF);
    pay[3] = (uint8_t)((val >> 16) & 0xFF);
    sendCmd(0x0A, pay, 4);
    // Do not present requested settings as hardware readback.
  } else {
    // pid 16：首事件锁定窗，单独命令避免改变旧 0x0A 的 8 个 pid 编号。
    uint8_t pay[3];
    pay[0] = (uint8_t)(val & 0xFF);
    pay[1] = (uint8_t)((val >> 8) & 0xFF);
    pay[2] = (uint8_t)((val >> 16) & 0x0F);
    sendCmd(0x0C, pay, 3);
    // Do not present requested settings as hardware readback.
  }
}

void GvLink::setOptoParams(const uint32_t *vals, int n) {
  if(!vals || n!=OPT_PID_COUNT)return;
  for(uint8_t ch=0;ch<4;ch++){
    uint8_t p[4]={ch,(uint8_t)vals[ch],(uint8_t)(vals[ch]>>8),(uint8_t)vals[ch+4]};
    sendCmd(0x08,p,4);
  }
  for(uint8_t pid=8;pid<OPT_PID_COUNT;pid++)setOptoParam(pid,vals[pid]);
}
void GvLink::requestOptoParams(uint8_t token) {sendCmd(0x0B,&token,1);}
void GvLink::setPlayMode(uint8_t mode) {if(mode<=1)sendCmd(0x19,&mode,1);}

void GvLink::powerCommand(uint8_t op,uint16_t id,uint16_t epoch){
 if(op>3)return;
 uint8_t p[7]={1,op,(uint8_t)id,(uint8_t)(id>>8),(uint8_t)epoch,(uint8_t)(epoch>>8),0};
 sendCmd(0x1B,p,7);
}

void GvLink::poll() {
  if (!spi_) return;
  int b;
  while ((b = spi_->read()) >= 0) {
    uint8_t by = (uint8_t)b;
    switch (st_) {
      case 0:                                  // 等同步头
        if (by == 0xAA) st_ = 1;
        break;
      case 1:                                  // CMD
        code_ = by;
        crc_  = by;
        st_   = 2;
        break;
      case 2:                                  // LEN
        len_ = by;
        crc_ ^= by;
        cnt_ = 0;
        if (len_ > sizeof(pay_)) {             // 长度异常 → 丢弃重同步
          errFrames++;
          st_ = 0;
        } else {
          st_ = (len_ == 0) ? 4 : 3;
        }
        break;
      case 3:                                  // DATA
        pay_[cnt_++] = by;
        crc_ ^= by;
        if (cnt_ >= len_) st_ = 4;
        break;
      case 4:                                  // CRC
        if (by == crc_) {
          okFrames++;
          dispatch(code_, pay_, len_);
        } else {
          errFrames++;
          Serial.printf("[GV] CRC错 cmd=0x%02X len=%u got=%02X want=%02X\n",
                        (unsigned)code_, (unsigned)len_, (unsigned)by, (unsigned)crc_);
          // CRC 位置若正好落在下一帧帧头，立即把它作为新的同步字，
          // 避免一次丢字节把后续状态帧全部错位。
          st_ = (by == 0xAA) ? 1 : 0;
        }
        if (by == 0xAA && st_ == 1) {
          // 保留上面的重同步状态。
        } else {
          st_ = 0;
        }
        break;
      default:
        st_ = 0;
        break;
    }
  }
}

void GvLink::dispatch(uint8_t cmd, const uint8_t *pay, uint8_t len) {
  if(cmd==0x8F){
    if(len!=16||pay[0]!=2||pay[1]>5||pay[2]>144||pay[4]>8||(pay[12]!=7&&pay[12]!=15)||pay[13]){++errFrames;return;}
    bitmap.error=pay[1];bitmap.cursor=pay[2];bitmap.level=pay[3];bitmap.mode=pay[4];bitmap.flags=pay[5];
    bitmap.crc=pay[6]|uint16_t(pay[7])<<8;bitmap.active=pay[8]|uint16_t(pay[9])<<8;
    bitmap.generation=pay[10]|uint16_t(pay[11])<<8;bitmap.caps=pay[12];bitmap.token=pay[14]|uint16_t(pay[15])<<8;
    bitmap.ms=millis();++bitmap.seq;return;
  }
  if(cmd==0x8E){
    if(!((len==12&&pay[0]==1)||(len==20&&pay[0]==2))||pay[1]>=97||(pay[3]&0x70)||pay[11]>1||((pay[3]>>7)!=pay[11])){++errFrames;return;}
    if(pay[0]==2&&(pay[12]>7||pay[14]>3||pay[19]>3)){++errFrames;return;}
    padDiag.version=pay[0];padDiag.gesture=0;padDiag.vibratoFlags=0;
    padDiag.owner=0;padDiag.amplitude=0;padDiag.threshold=0;padDiag.model=0;
    if(pay[0]==2){
      if(pay[12]>7||pay[14]>3||pay[19]>3){++errFrames;return;}
      padDiag.gesture=pay[12];padDiag.vibratoFlags=pay[13];padDiag.owner=pay[14];
      padDiag.amplitude=uint16_t(pay[15])|(uint16_t(pay[16])<<8);
      padDiag.threshold=uint16_t(pay[17])|(uint16_t(pay[18])<<8);padDiag.model=pay[19];
    }
    padDiag.channel=pay[1];padDiag.token=pay[2];padDiag.flags=pay[3];
    padDiag.raw=uint16_t(pay[4])|(uint16_t(pay[5])<<8);
    padDiag.baseline=uint16_t(pay[6])|(uint16_t(pay[7])<<8);
    padDiag.delta=uint16_t(pay[8])|(uint16_t(pay[9])<<8);
    padDiag.ids=pay[10];padDiag.ready=pay[11];padDiag.ms=millis();++padDiag.seq;return;
  }
  if(cmd==0x92){
    if(len!=12||pay[0]!=1||pay[1]>1||pay[2]>15||pay[9]>15||pay[10]>15||pay[11]>1)return;
    ChainConfig c;c.enabled=pay[1];c.mask=pay[2];memcpy(c.amount,pay+3,4);c.target=pay[7];if(!c.valid())return;
    chain.config=c;chain.token=pay[8];chain.mask=pay[9];chain.flags=pay[10];chain.armed=pay[11];chain.ms=millis();++chain.seq;return;
  }
  if(cmd==0x90){
    if(len!=4||(pay[0]!=1&&pay[0]!=2&&pay[0]!=3)||pay[1]>12||pay[3]!=12){++errFrames;return;}
    capo.version=pay[0];capo.semitones=pay[1];capo.token=pay[2];capo.caps=pay[3];capo.ms=millis();++capo.seq;return;
  }
  if(cmd==0x91){
    const bool old=len==4&&pay[0]==1;
    const bool v3=len==11&&pay[0]==3&&pay[3]==7&&pay[4]<28&&pay[5]<16&&pay[10]<16;
    const bool modern=len==12&&pay[0]==4&&pay[3]==7&&(pay[4]&0x7c)==0&&pay[5]<28&&pay[11]<16;
    if((!old&&!v3&&!modern)||pay[1]>3||(pay[3]!=3&&pay[3]!=7)){++errFrames;return;}
    uint32_t mask=0;
    if(modern){
      mask=uint32_t(pay[8])|(uint32_t(pay[9])<<8)|(uint32_t(pay[10])<<16)|(uint32_t(pay[11])<<24);
      if(((mask&(uint32_t(1)<<pay[5]))!=0)==(pay[6]==235)){++errFrames;return;}
    }
    model.version=pay[0];
    if(modern){
      envelopeBank=pay[4]&3;envelopeFull=(pay[4]&128)!=0;envelopeKey=pay[5];envelopeValue=pay[6];envelopeToken=pay[7];
      envelopeMask=mask;
    }else{envelopeMask=0;envelopeFull=false;envelopeBank=0;envelopeKey=0;envelopeValue=235;envelopeToken=0;}
    model.semitones=pay[1];model.token=pay[2];model.caps=pay[3];model.ms=millis();++model.seq;return;
  }
  if(cmd==0x8D){
    if(len!=8||pay[0]!=3||pay[1]>(4)||pay[2]>((pay[1]==2||pay[1]==3)?96:127)||pay[3]>(4)||pay[4]>((pay[3]==2||pay[3]==3)?96:127)||(pay[5]&~15)){++errFrames;return;}
    fx.version=pay[0];fx.mode=pay[1];fx.strength=pay[2];fx.actual=pay[3];fx.gain=pay[4];fx.flags=pay[5];
    fx.token=uint16_t(pay[6])|(uint16_t(pay[7])<<8);fx.ms=millis();++fx.seq;return;
  }
  if(cmd==0x8A){
    if(len==10 && pay[0]==1 && pay[3]<=1 && pay[4]<=1 && pay[5]<=60 && pay[8]<=7 && pay[9]==0){
      uint16_t th=uint16_t(pay[1])|(uint16_t(pay[2])<<8);
      if(th>=120 && th<=2400){touch.threshold=th;touch.requestedFast=pay[3];touch.actualFast=pay[4];touch.grace=pay[5];
        touch.token=uint16_t(pay[6])|(uint16_t(pay[7])<<8);touch.flags=pay[8];touch.ms=millis();++touch.seq;return;}
    }++errFrames;return;
  }
  if(cmd==0x89){
    if(len==4 && ((pay[0]==1 && pay[1]<=3 && pay[3]==3)||(pay[0]==2 && pay[1]<=7 && pay[3]==7))){
      gesture.mask=pay[1];gesture.token=pay[2];gesture.caps=pay[3];gesture.ms=millis();++gesture.seq;
    }return;
  }

  if(cmd==0x88){
    FpgaPowerState next=power;
    if(!next.decode(pay,len)){++errFrames;return;}
    next.ms=millis();++next.seq;power=next;return;
  }
  if (cmd == 0x81 && len >= 4) {               // 状态帧
    status.note   = pay[0];
    status.gain   = pay[1];
    status.timbre = pay[2] & 3;
    status.flags  = pay[3];
    // ★ v64：LEN=5 时第 5 字节是"FPGA 已收到的命令条数"。
    //   用它判断 ESP32→FPGA 方向通不通（LEN=4 的旧固件没有这一字节，保持 0）。
    if (len >= 5) status.cmdCnt = pay[4];
    status.ms     = millis();
    status.valid  = true;
    statusCount++;
    return;
  }
  if (cmd == 0x84 && (len == 17 || len == 30)) {              // ★ 光电遥测帧（屏幕数值上屏用）
    if(len==30){
      if(pay[17]!=1&&(pay[17]&15)!=2){errFrames++;return;}
      for(int i=0;i<4;i++)if(pay[18+i]>18 || (pay[22+i]>127 && pay[22+i]!=255)){errFrames++;return;}
    }
    optel.noteFeedback=len==30;optel.feedbackVersion=len==30&&((pay[17]&15)==2)?2:1;
    optel.automatic=optel.feedbackVersion==2?pay[17]>>4:0;
    if(optel.noteFeedback)for(int i=0;i<4;i++){
      optel.frets[i]=pay[18+i];optel.midi[i]=pay[22+i];optel.counts[i]=pay[26+i];
    }
    ++optel.seq;
    for (int i = 0; i < 4; i++) {
      optel.raw[i] = (uint16_t)pay[i * 2] | ((uint16_t)pay[i * 2 + 1] << 8);
      optel.dg[i]  = (uint16_t)pay[8 + i * 2] | ((uint16_t)pay[8 + i * 2 + 1] << 8);
    }
    optel.ev    = pay[16];
    optel.ms    = millis();
    optel.valid = true;
    return;
  }
  if (cmd == 0x87 && len == 26) {              // ★ 最终音频波形帧
    wave.seq   = pay[0];
    wave.decim = pay[1] ? pay[1] : 16;
    for (int i = 0; i < 24; i++) wave.samples[i] = (int8_t)pay[2 + i];
    wave.ms    = millis();
    wave.valid = true;
    waveCount++;
    if (onWave) onWave(wave);
    return;
  }
  if(cmd==0x8C){
    if(!parseLedDiagnostics(pay,len,ledDiag)){errFrames++;return;}
    ledDiag.ms=millis();++ledDiag.seq;return;
  }
  if (cmd == 0x86 && (len == 30 || len == 50)) {              // ★ v80 参数回读帧（0x0B 触发）
    GvParam next=param;
    // 载荷布局（全部小端）与 top_gv_host.v 的 o86_pay 函数**逐项对应**：
    //   [0..7]   th0..th3     各 2 字节
    //   [8..11]  gain0..3     各 1 字节
    //   [12..14] slow  20 位   [15..17] fast  20 位   [18..20] cm_cap 18 位
    //   [21]     margin        [22]     persist
    //   [23..24] cool  16 位   [25..27] blank 20 位   [28..29] rearm 16 位
    // ⚠ 校验脚本：fw_scripts/_check_o86.py（比对 FPGA 侧的两处定义）
    for (int i = 0; i < 4; i++) {
      next.v[i]     = (uint32_t)pay[i * 2] | ((uint32_t)pay[i * 2 + 1] << 8);
      next.v[4 + i] = pay[8 + i];
    }
    next.v[8]  = (uint32_t)pay[12] | ((uint32_t)pay[13] << 8) | ((uint32_t)pay[14] << 16);
    next.v[9]  = (uint32_t)pay[15] | ((uint32_t)pay[16] << 8) | ((uint32_t)pay[17] << 16);
    next.v[10] = (uint32_t)pay[18] | ((uint32_t)pay[19] << 8) | ((uint32_t)pay[20] << 16);
    next.v[11] = pay[21];
    next.v[12] = pay[22];
    next.v[13] = (uint32_t)pay[23] | ((uint32_t)pay[24] << 8);
    next.v[14] = (uint32_t)pay[25] | ((uint32_t)pay[26] << 8) | ((uint32_t)pay[27] << 16);
    next.v[15] = (uint32_t)pay[28] | ((uint32_t)pay[29] << 8);
    next.complete=false;next.version=1;next.mode=0xFF;
    if(len==50){
      if(pay[34]!=2 || pay[33]>1 || (pay[32]&0xF0) || pay[39]>8){errFrames++;return;}
      next.v[16]=(uint32_t)pay[30]|((uint32_t)pay[31]<<8)|((uint32_t)pay[32]<<16);
      next.mode=pay[33];next.version=2;next.token=pay[35];
      next.detected=pay[36]&15;next.accepted=pay[37]&15;next.emitted=pay[38]&15;next.depth=pay[39];
      next.overflow=(uint16_t)pay[40]|((uint16_t)pay[41]<<8);
      next.detectedCount=(uint16_t)pay[42]|((uint16_t)pay[43]<<8);
      next.emittedCount=(uint16_t)pay[44]|((uint16_t)pay[45]<<8);
      next.acceptedCount=(uint16_t)pay[46]|((uint16_t)pay[47]<<8);
      next.peak=(uint16_t)pay[48]|((uint16_t)pay[49]<<8);
      PerformanceConfig checked;memcpy(checked.values,next.v,sizeof(checked.values));checked.mode=next.mode;
      if(!checked.valid()){errFrames++;return;}
      next.complete=true;
    }
    next.ms    = millis();
    next.seq++;
    next.valid = true;
    param=next;
    return;
  }
  if(cmd==0x8B){if(len==20 && (pay[0]==0xA1 || pay[0]==0xA3) && pay[1]<=4){if(onAnalysis)onAnalysis(pay,len);}else ++errFrames;return;}
  // 其它上行（0x80 PONG 等）暂不处理
}
