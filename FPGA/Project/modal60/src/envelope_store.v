// Four timbres by 32 addresses. The analyser capture RAM holds the profiles
// above its 256 samples. A pluck prefetches its profile before engine launch.
module envelope_store (
    input wire clk, rst_n,
    input wire play, input wire [6:0] play_address,
    input wire start, input wire [1:0] operation,
    input wire [6:0] query, input wire [7:0] value,
    output wire [6:0] ram_raddr, ram_waddr,
    output wire ram_scan, ram_write,
    output wire [7:0] ram_data,
    input wire [7:0] ram_q,
    input wire scan_grant, write_grant,
    output wire [7:0] play_value,
    output reg ready, done,
    output reg [7:0] read_value,
    output reg [27:0] modified
);
    localparam [7:0] FACTORY = 8'd235;
    localparam [2:0] IDLE=0, WRITE=1, SCAN=2, CLEAR_BANK=3, CLEAR_ALL=4, SCAN_RESULT=5;
    reg [2:0] state;
    reg [6:0] cursor;
    reg [1:0] bank;
    reg startup;
    reg play_default;
    wire clearing = state==CLEAR_BANK || state==CLEAR_ALL;
    assign ram_waddr = clearing ? cursor : query;
    assign ram_raddr = play ? play_address : cursor;
    assign ram_data = clearing ? 8'd0 : value ^ FACTORY;
    assign ram_write = rst_n && (state==WRITE || clearing);
    assign ram_scan = state==SCAN;
    assign play_value = play_default ? FACTORY : ram_q ^ FACTORY;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) play_default<=1;
        else if (play)
            play_default<=play_address[4:0]>=28 || state==CLEAR_ALL ||
                          (state==CLEAR_BANK && play_address[6:5]==bank);
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state<=CLEAR_ALL; cursor<=0; bank<=0; startup<=1;
            ready<=0; done<=0; modified<=0; read_value<=FACTORY;
        end else begin
            done<=0;
            case (state)
                IDLE: if (start) begin
                    ready<=0; bank<=query[6:5]; modified<=0;
                    cursor<=operation==3 ? 7'd0 : {query[6:5],5'd0};
                    case (operation)
                        1: state<=WRITE;
                        2: state<=CLEAR_BANK;
                        3: state<=CLEAR_ALL;
                        default: state<=SCAN;
                    endcase
                end
                WRITE: if (write_grant) state<=SCAN;
                SCAN: if (scan_grant) state<=SCAN_RESULT;
                SCAN_RESULT: begin
                    modified<={|ram_q,modified[27:1]};
                    if (cursor==query) read_value<=ram_q ^ FACTORY;
                    if (cursor[4:0]==27) begin
                        state<=IDLE; ready<=1; done<=1;
                    end else begin cursor<=cursor+1'b1; state<=SCAN; end
                end
                CLEAR_BANK, CLEAR_ALL: if (write_grant) begin
                    read_value<=FACTORY;
                    if ((state==CLEAR_BANK && cursor[4:0]==27) || cursor==127) begin
                        state<=IDLE; ready<=1; done<=!startup; startup<=0;
                    end else cursor<=cursor+1'b1;
                end
                default: state<=IDLE;
            endcase
        end
    end
endmodule

