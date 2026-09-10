# 17 播放架构重构规范：会话、后端与播放页接口

> 文档性质：播放域的目标架构、破坏性重构计划、前端接口契约和验证门禁
>
> 状态：开发期执行规范，允许删除、重命名和替换现有播放实现
>
> 更新时间：2026-09-10
>
> 适用范围：常规播放页的横屏、竖屏，以及未来独立的 YLShorts 页面；Media3、未来 libmpv 和 FFmpeg 的边界

本文件是播放专项架构规范。它与以下文档共同构成事实源：

- [14-local-android-phone-architecture.md](14-local-android-phone-architecture.md)：全项目 Android、本地媒体、模块边界和平台策略。
- [16-player-ui-ux-interaction-implementation-spec.md](16-player-ui-ux-interaction-implementation-spec.md)：播放页 UI/UX、交互状态和前端可见行为。
- [architecture/phase-4-playback-contract.md](architecture/phase-4-playback-contract.md)：已经交付的基础播放契约和历史约束。
- [architecture/phase-7-advanced-playback-contract.md](architecture/phase-7-advanced-playback-contract.md)：高级控件、截图、系统集成和测试范围。
- [17-player-ui-implementation-agent-prompt.md](17-player-ui-implementation-agent-prompt.md)：给 UI 实现 Agent 的执行提示词，不定义后端架构。

---

## 1. 结论先行

### 1.1 冻结的播放边界

YingLi 的播放系统必须被拆成三个问题：

```text
播放页 UI / YLShorts UI
          |
          v
PlaybackSessionClient          <- 页面只看到稳定会话状态和命令
          |
          v
PlaybackSessionRuntime         <- Service 内唯一长期会话
          |
          +--> PlaybackEngineRouter
                    |
                    +--> Media3PlaybackEngine  (当前唯一实现、默认后端)
                    +--> MpvPlaybackEngine     (未来可选播放后端)

ProcessingCoordinator          <- 与播放会话平行的处理域
          |
          +--> Media3TransformerEngine
          +--> FFmpegProcessingEngine          (未来可选处理后端)
```

必须遵守以下不变量：

1. 一个播放会话同一时刻只激活一个播放后端。
2. `YingLiPlaybackService` 是播放会话和活动后端的唯一所有者；Activity、ViewModel、Composable 不创建或销毁 Player/native context。
3. UI 只依赖 `PlaybackSessionClient` 的领域状态、能力和命令，不依赖 ExoPlayer、MediaController、libmpv、`Surface`、`Uri` 或 `ContentResolver`。
4. Media3 与 libmpv 是 `PlaybackEngine` 的不同实现，不是两个同时工作的播放器，也不是在任意播放中途无提示切换的黑盒兜底。
5. FFmpeg 是独立的 `ProcessingEngine`，用于探测、Remux、转码、切片、字幕烧录和抽帧等任务，不进入 `PlaybackSessionRuntime`。
6. 横屏和竖屏属于同一个 `RegularPlayerRoute`，只改变窗口和控件投影；YLShorts 是独立一级页面，复用后端但不复用常规页面状态机。
7. 所有长期播放状态（队列、顺序、AB、进度、历史、后端选择结果、后台策略）归 Service 侧会话，不归 `PlayerViewModel`。
8. 所有 UI 能力必须先在领域契约中可测试，再由 Android/Media3 实现；“按钮存在但点击只 Toast”不算实现完成。

### 1.2 当前与未来的技术定位

| 技术 | 当前阶段 | 目标职责 | 不允许承担的职责 |
| --- | --- | --- | --- |
| Media3/ExoPlayer | 立即使用 | 常规本地播放、MediaSession、通知、音频焦点、PiP 协同、轨道选择 | 业务队列规则、Compose 状态、文件处理工作流 |
| libmpv/mpv-android | 不立即进入主线；先做 Spike | Media3 无法覆盖的容器、解码、ASS/SSA、滤镜、复杂字幕和渲染 | 直接被 UI 调用；取代会话层；承担转码 |
| FFmpeg | 不进入当前播放链路；按真实样本触发 | 探测、Remux、转码、精确切片、字幕烧录、批处理 | 作为完整 Android 播放器；由 PlayerViewModel 直接调用 |
| Media3 Transformer | 当前处理默认实现 | 常规导出和简单转码 | 复杂 FFmpeg 专属场景的强行替代 |
| Compose | 当前 UI | 横屏、竖屏、设置、截图、AB、YLShorts 投影 | 直接访问播放内核和文件系统 |

### 1.3 需要立即修正的 `14` 结论

`14-local-android-phone-architecture.md` 当前仍写着“不长期并存第二播放内核、mpv 仅作参考”。这与已确定的未来 libmpv 计划冲突。本文件对播放域采用以下更新解释：

> Media3 是当前默认且唯一实际后端；未来允许接入 libmpv 作为可选播放后端。同一会话只激活一个后端，UI 和业务不依赖具体后端。FFmpeg 是独立媒体处理后端，不默认作为播放后端。

在 `14` 直接修订前，本文件作为播放专项的补充决策；后续应在 `14` 的播放章节加入反向链接，避免两个文档长期漂移。

---

## 2. 范围、非目标与术语

### 2.1 本次重构范围

- 现有 Media3 播放链路的会话化和职责重划。
- 常规播放器的播放、暂停、重试、停止、上一项、下一项、进度拖动、倍速、画面比例、音轨、字幕、截图、AB、播放顺序、旋转、全屏、PiP、锁定、布局快捷槽和视频信息。
- Service 后台播放、音频焦点、耳机断开、通知、进度/历史串行写入。
- 播放源、SAF/MediaStore/Vault 的统一解析和授权错误。
- 面向未来 libmpv 的后端接口、能力协商、Surface 生命周期和 MediaSession 适配点。
- 面向未来 FFmpeg 的处理域边界、任务状态、取消和诊断。
- 与 `16` 的前端可消费状态和命令一一对应。
- JVM、架构、Compose、Media3 仪器和真机媒体矩阵的前后回归门禁。

### 2.2 明确非目标

以下内容不在本次播放架构中：

- 网络媒体协议、云同步、账号和远程播放。
- Android TV、桌面、iOS 或跨平台播放器抽象。
- 现在就集成 libmpv 或打包 FFmpeg native 库。
- 为保留旧 `PlaybackController` 形状而添加 Adapter/Legacy API。
- 由 UI 临时模拟播放、截图、AB、队列或处理进度。
- 通过复制第二套 PlayerViewModel 来支持 YLShorts。

### 2.3 术语

| 术语 | 定义 |
| --- | --- |
| 播放会话 | 一个由 Service 持有、可跨页面和后台持续的媒体播放上下文，包含当前媒体、队列、顺序、进度和后端实例 |
| 播放后端 | 实现 `PlaybackEngine` 的具体内核，例如 Media3 或 libmpv |
| 处理后端 | 实现 `ProcessingEngine` 的任务执行器，例如 Media3 Transformer 或 FFmpeg |
| 页面连接 | UI 对 Service 会话的只读订阅和命令入口，不拥有播放资源 |
| Surface lease | 带有 generation/token 的视频输出租约，防止旧页面解绑新页面输出 |
| 首帧前切换 | 当前后端尚未向用户输出第一帧前完成的后端选择或回退 |
| 真媒体矩阵 | 以容器、编码、字幕、轨道、HDR、分辨率和设备组合组织的可复现样本集 |

---

## 3. 当前实现基线：真实调用链与责任

### 3.1 当前调用链

```text
MainActivity
  -> YingLiApp / PlayerScreen
       -> PlayerViewModel
            -> PlaybackController (Media3PlaybackController)
                 -> MediaController
                      -> YingLiPlaybackService
                           -> ExoPlayer + MediaSession
```

当前关键类：

| 当前类 | 当前事实 | 重构去向 |
| --- | --- | --- |
| `app/YingLiPlaybackService.kt` | 创建 ExoPlayer、MediaSession；周期性写进度和历史 | 保留 Service 入口，内部改为持有 `PlaybackSessionRuntime` |
| `engine/media3/Media3PlaybackController.kt` | 应用级 MediaController；解析来源、映射状态、选择轨道、控制速度、绑定 PlayerView | 删除作为总控的职责，拆成 `Media3PlaybackEngine` + `Media3SessionAdapter` + 页面连接 |
| `feature/player/PlayerViewModel.kt` | 解析来源、标题、命令、偏好恢复、截图、PiP、Overlay 和位置投影 | 只负责页面状态投影和 UI 事件；长期会话能力移出 |
| `domain/playback/PlaybackModels.kt` | 基础状态、请求、来源仓储、Controller | 破坏性重构为会话、命令、事件和后端无关源句柄 |
| `domain/playback/AdvancedPlaybackContracts.kt` | 速度、轨道、截图、PiP、队列和 Overlay 混合 | 拆成窄接口；Overlay 留在页面域，播放能力留在会话域 |
| `data/preferences/InMemoryPlaybackQueueRepository` | 队列只在进程内存中存在 | 替换为 Room 会话队列；开发期删除内存实现 |
| `engine/media3/Media3VideoSurface.kt` | 页面直接把 `PlayerView` 交给 Controller | 改为带 lease 的 `VideoSurfacePort`，Service/Engine 验证 token |
| `MainActivity` | 参与视频输出开关、PiP、方向和配置变化 | 只管理系统窗口和页面连接，不管理内核所有权 |

