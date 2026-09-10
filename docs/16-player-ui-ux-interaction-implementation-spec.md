# 影里 Android 播放界面 UI/UX、交互与实现规范

> 版本：Draft 02（实现导向）
> 依据：`prototypes/views/04-player-demo.html`、`prototypes/views/04-player-Demo深度分析提示词.md`、`prototypes/views/影里播放页Demo详解文档.md`、`prototypes/design/*`、`prototypes/layout-typography-system.html`，以及当前 Android 源码。
> 目标：Android 31+，Kotlin + Jetpack Compose + Media3。
> 文档性质：开发实施规范。Demo 是交互和视觉事实源；Android 代码是能力和架构事实源。两者不一致时，按本文“目标实现”和“禁止照搬项”处理。

## 1. 结论先行

### 1.1 页面边界

横屏和竖屏是同一个常规 `PlayerRoute` 的两种响应式布局，必须共享播放状态、队列、Media3 会话、速度、比例、轨道、截图、AB 循环和播放器设置。它们不是两个播放器，也不是两个导航目的地。

YLShorts 是特殊的沉浸式短视频浏览产品，计划作为与首页、视频页同级的一级页面。它不能作为常规播放器的第三个 orientation 值，不能复用常规播放器的底栏、自动隐藏策略或控件布局配置。可以复用 Media3 播放引擎、截图网关、PiP 网关和视频信息读取能力，但必须有独立的 `ShortsRoute`、`ShortsViewModel`、`ShortsUiState` 和手势状态机。

### 1.2 当前代码事实

| 事实 | 代码位置 | 对规范的影响 |
|---|---|---|
| `PlayerScreen` 已是状态驱动入口，消费 `PlayerUiState` 和命令回调 | [PlayerScreen.kt:49](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerScreen.kt:49) | 保留无状态 Composable 方向，但要拆分覆盖层、面板和手势层 |
| `PlayerUiState` 已包含播放、连接、标题、位置、轨道、速度、比例、Overlay、偏好、截图结果 | [PlayerViewModel.kt:50](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerViewModel.kt:50) | 新状态应继续进入统一 UI state，不在 Composable 内复制事实状态 |
| 播放器所有者是 Service，Activity/Compose 不创建 Player | `docs/architecture/phase-4-playback-contract.md` | 保持 `Media3PlaybackController` 作为唯一命令边界 |
| Controller 已支持播放、暂停、Seek、速度、音轨、字幕、比例、重试 | [Media3PlaybackController.kt:54](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/engine/media3/Media3PlaybackController.kt:54) | UI 必须根据 `PlaybackCommandResult` 处理拒绝，不允许只改视觉 |
| 截图已使用 Surface/Texture + PixelCopy，并写入 MediaStore | [AndroidPlaybackSystemGateways.kt:43](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/engine/media3/AndroidPlaybackSystemGateways.kt:43) | UI 截图预览是临时状态，不能把截图失败归因于“未播放” |
| PiP 已有 Activity gateway；自动 PiP 由用户偏好控制 | [AndroidPlaybackSystemGateways.kt:30](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/engine/media3/AndroidPlaybackSystemGateways.kt:30)、[MainActivity.kt:181](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/app/MainActivity.kt:181) | 正式实现必须尊重设备能力和安全内容限制 |
| 常规队列当前在 `MediaContainer` 使用内存仓储，且 `PlayerViewModel` 没有队列命令 | [MediaContainer.kt:316](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/app/MediaContainer.kt:316) | 播放顺序和上一项/下一项需要补充真实队列用例，不得把 Demo 数组复制到 App |
| Demo 的音轨、字幕、解码器、后台播放、睡眠定时部分含 Toast 或视觉占位 | [04-player-demo.html:524](/D:/100_Projects/110_Daily/YingLi-Player/prototypes/views/04-player-demo.html:524) | 占位能力必须明确禁用、转为真实实现或删除，不得伪完成 |

### 1.3 升级原则

项目处于开发期，不以兼容旧接口为约束。允许删除当前单文件中的错误抽象、重构状态和破坏性调整；但每次改变必须先建立测试基线，再验证新功能、已有功能、边界和异常。目标是根因解决，不是最小 Diff。

## 2. Demo 的页面结构与事实参数

### 2.1 DOM 结构映射

```text
PlayerRoute
└── PlayerScreen
    ├── VideoSurface
    ├── GestureLayer
    ├── TopBar
    │   ├── BackButton
    │   ├── TitlePill
    │   └── TopQuickSlots
    ├── CenterControls
    │   ├── SeekBackward10s
    │   ├── PlayPause
    │   └── SeekForward10s
    ├── LandscapeBottomBar
    │   ├── SeekRow + ABMarkers
    │   └── CoreTransport + QuickSlots
    ├── PortraitFloatingControls
    ├── ScreenshotCapsule + ScreenshotPreview
    ├── AbLoopCapsule + ABMarkers
    ├── PanelScrim
    ├── SettingsDrawer
    ├── ControlLayoutDrawer
    ├── PlaylistDrawer
    ├── MediaInfoDialog
    └── StateOverlays
```

YLShorts 独立为：

```text
ShortsRoute
└── ShortsScreen
    ├── ShortsVideoSurface
    ├── ShortsGestureLayer
    ├── ShortsTopBar + Counter
    ├── ShortsActionRail
    ├── ShortsCaptionBlock
    ├── ShortsSeekBar
    ├── ShortsMoreBottomSheet
    ├── ShortsFavoritesSheet
    ├── ShortsBlacklistSheet
    └── Reused MediaInfoDialog / ScreenshotPreview / PiP gateway
```

### 2.2 Demo 容器与播放器内容的区别

Demo 的 `.demo-shell`、`.demo-header`、`stage`、手机模型、摄像头孔和系统状态栏用于展示设计，不进入正式 `PlayerScreen`。正式 App 的窗口由 `MainActivity` 和 `WindowInsets` 提供，视频容器必须覆盖安全区域，系统栏由 `YingLiSystemBars(SystemBarMode.PLAYER)` 管理。

Demo 的手机模型参数仅用于原型验收：横屏 `16:9`，竖屏/短视频 `9:16`，手机外壳圆角分别约 `34/45px`，内部播放器圆角约 `25/35px`。正式 App 使用窗口尺寸和设备圆角，不绘制虚拟手机外壳。

## 3. Design Tokens

### 3.1 颜色

Demo 的颜色与项目语义 Token 应保持同源，不在播放器组件中硬编码颜色。

| Token | Demo 值 | Compose 来源/用途 |
|---|---:|---|
| 播放画布 | `#000000` | `YingLiTheme.player.canvas` |
| 页面背景 | `#0F0F0F` | `YingLiTheme.colors.page` |
| 面板一级 | `#171717` | `surfaceLevel1` |
| 面板二级 | `#1F1F1F` | `surfaceLevel2` |
| 面板三级/悬停 | `#292929` | `surfaceLevel3` / `surfaceComponent` |
| 主文字 | `#F5F5F5` | `textPrimary` |
| 次文字 | `#B8B8B8` | `textSecondary` |
| 弱文字 | `#858585` | `textPlaceholder` |
| 主强调 | `#98C3D5` | 深色主题 SteelBlueA tone300 |
| 强调按下 | `#6FA8C0` | SteelBlueA tone400 |
| 错误 | `#F5AAA3` | DarkFunctional.error.base |
| 错误容器 | `#391512` | DarkFunctional.error.container |
| 播放轨道 | `#3DFFFFFF` | `PlayerScrimTokens.track` |
| 缓冲轨道 | `#66FFFFFF` | `PlayerScrimTokens.buffer` |
| 边缘遮罩 | `#B8000000` | `PlayerScrimTokens.edgeScrim` |
| 面板遮罩 | `#A3000000` | `scrimDefault` |
| 收藏激活 | `#E89CAF` | `accentFavorite` |

