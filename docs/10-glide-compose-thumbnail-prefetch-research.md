# Glide、Compose 与本地视频缩略图预取调研

> 调研日期：2026-09-04
> 适用项目：YingLi-Player（Android、Kotlin、Jetpack Compose）
> 文档性质：实现前的技术调研与架构决策记录

## 1. 调研范围与结论

本次调研覆盖四类资料：

1. 更新后的 `refer/安卓视频索引算法_d9d4bd45a308.md`。
2. `refer/glide-master` 当前保存的 Glide 源码，重点是 Compose 集成、RecyclerView 预加载、视频帧解码、缓存和请求生命周期。
3. `refer/glide-docs-cn-master` 中关于 RecyclerViewPreloader、缓存命中和固定尺寸的中文文档。
4. Android 官方 MediaStore、`ContentResolver.loadThumbnail()`、Compose Lazy 列表、Paging 3 和 RecyclerView 资料，以及 Glide 官方文档。

核心结论如下：

- 本地视频发现应以 MediaStore 为入口，基础索引与缩略图、深度元数据解码必须分层，不能在首次扫描时为全部视频同步生成缩略图。
- Glide 的核心启发不是“必须把项目改成 Glide”，而是请求一致性、固定解码尺寸、缓存分层、滚动方向预取、请求取消和资源复用这些工程原则。
- 当前项目使用 Compose `LazyVerticalGrid`/`LazyColumn`，不能直接套用 Glide 的 `RecyclerViewPreloader`。该预加载器的默认滚动监听器依赖 `LinearLayoutManager`，其他布局需要自行翻译滚动位置。Glide 官方页面目前将 Compose 集成描述为 Beta，并提供面向 `LazyRow`/`LazyColumn` 的原生预加载器；这些 API 不应作为项目领域层稳定契约。
- Glide 官方页面还警告 Compose 集成近期 API 正在回滚和重新评估，依赖旧行为的应用可以锁定 `com.github.bumptech.glide:compose:1.0.0-beta01`。因此必须把实际依赖版本和 API 证据记录在构建配置中，不能只依据在线页面的最新示例编码。
- Media3 `FrameExtractor` 当前标记为 `@UnstableApi`，实例必须只从单一 application thread 访问；其版本、线程和生命周期约束必须封装在基础设施实现中，不能泄漏到 `ThumbnailLoader` 领域接口。
- 在没有基准测试证明迁移收益前，不建议整体从 Coil 切换到 Glide。应先收敛项目自己的 `ThumbnailLoader` 接口，再以 Android `loadThumbnail()` 为首选来源，并保留现有 Coil/Media3 作为失败回退。
- 当前最有价值的优化顺序是：数据库级 keyset 分页、统一缩略图请求和缓存 key、持久化磁盘缓存、可见区域优先、滚动方向预取、快速 fling 降级，最后再比较 Coil 与 Glide 的解码性能。

## 2. 索引算法文档的可执行原则

### 2.1 发现阶段使用 MediaStore

Android 共享存储中的视频由 `MediaStore.Video` 提供结构化索引。与递归遍历目录并逐个打开文件相比，MediaStore 能直接返回 URI、名称、大小、修改时间、MIME、时长、宽高等基础字段，也能通过 `ContentObserver` 支持后续增量更新。

基础查询应只选择当前页面或索引所需字段，并使用稳定排序。例如排序字段为修改时间时，应追加 `_ID` 作为唯一的 tie-breaker，避免分页过程中同值记录改变顺序。

### 2.2 首次启动分层

建议把首次进入媒体库拆成三个优先级：

- **P0 基础索引**：快速读取 MediaStore，写入本地数据库，立即让列表可滚动。
- **P1 可见缩略图**：只处理当前可见项和滚动方向前方少量项目。
- **P2 深度元数据**：在后台补充编码器、帧率、码率等非首屏字段。

UI 状态应区分“索引进行中”“索引完成但缩略图加载中”和“缩略图失败”。不能用单一的全屏加载状态阻塞整个媒体库，也不能把缩略图生成当作索引完成的前置条件。

### 2.3 缩略图尺寸和缓存

缩略图应按照实际显示尺寸或略大于显示尺寸解码，例如列表当前约 `140dp` 宽、`90dp` 高的布局，可在运行时将目标尺寸转换为像素并使用 `320x180` 左右的统一上限。禁止使用原图尺寸作为列表缩略图的默认目标，否则一个 4K 视频可能在快速滚动时产生过大的 Bitmap 和解码压力。

