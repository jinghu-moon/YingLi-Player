# 影里产品与界面调研

> 文档状态：调研稿
> 
> 调研时间：2026-08-05
> 
> 适用项目：YingLi-Player（影里）

## 1. 调研目的

本调研建立在 [01-product-requirements-summary.md](./01-product-requirements-summary.md) 的 1-170 题回答和导航原型 F 之上。目标不是复制某个播放器，而是从成熟的本地媒体产品中提取可以迁移到影里的信息架构、交互模式和工程边界。

已确认的一级导航为：

```text
首页 / 视频 / 整理
```

处理中心通过右上角任务入口打开，设置作为全局辅助页面，播放页隐藏一级导航。

## 2. 调研结论速览

| 方向 | 同类产品做法 | 影里的建议 |
|---|---|---|
| 媒体发现 | VLC、MX Player 允许媒体库扫描和文件夹浏览；扫描范围可配置 | MediaStore 负责系统媒体，SAF 负责用户目录；扫描范围、白名单和黑名单独立配置 |
| 内容组织 | Aves 使用标签、筛选和动态相册；TagSpaces 支持 AND/OR/NOT 标签查询 | 系统属性、用户标签、收藏、播放列表、智能集合分层，避免把所有概念都叫“分类” |
| 播放上下文 | Poweramp 区分分类播放、临时队列和持久播放列表；Musicolet 强调多选和批量操作 | 播放上下文必须可见，队列和播放列表分开；从当前筛选结果直接播放并可继续 |
| 播放交互 | MX Player、Next Player、NekoVideo 使用滑动亮度/音量/进度、双击快进、字幕和轨道选择 | 手势作为可配置能力，首版默认克制；点击区域和可视化控件必须同时可用 |
| 播放会话 | Next Player、NekoVideo 使用 MediaSessionService、通知、PiP 和迷你播放器 | 播放器实例由会话/服务层持有，页面只订阅状态；播放页之外保留迷你播放器 |
| 视频处理 | LosslessCut 把片段、标签和项目保存为一等数据；Transformer 提供异步、进度、取消和错误回调 | 整理页管理媒体关系，处理中心管理可恢复任务；切片项目独立保存，不覆盖源文件 |
| 适配布局 | Android 官方推荐按窗口尺寸切换底栏、导航轨和侧栏 | 手机使用原型 F 底栏，平板/折叠屏使用侧边导航轨，保持同一导航语义 |

## 3. 产品调研

### 3.1 Aves：标签、筛选和动态集合

Aves 是 Android 图库和元数据浏览器。其重要经验有三点：

1. **集合先于固定页面**：用户可在集合页叠加路径、标签、评分、日期等过滤条件，再将结果保存为动态相册（智能集合）。动态集合保存的是规则，不是静态复制的文件列表。
2. **批量编辑入口一致**：长按进入选择模式，右上角菜单执行批量编辑标签、隐藏或删除；单项和多项使用同一套标签编辑语义。
3. **隐藏是过滤结果，不等于安全存储**：Aves 明确区分“应用内隐藏”和系统级隐藏/保险库。这个区分适合影里，避免用户误以为普通隐藏可以保护隐私。

对影里的启发：

- “智能集合”应保存可解释的条件，并允许重新打开后修改；
- 过滤条件应以可删除的条件芯片展示；
- 收藏、标签和隐藏状态应有独立视觉语义；
- 详情页需要展示媒体事实来源和应用自有属性，不能让用户误以为应用标签一定写入文件。

### 3.2 TagSpaces：标签查询模型

TagSpaces 将自由文本搜索与标签条件组合。其查询模型提供：

- `+tag`：必须包含，逻辑 AND；
- `|tag`：至少包含一个，逻辑 OR；
- `-tag`：排除，逻辑 NOT；
- 类型、大小、日期、搜索范围和匹配精度等高级条件。