状态颜色不单独承担语义：选中、锁定、错误必须同时改变图标、文本或辅助语义；播放器播放状态不可只靠颜色区分。

### 3.2 尺寸与触控

```kotlin
object PlayerDimensions {
    val MinimumTouchTarget = 48.dp
    val Icon = 24.dp
    val TopBarHeight = 64.dp
    val LandscapeTopButton = 42.dp
    val LandscapePrimary = 42.dp
    val CenterPlay = 70.dp
    val CenterSecondary = 58.dp
    val PortraitFloatingCorner = 10.dp
    val ProgressVisualHeight = 4.dp
    val ProgressTouchHeight = 22.dp
    val CapsuleButton = 42.dp
    val PanelCorner = 8.dp
    val SheetCorner = 16.dp
}
```

视觉尺寸可以是 `34/38/42dp`，但所有可点击节点的语义和实际布局点击区不得小于 `48dp`。Demo 的小尺寸只表示图标容器视觉尺寸；Compose 用 `minimumTouchTarget` 外包裹。

### 3.3 字体

| 内容 | Demo | Compose 建议 |
|---|---:|---|
| 视频标题 | 14px / 18px，粗体 | `14.sp`, SemiBold, `18.sp` line height，单行省略 |
| 标题元信息 | 10px | `11.sp`, 次文字，单行省略 |
| 当前/总时长 | 12px，等宽数字 | `12.sp`, tabular numbers |
| 底部快捷文字 | 11–12px | `12.sp`, Medium |
| 抽屉标题 | 17px | `17.sp`, SemiBold |
| 设置项 | 13px | `13.sp`, Regular |
| 设置说明 | 11–12px | `12.sp`, 次文字 |
| Shorts 标题 | 15px / 700 | `15.sp`, Bold |
| Shorts 标签 | 10px | `10.sp` |
| Toast | 12px | `12.sp`, 最多 2 行 |

使用系统字体族和现有 Material typography；字体大小不可随视口缩放；字距固定为 0。200% 字体测试时，按钮文本允许换行但不能溢出父容器。

### 3.4 图标

项目已迁移到本地生成的 Tabler Compose 图标，入口为 [YingLiIcon.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/core/designsystem/icon/YingLiIcon.kt)。播放器图标必须补齐到同一语义枚举，优先使用 Tabler outline；主播放键、收藏激活可使用 filled 变体。

| 功能 | Tabler 图标建议 | 状态 |
|---|---|---|
| 返回 | `ArrowLeft` | outline |
| 播放/暂停 | `PlayerPlay` / `PlayerPause` | 主键可 filled |
| 上一项/下一项 | `PlayerSkipBack` / `PlayerSkipForward` | outline |
| 播放顺序 | `ListNumbers`、`ArrowsShuffle`、`Repeat`、`RepeatOnce` | 四态动态 |
| 速度 | `Gauge` | 旁显示数值 |
| 截图 | `Camera` | 捕获按钮可 filled |
| AB 循环 | `Repeat` | 激活态强调色 |
| 画中画 | `PictureInPicture` | 不可用时禁用 |
| 旋转 | `Rotate2` | 补充语义枚举 |
| 全屏 | `Maximize` / `Minimize` | 跟随窗口状态 |
| 锁定/解锁 | `Lock` / `LockOpen` | 锁定后仅显示解锁 |
| 播放列表 | `List` | 顶部可固定强调 |
| 音轨 | `Music` | 有轨道时启用 |
| 字幕 | `BadgeCc` | 无字幕时空状态 |
| 信息 | `InfoCircle` | 只读 Dialog |
| 更多 | `Dots` | 设置抽屉 |
| 移除 | `Minus` / `X` | 布局编辑/黑名单管理 |

新增图标必须先扩充 `YingLiIcon` 和图标单测，再在 Feature 使用；禁止在 Composable 内直接导入任意 `composeicons` 图标。

## 4. 常规播放页布局

### 4.1 横屏

横屏布局对应 Demo `.topbar + .center-controls + .bottombar`：

1. 顶部覆盖层：左右 `22dp` 内边距，顶部加 `WindowInsets.safeDrawing`；左侧返回键、标题胶囊，右侧最多四个顶部快捷槽。
2. 中央控制：水平排列快退、播放/暂停、快进；主键视觉 `70dp`，辅助键 `58dp`。
3. 底部栏：第一行时间、进度和总时长；第二行上一项/播放/下一项与快捷槽。底部使用 `edgeScrim`。
4. 进度条触控区高度 `22dp`，轨道视觉高度 `4dp`。AB 标记叠加在同一轨道坐标系中。

| 区域 | 上限 | 默认 |
|---|---:|---|
| 右上 | 4 | 播放列表、音轨、字幕、更多 |
| 左下 | 4 | 播放顺序、速度、截图、AB 循环 |
| 右下 | 4 | 画中画、全屏 |

上一项、播放/暂停、下一项、返回属于核心交通控件，不受快捷槽删除影响。全屏若固定，布局编辑器显示锁而不是减号。

### 4.2 竖屏

竖屏仍是常规播放页，不能改名为 Shorts：

1. 顶部栏增加状态栏安全区，标题胶囊最大宽度约 `230dp`，顶部快捷按钮视觉 `38dp`。
2. 中央播放键下移约 `20dp`，主键视觉 `58dp`，辅助键 `48dp`。
3. 底部使用悬浮控制条：左右 `14dp`，底部 `18dp`，内边距 `13dp/12dp`，圆角 `10dp`，背景约 78% 深色并使用 blur。
4. 竖屏快捷槽最多 6 个，可横向滚动；不得把按钮压缩到不可触控。默认：速度、播放顺序、旋转、PiP、全屏、更多。
5. 设置、播放列表和布局编辑从底部进入，最大高度 72%；视频信息 Dialog 宽度为窗口减 `28dp`。

### 4.3 全屏与方向

- `MainActivity` 继续负责 `requestedOrientation`，但要将真实 orientation、用户主动旋转与配置变化纳入 UI 状态，不能只用 `landscapeRequested` 翻转。
- 全屏是 Window UI 状态：隐藏/显示系统栏、处理 Insets、保持位置和锁定状态；不能照搬浏览器 Fullscreen API。
- 旋转失败时显示稳定 Toast，不改变已确认状态。
- Vault 播放保持 `FLAG_SECURE`，截图和 PiP 按 `allowScreenshot/allowPictureInPicture` 禁用。

## 5. 控件逐项规范

### 5.1 返回

- 圆形半透明按钮，视觉 `42dp`，Tabler `ArrowLeft 24dp`，实际触控 `48dp`。
- 点击优先关闭 Dialog/Sheet/Drawer；无面板时退出 `PlayerRoute`。系统返回使用同一优先级。
- 锁定状态不显示返回，先显示解锁。
- 测试：面板打开时返回只关闭面板；无面板时调用 `onBack`；锁定状态无“返回”语义节点。