### 3.2 已确认的问题与根因

| 问题 | 根因 | 影响 | 目标修复 |
| --- | --- | --- | --- |
| 媒体源重复解析 | `PlayerViewModel.open()` 和 `Media3PlaybackController.prepare()` 都调用 `PlaybackSourceRepository` | 竞态、重复 I/O、旧结果覆盖新媒体 | 由 Runtime 一次解析并生成 `ResolvedPlaybackSource`/`PlaybackSourceHandle` |
| 旧异步结果覆盖新请求 | Controller 没有统一 request generation；解析完成后仅比较部分状态 | 快速切换视频时播放错误媒体 | 所有命令带 `SessionCommandId`，Runtime 只接受当前 generation |
| 队列不可跨重启 | `InMemoryPlaybackQueueRepository` | 通知、恢复和下一项不可靠 | Room 持久化队列、顺序、游标和 shuffle 历史 |
| Buffering 映射为 Preparing | Media3 `STATE_BUFFERING` 被映射到初次加载状态 | 播放中缓冲时 UI 错误显示初次加载 | 增加 `Buffering` 状态并携带 reason/可操作命令 |
| 后台策略不完整 | 离开页面只关闭视频轨，未由偏好决定暂停/继续 | “迷你播放器、后台音频、自动 PiP”语义不一致 | Runtime 根据 `BackgroundPlaybackPolicy` 统一决策 |
| PiP/方向/全屏不是真实状态 | UI 主要发命令，未订阅系统确认状态 | 旋转失败或配置变化后状态漂移 | `WindowPlaybackState` 由 Activity gateway 回传真实状态 |
| 进度写入可能乱序 | Service 的 `writeScope.launch` 可并发提交旧位置 | 旧位置覆盖新位置 | 单写入 Actor/Mutex，按媒体和序列号丢弃过时样本 |
| 历史阈值固定 10 秒 | Service 常量与产品规则不一致 | 短视频/长视频历史判定不一致 | `min(30s, 10%)` 的领域策略，支持无痕和完成条件 |
| 截图结果过窄 | `ScreenshotResult.Saved` 只有展示名 | 无法预览、倒计时、点击暂停和删除 | 返回 `ScreenshotArtifact`，UI 只接收受控 token/展示元数据 |
| Surface 只有一个 WeakReference | Controller 以单个 `PlayerView` 表示输出 | PiP、配置变化、页面替换时旧视图可解绑新视图 | lease/generation 输出绑定，内核只接受当前租约 |
| 轨道偏好按语言恢复 | 同语言多轨无法稳定选择 | 切换视频后选错音轨/字幕 | 保存稳定轨道指纹，按能力降级到语言 |
| UI 和后端能力混合 | `AdvancedPlaybackController` 同时暴露所有能力 | 新后端必须实现无意义方法，违反接口隔离 | `PlaybackSession` + capability-specific ports |

### 3.3 修改前基线

在实施任何代码重构前必须记录：

- `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`。
- `:app:testDebugUnitTest --rerun-tasks` 的用例数、失败数和耗时。
- 现有 Media3 仪器测试 `Media3PlaybackControllerTest` 的结果。
- 现有 `PlayerViewModelTest`、进度策略、错误映射、队列和 Overlay 测试结果。
- 真机样本的首帧、播放、暂停、Seek、后台、旋转、PiP、截图和多轨结果。

已知基线：JVM 测试曾强制重跑并通过 179 个用例；后续执行必须重新采集，不得把历史数字当作本次重构结果。

---

## 4. REX-Player 调研：采用与拒绝

调研对象为 `refer/REX-Player-master`。该项目的优点值得参考，但其播放内核所有权模型不能直接移植。

### 4.1 REX 的事实

- `PlayerActivity` 约 2618 行，`PlayerViewModel` 约 1997 行。
- 约 53 个 Kotlin 文件直接访问 `MPVLib`，其中约 20 个位于 UI 控件目录。
- Activity、Headless Controller 和 Service 之间通过进程级 MPV 单例交接播放内核。
- `MPVLifecycleLock` 用于避免 native 重复初始化。
- Activity 和 Service 分别创建 MediaSession。
- Service 不真正拥有播放内核，部分队列行为依赖 Activity listener。
- 截图、Surface、控制栏和 native 状态存在较强的页面耦合。

### 4.2 决策矩阵

| REX 做法 | YingLi 是否采用 | 决策理由 |
| --- | --- | --- |
| libmpv 能力覆盖复杂字幕、滤镜和格式 | 有条件采用 | 作为未来 `MpvPlaybackEngine` 的能力来源；必须由真实失败样本触发 |
| `MPVLifecycleLock` 防止 native 重复初始化 | 采用原则，不复制全局写法 | 由 Service/Engine 生命周期单例保证；锁只能保护 native 初始化，不能成为业务状态仓库 |
| Activity 直接访问 `MPVLib` | 拒绝 | 违反 UI 与后端隔离，阻碍测试和后端替换 |
| Activity 销毁/重建时交接 native 播放器 | 拒绝 | 播放内核应由 Service 持有，页面只重新申请 Surface lease |
| Activity 与 Service 双 MediaSession | 拒绝 | 系统通知、耳机键和外部 Controller 必须只有一个权威会话 |
| Headless Controller 与 Activity 共享全局状态 | 拒绝 | 用 `PlaybackSessionRuntime` 的单一状态流和命令队列替代 |
| 只拆 Manager 文件但共享大量全局状态 | 拒绝 | 按职责和依赖边界拆分契约、Runtime、Engine 和 UI 投影 |
| mpv 的字幕/滤镜/轨道模型 | 采用为 Engine 内部适配 | 通过稳定的 `TrackDescriptor`、`SubtitleCapability` 转译，禁止泄漏 mpv 属性名 |
| native 播放器与 MediaSession 的适配 | 采用目标 | 先验证 `MediaSessionPlayerAdapter`，通过后再产品化 libmpv |

### 4.3 不可妥协的架构差异

YingLi 不复制以下模式：

```text
Composable -> MPVLib
Activity -> native player lifecycle
Service -> Activity listener -> queue command
Activity MediaSession + Service MediaSession
```

YingLi 的目标必须是：

```text
Composable -> ViewModel -> PlaybackSessionClient
                                  |
                                  v
                  Service -> PlaybackSessionRuntime -> PlaybackEngine
                                                        |
                                                        +-> Media3
                                                        +-> libmpv
```

---

## 5. 目标分层与依赖规则

### 5.1 逻辑目录

在当前单 `app` 模块中先按包边界落地；只有出现可测量构建或依赖隔离收益时才拆 Gradle 模块。

```text
core/
  common/                 时间、ID、Result、日志、调度器
  model/                  MediaId、LocationId 等跨域值对象
  diagnostics/            脱敏诊断、指标和 trace
  testing/                Fake、Fixture、TestClock、共享断言

domain/playback/
  model/                   会话、队列、时间线、轨道、AB、错误
  policy/                  顺序、续播、历史、后台、后端选择
  session/                 SessionCommand、SessionEvent、SessionSnapshot
  source/                  PlaybackSourceHandle、SourceResolver 契约
  engine/                  PlaybackEngine、Capabilities、SurfaceLease
  processing/              ProcessingEngine 和任务契约

data/playback/
  room/                    队列、进度、历史、偏好实体和 DAO
  source/                  MediaStore、SAF、Vault 解析实现
  preferences/             DataStore 实现
  artifacts/               截图和处理产物存储

engine/playback/
  api/                     稳定后端契约（可单独成为模块）
  media3/                  Media3PlaybackEngine、SessionAdapter、Surface
  mpv/                     未来 MpvPlaybackEngine、JNI/native 生命周期

engine/processing/
  api/                     ProcessingEngine、任务事件
  media3-transformer/      当前常规处理
  ffmpeg/                  未来 FFmpeg worker/native 实现

app/playback/
  YingLiPlaybackService
  PlaybackSessionRuntime
  PlaybackEngineRouter
  MediaSessionPlayerAdapter

feature/player/
  PlayerViewModel           常规播放页投影
  RegularPlayerRoute        横屏/竖屏布局
  PlayerOverlayState        页面 Overlay 和面板互斥

feature/shorts/
  ShortsViewModel           独立 YLShorts 页面投影
  ShortsSessionContext      短视频队列/手势/预加载上下文
```

