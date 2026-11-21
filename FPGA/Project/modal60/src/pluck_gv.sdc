// ============================================================================
// pluck_gv.sdc  —— top_gv_host 时钟约束（Gowin SDC）
// 主时钟：板载 50 MHz 晶振 -> clk(E2)，周期 20 ns。
// Gowin IDE：Add Constraint 加入本文件，或在 Synthesize 页面设 Clock = 50 MHz。
// 说明：全设计单时钟域（50MHz）；UART 用 16× 过采样、I2S/tick 用整数分频使能，
//       均无需额外生成时钟。若后续用 PLL 再做 create_generated_clock。
// ============================================================================
create_clock -name clk_50m -period 20.000 -waveform {0 10.000} [get_ports {clk}]
