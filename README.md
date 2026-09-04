# 影里（YingLi Player）

影里是 Android 12+ 本地视频播放器与整理工具。应用使用 MediaStore/SAF 索引本地媒体，支持媒体库、标签与集合、Media3 播放、处理任务和多片段切片，不提供网络媒体源，也不上传遥测。

## 本地构建

环境要求：JDK 21、仓库内 Gradle Wrapper、Android SDK 36。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

真机仪器测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Macrobenchmark 使用独立、非 debuggable 的 `benchmark` variant：

```powershell
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest
```

发布、签名、校验和与隐私约定见 [发布说明](docs/release.md) 和 [隐私说明](docs/privacy.md)。

## 当前范围

- Android 12/API 31 及以上。
- 仅处理用户设备上的本地媒体和用户主动选择的 SAF 文档。
- 快速切片使用平台 Extractor/Muxer；精确切片使用 Media3 Transformer。
- 输出先写入应用私有临时目录，验证成功后提交到 `Movies/YingLi-Output`。

