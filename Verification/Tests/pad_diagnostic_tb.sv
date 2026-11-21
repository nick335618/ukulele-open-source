`timescale 1ns/1ps
module pad_diagnostic_tb;
reg clk=0,rst=0;
always #10 clk=~clk;
top_gv_host dut(.clk(clk),.rx(1'b1));
reg [6:0] channel=0;
reg [7:0] bytes[0:31];
integer frames=0,i;
reg [7:0] check;
always @(posedge clk)if(dut.esp_tx_start&&dut.st_kind==10)begin
 bytes[dut.st_idx]=dut.esp_tx_byte;
 if(dut.st_idx==bytes[2]+3)begin
  check=0;for(i=1;i<dut.st_idx;i=i+1)check=check^bytes[i];
  if(bytes[dut.st_idx]!==check)$fatal(1,"diagnostic CRC");
  if(bytes[0]!==8'haa||bytes[1]!==8'h8e||bytes[4]!==channel||bytes[6]!==8'h87||
    {bytes[8],bytes[7]}!==16'd40000||{bytes[10],bytes[9]}!==16'd32000||{bytes[12],bytes[11]}!==16'd8000)
    $fatal(1,"diagnostic raw fields");
  if(bytes[3]==2 && (bytes[15]!==7||bytes[16]!==8'h71||bytes[17]!==0||{bytes[19],bytes[18]}!==333||
    {bytes[21],bytes[20]}!==120||bytes[22]!==0))$fatal(1,"vibrato state fields");
  frames=frames+1;
 end
end
task send(input [7:0] byte_in);
begin force dut.esp_rx_byte=byte_in;force dut.esp_rx_valid=1;@(negedge clk);force dut.esp_rx_valid=0;@(negedge clk);end
endtask
task query(input [7:0] version,target,token);
begin send(8'haa);send(8'h22);send(3);send(version);send(target);send(token);send(8'h22^3^version^target^token);repeat(180)@(negedge clk);end
endtask
initial begin
force dut.rst_n=rst;force dut.esp_rx_valid=0;force dut.esp_tx_ready=1;
force dut.touch_config_ready=1;force dut.touch_diag_valid=1;force dut.touch_diag_ch=channel;
force dut.touch_diag_data={16'd40000,4'b0111,16'd8000,16'd32000};
force dut.gesture_mask=7;force dut.touch_vib_str=1;force dut.audio_vib_on=1;
force dut.vib_env_hi=1;force dut.glide_owner_valid=1;force dut.any_gliding=0;
force dut.glide_owner_string=0;force dut.touch_vib_amp=333;force dut.touch_vib_threshold=120;force dut.voice_model=0;
repeat(4)@(negedge clk);rst=1;repeat(140)@(negedge clk);
query(1,0,17);if(frames!=1||bytes[2]!=12||bytes[5]!=17)$fatal(1,"v1 compatibility");
channel=96;query(2,96,18);if(frames!=2||bytes[2]!=20||bytes[5]!=18)$fatal(1,"auxiliary channel 96");
query(2,97,19);if(frames!=2)$fatal(1,"out of range accepted");
if(dut.performance_detected!=0)$fatal(1,"read-only diagnostic injected a pluck");
$display("PASS v1/v2 diagnostic frame, 97-channel boundary, raw values, vibrato gate/owner/threshold, CRC, no pluck injection");$finish;
end
endmodule
