param([string]$ArduinoCli='arduino-cli',[string]$Fqbn='esp32:esp32:esp32')
$ErrorActionPreference='Stop'
$sketch=Join-Path $PSScriptRoot 'esp32_ukulele'
$output=Join-Path (Split-Path $PSScriptRoot -Parent) 'Generated\ESP32'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $ArduinoCli compile --fqbn $Fqbn --output-dir $output $sketch
if($LASTEXITCODE -ne 0){throw 'ESP32 build failed'}
