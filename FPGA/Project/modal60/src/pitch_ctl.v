// ============================================================================
// pitch_ctl.v —— 每引擎 1 例：滑音斜坡 + 颤音 LFO/包络 -> pitch_mul[18:0]
// ----------------------------------------------------------------------------
// 依据：doc/FPGA电子尤克里里_滑音颤音实现计划.md §2.1/§3.2（音高比例 r(t) 调制）。
// 语义：p_ph += round(t_inc × r(t))；r(t)=1.0 时与旧引擎逐位一致。
//   r(t) = r_glide(t) × (1 + m(t))
//   r_glide：滑音比例（1.0 起步，事件后按固定时长线性斜坡到目标比例）
//   m(t)   ：颤音微调 = 深度 × 正弦波LFO(≈6Hz) × 包络渐入
//
// 定点（Q15/Q16）：
//   r（pitch_mul）        ：19bit 无符号，低 15 位小数，1.0 = 0x8000；
//   内部分母 R（Q16）      ：1.0 = 0x10000（便于表示 4.0 的两倍频程滑音）；
//   target/step（Q16）     ：semitone 表，1.0 = 0x10000；
//   tri / vib_env / m（Q15 有符号）：1.0 = 0x8000。
//
// 计算时机：每次 sample_tick（≈48.8 kHz）更新一次；引擎窗口起点锁存 pitch_mul
//           （由 additive4_tone v13 的 PITCH_MOD 路径完成，见 top_gv_host.v）。
//
// 注：滑音落点默认用 12-TET 等程比例（self-contained）。若要用实测基频比例
//     （计划 §3.3，更准），把 ev_valid 时的 target 换成
//     (pt_inc(target,0) << 16) / pt_inc(anchor,0) 即可——本模块接口（ev_anchor/
//     ev_target）已为此设计。
//
// 参数：NV（本引擎声部数，用于 voice_active 门控）、FADE_MS（颤音渐入时长）。
// ============================================================================
module pitch_ctl #(
    parameter NV         = 7,
    parameter FADE_MS    = 200,    // 颤音渐入/渐出时长 ms
    parameter GLIDE_MS   = 60     // 滑音时长 ms（参考值；本参考实现用固定时长把比例斜坡走完）
) (
    input  wire              clk,
    input  wire              rst_n,
    input  wire              sample_tick,

    // ---- 颤音 ----
    input  wire              vib_on,      // 开/关（decoder VIB）
    input  wire [7:0]        vib_rate_x10,// 速率×10（decoder VIB_SET）
    input  wire [7:0]        vib_depth,   // 深度 音分（decoder VIB_SET）

    // ---- 滑音事件（顶层事件判定发出；仅"锚定引擎"的 pitch_ctl 收到有效事件）----
    input  wire              ev_valid,    // 滑音事件脉冲
    input  wire [5:0]        ev_anchor,   // 锚定全局音符 0..32（6bit：吉他库 33 音）
    input  wire [5:0]        ev_target,   // 目标全局音符 0..32
    input  wire [15:0]       glide_ms,    // 滑音时长 ms（decoder GLIDE_SET）

    // ---- 拨弦重触发：新 NOTE 走"重拨"时把滑音比例复位 1.0 ----
    input  wire              note_retrig,
    // ---- 引擎可闻监测（任一泛音仍在响）----
    input  wire [NV-1:0]     voice_active,

    output reg [18:0]        pitch_mul,   // 音高比例 Q15（1.0=0x08000；19bit -> 上限 8.0=3 个八度）
    output reg               gliding,     // 正在滑音
    output reg               vib_active   // 颤音已激活（包络升到位）
);

    // ---------------- 常量 ----------------
    // 1.0 在各表示下的值
    localparam [20:0] ONE_Q16  = 21'h010000;   // 1.0 in Q16
    localparam [18:0] ONE_Q15  = 19'h08000;    // 1.0 in Q15 (pitch_mul 默认)
                                               // 19bit：整数部分 4bit -> 比例上限 8.0（3 个八度），
                                               // 吉他库琴弦 33 品 = 最大 32 半音（6.35）才滑得完

    // N_GLIDE = GLIDE_MS × Fs/1000 ≈ GLIDE_MS × 48.828（整常数，除以它做"走完斜坡"的步长）
    //   常数除法综合器会优化成"乘倒数+移位"，避免逐拍大除法器（时序风险）。
    // VIB_INC = rate(Hz) × 2^32/Fs = (rate_x10/10) × 87960.9 ≈ rate_x10 × 8796
    // FADE_STEP = 32768 / (Fs × FADE_MS/1000)
    localparam integer N_GLIDE  = (48828 * GLIDE_MS + 500) / 1000;
    localparam integer FADE_STEP = (32768 * 1000) / (48828 * FADE_MS);
    // 若 FADE_STEP 算出来 <1，钳到 1（避免包络永远到不了）
    localparam integer VIB_FADE_STEP = (FADE_STEP < 1) ? 1 : FADE_STEP;
    // 滑音步长用"乘倒数+移位"：step = (|Δ| × STEP_K) >> STEP_S ≈ |Δ|/N_GLIDE
    //   （编译期常数算出，替代运行时大除法器 -> 省 ALU）
    localparam integer STEP_S = 20;
    localparam integer STEP_K = ((1 << STEP_S) + N_GLIDE / 2) / N_GLIDE;

    // ---------------- 内部状态 ----------------
    reg [20:0] r_acc;           // 滑音比例 Q16（1.0=0x10000）
    reg        gliding_r;
    reg [20:0] glide_target;    // 目标比例 Q16
    reg [20:0] glide_mag;       // |target - r_acc|
    reg        glide_dir;       // 1=升, 0=降
    localparam GLIDE_FRAC = 13;
    reg [20:0] glide_step;      // Q16 整数部分
    reg [GLIDE_FRAC-1:0] glide_step_frac, glide_carry;
    // 滑音事件锁存：ev_valid 是 1 拍脉冲，而 sample_tick 每 ~1024 拍才来一次，
    // 必须锁存 anchor/target 等下一拍 sample_tick 再处理，否则事件几乎全被漏掉。
    reg        ev_pending;
    reg [5:0]  ev_anchor_l;     // 全局声部号 0..32（6bit：吉他库 33 音）
    reg [5:0]  ev_target_l;
    // ---- 流水寄存器（原本"除法+查表+乘法+合成乘"全挤在 1 拍内，50MHz 时序不收敛：
    //      实测 Fmax 34.7MHz / 最差路径 -8.83ns（都在本模块）。采样周期有 1024 拍余量，
    //      所以把这两条链拆成 3~4 拍流水。----
    reg [1:0]  ev_ph;           // 事件处理流水阶段（0=空闲）
    reg signed [8:0] delta_c_r; // 拍1：锁存限幅后的 Δ
    reg [20:0] ratio_tgt_r;     // 拍2：查表得到的目标比例 Q16
    reg [3:0]  syn_ph;          // 合成流水阶段（0=空闲）
    reg signed [15:0] triq_r;   // 拍1：正弦波 Q15（保留旧寄存器名）
    reg signed [15:0] m_eff_r;  // 拍2：深度调制 m_eff
    reg signed [15:0] m_r;      // 拍3：再乘渐入包络得到 m
    reg [20:0] r_glide_r;       // 拍1：锁存 r_glide

    // ---------------- 颤音 -----------
    reg [31:0] lfo_ph;
    reg [15:0] vib_env;         // Q15 包络（0..32768）

    wire use_vib = vib_on;
    wire [15:0] depth_q15 = vib_depth * 16'd19;   // 深度音分 -> Q15（近似 2^(c/1200)-1，c 小误差<0.5c）

    function [15:0] sine_q;
        input [5:0] a;
        begin
            case (a)
                6'd0:  sine_q = 16'd402;    6'd1:  sine_q = 16'd1206;
                6'd2:  sine_q = 16'd2009;   6'd3:  sine_q = 16'd2811;
                6'd4:  sine_q = 16'd3612;   6'd5:  sine_q = 16'd4410;
                6'd6:  sine_q = 16'd5205;   6'd7:  sine_q = 16'd5998;
                6'd8:  sine_q = 16'd6786;   6'd9:  sine_q = 16'd7571;
                6'd10: sine_q = 16'd8351;   6'd11: sine_q = 16'd9126;
                6'd12: sine_q = 16'd9896;   6'd13: sine_q = 16'd10659;
                6'd14: sine_q = 16'd11417;  6'd15: sine_q = 16'd12167;
                6'd16: sine_q = 16'd12910;  6'd17: sine_q = 16'd13645;
                6'd18: sine_q = 16'd14372;  6'd19: sine_q = 16'd15090;
                6'd20: sine_q = 16'd15800;  6'd21: sine_q = 16'd16499;
                6'd22: sine_q = 16'd17189;  6'd23: sine_q = 16'd17869;
                6'd24: sine_q = 16'd18537;  6'd25: sine_q = 16'd19195;
                6'd26: sine_q = 16'd19841;  6'd27: sine_q = 16'd20475;
                6'd28: sine_q = 16'd21096;  6'd29: sine_q = 16'd21705;
                6'd30: sine_q = 16'd22301;  6'd31: sine_q = 16'd22884;
                6'd32: sine_q = 16'd23452;  6'd33: sine_q = 16'd24007;
                6'd34: sine_q = 16'd24547;  6'd35: sine_q = 16'd25072;
                6'd36: sine_q = 16'd25582;  6'd37: sine_q = 16'd26077;
                6'd38: sine_q = 16'd26556;  6'd39: sine_q = 16'd27019;
                6'd40: sine_q = 16'd27466;  6'd41: sine_q = 16'd27896;
                6'd42: sine_q = 16'd28310;  6'd43: sine_q = 16'd28706;
                6'd44: sine_q = 16'd29085;  6'd45: sine_q = 16'd29447;
                6'd46: sine_q = 16'd29791;  6'd47: sine_q = 16'd30117;
                6'd48: sine_q = 16'd30424;  6'd49: sine_q = 16'd30714;
                6'd50: sine_q = 16'd30985;  6'd51: sine_q = 16'd31237;
                6'd52: sine_q = 16'd31470;  6'd53: sine_q = 16'd31685;
                6'd54: sine_q = 16'd31880;  6'd55: sine_q = 16'd32057;
                6'd56: sine_q = 16'd32213;  6'd57: sine_q = 16'd32351;
                6'd58: sine_q = 16'd32469;  6'd59: sine_q = 16'd32567;
                6'd60: sine_q = 16'd32646;  6'd61: sine_q = 16'd32705;
                6'd62: sine_q = 16'd32745;  6'd63: sine_q = 16'd32765;
                default: sine_q = 16'd0;
            endcase
        end
    endfunction

    function signed [15:0] sin_q15;        // 32bit 相位 -> 16bit 有符号正弦（象限镜像）
        input [31:0] ph;
        begin
            case (ph[31:30])
                2'd0:    sin_q15 =  sine_q(ph[29:24]);
                2'd1:    sin_q15 =  sine_q(~ph[29:24]);
                2'd2:    sin_q15 = -$signed(sine_q(ph[29:24]));
                default: sin_q15 = -$signed(sine_q(~ph[29:24]));
            endcase
        end
    endfunction

    // ----------- 半音比例 2^(d/12)（Q16）：用 13 项小表 + 整数倍频程移位 -----------
    // 原来 49 项大 case 会被综合成大 mux（耗 LUT）；改成 2 张 13 项表 + 移位：
    //   d>=0: 2^(d/12) = UP12[d%12] << (d/12)
    //   d<0 : 2^(d/12) = DN12[(-d)%12] >> ((-d)/12)
    function [20:0] up12;
        input [3:0] i;
        begin
            case (i)
                4'd0:  up12 = 21'h10000; 4'd1:  up12 = 21'h10F39;
                4'd2:  up12 = 21'h11F5A; 4'd3:  up12 = 21'h13070;
                4'd4:  up12 = 21'h1428A; 4'd5:  up12 = 21'h155B8;
                4'd6:  up12 = 21'h16A0A; 4'd7:  up12 = 21'h17F91;
                4'd8:  up12 = 21'h19660; 4'd9:  up12 = 21'h1AE8A;
                4'd10: up12 = 21'h1C824; 4'd11: up12 = 21'h1E343;
                4'd12: up12 = 21'h20000;
                default: up12 = 21'h10000;
            endcase
        end
    endfunction
    function [20:0] dn12;
        input [3:0] i;
        begin
            case (i)
                4'd0:  dn12 = 21'h10000; 4'd1:  dn12 = 21'h0F1A2;
                4'd2:  dn12 = 21'h0E412; 4'd3:  dn12 = 21'h0D745;
                4'd4:  dn12 = 21'h0CB30; 4'd5:  dn12 = 21'h0BFC9;
                4'd6:  dn12 = 21'h0B505; 4'd7:  dn12 = 21'h0AADC;
                4'd8:  dn12 = 21'h0A145; 4'd9:  dn12 = 21'h09838;
                4'd10: dn12 = 21'h08FAD; 4'd11: dn12 = 21'h0879C;
                4'd12: dn12 = 21'h08000;
                default: dn12 = 21'h10000;
            endcase
        end
    endfunction
    function [20:0] ratio_of_delta;
      input signed [8:0] d;
      reg [5:0] magnitude,remainder;reg [1:0] octave;reg [20:0] base;
      begin
        magnitude=d[8] ? -d : d;
        if(magnitude>=36)begin octave=3;remainder=magnitude-36;end
        else if(magnitude>=24)begin octave=2;remainder=magnitude-24;end
        else if(magnitude>=12)begin octave=1;remainder=magnitude-12;end
        else begin octave=0;remainder=magnitude;end
        base=d[8]?dn12(remainder[3:0]):up12(remainder[3:0]);
        if(d[8])case(octave)0:ratio_of_delta=base;1:ratio_of_delta=base>>1;2:ratio_of_delta=base>>2;default:ratio_of_delta=base>>3;endcase
        else case(octave)0:ratio_of_delta=base;1:ratio_of_delta=base<<1;2:ratio_of_delta=base<<2;default:ratio_of_delta=base<<3;endcase
      end
    endfunction

    wire signed [8:0] delta_note = $signed({1'b0, ev_target_l}) - $signed({1'b0, ev_anchor_l});
    // 限幅到 ±36 半音（3 个八度，比例上限 8.0 = pitch_mul 19bit 能表达的最大值）；
    // 吉他库琴弦 33 品 = 最多 32 半音，因此全弦拖动也能滑到位（原来限 ±24 会差 8 个半音）。
    wire signed [8:0] delta_c = (delta_note > 9'sd36) ? 9'sd36
                              : ((delta_note < -9'sd36) ? -9'sd36 : delta_note);
    // 目标比例（Q16）= 2^(Δ/12)：由 delta_c 经 13 项表+移位算出（见 ratio_of_delta），
    // 现在只在事件流水"拍2"里调一次（原来在事件同拍算，除法器链太长）。

    // 事件流水用的步长。必须在时序块引用前声明，避免严格 Verilog
    // 编译器先生成隐式网线，再拒绝后面的显式声明。
    wire [41:0] step_mul = glide_mag * STEP_K;
    // Keep fractional Q16 units so small intervals and larger slides
    // finish at the configured rate. Only the integer carry moves r_acc.
    wire [20+GLIDE_FRAC:0] step_fixed=step_mul[STEP_S-GLIDE_FRAC+:21+GLIDE_FRAC];
    wire [GLIDE_FRAC:0] frac_sum={1'b0,glide_carry}+{1'b0,glide_step_frac};
    wire [20:0] glide_advance=glide_step+{{20{1'b0}},frac_sum[GLIDE_FRAC]};

    // Three full-precision signed products share one bit-serial adder.
    // 59 clocks are available inside the existing 1024-clock sample period;
    // results are sampled by the audio engine on the next frame as before.
    reg signed [35:0] mul_a,mul_acc;
    reg [17:0] mul_b;
    reg [4:0] mul_count;
    reg signed [35:0] r_sum_c;
    wire signed [35:0] mul_addend=!mul_b[0] ? 36'sd0 :
        mul_count==17 ? -mul_a : mul_a;
    wire signed [35:0] mul_sum=mul_acc+mul_addend;
    wire signed [35:0] mul_q15=mul_acc >>> 15;

    // ---------------- 每采样更新 ----------------
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            r_acc        <= ONE_Q16;
            gliding_r    <= 1'b0;
            glide_target <= ONE_Q16;
            glide_mag    <= 21'd0;
            glide_dir    <= 1'b1;
            glide_step   <= 21'd0;glide_step_frac<=0;glide_carry<=0;
            lfo_ph       <= 32'd0;
            vib_env      <= 16'd0;
            pitch_mul    <= ONE_Q15;
            gliding      <= 1'b0;
            vib_active   <= 1'b0;
            ev_pending   <= 1'b0;
            ev_anchor_l  <= 6'd0;
            ev_target_l  <= 6'd0;
            ev_ph        <= 2'd0;
            delta_c_r    <= 9'sd0;
            ratio_tgt_r  <= ONE_Q16;
            syn_ph       <= 2'd0;
            triq_r       <= 16'sd0;
            m_eff_r      <= 16'sd0;
            m_r          <= 16'sd0;
            r_glide_r    <= ONE_Q16;
            mul_a<=0;mul_b<=0;mul_acc<=0;mul_count<=0;r_sum_c<=ONE_Q16;
        end else begin
            if (sample_tick) begin
                // ---------- 滑音：接受新事件（锁存的 pending；仅锚定引擎收到）----------
                // 只在这里锁存 Δ（浅路径），后面的"查表 / 求步长 / 落定"交给 4 拍流水（见下面 ev_ph）
                if (ev_pending) begin
                    ev_pending <= 1'b0;     // 消费；若同拍又来新事件，下方锁存会再置 1
                    delta_c_r  <= delta_c;
                    ev_ph      <= 2'd1;
                end

                // ---------- 滑音：每采样推进 ----------
                // 步长在事件时算一次并锁存：N_GLIDE = (glide_ms × 3125) >> 6 ≈ glide_ms × Fs/1000
                if (gliding_r) begin
                    glide_carry<=frac_sum[GLIDE_FRAC-1:0];
                    if (glide_mag <= glide_advance) begin
                        r_acc     <= glide_target;   // 到位：精确归位
                        gliding_r <= 1'b0;
                    end else begin
                        if (glide_dir)
                            r_acc <= r_acc + glide_advance;
                        else
                            r_acc <= r_acc - glide_advance;
                        glide_mag <= glide_mag - glide_advance;
                    end
                end

                // ---------- 颤音：LFO + 包络渐入渐出 ----------
                lfo_ph <= lfo_ph + (vib_rate_x10 * 32'd8796);
                if (use_vib) begin
                    if (vib_env <= (16'd32768-VIB_FADE_STEP))
                        vib_env <= vib_env + VIB_FADE_STEP[15:0];
                    else
                        vib_env <= 16'd32768;
                end else begin
                    if (vib_env >= VIB_FADE_STEP)
                        vib_env <= vib_env - VIB_FADE_STEP[15:0];
                    else
                        vib_env <= 16'd0;
                end
                vib_active <= (vib_env > 16'd16384);
            end

            // ---------- 滑音事件锁存（放在 sample_tick 之后：同拍新事件优先保留）----------
            if (ev_valid) begin
                ev_pending  <= 1'b1;
                ev_anchor_l <= ev_anchor;
                ev_target_l <= ev_target;
            end

            // ================= 事件处理 4 拍流水（省时序；sample_tick 那拍不推进）=================
            // 拍1 Δ限幅后的锁存 -> 拍2 查 2^(Δ/12) 比例表（含除法/移位）-> 拍3 定目标与方向/差值
            // -> 拍4 用"乘倒数+移位"算步长（输入都是寄存器，路径很短）
            // 事件只在 sample_tick 消费，1024 拍后才到下一次采样，4 拍足够走完。
            if (!sample_tick) begin
                case (ev_ph)
                    2'd1: begin
                        ratio_tgt_r <= ratio_of_delta(delta_c_r);
                        ev_ph       <= 2'd2;
                    end
                    2'd2: begin
                        glide_target <= ratio_tgt_r;
                        if (ratio_tgt_r >= r_acc) begin
                            glide_dir <= 1'b1;
                            glide_mag <= ratio_tgt_r - r_acc;
                        end else begin
                            glide_dir <= 1'b0;
                            glide_mag <= r_acc - ratio_tgt_r;
                        end
                        gliding_r <= 1'b1;
                        ev_ph     <= 2'd3;
                    end
                    2'd3: begin
                        // 步长 = |Δ| / N_GLIDE ≈ (|Δ| × STEP_K) >> STEP_S（常数倒数，省除法器）
                        glide_step <= step_fixed[20+GLIDE_FRAC:GLIDE_FRAC];
                        glide_step_frac <= |step_fixed ? step_fixed[GLIDE_FRAC-1:0] : {{(GLIDE_FRAC-1){1'b0}},1'b1};
                        glide_carry <= 0;
                        ev_ph      <= 2'd0;
                    end
                    default: ev_ph <= 2'd0;
                endcase
            end

            // Complete every product exactly (including signed rounding),
            // with no additional DSP, no reduction in pitch precision.
            if (!sample_tick) begin
                case(syn_ph)
                    1:begin
                        mul_a<=$signed({2'b00,depth_q15});mul_b<={{2{triq_r[15]}},triq_r};
                        mul_acc<=0;mul_count<=0;syn_ph<=2;
                    end
                    2,4,6:begin
                        mul_acc<=mul_sum;mul_a<=mul_a<<1;mul_b<=mul_b>>1;
                        if(mul_count==17)syn_ph<=syn_ph+1'b1;
                        else mul_count<=mul_count+1'b1;
                    end
                    3:begin
                        m_eff_r<=mul_q15[15:0];
                        mul_a<=$signed(mul_q15[15:0]);mul_b<={2'b00,vib_env};
                        mul_acc<=0;mul_count<=0;syn_ph<=4;
                    end
                    5:begin
                        m_r<=mul_q15[15:0];
                        mul_a<=$signed({1'b0,r_glide_r[20:4]});
                        mul_b<={{2{mul_q15[15]}},mul_q15[15:0]};
                        mul_acc<=0;mul_count<=0;syn_ph<=6;
                    end
                    7:begin
                        r_sum_c<=$signed({15'b0,r_glide_r})+(mul_acc >>> 11);syn_ph<=8;
                    end
                    8:begin
                        if(r_sum_c[35])pitch_mul<=0;
                        else if(r_sum_c>36'sd524288)pitch_mul<=19'h40000;
                        else pitch_mul<=r_sum_c[19:1];
                        syn_ph<=0;
                    end
                    default:syn_ph<=0;
                endcase
            end else begin
                syn_ph<=1;triq_r<=sin_q15(lfo_ph);r_glide_r<=r_acc;
            end

            gliding <= gliding_r;

            // 放在本时序块最后，保证优先级高于同拍的采样/流水推进。
            if (note_retrig) begin
                // 重拨：滑音比例和对外倍率都立刻复位 1.0，清掉尚未消费的旧滑音。
                // 仅复位 r_acc 会让 pitch_mul 的流水值再残留几个采样，导致新拨弦起音跑调。
                vib_env      <= 16'd0;
                vib_active   <= 1'b0;
                m_eff_r      <= 16'sd0;
                m_r          <= 16'sd0;
                lfo_ph       <= 32'd0;
                r_acc        <= ONE_Q16;
                gliding_r    <= 1'b0;
                glide_target <= ONE_Q16;
                glide_mag    <= 21'd0;
                glide_step   <= 21'd0;glide_step_frac<=0;glide_carry<=0;
                ev_pending   <= 1'b0;
                ev_ph        <= 2'd0;
                syn_ph       <= 2'd0;
                pitch_mul    <= ONE_Q15;
                gliding      <= 1'b0;
            end
        end
    end

endmodule
