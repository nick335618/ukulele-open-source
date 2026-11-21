# 验证入口

## 原交付验证

Logs保留对应App3.19.12／FPGA8D36的实际构建与回归记录，Reports/FPGA保留完整综合及布局布线报告。build-summary.json汇总资源与时序。固件／APK由该交付快照原样复制，不把归档操作当作新一次上板演奏。

- 608次单弦拨弦，72个品位、空弦、四音色、锁定／复音和不同品位的2/3/4弦组合。
- 真实同步CDC RAM与共享映射表的触摸扫描、稳定按住、松手消抖、单轮噪声、最高品位和异常ADC检查。
- 12个混合音色声部、512个样本，每样本1024时钟预算检查。
- 实际自动播放命令的四弦提交、MIDI／计数／来源反馈、四音色及变调夹口径。
- v1/v2诊断帧、97路电极边界、CRC、音频分析传输和只读性。
- App历史80项测试记录，公开快照79项通过／1项因未附个人导入MIDI而跳过，结果见public-app-tests.log。

## App回归

安装JDK17与Maven，进入Verification/App_Tests执行 `mvn test`。Robolectric依赖首次运行需正常下载；测试使用的compiled-classes.jar来自本快照构建，实际APK在Firmware/Android。若修改App，先重新构建并替换测试lib中的compiled-classes.jar与相应APK。

个人导入的《成都》MIDI不随开源分发。若自行拥有该测试文件，可以保存为Verification/Inputs/chengdu.mid，相应测试将自动执行。默认缺少时仅跳过这一项，其余合成／协议／界面测试不依赖该文件。

## RTL回归

安装Icarus Verilog，设置IVERILOG_HOME为iverilog与vvp所在目录，运行Verification/Tests/run_rtl.ps1。脚本从FPGA/Project/modal60读取工程文件列表，进入该工程根目录以保证ROM相对路径正确。日志输出到Verification/Logs。

## ESP32解析器主机测试

在Visual Studio x64开发者命令行运行Verification/Tests/run_gvlink.cmd，使用实际gvlink.cpp配合mock字节管道，验证协议及CRC拒收。

## 结果解释

以上分别证明所覆盖的代码路径、时序预算和构建结果。它们不替代电池容量测试、DAC模拟端测量、新制造PCB上板核对或所有品位的颤音手感校准。更多边界见Docs/RELEASE_NOTES.md。
