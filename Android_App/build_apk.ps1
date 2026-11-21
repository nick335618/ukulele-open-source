<#


  Command-line build script (no Gradle / Android Studio required).





  IMPORTANT: aapt2 cannot handle paths containing non-ASCII characters


  (e.g. a workspace folder named with Chinese characters). It fails with


  "failed to open directory". This script therefore mirrors the project into


  an ASCII-only staging directory, builds there, then copies the APK back.





  The source of truth is ALWAYS this project directory; the staging directory


  is a throwaway copy that is recreated on every build.





  Usage:


    powershell -ExecutionPolicy Bypass -File .\build_apk.ps1


    powershell -ExecutionPolicy Bypass -File .\build_apk.ps1 -VersionName 3.2.0 -VersionCode 10





  NOTE: keep this file either pure ASCII, or saved as UTF-8 *with* BOM.


  Windows PowerShell 5.1 parses scripts read from disk as ANSI when no BOM is


  present, which corrupts non-ASCII text and breaks parsing.


#>


param(


    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\AndroidDev\sdk' }),


    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\AndroidDev\jdk17\jdk-17.0.15+6' }),


    [string]$OutDir = (Join-Path $PSScriptRoot '.build_final'),


    [string]$VersionName = '3.19.12',


    [int]$VersionCode = 90,


    [string]$KeyStore = (Join-Path $PSScriptRoot 'signing/debug.keystore'),


    # Must be an ASCII-only path, otherwise aapt2 fails again.


    [string]$StageDir = ('D:\AndroidDev\ukulele-stage-' + [Guid]::NewGuid().ToString('N')),


    # Which native ABIs to package. Default is arm64-v8a only.
    #
    # Why: the project's only native dependency is OpenCV, and its .so files are huge
    # (arm64 22.9 MB, armeabi-v7a 15.1 MB, x86 41.5 MB, x86_64 56.6 MB uncompressed).
    # x86/x86_64 are emulator-only and add ~98 MB to the APK for zero benefit on a
    # real phone. Pass -Abis with more values (or 'all') to build for an emulator:
    #     -Abis all
    #     -Abis arm64-v8a,armeabi-v7a
    [string]$Abis = 'arm64-v8a',

    [switch]$LightweightVoice,


    # v3.12.0：官方 DeepSeek 通道的开发者 Key。只在构建暂存目录注入到
    # com/ukulele/monitor/ai/OfficialKey.java，不会写回源码目录；源码里始终是空占位。
    # 用法：.\build_apk.ps1 -OfficialDeepSeekKey 'sk-xxxx'
    # 安全提示：Key 一旦打进 APK 就可被逆向提取，生产环境建议改用自己的后端代理，
    # 或使用限额/可轮换的专用 Key。
    [string]$OfficialDeepSeekKey = '',


    [switch]$NoStage


)





$ErrorActionPreference = 'Stop'


$ProgressPreference = 'SilentlyContinue'





# Build tools write warnings to stderr. Under PowerShell 5.1 with


# $ErrorActionPreference = 'Stop' those become terminating NativeCommandErrors,


# which silently aborted the build in the past. Run external tools with


# 'Continue' and check $LASTEXITCODE explicitly instead.


function Invoke-Tool {


    param([string]$Label, [string]$Exe, [string[]]$Arguments)


    $previous = $ErrorActionPreference


    $ErrorActionPreference = 'Continue'


    try {


        $output = & $Exe @Arguments 2>&1


        $code = $LASTEXITCODE


    } finally {


        $ErrorActionPreference = $previous


    }


    foreach ($line in @($output)) {


        if ("$line" -ne '') { Write-Host "  [$Label] $line" }


    }


    if ($code -ne 0) { throw "$Label failed (exit $code)" }


}





function Test-NonAscii {


    param([string]$Text)


    foreach ($ch in $Text.ToCharArray()) { if ([int]$ch -gt 127) { return $true } }


    return $false


}





$sourceProject = $PSScriptRoot


$project = $sourceProject





