// ============================================================
// partial_bank.vh —— 自动生成，勿手改！
// 由 src/tools/gen_partial_bank.py（v12 pluck）生成（record28录音(28音 C4..D6, 衰减修复版)）
// 音库：28 个白键音 C4..C6（按 MIDI 排序），每音最多 18 个泛音槽
// 槽包络 env(t)=env0*(e^{-t/tau_d} - e^{-t/tau_a})：
//   pt_rate = 衰减支率（tau_d 实测，12bit）
//   pt_aatk = 起振支率（16bit，每泛音不同：Tpk=max(8,25*k^-0.30)ms 反解
//             -> 高频先起振（doc/起振不同.md、木质 §3.2））
//   pt_ph0  = 每槽伪随机相位初值（32bit，重触发起步相位去同相堆积）
// env0 按峰值域归一（Σ env0*pf=8192，含 1/pf 补偿），初值 env0<<9
// `define PLUCK_ATTACK：引擎走逐槽起振/相位路径；删掉则退回按声部共用起振
// 重新生成：python src/tools/gen_partial_bank.py
// ============================================================
`ifndef PARTIAL_BANK_REC28_VH
`define PARTIAL_BANK_REC28_VH
`define PLUCK_ATTACK

localparam PT_NOTES = 28;          // 音库音数（声部数）
localparam PT_N     = 18;          // 每音泛音槽数
localparam PT_SUM_ENV0 = 8192;       // 每音峰值域归一总幅度

// 键 = voice*PT_N + (k-1)，越界返回 0；每音"叶子"函数 + 按 voice 包装。
function automatic [31:0] pt_inc_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v0 = 32'h015F25DC;   // C4 k=1 f=261.63Hz
        5'd1: pt_inc_v0 = 32'h02BE4BB8;   // C4 k=2 f=523.25Hz
        5'd2: pt_inc_v0 = 32'h041D7194;   // C4 k=3 f=784.88Hz
        5'd3: pt_inc_v0 = 32'h057C9770;   // C4 k=4 f=1046.50Hz
        5'd4: pt_inc_v0 = 32'h06DBBD4C;   // C4 k=5 f=1308.13Hz
        5'd5: pt_inc_v0 = 32'h083AE329;   // C4 k=6 f=1569.75Hz
        5'd6: pt_inc_v0 = 32'h099A0905;   // C4 k=7 f=1831.38Hz
        5'd7: pt_inc_v0 = 32'h0AF92EE1;   // C4 k=8 f=2093.00Hz
        5'd8: pt_inc_v0 = 32'h0C5854BD;   // C4 k=9 f=2354.63Hz
        5'd9: pt_inc_v0 = 32'h0DB77A99;   // C4 k=10 f=2616.26Hz
        5'd10: pt_inc_v0 = 32'h0F16A075;   // C4 k=11 f=2877.88Hz
        5'd16: pt_inc_v0 = 32'h1751839E;   // C4 k=17 f=4447.63Hz
        default: pt_inc_v0 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v0 = 16'd4123;   // C4 k=1 f=261.63Hz
        5'd1: pt_env0_v0 = 16'd2206;   // C4 k=2 f=523.25Hz
        5'd2: pt_env0_v0 = 16'd349;   // C4 k=3 f=784.88Hz
        5'd3: pt_env0_v0 = 16'd107;   // C4 k=4 f=1046.50Hz
        5'd4: pt_env0_v0 = 16'd614;   // C4 k=5 f=1308.13Hz
        5'd5: pt_env0_v0 = 16'd502;   // C4 k=6 f=1569.75Hz
        5'd6: pt_env0_v0 = 16'd222;   // C4 k=7 f=1831.38Hz
        5'd7: pt_env0_v0 = 16'd40;   // C4 k=8 f=2093.00Hz
        5'd8: pt_env0_v0 = 16'd173;   // C4 k=9 f=2354.63Hz
        5'd9: pt_env0_v0 = 16'd42;   // C4 k=10 f=2616.26Hz
        5'd10: pt_env0_v0 = 16'd48;   // C4 k=11 f=2877.88Hz
        5'd16: pt_env0_v0 = 16'd9;   // C4 k=17 f=4447.63Hz
        default: pt_env0_v0 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v0 = 12'd65;   // C4 k=1 f=261.63Hz
        5'd1: pt_rate_v0 = 12'd78;   // C4 k=2 f=523.25Hz
        5'd2: pt_rate_v0 = 12'd95;   // C4 k=3 f=784.88Hz
        5'd3: pt_rate_v0 = 12'd117;   // C4 k=4 f=1046.50Hz
        5'd4: pt_rate_v0 = 12'd353;   // C4 k=5 f=1308.13Hz
        5'd5: pt_rate_v0 = 12'd271;   // C4 k=6 f=1569.75Hz
        5'd6: pt_rate_v0 = 12'd217;   // C4 k=7 f=1831.38Hz
        5'd7: pt_rate_v0 = 12'd89;   // C4 k=8 f=2093.00Hz
        5'd8: pt_rate_v0 = 12'd264;   // C4 k=9 f=2354.63Hz
        5'd9: pt_rate_v0 = 12'd106;   // C4 k=10 f=2616.26Hz
        5'd10: pt_rate_v0 = 12'd170;   // C4 k=11 f=2877.88Hz
        5'd16: pt_rate_v0 = 12'd80;   // C4 k=17 f=4447.63Hz
        default: pt_rate_v0 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v0 = 16'd19688;   // C4 k=1 f=261.63Hz
        5'd1: pt_aatk_v0 = 16'd24389;   // C4 k=2 f=523.25Hz
        5'd2: pt_aatk_v0 = 16'd27076;   // C4 k=3 f=784.88Hz
        5'd3: pt_aatk_v0 = 16'd28797;   // C4 k=4 f=1046.50Hz
        5'd4: pt_aatk_v0 = 16'd23799;   // C4 k=5 f=1308.13Hz
        5'd5: pt_aatk_v0 = 16'd27435;   // C4 k=6 f=1569.75Hz
        5'd6: pt_aatk_v0 = 16'd30721;   // C4 k=7 f=1831.38Hz
        5'd7: pt_aatk_v0 = 16'd39149;   // C4 k=8 f=2093.00Hz
        5'd8: pt_aatk_v0 = 16'd32168;   // C4 k=9 f=2354.63Hz
        5'd9: pt_aatk_v0 = 16'd40915;   // C4 k=10 f=2616.26Hz
        5'd10: pt_aatk_v0 = 16'd38397;   // C4 k=11 f=2877.88Hz
        5'd16: pt_aatk_v0 = 16'd52180;   // C4 k=17 f=4447.63Hz
        default: pt_aatk_v0 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v0(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v0 = 32'h510C4619;   // C4 k=1 f=261.63Hz
        5'd1: pt_ph0_v0 = 32'hE02E553E;   // C4 k=2 f=523.25Hz
        5'd2: pt_ph0_v0 = 32'h7BB98F3A;   // C4 k=3 f=784.88Hz
        5'd3: pt_ph0_v0 = 32'h0183A8B5;   // C4 k=4 f=1046.50Hz
        5'd4: pt_ph0_v0 = 32'hE6336D1F;   // C4 k=5 f=1308.13Hz
        5'd5: pt_ph0_v0 = 32'hF989D237;   // C4 k=6 f=1569.75Hz
        5'd6: pt_ph0_v0 = 32'hBA2529D0;   // C4 k=7 f=1831.38Hz
        5'd7: pt_ph0_v0 = 32'hFCFBEDBF;   // C4 k=8 f=2093.00Hz
        5'd8: pt_ph0_v0 = 32'hA8C5EE39;   // C4 k=9 f=2354.63Hz
        5'd9: pt_ph0_v0 = 32'hB55A53B8;   // C4 k=10 f=2616.26Hz
        5'd10: pt_ph0_v0 = 32'h1A88A9EE;   // C4 k=11 f=2877.88Hz
        5'd16: pt_ph0_v0 = 32'hF918A8B4;   // C4 k=17 f=4447.63Hz
        default: pt_ph0_v0 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v1 = 32'h0174073A;   // C#4 k=1 f=277.18Hz
        5'd1: pt_inc_v1 = 32'h02E80E74;   // C#4 k=2 f=554.37Hz
        5'd2: pt_inc_v1 = 32'h045C15AE;   // C#4 k=3 f=831.55Hz
        5'd3: pt_inc_v1 = 32'h05D01CE8;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_inc_v1 = 32'h07442422;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_inc_v1 = 32'h08B82B5C;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_inc_v1 = 32'h0A2C3296;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_inc_v1 = 32'h0BA039D0;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_inc_v1 = 32'h0D14410B;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_inc_v1 = 32'h0E884845;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_inc_v1 = 32'h0FFC4F7F;   // C#4 k=11 f=3049.01Hz
        default: pt_inc_v1 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v1 = 16'd4428;   // C#4 k=1 f=277.18Hz
        5'd1: pt_env0_v1 = 16'd1805;   // C#4 k=2 f=554.37Hz
        5'd2: pt_env0_v1 = 16'd158;   // C#4 k=3 f=831.55Hz
        5'd3: pt_env0_v1 = 16'd236;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_env0_v1 = 16'd1245;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_env0_v1 = 16'd295;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_env0_v1 = 16'd246;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_env0_v1 = 16'd26;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_env0_v1 = 16'd171;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_env0_v1 = 16'd15;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_env0_v1 = 16'd22;   // C#4 k=11 f=3049.01Hz
        default: pt_env0_v1 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v1 = 12'd106;   // C#4 k=1 f=277.18Hz
        5'd1: pt_rate_v1 = 12'd149;   // C#4 k=2 f=554.37Hz
        5'd2: pt_rate_v1 = 12'd221;   // C#4 k=3 f=831.55Hz
        5'd3: pt_rate_v1 = 12'd322;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_rate_v1 = 12'd492;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_rate_v1 = 12'd354;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_rate_v1 = 12'd484;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_rate_v1 = 12'd142;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_rate_v1 = 12'd556;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_rate_v1 = 12'd225;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_rate_v1 = 12'd212;   // C#4 k=11 f=3049.01Hz
        default: pt_rate_v1 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v1 = 16'd17688;   // C#4 k=1 f=277.18Hz
        5'd1: pt_aatk_v1 = 16'd21104;   // C#4 k=2 f=554.37Hz
        5'd2: pt_aatk_v1 = 16'd22249;   // C#4 k=3 f=831.55Hz
        5'd3: pt_aatk_v1 = 16'd22411;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_aatk_v1 = 16'd21536;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_aatk_v1 = 16'd25506;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_aatk_v1 = 16'd24703;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_aatk_v1 = 16'd35541;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_aatk_v1 = 16'd26135;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_aatk_v1 = 16'd34786;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_aatk_v1 = 16'd36530;   // C#4 k=11 f=3049.01Hz
        default: pt_aatk_v1 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v1(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v1 = 32'h6DC588D3;   // C#4 k=1 f=277.18Hz
        5'd1: pt_ph0_v1 = 32'h472F513C;   // C#4 k=2 f=554.37Hz
        5'd2: pt_ph0_v1 = 32'h0C1870B8;   // C#4 k=3 f=831.55Hz
        5'd3: pt_ph0_v1 = 32'h43E1465F;   // C#4 k=4 f=1108.73Hz
        5'd4: pt_ph0_v1 = 32'h0E78EA8A;   // C#4 k=5 f=1385.91Hz
        5'd5: pt_ph0_v1 = 32'h761DC0DE;   // C#4 k=6 f=1663.10Hz
        5'd6: pt_ph0_v1 = 32'h0ECA9C7D;   // C#4 k=7 f=1940.28Hz
        5'd7: pt_ph0_v1 = 32'hF5E7493F;   // C#4 k=8 f=2217.46Hz
        5'd8: pt_ph0_v1 = 32'h84D44CBF;   // C#4 k=9 f=2494.64Hz
        5'd9: pt_ph0_v1 = 32'hA536E9DE;   // C#4 k=10 f=2771.83Hz
        5'd10: pt_ph0_v1 = 32'h79AFAED8;   // C#4 k=11 f=3049.01Hz
        default: pt_ph0_v1 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v2 = 32'h018A2672;   // D4 k=1 f=293.66Hz
        5'd1: pt_inc_v2 = 32'h03144CE4;   // D4 k=2 f=587.33Hz
        5'd2: pt_inc_v2 = 32'h049E7356;   // D4 k=3 f=880.99Hz
        5'd3: pt_inc_v2 = 32'h062899C9;   // D4 k=4 f=1174.66Hz
        5'd4: pt_inc_v2 = 32'h07B2C03B;   // D4 k=5 f=1468.32Hz
        5'd5: pt_inc_v2 = 32'h093CE6AD;   // D4 k=6 f=1761.99Hz
        5'd6: pt_inc_v2 = 32'h0AC70D1F;   // D4 k=7 f=2055.65Hz
        5'd7: pt_inc_v2 = 32'h0C513391;   // D4 k=8 f=2349.32Hz
        5'd8: pt_inc_v2 = 32'h0DDB5A03;   // D4 k=9 f=2642.98Hz
        default: pt_inc_v2 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v2 = 16'd6059;   // D4 k=1 f=293.66Hz
        5'd1: pt_env0_v2 = 16'd1614;   // D4 k=2 f=587.33Hz
        5'd2: pt_env0_v2 = 16'd55;   // D4 k=3 f=880.99Hz
        5'd3: pt_env0_v2 = 16'd176;   // D4 k=4 f=1174.66Hz
        5'd4: pt_env0_v2 = 16'd285;   // D4 k=5 f=1468.32Hz
        5'd5: pt_env0_v2 = 16'd238;   // D4 k=6 f=1761.99Hz
        5'd6: pt_env0_v2 = 16'd25;   // D4 k=7 f=2055.65Hz
        5'd7: pt_env0_v2 = 16'd18;   // D4 k=8 f=2349.32Hz
        5'd8: pt_env0_v2 = 16'd73;   // D4 k=9 f=2642.98Hz
        default: pt_env0_v2 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v2 = 12'd112;   // D4 k=1 f=293.66Hz
        5'd1: pt_rate_v2 = 12'd137;   // D4 k=2 f=587.33Hz
        5'd2: pt_rate_v2 = 12'd148;   // D4 k=3 f=880.99Hz
        5'd3: pt_rate_v2 = 12'd256;   // D4 k=4 f=1174.66Hz
        5'd4: pt_rate_v2 = 12'd354;   // D4 k=5 f=1468.32Hz
        5'd5: pt_rate_v2 = 12'd397;   // D4 k=6 f=1761.99Hz
        5'd6: pt_rate_v2 = 12'd117;   // D4 k=7 f=2055.65Hz
        5'd7: pt_rate_v2 = 12'd96;   // D4 k=8 f=2349.32Hz
        5'd8: pt_rate_v2 = 12'd367;   // D4 k=9 f=2642.98Hz
        default: pt_rate_v2 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v2 = 16'd17478;   // D4 k=1 f=293.66Hz
        5'd1: pt_aatk_v2 = 16'd21517;   // D4 k=2 f=587.33Hz
        5'd2: pt_aatk_v2 = 16'd24569;   // D4 k=3 f=880.99Hz
        5'd3: pt_aatk_v2 = 16'd23883;   // D4 k=4 f=1174.66Hz
        5'd4: pt_aatk_v2 = 16'd23787;   // D4 k=5 f=1468.32Hz
        5'd5: pt_aatk_v2 = 16'd24693;   // D4 k=6 f=1761.99Hz
        5'd6: pt_aatk_v2 = 16'd35292;   // D4 k=7 f=2055.65Hz
        5'd7: pt_aatk_v2 = 16'd38550;   // D4 k=8 f=2349.32Hz
        5'd8: pt_aatk_v2 = 16'd29509;   // D4 k=9 f=2642.98Hz
        default: pt_aatk_v2 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v2(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v2 = 32'h02E9F4A2;   // D4 k=1 f=293.66Hz
        5'd1: pt_ph0_v2 = 32'hB3C8F91C;   // D4 k=2 f=587.33Hz
        5'd2: pt_ph0_v2 = 32'h318EC249;   // D4 k=3 f=880.99Hz
        5'd3: pt_ph0_v2 = 32'hD13543EA;   // D4 k=4 f=1174.66Hz
        5'd4: pt_ph0_v2 = 32'h504FD68E;   // D4 k=5 f=1468.32Hz
        5'd5: pt_ph0_v2 = 32'hF9563BE1;   // D4 k=6 f=1761.99Hz
        5'd6: pt_ph0_v2 = 32'hFB6A9A74;   // D4 k=7 f=2055.65Hz
        5'd7: pt_ph0_v2 = 32'hACAD82A6;   // D4 k=8 f=2349.32Hz
        5'd8: pt_ph0_v2 = 32'h83D0D79A;   // D4 k=9 f=2642.98Hz
        default: pt_ph0_v2 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v3 = 32'h01A1966B;   // D#4 k=1 f=311.13Hz
        5'd1: pt_inc_v3 = 32'h03432CD6;   // D#4 k=2 f=622.25Hz
        5'd2: pt_inc_v3 = 32'h04E4C341;   // D#4 k=3 f=933.38Hz
        5'd3: pt_inc_v3 = 32'h068659AC;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_inc_v3 = 32'h0827F017;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_inc_v3 = 32'h09C98681;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_inc_v3 = 32'h0B6B1CEC;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_inc_v3 = 32'h0D0CB357;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_inc_v3 = 32'h0EAE49C2;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_inc_v3 = 32'h104FE02D;   // D#4 k=10 f=3111.27Hz
        5'd10: pt_inc_v3 = 32'h11F17698;   // D#4 k=11 f=3422.40Hz
        5'd11: pt_inc_v3 = 32'h13930D03;   // D#4 k=12 f=3733.52Hz
        5'd12: pt_inc_v3 = 32'h1534A36E;   // D#4 k=13 f=4044.65Hz
        5'd13: pt_inc_v3 = 32'h16D639D9;   // D#4 k=14 f=4355.78Hz
        5'd16: pt_inc_v3 = 32'h1BBAFD19;   // D#4 k=17 f=5289.16Hz
        5'd17: pt_inc_v3 = 32'h1D5C9384;   // D#4 k=18 f=5600.29Hz
        default: pt_inc_v3 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v3 = 16'd5433;   // D#4 k=1 f=311.13Hz
        5'd1: pt_env0_v3 = 16'd330;   // D#4 k=2 f=622.25Hz
        5'd2: pt_env0_v3 = 16'd769;   // D#4 k=3 f=933.38Hz
        5'd3: pt_env0_v3 = 16'd872;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_env0_v3 = 16'd314;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_env0_v3 = 16'd488;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_env0_v3 = 16'd180;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_env0_v3 = 16'd62;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_env0_v3 = 16'd96;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_env0_v3 = 16'd11;   // D#4 k=10 f=3111.27Hz
        5'd10: pt_env0_v3 = 16'd48;   // D#4 k=11 f=3422.40Hz
        5'd11: pt_env0_v3 = 16'd8;   // D#4 k=12 f=3733.52Hz
        5'd12: pt_env0_v3 = 16'd11;   // D#4 k=13 f=4044.65Hz
        5'd13: pt_env0_v3 = 16'd40;   // D#4 k=14 f=4355.78Hz
        5'd16: pt_env0_v3 = 16'd13;   // D#4 k=17 f=5289.16Hz
        5'd17: pt_env0_v3 = 16'd30;   // D#4 k=18 f=5600.29Hz
        default: pt_env0_v3 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v3 = 12'd144;   // D#4 k=1 f=311.13Hz
        5'd1: pt_rate_v3 = 12'd83;   // D#4 k=2 f=622.25Hz
        5'd2: pt_rate_v3 = 12'd215;   // D#4 k=3 f=933.38Hz
        5'd3: pt_rate_v3 = 12'd412;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_rate_v3 = 12'd667;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_rate_v3 = 12'd218;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_rate_v3 = 12'd435;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_rate_v3 = 12'd394;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_rate_v3 = 12'd729;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_rate_v3 = 12'd152;   // D#4 k=10 f=3111.27Hz
        5'd10: pt_rate_v3 = 12'd341;   // D#4 k=11 f=3422.40Hz
        5'd11: pt_rate_v3 = 12'd175;   // D#4 k=12 f=3733.52Hz
        5'd12: pt_rate_v3 = 12'd197;   // D#4 k=13 f=4044.65Hz
        5'd13: pt_rate_v3 = 12'd477;   // D#4 k=14 f=4355.78Hz
        5'd16: pt_rate_v3 = 12'd1385;   // D#4 k=17 f=5289.16Hz
        5'd17: pt_rate_v3 = 12'd1385;   // D#4 k=18 f=5600.29Hz
        default: pt_rate_v3 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v3 = 16'd16432;   // D#4 k=1 f=311.13Hz
        5'd1: pt_aatk_v3 = 16'd24043;   // D#4 k=2 f=622.25Hz
        5'd2: pt_aatk_v3 = 16'd22407;   // D#4 k=3 f=933.38Hz
        5'd3: pt_aatk_v3 = 16'd20851;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_aatk_v3 = 16'd19444;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_aatk_v3 = 16'd28979;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_aatk_v3 = 16'd25510;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_aatk_v3 = 16'd27654;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_aatk_v3 = 16'd23912;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_aatk_v3 = 16'd37986;   // D#4 k=10 f=3111.27Hz
        5'd10: pt_aatk_v3 = 16'd32496;   // D#4 k=11 f=3422.40Hz
        5'd11: pt_aatk_v3 = 16'd39404;   // D#4 k=12 f=3733.52Hz
        5'd12: pt_aatk_v3 = 16'd39522;   // D#4 k=13 f=4044.65Hz
        5'd13: pt_aatk_v3 = 16'd32496;   // D#4 k=14 f=4355.78Hz
        5'd16: pt_aatk_v3 = 16'd24467;   // D#4 k=17 f=5289.16Hz
        5'd17: pt_aatk_v3 = 16'd25063;   // D#4 k=18 f=5600.29Hz
        default: pt_aatk_v3 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v3(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v3 = 32'hBD58BA6B;   // D#4 k=1 f=311.13Hz
        5'd1: pt_ph0_v3 = 32'hE8A46341;   // D#4 k=2 f=622.25Hz
        5'd2: pt_ph0_v3 = 32'hFD4255C7;   // D#4 k=3 f=933.38Hz
        5'd3: pt_ph0_v3 = 32'h48A7297A;   // D#4 k=4 f=1244.51Hz
        5'd4: pt_ph0_v3 = 32'h1C8FC87E;   // D#4 k=5 f=1555.63Hz
        5'd5: pt_ph0_v3 = 32'h558F2D7E;   // D#4 k=6 f=1866.76Hz
        5'd6: pt_ph0_v3 = 32'hB43618AE;   // D#4 k=7 f=2177.89Hz
        5'd7: pt_ph0_v3 = 32'h935F84DF;   // D#4 k=8 f=2489.02Hz
        5'd8: pt_ph0_v3 = 32'h1B4EF29D;   // D#4 k=9 f=2800.14Hz
        5'd9: pt_ph0_v3 = 32'h66BB3273;   // D#4 k=10 f=3111.27Hz
        5'd10: pt_ph0_v3 = 32'h1E5F1329;   // D#4 k=11 f=3422.40Hz
        5'd11: pt_ph0_v3 = 32'h7B73EBB4;   // D#4 k=12 f=3733.52Hz
        5'd12: pt_ph0_v3 = 32'hC6A87E76;   // D#4 k=13 f=4044.65Hz
        5'd13: pt_ph0_v3 = 32'hE5BD8265;   // D#4 k=14 f=4355.78Hz
        5'd16: pt_ph0_v3 = 32'hEBD01B3D;   // D#4 k=17 f=5289.16Hz
        5'd17: pt_ph0_v3 = 32'hFE4E23A6;   // D#4 k=18 f=5600.29Hz
        default: pt_ph0_v3 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v4 = 32'h01BA6B2B;   // E4 k=1 f=329.63Hz
        5'd1: pt_inc_v4 = 32'h0374D655;   // E4 k=2 f=659.26Hz
        5'd2: pt_inc_v4 = 32'h052F4180;   // E4 k=3 f=988.88Hz
        5'd3: pt_inc_v4 = 32'h06E9ACAA;   // E4 k=4 f=1318.51Hz
        5'd4: pt_inc_v4 = 32'h08A417D5;   // E4 k=5 f=1648.14Hz
        5'd5: pt_inc_v4 = 32'h0A5E82FF;   // E4 k=6 f=1977.77Hz
        5'd6: pt_inc_v4 = 32'h0C18EE2A;   // E4 k=7 f=2307.39Hz
        5'd7: pt_inc_v4 = 32'h0DD35954;   // E4 k=8 f=2637.02Hz
        5'd8: pt_inc_v4 = 32'h0F8DC47F;   // E4 k=9 f=2966.65Hz
        5'd9: pt_inc_v4 = 32'h11482FA9;   // E4 k=10 f=3296.28Hz
        default: pt_inc_v4 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v4 = 16'd5210;   // E4 k=1 f=329.63Hz
        5'd1: pt_env0_v4 = 16'd825;   // E4 k=2 f=659.26Hz
        5'd2: pt_env0_v4 = 16'd646;   // E4 k=3 f=988.88Hz
        5'd3: pt_env0_v4 = 16'd205;   // E4 k=4 f=1318.51Hz
        5'd4: pt_env0_v4 = 16'd964;   // E4 k=5 f=1648.14Hz
        5'd5: pt_env0_v4 = 16'd636;   // E4 k=6 f=1977.77Hz
        5'd6: pt_env0_v4 = 16'd26;   // E4 k=7 f=2307.39Hz
        5'd7: pt_env0_v4 = 16'd14;   // E4 k=8 f=2637.02Hz
        5'd8: pt_env0_v4 = 16'd100;   // E4 k=9 f=2966.65Hz
        5'd9: pt_env0_v4 = 16'd14;   // E4 k=10 f=3296.28Hz
        default: pt_env0_v4 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v4 = 12'd136;   // E4 k=1 f=329.63Hz
        5'd1: pt_rate_v4 = 12'd137;   // E4 k=2 f=659.26Hz
        5'd2: pt_rate_v4 = 12'd154;   // E4 k=3 f=988.88Hz
        5'd3: pt_rate_v4 = 12'd140;   // E4 k=4 f=1318.51Hz
        5'd4: pt_rate_v4 = 12'd392;   // E4 k=5 f=1648.14Hz
        5'd5: pt_rate_v4 = 12'd400;   // E4 k=6 f=1977.77Hz
        5'd6: pt_rate_v4 = 12'd222;   // E4 k=7 f=2307.39Hz
        5'd7: pt_rate_v4 = 12'd161;   // E4 k=8 f=2637.02Hz
        5'd8: pt_rate_v4 = 12'd512;   // E4 k=9 f=2966.65Hz
        5'd9: pt_rate_v4 = 12'd271;   // E4 k=10 f=3296.28Hz
        default: pt_rate_v4 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v4 = 16'd16666;   // E4 k=1 f=329.63Hz
        5'd1: pt_aatk_v4 = 16'd21547;   // E4 k=2 f=659.26Hz
        5'd2: pt_aatk_v4 = 16'd24332;   // E4 k=3 f=988.88Hz
        5'd3: pt_aatk_v4 = 16'd27657;   // E4 k=4 f=1318.51Hz
        5'd4: pt_aatk_v4 = 16'd23084;   // E4 k=5 f=1648.14Hz
        5'd5: pt_aatk_v4 = 16'd24643;   // E4 k=6 f=1977.77Hz
        5'd6: pt_aatk_v4 = 16'd30571;   // E4 k=7 f=2307.39Hz
        5'd7: pt_aatk_v4 = 16'd34613;   // E4 k=8 f=2637.02Hz
        5'd8: pt_aatk_v4 = 16'd26808;   // E4 k=9 f=2966.65Hz
        5'd9: pt_aatk_v4 = 16'd33228;   // E4 k=10 f=3296.28Hz
        default: pt_aatk_v4 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v4(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v4 = 32'h7D6529DB;   // E4 k=1 f=329.63Hz
        5'd1: pt_ph0_v4 = 32'hD39A9B74;   // E4 k=2 f=659.26Hz
        5'd2: pt_ph0_v4 = 32'h9E7F3ACE;   // E4 k=3 f=988.88Hz
        5'd3: pt_ph0_v4 = 32'h5DFE0DFD;   // E4 k=4 f=1318.51Hz
        5'd4: pt_ph0_v4 = 32'h147D987D;   // E4 k=5 f=1648.14Hz
        5'd5: pt_ph0_v4 = 32'h493F1344;   // E4 k=6 f=1977.77Hz
        5'd6: pt_ph0_v4 = 32'hC1AF1B0F;   // E4 k=7 f=2307.39Hz
        5'd7: pt_ph0_v4 = 32'h7B13A768;   // E4 k=8 f=2637.02Hz
        5'd8: pt_ph0_v4 = 32'hF02AB277;   // E4 k=9 f=2966.65Hz
        5'd9: pt_ph0_v4 = 32'h6AE429E5;   // E4 k=10 f=3296.28Hz
        default: pt_ph0_v4 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v5 = 32'h01D4B9E8;   // F4 k=1 f=349.23Hz
        5'd1: pt_inc_v5 = 32'h03A973D0;   // F4 k=2 f=698.46Hz
        5'd2: pt_inc_v5 = 32'h057E2DB8;   // F4 k=3 f=1047.68Hz
        5'd3: pt_inc_v5 = 32'h0752E7A0;   // F4 k=4 f=1396.91Hz
        5'd4: pt_inc_v5 = 32'h0927A188;   // F4 k=5 f=1746.14Hz
        5'd5: pt_inc_v5 = 32'h0AFC5B71;   // F4 k=6 f=2095.37Hz
        5'd6: pt_inc_v5 = 32'h0CD11559;   // F4 k=7 f=2444.60Hz
        5'd7: pt_inc_v5 = 32'h0EA5CF41;   // F4 k=8 f=2793.83Hz
        5'd8: pt_inc_v5 = 32'h107A8929;   // F4 k=9 f=3143.05Hz
        5'd10: pt_inc_v5 = 32'h1423FCF9;   // F4 k=11 f=3841.51Hz
        default: pt_inc_v5 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v5 = 16'd4624;   // F4 k=1 f=349.23Hz
        5'd1: pt_env0_v5 = 16'd1760;   // F4 k=2 f=698.46Hz
        5'd2: pt_env0_v5 = 16'd941;   // F4 k=3 f=1047.68Hz
        5'd3: pt_env0_v5 = 16'd202;   // F4 k=4 f=1396.91Hz
        5'd4: pt_env0_v5 = 16'd701;   // F4 k=5 f=1746.14Hz
        5'd5: pt_env0_v5 = 16'd433;   // F4 k=6 f=2095.37Hz
        5'd6: pt_env0_v5 = 16'd12;   // F4 k=7 f=2444.60Hz
        5'd7: pt_env0_v5 = 16'd15;   // F4 k=8 f=2793.83Hz
        5'd8: pt_env0_v5 = 16'd72;   // F4 k=9 f=3143.05Hz
        5'd10: pt_env0_v5 = 16'd39;   // F4 k=11 f=3841.51Hz
        default: pt_env0_v5 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v5 = 12'd191;   // F4 k=1 f=349.23Hz
        5'd1: pt_rate_v5 = 12'd317;   // F4 k=2 f=698.46Hz
        5'd2: pt_rate_v5 = 12'd218;   // F4 k=3 f=1047.68Hz
        5'd3: pt_rate_v5 = 12'd555;   // F4 k=4 f=1396.91Hz
        5'd4: pt_rate_v5 = 12'd148;   // F4 k=5 f=1746.14Hz
        5'd5: pt_rate_v5 = 12'd461;   // F4 k=6 f=2095.37Hz
        5'd6: pt_rate_v5 = 12'd86;   // F4 k=7 f=2444.60Hz
        5'd7: pt_rate_v5 = 12'd128;   // F4 k=8 f=2793.83Hz
        5'd8: pt_rate_v5 = 12'd409;   // F4 k=9 f=3143.05Hz
        5'd10: pt_rate_v5 = 12'd448;   // F4 k=11 f=3841.51Hz
        default: pt_rate_v5 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v5 = 16'd15245;   // F4 k=1 f=349.23Hz
        5'd1: pt_aatk_v5 = 16'd17210;   // F4 k=2 f=698.46Hz
        5'd2: pt_aatk_v5 = 16'd22337;   // F4 k=3 f=1047.68Hz
        5'd3: pt_aatk_v5 = 16'd18936;   // F4 k=4 f=1396.91Hz
        5'd4: pt_aatk_v5 = 16'd29652;   // F4 k=5 f=1746.14Hz
        5'd5: pt_aatk_v5 = 16'd23614;   // F4 k=6 f=2095.37Hz
        5'd6: pt_aatk_v5 = 16'd37517;   // F4 k=7 f=2444.60Hz
        5'd7: pt_aatk_v5 = 16'd36333;   // F4 k=8 f=2793.83Hz
        5'd8: pt_aatk_v5 = 16'd28620;   // F4 k=9 f=3143.05Hz
        5'd10: pt_aatk_v5 = 16'd30146;   // F4 k=11 f=3841.51Hz
        default: pt_aatk_v5 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v5(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v5 = 32'h14C73F29;   // F4 k=1 f=349.23Hz
        5'd1: pt_ph0_v5 = 32'h976EB1B8;   // F4 k=2 f=698.46Hz
        5'd2: pt_ph0_v5 = 32'h6A6BB394;   // F4 k=3 f=1047.68Hz
        5'd3: pt_ph0_v5 = 32'h9F3E8E98;   // F4 k=4 f=1396.91Hz
        5'd4: pt_ph0_v5 = 32'h9358942E;   // F4 k=5 f=1746.14Hz
        5'd5: pt_ph0_v5 = 32'hBA7F8CC0;   // F4 k=6 f=2095.37Hz
        5'd6: pt_ph0_v5 = 32'h37128F53;   // F4 k=7 f=2444.60Hz
        5'd7: pt_ph0_v5 = 32'hB9E359CF;   // F4 k=8 f=2793.83Hz
        5'd8: pt_ph0_v5 = 32'h8980C4E2;   // F4 k=9 f=3143.05Hz
        5'd10: pt_ph0_v5 = 32'hB28541EC;   // F4 k=11 f=3841.51Hz
        default: pt_ph0_v5 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v6 = 32'h01F0991E;   // F#4 k=1 f=369.99Hz
        5'd1: pt_inc_v6 = 32'h03E1323B;   // F#4 k=2 f=739.99Hz
        5'd2: pt_inc_v6 = 32'h05D1CB59;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_inc_v6 = 32'h07C26476;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_inc_v6 = 32'h09B2FD94;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_inc_v6 = 32'h0BA396B2;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_inc_v6 = 32'h0D942FCF;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_inc_v6 = 32'h0F84C8ED;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_inc_v6 = 32'h1175620A;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_inc_v6 = 32'h1365FB28;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_inc_v6 = 32'h15569446;   // F#4 k=11 f=4069.94Hz
        5'd17: pt_inc_v6 = 32'h22EAC415;   // F#4 k=18 f=6659.90Hz
        default: pt_inc_v6 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v6 = 16'd6400;   // F#4 k=1 f=369.99Hz
        5'd1: pt_env0_v6 = 16'd628;   // F#4 k=2 f=739.99Hz
        5'd2: pt_env0_v6 = 16'd758;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_env0_v6 = 16'd138;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_env0_v6 = 16'd637;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_env0_v6 = 16'd252;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_env0_v6 = 16'd8;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_env0_v6 = 16'd11;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_env0_v6 = 16'd125;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_env0_v6 = 16'd26;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_env0_v6 = 16'd35;   // F#4 k=11 f=4069.94Hz
        5'd17: pt_env0_v6 = 16'd23;   // F#4 k=18 f=6659.90Hz
        default: pt_env0_v6 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v6 = 12'd292;   // F#4 k=1 f=369.99Hz
        5'd1: pt_rate_v6 = 12'd217;   // F#4 k=2 f=739.99Hz
        5'd2: pt_rate_v6 = 12'd259;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_rate_v6 = 12'd351;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_rate_v6 = 12'd368;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_rate_v6 = 12'd503;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_rate_v6 = 12'd142;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_rate_v6 = 12'd155;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_rate_v6 = 12'd787;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_rate_v6 = 12'd521;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_rate_v6 = 12'd664;   // F#4 k=11 f=4069.94Hz
        5'd17: pt_rate_v6 = 12'd574;   // F#4 k=18 f=6659.90Hz
        default: pt_rate_v6 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v6 = 16'd13448;   // F#4 k=1 f=369.99Hz
        5'd1: pt_aatk_v6 = 16'd19167;   // F#4 k=2 f=739.99Hz
        5'd2: pt_aatk_v6 = 16'd21339;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_aatk_v6 = 16'd21862;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_aatk_v6 = 16'd23513;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_aatk_v6 = 16'd22983;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_aatk_v6 = 16'd33857;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_aatk_v6 = 16'd34900;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_aatk_v6 = 16'd23284;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_aatk_v6 = 16'd27788;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_aatk_v6 = 16'd26735;   // F#4 k=11 f=4069.94Hz
        5'd17: pt_aatk_v6 = 16'd33939;   // F#4 k=18 f=6659.90Hz
        default: pt_aatk_v6 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v6(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v6 = 32'h4DA15AB0;   // F#4 k=1 f=369.99Hz
        5'd1: pt_ph0_v6 = 32'hB81A50AB;   // F#4 k=2 f=739.99Hz
        5'd2: pt_ph0_v6 = 32'hB3E67C2C;   // F#4 k=3 f=1109.98Hz
        5'd3: pt_ph0_v6 = 32'hF01B81BD;   // F#4 k=4 f=1479.98Hz
        5'd4: pt_ph0_v6 = 32'h85A054CB;   // F#4 k=5 f=1849.97Hz
        5'd5: pt_ph0_v6 = 32'h681719B7;   // F#4 k=6 f=2219.97Hz
        5'd6: pt_ph0_v6 = 32'hEF1638C7;   // F#4 k=7 f=2589.96Hz
        5'd7: pt_ph0_v6 = 32'h29D754C0;   // F#4 k=8 f=2959.96Hz
        5'd8: pt_ph0_v6 = 32'hAAA99987;   // F#4 k=9 f=3329.95Hz
        5'd9: pt_ph0_v6 = 32'hAABF9C2B;   // F#4 k=10 f=3699.94Hz
        5'd10: pt_ph0_v6 = 32'h7E60C676;   // F#4 k=11 f=4069.94Hz
        5'd17: pt_ph0_v6 = 32'hB3689101;   // F#4 k=18 f=6659.90Hz
        default: pt_ph0_v6 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v7 = 32'h020E209B;   // G4 k=1 f=392.00Hz
        5'd1: pt_inc_v7 = 32'h041C4136;   // G4 k=2 f=783.99Hz
        5'd2: pt_inc_v7 = 32'h062A61D2;   // G4 k=3 f=1175.99Hz
        5'd3: pt_inc_v7 = 32'h0838826D;   // G4 k=4 f=1567.98Hz
        5'd4: pt_inc_v7 = 32'h0A46A308;   // G4 k=5 f=1959.98Hz
        5'd5: pt_inc_v7 = 32'h0C54C3A3;   // G4 k=6 f=2351.97Hz
        5'd6: pt_inc_v7 = 32'h0E62E43E;   // G4 k=7 f=2743.97Hz
        5'd7: pt_inc_v7 = 32'h107104DA;   // G4 k=8 f=3135.96Hz
        5'd8: pt_inc_v7 = 32'h127F2575;   // G4 k=9 f=3527.96Hz
        5'd10: pt_inc_v7 = 32'h169B66AB;   // G4 k=11 f=4311.95Hz
        default: pt_inc_v7 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v7 = 16'd3507;   // G4 k=1 f=392.00Hz
        5'd1: pt_env0_v7 = 16'd1394;   // G4 k=2 f=783.99Hz
        5'd2: pt_env0_v7 = 16'd515;   // G4 k=3 f=1175.99Hz
        5'd3: pt_env0_v7 = 16'd384;   // G4 k=4 f=1567.98Hz
        5'd4: pt_env0_v7 = 16'd2320;   // G4 k=5 f=1959.98Hz
        5'd5: pt_env0_v7 = 16'd537;   // G4 k=6 f=2351.97Hz
        5'd6: pt_env0_v7 = 16'd50;   // G4 k=7 f=2743.97Hz
        5'd7: pt_env0_v7 = 16'd23;   // G4 k=8 f=3135.96Hz
        5'd8: pt_env0_v7 = 16'd43;   // G4 k=9 f=3527.96Hz
        5'd10: pt_env0_v7 = 16'd52;   // G4 k=11 f=4311.95Hz
        default: pt_env0_v7 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v7 = 12'd152;   // G4 k=1 f=392.00Hz
        5'd1: pt_rate_v7 = 12'd185;   // G4 k=2 f=783.99Hz
        5'd2: pt_rate_v7 = 12'd277;   // G4 k=3 f=1175.99Hz
        5'd3: pt_rate_v7 = 12'd356;   // G4 k=4 f=1567.98Hz
        5'd4: pt_rate_v7 = 12'd486;   // G4 k=5 f=1959.98Hz
        5'd5: pt_rate_v7 = 12'd500;   // G4 k=6 f=2351.97Hz
        5'd6: pt_rate_v7 = 12'd459;   // G4 k=7 f=2743.97Hz
        5'd7: pt_rate_v7 = 12'd255;   // G4 k=8 f=3135.96Hz
        5'd8: pt_rate_v7 = 12'd305;   // G4 k=9 f=3527.96Hz
        5'd10: pt_rate_v7 = 12'd878;   // G4 k=11 f=4311.95Hz
        default: pt_rate_v7 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v7 = 16'd16180;   // G4 k=1 f=392.00Hz
        5'd1: pt_aatk_v7 = 16'd19990;   // G4 k=2 f=783.99Hz
        5'd2: pt_aatk_v7 = 16'd20937;   // G4 k=3 f=1175.99Hz
        5'd3: pt_aatk_v7 = 16'd21784;   // G4 k=4 f=1567.98Hz
        5'd4: pt_aatk_v7 = 16'd21621;   // G4 k=5 f=1959.98Hz
        5'd5: pt_aatk_v7 = 16'd23029;   // G4 k=6 f=2351.97Hz
        5'd6: pt_aatk_v7 = 16'd25106;   // G4 k=7 f=2743.97Hz
        5'd7: pt_aatk_v7 = 16'd31035;   // G4 k=8 f=3135.96Hz
        5'd8: pt_aatk_v7 = 16'd31002;   // G4 k=9 f=3527.96Hz
        5'd10: pt_aatk_v7 = 16'd24301;   // G4 k=11 f=4311.95Hz
        default: pt_aatk_v7 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v7(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v7 = 32'h8854D505;   // G4 k=1 f=392.00Hz
        5'd1: pt_ph0_v7 = 32'h4C7BF39F;   // G4 k=2 f=783.99Hz
        5'd2: pt_ph0_v7 = 32'h730959FB;   // G4 k=3 f=1175.99Hz
        5'd3: pt_ph0_v7 = 32'h5EF4A9E0;   // G4 k=4 f=1567.98Hz
        5'd4: pt_ph0_v7 = 32'hB2D14C84;   // G4 k=5 f=1959.98Hz
        5'd5: pt_ph0_v7 = 32'hF371A5A4;   // G4 k=6 f=2351.97Hz
        5'd6: pt_ph0_v7 = 32'h3F6D8E86;   // G4 k=7 f=2743.97Hz
        5'd7: pt_ph0_v7 = 32'h591C32D8;   // G4 k=8 f=3135.96Hz
        5'd8: pt_ph0_v7 = 32'h37ACF21B;   // G4 k=9 f=3527.96Hz
        5'd10: pt_ph0_v7 = 32'h94171B6C;   // G4 k=11 f=4311.95Hz
        default: pt_ph0_v7 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v8 = 32'h022D699C;   // G#4 k=1 f=415.30Hz
        5'd1: pt_inc_v8 = 32'h045AD337;   // G#4 k=2 f=830.61Hz
        5'd2: pt_inc_v8 = 32'h06883CD3;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_inc_v8 = 32'h08B5A66E;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_inc_v8 = 32'h0AE3100A;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_inc_v8 = 32'h0D1079A5;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_inc_v8 = 32'h0F3DE341;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_inc_v8 = 32'h116B4CDC;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_inc_v8 = 32'h1398B678;   // G#4 k=9 f=3737.74Hz
        5'd15: pt_inc_v8 = 32'h22D699B8;   // G#4 k=16 f=6644.88Hz
        default: pt_inc_v8 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v8 = 16'd5734;   // G#4 k=1 f=415.30Hz
        5'd1: pt_env0_v8 = 16'd1555;   // G#4 k=2 f=830.61Hz
        5'd2: pt_env0_v8 = 16'd708;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_env0_v8 = 16'd744;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_env0_v8 = 16'd472;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_env0_v8 = 16'd229;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_env0_v8 = 16'd41;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_env0_v8 = 16'd10;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_env0_v8 = 16'd267;   // G#4 k=9 f=3737.74Hz
        5'd15: pt_env0_v8 = 16'd8;   // G#4 k=16 f=6644.88Hz
        default: pt_env0_v8 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v8 = 12'd622;   // G#4 k=1 f=415.30Hz
        5'd1: pt_rate_v8 = 12'd211;   // G#4 k=2 f=830.61Hz
        5'd2: pt_rate_v8 = 12'd305;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_rate_v8 = 12'd490;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_rate_v8 = 12'd434;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_rate_v8 = 12'd549;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_rate_v8 = 12'd301;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_rate_v8 = 12'd153;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_rate_v8 = 12'd746;   // G#4 k=9 f=3737.74Hz
        5'd15: pt_rate_v8 = 12'd659;   // G#4 k=16 f=6644.88Hz
        default: pt_rate_v8 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v8 = 16'd10247;   // G#4 k=1 f=415.30Hz
        5'd1: pt_aatk_v8 = 16'd19325;   // G#4 k=2 f=830.61Hz
        5'd2: pt_aatk_v8 = 16'd20385;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_aatk_v8 = 16'd19742;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_aatk_v8 = 16'd22389;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_aatk_v8 = 16'd22350;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_aatk_v8 = 16'd28281;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_aatk_v8 = 16'd35000;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_aatk_v8 = 16'd23730;   // G#4 k=9 f=3737.74Hz
        5'd15: pt_aatk_v8 = 16'd31074;   // G#4 k=16 f=6644.88Hz
        default: pt_aatk_v8 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v8(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v8 = 32'h982EBAF1;   // G#4 k=1 f=415.30Hz
        5'd1: pt_ph0_v8 = 32'hA1671469;   // G#4 k=2 f=830.61Hz
        5'd2: pt_ph0_v8 = 32'h3EA8A61C;   // G#4 k=3 f=1245.91Hz
        5'd3: pt_ph0_v8 = 32'h670D5609;   // G#4 k=4 f=1661.22Hz
        5'd4: pt_ph0_v8 = 32'h744E0D0F;   // G#4 k=5 f=2076.52Hz
        5'd5: pt_ph0_v8 = 32'h081948F8;   // G#4 k=6 f=2491.83Hz
        5'd6: pt_ph0_v8 = 32'h01CD571B;   // G#4 k=7 f=2907.13Hz
        5'd7: pt_ph0_v8 = 32'hCEE2330C;   // G#4 k=8 f=3322.44Hz
        5'd8: pt_ph0_v8 = 32'h98FD1EED;   // G#4 k=9 f=3737.74Hz
        5'd15: pt_ph0_v8 = 32'h5F34CCDD;   // G#4 k=16 f=6644.88Hz
        default: pt_ph0_v8 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v9 = 32'h024E8ED9;   // A4 k=1 f=440.00Hz
        5'd1: pt_inc_v9 = 32'h049D1DB3;   // A4 k=2 f=880.00Hz
        5'd2: pt_inc_v9 = 32'h06EBAC8C;   // A4 k=3 f=1320.00Hz
        5'd3: pt_inc_v9 = 32'h093A3B65;   // A4 k=4 f=1760.00Hz
        5'd4: pt_inc_v9 = 32'h0B88CA3E;   // A4 k=5 f=2200.00Hz
        5'd5: pt_inc_v9 = 32'h0DD75918;   // A4 k=6 f=2640.00Hz
        5'd6: pt_inc_v9 = 32'h1025E7F1;   // A4 k=7 f=3080.00Hz
        5'd7: pt_inc_v9 = 32'h127476CA;   // A4 k=8 f=3520.00Hz
        5'd8: pt_inc_v9 = 32'h14C305A4;   // A4 k=9 f=3960.00Hz
        5'd9: pt_inc_v9 = 32'h1711947D;   // A4 k=10 f=4400.00Hz
        5'd10: pt_inc_v9 = 32'h19602356;   // A4 k=11 f=4840.00Hz
        5'd11: pt_inc_v9 = 32'h1BAEB230;   // A4 k=12 f=5280.00Hz
        5'd12: pt_inc_v9 = 32'h1DFD4109;   // A4 k=13 f=5720.00Hz
        5'd13: pt_inc_v9 = 32'h204BCFE2;   // A4 k=14 f=6160.00Hz
        5'd14: pt_inc_v9 = 32'h229A5EBB;   // A4 k=15 f=6600.00Hz
        5'd15: pt_inc_v9 = 32'h24E8ED95;   // A4 k=16 f=7040.00Hz
        5'd17: pt_inc_v9 = 32'h29860B47;   // A4 k=18 f=7920.00Hz
        default: pt_inc_v9 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v9 = 16'd5274;   // A4 k=1 f=440.00Hz
        5'd1: pt_env0_v9 = 16'd633;   // A4 k=2 f=880.00Hz
        5'd2: pt_env0_v9 = 16'd100;   // A4 k=3 f=1320.00Hz
        5'd3: pt_env0_v9 = 16'd985;   // A4 k=4 f=1760.00Hz
        5'd4: pt_env0_v9 = 16'd1280;   // A4 k=5 f=2200.00Hz
        5'd5: pt_env0_v9 = 16'd312;   // A4 k=6 f=2640.00Hz
        5'd6: pt_env0_v9 = 16'd192;   // A4 k=7 f=3080.00Hz
        5'd7: pt_env0_v9 = 16'd402;   // A4 k=8 f=3520.00Hz
        5'd8: pt_env0_v9 = 16'd147;   // A4 k=9 f=3960.00Hz
        5'd9: pt_env0_v9 = 16'd68;   // A4 k=10 f=4400.00Hz
        5'd10: pt_env0_v9 = 16'd56;   // A4 k=11 f=4840.00Hz
        5'd11: pt_env0_v9 = 16'd135;   // A4 k=12 f=5280.00Hz
        5'd12: pt_env0_v9 = 16'd15;   // A4 k=13 f=5720.00Hz
        5'd13: pt_env0_v9 = 16'd13;   // A4 k=14 f=6160.00Hz
        5'd14: pt_env0_v9 = 16'd27;   // A4 k=15 f=6600.00Hz
        5'd15: pt_env0_v9 = 16'd44;   // A4 k=16 f=7040.00Hz
        5'd17: pt_env0_v9 = 16'd17;   // A4 k=18 f=7920.00Hz
        default: pt_env0_v9 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v9 = 12'd630;   // A4 k=1 f=440.00Hz
        5'd1: pt_rate_v9 = 12'd187;   // A4 k=2 f=880.00Hz
        5'd2: pt_rate_v9 = 12'd163;   // A4 k=3 f=1320.00Hz
        5'd3: pt_rate_v9 = 12'd288;   // A4 k=4 f=1760.00Hz
        5'd4: pt_rate_v9 = 12'd314;   // A4 k=5 f=2200.00Hz
        5'd5: pt_rate_v9 = 12'd484;   // A4 k=6 f=2640.00Hz
        5'd6: pt_rate_v9 = 12'd606;   // A4 k=7 f=3080.00Hz
        5'd7: pt_rate_v9 = 12'd555;   // A4 k=8 f=3520.00Hz
        5'd8: pt_rate_v9 = 12'd498;   // A4 k=9 f=3960.00Hz
        5'd9: pt_rate_v9 = 12'd852;   // A4 k=10 f=4400.00Hz
        5'd10: pt_rate_v9 = 12'd518;   // A4 k=11 f=4840.00Hz
        5'd11: pt_rate_v9 = 12'd1164;   // A4 k=12 f=5280.00Hz
        5'd12: pt_rate_v9 = 12'd809;   // A4 k=13 f=5720.00Hz
        5'd13: pt_rate_v9 = 12'd722;   // A4 k=14 f=6160.00Hz
        5'd14: pt_rate_v9 = 12'd1182;   // A4 k=15 f=6600.00Hz
        5'd15: pt_rate_v9 = 12'd1096;   // A4 k=16 f=7040.00Hz
        5'd17: pt_rate_v9 = 12'd1415;   // A4 k=18 f=7920.00Hz
        default: pt_rate_v9 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v9 = 16'd10194;   // A4 k=1 f=440.00Hz
        5'd1: pt_aatk_v9 = 16'd19941;   // A4 k=2 f=880.00Hz
        5'd2: pt_aatk_v9 = 16'd24024;   // A4 k=3 f=1320.00Hz
        5'd3: pt_aatk_v9 = 16'd23126;   // A4 k=4 f=1760.00Hz
        5'd4: pt_aatk_v9 = 16'd24590;   // A4 k=5 f=2200.00Hz
        5'd5: pt_aatk_v9 = 16'd23261;   // A4 k=6 f=2640.00Hz
        5'd6: pt_aatk_v9 = 16'd23011;   // A4 k=7 f=3080.00Hz
        5'd7: pt_aatk_v9 = 16'd24959;   // A4 k=8 f=3520.00Hz
        5'd8: pt_aatk_v9 = 16'd27033;   // A4 k=9 f=3960.00Hz
        5'd9: pt_aatk_v9 = 16'd23630;   // A4 k=10 f=4400.00Hz
        5'd10: pt_aatk_v9 = 16'd28889;   // A4 k=11 f=4840.00Hz
        5'd11: pt_aatk_v9 = 16'd22662;   // A4 k=12 f=5280.00Hz
        5'd12: pt_aatk_v9 = 16'd26762;   // A4 k=13 f=5720.00Hz
        5'd13: pt_aatk_v9 = 16'd28627;   // A4 k=14 f=6160.00Hz
        5'd14: pt_aatk_v9 = 16'd24727;   // A4 k=15 f=6600.00Hz
        5'd15: pt_aatk_v9 = 16'd26134;   // A4 k=16 f=7040.00Hz
        5'd17: pt_aatk_v9 = 16'd24849;   // A4 k=18 f=7920.00Hz
        default: pt_aatk_v9 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v9(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v9 = 32'h134EFECA;   // A4 k=1 f=440.00Hz
        5'd1: pt_ph0_v9 = 32'h5E6CC8A1;   // A4 k=2 f=880.00Hz
        5'd2: pt_ph0_v9 = 32'h2869E8BD;   // A4 k=3 f=1320.00Hz
        5'd3: pt_ph0_v9 = 32'hBAB60242;   // A4 k=4 f=1760.00Hz
        5'd4: pt_ph0_v9 = 32'h2531989D;   // A4 k=5 f=2200.00Hz
        5'd5: pt_ph0_v9 = 32'hD264420C;   // A4 k=6 f=2640.00Hz
        5'd6: pt_ph0_v9 = 32'h1E980CDE;   // A4 k=7 f=3080.00Hz
        5'd7: pt_ph0_v9 = 32'hFF7BA8BF;   // A4 k=8 f=3520.00Hz
        5'd8: pt_ph0_v9 = 32'hC7EDBCA9;   // A4 k=9 f=3960.00Hz
        5'd9: pt_ph0_v9 = 32'h7F6C3635;   // A4 k=10 f=4400.00Hz
        5'd10: pt_ph0_v9 = 32'hCCF7B6E0;   // A4 k=11 f=4840.00Hz
        5'd11: pt_ph0_v9 = 32'h7F5ED555;   // A4 k=12 f=5280.00Hz
        5'd12: pt_ph0_v9 = 32'h1B70D24F;   // A4 k=13 f=5720.00Hz
        5'd13: pt_ph0_v9 = 32'h261F68B3;   // A4 k=14 f=6160.00Hz
        5'd14: pt_ph0_v9 = 32'hAA24CBD7;   // A4 k=15 f=6600.00Hz
        5'd15: pt_ph0_v9 = 32'h58987D78;   // A4 k=16 f=7040.00Hz
        5'd17: pt_ph0_v9 = 32'hB1DD8A83;   // A4 k=18 f=7920.00Hz
        default: pt_ph0_v9 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v10 = 32'h0271ACA6;   // A#4 k=1 f=466.16Hz
        5'd1: pt_inc_v10 = 32'h04E3594C;   // A#4 k=2 f=932.33Hz
        5'd2: pt_inc_v10 = 32'h075505F2;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_inc_v10 = 32'h09C6B298;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_inc_v10 = 32'h0C385F3E;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_inc_v10 = 32'h0EAA0BE5;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_inc_v10 = 32'h111BB88B;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_inc_v10 = 32'h138D6531;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_inc_v10 = 32'h15FF11D7;   // A#4 k=9 f=4195.47Hz
        5'd10: pt_inc_v10 = 32'h1AE26B23;   // A#4 k=11 f=5127.80Hz
        default: pt_inc_v10 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v10 = 16'd7316;   // A#4 k=1 f=466.16Hz
        5'd1: pt_env0_v10 = 16'd486;   // A#4 k=2 f=932.33Hz
        5'd2: pt_env0_v10 = 16'd397;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_env0_v10 = 16'd803;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_env0_v10 = 16'd162;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_env0_v10 = 16'd117;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_env0_v10 = 16'd14;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_env0_v10 = 16'd82;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_env0_v10 = 16'd54;   // A#4 k=9 f=4195.47Hz
        5'd10: pt_env0_v10 = 16'd12;   // A#4 k=11 f=5127.80Hz
        default: pt_env0_v10 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v10 = 12'd389;   // A#4 k=1 f=466.16Hz
        5'd1: pt_rate_v10 = 12'd230;   // A#4 k=2 f=932.33Hz
        5'd2: pt_rate_v10 = 12'd414;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_rate_v10 = 12'd755;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_rate_v10 = 12'd474;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_rate_v10 = 12'd417;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_rate_v10 = 12'd527;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_rate_v10 = 12'd878;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_rate_v10 = 12'd876;   // A#4 k=9 f=4195.47Hz
        5'd10: pt_rate_v10 = 12'd766;   // A#4 k=11 f=5127.80Hz
        default: pt_rate_v10 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v10 = 16'd12244;   // A#4 k=1 f=466.16Hz
        5'd1: pt_aatk_v10 = 16'd18872;   // A#4 k=2 f=932.33Hz
        5'd2: pt_aatk_v10 = 16'd18587;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_aatk_v10 = 16'd16958;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_aatk_v10 = 16'd21797;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_aatk_v10 = 16'd24335;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_aatk_v10 = 16'd24067;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_aatk_v10 = 16'd21331;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_aatk_v10 = 16'd22413;   // A#4 k=9 f=4195.47Hz
        5'd10: pt_aatk_v10 = 16'd25496;   // A#4 k=11 f=5127.80Hz
        default: pt_aatk_v10 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v10(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v10 = 32'h1130B265;   // A#4 k=1 f=466.16Hz
        5'd1: pt_ph0_v10 = 32'hE8FE2ABB;   // A#4 k=2 f=932.33Hz
        5'd2: pt_ph0_v10 = 32'h9882D18F;   // A#4 k=3 f=1398.49Hz
        5'd3: pt_ph0_v10 = 32'h94D94A16;   // A#4 k=4 f=1864.66Hz
        5'd4: pt_ph0_v10 = 32'h0EE14FBB;   // A#4 k=5 f=2330.82Hz
        5'd5: pt_ph0_v10 = 32'hC5D1BA30;   // A#4 k=6 f=2796.98Hz
        5'd6: pt_ph0_v10 = 32'hA06FAC1B;   // A#4 k=7 f=3263.15Hz
        5'd7: pt_ph0_v10 = 32'hE8703B4D;   // A#4 k=8 f=3729.31Hz
        5'd8: pt_ph0_v10 = 32'h0C2474E1;   // A#4 k=9 f=4195.47Hz
        5'd10: pt_ph0_v10 = 32'hD5BAA21D;   // A#4 k=11 f=5127.80Hz
        default: pt_ph0_v10 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v11 = 32'h0296E103;   // B4 k=1 f=493.88Hz
        5'd1: pt_inc_v11 = 32'h052DC205;   // B4 k=2 f=987.77Hz
        5'd2: pt_inc_v11 = 32'h07C4A308;   // B4 k=3 f=1481.65Hz
        5'd3: pt_inc_v11 = 32'h0A5B840A;   // B4 k=4 f=1975.53Hz
        5'd4: pt_inc_v11 = 32'h0CF2650D;   // B4 k=5 f=2469.42Hz
        5'd5: pt_inc_v11 = 32'h0F894610;   // B4 k=6 f=2963.30Hz
        5'd6: pt_inc_v11 = 32'h12202712;   // B4 k=7 f=3457.18Hz
        5'd7: pt_inc_v11 = 32'h14B70815;   // B4 k=8 f=3951.07Hz
        5'd8: pt_inc_v11 = 32'h174DE917;   // B4 k=9 f=4444.95Hz
        5'd9: pt_inc_v11 = 32'h19E4CA1A;   // B4 k=10 f=4938.83Hz
        5'd10: pt_inc_v11 = 32'h1C7BAB1D;   // B4 k=11 f=5432.72Hz
        default: pt_inc_v11 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v11 = 16'd4712;   // B4 k=1 f=493.88Hz
        5'd1: pt_env0_v11 = 16'd1816;   // B4 k=2 f=987.77Hz
        5'd2: pt_env0_v11 = 16'd880;   // B4 k=3 f=1481.65Hz
        5'd3: pt_env0_v11 = 16'd1152;   // B4 k=4 f=1975.53Hz
        5'd4: pt_env0_v11 = 16'd280;   // B4 k=5 f=2469.42Hz
        5'd5: pt_env0_v11 = 16'd56;   // B4 k=6 f=2963.30Hz
        5'd6: pt_env0_v11 = 16'd33;   // B4 k=7 f=3457.18Hz
        5'd7: pt_env0_v11 = 16'd11;   // B4 k=8 f=3951.07Hz
        5'd8: pt_env0_v11 = 16'd50;   // B4 k=9 f=4444.95Hz
        5'd9: pt_env0_v11 = 16'd23;   // B4 k=10 f=4938.83Hz
        5'd10: pt_env0_v11 = 16'd37;   // B4 k=11 f=5432.72Hz
        default: pt_env0_v11 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v11 = 12'd238;   // B4 k=1 f=493.88Hz
        5'd1: pt_rate_v11 = 12'd320;   // B4 k=2 f=987.77Hz
        5'd2: pt_rate_v11 = 12'd443;   // B4 k=3 f=1481.65Hz
        5'd3: pt_rate_v11 = 12'd545;   // B4 k=4 f=1975.53Hz
        5'd4: pt_rate_v11 = 12'd671;   // B4 k=5 f=2469.42Hz
        5'd5: pt_rate_v11 = 12'd397;   // B4 k=6 f=2963.30Hz
        5'd6: pt_rate_v11 = 12'd558;   // B4 k=7 f=3457.18Hz
        5'd7: pt_rate_v11 = 12'd407;   // B4 k=8 f=3951.07Hz
        5'd8: pt_rate_v11 = 12'd661;   // B4 k=9 f=4444.95Hz
        5'd9: pt_rate_v11 = 12'd888;   // B4 k=10 f=4938.83Hz
        5'd10: pt_rate_v11 = 12'd1051;   // B4 k=11 f=5432.72Hz
        default: pt_rate_v11 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v11 = 16'd14314;   // B4 k=1 f=493.88Hz
        5'd1: pt_aatk_v11 = 16'd17169;   // B4 k=2 f=987.77Hz
        5'd2: pt_aatk_v11 = 16'd18191;   // B4 k=3 f=1481.65Hz
        5'd3: pt_aatk_v11 = 16'd19055;   // B4 k=4 f=1975.53Hz
        5'd4: pt_aatk_v11 = 16'd19411;   // B4 k=5 f=2469.42Hz
        5'd5: pt_aatk_v11 = 16'd24693;   // B4 k=6 f=2963.30Hz
        5'd6: pt_aatk_v11 = 16'd23632;   // B4 k=7 f=3457.18Hz
        5'd7: pt_aatk_v11 = 16'd27387;   // B4 k=8 f=3951.07Hz
        5'd8: pt_aatk_v11 = 16'd24719;   // B4 k=9 f=4444.95Hz
        5'd9: pt_aatk_v11 = 16'd23279;   // B4 k=10 f=4938.83Hz
        5'd10: pt_aatk_v11 = 16'd22736;   // B4 k=11 f=5432.72Hz
        default: pt_aatk_v11 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v11(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v11 = 32'hBED11EC1;   // B4 k=1 f=493.88Hz
        5'd1: pt_ph0_v11 = 32'h3C2778E5;   // B4 k=2 f=987.77Hz
        5'd2: pt_ph0_v11 = 32'hB44D9E78;   // B4 k=3 f=1481.65Hz
        5'd3: pt_ph0_v11 = 32'hF7D12A99;   // B4 k=4 f=1975.53Hz
        5'd4: pt_ph0_v11 = 32'h82CE18D8;   // B4 k=5 f=2469.42Hz
        5'd5: pt_ph0_v11 = 32'h7B723E72;   // B4 k=6 f=2963.30Hz
        5'd6: pt_ph0_v11 = 32'hAB3065AC;   // B4 k=7 f=3457.18Hz
        5'd7: pt_ph0_v11 = 32'h57337BAE;   // B4 k=8 f=3951.07Hz
        5'd8: pt_ph0_v11 = 32'h3092562D;   // B4 k=9 f=4444.95Hz
        5'd9: pt_ph0_v11 = 32'h30AEABC6;   // B4 k=10 f=4938.83Hz
        5'd10: pt_ph0_v11 = 32'h5F153C8D;   // B4 k=11 f=5432.72Hz
        default: pt_ph0_v11 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v12 = 32'h02BE4BB8;   // C5 k=1 f=523.25Hz
        5'd1: pt_inc_v12 = 32'h057C9770;   // C5 k=2 f=1046.50Hz
        5'd2: pt_inc_v12 = 32'h083AE329;   // C5 k=3 f=1569.75Hz
        5'd3: pt_inc_v12 = 32'h0AF92EE1;   // C5 k=4 f=2093.00Hz
        5'd4: pt_inc_v12 = 32'h0DB77A99;   // C5 k=5 f=2616.26Hz
        5'd5: pt_inc_v12 = 32'h1075C651;   // C5 k=6 f=3139.51Hz
        5'd6: pt_inc_v12 = 32'h13341209;   // C5 k=7 f=3662.76Hz
        5'd7: pt_inc_v12 = 32'h15F25DC1;   // C5 k=8 f=4186.01Hz
        5'd8: pt_inc_v12 = 32'h18B0A97A;   // C5 k=9 f=4709.26Hz
        5'd9: pt_inc_v12 = 32'h1B6EF532;   // C5 k=10 f=5232.51Hz
        5'd10: pt_inc_v12 = 32'h1E2D40EA;   // C5 k=11 f=5755.76Hz
        5'd12: pt_inc_v12 = 32'h23A9D85A;   // C5 k=13 f=6802.26Hz
        default: pt_inc_v12 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v12 = 16'd3605;   // C5 k=1 f=523.25Hz
        5'd1: pt_env0_v12 = 16'd2066;   // C5 k=2 f=1046.50Hz
        5'd2: pt_env0_v12 = 16'd815;   // C5 k=3 f=1569.75Hz
        5'd3: pt_env0_v12 = 16'd1045;   // C5 k=4 f=2093.00Hz
        5'd4: pt_env0_v12 = 16'd218;   // C5 k=5 f=2616.26Hz
        5'd5: pt_env0_v12 = 16'd802;   // C5 k=6 f=3139.51Hz
        5'd6: pt_env0_v12 = 16'd135;   // C5 k=7 f=3662.76Hz
        5'd7: pt_env0_v12 = 16'd46;   // C5 k=8 f=4186.01Hz
        5'd8: pt_env0_v12 = 16'd87;   // C5 k=9 f=4709.26Hz
        5'd9: pt_env0_v12 = 16'd36;   // C5 k=10 f=5232.51Hz
        5'd10: pt_env0_v12 = 16'd28;   // C5 k=11 f=5755.76Hz
        5'd12: pt_env0_v12 = 16'd12;   // C5 k=13 f=6802.26Hz
        default: pt_env0_v12 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v12 = 12'd167;   // C5 k=1 f=523.25Hz
        5'd1: pt_rate_v12 = 12'd273;   // C5 k=2 f=1046.50Hz
        5'd2: pt_rate_v12 = 12'd265;   // C5 k=3 f=1569.75Hz
        5'd3: pt_rate_v12 = 12'd438;   // C5 k=4 f=2093.00Hz
        5'd4: pt_rate_v12 = 12'd301;   // C5 k=5 f=2616.26Hz
        5'd5: pt_rate_v12 = 12'd684;   // C5 k=6 f=3139.51Hz
        5'd6: pt_rate_v12 = 12'd943;   // C5 k=7 f=3662.76Hz
        5'd7: pt_rate_v12 = 12'd454;   // C5 k=8 f=4186.01Hz
        5'd8: pt_rate_v12 = 12'd692;   // C5 k=9 f=4709.26Hz
        5'd9: pt_rate_v12 = 12'd934;   // C5 k=10 f=5232.51Hz
        5'd10: pt_rate_v12 = 12'd983;   // C5 k=11 f=5755.76Hz
        5'd12: pt_rate_v12 = 12'd1588;   // C5 k=13 f=6802.26Hz
        default: pt_rate_v12 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v12 = 16'd15798;   // C5 k=1 f=523.25Hz
        5'd1: pt_aatk_v12 = 16'd17992;   // C5 k=2 f=1046.50Hz
        5'd2: pt_aatk_v12 = 16'd21200;   // C5 k=3 f=1569.75Hz
        5'd3: pt_aatk_v12 = 16'd20456;   // C5 k=4 f=2093.00Hz
        5'd4: pt_aatk_v12 = 16'd24886;   // C5 k=5 f=2616.26Hz
        5'd5: pt_aatk_v12 = 16'd20757;   // C5 k=6 f=3139.51Hz
        5'd6: pt_aatk_v12 = 16'd19650;   // C5 k=7 f=3662.76Hz
        5'd7: pt_aatk_v12 = 16'd26547;   // C5 k=8 f=4186.01Hz
        5'd8: pt_aatk_v12 = 16'd24339;   // C5 k=9 f=4709.26Hz
        5'd9: pt_aatk_v12 = 16'd22857;   // C5 k=10 f=5232.51Hz
        5'd10: pt_aatk_v12 = 16'd23323;   // C5 k=11 f=5755.76Hz
        5'd12: pt_aatk_v12 = 16'd20596;   // C5 k=13 f=6802.26Hz
        default: pt_aatk_v12 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v12(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v12 = 32'hE818F92F;   // C5 k=1 f=523.25Hz
        5'd1: pt_ph0_v12 = 32'h10913491;   // C5 k=2 f=1046.50Hz
        5'd2: pt_ph0_v12 = 32'hF662FD90;   // C5 k=3 f=1569.75Hz
        5'd3: pt_ph0_v12 = 32'h93C58678;   // C5 k=4 f=2093.00Hz
        5'd4: pt_ph0_v12 = 32'h4258685D;   // C5 k=5 f=2616.26Hz
        5'd5: pt_ph0_v12 = 32'hA52E1174;   // C5 k=6 f=3139.51Hz
        5'd6: pt_ph0_v12 = 32'h8714FC74;   // C5 k=7 f=3662.76Hz
        5'd7: pt_ph0_v12 = 32'h0BD47719;   // C5 k=8 f=4186.01Hz
        5'd8: pt_ph0_v12 = 32'h23D5A5C2;   // C5 k=9 f=4709.26Hz
        5'd9: pt_ph0_v12 = 32'h7AD860F4;   // C5 k=10 f=5232.51Hz
        5'd10: pt_ph0_v12 = 32'hAE1DA977;   // C5 k=11 f=5755.76Hz
        5'd12: pt_ph0_v12 = 32'h7D5BD92E;   // C5 k=13 f=6802.26Hz
        default: pt_ph0_v12 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v13 = 32'h02E80E74;   // C#5 k=1 f=554.37Hz
        5'd1: pt_inc_v13 = 32'h05D01CE8;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_inc_v13 = 32'h08B82B5C;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_inc_v13 = 32'h0BA039D0;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_inc_v13 = 32'h0E884845;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_inc_v13 = 32'h117056B9;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_inc_v13 = 32'h1458652D;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_inc_v13 = 32'h174073A1;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_inc_v13 = 32'h1A288215;   // C#5 k=9 f=4989.29Hz
        5'd10: pt_inc_v13 = 32'h1FF89EFD;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_inc_v13 = 32'h22E0AD71;   // C#5 k=12 f=6652.38Hz
        default: pt_inc_v13 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v13 = 16'd5524;   // C#5 k=1 f=554.37Hz
        5'd1: pt_env0_v13 = 16'd754;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_env0_v13 = 16'd229;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_env0_v13 = 16'd1143;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_env0_v13 = 16'd672;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_env0_v13 = 16'd112;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_env0_v13 = 16'd196;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_env0_v13 = 16'd70;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_env0_v13 = 16'd210;   // C#5 k=9 f=4989.29Hz
        5'd10: pt_env0_v13 = 16'd11;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_env0_v13 = 16'd12;   // C#5 k=12 f=6652.38Hz
        default: pt_env0_v13 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v13 = 12'd210;   // C#5 k=1 f=554.37Hz
        5'd1: pt_rate_v13 = 12'd174;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_rate_v13 = 12'd551;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_rate_v13 = 12'd407;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_rate_v13 = 12'd595;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_rate_v13 = 12'd632;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_rate_v13 = 12'd567;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_rate_v13 = 12'd741;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_rate_v13 = 12'd795;   // C#5 k=9 f=4989.29Hz
        5'd10: pt_rate_v13 = 12'd903;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_rate_v13 = 12'd1410;   // C#5 k=12 f=6652.38Hz
        default: pt_rate_v13 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v13 = 16'd14845;   // C#5 k=1 f=554.37Hz
        5'd1: pt_aatk_v13 = 16'd20322;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_aatk_v13 = 16'd16908;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_aatk_v13 = 16'd20918;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_aatk_v13 = 16'd20229;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_aatk_v13 = 16'd21328;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_aatk_v13 = 16'd23513;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_aatk_v13 = 16'd22681;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_aatk_v13 = 16'd23209;   // C#5 k=9 f=4989.29Hz
        5'd10: pt_aatk_v13 = 16'd24058;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_aatk_v13 = 16'd20948;   // C#5 k=12 f=6652.38Hz
        default: pt_aatk_v13 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v13(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v13 = 32'hC9BD5831;   // C#5 k=1 f=554.37Hz
        5'd1: pt_ph0_v13 = 32'h35D264EC;   // C#5 k=2 f=1108.73Hz
        5'd2: pt_ph0_v13 = 32'h50B4D12B;   // C#5 k=3 f=1663.10Hz
        5'd3: pt_ph0_v13 = 32'h98AB5803;   // C#5 k=4 f=2217.46Hz
        5'd4: pt_ph0_v13 = 32'h86C37B16;   // C#5 k=5 f=2771.83Hz
        5'd5: pt_ph0_v13 = 32'hDD983706;   // C#5 k=6 f=3326.19Hz
        5'd6: pt_ph0_v13 = 32'hB46BCDFA;   // C#5 k=7 f=3880.56Hz
        5'd7: pt_ph0_v13 = 32'h77498910;   // C#5 k=8 f=4434.92Hz
        5'd8: pt_ph0_v13 = 32'h8B1EEE85;   // C#5 k=9 f=4989.29Hz
        5'd10: pt_ph0_v13 = 32'h8F02D9A2;   // C#5 k=11 f=6098.02Hz
        5'd11: pt_ph0_v13 = 32'h52E88499;   // C#5 k=12 f=6652.38Hz
        default: pt_ph0_v13 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v14 = 32'h03144CE4;   // D5 k=1 f=587.33Hz
        5'd1: pt_inc_v14 = 32'h062899C9;   // D5 k=2 f=1174.66Hz
        5'd2: pt_inc_v14 = 32'h093CE6AD;   // D5 k=3 f=1761.99Hz
        5'd3: pt_inc_v14 = 32'h0C513391;   // D5 k=4 f=2349.32Hz
        5'd4: pt_inc_v14 = 32'h0F658076;   // D5 k=5 f=2936.65Hz
        5'd5: pt_inc_v14 = 32'h1279CD5A;   // D5 k=6 f=3523.98Hz
        5'd6: pt_inc_v14 = 32'h158E1A3E;   // D5 k=7 f=4111.31Hz
        5'd7: pt_inc_v14 = 32'h18A26723;   // D5 k=8 f=4698.64Hz
        5'd8: pt_inc_v14 = 32'h1BB6B407;   // D5 k=9 f=5285.97Hz
        5'd9: pt_inc_v14 = 32'h1ECB00EB;   // D5 k=10 f=5873.30Hz
        5'd10: pt_inc_v14 = 32'h21DF4DD0;   // D5 k=11 f=6460.62Hz
        5'd11: pt_inc_v14 = 32'h24F39AB4;   // D5 k=12 f=7047.95Hz
        default: pt_inc_v14 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v14 = 16'd3571;   // D5 k=1 f=587.33Hz
        5'd1: pt_env0_v14 = 16'd955;   // D5 k=2 f=1174.66Hz
        5'd2: pt_env0_v14 = 16'd1443;   // D5 k=3 f=1761.99Hz
        5'd3: pt_env0_v14 = 16'd1080;   // D5 k=4 f=2349.32Hz
        5'd4: pt_env0_v14 = 16'd657;   // D5 k=5 f=2936.65Hz
        5'd5: pt_env0_v14 = 16'd510;   // D5 k=6 f=3523.98Hz
        5'd6: pt_env0_v14 = 16'd177;   // D5 k=7 f=4111.31Hz
        5'd7: pt_env0_v14 = 16'd32;   // D5 k=8 f=4698.64Hz
        5'd8: pt_env0_v14 = 16'd639;   // D5 k=9 f=5285.97Hz
        5'd9: pt_env0_v14 = 16'd25;   // D5 k=10 f=5873.30Hz
        5'd10: pt_env0_v14 = 16'd58;   // D5 k=11 f=6460.62Hz
        5'd11: pt_env0_v14 = 16'd14;   // D5 k=12 f=7047.95Hz
        default: pt_env0_v14 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v14 = 12'd187;   // D5 k=1 f=587.33Hz
        5'd1: pt_rate_v14 = 12'd342;   // D5 k=2 f=1174.66Hz
        5'd2: pt_rate_v14 = 12'd439;   // D5 k=3 f=1761.99Hz
        5'd3: pt_rate_v14 = 12'd685;   // D5 k=4 f=2349.32Hz
        5'd4: pt_rate_v14 = 12'd556;   // D5 k=5 f=2936.65Hz
        5'd5: pt_rate_v14 = 12'd863;   // D5 k=6 f=3523.98Hz
        5'd6: pt_rate_v14 = 12'd623;   // D5 k=7 f=4111.31Hz
        5'd7: pt_rate_v14 = 12'd405;   // D5 k=8 f=4698.64Hz
        5'd8: pt_rate_v14 = 12'd954;   // D5 k=9 f=5285.97Hz
        5'd9: pt_rate_v14 = 12'd1256;   // D5 k=10 f=5873.30Hz
        5'd10: pt_rate_v14 = 12'd1145;   // D5 k=11 f=6460.62Hz
        5'd11: pt_rate_v14 = 12'd602;   // D5 k=12 f=7047.95Hz
        default: pt_rate_v14 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v14 = 16'd15325;   // D5 k=1 f=587.33Hz
        5'd1: pt_aatk_v14 = 16'd16822;   // D5 k=2 f=1174.66Hz
        5'd2: pt_aatk_v14 = 16'd18246;   // D5 k=3 f=1761.99Hz
        5'd3: pt_aatk_v14 = 16'd17588;   // D5 k=4 f=2349.32Hz
        5'd4: pt_aatk_v14 = 16'd20693;   // D5 k=5 f=2936.65Hz
        5'd5: pt_aatk_v14 = 16'd19066;   // D5 k=6 f=3523.98Hz
        5'd6: pt_aatk_v14 = 16'd22799;   // D5 k=7 f=4111.31Hz
        5'd7: pt_aatk_v14 = 16'd27442;   // D5 k=8 f=4698.64Hz
        5'd8: pt_aatk_v14 = 16'd21707;   // D5 k=9 f=5285.97Hz
        5'd9: pt_aatk_v14 = 16'd20351;   // D5 k=10 f=5873.30Hz
        5'd10: pt_aatk_v14 = 16'd21991;   // D5 k=11 f=6460.62Hz
        5'd11: pt_aatk_v14 = 16'd28546;   // D5 k=12 f=7047.95Hz
        default: pt_aatk_v14 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v14(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v14 = 32'h0D0B3124;   // D5 k=1 f=587.33Hz
        5'd1: pt_ph0_v14 = 32'h0EDF12D3;   // D5 k=2 f=1174.66Hz
        5'd2: pt_ph0_v14 = 32'h7C2596B1;   // D5 k=3 f=1761.99Hz
        5'd3: pt_ph0_v14 = 32'h1089E8C8;   // D5 k=4 f=2349.32Hz
        5'd4: pt_ph0_v14 = 32'h9F8F3E00;   // D5 k=5 f=2936.65Hz
        5'd5: pt_ph0_v14 = 32'h71AF46C7;   // D5 k=6 f=3523.98Hz
        5'd6: pt_ph0_v14 = 32'hB78AA5FC;   // D5 k=7 f=4111.31Hz
        5'd7: pt_ph0_v14 = 32'h859FD8A6;   // D5 k=8 f=4698.64Hz
        5'd8: pt_ph0_v14 = 32'hAFEFDB83;   // D5 k=9 f=5285.97Hz
        5'd9: pt_ph0_v14 = 32'hC76DA84C;   // D5 k=10 f=5873.30Hz
        5'd10: pt_ph0_v14 = 32'h3EE63EBE;   // D5 k=11 f=6460.62Hz
        5'd11: pt_ph0_v14 = 32'hDF01C6E6;   // D5 k=12 f=7047.95Hz
        default: pt_ph0_v14 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v15 = 32'h03432CD6;   // D#5 k=1 f=622.25Hz
        5'd1: pt_inc_v15 = 32'h068659AC;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_inc_v15 = 32'h09C98681;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_inc_v15 = 32'h0D0CB357;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_inc_v15 = 32'h104FE02D;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_inc_v15 = 32'h13930D03;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_inc_v15 = 32'h16D639D9;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_inc_v15 = 32'h1A1966AE;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_inc_v15 = 32'h1D5C9384;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_inc_v15 = 32'h209FC05A;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_inc_v15 = 32'h23E2ED30;   // D#5 k=11 f=6844.79Hz
        default: pt_inc_v15 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v15 = 16'd1847;   // D#5 k=1 f=622.25Hz
        5'd1: pt_env0_v15 = 16'd3966;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_env0_v15 = 16'd1666;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_env0_v15 = 16'd537;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_env0_v15 = 16'd580;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_env0_v15 = 16'd386;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_env0_v15 = 16'd40;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_env0_v15 = 16'd58;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_env0_v15 = 16'd130;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_env0_v15 = 16'd16;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_env0_v15 = 16'd17;   // D#5 k=11 f=6844.79Hz
        default: pt_env0_v15 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v15 = 12'd202;   // D#5 k=1 f=622.25Hz
        5'd1: pt_rate_v15 = 12'd345;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_rate_v15 = 12'd674;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_rate_v15 = 12'd689;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_rate_v15 = 12'd584;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_rate_v15 = 12'd724;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_rate_v15 = 12'd670;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_rate_v15 = 12'd701;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_rate_v15 = 12'd924;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_rate_v15 = 12'd1621;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_rate_v15 = 12'd742;   // D#5 k=11 f=6844.79Hz
        default: pt_rate_v15 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v15 = 16'd14996;   // D#5 k=1 f=622.25Hz
        5'd1: pt_aatk_v15 = 16'd16770;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_aatk_v15 = 16'd15723;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_aatk_v15 = 16'd17546;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_aatk_v15 = 16'd20366;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_aatk_v15 = 16'd20341;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_aatk_v15 = 16'd22246;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_aatk_v15 = 16'd23119;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_aatk_v15 = 16'd21976;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_aatk_v15 = 16'd18202;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_aatk_v15 = 16'd25763;   // D#5 k=11 f=6844.79Hz
        default: pt_aatk_v15 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v15(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v15 = 32'h1C73D408;   // D#5 k=1 f=622.25Hz
        5'd1: pt_ph0_v15 = 32'hB8AE0951;   // D#5 k=2 f=1244.51Hz
        5'd2: pt_ph0_v15 = 32'h4906A7F3;   // D#5 k=3 f=1866.76Hz
        5'd3: pt_ph0_v15 = 32'h22E9A8EF;   // D#5 k=4 f=2489.02Hz
        5'd4: pt_ph0_v15 = 32'hE97C21B5;   // D#5 k=5 f=3111.27Hz
        5'd5: pt_ph0_v15 = 32'hC41C5510;   // D#5 k=6 f=3733.52Hz
        5'd6: pt_ph0_v15 = 32'h99703BAF;   // D#5 k=7 f=4355.78Hz
        5'd7: pt_ph0_v15 = 32'h5EB7010D;   // D#5 k=8 f=4978.03Hz
        5'd8: pt_ph0_v15 = 32'h6C493686;   // D#5 k=9 f=5600.29Hz
        5'd9: pt_ph0_v15 = 32'h19A3AA8A;   // D#5 k=10 f=6222.54Hz
        5'd10: pt_ph0_v15 = 32'hF2A94293;   // D#5 k=11 f=6844.79Hz
        default: pt_ph0_v15 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v16 = 32'h0374D655;   // E5 k=1 f=659.26Hz
        5'd1: pt_inc_v16 = 32'h06E9ACAA;   // E5 k=2 f=1318.51Hz
        5'd2: pt_inc_v16 = 32'h0A5E82FF;   // E5 k=3 f=1977.77Hz
        5'd3: pt_inc_v16 = 32'h0DD35954;   // E5 k=4 f=2637.02Hz
        5'd4: pt_inc_v16 = 32'h11482FA9;   // E5 k=5 f=3296.28Hz
        5'd5: pt_inc_v16 = 32'h14BD05FE;   // E5 k=6 f=3955.53Hz
        5'd6: pt_inc_v16 = 32'h1831DC53;   // E5 k=7 f=4614.79Hz
        5'd7: pt_inc_v16 = 32'h1BA6B2A9;   // E5 k=8 f=5274.04Hz
        5'd8: pt_inc_v16 = 32'h1F1B88FE;   // E5 k=9 f=5933.30Hz
        5'd9: pt_inc_v16 = 32'h22905F53;   // E5 k=10 f=6592.55Hz
        5'd10: pt_inc_v16 = 32'h260535A8;   // E5 k=11 f=7251.81Hz
        default: pt_inc_v16 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v16 = 16'd3218;   // E5 k=1 f=659.26Hz
        5'd1: pt_env0_v16 = 16'd3543;   // E5 k=2 f=1318.51Hz
        5'd2: pt_env0_v16 = 16'd1341;   // E5 k=3 f=1977.77Hz
        5'd3: pt_env0_v16 = 16'd149;   // E5 k=4 f=2637.02Hz
        5'd4: pt_env0_v16 = 16'd580;   // E5 k=5 f=3296.28Hz
        5'd5: pt_env0_v16 = 16'd85;   // E5 k=6 f=3955.53Hz
        5'd6: pt_env0_v16 = 16'd64;   // E5 k=7 f=4614.79Hz
        5'd7: pt_env0_v16 = 16'd43;   // E5 k=8 f=5274.04Hz
        5'd8: pt_env0_v16 = 16'd71;   // E5 k=9 f=5933.30Hz
        5'd9: pt_env0_v16 = 16'd13;   // E5 k=10 f=6592.55Hz
        5'd10: pt_env0_v16 = 16'd12;   // E5 k=11 f=7251.81Hz
        default: pt_env0_v16 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v16 = 12'd292;   // E5 k=1 f=659.26Hz
        5'd1: pt_rate_v16 = 12'd296;   // E5 k=2 f=1318.51Hz
        5'd2: pt_rate_v16 = 12'd552;   // E5 k=3 f=1977.77Hz
        5'd3: pt_rate_v16 = 12'd496;   // E5 k=4 f=2637.02Hz
        5'd4: pt_rate_v16 = 12'd521;   // E5 k=5 f=3296.28Hz
        5'd5: pt_rate_v16 = 12'd745;   // E5 k=6 f=3955.53Hz
        5'd6: pt_rate_v16 = 12'd662;   // E5 k=7 f=4614.79Hz
        5'd7: pt_rate_v16 = 12'd536;   // E5 k=8 f=5274.04Hz
        5'd8: pt_rate_v16 = 12'd910;   // E5 k=9 f=5933.30Hz
        5'd9: pt_rate_v16 = 12'd856;   // E5 k=10 f=6592.55Hz
        5'd10: pt_rate_v16 = 12'd1103;   // E5 k=11 f=7251.81Hz
        default: pt_rate_v16 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v16 = 16'd13457;   // E5 k=1 f=659.26Hz
        5'd1: pt_aatk_v16 = 16'd17572;   // E5 k=2 f=1318.51Hz
        5'd2: pt_aatk_v16 = 16'd16900;   // E5 k=3 f=1977.77Hz
        5'd3: pt_aatk_v16 = 16'd19661;   // E5 k=4 f=2637.02Hz
        5'd4: pt_aatk_v16 = 16'd21140;   // E5 k=5 f=3296.28Hz
        5'd5: pt_aatk_v16 = 16'd20136;   // E5 k=6 f=3955.53Hz
        5'd6: pt_aatk_v16 = 16'd22334;   // E5 k=7 f=4614.79Hz
        5'd7: pt_aatk_v16 = 16'd25234;   // E5 k=8 f=5274.04Hz
        5'd8: pt_aatk_v16 = 16'd22098;   // E5 k=9 f=5933.30Hz
        5'd9: pt_aatk_v16 = 16'd23588;   // E5 k=10 f=6592.55Hz
        5'd10: pt_aatk_v16 = 16'd22321;   // E5 k=11 f=7251.81Hz
        default: pt_aatk_v16 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v16(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v16 = 32'h8592B22E;   // E5 k=1 f=659.26Hz
        5'd1: pt_ph0_v16 = 32'hA9346365;   // E5 k=2 f=1318.51Hz
        5'd2: pt_ph0_v16 = 32'h8E42E8E9;   // E5 k=3 f=1977.77Hz
        5'd3: pt_ph0_v16 = 32'hB8AB8986;   // E5 k=4 f=2637.02Hz
        5'd4: pt_ph0_v16 = 32'hFAFE842B;   // E5 k=5 f=3296.28Hz
        5'd5: pt_ph0_v16 = 32'h6505D3D6;   // E5 k=6 f=3955.53Hz
        5'd6: pt_ph0_v16 = 32'h3090F149;   // E5 k=7 f=4614.79Hz
        5'd7: pt_ph0_v16 = 32'hF98104B5;   // E5 k=8 f=5274.04Hz
        5'd8: pt_ph0_v16 = 32'hFBEECFFE;   // E5 k=9 f=5933.30Hz
        5'd9: pt_ph0_v16 = 32'h6032C036;   // E5 k=10 f=6592.55Hz
        5'd10: pt_ph0_v16 = 32'h3EB799AC;   // E5 k=11 f=7251.81Hz
        default: pt_ph0_v16 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v17 = 32'h03A973D0;   // F5 k=1 f=698.46Hz
        5'd1: pt_inc_v17 = 32'h0752E7A0;   // F5 k=2 f=1396.91Hz
        5'd2: pt_inc_v17 = 32'h0AFC5B71;   // F5 k=3 f=2095.37Hz
        5'd3: pt_inc_v17 = 32'h0EA5CF41;   // F5 k=4 f=2793.83Hz
        5'd4: pt_inc_v17 = 32'h124F4311;   // F5 k=5 f=3492.28Hz
        5'd5: pt_inc_v17 = 32'h15F8B6E1;   // F5 k=6 f=4190.74Hz
        5'd6: pt_inc_v17 = 32'h19A22AB1;   // F5 k=7 f=4889.20Hz
        5'd7: pt_inc_v17 = 32'h1D4B9E82;   // F5 k=8 f=5587.65Hz
        5'd8: pt_inc_v17 = 32'h20F51252;   // F5 k=9 f=6286.11Hz
        default: pt_inc_v17 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v17 = 16'd2080;   // F5 k=1 f=698.46Hz
        5'd1: pt_env0_v17 = 16'd5943;   // F5 k=2 f=1396.91Hz
        5'd2: pt_env0_v17 = 16'd254;   // F5 k=3 f=2095.37Hz
        5'd3: pt_env0_v17 = 16'd624;   // F5 k=4 f=2793.83Hz
        5'd4: pt_env0_v17 = 16'd349;   // F5 k=5 f=3492.28Hz
        5'd5: pt_env0_v17 = 16'd95;   // F5 k=6 f=4190.74Hz
        5'd6: pt_env0_v17 = 16'd20;   // F5 k=7 f=4889.20Hz
        5'd7: pt_env0_v17 = 16'd25;   // F5 k=8 f=5587.65Hz
        5'd8: pt_env0_v17 = 16'd69;   // F5 k=9 f=6286.11Hz
        default: pt_env0_v17 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v17 = 12'd366;   // F5 k=1 f=698.46Hz
        5'd1: pt_rate_v17 = 12'd453;   // F5 k=2 f=1396.91Hz
        5'd2: pt_rate_v17 = 12'd564;   // F5 k=3 f=2095.37Hz
        5'd3: pt_rate_v17 = 12'd768;   // F5 k=4 f=2793.83Hz
        5'd4: pt_rate_v17 = 12'd826;   // F5 k=5 f=3492.28Hz
        5'd5: pt_rate_v17 = 12'd893;   // F5 k=6 f=4190.74Hz
        5'd6: pt_rate_v17 = 12'd391;   // F5 k=7 f=4889.20Hz
        5'd7: pt_rate_v17 = 12'd600;   // F5 k=8 f=5587.65Hz
        5'd8: pt_rate_v17 = 12'd1041;   // F5 k=9 f=6286.11Hz
        default: pt_rate_v17 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v17 = 16'd12492;   // F5 k=1 f=698.46Hz
        5'd1: pt_aatk_v17 = 16'd15360;   // F5 k=2 f=1396.91Hz
        5'd2: pt_aatk_v17 = 16'd16770;   // F5 k=3 f=2095.37Hz
        5'd3: pt_aatk_v17 = 16'd16856;   // F5 k=4 f=2793.83Hz
        5'd4: pt_aatk_v17 = 16'd17980;   // F5 k=5 f=3492.28Hz
        5'd5: pt_aatk_v17 = 16'd18822;   // F5 k=6 f=4190.74Hz
        5'd6: pt_aatk_v17 = 16'd26328;   // F5 k=7 f=4889.20Hz
        5'd7: pt_aatk_v17 = 16'd24340;   // F5 k=8 f=5587.65Hz
        5'd8: pt_aatk_v17 = 16'd20994;   // F5 k=9 f=6286.11Hz
        default: pt_aatk_v17 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v17(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v17 = 32'h7DCD92CD;   // F5 k=1 f=698.46Hz
        5'd1: pt_ph0_v17 = 32'h3D1EF5E7;   // F5 k=2 f=1396.91Hz
        5'd2: pt_ph0_v17 = 32'h97EEE2F6;   // F5 k=3 f=2095.37Hz
        5'd3: pt_ph0_v17 = 32'h3DB0E2EE;   // F5 k=4 f=2793.83Hz
        5'd4: pt_ph0_v17 = 32'h1C4B7118;   // F5 k=5 f=3492.28Hz
        5'd5: pt_ph0_v17 = 32'h3F614DAC;   // F5 k=6 f=4190.74Hz
        5'd6: pt_ph0_v17 = 32'hCC4C1E06;   // F5 k=7 f=4889.20Hz
        5'd7: pt_ph0_v17 = 32'hBE13C1C0;   // F5 k=8 f=5587.65Hz
        5'd8: pt_ph0_v17 = 32'h035FF875;   // F5 k=9 f=6286.11Hz
        default: pt_ph0_v17 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v18 = 32'h03E1323B;   // F#5 k=1 f=739.99Hz
        5'd1: pt_inc_v18 = 32'h07C26476;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_inc_v18 = 32'h0BA396B2;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_inc_v18 = 32'h0F84C8ED;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_inc_v18 = 32'h1365FB28;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_inc_v18 = 32'h17472D63;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_inc_v18 = 32'h1B285F9E;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_inc_v18 = 32'h1F0991DA;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_inc_v18 = 32'h22EAC415;   // F#5 k=9 f=6659.90Hz
        default: pt_inc_v18 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v18 = 16'd5189;   // F#5 k=1 f=739.99Hz
        5'd1: pt_env0_v18 = 16'd3704;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_env0_v18 = 16'd256;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_env0_v18 = 16'd334;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_env0_v18 = 16'd63;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_env0_v18 = 16'd62;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_env0_v18 = 16'd69;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_env0_v18 = 16'd15;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_env0_v18 = 16'd80;   // F#5 k=9 f=6659.90Hz
        default: pt_env0_v18 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v18 = 12'd472;   // F#5 k=1 f=739.99Hz
        5'd1: pt_rate_v18 = 12'd567;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_rate_v18 = 12'd951;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_rate_v18 = 12'd531;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_rate_v18 = 12'd385;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_rate_v18 = 12'd881;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_rate_v18 = 12'd425;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_rate_v18 = 12'd526;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_rate_v18 = 12'd704;   // F#5 k=9 f=6659.90Hz
        default: pt_rate_v18 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v18 = 16'd11422;   // F#5 k=1 f=739.99Hz
        5'd1: pt_aatk_v18 = 16'd14187;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_aatk_v18 = 16'd13691;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_aatk_v18 = 16'd19228;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_aatk_v18 = 16'd23217;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_aatk_v18 = 16'd18919;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_aatk_v18 = 16'd25692;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_aatk_v18 = 16'd25376;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_aatk_v18 = 16'd24199;   // F#5 k=9 f=6659.90Hz
        default: pt_aatk_v18 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v18(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v18 = 32'h7675EDFD;   // F#5 k=1 f=739.99Hz
        5'd1: pt_ph0_v18 = 32'hB28F2B18;   // F#5 k=2 f=1479.98Hz
        5'd2: pt_ph0_v18 = 32'hAA6C1D2E;   // F#5 k=3 f=2219.97Hz
        5'd3: pt_ph0_v18 = 32'h10F0F08A;   // F#5 k=4 f=2959.96Hz
        5'd4: pt_ph0_v18 = 32'hD2D748BA;   // F#5 k=5 f=3699.94Hz
        5'd5: pt_ph0_v18 = 32'h43C2BE1A;   // F#5 k=6 f=4439.93Hz
        5'd6: pt_ph0_v18 = 32'h943F775A;   // F#5 k=7 f=5179.92Hz
        5'd7: pt_ph0_v18 = 32'h20554C30;   // F#5 k=8 f=5919.91Hz
        5'd8: pt_ph0_v18 = 32'hB3B213F9;   // F#5 k=9 f=6659.90Hz
        default: pt_ph0_v18 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v19 = 32'h041C4136;   // G5 k=1 f=783.99Hz
        5'd1: pt_inc_v19 = 32'h0838826D;   // G5 k=2 f=1567.98Hz
        5'd2: pt_inc_v19 = 32'h0C54C3A3;   // G5 k=3 f=2351.97Hz
        5'd3: pt_inc_v19 = 32'h107104DA;   // G5 k=4 f=3135.96Hz
        5'd4: pt_inc_v19 = 32'h148D4610;   // G5 k=5 f=3919.95Hz
        5'd5: pt_inc_v19 = 32'h18A98746;   // G5 k=6 f=4703.95Hz
        5'd6: pt_inc_v19 = 32'h1CC5C87D;   // G5 k=7 f=5487.94Hz
        5'd7: pt_inc_v19 = 32'h20E209B3;   // G5 k=8 f=6271.93Hz
        5'd8: pt_inc_v19 = 32'h24FE4AE9;   // G5 k=9 f=7055.92Hz
        default: pt_inc_v19 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v19 = 16'd3120;   // G5 k=1 f=783.99Hz
        5'd1: pt_env0_v19 = 16'd4324;   // G5 k=2 f=1567.98Hz
        5'd2: pt_env0_v19 = 16'd478;   // G5 k=3 f=2351.97Hz
        5'd3: pt_env0_v19 = 16'd789;   // G5 k=4 f=3135.96Hz
        5'd4: pt_env0_v19 = 16'd226;   // G5 k=5 f=3919.95Hz
        5'd5: pt_env0_v19 = 16'd189;   // G5 k=6 f=4703.95Hz
        5'd6: pt_env0_v19 = 16'd83;   // G5 k=7 f=5487.94Hz
        5'd7: pt_env0_v19 = 16'd17;   // G5 k=8 f=6271.93Hz
        5'd8: pt_env0_v19 = 16'd59;   // G5 k=9 f=7055.92Hz
        default: pt_env0_v19 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v19 = 12'd382;   // G5 k=1 f=783.99Hz
        5'd1: pt_rate_v19 = 12'd351;   // G5 k=2 f=1567.98Hz
        5'd2: pt_rate_v19 = 12'd266;   // G5 k=3 f=2351.97Hz
        5'd3: pt_rate_v19 = 12'd650;   // G5 k=4 f=3135.96Hz
        5'd4: pt_rate_v19 = 12'd935;   // G5 k=5 f=3919.95Hz
        5'd5: pt_rate_v19 = 12'd1026;   // G5 k=6 f=4703.95Hz
        5'd6: pt_rate_v19 = 12'd494;   // G5 k=7 f=5487.94Hz
        5'd7: pt_rate_v19 = 12'd547;   // G5 k=8 f=6271.93Hz
        5'd8: pt_rate_v19 = 12'd625;   // G5 k=9 f=7055.92Hz
        default: pt_rate_v19 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v19 = 16'd12315;   // G5 k=1 f=783.99Hz
        5'd1: pt_aatk_v19 = 16'd16679;   // G5 k=2 f=1567.98Hz
        5'd2: pt_aatk_v19 = 16'd21186;   // G5 k=3 f=2351.97Hz
        5'd3: pt_aatk_v19 = 16'd17922;   // G5 k=4 f=3135.96Hz
        5'd4: pt_aatk_v19 = 16'd17130;   // G5 k=5 f=3919.95Hz
        5'd5: pt_aatk_v19 = 16'd17812;   // G5 k=6 f=4703.95Hz
        5'd6: pt_aatk_v19 = 16'd24561;   // G5 k=7 f=5487.94Hz
        5'd7: pt_aatk_v19 = 16'd25071;   // G5 k=8 f=6271.93Hz
        5'd8: pt_aatk_v19 = 16'd25178;   // G5 k=9 f=7055.92Hz
        default: pt_aatk_v19 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v19(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v19 = 32'hC86428FF;   // G5 k=1 f=783.99Hz
        5'd1: pt_ph0_v19 = 32'hE2062602;   // G5 k=2 f=1567.98Hz
        5'd2: pt_ph0_v19 = 32'hFE08D941;   // G5 k=3 f=2351.97Hz
        5'd3: pt_ph0_v19 = 32'h4131F1F1;   // G5 k=4 f=3135.96Hz
        5'd4: pt_ph0_v19 = 32'h9EF220B6;   // G5 k=5 f=3919.95Hz
        5'd5: pt_ph0_v19 = 32'h86753544;   // G5 k=6 f=4703.95Hz
        5'd6: pt_ph0_v19 = 32'h3B69006A;   // G5 k=7 f=5487.94Hz
        5'd7: pt_ph0_v19 = 32'h77EDF6D8;   // G5 k=8 f=6271.93Hz
        5'd8: pt_ph0_v19 = 32'hEFE4DA23;   // G5 k=9 f=7055.92Hz
        default: pt_ph0_v19 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v20 = 32'h045AD337;   // G#5 k=1 f=830.61Hz
        5'd1: pt_inc_v20 = 32'h08B5A66E;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_inc_v20 = 32'h0D1079A5;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_inc_v20 = 32'h116B4CDC;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_inc_v20 = 32'h15C62013;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_inc_v20 = 32'h1A20F34A;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_inc_v20 = 32'h1E7BC681;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_inc_v20 = 32'h22D699B8;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_inc_v20 = 32'h27316CEF;   // G#5 k=9 f=7475.48Hz
        default: pt_inc_v20 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v20 = 16'd1969;   // G#5 k=1 f=830.61Hz
        5'd1: pt_env0_v20 = 16'd5206;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_env0_v20 = 16'd792;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_env0_v20 = 16'd902;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_env0_v20 = 16'd205;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_env0_v20 = 16'd56;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_env0_v20 = 16'd114;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_env0_v20 = 16'd76;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_env0_v20 = 16'd136;   // G#5 k=9 f=7475.48Hz
        default: pt_env0_v20 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v20 = 12'd293;   // G#5 k=1 f=830.61Hz
        5'd1: pt_rate_v20 = 12'd523;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_rate_v20 = 12'd395;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_rate_v20 = 12'd565;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_rate_v20 = 12'd586;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_rate_v20 = 12'd787;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_rate_v20 = 12'd933;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_rate_v20 = 12'd1155;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_rate_v20 = 12'd1193;   // G#5 k=9 f=7475.48Hz
        default: pt_rate_v20 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v20 = 16'd13435;   // G#5 k=1 f=830.61Hz
        5'd1: pt_aatk_v20 = 16'd14604;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_aatk_v20 = 16'd18871;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_aatk_v20 = 16'd18827;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_aatk_v20 = 16'd20342;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_aatk_v20 = 16'd19735;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_aatk_v20 = 16'd19733;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_aatk_v20 = 16'd19169;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_aatk_v20 = 16'd19879;   // G#5 k=9 f=7475.48Hz
        default: pt_aatk_v20 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v20(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v20 = 32'hE0B08E13;   // G#5 k=1 f=830.61Hz
        5'd1: pt_ph0_v20 = 32'hDF2043EA;   // G#5 k=2 f=1661.22Hz
        5'd2: pt_ph0_v20 = 32'h3CF060C4;   // G#5 k=3 f=2491.83Hz
        5'd3: pt_ph0_v20 = 32'h2DF7EEB0;   // G#5 k=4 f=3322.44Hz
        5'd4: pt_ph0_v20 = 32'hD41152A0;   // G#5 k=5 f=4153.05Hz
        5'd5: pt_ph0_v20 = 32'h36E09DC2;   // G#5 k=6 f=4983.66Hz
        5'd6: pt_ph0_v20 = 32'h8E4122AE;   // G#5 k=7 f=5814.27Hz
        5'd7: pt_ph0_v20 = 32'hE8824324;   // G#5 k=8 f=6644.88Hz
        5'd8: pt_ph0_v20 = 32'hBC34F9B7;   // G#5 k=9 f=7475.48Hz
        default: pt_ph0_v20 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v21 = 32'h049D1DB3;   // A5 k=1 f=880.00Hz
        5'd1: pt_inc_v21 = 32'h093A3B65;   // A5 k=2 f=1760.00Hz
        5'd2: pt_inc_v21 = 32'h0DD75918;   // A5 k=3 f=2640.00Hz
        5'd3: pt_inc_v21 = 32'h127476CA;   // A5 k=4 f=3520.00Hz
        5'd4: pt_inc_v21 = 32'h1711947D;   // A5 k=5 f=4400.00Hz
        5'd5: pt_inc_v21 = 32'h1BAEB230;   // A5 k=6 f=5280.00Hz
        5'd6: pt_inc_v21 = 32'h204BCFE2;   // A5 k=7 f=6160.00Hz
        5'd7: pt_inc_v21 = 32'h24E8ED95;   // A5 k=8 f=7040.00Hz
        5'd8: pt_inc_v21 = 32'h29860B47;   // A5 k=9 f=7920.00Hz
        default: pt_inc_v21 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v21 = 16'd3691;   // A5 k=1 f=880.00Hz
        5'd1: pt_env0_v21 = 16'd3452;   // A5 k=2 f=1760.00Hz
        5'd2: pt_env0_v21 = 16'd115;   // A5 k=3 f=2640.00Hz
        5'd3: pt_env0_v21 = 16'd1531;   // A5 k=4 f=3520.00Hz
        5'd4: pt_env0_v21 = 16'd156;   // A5 k=5 f=4400.00Hz
        5'd5: pt_env0_v21 = 16'd45;   // A5 k=6 f=5280.00Hz
        5'd6: pt_env0_v21 = 16'd22;   // A5 k=7 f=6160.00Hz
        5'd7: pt_env0_v21 = 16'd55;   // A5 k=8 f=7040.00Hz
        5'd8: pt_env0_v21 = 16'd27;   // A5 k=9 f=7920.00Hz
        default: pt_env0_v21 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v21 = 12'd176;   // A5 k=1 f=880.00Hz
        5'd1: pt_rate_v21 = 12'd344;   // A5 k=2 f=1760.00Hz
        5'd2: pt_rate_v21 = 12'd403;   // A5 k=3 f=2640.00Hz
        5'd3: pt_rate_v21 = 12'd774;   // A5 k=4 f=3520.00Hz
        5'd4: pt_rate_v21 = 12'd1041;   // A5 k=5 f=4400.00Hz
        5'd5: pt_rate_v21 = 12'd784;   // A5 k=6 f=5280.00Hz
        5'd6: pt_rate_v21 = 12'd720;   // A5 k=7 f=6160.00Hz
        5'd7: pt_rate_v21 = 12'd1139;   // A5 k=8 f=7040.00Hz
        5'd8: pt_rate_v21 = 12'd765;   // A5 k=9 f=7920.00Hz
        default: pt_rate_v21 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v21 = 16'd15582;   // A5 k=1 f=880.00Hz
        5'd1: pt_aatk_v21 = 16'd16797;   // A5 k=2 f=1760.00Hz
        5'd2: pt_aatk_v21 = 16'd18752;   // A5 k=3 f=2640.00Hz
        5'd3: pt_aatk_v21 = 16'd16804;   // A5 k=4 f=3520.00Hz
        5'd4: pt_aatk_v21 = 16'd16390;   // A5 k=5 f=4400.00Hz
        5'd5: pt_aatk_v21 = 16'd19761;   // A5 k=6 f=5280.00Hz
        5'd6: pt_aatk_v21 = 16'd21699;   // A5 k=7 f=6160.00Hz
        5'd7: pt_aatk_v21 = 16'd19274;   // A5 k=8 f=7040.00Hz
        5'd8: pt_aatk_v21 = 16'd23521;   // A5 k=9 f=7920.00Hz
        default: pt_aatk_v21 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v21(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v21 = 32'h43430EF6;   // A5 k=1 f=880.00Hz
        5'd1: pt_ph0_v21 = 32'h712628B8;   // A5 k=2 f=1760.00Hz
        5'd2: pt_ph0_v21 = 32'h321F26A0;   // A5 k=3 f=2640.00Hz
        5'd3: pt_ph0_v21 = 32'h0FA2F565;   // A5 k=4 f=3520.00Hz
        5'd4: pt_ph0_v21 = 32'h70C1C1A2;   // A5 k=5 f=4400.00Hz
        5'd5: pt_ph0_v21 = 32'h56411ED8;   // A5 k=6 f=5280.00Hz
        5'd6: pt_ph0_v21 = 32'hC6DEA6B5;   // A5 k=7 f=6160.00Hz
        5'd7: pt_ph0_v21 = 32'h5309F991;   // A5 k=8 f=7040.00Hz
        5'd8: pt_ph0_v21 = 32'hEB461E0C;   // A5 k=9 f=7920.00Hz
        default: pt_ph0_v21 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v22 = 32'h04E3594C;   // A#5 k=1 f=932.33Hz
        5'd1: pt_inc_v22 = 32'h09C6B298;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_inc_v22 = 32'h0EAA0BE5;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_inc_v22 = 32'h138D6531;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_inc_v22 = 32'h1870BE7D;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_inc_v22 = 32'h1D5417C9;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_inc_v22 = 32'h22377115;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_inc_v22 = 32'h271ACA62;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_inc_v22 = 32'h2BFE23AE;   // A#5 k=9 f=8390.95Hz
        5'd9: pt_inc_v22 = 32'h30E17CFA;   // A#5 k=10 f=9323.28Hz
        default: pt_inc_v22 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v22 = 16'd6062;   // A#5 k=1 f=932.33Hz
        5'd1: pt_env0_v22 = 16'd490;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_env0_v22 = 16'd1470;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_env0_v22 = 16'd466;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_env0_v22 = 16'd471;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_env0_v22 = 16'd232;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_env0_v22 = 16'd214;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_env0_v22 = 16'd74;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_env0_v22 = 16'd248;   // A#5 k=9 f=8390.95Hz
        5'd9: pt_env0_v22 = 16'd53;   // A#5 k=10 f=9323.28Hz
        default: pt_env0_v22 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v22 = 12'd314;   // A#5 k=1 f=932.33Hz
        5'd1: pt_rate_v22 = 12'd462;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_rate_v22 = 12'd915;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_rate_v22 = 12'd1206;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_rate_v22 = 12'd992;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_rate_v22 = 12'd1612;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_rate_v22 = 12'd1434;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_rate_v22 = 12'd2440;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_rate_v22 = 12'd2807;   // A#5 k=9 f=8390.95Hz
        5'd9: pt_rate_v22 = 12'd1385;   // A#5 k=10 f=9323.28Hz
        default: pt_rate_v22 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v22 = 16'd13146;   // A#5 k=1 f=932.33Hz
        5'd1: pt_aatk_v22 = 16'd15260;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_aatk_v22 = 16'd13921;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_aatk_v22 = 16'd13957;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_aatk_v22 = 16'd16722;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_aatk_v22 = 16'd14555;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_aatk_v22 = 16'd16471;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_aatk_v22 = 16'd13324;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_aatk_v22 = 16'd12976;   // A#5 k=9 f=8390.95Hz
        5'd9: pt_aatk_v22 = 16'd19522;   // A#5 k=10 f=9323.28Hz
        default: pt_aatk_v22 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v22(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v22 = 32'h3876C3AF;   // A#5 k=1 f=932.33Hz
        5'd1: pt_ph0_v22 = 32'hE069266E;   // A#5 k=2 f=1864.66Hz
        5'd2: pt_ph0_v22 = 32'h503403BC;   // A#5 k=3 f=2796.98Hz
        5'd3: pt_ph0_v22 = 32'hD83E983D;   // A#5 k=4 f=3729.31Hz
        5'd4: pt_ph0_v22 = 32'h6C1E8981;   // A#5 k=5 f=4661.64Hz
        5'd5: pt_ph0_v22 = 32'h18F015D6;   // A#5 k=6 f=5593.97Hz
        5'd6: pt_ph0_v22 = 32'h5311C693;   // A#5 k=7 f=6526.29Hz
        5'd7: pt_ph0_v22 = 32'h13B1FD32;   // A#5 k=8 f=7458.62Hz
        5'd8: pt_ph0_v22 = 32'hAEE2CC19;   // A#5 k=9 f=8390.95Hz
        5'd9: pt_ph0_v22 = 32'h1B536289;   // A#5 k=10 f=9323.28Hz
        default: pt_ph0_v22 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v23 = 32'h052DC205;   // B5 k=1 f=987.77Hz
        5'd1: pt_inc_v23 = 32'h0A5B840A;   // B5 k=2 f=1975.53Hz
        5'd2: pt_inc_v23 = 32'h0F894610;   // B5 k=3 f=2963.30Hz
        5'd3: pt_inc_v23 = 32'h14B70815;   // B5 k=4 f=3951.07Hz
        5'd4: pt_inc_v23 = 32'h19E4CA1A;   // B5 k=5 f=4938.83Hz
        5'd5: pt_inc_v23 = 32'h1F128C1F;   // B5 k=6 f=5926.60Hz
        5'd6: pt_inc_v23 = 32'h24404E24;   // B5 k=7 f=6914.37Hz
        5'd7: pt_inc_v23 = 32'h296E102A;   // B5 k=8 f=7902.13Hz
        5'd9: pt_inc_v23 = 32'h33C99434;   // B5 k=10 f=9877.67Hz
        default: pt_inc_v23 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v23 = 16'd6878;   // B5 k=1 f=987.77Hz
        5'd1: pt_env0_v23 = 16'd1478;   // B5 k=2 f=1975.53Hz
        5'd2: pt_env0_v23 = 16'd357;   // B5 k=3 f=2963.30Hz
        5'd3: pt_env0_v23 = 16'd274;   // B5 k=4 f=3951.07Hz
        5'd4: pt_env0_v23 = 16'd689;   // B5 k=5 f=4938.83Hz
        5'd5: pt_env0_v23 = 16'd39;   // B5 k=6 f=5926.60Hz
        5'd6: pt_env0_v23 = 16'd147;   // B5 k=7 f=6914.37Hz
        5'd7: pt_env0_v23 = 16'd81;   // B5 k=8 f=7902.13Hz
        5'd9: pt_env0_v23 = 16'd16;   // B5 k=10 f=9877.67Hz
        default: pt_env0_v23 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v23 = 12'd390;   // B5 k=1 f=987.77Hz
        5'd1: pt_rate_v23 = 12'd745;   // B5 k=2 f=1975.53Hz
        5'd2: pt_rate_v23 = 12'd1098;   // B5 k=3 f=2963.30Hz
        5'd3: pt_rate_v23 = 12'd893;   // B5 k=4 f=3951.07Hz
        5'd4: pt_rate_v23 = 12'd1888;   // B5 k=5 f=4938.83Hz
        5'd5: pt_rate_v23 = 12'd1144;   // B5 k=6 f=5926.60Hz
        5'd6: pt_rate_v23 = 12'd1913;   // B5 k=7 f=6914.37Hz
        5'd7: pt_rate_v23 = 12'd2002;   // B5 k=8 f=7902.13Hz
        5'd9: pt_rate_v23 = 12'd1385;   // B5 k=10 f=9877.67Hz
        default: pt_rate_v23 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v23 = 16'd12225;   // B5 k=1 f=987.77Hz
        5'd1: pt_aatk_v23 = 16'd12762;   // B5 k=2 f=1975.53Hz
        5'd2: pt_aatk_v23 = 16'd12847;   // B5 k=3 f=2963.30Hz
        5'd3: pt_aatk_v23 = 16'd15885;   // B5 k=4 f=3951.07Hz
        5'd4: pt_aatk_v23 = 16'd12344;   // B5 k=5 f=4938.83Hz
        5'd5: pt_aatk_v23 = 16'd17027;   // B5 k=6 f=5926.60Hz
        5'd6: pt_aatk_v23 = 16'd14307;   // B5 k=7 f=6914.37Hz
        5'd7: pt_aatk_v23 = 16'd14849;   // B5 k=8 f=7902.13Hz
        5'd9: pt_aatk_v23 = 16'd19522;   // B5 k=10 f=9877.67Hz
        default: pt_aatk_v23 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v23(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v23 = 32'h974D5808;   // B5 k=1 f=987.77Hz
        5'd1: pt_ph0_v23 = 32'hB5C483EE;   // B5 k=2 f=1975.53Hz
        5'd2: pt_ph0_v23 = 32'h92937772;   // B5 k=3 f=2963.30Hz
        5'd3: pt_ph0_v23 = 32'h73D460CC;   // B5 k=4 f=3951.07Hz
        5'd4: pt_ph0_v23 = 32'h067E7A6A;   // B5 k=5 f=4938.83Hz
        5'd5: pt_ph0_v23 = 32'hEF588093;   // B5 k=6 f=5926.60Hz
        5'd6: pt_ph0_v23 = 32'h161979F6;   // B5 k=7 f=6914.37Hz
        5'd7: pt_ph0_v23 = 32'h1DD309C5;   // B5 k=8 f=7902.13Hz
        5'd9: pt_ph0_v23 = 32'hE19961B0;   // B5 k=10 f=9877.67Hz
        default: pt_ph0_v23 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v24 = 32'h057C9770;   // C6 k=1 f=1046.50Hz
        5'd1: pt_inc_v24 = 32'h0AF92EE1;   // C6 k=2 f=2093.00Hz
        5'd2: pt_inc_v24 = 32'h1075C651;   // C6 k=3 f=3139.51Hz
        5'd3: pt_inc_v24 = 32'h15F25DC1;   // C6 k=4 f=4186.01Hz
        5'd4: pt_inc_v24 = 32'h1B6EF532;   // C6 k=5 f=5232.51Hz
        5'd5: pt_inc_v24 = 32'h20EB8CA2;   // C6 k=6 f=6279.01Hz
        5'd6: pt_inc_v24 = 32'h26682413;   // C6 k=7 f=7325.52Hz
        5'd7: pt_inc_v24 = 32'h2BE4BB83;   // C6 k=8 f=8372.02Hz
        default: pt_inc_v24 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v24 = 16'd7765;   // C6 k=1 f=1046.50Hz
        5'd1: pt_env0_v24 = 16'd730;   // C6 k=2 f=2093.00Hz
        5'd2: pt_env0_v24 = 16'd503;   // C6 k=3 f=3139.51Hz
        5'd3: pt_env0_v24 = 16'd500;   // C6 k=4 f=4186.01Hz
        5'd4: pt_env0_v24 = 16'd267;   // C6 k=5 f=5232.51Hz
        5'd5: pt_env0_v24 = 16'd266;   // C6 k=6 f=6279.01Hz
        5'd6: pt_env0_v24 = 16'd155;   // C6 k=7 f=7325.52Hz
        5'd7: pt_env0_v24 = 16'd15;   // C6 k=8 f=8372.02Hz
        default: pt_env0_v24 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v24 = 12'd498;   // C6 k=1 f=1046.50Hz
        5'd1: pt_rate_v24 = 12'd575;   // C6 k=2 f=2093.00Hz
        5'd2: pt_rate_v24 = 12'd1040;   // C6 k=3 f=3139.51Hz
        5'd3: pt_rate_v24 = 12'd1568;   // C6 k=4 f=4186.01Hz
        5'd4: pt_rate_v24 = 12'd1739;   // C6 k=5 f=5232.51Hz
        5'd5: pt_rate_v24 = 12'd1591;   // C6 k=6 f=6279.01Hz
        5'd6: pt_rate_v24 = 12'd1691;   // C6 k=7 f=7325.52Hz
        5'd7: pt_rate_v24 = 12'd1385;   // C6 k=8 f=8372.02Hz
        default: pt_rate_v24 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v24 = 16'd11192;   // C6 k=1 f=1046.50Hz
        5'd1: pt_aatk_v24 = 16'd14111;   // C6 k=2 f=2093.00Hz
        5'd2: pt_aatk_v24 = 16'd13168;   // C6 k=3 f=3139.51Hz
        5'd3: pt_aatk_v24 = 16'd12294;   // C6 k=4 f=4186.01Hz
        5'd4: pt_aatk_v24 = 16'd12897;   // C6 k=5 f=5232.51Hz
        5'd5: pt_aatk_v24 = 16'd14649;   // C6 k=6 f=6279.01Hz
        5'd6: pt_aatk_v24 = 16'd15231;   // C6 k=7 f=7325.52Hz
        5'd7: pt_aatk_v24 = 16'd17731;   // C6 k=8 f=8372.02Hz
        default: pt_aatk_v24 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v24(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v24 = 32'h784FEB87;   // C6 k=1 f=1046.50Hz
        5'd1: pt_ph0_v24 = 32'h22D66A18;   // C6 k=2 f=2093.00Hz
        5'd2: pt_ph0_v24 = 32'h1D36A792;   // C6 k=3 f=3139.51Hz
        5'd3: pt_ph0_v24 = 32'hF154ED70;   // C6 k=4 f=4186.01Hz
        5'd4: pt_ph0_v24 = 32'hF3A1BAAD;   // C6 k=5 f=5232.51Hz
        5'd5: pt_ph0_v24 = 32'h5A7B6237;   // C6 k=6 f=6279.01Hz
        5'd6: pt_ph0_v24 = 32'hF18EBC09;   // C6 k=7 f=7325.52Hz
        5'd7: pt_ph0_v24 = 32'hE7FE6ECE;   // C6 k=8 f=8372.02Hz
        default: pt_ph0_v24 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v25 = 32'h05D01CE8;   // C#6 k=1 f=1108.73Hz
        5'd1: pt_inc_v25 = 32'h0BA039D0;   // C#6 k=2 f=2217.46Hz
        5'd2: pt_inc_v25 = 32'h117056B9;   // C#6 k=3 f=3326.19Hz
        5'd3: pt_inc_v25 = 32'h174073A1;   // C#6 k=4 f=4434.92Hz
        5'd4: pt_inc_v25 = 32'h1D109089;   // C#6 k=5 f=5543.65Hz
        5'd5: pt_inc_v25 = 32'h22E0AD71;   // C#6 k=6 f=6652.38Hz
        5'd6: pt_inc_v25 = 32'h28B0CA5A;   // C#6 k=7 f=7761.11Hz
        5'd8: pt_inc_v25 = 32'h3451042A;   // C#6 k=9 f=9978.57Hz
        default: pt_inc_v25 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v25 = 16'd8283;   // C#6 k=1 f=1108.73Hz
        5'd1: pt_env0_v25 = 16'd459;   // C#6 k=2 f=2217.46Hz
        5'd2: pt_env0_v25 = 16'd344;   // C#6 k=3 f=3326.19Hz
        5'd3: pt_env0_v25 = 16'd254;   // C#6 k=4 f=4434.92Hz
        5'd4: pt_env0_v25 = 16'd205;   // C#6 k=5 f=5543.65Hz
        5'd5: pt_env0_v25 = 16'd124;   // C#6 k=6 f=6652.38Hz
        5'd6: pt_env0_v25 = 16'd436;   // C#6 k=7 f=7761.11Hz
        5'd8: pt_env0_v25 = 16'd10;   // C#6 k=9 f=9978.57Hz
        default: pt_env0_v25 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v25 = 12'd467;   // C#6 k=1 f=1108.73Hz
        5'd1: pt_rate_v25 = 12'd909;   // C#6 k=2 f=2217.46Hz
        5'd2: pt_rate_v25 = 12'd966;   // C#6 k=3 f=3326.19Hz
        5'd3: pt_rate_v25 = 12'd1408;   // C#6 k=4 f=4434.92Hz
        5'd4: pt_rate_v25 = 12'd1328;   // C#6 k=5 f=5543.65Hz
        5'd5: pt_rate_v25 = 12'd1896;   // C#6 k=6 f=6652.38Hz
        5'd6: pt_rate_v25 = 12'd2285;   // C#6 k=7 f=7761.11Hz
        5'd8: pt_rate_v25 = 12'd1385;   // C#6 k=9 f=9978.57Hz
        default: pt_rate_v25 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v25 = 16'd11464;   // C#6 k=1 f=1108.73Hz
        5'd1: pt_aatk_v25 = 16'd11727;   // C#6 k=2 f=2217.46Hz
        5'd2: pt_aatk_v25 = 16'd13599;   // C#6 k=3 f=3326.19Hz
        5'd3: pt_aatk_v25 = 16'd12973;   // C#6 k=4 f=4434.92Hz
        5'd4: pt_aatk_v25 = 16'd14727;   // C#6 k=5 f=5543.65Hz
        5'd5: pt_aatk_v25 = 16'd13395;   // C#6 k=6 f=6652.38Hz
        5'd6: pt_aatk_v25 = 16'd12991;   // C#6 k=7 f=7761.11Hz
        5'd8: pt_aatk_v25 = 16'd18657;   // C#6 k=9 f=9978.57Hz
        default: pt_aatk_v25 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v25(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v25 = 32'h6ED0C07D;   // C#6 k=1 f=1108.73Hz
        5'd1: pt_ph0_v25 = 32'hAD343952;   // C#6 k=2 f=2217.46Hz
        5'd2: pt_ph0_v25 = 32'h69D3E7FD;   // C#6 k=3 f=3326.19Hz
        5'd3: pt_ph0_v25 = 32'hB0A5E00B;   // C#6 k=4 f=4434.92Hz
        5'd4: pt_ph0_v25 = 32'h98344D79;   // C#6 k=5 f=5543.65Hz
        5'd5: pt_ph0_v25 = 32'h22F7D334;   // C#6 k=6 f=6652.38Hz
        5'd6: pt_ph0_v25 = 32'hCAB6D0FC;   // C#6 k=7 f=7761.11Hz
        5'd8: pt_ph0_v25 = 32'h05824DA8;   // C#6 k=9 f=9978.57Hz
        default: pt_ph0_v25 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v26 = 32'h062899C9;   // D6 k=1 f=1174.66Hz
        5'd1: pt_inc_v26 = 32'h0C513391;   // D6 k=2 f=2349.32Hz
        5'd2: pt_inc_v26 = 32'h1279CD5A;   // D6 k=3 f=3523.98Hz
        5'd3: pt_inc_v26 = 32'h18A26723;   // D6 k=4 f=4698.64Hz
        5'd4: pt_inc_v26 = 32'h1ECB00EB;   // D6 k=5 f=5873.30Hz
        5'd5: pt_inc_v26 = 32'h24F39AB4;   // D6 k=6 f=7047.95Hz
        5'd6: pt_inc_v26 = 32'h2B1C347C;   // D6 k=7 f=8222.61Hz
        5'd7: pt_inc_v26 = 32'h3144CE45;   // D6 k=8 f=9397.27Hz
        default: pt_inc_v26 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v26 = 16'd7890;   // D6 k=1 f=1174.66Hz
        5'd1: pt_env0_v26 = 16'd1976;   // D6 k=2 f=2349.32Hz
        5'd2: pt_env0_v26 = 16'd741;   // D6 k=3 f=3523.98Hz
        5'd3: pt_env0_v26 = 16'd239;   // D6 k=4 f=4698.64Hz
        5'd4: pt_env0_v26 = 16'd323;   // D6 k=5 f=5873.30Hz
        5'd5: pt_env0_v26 = 16'd126;   // D6 k=6 f=7047.95Hz
        5'd6: pt_env0_v26 = 16'd256;   // D6 k=7 f=8222.61Hz
        5'd7: pt_env0_v26 = 16'd18;   // D6 k=8 f=9397.27Hz
        default: pt_env0_v26 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v26 = 12'd814;   // D6 k=1 f=1174.66Hz
        5'd1: pt_rate_v26 = 12'd753;   // D6 k=2 f=2349.32Hz
        5'd2: pt_rate_v26 = 12'd1704;   // D6 k=3 f=3523.98Hz
        5'd3: pt_rate_v26 = 12'd1534;   // D6 k=4 f=4698.64Hz
        5'd4: pt_rate_v26 = 12'd2413;   // D6 k=5 f=5873.30Hz
        5'd5: pt_rate_v26 = 12'd2085;   // D6 k=6 f=7047.95Hz
        5'd6: pt_rate_v26 = 12'd2710;   // D6 k=7 f=8222.61Hz
        5'd7: pt_rate_v26 = 12'd1385;   // D6 k=8 f=9397.27Hz
        default: pt_rate_v26 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v26 = 16'd9113;   // D6 k=1 f=1174.66Hz
        5'd1: pt_aatk_v26 = 16'd12708;   // D6 k=2 f=2349.32Hz
        5'd2: pt_aatk_v26 = 16'd10297;   // D6 k=3 f=3523.98Hz
        5'd3: pt_aatk_v26 = 16'd12431;   // D6 k=4 f=4698.64Hz
        5'd4: pt_aatk_v26 = 16'd10714;   // D6 k=5 f=5873.30Hz
        5'd5: pt_aatk_v26 = 16'd12723;   // D6 k=6 f=7047.95Hz
        5'd6: pt_aatk_v26 = 16'd11742;   // D6 k=7 f=8222.61Hz
        5'd7: pt_aatk_v26 = 16'd17731;   // D6 k=8 f=9397.27Hz
        default: pt_aatk_v26 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v26(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v26 = 32'hCADA1DD3;   // D6 k=1 f=1174.66Hz
        5'd1: pt_ph0_v26 = 32'hA5671503;   // D6 k=2 f=2349.32Hz
        5'd2: pt_ph0_v26 = 32'hBF2D8AE0;   // D6 k=3 f=3523.98Hz
        5'd3: pt_ph0_v26 = 32'hC04036D8;   // D6 k=4 f=4698.64Hz
        5'd4: pt_ph0_v26 = 32'h15F1E735;   // D6 k=5 f=5873.30Hz
        5'd5: pt_ph0_v26 = 32'h0BFD247E;   // D6 k=6 f=7047.95Hz
        5'd6: pt_ph0_v26 = 32'h4124CB27;   // D6 k=7 f=8222.61Hz
        5'd7: pt_ph0_v26 = 32'hD048A7E7;   // D6 k=8 f=9397.27Hz
        default: pt_ph0_v26 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_inc_v27 = 32'h068659AC;   // D#6 k=1 f=1244.51Hz
        5'd1: pt_inc_v27 = 32'h0D0CB357;   // D#6 k=2 f=2489.02Hz
        5'd2: pt_inc_v27 = 32'h13930D03;   // D#6 k=3 f=3733.52Hz
        5'd3: pt_inc_v27 = 32'h1A1966AE;   // D#6 k=4 f=4978.03Hz
        5'd4: pt_inc_v27 = 32'h209FC05A;   // D#6 k=5 f=6222.54Hz
        5'd5: pt_inc_v27 = 32'h27261A06;   // D#6 k=6 f=7467.05Hz
        5'd6: pt_inc_v27 = 32'h2DAC73B1;   // D#6 k=7 f=8711.56Hz
        default: pt_inc_v27 = 32'd0;
    endcase
end
endfunction

function automatic [15:0] pt_env0_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_env0_v27 = 16'd8108;   // D#6 k=1 f=1244.51Hz
        5'd1: pt_env0_v27 = 16'd1523;   // D#6 k=2 f=2489.02Hz
        5'd2: pt_env0_v27 = 16'd591;   // D#6 k=3 f=3733.52Hz
        5'd3: pt_env0_v27 = 16'd446;   // D#6 k=4 f=4978.03Hz
        5'd4: pt_env0_v27 = 16'd230;   // D#6 k=5 f=6222.54Hz
        5'd5: pt_env0_v27 = 16'd60;   // D#6 k=6 f=7467.05Hz
        5'd6: pt_env0_v27 = 16'd128;   // D#6 k=7 f=8711.56Hz
        default: pt_env0_v27 = 16'd0;
    endcase
end
endfunction

function automatic [11:0] pt_rate_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_rate_v27 = 12'd710;   // D#6 k=1 f=1244.51Hz
        5'd1: pt_rate_v27 = 12'd945;   // D#6 k=2 f=2489.02Hz
        5'd2: pt_rate_v27 = 12'd1193;   // D#6 k=3 f=3733.52Hz
        5'd3: pt_rate_v27 = 12'd1820;   // D#6 k=4 f=4978.03Hz
        5'd4: pt_rate_v27 = 12'd1429;   // D#6 k=5 f=6222.54Hz
        5'd5: pt_rate_v27 = 12'd1385;   // D#6 k=6 f=7467.05Hz
        5'd6: pt_rate_v27 = 12'd2111;   // D#6 k=7 f=8711.56Hz
        default: pt_rate_v27 = 12'd0;
    endcase
end
endfunction

function automatic [15:0] pt_aatk_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_aatk_v27 = 16'd9691;   // D#6 k=1 f=1244.51Hz
        5'd1: pt_aatk_v27 = 16'd11525;   // D#6 k=2 f=2489.02Hz
        5'd2: pt_aatk_v27 = 16'd12364;   // D#6 k=3 f=3733.52Hz
        5'd3: pt_aatk_v27 = 16'd11355;   // D#6 k=4 f=4978.03Hz
        5'd4: pt_aatk_v27 = 16'd14225;   // D#6 k=5 f=6222.54Hz
        5'd5: pt_aatk_v27 = 16'd15642;   // D#6 k=6 f=7467.05Hz
        5'd6: pt_aatk_v27 = 16'd13577;   // D#6 k=7 f=8711.56Hz
        default: pt_aatk_v27 = 16'd0;
    endcase
end
endfunction

function automatic [31:0] pt_ph0_v27(input [4:0] p); begin
    case (p)
        5'd0: pt_ph0_v27 = 32'h5230921D;   // D#6 k=1 f=1244.51Hz
        5'd1: pt_ph0_v27 = 32'h4E1156A4;   // D#6 k=2 f=2489.02Hz
        5'd2: pt_ph0_v27 = 32'hFC797C06;   // D#6 k=3 f=3733.52Hz
        5'd3: pt_ph0_v27 = 32'hACC36ABA;   // D#6 k=4 f=4978.03Hz
        5'd4: pt_ph0_v27 = 32'hF31D0470;   // D#6 k=5 f=6222.54Hz
        5'd5: pt_ph0_v27 = 32'h21F69A99;   // D#6 k=6 f=7467.05Hz
        5'd6: pt_ph0_v27 = 32'hA61DBAAB;   // D#6 k=7 f=8711.56Hz
        default: pt_ph0_v27 = 32'd0;
    endcase
end
endfunction

function automatic [31:0] pt_inc(input [4:0] vv, input [4:0] pp);
    begin
        case (vv)
            5'd0: pt_inc = pt_inc_v0(pp);   // C4
            5'd1: pt_inc = pt_inc_v1(pp);   // C#4
            5'd2: pt_inc = pt_inc_v2(pp);   // D4
            5'd3: pt_inc = pt_inc_v3(pp);   // D#4
            5'd4: pt_inc = pt_inc_v4(pp);   // E4
            5'd5: pt_inc = pt_inc_v5(pp);   // F4
            5'd6: pt_inc = pt_inc_v6(pp);   // F#4
            5'd7: pt_inc = pt_inc_v7(pp);   // G4
            5'd8: pt_inc = pt_inc_v8(pp);   // G#4
            5'd9: pt_inc = pt_inc_v9(pp);   // A4
            5'd10: pt_inc = pt_inc_v10(pp);   // A#4
            5'd11: pt_inc = pt_inc_v11(pp);   // B4
            5'd12: pt_inc = pt_inc_v12(pp);   // C5
            5'd13: pt_inc = pt_inc_v13(pp);   // C#5
            5'd14: pt_inc = pt_inc_v14(pp);   // D5
            5'd15: pt_inc = pt_inc_v15(pp);   // D#5
            5'd16: pt_inc = pt_inc_v16(pp);   // E5
            5'd17: pt_inc = pt_inc_v17(pp);   // F5
            5'd18: pt_inc = pt_inc_v18(pp);   // F#5
            5'd19: pt_inc = pt_inc_v19(pp);   // G5
            5'd20: pt_inc = pt_inc_v20(pp);   // G#5
            5'd21: pt_inc = pt_inc_v21(pp);   // A5
            5'd22: pt_inc = pt_inc_v22(pp);   // A#5
            5'd23: pt_inc = pt_inc_v23(pp);   // B5
            5'd24: pt_inc = pt_inc_v24(pp);   // C6
            5'd25: pt_inc = pt_inc_v25(pp);   // C#6
            5'd26: pt_inc = pt_inc_v26(pp);   // D6
            5'd27: pt_inc = pt_inc_v27(pp);   // D#6
            default: pt_inc = 32'd0;
        endcase
    end
endfunction

function automatic [15:0] pt_env0(input [4:0] vv, input [4:0] pp);
    begin
        case (vv)
            5'd0: pt_env0 = pt_env0_v0(pp);   // C4
            5'd1: pt_env0 = pt_env0_v1(pp);   // C#4
            5'd2: pt_env0 = pt_env0_v2(pp);   // D4
            5'd3: pt_env0 = pt_env0_v3(pp);   // D#4
            5'd4: pt_env0 = pt_env0_v4(pp);   // E4
            5'd5: pt_env0 = pt_env0_v5(pp);   // F4
            5'd6: pt_env0 = pt_env0_v6(pp);   // F#4
            5'd7: pt_env0 = pt_env0_v7(pp);   // G4
            5'd8: pt_env0 = pt_env0_v8(pp);   // G#4
            5'd9: pt_env0 = pt_env0_v9(pp);   // A4
            5'd10: pt_env0 = pt_env0_v10(pp);   // A#4
            5'd11: pt_env0 = pt_env0_v11(pp);   // B4
            5'd12: pt_env0 = pt_env0_v12(pp);   // C5
            5'd13: pt_env0 = pt_env0_v13(pp);   // C#5
            5'd14: pt_env0 = pt_env0_v14(pp);   // D5
            5'd15: pt_env0 = pt_env0_v15(pp);   // D#5
            5'd16: pt_env0 = pt_env0_v16(pp);   // E5
            5'd17: pt_env0 = pt_env0_v17(pp);   // F5
            5'd18: pt_env0 = pt_env0_v18(pp);   // F#5
            5'd19: pt_env0 = pt_env0_v19(pp);   // G5
            5'd20: pt_env0 = pt_env0_v20(pp);   // G#5
            5'd21: pt_env0 = pt_env0_v21(pp);   // A5
            5'd22: pt_env0 = pt_env0_v22(pp);   // A#5
            5'd23: pt_env0 = pt_env0_v23(pp);   // B5
            5'd24: pt_env0 = pt_env0_v24(pp);   // C6
            5'd25: pt_env0 = pt_env0_v25(pp);   // C#6
            5'd26: pt_env0 = pt_env0_v26(pp);   // D6
            5'd27: pt_env0 = pt_env0_v27(pp);   // D#6
            default: pt_env0 = 16'd0;
        endcase
    end
endfunction

function automatic [11:0] pt_rate(input [4:0] vv, input [4:0] pp);
    begin
        case (vv)
            5'd0: pt_rate = pt_rate_v0(pp);   // C4
            5'd1: pt_rate = pt_rate_v1(pp);   // C#4
            5'd2: pt_rate = pt_rate_v2(pp);   // D4
            5'd3: pt_rate = pt_rate_v3(pp);   // D#4
            5'd4: pt_rate = pt_rate_v4(pp);   // E4
            5'd5: pt_rate = pt_rate_v5(pp);   // F4
            5'd6: pt_rate = pt_rate_v6(pp);   // F#4
            5'd7: pt_rate = pt_rate_v7(pp);   // G4
            5'd8: pt_rate = pt_rate_v8(pp);   // G#4
            5'd9: pt_rate = pt_rate_v9(pp);   // A4
            5'd10: pt_rate = pt_rate_v10(pp);   // A#4
            5'd11: pt_rate = pt_rate_v11(pp);   // B4
            5'd12: pt_rate = pt_rate_v12(pp);   // C5
            5'd13: pt_rate = pt_rate_v13(pp);   // C#5
            5'd14: pt_rate = pt_rate_v14(pp);   // D5
            5'd15: pt_rate = pt_rate_v15(pp);   // D#5
            5'd16: pt_rate = pt_rate_v16(pp);   // E5
            5'd17: pt_rate = pt_rate_v17(pp);   // F5
            5'd18: pt_rate = pt_rate_v18(pp);   // F#5
            5'd19: pt_rate = pt_rate_v19(pp);   // G5
            5'd20: pt_rate = pt_rate_v20(pp);   // G#5
            5'd21: pt_rate = pt_rate_v21(pp);   // A5
            5'd22: pt_rate = pt_rate_v22(pp);   // A#5
            5'd23: pt_rate = pt_rate_v23(pp);   // B5
            5'd24: pt_rate = pt_rate_v24(pp);   // C6
            5'd25: pt_rate = pt_rate_v25(pp);   // C#6
            5'd26: pt_rate = pt_rate_v26(pp);   // D6
            5'd27: pt_rate = pt_rate_v27(pp);   // D#6
            default: pt_rate = 12'd0;
        endcase
    end
endfunction

function automatic [15:0] pt_aatk(input [4:0] vv, input [4:0] pp);
    begin
        case (vv)
            5'd0: pt_aatk = pt_aatk_v0(pp);   // C4
            5'd1: pt_aatk = pt_aatk_v1(pp);   // C#4
            5'd2: pt_aatk = pt_aatk_v2(pp);   // D4
            5'd3: pt_aatk = pt_aatk_v3(pp);   // D#4
            5'd4: pt_aatk = pt_aatk_v4(pp);   // E4
            5'd5: pt_aatk = pt_aatk_v5(pp);   // F4
            5'd6: pt_aatk = pt_aatk_v6(pp);   // F#4
            5'd7: pt_aatk = pt_aatk_v7(pp);   // G4
            5'd8: pt_aatk = pt_aatk_v8(pp);   // G#4
            5'd9: pt_aatk = pt_aatk_v9(pp);   // A4
            5'd10: pt_aatk = pt_aatk_v10(pp);   // A#4
            5'd11: pt_aatk = pt_aatk_v11(pp);   // B4
            5'd12: pt_aatk = pt_aatk_v12(pp);   // C5
            5'd13: pt_aatk = pt_aatk_v13(pp);   // C#5
            5'd14: pt_aatk = pt_aatk_v14(pp);   // D5
            5'd15: pt_aatk = pt_aatk_v15(pp);   // D#5
            5'd16: pt_aatk = pt_aatk_v16(pp);   // E5
            5'd17: pt_aatk = pt_aatk_v17(pp);   // F5
            5'd18: pt_aatk = pt_aatk_v18(pp);   // F#5
            5'd19: pt_aatk = pt_aatk_v19(pp);   // G5
            5'd20: pt_aatk = pt_aatk_v20(pp);   // G#5
            5'd21: pt_aatk = pt_aatk_v21(pp);   // A5
            5'd22: pt_aatk = pt_aatk_v22(pp);   // A#5
            5'd23: pt_aatk = pt_aatk_v23(pp);   // B5
            5'd24: pt_aatk = pt_aatk_v24(pp);   // C6
            5'd25: pt_aatk = pt_aatk_v25(pp);   // C#6
            5'd26: pt_aatk = pt_aatk_v26(pp);   // D6
            5'd27: pt_aatk = pt_aatk_v27(pp);   // D#6
            default: pt_aatk = 16'd0;
        endcase
    end
endfunction

function automatic [31:0] pt_ph0(input [4:0] vv, input [4:0] pp);
    begin
        case (vv)
            5'd0: pt_ph0 = pt_ph0_v0(pp);   // C4
            5'd1: pt_ph0 = pt_ph0_v1(pp);   // C#4
            5'd2: pt_ph0 = pt_ph0_v2(pp);   // D4
            5'd3: pt_ph0 = pt_ph0_v3(pp);   // D#4
            5'd4: pt_ph0 = pt_ph0_v4(pp);   // E4
            5'd5: pt_ph0 = pt_ph0_v5(pp);   // F4
            5'd6: pt_ph0 = pt_ph0_v6(pp);   // F#4
            5'd7: pt_ph0 = pt_ph0_v7(pp);   // G4
            5'd8: pt_ph0 = pt_ph0_v8(pp);   // G#4
            5'd9: pt_ph0 = pt_ph0_v9(pp);   // A4
            5'd10: pt_ph0 = pt_ph0_v10(pp);   // A#4
            5'd11: pt_ph0 = pt_ph0_v11(pp);   // B4
            5'd12: pt_ph0 = pt_ph0_v12(pp);   // C5
            5'd13: pt_ph0 = pt_ph0_v13(pp);   // C#5
            5'd14: pt_ph0 = pt_ph0_v14(pp);   // D5
            5'd15: pt_ph0 = pt_ph0_v15(pp);   // D#5
            5'd16: pt_ph0 = pt_ph0_v16(pp);   // E5
            5'd17: pt_ph0 = pt_ph0_v17(pp);   // F5
            5'd18: pt_ph0 = pt_ph0_v18(pp);   // F#5
            5'd19: pt_ph0 = pt_ph0_v19(pp);   // G5
            5'd20: pt_ph0 = pt_ph0_v20(pp);   // G#5
            5'd21: pt_ph0 = pt_ph0_v21(pp);   // A5
            5'd22: pt_ph0 = pt_ph0_v22(pp);   // A#5
            5'd23: pt_ph0 = pt_ph0_v23(pp);   // B5
            5'd24: pt_ph0 = pt_ph0_v24(pp);   // C6
            5'd25: pt_ph0 = pt_ph0_v25(pp);   // C#6
            5'd26: pt_ph0 = pt_ph0_v26(pp);   // D6
            5'd27: pt_ph0 = pt_ph0_v27(pp);   // D#6
            default: pt_ph0 = 32'd0;
        endcase
    end
endfunction

`endif
