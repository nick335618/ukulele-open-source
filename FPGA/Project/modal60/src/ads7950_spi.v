// ============================================================
// ads7950_spi.v — ADS7950 4 通道 12 位 ADC 的 SPI 采集控制器
//
// 命令字（TI SLAS605C 第 32 页，Manual 模式）：
//   cmd = 0x1800 | (ch << 7)
//     DI15..12 = 0001  Manual 模式
//     DI11     = 1     允许编程 range/GPIO
//     DI10..7  = 通道地址 ch
//     DI6      = 0     量程 0~VREF（2.5V 基准 → 1 LSB = 610.35 µV）
//     DI5      = 0     不掉电
//
// 两条关键时序特性（本模块已处理）：
//   1) SDO 是「2 帧流水线」（TI SLAS605 手册 Figure 51：第 N 帧下发的通道命令，
//      其转换结果连地址半字节一起要到第 N+2 帧才回到 SDO）
//      → 模块按回传的高 4 位地址把结果存到对应通道，天然自对齐；
//        上电后第一帧（地址无意义）被丢弃，不计入错误。
//      ★这里原本按「1 帧流水线」写，导致链路自检每帧都误判为不匹配 —— 详见下方判据处注释。
//   2) SDO 高 4 位回传通道地址 → 免费的链路自检（地址非 0..3 记一次错误）
//
// SPI 时序（CPOL=0）：SCLK 低电平期建立 SDI，上升沿采样 SDO
// 引脚：与 AD7147 共总线（各自独立片选），SCLK 默认 1 MHz（远低于手册 20 MHz 上限）
// ============================================================
module ads7950_spi #(
    parameter integer CLK_HZ   = 50_000_000,
    parameter integer SCLK_HZ  = 1_000_000,
    parameter integer NCH      = 4,
    parameter integer FIXED_CH = -1,    // >=0：固定只请求该通道（诊断 SDI 命令通路）；-1：正常轮询
    parameter integer RANGE_5V = 0      // 1 = 量程 0~2*VREF(5V)，0 = 0~VREF(2.5V)（命令字 DI6）
)(
    input  wire        clk,
    input  wire        rst_n,

    input  wire        start,          // 单周期脉冲：启动一轮 NCH 通道扫描
    output reg         busy,
    output reg         done,           // 一轮扫描完成的单周期脉冲

    // 物理接口
    output reg         sclk,
    output reg         sdi,
    input  wire        sdo,
    output reg         cs_n,

    // 每通道最近一次的原始 16 位回传（[15:12]=地址，[11:0]=码值）
    output reg  [15:0] raw0,
    output reg  [15:0] raw1,
    output reg  [15:0] raw2,
    output reg  [15:0] raw3,

    // 诊断
    output reg  [15:0] frame_cnt,      // 已完成帧数
    output reg  [15:0] addr_err_cnt,   // 地址非法（不在 0..NCH-1）的帧数
    output reg  [15:0] scan_us,        // 一轮扫描耗时（µs）
    output reg  [15:0] last_word,      // 最近一帧收到的原始 16 位（无论地址是否合法）
    output reg  [15:0] ph0, ph1, ph2, ph3,  // 诊断：同一帧在 4 个采样相位下的结果
    output reg  [15:0] ch_mismatch_cnt     // 回传地址 != 上一帧请求通道 的帧数（真正的链路自检）
);

    localparam integer HALF   = (CLK_HZ / SCLK_HZ) / 2;   // 半周期 = 时钟数
    localparam integer GAP_N  = 20;                      // CS# 高电平 400 ns（高速连续扫描用）
    localparam integer US_DIV = CLK_HZ / 1_000_000;       // 1 µs 的时钟数
    localparam [1:0]   LAST_CH = NCH - 1;                 // 本轮最后一个通道号

    localparam [2:0] S_IDLE = 3'd0,
                     S_LO   = 3'd1,   // SCLK 低：建立 SDI
                     S_HI   = 3'd2,   // SCLK 高：采样 SDO
                     S_END  = 3'd3,   // 收尾：CS# 仍为低时先把 SCLK 拉低（第16个下降沿）
                     S_GAP  = 3'd4;   // 帧间：CS# 拉高

    reg [2:0]  st;
    reg [1:0]  cur_ch;
    reg [4:0]  bit_i;
    reg [15:0] sh_out, sh_in;
    localparam DIV_MAX=HALF>GAP_N ? HALF : GAP_N;
    localparam DIV_RAW=$clog2(DIV_MAX);
    localparam DIV_W=DIV_RAW<1 ? 1 : DIV_RAW>16 ? 16 : DIV_RAW;
    reg [DIV_W-1:0] div;
    reg        primed;                 // 上电后第一帧丢弃
    reg [5:0]  us_div;                 // 1 µs 分频
    reg [15:0] us_cnt;                 // 本轮已耗时（µs）

    // 诊断：同一帧在 SCLK 高电平期内的 4 个不同相位各采一次，用于判定采样相位是否正确
    reg [15:0] sh_p1, sh_p2, sh_p3;
    localparam [15:0] PH1 = HALF / 4;          // 25/4 = 6
    localparam [15:0] PH2 = HALF / 2;          // 12
    localparam [15:0] PH3 = (HALF * 3) / 4;    // 18

    // 生成某通道的命令字
    function [15:0] cmd_for;
        input [1:0] ch;
        begin
            cmd_for = 16'h1800
                    | (RANGE_5V ? 16'h0040 : 16'h0000)   // DI6：量程选择
                    | ({14'd0, ch} << 7);                // DI10..7：通道地址
        end
    endfunction

    // ⚠️ 必须在"下一拍"的语境里算出下一个通道：
    //    非阻塞赋值下 cur_ch <= cur_ch+1 不会立即生效，若直接用 req_ch(=cur_ch)
    //    装载命令字，就会把"当前通道"当成"下一通道"，命令序列整体错位
    //    （codex 与 claude 独立复核都指出过这一点）。
    wire [1:0] nxt_ch       = cur_ch + 2'd1;
    wire [1:0] ch_sel_next  = (FIXED_CH >= 0) ? FIXED_CH[1:0] : nxt_ch;
    wire [1:0] ch_sel_start = (FIXED_CH >= 0) ? FIXED_CH[1:0] : 2'd0;

    reg [1:0]  cur_req;          // 本帧正在请求的通道
    reg [1:0]  req_prev;         // 1 帧前请求的通道
    reg [1:0]  req_prev2;        // 2 帧前请求的通道 = 本帧 SDO 应当回传的地址

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            st           <= S_IDLE;
            cur_ch       <= 2'd0;
            bit_i        <= 5'd15;
            sh_out       <= 16'h1800;
            sh_in        <= 16'd0;
            div          <= 16'd0;
            primed       <= 1'b0;
            busy         <= 1'b0;
            done         <= 1'b0;
            sclk         <= 1'b0;
            sdi          <= 1'b0;
            cs_n         <= 1'b1;
            raw0         <= 16'd0;
            raw1         <= 16'd0;
            raw2         <= 16'd0;
            raw3         <= 16'd0;
            frame_cnt    <= 16'd0;
            addr_err_cnt <= 16'd0;
            scan_us      <= 16'd0;
            last_word    <= 16'd0;
            ph0          <= 16'd0;
            ph1          <= 16'd0;
            ph2          <= 16'd0;
            ph3          <= 16'd0;
            sh_p1        <= 16'd0;
            sh_p2        <= 16'd0;
            sh_p3        <= 16'd0;
            us_div       <= 6'd0;
            us_cnt       <= 16'd0;
            cur_req         <= 2'd0;
            req_prev        <= 2'd0;
            req_prev2       <= 2'd0;
            ch_mismatch_cnt <= 16'd0;
        end else begin
            done <= 1'b0;

            // ---- 一轮扫描的 µs 计时 ----
            if (busy) begin
                if (us_div == US_DIV - 1) begin
                    us_div <= 6'd0;
                    if (us_cnt != 16'hFFFF) us_cnt <= us_cnt + 1'b1;
                end else begin
                    us_div <= us_div + 1'b1;
                end
            end else begin
                us_div <= 6'd0;
            end

            case (st)
                // ---------- 空闲 ----------
                S_IDLE: begin
                    cs_n <= 1'b1;
                    sclk <= 1'b0;
                    busy <= 1'b0;
                    if (start) begin
                        cur_ch <= 2'd0;
                        cur_req <= ch_sel_start;
                        sh_out  <= cmd_for(ch_sel_start);
                        bit_i  <= 5'd15;
                        div    <= 16'd0;
                        cs_n   <= 1'b0;
                        busy   <= 1'b1;
                        st     <= S_LO;
                    end
                end

                // ---------- SCLK 低：建立 SDI ----------
                S_LO: begin
                    sclk <= 1'b0;
                    sdi  <= sh_out[bit_i];
                    if (div == HALF - 1) begin
                        div <= 16'd0;
                        st  <= S_HI;
                    end else begin
                        div <= div + 1'b1;
                    end
                end

                // ---------- SCLK 高：仅在上升沿采样一次 SDO ----------
                // ⚠️ 本状态持续 HALF 个时钟，必须用 div==0 限定"每个比特只移入一次"；
                //    否则每比特会被移入 HALF 次，整帧结果退化成最后一个比特的重复
                //    （实测症状：短接自测时恒读到 0x0000 而非 0x1800）。
                S_HI: begin
                    sclk  <= 1'b1;
                    // ⚠️ 采样点必须放在高电平【中部】，不能放在上升沿那一刻：
                    //    实测（2026-09-12）相位0(上升沿)读到乱码、相位2(240ns)读到稳定值。
                    //    板上 SPI 串阻是 22Ω（不是 220Ω），RC 延迟仅约 1~2 ns，
                    //    所以这不是"线太慢"，而是 SDO 的数据翻转发生在上升沿附近
                    //    （建立/保持竞争）。采在高电平中部可同时远离两个边沿，余量最大。
                    if (div == PH2)
                        sh_in <= {sh_in[14:0], sdo};
                    // 诊断用：另外 3 个相位
                    if (div == 16'd0) sh_p1 <= {sh_p1[14:0], sdo};   // 上升沿（曾经的错误做法）
                    if (div == PH1)   sh_p2 <= {sh_p2[14:0], sdo};   // 120 ns
                    if (div == PH3)   sh_p3 <= {sh_p3[14:0], sdo};   // 360 ns
                    if (div == HALF - 1) begin
                        div <= 16'd0;
                        if (bit_i == 5'd0)
                            st <= S_END;      // 先收尾（CS# 仍低），再拉高 CS#
                        else begin
                            bit_i <= bit_i - 1'b1;
                            st    <= S_LO;
                        end
                    end else begin
                        div <= div + 1'b1;
                    end
                end

                // ---------- 收尾：CS# 保持低，先把 SCLK 拉低（第 16 个下降沿）----------
                // 手册要求 CS# 必须保持低直到 16 个 SCLK 结束之后才能拉高；
                // 若与第 16 个下降沿同拍拉高，任何 pad 偏斜都可能让帧被提前终止。
                S_END: begin
                    cs_n <= 1'b0;
                    sclk <= 1'b0;
                    st   <= S_GAP;
                end

                // ---------- 帧间：CS# 拉高，处理刚收到的字 ----------
                S_GAP: begin
                    cs_n <= 1'b1;
                    sclk <= 1'b0;
                    if (div == GAP_N - 1) begin
                        div <= 16'd0;

                        if (primed) begin
                            // 有效数据帧：按回传的高 4 位地址归档（自对齐）
                            frame_cnt <= frame_cnt + 1'b1;
                            // 真正的链路自检：本帧回传的高 4 位地址，应当是【2 帧前】
                            // 请求的通道 —— ADS7950 在 Manual 模式下是 2 帧流水线：
                            // 第 N 帧下发的通道命令，其转换结果（含地址半字节）要到第
                            // N+2 帧才回到 SDO（TI SLAS605 手册 Figure 51 的实例明写
                            // "issued in the Nth frame … available in the (N+2)th frame"）。
                            //
                            // ★踩过的坑：原判据拿它比 req_prev（1 帧前），恒差 1 →
                            //   每一帧都被判为不匹配 → ch_mismatch_cnt 恒等于 frame_cnt
                            //   （实测连续 10 次心跳里两个 16 位计数器数值完全相同）→
                            //   0x23 心跳的 flags bit0 永远为 0 → 上位机把完全正常的链路
                            //   永久显示成"失败"，还容易让人误以为是接线/供电问题。
                            //   注意 raw0..3 是按回传地址归档的，与流水线深度无关，所以
                            //   四路数据一直是对的，只有这条自检和依赖它的健康位是错的。
                            if (sh_in[15:12] != {2'b00, req_prev2})
                                ch_mismatch_cnt <= ch_mismatch_cnt + 1'b1;
                            req_prev2 <= req_prev;       // 先移位，再更新 1 帧寄存器
                            req_prev  <= cur_req;
                            last_word <= sh_in;          // 无论地址是否合法都记录，便于诊断
                            ph0 <= sh_in;                // 相位 0 = 高电平中部（当前正式使用）
                            ph1 <= sh_p1;                // 相位 1 = 上升沿（曾经的错误做法）
                            ph2 <= sh_p2;                // 相位 2 = 高电平 1/4 处
                            ph3 <= sh_p3;                // 相位 3 = 高电平 3/4 处
                            case (sh_in[15:12])
                                4'd0: raw0 <= sh_in;
                                4'd1: raw1 <= sh_in;
                                4'd2: raw2 <= sh_in;
                                4'd3: raw3 <= sh_in;
                                default: addr_err_cnt <= addr_err_cnt + 1'b1;
                            endcase
                        end else begin
                            primed    <= 1'b1;      // 丢弃上电后第一帧
                            last_word <= sh_in;
                        end

                        // 下一通道 or 结束本轮
                        if (cur_ch == LAST_CH) begin
                            busy    <= 1'b0;
                            done    <= 1'b1;
                            scan_us <= us_cnt;
                            us_cnt  <= 16'd0;
                            st      <= S_IDLE;
                        end else begin
                            cur_ch  <= nxt_ch;
                            cur_req <= ch_sel_next;
                            sh_out  <= cmd_for(ch_sel_next);
                            bit_i  <= 5'd15;
                            cs_n   <= 1'b0;
                            st     <= S_LO;
                        end
                    end else begin
                        div <= div + 1'b1;
                    end
                end

                default: st <= S_IDLE;
            endcase
        end
    end

endmodule
