# Builds the Android app with the portable toolchain from tools\setup-android.ps1.
#
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1            # build dist\PocketTrack.apk
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Install   # ...and install via adb
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Tasks lintDebug,assembleDebug
#
# For -Install, enable Developer options + USB debugging (or Wireless debugging and
# `adb pair` / `adb connect` first) on the phone.
#
# Always run Gradle through this script: it maps the repo to the same short drive letter every
# time, and mixing paths (e.g. P:\ and the real path) corrupts Gradle's incremental build cache.

param([switch]$Install, [string[]]$Tasks = @("assembleDebug"))

$ErrorActionPreference = "Stop"
# `powershell -File` passes "a,b" as one string; split it ourselves.
$Tasks = @($Tasks | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$realRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $realRoot ".toolchain\gradle\bin\gradle.bat"))) {
    throw "Toolchain missing - run tools\setup-android.ps1 first"
}

# Android's aapt2 breaks on paths longer than 260 characters, which Gradle's cache easily hits
# in a deep folder. Map the repo to a spare drive letter for the build (removed afterwards).
$drive = $null
# A P: left over from an earlier (interrupted) build of this repo: just remove it and map it again,
# so the build always runs from the same path.
$existing = subst | Where-Object { $_ -like "P:\: => $realRoot" }
if ($existing) { subst P: /D | Out-Null }
if ($realRoot.Length -gt 40) {
    # Prefer P: every time so Gradle's cached paths stay valid between builds.
    $used = (Get-PSDrive -PSProvider FileSystem).Name
    $letter = "PQRSTUVWXYZ".ToCharArray() | Where-Object { $used -notcontains "$_" } | Select-Object -First 1
    if ($letter -and $letter -ne 'P') {
        Write-Warning "P: is in use, building via ${letter}: - the first build will be a full rebuild"
        Remove-Item -Recurse -Force (Join-Path $realRoot "android\app\build") -ErrorAction SilentlyContinue
    }
    if ($letter) {
        subst "${letter}:" "$realRoot"
        if ($LASTEXITCODE -eq 0) { $drive = "${letter}:" }
    }
}
$root = if ($drive) { "$drive\" } else { $realRoot }

$tc = Join-Path $root ".toolchain"
$sdk = Join-Path $tc "android-sdk"
$gradle = Join-Path $tc "gradle\bin\gradle.bat"
$env:JAVA_HOME = Join-Path $tc "jdk"
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = Join-Path $tc "gradle-home"   # keep Gradle's cache inside the repo too

$android = Join-Path $root "android"
# Gradle wants forward slashes / escaped backslashes in local.properties.
"sdk.dir=" + ($sdk -replace '\\', '/') | Set-Content -Encoding ascii (Join-Path $android "local.properties")

Push-Location $android
try {
    & $gradle --no-daemon @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
    Remove-Item (Join-Path $android "local.properties") -ErrorAction SilentlyContinue
    if ($drive) {
        # Gradle's daemon can hold the drive for a moment after the build; retry briefly.
        for ($i = 0; $i -lt 10; $i++) {
            subst $drive /D 2>$null
            if (-not (Test-Path "$drive\")) { break }
            Start-Sleep -Milliseconds 500
        }
    }
}

$android = Join-Path $realRoot "android"
$sdk = Join-Path $realRoot ".toolchain\android-sdk"
$apk = Join-Path $android "app\build\outputs\apk\debug\app-debug.apk"
$dist = Join-Path $realRoot "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $apk (Join-Path $dist "PocketTrack.apk") -Force
Write-Host "APK: $dist\PocketTrack.apk"

if ($Install) {
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    & $adb install -r (Join-Path $dist "PocketTrack.apk")
}