### 5.2 依赖方向

```text
core <- domain
core <- data implements domain
core <- engine implements domain
domain <- app/runtime
domain <- feature/viewmodel
data + engine -> app composition root
feature -> domain session client only
```

严格规则：

- `domain` 不导入 `android.*`、Compose、Room、Media3、mpv、FFmpeg、`Uri`、`Surface`。
- `feature` 不导入 `androidx.media3.*`、`MPVLib`、Room DAO 或 `ContentResolver`。
- `engine` 不依赖 `feature`；`engine/media3` 不依赖 `app` 的组合根。
- `app` 是唯一把 Service、Runtime、Repository、Engine 和 Gateway 装配起来的位置。
- `data` 不能把数据库 Entity 直接返回到 `domain` 或 `feature`。
- 测试替身放在 `core/testing` 或 test source set，不进入生产包。

### 5.3 为什么当前不立即拆物理模块

当前项目仍是单 `app` Gradle 模块。先使用包级架构测试冻结边界，等以下证据出现再拆物理模块：

- libmpv/FFmpeg native ABI 需要独立构建和 R8 规则；
- engine 与 feature 的编译时间或依赖冲突可量化；
- 处理任务需要独立 worker/test fixture；
- 依赖图可以通过 Gradle module API 强制隔离，而不是复制接口。

---

## 6. 领域模型与会话状态

### 6.1 播放源：领域不保存 URI

现有 `PlaybackRequest` 直接关联 `MediaLocationId`，`ResolvedPlaybackSource` 暴露字符串 URI。目标模型必须把“逻辑媒体身份”和“后端可打开的来源句柄”分开：

```kotlin
@JvmInline
value class PlaybackSessionId(val value: String)

@JvmInline
value class PlaybackCommandId(val value: Long)

data class PlaybackSourceHandle(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val accessHandleId: SourceAccessHandleId,
    val displayName: String,
    val durationMillis: Long?,
)

data class PlaybackOpenRequest(
    val sessionId: PlaybackSessionId,
    val mediaId: MediaItemId,
    val sourceContext: PlaybackSourceContext,
    val startPositionMillis: Long,
    val incognito: Boolean,
)

interface PlaybackSourceResolver {
    suspend fun resolve(request: PlaybackOpenRequest): Result<PlaybackSourceHandle>
}

@JvmInline
value class SourceAccessHandleId(val value: String)
```

`PlaybackSourceHandle` 只携带不可解释的 handle ID。`data/playback/source` 内部的 `PlaybackSourceRegistry` 以该 ID 短期持有 URI、文件描述符、Vault 解密会话或临时文件等 Android 资源；`engine/media3` 和未来 `engine/mpv` 通过各自的 `EngineSourceAdapter` 获取后端输入。Registry 必须绑定 session generation、支持显式释放且不落盘。这样 domain 中既不出现 Android 类型，也不把敏感 token 泄漏到页面状态、日志和持久化队列。源解析必须一次完成；后端只接受已经校验 generation 的 opaque source。

### 6.2 播放顺序与队列

删除当前仅有 `continuousPlayback` 的队列模型，改为明确的顺序策略：

```kotlin
enum class PlaybackOrder {
    SEQUENCE,
    SHUFFLE,
    QUEUE_REPEAT,
    SINGLE_REPEAT,
}

data class PlaybackQueueSnapshot(
    val queueId: String,
    val mediaIds: List<MediaItemId>,
    val currentIndex: Int,
    val order: PlaybackOrder,
    val shuffleHistory: List<Int>,
)

interface QueueNavigator {
    fun next(queue: PlaybackQueueSnapshot, ended: Boolean): NavigationDecision
    fun previous(queue: PlaybackQueueSnapshot, currentPositionMillis: Long): NavigationDecision
}
```

规则：

- `SEQUENCE` 到末尾停止并显示结束状态。
- `SHUFFLE` 不连续选择当前项，记录会话历史，使“上一项”可逆。
- `QUEUE_REPEAT` 到末尾回到队列第一项。
- `SINGLE_REPEAT` 只影响自然结束；用户手动“下一项”仍进入队列下一项。
- 没有明确队列时，“下一项/上一项”命令返回 `NO_CANDIDATE`，UI 不自行猜测。
- 队列、游标和顺序的更新由 Runtime 原子提交，页面只观察快照。

### 6.3 状态模型

现有 `Idle/Preparing/Ready/Playing/Paused/Ended/Failed` 需要扩展为明确的缓冲、后端和输出状态：

```kotlin
sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    data class Resolving(val commandId: PlaybackCommandId) : PlaybackPhase
    data class Preparing(val backend: BackendId) : PlaybackPhase
    data class Ready(val canPlay: Boolean) : PlaybackPhase
    data class Playing(val startedAtElapsedMillis: Long) : PlaybackPhase
    data class Paused(val reason: PauseReason) : PlaybackPhase
    data class Buffering(val reason: BufferingReason) : PlaybackPhase
    data class Ended(val next: MediaItemId?) : PlaybackPhase
    data class Failed(val error: PlaybackError) : PlaybackPhase
}

data class PlaybackTimeline(
    val positionMillis: Long,
    val durationMillis: Long?,
    val bufferedPositionMillis: Long,
    val isSeekable: Boolean,
)

data class PlaybackSessionSnapshot(
    val sessionId: PlaybackSessionId?,
    val mediaId: MediaItemId?,
    val title: String?,
    val phase: PlaybackPhase,
    val timeline: PlaybackTimeline,
    val queue: PlaybackQueueSnapshot?,
    val abLoop: AbLoopState,
    val backend: BackendSnapshot,
    val output: VideoOutputState,
    val capabilities: PlaybackCapabilities,
)
```

`Buffering` 必须与 `Preparing` 区分。UI 的播放图标、进度可拖动性、错误按钮和“初次加载”文案均从 `phase` 和 `capabilities` 推导，不维护第二个 `isPlaying` 布尔值。

### 6.4 命令与事件

```kotlin
sealed interface PlaybackSessionCommand {
    data class Open(val request: PlaybackOpenRequest) : PlaybackSessionCommand
    data object Play : PlaybackSessionCommand
    data object Pause : PlaybackSessionCommand
    data class Seek(val positionMillis: Long, val origin: SeekOrigin) : PlaybackSessionCommand
    data class SeekBy(val offsetMillis: Long) : PlaybackSessionCommand
    data object Stop : PlaybackSessionCommand
    data object Retry : PlaybackSessionCommand
    data object Next : PlaybackSessionCommand
    data object Previous : PlaybackSessionCommand
    data class SetOrder(val order: PlaybackOrder) : PlaybackSessionCommand
    data class SetSpeed(val speed: PlaybackSpeed) : PlaybackSessionCommand
    data class SelectTrack(val selection: TrackSelection) : PlaybackSessionCommand
    data class SetScale(val mode: VideoScaleMode) : PlaybackSessionCommand
    data class SetAbPoint(val point: AbPoint) : PlaybackSessionCommand
    data object ClearAb : PlaybackSessionCommand
    data class BindSurface(val request: SurfaceBindRequest) : PlaybackSessionCommand
    data class UnbindSurface(val lease: SurfaceLease) : PlaybackSessionCommand
    data object CaptureFrame : PlaybackSessionCommand
}

sealed interface PlaybackSessionEvent {
    data class StateChanged(val snapshot: PlaybackSessionSnapshot) : PlaybackSessionEvent
    data class OneShotFeedback(val feedback: PlaybackFeedback) : PlaybackSessionEvent
    data class ScreenshotReady(val artifact: ScreenshotArtifact) : PlaybackSessionEvent
}
```

每个命令由 Runtime 分配 `PlaybackCommandId`，结果包含 `Accepted/AlreadyApplied/Rejected` 以及稳定拒绝码。命令处理必须串行化；耗时的源解析、截图编码和数据库写入在对应 Dispatcher 执行，但结果回到 Runtime 时必须检查 command/session generation。

---

## 7. 播放后端契约

### 7.1 窄接口原则

不要让一个“万能 Controller”迫使所有后端实现所有能力。基础接口和能力端口如下：

