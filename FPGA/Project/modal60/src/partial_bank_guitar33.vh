// ============================================================
// partial_bank.vh —— 自动生成，勿手改！
// 由 src/tools/gen_partial_bank.py（v12 pluck）生成（吉他库 D3..A#5 33音）
// 音库：33 个音（D3..A#5，按 MIDI 排序），每音最多 15 个泛音槽
// 槽包络 env(t)=env0*(e^{-t/tau_d} - e^{-t/tau_a})：
//   pt_rate = 衰减支率（tau_d 实测，12bit）
//   pt_aatk = 起振支率（16bit，每泛音不同：Tpk=max(8,25*k^-0.30)ms 反解
//             -> 高频先起振（doc/起振不同.md、木质 §3.2））
//   pt_ph0  = 每槽伪随机相位初值（32bit，重触发起步相位去同相堆积）
// env0 按峰值域归一（Σ env0*pf=8192，含 1/pf 补偿），初值 env0<<9
// `define PLUCK_ATTACK：引擎走逐槽起振/相位路径；删掉则退回按声部共用起振
// 重新生成：python src/tools/gen_partial_bank.py
// ============================================================
`ifndef PARTIAL_BANK_GUITAR33_VH
`define PARTIAL_BANK_GUITAR33_VH
`define PLUCK_ATTACK

localparam PT_NOTES = 33;          // 音库音数（声部数）
localparam PT_N     = 15;          // 每音泛音槽数
localparam PT_SUM_ENV0 = 8192;       // 每音峰值域归一总幅度

// 键 = voice*PT_N + (k-1)，越界返回 0；每音"叶子"函数 + 按 voice 包装。
function automatic [31:0] pt_inc_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v0 = 32'h00C51339;   // D3 k=1 f=146.83Hz
        5'd1: pt_inc_v0 = 32'h018A2672;   // D3 k=2 f=293.66Hz
        5'd2: pt_inc_v0 = 32'h024F39AB;   // D3 k=3 f=440.50Hz
        5'd3: pt_inc_v0 = 32'h03144CE4;   // D3 k=4 f=587.33Hz
        5'd4: pt_inc_v0 = 32'h03D9601D;   // D3 k=5 f=734.16Hz
        5'd5: pt_inc_v0 = 32'h049E7357;   // D3 k=6 f=880.99Hz
        5'd6: pt_inc_v0 = 32'h05638690;   // D3 k=7 f=1027.83Hz
        5'd7: pt_inc_v0 = 32'h062899C9;   // D3 k=8 f=1174.66Hz
        5'd8: pt_inc_v0 = 32'h06EDAD02;   // D3 k=9 f=1321.49Hz
        5'd9: pt_inc_v0 = 32'h07B2C03B;   // D3 k=10 f=1468.32Hz
        5'd10: pt_inc_v0 = 32'h0877D374;   // D3 k=11 f=1615.16Hz
        5'd11: pt_inc_v0 = 32'h093CE6AD;   // D3 k=12 f=1761.99Hz
        5'd12: pt_inc_v0 = 32'h0A01F9E6;   // D3 k=13 f=1908.82Hz
        5'd13: pt_inc_v0 = 32'h0AC70D1F;   // D3 k=14 f=2055.65Hz
        5'd14: pt_inc_v0 = 32'h0B8C2058;   // D3 k=15 f=2202.49Hz
        default: pt_inc_v0 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v0 = 16'd2833;   // D3 k=1 f=146.83Hz
        5'd1: pt_env0_v0 = 16'd2840;   // D3 k=2 f=293.66Hz
        5'd2: pt_env0_v0 = 16'd1142;   // D3 k=3 f=440.50Hz
        5'd3: pt_env0_v0 = 16'd206;   // D3 k=4 f=587.33Hz
        5'd4: pt_env0_v0 = 16'd158;   // D3 k=5 f=734.16Hz
        5'd5: pt_env0_v0 = 16'd209;   // D3 k=6 f=880.99Hz
        5'd6: pt_env0_v0 = 16'd513;   // D3 k=7 f=1027.83Hz
        5'd7: pt_env0_v0 = 16'd87;   // D3 k=8 f=1174.66Hz
        5'd8: pt_env0_v0 = 16'd11;   // D3 k=9 f=1321.49Hz
        5'd9: pt_env0_v0 = 16'd59;   // D3 k=10 f=1468.32Hz
        5'd10: pt_env0_v0 = 16'd63;   // D3 k=11 f=1615.16Hz
        5'd11: pt_env0_v0 = 16'd136;   // D3 k=12 f=1761.99Hz
        5'd12: pt_env0_v0 = 16'd8;   // D3 k=13 f=1908.82Hz
        5'd13: pt_env0_v0 = 16'd80;   // D3 k=14 f=2055.65Hz
        5'd14: pt_env0_v0 = 16'd50;   // D3 k=15 f=2202.49Hz
        default: pt_env0_v0 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v0 = 12'd34;   // D3 k=1 f=146.83Hz
        5'd1: pt_rate_v0 = 12'd146;   // D3 k=2 f=293.66Hz
        5'd2: pt_rate_v0 = 12'd57;   // D3 k=3 f=440.50Hz
        5'd3: pt_rate_v0 = 12'd53;   // D3 k=4 f=587.33Hz
        5'd4: pt_rate_v0 = 12'd85;   // D3 k=5 f=734.16Hz
        5'd5: pt_rate_v0 = 12'd57;   // D3 k=6 f=880.99Hz
        5'd6: pt_rate_v0 = 12'd175;   // D3 k=7 f=1027.83Hz
        5'd7: pt_rate_v0 = 12'd138;   // D3 k=8 f=1174.66Hz
        5'd8: pt_rate_v0 = 12'd34;   // D3 k=9 f=1321.49Hz
        5'd9: pt_rate_v0 = 12'd137;   // D3 k=10 f=1468.32Hz
        5'd10: pt_rate_v0 = 12'd72;   // D3 k=11 f=1615.16Hz
        5'd11: pt_rate_v0 = 12'd268;   // D3 k=12 f=1761.99Hz
        5'd12: pt_rate_v0 = 12'd48;   // D3 k=13 f=1908.82Hz
        5'd13: pt_rate_v0 = 12'd193;   // D3 k=14 f=2055.65Hz
        5'd14: pt_rate_v0 = 12'd296;   // D3 k=15 f=2202.49Hz
        default: pt_rate_v0 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v0 = 16'd22346;   // D3 k=1 f=146.83Hz
        5'd1: pt_aatk_v0 = 16'd21214;   // D3 k=2 f=293.66Hz
        5'd2: pt_aatk_v0 = 16'd30031;   // D3 k=3 f=440.50Hz
        5'd3: pt_aatk_v0 = 16'd33675;   // D3 k=4 f=587.33Hz
        5'd4: pt_aatk_v0 = 16'd33321;   // D3 k=5 f=734.16Hz
        5'd5: pt_aatk_v0 = 16'd38406;   // D3 k=6 f=880.99Hz
        5'd6: pt_aatk_v0 = 16'd32333;   // D3 k=7 f=1027.83Hz
        5'd7: pt_aatk_v0 = 16'd35784;   // D3 k=8 f=1174.66Hz
        5'd8: pt_aatk_v0 = 16'd48216;   // D3 k=9 f=1321.49Hz
        5'd9: pt_aatk_v0 = 16'd38862;   // D3 k=10 f=1468.32Hz
        5'd10: pt_aatk_v0 = 16'd45610;   // D3 k=11 f=1615.16Hz
        5'd11: pt_aatk_v0 = 16'd35674;   // D3 k=12 f=1761.99Hz
        5'd12: pt_aatk_v0 = 16'd51871;   // D3 k=13 f=1908.82Hz
        5'd13: pt_aatk_v0 = 16'd40774;   // D3 k=14 f=2055.65Hz
        5'd14: pt_aatk_v0 = 16'd37869;   // D3 k=15 f=2202.49Hz
        default: pt_aatk_v0 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v0 = 32'h510C4619;   // D3 k=1 f=146.83Hz
        5'd1: pt_ph0_v0 = 32'hE02E553E;   // D3 k=2 f=293.66Hz
        5'd2: pt_ph0_v0 = 32'h7BB98F3A;   // D3 k=3 f=440.50Hz
        5'd3: pt_ph0_v0 = 32'h0183A8B5;   // D3 k=4 f=587.33Hz
        5'd4: pt_ph0_v0 = 32'hE6336D1F;   // D3 k=5 f=734.16Hz
        5'd5: pt_ph0_v0 = 32'hF989D237;   // D3 k=6 f=880.99Hz
        5'd6: pt_ph0_v0 = 32'hBA2529D0;   // D3 k=7 f=1027.83Hz
        5'd7: pt_ph0_v0 = 32'hFCFBEDBF;   // D3 k=8 f=1174.66Hz
        5'd8: pt_ph0_v0 = 32'hA8C5EE39;   // D3 k=9 f=1321.49Hz
        5'd9: pt_ph0_v0 = 32'hB55A53B8;   // D3 k=10 f=1468.32Hz
        5'd10: pt_ph0_v0 = 32'h1A88A9EE;   // D3 k=11 f=1615.16Hz
        5'd11: pt_ph0_v0 = 32'hF918A8B4;   // D3 k=12 f=1761.99Hz
        5'd12: pt_ph0_v0 = 32'h6DC588D3;   // D3 k=13 f=1908.82Hz
        5'd13: pt_ph0_v0 = 32'h472F513C;   // D3 k=14 f=2055.65Hz
        5'd14: pt_ph0_v0 = 32'h0C1870B8;   // D3 k=15 f=2202.49Hz
        default: pt_ph0_v0 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v1 = 32'h00D0CB35;   // D#3 k=1 f=155.56Hz
        5'd1: pt_inc_v1 = 32'h01A1966B;   // D#3 k=2 f=311.13Hz
        5'd2: pt_inc_v1 = 32'h027261A0;   // D#3 k=3 f=466.69Hz
        5'd3: pt_inc_v1 = 32'h03432CD6;   // D#3 k=4 f=622.25Hz
        5'd4: pt_inc_v1 = 32'h0413F80B;   // D#3 k=5 f=777.82Hz
        5'd5: pt_inc_v1 = 32'h04E4C341;   // D#3 k=6 f=933.38Hz
        5'd6: pt_inc_v1 = 32'h05B58E76;   // D#3 k=7 f=1088.94Hz
        5'd7: pt_inc_v1 = 32'h068659AC;   // D#3 k=8 f=1244.51Hz
        5'd8: pt_inc_v1 = 32'h075724E1;   // D#3 k=9 f=1400.07Hz
        5'd9: pt_inc_v1 = 32'h0827F017;   // D#3 k=10 f=1555.63Hz
        5'd10: pt_inc_v1 = 32'h08F8BB4C;   // D#3 k=11 f=1711.20Hz
        5'd11: pt_inc_v1 = 32'h09C98681;   // D#3 k=12 f=1866.76Hz
        5'd12: pt_inc_v1 = 32'h0A9A51B7;   // D#3 k=13 f=2022.33Hz
        5'd13: pt_inc_v1 = 32'h0B6B1CEC;   // D#3 k=14 f=2177.89Hz
        5'd14: pt_inc_v1 = 32'h0C3BE822;   // D#3 k=15 f=2333.45Hz
        default: pt_inc_v1 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v1 = 16'd3326;   // D#3 k=1 f=155.56Hz
        5'd1: pt_env0_v1 = 16'd2554;   // D#3 k=2 f=311.13Hz
        5'd2: pt_env0_v1 = 16'd1361;   // D#3 k=3 f=466.69Hz
        5'd3: pt_env0_v1 = 16'd174;   // D#3 k=4 f=622.25Hz
        5'd4: pt_env0_v1 = 16'd156;   // D#3 k=5 f=777.82Hz
        5'd5: pt_env0_v1 = 16'd290;   // D#3 k=6 f=933.38Hz
        5'd6: pt_env0_v1 = 16'd85;   // D#3 k=7 f=1088.94Hz
        5'd7: pt_env0_v1 = 16'd9;   // D#3 k=8 f=1244.51Hz
        5'd8: pt_env0_v1 = 16'd88;   // D#3 k=9 f=1400.07Hz
        5'd9: pt_env0_v1 = 16'd252;   // D#3 k=10 f=1555.63Hz
        5'd10: pt_env0_v1 = 16'd11;   // D#3 k=11 f=1711.20Hz
        5'd11: pt_env0_v1 = 16'd21;   // D#3 k=12 f=1866.76Hz
        5'd12: pt_env0_v1 = 16'd44;   // D#3 k=13 f=2022.33Hz
        5'd13: pt_env0_v1 = 16'd19;   // D#3 k=14 f=2177.89Hz
        5'd14: pt_env0_v1 = 16'd8;   // D#3 k=15 f=2333.45Hz
        default: pt_env0_v1 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v1 = 12'd55;   // D#3 k=1 f=155.56Hz
        5'd1: pt_rate_v1 = 12'd104;   // D#3 k=2 f=311.13Hz
        5'd2: pt_rate_v1 = 12'd81;   // D#3 k=3 f=466.69Hz
        5'd3: pt_rate_v1 = 12'd213;   // D#3 k=4 f=622.25Hz
        5'd4: pt_rate_v1 = 12'd115;   // D#3 k=5 f=777.82Hz
        5'd5: pt_rate_v1 = 12'd144;   // D#3 k=6 f=933.38Hz
        5'd6: pt_rate_v1 = 12'd241;   // D#3 k=7 f=1088.94Hz
        5'd7: pt_rate_v1 = 12'd106;   // D#3 k=8 f=1244.51Hz
        5'd8: pt_rate_v1 = 12'd306;   // D#3 k=9 f=1400.07Hz
        5'd9: pt_rate_v1 = 12'd306;   // D#3 k=10 f=1555.63Hz
        5'd10: pt_rate_v1 = 12'd34;   // D#3 k=11 f=1711.20Hz
        5'd11: pt_rate_v1 = 12'd138;   // D#3 k=12 f=1866.76Hz
        5'd12: pt_rate_v1 = 12'd99;   // D#3 k=13 f=2022.33Hz
        5'd13: pt_rate_v1 = 12'd98;   // D#3 k=14 f=2177.89Hz
        5'd14: pt_rate_v1 = 12'd70;   // D#3 k=15 f=2333.45Hz
        default: pt_rate_v1 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v1 = 16'd20376;   // D#3 k=1 f=155.56Hz
        5'd1: pt_aatk_v1 = 16'd22950;   // D#3 k=2 f=311.13Hz
        5'd2: pt_aatk_v1 = 16'd27987;   // D#3 k=3 f=466.69Hz
        5'd3: pt_aatk_v1 = 16'd25050;   // D#3 k=4 f=622.25Hz
        5'd4: pt_aatk_v1 = 16'd31341;   // D#3 k=5 f=777.82Hz
        5'd5: pt_aatk_v1 = 16'd31916;   // D#3 k=6 f=933.38Hz
        5'd6: pt_aatk_v1 = 16'd29959;   // D#3 k=7 f=1088.94Hz
        5'd7: pt_aatk_v1 = 16'd37770;   // D#3 k=8 f=1244.51Hz
        5'd8: pt_aatk_v1 = 16'd30979;   // D#3 k=9 f=1400.07Hz
        5'd9: pt_aatk_v1 = 16'd32237;   // D#3 k=10 f=1555.63Hz
        5'd10: pt_aatk_v1 = 16'd51616;   // D#3 k=11 f=1711.20Hz
        5'd11: pt_aatk_v1 = 16'd41425;   // D#3 k=12 f=1866.76Hz
        5'd12: pt_aatk_v1 = 16'd45592;   // D#3 k=13 f=2022.33Hz
        5'd13: pt_aatk_v1 = 16'd46915;   // D#3 k=14 f=2177.89Hz
        5'd14: pt_aatk_v1 = 16'd51120;   // D#3 k=15 f=2333.45Hz
        default: pt_aatk_v1 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v1 = 32'h43E1465F;   // D#3 k=1 f=155.56Hz
        5'd1: pt_ph0_v1 = 32'h0E78EA8A;   // D#3 k=2 f=311.13Hz
        5'd2: pt_ph0_v1 = 32'h761DC0DE;   // D#3 k=3 f=466.69Hz
        5'd3: pt_ph0_v1 = 32'h0ECA9C7D;   // D#3 k=4 f=622.25Hz
        5'd4: pt_ph0_v1 = 32'hF5E7493F;   // D#3 k=5 f=777.82Hz
        5'd5: pt_ph0_v1 = 32'h84D44CBF;   // D#3 k=6 f=933.38Hz
        5'd6: pt_ph0_v1 = 32'hA536E9DE;   // D#3 k=7 f=1088.94Hz
        5'd7: pt_ph0_v1 = 32'h79AFAED8;   // D#3 k=8 f=1244.51Hz
        5'd8: pt_ph0_v1 = 32'h02E9F4A2;   // D#3 k=9 f=1400.07Hz
        5'd9: pt_ph0_v1 = 32'hB3C8F91C;   // D#3 k=10 f=1555.63Hz
        5'd10: pt_ph0_v1 = 32'h318EC249;   // D#3 k=11 f=1711.20Hz
        5'd11: pt_ph0_v1 = 32'hD13543EA;   // D#3 k=12 f=1866.76Hz
        5'd12: pt_ph0_v1 = 32'h504FD68E;   // D#3 k=13 f=2022.33Hz
        5'd13: pt_ph0_v1 = 32'hF9563BE1;   // D#3 k=14 f=2177.89Hz
        5'd14: pt_ph0_v1 = 32'hFB6A9A74;   // D#3 k=15 f=2333.45Hz
        default: pt_ph0_v1 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v2 = 32'h00DD3595;   // E3 k=1 f=164.81Hz
        5'd1: pt_inc_v2 = 32'h01BA6B2B;   // E3 k=2 f=329.63Hz
        5'd2: pt_inc_v2 = 32'h0297A0C0;   // E3 k=3 f=494.44Hz
        5'd3: pt_inc_v2 = 32'h0374D655;   // E3 k=4 f=659.26Hz
        5'd4: pt_inc_v2 = 32'h04520BEA;   // E3 k=5 f=824.07Hz
        5'd5: pt_inc_v2 = 32'h052F4180;   // E3 k=6 f=988.88Hz
        5'd6: pt_inc_v2 = 32'h060C7715;   // E3 k=7 f=1153.70Hz
        5'd7: pt_inc_v2 = 32'h06E9ACAA;   // E3 k=8 f=1318.51Hz
        5'd8: pt_inc_v2 = 32'h07C6E23F;   // E3 k=9 f=1483.32Hz
        5'd10: pt_inc_v2 = 32'h09814D6A;   // E3 k=11 f=1812.95Hz
        5'd11: pt_inc_v2 = 32'h0A5E82FF;   // E3 k=12 f=1977.77Hz
        5'd13: pt_inc_v2 = 32'h0C18EE2A;   // E3 k=14 f=2307.39Hz
        default: pt_inc_v2 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v2 = 16'd3052;   // E3 k=1 f=164.81Hz
        5'd1: pt_env0_v2 = 16'd2887;   // E3 k=2 f=329.63Hz
        5'd2: pt_env0_v2 = 16'd765;   // E3 k=3 f=494.44Hz
        5'd3: pt_env0_v2 = 16'd534;   // E3 k=4 f=659.26Hz
        5'd4: pt_env0_v2 = 16'd301;   // E3 k=5 f=824.07Hz
        5'd5: pt_env0_v2 = 16'd571;   // E3 k=6 f=988.88Hz
        5'd6: pt_env0_v2 = 16'd17;   // E3 k=7 f=1153.70Hz
        5'd7: pt_env0_v2 = 16'd157;   // E3 k=8 f=1318.51Hz
        5'd8: pt_env0_v2 = 16'd31;   // E3 k=9 f=1483.32Hz
        5'd10: pt_env0_v2 = 16'd139;   // E3 k=11 f=1812.95Hz
        5'd11: pt_env0_v2 = 16'd16;   // E3 k=12 f=1977.77Hz
        5'd13: pt_env0_v2 = 16'd9;   // E3 k=14 f=2307.39Hz
        default: pt_env0_v2 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v2 = 12'd87;   // E3 k=1 f=164.81Hz
        5'd1: pt_rate_v2 = 12'd84;   // E3 k=2 f=329.63Hz
        5'd2: pt_rate_v2 = 12'd133;   // E3 k=3 f=494.44Hz
        5'd3: pt_rate_v2 = 12'd292;   // E3 k=4 f=659.26Hz
        5'd4: pt_rate_v2 = 12'd321;   // E3 k=5 f=824.07Hz
        5'd5: pt_rate_v2 = 12'd276;   // E3 k=6 f=988.88Hz
        5'd6: pt_rate_v2 = 12'd154;   // E3 k=7 f=1153.70Hz
        5'd7: pt_rate_v2 = 12'd272;   // E3 k=8 f=1318.51Hz
        5'd8: pt_rate_v2 = 12'd113;   // E3 k=9 f=1483.32Hz
        5'd10: pt_rate_v2 = 12'd402;   // E3 k=11 f=1812.95Hz
        5'd11: pt_rate_v2 = 12'd103;   // E3 k=12 f=1977.77Hz
        5'd13: pt_rate_v2 = 12'd106;   // E3 k=14 f=2307.39Hz
        default: pt_rate_v2 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v2 = 16'd18507;   // E3 k=1 f=164.81Hz
        5'd1: pt_aatk_v2 = 16'd23992;   // E3 k=2 f=329.63Hz
        5'd2: pt_aatk_v2 = 16'd25194;   // E3 k=3 f=494.44Hz
        5'd3: pt_aatk_v2 = 16'd23043;   // E3 k=4 f=659.26Hz
        5'd4: pt_aatk_v2 = 16'd24459;   // E3 k=5 f=824.07Hz
        5'd5: pt_aatk_v2 = 16'd27285;   // E3 k=6 f=988.88Hz
        5'd6: pt_aatk_v2 = 16'd33274;   // E3 k=7 f=1153.70Hz
        5'd7: pt_aatk_v2 = 16'd30532;   // E3 k=8 f=1318.51Hz
        5'd8: pt_aatk_v2 = 16'd38935;   // E3 k=9 f=1483.32Hz
        5'd10: pt_aatk_v2 = 16'd31069;   // E3 k=11 f=1812.95Hz
        5'd11: pt_aatk_v2 = 16'd43955;   // E3 k=12 f=1977.77Hz
        5'd13: pt_aatk_v2 = 16'd46223;   // E3 k=14 f=2307.39Hz
        default: pt_aatk_v2 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v2 = 32'hACAD82A6;   // E3 k=1 f=164.81Hz
        5'd1: pt_ph0_v2 = 32'h83D0D79A;   // E3 k=2 f=329.63Hz
        5'd2: pt_ph0_v2 = 32'hBD58BA6B;   // E3 k=3 f=494.44Hz
        5'd3: pt_ph0_v2 = 32'hE8A46341;   // E3 k=4 f=659.26Hz
        5'd4: pt_ph0_v2 = 32'hFD4255C7;   // E3 k=5 f=824.07Hz
        5'd5: pt_ph0_v2 = 32'h48A7297A;   // E3 k=6 f=988.88Hz
        5'd6: pt_ph0_v2 = 32'h1C8FC87E;   // E3 k=7 f=1153.70Hz
        5'd7: pt_ph0_v2 = 32'h558F2D7E;   // E3 k=8 f=1318.51Hz
        5'd8: pt_ph0_v2 = 32'hB43618AE;   // E3 k=9 f=1483.32Hz
        5'd10: pt_ph0_v2 = 32'h935F84DF;   // E3 k=11 f=1812.95Hz
        5'd11: pt_ph0_v2 = 32'h1B4EF29D;   // E3 k=12 f=1977.77Hz
        5'd13: pt_ph0_v2 = 32'h66BB3273;   // E3 k=14 f=2307.39Hz
        default: pt_ph0_v2 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v3 = 32'h00EA5CF4;   // F3 k=1 f=174.61Hz
        5'd1: pt_inc_v3 = 32'h01D4B9E8;   // F3 k=2 f=349.23Hz
        5'd2: pt_inc_v3 = 32'h02BF16DC;   // F3 k=3 f=523.84Hz
        5'd3: pt_inc_v3 = 32'h03A973D0;   // F3 k=4 f=698.46Hz
        5'd4: pt_inc_v3 = 32'h0493D0C4;   // F3 k=5 f=873.07Hz
        5'd5: pt_inc_v3 = 32'h057E2DB8;   // F3 k=6 f=1047.68Hz
        5'd6: pt_inc_v3 = 32'h06688AAC;   // F3 k=7 f=1222.30Hz
        5'd7: pt_inc_v3 = 32'h0752E7A0;   // F3 k=8 f=1396.91Hz
        5'd8: pt_inc_v3 = 32'h083D4494;   // F3 k=9 f=1571.53Hz
        5'd9: pt_inc_v3 = 32'h0927A188;   // F3 k=10 f=1746.14Hz
        5'd10: pt_inc_v3 = 32'h0A11FE7D;   // F3 k=11 f=1920.76Hz
        5'd11: pt_inc_v3 = 32'h0AFC5B71;   // F3 k=12 f=2095.37Hz
        5'd12: pt_inc_v3 = 32'h0BE6B865;   // F3 k=13 f=2269.98Hz
        5'd13: pt_inc_v3 = 32'h0CD11559;   // F3 k=14 f=2444.60Hz
        5'd14: pt_inc_v3 = 32'h0DBB724D;   // F3 k=15 f=2619.21Hz
        default: pt_inc_v3 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v3 = 16'd2455;   // F3 k=1 f=174.61Hz
        5'd1: pt_env0_v3 = 16'd499;   // F3 k=2 f=349.23Hz
        5'd2: pt_env0_v3 = 16'd210;   // F3 k=3 f=523.84Hz
        5'd3: pt_env0_v3 = 16'd1131;   // F3 k=4 f=698.46Hz
        5'd4: pt_env0_v3 = 16'd238;   // F3 k=5 f=873.07Hz
        5'd5: pt_env0_v3 = 16'd205;   // F3 k=6 f=1047.68Hz
        5'd6: pt_env0_v3 = 16'd231;   // F3 k=7 f=1222.30Hz
        5'd7: pt_env0_v3 = 16'd743;   // F3 k=8 f=1396.91Hz
        5'd8: pt_env0_v3 = 16'd1407;   // F3 k=9 f=1571.53Hz
        5'd9: pt_env0_v3 = 16'd557;   // F3 k=10 f=1746.14Hz
        5'd10: pt_env0_v3 = 16'd611;   // F3 k=11 f=1920.76Hz
        5'd11: pt_env0_v3 = 16'd407;   // F3 k=12 f=2095.37Hz
        5'd12: pt_env0_v3 = 16'd143;   // F3 k=13 f=2269.98Hz
        5'd13: pt_env0_v3 = 16'd116;   // F3 k=14 f=2444.60Hz
        5'd14: pt_env0_v3 = 16'd64;   // F3 k=15 f=2619.21Hz
        default: pt_env0_v3 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v3 = 12'd462;   // F3 k=1 f=174.61Hz
        5'd1: pt_rate_v3 = 12'd53;   // F3 k=2 f=349.23Hz
        5'd2: pt_rate_v3 = 12'd116;   // F3 k=3 f=523.84Hz
        5'd3: pt_rate_v3 = 12'd364;   // F3 k=4 f=698.46Hz
        5'd4: pt_rate_v3 = 12'd151;   // F3 k=5 f=873.07Hz
        5'd5: pt_rate_v3 = 12'd130;   // F3 k=6 f=1047.68Hz
        5'd6: pt_rate_v3 = 12'd203;   // F3 k=7 f=1222.30Hz
        5'd7: pt_rate_v3 = 12'd285;   // F3 k=8 f=1396.91Hz
        5'd8: pt_rate_v3 = 12'd476;   // F3 k=9 f=1571.53Hz
        5'd9: pt_rate_v3 = 12'd460;   // F3 k=10 f=1746.14Hz
        5'd10: pt_rate_v3 = 12'd491;   // F3 k=11 f=1920.76Hz
        5'd11: pt_rate_v3 = 12'd496;   // F3 k=12 f=2095.37Hz
        5'd12: pt_rate_v3 = 12'd257;   // F3 k=13 f=2269.98Hz
        5'd13: pt_rate_v3 = 12'd262;   // F3 k=14 f=2444.60Hz
        5'd14: pt_rate_v3 = 12'd457;   // F3 k=15 f=2619.21Hz
        default: pt_rate_v3 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v3 = 16'd11514;   // F3 k=1 f=174.61Hz
        5'd1: pt_aatk_v3 = 16'd26288;   // F3 k=2 f=349.23Hz
        5'd2: pt_aatk_v3 = 16'd25956;   // F3 k=3 f=523.84Hz
        5'd3: pt_aatk_v3 = 16'd21634;   // F3 k=4 f=698.46Hz
        5'd4: pt_aatk_v3 = 16'd29529;   // F3 k=5 f=873.07Hz
        5'd5: pt_aatk_v3 = 16'd32609;   // F3 k=6 f=1047.68Hz
        5'd6: pt_aatk_v3 = 16'd31231;   // F3 k=7 f=1222.30Hz
        5'd7: pt_aatk_v3 = 16'd30179;   // F3 k=8 f=1396.91Hz
        5'd8: pt_aatk_v3 = 16'd27397;   // F3 k=9 f=1571.53Hz
        5'd9: pt_aatk_v3 = 16'd28831;   // F3 k=10 f=1746.14Hz
        5'd10: pt_aatk_v3 = 16'd29355;   // F3 k=11 f=1920.76Hz
        5'd11: pt_aatk_v3 = 16'd30271;   // F3 k=12 f=2095.37Hz
        5'd12: pt_aatk_v3 = 16'd37129;   // F3 k=13 f=2269.98Hz
        5'd13: pt_aatk_v3 = 16'd38015;   // F3 k=14 f=2444.60Hz
        5'd14: pt_aatk_v3 = 16'd33772;   // F3 k=15 f=2619.21Hz
        default: pt_aatk_v3 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v3 = 32'h1E5F1329;   // F3 k=1 f=174.61Hz
        5'd1: pt_ph0_v3 = 32'h7B73EBB4;   // F3 k=2 f=349.23Hz
        5'd2: pt_ph0_v3 = 32'hC6A87E76;   // F3 k=3 f=523.84Hz
        5'd3: pt_ph0_v3 = 32'hE5BD8265;   // F3 k=4 f=698.46Hz
        5'd4: pt_ph0_v3 = 32'hEBD01B3D;   // F3 k=5 f=873.07Hz
        5'd5: pt_ph0_v3 = 32'hFE4E23A6;   // F3 k=6 f=1047.68Hz
        5'd6: pt_ph0_v3 = 32'h7D6529DB;   // F3 k=7 f=1222.30Hz
        5'd7: pt_ph0_v3 = 32'hD39A9B74;   // F3 k=8 f=1396.91Hz
        5'd8: pt_ph0_v3 = 32'h9E7F3ACE;   // F3 k=9 f=1571.53Hz
        5'd9: pt_ph0_v3 = 32'h5DFE0DFD;   // F3 k=10 f=1746.14Hz
        5'd10: pt_ph0_v3 = 32'h147D987D;   // F3 k=11 f=1920.76Hz
        5'd11: pt_ph0_v3 = 32'h493F1344;   // F3 k=12 f=2095.37Hz
        5'd12: pt_ph0_v3 = 32'hC1AF1B0F;   // F3 k=13 f=2269.98Hz
        5'd13: pt_ph0_v3 = 32'h7B13A768;   // F3 k=14 f=2444.60Hz
        5'd14: pt_ph0_v3 = 32'hF02AB277;   // F3 k=15 f=2619.21Hz
        default: pt_ph0_v3 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v4 = 32'h00F84C8F;   // F#3 k=1 f=185.00Hz
        5'd1: pt_inc_v4 = 32'h01F0991E;   // F#3 k=2 f=369.99Hz
        5'd2: pt_inc_v4 = 32'h02E8E5AC;   // F#3 k=3 f=554.99Hz
        5'd3: pt_inc_v4 = 32'h03E1323B;   // F#3 k=4 f=739.99Hz
        5'd4: pt_inc_v4 = 32'h04D97ECA;   // F#3 k=5 f=924.99Hz
        5'd5: pt_inc_v4 = 32'h05D1CB59;   // F#3 k=6 f=1109.98Hz
        5'd6: pt_inc_v4 = 32'h06CA17E8;   // F#3 k=7 f=1294.98Hz
        5'd7: pt_inc_v4 = 32'h07C26476;   // F#3 k=8 f=1479.98Hz
        5'd8: pt_inc_v4 = 32'h08BAB105;   // F#3 k=9 f=1664.97Hz
        5'd9: pt_inc_v4 = 32'h09B2FD94;   // F#3 k=10 f=1849.97Hz
        5'd10: pt_inc_v4 = 32'h0AAB4A23;   // F#3 k=11 f=2034.97Hz
        5'd11: pt_inc_v4 = 32'h0BA396B2;   // F#3 k=12 f=2219.97Hz
        5'd12: pt_inc_v4 = 32'h0C9BE340;   // F#3 k=13 f=2404.96Hz
        5'd14: pt_inc_v4 = 32'h0E8C7C5E;   // F#3 k=15 f=2774.96Hz
        default: pt_inc_v4 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v4 = 16'd4194;   // F#3 k=1 f=185.00Hz
        5'd1: pt_env0_v4 = 16'd1615;   // F#3 k=2 f=369.99Hz
        5'd2: pt_env0_v4 = 16'd239;   // F#3 k=3 f=554.99Hz
        5'd3: pt_env0_v4 = 16'd571;   // F#3 k=4 f=739.99Hz
        5'd4: pt_env0_v4 = 16'd491;   // F#3 k=5 f=924.99Hz
        5'd5: pt_env0_v4 = 16'd241;   // F#3 k=6 f=1109.98Hz
        5'd6: pt_env0_v4 = 16'd185;   // F#3 k=7 f=1294.98Hz
        5'd7: pt_env0_v4 = 16'd270;   // F#3 k=8 f=1479.98Hz
        5'd8: pt_env0_v4 = 16'd45;   // F#3 k=9 f=1664.97Hz
        5'd9: pt_env0_v4 = 16'd229;   // F#3 k=10 f=1849.97Hz
        5'd10: pt_env0_v4 = 16'd260;   // F#3 k=11 f=2034.97Hz
        5'd11: pt_env0_v4 = 16'd26;   // F#3 k=12 f=2219.97Hz
        5'd12: pt_env0_v4 = 16'd153;   // F#3 k=13 f=2404.96Hz
        5'd14: pt_env0_v4 = 16'd42;   // F#3 k=15 f=2774.96Hz
        default: pt_env0_v4 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v4 = 12'd110;   // F#3 k=1 f=185.00Hz
        5'd1: pt_rate_v4 = 12'd106;   // F#3 k=2 f=369.99Hz
        5'd2: pt_rate_v4 = 12'd138;   // F#3 k=3 f=554.99Hz
        5'd3: pt_rate_v4 = 12'd158;   // F#3 k=4 f=739.99Hz
        5'd4: pt_rate_v4 = 12'd178;   // F#3 k=5 f=924.99Hz
        5'd5: pt_rate_v4 = 12'd210;   // F#3 k=6 f=1109.98Hz
        5'd6: pt_rate_v4 = 12'd307;   // F#3 k=7 f=1294.98Hz
        5'd7: pt_rate_v4 = 12'd324;   // F#3 k=8 f=1479.98Hz
        5'd8: pt_rate_v4 = 12'd462;   // F#3 k=9 f=1664.97Hz
        5'd9: pt_rate_v4 = 12'd662;   // F#3 k=10 f=1849.97Hz
        5'd10: pt_rate_v4 = 12'd719;   // F#3 k=11 f=2034.97Hz
        5'd11: pt_rate_v4 = 12'd324;   // F#3 k=12 f=2219.97Hz
        5'd12: pt_rate_v4 = 12'd564;   // F#3 k=13 f=2404.96Hz
        5'd14: pt_rate_v4 = 12'd672;   // F#3 k=15 f=2774.96Hz
        default: pt_rate_v4 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v4 = 16'd17526;   // F#3 k=1 f=185.00Hz
        5'd1: pt_aatk_v4 = 16'd22840;   // F#3 k=2 f=369.99Hz
        5'd2: pt_aatk_v4 = 16'd24965;   // F#3 k=3 f=554.99Hz
        5'd3: pt_aatk_v4 = 16'd26930;   // F#3 k=4 f=739.99Hz
        5'd4: pt_aatk_v4 = 16'd28428;   // F#3 k=5 f=924.99Hz
        5'd5: pt_aatk_v4 = 16'd29255;   // F#3 k=6 f=1109.98Hz
        5'd6: pt_aatk_v4 = 16'd28135;   // F#3 k=7 f=1294.98Hz
        5'd7: pt_aatk_v4 = 16'd29188;   // F#3 k=8 f=1479.98Hz
        5'd8: pt_aatk_v4 = 16'd27637;   // F#3 k=9 f=1664.97Hz
        5'd9: pt_aatk_v4 = 16'd25767;   // F#3 k=10 f=1849.97Hz
        5'd10: pt_aatk_v4 = 16'd26044;   // F#3 k=11 f=2034.97Hz
        5'd11: pt_aatk_v4 = 16'd34017;   // F#3 k=12 f=2219.97Hz
        5'd12: pt_aatk_v4 = 16'd30057;   // F#3 k=13 f=2404.96Hz
        5'd14: pt_aatk_v4 = 16'd30117;   // F#3 k=15 f=2774.96Hz
        default: pt_aatk_v4 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v4 = 32'h6AE429E5;   // F#3 k=1 f=185.00Hz
        5'd1: pt_ph0_v4 = 32'h14C73F29;   // F#3 k=2 f=369.99Hz
        5'd2: pt_ph0_v4 = 32'h976EB1B8;   // F#3 k=3 f=554.99Hz
        5'd3: pt_ph0_v4 = 32'h6A6BB394;   // F#3 k=4 f=739.99Hz
        5'd4: pt_ph0_v4 = 32'h9F3E8E98;   // F#3 k=5 f=924.99Hz
        5'd5: pt_ph0_v4 = 32'h9358942E;   // F#3 k=6 f=1109.98Hz
        5'd6: pt_ph0_v4 = 32'hBA7F8CC0;   // F#3 k=7 f=1294.98Hz
        5'd7: pt_ph0_v4 = 32'h37128F53;   // F#3 k=8 f=1479.98Hz
        5'd8: pt_ph0_v4 = 32'hB9E359CF;   // F#3 k=9 f=1664.97Hz
        5'd9: pt_ph0_v4 = 32'h8980C4E2;   // F#3 k=10 f=1849.97Hz
        5'd10: pt_ph0_v4 = 32'hB28541EC;   // F#3 k=11 f=2034.97Hz
        5'd11: pt_ph0_v4 = 32'h4DA15AB0;   // F#3 k=12 f=2219.97Hz
        5'd12: pt_ph0_v4 = 32'hB81A50AB;   // F#3 k=13 f=2404.96Hz
        5'd14: pt_ph0_v4 = 32'hB3E67C2C;   // F#3 k=15 f=2774.96Hz
        default: pt_ph0_v4 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v5 = 32'h0107104E;   // G3 k=1 f=196.00Hz
        5'd1: pt_inc_v5 = 32'h020E209B;   // G3 k=2 f=392.00Hz
        5'd2: pt_inc_v5 = 32'h031530E9;   // G3 k=3 f=587.99Hz
        5'd3: pt_inc_v5 = 32'h041C4136;   // G3 k=4 f=783.99Hz
        5'd4: pt_inc_v5 = 32'h05235184;   // G3 k=5 f=979.99Hz
        5'd5: pt_inc_v5 = 32'h062A61D2;   // G3 k=6 f=1175.99Hz
        5'd6: pt_inc_v5 = 32'h0731721F;   // G3 k=7 f=1371.98Hz
        5'd7: pt_inc_v5 = 32'h0838826D;   // G3 k=8 f=1567.98Hz
        5'd8: pt_inc_v5 = 32'h093F92BA;   // G3 k=9 f=1763.98Hz
        5'd9: pt_inc_v5 = 32'h0A46A308;   // G3 k=10 f=1959.98Hz
        5'd10: pt_inc_v5 = 32'h0B4DB356;   // G3 k=11 f=2155.97Hz
        5'd11: pt_inc_v5 = 32'h0C54C3A3;   // G3 k=12 f=2351.97Hz
        5'd12: pt_inc_v5 = 32'h0D5BD3F1;   // G3 k=13 f=2547.97Hz
        5'd13: pt_inc_v5 = 32'h0E62E43E;   // G3 k=14 f=2743.97Hz
        5'd14: pt_inc_v5 = 32'h0F69F48C;   // G3 k=15 f=2939.97Hz
        default: pt_inc_v5 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v5 = 16'd5056;   // G3 k=1 f=196.00Hz
        5'd1: pt_env0_v5 = 16'd647;   // G3 k=2 f=392.00Hz
        5'd2: pt_env0_v5 = 16'd1079;   // G3 k=3 f=587.99Hz
        5'd3: pt_env0_v5 = 16'd256;   // G3 k=4 f=783.99Hz
        5'd4: pt_env0_v5 = 16'd187;   // G3 k=5 f=979.99Hz
        5'd5: pt_env0_v5 = 16'd114;   // G3 k=6 f=1175.99Hz
        5'd6: pt_env0_v5 = 16'd194;   // G3 k=7 f=1371.98Hz
        5'd7: pt_env0_v5 = 16'd301;   // G3 k=8 f=1567.98Hz
        5'd8: pt_env0_v5 = 16'd24;   // G3 k=9 f=1763.98Hz
        5'd9: pt_env0_v5 = 16'd124;   // G3 k=10 f=1959.98Hz
        5'd10: pt_env0_v5 = 16'd60;   // G3 k=11 f=2155.97Hz
        5'd11: pt_env0_v5 = 16'd189;   // G3 k=12 f=2351.97Hz
        5'd12: pt_env0_v5 = 16'd64;   // G3 k=13 f=2547.97Hz
        5'd13: pt_env0_v5 = 16'd29;   // G3 k=14 f=2743.97Hz
        5'd14: pt_env0_v5 = 16'd70;   // G3 k=15 f=2939.97Hz
        default: pt_env0_v5 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v5 = 12'd65;   // G3 k=1 f=196.00Hz
        5'd1: pt_rate_v5 = 12'd45;   // G3 k=2 f=392.00Hz
        5'd2: pt_rate_v5 = 12'd97;   // G3 k=3 f=587.99Hz
        5'd3: pt_rate_v5 = 12'd171;   // G3 k=4 f=783.99Hz
        5'd4: pt_rate_v5 = 12'd136;   // G3 k=5 f=979.99Hz
        5'd5: pt_rate_v5 = 12'd130;   // G3 k=6 f=1175.99Hz
        5'd6: pt_rate_v5 = 12'd136;   // G3 k=7 f=1371.98Hz
        5'd7: pt_rate_v5 = 12'd222;   // G3 k=8 f=1567.98Hz
        5'd8: pt_rate_v5 = 12'd147;   // G3 k=9 f=1763.98Hz
        5'd9: pt_rate_v5 = 12'd130;   // G3 k=10 f=1959.98Hz
        5'd10: pt_rate_v5 = 12'd346;   // G3 k=11 f=2155.97Hz
        5'd11: pt_rate_v5 = 12'd328;   // G3 k=12 f=2351.97Hz
        5'd12: pt_rate_v5 = 12'd286;   // G3 k=13 f=2547.97Hz
        5'd13: pt_rate_v5 = 12'd328;   // G3 k=14 f=2743.97Hz
        5'd14: pt_rate_v5 = 12'd306;   // G3 k=15 f=2939.97Hz
        default: pt_rate_v5 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v5 = 16'd19725;   // G3 k=1 f=196.00Hz
        5'd1: pt_aatk_v5 = 16'd27094;   // G3 k=2 f=392.00Hz
        5'd2: pt_aatk_v5 = 16'd27010;   // G3 k=3 f=587.99Hz
        5'd3: pt_aatk_v5 = 16'd26412;   // G3 k=4 f=783.99Hz
        5'd4: pt_aatk_v5 = 16'd30231;   // G3 k=5 f=979.99Hz
        5'd5: pt_aatk_v5 = 16'd32631;   // G3 k=6 f=1175.99Hz
        5'd6: pt_aatk_v5 = 16'd34170;   // G3 k=7 f=1371.98Hz
        5'd7: pt_aatk_v5 = 16'd32102;   // G3 k=8 f=1567.98Hz
        5'd8: pt_aatk_v5 = 16'd36832;   // G3 k=9 f=1763.98Hz
        5'd9: pt_aatk_v5 = 16'd39263;   // G3 k=10 f=1959.98Hz
        5'd10: pt_aatk_v5 = 16'd32359;   // G3 k=11 f=2155.97Hz
        5'd11: pt_aatk_v5 = 16'd33911;   // G3 k=12 f=2351.97Hz
        5'd12: pt_aatk_v5 = 16'd36199;   // G3 k=13 f=2547.97Hz
        5'd13: pt_aatk_v5 = 16'd35938;   // G3 k=14 f=2743.97Hz
        5'd14: pt_aatk_v5 = 16'd37554;   // G3 k=15 f=2939.97Hz
        default: pt_aatk_v5 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v5 = 32'hF01B81BD;   // G3 k=1 f=196.00Hz
        5'd1: pt_ph0_v5 = 32'h85A054CB;   // G3 k=2 f=392.00Hz
        5'd2: pt_ph0_v5 = 32'h681719B7;   // G3 k=3 f=587.99Hz
        5'd3: pt_ph0_v5 = 32'hEF1638C7;   // G3 k=4 f=783.99Hz
        5'd4: pt_ph0_v5 = 32'h29D754C0;   // G3 k=5 f=979.99Hz
        5'd5: pt_ph0_v5 = 32'hAAA99987;   // G3 k=6 f=1175.99Hz
        5'd6: pt_ph0_v5 = 32'hAABF9C2B;   // G3 k=7 f=1371.98Hz
        5'd7: pt_ph0_v5 = 32'h7E60C676;   // G3 k=8 f=1567.98Hz
        5'd8: pt_ph0_v5 = 32'hB3689101;   // G3 k=9 f=1763.98Hz
        5'd9: pt_ph0_v5 = 32'h8854D505;   // G3 k=10 f=1959.98Hz
        5'd10: pt_ph0_v5 = 32'h4C7BF39F;   // G3 k=11 f=2155.97Hz
        5'd11: pt_ph0_v5 = 32'h730959FB;   // G3 k=12 f=2351.97Hz
        5'd12: pt_ph0_v5 = 32'h5EF4A9E0;   // G3 k=13 f=2547.97Hz
        5'd13: pt_ph0_v5 = 32'hB2D14C84;   // G3 k=14 f=2743.97Hz
        5'd14: pt_ph0_v5 = 32'hF371A5A4;   // G3 k=15 f=2939.97Hz
        default: pt_ph0_v5 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v6 = 32'h0116B4CE;   // G#3 k=1 f=207.65Hz
        5'd1: pt_inc_v6 = 32'h022D699C;   // G#3 k=2 f=415.30Hz
        5'd2: pt_inc_v6 = 32'h03441E69;   // G#3 k=3 f=622.96Hz
        5'd3: pt_inc_v6 = 32'h045AD337;   // G#3 k=4 f=830.61Hz
        5'd4: pt_inc_v6 = 32'h05718805;   // G#3 k=5 f=1038.26Hz
        5'd6: pt_inc_v6 = 32'h079EF1A0;   // G#3 k=7 f=1453.57Hz
        5'd7: pt_inc_v6 = 32'h08B5A66E;   // G#3 k=8 f=1661.22Hz
        5'd8: pt_inc_v6 = 32'h09CC5B3C;   // G#3 k=9 f=1868.87Hz
        5'd9: pt_inc_v6 = 32'h0AE3100A;   // G#3 k=10 f=2076.52Hz
        5'd10: pt_inc_v6 = 32'h0BF9C4D7;   // G#3 k=11 f=2284.18Hz
        5'd12: pt_inc_v6 = 32'h0E272E73;   // G#3 k=13 f=2699.48Hz
        5'd13: pt_inc_v6 = 32'h0F3DE341;   // G#3 k=14 f=2907.13Hz
        5'd14: pt_inc_v6 = 32'h1054980E;   // G#3 k=15 f=3114.79Hz
        default: pt_inc_v6 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v6 = 16'd7213;   // G#3 k=1 f=207.65Hz
        5'd1: pt_env0_v6 = 16'd535;   // G#3 k=2 f=415.30Hz
        5'd2: pt_env0_v6 = 16'd268;   // G#3 k=3 f=622.96Hz
        5'd3: pt_env0_v6 = 16'd21;   // G#3 k=4 f=830.61Hz
        5'd4: pt_env0_v6 = 16'd169;   // G#3 k=5 f=1038.26Hz
        5'd6: pt_env0_v6 = 16'd40;   // G#3 k=7 f=1453.57Hz
        5'd7: pt_env0_v6 = 16'd10;   // G#3 k=8 f=1661.22Hz
        5'd8: pt_env0_v6 = 16'd109;   // G#3 k=9 f=1868.87Hz
        5'd9: pt_env0_v6 = 16'd36;   // G#3 k=10 f=2076.52Hz
        5'd10: pt_env0_v6 = 16'd25;   // G#3 k=11 f=2284.18Hz
        5'd12: pt_env0_v6 = 16'd10;   // G#3 k=13 f=2699.48Hz
        5'd13: pt_env0_v6 = 16'd12;   // G#3 k=14 f=2907.13Hz
        5'd14: pt_env0_v6 = 16'd17;   // G#3 k=15 f=3114.79Hz
        default: pt_env0_v6 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v6 = 12'd98;   // G#3 k=1 f=207.65Hz
        5'd1: pt_rate_v6 = 12'd38;   // G#3 k=2 f=415.30Hz
        5'd2: pt_rate_v6 = 12'd90;   // G#3 k=3 f=622.96Hz
        5'd3: pt_rate_v6 = 12'd84;   // G#3 k=4 f=830.61Hz
        5'd4: pt_rate_v6 = 12'd234;   // G#3 k=5 f=1038.26Hz
        5'd6: pt_rate_v6 = 12'd207;   // G#3 k=7 f=1453.57Hz
        5'd7: pt_rate_v6 = 12'd86;   // G#3 k=8 f=1661.22Hz
        5'd8: pt_rate_v6 = 12'd281;   // G#3 k=9 f=1868.87Hz
        5'd9: pt_rate_v6 = 12'd253;   // G#3 k=10 f=2076.52Hz
        5'd10: pt_rate_v6 = 12'd338;   // G#3 k=11 f=2284.18Hz
        5'd12: pt_rate_v6 = 12'd299;   // G#3 k=13 f=2699.48Hz
        5'd13: pt_rate_v6 = 12'd334;   // G#3 k=14 f=2907.13Hz
        5'd14: pt_rate_v6 = 12'd424;   // G#3 k=15 f=3114.79Hz
        default: pt_rate_v6 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v6 = 16'd18005;   // G#3 k=1 f=207.65Hz
        5'd1: pt_aatk_v6 = 16'd27993;   // G#3 k=2 f=415.30Hz
        5'd2: pt_aatk_v6 = 16'd27394;   // G#3 k=3 f=622.96Hz
        5'd3: pt_aatk_v6 = 16'd30822;   // G#3 k=4 f=830.61Hz
        5'd4: pt_aatk_v6 = 16'd26601;   // G#3 k=5 f=1038.26Hz
        5'd6: pt_aatk_v6 = 16'd31089;   // G#3 k=7 f=1453.57Hz
        5'd7: pt_aatk_v6 = 16'd39331;   // G#3 k=8 f=1661.22Hz
        5'd8: pt_aatk_v6 = 16'd31658;   // G#3 k=9 f=1868.87Hz
        5'd9: pt_aatk_v6 = 16'd33817;   // G#3 k=10 f=2076.52Hz
        5'd10: pt_aatk_v6 = 16'd32551;   // G#3 k=11 f=2284.18Hz
        5'd12: pt_aatk_v6 = 16'd35796;   // G#3 k=13 f=2699.48Hz
        5'd13: pt_aatk_v6 = 16'd35767;   // G#3 k=14 f=2907.13Hz
        5'd14: pt_aatk_v6 = 16'd34483;   // G#3 k=15 f=3114.79Hz
        default: pt_aatk_v6 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v6 = 32'h3F6D8E86;   // G#3 k=1 f=207.65Hz
        5'd1: pt_ph0_v6 = 32'h591C32D8;   // G#3 k=2 f=415.30Hz
        5'd2: pt_ph0_v6 = 32'h37ACF21B;   // G#3 k=3 f=622.96Hz
        5'd3: pt_ph0_v6 = 32'h94171B6C;   // G#3 k=4 f=830.61Hz
        5'd4: pt_ph0_v6 = 32'h982EBAF1;   // G#3 k=5 f=1038.26Hz
        5'd6: pt_ph0_v6 = 32'hA1671469;   // G#3 k=7 f=1453.57Hz
        5'd7: pt_ph0_v6 = 32'h3EA8A61C;   // G#3 k=8 f=1661.22Hz
        5'd8: pt_ph0_v6 = 32'h670D5609;   // G#3 k=9 f=1868.87Hz
        5'd9: pt_ph0_v6 = 32'h744E0D0F;   // G#3 k=10 f=2076.52Hz
        5'd10: pt_ph0_v6 = 32'h081948F8;   // G#3 k=11 f=2284.18Hz
        5'd12: pt_ph0_v6 = 32'h01CD571B;   // G#3 k=13 f=2699.48Hz
        5'd13: pt_ph0_v6 = 32'hCEE2330C;   // G#3 k=14 f=2907.13Hz
        5'd14: pt_ph0_v6 = 32'h98FD1EED;   // G#3 k=15 f=3114.79Hz
        default: pt_ph0_v6 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v7 = 32'h0127476D;   // A3 k=1 f=220.00Hz
        5'd1: pt_inc_v7 = 32'h024E8ED9;   // A3 k=2 f=440.00Hz
        5'd2: pt_inc_v7 = 32'h0375D646;   // A3 k=3 f=660.00Hz
        5'd3: pt_inc_v7 = 32'h049D1DB3;   // A3 k=4 f=880.00Hz
        5'd4: pt_inc_v7 = 32'h05C4651F;   // A3 k=5 f=1100.00Hz
        5'd5: pt_inc_v7 = 32'h06EBAC8C;   // A3 k=6 f=1320.00Hz
        5'd6: pt_inc_v7 = 32'h0812F3F9;   // A3 k=7 f=1540.00Hz
        5'd7: pt_inc_v7 = 32'h093A3B65;   // A3 k=8 f=1760.00Hz
        5'd8: pt_inc_v7 = 32'h0A6182D2;   // A3 k=9 f=1980.00Hz
        5'd9: pt_inc_v7 = 32'h0B88CA3E;   // A3 k=10 f=2200.00Hz
        5'd10: pt_inc_v7 = 32'h0CB011AB;   // A3 k=11 f=2420.00Hz
        5'd11: pt_inc_v7 = 32'h0DD75918;   // A3 k=12 f=2640.00Hz
        5'd12: pt_inc_v7 = 32'h0EFEA084;   // A3 k=13 f=2860.00Hz
        5'd13: pt_inc_v7 = 32'h1025E7F1;   // A3 k=14 f=3080.00Hz
        default: pt_inc_v7 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v7 = 16'd5545;   // A3 k=1 f=220.00Hz
        5'd1: pt_env0_v7 = 16'd408;   // A3 k=2 f=440.00Hz
        5'd2: pt_env0_v7 = 16'd977;   // A3 k=3 f=660.00Hz
        5'd3: pt_env0_v7 = 16'd139;   // A3 k=4 f=880.00Hz
        5'd4: pt_env0_v7 = 16'd160;   // A3 k=5 f=1100.00Hz
        5'd5: pt_env0_v7 = 16'd289;   // A3 k=6 f=1320.00Hz
        5'd6: pt_env0_v7 = 16'd441;   // A3 k=7 f=1540.00Hz
        5'd7: pt_env0_v7 = 16'd102;   // A3 k=8 f=1760.00Hz
        5'd8: pt_env0_v7 = 16'd140;   // A3 k=9 f=1980.00Hz
        5'd9: pt_env0_v7 = 16'd86;   // A3 k=10 f=2200.00Hz
        5'd10: pt_env0_v7 = 16'd72;   // A3 k=11 f=2420.00Hz
        5'd11: pt_env0_v7 = 16'd53;   // A3 k=12 f=2640.00Hz
        5'd12: pt_env0_v7 = 16'd109;   // A3 k=13 f=2860.00Hz
        5'd13: pt_env0_v7 = 16'd78;   // A3 k=14 f=3080.00Hz
        default: pt_env0_v7 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v7 = 12'd170;   // A3 k=1 f=220.00Hz
        5'd1: pt_rate_v7 = 12'd32;   // A3 k=2 f=440.00Hz
        5'd2: pt_rate_v7 = 12'd80;   // A3 k=3 f=660.00Hz
        5'd3: pt_rate_v7 = 12'd107;   // A3 k=4 f=880.00Hz
        5'd4: pt_rate_v7 = 12'd74;   // A3 k=5 f=1100.00Hz
        5'd5: pt_rate_v7 = 12'd167;   // A3 k=6 f=1320.00Hz
        5'd6: pt_rate_v7 = 12'd181;   // A3 k=7 f=1540.00Hz
        5'd7: pt_rate_v7 = 12'd168;   // A3 k=8 f=1760.00Hz
        5'd8: pt_rate_v7 = 12'd231;   // A3 k=9 f=1980.00Hz
        5'd9: pt_rate_v7 = 12'd237;   // A3 k=10 f=2200.00Hz
        5'd10: pt_rate_v7 = 12'd180;   // A3 k=11 f=2420.00Hz
        5'd11: pt_rate_v7 = 12'd467;   // A3 k=12 f=2640.00Hz
        5'd12: pt_rate_v7 = 12'd393;   // A3 k=13 f=2860.00Hz
        5'd13: pt_rate_v7 = 12'd383;   // A3 k=14 f=3080.00Hz
        default: pt_rate_v7 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v7 = 16'd15730;   // A3 k=1 f=220.00Hz
        5'd1: pt_aatk_v7 = 16'd28840;   // A3 k=2 f=440.00Hz
        5'd2: pt_aatk_v7 = 16'd28056;   // A3 k=3 f=660.00Hz
        5'd3: pt_aatk_v7 = 16'd29351;   // A3 k=4 f=880.00Hz
        5'd4: pt_aatk_v7 = 16'd34230;   // A3 k=5 f=1100.00Hz
        5'd5: pt_aatk_v7 = 16'd30859;   // A3 k=6 f=1320.00Hz
        5'd6: pt_aatk_v7 = 16'd32087;   // A3 k=7 f=1540.00Hz
        5'd7: pt_aatk_v7 = 16'd34285;   // A3 k=8 f=1760.00Hz
        5'd8: pt_aatk_v7 = 16'd33227;   // A3 k=9 f=1980.00Hz
        5'd9: pt_aatk_v7 = 16'd34357;   // A3 k=10 f=2200.00Hz
        5'd10: pt_aatk_v7 = 16'd37926;   // A3 k=11 f=2420.00Hz
        5'd11: pt_aatk_v7 = 16'd30807;   // A3 k=12 f=2640.00Hz
        5'd12: pt_aatk_v7 = 16'd33328;   // A3 k=13 f=2860.00Hz
        5'd13: pt_aatk_v7 = 16'd34516;   // A3 k=14 f=3080.00Hz
        default: pt_aatk_v7 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v7 = 32'h5F34CCDD;   // A3 k=1 f=220.00Hz
        5'd1: pt_ph0_v7 = 32'h134EFECA;   // A3 k=2 f=440.00Hz
        5'd2: pt_ph0_v7 = 32'h5E6CC8A1;   // A3 k=3 f=660.00Hz
        5'd3: pt_ph0_v7 = 32'h2869E8BD;   // A3 k=4 f=880.00Hz
        5'd4: pt_ph0_v7 = 32'hBAB60242;   // A3 k=5 f=1100.00Hz
        5'd5: pt_ph0_v7 = 32'h2531989D;   // A3 k=6 f=1320.00Hz
        5'd6: pt_ph0_v7 = 32'hD264420C;   // A3 k=7 f=1540.00Hz
        5'd7: pt_ph0_v7 = 32'h1E980CDE;   // A3 k=8 f=1760.00Hz
        5'd8: pt_ph0_v7 = 32'hFF7BA8BF;   // A3 k=9 f=1980.00Hz
        5'd9: pt_ph0_v7 = 32'hC7EDBCA9;   // A3 k=10 f=2200.00Hz
        5'd10: pt_ph0_v7 = 32'h7F6C3635;   // A3 k=11 f=2420.00Hz
        5'd11: pt_ph0_v7 = 32'hCCF7B6E0;   // A3 k=12 f=2640.00Hz
        5'd12: pt_ph0_v7 = 32'h7F5ED555;   // A3 k=13 f=2860.00Hz
        5'd13: pt_ph0_v7 = 32'h1B70D24F;   // A3 k=14 f=3080.00Hz
        default: pt_ph0_v7 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v8 = 32'h0138D653;   // A#3 k=1 f=233.08Hz
        5'd1: pt_inc_v8 = 32'h0271ACA6;   // A#3 k=2 f=466.16Hz
        5'd2: pt_inc_v8 = 32'h03AA82F9;   // A#3 k=3 f=699.25Hz
        5'd3: pt_inc_v8 = 32'h04E3594C;   // A#3 k=4 f=932.33Hz
        5'd4: pt_inc_v8 = 32'h061C2F9F;   // A#3 k=5 f=1165.41Hz
        5'd5: pt_inc_v8 = 32'h075505F2;   // A#3 k=6 f=1398.49Hz
        5'd6: pt_inc_v8 = 32'h088DDC45;   // A#3 k=7 f=1631.57Hz
        5'd7: pt_inc_v8 = 32'h09C6B298;   // A#3 k=8 f=1864.66Hz
        5'd8: pt_inc_v8 = 32'h0AFF88EB;   // A#3 k=9 f=2097.74Hz
        5'd9: pt_inc_v8 = 32'h0C385F3F;   // A#3 k=10 f=2330.82Hz
        5'd10: pt_inc_v8 = 32'h0D713592;   // A#3 k=11 f=2563.90Hz
        5'd11: pt_inc_v8 = 32'h0EAA0BE5;   // A#3 k=12 f=2796.98Hz
        5'd14: pt_inc_v8 = 32'h12548EDE;   // A#3 k=15 f=3496.23Hz
        default: pt_inc_v8 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v8 = 16'd5718;   // A#3 k=1 f=233.08Hz
        5'd1: pt_env0_v8 = 16'd1635;   // A#3 k=2 f=466.16Hz
        5'd2: pt_env0_v8 = 16'd391;   // A#3 k=3 f=699.25Hz
        5'd3: pt_env0_v8 = 16'd50;   // A#3 k=4 f=932.33Hz
        5'd4: pt_env0_v8 = 16'd112;   // A#3 k=5 f=1165.41Hz
        5'd5: pt_env0_v8 = 16'd15;   // A#3 k=6 f=1398.49Hz
        5'd6: pt_env0_v8 = 16'd22;   // A#3 k=7 f=1631.57Hz
        5'd7: pt_env0_v8 = 16'd91;   // A#3 k=8 f=1864.66Hz
        5'd8: pt_env0_v8 = 16'd339;   // A#3 k=9 f=2097.74Hz
        5'd9: pt_env0_v8 = 16'd51;   // A#3 k=10 f=2330.82Hz
        5'd10: pt_env0_v8 = 16'd30;   // A#3 k=11 f=2563.90Hz
        5'd11: pt_env0_v8 = 16'd8;   // A#3 k=12 f=2796.98Hz
        5'd14: pt_env0_v8 = 16'd15;   // A#3 k=15 f=3496.23Hz
        default: pt_env0_v8 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v8 = 12'd112;   // A#3 k=1 f=233.08Hz
        5'd1: pt_rate_v8 = 12'd61;   // A#3 k=2 f=466.16Hz
        5'd2: pt_rate_v8 = 12'd102;   // A#3 k=3 f=699.25Hz
        5'd3: pt_rate_v8 = 12'd462;   // A#3 k=4 f=932.33Hz
        5'd4: pt_rate_v8 = 12'd105;   // A#3 k=5 f=1165.41Hz
        5'd5: pt_rate_v8 = 12'd346;   // A#3 k=6 f=1398.49Hz
        5'd6: pt_rate_v8 = 12'd277;   // A#3 k=7 f=1631.57Hz
        5'd7: pt_rate_v8 = 12'd209;   // A#3 k=8 f=1864.66Hz
        5'd8: pt_rate_v8 = 12'd231;   // A#3 k=9 f=2097.74Hz
        5'd9: pt_rate_v8 = 12'd210;   // A#3 k=10 f=2330.82Hz
        5'd10: pt_rate_v8 = 12'd49;   // A#3 k=11 f=2563.90Hz
        5'd11: pt_rate_v8 = 12'd54;   // A#3 k=12 f=2796.98Hz
        5'd14: pt_rate_v8 = 12'd230;   // A#3 k=15 f=3496.23Hz
        default: pt_rate_v8 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v8 = 16'd17453;   // A#3 k=1 f=233.08Hz
        5'd1: pt_aatk_v8 = 16'd25638;   // A#3 k=2 f=466.16Hz
        5'd2: pt_aatk_v8 = 16'd26700;   // A#3 k=3 f=699.25Hz
        5'd3: pt_aatk_v8 = 16'd20121;   // A#3 k=4 f=932.33Hz
        5'd4: pt_aatk_v8 = 16'd31914;   // A#3 k=5 f=1165.41Hz
        5'd5: pt_aatk_v8 = 16'd25676;   // A#3 k=6 f=1398.49Hz
        5'd6: pt_aatk_v8 = 16'd28910;   // A#3 k=7 f=1631.57Hz
        5'd7: pt_aatk_v8 = 16'd32578;   // A#3 k=8 f=1864.66Hz
        5'd8: pt_aatk_v8 = 16'd33242;   // A#3 k=9 f=2097.74Hz
        5'd9: pt_aatk_v8 = 16'd35349;   // A#3 k=10 f=2330.82Hz
        5'd10: pt_aatk_v8 = 16'd48695;   // A#3 k=11 f=2563.90Hz
        5'd11: pt_aatk_v8 = 16'd49504;   // A#3 k=12 f=2796.98Hz
        5'd14: pt_aatk_v8 = 16'd40196;   // A#3 k=15 f=3496.23Hz
        default: pt_aatk_v8 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v8 = 32'h261F68B3;   // A#3 k=1 f=233.08Hz
        5'd1: pt_ph0_v8 = 32'hAA24CBD7;   // A#3 k=2 f=466.16Hz
        5'd2: pt_ph0_v8 = 32'h58987D78;   // A#3 k=3 f=699.25Hz
        5'd3: pt_ph0_v8 = 32'hB1DD8A83;   // A#3 k=4 f=932.33Hz
        5'd4: pt_ph0_v8 = 32'h1130B265;   // A#3 k=5 f=1165.41Hz
        5'd5: pt_ph0_v8 = 32'hE8FE2ABB;   // A#3 k=6 f=1398.49Hz
        5'd6: pt_ph0_v8 = 32'h9882D18F;   // A#3 k=7 f=1631.57Hz
        5'd7: pt_ph0_v8 = 32'h94D94A16;   // A#3 k=8 f=1864.66Hz
        5'd8: pt_ph0_v8 = 32'h0EE14FBB;   // A#3 k=9 f=2097.74Hz
        5'd9: pt_ph0_v8 = 32'hC5D1BA30;   // A#3 k=10 f=2330.82Hz
        5'd10: pt_ph0_v8 = 32'hA06FAC1B;   // A#3 k=11 f=2563.90Hz
        5'd11: pt_ph0_v8 = 32'hE8703B4D;   // A#3 k=12 f=2796.98Hz
        5'd14: pt_ph0_v8 = 32'h0C2474E1;   // A#3 k=15 f=3496.23Hz
        default: pt_ph0_v8 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v9 = 32'h014B7081;   // B3 k=1 f=246.94Hz
        5'd1: pt_inc_v9 = 32'h0296E103;   // B3 k=2 f=493.88Hz
        5'd2: pt_inc_v9 = 32'h03E25184;   // B3 k=3 f=740.82Hz
        5'd3: pt_inc_v9 = 32'h052DC205;   // B3 k=4 f=987.77Hz
        5'd4: pt_inc_v9 = 32'h06793286;   // B3 k=5 f=1234.71Hz
        5'd5: pt_inc_v9 = 32'h07C4A308;   // B3 k=6 f=1481.65Hz
        5'd6: pt_inc_v9 = 32'h09101389;   // B3 k=7 f=1728.59Hz
        5'd7: pt_inc_v9 = 32'h0A5B840A;   // B3 k=8 f=1975.53Hz
        5'd8: pt_inc_v9 = 32'h0BA6F48C;   // B3 k=9 f=2222.47Hz
        5'd9: pt_inc_v9 = 32'h0CF2650D;   // B3 k=10 f=2469.42Hz
        5'd10: pt_inc_v9 = 32'h0E3DD58E;   // B3 k=11 f=2716.36Hz
        5'd11: pt_inc_v9 = 32'h0F894610;   // B3 k=12 f=2963.30Hz
        5'd12: pt_inc_v9 = 32'h10D4B691;   // B3 k=13 f=3210.24Hz
        5'd13: pt_inc_v9 = 32'h12202712;   // B3 k=14 f=3457.18Hz
        5'd14: pt_inc_v9 = 32'h136B9793;   // B3 k=15 f=3704.12Hz
        default: pt_inc_v9 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v9 = 16'd4482;   // B3 k=1 f=246.94Hz
        5'd1: pt_env0_v9 = 16'd1912;   // B3 k=2 f=493.88Hz
        5'd2: pt_env0_v9 = 16'd152;   // B3 k=3 f=740.82Hz
        5'd3: pt_env0_v9 = 16'd29;   // B3 k=4 f=987.77Hz
        5'd4: pt_env0_v9 = 16'd57;   // B3 k=5 f=1234.71Hz
        5'd5: pt_env0_v9 = 16'd208;   // B3 k=6 f=1481.65Hz
        5'd6: pt_env0_v9 = 16'd315;   // B3 k=7 f=1728.59Hz
        5'd7: pt_env0_v9 = 16'd182;   // B3 k=8 f=1975.53Hz
        5'd8: pt_env0_v9 = 16'd54;   // B3 k=9 f=2222.47Hz
        5'd9: pt_env0_v9 = 16'd424;   // B3 k=10 f=2469.42Hz
        5'd10: pt_env0_v9 = 16'd67;   // B3 k=11 f=2716.36Hz
        5'd11: pt_env0_v9 = 16'd57;   // B3 k=12 f=2963.30Hz
        5'd12: pt_env0_v9 = 16'd16;   // B3 k=13 f=3210.24Hz
        5'd13: pt_env0_v9 = 16'd148;   // B3 k=14 f=3457.18Hz
        5'd14: pt_env0_v9 = 16'd278;   // B3 k=15 f=3704.12Hz
        default: pt_env0_v9 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v9 = 12'd60;   // B3 k=1 f=246.94Hz
        5'd1: pt_rate_v9 = 12'd66;   // B3 k=2 f=493.88Hz
        5'd2: pt_rate_v9 = 12'd96;   // B3 k=3 f=740.82Hz
        5'd3: pt_rate_v9 = 12'd346;   // B3 k=4 f=987.77Hz
        5'd4: pt_rate_v9 = 12'd62;   // B3 k=5 f=1234.71Hz
        5'd5: pt_rate_v9 = 12'd73;   // B3 k=6 f=1481.65Hz
        5'd6: pt_rate_v9 = 12'd117;   // B3 k=7 f=1728.59Hz
        5'd7: pt_rate_v9 = 12'd178;   // B3 k=8 f=1975.53Hz
        5'd8: pt_rate_v9 = 12'd55;   // B3 k=9 f=2222.47Hz
        5'd9: pt_rate_v9 = 12'd197;   // B3 k=10 f=2469.42Hz
        5'd10: pt_rate_v9 = 12'd462;   // B3 k=11 f=2716.36Hz
        5'd11: pt_rate_v9 = 12'd89;   // B3 k=12 f=2963.30Hz
        5'd12: pt_rate_v9 = 12'd172;   // B3 k=13 f=3210.24Hz
        5'd13: pt_rate_v9 = 12'd275;   // B3 k=14 f=3457.18Hz
        5'd14: pt_rate_v9 = 12'd346;   // B3 k=15 f=3704.12Hz
        default: pt_rate_v9 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v9 = 16'd20051;   // B3 k=1 f=246.94Hz
        5'd1: pt_aatk_v9 = 16'd25246;   // B3 k=2 f=493.88Hz
        5'd2: pt_aatk_v9 = 16'd27064;   // B3 k=3 f=740.82Hz
        5'd3: pt_aatk_v9 = 16'd21961;   // B3 k=4 f=987.77Hz
        5'd4: pt_aatk_v9 = 16'd35436;   // B3 k=5 f=1234.71Hz
        5'd5: pt_aatk_v9 = 16'd36612;   // B3 k=6 f=1481.65Hz
        5'd6: pt_aatk_v9 = 16'd35272;   // B3 k=7 f=1728.59Hz
        5'd7: pt_aatk_v9 = 16'd33837;   // B3 k=8 f=1975.53Hz
        5'd8: pt_aatk_v9 = 16'd44483;   // B3 k=9 f=2222.47Hz
        5'd9: pt_aatk_v9 = 16'd35859;   // B3 k=10 f=2469.42Hz
        5'd10: pt_aatk_v9 = 16'd29881;   // B3 k=11 f=2716.36Hz
        5'd11: pt_aatk_v9 = 16'd45181;   // B3 k=12 f=2963.30Hz
        5'd12: pt_aatk_v9 = 16'd40716;   // B3 k=13 f=3210.24Hz
        5'd13: pt_aatk_v9 = 16'd37557;   // B3 k=14 f=3457.18Hz
        5'd14: pt_aatk_v9 = 16'd36397;   // B3 k=15 f=3704.12Hz
        default: pt_aatk_v9 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v9 = 32'hD5BAA21D;   // B3 k=1 f=246.94Hz
        5'd1: pt_ph0_v9 = 32'hBED11EC1;   // B3 k=2 f=493.88Hz
        5'd2: pt_ph0_v9 = 32'h3C2778E5;   // B3 k=3 f=740.82Hz
        5'd3: pt_ph0_v9 = 32'hB44D9E78;   // B3 k=4 f=987.77Hz
        5'd4: pt_ph0_v9 = 32'hF7D12A99;   // B3 k=5 f=1234.71Hz
        5'd5: pt_ph0_v9 = 32'h82CE18D8;   // B3 k=6 f=1481.65Hz
        5'd6: pt_ph0_v9 = 32'h7B723E72;   // B3 k=7 f=1728.59Hz
        5'd7: pt_ph0_v9 = 32'hAB3065AC;   // B3 k=8 f=1975.53Hz
        5'd8: pt_ph0_v9 = 32'h57337BAE;   // B3 k=9 f=2222.47Hz
        5'd9: pt_ph0_v9 = 32'h3092562D;   // B3 k=10 f=2469.42Hz
        5'd10: pt_ph0_v9 = 32'h30AEABC6;   // B3 k=11 f=2716.36Hz
        5'd11: pt_ph0_v9 = 32'h5F153C8D;   // B3 k=12 f=2963.30Hz
        5'd12: pt_ph0_v9 = 32'hE818F92F;   // B3 k=13 f=3210.24Hz
        5'd13: pt_ph0_v9 = 32'h10913491;   // B3 k=14 f=3457.18Hz
        5'd14: pt_ph0_v9 = 32'hF662FD90;   // B3 k=15 f=3704.12Hz
        default: pt_ph0_v9 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v10 = 32'h015F25DC;   // C4 k=1 f=261.63Hz
        5'd1: pt_inc_v10 = 32'h02BE4BB8;   // C4 k=2 f=523.25Hz
        5'd2: pt_inc_v10 = 32'h041D7194;   // C4 k=3 f=784.88Hz
        5'd3: pt_inc_v10 = 32'h057C9770;   // C4 k=4 f=1046.50Hz
        5'd4: pt_inc_v10 = 32'h06DBBD4D;   // C4 k=5 f=1308.13Hz
        5'd5: pt_inc_v10 = 32'h083AE329;   // C4 k=6 f=1569.75Hz
        5'd6: pt_inc_v10 = 32'h099A0905;   // C4 k=7 f=1831.38Hz
        5'd7: pt_inc_v10 = 32'h0AF92EE1;   // C4 k=8 f=2093.00Hz
        5'd8: pt_inc_v10 = 32'h0C5854BD;   // C4 k=9 f=2354.63Hz
        5'd9: pt_inc_v10 = 32'h0DB77A99;   // C4 k=10 f=2616.26Hz
        5'd10: pt_inc_v10 = 32'h0F16A075;   // C4 k=11 f=2877.88Hz
        5'd11: pt_inc_v10 = 32'h1075C651;   // C4 k=12 f=3139.51Hz
        5'd12: pt_inc_v10 = 32'h11D4EC2D;   // C4 k=13 f=3401.13Hz
        5'd13: pt_inc_v10 = 32'h13341209;   // C4 k=14 f=3662.76Hz
        5'd14: pt_inc_v10 = 32'h149337E5;   // C4 k=15 f=3924.38Hz
        default: pt_inc_v10 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v10 = 16'd4757;   // C4 k=1 f=261.63Hz
        5'd1: pt_env0_v10 = 16'd799;   // C4 k=2 f=523.25Hz
        5'd2: pt_env0_v10 = 16'd82;   // C4 k=3 f=784.88Hz
        5'd3: pt_env0_v10 = 16'd270;   // C4 k=4 f=1046.50Hz
        5'd4: pt_env0_v10 = 16'd366;   // C4 k=5 f=1308.13Hz
        5'd5: pt_env0_v10 = 16'd469;   // C4 k=6 f=1569.75Hz
        5'd6: pt_env0_v10 = 16'd180;   // C4 k=7 f=1831.38Hz
        5'd7: pt_env0_v10 = 16'd409;   // C4 k=8 f=2093.00Hz
        5'd8: pt_env0_v10 = 16'd859;   // C4 k=9 f=2354.63Hz
        5'd9: pt_env0_v10 = 16'd176;   // C4 k=10 f=2616.26Hz
        5'd10: pt_env0_v10 = 16'd28;   // C4 k=11 f=2877.88Hz
        5'd11: pt_env0_v10 = 16'd18;   // C4 k=12 f=3139.51Hz
        5'd12: pt_env0_v10 = 16'd19;   // C4 k=13 f=3401.13Hz
        5'd13: pt_env0_v10 = 16'd13;   // C4 k=14 f=3662.76Hz
        5'd14: pt_env0_v10 = 16'd30;   // C4 k=15 f=3924.38Hz
        default: pt_env0_v10 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v10 = 12'd82;   // C4 k=1 f=261.63Hz
        5'd1: pt_rate_v10 = 12'd149;   // C4 k=2 f=523.25Hz
        5'd2: pt_rate_v10 = 12'd80;   // C4 k=3 f=784.88Hz
        5'd3: pt_rate_v10 = 12'd191;   // C4 k=4 f=1046.50Hz
        5'd4: pt_rate_v10 = 12'd163;   // C4 k=5 f=1308.13Hz
        5'd5: pt_rate_v10 = 12'd160;   // C4 k=6 f=1569.75Hz
        5'd6: pt_rate_v10 = 12'd144;   // C4 k=7 f=1831.38Hz
        5'd7: pt_rate_v10 = 12'd262;   // C4 k=8 f=2093.00Hz
        5'd8: pt_rate_v10 = 12'd258;   // C4 k=9 f=2354.63Hz
        5'd9: pt_rate_v10 = 12'd213;   // C4 k=10 f=2616.26Hz
        5'd10: pt_rate_v10 = 12'd275;   // C4 k=11 f=2877.88Hz
        5'd11: pt_rate_v10 = 12'd226;   // C4 k=12 f=3139.51Hz
        5'd12: pt_rate_v10 = 12'd280;   // C4 k=13 f=3401.13Hz
        5'd13: pt_rate_v10 = 12'd326;   // C4 k=14 f=3662.76Hz
        5'd14: pt_rate_v10 = 12'd471;   // C4 k=15 f=3924.38Hz
        default: pt_rate_v10 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v10 = 16'd18743;   // C4 k=1 f=261.63Hz
        5'd1: pt_aatk_v10 = 16'd21091;   // C4 k=2 f=523.25Hz
        5'd2: pt_aatk_v10 = 16'd28048;   // C4 k=3 f=784.88Hz
        5'd3: pt_aatk_v10 = 16'd25718;   // C4 k=4 f=1046.50Hz
        5'd4: pt_aatk_v10 = 16'd29000;   // C4 k=5 f=1308.13Hz
        5'd5: pt_aatk_v10 = 16'd31188;   // C4 k=6 f=1569.75Hz
        5'd6: pt_aatk_v10 = 16'd33759;   // C4 k=7 f=1831.38Hz
        5'd7: pt_aatk_v10 = 16'd30846;   // C4 k=8 f=2093.00Hz
        5'd8: pt_aatk_v10 = 16'd32359;   // C4 k=9 f=2354.63Hz
        5'd9: pt_aatk_v10 = 16'd35232;   // C4 k=10 f=2616.26Hz
        5'd10: pt_aatk_v10 = 16'd34333;   // C4 k=11 f=2877.88Hz
        5'd11: pt_aatk_v10 = 16'd37163;   // C4 k=12 f=3139.51Hz
        5'd12: pt_aatk_v10 = 16'd36364;   // C4 k=13 f=3401.13Hz
        5'd13: pt_aatk_v10 = 16'd36015;   // C4 k=14 f=3662.76Hz
        5'd14: pt_aatk_v10 = 16'd33486;   // C4 k=15 f=3924.38Hz
        default: pt_aatk_v10 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v10 = 32'h93C58678;   // C4 k=1 f=261.63Hz
        5'd1: pt_ph0_v10 = 32'h4258685D;   // C4 k=2 f=523.25Hz
        5'd2: pt_ph0_v10 = 32'hA52E1174;   // C4 k=3 f=784.88Hz
        5'd3: pt_ph0_v10 = 32'h8714FC74;   // C4 k=4 f=1046.50Hz
        5'd4: pt_ph0_v10 = 32'h0BD47719;   // C4 k=5 f=1308.13Hz
        5'd5: pt_ph0_v10 = 32'h23D5A5C2;   // C4 k=6 f=1569.75Hz
        5'd6: pt_ph0_v10 = 32'h7AD860F4;   // C4 k=7 f=1831.38Hz
        5'd7: pt_ph0_v10 = 32'hAE1DA977;   // C4 k=8 f=2093.00Hz
        5'd8: pt_ph0_v10 = 32'h7D5BD92E;   // C4 k=9 f=2354.63Hz
        5'd9: pt_ph0_v10 = 32'hC9BD5831;   // C4 k=10 f=2616.26Hz
        5'd10: pt_ph0_v10 = 32'h35D264EC;   // C4 k=11 f=2877.88Hz
        5'd11: pt_ph0_v10 = 32'h50B4D12B;   // C4 k=12 f=3139.51Hz
        5'd12: pt_ph0_v10 = 32'h98AB5803;   // C4 k=13 f=3401.13Hz
        5'd13: pt_ph0_v10 = 32'h86C37B16;   // C4 k=14 f=3662.76Hz
        5'd14: pt_ph0_v10 = 32'hDD983706;   // C4 k=15 f=3924.38Hz
        default: pt_ph0_v10 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v11 = 32'h0174073A;   // C#4 k=1 f=277.18Hz
        5'd1: pt_inc_v11 = 32'h02E80E74;   // C#4 k=2 f=554.37Hz
        5'd2: pt_inc_v11 = 32'h045C15AE;   // C#4 k=3 f=831.55Hz
        5'd3: pt_inc_v11 = 32'h05D01CE8;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_inc_v11 = 32'h07442422;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_inc_v11 = 32'h08B82B5C;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_inc_v11 = 32'h0A2C3296;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_inc_v11 = 32'h0BA039D1;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_inc_v11 = 32'h0D14410B;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_inc_v11 = 32'h0E884845;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_inc_v11 = 32'h0FFC4F7F;   // C#4 k=11 f=3049.01Hz
        5'd11: pt_inc_v11 = 32'h117056B9;   // C#4 k=12 f=3326.19Hz
        5'd12: pt_inc_v11 = 32'h12E45DF3;   // C#4 k=13 f=3603.37Hz
        5'd13: pt_inc_v11 = 32'h1458652D;   // C#4 k=14 f=3880.56Hz
        5'd14: pt_inc_v11 = 32'h15CC6C67;   // C#4 k=15 f=4157.74Hz
        default: pt_inc_v11 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v11 = 16'd3977;   // C#4 k=1 f=277.18Hz
        5'd1: pt_env0_v11 = 16'd2798;   // C#4 k=2 f=554.37Hz
        5'd2: pt_env0_v11 = 16'd357;   // C#4 k=3 f=831.55Hz
        5'd3: pt_env0_v11 = 16'd143;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_env0_v11 = 16'd139;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_env0_v11 = 16'd348;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_env0_v11 = 16'd15;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_env0_v11 = 16'd165;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_env0_v11 = 16'd239;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_env0_v11 = 16'd51;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_env0_v11 = 16'd11;   // C#4 k=11 f=3049.01Hz
        5'd11: pt_env0_v11 = 16'd42;   // C#4 k=12 f=3326.19Hz
        5'd12: pt_env0_v11 = 16'd65;   // C#4 k=13 f=3603.37Hz
        5'd13: pt_env0_v11 = 16'd28;   // C#4 k=14 f=3880.56Hz
        5'd14: pt_env0_v11 = 16'd42;   // C#4 k=15 f=4157.74Hz
        default: pt_env0_v11 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v11 = 12'd61;   // C#4 k=1 f=277.18Hz
        5'd1: pt_rate_v11 = 12'd126;   // C#4 k=2 f=554.37Hz
        5'd2: pt_rate_v11 = 12'd62;   // C#4 k=3 f=831.55Hz
        5'd3: pt_rate_v11 = 12'd277;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_rate_v11 = 12'd104;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_rate_v11 = 12'd144;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_rate_v11 = 12'd81;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_rate_v11 = 12'd109;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_rate_v11 = 12'd214;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_rate_v11 = 12'd156;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_rate_v11 = 12'd136;   // C#4 k=11 f=3049.01Hz
        5'd11: pt_rate_v11 = 12'd229;   // C#4 k=12 f=3326.19Hz
        5'd12: pt_rate_v11 = 12'd153;   // C#4 k=13 f=3603.37Hz
        5'd13: pt_rate_v11 = 12'd309;   // C#4 k=14 f=3880.56Hz
        5'd14: pt_rate_v11 = 12'd358;   // C#4 k=15 f=4157.74Hz
        default: pt_rate_v11 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v11 = 16'd19941;   // C#4 k=1 f=277.18Hz
        5'd1: pt_aatk_v11 = 16'd21959;   // C#4 k=2 f=554.37Hz
        5'd2: pt_aatk_v11 = 16'd29496;   // C#4 k=3 f=831.55Hz
        5'd3: pt_aatk_v11 = 16'd23379;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_aatk_v11 = 16'd32034;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_aatk_v11 = 16'd31900;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_aatk_v11 = 16'd37967;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_aatk_v11 = 16'd37564;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_aatk_v11 = 16'd33855;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_aatk_v11 = 16'd37813;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_aatk_v11 = 16'd40286;   // C#4 k=11 f=3049.01Hz
        5'd11: pt_aatk_v11 = 16'd37055;   // C#4 k=12 f=3326.19Hz
        5'd12: pt_aatk_v11 = 16'd41744;   // C#4 k=13 f=3603.37Hz
        5'd13: pt_aatk_v11 = 16'd36496;   // C#4 k=14 f=3880.56Hz
        5'd14: pt_aatk_v11 = 16'd36066;   // C#4 k=15 f=4157.74Hz
        default: pt_aatk_v11 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v11 = 32'hB46BCDFA;   // C#4 k=1 f=277.18Hz
        5'd1: pt_ph0_v11 = 32'h77498910;   // C#4 k=2 f=554.37Hz
        5'd2: pt_ph0_v11 = 32'h8B1EEE85;   // C#4 k=3 f=831.55Hz
        5'd3: pt_ph0_v11 = 32'h8F02D9A2;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_ph0_v11 = 32'h52E88499;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_ph0_v11 = 32'h0D0B3124;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_ph0_v11 = 32'h0EDF12D3;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_ph0_v11 = 32'h7C2596B1;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_ph0_v11 = 32'h1089E8C8;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_ph0_v11 = 32'h9F8F3E00;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_ph0_v11 = 32'h71AF46C7;   // C#4 k=11 f=3049.01Hz
        5'd11: pt_ph0_v11 = 32'hB78AA5FC;   // C#4 k=12 f=3326.19Hz
        5'd12: pt_ph0_v11 = 32'h859FD8A6;   // C#4 k=13 f=3603.37Hz
        5'd13: pt_ph0_v11 = 32'hAFEFDB83;   // C#4 k=14 f=3880.56Hz
        5'd14: pt_ph0_v11 = 32'hC76DA84C;   // C#4 k=15 f=4157.74Hz
        default: pt_ph0_v11 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v12 = 32'h018A2672;   // D4 k=1 f=293.66Hz
        5'd1: pt_inc_v12 = 32'h03144CE4;   // D4 k=2 f=587.33Hz
        5'd2: pt_inc_v12 = 32'h049E7357;   // D4 k=3 f=880.99Hz
        5'd3: pt_inc_v12 = 32'h062899C9;   // D4 k=4 f=1174.66Hz
        5'd4: pt_inc_v12 = 32'h07B2C03B;   // D4 k=5 f=1468.32Hz
        5'd5: pt_inc_v12 = 32'h093CE6AD;   // D4 k=6 f=1761.99Hz
        5'd6: pt_inc_v12 = 32'h0AC70D1F;   // D4 k=7 f=2055.65Hz
        5'd7: pt_inc_v12 = 32'h0C513391;   // D4 k=8 f=2349.32Hz
        5'd8: pt_inc_v12 = 32'h0DDB5A03;   // D4 k=9 f=2642.98Hz
        5'd9: pt_inc_v12 = 32'h0F658076;   // D4 k=10 f=2936.65Hz
        5'd10: pt_inc_v12 = 32'h10EFA6E8;   // D4 k=11 f=3230.31Hz
        5'd11: pt_inc_v12 = 32'h1279CD5A;   // D4 k=12 f=3523.98Hz
        5'd12: pt_inc_v12 = 32'h1403F3CC;   // D4 k=13 f=3817.64Hz
        5'd13: pt_inc_v12 = 32'h158E1A3E;   // D4 k=14 f=4111.31Hz
        5'd14: pt_inc_v12 = 32'h171840B0;   // D4 k=15 f=4404.97Hz
        default: pt_inc_v12 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v12 = 16'd4170;   // D4 k=1 f=293.66Hz
        5'd1: pt_env0_v12 = 16'd2065;   // D4 k=2 f=587.33Hz
        5'd2: pt_env0_v12 = 16'd492;   // D4 k=3 f=880.99Hz
        5'd3: pt_env0_v12 = 16'd327;   // D4 k=4 f=1174.66Hz
        5'd4: pt_env0_v12 = 16'd276;   // D4 k=5 f=1468.32Hz
        5'd5: pt_env0_v12 = 16'd263;   // D4 k=6 f=1761.99Hz
        5'd6: pt_env0_v12 = 16'd173;   // D4 k=7 f=2055.65Hz
        5'd7: pt_env0_v12 = 16'd223;   // D4 k=8 f=2349.32Hz
        5'd8: pt_env0_v12 = 16'd79;   // D4 k=9 f=2642.98Hz
        5'd9: pt_env0_v12 = 16'd118;   // D4 k=10 f=2936.65Hz
        5'd10: pt_env0_v12 = 16'd40;   // D4 k=11 f=3230.31Hz
        5'd11: pt_env0_v12 = 16'd44;   // D4 k=12 f=3523.98Hz
        5'd12: pt_env0_v12 = 16'd61;   // D4 k=13 f=3817.64Hz
        5'd13: pt_env0_v12 = 16'd8;   // D4 k=14 f=4111.31Hz
        5'd14: pt_env0_v12 = 16'd13;   // D4 k=15 f=4404.97Hz
        default: pt_env0_v12 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v12 = 12'd56;   // D4 k=1 f=293.66Hz
        5'd1: pt_rate_v12 = 12'd54;   // D4 k=2 f=587.33Hz
        5'd2: pt_rate_v12 = 12'd80;   // D4 k=3 f=880.99Hz
        5'd3: pt_rate_v12 = 12'd74;   // D4 k=4 f=1174.66Hz
        5'd4: pt_rate_v12 = 12'd74;   // D4 k=5 f=1468.32Hz
        5'd5: pt_rate_v12 = 12'd62;   // D4 k=6 f=1761.99Hz
        5'd6: pt_rate_v12 = 12'd117;   // D4 k=7 f=2055.65Hz
        5'd7: pt_rate_v12 = 12'd389;   // D4 k=8 f=2349.32Hz
        5'd8: pt_rate_v12 = 12'd116;   // D4 k=9 f=2642.98Hz
        5'd9: pt_rate_v12 = 12'd173;   // D4 k=10 f=2936.65Hz
        5'd10: pt_rate_v12 = 12'd89;   // D4 k=11 f=3230.31Hz
        5'd11: pt_rate_v12 = 12'd152;   // D4 k=12 f=3523.98Hz
        5'd12: pt_rate_v12 = 12'd202;   // D4 k=13 f=3817.64Hz
        5'd13: pt_rate_v12 = 12'd150;   // D4 k=14 f=4111.31Hz
        5'd14: pt_rate_v12 = 12'd214;   // D4 k=15 f=4404.97Hz
        default: pt_rate_v12 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v12 = 16'd20315;   // D4 k=1 f=293.66Hz
        5'd1: pt_aatk_v12 = 16'd26182;   // D4 k=2 f=587.33Hz
        5'd2: pt_aatk_v12 = 16'd28095;   // D4 k=3 f=880.99Hz
        5'd3: pt_aatk_v12 = 16'd31650;   // D4 k=4 f=1174.66Hz
        5'd4: pt_aatk_v12 = 16'd34233;   // D4 k=5 f=1468.32Hz
        5'd5: pt_aatk_v12 = 16'd37769;   // D4 k=6 f=1761.99Hz
        5'd6: pt_aatk_v12 = 16'd35259;   // D4 k=7 f=2055.65Hz
        5'd7: pt_aatk_v12 = 16'd27743;   // D4 k=8 f=2349.32Hz
        5'd8: pt_aatk_v12 = 16'd38687;   // D4 k=9 f=2642.98Hz
        5'd9: pt_aatk_v12 = 16'd36957;   // D4 k=10 f=2936.65Hz
        5'd10: pt_aatk_v12 = 16'd43807;   // D4 k=11 f=3230.31Hz
        5'd11: pt_aatk_v12 = 16'd40610;   // D4 k=12 f=3523.98Hz
        5'd12: pt_aatk_v12 = 16'd39308;   // D4 k=13 f=3817.64Hz
        5'd13: pt_aatk_v12 = 16'd43080;   // D4 k=14 f=4111.31Hz
        5'd14: pt_aatk_v12 = 16'd40881;   // D4 k=15 f=4404.97Hz
        default: pt_aatk_v12 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v12 = 32'h3EE63EBE;   // D4 k=1 f=293.66Hz
        5'd1: pt_ph0_v12 = 32'hDF01C6E6;   // D4 k=2 f=587.33Hz
        5'd2: pt_ph0_v12 = 32'h1C73D408;   // D4 k=3 f=880.99Hz
        5'd3: pt_ph0_v12 = 32'hB8AE0951;   // D4 k=4 f=1174.66Hz
        5'd4: pt_ph0_v12 = 32'h4906A7F3;   // D4 k=5 f=1468.32Hz
        5'd5: pt_ph0_v12 = 32'h22E9A8EF;   // D4 k=6 f=1761.99Hz
        5'd6: pt_ph0_v12 = 32'hE97C21B5;   // D4 k=7 f=2055.65Hz
        5'd7: pt_ph0_v12 = 32'hC41C5510;   // D4 k=8 f=2349.32Hz
        5'd8: pt_ph0_v12 = 32'h99703BAF;   // D4 k=9 f=2642.98Hz
        5'd9: pt_ph0_v12 = 32'h5EB7010D;   // D4 k=10 f=2936.65Hz
        5'd10: pt_ph0_v12 = 32'h6C493686;   // D4 k=11 f=3230.31Hz
        5'd11: pt_ph0_v12 = 32'h19A3AA8A;   // D4 k=12 f=3523.98Hz
        5'd12: pt_ph0_v12 = 32'hF2A94293;   // D4 k=13 f=3817.64Hz
        5'd13: pt_ph0_v12 = 32'h8592B22E;   // D4 k=14 f=4111.31Hz
        5'd14: pt_ph0_v12 = 32'hA9346365;   // D4 k=15 f=4404.97Hz
        default: pt_ph0_v12 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v13 = 32'h01A1966B;   // D#4 k=1 f=311.13Hz
        5'd1: pt_inc_v13 = 32'h03432CD6;   // D#4 k=2 f=622.25Hz
        5'd2: pt_inc_v13 = 32'h04E4C341;   // D#4 k=3 f=933.38Hz
        5'd3: pt_inc_v13 = 32'h068659AC;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_inc_v13 = 32'h0827F017;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_inc_v13 = 32'h09C98681;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_inc_v13 = 32'h0B6B1CEC;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_inc_v13 = 32'h0D0CB357;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_inc_v13 = 32'h0EAE49C2;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_inc_v13 = 32'h104FE02D;   // D#4 k=10 f=3111.27Hz
        5'd11: pt_inc_v13 = 32'h13930D03;   // D#4 k=12 f=3733.52Hz
        default: pt_inc_v13 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v13 = 16'd4564;   // D#4 k=1 f=311.13Hz
        5'd1: pt_env0_v13 = 16'd1915;   // D#4 k=2 f=622.25Hz
        5'd2: pt_env0_v13 = 16'd341;   // D#4 k=3 f=933.38Hz
        5'd3: pt_env0_v13 = 16'd99;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_env0_v13 = 16'd350;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_env0_v13 = 16'd330;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_env0_v13 = 16'd110;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_env0_v13 = 16'd342;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_env0_v13 = 16'd161;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_env0_v13 = 16'd168;   // D#4 k=10 f=3111.27Hz
        5'd11: pt_env0_v13 = 16'd14;   // D#4 k=12 f=3733.52Hz
        default: pt_env0_v13 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v13 = 12'd51;   // D#4 k=1 f=311.13Hz
        5'd1: pt_rate_v13 = 12'd92;   // D#4 k=2 f=622.25Hz
        5'd2: pt_rate_v13 = 12'd105;   // D#4 k=3 f=933.38Hz
        5'd3: pt_rate_v13 = 12'd95;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_rate_v13 = 12'd167;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_rate_v13 = 12'd277;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_rate_v13 = 12'd77;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_rate_v13 = 12'd216;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_rate_v13 = 12'd226;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_rate_v13 = 12'd402;   // D#4 k=10 f=3111.27Hz
        5'd11: pt_rate_v13 = 12'd187;   // D#4 k=12 f=3733.52Hz
        default: pt_rate_v13 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v13 = 16'd20707;   // D#4 k=1 f=311.13Hz
        5'd1: pt_aatk_v13 = 16'd23561;   // D#4 k=2 f=622.25Hz
        5'd2: pt_aatk_v13 = 16'd26560;   // D#4 k=3 f=933.38Hz
        5'd3: pt_aatk_v13 = 16'd30062;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_aatk_v13 = 16'd28866;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_aatk_v13 = 16'd27262;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_aatk_v13 = 16'd38314;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_aatk_v13 = 16'd32335;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_aatk_v13 = 16'd33408;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_aatk_v13 = 16'd29958;   // D#4 k=10 f=3111.27Hz
        5'd11: pt_aatk_v13 = 16'd38816;   // D#4 k=12 f=3733.52Hz
        default: pt_aatk_v13 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v13 = 32'h8E42E8E9;   // D#4 k=1 f=311.13Hz
        5'd1: pt_ph0_v13 = 32'hB8AB8986;   // D#4 k=2 f=622.25Hz
        5'd2: pt_ph0_v13 = 32'hFAFE842B;   // D#4 k=3 f=933.38Hz
        5'd3: pt_ph0_v13 = 32'h6505D3D6;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_ph0_v13 = 32'h3090F149;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_ph0_v13 = 32'hF98104B5;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_ph0_v13 = 32'hFBEECFFE;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_ph0_v13 = 32'h6032C036;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_ph0_v13 = 32'h3EB799AC;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_ph0_v13 = 32'h7DCD92CD;   // D#4 k=10 f=3111.27Hz
        5'd11: pt_ph0_v13 = 32'h3D1EF5E7;   // D#4 k=12 f=3733.52Hz
        default: pt_ph0_v13 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v14 = 32'h01BA6B2B;   // E4 k=1 f=329.63Hz
        5'd1: pt_inc_v14 = 32'h0374D655;   // E4 k=2 f=659.26Hz
        5'd2: pt_inc_v14 = 32'h052F4180;   // E4 k=3 f=988.88Hz
        5'd3: pt_inc_v14 = 32'h06E9ACAA;   // E4 k=4 f=1318.51Hz
        5'd4: pt_inc_v14 = 32'h08A417D5;   // E4 k=5 f=1648.14Hz
        5'd5: pt_inc_v14 = 32'h0A5E82FF;   // E4 k=6 f=1977.77Hz
        5'd6: pt_inc_v14 = 32'h0C18EE2A;   // E4 k=7 f=2307.39Hz
        5'd7: pt_inc_v14 = 32'h0DD35954;   // E4 k=8 f=2637.02Hz
        5'd8: pt_inc_v14 = 32'h0F8DC47F;   // E4 k=9 f=2966.65Hz
        5'd9: pt_inc_v14 = 32'h11482FA9;   // E4 k=10 f=3296.28Hz
        5'd10: pt_inc_v14 = 32'h13029AD4;   // E4 k=11 f=3625.90Hz
        5'd14: pt_inc_v14 = 32'h19EC477E;   // E4 k=15 f=4944.41Hz
        default: pt_inc_v14 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v14 = 16'd5326;   // E4 k=1 f=329.63Hz
        5'd1: pt_env0_v14 = 16'd913;   // E4 k=2 f=659.26Hz
        5'd2: pt_env0_v14 = 16'd742;   // E4 k=3 f=988.88Hz
        5'd3: pt_env0_v14 = 16'd181;   // E4 k=4 f=1318.51Hz
        5'd4: pt_env0_v14 = 16'd279;   // E4 k=5 f=1648.14Hz
        5'd5: pt_env0_v14 = 16'd291;   // E4 k=6 f=1977.77Hz
        5'd6: pt_env0_v14 = 16'd542;   // E4 k=7 f=2307.39Hz
        5'd7: pt_env0_v14 = 16'd21;   // E4 k=8 f=2637.02Hz
        5'd8: pt_env0_v14 = 16'd23;   // E4 k=9 f=2966.65Hz
        5'd9: pt_env0_v14 = 16'd24;   // E4 k=10 f=3296.28Hz
        5'd10: pt_env0_v14 = 16'd12;   // E4 k=11 f=3625.90Hz
        5'd14: pt_env0_v14 = 16'd36;   // E4 k=15 f=4944.41Hz
        default: pt_env0_v14 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v14 = 12'd62;   // E4 k=1 f=329.63Hz
        5'd1: pt_rate_v14 = 12'd36;   // E4 k=2 f=659.26Hz
        5'd2: pt_rate_v14 = 12'd152;   // E4 k=3 f=988.88Hz
        5'd3: pt_rate_v14 = 12'd154;   // E4 k=4 f=1318.51Hz
        5'd4: pt_rate_v14 = 12'd151;   // E4 k=5 f=1648.14Hz
        5'd5: pt_rate_v14 = 12'd150;   // E4 k=6 f=1977.77Hz
        5'd6: pt_rate_v14 = 12'd225;   // E4 k=7 f=2307.39Hz
        5'd7: pt_rate_v14 = 12'd118;   // E4 k=8 f=2637.02Hz
        5'd8: pt_rate_v14 = 12'd254;   // E4 k=9 f=2966.65Hz
        5'd9: pt_rate_v14 = 12'd197;   // E4 k=10 f=3296.28Hz
        5'd10: pt_rate_v14 = 12'd175;   // E4 k=11 f=3625.90Hz
        5'd14: pt_rate_v14 = 12'd287;   // E4 k=15 f=4944.41Hz
        default: pt_rate_v14 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v14 = 16'd19883;   // E4 k=1 f=329.63Hz
        5'd1: pt_aatk_v14 = 16'd28275;   // E4 k=2 f=659.26Hz
        5'd2: pt_aatk_v14 = 16'd24406;   // E4 k=3 f=988.88Hz
        5'd3: pt_aatk_v14 = 16'd27069;   // E4 k=4 f=1318.51Hz
        5'd4: pt_aatk_v14 = 16'd29533;   // E4 k=5 f=1648.14Hz
        5'd5: pt_aatk_v14 = 16'd31635;   // E4 k=6 f=1977.77Hz
        5'd6: pt_aatk_v14 = 16'd30443;   // E4 k=7 f=2307.39Hz
        5'd7: pt_aatk_v14 = 16'd36979;   // E4 k=8 f=2637.02Hz
        5'd8: pt_aatk_v14 = 16'd32483;   // E4 k=9 f=2966.65Hz
        5'd9: pt_aatk_v14 = 16'd35893;   // E4 k=10 f=3296.28Hz
        5'd10: pt_aatk_v14 = 16'd38180;   // E4 k=11 f=3625.90Hz
        5'd14: pt_aatk_v14 = 16'd38139;   // E4 k=15 f=4944.41Hz
        default: pt_aatk_v14 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v14 = 32'h97EEE2F6;   // E4 k=1 f=329.63Hz
        5'd1: pt_ph0_v14 = 32'h3DB0E2EE;   // E4 k=2 f=659.26Hz
        5'd2: pt_ph0_v14 = 32'h1C4B7118;   // E4 k=3 f=988.88Hz
        5'd3: pt_ph0_v14 = 32'h3F614DAC;   // E4 k=4 f=1318.51Hz
        5'd4: pt_ph0_v14 = 32'hCC4C1E06;   // E4 k=5 f=1648.14Hz
        5'd5: pt_ph0_v14 = 32'hBE13C1C0;   // E4 k=6 f=1977.77Hz
        5'd6: pt_ph0_v14 = 32'h035FF875;   // E4 k=7 f=2307.39Hz
        5'd7: pt_ph0_v14 = 32'h7675EDFD;   // E4 k=8 f=2637.02Hz
        5'd8: pt_ph0_v14 = 32'hB28F2B18;   // E4 k=9 f=2966.65Hz
        5'd9: pt_ph0_v14 = 32'hAA6C1D2E;   // E4 k=10 f=3296.28Hz
        5'd10: pt_ph0_v14 = 32'h10F0F08A;   // E4 k=11 f=3625.90Hz
        5'd14: pt_ph0_v14 = 32'hD2D748BA;   // E4 k=15 f=4944.41Hz
        default: pt_ph0_v14 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v15 = 32'h01D4B9E8;   // F4 k=1 f=349.23Hz
        5'd1: pt_inc_v15 = 32'h03A973D0;   // F4 k=2 f=698.46Hz
        5'd2: pt_inc_v15 = 32'h057E2DB8;   // F4 k=3 f=1047.68Hz
        5'd3: pt_inc_v15 = 32'h0752E7A0;   // F4 k=4 f=1396.91Hz
        5'd4: pt_inc_v15 = 32'h0927A188;   // F4 k=5 f=1746.14Hz
        5'd5: pt_inc_v15 = 32'h0AFC5B71;   // F4 k=6 f=2095.37Hz
        5'd6: pt_inc_v15 = 32'h0CD11559;   // F4 k=7 f=2444.60Hz
        5'd7: pt_inc_v15 = 32'h0EA5CF41;   // F4 k=8 f=2793.83Hz
        5'd8: pt_inc_v15 = 32'h107A8929;   // F4 k=9 f=3143.05Hz
        5'd9: pt_inc_v15 = 32'h124F4311;   // F4 k=10 f=3492.28Hz
        5'd10: pt_inc_v15 = 32'h1423FCF9;   // F4 k=11 f=3841.51Hz
        5'd11: pt_inc_v15 = 32'h15F8B6E1;   // F4 k=12 f=4190.74Hz
        5'd12: pt_inc_v15 = 32'h17CD70C9;   // F4 k=13 f=4539.97Hz
        5'd13: pt_inc_v15 = 32'h19A22AB1;   // F4 k=14 f=4889.20Hz
        5'd14: pt_inc_v15 = 32'h1B76E499;   // F4 k=15 f=5238.42Hz
        default: pt_inc_v15 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v15 = 16'd3057;   // F4 k=1 f=349.23Hz
        5'd1: pt_env0_v15 = 16'd2556;   // F4 k=2 f=698.46Hz
        5'd2: pt_env0_v15 = 16'd354;   // F4 k=3 f=1047.68Hz
        5'd3: pt_env0_v15 = 16'd328;   // F4 k=4 f=1396.91Hz
        5'd4: pt_env0_v15 = 16'd165;   // F4 k=5 f=1746.14Hz
        5'd5: pt_env0_v15 = 16'd1423;   // F4 k=6 f=2095.37Hz
        5'd6: pt_env0_v15 = 16'd53;   // F4 k=7 f=2444.60Hz
        5'd7: pt_env0_v15 = 16'd38;   // F4 k=8 f=2793.83Hz
        5'd8: pt_env0_v15 = 16'd205;   // F4 k=9 f=3143.05Hz
        5'd9: pt_env0_v15 = 16'd152;   // F4 k=10 f=3492.28Hz
        5'd10: pt_env0_v15 = 16'd50;   // F4 k=11 f=3841.51Hz
        5'd11: pt_env0_v15 = 16'd25;   // F4 k=12 f=4190.74Hz
        5'd12: pt_env0_v15 = 16'd15;   // F4 k=13 f=4539.97Hz
        5'd13: pt_env0_v15 = 16'd10;   // F4 k=14 f=4889.20Hz
        5'd14: pt_env0_v15 = 16'd18;   // F4 k=15 f=5238.42Hz
        default: pt_env0_v15 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v15 = 12'd68;   // F4 k=1 f=349.23Hz
        5'd1: pt_rate_v15 = 12'd127;   // F4 k=2 f=698.46Hz
        5'd2: pt_rate_v15 = 12'd181;   // F4 k=3 f=1047.68Hz
        5'd3: pt_rate_v15 = 12'd134;   // F4 k=4 f=1396.91Hz
        5'd4: pt_rate_v15 = 12'd223;   // F4 k=5 f=1746.14Hz
        5'd5: pt_rate_v15 = 12'd161;   // F4 k=6 f=2095.37Hz
        5'd6: pt_rate_v15 = 12'd83;   // F4 k=7 f=2444.60Hz
        5'd7: pt_rate_v15 = 12'd161;   // F4 k=8 f=2793.83Hz
        5'd8: pt_rate_v15 = 12'd126;   // F4 k=9 f=3143.05Hz
        5'd9: pt_rate_v15 = 12'd203;   // F4 k=10 f=3492.28Hz
        5'd10: pt_rate_v15 = 12'd362;   // F4 k=11 f=3841.51Hz
        5'd11: pt_rate_v15 = 12'd217;   // F4 k=12 f=4190.74Hz
        5'd12: pt_rate_v15 = 12'd289;   // F4 k=13 f=4539.97Hz
        5'd13: pt_rate_v15 = 12'd222;   // F4 k=14 f=4889.20Hz
        5'd14: pt_rate_v15 = 12'd534;   // F4 k=15 f=5238.42Hz
        default: pt_rate_v15 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v15 = 16'd19508;   // F4 k=1 f=349.23Hz
        5'd1: pt_aatk_v15 = 16'd21899;   // F4 k=2 f=698.46Hz
        5'd2: pt_aatk_v15 = 16'd23402;   // F4 k=3 f=1047.68Hz
        5'd3: pt_aatk_v15 = 16'd27929;   // F4 k=4 f=1396.91Hz
        5'd4: pt_aatk_v15 = 16'd26909;   // F4 k=5 f=1746.14Hz
        5'd5: pt_aatk_v15 = 16'd31139;   // F4 k=6 f=2095.37Hz
        5'd6: pt_aatk_v15 = 16'd37814;   // F4 k=7 f=2444.60Hz
        5'd7: pt_aatk_v15 = 16'd34597;   // F4 k=8 f=2793.83Hz
        5'd8: pt_aatk_v15 = 16'd38038;   // F4 k=9 f=3143.05Hz
        5'd9: pt_aatk_v15 = 16'd35619;   // F4 k=10 f=3492.28Hz
        5'd10: pt_aatk_v15 = 16'd31963;   // F4 k=11 f=3841.51Hz
        5'd11: pt_aatk_v15 = 16'd37529;   // F4 k=12 f=4190.74Hz
        5'd12: pt_aatk_v15 = 16'd36109;   // F4 k=13 f=4539.97Hz
        5'd13: pt_aatk_v15 = 16'd39527;   // F4 k=14 f=4889.20Hz
        5'd14: pt_aatk_v15 = 16'd32304;   // F4 k=15 f=5238.42Hz
        default: pt_aatk_v15 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v15 = 32'h43C2BE1A;   // F4 k=1 f=349.23Hz
        5'd1: pt_ph0_v15 = 32'h943F775A;   // F4 k=2 f=698.46Hz
        5'd2: pt_ph0_v15 = 32'h20554C30;   // F4 k=3 f=1047.68Hz
        5'd3: pt_ph0_v15 = 32'hB3B213F9;   // F4 k=4 f=1396.91Hz
        5'd4: pt_ph0_v15 = 32'hC86428FF;   // F4 k=5 f=1746.14Hz
        5'd5: pt_ph0_v15 = 32'hE2062602;   // F4 k=6 f=2095.37Hz
        5'd6: pt_ph0_v15 = 32'hFE08D941;   // F4 k=7 f=2444.60Hz
        5'd7: pt_ph0_v15 = 32'h4131F1F1;   // F4 k=8 f=2793.83Hz
        5'd8: pt_ph0_v15 = 32'h9EF220B6;   // F4 k=9 f=3143.05Hz
        5'd9: pt_ph0_v15 = 32'h86753544;   // F4 k=10 f=3492.28Hz
        5'd10: pt_ph0_v15 = 32'h3B69006A;   // F4 k=11 f=3841.51Hz
        5'd11: pt_ph0_v15 = 32'h77EDF6D8;   // F4 k=12 f=4190.74Hz
        5'd12: pt_ph0_v15 = 32'hEFE4DA23;   // F4 k=13 f=4539.97Hz
        5'd13: pt_ph0_v15 = 32'hE0B08E13;   // F4 k=14 f=4889.20Hz
        5'd14: pt_ph0_v15 = 32'hDF2043EA;   // F4 k=15 f=5238.42Hz
        default: pt_ph0_v15 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v16 = 32'h01F0991E;   // F#4 k=1 f=369.99Hz
        5'd1: pt_inc_v16 = 32'h03E1323B;   // F#4 k=2 f=739.99Hz
        5'd2: pt_inc_v16 = 32'h05D1CB59;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_inc_v16 = 32'h07C26476;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_inc_v16 = 32'h09B2FD94;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_inc_v16 = 32'h0BA396B2;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_inc_v16 = 32'h0D942FCF;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_inc_v16 = 32'h0F84C8ED;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_inc_v16 = 32'h1175620A;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_inc_v16 = 32'h1365FB28;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_inc_v16 = 32'h15569446;   // F#4 k=11 f=4069.94Hz
        5'd11: pt_inc_v16 = 32'h17472D63;   // F#4 k=12 f=4439.93Hz
        5'd12: pt_inc_v16 = 32'h1937C681;   // F#4 k=13 f=4809.93Hz
        5'd13: pt_inc_v16 = 32'h1B285F9E;   // F#4 k=14 f=5179.92Hz
        5'd14: pt_inc_v16 = 32'h1D18F8BC;   // F#4 k=15 f=5549.92Hz
        default: pt_inc_v16 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v16 = 16'd2551;   // F#4 k=1 f=369.99Hz
        5'd1: pt_env0_v16 = 16'd2492;   // F#4 k=2 f=739.99Hz
        5'd2: pt_env0_v16 = 16'd344;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_env0_v16 = 16'd121;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_env0_v16 = 16'd366;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_env0_v16 = 16'd1098;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_env0_v16 = 16'd153;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_env0_v16 = 16'd110;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_env0_v16 = 16'd437;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_env0_v16 = 16'd175;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_env0_v16 = 16'd624;   // F#4 k=11 f=4069.94Hz
        5'd11: pt_env0_v16 = 16'd36;   // F#4 k=12 f=4439.93Hz
        5'd12: pt_env0_v16 = 16'd64;   // F#4 k=13 f=4809.93Hz
        5'd13: pt_env0_v16 = 16'd63;   // F#4 k=14 f=5179.92Hz
        5'd14: pt_env0_v16 = 16'd37;   // F#4 k=15 f=5549.92Hz
        default: pt_env0_v16 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v16 = 12'd87;   // F#4 k=1 f=369.99Hz
        5'd1: pt_rate_v16 = 12'd159;   // F#4 k=2 f=739.99Hz
        5'd2: pt_rate_v16 = 12'd232;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_rate_v16 = 12'd117;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_rate_v16 = 12'd155;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_rate_v16 = 12'd210;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_rate_v16 = 12'd213;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_rate_v16 = 12'd191;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_rate_v16 = 12'd599;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_rate_v16 = 12'd475;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_rate_v16 = 12'd1167;   // F#4 k=11 f=4069.94Hz
        5'd11: pt_rate_v16 = 12'd535;   // F#4 k=12 f=4439.93Hz
        5'd12: pt_rate_v16 = 12'd596;   // F#4 k=13 f=4809.93Hz
        5'd13: pt_rate_v16 = 12'd719;   // F#4 k=14 f=5179.92Hz
        5'd14: pt_rate_v16 = 12'd319;   // F#4 k=15 f=5549.92Hz
        default: pt_rate_v16 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v16 = 16'd18484;   // F#4 k=1 f=369.99Hz
        5'd1: pt_aatk_v16 = 16'd20766;   // F#4 k=2 f=739.99Hz
        5'd2: pt_aatk_v16 = 16'd21967;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_aatk_v16 = 16'd28772;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_aatk_v16 = 16'd29343;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_aatk_v16 = 16'd29253;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_aatk_v16 = 16'd30868;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_aatk_v16 = 16'd33286;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_aatk_v16 = 16'd25515;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_aatk_v16 = 16'd28557;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_aatk_v16 = 16'd21827;   // F#4 k=11 f=4069.94Hz
        5'd11: pt_aatk_v16 = 16'd29602;   // F#4 k=12 f=4439.93Hz
        5'd12: pt_aatk_v16 = 16'd29554;   // F#4 k=13 f=4809.93Hz
        5'd13: pt_aatk_v16 = 16'd28666;   // F#4 k=14 f=5179.92Hz
        5'd14: pt_aatk_v16 = 16'd37166;   // F#4 k=15 f=5549.92Hz
        default: pt_aatk_v16 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v16 = 32'h3CF060C4;   // F#4 k=1 f=369.99Hz
        5'd1: pt_ph0_v16 = 32'h2DF7EEB0;   // F#4 k=2 f=739.99Hz
        5'd2: pt_ph0_v16 = 32'hD41152A0;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_ph0_v16 = 32'h36E09DC2;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_ph0_v16 = 32'h8E4122AE;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_ph0_v16 = 32'hE8824324;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_ph0_v16 = 32'hBC34F9B7;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_ph0_v16 = 32'h43430EF6;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_ph0_v16 = 32'h712628B8;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_ph0_v16 = 32'h321F26A0;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_ph0_v16 = 32'h0FA2F565;   // F#4 k=11 f=4069.94Hz
        5'd11: pt_ph0_v16 = 32'h70C1C1A2;   // F#4 k=12 f=4439.93Hz
        5'd12: pt_ph0_v16 = 32'h56411ED8;   // F#4 k=13 f=4809.93Hz
        5'd13: pt_ph0_v16 = 32'hC6DEA6B5;   // F#4 k=14 f=5179.92Hz
        5'd14: pt_ph0_v16 = 32'h5309F991;   // F#4 k=15 f=5549.92Hz
        default: pt_ph0_v16 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v17 = 32'h020E209B;   // G4 k=1 f=392.00Hz
        5'd1: pt_inc_v17 = 32'h041C4136;   // G4 k=2 f=783.99Hz
        5'd2: pt_inc_v17 = 32'h062A61D2;   // G4 k=3 f=1175.99Hz
        5'd3: pt_inc_v17 = 32'h0838826D;   // G4 k=4 f=1567.98Hz
        5'd4: pt_inc_v17 = 32'h0A46A308;   // G4 k=5 f=1959.98Hz
        5'd5: pt_inc_v17 = 32'h0C54C3A3;   // G4 k=6 f=2351.97Hz
        5'd6: pt_inc_v17 = 32'h0E62E43E;   // G4 k=7 f=2743.97Hz
        5'd7: pt_inc_v17 = 32'h107104DA;   // G4 k=8 f=3135.96Hz
        5'd8: pt_inc_v17 = 32'h127F2575;   // G4 k=9 f=3527.96Hz
        5'd9: pt_inc_v17 = 32'h148D4610;   // G4 k=10 f=3919.95Hz
        5'd10: pt_inc_v17 = 32'h169B66AB;   // G4 k=11 f=4311.95Hz
        5'd11: pt_inc_v17 = 32'h18A98746;   // G4 k=12 f=4703.95Hz
        5'd12: pt_inc_v17 = 32'h1AB7A7E2;   // G4 k=13 f=5095.94Hz
        5'd13: pt_inc_v17 = 32'h1CC5C87D;   // G4 k=14 f=5487.94Hz
        5'd14: pt_inc_v17 = 32'h1ED3E918;   // G4 k=15 f=5879.93Hz
        default: pt_inc_v17 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v17 = 16'd2802;   // G4 k=1 f=392.00Hz
        5'd1: pt_env0_v17 = 16'd2886;   // G4 k=2 f=783.99Hz
        5'd2: pt_env0_v17 = 16'd702;   // G4 k=3 f=1175.99Hz
        5'd3: pt_env0_v17 = 16'd347;   // G4 k=4 f=1567.98Hz
        5'd4: pt_env0_v17 = 16'd927;   // G4 k=5 f=1959.98Hz
        5'd5: pt_env0_v17 = 16'd379;   // G4 k=6 f=2351.97Hz
        5'd6: pt_env0_v17 = 16'd85;   // G4 k=7 f=2743.97Hz
        5'd7: pt_env0_v17 = 16'd50;   // G4 k=8 f=3135.96Hz
        5'd8: pt_env0_v17 = 16'd146;   // G4 k=9 f=3527.96Hz
        5'd9: pt_env0_v17 = 16'd24;   // G4 k=10 f=3919.95Hz
        5'd10: pt_env0_v17 = 16'd50;   // G4 k=11 f=4311.95Hz
        5'd11: pt_env0_v17 = 16'd17;   // G4 k=12 f=4703.95Hz
        5'd12: pt_env0_v17 = 16'd59;   // G4 k=13 f=5095.94Hz
        5'd13: pt_env0_v17 = 16'd14;   // G4 k=14 f=5487.94Hz
        5'd14: pt_env0_v17 = 16'd26;   // G4 k=15 f=5879.93Hz
        default: pt_env0_v17 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v17 = 12'd57;   // G4 k=1 f=392.00Hz
        5'd1: pt_rate_v17 = 12'd166;   // G4 k=2 f=783.99Hz
        5'd2: pt_rate_v17 = 12'd156;   // G4 k=3 f=1175.99Hz
        5'd3: pt_rate_v17 = 12'd128;   // G4 k=4 f=1567.98Hz
        5'd4: pt_rate_v17 = 12'd250;   // G4 k=5 f=1959.98Hz
        5'd5: pt_rate_v17 = 12'd206;   // G4 k=6 f=2351.97Hz
        5'd6: pt_rate_v17 = 12'd161;   // G4 k=7 f=2743.97Hz
        5'd7: pt_rate_v17 = 12'd256;   // G4 k=8 f=3135.96Hz
        5'd8: pt_rate_v17 = 12'd485;   // G4 k=9 f=3527.96Hz
        5'd9: pt_rate_v17 = 12'd179;   // G4 k=10 f=3919.95Hz
        5'd10: pt_rate_v17 = 12'd220;   // G4 k=11 f=4311.95Hz
        5'd11: pt_rate_v17 = 12'd316;   // G4 k=12 f=4703.95Hz
        5'd12: pt_rate_v17 = 12'd815;   // G4 k=13 f=5095.94Hz
        5'd13: pt_rate_v17 = 12'd928;   // G4 k=14 f=5487.94Hz
        5'd14: pt_rate_v17 = 12'd716;   // G4 k=15 f=5879.93Hz
        default: pt_rate_v17 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v17 = 16'd20235;   // G4 k=1 f=392.00Hz
        5'd1: pt_aatk_v17 = 16'd20539;   // G4 k=2 f=783.99Hz
        5'd2: pt_aatk_v17 = 16'd24260;   // G4 k=3 f=1175.99Hz
        5'd3: pt_aatk_v17 = 16'd28234;   // G4 k=4 f=1567.98Hz
        5'd4: pt_aatk_v17 = 16'd26147;   // G4 k=5 f=1959.98Hz
        5'd5: pt_aatk_v17 = 16'd29370;   // G4 k=6 f=2351.97Hz
        5'd6: pt_aatk_v17 = 16'd32956;   // G4 k=7 f=2743.97Hz
        5'd7: pt_aatk_v17 = 16'd31001;   // G4 k=8 f=3135.96Hz
        5'd8: pt_aatk_v17 = 16'd27247;   // G4 k=9 f=3527.96Hz
        5'd9: pt_aatk_v17 = 16'd36682;   // G4 k=10 f=3919.95Hz
        5'd10: pt_aatk_v17 = 16'd36242;   // G4 k=11 f=4311.95Hz
        5'd11: pt_aatk_v17 = 16'd34240;   // G4 k=12 f=4703.95Hz
        5'd12: pt_aatk_v17 = 16'd26693;   // G4 k=13 f=5095.94Hz
        5'd13: pt_aatk_v17 = 16'd26290;   // G4 k=14 f=5487.94Hz
        5'd14: pt_aatk_v17 = 16'd29510;   // G4 k=15 f=5879.93Hz
        default: pt_aatk_v17 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v17 = 32'hEB461E0C;   // G4 k=1 f=392.00Hz
        5'd1: pt_ph0_v17 = 32'h3876C3AF;   // G4 k=2 f=783.99Hz
        5'd2: pt_ph0_v17 = 32'hE069266E;   // G4 k=3 f=1175.99Hz
        5'd3: pt_ph0_v17 = 32'h503403BC;   // G4 k=4 f=1567.98Hz
        5'd4: pt_ph0_v17 = 32'hD83E983D;   // G4 k=5 f=1959.98Hz
        5'd5: pt_ph0_v17 = 32'h6C1E8981;   // G4 k=6 f=2351.97Hz
        5'd6: pt_ph0_v17 = 32'h18F015D6;   // G4 k=7 f=2743.97Hz
        5'd7: pt_ph0_v17 = 32'h5311C693;   // G4 k=8 f=3135.96Hz
        5'd8: pt_ph0_v17 = 32'h13B1FD32;   // G4 k=9 f=3527.96Hz
        5'd9: pt_ph0_v17 = 32'hAEE2CC19;   // G4 k=10 f=3919.95Hz
        5'd10: pt_ph0_v17 = 32'h1B536289;   // G4 k=11 f=4311.95Hz
        5'd11: pt_ph0_v17 = 32'h974D5808;   // G4 k=12 f=4703.95Hz
        5'd12: pt_ph0_v17 = 32'hB5C483EE;   // G4 k=13 f=5095.94Hz
        5'd13: pt_ph0_v17 = 32'h92937772;   // G4 k=14 f=5487.94Hz
        5'd14: pt_ph0_v17 = 32'h73D460CC;   // G4 k=15 f=5879.93Hz
        default: pt_ph0_v17 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v18 = 32'h022D699C;   // G#4 k=1 f=415.30Hz
        5'd1: pt_inc_v18 = 32'h045AD337;   // G#4 k=2 f=830.61Hz
        5'd2: pt_inc_v18 = 32'h06883CD3;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_inc_v18 = 32'h08B5A66E;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_inc_v18 = 32'h0AE3100A;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_inc_v18 = 32'h0D1079A5;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_inc_v18 = 32'h0F3DE341;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_inc_v18 = 32'h116B4CDC;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_inc_v18 = 32'h1398B678;   // G#4 k=9 f=3737.74Hz
        5'd9: pt_inc_v18 = 32'h15C62013;   // G#4 k=10 f=4153.05Hz
        5'd10: pt_inc_v18 = 32'h17F389AF;   // G#4 k=11 f=4568.35Hz
        5'd11: pt_inc_v18 = 32'h1A20F34A;   // G#4 k=12 f=4983.66Hz
        5'd12: pt_inc_v18 = 32'h1C4E5CE6;   // G#4 k=13 f=5398.96Hz
        5'd13: pt_inc_v18 = 32'h1E7BC681;   // G#4 k=14 f=5814.27Hz
        5'd14: pt_inc_v18 = 32'h20A9301D;   // G#4 k=15 f=6229.57Hz
        default: pt_inc_v18 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v18 = 16'd3192;   // G#4 k=1 f=415.30Hz
        5'd1: pt_env0_v18 = 16'd3807;   // G#4 k=2 f=830.61Hz
        5'd2: pt_env0_v18 = 16'd454;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_env0_v18 = 16'd202;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_env0_v18 = 16'd111;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_env0_v18 = 16'd65;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_env0_v18 = 16'd231;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_env0_v18 = 16'd14;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_env0_v18 = 16'd119;   // G#4 k=9 f=3737.74Hz
        5'd9: pt_env0_v18 = 16'd44;   // G#4 k=10 f=4153.05Hz
        5'd10: pt_env0_v18 = 16'd46;   // G#4 k=11 f=4568.35Hz
        5'd11: pt_env0_v18 = 16'd47;   // G#4 k=12 f=4983.66Hz
        5'd12: pt_env0_v18 = 16'd49;   // G#4 k=13 f=5398.96Hz
        5'd13: pt_env0_v18 = 16'd62;   // G#4 k=14 f=5814.27Hz
        5'd14: pt_env0_v18 = 16'd12;   // G#4 k=15 f=6229.57Hz
        default: pt_env0_v18 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v18 = 12'd67;   // G#4 k=1 f=415.30Hz
        5'd1: pt_rate_v18 = 12'd105;   // G#4 k=2 f=830.61Hz
        5'd2: pt_rate_v18 = 12'd269;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_rate_v18 = 12'd177;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_rate_v18 = 12'd120;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_rate_v18 = 12'd268;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_rate_v18 = 12'd199;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_rate_v18 = 12'd124;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_rate_v18 = 12'd383;   // G#4 k=9 f=3737.74Hz
        5'd9: pt_rate_v18 = 12'd270;   // G#4 k=10 f=4153.05Hz
        5'd10: pt_rate_v18 = 12'd403;   // G#4 k=11 f=4568.35Hz
        5'd11: pt_rate_v18 = 12'd394;   // G#4 k=12 f=4983.66Hz
        5'd12: pt_rate_v18 = 12'd376;   // G#4 k=13 f=5398.96Hz
        5'd13: pt_rate_v18 = 12'd482;   // G#4 k=14 f=5814.27Hz
        5'd14: pt_rate_v18 = 12'd533;   // G#4 k=15 f=6229.57Hz
        default: pt_rate_v18 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v18 = 16'd19544;   // G#4 k=1 f=415.30Hz
        5'd1: pt_aatk_v18 = 16'd22867;   // G#4 k=2 f=830.61Hz
        5'd2: pt_aatk_v18 = 16'd21113;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_aatk_v18 = 16'd26204;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_aatk_v18 = 16'd31047;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_aatk_v18 = 16'd27504;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_aatk_v18 = 16'd31380;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_aatk_v18 = 16'd36601;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_aatk_v18 = 16'd29155;   // G#4 k=9 f=3737.74Hz
        5'd9: pt_aatk_v18 = 16'd33273;   // G#4 k=10 f=4153.05Hz
        5'd10: pt_aatk_v18 = 16'd31041;   // G#4 k=11 f=4568.35Hz
        5'd11: pt_aatk_v18 = 16'd32301;   // G#4 k=12 f=4983.66Hz
        5'd12: pt_aatk_v18 = 16'd33724;   // G#4 k=13 f=5398.96Hz
        5'd13: pt_aatk_v18 = 16'd32392;   // G#4 k=14 f=5814.27Hz
        5'd14: pt_aatk_v18 = 16'd32316;   // G#4 k=15 f=6229.57Hz
        default: pt_aatk_v18 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v18 = 32'h067E7A6A;   // G#4 k=1 f=415.30Hz
        5'd1: pt_ph0_v18 = 32'hEF588093;   // G#4 k=2 f=830.61Hz
        5'd2: pt_ph0_v18 = 32'h161979F6;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_ph0_v18 = 32'h1DD309C5;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_ph0_v18 = 32'hE19961B0;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_ph0_v18 = 32'h784FEB87;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_ph0_v18 = 32'h22D66A18;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_ph0_v18 = 32'h1D36A792;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_ph0_v18 = 32'hF154ED70;   // G#4 k=9 f=3737.74Hz
        5'd9: pt_ph0_v18 = 32'hF3A1BAAD;   // G#4 k=10 f=4153.05Hz
        5'd10: pt_ph0_v18 = 32'h5A7B6237;   // G#4 k=11 f=4568.35Hz
        5'd11: pt_ph0_v18 = 32'hF18EBC09;   // G#4 k=12 f=4983.66Hz
        5'd12: pt_ph0_v18 = 32'hE7FE6ECE;   // G#4 k=13 f=5398.96Hz
        5'd13: pt_ph0_v18 = 32'h6ED0C07D;   // G#4 k=14 f=5814.27Hz
        5'd14: pt_ph0_v18 = 32'hAD343952;   // G#4 k=15 f=6229.57Hz
        default: pt_ph0_v18 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v19 = 32'h024E8ED9;   // A4 k=1 f=440.00Hz
        5'd1: pt_inc_v19 = 32'h049D1DB3;   // A4 k=2 f=880.00Hz
        5'd2: pt_inc_v19 = 32'h06EBAC8C;   // A4 k=3 f=1320.00Hz
        5'd3: pt_inc_v19 = 32'h093A3B65;   // A4 k=4 f=1760.00Hz
        5'd4: pt_inc_v19 = 32'h0B88CA3E;   // A4 k=5 f=2200.00Hz
        5'd5: pt_inc_v19 = 32'h0DD75918;   // A4 k=6 f=2640.00Hz
        5'd6: pt_inc_v19 = 32'h1025E7F1;   // A4 k=7 f=3080.00Hz
        5'd7: pt_inc_v19 = 32'h127476CA;   // A4 k=8 f=3520.00Hz
        5'd8: pt_inc_v19 = 32'h14C305A4;   // A4 k=9 f=3960.00Hz
        5'd10: pt_inc_v19 = 32'h19602356;   // A4 k=11 f=4840.00Hz
        5'd11: pt_inc_v19 = 32'h1BAEB230;   // A4 k=12 f=5280.00Hz
        5'd12: pt_inc_v19 = 32'h1DFD4109;   // A4 k=13 f=5720.00Hz
        5'd13: pt_inc_v19 = 32'h204BCFE2;   // A4 k=14 f=6160.00Hz
        5'd14: pt_inc_v19 = 32'h229A5EBB;   // A4 k=15 f=6600.00Hz
        default: pt_inc_v19 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v19 = 16'd4948;   // A4 k=1 f=440.00Hz
        5'd1: pt_env0_v19 = 16'd2715;   // A4 k=2 f=880.00Hz
        5'd2: pt_env0_v19 = 16'd254;   // A4 k=3 f=1320.00Hz
        5'd3: pt_env0_v19 = 16'd106;   // A4 k=4 f=1760.00Hz
        5'd4: pt_env0_v19 = 16'd114;   // A4 k=5 f=2200.00Hz
        5'd5: pt_env0_v19 = 16'd45;   // A4 k=6 f=2640.00Hz
        5'd6: pt_env0_v19 = 16'd35;   // A4 k=7 f=3080.00Hz
        5'd7: pt_env0_v19 = 16'd14;   // A4 k=8 f=3520.00Hz
        5'd8: pt_env0_v19 = 16'd21;   // A4 k=9 f=3960.00Hz
        5'd10: pt_env0_v19 = 16'd58;   // A4 k=11 f=4840.00Hz
        5'd11: pt_env0_v19 = 16'd55;   // A4 k=12 f=5280.00Hz
        5'd12: pt_env0_v19 = 16'd9;   // A4 k=13 f=5720.00Hz
        5'd13: pt_env0_v19 = 16'd26;   // A4 k=14 f=6160.00Hz
        5'd14: pt_env0_v19 = 16'd59;   // A4 k=15 f=6600.00Hz
        default: pt_env0_v19 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v19 = 12'd77;   // A4 k=1 f=440.00Hz
        5'd1: pt_rate_v19 = 12'd110;   // A4 k=2 f=880.00Hz
        5'd2: pt_rate_v19 = 12'd338;   // A4 k=3 f=1320.00Hz
        5'd3: pt_rate_v19 = 12'd276;   // A4 k=4 f=1760.00Hz
        5'd4: pt_rate_v19 = 12'd208;   // A4 k=5 f=2200.00Hz
        5'd5: pt_rate_v19 = 12'd235;   // A4 k=6 f=2640.00Hz
        5'd6: pt_rate_v19 = 12'd236;   // A4 k=7 f=3080.00Hz
        5'd7: pt_rate_v19 = 12'd162;   // A4 k=8 f=3520.00Hz
        5'd8: pt_rate_v19 = 12'd194;   // A4 k=9 f=3960.00Hz
        5'd10: pt_rate_v19 = 12'd372;   // A4 k=11 f=4840.00Hz
        5'd11: pt_rate_v19 = 12'd468;   // A4 k=12 f=5280.00Hz
        5'd12: pt_rate_v19 = 12'd839;   // A4 k=13 f=5720.00Hz
        5'd13: pt_rate_v19 = 12'd660;   // A4 k=14 f=6160.00Hz
        5'd14: pt_rate_v19 = 12'd663;   // A4 k=15 f=6600.00Hz
        default: pt_rate_v19 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v19 = 16'd19013;   // A4 k=1 f=440.00Hz
        5'd1: pt_aatk_v19 = 16'd22664;   // A4 k=2 f=880.00Hz
        5'd2: pt_aatk_v19 = 16'd19782;   // A4 k=3 f=1320.00Hz
        5'd3: pt_aatk_v19 = 16'd23395;   // A4 k=4 f=1760.00Hz
        5'd4: pt_aatk_v19 = 16'd27374;   // A4 k=5 f=2200.00Hz
        5'd5: pt_aatk_v19 = 16'd28435;   // A4 k=6 f=2640.00Hz
        5'd6: pt_aatk_v19 = 16'd30103;   // A4 k=7 f=3080.00Hz
        5'd7: pt_aatk_v19 = 16'd34539;   // A4 k=8 f=3520.00Hz
        5'd8: pt_aatk_v19 = 16'd34651;   // A4 k=9 f=3960.00Hz
        5'd10: pt_aatk_v19 = 16'd31746;   // A4 k=11 f=4840.00Hz
        5'd11: pt_aatk_v19 = 16'd30778;   // A4 k=12 f=5280.00Hz
        5'd12: pt_aatk_v19 = 16'd26429;   // A4 k=13 f=5720.00Hz
        5'd13: pt_aatk_v19 = 16'd29467;   // A4 k=14 f=6160.00Hz
        5'd14: pt_aatk_v19 = 16'd30243;   // A4 k=15 f=6600.00Hz
        default: pt_aatk_v19 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v19 = 32'h69D3E7FD;   // A4 k=1 f=440.00Hz
        5'd1: pt_ph0_v19 = 32'hB0A5E00B;   // A4 k=2 f=880.00Hz
        5'd2: pt_ph0_v19 = 32'h98344D79;   // A4 k=3 f=1320.00Hz
        5'd3: pt_ph0_v19 = 32'h22F7D334;   // A4 k=4 f=1760.00Hz
        5'd4: pt_ph0_v19 = 32'hCAB6D0FC;   // A4 k=5 f=2200.00Hz
        5'd5: pt_ph0_v19 = 32'h05824DA8;   // A4 k=6 f=2640.00Hz
        5'd6: pt_ph0_v19 = 32'hCADA1DD3;   // A4 k=7 f=3080.00Hz
        5'd7: pt_ph0_v19 = 32'hA5671503;   // A4 k=8 f=3520.00Hz
        5'd8: pt_ph0_v19 = 32'hBF2D8AE0;   // A4 k=9 f=3960.00Hz
        5'd10: pt_ph0_v19 = 32'hC04036D8;   // A4 k=11 f=4840.00Hz
        5'd11: pt_ph0_v19 = 32'h15F1E735;   // A4 k=12 f=5280.00Hz
        5'd12: pt_ph0_v19 = 32'h0BFD247E;   // A4 k=13 f=5720.00Hz
        5'd13: pt_ph0_v19 = 32'h4124CB27;   // A4 k=14 f=6160.00Hz
        5'd14: pt_ph0_v19 = 32'hD048A7E7;   // A4 k=15 f=6600.00Hz
        default: pt_ph0_v19 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v20 = 32'h0271ACA6;   // A#4 k=1 f=466.16Hz
        5'd1: pt_inc_v20 = 32'h04E3594C;   // A#4 k=2 f=932.33Hz
        5'd2: pt_inc_v20 = 32'h075505F2;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_inc_v20 = 32'h09C6B298;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_inc_v20 = 32'h0C385F3F;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_inc_v20 = 32'h0EAA0BE5;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_inc_v20 = 32'h111BB88B;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_inc_v20 = 32'h138D6531;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_inc_v20 = 32'h15FF11D7;   // A#4 k=9 f=4195.47Hz
        5'd9: pt_inc_v20 = 32'h1870BE7D;   // A#4 k=10 f=4661.64Hz
        5'd10: pt_inc_v20 = 32'h1AE26B23;   // A#4 k=11 f=5127.80Hz
        5'd11: pt_inc_v20 = 32'h1D5417C9;   // A#4 k=12 f=5593.97Hz
        5'd12: pt_inc_v20 = 32'h1FC5C46F;   // A#4 k=13 f=6060.13Hz
        5'd13: pt_inc_v20 = 32'h22377115;   // A#4 k=14 f=6526.29Hz
        5'd14: pt_inc_v20 = 32'h24A91DBC;   // A#4 k=15 f=6992.46Hz
        default: pt_inc_v20 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v20 = 16'd781;   // A#4 k=1 f=466.16Hz
        5'd1: pt_env0_v20 = 16'd776;   // A#4 k=2 f=932.33Hz
        5'd2: pt_env0_v20 = 16'd889;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_env0_v20 = 16'd1398;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_env0_v20 = 16'd2090;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_env0_v20 = 16'd543;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_env0_v20 = 16'd109;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_env0_v20 = 16'd642;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_env0_v20 = 16'd32;   // A#4 k=9 f=4195.47Hz
        5'd9: pt_env0_v20 = 16'd152;   // A#4 k=10 f=4661.64Hz
        5'd10: pt_env0_v20 = 16'd414;   // A#4 k=11 f=5127.80Hz
        5'd11: pt_env0_v20 = 16'd435;   // A#4 k=12 f=5593.97Hz
        5'd12: pt_env0_v20 = 16'd211;   // A#4 k=13 f=6060.13Hz
        5'd13: pt_env0_v20 = 16'd21;   // A#4 k=14 f=6526.29Hz
        5'd14: pt_env0_v20 = 16'd395;   // A#4 k=15 f=6992.46Hz
        default: pt_env0_v20 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v20 = 12'd130;   // A#4 k=1 f=466.16Hz
        5'd1: pt_rate_v20 = 12'd692;   // A#4 k=2 f=932.33Hz
        5'd2: pt_rate_v20 = 12'd276;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_rate_v20 = 12'd358;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_rate_v20 = 12'd254;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_rate_v20 = 12'd254;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_rate_v20 = 12'd285;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_rate_v20 = 12'd312;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_rate_v20 = 12'd293;   // A#4 k=9 f=4195.47Hz
        5'd9: pt_rate_v20 = 12'd560;   // A#4 k=10 f=4661.64Hz
        5'd10: pt_rate_v20 = 12'd493;   // A#4 k=11 f=5127.80Hz
        5'd11: pt_rate_v20 = 12'd428;   // A#4 k=12 f=5593.97Hz
        5'd12: pt_rate_v20 = 12'd961;   // A#4 k=13 f=6060.13Hz
        5'd13: pt_rate_v20 = 12'd1118;   // A#4 k=14 f=6526.29Hz
        5'd14: pt_rate_v20 = 12'd556;   // A#4 k=15 f=6992.46Hz
        default: pt_rate_v20 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v20 = 16'd16858;   // A#4 k=1 f=466.16Hz
        5'd1: pt_aatk_v20 = 16'd13146;   // A#4 k=2 f=932.33Hz
        5'd2: pt_aatk_v20 = 16'd20971;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_aatk_v20 = 16'd21752;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_aatk_v20 = 16'd26034;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_aatk_v20 = 16'd27896;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_aatk_v20 = 16'd28700;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_aatk_v20 = 16'd29463;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_aatk_v20 = 16'd31333;   // A#4 k=9 f=4195.47Hz
        5'd9: pt_aatk_v20 = 16'd27175;   // A#4 k=10 f=4661.64Hz
        5'd10: pt_aatk_v20 = 16'd29316;   // A#4 k=11 f=5127.80Hz
        5'd11: pt_aatk_v20 = 16'd31571;   // A#4 k=12 f=5593.97Hz
        5'd12: pt_aatk_v20 = 16'd25187;   // A#4 k=13 f=6060.13Hz
        5'd13: pt_aatk_v20 = 16'd24540;   // A#4 k=14 f=6526.29Hz
        5'd14: pt_aatk_v20 = 16'd31913;   // A#4 k=15 f=6992.46Hz
        default: pt_aatk_v20 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v20 = 32'h5230921D;   // A#4 k=1 f=466.16Hz
        5'd1: pt_ph0_v20 = 32'h4E1156A4;   // A#4 k=2 f=932.33Hz
        5'd2: pt_ph0_v20 = 32'hFC797C06;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_ph0_v20 = 32'hACC36ABA;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_ph0_v20 = 32'hF31D0470;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_ph0_v20 = 32'h21F69A99;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_ph0_v20 = 32'hA61DBAAB;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_ph0_v20 = 32'h385293EF;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_ph0_v20 = 32'h2FC799F8;   // A#4 k=9 f=4195.47Hz
        5'd9: pt_ph0_v20 = 32'h43E60704;   // A#4 k=10 f=4661.64Hz
        5'd10: pt_ph0_v20 = 32'hE3DE1667;   // A#4 k=11 f=5127.80Hz
        5'd11: pt_ph0_v20 = 32'h034E3B2E;   // A#4 k=12 f=5593.97Hz
        5'd12: pt_ph0_v20 = 32'h4158BE5B;   // A#4 k=13 f=6060.13Hz
        5'd13: pt_ph0_v20 = 32'h84ED5752;   // A#4 k=14 f=6526.29Hz
        5'd14: pt_ph0_v20 = 32'hEEE70A71;   // A#4 k=15 f=6992.46Hz
        default: pt_ph0_v20 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v21 = 32'h0296E103;   // B4 k=1 f=493.88Hz
        5'd1: pt_inc_v21 = 32'h052DC205;   // B4 k=2 f=987.77Hz
        5'd2: pt_inc_v21 = 32'h07C4A308;   // B4 k=3 f=1481.65Hz
        5'd3: pt_inc_v21 = 32'h0A5B840A;   // B4 k=4 f=1975.53Hz
        5'd4: pt_inc_v21 = 32'h0CF2650D;   // B4 k=5 f=2469.42Hz
        5'd5: pt_inc_v21 = 32'h0F894610;   // B4 k=6 f=2963.30Hz
        5'd6: pt_inc_v21 = 32'h12202712;   // B4 k=7 f=3457.18Hz
        5'd7: pt_inc_v21 = 32'h14B70815;   // B4 k=8 f=3951.07Hz
        5'd8: pt_inc_v21 = 32'h174DE917;   // B4 k=9 f=4444.95Hz
        5'd9: pt_inc_v21 = 32'h19E4CA1A;   // B4 k=10 f=4938.83Hz
        5'd10: pt_inc_v21 = 32'h1C7BAB1D;   // B4 k=11 f=5432.72Hz
        5'd11: pt_inc_v21 = 32'h1F128C1F;   // B4 k=12 f=5926.60Hz
        5'd12: pt_inc_v21 = 32'h21A96D22;   // B4 k=13 f=6420.48Hz
        5'd13: pt_inc_v21 = 32'h24404E24;   // B4 k=14 f=6914.37Hz
        5'd14: pt_inc_v21 = 32'h26D72F27;   // B4 k=15 f=7408.25Hz
        default: pt_inc_v21 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v21 = 16'd1787;   // B4 k=1 f=493.88Hz
        5'd1: pt_env0_v21 = 16'd2311;   // B4 k=2 f=987.77Hz
        5'd2: pt_env0_v21 = 16'd370;   // B4 k=3 f=1481.65Hz
        5'd3: pt_env0_v21 = 16'd1383;   // B4 k=4 f=1975.53Hz
        5'd4: pt_env0_v21 = 16'd1298;   // B4 k=5 f=2469.42Hz
        5'd5: pt_env0_v21 = 16'd175;   // B4 k=6 f=2963.30Hz
        5'd6: pt_env0_v21 = 16'd167;   // B4 k=7 f=3457.18Hz
        5'd7: pt_env0_v21 = 16'd34;   // B4 k=8 f=3951.07Hz
        5'd8: pt_env0_v21 = 16'd175;   // B4 k=9 f=4444.95Hz
        5'd9: pt_env0_v21 = 16'd35;   // B4 k=10 f=4938.83Hz
        5'd10: pt_env0_v21 = 16'd291;   // B4 k=11 f=5432.72Hz
        5'd11: pt_env0_v21 = 16'd335;   // B4 k=12 f=5926.60Hz
        5'd12: pt_env0_v21 = 16'd25;   // B4 k=13 f=6420.48Hz
        5'd13: pt_env0_v21 = 16'd112;   // B4 k=14 f=6914.37Hz
        5'd14: pt_env0_v21 = 16'd93;   // B4 k=15 f=7408.25Hz
        default: pt_env0_v21 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v21 = 12'd43;   // B4 k=1 f=493.88Hz
        5'd1: pt_rate_v21 = 12'd165;   // B4 k=2 f=987.77Hz
        5'd2: pt_rate_v21 = 12'd190;   // B4 k=3 f=1481.65Hz
        5'd3: pt_rate_v21 = 12'd239;   // B4 k=4 f=1975.53Hz
        5'd4: pt_rate_v21 = 12'd227;   // B4 k=5 f=2469.42Hz
        5'd5: pt_rate_v21 = 12'd301;   // B4 k=6 f=2963.30Hz
        5'd6: pt_rate_v21 = 12'd314;   // B4 k=7 f=3457.18Hz
        5'd7: pt_rate_v21 = 12'd73;   // B4 k=8 f=3951.07Hz
        5'd8: pt_rate_v21 = 12'd389;   // B4 k=9 f=4444.95Hz
        5'd9: pt_rate_v21 = 12'd229;   // B4 k=10 f=4938.83Hz
        5'd10: pt_rate_v21 = 12'd425;   // B4 k=11 f=5432.72Hz
        5'd11: pt_rate_v21 = 12'd611;   // B4 k=12 f=5926.60Hz
        5'd12: pt_rate_v21 = 12'd635;   // B4 k=13 f=6420.48Hz
        5'd13: pt_rate_v21 = 12'd586;   // B4 k=14 f=6914.37Hz
        5'd14: pt_rate_v21 = 12'd801;   // B4 k=15 f=7408.25Hz
        default: pt_rate_v21 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v21 = 16'd21410;   // B4 k=1 f=493.88Hz
        5'd1: pt_aatk_v21 = 16'd20569;   // B4 k=2 f=987.77Hz
        5'd2: pt_aatk_v21 = 16'd23128;   // B4 k=3 f=1481.65Hz
        5'd3: pt_aatk_v21 = 16'd24315;   // B4 k=4 f=1975.53Hz
        5'd4: pt_aatk_v21 = 16'd26789;   // B4 k=5 f=2469.42Hz
        5'd5: pt_aatk_v21 = 16'd26687;   // B4 k=6 f=2963.30Hz
        5'd6: pt_aatk_v21 = 16'd27975;   // B4 k=7 f=3457.18Hz
        5'd7: pt_aatk_v21 = 16'd40616;   // B4 k=8 f=3951.07Hz
        5'd8: pt_aatk_v21 = 16'd29042;   // B4 k=9 f=4444.95Hz
        5'd9: pt_aatk_v21 = 16'd34639;   // B4 k=10 f=4938.83Hz
        5'd10: pt_aatk_v21 = 16'd30593;   // B4 k=11 f=5432.72Hz
        5'd11: pt_aatk_v21 = 16'd28420;   // B4 k=12 f=5926.60Hz
        5'd12: pt_aatk_v21 = 16'd28973;   // B4 k=13 f=6420.48Hz
        5'd13: pt_aatk_v21 = 16'd30572;   // B4 k=14 f=6914.37Hz
        5'd14: pt_aatk_v21 = 16'd28444;   // B4 k=15 f=7408.25Hz
        default: pt_aatk_v21 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v21 = 32'hFA8C9905;   // B4 k=1 f=493.88Hz
        5'd1: pt_ph0_v21 = 32'h5C2DB7B3;   // B4 k=2 f=987.77Hz
        5'd2: pt_ph0_v21 = 32'hB1AFF91E;   // B4 k=3 f=1481.65Hz
        5'd3: pt_ph0_v21 = 32'h9F0FD558;   // B4 k=4 f=1975.53Hz
        5'd4: pt_ph0_v21 = 32'hD13816CA;   // B4 k=5 f=2469.42Hz
        5'd5: pt_ph0_v21 = 32'hAFC6D87A;   // B4 k=6 f=2963.30Hz
        5'd6: pt_ph0_v21 = 32'hEDFDE1DE;   // B4 k=7 f=3457.18Hz
        5'd7: pt_ph0_v21 = 32'h69072E9D;   // B4 k=8 f=3951.07Hz
        5'd8: pt_ph0_v21 = 32'h164DD617;   // B4 k=9 f=4444.95Hz
        5'd9: pt_ph0_v21 = 32'h3D636A50;   // B4 k=10 f=4938.83Hz
        5'd10: pt_ph0_v21 = 32'h55010AC4;   // B4 k=11 f=5432.72Hz
        5'd11: pt_ph0_v21 = 32'hFF6FADE8;   // B4 k=12 f=5926.60Hz
        5'd12: pt_ph0_v21 = 32'h5087B8A1;   // B4 k=13 f=6420.48Hz
        5'd13: pt_ph0_v21 = 32'h55EAA668;   // B4 k=14 f=6914.37Hz
        5'd14: pt_ph0_v21 = 32'h25D3799B;   // B4 k=15 f=7408.25Hz
        default: pt_ph0_v21 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v22 = 32'h02BE4BB8;   // C5 k=1 f=523.25Hz
        5'd1: pt_inc_v22 = 32'h057C9770;   // C5 k=2 f=1046.50Hz
        5'd2: pt_inc_v22 = 32'h083AE329;   // C5 k=3 f=1569.75Hz
        5'd3: pt_inc_v22 = 32'h0AF92EE1;   // C5 k=4 f=2093.00Hz
        5'd4: pt_inc_v22 = 32'h0DB77A99;   // C5 k=5 f=2616.26Hz
        5'd5: pt_inc_v22 = 32'h1075C651;   // C5 k=6 f=3139.51Hz
        5'd6: pt_inc_v22 = 32'h13341209;   // C5 k=7 f=3662.76Hz
        5'd7: pt_inc_v22 = 32'h15F25DC2;   // C5 k=8 f=4186.01Hz
        5'd8: pt_inc_v22 = 32'h18B0A97A;   // C5 k=9 f=4709.26Hz
        5'd9: pt_inc_v22 = 32'h1B6EF532;   // C5 k=10 f=5232.51Hz
        5'd10: pt_inc_v22 = 32'h1E2D40EA;   // C5 k=11 f=5755.76Hz
        5'd11: pt_inc_v22 = 32'h20EB8CA2;   // C5 k=12 f=6279.01Hz
        5'd12: pt_inc_v22 = 32'h23A9D85A;   // C5 k=13 f=6802.26Hz
        5'd13: pt_inc_v22 = 32'h26682413;   // C5 k=14 f=7325.52Hz
        5'd14: pt_inc_v22 = 32'h29266FCB;   // C5 k=15 f=7848.77Hz
        default: pt_inc_v22 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v22 = 16'd875;   // C5 k=1 f=523.25Hz
        5'd1: pt_env0_v22 = 16'd5905;   // C5 k=2 f=1046.50Hz
        5'd2: pt_env0_v22 = 16'd938;   // C5 k=3 f=1569.75Hz
        5'd3: pt_env0_v22 = 16'd682;   // C5 k=4 f=2093.00Hz
        5'd4: pt_env0_v22 = 16'd111;   // C5 k=5 f=2616.26Hz
        5'd5: pt_env0_v22 = 16'd39;   // C5 k=6 f=3139.51Hz
        5'd6: pt_env0_v22 = 16'd25;   // C5 k=7 f=3662.76Hz
        5'd7: pt_env0_v22 = 16'd45;   // C5 k=8 f=4186.01Hz
        5'd8: pt_env0_v22 = 16'd18;   // C5 k=9 f=4709.26Hz
        5'd9: pt_env0_v22 = 16'd67;   // C5 k=10 f=5232.51Hz
        5'd10: pt_env0_v22 = 16'd148;   // C5 k=11 f=5755.76Hz
        5'd11: pt_env0_v22 = 16'd66;   // C5 k=12 f=6279.01Hz
        5'd12: pt_env0_v22 = 16'd55;   // C5 k=13 f=6802.26Hz
        5'd13: pt_env0_v22 = 16'd97;   // C5 k=14 f=7325.52Hz
        5'd14: pt_env0_v22 = 16'd19;   // C5 k=15 f=7848.77Hz
        default: pt_env0_v22 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v22 = 12'd52;   // C5 k=1 f=523.25Hz
        5'd1: pt_rate_v22 = 12'd382;   // C5 k=2 f=1046.50Hz
        5'd2: pt_rate_v22 = 12'd232;   // C5 k=3 f=1569.75Hz
        5'd3: pt_rate_v22 = 12'd440;   // C5 k=4 f=2093.00Hz
        5'd4: pt_rate_v22 = 12'd403;   // C5 k=5 f=2616.26Hz
        5'd5: pt_rate_v22 = 12'd330;   // C5 k=6 f=3139.51Hz
        5'd6: pt_rate_v22 = 12'd248;   // C5 k=7 f=3662.76Hz
        5'd7: pt_rate_v22 = 12'd325;   // C5 k=8 f=4186.01Hz
        5'd8: pt_rate_v22 = 12'd244;   // C5 k=9 f=4709.26Hz
        5'd9: pt_rate_v22 = 12'd817;   // C5 k=10 f=5232.51Hz
        5'd10: pt_rate_v22 = 12'd1444;   // C5 k=11 f=5755.76Hz
        5'd11: pt_rate_v22 = 12'd1633;   // C5 k=12 f=6279.01Hz
        5'd12: pt_rate_v22 = 12'd1427;   // C5 k=13 f=6802.26Hz
        5'd13: pt_rate_v22 = 12'd1863;   // C5 k=14 f=7325.52Hz
        5'd14: pt_rate_v22 = 12'd949;   // C5 k=15 f=7848.77Hz
        default: pt_rate_v22 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v22 = 16'd20629;   // C5 k=1 f=523.25Hz
        5'd1: pt_aatk_v22 = 16'd16247;   // C5 k=2 f=1046.50Hz
        5'd2: pt_aatk_v22 = 16'd21983;   // C5 k=3 f=1569.75Hz
        5'd3: pt_aatk_v22 = 16'd20427;   // C5 k=4 f=2093.00Hz
        5'd4: pt_aatk_v22 = 16'd22892;   // C5 k=5 f=2616.26Hz
        5'd5: pt_aatk_v22 = 16'd26017;   // C5 k=6 f=3139.51Hz
        5'd6: pt_aatk_v22 = 16'd29725;   // C5 k=7 f=3662.76Hz
        5'd7: pt_aatk_v22 = 16'd29168;   // C5 k=8 f=4186.01Hz
        5'd8: pt_aatk_v22 = 16'd32811;   // C5 k=9 f=4709.26Hz
        5'd9: pt_aatk_v22 = 16'd23991;   // C5 k=10 f=5232.51Hz
        5'd10: pt_aatk_v22 = 16'd19979;   // C5 k=11 f=5755.76Hz
        5'd11: pt_aatk_v22 = 16'd19644;   // C5 k=12 f=6279.01Hz
        5'd12: pt_aatk_v22 = 16'd21570;   // C5 k=13 f=6802.26Hz
        5'd13: pt_aatk_v22 = 16'd19778;   // C5 k=14 f=7325.52Hz
        5'd14: pt_aatk_v22 = 16'd26819;   // C5 k=15 f=7848.77Hz
        default: pt_aatk_v22 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v22 = 32'h16E7A18B;   // C5 k=1 f=523.25Hz
        5'd1: pt_ph0_v22 = 32'hB800ACE0;   // C5 k=2 f=1046.50Hz
        5'd2: pt_ph0_v22 = 32'h1E03BFEE;   // C5 k=3 f=1569.75Hz
        5'd3: pt_ph0_v22 = 32'h56372931;   // C5 k=4 f=2093.00Hz
        5'd4: pt_ph0_v22 = 32'hD13B4799;   // C5 k=5 f=2616.26Hz
        5'd5: pt_ph0_v22 = 32'h80CF54DD;   // C5 k=6 f=3139.51Hz
        5'd6: pt_ph0_v22 = 32'h20CCFF17;   // C5 k=7 f=3662.76Hz
        5'd7: pt_ph0_v22 = 32'h5AE65080;   // C5 k=8 f=4186.01Hz
        5'd8: pt_ph0_v22 = 32'h8E35079B;   // C5 k=9 f=4709.26Hz
        5'd9: pt_ph0_v22 = 32'hF6086FF8;   // C5 k=10 f=5232.51Hz
        5'd10: pt_ph0_v22 = 32'h85155263;   // C5 k=11 f=5755.76Hz
        5'd11: pt_ph0_v22 = 32'hC47D9C2F;   // C5 k=12 f=6279.01Hz
        5'd12: pt_ph0_v22 = 32'h88F0BDB3;   // C5 k=13 f=6802.26Hz
        5'd13: pt_ph0_v22 = 32'h7794D010;   // C5 k=14 f=7325.52Hz
        5'd14: pt_ph0_v22 = 32'h5F427DBB;   // C5 k=15 f=7848.77Hz
        default: pt_ph0_v22 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v23 = 32'h02E80E74;   // C#5 k=1 f=554.37Hz
        5'd1: pt_inc_v23 = 32'h05D01CE8;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_inc_v23 = 32'h08B82B5C;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_inc_v23 = 32'h0BA039D1;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_inc_v23 = 32'h0E884845;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_inc_v23 = 32'h117056B9;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_inc_v23 = 32'h1458652D;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_inc_v23 = 32'h174073A1;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_inc_v23 = 32'h1A288215;   // C#5 k=9 f=4989.29Hz
        5'd9: pt_inc_v23 = 32'h1D109089;   // C#5 k=10 f=5543.65Hz
        5'd10: pt_inc_v23 = 32'h1FF89EFD;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_inc_v23 = 32'h22E0AD71;   // C#5 k=12 f=6652.38Hz
        5'd12: pt_inc_v23 = 32'h25C8BBE6;   // C#5 k=13 f=7206.75Hz
        default: pt_inc_v23 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v23 = 16'd4172;   // C#5 k=1 f=554.37Hz
        5'd1: pt_env0_v23 = 16'd1254;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_env0_v23 = 16'd147;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_env0_v23 = 16'd2691;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_env0_v23 = 16'd159;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_env0_v23 = 16'd71;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_env0_v23 = 16'd50;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_env0_v23 = 16'd31;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_env0_v23 = 16'd108;   // C#5 k=9 f=4989.29Hz
        5'd9: pt_env0_v23 = 16'd126;   // C#5 k=10 f=5543.65Hz
        5'd10: pt_env0_v23 = 16'd46;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_env0_v23 = 16'd17;   // C#5 k=12 f=6652.38Hz
        5'd12: pt_env0_v23 = 16'd34;   // C#5 k=13 f=7206.75Hz
        default: pt_env0_v23 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v23 = 12'd214;   // C#5 k=1 f=554.37Hz
        5'd1: pt_rate_v23 = 12'd215;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_rate_v23 = 12'd99;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_rate_v23 = 12'd383;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_rate_v23 = 12'd503;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_rate_v23 = 12'd336;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_rate_v23 = 12'd416;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_rate_v23 = 12'd415;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_rate_v23 = 12'd704;   // C#5 k=9 f=4989.29Hz
        5'd9: pt_rate_v23 = 12'd1145;   // C#5 k=10 f=5543.65Hz
        5'd10: pt_rate_v23 = 12'd1497;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_rate_v23 = 12'd1688;   // C#5 k=12 f=6652.38Hz
        5'd12: pt_rate_v23 = 12'd1385;   // C#5 k=13 f=7206.75Hz
        default: pt_rate_v23 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v23 = 16'd14765;   // C#5 k=1 f=554.37Hz
        5'd1: pt_aatk_v23 = 16'd19216;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_aatk_v23 = 16'd26895;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_aatk_v23 = 16'd21309;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_aatk_v23 = 16'd21385;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_aatk_v23 = 16'd25895;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_aatk_v23 = 16'd25851;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_aatk_v23 = 16'd27240;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_aatk_v23 = 16'd24199;   // C#5 k=9 f=4989.29Hz
        5'd9: pt_aatk_v23 = 16'd21129;   // C#5 k=10 f=5543.65Hz
        5'd10: pt_aatk_v23 = 16'd19668;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_aatk_v23 = 16'd19352;   // C#5 k=12 f=6652.38Hz
        5'd12: pt_aatk_v23 = 16'd21839;   // C#5 k=13 f=7206.75Hz
        default: pt_aatk_v23 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v23 = 32'h0E57ADE1;   // C#5 k=1 f=554.37Hz
        5'd1: pt_ph0_v23 = 32'h8695F294;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_ph0_v23 = 32'h202AB017;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_ph0_v23 = 32'hB3250B63;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_ph0_v23 = 32'h5B652AA7;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_ph0_v23 = 32'h3807025F;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_ph0_v23 = 32'hD1DD4159;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_ph0_v23 = 32'h473DE9E2;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_ph0_v23 = 32'hBA3B48A2;   // C#5 k=9 f=4989.29Hz
        5'd9: pt_ph0_v23 = 32'hB6C34795;   // C#5 k=10 f=5543.65Hz
        5'd10: pt_ph0_v23 = 32'h1800992D;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_ph0_v23 = 32'h6F82AB5F;   // C#5 k=12 f=6652.38Hz
        5'd12: pt_ph0_v23 = 32'h67C3934B;   // C#5 k=13 f=7206.75Hz
        default: pt_ph0_v23 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v24 = 32'h03144CE4;   // D5 k=1 f=587.33Hz
        5'd1: pt_inc_v24 = 32'h062899C9;   // D5 k=2 f=1174.66Hz
        5'd2: pt_inc_v24 = 32'h093CE6AD;   // D5 k=3 f=1761.99Hz
        5'd3: pt_inc_v24 = 32'h0C513391;   // D5 k=4 f=2349.32Hz
        5'd4: pt_inc_v24 = 32'h0F658076;   // D5 k=5 f=2936.65Hz
        5'd5: pt_inc_v24 = 32'h1279CD5A;   // D5 k=6 f=3523.98Hz
        5'd6: pt_inc_v24 = 32'h158E1A3E;   // D5 k=7 f=4111.31Hz
        5'd7: pt_inc_v24 = 32'h18A26723;   // D5 k=8 f=4698.64Hz
        5'd8: pt_inc_v24 = 32'h1BB6B407;   // D5 k=9 f=5285.97Hz
        5'd9: pt_inc_v24 = 32'h1ECB00EB;   // D5 k=10 f=5873.30Hz
        5'd10: pt_inc_v24 = 32'h21DF4DCF;   // D5 k=11 f=6460.62Hz
        default: pt_inc_v24 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v24 = 16'd5009;   // D5 k=1 f=587.33Hz
        5'd1: pt_env0_v24 = 16'd1286;   // D5 k=2 f=1174.66Hz
        5'd2: pt_env0_v24 = 16'd1763;   // D5 k=3 f=1761.99Hz
        5'd3: pt_env0_v24 = 16'd295;   // D5 k=4 f=2349.32Hz
        5'd4: pt_env0_v24 = 16'd64;   // D5 k=5 f=2936.65Hz
        5'd5: pt_env0_v24 = 16'd30;   // D5 k=6 f=3523.98Hz
        5'd6: pt_env0_v24 = 16'd42;   // D5 k=7 f=4111.31Hz
        5'd7: pt_env0_v24 = 16'd20;   // D5 k=8 f=4698.64Hz
        5'd8: pt_env0_v24 = 16'd9;   // D5 k=9 f=5285.97Hz
        5'd9: pt_env0_v24 = 16'd25;   // D5 k=10 f=5873.30Hz
        5'd10: pt_env0_v24 = 16'd10;   // D5 k=11 f=6460.62Hz
        default: pt_env0_v24 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v24 = 12'd92;   // D5 k=1 f=587.33Hz
        5'd1: pt_rate_v24 = 12'd102;   // D5 k=2 f=1174.66Hz
        5'd2: pt_rate_v24 = 12'd306;   // D5 k=3 f=1761.99Hz
        5'd3: pt_rate_v24 = 12'd271;   // D5 k=4 f=2349.32Hz
        5'd4: pt_rate_v24 = 12'd275;   // D5 k=5 f=2936.65Hz
        5'd5: pt_rate_v24 = 12'd226;   // D5 k=6 f=3523.98Hz
        5'd6: pt_rate_v24 = 12'd487;   // D5 k=7 f=4111.31Hz
        5'd7: pt_rate_v24 = 12'd312;   // D5 k=8 f=4698.64Hz
        5'd8: pt_rate_v24 = 12'd392;   // D5 k=9 f=5285.97Hz
        5'd9: pt_rate_v24 = 12'd912;   // D5 k=10 f=5873.30Hz
        5'd10: pt_rate_v24 = 12'd1215;   // D5 k=11 f=6460.62Hz
        default: pt_rate_v24 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v24 = 16'd18275;   // D5 k=1 f=587.33Hz
        5'd1: pt_aatk_v24 = 16'd23020;   // D5 k=2 f=1174.66Hz
        5'd2: pt_aatk_v24 = 16'd20368;   // D5 k=3 f=1761.99Hz
        5'd3: pt_aatk_v24 = 16'd23510;   // D5 k=4 f=2349.32Hz
        5'd4: pt_aatk_v24 = 16'd25497;   // D5 k=5 f=2936.65Hz
        5'd5: pt_aatk_v24 = 16'd28718;   // D5 k=6 f=3523.98Hz
        5'd6: pt_aatk_v24 = 16'd24665;   // D5 k=7 f=4111.31Hz
        5'd7: pt_aatk_v24 = 16'd29477;   // D5 k=8 f=4698.64Hz
        5'd8: pt_aatk_v24 = 16'd28979;   // D5 k=9 f=5285.97Hz
        5'd9: pt_aatk_v24 = 16'd23057;   // D5 k=10 f=5873.30Hz
        5'd10: pt_aatk_v24 = 16'd21478;   // D5 k=11 f=6460.62Hz
        default: pt_aatk_v24 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v24 = 32'hA0F5CA5E;   // D5 k=1 f=587.33Hz
        5'd1: pt_ph0_v24 = 32'h2E7ED6A1;   // D5 k=2 f=1174.66Hz
        5'd2: pt_ph0_v24 = 32'h61FB1274;   // D5 k=3 f=1761.99Hz
        5'd3: pt_ph0_v24 = 32'h7507E66E;   // D5 k=4 f=2349.32Hz
        5'd4: pt_ph0_v24 = 32'hB08633EB;   // D5 k=5 f=2936.65Hz
        5'd5: pt_ph0_v24 = 32'hA9967A56;   // D5 k=6 f=3523.98Hz
        5'd6: pt_ph0_v24 = 32'hBD4DAE38;   // D5 k=7 f=4111.31Hz
        5'd7: pt_ph0_v24 = 32'h19DFE5DD;   // D5 k=8 f=4698.64Hz
        5'd8: pt_ph0_v24 = 32'h49E2DA8F;   // D5 k=9 f=5285.97Hz
        5'd9: pt_ph0_v24 = 32'h44D54916;   // D5 k=10 f=5873.30Hz
        5'd10: pt_ph0_v24 = 32'h5308024D;   // D5 k=11 f=6460.62Hz
        default: pt_ph0_v24 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v25 = 32'h03432CD6;   // D#5 k=1 f=622.25Hz
        5'd1: pt_inc_v25 = 32'h068659AC;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_inc_v25 = 32'h09C98681;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_inc_v25 = 32'h0D0CB357;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_inc_v25 = 32'h104FE02D;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_inc_v25 = 32'h13930D03;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_inc_v25 = 32'h16D639D9;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_inc_v25 = 32'h1A1966AF;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_inc_v25 = 32'h1D5C9384;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_inc_v25 = 32'h209FC05A;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_inc_v25 = 32'h23E2ED30;   // D#5 k=11 f=6844.79Hz
        default: pt_inc_v25 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v25 = 16'd6458;   // D#5 k=1 f=622.25Hz
        5'd1: pt_env0_v25 = 16'd1120;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_env0_v25 = 16'd340;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_env0_v25 = 16'd428;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_env0_v25 = 16'd152;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_env0_v25 = 16'd49;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_env0_v25 = 16'd44;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_env0_v25 = 16'd59;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_env0_v25 = 16'd37;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_env0_v25 = 16'd18;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_env0_v25 = 16'd20;   // D#5 k=11 f=6844.79Hz
        default: pt_env0_v25 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v25 = 12'd152;   // D#5 k=1 f=622.25Hz
        5'd1: pt_rate_v25 = 12'd223;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_rate_v25 = 12'd405;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_rate_v25 = 12'd336;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_rate_v25 = 12'd664;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_rate_v25 = 12'd495;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_rate_v25 = 12'd560;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_rate_v25 = 12'd708;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_rate_v25 = 12'd1392;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_rate_v25 = 12'd1668;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_rate_v25 = 12'd1627;   // D#5 k=11 f=6844.79Hz
        default: pt_rate_v25 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v25 = 16'd16180;   // D#5 k=1 f=622.25Hz
        5'd1: pt_aatk_v25 = 16'd19042;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_aatk_v25 = 16'd18716;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_aatk_v25 = 16'd22140;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_aatk_v25 = 16'd19475;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_aatk_v25 = 16'd23092;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_aatk_v25 = 16'd23602;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_aatk_v25 = 16'd23041;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_aatk_v25 = 16'd18617;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_aatk_v25 = 16'd17961;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_aatk_v25 = 16'd18945;   // D#5 k=11 f=6844.79Hz
        default: pt_aatk_v25 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v25 = 32'h3B70F64D;   // D#5 k=1 f=622.25Hz
        5'd1: pt_ph0_v25 = 32'h9291D6B1;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_ph0_v25 = 32'hA0B3F0D2;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_ph0_v25 = 32'h0B922F46;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_ph0_v25 = 32'h8123C71B;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_ph0_v25 = 32'hC1DBA49B;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_ph0_v25 = 32'h1C5B59DF;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_ph0_v25 = 32'h9B70CF8F;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_ph0_v25 = 32'hD2ACB72F;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_ph0_v25 = 32'hCD67D46B;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_ph0_v25 = 32'hCABF5C5E;   // D#5 k=11 f=6844.79Hz
        default: pt_ph0_v25 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v26 = 32'h0374D655;   // E5 k=1 f=659.26Hz
        5'd1: pt_inc_v26 = 32'h06E9ACAA;   // E5 k=2 f=1318.51Hz
        5'd2: pt_inc_v26 = 32'h0A5E82FF;   // E5 k=3 f=1977.77Hz
        5'd3: pt_inc_v26 = 32'h0DD35954;   // E5 k=4 f=2637.02Hz
        5'd4: pt_inc_v26 = 32'h11482FA9;   // E5 k=5 f=3296.28Hz
        5'd5: pt_inc_v26 = 32'h14BD05FE;   // E5 k=6 f=3955.53Hz
        5'd6: pt_inc_v26 = 32'h1831DC53;   // E5 k=7 f=4614.79Hz
        5'd7: pt_inc_v26 = 32'h1BA6B2A9;   // E5 k=8 f=5274.04Hz
        5'd8: pt_inc_v26 = 32'h1F1B88FE;   // E5 k=9 f=5933.30Hz
        5'd9: pt_inc_v26 = 32'h22905F53;   // E5 k=10 f=6592.55Hz
        5'd10: pt_inc_v26 = 32'h260535A8;   // E5 k=11 f=7251.81Hz
        5'd11: pt_inc_v26 = 32'h297A0BFD;   // E5 k=12 f=7911.06Hz
        5'd12: pt_inc_v26 = 32'h2CEEE252;   // E5 k=13 f=8570.32Hz
        5'd13: pt_inc_v26 = 32'h3063B8A7;   // E5 k=14 f=9229.57Hz
        5'd14: pt_inc_v26 = 32'h33D88EFC;   // E5 k=15 f=9888.83Hz
        default: pt_inc_v26 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v26 = 16'd5992;   // E5 k=1 f=659.26Hz
        5'd1: pt_env0_v26 = 16'd345;   // E5 k=2 f=1318.51Hz
        5'd2: pt_env0_v26 = 16'd1103;   // E5 k=3 f=1977.77Hz
        5'd3: pt_env0_v26 = 16'd224;   // E5 k=4 f=2637.02Hz
        5'd4: pt_env0_v26 = 16'd66;   // E5 k=5 f=3296.28Hz
        5'd5: pt_env0_v26 = 16'd250;   // E5 k=6 f=3955.53Hz
        5'd6: pt_env0_v26 = 16'd224;   // E5 k=7 f=4614.79Hz
        5'd7: pt_env0_v26 = 16'd212;   // E5 k=8 f=5274.04Hz
        5'd8: pt_env0_v26 = 16'd245;   // E5 k=9 f=5933.30Hz
        5'd9: pt_env0_v26 = 16'd38;   // E5 k=10 f=6592.55Hz
        5'd10: pt_env0_v26 = 16'd22;   // E5 k=11 f=7251.81Hz
        5'd11: pt_env0_v26 = 16'd69;   // E5 k=12 f=7911.06Hz
        5'd12: pt_env0_v26 = 16'd55;   // E5 k=13 f=8570.32Hz
        5'd13: pt_env0_v26 = 16'd22;   // E5 k=14 f=9229.57Hz
        5'd14: pt_env0_v26 = 16'd10;   // E5 k=15 f=9888.83Hz
        default: pt_env0_v26 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v26 = 12'd206;   // E5 k=1 f=659.26Hz
        5'd1: pt_rate_v26 = 12'd174;   // E5 k=2 f=1318.51Hz
        5'd2: pt_rate_v26 = 12'd289;   // E5 k=3 f=1977.77Hz
        5'd3: pt_rate_v26 = 12'd261;   // E5 k=4 f=2637.02Hz
        5'd4: pt_rate_v26 = 12'd273;   // E5 k=5 f=3296.28Hz
        5'd5: pt_rate_v26 = 12'd514;   // E5 k=6 f=3955.53Hz
        5'd6: pt_rate_v26 = 12'd555;   // E5 k=7 f=4614.79Hz
        5'd7: pt_rate_v26 = 12'd655;   // E5 k=8 f=5274.04Hz
        5'd8: pt_rate_v26 = 12'd693;   // E5 k=9 f=5933.30Hz
        5'd9: pt_rate_v26 = 12'd1091;   // E5 k=10 f=6592.55Hz
        5'd10: pt_rate_v26 = 12'd1124;   // E5 k=11 f=7251.81Hz
        5'd11: pt_rate_v26 = 12'd954;   // E5 k=12 f=7911.06Hz
        5'd12: pt_rate_v26 = 12'd975;   // E5 k=13 f=8570.32Hz
        5'd13: pt_rate_v26 = 12'd1905;   // E5 k=14 f=9229.57Hz
        5'd14: pt_rate_v26 = 12'd1385;   // E5 k=15 f=9888.83Hz
        default: pt_rate_v26 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v26 = 16'd14928;   // E5 k=1 f=659.26Hz
        5'd1: pt_aatk_v26 = 16'd20318;   // E5 k=2 f=1318.51Hz
        5'd2: pt_aatk_v26 = 16'd20685;   // E5 k=3 f=1977.77Hz
        5'd3: pt_aatk_v26 = 16'd23758;   // E5 k=4 f=2637.02Hz
        5'd4: pt_aatk_v26 = 16'd25542;   // E5 k=5 f=3296.28Hz
        5'd5: pt_aatk_v26 = 16'd22820;   // E5 k=6 f=3955.53Hz
        5'd6: pt_aatk_v26 = 16'd23676;   // E5 k=7 f=4614.79Hz
        5'd7: pt_aatk_v26 = 16'd23648;   // E5 k=8 f=5274.04Hz
        5'd8: pt_aatk_v26 = 16'd24332;   // E5 k=9 f=5933.30Hz
        5'd9: pt_aatk_v26 = 16'd21537;   // E5 k=10 f=6592.55Hz
        5'd10: pt_aatk_v26 = 16'd22152;   // E5 k=11 f=7251.81Hz
        5'd11: pt_aatk_v26 = 16'd24435;   // E5 k=12 f=7911.06Hz
        5'd12: pt_aatk_v26 = 16'd25053;   // E5 k=13 f=8570.32Hz
        5'd13: pt_aatk_v26 = 16'd19574;   // E5 k=14 f=9229.57Hz
        5'd14: pt_aatk_v26 = 16'd23207;   // E5 k=15 f=9888.83Hz
        default: pt_aatk_v26 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v26 = 32'h07A51444;   // E5 k=1 f=659.26Hz
        5'd1: pt_ph0_v26 = 32'h00951C92;   // E5 k=2 f=1318.51Hz
        5'd2: pt_ph0_v26 = 32'hC3E6AF31;   // E5 k=3 f=1977.77Hz
        5'd3: pt_ph0_v26 = 32'hD6100211;   // E5 k=4 f=2637.02Hz
        5'd4: pt_ph0_v26 = 32'h1C1B6E38;   // E5 k=5 f=3296.28Hz
        5'd5: pt_ph0_v26 = 32'h4A568C16;   // E5 k=6 f=3955.53Hz
        5'd6: pt_ph0_v26 = 32'hE1543E7C;   // E5 k=7 f=4614.79Hz
        5'd7: pt_ph0_v26 = 32'hB5EA2B11;   // E5 k=8 f=5274.04Hz
        5'd8: pt_ph0_v26 = 32'hE18619F5;   // E5 k=9 f=5933.30Hz
        5'd9: pt_ph0_v26 = 32'h75ADBD89;   // E5 k=10 f=6592.55Hz
        5'd10: pt_ph0_v26 = 32'h81836C67;   // E5 k=11 f=7251.81Hz
        5'd11: pt_ph0_v26 = 32'h6DF0B660;   // E5 k=12 f=7911.06Hz
        5'd12: pt_ph0_v26 = 32'h1CADF43E;   // E5 k=13 f=8570.32Hz
        5'd13: pt_ph0_v26 = 32'hE766C04B;   // E5 k=14 f=9229.57Hz
        5'd14: pt_ph0_v26 = 32'hD298407C;   // E5 k=15 f=9888.83Hz
        default: pt_ph0_v26 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v27 = 32'h03A973D0;   // F5 k=1 f=698.46Hz
        5'd1: pt_inc_v27 = 32'h0752E7A0;   // F5 k=2 f=1396.91Hz
        5'd2: pt_inc_v27 = 32'h0AFC5B71;   // F5 k=3 f=2095.37Hz
        5'd3: pt_inc_v27 = 32'h0EA5CF41;   // F5 k=4 f=2793.83Hz
        5'd4: pt_inc_v27 = 32'h124F4311;   // F5 k=5 f=3492.28Hz
        5'd5: pt_inc_v27 = 32'h15F8B6E1;   // F5 k=6 f=4190.74Hz
        5'd6: pt_inc_v27 = 32'h19A22AB1;   // F5 k=7 f=4889.20Hz
        5'd7: pt_inc_v27 = 32'h1D4B9E82;   // F5 k=8 f=5587.65Hz
        5'd8: pt_inc_v27 = 32'h20F51252;   // F5 k=9 f=6286.11Hz
        5'd9: pt_inc_v27 = 32'h249E8622;   // F5 k=10 f=6984.56Hz
        5'd10: pt_inc_v27 = 32'h2847F9F2;   // F5 k=11 f=7683.02Hz
        default: pt_inc_v27 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v27 = 16'd3766;   // F5 k=1 f=698.46Hz
        5'd1: pt_env0_v27 = 16'd585;   // F5 k=2 f=1396.91Hz
        5'd2: pt_env0_v27 = 16'd3300;   // F5 k=3 f=2095.37Hz
        5'd3: pt_env0_v27 = 16'd584;   // F5 k=4 f=2793.83Hz
        5'd4: pt_env0_v27 = 16'd62;   // F5 k=5 f=3492.28Hz
        5'd5: pt_env0_v27 = 16'd46;   // F5 k=6 f=4190.74Hz
        5'd6: pt_env0_v27 = 16'd125;   // F5 k=7 f=4889.20Hz
        5'd7: pt_env0_v27 = 16'd32;   // F5 k=8 f=5587.65Hz
        5'd8: pt_env0_v27 = 16'd87;   // F5 k=9 f=6286.11Hz
        5'd9: pt_env0_v27 = 16'd80;   // F5 k=10 f=6984.56Hz
        5'd10: pt_env0_v27 = 16'd19;   // F5 k=11 f=7683.02Hz
        default: pt_env0_v27 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v27 = 12'd115;   // F5 k=1 f=698.46Hz
        5'd1: pt_rate_v27 = 12'd265;   // F5 k=2 f=1396.91Hz
        5'd2: pt_rate_v27 = 12'd261;   // F5 k=3 f=2095.37Hz
        5'd3: pt_rate_v27 = 12'd271;   // F5 k=4 f=2793.83Hz
        5'd4: pt_rate_v27 = 12'd426;   // F5 k=5 f=3492.28Hz
        5'd5: pt_rate_v27 = 12'd548;   // F5 k=6 f=4190.74Hz
        5'd6: pt_rate_v27 = 12'd647;   // F5 k=7 f=4889.20Hz
        5'd7: pt_rate_v27 = 12'd792;   // F5 k=8 f=5587.65Hz
        5'd8: pt_rate_v27 = 12'd633;   // F5 k=9 f=6286.11Hz
        5'd9: pt_rate_v27 = 12'd657;   // F5 k=10 f=6984.56Hz
        5'd10: pt_rate_v27 = 12'd715;   // F5 k=11 f=7683.02Hz
        default: pt_rate_v27 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v27 = 16'd17358;   // F5 k=1 f=698.46Hz
        5'd1: pt_aatk_v27 = 16'd18142;   // F5 k=2 f=1396.91Hz
        5'd2: pt_aatk_v27 = 16'd21293;   // F5 k=3 f=2095.37Hz
        5'd3: pt_aatk_v27 = 16'd23524;   // F5 k=4 f=2793.83Hz
        5'd4: pt_aatk_v27 = 16'd22522;   // F5 k=5 f=3492.28Hz
        5'd5: pt_aatk_v27 = 16'd22364;   // F5 k=6 f=4190.74Hz
        5'd6: pt_aatk_v27 = 16'd22513;   // F5 k=7 f=4889.20Hz
        5'd7: pt_aatk_v27 = 16'd22145;   // F5 k=8 f=5587.65Hz
        5'd8: pt_aatk_v27 = 16'd25076;   // F5 k=9 f=6286.11Hz
        5'd9: pt_aatk_v27 = 16'd25831;   // F5 k=10 f=6984.56Hz
        5'd10: pt_aatk_v27 = 16'd26095;   // F5 k=11 f=7683.02Hz
        default: pt_aatk_v27 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v27 = 32'h88620BD7;   // F5 k=1 f=698.46Hz
        5'd1: pt_ph0_v27 = 32'hEA09643B;   // F5 k=2 f=1396.91Hz
        5'd2: pt_ph0_v27 = 32'h174288FC;   // F5 k=3 f=2095.37Hz
        5'd3: pt_ph0_v27 = 32'h8DF85192;   // F5 k=4 f=2793.83Hz
        5'd4: pt_ph0_v27 = 32'h7E801C97;   // F5 k=5 f=3492.28Hz
        5'd5: pt_ph0_v27 = 32'hDF4A81DE;   // F5 k=6 f=4190.74Hz
        5'd6: pt_ph0_v27 = 32'h6151CAA6;   // F5 k=7 f=4889.20Hz
        5'd7: pt_ph0_v27 = 32'h58A1F224;   // F5 k=8 f=5587.65Hz
        5'd8: pt_ph0_v27 = 32'hBA4D6B96;   // F5 k=9 f=6286.11Hz
        5'd9: pt_ph0_v27 = 32'hF0CBA129;   // F5 k=10 f=6984.56Hz
        5'd10: pt_ph0_v27 = 32'h1936A89E;   // F5 k=11 f=7683.02Hz
        default: pt_ph0_v27 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v28(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v28 = 32'h03E1323B;   // F#5 k=1 f=739.99Hz
        5'd1: pt_inc_v28 = 32'h07C26476;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_inc_v28 = 32'h0BA396B2;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_inc_v28 = 32'h0F84C8ED;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_inc_v28 = 32'h1365FB28;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_inc_v28 = 32'h17472D63;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_inc_v28 = 32'h1B285F9E;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_inc_v28 = 32'h1F0991DA;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_inc_v28 = 32'h22EAC415;   // F#5 k=9 f=6659.90Hz
        5'd9: pt_inc_v28 = 32'h26CBF650;   // F#5 k=10 f=7399.89Hz
        5'd10: pt_inc_v28 = 32'h2AAD288B;   // F#5 k=11 f=8139.88Hz
        5'd11: pt_inc_v28 = 32'h2E8E5AC6;   // F#5 k=12 f=8879.87Hz
        5'd12: pt_inc_v28 = 32'h326F8D02;   // F#5 k=13 f=9619.85Hz
        default: pt_inc_v28 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v28(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v28 = 16'd4644;   // F#5 k=1 f=739.99Hz
        5'd1: pt_env0_v28 = 16'd358;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_env0_v28 = 16'd2709;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_env0_v28 = 16'd304;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_env0_v28 = 16'd118;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_env0_v28 = 16'd213;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_env0_v28 = 16'd89;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_env0_v28 = 16'd221;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_env0_v28 = 16'd50;   // F#5 k=9 f=6659.90Hz
        5'd9: pt_env0_v28 = 16'd40;   // F#5 k=10 f=7399.89Hz
        5'd10: pt_env0_v28 = 16'd49;   // F#5 k=11 f=8139.88Hz
        5'd11: pt_env0_v28 = 16'd12;   // F#5 k=12 f=8879.87Hz
        5'd12: pt_env0_v28 = 16'd16;   // F#5 k=13 f=9619.85Hz
        default: pt_env0_v28 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v28(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v28 = 12'd92;   // F#5 k=1 f=739.99Hz
        5'd1: pt_rate_v28 = 12'd281;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_rate_v28 = 12'd452;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_rate_v28 = 12'd311;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_rate_v28 = 12'd700;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_rate_v28 = 12'd770;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_rate_v28 = 12'd671;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_rate_v28 = 12'd937;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_rate_v28 = 12'd1164;   // F#5 k=9 f=6659.90Hz
        5'd9: pt_rate_v28 = 12'd1528;   // F#5 k=10 f=7399.89Hz
        5'd10: pt_rate_v28 = 12'd277;   // F#5 k=11 f=8139.88Hz
        5'd11: pt_rate_v28 = 12'd1240;   // F#5 k=12 f=8879.87Hz
        5'd12: pt_rate_v28 = 12'd1415;   // F#5 k=13 f=9619.85Hz
        default: pt_rate_v28 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v28(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v28 = 16'd18271;   // F#5 k=1 f=739.99Hz
        5'd1: pt_aatk_v28 = 16'd17837;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_aatk_v28 = 16'd18069;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_aatk_v28 = 16'd22646;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_aatk_v28 = 16'd19116;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_aatk_v28 = 16'd19899;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_aatk_v28 = 16'd22240;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_aatk_v28 = 16'd20822;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_aatk_v28 = 16'd20081;   // F#5 k=9 f=6659.90Hz
        5'd9: pt_aatk_v28 = 16'd18695;   // F#5 k=10 f=7399.89Hz
        5'd10: pt_aatk_v28 = 16'd34253;   // F#5 k=11 f=8139.88Hz
        5'd11: pt_aatk_v28 = 16'd22100;   // F#5 k=12 f=8879.87Hz
        5'd12: pt_aatk_v28 = 16'd21646;   // F#5 k=13 f=9619.85Hz
        default: pt_aatk_v28 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v28(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v28 = 32'h4884DF0C;   // F#5 k=1 f=739.99Hz
        5'd1: pt_ph0_v28 = 32'hBFC3E17E;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_ph0_v28 = 32'hBE645188;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_ph0_v28 = 32'hBEFC3FE2;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_ph0_v28 = 32'h190C0F22;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_ph0_v28 = 32'h85E86996;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_ph0_v28 = 32'h9387529B;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_ph0_v28 = 32'h4355C051;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_ph0_v28 = 32'h90AC223E;   // F#5 k=9 f=6659.90Hz
        5'd9: pt_ph0_v28 = 32'h8996E12B;   // F#5 k=10 f=7399.89Hz
        5'd10: pt_ph0_v28 = 32'hE3C6D5B2;   // F#5 k=11 f=8139.88Hz
        5'd11: pt_ph0_v28 = 32'h1761A84A;   // F#5 k=12 f=8879.87Hz
        5'd12: pt_ph0_v28 = 32'h6F77D6BE;   // F#5 k=13 f=9619.85Hz
        default: pt_ph0_v28 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v29(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v29 = 32'h041C4136;   // G5 k=1 f=783.99Hz
        5'd1: pt_inc_v29 = 32'h0838826D;   // G5 k=2 f=1567.98Hz
        5'd2: pt_inc_v29 = 32'h0C54C3A3;   // G5 k=3 f=2351.97Hz
        5'd3: pt_inc_v29 = 32'h107104DA;   // G5 k=4 f=3135.96Hz
        5'd4: pt_inc_v29 = 32'h148D4610;   // G5 k=5 f=3919.95Hz
        5'd5: pt_inc_v29 = 32'h18A98746;   // G5 k=6 f=4703.95Hz
        5'd6: pt_inc_v29 = 32'h1CC5C87D;   // G5 k=7 f=5487.94Hz
        5'd7: pt_inc_v29 = 32'h20E209B3;   // G5 k=8 f=6271.93Hz
        5'd8: pt_inc_v29 = 32'h24FE4AE9;   // G5 k=9 f=7055.92Hz
        5'd9: pt_inc_v29 = 32'h291A8C20;   // G5 k=10 f=7839.91Hz
        default: pt_inc_v29 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v29(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v29 = 16'd5529;   // G5 k=1 f=783.99Hz
        5'd1: pt_env0_v29 = 16'd609;   // G5 k=2 f=1567.98Hz
        5'd2: pt_env0_v29 = 16'd1443;   // G5 k=3 f=2351.97Hz
        5'd3: pt_env0_v29 = 16'd180;   // G5 k=4 f=3135.96Hz
        5'd4: pt_env0_v29 = 16'd184;   // G5 k=5 f=3919.95Hz
        5'd5: pt_env0_v29 = 16'd12;   // G5 k=6 f=4703.95Hz
        5'd6: pt_env0_v29 = 16'd310;   // G5 k=7 f=5487.94Hz
        5'd7: pt_env0_v29 = 16'd80;   // G5 k=8 f=6271.93Hz
        5'd8: pt_env0_v29 = 16'd309;   // G5 k=9 f=7055.92Hz
        5'd9: pt_env0_v29 = 16'd20;   // G5 k=10 f=7839.91Hz
        default: pt_env0_v29 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v29(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v29 = 12'd120;   // G5 k=1 f=783.99Hz
        5'd1: pt_rate_v29 = 12'd185;   // G5 k=2 f=1567.98Hz
        5'd2: pt_rate_v29 = 12'd294;   // G5 k=3 f=2351.97Hz
        5'd3: pt_rate_v29 = 12'd387;   // G5 k=4 f=3135.96Hz
        5'd4: pt_rate_v29 = 12'd213;   // G5 k=5 f=3919.95Hz
        5'd5: pt_rate_v29 = 12'd320;   // G5 k=6 f=4703.95Hz
        5'd6: pt_rate_v29 = 12'd551;   // G5 k=7 f=5487.94Hz
        5'd7: pt_rate_v29 = 12'd828;   // G5 k=8 f=6271.93Hz
        5'd8: pt_rate_v29 = 12'd878;   // G5 k=9 f=7055.92Hz
        5'd9: pt_rate_v29 = 12'd648;   // G5 k=10 f=7839.91Hz
        default: pt_rate_v29 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v29(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v29 = 16'd17177;   // G5 k=1 f=783.99Hz
        5'd1: pt_aatk_v29 = 16'd19987;   // G5 k=2 f=1567.98Hz
        5'd2: pt_aatk_v29 = 16'd20592;   // G5 k=3 f=2351.97Hz
        5'd3: pt_aatk_v29 = 16'd21252;   // G5 k=4 f=3135.96Hz
        5'd4: pt_aatk_v29 = 16'd27232;   // G5 k=5 f=3919.95Hz
        5'd5: pt_aatk_v29 = 16'd26243;   // G5 k=6 f=4703.95Hz
        5'd6: pt_aatk_v29 = 16'd23729;   // G5 k=7 f=5487.94Hz
        5'd7: pt_aatk_v29 = 16'd21802;   // G5 k=8 f=6271.93Hz
        5'd8: pt_aatk_v29 = 16'd22388;   // G5 k=9 f=7055.92Hz
        5'd9: pt_aatk_v29 = 16'd25947;   // G5 k=10 f=7839.91Hz
        default: pt_aatk_v29 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v29(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v29 = 32'h21ABD1AE;   // G5 k=1 f=783.99Hz
        5'd1: pt_ph0_v29 = 32'h2859B041;   // G5 k=2 f=1567.98Hz
        5'd2: pt_ph0_v29 = 32'hD4627249;   // G5 k=3 f=2351.97Hz
        5'd3: pt_ph0_v29 = 32'hDF48F4DC;   // G5 k=4 f=3135.96Hz
        5'd4: pt_ph0_v29 = 32'hFBB19295;   // G5 k=5 f=3919.95Hz
        5'd5: pt_ph0_v29 = 32'hF5899AE4;   // G5 k=6 f=4703.95Hz
        5'd6: pt_ph0_v29 = 32'h1C7A484E;   // G5 k=7 f=5487.94Hz
        5'd7: pt_ph0_v29 = 32'hFB07FC17;   // G5 k=8 f=6271.93Hz
        5'd8: pt_ph0_v29 = 32'h9426D4F5;   // G5 k=9 f=7055.92Hz
        5'd9: pt_ph0_v29 = 32'h99B22689;   // G5 k=10 f=7839.91Hz
        default: pt_ph0_v29 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v30(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v30 = 32'h045AD337;   // G#5 k=1 f=830.61Hz
        5'd1: pt_inc_v30 = 32'h08B5A66E;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_inc_v30 = 32'h0D1079A5;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_inc_v30 = 32'h116B4CDC;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_inc_v30 = 32'h15C62013;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_inc_v30 = 32'h1A20F34A;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_inc_v30 = 32'h1E7BC681;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_inc_v30 = 32'h22D699B8;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_inc_v30 = 32'h27316CEF;   // G#5 k=9 f=7475.48Hz
        5'd9: pt_inc_v30 = 32'h2B8C4027;   // G#5 k=10 f=8306.09Hz
        default: pt_inc_v30 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v30(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v30 = 16'd5604;   // G#5 k=1 f=830.61Hz
        5'd1: pt_env0_v30 = 16'd552;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_env0_v30 = 16'd2074;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_env0_v30 = 16'd57;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_env0_v30 = 16'd62;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_env0_v30 = 16'd90;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_env0_v30 = 16'd141;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_env0_v30 = 16'd14;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_env0_v30 = 16'd61;   // G#5 k=9 f=7475.48Hz
        5'd9: pt_env0_v30 = 16'd10;   // G#5 k=10 f=8306.09Hz
        default: pt_env0_v30 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v30(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v30 = 12'd98;   // G#5 k=1 f=830.61Hz
        5'd1: pt_rate_v30 = 12'd351;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_rate_v30 = 12'd358;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_rate_v30 = 12'd400;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_rate_v30 = 12'd462;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_rate_v30 = 12'd555;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_rate_v30 = 12'd564;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_rate_v30 = 12'd685;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_rate_v30 = 12'd747;   // G#5 k=9 f=7475.48Hz
        5'd9: pt_rate_v30 = 12'd1120;   // G#5 k=10 f=8306.09Hz
        default: pt_rate_v30 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v30(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v30 = 16'd18010;   // G#5 k=1 f=830.61Hz
        5'd1: pt_aatk_v30 = 16'd16679;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_aatk_v30 = 16'd19441;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_aatk_v30 = 16'd21035;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_aatk_v30 = 16'd21965;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_aatk_v30 = 16'd22267;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_aatk_v30 = 16'd23548;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_aatk_v30 = 16'd23297;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_aatk_v30 = 16'd23715;   // G#5 k=9 f=7475.48Hz
        5'd9: pt_aatk_v30 = 16'd21319;   // G#5 k=10 f=8306.09Hz
        default: pt_aatk_v30 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v30(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v30 = 32'h710E6F38;   // G#5 k=1 f=830.61Hz
        5'd1: pt_ph0_v30 = 32'h21CF18CC;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_ph0_v30 = 32'h9809CD47;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_ph0_v30 = 32'h958ECF77;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_ph0_v30 = 32'hC06121A7;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_ph0_v30 = 32'h6EE3C22D;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_ph0_v30 = 32'hC26B46BE;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_ph0_v30 = 32'hFD26AFE0;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_ph0_v30 = 32'h338DCA2D;   // G#5 k=9 f=7475.48Hz
        5'd9: pt_ph0_v30 = 32'hD3CDC669;   // G#5 k=10 f=8306.09Hz
        default: pt_ph0_v30 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v31(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v31 = 32'h049D1DB3;   // A5 k=1 f=880.00Hz
        5'd1: pt_inc_v31 = 32'h093A3B65;   // A5 k=2 f=1760.00Hz
        5'd2: pt_inc_v31 = 32'h0DD75918;   // A5 k=3 f=2640.00Hz
        5'd3: pt_inc_v31 = 32'h127476CA;   // A5 k=4 f=3520.00Hz
        5'd4: pt_inc_v31 = 32'h1711947D;   // A5 k=5 f=4400.00Hz
        5'd5: pt_inc_v31 = 32'h1BAEB230;   // A5 k=6 f=5280.00Hz
        5'd6: pt_inc_v31 = 32'h204BCFE2;   // A5 k=7 f=6160.00Hz
        5'd7: pt_inc_v31 = 32'h24E8ED95;   // A5 k=8 f=7040.00Hz
        5'd8: pt_inc_v31 = 32'h29860B47;   // A5 k=9 f=7920.00Hz
        5'd9: pt_inc_v31 = 32'h2E2328FA;   // A5 k=10 f=8800.00Hz
        5'd10: pt_inc_v31 = 32'h32C046AD;   // A5 k=11 f=9680.00Hz
        default: pt_inc_v31 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v31(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v31 = 16'd7795;   // A5 k=1 f=880.00Hz
        5'd1: pt_env0_v31 = 16'd432;   // A5 k=2 f=1760.00Hz
        5'd2: pt_env0_v31 = 16'd130;   // A5 k=3 f=2640.00Hz
        5'd3: pt_env0_v31 = 16'd178;   // A5 k=4 f=3520.00Hz
        5'd4: pt_env0_v31 = 16'd98;   // A5 k=5 f=4400.00Hz
        5'd5: pt_env0_v31 = 16'd364;   // A5 k=6 f=5280.00Hz
        5'd6: pt_env0_v31 = 16'd56;   // A5 k=7 f=6160.00Hz
        5'd7: pt_env0_v31 = 16'd136;   // A5 k=8 f=7040.00Hz
        5'd8: pt_env0_v31 = 16'd33;   // A5 k=9 f=7920.00Hz
        5'd9: pt_env0_v31 = 16'd15;   // A5 k=10 f=8800.00Hz
        5'd10: pt_env0_v31 = 16'd22;   // A5 k=11 f=9680.00Hz
        default: pt_env0_v31 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v31(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v31 = 12'd329;   // A5 k=1 f=880.00Hz
        5'd1: pt_rate_v31 = 12'd465;   // A5 k=2 f=1760.00Hz
        5'd2: pt_rate_v31 = 12'd414;   // A5 k=3 f=2640.00Hz
        5'd3: pt_rate_v31 = 12'd583;   // A5 k=4 f=3520.00Hz
        5'd4: pt_rate_v31 = 12'd623;   // A5 k=5 f=4400.00Hz
        5'd5: pt_rate_v31 = 12'd604;   // A5 k=6 f=5280.00Hz
        5'd6: pt_rate_v31 = 12'd615;   // A5 k=7 f=6160.00Hz
        5'd7: pt_rate_v31 = 12'd274;   // A5 k=8 f=7040.00Hz
        5'd8: pt_rate_v31 = 12'd656;   // A5 k=9 f=7920.00Hz
        5'd9: pt_rate_v31 = 12'd890;   // A5 k=10 f=8800.00Hz
        5'd10: pt_rate_v31 = 12'd1385;   // A5 k=11 f=9680.00Hz
        default: pt_rate_v31 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v31(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v31 = 16'd12943;   // A5 k=1 f=880.00Hz
        5'd1: pt_aatk_v31 = 16'd15218;   // A5 k=2 f=1760.00Hz
        5'd2: pt_aatk_v31 = 16'd18595;   // A5 k=3 f=2640.00Hz
        5'd3: pt_aatk_v31 = 16'd18622;   // A5 k=4 f=3520.00Hz
        5'd4: pt_aatk_v31 = 16'd19913;   // A5 k=5 f=4400.00Hz
        5'd5: pt_aatk_v31 = 16'd21662;   // A5 k=6 f=5280.00Hz
        5'd6: pt_aatk_v31 = 16'd22898;   // A5 k=7 f=6160.00Hz
        5'd7: pt_aatk_v31 = 16'd30478;   // A5 k=8 f=7040.00Hz
        5'd8: pt_aatk_v31 = 16'd24775;   // A5 k=9 f=7920.00Hz
        5'd9: pt_aatk_v31 = 16'd23261;   // A5 k=10 f=8800.00Hz
        5'd10: pt_aatk_v31 = 16'd20337;   // A5 k=11 f=9680.00Hz
        default: pt_aatk_v31 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v31(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v31 = 32'h0B1AAEC9;   // A5 k=1 f=880.00Hz
        5'd1: pt_ph0_v31 = 32'h86B794A8;   // A5 k=2 f=1760.00Hz
        5'd2: pt_ph0_v31 = 32'hF0777999;   // A5 k=3 f=2640.00Hz
        5'd3: pt_ph0_v31 = 32'hF7CE915B;   // A5 k=4 f=3520.00Hz
        5'd4: pt_ph0_v31 = 32'h99599689;   // A5 k=5 f=4400.00Hz
        5'd5: pt_ph0_v31 = 32'hDA948AED;   // A5 k=6 f=5280.00Hz
        5'd6: pt_ph0_v31 = 32'h32E8EE29;   // A5 k=7 f=6160.00Hz
        5'd7: pt_ph0_v31 = 32'hCA96EE5F;   // A5 k=8 f=7040.00Hz
        5'd8: pt_ph0_v31 = 32'hFCFDBBD1;   // A5 k=9 f=7920.00Hz
        5'd9: pt_ph0_v31 = 32'h3B707C52;   // A5 k=10 f=8800.00Hz
        5'd10: pt_ph0_v31 = 32'hABBEE3CF;   // A5 k=11 f=9680.00Hz
        default: pt_ph0_v31 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v32(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v32 = 32'h04E3594C;   // A#5 k=1 f=932.33Hz
        5'd1: pt_inc_v32 = 32'h09C6B298;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_inc_v32 = 32'h0EAA0BE5;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_inc_v32 = 32'h138D6531;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_inc_v32 = 32'h1870BE7D;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_inc_v32 = 32'h1D5417C9;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_inc_v32 = 32'h22377115;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_inc_v32 = 32'h271ACA62;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_inc_v32 = 32'h2BFE23AE;   // A#5 k=9 f=8390.95Hz
        default: pt_inc_v32 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v32(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v32 = 16'd3108;   // A#5 k=1 f=932.33Hz
        5'd1: pt_env0_v32 = 16'd207;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_env0_v32 = 16'd4615;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_env0_v32 = 16'd25;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_env0_v32 = 16'd438;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_env0_v32 = 16'd168;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_env0_v32 = 16'd310;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_env0_v32 = 16'd104;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_env0_v32 = 16'd29;   // A#5 k=9 f=8390.95Hz
        default: pt_env0_v32 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v32(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v32 = 12'd198;   // A#5 k=1 f=932.33Hz
        5'd1: pt_rate_v32 = 12'd39;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_rate_v32 = 12'd361;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_rate_v32 = 12'd72;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_rate_v32 = 12'd672;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_rate_v32 = 12'd1225;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_rate_v32 = 12'd841;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_rate_v32 = 12'd1166;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_rate_v32 = 12'd907;   // A#5 k=9 f=8390.95Hz
        default: pt_rate_v32 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v32(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v32 = 16'd15083;   // A#5 k=1 f=932.33Hz
        5'd1: pt_aatk_v32 = 16'd27845;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_aatk_v32 = 16'd19397;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_aatk_v32 = 16'd31828;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_aatk_v32 = 16'd19395;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_aatk_v32 = 16'd16528;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_aatk_v32 = 16'd20516;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_aatk_v32 = 16'd19094;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_aatk_v32 = 16'd22124;   // A#5 k=9 f=8390.95Hz
        default: pt_aatk_v32 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v32(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v32 = 32'h8F203DAC;   // A#5 k=1 f=932.33Hz
        5'd1: pt_ph0_v32 = 32'h9A2AC526;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_ph0_v32 = 32'h9342E841;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_ph0_v32 = 32'h071F43E4;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_ph0_v32 = 32'h03153EF5;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_ph0_v32 = 32'h3DB25E90;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_ph0_v32 = 32'hBA6CD1A0;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_ph0_v32 = 32'h2B40F00C;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_ph0_v32 = 32'h9D6C3F2C;   // A#5 k=9 f=8390.95Hz
        default: pt_ph0_v32 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc(input [5:0] vv, input [4:0] pp);
    begin
        case (vv)
            6'd0: pt_inc = pt_inc_v0(pp);   // D3
            6'd1: pt_inc = pt_inc_v1(pp);   // D#3
            6'd2: pt_inc = pt_inc_v2(pp);   // E3
            6'd3: pt_inc = pt_inc_v3(pp);   // F3
            6'd4: pt_inc = pt_inc_v4(pp);   // F#3
            6'd5: pt_inc = pt_inc_v5(pp);   // G3
            6'd6: pt_inc = pt_inc_v6(pp);   // G#3
            6'd7: pt_inc = pt_inc_v7(pp);   // A3
            6'd8: pt_inc = pt_inc_v8(pp);   // A#3
            6'd9: pt_inc = pt_inc_v9(pp);   // B3
            6'd10: pt_inc = pt_inc_v10(pp);   // C4
            6'd11: pt_inc = pt_inc_v11(pp);   // C#4
            6'd12: pt_inc = pt_inc_v12(pp);   // D4
            6'd13: pt_inc = pt_inc_v13(pp);   // D#4
            6'd14: pt_inc = pt_inc_v14(pp);   // E4
            6'd15: pt_inc = pt_inc_v15(pp);   // F4
            6'd16: pt_inc = pt_inc_v16(pp);   // F#4
            6'd17: pt_inc = pt_inc_v17(pp);   // G4
            6'd18: pt_inc = pt_inc_v18(pp);   // G#4
            6'd19: pt_inc = pt_inc_v19(pp);   // A4
            6'd20: pt_inc = pt_inc_v20(pp);   // A#4
            6'd21: pt_inc = pt_inc_v21(pp);   // B4
            6'd22: pt_inc = pt_inc_v22(pp);   // C5
            6'd23: pt_inc = pt_inc_v23(pp);   // C#5
            6'd24: pt_inc = pt_inc_v24(pp);   // D5
            6'd25: pt_inc = pt_inc_v25(pp);   // D#5
            6'd26: pt_inc = pt_inc_v26(pp);   // E5
            6'd27: pt_inc = pt_inc_v27(pp);   // F5
            6'd28: pt_inc = pt_inc_v28(pp);   // F#5
            6'd29: pt_inc = pt_inc_v29(pp);   // G5
            6'd30: pt_inc = pt_inc_v30(pp);   // G#5
            6'd31: pt_inc = pt_inc_v31(pp);   // A5
            6'd32: pt_inc = pt_inc_v32(pp);   // A#5
            default: pt_inc = 32'd0;
        endcase
    end
endfunction

function automatic [15:0] pt_env0(input [5:0] vv, input [4:0] pp);
    begin
        case (vv)
            6'd0: pt_env0 = pt_env0_v0(pp);   // D3
            6'd1: pt_env0 = pt_env0_v1(pp);   // D#3
            6'd2: pt_env0 = pt_env0_v2(pp);   // E3
            6'd3: pt_env0 = pt_env0_v3(pp);   // F3
            6'd4: pt_env0 = pt_env0_v4(pp);   // F#3
            6'd5: pt_env0 = pt_env0_v5(pp);   // G3
            6'd6: pt_env0 = pt_env0_v6(pp);   // G#3
            6'd7: pt_env0 = pt_env0_v7(pp);   // A3
            6'd8: pt_env0 = pt_env0_v8(pp);   // A#3
            6'd9: pt_env0 = pt_env0_v9(pp);   // B3
            6'd10: pt_env0 = pt_env0_v10(pp);   // C4
            6'd11: pt_env0 = pt_env0_v11(pp);   // C#4
            6'd12: pt_env0 = pt_env0_v12(pp);   // D4
            6'd13: pt_env0 = pt_env0_v13(pp);   // D#4
            6'd14: pt_env0 = pt_env0_v14(pp);   // E4
            6'd15: pt_env0 = pt_env0_v15(pp);   // F4
            6'd16: pt_env0 = pt_env0_v16(pp);   // F#4
            6'd17: pt_env0 = pt_env0_v17(pp);   // G4
            6'd18: pt_env0 = pt_env0_v18(pp);   // G#4
            6'd19: pt_env0 = pt_env0_v19(pp);   // A4
            6'd20: pt_env0 = pt_env0_v20(pp);   // A#4
            6'd21: pt_env0 = pt_env0_v21(pp);   // B4
            6'd22: pt_env0 = pt_env0_v22(pp);   // C5
            6'd23: pt_env0 = pt_env0_v23(pp);   // C#5
            6'd24: pt_env0 = pt_env0_v24(pp);   // D5
            6'd25: pt_env0 = pt_env0_v25(pp);   // D#5
            6'd26: pt_env0 = pt_env0_v26(pp);   // E5
            6'd27: pt_env0 = pt_env0_v27(pp);   // F5
            6'd28: pt_env0 = pt_env0_v28(pp);   // F#5
            6'd29: pt_env0 = pt_env0_v29(pp);   // G5
            6'd30: pt_env0 = pt_env0_v30(pp);   // G#5
            6'd31: pt_env0 = pt_env0_v31(pp);   // A5
            6'd32: pt_env0 = pt_env0_v32(pp);   // A#5
            default: pt_env0 = 16'd0;
        endcase
    end
endfunction

function automatic [11:0] pt_rate(input [5:0] vv, input [4:0] pp);
    begin
        case (vv)
            6'd0: pt_rate = pt_rate_v0(pp);   // D3
            6'd1: pt_rate = pt_rate_v1(pp);   // D#3
            6'd2: pt_rate = pt_rate_v2(pp);   // E3
            6'd3: pt_rate = pt_rate_v3(pp);   // F3
            6'd4: pt_rate = pt_rate_v4(pp);   // F#3
            6'd5: pt_rate = pt_rate_v5(pp);   // G3
            6'd6: pt_rate = pt_rate_v6(pp);   // G#3
            6'd7: pt_rate = pt_rate_v7(pp);   // A3
            6'd8: pt_rate = pt_rate_v8(pp);   // A#3
            6'd9: pt_rate = pt_rate_v9(pp);   // B3
            6'd10: pt_rate = pt_rate_v10(pp);   // C4
            6'd11: pt_rate = pt_rate_v11(pp);   // C#4
            6'd12: pt_rate = pt_rate_v12(pp);   // D4
            6'd13: pt_rate = pt_rate_v13(pp);   // D#4
            6'd14: pt_rate = pt_rate_v14(pp);   // E4
            6'd15: pt_rate = pt_rate_v15(pp);   // F4
            6'd16: pt_rate = pt_rate_v16(pp);   // F#4
            6'd17: pt_rate = pt_rate_v17(pp);   // G4
            6'd18: pt_rate = pt_rate_v18(pp);   // G#4
            6'd19: pt_rate = pt_rate_v19(pp);   // A4
            6'd20: pt_rate = pt_rate_v20(pp);   // A#4
            6'd21: pt_rate = pt_rate_v21(pp);   // B4
            6'd22: pt_rate = pt_rate_v22(pp);   // C5
            6'd23: pt_rate = pt_rate_v23(pp);   // C#5
            6'd24: pt_rate = pt_rate_v24(pp);   // D5
            6'd25: pt_rate = pt_rate_v25(pp);   // D#5
            6'd26: pt_rate = pt_rate_v26(pp);   // E5
            6'd27: pt_rate = pt_rate_v27(pp);   // F5
            6'd28: pt_rate = pt_rate_v28(pp);   // F#5
            6'd29: pt_rate = pt_rate_v29(pp);   // G5
            6'd30: pt_rate = pt_rate_v30(pp);   // G#5
            6'd31: pt_rate = pt_rate_v31(pp);   // A5
            6'd32: pt_rate = pt_rate_v32(pp);   // A#5
            default: pt_rate = 12'd0;
        endcase
    end
endfunction

function automatic [15:0] pt_aatk(input [5:0] vv, input [4:0] pp);
    begin
        case (vv)
            6'd0: pt_aatk = pt_aatk_v0(pp);   // D3
            6'd1: pt_aatk = pt_aatk_v1(pp);   // D#3
            6'd2: pt_aatk = pt_aatk_v2(pp);   // E3
            6'd3: pt_aatk = pt_aatk_v3(pp);   // F3
            6'd4: pt_aatk = pt_aatk_v4(pp);   // F#3
            6'd5: pt_aatk = pt_aatk_v5(pp);   // G3
            6'd6: pt_aatk = pt_aatk_v6(pp);   // G#3
            6'd7: pt_aatk = pt_aatk_v7(pp);   // A3
            6'd8: pt_aatk = pt_aatk_v8(pp);   // A#3
            6'd9: pt_aatk = pt_aatk_v9(pp);   // B3
            6'd10: pt_aatk = pt_aatk_v10(pp);   // C4
            6'd11: pt_aatk = pt_aatk_v11(pp);   // C#4
            6'd12: pt_aatk = pt_aatk_v12(pp);   // D4
            6'd13: pt_aatk = pt_aatk_v13(pp);   // D#4
            6'd14: pt_aatk = pt_aatk_v14(pp);   // E4
            6'd15: pt_aatk = pt_aatk_v15(pp);   // F4
            6'd16: pt_aatk = pt_aatk_v16(pp);   // F#4
            6'd17: pt_aatk = pt_aatk_v17(pp);   // G4
            6'd18: pt_aatk = pt_aatk_v18(pp);   // G#4
            6'd19: pt_aatk = pt_aatk_v19(pp);   // A4
            6'd20: pt_aatk = pt_aatk_v20(pp);   // A#4
            6'd21: pt_aatk = pt_aatk_v21(pp);   // B4
            6'd22: pt_aatk = pt_aatk_v22(pp);   // C5
            6'd23: pt_aatk = pt_aatk_v23(pp);   // C#5
            6'd24: pt_aatk = pt_aatk_v24(pp);   // D5
            6'd25: pt_aatk = pt_aatk_v25(pp);   // D#5
            6'd26: pt_aatk = pt_aatk_v26(pp);   // E5
            6'd27: pt_aatk = pt_aatk_v27(pp);   // F5
            6'd28: pt_aatk = pt_aatk_v28(pp);   // F#5
            6'd29: pt_aatk = pt_aatk_v29(pp);   // G5
            6'd30: pt_aatk = pt_aatk_v30(pp);   // G#5
            6'd31: pt_aatk = pt_aatk_v31(pp);   // A5
            6'd32: pt_aatk = pt_aatk_v32(pp);   // A#5
            default: pt_aatk = 16'd0;
        endcase
    end
endfunction

function automatic [31:0] pt_ph0(input [5:0] vv, input [4:0] pp);
    begin
        case (vv)
            6'd0: pt_ph0 = pt_ph0_v0(pp);   // D3
            6'd1: pt_ph0 = pt_ph0_v1(pp);   // D#3
            6'd2: pt_ph0 = pt_ph0_v2(pp);   // E3
            6'd3: pt_ph0 = pt_ph0_v3(pp);   // F3
            6'd4: pt_ph0 = pt_ph0_v4(pp);   // F#3
            6'd5: pt_ph0 = pt_ph0_v5(pp);   // G3
            6'd6: pt_ph0 = pt_ph0_v6(pp);   // G#3
            6'd7: pt_ph0 = pt_ph0_v7(pp);   // A3
            6'd8: pt_ph0 = pt_ph0_v8(pp);   // A#3
            6'd9: pt_ph0 = pt_ph0_v9(pp);   // B3
            6'd10: pt_ph0 = pt_ph0_v10(pp);   // C4
            6'd11: pt_ph0 = pt_ph0_v11(pp);   // C#4
            6'd12: pt_ph0 = pt_ph0_v12(pp);   // D4
            6'd13: pt_ph0 = pt_ph0_v13(pp);   // D#4
            6'd14: pt_ph0 = pt_ph0_v14(pp);   // E4
            6'd15: pt_ph0 = pt_ph0_v15(pp);   // F4
            6'd16: pt_ph0 = pt_ph0_v16(pp);   // F#4
            6'd17: pt_ph0 = pt_ph0_v17(pp);   // G4
            6'd18: pt_ph0 = pt_ph0_v18(pp);   // G#4
            6'd19: pt_ph0 = pt_ph0_v19(pp);   // A4
            6'd20: pt_ph0 = pt_ph0_v20(pp);   // A#4
            6'd21: pt_ph0 = pt_ph0_v21(pp);   // B4
            6'd22: pt_ph0 = pt_ph0_v22(pp);   // C5
            6'd23: pt_ph0 = pt_ph0_v23(pp);   // C#5
            6'd24: pt_ph0 = pt_ph0_v24(pp);   // D5
            6'd25: pt_ph0 = pt_ph0_v25(pp);   // D#5
            6'd26: pt_ph0 = pt_ph0_v26(pp);   // E5
            6'd27: pt_ph0 = pt_ph0_v27(pp);   // F5
            6'd28: pt_ph0 = pt_ph0_v28(pp);   // F#5
            6'd29: pt_ph0 = pt_ph0_v29(pp);   // G5
            6'd30: pt_ph0 = pt_ph0_v30(pp);   // G#5
            6'd31: pt_ph0 = pt_ph0_v31(pp);   // A5
            6'd32: pt_ph0 = pt_ph0_v32(pp);   // A#5
            default: pt_ph0 = 32'd0;
        endcase
    end
endfunction

`endif
