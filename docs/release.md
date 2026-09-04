# GitHub Release 发布约定

## 签名

发布密钥不进入仓库。构建机通过以下环境变量提供签名：

```text
YINGLI_KEYSTORE_PATH
YINGLI_KEYSTORE_PASSWORD
YINGLI_KEY_ALIAS
YINGLI_KEY_PASSWORD
```

变量不完整时 `assembleRelease` 只生成 `*-release-unsigned.apk`，该产物不得发布或标记为可安装 Release。

## 构建与校验和

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug :app:generateReleaseChecksums
```

输出位于：

- APK：`app/build/outputs/apk/release/`
- SHA-256：`app/build/outputs/release/SHA256SUMS`

发布前使用 Android SDK `apksigner verify --verbose --print-certs <apk>` 验证签名，并使用 `Get-FileHash -Algorithm SHA256` 复核摘要。GitHub Release 只分发按 ABI 拆分的已签名 APK、`SHA256SUMS` 和变更说明。

## 安装验证

至少覆盖 Android 12/API 31 与当前真机：全新安装、同签名覆盖升级、设置/数据库迁移、权限拒绝、空间不足和损坏 APK。签名不匹配时停止安装，不卸载已有数据作为自动回退。

