# 影里播放器技术选型

> 文档状态：已接受  
> 适用阶段：项目初始化与 MVP  
> 最后核对：2026-08-07

## 1. 项目定位

影里（YingLi Player）是一款 Android 本地视频播放器，首版聚焦以下目标：

- 播放常见本地视频、音频和字幕格式；
- 优先使用硬件解码，保证流畅度、功耗和首帧速度；
- 安装包保持轻量，不预置体积庞大的全格式软件解码器；
- 支持媒体库扫描、目录导入、播放列表和断点续播；
- 播放控件易于自定义，能够逐步扩展手势、倍速、字幕和轨道选择；
- 本地优先，不依赖账号、云服务或网络权限完成核心功能。

首版不追求桌面级全格式覆盖，不内置视频转码、复杂滤镜或在线媒体服务。

## 2. 选型原则

1. **稳定优先**：业务依赖只使用稳定版本，不采用 Alpha、Beta 或 RC 版本。
2. **原生优先**：优先使用 AndroidX、平台 API 和官方维护组件。
3. **硬解优先**：播放链路优先走 `MediaCodec`，软件解码仅作为后续候选。
4. **轻量优先**：没有明确需求时，不引入 FFmpeg、libVLC、libmpv 或大型依赖注入框架。
5. **可替换但不过度抽象**：隔离播放会话与 UI，不提前实现多播放内核。
6. **版本集中管理**：所有版本统一定义在 Gradle Version Catalog 中。

## 3. 基础构建基线

| 项目 | 选定版本 | 说明 |
|---|---:|---|
| Kotlin | `2.4.0` | 项目固定语言与编译器基线 |
| Android Gradle Plugin | `9.3.1` | 稳定版，默认启用内置 Kotlin |
| Gradle Wrapper | `9.5.0` | AGP 9.3 要求的最低及默认版本 |
| JDK | `21` | AGP 最低要求 17，项目统一使用 21 |
| `compileSdk` | `36` | 使用稳定 Android SDK |
| `targetSdk` | `36` | 与当前发布目标保持一致 |
| `minSdk` | `31` | 按最终需求仅支持 Android 12+；当前指定真机为 Android 13（API 33） |
| JVM target | `21` | 与项目 JDK 保持一致 |

AGP 9 已默认启用内置 Kotlin。Android 模块不得再应用 `org.jetbrains.kotlin.android`，否则会与 AGP 的 Kotlin 扩展冲突。Compose Compiler 与 Kotlin Serialization 编译器插件使用 `2.4.0`，与 Kotlin 基线严格一致。

Room 使用 KSP 生成 DAO 实现。KSP `2.3.2` 仍通过 Kotlin sourceSets 注册生成目录，因此启用 AGP 9 的 `android.disallowKotlinSourceSets=false` 兼容开关；项目继续使用内置 Kotlin，不应用旧 `org.jetbrains.kotlin.android` 插件。

Phase 1 已按上述版本实装 Gradle Wrapper、单 `app` 模块和构建约束。当前代码没有 AndroidX Core KTX 使用点，因此不预选版本、不声明直接依赖；后续出现明确使用点时，再选择与当时 `compileSdk` 兼容的稳定版本。

Lint 的 `UseKtx` 风格规则已关闭，避免仅为 `String.toUri()` 等可由平台 API 直接表达的调用反向引入 Core KTX；这不影响其余正确性、安全性和 API 级别检查。

## 4. 核心技术栈

| 领域 | 选择 | 版本 | 用途 |
|---|---|---:|---|
| UI | Jetpack Compose + Material 3 | BOM `2026.06.01` | 页面、主题和自定义播放控制层 |
| 窗口分类 | Material 3 Window Size Class | `1.4.0` | Compact/Medium/Expanded 自适应导航断点 |
| 图标 | Compose Icons Tabler（本地构建） | `0.1.0-local.1` | 基于 Tabler Icons `3.46.0` 生成；业务语义图标，缺失项集中回退 Material Icons |
| 播放内核 | AndroidX Media3 ExoPlayer | `1.10.1` | 解封装、播放、轨道和字幕 |
| 媒体会话 | Media3 Session | `1.10.1` | 后台播放、通知栏、锁屏和耳机控制 |
| 播放控件 | Media3 UI / UI Compose | `1.10.1` | 播放画面和官方控制组件 |
| 生命周期 | AndroidX Lifecycle | `2.10.0` | 兼容 compileSdk 36；用于 ViewModel、StateFlow 收集和生命周期管理 |
| Activity | Activity Compose | `1.13.0` | Compose Activity 宿主 |
| 导航 | Navigation Compose | `2.9.8` | 稳定页面导航 |
| 数据库 | Room | `2.8.4` | 媒体条目、播放记录、播放列表和标签 |
| 符号处理 | KSP 2 | `2.3.2` | Room 代码生成 |
| 设置存储 | DataStore Preferences | `1.2.1` | 播放偏好、扫描设置和 UI 设置 |
| 图片加载 | Coil 3 Compose + Video | `3.5.0` | 视频缩略图和异步图片加载 |
| 并发 | Kotlin Coroutines | `1.11.0` | 媒体扫描、数据库和文件 I/O |
| 文件访问 | `MANAGE_EXTERNAL_STORAGE` + MediaStore + SAF | 平台 API | 默认全盘媒体管理，以及拒绝授权后的媒体发现与目录降级 |
| SAF 辅助 | AndroidX DocumentFile | `1.1.0` | 文档树和持久 URI 操作 |

