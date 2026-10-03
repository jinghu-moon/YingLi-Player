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

> **更正（2026-10-03）：上表两条 `:app:lintDebug` 的"0 error"已经不是当前事实。** 本批开始前实测 `.\gradlew.bat :app:lintDebug` → **FAILED，25 errors**（`ServicePlaybackEngine` 6 条 `UnsafeOptInUsageError`、`strings.xml` 11 条 `UnusedResources`、`AndroidManifest` 1 条 `PictureInPictureIssue`、`YingLiControls` 2、`Media3VideoSurface` 2、`LibraryScreen`/`PlayerTopBar`/`PlaylistPanel` 各 1），全部在这些旧结论之后、本批之前就存在。这 25 条已于 2026-10-03 逐条处理（修根因优先，仅 1 条 `PictureInPictureIssue` 有理由抑制，理由写在 `AndroidManifest.xml` 的注释里），当前真实状态是 **`lintDebug` 通过：0 errors / 0 warnings / 4 hints**；4 条 hint 均为 `AutoboxingStateCreation`（`LibraryScreen` 2、`PlayerTransportControls` 2），不阻断构建，留作后续单独处理。引用本文件时不要再用旧结论声称"lint 一直通过"。

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
- 实测（`FRAME_SCAN_TEST_MEASURED`，两轮运行结果一致）：`cancelReturnMillis=0/0/0/0`，`scanExitMillis=0–2`（两轮分别为 `0/1/1/2` 与 `1/1/1/2`），`interruptedBy=flag/flag/flag/flag`，`cancelled=true`。即：`release()` 在被扫线程处于 native 调用期间**返回耗时 0 ms**（不会阻塞调用者），扫描线程在 ≤2 ms 内退出；整份容器扫完需要约 4 s（19–20 µs/样本），所以这是"没跑完就退出"，不是"跑完了才发现要取消"。
- 诚实边界：这 4 轮里**退出都是被"每样本自查"拦下的**（`interruptedBy=flag`），没有一轮走到"`release()` 让 native 调用抛异常"那条路——因为每样本自查本身就足够快。`release()` 的不可替代价值在于它能打破"单次 `advance()` 长时间不返回"（卡住的 IO）这种情形，而"它不会等着 native 调用返回"这一点已被上面的 0 ms 实测证实。
- 并发安全：4 轮并发 `release()` + `advance()` 与 3 个用例全部通过（两轮运行都是 `Starting 3 tests … Finished 3 tests … 0 failed`），进程没有 native 崩溃。

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
