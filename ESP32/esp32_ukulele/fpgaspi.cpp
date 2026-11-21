// ============================================================
// fpgaspi.cpp — ESP32 ↔ FPGA SPI 字节管道实现
//
// 与 gv_host/src/spi_slave_bytes.v 配对（模式 0，MSB first，固定 2 字节事务）。
// 该从机已用 ModelSim 仿真验证（sim/tb_spi_slave.v，20 项检查全过）。
// ============================================================
#include "fpgaspi.h"

#include <SPI.h>

void FpgaSpi::begin(uint32_t hz) {
  hz_ = (hz < 250000) ? 250000 : hz;
  pinMode(PIN_CS, OUTPUT);
  digitalWrite(PIN_CS, HIGH);          // CS 空闲为高（无效态）

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_CS);
  SPI.setFrequency(hz_);
  SPI.setDataMode(SPI_MODE0);          // CPOL=0 CPHA=0，与从机一致
  SPI.setBitOrder(MSBFIRST);

  txWr_ = txRd_ = 0;
  rxWr_ = rxRd_ = 0;
  rxCnt_ = 0;
  begun_ = true;
}

int FpgaSpi::txPending() const {
  int n = txWr_ - txRd_;
  if (n < 0) n += TXQ;
  return n;
}

void FpgaSpi::sendByte(uint8_t b) {
  int n = txPending();
  if (n >= TXQ - 1) { txDrop++; return; }   // 队列满：丢（正常不该发生）
  txq_[txWr_] = b;
  txWr_ = (txWr_ + 1) % TXQ;
}

int FpgaSpi::read() {
  if (rxWr_ == rxRd_) return -1;
  int b = rxq_[rxRd_];
  rxRd_ = (rxRd_ + 1) % RXQ;
  return b;
}

void FpgaSpi::poll() {
  if (!begun_) return;

  // 每个 loop 跑若干次事务：给"主机→FPGA"发字节，顺手取回"FPGA→主机"的字节。
  // 次数不宜太大（会挤占屏幕/音频时间），也不宜太小（吞吐不够）；
  // 每事务约 64~80 µs @250kHz，32 次约 2 ms。
  const int ROUNDS = 32;

  SPI.beginTransaction(SPISettings(hz_, MSBFIRST, SPI_MODE0));
  for (int i = 0; i < ROUNDS; i++) {
    bool have = (txPending() > 0);
    uint8_t out1 = have ? 0x80 : 0x00;      // bit7 = 本事务带数据
    uint8_t out2 = 0x00;
    if (have) out2 = txq_[txRd_];

    digitalWrite(PIN_CS, LOW);
    // 从机在 CS 下降沿就摆好第 1 字节的 bit7，故先建立一点时间再打时钟
    delayMicroseconds(1);
    uint8_t in1 = SPI.transfer(out1);
    uint8_t in2 = SPI.transfer(out2);
    // 给 FPGA 的 50 MHz 域留出一个同步窗口，确保最后一个 SCK 上升沿
    // 已经被从机采到，再释放 CS。没有这个保持时间时，偶发会丢掉
    // 事务的最后一位，表现为上行 0x81/0x84 CRC 偶尔错误。
    delayMicroseconds(1);
    digitalWrite(PIN_CS, HIGH);
    delayMicroseconds(1);
    transactions++;

    if (have) {                              // 发出去一个
      txRd_ = (txRd_ + 1) % TXQ;
      txBytes++;
    }
    // ★ 状态字节合法性检查：从机只会输出 bit0(有数据)/bit1(队列满过)，
    //   其它位必须为 0。MISO 悬空时会被上拉读到全 1（0xFF）⇒ 不检查的话
    //   会被当成"有数据"，把一堆 0xFF 灌进帧解析器（实测：145k 次事务收了 114k 字节垃圾）。
    //   这里用 (in1 & 0xFC) != 0 判定为"线不对"，并计数供自检诊断。
    if (in1 & 0xFC) {
      badStatus++;
      // 不做任何事：既不发（下面已处理），也不收
    } else if (in1 & 0x01) {                 // 从机说"有数据"
      int n = rxWr_ - rxRd_;
      if (n < 0) n += RXQ;
      if (n < RXQ - 1) {
        rxq_[rxWr_] = in2;
        rxWr_ = (rxWr_ + 1) % RXQ;
        rxCnt_++;
        rxBytes++;
      }
    }
    // 没有待发也没有待收时，早退（省时间）
    if (!have && !(in1 & 0x01) && (i > 2)) break;
  }
  SPI.endTransaction();
}

void FpgaSpi::flush() {
  if (!begun_) return;
  uint32_t t0 = millis();
  while (txPending() > 0 && (millis() - t0) < 500) {
    poll();
    delay(1);
  }
}
