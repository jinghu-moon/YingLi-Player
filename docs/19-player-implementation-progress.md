# 播放界面实施证据

> 执行规范：`18-player-ui-implementation-agent-prompt.md`
>
> 更新时间：2026-09-21

## 当前状态（2026-09-21）

本文前半部分保留 Phase 0-2 的历史基线和当时的停线结论；以下状态是当前工作区的有效结论，不能用历史记录替代当前验证。

### 已完成并有测试证据

- `PlaybackSessionRuntime` 已成为 Service 侧单一会话聚合；普通媒体 Open 的 generation、Engine 状态、AB、Surface lease、截图事件和队列顺序均有领域/Runtime 测试。
- 常规播放器页面已通过 `PlaybackSessionClient` 投影状态，覆盖视频信息、四态顺序、队列、AB、布局持久化、截图预览/倒计时/删除和 Vault 路径。
- YLShorts 已具备独立路由、候选队列、自动下一条/循环当前、上下滑切换、横向快进、收藏/黑名单、截图和偏好持久化；新增纯 Kotlin/ViewModel 定向测试已通过。
- `MediaStoreScreenshotFileGateway` 已覆盖成功删除、无效 URI、权限拒绝和删除 0 行；真机插入并删除 MediaStore 文件的 AndroidTest 已存在。
- 新增 Controller 替换客户端连接测试已在 Xiaomi Android 16 真机通过；旧客户端关闭后新 Controller 可重新连接 Service。

### 当前验证结果

| 验证 | 最近结果 |
| --- | --- |
| Shorts 定向 JVM 测试 | 通过；自动下一条、黑名单移除、空队列、手势阈值、截图倒计时均覆盖 |
| Runtime/Vault 定向 JVM 测试 | 通过；含并发 Open、防过时结果和 Vault opaque source |
| MediaStore 删除定向 JVM 测试 | 通过；权限失败与 0 行删除映射稳定 |
| Controller 新客户端连接 AndroidTest | 通过；Xiaomi 25102RKBEC / Android 16 |
| 既有完整 AndroidTest | 需在最后一批代码后重新执行，历史 80/80 早于本轮修改 |

### 尚未完成，不能宣称全部通过

1. 真正的 Service 进程销毁后同一 Controller 自动重连尚未取得稳定设备证据。`stopService` 对仍绑定的 MediaSessionService 不会销毁；强制停止目标进程会连带中止当前 instrumentation，因此该场景需独立 adb/设备脚本验证。
2. 播放器页面仍由 `PlaybackSessionClientBridge` 适配旧 `Media3PlaybackController`；Runtime 已支持 Vault，但应用端尚未完全删除旧 Controller/Bridge 调用链。
3. 播放进度/历史仍不是单写入 Actor，队列持久化已接入 DataStore，但进程重建后的完整恢复语义尚未通过设备级测试。
4. `OpenVault` 已接入 Service Runtime 的 `vaultResolver`，但 Vault 真媒体播放、`FLAG_SECURE`、截图/PiP 禁止仍缺独立真机门禁。
5. 媒体兼容性矩阵缺少 MKV、WebM、H.265、AV1、Opus、AC3、字幕、HDR、多音轨、4K、高帧率、损坏文件、权限撤销、只读、存储不足等真实样本；当前只能记录样本缺失，不能宣称支持。
6. Shorts Compose/TalkBack 和完整真机流程尚未新增专项测试；现有真机结果不包含本轮新增 Shorts 代码。

### 最终退出条件

- 完成上述 1-6 的可执行验证或明确外部阻塞；
- 重新执行完整 `connectedDebugAndroidTest`，结果来自最后一批代码且 0 失败；
- 重新执行完整 JVM、Debug/Release Lint 和 Debug/Release 构建；
- 文档中的“尚未完成”清单为空，或逐项记录经用户确认的外部测试阻塞。

## Phase 0：基线和样本冻结

### 工具链

| 项目 | 冻结值 |
| --- | --- |
| 操作系统 | Windows 11 10.0 amd64 |
| JDK | Eclipse Temurin 21.0.10+7 LTS |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.4.0 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 31 |
| Media3 | 1.10.1 |
| 测试设备 | Xiaomi 25102RKBEC，Android 16 / API 36 |
| ffprobe | 8.1.1 |

