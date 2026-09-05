我的重构方案不是“把 Coil 替换成 Glide”，而是先重构媒体库和缩略图的边界，再让 Coil、Glide、Media3 成为可替换实现。

> **2026-09 修订**：Compose 列表分页由手写 `LibraryQuerySession` 改为 AndroidX Paging 3。新增 `paging-runtime`、`paging-compose` 和 `paging-testing`（版本与项目 AndroidX 依赖统一）。本次范围包含 ViewModel 和 UI 的分页契约变更，不保留旧 `items + loadMore()` 兼容层。Room 使用可恢复丢弃页面的双向 Keyset 查询，当前设备调参采用 `pageSize=60`、`prefetchDistance=24`、`maxSize=150`。

## 兼容性边界

本项目处于开发期，兼容性不是方案约束。实现时应直接替换错误抽象，不增加 Adapter、Legacy API、迁移分支或新旧调用链并行运行。以下内容不是兼容层：

- 保留 MediaStore 和 SAF，是两个仍在使用的媒体来源能力，不是为旧实现保留的后门；
- `ThumbnailLoader` 是新的领域边界，用于隔离 UI 与具体解码器，不承诺兼容现有 Coil 调用方式；
- `getRefreshKey()`、`cachedIn()` 和 `LoadState.retry()` 是 Paging 3 的正确运行契约，不是旧分页 API 的兼容实现；
- 回归测试要求播放、权限、搜索、筛选等当前功能继续正常，是行为验收，不是 API 兼容承诺。

数据库采用开发期策略：schema 发生破坏性变化时默认允许删除并重建本地数据库，不为了保留旧开发数据编写迁移代码。只有在测试当前数据保留行为确有必要，或需求明确要求保留用户数据时，才增加对应的 Room migration；该 migration 不能成为旧领域 API 或旧查询实现的兼容包装。