### 5.2 播放/暂停

- 中心主键 `70dp`，底栏主键视觉 `42dp`，图标 `24dp`，中心图标可 `31dp`。
- `Ready/Paused -> play`，`Playing -> pause`，`Ended -> replay`；命令只经 ViewModel/Controller。
- 图标和 content description 从 `PlaybackState` 推导，UI 不单独维护 `playing` 布尔值。
- 当前代码将 Media3 `STATE_BUFFERING` 映射为 `Preparing`。目标实现应增加 `Buffering` 或 reason，避免播放中缓冲显示为初次加载。
- 保留已有播放/暂停语义测试；新增命令拒绝、Ended replay、Buffering 禁用测试。

### 5.3 上一项/下一项

- 横屏核心控件固定显示；竖屏可进入快捷槽或更多，契约相同。
- 上一项：当前位置大于 5 秒时回到 0，否则按队列策略选择上一项。
- 下一项按队列和播放顺序计算，不能在 UI 随机选择。
- 无下一项时禁用并显示“播放队列已结束”。
- 当前 `PlaybackQueue.next()` 仅表达连续播放，建议破坏性重构为 `PlaybackOrder + QueueNavigator`，ViewModel 暴露 `playNext/playPrevious`。

### 5.4 快退/快进 10 秒

- 中央辅助键和双击左右半区都调用 `seekBy(±10_000)`。
- 双击不得同时触发单击；统一由 `PlayerGestureLayer` 分发事件。
- Seek Clamp 到完整时长；AB 生效时 Clamp 到 `[A,B]`。
- 中央显示 `-10s/+10s` 反馈，约 1 秒结束，并发送无障碍事件。

### 5.5 进度条

- 时间文字用等宽数字，最小宽度 `42dp`；轨道 `4dp`，触控容器 `22dp`。
- 已播放段为强调色，缓冲段白色 40%，未播放段白色 24%；滑块视觉 `14dp`，白色 `3dp` 描边。
- 进度来自 `PlaybackTimeline`；ViewModel 的 250ms 投影只用于显示，真实 Seek 回到 Media3。

```text
DragStart -> controlsVisible = true，暂停自动隐藏
DragMove  -> 更新 previewPosition，不写进度仓储
DragEnd   -> controller.seekTo(clampedPosition)，按用户最终意图恢复播放
```

拖动期间记录 `wasPlaying`；若用户主动暂停，则松手不自动恢复。Seek 预览时间显示在滑块上方。有 A/B 时主进度只允许 `[A,B]`。

### 5.6 播放顺序

Demo 四态为顺序、随机、列表循环、单曲重复。正式实现：

- 建立 `PlaybackOrder` 和纯 Kotlin `QueueNavigator`。
- 快捷按钮循环四态；设置面板使用单选控件；二者双向同步。
- 随机避免连续同项，并保留会话历史以支持上一项。
- 单曲重复只影响 Ended；手动下一项仍切换。
- 设置写入 DataStore，坏值回退 `SEQUENCE`。

### 5.7 播放速度

领域支持 `0.5、0.75、1、1.25、1.5、2、3、4` 倍。Demo 快捷与设置列表不一致，正式实现必须统一：

- 快捷按钮循环领域支持值，调用 `setSpeed`。
- 设置面板显示完整单选列表和当前值。
- Controller 拒绝时不更新本地显示，展示错误 Toast。
- 每媒体偏好写入 `TrackPreferenceRepository`；全局偏好只作为默认值。

### 5.8 画面比例

三态 `FIT/FILL/ORIGINAL` 对应原始/裁剪/拉伸。当前 Controller 只有状态接口，`Media3VideoSurface` 还需映射到 `PlayerView.resizeMode`：

- `FIT`：完整显示，允许黑边。
- `FILL`：裁剪填满。
- `ORIGINAL`：MVP 若无法按像素显示，可明确回退 FIT，不能假装生效。
- 常规 Player 与 Shorts 分别持有页面偏好，底层能力可复用。

### 5.9 音轨与字幕

当前代码已经读取 `TrackChoice` 并调用 Media3 TrackSelectionParameters，不能再使用 Demo Toast 占位：

- 音轨按钮只在存在可选轨道时启用；单轨显示当前项或禁用并解释。
- 字幕提供“关闭 + 所有字幕轨道”，选中态有 Check 图标和 selected 语义。
- 列表行最小 `48dp`，标题 `14sp`，语言/格式 `12sp`。
- 成功后写入媒体偏好；切换视频按媒体 ID 恢复。
- 无轨道不是错误，显示空状态，不显示假按钮。

### 5.10 截图

#### 工具胶囊

Demo 包含上一帧、截图当前帧、下一帧、取消；按钮视觉 `42×34px`，截图按钮宽 `52px`。正式 Compose 胶囊高度至少 `48dp`，每项触控区至少 `48dp`。

#### 交互

1. 打开截图时关闭其他面板；Ready/Paused/Playing 都允许。
2. 上一帧/下一帧先暂停，按 `1/frameRate` Seek；无元数据时禁用。
3. 点击截图调用 `ScreenshotGateway.capture`；禁止因“未播放”拒绝。失败按空帧、权限、存储、只读、未知分类。
4. 成功预览从画面缩小飞向左上角，约 `420ms`；卡片约 `116dp`，比例 `16:10`。
5. 显示 3 秒，底部 `4dp` 倒计时条从满到空；点击图片暂停倒计时，删除按钮以 `200ms` 缩放、旋转和淡入出现。
6. 超时只移除预览。若删除按钮只删除预览，文案应为“关闭预览”；若要删除 MediaStore 文件，必须新增返回 URI/token 的网关契约和可撤销删除用例。

```text
ScreenshotState
├── Closed
├── Armed
├── Capturing
├── Preview(displayName, expiresAt, paused)
└── Failed(kind)
```

### 5.11 AB 循环

胶囊包含 A、B、清除、关闭；A/B 下方显示时间。状态为：

```text
Off -> SetA -> SetB(active) -> DragA/DragB
任意阶段 -> Clear -> Off
```

- A/B 标记为 `2dp` 竖线和朝区间的三角，触控区至少 `48dp`。
- A 不得晚于 B，最小间隔一帧；缺失帧率时按 30fps。
- `seekTo`、`seekBy`、进度拖动、逐帧都经过 `AbLoopLimiter`。
- 播放到 B 时在播放器/控制器层跳回 A，不依赖 UI 轮询。
- 切换媒体默认清除 AB；AB 仅是当前会话状态，不写全局 DataStore。

### 5.12 画中画

- 不支持设备、Vault 安全内容或没有活动媒体时禁用。
- 进入 PiP 不停止 MediaSession；退出后恢复 Overlay 和 Insets。
- 自动 PiP 仅在偏好开启、非安全内容、有当前请求时触发。
- 增加 `onPictureInPictureModeChanged` 状态回传，UI 不在点击后假设成功。

### 5.13 锁定界面

- 锁定后隐藏所有常规控件和面板，只保留居中解锁。
- 锁定不暂停视频；系统返回先解锁。
- 复用现有 `PlayerOverlayReducer`，补充面板打开时禁止锁定、旋转后保持锁定测试。

