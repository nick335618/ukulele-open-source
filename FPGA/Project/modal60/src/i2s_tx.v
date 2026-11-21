// ============================================================
// i2s_tx.v  —— 负责人 B（已实现：BCLK/LRCK + 数据通路）
// 标准 I2S 发送器：把混音样本串行输出到 DAC。
// PCM5102A 与 MAX98357A 均兼容（教程 15 章）。
//
// 原型时序（全部由 50 MHz 整数分频，与 tick_gen 同源同步）：
//   BCLK = clk/16 = 3.125 MHz        （BCLK 周期 = 16 个 clk）
//   LRCK = BCLK/64 = 48,828.125 Hz   （= 采样率，与 sample_tick 完全同频）
//   槽格式 = 32bit × 2 声道 = 每帧 64 BCLK
//   标准 I2S = LRCK 切换沿之后延迟 1 个 BCLK 发送 MSB；
//              左声道 LRCK=0，右声道 LRCK=1
//   数据 = 16bit 有效数据左对齐（MSB 先发），32bit 槽内其余位补 0；
//          左右声道发送同一份样本（单声道原型）
//
// 实现要点：
//   1) bit_cnt 只在 BCLK 下降沿计数（每个 BCLK 周期 16 clk 计 1 次）。
//      若每个 BCLK 边沿都计数，帧会缩成 32 BCLK，LRCK 变成
//      97.656 kHz（设计值 48.828 kHz 的 2 倍）。
//   2) 数据在 BCLK 下降沿更新、上升沿稳定（Philips 惯例，DAC 在上升沿采样）。
//      1-BCLK 延迟由 slot_idx 的相位关系自然实现：帧边界那一拍输出的是
//      上一右槽的末位（补 0），MSB 从下一拍（LRCK 沿后第 2 个 BCLK 上升沿）出现。
//
// 验证：tb_i2s.v（目测）+ tb_i2s_check.v（自检频率与位流，全 PASS）；
// 上板后用逻辑分析仪按教程 15.4 清单实测核对（尤其数据更新边沿与
// 接收方采样沿的对齐，必要时微调）。
// ============================================================
module i2s_tx #(
    parameter SAMPLE_W    = 16,
    parameter SLOT_W      = 32,      // 每声道槽位宽（bit）
    parameter BCLK_PERIOD = 16,      // BCLK 周期 = 16 个 clk = 320ns -> 3.125MHz
    parameter FRAME_BCLKS = 64       // 每帧 BCLK 数 = 2 × SLOT_W
) (
    input  wire                clk,
    input  wire                rst_n,
    input  wire                sample_tick,   // 新样本就绪（tick_gen）
    input  wire [SAMPLE_W-1:0] sample_in,     // 混音样本（mixer 输出，16bit 有符号）
    output wire                clock_safe,
    output reg                 bclk,
    output wire                lrck,
    output reg                 data_out
);

    // ---- BCLK 产生：半周期计数器，每 BCLK_PERIOD/2 个 clk 翻转 ----
    // BCLK_PERIOD=16 时 HALF=8，4bit 计数器足够；改参数需同步加宽
    localparam HALF = BCLK_PERIOD / 2;
    reg [3:0] bclk_cnt;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            bclk_cnt <= 4'd0;
            bclk     <= 1'b0;
        end else begin
            if (bclk_cnt == HALF - 1) begin
                bclk_cnt <= 4'd0;
                bclk     <= ~bclk;
            end else
                bclk_cnt <= bclk_cnt + 4'd1;
        end
    end

    // ---- 位计数与 LRCK：每帧 64 BCLK，前 32 位为左声道 ----
    // 只在 BCLK 下降沿计数（bclk_cnt==HALF-1 且 bclk==1），每个 BCLK 周期计 1 次
    reg [5:0] bit_cnt;                 // 0..63 = 每帧 64 个 BCLK 周期
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            bit_cnt <= 6'd0;
        else if (bclk_cnt == HALF - 1 && bclk) begin
            if (bit_cnt == FRAME_BCLKS - 1)
                bit_cnt <= 6'd0;
            else
                bit_cnt <= bit_cnt + 6'd1;
        end
    end
    // LRCK：bit 0..31 = 左（0），32..63 = 右（1），在 BCLK 下降沿翻转
    assign lrck = (bit_cnt < SLOT_W) ? 1'b0 : 1'b1;

    // ---- 数据通路：sample_tick 时锁存新样本 ----
    reg [SAMPLE_W-1:0] sample_reg;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            sample_reg <= {SAMPLE_W{1'b0}};
        else if (sample_tick)
            sample_reg <= sample_in;
    end

    // ---- 移位输出：32bit 槽 = {16bit 样本, 16'b0}，MSB 先发 ----
    // slot_idx = 槽内位位置（0 = MSB）。数据在 BCLK 下降沿更新：
    //   - 标准 I2S 1-BCLK 延迟：帧边界（bit_cnt 63->0）那一拍输出的是
    //     上一右槽的末位（补 0），MSB 从下一拍开始出现；
    //   - LRCK 0->1 之后右声道同样延迟 1 个 BCLK；
    //   - slot_idx 0..15 输出样本位（MSB 先），16..31 输出 0（左对齐补位）
    reg [4:0] slot_idx;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            slot_idx <= 5'd0;
            data_out <= 1'b0;
        end else if (bclk_cnt == HALF - 1 && bclk) begin
            data_out <= (slot_idx < SAMPLE_W) ? sample_reg[SAMPLE_W - 1 - slot_idx] : 1'b0;
            slot_idx <= (slot_idx == SLOT_W - 1) ? 5'd0 : slot_idx + 5'd1;
        end
    end

    assign clock_safe=(bit_cnt==0 && bclk_cnt==0 && !bclk);
endmodule
