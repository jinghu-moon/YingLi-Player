[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

# ============================================================
# 输出样式辅助函数
# ============================================================

function Get-DisplayWidth {
    param([string]$Text)

    $width = 0

    foreach ($ch in $Text.ToCharArray()) {
        $code = [int]$ch

        if (($code -ge 0x1100 -and $code -le 0x115F) -or
            ($code -ge 0x2E80 -and $code -le 0xA4CF) -or
            ($code -ge 0xAC00 -and $code -le 0xD7A3) -or
            ($code -ge 0xF900 -and $code -le 0xFAFF) -or
            ($code -ge 0xFF00 -and $code -le 0xFF60) -or
            ($code -ge 0xFFE0 -and $code -le 0xFFE6)) {
            $width += 2
        }
        else {
            $width += 1
        }
    }

    return $width
}

function Pad-Label {
    param(
        [string]$Text,
        [int]$TargetWidth
    )

    $padding = [Math]::Max(0, $TargetWidth - (Get-DisplayWidth $Text))
    return $Text + (' ' * $padding)
}

function Write-Banner {
    param(
        [string]$Title,
        [ConsoleColor]$Color = 'Cyan',
        [int]$Width = 48
    )

    $inner = $Width - 2
    $titleWidth = Get-DisplayWidth $Title
    $leftPad = [Math]::Max(0, [Math]::Floor(($inner - $titleWidth) / 2))
    $rightPad = [Math]::Max(0, $inner - $titleWidth - $leftPad)

    Write-Host ""
    Write-Host ("╔" + ('═' * $inner) + "╗") -ForegroundColor $Color
    Write-Host ("║" + (' ' * $leftPad) + $Title + (' ' * $rightPad) + "║") -ForegroundColor $Color
    Write-Host ("╚" + ('═' * $inner) + "╝") -ForegroundColor $Color
    Write-Host ""
}

function Write-StepHeader {
    param(
        [int]$Step,
        [int]$Total,
        [string]$Title,
        [int]$Width = 48
    )

    $label = "步骤 $Step/$Total ─ $Title"
    $dashCount = [Math]::Max(2, $Width - (Get-DisplayWidth $label) - 4)

    Write-Host ""
    Write-Host ("── " + $label + " " + ('─' * $dashCount)) -ForegroundColor Yellow
}

function Write-Note {
    param([string]$Message)
    Write-Host "    · $Message" -ForegroundColor DarkGray
}

function Write-KV {
    param(
        [string]$Label,
        [string]$Value,
        [int]$LabelWidth = 10,
        [ConsoleColor]$LabelColor = 'White',
        [ConsoleColor]$ValueColor = 'Gray'
    )

    Write-Host "  $(Pad-Label -Text $Label -TargetWidth $LabelWidth)" -NoNewline -ForegroundColor $LabelColor
    Write-Host " → " -NoNewline -ForegroundColor DarkGray
    Write-Host $Value -ForegroundColor $ValueColor
}

function Write-Bullet {
    param(
        [string]$Icon,
        [ConsoleColor]$IconColor,
        [string]$Text,
        [ConsoleColor]$TextColor = 'Gray'
    )

    Write-Host "  $Icon " -NoNewline -ForegroundColor $IconColor
    Write-Host $Text -ForegroundColor $TextColor
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  ✔ $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "  ⚠ $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "  ✘ $Message" -ForegroundColor Red
}

function Fail {
    param(
        [string]$Message,
        [string]$Detail
    )

    Write-Fail $Message

    if (-not [string]::IsNullOrWhiteSpace($Detail)) {
        Write-Host ""
        foreach ($line in ($Detail.Trim() -split "`r?`n")) {
            Write-Host "    $line" -ForegroundColor DarkGray
        }
    }

    Write-Host ""
    exit 1
}

function Confirm {
    param([string]$Message)

    Write-Host "  $Message (y/N)：" -NoNewline -ForegroundColor Yellow
    $answer = Read-Host

    return ($answer -eq 'y' -or $answer -eq 'Y')
}

function Save-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Text
    )

    [IO.File]::WriteAllText(
        $Path,
        $Text,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Read-SecureText {
    param([string]$Prompt)

    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)

    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Resolve-KeyTool {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"

        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    if ($env:LOCALAPPDATA) {
        $asJdk = Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr\bin\keytool.exe"

        if (Test-Path -LiteralPath $asJdk) {
            return $asJdk
        }
    }

    $found = Get-Command keytool.exe -ErrorAction SilentlyContinue

    if ($found) {
        return $found.Source
    }

    $found = Get-Command keytool -ErrorAction SilentlyContinue

    if ($found) {
        return $found.Source
    }

    return $null
}

$totalSteps = 8

# =========================================================
# 检测项目根目录
# =========================================================
$projectRoot = $PSScriptRoot
$buildGradle = Join-Path $projectRoot "app\build.gradle.kts"

if (-not (Test-Path -LiteralPath $buildGradle)) {
    $parent = Split-Path -Parent $projectRoot
    $parentGradle = Join-Path $parent "app\build.gradle.kts"

    if (Test-Path -LiteralPath $parentGradle) {
        $projectRoot = $parent
        $buildGradle = $parentGradle
    }
    else {
        Fail "未找到 app\build.gradle.kts" @"
当前脚本目录：$PSScriptRoot
期望路径：$buildGradle

请确认：
  1. 脚本放在 Android 项目根目录中。
  2. 项目包含 app 模块。
  3. 当前目录不是错误的上级目录。
"@
    }
}

$keystoreDir = Join-Path $projectRoot "keystore"
$keystoreProperties = Join-Path $projectRoot "keystore.properties"
$keystorePath = Join-Path $keystoreDir "release.jks"
$gitignore = Join-Path $projectRoot ".gitignore"

$keytool = Resolve-KeyTool

if (-not $keytool) {
    Fail "未找到 keytool" @"
请安装 JDK 或设置 JAVA_HOME。

Android Studio 自带 JDK 通常位于：
  $env:LOCALAPPDATA\Programs\Android Studio\jbr
"@
}

function Get-ProjectName {
    $settings = Join-Path $projectRoot "settings.gradle.kts"

    if (Test-Path -LiteralPath $settings) {
        $content = Get-Content -LiteralPath $settings -Raw
        $match = [regex]::Match($content, 'rootProject\.name\s*=\s*"([^"]+)"')

        if ($match.Success) {
            return $match.Groups[1].Value
        }
    }

    return Split-Path -Leaf $projectRoot
}

function Get-DefaultAlias {
    param([string]$Name)

    $alias = $Name.ToLowerInvariant()
    $alias = $alias -replace '[^a-z0-9]+', '-'
    $alias = $alias.Trim('-')

    if ([string]::IsNullOrWhiteSpace($alias)) {
        return 'release'
    }

    return $alias
}

$projectName = Get-ProjectName
$defaultAlias = Get-DefaultAlias -Name $projectName

Write-Banner -Title "Android Release 签名配置" -Color Cyan

Write-Host "检测到：" -ForegroundColor White
$detectWidth = (@("项目根目录", "Gradle 文件", "密钥库", "配置文件", "keytool") |
    ForEach-Object { Get-DisplayWidth $_ } | Measure-Object -Maximum).Maximum
Write-KV -Label "项目根目录" -Value $projectRoot -LabelWidth $detectWidth
Write-KV -Label "Gradle 文件" -Value $buildGradle -LabelWidth $detectWidth
Write-KV -Label "密钥库" -Value $keystorePath -LabelWidth $detectWidth
Write-KV -Label "配置文件" -Value $keystoreProperties -LabelWidth $detectWidth
Write-KV -Label "keytool" -Value $keytool -LabelWidth $detectWidth
Write-Host ""

if (Test-Path -LiteralPath $keystorePath) {
    Write-Warn "检测到已有密钥库，继续执行可能需要覆盖。"
}

Write-Host "即将生成/修改：" -ForegroundColor White
Write-Bullet -Icon "•" -IconColor DarkCyan -Text "keystore/release.jks"
Write-Bullet -Icon "•" -IconColor DarkCyan -Text "keystore.properties"
Write-Bullet -Icon "•" -IconColor DarkCyan -Text "app/build.gradle.kts"
Write-Bullet -Icon "•" -IconColor DarkCyan -Text ".gitignore"
Write-Host ""

Write-Warn "release.jks 和密码丢失后无法恢复，请务必备份。"
Write-Warn "覆盖已有密钥库会改变应用签名。"
Write-Warn "请勿提交 keystore/ 和 keystore.properties。"
Write-Host ""

if (-not (Confirm "是否继续？仅输入 y 时执行")) {
    Write-Warn "已取消"
    exit 0
}

# =========================================================
# 1. 密码
# =========================================================
Write-StepHeader -Step 1 -Total $totalSteps -Title "设置密钥库密码"
Write-Note "用于保护 release.jks。"
Write-Note "至少 6 位，建议 8 位以上。"
Write-Note "建议使用英文、数字和常见符号，避免中文或首尾空格。"
Write-Host ""

$storePasswordPlain = Read-SecureText "  请输入密码"

if ($storePasswordPlain.Length -lt 6) {
    Fail "密码太短" "至少需要 6 位。"
}

$confirmPasswordPlain = Read-SecureText "  请再次输入密码"

if ($storePasswordPlain -ne $confirmPasswordPlain) {
    Fail "两次密码不一致"
}

# =========================================================
# 2. 别名
# =========================================================
Write-StepHeader -Step 2 -Total $totalSteps -Title "设置密钥别名"
Write-Note "用于在 release.jks 中标识密钥。"
Write-Host ""

$keyAlias = Read-Host "  密钥别名 [$defaultAlias]"

if ([string]::IsNullOrWhiteSpace($keyAlias)) {
    $keyAlias = $defaultAlias
}

$keyAlias = $keyAlias.Trim().ToLowerInvariant()

# =========================================================
# 3. 有效期
# =========================================================
Write-StepHeader -Step 3 -Total $totalSteps -Title "设置有效期"
Write-Note "签名密钥过期后无法用于更新应用。"
Write-Host ""

$validityInput = Read-Host "  有效期（天）[10000]"
$validity = 10000

if (-not [string]::IsNullOrWhiteSpace($validityInput)) {
    $parsed = 0

    if (-not [int]::TryParse($validityInput, [ref]$parsed) -or $parsed -le 0) {
        Fail "有效期无效" "请输入正整数。"
    }

    $validity = $parsed
}

# =========================================================
# 4. 证书信息
# =========================================================
Write-StepHeader -Step 4 -Total $totalSteps -Title "填写证书信息"
Write-Note "可选项直接回车跳过，不要输入 null。"
Write-Host ""

$cn = Read-Host "  应用/开发者名称 (CN) [$projectName]"

if ([string]::IsNullOrWhiteSpace($cn)) {
    $cn = $projectName
}

$ou = Read-Host "  部门/团队 (OU)（可选）"
$org = Read-Host "  组织/公司 (O)（可选）"
$loc = Read-Host "  城市 (L)（可选）"
$state = Read-Host "  省份 (ST)（可选）"
$country = Read-Host "  国家代码 (C) [CN]"

if ($ou -ieq 'null') { $ou = '' }
if ($org -ieq 'null') { $org = '' }
if ($loc -ieq 'null') { $loc = '' }
if ($state -ieq 'null') { $state = '' }

if ([string]::IsNullOrWhiteSpace($country) -or $country -ieq 'null') {
    $country = "CN"
}

# =========================================================
# 5. 生成密钥库
# =========================================================
Write-StepHeader -Step 5 -Total $totalSteps -Title "生成密钥库"

if (-not (Test-Path -LiteralPath $keystoreDir)) {
    New-Item -ItemType Directory -Path $keystoreDir | Out-Null
}

if (Test-Path -LiteralPath $keystorePath) {
    Write-Host ""
    Write-Warn "已存在密钥库：$keystorePath"
    Write-Warn "覆盖后旧密钥不可恢复，已发布应用可能无法更新。"

    if (-not (Confirm "是否覆盖？仅输入 y 时继续")) {
        Write-Warn "已取消"
        exit 0
    }

    Remove-Item -LiteralPath $keystorePath -Force
}

$dnParts = @("CN=$cn")

if (-not [string]::IsNullOrWhiteSpace($ou)) {
    $dnParts += "OU=$ou"
}

if (-not [string]::IsNullOrWhiteSpace($org)) {
    $dnParts += "O=$org"
}

if (-not [string]::IsNullOrWhiteSpace($loc)) {
    $dnParts += "L=$loc"
}

if (-not [string]::IsNullOrWhiteSpace($state)) {
    $dnParts += "ST=$state"
}

if (-not [string]::IsNullOrWhiteSpace($country)) {
    $dnParts += "C=$country"
}

$dn = $dnParts -join ", "

Write-Note "正在生成..."

$keytoolOutput = & $keytool `
    -genkeypair `
    -v `
    -keystore $keystorePath `
    -storetype JKS `
    -keyalg RSA `
    -keysize 2048 `
    -validity $validity `
    -alias $keyAlias `
    -storepass $storePasswordPlain `
    -keypass $storePasswordPlain `
    -dname $dn 2>&1 | Out-String

if ($LASTEXITCODE -ne 0) {
    Fail "密钥库生成失败" $keytoolOutput
}

Write-Ok "密钥库已生成 → keystore/release.jks"

# =========================================================
# 6. 写入 keystore.properties
# =========================================================
Write-StepHeader -Step 6 -Total $totalSteps -Title "写入密钥库配置"

$propsContent = @"
# Generated by setup-signing.ps1
# Contains sensitive information. Do not commit.
storePassword=$storePasswordPlain
keyPassword=$storePasswordPlain
keyAlias=$keyAlias
storeFile=keystore/release.jks
"@

Save-Utf8NoBom -Path $keystoreProperties -Text $propsContent
Write-Ok "配置已写入 → keystore.properties"

# =========================================================
# 7. 更新 app/build.gradle.kts
# =========================================================
Write-StepHeader -Step 7 -Total $totalSteps -Title "更新 Gradle 签名配置"

$gradleContent = Get-Content -LiteralPath $buildGradle -Raw

if ($null -eq $gradleContent) {
    Fail "无法读取 $buildGradle"
}

if ($gradleContent -match 'signingConfigs\s*\{') {
    Write-Warn "app/build.gradle.kts 已包含 signingConfigs，跳过自动修改"
    Write-Warn "请手动确认 release 使用：signingConfig = signingConfigs.getByName(`"release`")"
}
else {
    if ($gradleContent -notmatch 'import java\.util\.Properties') {
        $gradleContent = "import java.util.Properties`n`n" + $gradleContent
    }

    $androidMatch = [regex]::Match($gradleContent, 'android\s*\{')

    if (-not $androidMatch.Success) {
        Fail "未找到 android { 块" "请手动在 app/build.gradle.kts 中添加 signingConfigs。"
    }

    $signingBlock = @'

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")

            if (!propsFile.exists()) {
                throw org.gradle.api.GradleException("缺少 keystore.properties，请先运行 setup-signing.ps1")
            }

            propsFile.inputStream().use { props.load(it) }

            storeFile = rootProject.file(props.getProperty("storeFile", "keystore/release.jks"))
            storePassword = props.getProperty("storePassword", "")
            keyAlias = props.getProperty("keyAlias", "")
            keyPassword = props.getProperty("keyPassword", "")
        }
    }