缓存 key 至少应包含：媒体稳定 ID、位置/授权标识、目标宽高和媒体版本信息（如 `dateModified`，必要时加文件大小）。视频被替换而 ID 未改变时，版本字段可以避免继续使用旧缩略图。

### 2.4 分页与缩略图预取解耦

数据分页决定“哪些媒体对象进入内存”；缩略图预取决定“哪些媒体对象提前解码”。两者不应绑在同一个任务中：分页触发可以继续使用 cursor/keyset，缩略图则根据可见范围和滚动方向独立调度。这样能避免用户只浏览少量项目时提前解码整页，也能在方向变化时取消无价值的任务。

## 3. Android 官方资料结论

### 3.1 `ContentResolver.loadThumbnail()`

Android 官方建议使用 `ContentResolver.loadThumbnail(uri, size, signal)` 获取 MediaStore 或文档提供方的缩略图。它通过 `ContentProvider.openTypedAssetFile` 请求缩略图，并将目标尺寸传给 Provider，使 Provider 有机会避免生成或传输过重的资源；调用方还可以使用取消信号终止不再需要的请求。它是系统 Provider 路径，不是万能的视频解码器。旧的 `MediaStore.Video.Thumbnails` API 已废弃，不应作为新实现的主路径。

推荐的来源顺序：

```text
内存缓存
  -> 磁盘缓存
  -> ContentResolver.loadThumbnail()
  -> Media3 FrameExtractor（标准视频帧后备实现）
  -> 占位图和可重试失败状态
```

`loadThumbnail()` 失败并不一定表示视频不可播放，可能只是某个 DocumentProvider 没有实现缩略图，因此必须有回退路径。

### 3.2 Compose Lazy 列表

Compose Lazy 列表应为每个媒体项提供稳定 key；项目当前使用 `LibraryMedia.id.value`，方向是正确的。列表和网格的布局预取只负责提前创建少量 item，不能替代视频帧的解码缓存。

Glide 官方 Compose 页面新增了 `GlideLazyListPreloader`：它使用 `LazyListState` 判断滚动方向，面向 `LazyRow`/`LazyColumn` 预加载前方项目。它不是专门为 `LazyVerticalGrid` 设计的；网格场景需要验证其位置语义，或在项目自己的 `ThumbnailLoader` 中实现网格方向预取。

当前 `LazyVerticalGrid` API 已直接接收 `cacheWindow`，这是 Compose 网格缓存窗口的当前 API 方向；旧的 `LazyGridPrefetchStrategy` 已标记为弃用。`LazyLayoutCacheWindow`/`CacheWindow` 只控制 Lazy item 的组合和布局缓存，不会解码或缓存视频帧，因此必须与缩略图缓存和预取分开调优。

当数据量增长到数万级时，建议使用 Paging 3 或等价的数据库级 keyset 分页。`prefetchDistance` 用于接近边界时请求下一页，`maxSize` 必须覆盖页面和预取范围，否则已加载页面可能被过早丢弃并反复查询。

## 4. Glide 源码研读

### 4.1 Compose 预加载：`rememberGlidePreloadingData`

源码位置：

```text
refer/glide-master/integration/compose/src/main/java/com/bumptech/glide/integration/compose/Preload.kt
```

本地源码快照中没有 `GlideLazyListPreloader` 符号，实际公开实现是 `rememberGlidePreloadingData`。该 API 将 Lazy 列表中的 index 访问转换成 Glide `ListPreloader` 的滚动事件。实现中有几个必须保留的约束：

1. 预加载请求和实际显示请求必须完全一致，包括 model、尺寸、变换、占位图和错误选项；否则会生成不同的缓存 key，预加载无法被显示请求复用。
2. `preloadImageSize` 是显式的 `Size`，用于在布局测量前固定 `RequestBuilder.override()` 的尺寸。它应对应真实缩略图目标尺寸，而不是 `SIZE_ORIGINAL`。
3. `numberOfItemsToPreload` 默认值为 10。数量越大，命中率不一定越高，可能超过内存缓存并增加无效 I/O；必须用目标设备和真实视频库做压测。
4. API 假设列表按用户显示顺序访问。随机跳转或大范围重排会扰乱预加载器的方向判断，因此搜索、排序切换和分页刷新后应重置预加载状态。
5. 本地快照的 `ExperimentalGlideComposeApi` 注解明确写着 “APIs may change or be removed without warning”，并且 `Preload.kt` 仍暴露 `RequestBuilder`。这说明该快照的 API 形态并未稳定。

