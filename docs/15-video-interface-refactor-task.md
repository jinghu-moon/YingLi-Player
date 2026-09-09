# 视频界面 UI 重构任务清单

## 1. 文档目的

本任务以 [`prototypes/views/02-videos-demo.html`](../prototypes/views/02-videos-demo.html) 为当前视频界面的视觉和交互事实源，重构 Android Compose 视频库页面。

本次重构不是局部换肤，而是重新建立页面 Chrome、目录导航、搜索、快捷设置、查询状态和分页状态之间的边界。

项目处于开发阶段，尚未正式发布：

- 不考虑旧视频页面 API、旧状态结构和旧 UI 层的向后兼容；
- 允许删除错误抽象、修改公共接口、替换状态模型和重做调用链；
- 不为兼容旧实现保留 Adapter、Legacy API、Migration Logic 或临时分支；
- 不允许无意破坏权限、扫描、播放、搜索、排序、筛选、选择、删除和回收站；
- 必须执行“修改前基线 → 实施重构 → 新行为测试 → 既有行为回归 → 性能对比”。

## 2. 范围和冻结项

### 2.1 必须重构

- [ ] 顶部栏及标题/摘要；
- [ ] 搜索入口、搜索面板和搜索结果；
- [ ] 文件夹浏览、进入和返回；
- [ ] 面包屑导航；
- [ ] 文件夹行三层信息布局；
- [ ] “更多”菜单及操作入口；
- [ ] 快捷设置 Bottom Sheet；
- [ ] 浏览范围、排序和显示字段模型；
- [ ] Paging 查询快照和分页状态连接；
- [ ] 空、加载、错误、重试和末页状态；
- [ ] 选择模式和批量操作状态；
- [ ] 缩略图请求边界和预取接线。

### 2.2 网格/列表媒体 UI 冻结

网格模式和列表模式的视频媒体卡片 UI 不在本次重构范围内。除非另有独立任务批准，不得修改：

- [ ] 网格缩略图 `16:9`；
- [ ] 列表缩略图约 `140dp × 90dp`；
- [ ] 列表无分隔线，使用统一行间距；
- [ ] 列表右侧信息与缩略图上下边缘对齐；
- [ ] 文件名和路径的两行省略规则；
- [ ] 时长位于缩略图右下角；
- [ ] 网格/列表标题、缩略图、时长和进度条结构；
- [ ] 缩略图失败占位和不阻塞滚动的行为。

允许修改事件接线、字段数据来源和 Paging 输入，但不得改变上述视觉结构。

### 2.3 明确排除

- [ ] 不实现树形目录模式；
- [ ] 不增加顶部排列方式按钮；
- [ ] 不恢复旧的 `位置`、`日期`、扩展名等排序入口；
- [ ] 网格/列表切换只存在于快捷设置；
- [ ] 不把全库媒体复制成 ViewModel 长期 `List`；
- [ ] 不恢复手写 generation、mutex、cursor session 或兼容层；
- [ ] 不在 UI 层实现数据库聚合和分页。

## 3. Demo 事实源

### 3.1 页面结构

```text
视频页面
├── 固定顶部栏
│   ├── 返回按钮（仅子目录）
│   ├── 标题
│   ├── 当前目录摘要（仅子目录）
│   ├── 搜索按钮
│   └── 更多按钮
├── 面包屑栏（仅文件夹模式、非根目录、未搜索）
├── 搜索面板（展开时位于顶部栏下方）
├── 可滚动内容区
│   ├── 文件夹区块（文件夹模式）
│   ├── 视频区块
│   └── Paging 尾部状态
├── 选择模式顶部栏
└── 选择模式底部批量操作栏
```

### 3.2 顶部栏

根目录：

- [ ] 标题显示“视频”；
- [ ] 不显示首页已经提供的全库视频数和目录数；
- [ ] 不显示空摘要行；
- [ ] 隐藏面包屑栏；
- [ ] 右侧只有搜索和更多两个图标按钮。

