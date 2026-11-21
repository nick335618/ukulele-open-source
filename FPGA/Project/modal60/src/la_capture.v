// ============================================================================
// la_capture.v —— ★ 2026-09-23 新增：**内建"在线逻辑分析仪"**（BSRAM 采样器）
// ----------------------------------------------------------------------------
// 为什么自己写而不用高云的 GAO：
//   子代理实测过 —— GAO 的**编译侧**能全命令行跑通（.rao + gprj 加 file.gao，
//   run syn 会打印 "Trying to combine GAO to RTL design"，run pnr 产出 ao_0.fs），
//   但**数据读回侧没有任何可用的命令行工具**：`gao_sh.exe` 能读配置、拉起 jtagserver、
//   打印 "Start running GAO analyzer."，然后**永久阻塞、CPU 归零、不产出文件、不报错**，
//   换遍线缆写法/触发条件/–local/--fsFile 都一样；而且它在高云任何手册里都没有文档。
//   唯一有文档的入口 gao_analyzer.exe 是 GUI。
//   ⇒ 又要全命令行、又要能读到波形，就只能自己在 RTL 里做采样器。
//
// 本模块的用法（本工程）：16 位探针 + 循环缓冲，抓满一轮后**等触发信号**，
//   触发一到就冻结 —— 于是缓冲里装的正好是**触发之前**这一整窗的样本。
//   本工程把触发接 `|opto_ev`（拨弦事件），所以抓到的是
//   "**造成这次（误）触发的那一帧 ADC 数据**"，正好用来查 ADC 为什么会乱跳。
//
// 读出：不复用任何新总线，直接搭在现成的 $O 遥测帧上（index + 8 个样本/帧）。
//   512 个样本 / 每帧 8 个 = 64 帧，@10Hz 约 6.4 秒读完。
//
// 资源：512×16 = 8 Kbit ⇒ 1 块 BSRAM（本工程 BSRAM 才用 11/56，放得下）。
// ============================================================================
`timescale 1ns / 1ps

module la_capture #(
    parameter integer DEPTH = 512          // 采样深度（建议 2 的幂）
)(
    input  wire        clk,
    input  wire        rst_n,

    input  wire [15:0] probe,              // 本拍要采的 16 位探针
    input  wire        trig,               // 触发：1 拍脉冲即冻结
    output reg         done,               // 已抓完并在冻结（数据可安全读出）

    input  wire [8:0]  raddr,              // 读出地址（同步读，1 拍延迟）
    output reg  [15:0] rdata,

    output wire [15:0] wp_dbg              // 诊断：当前写指针（只做观测）
);
    reg [15:0] mem [0:DEPTH-1];
    reg [8:0]  wp;
    reg        filled;                     // 已经绕过一整圈（缓冲是满的）

    assign wp_dbg = {7'd0, wp};

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            wp     <= 9'd0;
            filled <= 1'b0;
            done   <= 1'b0;
        end else if (!done) begin
            // 写：冻结后就不再写，保证读出期间数据稳定
            mem[wp] <= probe;
            wp      <= wp + 9'd1;
            if (wp == DEPTH - 1) filled <= 1'b1;
            // 只有缓冲绕过一整圈之后才认触发，保证窗口是满的
            if (filled && wp == DEPTH - 1 && trig) done <= 1'b1;
        end
    end

    // 读端口：与写**在同一个 always**（综合器才认得出这是带写口的 BSRAM，
    //   分成两个 always 有些版本会退化成 FF 阵列 —— 同 tone_rom.v 的教训）。
    //   写完冻结之后就没写了，所以不需要写穿透。
    always @(posedge clk) begin
        rdata <= mem[raddr];
    end

endmodule