if ($LightweightVoice -and $NoStage) { throw "LightweightVoice requires isolated staging" }
if (-not $NoStage -and ((Test-NonAscii $project) -or $LightweightVoice -or (-not [string]::IsNullOrWhiteSpace($OfficialDeepSeekKey)))) {


    Write-Host "Project path contains non-ASCII characters; staging to: $StageDir"


    if (Test-Path $StageDir) {


        if (!(Test-Path -LiteralPath (Join-Path $StageDir '.ukulele-build-stage'))) { throw 'Refusing to clear an unmarked staging directory' }
        $resolvedStage=[IO.Path]::GetFullPath($StageDir)
        if ($resolvedStage -eq [IO.Path]::GetPathRoot($resolvedStage)) { throw 'Invalid staging root' }
        Get-ChildItem -LiteralPath $resolvedStage -Force | Remove-Item -Recurse -Force


    } else {


        New-Item -ItemType Directory -Force -Path $StageDir | Out-Null


    }


    # Call robocopy directly. Do NOT use Start-Process -ArgumentList: it re-splits


    # paths that contain spaces (e.g. "android_app (2)").


    $previous = $ErrorActionPreference


    $ErrorActionPreference = 'Continue'


    try {


        # Filter staged inputs; original artwork and the full speech model stay in source.
        $stageExcludeNames = @('ukulele_icon_generated.png')
        if ($LightweightVoice) { $stageExcludeNames += 'model-cn.zip' }
        & robocopy.exe $project $StageDir /E /XD build dist /XF $stageExcludeNames /NFL /NDL /NJH /NJS /NP | Out-Null


        $copyCode = $LASTEXITCODE


    } finally { $ErrorActionPreference = $previous }


    # robocopy exit codes 0-7 mean success, >= 8 means failure.


    if ($copyCode -ge 8) { throw "robocopy staging failed (exit $copyCode)" }


    Set-Content -LiteralPath (Join-Path $StageDir '.ukulele-build-stage') -Value $sourceProject
    $project = $StageDir


}


