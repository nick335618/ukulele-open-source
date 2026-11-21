// ============================================================================
// uart_rx.v —— 通用 UART 接收器（8N1，16× 过采样，参数化 CLK_HZ/BAUD）
// ----------------------------------------------------------------------------
// 用途：上位机（host/index.html，Web Serial）→ FPGA 的串口字节接收。
//       本模块只做"串口字节流"→ rx_byte/rx_valid 的异步串行接收；
//       组帧/CRC/命令解码在 cmd_decoder.v 完成（见 host/PROTOCOL.md §1）。
//
// 特性：
//   - 标准 UART：1 起始位(低)、8 数据位(LSB first)、无校验、1 停止位(高)。
//   - 16× 过采样：每 bit 16 个采样点，采样各 bit 中点（抗抖动/失真，容忍 ±5% 波特率误差）。
//   - rx 先经 2 级触发器同步到本时钟域，避免亚稳态。
//   - 输出 rx_valid 为 1 clk 脉冲（收到一个完整、停止位正确的字节）。
//
// 采样相位：检测到起始边沿后，用连续相位计数 scnt（每样点 +1）从 0 起。
//   各 bit 中点落在 scnt = OS/2 + k*OS（k=0..WIDTH+1），用 scnt[3:0]==OS_HALF 命中。
//
// 参数：
//   CLK_HZ 主时钟频率（默认 50MHz）
//   BAUD    波特率（默认 115200）
//   OS      每 bit 采样数（默认 16，须为 2 的幂）
//   WIDTH   数据位宽（默认 8）
// ============================================================================
module uart_rx #(
    parameter CLK_HZ = 50_000_000,
    parameter BAUD   = 115_200,
    parameter OS     = 16,
    parameter WIDTH  = 8
) (
    input  wire                 clk,
    input  wire                 rst_n,
    input  wire                 rx,                       // 串口数据输入（TTL 高=空闲）
    output reg  [WIDTH-1:0]     rx_byte,                  // 收到的字节
    output reg                  rx_valid                  // 1 clk 脉冲，收到完整字节
);

    // 每个"样点"的时钟周期数 = CLK_HZ / (BAUD * OS)
    localparam integer DIV = (CLK_HZ + (BAUD * OS) / 2) / (BAUD * OS);
    localparam [3:0] OS_HALF = OS / 2;       // 一个 bit 的一半（采样中点）

    // ---------------- 亚稳态同步 ----------------
    reg rx_q1, rx_q2;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            rx_q1 <= 1'b1;
            rx_q2 <= 1'b1;
        end else begin
            rx_q1 <= rx;
            rx_q2 <= rx_q1;
        end
    end

    // ---------------- 采样时钟（每 DIV 个 clk 出一个 sample 脉冲）----------------
    reg [31:0] clk_cnt;
    reg        sample;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            clk_cnt <= 32'd0;
            sample  <= 1'b0;
        end else begin
            if (clk_cnt >= DIV - 1) begin
                clk_cnt <= 32'd0;
                sample  <= 1'b1;          // 本拍对 rx_q2 采样
            end else begin
                clk_cnt <= clk_cnt + 32'd1;
                sample  <= 1'b0;
            end
        end
    end

    // ---------------- 状态机 ----------------
    localparam [1:0] ST_IDLE  = 2'd0;
    localparam [1:0] ST_START = 2'd1;
    localparam [1:0] ST_DATA  = 2'd2;
    localparam [1:0] ST_STOP  = 2'd3;

    reg       [1:0]  state;
    reg       [31:0] scnt;                // 自起始边沿起的采样相位计数（连续，跨字节不复位）
    reg       [3:0]  bit_idx;             // 0=start, 1..WIDTH=data, WIDTH+1=stop
    reg       [WIDTH-1:0] shreg;          // 数据位移（LSB first）
    reg                 rx_prev;          // 上一拍采样到的 RX 电平（起始边沿检测）

    wire                center = (scnt[3:0] == OS_HALF);    // 命中某 bit 中点

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= ST_IDLE;
            scnt      <= 32'd0;
            bit_idx   <= 4'd0;
            shreg     <= {WIDTH{1'b0}};
            rx_prev   <= 1'b1;
            rx_byte   <= {WIDTH{1'b0}};
            rx_valid  <= 1'b0;
        end else begin
            rx_valid <= 1'b0;            // 默认拉低
            if (sample) begin
                case (state)
                ST_IDLE: begin
                    // 检测起始边沿：RX 由高(空闲)落到低 -> 新字节开始
                    if (rx_prev && !rx_q2) begin
                        state   <= ST_START;
                        scnt    <= 32'd0;
                        bit_idx <= 4'd0;
                    end
                end
                ST_START: begin
                    scnt <= scnt + 32'd1;
                    if (center) begin
                        if (!rx_q2) begin            // 起始位中点确认为低：有效
                            state   <= ST_DATA;
                            bit_idx <= 4'd1;         // 下一命中 = data bit0
                        end else begin
                            state <= ST_IDLE;        // 假起始/毛刺
                        end
                    end
                end
                ST_DATA: begin
                    scnt <= scnt + 32'd1;
                    if (center) begin
                        shreg[bit_idx[3:0] - 4'd1] <= rx_q2;
                        if (bit_idx == WIDTH) begin
                            state   <= ST_STOP;
                            bit_idx <= 4'd0;
                        end else begin
                            bit_idx <= bit_idx + 4'd1;
                        end
                    end
                end
                ST_STOP: begin
                    scnt <= scnt + 32'd1;
                    if (center) begin
                        if (rx_q2) begin             // 停止位为高：帧有效
                            rx_byte  <= shreg;
                            rx_valid <= 1'b1;
                        end
                        // 否则帧错误（波特率不对/毛刺），丢弃
                        state <= ST_IDLE;
                    end
                end
                default: state <= ST_IDLE;
                endcase
                rx_prev <= rx_q2;        // 只在“采样拍”更新：比较相邻两次采样电平
            end
            // 注意：rx_prev 必须放在 if(sample) 内。若每拍都更新，采样拍时 rx_prev
            // 已等于上一拍 rx_q2，起始位下降沿几乎永远检测不到（收不到任何字节）。
        end
    end

endmodule
