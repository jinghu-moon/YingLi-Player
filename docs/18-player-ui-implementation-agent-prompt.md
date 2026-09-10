# 播放界面完整实现 Agent 提示词

你是本项目的主实现 Agent。请直接在当前工作区完成播放界面的架构重构、功能实现、UI 复原、测试与验证，不要只给出分析、建议、伪代码或实施计划。

## 一、唯一目标

以 [`docs/16-player-ui-ux-interaction-implementation-spec.md`](./16-player-ui-ux-interaction-implementation-spec.md) 为最高优先级实现规范，以项目当前代码和能力为辅助事实，以 [`prototypes/views/04-player-demo.html`](../prototypes/views/04-player-demo.html) 为视觉与交互参考，完整实现影里播放器的：

1. 常规播放页横屏形态；
2. 常规播放页竖屏形态；
3. 独立一级页面 YLShorts；
4. Demo 中已经承诺且规范要求保留的控件、弹层、状态、手势、动画与真实功能；
5. 对应的领域模型、状态机、Controller/Gateway/Repository、持久化和测试。

尽可能复原 `04-player-demo.html` 的播放器内容区域，包括布局层级、控件位置、尺寸、颜色、字体、图标、遮罩、动效、面板和交互反馈；不要把 Demo 网站外壳复制进 Android App。

本任务的完成标准是“实现和验证均完成”，不是“代码已经修改”。

## 二、事实源优先级

发生冲突时严格按以下顺序裁决：

```text
1. docs/16-player-ui-ux-interaction-implementation-spec.md
2. 根目录 AGENTS.md 与当前可运行的领域/安全/播放架构契约
3. prototypes/views/04-player-demo.html 的 UI、交互和动效
4. 当前 PlayerScreen/PlayerViewModel 的历史实现
```

具体规则：

- 必须完整阅读规范，不得只根据章节标题或本提示词摘要实现。
- 规范与 Demo 冲突时，以规范为准。
- 当前代码与规范冲突时，重构当前代码；不要降低规范来迁就旧实现。
- Demo 的固定文件、浏览器 API、假设备外壳、模拟状态栏、内存数组和 Toast 占位不是正式实现依据。
- Demo 表现了某项交互而规范没有逐像素限定时，按 Demo 还原。
- Android 平台限制使行为无法一比一复制时，保留用户意图和视觉结果，并使用原生 Android 能力实现。
- 不得自行修改 `16-player-ui-ux-interaction-implementation-spec.md` 来回避实现；确有不可实现的矛盾时，先用代码与平台证据说明。

## 三、开始前必须研读

先完整阅读以下内容，再修改核心代码：

```text
AGENTS.md
docs/16-player-ui-ux-interaction-implementation-spec.md
prototypes/views/04-player-demo.html
prototypes/views/04-player-Demo深度分析提示词.md
prototypes/views/影里播放页Demo详解文档.md
prototypes/design/02-color-system-components.html
prototypes/layout-typography-system.html
```

随后审计至少以下代码及其所有调用方和测试：

```text
app/src/main/java/seeyuer/yingli/player/feature/player/
app/src/main/java/seeyuer/yingli/player/domain/playback/
app/src/main/java/seeyuer/yingli/player/engine/media3/
app/src/main/java/seeyuer/yingli/player/app/MainActivity.kt
app/src/main/java/seeyuer/yingli/player/app/YingLiPlaybackService.kt
app/src/main/java/seeyuer/yingli/player/app/MediaContainer.kt
app/src/main/java/seeyuer/yingli/player/data/preferences/
app/src/main/java/seeyuer/yingli/player/core/designsystem/
app/src/main/java/seeyuer/yingli/player/feature/shell/
app/src/test/java/seeyuer/yingli/player/domain/playback/
app/src/test/java/seeyuer/yingli/player/feature/player/
app/src/androidTest/java/seeyuer/yingli/player/feature/player/
```

使用 `rg` 查清路由、播放器所有权、队列、媒体仓储、收藏、文件删除、分享、权限、Vault、截图、PiP、方向、系统栏和偏好链路。不要仅阅读入口文件后猜测架构。

