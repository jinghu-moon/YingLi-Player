# YingLi-Player 本地 Android 手机端架构规划

> 文档性质：面向长期演进的范围约束、目录规划与模块边界
>
> 更新时间：2026-09-06
>
> 前提：只支持本地媒体；只支持 Android；手机优先；平板后期支持；不支持 TV

## 1. 架构决策

YingLi-Player 的目标不是通用媒体平台，而是一个运行在 Android 手机上的本地视频播放器和媒体管理器。

核心链路固定为：

```text
Android 本地媒体
    -> MediaStore / SAF 授权
    -> 增量索引
    -> Room 媒体目录
    -> Paging 3 浏览
    -> 缩略图缓存与预取
    -> Media3 播放
    -> 播放进度、历史与整理关系
```

长期架构必须同时满足：

1. 本地媒体数量达到数万时，索引、浏览和缩略图不会把全库加载到内存。
2. 播放主链路稳定，常见格式优先使用 Android MediaCodec 硬件解码。
3. 播放器、媒体库、缩略图、整理和处理任务可以独立演进。
4. Android 平台 API 被隔离在平台适配边界，不污染领域契约和页面状态。
5. 新能力通过新增边界内实现接入，而不是在首页、播放器或组合根中堆叠分支。

## 2. 明确范围

### 2.1 包含范围

- Android 手机上的本地视频发现、索引和浏览。
- MediaStore 媒体来源。
- 用户通过 SAF 授权的一个或多个本地目录。
- 本地文件播放、续播、播放历史和播放队列。
- 本地缩略图生成、缓存、预取和取消。
- 标签、收藏、合集、播放列表、回收站和最近整理。
- 本地查重、转码、剪辑、处理队列和保险箱。
- Compose 手机界面、权限引导、首页、媒体库、播放页和设置。

### 2.2 明确排除

以下内容不进入当前架构的稳定边界：

- SMB、FTP、SFTP、NFS、WebDAV、UPnP、DLNA 等网络媒体来源。
- 云同步、账号系统、远程数据库、服务端 API 和跨设备同步。
- Android TV、Google TV、车机和遥控器交互模型。
- iOS、桌面端或其他跨平台运行时。
- 为 TV 或跨平台提前抽象导航、输入、窗口和播放器接口。
- 以 VLC 或 mpv 作为第二播放内核长期并存。

这些项目不是“预留但现在不做”的隐性需求，而是当前范围之外的能力。未来若重新纳入，必须以新的架构决策和测试基线为入口。

## 3. 平台策略

### 3.1 Android 是唯一平台

不创建跨平台文件系统、播放器或 UI 抽象。`Context`、`Uri`、`ContentResolver`、SAF、MediaStore、MediaCodec、Surface 和 MediaSession 都可以存在于 Android 平台实现中，但不能进入纯领域契约。

平台边界由 `app`、`data` 和 `engine` 中的 Android 实现承担，领域层只看到稳定的业务模型和能力接口。

### 3.2 手机优先

首要设计目标是竖屏手机和横屏播放场景：

- 触摸操作是主要输入方式，交互目标至少 48dp。
- 首页和媒体库优先优化单列列表、双列/多列网格以及快速滚动。
- 播放页优先处理系统栏、刘海、旋转、画中画和后台播放。
- 使用 Window Size Class 或等价的尺寸状态承载布局差异，但不提前创建平板专用 feature。

平板支持应在手机信息架构稳定后，通过 feature 层的响应式布局增加，不修改 domain、data 或 engine 契约。

### 3.3 不为 TV 设计兼容层

不引入 D-pad 焦点导航、遥控器按键模型、TV 专用资源、Leanback 页面或 TV 专用播放器壳。这样可以避免为了未纳入范围的平台增加大量状态和测试分支。

## 4. 目标文件树

目标文件树先作为逻辑边界使用，再按真实构建和依赖隔离收益逐步拆为 Gradle 模块。目录名称表达职责，不使用 `utils`、`common2`、`manager` 等无边界的聚合包。