```kotlin
interface PlaybackEngine {
    val state: StateFlow<EngineState>
    val capabilities: StateFlow<PlaybackCapabilities>

    suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMillis: Long)
    fun bindSurface(request: SurfaceBindRequest): SurfaceLease
    fun unbindSurface(lease: SurfaceLease)
    fun release()
}

interface FrameStepControl {
    fun stepFrame(direction: FrameStepDirection): CommandResult
}

interface SnapshotControl {
    suspend fun captureFrame(request: FrameCaptureRequest): Result<ScreenshotArtifact>
}

interface TrackControl {
    val tracks: StateFlow<TrackSnapshot>
    fun select(selection: TrackSelection): CommandResult
}

interface SubtitleControl {
    fun setSubtitleStyle(style: SubtitleStyle): CommandResult
}

interface VideoTransformControl {
    fun setScaleMode(mode: VideoScaleMode): CommandResult
    fun setRotation(rotation: VideoRotation): CommandResult
}

interface AudioDelayControl {
    fun setAudioDelay(delayMillis: Long): CommandResult
}

interface ShaderControl {
    fun setShader(shader: ShaderPreset): CommandResult
}
```

`PlaybackEngine` 不暴露 Media3 `Player`、`MediaController`、`MediaItem`，不暴露 mpv property 名称或 JNI 指针。可选端口由 `PlaybackCapabilities` 判断后再向 UI 暴露。

### 7.2 能力模型

```kotlin
data class PlaybackCapabilities(
    val backend: BackendId,
    val supportedContainers: Set<String>,
    val supportedVideoCodecs: Set<String>,
    val supportedAudioCodecs: Set<String>,
    val subtitleFormats: Set<SubtitleFormat>,
    val supportsAss: Boolean,
    val supportsFilters: Boolean,
    val supportsFrameCapture: Boolean,
    val supportsFrameStep: Boolean,
    val supportsPip: Boolean,
    val supportsAudioOnlyBackground: Boolean,
    val supportsSecureSource: Boolean,
)
```

能力不是静态 UI 开关。页面必须同时检查：当前媒体、当前后端、Vault 安全策略、系统能力和页面模式。例如：

- `supportsFrameCapture=false` 时截图按钮禁用并显示原因，不点击后才失败。
- Vault 内容即便后端支持截图，也必须由安全策略强制 `allowScreenshot=false`。
- PiP 由 Activity gateway 确认系统支持，不能只看后端能力。
- `ORIGINAL` 如果 Media3 无法提供像素级模式，必须返回能力降级结果并显示明确反馈，不能假装生效。

### 7.3 后端选择

```kotlin
interface BackendSelectionPolicy {
    suspend fun select(
        probe: MediaProbe,
        preference: BackendPreference,
        available: List<BackendDescriptor>,
    ): BackendSelection
}
```

选择顺序：

1. 读取媒体探测结果和用户后端偏好。
2. 过滤安全策略、ABI 可用性、格式/字幕/渲染能力。
3. 默认选择 Media3。
4. 只有明确的媒体失败证据或用户主动选择时选择 libmpv。
5. 在首帧前失败可尝试一次候选后端；已经显示首帧后禁止无提示切换。
6. 记录脱敏的 `BackendSelectionReason` 和失败分类，不能记录 URI/path。

后端选择结果必须成为会话状态的一部分，以便 UI 显示“Media3/mpv”诊断信息（仅在诊断面板需要时），但普通播放页不暴露技术细节。

### 7.4 Surface lease

```kotlin
data class SurfaceBindRequest(
    val owner: SurfaceOwner,
    val generation: Long,
    val surface: VideoSurfaceToken,
)

data class SurfaceLease(
    val owner: SurfaceOwner,
    val generation: Long,
    val token: String,
)

interface VideoSurfacePort {
    fun bind(request: SurfaceBindRequest): Result<SurfaceLease>
    fun unbind(lease: SurfaceLease): Result<Unit>
}
```

Activity/Compose 重建时先生成新 generation，再绑定新输出；旧页面只能解绑自己拿到的 lease。Service 销毁时统一释放；页面 `DisposableEffect` 只能调用 bind/unbind port，不能直接操作 `PlayerView`。

---

## 8. PlaybackSessionRuntime 与 Service

### 8.1 Service 所有权

`YingLiPlaybackService` 的职责收敛为：

- 创建和销毁 `PlaybackSessionRuntime`。
- 创建唯一 `MediaSession`，注册 `MediaSessionPlayerAdapter`。
- 把系统 Controller 命令转入 Runtime。
- 暴露通知、耳机、音频焦点和后台生命周期。
- 在进程/任务生命周期边界请求最终进度写入。

Service 不在自己内部实现队列、AB、轨道偏好、源解析或页面 Overlay。它通过依赖注入获得 Runtime。

### 8.2 Runtime 内部组件

```text
PlaybackSessionRuntime
  ├── SessionCommandProcessor       串行命令队列
  ├── PlaybackStateReducer           状态归约
  ├── QueueNavigator                 顺序/上一项/下一项
  ├── AbLoopLimiter                  A/B 边界和回跳
  ├── PlaybackEngineRouter           当前后端和首帧前回退
  ├── PlaybackSourceResolver         单次解析
  ├── ProgressCommitter              单写入顺序
  ├── HistoryEligibilityPolicy       min(30s, 10%) 等规则
  ├── BackgroundPlaybackPolicy       页面/后台/音频策略
  └── SessionSnapshotStore           向 UI/MediaSession 投影
```

### 8.3 MediaSession

只允许 Service 创建一个 `MediaSession`：

- Controller 重连复用同一 session/player。
- 通知、蓝牙耳机、锁屏和外部 MediaController 都进入 Runtime。
- MediaSession 的 `play/pause/seek/skipNext/skipPrevious` 不能绕过 Runtime 直接调用 Engine。
- libmpv 接入时使用 `MediaSessionPlayerAdapter` 将 Runtime/Engine 状态适配给 Media3 `Player` 接口，或使用 MediaSession 的自定义回调；不得再创建 Activity MediaSession。
- 适配器必须覆盖播放状态、位置、持续时间、可用命令、轨道和错误映射，并有契约测试。

### 8.4 后台和音频焦点

```kotlin
data class BackgroundPlaybackPolicy(
    val continueAudioWhenPageLeaves: Boolean,
    val pauseWhenAudioFocusLost: Boolean,
    val autoPictureInPicture: Boolean,
    val keepSessionWhenMiniPlayerDisabled: Boolean,
)
```

规则：

- 页面离开不等于停止会话；由策略决定继续播放、仅关闭视频输出或暂停。
- 音频焦点丢失、耳机断开、短暂失焦和 Duck 均由 Runtime 处理。
- 关闭迷你播放器只隐藏应用内 UI，不销毁 MediaSession；用户主动停止才停止会话。
- Vault/安全内容不能自动 PiP，必要时停止视频输出但不能泄露画面。

### 8.5 进度和历史

`ProgressCommitter` 使用单写入队列：

```text
Engine position event
    -> sample(sequence, mediaId, position, reason)
    -> coalesce by mediaId
    -> discard older sequence
    -> Room transaction: progress + completion
```

规则：

- 播放中最多每 5 秒写一次；暂停、结束、停止、后台和 Service 销毁强制 flush。
- 旧媒体的异步写入不能覆盖新媒体；事务包含媒体 ID 和 sequence。
- 无痕/Vault 按安全策略不写进度和历史。
- 历史资格默认 `position >= min(30_000, duration * 0.10)`，并支持短视频独立策略。
- 完成条件为自然结束或达到 95%，具体值由 `CompletionPolicy` 测试固定。

---

## 9. Media3 当前实现迁移

### 9.1 目标组件

```text
engine/media3/Media3PlaybackEngine
  -> ExoPlayer / Media3 Player.Listener
engine/media3/Media3TrackAdapter
engine/media3/Media3SurfacePort
engine/media3/Media3FrameCaptureGateway
app/playback/Media3SessionPlayerAdapter
app/playback/YingLiPlaybackService
app/playback/PlaybackSessionRuntime
feature/player/PlaybackSessionClient
```

### 9.2 从现有 Controller 拆出的职责

从 `Media3PlaybackController` 移除：

- `PlaybackSourceRepository` 依赖和源解析。
- 队列、播放顺序、AB、历史和进度写入。
- ViewModel 标题和页面 Overlay 状态。
- 以 `WeakReference<PlayerView>` 管理 Surface。
- 对外暴露 MediaController 和 ExoPlayer 类型。

保留并重写为 Engine 内部实现：

- Media3 事件到 `EngineState` 的映射。
- Media3 轨道到稳定 `TrackDescriptor` 的转换。
- playback speed、seek、track selection、resize mode 和错误分类。
- `Player.Listener` 生命周期和 release。

