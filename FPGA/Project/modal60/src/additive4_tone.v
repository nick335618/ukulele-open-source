// Calibrated MATLAB modal string/body model, streaming DF1 in Q24.
// One modal update starts per clock: 12*60 string modes and two 8-mode body
// state banks (for old/new timbre tails) fit the 1024-clock audio frame.
// Absolute ROM paths are generated separately for each standalone project.
`include "modal_config.vh"
module additive4_tone #(
    parameter GAIN_W=8, NV_LOCAL=12, NOTE_BITS=6,
    parameter ENV_INIT=16384, RAMP_N=24, DECAY_R=8, NOISE_SH=20, ACT_TH=2048
) (
    input wire clk, rst_n, sample_tick, trig_valid,
    input wire [3:0] trig_voice,
    input wire [NOTE_BITS-1:0] trig_note,
    // 3-bit per-note playing force.  Level 7 is the legacy/full excitation.
    // The force only scales the initial modal seed; decay, pitch and both
    // calibrated timbre banks remain unchanged.
    input wire [2:0] trig_velocity,
    input wire [1:0] trig_model,
    input wire trig_send,
    input wire [2:0] trig_string,send_route,
    input wire [GAIN_W-1:0] gain,
    input wire [7:0] trig_envelope,
    input wire [18:0] pitch_mul,
    input wire [16:0] capo_mul,
    input wire [3:0] pitch_voice,
    input wire glide_tail_damp,
    input wire tone_we,
    input wire [1:0] tone_wsel,
    input wire [8:0] tone_waddr,
    input wire [31:0] tone_wdata,
    input wire [5:0] pt_k,
    output reg signed [17:0] sample_out,
    output reg signed [17:0] sample_send,
    output reg [NV_LOCAL-1:0] voice_active,
    output wire busy,
    output reg [15:0] dbg_env
) /* synthesis syn_srlstyle = "registers" */;
    localparam K=`MODAL_K, DEPTH=`MODAL_ROM_DEPTH;
    localparam TOTAL=NV_LOCAL*K, SLOTS=TOTAL+16;
    localparam SW=$clog2(SLOTS), AW=$clog2(DEPTH), STATE_DEPTH=1<<SW;
    localparam Q=24, PIPE=9;
    (* syn_ramstyle="block_ram" *) reg [31:0] inc_mem[0:DEPTH-1];
    (* syn_ramstyle="block_ram" *) reg [23:0] rho_mem[0:DEPTH-1];
    (* syn_ramstyle="block_ram" *) reg signed [24:0] a2_mem[0:DEPTH-1];
    (* syn_ramstyle="block_ram" *) reg [23:0] g_mem[0:DEPTH-1];
    (* syn_ramstyle="block_ram" *) reg [24:0] cos_mem[0:2047];
    (* syn_ramstyle="block_ram" *) reg [13:0] cos_step_mem[0:2047];
    // Keep both attack tables in one contiguous 2048x16 RAM.  Some Gowin
    // Education builds map the second independent 1024x16 ROM to LUT4 even
    // with syn_ramstyle=block_ram; the bank bit below preserves the original
    // table contents while allowing one BSRAM inference.
    (* syn_ramstyle="block_ram" *) reg [15:0] attack_mem[0:2047];
    reg [24:0] drive_mem[0:127];
    (* syn_ramstyle="block_ram" *) reg signed [27:0] s1_mem[0:STATE_DEPTH-1];
    (* syn_ramstyle="block_ram" *) reg signed [27:0] s2_mem[0:STATE_DEPTH-1];
    initial begin
        $readmemh({`MODAL_DIR,"/inc.hex"},inc_mem);
        $readmemh({`MODAL_DIR,"/rho.hex"},rho_mem);
        $readmemh({`MODAL_DIR,"/a2.hex"},a2_mem);
        $readmemh({`MODAL_DIR,"/g.hex"},g_mem);
        $readmemh({`MODAL_DIR,"/cos.hex"},cos_mem);
        $readmemh({`MODAL_DIR,"/cos_step.hex"},cos_step_mem);
        $readmemh({`MODAL_DIR,"/attack.hex"},attack_mem, 0, 1023);
        $readmemh({`MODAL_DIR,"/attack_guitar.hex"},attack_mem, 1024, 2047);
        $readmemh({`MODAL_DIR,"/drive.hex"},drive_mem);
    end

    reg [SW-1:0] clear_addr;
    reg init_done;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin clear_addr<=0; init_done<=0; end
        else if(!init_done) begin
            if(clear_addr==SLOTS-1) init_done<=1;
            else clear_addr<=clear_addr+1'b1;
        end
    end

    reg [NOTE_BITS-1:0] note[0:NV_LOCAL-1], note_s[0:NV_LOCAL-1];
    reg [2:0] velocity[0:NV_LOCAL-1], velocity_s[0:NV_LOCAL-1];
    reg [NV_LOCAL-1:0] pending, rearm_s;
    reg [12:0] age[0:NV_LOCAL-1], age_s[0:NV_LOCAL-1];
    reg [1:0] model[0:NV_LOCAL-1],model_s[0:NV_LOCAL-1];
    reg [4:0] envelope[0:NV_LOCAL-1],envelope_s[0:NV_LOCAL-1];
    wire [2:0] limited_velocity=trig_velocity>trig_envelope[7:5]?trig_envelope[7:5]:trig_velocity;
    reg [2:0] string_owner[0:NV_LOCAL-1],string_s[0:NV_LOCAL-1];
    reg [2:0] route_s;
    
    reg [18:0] pitch_s;
    reg [16:0] capo_s;
    reg [3:0] owner_s;
    reg glide_damp_s;
    integer i;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            pending<=0; rearm_s<=0;send_voice<=0;send_s<=0;
            pitch_s<=32768; capo_s<=32768; owner_s<=0;route_s<=0;glide_damp_s<=0;
            for(i=0;i<NV_LOCAL;i=i+1) begin
                note[i]<=0; note_s[i]<=0;model[i]<=0;model_s[i]<=0;
                velocity[i]<=3'd7; velocity_s[i]<=3'd7;
                age[i]<=8191; age_s[i]<=8191;envelope[i]<=5'd11;envelope_s[i]<=5'd11;
                string_owner[i]<=4;string_s[i]<=4;
            end
        end else begin
            if(trig_valid && trig_voice<NV_LOCAL) begin
                note[trig_voice]<=trig_note;
                velocity[trig_voice]<=limited_velocity;model[trig_voice]<=trig_model;send_voice[trig_voice]<=trig_send;
                string_owner[trig_voice]<=trig_string;envelope[trig_voice]<=trig_envelope[4:0];
                pending[trig_voice]<=1;
            end
            if(sample_tick && init_done) begin
                rearm_s<=pending | ((trig_valid && trig_voice<NV_LOCAL) ? (1'b1<<trig_voice) : 0);
                pending<=0;
                send_s<=send_voice;route_s<=send_route;
                if(trig_valid&&trig_voice<NV_LOCAL)send_s[trig_voice]<=trig_send;
                pitch_s<=pitch_mul; capo_s<=capo_mul; owner_s<=pitch_voice;glide_damp_s<=(glide_tail_damp===1'b1);
                for(i=0;i<NV_LOCAL;i=i+1) begin
                    note_s[i]<=note[i];model_s[i]<=model[i];
                    string_s[i]<=string_owner[i];envelope_s[i]<=envelope[i];
                    velocity_s[i]<=velocity[i];
                    if(pending[i] || (trig_valid && trig_voice==i)) begin
                        age[i]<=1; age_s[i]<=0;
                    end else begin
                        age_s[i]<=age[i];
                        if(age[i]!=8191) age[i]<=age[i]+1'b1;
                    end
                end
                if(trig_valid && trig_voice<NV_LOCAL) begin
                    note_s[trig_voice]<=trig_note;model_s[trig_voice]<=trig_model;
                    string_s[trig_voice]<=trig_string;envelope_s[trig_voice]<=trig_envelope[4:0];
                    velocity_s[trig_voice]<=limited_velocity;
                end
            end
        end
    end

    reg [2:0] fsm;
    reg [NV_LOCAL-1:0] send_voice,send_s;
    reg ramp_send,finished_send;
    reg signed [39:0] send_acc;
    reg [2:0] send_filter_route;
    reg signed [31:0] send_previous_x;reg signed [35:0] send_previous_y;
    wire signed [35:0] send_dc=($signed(pcm)<<<8)-($signed(send_previous_x)<<<8)+send_previous_y-(send_previous_y>>>8);
    assign busy=fsm!=0;
    reg [SW-1:0] read_slot;
    reg [3:0] read_voice;
    reg [5:0] read_mode;
    reg [3:0] body_mode;
    wire bank_s=model_s[read_voice]==1;
    reg [5:0] wait_count;
    reg overrun;
    wire stream=(fsm==1 || fsm==3) && init_done;
    wire body_read=fsm==3;
    // Guitar B5–D#6 reuse the matching guitar resonances one octave below.
    // The note number is kept for pluck feedback and absolute glide targets.
    wire [5:0] note_limit=bank_s ? 37 : 27;
    wire [5:0] read_key=(note_s[read_voice]>note_limit) ? note_limit : note_s[read_voice];
    wire upper_guitar=bank_s && read_key>32;
    wire [5:0] read_note=upper_guitar ? read_key-6'd12 : read_key;
    // Four coefficient slots per electronic voice share the spare depth in
    // the original ROMs. Inactive slots fetch a zero row, clearing old modes
    // on voice reuse without changing the 60-slot scheduling cadence.
    wire [8:0] extra_index={1'b0,read_note[4:0],model_s[read_voice][0],read_mode[1:0]};
    wire [AW-1:0] extra_addr=read_mode<4 ? 12'd3840+extra_index : 12'd4064;
    wire [AW-1:0] table_addr=body_read ? (`MODAL_BODY_BASE+body_mode) :
        model_s[read_voice][1] ? extra_addr : ((bank_s ? 28*K:0)+read_note*K+read_mode);

    // Tags and state align with the frequency/coefficient pipeline below.
    reg [PIPE-1:0] valid_p, body_p, seed_p, reset_p;
    reg [SW-1:0] slot_p[0:PIPE-1];
    reg [3:0] voice_p[0:PIPE-1];
    reg [5:0] mode_p[0:PIPE-1];
    reg signed [27:0] state1_r,state2_r,previous1_r;
    // Delay narrow ROM addresses, then fetch each coefficient at its
    // consumption stage. Same registered RAM reads / exact coefficients,
    // replacing 24/25-bit multi-stage coefficient shift registers.
    reg [AW-1:0] coef_addr[0:6];
    reg [23:0] rho_r,g_r;
    reg signed [24:0] a2_r;
    reg [31:0] inc_q;
    reg signed [39:0] tone_acc,drive_acc,drive_guitar,body_acc,voice_acc;
    integer p;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin valid_p<=0; body_p<=0; seed_p<=0; reset_p<=0; end
        else begin
            valid_p<={valid_p[PIPE-2:0],stream};
            body_p<={body_p[PIPE-2:0],body_read};
            seed_p<={seed_p[PIPE-2:0],!body_read && rearm_s[read_voice]};
            reset_p<=0; // Per-voice model snapshots preserve both banks' tails.
        end
    end
    // RAM arrays have no asynchronous resets. Only valid tags reset.
    always @(posedge clk) begin
        if(stream) begin
            slot_p[0]<=read_slot; voice_p[0]<=read_voice; mode_p[0]<=read_mode;
            effective_pitch_q<=effective_pitch;inc_q<=inc_mem[table_addr]; coef_addr[0]<=table_addr;
        end
        for(p=1;p<PIPE;p=p+1) begin
            slot_p[p]<=slot_p[p-1]; voice_p[p]<=voice_p[p-1]; mode_p[p]<=mode_p[p-1];
        end
        for(p=1;p<7;p=p+1)coef_addr[p]<=coef_addr[p-1];
        state1_r<=s1_mem[slot_p[6]];state2_r<=s2_mem[slot_p[6]];previous1_r<=state1_r;
        rho_r<=rho_mem[coef_addr[4]];
        a2_r<=a2_mem[coef_addr[6]];g_r<=g_mem[coef_addr[6]];
    end

    // Q32 frequency -> pitch ratio -> interpolated quarter cosine -> a1.
    // Q24 coefficient accuracy supports long decays without 16-bit pole drift.
    reg [50:0] freq_product;
    reg [18:0] effective_pitch_q;
    wire [18:0] base_pitch=read_voice!=owner_s ? {2'd0,capo_s} : pitch_s;
    wire [18:0] effective_pitch=body_read ? 19'd32768 : base_pitch;
    // Apply the octave to the full product before selecting Q32. Include
    // the discarded high bits in the anti-alias check in either mode.
    reg octave_inc, octave_freq;
    always @(posedge clk) begin
        octave_inc <= !body_read && upper_guitar;
        octave_freq <= octave_inc;
    end
    wire [31:0] freq_scaled=octave_freq ? freq_product[45:14] : freq_product[46:15];
    wire freq_overflow=(|freq_product[50:47]) || (octave_freq && freq_product[46]);
    wire [29:0] folded=freq_scaled[30] ? ~freq_scaled[29:0] : freq_scaled[29:0];
    reg [10:0] cos_addr;
    reg [11:0] fraction_q, fraction_d;
    reg [6:0] negative_p, band_p;
    reg [24:0] cos_q0, cos_base_d;
    reg [13:0] cos_step_q;
    reg [26:0] interpolation_product;
    // Both ROMs use exactly the same registered address, with no adjacent
    // address or endpoint mux in the RAM read. This avoids the demonstrated
    // Gowin mismatch for the original two-port/conditional cosine ROM.
    wire [26:0] fraction_product=cos_step_q*fraction_d;
    wire [26:0] interpolation_rounded=interpolation_product+27'd4095;
    wire [24:0] interpolated=cos_base_d-(interpolation_rounded>>12);
    reg signed [25:0] cosine_r;
    reg signed [50:0] coefficient_product;
    wire signed [50:0] coefficient_shifted=coefficient_product>>>23;
    reg signed [25:0] a1_r;
    reg signed [53:0] product1;
    reg signed [52:0] product2;
    reg signed [56:0] excitation_product;
    always @(posedge clk) begin
        freq_product<=inc_q*effective_pitch_q;
        cos_addr<=folded[29:19]; fraction_q<=folded[18:7];
        negative_p<={negative_p[5:0],freq_scaled[31]^freq_scaled[30]};
        // Reject frequencies above one sample/clock before the Q32 narrowing:
        // large slides must not wrap high modes back into the audible band.
        band_p<={band_p[5:0],!freq_overflow && freq_scaled!=0 && freq_scaled<32'd1932735283};
        cos_q0<=cos_mem[cos_addr];
        cos_step_q<=cos_step_mem[cos_addr];
        fraction_d<=fraction_q;
        interpolation_product<=fraction_product; cos_base_d<=cos_q0;
        cosine_r<=negative_p[2] ? -$signed({1'b0,interpolated}) : $signed({1'b0,interpolated});
        coefficient_product<=cosine_r*$signed({1'b0,rho_r});
        a1_r<=coefficient_shifted[25:0];
        product1<=a1_r*state1_r; product2<=a2_r*state2_r;
        excitation_product<=$signed({1'b0,g_r})*$signed(slot_p[7][3] ? drive_guitar[31:0] : drive_acc[31:0]);
    end

    wire signed [56:0] recurrence=$signed(product1)+$signed(product2);
    wire signed [56:0] recurrence_rounded=(recurrence+57'sd8388608)>>>24;
    wire signed [56:0] excitation_rounded=(excitation_product+57'sd8388608)>>>24;
    wire signed [56:0] candidate=(reset_p[8] ? 57'sd0 : recurrence_rounded)+
                                (body_p[8] ? excitation_rounded : 57'sd0);
    // A shift/add curve avoids another DSP multiplier in the already-full
    // device.  The default level 7 is exactly the old excitation value.
    function signed [27:0] scale_excitation;
        input signed [27:0] base;
        input [2:0] level;
        begin
            case(level)
                // Wider temporal-velocity expression; full force is bit-exact
                // legacy excitation. Models, modal counts and decay unchanged.
                3'd0: scale_excitation = base >>> 2;                    // 4/16
                3'd1: scale_excitation = (base >>> 2) + (base >>> 4);   // 5/16
                3'd2: scale_excitation = (base >>> 2) + (base >>> 3);   // 6/16
                3'd3: scale_excitation = (base >>> 1) - (base >>> 4);   // 7/16
                3'd4: scale_excitation = base >>> 1;                    // 8/16
                3'd5: scale_excitation = (base >>> 1) + (base >>> 3);   // 10/16
                3'd6: scale_excitation = base - (base >>> 2);            // 12/16
                default: scale_excitation = base;                       // 1
            endcase
        end
    endfunction
    // Scale one stage BEFORE recurrence writeback. Coefficient reads and
    // voice tags stay aligned
    // with seed_p[8] without extending the original seed -> magnitude path.
    // This also keeps playing-force arithmetic away from voice-active/env CE.
    reg signed [27:0] seeded_excitation;
    always @(posedge clk) begin
        seeded_excitation <= scale_excitation($signed({4'b0,g_r}),
                                               velocity_s[voice_p[7]]);
    end
    // Separate the DF1 add/round/excitation from saturation, state writeback
    // and accumulation. Preserve every bit of candidate and all matching
    // tags; the frame's existing drain waits cover this extra 20-ns stage.
    // This prevents placement changes in the LED logic from pushing the
    // DSP -> three adders -> magnitude/accumulator path beyond 50 MHz.
    reg signed [56:0] candidate_q;
    reg signed [27:0] seed_value_q, previous_state_q;
    reg [SW-1:0] slot_q;
    reg [3:0] voice_q;
    reg [5:0] mode_q;
    reg valid_q, body_q, seed_q, reset_q, band_q;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) valid_q<=0;
        else valid_q<=valid_p[8];
    end
    always @(posedge clk) begin
        candidate_q<=candidate;
        seed_value_q<=seeded_excitation; previous_state_q<=previous1_r;
        slot_q<=slot_p[8]; voice_q<=voice_p[8]; mode_q<=mode_p[8];
        body_q<=body_p[8]; seed_q<=seed_p[8]; reset_q<=reset_p[8];
        band_q<=band_p[6];
    end
    wire signed [27:0] saturated=(candidate_q>57'sd134217727) ? 28'sd134217727 :
                               ((candidate_q < -57'sd134217728) ? -28'sd134217728 : candidate_q[27:0]);
    // Scale both history samples equally while pitch is moving. This adds a
    // release to the existing modal state, without rearming or changing pitch.
    wire damp_slide=glide_damp_s && voice_q==owner_s && !body_q;
    wire signed [27:0] damped=saturated-(saturated>>>17);
    wire signed [27:0] damped_previous=previous_state_q-(previous_state_q>>>17);
    wire signed [27:0] next_state=(!body_q && seed_q) ? seed_value_q :
                                 (!band_q ? 28'sd0 : damp_slide ? damped : saturated);
    wire [27:0] magnitude=next_state[27] ? -next_state : next_state;
    always @(posedge clk) begin
        if(!init_done) begin s1_mem[clear_addr]<=0; s2_mem[clear_addr]<=0; end
        else if(valid_q) begin
            s1_mem[slot_q]<=next_state;
            s2_mem[slot_q]<= (seed_q || reset_q || !band_q) ? 28'sd0 : damp_slide ? damped_previous : previous_state_q;
        end
    end

    // Diagnostic/voice-allocation measurements are not in the audio math.
    // Register their input to break recurrence -> abs -> compare -> CE;
    // the audio accumulator and modal state writes keep their original timing.
    reg [27:0] magnitude_r;
    reg [3:0] magnitude_voice_r;
    reg magnitude_valid_r;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            magnitude_r<=0; magnitude_voice_r<=0; magnitude_valid_r<=0;
        end else begin
            magnitude_r<=magnitude;
            magnitude_voice_r<=voice_q;
            magnitude_valid_r<=valid_q && !body_q;
        end
    end
    reg [NV_LOCAL-1:0] active_work;
    reg [15:0] env_work;
    wire signed [39:0] voice_sum=(mode_q==0) ? $signed(next_state) : voice_acc+$signed(next_state);
    reg signed [31:0] voice_sum_r;
    reg [15:0] attack_q;
    reg [24:0] drive_q, drive_d;
    reg voice_valid,ramp_valid,drive_valid;
    reg finished_bank,drive_bank,drive_bank_d;
    reg signed [48:0] ramp_product;
    wire signed [48:0] ramp_shifted=(ramp_product+49'sd16384)>>>15;
    reg signed [57:0] drive_product;
    wire signed [57:0] drive_shifted=(drive_product+58'sd8388608)>>>24;
    wire finish_bank=model_s[voice_q]==1;
    wire [5:0] finish_limit=finish_bank ? 37:27;
    wire [5:0] finish_key=(note_s[voice_q]>finish_limit) ? finish_limit : note_s[voice_q];
    wire [5:0] finished_note=finish_bank && finish_key>32 ? finish_key-6'd12 : finish_key;
    wire extra_voice=model_s[voice_q][1];
    wire [12:0] finish_age=age_s[voice_q];
    // Keep the original attack ROM, per-mode decay and resonator state.
    // The settings affect the table traversal and the existing body send.
    wire [4:0] finish_envelope=envelope_s[voice_q];
    reg [11:0] attack_age_a,attack_age_b;
    reg [12:0] adjusted_age;
    // Beyond 4095 samples even the slowest ramp has reached the ROM endpoint.
    // Split the address scaling from the ROM read; this uses no DSP block.
    wire [11:0] attack_age=finish_age[12]?12'd4095:finish_age[11:0];
    wire [11:0] double_age=(|attack_age[11:10])?12'd4095:{attack_age[10:0],1'b0};
    always @(*) begin
        attack_age_a=attack_age;
        attack_age_b=0;
        case(finish_envelope[2:0])
            0: attack_age_a=double_age;
            1: attack_age_b=attack_age>>1;
            2: attack_age_b=attack_age>>2;
            4: begin attack_age_a=attack_age>>1;attack_age_b=attack_age>>2;end
            5: attack_age_a=attack_age>>1;
            6: begin attack_age_a=attack_age>>2;attack_age_b=attack_age>>3;end
            7: attack_age_a=attack_age>>2;
            default: ;
        endcase
    end
    always @(posedge clk) begin
        if(valid_q && !body_q && mode_q==K-2)
            adjusted_age<={1'b0,attack_age_a}+{1'b0,attack_age_b};
    end
    wire [9:0] attack_index=extra_voice ?
        (|adjusted_age[12:8] ? 10'd1023 : {adjusted_age[7:0],2'b00}) :
        (|adjusted_age[12:10] ? 10'd1023 : adjusted_age[9:0]);
    reg [1:0] body_level;
    reg [24:0] selected_drive;
    always @(*) begin
        case(body_level)
            0: selected_drive=drive_q>>1;
            2: selected_drive=drive_q+(drive_q>>1);
            3: selected_drive=drive_q<<1;
            default: selected_drive=drive_q;
        endcase
    end
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            voice_acc<=0;tone_acc<=0;drive_acc<=0;drive_guitar<=0;body_acc<=0;send_acc<=0;
            active_work<=0; env_work<=0; voice_valid<=0; ramp_valid<=0; drive_valid<=0;
        end else begin
            voice_valid<=valid_q && !body_q && mode_q==K-1;
            ramp_valid<=voice_valid; drive_valid<=ramp_valid;
            if(valid_q && !body_q) begin
                voice_acc<=voice_sum;

                if(mode_q==K-1) begin
                    voice_sum_r<=voice_sum[31:0];
                    attack_q<=attack_mem[{finish_bank,attack_index}];finished_bank<=finish_bank;
                    finished_send<=route_s==1 ? send_s[voice_q] :
                        (route_s>=2 && route_s<=5 && string_s[voice_q]==route_s-3'd2);
                    body_level<=finish_envelope[4:3];
                    drive_q<=extra_voice ? 25'd0 : drive_mem[{finish_bank,finished_note}];
                end
            end
            if(magnitude_valid_r) begin
                if(magnitude_r>ACT_TH) active_work[magnitude_voice_r]<=1;
                if(magnitude_r[27:12]>env_work) env_work<=magnitude_r[27:12];
            end
            if(voice_valid) begin
                ramp_product<=voice_sum_r*$signed({1'b0,attack_q});drive_d<=selected_drive;drive_bank<=finished_bank;ramp_send<=finished_send;
            end
            if(ramp_valid) begin
                tone_acc<=tone_acc+ramp_shifted;
                if(ramp_send)send_acc<=send_acc+ramp_shifted;drive_bank_d<=drive_bank;
                drive_product<=$signed(ramp_shifted[31:0])*$signed({1'b0,drive_d});
            end
            if(drive_valid)begin if(drive_bank_d)drive_guitar<=drive_guitar+drive_shifted;else drive_acc<=drive_acc+drive_shifted;end
            if(valid_q && body_q) body_acc<=body_acc+$signed(next_state);
            if(sample_tick && init_done) begin
                voice_acc<=0;tone_acc<=0;drive_acc<=0;drive_guitar<=0;body_acc<=0;send_acc<=0;
                active_work<=0; env_work<=0;
            end
        end
    end

    reg signed [48:0] master_product;
    // Mux before the multiplier so both output buses use one physical DSP
    // path. Two multiply expressions in separate FSM arms infer two units.
    wire signed [39:0] master_operand=(fsm==6) ? send_acc : tone_acc+body_acc;
    wire signed [48:0] master_result=master_operand*$signed({1'b0,gain});
    wire signed [48:0] scaled_output=master_product>>>19; // Q24 * gain/256 * 8192
    wire signed [31:0] pcm=(scaled_output>49'sd131071) ? 32'sd131071 :
                            ((scaled_output < -49'sd131072) ? -32'sd131072 : scaled_output[31:0]);
    reg signed [31:0] previous_x;
    reg signed [35:0] previous_y;
    wire signed [35:0] dc_output=($signed(pcm)<<<8)-($signed(previous_x)<<<8)+previous_y-(previous_y>>>8);
    wire signed [35:0] dc_pcm=dc_output>>>8;
    always @(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            fsm<=0; read_slot<=0; read_voice<=0; read_mode<=0; body_mode<=0; wait_count<=0;
            overrun<=0; sample_out<=0; voice_active<=0; dbg_env<=0;
            previous_x<=0; previous_y<=0; master_product<=0;sample_send<=0;send_previous_x<=0;send_previous_y<=0;send_filter_route<=0;
        end else if(sample_tick && init_done) begin
            if(fsm!=0) overrun<=1;
            fsm<=1; read_slot<=0; read_voice<=0; read_mode<=0;
        end else case(fsm)
            1: begin
                if(read_slot==TOTAL-1) begin fsm<=2; wait_count<=0; end
                else begin
                    read_slot<=read_slot+1'b1;
                    if(read_mode==K-1) begin read_mode<=0; read_voice<=read_voice+1'b1; end
                    else read_mode<=read_mode+1'b1;
                end
            end
            2: if(wait_count==23) begin fsm<=3; read_slot<=TOTAL; body_mode<=0; read_voice<=0; end
               else wait_count<=wait_count+1'b1;
            3: if(body_mode==15) begin fsm<=4; wait_count<=0; end
               else begin body_mode<=body_mode+1'b1; read_slot<=read_slot+1'b1; end
            4: if(wait_count==15) fsm<=5;
               else wait_count<=wait_count+1'b1;
            5: begin master_product<=master_result; fsm<=6; end
            6: begin
                previous_x<=pcm; previous_y<=dc_output;
                sample_out<=(dc_pcm>36'sd131071) ? 18'sd131071 :
                            ((dc_pcm < -36'sd131072) ? -18'sd131072 : dc_pcm[17:0]);
                voice_active<=active_work; dbg_env<=env_work;
                master_product<=master_result;fsm<=7;
            end
            7:begin
                send_previous_x<=pcm;send_filter_route<=route_s;
                if(send_filter_route!=route_s)begin send_previous_y<=0;sample_send<=0;end
                else begin send_previous_y<=send_dc;
                sample_send<=(send_dc >>> 8) > 36'sd131071 ? 18'sd131071 : (send_dc >>> 8) < -36'sd131072 ? -18'sd131072 : send_dc[25:8];end fsm<=0;
            end
            default: ;
        endcase
    end
endmodule
