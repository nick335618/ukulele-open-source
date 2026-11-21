// Configuration-retaining standby. No generated/gated FPGA clock.
// Module transactions drain before standby; I2S clock stopping is separately
// acknowledged. Existing audio/ADC rates and timbre arithmetic are unchanged.
module power_control #(
    parameter integer CLK_HZ=50000000,
    parameter integer SETTLE_MS=500,
    parameter integer TIMEOUT_MS=3000
)(
    input wire clk,rst_n,cmd_valid,
    input wire [7:0] cmd_op,
    input wire [15:0] cmd_id,cmd_epoch,
    input wire [7:0] volume,
    input wire modules_idle,sensors_ready,clocks_stopped,
    output wire events_enable,compute_enable,modules_sleep,clocks_enable,
    output reg audio_clear,wake_start,flush_events,
    output wire [7:0] effective_volume,
    output wire [7:0] actual_state,
    output reg [7:0] result,
    output reg [15:0] reply_id,reply_epoch,
    output wire [15:0] elapsed_ms
);
    localparam DIV=(CLK_HZ<1000)?1:CLK_HZ/1000;
    localparam DW=(DIV<2)?1:$clog2(DIV);
    localparam RUN=0,DOWN=1,STOP=2,SLEEP=3,WAKE=4,UP=5,FAULT=6;
    reg [2:0] state;
    reg [DW-1:0] ms_count;
    reg [11:0] age;
    reg [7:0] fade_gain;
    wire ms_tick=ms_count==DIV-1;
    wire [7:0] step_gain=(volume>>5)+8'd1;
    assign actual_state=(state==RUN)?8'd0:(state==SLEEP)?8'd4:
                        (state==DOWN || state==STOP)?8'd3:
                        (state==FAULT)?8'd6:8'd5;
    assign events_enable=state==RUN;
    assign compute_enable=state==RUN || state==DOWN || state==WAKE || state==UP;
    assign modules_sleep=state==STOP || state==SLEEP;
    assign clocks_enable=state!=SLEEP && state!=STOP;
    assign effective_volume=state==RUN ? volume:
                            (state==DOWN || state==UP)?fade_gain:8'd0;
    assign elapsed_ms={4'd0,age};
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            state<=RUN;result<=0;reply_id<=0;reply_epoch<=0;
            ms_count<=0;age<=0;fade_gain<=0;
            audio_clear<=0;wake_start<=0;flush_events<=0;
        end else begin
            audio_clear<=0;wake_start<=0;flush_events<=0;
            if(ms_tick) begin
                ms_count<=0;
                if(state!=RUN && state!=SLEEP && age!=4095)age<=age+1'b1;
                case(state)
                    DOWN: if(fade_gain<=step_gain)begin fade_gain<=0;state<=STOP;end
                          else fade_gain<=fade_gain-step_gain;
                    WAKE: if(age>=SETTLE_MS && sensors_ready)begin state<=UP;fade_gain<=0;end
                    UP: if(fade_gain>=volume || volume-fade_gain<=step_gain)begin
                            state<=RUN;result<=2;fade_gain<=volume;end
                        else fade_gain<=fade_gain+step_gain;
                    default: ;
                endcase
            end else ms_count<=ms_count+1'b1;
            if(state==STOP && modules_idle && clocks_stopped) begin
                state<=SLEEP;result<=2;audio_clear<=1;flush_events<=1;
            end
            if((state==STOP || state==WAKE) && age>=TIMEOUT_MS)begin
                state<=FAULT;result<=4;flush_events<=1;
            end
            if(cmd_valid)begin
                reply_id<=cmd_id;reply_epoch<=cmd_epoch;
                case(cmd_op)
                    0: ; // Query never restarts an in-flight transition.
                    1: if(state==SLEEP)result<=2;
                       else if(state!=DOWN && state!=STOP)begin
                           state<=DOWN;age<=0;fade_gain<=effective_volume;
                           result<=1;flush_events<=1;
                       end
                    2,3: if(state==RUN)result<=2;
                         else if(state!=WAKE && state!=UP)begin
                             state<=WAKE;age<=0;fade_gain<=0;result<=1;
                             audio_clear<=1;wake_start<=1;flush_events<=1;
                         end
                    default:result<=3;
                endcase
            end
        end
    end
endmodule