### 9.3 源解析一次性约束

当前 `PlayerViewModel.open()` 与 `Media3PlaybackController.prepare()` 重复解析。迁移后的流程必须是：

```text
UI open(mediaId, context)
  -> PlaybackSessionRuntime.submit(Open)
      -> PlaybackSourceResolver.resolve() exactly once
          -> BackendSelectionPolicy
              -> selectedEngine.prepare(sourceHandle)
```

同一个 `Open` 命令的解析不得由 ViewModel、Controller 和 Engine 重复执行。若需要刷新授权，必须产生新的 command generation，而不是在旧协程中重试并覆盖新媒体。

### 9.4 Buffering 和状态映射

Media3 的 `STATE_BUFFERING`：

- 若尚未首帧，映射为 `Preparing`。
- 若此前已经 `Playing`，映射为 `Buffering(reason = Rebuffer)`。
- 若网络不在范围内但本地读取等待，仍使用 `Buffering`，错误分类不应伪造为 source unavailable。
- UI 在 Buffering 时保留暂停/停止和当前位置，进度条是否可拖动由能力决定。

### 9.5 轨道和偏好

轨道选择保存 `TrackFingerprint`：

```kotlin
data class TrackFingerprint(
    val type: TrackType,
    val language: String?,
    val roleFlags: Int,
    val codec: String?,
    val channelCount: Int?,
    val label: String?,
)
```

恢复优先级：精确 fingerprint > codec/language/role 组合 > 语言 > 后端默认。禁止保存 Media3 group/index 作为跨播放稳定 ID。多个相同语言轨道必须显示区分标签。

---

## 10. 未来 libmpv 架构与接入门槛

### 10.1 接入形态

```text
MpvPlaybackEngine
  -> MpvNativeSession
      -> JNI / mpv client API
  -> MpvTrackAdapter
  -> MpvSurfacePort
  -> MpvFrameCaptureGateway
  -> MediaSessionPlayerAdapter
```

libmpv 只实现 `PlaybackEngine` 及可选 capability ports。UI、Runtime、Room、DataStore 不知道 mpv property、command string、JNI pointer 或 native thread。

### 10.2 native 生命周期

- native 初始化、事件循环和释放必须由 Service/Engine scope 管理。
- 允许参考 REX 的生命周期锁，但锁只保护 native 初始化/销毁临界区，不存储当前媒体和 UI 状态。
- Activity 重新创建只重新绑定 Surface，不重建 mpv context。
- 所有 JNI 回调转换为结构化 `EngineEvent`，在 Runtime dispatcher 串行归约。
- native 错误必须映射为稳定分类，日志记录后端、错误码和媒体探测摘要，不记录路径。
- 多 ABI、NDK、CMake、符号表、native 崩溃和版本升级必须有独立构建门禁。

### 10.3 MediaSession 技术 Spike

在产品化前必须完成最小 Spike：

1. Service 创建 mpv context，播放一个本地样本。
2. Play/Pause/Seek/Stop 和进度回调能映射到 `PlaybackSessionRuntime`。
3. Surface 绑定、Activity 重建和旧 lease 拒绝可验证。
4. MediaSession 外部控制和通知按钮只存在一份。
5. 音轨、字幕、ASS、硬解和截图能力分别记录结果。
6. 前后台、音频焦点、耳机断开、PiP 和 `FLAG_SECURE` 结果可复现。
7. Release/R8、所有目标 ABI、冷启动、native 内存和崩溃诊断通过。

Spike 未解决任何一个关键项时，不能把 libmpv 接入默认路径。Media3 仍是默认后端。

### 10.4 后端切换规则

- 选择发生在 `Open` 后、首帧前。
- Media3 首帧前失败且 `BackendSelectionPolicy` 认为 libmpv 能力匹配时，最多回退一次。
- 回退保留逻辑媒体 ID、起始位置、轨道意图和 AB 状态；不把 Media3 index 传给 mpv。
- 已输出首帧后不静默切换；用户看到明确错误和“使用另一后端重试”操作。
- 后端偏好写入 DataStore 仅作为选择提示，不覆盖媒体能力和安全策略。

---

## 11. FFmpeg 处理域

### 11.1 与播放域隔离

```text
Feature Processing
    -> ProcessingCoordinator
        -> ProcessingRepository (Room)
        -> ProcessingEngine
             +--> Media3TransformerEngine
             +--> FFmpegProcessingEngine
```

`PlaybackSessionRuntime` 不依赖 `ProcessingCoordinator`。播放页如果发起“截图/转码/字幕烧录”，只提交处理任务并订阅任务状态，不直接运行 FFmpeg。

### 11.2 任务契约

```kotlin
data class ProcessingJob(
    val id: ProcessingJobId,
    val type: ProcessingJobType,
    val input: ProcessingSourceHandle,
    val output: ProcessingOutputSpec,
    val cancellation: CancellationToken,
)

sealed interface ProcessingEvent {
    data class Progress(val fraction: Float, val processedMillis: Long?) : ProcessingEvent
    data class Completed(val artifact: ProcessingArtifact) : ProcessingEvent
    data class Failed(val error: ProcessingError) : ProcessingEvent
    data object Cancelled : ProcessingEvent
}

interface ProcessingEngine {
    val capabilities: ProcessingCapabilities
    suspend fun run(job: ProcessingJob): Flow<ProcessingEvent>
    suspend fun cancel(jobId: ProcessingJobId)
}
```

### 11.3 何时引入 FFmpeg

只有以下证据同时满足才进入产品化：

- Media3 Transformer/平台能力无法满足已登记样本。
- FFmpeg 解决了具体容器、编码、Remux、精确切片或字幕烧录失败。
- 任务可取消、可恢复/重试、可诊断，且不会阻塞播放 Service。
- 包体、启动、Java/native 内存、功耗和处理速度达到项目门槛。
- GPL/LGPL、FFmpeg 配置、libavcodec/libass 许可证方案已记录并通过审查。
- 与 libmpv 的 FFmpeg 依赖不会产生重复库、ABI 冲突或无法升级的版本锁定。

libmpv 自带 FFmpeg 与独立 FFmpeg processing 的组合必须在 native 发布 ADR 中明确选择“独立构建”或“统一构建”，不得凭感觉同时打包两套库。

---

## 12. 与 `16` 播放页规范的后端接口映射

`16` 是 UI 行为事实源。本节规定每个控件调用什么、状态从哪里来、失败如何反馈。

### 12.1 页面连接接口

```kotlin
interface PlaybackSessionClient {
    val snapshot: StateFlow<PlaybackSessionSnapshot>
    val events: Flow<PlaybackSessionEvent>
    fun dispatch(command: PlaybackSessionCommand): CommandHandle
}

interface WindowPlaybackGateway {
    val state: StateFlow<WindowPlaybackState>
    fun setFullscreen(enabled: Boolean): Result<Unit>
    fun requestOrientation(orientation: RequestedOrientation): Result<Unit>
    fun enterPictureInPicture(): Result<Unit>
}

interface ScreenshotPreviewController {
    val state: StateFlow<ScreenshotPreviewState>
    fun arm()
    fun capture()
    fun previousFrame()
    fun nextFrame()
    fun cancel()
    fun pauseExpiry()
    fun deletePreview()
}
```

页面 ViewModel 可以组合这三个接口的状态，但不能持有 Engine。

### 12.2 控件映射表