### 5.14 更多与设置

横屏设置抽屉从右侧进入，宽 `min(340dp, 84%)`；竖屏从底部进入，最高 72%。内容顺序：控件布局、视频信息、播放顺序、速度、比例、音轨、字幕。解码器只有存在真实可切换实现时显示；后台播放和睡眠定时不能用成功 Toast 伪装。

## 6. 控件布局编辑器

Demo 的卡片式编辑方式保留：每区显示“已选数/上限”；已选卡片右下角是减号；可添加卡片右下角是加号；固定控件显示锁。

```kotlin
data class PlayerControlLayout(
    val landscapeTop: List<PlayerControlId>,
    val landscapeLeft: List<PlayerControlId>,
    val landscapeRight: List<PlayerControlId>,
    val portraitBottom: List<PlayerControlId>,
)
```

约束：同区不重复；超过上限拒绝；核心控件不可移除；只允许同区排序；恢复推荐一次性替换默认。布局写入 DataStore，坏数据过滤并补默认。

Compose 使用 `LazyVerticalGrid/LazyRow` 与稳定 reorder 方案，同时提供 TalkBack 的“上移/下移”自定义语义。卡片最小高度 `56dp`，加减按钮触控区 `48dp`。

## 7. 播放列表与视频信息

### 7.1 播放列表

- 缩略图宽 `72dp`、`16:9`，项目最小高度 `64dp`；标题单行，时长次文字。
- 数据来自媒体库/显式播放队列，不复制 Demo 固定数组。
- 缩略图使用现有 `ThumbnailLoader`。
- 点击调用 `selectQueueItem`，更新队列并播放；当前项有 selected 和“正在播放”语义。
- 文件缺失项显示错误且不可播放。
- 横屏右侧 Drawer，竖屏 Bottom Sheet；共享同一 `PlaylistPanel` 内容组件。

### 7.2 视频信息 Dialog

字段：文件名、文件位置、封装格式、大小、分辨率、方向、时长、编码、帧率、平均码率、音轨。

- 文件名、MIME、时长来自媒体库；URI/路径按安全上下文脱敏。
- 分辨率、编码、帧率、码率和轨道来自 Media3 `Tracks/Format`，缺失显示“未知”，不伪造。
- Dialog 宽度为窗口减 `28dp`，最大高度减安全区 `48dp`，内部 `LazyColumn`。
- 行高至少 `48dp`，左列 `76–90dp`，右列允许换行。
- Shorts 可复用 Dialog，但数据来自当前 Shorts item。

## 8. Overlay、面板互斥与自动隐藏

现有 `PlayerOverlayReducer` 支持 `controlsVisible、locked、dragging、AUTO_HIDE_MILLIS=3000`。目标状态还需加入 `PlayerPanelState`、截图和 AB 工具状态。

- 初次进入显示；播放中 3 秒无交互隐藏。
- 暂停默认保持显示；用户主动隐藏后尊重该状态。
- Loading/Buffering 显示状态；Error/Ended 不自动隐藏。
- 拖动进度、AB、面板滚动期间不隐藏。
- 同时只打开一个 Drawer/Dialog/Sheet；截图与 AB 胶囊互斥。
- 危险确认 Dialog 不允许遮罩误关。

返回优先级：

```text
危险确认 -> Dialog -> Sheet/Drawer -> 截图/AB 胶囊 -> 解锁 -> 退出 PlayerRoute
```

视频空白区域单击只切换控制层，不自动播放/暂停，避免语义冲突。

## 9. 播放状态与错误状态

当前领域已有 `Idle、Preparing、Ready、Playing、Paused、Ended、Failed`。建议增加 `Buffering` 或在 UI 层从 `Preparing` 携带 reason，避免播放中缓冲显示为初次加载。

| 状态 | 视频 | 中央区 | 控件 |
|---|---|---|---|
| Idle | 黑画布 | 无 | 无 |
| Preparing | 黑画布或上一帧 | Spinner | 控件可见，Seek 禁用 |
| Ready | 首帧 | 播放键 | Seek 可用 |
| Playing | 正常播放 | 播放/暂停 | 3 秒后可隐藏 |
| Paused | 当前帧 | 播放键 | 默认保持显示 |
| Buffering | 当前帧 | 小 Spinner | Seek 可用，显示缓冲 |
| Ended | 最后一帧 | 重新播放 | 下一项按队列启用/禁用 |
| Failed | 黑画布或最后帧 | 错误文案+动作 | 不自动隐藏 |

沿用权限失效、文件不存在、容器不支持、解码器不可用、媒体损坏、未知分类。动作必须对应真实用例：重新授权、重新扫描/定位、查看兼容性、重试、返回。日志只记录稳定诊断码和 Media3 数字码，不显示 URI、路径或异常消息。

## 10. YLShorts 独立页面规范

### 10.1 布局

Demo `.shorts-ui` 是特殊页面参考：全屏 `9:16` 内容，顶部返回和 `1/3` 计数，右侧动作栏，底部标题/说明/标签/时间，底部细进度条。普通播放器的标题胶囊、中心三键和底部浮岛在 Shorts 中隐藏。

右侧动作按钮视觉 `48dp`，轨道间距约 `13dp`，每个按钮下方为 `10sp` 标签。收藏激活使用 `accentFavorite`，屏蔽激活使用错误色；状态必须有“已收藏/已屏蔽”文字。

### 10.2 手势状态机

- 上滑超过 `64dp`：下一条；下滑超过 `64dp`：上一条；切换前预加载相邻 MediaItem。
- 左右拖动：快进/快退，方向锁定后不触发上下切换。
- 长按：临时 2x，松手恢复；单击不能与长按重复。
- 播放结束：自动下一条由独立偏好控制；循环当前优先级更高。
- 黑名单：当前项加入后立即从候选移除并切换下一条；管理页按文件名列表显示，末尾 `X` 为 48dp 移除按钮。
- 收藏：接入现有组织域 Favorite 或新建 Shorts 仓储，不能使用 Demo 内存 `Set`。

### 10.3 更多 Bottom Sheet

Demo 项目包括自动下一条、长按倍速、分享、收藏列表、循环当前、PiP、截图、比例、视频信息、黑名单管理、删除短视频。

- P0：自动下一条、循环当前、速度、截图、信息、收藏、黑名单。
- P1：PiP、比例、收藏列表、黑名单管理。
- P2：分享（本地 `ACTION_SEND`）和删除文件（MediaStore/SAF 授权 + 二次确认）。
- 未实现项不得显示成功 Toast；开发开关可显示“尚未实现”，但不纳入验收。

## 11. Android 实现架构

### 11.1 目标包结构

```text
feature/player/
├── PlayerRoute.kt
├── PlayerScreen.kt
├── PlayerUiState.kt
├── PlayerEvent.kt
├── PlayerOverlay.kt
├── PlayerTopBar.kt
├── PlayerTransportControls.kt
├── PlayerProgressBar.kt
├── PlayerPanels.kt
├── PlayerScreenshot.kt
├── PlayerAbLoop.kt
└── PlayerGestureLayer.kt

feature/shorts/
├── ShortsRoute.kt
├── ShortsScreen.kt
├── ShortsUiState.kt
├── ShortsViewModel.kt
├── ShortsGestureLayer.kt
└── ShortsPanels.kt

domain/playback/
├── PlaybackOrder.kt
├── QueueNavigator.kt
├── AbLoop.kt
├── ScreenshotContracts.kt
└── PlaybackStateReducer.kt
```