子目录：

- [ ] 显示返回按钮；
- [ ] 标题显示当前文件夹名，最多一行尾部省略；
- [ ] 副标题显示当前文件夹自身摘要；
- [ ] 保留搜索和更多按钮；
- [ ] 当前目录不在面包屑中重复显示。

### 3.3 面包屑

仅当以下条件同时成立时显示：

```text
browseMode == FOLDER
&& currentPath.isNotEmpty()
&& keyword.isBlank()
```

- [ ] 根目录使用媒体库/设备图标作为固定入口；
- [ ] 祖先目录可点击并直接跳转；
- [ ] 当前目录只在顶部标题显示；
- [ ] 深层路径折叠中间目录为 `…` 菜单；
- [ ] `>` 出现在省略入口前后以及各路径段之间；
- [ ] 路径段内容自适应宽度，仅设置最大宽度；
- [ ] 长名称在自身槽位内尾部省略，不遮挡相邻元素；
- [ ] 路径轨道支持横向滚动，不强制滚到最右侧；
- [ ] 根目录、全部视频和搜索状态隐藏面包屑。

### 3.4 文件夹行

`folder-copy` 固定为：

```text
文件夹名
文件夹路径
其他信息
```

- [ ] 图标背景约 `48 × 48dp`；
- [ ] filled 文件夹图标约 `25 × 25dp`；
- [ ] 名称、路径和其他信息分别单行尾部省略；
- [ ] 路径和其他信息受字段设置控制；
- [ ] 点击进入目录；选择模式下禁用或隐藏文件夹区块；
- [ ] 搜索结果复用相同三层结构。

### 3.5 更多菜单

- [ ] 添加媒体目录；
- [ ] 重新扫描；
- [ ] 进入选择模式；
- [ ] 显示设置；
- [ ] 设置；
- [ ] 搜索、菜单、Bottom Sheet 和遮罩互斥打开；
- [ ] 点击遮罩、返回键或关闭按钮按统一规则关闭当前层。

### 3.6 快捷设置

打开时复制当前配置到草稿，编辑期间不触发查询。

- [ ] 浏览范围使用分段控制器：文件夹 / 全部视频；
- [ ] 排列方式使用分段控制器：列表 / 网格；
- [ ] 排序方向使用分段控制器：升序 / 降序；
- [ ] 排序字段分为“内容属性”和“使用记录”；
- [ ] 字段显示默认折叠，展开后显示文件夹/视频两组；
- [ ] 字段胶囊使用空心/实心圆形选择标记；
- [ ] 全部视频模式下文件夹字段组置灰并禁止操作；
- [ ] 确定一次性应用草稿；
- [ ] 取消丢弃草稿。

字段至少包括：

```text
文件夹：视频总数、文件夹大小、总时长、修改时间、路径
视频：路径、文件大小、分辨率、修改时间、播放进度
```

## 4. 目标状态模型

```text
LibraryBrowseMode    文件夹 / 全部视频
LibraryViewMode      列表 / 网格
SortSpec             排序字段和方向
FilterExpression     筛选条件
LibraryDisplayFields 文件夹字段和视频字段
LibraryPath          当前目录及祖先目录
```

建议 UI 状态：

```kotlin
data class LibraryUiState(
    val browseMode: LibraryBrowseMode = LibraryBrowseMode.FOLDER,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val currentPath: List<LibraryFolder> = emptyList(),
    val keyword: String = "",
    val sort: SortSpec = SortSpec(),
    val filter: FilterExpression = FilterExpression(),
    val displayFields: LibraryDisplayFields = LibraryDisplayFields(),
    val selectedIds: Set<MediaItemId> = emptySet(),
    val searchOpen: Boolean = false,
    val quickSettingsOpen: Boolean = false,
)
```

所有媒体结果条件组成不可变查询快照：

