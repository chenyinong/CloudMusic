# 一键编译 APK（无需 Android Studio 命令行也能用）
# 用法: powershell -ExecutionPolicy Bypass -File build-apk.ps1
$ErrorActionPreference = "Continue"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc = "C:\Users\chen\androidtoolchain"

if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
  $jdk = $env:JAVA_HOME
} elseif (Test-Path "$tc\jdk\bin\java.exe") {
  $jdk = "$tc\jdk"
} else {
  $jdk = ""
  foreach ($p in @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio\jre",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
    if (Test-Path "$p\bin\java.exe") { $jdk = $p; break }
  }
}
if (-not $jdk) { Write-Error "JDK not found. Install Android Studio or set JAVA_HOME."; exit 1 }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;" + $env:PATH
Write-Host "JDK: $jdk"

$sdk = ""
if ($env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
elseif (Test-Path "$tc\android-sdk") { $sdk = "$tc\android-sdk" }
elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $sdk) { Write-Error "Android SDK not found."; exit 1 }
Write-Host "SDK: $sdk"
[System.IO.File]::WriteAllText("$proj\local.properties", "sdk.dir=$($sdk -replace '\\','\\')`n")

Set-Location $proj
if (Test-Path "$tc\gradle\bin\gradle.bat") { $g = "$tc\gradle\bin\gradle.bat" }
elseif (Test-Path "$proj\gradlew.bat") { $g = "$proj\gradlew.bat" }
else { Write-Error "gradle not found."; exit 1 }

Write-Host "Building..."
& $g assembleDebug 2>&1 | ForEach-Object { Write-Host $_ }
$code = $LASTEXITCODE
if ($code -eq 0) {
  $apk = Get-ChildItem "$proj\app\build\outputs\apk\debug" -Filter "*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($apk) {
    Copy-Item $apk.FullName "$proj\CloudMusic-debug.apk" -Force
    Write-Host ""
    Write-Host "APK ready: $proj\CloudMusic-debug.apk"
    Write-Host ("Size: {0:N1} MB" -f ((Get-Item "$proj\CloudMusic-debug.apk").Length / 1MB))
  }
} else {
  Write-Host "Build failed, exit=$code"
}
exit $code