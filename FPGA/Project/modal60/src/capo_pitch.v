// Global virtual capo. Shared shift/add preparation, no DSP or audio RAM.
// Audio-frame latching is performed by the modal engine. Body resonances
// remain fixed, as on an acoustic instrument fitted with a mechanical capo.
module capo_pitch(input wire clk,rst_n,input wire [3:0] semitones,
 input wire [18:0] gesture,output reg [18:0] combined,
 output reg [16:0] common,output reg [3:0] applied);
 function [16:0] ratio_lut(input [3:0] n);begin case(n)
     4'd0:ratio_lut=17'd32768;
     4'd1:ratio_lut=17'd34716;
     4'd2:ratio_lut=17'd36781;
     4'd3:ratio_lut=17'd38968;
     4'd4:ratio_lut=17'd41285;
     4'd5:ratio_lut=17'd43740;
     4'd6:ratio_lut=17'd46341;
     4'd7:ratio_lut=17'd49097;
     4'd8:ratio_lut=17'd52016;
     4'd9:ratio_lut=17'd55109;
     4'd10:ratio_lut=17'd58386;
     4'd11:ratio_lut=17'd61858;
     4'd12:ratio_lut=17'd65536;
     default:ratio_lut=17'd32768;
 endcase end endfunction
 wire [16:0] ratio=ratio_lut(semitones);
 reg [18:0] seen_g;
 reg [3:0] seen_c;
 reg [18:0] bits;
 reg [35:0] shifted,acc;
 reg [4:0] count;
 reg working;
 wire [35:0] next_acc=acc+(bits[0]?shifted:36'd0);
 always @(posedge clk or negedge rst_n)begin
  if(!rst_n)begin combined<=32768;common<=32768;applied<=0;
   seen_g<=0;seen_c<=15;bits<=0;shifted<=0;acc<=0;count<=0;working<=0;end
  else if(!working)begin
   if(seen_g!=gesture || seen_c!=semitones)begin
    seen_g<=gesture;seen_c<=semitones;bits<=gesture;
    shifted<={19'd0,ratio};acc<=0;count<=18;working<=1;
   end
  end else begin
   acc<=next_acc;bits<=bits>>1;shifted<=shifted<<1;
   if(count==0)begin
    // gesture is Q15, capo <=2x. Saturate rather than wrap extreme glides.
    combined<=next_acc[35:34]!=0 ? 19'h7ffff : next_acc[33:15];
    common<=ratio_lut(seen_c);
    applied<=seen_c;working<=0;
   end else count<=count-1'b1;
  end
 end
endmodule