# v3.12.0：官方 DeepSeek Key 注入（只写暂存副本，绝不回写源码树）。
if ($OfficialDeepSeekKey -and $OfficialDeepSeekKey.Trim() -ne '') {
    if ($NoStage -or ($project -eq $sourceProject)) {
        throw 'Refusing to inject the official DeepSeek key into the source tree; run without -NoStage so the key is written only to the staged copy.'
    }
    $aiDir = Join-Path $project 'app\src\main\java\com\ukulele\monitor\ai'
    if (!(Test-Path $aiDir)) { New-Item -ItemType Directory -Force -Path $aiDir | Out-Null }
    $keyFile = Join-Path $aiDir 'OfficialKey.java'
    $safeKey = $OfficialDeepSeekKey.Trim().Replace('\', '\\').Replace('"', '\"')
    $java = @"
package com.ukulele.monitor.ai;

/**
 * Official-channel developer key. Injected by build_apk.ps1 -OfficialDeepSeekKey
 * into a throwaway staging copy only; the checked-in source always keeps an empty
 * placeholder. Do not commit a real key to the source tree.
 */
public final class OfficialKey {
    public static final String DEVELOPER_KEY = "$safeKey";
    private OfficialKey() { }
}
"@
    # ASCII keeps javac happy (it rejects a UTF-8 BOM at the start of a .java file).
    Set-Content -LiteralPath $keyFile -Value $java -Encoding ASCII
    Write-Host '      official DeepSeek key injected into staged OfficialKey.java (not written back to source)'
}





$env:JAVA_HOME = $JavaHome


$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path


$bt = Join-Path $SdkRoot 'build-tools\34.0.0'


$androidJar = Join-Path $SdkRoot 'platforms\android-34\android.jar'


$res = Join-Path $project 'app\src\main\res'


$assets = Join-Path $project 'app\src\main\assets'


$manifest = Join-Path $project 'app\src\main\AndroidManifest.xml'


# app/libs is optional: it held the OpenCV jars, which were removed once the paper
# detection moved to pure Java. Treat a missing directory as "no extra jars" instead
# of aborting the build.
$java = Get-ChildItem -LiteralPath (Join-Path $project 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName
# The current paper detector is pure Java. Old copies of the OpenCV bundle may
# remain in the checkout; do not reintroduce them into this platform-only APK.
$needsOpenCv = @($java | Select-String -Pattern '^\s*import\s+org\.opencv\.').Count -gt 0
$libraryJars = @()
$libsDir = Join-Path $project 'app/libs'
if (Test-Path $libsDir) {
    $libraryJars = @(Get-ChildItem -LiteralPath $libsDir -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $needsOpenCv -or $_.Name -notmatch '^(opencv-|kotlin-stdlib|annotations-13\.0\.jar)' } |
        ForEach-Object FullName)
}





foreach ($tool in @(


    (Join-Path $bt 'aapt2.exe'), (Join-Path $bt 'd8.bat'),


    (Join-Path $bt 'zipalign.exe'), (Join-Path $bt 'apksigner.bat'),


    (Join-Path $JavaHome 'bin\javac.exe'), (Join-Path $JavaHome 'bin\jar.exe'),


    $androidJar, $manifest, $res)) {


    if (-not (Test-Path $tool)) { throw "Missing build dependency: $tool" }


}


if (-not $java) { throw "No Java sources found under $project\app\src\main\java" }





$resolvedOut=[IO.Path]::GetFullPath($OutDir)
$resolvedKey=[IO.Path]::GetFullPath($KeyStore)
if ($resolvedOut -eq [IO.Path]::GetPathRoot($resolvedOut) -or $resolvedOut.TrimEnd('\','/') -eq [IO.Path]::GetFullPath($project).TrimEnd('\','/')) { throw 'Invalid build output root' }
if ($resolvedKey.StartsWith($resolvedOut.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'The signing key must be outside the removable build output' }
if ((Test-Path -LiteralPath $resolvedOut) -and !(Test-Path -LiteralPath (Join-Path $resolvedOut '.ukulele-build-output'))) { throw 'Refusing to remove an unmarked build output directory; choose a fresh -OutDir' }
$OutDir=$resolvedOut
$classesDir = Join-Path $OutDir 'classes'


$genDir = Join-Path $OutDir 'gen'


$dexDir = Join-Path $OutDir 'dex'


foreach ($dir in @($OutDir, $genDir, $classesDir, $dexDir)) {


    # d8 requires the output directory to already exist, and a stale classes.dex


    # must not survive from a previous build. Wipe every intermediate directory.


    if (Test-Path $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }


    New-Item -ItemType Directory -Force -Path $dir | Out-Null


}





Set-Content -LiteralPath (Join-Path $OutDir '.ukulele-build-output') -Value $sourceProject
Write-Host '[1/6] aapt2 compile resources'


Invoke-Tool 'aapt2 compile' (Join-Path $bt 'aapt2.exe') @(


    'compile', '--dir', $res, '-o', (Join-Path $OutDir 'resources.zip'))





Write-Host '[2/6] aapt2 link resources and manifest'


Invoke-Tool 'aapt2 link' (Join-Path $bt 'aapt2.exe') @(


    'link', '-o', (Join-Path $OutDir 'base.apk'), '-I', $androidJar,


    '--manifest', $manifest, '--java', $genDir, '-A', $assets,


    '--auto-add-overlay', '--min-sdk-version', '26', '--target-sdk-version', '34',


    '--version-code', "$VersionCode", '--version-name', $VersionName,


    (Join-Path $OutDir 'resources.zip'))





Write-Host '[3/6] javac compile Java sources'


# Do NOT pass -source/-target 8: on JDK 17 that combination makes javac emit a


# "bootstrap class path not set" warning and, worse, produced zero class files.


# Bytecode level is lowered by d8 (--min-api 26) instead.


$rJava = Join-Path $genDir 'com\ukulele\monitor\R.java'


Invoke-Tool 'javac' (Join-Path $JavaHome 'bin\javac.exe') (@(


    '-encoding', 'UTF-8', '-nowarn', '-cp', ((@($androidJar) + $libraryJars) -join ';'), '-d', $classesDir) + $java + @($rJava))





$classFiles = Get-ChildItem -LiteralPath $classesDir -Recurse -Filter '*.class' | ForEach-Object FullName


if (-not $classFiles) { throw 'javac produced no class files' }


Write-Host "      produced $($classFiles.Count) class files"





Write-Host '[4/6] d8 dex conversion'


# Pass a single class archive; Windows batch argument limits truncate long source trees.


$inputJar = Join-Path $OutDir 'compiled-classes.jar'


Invoke-Tool 'jar classes' (Join-Path $JavaHome 'bin\jar.exe') @('cf', $inputJar, '-C', $classesDir, '.')


# Bundled JNA 5.18 requires a current D8; SDK 34's D8 8.2 crashes on its callbacks.
$r8Jar = Join-Path $project 'tools/r8/r8-8.13.17.jar'
if (!(Test-Path -LiteralPath $r8Jar)) { throw 'Missing pinned R8/D8 8.13.17 runtime' }
Invoke-Tool 'd8' (Join-Path $JavaHome 'bin/java.exe') (@('-cp', $r8Jar, 'com.android.tools.r8.D8',
    '--lib', $androidJar, '--min-api', '26', '--output', $dexDir, $inputJar) + $libraryJars)





$dex = Join-Path $dexDir 'classes.dex'


if (-not (Test-Path $dex)) { throw 'd8 produced no classes.dex' }





Push-Location $dexDir


try {


    Invoke-Tool 'jar' (Join-Path $JavaHome 'bin\jar.exe') @('uf', (Join-Path $OutDir 'base.apk'), 'classes.dex')


} finally { Pop-Location }





$nativeRoot = Join-Path $project 'app/src/main/jniLibs'
if ((Test-Path $nativeRoot) -and ($needsOpenCv -or
    @(Get-ChildItem -LiteralPath $nativeRoot -Recurse -File |
      Where-Object { $_.Name -notin @('libopencv_java4.so','libc++_shared.so') }).Count -gt 0)) {
 $nativeStage = Join-Path $OutDir 'native/lib'
 New-Item -ItemType Directory -Force -Path $nativeStage | Out-Null
 # Package only the requested ABIs (see -Abis). 'all' keeps every directory.
 $wantAll = ($Abis -eq 'all')
 $wanted = @()
 if (-not $wantAll) { $wanted = $Abis -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } }
 $copied = @()
 $skipped = @()
 foreach ($abiDir in Get-ChildItem -LiteralPath $nativeRoot -Directory) {
   if ($wantAll -or ($wanted -contains $abiDir.Name)) {
     $abiTarget = Join-Path $nativeStage $abiDir.Name
     New-Item -ItemType Directory -Force -Path $abiTarget | Out-Null
     Get-ChildItem -LiteralPath $abiDir.FullName -File |
       Where-Object { $needsOpenCv -or $_.Name -notin @('libopencv_java4.so','libc++_shared.so') } |
       Copy-Item -Destination $abiTarget -Force
     $copied += $abiDir.Name
   } else {
     $skipped += $abiDir.Name
   }
 }
 if ($copied.Count -eq 0) { throw "No native ABI matched -Abis '$Abis' (available: $((Get-ChildItem -LiteralPath $nativeRoot -Directory | ForEach-Object Name) -join ', '))" }
 $libBytes = (Get-ChildItem -LiteralPath $nativeStage -Recurse -File | Measure-Object Length -Sum).Sum
 Write-Host ("      native ABIs: {0}  ({1:N1} MB)" -f ($copied -join ', '), ($libBytes / 1MB))
 if ($skipped.Count -gt 0) { Write-Host ("      skipped    : {0}" -f ($skipped -join ', ')) }
 Push-Location (Split-Path $nativeStage -Parent)
 try { Invoke-Tool 'jar native libraries' (Join-Path $JavaHome 'bin/jar.exe') @('uf', (Join-Path $OutDir 'base.apk'), 'lib') } finally { Pop-Location }
}
# Windows aapt2 + jar can leave backslashes in LOCAL ZIP names while the
# central directory uses '/'. Android AssetManager then cannot open the asset.
# Recreate entries with one canonical name before alignment and signing.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkBeforeAlign = Join-Path $OutDir 'base.apk'
$canonicalApk = Join-Path $OutDir 'canonical.apk'
$zipIn = [IO.Compression.ZipFile]::OpenRead($apkBeforeAlign)
$zipOut = [IO.Compression.ZipFile]::Open($canonicalApk, [IO.Compression.ZipArchiveMode]::Create)
$entryNames = New-Object 'System.Collections.Generic.HashSet[string]'
try {
 foreach ($entry in $zipIn.Entries) {
  $name = $entry.FullName.Replace('\', '/')
  if ($name.StartsWith('/') -or ($name.Split('/') -contains '..')) { throw 'Unsafe APK entry name' }
  if (-not $entryNames.Add($name)) { throw "Duplicate APK entry: $name" }
  $level = [IO.Compression.CompressionLevel]::Optimal
  if ($name -eq 'resources.arsc' -or $name.EndsWith('.zip')) {
   $level = [IO.Compression.CompressionLevel]::NoCompression
  }
  $written = $zipOut.CreateEntry($name, $level)
  if (-not $name.EndsWith('/')) {
   $inputStream = $entry.Open()
   $outputStream = $written.Open()
   try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
  }
 }
} finally { $zipOut.Dispose(); $zipIn.Dispose() }
Move-Item -LiteralPath $canonicalApk -Destination $apkBeforeAlign -Force
# .NET Framework represents NoCompression as deflate level 0. Android requires
# resources.arsc to use the actual STORED ZIP method, so let JDK jar set method 0.
$storedStage = Join-Path $OutDir 'stored-entries'
New-Item -ItemType Directory -Force -Path $storedStage | Out-Null
$storeNames = @()
$canonicalRead = [IO.Compression.ZipFile]::OpenRead($apkBeforeAlign)
try {
 foreach ($entry in $canonicalRead.Entries) {
  if ($entry.FullName -eq 'resources.arsc' -or $entry.FullName.EndsWith('.zip')) {
   $destination = Join-Path $storedStage $entry.FullName
   New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
   $from = $entry.Open()
   $to = [IO.File]::Create($destination)
   try { $from.CopyTo($to) } finally { $to.Dispose(); $from.Dispose() }
   $storeNames += $entry.FullName
  }
 }
} finally { $canonicalRead.Dispose() }
if ($storeNames.Count -gt 0) {
 Push-Location $storedStage
 try { Invoke-Tool 'jar stored resource/model entries' (Join-Path $JavaHome 'bin/jar.exe') (@('uf0', $apkBeforeAlign) + $storeNames) }
 finally { Pop-Location }
}
Write-Host ("      canonical APK entry names: {0}" -f $entryNames.Count)
Write-Host '[5/6] zipalign'


$aligned = Join-Path $OutDir 'aligned.apk'


Invoke-Tool 'zipalign' (Join-Path $bt 'zipalign.exe') @('-f', '4', (Join-Path $OutDir 'base.apk'), $aligned)





Write-Host '[6/6] sign and verify'


$ks = $KeyStore


if (!(Test-Path $ks)) {


    $ksDir = Split-Path $ks -Parent


    if ($ksDir -and !(Test-Path $ksDir)) { New-Item -ItemType Directory -Force -Path $ksDir | Out-Null }


    Invoke-Tool 'keytool' (Join-Path $JavaHome 'bin\keytool.exe') @(


        '-genkeypair', '-keystore', $ks, '-storepass', 'android', '-keypass', 'android',


        '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',


        '-dname', 'CN=Android Debug,O=Android,C=US')


}


$apk = Join-Path $OutDir 'ukulele-monitor-debug.apk'


Invoke-Tool 'apksigner sign' (Join-Path $bt 'apksigner.bat') @(


    'sign', '--ks', $ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',


    '--out', $apk, $aligned)


Invoke-Tool 'apksigner verify' (Join-Path $bt 'apksigner.bat') @('verify', '--verbose', $apk)





# Copy the APK back into the project tree so it is easy to find.


$deliverDir = Join-Path $sourceProject 'build\outputs\apk\debug'


New-Item -ItemType Directory -Force -Path $deliverDir | Out-Null


$deliver = Join-Path $deliverDir ("UkuleleStudio-v{0}-debug.apk" -f $VersionName)


Copy-Item -LiteralPath $apk -Destination $deliver -Force





$distDir = Join-Path $sourceProject 'dist'


New-Item -ItemType Directory -Force -Path $distDir | Out-Null


Copy-Item -LiteralPath $apk -Destination (Join-Path $distDir ("UkuleleStudio-v{0}-debug.apk" -f $VersionName)) -Force





$info = Get-Item $apk


Write-Host ''


Write-Host ("BUILD OK  v{0} (versionCode {1})" -f $VersionName, $VersionCode)


Write-Host ("APK      : {0}  ({1:N0} bytes)" -f $apk, $info.Length)


Write-Host ("In project: {0}" -f $deliver)



