// ============================================================================
// uart_tx.v —— 通用 UART 发送器（8N1，参数化 CLK_HZ/BAUD）
// ----------------------------------------------------------------------------
// 用途：FPGA → 上位机（host/index.html）。目前仅用于回 PONG（回应 PING，链路自检），
//       host/PROTOCOL.md §4。若以后需要状态回读，可复用本模块。
// 行为：tx_start 脉冲捕获 tx_byte，之后逐位发送 起始位(0) + 8 数据位(LSB first)
//       + 停止位(1)，空闲时 tx_out=1。tx_busy 忙时忽略新的 tx_start。
// 时序：每 bit 占 BIT_TICKS = CLK_HZ/BAUD 个 clk；起始位触发后 tcnt 倒计数。
// ============================================================================
module uart_tx #(
    parameter CLK_HZ = 50_000_000,
    parameter BAUD   = 115_200,
    parameter WIDTH  = 8
) (
    input  wire                 clk,
    input  wire                 rst_n,
    input  wire                 tx_start,        // 触发发送（1 clk 脉冲）
    input  wire [WIDTH-1:0]     tx_byte,         // 要发送的字节
    output reg                  tx_out,          // 串口数据输出（TTL 高=空闲）
    output wire                 tx_busy,         // 忙（=内部 busy，供上层握手）
    output reg                  tx_done          // 发送完成（1 clk 脉冲）
);

    localparam integer BIT_TICKS = (CLK_HZ + (BAUD / 2)) / BAUD;

    reg [31:0]   tcnt;
    reg [3:0]    bitidx;        // 0=start, 1..WIDTH=data, WIDTH+1=stop
    reg [WIDTH-1:0] tsr;
    reg          busy;

    assign tx_busy = busy;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            tcnt    <= 32'd0;
            bitidx  <= 4'd0;
            tsr     <= {WIDTH{1'b0}};
            busy    <= 1'b0;
            tx_out  <= 1'b1;
            tx_done <= 1'b0;
        end else begin
            tx_done <= 1'b0;
            if (tx_start && !busy) begin
                busy    <= 1'b1;
                tsr     <= tx_byte;
                bitidx  <= 4'd0;
                tcnt    <= BIT_TICKS - 1;
                tx_out  <= 1'b0;                 // 起始位
            end else if (busy) begin
                if (tcnt == 32'd0) begin
                    if (bitidx == WIDTH + 1) begin
                        // 停止位已发满一个位宽：结束
                        busy    <= 1'b0;
                        tx_out  <= 1'b1;         // 空闲
                        tx_done <= 1'b1;
                    end else begin
                        // 进入下一位：先按"当前位号"决定下一位电平，再推进位号
                        //   bitidx=0(起始)   -> 数据 bit0
                        //   bitidx=1..W-1    -> 数据 bit(bitidx)
                        //   bitidx=W         -> 停止位
                        if (bitidx == 4'd0)
                            tx_out <= tsr[0];                       // 离开起始位 -> data0
                        else if (bitidx <= WIDTH - 1)
                            tx_out <= tsr[bitidx[2:0]];             // 数据 LSB first
                        else
                            tx_out <= 1'b1;                         // 停止位
                        bitidx  <= bitidx + 4'd1;
                        tcnt    <= BIT_TICKS - 1;
                    end
                end else begin
                    tcnt <= tcnt - 32'd1;
                end
            end else begin
                tx_out <= 1'b1;   // idle
            end
        end
    end

endmodule