Coil `3.5.0` 的发布元数据使用 Kotlin `2.4.0`，与本项目基线一致。Media3、Room、Coroutines 等组件使用更早的 Kotlin 标准库发布，Kotlin `2.4.0` 可向后兼容这些依赖。

### 4.1 Compose 版本策略

Compose 组件统一交由 BOM 管理，不为 `ui`、`foundation`、`animation` 和 `material3` 单独声明版本：

```kotlin
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
```

不得沿用 `Material3 1.5.0-alpha*` 等预览版本。需要预览 API 时，应先建立独立验证分支并记录升级理由。

### 4.2 播放控件策略

首版以 Media3 官方控件为基础，不引入 GSYVideoPlayer、JiaoZiVideoPlayer 或 ijkplayer：

- 播放画面由 Media3 提供；
- 控制层使用 Compose 和 Material 3 实现；
- 首期完成播放/暂停、进度、倍速、横竖屏、画中画、字幕和轨道选择；
- 手势控制在稳定播放链路完成后加入；
- 播放器实例由播放会话层持有，不在 Composable 重组过程中创建。

## 5. 播放内核决策

### 5.1 选用 Media3 的原因

- Android 官方维护，能与 Activity、Lifecycle、MediaSession 和画中画自然集成；
- 默认使用设备 `MediaCodec` 硬件解码，性能、功耗和启动速度更适合移动端；
- 支持 MP4、M4A、Matroska、WebM、MPEG-TS、FLV、Ogg、WAV、FLAC 等常见容器；
- 支持内嵌和外挂字幕、音视频轨道、播放列表、倍速和断点续播；
- 基础包体明显小于预置 FFmpeg、libVLC 或 libmpv 的方案；
- API 与测试生态成熟，后续维护风险低。

### 5.2 格式支持边界

容器格式和编解码格式是两个不同维度。能够解析 MKV 不代表设备能够解码其中所有音视频轨道。

| 场景 | 预期支持情况 |
|---|---|
| MP4 + H.264 + AAC | 核心支持，作为基准格式 |
| MP4/MKV + HEVC | 取决于设备硬件解码能力 |
| WebM + VP9/AV1 | 取决于系统版本和设备能力 |
| HDR10/HLG/Dolby Vision | 取决于设备、显示屏和系统解码链路 |
| SRT/WebVTT/SSA/ASS 字幕 | 使用 Media3 能力，并通过测试集验证样式边界 |
| DTS/TrueHD 等音频 | 部分设备缺少系统解码器，不能承诺全覆盖 |
| AVI、RMVB 和老旧编码 | 不作为首版兼容承诺 |

应用必须把“不支持的编码”“文件损坏”和“权限失效”区分为不同错误，不以崩溃或无限加载处理。

### 5.3 libVLC 与 libmpv 的处理

首版不引入第二播放内核。只有测试和真实用户样本满足以下条件之一时，才重新评估：

- 核心目标格式在主流设备上持续无法播放；
- 软件解码需求明确且覆盖收益大于包体、功耗和维护成本；
- 需要 libass 高级字幕、复杂滤镜或桌面级色彩管理；
- 产品定位从轻量播放器转为专业全格式播放器。

候选方案对比：

| 方案 | 优点 | 主要代价 |
|---|---|---|
| libVLC | 格式覆盖广，Android 集成相对成熟 | AAB 体积、ABI 管理、JNI 崩溃和许可证审查 |
| libmpv | 字幕、滤镜、色彩和格式能力强 | Android 控件生态弱，构建和生命周期集成复杂 |
| Media3 FFmpeg 扩展 | 保留 Media3 API，可补充部分音频解码 | 需要自行构建，增加体积和 CPU 消耗 |

