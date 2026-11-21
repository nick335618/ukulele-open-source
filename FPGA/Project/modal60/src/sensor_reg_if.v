// ============================================================================
// sensor_reg_if.v —— ★★ **可替换的传感器寄存器访问层**（整个工程的"换芯片接口"）
// ----------------------------------------------------------------------------
// 设计要点：**把可替换点放在"寄存器级"**，而不是放在"扫描 / 识别"那一层。
//   AD7147 的 I²C 版和 SPI 版**寄存器、上电序列、CDC 结果地址完全一样**，
//   只有"怎么把 16 位地址 + 16 位数据搬到芯片里"这一层不同：
//
//       读者（ad7147_scan.v）
//                  │  start / wr / chip_sel / dev / addr / wdata → rdata / done / busy / err
//        ┌─────────┴─────────┐
//        │  sensor_reg_if.v  │   ← 本文件：按参数选一路
//        └─────────┬─────────┘
//        SENSOR_SPI=0 → ad7147_i2c.v（I²C 版，当前板上这两颗）
//        SENSOR_SPI=1 → ad7147_spi.v（SPI 款；已从触摸工程搬进来）
//
// ★ v48 扩展性改造：**片数参数化**
//   - `N_CHIPS`（1..8）决定 `spi_cs_n` 的位宽；
//   - SPI 版用 `chip_sel[2:0]` 做**一位热码**片选（8 片正好配 74LVC138 的 Y0..Y7）；
//   - I²C 版忽略 chip_sel，用 `dev[6:0]` 从机地址（由扫描层按 `DEV_BASE` 算）。
//   ⚠ I²C 面板最多只能 2 片：74LVC138 只把 Y0/Y1 接到两颗芯片的 ADD1，
//     第 3 片以后会撞地址 ⇒ **8 片 = 必须用 SPI 款**。
// ============================================================================
module sensor_reg_if #(
    parameter SENSOR_SPI = 0,           // 0 = I²C 版；1 = SPI 款
    parameter CLK_HZ     = 50_000_000,
    parameter SCL_HZ     = 200_000,     // I²C 时钟
    parameter SPI_CLK_DIV= 24,          // SPI 半周期 = DIV+1 个 clk（24 ⇒ 1 MHz）
    parameter DATA_SHIFT = 0,           // SPI 读数据相位修正（见 ad7147_spi.v）
    parameter N_CHIPS    = 2            // ★ 片数（1..8）；SPI 版才能 >2
) (
    input  wire        clk,
    input  wire        rst_n,
    // ---- 统一请求接口 ----
    input  wire        start,           // 单拍：启动一次寄存器事务
    input  wire        abort,           // 单拍：调用方看门狗超时，强制收尾（仅 I²C 用）
    input  wire        wr,              // 1 = 写，0 = 读
    input  wire [2:0]  chip_sel,        // ★ 目标芯片序号（SPI 用；I²C 忽略）
    input  wire [6:0]  dev,             // I²C 从机地址（SPI 忽略）
    input  wire [9:0]  addr,
    input  wire [15:0] wdata,
    output wire [15:0] rdata,
    output wire        done,
    output wire        busy,
    output wire        err,
    // ---- I²C 引脚（开漏）----
    output wire        i2c_scl_oe,
    output wire        i2c_sda_oe,
    input  wire        i2c_sda_in,
    // ---- SPI 引脚 ----
    output wire        spi_sck,
    output wire        spi_mosi,
    input  wire        spi_miso,
    output wire [2:0] spi_addr,         // final PCB decoder ADDR2:0
    output wire [N_CHIPS-1:0] spi_cs_n  // logical A..H, debug/test only
);

    wire [15:0] rdata_i2c, rdata_spi;
    wire        done_i2c,  done_spi;
    wire        busy_i2c,  busy_spi;
    wire        err_i2c;
    wire        cs_n_raw;      // SPI 版当前事务的原始片选（低有效）

    // 8 位一位热码：只有 chip_sel 那一位是 1（N_CHIPS ≤ 8）
    wire [7:0] cs_onehot = (8'd1 << chip_sel);

    generate
    if (SENSOR_SPI == 0) begin : g_i2c
        // ---------------- I²C 版 AD7147（当前板上这两颗）----------------
        ad7147_i2c #(.CLK_HZ(CLK_HZ), .SCL_HZ(SCL_HZ)) u_regs (
            .clk(clk), .rst_n(rst_n),
            .start(start), .wr(wr), .dev(dev), .addr(addr), .wdata(wdata),
            .abort(abort),
            .rdata(rdata_i2c), .done(done_i2c), .busy(busy_i2c), .err(err_i2c),
            .scl_oe(i2c_scl_oe), .sda_oe(i2c_sda_oe), .sda_in(i2c_sda_in)
        );
        assign rdata_spi = 16'h0000; assign done_spi = 1'b0; assign busy_spi = 1'b0;
        assign spi_sck = 1'b0; assign spi_mosi = 1'b0;
        assign spi_addr = 3'd0;
        assign spi_cs_n = {N_CHIPS{1'b1}};      // 全部不选
    end else begin : g_spi
        // ---------------- SPI 款 AD7147（换芯片时把 SENSOR_SPI 改成 1）----------------
        //   ad7147_spi.v 一次事务固定 32 个 SCLK（16 位命令 + 16 位数据），
        //   命令字高位固定 11100 + R/W# + 10 位地址；片选由 chip_sel 一位热码译出。
        ad7147_spi #(.CLK_DIV(SPI_CLK_DIV), .DATA_SHIFT(DATA_SHIFT)) u_regs (
            .clk(clk), .rst_n(rst_n),
            .start(start), .wr(wr), .chip_sel(chip_sel), .addr(addr), .wdata(wdata),
            .rdata(rdata_spi), .done(done_spi), .busy(busy_spi),
            .sck(spi_sck), .mosi(spi_mosi), .miso(spi_miso), .cs_n(cs_n_raw),
            .decoder_addr(spi_addr)
        );
        // 选中那一路输出 cs_n_raw（低有效），其余保持高（不选）
        genvar gc;
        for (gc = 0; gc < N_CHIPS; gc = gc + 1) begin : g_cs
            assign spi_cs_n[gc] = cs_onehot[gc] ? cs_n_raw : 1'b1;
        end
        assign rdata_i2c = 16'h0000; assign done_i2c = 1'b0; assign busy_i2c = 1'b0;
        assign err_i2c = 1'b0;
        assign i2c_scl_oe = 1'b1; assign i2c_sda_oe = 1'b1;   // I²C 不用：释放总线
    end
    endgenerate

    assign rdata = (SENSOR_SPI == 0) ? rdata_i2c : rdata_spi;
    assign done  = (SENSOR_SPI == 0) ? done_i2c  : done_spi;
    assign busy  = (SENSOR_SPI == 0) ? busy_i2c  : busy_spi;
    assign err   = (SENSOR_SPI == 0) ? err_i2c   : 1'b0;   // SPI 版没有 ACK 概念

endmodule