```text
YingLi-Player/
├── app/                               # 唯一组合根和 Android 入口
├── build-logic/                       # 统一 Gradle 约定，规模达到需要时启用
├── core/
│   ├── common/                        # Result、时间、调度器、日志、ID、错误基础类型
│   ├── model/                         # MediaId、LocationId、媒体值对象和跨层 DTO
│   ├── designsystem/                  # Compose 主题、Token、图标和通用组件
│   ├── diagnostics/                   # 本地诊断、性能指标和脱敏日志模型
│   └── testing/                       # Fake、Fixture、测试调度器和共享断言
├── domain/
│   ├── catalog/                       # 媒体发现、身份解析、索引和权限状态
│   ├── library/                       # 查询、过滤、排序、Keyset 和分页契约
│   ├── thumbnail/                     # 缩略图请求、Key、优先级和缓存契约
│   ├── playback/                      # 播放请求、状态、队列、恢复和进度策略
│   ├── organize/                      # 标签、收藏、合集、播放列表和回收站关系
│   ├── processing/                    # 查重、转码、剪辑和处理任务契约
│   ├── security/                      # 应用锁、保险箱和安全播放契约
│   └── settings/                      # 设置、备份和诊断入口契约
├── data/
│   ├── room/                          # Room Entity、DAO、迁移和 Repository 实现
│   ├── media-store/                   # MediaStore 查询和变更观察
│   ├── saf/                           # SAF Tree、多目录授权和 DocumentFile 操作
│   ├── preferences/                   # DataStore 偏好实现
│   ├── filesystem/                    # 本地文件、缩略图和处理产物存储
│   └── backup/                        # 本地备份/恢复文件格式和 Android 文档访问
├── engine/
│   ├── playback-api/                  # 播放引擎稳定契约
│   ├── media3/                        # Media3/ExoPlayer、MediaSession 和 Surface 实现
│   ├── thumbnail-api/                 # 抽帧和缩略图来源契约
│   ├── thumbnail-system/              # ContentResolver.loadThumbnail 实现
│   ├── thumbnail-frame/               # Media3 FrameExtractor 实现
│   └── ffmpeg-optional/               # 有真实失败证据时才启用的本地回退
├── feature/
│   ├── shell/                         # 根导航、启动状态和应用锁入口
│   ├── onboarding/                    # 本地媒体权限和目录授权引导
│   ├── home/                          # 首页有限投影和卡片排序
│   ├── library/                       # 媒体库列表、网格、筛选和选择
│   ├── player/                        # 播放页、控制栏、手势和显示设置
│   ├── organize/                      # 收藏、合集、标签和回收站页面
│   ├── processing/                    # 查重、转码、剪辑和任务状态页面
│   ├── security/                      # 应用锁和保险箱页面
│   └── settings/                      # 设置、备份和诊断页面
└── benchmark/                         # 真机性能脚本和基准，不进入生产运行时
    ├── library-scroll/
    ├── thumbnail/
    ├── playback-start/
    └── memory/
```

`build-logic`、`benchmark` 和 `ffmpeg-optional` 是目标边界，不代表现在必须马上创建对应模块。它们只有在构建、性能或功能证据证明需要时才落地。

## 5. 依赖方向

### 5.1 稳定方向

```text
core
  ↑
domain
  ↑
data / engine
  ↑
feature
  ↑
app
```

更具体的约束是：

- `core` 不依赖 `domain`、`feature` 或 `app`。
- `domain` 只依赖 `core`，不依赖 Compose、Room DAO、ContentResolver、ExoPlayer 或 Android Activity。
- `data` 实现 `domain` 定义的 Repository、DataSource 和 Gateway，不反向定义业务规则。
- `engine` 实现播放和缩略图契约；Media3、FrameExtractor 和 FFmpeg 细节留在 engine 内部。
- `feature` 只消费 ViewModel 暴露的状态和事件，不直接访问 Room、MediaStore、SAF 或播放器实例。
- `app` 负责 Application、Service、权限回调和所有实现的装配，是唯一组合根。

