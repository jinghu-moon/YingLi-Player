# YingLi-Player 架构文档

> 文档性质：当前实现基线、长期架构原则与条件式演进规划
>
> 更新时间：2026-09-11
>
> 适用范围：本地视频索引、媒体库、缩略图、播放会话、Compose UI 与后续媒体管理能力

> 播放专项的会话、后端、迁移阶段和前端接口以 [`17-playback-architecture-refactor-spec.md`](17-playback-architecture-refactor-spec.md) 为准；本文件只保留全局依赖方向和跨领域边界。

## 1. 文档来源与使用方式

本文件综合以下资料整理：

- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015.md`
- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015_files/上传的附件1.md`
- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015_files/上传的附件2.md`
- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015_files/YingLi-Player-ARCHITECTURE-v2.md`
- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015_files/YingLi播放器软件架构总览.md`
- `D:/000_Inbox/020_Browser/021_Edge/chatgpt_personal_selected_2026-09-06/搜索开源安卓播放器_eb6c665d3015_files/YingLi播放器软件架构总览.png`
- 项目现有代码、`docs/09-tdd-phased-development-checklist.md`、`docs/10-glide-compose-thumbnail-prefetch-research.md` 和 `docs/11-compose-thumbnail-prefetch-plan.md`

资料中的总览图和 v2 文档是目标架构，不是当前代码的逐文件快照。因此本文件将内容分成三类：

| 标记 | 含义 |
| --- | --- |
| **当前** | 已在代码中实现，并应以代码和测试为事实源 |
| **原则** | 现在就必须遵守的边界或正确性要求 |
| **演进** | 只有满足触发条件并完成测试后才实施 |

当前项目处于开发期，不为历史 API 增加兼容层；但重构不能无意破坏现有权限、索引、浏览、播放、整理和设置行为。

## 2. 产品与技术定位

YingLi-Player 是本地优先的视频播放器和媒体管理中心：

```text
本地媒体来源
    -> 增量索引
    -> Room 媒体目录
    -> Paging 3 浏览
    -> 缩略图缓存/预取
    -> Media3 播放
    -> 播放历史、进度与整理关系
```

目标优先级：

1. 常见本地视频启动稳定、硬件解码优先。
2. 大媒体库索引和滚动不阻塞 UI，内存可控。
3. 播放进度、历史、标签、合集和回收站等本地数据正确持久化。
4. UI 现代、可维护，Compose 不被平台实现细节污染。
5. 对特殊格式提供可诊断的后备路径，而不是预先引入过重的万能内核。

Media3/ExoPlayer 是当前默认播放后端，但不是不可替换的长期架构边界。未来只有在真实媒体样本证明必要、且完成独立 Spike 与门禁后，才允许以 `PlaybackEngine` 实现的形式接入 libmpv。VLC、Next Player、Only Player 等仍只作为能力和交互参考，不直接并入项目。FFmpeg 属于独立的媒体处理后端，不作为完整播放器塞入播放链路。

## 3. 依赖方向与组合根

项目当前保持单 `app` Gradle 模块，使用包边界和架构测试约束依赖方向：

```text
app (Android 入口、系统生命周期与组合根)
        -> feature (Compose UI / ViewModel)
        -> domain (业务契约、状态、策略)
        -> core (通用模型、设计系统和基础设施契约)
