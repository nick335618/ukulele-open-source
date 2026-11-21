// ============================================================================
// spi_master.v —— 通用 SPI 主机（Mode 0：CPOL=0, CPHA=0），单字节全双工
// ----------------------------------------------------------------------------
// 用途：和 12 品格电容触摸板的控制器通信，周期性地把触摸状态读回来。
//
// 为什么做成"通用"：你手上的触摸板控制器型号还没最终确认（丝印 LP5860RKPR
// 是 TI 的 18 通道 LED 驱动，不是触摸控制器），所以这里把"发什么"做成参数，
// 上板用逻辑分析仪抓到真实时序后，只改 top 里的参数或换一条命令字即可。
//
// 时序（Mode 0）：
//   CS 拉低 -> 在 SCK 下降沿改变 MOSI -> 在 SCK 上升沿双方采样
//   共 8 个 SCK 周期，MSB 先出。CS 在最后一个上升沿后保持半个周期再拉高。
//
// 时钟：SCK 半周期 = (CLK_DIV+1) 个 clk。
//   50 MHz / (CLK_DIV+1) / 2 = SCK 频率。CLK_DIV=49 -> 半周期 1us -> SCK 500kHz
//   触摸控制器内部转换很慢（一般 <400kHz~几 MHz），500kHz 非常安全。
//   想快就把 CLK_DIV 调小（官方数据手册允许的上限内）。
// ============================================================================
module spi_master #(
    parameter CLK_DIV = 49          // SCK 半周期 = CLK_DIV+1 个 clk（默认 1us @50MHz）
) (
    input  wire       clk,
    input  wire       rst_n,
    // 请求接口
    input  wire       start,        // 单拍脉冲：发起一次 8bit 全双工传输
    input  wire [7:0] tx_byte,      // 要发出去的字节（MSB 先）
    output reg  [7:0] rx_byte,      // 收到的字节（MSB 先）
    output reg        done,         // 单拍脉冲：传输完成，rx_byte 有效
    output reg        busy,
    // 物理接口
    output reg        sck,
    output reg        mosi,
    input  wire       miso,
    output reg        cs_n          // 低有效片选
);
    localparam S_IDLE  = 2'd0;
    localparam S_CS    = 2'd1;      // CS 拉低 + 建立时间
    localparam S_SHIFT = 2'd2;      // 8 个 SCK 周期
    localparam S_TAIL  = 2'd3;      // SCK 回低 + CS 拉高

    reg [1:0]  state;
    reg [15:0] div;
    reg [3:0]  bitcnt;
    reg [7:0]  sh_tx;
    reg [7:0]  sh_rx;
    reg        sck_phase;           // 0=要产生上升沿, 1=要产生下降沿

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= S_IDLE;
            div       <= 16'd0;
            bitcnt    <= 4'd0;
            sh_tx     <= 8'd0;
            sh_rx     <= 8'd0;
            sck_phase <= 1'b0;
            sck       <= 1'b0;
            mosi      <= 1'b0;
            cs_n      <= 1'b1;
            rx_byte   <= 8'd0;
            done      <= 1'b0;
            busy      <= 1'b0;
        end else begin
            done <= 1'b0;
            case (state)
                // ---------------- 空闲 ----------------
                S_IDLE: begin
                    sck  <= 1'b0;
                    cs_n <= 1'b1;
                    if (start) begin
                        sh_tx  <= tx_byte;
                        mosi   <= tx_byte[7];
                        bitcnt <= 4'd0;
                        div    <= 16'd0;
                        busy   <= 1'b1;
                        cs_n   <= 1'b0;
                        state  <= S_CS;
                    end
                end

                // ---------------- CS 建立 ----------------
                S_CS: begin
                    if (div == CLK_DIV) begin
                        div       <= 16'd0;
                        sck_phase <= 1'b0;
                        state     <= S_SHIFT;
                    end else
                        div <= div + 16'd1;
                end

                // ---------------- 8 个 SCK 周期 ----------------
                S_SHIFT: begin
                    if (div == CLK_DIV) begin
                        div <= 16'd0;
                        if (!sck_phase) begin
                            // 上升沿：双方采样
                            sck   <= 1'b1;
                            sh_rx <= {sh_rx[6:0], miso};
                            sck_phase <= 1'b1;
                        end else begin
                            // 下降沿：主机改 MOSI
                            sck <= 1'b0;
                            if (bitcnt == 4'd7) begin
                                state  <= S_TAIL;
                                bitcnt <= 4'd0;
                            end else begin
                                bitcnt <= bitcnt + 4'd1;
                                sh_tx  <= {sh_tx[6:0], 1'b0};
                                mosi   <= sh_tx[6];
                            end
                            sck_phase <= 1'b0;
                        end
                    end else
                        div <= div + 16'd1;
                end

                // ---------------- 收尾 ----------------
                S_TAIL: begin
                    sck <= 1'b0;
                    if (div == CLK_DIV) begin
                        div      <= 16'd0;
                        cs_n     <= 1'b1;
                        rx_byte  <= sh_rx;
                        done     <= 1'b1;
                        busy     <= 1'b0;
                        state    <= S_IDLE;
                    end else
                        div <= div + 16'd1;
                end

                default: state <= S_IDLE;
            endcase
        end
    end
endmodule
