// ============================================================================
// ad7147_i2c.v —— AD7147（**I²C 版**）寄存器事务：读/写一个 16 位寄存器
// ----------------------------------------------------------------------------
// ★★ 这是**可替换的物理层**：对外接口与 ad7147_spi.v 逐位相同
//       start / wr / dev / addr[9:0] / wdata[15:0] → rdata[15:0] / done / busy / err
//    换成 SPI 款 AD7147 时只需：① sensor_reg_if.v 里 SENSOR_SPI 改 1；
//                              ② 不再例化本模块。上层一行都不用改。
//
// I²C 版 AD7147 时序（手册 Rev.E + Linux ad714x-i2c.c 交叉核对）：
//   * 从机地址 7 位 0x2C（ADD0=0）/ 0x2E（ADD0=1），与固件里用的两颗一致
//   * 寄存器地址 **16 位**、MSB 先发；地址指针自动递增（突发读 12 组 CDC 只给一次地址）
//   * 写：START | dev+W | addrHi | addrLo | dataHi | dataLo | STOP
//   * 读：START | dev+W | addrHi | addrLo | RESTART | dev+R | recvHi(ACK) | recvLo(NACK) | STOP
//
// 结构：外层 step 序列 + 字节级 SEND/RECV 子状态机。
//   每个字节两拍：SCL 低相位放数据（或释放总线）、SCL 高相位采样/推进；
//   `bitc` = 0..7 表示数据位、= 8 表示 ACK 相位（发字节时读 ACK，收字节时驱动 ACK/NACK）。
//   SCL 释放后等它真的回高（支持从机拉伸）由 HALF 计数保证。
//
// ⚠ 上拉：4.7k 上拉原来做在 **ESP32 那一侧**（排线 pin6/pin8 → ESP32 3V3）。
//   移植后请保留 ESP32 板接着（哪怕不再驱动 I²C），或在 FPGA 侧补 4.7k，否则读错。
// ============================================================================
module ad7147_i2c #(
    parameter CLK_HZ = 50_000_000,
    parameter SCL_HZ = 200_000
) (
    input  wire        clk,
    input  wire        rst_n,
    input  wire        start,      // 单拍：启动一次事务
    input  wire        abort,      // 单拍：调用方看门狗超时，强制收尾本笔事务
    input  wire        wr,         // 1 = 写寄存器，0 = 读寄存器
    input  wire [6:0]  dev,        // 7 位从机地址
    input  wire [9:0]  addr,       // 寄存器地址（内部补成 16 位）
    input  wire [15:0] wdata,
    output reg  [15:0] rdata,
    output reg         done,
    output reg         busy,
    output reg         err,
    output reg         scl_oe,     // 1 = 释放 SCL（外部上拉），0 = 拉低
    output reg         sda_oe,     // 1 = 释放 SDA，0 = 拉低
    input  wire        sda_in
);

    localparam integer HALF = (CLK_HZ / (SCL_HZ * 2) < 2) ? 2 : (CLK_HZ / (SCL_HZ * 2));

    localparam S_IDLE = 3'd0, S_START = 3'd1, S_SEND = 3'd2,
               S_RECV = 3'd3, S_STOP  = 3'd4, S_FIN  = 3'd5;

    // 写事务：0 devW / 1 addrHi / 2 addrLo / 3 dataHi / 4 dataLo → 停
    // 读事务：0 devW / 1 addrHi / 2 addrLo / 3 重启 / 4 devR / 5 recvHi / 6 recvLo → 停
    localparam STEP_W_END = 4'd5;   // 写：step 走到 5 就该停
    localparam STEP_R_END = 4'd7;   // 读：step 走到 7 就该停

    reg [2:0]  st;
    reg [3:0]  step;
    reg [2:0]  ph;         // 起始/停止条件的相位
    reg [3:0]  bitc;       // 0..7 数据位，8 = ACK 相位
    reg [7:0]  shreg;
    reg [15:0] hcnt;
    reg        scl_lo;     // 0 = SCL 低相位，1 = SCL 高相位
    reg        ack_drive;  // 收字节时 ACK 相位要驱动的电平（0=ACK 拉低，1=NACK 释放）

    /* -------- 组合：当前 step 要发的字节 / 下一步的去向 -------- */
    function [7:0] byte_of_step;
        input [3:0] s;
        input       w;
        input [6:0] d;
        input [9:0] a;
        input [15:0] wd;
        begin
            byte_of_step = 8'h00;
            if (w) begin
                case (s)
                    4'd0: byte_of_step = {d, 1'b0};
                    4'd1: byte_of_step = {6'b0, a[9:8]};
                    4'd2: byte_of_step = a[7:0];
                    4'd3: byte_of_step = wd[15:8];
                    4'd4: byte_of_step = wd[7:0];
                    default: byte_of_step = 8'h00;
                endcase
            end else begin
                case (s)
                    4'd0: byte_of_step = {d, 1'b0};
                    4'd1: byte_of_step = {6'b0, a[9:8]};
                    4'd2: byte_of_step = a[7:0];
                    4'd3: byte_of_step = 8'h00;      // 重启
                    4'd4: byte_of_step = {d, 1'b1};
                    default: byte_of_step = 8'h00;
                endcase
            end
        end
    endfunction

    wire [3:0] step_n    = step + 4'd1;
    wire       n_is_stop = wr ? (step_n >= STEP_W_END) : (step_n >= STEP_R_END);
    wire       n_is_rst  = (!wr) && (step_n == 4'd3);
    wire       n_is_recv = (!wr) && (step_n >= 4'd5) && !n_is_stop;
    wire [7:0] next_byte = byte_of_step(step_n, wr, dev, addr, wdata);
    wire [7:0] cur_byte  = byte_of_step(step,   wr, dev, addr, wdata);

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            st <= S_IDLE; step <= 4'd0; ph <= 3'd0; bitc <= 4'd0; shreg <= 8'h00;
            hcnt <= 16'd0; scl_lo <= 1'b1; ack_drive <= 1'b1;
            scl_oe <= 1'b1; sda_oe <= 1'b1;
            rdata <= 16'h0000; done <= 1'b0; busy <= 1'b0; err <= 1'b0;
        end else begin
            done <= 1'b0;
            // ★ v53 新增：事务看门狗。调用方（ad7147_scan）若发现本笔事务超时，
            //   就拉高 abort 一拍，本模块立刻走 S_FIN 收尾（done 脉冲 + err=1 + 释放总线），
            //   这样**任何**异常都只会让这一笔失败，而不会把整条触摸链路永久卡死。
            if (abort && busy) begin
                st <= S_FIN; err <= 1'b1;
            end else case (st)

            // ------------------ 空闲 ------------------
            S_IDLE: begin
                scl_oe <= 1'b1; sda_oe <= 1'b1; busy <= 1'b0; err <= 1'b0;
                if (start) begin
                    busy <= 1'b1; err <= 1'b0; step <= 4'd0; ph <= 3'd0;
                    bitc <= 4'd0; hcnt <= 16'd0; scl_lo <= 1'b1;
                    st <= S_START;
                end
            end

            // ------------------ 起始/重启条件（★ v53：拆成 3 相）------------------
            //  ★ v53 修 bug（子代理复核 + 上板现象一致）：原来 ph0 **同一拍**释放 SCL 又拉低 SDA。
            //    开漏总线上 SCL 靠 4.7k 上拉缓慢上升（几百 ns~µs），而 SDA 被推挽快速拉低（~10 ns）
            //    ⇒ SCL 到达 VIH 时 SDA 早已为低 ⇒ 从机**看不到"SCL 高时 SDA 下降"**这个 START/
            //    重复起始条件 ⇒ 从机不回应/时序错乱。现在拆成三相：
            //      ph0：只释放 SCL（SDA 保持释放=高）→ 等 SCL 真正升到高
            //      ph1：SCL 已高，再拉低 SDA      → 这才是合法的 START
            //      ph2：拉低 SCL
            S_START: begin
                if (hcnt < HALF) hcnt <= hcnt + 16'd1;
                else begin
                    hcnt <= 16'd0;
                    case (ph)
                        3'd0: begin scl_oe <= 1'b1;                  ph <= 3'd1; end   // 先让 SCL 升到高
                        3'd1: begin sda_oe <= 1'b0;                  ph <= 3'd2; end   // SCL 高时拉低 SDA = START
                        3'd2: begin scl_oe <= 1'b0;                  ph <= 3'd3; end   // 拉低 SCL
                        default: begin
                            ph <= 3'd0; st <= S_SEND; bitc <= 4'd0; scl_lo <= 1'b0;
                            shreg <= cur_byte;
                        end
                    endcase
                end
            end

            // ------------------ 发一个字节 + 读 ACK ------------------
            S_SEND: begin
                if (!scl_lo) begin
                    scl_oe <= 1'b0;
                    // sda_oe 为“释放”电平（1=释放/高，0=拉低），所以发 1 位必须释放，
                    // 即 sda_oe = shreg[7]；原来的 ~shreg[7] 把整字节取反发出，
                    // 从机地址 0x58 会变成 0xA7 ⇒ 无器件应答（NACK）⇒ err_any=1。
                    sda_oe <= (bitc < 4'd8) ? shreg[7] : 1'b1;   // bitc==8 时释放 SDA 读 ACK
                    if (hcnt >= HALF) begin hcnt <= 16'd0; scl_oe <= 1'b1; scl_lo <= 1'b1; end
                    else                    hcnt <= hcnt + 16'd1;
                end else begin
                    if (hcnt >= HALF) begin
                        hcnt  <= 16'd0; scl_oe <= 1'b0; scl_lo <= 1'b0;
                        if (bitc < 4'd8) begin
                            shreg <= {shreg[6:0], 1'b1};
                            bitc  <= bitc + 4'd1;
                        end else begin
                            if (sda_in) err <= 1'b1;             // 从机 NACK
                            bitc <= 4'd0;
                            if (n_is_stop)      st <= S_STOP;
                            else if (n_is_rst) begin
                                // ★★ v53 修 bug：读事务的**重复起始（RESTART）**。
                                //   原来这个分支只 `st <= S_START`，**没有推进 step**，
                                //   于是 S_START 里 `shreg <= cur_byte` 取到的还是 step=2 的
                                //   "寄存器地址低字节"，重启后又把它当从机地址发了一遍
                                //   ⇒ 从机地址不匹配 ⇒ NACK。现象就是：写（无重启）正常、
                                //   读（Device ID / CDC）全部失败、且 err_any 被锁存。
                                //   修法：把 step 直接推到 4（"dev+R" 那一拍）。
                                step  <= 4'd4;
                                shreg <= byte_of_step(4'd4, wr, dev, addr, wdata);
                                st    <= S_START;
                            end
                            else begin
                                step  <= step_n;
                                shreg <= next_byte;
                                st    <= n_is_recv ? S_RECV : S_SEND;
                            end
                        end
                    end else hcnt <= hcnt + 16'd1;
                end
            end

            // ------------------ 收一个字节 + 回 ACK/NACK ------------------
            //  ★ v53 修 bug：ACK/NACK 原来靠寄存器 `ack_drive`（复位值 = 1 = NACK），而它只在
            //    收完一个字节之后才更新 ⇒ **第一个收到的字节被主机 NACK、最后一个反而 ACK**
            //    （整体 off-by-one，读 2 字节寄存器时高字节被 NACK、从机释放 SDA ⇒ 读成 0xFF）。
            //    现在直接按"这一字节是不是最后一个"组合判断：最后一个回 NACK，其余回 ACK。
            S_RECV: begin
                if (!scl_lo) begin
                    scl_oe <= 1'b0;
                    sda_oe <= (bitc == 4'd8) ? ((step >= 4'd6) ? 1'b1 : 1'b0) : 1'b1;
                    if (hcnt >= HALF) begin hcnt <= 16'd0; scl_oe <= 1'b1; scl_lo <= 1'b1; end
                    else                    hcnt <= hcnt + 16'd1;
                end else begin
                    if (hcnt >= HALF) begin
                        hcnt  <= 16'd0; scl_oe <= 1'b0; scl_lo <= 1'b0;
                        if (bitc < 4'd8) begin
                            shreg <= {shreg[6:0], sda_in};       // MSB 先来
                            bitc  <= bitc + 4'd1;
                        end else begin
                            // 8 位收完：step 5 = 高字节、step 6 = 低字节（最后一个）
                            if (step == 4'd5)      rdata[15:8] <= shreg;
                            else if (step == 4'd6) rdata[7:0]  <= shreg;
                            bitc      <= 4'd0;
                            if (n_is_stop) begin st <= S_STOP; end
                            else begin
                                step  <= step_n;
                                shreg <= 8'h00;
                                st    <= n_is_recv ? S_RECV : S_SEND;
                            end
                        end
                    end else hcnt <= hcnt + 16'd1;
                end
            end

            // ------------------ 停止条件（★ v53：三相各自计时）------------------
            //  ★ v53 修 bug（子代理复核 + 仿真：4 种版本 STOP 计数恒为 0）：
            //    原来写成"if (hcnt<HALF) 就无条件 scl_oe<=0; sda_oe<=0;"，而 ph0 释放 SCL 之后
            //    下一个周期又被这个分支拉低 ⇒ SCL 只高 **1 个时钟（20 ns）**，ph1 释放 SDA 时
            //    SCL 已经是低 ⇒ 根本没产生 STOP 条件（只是在 SCL 低时动了 SDA）。某些 I²C 从机
            //    要靠 STOP 才提交寄存器写，所以初始化写可能整批无效。
            //    修法：三相**各自**用 hcnt 计时，释放动作不再被下一拍覆盖：
            //      ph0：SCL/SDA 都拉低并保持 HALF（保证 SDA 先低）
            //      ph1：释放 SCL 并保持 HALF（SCL 升到高，SDA 仍低）
            //      ph2：SCL 保持高时释放 SDA = STOP，保持 HALF
            S_STOP: begin
                case (ph)
                    3'd0: begin
                        scl_oe <= 1'b0; sda_oe <= 1'b0;
                        if (hcnt >= HALF) begin hcnt <= 16'd0; ph <= 3'd1; end
                        else                    hcnt <= hcnt + 16'd1;
                    end
                    3'd1: begin
                        scl_oe <= 1'b1; sda_oe <= 1'b0;
                        if (hcnt >= HALF) begin hcnt <= 16'd0; ph <= 3'd2; end
                        else                    hcnt <= hcnt + 16'd1;
                    end
                    default: begin
                        scl_oe <= 1'b1; sda_oe <= 1'b1;      // SCL 高时释放 SDA ⇒ STOP
                        if (hcnt >= HALF) begin hcnt <= 16'd0; ph <= 3'd0; st <= S_FIN; end
                        else                    hcnt <= hcnt + 16'd1;
                    end
                endcase
            end

            // ------------------ 结束 ------------------
            S_FIN: begin
                done <= 1'b1; busy <= 1'b0;
                scl_oe <= 1'b1; sda_oe <= 1'b1;
                st <= S_IDLE;
            end

            default: st <= S_IDLE;
            endcase
        end
    end


endmodule