### 5.2 业务边界

业务上下文之间通过契约通信，不跨上下文访问 DAO：

```text
catalog -> library       # 媒体目录供查询
catalog -> thumbnail     # 提供稳定媒体位置和请求身份
library -> playback      # 用户选择媒体并产生播放请求
playback -> organize     # 写入历史、进度和最近播放
organize -> home         # 生成首页有限投影
processing -> catalog    # 处理完成后请求目录刷新
security -> playback     # 提供受保护的播放源
```

箭头表示契约依赖或事件关系，不表示允许直接依赖另一个上下文的实现包。

## 6. 本地媒体架构

### 6.1 来源模型

本地媒体来源统一抽象为 `MediaDiscoveryDataSource`，当前只有两个实现：

```text
MediaStoreDataSource
SAFTreeDataSource
        -> MediaDiscoveryEvent
        -> CatalogScanner
        -> MediaIdentityResolver
        -> Room Catalog
```

MediaStore 负责系统媒体库；SAF 负责用户明确授权的目录。多个 SAF Tree URI 是多个授权来源的集合，不是一个“多选文件”请求。

USB/OTG 仅在 Android 以 MediaStore 或 SAF 暴露时沿用这两条路径，不为外接设备建立第三套扫描器。

### 6.2 索引原则

- 先写入 URI、文件名、大小、修改时间、MIME、可用时长和尺寸，再异步补充深度信息。
- 使用稳定位置身份、文件证据和内容 hash 识别替换、移动和重复位置。
- 使用批量 Room 写入，不逐文件提交事务。
- 扫描中断时不得把未完成来源误标记为缺失。
- 索引、缩略图和播放源解析互相独立；任何缩略图失败都不能阻塞目录出现。
- 库查询必须在 Room 中过滤、排序和分页，不把全库装入 ViewModel。

### 6.3 外部变化

首阶段以用户主动扫描为真相更新入口。只有真机证明确有需要，才增加受控的 ContentObserver 或后台同步；不因为“未来可能需要”提前引入持续监听和后台常驻任务。

## 7. 播放与缩略图边界

### 7.1 播放

Media3/ExoPlayer 是唯一默认播放主干：

```text
Feature Player
    -> PlaybackController
    -> MediaController
    -> MediaSessionService
    -> ExoPlayer / MediaCodec
    -> SurfaceView / PlayerView
```

播放服务拥有播放器和 MediaSession；页面只拥有 Controller 连接和 Surface 生命周期。播放请求必须包含媒体身份、物理位置、起始位置和请求身份，避免旧的异步解析结果覆盖新选择。

mpv-android 和 VLC 只作为格式、字幕、渲染和产品能力参考，不作为第二内核加入运行时。只有 Media3 在真实设备和真实媒体样本上无法满足明确需求时，才单独评估替换或回退方案。

### 7.2 缩略图

缩略图是独立的可取消资源管线：

```text
ThumbnailRequest
    -> Memory / Disk Cache
    -> ContentResolver.loadThumbnail()
    -> Media3 FrameExtractor
    -> 本地文件缓存
    -> Coil/Compose 显示
```

显示和预取必须使用相同的尺寸、签名和缓存 key。任务按 `VISIBLE`、`PREFETCH`、`BACKGROUND` 分级，快速滚动时取消未开始且已经离开窗口的预取任务。

`FrameExtractor` 的 `@UnstableApi`、单线程访问和 `Presentation` 降采样约束只能存在于 engine/data 实现，不得泄漏到 domain 或 feature。

## 8. 手机 UI 边界

### 8.1 页面组织

```text
Shell
├── Home
├── Library
├── Organize
├── Player (独立播放层)
├── Processing
├── Security
└── Settings
```

首页只展示统计、继续观看、最近添加、我的合集和维护提醒等有限投影，不持有全库媒体列表。媒体库负责分页和滚动；播放页不显示一级底部导航。

