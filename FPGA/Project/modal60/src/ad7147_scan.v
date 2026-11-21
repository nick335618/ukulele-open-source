// Final AD7147 SPI scanner: addressed CDC RAM and batch startup calibration.
// Reset/configure all chips, calibrate all electrodes by passes, then force
// ambient calibration on all chips together. SPI operation remains shared.
// Runtime waits a full conversion interval before each scan: identical CDC
// values are still NEW samples and must advance learning/debounce counters.
`include "touch_tables.vh"
module ad7147_scan #(
    parameter CLK_HZ=50_000_000, SENSOR_SPI=1,
    parameter N_CHIPS=`TT_N_CHIPS, SP_CH=12, N_CH=`TT_NCH,
    parameter DECIM=128, SCL_HZ=200_000, SPI_CLK_DIV=24, DATA_SHIFT=0,
    parameter CDC_BUS_ENABLE=0, // optional legacy/debug mirror; release uses RAM
    parameter [6:0] DEV_BASE=7'h2C,
    parameter AMB0_V=16'h3230, AMB1_V=16'h0419, AMB2_V=16'h0832,
    parameter VAL_AMB0_FORCE=16'h7230,
    parameter REG_PWR=10'h000, REG_CAL=10'h001, REG_AMB0=10'h002,
    parameter REG_AMB1=10'h003, REG_AMB2=10'h004,
    parameter REG_INT_LOW=10'h005, REG_INT_HIGH=10'h006, REG_INT_CMPL=10'h007,
    parameter REG_B2=10'h080, REG_DEVID=10'h017, REG_CDC0=10'h00B,
    parameter DEVID_EXP=16'h1470, DEVID_MASK=16'hFFF0,
    parameter PWR_MS=5, CFG_MS=0, FRC_MS=150, ON_MS=2, RESET_MS=40,
    parameter ID_READ_TRIES=3, ID_RETRY_MS=10,
    parameter INIT_RETRY_MS=500, INIT_SLOW_MS=2000, INIT_FAST_RETRIES=3
)(
    input wire clk,rst_n,
    // Shared SPI arbitration.  When asserted, wait before starting the next
    // AD7147 transaction; an already active transaction is allowed to finish.
    input wire spi_block,
    input wire sleep_req,
    input wire rate_fast,
    output reg rate_applied_fast,
    output wire rate_busy,
    output reg rate_fault,
    output wire asleep,
    output wire spi_busy,
    output wire i2c_scl_oe,i2c_sda_oe,
    input wire i2c_sda_in,
    output wire spi_sck,spi_mosi,
    input wire spi_miso,
    output wire [2:0] spi_addr,
    output wire [N_CHIPS-1:0] spi_cs_n,
    output wire [N_CH*16-1:0] cdc_bus,
    input wire [6:0] cdc_addr,
    output reg [15:0] cdc_data,
    output reg sample_valid,sample_dup,panel_ready,sensor_ok,
    output reg [N_CHIPS-1:0] id_ok,
    output reg [15:0] dev_id0,dev_id1,
    output reg [3:0] err_cnt,
    output reg [N_CHIPS*16-1:0] dev_ids,
    output reg [7:0] init_retries,id_retries,
    output reg [N_CHIPS-1:0] last_fail_mask,
    output wire [4:0] init_state
);
    localparam [2:0] CHIP_LAST=N_CHIPS-1;
    localparam [3:0] STG_LAST=SP_CH-1;
    localparam CONV_MS=(DECIM==64) ? (SP_CH*768+999)/1000 :
                      (DECIM==128) ? (SP_CH*1536+999)/1000 : (SP_CH*3072+999)/1000;
    localparam PWR_WAIT=(CLK_HZ/1000)*PWR_MS;
    localparam RESET_WAIT=(CLK_HZ/1000)*RESET_MS;
    reg rate_target,rate_switch;
    wire [31:0] CONV_WAIT=(CLK_HZ/1000)*(rate_target ? (SP_CH*768+999)/1000 : (SP_CH*1536+999)/1000);
    assign rate_busy=rate_switch || rate_fast!=rate_applied_fast;
    localparam FRC_WAIT=(CLK_HZ/1000)*FRC_MS;
    localparam CFG_WAIT=(CLK_HZ/1000)*CFG_MS;
    localparam ID_RETRY_WAIT=(CLK_HZ/1000)*ID_RETRY_MS;
    localparam INIT_RETRY_WAIT=(CLK_HZ/1000)*INIT_RETRY_MS;
    localparam INIT_SLOW_WAIT=(CLK_HZ/1000)*INIT_SLOW_MS;
    localparam [15:0] DECIM_BITS=(DECIM==64) ? 16'h0200 : (DECIM==128) ? 16'h0100 : 16'd0;
    wire [15:0] PWR_CONTROL_V=16'h8000 | ((SP_CH-1)<<4) | (rate_target ? 16'h0200 : 16'h0100);
    localparam [15:0] STG_CAL_V=(16'h0001<<SP_CH)-16'h0001;
    localparam [7:0] IDX_B1_0=1+8*SP_CH, INIT_LAST=IDX_B1_0+9;

    localparam S_MEM_INIT=0, S_PWR_WAIT=1, S_RESET=2, S_RESET_END=3,
        S_WAIT=4, S_CONFIG=5, S_CONFIG_END=6, S_CONFIG_NEXT=7,
        S_ID=8, S_ID_END=9, S_AFE_FETCH=10, S_AFE_READ=11,
        S_AFE_CHECK=12, S_AFE_WRITE=13, S_AFE_WEND=14, S_AFE_NEXT=15,
        S_CAL=16, S_CAL_END=17, S_SCAN=18, S_SCAN_END=19,
        S_SCAN_NEXT=20, S_FAULT=21, S_VERIFY=22, S_VERIFY_END=23,
        S_ID_NEXT=24, S_RESTART=25,
        S_SLEEP_WRITE=26,S_SLEEP_END=27,S_ASLEEP=28,S_WAKE_WRITE=29,S_WAKE_END=30,
        S_RATE_WRITE=31,S_RATE_END=32,S_RATE_READ=33,S_RATE_REND=34,
        S_AUX_IO=35,S_AUX_DONE=36;
    reg [5:0] st,after_wait;
    localparam AUX_H8 = SENSOR_SPI && N_CH==97 && N_CHIPS==8 && SP_CH==12;
    reg aux_turn;
    reg [3:0] aux_step;
    reg [5:0] h8_afe, h0_afe;
    reg [2:0] h8_pass;
    reg h8_ready;
    wire [5:0] h8_step=h8_pass<5 ? (6'd16>>h8_pass) : 6'd1;
    wire [6:0] h8_plus={1'b0,h8_afe}+{1'b0,h8_step};
    reg wake_verify;
    assign asleep=st==S_ASLEEP;
    assign init_state=st>30 ? 5'd31 : st[4:0];
    function [31:0] wait_max(input [31:0] a,input [31:0] b);
        begin wait_max=a>b ? a:b;end
    endfunction
    localparam [31:0] MAX_WAIT=wait_max(wait_max(wait_max(PWR_WAIT,RESET_WAIT),wait_max(FRC_WAIT,CFG_WAIT)),
        wait_max(wait_max(ID_RETRY_WAIT,INIT_RETRY_WAIT),wait_max(INIT_SLOW_WAIT,(CLK_HZ/1000)*((SP_CH*1536+999)/1000)*2)));
    localparam WAIT_W=MAX_WAIT<1 ? 1 : $clog2({1'b0,MAX_WAIT}+1);
    reg [WAIT_W-1:0] wait_cnt,wait_limit;
    reg [2:0] chip,afe_pass;
    reg [3:0] ch_idx;
    reg [7:0] ini_idx;
    reg [6:0] mem_init_addr;
    reg [5:0] afe_value;
    reg [1:0] cal_phase;
    reg pass_changed,id_ok_acc;
    reg cfg_ok_acc;
    reg [N_CHIPS-1:0] cfg_fail_mask;
    reg [7:0] id_try;
    reg t_start,t_wr,t_abort;
    reg [2:0] t_chip;
    reg [9:0] t_addr;
    reg [15:0] t_wdata;
    wire [15:0] t_rdata;
    wire t_busy,t_done,t_err;
    // Include the one-cycle launch pulse.  sensor_reg_if samples t_start on
    // the following clock, so exposing only t_busy would leave a one-cycle
    // window in which the LED arbiter could start its own SPI transaction.
    assign spi_busy = t_busy || t_start;
    wire id_valid=!t_err && ((t_rdata&DEVID_MASK)==(DEVID_EXP&DEVID_MASK));
    reg [19:0] tmo_cnt;
    wire [6:0] t_dev=DEV_BASE+{4'd0,t_chip[0],1'b0};

    sensor_reg_if #(.SENSOR_SPI(SENSOR_SPI),.N_CHIPS(N_CHIPS),.CLK_HZ(CLK_HZ),
        .SCL_HZ(SCL_HZ),.SPI_CLK_DIV(SPI_CLK_DIV),.DATA_SHIFT(DATA_SHIFT)) u_reg (
        .clk(clk),.rst_n(rst_n),.start(t_start),.abort(t_abort),
        .wr(t_wr),.chip_sel(t_chip),.dev(t_dev),.addr(t_addr),.wdata(t_wdata),
        .rdata(t_rdata),.done(t_done),.busy(t_busy),.err(t_err),
        .i2c_scl_oe(i2c_scl_oe),.i2c_sda_oe(i2c_sda_oe),.i2c_sda_in(i2c_sda_in),
        .spi_sck(spi_sck),.spi_mosi(spi_mosi),.spi_miso(spi_miso),
        .spi_cs_n(spi_cs_n),.spi_addr(spi_addr)
    );

    wire [6:0] compact_ch=stage_global_ch(chip,ch_idx);
    wire [5:0] afe_step=afe_pass<5 ? (6'd16>>afe_pass) : 6'd1;
    wire [6:0] afe_plus={1'b0,afe_value}+{1'b0,afe_step};
    wire afe_in_band=(t_rdata>=16'd31300 && t_rdata<=16'd34100);
    // Startup scratch uses the SAME RAM as runtime CDC. bit6=converged,
    // bits5:0=POS_AFE_OFFSET. Runtime overwrites all entries before valid.
    reg [15:0] cdc_mem[0:127] /* synthesis syn_ramstyle = "block_ram" */;
    wire [6:0] ram_raddr=panel_ready ? cdc_addr : compact_ch;
    wire mark_converged=st==S_AFE_CHECK && t_done && (afe_in_band || afe_pass==6);
    wire afe_written=st==S_AFE_WEND && t_done;
    wire scan_written=st==S_SCAN_END && t_done && compact_ch<N_CH;
    wire h8_written=AUX_H8 && st==S_AUX_DONE && aux_step==6 && t_done && !t_err;
    wire ram_we=st==S_MEM_INIT || mark_converged || afe_written || scan_written || h8_written;
    wire [6:0] ram_waddr=st==S_MEM_INIT ? mem_init_addr : h8_written ? 7'd96 : compact_ch;
    wire [15:0] ram_wdata=st==S_MEM_INIT ? 16'd32 :
                         scan_written ? (t_err ? 16'd0 : t_rdata) :
                         h8_written ? (h8_ready || afe_in_band ? t_rdata : 16'd0) :
                         {9'd0,mark_converged,afe_value};
    always @(posedge clk) begin
        if (ram_we) cdc_mem[ram_waddr]<=ram_wdata;
        // Explicit write-through avoids unsupported read-before-write modes.
        cdc_data <= (ram_we && ram_waddr==ram_raddr) ? ram_wdata : cdc_mem[ram_raddr];
    end
    generate if (CDC_BUS_ENABLE) begin : g_debug_bus
        reg [15:0] shadow[0:N_CH-1];
        integer n;
        always @(posedge clk or negedge rst_n)
            if (!rst_n) begin
                for(n=0;n<N_CH;n=n+1) shadow[n]<=0;
            end else if(scan_written || h8_written) shadow[ram_waddr]<=ram_wdata;
        genvar d;
        for(d=0;d<N_CH;d=d+1) begin : g_flat
            assign cdc_bus[d*16+:16]=shadow[d];
        end
    end else begin : g_no_debug_bus
        assign cdc_bus={N_CH*16{1'b0}};
    end endgenerate

    wire [7:0] b2_off=ini_idx-1;
    wire [3:0] b2_stg=b2_off[6:3];
    reg [9:0] ini_reg;
    reg [15:0] ini_val;
    always @(*) begin
        if (ini_idx<IDX_B1_0) begin
            ini_reg=REG_B2+{b2_stg,3'd0}+{7'd0,b2_off[2:0]};
            case(b2_off[2:0])
                0: ini_val=stage_conn_a(chip,b2_stg);
                1: ini_val=stage_conn_b(chip,b2_stg);
                2: ini_val=stage_afe_word(chip,b2_stg);
                3: ini_val=`TT_SENSITIVITY;
                4,5: ini_val=`TT_OFFSET_VAL;
                default: ini_val=`TT_OFFSET_CLAMP;
            endcase
        end else begin
            case(ini_idx-IDX_B1_0)
                0: begin ini_reg=REG_PWR; ini_val=0; end
                1: begin ini_reg=REG_CAL; ini_val=0; end
                2: begin ini_reg=REG_AMB0; ini_val=AMB0_V; end
                3: begin ini_reg=REG_AMB1; ini_val=AMB1_V; end
                4: begin ini_reg=REG_AMB2; ini_val=AMB2_V; end
                5: begin ini_reg=REG_INT_LOW; ini_val=0; end
                6: begin ini_reg=REG_INT_HIGH; ini_val=0; end
                7: begin ini_reg=REG_INT_CMPL; ini_val=0; end
                8: begin ini_reg=REG_PWR; ini_val=PWR_CONTROL_V; end
                default: begin ini_reg=REG_CAL; ini_val=0; end
            endcase
        end
    end

    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            rate_target<=DECIM==64;rate_applied_fast<=DECIM==64;rate_switch<=0;rate_fault<=0;
            wake_verify<=0;
            aux_turn<=0;aux_step<=0;h8_afe<=32;h0_afe<=32;h8_pass<=0;h8_ready<=0;
            st<=S_MEM_INIT; after_wait<=S_CONFIG;
            wait_cnt<=0; wait_limit<=0; mem_init_addr<=0;
            chip<=0; ch_idx<=0; ini_idx<=1; afe_pass<=0; afe_value<=32;
            cal_phase<=0; pass_changed<=0; id_ok_acc<=1;
            cfg_ok_acc<=1; cfg_fail_mask<=0; id_try<=0;
            t_start<=0; t_wr<=0; t_abort<=0; t_chip<=0; t_addr<=0; t_wdata<=0;
            tmo_cnt<=0; sample_valid<=0; sample_dup<=0;
            panel_ready<=0; sensor_ok<=0; id_ok<=0; dev_id0<=0; dev_id1<=0; err_cnt<=0;
            dev_ids<=0; init_retries<=0; id_retries<=0; last_fail_mask<=0;
        end else begin
            t_start<=0; t_abort<=0; sample_valid<=0; sample_dup<=0;
            if(t_done && t_err && err_cnt<15) err_cnt<=err_cnt+1'b1;
            if(t_busy) begin
                if(tmo_cnt>=20'd1000000) begin t_abort<=1; tmo_cnt<=0; end
                else tmo_cnt<=tmo_cnt+1'b1;
            end else tmo_cnt<=0;

            case(st)
            S_MEM_INIT: if(mem_init_addr==N_CH-1) begin
                wait_cnt<=0; st<=S_PWR_WAIT;
            end else mem_init_addr<=mem_init_addr+1'b1;
            S_PWR_WAIT: if(wait_cnt>=PWR_WAIT) begin
                chip<=0; wait_cnt<=0; st<=S_RESET;
            end else wait_cnt<=wait_cnt+1'b1;
            S_RESET: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=1; t_chip<=chip; t_addr<=REG_PWR;
                t_wdata<=PWR_CONTROL_V|16'h0400; st<=S_RESET_END;
            end
            S_RESET_END: if(t_done) begin
                if(chip<CHIP_LAST) begin chip<=chip+1'b1; st<=S_RESET; end
                else begin
                    chip<=0; ini_idx<=1; wait_cnt<=0; wait_limit<=RESET_WAIT;
                    after_wait<=S_CONFIG; st<=S_WAIT;
                end
            end
            S_WAIT: if(wait_cnt>=wait_limit) begin wait_cnt<=0; st<=after_wait; end
                    else wait_cnt<=wait_cnt+1'b1;
            S_CONFIG: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=1; t_chip<=chip; t_addr<=ini_reg;
                t_wdata<=ini_val; st<=S_CONFIG_END;
            end
            S_CONFIG_END: if(t_done) begin
                if(CFG_MS!=0) begin
                    wait_cnt<=0; wait_limit<=CFG_WAIT; after_wait<=S_CONFIG_NEXT; st<=S_WAIT;
                end else st<=S_CONFIG_NEXT;
            end
            S_CONFIG_NEXT: begin
                if(ini_idx<INIT_LAST) begin ini_idx<=ini_idx+1'b1; st<=S_CONFIG; end
                else if(chip<CHIP_LAST) begin chip<=chip+1'b1; ini_idx<=1; st<=S_CONFIG; end
                else begin
                    chip<=0; id_ok_acc<=1; id_try<=0;
                    cfg_ok_acc<=1; cfg_fail_mask<=0;
                    wait_cnt<=0; wait_limit<=CONV_WAIT*2;
                    after_wait<=S_ID; st<=S_WAIT;
                end
            end
            S_ID: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=0; t_chip<=chip; t_addr<=REG_DEVID; st<=S_ID_END;
            end
            S_ID_END: if(t_done) begin
                dev_ids[chip*16+:16]<=t_rdata;
                if(chip==0) dev_id0<=t_rdata;
                if(chip==1) dev_id1<=t_rdata;
                if(!id_valid && id_try+1<ID_READ_TRIES) begin
                    id_try<=id_try+1'b1;
                    if(id_retries!=8'hFF) id_retries<=id_retries+1'b1;
                    wait_cnt<=0; wait_limit<=ID_RETRY_WAIT;
                    after_wait<=S_ID; st<=S_WAIT;
                end else begin
                    id_ok[chip]<=id_valid;
                    id_ok_acc<=id_ok_acc && id_valid;
                    // A valid ID alone does not prove that configuration writes
                    // survived startup. Check the stable power/sequencer word.
                    st<=id_valid ? S_VERIFY : S_ID_NEXT;
                end
            end
            S_VERIFY: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=0; t_chip<=chip; t_addr<=REG_PWR;
                st<=S_VERIFY_END;
            end
            S_VERIFY_END: if(t_done) begin
                cfg_fail_mask[chip]<=t_err || t_rdata!=PWR_CONTROL_V;
                cfg_ok_acc<=cfg_ok_acc && !t_err && t_rdata==PWR_CONTROL_V;
                st<=S_ID_NEXT;
            end
            S_ID_NEXT: begin
                id_try<=0;
                if(chip<CHIP_LAST) begin chip<=chip+1'b1; st<=S_ID; end
                else begin
                    sensor_ok<=id_ok_acc;
                    chip<=0; ch_idx<=0; afe_pass<=0; pass_changed<=0;
                    if(wake_verify && id_ok_acc && cfg_ok_acc)begin
                        wake_verify<=0;panel_ready<=1;
                        wait_cnt<=0;wait_limit<=CONV_WAIT;after_wait<=S_SCAN;st<=S_WAIT;
                    end else if(id_ok_acc && cfg_ok_acc) st<=S_AFE_FETCH;
                    else begin
                        last_fail_mask<=(~id_ok)|cfg_fail_mask;
                        wait_cnt<=0;
                        wait_limit<=init_retries<INIT_FAST_RETRIES ? INIT_RETRY_WAIT : INIT_SLOW_WAIT;
                        st<=S_FAULT;
                    end
                end
            end
            S_AFE_FETCH: st<=S_AFE_READ; // synchronous scratch-RAM latency
            S_AFE_READ: begin
                if(compact_ch>=N_CH || cdc_data[6]) st<=S_AFE_NEXT;
                else if(!spi_block && !t_busy && !t_start) begin
                    afe_value<=cdc_data[5:0]; t_start<=1; t_wr<=0; t_chip<=chip;
                    t_addr<=REG_CDC0+{6'd0,ch_idx}; st<=S_AFE_CHECK;
                end
            end
            S_AFE_CHECK: if(t_done) begin
                if(afe_in_band || afe_pass==6) begin
                    if((t_rdata<=800 || t_rdata>=64700) && err_cnt<15) err_cnt<=err_cnt+1'b1;
                    st<=S_AFE_NEXT;
                end else begin
                    afe_value<=t_rdata>32700 ? (afe_plus>63 ? 6'd63 : afe_plus[5:0]) :
                               (afe_value<afe_step ? 6'd0 : afe_value-afe_step);
                    pass_changed<=1; st<=S_AFE_WRITE;
                end
            end
            S_AFE_WRITE: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=1; t_chip<=chip;
                t_addr<=REG_B2+{ch_idx,3'd0}+10'd2;
                t_wdata<={2'd0,afe_value,8'd0}; st<=S_AFE_WEND;
            end
            S_AFE_WEND: if(t_done) begin
                if(AUX_H8 && chip==CHIP_LAST && ch_idx==0 && !t_err) h0_afe<=afe_value;
                st<=S_AFE_NEXT;
            end
            S_AFE_NEXT: begin
                if(ch_idx<STG_LAST) begin ch_idx<=ch_idx+1'b1; st<=S_AFE_FETCH; end
                else if(chip<CHIP_LAST) begin chip<=chip+1'b1; ch_idx<=0; st<=S_AFE_FETCH; end
                else begin
                    chip<=0; ch_idx<=0;
                    if(!pass_changed || afe_pass==6) begin cal_phase<=0; st<=S_CAL; end
                    else begin
                        afe_pass<=afe_pass+1'b1; pass_changed<=0;
                        wait_cnt<=0; wait_limit<=CONV_WAIT*2; after_wait<=S_AFE_FETCH; st<=S_WAIT;
                    end
                end
            end
            S_CAL: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1; t_wr<=1; t_chip<=chip;
                t_addr<=cal_phase==0 ? REG_CAL : REG_AMB0;
                t_wdata<=cal_phase==0 ? STG_CAL_V : (cal_phase==1 ? VAL_AMB0_FORCE : AMB0_V);
                st<=S_CAL_END;
            end
            S_CAL_END: if(t_done) begin
                if(chip<CHIP_LAST) begin chip<=chip+1'b1; st<=S_CAL; end
                else begin
                    chip<=0;
                    if(cal_phase==0) begin cal_phase<=1; st<=S_CAL; end
                    else if(cal_phase==1) begin
                        cal_phase<=2; wait_cnt<=0; wait_limit<=FRC_WAIT; after_wait<=S_CAL; st<=S_WAIT;
                    end else begin
                        panel_ready<=sensor_ok;rate_applied_fast<=rate_target;rate_fault<=0; ch_idx<=0;
                        wait_cnt<=0; wait_limit<=CONV_WAIT; after_wait<=S_SCAN; st<=S_WAIT;
                    end
                end
            end
            S_SCAN: if(sleep_req)begin chip<=0;st<=S_SLEEP_WRITE;end
                    else if(chip==0 && ch_idx==0 && rate_fast!=rate_applied_fast && !rate_switch)begin
                        rate_target<=rate_fast;rate_switch<=1;rate_fault<=0;chip<=0;cfg_ok_acc<=1;
                        st<=S_RATE_WRITE;
                    end
                    else if(compact_ch>=N_CH) st<=S_SCAN_NEXT;
                    else if(!spi_block && !t_busy && !t_start) begin
                        t_start<=1; t_wr<=0; t_chip<=chip;
                        t_addr<=REG_CDC0+{6'd0,ch_idx}; st<=S_SCAN_END;
                    end
            S_SCAN_END: if(t_done) st<=S_SCAN_NEXT;
            S_SCAN_NEXT: begin
                if(ch_idx<STG_LAST) begin ch_idx<=ch_idx+1'b1; st<=S_SCAN; end
                else if(chip<CHIP_LAST) begin chip<=chip+1'b1; ch_idx<=0; st<=S_SCAN; end
                else begin
                    chip<=0;ch_idx<=0;aux_turn<=~aux_turn;
                    if(AUX_H8 && aux_turn && !sleep_req && !rate_switch) begin
                        aux_step<=0;st<=S_AUX_IO;
                    end else begin
                        sample_valid<=1;rate_switch<=0;
                        wait_cnt<=0;wait_limit<=CONV_WAIT;after_wait<=S_SCAN;st<=S_WAIT;
                    end
                end
            end
            // H has twelve fret conversions. Use one additional one-stage
            // conversion every other round, then restore the complete map.
            S_AUX_IO: if(!spi_block && !t_busy && !t_start) begin
                t_start<=1;t_wr<=1;t_chip<=CHIP_LAST;st<=S_AUX_DONE;
                case(aux_step)
                    0,7: begin t_addr<=REG_PWR;t_wdata<=PWR_CONTROL_V|16'h0001;end
                    1: begin t_addr<=REG_CAL;t_wdata<=0;end
                    2: begin t_addr<=REG_B2;t_wdata<=16'h3FFF;end
                    3: begin t_addr<=REG_B2+1;t_wdata<=16'h1FFB;end
                    4: begin t_addr<=REG_B2+2;t_wdata<={2'd0,h8_afe,8'd0};end
                    5: begin t_addr<=REG_PWR;t_wdata<=PWR_CONTROL_V&16'hFF0F;end
                    6: begin t_addr<=REG_CDC0;t_wr<=0;end
                    8: begin t_addr<=REG_B2;t_wdata<=stage_conn_a(CHIP_LAST,4'd0);end
                    9: begin t_addr<=REG_B2+1;t_wdata<=stage_conn_b(CHIP_LAST,4'd0);end
                    10:begin t_addr<=REG_B2+2;t_wdata<={2'd0,h0_afe,8'd0};end
                    11:begin t_addr<=REG_CAL;t_wdata<=STG_CAL_V;end
                    default:begin t_addr<=REG_PWR;t_wdata<=PWR_CONTROL_V;end
                endcase
            end
            S_AUX_DONE: if(t_done) begin
                if(t_err) begin
                    panel_ready<=0;sensor_ok<=0;wait_cnt<=0;wait_limit<=INIT_RETRY_WAIT;st<=S_FAULT;
                end else if(aux_step==12) begin
                    sample_valid<=1;
                    wait_cnt<=0;wait_limit<=CONV_WAIT;after_wait<=S_SCAN;st<=S_WAIT;
                end else begin
                    aux_step<=aux_step+1'b1;
                    if(aux_step==6 && !h8_ready) begin
                        if(afe_in_band || h8_pass==6) h8_ready<=1;
                        else begin
                            h8_pass<=h8_pass+1'b1;
                            h8_afe<=t_rdata>32700 ? (h8_plus>63 ? 6'd63 : h8_plus[5:0]) :
                                    (h8_afe<h8_step ? 6'd0 : h8_afe-h8_step);
                        end
                    end
                    if(aux_step==5) begin
                        wait_cnt<=0;wait_limit<=(CLK_HZ/1000)*(rate_target?2:4);
                        after_wait<=S_AUX_IO;st<=S_WAIT;
                    end else st<=S_AUX_IO;
                end
            end
            S_RATE_WRITE: if(!spi_block && !t_busy && !t_start)begin
                t_start<=1;t_wr<=1;t_chip<=chip;t_addr<=REG_PWR;t_wdata<=PWR_CONTROL_V;st<=S_RATE_END;
            end
            S_RATE_END: if(t_done)begin
                cfg_ok_acc<=cfg_ok_acc && !t_err;
                if(chip<CHIP_LAST)begin chip<=chip+1'b1;st<=S_RATE_WRITE;end
                else begin chip<=0;st<=S_RATE_READ;end
            end
            S_RATE_READ: if(!spi_block && !t_busy && !t_start)begin
                t_start<=1;t_wr<=0;t_chip<=chip;t_addr<=REG_PWR;st<=S_RATE_REND;
            end
            S_RATE_REND: if(t_done)begin
                cfg_ok_acc<=cfg_ok_acc && !t_err && t_rdata==PWR_CONTROL_V;
                if(chip<CHIP_LAST)begin chip<=chip+1'b1;st<=S_RATE_READ;end
                else begin
                    chip<=0;ch_idx<=0;wait_cnt<=0;
                    if(cfg_ok_acc && !t_err && t_rdata==PWR_CONTROL_V)begin
                        rate_applied_fast<=rate_target;
                        // Discard conversion/filter transients; first full fresh scan
                        // ends rate_busy. No partial old/new-rate round is published.
                        wait_limit<=CONV_WAIT*2;after_wait<=S_SCAN;st<=S_WAIT;
                    end else begin
                        rate_fault<=1;panel_ready<=0;sensor_ok<=0;rate_switch<=0;
                        wait_limit<=INIT_RETRY_WAIT;st<=S_FAULT;
                    end
                end
            end
            S_FAULT: begin
                if(sleep_req)begin chip<=0;st<=S_SLEEP_WRITE;end else begin
                // Keep the failed round visible while backing off. Never emit
                // samples or enable recognition while a chip/config is missing.
                panel_ready<=0;
                if(wait_cnt>=wait_limit) begin wait_cnt<=0; st<=S_RESTART; end
                else wait_cnt<=wait_cnt+1'b1;
            end
                end
            S_SLEEP_WRITE: if(!spi_block && !t_busy && !t_start)begin
                t_start<=1;t_wr<=1;t_chip<=chip;t_addr<=REG_PWR;
                t_wdata<=PWR_CONTROL_V|16'h0001;st<=S_SLEEP_END;
            end
            S_SLEEP_END: if(t_done)begin
                if(chip<CHIP_LAST)begin chip<=chip+1'b1;st<=S_SLEEP_WRITE;end
                else begin chip<=0;st<=S_ASLEEP;end
            end
            S_ASLEEP: if(!sleep_req)begin chip<=0;st<=S_WAKE_WRITE;end
            S_WAKE_WRITE: if(!spi_block && !t_busy && !t_start)begin
                t_start<=1;t_wr<=1;t_chip<=chip;t_addr<=REG_PWR;
                t_wdata<=PWR_CONTROL_V;st<=S_WAKE_END;
            end
            S_WAKE_END: if(t_done)begin
                if(chip<CHIP_LAST)begin chip<=chip+1'b1;st<=S_WAKE_WRITE;end
                else begin
                    chip<=0;ch_idx<=0;wait_cnt<=0;wait_limit<=CONV_WAIT;
                    wake_verify<=panel_ready;
                    id_ok_acc<=1;cfg_ok_acc<=1;cfg_fail_mask<=0;id_try<=0;
                    after_wait<=panel_ready ? S_ID : S_RESTART;st<=S_WAIT;
                end
            end
            S_RESTART: begin
                wake_verify<=0;aux_turn<=0;aux_step<=0;h8_afe<=32;h0_afe<=32;h8_pass<=0;h8_ready<=0;
                if(init_retries!=8'hFF) init_retries<=init_retries+1'b1;
                panel_ready<=0; sensor_ok<=0; id_ok<=0;
                chip<=0; ch_idx<=0; ini_idx<=1; mem_init_addr<=0;
                afe_pass<=0; afe_value<=32; cal_phase<=0; pass_changed<=0;
                id_ok_acc<=1; cfg_ok_acc<=1; cfg_fail_mask<=0; id_try<=0;
                wait_cnt<=0; tmo_cnt<=0;
                // Clear converged/POS scratch RAM on EVERY full reset; old AFE
                // values must not skip calibration after registers are reset.
                st<=S_MEM_INIT;
            end
            default: st<=S_MEM_INIT;
            endcase
        end
    end
endmodule