`PlayerScreen` 只编排组件和回调；ViewModel 负责业务状态、命令结果和计时；Media3 Controller 负责播放器事实；Gateway 负责 PiP、截图、方向和系统分享；Repository 负责队列、偏好、收藏/黑名单和进度持久化。Composable 不导入 Room、文件 API 或 ExoPlayer。

### 11.2 State 与事件

建议使用单向数据流：

```kotlin
sealed interface PlayerEvent {
    data object ToggleControls : PlayerEvent
    data object PlayPause : PlayerEvent
    data class SeekPreview(val positionMillis: Long) : PlayerEvent
    data object SeekCommit : PlayerEvent
    data object Next : PlayerEvent
    data object Previous : PlayerEvent
    data class SetOrder(val order: PlaybackOrder) : PlayerEvent
    data class SetSpeed(val speed: PlaybackSpeed) : PlayerEvent
    data object OpenScreenshot : PlayerEvent
    data object CaptureScreenshot : PlayerEvent
    data class SetAbPoint(val point: AbPoint) : PlayerEvent
    data object ClearAb : PlayerEvent
    data class OpenPanel(val panel: PlayerPanel) : PlayerEvent
}
```

所有按钮、手势和系统回调转为事件；不要在 Composable 中直接修改多个 `remember` 状态来模拟播放器事实。

### 11.3 与现有代码的重构要求

1. 将 `PlayerScreen.kt` 中 `settingsOpen` 拆为 `PlayerPanelState`，面板互斥由 reducer 管理。
2. 将 `PlayerUiState.screenshotResult` 改为可消费的一次性事件或包含预览状态，避免截图成功后永久显示文本。
3. 给 `PlayerViewModel` 注入 `PlaybackQueueRepository` 和队列导航用例，删除只有当前项播放能力的隐含假设。
4. 让 `MainActivity` 的方向状态从窗口回调同步回 UI，不要只用 `landscapeRequested` 翻转。
5. 把音轨/字幕设置从当前 `AdvancedSettingsSheet` 细化为可测试列表组件；无轨道时显示空状态。
6. 增加 `AbLoopLimiter` 和纯 Kotlin reducer；AB 不需要持久化。
7. 独立建立 Shorts Feature，不在 `PlayerScreen` 增加 `mode == SHORTS` 分支。

## 12. 动画与动效

| 动效 | 时长 | 实现 |
|---|---:|---|
| 控件显示/隐藏 | 180–220ms | `AnimatedVisibility` + alpha/translation |
| Drawer/Sheet | 240ms | `slideInHorizontally` / `slideInVertically` |
| Dialog | 180ms | alpha + scale `0.98 -> 1` |
| 截图飞入 | 420ms | `Animatable`，从当前画面到左上角 |
| 截图倒计时 | 3000ms | `LaunchedEffect` + LinearProgressIndicator |
| 删除按钮 | 200ms | scale `.55 -> 1` + alpha + 轻微旋转 |
| Shorts 切换 | 320ms | 双层视频 translation |
| 快退/快进反馈 | 1000ms 内 | alpha + 数值变化 |

尊重系统 `AnimatorDurationScale=0`；状态和点击顺序不能依赖动画回调。

## 13. 功能优先级

| 优先级 | 功能 |
|---|---|
| P0 | Media3 播放/暂停、准备/错误、Seek、上一项/下一项、横屏/竖屏、全屏、锁定、播放列表、视频信息、截图、基础 AB、速度、音轨/字幕真实选择 |
| P1 | 四态播放顺序、控件布局编辑与 DataStore、PiP、比例、截图预览生命周期、YLShorts 页面、收藏、黑名单、自动下一条、循环当前 |
| P2 | 音量/亮度手势、分享、删除短视频、睡眠定时、后台播放策略、帧预览缩略图 |
| P3 | 评论、推荐算法、社交分享服务、复杂剪辑、跨视频 AB、非本地流媒体 |

YLShorts 的优先级只在 Shorts Feature 内计算，不能反向提升常规播放器复杂度。

## 14. 测试与升级门禁

### 14.1 修改前基线

重构前执行并保存报告：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

记录任务退出码、JVM 测试数量、Lint 错误/警告和 APK 产物。优先复用 `PlayerViewModelTest`、`PlayerScreenStateTest`、`AdvancedPlaybackContractsTest`、`Media3PlaybackControllerTest`。

### 14.2 单元测试

- `PlaybackOrder/QueueNavigator`：四态、首尾、随机不重复、单曲重复、空队列拒绝。
- `AbLoopReducer/Limiter`：A/B 顺序、最小一帧、Seek/Ended/切换视频。
- `PlayerOverlayReducer`：3 秒隐藏、拖动不隐藏、锁定只解锁、面板互斥。
- `ScreenshotReducer`：Armed/Capturing/Saved/Failed、3 秒超时、点击暂停倒计时、删除。
- `TrackPreference`：每媒体覆盖全局、坏值回退、速度和比例边界。
- `PlayerViewModel`：命令拒绝不改变 UI、暂停截图可调用、队列切换和进度写入。

### 14.3 Compose 仪器测试

- 横屏/竖屏：组件存在、面板呈现方向、Insets 不遮挡。
- Preparing/Buffering/Playing/Paused/Ended/Failed/Locked：节点和 content description。
- Seek：禁用、拖动、AB 标记、时间文本；触控节点至少 `48dp`。
- 控件布局：添加、移除、排序、上限、固定控件、恢复推荐、TalkBack 上移/下移。
- 截图：胶囊四按钮、成功预览、倒计时暂停、删除按钮最终状态。
- Dialog/Sheet：遮罩关闭、系统返回优先级、焦点恢复、200% 字体不溢出。

### 14.4 Media3/真机测试

- API 31、33、36；横屏、竖屏、系统手势导航；SurfaceView 和 TextureView 截图。
- 普通视频、无音轨、无字幕、多轨、损坏文件、权限撤销、文件移除。
- PiP 支持/不支持、自动 PiP、Vault `FLAG_SECURE`、耳机断开、音频焦点。
- 截图在 Playing、Paused、Ready 均可用；验证 MediaStore 文件、JPEG 可读、失败分类和 Bitmap 回收。

### 14.5 前后行为对比

每次变更在报告中填写：

| 项目 | 修改前实测 | 修改后实测 | 验收 |
|---|---|---|---|
| 播放/暂停 |  |  | 状态和图标同步 |
| Seek/AB |  |  | 区间和边界正确 |
| 截图 |  |  | Playing/Paused 均成功或正确分类失败 |
| 横竖屏 |  |  | 布局切换且位置保持 |
| 播放列表/顺序 |  |  | 队列导航符合四态 |
| 音轨/字幕 |  |  | Media3 选择和偏好恢复 |
| PiP/安全内容 |  |  | 权限与 FLAG_SECURE 正确 |
| 控件布局 |  |  | 增删排序持久化 |
| YLShorts | 不存在或 Demo 占位 | 独立页面实测 | 不影响常规 PlayerRoute |

