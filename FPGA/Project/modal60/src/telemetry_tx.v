// ============================================================================
// telemetry_tx.v —— ★ v56 新增：FPGA → 上位机的**遥测串流**（只发不收）
// ----------------------------------------------------------------------------
// 用途：上板调试时把"通信状况 + 每一路 CDC + 识别结果"持续发给 PC，
//      由上位机 `work/host/touch_monitor.py` 显示。没有它，上板只能看 4 个状态位，
//      无法判断"到底是没读到数据、还是数据不随触摸变化、还是阈值不对"。
//
// 协议（ASCII 文本行；**所有数字都是定宽十六进制** ⇒ RTL 只需选 nibble，
//        也便于人肉用任意串口助手直接看）：
//
//   $H,<seq4>,<err2>,<idok2>,<flags2>,<dev0_4>,<dev1_4>,<sv3>,<dup3>\n
//        seq  = 帧序号（4 hex，回绕）
//        err  = I²C 事务出错计数（NACK/看门狗超时）
//        idok = 每片 Device ID 是否通过（一位一片）
//        flags= bit0 sensor_ok、bit1 panel_ready、bit2 有 pad 命中
//        dev0/dev1 = 两片读回的 Device ID（正常应为 1470）
//        sv   = 本帧内 sample_valid 次数 ⇒ 实际轮询帧率
//        dup  = 本帧内 sample_dup 次数 ⇒ 数据没更新的重复读
//   $C,<ch2>,<cdc4>,<fbase4>,<fdelta4>,<fflg1>\n
//        cdc   = 芯片原始 CDC 值
//        fbase/fdelta = **FPGA 内部**这一路的基线与 Δ（★ v59 新增）
//        fflg  = bit0 over、bit1 phit、bit2 valid、bit3 pad_ok
//        RAM mode: raw/base/delta/flags share one recognition-round snapshot.
//   $K,<str1>,<fret2>\n   每个"判定为按下"的品格一行
//   $R,<gain2>,<voice2>,<dmax4>,<flags1>,<gtgt2>,<rounds4>,<padhit>\n
//        padhit has ceil(N_STR*N_FRET/4) hex digits (18 for the 72-pad panel).
//        dmax = 当前最大 Δ；flags bit0 = 本帧内出现过 note_ev、bit1 = 出现过 glide_ev
//        rounds = touch_recog 完成的轮数（**证明识别状态机还在跑**，不再动就是卡死了）
//        padhit = pad_hit 位图原文（与 $K 对照：不一致就是遥测索引有问题）
//   $E,<seq4>\n           帧结束 —— 上位机收到它才认这一帧完整
//   $D,<init2>,<retry2>,<fail2>,<state2>,<ids32>\n (INIT_DIAG=1, after $H)
//        init/retry: saturating full-restart/extra-ID-read counters, hex.
//        fail: last failed round mask (ID OR configuration readback failure).
//        ids: H..A packed latest raw IDs, A is the rightmost four digits.
//
//   实测一帧（24 通道、1 个按下）≈ 660 字节 @115200 ⇒ 57 ms；
//   默认每 100 ms 一帧（10 Hz）⇒ 占用率约 57%。通道很多时可以调大 PERIOD_MS。
// ============================================================================
module telemetry_tx #(
    parameter CLK_HZ    = 50_000_000,
    parameter NCH       = 24,
    parameter N_STR     = 4,
    parameter N_FRET    = 3,
    parameter N_CHIPS   = 2,
    parameter PERIOD_MS = 100,
    parameter SNAP_CDC  = 0,
    parameter INIT_DIAG = 0
) (
    input  wire        clk,
    input  wire        rst_n,
    // ---- 通信/链路状况 ----
    input  wire [3:0]  err_cnt,
    input  wire [N_CHIPS-1:0] id_ok,
    input  wire        sensor_ok,
    input  wire        panel_ready,
    input  wire [15:0] dev_id0,
    input  wire [15:0] dev_id1,
    input  wire [N_CHIPS*16-1:0] dev_ids,
    input  wire [7:0] init_retries,id_retries,
    input  wire [N_CHIPS-1:0] last_fail_mask,
    input  wire [4:0] init_state,
    input  wire        sample_valid,
    input  wire        sample_dup,
    // ---- 触摸数据 ----
    input  wire [NCH*16-1:0]  cdc_bus,
    input  wire [15:0] cdc_snap,
    input  wire [N_STR*N_FRET-1:0] pad_hit,
    // ---- ★ v59：FPGA 内部每路状态快照（来自 touch_recog）----
    input  wire [47:0] dbg_snap,             // {flags[3:0], delta[15:0], base[15:0]}
    output wire [6:0]  dbg_snap_ch,          // 本拍要看哪一路
    input  wire [15:0] dbg_rounds,
    // ---- 识别结果快照 ----
    input  wire [7:0]  gain,
    input  wire [5:0]  voice,
    input  wire [13:0] dbg_dmax,
    input  wire        note_ev,
    input  wire        glide_ev,
    input  wire [5:0]  glide_target,
    // ---- 字节流输出（接 uart_tx）----
    output reg  [7:0]  tx_byte,
    output reg         tx_start,
    input  wire        tx_busy,
    // ★ 2026-09-23 新增：**整帧进行中**（从 $H 第一个字节起到 $E 最后一个字节被接走）。
    //   为什么要对外暴露：本工程把这个遥测口和"光电 $O 帧"时分复用了
    //   （顶层 `assign tx = o_busy ? o_tx_out : tele_tx_out;`）。原来 $O 侧只用
    //   uart_tx 的 tx_busy 判"空闲"，而 tx_busy 在**字节之间**也会拉低一个时钟，
    //   于是 $O 帧从缝里挤进来，把正在发的 $C 行劈成两半 —— 实测抓到
    //   `$C,01,0100,2F91,000$O,07DF,1868,`，两边遥测同时被破坏。
    //   更坑的是本模块在 L_F 状态会**等 pad_valid 很久**（远超任何"空闲超时"判据），
    //   所以只能用这个帧级信号，不能靠猜空闲时长。
    output wire        frame_active
);

    // Declare the frame state before exposing it on the output.  Some tools
    // otherwise treat `sending` as an implicit wire and reject the later reg.
    reg        sending;
    assign frame_active = sending;

    localparam N_PAD   = N_STR * N_FRET;
    localparam PAD_HEX = (N_PAD+3)/4;
    localparam [7:0] N_FRET8 = N_FRET;
    localparam [7:0] NCH8 = NCH;
    localparam [7:0] NPAD8= N_PAD;
    localparam integer PERIOD_TICKS = (CLK_HZ / 1000) * PERIOD_MS;

    // 行类型
    localparam [2:0] L_H = 3'd0,   // 状态头
                     L_C = 3'd1,   // 一路 CDC
                     L_F = 3'd2,   // 找下一个按下的 pad（1 拍）
                     L_K = 3'd3,   // 发一个按下的 pad
                     L_R = 3'd4,   // 识别结果
                     L_E = 3'd5,   // 帧尾
                     L_D = 3'd6;   // startup retry diagnostics

    // 各行字节数
    localparam [6:0] LEN_H = 7'd35, LEN_C = 7'd23, LEN_K = 7'd8,
                     LEN_R = 25+PAD_HEX, LEN_E = 7'd8, LEN_D=7'd48;
    // $R length is 25+PAD_HEX (28 bytes for 12 pads, 43 for 72 pads).

    // ASCII
    localparam [7:0] C_DOLLAR = 8'h24, C_COMMA = 8'h2C, C_LF = 8'h0A;
    localparam [7:0] C_H = 8'h48, C_C = 8'h43, C_K = 8'h4B, C_R = 8'h52, C_E = 8'h45;

    function [7:0] hexb;
        input [3:0] n;
        begin
            hexb = (n < 4'd10) ? (8'h30 + {4'b0, n}) : (8'h41 + {4'b0, n} - 8'd10);
        end
    endfunction

    // ---------------- 帧快照（开帧时锁存，保证一帧内一致）----------------
    reg [15:0] f_seq;
    reg [7:0]  f_err;
    reg [7:0]  f_idok;
    reg [7:0]  f_flags;
    reg [15:0] f_dev0, f_dev1;
    reg [127:0] f_ids;
    reg [7:0] f_init_retry,f_id_retry,f_fail,f_state;
    reg [11:0] f_sv, f_dup;
    reg [7:0]  f_gain;
    reg [5:0]  f_voice, f_gtgt;
    reg [13:0] f_dmax;
    reg [1:0]  f_ev;
    reg [15:0] f_rounds;              // ★ v59：touch_recog 完成的轮数
    reg [PAD_HEX*4-1:0] f_padhit;      // all pads, including string 4 fret 18


    reg [11:0] sv_cnt, dup_cnt;
    reg        ev_note_l, ev_glide_l;

    // ---------------- 发送状态 ----------------
    reg        byte_pend;     // ★ v57：已断言 tx_start、等 uart_tx 接走（busy 拉高）
    reg [2:0]  line;
    reg [6:0]  pos;
    reg [7:0]  ch;
    reg [7:0]  pad_scan;      // 扫描起点
    reg [7:0]  cur_pad;       // 正在发的 pad

    // ★ v59：遥测按通道依次读 touch_recog 的快照 ⇒ 用当前通道号做读地址
    //   （声明必须放在 ch 之后：Questa 不允许先用后声明）
    assign dbg_snap_ch = ch[6:0];

    // 周期计时
    reg [31:0] ptick;
    wire       period_hit = (ptick >= PERIOD_TICKS[31:0]);

    // ---------------- 组合：从 pad_scan 找下一个按下的 pad ----------------
    //   ★ 必须**由低到高**扫（0 → N_PAD-1），否则高位的 pad 先被取走、
    //     低位的会被 pad_scan 跳过（第一版就是高到低，结果漏报了一个按下的键）。
    reg        pad_valid;
    reg [7:0]  pad_next;
    integer    pi;
    always @(*) begin
        pad_valid = 1'b0;
        pad_next  = 8'd0;
        for (pi = 0; pi < N_PAD; pi = pi + 1) begin
            if ((pi[7:0] >= pad_scan) && pad_hit[pi] && !pad_valid) begin
                pad_valid = 1'b1;
                pad_next  = pi[7:0];
            end
        end
    end

    // cur_pad → (弦,品)，1-based
    wire [7:0] cp_str  = (cur_pad / N_FRET8) + 8'd1;
    wire [7:0] cp_fret = (cur_pad % N_FRET8) + 8'd1;

    // 当前通道的 CDC
    wire [15:0] cdc_sel = SNAP_CDC ? cdc_snap : cdc_bus[ch*16 +: 16];
    reg [15:0] line_cdc;
    reg [47:0] line_snap;
    // Freeze one channel before sending its hex digits: a recognition update
    // during UART transmission must not mix old/new nibbles in a single $C.
    always @(posedge clk) begin
        if (sending && line==L_C && pos==0 && !byte_pend && !tx_busy) begin
            line_cdc <= cdc_sel;
            line_snap <= dbg_snap;
        end
    end

    // 当前行长度
    reg [6:0] cur_len;
    always @(*) begin
        case (line)
            L_H:     cur_len = LEN_H;
            L_D:     cur_len = LEN_D;
            L_C:     cur_len = LEN_C;
            L_K:     cur_len = LEN_K;
            L_R:     cur_len = LEN_R;
            L_E:     cur_len = LEN_E;
            default: cur_len = 7'd1;      // L_F：只占一拍，不真发字节
        endcase
    end

    // ---------------- 组合：当前要发的字节 ----------------
    reg [7:0] cur_byte;
    always @(*) begin
        cur_byte = C_LF;
        case (line)
        L_H: case (pos)
            7'd0 : cur_byte = C_DOLLAR;
            7'd1 : cur_byte = C_H;
            7'd2 : cur_byte = C_COMMA;
            7'd3 : cur_byte = hexb(f_seq[15:12]);
            7'd4 : cur_byte = hexb(f_seq[11:8]);
            7'd5 : cur_byte = hexb(f_seq[7:4]);
            7'd6 : cur_byte = hexb(f_seq[3:0]);
            7'd7 : cur_byte = C_COMMA;
            7'd8 : cur_byte = hexb(f_err[7:4]);
            7'd9 : cur_byte = hexb(f_err[3:0]);
            7'd10: cur_byte = C_COMMA;
            7'd11: cur_byte = hexb(f_idok[7:4]);
            7'd12: cur_byte = hexb(f_idok[3:0]);
            7'd13: cur_byte = C_COMMA;
            7'd14: cur_byte = hexb(f_flags[7:4]);
            7'd15: cur_byte = hexb(f_flags[3:0]);
            7'd16: cur_byte = C_COMMA;
            7'd17: cur_byte = hexb(f_dev0[15:12]);
            7'd18: cur_byte = hexb(f_dev0[11:8]);
            7'd19: cur_byte = hexb(f_dev0[7:4]);
            7'd20: cur_byte = hexb(f_dev0[3:0]);
            7'd21: cur_byte = C_COMMA;
            7'd22: cur_byte = hexb(f_dev1[15:12]);
            7'd23: cur_byte = hexb(f_dev1[11:8]);
            7'd24: cur_byte = hexb(f_dev1[7:4]);
            7'd25: cur_byte = hexb(f_dev1[3:0]);
            7'd26: cur_byte = C_COMMA;
            7'd27: cur_byte = hexb(f_sv[11:8]);
            7'd28: cur_byte = hexb(f_sv[7:4]);
            7'd29: cur_byte = hexb(f_sv[3:0]);
            7'd30: cur_byte = C_COMMA;
            7'd31: cur_byte = hexb(f_dup[11:8]);
            7'd32: cur_byte = hexb(f_dup[7:4]);
            7'd33: cur_byte = hexb(f_dup[3:0]);
            default: cur_byte = C_LF;
        endcase
        L_D: begin
            case(pos)
                0: cur_byte=C_DOLLAR;
                1: cur_byte=8'h44;
                2,5,8,11,14: cur_byte=C_COMMA;
                3: cur_byte=hexb(f_init_retry[7:4]);
                4: cur_byte=hexb(f_init_retry[3:0]);
                6: cur_byte=hexb(f_id_retry[7:4]);
                7: cur_byte=hexb(f_id_retry[3:0]);
                9: cur_byte=hexb(f_fail[7:4]);
                10: cur_byte=hexb(f_fail[3:0]);
                12: cur_byte=hexb(f_state[7:4]);
                13: cur_byte=hexb(f_state[3:0]);
                default: if(pos>=15 && pos<=46) cur_byte=hexb(f_ids[(46-pos)*4+:4]);
            endcase
        end
        L_C: case (pos)
            7'd0 : cur_byte = C_DOLLAR;
            7'd1 : cur_byte = C_C;
            7'd2 : cur_byte = C_COMMA;
            7'd3 : cur_byte = hexb(ch[7:4]);
            7'd4 : cur_byte = hexb(ch[3:0]);
            7'd5 : cur_byte = C_COMMA;
            7'd6 : cur_byte = hexb(line_cdc[15:12]);
            7'd7 : cur_byte = hexb(line_cdc[11:8]);
            7'd8 : cur_byte = hexb(line_cdc[7:4]);
            7'd9 : cur_byte = hexb(line_cdc[3:0]);
            7'd10: cur_byte = C_COMMA;
            // ---- ★ v59：FPGA 内部基线 ----
            7'd11: cur_byte = hexb(line_snap[15:12]);
            7'd12: cur_byte = hexb(line_snap[11:8]);
            7'd13: cur_byte = hexb(line_snap[7:4]);
            7'd14: cur_byte = hexb(line_snap[3:0]);
            7'd15: cur_byte = C_COMMA;
            // ---- ★ v59：FPGA 内部 Δ ----
            7'd16: cur_byte = hexb(line_snap[31:28]);
            7'd17: cur_byte = hexb(line_snap[27:24]);
            7'd18: cur_byte = hexb(line_snap[23:20]);
            7'd19: cur_byte = hexb(line_snap[19:16]);
            7'd20: cur_byte = C_COMMA;
            // ---- ★ v59：flags（1 位 hex：bit0 over / bit1 phit / bit2 valid / bit3 pad_ok）----
            7'd21: cur_byte = hexb(line_snap[35:32]);
            // ★ 2026-09-23 修（子代理复核发现）：原来取 [47:44]，但 dbg_snap 只有 36 位
            //   （touch_recog.v 拼的是 {flags[3:0], delta[15:0], base[15:0]}）⇒ 高 12 位恒 0，
            //   每路 $C 的 fflg 字段【恒 0】，v59 那套每路诊断实际一直是失效的。
            //   旁证：work/sim_i2c/tb_telemetry.v 用 48'h3_1234_5678 展开后 flags 正好在
            //   [35:32]，而 check_telemetry.py 从未校验该字段 ⇒ 当时的仿真是"假 PASS"。

            default: cur_byte = C_LF;
        endcase
        L_K: case (pos)
            7'd0 : cur_byte = C_DOLLAR;
            7'd1 : cur_byte = C_K;
            7'd2 : cur_byte = C_COMMA;
            7'd3 : cur_byte = hexb(cp_str[3:0]);
            7'd4 : cur_byte = C_COMMA;
            7'd5 : cur_byte = hexb(cp_fret[7:4]);
            7'd6 : cur_byte = hexb(cp_fret[3:0]);
            default: cur_byte = C_LF;
        endcase
        L_R: case (pos)
            7'd0 : cur_byte = C_DOLLAR;
            7'd1 : cur_byte = C_R;
            7'd2 : cur_byte = C_COMMA;
            7'd3 : cur_byte = hexb(f_gain[7:4]);
            7'd4 : cur_byte = hexb(f_gain[3:0]);
            7'd5 : cur_byte = C_COMMA;
            7'd6 : cur_byte = hexb({2'b0, f_voice[5:4]});
            7'd7 : cur_byte = hexb(f_voice[3:0]);
            7'd8 : cur_byte = C_COMMA;
            7'd9 : cur_byte = hexb({2'b0, f_dmax[13:12]});
            7'd10: cur_byte = hexb(f_dmax[11:8]);
            7'd11: cur_byte = hexb(f_dmax[7:4]);
            7'd12: cur_byte = hexb(f_dmax[3:0]);
            7'd13: cur_byte = C_COMMA;
            7'd14: cur_byte = hexb({2'b0, f_ev});
            7'd15: cur_byte = C_COMMA;
            7'd16: cur_byte = hexb({2'b0, f_gtgt[5:4]});
            7'd17: cur_byte = hexb(f_gtgt[3:0]);
            // ---- ★ v59：识别轮数 + pad_hit 原文 ----
            7'd18: cur_byte = C_COMMA;
            7'd19: cur_byte = hexb(f_rounds[15:12]);
            7'd20: cur_byte = hexb(f_rounds[11:8]);
            7'd21: cur_byte = hexb(f_rounds[7:4]);
            7'd22: cur_byte = hexb(f_rounds[3:0]);
            7'd23: cur_byte = C_COMMA;
            default: begin
                if (pos>=24 && pos<24+PAD_HEX)
                    cur_byte=hexb(f_padhit[(PAD_HEX-1-(pos-24))*4+:4]);
                else cur_byte=C_LF;
            end
        endcase
        L_E: case (pos)
            7'd0 : cur_byte = C_DOLLAR;
            7'd1 : cur_byte = C_E;
            7'd2 : cur_byte = C_COMMA;
            7'd3 : cur_byte = hexb(f_seq[15:12]);
            7'd4 : cur_byte = hexb(f_seq[11:8]);
            7'd5 : cur_byte = hexb(f_seq[7:4]);
            7'd6 : cur_byte = hexb(f_seq[3:0]);
            default: cur_byte = C_LF;
        endcase
        default: cur_byte = C_LF;
        endcase
    end

    // ---------------- 单一顺序块：帧调度 + 字节推进 ----------------
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            ptick <= 32'd0;
            sending <= 1'b0; line <= L_H; pos <= 7'd0; ch <= 8'd0;
            byte_pend <= 1'b0;
            pad_scan <= 8'd0; cur_pad <= 8'd0;
            tx_byte <= 8'h00; tx_start <= 1'b0;
            f_seq <= 16'h0000; f_err <= 8'h00; f_idok <= 8'h00; f_flags <= 8'h00;
            f_dev0 <= 16'h0000; f_dev1 <= 16'h0000; f_sv <= 12'd0; f_dup <= 12'd0;
            f_ids<=0; f_init_retry<=0; f_id_retry<=0; f_fail<=0; f_state<=0;
            f_gain <= 8'h00; f_voice <= 6'd0; f_gtgt <= 6'd0; f_dmax <= 14'd0; f_ev <= 2'b00;
            f_rounds <= 16'h0000; f_padhit <= 12'h000;
            sv_cnt <= 12'd0; dup_cnt <= 12'd0; ev_note_l <= 1'b0; ev_glide_l <= 1'b0;
        end else begin
            tx_start <= 1'b0;

            // 帧内统计
            if (sample_valid && sv_cnt  != 12'hFFF) sv_cnt  <= sv_cnt  + 12'd1;
            if (sample_dup   && dup_cnt != 12'hFFF) dup_cnt <= dup_cnt + 12'd1;
            if (note_ev)  ev_note_l  <= 1'b1;
            if (glide_ev) ev_glide_l <= 1'b1;

            // 周期计时
            if (period_hit) ptick <= 32'd0;
            else            ptick <= ptick + 32'd1;

            if (!sending) begin
                if (period_hit) begin
                    // ---- 开一帧：锁存快照 ----
                    sending  <= 1'b1;
                    line     <= L_H;
                    pos      <= 7'd0;
                    ch       <= 8'd0;
                    pad_scan <= 8'd0;
                    cur_pad  <= 8'd0;
                    f_seq    <= f_seq + 16'd1;
                    f_err    <= {4'b0, err_cnt};
                    f_idok   <= {{(8-N_CHIPS){1'b0}}, id_ok};
                    f_flags  <= {5'b0, (pad_hit != {N_PAD{1'b0}}), panel_ready, sensor_ok};
                    f_dev0   <= dev_id0;
                    f_dev1   <= dev_id1;
                    if(INIT_DIAG) begin
                        f_ids<=dev_ids;
                        f_init_retry<=init_retries; f_id_retry<=id_retries;
                        f_fail<=last_fail_mask; f_state<={3'd0,init_state};
                    end
                    f_sv     <= sv_cnt;
                    f_dup    <= dup_cnt;
                    f_gain   <= gain;
                    f_voice  <= voice;
                    f_gtgt   <= glide_target;
                    f_dmax   <= dbg_dmax;
                    f_ev     <= {ev_glide_l, ev_note_l};
                    f_rounds <= dbg_rounds;
                    f_padhit <= pad_hit;
                    sv_cnt     <= 12'd0;
                    dup_cnt    <= 12'd0;
                    ev_note_l  <= 1'b0;
                    ev_glide_l <= 1'b0;
                end
            end else if (line == L_F) begin
                // ---- 找下一个按下的 pad（1 拍，不发字节）----
                pos <= 7'd0;
                if (pad_valid) begin
                    cur_pad <= pad_next;
                    line    <= L_K;
                end else begin
                    line <= L_R;
                end
            end else if (!byte_pend) begin
                // ---- 第一段：断言 tx_start（uart_tx 下一拍才把它接走）----
                if (!tx_busy) begin
                    tx_start  <= 1'b1;
                    tx_byte   <= cur_byte;
                    byte_pend <= 1'b1;
                end
            end else if (tx_busy) begin
                // ---- 第二段：uart_tx 已接走（busy 拉高）⇒ 清 pend 并推进指针 ----
                //   ★ v57 修 bug（上板现象：只有"坏行/半帧"在涨，其它全 0）：
                //     原来这里是 `else if (!tx_busy)` 直接发+推进 —— 但 uart_tx 是
                //     **下一拍**才采到 tx_start 并把 busy 拉高的，所以"busy=0"会持续
                //     两拍：第一拍断言 tx_start，第二拍又断言一次并把 pos 推进两次、
                //     tx_byte 被覆盖 ⇒ **每个字节都会被吞掉一个/错位**，收到的全是乱码。
                //     现在改成"断言 → 等它变忙 → 才推进"，一个字节一拍不多一拍不少。
                byte_pend <= 1'b0;

                if (pos + 7'd1 >= cur_len) begin
                    pos <= 7'd0;
                    case (line)
                        L_H: begin
                            line <= INIT_DIAG ? L_D : L_C;
                            ch   <= 8'd0;
                        end
                        L_D: begin line<=L_C; ch<=0; end
                        L_C: begin
                            if (ch + 8'd1 >= NCH8) begin
                                line     <= L_F;      // 通道发完 ⇒ 找按下的 pad
                                pad_scan <= 8'd0;
                            end else begin
                                ch <= ch + 8'd1;
                            end
                        end
                        L_K: begin
                            pad_scan <= cur_pad + 8'd1;
                            line     <= (cur_pad + 8'd1 >= NPAD8) ? L_R : L_F;
                        end
                        L_R: line <= L_E;
                        default: begin
                            sending <= 1'b0;      // L_E 的最后一个字节已被接走 ⇒ 一帧结束
                        end
                    endcase
                end else begin
                    pos <= pos + 7'd1;
                end
            end
        end
    end

endmodule