app     -> data / engine (Room、DataStore、Android 与媒体实现)
data / engine -> domain / core
```

禁止反向依赖：

- `core` 不依赖 `feature`。
- `domain` 不依赖 Compose、Activity 或具体 Media3 对象。
- Repository 不依赖 Compose。
- feature 不直接访问 Room DAO、`ContentResolver`、`DocumentFile` 或 `ExoPlayer`。
- UI 不直接调用播放器实例，必须通过领域控制器和 ViewModel。

`YingLiApplication`、`ProductionAppContainerFactory` 与 `ProductionMediaContainerFactory` 是当前组合根，负责构造数据库、Repository、扫描器、缩略图协调器、播放控制器、安全和处理能力。组合根只负责装配；业务规则留在 domain/core，页面行为留在 feature。

### 3.1 暂不拆分 Gradle 模块

v2 文档建议拆成 `player-contract`、`player-platform`、`player-render-api`、`player-renderer`、`player-engine`、`player-data`、`media-thumbnail` 等模块。该方向只有在以下条件成立时才实施：

- 构建时间、源码规模或团队协作已经证明单模块成为瓶颈；
- 模块边界已有稳定的多个真实实现；
- 拆分能够提供可测量的构建隔离或依赖隔离收益。

在此之前，包级边界、架构测试和窄依赖接口足够，避免为了图示整洁增加空模块和重复适配层。

## 4. 当前目录职责

```text
app/src/main/java/seeyuer/yingli/player/
├── app/       Android 入口、组合根、Service 与系统生命周期边界
├── core/      通用模型、基础设施契约、日志、安全原语与设计系统
├── data/      Room、DataStore、媒体来源及 Android 数据实现
├── domain/    媒体、库、播放、整理、处理、安全和设置契约/策略
├── engine/    Media3 播放与缩略图等可替换媒体实现
└── feature/   Compose 页面与 ViewModel
```

主要职责：

| 区域 | 当前职责 |
| --- | --- |
| `app` | `MainActivity`、Application、组合根、系统 Service 与 Activity 级 Gateway |
| `data.room` | Room Entity、DAO、数据库迁移、数据库级查询及 Repository 实现 |
| `data.sources` | MediaStore/SAF 发现、权限和 Android 元数据读取 |
| `core.model.media` | 媒体 ID、位置、候选、扫描、缩略图请求和缓存 key |
| `domain.media` | 扫描协调、身份解析、权限状态机 |
| `domain.library` | 查询、排序、过滤、Keyset cursor、分页契约 |
| `feature.library` | PagingSource、Pager、LazyPagingItems、列表/网格和 loadState |
| `domain.playback` | 播放请求、状态、错误、轨道、倍速、播放队列和持久化契约 |
| `engine.media3` | 当前 Media3 控制器、状态映射、视频 Surface 和截图实现 |
| `app.playback` | `YingLiPlaybackService` 与 Activity 级画中画 Gateway；后续承载会话 Runtime |
| `feature.player` | 播放页面、控制层、手势入口、错误和设置面板 |
| `feature.home` | 有限投影首页、统计、继续观看、最近添加、合集和维护提醒 |

## 5. 媒体来源与索引链路

### 5.1 来源

当前支持两类来源：

1. `MediaStore`：全部文件访问或媒体读取权限对应的系统媒体目录。
2. `SAF Tree`：用户逐次授权的多个目录，应用将多次授权合并为多个持久 URI 权限。

USB/OTG 不需要单独复制一套 Provider；只要系统以 MediaStore 或 SAF 暴露，沿用对应来源路径即可。

全部文件权限不是递归 `File.listFiles()` 的理由。当前设计优先通过 `MediaStore.Video.Media` 查询，避免扫描阶段对整个文件系统做深度递归。

### 5.2 扫描器

```text
MediaStore / SAF DataSource
        -> MediaDiscoveryEvent
        -> DefaultMediaScanner
        -> MediaIdentityResolver
        -> CatalogMutation
        -> RoomMediaCatalogRepository
```

`DefaultMediaScanner` 当前具有以下行为：

- 按来源扫描，不同来源使用不同 DataSource；
- 依据 URI、稳定卷/文档身份、大小和内容 hash 解析逻辑媒体身份；
- 以约 400 条为批次写入 Room；
- 以较小频率发布扫描进度；
- 单条异常记录为失败，不终止整个扫描；
- 只有扫描完整结束时才批量标记缺失位置，避免权限中断造成误删。

索引和缩略图严格解耦：扫描先保证文件名、URI、大小、修改时间、MIME、可用的时长/尺寸进入目录，缩略图不得阻塞索引。

### 5.3 条件式演进

只有当真实设备证明当前扫描仍受以下问题影响时，才继续拆分：

- SAF DataSource 仍同步读取深度元数据；
- 单来源快照在超大目录中造成不可接受的内存峰值；
- 需要在应用退出后继续扫描；
- 需要监听外部文件变化而不是用户主动重新扫描。

届时可以增加 P0 基础发现、P1 可见元数据、P2 深度元数据的流水线，以及 `ContentObserver`/受控后台任务；不提前引入 WorkManager 或 FileObserver。

## 6. Room 数据与查询

Room 是媒体目录、播放历史、整理关系、处理任务和保险库元数据的本地真相源。DataStore 只用于轻量偏好和设置，例如首页布局、主题偏好、播放选项和授权状态。

当前数据模型已经覆盖：

- 媒体来源、媒体逻辑实体、物理位置和位置关系；
- 标签、收藏、播放列表、合集、最近整理；
- 播放历史、播放进度、回收站；
- 重复文件、处理任务、切片项目和保险库条目。

### 6.1 库查询

媒体库不应回到“全量查询后在内存中过滤、排序、分页”。当前使用数据库级查询和 Keyset cursor：

```text
LibraryQuery
    -> Room SQL WHERE / ORDER BY
    -> pageSize + 1 哨兵行
    -> LibraryPage
    -> LibraryPagingSource
    -> PagingData
    -> LazyPagingItems