官网示例还给出了两个实现约束：

- `GlideLazyListPreloader` 的 `size` 必须与 `GlideImage` 的主请求或至少一个 thumbnail 请求完全匹配；示例通过 `signature()` 让预加载和显示请求使用相同的媒体版本。
- `GlideImage` 只有在 Modifier 提供有界尺寸时才能高效推导目标大小；宽或高无界时会使用 `Target.SIZE_ORIGINAL`，可能造成过高内存占用。视频列表必须使用固定尺寸或显式 `override()`。

官网同时说明 `GlideSubcomposition` 会在每次加载状态变化时触发额外重组，不应在滚动列表中用于显示加载状态。列表应优先使用稳定占位图和单次 `GlideImage`，避免把每张缩略图的状态变化扩散为多次 Compose 重组。

官网示例与本地快照还有一处需要特别核对的差异：官网的 `GlideImage` 回调会接收一个已经对 model 调用 `load()` 的 `RequestBuilder`；而本地 `Preload.kt` 的 `PreloadRequestBuilderTransform` 注释明确要求调用方自己执行 `RequestBuilder.load()`。因此不能把两个版本的回调语义混用。官网还说明 `RequestBuilder.transition` 会被忽略，过渡应通过 Compose 的 `GlideImage` API 指定；本地 `GlideImage.kt` 也有同样注释。

官方 Compose 页面目前将 Glide Compose 整体标为 Beta，并展示 `GlideLazyListPreloader`。页面还明确表示近期 API 正在回滚，未来会移除部分变化；这与本地快照的注解和符号不一致，说明本地 `refer/glide-master` 与官网文档不是同一发布快照。实现时应锁定实际依赖版本，逐项确认 API；不应把官网新 API 假定为本地源码已经具备。无论 Beta 还是本地 Experimental，均不应直接成为项目领域层契约。

`GlideImage.kt` 还展示了 Compose 生命周期绑定方式：通过 `LocalLifecycleOwner`、`remember` 的 `RequestManager`/请求构建器和状态 painter，确保请求随组合生命周期管理，并区分加载、成功和失败状态。这些状态边界可用于改进项目当前的 UI 状态建模。

### 4.2 RecyclerView 预加载：`RecyclerViewPreloader`

源码位置：

```text
refer/glide-master/integration/recyclerview/src/main/java/com/bumptech/glide/integration/recyclerview/RecyclerViewPreloader.java
```

经典 RecyclerView 方案由四部分组成：

1. `PreloadSizeProvider`：提供与 Adapter 实际绑定相同的尺寸。
2. `PreloadModelProvider`：把即将显示的模型转换成 Glide 请求。
3. `RecyclerViewPreloader`：维护前方项目窗口并触发请求。
4. `RecyclerView.OnScrollListener`：接收滚动方向和位置变化。

源码和中文文档都特别警告：默认监听器假定使用 `LinearLayoutManager` 或其子类。Grid、自定义布局或其他 LayoutManager 必须自行实现位置翻译，否则可能崩溃。当前项目是 Compose Lazy Grid/Column，因此不能直接复制这个监听器。

### 4.3 视频帧解码：Provider 优先与 Media3 后备

相关源码：

```text
refer/glide-master/library/src/main/java/com/bumptech/glide/load/resource/bitmap/VideoDecoder.java
refer/glide-master/library/src/main/java/com/bumptech/glide/load/model/stream/MediaStoreVideoThumbLoader.java
```

`MediaStoreVideoThumbLoader` 尝试从 MediaStore 视频 URI 获取系统缩略图；`VideoDecoder` 是 Glide 当前源码中的候选视频帧实现，内部仍使用平台 `MediaMetadataRetriever` 等能力并根据目标宽高选择缩放路径。对 YingLi-Player 的 2026 年架构建议是：先复用系统 Provider 结果，失败后优先评估 Media3 Inspector 的 `FrameExtractor`，最后才把 Glide `VideoDecoder` 或 Coil `VideoFrameDecoder` 作为可替换候选实现。Media3 官方把 `FrameExtractor` 的典型用途明确列为视频图库缩略图生成，`getThumbnail()` 会寻找代表性位置，找不到时回退到媒体开头。

