// Protocol v3: dry / tremolo / chorus / flanger / envelope-tracked electric distortion.
// One serial ALU, original 1024x16 RAM and full-rate audio throughout.
// Modulation structures informed by DaisySP (MIT): dual chorus paths and
// bounded flanger feedback; this is a fixed-point serial RTL implementation.
// References and license snapshot: Docs/开源音效参考.md.
module audio_fx(
 input wire clk,rst_n,enable,sample_tick,
 input wire signed [15:0] sample_in,
 input wire [2:0] cfg_mode,
 input wire [6:0] cfg_strength,
 output reg signed [16:0] wet_out,
 output wire signed [15:0] dry_out,
 output wire [2:0] actual_mode,
 output wire [6:0] wet_gain,
 output wire ready,settled,
 output reg overrun,
 input wire chain_enable,input wire [3:0] chain_mask,input wire [27:0] chain_strengths,
 output wire chain_active,output wire [3:0] chain_actual_mask,output wire chain_settled,
 input wire [2:0] chain_route,output wire [2:0] chain_actual_route
);
 reg [2:0] kernel;reg [6:0] stage_gain;
 reg running_chain,final_gain;
 reg [3:0] active_mask;
 reg [27:0] active_strengths;
 reg [2:0] active_route;
 reg [1:0] chain_stage;
 reg [6:0] chain_gain;
 reg [15:0] chorus_phase,flanger_phase,trem_phase,hp_context;
 reg signed [15:0] filter_context,chain_original,chain_sample;
 reg [9:0] chorus_wp;
 reg [7:0] flanger_wp;
 reg chorus_filled,flanger_filled;
 wire chain_requested=(chain_enable===1'b1);
 wire chain_same=chain_requested&&active_mask==chain_mask&&active_strengths==chain_strengths&&active_route==chain_route;
 assign chain_actual_route=active_route;
 assign actual_mode=running_chain?3'd0:kernel;
 assign wet_gain=running_chain?7'd0:stage_gain;
 assign chain_active=running_chain;
 assign chain_actual_mask=running_chain?active_mask:4'd0;
 assign chain_settled=running_chain&&chain_same&&chain_gain==127&&chorus_filled&&flanger_filled;
 localparam CHAIN_LOAD=30,CHAIN_BEGIN=31,CHAIN_SUM=32,CHAIN_FINAL=33;
 localparam IDLE=0,WAIT_A=1,GET_A=2,WAIT_B=3,DIFF=4,INTERP=5,
            ADD=6,WRITE=7,GAIN_INIT=8,GAIN=9,FINISH=10,
            GET_B=11,ADD_PREP=12,WRITE_PREP=13,ADDRESS=14,
            DRIVE_ABS=15,DRIVE_ENV=16,DRIVE_SIGN=17,
            DRIVE_FILTER_DIFF=18,DRIVE_ROUND=19,DRIVE_FILTER_STEP=20,CHORUS_SUM=21,GAIN_DIFF=22,
            DRIVE_HP_DIFF=23,DRIVE_HP_ROUND=24,DRIVE_HP_STEP=25,PHASE_ADV=26,CAB_DIFF=27,CAB_ROUND=28,CAB_STEP=29;
 (* syn_ramstyle="block_ram" *) reg signed [15:0] ram[0:1023];
 reg signed [15:0] ram_q,base,dry;
 reg [15:0] drive_env;
 reg signed [15:0] drive_filter, cabinet;
 reg [9:0] rp;
 wire [9:0] wp=running_chain ? (kernel==2 ? chorus_wp : {2'b11,flanger_wp}) : phase[9:0];
 reg filled,chorus_second;
 reg [5:0] state;
 reg [3:0] fade_div;
 reg [15:0] phase;
 wire [14:0] triangle=phase[15] ? ~phase[14:0] : phase[14:0];
 reg [6:0] fraction,bits;
 reg [2:0] count;
 reg signed [16:0] operand;
 reg signed [17:0] accum;
 wire modulated=(kernel==2 || kernel==3);
 wire multiply=(state==INTERP || state==GAIN);
 wire subtract=(state==DIFF || state==ADDRESS ||
               (state==DRIVE_ABS && operand[16]) || (state==DRIVE_SIGN && chorus_second) ||
               state==DRIVE_ENV || state==DRIVE_FILTER_DIFF || state==GAIN_DIFF || state==DRIVE_HP_DIFF || state==CAB_DIFF);
 wire signed [17:0] alu_b=(multiply && !bits[0]) ? 18'sd0 : {operand[16],operand};
 wire signed [17:0] alu_sum=accum+(alu_b^{18{subtract}})+subtract;
 wire [15:0] ram_input_sat=sat16(alu_sum);
 wire [6:0] configured_gain=cfg_strength;
 // |input| and the released peak are both 0..32768; no multiplier.
 wire [15:0] env_released=alu_sum[17] ? 16'd0 : alu_sum[15:0];
 wire [15:0] env_next=($unsigned(base)>env_released) ? base : env_released;
 // Continuous, odd-symmetric three-slope soft knee. The transfer has
 // slopes 4, 1, 1/4, retains the original peak scale and avoids the
 // previous 32x near-square-wave limiter. All operations are unsigned.
 wire [15:0] knee_a=env_next>>3, knee_b=env_next>>1;
 wire [15:0] drive_mag=base==0 ? 16'd0 : ($unsigned(base)<knee_a) ? (base<<2) :
     ($unsigned(base)<knee_b) ? knee_b+base-knee_a :
     env_next-knee_a+(($unsigned(base)-knee_b)>>2);
 assign ready=running_chain ? (chorus_filled&&flanger_filled) : (!modulated || filled);
 wire [6:0] target_gain=(!chain_requested && ready && kernel==cfg_mode && cfg_mode!=0) ? configured_gain : 7'd0;
 assign settled=running_chain ? chain_settled : (kernel==cfg_mode && stage_gain==target_gain && ready);
 assign dry_out=running_chain?chain_original:dry;
 function [15:0] sat16(input signed [17:0] x);
  begin sat16=(x[17:15]==3'b000 || x[17:15]==3'b111) ? x[15:0] : x[17] ? 16'h8000 : 16'h7fff;end
 endfunction
 wire [9:0] chorus_delay_b={2'b10,~triangle[14:7]};
 wire [9:0] first_delay=kernel==3 ? {3'd0,1'b1,triangle[14:9]} : (10'd384+{2'd0,triangle[14:7]});
 always @(posedge clk)begin
  ram_q<=ram[rp];
  if(enable && state==WRITE)ram[wp]<=operand[15:0];
 end
 always @(posedge clk or negedge rst_n)begin
  if(!rst_n)begin
   running_chain<=0;final_gain<=0;active_mask<=0;active_strengths<=0;active_route<=0;chain_stage<=0;chain_gain<=0;
   chorus_phase<=0;flanger_phase<=0;trem_phase<=0;hp_context<=0;filter_context<=0;chain_original<=0;chain_sample<=0;
   chorus_wp<=0;flanger_wp<=0;chorus_filled<=0;flanger_filled<=0;
   rp<=0;filled<=0;chorus_second<=0;state<=0;phase<=0;fraction<=0;bits<=0;count<=0;
   operand<=0;accum<=0;base<=0;dry<=0;fade_div<=0;stage_gain<=0;
   kernel<=0;wet_out<=0;overrun<=0;drive_env<=0;drive_filter<=0;cabinet<=0;
  end else if(!enable)begin
   running_chain<=0;final_gain<=0;chain_gain<=0;chorus_filled<=0;flanger_filled<=0;
   chorus_wp<=0;flanger_wp<=0;chorus_phase<=0;flanger_phase<=0;trem_phase<=0;hp_context<=0;filter_context<=0;
   filled<=0;chorus_second<=0;state<=IDLE;stage_gain<=0;wet_out<=0;fade_div<=0;
   kernel<=cfg_mode;phase<=0;accum<=0;base<=0;dry<=0;drive_env<=0;drive_filter<=0;cabinet<=0;
  end else begin
   if(sample_tick)begin
    if(state!=IDLE)overrun<=1;
    fade_div<=fade_div+1'b1;
    if(fade_div==0 && running_chain)begin
     if(!chain_same && chain_gain!=0)chain_gain<=chain_gain-1'b1;
     else if(chain_same && chorus_filled&&flanger_filled&&chain_gain!=127)chain_gain<=chain_gain+1'b1;
    end
    if(fade_div==0 && !running_chain && stage_gain!=target_gain)
     stage_gain<=stage_gain+(stage_gain<target_gain ? 7'd1 : 7'h7f);
   end
   case(state)
    IDLE:if(sample_tick)begin
     dry<=sample_in;
     final_gain<=0;
     if(running_chain)begin
      chain_original<=sample_in;chain_sample<=sample_in;chain_stage<=0;
      if(!chain_same&&chain_gain==0)begin
       running_chain<=chain_requested;active_mask<=chain_mask;active_strengths<=chain_strengths;active_route<=chain_route;
       chorus_filled<=0;flanger_filled<=0;chorus_wp<=0;flanger_wp<=0;
       chorus_phase<=0;flanger_phase<=0;trem_phase<=0;hp_context<=0;filter_context<=0;drive_env<=0;cabinet<=0;
       wet_out<=0;kernel<=0;stage_gain<=0;phase<=0;filled<=0;
      end else state<=CHAIN_LOAD;
     end else if(chain_requested&&stage_gain==0)begin
       running_chain<=1;active_mask<=chain_mask;active_strengths<=chain_strengths;active_route<=chain_route;chain_gain<=0;
       chorus_filled<=0;flanger_filled<=0;chorus_wp<=0;flanger_wp<=0;
       chorus_phase<=0;flanger_phase<=0;trem_phase<=0;hp_context<=0;filter_context<=0;drive_env<=0;cabinet<=0;wet_out<=0;
     end else if(kernel!=cfg_mode && stage_gain==0)begin
      kernel<=cfg_mode;filled<=0;phase<=0;wet_out<=0;accum<=0;base<=0;drive_env<=0;drive_filter<=0;cabinet<=0;
     end else if(kernel==1)begin
      // 7*Fs/65536 = 5.2154 Hz. Smooth triangle amplitude, no pitch change.
      accum<=0;operand<={sample_in[15],sample_in};
      bits<=triangle[14:8];count<=6;state<=INTERP;
     end else if(kernel==4)begin
      accum<={{2{sample_in[15]}},sample_in};operand<={phase[15],phase};state<=DRIVE_HP_DIFF;
     end else if(modulated)begin
      chorus_second<=0;
      accum<={8'd0,wp};operand<={7'd0,first_delay};
      fraction<=kernel==3 ? triangle[8:2] : triangle[6:0];state<=ADDRESS;
     end else wet_out<=0;
    end
    ADDRESS:begin
     rp<=running_chain ? (kernel==2 ? (alu_sum[17] ? alu_sum+18'd768 : alu_sum) : {2'b11,alu_sum[7:0]}) : alu_sum[9:0];state<=WAIT_A;
    end
    WAIT_A:state<=GET_A;
    GET_A:begin base<=ram_q;state<=WAIT_B;end
    WAIT_B:begin
     rp<=running_chain ? (kernel==2 ? (rp==0 ? 10'd767:rp-1'b1) : {2'b11,(rp[7:0]-8'd1)}) : rp-1'b1;state<=GET_B;
    end
    GET_B:state<=ADD_PREP;
    ADD_PREP:begin accum<={{2{ram_q[15]}},ram_q};operand<={base[15],base};state<=DIFF;end
    DIFF:begin operand<=alu_sum[16:0];accum<=0;bits<=modulated ? fraction : stage_gain;count<=6;state<=modulated ? INTERP : GAIN;end
    INTERP:begin accum<=$signed(alu_sum)>>>1;bits<=bits>>1;
     if(count==0)begin
      if(modulated)begin operand<=($signed(alu_sum)>>>1);accum<={{2{base[15]}},base};state<=ADD;end
      else begin accum<=$signed(alu_sum)>>>1;operand<={dry[15],dry};state<=DIFF;end
     end else count<=count-1'b1;
    end
    ADD:begin
     base<=alu_sum[15:0];
     if(kernel==2 && !chorus_second)begin
      // Reuse the distortion filter register as the first chorus tap.
      drive_filter<=alu_sum[15:0];chorus_second<=1;
      accum<={8'd0,wp};operand<={7'd0,chorus_delay_b};fraction<=~triangle[6:0];state<=ADDRESS;
     end else if(kernel==2)begin
      accum<=alu_sum;operand<={drive_filter[15],drive_filter};state<=CHORUS_SUM;
     end else begin
      // 1/2 feedback, saturation and a 3-LSB deadband: no unstable loop or
      // persistent quantizer residue when returning to silence.
      accum<={{2{dry[15]}},dry};
      operand<=(!filled || (alu_sum>=-18'sd3 && alu_sum<=18'sd3)) ? 17'd0 : ($signed(alu_sum)>>>1);
      state<=WRITE_PREP;
     end
    end
    CHORUS_SUM:begin base<=$signed(alu_sum)>>>1;state<=WRITE_PREP;end
    WRITE_PREP:begin operand<=kernel==3 ? {ram_input_sat[15],ram_input_sat} : {dry[15],dry};state<=WRITE;end
    WRITE:begin
     if(!running_chain)begin if(wp==1023)filled<=1;end
     else if(kernel==2)begin if(chorus_wp==767)begin chorus_filled<=1;filled<=1;end end
     else if(flanger_wp==255)begin flanger_filled<=1;filled<=1;end
     state<=GAIN_INIT;
    end
    // A true wet/dry crossfade makes high settings genuinely wet rather
    // than keeping the dry sound dominant and only raising its volume.
    GAIN_INIT:begin accum<={{2{base[15]}},base};operand<={dry[15],dry};state<=GAIN_DIFF;end
    GAIN_DIFF:begin operand<=alu_sum[16:0];accum<=0;bits<=final_gain?chain_gain:stage_gain;count<=6;state<=GAIN;end
    GAIN:begin accum<=$signed(alu_sum)>>>1;bits<=bits>>1;if(count==0)state<=FINISH;else count<=count-1'b1;end
    FINISH:begin
     if(running_chain)begin
      if(final_gain)begin wet_out<=accum[16:0];state<=IDLE;final_gain<=0;end
      else begin
       // Store only the current kernel's context. No duplicate effect engines.
       case(kernel)
        1:trem_phase<=phase+16'd7;
        2:begin chorus_phase<=phase+16'd1;chorus_wp<=chorus_wp==767?10'd0:chorus_wp+1'b1;end
        3:begin flanger_phase<=phase+16'd1;flanger_wp<=flanger_wp+1'b1;end
        4:begin hp_context<=phase;filter_context<=drive_filter;end
       endcase
       operand<=(modulated&&!filled)?17'd0:accum[16:0];accum<={{2{dry[15]}},dry};state<=CHAIN_SUM;
      end
     end else begin
     wet_out<=(modulated && !filled) ? 17'd0 : accum[16:0];
     if(kernel==1 || modulated)begin
      accum<={2'd0,phase};operand<=kernel==1 ? 17'd7 : 17'd1;state<=PHASE_ADV;
     end else state<=IDLE;
     end
    end
    // Use the same ALU for modulation phase and write-pointer advancement;
    // no parallel phase+1 / phase+7 adders or additional phase data mux.
    PHASE_ADV:begin phase<=alu_sum[15:0];state<=IDLE;end
    // Guitar voicing: ~61 Hz DC/bass removal, smooth 4x soft knee; retain the original
    // peak scale with ~21ms envelope release, then two cabinet low-pass poles.
    // phase is a signed input low-pass state in mode4; chorus_second is the
    // high-passed sample sign. Neither is used by modulation in this mode.
    // Three serial states reuse the ALU, with no additional RAM/DSP/state bank.
    DRIVE_HP_DIFF:begin
     base<=ram_input_sat;accum<=alu_sum;operand<=alu_sum[17] ? 17'd0 : 17'd127;state<=DRIVE_HP_ROUND;
    end
    DRIVE_HP_ROUND:begin
     operand<=$signed(alu_sum)>>>7;accum<={{2{phase[15]}},phase};state<=DRIVE_HP_STEP;
    end
    DRIVE_HP_STEP:begin
     phase<=alu_sum[15:0];chorus_second<=base[15];operand<={base[15],base};accum<=0;state<=DRIVE_ABS;
    end
    DRIVE_ABS:begin
     base<=alu_sum[15:0];accum<={2'd0,drive_env};
     operand<=drive_env[15:10]==0 ? 17'd1 : {11'd0,drive_env[15:10]};state<=DRIVE_ENV;
    end
    DRIVE_ENV:begin drive_env<=env_next;operand<={1'b0,drive_mag};accum<=0;state<=DRIVE_SIGN;end
    DRIVE_SIGN:begin accum<=alu_sum;operand<={drive_filter[15],drive_filter};state<=DRIVE_FILTER_DIFF;end
    DRIVE_FILTER_DIFF:begin
     // Round each quarter-step away from zero so the state reaches its input
     // a stuck DC residue when the input returns to silence.
     accum<=alu_sum;operand<=alu_sum[17] ? 17'd0 : 17'd3;state<=DRIVE_ROUND;
    end
    DRIVE_ROUND:begin operand<=$signed(alu_sum)>>>2;accum<={{2{drive_filter[15]}},drive_filter};state<=DRIVE_FILTER_STEP;end
    DRIVE_FILTER_STEP:begin
     // 3/4 post-gain compensation before the wet/dry blend. Reuse the same
     // seven-cycle multiplier as tremolo/interpolation, no new multiplier.
     drive_filter<=alu_sum[15:0];accum<=alu_sum;
     operand<={cabinet[15],cabinet};state<=CAB_DIFF;
    end
    // Two cascaded 1/4-step cabinet poles (~2.2 kHz each). Rounding
    // away from zero lets both states settle to digital zero without a gate.
    CAB_DIFF:begin accum<=alu_sum;operand<=alu_sum[17] ? 17'd0 : 17'd3;state<=CAB_ROUND;end
    CAB_ROUND:begin operand<=$signed(alu_sum)>>>2;accum<={{2{cabinet[15]}},cabinet};state<=CAB_STEP;end
    CAB_STEP:begin cabinet<=alu_sum[15:0];accum<=0;operand<=alu_sum[16:0];bits<=96;count<=6;state<=INTERP;end
    CHAIN_LOAD:begin
     dry<=chain_sample;accum<=0;base<=0;chorus_second<=0;
     case(chain_stage)
      0:begin kernel<=4;phase<=hp_context;drive_filter<=filter_context;stage_gain<=active_mask[3]?active_strengths[27:21]:7'd0;filled<=1;end
      1:begin kernel<=2;phase<=chorus_phase;stage_gain<=active_mask[1]&&chorus_filled?active_strengths[13:7]:7'd0;filled<=chorus_filled;end
      2:begin kernel<=3;phase<=flanger_phase;stage_gain<=active_mask[2]&&flanger_filled?active_strengths[20:14]:7'd0;filled<=flanger_filled;end
      3:begin kernel<=1;phase<=trem_phase;stage_gain<=active_mask[0]?active_strengths[6:0]:7'd0;filled<=1;end
     endcase
     state<=CHAIN_BEGIN;
    end
    CHAIN_BEGIN:begin
     if(kernel==1)begin accum<=0;operand<={dry[15],dry};bits<=triangle[14:8];count<=6;state<=INTERP;end
     else if(kernel==4)begin accum<={{2{dry[15]}},dry};operand<={phase[15],phase};state<=DRIVE_HP_DIFF;end
     else begin
      chorus_second<=0;accum<={8'd0,wp};operand<={7'd0,first_delay};
      fraction<=kernel==3?triangle[8:2]:triangle[6:0];state<=ADDRESS;
     end
    end
    CHAIN_SUM:begin
     chain_sample<=ram_input_sat;
     if(chain_stage==3)state<=CHAIN_FINAL;else begin chain_stage<=chain_stage+1'b1;state<=CHAIN_LOAD;end
    end
    CHAIN_FINAL:begin
     accum<={{2{chain_sample[15]}},chain_sample};operand<={chain_original[15],chain_original};final_gain<=1;state<=GAIN_DIFF;
     // GAIN_DIFF below uses chain_gain for this final wet/dry transition.
    end
    default:state<=IDLE;
   endcase
  end
 end
endmodule
