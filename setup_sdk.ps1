#requires -Version 5
# CastToYourTV 一键配置最小 Android 构建环境（走代理 127.0.0.1:7890）
$ErrorActionPreference = 'Stop'
$proxy = 'http://127.0.0.1:7890'
$env:HTTPS_PROXY = $proxy
$env:HTTP_PROXY  = $proxy

$root   = 'D:\HaroroWorkSpace\castToYourTv'
$sdkDir = Join-Path $root 'android-sdk'
$tools  = Join-Path $sdkDir 'cmdline-tools'
New-Item -ItemType Directory -Force $sdkDir, $tools | Out-Null

Write-Host "== 1. 下载 Android command-line tools ==" -ForegroundColor Cyan
$cmdZip = Join-Path $root 'cmdline-tools.zip'
if (-not (Test-Path $cmdZip)) {
    Invoke-WebRequest -Proxy $proxy `
        -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' `
        -OutFile $cmdZip
}
Expand-Archive -Force $cmdZip $tools
# 规范目录 cmdline-tools/latest
$latest = Join-Path $tools 'latest'
if (-not (Test-Path $latest)) {
    Move-Item (Join-Path $tools 'cmdline-tools') $latest
}

$sdkmanager = Join-Path $latest 'bin\sdkmanager.bat'
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir

Write-Host "== 2. 接受许可并安装 platform-34 / build-tools / platform-tools ==" -ForegroundColor Cyan
"y`ny`ny`ny`ny`ny`ny`ny`n" | & $sdkmanager --sdk_root=$sdkDir --licenses | Out-Null
& $sdkmanager --sdk_root=$sdkDir "platforms;android-34" "build-tools;34.0.0" "platform-tools"

Write-Host "== 3. 写入 local.properties ==" -ForegroundColor Cyan
Set-Content -Path (Join-Path $root 'local.properties') -Value ("sdk.dir=" + ($sdkDir -replace '\\','\\')) -Encoding ASCII

Write-Host "SDK 配置完成：$sdkDir" -ForegroundColor Green