```text
浏览范围 + 当前目录 + 搜索词 + 排序 + 筛选
                         ↓
                   flatMapLatest
                         ↓
                    Pager.flow
                         ↓
                     cachedIn(scope)
```

禁止重新引入全量 loaded list、手写 generation、mutex 或旧兼容 API。

## 5. Android 实施任务

### 阶段 A：基线和审计

- [ ] 审计 `LibraryScreen`、`LibraryViewModel`、`LibraryPagingSource`、Repository 和导航调用方；
- [ ] 记录顶部栏、搜索、目录进入/返回、排序、筛选、选择和批量操作基线；
- [ ] 执行现有单元测试、Paging 测试、Compose 测试、Lint 和 Debug 构建；
- [ ] 在真实设备记录首屏时间、首张缩略图时间、正/反向快速滚动、内存峰值；
- [ ] 在约 8000 个视频库记录连续滚动基线；
- [ ] 对网格/列表媒体卡片建立视觉快照并冻结。

### 阶段 B：页面 Chrome

- [ ] 拆出 `LibraryTopBar`；
- [ ] 根目录只显示“视频”，不重复首页统计；
- [ ] 子目录显示返回、当前目录名和摘要；
- [ ] 删除顶部排列切换按钮；
- [ ] 顶部只保留搜索和更多入口；
- [ ] 将搜索输入移到固定顶部搜索面板；
- [ ] 拆出 `MoreMenu` 并接入五个入口；
- [ ] 统一弹层互斥和返回行为；
- [ ] 验证内容滚动不带走顶部栏和搜索面板。

### 阶段 C：目录导航和文件夹

- [ ] 建立不可变 `currentPath` 状态；
- [ ] 拆出 `BreadcrumbBar`；
- [ ] 实现根目录隐藏、子目录祖先路径显示；
- [ ] 实现深层折叠菜单、`>` 分隔和横向滚动；
- [ ] 实现祖先目录直接跳转；
- [ ] 文件夹行改为名称/路径/其他信息三层；
- [ ] 文件夹字段由 `LibraryDisplayFields.folderFields` 控制；
- [ ] 使用 48dp 背景和 25dp filled 图标；
- [ ] 选择模式下禁用文件夹点击；
- [ ] 验证长名称、深层路径、空路径和权限撤销。

### 阶段 D：查询和 Paging

- [ ] 将浏览范围、目录、搜索词、排序和筛选组成 `LibraryQuery`；
- [ ] 使用数据库级查询，不在 ViewModel 递归构造全库列表；
- [ ] 使用稳定排序字段和媒体 ID 作为第二排序键；
- [ ] 正确实现 `getRefreshKey()`；
- [ ] 使用 Paging 管理 refresh、append、prepend 和末页；
- [ ] 使用 `flatMapLatest` 取消旧查询；
- [ ] 使用 `cachedIn(viewModelScope)`；
- [ ] 实测 `pageSize`、`prefetchDistance`、`maxSize` 和 `CacheWindow`；
- [ ] 删除旧 cursor session、generation、mutex 和长期 `items`；
- [ ] 验证末尾停止请求、反向滚动不跳顶、不停顿。

### 阶段 E：搜索

- [ ] 搜索按钮展开固定搜索面板；
- [ ] 使用 `debounce + flatMapLatest`；
- [ ] 仅搜索本地目录、视频、合集和标签；
- [ ] 明确区分文件夹结果和视频结果；
- [ ] 文件夹结果进入目录，视频结果播放；
- [ ] 搜索文件夹复用三层信息；
- [ ] 搜索视频复用冻结的网格/列表媒体 UI；
- [ ] 清除搜索后恢复原目录和列表状态；
- [ ] 验证无结果、特殊字符和快速输入。

### 阶段 F：快捷设置

