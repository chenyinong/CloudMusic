# DLNA 协议自测：在电脑上跑真实的 DLNA 客户端代码
#
# 用法: powershell -ExecutionPolicy Bypass -File run-dlna-test.ps1
#
# 做法：
#   1. 起一个假电视（mock-renderer.mjs），它会应答 SSDP、接受 SOAP
#   2. 把 App 里的 DLNA 客户端代码原样编译到 JVM 上跑一遍
#   3. 检查假电视收到的报文对不对
#
# 注意：编译用的是 Gradle 缓存里的 kotlin-compiler-embeddable，不用联网。

$ErrorActionPreference = "Continue"
$test = $PSScriptRoot
$proj = (Resolve-Path (Join-Path $test "..\..")).Path

$tc = "C:\Users\chen\androidtoolchain"
$jdk = ""
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
  $jdk = $env:JAVA_HOME
} elseif (Test-Path "$tc\jdk\bin\java.exe") {
  $jdk = "$tc\jdk"
} else {
  foreach ($p in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
    if (Test-Path "$p\bin\java.exe") { $jdk = $p; break }
  }
}
if (-not $jdk) { Write-Error "JDK not found"; exit 1 }

$cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
function Find-Jar($group, $name) {
  (Get-ChildItem -Path "$cache\$group\$name" -Recurse -Filter "$name-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1).FullName
}
$compiler = Find-Jar "org.jetbrains.kotlin" "kotlin-compiler-embeddable"
$stdlib = Find-Jar "org.jetbrains.kotlin" "kotlin-stdlib"
$trove = Find-Jar "org.jetbrains.intellij.deps" "trove4j"
$scriptRt = Find-Jar "org.jetbrains.kotlin" "kotlin-script-runtime"
$daemon = Find-Jar "org.jetbrains.kotlin" "kotlin-daemon-embeddable"
$reflect = Find-Jar "org.jetbrains.kotlin" "kotlin-reflect"
$annotations = Find-Jar "org.jetbrains" "annotations"
if (-not $compiler -or -not $stdlib) { Write-Error "kotlin compiler / stdlib not found in gradle cache"; exit 1 }
Write-Host "compiler: $compiler"

$compilerCp = (@($compiler, $stdlib, $trove, $scriptRt, $daemon, $reflect, $annotations) | Where-Object { $_ }) -join ";"

$out = Join-Path $test "classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @(
  (Join-Path $test "Harness.kt"),
  (Join-Path $test "stubs\android\util\Log.kt"),
  (Join-Path $test "stubs\android\content\Context.kt"),
  (Join-Path $test "stubs\android\net\wifi\WifiManager.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\LanHttp.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\Xml.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\DlnaClient.kt")
)

Write-Host "Compiling..."
& "$jdk\bin\java.exe" -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
  -no-stdlib -nowarn -cp $stdlib -d $out @sources 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "compile failed"; exit 1 }

Write-Host "Starting mock renderer..."
$mock = Start-Process -FilePath "node" -ArgumentList (Join-Path $test "mock-renderer.mjs") `
  -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $test "mock.log") -RedirectStandardError (Join-Path $test "mock.err")
Start-Sleep -Seconds 2

try {
  Write-Host "Running harness..."
  & "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$stdlib" HarnessKt (Join-Path $test "out")
  $code = $LASTEXITCODE
} finally {
  Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "--- mock log ---"
if (Test-Path (Join-Path $test "mock.log")) { Get-Content (Join-Path $test "mock.log") | ForEach-Object { Write-Host $_ } }

# ================= 服务端（手机当渲染器）=================
Write-Host ""
Write-Host "==================== 服务端自测 ===================="

$out2 = Join-Path $test "classes-server"
if (Test-Path $out2) { Remove-Item $out2 -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out2 | Out-Null

$okhttp = Find-Jar "com.squareup.okhttp3" "okhttp"
$okio = Find-Jar "com.squareup.okio" "okio-jvm"
if (-not $okhttp -or -not $okio) { Write-Host "okhttp / okio not found in gradle cache"; exit 1 }

$serverSources = @(
  (Join-Path $test "ServerHarness.kt"),
  (Join-Path $test "stubs\android\util\Log.kt"),
  (Join-Path $test "stubs\android\content\Context.kt"),
  (Join-Path $test "stubs\android\net\wifi\WifiManager.kt"),
  (Join-Path $test "stubs\com\cloudmusic\app\data\Stubs.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\LanHttp.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\Xml.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\SsdpResponder.kt"),
  (Join-Path $proj "app\src\main\java\com\cloudmusic\app\data\dlna\DlnaServer.kt")
)

Write-Host "Compiling server harness..."
& "$jdk\bin\java.exe" -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
  -no-stdlib -nowarn -cp "$stdlib;$okhttp;$okio" -d $out2 @serverSources 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "compile failed"; exit 1 }

Write-Host "Running server harness..."
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$out2;$stdlib;$okhttp;$okio" ServerHarnessKt
if ($LASTEXITCODE -ne 0) { $code = $LASTEXITCODE }

exit $code