影里不必直接暴露命令语法，但可以把它转译成筛选面板的三个区域：**全部满足、任一满足、排除**。高级用户可获得组合能力，普通用户仍可通过表单完成筛选。

### 3.3 VLC：格式覆盖与媒体库

VLC Android 使用独立的 LibVLC 播放内核，提供广泛容器/编解码器、硬件解码、字幕、音视频轨道、网络协议和媒体数据库。它的优点是格式覆盖广，代价是 Native 库体积、ABI 管理和 GPL/LGPL 合规复杂度较高。

对影里的启发：

- 首版 Media3 单内核符合轻量和原生优先目标；
- 格式承诺必须以真机测试集为依据；
- 播放错误需要区分编码不支持、文件损坏和 URI 权限失效；
- 未来若增加软件解码，应以失败样本证明需求后再引入，而不是预置完整 FFmpeg。

### 3.4 MX Player：文件夹浏览和播放器操作

MX Player 的本地模式强调文件夹浏览、扫描范围配置、刷新、隐藏目录处理和长按选择。播放器侧提供硬件/混合硬件加速、音量/亮度/进度手势、缩放、字幕定位和 PiP。

对影里的启发：

- 视频页需要支持“文件夹优先”和“全部视频”两种入口，且两者共享搜索、排序、视图和批量操作；
- 扫描状态必须可见，扫描范围应能缩小，避免大容量存储首次启动过慢；
- 手势设置需要允许关闭或调整步长，避免单一手势方案覆盖所有用户；
- 播放页应把字幕、轨道、画面比例和锁定归入一个清晰的更多/工具面板。

### 3.5 Poweramp：分类、排序、队列与自定义

Poweramp 将音乐库分类（艺术家、专辑、文件夹等）、临时 Queue 和持久 Playlist 明确分开。分类有自己的排序/视图状态；Queue 是当前会话的临时顺序，Playlist 是可保存、可重排的人工列表。它还提供主题、布局、皮肤、手势和输出控制等深度自定义。

对影里的启发：

- **播放上下文要可追溯**：从文件夹、筛选结果、播放列表启动时，下一项范围应在播放器中可查看；
- 播放列表保存人工顺序，智能集合保存规则，不能用一个模型混淆两者；
- 视图、排序和密度最好按页面/集合保存，而不是只有一个全局开关；
- 用户可配置按钮是后续增强点，首版先固定高频操作，避免设置页复杂化。

### 3.6 Musicolet：多队列与批量管理

Musicolet 的核心经验是本地优先、多队列、多选和批量编辑。多队列适合在不同播放场景间快速切换；长按多选后可批量加入队列、播放列表或修改标签。

对影里的启发：

- 可将“当前播放队列”和“保存的播放列表”分开；
- 多选工具栏应提供计数、全选、反选和批量整理；
- 批量操作需要显示成功/失败数量，不能静默跳过无权限或不支持写入的文件；
- 视频产品的队列不必复制音乐播放器的全部分类，但应保留来源和人工顺序。

### 3.7 Next Player：Compose + Media3 的可行架构

Next Player 是 Kotlin 原生视频播放器，使用 Jetpack Compose、Material 3、Media3/ExoPlayer、Room、Coroutines，并实现轨道选择、字幕、PiP、后台播放和手势控制。它证明影里选择的主栈可以覆盖从媒体库到播放器的完整链路。

值得借鉴的边界：

- UI 使用状态驱动，不在 Composable 内直接创建播放器；
- 播放会话通过 MediaSession/Controller 与界面连接；
- 本地媒体索引放在 Room，列表读取可分页；
- 轨道和手势属于播放域，不应散落在媒体库页面。

### 3.8 NekoVideo：隐私文件夹与本地播放器闭环

NekoVideo 是较新的 Kotlin + Compose + Media3 本地播放器，除文件夹浏览外，还包含私密文件夹、密码/生物识别、PiP、后台播放、迷你播放器、标签和本地 MP4 remux 修复。