```

排序值相同时使用稳定媒体 ID 作为第二排序键；Append 和 Prepend 使用严格边界，避免重复和遗漏。`getRefreshKey()` 从锚点页边界或真实锚点媒体反推出 cursor，不使用可见 index 拼接。

### 6.2 Paging 3

`LibraryViewModel` 当前使用：

- `flatMapLatest` 取消旧查询；
- `cachedIn(viewModelScope)` 复用 ViewModel 生命周期内的 PagingData；
- `pageSize=60`、`prefetchDistance=24`、`maxSize=150`；
- `LoadState` 区分首次加载、追加加载和追加失败；
- `paging-testing`、PagingSource 单元测试和 Room 测试覆盖边界。

`maxSize` 只负责丢弃远离锚点的页面，不是缩略图缓存，也不等于 Compose item 缓存。其值必须通过真机往返滚动、内存和重载次数测量调整。

## 7. 缩略图架构

缩略图不是媒体索引的一部分，而是独立的可取消资源管线：

```text
ThumbnailRequest
        -> ThumbnailLoader
        -> Memory LRU
        -> Disk thumbnail files
        -> ContentResolver.loadThumbnail()
        -> Media3 FrameExtractor
        -> Placeholder / Failed
        -> Compose display
```

### 7.1 稳定请求与缓存 key

`ThumbnailKey` 必须包含：

- 媒体 ID、位置 ID；
- 文件修改时间和大小；
- 目标宽高；
- 变体标识。

这样文件被替换或目标尺寸改变时不会错误复用旧图。显示请求和预取请求必须使用完全相同的 key、尺寸和处理参数。

### 7.2 Provider 顺序

当前顺序是：

1. 统一磁盘缓存命中；
2. `ContentResolver.loadThumbnail()` 请求系统 Provider 缩略图；
3. 失败后使用 Media3 `FrameExtractor`；
4. 使用 `Presentation` 在提取阶段降采样；
5. 以统一 PNG 文件写入受限磁盘缓存。

`FrameExtractor` 标记为 `@UnstableApi`，并隔离在 `engine.thumbnail.frame` 基础设施实现内。Extractor 实例只由单一 application thread 访问，不泄漏到 domain 或 UI。

当前项目仍使用 Coil 读取已生成的本地缩略图并承担 Compose 图片显示；这不等于把 Coil 作为领域契约。Glide 只能在 benchmark 阶段作为候选实现，评估完成后删除落选调用链，不长期保留双轨。Media3 `FrameExtractor` 实现位于 `engine.thumbnail.frame`，不属于 `core.media`。

### 7.3 调度与内存

缩略图任务区分 `VISIBLE`、`PREFETCH` 和 `BACKGROUND` 优先级，限制并发、可取消，并按滚动方向替换未开始的预取任务。Compose `CacheWindow`、Paging `maxSize` 和 ThumbnailLoader 缓存分别调参、分别测量。

核心指标是 `Cache Hit Before Bind Rate`，即列表项绑定前缩略图已经可显示的比例；它比单张抽帧平均耗时更接近用户快速 fling 的实际感受。

## 8. 播放架构

### 8.1 生命周期

```text
PlayerScreen / PlayerViewModel
        -> PlaybackSessionClient
        -> PlaybackSessionRuntime (由 YingLiPlaybackService 持有)
        -> PlaybackEngine
             +--> Media3PlaybackEngine (当前默认实现)
             +--> MpvPlaybackEngine (未来可选实现)
        -> MediaSession / PlayerView / Surface
