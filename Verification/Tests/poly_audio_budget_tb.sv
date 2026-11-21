`timescale 1ns/1ps
module poly_audio_budget_tb;
    reg clk=0,rst_n=0,tick=0,trig=0,damp=0;
    always #10 clk=~clk;
    reg [3:0] voice=0;
    reg [5:0] note=0;
    reg [1:0] model=0;
    reg [18:0] pitch=32768;
    wire signed [17:0] sample;
    wire [11:0] active;
    wire busy;
    integer i,frame,frames_with_audio=0,damped_slots=0;
    additive4_tone #(.NV_LOCAL(12)) dut(
       .clk(clk),.rst_n(rst_n),.sample_tick(tick),.trig_valid(trig),.trig_voice(voice),.trig_note(note),
       .trig_velocity(3'd7),.trig_model(model),.trig_envelope(8'd235),.gain(8'd51),
       .pitch_mul(pitch),.capo_mul(17'd32768),.pitch_voice(4'd3),.glide_tail_damp(damp),
       .tone_we(1'b0),.tone_wsel(2'd0),.tone_waddr(9'd0),.tone_wdata(32'd0),.pt_k(6'd18),
       .trig_send(1'b0),.trig_string(3'd0),.send_route(3'd0),
       .sample_out(sample),.voice_active(active),.busy(busy));
    always @(posedge clk) if(dut.valid_q && dut.damp_slide) begin
       if(dut.body_q || dut.voice_q!==4'd3)$fatal(1,"slide damping leaked across voices");
       damped_slots=damped_slots+1;
    end
    initial begin
       repeat(3)@(negedge clk);rst_n=1;repeat(1100)@(negedge clk);
       for(i=0;i<12;i=i+1)begin
          voice=i;model=i%4;note=model==1?6'd37:i;trig=1;@(negedge clk);trig=0;
       end
       for(frame=0;frame<512;frame=frame+1)begin
          if(frame>=96)begin damp=1;pitch=32768+frame*16;end
          tick=1;@(negedge clk);tick=0;repeat(1023)@(negedge clk);
          if(busy||dut.overrun||(^sample)===1'bx)$fatal(1,"12-voice sample deadline at %0d",frame);
          if(sample!=0)frames_with_audio=frames_with_audio+1;
          if(frame==32 && active!==12'hfff)$fatal(1,"expected all 12 voices active, got %h",active);
       end
       if(frames_with_audio<400 || damped_slots!=60*(512-96))
          $fatal(1,"missing samples or damping slots: %0d, %0d",frames_with_audio,damped_slots);
       $display("PASS 12 mixed-model voices, 512 samples in 1024-cycle frames, damping only in owner voice");
       $finish;
    end
endmodule