Coil 与 Glide 可以在基准测试阶段暂时同时存在，但这只是实现选型实验。完成真实设备 benchmark 后必须删除落选实现及其专用调用路径，不形成长期双轨架构。

  目标架构：

  MediaStore / SAF
          ↓
  增量扫描器
          ↓
  Room 媒体目录
          ↓
  Paging 3 + 数据库级 Keyset 分页
          ↓
  Compose LazyGrid / LazyColumn
          ↓
  ThumbnailLoader
          ├── Memory Cache
          ├── Disk Cache
          ├── ContentResolver.loadThumbnail()
          ├── Media3 FrameExtractor
          └── Coil / Glide Benchmark

  ## 一、先建立修改前基线

  当前代码已有相关测试，先执行：

  ./gradlew testDebugUnitTest
  ./gradlew connectedDebugAndroidTest
  ./gradlew :app:assembleDebug

  同时用真实设备记录：

  - 8000 个视频的首次索引时间
  - 首屏首张缩略图显示时间
  - 快速 fling 的掉帧数
  - 缩略图解码失败率
  - 内存峰值
  - Cache Hit Before Bind Rate

  后续每个阶段都必须与这组数据比较。

  ## 二、重构媒体发现和索引

  涉及：

  - MediaDiscoveryDataSources.kt
  - DefaultMediaScanner.kt
  - MediaContainer.kt
  - MediaSourceMode

  ### 1. 统一权限和媒体来源

  全部文件权限不再走递归文件系统扫描。

  当前：

  ALL_FILES
      ↓
  AllFilesDiscoveryDataSource
      ↓
  递归 File.listFiles()

  改为：

  全部文件权限
      ↓
  MediaStoreDiscoveryDataSource
      ↓
  MediaStore.Video

  SAF_TREE 只负责用户选择的目录。

  AllFilesDiscoveryDataSource 和对应的递归扫描逻辑可以删除，不保留兼容分支。权限状态仍然保留，改变的是权限对应的数据来源。

  ### 2. SAF 扫描不再同步解析视频元数据

  当前 SafTreeDiscoveryDataSource 在发现文件时调用 MediaMetadataReader，这会对大量视频产生同步 I/O 和容器解析。

  改为：

  P0：文件名、URI、大小、修改时间、MIME
  P1：可见视频缩略图
  P2：时长、宽高、编码器等深度元数据

  这样 SAF 目录也能先建立基础索引，再后台补充元数据。

  ### 3. 扫描结果分批写入数据库

  当前 DefaultMediaScanner 收集整个来源后才调用一次 applyMutation()。

  改为每 200～500 条候选记录提交一次：

  读取一批
      ↓
  身份匹配
      ↓
  批量 upsert
      ↓
  发布扫描进度
      ↓
  继续下一批

  这样首页可以在扫描尚未完成时显示已经发现的视频，不必等待整个目录结束。

  ## 三、重构 Room 分页

  涉及：

  - LibraryAndOrganizeDaos.kt
  - RoomLibraryRepositories.kt
  - LibraryContracts.kt
  - LibraryViewModel.kt

  ### 1. 删除全量内存分页

  当前核心问题是：

  读取全部行
      ↓
  内存过滤
      ↓
  内存排序
      ↓
  drop(startIndex).take(pageSize)

  RoomLibraryRepository.execute() 应被删除，分页逻辑下沉到 DAO。

  DAO 直接执行：

  WHERE ...
    AND (
      sort_value < :cursorValue
      OR (sort_value = :cursorValue AND id < :cursorId)
    )
  ORDER BY sort_value DESC, id DESC
  LIMIT :pageSize

  不同排序字段使用明确的 DAO 查询，或者使用经过严格测试的 @RawQuery。

  ### 2. 重新设计 Cursor

  当前：

  LibraryCursor(
      sortKey: String,
      mediaId: MediaItemId
  )

  建议改成带类型的 cursor：

  data class LibraryCursor(
      val field: LibrarySortField,
      val direction: SortDirection,
      val textValue: String?,
      val longValue: Long?,
      val mediaId: MediaItemId,
  )

  避免把日期、时长、播放次数都编码成字符串后再比较。

  ### 3. 数据库过滤

  名称、路径、分辨率、时长、回收站状态应尽量在 SQL 中完成。

  标签过滤使用 EXISTS 或关联查询，不再：

  查询全部标签
      ↓
  内存 groupBy
      ↓
  内存过滤

  ### 4. 数据库索引

  为常用查询增加复合索引，重点覆盖：

  - modifiedEpochMillis
  - title
  - playbackPositionMillis
  - completed
  - missingScanCount
  - trash_entries
  - media_item_locations

  不保留旧的领域 API 兼容层。schema 破坏性变化默认重建开发期数据库；只有明确需要保留当前数据时才增加最小的 Room schema migration，不为旧实现增加包装代码。

  ## 四、重构缩略图系统

  涉及：

  - MediaModels.kt
  - MediaContracts.kt
  - PriorityThumbnailRepository.kt
  - MediaContainer.kt
  - LibraryScreen.kt

  ### 1. 用稳定的项目接口替代当前字符串缓存协议

  删除：

  val cacheKey: String

  引入：

  data class ThumbnailKey(
      val mediaItemId: MediaItemId,
      val locationId: MediaLocationId,
      val modifiedEpochMillis: Long,
      val sizeBytes: Long,
      val widthPixels: Int,
      val heightPixels: Int,
      val variant: String,
  )

  缓存 key 必须包含媒体版本，避免视频替换后继续显示旧缩略图。

  ### 2. 重新定义 ThumbnailLoader

  建议接口只暴露项目自己的类型：

  interface ThumbnailLoader {
      fun observe(request: ThumbnailRequest): Flow<ThumbnailState>
      fun requestVisible(requests: List<ThumbnailRequest>)
      fun prefetch(requests: List<ThumbnailRequest>, direction: ScrollDirection)
      fun cancelPrefetch(generation: Long)
  }

  领域接口不能暴露：

  - Glide RequestBuilder
  - Coil ImageRequest
  - Media3 FrameExtractor
  - Bitmap 生命周期细节

  ### 3. 缓存和解码分层

  推荐实现：

  ThumbnailCoordinator
          ↓
  内存 LRU
          ↓
  磁盘缩略图缓存
          ↓ miss
  ContentResolver.loadThumbnail()
          ↓ failure
  Media3 FrameExtractor
          ↓ failure
  Placeholder

  磁盘缓存保存项目统一格式的缩略图资源，使 Coil 和 Glide benchmark 使用相同的缓存协议。

  ### 4. Media3 FrameExtractor 封装

  新增基础设施实现：

  Media3FrameThumbnailSource

  要求：

  - 标记并隔离 @UnstableApi
  - 每个 extractor 只由一个 application thread 访问
  - 使用 Presentation 在提取阶段降采样
  - 使用 media3-inspector-frame
  - 不让多个并发 worker 共享同一个 extractor

  ### 5. UI 不再直接使用原始 URI

  当前：

  AsyncImage(model = item.uri.value)

  改为：

  YingLiThumbnail(
      request = thumbnailRequest,
      loader = thumbnailLoader,
  )

  这样 UI、预取和缓存使用完全相同的请求参数。

  ## 五、使用 Paging 3 重构 Compose 列表

  涉及：

  - LibraryPagingSource.kt（新增）
  - LibraryViewModel.kt
  - LibraryScreen.kt
  - app/build.gradle.kts 和 gradle/libs.versions.toml

  Paging 3 是 AndroidX 官方分页组件，与当前 Room、Compose、Media3 同属 Jetpack 技术栈。它替换手写的 `LibraryQuerySession`、generation、mutex、loaded cursor 和 `loadMore()`，不再保留重复的并发控制实现。

  ### 1. PagingSource 采用 Keyset Cursor

  `LibraryPagingSource<LibraryCursor, LibraryMedia>` 继续复用当前 SQLite Keyset SQL，不改回 OFFSET 分页：

  ```text
  refresh: key = null
  append:  key = 上一页的 nextKey
  prepend: key = 当前窗口第一页的 prevKey，用于重新加载被丢弃的前页
  ```

  每次 SQL 仍然读取 `pageSize + 1` 行，用哨兵行判断 `nextKey` 是否存在。`LoadResult.Page` 必须同时返回：

  - `data`：最多 `pageSize` 条；
  - `prevKey`：本页第一条记录的游标，Prepend 以它为严格排除边界向前查询；
  - `nextKey`：本页最后一条记录的游标，Append 以它为严格排除边界向后查询；
  - `itemsBefore/itemsAfter`：当前 Keyset 查询无法廉价计算时使用 `COUNT_UNDEFINED`，总数由独立 COUNT Flow 提供。

  `LibraryPagingSource` 必须接入 Room `InvalidationTracker`，观察 `media_items`、`media_locations`、`media_item_locations`、`media_sources`、`playback_history`、`trash_entries` 和 `media_tags`。任一相关表发生变化时调用 `invalidate()`，并移除对应 observer。不能只依赖 Compose 重组或 COUNT Flow 变化，否则数据库更新后旧 PagingSource 可能继续追加过期数据。

  ### 2. 正确实现 getRefreshKey()

  `PagingSource` 必须实现 `getRefreshKey(state)`。刷新恢复规则：

  1. 读取 `state.anchorPosition`；
  2. 用 `state.closestPageToPosition(anchorPosition)` 找到锚点页；
  3. 优先返回锚点页的 `prevKey`，使刷新从该页开始，不跳过锚点附近数据；
  4. 锚点页存在但 `prevKey` 为 `null` 时，说明它是首屏，必须返回 `null` 从查询开头刷新，不能用 `nextKey` 跳过首屏；
  5. 只有找不到锚点页时，才通过 `state.closestItemToPosition(anchorPosition)` 取得真实 `LibraryMedia`，根据当前排序字段反推出 `LibraryCursor`。

  不能用可见 index 拼接游标，也不能使用未经当前排序字段计算的临时值。排序字段、方向和媒体 ID 必须来自真实记录。

  游标边界协议必须保持一致：Append 和 Prepend 均严格排除边界记录；带 key 的 Refresh 包含该 key 对应的记录，使 `getRefreshKey()` 返回的页首游标能够恢复同一页。同排序值必须使用 `mediaId` 作为第二排序键，且两个翻页方向都使用严格比较，避免重复或遗漏。

  删除、收藏、标签、播放进度和扫描写入导致数据库失效时，Paging 会创建新的 PagingSource，并按 refresh key 恢复附近页面。若排序字段本身发生变化，允许该条记录在刷新后移动到新位置，但不能混入旧查询结果。

  对于“播放进度”这类高频写入，必须验证是否真的影响当前排序/过滤结果；不影响排序结果的写入仍会触发失效，但应由 Paging 的刷新合并机制去重，不能在 ViewModel 中重新累加旧列表。

  ### 3. ViewModel 使用 flatMapLatest + cachedIn

  查询条件应建模为 `Flow<LibraryQuery>`：

  ```kotlin
  val pagingData = queryFlow
      .flatMapLatest { query ->
          Pager(
              config = PagingConfig(
                  pageSize = 60,
                  prefetchDistance = measuredPrefetchDistance,
                  maxSize = measuredMaxSize,
                  enablePlaceholders = false,
              ),
              pagingSourceFactory = { LibraryPagingSource(repository, query) },
          ).flow
      }
      .cachedIn(viewModelScope)
  ```

  `flatMapLatest` 自动取消旧查询，替代原来的 generation 和 mutex。`cachedIn(viewModelScope)` 使旋转屏幕或多个 UI 收集者复用同一个 PagingData，避免重新创建并重复加载 PagingSource。它只保证 ViewModel 生命周期内共享，不承担进程重启后的持久化。

  ### 4. Compose 只消费 LazyPagingItems

  `LibraryScreen` 使用 `collectAsLazyPagingItems()`，不再接收会无限增长的 `List<LibraryMedia>`：

  ```text
  PagingData → LazyPagingItems → LazyVerticalGrid / LazyColumn
  ```

  使用稳定的 `itemKey`（`mediaItemId`）和固定 `contentType`。Paging 的 `maxSize` 负责丢弃远离锚点的页面，Compose 只组合可见区域和 CacheWindow 范围内的项目。

  ### 5. loadState 重新接回现有交互

  - `refresh is LoadState.Loading` 且没有项目：显示首次加载状态；
  - `refresh is LoadState.Error`：显示初始加载失败和 `retry()`；
  - `append is LoadState.Loading`：显示底部加载指示器；
  - `append is LoadState.Error`：显示底部重试操作并调用 `retry()`；
  - `append.endOfPaginationReached`：表示已经到达最后一条视频；
  - `refresh()` 用于手动重新扫描或显式刷新，`retry()` 只重试失败请求，不清空已有页面。

  ### 6. CacheWindow 与 Paging 缓存职责分离

  LazyVerticalGrid 使用当前 cacheWindow API。

  需要明确：

  CacheWindow
      = item 组合和布局缓存

  ThumbnailLoader
      = 视频帧加载和缩略图缓存

  两者分别调参、分别统计命中率。

  不直接使用 Glide 的 RecyclerViewPreloader，也不把 GlideLazyListPreloader 当作 LazyGrid 的现成方案。

  `CacheWindow` 负责 Lazy Layout 的 item 组合和布局缓存；`PagingConfig.maxSize` 负责 PagingData 页面数量；`ThumbnailLoader`/Coil 负责缩略图缓存。三者分别调参、分别测量，不能用其中一个替代另外两个。

  ### 7. maxSize 不拍脑袋确定

  官方约束是：

  ```text
  maxSize >= pageSize + 2 * prefetchDistance
  ```

  同时官方说明 `maxSize` 是 best effort，预取窗口内页面不会被丢弃。真实设备对比显示 `maxSize=120` 内存较低但反向滚动重载更频繁，`maxSize=180` 内存更高；当前取中间值 `maxSize=150`。后续仍需用固定滚动脚本记录：

  - Java/Kotlin heap 峰值；
  - 丢页后的重新加载次数；
  - 快速往返滚动的平均等待时间；
  - Cache Hit Before Bind Rate；
  - 掉帧和 ANR。

  以“内存增长趋于稳定、往返滚动无明显重复加载、命中率不下降”为验收条件，选择最小满足条件的值。不能只套用 3～4 倍 pageSize 的经验值。

  ## 六、测试安排

  ### 第一组：现有功能回归

  必须继续覆盖：

  - 权限引导
  - 全部文件权限
  - SAF 多目录授权
  - MediaStore 扫描
  - 播放
  - 搜索
  - 排序
  - 过滤
  - 列表/网格切换
  - 选择和回收站
  - 分页失败重试

  ### 第二组：PagingSource 和数据库分页测试

  新增 `androidx.paging:paging-testing` 测试依赖。分页边界优先使用 Paging 官方 `TestPager`，不通过 Compose UI 间接验证：

  - `refresh()` 返回第一页，`append()` 连续返回后续页；
  - 同排序值时 cursor 不重复、不遗漏；
  - 升序和降序正确；
  - 删除 cursor 对应记录后仍能继续翻页；
  - 搜索、标签、分辨率、时长过滤正确；
  - `pageSize + 1` 哨兵行不会暴露给 UI；
  - 最后一页 `nextKey == null`；
  - `getRefreshKey()` 使用锚点页边界恢复，不因失效跳回顶部；
  - Room `InvalidationTracker` 触发表失效后，旧 PagingSource 不再接受新的 append；
  - PagingSource 返回 `LoadResult.Error` 时可由 `retry()` 恢复；
  - 8000、20000 条数据下数据库不会被全量映射到内存。

  ### 第三组：缩略图测试

  新增：

  - 修改时间变化会产生新缓存 key
  - 文件大小变化会产生新缓存 key
  - VISIBLE 优先于 PREFETCH
  - PREFETCH 优先于 BACKGROUND
  - 取消任务不会重试
  - 方向变化会取消旧方向任务
  - Provider 失败后进入 Media3 fallback
  - Media3 extractor 单线程访问
  - Presentation 输出尺寸符合目标尺寸
  - 预取请求和实际显示请求完全一致

  ### 第四组：Compose UI 和 Paging 集成测试

  新增：

  - 扫描中即可显示已发现的视频
  - 列表可以一直滚动到最后一项
  - 接近末尾自动加载下一页
  - 快速反向滚动不会卡死
  - `maxSize` 超限后远端页面会被丢弃，回滚能够重新加载
  - `refresh`/`append` 的 Loading、Error、NotLoading 状态显示正确
  - `append` 失败后点击重试可以继续分页
  - 缩略图失败不影响列表滚动
  - CacheWindow 不影响 ThumbnailLoader 状态
  - 查询条件变化不会混入旧分页结果

  ### 第五组：性能测试

  重点指标：

  P0 索引完成时间
  首屏首张缩略图时间
  95/99 分位帧耗时
  掉帧数
  内存峰值
  重复解码率
  缩略图失败率
  Cache Hit Before Bind Rate

  其中最重要的是：

  Cache Hit Before Bind Rate

  它比单张视频帧平均解码耗时更能反映快速 fling 的实际体验。

  ## 实施顺序

  阶段 0：建立测试和性能基线
  阶段 1：重构媒体来源和分批扫描
  阶段 2：Room Keyset SQL 和 LibraryPagingSource
  阶段 3：ThumbnailKey、缓存和 ThumbnailLoader
  阶段 4：Provider + Media3 FrameExtractor
  阶段 5：Paging 3、Compose LazyPagingItems 和 CacheWindow
  阶段 6：移除旧实现，完成全量回归
  阶段 7：Coil / Glide benchmark，并删除落选实现

  相关官方资料：

  - Paging 分页和 `getRefreshKey()`：<https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data>
  - `PagingConfig.maxSize` 和 `prefetchDistance`：<https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig>
  - PagingSource、Pager 和 Compose 测试：<https://developer.android.com/topic/libraries/architecture/paging/test>

  最终决策是：

  MediaStore
      ↓
  Room Keyset Pagination
      ↓
  Compose CacheWindow
      ↓
  项目 ThumbnailLoader
      ↓
  Memory/Disk Cache
      ↓
  loadThumbnail()
      ↓
  Media3 FrameExtractor
      ↓
  Coil / Glide 可替换 benchmark（仅在评估阶段并行）

  不直接迁移到 Glide，不保留旧的全量分页和独立缩略图请求链；benchmark 完成后删除落选的缩略图实现。这样可以从根因上解决当前的索引、分页、缩略图重复解码和快速滚动卡顿问题，同时通过回归测试保证现有播放、权限、搜索、筛选和媒体管理行为正常，而不是为旧实现维持兼容层。
