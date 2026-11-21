// ============================================================================
// spi_slave_bytes.v —— SPI 从机「字节管道」（ESP32↔FPGA 的物理层，替代 UART）
// ----------------------------------------------------------------------------
// 设计目标：**不动上面任何一层的帧逻辑**。对外只暴露"收一个字节 / 发一个字节"，
// 形状与 uart_rx / uart_tx 一致 ⇒ 顶层把线改接过来即可；帧协议/CRC/分帧一行不改。
//
//   rx_byte / rx_valid   ← 主机通过 MOSI 送来的字节（同 uart_rx 语义）
//   tx_byte / tx_start   → 要发给主机的字节（同 uart_tx 语义）
//   tx_ready             → 发送 FIFO 未满
//
// ----------------------------------------------------------------------------
// ★ 事务格式（SPI 模式 0：CPOL=0 CPHA=0，MSB first）
// ----------------------------------------------------------------------------
//   每次 CS 拉低→拉高 = **固定 2 字节 = 16 个 SCLK**：
//
//     第 1 字节：控制/状态
//        MOSI bit7 = 1 → 本次带一个"要发给 FPGA 的数据字节"（在第 2 字节）
//        MISO bit0 = 1 → FPGA 有一个"要发给主机的字节"（在第 2 字节）
//        MISO bit1 = 1 → FPGA 发送队列满过（诊断，正常 0）
//        其余位保留：读 0、写忽略
//     第 2 字节：数据（各自仅在对应标志为 1 时有效）
//
//   ★ 为什么不用"0xFF 代表无数据"这种省一半时钟的写法：
//     音色数据里本来就会出现 0xFF（fpga_mem/*.hex 实测有），拿 0xFF 当"空"
//     会把真实数据吞掉 ⇒ 静默丢字节。多花 8 个 SCLK 换正确性。
//
//   吞吐：16 SCLK / 数据字节 ⇒ 2 MHz 约 125 KB/s、5 MHz 约 312 KB/s
//        （原 UART 115200 只有 11.5 KB/s）。音色库 5120B ≈ 82 kbit
//        ⇒ 2 MHz 下约 41 ms（原来 2 秒）。
//
// ----------------------------------------------------------------------------
// 位序推导（写错过两版，这次先把下标推清楚再写）
// ----------------------------------------------------------------------------
//   rx_shift <= {rx_shift[14:0], mosi};   每次上升沿左移、新位进 bit0
//     ⇒ 移满 16 次后：bit15 = **最先到的那一位**（MSB first ✓）
//     ⇒ 在第 16 个上升沿当拍，手上是"已移 15 次"的值：
//          完整 16 位 = {rx_shift[14:0], mosi_s}
//          第 1 字节  = 完整[15:8] = rx_shift[14:7]
//          第 2 字节  = 完整[7:0]  = {rx_shift[6:0], mosi_s}
//        其中 rx_shift[14] = 整帧第 1 位 = 第 1 字节的 bit7（控制标志）✓
//   MISO 同理由 tx_shift 高位起摆：CS 下降沿先摆 tx_shift[15]，
//   之后每个下降沿摆 tx_shift[15-bitc]（bitc = 已采样位数 1..15）✓
//
//   跨时钟域：SCLK/CS/MOSI 先打两拍同步再在 clk 域做边沿检测（不用 SCLK 直接
//   驱动逻辑，否则无法约束时序）。⇒ **SCLK 必须明显低于 clk**，建议 ≤ 5 MHz
//   @ 50 MHz；ESP32 侧当前用 2 MHz，余量充足。
// ============================================================================
`timescale 1ns / 1ps

module spi_slave_bytes #(
    parameter integer CLK_HZ     = 50_000_000,
    parameter integer SCK_MAX_HZ = 5_000_000,
    parameter integer FIFO_AW    = 6            // 发送 FIFO 深度 = 2^6 = 64
)(
    input  wire        clk,
    input  wire        rst_n,

    input  wire        sck,
    input  wire        cs_n,
    input  wire        mosi,
    output reg         miso,        // 空闲高；外部建议加上拉

    output reg  [7:0]  rx_byte,
    output reg         rx_valid,    // 1 拍脉冲

    input  wire [7:0]  tx_byte,
    input  wire        tx_start,
    output wire        tx_ready,

    output reg  [15:0] rx_cnt,
    output reg  [15:0] tx_cnt,
    output reg  [15:0] tx_drop,
    output reg  [7:0]  fifo_level,
    output reg  [7:0]  dbg_b1,      // 最近一次事务第 1 字节（调试）
    output reg  [7:0]  dbg_b2       // 最近一次事务第 2 字节（调试）
);

    // ==================================================================
    // 1) 输入同步 + 边沿检测
    // ==================================================================
    reg sck_m, sck_s, cs_m, cs_s, mosi_m, mosi_s;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            sck_m <= 1'b0; sck_s <= 1'b0;
            cs_m  <= 1'b1; cs_s  <= 1'b1;
            mosi_m <= 1'b0; mosi_s <= 1'b0;
        end else begin
            sck_m  <= sck;   sck_s  <= sck_m;
            cs_m   <= cs_n;  cs_s   <= cs_m;
            mosi_m <= mosi;  mosi_s <= mosi_m;
        end
    end

    wire sck_rise =  sck_m & ~sck_s;
    wire sck_fall = ~sck_m &  sck_s;
    wire cs_fall  = ~cs_m  &  cs_s;
    wire cs_rise  =  cs_m  & ~cs_s;
    wire cs_low   = ~cs_s;

    // ==================================================================
    // 2) 发送 FIFO
    // ==================================================================
    // ★★ v81 修（代码复核发现的真 bug）：**满判断原来永远是假的**。
    //   原来的写法是：
    //       reg  [FIFO_AW-1:0] wptr, rptr;                    // 6 位
    //       wire [FIFO_AW:0]   fcnt = {1'b0,wptr} - {1'b0,rptr};
    //       wire               ffull = (fcnt == FD);           // FD = 64
    //   两个 **6 位**指针相减，结果只可能是 0..63 —— **永远等于不了 64**
    //   ⇒ `ffull` 恒为假、`tx_ready` 恒为 1 ⇒ 上层会一直压字节，
    //     而写满之后 `wptr == rptr`（`fcnt` 变 0），`fempty` 也跟着变成"空"
    //   ⇒ 未读数据被静默覆盖，主机侧收到**缺字节或错位**的帧。
    //   （这里还有个更隐蔽的地方：`fcnt` 声明成 `[FIFO_AW:0]` 看起来"多一位"，
    //     但右边两个操作数都是 6 位，Verilog 会**按操作数里最宽的那个**（6 位）做减法，
    //     零扩展到 7 位 —— 多出来的那位永远是 0。位宽写在左边骗不了自己。）
    //   ⇒ 正确做法：**指针本身多一位**（经典异步 FIFO 的写法），
    //     用最高位区分"空"和"满"，这样 0..64 全部可表示。
    localparam integer FD = 1 << FIFO_AW;
    reg  [7:0]         fifo [0:FD-1];
    reg  [FIFO_AW:0]   wptr, rptr;                 // ★ 7 位（多一位做满/空区分）
    wire [FIFO_AW:0]   fcnt   = wptr - rptr;       // ★ 7 位减法，0..64 全部可表示
    wire               fempty = (fcnt == 0);
    wire               ffull  = (fcnt == FD);      // ★ 现在真的能等于 64 了

    assign tx_ready = ~ffull;

    // ==================================================================
    // 3) 事务级移位
    // ==================================================================
    reg [4:0]  bitc;         // 已采样位数 0..16
    reg [15:0] rx_shift;
    reg [15:0] tx_shift;     // CS 下降沿一次组装好：{第2字节, 第1字节}
    reg        tx_have;      // 本次事务有字节要发给主机
    reg        tx_commit;    // 事务正常完成后推进 rptr

    // 第 1 字节（状态）的组合值：
    //   bit0 = 1 → 有数据要发给主机（★必须随 FIFO 状态变；写死 1 过一次，
    //              仿真立刻抓到"空队列也报有数据"）
    //   bit1 = 1 → 发送队列满过（诊断）
    // ★注意括号：Verilog 里 ?: 优先级低于 |，写成 `a ? b : c | d` 会被解析成
    //   `a ? b : (c|d)` —— 这里显式加括号。
    wire [7:0] st_comb = (fempty ? 8'h00 : 8'h01)
                       | ((tx_drop != 8'd0) ? 8'h02 : 8'h00);
    // 状态字节必须在 CS 下降沿【锁存】，否则发送期间 FIFO 变化会让线上的字节前后不一致
    reg  [7:0] tx_stat;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            miso <= 1'b1; bitc <= 5'd0;
            rx_shift <= 16'd0; tx_shift <= 16'd0;
            tx_have <= 1'b0; tx_commit <= 1'b0;
            wptr <= {(FIFO_AW+1){1'b0}}; rptr <= {(FIFO_AW+1){1'b0}};
            rx_byte <= 8'd0; rx_valid <= 1'b0;
            rx_cnt <= 16'd0; tx_cnt <= 16'd0; tx_drop <= 16'd0;
            fifo_level <= 8'd0; dbg_b1 <= 8'd0; dbg_b2 <= 8'd0;
        end else begin
            rx_valid <= 1'b0;

            // ---------------- 上层给字节 → 压 FIFO ----------------
            if (tx_start) begin
                if (!ffull) begin
                    // ★ 指针是 FIFO_AW+1 位，寻址只用低 FIFO_AW 位
                    fifo[wptr[FIFO_AW-1:0]] <= tx_byte;
                    wptr <= wptr + 1'b1;
                end else begin
                    tx_drop <= tx_drop + 16'd1;
                end
            end

            // ---------------- CS 下降沿：组装整个 16 位 MISO 内容 ----------------
            if (cs_fall) begin
                bitc      <= 5'd0;
                rx_shift  <= 16'd0;
                tx_have   <= ~fempty;
                tx_commit <= ~fempty;
                // ★字节顺序（MSB first）：先发的是 tx_shift[15:8] ⇒ 第 1 字节必须是
                //   状态字节、第 2 字节才是数据。写反过一次（仿真立刻抓到：
                //   主机把数据字节读成了状态字节 0x01）。别改回去。
                tx_stat   <= st_comb;
                tx_shift  <= {st_comb, fempty ? 8'h00 : fifo[rptr[FIFO_AW-1:0]]};
                miso      <= st_comb[7];       // 第 1 字节的 MSB（恒 0）
            end

            // ---------------- 事务进行中 ----------------
            else if (cs_low) begin
                // 上升沿：采样 MOSI
                if (sck_rise && (bitc < 5'd16)) begin
                    bitc <= bitc + 5'd1;
                    if (bitc == 5'd15) begin
                        // 第 16 位（最后一个）当拍就能交付，用的是推导好的下标
                        dbg_b1 <= rx_shift[14:7];        // 第 1 字节
                        dbg_b2 <= {rx_shift[6:0], mosi_s}; // 第 2 字节
                        if (rx_shift[14]) begin          // 第 1 字节的 bit7 = 控制标志
                            rx_byte  <= {rx_shift[6:0], mosi_s};
                            rx_valid <= 1'b1;
                            rx_cnt   <= rx_cnt + 16'd1;
                        end
                    end else begin
                        rx_shift <= {rx_shift[14:0], mosi_s};
                    end
                end

                // 下降沿：摆出下一位 MISO（bitc = 已采样位数 1..15）
                if (sck_fall && (bitc >= 5'd1) && (bitc <= 5'd15))
                    miso <= tx_shift[5'd15 - bitc];
            end

            // ---------------- CS 上升沿：事务结束 ----------------
            if (cs_rise) begin
                miso <= 1'b1;
                bitc <= 5'd0;
                if (tx_commit && tx_have && !fempty) begin
                    rptr   <= rptr + 1'b1;
                    tx_cnt <= tx_cnt + 16'd1;
                end
                tx_have   <= 1'b0;
                tx_commit <= 1'b0;
            end

            // ★ 注意：fcnt 是 [FIFO_AW:0]（FIFO_AW=6 时 7 位，值域 0..64），
            //   fifo_level 是 8 位 ⇒ **显式零扩展**。
            //   （v81 改：指针从 6 位变 7 位之后 fcnt 才真的能到 64；见上面 FIFO 段的长注释。
            //     ⚠ 这里**不能**写 `fcnt[7:0]` —— 综合器会直接报 EX3784 "Index 7 is out of
            //     range [6:0] for 'fcnt'"，整个模块会被降级成黑盒。已实测踩过。）
            fifo_level <= {1'b0, fcnt};
        end
    end

endmodule
