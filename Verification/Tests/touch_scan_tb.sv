`timescale 1ns/1ps
module touch_scan_tb;
  reg clk=0,rst_n=0,valid=0,dup=0;
  always #10 clk=~clk;
  reg [15:0] cdc[0:127];
  reg [15:0] data;
  wire [6:0] addr,map_addr;
  wire [7:0] map_data;
  wire [19:0] held,pluck;
  wire [3:0] pending;
  reg [7:0] map[0:127];
  integer ch,f,s,i,target;
  always @(posedge clk)data<=cdc[addr];
  led_profile mapping(.clk(clk),.rst_n(rst_n),.map_addr(map_addr),.map_data(map_data),
    .cmd_we(1'b0),.frame_tick(1'b0),.prepare(1'b0),.sleep_req(1'b0),.cancel(1'b0));
  touch_recog #(.CLK_HZ(1000),.NCH(97),.N_STR(4),.N_FRET(18),.CH_BITS(7),
    .CDC_RAM(1),.EXTERNAL_MAP(1),.SETTLE_MS_CFG(0),.RUNTIME_CONFIG(1)) dut(
    .clk(clk),.rst_n(rst_n),.panel_ready(1'b1),.sample_valid(valid),.sample_dup(dup),
    .cdc_bus(1552'd0),.cdc_addr(addr),.cdc_data(data),.shared_map_addr(map_addr),.shared_map_data(map_data),
    .capo_fret(4'd0),.fast_scan(1'b0),.vibrato_enabled(1'b1),.vibrato_use_aux(1'b1),
    .vibrato_owner_valid(1'b0),.vibrato_owner(2'd0),.vibrato_retrigger(1'b0),.vibrato_threshold(16'd360),
    .held_fret(held),.pluck_fret(pluck),.change_pending(pending));
  task scan;
    begin valid=1;@(negedge clk);valid=0;repeat(565)@(negedge clk);end
  endtask
  task clear;
    begin for(i=0;i<97;i=i+1)cdc[i]=32000;repeat(4)scan();end
  endtask
  task check(input integer owner,input integer expected);
    begin
      if(pluck[owner*5+:5]!==expected || held[owner*5+:5]!==expected)
        $fatal(1,"scan string=%0d expected=%0d held=%h pluck=%h",owner,expected,held,pluck);
    end
  endtask
  initial begin
    $readmemh("src/fpga_mem/chmap.hex",map);
    for(i=0;i<128;i=i+1)cdc[i]=32000;
    repeat(5)@(negedge clk);rst_n=1;repeat(110)@(negedge clk);repeat(24)scan();
    for(ch=0;ch<72;ch=ch+1)begin
      clear();s=(map[ch]>>5)-1;f=map[ch]&31;
      cdc[ch]=36000;scan();check(s,0);scan();check(s,f);
      for(i=0;i<4;i=i+1)if(i!=s)check(i,0);
      repeat(3)scan();check(s,f);
      // A single below-threshold scan must not replace a held note with open.
      cdc[ch]=32000;scan();check(s,f);
      cdc[ch]=36000;scan();check(s,f);
      clear();check(s,0);
      // A single high sample does not qualify a new fret.
      cdc[ch]=36000;scan();check(s,0);clear();check(s,0);
    end
    // Lowest and highest together, independent of their relative strength.
    clear();
    for(ch=0;ch<72;ch=ch+1)if((map[ch]&31)==1 || (map[ch]&31)==18)cdc[ch]=(map[ch]&31)==1?40000:34500;
    repeat(3)scan();for(i=0;i<4;i=i+1)check(i,18);
    clear();
    for(ch=0;ch<72;ch=ch+1)cdc[ch]=65535;
    repeat(4)scan();for(i=0;i<4;i=i+1)check(i,0);
    $display("PASS actual synchronous CDC RAM + shared LED mapping ROM, all 72 frets, stable hold, release hysteresis, one-scan noise, highest fret, invalid ADC");
    $finish;
  end
endmodule