### 8.2 状态规则

- 页面通过 `UiState + UiAction` 与 ViewModel 通信。
- 高频播放位置、缓冲和手势状态必须拆分，不能驱动整个页面重组。
- 列表使用稳定 key 和 contentType；`PagingData` 由 ViewModel 在 `cachedIn(viewModelScope)` 后提供。
- 加载、追加、失败和重试状态由 `LoadState` 表达。
- 首页卡片顺序和可见性属于 DataStore 偏好；媒体事实仍来自 Room。

### 8.3 平板演进

平板后期只允许在 `feature` 增加响应式布局、双栏导航和更宽的网格；不得让平板尺寸判断进入 domain、data 或 engine。手机端的状态模型、数据查询和播放服务保持不变。

## 9. 本地处理与安全

查重、转码、剪辑和保险箱都是本地能力，不能把它们隐含为网络服务：

```text
Feature
    -> Domain Processing/Security Contract
    -> Local Repository / Queue
    -> Android Executor / Media3 / Keystore
    -> Room 状态和本地产物
```

- 处理任务必须有可取消状态、进度、重试和失败原因。
- 转码使用 Media3/Android 平台能力为主；FFmpeg 仅在格式证据、包体积、性能和许可证均可接受时引入。
- 保险箱的密钥材料只在 Keystore/安全基础设施边界处理，不能进入日志、UI 状态或普通媒体模型。
- 文件操作必须经过能力接口，页面不能直接删除、移动或重命名文件。

## 10. Gradle 模块演进

当前单 `app` 模块可以继续作为开发主线，但目标目录必须先按上述边界组织。物理拆分按以下顺序进行：

1. 先用包级架构测试固定 `core -> domain -> data/engine -> feature -> app` 方向。
2. 抽出无 Android 依赖的 `core.model`、`core.common` 和 domain 契约。
3. 当编译或依赖隔离有可测量收益时，拆出 `data.room`、`data.media-store`、`data.saf` 和 `engine.media3`。
4. 当 feature 之间的构建和协作冲突成为瓶颈时，再拆分 `feature.home`、`feature.library`、`feature.player` 等模块。
5. 最后再考虑 `build-logic`、独立 benchmark 模块和可选 FFmpeg 模块。

不创建没有真实职责的空模块，也不为了“看起来像大型架构”复制接口、Adapter 或兼容层。开发阶段允许删除和重命名旧实现，但每次迁移都必须保留当前已有功能的测试覆盖。

## 11. 强制目录与命名规则

1. 一个能力的契约、实现、页面和测试必须能够按包名追踪。
2. 契约使用 `*Repository`、`*Gateway`、`*Controller`、`*Source` 等明确职责命名。
3. Android 实现使用 `Android*`、`Media3*`、`Room*`、`DataStore*` 等前缀或后缀标识平台技术。
4. 禁止新增无上下文的 `Utils`、`Helper`、`Manager`、`CommonRepository`。
5. 数据库 Entity/DAO 只能位于 data/Room 边界；domain 只能使用领域模型。
6. Compose 屏幕、ViewModel、UI 状态和 UI 事件只能位于 feature。
7. 共享测试替身位于 `core.testing` 或测试源集，不能进入生产包。
8. 文件树变化必须同步更新架构测试和相关文档。

## 12. 测试与架构验收

每次边界变化遵循：

```text
修改前基线
    -> 架构/单元测试
    -> 实现迁移
    -> 新行为测试
    -> 既有行为回归
    -> 真机性能对比
```

最低验收范围：

- 架构依赖方向、包循环和平台泄漏检查。
- MediaStore/SAF 权限、多目录授权、扫描取消和部分失败。
- Room 身份解析、Keyset 分页、Paging refresh/append/prepend、`getRefreshKey()` 和重试。
- 缩略图 key、缓存上限、Provider 回退、取消、预取命中率和滚动掉帧。
- 播放启动、首帧、暂停、seek、Surface、后台、进度写入和错误恢复。
- 收藏、合集、回收站、查重、处理队列、保险箱和首页投影。
- 手机冷启动、8000+ 视频滚动、内存峰值、native 内存和长时间操作稳定性。