## 四、当前工程事实仅作起点，执行时必须重新验证

当前已知情况：

- Android 31+，compile/target SDK 36，Kotlin 2.4，JVM 21，Jetpack Compose、Material 3、Media3 1.10.1。
- ExoPlayer/Media3 Player 由 `YingLiPlaybackService` 持有，Activity 和 Composable 不应创建第二个 Player。
- `PlaybackController`/`Media3PlaybackController` 已承担基础播放命令，`AdvancedPlaybackController` 已包含部分速度、比例和轨道能力。
- 已有 `ScreenshotGateway`、`PictureInPictureGateway`、播放器偏好、轨道偏好、播放状态 reducer 和安全播放契约。
- 当前 `PlayerScreen.kt` 仍较集中，设置面板使用局部状态；`PlayerUiState.screenshotResult` 是持久文本式结果，这些需要按规范重构。
- 当前常规播放队列能力和 ViewModel 命令不完整，需要建立真实队列导航和四态播放顺序。
- 当前尚无符合规范的独立 `feature/shorts` 完整页面。
- 设计系统已有 Player Token；图标已迁移到本地 `compose-icons` Tabler 包，正式代码必须经 `YingLiIcon` 语义层使用 Tabler Outline/Filled 图标。
- 已有单元测试、Compose 仪器测试、Lint、Release/R8 配置，且 `allWarningsAsErrors=true`、Lint warnings as errors。

这些事实可能已经被其他未提交改动更新。必须以执行时的工作区为准，并保留用户已有修改。不要回滚、覆盖或格式化无关文件。

## 五、工程决策约束

项目尚未发布，不考虑向后兼容。允许并鼓励破坏性重构，但必须从根因解决问题：

- 可以重构 `PlayerUiState`、事件、Controller 接口、Repository、数据库结构、导航和组件边界。
- 新设计验证通过后删除旧实现、重复状态、临时 Adapter、兼容层、无效分支和占位接口。
- 不以最小 Diff 为目标；正确性、根因、性能、架构质量优先。
- 遵循单向数据流。Media3/系统回调是事实源，Composable 只渲染状态和发送事件。
- 不在 Composable 中访问 Room、MediaStore、SAF、文件系统或 ExoPlayer。
- 不在 UI 中复制播放器位置、播放状态、PiP、方向、全屏等事实状态。
- Dispatcher、Clock、随机源和计时器必须可注入、可测试，不直接散落使用 `Dispatchers.IO` 或系统时间。
- 不为了臆测性能增加缓存、并发或抽象；先测量再优化。
- 未经用户要求不要创建分支、提交或推送 Git。

## 六、必须实现的页面边界

### 6.1 常规 PlayerRoute

横屏和竖屏是同一个 `PlayerRoute` 的响应式布局变体：

- 共享同一个 Media3 会话、当前媒体、队列、位置、播放状态、AB、截图、速度、比例、轨道、面板与偏好。
- 旋转过程中不得重新创建第二个 Player，不得丢失进度、队列、AB 或锁定状态。
- 横屏使用 Demo 的顶部栏、中央控制区、底部进度及快捷控制布局。
- 竖屏使用 Demo 的顶部栏、下移中央播放键和底部悬浮控制条，不显示横屏底栏。
- 全屏、方向、WindowInsets 和系统栏以 Android 系统状态为准。

### 6.2 独立 YLShorts

YLShorts 必须是与首页、视频页同级的一级导航页面：

- 建立独立 `ShortsRoute`、`ShortsViewModel`、`ShortsUiState`、事件和手势状态机。
- 不得把 Shorts 写成 `PlayerScreen(mode = SHORTS)` 或 orientation 第三态。
- 可以复用播放引擎、Media3 会话、截图、PiP、视频信息和底层仓储，但不能复用常规播放器 Chrome、自动隐藏策略和控件布局配置。
- 添加真实一级导航入口，并验证进入、返回、恢复和进程重建行为。

## 七、常规播放器必须完成

逐项落实规范第 3～9、11～12、18 章，至少包括：

