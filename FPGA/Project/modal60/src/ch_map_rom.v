// ============================================================================
// ch_map_rom.v —— 通道 → (弦, 品) 映射表（同步 ROM，替代深 LUT mux）
// ----------------------------------------------------------------------------
// 为什么：`touch_recog.v` 原来直接调用 `touch_tables.vh` 的 `ch2sf()`（96/72 项 case），
//   综合成很深的 LUT mux 树，是 25 条 setup 时序违例（−7.89 ns）关键路径的起点。
//   改成同步 ROM 后，这条路径变成"提前一拍给地址、当拍读数据"。
// 数据：`src/fpga_mem/chmap.hex`（深度 128，每行 2 位十六进制 = {弦[2:0], 品[4:0]}），
//       由 **work/gen_chmap_hex.py** 从 `touch_tables.vh` 导出。
// 时序：同步读，1 拍延迟（调用方负责提前一拍给地址）。
// ⚠ Gowin 综合时相对路径不可靠；这个最终工程使用本机 modal60 路径。
// ============================================================================
module ch_map_rom (
    input  wire        clk,
    input  wire [6:0]  addr,
    output reg  [7:0]  sf          // {弦[2:0], 品[4:0]}；=0 表示该通道不是品格
);
    localparam DEPTH = 128;
    reg [7:0] mem [0:DEPTH-1];

    integer i;
    initial begin
        for (i = 0; i < DEPTH; i = i + 1) mem[i] = 8'd0;
        // Gowin synthesis resolves this file most reliably through an absolute path.
        $readmemh("src/fpga_mem/chmap.hex", mem);
    end

    always @(posedge clk) sf <= mem[addr];
endmodule
