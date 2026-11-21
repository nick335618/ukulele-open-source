// Temporal playing-force estimate: count ADC sample intervals from 1/4 of
// the calibrated detector threshold to the detector threshold itself.
// A qualified pluck must cross this endpoint; requiring 2x threshold used to
// classify legitimate shallow/fast plucks as soft. No extra event delay.
// This observer cannot generate or suppress a strum event. Only an existing
// qualified detector event publishes velocity. DSP/BSRAM are not required.
module opto_velocity(
 input wire clk,rst_n,clear,sample_valid,
 input wire [47:0] levels,thresholds,
 input wire [3:0] armed,events,
 output reg [11:0] velocities
);
 (* syn_ramstyle="registers" *) reg [5:0] age[0:3];
 reg [3:0] reached;
 (* syn_ramstyle="registers" *) reg [2:0] captured[0:3];
 integer i;
 function [2:0] time_to_velocity;
  input [5:0] n;
  begin
   // 22.2 us per four-channel scan. Fast/ordinary edges up to eight
   // intervals retain legacy full excitation. Only measured slower edges
   // attenuate; these boundaries require calibration on the real optics.
   if(n<=8)time_to_velocity=7;
   else if(n<=12)time_to_velocity=6;
   else if(n<=18)time_to_velocity=5;
   else if(n<=26)time_to_velocity=4;
   else if(n<=36)time_to_velocity=3;
   else if(n<=46)time_to_velocity=2;
   else if(n<=56)time_to_velocity=1;
   else time_to_velocity=0;
  end
 endfunction
 genvar c;
 generate for(c=0;c<4;c=c+1)begin:channel
  wire [11:0] level=levels[c*12+:12];
  wire [11:0] threshold=thresholds[c*12+:12];
  wire [11:0] low=(threshold<4)?12'd1:(threshold>>2);
  wire [11:0] high=threshold;
  wire [2:0] candidate=time_to_velocity(age[c]);
  // Missing timing is not evidence of a soft pluck. Configuration changes,
  // reset alignment or an already-qualified falling edge retain legacy
  // volume instead of silently forcing a legitimate event to grade 0/2.
  wire [2:0] event_velocity=reached[c]?captured[c]:
   level>high?candidate:3'd7;
  // Independent event register: capture qualified data before a simultaneous
  // measurement clear. Keep the data mux out of the timing-counter enables.
  always @(posedge clk or negedge rst_n)begin
   if(!rst_n)velocities[c*3+:3]<=7;
   else if(sample_valid && events[c])velocities[c*3+:3]<=event_velocity;
   else if(clear)velocities[c*3+:3]<=7;
  end
  always @(posedge clk or negedge rst_n)begin
   if(!rst_n)begin age[c]<=0;reached[c]<=0;captured[c]<=0;end
   else if(clear)begin age[c]<=0;reached[c]<=0;captured[c]<=0;end
   else if(sample_valid)begin
    if(!armed[c] || events[c])begin age[c]<=0;reached[c]<=0;end
    else if(level<low || (reached[c] && level<=threshold))begin
     age[c]<=0;reached[c]<=0;
    end else if(!reached[c])begin
     if(level>high)begin reached[c]<=1;captured[c]<=candidate;end
     else if(age[c]==0)age[c]<=1;
     else if(age[c]!=63)age[c]<=age[c]+1'b1;
    end
   end
  end
 end endgenerate
endmodule
