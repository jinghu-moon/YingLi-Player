[CmdletBinding()]
param(
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$supportedAbis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

$spinnerFrames = @('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

function Clear-ConsoleLine {
    try {
        try {
            $width = [Console]::WindowWidth
        } catch {
            $width = 120
        }

        if ($width -lt 20) {
            $width = 120
        }

        Write-Host ("`r" + (' ' * ($width - 1)) + "`r") -NoNewline
    } catch {
        # 忽略清行动画异常，避免影响主流程
    }
}

function Write-OkLine {
    param(
        [string]$Message
    )

    Clear-ConsoleLine
    Write-Host "✔ $Message" -ForegroundColor Green
}

function Fail {
    param(
        [string]$Message,
        [string]$Detail
    )

    Clear-ConsoleLine
    Write-Host "✘ $Message" -ForegroundColor Red

    if (-not [string]::IsNullOrWhiteSpace($Detail)) {
        Write-Host $Detail.Trim()
    }

    exit 1
}

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

function Wait-JobWithSpinner {
    param(
        [Parameter(Mandatory = $true)]
        $Job,

        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    $index = 0

    while (@('NotStarted', 'Running') -contains $Job.State) {
        $frame = $spinnerFrames[$index % $spinnerFrames.Count]
        Write-Host ("`r{0} {1}   " -f $frame, $Message) -NoNewline
        $index++
        Start-Sleep -Milliseconds 80
    }

    Clear-ConsoleLine

    try {
        $result = Receive-Job -Job $Job -ErrorAction Stop
    }
    finally {
        Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue
    }

    return $result
}

try {
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        Fail "找不到 Gradle Wrapper：$gradleWrapper"
    }

    $adb = Resolve-AdbPath

    # =====================
    # 构建 Debug 安装包
    # =====================
    $buildJob = Start-Job -ScriptBlock {
        param(
            [string]$WorkingDir,
            [string]$GradleWrapper
        )

        Set-Location $WorkingDir

        $output = & $GradleWrapper --no-configuration-cache --console=plain assembleDebug 2>&1 | Out-String

        [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Output   = $output
        }
    } -ArgumentList $projectRoot, $gradleWrapper

    $buildResult = Wait-JobWithSpinner -Job $buildJob -Message "正在构建安装包..."

    if ($null -eq $buildResult) {
        Fail "构建失败，输出所有日志：" "未获取到构建输出。"
    }

    if ($buildResult.ExitCode -ne 0) {
        Fail "构建失败，输出所有日志：" $buildResult.Output
    }

    Write-OkLine "构建成功"

    # =====================
    # 查找设备
    # =====================
    $deviceLines = Invoke-Adb -Executable $adb -Arguments @("devices")

    $connectedDevices = @(
        $deviceLines -split "`r?`n" |
        Select-Object -Skip 1 |
        ForEach-Object {
            $line = $_.Trim()

            if ([string]::IsNullOrWhiteSpace($line)) {
                return
            }

            $columns = $line -split "\s+"

            if ($columns.Count -ge 2 -and $columns[1] -eq "device") {
                $columns[0]
            }
        } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )

    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        if ($connectedDevices.Count -eq 0) {
            Fail "没有找到已授权的 Android 手机。请连接手机并开启 USB 调试。"
        }

        if ($connectedDevices.Count -gt 1) {
            Fail "检测到多个设备（$($connectedDevices -join ', ')）。请使用 -DeviceSerial <序列号> 指定目标设备。"
        }

        $DeviceSerial = $connectedDevices[0]
    }
    elseif ($connectedDevices -notcontains $DeviceSerial) {
        $available = if ($connectedDevices.Count -gt 0) {
            $connectedDevices -join ", "
        } else {
            "无"
        }

        Fail "设备 '$DeviceSerial' 未连接或未授权。当前已授权设备：$available"
    }

    # =====================
    # 获取设备 ABI
    # =====================
    $abi = Invoke-Adb -Executable $adb -Arguments @(
        "-s", $DeviceSerial,
        "shell", "getprop", "ro.product.cpu.abi"
    )

    $abi = $abi.Trim()

    if ([string]::IsNullOrWhiteSpace($abi)) {
        Fail "无法获取设备 '$DeviceSerial' 的 ABI。"
    }

    if ($supportedAbis -notcontains $abi) {
        Fail "设备 '$DeviceSerial' 的 ABI '$abi' 不在当前支持列表：$($supportedAbis -join ', ')"
    }

    $apk = Join-Path $projectRoot "app/build/outputs/apk/debug/app-$abi-debug.apk"

    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        Fail "未找到设备 ABI 对应的 APK：$apk"
    }

    # =====================
    # 安装到手机
    # =====================
    $installJob = Start-Job -ScriptBlock {
        param(
            [string]$AdbPath,
            [string]$Serial,
            [string]$ApkPath
        )

        $output = & $AdbPath -s $Serial install -r -d $ApkPath 2>&1 | Out-String

        [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Output   = $output
        }
    } -ArgumentList $adb, $DeviceSerial, $apk

    $installResult = Wait-JobWithSpinner -Job $installJob -Message "正在安装到手机 $DeviceSerial（$abi）..."

    if ($null -eq $installResult) {
        Fail "安装失败，输出所有日志：" "未获取到安装输出。"
    }

    if ($installResult.ExitCode -ne 0) {
        Fail "安装失败，输出所有日志：" $installResult.Output
    }

    if ($installResult.Output -notmatch '(?mi)^Success') {
        Fail "安装失败，输出所有日志：" $installResult.Output
    }

    Write-OkLine ("已安装到手机：{0} -> {1}（{2}）" -f [IO.Path]::GetFileName($apk), $DeviceSerial, $abi)
}
catch {
    Fail $_.Exception.Message
}