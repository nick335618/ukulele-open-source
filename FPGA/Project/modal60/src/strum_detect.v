// ============================================================
// strum_detect.v — v12.2：入口/中值分段 + 七级事件流水线
//
// 【为什么】v11 把增益乘法串进组合链后 50 MHz 时序严重不收敛：
//   实测 475 违例 / TNS −12188 ns / Fmax 17.77 MHz / 最差 slack −36.27 ns。
//   逐级拆分后 TNS 降到 −3.84 ns、Fmax 48.86 MHz（仍差 2.3%），故再拆两级。
//   vld 仅 ~45 kHz（22 µs 一拍），50 MHz 下每拍约 1100 个时钟，
//   多级流水完全无代价（新增两拍仍远小于采样间隔，对拨弦检测无影响）。
//
// 【流水线各级职责】
//   S0        : 锁存 ADS7950 四路采样，切断输入走线
//   S1        : 中值滤波 + 基线相减；中值结果另存一拍后更新基线
//   S2 (pv1)  : 慢/快基线凹坑取较大 → dip；两两取较小 (mi01/mi23)
//   S3 (pv2)  : 共模 = min(mi01,mi23) 并钳位；dip 延迟一拍
//   S4 (pv3)  : 相减 e = dip − cm；每通道增益乘法（DSP）；算术右移
//   S5 (pv4)  : 阈值比较 + "其余三路最大值" (mx..)
//   S6 (pv5)  : 胜者通吃 + 时间一致性 + 重武装 + 冷却装载
//   S7 (pv6)  : 事件脉冲 + 峰值捕获 + 显示量锁存
//
// 【可调旋钮（运行时可改）】每通道阈值/增益/使能 +
//   慢/快基线步长、共模上限、胜者裕量、时间一致性、冷却、开机消隐、重武装门限
// ============================================================
module strum_detect #(
    parameter integer FORCE_TIMING_ENABLE = 0,
    parameter integer TH_RAW0 = 100,
    parameter integer TH_RAW1 = 100,
    parameter integer TH_RAW2 = 100,
    parameter integer TH_RAW3 = 100,
    parameter integer CM_CAP  = 0,
    parameter integer MARGIN  = 10,
    parameter integer PERSIST_N = 20,
    parameter integer SLOW_STEP = 262144,
    parameter integer FAST_STEP = 8192,
    parameter integer COOL_N  = 7000,
    parameter integer REARM_N = 40,
    // 一次事件结束后，必须在冷却已经结束的前提下连续低于重武装门限
    // 这么多个 ADC 样本才允许下一次事件。256 / 45kHz ≈ 5.7ms。
    parameter integer REARM_HOLD_N = 256,
    parameter integer WIN_N   = 45000,
    parameter integer ACQ_N   = 128,
    parameter integer BLANK_N = 90000
)(
    input  wire        clk,
    input  wire        rst_n,
    input  wire        vld,
    input  wire [11:0] v0, v1, v2, v3,

    // ---- 运行时参数 ----
    input  wire        cfg_valid,
    input  wire [11:0] cfg_th0, cfg_th1, cfg_th2, cfg_th3,
    input  wire [7:0]  cfg_gain0, cfg_gain1, cfg_gain2, cfg_gain3,
    input  wire [3:0]  cfg_en,
    input  wire [19:0] cfg_slow_step, cfg_fast_step,
    input  wire [17:0] cfg_cm_cap,
    input  wire [7:0]  cfg_margin, cfg_persist,
    input  wire [15:0] cfg_cool,
    input  wire [19:0] cfg_blank,
    input  wire [15:0] cfg_rearm,
    input  wire        cfg_stamp,
    input wire cfg_poly, mode_change,

    output reg         ev0, ev1, ev2, ev3,
    output reg  [11:0] pk0, pk1, pk2, pk3,
    output reg signed [15:0] d0, d1, d2, d3,
    output reg  [15:0] ob0, ob1, ob2, ob3,
    input wire velocity_reset,
    output wire [11:0] velocities
);

    // ============ 参数寄存级（切断 opto_cfg → 检测逻辑的长组合路径）============
    reg r_poly;
    always @(posedge clk or negedge rst_n)
      if(!rst_n)r_poly<=0;else r_poly<=cfg_poly;
    reg [11:0] r_th0, r_th1, r_th2, r_th3;
    reg [7:0]  r_g0, r_g1, r_g2, r_g3;
    reg [3:0]  r_en;
    reg [19:0] r_slow, r_fast, r_blank;
    reg [17:0] r_cm;
    reg [7:0]  r_margin, r_persist;
    reg [15:0] r_cool, r_rearm;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            r_th0 <= 12'd100; r_th1 <= 12'd100; r_th2 <= 12'd100; r_th3 <= 12'd100;
            r_g0 <= 8'd64; r_g1 <= 8'd64; r_g2 <= 8'd64; r_g3 <= 8'd64;
            r_en <= 4'b1111;
            r_slow <= 20'd262144; r_fast <= 20'd8192; r_blank <= 20'd90000;
            r_cm <= 18'd0; r_margin <= 8'd10; r_persist <= 8'd20;
            r_cool <= 16'd7000; r_rearm <= 16'd40;
        end else begin
            r_th0 <= cfg_valid ? cfg_th0 : TH_RAW0[11:0];
            r_th1 <= cfg_valid ? cfg_th1 : TH_RAW1[11:0];
            r_th2 <= cfg_valid ? cfg_th2 : TH_RAW2[11:0];
            r_th3 <= cfg_valid ? cfg_th3 : TH_RAW3[11:0];
            r_g0  <= cfg_valid ? cfg_gain0 : 8'd64;
            r_g1  <= cfg_valid ? cfg_gain1 : 8'd64;
            r_g2  <= cfg_valid ? cfg_gain2 : 8'd64;
            r_g3  <= cfg_valid ? cfg_gain3 : 8'd64;
            r_en  <= cfg_valid ? cfg_en : 4'b1111;
            r_slow <= cfg_valid ? cfg_slow_step : SLOW_STEP[19:0];
            r_fast <= cfg_valid ? cfg_fast_step : FAST_STEP[19:0];
            r_blank<= cfg_valid ? cfg_blank : BLANK_N[19:0];
            r_cm   <= cfg_valid ? cfg_cm_cap : CM_CAP[17:0];
            r_margin  <= cfg_valid ? cfg_margin  : MARGIN[7:0];
            r_persist <= cfg_valid ? cfg_persist : PERSIST_N[7:0];
            r_cool    <= cfg_valid ? cfg_cool    : COOL_N[15:0];
            r_rearm   <= cfg_valid ? cfg_rearm   : REARM_N[15:0];
        end
    end

    // ============ S0：采样入口（切断 ADC 到中值/基线组合链）============
    // ADS7950 的采样寄存器离拨弦检测逻辑较远。先把四路数据和配置
    // 标记锁存到本模块内，再在下一拍做中值滤波和基线更新，可明显缩短
    // raw* → S1 寄存器的布线延迟；检测总延迟只增加一个 50 MHz 时钟。
    reg [11:0] in0_r, in1_r, in2_r, in3_r;
    reg        in_vld_r, in_stamp_r;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            in0_r <= 12'd0; in1_r <= 12'd0; in2_r <= 12'd0; in3_r <= 12'd0;
            in_vld_r <= 1'b0;
            in_stamp_r <= 1'b0;
        end else begin
            in_vld_r   <= vld && !mode_change;
            in_stamp_r <= cfg_stamp;
            if (vld) begin
                in0_r <= v0; in1_r <= v1; in2_r <= v2; in3_r <= v3;
            end
        end
    end

    // ============ 流水线有效标志 ============
    reg pv1, pv2, pv3, pv4, pv5, pv6;
    reg gain_done;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            pv1 <= 1'b0; pv2 <= 1'b0; pv3 <= 1'b0;
            pv4 <= 1'b0; pv5 <= 1'b0; pv6 <= 1'b0;
        end else begin
            if(mode_change)begin pv1<=0;pv2<=0;pv3<=0;pv4<=0;pv5<=0;pv6<=0;end else begin
            pv1 <= in_vld_r && !in_stamp_r;   // cfg_stamp 同拍时 S1 不更新，流水线也不该认这个样本
            pv2 <= pv1; pv3 <= pv2;
            pv4 <= gain_done; pv5 <= pv4; pv6 <= pv5;
            end
        end
    end

    // ============ S1：中值滤波 + 基线相减 + 基线更新 ============
    reg [11:0] p1_0, p1_1, p1_2, p1_3, p2_0, p2_1, p2_2, p2_3;

    function [11:0] med3;
        input [11:0] a, b, c;
        reg [11:0] hi, lo;
        begin
            if (a > b) begin hi = a; lo = b; end else begin hi = b; lo = a; end
            if (c > hi)      med3 = hi;
            else if (c < lo) med3 = lo;
            else             med3 = c;
        end
    endfunction

    wire [11:0] m0 = med3(in0_r, p1_0, p2_0);
    wire [11:0] m1 = med3(in1_r, p1_1, p2_1);
    wire [11:0] m2 = med3(in2_r, p1_2, p2_2);
    wire [11:0] m3 = med3(in3_r, p1_3, p2_3);

    // Register the median result before the slow/fast baseline accumulators.
    // This separates the median compare tree from the 32-bit accumulator.
    reg [11:0] m0_r, m1_r, m2_r, m3_r;
    reg [11:0] acq0_r, acq1_r, acq2_r, acq3_r;
    reg        m_vld_r;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            m0_r <= 12'd0; m1_r <= 12'd0; m2_r <= 12'd0; m3_r <= 12'd0;
            acq0_r <= 12'd0; acq1_r <= 12'd0; acq2_r <= 12'd0; acq3_r <= 12'd0;
            m_vld_r <= 1'b0;
        end else begin
            m_vld_r <= in_vld_r;
            if (in_vld_r) begin
                m0_r <= m0; m1_r <= m1; m2_r <= m2; m3_r <= m3;
                acq0_r <= in0_r; acq1_r <= in1_r; acq2_r <= in2_r; acq3_r <= in3_r;
            end
        end
    end

    reg signed [17:0] base0, base1, base2, base3, fst0, fst1, fst2, fst3;
    reg signed [31:0] fac0, fac1, fac2, fac3, ffc0, ffc1, ffc2, ffc3;
    localparam ACQ_COUNT_RAW=$clog2({1'b0,ACQ_N[15:0]}+1);
    localparam ACQ_COUNT_W=ACQ_COUNT_RAW<1 ? 1 : ACQ_COUNT_RAW>16 ? 16 : ACQ_COUNT_RAW;
    reg [ACQ_COUNT_W-1:0] acq_cnt;
    reg [24:0] acc0, acc1, acc2, acc3;
    reg [19:0] blank_cnt;
    wire acq_done = (acq_cnt == ACQ_N[15:0]);
    wire blank_ok = (blank_cnt >= r_blank);

    reg signed [17:0] s1a0, s1a1, s1a2, s1a3, s1b0, s1b1, s1b2, s1b3;

    wire signed [17:0] ra0 = base0 - {6'd0, m0};
    wire signed [17:0] ra1 = base1 - {6'd0, m1};
    wire signed [17:0] ra2 = base2 - {6'd0, m2};
    wire signed [17:0] ra3 = base3 - {6'd0, m3};
    wire signed [17:0] rb0 = fst0 - {6'd0, m0};
    wire signed [17:0] rb1 = fst1 - {6'd0, m1};
    wire signed [17:0] rb2 = fst2 - {6'd0, m2};
    wire signed [17:0] rb3 = fst3 - {6'd0, m3};


    // Eight independent histories, one arithmetic path; all updates finish
    // within 16 clocks (0.32 us), before the next legal ADC sample. Filtering,
    // comparisons and event pipeline keep their original sample boundaries.
    reg baseline_run,baseline_stage;
    reg [2:0] baseline_idx;
    reg [19:0] slow_snapshot,fast_snapshot;
    reg signed [31:0] baseline_fac,baseline_threshold;
    reg signed [17:0] baseline_value;
    reg [11:0] baseline_sample;
    reg signed [31:0] selected_fac;
    reg signed [17:0] selected_base;
    reg [11:0] selected_sample;
    always @* begin
      selected_fac=0;selected_base=0;selected_sample=0;
      case(baseline_idx)
        3'd0:begin selected_fac=fac0;selected_base=base0;selected_sample=m0_r;end
        3'd1:begin selected_fac=fac1;selected_base=base1;selected_sample=m1_r;end
        3'd2:begin selected_fac=fac2;selected_base=base2;selected_sample=m2_r;end
        3'd3:begin selected_fac=fac3;selected_base=base3;selected_sample=m3_r;end
        3'd4:begin selected_fac=ffc0;selected_base=fst0;selected_sample=m0_r;end
        3'd5:begin selected_fac=ffc1;selected_base=fst1;selected_sample=m1_r;end
        3'd6:begin selected_fac=ffc2;selected_base=fst2;selected_sample=m2_r;end
        3'd7:begin selected_fac=ffc3;selected_base=fst3;selected_sample=m3_r;end
      endcase
    end
    // Retain the released RTL's 18-bit unsigned subtraction context, including
    // wrap behavior under extreme runtime step settings.
    wire signed [31:0] baseline_delta=$signed({20'd0,baseline_sample})-$signed({14'd0,baseline_value});
    wire baseline_up=baseline_fac>=baseline_threshold;
    wire baseline_down=baseline_fac<=-baseline_threshold;
    wire signed [31:0] updated_fac=baseline_up ? baseline_fac-baseline_threshold :
       baseline_down ? baseline_fac+baseline_threshold : baseline_fac+baseline_delta;
    wire signed [17:0] updated_base=baseline_value+(baseline_up ? 18'd1 : baseline_down ? 18'h3ffff:18'd0);

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            baseline_run<=0;baseline_stage<=0;baseline_idx<=0;slow_snapshot<=0;fast_snapshot<=0;
            baseline_fac<=0;baseline_threshold<=0;baseline_value<=0;baseline_sample<=0;
            p1_0 <= 12'd0; p1_1 <= 12'd0; p1_2 <= 12'd0; p1_3 <= 12'd0;
            p2_0 <= 12'd0; p2_1 <= 12'd0; p2_2 <= 12'd0; p2_3 <= 12'd0;
            base0 <= 18'sd2047; base1 <= 18'sd2047; base2 <= 18'sd2047; base3 <= 18'sd2047;
            fst0  <= 18'sd2047; fst1  <= 18'sd2047; fst2  <= 18'sd2047; fst3  <= 18'sd2047;
            fac0 <= 32'sd0; fac1 <= 32'sd0; fac2 <= 32'sd0; fac3 <= 32'sd0;
            ffc0 <= 32'sd0; ffc1 <= 32'sd0; ffc2 <= 32'sd0; ffc3 <= 32'sd0;
            acq_cnt <= 16'd0; blank_cnt <= 20'd0;
            acc0 <= 25'd0; acc1 <= 25'd0; acc2 <= 25'd0; acc3 <= 25'd0;
            s1a0 <= 18'sd0; s1a1 <= 18'sd0; s1a2 <= 18'sd0; s1a3 <= 18'sd0;
            s1b0 <= 18'sd0; s1b1 <= 18'sd0; s1b2 <= 18'sd0; s1b3 <= 18'sd0;
        end else begin
            if(baseline_run)begin
              if(!baseline_stage)begin
                baseline_fac<=selected_fac;baseline_value<=selected_base;baseline_sample<=selected_sample;
                baseline_threshold<=$signed({12'd0,baseline_idx[2]?fast_snapshot:slow_snapshot});baseline_stage<=1;
              end else begin
                baseline_stage<=0;baseline_idx<=baseline_idx+1'b1;
                if(baseline_idx==7)baseline_run<=0;
                case(baseline_idx)
                  3'd0:begin fac0<=updated_fac;base0<=updated_base;end
                  3'd1:begin fac1<=updated_fac;base1<=updated_base;end
                  3'd2:begin fac2<=updated_fac;base2<=updated_base;end
                  3'd3:begin fac3<=updated_fac;base3<=updated_base;end
                  3'd4:begin ffc0<=updated_fac;fst0<=updated_base;end
                  3'd5:begin ffc1<=updated_fac;fst1<=updated_base;end
                  3'd6:begin ffc2<=updated_fac;fst2<=updated_base;end
                  3'd7:begin ffc3<=updated_fac;fst3<=updated_base;end
                endcase
              end
            end
            if (in_vld_r) begin
            // 注意：参数变更【不】重采基线。增益只缩放凹坑、不改原始基线，
            // 若在此清 acq_cnt，拖滑块（每帧下发配置）会让 acq_cnt 永远到不了 ACQ_N，
            // 导致 base/fst 永久冻结 → 凹坑持续偏大 → 伪事件。
            p1_0 <= in0_r; p1_1 <= in1_r; p1_2 <= in2_r; p1_3 <= in3_r;
            p2_0 <= p1_0; p2_1 <= p1_1; p2_2 <= p1_2; p2_3 <= p1_3;

            s1a0 <= (ra0 > 0) ? ra0 : 18'sd0;
            s1a1 <= (ra1 > 0) ? ra1 : 18'sd0;
            s1a2 <= (ra2 > 0) ? ra2 : 18'sd0;
            s1a3 <= (ra3 > 0) ? ra3 : 18'sd0;
            s1b0 <= (rb0 > 0) ? rb0 : 18'sd0;
            s1b1 <= (rb1 > 0) ? rb1 : 18'sd0;
            s1b2 <= (rb2 > 0) ? rb2 : 18'sd0;
            s1b3 <= (rb3 > 0) ? rb3 : 18'sd0;
            end

        if (m_vld_r) begin
            if (!acq_done) begin
                acq_cnt <= acq_cnt + 1'b1;
                acc0 <= acc0 + {13'd0, acq0_r};
                acc1 <= acc1 + {13'd0, acq1_r};
                acc2 <= acc2 + {13'd0, acq2_r};
                acc3 <= acc3 + {13'd0, acq3_r};
                if (acq_cnt == ACQ_N[15:0] - 1'b1) begin
                    base0 <= (acc0 + {13'd0, acq0_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    base1 <= (acc1 + {13'd0, acq1_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    base2 <= (acc2 + {13'd0, acq2_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    base3 <= (acc3 + {13'd0, acq3_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    fst0  <= (acc0 + {13'd0, acq0_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    fst1  <= (acc1 + {13'd0, acq1_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    fst2  <= (acc2 + {13'd0, acq2_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    fst3  <= (acc3 + {13'd0, acq3_r} + (ACQ_N[24:0] >> 1)) >> 7;
                    fac0 <= 32'sd0; fac1 <= 32'sd0; fac2 <= 32'sd0; fac3 <= 32'sd0;
                    ffc0 <= 32'sd0; ffc1 <= 32'sd0; ffc2 <= 32'sd0; ffc3 <= 32'sd0;
                end
            end else begin
                if (!blank_ok) blank_cnt <= blank_cnt + 1'b1;

                // Baseline arithmetic is time shared between ADC samples.
                // Capture the same thresholds used by the original parallel update.
                baseline_run<=1;baseline_idx<=0;baseline_stage<=0;
                slow_snapshot<=r_slow;fast_snapshot<=r_fast;

            end
        end
        end
    end

    // ============ S2：慢/快取较大 → dip；两两取较小 ============
    reg signed [17:0] dp0, dp1, dp2, dp3, mi01, mi23;
    wire signed [17:0] pa0 = (s1a0 > s1b0) ? s1a0 : s1b0;
    wire signed [17:0] pa1 = (s1a1 > s1b1) ? s1a1 : s1b1;
    wire signed [17:0] pa2 = (s1a2 > s1b2) ? s1a2 : s1b2;
    wire signed [17:0] pa3 = (s1a3 > s1b3) ? s1a3 : s1b3;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            dp0 <= 18'sd0; dp1 <= 18'sd0; dp2 <= 18'sd0; dp3 <= 18'sd0;
            mi01 <= 18'sd0; mi23 <= 18'sd0;
        end else if (pv1) begin
            dp0 <= pa0; dp1 <= pa1; dp2 <= pa2; dp3 <= pa3;
            mi01 <= (pa0 < pa1) ? pa0 : pa1;
            mi23 <= (pa2 < pa3) ? pa2 : pa3;
        end
    end

    // ============ S3：共模 = min(mi01,mi23) 并钳位 ============
    reg signed [17:0] cm2, d2_0, d2_1, d2_2, d2_3;
    wire signed [17:0] cm_raw = (mi01 < mi23) ? mi01 : mi23;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cm2 <= 18'sd0;
            d2_0 <= 18'sd0; d2_1 <= 18'sd0; d2_2 <= 18'sd0; d2_3 <= 18'sd0;
        end else if (pv2) begin
            cm2  <= r_poly ? 18'sd0 : ((cm_raw > r_cm) ? r_cm : cm_raw);
            d2_0 <= dp0; d2_1 <= dp1; d2_2 <= dp2; d2_3 <= dp3;
        end
    end

    // ============ S4：相减 + 每通道增益（DSP）============
    reg signed [17:0] sg3_0, sg3_1, sg3_2, sg3_3;
    wire signed [17:0] e0 = d2_0 - cm2;
    wire signed [17:0] e1 = d2_1 - cm2;
    wire signed [17:0] e2 = d2_2 - cm2;
    wire signed [17:0] e3 = d2_3 - cm2;
    // Four independent gains share one multiply, between legal ADC samples.
    // All four results commit before threshold/persistence logic advances.
    // Event latency increases by four 50-MHz clocks (80 ns); sample-domain
    // filtering, simultaneous-hit priority, cooldown and rearm are unchanged.
    reg gain_run;reg [1:0] gain_index;reg [31:0] gains_snapshot;
    wire signed [17:0] gain_input=pv3 ? e0 : gain_index==1 ? e1 : gain_index==2 ? e2 : e3;
    wire [7:0] gain_value=pv3 ? r_g0 : gains_snapshot[gain_index*8+:8];
    wire signed [25:0] gain_product=gain_input*$signed({1'b0,gain_value});
    wire signed [25:0] gain_shifted=gain_product >>> 6;

    // Saturate using the sign-extension bits instead of four full-width
    // comparators.  After the >>>6 shift, bits [25:17] must be all zero for
    // a positive value or all one for a negative value to fit in 18 bits.
    function signed [17:0] sat_sg3;
        input signed [25:0] v;
        begin
            if (v[25]) begin
                sat_sg3 = (v[25:17] == 9'b111111111) ? v[17:0] : -18'sd131072;
            end else begin
                sat_sg3 = (v[25:17] == 9'b000000000) ? v[17:0] : 18'sd131071;
            end
        end
    endfunction

    always @(posedge clk or negedge rst_n) begin
        if(!rst_n)begin
            sg3_0<=0;sg3_1<=0;sg3_2<=0;sg3_3<=0;
            gain_run<=0;gain_index<=0;gains_snapshot<=0;gain_done<=0;
        end else begin
            gain_done<=0;
            if(mode_change)gain_run<=0;
            else if(pv3)begin
                sg3_0<=sat_sg3(gain_shifted);gains_snapshot<={r_g3,r_g2,r_g1,r_g0};gain_index<=1;gain_run<=1;
            end else if(gain_run)begin
                case(gain_index)
                    1:sg3_1<=sat_sg3(gain_shifted);
                    2:sg3_2<=sat_sg3(gain_shifted);
                    3:sg3_3<=sat_sg3(gain_shifted);
                endcase
                gain_index<=gain_index+1'b1;
                if(gain_index==3)begin gain_run<=0;gain_done<=1;end
            end
        end
    end

    // ============ S5：阈值比较 + "其余三路最大值" ============
    reg th_ok0, th_ok1, th_ok2, th_ok3;
    reg signed [17:0] wx0, wx1, wx2, wx3;
    // mx01/02/03/12/13/23 是四路两两最大值，dom 表达式用它们组合出"其余三路最大"
    reg signed [17:0] mx01, mx02, mx03, mx12, mx13, mx23;

    wire signed [17:0] w0 = r_en[0] ? sg3_0 : -18'sd131072;
    wire signed [17:0] w1 = r_en[1] ? sg3_1 : -18'sd131072;
    wire signed [17:0] w2 = r_en[2] ? sg3_2 : -18'sd131072;
    wire signed [17:0] w3 = r_en[3] ? sg3_3 : -18'sd131072;
    wire signed [17:0] thr0 = $signed({6'd0, r_th0});
    wire signed [17:0] thr1 = $signed({6'd0, r_th1});
    wire signed [17:0] thr2 = $signed({6'd0, r_th2});
    wire signed [17:0] thr3 = $signed({6'd0, r_th3});

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            th_ok0 <= 1'b0; th_ok1 <= 1'b0; th_ok2 <= 1'b0; th_ok3 <= 1'b0;
            mx01 <= 18'sd0; mx02 <= 18'sd0; mx03 <= 18'sd0;
            mx12 <= 18'sd0; mx13 <= 18'sd0; mx23 <= 18'sd0;
            wx0 <= 18'sd0; wx1 <= 18'sd0; wx2 <= 18'sd0; wx3 <= 18'sd0;
        end else if (pv4) begin
            wx0 <= w0; wx1 <= w1; wx2 <= w2; wx3 <= w3;
            th_ok0 <= (sg3_0 > thr0);
            th_ok1 <= (sg3_1 > thr1);
            th_ok2 <= (sg3_2 > thr2);
            th_ok3 <= (sg3_3 > thr3);
            mx01 <= (w0 > w1) ? w0 : w1;
            mx02 <= (w0 > w2) ? w0 : w2;
            mx03 <= (w0 > w3) ? w0 : w3;
            mx12 <= (w1 > w2) ? w1 : w2;
            mx13 <= (w1 > w3) ? w1 : w3;
            mx23 <= (w2 > w3) ? w2 : w3;
        end
    end

    // ============ S6：胜者通吃 + 时间一致性 + 重武装 + 冷却 ============
    // Persistence saturates at the 8-bit runtime limit, never exceeds 255.
    reg [7:0] pc0, pc1, pc2, pc3;
    reg [15:0] cool0, cool1, cool2, cool3;
    reg        dom0_r, dom1_r, dom2_r, dom3_r;
    reg        ar0, ar1, ar2, ar3;
    reg [8:0]  rq0, rq1, rq2, rq3;
    reg        blank_ok_r;
    reg        evq0_r, evq1_r, evq2_r, evq3_r;

    wire signed [17:0] mg = $signed({10'd0, r_margin});
    wire signed [17:0] rm = $signed({2'd0, r_rearm});
    wire signed [17:0] rearm_th0 = (rm < (thr0 >>> 1)) ? rm : (thr0 >>> 1);
    wire signed [17:0] rearm_th1 = (rm < (thr1 >>> 1)) ? rm : (thr1 >>> 1);
    wire signed [17:0] rearm_th2 = (rm < (thr2 >>> 1)) ? rm : (thr2 >>> 1);
    wire signed [17:0] rearm_th3 = (rm < (thr3 >>> 1)) ? rm : (thr3 >>> 1);
    wire [8:0] rearm_hold = REARM_HOLD_N[8:0];

    wire ev_now0 = dom0_r && (pc0 >= {8'd0, r_persist}) && ar0 && (cool0 == 16'd0) && blank_ok_r && acq_done;
    wire ev_now1 = dom1_r && (pc1 >= {8'd0, r_persist}) && ar1 && (cool1 == 16'd0) && blank_ok_r && acq_done;
    wire ev_now2 = dom2_r && (pc2 >= {8'd0, r_persist}) && ar2 && (cool2 == 16'd0) && blank_ok_r && acq_done;
    wire ev_now3 = dom3_r && (pc3 >= {8'd0, r_persist}) && ar3 && (cool3 == 16'd0) && blank_ok_r && acq_done;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            pc0 <= 16'd0; pc1 <= 16'd0; pc2 <= 16'd0; pc3 <= 16'd0;
            cool0 <= 16'd0; cool1 <= 16'd0; cool2 <= 16'd0; cool3 <= 16'd0;
            dom0_r <= 1'b0; dom1_r <= 1'b0; dom2_r <= 1'b0; dom3_r <= 1'b0;
            ar0 <= 1'b0; ar1 <= 1'b0; ar2 <= 1'b0; ar3 <= 1'b0;
            rq0 <= 9'd0; rq1 <= 9'd0; rq2 <= 9'd0; rq3 <= 9'd0;
            blank_ok_r <= 1'b0;
            evq0_r <= 1'b0; evq1_r <= 1'b0; evq2_r <= 1'b0; evq3_r <= 1'b0;
        end else if (cfg_stamp || mode_change) begin
            // 参数变更：只清"判定过程"的状态，并解除武装（否则冷却被清 + ar 仍为 1 会立刻误报）
            pc0 <= 16'd0; pc1 <= 16'd0; pc2 <= 16'd0; pc3 <= 16'd0;
            dom0_r <= 1'b0; dom1_r <= 1'b0; dom2_r <= 1'b0; dom3_r <= 1'b0;
            ar0 <= 1'b0; ar1 <= 1'b0; ar2 <= 1'b0; ar3 <= 1'b0;
            rq0 <= 9'd0; rq1 <= 9'd0; rq2 <= 9'd0; rq3 <= 9'd0;
            evq0_r <= 1'b0; evq1_r <= 1'b0; evq2_r <= 1'b0; evq3_r <= 1'b0;
            // 注意：cool* 不清（保留冷却保护），blank_ok_r 不清（保留消隐）
        end else if (pv5) begin
            dom0_r <= r_en[0] && (r_poly || (wx0 > ((mx12 > mx13 ? mx12 : mx13) + mg))) && th_ok0;
            dom1_r <= r_en[1] && (r_poly || (wx1 > ((mx02 > mx03 ? mx02 : mx03) + mg))) && th_ok1;
            dom2_r <= r_en[2] && (r_poly || (wx2 > ((mx01 > mx03 ? mx01 : mx03) + mg))) && th_ok2;
            dom3_r <= r_en[3] && (r_poly || (wx3 > ((mx01 > mx02 ? mx01 : mx02) + mg))) && th_ok3;

            // 冷却或未武装期间不能预先累计 PERSIST。旧逻辑会在冷却期间把 pc
            // 累满，冷却一结束就把同一次按压的余振当成新事件。
            if (ar0 && (cool0 == 16'd0) && th_ok0) begin
                if (pc0 < {8'd0, r_persist}) pc0 <= pc0 + 1'b1;
            end else pc0 <= 16'd0;
            if (ar1 && (cool1 == 16'd0) && th_ok1) begin
                if (pc1 < {8'd0, r_persist}) pc1 <= pc1 + 1'b1;
            end else pc1 <= 16'd0;
            if (ar2 && (cool2 == 16'd0) && th_ok2) begin
                if (pc2 < {8'd0, r_persist}) pc2 <= pc2 + 1'b1;
            end else pc2 <= 16'd0;
            if (ar3 && (cool3 == 16'd0) && th_ok3) begin
                if (pc3 < {8'd0, r_persist}) pc3 <= pc3 + 1'b1;
            end else pc3 <= 16'd0;

            // 重武装必须满足三个条件：冷却结束、采集/消隐完成、连续安静。
            // 不能让冷却期间的一两个低点提前把 ar 置回 1。
            if (!ar0 && (cool0 == 16'd0) && blank_ok_r && acq_done) begin
                if (wx0 <= rearm_th0) begin
                    if (rq0 < rearm_hold) rq0 <= rq0 + 9'd1;
                    if ((rearm_hold == 9'd0) || (rq0 + 9'd1 >= rearm_hold)) begin ar0 <= 1'b1; rq0 <= 9'd0; end
                end else rq0 <= 9'd0;
            end else rq0 <= 9'd0;
            if (!ar1 && (cool1 == 16'd0) && blank_ok_r && acq_done) begin
                if (wx1 <= rearm_th1) begin
                    if (rq1 < rearm_hold) rq1 <= rq1 + 9'd1;
                    if ((rearm_hold == 9'd0) || (rq1 + 9'd1 >= rearm_hold)) begin ar1 <= 1'b1; rq1 <= 9'd0; end
                end else rq1 <= 9'd0;
            end else rq1 <= 9'd0;
            if (!ar2 && (cool2 == 16'd0) && blank_ok_r && acq_done) begin
                if (wx2 <= rearm_th2) begin
                    if (rq2 < rearm_hold) rq2 <= rq2 + 9'd1;
                    if ((rearm_hold == 9'd0) || (rq2 + 9'd1 >= rearm_hold)) begin ar2 <= 1'b1; rq2 <= 9'd0; end
                end else rq2 <= 9'd0;
            end else rq2 <= 9'd0;
            if (!ar3 && (cool3 == 16'd0) && blank_ok_r && acq_done) begin
                if (wx3 <= rearm_th3) begin
                    if (rq3 < rearm_hold) rq3 <= rq3 + 9'd1;
                    if ((rearm_hold == 9'd0) || (rq3 + 9'd1 >= rearm_hold)) begin ar3 <= 1'b1; rq3 <= 9'd0; end
                end else rq3 <= 9'd0;
            end else rq3 <= 9'd0;

            evq0_r <= ev_now0; evq1_r <= ev_now1; evq2_r <= ev_now2; evq3_r <= ev_now3;

            if (ev_now0) begin cool0 <= r_cool; ar0 <= 1'b0; end
            else if (cool0 != 16'd0) cool0 <= cool0 - 1'b1;
            if (ev_now1) begin cool1 <= r_cool; ar1 <= 1'b0; end
            else if (cool1 != 16'd0) cool1 <= cool1 - 1'b1;
            if (ev_now2) begin cool2 <= r_cool; ar2 <= 1'b0; end
            else if (cool2 != 16'd0) cool2 <= cool2 - 1'b1;
            if (ev_now3) begin cool3 <= r_cool; ar3 <= 1'b0; end
            else if (cool3 != 16'd0) cool3 <= cool3 - 1'b1;

            blank_ok_r <= blank_ok;
        end
    end

    // ============ S7：事件脉冲 + 峰值 + 显示量 ============
    reg [15:0] win_cnt;
    reg [11:0] wp0, wp1, wp2, wp3;

    wire [11:0] c0 = (sg3_0 > 18'sd4095) ? 12'd4095 : ((sg3_0 < 0) ? 12'd0 : sg3_0[11:0]);
    wire [11:0] c1 = (sg3_1 > 18'sd4095) ? 12'd4095 : ((sg3_1 < 0) ? 12'd0 : sg3_1[11:0]);
    wire [11:0] c2 = (sg3_2 > 18'sd4095) ? 12'd4095 : ((sg3_2 < 0) ? 12'd0 : sg3_2[11:0]);
    wire [11:0] c3 = (sg3_3 > 18'sd4095) ? 12'd4095 : ((sg3_3 < 0) ? 12'd0 : sg3_3[11:0]);

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            ev0 <= 1'b0; ev1 <= 1'b0; ev2 <= 1'b0; ev3 <= 1'b0;
            pk0 <= 12'd0; pk1 <= 12'd0; pk2 <= 12'd0; pk3 <= 12'd0;
            win_cnt <= 16'd0;
            wp0 <= 12'd0; wp1 <= 12'd0; wp2 <= 12'd0; wp3 <= 12'd0;
            d0 <= 16'sd0; d1 <= 16'sd0; d2 <= 16'sd0; d3 <= 16'sd0;
            ob0 <= 16'd0; ob1 <= 16'd0; ob2 <= 16'd0; ob3 <= 16'd0;
        end else begin
            ev0 <= 1'b0; ev1 <= 1'b0; ev2 <= 1'b0; ev3 <= 1'b0;

            // Publish a complete baseline snapshot, never a half-updated
            // four-lane set during the shared arithmetic transaction.
            if (baseline_run && baseline_stage && baseline_idx==3) begin
                ob0<=base0[15:0];ob1<=base1[15:0];ob2<=base2[15:0];ob3<=updated_base[15:0];
            end

            if (pv6 && !mode_change) begin
                if (evq0_r) begin ev0 <= 1'b1; pk0 <= c0; end
                if (evq1_r) begin ev1 <= 1'b1; pk1 <= c1; end
                if (evq2_r) begin ev2 <= 1'b1; pk2 <= c2; end
                if (evq3_r) begin ev3 <= 1'b1; pk3 <= c3; end

                d0 <= wx0[15:0]; d1 <= wx1[15:0];
                d2 <= wx2[15:0]; d3 <= wx3[15:0];
                if (!baseline_run) begin
                    ob0<=base0[15:0];ob1<=base1[15:0];ob2<=base2[15:0];ob3<=base3[15:0];
                end

                if (c0 > wp0) wp0 <= c0;
                if (c1 > wp1) wp1 <= c1;
                if (c2 > wp2) wp2 <= c2;
                if (c3 > wp3) wp3 <= c3;
                if (win_cnt == WIN_N[15:0] - 1'b1) begin
                    win_cnt <= 16'd0;
                    wp0 <= 12'd0; wp1 <= 12'd0; wp2 <= 12'd0; wp3 <= 12'd0;
                end else begin
                    win_cnt <= win_cnt + 1'b1;
                end
            end
        end
    end

    generate if(FORCE_TIMING_ENABLE)begin:g_velocity
      opto_velocity u_velocity(.clk(clk),.rst_n(rst_n),
        .clear(cfg_stamp || mode_change || velocity_reset),
        .sample_valid(pv6 && !mode_change),.levels({c3,c2,c1,c0}),
        .thresholds({r_th3,r_th2,r_th1,r_th0}),.armed({ar3,ar2,ar1,ar0}),
        .events({evq3_r,evq2_r,evq1_r,evq0_r}),.velocities(velocities));
    end else begin:g_fixed_velocity
      assign velocities=12'hFFF;
    end endgenerate
endmodule

