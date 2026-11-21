// Two retained events per ADC channel. Legal detector samples are >1000
// clocks apart; eight records drain in eight clocks. Overflow is reported,
// never overwrites an older record. Mode changes preserve queued events.
// A second pending pluck on a string releases the older one immediately:
// grace is an upper bound, never a reason to lose rapid repeated plucks.
module performance_router #(parameter FORCE_ENABLED=0,parameter TOUCH_GRACE=0)(
 input wire clk,rst_n,poly_mode,mode_change,
 input wire [19:0] lock_clocks,
 input wire [3:0] detected,
 input wire [23:0] notes,
 input wire [7:0] time_ms,
 input wire [5:0] grace_ms,
 input wire [3:0] change_pending,
 input wire cancel_pending,
 input wire [47:0] peaks,
 output reg [3:0] accepted,
 output reg fire,
 output reg [1:0] channel,
 output reg [5:0] note,
 output reg [11:0] peak,
 output wire [3:0] depth,
 output reg [15:0] overflows,detected_count,accepted_count,emitted_count,
 input wire [1:0] model,
 output reg [1:0] emitted_model,
 input wire [11:0] velocities,
 output reg [2:0] velocity
);
 reg [19:0] lock_left;
 (* syn_ramstyle="registers" *) reg [22:0] records[0:7];
 reg [1:0] count[0:3];
 reg [7:0] stamps[0:7];
 reg [5:0] waits[0:7];
 reg [7:0] elapsed;
 reg [3:0] rd,wr;
 reg [1:0] rr;
 reg [3:0] incoming;
 reg [3:0] overflow_pending;
 reg pop_q,deferred_q;
 reg [1:0] selected_q;
 reg [22:0] record_q;
 reg pop;
 wire emit_now=TOUCH_GRACE ? (pop_q && !cancel_pending) : pop;
 reg [1:0] selected;
 wire [2:0] rr_next={1'b0,selected}+3'd1;
 integer i,j;
 reg [1:0] candidate;
 reg [3:0] stored;
 function [2:0] bits4(input [3:0] x);
  begin bits4={2'd0,x[0]}+{2'd0,x[1]}+{2'd0,x[2]}+{2'd0,x[3]};end
 endfunction
 assign depth={2'd0,count[0]}+{2'd0,count[1]}+{2'd0,count[2]}+{2'd0,count[3]};
 always @* begin
  incoming=0;elapsed=0;
  if(!mode_change && !(TOUCH_GRACE && cancel_pending)) begin
   if(poly_mode) incoming=detected;
   else if(lock_left==0) begin
    if(detected[0])incoming=1;
    else if(detected[1])incoming=2;
    else if(detected[2])incoming=4;
    else if(detected[3])incoming=8;
   end
  end
  pop=0;selected=rr;candidate=0;
  for(j=0;j<4;j=j+1) begin
   candidate={rr[1]^j[1]^(rr[0]&j[0]),rr[0]^j[0]}; // two-bit modulo-four addition
   elapsed=time_ms-stamps[{candidate,rd[candidate]}];
   if(!pop && count[candidate]!=0 && !(TOUCH_GRACE && cancel_pending) &&
      (!TOUCH_GRACE || count[candidate]==2 || !change_pending[candidate] || elapsed>={2'd0,waits[{candidate,rd[candidate]}]}))begin pop=1;selected=candidate;end
  end
  stored=0;
  for(j=0;j<4;j=j+1)
   stored[j]=incoming[j] && ((count[j]<2) || (pop && selected==j));
 end
 always @(posedge clk or negedge rst_n) begin
  if(!rst_n)begin
   pop_q<=0;deferred_q<=0;selected_q<=0;record_q<=0;
   overflow_pending<=0;lock_left<=0;rd<=0;wr<=0;rr<=0;
   accepted<=0;fire<=0;channel<=0;note<=0;peak<=0;velocity<=7;emitted_model<=0;
   overflows<=0;detected_count<=0;accepted_count<=0;emitted_count<=0;
   for(i=0;i<4;i=i+1)count[i]<=0;
  end else begin
   accepted<=stored;fire<=emit_now;overflow_pending<=incoming & ~stored;
   pop_q<=pop;
   if(pop)begin selected_q<=selected;record_q<=records[{selected,rd[selected]}];
     deferred_q<=waits[{selected,rd[selected]}]!=0;end
   detected_count<=detected_count+bits4(detected);
   accepted_count<=accepted_count+bits4(accepted);
   // One diagnostic pipeline stage keeps queue arbitration out of the saturating adder path.
   if(|overflow_pending)begin
    if(overflows<16'hFFFB)overflows<=overflows+bits4(overflow_pending);
    else overflows<=16'hFFFF;
   end
   if(mode_change || poly_mode)lock_left<=0;
   else begin
    if(lock_left!=0)lock_left<=lock_left-1'b1;
    if(|incoming)lock_left<=lock_clocks;
   end
   if(emit_now)begin
    // Register the dequeue selection before the note mux: no arbitration,
    // deadline compare and note selection packed into one timing path.
    channel<=TOUCH_GRACE ? selected_q : selected;
    emitted_model<=TOUCH_GRACE ? record_q[22:21] : records[{selected,rd[selected]}][22:21];
    note<=TOUCH_GRACE ? (deferred_q ? notes[selected_q*6+:6] : record_q[17:12]) : records[{selected,rd[selected]}][17:12];
    peak<=TOUCH_GRACE ? record_q[11:0] : records[{selected,rd[selected]}][11:0];
    velocity<=TOUCH_GRACE ? record_q[20:18] : records[{selected,rd[selected]}][20:18];
    emitted_count<=emitted_count+1'b1;
   end
   if(pop)begin rd[selected]<=~rd[selected];rr<=rr_next[1:0];end
   for(i=0;i<4;i=i+1)begin
    if(stored[i])begin
     records[{i[1:0],wr[i]}]<={model,(FORCE_ENABLED?velocities[i*3+:3]:3'd7),notes[i*6+:6],peaks[i*12+:12]};
     // Stable fingering always uses the captured note with zero grace.
     // Only a pending change at capture permits later retargeting.
     stamps[{i[1:0],wr[i]}]<=time_ms;waits[{i[1:0],wr[i]}]<=change_pending[i] ? grace_ms : 6'd0;
     wr[i]<=~wr[i];
    end
    case({stored[i],pop && selected==i})
     2'b10:count[i]<=count[i]+1'b1;
     2'b01:count[i]<=count[i]-1'b1;
     default:;
    endcase
    if(TOUCH_GRACE && cancel_pending)begin count[i]<=0;rd[i]<=0;wr[i]<=0;end
   end
  end
 end
endmodule