- Demo 色彩、Player Token、字体、圆角、遮罩、顶部/底部渐变和响应式尺寸。
- Tabler Outline/Filled 语义图标，不得退回 Material 图标或手绘 SVG。
- 返回、播放/暂停、上一项/下一项、快退/快进 10 秒。
- 单击空白画面只切换控制层，不把它和播放/暂停混为一个动作。
- 可拖动 Seek、预览位置与真实位置分离、时长和缓冲/错误/结束状态。
- 顺序、随机、列表循环、单曲重复四态；队列为空、首尾、缺失文件行为明确。
- 播放速度、原始/裁剪/拉伸比例、真实音轨和字幕选择。
- 横屏右上、左下、右下和竖屏底栏快捷槽。
- 播放列表 Drawer/Sheet、当前项、缩略图、点击自动切换并关闭面板。
- 视频信息 Dialog，字段来自真实媒体库与 Media3 Format，不得写死。
- PiP、全屏、旋转、锁定、系统返回优先级和 Vault 安全限制。
- Loading、Preparing、Buffering、Ready、Playing、Paused、Ended、Failed 的完整可观察 UI。
- 控件自动隐藏、交互续期、面板互斥和统一 transient message。

所有按钮都必须有真实命令、成功后的事实状态、禁用条件、错误路径和无障碍语义。不得点击后只改图标或弹“已完成”。

## 八、截图必须完成

严格实现 Demo 的截图交互，但正式版本必须截取真实视频帧：

1. 截图入口打开悬浮胶囊：上一帧、截图当前帧、下一帧、取消。
2. Ready、Paused、Playing 都允许截图；不得以“请先播放”为统一失败原因。
3. 前后逐帧先暂停，再依据真实帧率移动；帧率未知按规范回退。
4. 截图通过 `ScreenshotGateway` 获取真实画面并写入 MediaStore。
5. 成功预览从画面缩小飞入左上角，约 420ms，视觉约 116dp、16:10、白边和阴影。
6. 预览显示 3 秒，底部 4dp 倒计时条从满到空。
7. 点击预览暂停倒计时，删除/关闭动作以约 200ms 动画出现。
8. 明确区分“关闭预览”和“删除已保存文件”。若按钮文案为删除截图，必须真实删除 MediaStore 文件并处理权限/失败。
9. 截图结果使用临时状态或一次性 UI event，超时、删除、媒体切换后清理。
10. Vault/安全内容严格遵守 `FLAG_SECURE` 和截图许可。

## 九、AB 循环必须完成

- 使用截图工具一致的悬浮胶囊，包含设置 A、设置 B、清除、关闭。
- 先 A 后 B；B 必须晚于 A，至少间隔一帧。
- A/B 标记显示在完整时长坐标系的进度条上，并清晰显示有效区间。
- 播放位置只能在 A/B 间移动，到 B 后由播放器/控制器层跳回 A。
- 所有 Seek、快退快进、逐帧、进度拖动都经过统一 `AbLoopLimiter`。
- 标记支持触摸拖动、Pointer Capture 等价行为、外接键盘左右一帧和 TalkBack 自定义动作。
- 媒体切换清除 AB；AB 为当前会话临时状态，不写全局偏好。

## 十、控件布局编辑器必须完成

按 Demo 实现“图标 + 简短文本 + 卡片右下角加/减号”的编辑方式：

- 四个区域：横屏右上、横屏左下、横屏右下、竖屏底栏。
- 显示已选数量/上限、拖动手柄、固定控件锁、可添加控件库和恢复推荐。
- 同一区域内拖动排序；跨区拒绝；超限禁用；重复和坏数据过滤。
- 点击加减或排序后立即同步真实播放页，不只是更新编辑器预览。
- 核心播放、进度和固定全屏不可移除。
- 通过 DataStore 持久化；进程重启、旋转和坏数据恢复均需测试。
- TalkBack 提供上移/下移动作，所有视觉小按钮具有至少 48dp 触控区。

Demo 控件库中的音轨、字幕、锁定、后台播放、睡眠定时等条目只有在具备真实功能时才能显示；不能保留 Demo Toast 占位。

## 十一、YLShorts 必须完成

严格落实规范第 10、19 章：