当前 Media3 API 的 `FrameExtractor` 标记为 `@UnstableApi`。每个 extractor 实例必须只从单一 application thread 访问；可以通过该实例返回的异步 Future 把解码结果交给其他线程，但不能让多个并发 worker 直接共享同一个 extractor。项目应在基础设施层为 extractor 建立单线程串行执行器或实例池，并把这一约束隐藏在 `ThumbnailLoader` 后面。

如果目标不是原始分辨率，应在帧提取阶段直接应用 Media3 `Presentation` 降采样，再将结果写入目标尺寸缓存；不要先解码 4K 原帧再在 UI 或缓存层缩放。Media3 1.10 起，`FrameExtractor` 位于独立的 `androidx.media3:media3-inspector-frame` 模块（不再从主 `media3-inspector` 模块导入）。

因此推荐链路是：

```text
Thumbnail Cache
      ↓ miss
ContentResolver.loadThumbnail()
      ↓ failure / unavailable
Media3 FrameExtractor
      ↓ failure
Error Placeholder
```

这不是说 Glide/Coil 不能使用，而是避免把平台视频解析能力绑定到某个图片加载库；Glide `VideoDecoder` 应作为基准对照或替换实现，而不是架构中的必经层。

### 4.4 缓存和请求生命周期

`LruResourceCache` 展示了基于资源大小的内存 LRU 缓存；`DiskLruCacheWrapper` 提供了可复用的磁盘 LRU 包装。`RequestTracker` 和 `SingleRequest` 负责请求的开始、暂停、清理、取消以及生命周期状态转换。

项目当前 `PriorityThumbnailRepository` 只有最多 256 个 key 的内存完成记录，这不是缩略图 Bitmap 的持久化缓存。重启后会丢失状态，且它不能保证 UI 的 `AsyncImage` 请求与队列提取器共享同一个缓存。应把“缓存是否命中”和“任务是否完成”分开建模，并让 UI 与预取使用同一套请求构造规则。

## 5. 审核报告逐项校核

下表将审核报告中的判断与本地源码和官方资料逐项对齐。这里的“本地源码”特指 `refer/glide-master` 当前快照，不能替代最终 Gradle 依赖版本的 API 检查。

| 审核项 | 本地 Glide 源码证据 | 官方资料/网页结论 | YingLi-Player 决策 |
| --- | --- | --- | --- |
| Compose API 状态 | `ExperimentalGlideComposeApi.kt` 使用 `@RequiresOptIn`，并写明 API 可能无预警变更或移除 | Glide Compose 官方页面目前标为 Beta，并警告 API 可能继续调整 | 文档统一写为“Beta；不作为领域层稳定契约”，同时保留本地快照的 Experimental 证据 |
| Compose 原生预加载 | 本地源码没有 `GlideLazyListPreloader` 符号，实际是 `rememberGlidePreloadingData` + `ListPreloader` | 官方页面展示 `GlideLazyListPreloader`，面向 `LazyRow`/`LazyColumn` 使用 `LazyListState` 判断方向 | `LazyColumn`/`LazyRow` 可验证官方 API；`LazyVerticalGrid` 不假定可直接复用，需自行验证或实现网格预取 |
| Beta 分支稳定性 | 本地快照的 API 注解不承诺稳定 | 官网警告近期 Compose API 正在回滚，依赖旧行为可锁定 `com.github.bumptech.glide:compose:1.0.0-beta01` | 评估时固定版本、记录 API；不在生产领域接口暴露 Glide Compose 类型 |
| 重组和占位策略 | 本地 `GlideImage` 支持 loading/failure placeholder；`GlideSubcomposition` 需 opt-in | 官网警告滚动列表中按加载状态进行 subcomposition 会产生多次重组和明显 jank | 列表使用稳定占位图和单次加载组件，不以每张缩略图状态驱动额外子组合 |
| 尺寸约束 | 本地 `GlideImage.kt` 明确建议设置固定尺寸，避免 `SIZE_ORIGINAL` | 官网说明无界宽高会退化为 `Target.SIZE_ORIGINAL`，并建议 Modifier 有界尺寸或 `override()` | 列表/网格统一显式目标像素尺寸，禁止原图尺寸作为缩略图默认策略 |
| `loadThumbnail()` | Glide 的 `MediaStoreVideoThumbLoader` 是一个 Provider 缩略图 loader，不等于任意视频解码 | Android API 通过 `openTypedAssetFile` 将目标尺寸和取消信号交给 Provider | 把它定义为系统 Provider 首选路径，明确失败不代表视频不可播放 |
| Media3 后备帧提取 | 本地 Glide `VideoDecoder` 仍通过平台 `MediaMetadataRetriever` 等能力提帧 | Media3 Inspector 官方将 `FrameExtractor` 明确用于视频图库缩略图，并提供 `getThumbnail()`；API 标记 `@UnstableApi`，实例要求单一 application thread | 项目级标准后备优先评估 Media3 `FrameExtractor`；以单线程封装、`Presentation` 提取期降采样；Glide/Coil 作为可替换 benchmark 实现 |
| Media3 模块与版本 | 当前项目只声明 ExoPlayer、Session、UI、Transformer，尚未声明 inspector 模块 | Media3 1.10 将 `FrameExtractor` 拆到 `androidx.media3:media3-inspector-frame` | 若实施，新增与 Media3 版本锁定的 inspector-frame 依赖，并只在基础设施模块导入不稳定 API |
| Lazy Grid 缓存 | CacheWindow 不属于 Glide 源码，而属于 Compose Foundation | 当前 `LazyVerticalGrid` 直接接收 `cacheWindow`；旧 `LazyGridPrefetchStrategy` 已弃用；窗口不是视频帧缓存 | 与 `ThumbnailLoader` 的内存/磁盘缓存分开调优、分开埋点 |
| 快速 fling 指标 | Glide 预加载依赖固定尺寸、请求一致性和有限预加载窗口 | 官方资料强调预加载数量过多会超过缓存并降低效果 | 新增 `Cache Hit Before Bind Rate`，优先评价即将显示项在绑定前是否 ready |

