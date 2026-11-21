// Reuses the unused capacity in the existing 128x8 channel-map BSRAM.
// A: immutable touch map 0..127, same one-cycle latency.
// B: profile banks 256..399 / 512..655, rendered PWM 768..839,
//    per-column Dot_onoff masks 896..910 (three bytes in each four-byte slot).
// Per LED: brightness8, metadata {0,phase4,rate2,breathing1}.
// Exactly one dual-port 1024x8 BSRAM, no additional audio RAM or DSP.
module led_profile #(parameter integer FRAME_HZ=50)(
 input wire clk,rst_n,
 input wire [6:0] map_addr,output reg [7:0] map_data,
 input wire cmd_we,input wire [7:0] op,index,d0,d1,d2,
 input wire [15:0] cmd_token,
 input wire frame_tick,prepare,sleep_req,pixel_active,cancel,input wire [15:0] render_phase,
 input wire [7:0] master_level,input wire [6:0] pixel_index,
 input wire mask_select,input wire [3:0] mask_index,
 input wire custom_mode,legacy_multiply,input wire [7:0] legacy_value,legacy_level,
 input wire legacy_request,output reg [7:0] legacy_pixel,
 output reg [1:0] calc_col,output reg [4:0] calc_row,
 output reg [7:0] pixel_data,output reg ready,
 output reg select_custom,output reg [7:0] requested_level,
 output reg [7:0] error,cursor,
 output reg [15:0] active_crc,active_token,output wire [15:0] generation,
 output wire [7:0] flags
);
 reg [7:0] mem[0:1023] /* synthesis syn_ramstyle = "block_ram" */;
 integer i;
 initial begin
   for(i=0;i<1024;i=i+1)mem[i]=0;
   $readmemh("src/fpga_mem/chmap.hex",mem,0,127);
 end
 always @(posedge clk)map_data<=mem[{3'b000,map_addr}];
 reg [9:0] ba;reg [7:0] bw,bq;reg bwe;
 always @(posedge clk)begin
   if(bwe && ba>=256)mem[ba]<=bw; // GW5A normal write mode: output holds during write
   else bq<=mem[ba];pixel_data<=bq;
 end
 reg active_bank,valid,uploading,commit_pending,chunk_pending,prepare_pending;
 reg [15:0] upload_token,upload_crc;
 reg [23:0] chunk;
 reg [1:0] byte_no;
 reg [2:0] crc_bit;

 
 reg [6:0] calc_pixel;
 reg [7:0] meta,wave;
 reg [15:0] product;reg [7:0] operand;
 reg [2:0] multiply_count;
 reg second_multiply;
 reg legacy_pending,legacy_calc;
 reg [17:0] dot_bits;
 reg [1:0] mask_no;
 wire [17:0] physical_bits={dot_bits[13],dot_bits[14],dot_bits[15],dot_bits[16],dot_bits[17],dot_bits[12:0]};
 reg [7:0] legacy_operand,legacy_gain;
 reg [4:0] state;
 localparam IDLE=0,WRITE=1,CRC=2,BR_ADDR=3,BR_WAIT=4,BR_GET=5,META_ADDR=6,META_WAIT=7,META_GET=8,MULTIPLY=9,MULT_DONE=10,PIXEL_WRITE=11,MASK_WRITE=12;
 localparam STEP_RAW=(65536+4*FRAME_HZ)/(8*FRAME_HZ);
 localparam STEP=STEP_RAW<1?1:STEP_RAW;
 assign generation=active_token; // committed revision tag; no redundant counter
 assign flags={3'd0,valid,commit_pending,uploading,chunk_pending,state!=IDLE};

 wire [8:0] product_sum={1'b0,product[15:8]}+(product[0]?{1'b0,operand}:9'd0);
 function [7:0] triangle(input [7:0] x);
  begin triangle=x[7]?{~x[6:0],1'b0}:{x[6:0],1'b0};end
 endfunction
 function [7:0] envelope(input [7:0] m,input [15:0] ph);
  reg [7:0] t;
  begin
    case(m[2:1])0:t=ph[12:5];1:t=ph[13:6];2:t=ph[14:7];default:t=ph[15:8];endcase
    t=t+{m[6:3],4'd0};
    // Pulse has an immediate head and a 1/8-period fading tail, then darkness.
    envelope=m[7] ? ((t[7:5]==0)?{~t[4:0],3'b000}:8'd0) : triangle(t);
  end
 endfunction
 // Synchronous read port prefetches the NEXT SPI pixel. Upload work only
 // starts outside a pixel burst; a queued write finishes within 27 clocks,
 // well before two SPI header bytes have finished at the supported rate.
 always @(*)begin
   ba={3'b110,pixel_index};bwe=0;bw=0;
   if(mask_select)ba={6'b111000,mask_index};
   case(state)
    WRITE:begin ba={active_bank?2'b01:2'b10,cursor}+byte_no;bwe=1;bw=chunk[7:0];end
    BR_ADDR,BR_WAIT,BR_GET:ba={active_bank?2'b10:2'b01,calc_pixel,1'b0};
    META_ADDR,META_WAIT,META_GET:ba={active_bank?2'b10:2'b01,calc_pixel,1'b1};
    PIXEL_WRITE:begin ba={3'b110,calc_pixel};bwe=1;bw=(valid||!custom_mode)?product[15:8]:8'd0;end
    MASK_WRITE:begin
      ba={6'b111000,calc_col,2'b00}+mask_no;bwe=1;
      case(mask_no)0:bw=physical_bits[7:0];1:bw=physical_bits[15:8];default:bw={6'd0,physical_bits[17:16]};endcase
    end
   endcase
 end
 always @(posedge clk or negedge rst_n)begin
  if(!rst_n)begin
    active_bank<=0;valid<=0;uploading<=0;commit_pending<=0;
    chunk_pending<=0;prepare_pending<=0;upload_token<=0;upload_crc<=16'hffff;
    chunk<=0;byte_no<=0;crc_bit<=0;
    calc_pixel<=0;calc_col<=0;calc_row<=0;meta<=0;wave<=0;product<=0;operand<=0;
    multiply_count<=0;second_multiply<=0;state<=IDLE;ready<=0;select_custom<=0;
    legacy_pending<=0;legacy_calc<=0;legacy_operand<=0;legacy_gain<=0;legacy_pixel<=0;
    dot_bits<=0;mask_no<=0;
    requested_level<=180;error<=0;cursor<=0;active_crc<=0;active_token<=0;
  end else begin
    select_custom<=0;

    if(legacy_request)begin legacy_pending<=1;legacy_operand<=legacy_value;legacy_gain<=legacy_level;end
    if(prepare)begin prepare_pending<=1;ready<=0;legacy_pending<=0;end
    if(cmd_we)case(op)
      0:begin end // read only
      1:begin
        if(cmd_token==0 || d0!=0 || d1!=0 || d2!=0)error<=1;
        else if(commit_pending || chunk_pending)error<=2;
        else if(!(uploading&&upload_token==cmd_token&&requested_level==index))begin
          upload_token<=cmd_token;requested_level<=index;
          cursor<=0;upload_crc<=16'hffff;uploading<=1;error<=0;
        end
      end
      2:begin
        if(!uploading||cmd_token!=upload_token)error<=3;
        else if(cursor>=3 && index==cursor-3)begin end // idempotent retry
        else if(chunk_pending)error<=2;
        else if(index!=cursor || cursor>141)error<=4;
        else begin chunk<={d2,d1,d0};chunk_pending<=1;byte_no<=0;error<=0;end
      end
      3:begin
        if(!uploading && (commit_pending || (valid&&active_token==cmd_token)) && cmd_token==upload_token && index==144 && d2==0 && {d1,d0}==upload_crc)begin error<=0;end
        else if(!uploading||chunk_pending||cmd_token!=upload_token||index!=144||cursor!=144||d2!=0||{d1,d0}!=upload_crc)error<=5;
        else begin commit_pending<=1;uploading<=0;select_custom<=1;error<=0;end
      end
      default:error<=1;
    endcase
    case(state)
      IDLE:begin
        if(prepare_pending)begin
          prepare_pending<=prepare;
          if(commit_pending)begin active_bank<=~active_bank;active_crc<=upload_crc;active_token<=upload_token;valid<=1;commit_pending<=0;end
          calc_pixel<=0;calc_col<=0;calc_row<=0;dot_bits<=0;state<=BR_ADDR;
        end else if(legacy_pending)begin
          operand<=legacy_operand;product<={8'd0,legacy_gain};
          multiply_count<=0;legacy_calc<=1;legacy_pending<=0;state<=MULTIPLY;
        end else if(chunk_pending&&!pixel_active)state<=WRITE;
      end
      WRITE:begin crc_bit<=0;state<=CRC;end
      CRC:begin
        upload_crc<={upload_crc[14:0],1'b0}^((upload_crc[15]^chunk[7])?16'h1021:16'd0);
        chunk[7:0]<={chunk[6:0],1'b0};
        if(crc_bit==7)begin

          if(byte_no==2)begin chunk_pending<=0;cursor<=cursor+3;state<=IDLE;end
          else begin byte_no<=byte_no+1'b1;chunk<={8'd0,chunk[23:8]};state<=WRITE;end
        end else crc_bit<=crc_bit+1'b1;
      end
      BR_ADDR:begin
        if(custom_mode)state<=BR_WAIT;
        else begin
          operand<=legacy_level;product<=legacy_multiply?{8'd0,legacy_value}:{legacy_value,8'd0};
          meta<=0;multiply_count<=0;second_multiply<=0;state<=legacy_multiply?MULTIPLY:PIXEL_WRITE;
        end
      end
      BR_WAIT:state<=BR_GET;
      BR_GET:begin operand<=bq;state<=META_ADDR;end
      META_ADDR:state<=META_WAIT;
      META_WAIT:state<=META_GET;
      META_GET:begin
        meta<=bq;wave<=envelope(bq,render_phase);
        product<=master_level==255 ? {operand,8'd0} : {8'd0,master_level+8'd1};
        multiply_count<=0;second_multiply<=0;state<=master_level==255?MULT_DONE:MULTIPLY;
      end
      MULTIPLY:begin
        product<={product_sum,product[7:1]};
        if(multiply_count==7)state<=MULT_DONE;else multiply_count<=multiply_count+1'b1;
      end
      MULT_DONE:begin
        if(legacy_calc)begin
          legacy_pixel<=product[15:8];legacy_calc<=0;state<=IDLE;
        end else if(meta[0]&&!second_multiply)begin
          operand<=product[15:8];product<={8'd0,wave+8'd1};
          multiply_count<=0;second_multiply<=1;state<=MULTIPLY;
        end else state<=PIXEL_WRITE;
      end
      PIXEL_WRITE:begin
        dot_bits<={dot_bits[16:0],((valid||!custom_mode)&&product[15:8]!=0)};
        if(calc_row==17)begin mask_no<=0;state<=MASK_WRITE;end
        else begin calc_pixel<=calc_pixel+1'b1;calc_row<=calc_row+1'b1;state<=BR_ADDR;end
      end
      MASK_WRITE:begin
        if(mask_no!=2)mask_no<=mask_no+1'b1;
        else if(calc_pixel==71)begin ready<=1;state<=IDLE;end
        else begin
          calc_pixel<=calc_pixel+1'b1;calc_col<=calc_col+1'b1;calc_row<=0;
          dot_bits<=0;state<=BR_ADDR;
        end
      end
    endcase
    if(cancel)begin uploading<=0;commit_pending<=0;chunk_pending<=0;end
  end
 end
endmodule
