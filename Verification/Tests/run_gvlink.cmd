@echo off
cd /d "%~dp0"
where cl >nul 2>&1
if errorlevel 1 (echo Run from the Visual Studio x64 Developer Command Prompt. & exit /b 1)
cl /nologo /utf-8 /EHsc /std:c++17 /I "%~dp0mock-arduino" /I "%~dp0..\..\ESP32\esp32_ukulele" "%~dp0gvlink_frames.cpp" "%~dp0..\..\ESP32\esp32_ukulele\gvlink.cpp" /Fe:gvlink_frames.exe
if errorlevel 1 exit /b 1
gvlink_frames.exe
