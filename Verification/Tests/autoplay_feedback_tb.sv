`timescale 1ns/1ps
module autoplay_feedback_tb;
reg clk=0,rst=0;always #10 clk=~clk;
top_gv_host dut(.clk(clk),.rx(1'b1));
reg [7:0] packet[0:40];integer i,frames=0,model;reg [7:0] crc;reg [1:0] selected_model=0;
always @(posedge clk)if(dut.esp_tx_start&&dut.st_kind==1)begin
 packet[dut.st_idx]=dut.esp_tx_byte;
 if(dut.st_idx==33)begin
  crc=0;for(i=1;i<33;i=i+1)crc=crc^packet[i];
  if(packet[0]!==8'haa||packet[1]!==8'h84||packet[2]!==30||packet[33]!==crc)$fatal(1,"optical frame/CRC");
  if(packet[20]!==8'hf2)$fatal(1,"v2 automatic source mask");
  if({packet[28],packet[27],packet[26],packet[25]}!==32'h45404047)$fatal(1,"actual MIDI transport %h",{packet[28],packet[27],packet[26],packet[25]});
  if({packet[32],packet[31],packet[30],packet[29]}!==32'h01010101)$fatal(1,"all four event counters transported");
  frames=frames+1;
 end
end
task put(input [7:0] b);
begin force dut.esp_rx_byte=b;force dut.esp_rx_valid=1;@(negedge clk);force dut.esp_rx_valid=0;@(negedge clk);end
endtask
initial begin
 force dut.rst_n=rst;force dut.power_events=1;force dut.esp_rx_valid=0;force dut.esp_tx_ready=1;
 force dut.opto_any=0;force dut.trig_note=0;
 repeat(5)@(negedge clk);rst=1;repeat(140)@(negedge clk);
 // Actual 0x28 automatic chord G4/C4/E4/A4, frets 4/4/0/0 -> B4/E4/E4/A4.
 put(8'haa);put(8'h28);put(8'd8);put(1);put(1);put(1);put(0);put(4);put(4);put(0);put(0);
 put(8'h28^8'd8^8'd1^8'd1^8'd1);repeat(12)@(negedge clk);
 if(dut.feedback_count!==32'h01010101||dut.feedback_auto!==15)$fatal(1,"automatic chord feedback missing");
 if(dut.feedback_midi!==32'h45404047)$fatal(1,"feedback string order wrong %h",dut.feedback_midi);
 dut.st_pend84=1;repeat(3000)@(negedge clk);
 if(frames!=1)$fatal(1,"missing optical feedback frame frames=%d kind=%d idx=%d active=%d pend=%d",frames,dut.st_kind,dut.st_idx,dut.st_active,dut.st_pend84);
 // Physical E string then replaces only E's origin bit and note, no automatic injection into LED path.
 force dut.opto_any=1;force dut.opto_note=6'd6;force dut.opto_string=2'd1;force dut.performance_model=0;
 @(negedge clk);force dut.opto_any=0;repeat(3)@(negedge clk);
 if(dut.feedback_auto!==4'b1101||dut.feedback_count!==32'h01010201||dut.feedback_midi[15:8]!==66)$fatal(1,"physical note must replace its own lane only");
 force dut.capo_applied=3;force dut.capo_change=0;
 for(model=1;model<4;model=model+1)begin
  put(8'haa);put(8'h25);put(3);put(1);put(model);put(5);put(8'h25^3^1^model^5);repeat(3)@(negedge clk);
  put(8'haa);put(8'h28);put(8'd8);put(1);put(1);put(model+2);put(0);put(4);put(4);put(0);put(0);
  put(8'h28^8'd8^(model+2));repeat(12)@(negedge clk);
  if(dut.feedback_midi!==32'h4843434a||dut.feedback_auto!==15)$fatal(1,"timbre/capo pitch feedback mismatch model=%d actual=%h",model,dut.feedback_midi);
 end
 if(dut.feedback_count!==32'h04040504)$fatal(1,"all timbres did not enter audio engine exactly once per string");
 $display("PASS actual automatic 0x28 four-note audio submission, correct MIDI/counters/source, 0x84 v2 CRC, physical replacement isolation");$finish;
end
initial begin #2000000;$fatal(1,"timeout");end
endmodule
