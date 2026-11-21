// 由 work/gen_tone_rom_hex.py 生成，勿手改（与 src/fpga_mem/*.hex 配套）
`ifndef TONE_ROM_PARAMS_VH
`define TONE_ROM_PARAMS_VH
localparam PT_NOTES = 28;   // 音库音数
localparam PT_N     = 18;   // 每音泛音槽数
localparam ROM_DEPTH = 512;  // ROM 深度（2 的幂）
`endif