'@

    $insertPos = $androidMatch.Index + $androidMatch.Length
    $gradleContent = $gradleContent.Substring(0, $insertPos) + $signingBlock + $gradleContent.Substring($insertPos)

    if ($gradleContent -notmatch 'signingConfig\s*=\s*signingConfigs\.getByName\("release"\)') {
        if ($gradleContent -match 'signingConfig\s*=') {
            Write-Warn "已存在 signingConfig，请手动确认 release 使用 signingConfigs.getByName(`"release`")"
        }
        else {
            $releaseMatch = [regex]::Match($gradleContent, '(?:release|getByName\("release"\))\s*\{')

            if ($releaseMatch.Success) {
                $releaseInsertPos = $releaseMatch.Index + $releaseMatch.Length
                $releaseLine = "`n            signingConfig = signingConfigs.getByName(`"release`")"
                $gradleContent = $gradleContent.Substring(0, $releaseInsertPos) + $releaseLine + $gradleContent.Substring($releaseInsertPos)
            }
            else {
                Write-Warn "未找到 release buildType，请手动添加：signingConfig = signingConfigs.getByName(`"release`")"
            }
        }
    }

    Save-Utf8NoBom -Path $buildGradle -Text $gradleContent
    Write-Ok "app/build.gradle.kts 已更新"
}

# =========================================================
# 8. 更新 .gitignore
# =========================================================
Write-StepHeader -Step 8 -Total $totalSteps -Title "更新 .gitignore"

$gitignoreLines = @()

if (Test-Path -LiteralPath $gitignore) {
    $raw = Get-Content -LiteralPath $gitignore -Raw

    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        $gitignoreLines = @($raw -split "`r?`n")
    }
}

$required = @(
    "keystore/",
    "keystore.properties",
    "*.jks",
    "*.keystore"
)

$added = $false

foreach ($entry in $required) {
    if ($gitignoreLines -notcontains $entry) {
        $gitignoreLines += $entry
        $added = $true
    }
}

if ($added) {
    $gitignoreText = ($gitignoreLines -join [Environment]::NewLine) + [Environment]::NewLine
    Save-Utf8NoBom -Path $gitignore -Text $gitignoreText
    Write-Ok ".gitignore 已更新"
}
else {
    Write-Ok ".gitignore 已包含密钥排除规则"
}

# =========================================================
# 完成
# =========================================================
Write-Banner -Title "配置完成" -Color Green

Write-Host "已生成：" -ForegroundColor White
Write-Bullet -Icon "✔" -IconColor Green -Text "keystore/release.jks"
Write-Bullet -Icon "✔" -IconColor Green -Text "keystore.properties"
Write-Host ""

Write-Host "已修改：" -ForegroundColor White
Write-Bullet -Icon "✔" -IconColor Green -Text "app/build.gradle.kts"
Write-Bullet -Icon "✔" -IconColor Green -Text ".gitignore"
Write-Host ""

Write-Warn "请立刻备份 release.jks 和密码。"
Write-Host ""

$buildInfoWidth = (@("构建命令", "输出目录") | ForEach-Object { Get-DisplayWidth $_ } | Measure-Object -Maximum).Maximum
Write-KV -Label "构建命令" -Value "./gradlew assembleRelease" -LabelWidth $buildInfoWidth -ValueColor Yellow
Write-KV -Label "输出目录" -Value "app/build/outputs/apk/release/" -LabelWidth $buildInfoWidth -ValueColor Yellow
Write-Host ""

# =========================================================
# 可选：构建 Release，必须用户同意
# =========================================================
if (Confirm "是否立即构建 Release？仅输入 y 时开始") {
    $gradleWrapper = Join-Path $projectRoot "gradlew.bat"

    if (-not (Test-Path -LiteralPath $gradleWrapper)) {
        Fail "未找到 gradlew.bat" $gradleWrapper
    }

    Write-Note "正在构建 Release..."

    # 即使现有 Gradle 文件只读取环境变量，脚本也能独立完成构建；构建后恢复调用方环境。
    $signingEnvironment = @{
        YINGLI_KEYSTORE_PATH = (Resolve-Path -LiteralPath $keystorePath).Path
        YINGLI_KEYSTORE_PASSWORD = $storePasswordPlain
        YINGLI_KEY_ALIAS = $keyAlias
        YINGLI_KEY_PASSWORD = $storePasswordPlain
    }
    $previousEnvironment = @{}

    try {
        foreach ($name in $signingEnvironment.Keys) {
            $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
            [Environment]::SetEnvironmentVariable($name, $signingEnvironment[$name], "Process")
        }

        & $gradleWrapper --no-configuration-cache --console=plain assembleRelease

        if ($LASTEXITCODE -ne 0) {
            Fail "Release 构建失败" "请查看上方 Gradle 输出。"
        }
    }
    finally {
        foreach ($name in $signingEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], "Process")
        }
    }

    $releaseOutput = Join-Path $projectRoot "app\build\outputs\apk\release"
    $signedApks = @(Get-ChildItem -LiteralPath $releaseOutput -Filter "*-release.apk" -File -ErrorAction SilentlyContinue)
    $unsignedApks = @(Get-ChildItem -LiteralPath $releaseOutput -Filter "*-release-unsigned.apk" -File -ErrorAction SilentlyContinue)

    if ($signedApks.Count -eq 0 -or $unsignedApks.Count -gt 0) {
        Fail "Release 构建未生成有效签名 APK" @"
输出目录：$releaseOutput
已签名 APK：$($signedApks.Count)
未签名 APK：$($unsignedApks.Count)
请检查 signingConfigs.release 配置和密钥信息。
"@
    }

    Write-Ok "Release 构建成功，已生成 $($signedApks.Count) 个签名 APK"
    Write-Host "  → app/build/outputs/apk/release/" -ForegroundColor Yellow
}
else {
    Write-Warn "已跳过 Release 构建"
}