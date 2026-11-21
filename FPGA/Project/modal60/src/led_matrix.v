// ============================================================================
// led_matrix.v - LP5860 4 x 18 LED matrix renderer
// ----------------------------------------------------------------------------
// The schematic uses one LP5860 with four populated scan outputs:
//   physical columns (left -> right): SW8, SW9, SW0, SW1
//   physical rows (top -> bottom):     CS13,CS14,CS15,CS16,CS17,CS12..CS0
//
// The LP5860 shares SCLK/MOSI/MISO with the AD7147 SPI bus.  This block only
// drives the shared pins while bus_lock is high. Revised PCB CN1-7 EN#
// permits the top level to deselect ALL AD7147 chips during LED traffic.
// Byte parking is retained only as an optional legacy test parameter;
// production uses EN# isolation and never toggles addresses per LED byte.
//
// SPI mode 0 (sample on rising SCLK), data-refresh Mode 1 (8-bit PWM,
// immediate update, no VSYNC). Revised PCB ties the VSYNC input to GND.
// The device scans the contiguous SW0..SW9 window so the non-contiguous SW8
// and SW9 columns are active; SW2..SW7 are unpopulated.  PWM is updated as
// four independent bursts, one for each populated scan output, so the address
// gaps for SW0/SW1/SW8/SW9 never need FPGA RAM.
//
// Modes:
//   0 off                    1 constant
//   2 all breathe             3 progressive breathe (axis selects row/column)
//   4 dragon/bouncing tail    5 travelling ripple
//   6 strum-follow physical fret flash (actual audio submission)
//   7 polyphonic fret guide (one compact five-bit fret per string)
// ============================================================================
module led_matrix #(
    parameter integer CAPO_ENABLED = 0,
    parameter integer CLK_HZ       = 50_000_000,
    parameter integer SPI_HZ       = 2_000_000,
    parameter integer FRAME_HZ    = 50,
    parameter integer FRAME_PIXELS = 18,
    parameter integer TOUCH_PARK_PER_BYTE = 0,
    parameter integer CONFIG_CHECK_MS = 500,
    parameter integer EVENT_HOLD_FRAMES = 20
) (
    input  wire       clk,
    input  wire       rst_n,
    // Shared touch SPI bus arbitration.
    input  wire       bus_idle,
    input wire sleep_req,
    output wire asleep,
    input  wire       spi_miso,

    // One-cycle configuration write from the ESP32 command parser.
    input  wire       cfg_we,
    input  wire [3:0] cfg_mode,
    input  wire       cfg_axis,       // 0 = CS rows, 1 = SW columns
    input  wire [7:0] cfg_speed,
    input  wire [7:0] cfg_level,
    input  wire [7:0] cfg_flags,
    // Atomic four-string guide: 20 bits replace a 72-byte framebuffer.
    // ESP32 owns musical timing; FPGA maps physical LED boundaries.
    input wire [6:0] shared_map_addr,output wire [7:0] shared_map_data,
    input wire profile_cmd_we,input wire [7:0] profile_op,profile_index,profile_d0,profile_d1,profile_d2,
    input wire [15:0] profile_cmd_token,
    output wire [111:0] profile_status,
    output wire profile_select,output wire [7:0] profile_requested_level,
    input  wire       guide_we,
    input  wire [19:0] guide_frets, // G/C/E/A five-bit frets; 0=open, 31=off
    input  wire [31:0] guide_levels,
    input wire [3:0] capo_fret,
    input  wire [4:0] event_fret,     // actual submitted fret, 0..18; other = off
    input  wire [3:0] event_bits,     // bit0..3 = G/E/C/A optical events

    output reg        spi_sck,
    output reg        spi_mosi,
    output reg        cs_n,
    output reg        vsync,
    output reg        touch_park_phase,
    output wire       bus_lock,
    output reg        initialized,
    output wire       config_ok,
    output reg  [7:0] read_chip_en,
    output reg  [7:0] read_dev_initial,
    output reg  [7:0] read_config3,
    output reg  [7:0] read_global,
    output reg [23:0] sent_chip_en,
    output reg [23:0] sent_dev_initial,
    output wire [7:0] diag_flags,
    output reg [7:0] control_errors,pwm_errors,last_fault,
    output reg [1:0] last_bad_col,
    output reg [15:0] write_crc,read_crc
);

    localparam integer HALF_DIV_RAW = CLK_HZ / (SPI_HZ * 2);
    localparam integer HALF_DIV = (HALF_DIV_RAW < 1) ? 1 : HALF_DIV_RAW;
    localparam integer FRAME_DIV_RAW = CLK_HZ / FRAME_HZ;
    localparam integer FRAME_DIV = (FRAME_DIV_RAW < 1) ? 1 : FRAME_DIV_RAW;
    // TI specifies tCHIP_EN >= 100 us and SS inactive >= 50 ns. Use 1 ms
    // after enabling and at least a half SPI period between transactions.
    // The startup wait releases the bus so touch calibration can continue.
    localparam integer ENABLE_WAIT_RAW = CLK_HZ / 1000;
    localparam integer ENABLE_WAIT_CLKS = (ENABLE_WAIT_RAW < 1) ? 1 : ENABLE_WAIT_RAW;
    localparam integer WAIT_W = $clog2(ENABLE_WAIT_CLKS + 1);
    localparam integer GAP_MIN_CLKS = (CLK_HZ + 19_999_999) / 20_000_000;
    localparam integer GAP_CLKS = (HALF_DIV > GAP_MIN_CLKS) ? HALF_DIV : GAP_MIN_CLKS;

    // LP5860 SPI write transaction kinds.
    localparam [2:0] TX_CFG   = 3'd0;
    localparam [2:0] TX_ONOFF = 3'd1;
    localparam [2:0] TX_DC    = 3'd2;
    localparam [2:0] TX_PWM   = 3'd3;
    localparam [2:0] TX_READ  = 3'd4;

    localparam [2:0] S_IDLE  = 3'd0;
    localparam [2:0] S_PREP  = 3'd1;
    localparam [2:0] S_SHIFT = 3'd2;
    localparam [2:0] S_DONE  = 3'd3;
    localparam [2:0] S_GAP   = 3'd4;
    localparam [2:0] S_HOLD  = 3'd5;
    localparam [2:0] S_PROFILE=3'd6;

    reg [2:0] state;
    reg [2:0] tx_kind;
    localparam TX_SLEEP=3'd5,TX_WAKE=3'd6;
    reg sleep_applied;
    // First attempt completion is sticky so failed LED readback cannot hold
    // touch in reset. Readback failure retries with bounded bus duty; healthy
    // devices are checked periodically and restored after a power reset.
    reg init_pending;
    reg control_bad,control_valid,data_ok;
    reg [1:0] pwm_retry;
    localparam TX_VERIFY=3'd7;
    function [15:0] crc_bit(input [15:0] c,input b);
      begin crc_bit={c[14:0],1'b0} ^ ((c[15]^b) ? 16'h1021 : 16'd0);end
    endfunction
    assign diag_flags={2'd0,sleep_applied,init_pending,bus_lock,data_ok,control_valid,initialized};
    localparam integer CHECK_FRAMES_RAW = (FRAME_HZ*CONFIG_CHECK_MS+999)/1000;
    localparam integer CHECK_FRAMES = CHECK_FRAMES_RAW < 1 ? 1 : CHECK_FRAMES_RAW;
    localparam integer CHECK_W = $clog2(CHECK_FRAMES+1);
    reg [CHECK_W-1:0] check_count;
    reg [2:0] gap_next;
    reg [WAIT_W-1:0] enable_wait;
    reg [7:0] rx_byte;
    // LP5860 has a 10-bit register address A[9:0].  The first SPI byte is
    // A[9:2], so the PWM window starts at 0x200 without truncation.
    reg [9:0] tx_addr;
    reg [4:0] tx_line;       // physical column index while a burst is active
    reg [7:0] tx_data;       // one-byte payload for TX_CFG
    reg [5:0] tx_total;
    reg [5:0] tx_byte_idx;
    reg [2:0] tx_bit_idx;
    localparam HALF_COUNT_MAX=HALF_DIV>GAP_CLKS ? HALF_DIV : GAP_CLKS;
    localparam HALF_COUNT_RAW=$clog2(HALF_COUNT_MAX);
    localparam HALF_COUNT_W=HALF_COUNT_RAW<1 ? 1 : HALF_COUNT_RAW>16 ? 16 : HALF_COUNT_RAW;
    reg [HALF_COUNT_W-1:0] half_cnt;

    reg [4:0] init_step;
    reg [1:0] frame_col;
    localparam FRAME_W=FRAME_DIV<2 ? 1 : $clog2(FRAME_DIV);
    reg [FRAME_W-1:0] frame_cnt;
    reg [15:0] phase, phase_frame;
    reg        dirty;
    reg        frame_pending;

    // Configuration shadow/current frame.  Shadow can change while a burst is
    // in progress; frame_* is only sampled when the next frame starts.
    reg [3:0] mode_shadow, mode_frame;
    reg       axis_shadow, axis_frame;
    reg [7:0] speed_shadow, speed_frame;
    localparam integer CUSTOM_STEP_RAW=(65536+4*FRAME_HZ)/(8*FRAME_HZ);
    localparam [15:0] CUSTOM_STEP=CUSTOM_STEP_RAW<1?16'd1:CUSTOM_STEP_RAW;
    wire [15:0] phase_step=(speed_shadow==0)?16'd0:(16'd256+{7'd0,speed_shadow,1'b0});
    reg [7:0] level_shadow, level_frame;
    reg [7:0] flags_shadow, flags_frame;
    reg [19:0] guide_frets_shadow, guide_frets_frame;
    reg [31:0] guide_levels_shadow, guide_levels_frame;
    reg [3:0] capo_shadow,capo_frame;
    wire [4:0] active_capo=CAPO_ENABLED ? {1'b0,capo_frame} : 5'd0;
    reg [19:0] event_frets_shadow, event_frets_frame;
    // Independent expiry: another string must not prolong an old flash.
    localparam integer REFLASH_FRAMES=2; // 40 ms at production 50 Hz
    localparam integer EVENT_W=$clog2(EVENT_HOLD_FRAMES+REFLASH_FRAMES+1);
    reg [EVENT_W-1:0] event_left [0:3];
    integer ec;
    wire event_tick=(frame_cnt==FRAME_DIV-1);

    reg onoff_enabled; // Hardware Dot_onoff mask: off really disables every populated dot.
    reg mask_cycle; // Reuse the same shifter and CRC for custom per-dot masks.
    reg profile_prepare;
    reg [7:0] profile_current_pixel;
    wire [7:0] profile_next_pixel,profile_error,profile_cursor,profile_flags;
    wire [15:0] profile_crc,profile_token,profile_generation;
    wire profile_ready;
    wire [1:0] profile_calc_col;wire [4:0] profile_calc_row;
    wire [7:0] legacy_mult_pixel;
    wire legacy_request;
    wire [7:0] legacy_raw;
    reg [7:0] legacy_raw_q;
    always @(posedge clk) legacy_raw_q<=legacy_raw;
    wire [4:0] profile_next_cs=tx_byte_idx<1 ? 5'd0 : tx_byte_idx>=18 ? 5'd17 : tx_byte_idx[4:0]-1'b1;
    wire [6:0] profile_next_index=frame_col*18+row_pos(profile_next_cs);
    wire mask_read=mode_frame==8 && (tx_kind==TX_ONOFF || (tx_kind==TX_VERIFY && mask_cycle) || (tx_kind==TX_READ && init_step>=22 && init_step<26));
    wire [1:0] mask_col=init_pending ? (tx_kind==TX_ONOFF ? init_step[1:0] : init_step[1:0]-2'd2) : frame_col;
    wire [1:0] mask_byte=tx_byte_idx<1 ? 2'd0 : tx_byte_idx>=3 ? 2'd2 : tx_byte_idx[1:0]-1'b1;
    wire [3:0] mask_index={mask_col,2'b00}+mask_byte;
    wire profile_pixel_active=mode_frame==8 && (tx_kind==TX_PWM || tx_kind==TX_VERIFY || mask_read) && state!=S_PROFILE && state!=S_IDLE;
    wire profile_captured=mode_frame==8 && mode_shadow==8 && profile_ready && data_ok && profile_flags[4] && !profile_flags[3];
    assign profile_status={8'd0,8'd15,profile_generation,profile_token,profile_crc,
      {config_ok,sleep_applied,profile_captured,profile_flags[4:0]},
      {4'd0,mode_frame},level_frame,profile_cursor,profile_error,8'd2};
    led_profile #(.FRAME_HZ(FRAME_HZ)) u_profile(
      .clk(clk),.rst_n(rst_n),.map_addr(shared_map_addr),.map_data(shared_map_data),
      .cmd_we(profile_cmd_we),.op(profile_op),.index(profile_index),.d0(profile_d0),.d1(profile_d1),.d2(profile_d2),.cmd_token(profile_cmd_token),
      .cancel(cfg_we && cfg_mode!=8),.render_phase(phase_frame),.frame_tick(event_tick),.prepare(profile_prepare),.sleep_req(sleep_req),.pixel_active(profile_pixel_active),
      .custom_mode(1'b1),.legacy_multiply(1'b0),.legacy_value(legacy_raw),.legacy_level(level_frame),
      .legacy_request(legacy_request),.legacy_pixel(legacy_mult_pixel),.calc_col(profile_calc_col),.calc_row(profile_calc_row),
      .master_level(level_frame),.pixel_index(profile_next_index),.pixel_data(profile_next_pixel),.ready(profile_ready),
      .mask_select(mask_read),.mask_index(mask_index),
      .select_custom(profile_select),.requested_level(profile_requested_level),.error(profile_error),.cursor(profile_cursor),
      .active_crc(profile_crc),.active_token(profile_token),.generation(profile_generation),.flags(profile_flags));
    reg hold_for_touch;
    assign asleep=sleep_applied && state==S_IDLE && !hold_for_touch;
    wire active_shift = (state == S_PREP) || (state == S_SHIFT) ||
                         (state == S_DONE) || (state == S_GAP) || (state == S_HOLD);
    wire frame_due = initialized && !init_pending && frame_pending &&
                     (dirty || (mode_shadow >= 4'd2));
    // Reserve the shared bus in the same cycle in which S_IDLE accepts a new
    // transaction.  Without this look-ahead, the touch scanner could observe
    // bus_idle=1 on that edge and launch an AD7147 transfer alongside us.
    wire acquire_bus = bus_idle && !hold_for_touch &&
      (((state==S_IDLE) && (enable_wait==0) && ((sleep_req!=sleep_applied) || (!sleep_req && (check_count==0 || frame_due)))) ||
       ((state==S_PROFILE) && profile_ready && !profile_prepare));
    // Keep ownership for one extra cycle after CS rises if the touch scanner
    // started at the same clock edge as us.  This lets that transaction finish
    // before the mux returns the physical wires to the touch scanner.
    assign bus_lock = active_shift || hold_for_touch || acquire_bus;
    // initialized means the sequence finished; config_ok is based on actual
    // register readback. Floating MISO (0xFF) never reports configuration OK.
    wire readback_match = (read_chip_en == 8'h01) &&
                       (read_dev_initial == 8'h50) &&
                       (read_config3 == 8'h59) && (read_global == 8'hFF);
    assign config_ok = initialized && !sleep_applied && readback_match && control_valid && data_ok;

    // The populated LP5860 scan-line numbers are deliberately non-contiguous;
    // this is the electrical mapping in the supplied schematic.
    function [4:0] line_map;
        input [1:0] c;
        begin
            case (c)
                2'd0: line_map = 5'd8;  // SW8, leftmost column
                2'd1: line_map = 5'd9;  // SW9
                2'd2: line_map = 5'd0;  // SW0
                default: line_map = 5'd1; // SW1, rightmost column
            endcase
        end
    endfunction

    // Electrical CS index -> visual top-to-bottom row index from the schematic.
    function [4:0] row_pos;
        input [4:0] cs;
        begin
            case (cs)
                5'd13: row_pos = 5'd0;
                5'd14: row_pos = 5'd1;
                5'd15: row_pos = 5'd2;
                5'd16: row_pos = 5'd3;
                5'd17: row_pos = 5'd4;
                5'd12: row_pos = 5'd5;
                5'd11: row_pos = 5'd6;
                5'd10: row_pos = 5'd7;
                5'd9:  row_pos = 5'd8;
                5'd8:  row_pos = 5'd9;
                5'd7:  row_pos = 5'd10;
                5'd6:  row_pos = 5'd11;
                5'd5:  row_pos = 5'd12;
                5'd4:  row_pos = 5'd13;
                5'd3:  row_pos = 5'd14;
                5'd2:  row_pos = 5'd15;
                5'd1:  row_pos = 5'd16;
                default: row_pos = 5'd17; // CS0
            endcase
        end
    endfunction

    function [7:0] tri8;
        input [7:0] x;
        begin
            // 0..254..0 triangle.  The one-LSB asymmetry avoids a wide
            // subtractor and is invisible on the LED PWM output.
            tri8 = x[7] ? {~x[6:0], 1'b0} : {x[6:0], 1'b0};
        end
    endfunction

    function [7:0] mul_level;
        input [7:0] a;
        input [7:0] b;
        reg [15:0] p;
        begin
            p = a * b;
            mul_level = p[15:8];
        end
    endfunction

    // Pixel renderer.  It is evaluated only for one byte at a time while a
    // PWM burst is shifted, so no 72-byte framebuffer is needed in FPGA RAM.
    function [7:0] pixel_pwm;
        input [3:0] m;
        input       ax;
        input [7:0] lvl;
        input [15:0] ph;
        input [1:0] col;
        input [4:0] cs;
        input [3:0] ev;
        input [7:0] fl;
        input [19:0] gfrets;
        input [7:0] glvl;
        reg [4:0] rp;
        reg [4:0] fret;
        reg [7:0] wave;
        reg [7:0] wave2;
        reg [7:0] head;
        reg [7:0] travel;
        reg [9:0] scaled_head;
        reg [7:0] distance;
        begin
            rp = cs; // cache renderer receives an already visual row
            fret = gfrets[col*5 +: 5];
            if(m==7 && fret!=0 && fret!=31)
                fret=({1'b0,fret}+{1'b0,active_capo}<=18)?fret+active_capo:5'd31;
            pixel_pwm = 8'd0;wave=8'd0;
            case (m)
                4'd0: pixel_pwm = 8'd0;               // off
                4'd1: pixel_pwm = lvl;                // constant
                4'd2: begin                             // all breathe
                    wave = tri8(ph[15:8]);
                    pixel_pwm = wave; // serial shared multiplier applies level during frame preparation
                end
                4'd3: begin                             // progressive breathe
                    // A row step is eight phase counts; a column step is 32.
                    wave2 = ph[15:8] + (ax ? ({6'd0, col} << 5) : ({3'd0, rp} << 3));
                    wave = tri8(wave2);
                    pixel_pwm = wave; // serial shared multiplier applies level during frame preparation
                end
                4'd4: begin                             // dragon / bouncing tail
                    // Use a reflected 6-bit travel coordinate.  Row travel is
                    // scaled to 18 positions; column travel to four positions.
                    travel = ax ? {5'd0, ph[15:13]} : {2'd0, ph[15:10]};
                    if (ax) begin
                        if (travel > 8'd3) head = 8'd7 - travel;
                        else head = travel;
                        distance = (col >= head[1:0]) ? (col - head[1:0])
                                                   : (head[1:0] - col);
                    end else begin
                        if (travel > 8'd32) head = 8'd63 - travel;
                        else head = travel;
                        // Map the reflected 0..32 travel range onto valid
                        // row indices 0..17; multiplying by 18 would leave
                        // one blank step at the turnaround (head == 18).
                        scaled_head = {2'd0,head} * 10'd17;
                        head = {3'd0,scaled_head[9:5]};
                        distance = (rp >= head[4:0]) ? (rp - head[4:0])
                                                 : (head[4:0] - rp);
                    end
                    if (distance == 0) pixel_pwm = lvl;
                    else if (distance == 1) pixel_pwm = lvl >> 1;
                    else if (distance == 2) pixel_pwm = lvl >> 2;
                    else if (distance == 3) pixel_pwm = lvl >> 3;
                    else pixel_pwm = 8'd0;
                end
                4'd5: begin                             // travelling ripple
                    wave2 = ph[15:8] + (ax ? ({6'd0, col} << 5) : ({3'd0, rp} << 3));
                    wave = tri8(wave2);
                    // A narrow bright crest plus a dim tail makes the ripple
                    // visibly different from mode 3.
                    if (wave > 8'd190) pixel_pwm = lvl;
                    else if (wave > 8'd120) pixel_pwm = lvl >> 1;
                    else if (wave > 8'd55) pixel_pwm = lvl >> 3;
                    else pixel_pwm = 8'd0;
                end
                4'd6,4'd7: begin // One geometry for played notes and song guidance.
                    if ((fret==0 && rp>=active_capo) || (fret!=0 && fret<=18 && ((rp+5'd1)==fret || rp==fret)))
                        pixel_pwm = m==4'd6 ? lvl : glvl;
                    else pixel_pwm = 8'd0;
                end
                default: pixel_pwm = 8'd0;
            endcase

        end
    endfunction

    // Capo marker reserves both boundaries and all rows toward the nut.
    // Off/sleep/master-zero retain their original all-dark semantics.
    wire [7:0] capo_marker=(active_capo!=0 && mode_frame!=0 && level_frame!=0) ?
        (level_frame>=4 ? level_frame>>2 : 8'd1) : 8'd0;
    wire [17:0] capo_cs_mask;
    genvar cm;generate for(cm=0;cm<18;cm=cm+1)begin:g_capo_mask
      assign capo_cs_mask[cm]=capo_marker!=0 && row_pos(cm)<=active_capo;
    end endgenerate
    function [7:0] capo_mask_byte(input [5:0] idx);
      begin case(idx)2:capo_mask_byte=capo_cs_mask[7:0];3:capo_mask_byte=capo_cs_mask[15:8];4:capo_mask_byte={6'd0,capo_cs_mask[17:16]};default:capo_mask_byte=0;endcase end
    endfunction
    // Return one byte of the currently selected LP5860 transaction.
    function [7:0] tx_byte_for;
        input [5:0] idx;
        input [7:0] pixel;
        reg [7:0] pwm_byte;
        reg [7:0] addr_hi;
        reg [7:0] addr_lo;
        reg [4:0] cs_idx;
        begin
            addr_hi = tx_addr[9:2];
            addr_lo = {tx_addr[1:0], (tx_kind != TX_READ && tx_kind != TX_VERIFY), 5'd0}; // W=1/R=0
            cs_idx = (idx >= 6'd2) ? (idx[4:0] - 5'd2) : 5'd0;
            if (tx_kind == TX_CFG || tx_kind==TX_SLEEP || tx_kind==TX_WAKE) begin
                case (idx)
                    6'd0: tx_byte_for = addr_hi;
                    6'd1: tx_byte_for = addr_lo;
                    default: tx_byte_for = tx_data;
                endcase
                if (idx >= 6'd2) tx_byte_for = tx_data;
            end else if (tx_kind == TX_READ) begin
                if (idx == 0) tx_byte_for = addr_hi;
                else if (idx == 1) tx_byte_for = addr_lo;
                else tx_byte_for = 8'd0;
            end else if (tx_kind == TX_ONOFF) begin
                if (idx == 0) tx_byte_for = addr_hi;
                else if (idx == 1) tx_byte_for = addr_lo;
                else if(mode_frame==8)tx_byte_for=pixel|capo_mask_byte(idx);
                else begin
                    case (idx)
                        6'd2: tx_byte_for = onoff_enabled ? 8'hFF : 8'd0;
                        6'd3: tx_byte_for = onoff_enabled ? 8'hFF : 8'd0;
                        default: tx_byte_for = onoff_enabled ? 8'h03 : 8'd0;
                    endcase
                end
            end else if (tx_kind == TX_DC) begin
                if (idx == 0) tx_byte_for = addr_hi;
                else if (idx == 1) tx_byte_for = addr_lo;
                else tx_byte_for = 8'hFF;
            end else begin
                // Verification reads send the intended PWM as ignored MOSI
                // data too. If a damaged R/W bit turns a read into a write,
                // this is idempotent instead of overwriting the LEDs with 0.
                if (idx == 0) tx_byte_for = addr_hi;
                else if (idx == 1) tx_byte_for = addr_lo;
                else begin
                    pwm_byte = mask_cycle ? (pixel|capo_mask_byte(idx)) : pixel;
                    tx_byte_for = pwm_byte;
                end
            end
        end
    endfunction

    // Bit extractor kept separate so Verilog-2001 tools do not have to parse
    // a variable part-select of a function call in the SPI shifter.
    function tx_bit_for;
        input [5:0] idx;
        input [2:0] bit_no;
        input [7:0] pixel;
        reg [7:0] b;
        begin
            b = tx_byte_for(idx,pixel);
            tx_bit_for = (b >> bit_no) & 1'b1;
        end
    endfunction

    // One renderer/bit selector is shared by transaction start, byte change,
    // and ordinary bit shifts; these actions never occur on the same clock.
    wire [5:0] shift_index=state==S_PREP ? 6'd0 : tx_bit_idx==0 ? tx_byte_idx+6'd1 : tx_byte_idx;
    wire [2:0] shift_bit=state==S_PREP || tx_bit_idx==0 ? 3'd7 : tx_bit_idx-3'd1;
    wire legacy_breathe=(mode_frame==2 || mode_frame==3);
    wire [7:0] shift_base=(mode_frame==8 || legacy_breathe) ?
      (shift_index==tx_byte_idx ? profile_current_pixel : legacy_breathe ? legacy_mult_pixel : profile_next_pixel) : legacy_raw_q;
    wire capo_pwm_byte=(tx_kind==TX_PWM || (tx_kind==TX_VERIFY && !mask_cycle)) && shift_index>=2 && shift_index<20;
    wire [7:0] shift_pixel=(capo_pwm_byte && active_capo!=0 && row_pos(shift_index-6'd2)<=active_capo && shift_base<capo_marker) ? capo_marker : shift_base;
    wire shift_mosi=tx_bit_for(shift_index,shift_bit,shift_pixel);
    // Restore the original direct byte renderer for modes 0..7. The new
    // profile RAM/cache is exclusively used by mode 8, so uploads and profile
    // prefetch cannot change a legacy mode's PWM data. One shared bit selector
    // prevents the original three copies of the renderer being synthesized.
    wire legacy_advance=state==S_SHIFT && half_cnt==HALF_DIV-1 && spi_sck && tx_bit_idx==0 && ({1'b0,tx_byte_idx}+7'd1)<{1'b0,tx_total};
    assign legacy_request=legacy_breathe && (tx_kind==TX_PWM || tx_kind==TX_VERIFY) && (state==S_PREP || legacy_advance);
    // Calculate the following byte while the current byte is on the wire.
    // 8 multiply cycles share the existing ALU; a SPI byte affords >=384 cycles.
    wire [5:0] legacy_index=legacy_breathe ? tx_byte_idx+(legacy_advance?6'd2:6'd1) : shift_index;
    wire [4:0] legacy_cs=legacy_index>=2 ? legacy_index[4:0]-5'd2 : 5'd0;
    assign legacy_raw=pixel_pwm(mode_frame,axis_frame,level_frame,phase_frame,
      frame_col,row_pos(legacy_cs),4'd0,flags_frame,
      mode_frame==6?event_frets_frame:guide_frets_frame,guide_levels_frame[frame_col*8+:8]);

    // Config register data is kept in a small function instead of a ROM.
    function [7:0] cfg_data;
        input [4:0] step;
        begin
            case (step)
                5'd0: cfg_data = 8'h01; // Chip_EN
                5'd1: cfg_data = 8'h50; // 10 scan lines (SW0..SW9), Mode 1, 125 kHz PWM
                5'd2: cfg_data = 8'h00; // Dev_config1
                5'd3: cfg_data = 8'h00; // Dev_config2
                5'd4: cfg_data = 8'h59; // Dev_config3: MC[3:1]=4 (20 mA) + deghost
                5'd5: cfg_data = 8'hFF; // Global brightness
                5'd6: cfg_data = 8'hFF; // PWM group 1 brightness
                5'd7: cfg_data = 8'hFF; // PWM group 2 brightness
                5'd8: cfg_data = 8'hFF; // PWM group 3 brightness
                5'd9: cfg_data = 8'h7F; // CC group 1: 7-bit full current
                5'd10: cfg_data = 8'h7F; // CC group 2
                5'd11: cfg_data = 8'h7F; // CC group 3
                default: cfg_data = 8'h00;
            endcase
        end
    endfunction

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            sleep_applied<=0;
            init_pending<=1;check_count<=0;
            control_bad<=0;control_valid<=0;data_ok<=0;pwm_retry<=0;
            control_errors<=0;pwm_errors<=0;last_fault<=0;last_bad_col<=0;write_crc<=16'hFFFF;read_crc<=16'hFFFF;
            state        <= S_IDLE;
            gap_next     <= S_IDLE;
            enable_wait  <= ENABLE_WAIT_CLKS;
            rx_byte      <= 8'd0;
            read_chip_en <= 8'd0;
            read_dev_initial <= 8'd0;
            read_config3 <= 8'd0;
            read_global  <= 8'd0;
            sent_chip_en <= 24'd0;
            sent_dev_initial <= 24'd0;
            tx_kind      <= TX_CFG;
            tx_addr      <= 10'd0;
            tx_line      <= 5'd0;
            tx_data      <= 8'd0;
            tx_total     <= 6'd0;
            tx_byte_idx  <= 6'd0;
            tx_bit_idx   <= 3'd7;
            half_cnt     <= 16'd0;
            spi_sck      <= 1'b0;
            spi_mosi     <= 1'b0;
            cs_n         <= 1'b1;
            vsync        <= 1'b0;
            touch_park_phase <= 1'b0;
            initialized  <= 1'b0;
            init_step    <= 5'd0;
            frame_col    <= 2'd0;
            frame_cnt    <= 32'd0;
            phase        <= 16'd0;
            phase_frame  <= 16'd0;
            dirty        <= 1'b1;
            frame_pending <= 1'b0;
            hold_for_touch <= 1'b0;
            mode_shadow  <= 4'd0;
            mode_frame   <= 4'd0;
            axis_shadow  <= 1'b0;
            axis_frame   <= 1'b0;
            speed_shadow <= 8'd24;
            speed_frame  <= 8'd24;
            level_shadow <= 8'd180;
            level_frame  <= 8'd180;
            flags_shadow <= 8'd0;
            flags_frame  <= 8'd0;
            profile_prepare<=0;profile_current_pixel<=0;onoff_enabled<=0;mask_cycle<=0;
            capo_shadow<=0;capo_frame<=0;guide_frets_shadow <= 20'hFFFFF;
            guide_frets_frame <= 20'hFFFFF;
            guide_levels_shadow <= 8'd0;
            guide_levels_frame  <= 8'd0;
            event_frets_shadow <= 20'hFFFFF;
            event_frets_frame <= 20'hFFFFF;
            for(ec=0;ec<4;ec=ec+1)event_left[ec]<=0;
        end else begin
            profile_prepare<=0;
            if(profile_select)data_ok<=0; // A new commit is applied only after PWM AND masks verify.
            if (enable_wait != 0) enable_wait <= enable_wait - 1'b1;
            // Configuration is shadowed so a frame already in flight remains
            // visually coherent.  A zero speed is accepted as a valid stop.
            if (cfg_we) begin
                mode_shadow  <= (cfg_mode > 4'd8) ? 4'd0 : cfg_mode;
                axis_shadow  <= cfg_axis;
                speed_shadow <= cfg_speed;
                level_shadow <= cfg_level;
                flags_shadow <= cfg_flags;
                dirty        <= 1'b1;
            end
            if (guide_we) begin
                guide_frets_shadow <= guide_frets;
                guide_levels_shadow <= guide_levels;
                dirty              <= 1'b1;
            end
            // ADC G/E/C/A -> physical G/C/E/A. Capture the queued audio
            // note, never the later moving finger position or raw detector.
            for(ec=0;ec<4;ec=ec+1) begin
                if(sleep_req || (cfg_we && cfg_mode!=mode_shadow)) begin
                    event_left[ec]<=0;
                    event_frets_shadow[ec*5 +: 5]<=31;
                end else if(event_bits[ec==1 ? 2 : ec==2 ? 1 : ec]) begin
                    event_frets_shadow[ec*5 +: 5]<=event_fret<=18 ? event_fret : 5'd31;
                    event_left[ec]<=event_left[ec]!=0 ? EVENT_HOLD_FRAMES+REFLASH_FRAMES : EVENT_HOLD_FRAMES;
                end else if(event_tick && event_left[ec]!=0)event_left[ec]<=event_left[ec]-1'b1;
            end
            if(sleep_req)begin frame_cnt<=0;end
            else if (frame_cnt < FRAME_DIV - 1)
                frame_cnt <= frame_cnt + 32'd1;
            else begin
                frame_cnt <= 32'd0;
                if (initialized && check_count!=0) check_count<=check_count-1'b1;
                phase <= phase + (mode_shadow==8 ? CUSTOM_STEP : phase_step);
                // A busy touch read at the frame boundary must not drop the
                // only opportunity to refresh. Retain it until acquired.
                frame_pending <= 1'b1;
            end

            if(CAPO_ENABLED && capo_shadow!=capo_fret)begin capo_shadow<=capo_fret;dirty<=1;frame_pending<=1;end
            if (hold_for_touch && bus_idle)
                hold_for_touch <= 1'b0;

            case (state)
                S_IDLE: begin
                    spi_sck <= 1'b0;
                    cs_n    <= 1'b1;
                    if (bus_idle && !hold_for_touch && (enable_wait == 0)) begin
                        if(sleep_req!=sleep_applied)begin
                            tx_kind<=sleep_req ? TX_SLEEP : TX_WAKE;tx_addr<=0;
                            tx_data<=sleep_req ? 8'd0 : 8'd1;tx_total<=3;
                            tx_byte_idx<=0;state<=S_PREP;
                        end else if (!sleep_req && check_count==0) begin
                            // Setup: 12 config writes, then four ON/OFF bursts,
                            // then four DC bursts and two independent reads.
                            if (!init_pending) begin
                                // Poll actual device registers, not a cached
                                // command mirror. A mismatch schedules reinit.
                                init_pending<=1;init_step<=20;control_bad<=0;control_valid<=0;
                                tx_kind<=TX_READ;tx_addr<=0;tx_total<=3;
                            end else if (init_step < 5'd12) begin
                                tx_kind     <= TX_CFG;
                                tx_addr     <= {5'd0, init_step};
                                tx_line     <= 5'd0;
                                tx_data     <= cfg_data(init_step);
                                tx_total    <= 6'd3;
                            end else if (init_step < 5'd16) begin
                                tx_kind     <= TX_ONOFF;
                                tx_line     <= init_step - 5'd12;
                                tx_addr     <= 10'h043 + line_map(init_step - 5'd12) * 10'd3;
                                tx_total    <= 6'd5;
                            end else if (init_step < 5'd20) begin
                                tx_kind     <= TX_DC;
                                tx_line     <= init_step - 5'd16;
                                tx_addr     <= 10'h100 + line_map(init_step - 5'd16) * 10'd18;
                                tx_total    <= 6'd20;
                            end else if (init_step == 5'd20) begin
                                tx_kind     <= TX_READ;
                                tx_addr     <= 10'd0;
                                tx_total    <= 6'd3; // Chip_EN by itself
                            end else if (init_step==21) begin
                                // An address-zero burst can appear correct
                                // with MOSI stuck low. Read from address 1 in
                                // another CS frame to exercise its address bit.
                                tx_kind     <= TX_READ;
                                tx_addr     <= 10'd1;
                                tx_total    <= 6'd13; // registers 1..11, including CC/group/DC controls
                            end else if (init_step<26) begin
                                tx_kind<=TX_READ;tx_line<=init_step-22;
                                tx_addr<=10'h043+line_map(init_step-22)*10'd3;tx_total<=5;
                            end else if (init_step<30) begin
                                tx_kind<=TX_READ;tx_line<=init_step-26;
                                tx_addr<=10'h100+line_map(init_step-26)*10'd18;tx_total<=20;
                            end else begin
                                tx_kind<=TX_READ;tx_addr<=10'h064;tx_total<=3;
                            end
                            tx_byte_idx <= 6'd0;
                            state       <= S_PREP;
                        end else if (!sleep_req && !init_pending && frame_pending &&
                                     (dirty || (mode_shadow >= 4'd2))) begin
                            frame_pending <= 1'b0;
                            phase_frame <= phase;
                            mode_frame  <= mode_shadow;
                            axis_frame  <= axis_shadow;
                            speed_frame <= speed_shadow;
                            level_frame <= level_shadow;
                            flags_frame <= flags_shadow;
                            guide_frets_frame <= guide_frets_shadow;
                            guide_levels_frame <= guide_levels_shadow;capo_frame<=capo_shadow;
                            for(ec=0;ec<4;ec=ec+1)
                                event_frets_frame[ec*5 +: 5] <= event_left[ec]!=0 && event_left[ec]<=EVENT_HOLD_FRAMES ? event_frets_shadow[ec*5 +: 5] : 5'd31;
                            frame_col   <= 2'd0;pwm_retry<=0;mask_cycle<=0;
                            if(mode_shadow==8 && mode_frame!=8)data_ok<=0;
                            // A same-edge configuration is in the shadow on
                            // the next clock, so schedule another frame for it.
                            dirty       <= cfg_we || guide_we || (CAPO_ENABLED && capo_shadow!=capo_fret);
                            profile_prepare<=mode_shadow==8;
                            onoff_enabled<=mode_shadow!=0;
                            tx_kind     <= (mode_shadow!=8 && ((onoff_enabled!=(mode_shadow!=0)) || mode_frame==8)) ? TX_ONOFF : TX_PWM;
                            tx_line     <= {3'd0, line_map(2'd0)};
                            tx_addr     <= (mode_shadow!=8 && ((onoff_enabled!=(mode_shadow!=0)) || mode_frame==8)) ? 10'h043+line_map(2'd0)*10'd3 : 10'h200 + line_map(2'd0) * 10'd18;
                            tx_total    <= (mode_shadow!=8 && ((onoff_enabled!=(mode_shadow!=0)) || mode_frame==8)) ? 6'd5 : 6'd20;
                            tx_byte_idx <= 6'd0;
                            state       <= mode_shadow==8 ? S_PROFILE : S_PREP;
                        end
                    end
                end
                S_PROFILE:begin
                    if(profile_ready && !profile_prepare && bus_idle && !hold_for_touch)state<=S_PREP;
                end

                S_PREP: begin
                    // CS setup and first MOSI bit.  LP5860 samples on rising
                    // SCLK edges, so the bit is stable for a full half period.
                    cs_n        <= 1'b0;
                    spi_sck     <= 1'b0;
                    spi_mosi    <= shift_mosi;
                    tx_bit_idx  <= 3'd7;
                    half_cnt    <= 16'd0;
                    rx_byte     <= 8'd0;
                    if(tx_kind==TX_PWM || (tx_kind==TX_ONOFF && mode_frame==8 && !init_pending))write_crc<=16'hFFFF;
                    if(tx_kind==TX_VERIFY)read_crc<=16'hFFFF;
                    if(tx_kind==TX_CFG && init_step==0)begin control_bad<=0;control_valid<=0;data_ok<=0;end
                    state       <= S_SHIFT;
                end

                S_SHIFT: begin
                    if (half_cnt < HALF_DIV - 1)
                        half_cnt <= half_cnt + 16'd1;
                    else begin
                        half_cnt <= 16'd0;
                        if (!spi_sck) begin
                            spi_sck <= 1'b1; // LP5860 samples here
                            // Capture the actual registered MOSI sent on every
                            // sampling edge of the first two configuration
                            // writes. Expected: 002001 and 006050.
                            if (tx_kind == TX_CFG && init_step == 5'd0)
                                sent_chip_en <= {sent_chip_en[22:0], spi_mosi};
                            if (tx_kind == TX_CFG && init_step == 5'd1)
                                sent_dev_initial <= {sent_dev_initial[22:0], spi_mosi};
                            if ((tx_kind == TX_READ || tx_kind==TX_VERIFY) && tx_byte_idx>=2)
                                rx_byte <= {rx_byte[6:0], spi_miso};
                            if(tx_byte_idx>=2)begin
                                if(tx_kind==TX_PWM || (tx_kind==TX_ONOFF && mode_frame==8 && !init_pending))write_crc<=crc_bit(write_crc,spi_mosi);
                                if(tx_kind==TX_VERIFY)read_crc<=crc_bit(read_crc,spi_miso);
                            end
                        end else begin
                            spi_sck <= 1'b0;
                            if (tx_bit_idx == 3'd0) begin
                                // Include the LAST byte of each SS frame:
                                // otherwise the same AD7147 can accumulate
                                // clocks across two short LED transactions.
                                // One decoder address bit changes on this
                                // falling edge, with a full low half-period
                                // before the next SCLK rising edge.
                                if (TOUCH_PARK_PER_BYTE != 0)
                                    touch_park_phase <= ~touch_park_phase;
                                if (tx_kind == TX_READ) begin
                                    if(tx_byte_idx>=2)begin
                                        if(init_step==20 && rx_byte!=1)control_bad<=1;
                                        if(init_step==21 && rx_byte!=cfg_data(tx_byte_idx-1))control_bad<=1;
                                        if(init_step>=22 && init_step<26 &&
                                            (mode_frame==8 ? rx_byte!=profile_current_pixel :
                                            ((tx_byte_idx<4 && rx_byte!=(onoff_enabled?8'hFF:8'd0)) || (tx_byte_idx==4 && rx_byte[1:0]!=(onoff_enabled?2'b11:2'b00)))))control_bad<=1;
                                        if(init_step>=26 && init_step<30 && rx_byte!=8'hFF)control_bad<=1;
                                        if(init_step==30)last_fault<=rx_byte;
                                    end
                                    if (init_step == 5'd20 && tx_byte_idx == 6'd2)
                                        read_chip_en <= rx_byte;
                                    else if (init_step == 5'd21) begin
                                        case (tx_byte_idx)
                                            6'd2: read_dev_initial <= rx_byte;
                                            6'd5: read_config3 <= rx_byte;
                                            6'd6: read_global <= rx_byte;
                                            default: ;
                                        endcase
                                    end
                                end
                                if (tx_byte_idx + 6'd1 >= tx_total) begin
                                    // Do not raise SS one 20-ns clock after
                                    // the final SCLK falling edge. Preserve
                                    // SS/MOSI for a full half SPI period.
                                    state <= S_HOLD;
                                end else begin
                                    profile_current_pixel<=legacy_breathe ? legacy_mult_pixel : profile_next_pixel;
                                    tx_byte_idx <= tx_byte_idx + 6'd1;
                                    tx_bit_idx  <= 3'd7;
                                    spi_mosi    <= shift_mosi;
                                end
                            end else begin
                                tx_bit_idx <= tx_bit_idx - 3'd1;
                                spi_mosi   <= shift_mosi;
                            end
                        end
                    end
                end

                S_HOLD: begin
                    // TI requires SS active leg time >=50 ns. GAP_CLKS is
                    // also at least 50 ns; at the 1-MHz bus it gives 500 ns.
                    // Keep bus ownership and the final MOSI bit until done.
                    spi_sck <= 1'b0;
                    if (half_cnt < GAP_CLKS - 1)
                        half_cnt <= half_cnt + 16'd1;
                    else begin
                        half_cnt <= 16'd0;
                        state <= S_DONE;
                    end
                end

                S_DONE: begin
                    spi_sck <= 1'b0;
                    spi_mosi <= 1'b0;
                    cs_n <= 1'b1;
                    half_cnt <= 16'd0;
                    state <= S_GAP;
                    gap_next <= S_IDLE;
                    if(tx_kind==TX_SLEEP || tx_kind==TX_WAKE)begin
                        sleep_applied<=tx_kind==TX_SLEEP;gap_next<=S_IDLE;
                        if(tx_kind==TX_WAKE)begin
                            enable_wait<=ENABLE_WAIT_CLKS;dirty<=1;frame_pending<=1;
                            init_pending<=1;init_step<=0;check_count<=0;control_valid<=0;data_ok<=0;
                            read_chip_en<=0;read_dev_initial<=0;read_config3<=0;read_global<=0;
                        end
                    end else if (init_pending) begin
                        if (init_step == 5'd0) enable_wait <= ENABLE_WAIT_CLKS;
                        if (init_step == 5'd30) begin
                            initialized <= 1'b1;
                            init_pending <= control_bad || !readback_match;
                            control_valid<=!control_bad && readback_match;
                            if(control_bad || !readback_match)begin
                                data_ok<=0;
                                if(control_errors!=255)control_errors<=control_errors+1'b1;
                            end
                            check_count <= CHECK_FRAMES;
                            init_step <= 5'd0;
                            dirty <= 1'b1;
                        end else begin
                            init_step <= init_step + 5'd1;
                        end
                    end else if(tx_kind==TX_ONOFF)begin
                        if(mode_frame==8)begin
                            tx_kind<=TX_VERIFY;tx_byte_idx<=0;gap_next<=S_PREP;
                        end else begin
                        // Four compact masks are changed only on enable/off
                        // transitions; breathing never blanks the whole matrix.
                        tx_byte_idx<=0;gap_next<=S_PREP;
                        if(frame_col!=3)begin
                            frame_col<=frame_col+1'b1;
                            tx_addr<=10'h043+line_map(frame_col+1'b1)*10'd3;tx_total<=5;
                        end else begin
                            frame_col<=0;tx_kind<=TX_PWM;
                            tx_addr<=10'h200+line_map(2'd0)*10'd18;tx_total<=20;
                        end
                        end
                    end else if(tx_kind==TX_PWM)begin
                        // Verify the exact payload just shifted, with no framebuffer
                        // or duplicate renderer. CRC16 is accumulated bit by bit.
                        tx_kind<=TX_VERIFY;tx_byte_idx<=0;gap_next<=S_PREP;
                    end else if(tx_kind==TX_VERIFY && read_crc!=write_crc)begin
                        data_ok<=0;last_bad_col<=frame_col;
                        if(pwm_errors!=255)pwm_errors<=pwm_errors+1'b1;
                        if(pwm_retry<2)begin
                            pwm_retry<=pwm_retry+1'b1;tx_kind<=mask_cycle ? TX_ONOFF : TX_PWM;tx_byte_idx<=0;gap_next<=S_PREP;
                        end else begin
                            // Bound a failing lane's bus occupancy and allow touch
                            // transactions to continue before another attempt.
                            // An isolated payload/read error does not reset a
                            // healthy chip or stall animation for 500 ms.
                            // Re-render on the next frame; the independent
                            // register audit still recovers real chip resets.
                            pwm_retry<=0;dirty<=1;
                        end
                    end else if(tx_kind==TX_VERIFY && mode_frame==8 && !mask_cycle)begin
                        // Write PWM first, then enable precisely its nonzero
                        // dots. Inactive dots stay hardware-disabled.
                        pwm_retry<=0;mask_cycle<=1;tx_kind<=TX_ONOFF;
                        tx_addr<=10'h043+line_map(frame_col)*10'd3;tx_total<=5;
                        tx_byte_idx<=0;gap_next<=S_PREP;
                    end else if (tx_kind == TX_VERIFY && frame_col != 2'd3) begin
                        pwm_retry<=0;mask_cycle<=0;
                        frame_col <= frame_col + 2'd1;
                        tx_kind <= TX_PWM;
                        tx_line <= {3'd0, line_map(frame_col + 2'd1)};
                        tx_addr <= 10'h200 + line_map(frame_col + 2'd1) * 10'd18;
                        tx_total <= 6'd20;
                        tx_byte_idx <= 6'd0;
                        gap_next <= S_PREP;
                    end else begin
                        // The arbiter only acquires an idle touch transaction.
                        // Keep a guard if a future scanner reports late busy.
                        hold_for_touch <= !bus_idle;
                        if(tx_kind==TX_VERIFY && frame_col==3 && read_crc==write_crc)begin data_ok<=1;pwm_retry<=0;end
                    end
                end

                S_GAP: begin
                    // Keep CS high long enough to reset the SPI interface.
                    // Previously consecutive PWM bursts had only 20 ns here.
                    if (half_cnt < GAP_CLKS - 1)
                        half_cnt <= half_cnt + 16'd1;
                    else begin
                        half_cnt <= 16'd0;
                        state <= gap_next;
                    end
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