如果将来增加多内核，只在确认需求后引入最小 `PlaybackEngine` 接口，不为未知未来预先维护两套实现。

## 6. 本地媒体与存储

### 6.1 媒体发现

- 首次引导允许用户选择立即添加或稍后添加；用户主动添加时说明用途，再由用户在系统界面显式授权 `MANAGE_EXTERNAL_STORAGE`，不在冷启动时强制跳转；
- 获得全部文件访问后执行全盘媒体发现、跨目录管理和移动后重定位；
- 使用 `MediaStore` 查询系统登记的视频；
- 用户拒绝全部文件访问时，使用 Storage Access Framework 选择单个文件或目录作为完整降级路径；
- 使用 `takePersistableUriPermission` 保存用户授权；
- 播放和数据库统一保存 `content://` URI，不依赖真实文件路径；
- Android 13+ 按需申请 `READ_MEDIA_VIDEO`，拒绝权限时仍保留 SAF 导入能力；
- 扫描、元数据读取和缩略图处理不得运行在主线程。

### 6.2 数据职责

| 数据 | 存储位置 |
|---|---|
| 系统媒体事实数据 | MediaStore，按需重新查询 |
| SAF 授权 URI | Room，同时保留系统持久授权 |
| 播放进度和历史 | Room |
| 播放列表、收藏、标签 | Room |
| 主题、手势、倍速等偏好 | DataStore |
| 视频缩略图 | Coil 磁盘/内存缓存 |

Room 不复制完整媒体文件信息作为唯一事实来源。扫描时使用稳定媒体标识和 URI 做增量同步，避免每次启动全库重建。

## 7. 推荐架构

首版采用单 Activity、Compose UI 和按业务域分层的简单架构：

```text
UI (Compose)
  -> ViewModel
      -> Use case（仅复杂业务需要）
          -> Repository
              -> Room / MediaStore / SAF / DataStore

Playback UI
  -> Playback ViewModel / Controller
      -> MediaSession / MediaController
          -> ExoPlayer
```

约束如下：

- Composable 只消费不可变 UI State，并向 ViewModel 发送事件；
- Repository 提供主线程安全的 `suspend` API 和 `Flow`；
- 文件 I/O 使用 `Dispatchers.IO`，纯计算使用 `Dispatchers.Default`；
- 播放器生命周期归播放服务或会话层管理，不归单个页面管理；
- 首版使用构造函数和工厂进行依赖组装，不引入 Hilt；
- 只有依赖数量和作用域管理出现真实复杂度后，才评估依赖注入框架。

## 8. 版本目录建议

```toml
[versions]
agp = "9.3.1"
kotlin = "2.4.0"
compose-bom = "2026.06.01"
activity-compose = "1.13.0"
lifecycle = "2.10.0"
navigation-compose = "2.9.8"
media3 = "1.10.1"
room = "2.8.4"
ksp = "2.3.2"
datastore = "1.2.1"
coil = "3.5.0"
coroutines = "1.11.0"
documentfile = "1.1.0"
material3-window = "1.4.0"
tabler-icons = "0.1.0-local.1"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

仅在确实需要 JSON 导入导出时增加 Kotlin Serialization：

```toml
[versions]
serialization = "1.11.0"

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

## 9. 暂不引入的组件

| 组件 | 暂不引入原因 |
|---|---|
| FFmpeg / FFmpegKit | 增加包体、ABI 和许可证成本；FFmpegKit 原项目已停止维护 |
| libVLC / libmpv | 首版格式需求尚不足以证明双内核成本合理 |
| ijkplayer | 维护状态和现代 Android 生态适配不适合新项目 |
| GSYVideoPlayer / JiaoZiVideoPlayer | UI 和播放生命周期受框架约束，不利于 Compose 深度定制 |
| Hilt / Koin | 首版依赖图简单，手动组装更直接 |
| Retrofit / OkHttp | 本地播放器核心功能不需要网络 |
| WorkManager | 首版扫描可由前台交互触发；需要可靠后台任务后再加入 |
| 分析与广告 SDK | 与本地优先、隐私和启动速度目标冲突 |

## 10. 性能策略

