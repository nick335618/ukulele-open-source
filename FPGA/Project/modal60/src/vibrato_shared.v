// Four independent gesture states share one unchanged arithmetic pipeline.
// Each recognition round supplies <=N_STR events; a batch completes in
// <12*N_STR clocks (under 1 us for four strings at 50MHz), far below 11ms.
module vibrato_shared #(
 parameter W=21,N_STR=4,RUNTIME_CONFIG=0,AMP_ABS=120,REL_SH=5,AGE_MAX=10,MIN_GAP=2,DC_SH=3,AMP_SH=2
)(input wire clk,rst_n,input wire[N_STR-1:0]update,held,reseed,
 input wire[N_STR*W-1:0]delta_sum,input wire[N_STR*16-1:0]delta_pad,
 input wire[15:0]trigger_threshold,input wire fast_scan,
 output wire[N_STR-1:0]active,gate,output wire[N_STR*16-1:0]amplitude);
 localparam CW=2*W+60,EW=W+35,IW=N_STR<=2?1:$clog2(N_STR);
 (* syn_ramstyle="registers" *)reg[CW-1:0]contexts[0:N_STR-1];
 (* syn_ramstyle="registers" *)reg[EW-1:0]events[0:N_STR-1];
 reg[N_STR-1:0]pending;reg[IW-1:0]owner,chosen;reg[1:0]state;reg[3:0]wait_count;
 wire[CW-1:0]context_result;
 wire[EW-1:0]event_value=events[owner];
 integer i,k;always @(*)begin chosen=0;for(k=N_STR-1;k>=0;k=k-1)if(pending[k])chosen=k;end
 vibrato_detect #(.W(W),.SHARED_CONTEXT(1),.RUNTIME_CONFIG(RUNTIME_CONFIG),.AMP_ABS(AMP_ABS),.REL_SH(REL_SH),.AGE_MAX(AGE_MAX),.MIN_GAP(MIN_GAP),.DC_SH(DC_SH),.AMP_SH(AMP_SH)) core(
  .clk(clk),.rst_n(rst_n),.context_load(state==1),.context_in(contexts[owner]),.context_out(context_result),
  .update(state==2),.held(event_value[EW-1]),.reseed(event_value[EW-2]),
  .trigger_threshold(event_value[EW-3-:16]),.fast_scan(event_value[W+16]),
  .delta_sum(event_value[16+:W]),.delta_pad(event_value[15:0]),.active(),.gate(),.amplitude());
 genvar g;generate for(g=0;g<N_STR;g=g+1)begin:outputs
  assign gate[g]=contexts[g][0];assign active[g]=contexts[g][1];
  assign amplitude[g*16+:16]=(|contexts[g][CW-W-1:76])?16'hffff:contexts[g][75:60];
 end endgenerate
 always @(posedge clk or negedge rst_n)begin
  if(!rst_n)begin
   pending<=0;owner<=0;state<=0;wait_count<=0;
   for(i=0;i<N_STR;i=i+1)begin contexts[i]<={{(2*W+32){1'b0}},5'd31,16'd0,1'b0,3'd0,1'b0,1'b0,1'b0};events[i]<=0;end
  end else begin
   for(i=0;i<N_STR;i=i+1)if(update[i])begin
    events[i]<={held[i],reseed[i],trigger_threshold,fast_scan,delta_sum[i*W+:W],delta_pad[i*16+:16]};pending[i]<=1;
   end
   case(state)
    0:if(|pending)begin owner<=chosen;pending[chosen]<=update[chosen];state<=1;end
    1:state<=2;
    2:begin wait_count<=0;state<=3;end
    3:if(wait_count==7)begin contexts[owner]<=context_result;state<=0;end else wait_count<=wait_count+1'b1;
   endcase
  end
 end
endmodule