| `16` 功能 | 前端命令/状态 | 后端规则 |
| --- | --- | --- |
| 返回 | 页面事件 `onBack` | 先关闭 Dialog/Sheet/Drawer；无面板才退出 Route；锁定时先解锁 |
| 播放/暂停 | `Play/Pause/Retry`，由 `PlaybackPhase` 推导图标 | `Ready/Paused -> Play`，`Playing -> Pause`，`Ended -> Replay`，Buffering 不伪造暂停 |
| 上一项/下一项 | `Previous/Next` | 交给 `QueueNavigator`；UI 不自行处理顺序、随机或末尾 |
| 快退/快进 10 秒 | `SeekBy(±10_000)` | 经 `AbLoopLimiter`；在完整时长和 `[A,B]` 内 Clamp |
| 进度条 | `Seek(position, DRAG_END)` | 拖动只更新 preview；松手才提交；AB 时仅允许 `[A,B]` |
| 播放顺序胶囊 | `SetOrder` | `SEQUENCE/SHUFFLE/QUEUE_REPEAT/SINGLE_REPEAT` 四态，DataStore 持久化 |
| 倍速 | `SetSpeed` | 领域固定值校验；后端拒绝不更新 UI；按媒体保存偏好 |
| 画面比例 | `SetScale` | Media3 映射 resize mode；不支持 ORIGINAL 时返回降级结果 |
| 音轨 | `SelectTrack(Audio)` | 显示真实轨道；按 fingerprint 恢复；单轨不伪造可选列表 |
| 字幕 | `SelectTrack(Subtitle)` | 包含关闭项；ASS 能力由后端报告；切换失败反馈稳定错误 |
| 截图胶囊 | `FrameStepControl` + `SnapshotControl` | Ready/Paused/Playing 都可；不以“未播放”作为失败条件 |
| 截图预览 | `ScreenshotPreviewState` | 420ms 缩小到左上角、3 秒倒计时、点击暂停、删除按钮动画；预览和文件删除分开授权 |
| AB 循环 | `SetAbPoint/ClearAb` | A/B marker 是时间线投影；B 到达由 Runtime 回跳 A；拖动重新定义范围 |
| PiP | `WindowPlaybackGateway.enterPictureInPicture` | 系统确认后更新状态；Vault/无能力时禁用；MediaSession 不停止 |
| 旋转 | `requestOrientation` | Activity 请求系统方向，真实配置变化回流；失败不改变已确认状态 |
| 全屏 | `setFullscreen` | WindowInsets/system bars 的真实状态；不使用浏览器 Fullscreen API |
| 锁定 | 页面 `PlayerOverlayReducer` | 只影响页面控件可见性；不暂停会话、不改变 Engine |
| 更多/设置 | 面板事件 + 会话命令 | 面板互斥由页面状态机，实际偏好通过 Runtime/DataStore |
| 播放列表 | `snapshot.queue` + QueueRepository | Room 快照；排序、删除、选择项由 Runtime 原子更新 |
| 视频信息 | `MediaInfoRepository` 查询 | UI 展示媒体事实；不从 Engine 反推文件路径 |
| 黑名单 | `BlacklistRepository.remove(mediaId)` | 列表删除更新 Room；正在播放项需由会话策略决定停止或继续 |

### 12.3 横屏、竖屏和 YLShorts 边界

```text
RegularPlayerRoute
  ├── LandscapePlayerLayout
  └── PortraitPlayerLayout

ShortsRoute (一级页面)
  └── ShortsSessionContext
       └── PlaybackSessionClient (复用底层)
```

横屏/竖屏：

- 共享同一个 Runtime、媒体会话、队列、AB 和后端。
- 横屏核心交通控件固定可见；竖屏使用悬浮栏和最多 6 个快捷槽。
- 方向、全屏和 Insets 属于窗口 gateway，不创建第二个播放会话。

YLShorts：

- 是独立一级路由，不是 `orientation == portrait` 的分支。
- 自己持有短视频队列、上下滑手势、邻项预加载、自动切换、长按倍速和更多 Bottom Sheet。
- 可复用 `PlaybackSessionRuntime` 和 Engine，但通过 `ShortsSessionContext` 管理页面级候选和自动播放规则。
- 常规播放器的 AB、完整轨道面板、播放列表和复杂控制栏不能未经设计直接塞入 Shorts。

---

## 13. 截图和 AB 的实现契约

### 13.1 截图状态机

```text
Closed
  -> Armed
  -> Capturing
  -> Preview(artifact, expiresAt, paused=false)
  -> Preview(paused=true, deleteVisible=true)
  -> Closed / Deleted
Capturing -> Failed(kind) -> Armed or Closed
```

实现要求：

- `Armed` 胶囊包含上一帧、截图当前帧、下一帧、取消，触控区至少 48dp。
- 上一帧/下一帧要求 `FrameStepControl` 能力；无帧率时禁用并反馈。
- `captureFrame` 可以在 Ready、Paused、Playing 执行，不依赖“必须正在播放”。
- Engine 返回真实图像或稳定失败分类；不得用延时猜测截图完成。
- UI 预览 3 秒后自动关闭，倒计时条每帧由 expiry 计算；暂停后冻结。
- 点击预览时只暂停预览倒计时，`deletePreview` 的语义必须明确是删除预览还是删除媒体文件。
- 若要删除 MediaStore 文件，Artifact 必须带可撤销的授权 token，并增加确认、失败和撤销测试；默认只删除预览。

### 13.2 AB 状态机

```text
Off -> SetA -> SetB(active) -> DragA/DragB
任意状态 -> Clear -> Off
```

```kotlin
data class AbLoopState(
    val pointA: Long? = null,
    val pointB: Long? = null,
    val active: Boolean = false,
)
```

规则：

- A/B 标记、时间和拖动命中区由页面投影，真正的边界由 Runtime 保存。
- A 不得晚于 B；最小间隔为一帧；无帧率按 30fps。
- `Seek`、`SeekBy`、进度拖动、逐帧都经过 `AbLoopLimiter`。
- 播放到 B 时 Engine/Runtime 立即 seek A，不依赖 Compose 轮询。
- 切换媒体清除 AB；AB 不写全局 DataStore。
- 页面失去焦点、打开设置或锁定不自动清除 AB，除非用户点击清除或切换媒体。

---

## 14. 持久化模型建议

### 14.1 Room 表

建议新增或重构以下表，字段只保存领域事实，不保存后端 index、URI 明文或 native 指针：

| 表 | 关键字段 | 作用 |
| --- | --- | --- |
| `playback_queue` | `queue_id`, `media_id`, `position`, `added_at` | 队列成员和顺序 |
| `playback_session` | `session_id`, `current_media_id`, `current_index`, `order`, `updated_at` | 可恢复会话摘要 |
| `shuffle_history` | `session_id`, `sequence`, `queue_index` | 随机上一项 |
| `playback_progress` | `media_id`, `position_ms`, `duration_ms`, `completed`, `sequence`, `updated_at` | 续播和完成 |
| `playback_history` | `media_id`, `started_at`, `eligible_at`, `last_position_ms`, `incognito` | 历史投影 |
| `track_preference` | `media_id`, `track_type`, `fingerprint`, `language`, `enabled` | 稳定轨道偏好 |
| `backend_preference` | `scope`, `backend_id`, `reason` | 默认/媒体后端偏好 |
| `screenshot_artifact` | `id`, `media_id`, `position_ms`, `display_name`, `storage_key`, `created_at` | 若产品要求保留截图 |

### 14.2 DataStore

DataStore 只保存小型偏好：默认倍速、默认画面模式、播放顺序默认值、后台音频、自动 PiP、长按倍速、常规播放器快捷槽布局和 Shorts 快捷槽布局。坏值必须回退领域默认值。

### 14.3 不持久化内容

- `Surface`、`Player`、Media3 index、mpv `sid/aid`、JNI pointer。
- 临时 Overlay 可见性、截图 3 秒倒计时、当前触摸拖动。
- Vault 解密内容、访问 token 明文、日志中的 URI/path。

---

## 15. 错误、诊断、安全和可观测性

### 15.1 统一错误分类

```kotlin
enum class PlaybackErrorKind {
    ACCESS_DENIED,
    SOURCE_NOT_FOUND,
    UNSUPPORTED_CONTAINER,
    UNSUPPORTED_DECODER,
    CORRUPT_MEDIA,
    NO_SURFACE,
    BACKEND_UNAVAILABLE,
    TRACK_UNAVAILABLE,
    SCREENSHOT_EMPTY_FRAME,
    STORAGE_FULL,
    AB_INVALID_RANGE,
    UNKNOWN,
}
```

每个错误包含用户动作、是否可重试、诊断码和脱敏技术详情。UI 只显示稳定文案和必要操作；日志记录 `backendId`、错误码、媒体摘要和 command/session ID，不记录 URI、真实路径、查询参数和异常完整消息。

### 15.2 安全边界

- Vault 播放使用 `FLAG_SECURE`，截图、PiP、外部显示和普通预览按策略禁用。
- 安全源的 token 只在 data/engine 使用，不能进入 `PlaybackSessionSnapshot` 的可序列化日志。
- Service notification 不显示敏感文件名时使用脱敏标题。
- 任何“删除截图/删除媒体”操作都要区分 preview artifact 和源媒体文件，并有独立授权。
- libmpv/FFmpeg native 输入必须经过允许的本地源句柄，禁止从 UI 传入任意 shell 字符串。

### 15.3 诊断指标

至少记录以下不含敏感数据的指标：

- `playback_open_to_first_frame_ms`，按后端、容器、设备分组。
- `playback_backend_selection` 和失败分类。
- `seek_command_latency_ms`、缓冲次数和累计缓冲时间。
- `surface_bind_rejected_stale_lease_count`。
- 截图成功率、失败分类、编码耗时和存储失败数。
- 处理任务吞吐、取消延迟和 native 内存峰值。

---

## 16. 性能和线程模型