## 6. Glide 中文文档的价值与限制

本地中文文档：

```text
refer/glide-docs-cn-master/_posts/2017-05-10-recyclerview.md
refer/glide-docs-cn-master/_pages/recyclerview.md
```

可直接借鉴的工程规则：

- Adapter 和预加载器必须使用同样的 `override()` 尺寸和请求选项。
- `maxPreload` 先覆盖约 2～3 行，再根据设备和滚动速度调整。
- 预加载过少会来不及准备，过多会浪费 CPU、内存、磁盘和无效请求。
- 快速滚动不流畅时，可以先加载较低尺寸，停止滚动后再替换高质量资源。
- 默认滚动监听器不是所有 LayoutManager 的通用实现。

该仓库是 Glide 4.x 的非官方翻译，最后同步时间为 2020-07-20，项目已归档。它适合作为概念和旧版 API 的中文索引，不能作为当前 Compose API、Gradle 配置或 Glide 未来版本行为的权威依据。当前 Compose 结论应以 `glide-master/integration/compose` 源码和 Glide 官方文档为准。

## 7. 与当前 YingLi-Player 的对照

### 7.1 已具备的基础

- `LibraryScreen` 已使用 `LazyVerticalGrid` 和 `LazyColumn`，并为媒体项提供稳定 key。
- 列表和网格都有接近末尾触发 `onLoadMore` 的无限滚动逻辑，加载失败可重试。
- `PriorityThumbnailRepository` 已有优先级队列、可配置并发数、运行任务取消和一次失败重试。
- 项目已经把缩略图请求抽象为 `ThumbnailRepository`/`ThumbnailExtractor`，具备替换底层实现的边界。

### 7.2 关键缺口

#### 数据库分页仍是全量内存处理

`RoomLibraryRepository.execute()` 先读取全部行，再内存去重、过滤、排序，最后执行 `drop(startIndex).take(pageSize)`。这会使 UI 分页无法降低数据库读取、对象创建和排序成本；视频数量达到 8000 或更高时，滚动期间的刷新和搜索仍会反复处理全量数据。

目标应是 DAO 层完成过滤和排序，并使用包含唯一 tie-breaker 的 keyset 条件，例如：

```text
(sort_value < cursor.sort_value)
OR (sort_value = cursor.sort_value AND id < cursor.id)
ORDER BY sort_value DESC, id DESC
LIMIT :pageSize
```

升序时相应调整比较符号。这样下一页不依赖 `OFFSET` 或重新构建完整列表。

#### 缩略图路径未统一