关键性能指标包括：

- App 冷启动到首页可交互时间。
- 点击视频到播放页和第一帧的时间。
- `Cache Hit Before Bind Rate`。
- 快速上下滚动的掉帧、追加等待和页面重载次数。
- 索引总耗时、批量写入耗时和内存峰值。
- 播放页和媒体库在持续操作中的 Java/native 内存变化。

没有真实设备和媒体样本数据时，指标只能作为待测假设，不能写成承诺。

## 13. 最终边界

```text
Android 手机
    ├── 本地来源：MediaStore / SAF
    ├── 本地数据：Room / DataStore / 文件系统
    ├── 播放主干：Media3 / ExoPlayer
    ├── 缩略图：系统 Provider / FrameExtractor / 本地缓存
    ├── 本地处理：查重 / 转码 / 剪辑 / 保险箱
    └── UI：Compose 手机界面
```

最终原则：

- 范围越窄，边界越应清晰；不因排除网络和 TV 而把所有代码堆进 `app`。
- 长期可扩展性来自稳定的领域契约和可替换的 Android 实现，而不是提前引入跨平台抽象。
- Media3、Room、Paging 和 Android 存储 API 是当前技术主干；替换它们必须有真实证据和独立测试。
- 物理 Gradle 模块按收益拆分，逻辑文件树和依赖规则现在就必须执行。
- 开发期不维护历史兼容层，但任何重构都必须证明新功能正确且当前功能没有被无意破坏。

## 14. 当前迁移落地状态

本阶段在 `refactor/local-android-architecture` 分支完成了第一批逻辑边界迁移。项目仍保持单 `app` Gradle 模块，迁移目标是先让包名、依赖方向和测试归属与目标文件树一致，再依据构建隔离和性能收益拆分物理模块。

已落地的包边界如下：

```text
core.foundation       -> core.common
core.database         -> data.room
core.datastore        -> data.preferences
domain.media          -> domain.catalog
app.home              -> data.home
app.library           -> data.library
app.organize          -> data.organize
app.duplicates        -> data.duplicates
app.processing        -> data.processing
app.security          -> data.security
app.settings          -> data.settings
app.transcode         -> data.processing.transcode
app.clips             -> data.processing.clips
app.playback          -> engine.media3
core.media contracts  -> domain.catalog / domain.thumbnail
core.media sources    -> data.sources / data.filesystem
core.media thumbnail  -> engine.thumbnail
```

Android 生命周期组件保留在应用入口边界：

```text
app.YingLiPlaybackService
app.processing.YingLiProcessingService
```

播放控制器通过应用装配时注入 Service `ComponentName`，不再由 `engine.media3` 反向依赖 `app`，从而消除组合根与播放引擎之间的循环依赖。媒体缩略图 Compose 组件已从 `core.designsystem` 移到 `feature.library`，避免 core 依赖领域缩略图契约。

当前仍未物理拆分的目标边界：

- `data.sources` 后续可按实际依赖拆为 `data.media-store` 与 `data.saf`；
- `engine.thumbnail` 后续可按缓存调度、系统 Provider 和 Media3 FrameExtractor 拆为独立 Gradle 模块；
- `core`、`domain`、`data`、`engine`、`feature` 仍在单一 Android 模块内，只有出现可量化的构建或依赖隔离收益时才拆分。

本次迁移的验证基线：

```text
:app:compileDebugKotlin
:app:testDebugUnitTest
:app:compileDebugAndroidTestKotlin
git diff --check
```

其中单元测试保持 159 个用例通过；AndroidTest 已完成 Kotlin 编译检查。真机安装、扫描 8000+ 视频、分页滚动和缩略图命中率仍需在迁移分支构建 APK 后执行设备回归。