对影里的启发：

- 私密内容如果进入产品，必须单独定义威胁模型；普通“隐藏”不能冒充加密保险库；
- 迷你播放器能让用户离开播放页后继续浏览媒体库，是三页一级导航下的重要连接件；
- 标签可以与私密范围隔离，但要避免让隐私标签泄露内容名称；
- MP4 remux 与视频压缩属于不同任务，界面需要明确区分“快速修复/封装转换”和“重新编码”。

### 3.9 LosslessCut：片段是一等实体

LosslessCut 将一个源视频的多个片段视为可保存、可重排、可标注的项目。项目文件保存源文件引用、片段起止时间、名称、标签和版本；项目支持自动保存、重新打开、分别导出、合并导出以及多种标记格式导入导出。

对影里的启发：

- 切片不应只是一次性“开始/结束”对话框，而应有独立项目和片段列表；
- 片段需要稳定 ID、起止时间、名称、标签、选中状态和导出状态；
- 源文件变化、权限失效和项目文件损坏要分别提示；
- 首版可先做单源视频、多片段、单独导出/批量导出，后续再扩展合并、章节和 EDL 互操作。

### 3.10 Media3 Transformer：异步处理基础

官方 Transformer API 将处理建模为 `EditedMediaItem`、`Composition` 和 `Transformer`：任务异步启动，通过 Listener 接收完成/失败事件，可查询进度并取消。它支持裁剪、转码、旋转和效果，输出通常为 MP4；同一 Transformer 实例不支持并发导出，部分 MP4 单素材任务可恢复。

对影里的启发：

- 处理中心应以任务状态机为核心，而不是以页面生命周期为核心；
- 任务至少需要排队、运行、暂停/取消、失败、完成和部分完成状态；
- 进度、剩余时间、输出文件和错误原因都应持久化；
- 任务执行器必须限制并发，并在资源不足时给出可解释提示；
- 无损切片和重新编码应显示不同的速度、质量和兼容性预期。

## 4. 对影里产品结构的建议

### 4.1 信息架构

```text
首页
├─ 继续观看
├─ 最近添加
├─ 常用文件夹/集合
└─ 统计摘要

视频
├─ 全部视频
├─ 文件夹
├─ 搜索与筛选
└─ 视图/排序/批量管理

整理
├─ 收藏
├─ 标签
├─ 播放列表
└─ 智能集合

右上角入口
├─ 处理中心
└─ 设置
```

一级页面保持三项，避免把任务和设置混入内容导航。手机底栏可按用户选择固定或滚动隐藏；大屏使用导航轨，语义和顺序不变。

### 4.2 统一列表工具模型

视频、收藏、标签结果和播放列表都建议共享以下交互结构：

1. 顶部标题、数量和当前条件摘要；
2. 搜索入口与筛选入口；
3. 排序菜单（字段 + 升序/降序）；
4. 视图切换（网格、缩略图、列表、瀑布流）和密度；
5. 长按进入多选，顶部显示选中数和批量操作；
6. 空状态、扫描中、权限失效、缩略图失败和无可播放项分别设计。

这样可以在 Compose 中复用列表状态和工具栏模型，减少页面之间的行为差异。

### 4.3 标签和智能集合的数据边界

建议至少区分：

| 数据层 | 示例 | 默认保存位置 |
|---|---|---|
| 媒体事实 | URI、文件名、时长、编码、修改时间 | MediaStore/扫描结果 |
| 系统属性 | 收藏、已看、播放次数、最近播放 | Room |
| 用户组织 | 标签、播放列表、智能集合 | Room |
| 文件元数据 | 容器内标题、章节、外挂字幕 | 仅在用户明确执行写入时修改文件 |

默认不把应用标签静默写入文件。写入文件可能失败、改变媒体时间戳，或导致用户在其他设备上看到意外结果；若未来提供“写入元数据”，应是显式、可撤销、逐项报告结果的操作。

