param([Parameter(Mandatory=$true)][string]$GowinShell)
$ErrorActionPreference='Stop'
$project=Join-Path $PSScriptRoot 'Project\modal60'
[xml]$config=Get-Content -LiteralPath (Join-Path $project 'gv_host.gprj')
if($config.Project.Device.pn -ne 'GW5A-LV25MG121NC1/I0'){throw 'Unexpected FPGA target'}
if(!(Test-Path -LiteralPath $GowinShell)){throw 'Gowin shell not found'}
Push-Location $project
try { & $GowinShell 'build_spi_final.tcl';if($LASTEXITCODE -ne 0){throw 'FPGA build failed'} }
finally { Pop-Location }
