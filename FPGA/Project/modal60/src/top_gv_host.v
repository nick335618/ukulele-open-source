// ============================================================================
// top_gv_host.v —— 触摸识别 / 上位机串口控制 二选一顶层（识别 + 引擎装配）
// ----------------------------------------------------------------------------
// ★ v45（本次）：**两条命令来源都保留，用一个参数切换**，方便 A/B 对比：
//
//   USE_UART_CMD = 0（**默认**）：片上触摸识别
//       AD7147(I²C) → ad7147_scan（上电序列 + 周期读 24 路 CDC）
//                   → touch_recog（单音 / 和弦 / 滑音）
//                   → cmd_note_ev / note_voice / cmd_glide_ev / glide_target / gain
//   USE_UART_CMD = 1：恢复 v44 之前的老链路（串口命令回放，用于对比）
//       rx → uart_rx → cmd_decoder → 同一组 cmd_* 信号，PING 由 uart_tx 回 PONG
//
//   两条链路产出**完全同名**的信号，所以"事件判定 / 音频 / LED"这几段一行都不用改。
//   老链路用的是 v44 之前的原样代码（uart_rx.v / uart_tx.v / cmd_decoder.v，
//   原来被移到 src/_unused_uart/，现在放回 src/ 并挂在同一个 generate 分支下）。
//   颤音：触摸模式由 gv_host v64 揉弦检测触发；UART 模式使用 VIB/VIB_SET。
//   音高调制只作用于最近实际拨响的动态声部，默认 5.5 Hz、约 ±30 音分。
//
// 结构：
//   [g_uart_cmd]  rx(UART) -> uart_rx -> cmd_decoder --------┐
//   [g_touch]     AD7147   -> ad7147_scan -> touch_recog ----┤
//                                                           ▼
//                            事件判定(NOTE/GLIDE) -> trig 分派到引擎
//   触摸/光电事件 -> 四弦动态 additive4_tone -> limiter -> i2s_tx
//
// ★ v48（扩展性改造）：**触摸侧全部参数化**，默认值直接从 `touch_tables.vh` 的宏取，
//   保证"生成的通道表"与"RTL 参数"永远一致（换面板 = 改映射 CSV + 重跑生成脚本 + 重编译）：
//       N_CHIPS / SP_CH  → 芯片数 / 每片级数（当前 2×12 = 24 通道；最终 8×12 = 96 通道）
//       N_STR / N_FRET   → 弦数 / 每弦品数（当前 4×3 = 12 品格；最终 4×18 = 72 品格）
//       DECIM            → 抽取率 256/128/64 ⇒ 每片更新周期 36.9 / 18.4 / 9.2 ms
//   注意：最终面板 8 片必须用 **SPI 款**（I²C 面板受 74LVC138 只引出 Y0/Y1 限制，只能 2 片）。
//
// 音库/声部映射（voice = 上位机发的全局声部号）：
//   BANK_NOTES = 33（**当前默认：吉他库 33 音**）：上位机琴弦 voice = 品号 = MIDI-50
//       （D3..A#5 → 0..32，见 host/index.html 与 src/docs/note_table.md）。
//       ⚠ 必须同时在 additive4_tone.v include 处 `define BANK_GUITAR33，
//         否则只有前 15 个声部对得上、其余声部错音/静音。
//       引擎拆分 3×NV11（E0:0..10 E1:11..21 E2:22..32，BASE 0/11/22，
//       槽位 11×15×6 = 990 ≤ 1024 拍）。
//   BANK_NOTES = 28（尤克里里 record28 半音阶）：voice = 品号 = MIDI-60
//       （C4..D#6 → 0..27）。⚠ 需 `define BANK_REC28。
//       引擎拆分 4×NV7（E0:0..6 E1:7..13 E2:14..20 E3:21..27，BASE 0/7/14/21）。
//   BANK_NOTES = 15（旧白键库 partial_bank.vh）：voice = 白键序 0..14（C4..C6 白键），
//       引擎拆分 NV 7/6/2。
//
// ★ v62（本次）—— 上面 BANK_NOTES 那段是**旧版（多引擎、声部↔音高死绑）**的遗留描述：
//   当前工程是"单引擎 12 声部 + 动态绑定"，音库号是触发时写进 `vnote[]` 的，与 BANK_NOTES
//   这个参数已经无关（它现在是死参数）。与音库真正相关的两个口径改成：
//     · 步长 K  → **运行时端口 `pt_k`**（esp_timbre==1 时 15，否则 18），
//                 引擎在窗口起点与 vnote_s 同拍快照成 pt_k_s。见 docs/音色库步长切换.md
//     · 音库号位宽 **NOTE_BITS = 6**（guitar33 的 0..32 需要 6 bit；5 bit 会把 A#5(32) 截成 0）
//
// 两种构建（与音库正交）：
//   GV_PITCH = 1（默认）：保留滑音控制器，并把倍率只送到最近光电拨响的动态声部；
//       音高表、包络和拨弦瞬态仍由 additive4_tone 基线提供。
//   GV_PITCH = 0：固定 1.0 音高比例，省掉 pitch_ctl，作为“只拨弦”A/B 基线。
// ============================================================================
`include "touch_tables.vh"

`include "performance_defaults.vh"
module top_gv_host #(
    parameter CLK_HZ   = 50_000_000,
    parameter BAUD     = 115_200, // ★ v45：老 UART 链路波特率（只有 USE_UART_CMD=1 时用到）
    parameter SENSOR_SPI = 1,     // final 2026-10-01 AD7147 SPI PCB
    parameter N_CHIPS  = `TT_N_CHIPS,   // ★ v48：芯片数（1..8；I²C 面板最多 2 片，>2 必须 SPI）
    parameter SP_CH    = `TT_SP_CH,     // ★ v48：每片级数（AD7147 = 12）
    parameter DECIM    = 128,           // 12 stages: ~18.4ms conversion + ~3.4ms readout
    parameter N_TOUCH_CH = `TT_NCH,     // 72 active electrodes, omit unused stages
    parameter N_STR    = `TT_N_STR,     // ★ v48：弦数
    parameter N_FRET   = `TT_N_FRET,    // ★ v48：每弦品数
    parameter USE_UART_CMD = 0,   // ★ v45：命令来源二选一
                                  //   0 = 片上触摸识别（ad7147_scan + touch_recog）—— 默认，同 v44
                                  //   1 = 老 UART 命令链路（uart_rx + cmd_decoder + PONG）—— 对比用
    parameter TELEMETRY = 0,      // 0=off, 1=legacy touch+optical, 2=RAM touch diagnostics only
                                  //   1 = tx(E3) 持续发遥测文本帧，上位机 work/host/touch_monitor.py
                                  //   0 = tx 停在空闲高电平（完全不占串口）
                                  //   ⚠ 只发不收，rx(A1) 不参与；接法：FPGA tx → USB-TTL RXD + 共地
    parameter TELE_PERIOD_MS = 250, // 72 channels at 115200 baud: about 150 ms per frame
                                    //   Diagnostics do not send UART commands to the audio engine.
    parameter DBG_PIN_PROBE = 0,  // ★ 诊断用：=1 时 tp_scl 推挽输出高、tp_sda 推挽输出低，
                                  //   用来"用电平把 F6/F7 在排针上的位置找出来"。
                                  //   ⚠ 必须同时用 src/probe_pins.cst（去掉 OPEN_DRAIN=ON），
                                  //     而且**只临时烧来测量**，测完立刻烧回 gv_host.fs。
    parameter BANK_NOTES = 28,   // 33=吉他库 D3..A#5（BANK_GUITAR33，默认）；28=尤克里里半音阶；15=旧白键库
    parameter GV_PITCH = 1'b1,   // 默认启用滑音；资源不足时可改 0 作“只拨弦”A/B 基线
    // ★ 2026-09-23「J5 八脚侦探」：=1 时把 J5 的 8 个信号脚**全部置成高阻输入**，
    //   各自统计跳变次数并塞进 $O 遥测末尾 8 个字段。用途见下方注释。
    //   ⚠ 探测模式下 ADC 与 ESP32 SPI 都停止工作，**只临时烧来测线，测完立刻烧回**。
    //   ★ 2026-09-23 结论：探测方法本身不可靠（J5 八脚互相串扰，计数全是 65535），
    //     已作废、默认关闭。真正的故障定位是靠"正常模式下看 SPIRXC/CMDCNT 涨不涨"。
    //   ★ 1 = 【孔位标定】模式：J5 八脚永久三态，遥测 22..29 报八脚实时电平。
    //     配合 CST 里把八脚全设成 PULL_MODE=UP：没接线的孔读 1，用 GND 线碰到的孔读 0。
    //     ★ 2026-09-23 晚：用户给了板子正面的孔位丝印图，直接照着插就行，
    //       标定模式不再需要 ⇒ 改回 0。标定固件另存 fw_scripts/_gv_j5lvl.fs。
    parameter J5_PROBE = 0,
    parameter VIB_RATE_X10 = 55, // 5.5 Hz sine, as in gv_host
    parameter VIB_DEPTH = 30,    // approximately +/-30 cents
    parameter VIB_FADE_MS = 200,
    parameter DEBUG_LA = 0,       // 1=保留片内逻辑分析仪；发布版默认关闭，释放 1 块 BSRAM
    parameter DEBUG_OPTO = 0,     // 现场排查暂时开启 $O 光电/音频诊断；排查后可设为 0
    parameter ENABLE_LED = 1,    // 已通过协议仿真，继续与触摸/音频一起上板验证
    parameter ENABLE_FORCE = 0   // 关闭光电力度交互：所有合法拨弦固定使用原始满档激励
) (
    input  wire clk,             // 板载 50MHz
    // ★ v45：老 UART 链路的两根线（**只有 USE_UART_CMD=1 时才真正使用**）
    //   rx = 球号 A1（Dock J3-17，IOB24A）← USB-TTL 模块 TXD
    //   tx = 球号 E3（Dock J3-20，IOB60A）→ USB-TTL 模块 RXD
    //   USE_UART_CMD=0 时 rx 不参与逻辑、tx 恒定输出 1（UART 空闲电平），两根线可以不接。
    input  wire rx,
    output wire tx,
    // ★ v44：触摸板传感器接口（I²C 版 AD7147）
    //   ⚠ 旧文档推测 SCL/SDA 用 C2/B2（J3-13/14）**被 PnR 拒绝**（PR2017：专用配置 I²C 脚），
    //     实际改普通 IO：面板 SCL → Dock J3-37（球 F6）、面板 SDA → J3-38（球 F7）。
    //   两根都是**开漏**：RTL 只拉低/释放，必须外接 4.7k~10k 上拉到 3.3V。
    output wire tp_scl,          // → 面板 SCL
    inout  wire tp_sda,          // ↔ 面板 SDA
    output wire tp_sck, tp_mosi,
    input  wire tp_miso,
    output wire [2:0] tp_addr,
    output wire led_cs_n, tp_en_n, // CN1-6 LP5860 SS; CN1-7 decoder EN#
    output wire i2s_bclk,
    output wire i2s_lrck,
    output wire i2s_data,
    // ★★ 2026-09-23 补：PCM5102A 的 **MCLK/SCK**（模块丝印是 `SCL`）必须由 FPGA 提供。
    //   本工程 fS = 50MHz/1024 = **48828.125 Hz，不是标准采样率**，PCM5102A 内部
    //   三线 PLL 只支持 48/44.1/96k 这些精确值 ⇒ 锁不住就**完全无声**。
    //   ⇒ 必须走四线模式：给它 256×fS 的主时钟。
    //   05_i2s_tone / 06_full_system / 13_audio_pintest 三个能出声的工程全都驱动
    //   **G5**，唯独融合成本工程时把这一根丢了 —— 这就是"光电触发都正常却听不到声音"
    //   的根因。详见 docs/接线图_audio_ext音频验证.md 第 81 行与第 162 行。
    output wire i2s_sck,         // → PCM5102A 模块的 SCL 脚（G5，J6 第 8 脚）
    // ★ v52：4 位硬件状态总线（用万用表量电平就能定位故障，见 .cst 的引脚分配）
    //   [0] = sensor_ok   两颗 AD7147 都读到 0x147x
    //   [1] = id_ok[0]    0x2C（A 组电极）那颗 ID 对上了
    //   [2] = id_ok[1]    0x2E（B 组电极）那颗 ID 对上了
    //   [3] = err_any     出现过 I²C 错误（从机不 ACK）
    output wire [3:0] dbg_status,

    // ============ ★ ESP32 链路（串口屏的中间桥）============
    //   为什么单独加一路而不是复用 PC 那路（rx/tx）：
    //     PC 的 rx/tx 现在挂着遥测（TELEMETRY=1，tx 被 telemetry_tx 独占），
    //     而屏幕要的是"收命令 + 收一份轻量状态"，两者用途不同，分开最干净。
    //   ★ 2026-09-23 改成 SPI（用户/同学要求）：ESP32 侧用 SPI3(VSPI) + IO_MUX 直连，
    //     引脚由同学的《GPIO分配与选型说明》定死：
    //       ESP32 GPIO18 = SCK   → spi_sck   (FPGA 收)
    //       ESP32 GPIO23 = MOSI  → spi_mosi  (FPGA 收)
    //       ESP32 GPIO19 = MISO  ← spi_miso  (FPGA 发)
    //       ESP32 GPIO5  = CS    → spi_cs_n  (FPGA 收，低有效)
    //     帧协议**完全不变**（AA CMD LEN PAYLOAD... CRC），只换物理层 ⇒
    //     上面所有已离线验证过的通路（0x06/07/08/09/0x81/0x84）一行都不用改。
    //     详见 spi_slave_bytes.v 头部（2 字节事务格式）与 docs/接线后验证清单.md。
    input  wire spi_sck,
    input  wire spi_cs_n,
    input  wire spi_mosi,
    output wire spi_miso,

    // ============ ★ 光电拨弦链（从 06 工程移植）============
    //   ADS7950 四通道采集 + 光电检测。2026-10-10 按用户要求恢复四个球位：
    //     adc_sclk = K5  adc_cs_n = L5
    //     adc_sdi  = E11 adc_sdo  = A11（保留内部上拉）
    //   仅修改 CST 落位；SPI时钟、采集、阈值、抗重复触发及音频逻辑未改。
    //   ★★ 2026-09-23 晚：这三根**从 inout 改回 output**。
    //     它们一度被我改成 inout（为了"J5 孔位侦探"那个已经废弃的诊断功能需要三态能力），
    //     PnR 报告里就从 `out` 变成了 `io` = IOBUF（多挂了输入缓冲）。
    //     症状很隐蔽：**同一个 ads7950_spi.v、同样的引脚、同样的 SCLK=3.125MHz、同一块光电板**，
    //       跑 14_opto_tune（output/OBUF）：四路 1979/2128/1895/1921 ✓
    //       跑 gv_host（inout/IOBUF）   ：ch0/ch2/ch3 读到几十~几百，只有 ch1 正常 ✗
    //     而通道地址半字节 44 帧全对、addr_err 仅 1.8% ⇒ SPI 读写通路是好的，
    //     是 ADS7950 对 SDI 的建立/保持被 IOBUF 的输出特性破坏了。
    //     ⇒ 恢复成与 14/06 逐字一致的 output。
    output wire adc_sclk,
    output wire adc_cs_n,
    output wire adc_sdi,
    input  wire adc_sdo,
    // 运放静音控制输入：配置完成后 H5 永久输出逻辑高电平。
    output wire opamp_mute_high
);

    assign opamp_mute_high = 1'b1;
    // Standby control is independent of the H5 constant output and config registers.
    wire power_accept,power_compute,power_modules_sleep,power_clocks_enable;
    wire power_clear,power_wake_start,power_flush;
    wire [7:0] power_volume,power_state,power_result;
    wire [15:0] power_id,power_epoch,power_elapsed;
    wire power_touch_asleep,power_touch_ready,power_touch_sample,power_led_asleep;
    wire engine_busy,power_clock_safe;
    reg power_clock_gate;
    reg power_cmd_valid,power_getreq,power_sleep_cmd;
    reg [7:0] es_pay[0:7];
    reg [2:0] gesture_mask; // glide, vibrato, dedicated auxiliary sensor source
    reg [1:0] glide_owner_string;
    reg glide_owner_valid;
    reg eng_trig;
    wire [18:0] audio_pitch_mul;
    reg [1:0] voice_model,eng_model;
    wire [1:0] performance_model;
    reg [7:0] model_token;reg model_req;
    reg [6:0] envelope_query;
    reg [7:0] envelope_token,envelope_new_value;
    reg [1:0] envelope_operation;
    reg envelope_commit;
    wire envelope_ready,envelope_done;
    wire [27:0] envelope_bank_modified;
    wire [7:0] envelope_play,envelope_read;
    reg [3:0] capo_requested;
    reg [7:0] capo_token;
    reg capo_req;
    wire [18:0] capo_combined;
    wire [16:0] capo_common;
    wire [3:0] capo_applied;
    reg [3:0] capo_seen;
    wire capo_change=capo_seen!=capo_applied;
    always @(posedge clk or negedge rst_n)if(!rst_n)capo_seen<=0;else capo_seen<=capo_applied;
    // Physical pad P above capo C uses ROM fingering P-C, then the shared
    // capo pitch ratio restores absolute pitch P. Covered pads are open.
    function [4:0] capo_relative(input [4:0] physical);
      begin capo_relative=physical>{1'b0,capo_applied}?physical-{1'b0,capo_applied}:5'd0;end
    endfunction
    reg chain_enable;reg [3:0] chain_mask;reg [27:0] chain_strengths;reg [2:0] chain_target;
    reg [7:0] chain_token;reg chain_req,chain_next_armed;
    wire chain_active,chain_settled;wire [3:0] chain_actual_mask;
    reg eng_send;
    reg [2:0] eng_string;
    wire [2:0] chain_actual_route;
    wire signed [17:0] additive_send;
    reg [2:0] fx_mode;
    reg [6:0] fx_strength;
    reg [15:0] fx_token;
    reg fx_getreq;
    wire [6:0] shared_map_addr;wire [7:0] shared_map_data;
    reg profile_cmd_we,profile_req;reg [15:0] profile_query_token;
    wire [111:0] profile_status;wire profile_select;wire [7:0] profile_requested_level;
    wire [2:0] fx_actual_mode;
    wire [6:0] fx_gain;
    wire fx_ready,fx_settled,fx_overrun;
    reg gesture_getreq;
    reg [7:0] gesture_token;
    reg [15:0] touch_vib_threshold;
    reg touch_rate_fast,touch_getreq;
    reg [5:0] touch_grace_ms;
    reg [15:0] touch_token;
    // 0x22/v1 read-only selected channel -> 0x8E/v1 correlated reply.
    localparam TOUCH_DIAG_CHANNELS = SENSOR_SPI && N_TOUCH_CH==`TT_NCH ? `TT_SCAN_NCH : N_TOUCH_CH;
    reg [6:0] touch_diag_channel;reg [7:0] touch_diag_token;
    reg touch_diag_req,touch_diag_ack,touch_diag_modern;
    wire touch_diag_valid;wire [6:0] touch_diag_ch;
    wire [51:0] touch_diag_data;wire [7:0] touch_diag_ids;
    reg led_diag_req;reg [7:0] led_diag_token;
    wire touch_rate_actual,touch_rate_busy,touch_rate_fault,touch_config_ready;
    reg [15:0] touch_ms_div;
    reg [7:0] touch_time_ms;
    wire power_events=power_accept && !power_sleep_cmd;
    reg power_adc_seen,power_touch_seen;

    localparam [7:0] MASTER_GAIN = 8'd100;

    // ---------------- 上电自复位 ----------------
    // ★★ 2026-09-23 晚：从 **2.4µs 改成 100ms**（与 14_opto_tune / 06 逐字一致）。
    //   原来 `por_cnt` 是 8 位、阈值 120 拍 = 2.4µs，太短了。
    //   实测症状（同一个 ads7950_spi.v**逐字节相同**、同样的引脚约束、同样的 SCLK=3.125MHz、
    //             同一块光电板、同一套接线）：
    //     · 跑 14_opto_tune（POR = 100ms）：四路 1979/2128/1895/1921，合理值占比约 90% ✓
    //     · 跑 gv_host  （POR = 2.4µs）：码值在"全黑(3)"与"正常(2000)"之间来回跳，
    //                                    合理值占比只有 **26%**；通道地址始终正确、addr_err≈0
    //       ⇒ 不是协议问题，是**模拟/器件状态**问题。
    //   机理：FPGA 配置期间 CS#/SCLK/SDI 都是悬空的（引脚未驱动），配置刚结束 2.4µs 就开始
    //     给 ADS7950 打时钟，器件很可能停在坏状态（部分通道的采样保持被卡住）。
    //     14 给了 100ms 让器件稳下来，所以干净。
    //   ⇒ 这也解释了"为什么换个比特流结果就变" —— 每次重烧 FPGA，ADC 被启动的时刻不同。
    reg [23:0] por_cnt = 24'd0;
    wire       por_n   = (por_cnt >= 24'd5_000_000);   // 5e6 / 50MHz = 100 ms
    always @(posedge clk) begin
        if (!por_n) por_cnt <= por_cnt + 24'd1;
    end
    wire rst_n = por_n;
    always @(posedge clk or negedge rst_n)begin
        if(!rst_n)begin touch_ms_div<=0;touch_time_ms<=0;end
        else if(touch_ms_div==CLK_HZ/1000-1)begin touch_ms_div<=0;touch_time_ms<=touch_time_ms+1'b1;end
        else touch_ms_div<=touch_ms_div+1'b1;
    end

    // The LP5860 LED driver shares the touch sensor SPI clock/data pins.
    // The internal nets are selected below by led_bus_lock_i; the physical
    // pins remain unchanged so the existing CST file and wiring are reused.
    wire                     touch_spi_sck_i;
    wire                     touch_spi_mosi_i;
    wire [2:0]               touch_spi_addr_i;
    wire [N_CHIPS-1:0]       touch_spi_cs_n_i;
    wire                     touch_spi_busy_i;
    wire                     touch_spi_idle_i;
    wire                     led_spi_sck_i, led_spi_mosi_i;
    wire                     led_cs_n_i, led_vsync_i, led_bus_lock_i;
    wire                     led_touch_park_phase_i;
    wire                     led_initialized_i;
    wire [7:0] led_diag_flags,led_control_errors,led_pwm_errors,led_last_fault;
    wire [1:0] led_bad_col;wire [15:0] led_write_crc,led_read_crc;
    // Revised PCB provides an active-low decoder enable. LED and touch can
    // now initialize independently; a missing LED chip never resets touch.
    wire touch_scan_rst_n = rst_n;
    wire                     led_config_ok_i;
    wire [7:0]               led_read_chip_en_i, led_read_initial_i;
    wire [7:0]               led_read_config3_i, led_read_global_i;
    wire [23:0]              led_sent_chip_en_i, led_sent_initial_i;
    reg  [3:0]               led_mode_cfg;
    reg                      led_axis_cfg;
    reg  [7:0]                led_speed_cfg, led_level_cfg, led_flags_cfg;
    reg                      led_cfg_we;
    reg                      led_guide_we;
    reg  [19:0]              led_guide_frets; // G/C/E/A, five bits each; 31=off
    reg  [31:0]              led_guide_levels;
    // The AD7147 SPI block has a BREAK/SETUP phase where CS is still high
    // although a transaction is already in progress.  Use its busy flag for
    // arbitration instead of looking only at chip-select levels.
    assign touch_spi_idle_i = !touch_spi_busy_i;
    wire sample_tick;

    // ★ J5_PROBE（八脚侦探）那套诊断已彻底废弃并删除。
    //   两个原因：① 它要求把 adc_sclk/cs_n/sdi 改成 inout（会变成 IOBUF，
    //   实测会打乱 ADS7950 的 SDI 时序）；② 计跳变数的方法本身会被相邻线串扰污染。
    //   定位孔位改用“用户拿 GND 线碰孔 + 上位机读数”的办法。

    // 音频分支回读（事件判定与 PONG 用）。提前声明，方便下面的命令来源分支引用。
    wire       any_active;      // 任一引擎有可闻声部（由音频分支驱动）
    wire       any_gliding;     // 任一引擎正在滑音（由音频分支驱动）
    wire       vib_env_hi;      // 颤音包络超过一半

    // ============ ★ v45：命令来源二选一（USE_UART_CMD）============
    //   两条链路都产出下面这一组**同名**信号 => 事件判定/音频/LED 完全无感。
    wire        cmd_note_ev;
    wire [5:0]  note_voice;
    wire        cmd_glide_ev;
    wire [5:0]  glide_target;
    wire [1:0]  touch_glide_string; // 0=A、1=E、2=C、3=G；UART 分支无此信息
    wire cmd_vib_on;
    wire [7:0] cmd_vib_rate, cmd_vib_depth;
    wire [N_STR-1:0] touch_vib_str;
    wire [15:0] touch_vib_amp;
    wire [7:0]  gain;
    wire [15:0] glide_ms;
    wire        glide_en;
    wire        cmd_reset_ev;
    wire        cmd_gain_ev;    wire        cmd_glideset_ev;    wire        cmd_ping_ev;
    wire [3:0]  dbg_touch;      // 触摸链路的状态字（见端口注释）

    // Top-level cross-block nets and state are declared together before any
    // combinational expression or generate branch consumes them.  The fusion
    // pass had several declarations hundreds of lines below their first use;
    // strict Verilog tools then created implicit wires or rejected the later
    // reg declarations.
    reg  [7:0]  esp_timbre;
    reg  [7:0]  vol_q8;
    reg         o_start, o_busy;
    wire        o_tx_out, o_tx_busy, o_tx_done;
    wire [7:0]  esp_rx_byte;
    wire        esp_rx_valid;
    wire        adc_busy, adc_done;
    wire [15:0] ascan_us, alast_word, aframe_cnt, aaddr_err, amismatch;
    wire [15:0] aph1, aph2, aph3;
    wire [11:0] aev_pk0, aev_pk1, aev_pk2, aev_pk3;
    wire [15:0] abase0, abase1, abase2, abase3;

    // ============================================================================
    // ★★ 音色库口径偏置（v64）★★
    //   两套音色库的"音库号 = MIDI − 常数"里的常数**不同**（同学工程里的定义）：
    //       rec28   （尤克里里 28 音）：voice = MIDI − 60    （C4..D#6 → 0..27）
    //       guitar33（吉他 33 音）    ：voice = MIDI − 50    （D3..A#5 → 0..32）
    //   ⇒ 切换音色库时，同一个音的序号要整体 +10，否则**弹错音**。
    //
    //   这个偏置加在**顶层消费侧**（`trig_voice` / `cur` / `ev_target`），
    //   **不去改 `touch_recog`**：它内部的比较、查表、best_f/best_d 都用自己的口径，
    //   加了反而会乱。滑音的 `ev_anchor`（= `cur`）和 `ev_target` 都加同一个偏置，
    //   而 `pitch_ctl` 只用两者的**差**算音高比 ⇒ 偏置在差里自然抵消 ✓
    //
    //   ⚠ 声明必须在这里（而不是它原来所在的 527 行）：下面 458 行就要用它，
    //     声明在使用之后会产生悬空网，综合器报 EX1998 并据此把整块逻辑折叠掉
    //     （本工程已经踩过一次，见 docs/gv_host融合现状.md）。
    // ============================================================================
    // 音库参数默认由 FPGA 的 rec28 初始化表提供；切换音色时 ESP32
    // 先选择 K，再把完整 bank 写入这四块 BSRAM。这样 FPGA 不保存两套
    // 大表，片内资源留给触摸、光电和屏幕链路。
    localparam FIXED_REC28_AUDIO = 1'b0;
    wire [5:0] bank_bias = FIXED_REC28_AUDIO ? 6'd0 :
                           ((esp_timbre == 8'd1) ? 6'd10 : 6'd0);

    // ★★ v81：触摸链路的音库号 —— 加 bias 之后**必须钳位**（代码复核发现的越界 bug）。
    //   触摸链路（touch_recog/touch_tables）是 rec28 口径：voice = MIDI−60，范围 0..27。
    //   guitar33 库是 voice = MIDI−50，范围 0..32。
    //   ⇒ 原样 `note_voice + 10` 最大到 **37，超出 33 音的库**；
    //     而引擎的 ROM 地址只有 9 位（additive4_tone.v：`rom_addr = rom_prod[8:0] + pcnt`），
    //     地址 37×15+14 = 569 会**绕回 569−512 = 57** ⇒ 读到别的音的参数
    //     ⇒ 表现为"选了吉他库之后最高的 5 个音完全跑调（音高+包络都错）"。
    //   ⚠ 这个 bug **只在 `esp_timbre==1` 时出现**，而默认上电是 0（rec28、bias=0、K=18），
    //     与 ROM 里实际放的 rec28 数据自洽 ⇒ 所以一直没被发现。
    //   钳到本库最高音是"最不坏的降级"（弹成同一个音），比绕回乱音好得多。
    wire [5:0] bank_note_max = (esp_timbre == 8'd1) ? 6'd37 : 6'd27;
    wire [5:0] touch_voice_raw = note_voice + bank_bias - (USE_UART_CMD ? 6'd0 : {2'd0,capo_applied});
    wire [5:0] touch_voice = (touch_voice_raw > bank_note_max) ? bank_note_max
                                                              : touch_voice_raw;
    wire [5:0] touch_glide_raw = glide_target + bank_bias - (USE_UART_CMD ? 6'd0 : {2'd0,capo_applied});
    wire [5:0] touch_glide_target = (touch_glide_raw > bank_note_max) ? bank_note_max
                                                                     : touch_glide_raw;

    // ★ 光电融合用：触摸识别器输出每弦经过消抖、最强品选择后的稳定品位。
    //   旧版用 pad_hit 原始阈值位图；它在通道轮询时会瞬时变化，光电事件到来时
    //   经常恰好读到 0，表现为“触摸能识别，但拨弦仍总是空弦”。
    wire [N_STR*N_FRET-1:0] pad_hit_esp;
    wire [N_STR*5-1:0] touch_fret_held,touch_pluck_fret;
    wire [N_STR-1:0] touch_change_pending;

    // 遥测口是否忙（供光电遥测帧避让；见文件后部）
    wire        tele_busy;
    // ★ 触摸遥测"整帧进行中"（$H..$E）。$O 帧必须等它落下 —— 只看 tele_busy 会在
    //   字节间的缝里挤进去把 $C 行劈开，而且 telemetry_tx 在 L_F 状态会等 pad_valid
    //   很久，任何"空闲超时"判据都不成立。声明必须放在这里（generate 块里面看不见）。
    wire        tele_frame_active;
    // ★★ 子代理复核 D4：**整条 tx 线的忙**（触摸 UART 或 $O UART 有一个在发都算忙）。
    //   要把这个送给 telemetry_tx 当 tx_busy，否则它会往一个"看不见的串口"里灌整帧。
    wire        tele_line_busy = tele_busy | o_busy | o_tx_busy;

    // ★ v61：音色表（BSRAM）写端口 —— 提前声明（照本文件 any_active / pad_hit_esp 的惯例）。
    //   ⚠ 必须有这几行：u_eng 的例化在文件中间，而 0x09 TONEWR 的解析在文件后部的
    //     ESP32 段。若不提前声明，综合器会把 tw_sel/tw_addr/tw_data 当成"使用处的
    //     隐式网线"，报
    //         WARN (EX1998) : Net 'tw_sel[1]' does not have a driver
    //         WARN (EX1998) : Net 'tw_addr[8]' does not have a driver
    //         WARN (EX1998) : Net 'tw_data[31]' does not have a driver
    //     并且最高位真的悬空 ⇒ 写 sel=2/3（rate/aatk）和 addr≥256 会写错。
    reg         tone_we_r;      // 写脉冲（CRC 校验通过后 1 拍）
    wire [1:0]  tw_sel;
    wire [8:0]  tw_addr;
    wire [31:0] tw_data;

    // ★ v62：音色库步长 pt_k_sel —— **同样必须提前声明**（理由见上面 tw_* 那段）。
    //   实测证据：`fw_scripts/build_gv17.log` 报了
    //       WARN (EX1998) : Net 'pt_k_sel[5]' does not have a driver(top_gv_host.v:529)
    //   例化在 515 行、声明在 537 行 ⇒ 综合器把例化处当成"使用处的隐式网线"。
    //   ⚠ 注意：这不是"字面量没写满 6 位"的问题 —— 15=6'b001111、18=6'b010010 的
    //     最高位**都是 0**，把 `6'd15:6'd18` 改写成 6 位字面量治不了这个警告，
    //     只有把声明挪到例化之前才治得了。
    //   `esp_timbre`（0x07 TIMBRE 记下来的音色索引）在这里被引用，声明一并提前；
    //   它的赋值仍在文件后部 ESP32 段（0x07 分支，复位初值 0）
    //   ⇒ 上电/复位后 esp_timbre=0 ⇒ pt_k=18（rec28），与改前的编译期常量一致。
    wire [5:0]  pt_k_sel;       // 音色索引 1（吉他 guitar33）→ 15，其余（rec28）→ 18
    assign pt_k_sel = FIXED_REC28_AUDIO ? 6'd18 :
                      ((esp_timbre == 8'd1) ? 6'd15 : 6'd18);

    // ★ v63：0x84 OPTEL（光电遥测）帧要用的三组信号 —— 同样**提前声明**
    //   原因：0x81/0x84 的发送块在文件中间（"上行状态帧"那段），而光电 ADC（u_adc）与
    //   strum_detect（u_strum）的**例化在文件后部**。不提前声明就会复现 EX1998
    //   "Net 'araw0[0]' does not have a driver" —— 本工程已经因为这个坑栽过两次
    //   （tw_sel 那次被折叠回 ROM、pt_k_sel 那次直接报了出来）。
    //   这三组网线都是被后部例化的输出驱动，提前声明只改"声明位置"，不改任何连接。
    wire [15:0] araw0, araw1, araw2, araw3;            // 四路 ADC 原始值（u_adc 驱动）
    wire signed [15:0] adip0, adip1, adip2, adip3;     // 四路"增益后凹坑"（u_strum 的 d0..d3）
    wire [3:0]  opto_ev;                               // 四路拨弦事件位图（原始）

    // 光电四路可能在同一个采样窗口连续冒出多个脉冲。一次尤克里里拨弦
    // 只应生成一个音符，因此这里做“首个事件优先”门控：同拍多路命中按
    // ADC0→ADC1→ADC2→ADC3 固定优先级取第一路，随后 1 ms 内的其它路丢弃。
    // 50 MHz 时钟下 50,000 拍约等于 1 ms；这是独立的可调设定。
    localparam [19:0] OPTO_FIRST_LOCK_DEFAULT = `PERF_P16;
    reg  [19:0] o_first_lock;
    reg performance_poly, performance_mode_stamp;
    wire [3:0] opto_ev_first;
    wire performance_fire;
    wire [3:0] led_audio_events;
    wire [4:0] led_audio_fret;
    wire [1:0] performance_channel;
    wire [5:0] performance_note;
    wire [11:0] performance_peak;
    wire [11:0] opto_velocities;
    wire [2:0] performance_velocity;
    wire [3:0] performance_depth;
    wire [15:0] performance_overflow,performance_detected,performance_accepted,performance_emitted;
    // 光电遥测帧的信号（实体在文件后部"光电遥测帧"段；
    // 这里提前声明，因为上面的 generate 分支里 assign tx 要用到 o_busy / o_tx_out）
    generate
    if (USE_UART_CMD) begin : g_uart_cmd
        assign power_touch_asleep=1'b1;
        assign power_touch_ready=1'b1;
        assign power_touch_sample=1'b1;
        // ---- 老链路（v44 之前的原样代码）：UART 字节 → 命令帧解码 ----
        wire [7:0] rx_byte;
        wire       rx_valid;
        uart_rx #(.CLK_HZ(CLK_HZ), .BAUD(BAUD)) u_uart_rx (
            .clk(clk), .rst_n(rst_n), .rx(rx), .rx_byte(rx_byte), .rx_valid(rx_valid));

        // 颤音（VIB / VIB_SET）按 v44 的结论**保持关闭**：解码结果留空不接，
        // UART VIB / VIB_SET 接入与触摸链路共用的音高控制器。
        wire        vib_on, cmd_vib_ev, cmd_vibset_ev;
        wire [7:0]  vib_rate_x10, vib_depth;
        wire [7:0]  cmd_last;
        wire        cmd_ev, cmd_err;

        // ============ ★ ESP32（串口屏桥）第二路命令输入 ============
        //   屏幕上的操作经 ESP32 转成同样的 AA CMD LEN PAY CRC 帧发过来，
        //   与 PC 那路共用同一个 cmd_decoder。
        //   冲突仲裁：ESP32 有数据时优先（两者同时说话的概率极低）。
        //   （esp_rx_byte / esp_rx_valid 在顶层实例化，见文件后部"ESP32 链路"段）
        wire [7:0] cmd_rx_byte  = esp_rx_valid ? esp_rx_byte : rx_byte;
        wire       cmd_rx_valid = esp_rx_valid | rx_valid;

        cmd_decoder u_cmd (
            .clk(clk), .rst_n(rst_n),
            .rx_byte(cmd_rx_byte), .rx_valid(cmd_rx_valid),
            .cmd_note_ev(cmd_note_ev), .note_voice(note_voice),
            .vib_on(vib_on), .cmd_vib_ev(cmd_vib_ev),
            .vib_rate_x10(vib_rate_x10), .vib_depth(vib_depth), .cmd_vibset_ev(cmd_vibset_ev),
            .glide_en(glide_en), .glide_ms(glide_ms), .cmd_glideset_ev(cmd_glideset_ev),
            .glide_target(glide_target), .cmd_glide_ev(cmd_glide_ev),
            .gain(gain), .cmd_gain_ev(cmd_gain_ev),
            .cmd_reset_ev(cmd_reset_ev), .cmd_ping_ev(cmd_ping_ev),
            .cmd_last(cmd_last), .cmd_ev(cmd_ev), .cmd_err(cmd_err));

        // 这一分支里触摸传感器完全不参与：I²C 两根线放手（高阻），
        // 免得板上还有别的 I²C 主机（例如 ESP32）时两个主机抢总线。
        assign tp_scl = 1'bz;
        assign tp_sda = 1'bz;
        assign touch_spi_sck_i = 1'b0;
        assign touch_spi_mosi_i = 1'b0;
        assign touch_spi_cs_n_i = {N_CHIPS{1'b1}};
        assign touch_spi_busy_i = 1'b0;
        assign touch_spi_addr_i = 3'd0;

        assign dbg_touch = {3'b0, any_gliding};   // UART 链路：bit0 = 正在滑音

        // ---- PING → PONG（uart_tx）----
        wire       tx_busy, tx_done;
        reg        tx_start;
        reg [7:0]  tx_byte;
        reg [7:0]  pong[0:4];
        reg [2:0]  pong_idx;
        reg        pong_active;
        // status: bit0 = 颤音包络超过一半，bit1 = 正在滑音
        wire [7:0] status = {6'd0, any_gliding, vib_env_hi};

        uart_tx #(.CLK_HZ(CLK_HZ), .BAUD(BAUD)) u_uart_tx (
            .clk(clk), .rst_n(rst_n), .tx_start(tx_start), .tx_byte(tx_byte),
            .tx_out(tx), .tx_busy(tx_busy), .tx_done(tx_done));

        always @(posedge clk or negedge rst_n) begin
            if (!rst_n) begin
                tx_start    <= 1'b0;
                tx_byte     <= 8'h00;
                pong_idx    <= 3'd0;
                pong_active <= 1'b0;
            end else begin
                tx_start <= 1'b0;
                if (cmd_ping_ev) begin
                    pong[0] <= 8'hAA; pong[1] <= 8'h80; pong[2] <= 8'h01; pong[3] <= status;
                    pong[4] <= 8'h80 ^ 8'h01 ^ status;
                    pong_idx <= 3'd0; pong_active <= 1'b1;
                end
                if (pong_active && !tx_busy) begin
                    tx_byte  <= pong[pong_idx];
                    tx_start <= 1'b1;
                end
                if (tx_done) begin
                    if (pong_idx == 4) pong_active <= 1'b0;
                    else               pong_idx <= pong_idx + 3'd1;
                end
            end
        end

        // 这条链路没有触摸面板 ⇒ pad_hit 全 0（光电触发会退化成"全部空弦音"）
        assign pad_hit_esp = {(N_STR*N_FRET){1'b0}};
        assign cmd_vib_on = vib_on;
        assign cmd_vib_rate = vib_rate_x10;
        assign cmd_vib_depth = vib_depth;
        assign touch_vib_str = 0;
        assign touch_vib_amp = 0;
        assign shared_map_addr=0;
        assign touch_fret_held = {(N_STR*5){1'b0}};
        assign touch_diag_valid=0;assign touch_diag_ch=0;assign touch_diag_data=0;assign touch_diag_ids=0;
        assign touch_pluck_fret=0;assign touch_change_pending=0;assign touch_rate_actual=0;assign touch_rate_busy=0;assign touch_rate_fault=0;assign touch_config_ready=0;
        assign touch_glide_string = 2'd0;
    end else begin : g_touch
        // ---- v44/v48 链路：AD7147(I²C/SPI) → ad7147_scan（上电序列 + 周期读 N 路 CDC）
        //      → touch_recog（单音/和弦/滑音）→ 同名事件线
        //   参数全部来自 touch_tables.vh 的宏（换面板只改映射表 + 重跑生成脚本）。
        // New SPI fretboard: 72 fret positions plus 25 gesture electrodes.
        localparam TOUCH_CHANNELS = SENSOR_SPI && N_TOUCH_CH==`TT_NCH ? `TT_SCAN_NCH : N_TOUCH_CH;
        wire [TOUCH_CHANNELS*16-1:0] cdc_bus;
        wire [6:0] touch_cdc_addr;
        wire [15:0] touch_cdc_data;
        wire             sample_valid, sample_dup, panel_ready, sensor_ok;
        wire [15:0]      dev_id0, dev_id1;
        wire [N_CHIPS-1:0] id_ok;
        wire [3:0]       sen_err_cnt;
        wire [N_CHIPS*16-1:0] touch_dev_ids;
        wire [7:0] touch_init_retries,touch_id_retries;
        wire [N_CHIPS-1:0] touch_fail_mask;
        wire [4:0] touch_init_state;
        wire [N_STR*N_FRET-1:0] pad_hit;
        wire [N_STR-1:0] str_press;

        // ★ 提供给光电融合用（见顶层"光电拨弦 → 引擎触发"段）：
        //   触摸链路给出每弦每品位的命中位图，光电拨弦时据此查该弦当前按在第几品。
        assign pad_hit_esp = pad_hit;
        wire [13:0]      dbg_dmax;
        wire [47:0]      dbg_rec_snap;      // ★ v59：识别器每路内部状态快照
        wire [15:0]      dbg_rec_cdc;
        wire [6:0]       dbg_rec_ch;
        wire [15:0]      dbg_rec_rounds;
        wire tp_scl_oe, tp_sda_oe, tp_sda_in;   // 开漏驱动（声明在前，避免隐式声明告警）

        ad7147_scan #(
            .CLK_HZ(CLK_HZ), .SENSOR_SPI(SENSOR_SPI),
            .N_CHIPS(N_CHIPS), .SP_CH(SP_CH), .N_CH(TOUCH_CHANNELS), .DECIM(DECIM),
            .CDC_BUS_ENABLE(0), .CFG_MS(0)
        ) u_scan (
            .clk(clk), .rst_n(touch_scan_rst_n),
            .rate_fast(touch_rate_fast),.rate_applied_fast(touch_rate_actual),.rate_busy(touch_rate_busy),.rate_fault(touch_rate_fault),
            .spi_block(led_bus_lock_i), .sleep_req(power_modules_sleep), .asleep(power_touch_asleep),
            .spi_busy(touch_spi_busy_i),
            .i2c_scl_oe(tp_scl_oe), .i2c_sda_oe(tp_sda_oe), .i2c_sda_in(tp_sda_in),
            .spi_sck(touch_spi_sck_i), .spi_mosi(touch_spi_mosi_i), .spi_miso(tp_miso),
            .spi_cs_n(touch_spi_cs_n_i), .spi_addr(touch_spi_addr_i),
            .cdc_addr(touch_cdc_addr), .cdc_data(touch_cdc_data),
            .cdc_bus(cdc_bus), .sample_valid(sample_valid), .sample_dup(sample_dup),
            .panel_ready(panel_ready),
            .sensor_ok(sensor_ok), .id_ok(id_ok), .dev_id0(dev_id0), .dev_id1(dev_id1),
            .err_cnt(sen_err_cnt), .dev_ids(touch_dev_ids),
            .init_retries(touch_init_retries), .id_retries(touch_id_retries),
            .last_fail_mask(touch_fail_mask), .init_state(touch_init_state)
        );

        assign touch_diag_ids=id_ok;
        assign power_touch_ready=panel_ready && sensor_ok;
        assign touch_config_ready=panel_ready && sensor_ok;
        assign power_touch_sample=sample_valid;

        // 开漏驱动：oe=1 释放（高阻，靠外部/内部上拉），oe=0 拉低
        //   ★ 极性已核对：网表里生成的是 TBUF(.O(tp_scl), .I(GND), .OEN(oe))，
        //     而 Gowin 自己的原语模型 prim_sim.v 里 TBUF = `bufif0`（**OEN 低有效**）
        //     ⇒ oe=1 → 高阻 ✓、oe=0 → 拉低 ✓，与设计意图完全一致。
        if (DBG_PIN_PROBE) begin : g_probe
            assign tp_scl = 1'b1;        // 诊断：推挽输出高（找 F6 在排针上的位置）
            assign tp_sda = 1'b0;        // 诊断：推挽输出低（找 F7 的位置）
        end else begin : g_od
            assign tp_scl    = tp_scl_oe ? 1'bz : 1'b0;
            assign tp_sda    = tp_sda_oe ? 1'bz : 1'b0;
            assign tp_sda_in = tp_sda;
        end

        // 这些原来由 UART 命令提供，触摸链路里改成常量
        // ★ v60：滑音斜坡时长。原来是 60 ms —— 60 ms 内滑几个半音基本等于"瞬间变调"，
        //   人耳听不出是滑音（用户反馈"滑音几乎检测不到"，其实一部分是**检测到了但太快听不出**）。
        //   改成 180 ms：能明显听出连续滑过去，又不会拖到像两个音。
        assign cmd_vib_rate = VIB_RATE_X10;
        assign cmd_vib_depth = VIB_DEPTH;
        assign glide_ms        = 16'd180;
        assign glide_en        = 1'b1;
        assign cmd_reset_ev    = 1'b0;
        assign cmd_gain_ev     = 1'b0;
        assign cmd_glideset_ev = 1'b0;
        assign cmd_ping_ev     = 1'b0;

        touch_recog #(
            .EXTERNAL_MAP(1),            .RUNTIME_CONFIG(1), .CAPO_ENABLED(1),.CLK_HZ(CLK_HZ), .NCH(TOUCH_CHANNELS), .CH_BITS(7), .SAMPLE_MS(23), .CDC_RAM(1),
            .N_STR(N_STR), .N_FRET(N_FRET), .FRET_BITS(5)
        ) u_recog (
            .shared_map_addr(shared_map_addr),.shared_map_data(shared_map_data),
            .clk(clk), .rst_n(rst_n),
            .cdc_addr(touch_cdc_addr), .cdc_data(touch_cdc_data),
            .cdc_bus(cdc_bus), .sample_valid(sample_valid), .sample_dup(sample_dup),
            .panel_ready(panel_ready),
            .vib_on(cmd_vib_on), .vib_str(touch_vib_str), .vib_amp(touch_vib_amp),
            .note_ev(cmd_note_ev), .note_voice(note_voice),
            .glide_ev(cmd_glide_ev), .glide_target(glide_target),
            .glide_string(touch_glide_string),
            .gain(gain),
            .pad_hit(pad_hit), .str_press(str_press), .held_fret(touch_fret_held), .capo_fret(capo_applied),
            .pluck_fret(touch_pluck_fret),.vibrato_threshold(touch_vib_threshold),.fast_scan(touch_rate_actual),
            .change_pending(touch_change_pending),
            .vibrato_use_aux(gesture_mask[2]),.vibrato_owner(glide_owner_string),.vibrato_owner_valid(glide_owner_valid),
            .vibrato_enabled(gesture_mask[1]),.vibrato_retrigger(eng_trig),
            .dbg_dmax(dbg_dmax),
            .diag_valid(touch_diag_valid),.diag_channel(touch_diag_ch),.diag_data(touch_diag_data),
            .dbg_snap(dbg_rec_snap), .dbg_cdc(dbg_rec_cdc), .dbg_snap_ch(dbg_rec_ch), .dbg_rounds(dbg_rec_rounds)
        );

        // ★ v52 硬件状态字：用万用表量 4 个脚就能分清"没应答"还是"数据不对"
        //   bit3 = 出现过 NACK（说明总线/地址/供电有问题，从机根本没回话）
        //   bit2/bit1 = 0x2E / 0x2C 那颗芯片的 Device ID 是否匹配 0x147x
        //   bit0 = 两颗都通过
        reg err_any;
        always @(posedge clk or negedge rst_n) begin
            if (!rst_n) err_any <= 1'b0;
            else if (sen_err_cnt != 4'd0) err_any <= 1'b1;   // 只要有 NACK 就锁存
        end
        assign dbg_touch = {err_any | (SENSOR_SPI && !sensor_ok), id_ok[1], id_ok[0], sensor_ok};

        // ============ ★ v56：遥测串流（FPGA → PC 上位机）============
        //   把"通信状况 + 每路 CDC + 识别结果"持续发给 PC：
        //     $H 状态头 / $C 每通道 CDC / $K 按下的品格 / $R 识别结果 / $E 帧尾
        //   协议与上位机解析器见 src/telemetry_tx.v 头部、work/host/proto.py。
        //   只发不收（rx 不参与），所以两根线只接 FPGA tx → USB-TTL RXD 就能看数据。
        if (TELEMETRY) begin : g_tele
            wire [7:0] tele_byte;
            wire       tele_start, tele_tx_out;
        // ★ tele_busy 提到顶层：光电遥测要在它空闲时插入，见文件后部"光电遥测帧"
        //   （原来这里是 local wire，顶层看不见）
        // ★ tele_frame_active 也已在顶层声明（generate 块里声明的话外面看不见）

            telemetry_tx #(
                .CLK_HZ(CLK_HZ), .NCH(N_TOUCH_CH),
                .N_STR(N_STR), .N_FRET(N_FRET), .N_CHIPS(N_CHIPS),
                .PERIOD_MS(TELE_PERIOD_MS), .SNAP_CDC(1), .INIT_DIAG(1)
            ) u_tele (
                .clk(clk), .rst_n(rst_n),
                .err_cnt(sen_err_cnt), .id_ok(id_ok), .sensor_ok(sensor_ok),
                .panel_ready(panel_ready), .dev_id0(dev_id0), .dev_id1(dev_id1),
                .dev_ids(touch_dev_ids), .init_retries(touch_init_retries),
                .id_retries(touch_id_retries), .last_fail_mask(touch_fail_mask),
                .init_state(touch_init_state),
                .sample_valid(sample_valid), .sample_dup(sample_dup),
                .cdc_bus(cdc_bus[N_TOUCH_CH*16-1:0]), .cdc_snap(dbg_rec_cdc), .pad_hit(pad_hit),
                .gain(gain), .voice(note_voice), .dbg_dmax(dbg_dmax),
                .note_ev(cmd_note_ev), .glide_ev(cmd_glide_ev), .glide_target(glide_target),
                .dbg_snap(dbg_rec_snap), .dbg_snap_ch(dbg_rec_ch), .dbg_rounds(dbg_rec_rounds),
                .tx_byte(tele_byte), .tx_start(tele_start),
                // ★★ 2026-09-23 修（子代理复核 D4）：传给 telemetry_tx 的"忙"必须是
                //   **整条 tx 线**的忙，而不只是它自己那路 UART 的忙。
                //   原来只给 tele_busy，于是 $O 帧在发的 ~15ms 里 telemetry_tx 以为线是空的，
                //   会把整帧（约 620 字节）塞进自己的 UART —— 这些字节全被 mux 吞掉，
                //   线上什么都没有；$O 结束那一刻 mux 切回来还会从字节中间切开。
                //   表现就是触摸帧整帧丢失 / $H 缺失 / 上位机 dropped 一直涨。
                .tx_busy(tele_line_busy),
                .frame_active(tele_frame_active)
            );
            uart_tx #(.CLK_HZ(CLK_HZ), .BAUD(BAUD), .WIDTH(8)) u_tele_uart (
                .clk(clk), .rst_n(rst_n),
                .tx_start(tele_start), .tx_byte(tele_byte),
                .tx_out(tele_tx_out), .tx_busy(tele_busy), .tx_done()
            );
            // ★ tx 引脚二选一：光电遥测帧只在 telemetry 空闲时插入（见文件后部）
            // ★★ 2026-09-23 修：判据必须带上 **o_tx_busy**。
            //   `o_busy` 是在最后一个字节【交给】u_o_uart 后就落下的，而那个字节还要
            //   移 87us 才发完。只看 o_busy 的话，tx 会在半个字节处切回 tele_tx_out
            //   ⇒ 线上出现半个字节的乱码，后面整个触摸帧跟着错位
            //   （实测抓到 `...0001,` 后面接 `����b��` 这种二进制垃圾）。
            //   加上 o_tx_busy 就把"最后一个字节移完"也纳入了持有条件。
    assign tx = (o_busy | o_tx_busy) ? o_tx_out : tele_tx_out;
        end else begin : g_no_tele
            assign dbg_rec_ch = 7'd0;
            assign tx = 1'b1;                // 不发遥测：tx 停在空闲高电平
            // ★ v81 修（代码复核发现）：**这两根线也必须驱动**。
            //   它们原本只在上面那个分支里被 telemetry_tx 驱动；而文件后部的 `$O` 帧
            //   调度用的是 `o_go = !o_busy && !tele_frame_active && !tele_busy && ...`
            //   ⇒ 在这个分支下两根线悬空、取值 X，`$O` 帧要么永不启动、要么行为不确定。
            //   发布配置默认 TELEMETRY=0，因此在关闭 PC 文本遥测时也必须给它们确定值，
            //   让相关状态机能被综合器完整裁掉，而不是留下 X 传播。
            assign tele_busy         = 1'b0;
            assign tele_frame_active = 1'b0;
        end
    end
    endgenerate

    // ========================================================================
    // LP5860 LED matrix.  The renderer owns the shared touch SPI pins only
    // during a complete LP5860 transaction.  Its bus_idle input is driven by
    // the AD7147 scanner's busy flag, so the BREAK/SETUP lead-in is protected
    // even while the touch chip-selects are still high.
    // ========================================================================
    generate
    if (ENABLE_LED != 0) begin : g_led_enabled
    led_matrix #(.CAPO_ENABLED(1),
        // Match the already verified touch-bus clock on the populated PCB.
        .CLK_HZ(CLK_HZ), .SPI_HZ(1_000_000), .FRAME_HZ(50), .FRAME_PIXELS(18),
        .TOUCH_PARK_PER_BYTE(0)
    ) u_led_matrix (
        .clk(clk), .rst_n(rst_n), .sleep_req(power_modules_sleep), .asleep(power_led_asleep), .bus_idle(touch_spi_idle_i), .spi_miso(tp_miso),
         .shared_map_addr(shared_map_addr),.shared_map_data(shared_map_data),
         .profile_cmd_we(profile_cmd_we),.profile_op(es_pay[1]),.profile_index(es_pay[2]),
         .profile_d0(es_pay[3]),.profile_d1(es_pay[4]),.profile_d2(es_pay[5]),.profile_cmd_token({es_pay[7],es_pay[6]}),
         .profile_status(profile_status),.profile_select(profile_select),.profile_requested_level(profile_requested_level),
         .cfg_we(led_cfg_we), .cfg_mode(led_mode_cfg), .cfg_axis(led_axis_cfg),
         .cfg_speed(led_speed_cfg), .cfg_level(led_level_cfg),
         .cfg_flags(led_flags_cfg),
         .guide_we(led_guide_we), .guide_frets(led_guide_frets),
         .guide_levels(led_guide_levels), .capo_fret(capo_applied),
         .event_bits(led_audio_events), .event_fret(led_audio_fret),
        .spi_sck(led_spi_sck_i), .spi_mosi(led_spi_mosi_i),
        .cs_n(led_cs_n_i), .vsync(led_vsync_i),
        .touch_park_phase(led_touch_park_phase_i),
        .bus_lock(led_bus_lock_i), .initialized(led_initialized_i),
        .config_ok(led_config_ok_i), .read_chip_en(led_read_chip_en_i),
        .read_dev_initial(led_read_initial_i), .read_config3(led_read_config3_i),
        .read_global(led_read_global_i), .sent_chip_en(led_sent_chip_en_i),
        .sent_dev_initial(led_sent_initial_i),.diag_flags(led_diag_flags),
        .control_errors(led_control_errors),.pwm_errors(led_pwm_errors),.last_fault(led_last_fault),
        .last_bad_col(led_bad_col),.write_crc(led_write_crc),.read_crc(led_read_crc)
    );
    end else begin : g_led_disabled
        assign power_led_asleep=1'b1;
        assign led_spi_sck_i = 1'b0;
        assign led_spi_mosi_i = 1'b0;
        assign led_cs_n_i = 1'b1;
        assign led_vsync_i = 1'b0;
        assign led_touch_park_phase_i = 1'b0;
        assign led_bus_lock_i = 1'b0;
        assign led_initialized_i = 1'b0;
        assign led_config_ok_i = 1'b0;
        assign led_read_chip_en_i = 8'd0;
        assign led_read_initial_i = 8'd0;
        assign led_read_config3_i = 8'd0;
        assign led_read_global_i = 8'd0;
        assign led_sent_chip_en_i = 24'd0;
        assign led_sent_initial_i = 24'd0;
        ch_map_rom u_map_fallback(.clk(clk),.addr(shared_map_addr),.sf(shared_map_data));
        assign profile_status=0;assign profile_select=0;assign profile_requested_level=0;
        assign led_diag_flags=0;assign led_control_errors=0;assign led_pwm_errors=0;
        assign led_last_fault=0;assign led_bad_col=0;assign led_write_crc=0;assign led_read_crc=0;
    end
    endgenerate

    // Shared physical SPI pins: LED traffic takes ownership only while the
    // LED block is shifting.  MISO is an input and is therefore seen by both
    // blocks without an additional mux.
    assign tp_sck    = led_bus_lock_i ? led_spi_sck_i  : touch_spi_sck_i;
    assign tp_mosi   = led_bus_lock_i ? led_spi_mosi_i : touch_spi_mosi_i;
    // New CN1-7 EN# drives both G2A#/G2B# of the 74LVC138. High forces
    // ALL touch CS outputs high. Low enables exactly the addressed chip,
    // only during its real CS window. SPI BREAK/SETUP/HOLD timing is kept.
    // LED ownership is reserved before its first SCLK; address parking and
    // dependence on partial-command rejection are no longer needed.
    assign tp_addr = touch_spi_addr_i;
    assign tp_en_n = !rst_n || led_bus_lock_i || (&touch_spi_cs_n_i);
    assign led_cs_n  = led_cs_n_i;

    assign dbg_status = dbg_touch;

    // ============ 时钟/采样节拍 ============
    tick_gen u_tick_gen (.clk(clk), .rst_n(rst_n), .sample_tick(sample_tick));

    // ============ 事件判定：NOTE -> 重拨 or 滑音（计划 §3.1）============
    reg [5:0]  cur;             // 当前音（吉他库 33 音 -> 0..32）
    reg        cur_valid;
    reg        trig_note;
    reg [5:0]  trig_voice;      // 吉他库 33 音 -> 0..32（6bit）

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cur          <= 6'd0;
            cur_valid    <= 1'b0;
            trig_note    <= 1'b0;
            trig_voice   <= 6'd0;
        end else begin
            trig_note    <= 1'b0;

            // NOTE：**始终重拨**。语义 = "新的一次按下/按键" -> 拨响新音（与仍在衰减的旧音叠加）；
            //   滑音只在"按住拖动"时由上面的 GLIDE 命令触发，不再在 NOTE 里自动判定。
            //   （这样：滑音进行中松手、再点左键 = 拨新音，而不是继续滑过去。）
            if (cmd_note_ev) begin
                // ★ 加 bank_bias：把触摸链路的 rec28 口径换算成当前音色库的口径
                //   （rec28: +0；guitar33: +10）。不加的话选了吉他库会整体弹错音。
                // ★★ v81 修（代码复核发现）：**加完 bias 必须钳位**。
                //   触摸链路是 rec28 口径（voice = MIDI−60，0..27），guitar33 是
                //   voice = MIDI−50（0..32）⇒ 原来 +10 之后最大到 **37，超出 33 音库**。
                //   而引擎的 ROM 地址只有 9 位（additive4_tone.v 的 `rom_addr = rom_prod[8:0]+pcnt`）
                //   ⇒ 地址 37×15+14 = 569 会**绕回 569−512 = 57**，读到另一个音的参数，
                //     表现为"选吉他库后最高的 5 个音完全跑调"（音高、包络都不对）。
                //   光电路径不越界（opto_note_* 最大 7+10+高品，远小于 32），所以只有触摸路要钳。
                //   钳到本库最高音是"最不坏"的降级（弹成同一个音），比绕回乱音好得多。
                trig_voice   <= touch_voice;
                cur          <= touch_voice;
                cur_valid    <= 1'b1;
                // 触摸在融合模式下只记录左手按下的品位；真正的拨弦由光电事件
                // 触发。UART/PC 调试模式仍保留 NOTE 直发声语义。
                if (USE_UART_CMD) begin
                    trig_note    <= 1'b1;
                end
            end

            if (cmd_reset_ev) begin
                cur_valid <= 1'b0;
            end
        end
    end

    // ============ 音频部分（融合前 additive4_tone 基线，四弦单引擎）============
    // 融合前已经验证的音源是 additive4_tone：录音泛音表、双指数包络、
    // 逐泛音起振相位和拨弦瞬态都在这个模块中。这里保持它作为音色基线。

    // touch_fret_held 段序是 A/E/C/G；硬件实测光电 ADC 顺序是 G/E/C/A。
    // 各路拨弦直接读取同一根弦的稳定触摸品位。
    wire [5:0] opto_note_g_raw = 6'd7 + bank_bias
                               + {1'b0, capo_relative(touch_pluck_fret[3*5 +: 5])};
    wire [5:0] opto_note_e_raw = 6'd4 + bank_bias
                               + {1'b0, capo_relative(touch_pluck_fret[1*5 +: 5])};
    wire [5:0] opto_note_c_raw = 6'd0 + bank_bias
                               + {1'b0, capo_relative(touch_pluck_fret[2*5 +: 5])};
    wire [5:0] opto_note_a_raw = 6'd9 + bank_bias
                               + {1'b0, capo_relative(touch_pluck_fret[0*5 +: 5])};
    // The guitar engine octave-maps its five notes above the original table.
    wire [5:0] opto_note_g = (opto_note_g_raw > bank_note_max) ? bank_note_max : opto_note_g_raw;
    wire [5:0] opto_note_e = (opto_note_e_raw > bank_note_max) ? bank_note_max : opto_note_e_raw;
    wire [5:0] opto_note_c = (opto_note_c_raw > bank_note_max) ? bank_note_max : opto_note_c_raw;
    wire [5:0] opto_note_a = (opto_note_a_raw > bank_note_max) ? bank_note_max : opto_note_a_raw;
    // Programmed plucks enter the same queue, allocator and synthesis path.
    reg auto_on,auto_pulse,auto_have;
    reg [15:0] auto_token;
    reg [19:0] auto_frets;
    reg [7:0] auto_stamp;
    wire [7:0] auto_age=touch_time_ms-auto_stamp;
    reg [2:0] auto_left;reg [1:0] auto_index;
    wire auto_fire=auto_on && auto_left!=0 && auto_frets[4:0]!=31 && !capo_change && !opto_any && !trig_note;
    wire [5:0] auto_note_raw={1'b0,auto_frets[4:0]}+(auto_index==0?6'd7:auto_index==1?6'd0:auto_index==2?6'd4:6'd9)+bank_bias;
    wire [5:0] auto_note=auto_note_raw>bank_note_max?bank_note_max:auto_note_raw;

    wire        opto_any  = performance_fire;
    wire [5:0] queued_base=performance_channel==0?6'd7:performance_channel==1?6'd4:performance_channel==2?6'd0:6'd9;
    wire [5:0] queued_relative=performance_note>queued_base+{2'd0,capo_applied}?performance_note-{2'd0,capo_applied}:queued_base;
    wire [5:0] queued_note=queued_relative+(performance_model==1 ? 6'd10:6'd0);
    wire [5:0] opto_note=performance_model==1 && queued_note>37 ? 6'd37 : queued_note;
    // strum_detect reports the calibrated 12-bit peak on the same cycle that
    // opto_ev_first becomes visible.  Convert it to eight coarse force levels
    // with no multiplier in the top-level timing path.
    wire [11:0] opto_peak = performance_peak;
    // Shared threshold declarations precede their use in the force mapper.
    // The existing command parser remains the only writer of these registers.
    reg [11:0] o_th0, o_th1, o_th2, o_th3;
    reg [2:0] o_cfg_cnt;
    wire [2:0] opto_velocity = opto_any ? performance_velocity : 3'd7;
    wire [1:0] opto_string = performance_channel==0 ? 2'd3 :
        performance_channel==1 ? 2'd1 : performance_channel==2 ? 2'd2 : 2'd0;
    performance_router #(.FORCE_ENABLED(ENABLE_FORCE),.TOUCH_GRACE(1)) u_performance(
      .clk(clk),.rst_n(rst_n),.poly_mode(performance_poly),.mode_change(performance_mode_stamp | power_flush),
      .time_ms(touch_time_ms),.grace_ms(touch_grace_ms),.cancel_pending(!power_events || touch_rate_busy || capo_change || (auto_on && auto_age>=200)),
      .change_pending({touch_change_pending[0],touch_change_pending[2],touch_change_pending[1],touch_change_pending[3]}),
      .lock_clocks(o_first_lock),.detected(power_events && !touch_rate_busy ? opto_ev : 4'd0),
      .notes({(6'd9+{1'b0,touch_pluck_fret[0+:5]}),{1'b0,touch_pluck_fret[10+:5]},(6'd4+{1'b0,touch_pluck_fret[5+:5]}),(6'd7+{1'b0,touch_pluck_fret[15+:5]})}),
      .model(voice_model),.emitted_model(performance_model),
      .peaks({aev_pk3,aev_pk2,aev_pk1,aev_pk0}),
      .velocities(opto_velocities),.velocity(performance_velocity),
      .accepted(opto_ev_first),.fire(performance_fire),.channel(performance_channel),
      .note(performance_note),.peak(performance_peak),.depth(performance_depth),
      .overflows(performance_overflow),.detected_count(performance_detected),
      .accepted_count(performance_accepted),.emitted_count(performance_emitted));

    // 滑音必须先由一次真实拨弦建立上下文：记住“哪根弦、起始音、12 声部池中的槽位”。
    // 随后的触摸滑动只有来自同一根弦才有效。这样按住和弦时，滑动 A 弦不会把刚拨响
    // 的 E 弦误拉音；新一次光电拨弦会自然取代旧上下文。
    reg        ev_glide;
    reg [5:0]  ev_anchor;
    reg [5:0]  glide_owner_note;
    reg [3:0]  glide_owner_voice;
    reg        glide_tail_damp;
    reg [1:0] gesture_model_seen;
    wire [5:0] pitch_ev_target = USE_UART_CMD ? glide_target : touch_glide_target;

    // 光电优先选择实际检测到的第一根弦；UART NOTE 仅保留作调试。
    wire        audio_trig = power_events && !capo_change && (trig_note | opto_any | auto_fire);
    wire [5:0]  audio_voice = opto_any ? opto_note : auto_fire ? auto_note : trig_voice;
    // Restore the verified 12-voice round-robin pool.  The four-voice fused
    // version tied one voice permanently to each string, so a second pluck on
    // the same string reset an already audible waveform at an arbitrary phase
    // and produced clicks/harsh distortion.  Twelve voices still complete the
    // 12*18*4+13 = 877-cycle scan inside every 1024-cycle sample period.
    localparam integer AUDIO_NV = 12;
    localparam [3:0] AUDIO_NV_LAST = AUDIO_NV - 1;
    wire [15:0] gain_scaled = {8'd0, gain} * {8'd0, power_volume};
    wire [7:0]  gain_w = gain_scaled[15:8];
    wire [7:0]  gain_final = gain_w;   // 状态帧沿用原字段名
    wire signed [17:0] additive_sample;
    wire [AUDIO_NV-1:0] audio_active;

    wire [AUDIO_NV-1:0] eng_active;

    reg  [3:0] eng_voice;
    reg  [5:0] eng_note;
    reg  [2:0] eng_velocity;
    reg  [3:0] audio_alloc_ptr;
    wire [15:0] eng_dbg_env;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            ev_glide          <= 1'b0;
            ev_anchor         <= 6'd0;
            glide_owner_note  <= 6'd0;
            glide_owner_string<= 2'd0;
            glide_owner_voice <= 4'd0;
            glide_owner_valid <= 1'b0;glide_tail_damp<=0;gesture_model_seen<=0;
        end else begin
            ev_glide <= 1'b0;
            if (audio_trig || auto_on || cmd_reset_ev || !power_events || capo_change || gesture_model_seen!=voice_model)
                glide_tail_damp <= 1'b0;
            else glide_tail_damp <= any_gliding || ev_glide;

            // 默认触摸模式：只有光电真实拨响之后才允许滑音。
            if (opto_any && !auto_on) begin
                glide_owner_note   <= opto_note;
                glide_owner_string <= opto_string;
                glide_owner_voice  <= audio_alloc_ptr;
                glide_owner_valid  <= 1'b1;
            end else if (USE_UART_CMD && trig_note) begin
                glide_owner_note   <= trig_voice;
                glide_owner_string <= 2'd0;
                glide_owner_voice  <= audio_alloc_ptr;
                glide_owner_valid  <= 1'b1;
            end

            if (power_events && gesture_mask[0] && !audio_trig && !USE_UART_CMD && cmd_glide_ev && glide_owner_valid && audio_active[glide_owner_voice] &&
                (touch_glide_string == glide_owner_string)) begin
                ev_glide  <= 1'b1;
                ev_anchor <= glide_owner_note;
            end else if (power_events && gesture_mask[0] && !audio_trig && USE_UART_CMD && cmd_glide_ev && cur_valid) begin
                ev_glide  <= 1'b1;
                ev_anchor <= cur;
            end

            gesture_model_seen<=voice_model;
            if (auto_on || cmd_reset_ev || !power_events || capo_change || gesture_model_seen!=voice_model)
                glide_owner_valid <= 1'b0;
        end
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            eng_trig       <= 1'b0;
            eng_voice      <= 4'd0;
            eng_note       <= 6'd0;
            eng_velocity   <= 3'd7;eng_model<=0;eng_send<=0;eng_string<=4;
            audio_alloc_ptr <= 4'd0;
        end else begin
            eng_trig <= 1'b0;
            if (audio_trig) begin
                eng_trig  <= 1'b1;
                eng_voice <= audio_alloc_ptr;
                eng_note<=audio_voice;eng_model<=opto_any?performance_model:voice_model;
                eng_send<=chain_enable && chain_target==1 && chain_next_armed;
                eng_string<=auto_fire ? {1'b0,auto_index} : !opto_any ? 3'd4 : opto_string==1 ? 3'd2 : opto_string==2 ? 3'd1 : {1'b0,opto_string};
                // UART/touch note triggers keep the legacy full excitation;
                // optical triggers use the measured pluck peak.
                eng_velocity <= (ENABLE_FORCE != 0 && opto_any) ? opto_velocity : 3'd7;
                audio_alloc_ptr <= (audio_alloc_ptr == AUDIO_NV_LAST)
                                 ? 4'd0 : (audio_alloc_ptr + 4'd1);
            end
        end
    end

    // The current pitch owner is the latest physically plucked string/voice.
    // Rubbing another held chord string must not change this voice's pitch.
    // Muting the request during slides lets the existing envelope fade out.
    wire audio_vib_on = power_events && gesture_mask[1] && !any_gliding && !ev_glide && !eng_trig &&
        (USE_UART_CMD ? (cmd_vib_on && any_active) :
         (glide_owner_valid && touch_fret_held[glide_owner_string*5+:5]>{1'b0,capo_applied} && touch_vib_str[glide_owner_string] &&
          audio_active[glide_owner_voice]));

    // 仅保留一份音高调制逻辑。无滑音配置时固定为 1.0，
    // 与融合前 additive4_tone 的相位步进逐位一致。
    generate
    if (GV_PITCH == 1'b0) begin : g_audio_nopitch
        assign audio_pitch_mul = 19'h08000;
        assign any_active  = |audio_active;
        assign any_gliding = 1'b0;
        assign vib_env_hi  = 1'b0;
    end else begin : g_audio_pitch
        pitch_ctl #(.NV(AUDIO_NV), .FADE_MS(VIB_FADE_MS), .GLIDE_MS(180)) u_pc (
            .clk(clk), .rst_n(rst_n && !capo_change), .sample_tick(sample_tick && power_compute),
            .vib_on(audio_vib_on), .vib_rate_x10(cmd_vib_rate), .vib_depth(cmd_vib_depth),
            .ev_valid(ev_glide), .ev_anchor(ev_anchor), .ev_target(pitch_ev_target),
            .glide_ms(glide_ms), .note_retrig(eng_trig),
            .voice_active(audio_active), .pitch_mul(audio_pitch_mul),
            .gliding(any_gliding), .vib_active(vib_env_hi));
        assign any_active = |audio_active;
    end
    endgenerate

    capo_pitch u_capo(.clk(clk),.rst_n(rst_n),.semitones(capo_requested),
        .gesture(audio_pitch_mul),.combined(capo_combined),.common(capo_common),.applied(capo_applied));
    additive4_tone #(
        .NV_LOCAL(AUDIO_NV), .NOTE_BITS(6),
        // 宽频拨弦噪声在融合板上会遮住基频；相比原默认再降 12 dB。
        .NOISE_SH(20)
    ) u_eng (
        .clk(clk), .rst_n(rst_n && !power_clear), .sample_tick(sample_tick && power_compute),
        .busy(engine_busy),
        .trig_valid(eng_trig), .trig_voice(eng_voice),
        .trig_note(eng_note),.trig_velocity(eng_velocity),.trig_model(eng_model),.trig_send(eng_send),
        .trig_string(eng_string),.send_route(chain_actual_route),
        .trig_envelope(envelope_play),.gain(gain_w), .pitch_mul(capo_combined), .capo_mul(capo_common),
        .pitch_voice(glide_owner_voice), .glide_tail_damp(glide_tail_damp),
        .tone_we(tone_we_r), .tone_wsel(tw_sel), .tone_waddr(tw_addr),
        .tone_wdata(tw_data), .pt_k(pt_k_sel),
        .sample_out(additive_sample),.sample_send(additive_send), .voice_active(audio_active),
        .dbg_env(eng_dbg_env));

    assign eng_active = audio_active;
    // 正常单弦与四弦和声均在 18bit 引擎限幅范围内；顶层仍保留 21bit
    // 进入柔和限幅，避免再增加一次硬削波。
    wire signed [20:0] full_sum = $signed({{3{additive_sample[17]}}, additive_sample});

    // ============ 限幅 + I2S ============
    wire signed [21:0] lim_in = $signed({{1{full_sum[20]}}, full_sum});
    wire signed [15:0] mix_limited;
    reg signed [15:0] mix_lim;
    limiter16 #(.W(22)) u_limiter (.x(lim_in), .y(mix_limited));
    // Audio engine finishes >200 clocks before the next sample_tick. This
    // register breaks limiter->effect-select->mixer timing without adding
    // a sampled-audio frame or changing the emitted PCM.
    always @(posedge clk or negedge rst_n)if(!rst_n)mix_lim<=0;else mix_lim<=mix_limited;

    reg analysis_request,analysis_scope;
    reg [15:0] analysis_token;
    wire analysis_ready,analysis_valid,analysis_busy;
    wire [7:0] analysis_byte;
    wire signed [16:0] fx_wet;
    wire signed [15:0] fx_dry;
    wire signed [15:0] send_limited;
    limiter16 #(.W(18)) u_send_limiter(.x(additive_send),.y(send_limited));
    reg signed [15:0] fx_full_dry;
    always @(posedge clk or negedge rst_n)if(!rst_n)fx_full_dry<=0;else if(sample_tick)fx_full_dry<=mix_lim;
    audio_fx u_audio_fx(.clk(clk),.rst_n(rst_n),.enable(power_compute),
        .sample_tick(sample_tick),.sample_in(chain_active&&chain_actual_route!=0?send_limited:mix_lim),.cfg_mode(fx_mode),.cfg_strength(fx_strength),
        .wet_out(fx_wet),.dry_out(fx_dry),.actual_mode(fx_actual_mode),.wet_gain(fx_gain),
        .ready(fx_ready),.settled(fx_settled),.overrun(fx_overrun),
        .chain_enable(chain_enable),.chain_mask(chain_mask),.chain_strengths(chain_strengths),
        .chain_active(chain_active),.chain_actual_mask(chain_actual_mask),.chain_settled(chain_settled),
        .chain_route(chain_target),.chain_actual_route(chain_actual_route));
    wire signed [15:0] fx_dry_aligned=chain_active ? fx_full_dry : (fx_actual_mode!=0) ? fx_dry : mix_lim;
    wire signed [17:0] fx_sum=$signed({{2{fx_dry_aligned[15]}},fx_dry_aligned})+$signed({fx_wet[16],fx_wet});
    wire signed [15:0] fx_mix=(fx_sum[17:15]==3'b000 || fx_sum[17:15]==3'b111) ? fx_sum[15:0] : fx_sum[17] ? 16'sh8000 : 16'sh7fff;
    reg signed [15:0] sample_mix;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) sample_mix <= 16'sd0;
        else        sample_mix <= power_compute ? fx_mix : 16'sd0;
    end

    // ★ v81 修（代码复核发现）：这三个中间网的**声明必须在使用之前**。
    //   原来声明写在下面的第 745 行（使用之后），综合器按"隐式声明"处理并逐条告警：
    //     Warning: Undeclared symbol 'i2s_bclk_i', assumed default net type 'wire'(top_gv_host.v:720)
    //     WARN (EX3638) : 'i2s_bclk_i' is already implicitly declared on line 720
    //   功能上侥幸没坏（隐式网也是 1 位 wire，PnR 四个脚都在），但这是**地雷**：
    //   隐式网的位宽永远是 1 位，将来谁把它改成多位总线就会静默出错。
    wire i2s_bclk_i, i2s_lrck_i, i2s_data_i;

    i2s_tx u_i2s_tx (.clk(clk), .rst_n(rst_n), .sample_tick(sample_tick),
        .sample_in(sample_mix), .clock_safe(power_clock_safe), .bclk(i2s_bclk_i), .lrck(i2s_lrck_i), .data_out(i2s_data_i));

    // ============================================================================
    // ★★ v81：静默启动门 —— **默认关闭**，与三个"确定能出声"的工程（05/06/13）保持一致
    // ----------------------------------------------------------------------------
    //   历史（v78 加进来的）：原意是"配置完成后先把四根线拉低 500ms，给 PCM5102A
    //   一个干净起点"，理由是当时 ADC 就是靠"给足 POR 时间"修好的。
    //   ★ 但代码复核发现这个**理由引错了手册段落**：
    //     原注释引的是 "if BCK and LRCK start correctly while SCK remains at ground level
    //     for 16 successive LRCK periods, then the **internal PLL** starts."
    //     —— 这句描述的是**三线模式（不用外部 SCK、靠内部 PLL）**的路径；
    //     而本设计是**四线模式（外部 SCK 由 G5 给）**，手册同一节紧接着写的是
    //     "The internal PLL is disabled as soon as an external SCK is supplied."
    //     ⇒ 我们根本不要内部 PLL，这句不是给我们用的。
    //   而且门一开，BCK/LRCK/DIN/SCK **四根同时**从 0 释放，
    //   "BCK/LRCK 正常起振而 SCK 保持地电平"的前提也不成立。
    //   ⇒ 结论：这个门**没有手册依据**，又是 05/06/13 都没有、且从未验证过的东西，
    //     按"与已验证设计保持一致"的原则**默认关掉**。想再试就改这一个数：
    //       AUDIO_QUIET_MS = 0    → 关（默认，与 06 一致）
    //       AUDIO_QUIET_MS = 500  → 开（原来的行为）
    //   ⚠ 位宽铁律：500ms @50MHz = 25_000_000 需要 **25 位**（2^25 = 33,554,432）。
    //     原来写 `localparam [24:0]` 是对的；但这行算式以前踩过截断坑，保留注释警示。
    // ============================================================================
    localparam integer AUDIO_QUIET_MS = 0;
    localparam [24:0] AUDIO_QUIET_N =
        (AUDIO_QUIET_MS <= 0) ? 25'd0
                              : (25'd50_000 * AUDIO_QUIET_MS[24:0]) / 25'd1000;
    reg [24:0] audio_q;
    // ★ AUDIO_QUIET_N = 0 时必须立刻放行：若写成 `audio_q >= AUDIO_QUIET_N`，
    //   复位后 audio_q=0 也满足，行为一样，但若有人把它改成 1 就变成"要等 1 拍"，
    //   语义含糊 ⇒ 显式写成"门关掉就直接为真"。
    wire audio_run = (AUDIO_QUIET_N == 25'd0) || (audio_q >= AUDIO_QUIET_N);
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)          audio_q <= 25'd0;
        else if (!audio_run) audio_q <= audio_q + 25'd1;
    end

    assign i2s_bclk = (audio_run && power_clock_gate) ? i2s_bclk_i : 1'b0;
    assign i2s_lrck = (audio_run && power_clock_gate) ? i2s_lrck_i : 1'b0;
    assign i2s_data = (audio_run && power_clock_gate) ? i2s_data_i : 1'b0;

    // ============================================================================
    // ★★ v81：MCLK —— 改成 **25 MHz = 512 × fS**，与"确定能出声"的 06 工程一致
    // ----------------------------------------------------------------------------
    //   ★ 先纠正一条**假事实**（代码复核发现，原来的注释是错的）：
    //     旧注释写「MCLK = 50MHz/4 = 12.5 MHz，**与 06 工程逐字一致**」—— 这是假的。
    //     06 的实际代码是（06_full_system/ukulele_top/src/ukulele_top.v）：
    //         reg mclk_div;  mclk_div <= ~mclk_div;   // 每拍翻转 = 50/2 = **25 MHz**
    //         assign mclk = mclk_div;
    //     即 25 MHz = 512 × fS，比这里的 12.5 MHz **高一倍**。差了一倍却写着"逐字一致"，
    //     这正是那种会把后续排查带偏的注释。
    //   PCM5102A 手册里 256× 与 512× **都在允许表内**
    //     （pcm5102a_extract.txt：SCK multiples = 64,128,192,256,384,512,768,…），
    //     所以这不是"确定的 bug"；但 25 MHz 是**在本硬件上被验证过能出声的那一档**，
    //     而 12.5 MHz 从来没有人验过 ⇒ 默认选已验证的那一档。
    //   想回到 12.5 MHz：把 MCLK_SEL_25M 改成 0（这一个数就是全部开关）。
    // ============================================================================
    localparam MCLK_SEL_25M = 1;
    reg [1:0] mclk_div;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) mclk_div <= 2'd0;
        else        mclk_div <= mclk_div + 2'd1;
    end
    // mclk_div[0] = 25 MHz（每拍翻转），mclk_div[1] = 12.5 MHz（4 分频）
    wire mclk_sel = MCLK_SEL_25M ? mclk_div[0] : mclk_div[1];
    // ★ 门关掉（默认）时 MCLK 从配置起就一直送，与 06/05/13 一致；
    //   门打开时静默期内 MCLK 也拉低（那是原来 v78 的行为，保留但不再是默认）。
    assign i2s_sck = power_clock_gate && audio_run ? mclk_sel : 1'b0;
    always @(posedge clk or negedge rst_n)
        if(!rst_n)power_clock_gate<=1'b1;
        else if(power_clock_safe && !mclk_sel)power_clock_gate<=power_clocks_enable;

    // ============================================================================
    // ★ 2026-09-23「发声链诊断」：把"触发 → 引擎 → 混音 → I2S"每一级的活口报进 $O 遥测。
    //   背景：光电拨弦明明出了事件（PEAK/EV 都在动）却听不到声音，而这四级里
    //   任何一级断了、从外面都完全看不出来。四个量分别对应一个断点：
    //     a_trig_cnt = 引擎被触发过多少次   （不涨 ⇒ 触发链断，问题在 strum_detect/事件判定）
    //     a_vol      = vol_q8 当前值        （= 0 ⇒ 屏幕/ESP32 把音量下发成 0，
    //                                          引擎在响但被乘没了，是"程序问题"不是"接线问题"）
    //     a_peak     = 最近 100ms 内 |sample_mix| 峰值
    //                                       （恒 0 ⇒ 引擎没出波形；非 0 而仍无声 ⇒ 问题在 DAC/功放/喇叭）
    //     a_active   = 当前还有没有声部在响  （触发后应立刻变 1，几百 ms 后落回 0）
    // ============================================================================
    // ★ 2026-09-23 修（子代理复核发现）：`a_peak_tmr` 原来是 20 位，装不下 4_999_999
    //   （需要 23 位）⇒ `20'd4_999_999` 被截成 805695，统计窗变成 **16.1ms** 而不是注释里
    //   写的 100ms（综合日志 EX3792 点名）。同款位宽溢出在本文件已出现三次：
    //   4 位装不下 18、5 位装不下 33、20 位装不下 5e6。
    //   ★ 规矩：**凡是拿字面量去比较的计数器，位宽必须 >= 该字面量的位宽**。
    reg  [15:0] a_trig_cnt;
    reg  [15:0] a_peak, a_peak_acc;
    reg  [22:0] a_peak_tmr;
    wire [15:0] a_mag = sample_mix[15] ? (~sample_mix + 16'd1) : sample_mix;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            a_trig_cnt <= 16'd0; a_peak <= 16'd0; a_peak_acc <= 16'd0; a_peak_tmr <= 23'd0;
        end else begin
            if (eng_trig) a_trig_cnt <= a_trig_cnt + 16'd1;
            if (a_mag > a_peak_acc) a_peak_acc <= a_mag;
            if (a_peak_tmr >= 23'd4_999_999) begin        // 100 ms 一个统计窗
                a_peak_tmr <= 23'd0;
                a_peak     <= a_peak_acc;                 // 锁存上一窗的峰值（随时可读）
                a_peak_acc <= 16'd0;
            end else begin
                a_peak_tmr <= a_peak_tmr + 23'd1;
            end
        end
    end

    // ★ v45：PING/PONG 只存在于 USE_UART_CMD=1 分支（见上面的 g_uart_cmd）

    // ============================================================================
    // ★ ESP32 链路（串口屏的中间桥）—— 始终生效，不受 USE_UART_CMD 分支影响
    // ----------------------------------------------------------------------------
    //   为什么独立成一段：PC 那条命令链（cmd_decoder）挂在 USE_UART_CMD=1 分支下，
    //   而默认 USE_UART_CMD=0（走触摸链）⇒ cmd_decoder 根本没被例化。
    //   屏幕要用的音量/音色控制不能依赖那个分支，所以在这里单独实现一份最简解析。
    //
    //   协议与 PC 那路完全相同（见 cmd_decoder.v / host/PROTOCOL.md）：
    //       下行  AA | CMD | LEN | PAYLOAD... | CRC     CRC = CMD^LEN^PAYLOAD 各字节
    //         CMD 0x06 GAIN   [音量 0..255]
    //         CMD 0x07 TIMBRE [音色索引 0..3]
    //         CMD 0x08 OPTOCFG[ch, th_lo, th_hi, gain]           ★ 每通道阈值/增益
    //         CMD 0x09 TONEWR [sel, addr_lo, addr_hi, d0..d3]    ★ v61：音色数据写入
    //         CMD 0x0A OPTGLOB[pid, b0, b1, b2]                  ★ v80：全局参数（功能④补全）
    //         CMD 0x0B OPTGET []                                 ★ v80：请求一帧 0x86 参数回读
    //         CMD 0x0C OPTFIRST[b0, b1, b2]                      首事件锁定窗（20 位）
    //       上行  AA | 0x81 | LEN | PAYLOAD... | CRC
    //         0x81 状态帧，LEN=5：[音库号, 音量, 音色索引, flags, 已收命令数]，50ms 一次
    //         0x84 光电遥测，LEN=17：[raw0..3, dg0..3, ev]，100ms 一次
    //         0x86 参数回读，LEN=30：当前全部光电参数（th/gain + 8 个全局），按需
    //   音色数据写入（0x09）的字段/时序/用法见 `docs/音色下发协议.md`；
    //   光电校正（0x08/0x0A/0x0B/0x84/0x86）见 `docs/屏幕光电校正方案.md`。
    // ============================================================================
    localparam [2:0] ES_SYNC = 3'd0, ES_CMD = 3'd1, ES_LEN = 3'd2,
                     ES_DATA = 3'd3, ES_CRC = 3'd4;

    // Ready/valid streams complete frames through a 16-byte FIFO.
    // Frame size may exceed FIFO capacity: stop the producer until a byte drains.
    // ★ 物理层：SPI 从机「字节管道」（替代原来的 UART）。
    //   对外接口形状与 uart_rx/uart_tx 一致 ⇒ 下面的帧解析器一行都不用改。
    //   rx：主机 MOSI 送来的字节 → esp_rx_byte/esp_rx_valid
    //   tx：要发给主机的字节 ← tx_start 压入 FIFO（见后面 0x81/0x84 调度器）
    wire [7:0] esp_tx_byte;         // = 调度器的 st_byte（在下面 assign）
    wire       esp_tx_start;        // = 调度器的 st_start（1 拍脉冲）
    wire       esp_tx_ready;        // SPI FIFO space, direct ready/valid handshake

    // ★ 诊断用：把从机的计数/最近事务字节接出来送进 $O 遥测（见下面的 o_cur）。
    //   作用是把 "ESP32→FPGA 不通" 一刀切成两种完全不同的故障：
    //     · spi_rxc 恒 0  → 主机"声称要发字节"的事务 FPGA 一次都没看见
    //                       ⇒ MOSI 没通（或 CS/SCK 没到），是接线/物理层问题
    //     · spi_rxc 在涨但 es_ok_cnt 不涨 → 字节到了、但帧解析/CRC 不过，是协议问题
    //   spi_rxc 只在"第 1 字节 bit7=1"时 +1，正好等于主机 sendByte 的次数。
    wire [15:0] spi_rxc, spi_dbg;
    wire [7:0]  spi_b1, spi_b2;

    spi_slave_bytes #(
        .CLK_HZ(CLK_HZ), .SCK_MAX_HZ(5_000_000), .FIFO_AW(2)
    ) u_esp_spi (
        .clk(clk), .rst_n(rst_n),
        .sck(spi_sck), .cs_n(spi_cs_n), .mosi(spi_mosi), .miso(spi_miso),
        .rx_byte(esp_rx_byte), .rx_valid(esp_rx_valid),
        .tx_byte(esp_tx_byte), .tx_start(esp_tx_start), .tx_ready(esp_tx_ready),
        .rx_cnt(spi_rxc), .tx_cnt(), .tx_drop(),
        .fifo_level(), .dbg_b1(spi_b1), .dbg_b2(spi_b2)
    );
    assign spi_dbg = {spi_b1, spi_b2};   // 最近一次事务的两个字节拼成一个 16 位字段

    reg  [2:0]  es_st;
    reg  [7:0]  es_code, es_len, es_cnt, es_crc;
    // ★ v81：本帧的 LEN 是否越界（> 8）。越界帧照旧收完、验 CRC，但**不执行任何命令**。
    reg         es_bad;
    // es_pay declared before the power/event gate.
    // reg  [7:0]  es_pay [0:7];    // 最多缓存 8 字节载荷
                                 //   （0x08 OPTOCFG 用 4 字节；★ v61 的 0x09 TONEWR 用 7 字节）
    // ★ v62：`esp_timbre` 的**声明**已提前到文件前部（pt_k_sel 那段要用它），
    //   这里只保留它的赋值（复位初值 + 0x07 分支）。别再在下面重新声明一次：
    //   重复声明会报重定义，且容易连带出 EX1998。
    reg  [7:0]  es_ok_cnt;       // 收到并校验通过的命令数
    // ★ 2026-09-23：从 3 位扩到 8 位。原来是 3 位（0..7 回绕），ESP32 的 'w' 自检
    //   靠 "cmd_cnt 有没有变" 判断方向通不通，撞上回绕就会**误报"不通"**
    //   （实测连续两次 cmd_cnt 6→7→0 被判成失败）。8 位要到 256 条才回绕，够用。

    // ---- LED matrix configuration (0x17, five-byte payload) ----
    //   Payload = [mode, axis, speed, brightness, flags].
    //   mode 0=off, 1=constant, 2=all breathe, 3=progressive,
    //        4=dragon, 5=ripple, 6=event sparkle.

    // ---- ★ v61：0x09 TONEWR（音色数据写入）的落地信号 ----
    //   载荷 [sel, addr_lo, addr_hi, d0, d1, d2, d3]（★≥7 字节即可，多出的字节只参与 CRC）：
    //     sel  = 0 inc / 1 env0 / 2 rate / 3 aatk
    //     addr = {addr_hi[0], addr_lo}（BSRAM 深度 512 ⇒ 9 位；addr_hi 只用到 bit0）
    //     d0..d3 = **小端** 32 位值：inc 用满 4 字节；env0/aatk 取低 2 字节；rate 取低 12 位
    //   写脉冲 tone_we 在"CRC 校验通过的那一拍之后"拉高 1 拍；此时 es_pay 里的载荷
    //   仍然有效（要等下一帧的 ES_DATA 才会被覆盖），所以数据线直接取 es_pay 即可。
    //   ⚠ 这三根线**必须**是"寄存器输出的 1 拍脉冲 + 稳定数据"的形态，不要接成组合逻辑
    //     直接由 esp_rx_byte 驱动 —— 写端口要稳态，否则 512 格 BSRAM 的写地址/数据会抖。
    //   （tone_we_r / tw_sel / tw_addr / tw_data 的**声明**在文件前部"提前声明"段，
    //     因为 u_eng 的例化在中间、这里只是驱动它们。）
    assign tw_sel  = es_pay[0][1:0];
    assign tw_addr = {es_pay[2][0], es_pay[1]};
    assign tw_data = {es_pay[6], es_pay[5], es_pay[4], es_pay[3]};

    // ---- 光电参数寄存器（功能④：可由屏幕/上位机运行时改，不再是编译期常量）----
    //   0x08 OPTOCFG 载荷 [ch, th_lo, th_hi, gain]：
    //     ch  = 0..3 选哪一路；th = 12 位阈值；gain = 凹坑增益（sg=(dip*gain)>>6）
    reg  [7:0]  o_g0,  o_g1,  o_g2,  o_g3;
    // o_th*/o_cfg_cnt are declared beside the force mapper above.

    // ---- ★ v80：0x0A OPTGLOB —— strum_detect 的**全局参数**（功能④补全）----
    //   为什么必须有这条命令：原来这 8 个参数是**写死的常量**（见 u_strum 例化处），
    //   而 PC 上位机 opto_tuner.py 的"四档预设"和"自动整定"里它们全是变量：
    //       PRESETS = { 灵敏优先: cool=4000 slow=131072 fast=4096 blank=45000,
    //                   均衡(推荐): cool=7000 slow=262144 fast=8192 blank=90000,
    //                   抗误报:    cm_cap=150 cool=12000 slow=524288 fast=16384 blank=180000,
    //                   抗环境光:  cm_cap=250 cool=7000  slow=131072 ...
    //   结果就是：屏幕上点"加载预设"，只有阈值/增益真的变了，其余参数纹丝不动
    //   ⇒ 预设之间**没有任何区别**（功能④等于只做了一半）。这里把它们变成运行时寄存器。
    //   载荷 [pid, b0, b1, b2]（小端，取低位）：
    //     pid 0=slow(20) 1=fast(20) 2=cm_cap(18) 3=margin(8)
    //         4=persist(8) 5=cool(16) 6=blank(20) 7=rearm(16)
    reg  [19:0] o_slow, o_fast, o_blank;
    reg  [17:0] o_cmcap;
    reg  [7:0]  o_margin, o_persist;
    reg  [15:0] o_cool, o_rearm;
    reg  [3:0]  o_glob_cnt;      // 收到过多少条全局参数（调试用）
    // ★ 改完参数给 strum_detect 一个 1 拍 stamp：让它重新武装（清 ar），
    //   新参数从下一个采样点起就是干净的。strum_detect v12.2 的 stamp **不再重采基线**
    //   （重采会把 acq_cnt 永远清零 ⇒ 基线冻结，那是 v12.1 的 bug），所以这里可以放心打。
    reg         o_stamp;
    // 0x0B OPTGET 的请求/确认握手（两个 always 块各拥有一个 reg，避免双驱动）
    reg [7:0] o_get_token;
    reg         o_getreq;        // 本块拥有：收到 0x0B 置位
    reg         o_getack;        // 调度器块拥有：取走请求时置位 ⇒ 本块据此清零

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            touch_diag_req<=0;touch_diag_modern<=0;touch_diag_channel<=0;touch_diag_token<=0;
            profile_cmd_we<=0;profile_req<=0;profile_query_token<=0;
            voice_model<=0;model_token<=0;model_req<=0;envelope_commit<=0;envelope_operation<=0;envelope_new_value<=0;envelope_query<=0;envelope_token<=0;capo_requested<=0;capo_token<=0;capo_req<=0;
            chain_enable<=0;chain_mask<=0;chain_strengths<={7'd64,7'd48,7'd48,7'd64};chain_target<=0;chain_token<=0;chain_req<=0;chain_next_armed<=0;
            fx_mode<=0;fx_strength<=64;fx_token<=0;fx_getreq<=0;
            gesture_mask<=3'b111;gesture_getreq<=0;gesture_token<=0;
            touch_vib_threshold<=360;touch_rate_fast<=DECIM==64;touch_grace_ms<=0;touch_token<=0;touch_getreq<=0;led_diag_req<=0;led_diag_token<=0;
            es_st <= ES_SYNC;
            es_code <= 8'd0; es_len <= 8'd0; es_cnt <= 8'd0;
            es_bad  <= 1'b0;
            es_crc <= 8'd0;
            es_pay[0] <= 8'd0; es_pay[1] <= 8'd0; es_pay[2] <= 8'd0; es_pay[3] <= 8'd0;
            es_pay[4] <= 8'd0; es_pay[5] <= 8'd0; es_pay[6] <= 8'd0; es_pay[7] <= 8'd0;
            tone_we_r <= 1'b0;
            led_cfg_we <= 1'b0;
            led_guide_we <= 1'b0;
            led_mode_cfg <= 4'd0;
            led_axis_cfg <= 1'b0;
            led_speed_cfg <= 8'd24;
            led_level_cfg <= 8'd180;
            led_flags_cfg <= 8'd0;
            led_guide_frets <= 20'hFFFFF;
            led_guide_levels <= 0;
            auto_left<=0;auto_index<=0;auto_on<=0;auto_pulse<=0;auto_have<=0;auto_token<=0;auto_frets<=20'hfffff;auto_stamp<=0;
            power_cmd_valid<=0;power_sleep_cmd<=0;analysis_request<=0;analysis_scope<=0;analysis_token<=0;power_getreq<=0;
            vol_q8 <= 8'd51;         // 20% boot volume, matching ESP32 and UI.
            esp_timbre <= 8'd0;
            es_ok_cnt <= 8'd0;
            // 光电默认参数恢复到融合前实际使用的“均衡”档。
            // 上一版抗误报档把阈值、持续时间和盲区都抬得过高，导致正常拨弦
            // 也很难越过检测门限；多根弦的第一事件锁仍保留，负责抑制连触发。
            o_th0 <= `PERF_P0; o_th1 <= `PERF_P1; o_th2 <= `PERF_P2; o_th3 <= `PERF_P3;
            o_g0  <= `PERF_P4;   o_g1  <= `PERF_P5;   o_g2  <= `PERF_P6;   o_g3  <= `PERF_P7;
            o_cfg_cnt <= 3'd0;
            // 全局参数 = opto_tuner.py 的“均衡（推荐）”档。
            o_slow    <= `PERF_P8;
            o_fast    <= `PERF_P9;
            o_cmcap   <= `PERF_P10;
            o_margin  <= `PERF_P11;
            o_persist <= `PERF_P12;
            o_cool    <= `PERF_P13;
            o_blank   <= `PERF_P14;
            o_rearm   <= `PERF_P15;
            o_first_lock <= OPTO_FIRST_LOCK_DEFAULT;
            performance_poly<=0; performance_mode_stamp<=0; o_get_token<=0;
            o_glob_cnt <= 4'd0;
            o_stamp   <= 1'b0;
            o_getreq  <= 1'b0;
        end else begin
            profile_cmd_we<=0;
            if(audio_trig && chain_target==1 && chain_next_armed)chain_next_armed<=0;
            auto_pulse<=0;
            if(auto_on && auto_left!=0 && !capo_change && !opto_any && !trig_note)begin
                auto_left<=auto_left-1'b1;auto_index<=auto_index+1'b1;auto_frets<={5'd31,auto_frets[19:5]};
            end
            if(!power_events || (auto_on && auto_age>=200))auto_on<=0;
            power_cmd_valid<=0;power_sleep_cmd<=0;analysis_request<=0;
            // ★ v61：写脉冲默认拉低（只在下面 0x09 校验通过那一拍置 1，恒为 1 拍宽）
            tone_we_r <= 1'b0;
            led_cfg_we <= 1'b0;
            led_guide_we <= 1'b0;
            o_stamp   <= 1'b0;
            performance_mode_stamp<=0;
            if(profile_select)begin led_mode_cfg<=8;led_level_cfg<=profile_requested_level;led_cfg_we<=1;end
            envelope_commit<=0;
            if(envelope_done) model_req<=~model_req;
            if (esp_rx_valid) begin
            case (es_st)
                ES_SYNC: if (esp_rx_byte == 8'hAA) es_st <= ES_CMD;

                ES_CMD: begin
                    es_code <= esp_rx_byte;
                    es_crc  <= esp_rx_byte;
                    es_st   <= ES_LEN;
                end

                ES_LEN: begin
                    es_len <= esp_rx_byte;
                    es_crc <= es_crc ^ esp_rx_byte;
                    es_cnt <= 8'd0;
                    // ★★ v81 修（代码复核发现）：**LEN 上界要当场判掉**。
                    //   `es_pay` 只有 8 格，而 ES_DATA 里写的是 `if (es_cnt < 8)` ——
                    //   也就是 LEN=9..32 的帧会**收完、CRC 也算对、然后静默只用前 8 字节执行**。
                    //   而 ESP32 侧 `gvlink` 能发出最多 32 字节载荷 ⇒ 两侧对 LEN 的语义不一致，
                    //   一旦将来有人加了 9 字节以上的命令，就会"发出去没报错、FPGA 却按截断值执行"。
                    //   ⇒ 超过 8 的帧标记为坏帧（照旧收完并验 CRC，但**不执行**任何命令）。
                    es_bad <= (esp_rx_byte > 8'd8);
                    es_st  <= (esp_rx_byte == 8'd0) ? ES_CRC : ES_DATA;
                end

                ES_DATA: begin
                    // ★ v61：载荷缓存从 4 字节扩到 8 字节（0x09 TONEWR 要 7 字节）
                    if (es_cnt < 8'd8) es_pay[es_cnt[2:0]] <= esp_rx_byte;
                    es_crc <= es_crc ^ esp_rx_byte;
                    es_cnt <= es_cnt + 8'd1;
                    if ((es_cnt + 8'd1) >= es_len) es_st <= ES_CRC;
                end

                ES_CRC: begin
                    // ★ v81：`!es_bad` 是"LEN 上界合法"（见 ES_LEN 处注释）
                    if ((esp_rx_byte == es_crc) && !es_bad) begin   // 校验通过才执行
                        case (es_code)
                            8'h06: vol_q8     <= es_pay[0];
                            // ★ 0x07 TIMBRE：选择 rec28/guitar33 的步长和音高口径；
                            //   完整参数表由 ESP32 随后通过 0x09 TONEWR 写入。
                            8'h07: esp_timbre <= FIXED_REC28_AUDIO ? 8'd0
                                                                    : {6'd0, es_pay[0][1:0]};
                            // ★ v81 修（代码复核发现）：**补上 LEN>=4 的长度校验**。
                            //   原来 `0x08` 是唯一一条**不检查长度**的命令（`0x09`/`0x0A` 都查了）：
                            //   它直接用 `es_pay[1]`、`es_pay[2]`、`es_pay[3]`。
                            //   一条 CRC 正确但 LEN=1 的帧（比如线上被干扰吃掉几个字节后又凑出
                            //   一个"刚好对上"的 CRC，或对端软件写错）会让 `es_pay[1..3]` 取到
                            //   **上一帧残留的值**，把阈值/增益写成一堆看不出所以然的数。
                            //   ⇒ 与 0x09/0x0A 一致：长度不足就整条不执行。
                            8'h08: if (es_len >= 8'd4) begin  // ★ 光电参数下发（功能④）
                                case (es_pay[0][1:0])
                                    2'd0: begin o_th0 <= {es_pay[2][3:0], es_pay[1]}; o_g0 <= es_pay[3]; end
                                    2'd1: begin o_th1 <= {es_pay[2][3:0], es_pay[1]}; o_g1 <= es_pay[3]; end
                                    2'd2: begin o_th2 <= {es_pay[2][3:0], es_pay[1]}; o_g2 <= es_pay[3]; end
                                    default: begin o_th3 <= {es_pay[2][3:0], es_pay[1]}; o_g3 <= es_pay[3]; end
                                endcase
                                o_cfg_cnt <= o_cfg_cnt + 3'd1;
                            end
                            // ★ v80：0x0A OPTGLOB —— 全局参数下发（功能④补全，8 个 pid）
                            //   长度不足 4 字节 ⇒ 不写（防止半截帧把参数改成垃圾）
                            8'h0A: if (es_len >= 8'd4) begin
                                case (es_pay[0][2:0])
                                    3'd0: o_slow    <= {es_pay[3][3:0], es_pay[2], es_pay[1]};
                                    3'd1: o_fast    <= {es_pay[3][3:0], es_pay[2], es_pay[1]};
                                    3'd2: o_cmcap   <= {es_pay[3][1:0], es_pay[2], es_pay[1]};
                                    3'd3: o_margin  <= es_pay[1];
                                    3'd4: o_persist <= es_pay[1];
                                    3'd5: o_cool    <= {es_pay[2], es_pay[1]};
                                    3'd6: o_blank   <= {es_pay[3][3:0], es_pay[2], es_pay[1]};
                                    default: o_rearm <= {es_pay[2], es_pay[1]};
                                endcase
                                o_glob_cnt <= o_glob_cnt + 4'd1;
                                o_stamp    <= 1'b1;      // 1 拍：让 strum_detect 重新武装
                            end
                            // ★ 首事件锁定窗：20 位时钟数，单独命令保持旧 0x0A
                            // 的 pid 编号兼容。0 表示关闭时间门控。
                            8'h0C: if (es_len >= 8'd3) begin
                                o_first_lock <= {es_pay[2][3:0], es_pay[1], es_pay[0]};
                                o_stamp      <= 1'b1;
                            end
                            // ★ LED matrix configuration.  The complete five-byte
                            // payload is kept in FPGA registers; the renderer turns
                            // it into LP5860 PWM bursts without a 72-byte framebuffer.
                            // 0x23 profile v2, exact8 bytes; keeps the legacy parser bound.
                            8'h25:if(es_len==3 && es_pay[0]==1 && (es_pay[1]<=3 || es_pay[1]==255))begin
                                if(es_pay[1]<=3)begin voice_model<=es_pay[1][1:0];esp_timbre<=es_pay[1]==1 ? 8'd1:8'd0;end
                                model_token<=es_pay[2];model_req<=~model_req;
                            end else if(es_len==6 && envelope_ready && es_pay[0]==4 && es_pay[1]<=3 && es_pay[2]<4 && es_pay[3]<28)begin
                                envelope_query<={es_pay[2][1:0],es_pay[3][4:0]};
                                envelope_token<=es_pay[5];envelope_operation<=es_pay[1][1:0];
                                envelope_new_value<=es_pay[4];envelope_commit<=1;
                            end
                            8'h24:if(es_len==3 && es_pay[0]==1 && (es_pay[1]<=12 || es_pay[1]==255))begin
                                if(es_pay[1]<=12)capo_requested<=es_pay[1][3:0];
                                capo_token<=es_pay[2];capo_req<=~capo_req;
                            end
                            8'h23:if(es_len==8 && es_pay[0]==2 && es_pay[1]<=3)begin
                              profile_cmd_we<=1;profile_query_token<={es_pay[7],es_pay[6]};profile_req<=~profile_req;
                            end
                            8'h17: if (es_len >= 8'd5) begin
                                led_mode_cfg  <= (es_pay[0] > 8'd8) ? 4'd0 : es_pay[0][3:0];
                                led_axis_cfg  <= es_pay[1][0];
                                led_speed_cfg <= es_pay[2];
                                led_level_cfg <= es_pay[3];
                                led_flags_cfg <= es_pay[4];
                                led_cfg_we    <= 1'b1;
                            end
                            // 0x18 legacy LEDGUIDE: one fret applied to selected strings
                            //   payload = [column_mask, fret(0..18), level, reserved]
                            //   column_mask bit0..3 = 视觉左→右四列；flags 保留。
                            //   采用单点命令避免在 FPGA 中保存 72 字节帧缓冲，
                            //   ESP32 负责曲目计时，FPGA 只做确定时序的 PWM 刷新。
                            8'h18: if (es_len == 8'd4 && es_pay[0]<=15 && es_pay[1]<=18) begin
                                led_guide_frets <= {
                                  es_pay[0][3] ? es_pay[1][4:0] : 5'd31,
                                  es_pay[0][2] ? es_pay[1][4:0] : 5'd31,
                                  es_pay[0][1] ? es_pay[1][4:0] : 5'd31,
                                  es_pay[0][0] ? es_pay[1][4:0] : 5'd31};
                                led_guide_levels <= {4{es_pay[2]}};
                                led_guide_we    <= 1'b1;
                                led_mode_cfg    <= 4'd7;
                                led_cfg_we      <= 1'b1;
                            end
                            // New-board polyphonic LED guide. One CRC-protected packet
                            // replaces all four strings atomically; no last-event overwrite.
                            // [version=1, G_fret, C_fret, E_fret, A_fret, brightness].
                            // Fret 0=open/all rows; 1..18=physical fret; 31=off.
                            // Audio FX v1: [1, op(0=query/1=set), mode, strength, tokenLE16].
                            // Strict lengths/ranges; old firmware ignores unknown commands.
                            8'h22:if(es_len==3 && (es_pay[0]==1 || es_pay[0]==2) && es_pay[1]<TOUCH_DIAG_CHANNELS && touch_diag_req==touch_diag_ack)begin
                                touch_diag_modern<=es_pay[0]==2;touch_diag_channel<=es_pay[1][6:0];touch_diag_token<=es_pay[2];touch_diag_req<=~touch_diag_req;end
                            // Advanced chain v1: [1, mask|255=query|254=legacy,
                            // tremolo, chorus, flanger, distortion, route(0..5), token].
                            // v1: op(0 stop,1 chord,2 lease), token16, four canonical frets.
                            // Consecutive retries are idempotent. A missing ESP lease ends playback.
                            8'h28:if(es_len==8 && es_pay[0]==1 && es_pay[1]<=2 &&
                               (es_pay[4]<=18 || es_pay[4]==31) && (es_pay[5]<=18 || es_pay[5]==31) &&
                               (es_pay[6]<=18 || es_pay[6]==31) && (es_pay[7]<=18 || es_pay[7]==31))begin
                                auto_stamp<=touch_time_ms;
                                if(es_pay[1]==0)begin auto_on<=0;auto_left<=0;end
                                else begin auto_on<=1;
                                  if(es_pay[1]==1 && (!auto_have || auto_token!={es_pay[3],es_pay[2]}))begin
                                    auto_left<=4;auto_index<=0;auto_have<=1;auto_token<={es_pay[3],es_pay[2]};auto_pulse<=1;
                                    auto_frets<={es_pay[7][4:0],es_pay[6][4:0],es_pay[5][4:0],es_pay[4][4:0]};
                                  end
                                end
                            end
                            8'h27:if(es_len==8 && es_pay[0]==1 &&
                               (es_pay[1]<=15 || es_pay[1]==254 || es_pay[1]==255) &&
                               es_pay[2]<=127 && es_pay[3]<=96 && es_pay[4]<=96 && es_pay[5]<=127 && es_pay[6]<=5)begin
                                if(es_pay[1]<=15)begin
                                    chain_enable<=1;chain_mask<=es_pay[1][3:0];chain_strengths<={es_pay[5][6:0],es_pay[4][6:0],es_pay[3][6:0],es_pay[2][6:0]};
                                    chain_target<=es_pay[6][2:0];chain_next_armed<=es_pay[6]==1;
                                end else if(es_pay[1]==254)begin chain_enable<=0;chain_next_armed<=0;end
                                chain_token<=es_pay[7];chain_req<=~chain_req;
                            end
                            8'h21:if(es_len==6 && es_pay[0]==3 && es_pay[1]<=1 && es_pay[2]<=4 && es_pay[3]<=((es_pay[2]==2 || es_pay[2]==3) ? 96 : 127))begin
                                if(es_pay[1]==1)begin fx_mode<=es_pay[2][2:0];fx_strength<=es_pay[3][6:0];chain_enable<=0;end
                                fx_token<={es_pay[5],es_pay[4]};fx_getreq<=~fx_getreq;
                            end
                            // v1 atomic physical frets + independent envelopes, 8 bytes.
                            8'h26:if(es_len==8 && es_pay[0]==1 && es_pay[3][7:4]==0 &&
                                (es_pay[1][4:0]<=18 || es_pay[1][4:0]==31) &&
                                ({es_pay[2][1:0],es_pay[1][7:5]}<=18 || {es_pay[2][1:0],es_pay[1][7:5]}==31) &&
                                (es_pay[2][6:2]<=18 || es_pay[2][6:2]==31) &&
                                ({es_pay[3][3:0],es_pay[2][7]}<=18 || {es_pay[3][3:0],es_pay[2][7]}==31))begin
                                led_guide_frets<={es_pay[3][3:0],es_pay[2],es_pay[1]};
                                led_guide_levels<={es_pay[7],es_pay[6],es_pay[5],es_pay[4]};
                                led_guide_we<=1;led_mode_cfg<=7;led_cfg_we<=1;
                            end
                            8'h20: if(es_len==6 && es_pay[0]==1 &&
                                (es_pay[1]<=18 || es_pay[1]==31) &&
                                (es_pay[2]<=18 || es_pay[2]==31) &&
                                (es_pay[3]<=18 || es_pay[3]==31) &&
                                (es_pay[4]<=18 || es_pay[4]==31))begin
                                led_guide_frets<={es_pay[4][4:0],es_pay[3][4:0],es_pay[2][4:0],es_pay[1][4:0]};
                                led_guide_levels<={4{es_pay[5]}};led_guide_we<=1;
                                led_mode_cfg<=7;led_cfg_we<=1;
                            end
                            // ★ v80：0x0B OPTGET —— 请求一帧 0x86 参数回读（屏幕要显示当前值）
                            //   真正发帧由下面的调度器做（st_pend86），这里只置"请求"标志。
                            8'h0B: if(es_len<=1)begin o_getreq<=~o_getreq;
                                o_get_token<=es_len==1 ? es_pay[0] : 8'd0;end
                            8'h1B: if(es_len==7 && es_pay[0]==1 && es_pay[1]<=3 && es_pay[6]<=1)begin
                                power_cmd_valid<=1;power_getreq<=~power_getreq;
                                // Decode the one-cycle event gate with the command strobe.
                                // Identical gating cycle, isolated from payload fan-out.
                                power_sleep_cmd<=es_pay[1]==1;end
                            // 0x1C: version1, mask0..3 (255=query), echoed token.
                            8'h1D: if(es_len==3 && (es_pay[0]==1 || es_pay[0]==2) && !analysis_busy)begin
                                analysis_scope<=es_pay[0]==2;
                                analysis_token<={es_pay[2],es_pay[1]};analysis_request<=1;end
                            8'h1C: if(es_len==3 &&
                                ((es_pay[0]==1 && es_pay[1]<=3) ||
                                 (es_pay[0]==2 && es_pay[1]<=7) ||
                                 ((es_pay[0]==1 || es_pay[0]==2) && es_pay[1]==255)))begin
                                if(es_pay[1]!=255)begin
                                    gesture_mask[1:0]<=es_pay[1][1:0];
                                    if(es_pay[0]==2)gesture_mask[2]<=es_pay[1][2];
                                end
                                gesture_token<=es_pay[2];gesture_getreq<=~gesture_getreq;
                            end
                            8'h1F: if(es_len==2 && es_pay[0]==1)begin led_diag_token<=es_pay[1];led_diag_req<=~led_diag_req;end
                            8'h1E: if(es_len==8 && es_pay[0]==1 && es_pay[1]<=1 &&
                                (es_pay[1]==0 || ({es_pay[3],es_pay[2]}>=120 && {es_pay[3],es_pay[2]}<=2400 && es_pay[4]<=1 && es_pay[5]<=60)))begin
                                if(es_pay[1]==1)begin
                                    touch_vib_threshold<={es_pay[3],es_pay[2]};touch_rate_fast<=es_pay[4][0];touch_grace_ms<=es_pay[5][5:0];
                                end
                                touch_token<={es_pay[7],es_pay[6]};touch_getreq<=~touch_getreq;
                            end
                            8'h19: if(es_len==1 && es_pay[0]<=1)begin
                                performance_poly<=es_pay[0][0];
                                performance_mode_stamp<=performance_poly!=es_pay[0][0];end
                            // ★ v61：0x09 TONEWR —— 音色数据写入（BSRAM 运行时改写）
                            //   这里只发"1 拍写脉冲"，真正落地在 tone_rom 的同步写端口；
                            //   sel/addr/data 直接取 es_pay（本拍有效，下一帧才被覆盖）。
                            //   长度不足 7 字节 ⇒ 不写（防止半截帧改坏音色表）。
                            8'h09: if (es_len >= 8'd7) tone_we_r <= 1'b1;
                            default: ;                       // 其它命令忽略
                        endcase
                        es_ok_cnt <= es_ok_cnt + 8'd1;
                    end
                    // ★ v80：握手清零——调度器把请求转成 st_pend86 后置 o_getack，这里清掉请求。
                    //   放在 case 之后（后写优先）：万一同一拍又来一条 0x0B，宁可丢这次请求，
                    //   也不要因为重复请求连发两帧（屏幕 2Hz 问、整帧只要 272µs，撞不上）。
                    // Request toggle is acknowledged by the scheduler, not cleared here.
                    es_st <= ES_SYNC;
                end

                default: es_st <= ES_SYNC;
            endcase
            end
        end
    end

    // 命令链路（USE_UART_CMD=1）没有触摸面板 ⇒ pad_hit 恒 0

    // ============================================================================
    // ★ 光电遥测帧（功能④的"回读通路"）
    // ----------------------------------------------------------------------------
    //   PC 上位机整定光电管时要看到四路的原始值/凹坑/基线，屏幕上做校正界面也
    //   需要这些量。telemetry_tx 那套帧格式是固定的（$H/$C/$R/$E），塞不进去，
    //   所以在它【空闲时】往同一根 tx 上插入一帧，互不干扰：
    //       telemetry 每 100ms 发一帧约 31ms（115200），本帧 10Hz 约 8ms，
    //       只在 !tele_busy 时插入 ⇒ 永远不会同时驱动。
    //   格式（定长十六进制，方便解析）：
    //       $O,RAW0,RAW1,RAW2,RAW3,PEAK0..PEAK3,EV,BASE0..BASE3,SCANUS,LASTW\n
    //       共 15 个字段 × 4 位十六进制
    // ============================================================================
    function [7:0] hexc(input [3:0] d);
        hexc = (d < 4'd10) ? (8'h30 + d) : (8'h41 + {4'd0, d} - 4'd10);
    endfunction

    // o_fld 必须 5 位：4 位只能表示 0..15，而末字段号是 21。
    // 写成 4 位时比较式里的 18 会被截成 2，帧在第 3 个字段就"以为"发完了
    // （实测收到 "$O,2750,8F71,A222," 这种只有 3 个字段的残帧）
    // ★ o_fld 必须 6 位：字段号已经加到 33，而 5 位最大只能表示 31。
    //   写成 `localparam [4:0] O_LAST = 5'd33;` 时 33 被**截成 1**，
    //   帧发到第 2 个字段就"以为"发完了（实测抓到 `$O,07D1,186A,` 这种两字段残帧）。
    //   （更早还踩过 4 位装不下 18 的同款坑 —— 这里位宽必须 >= ceil(log2(字段数))。）
    reg  [5:0]  o_fld;          // 字段号 0..47
    localparam [5:0] O_LAST = 6'd47;
    reg  [2:0]  o_pos;          // 字段内位置：0..3 = 4 个十六进制位，4 = 逗号
    reg  [1:0]  o_ph;           // 0=发 "$O," 1=发字段 2=发 '\n' 3=空闲
    reg  [1:0]  o_pre;          // "$O," 内的字符序号
    reg  [7:0]  o_byte;
    // ★ 2026-09-23 修：原来写 23 位但比较 25_000_000（需要 25 位）⇒ 被截成 8_222_784
    //   ⇒ "500ms 兜底"实际只有 164ms（EX3792 点名）。同款位宽溢出，见 a_peak_tmr 处注释。
    reg  [24:0] o_timer;
    reg         o_pend, tele_fa_d;
    wire        tele_fa_fall = tele_fa_d & ~tele_frame_active;
    wire        o_go = power_events && ((TELEMETRY==1) || DEBUG_OPTO) && !o_busy && !tele_frame_active && !tele_busy &&
                       (o_pend || o_timer >= 25'd25_000_000);
    // ★★ 2026-09-23：$O 帧的发射时机（这一块原来有两个叠加的 bug）
    //   ① `o_timer` 只有 **20 位**，装不下 5_000_000（需要 23 位）：
    //      `20'd5_000_000` 被截断成 805696 ⇒ 阈值只有 16ms，实测 $O 跑到了
    //      **20 Hz**（本该 10 Hz），白吃掉一半串口带宽。
    //   ② 发射时刻相对触摸帧是**自由漂移**的：$O 整帧 15ms，而触摸帧之间的空档
    //      只有约 43ms。一旦 $O 跨到触摸帧开头，就会把 **$H 整行吃掉** ——
    //      实测 `$H=0 / $C=658 / $R=32 / $E=32`，只丢帧头，上位机因此一帧都拼不出来
    //      （触摸解析器要求 $H 的 seq 与 $E 的 seq 相同）。
    //   ⇒ 改成**跟在触摸帧屁股后面发**：检测 tele_frame_active 的下降沿排队，
    //     等 UART 也空了再发。时刻确定、必定落在帧间空档里，永不撞车。
    //     另留 500ms 超时兜底：万一触摸遥测停了（例如没接面板），$O 仍能继续出。

    power_control #(.CLK_HZ(CLK_HZ)) u_power(
        .clk(clk),.rst_n(rst_n),.cmd_valid(power_cmd_valid),.cmd_op(es_pay[1]),
        .cmd_id({es_pay[3],es_pay[2]}),.cmd_epoch({es_pay[5],es_pay[4]}),
        .volume(vol_q8),.modules_idle(!adc_busy && !engine_busy && power_touch_asleep && power_led_asleep),
        .sensors_ready(power_adc_seen && power_touch_seen && power_touch_ready),
        .clocks_stopped(!power_clock_gate),.events_enable(power_accept),.compute_enable(power_compute),
        .modules_sleep(power_modules_sleep),.clocks_enable(power_clocks_enable),
        .audio_clear(power_clear),.wake_start(power_wake_start),.flush_events(power_flush),
        .effective_volume(power_volume),.actual_state(power_state),.result(power_result),
        .reply_id(power_id),.reply_epoch(power_epoch),.elapsed_ms(power_elapsed));
    always @(posedge clk or negedge rst_n)begin
        if(!rst_n)begin power_adc_seen<=0;power_touch_seen<=0;end
        else if(power_wake_start)begin power_adc_seen<=0;power_touch_seen<=0;end
        else begin
            if(adc_done && araw0[15:12]==0 && araw1[15:12]==1 &&
               araw2[15:12]==2 && araw3[15:12]==3 &&
               araw0[11:0]!=0 && araw1[11:0]!=0 && araw2[11:0]!=0 && araw3[11:0]!=0 &&
               araw0[11:0]!=4095 && araw1[11:0]!=4095 && araw2[11:0]!=4095 && araw3[11:0]!=4095)
                power_adc_seen<=1;
            if(power_touch_sample)power_touch_seen<=1;
        end
    end

    // ============================================================================
    // ★★ 2026-09-23 新增：**内建在线逻辑分析仪**（不用高云 GAO 的原因见 la_capture.v 头部）
    // ----------------------------------------------------------------------------
    //   探针 16 位（高位在左）：
    //     [15] adc_cs_n   [14] adc_sclk  [13] adc_sdi   [12] adc_sdo
    //     [11] i2s_bclk   [10] i2s_lrck  [ 9] i2s_data  [ 8] i2s_sck
    //     [ 7] eng_trig   [ 6] sample_tick   [5:0] 保留 0
    //   触发 = `|opto_ev`（任一拨弦事件）⇒ 冻结后缓冲里装的正是
    //   "**造成这次事件之前 10.24us 的现场**"（512 拍 @50MHz），
    //   用来回答两个问题：
    //     ① 那几帧 ADS7950 的 SPI 到底长什么样（SDO 是不是在某个 bit 上被干扰）；
    //     ② I2S 四根线在硅片上到底有没有在翻转。
    //   读出：每帧 $O 预取 8 个样本 ⇒ 64 帧@10Hz ≈ 6.4 秒读完。
    // ============================================================================
    wire [15:0] la_probe = {adc_cs_n, adc_sclk, adc_sdi, adc_sdo,
                            i2s_bclk, i2s_lrck, i2s_data, i2s_sck,
                            eng_trig, sample_tick, 6'b0};
    wire [8:0]  la_raddr;
    wire [15:0] la_rdata, la_wp_dbg;
    wire        la_done;

    generate
    if (DEBUG_LA != 0) begin : g_debug_la
        la_capture #(.DEPTH(512)) u_la (
            .clk(clk), .rst_n(rst_n),
            .probe(la_probe), .trig(|opto_ev), .done(la_done),
            .raddr(la_raddr), .rdata(la_rdata), .wp_dbg(la_wp_dbg)
        );
    end else begin : g_no_debug_la
        assign la_done   = 1'b0;
        assign la_rdata  = 16'd0;
        assign la_wp_dbg = 16'd0;
    end
    endgenerate

    // ---- 读出侧：每帧 $O 预取 8 个样本到寄存器（BSRAM 同步读，1 拍延迟）----
    reg  [3:0]  la_ld;
    reg  [5:0]  la_idx;                       // 0..63（每帧 +1，回绕）
    reg  [15:0] la_b0, la_b1, la_b2, la_b3, la_b4, la_b5, la_b6, la_b7;
    reg         la_obusy_d;

    assign la_raddr = {la_idx, 3'd0} + {5'd0, la_ld[2:0]};

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            la_ld <= 4'd0; la_idx <= 6'd0; la_obusy_d <= 1'b0;
            la_b0 <= 16'd0; la_b1 <= 16'd0; la_b2 <= 16'd0; la_b3 <= 16'd0;
            la_b4 <= 16'd0; la_b5 <= 16'd0; la_b6 <= 16'd0; la_b7 <= 16'd0;
        end else begin
            la_obusy_d <= o_busy;
            if (o_busy && la_ld <= 4'd8) begin
                case (la_ld)
                    4'd1: la_b0 <= la_rdata;
                    4'd2: la_b1 <= la_rdata;
                    4'd3: la_b2 <= la_rdata;
                    4'd4: la_b3 <= la_rdata;
                    4'd5: la_b4 <= la_rdata;
                    4'd6: la_b5 <= la_rdata;
                    4'd7: la_b6 <= la_rdata;
                    4'd8: la_b7 <= la_rdata;
                    default: ;
                endcase
                la_ld <= la_ld + 4'd1;
            end else if (!o_busy) begin
                la_ld <= 4'd0;
            end
            if (la_obusy_d && !o_busy) la_idx <= la_idx + 6'd1;   // 发完一帧换下一组
        end
    end

    // ★★ v82：`$O` 帧的 **raw0..3 也做帧起点快照**（这是修时序违例的根因性修改）。
    //   背景：`$O` 有 48 个字段，而字段 0..3 原来直接取 `araw0..3` ⇒
    //   那个 **48 选 1 的 16 位 mux 树（≈700 个 LUT）整片挂在 `araw0..3` 这几根网线上**。
    //   而 `u_adc/raw* → u_strum` 一直是本设计**最紧的一条路径**
    //   （v81 时余量只剩 +0.217 ns），于是任何一次布局扰动都可能把它推过 20 ns：
    //     v82 实测 —— 8 条 Setup 违例、TNS −1.638 ns，
    //     违例路径正是 `u_adc/raw0_0_s0/Q → u_strum/fac0_31_s0/D`（−0.308 ns）。
    //   0x84 帧早就为**同一个原因**做过快照（见 o84_* 那段的长注释），`$O` 漏了 —— 这里补齐。
    //   副作用反而是好的：整帧的 4 个 raw 字段从此来自**同一个瞬间**，
    //   不再像以前那样"高/低字节可能来自不同 ADC 轮次"
    //   （`$O` 一帧要 ~21ms @115200，而 ADC 每 ~22µs 就出一轮）。
    reg [15:0] o_raw0, o_raw1, o_raw2, o_raw3;
    // Diagnostic frame snapshots only; audio gain path is unchanged. Break
    // power/volume -> gain arithmetic -> 48-way mux -> UART ASCII timing.
    reg [7:0] o_gain_w, o_vol_q8;

    wire [15:0] o_cur =
        (o_fld == 6'd0)  ? o_raw0 :
        (o_fld == 6'd1)  ? o_raw1 :
        (o_fld == 6'd2)  ? o_raw2 :
        (o_fld == 6'd3)  ? o_raw3 :
        (o_fld == 6'd4)  ? {4'd0, aev_pk0} :
        (o_fld == 6'd5)  ? {4'd0, aev_pk1} :
        (o_fld == 6'd6)  ? {4'd0, aev_pk2} :
        (o_fld == 6'd7)  ? {4'd0, aev_pk3} :
        (o_fld == 6'd8)  ? {12'd0, opto_ev} :
        (o_fld == 6'd9)  ? abase0 :
        (o_fld == 6'd10) ? abase1 :
        (o_fld == 6'd11) ? abase2 :
        (o_fld == 6'd12) ? abase3 :
        (o_fld == 6'd13) ? ascan_us :
        (o_fld == 6'd14) ? alast_word :
        (o_fld == 6'd15) ? aframe_cnt :      // ★ 诊断：完成的轮数（不涨=模块没跑）
        (o_fld == 6'd16) ? aaddr_err :       // 地址错次数
        (o_fld == 6'd17) ? amismatch :       // 通道错配次数
        (o_fld == 6'd18) ? {15'd0, adc_busy} : // ADC 是否一直忙
        (o_fld == 6'd19) ? spi_rxc :         // ★ ESP32 声称要发字节的次数（恒 0 = MOSI 没通）
        (o_fld == 6'd20) ? spi_dbg :         // ★ 最近一次 SPI 事务的两个字节
        (o_fld == 6'd21) ? {8'd0, es_ok_cnt} : // ★ 已校验通过的 ESP32 命令条数
        // 22..29: actual LP5860 readback, replacing the removed J5 probes.
        // Flags: bit2=config_ok, bit1=sequence_done, bit0=bus_owned.
        (o_fld == 6'd22) ? {13'd0, led_config_ok_i, led_initialized_i, led_bus_lock_i} :
        (o_fld == 6'd23) ? {8'd0, led_read_chip_en_i} :
        (o_fld == 6'd24) ? {8'd0, led_read_initial_i} :
        (o_fld == 6'd25) ? {8'd0, led_read_config3_i} :
        (o_fld == 6'd26) ? {8'd0, led_read_global_i} :
        (o_fld == 6'd27) ? {12'd0, led_mode_cfg} :
        (o_fld == 6'd28) ? {8'd0, led_level_cfg} :
        (o_fld == 6'd29) ? {12'd0, (ENABLE_FORCE != 0), eng_velocity} :
        // ★ 30..33：发声链诊断（触发→引擎→混音→I2S 四级活口），见上面的说明
        (o_fld == 6'd30) ? a_trig_cnt :      // 引擎触发次数（不涨=触发链断）
        (o_fld == 6'd31) ? {8'd0, o_vol_q8} :  // 当前音量（=0 = 被下发成静音）
        (o_fld == 6'd32) ? a_peak :          // 最近 100ms 内音频峰值（恒 0 = 引擎没出波形）
        (o_fld == 6'd33) ? {15'd0, |eng_active} : // 是否还有声部在响
        (o_fld == 6'd34) ? eng_dbg_env :     // ★ 引擎内部包络（恒 0 = 音色表空/包络没起来）
        (o_fld == 6'd35) ? {8'd0, o_gain_w} :  // ★ 实际送到引擎的 gain（=gain×vol>>8）
        // ★ 36..38：ADS7950 另外三个采样相位的帧内容（诊断 SCLK/SDO 相位是否滑了）
        (o_fld == 6'd36) ? aph1 :            // 上升沿那一拍
        (o_fld == 6'd37) ? aph2 :            // 高电平 1/4 处
        (o_fld == 6'd38) ? aph3 :            // 高电平 3/4 处
        // ★ 39..47：内建逻辑分析仪的导出（见上面 u_la 处的说明）
        //   39 = 本帧样本组的序号（0..63，每帧 +1）；la_done 用 bit15 标记是否已冻结
        // With the large LA disabled, expose the small LED command trace.
        (o_fld == 6'd39) ? (DEBUG_LA ? {9'd0, la_done, la_idx} : {8'd0,led_sent_chip_en_i[23:16]}) :
        (o_fld == 6'd40) ? (DEBUG_LA ? la_b0 : led_sent_chip_en_i[15:0]) :
        (o_fld == 6'd41) ? (DEBUG_LA ? la_b1 : {8'd0,led_sent_initial_i[23:16]}) :
        (o_fld == 6'd42) ? (DEBUG_LA ? la_b2 : led_sent_initial_i[15:0]) :
        (o_fld == 6'd43) ? la_b3 :
        (o_fld == 6'd44) ? la_b4 :
        (o_fld == 6'd45) ? la_b5 :
        (o_fld == 6'd46) ? la_b6 :
                           la_b7;

    // 取第 i 个十六进制位（i=0 是【最高位】）。
    // ★注意：`v[i*4 +: 4]` 取的是【最低】4 位，直接这么写会把整个数反过来打印
    //   （实测踩过：SCAN 22us 打成 "6100"、ADC忙 1 打成 "1000"）。所以要取 (3-i) 那组。
    function [7:0] o_nib(input [15:0] v, input [1:0] i);
        reg [5:0] bs;
        begin
            bs = {4'd0, (i ^ 2'd3)} << 2;      // (3-i)*4
            o_nib = hexc(v[bs +: 4]);
        end
    endfunction

    // 当前要发的字节（纯组合）
    // ★★ 帧的**精确**字节格式（代码复核要求写清，因为这里与"文档里的一句话描述"不一致）：
    //     实际发出去的是  $O,<f0>,<f1>,...,<f47>,\n
    //                                            ↑ **末字段后面还有一个逗号**
    //   原因：状态机在 `o_pos == 3'd4` 这一拍无条件发 ','，之后才判断"是不是末字段"并转去发 '\n'
    //   ⇒ 末字段的逗号**已经发出去了**。所以整行是「47 个字段 + 一个空字段 + 换行」。
    //   ★ 为什么**不改成** `$O,...,<f47>\n`：这个尾逗号对所有消费方都无害 ——
    //     `_probe_o2.py` / `check_opto_telemetry.py` / `ukulele_monitor.py` 都是
    //     `split(",")` 之后**主动丢掉末尾的空字段**（`if parts[-1]=="": parts.pop()`）。
    //     而 `$O` 目前是本工程**唯一**能用的整机诊断通道，在拿不到硬件验证的时段里
    //     为一个纯美观的差异去动它，风险收益不成比例。
    //   ⇒ 决定：**保持现状，把契约写准**（本节就是契约）。将来要改的话，
    //     改动点是这里 + 下面的 `2'd1` 相位跳转（末字段时要跳过逗号直接收尾），
    //     且必须同步确认三个解析脚本仍然能读。
    always @(*) begin
        case (o_ph)
            2'd0: o_byte = (o_pre == 2'd0) ? 8'h24 :           // '$'
                           (o_pre == 2'd1) ? 8'h4F :           // 'O'
                           (o_pre == 2'd2) ? 8'h2C : 8'h00;    // ','
            2'd1: o_byte = (o_pos == 3'd4) ? 8'h2C            // ','（末字段也会发）
                                           : o_nib(o_cur, o_pos[1:0]);
            default: o_byte = 8'h0A;                           // '\n'
        endcase
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            o_fld <= 6'd0; o_pos <= 3'd0; o_ph <= 2'd3; o_pre <= 2'd0;
            o_start <= 1'b0; o_busy <= 1'b0; o_timer <= 25'd0;
            o_pend <= 1'b0; tele_fa_d <= 1'b0;
            o_raw0 <= 16'd0; o_raw1 <= 16'd0; o_raw2 <= 16'd0; o_raw3 <= 16'd0;
            o_gain_w<=0;o_vol_q8<=0;
        end else begin
            o_start <= 1'b0;

            // ---- ★ 发射时机（详见上面 o_timer 处的说明）----
            tele_fa_d <= tele_frame_active;
            if (tele_fa_fall) o_pend <= 1'b1;      // 触摸帧刚结束 ⇒ 排队
            if (o_go)         o_pend <= 1'b0;      // 已经在发了 ⇒ 清排队
            //   （两条同时成立时后写的生效 ⇒ o_pend=0，正好是我们要的"刚启动"）

            if (!o_busy) begin
                if (o_go) begin
                    o_timer <= 25'd0;
                    o_fld   <= 6'd0; o_pos <= 3'd0; o_pre <= 2'd0;
                    o_ph    <= 2'd0; o_busy <= 1'b1;
                    // ★ v82：帧起点快照 raw0..3（见 o_cur 处的长注释）。
                    //   放在这里而不是别处：这一拍正是 $O 帧真正开始的时刻，
                    //   与下面 o_fld/o_ph 的初始化同拍，语义最清楚。
                    o_raw0  <= araw0; o_raw1 <= araw1;
                    o_raw2  <= araw2; o_raw3 <= araw3;
                    o_gain_w<=gain_w;o_vol_q8<=vol_q8;
                end else begin
                    o_timer <= o_timer + 25'd1;
                end
            end else if (!o_tx_busy) begin
                o_start <= 1'b1;                                   // 送当前字节
            end

            if (o_busy && o_tx_done) begin
                case (o_ph)
                    2'd0: begin                                    // "$O," 发完
                        if (o_pre == 2'd2) begin o_ph <= 2'd1; o_pos <= 3'd0; end
                        else                o_pre <= o_pre + 2'd1;
                    end
                    2'd1: begin
                        if (o_pos == 3'd4) begin                   // 字段尾逗号
                            o_pos <= 3'd0;
                            if (o_fld == O_LAST) o_ph <= 2'd2;     // 末字段 → 换行
                            else                 o_fld <= o_fld + 6'd1;
                        end else begin
                            o_pos <= o_pos + 3'd1;
                        end
                    end
                    default: begin o_busy <= 1'b0; o_ph <= 2'd3; end // '\n' 发完
                endcase
            end
        end
    end

    uart_tx #(.CLK_HZ(CLK_HZ), .BAUD(115200)) u_optx (
        .clk(clk), .rst_n(rst_n),
        .tx_start(o_start), .tx_byte(o_byte),
        .tx_out(o_tx_out), .tx_busy(o_tx_busy), .tx_done(o_tx_done));

    // ============================================================================
    // ★ 上行帧（ESP32 口 J7/J8）：0x81 状态帧（50ms）+ ★v63 0x84 光电遥测帧（100ms）
    // ----------------------------------------------------------------------------
    //   两帧**共用同一个 u_esp_tx**（绝不能给同一根 esp_tx 再例化第二个 uart_tx ⇒ 双驱动）。
    //   做法：一个"帧类型 + 字节序号"的小调度器，两种帧各发完自己的长度再放开总线。
    //   ⚠ 0x81 的**格式与 50ms 周期保持 v61 原样**（ESP32 侧已按那个格式解析）：
    //        AA | 81 | 04 | <note> <gain> <timbre> <flags> | CRC
    // ============================================================================
    // Read-only note feedback; captures the actual audio submission, not a
    // guessed fret or a winner bitmap. A/E/C/G owner maps to ADC G/E/C/A.
    reg feedback_pluck;
    reg [31:0] feedback_midi,feedback_count;
    reg feedback_origin;
    reg [3:0] feedback_auto;
    wire [7:0] feedback_count_next = (eng_string==0 ? feedback_count[7:0] : eng_string==2 ? feedback_count[15:8] : eng_string==1 ? feedback_count[23:16] : feedback_count[31:24])+8'd1;
    wire [7:0] feedback_audio_midi={2'd0,eng_note}+(eng_model==1 ? 8'd50 : 8'd60)+{4'd0,capo_applied};
    wire [1:0] envelope_next_bank=opto_any?performance_model:voice_model;
    wire [6:0] envelope_next_key={1'b0,audio_voice}+{3'd0,capo_applied}-
                                 (envelope_next_bank==1 ? 7'd10 : 7'd0);
    wire [6:0] envelope_next_address={envelope_next_bank,
        envelope_next_key<28 ? envelope_next_key[4:0] : 5'd31};
    wire [6:0] env_ram_raddr,env_ram_waddr;
    wire [7:0] env_ram_data,env_ram_q;
    wire env_ram_scan,env_ram_write,env_scan_grant,env_write_grant;
    envelope_store u_envelope (
        .clk(clk),.rst_n(rst_n),.play(audio_trig),.play_address(envelope_next_address),
        .start(envelope_commit),.operation(envelope_operation),.query(envelope_query),
        .value(envelope_new_value),.play_value(envelope_play),.ready(envelope_ready),
        .done(envelope_done),.read_value(envelope_read),.modified(envelope_bank_modified),
        .ram_raddr(env_ram_raddr),.ram_waddr(env_ram_waddr),.ram_scan(env_ram_scan),
        .ram_write(env_ram_write),.ram_data(env_ram_data),.ram_q(env_ram_q),
        .scan_grant(env_scan_grant),.write_grant(env_write_grant));
    // Use exactly the same note/owner gate as real-audio BLE feedback.
    // Do not light LEDs for touch-only glide, rejected or queued events.
    wire [5:0] led_audio_open=glide_owner_string==3 ? 6'd7 : glide_owner_string==1 ? 6'd4 : glide_owner_string==2 ? 6'd0 : 6'd9;
    wire [5:0] led_fret_delta=eng_note-(eng_model==1?6'd10:6'd0)-led_audio_open;
    wire [5:0] led_physical_fret=led_fret_delta+{2'd0,capo_applied};
    // Event frets are physical already; song guide frets remain capo-relative.
    assign led_audio_fret=led_fret_delta==0 ? 5'd0 : led_physical_fret<=18 ? led_physical_fret[4:0] : 5'd31;
    assign led_audio_events=feedback_pluck && eng_trig ?
        (glide_owner_string==3 ? 4'b0001 : glide_owner_string==1 ? 4'b0010 : glide_owner_string==2 ? 4'b0100 : 4'b1000) : 4'd0;
    always @(posedge clk or negedge rst_n) begin
      if(!rst_n) begin feedback_pluck<=0;feedback_origin<=0;feedback_auto<=0;feedback_midi<=32'hffffffff;feedback_count<=0;end
      else begin
        feedback_pluck<=opto_any;
        feedback_origin<=auto_fire;
        if((feedback_pluck || feedback_origin) && eng_trig && eng_string<4) begin
          case(eng_string[1:0])
            2'd0:begin feedback_auto[0]<=feedback_origin;feedback_midi[7:0]<=feedback_audio_midi;feedback_count[7:0]<=feedback_count_next;end
            2'd2:begin feedback_auto[1]<=feedback_origin;feedback_midi[15:8]<=feedback_audio_midi;feedback_count[15:8]<=feedback_count_next;end
            2'd1:begin feedback_auto[2]<=feedback_origin;feedback_midi[23:16]<=feedback_audio_midi;feedback_count[23:16]<=feedback_count_next;end
            2'd3:begin feedback_auto[3]<=feedback_origin;feedback_midi[31:24]<=feedback_audio_midi;feedback_count[31:24]<=feedback_count_next;end
          endcase
        end
      end
    end
    // ---- 0x81 状态帧的载荷 ----
    //   ★ v64：LEN 4 → **5**，新增第 5 字节 `cmd_cnt`（收到并校验通过的 ESP32 命令数）。
    //   为什么加它：接上 J7/J8 后，"FPGA → ESP32"这一向立刻能从 0x81 帧看出来 ✓，
    //   但"ESP32 → FPGA"这一向**没法验** —— 音量/音色的效果都要出声才听得见，
    //   而触摸板没接、光电板又坏了两路 ⇒ 根本不出声 ⇒ 命令到没到完全看不出来。
    //   有了这个计数器，串口/屏幕上就能直接看到"命令收到了几条"，一眼判断方向通不通。
    //   ⚠ 向后兼容：ESP32 侧是 `cmd == 0x81 && len >= 4` ⇒ LEN=5 照样能收，多出的字节已同步用起来。
    wire [7:0] st_note  = {2'd0, note_voice};                    // 音库号 0..32
    wire [7:0] st_gain  = gain_final;
    wire [7:0] st_flags = {6'd0, any_gliding, 1'b0};             // bit1=滑音中
    wire [7:0] st_ccnt  = es_ok_cnt;                             // ★ 已收到的命令条数（8 位）

    // ★★ v81 修（代码复核发现的真 bug）：0x81 的**载荷与 CRC 必须同源**。
    //   原来 CRC 是**组合地**从上面的实时值算出来的：
    //       wire st_crc = 8'h81 ^ 8'd5 ^ st_note ^ st_gain ^ esp_timbre ^ st_flags ^ st_ccnt;
    //   而载荷字节是"发到第几号字节就取当时的实时值"。这个帧**可能被暂停**
    //   （推字节的握手是 `st_start = st_active && esp_tx_ready`，FIFO 满了就停），
    //   暂停期间 `note_voice`（拨弦/触摸随时在变）、`st_gain`、`st_flags`、`st_ccnt` 都可能变
    //   ⇒ 前面几个字节是**旧值**、最后那个 CRC 是**新值算的** ⇒ **ESP32 侧整帧 CRC 校验失败、
    //     被当成坏帧丢掉**（`gvlink.cpp` 里 `errFrames++`）。表现为状态帧"偶尔丢失"。
    //   0x84 帧早就为此做了快照（见下面 o84_* 的注释），0x81 漏了 —— 这里补齐：
    //   在**帧起点那一拍**把 5 个字段锁存，载荷 mux 和 CRC 全部改用快照值。
    reg [7:0] st_note_s, st_gain_s, st_timbre_s, st_flags_s, st_ccnt_s;
    wire [7:0] st_crc = 8'h81 ^ 8'd5 ^ st_note_s ^ st_gain_s
                      ^ st_timbre_s ^ st_flags_s ^ st_ccnt_s;

    // ---- ★ v63：0x84 OPTEL 光电遥测帧 ----------------------------------------
    //   为什么需要：PC 口那套文本帧 "$O,RAW0,..." 走的是 `assign tx = o_busy ? ...`
    //   ⇒ 出到 **A1/E3（PC 口）**；而 ESP32 挂在 **J7/J8（esp_tx）** ⇒ ESP32/串口屏
    //   **收不到**光电数据，功能④（光电校正）的界面就画不出来。本帧走 esp_tx。
    //
    //   帧格式（共 21 字节，与屏幕侧设计一致）：
    //     AA | 84 | 11 | payload[17] | CRC              CRC = 84 ^ 11 ^ payload 各字节（异或）
    //     payload = raw0(2B) raw1(2B) raw2(2B) raw3(2B)   ADC 原始值，**小端**（低字节在前）
    //             + dg0(2B)  dg1(2B)  dg2(2B)  dg3(2B)   增益后凹坑，小端
    //             + ev(1B)                              拨弦事件位图 bit0..3 = 四路事件
    //   周期 100 ms（10 Hz）：0x81 仍是 50 ms，两帧先后占用链路（21B@115200 ≈ 1.8 ms）。
    //   ★ raw 的位含义：`araw0..3` 是 ads7950_spi 的**16 位回传字**，
    //     `[15:12] = 通道地址、[11:0] = 12 位码值`（见 ads7950_spi.v:43-44）。
    //     这里**原样发送**（与 PC 口 $O 帧里的 RAWn 字段完全一致，方便两边对照校准）；
    //     屏幕要 12 位码值就取 `raw & 0x0FFF`，高 4 位可顺手校验通道有没有错配。
    //
    //   ★★ 语义确认（与设计稿的字面公式不同，**以本段为准**）★★
    //     设计稿写的是"增益后凹坑 = (adip × gain) >> 6"。**这个乘法 strum_detect 内部
    //     已经做过了**，证据（strum_detect.v）：
    //         :293  wire signed [25:0] gmul0 = e0 * $signed({1'b0, r_g0});
    //         :304  sg3_0 <= (gmul0 >>> 6 > 26'sd131071) ? 18'sd131071 : (gmul0 >>> 6);
    //         :446  d0 <= wx0[15:0];          // wx0 = r_en[0] ? sg3_0 : 0
    //     其中 r_g0 就是顶层接进去的 o_g0（`.cfg_gain0(o_g0)`，且 cfg_valid=1），
    //     e0 = dip − 共模。⇒ **adip0..3（= d0..d3）本身就是"增益后凹坑"**，
    //     再乘一次 gain 属于**重复施加增益**（gain=78 会放大到 1.49 倍、gain=48 缩到 0.56 倍）。
    //     所以这里 dg 只做一件事：**负值钳 0**（信号高于基线时 e<0 ⇒ adip<0，凹坑不该为负）。
    //     好处：dg 的量纲与 strum_detect 拿去比阈值的量**完全一致**（sg3 vs o_th*），
    //     屏幕上可以直接把 dg 与阈值 th 画在同一条刻度上。
    //     ⚠ 若屏幕侧确实要"再乘一次 gain"，那是改一行的事，但按语义判断**设计稿写错了**。
    // ----------------------------------------------------------------------------
    wire [15:0] dg0 = adip0[15] ? 16'd0 : adip0[15:0];   // 负 ⇒ 0（adip 是有符号 16bit）
    wire [15:0] dg1 = adip1[15] ? 16'd0 : adip1[15:0];
    wire [15:0] dg2 = adip2[15] ? 16'd0 : adip2[15:0];
    wire [15:0] dg3 = adip3[15] ? 16'd0 : adip3[15:0];

    // ---- ★ 帧起点快照：开始发 0x84 的**那一拍**把 raw/dg/ev 一起锁进寄存器 ----
    //   为什么要快照（而不是让 21 个字节直取实时值）：
    //     ① **数据自洽**：一帧要 1.8ms（21B@115200），而 ADC 每 ~22µs 就出一轮新数据。
    //        直取实时值时，同一个 16bit 字段的高/低字节会来自**不同的 ADC 轮次**，
    //        拼出来的数会偶尔跳一下（虽然 12 位码值只差几 LSB，但没必要）
    //        —— 快照后整帧 17 个载荷字节来自同一个瞬间。
    //     ② **别拖累本工程最紧的路径**：`araw0..3` 是 `u_adc → u_strum`（Setup 余量一直
    //        只有 0.3~0.9ns，是长期的最差路径）。若载荷 mux + CRC 异或树直接挂在 raw 上，
    //        就是给这条最紧的网线又加一大片扇出（实测：本帧加进去后最差 Setup 从 +0.469
    //        掉到 +0.289ns）。快照后 ADC 只多驱动 16 个触发器，不再驱动组合树。
    //   ★★ v81 修（代码复核发现的真 bug）：**raw 必须掩成 12 位再发**。
    //     `ads7950_spi.v` 自己写着（第 43 行）：
    //         "每通道最近一次的原始 16 位回传（**[15:12]=地址**，[11:0]=码值）"
    //     而 `raw0 <= sh_in` 存的是**整字**。原来这里直接快照 `araw0..3` ⇒ 发到屏幕上的
    //     `vra0..3` 是"**地址半字节 + 码值**"：
    //         ch0 → 0x0xxx（看起来正常，因为地址是 0 ⇒ 一直没被发现）
    //         ch1 → 4096+码值、ch2 → 8192+码值、ch3 → 12288+码值
    //     ⇒ 屏幕上四路原始值**差着整数千**，会被误判成"光电板四路灵敏度不一致"。
    //     （PC 上位机的解析器一直用 `& 0xFFF` 掩着，所以 PC 那边看不出来 —— 典型的
    //       "一个量两套口径，谁掩谁不掩各写一遍"。）
    //     掩在这里而不是 ESP32 侧：让帧里字段的含义（"12 位码值"）真的成立，
    //     只留一个真相。地址半字节的诊断仍在 `$O` 帧的 LASTW 字段里（那里不掩）。

    // ★ ev 必须**锁存到发走为止**，不能只取当拍：
    //   strum_detect 的 `ev0..3` 是 **1 拍宽（20 ns）的脉冲**（strum_detect.v:441-444）。
    //   10 Hz 采样直接取当拍的话命中概率 ≈ 20ns/100ms = 2e-7 ⇒ 屏幕上 ev **永远显示 0**（等于没有）。
    //   所以：事件来了就置位 → 帧发出去的那一拍连同快照一起清零
    //   ⇒ 帧里的 ev = "**最近 100 ms 内拨过弦的那些通道**"，正好配屏幕 10 Hz 的刷新。
    //   ⚠ 这是对规格里"事件位图"的语义解释（规格没写清是瞬时还是累计），已写进文档 §10.3。
    reg [3:0]  o84_evl;

    // CRC 与载荷 mux **都取快照**（必须一致：CRC 要正好覆盖实际发出的那些字节）


    reg  [21:0] st_timer;                                        // 50MHz → 50ms（自由运行）
    // ★ v80：st_idx 从 5 位扩到 **6 位**。原来 5 位够用（0x84 最长 21 字节，下标 0..20），
    //   新增的 0x86 参数回读帧是 34 字节（下标 0..33），5 位最大只能到 31
    //   ⇒ 不改位宽的话第 32/33 号字节会被截成 0/1，帧尾错乱。
    //   （本工程在"位宽不够"上已经栽过 4 次：O_LAST、a_peak_tmr、o_timer、QUIET_N，
    //     规则是：**任何计数器/下标，位宽必须 >= 它能取到的最大值**。）
    reg  [5:0]  st_idx;         // 字节序号：0x81 用 0..8、0x84 用 0..20、0x86 用 0..33
    reg         st_active;
    reg  [7:0]  st_byte;
    // ★ st_start 必须是【组合】信号，不能是寄存器脉冲 —— 这是踩过的坑：
    //   原来写 `st_start <= 1'b1;` 与 `st_idx <= st_idx + 1;` 在同一个分支里，
    //   而 st_byte 是随 st_idx 变化的组合逻辑。非阻塞赋值下 st_start 要到【下一拍】
    //   才有效，那一拍 st_idx 已经 +1 了 ⇒ FIFO 采到的是【下一个下标】的字节：
    //     · 每帧的 0xAA 永远发不出去（被跳过）→ 主机侧完全无法分帧（找不到帧头）
    //     · 帧尾多推一个字节（default 分支 = CRC）→ 实测字节流 `81 05 ... 04 EF EF`
    //   实测证据（2026-09-23，ESP32 的 w 自检原始转储）：
    //     期望 AA 81 05 00 6F 00 00 04 EF，实际收到 81 05 00 6F 00 00 04 EF EF
    //     （CRC 校验本身是对的，只是整体错位一格）
    //   ⇒ 用 `assign st_start = st_active && esp_tx_ready;`：与 st_byte 同拍，
    //     且 st_idx 只在 esp_tx_ready 的分支里推进，两者严格一一对应。
    reg  [3:0]  st_kind;
    wire        st_start = st_active && esp_tx_ready && (st_kind!=6 || analysis_valid);
    audio_analyzer u_audio_analyzer(.clk(clk),.rst_n(rst_n),.enable(power_events),
        .request(analysis_request),.token(analysis_token),.scope_only(analysis_scope),.sample_tick(sample_tick),.sample(sample_mix),
        .packet_ready(analysis_ready),.byte_valid(analysis_valid),.byte_out(analysis_byte),
        .take_byte(st_start && st_kind==6),.busy(analysis_busy),
        .env_play(audio_trig),.env_scan(env_ram_scan),.env_write(env_ram_write),
        .env_raddr(env_ram_raddr),.env_waddr(env_ram_waddr),.env_data(env_ram_data),.env_q(env_ram_q),
        .env_scan_grant(env_scan_grant),.env_write_grant(env_write_grant));
    // ★ st_kind：0 = 0x81 状态帧，1 = 0x84 光电遥测，2 = 0x86 参数回读，3 = 0x87 波形
    reg st_pend88,power_getack;
    reg st_pend89,gesture_getack;
    reg st_pend8a,touch_getack;
    reg st_pend8c,led_diag_ack;
    reg st_pend8d,fx_getack;
    reg st_pend8f,profile_ack;
    reg capo_ack,st_pend90,model_ack,st_pend91,chain_ack,st_pend92;
    reg         st_div2;                                         // 50ms 节拍的 2 分频 ⇒ 0x84 每 100ms
    reg         st_pend81, st_pend84;                            // 待发请求（发完才清）
    reg         st_pend86;                                       // ★ v80：0x86 参数回读（按需）

    // ---- ★ 0x87：低资源实时波形串流 -----------------------------------------
    // sample_mix 已经是送入 I2S 的最终 16-bit 时域样本。这里每 16 个音频
    // 样本取 1 个高字节，组成 24 点的小帧；ESP32 再拼接成 128 点窗口做 FFT。
    // 双 24x8 缓冲避免 SPI 发帧时覆盖正在发送的样本，不使用 BSRAM/DSP。
    localparam integer WAVE_SAMPLES = 24;
    localparam integer WAVE_DECIM   = 16;
    localparam integer WAVE_PAY_LEN = WAVE_SAMPLES + 2; // seq, decim, samples
    localparam integer WAVE_LAST    = WAVE_PAY_LEN + 3; // AA/CMD/LEN + payload + CRC - 1
    reg [7:0] wave_buf0 [0:WAVE_SAMPLES-1];
    reg [7:0] wave_buf1 [0:WAVE_SAMPLES-1];
    // Read the locked full capture bank directly; no third register copy.
    reg [7:0] wave_xor0, wave_xor1;
    reg [7:0] wave_seq_counter, wave_seq0, wave_seq1, wave_seq_s, wave_crc_s;
    reg       wave_full0, wave_full1, wave_fill_bank, wave_tx_bank;
    reg [4:0] wave_cap_idx;
    reg [3:0] wave_decim_cnt;
    integer wave_i;

    // ---- ★ v80：0x86 参数回读帧的 30 字节载荷（按序号取，纯组合函数）----
    //   布局（全部小端）：
    //     0..3   th0..th3    各 2 字节   = 8
    //     4..7   g0..g3      各 1 字节   = 4
    //     8      slow   3 字节    9  fast   3 字节    10 cm_cap 3 字节
    //     11     margin 1 字节    12 persist 1 字节
    //     13     cool   2 字节    14 blank  3 字节    15 rearm  2 字节   = 18
    //   合计 30 ⇒ LEN=30，整帧 34 字节。
    //   ★ 为什么不像 0x84 那样直取实时值：这些量全是**寄存器**（o_th*/o_g*/o_glob*），
    //     本来就是稳态的，不需要快照，也不会给 ADC 那条最紧的路径加扇出。
    reg [399:0] o86_snapshot;
    reg [7:0] o86_crc;
    wire [7:0] o84_crc=o86_crc;
    reg [3:0] diag_detect,diag_accept,diag_emit;
    function [7:0] o86_pay(input [5:0] i);
      begin o86_pay=o86_snapshot[i*8+:8];end
    endfunction

    function [7:0] wave_pay(input [5:0] i);
        begin
            if (i == 6'd0) wave_pay = wave_seq_s;
            else if (i == 6'd1) wave_pay = WAVE_DECIM;
            else wave_pay = wave_tx_bank ? wave_buf1[i - 6'd2] : wave_buf0[i - 6'd2];
        end
    endfunction

    // 字节选择：按当前帧类型 + 序号取
    wire shared_packet=(st_kind==1 || st_kind==2 || (st_kind>=4 && st_kind<=14 && st_kind!=6));
    reg [7:0] packet_command;reg [5:0] packet_length;
    wire [5:0] packet_last=packet_length+6'd3;
    wire [5:0] packet_index=st_idx-6'd3;
    wire [7:0] packet_payload=o86_snapshot[packet_index*8+:8];
    always @(*)begin
      case(st_kind)
        1:begin packet_command=8'h84;packet_length=30;end
        2:begin packet_command=8'h86;packet_length=50;end
        4:begin packet_command=8'h88;packet_length=16;end
        5:begin packet_command=8'h89;packet_length=4;end
        7:begin packet_command=8'h8A;packet_length=10;end
        8:begin packet_command=8'h8C;packet_length=16;end
        9:begin packet_command=8'h8D;packet_length=8;end
        10:begin packet_command=8'h8E;packet_length=o86_snapshot[7:0]==2 ? 20:12;end
        12:begin packet_command=8'h90;packet_length=4;end
        13:begin packet_command=8'h91;packet_length=12;end
        14:begin packet_command=8'h92;packet_length=12;end
        default:begin packet_command=8'h8F;packet_length=16;end
      endcase
    end
    always @(*) begin
        if(shared_packet)begin
            if(st_idx==0)st_byte=8'hAA;
            else if(st_idx==1)st_byte=packet_command;
            else if(st_idx==2)st_byte={2'd0,packet_length};
            else if(st_idx==packet_last)st_byte=o86_crc;
            else st_byte=packet_payload;
        end else case(st_kind)
            3'd6:st_byte=analysis_byte;
            2'd0: begin                                          // ---- 0x81（9 字节）----
                case (st_idx)
                    6'd0: st_byte = 8'hAA;
                    6'd1: st_byte = 8'h81;
                    6'd2: st_byte = 8'd5;                        // LEN=5（原为 4）
                    // ★ v81：一律用**帧起点快照**，不要用实时值 —— 必须与 st_crc 同源
                    6'd3: st_byte = st_note_s;
                    6'd4: st_byte = st_gain_s;
                    6'd5: st_byte = st_timbre_s;
                    6'd6: st_byte = st_flags_s;
                    6'd7: st_byte = st_ccnt_s;                   // ★ 已收到的命令条数
                    default: st_byte = st_crc;
                endcase
            end
            default: begin                                       // ---- 0x87（30 字节）----
                case (st_idx)
                    6'd0: st_byte = 8'hAA;
                    6'd1: st_byte = 8'h87;
                    6'd2: st_byte = WAVE_PAY_LEN;
                    6'd29: st_byte = wave_crc_s;
                    default: st_byte = wave_pay(st_idx-6'd3);
                endcase
            end
        endcase
    end

    // 50ms 节拍 + 发送状态机（★ 必须在**同一个 always 块**里：st_pend81/84/86 既被节拍置位、
    //   又被发送逻辑清零，拆成两个 always 就是"一个 reg 两个驱动"，综合会直接报错/出垃圾）
    //   —— 节拍是【自由运行】的（不因为在发帧而暂停）⇒ 0x81 周期严格 50ms，不被 0x84 拖长
    //   ★ v80/v83 优先级：0x86（按需参数回读）> 0x81（周期状态）> 0x84（100ms 光电遥测）。
    //     回读是屏幕一次操作的闭环确认，必须先于周期刷新；否则在 0x81/0x84
    //     刚好排队时会把回读拖到超时，表现为屏幕写入“偶尔有效”。
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            chain_ack<=0;st_pend92<=0;model_ack<=0;st_pend91<=0;st_pend90<=0;capo_ack<=0;st_pend8d<=0;fx_getack<=0;st_pend8f<=0;profile_ack<=0;
            touch_diag_ack<=0;
            st_pend88<=0;power_getack<=0;st_pend89<=0;gesture_getack<=0;st_pend8a<=0;touch_getack<=0;st_pend8c<=0;led_diag_ack<=0;
            st_timer <= 22'd0; st_div2 <= 1'b0; st_pend81 <= 1'b0; st_pend84 <= 1'b0;
            st_pend86 <= 1'b0;
            o86_snapshot<=0;o86_crc<=0;diag_detect<=0;diag_accept<=0;diag_emit<=0;
            st_idx <= 6'd0; st_active <= 1'b0; st_kind <= 2'd0;
            o_getack <= 1'b0;
            wave_xor0 <= 8'd0; wave_xor1 <= 8'd0;
            wave_seq_counter <= 8'd0; wave_seq0 <= 8'd0; wave_seq1 <= 8'd0;
            wave_seq_s <= 8'd0; wave_crc_s <= 8'd0;
            wave_full0 <= 1'b0; wave_full1 <= 1'b0;
            wave_fill_bank <= 1'b0; wave_tx_bank <= 1'b0;
            wave_cap_idx <= 5'd0; wave_decim_cnt <= 4'd0;
            // ★ v81：0x81 帧起点快照（复位时给 0，避免 X 传播到 CRC）
            st_note_s <= 8'd0; st_gain_s <= 8'd0; st_timbre_s <= 8'd0;
            st_flags_s <= 8'd0; st_ccnt_s <= 8'd0;
            o84_evl  <= 4'd0;
        end else begin

            // ---- 波形抽取：只观察最终音频样本，不进入触摸/合成关键路径 ----
            // sample_tick 约 48.8 kHz；16 倍抽取后约 3.05 kS/s，
            // 足够覆盖尤克里里基频和低阶泛音，也让 ESP32 的 128 点 FFT
            // 获得约 42 ms 的时间窗。
            if (sample_tick && power_events) begin
                if (wave_decim_cnt == WAVE_DECIM - 1) begin
                    wave_decim_cnt <= 4'd0;
                    // 只有当前采集缓冲未封存时才写入；双缓冲满时丢弃一个
                    // 波形块，但绝不阻塞音频或触摸时序。
                    if (!wave_fill_bank && !wave_full0) begin
                        wave_buf0[wave_cap_idx] <= sample_mix[15:8];
                        wave_xor0 <= wave_xor0 ^ sample_mix[15:8];
                    end else if (wave_fill_bank && !wave_full1) begin
                        wave_buf1[wave_cap_idx] <= sample_mix[15:8];
                        wave_xor1 <= wave_xor1 ^ sample_mix[15:8];
                    end
                    if (wave_cap_idx == WAVE_SAMPLES - 1) begin
                        if (!wave_fill_bank && !wave_full0) begin
                            wave_full0 <= 1'b1;
                            wave_seq0  <= wave_seq_counter;
                            wave_seq_counter <= wave_seq_counter + 8'd1;
                            wave_cap_idx <= 5'd0;
                            // 另一块可用时切换；发送器不会覆盖它。
                            if (!wave_full1) wave_fill_bank <= 1'b1;
                        end else if (wave_fill_bank && !wave_full1) begin
                            wave_full1 <= 1'b1;
                            wave_seq1  <= wave_seq_counter;
                            wave_seq_counter <= wave_seq_counter + 8'd1;
                            wave_cap_idx <= 5'd0;
                            if (!wave_full0) wave_fill_bank <= 1'b0;
                        end
                    end else if ((!wave_fill_bank && !wave_full0) ||
                                 (wave_fill_bank && !wave_full1)) begin
                        wave_cap_idx <= wave_cap_idx + 5'd1;
                    end
                end else begin
                    wave_decim_cnt <= wave_decim_cnt + 4'd1;
                end
            end

            // ---- 50ms 节拍：每 50ms 请求一帧 0x81；隔一次再请求一帧 0x84（= 100ms）----
            if (st_timer >= 22'd2_500_000) begin
                st_timer  <= 22'd0;
                st_pend81 <= 1'b1;
                if (st_div2 && power_events) st_pend84 <= 1'b1;
                st_div2   <= ~st_div2;
            end else begin
                st_timer <= st_timer + 22'd1;
            end

            // ---- ★ v80：0x0B 请求 → 0x86 帧的请求/确认握手 ----
            //   两个 always 块各拥有一个 reg（o_getreq 归命令解析块、o_getack 归本块），
            //   避免"一个 reg 两个驱动"。取走请求后 o_getack 保持到对方清掉 o_getreq 为止。
            if(led_diag_req!=led_diag_ack)begin led_diag_ack<=led_diag_req;st_pend8c<=1;end
            if(o_getreq != o_getack)begin o_getack<=o_getreq;st_pend86<=1;end

            // ---- 发送：空闲时按优先级取一个待发请求 ----
            if (!st_active) begin
                if(st_pend88)begin
                    st_idx<=0;st_kind<=4;st_active<=1;st_pend88<=0;
                    o86_snapshot[127:0]<={8'd0,8'h0F,power_elapsed,
                        8'd0,{5'd0,!led_config_ok_i,!power_touch_seen,!power_adc_seen},
                        8'd0,{3'd0,!engine_busy,power_led_asleep,power_touch_asleep,!adc_busy,!power_clock_gate},
                        power_epoch,power_id,8'd0,power_result,power_state,8'd1};
                    o86_crc<=8'h88 ^ 8'd16;
                end else if(st_pend8f)begin
                    st_idx<=0;st_kind<=11;st_active<=1;st_pend8f<=0;
                    o86_snapshot[127:0]<={profile_query_token,profile_status};o86_crc<=8'h8F ^ 8'd16;
                end else if(st_pend92)begin
                    st_idx<=0;st_kind<=14;st_active<=1;st_pend92<=0;
                    o86_snapshot[95:0]<={{7'd0,chain_next_armed},{4'd0,fx_overrun,chain_settled,chain_active,power_compute},
                        {4'd0,chain_actual_mask},chain_token,{5'd0,chain_target},
                        {1'b0,chain_strengths[27:21]},{1'b0,chain_strengths[20:14]},
                        {1'b0,chain_strengths[13:7]},{1'b0,chain_strengths[6:0]},
                        {4'd0,chain_mask},{7'd0,chain_enable},8'd1};o86_crc<=8'h92^8'd12;
                end else if(st_pend91)begin
                    st_idx<=0;st_kind<=13;st_active<=1;st_pend91<=0;
                    o86_snapshot[95:0]<={4'd0,envelope_bank_modified,envelope_token,envelope_read,{3'd0,envelope_query[4:0]},{6'd0,envelope_query[6:5]},8'd7,model_token,{6'd0,voice_model},8'd4};o86_crc<=8'h91^8'd12;
                end else if(st_pend90 && capo_applied==capo_requested)begin
                    st_idx<=0;st_kind<=12;st_active<=1;st_pend90<=0;
                    o86_snapshot[31:0]<={8'd12,capo_token,{4'd0,capo_applied},8'd3};
                    o86_crc<=8'h90 ^ 8'd4;
                end else if(st_pend8d)begin
                    st_idx<=0;st_kind<=9;st_active<=1;st_pend8d<=0;
                    o86_snapshot[63:0]<={fx_token,{4'd0,fx_overrun,fx_settled,fx_ready,power_compute},
                        {1'b0,fx_gain},{5'd0,fx_actual_mode},{1'b0,fx_strength},{5'd0,fx_mode},8'd3};
                    o86_crc<=8'h8D ^ 8'd8;
                end else if(st_pend8c)begin
                    st_idx<=0;st_kind<=8;st_active<=1;st_pend8c<=0;
                    o86_snapshot[127:0]<={8'd0,led_last_fault,led_read_crc,led_write_crc,{6'd0,led_bad_col},
                        led_pwm_errors,led_control_errors,led_read_global_i,led_read_config3_i,led_read_initial_i,
                        led_read_chip_en_i,led_diag_flags,led_diag_token,8'd1};
                    o86_crc<=8'h8C ^ 8'd16;
                end else if(touch_diag_req!=touch_diag_ack)begin
                    // Reserve a reply slot until the next selected pad result.
                    // Audio and sensor scanning continue independently.
                    if(!touch_config_ready || (touch_diag_valid && touch_diag_ch==touch_diag_channel))begin
                        touch_diag_ack<=touch_diag_req;st_idx<=0;st_kind<=10;st_active<=1;
                        o86_snapshot[159:96]<={
                            6'd0,voice_model,touch_vib_threshold,touch_vib_amp,
                            6'd0,glide_owner_string,
                            any_gliding,glide_owner_valid,vib_env_hi,audio_vib_on,touch_vib_str,
                            5'd0,gesture_mask};
                        o86_snapshot[95:0]<={{7'd0,touch_config_ready},touch_diag_ids,
                            touch_diag_data[31:16],touch_diag_data[15:0],touch_diag_data[51:36],
                            {touch_config_ready,3'd0,touch_diag_data[35:32]},touch_diag_token,{1'b0,touch_diag_channel},(touch_diag_modern ? 8'd2 : 8'd1)};
                        o86_crc<=8'h8E ^ (touch_diag_modern ? 8'd20 : 8'd12);
                    end
                end else if(st_pend8a)begin
                    st_idx<=0;st_kind<=7;st_active<=1;st_pend8a<=0;
                    o86_snapshot[79:0]<={8'd0,{5'd0,touch_rate_fault,touch_rate_busy,touch_config_ready},touch_token,{2'd0,touch_grace_ms},{7'd0,touch_rate_actual},{7'd0,touch_rate_fast},touch_vib_threshold,8'd1};
                    o86_crc<=8'h8A ^ 8'd10;
                end else if(st_pend89)begin
                    st_idx<=0;st_kind<=5;st_active<=1;st_pend89<=0;
                    o86_snapshot[31:0]<={(GV_PITCH ? 8'd7 : 8'd0),gesture_token,(GV_PITCH ? {5'd0,gesture_mask} : 8'd0),8'd2};
                    o86_crc<=8'h89 ^ 8'd4;
                end else if (st_pend86) begin
                    st_idx    <= 6'd0;
                    st_kind   <= 2'd2;
                    o86_snapshot<={{4'd0,performance_peak[11:8]},performance_peak[7:0],performance_accepted[15:8],performance_accepted[7:0],performance_emitted[15:8],performance_emitted[7:0],performance_detected[15:8],performance_detected[7:0],performance_overflow[15:8],performance_overflow[7:0],{4'd0,performance_depth},{4'd0,diag_emit},{4'd0,diag_accept},{4'd0,diag_detect},o_get_token,8'd2,{7'd0,performance_poly},{4'd0,o_first_lock[19:16]},o_first_lock[15:8],o_first_lock[7:0],o_rearm[15:8],o_rearm[7:0],{4'd0, o_blank[19:16]},o_blank[15:8],o_blank[7:0],o_cool[15:8],o_cool[7:0],o_persist,o_margin,{6'd0, o_cmcap[17:16]},o_cmcap[15:8],o_cmcap[7:0],{4'd0, o_fast[19:16]},o_fast[15:8],o_fast[7:0],{4'd0, o_slow[19:16]},o_slow[15:8],o_slow[7:0],o_g3,o_g2,o_g1,o_g0,{4'd0, o_th3[11:8]},o_th3[7:0],{4'd0, o_th2[11:8]},o_th2[7:0],{4'd0, o_th1[11:8]},o_th1[7:0],{4'd0, o_th0[11:8]},o_th0[7:0]};
                    o86_crc<=8'h86 ^ 8'd50;
                    diag_detect<=opto_ev;diag_accept<=opto_ev_first;diag_emit<=opto_any ? (4'b1<<performance_channel) : 4'd0;
                    st_active <= 1'b1;
                    st_pend86 <= 1'b0;
                end else if (st_pend81) begin
                    st_idx    <= 6'd0;
                    st_kind   <= 2'd0;
                    st_active <= 1'b1;
                    st_pend81 <= 1'b0;
                    // ★ v81：0x81 的帧起点快照（载荷与 CRC 必须取自同一份值，见 st_crc 处的注释）
                    st_note_s   <= st_note;
                    st_gain_s   <= st_gain;
                    st_timbre_s <= esp_timbre;
                    st_flags_s  <= st_flags;
                    st_ccnt_s   <= st_ccnt;
                end else if (st_pend84) begin
                    o86_crc<=8'h84 ^ 8'd30;
                    // All framed replies share one immutable packet snapshot.
                    // No duplicate optical/fret/MIDI/count register bank is needed.
                    o86_snapshot[239:0]<={feedback_count,feedback_midi,
                        {3'd0,touch_fret_held[4:0]},{3'd0,touch_fret_held[14:10]},
                        {3'd0,touch_fret_held[9:5]},{3'd0,touch_fret_held[19:15]},{feedback_auto,4'd2},
                        {4'd0,(o84_evl | (opto_any ? (4'b1<<performance_channel) : 4'd0))},
                        dg3,dg2,dg1,dg0,{4'd0,araw3[11:0]},{4'd0,araw2[11:0]},
                        {4'd0,araw1[11:0]},{4'd0,araw0[11:0]}};
                    st_idx<=0;st_kind<=1;st_active<=1;st_pend84<=0;
                     o84_evl  <= 4'd0;                // 清锁存（本帧已把它带走）
                end else if (analysis_ready) begin
                    st_idx<=0;st_kind<=6;st_active<=1;
                end else if (wave_full0 || wave_full1) begin
                    // 波形最低优先级：只有周期状态/光电帧都发完后才占用 FIFO。
                    st_idx    <= 6'd0;
                    st_kind   <= 2'd3;
                    st_active <= 1'b1;
                    if (wave_full0) begin
                        wave_tx_bank <= 1'b0;
                        wave_seq_s   <= wave_seq0;
                        wave_crc_s   <= 8'h87 ^ WAVE_PAY_LEN ^ wave_seq0 ^ 8'd16 ^ wave_xor0;
                    end else begin
                        wave_tx_bank <= 1'b1;
                        wave_seq_s   <= wave_seq1;
                        wave_crc_s   <= 8'h87 ^ WAVE_PAY_LEN ^ wave_seq1 ^ 8'd16 ^ wave_xor1;
                    end
                end
            end else if (st_start) begin
                if(shared_packet && st_idx>=3 && st_idx<packet_last)o86_crc<=o86_crc ^ st_byte;
                // ★ SPI 版：FIFO 有空位就【当拍】把 st_byte 推进去并推进下标。
                //   推字节的握手信号是组合的 st_start（见上面的声明处注释），
                //   所以 "st_start 有效的那一拍 st_idx 是多少，FIFO 就收到第几号字节"。
                //   UART 版是"等 tx_busy 落下再送、等 tx_done 再推进"，
                //   换成 FIFO 后握手简化为"接受即完成"，深度16按ready背压分段发送，帧长度不受FIFO深度限制
                //   （0x86参数帧54字节，逐字节发送，不能依赖整帧驻留）。
                if(shared_packet)begin
                    if(st_idx==packet_last)st_active<=0;else st_idx<=st_idx+1'b1;
                end else case(st_kind)
                    3'd6:if(st_idx==23)st_active<=0;else st_idx<=st_idx+1'b1;
                    2'd0: begin                                  // 0x81 共 9 字节（下标 0..8）
                        if (st_idx == 6'd8) st_active <= 1'b0;
                        else                st_idx    <= st_idx + 6'd1;
                    end
                    default: begin                               // 0x87 共 30 字节（下标 0..29）
                        if (st_idx == WAVE_LAST) begin
                            st_active <= 1'b0;
                            if (!wave_tx_bank) begin
                                wave_full0 <= 1'b0;
                                wave_xor0  <= 8'd0;
                            end else begin
                                wave_full1 <= 1'b0;
                                wave_xor1  <= 8'd0;
                            end
                            // 如果另一块在发送期间已经封存，刚释放的块
                            // 立即成为新的采集缓冲。
                            if (wave_fill_bank != wave_tx_bank &&
                                ((wave_fill_bank == 1'b0 && wave_full0) ||
                                 (wave_fill_bank == 1'b1 && wave_full1)))
                                wave_fill_bank <= wave_tx_bank;
                        end else st_idx <= st_idx + 6'd1;
                    end
                endcase
            end

            if(profile_req!=profile_ack)begin profile_ack<=profile_req;st_pend8f<=1;end
            if(chain_req!=chain_ack)begin chain_ack<=chain_req;st_pend92<=1;end
            if(model_req!=model_ack)begin model_ack<=model_req;st_pend91<=1;end
            if(capo_req!=capo_ack)begin capo_ack<=capo_req;st_pend90<=1;end
            if(fx_getreq!=fx_getack)begin fx_getack<=fx_getreq;st_pend8d<=1;end
            if(touch_getreq!=touch_getack)begin touch_getack<=touch_getreq;st_pend8a<=1;end
            if(gesture_getreq!=gesture_getack)begin gesture_getack<=gesture_getreq;st_pend89<=1;end
            if(power_getreq!=power_getack)begin power_getack<=power_getreq;st_pend88<=1;end
            if(!power_events && !st_active)begin wave_full0<=0;wave_full1<=0;wave_cap_idx<=0;wave_xor0<=0;wave_xor1<=0;end

            diag_detect<=((!st_active && st_pend86) ? 4'd0 : diag_detect) | opto_ev;
            diag_accept<=((!st_active && st_pend86) ? 4'd0 : diag_accept) | opto_ev_first;
            diag_emit<=((!st_active && st_pend86) ? 4'd0 : diag_emit) | (opto_any ? (4'b1<<performance_channel) : 4'b0);
            // ★ 拨弦事件锁存（放在最后：与"帧起点清零"同一拍时，置位优先 ⇒ 不丢事件）
            if (opto_any && performance_channel==0) o84_evl[0] <= 1'b1;
            if (opto_any && performance_channel==1) o84_evl[1] <= 1'b1;
            if (opto_any && performance_channel==2) o84_evl[2] <= 1'b1;
            if (opto_any && performance_channel==3) o84_evl[3] <= 1'b1;
        end
    end

    // ★ 调度器 → SPI 发送 FIFO 的连线
    //   st_byte 与 st_start 现在都是**组合**的：st_start = st_active && esp_tx_ready，
    //   与 st_byte(当前 st_idx) 严格同拍 ⇒ FIFO 采到的就是当前下标的字节。
    //   （原来 st_start 是寄存器脉冲，两者差一拍，每帧第一个字节 0xAA 被跳过、
    //     帧尾多发一个字节 —— 详见上面 st_start 声明处的注释。）
    assign esp_tx_byte = st_byte;
    assign esp_tx_start = st_start;

    // ============================================================================
    // ★ 光电拨弦链（从 06 工程移植，参数用 06 上整定好的那套）
    // ----------------------------------------------------------------------------
    //   分工：左手按和弦靠 AD7147 电容触摸（同学原有），右手拨弦靠这四路光电。
    //   原理：光电管照着弦，拨动时遮挡反射光 → ADC 读数出现"凹坑"；
    //   四路里凹坑最深且持续够久的那一路判定为拨弦，输出一个事件脉冲。
    // ============================================================================
    // ★ v63：araw0..3 的**声明**已提前到文件前部（0x84 帧要用，见那里的注释）
    reg         adc_start;
    reg         adc_started;
    // ★★ 2026-09-23 晚新增：ADS7950 的**四个采样相位**诊断口。
    //   背景（很反常的一个现象）：**同一个 ads7950_spi.v（逐字节相同）、同样的引脚约束、
    //   同样的 SCLK=3.125MHz、同一块光电板**，
    //     跑 14_opto_tune：四路 1979/2128/1895/1921，addr_err=0 ✓
    //     跑 gv_host   ：ch0/ch2/ch3 掉到几十~几百，只有 ch1 正常 ✗
    //   ⇒ 逻辑一模一样却结果不同 ⇒ 只可能是"SCLK 与 SDO 的相对时序/布线"变了
    //     （这几根脚是 GCLK 可用脚，不同工程里可能被放上全局时钟网络，
    //       于是 SCLK 边沿到采样点的延迟不同，采样点从中部滑到了边沿附近）。
    //   模块内部本来就在一帧里对 SDO 采四次（见 ads7950_spi.v 的 ph0..3）：
    //     ph0 = 高电平中部（当前正式使用，= raw0..3）
    //     ph1 = 上升沿那一拍（曾经的错误做法，留作对照）
    //     ph2 = 高电平 1/4 处    ph3 = 高电平 3/4 处
    //   把三个备用相位引出来：**只要有一个相位读数是干净的，就说明纯粹是相位问题，
    //   改 PH2 即可，不用碰任何硬件**；如果四个相位全是垃圾，那才是真的信号/接线问题。

    // 启动逻辑与 06 完全一致：上电补一个脉冲，之后 done 自环
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            adc_start   <= 1'b0;
            adc_started <= 1'b0;
        end else if(power_modules_sleep)begin adc_start<=0;adc_started<=0;end
        else if (!adc_started) begin
            adc_started <= 1'b1;
            adc_start   <= 1'b1;
        end else begin
            adc_start   <= adc_done;
        end
    end

    ads7950_spi #(
        .CLK_HZ(50_000_000),
        // ★★ 2026-09-23 晚：SCLK 保持 **3.125 MHz**（曾试过降到 781.25 kHz，实测**行不通**）。
        //   实测记录（同一块光电板、同一套接线）：
        //     · 3.125 MHz（14_opto_tune 与 06 都用这个值）：四路 1979/2128/1895/1921，addr_err=0 ✓
        //     · 781.25 kHz（本工程试过）：ch0/ch2/ch3 掉到码值 3（几乎全黑），300/408 个样本 < 500 ✗
        //     · 地址半字节始终是 0/1/2/3、addr_err 只有 545/30061 ⇒ SPI 通路本身是好的，
        //       是**慢时钟让 ADS7950 的采样/转换时序不成立**（不是串扰）。
        //   ⇒ ADS7950 的 SCLK 不能随便降。对付串扰要靠"把干扰源挪开"（ESP32 已从 J5 挪到 J3），
        //     而不是降时钟。
        .SCLK_HZ(3_125_000), .NCH(4)) u_adc (
        .clk(clk), .rst_n(rst_n),
        .start(adc_start), .busy(adc_busy), .done(adc_done),
        .sclk(adc_sclk), .sdi(adc_sdi), .sdo(adc_sdo), .cs_n(adc_cs_n),
        .raw0(araw0), .raw1(araw1), .raw2(araw2), .raw3(araw3),
        .frame_cnt(aframe_cnt), .addr_err_cnt(aaddr_err),
        .scan_us(ascan_us), .last_word(alast_word),
        .ph0(), .ph1(aph1), .ph2(aph2), .ph3(aph3),
        .ch_mismatch_cnt(amismatch)
    );

    // ★ v63：opto_ev / adip0..3 的**声明**已提前到文件前部（0x84 帧要用，见那里的注释）

    strum_detect #(
        .FORCE_TIMING_ENABLE(ENABLE_FORCE),
        .TH_RAW0(100), .TH_RAW1(100), .TH_RAW2(100), .TH_RAW3(100),
        .CM_CAP(0), .MARGIN(10), .PERSIST_N(20),
        .SLOW_STEP(262144), .FAST_STEP(8192),
        .COOL_N(7000), .REARM_N(40), .BLANK_N(90000)
    ) u_strum (
        .clk(clk), .rst_n(rst_n), .vld(adc_done),
        .v0(araw0[11:0]), .v1(araw1[11:0]), .v2(araw2[11:0]), .v3(araw3[11:0]),
        .cfg_valid(1'b1),
        // ★ 光电参数改用【运行时寄存器】o_th*/o_g*（功能④：可由屏幕/上位机下发）
        //   上电初值就是 06 上整定好的那套（阈值 97，增益 68/48/78/71），
        //   收到 0x08 OPTOCFG 后即时生效。
        .cfg_th0(o_th0), .cfg_th1(o_th1), .cfg_th2(o_th2), .cfg_th3(o_th3),
        .cfg_gain0(o_g0), .cfg_gain1(o_g1), .cfg_gain2(o_g2), .cfg_gain3(o_g3),
        .cfg_en(4'b1111),
        // ★★ v80：这 8 个全局参数从**写死的常量**改成运行时寄存器（功能④补全）。
        //   原来它们在这里硬编码，屏幕上点"加载预设"时只有 th/gain 生效，
        //   其余参数一动不动 ⇒ 四档预设之间没有区别（= 上位机那套校正方案只搬进来一半）。
        //   现在由 0x0A OPTGLOB 逐条下发；上电默认值采用 opto_tuner.py 的「抗误报」档。
        .cfg_slow_step(o_slow), .cfg_fast_step(o_fast),
        .cfg_cm_cap(o_cmcap), .cfg_margin(o_margin), .cfg_persist(o_persist),
        .cfg_cool(o_cool), .cfg_blank(o_blank), .cfg_rearm(o_rearm),
        .cfg_stamp(o_stamp),.cfg_poly(performance_poly),.mode_change(performance_mode_stamp),
        // ★ 通道→弦映射（硬件实测）：ADC0→G、ADC1→E、ADC2→C、ADC3→A。
        //   此处 ev0..ev3 保持 ADC 原序，在上面的 opto_note 选择器中完成
        //   “通道→对应弦音高/稳定触摸品位”的映射。
        .ev0(opto_ev[0]), .ev1(opto_ev[1]), .ev2(opto_ev[2]), .ev3(opto_ev[3]),
        .pk0(aev_pk0), .pk1(aev_pk1), .pk2(aev_pk2), .pk3(aev_pk3),
        .velocity_reset(power_flush || power_wake_start),.velocities(opto_velocities),
        .d0(adip0), .d1(adip1), .d2(adip2), .d3(adip3),
        .ob0(abase0), .ob1(abase1), .ob2(abase2), .ob3(abase3)
    );


    // ============ 调试状态总线（v52）============
    //   由命令来源分支给出状态字（触摸：err_any/id_ok/sensor_ok；UART：滑音中），
    //   引脚固定在 .cst：dbg_status[0..3] = K8/L9/K9/J8 = Dock J3-35/34/33/36（bank0，紧挨 SCL/SDA）。
    //   使用：量这 4 个脚的电平即可定位故障阶段（见 doc/v46 §3.3）。

endmodule