### 4.4 播放页层级

播放页默认纯黑，建议分三层：

- **内容层**：视频画面、旋转/比例和字幕渲染；
- **主控层**：播放/暂停、进度、前后项、音量和锁定；
- **扩展层**：音轨、字幕、倍速、手势、画中画、详情和处理入口。

控件自动隐藏，但必须提供可访问的固定入口；手势与按钮执行同一命令，避免出现“只能靠手势完成”的隐藏功能。

### 4.5 处理中心层级

处理中心建议显示：

- 进行中任务；
- 等待队列；
- 最近完成/失败；
- 可恢复的切片项目。

任务卡片显示输入、输出、处理类型、进度、剩余时间、暂停/取消/重试和错误详情。处理中心入口显示未读失败数量或进行中数量，但不改变一级导航结构。

## 5. 推荐架构落地

结合已有技术选型文档，建议保持以下模块边界：

```text
Compose UI
  -> ViewModel / StateFlow
      -> MediaLibraryRepository -> MediaStore + SAF + Room
      -> OrganizationRepository -> Room
      -> PlaybackSession -> MediaSessionService + Media3
      -> ProcessingRepository -> Room + Transformer/处理执行器
      -> SettingsRepository -> DataStore
```

- 媒体扫描是增量同步，不阻塞首屏；
- 播放器是应用级会话，不随 Composable 重组；
- 处理任务和切片项目均持久化，可在应用重启后恢复显示；
- UI 只依赖领域模型，不直接依赖 MediaStore Cursor、Media3 原始类型或文件路径；
- 未来增加软件解码或 FFmpeg 时，只替换播放/处理适配层，不改媒体库和整理模型。

## 6. 171-240 调研前边界的处理情况

本节原本记录的授权、媒体身份、标签、搜索、播放、隐私和处理任务问题，已在需求基线的 171-240 题中完成第一轮确认。后续调研重点转向视频去重、数据迁移、私密保险库和复杂媒体处理的实现边界。

## 7. 本地参考项目深度观察

### 7.1 Next Player

参考目录：`refer/nextplayer-main`

Next Player 的 README 和源码展示了一个与影里目标高度接近的基础：Kotlin、Compose、Material 3、Media3、Room、DataStore、Coil、Hilt、导航和 Android TV。值得直接吸收的实现思想有：

- `ResponsiveNavigationSceneDecorator` 将顶级页面和文件夹、设置、连接编辑等上下文页面区分开；顶级页面在紧凑宽度使用底部导航，在中等及以上宽度使用 NavigationRail；
- 导航栏不是播放器页面的共享元素，播放页可以全屏显示，返回后恢复导航上下文；
- README 将文件夹、树状、平面视频视图、网格/列表、搜索和播放器手势视为同一媒体库的不同视图，而不是多个互相割裂的页面；
- 播放器支持按文件记忆音轨/字幕轨、字幕延迟、PiP、后台播放和外部 URI，说明播放状态不能只保存在当前 Activity；
- 版本目录集中管理 Media3、Room、DataStore、Coil 和编译器，适合影里继续沿用 Version Catalog。

影里不直接复制 Next Player 的网络存储和 FFmpeg 扩展，而是保留其导航、会话和媒体库边界。

### 7.2 Only Player

参考目录：`refer/only_player-main`

Only Player 是 Next Player 的延伸项目，增加了设置备份/恢复、ASS 字幕效果、回收站和更细的设置组织。其目录结构把 `core/model`、`core/database`、`core/datastore`、`core/media`、`core/data`、`core/domain` 与 `feature/player`、`feature/settings`、`feature/videopicker` 分开，说明功能增长后按业务域拆模块比继续堆叠单一模块更易维护。

源码中的几个具体模式值得借鉴：

