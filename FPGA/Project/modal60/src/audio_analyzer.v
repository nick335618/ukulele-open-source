// Read-only 4096-point full-rate audio analyser. No multiplier/DSP primitive.
// One signed serial multiplier is shared by Hann, complex FFT and magnitude.
// Captures consecutive FINAL I2S samples. Capture/FFT/export never stall audio.
// FFT uses signed 16-bit state, /2 per radix-2 stage, periodic Hann, Q15 twiddles.
// A request starts one block; gaps between blocks are deliberate, not joined.
// SPI 0x8B: AA 8B 14 [A1,type,seqLE,offsetLE,14 data bytes] XOR.
// type0 metadata; type1 2048 half-dB bins; type2 256 signed16 PCM samples;
// type3 sum16 of the exact spectrum+PCM bytes (padding excluded).
module audio_analyzer(
 input clk,input rst_n,input enable,input request,input [15:0] token,
 input scope_only,
 input sample_tick,input signed [15:0] sample,
 output packet_ready,output byte_valid,output reg [7:0] byte_out,
 input take_byte,output reg busy,
 input wire env_play,env_scan,env_write,
 input wire [6:0] env_raddr,env_waddr,
 input wire [7:0] env_data,output wire [7:0] env_q,
 output wire env_scan_grant,env_write_grant
);
 localparam IDLE=0,CAPTURE=1,LOAD0=2,LOAD1=3,LOAD2=4,LOAD3=5,
  LOAD4=6,LOAD5=7,LOADWRITE=8,MUL=9,
  READ0=10,READ1=11,READ2=12,READB0=13,READB1=14,READB2=15,
  COS0=16,COS1=17,COS2=18,SIN0=19,SIN1=20,SIN2=21,
  PROD0=22,PROD1=23,PROD2=24,PROD3=25,PROD4=26,WRITEA=27,WRITEB=28,NEXT=29,
  MAG0=30,MAG1=31,MAG2=32,MAG3=33,MAG4=34,MAG5=35,MAGWRITE=36,
  EXPORT=37,FETCH0=38,FETCH1=39,FETCH2=40,SEND=41,LOAD6_STATE=42,MAGWRITE_PRE=43,MAGEXP=44,MAGDB=45;
 reg [5:0] state,ret;reg scope_frame;
 // FFT input uses its real working RAM in-place; retain only the first
 // 256 raw samples separately for the time trace and delayed XY pairs.
 // A 16-bit BSRAM bank has room for the 256 capture words and 128 profiles.
 (* syn_ramstyle="block_ram" *) reg signed [15:0] capture [0:511];
 reg signed [15:0] real_mem [0:4095],imag_mem [0:4095];
 reg [7:0] levels [0:2047];
 reg signed [15:0] cosine [0:1023];
 initial $readmemh("src/analyzer_cos.hex",cosine);
 reg [11:0] cap_idx,index,addr_a,addr_b,half,base,j,step,phase;
 reg [11:0] rd_addr;
 reg signed [15:0] cap_q;
 reg signed [15:0] rq,iq;
 reg signed [17:0] ar,ai,br,bi;
 wire wr_en=(state==LOADWRITE || state==WRITEA || state==WRITEB || (state==CAPTURE && sample_tick && enable));
 reg [11:0] wr_addr;
 reg signed [17:0] wr_real,wr_imag;
 reg signed [18:0] tr,ti;
 reg signed [35:0] p0,p1,p2;
 reg signed [15:0] wc,ws;
 reg [11:0] cos_phase;
 wire [9:0] cos_addr=cos_phase[10] ? (10'd0-cos_phase[9:0]) : cos_phase[9:0];
 reg signed [15:0] cos_q;
 reg cos_neg,cos_zero;
 wire signed [15:0] cos_value=cos_zero ? 16'sd0 : (cos_neg ? -cos_q : cos_q);
 reg [10:0] level_addr;
 reg [7:0] level_q;
 reg signed [27:0] sum;
 reg signed [15:0] mean;
 reg [15:0] seq;
 reg [10:0] peak_bin;
 reg [7:0] peak_level;
 reg signed [17:0] ma,mb;
 reg [17:0] multiplier;
 reg signed [35:0] multiplicand,acc,product;
 reg mul_neg;
 reg [4:0] mul_count;
 reg [36:0] mag_power;
 reg [7:0] mag_level;
 reg [7:0] mag_level_q;
 reg [5:0] quant_exp;
 reg [2:0] quant_frac;
 reg quant_zero;
 reg [2:0] kind;
 reg [11:0] offset;
 reg [4:0] tx_idx;
 reg [7:0] frame_xor,stream_byte;
 reg [15:0] block_sum;
 reg [8:0] pcm_qaddr;
 integer k,exponent,fraction,db;
 function [11:0] reverse12(input [11:0] a);
  integer b;begin for(b=0;b<12;b=b+1) reverse12[b]=a[11-b];end
 endfunction
 function signed [17:0] half_even(input signed [18:0] x);
  begin half_even=(x>>>1)+((x[0] && x[1]) ? 18'sd1 : 18'sd0);end
 endfunction
 function signed [18:0] q15_even(input signed [35:0] x);
  begin q15_even=(x>>>15)+((x[14] && ((|x[13:0]) || x[15])) ? 19'sd1 : 19'sd0);end
 endfunction
 // A serial multiply starts with signed 18-bit operands and ends after 18 clocks.
 task multiply(input signed [17:0] a,input signed [17:0] b,input [5:0] destination);
 begin
  multiplier<=b[17] ? -b : b;
  multiplicand<=a[17] ? -{{18{a[17]}},a} : {{18{a[17]}},a};
  acc<=0;mul_neg<=a[17]^b[17];mul_count<=0;ret<=destination;state<=MUL;
 end endtask
 // Plucks own the read port during event allocation. PCM export retries
 // FETCH1 if preempted; a profile scan can wait behind both users.
 wire pcm_read=state==FETCH1 && (kind==2 || kind==4);
 wire pcm_write=state==CAPTURE && sample_tick && enable && cap_idx<256;
 assign env_scan_grant=env_scan && !env_play && !pcm_read;
 assign env_write_grant=env_write && !pcm_write && !env_play;
 assign env_q=cap_q[7:0];
 wire [8:0] cap_read_address=(env_play || env_scan_grant) ?
                             {2'b10,env_raddr} : {1'b0,rd_addr[7:0]};
 wire [8:0] cap_write_address=pcm_write ? {1'b0,cap_idx[7:0]} : {2'b10,env_waddr};
 wire [15:0] cap_write_value=pcm_write ? sample : {8'd0,env_data};
 always @(posedge clk) begin
  cap_q<=capture[cap_read_address];
  if(pcm_write || env_write_grant)capture[cap_write_address]<=cap_write_value;
 end
 always @(posedge clk) begin
  rq<=real_mem[rd_addr];iq<=imag_mem[rd_addr];
  cos_q<=cosine[cos_addr];cos_neg<=cos_phase[11]^cos_phase[10];
  cos_zero<=cos_phase[10] && cos_phase[9:0]==0;
  level_q<=levels[level_addr];
  if(wr_en)begin real_mem[wr_addr]<=wr_real;imag_mem[wr_addr]<=wr_imag;end
  if(state==MAGWRITE)levels[index[10:0]]<=mag_level_q;
 end
 always @(*)begin
  wr_addr=addr_a;wr_real=0;wr_imag=0;
  if(state==CAPTURE)begin wr_addr=reverse12(cap_idx);wr_real={{2{sample[15]}},sample};end
  // One input guard bit: DC removal can double the PCM range. This fixed
  // /2 is compensated in the dBFS calibration, not in the audible signal.
  else if(state==LOADWRITE)begin wr_addr=reverse12(index);wr_real=(product>>>16)+((product[15] && ((|product[14:0]) || product[16])) ? 18'sd1 : 18'sd0);end
  else if(state==WRITEA)begin wr_real=half_even($signed(ar)+tr);wr_imag=half_even($signed(ai)+ti);end
  else if(state==WRITEB)begin wr_addr=addr_b;wr_real=half_even($signed(ar)-tr);wr_imag=half_even($signed(ai)-ti);end
  // Fixed calibration: peak unit sine after input /2 and Hann/N has power 2^24.
  // 0=below -72 dBFS; 48..192 represent -72..0 dBFS, half-dB steps.
  // Static bit slices, not a priority encoder followed by a variable barrel
  // shifter. The logarithm and peak comparison have separate pipeline stages.
  exponent=0;fraction=0;
  for(k=0;k<37;k=k+1)if(mag_power[k])begin
    exponent=k;fraction=(k>=3) ? ((mag_power>>(k-3))&7) : 0;
  end
  case(quant_frac)0:db=0;1:db=1;2:db=2;3:db=3;4:db=4;5:db=4;6:db=5;default:db=5;endcase
  db=192+($signed({1'b0,quant_exp})-24)*6+db;
  mag_level=(quant_zero || db<48 || index==0) ? 0 : (db>192 ? 192 : db);
 end
 assign packet_ready=(state==EXPORT || state==FETCH0 || state==FETCH1 || state==FETCH2 || state==SEND);
 assign byte_valid=(state==SEND);
 // Byte preparation is separated from acceptance; synchronous RAM latency and
 // arbitrary SPI FIFO backpressure are both honoured. No changing live frame.
 always @(*)begin
  byte_out=0;
  case(tx_idx)
   0:byte_out=8'hAA;1:byte_out=8'h8B;2:byte_out=20;
   3:byte_out=scope_frame?8'hA3:8'hA1;4:byte_out={5'd0,kind};5:byte_out=seq[7:0];6:byte_out=seq[15:8];
   7:byte_out=offset[7:0];8:byte_out={5'd0,offset[10:8]};
   23:byte_out=frame_xor;
   default:begin
    if(kind==0)case(tx_idx)
     9:byte_out=0;10:byte_out=scope_frame?1:16; // snapshot256 or FFT4096
     11:byte_out=8'h80;12:byte_out=8'hF0;13:byte_out=8'hFA;14:byte_out=2; // 50,000,000
     15:byte_out=0;16:byte_out=4; // divider 1024
     17:byte_out=scope_frame?0:peak_bin[7:0];18:byte_out=scope_frame?0:{5'd0,peak_bin[10:8]};
     19:byte_out=1;20:byte_out=2; // Hann, two units/dB
     21:byte_out=3;22:byte_out=32; // full-rate flag, reserved
    endcase
    else if(kind==3)case(tx_idx)9:byte_out=block_sum[7:0];10:byte_out=block_sum[15:8];default:byte_out=0;endcase
    else byte_out=stream_byte;
   end
  endcase
 end
 always @(posedge clk or negedge rst_n)begin
  if(!rst_n)begin
   state<=IDLE;scope_frame<=0;busy<=0;cap_idx<=0;sum<=0;mean<=0;index<=0;rd_addr<=0;
   cos_phase<=0;level_addr<=0;seq<=0;peak_bin<=0;peak_level<=0;kind<=0;offset<=0;
   tx_idx<=0;frame_xor<=0;block_sum<=0;stream_byte<=0;
   half<=1;base<=0;j<=0;step<=2048;phase<=0;addr_a<=0;addr_b<=1;
   ar<=0;ai<=0;br<=0;bi<=0;wc<=0;ws<=0;tr<=0;ti<=0;
   ma<=0;mb<=0;multiplier<=0;multiplicand<=0;acc<=0;product<=0;
   mul_count<=0;mul_neg<=0;ret<=IDLE;p0<=0;p1<=0;p2<=0;mag_power<=0;pcm_qaddr<=0;
   quant_exp<=0;quant_frac<=0;quant_zero<=1;mag_level_q<=0;
  end else if(!enable && state<EXPORT)begin state<=IDLE;busy<=0;end
  else case(state)
   IDLE:if(request && enable)begin busy<=1;cap_idx<=0;sum<=0;seq<=token;scope_frame<=scope_only===1'b1;state<=CAPTURE;end
   CAPTURE:if(sample_tick)begin
    sum<=sum+sample;
    if(scope_frame && cap_idx==255)begin kind<=0;offset<=0;tx_idx<=0;block_sum<=0;state<=EXPORT;end
    else if(cap_idx==4095)begin mean<=($signed(sum)+$signed(sample))>>>12;index<=0;state<=LOAD0;end
    else cap_idx<=cap_idx+1'b1;
   end
   LOAD0:begin rd_addr<=reverse12(index);cos_phase<=index;state<=LOAD1;end
   LOAD1:state<=LOAD2;
   LOAD2:state<=LOAD3;
   LOAD3:begin ma<=$signed(rq)-$signed(mean);mb<=(18'sd32767-$signed(cos_value))>>>1;state<=LOAD4;end
   LOAD4:multiply(ma,mb,LOADWRITE);
   LOADWRITE:if(index==4095)begin half<=1;step<=2048;base<=0;j<=0;phase<=0;addr_a<=0;addr_b<=1;state<=READ0;end
     else begin index<=index+1'b1;state<=LOAD0;end
   MUL:begin
    if(multiplier[0])acc<=acc+multiplicand;
    multiplicand<=multiplicand<<<1;multiplier<=multiplier>>1;
    if(mul_count==17)begin product<=mul_neg ? -(acc+(multiplier[0]?multiplicand:36'sd0)) : (acc+(multiplier[0]?multiplicand:36'sd0));state<=ret;end
    else mul_count<=mul_count+1'b1;
   end
   READ0:begin rd_addr<=addr_a;state<=READ1;end
   READ1:state<=READ2;
   READ2:begin ar<=rq;ai<=iq;rd_addr<=addr_b;state<=READB0;end
   READB0:state<=READB1;
   READB1:begin br<=rq;bi<=iq;cos_phase<=phase;state<=COS0;end
   COS0:state<=COS1;
   COS1:begin wc<=cos_value;cos_phase<=phase+12'd3072;state<=SIN0;end // cos(theta-pi/2)=sin(theta)
   SIN0:state<=SIN1;
   SIN1:begin ws<=-cos_value;state<=PROD0;end // forward FFT -sin
   PROD0:multiply(br,{{2{wc[15]}},wc},PROD1);
   PROD1:begin p0<=product;state<=PROD2;end
   PROD2:multiply(bi,{{2{ws[15]}},ws},PROD3);
   PROD3:begin tr<=q15_even($signed(p0)-$signed(product));state<=PROD4;end
   PROD4:multiply(br,{{2{ws[15]}},ws},LOAD5);
   LOAD5:begin p1<=product;state<=LOAD6_STATE;end
   LOAD6_STATE:multiply(bi,{{2{wc[15]}},wc},SIN2);
   SIN2:begin ti<=q15_even($signed(p1)+$signed(product));state<=WRITEA;end
   WRITEA:state<=WRITEB;
   WRITEB:state<=NEXT;
   NEXT:begin
    if(j+12'd1<half)begin j<=j+1'b1;addr_a<=addr_a+1'b1;addr_b<=addr_b+1'b1;phase<=phase+step;state<=READ0;end
    else if(({1'b0,base}+({1'b0,half}<<1))<4096)begin
     base<=base+(half<<1);j<=0;phase<=0;addr_a<=base+(half<<1);addr_b<=base+(half<<1)+half;state<=READ0;
    end else if(half==2048)begin index<=0;peak_bin<=0;peak_level<=0;state<=MAG0;end
    else begin half<=half<<1;step<=step>>1;base<=0;j<=0;phase<=0;addr_a<=0;addr_b<=half<<1;state<=READ0;end
   end
   MAG0:begin rd_addr<=index;state<=MAG1;end
   MAG1:state<=MAG2;
   MAG2:begin ma<=rq;mb<=iq;state<=MAG3;end
   MAG3:multiply(ma,ma,MAG4);
   MAG4:begin p2<=product;state<=MAG5;end
   MAG5:multiply(mb,mb,MAGWRITE_PRE);
   MAGWRITE_PRE:begin mag_power<={1'b0,p2}+{1'b0,product};state<=MAGEXP;end
   MAGEXP:begin quant_exp<=exponent[5:0];quant_frac<=fraction[2:0];quant_zero<=mag_power==0;state<=MAGDB;end
   MAGDB:begin mag_level_q<=mag_level;state<=MAGWRITE;end
   MAGWRITE:begin
    if(mag_level_q>peak_level)begin peak_level<=mag_level_q;peak_bin<=index[10:0];end
    if(index==2047)begin kind<=0;offset<=0;tx_idx<=0;block_sum<=0;state<=EXPORT;end
    else begin index<=index+1'b1;state<=MAG0;end
   end
   EXPORT:begin frame_xor<=8'h8B^8'd20;tx_idx<=0;state<=FETCH0;end
   FETCH0:begin
    level_addr<=offset+tx_idx-5'd9;
    if(kind==4)rd_addr<=12'd128+((offset+tx_idx-5'd9)>>2)-(((offset+tx_idx-5'd9)&2) ? 12'd32 : 12'd0);
    else rd_addr<=(offset+tx_idx-5'd9)>>1;
    state<=FETCH1;
   end
   FETCH1:if (!pcm_read || !env_play) state<=FETCH2;
   FETCH2:begin
    if(kind==1)stream_byte<=((offset+tx_idx-5'd9)<2048) ? level_q : 8'd0;
    else if(kind==2 || kind==4)stream_byte<=((offset+tx_idx-5'd9)<512) ? (((offset+tx_idx-5'd9)&1) ? cap_q[15:8] : cap_q[7:0]) : 8'd0;
    state<=SEND;
   end
   SEND:if(take_byte)begin
    if(tx_idx>=1 && tx_idx<=22)frame_xor<=frame_xor^byte_out;
    // XOR is initialised before CMD/LEN, so only payload is folded.
    if(tx_idx==1 || tx_idx==2)frame_xor<=frame_xor;
    if(tx_idx>=9 && tx_idx<=22 && ((kind==1 && offset+tx_idx-9<2048)||((kind==2 || kind==4) && offset+tx_idx-9<512)))block_sum<=block_sum+byte_out;
    if(tx_idx==23)begin
     if(kind==3)begin state<=IDLE;busy<=0;end
     else begin
      if(kind==0)begin kind<=scope_frame?2:1;offset<=0;end
      else if(kind==1 && offset>=2044)begin kind<=2;offset<=0;end
      else if(kind==2 && offset>=504)begin kind<=4;offset<=0;end
      else if(kind==4 && offset>=504)begin kind<=3;offset<=0;end
      else offset<=offset+11'd14;
      state<=EXPORT;
     end
    end else begin tx_idx<=tx_idx+1'b1;state<=FETCH0;end
   end
   default:begin state<=IDLE;busy<=0;end
  endcase
 end
endmodule