`PriorityThumbnailRepository` 使用 Coil `VideoFrameDecoder` 创建独立 `ImageLoader`，而 `LibraryScreen` 使用 `AsyncImage(model = item.uri.value)`。如果二者没有共享相同的 ImageLoader、尺寸、缓存策略和 key，预取结果可能无法被 UI 复用，造成重复解码。

#### 缓存 key 缺少媒体版本

当前 `ThumbnailRequest.cacheKey` 类似：

```text
mediaItemId:locationId:width:height
```

应加入 `dateModified`，必要时加入文件大小或内容摘要。否则文件替换后可能显示旧缩略图。

#### 缺少稳定的磁盘缩略图缓存

`cachedKeys` 是进程内 LRU 完成记录，不等价于磁盘 Bitmap/EncodedImage 缓存。应用重启、进程被杀或内存压力回收后，系统会再次执行昂贵解码。

#### 缺少滚动方向感知和快速 fling 降级

当前队列有优先级，但没有由 Lazy 列表滚动方向驱动的批量预取、取消和降级策略。向上反向滚动时，之前排入的向下任务仍可能占用解码槽位。

## 8. 推荐目标架构

```text
                         MediaStore.Video
                               │
                               ▼
                        P0 增量索引器
                               │
                               ▼
                    Room / SQLite Keyset
                               │
                               ▼
             ┌─────────────────────────────┐
             │ Compose Lazy Grid / List    │
             │                             │
             │ Stable Key + ContentType    │
             │ CacheWindow                 │
             └──────────────┬──────────────┘
                            │
                            ▼
                    ThumbnailLoader
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
          VISIBLE       PREFETCH       BACKGROUND
             │              │              │
             └──────────────┼──────────────┘
                            ▼
                    Memory LRU Cache
                            │
                            ▼
                    Disk Thumbnail Cache
                            │
                     cache miss
                            ▼
              ContentResolver.loadThumbnail()
                            │
                         failure
                            ▼
                   Media3 FrameExtractor
                            │
                        failure
                            ▼
                       Placeholder
```

其中 `Media3 FrameExtractor` 属于基础设施实现：它的 `@UnstableApi`、单线程访问、版本和依赖模块约束都必须停留在该层；UI 和领域层只依赖稳定的 `ThumbnailLoader`。`CacheWindow` 只影响上图中的 Compose item 缓存窗口，不应被当作 Thumbnail Cache 的替代品。

`ThumbnailLoader` 应是项目自己的领域边界，建议至少包含：

- `ThumbnailKey(mediaId, version, width, height, transformation)`。
- `request(key, uri, priority): Flow<ThumbnailState>`。
- `cancel(key)` 与按方向/范围取消的批量接口。
- `prefetch(keys, direction)`，只接收已经确定显示顺序的 key。
- 共享的请求构造器，确保预取与实际显示生成相同缓存 key。

底层实现可以先继续使用 Coil，并增加 `loadThumbnail()` 数据源和磁盘缓存；只有在基准测试显示 Glide 在目标设备、目标视频格式和目标滚动速度下明显更好时，才考虑替换实现。这样遵循依赖倒置，UI 不依赖某个媒体库。

## 9. 分阶段实施建议

### 阶段 A：先修正当前实现的正确性

1. 统一 UI 显示和预取的缩略图尺寸、变换、占位和错误策略。
2. 将 `dateModified`/文件大小加入缩略图 key，并在媒体更新时使旧缓存失效。
3. 让 `AsyncImage` 与项目的 `ThumbnailLoader` 共用同一个 ImageLoader/缓存，或让 UI 直接收集 loader 状态，避免两条独立请求链。
4. 优先调用 `ContentResolver.loadThumbnail()`，失败后使用 Media3 `FrameExtractor`；Glide/Coil 帧提取作为可替换的 benchmark 实现。

### 阶段 B：消除全量分页成本

1. 在 Room DAO 增加按排序字段和过滤条件查询的 keyset 分页方法。
2. 为常用过滤字段建立索引，避免每次 Flow 发射都把全表映射为领域对象。
3. 保留当前 cursor 对外协议，替换内部 `drop/take` 实现，减少 UI 层改动。
4. 对搜索、排序、目录授权变化建立明确的分页会话 ID；会话变化时丢弃旧页结果。

### 阶段 C：加入方向感知预取

