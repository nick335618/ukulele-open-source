param([string]$IcarusBin=$env:IVERILOG_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$project=Join-Path (Split-Path $root -Parent) 'FPGA\Project\modal60'
[xml]$config=Get-Content -LiteralPath (Join-Path $project 'gv_host.gprj')
$sources=@($config.Project.FileList.File|Where-Object {$_.type -eq 'file.verilog' -and $_.enable -eq '1'}|ForEach-Object {Join-Path $project $_.path})
$bin=$IcarusBin
if(!$bin){$bin=Split-Path (Get-Command iverilog -ErrorAction Stop).Source -Parent}
Push-Location $project
try {
 foreach($name in @('autoplay_feedback','pad_diagnostic','string_routing','poly_audio_budget','touch_scan')) {
  & "$bin\iverilog.exe" -g2012 -I "$project\src" -s "${name}_tb" -o "$root\Tests\$name.vvp" "$root\Tests\${name}_tb.sv" @sources *> "$root\Logs\$name-compile.log"
  if($LASTEXITCODE -ne 0){throw "$name compile failed"}
  & "$bin\vvp.exe" "$root\Tests\$name.vvp" *> "$root\Logs\$name.log"
  if($LASTEXITCODE -ne 0){throw "$name failed"}
 }
} finally {Pop-Location}