### 14.6 最终门禁

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
git diff --check -- "docs/16-player-ui-ux-interaction-implementation-spec.md"
```

不得通过跳过测试、降低断言、删除失败用例或只测试新增路径制造通过结果。若真机或仪器测试未执行，交付报告必须明确列出未覆盖风险。

## 15. 实施顺序

1. 以当前测试建立基线，补齐 `PlaybackOrder`、队列导航、AB reducer 和 Buffering 语义。
2. 拆分 `PlayerScreen`，先实现横屏目标布局和真实状态映射。
3. 复用同一 `PlayerUiState` 实现竖屏布局变体，接入 Insets、方向和全屏。
4. 接入播放列表、视频信息、速度、比例、音轨、字幕、PiP 和锁定。
5. 实现截图状态机和 MediaStore 结果预览，验证暂停状态截图。
6. 实现控件布局编辑器、DataStore 持久化和无障碍 reorder 语义。
7. 增加 AB 标记拖动、区间限制和播放器回跳。
8. 建立 `ShortsRoute` 和独立 `ShortsViewModel`，实现上下滑、收藏、黑名单、更多 Sheet。
9. 执行 JVM、Compose、Media3 真机和 Release/Lint/R8 回归，填写前后行为表。

## 16. 禁止照搬 Demo

- 不把固定视频路径、文件大小、编码信息、黑名单初始集合复制到生产代码。
- 不把浏览器 Fullscreen API、HTML `video.play()` 当作 Android 实现。
- 不用 Toast 代替音轨、字幕、解码器、后台播放、睡眠定时、删除等真实能力。
- 不把 YLShorts 塞进常规播放器 orientation/mode 分支。
- 不在 UI 创建 ExoPlayer，不绕过 `PlaybackController`。
- 不使用内存 `Set` 保存 Shorts 收藏/黑名单，必须接入持久化仓储。
- 不因兼容旧实现保留重复状态、过渡 Adapter 或无效接口；新设计验证通过后删除旧实现。

## 17. 验收标准

1. 横屏、竖屏使用同一播放会话完成播放、暂停、Seek、切换和错误恢复。
2. Demo 中承诺保留的按钮都有真实命令、状态反馈、禁用和错误路径。
3. 截图在暂停和播放状态均工作，预览飞入左上角、3 秒倒计时、点击暂停、删除动画完整。
4. AB 的 A/B 标记可拖动，进度和播放范围真实受限。
5. 播放顺序四态、速度、比例、轨道、控件布局具备统一状态源和持久化策略。
6. YLShorts 是独立一级页面，不污染常规 PlayerRoute。
7. 相关旧功能、边界、异常、无障碍和安全行为均有前后测试证据。
8. 构建、Lint、单元测试和计划中的仪器/真机测试通过；未执行项目在交付说明中明确列出。

## 18. Demo 对照补充：已体现但原规范未充分描述的内容

本章逐项记录 `04-player-demo.html` 已经表现出来、但前文没有足够明确描述的行为和视觉事实。它的作用不是把 HTML/CSS 直接搬到 Android，而是避免实现时遗漏 Demo 已经承诺给用户的交互。每一项都必须转换为正式 Android 的状态、命令、语义和测试；仅用于演示的部分必须明确排除。

### 18.1 Demo 展示层与正式页面边界

Demo 的以下元素属于“演示环境”，不属于正式 `PlayerRoute`：

- `demo-header`、横屏/竖屏/YL短片分段切换和“演示信息”按钮。
- 手机模型外壳、摄像头孔、侧键、模拟系统状态栏和设备阴影。
- 页面底部“点击画面中央或进度条体验交互”的提示文字。

正式 Android 的对应关系如下：

| Demo 展示层 | 正式实现 |
|---|---|
| 顶部形态切换 | 由窗口方向、用户旋转按钮和 `WindowSizeClass` 决定，不在播放页显示第三个模式切换器 |
| 手机外壳 | 真机窗口、`WindowInsets`、系统状态栏/导航栏；不绘制假设备边框 |
| 模拟状态栏 | 系统状态栏，播放器只处理内容安全区 |
| 演示信息按钮 | 开发构建可保留诊断入口；正式构建隐藏 |
| 底部交互提示 | 首次使用可通过无障碍提示或一次性 Snackbar 告知，不常驻遮挡画面 |

YLShorts 的入口可以出现在应用一级导航中，但不能复用 Demo 的横屏/竖屏分段控件，也不能作为 `PlayerRoute` 的 orientation 参数。

### 18.2 常规播放器的点击、加载和切换语义

Demo 的常规播放器和 YLShorts 对“点击视频主体”的定义不同：

- 常规播放器点击视频空白区域只切换控制层显隐，不执行播放/暂停。
- YLShorts 点击视频主体才执行播放/暂停；完成一次滑动后必须抑制随后的 click，避免误暂停。
- 常规视频加载后会尝试自动播放；浏览器拒绝时显示“浏览器阻止了自动播放，请点击播放”。正式 Android 应将其转换为稳定错误码 `AUTOPLAY_REQUIRES_USER_GESTURE`，文案由 UI 层本地化。
- 用户主动点击播放时，播放失败显示可操作反馈，不能只翻转播放图标。
- 播放列表切换必须一次性完成：更新 MediaItem/source、`prepare`、清除当前媒体的 AB 状态、更新标题/计数/视频信息、关闭抽屉，再按策略尝试播放。
- 从 YLShorts 返回常规播放器时，清理邻项视频、拖动位移和切换动画；恢复常规媒体但不假定必须自动播放。
- 全屏和 PiP 的按钮状态只能由系统回调确认：Android 使用 `onPictureInPictureModeChanged`、窗口 Insets/系统栏状态和真实方向回调更新 UI；退出全屏时解除方向锁定。点击命令失败时不得提前切换图标或 `aria-pressed` 等价状态。

Android 的事实源顺序为：Media3 播放状态 -> `PlaybackCommandResult` -> ViewModel UI state -> Compose。不能用 `LaunchedEffect` 或按钮点击结果猜测 `isPlaying`。

### 18.3 Toast/Snackbar 统一反馈规范

Demo 的 `.toast` 是底部中央的短暂状态反馈：`z-index:30`、水平内边距约 `14px`、垂直内边距约 `10px`、圆角约 `7px`、背景 `#222724`、字号 `12px`，由 `opacity:0 + translateY(18px)` 在约 `180ms` 内进入，默认约 `1700ms` 后消失，并使用 `role="status"`。

正式 Android 统一使用 Snackbar 或等价 transient message：

1. 所有命令拒绝、播放切换、播放顺序、速度、比例、AB、截图、黑名单和收藏反馈走同一个 UI event 通道。
2. 错误反馈必须提供稳定的动作或恢复路径，例如“重试”“重新授权”“关闭”。
3. 截图结果不能永久放在 `PlayerUiState.screenshotResult` 中；成功事件只负责创建临时预览状态，超时或关闭后清理。
4. Snackbar 不得覆盖底部进度条、截图胶囊或 Shorts 操作栏；锚点由当前页面形态和 Insets 计算。

### 18.4 常规播放器视觉 Token 与响应式规则

Demo 明确使用下列视觉事实，正式实现应转换为 Compose `PlayerTokens`，不得在多个 Composable 中散落常量：