- `MediumEntity` 保存 URI、路径、文件名、父目录、修改时间、大小、宽高、时长、MediaStore ID 和缩略图；`MediumStateEntity` 单独保存播放位置、轨道、倍速、字幕偏移、回收站状态和原始路径；媒体事实与用户状态分离；
- `MediaDatabase` 使用 Room 迁移，回收站和播放标记都是独立数据，而不是给媒体文件加不可解释的临时字段；
- `LocalMediaSynchronizer` 监听 MediaStore 变化、维护手动添加目录、对扫描加互斥锁，并将删除媒体与缩略图清理分开；
- `LocalMediaService` 使用 `MediaStore.createDeleteRequest` 和 `createWriteRequest` 获取系统确认，重命名、移动和删除都返回结果，不静默假设权限成功；
- `QuickSettingsDialog` 将视图模式、布局、缩放、排序、升降序和显示字段集中到一个可取消的快速设置面板，适合影里“无底栏但强筛选面板”的方向；
- `VideoItem` 将缩略图、时长、播放进度、分辨率、文件大小和路径作为可配置字段，说明列表密度和信息字段应属于页面配置；
- DataStore Serializer 使用 JSON、忽略未知字段并处理旧版本迁移，适合作为影里设置备份和版本兼容的参考。

Only Player 的 JDK、Material3 预览版本和具体依赖版本不作为影里的版本基线；这里仅参考其边界和交互。

## 8. 视频去重专题调研

### 8.1 Files by Google：重复组与“原始文件”标识

Files by Google 的重复文件清理流程是：进入清理页，打开“重复文件”，按组查看，系统给一个文件标记“Original”，用户勾选要删除的副本后移动到回收站。该产品不把扫描结果直接等同于删除决定，推荐项和用户选择是两个步骤。

对影里的建议：

- 去重结果使用“重复组”卡片，每组显示成员数量、总大小和预计可释放空间；
- 推荐保留项必须说明依据，例如较早创建、路径更稳定、分辨率更高或文件更大；
- 不能只用“原始”字样，因为同一个内容可能有多个合理原件；建议使用“建议保留”并允许修改；
- 删除动作统一进入影里回收站或系统回收机制，不直接永久删除。

### 8.2 Google Photos：相似内容分组但不改变文件

Google Photos 的“相似照片堆叠”把相似媒体分组，选择一张作为封面，并区分“只操作封面”与“包含整个堆叠”。堆叠本身不减少存储空间，只有用户明确删除才会改变文件。

对影里的建议：

- “相似组”与“删除重复项”要分开，用户可以先整理、不执行删除；
- 组封面只用于浏览，不代表系统已经决定保留哪一个；
- 批量操作必须明确作用于“选中视频”还是“整个重复组”。

### 8.3 Czkawka：分层检测、缓存和受保护路径

Czkawka 将完全重复和相似视频分成不同扫描器。完全重复可以先按大小分组，再计算文件哈希；相似视频则抽取多个帧并使用感知哈希比较。它还支持缓存哈希、部分哈希预筛选和“Reference paths”：参考目录只参与比较，但不会被自动移动或删除。

对影里的建议：

```text
L0  媒体事实预筛选：类型、大小、时长、分辨率
L1  完全重复：分组后计算部分哈希/完整哈希
L2  视觉相似：抽样帧 + 感知哈希，默认关闭或单独启动
```

- 哈希和缩略图结果持久化缓存，文件未变化时不重复计算；
- 用户可以把目录标记为“保护来源”，去重时只作为参考，不允许被自动选中删除；
- 扫描阈值需要解释“更严格/更宽松”的后果，不能只显示一个无意义的数字。

### 8.4 Video Duplicate Finder 与近重复检测

Video Duplicate Finder、VidDup 等项目进一步区分：

- 字节级相同：适合使用 SHA-256/xxHash 等文件摘要；
- 重编码、缩放或压缩后的近重复：使用多时间点帧抽样和感知哈希；
- 部分片段：使用音频指纹或滑动窗口，判断短片段是否来自长视频。

