// AD7147 Rev.E Fig.49/51: continuous 32-bit Mode-0 transaction.
// Always-enabled 74LVC138: park on a different chip before EVERY frame.
// All decoder address transitions occur with SCK low; parking clocks no data.
module ad7147_spi #(
    parameter CLK_DIV=24, NBYTES=4, CMD_PREFIX=16'hE000, DATA_SHIFT=0
) (
    input wire clk, rst_n, start, wr,
    input wire [2:0] chip_sel,
    input wire [9:0] addr,
    input wire [15:0] wdata,
    output reg [15:0] rdata,
    output reg done, busy, sck, mosi,
    input wire miso,
    output reg cs_n,
    output reg [2:0] decoder_addr
);
    localparam IDLE=0, BREAK=1, SETUP=2, SHIFT=3, HOLD=4, FINISH=5;
    reg [2:0] state, target;
    localparam DIV_W_RAW=$clog2(CLK_DIV+1);
    localparam DIV_W=DIV_W_RAW<1 ? 1 : DIV_W_RAW>16 ? 16 : DIV_W_RAW;
    reg [DIV_W-1:0] div;
    reg [5:0] bit_count;
    reg [31:0] tx_shift, rx_shift;
    wire [15:0] command = CMD_PREFIX | (wr ? 16'h0000 : 16'h0400) | {6'd0,addr};
    wire [2:0] physical_chip = chip_sel >= 6 ? (chip_sel ^ 3'd1) : chip_sel;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state<=IDLE; target<=0; div<=0; bit_count<=0;
            tx_shift<=0; rx_shift<=0; rdata<=0;
            done<=0; busy<=0; cs_n<=1; sck<=0; mosi<=0; decoder_addr<=0;
        end else begin
            done<=0;
            case (state)
            IDLE: if (start) begin
                target<=physical_chip;
                decoder_addr<=physical_chip ^ 3'd1;
                tx_shift<={command, wr ? wdata : 16'd0};
                rx_shift<=0; bit_count<=0; div<=0;
                mosi<=command[15]; busy<=1; state<=BREAK;
            end
            BREAK: if (div==CLK_DIV) begin
                div<=0; decoder_addr<=target; cs_n<=0; state<=SETUP;
            end else div<=div+1'b1;
            SETUP: if (div==CLK_DIV) begin
                div<=0; state<=SHIFT;
            end else div<=div+1'b1;
            SHIFT: if (div==CLK_DIV) begin
                div<=0;
                if (!sck) begin
                    sck<=1; rx_shift<={rx_shift[30:0],miso};
                end else begin
                    sck<=0;
                    if (bit_count==31) state<=HOLD;
                    else begin
                        bit_count<=bit_count+1'b1;
                        tx_shift<={tx_shift[30:0],1'b0}; mosi<=tx_shift[30];
                    end
                end
            end else div<=div+1'b1;
            HOLD: if (div==CLK_DIV) begin
                div<=0; cs_n<=1; decoder_addr<=target ^ 3'd1;
                rdata<=DATA_SHIFT==0 ? rx_shift[15:0] : rx_shift[16:1];
                state<=FINISH;
            end else div<=div+1'b1;
            FINISH: begin busy<=0; done<=1; mosi<=0; state<=IDLE; end
            default: state<=IDLE;
            endcase
        end
    end
endmodule
