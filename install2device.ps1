[CmdletBinding()]
param(
    [Alias("d")]
    [switch]$DebugBuild,

    [Alias("r")]
    [switch]$ReleaseBuild,

    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"

# 执行前清除屏幕已有输出
try {
    Clear-Host
} catch {}

function Hide-Cursor {
    try {
        [Console]::CursorVisible = $false
    } catch {}

    try {
        $Host.UI.RawUI.CursorSize = 0
    } catch {}
}

function Show-Cursor {
    try {
        [Console]::CursorVisible = $true
    } catch {}

    try {
        $Host.UI.RawUI.CursorSize = 25
    } catch {}
}

# 开始脚本时隐藏光标
Hide-Cursor

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$supportedAbis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

# 加载动画字符
$spinnerFrames = @('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

# 可按需修改颜色
$spinnerColor = "Yellow"
$successColor = "Cyan"
$failColor    = "Red"

function Select-BuildVariant {
    if ($DebugBuild -and $ReleaseBuild) {
        throw "-d 和 -r 不能同时使用。"
    }

    if ($DebugBuild) {
        return "debug"
    }

    if ($ReleaseBuild) {
        return "release"
    }

    Show-Cursor
    try {
        do {
            Write-Host ""
            Write-Host "选择构建类型：" -ForegroundColor Cyan
            Write-Host "  [1] Debug  构建调试包并安装"
            Write-Host "  [2] Release 构建签名包并安装"
            Write-Host "  [0] 取消"
            $choice = Read-Host "请输入选项"
        } while ($choice -notin @("0", "1", "2"))

        if ($choice -eq "0") {
            throw "已取消"
        }

        return if ($choice -eq "1") { "debug" } else { "release" }
    }
    finally {
        Hide-Cursor
    }
}

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
    } catch {}
}

function Write-OkLine {
    param(
        [string]$Message
    )

    Clear-ConsoleLine

    # 字符与文本之间两个空格
    Write-Host "✔  " -NoNewline -ForegroundColor $successColor
    Write-Host $Message
}

function Fail {
    param(
        [string]$Message,
        [string]$Detail
    )

    Clear-ConsoleLine
    Show-Cursor

    # 字符与文本之间两个空格
    Write-Host "✘  " -NoNewline -ForegroundColor $failColor
    Write-Host $Message -ForegroundColor $failColor

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

    Hide-Cursor

    $index = 0

    while (@('NotStarted', 'Running') -contains $Job.State) {
        $frame = $spinnerFrames[$index % $spinnerFrames.Count]

        # 回到行首
        Write-Host "`r" -NoNewline

        # 字符与文本之间两个空格
        Write-Host "$frame  " -NoNewline -ForegroundColor $spinnerColor
        Write-Host "$Message   " -NoNewline

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

    $buildVariant = Select-BuildVariant
    $adb = Resolve-AdbPath

    # =====================
    # 构建设备安装包
    # =====================
    $gradleTask = if ($buildVariant -eq "release") { "assembleRelease" } else { "assembleDebug" }
    $previousSigningEnvironment = @{}
    $signingEnvironmentNames = @(
        "YINGLI_KEYSTORE_PATH",
        "YINGLI_KEYSTORE_PASSWORD",
        "YINGLI_KEY_ALIAS",
        "YINGLI_KEY_PASSWORD"
    )

    try {
        if ($buildVariant -eq "release") {
            $propertiesFile = Join-Path $projectRoot "keystore.properties"
            if (Test-Path -LiteralPath $propertiesFile -PathType Leaf) {
                $properties = ConvertFrom-StringData (Get-Content -LiteralPath $propertiesFile -Raw)
                $configuredKeystore = $properties.storeFile
                $configuredKeystorePath = if ([IO.Path]::IsPathRooted($configuredKeystore)) {
                    $configuredKeystore
                } else {
                    Join-Path $projectRoot $configuredKeystore
                }
                $releaseValues = @{
                    YINGLI_KEYSTORE_PATH = (Resolve-Path -LiteralPath $configuredKeystorePath).Path
                    YINGLI_KEYSTORE_PASSWORD = $properties.storePassword
                    YINGLI_KEY_ALIAS = $properties.keyAlias
                    YINGLI_KEY_PASSWORD = $properties.keyPassword
                }
                foreach ($name in $signingEnvironmentNames) {
                    $previousSigningEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
                    if ([string]::IsNullOrWhiteSpace($previousSigningEnvironment[$name])) {
                        [Environment]::SetEnvironmentVariable($name, $releaseValues[$name], "Process")
                    }
                }
            }

            $missingSigningValues = @(
                $signingEnvironmentNames |
                    Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, "Process")) }
            )
            if ($missingSigningValues.Count -gt 0) {
                Fail "缺少 Release 签名配置" "请先运行 setup-signing.ps1，或设置：$($missingSigningValues -join ', ')"
            }
        }

        $buildJob = Start-Job -ScriptBlock {
            param(
                [string]$WorkingDir,
                [string]$GradleWrapper,
                [string]$GradleTask
            )

            Set-Location $WorkingDir

            $output = & $GradleWrapper --no-configuration-cache --console=plain $GradleTask 2>&1 | Out-String

            [PSCustomObject]@{
                ExitCode = $LASTEXITCODE
                Output   = $output
            }
        } -ArgumentList $projectRoot, $gradleWrapper, $gradleTask

        $buildResult = Wait-JobWithSpinner -Job $buildJob -Message "正在构建 $buildVariant 安装包..."
    }
    finally {
        foreach ($name in $signingEnvironmentNames) {
            if ($previousSigningEnvironment.ContainsKey($name)) {
                [Environment]::SetEnvironmentVariable($name, $previousSigningEnvironment[$name], "Process")
            }
        }
    }

    if ($null -eq $buildResult) {
        Fail "构建失败，输出所有日志：" "未获取到构建输出。"
    }

    if ($buildResult.ExitCode -ne 0) {
        Fail "构建失败，输出所有日志：" $buildResult.Output
    }

    Write-OkLine "${buildVariant} 构建成功"

    # =====================
    # 查找设备
    # =====================
    $deviceLines = Invoke-Adb -Executable $adb -Arguments @("devices")
    $deviceLineArray = $deviceLines -split "`r?`n"

    $startIndex = 0

    for ($i = 0; $i -lt $deviceLineArray.Count; $i++) {
        if ($deviceLineArray[$i] -match '^List of devices attached') {
            $startIndex = $i + 1
            break
        }
    }

    $connectedDevices = @()

    if ($startIndex -lt $deviceLineArray.Count) {
        $connectedDevices = @(
            $deviceLineArray[$startIndex..($deviceLineArray.Count - 1)] |
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
    }

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

    $apk = Join-Path $projectRoot "app/build/outputs/apk/$buildVariant/app-$abi-$buildVariant.apk"

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

    if ($installResult.Output -notmatch '(?mi)^\s*Success') {
        Fail "安装失败，输出所有日志：" $installResult.Output
    }

    Write-OkLine ("已安装到手机：{0} -> {1}（{2}）" -f [IO.Path]::GetFileName($apk), $DeviceSerial, $abi)

    Show-Cursor
}
catch {
    Fail $_.Exception.Message
}
finally {
    Show-Cursor
}
