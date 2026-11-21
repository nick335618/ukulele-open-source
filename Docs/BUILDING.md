# 编译、安装与烧录

## 配套版本

FPGA器件为GW5A-LV25MG121NC1/I0，主时钟50 MHz。App 3.19.12，FPGA预编译位流UserCode 8D36。工程目录应放在英文路径中。

## FPGA

安装Gowin V1.9.11.03 Education，打开 `FPGA/Project/modal60/gv_host.gprj`，完整运行综合及布局布线。也可以在仓库根目录执行：

```powershell
.\FPGA\Build.ps1 -GowinShell 'C:\Gowin\IDE\bin\gw_sh.exe'
```

脚本固定进入本仓库的modal60工程，不使用历史fpga目录。ROM路径相对工程根目录，保留整个src目录。输出在工程的impl目录；预编译固件在Firmware/FPGA/gv_host.fs。

编译结束应检查资源、50 MHz Setup/Hold、ROM初始化及警告；不能仅依据.fs存在判断通过。本快照的原始报告位于Reports/FPGA。SDC当前主要为单主时钟约束，不应将报告扩展解释为全部板级I/O均已完成时序签核。

## ESP32

Arduino IDE安装Espressif ESP32 core **2.0.14**，打开 `ESP32/esp32_ukulele/esp32_ukulele.ino`，选择ESP32 Dev Module。或使用Arduino CLI：

```powershell
.\ESP32\Build.ps1 -ArduinoCli 'C:\tools\arduino-cli.exe'
```

FPGA SPI为GPIO18/19/23/5；串口屏UART1为GPIO22 TX、GPIO21 RX、115200。新电源板光电控制I²C为GPIO25/26，电源监测I²C为GPIO32/33；具体对应线序以代码及原理图为准。

下载工具的固件地址：

| 地址 | Firmware/ESP32中的文件 |
|---|---|
| 0x1000 | esp32_ukulele.ino.bootloader.bin |
| 0x8000 | esp32_ukulele.ino.partitions.bin |
| 0xe000 | boot_app0.bin |
| 0x10000 | esp32_ukulele.ino.bin |

正常升级保留NVS，无需全片擦除。首次使用已有数据可能影响音量、门限等设置，界面以真实回读为准。

## Android App

预编译APK在Firmware/Android及Releases。源代码完整保留四种ABI的本地库、离线语音模型及依赖许可。

安装JDK17、Android SDK 34、Build Tools 34.0.0，在Android_App下运行：

```powershell
.\build_apk.ps1 -SdkRoot 'C:\Android\sdk' -JavaHome 'C:\Java\jdk17' `
  -StageDir 'C:\uke_build\stage' -OutDir 'C:\uke_build\apk' `
  -KeyStore 'C:\uke_signing\debug.keystore'
```

脚本提供不依赖Gradle的构建链，会在需要时创建本机调试签名。请自行保管签名文件；仓库不发布团队签名私钥。自己编译的APK可能无法覆盖签名不同的现有安装包，卸载前先导出自己的曲谱和录奏。

OfficialKey.java保留空占位。联网AI请在App配置个人通道，本地控制与曲库功能不依赖开发者密钥。构建脚本的可选密钥注入参数仅供自行部署，含真实密钥的APK也能被逆向，不要公开上传此类构建。

## 串口屏

使用USART HMI/TJC编辑器打开Screen中的 `UkeStudio_BackFix.HMI`，屏幕分辨率320×240；已有匹配TFT也在该目录。UTF-8/GBK事件脚本另行保留便于审阅，实际屏幕工程中的事件为准，历史TXT脚本不应无差别覆盖新工程。

## 硬件与机械

在嘉立创EDA专业版导入Hardware/Electrical/JLCEDA中的epro2。该文件含六套PCB／原理图及工程库，打板文件请从当前工程导出并检查。

SolidWorks装配和零件位于Hardware/Mechanical/Uke-V2，保留原文件夹关系。原始RAR同时在Releases。整体使用3D打印制作，但材料、填充率、支撑和公差应按自己的工艺设置；没有把原生SLDPRT冒称为可直接切片的STL。