- 全屏沉浸式竖屏视频、顶部返回和计数、右侧收藏/屏蔽/更多操作栏、底部标题/描述/标签/时间和细进度条。
- Shorts 默认 `cover`，独立循环“裁剪 cover -> 原始 contain -> 拉伸 fill”，不污染常规播放器比例。
- 点击视频主体播放/暂停；拖动后抑制一次点击。
- 上滑下一条、下滑上一条；方向锁定、6/10/64dp 阈值、未提交回弹。
- 左右滑动快进/快退 5 秒，约 42dp 提交阈值；不得与上下切换同时触发。
- 邻项预加载、双层视频跟手移动、320ms 提交/回弹、切换防重入和失败恢复。
- 视频主体约 360ms 长按临时 2x，移动超过约 8dp 取消；松手恢复。
- 更多面板约 320ms 长按临时 2x，单击锁定 1x/2x；两套入口不能互相重复触发。
- 首次进入显示一次约 2800ms 手势提示，并提供无障碍等价动作。
- 自动下一条、循环当前，循环当前优先级更高。
- 收藏和黑名单持久化；管理面板按文件名显示，末尾关闭图标移除，主界面立即同步。
- 加入黑名单后从候选队列移除并切换；空队列、当前项和文件失效均有确定行为。
- 更多 Bottom Sheet：自动下一条、倍速、分享、收藏列表、循环当前、PiP、截图、比例、信息、黑名单、删除。
- 分享使用 Android `ACTION_SEND`/URI 授权；删除使用 MediaStore/SAF、二次确认和真实结果。
- PiP、截图和视频信息复用底层能力，但使用 Shorts 独立页面状态。

禁止将 Demo 的 `shortTracks`、初始黑名单 `2.mp4` 或 `Set` 复制到生产代码。短视频候选必须来自项目真实媒体数据源和明确的筛选规则。

## 十二、视觉复原要求

必须根据规范中的精确参数和 Demo CSS 建立集中 Token，而不是在 Composable 中散落 magic number。重点核对：

- 横屏顶部 42dp 圆形按钮、最大约 430dp 标题胶囊、70dp 中央主按钮、38/42dp 底部快捷控件。
- 竖屏顶部安全区、最大约 230dp 标题、38dp 顶部视觉按钮、底部 14/18dp 边距和 10dp 圆角浮岛。
- 22% 黑色全局遮罩、顶部 58% 黑色不透明度渐变起点、底部 80% 黑色不透明度渐变终点。
- 截图/AB 胶囊、进度条、AB 标记、Drawer、Dialog、Bottom Sheet、scrim 和 Snackbar 的层级关系。
- Shorts 顶部 16/18dp、右侧 rail 13/132dp、48dp 图标容器、底部信息 16/78/17dp 和 3dp 进度轨道。
- Shorts Sheet 的 26dp 上圆角、44x5dp 把手、62dp 行、48x28dp Switch；收藏/黑名单 58dp 行和空状态。
- 所有触控区至少 48x48dp；视觉尺寸和触控尺寸分离。
- 320dp 宽、系统最大字体、手势导航、刘海/圆角屏、横竖屏下不得重叠、裁切或被系统栏遮挡。

不要绘制手机模型、摄像头孔、侧键、假状态栏、Demo 顶部形态切换器和网页提示文字。

## 十三、真实能力与禁止伪实现

下列规则没有例外：

- 音轨和字幕来自 Media3 Tracks，不写死“AAC 立体声”或“简体中文”。
- 视频信息来自媒体库和 Media3 Format，未知字段显示“未知”。
- 截图读取真实帧；Demo poster 仅可用于测试替身，不可作为正式截图结果。
- PiP、全屏和方向按钮以系统回调确认，不以点击动作乐观改状态。
- 解码器 HW+/HW/SW 在没有真实 Renderer 切换能力时删除，不做假选项。
- 后台播放、睡眠定时、分享、删除、收藏、黑名单必须真实接线；暂未实现时隐藏入口，禁止成功 Toast。
- 不复制 Demo 固定视频路径、文件大小、编码信息、收藏或黑名单样例。
- 不引入内存 `Set` 作为正式持久化。
- 不在正式 UI 中显示“Demo”“尚未实现”“已准备”等伪完成反馈。

