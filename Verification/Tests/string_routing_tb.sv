`timescale 1ns/1ps
module string_routing_tb;
  reg clk=0,rst_n=0;
  always #10 clk=~clk;
  reg [19:0] frets=0;
  reg [3:0] plucks=0;
  top_gv_host dut(.clk(clk),.rx(1'b1));
  integer events=0,channel_id,fret,model_id,poly_id;
  integer expected[0:3];
  integer base_note[0:3];
  integer queued=0;
  integer expected_audio[0:1023];
  always @(negedge clk)begin
    if(dut.performance_fire)begin
      if(dut.performance_note!==expected[dut.performance_channel])
        $fatal(1,"channel=%0d frets=%h note=%0d expected=%0d",dut.performance_channel,frets,dut.performance_note,expected[dut.performance_channel]);
      expected_audio[queued]=expected[dut.performance_channel]+(model_id==1?10:0);
      queued=queued+1;
    end
    if(dut.eng_trig)begin
      if(events>=queued || dut.eng_note!==expected_audio[events])
        $fatal(1,"audio note mapping failed model=%0d eng=%0d expected=%0d",model_id,dut.eng_note,expected_audio[events]);
      events=events+1;
    end
  end
  task pluck(input [3:0] mask,input integer count);
    integer before_count;
    begin
      before_count=events;plucks=mask;@(negedge clk);plucks=0;
      repeat(40)@(negedge clk);
      if(events!=before_count+count)$fatal(1,"lost or duplicate pluck");
    end
  endtask
  initial begin
    base_note[0]=7;base_note[1]=4;base_note[2]=0;base_note[3]=9;
    force dut.rst_n=rst_n;force dut.esp_rx_valid=0;
    force dut.power_events=1;force dut.touch_rate_busy=0;
    force dut.touch_change_pending=0;force dut.touch_grace_ms=0;
    force dut.touch_pluck_fret=frets;force dut.opto_ev=plucks;
    force dut.sample_tick=0;force dut.o_first_lock=0;
    force dut.capo_applied=0;
    repeat(5)@(negedge clk);rst_n=1;repeat(1000)@(negedge clk);
    for(model_id=0;model_id<4;model_id=model_id+1)begin
      force dut.voice_model=model_id;
      for(poly_id=0;poly_id<2;poly_id=poly_id+1)begin
        force dut.performance_poly=poly_id;
        for(channel_id=0;channel_id<4;channel_id=channel_id+1)begin
          for(fret=0;fret<=18;fret=fret+1)begin
            frets=0;
            case(channel_id)
              0:frets[15+:5]=fret;
              1:frets[5+:5]=fret;
              2:frets[10+:5]=fret;
              3:frets[0+:5]=fret;
            endcase
            expected[channel_id]=base_note[channel_id]+fret;
            pluck(1<<channel_id,1);
          end
        end
      end
    end
    model_id=0;force dut.voice_model=0;force dut.performance_poly=1;
    frets={5'd3,5'd7,5'd11,5'd16};
    expected[0]=10;expected[1]=15;expected[2]=7;expected[3]=25;
    pluck(15,4);pluck(6,2);pluck(7,3);
    $display("PASS 608 single-string plucks, all 72 frets, open strings, four timbres, lock/poly, unequal-fret 2/3/4 chords");
    $finish;
  end
endmodule