### 16.1 Dispatcher 规则

| 工作 | Dispatcher/线程 |
| --- | --- |
| Runtime 命令归约、状态更新 | Service 单线程 actor 或 `Dispatchers.Main.immediate` 的受控 scope |
| Media3 Player 调用 | Media3 要求的应用线程；通过 Engine 封装 |
| Media3/mpv native 事件转换 | Engine 专用线程 -> Runtime 串行事件流 |
| Room、SAF、MediaStore、截图写盘 | `Dispatchers.IO` |
| 媒体探测、排序、指纹和编码计算 | `Dispatchers.Default` 或 native worker |
| Compose 状态投影 | UI 线程；拆分高频 timeline 与低频结构状态 |

禁止在 ViewModel 中启动无限位置投影来替代 Engine 时钟。播放位置以 Engine 事件为真相；UI 可做受控插值，但 Seek/暂停/缓冲/结束必须立即由事件校正。

### 16.2 高频状态拆分

`PlaybackSessionSnapshot` 的低频部分（媒体、队列、能力、错误）和高频部分（timeline、buffered position）应拆为两个 StateFlow 或稳定的 `StateIn` 投影，避免每 250ms 重组整个设置面板和视频信息 Dialog。

### 16.3 Surface 与内存

- 页面离开时解绑 Surface，不释放会话后端。
- 同时只能有一个视频输出 lease；PiP 由系统输出策略确认后切换。
- 截图和缩略图编码使用有界缓存和 IO scope，不能把整段视频读入内存。
- libmpv/FFmpeg native 内存纳入真机基准和低内存测试；不能只看 Java heap。

---

## 17. 破坏性重构策略

当前属于开发期，不保留错误抽象的兼容层。迁移完成后应删除以下实现：

- `Media3PlaybackController` 作为应用级总控的旧形状。
- `PlayerViewModel` 中的媒体源解析、长期队列逻辑、Service 级进度/历史策略和直接截图/PiP gateway 编排。
- `AdvancedPlaybackController` 这个胖接口；按 `PlaybackSessionClient` 和 capability ports 重构。
- `InMemoryPlaybackQueueRepository` 生产装配。
- `WeakReference<PlayerView>` 作为视频输出唯一绑定方式。
- ViewModel 自己根据 250ms 增量猜测真实位置的逻辑。
- Media3 index/语言字符串作为稳定轨道偏好的逻辑。
- 所有仅用于 Demo 的成功 Toast、固定延时截图和假能力按钮。

允许破坏的接口：

- `PlaybackRequest`、`PlaybackState`、`PlaybackController`、`AdvancedPlaybackController`。
- `PlaybackQueue`、截图结果和 PiP gateway 的参数/返回值。
- Service 与 Controller 的连接方式。
- Room/DataStore 的播放 schema；开发期可以直接重建数据库或提供仅用于开发数据的迁移脚本。

禁止留下：

- 同一功能的新旧两套实现并行。
- 为旧调用方增加的临时 Adapter、Legacy API 或兼容分支。
- “未来 libmpv”名义下提前引入没有测试价值的 native 依赖。

---

## 18. 分阶段实施计划

每个阶段都必须遵循：

```text
建立基线 -> 写失败测试/契约 -> 实施重构 -> 新行为测试 -> 相关旧行为回归 -> 记录证据
```

### Phase 0：基线和样本冻结

**目标**：冻结现状，避免重构过程中把环境变化误认为代码变化。

工作：

- 固定 Gradle、AGP、Media3、Kotlin 和设备信息。
- 重新运行 JVM、编译、架构和现有 Media3 仪器测试。
- 建立真媒体矩阵：MP4/MKV/WebM、H.264/H.265/AV1、AAC/Opus/AC3、外挂/内嵌字幕、ASS、竖屏、HDR、多音轨、损坏文件、Vault 样本。
- 记录首帧、暂停、Seek、截图、后台、旋转、PiP、进度和历史基线。

退出条件：测试结果和样本 hash 已记录；失败分类可重现。

### Phase 1：领域契约和架构测试

**目标**：先建立后端无关的模型，不改变用户路径。

工作：

- 新增 `PlaybackSessionSnapshot`、`PlaybackSessionCommand/Event`、`PlaybackEngine`、能力端口和 `SurfaceLease`。
- 实现 `QueueNavigator`、`AbLoopLimiter`、`HistoryEligibilityPolicy`、`BackgroundPlaybackPolicy`。
- 扩展架构测试识别 `engine`、`data`、`feature`、`app` 依赖方向和 Media3 泄漏。
- 写 Fake Engine 契约测试：所有 Engine 实现必须通过同一组行为测试。

退出条件：domain JVM 无 Android/Media3 依赖；非法状态、AB 边界、顺序和错误分类测试通过。

### Phase 2：Runtime 和 Service 单一所有权

**目标**：把长期会话从 ViewModel/Controller 移到 Service。

工作：

- `YingLiPlaybackService` 创建 `PlaybackSessionRuntime` 和唯一 MediaSession。
- 将命令串行化，加入 command/session generation。
- 将队列、AB、后台、进度和历史放入 Runtime。
- 实现 `MediaSessionPlayerAdapter`，MediaSession 命令不得绕过 Runtime。

退出条件：Service 重建/Controller 重连不丢会话；并发 Open、过时解析、进度乱序测试通过。

### Phase 3：Media3 Engine 迁移

**目标**：Media3 继续默认播放，但只作为 Engine 实现。

工作：

- 从 `Media3PlaybackController` 拆出 `Media3PlaybackEngine`。
- 解析源移至 Runtime，Engine 只接受 `PlaybackSourceHandle`。
- 修正 Buffering、轨道 fingerprint、速度/画面比例和错误映射。
- 将 `Media3VideoSurface` 改为 Surface port/lease。

退出条件：现有播放行为回归；Media3 仪器通过单一 Service session、输出绑定、命令和错误用例。

### Phase 4：数据持久化和恢复

**目标**：删除内存队列，保证重启和后台行为可恢复。

工作：

- Room 队列、顺序、游标、随机历史、进度、历史和轨道偏好。
- DataStore 播放默认偏好和快捷槽布局。
- 单写入进度 Actor，增加崩溃/取消/旧序列测试。

退出条件：进程重启后队列、续播和偏好符合策略；无痕/Vault 无泄露。

### Phase 5：常规播放页接口对齐

**目标**：使 `16` 的所有按钮都是真实会话命令或真实窗口 gateway。

工作：

- 重写 `PlayerViewModel` 为 `PlaybackSessionClient` 投影器。
- 横屏/竖屏共享 Route，面板互斥、自动隐藏和锁定留在页面状态机。
- 接入顺序、速度、比例、轨道、截图、AB、播放列表、视频信息、PiP、旋转和全屏。
- 删除固定 Toast/假按钮/固定延时截图。

退出条件：`16` 的每项交互都有命令、状态、失败反馈和测试；Compose 关键流程通过。

### Phase 6：截图、AB 和系统能力真机验证

**目标**：验证最容易被模拟实现掩盖的画面能力。

工作：

- PixelCopy/Surface/Texture 的真实截图路径和文件写入。
- 逐帧、A/B 标记拖动、B 到 A 回跳、锁定和 PiP。
- Vault `FLAG_SECURE`、无帧、存储不足、页面重建和快速操作。

退出条件：真机矩阵记录成功/失败原因；没有“点击无反应”或“必须播放才能截图”的错误行为。

### Phase 7：YLShorts 独立页面

**目标**：在不污染常规播放页的情况下复用底层会话。

工作：

- 新增 `ShortsRoute`、`ShortsViewModel`、`ShortsSessionContext`。
- 垂直滑动、邻项预加载、自动播放、长按 2x、点赞/收藏/黑名单和更多 Bottom Sheet。
- 处理 YLShorts 自己的队列和生命周期；常规 AB/播放列表按产品规则隔离。

退出条件：YLShorts 是一级页面；离开/进入常规播放页不产生第二个活动后端。

### Phase 8：libmpv Spike

**目标**：用真实证据决定是否引入可选后端。

工作：

- 仅在独立分支/可选构建变体接入 native，不改变 Media3 默认路径。
- 完成播放、暂停、Seek、Surface、轨道、ASS、截图、MediaSession 和后台测试。
- 测量 ABI 包体、首帧、功耗、native 内存、崩溃和 R8/Release。

退出条件：所有 Spike 门槛通过，并登记“引入/不引入”ADR。未通过则保留契约和 Fake，不接入生产。

### Phase 9：FFmpeg Processing Spike

**目标**：验证独立处理能力和 native 依赖策略。

工作：