规范 P0、P1 全部是本任务必做。Demo 中已经可见的 P2 项若保留入口，也必须完成真实实现；不能完成时先隐藏入口并在最终报告中列为未完成，因此不得宣称整个任务完成。P3 社交评论、推荐算法、复杂剪辑、跨视频 AB 和非本地流媒体不在本任务范围。

## 十四、建议目标结构

以规范第 11 章为准，不要求机械复制文件名，但职责边界必须等价：

```text
feature/player/       常规 PlayerRoute、响应式布局、Overlay、面板和事件
feature/shorts/       独立 ShortsRoute、状态、手势和面板
domain/playback/      队列、顺序、AB、截图契约、播放 reducer
engine/media3/        Media3 命令、系统事实回传、帧/轨道/格式
data/                 偏好、收藏、黑名单、队列和文件操作持久化
core/designsystem/    Player Token、语义 Tabler 图标和通用控件
```

`PlayerScreen` 和 `ShortsScreen` 应是状态驱动的展示组件；ViewModel 负责业务状态和事件编排；Controller 负责播放器事实；Gateway 负责系统能力；Repository 负责持久化。

## 十五、强制实施流程

### 阶段 A：基线与差距矩阵

1. 检查 `git status`，记录并保护已有修改。
2. 完整阅读事实源和调用链。
3. 建立“规范条目 -> 当前实现 -> 缺口 -> 目标文件 -> 验证方式”矩阵。
4. 执行修改前基线并保存退出码、测试数量和失败详情：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

5. 若已有失败，确认是否为本任务相关；不得删除测试或降低断言。最终报告区分“基线已有失败”和“本次引入失败”。

### 阶段 B：领域与状态重构

先建立可测试的状态和命令边界，再写复杂 UI：

- 队列与四态顺序；
- Player 面板/Overlay reducer；
- AB reducer 与 limiter；
- Screenshot 临时状态机；
- 系统方向、全屏和 PiP 回传；
- 控件布局模型和持久化；
- Shorts 独立状态、候选队列和手势 reducer；
- 收藏、黑名单、分享和删除用例。

### 阶段 C：常规播放器 UI

实现并逐项验证横屏，再使用同一状态实现竖屏响应式布局。不要复制两套业务状态或播放会话。

### 阶段 D：高级交互与面板

实现截图、AB、播放列表、视频信息、轨道、顺序、比例、速度、PiP、锁定、控件布局编辑器和统一反馈。

### 阶段 E：YLShorts

创建一级 Route，完成布局、手势、双层切换、更多面板、收藏、黑名单和真实系统能力。

### 阶段 F：清理

删除旧重复实现、局部伪状态、无效接口、占位 Toast、旧图标和被替代组件。使用 `rg` 证明没有残留。

### 阶段 G：验证

每完成一个阶段立即运行最相关的小范围测试，最后执行全量门禁。

## 十六、必须新增或扩展的测试

### 16.1 JVM 单元测试

- `PlaybackOrder/QueueNavigator`：四态、首尾、随机、空队列、单项和缺失文件。
- `AbLoopReducer/Limiter`：A/B 顺序、最小帧、拖动、所有 Seek 路径、媒体切换。
- `PlayerOverlayReducer`：自动隐藏、暂停、锁定、面板互斥和返回优先级。
- `ScreenshotReducer`：Armed/Capturing/Preview/Failed、3 秒、暂停倒计时、关闭和删除。
- `ControlLayout`：增删、同区排序、上限、固定控件、坏值恢复和持久化。
- `ShortsGestureReducer`：轴锁定、所有阈值、回弹、提交、防重入、长按和 click 抑制。
- `ShortsViewModel`：自动下一条、循环优先、收藏、黑名单、删除、空列表和恢复。
- `PlayerViewModel`：命令拒绝不改变事实状态、暂停截图、队列切换、轨道/比例/速度恢复。

### 16.2 Compose 仪器测试

