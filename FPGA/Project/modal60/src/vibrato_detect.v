// gv_host v63/v64 gesture detector, pipelined for the 50 MHz RAM-based panel.
// One instance per string. update is one pulse per complete recognition round.
// Sum all fret deltas to reject slides; require motion on the held pad itself
// to reject adjacent electrode coupling. Thresholds are in CDC counts/rounds.
module vibrato_detect #(
    parameter W = 21,
    parameter SHARED_CONTEXT = 0,
    parameter RUNTIME_CONFIG = 0,
    parameter AMP_ABS = 120,
    parameter REL_SH = 5,
    parameter AGE_MAX = 10, // about 230 ms at the release panel's 23 ms/round
    parameter MIN_GAP = 2,
    parameter DC_SH = 3,
    parameter AMP_SH = 2
) (
    input wire clk, rst_n, update, held, reseed,
    input wire [W-1:0] delta_sum,
    input wire [15:0] delta_pad,
    input wire [15:0] trigger_threshold,
    input wire fast_scan,
    input wire context_load,input wire [2*W+59:0] context_in,
    output wire [2*W+59:0] context_out,
    output reg active, gate,
    output wire [15:0] amplitude
);
    reg [W-1:0] dc, amp;
    // The pad input is 16 bits. Its EMA and absolute deviation are bounded
    // by the same unsigned range; retaining sum-width bits only adds logic.
    reg [15:0] pdc, pamp;
    reg [4:0] age;
    reg [15:0] threshold_seen;
    reg fast_seen;
    reg [2:0] crossings;
    reg level;
    assign context_out={dc,amp,pdc,pamp,age,threshold_seen,fast_seen,crossings,level,active,gate};
    assign amplitude = (|amp[W-1:16]) ? 16'hffff : amp[15:0];

    reg [3:0] phase;
    reg [W-1:0] sum_r, abs_r;
    reg [15:0] pad_r, pabs_r;
    reg signed [W:0] diff_r, pdiff_r;
    reg seed_r, crossing_r;
    reg [W-1:0] dc_n, amp_n;
    reg [15:0] pdc_n, pamp_n;
    reg [4:0] age_n;
    reg [2:0] count_n;
    wire [15:0] abs_threshold = RUNTIME_CONFIG ? trigger_threshold : AMP_ABS;
    // Threshold is both the envelope floor and the Schmitt crossing floor.
    // Small noise never builds a hidden sequence of valid crossings.
    wire [W-1:0] floor_ext={{(W-16){1'b0}},abs_threshold};
    wire signed [W:0] hyst = $signed({1'b0,(RUNTIME_CONFIG && floor_ext>(amp_n>>1) ? floor_ext : (amp_n>>1))});
    wire [4:0] gap_limit=RUNTIME_CONFIG ? (fast_scan ? 5'd4 : 5'd2) : MIN_GAP;
    wire [4:0] age_limit=RUNTIME_CONFIG ? (fast_scan ? 5'd19 : 5'd10) : AGE_MAX;
    wire config_changed=RUNTIME_CONFIG && (threshold_seen!=trigger_threshold || fast_seen!=fast_scan);
    wire amp_ok = (amp_n >= abs_threshold) && (RUNTIME_CONFIG || (amp_n >= (dc_n >> REL_SH))) &&
                   ({1'b0,pamp_n} << 1) >= {1'b0,amp_n};
    localparam [W:0] ROUND_DC = (1 << DC_SH)-1;
    localparam [W:0] ROUND_AMP = (1 << AMP_SH)-1;
    wire [W:0] abs_full = diff_r[W] ? -diff_r : diff_r;
    wire [W:0] pabs_full = pdiff_r[W] ? -pdiff_r : pdiff_r;
    wire [W:0] dc_step_full = ({1'b0,abs_r}+ROUND_DC)>>DC_SH;
    wire [W:0] pdc_step_full = ({1'b0,pabs_r}+ROUND_DC)>>DC_SH;
    wire [W-1:0] amp_difference = (abs_r>=amp) ? abs_r-amp : amp-abs_r;
    wire [W-1:0] pamp_difference = (pabs_r>=pamp) ? pabs_r-pamp : pamp-pabs_r;
    wire [W:0] amp_step_full = ({1'b0,amp_difference}+ROUND_AMP)>>AMP_SH;
    wire [W:0] pamp_step_full = ({1'b0,pamp_difference}+ROUND_AMP)>>AMP_SH;

    // A separate stage for each difference, EMA, crossing, and qualification
    // keeps the v64 arithmetic from becoming a single long combinational path.
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            dc<=0; amp<=0; pdc<=0; pamp<=0;
            age<=31; crossings<=0; level<=0; active<=0; gate<=0;
            threshold_seen<=0;fast_seen<=0;
            phase<=0; sum_r<=0; pad_r<=0; abs_r<=0; pabs_r<=0;
            diff_r<=0; pdiff_r<=0; seed_r<=0; crossing_r<=0;
            dc_n<=0; amp_n<=0; pdc_n<=0; pamp_n<=0;
            age_n<=31; count_n<=0;
        end else if(SHARED_CONTEXT && context_load)begin
            {dc,amp,pdc,pamp,age,threshold_seen,fast_seen,crossings,level,active,gate}<=context_in;
            phase<=0;
        end else case (phase)
            0: if (update) begin
                if (!held) begin
                    dc<=0; amp<=0; pdc<=0; pamp<=0;
                    age<=31; crossings<=0; level<=0; active<=0; gate<=0;
                end else begin
                    sum_r<=delta_sum; pad_r<=delta_pad;
                    diff_r<=$signed({1'b0,delta_sum})-$signed({1'b0,dc});
                    pdiff_r<=$signed({1'b0,{{(W-16){1'b0}},delta_pad}})-$signed({1'b0,pdc});
                    seed_r<=reseed || (dc==0) || config_changed;
                    threshold_seen<=trigger_threshold;fast_seen<=fast_scan;
                    phase<=1;
                end
            end
            1: begin
                abs_r<=abs_full[W-1:0];
                pabs_r<=pabs_full[W-1:0];
                phase<=2;
            end
            2: begin
                if (seed_r || (abs_r > (dc >> 1))) begin
                    dc<=sum_r; pdc<=pad_r; amp<=0; pamp<=0;
                    age<=31; crossings<=0; level<=0; active<=0; gate<=0;
                    phase<=0;
                end else begin
                    if (diff_r[W]) dc_n<=dc-dc_step_full[W-1:0];
                    else dc_n<=dc+dc_step_full[W-1:0];
                    if (pdiff_r[W]) pdc_n<=pdc-pdc_step_full[W-1:0];
                    else pdc_n<=pdc+pdc_step_full[W-1:0];
                    phase<=3;
                end
            end
            3: begin
                if (abs_r>=amp) amp_n<=amp+amp_step_full[W-1:0];
                else amp_n<=amp-amp_step_full[W-1:0];
                if (pabs_r>=pamp) pamp_n<=pamp+pamp_step_full[W-1:0];
                else pamp_n<=pamp-pamp_step_full[W-1:0];
                phase<=4;
            end
            4: begin
                crossing_r<=(level==diff_r[W]) && ({1'b0,abs_r}>hyst);
                phase<=5;
            end
            5: begin
                if (crossing_r && age>=gap_limit && (!RUNTIME_CONFIG || amp_ok)) begin
                    age_n<=0;
                    count_n<=(crossings==7) ? 3'd7 : crossings+3'd1;
                end else begin
                    age_n<=(age==31) ? 5'd31 : age+5'd1;
                    count_n<=((age==31) || (age+1>age_limit)) ? 3'd0 : crossings;
                end
                if (crossing_r && age>=gap_limit && (!RUNTIME_CONFIG || amp_ok)) level<=~level;
                phase<=6;
            end
            6: begin
                dc<=dc_n; amp<=amp_n; pdc<=pdc_n; pamp<=pamp_n;
                age<=age_n; crossings<=count_n;
                active<=amp_ok && age_n<=age_limit && count_n>=(RUNTIME_CONFIG ? 4 : 2);
                gate<=amp_ok && age_n<=age_limit && count_n>=(RUNTIME_CONFIG ? 4 : 3);
                phase<=0;
            end
            default: phase<=0;
        endcase
    end
endmodule
