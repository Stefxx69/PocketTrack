# Downloads a portable Android build toolchain into .toolchain\ (no Android Studio, no installers,
# nothing written outside this repo). Delete the folder to uninstall.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-android.ps1
#
# Downloads ~500 MB (JDK 17, Android command-line tools, Gradle 8.9) and ends up ~1.5 GB on disk.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is 10x slower with the progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root ".toolchain"
$dl = Join-Path $tc "downloads"
New-Item -ItemType Directory -Force $dl | Out-Null

$jdkUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
$sdkToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$gradleUrl = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"

function Get-Zip($url, $name) {
    $file = Join-Path $dl $name
    if (-not (Test-Path $file)) {
        Write-Host "Downloading $name ..."
        Invoke-WebRequest -Uri $url -OutFile "$file.part" -UseBasicParsing
        Move-Item "$file.part" $file
    }
    return $file
}

# --- JDK 17 ---
$jdkDir = Join-Path $tc "jdk"
if (-not (Test-Path (Join-Path $jdkDir "bin\java.exe"))) {
    $zip = Get-Zip $jdkUrl "jdk17.zip"
    $tmp = Join-Path $tc "jdk-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $zip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    Move-Item $inner.FullName $jdkDir
    Remove-Item -Recurse -Force $tmp
}

# --- Gradle ---
$gradleDir = Join-Path $tc "gradle"
if (-not (Test-Path (Join-Path $gradleDir "bin\gradle.bat"))) {
    $zip = Get-Zip $gradleUrl "gradle-8.9.zip"
    $tmp = Join-Path $tc "gradle-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $zip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (Test-Path $gradleDir) { Remove-Item -Recurse -Force $gradleDir }
    Move-Item $inner.FullName $gradleDir
    Remove-Item -Recurse -Force $tmp
}

# --- Android SDK (command-line tools must live in cmdline-tools\latest) ---
$sdk = Join-Path $tc "android-sdk"
$sdkManager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    $zip = Get-Zip $sdkToolsUrl "cmdline-tools.zip"
    $tmp = Join-Path $tc "sdk-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $zip $tmp
    New-Item -ItemType Directory -Force (Join-Path $sdk "cmdline-tools") | Out-Null
    $latest = Join-Path $sdk "cmdline-tools\latest"
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item (Join-Path $tmp "cmdline-tools") $latest
    Remove-Item -Recurse -Force $tmp
}

$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

Write-Host "Android SDK License Agreement: https://developer.android.com/studio/terms"
Write-Host "Accepting Android SDK licenses ..."
# PowerShell pipes don't reach a .bat's stdin reliably, so feed the answers through cmd.
$yesFile = Join-Path $tc "yes.txt"
Set-Content -Encoding ascii $yesFile (("y`r`n") * 30)
cmd /c "`"$sdkManager`" --sdk_root=`"$sdk`" --licenses < `"$yesFile`"" | Out-Null

Write-Host "Installing platform-tools, android-35, build-tools 34.0.0 ..."
cmd /c "`"$sdkManager`" --sdk_root=`"$sdk`" platform-tools platforms;android-35 build-tools;34.0.0 < `"$yesFile`""
Remove-Item $yesFile
if (-not (Test-Path (Join-Path $sdk "platforms\android-35"))) { throw "sdkmanager failed to install the SDK packages" }

Remove-Item -Recurse -Force $dl
Write-Host ""
Write-Host "Toolchain ready in $tc"
Write-Host "Build the app with:  powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1"
