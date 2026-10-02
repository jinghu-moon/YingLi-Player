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
| `:app:lintDebug --rerun-tasks` | 通过，0 error |
| `:app:assembleDebug --rerun-tasks` | 通过 |
| `:app:testDebugUnitTest --rerun-tasks` | 221 tests，0 failures，0 errors，0 skipped |
| `:app:compileDebugAndroidTestKotlin --rerun-tasks` | 通过 |
| `:app:lintDebug --rerun-tasks` | 通过，0 error |
| `adb shell am instrument ...PlayerScreenStateTest` | Android 16 / API 36 真机，11/11 通过 |
| `:app:connectedDebugAndroidTest --rerun-tasks` | Android 16 / API 36 真机，80/80 通过，0 失败 |

真机验证需要在 Xiaomi/MIUI 设备测试期间临时允许 `MIUIOP(10021)`，Gradle 安装会重置该模式。常规播放器此前已完成 80/80；YLShorts 改动后的本轮重跑在 Windows 结果文件被占用时中止，未获得新的设备级完整结果。媒体格式兼容性矩阵、Service 销毁重建专门生命周期用例仍未覆盖。
# YLShorts implementation

- Added independent `SHORTS` root destination and immersive `ShortsRoute`/`ShortsScreen`; it does not reuse the regular player chrome or page state.
- Added `ShortsViewModel` backed by the real library repository, filtering known portrait media and driving the shared `PlaybackSessionClient`.
- Added persistent Shorts preferences and blocked-media storage through `DataStoreShortsPreferenceRepository`; favorites use `OrganizeRepository` and deletion uses the existing library trash gateway.
- Implemented playback projection, auto-next/repeat-current, portrait navigation, horizontal 5-second seek gestures, fit-mode and speed controls, info dialog, share action, favorite/block actions, delete confirmation, and progress metadata.
- Historical verification: `:app:testDebugUnitTest` 226/226; Debug/Release Lint and builds passed; direct instrumentation completed `OK (80 tests)` before the latest Shorts and Runtime changes. Media-format matrix and Service destroy/recreate lifecycle tests remain uncovered; current status is tracked above.