- 播放画面有约 `22%` 黑色整体遮罩；顶部渐变起点约为 `58%` 黑色不透明度并向下透明，底部渐变向上透明、终点约为 `80%` 黑色不透明度。
- 横屏顶部圆形按钮约 `42dp`，半透明深色背景、`1dp` 半透明白边、圆形和约 `10dp` 模糊；标题胶囊最大宽约 `430dp`，两行文本，标题 `14sp`、元信息 `10sp`。
- 中央播放按钮约 `70dp`，白色半透明背景、`1dp` 边框和阴影，悬停/按下状态只做轻微放大，不改变布局尺寸。
- 横屏底部快捷控件约 `38dp`，主播放按钮约 `42dp`，圆角约 `7dp`；激活状态使用强调色容器而不是改变整个播放器色调。播放列表入口固定使用强调色。
- 竖屏顶部安全区后的起始内边距约 `44dp`，标题最大宽约 `230dp`，顶部按钮视觉尺寸约 `38dp`。
- 竖屏底部浮岛左右 `14dp`、底部 `18dp`，内边距约 `13dp 13dp 12dp`，圆角 `10dp`，半透明深色背景并带约 `12dp` 模糊。第一行是播放键和最多六个快捷槽，第二行是当前时间、进度条和总时长；竖屏不显示横屏底栏，默认不把音轨/字幕槽塞入顶部。
- 所有按钮触控区域仍必须至少 `48dp`；上述尺寸是视觉尺寸，触控区可以通过透明外层扩大。

### 18.5 进度条、元数据和 AB 实时同步

Demo 在 `input`、`timeupdate` 和 `loadedmetadata` 三个时机更新所有进度输入框、当前时间、总时长、AB 标记位置和 Shorts 时间文本。正式实现必须保留这些可观察结果，但由 Media3 状态驱动：

- `loadedmetadata` 对应 Media3 `duration`/`Tracks` 就绪，重新计算时长、帧率和视频信息。
- 播放事实来自播放器 position；Compose 的重组计时器只能刷新显示，不能成为 position 的来源。
- AB 开启后，进度条坐标仍以完整媒体时长为坐标系，但 `seekTo`、拖动、逐帧和自动播放位置必须经过 `AbLoopLimiter`。
- 播放到 B 点时由播放器命令层跳回 A 点；不能依赖 UI 每帧轮询才能循环。
- 进度输入框、横屏 Seek、竖屏 Seek、Shorts Seek 必须共享同一个 position state，避免三处显示漂移。

### 18.6 AB 标记的无障碍和键盘行为

Demo 的 A/B 标记是可聚焦 slider，具有 `aria-valuemin`、`aria-valuemax`、`aria-valuenow` 和带时间的 `aria-valuetext`，支持 Pointer Capture 拖动及左右方向键按一帧移动。正式 Compose 必须等价实现：

- 使用 `progressBarRangeInfo` 暴露范围、当前值和步进信息。
- 使用 `customActions` 提供“向左一帧”“向右一帧”，外接键盘的 `ArrowLeft/ArrowRight` 也必须生效。
- 拖动期间保持控制层可见，结束后宣布“A 点已调整”或“B 点已调整”；值被边界钳制时仍返回合法状态。
- A 与 B 至少间隔一帧；A 不得晚于 B；切换媒体、关闭 AB 工具或清除 AB 后焦点返回触发按钮。

### 18.7 截图 Demo 占位与正式能力的区别

Demo 的 `captureScreenshot()` 并未读取真实视频帧，而是直接显示当前轨道已有 poster/素材图；它用于验证交互，不应被误认为截图失败条件。正式实现必须使用现有 `ScreenshotGateway` 和 MediaStore，但允许在 Ready、Paused、Playing 状态截图，不能因为“没有播放”拒绝。

Demo 视觉事实：

- 截图胶囊包含上一帧、截图当前帧、下一帧、取消四个按钮；截图按钮约 `52dp` 宽，其他按钮约 `42dp`，胶囊高度不低于 `48dp`。
- 预览位于播放区域左上角，视觉宽约 `116dp`、比例约 `16:10`、`2dp` 白边、`7dp` 圆角和阴影。
- 预览约 `420ms` 从当前位置以约 `scale(2.4)` 飞入左上角，显示 `3s`；底部约 `4dp` 进度条从满到空。
- 点击预览暂停倒计时并以约 `200ms` 缩放、旋转和淡入显示删除按钮。
- Demo 的删除只移除预览，不删除图片文件。正式产品必须明确区分“关闭预览”和“删除 MediaStore 文件”；若实现后者，网关必须返回可删除 URI/token，并提供失败反馈和权限处理。

### 18.8 设置抽屉中的能力边界

Demo 设置抽屉实际包含：控件布局、视频信息、解码器 HW+/HW/SW、播放顺序、播放速度、画面比例、字幕简体中文/关闭。正式实现处理如下：

- 控件布局、信息、顺序、速度、比例、字幕必须有真实命令和状态回传。
- 当前 Android 没有真实解码器切换能力时，删除 HW+/HW/SW；不得保留只改变选中态的假开关。若未来接入能力，必须显示当前 Renderer 能力、重建播放器并覆盖测试。
- 字幕选项必须来自 Media3 `TrackChoice`；“简体中文”只是 Demo 示例，不得写死为唯一轨道。
- 播放顺序至少支持顺序、随机、列表循环、单曲重复，并在当前按钮图标、短文本、设置值和队列导航中同步。

### 18.9 控件布局编辑器的完整交互

Demo 的编辑器不仅是列表：它包含说明文字“拖动调整顺序；移除后可从控件库重新添加”、核心控件始终保留提示、“恢复推荐”按钮、四个区域标题、`selected / limit` 数量、拖拽手柄、固定控件锁图标、卡片右下角减号和可添加卡片右下角加号。

- 只允许同一区域内拖拽排序；跨区域拖放必须拒绝并保持原序。
- 点击减号/加号后立即刷新真实播放器快捷槽，不能只更新编辑器预览。
- 达到区域上限时，添加卡片禁用并保留清晰的无障碍 disabled 语义。
- 固定控件不可移除；坏数据、重复控件和超上限数据加载时过滤并补推荐默认值。
- Compose 仪器测试必须覆盖添加、移除、排序、恢复推荐、上限和旋转后布局保持。

### 18.10 播放列表和视频信息的精确呈现

Demo 播放列表使用 `72px` 宽、`16:9` 缩略图、约 `5px` 圆角；行内边距约 `11px 0`，行间 `1px` 分割线，标题 `12px`，时长 `10px`，当前标题用强调色，右侧显示 filled play 图标。缩略图用静音、`preload="metadata"` 的 video 读取首帧。

正式实现映射为：`ThumbnailLoader` 优先提供首帧，列表项最小高度不低于 `64dp`，当前项同时提供 selected 和“正在播放”语义；点击后更新 MediaItem、关闭 Drawer 并按当前自动播放策略执行。

视频信息 Dialog 必须在媒体元数据改变后刷新；Demo 展示的文件名、位置、封装、大小、分辨率、方向、时长、编码、帧率、平均码率、音轨都不能使用固定字符串。缺失字段显示“未知”，路径按安全上下文脱敏。

## 19. YLShorts Demo 对照补充