这三类结果的误报风险和计算成本完全不同。影里首期建议只做完全重复，随后增加“重编码/缩放相似”，暂不默认开启部分片段检测。部分片段检测应作为高级实验功能，因为它需要更多解码、阈值解释和人工复核。

### 8.5 视频去重界面建议

处理中心新增“视频去重”入口，流程建议为：

```text
选择来源 -> 选择检测类型 -> 扫描中 -> 重复组列表 -> 组内复核 -> 进入回收站
```

重复组卡片建议包含：

- 代表缩略图和成员数量；
- 每个成员的文件名、路径、大小、时长、分辨率、编码和修改时间；
- “建议保留”标记及依据；
- 勾选框、组内播放/对比、全组选择和撤销选择；
- 预计释放空间；
- 保护来源标识和权限失效提示。

去重任务与导出任务共享处理中心，但使用不同任务类型和资源策略。扫描阶段主要消耗 I/O、解码和 CPU，不应与视频转码同时抢占全部资源。

## 9. 基于调研的架构调整建议

在原有架构基础上增加以下领域边界：

```text
MediaLibraryRepository
  -> MediaStore + SAF + Room

OrganizationRepository
  -> tags / tag_groups / playlists / smart_collections

PlaybackSession
  -> MediaSessionService + Media3

ProcessingRepository
  -> slice_projects / processing_tasks / task_logs

DeduplicationRepository
  -> duplicate_scans / duplicate_groups / duplicate_candidates / hash_cache
```

视频去重不应直接修改 `media` 表中的 URI，也不应在 Composable 中计算哈希。建议：

- `duplicate_scan` 保存扫描范围、检测模式、阈值、状态和时间；
- `duplicate_group` 保存一组候选和预计释放空间；
- `duplicate_candidate` 保存媒体引用、检测证据、推荐状态和用户选择；
- `hash_cache` 按媒体身份、文件大小、修改时间和算法版本建立缓存键；
- 删除通过现有媒体服务和回收站流程执行，成功后再刷新媒体索引；
- 算法版本变化时废弃旧缓存，不混用不同算法产生的相似度。

## 10. 更新后的产品判断

1. 影里仍然是三项一级导航，去重属于处理中心中的媒体管理任务。
2. 参考项目证明“媒体库、播放器、设置、处理”需要分层，而不是继续增加底栏项目。
3. Only Player 的快速设置面板适合转化为影里视频页的筛选/排序工作区，但影里需要额外增加标签条件和智能集合保存。
4. 视频去重必须先做安全的重复组复核，再执行回收站删除；不能做成一个无确认的“清理按钮”。
5. 去重检测需要分阶段实现：完全相同文件优先，近重复次之，部分片段最后评估。

## 11. 配色与视觉系统调研

### 11.1 参考项目的视觉观察

参考目录中的截图和源码呈现出两种常见路线：

- Next Player 的媒体库使用 Material You 动态浅色方案，背景接近浅灰，选中状态使用青绿色；快速设置使用大圆角对话框、分段控件和芯片，把视图、排序、升降序和字段配置集中到一处；
- Next Player 播放页使用纯黑画布、白色控件和低透明度遮罩，控件尽量不与视频画面争夺注意力；
- Only Player 的 README 将 Material You、动态颜色、主题、手势和播放器设置作为统一的个性化系统，而不是每个页面单独选择颜色；
- Poweramp 等音乐播放器通常允许主题/皮肤和强调色变化，但播放器控制、队列状态和选中状态仍使用稳定的语义色；
- VLC、MX Player 等视频播放器普遍将视频画面作为第一视觉层，控件使用中性黑/白，功能色只用于当前状态、警告或强调操作。

影里不直接复制动态青绿色方案。用户偏好的黑白灰更适合作为长期稳定的产品识别，同时保留 Material 3 的语义角色和无障碍约束。

### 11.2 影里的建议色彩架构

建议将颜色分为三层：