1. 从 `LazyListState`/`LazyGridState` 的可见区间和 `isScrollInProgress` 得到方向。
2. 停止滚动前只保证可见项和 1～2 行前方项目；快速 fling 时暂停远处解码。
3. 方向改变时取消旧方向的 PREFETCH/BACKGROUND 任务，保留 VISIBLE 任务。
4. 预取窗口按像素和行数限制，而不是无界地按 item 数量增长。

### 阶段 D：用真实数据决定是否引入 Glide

如需验证 Glide，可建立独立实现：

- Compose `LazyColumn`/`LazyRow` 路径可试用官网的 `GlideLazyListPreloader`；若依赖版本只有本地源码中的 `rememberGlidePreloadingData`，则按该 API 进行验证，并固定 `preloadImageSize` 和较小的预加载窗口。
- `LazyVerticalGrid` 不应假定 Glide 的 LazyList 预加载器可直接复用；应单独验证网格位置和方向语义，或使用项目自己的网格预取实现。
- 同时评估 Compose `CacheWindow`，并单独记录 item cache 命中和缩略图 cache 命中，避免将两者混为一个指标。
- `LazyVerticalGrid` 优先使用当前 `cacheWindow` 参数，不再围绕已弃用的 `LazyGridPrefetchStrategy` 设计新接口。
- 不在滚动列表中使用 `GlideSubcomposition` 观察每张图片的 Loading/Success 状态；状态变化应通过稳定 painter/placeholder 或项目 loader 状态一次性呈现。
- 对 Glide Compose 做实验时固定有界 Modifier 或显式 `override()`，并让预加载请求和显示请求共享同一 `signature`/媒体版本。
- 若需要复现官网旧 Beta 行为，单独建立 `com.github.bumptech.glide:compose:1.0.0-beta01` 的实验变体，不与默认实现混用。
- 若采用 Media3 `FrameExtractor`，使用 `androidx.media3:media3-inspector-frame`，在基础设施内部标记并处理 `@UnstableApi`，以单一 application thread 串行访问 extractor，并在提取阶段通过 `Presentation` 降采样。
- 不把 `RecyclerViewPreloader` 直接用于 Compose Lazy Grid。
- 记录首屏缩略图可见时间、滚动丢帧、解码耗时、内存峰值、磁盘命中率和失败率。
- 与现有 Coil 实现使用相同 URI、尺寸、缓存 key、视频样本和滚动脚本进行 A/B 测试。

## 10. 测试与基准要求

项目属于开发期，允许重构，但每次改变索引、分页或缩略图路径都必须有前后测试。建议至少覆盖：

### 单元测试

- keyset cursor 在同一排序值、边界、空页和删除记录后的行为。
- `dateModified` 或文件大小变化会生成新的缩略图 key。
- 缩略图优先级顺序：VISIBLE 高于 PREFETCH，高于 BACKGROUND。
- 取消、重试、方向切换不会重复启动同一 key 的任务。
- 预取和实际显示的请求参数完全相同。

### Repository/数据库测试

- 8000、20000 条媒体记录下，分页查询只返回页面所需行。
- 名称、目录、标签、分辨率和回收站过滤与旧行为一致。
- 同一 cursor 连续翻页无重复、无遗漏，排序方向正确。
- MediaStore 增量插入、更新、删除后，数据库和分页会话最终一致。

### Compose UI 测试

- 首次进入先显示基础索引，随后逐项显示缩略图，不被全屏动画阻塞。
- 列表和网格快速上下滚动可到达最后一项，接近末尾会加载下一页。
- 反向滚动时前方缩略图优先，离开可见区域的任务可取消。
- 缩略图失败显示可重试状态，不影响其他项目滚动。
- 旋转或进程重建后，分页位置、缓存命中和错误状态符合预期。

### 性能基准

固定同一设备、同一视频库和同一滚动手势，记录：

| 指标 | 目标意义 |
| --- | --- |
| P0 索引完成时间 | 首次可用速度 |
| 首屏首张/全部缩略图可见时间 | 用户感知加载速度 |
| 95/99 分位帧耗时与丢帧数 | 快速滚动流畅度 |
| 单次解码耗时、并发数和取消率 | 调度是否合理 |
| Java/Kotlin native 内存峰值 | Bitmap 与缓存压力 |
| 磁盘命中率和重复解码率 | 缓存是否有效 |
| 失败率及按 MIME/来源分布 | 回退策略覆盖度 |
| Cache Hit Before Bind Rate | View 即将绑定前缩略图是否已 ready |