### 19.1 手势阈值与状态机

Demo 的 Shorts 手势不是一个简单的 `swipe` 回调，正式实现应采用以下状态机：

```text
Idle -> Dragging -> PreviewNeighbor -> CommitTransition -> LoadNext -> Cleanup
                  \-> Cancel -> SpringBack
```

具体规则：

- 垂直/水平移动超过约 `10dp` 后锁定主轴；小于约 `6dp` 不开始跟手。
- 上滑或下滑提交阈值约 `64dp`，分别进入下一条/上一条；未达阈值回弹。
- 水平快退/快进提交阈值约 `42dp`，每次调整 `5s`；方向锁定后不能同时触发上下切换。
- 切换期间禁止重复切换；按钮、输入框和 Bottom Sheet 区域不触发 Shorts 滑动。
- 支持键盘 `ArrowUp`/`ArrowDown` 切换上一条/下一条，并提供 TalkBack 的上一条/下一条自定义动作。

### 19.2 邻项预加载和动画

Demo 使用 `shortsPeekVideo` 预加载邻项，切换时当前视频与邻项同时移动，动画约 `320ms`，曲线为 `cubic-bezier(.22, .61, .36, 1)`。正式实现应保证：

- 邻项准备失败时，当前项保持可播放并显示可恢复反馈。
- 达到提交阈值后邻项开始播放，切换完成再释放旧 Surface/资源。
- 取消拖动时回弹，不改变当前索引和播放位置。
- `CommitTransition` 期间忽略重复手势，避免索引跳过或双重 `prepare`。

### 19.3 Shorts 点击、长按倍速和提示

- 点击视频主体播放/暂停；一次拖动结束后的 click 必须抑制。
- “更多”面板的长按倍速入口：按下约 `320ms` 进入临时 `2.0x`，松手恢复 `1.0x`；未触发长按时，单击在 `1.0x/2.0x` 锁定值之间切换。
- 视频主体长按入口：约 `360ms` 进入临时 `2.0x`，移动超过约 `8dp` 取消计时；松手恢复，不能与滑动切换冲突。
- 初次进入显示一次手势提示，包含上滑下一条、下滑上一条、左右快进/快退和长按 `2.0x`，约 `2800ms` 后消失；提示状态持久化为“已展示”，无障碍用户仍可通过动作语义获得同等信息。

### 19.4 Shorts 布局和尺寸

- 顶部左右约 `16dp`、距上约 `18dp`；返回按钮约 `36dp` 圆形，计数器胶囊内边距约 `5dp 9dp`。
- 右侧操作栏距右约 `13dp`、距底约 `132dp`；按钮视觉宽约 `51dp`，图标容器约 `48dp`，间距约 `13dp`，标签约 `10sp`。
- 底部信息区左约 `16dp`、右约 `78dp`、底约 `17dp`，标题 `15sp` 粗体，说明 `11sp`，标签 `10sp`，标签内边距约 `4dp 8dp`、圆角约 `5dp`。
- Shorts 进度条左右约 `16dp`、底约 `16dp`，轨道约 `3dp`，thumb 约 `9dp`。
- 默认比例为 `cover`；Shorts 独立维护 `shortsFitIndex`，顺序为“裁剪 cover -> 原始 contain -> 拉伸 fill”，不能与常规播放器比例状态共享。

### 19.5 更多面板、收藏和黑名单

Shorts 更多面板使用独立 scrim 和 Bottom Sheet：上圆角约 `26dp`，内边距约 `8dp 18dp` 加底部 safe-area，顶部拖拽把手约 `44dp x 5dp`；每行最小高度约 `62dp`，三列为 `44dp` 图标、内容、尾部控件，列间距约 `10dp`，标题 `16sp`、描述 `12sp`/`17sp` 行高。Switch 约 `48dp x 28dp`，边框 `2dp`，thumb `16dp`；删除项使用 error 色。

收藏/黑名单管理复用同一 Bottom Sheet 结构：标题约 `17sp`，数量 `12sp`，行高约 `58dp`，文件名 `13sp`，图标约 `20dp`，末尾移除按钮视觉约 `32dp`、触控区至少 `48dp`；空状态最小高度约 `120dp`。移除后必须立即同步主界面收藏/屏蔽按钮、数量和当前项状态。

当前项若已在黑名单，主按钮显示“已屏蔽”、无障碍标签为“取消屏蔽”；加入黑名单后从候选队列移除并按下一项策略继续。Demo 的初始黑名单数据仅用于演示，正式实现从持久化仓储读取。

“删除短视频”在 Demo 中只显示危险入口和占位说明，未修改本地文件。正式实现必须先二次确认，再通过 MediaStore/SAF 删除；删除成功后从候选列表移除，当前项要定义下一项/空列表策略，失败时显示可理解的错误，不得伪造成功 Toast。

### 19.6 Shorts 默认状态

实现和测试应明确以下 Demo 初始状态与正式持久化边界：

| 状态 | Demo 默认值 | Android 处理 |
|---|---|---|
| 常规播放方向 | 横屏 | 窗口/用户偏好；不是 Shorts 状态 |
| 播放顺序 | 顺序 | `PlaybackOrder.Sequence` |
| 常规速度 | `1.0x` | 每媒体/全局偏好按既有策略恢复 |
| 常规比例 | 原始 `contain` | 与 Shorts 比例隔离 |
| 音量 | `0.8` | 交给音频焦点和用户偏好，不能在 UI 写死 |
| AB | 未设置 | 当前媒体会话临时状态 |
| Shorts 自动下一条 | 开启 | Shorts 独立偏好 |
| Shorts 循环当前 | 关闭 | 循环优先级高于自动下一条 |
| Shorts 速度 | `1.0x`，长按临时 `2.0x` | 临时态不得持久化为播放速度 |
| Shorts 比例 | `cover` | `shortsFitIndex=0` |
| Shorts 黑名单/收藏 | Demo 内存示例 | Room/DataStore 仓储；不复制示例文件名 |

## 20. 补充测试门禁

在第 14 节测试之外，针对本章新增事实增加以下回归用例：

1. Demo 展示层不得出现在正式 Android 屏幕节点；正式页面使用系统 Insets 而非假状态栏。
2. 常规播放器空白区域点击只切换控制层，YLShorts 视频点击播放/暂停；滑动结束不误触发 click。
3. 自动播放拒绝、主动播放失败、队列切换和切换媒体清理 AB 的命令结果均可观察、可测试。
4. AB 标记支持拖动、左右一帧键盘操作、边界钳制和 TalkBack 范围语义。
5. 截图在 Ready/Paused/Playing 均可调用；预览飞入、3 秒倒计时、点击暂停和删除/关闭语义一致；真实 MediaStore URI 可验证。
6. Shorts 手势覆盖轴向锁定、阈值回弹、邻项预加载失败、切换防重入、长按倍速与滑动冲突。
7. Shorts 自动下一条、循环当前、收藏、黑名单、删除确认和管理列表的状态同步覆盖空列表、当前项和文件缺失边界。
8. 常规比例与 Shorts 比例互不污染；旋转、PiP、全屏回调后按钮状态以系统事实为准。
9. 所有视觉尺寸转换为 Token 后，在 320dp 宽、最大字体、横竖屏和系统手势导航下无重叠、无裁切。