- 启动阶段不创建完整媒体索引，不同步生成全部缩略图；
- 首页优先展示数据库缓存结果，后台增量校验 MediaStore；
- ExoPlayer、数据库和图片加载器均使用应用级单例或明确作用域；
- 列表使用稳定 Key、分页/分批加载和受限缩略图尺寸；
- 不在 Compose 重组中执行文件访问、元数据解析或播放器查询循环；
- Release 开启 R8、资源压缩，并为反射/序列化内容保留最小规则；
- MVP 稳定后增加 Baseline Profile 与 Macrobenchmark，不在首个功能提交中提前优化。

建议持续记录以下指标：

| 指标 | 关注点 |
|---|---|
| 冷启动时间 | 首屏是否被扫描、数据库迁移或播放器初始化阻塞 |
| 点击到首帧 | 本地 URI 打开、解封装和解码器初始化耗时 |
| 丢帧数 | 4K、HEVC、HDR 和倍速播放表现 |
| 扫描耗时 | 不同媒体数量和存储类型下的增量扫描效率 |
| 内存峰值 | 长列表、缩略图和高分辨率视频切换 |
| 安装包体积 | 防止无意引入 Native ABI 或大型传递依赖 |

## 11. 验证策略

### 11.1 自动化测试

- ViewModel 状态转换和错误处理使用 JVM 单元测试；
- Room DAO、迁移和播放进度写入使用数据库测试；
- 全部文件访问、MediaStore 与 SAF 降级流程使用仪器测试；
- 播放服务、通知和 MediaSession 使用设备测试；
- Compose 只覆盖关键导航和播放控制交互，不追求脆弱的全量截图测试。

### 11.2 媒体测试集

测试集至少覆盖：

- H.264/AAC MP4；
- HEVC 8-bit/10-bit MP4 与 MKV；
- VP9/AV1 WebM；
- 4K、HDR10、HLG 和 Dolby Vision 样本；
- AAC、Opus、FLAC、AC3、EAC3、DTS 和 TrueHD 音轨；
- 内嵌与外挂 SRT、WebVTT、SSA/ASS 字幕；
- 多音轨、多字幕轨和章节；
- 超长视频、大文件、损坏文件和权限失效 URI。

格式兼容结论必须来自真机测试，不以文件扩展名或模拟器结果代替。

### 11.3 首版设备范围

- 最低系统版本为 Android 12（API 31）；
- 首版唯一验收真机为 Xiaomi M2012K11AC，Android 13（API 33）；
- Android 12 及其他受支持版本在实际补测前标记为“声明支持但未验证”，不作为首版发布门槛；
- 格式、高分辨率、HDR 和硬件解码结论仅对已运行对应样本的当前真机负责，不外推到其他芯片平台。

## 12. 风险与应对

| 风险 | 应对 |
|---|---|
| 设备硬件解码能力碎片化 | 首版以指定真机建立基线，明确标注其他设备未验证；获得设备后逐步扩展矩阵 |
| 特殊音频轨无声 | 提供音轨切换；收集样本后再评估软件解码扩展 |
| SAF URI 权限失效 | 检查持久权限并引导用户重新授权，不缓存真实路径 |
| 大媒体库扫描卡顿 | 增量同步、批量数据库事务、限制并发和主线程工作 |
| Compose 播放控件状态不同步 | 统一由 Player 事件驱动状态，不轮询 UI |
| 依赖升级引入预览组件 | Version Catalog 固定稳定版本，升级时检查依赖图 |
| Native 后端许可证风险 | 引入前完成 LGPL/GPL、动态链接和源码提供义务评审 |

## 13. 结论

MVP 使用 **Kotlin 2.4.0 + Compose + Media3 + Room/KSP + Coil**。这套方案满足主流格式、高性能、轻量、加载快和易自定义的核心要求，并与现代 Android 生命周期、后台播放和存储权限模型保持一致。

首版只维护 Media3 一套播放内核。全格式兼容通过真实媒体测试集量化，只有当失败样本证明业务价值时，才引入软件解码或第二播放内核。

## 14. 参考资料

- [Android Kotlin 兼容性](https://developer.android.com/build/kotlin-support)
- [AGP 9.3 发布说明](https://developer.android.com/build/releases/gradle-plugin)
- [迁移到 AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [AndroidX Media3](https://developer.android.com/media/media3)
- [Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [MediaSession 后台播放](https://developer.android.com/media/media3/session/background-playback)
- [共享媒体文件与 MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Room](https://developer.android.com/training/data-storage/room)
- [Coil](https://coil-kt.github.io/coil/)
- [KSP](https://github.com/google/ksp)