基准必须同时包含本地短视频、4K 视频、损坏文件、无系统缩略图的 DocumentProvider URI，以及大量相同分辨率的连续滚动场景。

## 11. 决策结论

当前不直接把 YingLi-Player 改造成 RecyclerView + Glide，也不在没有基准数据时整体替换 Coil。Glide 对本项目最重要的价值是成熟的请求生命周期、固定尺寸、缓存一致性和方向预取思想；官方 Compose 集成目前处于 Beta，本地源码快照仍要求 `ExperimentalGlideComposeApi` opt-in，且 LazyList 预加载器并不等同于 LazyGrid 预加载器。

短期实现应围绕现有 Compose 和 `ThumbnailRepository` 边界修正缓存、请求和调度；中期把 Room 查询改成数据库级 keyset 分页；完成可重复的性能基准后，再以可替换的 loader 实现比较 Media3 `FrameExtractor`、Coil 和 Glide。评价重点应是快速 fling 时的 `Cache Hit Before Bind Rate`，而不只是单张帧的平均解码时间。这样既能吸收 Glide 的成熟经验，也不会把 UI 框架和第三方库的 Beta/Experimental API 扩散到领域层。

## 12. 参考资料

### 本地资料

- `refer/安卓视频索引算法_d9d4bd45a308.md`
- `refer/glide-master/integration/compose/src/main/java/com/bumptech/glide/integration/compose/Preload.kt`
- `refer/glide-master/integration/compose/src/main/java/com/bumptech/glide/integration/compose/GlideImage.kt`
- `refer/glide-master/integration/recyclerview/src/main/java/com/bumptech/glide/integration/recyclerview/RecyclerViewPreloader.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/load/resource/bitmap/VideoDecoder.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/load/model/stream/MediaStoreVideoThumbLoader.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/load/engine/cache/LruResourceCache.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/load/engine/cache/DiskLruCacheWrapper.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/request/SingleRequest.java`
- `refer/glide-master/library/src/main/java/com/bumptech/glide/manager/RequestTracker.java`
- `refer/glide-docs-cn-master/_posts/2017-05-10-recyclerview.md`
- `app/src/main/java/seeyuer/yingli/player/feature/library/LibraryScreen.kt`
- `app/src/main/java/seeyuer/yingli/player/app/library/RoomLibraryRepositories.kt`
- `app/src/main/java/seeyuer/yingli/player/engine/thumbnail/PriorityThumbnailRepository.kt`
- `app/src/main/java/seeyuer/yingli/player/core/model/media/MediaModels.kt`

### 官方网页

- Android 共享媒体存储：<https://developer.android.com/training/data-storage/shared/media>
- Android 媒体缩略图：<https://developer.android.com/social-and-messaging/guides/media-thumbnails>
- `ContentResolver.loadThumbnail()`：<https://developer.android.com/reference/kotlin/android/content/ContentResolver>
- 已废弃的 `MediaStore.Video.Thumbnails`：<https://developer.android.com/reference/android/provider/MediaStore.Video.Thumbnails>
- Compose Lazy 列表：<https://developer.android.com/develop/ui/compose/lists>
- PagingConfig：<https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig>
- RecyclerView：<https://developer.android.com/develop/ui/views/layout/recyclerview>
- RecyclerView LayoutManager 预取：<https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.LayoutManager>
- Glide RecyclerView 预加载：<https://bumptech.github.io/glide/int/recyclerview.html>
- Glide Compose：<https://bumptech.github.io/glide/int/compose.html>
- Glide Compose API 回滚跟踪：<https://github.com/bumptech/glide/issues/5512>
- Glide GitHub：<https://github.com/bumptech/glide>
- Media3 Inspector：<https://developer.android.com/media/media3/inspector>
- Media3 FrameExtractor 示例：<https://developer.android.com/media/media3/inspector/extract-frames>
- FrameExtractor API：<https://developer.android.com/reference/androidx/media3/inspector/frame/FrameExtractor>
- Media3 1.10 发布说明（FrameExtractor 拆分到 inspector-frame）：<https://android-developers.googleblog.com/2026/03/media3-110-is-out.html>
- Compose LazyGridState 与 CacheWindow：<https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridState>
- LazyVerticalGrid API（`cacheWindow`）：<https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyVerticalGrid.composable>
- LazyGridPrefetchStrategy（已弃用）：<https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridPrefetchStrategy>
- Compose Lazy Layout API：<https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/layout/package-summary>