| 层级 | 作用 | 建议 |
|---|---|---|
| 中性基底 | 页面背景、表面、分隔线、正文和次要文字 | 黑、白、灰为主，使用多个明度层次，不把所有背景都设为纯白/纯黑 |
| 品牌强调 | 当前选中、主要操作、导航激活和进度 | 选择一种低饱和主强调色，不同时使用多个高饱和主色 |
| 功能语义 | 错误/删除、警告/处理中、信息/链接、成功/完成 | 红、黄、蓝等功能色，降低饱和度并配合图标、文字和状态形状 |

建议的语义映射：

```text
primary          主要操作、当前导航、播放进度
secondary        次要筛选、标签、辅助选择
tertiary         特殊工具或少量个性化强调
error            删除、失败、权限拒绝
warning          扫描中、资源不足、编码兼容性提醒
info             媒体来源、帮助、待授权状态
success          扫描完成、导出完成、备份完成
surface          页面和面板
surfaceVariant   芯片、列表辅助区域和弱分隔
```

红、黄、蓝不能单独作为状态判断依据。每个功能色都需要同时配合图标、文字、边框或状态标记，避免色觉障碍用户无法区分。

### 11.3 主题模式

影里已经确定支持浅色、深色和跟随系统。建议建立两套静态 ColorScheme：

- **浅色主题**：暖白/冷白中选择一种基底，正文使用近黑，面板使用浅灰层次，功能色用于高优先级操作；
- **深色主题**：普通页面使用深灰层次，播放页使用纯黑；避免大面积纯白文字以外的高亮色；
- **跟随系统**：只切换主题，不改变用户保存的页面布局、筛选和按钮配置；
- **动态颜色**：可作为后续可选项，不作为影里默认品牌色。若启用，需要将动态颜色限制在非播放页，并保证功能色语义不被壁纸改变。

### 11.4 播放页颜色规则

播放页与非播放页应视为两个颜色上下文：

1. 视频画面永远优先，默认纯黑背景；
2. 控件默认白色或近白色，使用半透明黑色遮罩提升可读性；
3. 主强调色只用于进度、当前选项和少量关键按钮；
4. 错误、字幕、音轨等状态不使用大面积彩色背景覆盖画面；
5. 控件隐藏后，视频画面不应残留强烈色块；
6. 高对比模式需要提升控件边界和文本对比度，而不是单纯增加颜色饱和度。

### 11.5 无障碍与颜色验证

根据 Android 和 WCAG 建议：

- 小字号正文与背景至少达到 4.5:1；
- 大字号文本至少达到 3:1；
- 非文本图标、边界和交互组件至少达到 3:1；
- 不使用红绿组合表达唯一状态；
- 颜色 token 使用 `primary`、`onPrimary`、`errorContainer` 等语义角色，避免在组件中散落硬编码色值；
- 对浅色、深色、跟随系统和高对比模式分别进行截图和 Accessibility Scanner 检查。

### 11.6 视觉 token 初稿

以下不是最终色值，而是用于后续原型和 Compose 主题的命名边界：

```text
neutralBackground
neutralSurface
neutralSurfaceElevated
neutralOutline
neutralTextPrimary
neutralTextSecondary
accentPrimary
accentPrimaryContainer
functionalError
functionalWarning
functionalInfo
functionalSuccess
playerCanvas
playerControl
playerControlScrim
playerProgress
```

最终色值应通过 Material Theme Builder 或等价 HCT 色调系统生成，再用真实截图验证对比度和视觉层级。

## 12. 下一阶段设计重点

配色确定后，还需要继续收敛：

- 品牌主强调色是固定色还是提供几组低饱和预设；
- 红/黄/蓝功能色的具体语义和禁用状态；
- 字体、字号、字重、圆角、边框、阴影和间距；
- 首页、视频页、整理页、处理中心的页面密度；
- 标签颜色与系统功能色的冲突规则；
- 应用锁、保险库和危险操作的视觉警示级别；
- 图标库、图标线宽和选中态表现。