- [ ] 建立 `LibraryDisplayDraft`；
- [ ] 实现浏览范围和排列方式分段控制器；
- [ ] 实现排序分组和方向控制；
- [ ] 实现字段显示默认折叠/展开；
- [ ] 实现字段胶囊空心/实心选择标记；
- [ ] 实现全部视频模式下文件夹字段禁用；
- [ ] 确定一次性应用，取消不改变当前状态；
- [ ] 统一驱动文件夹行和视频元数据字段；
- [ ] 验证设置变更创建新查询并取消旧 Pager。

### 阶段 G：选择和批量操作

- [ ] 选择状态只保存 `Set<MediaItemId>`；
- [ ] 顶部显示数量、退出和全选；
- [ ] 底部固定移动、加入合集、删除/回收站；
- [ ] 普通点击播放，选择模式点击切换选中；
- [ ] 长按进入选择模式并选中当前项；
- [ ] 明确定义全选范围；
- [ ] 批量操作根据 ID 读取最新实体；
- [ ] Paging 刷新/删除后清理失效 ID；
- [ ] 验证跨页选择、切换目录、刷新和回收站。

### 阶段 H：缩略图边界

- [ ] 网格/列表继续使用统一 `ThumbnailLoader`；
- [ ] 显示和预取请求使用相同版本、尺寸和变体；
- [ ] 网格请求匹配 `16:9` 容器；
- [ ] 列表请求匹配 `140dp × 90dp` 容器；
- [ ] 缓存未命中使用 `loadThumbnail()`；
- [ ] 失败回退 Media3 `FrameExtractor`；
- [ ] `FrameExtractor` 的 `@UnstableApi` 和线程约束封装在基础设施层；
- [ ] UI/领域接口不暴露 Glide、Coil 或 Media3 类型；
- [ ] 缩略图失败不得影响 Paging 和滚动。

### 阶段 I：清理和收口

- [ ] 删除旧顶部搜索、重复布局和无效入口；
- [ ] 删除树形目录相关状态和递归渲染（如仍存在）；
- [ ] 删除顶部布局切换按钮的无效事件；
- [ ] 删除旧字段集合、旧排序字段和兼容分支；
- [ ] 删除完整媒体对象选择状态；
- [ ] 排查全量媒体列表重新进入 ViewModel；
- [ ] 更新接口、测试和文档；
- [ ] 完成 Lint、单元测试、Compose 测试和 Debug 构建。

## 6. 测试清单

### 6.1 Paging/Repository

- [ ] 文件夹模式只查询当前目录直接子目录和视频；
- [ ] 全部视频模式跨目录稳定排序；
- [ ] 同排序值无重复、无遗漏；
- [ ] 升序/降序正确；
- [ ] `getRefreshKey()` 能恢复锚点附近；
- [ ] refresh、append、prepend、末页和 append retry 正确；
- [ ] 查询变化不会混入旧 PagingData；
- [ ] `cachedIn` 不重复创建查询；
- [ ] 搜索结果类型和点击行为正确；
- [ ] 非法字段配置回退默认值；
- [ ] 选择只保存 ID，批量操作读取最新数据。

### 6.2 Compose 集成

- [ ] 根目录只显示“视频”，无重复统计和空摘要；
- [ ] 根目录隐藏面包屑；
- [ ] 子目录显示返回和自身摘要；
- [ ] 面包屑不重复当前目录；
- [ ] 深层 `…` 前后都有 `>`；
- [ ] 长路径段尾部省略且不遮挡；
- [ ] 面包屑横向滚动可用；
- [ ] 文件夹行显示名称/路径/其他信息；
- [ ] 文件夹图标尺寸和 filled 图标正确；
- [ ] 搜索、更多菜单和 Bottom Sheet 固定且互斥；
- [ ] 浏览范围和排列方式为分段控制器；
- [ ] 字段显示默认折叠，展开/收起正确；
- [ ] 字段胶囊选中/未选中正确；
- [ ] 全部视频模式下文件夹字段置灰；
- [ ] 确定/取消草稿正确；
- [ ] 网格/列表媒体卡片视觉快照未变化；
- [ ] 选择模式、全选和批量操作正确；
- [ ] loading、error、retry、空状态和末页正确；
- [ ] 缩略图失败不阻塞滚动。

