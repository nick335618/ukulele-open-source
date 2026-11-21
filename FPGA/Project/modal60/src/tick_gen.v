// ============================================================
// tick_gen.v  —— 负责人 A（已实现，可直接用）
// 音频采样使能产生器：把主时钟分频成采样使能脉冲 sample_tick。
//
// 原型参数：50 MHz / 1024 = 48,828.125 Hz
//   —— 与 i2s_tx 的 LRCK（BCLK/64 = 50MHz/16/64 = 50MHz/1024）同源同频，
//      整数分频，天然同步，无需 PLL（教程 14.2 的推荐做法）。
// 音高表（note_lut）必须按这个真实 Fs 生成，见 docs/note_table.md。
// ============================================================
module tick_gen #(
    parameter CLK_HZ     = 50_000_000,  // 主时钟频率（上板后实测确认）
    parameter SAMPLE_DIV = 1024         // 采样分频值：Fs = CLK_HZ / SAMPLE_DIV
) (
    input  wire clk,
    input  wire rst_n,
    output reg  sample_tick             // 1 clk 脉冲，每个采样周期一次
);
    // 计数器宽度：SAMPLE_DIV <= 65536 时 16bit 足够；改大分频值需同步加宽
    localparam CNT_RAW=$clog2(SAMPLE_DIV);
    localparam CNT_W=CNT_RAW<1 ? 1 : CNT_RAW>16 ? 16 : CNT_RAW;
    reg [CNT_W-1:0] cnt;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cnt         <= 16'd0;
            sample_tick <= 1'b0;
        end else if (cnt == SAMPLE_DIV - 1) begin
            cnt         <= 16'd0;
            sample_tick <= 1'b1;        // 脉冲
        end else begin
            cnt         <= cnt + 16'd1;
            sample_tick <= 1'b0;
        end
    end

    // TODO(上板后, A): 用逻辑分析仪/示波器实测 sample_tick（或 i2s 的 LRCK）频率，
    // 记录真实 Fs；若与 48,828.125 Hz 有偏差，重新运行
    // src/tools/gen_note_table.py <实测Fs> 并替换 note_lut 的表。
endmodule