```

播放服务拥有播放会话、活动后端和 MediaSession；当前活动后端是 Media3。Activity/Compose 只连接 `PlaybackSessionClient` 和 Surface lease，不直接依赖 ExoPlayer、MediaController 或未来的 libmpv。Activity 销毁只回收页面 Surface，播放服务按会话与 MediaSession 生命周期释放后端。

### 8.2 播放请求

`PlaybackRequest` 统一承载媒体 ID、位置 ID、起始位置、来源上下文和无痕标志。播放源由 `PlaybackSourceRepository` 解析，解析失败转换为领域错误，不让 UI 自行读取 URI 或文件系统。

### 8.3 状态模型

播放状态分为 Idle、Preparing、Ready、Playing、Paused、Ended、Failed，并独立表达：

- 时间线：position、duration、bufferedPosition；
- 连接状态；
- 控件可见性、锁定、拖拽和遮罩；
- 音轨、字幕轨、倍速、画面模式；
- 播放错误和可恢复动作。

播放进度高频刷新不应驱动整个播放页的高频重组。当前代码已经将播放状态和 250ms 的显示位置投影分开；若 profiling 证明仍有掉帧，再进一步拆成独立高频 Flow。

### 8.4 持久化

播放服务在暂停、结束、停止、后台和周期 checkpoint 时写回进度；历史只在满足资格时写入，无痕播放不写历史。后续应将多个写入任务收敛到串行队列，保证旧进度不会晚于新进度完成并覆盖新状态。

## 9. 解码策略

### 9.1 当前事实

当前项目使用 Media3 ExoPlayer 默认 MediaCodec 路径，尚未引入 FFmpeg renderer、设备能力矩阵或解码失败黑名单。不能把总览图中的 `FFmpeg Video Renderer` 视为已经存在的模块。

### 9.2 长期原则

目标策略为会话层统一编排、后端能力条件选择：

```text
媒体探测 + PlaybackCapabilities
        -> BackendSelectionPolicy
        -> Media3PlaybackEngine (默认)
        -> MpvPlaybackEngine (仅在门禁满足时可选)
```

后端内部可以分别选择视频和音频解码路径，允许“视频硬解 + 音频软解”；这一选择不得泄漏为 UI 或 domain 中的具体 decoder 类型。一次播放会话同一时刻只激活一个完整后端，不能由 UI 拼接两个播放器实例。

### 9.3 FFmpeg 引入条件

只有同时完成以下工作，才考虑 FFmpeg：

- 建立真实设备上的失败格式样本和硬件能力矩阵；
- 证明 Media3 默认路径或已验证扩展无法满足目标格式；
- 评估 arm64 包体积、CPU、发热、电量和构建时间；
- 明确 FFmpeg 编译裁剪和 GPL/LGPL 合规方案；
- 通过播放启动、持续播放、异常回退和 native 内存测试。

FFmpeg 不得阻塞冷启动，不得在没有失败证据时成为默认播放路径，也不得为了“格式全”直接替换 Media3。需要转码、Remux、精确切片、字幕烧录或媒体探测时，应通过独立 `ProcessingEngine` 进入处理域；不要从播放页或 `PlayerViewModel` 直接调用 FFmpeg。

## 10. Android 系统服务

当前或已规划的系统边界包括：

- `MediaSession` / 通知控制；
- Audio Focus 和耳机拔出处理；
- Picture-in-Picture；
- WakeLock、PowerManager 和后台播放策略；
- SurfaceView/PlayerView 视频输出；
- 系统权限、SAF 持久授权和应用锁。

这些能力必须通过 app/platform Gateway 注入 domain 或 ViewModel，不把 `Context`、`Intent`、`Surface` 和系统对象泄漏到纯领域契约。

## 11. UI 架构原则

Compose 页面只消费 ViewModel 的状态和事件：

```text
Gesture / Button
    -> UiAction
    -> ViewModel / domain controller
    -> repository / playback controller