### 6.3 真机性能

在约 8000 个本地视频设备上对比基线：

- [ ] 首次进入视频页可见内容时间；
- [ ] 首张缩略图显示时间；
- [ ] 快速向下/向上滚动掉帧和卡顿；
- [ ] 反向滚动是否跳顶或错误定位；
- [ ] 丢页后的重新加载次数；
- [ ] Java/Kotlin heap 峰值和一分钟稳定值；
- [ ] 缩略图缓存命中率；
- [ ] `Cache Hit Before Bind Rate`；
- [ ] 重复解码率和失败率。

## 7. 验收标准

只有同时满足以下条件才算完成：

1. 顶部栏、面包屑、搜索、更多菜单和快捷设置与 Demo 一致；
2. 根目录不重复首页统计，子目录显示自身摘要；
3. 文件夹行严格使用名称、路径、其他信息三层结构；
4. 网格和列表视频卡片 UI 未被本任务改变；
5. 不存在树形目录、顶部布局切换按钮或旧兼容分支；
6. 查询由数据库和 Paging 管理，不把全库视频放入 ViewModel 长期内存；
7. `getRefreshKey()`、append 重试和 `cachedIn` 有测试覆盖；
8. 搜索、排序、筛选、目录导航、播放、选择、批量操作和回收站回归通过；
9. 快速正/反向滚动没有卡死、跳顶或长时间无内容；
10. 真机性能相较基线没有不可接受退化；
11. 旧实现、重复代码、无效状态和临时兼容层已删除；
12. Lint、单元测试、集成测试和构建通过。

## 8. 相关资料

- [`02-videos-demo.html`](../prototypes/views/02-videos-demo.html)
- [`10-glide-compose-thumbnail-prefetch-research.md`](10-glide-compose-thumbnail-prefetch-research.md)
- [`11-compose-thumbnail-prefetch-plan.md`](11-compose-thumbnail-prefetch-plan.md)
- [`13-yingli-player-architecture.md`](13-yingli-player-architecture.md)
- [AndroidX Paging 3](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)
- [Paging 测试](https://developer.android.com/topic/libraries/architecture/paging/test)
- [Compose LazyVerticalGrid](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/package-summary)
- [ContentResolver.loadThumbnail](https://developer.android.com/reference/kotlin/android/content/ContentResolver#loadThumbnail(android.net.Uri,android.util.Size,android.os.CancellationSignal))
- [Media3 FrameExtractor](https://developer.android.com/reference/androidx/media3/inspector/frame/FrameExtractor)

## 9. 本次实现记录

- 已完成视频页专用固定顶部栏、搜索面板、更多菜单、子目录返回和横向面包屑。
- 已完成 `LibraryBrowseMode`、`LibraryPathSegment`、文件夹/视频字段模型，以及 Paging 查询快照接线。
- 已完成 Room 根级目录聚合和按媒体来源的目录视频查询；根目录不再把全库视频加载进内容列表。
- 已完成快捷设置草稿式 Bottom Sheet，浏览范围、排列方式、排序字段/方向和字段显示一次性应用。
- 已将选择状态改为 `Set<MediaItemId>`，批量操作时按 ID 重新读取最新实体。
- 已删除 Scaffold 中视频页重复顶部操作入口和内容区旧搜索框；网格/列表媒体卡片视觉代码保持冻结。
- 验证通过：`compileDebugKotlin`、`compileDebugAndroidTestKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleDebug`。
- 真机仪器测试已构建，但设备安装测试 APK 时返回 `INSTALL_FAILED_USER_RESTRICTED`，需在设备上允许 USB 安装后重试。
