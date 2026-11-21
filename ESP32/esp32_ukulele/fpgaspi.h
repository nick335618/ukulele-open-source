// ============================================================
// fpgaspi.h — ESP32 ↔ FPGA 的 SPI 字节管道（替代原 UART）
//
// 引脚按《GPIO分配与选型说明》（同学给的表）定死，**不要改**：
//     SCK  = GPIO18   （SPI3/VSPI 的 IOMUX 默认脚，可旁路 GPIO 交换矩阵）
//     MISO = GPIO19
//     MOSI = GPIO23
//     CS   = GPIO5
// 这四根是 VSPI 的默认脚 ⇒ Arduino 的 `SPI`(VSPI) 默认就用它们，且走 IO_MUX 直连。
//
// 事务格式（与 gv_host/src/spi_slave_bytes.v 严格对应，SPI 模式 0，MSB first）：
//     每次 CS 拉低→拉高 = 固定 2 字节：
//       第 1 字节：控制/状态
//         主机→从机 bit7 = 1 表示"本事务带一个要发给 FPGA 的数据字节"（在第 2 字节）
//         从机→主机 bit0 = 1 表示"FPGA 有一个要发给主机的字节"，它在第 2 字节
//         从机→主机 bit1 = 1 表示 FPGA 侧发送队列满过（诊断）
//       第 2 字节：数据（各自仅在对应标志为 1 时有效）
//
//   ★ 为什么不用 "0xFF 代表无数据" 这种省一半时钟的写法：音色数据里本来就有 0xFF，
//     拿 0xFF 当"空"会吞掉真实数据（静默丢字节）。
//
// 时钟：由 begin() 配置（当前整机默认 250 kHz；设计上限 5 MHz @50MHz 主时钟）。
//       低速给 FPGA 同步从机和较长杜邦线留出裕量；16 SCLK/数据字节仍足够承载状态帧。
// ============================================================
#pragma once
#include <Arduino.h>

class FpgaSpi {
 public:
  static const int PIN_SCK  = 18;
  static const int PIN_MISO = 19;
  static const int PIN_MOSI = 23;
  static const int PIN_CS   = 5;

  void begin(uint32_t hz = 2000000);
  // Changes only the next transaction's clock; queues and parser stay intact.
  void setClock(uint32_t hz) { hz_ = hz < 250000 ? 250000 : (hz > 1000000 ? 1000000 : hz); }
  uint32_t clockHz() const { return hz_; }

  // 反复跑事务，吞吐字节。主循环里每个 loop 调一次即可（内部会跑多轮）。
  void poll();

  void sendByte(uint8_t b);        // 排队一个要发给 FPGA 的字节
  // 环形队列的待读字节数；rxCnt_ 是累计统计量，不能拿来和读指针相减。
  int  available() const {
    int n = rxWr_ - rxRd_;
    return n < 0 ? n + RXQ : n;
  }
  int  read();                     // 取一个 FPGA 发来的字节，没数据返回 -1
  void flush();                    // 把待发字节尽量推出去

  // ---- 统计（调试）----
  volatile uint32_t txBytes = 0, rxBytes = 0, txDrop = 0, transactions = 0;
  volatile uint32_t badStatus = 0;   // 状态字节非法次数（MISO 悬空/没接的判据）
  int txPending() const;           // 队列里还有多少没发出去

 private:
  static const int TXQ = 512;      // 待发队列（比 SPI FIFO 深得多；FPGA 侧 FIFO 只有 64）
  static const int RXQ = 512;
  uint8_t txq_[TXQ]; int txWr_ = 0, txRd_ = 0;
  uint8_t rxq_[RXQ]; int rxWr_ = 0, rxRd_ = 0;   // rxCnt_ 用取模计数
  uint32_t rxCnt_ = 0;
  uint32_t hz_ = 1000000;
  bool begun_ = false;
};
