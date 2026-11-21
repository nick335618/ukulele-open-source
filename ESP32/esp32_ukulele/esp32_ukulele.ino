// ============================================================
// Arduino IDE 入口文件（文件夹名必须与本文件同名）
//   真正的实现分布在同一目录的 .cpp 中：
//     main_app.cpp  setup()/loop() + 串口控制台
//     gvlink.cpp    FPGA SPI 帧校验、命令与实际状态回读
//     fpgaspi.cpp   SPI 字节队列，GPIO18/19/23/5
//     screen_link.cpp UART1 串口屏，GPIO22/21，115200
//     song_player.cpp 曲谱文件、倍速播放与多音灯光指引
//     ble_link.cpp  BLE 4.2 服务
//   两套音色模型与实时音频计算在 FPGA 中；ESP32 不下载音色整表。
//   旧 UART 工程、旧 CDC 标定和 ESP32 FFT 已移到 ESP32/Legacy_Archive。
// ============================================================
#include <Arduino.h>