## 13. 参考资料

- [Android adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/building-adaptive-navigation)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Android color for mobile design](https://developer.android.com/design/ui/mobile/guides/styles/color)
- [Material 3 color roles](https://m3.material.io/styles/color/roles)
- [Android accessibility and color contrast](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Android MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Aves FAQ](https://github.com/deckerst/aves/wiki/FAQ)
- [Aves repository](https://github.com/deckerst/aves)
- [TagSpaces search](https://docs.tagspaces.org/search/)
- [VLC Android repository](https://github.com/videolan/vlc-android)
- [MX Player Google Play listing](https://play.google.com/store/apps/details?id=com.mxtech.videoplayer.ad)
- [MX Player support: local folders and scanning](https://support.mxplayer.in/support/solutions/folders/43000574901)
- [Poweramp](https://powerampapp.com/)
- [Poweramp user guides](https://forum.powerampapp.com/kb/en_us/guides/)
- [Next Player repository](https://github.com/anilbeesetti/nextplayer)
- [NekoVideo repository](https://github.com/FellipitoPV/NekoVideo)
- [NekoVideo on F-Droid](https://f-droid.org/packages/com.nkls.nekovideo/)
- [LosslessCut repository](https://github.com/mifi/lossless-cut)
- [Media3 Transformer getting started](https://developer.android.com/media/media3/transformer/getting-started)
- [Media3 Transformer transformations](https://developer.android.com/media/media3/transformer/transformations)
- [Transformer API reference](https://developer.android.com/reference/kotlin/androidx/media3/transformer/Transformer)
- [Files by Google: delete duplicate files](https://support.google.com/files/answer/9764075)
- [Google Photos: stack similar photos](https://support.google.com/photos/answer/14169846)
- [Czkawka repository](https://github.com/qarmin/czkawka)
- [Czkawka instructions: duplicate and similar videos](https://github.com/qarmin/czkawka/blob/master/instructions/Instruction.md)
- [Video Duplicate Finder](https://github.com/viniciusmi00/videoduplicatefinder)
- [VidDup](https://github.com/Pengfei-Kou/viddup)
- [VideoHash](https://github.com/akamhy/videohash)
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android BiometricPrompt](https://developer.android.com/reference/androidx/biometric/BiometricPrompt)
- [Android MediaStore trash requests](https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,java.util.Collection%3Candroid.net.Uri%3E,boolean))
- [DupFinder](https://github.com/burnersen/DupFinder)
- [Video Duperz](https://github.com/threepwood-py-labs/video-duperz)
- [Duplicates Detector OSS](https://github.com/omrikais/duplicates-detector-oss)
- [Next Player local reference](https://github.com/anilbeesetti/nextplayer)
- [Next Player responsive navigation reference](https://github.com/anilbeesetti/nextplayer/blob/main/app/src/main/java/dev/anilbeesetti/nextplayer/navigation/ResponsiveNavigationSceneDecorator.kt)
- [Only Player local reference](https://github.com/Kindness-Kismet/only_player)
- [Only Player media synchronizer reference](https://github.com/Kindness-Kismet/only_player/blob/main/core/media/src/main/java/one/only/player/core/media/sync/LocalMediaSynchronizer.kt)
- [Only Player media database reference](https://github.com/Kindness-Kismet/only_player/blob/main/core/database/src/main/java/one/only/player/core/database/MediaDatabase.kt)
- [Only Player quick settings reference](https://github.com/Kindness-Kismet/only_player/blob/main/feature/videopicker/src/main/java/one/only/player/feature/videopicker/composables/QuickSettingsDialog.kt)

## 14. 调研限制

商业产品的界面和内部实现会随版本、地区和订阅状态变化；本调研优先使用官方文档、官方支持页、开源仓库 README 和 Android 官方文档。对闭源产品的架构只作功能层面的观察，不将推断当作事实。