- 探测、Remux、精确切片、转码、字幕烧录、取消和进度。
- 与 Media3 Transformer 对相同样本进行输出正确性、速度、功耗、包体和许可证比较。
- 决定与 libmpv 的 FFmpeg 依赖统一构建或独立构建。

退出条件：仅把能解决明确失败样本的任务接入 ProcessingEngine；不能进入 PlaybackService。

### Phase 10：发布前质量门禁

**目标**：验证整个播放域没有因重构破坏现有能力。

工作：

- Debug、Release、R8、全部 ABI、冷启动、后台、锁屏、通知和系统按键。
- 真机矩阵、多设备方向/屏幕密度、低存储、权限撤销、文件移动和损坏媒体。
- 重新采集性能指标并与 Phase 0 对比。

退出条件：见第 20 节完成清单；任何关键行为没有证据时不能宣称完成。

---

## 19. 测试策略和命令门禁

### 19.1 Domain JVM 测试

必须覆盖：

- `PlaybackStateReducer`：Idle、Resolving、Preparing、Ready、Playing、Paused、Buffering、Ended、Failed 全路径。
- `QueueNavigator`：四种顺序、随机不重复、上一项历史、队列为空/末尾。
- `AbLoopLimiter`：A/B 缺失、A>=B、最小帧间隔、Seek/SeekBy/拖动/结束回跳。
- `PlaybackSpeed`、画面比例、轨道偏好 fingerprint 和坏值回退。
- `HistoryEligibilityPolicy`：10%/30 秒、短视频、已完成、无痕。
- `ProgressCommitter`：旧 sequence 丢弃、暂停/结束强制 flush、写入失败重试。
- `BackendSelectionPolicy`：能力过滤、首帧前一次回退、已首帧禁止静默切换。
- `PlayerOverlayReducer`：自动隐藏、拖动、锁定、面板互斥事件。

### 19.2 Engine 契约测试

Media3、未来 mpv 和 Fake Engine 必须共享：

- prepare/play/pause/stop/retry。
- Seek clamp、不可 seek、Buffering 和 ended。
- 轨道列表稳定 ID、选择和能力缺失。
- Surface lease 的绑定、旧 generation 拒绝、重复解绑。
- Frame capture、frame step 和失败分类。
- release 后拒绝所有命令且不泄漏线程。

### 19.3 Service/MediaSession 仪器测试

- 应用进程只创建一个 Service Player 和一个 MediaSession。
- Controller 断连/重连不创建第二后端。
- 通知、锁屏、耳机、蓝牙和系统 `MediaController` 命令走 Runtime。
- Service 被移除、暂停、结束、销毁时进度写入顺序正确。
- 后台音频、视频输出关闭、自动 PiP 与偏好一致。

### 19.4 Compose/Android UI 测试

按 `16` 验证：

- 横屏和竖屏控件布局、核心控件不可删除、快捷槽 +/− 编辑。
- 点击、双击、长按、拖动和系统返回优先级。
- 播放状态图标来自状态而非本地布尔值。
- 截图胶囊、预览飞入左上角、倒计时暂停、删除动画和失败反馈。
- AB marker 拖动、时间更新、范围限制和进度条只在 A/B 移动。
- PiP/旋转/全屏真实状态回流；失败不假更新。
- 设置、播放列表、视频信息、黑名单和更多 Bottom Sheet 互斥。
- YLShorts 与常规 Player 是不同一级路由和不同页面状态。

### 19.5 真机媒体矩阵

每个样本记录：设备、Android 版本、后端、首帧、音画同步、字幕、轨道、Seek、速度、截图、PiP、后台、内存、功耗和失败分类。至少覆盖：

- MP4/MKV/WebM、H.264/H.265/AV1。
- AAC/Opus/AC3 等音频、多音轨和无音频。
- 内嵌/外挂 SRT/ASS/SSA、复杂样式字幕。
- 竖屏、旋转元数据、HDR、4K、高帧率和长视频。
- 只读、文件移动、权限撤销、损坏容器、未知编码和存储不足。
- Vault 安全源和 `FLAG_SECURE`。

### 19.6 必须实际执行的命令

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:minifyReleaseWithR8
git diff --check
```

如果项目任务名因 Gradle 配置变化，必须执行等价任务并记录实际命令。只修改 Markdown 时不需要运行构建，但每次代码阶段必须运行与风险匹配的门禁；不得只运行新增测试。

---

## 20. 行为对比和验收门槛

| 项目 | 修改前事实 | 修改后目标 | 验证证据 |
| --- | --- | --- | --- |
| 播放所有权 | Service 有 ExoPlayer，但 Controller/ViewModel 分担解析和控制 | Service Runtime 唯一拥有会话，Engine 只实现后端 | Service/Engine 契约和重连仪器 |
| 媒体解析 | ViewModel、Controller 重复解析 | Runtime 每个 Open 只解析一次 | Fake resolver 调用次数和竞态测试 |
| 播放中缓冲 | 映射为 Preparing | 映射为 Buffering | 状态 reducer/Media3 仪器 |
| 队列 | InMemory，进程重启丢失 | Room 队列、顺序、游标和随机历史 | Room/恢复测试 |
| 下一项 | `continuousPlayback` 简化语义 | 四态 `PlaybackOrder` + QueueNavigator | 领域测试和 UI 流程 |
| 进度写入 | 多协程可能旧写覆盖新写 | 单 Actor/sequence 串行提交 | 并发写入测试、数据库断言 |
| 历史 | 固定 10 秒 | `min(30s, 10%)`，无痕/Vault 隔离 | 策略测试和真机检查 |
| 轨道偏好 | 语言/后端 index 不稳定 | fingerprint 优先、能力降级 | 多轨真实样本 |
| Surface | 单 WeakReference PlayerView | generation/lease，旧页面不能解绑新页面 | 旋转/重建仪器 |
| 截图 | 可能以未播放为条件或固定延时 | Ready/Paused/Playing 真实抽帧，分类错误，预览交互完整 | 真机 PixelCopy/失败矩阵 |
| AB | UI marker 与播放限制可能脱节 | Runtime limiter 统一 seek 和 B->A | Fake Engine + 真机 |
| PiP/方向/全屏 | 命令状态可能与系统实际状态漂移 | gateway 回传确认状态 | Activity 仪器和手工记录 |
| MediaSession | 当前 Media3 Service 主导 | 一个 Runtime、一个 MediaSession；未来 mpv 通过 adapter | 系统 Controller 测试 |
| libmpv | 未接入 | 仅通过 Spike 和量化门槛后可选接入 | ABI/Release/真机报告 |
| FFmpeg | 不在播放路径 | 独立 ProcessingEngine，按失败样本接入 | 任务契约和输出对比 |
| 常规/Shorts | 可能共享过多页面状态 | RegularPlayerRoute 与 ShortsRoute 分离，共享底层会话 | 路由和状态测试 |

最终验收必须同时满足：

1. 根因已由边界和所有权重构解决，而不是增加外层补丁。
2. `16` 所有列出的新 UI 功能都有真实后端命令、状态、能力或系统 gateway。
3. 现有播放、续播、后台、轨道、Vault、错误和设置行为通过回归。
4. 相关单元、架构、Compose、Media3 仪器和真机媒体测试均执行并有结果。
5. 没有保留无效旧实现、临时兼容层、重复解析或 Demo 假功能。
6. 性能、包体、Java/native 内存和功耗没有出现未经解释的不可接受退化。
7. 许可证、R8、Release、ABI 和安全策略在引入 native 后端前已有结论。

---

## 21. 开发执行顺序和责任边界

实现 Agent 必须按以下顺序提交代码变更（不要求 Git commit）：

1. 先补契约和失败测试，再移动实现。
2. 先完成 Runtime/Engine 边界，再接 UI；不得先在 UI 中添加无法落地的按钮。
3. 每完成一个阶段，更新本文件对应的“证据”和行为对比，不把计划文字当作完成。
4. 若发现当前接口不足，优先破坏性修改领域契约和调用方，禁止在外层添加 `Legacy`/`Compat` 分支。
5. 若真实样本证明 Media3 足够，则不引入 libmpv；若 FFmpeg 只解决假设问题，则不引入 FFmpeg。
6. 所有后端切换、Surface、截图、进度和 MediaSession 问题必须有可复现测试，不接受仅靠人工“看起来能用”。

`16-player-ui-ux-interaction-implementation-spec.md` 负责“用户看到什么、怎样操作”；本文件负责“这些操作由哪个会话接口承接、由哪个后端实现、如何持久化和验证”。两者发生冲突时：安全和系统生命周期以本文件为准，控件布局和交互细节以 `16` 为准，必须通过新增契约消除冲突，不能在 UI 中私自选择一种行为。