### 修改前测试基线

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --rerun-tasks` | 0 | 180 tests，0 failures，0 errors，0 skipped；Lint 0 error / 0 warning / 2 hint；90 tasks executed |
| `.\gradlew.bat :app:compileDebugAndroidTestKotlin` | 0 | AndroidTest Kotlin 编译通过 |
| `.\gradlew.bat :app:connectedDebugAndroidTest` | 1 | 设备拒绝安装测试 APK：`INSTALL_FAILED_USER_RESTRICTED`；执行 0 tests，未得到用例结果 |

首次 `connectedDebugAndroidTest` 的失败发生在测试 APK 安装阶段：`INSTALL_FAILED_USER_RESTRICTED`，执行 0 tests。用户允许继续后，设备已可安装测试 APK；MIUI 后台启动测试 Activity 需要测试期间临时允许 `MIUIOP(10021)`，测试结束已恢复为 `ignore`。

Debug APK：

| ABI | 字节数 |
| --- | ---: |
| arm64-v8a | 90,263,962 |
| armeabi-v7a | 90,262,098 |
| x86_64 | 90,263,508 |
| x86 | 90,262,290 |

Lint 的 2 个既有 Hint 均为 `LibraryScreen.kt` 中 `mutableStateOf(Int)` 的装箱提示，与播放任务无关。

### 真媒体样本冻结

| SHA-256 | 文件 | 媒体事实 |
| --- | --- | --- |
| `491368B221F688F96CD3347840820439CF3EF28700682DC6BC3C9C7A123873A3` | `prototypes/views/assets/横屏.m4v` | MP4/MOV，H.264 720x480 25fps + AAC，95.434s |
| `15AA61EBA874254C29C49DFC2CAD20B5B8333D62AB549264D2561F8D874E5DCC` | `prototypes/views/assets/竖屏.mp4` | MP4，H.264 426x854 30fps，无音轨，24.200s |
| `99B8EC9E1885554EFE9C1C5B4C06A1E37014EB17F17A0AD0489992B53CE6B283` | `prototypes/views/assets/shorts/1.mp4` | MP4，H.264 480x852 30fps，无音轨，62.800s |
| `15AA61EBA874254C29C49DFC2CAD20B5B8333D62AB549264D2561F8D874E5DCC` | `prototypes/views/assets/shorts/2.mp4` | 与 `竖屏.mp4` 内容相同 |
| `69E317AC9C0B11C0714B94CF148A042494A73D1FA1382E6954C9A865F2C6B746` | `prototypes/views/assets/shorts/3.mp4` | MP4，H.264 480x852 29.97fps，无音轨，54.087s |

缺失样本：MKV、WebM、H.265、AV1、Opus、AC3、内嵌/外挂字幕、ASS/SSA、HDR、多音轨、4K、高帧率、损坏文件、权限撤销、只读、Vault 和存储不足。这些能力必须在 Phase 6/最终真机门禁中补测，当前不能据此作兼容性或性能结论。

### 差距矩阵

| 规范条目 | 当前实现 | 根因/缺口 | 目标文件 | 验证方式 |
| --- | --- | --- | --- | --- |
| 单一长期播放会话 | Service 持有 ExoPlayer，但 ViewModel/Controller 分担源解析和长期状态 | 所有权和命令边界分裂 | `domain/playback/session/*`、`app/playback/PlaybackSessionRuntime.kt`、`YingLiPlaybackService.kt` | Runtime 单元测试、Service 重连仪器测试 |
| 每次 Open 只解析一次 | ViewModel 与 Controller 都解析来源 | 缺少带 generation 的 Runtime Open 流程 | `PlaybackSourceContracts.kt`、Runtime | Fake resolver 调用次数与过时结果测试 |
| 后端无关契约 | UI 依赖旧 `PlaybackController`/胖 `AdvancedPlaybackController` | 会话、Engine、能力端口未建立 | `domain/playback/session`、`domain/playback/engine` | 架构测试、Fake Engine 契约测试 |
| 四态队列导航 | `continuousPlayback` 只能表达单一下一项 | 队列数据结构和导航策略错误 | `PlaybackQueueContracts.kt`、`QueueNavigator.kt` | 四态、空/单项/首尾/随机历史测试 |
| AB 全路径限制 | 尚无领域 AB 状态/limiter | UI 与播放命令无法共享边界 | `AbLoop.kt`、Runtime | A/B 顺序、最小帧、Seek/SeekBy/回跳测试 |
| Buffering 可观察 | Media3 Buffering 映射为 Preparing | 播放阶段模型缺失 | 会话快照、Engine state mapper | reducer 与 Media3 映射测试 |
| 进度/历史有序写入 | Service 启动并发 IO 协程，历史固定 10 秒 | 无单写入序列和领域资格策略 | Runtime ProgressCommitter、HistoryEligibilityPolicy | 过时 sequence、强制 flush、10%/30s 测试 |
| Surface lease | Controller 保存 WeakReference<PlayerView> | 旧页面可能解绑新输出 | Phase 3 `Media3SurfacePort` | generation/lease 契约和旋转仪器测试 |
| 常规响应式 UI | 当前只有基础共用布局和局部 settings 状态 | 页面状态/面板/真实能力尚未对齐 | `feature/player/*` | Compose 横竖屏与状态测试 |
| 独立 YLShorts | 不存在 | 一级路由、状态、候选队列和持久化均缺失 | `feature/shorts/*`、导航、data | reducer/ViewModel/Compose/恢复测试 |

### Phase 0 结论

本地可执行基线（JVM、AndroidTest 编译、Debug Lint、Debug 构建）通过。真机仪器测试因设备安装权限被外部阻塞，已固定为可复现基础设施失败。样本哈希和缺口已记录；后续阶段不得把未覆盖媒体矩阵或 0 个真机用例表述为通过。

## Phase 1：领域契约和架构测试

已完成并验证。

新增后端无关契约：`PlaybackSessionContracts.kt` 中的 opaque source handle、`PlaybackSessionSnapshot`、command/event、`PlaybackEngine`、能力端口、`SurfaceLease`、`PlaybackOrder/QueueNavigator`、`AbLoopLimiter/Reducer` 和 `HistoryEligibilityPolicy`；架构测试增加 domain 平台无关、Media3 engine 不向上依赖检查。

阶段测试：

| 命令 | 结果 |
| --- | --- |
| Phase 1 定向领域/架构测试 | 22/22 通过 |
| `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks` | 198 tests，0 failures，0 errors，0 skipped；27 tasks executed |

关键行为：旧 `PlaybackQueue.continuousPlayback` 不再作为新契约依据；顺序/随机/列表循环/单曲重复、空队列、AB 最小帧和媒体切换均由纯 Kotlin 策略测试覆盖。

## Phase 2：Runtime 和 Service 单一所有权

本地实现完成，真机已执行，但完整仪器门禁仍有既有 Compose UI 失败，尚未满足“全部验证通过”。

已完成：

- 新增 `PlaybackSessionRuntime`，将 Open、命令 generation、源解析、Engine 状态、AB 清理、Surface lease、截图事件和单一 snapshot 流收敛到 Service 侧对象。
- 新增 `ServicePlaybackEngine` 和 `SourceHandleRegistry`，Media3 Player 只在 Service 中创建；Engine 只接受 opaque handle。
- 并发 Open 的旧解析结果覆盖测试已先失败后修复，当前 `PlaybackSessionRuntimeTest` 2/2 通过。
- `YingLiPlaybackService` 已创建 Runtime/Engine 并在销毁时释放；`MediaSessionPlayerAdapter` 将 MediaSession 播放、暂停、Seek、跳项、重复模式和速度命令转发到 Runtime/Engine。

阶段验证：

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest --tests seeyuer.yingli.player.app.playback.PlaybackSessionRuntimeTest` | 2/2 通过 |
| `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks` | 198 tests，0 failures，0 errors，0 skipped |
| `.\gradlew.bat :app:lintDebug :app:assembleDebug` | 通过；Debug APK 已生成 |
| `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug` | 通过；198 tests，0 failures，0 errors，0 skipped；61 tasks executed |
| `.\gradlew.bat :app:connectedDebugAndroidTest --rerun-tasks "-Pandroid.testInstrumentationRunnerArguments.class=seeyuer.yingli.player.data.room.YingLiDatabaseMigrationTest"` | Android 16 / API 36 真机，8/8 通过；退出码 0 |
| `.\gradlew.bat :app:connectedDebugAndroidTest --rerun-tasks` | Android 16 / API 36 真机，72 tests：66 通过、6 失败、0 跳过；退出码 1 |

完整真机失败明细（均为已有 Compose UI 可见性断言，未发现新增 PlaybackSession/Media3 失败）：

- `MediaHomeStateTest.dashboardUsesConfiguredOrderAndHidesEmptyCards`：未找到“需要处理”。
- `LibraryScreenTest.gridLayoutShowsIndexedMediaAndSearch`：`library.grid` 未显示。
- `LibraryScreenTest.listLayoutIsReachableWithoutChangingResult`：`library.list` 未显示。
- `AdaptiveAppShellTest.settingsRemainUsableAtTwoHundredPercentFontScale`：未显示“媒体库”。
- `AdaptiveAppShellTest.compactUsesThreeFixedDestinationsAndHomeTopActions`：未找到“搜索” content description。
- `AdaptiveAppShellTest.libraryUsesTopOverflowForViewSettings`：未找到“视频视图设置” content description。

Room 迁移测试最初缺少 1–6 版本 schema；已从提交 `b2d0363` 恢复原始 JSON 到当前 `data.room.YingLiDatabase` 路径，6 个文件的 Git blob 与历史版本逐一一致，定向 8/8 通过。

未满足的退出条件：

1. 完整 AndroidTest 门禁仍有 6 个已有 Compose UI 断言失败，需先修复并重新验证，不能进入常规播放器 UI。
2. Service 重建/Controller 重连的专门仪器用例尚未新增；当前 3 个 `Media3PlaybackControllerTest` 真机用例均通过。
3. 进度/历史单写入 Actor、Room 会话队列和恢复仍属于后续 Phase 4，不应在本阶段宣称完成。

### Phase 0-2 停线结论

Phase 0、Phase 1 和 Phase 2 的本地门禁均有真实结果；设备级安装权限已解除，新增 Media3/迁移用例在 Android 16 真机通过，但完整 72 用例仍有 6 个 UI 失败。因此 Phase 2 尚未达到“全部验证通过”，必须停线修复这些失败并重新执行完整门禁；当前不得扩大到常规播放页 UI 或 YLShorts。工作区保留领域契约、Runtime、Media3 Engine/Session adapter 和测试，未宣称播放器 UI 或 YLShorts 已完成。

## 常规播放器 UI：持续实施记录（2026-09-13）

用户明确要求继续实现常规播放器 UI 后，本轮完成：

- `PlayerScreen` 横屏/竖屏响应式布局；竖屏底部浮岛、横屏完整底栏。
- Seek 拖动预览与松手提交；播放位置通过 `AbLoopLimiter` 钳制。
- 设置面板状态提升到 `PlayerViewModel`，面板互斥、系统返回优先关闭面板/临时工具。
- 截图工具胶囊、真实 `ScreenshotGateway` 捕获、3 秒预览生命周期、倒计时暂停和媒体切换清理。
- AB 工具胶囊、A/B 最小帧间隔、完整时长坐标系标记、Seek/快退/快进限制和到 B 回跳 A。
- 播放列表右侧 Drawer/底部 Sheet，数据来自 `PlaybackQueueRepository`；队列项切换媒体并关闭面板。
- 上一项遵循“当前位置超过 5 秒先回到 0，否则按队列切换”规则。
- 播放器页面通过一次性 `PlayerUiEvent` 展示截图删除成功、权限拒绝和其他失败；预览缩略图保持约 116dp、16:10，操作区满足 48dp 触控目标。
- Vault 播放通过 `PlaybackSessionCommand.OpenVault` 进入安全会话，关闭时统一发送 `Stop`，不再把安全媒体伪装为普通源不可用。
- Service Runtime 在切换四态播放顺序时清空随机历史，避免会话重连后沿用旧随机轨迹。

本轮验证：

| 命令 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest --rerun-tasks` | 207 tests，0 failures，0 errors，0 skipped |
| `:app:lintDebug --rerun-tasks` | 通过，0 error（**2026-09-13 的旧结论，已被下方更正**） |
| `:app:assembleDebug --rerun-tasks` | 通过 |
| `:app:testDebugUnitTest --rerun-tasks` | 221 tests，0 failures，0 errors，0 skipped |
| `:app:compileDebugAndroidTestKotlin --rerun-tasks` | 通过 |
| `:app:lintDebug --rerun-tasks` | 通过，0 error（**2026-09-13 的旧结论，已被下方更正**） |
| `adb shell am instrument ...PlayerScreenStateTest` | Android 16 / API 36 真机，11/11 通过 |
| `:app:connectedDebugAndroidTest --rerun-tasks` | Android 16 / API 36 真机，80/80 通过，0 失败 |

> **更正（2026-10-03）：上表两条 `:app:lintDebug` 的"0 error"已经不是当前事实。** 本批开始前实测 `.\gradlew.bat :app:lintDebug` → **FAILED，25 errors**（`ServicePlaybackEngine` 6 条 `UnsafeOptInUsageError`、`strings.xml` 11 条 `UnusedResources`、`AndroidManifest` 1 条 `PictureInPictureIssue`、`YingLiControls` 2、`Media3VideoSurface` 2、`LibraryScreen`/`PlayerTopBar`/`PlaylistPanel` 各 1），全部在这些旧结论之后、本批之前就存在。这 25 条已于 2026-10-03 逐条处理（修根因优先）。
>
> **再次更正（同日，画中画参数镜像那一批）：那唯一一条 `PictureInPictureIssue` 抑制已经删除。** 当时是"只实现了 `setSourceRectHint`、`setAutoEnterEnabled` 有意不用"所以写了 `tools:ignore` 并附理由；本批把自动进入改成真正的参数镜像（见下文"画中画自动进入的参数镜像"一节），lint 要求的两项都已真实下发，抑制与理由注释一并删除。当前真实状态是 **`.\gradlew.bat :app:lintDebug` → `No issues found.`（0 errors / 0 warnings / 0 hints）**——上一条更正里写的 4 条 `AutoboxingStateCreation` hint 也已经不在报告里。引用本文件时不要再用更旧的结论。

真机验证需要在 Xiaomi/MIUI 设备测试期间临时允许 `MIUIOP(10021)`，Gradle 安装会重置该模式。常规播放器此前已完成 80/80；YLShorts 改动后的本轮重跑在 Windows 结果文件被占用时中止，未获得新的设备级完整结果。媒体格式兼容性矩阵、Service 销毁重建专门生命周期用例仍未覆盖。
# YLShorts implementation

- Added independent `SHORTS` root destination and immersive `ShortsRoute`/`ShortsScreen`; it does not reuse the regular player chrome or page state.
- Added `ShortsViewModel` backed by the real library repository, filtering known portrait media and driving the shared `PlaybackSessionClient`.
- Added persistent Shorts preferences and blocked-media storage through `DataStoreShortsPreferenceRepository`; favorites use `OrganizeRepository` and deletion uses the existing library trash gateway.
- Implemented playback projection, auto-next/repeat-current, portrait navigation, horizontal 5-second seek gestures, fit-mode and speed controls, info dialog, share action, favorite/block actions, delete confirmation, and progress metadata.
- Historical verification: `:app:testDebugUnitTest` 226/226; Debug/Release Lint and builds passed; direct instrumentation completed `OK (80 tests)` before the latest Shorts and Runtime changes. Media-format matrix and Service destroy/recreate lifecycle tests remain uncovered; current status is tracked above.

## 帧号后台校准：真机实测、可中断取消与策略（2026-10-03）

本节的数字全部来自真机（Xiaomi 25102RKBEC / Android 16 / API 36），不是估算或桌面推算。

### 触发与取证方式（可复现）

1. 装包：`adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`（不要用 `install2device.ps1`）。
2. 取日志：`adb logcat -c` 后 `adb logcat -s YingLi:V`（校准计时走项目既有的 `AppLogger` → `AndroidLogSink`，TAG 是 `YingLi`，日志统一带事件码与脱敏）。
3. 触发路径：进播放页 → 打开底部工具托盘（「更多」）→ 点「进入截图模式」。这一刻 `PlayerViewModel.applyFrameCaptureSession(true)` 会调 `PlaybackSessionClient.calibrateFrames()`。

### 实测数字

事件码 `FRAME_CALIBRATION_SCANNED`（`bytes` / `samples` / `elapsedMs` / `throughputMiBPerSecond` 都是事件字段）：

| 文件 | 大小 | 规格 | 视频轨 sample 数 | 耗时 | 派生吞吐 |
| --- | --- | --- | --- | --- | --- |
| `VID_20260506_112001.mp4` | 3,757,807 B（3.6 MiB） | 00:06 / 1280×720 / HEVC | 191 | 21 ms | 170.7 MiB/s |
| `VID_20260430_142408.mp4` | 68,627,617 B（65.4 MiB） | 00:41 / 1920×1080 / HEVC | 1,249 | 104 ms、108 ms（重测） | 606–629 MiB/s |
| `VID_20260406_141914.mp4` | 1,961,021,547 B（1.83 GiB） | 20:30 / 1920×1080 | 36,917 | 2,633 ms | 710.3 MiB/s |
| `VID_20260329_154124.mp4` | 2,345,040,134 B（2.19 GiB） | 19:52 / 1920×1080 | 35,794 | 2,751 ms | 812.9 MiB/s |
| `VID_20260329_161226.mp4` | 2,645,424,630 B（2.46 GiB） | 22:25 / 1920×1080 | 40,370 | 3,132 ms | 805.5 MiB/s |
| 自造 MP4（`AndroidFrameCountProbeTest`） | 680,829 B（0.65 MiB） | 5,000 样本 × 128 B | 5,000 | 95 / 98 / 102 ms（三轮） | 6.4 MiB/s |

### 数字 → 结论 → 策略

- **结论一：耗时不是单纯由体积决定。** 0.65 MiB 的自造容器按带宽算只要 1 ms，实测 95–102 ms —— 因为它有 5,000 个样本。真实 1080p 素材约 60 KiB/样本，两项都会出场。
- **结论二：拟合出的模型是** `耗时 ≈ 15 ms（打开容器）+ 样本数 × 18 µs + 容器 MiB × 1.0`（≈1 GiB/s 顺序读）。上表五个真机点的预测误差都在 ±6% 以内，这条拟合被 JVM 测试 `FrameCalibrationTest.cost model reproduces every measured device scan` 钉住。
  - **⚠ 2026-10-03 更正：这条拟合只在 4 万样本以下成立。** 样本数再往上时每样本成本不是常数（5 千样本 19 µs → 12 万样本 164 µs → 20 万样本 271 µs），旧模型在 12 万样本的容器上低估 9 倍。详见下方「校准耗时模型：上界重标定（2026-10-03）」一节；被钉住的那条用例已按新预期改写。
- **结论三：小文件无感，大文件确实要等。** 66 MiB 只要 104 ms；1.83 GiB 以上是 2.6–3.1 s，已越过"超过 3–5 秒就要有策略"的门槛。
- **结论四（判据）：CFR 素材上"校准完成、`≈`消失，但总帧数没变"是正确行为，不是校准没生效。** 判据是 `真实样本数 == round(容器时长 × 容器帧率)`：只要容器帧率是如实的（含 29.97 这类分数帧率，Media3 报的就是真实平均值），两个口径**逐位相同**，界面上唯一可见的变化就只有 `≈` 消失。2026-10-03 真机复核（同一台 Xiaomi 25102RKBEC / Android 16）：
  - 40 s / 3000 fps / 120,000 样本：`≈ 14506 / 120000`（估算）→ `14506 / 120000`（校准后），日志 `samples=120000`；
  - 3600 s / 29.97 fps / 107,893 样本：`≈ 134 / 107893` → `134 / 107893`，日志 `samples=107893`（证明分数帧率也如实，不是被四舍五入成 30）；
  - 只有这两种情况才会看到数字变化：**(a) 容器帧率缺失/为 0/写错**；**(b) 容器时长与视频轨时间跨度不一致**。判别性素材实测（视频轨 40 s / 120,000 样本 + 400 s 音轨，容器时长 400 s）：估算 `≈ 13918 / 1200000` → 校准后 `13918 / 120000`，日志 `samples=120000` —— 总数按真实样本数整体替换，证明校准值确实接到了显示上。
  - 因此看到"总数没变"时先按这条判据核对，不要再当成"校准结果没被采用"重新排查。
- **采用的策略**：仍是**静默后台校准**（不阻塞 UI、不弹进度条），但当模型估算耗时 ≥ `FRAME_CALIBRATION_NOTICE_THRESHOLD_MILLIS`（600 ms）时，帧数胶囊给整段数字加 `≈`（`player_frame_counter_approximate`），把"这几秒显示的是估算值、随后会换成精确值"告诉用户。
  - 阈值取 600 ms 的理由：帧数胶囊的入场动画本身有 360 ms（`SCREENSHOT_CAPSULE_TRANSITION_MILLIS`），比动画还快的扫描根本来不及被看见，给它加提示只会制造噪声；而 1.83 GiB 以上实测 2.6–3.1 s，用户确实在等。
  - 为什么用**估算耗时**而不是"体积阈值"：字节数只是两个因子之一，只看体积会漏掉"容器不大但样本极多"（40 分钟 60fps ≈ 14.4 万样本 → 约 2.9 s）的形态；估算耗时同时覆盖两项，且时长/帧率/体积缺项时能优雅退化。
  - 为什么不做"允许用户取消"：校准是后台只读任务，用户没有可取消的对象；而跳过校准会让最需要精确帧号的长视频失去精确值。

### 真可中断取消的设备级验证

- 机制：`MediaExtractor` 在 API 36 上没有 `setCancellationSignal`，因此取消分两层——扫描循环每样本自查标记，真正打断阻塞中的 `advance()` 靠**从取消线程 `release()` 容器**（`FrameScanLifecycle` 保证只有一个赢家负责释放，见该文件注释）。
- 用例：`AndroidFrameCountProbeTest`（`app/src/androidTest/java/seeyuer/yingli/player/engine/media3/frame/AndroidFrameCountProbeTest.kt`），自造 200,000 样本 / 27.2 MB 的 MP4，扫到第 2/4/6/8 万样本时从测试线程取消。
- 实测（`FRAME_SCAN_TEST_MEASURED`，两轮运行结果一致）：`cancelReturnMillis=0/0/0/0`，`scanExitMillis=0–2`（两轮分别为 `0/1/1/2` 与 `1/1/1/2`），`interruptedBy=flag/flag/flag/flag`，`cancelled=true`。即：`release()` 在被扫线程处于 native 调用期间**返回耗时 0 ms**（不会阻塞调用者），扫描线程在 ≤2 ms 内退出，所以这是"没跑完就退出"，不是"跑完了才发现要取消"。
  - **⚠ 2026-10-03 更正：本节原来写"整份容器扫完需要约 4 s（19–20 µs/样本）"，那是把 0.65 MiB / 5,000 样本那份容器的每样本成本套到了 20 万样本上。** 实测同样规格（20 万样本 × 128 B / 27.2 MB）的整扫耗时是 **54.2 s（271 µs/样本）**，200,000 × 19.5 µs = 3.9 s 这个推算与实际差 14 倍。取消仍是有效的（就在扫描早期退出），但"整扫只要 4 s"不能再用。
- 诚实边界：这 4 轮里**退出都是被"每样本自查"拦下的**（`interruptedBy=flag`），没有一轮走到"`release()` 让 native 调用抛异常"那条路——因为每样本自查本身就足够快。`release()` 的不可替代价值在于它能打破"单次 `advance()` 长时间不返回"（卡住的 IO）这种情形，而"它不会等着 native 调用返回"这一点已被上面的 0 ms 实测证实。
- 并发安全：4 轮并发 `release()` + `advance()` 与 3 个用例全部通过（两轮运行都是 `Starting 3 tests … Finished 3 tests … 0 failed`），进程没有 native 崩溃。

## 校准耗时模型：上界重标定（2026-10-03）

### 为什么重测

上一节的模型 `15 + 样本数×18µs + MiB×1.0` 在真实文件六点上误差 ≤6%，但在"样本数远大于时长"的容器上严重低估：同一份 40 s / 3000 fps / 120,000 样本的容器实测 15.3 s 与 1.3 s（`docs/19` 原记录），模型只给 2.2 s；真机 3600 s / 29.97 fps / 107,893 样本的容器实测 12.0 s。性能结论必须有依据，所以**先补实测再改模型**。

### 实测方式（可复现）

- 用例：`AndroidFrameScanCostMeasurementTest`（`app/src/androidTest/java/seeyuer/yingli/player/engine/media3/frame/`），自造 MP4：样本数、帧间隔、帧率、每样本字节数都由测试控制。
- 测量走**生产路径** `LocalMediaFrameCounter` + `AndroidFrameCountProbe`（不是裸探针），耗时取自生产日志事件 `FRAME_CALIBRATION_SCANNED` 的 `elapsedMs`，因此与用户在应用里等待的正是同一段时间。
- 事件 `FRAME_SCAN_COST_MEASURED` 额外记录 `microsPerSample`，便于直接看每样本成本。命令：
  `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=seeyuer.yingli.player.engine.media3.frame.AndroidFrameScanCostMeasurementTest" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"`（跑前 `adb shell cmd appops set seeyuer.yingli.player 10021 allow`）。
- 设备：Xiaomi 25102RKBEC / Android 16 / API 36（与上表同一台）。

### 实测数字（冷/热各轮）

| case | 样本数 | 字节 | 帧率 | COLD | 热测 1 | 热测 2 | 热测 3 | 每样本 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `NORMAL_FPS_5K_SAMPLES_128B` | 5,000 | 0.65 MiB | 30 | 96 ms | 99 ms | 96 ms | 98 ms | 19.2–19.8 µs |
| `NORMAL_FPS_5K_SAMPLES_8KIB` | 5,000 | 39.1 MiB | 30 | 287 ms | 284 ms | 291 ms | 285 ms | 56.8–58.2 µs |
| `HIGH_FPS_20K_SAMPLES_128B` | 20,000 | 2.6 MiB | 120 | 792 ms | 742 ms | 778 ms | 784 ms | 37.1–39.6 µs |
| `HIGH_FPS_50K_SAMPLES_128B` | 50,000 | 6.5 MiB | 3000 | 3,845 ms | 3,857 ms | 3,846 ms | 3,871 ms | 76.9–77.4 µs |
| `HIGH_FPS_120K_SAMPLES_128B` | 120,000 | 15.6 MiB | 3000 | 19,425 ms | 20,184 ms | 19,740 ms | 20,068 ms | 161.9–168.6 µs |
| `HIGH_FPS_200K_SAMPLES_128B` | 200,000 | 25.9 MiB | 3000 | 54,196 ms | — | — | — | 271.0 µs |
| `WITH_BACKGROUND_WRITER`（同 5k，另有线程持续写盘） | 5,000 | 0.65 MiB | 30 | 95 ms | 90 ms | 97 ms | — | 18–19.4 µs |

自造容器的时间戳间隔就是 `1/帧率`，所以"样本数 = 时长 × 帧率"在这些容器上逐位成立（200k 那档日志 `samples=199998`，与 66,666 ms × 3000 fps 一致）——这是把"模型输入"与"实际样本数"对齐的前提。

### 结论一：每样本成本不是常数

5,000 → 20,000 → 50,000 → 120,000 → 200,000 样本的每样本成本是 19 µs → 38 µs → 77 µs → 164 µs → 271 µs，**单调上升**，与样本数约 `样本数^0.75` 的幂律相符（以 5,000 样本为原点：`19µs × (120000/5000)^0.72 ≈ 155µs`、`^0.75 ≈ 171µs`；实测 164 µs。20 万档 `^0.75` 给 268 µs，实测 271 µs）。

这不是"合成容器才有"的现象：真实文件里独立测得的那份 107,893 样本容器是 111 µs/样本（12.0 s），与同档位合成容器（12 万样本 164 µs）**同一量级**。旧模型把每样本成本钉在 18 µs，因此在 4 万样本以下很准、往上系统性低估：12 万样本低估 9 倍（2,191 ms vs 19,748 ms）、20 万样本低估 15 倍（3,641 ms vs 54,196 ms）。

顺带更正一处旧推算：`AndroidFrameCountProbeTest` 的取消用例（20 万样本 / 27.2 MB）此前被记成"整份容器扫完约 4 s（19–20 µs/样本）"，那是把 5,000 样本容器的每样本成本外推到了 20 万样本；实测同规格整扫是 **54.2 s**。取消机制本身不受影响（它本来就在扫描早期退出）。

### 结论二：方差（15.3 s vs 1.3 s）的解释与证据

- **同一容器重复测量的方差极小**：上表每一档的 4 轮（含 COLD）极差都在 0.9%–1.5% 以内；12 万样本那档 4 轮是 19.4/20.2/19.7/20.1 s。**冷/热差异不超过 1.2%**（第 1 轮与后续几轮同级），因此"首次读文件、IO 冷热"**解释不了 12 倍差异**。
- **IO 竞争也解释不了**：`WITH_BACKGROUND_WRITER` 在另一线程持续写盘（每次 2 MiB + `fsync`）的同时扫描同一目录，耗时 90–97 ms，与无竞争时的 95–99 ms 同级。
- **判断**：1.3 s 那个读数不可复现，且与 15.3 s 相差 12 倍——在方差 <1.5% 的测量体系里，这只能来自**当时的设备状态**（同一容器在不同后台负载/温控/内存压力下的读数），或那一次记录本身有误。因此**按慢值（15.3 s 一侧）建模**：模型只要漏标，用户看到的就是"精确值"外观的估算帧号，代价远大于多标一个 `≈`。这条判断的证据是上面三行：方差 1.5%、冷热 1.2%、IO 竞争无影响。

### 结论三：模型改成上界

`耗时 ≈ max(15ms + 样本数 × 18µs × (样本数/5000)^0.75 + 容器 MiB × 1.0, 15ms + 样本数 × 18µs + 容器 MiB × 1.0)`

- 三项的物理含义（打开容器 / 逐样本推进 / 顺序读字节）与旧模型一致，只是"逐样本推进"的每样本成本按实测幂律放大；
- `max` 的第二项就是旧模型：它在 **1 秒以内的短扫描**上实测最准（真实素材五点误差 ≤5%），而幂律项在样本少时略低于实测（3.6 MiB 点：幂律 19 ms、旧模型 22 ms、实测 21 ms）。短扫描够不到 600 ms 阈值，所以那一侧优先保住"真实文件误差不变大"；估计值超过 1 秒后才让幂律项单独说话；
- 取 `^0.75` 而不是 `^0.72`：0.72 由 5,000→120,000 样本标定，外推到 20 万样本只有 1.48 倍而实测是 1.65 倍，会低估 5%（51.4 s vs 54.2 s）；0.75 同时覆盖两个点，且偏向"宁可多标"。

错误分布（新模型预测 / 实测）：

| 点 | 实测 | 旧模型 | 新模型 |
| --- | ---: | ---: | ---: |
| 3.6 MiB / 191 样本 | 21 ms | 22.0 ms（1.05×） | 22.0 ms（1.05×） |
| 66 MiB / 1 249 样本 | 104 ms | 102.9 ms（0.99×） | 102.9 ms（0.99×） |
| 1.83 GiB / 36 917 样本 | 2,633 ms | 2,550 ms（0.97×） | 4,862 ms（1.85×） |
| 2.19 GiB / 35 794 样本 | 2,751 ms | 2,896 ms（1.05×） | 5,071 ms（1.84×） |
| 2.46 GiB / 40 370 样本 | 3,132 ms | 3,265 ms（1.04×） | 6,018 ms（1.92×） |
| 107,893 样本（真实） | 12,000 ms | 1,957 ms（0.16×） | 17,747 ms（1.48×） |
| 20,000 样本 | 756 ms | 378 ms（0.50×） | 1,036 ms（1.37×） |
| 50,000 样本 | 3,855 ms | 922 ms（0.24×） | 5,083 ms（1.32×） |
| 120,000 样本 | 19,748 ms | 2,191 ms（0.11×） | 23,452 ms（1.19×） |
| 200,000 样本 | 54,196 ms | 3,641 ms（0.07×） | 57,299 ms（1.06×） |

即：真实文件从"±5% 的点估计"变成"1.05–1.92 倍的上界"，高帧率容器从"低估 4–15 倍"变成"1.06–1.37 倍的上界"。真实文件"不退化"的口径是**误差不越出旧模型的量级**，机器判据写在 `FrameCalibrationTest.cost model bounds every measured device scan`（3 秒以上的实测点必须满足 `预测 >= 实测`；短扫描点只要求同量级，并单独断言真实文件比值上限）。

### 阈值：维持 600 ms（附推出过程）

新模型下 `估算耗时 ≥ 600 ms` 的边界是**约 1.4 万样本 / 约 600 MiB**：5,000 样本给 105 ms、10,000 给 318 ms、14,000 给 561 ms（不标），20,000 给 1,033 ms（标），500 MiB 给 515 ms（不标）、600 MiB 给 615 ms（标）。实测里 2 万样本的容器实测 756 ms 确实在等，所以这条边界与"用户确实在等"吻合；600 ms 相比胶囊入场动画 360 ms 仍留了余量。**没有实测依据支持改这个数字，就不改**——本轮补的实测点只用来修"样本数"那一项的低估。

### 已知边界

1. 模型只有 `样本数` 与 `字节数` 两个自变量，而"样本载荷大小"会影响每样本成本（8 KiB/样本 → 39.1 MiB / 5,000 样本实测 287 ms，模型给 144 ms，低估 2 倍）。真机实测里没有这种素材（真实 1080p 是数十 KiB/样本，走 `MiB × 1.0` 那一项），因此不为它加系数。
2. 模型输入是容器元数据（时长 × 帧率），不是真实样本数。元数据不可靠时（例如某些自造容器的 moov 时长被写成 4.8 s 而视频轨实际 40 s），估算样本数会小于真实样本数，估算耗时随之偏低——这类容器只能靠真实样本数才能算准，属于校准本身要解决的问题，模型无法先知。
3. `FRAME_SCAN_COST_MEASURED` 的数据取自一台设备（Xiaomi 25102RKBEC / Android 16）。换设备/换 ROM 需要重新标定，不能把这里的数字外推到其他芯片平台。
4. 120,000 样本那档用 4 轮跑过一次，整份用例跑到 32 分钟被 framework 掐掉（单轮约 20 s × 4 + 容器构造）。因此本类对"单轮十几秒以上"的容器只重复 1–2 轮，方差结论主要由 5k/20k/50k 三档的 4 轮给出。

## `LibraryScreenTest` 既有失败断言的结论（2026-10-03）

本批之前 `LibraryScreenTest` 被记为"2~3 条失败断言"（`docs/19` 上一节的 6 条真机失败清单里占了 2 条）。本批实跑该类（`--tests` 见下方命令）得到**真实失败清单只有 1 条**：`listLayoutIsReachableWithoutChangingResult` 在 `onNodeWithText("64 MB")` 处失败，失败停在第一条断言，因此 `onNodeWithText("1080P")` 那条当时没有被执行到（本批把第一条修正后它同样失败）。

判定：**两条都是断言过时，不是真 bug。** 证据与理由：

- 设备上把列表行的语义树打出来，文本节点的实际内容是 `.../64.0 MB/1920 × 1080`。列表行的两个信息胶囊现在由共享组件 `MediaListRow` 渲染，`formatMediaListFileSize` 对 MB 及以上**一律一位小数**（64 MiB → `64.0 MB`），分辨率用**完整宽高**（1920×1080 → `1920 × 1080`）。
- 这两条断言写于 `b84e09d`（媒体库首次落地），当时列表行还在 `LibraryScreen` 内联实现：文件大小走 `formatFileSize`（整数值省小数 → `64 MB`）、分辨率走 `resolutionLabel`（短边 → `1080P`）。此后列表行被抽成 `MediaListItem.kt` 供播放页队列复用，口径随之改成现在的样子，**断言没有跟着更新**，所以它从那时起就一直失败。
- 为什么新行为正确：`MediaListRow` 同时服务媒体库列表和播放页播放队列，两处用同一份格式化函数是"同一数值在任何界面显示一致"的前提；列表行给完整宽高比给短边更精确，也与播放器信息面板（`1920 x 1080`）口径一致。断言强度没有削弱：仍是**精确文本匹配**，只是匹配当前正确值。
- 与"文件夹过渡 `AnimatedContent` 改用目标值"那次真 bug 修复无关：该用例用 `browseMode = ALL_VIDEOS`，根本不走文件夹分支；失败原因是元数据文案，不是过渡期间内容错配。

## 本机 instrumented 测试环境（MIUI/HyperOS）

### 现象

`connectedDebugAndroidTest` 报 `Process crashed`，只跑 1 个用例就卡住，`adb logcat -b crash` 为空。

### 真因

Compose 测试宿主 `androidx.activity.ComponentActivity` 的启动被 MIUI 拒绝：logcat 里是 `MIUILOG- Permission Denied Activity` + `result code=102 = START_ABORTED`，于是 `ActivityScenario.launch` 永久挂起；进程最终被最近任务 `SwipeUpClean` force-stop，UTP 这时才报 `crashed`。参考 android-test#1875。

### 解决

给 **app 包**（不是 `.test` 包）打开"后台弹出界面"：

```
adb shell cmd appops set seeyuer.yingli.player 10021 allow
adb shell cmd appops get seeyuer.yingli.player 10021   # 应输出 MIUIOP(10021): allow
```

### 配套

- UTP 默认跑完卸载 APK，会清空该授权；建议加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` 保留安装，避免下一轮又要重新授权。
- 完整命令示例（只跑相关类）：
  `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=seeyuer.yingli.player.engine.media3.frame.AndroidFrameCountProbeTest" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"`

### 取证注意

- 该 ROM logcat 刷得极快（`-t 4000` 仅覆盖约 3 秒），要**边跑边落盘**（`adb logcat -s YingLi:V` 常驻），事后翻缓冲区往往已经滚掉。
- 机器上装有 LSPosed 模块会 hook `ActivityStarter`，排查启动被拒时要把它算进变量。
- Gradle 安装/卸载会重置 `MIUIOP(10021)`；`MANAGE_EXTERNAL_STORAGE` 的 appop 目前是 allow（本机媒体库能直接按路径读文件，instrumented 用例也依赖这一点）。

## 画中画自动进入的参数镜像（2026-10-03，真机验证）

本节所有数字都来自真机（Xiaomi 25102RKBEC / Android 16 / API 36，1200×2608 物理像素、480dpi＝3px/dp、
状态栏 144px），不是推算。

### 为什么去掉 `onUserLeaveHint` 手动进入

AOSP 依据（源码行为，不是感觉）：

1. `TaskFragment.startPausing(...)`：当 `supportsEnterPipOnTaskSwitch && userLeaving && resumingOccludesParent
   && checkEnterPictureInPictureState(...) && pictureInPictureArgs.isAutoEnterEnabled()` 全部成立时，
   直接调用 `ActivityTaskManagerService.enterPictureInPictureMode(..., fromClient=false)`，**不再排带
   `userLeaving` 的 pause**；进入后由 `ActivityTaskManagerService.enterPictureInPictureMode` 尾部
   （`r.isState(PAUSING) && r.mPauseSchedulePendingForPip`）以 `userLeaving=false` 补排 pause。
2. `ActivityThread.handlePauseActivity(..., userLeaving, autoEnteringPip, ...)`：只有 `userLeaving` 为真才
   调用 `performUserLeavingActivity`（`onPictureInPictureRequested` + `onUserLeaveHint`）。
3. `ActivityTaskManagerService.enterPictureInPictureMode`：`if (fromClient && r.isState(PAUSING) &&
   params.isAutoEnterEnabled())` 直接返回 false，日志是
   `Skip client enterPictureInPictureMode request while pausing, auto-enter-pip is enabled`。

结论：武装 auto-enter 之后，`onUserLeaveHint` 那条手动路径**既不会被回调、回调了也会被系统拒绝**，
所以直接删除（本批 `MainActivity` 不再覆写 `onUserLeaveHint`），只保留用户点击画中画按钮的手动进入。

### 参数镜像与下发时机

- 合法性只由 `shouldAutoEnterPictureInPicture(偏好, 安全内容, 是否有媒体)` 判定（JVM 单测 4 例）。
- 下发唯一入口：`ActivityPictureInPictureGateway.applyAutoEnter(...)`（内部 `pushParams()` 是唯一的
  `setPictureInPictureParams` 调用点，参数三项——宽高比、source rect、autoEnter——同在一个构造点）。
- 触发时机：`MainActivity` 一个收集器订阅策略输入；另有 `videoSurfaceBounds` 布局信号、
  `onResume`、`onConfigurationChanged` 触发"用同一份镜像值重发一次"；**处于 PiP 期间一律不下发**。
- 单实例：`ActivityPictureInPictureGateway` 现在由 `MainActivity` 创建一次、播放页/短视频页/窗口网关共用
  （系统侧的参数是一份快照，多实例各自拼参数会互相覆盖）。

### 真机实测

| 项目 | 命令/取证 | 结果 |
| --- | --- | --- |
| 偏好开启后镜像是否真的下发到系统 | `adb shell dumpsys activity activities \| grep autoEnterPipEnabled` | `autoEnterPipEnabled: true` |
| 偏好关闭 | 同上 | `autoEnterPipEnabled: false`（显式撤销，不是"从没下发"） |
| 偏好开启 + 播放中按 Home | `adb shell input keyevent KEYCODE_HOME` | 自动进入 PiP：`mWindowingMode=pinned`、`mBounds=Rect(484,144-1182,537)`（16:9，与下发的宽高比一致）、`mLastReportedPictureInPictureMode=true`，前台是 `com.miui.home` |
| 偏好关闭 + 播放中按 Home | 同上 | **不进入** PiP：`mLastReportedPictureInPictureMode=false`、无 pinned 窗口 |
| 是否双重进入 | `adb logcat` 里搜 `Skip client enterPictureInPictureMode`（该日志只在"客户端在 PAUSING 阶段请求进入"时打印） | **无该日志** = 本次离开全程没有客户端进入请求，只有系统一次进入 |
| source rect 是否在新一次下发里刷新 | 临时观测日志（验证后已删除）打 `pushParams()` 实参 | 修前：仅一次 `autoEnter=true rect=Rect(0,0-1200,2608)`（视频尺寸未知时输出视图还是整窗大小，等于没有起点提示）；补上"输出视图布局变化"这个触发后：`rect=Rect(0, 966-1200, 1641)`，与截图里量到的 letterbox 画面区（y 966..1641）逐像素一致 |

未在真机上覆盖：保险库/应用锁（`secureContent=true`）分支（需要先启用应用锁并放私密文件，本轮未做；
该分支与"偏好关闭"共用同一条下发路径，只是输入取值不同）、概览/助手等 transient 场景、
低版本行为（minSdk=31，`setAutoEnterEnabled` 自 API 31 起存在，不存在"低版本回落"这件事）。

## 帧数胶囊的顶部 inset 双计（2026-10-03，真机像素取证）

`PlayerScreen` 的帧数胶囊曾经同时用了两种 inset 处理：`windowInsetsPadding(safeDrawing)` 与把
`safeDrawing.getTop()` 加进 `frameCounterTopPadding`。真机取证（1920×1080 视频、进入截图模式）：

| 状态 | 胶囊文本节点 bounds（uiautomator，屏幕像素） | 胶囊顶边（截图像素扫描，x∈[400,800]） | 期望值 |
| --- | --- | --- | --- |
| 修复前 | `[462,534]-[738,590]` | **y=516** | 状态栏 144 + 顶栏 192 + 间距 36 = 372 |
| 修复后 | `[462,390]-[738,446]` | **y=372** | 372 |

516 = 2×144 + 192 + 36，正好多算了一条状态栏；修复后正好落回 372，位移 144px。结论：**确实是双计**，
根因是 inset 的所有权不清；现在规定"inset 只由窗口内边距修饰符负责，偏移量只负责浮层之间的相对距离"，
并把这条写进了 `PlayerScreen` 的注释。

## A-B 循环方案阶段 0 的 P0 验证：T0.1（U7–U10）与 T0.2（2026-10-03，真机 spike）

对应 `docs/20-ab-loop-refactor-plan.md` §5 阶段 0 的 **T0.1（U7–U10）** 与 **T0.2**。本研究用的是
**临时 spike（不在主线）**：结束已把 spike 源码删除，`git status` 干净（见本节末尾"回退证据"）。

### 环境与方法

| 项目 | 取值 |
| --- | --- |
| 设备 | Xiaomi 25102RKBEC（`f3ba305a`）/ HyperOS V816 / Android 16 / API 36 |
| Media3 | 1.10.1（与 app 一致） |
| 素材 | `prototypes/views/assets/横屏.m4v`，SHA-256 `491368B2…3873A3`，MP4/MOV H.264 720×480 **25fps** + AAC，95.36 s（**一帧 = 40 ms**） |
| 区间 | A = 20 000 ms，B = 22 000 ms（`ClippingMediaSource(A, B)`）；U10 另用 [60 000, 62 000] 交叉验证 |
| 跳转精度 | `SeekParameters.EXACT`（与 `docs/20` §4.2"AB 胶囊打开期间强制 EXACT"一致） |
| 观测手段 | ① 每帧 `VideoFrameMetadataListener` 的**绝对媒体 PTS**（微秒）；② 100 ms 位置/状态采样；③ `Player.Listener` 状态与 `onPositionDiscontinuity`；④ `AnalyticsListener` 的 `onRenderedFirstFrame` / `onVideoFrameProcessingOffset` / `onDroppedVideoFrames`；⑤ 同一时刻读 `Window` / `Period` 字段做坐标系对照 |

**必须先说明的三条 spike 环境事实**（都踩过、都有日志，不是推断）：

1. **播放器必须跑在 app 主 Looper 上。** 用自定义 `HandlerThread` 作 `ExoPlayer.Builder.setLooper(...)`
   时，播放器永远停在 `BUFFERING`、timeline 始终为空（`duration=TIME_UNSET`、`tracks=0`），媒体源
   一次都没被读取。改到主 Looper 后同一份代码立刻 `READY`。
2. **视频渲染器需要一个会被消费的 Surface。** 只用 `setVideoSurface(null)` 或"挂了 `SurfaceTexture`
   但不 `updateTexImage()`"都会卡在准备阶段；本研究建了私有 EGL 上下文，在专用线程上持续
   `updateTexImage()`，`READY` 与出帧才正常。
3. **instrumentation 进程里音频路径起不来。** 音频解码器从未被创建，只要音频轨参与准备，播放器就
   永远 `BUFFERING`。因此本研究**只选视频轨**（`DefaultTrackSelector` 关闭 `TRACK_TYPE_AUDIO`）。
   → **音频间断这一项本轮无法验证**（见"未能覆盖"）。

### T0.1-U7 / U8 / U9：单一裁剪源的区间外能力与坐标语义

对照组（未裁剪）：

```text
BASELINE_UNCUT      durationPlayerMs=95458  durationWindowMs=95458  seekable=true  advancedMsIn2s=2010  framesIn2s=50
```

包裹 `ClippingMediaSource(20000, 22000)` 之后（同一条日志、同一台设备）：

```text
U9_CLIPPED_SEMANTICS_AT_A            durationPlayerMs=2000  durationWindowMs=2000
                                    durationClippedLengthMs=2000  durationFullMs=95458
                                    positionInFirstPeriodUs=20000000  currentPositionMs=2017
                                    advancedMsIn2s=0  framesIn2s=0  seekable=true
U7_SEEK_BEFORE_A                     seekTargetMs=12000  positionOnSeekMs=2010  advancedMsIn2s=0  framesIn2s=0
                                    playerState=ENDED  playedContentBeforeA=false
U8_SEEK_AFTER_B                      seekTargetMs=28000  positionOnSeekMs=2012  advancedMsIn2s=0  framesIn2s=0
                                    playerState=ENDED
U8_AFTER_B_SETTLED                   positionMs=2012  playerState=ENDED  playWhenReady=true  isPlaying=false
```

把同一时刻的窗口/周期字段并排读出来，坐标系就完全确定了：

```text
COORD_POSITION_VS_OFFSET  currentPositionMs=1038   durationMs=2000  windowDurationMs=2000
                          windowPositionInFirstPeriodUs=20000000  windowDefaultPositionUs=0
                          periodPositionInWindowUs=-20000000      periodDurationUs=22000000  periodIndex=0
```

`Period.getPositionInWindowUs() = -20000000`（即 −A）说明：**"时长"与"位置"用的不是同一个原点**，
而 `currentPosition` 报的是**窗口内位置**（0..2000），不是媒体绝对时间。

**逐条回答**：

- **U7（能否播 A 之前的内容）：不能，而且比"钳到 A"更糟。** seek 到 12000 ms 这一请求的落点是
  `2010`（把它当成"窗口内 12000 ms"去钳，结果钳到窗口末尾），随后 `advancedMsIn2s=0`、
  `framesIn2s=0`、`playerState=ENDED`：**既没有播到 A 之前，也没有回到 A 继续播，而是直接结束**。
- **U8（区间外 seek 的行为）：钳到窗口末尾并 `ENDED`，不回到 A。** seek 到 28000 → 停在 2012、
  `ENDED`、`isPlaying=false`；`playWhenReady` 仍为 `true`，但不会有任何进展。事件面上只出现
  `onPositionDiscontinuity(SEEK)`（old 2010 → new 1999），**没有** `onMediaItemTransition`。
- **U9（位置/时长是绝对值还是片段相对值）：两者口径不一致，这是最要命的一条。**
  - `player.duration` = **片段长度**（2000 ms）；
  - `player.currentPosition` = **窗口内位置**（1038..2017），不是绝对媒体时间；
  - 同一次 discontinuity 里 `newPositionMs` 又出现过 `20000`（绝对）；
  - `Window.positionInFirstPeriodUs` = 20000000（绝对），`Period.positionInWindowUs` = −20000000。
  → 进度条按"位置/时长"画会直接错（位置 1038 / 时长 2000，看起来像"播到一半"，实际在绝对 21 s 处）；
    而且**位置轨道与 seek 目标是两个不同的度量**（见下）。
- **seek 目标的坐标系与位置上报的坐标系不一致（本轮最关键的发现）**。逐目标实测
  （窗口 [20000, 22000]，时长 2000）：

  | 请求 seek 到 | 实际落点 | 状态 | 解释 |
  | --- | --- | --- | --- |
  | `0` | `667`（即窗口内 ~0，偏差来自解码起步） | READY | 命中窗口起点 |
  | `500` | `1157`（窗口内 ~500） | READY | 命中 |
  | `1000` | `1663`（窗口内 ~1000） | READY | 命中 |
  | `1500` | `2000` | ENDED | 越界 → 钳到窗口末尾 |
  | `2000` | `2014` | ENDED | 等于时长 → 末尾 |
  | `2500` | `2012` | ENDED | 越界 → 末尾 |
  | `21000`（= 绝对 A+1000） | `2015`，`framesIn2s=0` | ENDED | 越界 → 末尾，**且不再播放** |

  同一现象在另一个窗口上复现（[60000, 62000]，`positionInFirstPeriodUs=60000000`）：

  ```text
  COORD_CLIP_60_62_OPEN_ABS_60000   positionOnReady=1999  positionAfter2s=2017  positionDeltaMs=17  framesIn2s=1
  COORD_CLIP_20_22_OPEN_REL_0       positionOnReady=28    positionAfter2s=2004  positionDeltaMs=1978  framesIn2s=44
  ```

  **结论**：`seekTo(x)` 的 `x` 是**窗口内（片段相对）**坐标，而 `currentPosition` 报的也是窗口内坐标，
  但 `Window`/`Period` 字段是绝对媒体时间；`docs/20` §4.1 的"重建前记录绝对位置、重建后 seek 回"
  以及"循环回 A = `Seek(A)`"在裁剪源上**会 seek 到区间之外**，把播放器打成 `ENDED`。要在裁剪源上成立，
  必须同时改写"目标 A/B → 窗口内坐标"与"窗口内位置 → 绝对位置（进度条/截图/历史/上一首）"两侧，
  即 `docs/20` §2.4 的全局映射层**比预计的范围更大**（不只是展示层，还包括所有 seek 入口）。

  另一个反直觉但必须记录的实测：**"打开就在起点"也不安全**。
  `open(clipped, 20000)`（即绝对 A）会让播放器直接 `ENDED`、一帧不出；只有 `open(clipped, 0)`
  （窗口起点）才能正常播完整段（`advancedMsIn2s=1978`、`framesIn2s=44`）。
  显式声明 `setRelativeToDefaultPosition(true)` 也**不能**改变这一点（`positionDeltaMs=0`、`framesIn2s=0`）。

### T0.1-U10：切源（未裁剪源 ⇄ 裁剪循环源）的无缝性

事件序列（原始 JSON，节选）：

```text
U10_UNCUT_STARTED              startMs=16000  dur=95458
SWAP_TO_CLIPPED_FIRED          value=20005  tMs=4244
DISCONTINUITY                  reason=REMOVE  oldPositionMs=20005  newPositionMs=20000
PLAYBACK_STATE                 BUFFERING -> READY -> ENDED
U10_AFTER_SWAP_TO_CLIPPED      positionMs=2014  playerState=ENDED  durationMsWhenClipped=2000
SEEK_POSTED                    value=15000
U10_SEEK_OUTSIDE_CLIPPED       requestedMs=15000  positionMs=2011  playerState=ENDED
SWAP_BACK_TO_UNCUT_FIRED       value=2011  tMs=42475
DISCONTINUITY                  reason=REMOVE  oldPositionMs=2011  newPositionMs=15000
U10_AFTER_SWAP_BACK_TO_UNCUT   positionMs=17582  playerState=READY  durationMsWhenUncut=95458
summary                        swapInRebuffers=3  swapInFrameGaps=3
```

逐帧墙钟（从 A−1.5 s 开始记录，25fps 的正常帧间隔是 40 ms）：

```text
最大帧空洞（相邻两帧的墙钟差）：
  104 ms   t=4222..4326     pts 20040000 -> 21840000
  53/52/51 ms  （正常抖动）
切源点（SWAP_TO_CLIPPED_FIRED@t=4244）±200 ms 内的空洞 = [40, 51, 31, 32, 51, 104]
重缓冲时长 = [60, 36, 116] ms（3 次，均值约 71 ms）
切回未裁剪源之后恢复正常：空洞 [20, 10, 40, 41] ms
```

**判定**：

- **黑帧/停顿：切源瞬间有约 104 ms 的帧空洞**，是名义帧间隔（40 ms）的 **2.6 倍**，
  超过 `docs/20` §3.4 的"≤1 帧"，因此**不满足"无缝"**。
- **重新缓冲：切源 3 次出现 3 次 `STATE_BUFFERING`（36/60/116 ms）**，§3.4 要求循环点 0 次 → **不达标**。
- **位置连续性：不成立。** 进入裁剪源后 `currentPosition` 从 20005（绝对）变成 2014（窗口内），
  切回未裁剪源又回到 15000（绝对）：`DISCONTINUITY(REMOVE)` 记录到 −18 s 级别的跳变。
  即使"看起来数字没跳"（`newPositionMs=20000` 那次），也只是两套坐标系的巧合，不是连续。
- **切换"是否无缝"（按 §3.4 口径）：不通过。** 而且这一条**无法通过"实现得更小心"来绕过**，
  因为根因是 U7–U9 的坐标系不匹配：进入区间后播放器即 `ENDED`，切源换来的是一个静止的末帧。

### T0.2：方案 A（手动 `seek(A)` + 强制 `EXACT`）的对照

同一素材、同一区间、同一台设备、同一条观测链（循环方式与现实现一致：**播到接近 B 再 seek 回 A**）。

| 指标 | 实测 | §3.4 阈值 | 判定 |
| --- | --- | --- | --- |
| 落点误差（50 次，PTS 对 A 的偏差） | **50/50 = 0 µs**，唯一取值 `{0}` | ≤1 帧（40 ms） | ✅ 通过，且是帧精确 |
| 第 1 次 / 第 2 次 / 第 50 次误差 | `0 / 0 / 0` µs | 无累积漂移 | ✅ 通过（全程恒为 0） |
| 无累积漂移 | 前 10 次与后 10 次均值都是 0 | 首末同量级 | ✅ 通过 |
| 循环点是否重新缓冲 | **50/50 次都出现 `STATE_BUFFERING`**；每次持续 **27–45 ms**（均值 37.3 ms，n=49 次测得时长） | **日志计数 = 0 才算通过** | ❌ **不通过** |
| 丢帧/画面空洞 | `frameGapCount=50`（每次循环点一次），`framesRenderedPerRound` 中位数 = 1 | 逐帧录像无空洞 | ❌ 不通过（见下方口径说明） |
| 音频间断 | **未能验证**（见"未能覆盖"） | 无静音段超过 1 帧 | ⚠️ 无证据 |

口径说明（避免把"重缓冲"读成"卡很久"）：27–45 ms 的 `STATE_BUFFERING` **短于一个帧间隔（40 ms）**，
所以它更接近"一帧的抖动"而不是"明显卡顿"；但 `docs/20` §3.4 对重缓冲写的是**零容忍的日志计数**，
按该口径**方案 A 不通过**。这一条是"阈值是否过严"的产品选择，不是测量误差。

### 两个方案对 §3.4 的逐项对照

| §3.4 项 | 通过条件 | 切源方案（D8-A） | 方案 A（手动 seek + EXACT） |
| --- | --- | --- | --- |
| 循环落点误差 | ≤1 帧 | ❌ **测不到**（进入裁剪源后 `ENDED`，一帧不出） | ✅ 0 ms（帧精确） |
| 无累积漂移 | 50 次内首末同量级 | ❌ 同上 | ✅ 全程恒 0 |
| 不得重新缓冲 | 日志计数 = 0 | ❌ 3/3 次切源都重缓冲（36/60/116 ms） | ❌ **50/50 次循环点都重缓冲（27–45 ms）** |
| 音视频间断 | 逐帧无空洞、音频静音 ≤1 帧 | ❌ 切源处 104 ms 帧空洞（2.6 帧） | ❌ 循环点每次 1 帧空洞；**音频未测** |
| 主观无卡顿 | 与上面四项**同时**满足 | ❌ 前提不成立 | ⚠️ 客观项未全过，主观不作为通过依据 |

**总结论：按 `docs/20` §3.4 现有阈值，两个方案都不达标。** 不同的地方在于失败方式：

- **切源方案（D8-A）是被能力阻断**：单一裁剪源没有"区间外"能力（U7），且 seek/位置坐标系与
  现有"绝对毫秒"口径冲突（U9），切源处还有 2.6 帧的黑帧空洞（U10）。
- **方案 A 是被"零重缓冲"这一条卡住**：落点精度反而是帧精确的，唯一（但客观）的失败是每次循环点
  一次 27–45 ms 的重缓冲。

按 `docs/20` §1.1，这里**不默认回退即通过**，把取舍选项列为：

1. **接受轻微停顿**（收窄验收口径）：选方案 A，把 §3.4 的"重缓冲计数 = 0"改为
   "循环点重缓冲 ≤ 1 帧（40 ms）且不累积"；需明确写入体验口径，并补一次音频验证。
2. **收窄为 D8-B**（改 `docs/20` §1.1/§3.1 与 `docs/16`/`docs/17`）：放弃"区间外播放/可拖出区间"，
   换取单一裁剪源；但**仍要解决坐标系与位置映射**（U9），且要重新验证裁剪源在全片段上的播放
   （本轮的 `framesIn2s` 在 [20,22] 上正常，但 `open(绝对 A)` 直接 `ENDED` 这一点必须绕开）。
3. **换其它实现**：先解决"位置/seek 是两套坐标系"这个根因（唯一映射层 + 所有 seek 入口改口径），
   再评估"可变裁剪"（`docs/20` U11 / T0.7）是否能让 A/B 调整不重建、从而避免切源。
4. **放弃无缝要求**（产品侧接受循环点可见一帧的接缝）：这是最省事但必须显式确认的选项。

### 对 `docs/20` 的修正建议（只报告，本轮未改文档结构）

1. **§1.1 / §4.1 必须补"seek 坐标系"这条约束**：裁剪源上 `seekTo(x)` 的 `x` 是窗口内坐标，
   而 `currentPosition`/`Window.positionInFirstPeriodUs`/`Period.positionInWindowUs` 三者的原点并不相同；
   "重建前记录绝对位置、重建后 seek 回"与"循环回 A = `Seek(A)`"都必须先做坐标换算，否则直接 `ENDED`。
2. **§2.4 / T2.4 的映射层范围要扩大**：不只是"展示层换算"，还包括**所有 seek 入口**（进度条拖动、
   ±1 帧、上一首位置判定、截图取帧时间戳、恢复播放进度）——它们是写路径，裁剪后都会落到错误位置。
3. **§3.4 的"重缓冲计数 = 0"在真机上做不到**（方案 A 实测 50/50 次）：需要明确"如果接受轻微停顿，
   阈值改成什么"，否则阶段 2 的 T2.10 会永远无法通过。
4. **§4.3 U11 的措辞要修正**：`ClippingMediaSource.Builder.setAllowDynamicClippingUpdates(...)` 的官方
   注释是"裁剪窗口随 **live window** 移动"，**不是**"运行时改变裁剪边界"；"可变裁剪"是否存在，
   需要在 T0.7 用 API 核对而不是默认它存在。
5. **§4.1 的包装形态建议显式化相对模式**：同时声明 `setRelativeToDefaultPosition(true/false)`，
   因为两种模式的实测差异（位置原点）会直接改变映射层的写法。
6. **§2.3 的"引擎自己解析 URI"这一条被本轮证实是可行的**（`DefaultMediaSourceFactory` + 本地
   `file://` 在同一进程可正常准备/播放），问题不在建源入口，而在上面的坐标系。

### 未能覆盖 / 风险

1. **音频间断没有验证**：instrumentation 进程里音频渲染器无法就绪（音频解码器从未创建，播放器因此
   永远 `BUFFERING`），本轮被迫只选视频轨。方案 A 的"音频是否可观测间断"、以及 §3.4 的音频判定
   必须在**能出声的宿主**（真实 app/service 进程或手工真机操作）上补测。
2. **没有逐帧录像取证**：本研究播放器渲染到离屏 `SurfaceTexture`（为了获得逐帧 PTS 与帧计数），
   屏幕上看不到画面，`screenrecord` 无法用于本次循环点。因此"黑帧"是用**帧 PTS 的墙钟空洞**判定的
   （104 ms / 每次 1 帧），不是像素级验证；像素级黑帧检测留给能上屏的宿主补做。
3. **只覆盖一种素材、一个设备**：720×480 25fps H.264+AAC、95.36 s，A=20 s/B=22 s。未覆盖
   VFR、长 GOP 以外的编码、4K/高帧率、音频主导素材。§3.4 的基线说明同样适用于本节的数字。
4. **未做**：U1 的端到端影响面（截图文件名时间戳、恢复播放进度、历史、上一首）、U2（可靠循环事件）、
   U3/U4（重建代价、MediaSession/通知/PiP）、U5/U6（后台/锁屏/PiP 持续）、U11（可变裁剪）——
   按任务要求留给下一批，本轮不给结论。

### 回退证据

spike 只新增了一个未跟踪文件 `app/src/androidTest/java/seeyuer/yingli/player/spike/AbLoopSpikeTest.kt`，
**未修改任何主线源码**（因此结束时删除该文件即完全回退）。删除该文件后、**写入本证据章节之前**：

```text
$ git status --porcelain
（空）
$ git status --porcelain --untracked-files=all
（空）
```

本节落笔后（即最终状态）只剩本证据章节这一处改动：

```text
$ git status --porcelain
 M docs/19-player-implementation-progress.md
$ git diff --stat
 docs/19-player-implementation-progress.md | 271 ++++++++++++++++++++++++++++++
 1 file changed, 271 insertions(+)
```

随后用**回退后的源码**重新构建并安装测试 APK，并跑既有用例确认设备/构建链未被污染：

```text
$ .\gradlew.bat :app:assembleDebugAndroidTest
BUILD SUCCESSFUL
$ adb install -r -d app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
Success
$ adb shell "am instrument -w -r -e class …AndroidFrameCountProbeTest#countingReadsEverySampleOfTheSyntheticContainer \
    seeyuer.yingli.player.test/androidx.test.runner.AndroidJUnitRunner"
OK (1 test)
```

spike 取证材料（全部在 gitignore 的 `build/` 下，不进入提交；`build/spike/`）：

| 文件 | 内容 |
| --- | --- |
| `AbLoopSpikeTest.kt.spike` | spike 源码快照（含三个正式用例与两个坐标系/可播放性探针） |
| `spike-androidTest.apk` | 可直接复跑的 spike 测试 APK |
| `run_spike.ps1` | 跑测脚本（含本机两个 `am` 怪癖：整条命令必须是一个字符串、组件必须写成 `包/runner`） |
| `analyze.py` / `frames.py` / `frametrace.py` | 三个分析脚本（误差统计、重缓冲时长、帧空洞） |
| `json/u7_u8_u9.json`、`json/u10.json`、`json/t02.json`、`json/probe_coord.json`、`json/probe_clip.json` | app 侧原始 JSON 证据 |
| `f_u10-*-logcat.txt`、`f_t02-*-logcat.txt`、`h_coord-*-logcat.txt` 等 | 逐帧/事件 logcat 原始落盘 |

设备侧已清理：`adb shell cmd appops set seeyuer.yingli.player 10021 ignore`（恢复为 `ignore`）、
删除 `/data/local/tmp/spike_land.m4v` 与 app 私有目录 `files/spike/`、`files/spike_land.m4v`。
