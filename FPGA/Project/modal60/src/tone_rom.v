// ============================================================================
// tone_rom.v -- synchronous BSRAM parameter table used by additive4_tone.
//
// Power-up contents are the verified pre-fusion rec28 table. The optional
// write port is kept active so ESP32 can load a complete bank at runtime;
// when no TONEWR frames are sent, the power-up sound is unchanged.
// ============================================================================
module tone_rom (
    input  wire        clk,
    input  wire [8:0]  addr,
    output reg  [31:0] inc,
    output reg  [15:0] env0,
    output reg  [11:0] rate,
    output reg  [15:0] aatk,
    output reg  [31:0] ph0,

    // Runtime TONEWR port: one synchronous write per accepted frame.
    input  wire        we,
    input  wire [1:0]  wsel,
    input  wire [8:0]  waddr,
    input  wire [31:0] wdata
);
    localparam integer DEPTH = 512;

    reg [31:0] mem_inc  [0:DEPTH-1];
    reg [15:0] mem_env0 [0:DEPTH-1];
    reg [11:0] mem_rate [0:DEPTH-1];
    reg [15:0] mem_aatk [0:DEPTH-1];
    reg [31:0] mem_ph0  [0:DEPTH-1];

    integer fi;
    initial begin
        for (fi = 0; fi < DEPTH; fi = fi + 1) begin
            mem_inc[fi]  = 32'd0;
            mem_env0[fi] = 16'd0;
            mem_rate[fi] = 12'd0;
            mem_aatk[fi] = 16'd0;
            mem_ph0[fi]  = 32'd0;
        end
        // Generated from the pre-fusion partial bank.
        $readmemh("src/fpga_mem/inc.hex",  mem_inc);
        $readmemh("src/fpga_mem/env0.hex", mem_env0);
        $readmemh("src/fpga_mem/rate.hex", mem_rate);
        $readmemh("src/fpga_mem/aatk.hex", mem_aatk);
        $readmemh("src/fpga_mem/ph0.hex",  mem_ph0);
    end

    wire hit_inc  = we && (wsel == 2'd0) && (waddr == addr);
    wire hit_env0 = we && (wsel == 2'd1) && (waddr == addr);
    wire hit_rate = we && (wsel == 2'd2) && (waddr == addr);
    wire hit_aatk = we && (wsel == 2'd3) && (waddr == addr);

    // Synchronous read, one clock latency, identical to the pre-fusion ROM.
    // Write-through bypass avoids a read-before-write BSRAM mode on GW5A.
    always @(posedge clk) begin
        if (we) begin
            case (wsel)
                2'd0:    mem_inc [waddr] <= wdata;
                2'd1:    mem_env0[waddr] <= wdata[15:0];
                2'd2:    mem_rate[waddr] <= wdata[11:0];
                default: mem_aatk[waddr] <= wdata[15:0];
            endcase
        end
        inc  <= hit_inc  ? wdata[31:0] : mem_inc [addr];
        env0 <= hit_env0 ? wdata[15:0] : mem_env0[addr];
        rate <= hit_rate ? wdata[11:0] : mem_rate[addr];
        aatk <= hit_aatk ? wdata[15:0] : mem_aatk[addr];
        ph0  <= mem_ph0[addr];
    end
endmodule
