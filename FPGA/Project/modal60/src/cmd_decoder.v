// ============================================================================
// cmd_decoder.v —— 上位机串口命令帧的组帧/CRC/解码
// ----------------------------------------------------------------------------
// 输入：uart_rx 的 rx_byte / rx_valid（字节流）。
// 输出：按 host/PROTOCOL.md §1 组帧（AA CMD LEN PAYLOAD... CRC），
//       校验通过后把 CMD 解码成一组"事件脉冲 + 恒稳电平"控制信号，
//       供 top_gv_host.v 装配成 trig_*（拨弦）与滑音/颤音控制。
//
// 帧格式（PROTOCOL.md §1）：
//   byte0 0xAA | CMD | LEN | PAYLOAD[0..LEN-1] | CRC
//   CRC = CMD ^ LEN ^ PAYLOAD[0..LEN-1]
//
// 输出的每类信号分两种：
//   - cmd_*_ev ：仅 1 clk 脉冲，表示"这个命令刚被收到并成功解码"；
//   - 电平信号：命令携带的参数写成寄存器，持久保持到下次命令覆盖（或 RESET 复位）。
//
// 与下层的衔接（PROTOCOL.md §2/§3）：
//   NOTE  -> note_voice + cmd_note_ev        （送入事件判定：重拨 or 滑音）
//   VIB   -> vib_on                          （颤音开/关）
//   VIB_SET -> vib_rate_x10 / vib_depth      （颤音速率/深度）
//   GLIDE_SET -> glide_en / glide_ms         （滑音总开关 / 时长）
//   GLIDE -> glide_target + cmd_glide_ev     （显式滑音）
//   GAIN  -> gain                            （主音量）
//   RESET -> cmd_reset_ev                    （复位，含一路组合复位由顶层用）
//   PING  -> cmd_ping_ev                     （触发 uart_tx 回 PONG）
// ============================================================================
module cmd_decoder (
    input  wire               clk,
    input  wire               rst_n,
    input  wire [7:0]         rx_byte,
    input  wire               rx_valid,

    // ---- NOTE（拨弦 / 连按可能触发滑音）----
    output reg                cmd_note_ev,
    output reg [5:0]          note_voice,

    // ---- VIB（颤音开/关）----
    output reg                vib_on,
    output reg                cmd_vib_ev,

    // ---- VIB_SET（速率×10 / 深度音分）----
    output reg [7:0]          vib_rate_x10,
    output reg [7:0]          vib_depth,
    output reg                cmd_vibset_ev,

    // ---- GLIDE_SET（总开关 + 时长 ms）----
    output reg                glide_en,
    output reg [15:0]         glide_ms,
    output reg                cmd_glideset_ev,

    // ---- GLIDE（显式滑音到目标音）----
    output reg [5:0]          glide_target,
    output reg                cmd_glide_ev,

    // ---- GAIN（主音量 0..255）----
    output reg [7:0]          gain,
    output reg                cmd_gain_ev,

    // ---- ★ TIMBRE（音色选择 0..3）----  串口屏功能①
    //   注意：这条只是"选哪套音色"的**控制**信号；音色库数据本身
    //   （10.5 KB 的 inc/env0/rate/aatk）由另一条下载通路写进 tone_rom 的 BSRAM。
    output reg [7:0]          timbre_idx,
    output reg                cmd_timbre_ev,

    // ---- RESET / PING ----
    output reg                cmd_reset_ev,
    output reg                cmd_ping_ev,

    // ---- 监控 ----
    output reg [7:0]          cmd_last,       // 最近一次成功命令码（调试用）
    output reg                cmd_ev,         // 任何成功命令脉冲
    output reg                cmd_err         // CRC/长度错误脉冲
);

    // 默认参数：与上位机默认一致
    localparam [7:0] DEFAULT_GAIN = 8'd160;
    localparam [7:0] DEFAULT_VIB_RATE = 8'd60;    // 6.0 Hz（rate×10）
    localparam [7:0] DEFAULT_VIB_DEPTH = 8'd12;   // 12 音分
    localparam [15:0] DEFAULT_GLIDE_MS = 16'd60;  // 60 ms

    localparam [7:0] SYNC = 8'hAA;

    // ---------------- 组帧状态 ----------------
    localparam [2:0] ST_SYNC = 3'd0;
    localparam [2:0] ST_CMD  = 3'd1;
    localparam [2:0] ST_LEN  = 3'd2;
    localparam [2:0] ST_DATA = 3'd3;
    localparam [2:0] ST_CRC  = 3'd4;

    reg [2:0]  state;
    reg [7:0]  cmd;
    reg [7:0]  len;
    reg [6:0]  idx;
    reg [7:0]  crc_calc;
    reg [7:0]  pay [0:63];        // 载荷缓存（LEN≤64）

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state        <= ST_SYNC;
            cmd          <= 8'h00;
            len          <= 8'h00;
            idx          <= 7'd0;
            crc_calc     <= 8'h00;
            cmd_last     <= 8'h00;
            cmd_ev       <= 1'b0;
            cmd_err      <= 1'b0;
            // 输出寄存器复位到默认
            cmd_note_ev  <= 1'b0;
            note_voice   <= 6'd0;
            vib_on       <= 1'b0;
            cmd_vib_ev   <= 1'b0;
            vib_rate_x10 <= DEFAULT_VIB_RATE;
            vib_depth    <= DEFAULT_VIB_DEPTH;
            cmd_vibset_ev<= 1'b0;
            glide_en     <= 1'b0;
            glide_ms     <= DEFAULT_GLIDE_MS;
            cmd_glideset_ev <= 1'b0;
            glide_target <= 6'd0;
            cmd_glide_ev <= 1'b0;
            gain         <= DEFAULT_GAIN;
            cmd_gain_ev  <= 1'b0;
            timbre_idx   <= 8'd0;        // 上电 = 音色 0
            cmd_timbre_ev<= 1'b0;
            cmd_reset_ev <= 1'b0;
            cmd_ping_ev  <= 1'b0;
        end else begin
            // 默认拉低单拍脉冲（需要拉高的命令下面再置 1）
            cmd_note_ev    <= 1'b0;
            cmd_vib_ev     <= 1'b0;
            cmd_vibset_ev  <= 1'b0;
            cmd_glideset_ev<= 1'b0;
            cmd_glide_ev   <= 1'b0;
            cmd_gain_ev    <= 1'b0;
            cmd_timbre_ev  <= 1'b0;
            cmd_reset_ev   <= 1'b0;
            cmd_ping_ev    <= 1'b0;
            cmd_ev         <= 1'b0;
            cmd_err        <= 1'b0;

            if (rx_valid) begin
                case (state)
                ST_SYNC: begin
                    if (rx_byte == SYNC) begin
                        state    <= ST_CMD;
                        crc_calc <= 8'h00;
                    end
                end
                ST_CMD: begin
                    cmd      <= rx_byte;
                    crc_calc <= rx_byte;                 // CRC 从 CMD 开始
                    state    <= ST_LEN;
                end
                ST_LEN: begin
                    len      <= rx_byte;
                    crc_calc <= crc_calc ^ rx_byte;      // XOR LEN
                    idx      <= 7'd0;
                    if (rx_byte == 8'd0)
                        state <= ST_CRC;                 // 无载荷
                    else
                        state <= ST_DATA;
                end
                ST_DATA: begin
                    pay[idx] <= rx_byte;
                    crc_calc <= crc_calc ^ rx_byte;      // XOR payload
                    if (idx == len - 1)
                        state <= ST_CRC;
                    else
                        idx   <= idx + 7'd1;
                end
                ST_CRC: begin
                    if (rx_byte == crc_calc) begin       // 校验通过 -> 解码执行
                        cmd_ev   <= 1'b1;
                        cmd_last <= cmd;
                        decode_ok(cmd, len);
                    end else begin
                        cmd_err  <= 1'b1;
                    end
                    state <= ST_SYNC;
                    idx   <= 7'd0;
                end
                default: state <= ST_SYNC;
                endcase
            end
        end
    end

    // ---------------- 解码：根据 CMD 设置输出 ----------------
    task decode_ok(input [7:0] c, input [7:0] l);
        begin
            case (c)
            8'h01: begin        // NOTE: [voice]
                if (l >= 1) begin
                    note_voice   <= pay[0][5:0];
                    cmd_note_ev  <= 1'b1;
                end
            end
            8'h02: begin        // VIB: [on]
                if (l >= 1) begin
                    vib_on       <= pay[0][0];
                    cmd_vib_ev   <= 1'b1;
                end
            end
            8'h03: begin        // VIB_SET: [rate_x10, depth_cents]
                if (l >= 2) begin
                    vib_rate_x10 <= pay[0];
                    vib_depth    <= pay[1];
                    cmd_vibset_ev<= 1'b1;
                end
            end
            8'h04: begin        // GLIDE_SET: [en, ms_hi, ms_lo]
                if (l >= 3) begin
                    glide_en     <= pay[0][0];
                    glide_ms     <= {pay[1], pay[2]};
                    cmd_glideset_ev <= 1'b1;
                end
            end
            8'h05: begin        // GLIDE: [target_voice]
                if (l >= 1) begin
                    glide_target <= pay[0][5:0];
                    cmd_glide_ev <= 1'b1;
                end
            end
            8'h06: begin        // GAIN: [gain]
                if (l >= 1) begin
                    gain         <= pay[0];
                    cmd_gain_ev  <= 1'b1;
                end
            end
            8'h07: begin        // RESET
                vib_on       <= 1'b0;
                glide_en     <= 1'b0;
                gain         <= DEFAULT_GAIN;
                note_voice   <= 6'd0;
                glide_target <= 6'd0;
                cmd_reset_ev <= 1'b1;
            end
            8'h08: begin        // PING
                cmd_ping_ev  <= 1'b1;
            end
            default: ;          // 未定义命令：忽略（仅记 cmd_last）
            endcase
        end
    endtask

endmodule