- 横屏/竖屏控件、Insets、面板形态和最大字体。
- 所有 PlaybackState 的节点、动作、禁用态和 content description。
- Seek、AB 标记、截图胶囊/预览/倒计时、控件布局编辑器。
- Drawer/Dialog/Sheet 的 scrim、返回优先级、焦点恢复和互斥。
- Shorts 点击、上下切换、水平 Seek、长按倍速、更多面板、收藏和黑名单管理。

### 16.3 Media3/真机验证

- API 31、33、36；横屏、竖屏、手势导航；SurfaceView/TextureView 截图。
- 普通、多轨、无字幕、损坏、权限撤销、文件删除和 Vault 媒体。
- PiP 支持/不支持、自动 PiP、系统方向、音频焦点和耳机断开。
- Playing/Paused/Ready 截图文件可读取、可删除且 Bitmap/Surface 无泄漏。
- Shorts 连续快速滑动、邻项失败、切后台/回前台、进程重建和长时间播放。

设备不可用时不要伪造真机通过；完成 JVM、构建和 Lint，并在报告中明确列出待执行命令和风险。

## 十七、最终门禁

至少执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug
.\gradlew.bat lintRelease assembleRelease
git diff --check
```

有可用设备或模拟器时继续执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

验证要求：

- 不跳过测试，不注释失败测试，不弱化断言，不修改正确预期来迁就错误实现。
- 若架构变化导致预期合理改变，可以同步重写测试，但必须说明行为为何变化。
- 检查 R8/minify 后 Release 构建、资源压缩、Tabler 图标可达性和运行时反射规则。
- 比较修改前后测试数、构建结果、APK 体积和关键播放行为。
- 对横屏、竖屏、YLShorts 各保存至少一组实机/模拟器截图用于和 Demo 对照；核对遮挡、裁切、位置、字体和状态栏。
- 不得在存在本次引入的编译、测试、Lint 或运行时错误时宣称完成。

## 十八、最终验收清单

只有以下项目全部满足，任务才算完成：

- [ ] 横屏和竖屏共用一个常规播放会话，旋转不丢状态。
- [ ] YLShorts 是独立一级页面，不是 Player orientation 第三态。
- [ ] `16-player-ui-ux-interaction-implementation-spec.md` 的 P0、P1 条目全部实现。
- [ ] Demo 中保留的所有可见按钮均有真实功能、禁用条件和错误路径。
- [ ] 常规播放器布局、控件、颜色、尺寸、遮罩、面板和动效尽可能复原 Demo。
- [ ] 截图真实捕获，胶囊、飞入预览、3 秒进度、暂停与删除完整。
- [ ] AB 标记可见、可拖动、可键盘调整，播放和 Seek 被真实限制在 A/B。
- [ ] 播放列表、四态顺序、速度、比例、音轨、字幕、信息、PiP、全屏、旋转、锁定真实生效。
- [ ] 控件布局编辑器可增删排序、即时同步并持久化。
- [ ] Shorts 上下/左右手势、邻项动画、长按倍速、自动下一条、循环完整。
- [ ] Shorts 收藏、黑名单、管理列表、分享、删除和信息均接入真实数据或按规范隐藏。
- [ ] Vault 截图/PiP/文件信息限制没有被破坏。
- [ ] 所有交互满足 48dp 触控、TalkBack、键盘、最大字体和 Insets 要求。
- [ ] 没有 Demo 固定数据、浏览器 API、假解码器、占位 Toast、重复 Player 或内存伪持久化。
- [ ] 相关旧功能、边界和失败路径完成回归。
- [ ] Debug/Release 构建、单测、Lint、R8 及可执行的仪器测试有真实结果。

## 十九、最终回复格式

最终回复必须简洁但包含可核验事实：

1. 实际完成的架构和 UI/功能，不写笼统的“已优化”。
2. 关键修改文件及职责。
3. “修改前 -> 修改后”的关键行为对比表。
4. 每条执行过的测试命令、退出结果、测试数量和产物。
5. 横屏、竖屏、YLShorts 的视觉对照结果。
6. 未执行的真机/仪器测试、已有基线失败、剩余风险和明确原因。

不要以“后续可以继续”“核心已完成”“其余为扩展”结束。若验收项仍未完成，明确标记任务未完成并继续实施；只有被外部条件真实阻塞时，才停止并提供证据。
