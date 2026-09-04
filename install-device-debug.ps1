[CmdletBinding()]
param(
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"

$supportedAbis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

function Resolve-AdbPath {
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkRoot = $env:ANDROID_HOME
    }

    if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkAdb = Join-Path $sdkRoot "platform-tools/adb.exe"
        if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) {
            return (Resolve-Path -LiteralPath $sdkAdb).Path
        }
    }

    $pathAdb = Get-Command "adb.exe" -ErrorAction SilentlyContinue
    if ($null -ne $pathAdb) {
        return $pathAdb.Source
    }

    throw "找不到 adb.exe。请设置 ANDROID_SDK_ROOT/ANDROID_HOME，或将 Android SDK platform-tools 加入 PATH。"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = & $Executable @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        $command = ($Arguments -join " ")
        throw "ADB 命令执行失败：adb $command`n$($output.Trim())"
    }
    return $output.Trim()
}

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "找不到 Gradle Wrapper：$gradleWrapper"
}

$adb = Resolve-AdbPath
Write-Host "使用 ADB：$adb"

Write-Host "正在构建 Debug ABI 分包..."
& $gradleWrapper --no-configuration-cache assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle 构建失败。"
}

$deviceLines = Invoke-Adb -Executable $adb -Arguments @("devices")
$connectedDevices = @(
    $deviceLines -split "`r?`n" |
        Select-Object -Skip 1 |
        ForEach-Object {
            $columns = $_.Trim() -split "\s+"
            if ($columns.Count -ge 2 -and $columns[1] -eq "device") {
                $columns[0]
            }
        } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)

if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($connectedDevices.Count -eq 0) {
        throw "没有找到已授权的 Android 手机。请连接手机并开启 USB 调试。"
    }
    if ($connectedDevices.Count -gt 1) {
        throw "检测到多个设备（$($connectedDevices -join ', ')）。请使用 -DeviceSerial <序列号> 指定目标设备。"
    }
    $DeviceSerial = $connectedDevices[0]
} elseif ($connectedDevices -notcontains $DeviceSerial) {
    $available = if ($connectedDevices.Count -gt 0) { $connectedDevices -join ", " } else { "无" }
    throw "设备 '$DeviceSerial' 未连接或未授权。当前已授权设备：$available"
}

$abi = (Invoke-Adb -Executable $adb -Arguments @("-s", $DeviceSerial, "shell", "getprop", "ro.product.cpu.abi")).Trim()
if ($supportedAbis -notcontains $abi) {
    throw "设备 '$DeviceSerial' 的 ABI '$abi' 不在当前支持列表：$($supportedAbis -join ', ')"
}

$apk = Join-Path $projectRoot "app/build/outputs/apk/debug/app-$abi-debug.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "未找到设备 ABI 对应的 APK：$apk"
}

Write-Host "正在安装 $([IO.Path]::GetFileName($apk)) 到 $DeviceSerial（$abi）..."
Invoke-Adb -Executable $adb -Arguments @("-s", $DeviceSerial, "install", "-r", "-d", $apk) | Write-Host
Write-Host "安装成功。"