```

播放页的加载、缓冲、错误、字幕、音轨、速度和更多设置属于 UI 状态；播放器实例、Media3 Track、Cue 和 Surface 属于基础设施。播放页隐藏一级导航，视频库使用稳定 key 和 contentType，首页使用有限投影而不是全库列表。

UI 设计继续遵守现有设计事实源：默认浅色模式、中性结构色、功能色受限、播放器画布纯黑、触控目标至少 48dp。深色模式、动态配色和高复杂动画不是本架构文档的默认实现内容。

## 12. 错误与可观测性

错误必须区分来源和恢复动作，至少覆盖：

- 权限拒绝/权限丢失；
- 文件不存在或路径失效；
- 解析器错误；
- 解码器初始化/运行时错误；
- Surface、音频焦点和未知错误。

用户文案由 UI 映射，领域层保留稳定错误码。日志必须脱敏，不记录完整路径、授权 URI 查询参数、保险库内容或密钥材料。

长期演进可增加 `PlaybackHealth`、解码决策日志和本地诊断页，但必须先证明具体问题需要这些数据，不能为了“架构完整”记录无用运行时遥测。

## 13. 未来路线与触发条件

### M1：稳定本地播放器（当前主线）

- MediaStore/SAF 索引稳定；
- Room Keyset + Paging 3 长列表稳定；
- 缩略图可见优先、缓存可控；
- Media3 常见格式播放、续播、历史和基础控制稳定；
- 权限、搜索、排序、过滤、回收站和首页回归测试完整。

### M2：体验增强

在 M1 真机验证通过后增加：

- 双击快进、亮度/音量手势、长按倍速；
- 内封/外挂字幕和音轨选择；
- 播放列表和连续播放；
- PiP、迷你播放器和更多设置完善。

### M3：兼容性增强

只有真实失败样本达到阈值后增加：

- 解码能力矩阵；
- 后端能力与失败分类；
- 独立 libmpv Spike；
- Spike 和产品门禁通过后，将 libmpv 作为可选 `PlaybackEngine`；
- 对转码、Remux、精确切片、字幕烧录等处理需求，独立评估 FFmpeg `ProcessingEngine`。

### M4：物理模块拆分

只有构建和协作指标证明单模块成为瓶颈后，才按稳定边界拆分 Gradle 模块；拆分时先迁移纯 contract 和测试，再迁移平台实现，不创建空壳模块。

## 14. 当前已知风险与处理优先级

| 风险 | 处理策略 |
| --- | --- |
| 播放源解析旧结果覆盖新视频 | 为 `PlayerViewModel.open()` 增加 request identity/generation，并测试连续点击 |
| 播放进度写入乱序 | 引入单一串行写入队列，测试暂停、周期 checkpoint、结束交错顺序 |
| 高频 position 重组范围过大 | 使用 Compose 重组/帧耗时 profiling 后再拆状态流 |
| 组合根启动过重 | 仅在启动 profiling 证明有问题时延迟初始化非首屏能力 |
| 超大来源快照占用内存 | 用真机大库测量后再改为流式 identity 索引，不凭猜测重写 |
| Coil 与自定义缩略图缓存职责重叠 | 以统一磁盘文件为准，benchmark 后删除落选缓存路径 |
| FFmpeg 包体积与许可证 | 没有失败样本和合规方案前不引入 |

## 15. 测试与验收

架构变化必须遵循：

```text
修改前基线
    -> 实施重构
    -> 新行为测试
    -> 既有行为回归
    -> 真机性能对比
```

至少覆盖：

- 架构依赖方向和循环依赖；
- MediaStore/SAF 权限和多目录授权；
- 扫描批量写入、取消、失败和缺失标记；
- PagingSource refresh/append/prepend、`getRefreshKey()`、错误重试和 maxSize 往返滚动；
- ThumbnailKey、Provider fallback、取消、优先级、磁盘上限和 Cache Hit Before Bind Rate；
- 播放启动、暂停、seek、错误、恢复、进度和历史写入；
- 搜索、过滤、列表/网格、选择、回收站、首页卡片排序和设置；
- 真机冷启动、首帧、快速滚动、内存峰值、掉帧、发热和 native 内存。

文档中的性能目标必须由设备、媒体库和脚本测量支撑。没有基准数据时，只记录假设，不把经验值写成架构承诺。

## 16. 最终架构结论

YingLi-Player 的稳定主线是：

```text
Compose UI
    -> ViewModel / Domain Contracts
    -> PlaybackSessionClient
    -> app 组合根装配的 data / engine implementations
data / engine
    -> Room / MediaStore + SAF / Thumbnail Sources

Player UI
    -> PlaybackSessionClient
    -> PlaybackSessionRuntime
    -> PlaybackEngine
         +--> Media3 (当前默认)
         +--> libmpv (未来可选)
```

长期保持以下边界：

- UI 不直接操作 Media3、libmpv、Room、ContentResolver 或 FFmpeg；
- 播放会话不依赖具体后端；Media3 是当前默认实现，libmpv 只按证据和门禁接入；
- FFmpeg 只作为独立处理后端评估，不承担完整播放会话；
- 媒体索引、数据库分页、缩略图和播放运行时分别演进；
- 所有高风险异步任务可取消，并丢弃过期结果；
- 所有架构升级以真实测试和性能证据为准；
- 未来规划服务于产品正确性，不为了概念图完整而增加复杂度。
