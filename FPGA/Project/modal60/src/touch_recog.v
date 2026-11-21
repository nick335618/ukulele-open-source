// ============================================================================
// touch_recog.v —— 触摸识别：**单音 / 和弦 / 滑音 / 揉弦颤音**（从 ESP32 固件移植）
// ----------------------------------------------------------------------------
// ★★ 输出接口**刻意与老 cmd_decoder 逐位一致**：
//       note_ev / note_voice[5:0] / glide_ev / glide_target[5:0] / gain[7:0]
//    ⇒ 顶层只要把 cmd_decoder 换成本模块，事件判定、引擎触发、音频侧一行都不用改。
//
// 算法（与固件一致，未改语义）：
//   ① 每格 Δ = CDC − 基线；基线慢跟踪（只在"没被摸到"的样本上跟）；开机先学 BASE_LEARN_N 个样本。
//   ② 命中 = Δ > 阈值 + 消抖（连续 TRIG_N / REL_N）。
//   ③ 单音：一根弦在已经消抖命中的品位中取最高品；Δ 不决定音高。
//   ④ 和弦：每弦独立判定 ⇒ 各自一次 note_ev。⑤ 滑音：旧品塌 + 新版强 + SETTLE/GAP。
//   ⑥ 松开不发命令。⑦ 只丢弃前端明确标记的重复采样；SPI 完整转换后的相同值仍是新样本。
//
// ==================== ★ v50 重构：为时序而多级流水 ====================
// 实测：旧版触识别有 **25 条 setup 违例（最差 −7.89 ns）**，两处关键路径：
//   (a) 通道号 → CDC 大 mux + 平台值除法 + 基线移位 + 消抖，全挤在一拍；
//   (b) 弦号 → 读 6 张表 + 两次乘法 + 除100 + 32 位减法，全挤在一拍。
// 现在把每一步拆成独立的一拍（组合逻辑每拍 ≤ 2~3 级）：
//   每通道 5 拍：FETCH 等同步 RAM / A 读 CDC+ROM / B 算 over·Δ / C 算基线·平台·消抖 / D 取最强格·位图·写回 RAM
//   每弦   3 拍：A 读表值 + 32 位时差 / B 两次乘法（keep_th / domi）/ C 比较 + 出事件 + 更新弦状态
//   ⇒ 72 通道一轮约 373 拍（50MHz 下不足 8us），远小于约 23ms 的传感器扫描周期。
// 另外：通道→(弦,品) 走**同步 ROM**（ch_map_rom，提前一拍给地址）；每通道状态放在
//   **同步块 RAM**里，逐地址清零和写回，避免 72 路触发器以及大读写选择网络。
// ============================================================================
`include "touch_tables.vh"

module touch_recog #(
    parameter RUNTIME_CONFIG = 0,
    parameter CAPO_ENABLED = 0,
    parameter CLK_HZ     = 50_000_000,
    parameter NCH        = 24,
    parameter CH_BITS    = 7,
    parameter N_STR      = 4,
    parameter N_FRET     = 3,
    parameter FRET_BITS  = 5,
    parameter CDC_RAM    = 0,         // release top uses addressed CDC RAM
    // ---- 判定参数（默认取自固件调好之后的那一版）----
    parameter [15:0] THRESH      = 16'd1200,
    parameter [3:0]  BASE_ALPHA  = 4'd7,
    parameter [3:0]  BASE_LEARN_N= 4'd12,
    parameter [3:0]  TRIG_N      = 4'd2,
    parameter [3:0]  REL_N       = 4'd3,
    parameter [15:0] DOMI_ABS    = 16'd150,
    parameter [7:0]  DOMI_PCT    = 8'd2,
    parameter [3:0]  PLATEAU_DECAY = 4'd12,
    parameter [15:0] SETTLE_MS   = 16'd120,
    parameter [15:0] GAP_MS      = 16'd30,
    parameter [15:0] SAMPLE_MS   = 16'd37,
    parameter [7:0]  GAIN_DEF    = 8'd160,
    // ---- ★ v58：上电稳定 + 读数合法性（照搬固件里被踩过坑的两条保护）----
    //   ① SETTLE_MS：刚上电时片内 auto-zero / 环境跟踪还在爬，读数会持续漂移。
    //      固件是"手指离开等 4 秒再采基线"；这里在 panel_ready 之后同样先静置
    //      （默认 1500 ms），期间**照常学基线但不判命中**，避免把漂移中的值当触摸。
    //   ② CDC_SANE_LO/HI：固件明确写着"读失败(0xFFFF)和贴着量程两端(0/65535)的值
    //      绝不能当基线，否则 Δ≈±32000、那一格会永久粘在按下"。
    parameter [15:0] SETTLE_MS_CFG = 16'd1500,
    parameter [15:0] CDC_SANE_LO  = 16'd800,
    parameter [15:0] CDC_SANE_HI  = 16'd64700,
    parameter VIB_AMP_ABS = 120,
    parameter VIB_REL_SH = 5,
    parameter VIB_AGE_MAX = (228+SAMPLE_MS-1)/SAMPLE_MS,
    parameter EXTERNAL_MAP = 0,
    parameter VIB_MIN_GAP = 2,
    parameter VIB_DC_SH = 3,
    parameter VIB_AMP_SH = 2
) (
    input  wire        clk,
    input  wire        rst_n,
    output wire [6:0] shared_map_addr,
    input wire [7:0] shared_map_data,
    input  wire [NCH*16-1:0] cdc_bus,     // cdc_bus[ch*16 +: 16]
    input  wire [15:0] cdc_data,
    output wire [CH_BITS-1:0] cdc_addr,
    input  wire        sample_valid,
    input  wire        sample_dup,
    input  wire        panel_ready,
    output wire        vib_on,
    output wire [N_STR-1:0] vib_str,
    output reg [15:0]  vib_amp,
    output reg         note_ev,
    output reg  [5:0]  note_voice,
    output reg         glide_ev,
    output reg  [5:0]  glide_target,
    // glide_ev 对应的弦号：0=A、1=E、2=C、3=G。
    // 融合顶层用它把滑动只交给刚由光电拨响的同一根弦，避免和弦时串弦变调。
    output reg  [1:0]  glide_string,
    output reg  [7:0]  gain,
    // Live read-only tap; no scan stalls or additional snapshot RAM.
    output wire diag_valid,
    output wire [CH_BITS-1:0] diag_channel,
    output wire [51:0] diag_data,
    output reg  [N_STR*N_FRET-1:0] pad_hit,
    output reg  [N_STR-1:0] str_press,
    // 每根弦经过消抖、最高品选择后的稳定品位。光电拨弦必须使用这一份，
    // 不能使用逐通道扫描过程中会瞬时变化的 pad_hit 原始阈值位图。
    output wire [N_STR*FRET_BITS-1:0] held_fret,
    output reg [N_STR*FRET_BITS-1:0] pluck_fret,
    // Complete-round raw highest fret differs from the qualified pluck fret.
    // This is a hint to WAIT, never permission to sound an unqualified pad.
    output reg [N_STR-1:0] change_pending,
    input wire [15:0] vibrato_threshold,
    input wire vibrato_use_aux, vibrato_owner_valid,
    input wire vibrato_enabled, vibrato_retrigger,
    input wire [1:0] vibrato_owner,
    input wire fast_scan,
    input wire [3:0] capo_fret,
    output reg  [13:0] dbg_dmax,
    // ---- ★ v59：调试快照（给遥测用）----
    //   把 FPGA **内部**每一路的 {flags, Δ, 基线} 暴露出来，上位机就能同时看到
    //   "芯片原始值"和"FPGA 认为的基线/Δ"。之前只看原始值，等于让上位机自己猜基线，
    //   结果上板出问题时只能看到"Δ 小却一直按下"这种自相矛盾的现象、无法定位。
    output wire [47:0] dbg_snap,          // {flags[3:0], delta[15:0], base[15:0]}
    output wire [15:0] dbg_cdc,           // raw CDC from the SAME recognition round
    input  wire [CH_BITS-1:0] dbg_snap_ch,// 想看哪一路
    output reg  [15:0] dbg_rounds         // 完成的轮数（证明状态机还在跑）
);

    localparam [CH_BITS-1:0] CH_LAST  = NCH - 1;
    localparam [3:0]         STR_LAST = N_STR - 1;
    localparam [2:0]         STR_MAX  = N_STR;
    localparam [15:0]        PLATEAU_DECAY16 = PLATEAU_DECAY;
    localparam [5:0]         N_FRET6  = N_FRET;

    // Addressed synchronous state RAM, not a shifting register bank.
    // Clear through the WRITE port after reset; never reset the RAM array.
    // One fetch cycle aligns both state RAM and external CDC RAM.
    localparam STW = 41;
    reg [CH_BITS-1:0] ch;
    reg [STW-1:0] state_mem [0:(1<<CH_BITS)-1] /* synthesis syn_ramstyle = "block_ram" */;
    reg [STW-1:0] pipe_seg;
    reg state_clear;
    reg [CH_BITS-1:0] clear_addr;
    assign cdc_addr = ch;
    wire [15:0] raw_now = CDC_RAM ? cdc_data : cdc_bus[ch*16 +: 16];

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin state_clear<=1'b1; clear_addr<=0; end
        else if (state_clear) begin
            if (clear_addr==CH_LAST) begin state_clear<=1'b0; clear_addr<=0; end
            else clear_addr<=clear_addr+1'b1;
        end
    end

    wire [15:0] st_base = pipe_seg[40:25];
    wire [15:0] st_plat = pipe_seg[24:9];
    wire [3:0] st_hitc = pipe_seg[8:5];
    wire [3:0] st_relc = pipe_seg[4:1];
    wire st_phit = pipe_seg[0];
    // ---------------- 通道 → (弦,品)：同步 ROM ----------------
    reg  [6:0] rom_addr;
    wire [7:0] sf_now;
    assign shared_map_addr=rom_addr;
    generate if(EXTERNAL_MAP)begin:g_external_map
      assign sf_now=shared_map_data;
    end else begin:g_local_map
      ch_map_rom u_chmap (.clk(clk), .addr(rom_addr), .sf(sf_now));
    end endgenerate

    // ---------------- 每弦状态 / 扫描累加 ----------------
    reg        s_press [0:N_STR-1];
    reg [FRET_BITS-1:0] s_fret [0:N_STR-1];
    reg [CH_BITS-1:0]   s_ch   [0:N_STR-1];
    reg [31:0] s_ms    [0:N_STR-1];
    reg [31:0] s_last  [0:N_STR-1];
    reg [3:0]  s_moves [0:N_STR-1];
    reg [3:0]  s_glide [0:N_STR-1];

    genvar hf_i;
    generate
        for (hf_i = 0; hf_i < N_STR; hf_i = hf_i + 1) begin : g_held_fret
            assign held_fret[hf_i*FRET_BITS +: FRET_BITS] =
                s_press[hf_i] ? s_fret[hf_i] : {FRET_BITS{1'b0}};
        end
    endgenerate
    // ★ v60：重拨"已武装"标志：手指按住时 Δ 掉下来过（老音符尾已衰减）才允许再次触发，
    //   这样连续快速点按同一格会**各出一个音**（用户反馈 #2：连按两三次只响一次）。
    //   用"先掉下去才算武装"而不是"值上升就触发"，避免按下瞬间的起振被当成第二次拨弦。
    reg        s_armed [0:N_STR-1];
    reg [15:0] d_lag   [0:N_STR-1];   // ★ v60：上一轮的 Δ（重拨"冲高"判据）

    reg [15:0]          best_d  [0:N_STR-1];
    reg [FRET_BITS-1:0] best_f  [0:N_STR-1];
    reg [FRET_BITS-1:0] live_f [0:N_STR-1];
    reg [FRET_BITS-1:0] observed_f [0:N_STR-1];
    reg [CH_BITS-1:0]   best_ch [0:N_STR-1];
    reg [15:0]          d_old_now   [0:N_STR-1];
    reg [15:0]          plat_old_now[0:N_STR-1];
    reg [31:0]          dt_press_now[0:N_STR-1];   // now_ms - s_ms（A 拍算好，C 拍直接用）
    reg [31:0]          dt_last_now [0:N_STR-1];

    function [7:0] keep_pct;                 // 弦1=A 41 / 弦2=E 55 / 弦3=C 26 / 弦4=G 23
        input [1:0] st;
        begin
            case (st)
                // ★ v60：原来的 41/55/26/23 是"老品格衰减到峰值的百分之几才算离开"，
                //   实测太严：手指滑过去时新品格往往还没等到老品格衰减那么多就先占了上风
                //   ⇒ **滑音几乎检测不到**（用户反馈 #3）。现在放宽到 80/88/70/65，
                //   含义仍是"老品格确实掉了 ≥20%~35% 的峰值"（=手指确实离开了那一格），
                //   但滑动很快时也能满足。同一弦两根手指同时按会被判成滑音 —— 那本来
                //   也不是有效手势（同一弦只能有一个音），可以接受。
                2'd0: keep_pct = 8'd80;
                2'd1: keep_pct = 8'd88;
                2'd2: keep_pct = 8'd70;
                default: keep_pct = 8'd65;
            endcase
        end
    endfunction

    // ★ 时序优化：把 "×keep_pct/100"、"×DOMI_PCT/100" 里的**除 100** 换成"乘常数 + 右移"。
    //   除 100 是个 15 ns 量级的长进位链（实测就是它把 S_STR_B 顶到 23.2 ns、超 0.95 ns）。
    //   2^20/100 = 10486：
    //     keep_th = (p_old × (keep_pct×10486)) >> 20
    //     domi    = (db    × (DOMI_PCT×10486)) >> 20
    //   与"除 100"的相对误差 < 0.01%，对门限判定的影响可忽略（门限本身是经验值）。
    function [19:0] ks_of;                   // keep_pct(st)/100 × 2^20
        input [1:0] st;
        begin
            // ★ v60：必须与上面的 keep_pct 一致！原来改 keep_pct 却忘了改这里，
            //   等于白改（keep_th 还是按 41/55/26/23 算）。位宽也要从 19 位加宽到 20 位：
            //   88×10486 = 922768 > 2^19-1，19 位会**溢出成错的阈值**。
            case (st)
                2'd0: ks_of = 20'd838880;    // 80 × 10486
                2'd1: ks_of = 20'd922768;    // 88 × 10486
                2'd2: ks_of = 20'd734020;    // 70 × 10486
                default: ks_of = 20'd681590; // 65 × 10486
            endcase
        end
    endfunction
    localparam [18:0] DOMI_SCALE = 19'd20972;    // DOMI_PCT(2) × 10486

    // ---------------- ms 时基 ----------------
    localparam MS_COUNT_W=(CLK_HZ/1000)<2 ? 1 : $clog2(CLK_HZ/1000);
    reg [MS_COUNT_W-1:0] ms_cnt;
    reg [31:0] now_ms;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin ms_cnt <= 32'd0; now_ms <= 32'd0; end
        else if (ms_cnt >= (CLK_HZ/1000 - 1)) begin ms_cnt <= 32'd0; now_ms <= now_ms + 32'd1; end
        else ms_cnt <= ms_cnt + 32'd1;
    end

    // ---------------- ★ v58：上电静置（panel_ready 之后再等 SETTLE_MS_CFG）----------------
    //   期间照常学基线（这样静置结束时基线已经跟准了），但**不判命中**、不出事件，
    //   避免开机的漂移/跳变被当成"所有品格同时按下"。
    localparam [31:0] SETTLE_TICKS = (CLK_HZ / 1000) * SETTLE_MS_CFG;
    localparam SETTLE_COUNT_W=SETTLE_TICKS<1 ? 1 : $clog2({1'b0,SETTLE_TICKS}+1);
    reg [SETTLE_COUNT_W-1:0] settle_cnt;
    reg        settle_done;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            settle_cnt <= 32'd0; settle_done <= 1'b0;
        end else if (!panel_ready) begin
            settle_cnt <= 32'd0; settle_done <= 1'b0;
        end else if (!settle_done) begin
            if (settle_cnt >= SETTLE_TICKS) settle_done <= 1'b1;
            else                            settle_cnt <= settle_cnt + 32'd1;
        end
    end

    // ---------------- 状态机 ----------------
    localparam S_IDLE  = 4'd0,
               S_PAD_A = 3'd1,   // 读 CDC + ROM 给出(弦,品)
               S_PAD_B = 3'd2,   // over / Δ
               S_PAD_C = 3'd3,   // 基线 / 平台 / 消抖
               S_PAD_D = 3'd4,   // 最强格 / 位图 / 状态 RAM 写回
               S_STR_A = 3'd5,   // 读表值 + 32 位时差
               S_STR_B = 3'd6,   // 两次乘法（keep_th / domi）
               S_STR_C = 3'd7,   // 比较 + 出事件 + 更新弦状态
               S_PAD_FETCH = 4'd8; // one cycle for synchronous state/CDC reads

    reg [3:0] capo_round;
    reg [3:0]  st;
    reg [3:0]  st_idx;
    reg [7:0]  learn_cnt;
    // ★ v58：基线是否已学收敛（学习期用对半收敛，轮数按 BASE_LEARN_N 计）
    wire learn_done = (learn_cnt >= {4'd0, BASE_LEARN_N});
    // ★ v59：17 位阈值比较，杜绝 base+THRESH 的 16 位回绕
    wire [16:0] base_th = {1'b0, st_base} + {1'b0, THRESH};
    reg [15:0] dmax_now;

    // ---- 各拍之间的寄存器 ----
    reg [15:0] raw_r, dnow_r, nplat_r, nbase_r;
    // Retimed RAM operand: captured in existing B, consumed in existing C.
    // No extra scan state, no threshold/debounce/gesture behaviour change.
    reg [15:0] plat_stage_r;
    reg        over_r, phit_r, phit_n_r, pad_ok_r, valid_r;
    reg [3:0]  hitc_r, relc_r;
    reg [2:0]  str_r;
    reg [4:0]  fret_r;
    reg [7:0]  pidx_r;
    reg [CH_BITS-1:0] ch_r;
    // 弦级
    reg [15:0] p_old_s, db_s, d_old_s, keep_th_s, domi_s;
    reg [FRET_BITS-1:0] f_s, sf_s;
    reg [CH_BITS-1:0]   chn_s;
    reg [2:0]  str_s;
    reg        any_s, newpress_s, fretchg_s, spress_s;

    // ★ v60：两条乘法的**显式宽中间结果**（否则会被上下文截断，见 S_STR_B 注释）
    //   keep_th = p_old × keep_pct%  = (p_old × keep_pct×10486) >> 20
    //   domi    = db    × DOMI_PCT%  = (db    × DOMI_PCT×10486) >> 20
    wire [35:0] keep_prod = p_old_s * ks_of(str_s[1:0]);
    wire [34:0] domi_prod = db_s * DOMI_SCALE;
    wire [14:0] domi_calc = domi_prod[34:20];

    // 18 frets can sum to 18*65535: widen beyond gv_host's 18-bit sum.
    localparam SUM_W = 16 + $clog2(N_FRET+1);
    localparam AUX_PRESENT = NCH==`TT_SCAN_NCH && N_STR==`TT_N_STR && N_FRET==`TT_N_FRET;
    wire start_round=st==S_IDLE && sample_valid && !sample_dup && panel_ready && !state_clear;
    wire aux_channel_in_pair;
    reg [15:0] aux_peak;
    reg [SUM_W-1:0] sum_acc [0:N_STR-1];
    wire [N_STR-1:0] vib_gate;
    wire [N_STR*16-1:0] vib_amplitudes;
    wire [N_STR-1:0] vib_updates=(st==S_STR_C)?({{(N_STR-1){1'b0}},1'b1}<<str_s):0;
    wire [N_STR-1:0] vib_reseed;wire [N_STR*SUM_W-1:0] vib_sums;
    genvar vd;generate for(vd=0;vd<N_STR;vd=vd+1)begin:g_vib_inputs
      assign vib_sums[vd*SUM_W+:SUM_W]=sum_acc[vd];
      assign vib_reseed[vd]=fretchg_s || chn_s!=s_ch[vd];
    end endgenerate
    generate if (AUX_PRESENT) begin : g_aux_vibrato
        // The new fretboard has five longitudinal sections, each with five
        // auxiliary electrodes bordering four strings. Order the channel IDs
        // from A's outer edge towards G's outer edge, including the two outer
        // electrodes. Adjacent strings intentionally share an inner electrode.
        function [13:0] fret_aux_pair;
            input [1:0] owner;
            input [FRET_BITS-1:0] fret;
            reg [34:0] electrodes;
            begin
                if (fret<=3) electrodes={7'd76,7'd72,7'd73,7'd74,7'd75};
                else if (fret<=6) electrodes={7'd80,7'd77,7'd78,7'd79,7'd81};
                else if (fret<=10) electrodes={7'd86,7'd82,7'd83,7'd84,7'd85};
                else if (fret<=14) electrodes={7'd87,7'd88,7'd89,7'd90,7'd91};
                else electrodes={7'd96,7'd92,7'd93,7'd94,7'd95};
                case (owner)
                    0: fret_aux_pair=electrodes[13:0];
                    1: fret_aux_pair=electrodes[20:7];
                    2: fret_aux_pair=electrodes[27:14];
                    3: fret_aux_pair=electrodes[34:21];
                endcase
            end
        endfunction
        reg source_seen, owner_valid_seen;
        reg [1:0] owner_seen, round_owner;
        reg [FRET_BITS-1:0] round_fret;
        reg [13:0] round_aux_pair;
        wire [FRET_BITS-1:0] owner_fret=s_fret[vibrato_owner];
        // Accumulate a consistent pair for the full round. If the sounding
        // string or its held fret changes, drop that round's gesture history.
        always @(posedge clk or negedge rst_n)
            if (!rst_n) begin
                round_owner<=0;round_fret<=0;round_aux_pair<=0;
            end else if (start_round) begin
                round_owner<=vibrato_owner;
                round_fret<=owner_fret;
                round_aux_pair<=fret_aux_pair(vibrato_owner,owner_fret);
            end
        wire aux_round_matches=round_owner==vibrato_owner && round_fret==owner_fret && owner_fret!=0;
        assign aux_channel_in_pair=ch_r==round_aux_pair[6:0] || ch_r==round_aux_pair[13:7];
        always @(posedge clk or negedge rst_n)
            if (!rst_n) begin source_seen<=1;owner_valid_seen<=0;owner_seen<=0;end
            else begin source_seen<=vibrato_use_aux;owner_seen<=vibrato_owner;owner_valid_seen<=vibrato_owner_valid;end
        wire source_changed=source_seen!=vibrato_use_aux || owner_seen!=vibrato_owner ||
                            owner_valid_seen!=vibrato_owner_valid;
        // Repeated plucks on the same held fret preserve physical movement history.
        // Source/owner/fret changes and release still clear the
        // detector and release its slide interlock as well as the audio gate.
        wire clear_vibrato=source_changed || !vibrato_enabled ||
                            (vibrato_use_aux && !aux_round_matches);
        wire selected_active,selected_gate;
        wire [15:0] selected_amplitude;
        wire [SUM_W-1:0] selected_sum=vibrato_use_aux ? {{(SUM_W-16){1'b0}},aux_peak} : sum_acc[str_s[1:0]];
        wire [15:0] selected_pad=vibrato_use_aux ? aux_peak : d_old_s;
        // Only the latest sounded voice can bend. The detector is shared
        // between the two sources, clearing its history on a source/owner change.
        vibrato_detect #(.W(SUM_W),.RUNTIME_CONFIG(1),.SHARED_CONTEXT(1),.AMP_ABS(VIB_AMP_ABS)) u_selected_vibrato (
            .clk(clk),.rst_n(rst_n),.update(st==S_STR_C && str_s[1:0]==vibrato_owner),
            .held(panel_ready && settle_done && learn_done && vibrato_owner_valid && spress_s && any_s),
            .reseed(!vibrato_use_aux && vib_reseed[str_s[1:0]]),.delta_sum(selected_sum),.delta_pad(selected_pad),
            .trigger_threshold(vibrato_threshold),.fast_scan(fast_scan),
            .context_load(clear_vibrato),.context_in({(2*SUM_W+60){1'b0}}),.context_out(),
            .active(selected_active),.gate(selected_gate),.amplitude(selected_amplitude));
        wire [N_STR-1:0] owner_mask={{(N_STR-1){1'b0}},1'b1}<<vibrato_owner;
        assign vib_str=selected_active && !clear_vibrato && vibrato_owner_valid ? owner_mask : 0;
        assign vib_gate=selected_gate && !clear_vibrato && vibrato_owner_valid ? owner_mask : 0;
        always @(*) vib_amp=selected_amplitude;
    end else begin : g_fret_vibrato
    assign aux_channel_in_pair=1'b0;
    vibrato_shared #(.W(SUM_W),.N_STR(N_STR),.RUNTIME_CONFIG(RUNTIME_CONFIG),.AMP_ABS(VIB_AMP_ABS),.REL_SH(VIB_REL_SH),
      .AGE_MAX(VIB_AGE_MAX),.MIN_GAP(VIB_MIN_GAP),.DC_SH(VIB_DC_SH),.AMP_SH(VIB_AMP_SH)) u_vibrato_shared(
      .clk(clk),.rst_n(rst_n),.update(vib_updates),.held({N_STR{spress_s && any_s}}),.reseed(vib_reseed),
      .delta_sum(vib_sums),.delta_pad({N_STR{d_old_s}}),.trigger_threshold(vibrato_threshold),.fast_scan(fast_scan),
      .active(vib_str),.gate(vib_gate),.amplitude(vib_amplitudes));
    integer va;
    always @(*) begin
        vib_amp=0;
        for (va=0; va<N_STR; va=va+1)
            if (vib_amplitudes[va*16+:16]>vib_amp)
                vib_amp=vib_amplitudes[va*16+:16];
    end

    end endgenerate
    assign vib_on = |vib_str;

    integer i, k;

    // Single write address / synchronous read, no asynchronous array reset.
    // Read-during-write data is not consumed: writes occur in D, reads in B.
    always @(posedge clk) begin
        if (state_clear) state_mem[clear_addr] <= {STW{1'b0}};
        else if (st==S_PAD_D)
            state_mem[ch_r] <= {nbase_r,nplat_r,hitc_r,relc_r,phit_n_r};
        if (state_clear && ch==clear_addr) pipe_seg <= {STW{1'b0}};
        else if (st==S_PAD_D && ch==ch_r)
            pipe_seg <= {nbase_r,nplat_r,hitc_r,relc_r,phit_n_r};
        else pipe_seg <= state_mem[ch];
    end

    // Diagnostic snapshots use a separate block RAM port, never the CDC/state
    // read port. No asynchronous array reset or 72-channel CDC register mirror.
    // Explicit write-through also avoids unsupported read-before-write modes.
    (* syn_ramstyle = "block_ram" *) reg [51:0] snap_mem [0:(1<<CH_BITS)-1];
    reg [51:0] snap_q;
    wire snap_we = state_clear || (st==S_PAD_D);
    wire [CH_BITS-1:0] snap_wa = state_clear ? clear_addr : ch_r;
    wire [51:0] snap_wd = state_clear ? 52'd0 :
        {raw_r, pad_ok_r, valid_r, phit_n_r, over_r, dnow_r, nbase_r};
    assign dbg_snap = {12'd0, snap_q[35:0]};
    assign diag_valid=st==S_PAD_D && !state_clear;
    assign diag_channel=ch_r;
    assign diag_data={raw_r,pad_ok_r,valid_r,phit_n_r,over_r,dnow_r,nbase_r};
    assign dbg_cdc = snap_q[51:36];
    always @(posedge clk) begin
        if (snap_we) snap_mem[snap_wa] <= snap_wd;
        if (snap_we && snap_wa==dbg_snap_ch) snap_q <= snap_wd;
        else snap_q <= snap_mem[dbg_snap_ch];
    end
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) dbg_rounds <= 16'd0;
        else if (!state_clear && st == S_PAD_D) begin
            if (ch_r >= CH_LAST) dbg_rounds <= dbg_rounds + 16'd1;
        end
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            capo_round<=0;st <= S_IDLE; ch <= {CH_BITS{1'b0}}; st_idx <= 4'd0; learn_cnt <= 8'd0;
            rom_addr <= 7'd0;
            valid_r <= 1'b0;
            aux_peak <= 0;
            note_ev <= 1'b0; glide_ev <= 1'b0; note_voice <= 6'd0; glide_target <= 6'd0;
            glide_string <= 2'd0;
            pluck_fret<=0;
            change_pending<=0;
            gain <= GAIN_DEF; pad_hit <= {(N_STR*N_FRET){1'b0}};
            str_press <= {N_STR{1'b0}}; dbg_dmax <= 14'd0; dmax_now <= 16'd0;
            plat_stage_r<=0; raw_r <= 16'd0; dnow_r <= 16'd0; nplat_r <= 16'd0; nbase_r <= 16'd0;
            over_r <= 1'b0; phit_r <= 1'b0; phit_n_r <= 1'b0; pad_ok_r <= 1'b0;
            hitc_r <= 4'd0; relc_r <= 4'd0;
            str_r <= 3'd0; fret_r <= 5'd0; pidx_r <= 8'd0; ch_r <= {CH_BITS{1'b0}};
            p_old_s <= 16'd0; db_s <= 16'd0; d_old_s <= 16'd0;
            keep_th_s <= 16'd0; domi_s <= 16'd0; f_s <= 5'd0; sf_s <= 5'd0;
            chn_s <= {CH_BITS{1'b0}}; str_s <= 3'd0;
            any_s <= 1'b0; newpress_s <= 1'b0; fretchg_s <= 1'b0; spress_s <= 1'b0;
            for (i = 0; i < N_STR; i = i + 1) begin
                s_press[i] <= 1'b0; s_fret[i] <= {FRET_BITS{1'b0}}; s_ch[i] <= {CH_BITS{1'b0}};
                s_ms[i] <= 32'd0; s_last[i] <= 32'd0; s_moves[i] <= 4'd0; s_glide[i] <= 4'd0;
                s_armed[i] <= 1'b0;      // ★ v60
                d_lag[i]   <= 16'd0;     // ★ v60
                sum_acc[i] <= 0;
                live_f[i]<=0;
                observed_f[i]<=0;
                best_d[i] <= 16'h0000; best_f[i] <= {FRET_BITS{1'b0}}; best_ch[i] <= {CH_BITS{1'b0}};
                d_old_now[i] <= 16'h0000; plat_old_now[i] <= 16'h0000;
                dt_press_now[i] <= 32'd0; dt_last_now[i] <= 32'd0;
            end
        end else begin
            note_ev  <= 1'b0;
            glide_ev <= 1'b0;

            case (st)
            // ================= 等一轮数据 =================
            S_IDLE: begin
                capo_round<=CAPO_ENABLED ? capo_fret : 4'd0;
                if (start_round) begin
                    ch       <= {CH_BITS{1'b0}};
                    rom_addr <= 7'd0;                 // → S_PAD_A 时 sf_now = mem[0]
                    dmax_now <= 16'd0;
                    aux_peak <= 0;
                    st       <= S_PAD_FETCH;
                    if (learn_cnt < BASE_LEARN_N) learn_cnt <= learn_cnt + 8'd1;
                    for (i = 0; i < N_STR; i = i + 1) begin
                        sum_acc[i] <= 0;
                        live_f[i]<=0;
                        observed_f[i]<=0;
                        best_d[i] <= 16'h0000;
                        best_f[i] <= {FRET_BITS{1'b0}};
                        best_ch[i] <= {CH_BITS{1'b0}};
                    end
                end
            end

            S_PAD_FETCH: st <= S_PAD_A;
            // ================= A 拍：读 CDC + 记下(弦,品) =================
            S_PAD_A: begin
                raw_r    <= raw_now;
                // ★ v58：读数合法性（固件的 CDC_SANE_LO/HI）——贴量程/读失败的值
                //   既不能当基线，也不能参与命中判定。
                valid_r  <= (raw_now > CDC_SANE_LO) && (raw_now < CDC_SANE_HI);
                str_r    <= sf_now[7:5];
                fret_r   <= sf_now[4:0];
                ch_r     <= ch;
                pad_ok_r <= (sf_now[4:0] != 5'd0) & (sf_now[7:5] >= 3'd1) & (sf_now[7:5] <= STR_MAX);
                pidx_r   <= ((sf_now[7:5] - 3'd1) * N_FRET6)
                           + (sf_now[4:0] - 5'd1);
                rom_addr <= ch + {{(CH_BITS-1){1'b0}}, 1'b1};   // 下一通道的地址，提前一拍
                st <= S_PAD_B;
            end

            // ================= B 拍：over / Δ =================
            S_PAD_B: begin
                plat_stage_r <= st_plat;
                // ★ v58：命中判定的四个必要条件：
                //   ① 读数合法（不是贴量程的垃圾）② 已过静置期
                //   ③ **基线已经学收敛**（learn_cnt 到 BASE_LEARN_N；学习期用对半收敛，
                //      12 轮后基线误差 < 1 count，所以这一条是"基线可用"的硬保证）
                //   ④ 超过阈值
                //   以前只要 raw > base+THRESH 就算命中，而 base 复位值是 0 ⇒ 上电瞬间
                //   12 个品格全被当成"按下"；又因为命中期间基线冻结（!st_phit 才跟），
                //   基线永远学不上去 ⇒ **永久粘死**、四根弦开机各响一声后再也没声。
                // ★ v59：比较改成 **17 位**——`st_base + THRESH` 在 16 位里会溢出回绕，
                //   一旦基线被顶到 64000 以上，`raw > 回绕后的小值` 就恒成立 ⇒ 永久"按下"。
                over_r <= valid_r && settle_done && learn_done && ({1'b0, raw_r} > base_th);
                dnow_r <= (raw_r > st_base) ? (raw_r - st_base) : 16'h0000;
                if ((!AUX_PRESENT || pad_ok_r) && (raw_r > st_base ? (raw_r - st_base) : 16'h0000) > dmax_now)
                    dmax_now <= (raw_r > st_base ? (raw_r - st_base) : 16'h0000);
                st <= S_PAD_C;
            end

            // ================= C 拍：基线 / 平台 / 消抖 =================
            S_PAD_C: begin
                begin : pad_state
                    reg [15:0] nbase, nplat;
                    reg [3:0]  hc, rc;
                    reg        ph_n;
                    // ★ v58 修 bug：基线学习原来用 >>BASE_ALPHA(=7) 的慢跟踪，
                    //   从复位值 0 出发每轮只走 1/128 ⇒ 8 轮学习期结束时基线才 ~1000
                    //   （而静止读数是 16000+）⇒ 只要一命中就冻结基线 ⇒ 永久粘死。
                    //   现在：学习期用"对半收敛"（每轮走一半，8 轮后误差 <1%），
                    //   学习完成后的慢跟踪逻辑保持不变（没命中时 1/128 慢慢跟）。
                    // ★ v59 修 bug（上板现象：Δ 很小却"一直显示按下"、且再不出声）：
                    //   `raw_r - st_base` 是**无符号**减法。只要读数**低于**基线（上电漂移、
                    //   松手回落都会发生），差值就下溢成一个巨大的正数，于是
                    //     base += (下溢值)>>1  ⇒ 基线被一路顶到 60000 以上
                    //   ⇒ ① `base + THRESH` 16 位**溢出回绕** ⇒ raw > 回绕后的小值
                    //        ⇒ over_r 恒为 1 ⇒ 那一格永久"按下"、s_press 锁死不再出声；
                    //      ② 同时 raw < base ⇒ Δ 记 0 ⇒ **dmax 看起来很小（上板 15）**，
                    //        这正是"Δ 小却一直按下"这种自相矛盾现象的来源。
                    //   现在按方向分两种写法，全程无下溢。
                    if (!valid_r)
                        nbase = st_base;                       // 非法读数：基线原地不动
                    else if (learn_cnt < BASE_LEARN_N) begin
                        // 快速学习（对半收敛）：base+(raw-base)/2，且必须判方向
                        if (raw_r >= st_base) nbase = st_base + ((raw_r - st_base) >> 1);
                        else                  nbase = st_base - ((st_base - raw_r) >> 1);
                    end else if (!st_phit) begin
                        if (raw_r >= st_base) nbase = st_base + ((raw_r - st_base) >> BASE_ALPHA);
                        else                  nbase = st_base - ((st_base - raw_r) >> BASE_ALPHA);
                    end else
                        nbase = st_base;                       // 命中期间冻结（与原逻辑一致）
                    // 平台值：最大值跟随 + 慢速回落
                    nplat = (dnow_r >= plat_stage_r) ? dnow_r
                            : (plat_stage_r - ((plat_stage_r - dnow_r + PLATEAU_DECAY16 - 16'd1)
                                          / PLATEAU_DECAY16));
                    // 消抖
                    if (over_r) begin
                        hc   = (st_hitc >= TRIG_N) ? st_hitc : (st_hitc + 4'd1);
                        rc   = 4'd0;
                        ph_n = (st_hitc + 4'd1 >= TRIG_N) ? 1'b1 : st_phit;
                    end else begin
                        hc   = 4'd0;
                        rc   = (st_relc >= REL_N) ? st_relc : (st_relc + 4'd1);
                        ph_n = (st_relc + 4'd1 >= REL_N) ? 1'b0 : st_phit;
                    end
                    nbase_r  <= nbase;
                    nplat_r  <= nplat;
                    hitc_r   <= hc;
                    relc_r   <= rc;
                    phit_n_r <= ph_n;
                end
                st <= S_PAD_D;
            end

            // ================= D 拍：最高有效品位 / 位图 / 状态 RAM 写回 =================
            S_PAD_D: begin
                if (pad_ok_r) begin
                    pad_hit[pidx_r] <= over_r && fret_r>{1'b0,capo_round};
                    // ★ v60 修 bug（"上位机检测对了、但音高不对/部分品格不响"的根因）：
                    //   这里原来判的是 `phit_r`，而 phit_r 是在**本状态末尾**才被
                    //   `phit_r <= phit_n_r` 更新的 ⇒ 它此刻装的是**上一路**的 phit！
                    //   于是"最强品格"被记到了**下一路**的 (弦,品) 上：
                    //     · pad_hit/上位机显示是对的（用的是本路的 over_r/pidx_r）
                    //     · 但 best_f/best_d 偏了一路 ⇒ 出声的音高是隔壁那一格
                    //     · 隔壁不是品格通道时（辅助/未用级）干脆不出声
                    //   ⇒ 实测：按 弦1品1 出的是 弦2品3 的音、按 弦1品2 完全没声。
                    //   修法：用 S_PAD_C 刚为本路算出来的 phit_n_r。
                    if (phit_n_r && over_r && fret_r>{1'b0,capo_round} && fret_r>live_f[str_r-3'd1])
                        live_f[str_r-3'd1]<=fret_r;
                    if (over_r && fret_r>{1'b0,capo_round} && fret_r>observed_f[str_r-3'd1])
                        observed_f[str_r-3'd1]<=fret_r;
                    if (phit_n_r && fret_r>{1'b0,capo_round}) begin
                        // 按弦规则与真实琴弦一致：最高的有效按弦位置决定音高。
                        // 多指同时按下时，低品压力更大或Δ波动都不能抢占高品。
                        // 当前72路ROM中每个(弦,品)恰好一项，不需要同品幅度仲裁。
                        // best_d/best_ch仍随被选中的品位一起记录，供滑音/重拨使用。
                        if (fret_r > best_f[str_r-3'd1]) begin
                            best_d [str_r-3'd1] <= dnow_r;
                            best_f [str_r-3'd1] <= fret_r;
                            best_ch[str_r-3'd1] <= ch_r;
                        end
                    end
                end
                for (k = 0; k < N_STR; k = k + 1) begin
                    if (s_press[k] && (ch_r == s_ch[k])) begin
                        d_old_now[k]    <= dnow_r;
                        plat_old_now[k] <= nplat_r;
                    end
                end
                if (AUX_PRESENT && aux_channel_in_pair && valid_r && dnow_r>aux_peak) aux_peak<=dnow_r;
                // v63: slide transfers capacitance between frets; their sum stays stable.
                if (pad_ok_r && valid_r && fret_r>{1'b0,capo_round} && str_r>=1 && str_r<=STR_MAX)
                    sum_acc[str_r-1] <= sum_acc[str_r-1] + dnow_r;
                // 通道状态由独立 RAM 写端口在本拍写回，其他地址保持不动。
                phit_r <= phit_n_r;

                if (ch_r >= CH_LAST) begin
                    st       <= S_STR_A;
                    st_idx   <= 4'd0;
                    dbg_dmax <= dmax_now[13:0];
                end else begin
                    ch <= ch_r + {{(CH_BITS-1){1'b0}}, 1'b1};
                    st <= S_PAD_FETCH;
                end
            end

            // ================= 弦 A 拍：读表值 + 32 位时差 =================
            S_STR_A: begin
                str_s      <= {1'b0, st_idx[1:0]};
                f_s        <= best_f[st_idx[1:0]];
                db_s       <= best_d[st_idx[1:0]];
                d_old_s    <= d_old_now[st_idx[1:0]];
                p_old_s    <= plat_old_now[st_idx[1:0]];
                chn_s      <= best_ch[st_idx[1:0]];
                spress_s   <= s_press[st_idx[1:0]];
                sf_s       <= s_fret[st_idx[1:0]];
                any_s      <= (best_f[st_idx[1:0]] != {FRET_BITS{1'b0}});
                newpress_s <= (best_f[st_idx[1:0]] != {FRET_BITS{1'b0}}) && !s_press[st_idx[1:0]];
                fretchg_s  <= (best_f[st_idx[1:0]] != {FRET_BITS{1'b0}}) && s_press[st_idx[1:0]] &&
                              (best_f[st_idx[1:0]] != s_fret[st_idx[1:0]]);
                dt_press_now[st_idx[1:0]] <= now_ms - s_ms[st_idx[1:0]];
                dt_last_now [st_idx[1:0]] <= now_ms - s_last[st_idx[1:0]];
                st <= S_STR_B;
            end

            // ================= 弦 B 拍：两次乘法（keep_th / domi）=================
            S_STR_B: begin
                // ★ v60 修 bug（"滑音几乎检测不到"的根因）：
                //   `keep_th_s <= (p_old_s * ks_of(...)) >> 20;` 看着没问题，但 Verilog 里
                //   **乘积的位宽 = 上下文位宽**（这里被 >>20 的左边定成 20 位），
                //   16 位 × 20 位 = 36 位的乘积被**截断成 20 位** ⇒ 结果恒为 0！
                //   而滑音的前提正是 `d_old_s <= keep_th_s`（老品格衰减到峰值的一定比例以下）
                //   ⇒ 这个条件永远不成立 ⇒ **滑音永远检测不到**。
                //   修法：用显式宽度的 wire 承接乘积，再取高位。
                keep_th_s <= keep_prod[35:20];
                domi_s    <= (domi_calc > DOMI_ABS) ? {1'b0, domi_calc} : DOMI_ABS;
                st <= S_STR_C;
            end

            // ================= 弦 C 拍：比较 + 出事件 + 更新弦状态 =================
            S_STR_C: begin
                // Both candidates use TRIG_N qualification; no single-scan noise shortcut.
                pluck_fret[str_s*FRET_BITS+:FRET_BITS]<=live_f[str_s]!=0 ? live_f[str_s] : f_s;
                // Publish together with the qualified note, after ALL pads
                // have been processed. Lower-pad noise cannot delay a live
                // higher fret. Release hysteresis remains intact.
                change_pending[str_s]<=observed_f[str_s]!=(live_f[str_s]!=0 ? live_f[str_s] : f_s);
                if (newpress_s) begin
                    note_ev    <= 1'b1;
                    note_voice <= str_fret2note(str_s + 3'd1, f_s);
                    s_press[str_s[1:0]] <= 1'b1;
                    s_fret [str_s[1:0]] <= f_s;
                    s_ch   [str_s[1:0]] <= chn_s;
                    s_ms   [str_s[1:0]] <= now_ms;
                    s_last [str_s[1:0]] <= now_ms;
                    s_moves[str_s[1:0]] <= 4'd0;
                    s_glide[str_s[1:0]] <= 4'd0;
                    s_armed[str_s[1:0]] <= 1'b0;      // ★ v60：刚拨过，要等值掉下来才允许重拨
                    str_press[str_s[1:0]] <= 1'b1;
                end else if (!any_s && spress_s) begin
                    s_press[str_s[1:0]] <= 1'b0;
                    s_fret [str_s[1:0]] <= {FRET_BITS{1'b0}};
                    s_armed[str_s[1:0]] <= 1'b0;
                    str_press[str_s[1:0]] <= 1'b0;
                end else if (fretchg_s) begin
                    if ((d_old_s <= keep_th_s) &&
                        (db_s >= (d_old_s + domi_s)) &&
                        (dt_press_now[str_s[1:0]] >= SETTLE_MS) &&
                        (dt_last_now [str_s[1:0]] >= GAP_MS) && !vib_gate[str_s[1:0]]) begin
                        glide_ev     <= 1'b1;
                        glide_target <= str_fret2note(str_s + 3'd1, f_s);
                        glide_string <= str_s[1:0];
                        s_glide[str_s[1:0]] <= s_glide[str_s[1:0]] + 4'd1;
                    end
                    if (s_moves[str_s[1:0]] < 4'hF)
                        s_moves[str_s[1:0]] <= s_moves[str_s[1:0]] + 4'd1;
                    s_fret[str_s[1:0]] <= f_s;
                    s_ch  [str_s[1:0]] <= chn_s;
                    s_armed[str_s[1:0]] <= 1'b0;      // 换过品，重新等衰减
                    s_last[str_s[1:0]] <= now_ms;
                end else if (spress_s) begin
                    // ---- ★ v60 新增：同一格的"重拨"（连续快按） ----
                    //   用户短时间按了两三次，上位机能看出每次按下，但喇叭只响一次：
                    //   原因是松手后 REL_N 轮消抖（约 220 ms）里 s_press 一直是 1，
                    //   而 newpress 要求 `!s_press` ⇒ 第二次按根本不出事件。
                    //   这里补一条"按住状态下的再次触发"：
                    //     ① v64：Δ 先掉到峰值的 12.5% 以下（确实抬手）⇒ 武装 s_armed
                    //     ② 之后 Δ 相对**上一轮**冲高 domi 以上 ⇒ 再拨一次
                    //   ⚠ 必须和"上一轮"比（d_lag），不能和 d_old_s 比 —— d_old_s 与 db_s
                    //     在同一轮里由同一路写进去，自己跟自己比永远不成立（第一版就踩了）。
                    // 三次过零后确认持续揉弦，屏蔽压力起伏产生的重拨。
                    if (d_old_s <= (p_old_s >> 3)) s_armed[str_s[1:0]] <= 1'b1;
                    if (s_armed[str_s[1:0]] && !vib_gate[str_s[1:0]] &&
                        (d_old_s >= d_lag[str_s[1:0]]) &&
                        ((d_old_s - d_lag[str_s[1:0]]) >= domi_s) &&
                        (dt_last_now[str_s[1:0]] >= GAP_MS)) begin
                        note_ev    <= 1'b1;
                        note_voice <= str_fret2note(str_s + 3'd1, f_s);
                        s_last[str_s[1:0]] <= now_ms;
                        s_armed[str_s[1:0]] <= 1'b0;
                    end
                end
                // 记录本轮 Δ 供下一轮比较（重拨的"冲高"判据用）
                d_lag[str_s[1:0]] <= spress_s ? d_old_s : 16'd0;

                if (st_idx >= STR_LAST) begin
                    st       <= S_IDLE;
                    rom_addr <= 7'd0;
                end else begin
                    st_idx <= st_idx + 4'd1;
                    st     <= S_STR_A;
                end
            end

            default: st <= S_IDLE;
            endcase
        end
    end

endmodule
