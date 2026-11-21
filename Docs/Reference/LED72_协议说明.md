# 灯光配置协议 v2

72灯按G/C/E/A四列、每列18灯排列，索引=列×18+从上到下的行号。每灯2字节，共144字节：brightness 0..255；metadata bit0呼吸、bit2:1周期1/2/4/8秒、bit6:3相位0..15、bit7保留0。总亮度0..255独立传输。

静态PWM=floor(brightness×(master+1)/256)。呼吸以50Hz更新三角包络，再乘(envelope+1)/256。App采用相同数值公式做预览；手机与FPGA不承诺绝对初相位同步。曲谱模式7优先于自定义模式8，结束后恢复。

## BLE命令：单包不超过20字节，兼容MTU23

- LPB:<master两位HEX><client两位HEX><CRC16四位HEX>，开始；client=1..255。
- LP2:<9字节数据的12字符Base64><offset两位HEX><client两位HEX>，共16包，offset=0,9,...135。
- LPC:<client两位HEX>，提交；LM_GET查询。

ESP校验完整顺序、范围、期限和CRC后才上传FPGA；部分传输不影响已生效配置。CRC16=CCITT-FALSE，初值FFFF，多项式1021，仅对144数据字节计算。配置NVS v2记录148字节(version/master/144数据/记录CRC)，兼容旧v1 bitmap迁移，其他NVS命名空间保留。

## ESP32 ↔ FPGA

使用原AA、cmd、len、payload、XOR封装。新命令0x23，不覆盖原0x22触摸诊断。载荷固定8字节：[2,op,index,d0,d1,d2,tokenLE16]。

op0查询；op1开始(index=master，d0..2=0)；op2三字节数据(index=0,3,...141)；op3提交(index=144，d0/d1=CRC低/高字节，d2=0)。ESP根据真实cursor逐段推进、重试；完整提交在帧准备边界切换双银行。普通模式或曲谱接管可取消未完成上传。

0x8F回复16字节：version2,error,cursor,frameMaster,frameMode,flags,activeCRC16LE,activeToken16LE,revisionTag16LE,capabilities7,reserved0,queryToken16LE。

flags bit7为LP配置正常，6休眠，5自定义帧已捕获，4有效配置，3待提交，2上传中，1数据块待处理，0计算忙。revisionTag复用已提交token，不能当独立单调计数器。error=0正常、1格式、2忙、3令牌或无会话、4偏移或metadata、5提交CRC或长度。

## BLE真实状态快照

A7版本2为12个20字节片段：[A7,2,revisionLE16,part0..11,total12,13字节body片段,XOR]。body156字节：[result,flags,mode,master,client,caps7,activeCRC16LE,revisionTag16LE,reserved0,0,144字节profile]。

flags低至高：FPGA新鲜、配套协议已发现、已捕获且配置匹配、已保存、曲谱指引中、可演奏、配置内容已知；bit7保留0。result=0状态、1等待、2已应用、3格式/校验失败、4超时、5离线/休眠、6忙或取消、7已应用但NVS保存失败。

App收齐同一revision的12片、检查每片XOR、完整配置CRC与范围后才发布状态，拒绝混合或损坏快照。状态回显不会再次下发控制命令。旧A0/A1/A2/A4/A5/A6状态格式继续保留。

## 存储与原链路保护

同一1024×8双端口BSRAM：0..127是原触摸映射，只读；256..399和512..655为配置银行，768..839为PWM缓存。触摸端保留原一周期读取，灯阵端写地址有硬件保护。无新增BSRAM或DSP，不减少音频声部和模态。

AI采用version2/master/rules格式的受限JSON；规则只接受strings=GCEA子集、from/to=1..18、brightness=0..255、breath、period=1/2/4/8、phase/phaseStep=0..15。规则按序覆盖，未指定灯熄灭。解析后走与手动编辑相同的配置传输和真实确认流程，不执行模型返回的任意程序。
