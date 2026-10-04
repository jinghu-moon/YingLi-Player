# 影里 Android 播放界面 UI/UX、交互与实现规范

> 版本：Draft 02（实现导向）
> 依据：`prototypes/views/04-player-demo.html`、`prototypes/views/04-player-Demo深度分析提示词.md`、`prototypes/views/影里播放页Demo详解文档.md`、`prototypes/design/*`、`prototypes/layout-typography-system.html`，以及当前 Android 源码。
> 目标：Android 31+，Kotlin + Jetpack Compose；Media3 是当前默认播放后端。
> 文档性质：开发实施规范。Demo 是交互和视觉事实源；播放后端与会话边界以 [`17-playback-architecture-refactor-spec.md`](17-playback-architecture-refactor-spec.md) 为准。两者不一致时，按本文“目标实现”和“禁止照搬项”处理。

## 1. 结论先行

### 1.1 页面边界

横屏和竖屏是同一个常规 `PlayerRoute` 的两种响应式布局，必须共享播放状态、队列、Media3 会话、速度、比例、轨道、截图、AB 循环和播放器设置。它们不是两个播放器，也不是两个导航目的地。

YLShorts 是特殊的沉浸式短视频浏览产品，计划作为与首页、视频页同级的一级页面。它不能作为常规播放器的第三个 orientation 值，不能复用常规播放器的底栏、自动隐藏策略或控件布局配置。它通过 `PlaybackSessionClient` 复用底层播放会话和能力，不直接复用或访问 Media3、截图实现、PiP 实现；必须有独立的 `ShortsRoute`、`ShortsViewModel`、`ShortsUiState` 和手势状态机。

### 1.2 当前代码事实

| 事实 | 代码位置 | 对规范的影响 |
|---|---|---|
| `PlayerScreen` 已是状态驱动入口，消费 `PlayerUiState` 和命令回调 | [PlayerScreen.kt:49](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerScreen.kt:49) | 保留无状态 Composable 方向，但要拆分覆盖层、面板和手势层 |
| `PlayerUiState` 已包含播放、连接、标题、位置、轨道、速度、比例、Overlay、偏好、截图结果 | [PlayerViewModel.kt:50](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerViewModel.kt:50) | 新状态应继续进入统一 UI state，不在 Composable 内复制事实状态 |
| 播放器所有者是 Service，Activity/Compose 不创建 Player | `docs/architecture/phase-4-playback-contract.md` | 当前通过 Controller 连接；目标由 `PlaybackSessionClient` 作为页面命令边界 |
| Controller 已支持播放、暂停、Seek、速度、音轨、字幕、比例、重试 | [Media3PlaybackController.kt:54](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/engine/media3/Media3PlaybackController.kt:54) | UI 必须根据 `PlaybackCommandResult` 处理拒绝，不允许只改视觉 |
| 截图已使用 Surface/Texture + PixelCopy，并写入 MediaStore | [Media3ScreenshotGateway.kt:23](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/engine/media3/Media3ScreenshotGateway.kt:23) | UI 截图预览是临时状态，不能把截图失败归因于“未播放” |
| PiP 已有 Activity gateway；自动 PiP 由用户偏好控制，并在参数镜像里与安全内容、是否有媒体一起下发 | [ActivityPictureInPictureGateway.kt:1](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/app/playback/ActivityPictureInPictureGateway.kt:1)、[AutoPictureInPicturePolicy.kt:1](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/domain/playback/AutoPictureInPicturePolicy.kt:1) | 正式实现必须尊重设备能力和安全内容限制（§5.12） |
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

项目已迁移到本地生成的 Tabler Compose 图标，入口为 [YingLiIcon.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/core/designsystem/icon/YingLiIcon.kt)。播放器图标必须补齐到同一语义枚举，优先使用 Tabler outline；主播放键、收藏激活可使用 filled 变体。本地图标集（Tabler Icons 3.46.0）缺少的语义图标，按 [design/README.md](/D:/100_Projects/110_Daily/YingLi-Player/design/README.md) 的约定把原素材放进 `design/assets/icons/`，在 `YingLiLocalIcons` 中合成并标为 `IconProvider.LOCAL_VECTOR`。图标库里**有**同名图标、但设计稿已定稿字形时走 `YingLiCustomIcons`（同样 `IconProvider.LOCAL_VECTOR`，逐条 path 原样移植、不依赖图标库版本）：镜像翻转的 `flip-horizontal.svg` / `flip-vertical.svg` 就是这种情况，避免图标库升级改变已定稿字形。同一屏内不同语义的控件不得共用同一图标（曾出现播放列表/播放顺序/字幕共用 `List` 的误判）。

| 功能 | Tabler 图标建议 | 状态 |
|---|---|---|
| 返回 | `ArrowLeft` | outline |
| 播放/暂停 | `PlayerPlay` / `PlayerPause` | 主键可 filled |
| 上一项/下一项 | `PlayerSkipBack` / `PlayerSkipForward` | outline |
| 播放顺序 | `PlayModeSequence`（本地合成）、`ArrowsShuffle`、`Repeat`、`RepeatOnce` | 四态动态 |
| 速度 | `BrandSpeedtest` | 点击在底栏行内展开档位条 |
| 画面比例 | `AspectRatio`（适应）、`Crop`（裁剪）、`ArrowsHorizontal`（拉伸） | 三态动态，底栏一次点按循环 |
| 截图 | `Aperture` | 捕获按钮可 filled；入口在「更多」托盘与设置面板「工具」分组，不再占底栏（口径修正：原表写 `Camera`，代码为 `TablerIcons.Outline.Aperture`） |
| AB 循环 | `a-b-2`（`YingLiIcon.AB2`，Tabler 契约名 `a-b-2`、代码属性名 `AB2`；口径修正：先前的 `YingLiIcon.REPLAY` 是借用字形，**`REPLAY` 本身保留**给"刷新/重播"语义） | 循环生效时**实心**（激活态）；入口三处一致：底栏「更多」工具托盘、设置面板「工具」分组、顶栏溢出菜单（见 §5.11） |
| 镜像翻转 | `flip-horizontal` / `flip-vertical` 设计资产逐路径移植（`YingLiCustomIcons`，`IconProvider.LOCAL_VECTOR`） | 托盘开关，开启时 filled；只做视图层变换（见 §5.16） |
| 后台播放 | `Headphones` | 托盘开关，开启时 filled；默认开启，关闭后离开前台即暂停（见 §5.17） |
| 画中画 | `PictureInPicture` | 不可用时禁用 |
| 旋转 | `Rotate2` | 四态画面旋转（视图层，不请求系统方向） |
| 亮度 | `Brightness` | 手势反馈浮岛使用；亮度为窗口级页面亮度 |
| 全屏 | `Maximize` / `Minimize` | 跟随窗口状态 |
| 锁定/解锁 | `LockOpen`（未锁定）/ `Lock`（已锁定） | 图标跟状态、文案跟动作；锁定后保留解锁与播放/暂停，且随控件自动隐藏 |
| 播放列表 | `Playlist` | 顶部可固定强调 |
| 音轨 | `Music` | 有轨道时启用 |
| 字幕 | `Subtitles` | 无字幕时空状态 |
| 信息 | `InfoCircle` | 只读 Dialog |
| 更多 | `DotsVertical` | 竖屏底栏最右的托盘开关（`PlayerControlId.MORE`，复用 `YingLiIcon.OVERFLOW`）；横屏顶栏另有常驻溢出菜单（口径修正：原写「不再是快捷槽控件」） |
| 移除 | `Minus` / `X` | 布局编辑/黑名单管理 |

新增图标必须先扩充 `YingLiIcon` 和图标单测，再在 Feature 使用；禁止在 Composable 内直接导入任意 `composeicons` 图标。

## 4. 常规播放页布局

### 4.1 横屏

横屏布局对应 Demo `.topbar + .center-controls + .bottombar`：

1. 顶部覆盖层：左右 `22dp` 内边距，顶部加 `WindowInsets.safeDrawing`；左侧返回键、标题胶囊，右侧为顶部快捷槽（默认三项：播放列表、音轨、字幕），并常驻一个「更多」溢出按钮（设置、视频信息等入口都在它的菜单里；控件模型里的 `MORE` 只出现在竖屏底栏最右，见 §4.2）。
2. 中央控制：水平排列快退、播放/暂停、快进；主键视觉 `70dp`，辅助键 `58dp`。**锁定态不显示中央控制**（见 §5.13：只保留解锁与播放/暂停的浮动入口，随控件唤出、约 3 秒自动隐藏）。
3. 底部栏：第一行时间、进度和总时长；第二行上一项/播放/下一项与快捷槽。底部使用 `edgeScrim`。
4. 进度条触控区高度 `22dp`，轨道视觉高度 `4dp`。AB 标记叠加在同一轨道坐标系中。

| 区域 | 上限 | 默认 |
|---|---:|---|
| 右上 | 4 | 播放列表、音轨、字幕（音轨/字幕在竖屏由顶栏过滤；`MORE` 只放竖屏底栏，见 §4.2） |
| 左下 | 4 | 播放顺序、速度（截图与 AB 循环已移入「更多」托盘，见 §5.14） |
| 右下 | 4 | 画中画、全屏、锁定 |

上一项、播放/暂停、下一项、返回属于核心交通控件，不受快捷槽删除影响。全屏若固定，布局编辑器显示锁而不是减号。

### 4.2 竖屏

竖屏仍是常规播放页，不能改名为 Shorts：

1. 顶部栏增加状态栏安全区，标题胶囊最大宽度约 `230dp`，顶部快捷按钮视觉 `38dp`。
2. 中央播放键下移约 `20dp`，主键视觉 `58dp`，辅助键 `48dp`。
3. 底部使用悬浮控制条：左右 `14dp`，底部 `18dp`，内边距 `13dp/12dp`，圆角 `10dp`，背景约 78% 深色并使用 blur。
4. 竖屏快捷槽最多 7 个，默认：速度、画面比例、旋转、PiP、全屏、锁定、更多（即 `PlayerControlSurface.PORTRAIT_BOTTOM` 的默认顺序，「更多」在最右；口径修正：原默认列表写「速度、播放顺序、画面比例、旋转、PiP、全屏、锁定」，与代码不符）。**放得下时优先铺满整行**：只要按钮本体放得下（`48dp × 数量 ≤ 行宽`），按钮间距就按剩余宽度压缩（可以压到很小），不因为间距不足就退化成横向滚动；只有连按钮本体都放不下时才横向滚动。
5. 「更多」是竖屏底栏最右的托盘开关（`PlayerControlId.MORE`，图标复用 `YingLiIcon.OVERFLOW` = Tabler `DotsVertical`）：点击在按钮行上方展开工具托盘，托盘内容与交互见 §5.14；横屏顶栏的常驻溢出菜单继续承担设置、播放列表、视频信息等入口。锁定按钮图标表达状态（未锁定 `LockOpen`、锁定后 `Lock`），文案表达动作（「锁定屏幕」/「解锁屏幕」）。
6. 设置、播放列表和布局编辑从底部进入，最大高度 72%；视频信息 Dialog 宽度为窗口减 `28dp`。

### 4.3 全屏与方向

- 窗口状态由 `WindowPlaybackGateway` 承载（`setFullscreen` / `requestOrientation` / `WindowPlaybackState`），`MainActivity` 只实现它并回传真实结果：系统栏可见性用 `WindowInsetsController`，方向用 `requestedOrientation`，真实方向来自 `onConfigurationChanged`（Manifest 已声明自行处理方向/尺寸变化），PiP 状态来自 `onPictureInPictureModeChanged`。UI 状态镜像网关状态，不做乐观更新。
- 全屏语义（`FullscreenPolicy`，可单测）：竖屏播放横版视频 → 请求横屏全屏；竖屏播放竖版视频 → 保持竖屏并让画面按 cover 填满；横屏或视频形状未知 → 只隐藏系统栏，不改方向、不裁切；退出全屏 → 方向回到跟随系统并取消填满。
- 填满由视图层（`VideoRotationStage` 的等比放大）实现并带动画，**不修改用户的画面比例偏好**，因此退出全屏时比例天然恢复，没有裁切状态需要回滚。用户已选 `FILL`/`ORIGINAL` 时不再叠加填满。
- 方向或系统栏切换失败时走统一瞬时反馈通道提示，且不改变已确认状态（方向失败时不会只藏系统栏）。
- 离开播放页（含 Vault 播放页）时必须调用 `exitFullscreen`，避免全屏泄漏到其他页面。
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
- Seek Clamp 到完整时长（下界 `0`）；**AB 生效时不钳制到 `[A,B]`**（D8-A，见 §5.11）。
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

拖动期间记录 `wasPlaying`；若用户主动暂停，则松手不自动恢复。Seek 预览时间显示在滑块上方。**有 A/B 时主进度仍以完整媒体时长为坐标系，允许拖到区间之外**（D8-A，见 §5.11）；进度条上的 A–B 只是标记与区间高亮，不是可拖动范围。

### 5.6 播放顺序

Demo 四态为顺序、随机、列表循环、单曲重复。正式实现：

- 建立 `PlaybackOrder` 和纯 Kotlin `QueueNavigator`。
- 快捷按钮循环四态；设置面板使用单选控件；二者双向同步。
- 随机避免连续同项，并保留会话历史以支持上一项。
- 单曲重复只影响 Ended；手动下一项仍切换。
- 设置写入 DataStore，坏值回退 `SEQUENCE`。

### 5.6.1 画面旋转

画面旋转是**视图层变换**，不改播放管线，也不请求系统方向；窗口方向由全屏承担（见 §4.3）。

- 状态四态：`0° / 90° / 180° / 270°`（顺时针）。底栏快捷按钮点击循环 +90°；`向右 180°` 与 `向左 180°` 视觉结果相同，因此只保留一个 `180°` 状态，设置面板按 `0°`、`向右 90°`、`180°`、`向左 90°` 给出四个直达项。
- 与播放顺序一致：快捷按钮循环、设置面板单选、二者双向同步，切换后走统一瞬时反馈通道显示「画面旋转：向右 90°」。
- 按媒体持久化（`TrackPreferenceRepository`，与倍速/比例同一记录）；老记录缺字段回退 `0°`。Vault 播放没有按媒体偏好，回到 `0°`。
- 旋转 90°/270° 时取景框宽高互换后再旋转，保证画面在该角度下按 contain 重新取景；切换必须有动画，且动画过程中旋转后的画面外接矩形不得超出画布（必要时等比缩小）。
- 动画在 Compose 层（`graphicsLayer`）实现。SurfaceView 的内容由系统单独合成、不跟随视图变换，因此**只要是旋转状态（含动画过程中），播放输出切到 TextureView**；静止在 0° 时仍用 SurfaceView，保留 HDR 直通与功耗表现。切换输出类型会重建 PlayerView，需要正确释放旧的 surface 租约，且截图路径必须同时支持 TextureView（`PlayerView` 只公开 SurfaceView 访问器，需在子树中查找）。

### 5.7 播放速度

领域支持 `0.5、0.75、1、1.25、1.5、2、3、4` 倍，共 8 档。正式实现：

- 倍速按钮图标用 `BrandSpeedtest`，读屏文案为「播放速度：<当前档>」；档位文案统一由 `PlaybackSpeed.displayLabel()` 生成（整数档不带小数：`1x`、`2x`）。
- 点击**在底栏按钮行内就地展开滑杆**（`PlayerPanel.SPEED`），不叠浮层、不增加高度：倍速按钮留在原位并显示**实时数值**，它之后的按钮暂时隐藏，滑杆占据那段宽度（长度＝"第二个按钮最左侧到最后一个按钮最右侧"，横屏跨组时含组间距）。进度条与时间仍然可见，画面零遮挡。
- 滑杆是**手写的 48dp 高胶囊轨**（`PlayerSpeedRail`，与底栏按钮同高）：胶囊底 + 从左起的进度填充（右端到旋钮右缘再留 10dp 间距，旋钮明显嵌在填充条里）+ 8 个刻度（**直径 5dp 的小圆点**，排在胶囊中线上）+ 36dp 圆形旋钮（半径 18dp，保证在 48dp 胶囊任意位置都不越出圆角）。不叠浮层、不增加底栏高度，进度条与时间仍然可见。
- **刻度与旋钮必须颜色可分**：刻度用 `controlPrimary @ 40%`（比旋钮暗一档，形成"刻度 + 把手"的层次）；当前档位（拖动中为最近档位）的圆点改用旋钮的"内容色"（画布色）画在**旋钮之上**，形成明显的凹点，一眼看出滑块落在哪个刻度点。
- 手势是**单个手写手势循环**（`awaitEachGesture`）：按下即把旋钮移到触点并持续**绝对跟手**（不会与手指错位），抬起才吸附并提交；因此"点一下"和"拖一段"是同一套逻辑，也不与父级手势竞争。手势协程通过 `rememberUpdatedState` 取最新回调，父级也必须让预览状态跨重组保持同一实例，否则拖动时按钮数值不会实时更新。
- 触摸映射按旋钮中心可达区间 `[radius, width - radius]` 计算（与刻度位置一致），点刻度即选中该档；拖动只更新本地预览并让倍速按钮实时显示数值（与进度条"拖动只预览、松手才提交"一致），松手才 `setSpeed`。
- **松手后旋钮中心必须与刻度中心重合**：旋钮位置由 `pendingPosition ?: 已提交档位下标` 推导，点按、拖完、手势取消三条终止路径都会把位置收敛为整数下标；即使命令被拒绝、状态未变，也仍停在刻度上。刻度是小圆点，会被 36dp 旋钮遮住，对齐由这条位置推导保证（旋钮两侧圆点的间距即为可见的核对依据）。
- 点击倍速按钮时胶囊轨有 200ms 入场动画（淡入 + 从按钮一侧滑出）。
- 选中后**保持展开**，方便连续比较；再次点击倍速按钮、点画面收起控件、或系统返回都会收起并恢复快捷槽（返回优先级见 §8，档位条属于"关闭面板/工具"）。
- 竖屏底栏内容宽约 336dp 时，滑杆长度约 278dp；横屏滑杆长度约 220dp，均可拖动到位，不压缩触控区。
- Controller 拒绝时不更新本地显示，走统一瞬时反馈通道提示。
- 每媒体偏好写入 `TrackPreferenceRepository`（`setSpeed` 已实现）；设置面板**不再重复**提供速度分组，避免两个入口。

### 5.8 画面比例

三态 `FIT/FILL/ORIGINAL` 对应原始/裁剪/拉伸。当前 Controller 只有状态接口，`Media3VideoSurface` 还需映射到 `PlayerView.resizeMode`：

- `FIT`：完整显示，允许黑边。
- `FILL`：裁剪填满。
- `ORIGINAL`：MVP 若无法按像素显示，可明确回退 FIT，不能假装生效。
- 常规 Player 与 Shorts 分别持有页面偏好，底层能力可复用。
- 底栏快捷按钮与播放顺序同构：**一次点按循环 适应 → 裁剪 → 拉伸**，图标直接表达当前状态（`AspectRatio` / `Crop` / `ArrowsHorizontal`），不打开面板；与设置面板的比例分组双向同步，切换后走统一瞬时反馈「画面比例：裁剪」。

### 5.9 音轨与字幕

当前代码已经读取 `TrackChoice` 并调用 Media3 TrackSelectionParameters，不能再使用 Demo Toast 占位：

- 音轨按钮只在存在可选轨道时启用；单轨显示当前项或禁用并解释。
- 字幕提供“关闭 + 所有字幕轨道”，选中态有 Check 图标和 selected 语义。
- 列表行最小 `48dp`，标题 `14sp`，语言/格式 `12sp`。
- 成功后写入媒体偏好；切换视频按媒体 ID 恢复。
- 无轨道不是错误，显示空状态，不显示假按钮。

### 5.10 截图

#### 工具胶囊

Demo 包含上一帧、截图当前帧、下一帧、取消；按钮视觉 `42×34px`，截图按钮宽 `52px`。

当前实现的几何与动效（唯一来源是 [ScreenshotControls.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/ScreenshotControls.kt)，数字关系由 `PlayerChromeLayoutMathTest` 钉住）：

| 项 | 当前实现 |
|---|---|
| 胶囊高度 | `64dp`：`PlayerScreenshotCapsuleHeight = PlayerChromeButtonSize(48dp) + PlayerPortraitControlsSpacing(16dp)`。口径修正：本节原写「高度至少 48dp」，与代码不符 |
| 四个按钮（含捕获） | 统一 `PlayerScreenshotCapsuleButtonSize`，它**等值于** `PlayerChromeButtonSize`(48dp)——与工具托盘**同一个常量**，单测直接断言两者相等，任何「顺手换个尺寸」都会红 |
| 按钮间距 | `12dp`（`PlayerScreenshotCapsuleButtonSpacing`）：比托盘行同组按钮的 `PlayerShortcutSpacing`(8dp) 更大，托盘按钮的空隙外侧还有整行空白可以借，而胶囊是一整块容器，同样的间距在里面明显更挤 |
| 内边距 | `(64 − 48) / 2 = 8dp`（`ScreenshotCapsuleInnerPadding`），水平方向取**同一个值**，四周呼吸圈才一致（写死 4dp 会让左右比上下窄一半） |
| 出入场 | `360ms`（`SCREENSHOT_CAPSULE_TRANSITION_MILLIS`）：从右向左滑入 + 淡入，退出反向 |
| 触控 | 每枚按钮 48dp，满足 §3.2 的最小触控区 |

- 为什么是 360ms 而不是底栏三段的 240ms：胶囊是「工具入口」，进出比播放控制更需要从容——与三段淡入同拍时胶囊的横向滑行会显得仓促、和底栏的收起挤在一起；而且胶囊比底栏按钮大一圈，同样的位移速度下大控件看起来更快，放慢后速度感才与底栏一致。辅助带的「退出留位窗口」必须跟着它走（见 §5.14 的 `capsuleBandHeld`），否则带子会在胶囊还没滑完时提前塌掉。**不要为了「跟底栏同拍」把它改回 240ms。**
- 从右侧滑入：截图入口位于工具托盘最右端（托盘按 `TOOLS` 槽位反序渲染，`SCREENSHOT` 显示在最右），按整行宽度从右滑入，视觉上就是「从这个按钮的位置滑出来」；改成从底部滑入，起点与刚才点的按钮毫无关系。
- 材质与底栏按钮**同源**：同一份 `PlayerChromeControlFillAlpha` / `PlayerChromeControlBorderWidth` / `PlayerChromeControlBorderAlpha` / `PlayerChromeCapsuleShape`，帧数胶囊与它共用同一个 `ScreenshotCapsuleSurface`——胶囊与按钮同屏出现，各写一套 alpha 必然出现色差。
- `PlayerScreenshotCapsuleHeight` 与底栏的 `PlayerAuxiliaryBandHeight` 都是**计算属性**（`get()`）而不是顶层 `val`：两者跨文件互相引用（胶囊高度要读底栏文件里的段距，底栏文件又要读胶囊高度），顶层 `val` 会构成初始化环，实测会算出 `16dp` 这种「说不出理由」的带子高度。**不要再改回顶层 `val`。**

#### 交互

1. 打开截图时关闭其他面板（设置面板与 AB 工具）；Ready/Paused/Playing 都允许——`armScreenshot()` 在这三种播放状态下才生效，其余状态（Idle/Preparing/Ended/Failed）直接返回、不进入截图模式。
2. **进入截图模式默认暂停播放**：与逐帧步进共用**同一条**暂停路径（`PlayerViewModel.pauseForFrameStepping` → `PlaybackSessionCommand.Pause`），不另写一份。理由：播放中位置每 `250ms` 才回报一次，而一帧只有 `33ms`（30fps）——画面一直在动时「当前帧」没有稳定含义，捕获出来的也未必是用户看到的那一帧。恢复由用户按播放键完成。
3. **中央三连（上一个 / 播放暂停 / 下一个）在截图模式激活期间隐藏**：判据是 `ScreenshotUiState.hidesCenterTransportControls()`（`Armed`/`Capturing` 为真；`Preview` 不算激活——那时胶囊已收掉、用户看的是左上角那张小卡）。淡出/淡入时长 `240ms`，与底栏三段同拍（`TRANSPORT_SECTION_TRANSITION_MILLIS`），硬切会让它在画面正中「啪」地消失。这里只加「截图模式下不出现」这一条：横竖屏、锁定、控件自动隐藏等既有条件仍由调用方（`overlay.controlsVisible`、`overlay.locked`）判断。
4. 上一帧/下一帧先暂停，再按**一帧**走到目标：`frameDurationMillisOf(fps) = round(1000 / fps)`（下限 `1ms`，避免高帧率素材四舍五入成 0 而原地不动），夹在 `[0, duration]` 内（第 1 帧再往前仍是 0、最后一帧再往后仍是总时长，不越界不抛错）。帧率优先用**实测帧率**（后台校准派生，见下），拿不到时退回 `34ms` 兜底步长。
5. 连续步进有**锚点**：上一次步进的落点被记住，下一次从它起算（仅当锚点与回报位置相差不超过一帧时才算数；差距更大说明用户拖过进度条或换了媒体，锚点失效、回到真实位置），解决「连点两下只走一帧」。
6. 点击截图调用 `ScreenshotGateway.capture`；禁止因“未播放”拒绝。失败按空帧、权限、存储、只读、未知分类。
7. 捕获中捕获按钮切**等待态**（直接落在 `enabled = false` 上，与项目其余禁用按钮共用同一视觉语言），胶囊保持在场：`Armed` 与 `Capturing` 都算「工具打开」，否则按下的瞬间胶囊会在手指底下消失。
8. 成功后的预览卡从**视频画面区域的右下角**以 `scale(2.4) → 1` 飞入左上角，`420ms`；卡片宽 `116dp`、比例 `16:10`、`1.5dp` 白描边、`7dp` 圆角（详见下面的「飞入起点与轨迹」）。
9. 卡片显示 `3s`，下沿 `4dp` 读条**匀速**缩短：计时器每 `50ms`（`SCREENSHOT_PREVIEW_TICK_MILLIS`）把时间喂给会话，UI 只画 `remainingMillis / 3000` 的比例。
10. 点击卡片展开大图预览（`0.82` 屏宽的图 + 压暗遮罩；退出路径是**点遮罩**与**系统返回键**两条，点图本身不做任何事，返回键先关大图而不是退出播放页），右上角删除按钮以 `200ms` 弹入（`scale(.55) rotate(-18deg) → 1`，直径 `27dp`、错误色）。注意：代码注释里提到的第三种「关闭按钮」在实现中并不存在，只有删除按钮。
11. **删除是真的删文件**：走既有的 `ScreenshotFileGateway.delete`，与「关闭预览」明确区分；成功提示「截图已删除」，删除过的会话此后**不再提示保存路径**；失败按权限/失败分类提示，不撒谎、也不把卡片弹回来。
12. 读条归零 → 卡片消失 + 提示保存路径（`已保存到 %1$s`）；位置为空则不提示（宁可不提示，也不弹半截文案）。展开期间读条定格，收起后按**剩余**时间继续，不重新给 3 秒。
13. 入口在竖屏「更多」托盘（`PlayerControlSurface.TOOLS`）与设置面板的「工具」分组；底栏不再保留截图按钮，截图图标为 `Aperture`（见 §3.4）。口径修正原因：截图与 A-B 循环都是低频工具，底栏那两格让给高频控件，统一收进托盘（见 §5.14）。

```text
ScreenshotState
├── Idle
├── Armed
├── Capturing
├── Preview(displayName, uri, location, remainingMillis, expanded)
└── Failed(reason)
```

#### 飞入起点与轨迹

- **起点 = 视频画面实际渲染区域（不含黑边）的右下角**，由纯几何 `VideoRotationStageMath.pictureBounds` 按 media3 `resizeMode` 口径算出：容器内的 contain（`FIT`）/ cover（`FILL`）/ 固定宽度（`ORIGINAL`）、90°/270° 时舞台宽高互换、`fillScreen` 的 cover 放大、以及画面自身的旋转，最后按画布夹一次。**不是屏幕右下角**——画面有黑边时两者相差很远（1080×2400 画布上的 16:9 画面，右下角在 `y≈1504`，而屏幕右下角是 `y=2400`）。画面宽高比未知时退化为容器右下角：没有依据时不猜比例，也不把起点画到黑边里。
- 起点在**不带 `graphicsLayer` 变换的最外层 Box** 上测量；几何取当前的目标态（旋转角度 / 填满 / 缩放模式），画面旋转动画正好在跑时按终态算（差一帧不影响「从画面角落里飞出来」）。
- **终点 = 卡片静止位置的中心**：屏幕左上角，由 `windowInsetsPadding(safeDrawing)` + 左侧 `PlayerPortraitBarHorizontalPadding`(12dp) + 顶部（**帧号胶囊下方**）`PlayerTopBarContentHeight`(64dp) + 状态栏 inset + `PlayerFrameCounterTopGap`(12dp) + `PlayerScreenshotPreviewTopGap`(12dp) 决定。**不许改成手写 padding 绕过 inset**（那样会压到状态栏/刘海或顶栏按钮上）。
- 轨迹**必须单调**：位置只有唯一一次线性插值（`t` 由同一个 `0→1` 进度量给出），缩放与位移共用这同一个进度量，`transformOrigin` 锚在左上角；起点与卡片几何**都量到才启动动画**，而不是先跑起来再纠正。`ScreenshotPreviewAnimationTest` 从三个互补角度钉住单调性：到终点的距离单调不增、每步位移方向恒定、两个端点精确落位，另加越界进度夹紧与「几何未量到不给轨迹」。
- 这条起点口径是一次实测缺陷（卡片「先向下移动、然后又回到左上角」）的根源修正：此前起点取捕获按钮中心、卡片量的是自身已带变换的 `positionInRoot`、且 `graphicsLayer` 默认变换原点在中心又把平移量再放大 2.4 倍。相关结论见 commit `5632ac8`。

#### 帧数胶囊与后台校准

- **位置：顶部居中，但在顶栏下方**（`windowInsetsPadding(safeDrawing)` + `PlayerTopBarContentHeight`(64dp) + 状态栏 inset + `PlayerFrameCounterTopGap`(12dp)）。口径修正：本节此前写「截图模式下标题段让位、不叠浮层」，那是胶囊还在顶栏槽位里的旧做法；真机截图证实帧号文本一长（`488912 / 802008` 这种）按整屏居中时右端会被顶栏右侧快捷按钮压住。下沉到顶栏下方后，它与顶栏所有按钮在**视觉与点击区域**上彻底分开，**标题段不再需要让位**。宽度上限取整屏的 `PlayerFrameCounterMaxWidthFraction`(0.8)，只作极窄屏/最大字体的兜底，届时按可用宽度反推等宽字号（下限 `10sp`），数字不换行、不省略。
- 文本：`当前帧 / 总帧数`；帧号**从 1 开始**（时间 0 显示「1 / total」，位置等于总时长时显示「total / total」）。需要标估算时给整段数字加前缀 `≈`（字符串资源 `player_frame_counter_approximate`）。
- **估算 → 后台校准**：进入截图模式时先显示估算值（总帧数 = `round(时长 × 帧率)`，至少 1；当前帧 = `round(位置 × 帧率) + 1`，两者都夹在 `[1, total]`）；校准完成后整体切换为**真实样本数**。校准用 `MediaExtractor` **只读容器**统计视频轨 sample 数：不 `readSampleData`、不解码、不把样本拷进 `ByteBuffer`，内存 O(1)。实测帧率由样本时间轴派生：`(N − 1) × 1e6 / (末样本时间 − 首样本时间)`，并**优先于**容器 `Format.frameRate` 用于帧号与逐帧步进——两者若各取一个值，帧号与步长会互相漂移。
- `≈` 的出现条件只有一条：**正在校准 且 模型估算耗时 ≥ `FRAME_CALIBRATION_NOTICE_THRESHOLD_MILLIS`（600ms）**（`frameCounterPending = calibrationResult is Calibrating && frameCalibrationNoticeRequired(...)`）。阈值取 600ms 的理由：胶囊入场动画本身有 `360ms`，**比动画还快的扫描用户根本看不见**（实测 66 MiB / 1 249 样本 104ms、3.6 MiB / 191 样本 21ms），加提示只会闪一下；而 1.83 GiB 以上实测 2.6–3.1s，用户确实在等。校准失败/跳过保持估算值，但**不留**永久 `≈`（「没有精确值」与「正在校准」不是一回事）。
- `≈` 与两个数字取自**同一份**校准状态：`PlayerViewModel` 在一次合并结果里同时算出 `frameCounter` 与 `frameCounterPending`，不存在两套判定。
- **容器帧率缺失/为 0（或负、NaN）时整个胶囊不显示**（`frameCounterStateOf` 返回 `null`）：宁可不出这个胶囊，也不显示编造出来的帧号；这也与 `frameRateLabel` 把 `<= 0` 一律视为不可用的口径一致。唯一例外是校准值在场——此时总帧数是事实，缺容器帧率也能算出帧号。
- 后台校准**只对本地源**执行：网络源标为 `Skipped`（为了帧号去下载整段视频不可接受），这不是失败，界面继续显示估算值；结果随会话复位，退出截图模式、换媒体、页面销毁都会取消（可中断取消的机制与设备级验证见 `19`）。

**判据：估算值与校准值什么时候会不同（不要再当 bug 排查）**

- CFR 素材上 `真实样本数 == round(容器时长 × 容器帧率)`，所以两个口径**逐位相同**，界面上唯一可见的变化就是 `≈` 消失。真机复核：40s / 3000fps / 120 000 样本 `≈ 14506 / 120000` → `14506 / 120000`；3600s / 29.97fps / 107 893 样本 `≈ 134 / 107893` → `134 / 107893`。
- **真正会不同的是**：① 容器帧率缺失 / 为 0 / 写错；② 容器时长与视频轨时间跨度不一致。判别性素材（视频轨 40s / 120 000 样本 + 400s 音轨、容器时长 400s）：估算 `≈ 13918 / 1200000` → 校准后 `13918 / 120000`，总数按真实样本数整体替换。
- Media3 对 MP4 报的是**真实**帧率（3600s 的容器报 `29.97`，没有被舍成 `30`），所以**分数帧率本身不会造成两个口径的差异**。这条曾被误判为「分数帧率导致估算偏差」，按代码与实测结论写入。

**校准耗时模型（用途只是「要不要标 ≈」的启发式，不是进度条）**

- 每样本成本**不是常数**，随样本数按约 `样本数^0.75` 上升（实测 5 千 19µs → 2 万 38µs → 5 万 77µs → 12 万 164µs → 20 万 271µs）。
- 模型 = `max(15ms + 样本数 × 18µs × (样本数/5000)^0.75 + 容器 MiB × 1.0, 15ms + 样本数 × 18µs + 容器 MiB × 1.0)`（`estimatedFrameCalibrationMillis`，且幂律项在估算值超过 `1000ms` 之后**单独**承担：1 秒以内的短扫描上旧线性项实测更准，而短扫描够不到 600ms 阈值，所以这个分界不影响「要不要标 ≈」）。
- 时长/帧率缺失时退化成「只用字节数」那一项；两者都拿不到就返回 `null`（拿不到就不打扰用户）。
- 系数标定只在**一台设备**（Xiaomi 25102RKBEC / Android 16 / API 36）上做过，**换 ROM / 换芯片平台必须重测**；已知不覆盖「样本载荷把数据本身撑大」的形态（8 KiB/样本 → 实测 287ms、模型 144ms）。完整实测表、冷热/IO 竞争方差与错误分布见 [`19-player-implementation-progress.md`](19-player-implementation-progress.md) 的「帧号后台校准」「校准耗时模型：上界重标定」两节，此处不重复。

#### 逐帧步进的帧精确 Seek（UI 侧口径）

- 跳转精度的判定规则、引擎所有权与控制通道见 [`17-playback-architecture-refactor-spec.md`](17-playback-architecture-refactor-spec.md) §9.4（域层唯一判定点 `seekPrecisionFor(duration, frameAccurate)`）。本节只写 UI 侧必须遵守的两条：
  - 进入截图工具时把共享策略置为 `FRAME_ACCURATE`（截图工具激活期间**一律精确跳转**），退出时恢复 `defaultSeekPrecision(当前时长)`（`≤120s` 精确 / 更长最近关键帧）；切换只改跳转参数，**不重新 prepare、不重设媒体**，播放不被打断。
  - 逐帧步进先暂停再精确 Seek：`±1000/fps`（实测帧率优先）、夹 `[0, duration]`、带连点锚点，见上面的「交互」第 4、5 条。

### 5.11 AB 循环

胶囊包含 A、B、清除、关闭**四枚定尺寸圆钮**（`PlayerChromeIconButton`，尺寸 `PlayerScreenshotCapsuleButtonSize` = `PlayerChromeButtonSize` = `48dp`）：**按钮里不放时间**（文字会把圆钮撑成椭圆，`AbLoopControls.kt:39-55`），A/B 用 `LETTER_A` / `LETTER_B` 字形，「是否已设置」由 `filled` + `selected` 表达；时间数值一律交给下面的**读数条**。关闭键读屏文案「关闭」、清除键「清除」（**不要写成"取消"**，关闭 ≠ 取消见本节后文）。状态为：

```text
Off -> SetA -> SetB(active) -> DragA/DragB
任意阶段 -> Clear -> Off
```

**产品口径（用户裁决，不得改写）：「帧精确、低延迟循环」。** 不写"无缝"；用户可见说明是
「循环点可能出现一次短于一帧的解码抖动；这不是严格无缝循环」（原 `docs/20` §3.4）。阈值与已知未达标项见
[`19-player-implementation-progress.md`](19-player-implementation-progress.md) 的「A-B 循环方案阶段 1 / 阶段 2」一节，
本节不重复数字。

**播放语义（D8-A，用户裁决保留）**——这是本节唯一权威口径，与旧规范里"AB 激活后所有 seek 限制在 `[A,B]`"相反：

| 场景 | 行为 |
| --- | --- |
| A 之前 | **正常播放**，不做任何裁剪或跳转 |
| 抵达 A | **进入循环**：播到 B 时由引擎精确回跳 A（不依赖 UI 轮询） |
| 用户在循环期间拖到 `[A,B]` 之外 | **允许，不钳制**；拖出区间后按新的自然播放路径重新判定边界 |
| 只设了 A（未设 B） | 只有标记，不循环 |

（当前实现里 ViewModel 的手势、`seekBy` 与底栏进度条的拖动预览都不再对 AB 钳制，`AbLoopLimiter.clamp`/`seekBy` 已在阶段 1 删除；
详见本节末"实现现状"。）

交互与几何**实现规格**（几何/材质/动效一律与截图胶囊**同源**，不再有第二套常量；当前落地进度见本节末"实现现状"）：

**进度区的分层与竖直分寸链（本批口径；来源 `docs/21` 与 `AbLoopMath.kt:34-55`，落地状态见本节末「实现现状」）**：

- **绘制顺序**（同一批像素上从下到上；`AbLoopMath.kt:38-39`）：

  `轨道底 → 播放进度填充 → scrub 热区 → 区间条 → 竖线 + 徽标 → 夸大虚线 → 端点热区 → 滑块（最后）→ 刻度文字`

  其中"轨道底 / 进度填充 / 叠加层 / 滑块"这一段的真实层序由滑杆一处写死（`YingLiControls.kt:503-528`：① 轨道底 → ② 进度填充 → ③④ `trackOverlay` → ⑤ 滑块），滑块**永远最后画**（它是"现在在哪"的唯一指示，不许被任何叠加层压暗）；`scrub 热区`是滑杆自己的手势区（`YingLiControls.kt:464-499`），`刻度文字`是进度行左右两端的时刻。
- **竖直分寸链**（以轨道中线为 0，向上为负；唯一的计算入口是 `abMarkerLayout`，绘制侧与端点热区都调它、**不许**自己写"中心 ± 半径"，`AbLoopMath.kt:34-56`、`:372-417`）：

  | 段 | 区间（dp） | 常量 | 取值 / 依据 |
  | --- | --- | --- | --- |
  | 徽标 | `-30.0 .. -12.0` | `AbMarkerDiameter` | `18dp`；中心抬高 `AbMarkerTopOffset = 21dp`（`AbLoopMath.kt:98-106`、`:188-219`） |
  | 竖线 | `-12.0 .. -2.0` | `AbMarkerConnectorWidth` | `1.5dp`：徽标下沿 → **轨道上沿**，把徽标"钉"在它所属的那条轨道上（`AbLoopMath.kt:419-427`、`:403-405`） |
  | 区间条 | `-8.5 .. -5.5` | `AbRangeBarThickness` | `3dp`（**剖面口径**：剖面右侧标注的"5dp"量的是"徽标下沿 → 循环层上沿"的**间距**、**不是条厚**，几何 `7px ÷ 2.4 ≈ 2.9dp` 与 3dp 吻合，见 `docs/21` §2.1/§9.3）、圆角 `AbRangeBarCornerRadius = 1.5dp`（半厚圆头）；落在"徽标下沿 → 轨道上沿"这段空隙的**正中**（`AbLoopMath.kt:59-75`、`:406-413`） |
  | **标记组总高** | `-33.0 .. 0.0`（**三部分之和**，不是绘制包围盒） | `AbMarkerGroupHeight` | **`33dp`** = `徽标 18 + 间隙 12 + 条厚 3`（`AbLoopMath.kt:108-131`） |
  | 夸大虚线 | 以**轨道中线**为锚上下各 `12dp`（`-12.0 .. +12.0`） | `AbRangeBandHeight` | `24dp` = `YingLiSliderTrackHeight × 6`（夹在 `YingLiSliderTrackHeight .. 32dp`）；**不跟着徽标上移**（`AbLoopMath.kt:157-168`、`:414-415`） |
  | 标记层画布 | 居中于滑杆那条 `48dp` 触控带 | `AbMarkerLayerHeight` | `64dp`：**中线以上 32dp**，余量 2dp（`AbLoopMath.kt:133-144`） |
  | ——— | 轨道中线 `0`；轨道厚 `4dp`（上沿 `-2.0`） | `YingLiSliderTrackHeight` | 竖线的下端、区间条所落空隙的下沿都是这个**轨道上沿** |

  **"总高 33dp"与"绘制包围盒 30dp"必须分开读**（`AbLoopMath.kt:108-131`）：`33dp` 是"徽标 + 间隙 + 条厚"的**三部分之和**，回答"这三件东西合起来是什么量级"（与 demo 文字里的"约 30dp"同一量级 —— demo 只给叙述、没有加总式）；而实际画出来的**包围盒是"轨道中线以上 30dp"**（`AbMarkerTopOffset` 21 + `AbMarkerRadius` 9，即徽标顶边到中线）。两者差一个条厚的原因见下一条**记号澄清**：那个 `12dp` 的间隙量的是"徽标下沿 → **轨道中线**"，`3dp` 的区间条落在这一段空隙**内部**，所以三部分相加会把条厚多算一次。承载它的是自带画布 `AbMarkerLayerHeight = 64dp`：中线以上 `64 / 2 = 32dp > 30dp`，余量 2dp —— 标记组**明确溢出**滑杆那条 `48dp` 触控带（触控带上沿只到中线以上 24dp）**6dp**，"徽标允许向上溢出、不许被裁"因此由**布局**给出，不是靠把标记组压小、也不靠指望某个祖先不裁剪。
- **记号澄清（`AbRangeBarToMarkerGap` 的语义）**：它量的是"**徽标下沿 → 轨道中线**"（`12dp`），**不是**"徽标下沿 → 区间条"。区间条落在这段空隙**内部的正中**（`-8.5 .. -5.5`），因此"间隙"与"条厚"**不是相邻的两段、不能相加**成"徽标到条的距离"（`AbLoopMath.kt:77-96`、`:402-413`）。
- **徽标尺寸的独立依据**：`AbMarkerGlyphSize`（= 滑杆圆钮直径 `14dp`）+ 两侧留白 `2dp × 2` = `AbMarkerDiameter = 18dp`；**有意偏离 demo 的 `12.5dp`** —— 字母图标的墨高是 `0.75 × 14 = 10.5dp`，必须不低于项目最小可读文字 `10sp` 的墨高（约 `7.1dp`），旧 `11dp` 圆点装字母只剩 `5.25dp`（`AbLoopMath.kt:188-210`；冲突登记见 `docs/21` §1.2）。
- **两个阈值都由徽标尺寸推导**：`AbMarkerMergeThreshold = AbMarkerDiameter + AbMarkerMinGap = 18 + 10 = 28dp`，而 `MIN_AB_RANGE_WIDTH` **就是**这个数（同一个等式既回答"两端还分不分得开"，也回答"这一段画多宽"；`AbLoopMath.kt:259-291`；`abRangeGeometry` 内部用 `markerDiameterPx + minMarkerGapPx` 复算同一个等式，见 `:579-582`）。
- **区间条语义（只取两档）**：仅 A 时 **28%**（`AbRangeInactiveAlpha`）且起止按 `min(aX, 播放头)` 排、**随播放头延展**（播放头在 A 之前时条画在 `[播放头, A]`，并保证不小于一个条厚）；A/B 设全时 **100%**；demo 里属于编辑态的"**整组 50% 休眠**"**不引入**（常量 `AbLoopMath.kt:429-441`；仅 A 分支 `:588-618`；绘制 `PlayerTransportControls.kt:1038-1045`）。
- **仅 A 时另画播放头幽灵竖线**：宽 `AbRangeGhostHeadWidth = 1.5dp`、alpha `AbRangeGhostHeadAlpha = 0.55`，落在**夹取后**的播放头上（`AbLoopMath.kt:443-459`、`:593-617`；绘制 `PlayerTransportControls.kt:1047-1056`）。
- **区间外压暗（保留）**：`AbRangeOutsideAlpha = 0.55`、下限 `AbRangeOutsideAlphaFloor = 0.30`；用 `BlendMode.DstOut` **只覆盖区间外**（区间内一个像素都不碰），并**跟随渲染后（可能被最小宽度放大）的区间**（`PlayerTransportControls.kt:918-945`（`AbRangeDimOverlay`，左右边界取 `geometry.startPx` / `endPx`）、`:1230`（`DstOut` 源 = `1 − AbRangeOutsideAlpha`））。它与区间条**语义不重叠**：压暗回答"区间外不看"，区间条回答"区间在哪 / 是否激活"（`AbLoopMath.kt:308-336`、`:54-56`）。
- **夸大（防撞）**：最小可视宽度 `28dp` + 合成一枚**合并块**（两半各放一个字形框，中间一道 `1dp` 缝，缝取**反色**）+ 夸大时区间上下沿走**虚线**（`AbLoopMath.kt:275-291`、`:562-661`；绘制 `PlayerTransportControls.kt:1058-1077`（上下沿虚线）、`:1097-1130`（合并块 / 缝 / 徽标））；demo 的"**真实位置刻度 / 横向虚线帽**"**不采纳**（提交 `1e4bf51`）。
- **读数条（AB 的数值唯一落点）**：文案由纯函数 `abReadoutSegments` 给出四态 —— 未设置 `未设置循环 · 点 A 在播放头落点`；仅 A `A 06:12 — B 待落点`；A/B 设全 `A 06:12 — B 09:48 · Δ 03:36 · 循环 ×12`。区间分隔符是**长破折号 `—`（U+2014，不是 `→` 也不是 en dash）**、次级分隔符 `·`（U+00B7）；`Δ` 后面**与两端时刻、进度行同一份** `formatDuration`（不足 1 小时 `mm:ss`，**≥1 小时沿用全站补零口径**成 `Δ 01:35:00`，**有意偏离 demo 的不补零**）；`循环 ×N` **附在末尾**（`AbLoopControls.kt:175-268`、`:205-206`）。字号上限 `13sp`、下限 `PlayerChromeTextMinFontSize = 10sp`（项目唯一的"仍算可读"下限），按可用宽度反推；行高天条 `abReadoutBandHeight = max(16dp, 主题字号 × 1.15 × fontScale)`（`AbLoopControls.kt:303-336`、`:416-435`）。
  **位置口径偏离（如实记录；理由已由"结果描述"改为算式）**：demo 与 `docs/21` §3.1 要求读数条住**胶囊首行**，本批经算式判定**放不下**，因此仍在**进度区第二行**，显示判据从"设过点"改为"AB 工具打开"（据此未设置态也显示引导文案、不留空白）。算式（逐项可在代码核对）：胶囊高 `PlayerScreenshotCapsuleHeight = 64dp`，而它与截图胶囊**同格同高是不变量**；胶囊内部已经住着一排 `PlayerChromeButtonSize = 48dp` 圆钮 → **仅剩 `64 − 48 = 16dp`**；而读数行的行高是 `max(AbValueTapTargetHeight 20dp, abReadoutBandHeight)`（`AbLoopControls.kt:360`）→ **1× 字号**：`abReadoutBandHeight = max(16dp, 14sp × 1 × 1.15) = 16.1dp` ⇒ 行高 `20dp` ⇒ `48 + 20 = 68 > 64`；**2× 系统字号**：`14 × 2 × 1.15 = 32.2dp` ⇒ 行高 `32.2dp` ⇒ `48 + 32.2 = 80.2 > 64` → **两档都放不下**。要搬必须先裁决"**AB 胶囊允许比截图胶囊高**"（并把 `PlayerScreenshotCapsuleHeight` / `PlayerAuxiliaryBandHeight` 的高度账一起重算），或下调本节的 `32 × 20dp` 热区天条与 `abReadoutBandHeight` 这条行高天条 —— 两者都是 **docs 级口径**，不是任何单批能改的。判定与证据见 `docs/19`。
- **交互**：读数条里 A / B 两个数值各是**独立点击目标**（热区 `AbValueTapTargetWidth × AbValueTapTargetHeight = 32 × 20dp`，点按跳到该端点；`AbLoopControls.kt:341-414`）；点轨道上的**徽标**同样跳该端点（裁决 U6，**已落地**：`AbMarkerTapTargets` 用与绘制侧**同一份**几何落点，`AB_MARKER_POINT_A` / `AB_MARKER_POINT_B`，`PlayerTransportControls.kt:1154-1220`、挂载点 `:588-599`；它必须是那一格的**最后一个**子节点，并在命中时 `consume()` 掉这次 down —— 滑杆对"已被消费的 down"直接放手）；**轨道空白处点按 = seek**（滑杆在 down 时即 seek，`YingLiControls.kt:464-483`）。用户发起的跳转一律走 `USER`，**不得**用 `AB_LOOP` / `AB_ACTIVATION` 两档（`PlayerTransportControls.kt:650-653`）；滑杆对"已被上层叠加层消费的 down"必须放手，否则同一次点按会先跳端点、再被 down-seek 覆盖（`YingLiControls.kt:466-474`）。
- **域层规则（B 侧）**：`B = max(播放头, A + 1s)`（`AB_POINT_B_MIN_GAP_MILLIS = 1_000L`）→ **帧吸附** → **夹到片长** → 仍不足一帧则**拒绝**并由会话回流量成用户可见提示；A 侧保留"压过 B **互换**、`== B` **拒绝**"（`PlaybackSessionContracts.kt:198-300`）。
- **设完 B 后跳回 A（保留，`SeekOrigin.AB_ACTIVATION`）**：区间刚被设全时由引擎**先精确 seek 到 A、再武装**边界检测（顺序不可交换；`PlaybackSessionRuntime.kt:533-538`、`PlaybackSessionContracts.kt:371-380`）。这是本项目**有意比 demo 多做的一步**：没有它，"B 恰好设在播放头处"时循环永不启动（现场与前后对比见 `docs/19` 阶段 1/2「缺陷 2」）。
- **真横屏**：AB 胶囊打开时**隐藏中央三连**（与截图工具**对称**，判据合并为一条纯函数 `hidesCenterTransportControlsForTool`），修掉真横屏下"底栏长到竖直中线、被中央播放键压住"（`PlayerScreen.kt:472-482`、`ScreenshotControls.kt:107-110`；instrumented 用例 `PlayerScreenStateTest.landscapeAbCapsuleHidesTheCenterControls`）。
- **不采纳边界（明确写出）**：**编辑态（编辑会话）、拖拽端点、拖动时瞬时浮签、整组 50% 休眠、demo 的"真实位置刻度 / 横向虚线帽"**。前三条的细节与来源完整记录在 `docs/21` §5；后两条的裁决与依据见 `docs/21` §9「采纳状态」，本节不重复。

- **入口三处同一状态源**：竖屏「更多」工具托盘（主）、设置面板「工具」分组、顶栏溢出菜单；三处读同一份会话状态（`docs/17` §13.2），**打开动作也收在同一条路径**（`PlayerViewModel.openAbTool()`）。
- **托盘/顶栏按钮**：图标 `a-b-2`（`YingLiIcon.AB2`，Tabler 库里的**契约名**是 `a-b-2`、生成到代码里的**属性名**是 `AB2`——已解包 `icons-tabler-0.1.0-local.1.aar` 核对，写成 `Ab2` 编译不过）；循环生效时**实心**（激活态），并把该状态写进语义（读屏念"已选中"，测试用 `assertIsSelected` 断言——颜色本身既读不出也测不了）。
- **`REPLAY` 不删除**：`AB2` **不是** `REPLAY` 的改名。`REPLAY` 是"刷新/重播"语义（首页卡片、重试、撤销、重新播放都在用它，映射 `ti-refresh`），两者只是曾经共用一个字形；AB 改用 `AB2` 之后两条语义各有一个入口。（`docs/20` T3.1 原写"删除 `REPLAY`"，按实现修正为保留。）
- **AB 胶囊占用底栏"辅助带"**，与截图胶囊**同一格同源**：截图胶囊与 AB 胶囊渲染在**同一个槽**（`AuxiliaryToolCapsuleSlot`）里，由纯函数 `auxiliaryToolCapsule(abToolOpen, screenshot)` **只选一枚**（同一帧的中间态归 AB —— 用户刚点的就是它），因此两枚胶囊的**竖直带、水平起点、时序逐像素同源**（instrumented 断言"顶边与高度一致、且完整落在 `AUXILIARY_BAND` 内"，见 `docs/19` 阶段 3）。几何与材质**没有第二套常量**，全部复用截图胶囊那一份：

  | 项 | 取值 / 常量 | 说明 |
  | --- | --- | --- |
  | 胶囊高度 | `PlayerScreenshotCapsuleHeight` = `64dp` | = `PlayerChromeButtonSize`(48dp) + `PlayerPortraitControlsSpacing`(16dp) |
  | 按钮尺寸 | `PlayerScreenshotCapsuleButtonSize` = `PlayerChromeButtonSize` = `48dp` | **最小触控高度**（§3.2），不是固定宽度；关闭圆钮也用同一个常量 |
  | 按钮间距 | `PlayerScreenshotCapsuleButtonSpacing` = `12dp` | **没有紧凑档常量**：可用宽度不够时由 `capsuleInnerPadding(maxWidth, buttonCount)` **一处**收窄内边距（`ScreenshotControls.kt:603-619`），不引入第二套间距 |
  | 内边距 | `ScreenshotCapsuleInnerPadding` = `(64 − 48) / 2` = `8dp` | 水平方向优先取同一个值（四周呼吸圈一致）；收窄也走同一个 `capsuleInnerPadding(...)`，上界就是 `8dp` |
  | 出入场 | `SCREENSHOT_CAPSULE_TRANSITION_MILLIS` = `360ms` | 从右滑入 + 淡入、行内居中；退出反向（与截图胶囊同一时长与轨迹） |
  | 材质 | `PlayerChromeControlFillAlpha`(0.10) 底 + `PlayerChromeControlBorderAlpha`(0.12)/`PlayerChromeControlBorderWidth`(1dp) 描边 + `PlayerChromeCapsuleShape`（全圆胶囊） | 由 `PlayerChromeCapsuleSurface` 统一提供，与底栏圆钮、截图胶囊**同源**，不写第二份 alpha |
  | 占用满高 | `PlayerAuxiliaryBandHeight` = `PlayerScreenshotCapsuleHeight` + `PlayerPortraitControlsSpacing` = `80dp` | 与托盘行共用一格；高度怎么变见下面"辅助带高度"与 §5.14 |

- **辅助带高度的两条判据（阶段 3 落地，"终值"与"怎么过去"分开）**：**终值**由 `playerAuxiliaryBandHeight(bandHold, trayExpanded)` 给出（胶囊在场或托盘展开 → 满高 `80dp`，否则 `0dp`），**"怎么过去"**由 `auxiliaryBandTransition(bandHold)` 给出 —— **胶囊在场（含它滑出屏幕的 360ms 留位窗口）→ `SNAP` 瞬时**；**托盘开关、以及胶囊滑出后的回位 → `ANIMATE`（240ms 动画）**。这两条判据**同时避免**两条历史回归：只有动画时胶囊会跟着带子往上滑（**"胶囊竖直跳变"**）、全都瞬时则托盘开关会让进度行**瞬移**（**"进度行瞬移"**）；合成单一 `Animatable` 后两种成因各自拿到自己的变化方式，谁也不会污染谁（详见 §5.14、§12）。
- **辅助带高度的一条不变量：带子用 `requiredHeight`，不被 `Column` 剩余空间静默夹小**（提交 `22ed984`）：带子高度**只能**由 `auxiliaryBandHeight`（终值或动画值）给出，**不允许**被"底栏还剩多少空间"悄悄夹走。根因（真机取证）：原实现用 `height(...)` 时它会被 `Column` 的剩余空间夹小 —— 真机 `400 × 200dp` 宿主里带子被夹成 `48dp`，胶囊 `unclipped 116..180 / clipped 124..172`（**上下各被切 `8dp`**），而带子自带 `clipToBounds` ⇒ **被裁的是胶囊**，不是带子（用户看不到带子，只看得到胶囊缺一半）。修法：`Modifier.requiredHeight(auxiliaryBandHeight).clipToBounds()`（`PlayerTransportControls.kt:683-697`）—— **底栏放不下时该溢出的是这一格，而不是把工具切成两半**。这与 §5.14 的"托盘行按满高测量（`requiredHeight(PlayerAuxiliaryBandHeight)`）"是同一条规则的两次落点。
- **`testTag`（instrumented 断言的唯一锚点，`PlayerTestTags`）**：`AUXILIARY_BAND`（辅助带那一格，**它的宽度就是胶囊排版决策用的可用宽度**）、`AB_CAPSULE`（AB 胶囊内容，与 `SCREENSHOT_CAPSULE` 二选一）、**`AB_RANGE`（现在只是压暗层，不再承载任何标记）**（`AbRangeDimOverlay` 的 `Canvas`，`PlayerTransportControls.kt:918-945`，tag 在 `:923`；它只做"区间外压暗"，区间条 / 竖线 / 徽标 / 夸大虚线**都不在这个节点里**）、**`AB_MARKER_LAYER`（已挂载，不再是"仅定义"）**（进度条上的 **A–B 标记组**：区间条 / 竖线 + 徽标 / 夸大虚线；`AbMarkerLayer` 的 `Canvas`，`PlayerTransportControls.kt:982-1133`，tag 在 `:997`，挂载点 `:543-551`；它与 `AB_RANGE` 是**两个**节点——压暗必须住在滑杆隔离出来的离屏层里，而标记组要向上溢出滑杆那条 `48dp` 触控带、自带 `AbMarkerLayerHeight`（64dp）画布，所以是滑杆的**兄弟**节点，`PlayerScreen.kt:810-818`）、**`AB_MARKER_POINT_A` / `AB_MARKER_POINT_B`（本批新增）**（轨道上 A / B 两枚徽标各自的**端点热区**，`AbValueTapTargetWidth × AbValueTapTargetHeight = 32 × 20dp`，点按跳到该端点；`BadgeTapTarget`，`PlayerTransportControls.kt:1199-1220`，tag 在 `:1172` / `:1181`；定义 `PlayerScreen.kt:819-821`）、`AB_RANGE_LABELS`（读数条**整行**，`A 06:12 — B 09:48 · Δ 03:36 · 循环 ×12` 的各分段都是这一行的孩子）、`AB_READOUT_POINT_A` / `AB_READOUT_POINT_B`（读数条里 A / B 两个数值各自的点击目标）、`AB_LOOP_COUNT`（计数文本，只在区间完整时挂载）。
- **胶囊里一律是定尺寸圆钮（`PlayerChromeIconButton`）**：宽度恒为 `PlayerChromeButtonSize`（`48dp`）——**内容永远不会改写尺寸**（这正是"文字会把圆钮撑成椭圆"那条真机缺陷的反面，`AbLoopControls.kt:39-55`）。`PlayerChromeTextButton`（弹性宽度文字按钮）**已不再用于 AB 胶囊**；文字只住在**读数条**上。**四枚按钮与胶囊的竖直关系**：`PlayerChromeCapsuleSurface` 必须传 `verticalAlignment = Alignment.CenterVertically`（默认 `Top`，内部是 `Box(contentAlignment = TopStart)`）——不传就是"按钮贴顶、下方空 `16dp`"，这条是用户真机实测到的缺陷（`PlayerTopBar.kt:391-425`，修法见提交 `f0f8955`、证据见 `docs/19`）。
- **读数条的字号模型**：`abReadoutFontSizeSp(availableWidth, text, chipBudget, baseFontSize, fontScale)` 是**唯一**的字号决策点（上限 `min(主题字号, 13sp)`、下限 `PlayerChromeTextMinFontSize = 10sp`；`chipBudget` = 两个数值点击目标先占掉的 `32dp × 数量`）。宽度模型按**读数条自己的字符集**标定（`—` 满字身 `1.0em`、`·` `0.35em`、`Δ` `0.7em`、CJK `1.1em`、其余 `0.56em`，另加 `4%` 安全余量），**不复用**为按钮标签标定的 `playerChromeTextFontSizeSp` 那一套（对 `—` 会估窄、算出的字号偏大，`AbLoopControls.kt:279-336`）。旧的"常规档 ↔ 紧凑档 + `abCapsuleTextLayout` + `labelsFit`"整套随文字按钮一起**删除**（提交 `8fa612f`）。
- **已知取舍（未解决，如实记录）**：宽度 `<320dp` **且**系统字号 `≥2.0` 时，`10sp` 下限 + 两个点击目标的宽度预算仍可能放不下读数条 —— 取"**可读为先**"，不把字号继续往下缩（`AbLoopControls.kt:319-326`；`docs/19` 阶段 3 的未完成项）。
- **A/B 按钮不带时间文字**：按钮只回答"这一端设没设"（`filled` + `selected`），数值一律在读数条上（旧的"`A 00:12` / `B 设置`"那套文案已随文字按钮下线）。**未设 A 时 B 禁用**（没有 A 就没有区间），清除同样以 A 为前置条件（`AbLoopControls.kt:98-114`）。
- **进度条**：以完整媒体时长为坐标系画 **A–B 区间 + 两端字母徽标**（`AbMarkerDiameter = 18dp`，**不再是 `2dp` 标记**）。层序见本节开头的**分层规格**：**区间外压暗**住在滑杆自己的离屏叠加层里（`trackOverlay` → `AbRangeDimOverlay`，它**必须**在那里，`BlendMode.DstOut` 只允许作用在本行像素上），而**区间条 / 竖线 + 徽标 / 夸大虚线**住在滑杆**之外**的兄弟画布 `AbMarkerLayer`（`requiredHeight(AbMarkerLayerHeight)` = 64dp，理由见上面的分寸链）。读数条的行高天条是 `abReadoutBandHeight`（`max(16dp, 主题字号 × 1.15 × fontScale)`），**不再**用 `labelLarge.lineHeight`（该值在本项目可能是 `TextUnit.Unspecified`，且含字体自身的行距余量）；`1` 倍字号下它恰好也是 `16dp`，因此常规机型几何不变（`AbLoopControls.kt:416-435`）。
- **与截图工具的互斥是三分支**（`docs/20` §3.2）：

  | 截图状态 | 打开 AB 工具时 | 关闭 AB 时 |
  | --- | --- | --- |
  | `Armed` / `Capturing` | 先结束截图会话，再打开 AB | 不恢复截图工具 |
  | `Preview`（预览卡/大图共存） | 保留预览卡与倒计时，只退出截图"工具模式" | 不影响预览卡 |
  | `Capturing` → **捕获回调晚到** | 结束会话后，**晚到的捕获结果必须丢弃**（不得进入 `Preview`，不得与 AB 胶囊同时出现）；已落盘的文件保留，但不弹预览卡/保存提示 | 不恢复截图工具 |

- **关闭 ≠ 取消**（D3）：胶囊的关闭键读屏文案是「关闭」、清除键是「清除」（**不要写成"取消"**，否则用户会以为关闭即取消循环）。**关闭胶囊不改变循环**——托盘按钮保持实心、进度条区间与计数继续显示、循环继续跑；只有「清除」才取消区间。"只设了一端后关闭胶囊"保留该点（Q4）。
- **切换媒体清除 AB**；AB 是当前会话状态，不写全局 DataStore。
- 进入 AB 工具**不自动暂停**播放（D6）。
- **不做边界（`docs/20` T4.6，本批明确不做）**：**短视频模式（D12）**不做（AB 只在常规播放页；Shorts 页内的 AB 未列入本批）；**`loopCount` 不持久化**（计数是当前会话状态，切歌/换媒体归零，不写 DataStore、不进历史）；**不按 item 记忆 A/B**（换媒体即清除，不存在"下次打开这段视频还记得上次的 A/B"）。另：双 `ExoPlayer` 预热仅在**产品坚持绝对无缝**时才单独评估（`docs/20` §1.2），**本轮不实现、不排期**。

引擎契约（`events` / `configureAbLoop` / `activateAbLoop` / `currentPositionMillis`）、边界检测机制、generation 与
`SeekOrigin` 语义见 [`17-playback-architecture-refactor-spec.md`](17-playback-architecture-refactor-spec.md) §13.2；
真机证据见 [`19-player-implementation-progress.md`](19-player-implementation-progress.md)。

**实现现状（截至本批提交 `22ed984`；上一批 `1e4bf51` 只写了常量与判定、绘制侧未接线，本批把那一半补齐了；逐项判定仍以代码为准，证据与过程见 `docs/19`）**：

- 已落地：<br>· 会话侧唯一权威的 AB 状态与计数（`AbLoopSession` 一起投影区间与计数）；引擎侧自然边界检测与精确回跳（真机证据见 `docs/19` 阶段 1/2）；<br>· **端点改为字母徽标**（`AbMarkerDiameter = 18dp`，字形 `LETTER_A` / `LETTER_B`）、**区间外压暗**（`AbRangeOutsideAlpha = 0.55` + 下限 `0.30`、`DstOut` 只覆盖区间外、跟随渲染后的区间）、**夸大防撞**（最小可视宽度 `28dp` + 合并块 + 虚线边）与 `formatDuration` 的两档时长（`<1h` `mm:ss` / `≥1h` 补零成 `hh:mm:ss`）（提交 `50a76ea`）；<br>· AB 胶囊四个位置一律**定尺寸圆钮**（`filled` + `selected` 表达"设没设"），胶囊内边距收敛为唯一的 `capsuleInnerPadding(...)`（提交 `8fa612f`）；<br>· 胶囊按钮**竖直居中**（`PlayerChromeCapsuleSurface(verticalAlignment = CenterVertically)`，提交 `f0f8955`）；<br>· **AB 胶囊与截图胶囊同一格同源**（`auxiliaryToolCapsule` 单一判定决定画哪一枚），辅助带高度合成**单一 `Animatable`**（终值 `playerAuxiliaryBandHeight` / 变化方式 `auxiliaryBandTransition`）；<br>· 读数条四态文案 + **可点的 A / B 数值**（`AB_READOUT_POINT_A/B`）+ 行高天条 `abReadoutBandHeight`；<br>· **会话 → 客户端的 AB 状态回流两端共用同一条线格式**（`engine/media3/AbLoopSessionExtras.kt` + `AbLoopSessionCommands.encodeState/decodeState`，提交 `bcd3b8e`）；<br>· 展示层位置推进恢复为**只服务渲染**的 `displayPositionMillis`（`Playing` 相位内每 `250ms` 读实时位置，非播放相位挂起等待；决策点仍直读 `currentPositionMillis()`，提交 `c597599`）；<br>· **真横屏**下 AB 胶囊打开时隐藏中央三连（提交 `1e4bf51`）；<br>· **进度条拖动不再钳进 `[A,B]`**（与本节 D8-A 一致），`AbLoopLimiter` 只剩设点校验。
- **本批（`22ed984`）补齐的绘制接线 —— "常量 / 判定写好了、绘制没画"这一半已经不存在了（逐项可在代码核对）**：**区间条**（`AbRangeBarThickness = 3dp` + `AbRangeBarCornerRadius`，完整区间 `100%` / 仅 A `28%` `AbRangeInactiveAlpha`，起止按 `min(A, 播放头)` 随播放头延展）、**播放头幽灵竖线**（`AbRangeGhostHeadWidth = 1.5dp` / `AbRangeGhostHeadAlpha = 0.55`）、**`64dp` 独立标记层**（`AbMarkerLayer` 是滑杆的**兄弟**节点、`requiredHeight(AbMarkerLayerHeight)`）、**轨道上的端点热区**（`AB_MARKER_POINT_A` / `AB_MARKER_POINT_B`，点按徽标 = 跳到该端点 ⇒ **裁决 U6 已落地**）**均已绘制 / 已挂手势**；`PlayerTestTags.AB_MARKER_LAYER` 已**挂载**（不再是"仅定义"），`AB_RANGE` 现在只是**压暗层**。两个几何各自只有一个来源：竖直 = `abMarkerLayout`（绘制与端点热区共用），水平 = `abRangeGeometry`（标记组 / 压暗层 / 端点热区三处共用，在**组合期**算一次）。接线落点：几何 `PlayerTransportControls.kt:510-531`、标记层挂载 `:543-551`、压暗挂载 `:579-583`、端点热区挂载 `:588-599`、标记组绘制 `:997-1132`、端点热区实现 `:1154-1220`。
- **本批同时修掉的两个布局根因（这才是"原失败用例"的成因，不是断言问题）**：① `abMarkerLayout` 的原公式把区间条放到 `-42 .. -45dp`（**超出 64dp 画布、必被裁**，且与 `AbMarkerGroupHeight` 自相矛盾；原公式按"条在徽标之上再加一个 gap"算）→ 按常量口径统一为 徽标 `-30..-12` / 竖线 `-12..-2` / 条 `-8.5..-5.5`（落在徽标与轨道之间的空隙正中），并由 JVM 用例逐项钉住"**所有笔迹都落在 `[0, 64]` 内**"（`AbLoopMath.kt:372-417`、`AbLoopMathTest`）；② 辅助带原用 `height(...)` 被 `Column` 剩余空间夹小 ⇒ 胶囊被裁（见上一节"辅助带高度的一条不变量"）。
- 已按 instrumented 核对（各轮取证见 `docs/19`）：胶囊与截图胶囊**同格同位**、四枚按钮是 `48dp` 正圆且**竖直居中**、点 A/B/清除/关闭走**真实命令链**（提交 `f0f8955` 的 5 个类 14/14；提交 `8fa612f` 的 6 个类 32/32）、**状态回流往返**（`Media3PlaybackControllerTest.setPointOnTheRealSessionRoundTripsIntoTheClientProjection`，修前红、修后绿，提交 `bcd3b8e`）。
- **本批补跑并转绿（完整数字与逐类分解见 `docs/19`，本节不重复）**：受影响的 4 个 instrumented 类 **31/31** —— `PlayerAbLoopScreenTest` 8 / `PlayerScreenStateTest` 16 / `PlayerAbLoopCapsuleCommandTest` 5 / `PlayerAbLoopExclusionTest` 2；`1e4bf51` 因设备断开而未执行的断言（真横屏中央三连 `PlayerScreenStateTest.landscapeAbCapsuleHidesTheCenterControls`、读数条文案、真横屏档几何）**已在这一轮跑过**，其中原先 **3 个失败用例全部转绿**。
- **未验证 / 待补（如实记录）**：真机全链路观感（与截图、镜像、后台播放共存）仍未补测；另有一处**代码注释**与实现不符（本轮只改文档、未改代码）：`PlayerScreen.kt:808` 仍把 `AB_RANGE` 写成"区间高亮（含两端标记）"，而它现在只是**压暗层**（标记组在 `AB_MARKER_LAYER`）—— 已登记进 `docs/19` 的"当前缺口"。

### 5.12 画中画

- 不支持设备、Vault 安全内容或没有活动媒体时禁用。
- 进入 PiP 不停止 MediaSession；退出后恢复 Overlay 和 Insets。
- 自动 PiP 仅在偏好开启、非安全内容、有当前请求时触发。**该判定只有一份**：域层纯函数
  `shouldAutoEnterPictureInPicture(偏好, 安全内容, 是否有媒体)`。
- **自动进入由系统在切后台瞬间执行**（`PictureInPictureParams.setAutoEnterEnabled(true)`），
  不再走 `onUserLeaveHint` 手动进入：参数必须在用户离开**之前**下发，而"该不该自动进入"由上面三个
  运行期输入决定，因此这三个输入收成一条流、由 `MainActivity` 的收集器驱动唯一的下发入口
  `ActivityPictureInPictureGateway.applyAutoEnter(...)`（参数镜像）。
  依据：采用 auto-enter 时 AOSP 在 auto-enter 路径上不会下发带 `userLeaving` 的 pause
  （`TaskFragment.startPausing` → 直接进入 PiP，进入后以 `userLeaving=false` 补排 pause），
  所以 `onUserLeaveHint` 不会被回调；即使某个 ROM 仍回调它，客户端在 PAUSING 状态下的进入请求也会被
  `ActivityTaskManagerService.enterPictureInPictureMode` 以 `fromClient && PAUSING && isAutoEnterEnabled()`
  早退拒绝。两条合起来 = 手动路径既不会跑、跑了也无效，所以直接删除，不留"看起来在兜底"的死代码。
- **source rect（入场/退出动画起点）是快照**：它取视频输出视图在窗口里的真实矩形，
  必须在几何**量完之后**再下发一次，否则拿到的是"刚挂上、还是整窗大小"的矩形（等于没有起点提示）。
  因此除了策略输入变化，视频输出视图的布局变化也会触发重新下发
  （`Media3PlaybackController.videoSurfaceBounds`）；旋转/进出全屏/回到前台另有 `onConfigurationChanged`/
  `onResume` 兜底。**处于 PiP 期间一律不下发**：那一刻量到的是浮窗大小，写进去会污染退出动画的起点。
- 行为边界（有意如此，非缺陷）：概览（recents）/助手等 transient 场景系统不会自动进入 PiP
  （`Task.enableEnterPipOnTaskSwitch` 会把 `supportsEnterPipOnTaskSwitch` 置为 false），
  此时按 Home 之外的路径离开不会产生浮窗。
- 增加 `onPictureInPictureModeChanged` 状态回传，UI 不在点击后假设成功。

### 5.13 锁定界面

- 锁定后隐藏所有常规控件和面板：顶栏、底栏、进度拖动、四画面手势（音量、亮度、进度、缩放）、双击快进/快退全部关闭，只保留**解锁**和**播放/暂停**两个入口；瞬时反馈文案、缓冲/加载提示仍照常显示。
- 解锁与播放/暂停都**不常驻**：单击画面唤出，位置与底栏锁定键一致（右下角，同样的安全区和边距），并和其它控件一样 **3 秒自动隐藏**。因此"单击唤出 + 再单击解锁"是两步，锁定的防误触语义才成立（旧实现把解锁键常驻屏幕中央，一次误触即解锁）。
- 锁定态禁用手势但不禁用音量键：音量键仍按系统媒体流生效（决策 #102）。
- 图标跟状态、文案跟动作：未锁定显示 `LockOpen`、锁定后显示 `Lock`；按钮读屏文案在锁定时是「解锁屏幕」。
- 锁定/解锁都走统一瞬时反馈通道显示「已锁定屏幕」/「已解锁屏幕」。
- 锁定不暂停视频；系统返回先解锁。
- 复用现有 `PlayerOverlayReducer`（`Tap` 在锁定时只唤出、`Timeout` 对锁定同样生效），补充"锁定后自动隐藏""锁定态单击不隐藏"测试。

### 5.14 更多与设置

横屏设置抽屉从右侧进入，宽 `min(340dp, 84%)`；竖屏从底部进入，最高 72%。内容顺序：控件布局、视频信息、播放顺序、画面旋转、比例、音轨、字幕（速度不在此处，见 §5.7 的底栏档位条）。解码器只有存在真实可切换实现时显示；后台播放和睡眠定时不能用成功 Toast 伪装。设置面板另有「工具」分组（截图、A-B 循环、视频信息），与托盘入口一致。

竖屏底栏的「更多」托盘：

- `PlayerControlId.MORE`（图标复用 `YingLiIcon.OVERFLOW` = Tabler `DotsVertical`）**默认只放竖屏底栏、位于最右**（`PORTRAIT_BOTTOM` 默认列表的最后一项）；横屏默认不安排该控件，顶栏常驻溢出菜单维持原样。
- 点击在按钮行上方展开工具托盘。**当前实现是「固定槽位 + 内容单独淡入淡出」，不是 `expandVertically`/`shrinkVertically`**（口径修正：本节原写 `AnimatedVisibility + fadeIn/expandVertically` 进场、`fadeOut`/`shrinkVertically` 退场）。底栏是底部对齐的悬浮控制条，因此展开时**按钮行位置不变、进度行上移一个辅助带高度**，画面区域尺寸不变（不挤压画面）。
- 辅助带高度**只有一条驱动**，拆成"**终值**"与"**怎么过去**"两个纯函数（阶段 3 把原来的"托盘动画值 + 截图会话硬覆盖"两条路合成**单一 `Animatable`**；再往前它是一条路径里混着动画值与硬覆盖，于是"胶囊出现"和"托盘开关"两种成因互相污染）；两条判据缺一条就会退化成下面两个已修过的缺陷之一：
  1. **终值 → `playerAuxiliaryBandHeight(bandHold, trayExpanded)`**：`bandHold`（截图会话，或胶囊在场/正在滑出的留位窗口 `capsuleBandHeld`）或托盘展开 → 满高 `PlayerAuxiliaryBandHeight`(80dp)；否则 `0dp`。**它只回答"终值是多少"。**
  2. **变化方式 → `auxiliaryBandTransition(bandHold)`**：胶囊（截图胶囊或 AB 胶囊）在场 → `SNAP`（**瞬时**到位，`snapTo`）；托盘开关、以及胶囊滑出后的回位 → `ANIMATE`（`animateTo(…, tween(TRANSPORT_SECTION_TRANSITION_MILLIS))`，`240ms`，与三段淡入淡出同拍）。
  - 为什么必须分成这两条（两条各出过一次问题）：只有路径 2 的动画、没有 `SNAP` 时，胶囊出现的那一帧带子还在做 `0→满高` 动画，胶囊会跟着带子从下往上滑——这就是「胶囊竖直跳变」，commit `01ebdfa` 修的就是它；只有 `SNAP`、托盘开关也瞬时切换时，用户实测看到「进度条突然上移、突然回到原位」，commit `5632ac8` 把它改回高度动画。**两个纯函数都有单测**（`PlayerChromeLayoutMathTest`）。
  - 胶囊滑出之后的回位**也走 240ms 动画**（此时底栏三段已经可见，瞬时塌掉同样会看到进度行瞬移）；而这**不**与"只要胶囊在场就必须瞬时"冲突 —— 滑出留位窗口期间 `bandHold` 仍为真，带子保持满高，窗口结束后 `bandHold` 才转假、回位走动画。
- 底栏三段（进度行 / 辅助带（工具托盘行与截图胶囊）/ 按钮行）一律**固定槽位 + 内容单独淡入淡出**，槽位高度不参与任何动画：进度行槽位恒为 `PlayerChromeButtonSize`（**AB 工具打开**时再加一个读数行预留高度 —— 取 `abReadoutBandHeight`，见 §5.11；旧的"有 AB 标记才预留、按 `labelLarge.lineHeight` 取"那条口径已随读数条改版下线）、按钮行槽位恒为 `PlayerChromeButtonSize`、辅助带槽位只在「该满高」时为满高。若让槽位高度跟着 `AnimatedVisibility` 的进出场收缩，底对齐的 Column 会在动画中途整体重新定位——这正是 commit `01ebdfa` 的根因（`48dp`/`240ms` 的位移量与时刻与用户描述完全吻合）。胶囊竖直带的唯一依据因此是「按钮行槽位高度 + 底栏内边距」。
- 辅助带满高 `80dp`：`PlayerAuxiliaryBandHeight = PlayerScreenshotCapsuleHeight(64dp) + PlayerPortraitControlsSpacing(16dp)`。这一格要同时住得下 64dp 的截图胶囊与 48dp 的托盘按钮，且两者与下方按钮行之间都要留 16dp。托盘行比胶囊矮一档，用 `PlayerToolRowTopInset = 胶囊高度 − PlayerChromeButtonSize = 16dp` 补齐顶部，**托盘按钮的绝对位置与旧实现完全一致**（仍然离按钮行 16dp），多出来的 16dp 落在带子上方。
- 托盘行按**满高**测量（`requiredHeight(PlayerAuxiliaryBandHeight)`，而不是 `height`）再参与裁剪：辅助带的 `Box` 必须带 `clipToBounds`，托盘行才会表现为「**随带子被推开露出**」，动画中途也不会把按钮画到下方按钮行上；带子塌回 0 时也不会有残留内容。**不要退回「原地淡入」**——那等于把「随高度展开」这条视觉线索丢掉。外层 `contentAlignment` 显式取 `TopStart`，托盘行在动画中途比带子高时才不会在格子里上下浮动。
- 托盘按钮与底栏**同源**：复用同一个 `PlayerShortcut`；`Arrangement.spacedBy(PlayerShortcutSpacing, Alignment.End)` + 列表 `reversed()`，自右向左排列，间距与底栏一致。
- 托盘内容是可配置槽位 `PlayerControlSurface.TOOLS`（容量 8），默认 `[SCREENSHOT, AB_LOOP, MIRROR_HORIZONTAL, MIRROR_VERTICAL, INFO, BACKGROUND_PLAYBACK]`；设置页槽位编辑器里的「工具托盘（更多）」分组可拖拽重排（见 §6）。
- 展开状态是纯 UI 状态（`remember { mutableStateOf(false) }`），不进 ViewModel；控件自动隐藏会把整条底栏移出组合，托盘随之回到收起状态。

### 5.15 画面手势（常规播放页）

对应 FR-PLAYER-004 与决策 #101/#208/#209/#339/#386/#389/#394/#527/#529（注：`PlayerGestureHud.kt` 的注释写的是「§14.4」，实为 Media3/真机测试；本文把常规播放器手势集中在本节）。识别器为 [PlayerGestureRecognizer.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerGestureRecognizer.kt)（纯 Kotlin，可 JVM 驱动），阈值常量为 `PlayerGestureSpec`，位移到能力的应用在 [PlayerViewModel.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerViewModel.kt)，反馈浮岛在 [PlayerGestureHud.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/PlayerGestureHud.kt)。

#### 单一所有者（画面手势结构，决策见本轮重构）

画面上的手势**只有一个所有者**：`Modifier.playerCanvasDragGestures` 里那一个 `awaitEachGesture` 循环负责单击、双击、长按倍速、竖向音量/亮度、横滑进度、双指缩放与平移。画面链上**不再挂 `detectTapGestures`**（对齐 REX-Player 的做法：它的画面链上零个 `detectTapGestures`，单击/双击也是自写循环，见 `GestureHandler.kt:230-397`）。

原因（实测）：`detectTapGestures` 在按下时就会 `consume()` 整个 down，日志里 21 次画面按下有 17 次是 `consumed=true`。两个所有者抢同一个 down 时，另一个只能在"没被消费"的少数位置生效，表现为"捏合时好时坏、长按只有一块区域能用"。

- 起手用 `awaitFirstDown(requireUnconsumed = false)`；若 `down.isConsumed`（按钮/进度条/倍速轨接管）则本次按压画面手势完全不介入，控件优先。
- 单击在 `onTapReleased` 结算：延迟到 250ms 双击窗口之后再兑现（`Tap`），窗口中若来了第二次同分区点击则取消该延迟并直接触发双击。
- 长按倍速用 `rememberCoroutineScope().launch { delay(360) }`，在四处取消：新手势开始、位移触动手势目标、出现第二指、手势收尾；出现第二指时同时结束已生效的倍速。
- 缩放/平移要求**恰好两指**（`pressed == 2`）；多指会话内始终消费事件以隔离单指识别器，但不因"某一帧被消费"放弃整次手势。
#### 区域划分与开关

- 竖向音量/亮度区**按画面中线严格二分**（`VERTICAL_GESTURE_ZONE_FRACTION = 0.5f`，左右各 `50%`）、不设中间死区；双击分区另算（左右各 `40%`、中间 `20%` 播放/暂停），两者不再共用一组比例（口径修正：原写「左 `45%`/右 `45%`/中间 `10%`」，与代码不符；触发舒服的位置恰好被旧比例挡掉了）；
- 竖向手势**不做边缘避让**（`SYSTEM_EDGE_INSET_DP = 0f`）：垂直滑动不会触发系统左右返回手势，原先左右各留 `12dp` 恰好挡掉了最顺手的起手位置（口径修正：原写 `12dp`）；垂直手势按**按下位置**分侧，滑动过程中不允许目标跳变，也不回退到另一半屏的另一个手势；
- 左右映射可配置：`gestureLeftSideIsVolume` 默认 `true`（默认左音量、右亮度），关闭后互换为左亮度、右音量。音量走**系统媒体流**（`STREAM_MUSIC`），亮度是**窗口级页面亮度**（不改系统设置、不需要 `WRITE_SETTINGS`，退出播放页恢复）；
- 进度、音量、亮度、缩放四项手势可分别关闭（`gestureSeekEnabled`/`gestureVolumeEnabled`/`gestureBrightnessEnabled`/`gestureZoomEnabled`，默认全开）；手势进行中被关闭或锁定要立即打断并提交当前值，不做半途静默丢弃；
- 锁定态禁用全部画面手势（音量键仍然有效），下滑退出（`gestureSwipeDownToExitEnabled`）默认关闭，只作可选手势。

#### 阈值

| 项 | 值 | 说明 |
|---|---:|---|
| 起手不跟手 | `6dp` | 小于该位移只当作点击候选 |
| 主轴锁定 | `10dp` | 超过后锁定垂直或水平轴，方向锁定后不触发另一轴 |
| 长按 | `360ms` | 移动超过 `8dp` 取消长按 |
| 竖向分区 | 左右各 `50%` | 按画面中线严格二分，不设中间死区（与双击分区无关） |
| 边缘避让 | `0dp` | 竖向手势不做边缘避让；垂直滑动不会触发系统返回手势（口径修正：原为 `12dp`） |
| 双击分区 | 左 `40%` / 中 `20%` / 右 `40%` | 快退 / 播放暂停 / 快进 |

#### 点击与双击

- 单击画面只切换控制层，不自动播放/暂停，也不缩放；
- 双击中央 `20%` 默认播放/暂停；双击左/右分区快退/快进，步长为 `gestureDoubleTapSeekMillis`，可选 `5/10/15/30` 秒，默认 `10000`；
- 双击分区只按左右各 `40%` / 中间 `20%` 划分，与竖向音量亮度区解耦，且不再做边缘避让（`SYSTEM_EDGE_INSET_DP = 0f`；口径修正：原写「双击落在边缘 `12dp` 内按快退/快进处理」）；
- 双击反馈与手势字段同源（决策 #339），不额外弹出对话框。

#### 长按临时倍速

- 默认临时 `2x`，倍率可在设置中选择 `1.5/2/3`（`longPressSpeed`）；
- 松手恢复到**按下前的倍速**，而不是固定回 `1x`；
- 临时倍速**绝不写入按媒体的倍速偏好**：`setSpeed` 会按媒体持久化，因此临时倍速必须走独立通道（锚点 + `SetSpeed` 派发），页面关闭时也要收尾。

#### 水平拖动调进度

- 水平拖动调整进度，拖动只更新 HUD 预览，松手才提交，与进度条一致；
- 目标位置钳制在完整时长（下界 `0`）内；**AB 生效时不再钳制到 `[A, B]`**（D8-A，见 §5.11）；
- HUD 显示目标时间和总时长，拖动过程中不阻塞画面。

#### 双指缩放与平移

- 双指捏合自由缩放，缩放状态是**本次播放的临时状态**，在当前播放内保留，不自动成为所有视频的全局比例；
- 放大后单指用于平移画面，进度调整改由进度条或双指手势承担，缩放状态决定手势如何解释，不允许一次手势同时触发两项操作（决策 #529）；
- 提供复位入口把缩放恢复到适应状态；离开播放页时清掉缩放，不把页面状态带出去；
- 适应、填充、裁剪、自由缩放在当前播放内保留。
- **实现现状**：`VideoZoom` 状态、`setZoom/applyZoomGesture/panZoom/resetZoom` 与 `gestureZoomEnabled` 开关均已接入——画面上的双指捏合走独立的多指手势段（`calculateZoom/calculatePan`），放大后单指拖动改为平移（`zoomActive` 分流，不再触发音量/亮度/进度），**已放大时双击先复位缩放**，因此不需要额外的常驻复位按钮。

#### 反馈

- 音量/亮度用**竖向条**（与竖向手势方向一致：图标在上、轨道自下向上填充、数值在下），进度与缩放用横向浮岛；统一显示**图标＋数值（或倍率）＋方向**和细进度条，停止变化约 `900ms` 后消退；
- 音量贴左、亮度贴右（跟手势所在侧一致），进度与缩放居中，避免用户还要猜哪边动了；
- 不使用全屏彩色遮罩、不叠加系统音量条（决策 #339），不遮挡视频内容。
- 四种反馈（音量/亮度/进度/缩放）均已接入；长按临时倍速另有常驻可见反馈（顶部"长按快进中 2x"胶囊），松手即消失，避免用户不知道长按已生效。

#### 边界与首次提示

- 音量/亮度到达 `0%` 或 `100%` 时给出轻微震动反馈（`HapticFeedbackType.LongPress`，同一端只震一次，避免持续嗡鸣）；
- 首次进入常规播放页展示一次性手势提示（说明左右调节、进度拖动、长按快进、双击分区与双指缩放，约 `2800ms` 后自动消退，且显示过一次后持久化不再出现）；
- 下滑退出播放页是可选手势（设置项 `gestureSwipeDownToExitEnabled`，默认关闭）：只在**中间 20% 区域**向下滑超过约 `64dp` 才触发，系统返回始终是主返回方式（决策 #386）。

### 5.16 画面镜像翻转

- 领域模型 `VideoMirror(horizontal, vertical)`（[PlaybackTrackContracts.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/domain/playback/PlaybackTrackContracts.kt)）：两个方向相互独立，`isActive = horizontal || vertical`；与自由缩放同样是**本次播放的临时状态**，在当前播放内保留、随媒体切换复位（页面按 `mediaId` 记忆）。
- 只做视图层变换：与缩放叠加在同一个 `graphicsLayer` 上，`scaleX = zoomScale * mirrorScaleX`、`scaleY = zoomScale * mirrorScaleY`，平移量不受翻转影响（翻转不改变画面中心所在位置）；不进播放管线，也不写任何偏好。
- **翻面动画**：`animateFloatAsState(1f ↔ -1f, tween(240ms))`，时长与缩放过渡同源（`ZOOM_TRANSITION_MILLIS`）。从 `1f` 动画到 `-1f` 必然经过 `0`，画面先压扁再朝另一侧展开，视觉上就是"翻面"，不需要额外做 3D 旋转。
- 必须让播放输出走可被视图层级变换的 `TextureView`：`VideoRotationStage(zoomActive = zoom.isActive || mirror.isActive)`。`SurfaceView` 的画面由 SurfaceFlinger 单独合成，**不跟随父级 `graphicsLayer` 的负缩放**，只切按钮不改输出会出现"按钮状态变了、画面纹丝不动"。
- 入口在「更多」托盘：`PlayerControlId.MIRROR_HORIZONTAL` / `MIRROR_VERTICAL`，图标 `YingLiIcon.FLIP_HORIZONTAL` / `FLIP_VERTICAL`（`YingLiCustomIcons.FlipHorizontal` / `FlipVertical`，即 `design/assets/icons/flip-horizontal.svg`、`flip-vertical.svg` **逐路径移植**的 `ImageVector`，`IconProvider.LOCAL_VECTOR`，各 5 条 path），开启时按钮为**实心**（`filled`）；文案「水平翻转」/「垂直翻转」。

### 5.17 后台播放开关

- 偏好 `backgroundPlaybackEnabled` **默认 `true`**：维持"前台服务继续播放"，即离开前台默认不暂停（口径修正：早期规范写"离开应用默认暂停、需用户主动开启"，现按实现改为默认开启，见 `04` FR-PLAYER-008、`06` M5）。
- 判定是纯规则 [BackgroundPlaybackPolicy.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/domain/playback/BackgroundPlaybackPolicy.kt) 里的 `shouldPauseInBackground(enabled, inPictureInPicture) = !enabled && !inPictureInPicture`，可 JVM 单测。**画中画是唯一例外**：画面仍可见，暂停等于把 PiP 变成静态图（决策 #533"画中画仍属于画面可见状态，不套用音频-only 策略"）。
- 暂停点在 `MainActivity.onStop()` 的**非配置变更分支**（`!isChangingConfigurations`）内、`super.onStop()` **之前**：放在 `super.onStop()` 前是因为此刻 `collectAsStateWithLifecycle` 的订阅还没停，读到的是用户刚看到的那份偏好，而不是 StateFlow 的初始默认值；旋转屏幕走配置变更分支，不会误暂停。**回到前台不自动恢复**：`onStart` 不调播放，要不要继续由用户决定。
- 入口在「更多」托盘：`PlayerControlId.BACKGROUND_PLAYBACK`，图标 `YingLiIcon.BACKGROUND_PLAYBACK`（Tabler `Headphones`，"声音继续、画面不可见"比齿轮/扬声器更直观），开启时按钮为**实心**（`filled`）；文案「后台播放」（见 §5.14、§3.4）。

## 6. 控件布局编辑器

Demo 的卡片式编辑方式保留：每区显示“已选数/上限”；已选卡片右下角是减号；可添加卡片右下角是加号；固定控件显示锁。

```kotlin
enum class PlayerControlSurface(val capacity: Int) {
    LANDSCAPE_TOP_RIGHT(4),
    LANDSCAPE_BOTTOM_LEFT(4),
    LANDSCAPE_BOTTOM_RIGHT(4),
    PORTRAIT_BOTTOM(7),
    TOOLS(8), // 竖屏底栏上方的「更多」托盘，见 §5.14
}

data class PlayerControlLayout(
    val slots: Map<PlayerControlSurface, List<PlayerControlId>>,
)
```

（口径修正：原示例写 `landscapeTop/landscapeLeft/landscapeRight/portraitBottom` 四个字段，代码已收敛为「槽位 → 控件列表」的映射，并新增 `TOOLS` 槽位。）

约束：同区不重复；超过上限拒绝；同名控件在同一朝向的不同槽位之间也不允许重复；固定控件（`fixed`，当前只有全屏）不可移除，编辑器显示锁而不是减号；编辑器只允许同区拖拽排序；恢复推荐一次性替换默认。坏数据（未知枚举名、超容量、同向重复）过滤后回落默认布局，不让读取抛异常。

#### 持久化与版本迁移（新增控件前必读）

编解码在 [PlayerControlLayoutCodec.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/domain/playback/PlayerControlLayoutCodec.kt)（纯 Kotlin，不依赖 Android，可 JVM 驱动）：

- 存储键：当前写入 `layout_v4`，读取顺序 `layout_v4 → layout_v3 → layout_v2`；旧键只读保留、永不删除，升级后第一次写入即落到新键。
- **格式版本写在 value 前缀里**（`PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION`，当前为 `4`；前缀分隔符 `;`）。没有版本前缀的老值一律按版本 `0`（legacy）处理。版本进 value 而不是进 key 名，是因为 key 名只能表达"这份数据由哪一代实现写下"：用户在设置页动过一次布局，旧实现就会写满当时的最新键，"写入时的格式版本"和"是否已补齐该版本新增按钮"就再也分不开了。
- 迁移规则（`migrate`）：只对 `dataVersion < generation <= currentVersion` 的代次按引入版本升序逐代 `ensureControls(TOOLS, …)` 回填；`dataVersion` 已等于当前版本 → 原样返回；`dataVersion` 比实现更新（例如从更高版本恢复）→ 原样返回，不认识的数据一律不动；legacy（版本 0）→ 登记表里所有不超过当前版本的代全部回填。
- **用户主动移除的回填按钮不会被复活**：回填只认"比数据版本新的代"，已写进用户数据的旧代不会因后续提升版本号而重新补上，"永久移除"才成立。容量不足或与同方向槽位冲突时只跳过、不抛异常。
- **新增一个低频控件的两步（缺一不可）**：① 在 `BACKFILLED_CONTROLS_BY_VERSION` 里为它所属的**新版本号**登记这些 id；② 把 `CURRENT_LAYOUT_VERSION` 提到那个版本号。漏做第 ② 步的后果是"该代按钮不出现"（立刻可见，因为回填的上界就是 `CURRENT_LAYOUT_VERSION`），而**不是**"用户移除被复活"（那才是静默错误）。只做第 ② 步则那一代没有任何登记，自然补不出东西。
- 以上语义由 `PlayerControlLayoutCodecTest` 覆盖（版本前缀、legacy 回填、跨代只补新代、超出当前版本的登记不参与回填、容量不足不抛异常、更新版本数据原样返回）。

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
- **帧率的显示口径统一走纯函数 `frameRateLabel`**（[FrameRateText.kt](/D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/feature/player/FrameRateText.kt)，有单测 `FrameRateTextTest`）：`|值 − 最近整数| < 0.01` 时只显示整数、否则保留两位小数（`29.999 → "30 fps"`，**不得显示 29**；`23.976 → "23.98 fps"`、`59.94 → "59.94 fps"`，**不吸成 60**）；缺失 / `0` / 负数 / `NaN` / 无穷 → 返回 `null`，界面显示“未知”或整段不出现。**禁止任何调用点自己 `toInt()` 截断**——Media3 对精确 30fps 的容器在真机上报的是 `29.999x`。三处调用点共用这一份口径：顶栏副标题（§4.1/§4.2）、本节的播放器信息弹窗、Shorts 信息框。
- Dialog 宽度为窗口减 `28dp`，最大高度减安全区 `48dp`，内部 `LazyColumn`。
- 行高至少 `48dp`，左列 `76–90dp`，右列允许换行。
- Shorts 可复用 Dialog，但数据来自当前 Shorts item。

## 8. Overlay、面板互斥与自动隐藏

现有 `PlayerOverlayReducer` 支持 `controlsVisible、locked、dragging、AUTO_HIDE_MILLIS=3000`。目标状态还需加入 `PlayerPanelState`、截图和 AB 工具状态。

- 初次进入显示；播放中 3 秒无交互隐藏。
- 暂停默认保持显示；用户主动隐藏后尊重该状态。
- Loading/Buffering 显示状态；Error/Ended 不自动隐藏。
- 拖动进度、AB、面板滚动期间不隐藏。
- 同时只打开一个 Drawer/Dialog/Sheet；截图与 AB 胶囊互斥（三分支与"捕获回调晚到"的判定见 §5.11；当前代码里 AB 胶囊还是 `PlayerScreen` 里的独立居中浮层，阶段 3 才迁入底栏辅助带）。
- 危险确认 Dialog 不允许遮罩误关。

返回优先级（由 `resolvePlayerBack` 单点解析，UI 只按解析结果分发）：

```text
危险确认 -> Dialog -> Sheet/Drawer -> 截图/AB 胶囊/倍速档位条 -> 解锁 -> 退出全屏 -> 退出 PlayerRoute
```

只有最后一项不拦截系统返回，其余都由播放页的 `BackHandler` 消费；离开播放页时另外必须还原系统栏与方向。

视频空白区域单击只切换控制层，不自动播放/暂停，避免语义冲突；锁定态下同一个单击只唤出解锁与播放/暂停入口（见 §5.13），不会解锁、也不会触发任何画面手势。

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

app/playback/
├── YingLiPlaybackService.kt
├── PlaybackSessionRuntime.kt
└── ActivityPictureInPictureGateway.kt

engine/media3/
├── Media3PlaybackEngine.kt
├── Media3PlaybackStateMapper.kt
├── Media3TrackMapper.kt
└── Media3VideoSurface.kt
```

`PlayerScreen` 只编排组件和回调；ViewModel 把 `PlaybackSessionClient` 投影为页面状态，负责页面 Overlay、面板互斥和一次性反馈；`PlaybackSessionRuntime` 负责长期会话；Media3 Engine 负责播放器事实；Activity Gateway 负责 PiP、方向和窗口状态；Repository 负责队列、偏好、收藏/黑名单和进度持久化。Composable 不导入 Room、文件 API、Media3 或具体 Engine。

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
3. 给 Service 侧 `PlaybackSessionRuntime` 注入 `PlaybackQueueRepository` 和队列导航策略；`PlayerViewModel` 只通过 `PlaybackSessionClient` 发出上一项/下一项命令。
4. 让 `MainActivity` 的方向状态从窗口回调同步回 UI，不要只用 `landscapeRequested` 翻转。
5. 把音轨/字幕设置从当前 `AdvancedSettingsSheet` 细化为可测试列表组件；无轨道时显示空状态。
6. 增加 AB 的纯 Kotlin reducer（设点校验、A/B 互换、相等边界、帧吸附）与会话状态 `AbLoopSession`（区间 `AbLoopState` + `loopCount`，两者一起投影，避免 UI 读到"区间已更新、计数还没更新"的中间态）；**不再有"由 UI 钳制 seek 范围"这一层**（D8-A）；AB 不需要持久化。循环本身由引擎判定与回跳，见 §5.11 与 `docs/17` §13.2。
7. 独立建立 Shorts Feature，不在 `PlayerScreen` 增加 `mode == SHORTS` 分支。

## 12. 动画与动效

| 动效 | 时长 | 实现 |
|---|---:|---|
| 控件显示/隐藏 | 180–220ms | `AnimatedVisibility` + alpha/translation |
| Drawer/Sheet | 240ms | `slideInHorizontally` / `slideInVertically` |
| Dialog | 180ms | alpha + scale `0.98 -> 1` |
| 底栏三段（进度行 / 辅助带 / 按钮行）进出场 | 240ms | `TRANSPORT_SECTION_TRANSITION_MILLIS`：**固定槽位 + 内容单独淡入淡出**，槽位高度不参与动画（见 §5.14） |
| 辅助带高度（托盘开关 / 胶囊滑出后回位） | 240ms | `AuxiliaryBandTransition.ANIMATE`：`Animatable.animateTo(playerAuxiliaryBandHeight(...), tween(240ms))`，进度行随之平滑上移/回位（见 §5.14） |
| 辅助带高度（工具胶囊在场，含滑出留位窗口） | 瞬时（0ms） | `AuxiliaryBandTransition.SNAP`：`Animatable.snapTo(满高)` —— 终值由 `playerAuxiliaryBandHeight` 给出、变化方式由 `auxiliaryBandTransition` 给出（见 §5.14） |
| 截图胶囊出入场 | 360ms | `slideIn/OutHorizontally` + `fadeIn/Out`，比底栏三段慢一档（见 §5.10） |
| AB 胶囊出入场 | 360ms | 与截图胶囊**同一时长与轨迹**（同住辅助带同一格，见 §5.11）；胶囊在场期间辅助带保持满高，竖直带不参与动画 |
| 中央三连（上一个/播放/下一个）进出场 | 240ms | 与底栏三段同拍；截图模式激活期间不出现（见 §5.10） |
| 帧数胶囊进出场 | 240ms | 与底栏三段同拍；`frameCounter` 为 null 时整个胶囊不出现（见 §5.10） |
| 截图飞入 | 420ms | `animateFloatAsState` 单一 `0 → 1` 进度量同时驱动 `scale(2.4) → 1` 与位移；起点 = 视频画面区域（不含黑边）的右下角，终点 = 屏幕左上角（见 §5.10） |
| 截图倒计时 | 3000ms | 恒转计时器每 `50ms` 喂一次会话 + `LinearProgressIndicator`，读条匀速 |
| 删除按钮 | 200ms | scale `.55 -> 1` + alpha + `-18°` 旋转 |
| Shorts 切换 | 320ms | 双层视频 translation |
| 快退/快进反馈 | 1000ms 内 | alpha + 数值变化 |
| 「更多」托盘展开/收起 | 240ms | 辅助带高度动画（进度行上移/回位）+ 内容单独淡入淡出；**不是 `expandVertically`/`shrinkVertically`**（见 §5.14） |
| 镜像翻面 | 240ms | `animateFloatAsState(1 ↔ -1)`，经过 0 自然"压扁再展开"（见 §5.16） |

尊重系统 `AnimatorDurationScale=0`；状态和点击顺序不能依赖动画回调。

## 13. 功能优先级

| 优先级 | 功能 |
|---|---|
| P0 | Media3 播放/暂停、准备/错误、Seek、上一项/下一项、横屏/竖屏、全屏、锁定、播放列表、视频信息、截图、基础 AB、速度、音轨/字幕真实选择 |
| P1 | 四态播放顺序、控件布局编辑与 DataStore、PiP、比例、截图预览生命周期、YLShorts 页面、收藏、黑名单、自动下一条、循环当前 |
| P2 | 音量/亮度手势的震动边界反馈与首次进入手势提示（基础音量/亮度/进度/缩放手势已按 §5.15 落地）、分享、删除短视频、睡眠定时、后台播放策略、帧预览缩略图 |
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
- `PlayerOverlayReducer`：3 秒隐藏（锁定态同样生效）、拖动不隐藏、锁定态单击只唤出解锁与播放/暂停入口、面板互斥。
- `PlayerGestureRecognizer`：6/10dp 起手与主轴锁定、长按 360ms（移动 8dp 取消）、竖向音量/亮度按中线 50/50 二分且不做边缘避让（口径修正：原写「左右 45%＋中间 10% 分区、边缘 12dp 不接手势」）、双击分区（左右各 40%、中间 20%）、开关与锁定打断、左右映射互换。
- `PlayerViewModel` 手势：拖动只预览松手提交且**不受 AB 钳制**（D8-A，见 §5.11）、临时倍速不写媒体偏好且恢复先前倍速、亮度退出播放页恢复。
- `ScreenshotReducer`：Armed/Capturing/Saved/Failed、3 秒超时、点击暂停倒计时、删除。
- `TrackPreference`：每媒体覆盖全局、坏值回退、速度和比例边界。
- `PlayerViewModel`：命令拒绝不改变 UI、暂停截图可调用、队列切换和进度写入。
- 截图工具 / 帧号 / 跳转精度的纯逻辑：`PlayerChromeLayoutMathTest`（辅助带两条路径、胶囊尺寸与动效常量、顶栏高度与帧数字号数学）、`ScreenshotPreviewAnimationTest`（飞入轨迹单调）、`FrameRateTextTest`（帧率显示口径）、`SeekPrecisionTest`（截图激活一律精确 / 退出按时长）、`FrameCalibrationTest`（耗时模型、`≈` 阈值与判据）、`PlayerViewModelTest`（精度切换与恢复、步进锚点、帧号估算/校准切换）。

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

1. 以当前测试建立基线，先执行 `17` 的领域契约、Runtime 和 Media3 Engine 分层，补齐 `PlaybackOrder`、队列导航、AB reducer 和 Buffering 语义。
2. 在前端只依赖 `PlaybackSessionClient` 后拆分 `PlayerScreen`，先实现横屏目标布局和真实状态映射。
3. 复用同一 `PlayerUiState` 实现竖屏布局变体，接入 Insets、方向和全屏。
4. 接入播放列表、视频信息、速度、比例、音轨、字幕、PiP 和锁定。
5. 实现截图状态机和 MediaStore 结果预览，验证暂停状态截图。
6. 实现控件布局编辑器、DataStore 持久化和无障碍 reorder 语义。
7. 增加 AB 标记拖动、A–B 区间高亮与计数文案 `循环 ×N`；回跳由引擎完成（D8-A 不限制可播放范围，见 §5.11）。
8. 建立 `ShortsRoute` 和独立 `ShortsViewModel`，实现上下滑、收藏、黑名单、更多 Sheet。
9. 执行 JVM、Compose、Media3 真机和 Release/Lint/R8 回归，填写前后行为表。

## 16. 禁止照搬 Demo

- 不把固定视频路径、文件大小、编码信息、黑名单初始集合复制到生产代码。
- 不把浏览器 Fullscreen API、HTML `video.play()` 当作 Android 实现。
- 不用 Toast 代替音轨、字幕、解码器、后台播放、睡眠定时、删除等真实能力。
- 不把 YLShorts 塞进常规播放器 orientation/mode 分支。
- 不在 UI 创建 ExoPlayer，不绕过 `PlaybackSessionClient` 访问 Controller、MediaController 或 Engine。
- 不使用内存 `Set` 保存 Shorts 收藏/黑名单，必须接入持久化仓储。
- 不因兼容旧实现保留重复状态、过渡 Adapter 或无效接口；新设计验证通过后删除旧实现。

## 17. 验收标准

1. 横屏、竖屏使用同一播放会话完成播放、暂停、Seek、切换和错误恢复。
2. Demo 中承诺保留的按钮都有真实命令、状态反馈、禁用和错误路径。
3. 截图在暂停和播放状态均工作，预览从**视频画面区域的右下角**飞入左上角、轨迹单调、3 秒倒计时、点击卡片放大与删除真删文件都完整（见 §5.10）。
4. AB 的 A/B 标记可拖动、区间高亮与 `循环 ×N` 可见；**播到 B 时由引擎自然回跳 A，且用户仍可拖到区间之外**（D8-A，见 §5.11）——"播放范围真实受限"是旧口径，已废止。
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

1. 所有命令拒绝、播放切换、播放顺序、速度、比例、锁定/解锁、AB、截图、黑名单和收藏反馈走同一个 UI event 通道。
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
- 竖屏底部浮岛左右 `14dp`、底部 `18dp`，内边距约 `13dp 13dp 12dp`，圆角 `10dp`，半透明深色背景并带约 `12dp` 模糊。自下而上是：按钮行（`PlayerControlSurface.PORTRAIT_BOTTOM` 槽位，默认 7 个、最右是「更多」）、「更多」托盘（展开时插在按钮行上方）、进度行（当前时间、进度条和总时长）；竖屏不显示横屏底栏，默认不把音轨/字幕槽塞入顶部（口径修正：原写「第一行是播放键和最多六个快捷槽，第二行是当前时间、进度条和总时长」，顺序与槽位数均已变化，见 §4.2、§5.14）。
- 所有按钮触控区域仍必须至少 `48dp`；上述尺寸是视觉尺寸，触控区可以通过透明外层扩大。

### 18.5 进度条、元数据和 AB 实时同步

Demo 在 `input`、`timeupdate` 和 `loadedmetadata` 三个时机更新所有进度输入框、当前时间、总时长、AB 标记位置和 Shorts 时间文本。正式实现必须保留这些可观察结果，但由 Media3 状态驱动：

- `loadedmetadata` 对应 Media3 `duration`/`Tracks` 就绪，重新计算时长、帧率和视频信息。
- 播放事实来自播放器 position；Compose 的重组计时器只能刷新显示，不能成为 position 的来源。
- AB 开启后，进度条坐标仍以完整媒体时长为坐标系；`seekTo`、拖动、逐帧**都不再被 AB 钳制**（用户可拖到区间之外，D8-A），自动回跳由引擎完成（`docs/17` §13.2），不由 UI 轮询。
- 播放到 B 点时由**引擎自己**精确回跳 A（并在回跳前上报 `AbBoundaryReached` 供会话计数）；既不能依赖 UI 轮询，也不经过客户端命令层（`SeekOrigin.AB_LOOP` 只用于引擎内部回跳，见 `docs/17` §13.2）。
- 进度输入框、横屏 Seek、竖屏 Seek、Shorts Seek 必须共享同一个 position state，避免三处显示漂移。

### 18.6 AB 标记的无障碍和键盘行为

Demo 的 A/B 标记是可聚焦 slider，具有 `aria-valuemin`、`aria-valuemax`、`aria-valuenow` 和带时间的 `aria-valuetext`，支持 Pointer Capture 拖动及左右方向键按一帧移动。正式 Compose 必须等价实现：

- 使用 `progressBarRangeInfo` 暴露范围、当前值和步进信息。
- 使用 `customActions` 提供“向左一帧”“向右一帧”，外接键盘的 `ArrowLeft/ArrowRight` 也必须生效。
- 拖动期间保持控制层可见，结束后宣布“A 点已调整”或“B 点已调整”；**设点本身**被边界规则拒绝或吸附到合法值时仍返回合法状态（这里的"钳制"只指设点，不是把用户 seek 关进 `[A,B]`，见 §5.11）。
- A 与 B 至少间隔一帧；A 不得晚于 B；切换媒体、关闭 AB 工具或清除 AB 后焦点返回触发按钮。

> **更正（本批采纳边界，现行口径见 §5.11 与 `docs/21` §5/§9）**：上面"Pointer Capture 拖动 / 拖动期间保持控制层可见 /
> 按方向键移动端点"这批要求**本批不采纳、当前没有实现** —— A/B 标记的**编辑态与拖拽端点**已被明确排除（`docs/21` §5），
> 因此没有"被拖的端点"可供聚焦与键盘移动。已实现的是：读数条里 A / B 数值各是**独立点击目标**（点按跳到该端点）、
> 轨道空白处点按 = seek（`docs/16` §5.11）。键盘逐帧移动**播放头**是另一件事，见 §5.10 的逐帧步进。

### 18.7 截图 Demo 占位与正式能力的区别

Demo 的 `captureScreenshot()` 并未读取真实视频帧，而是直接显示当前轨道已有 poster/素材图；它用于验证交互，不应被误认为截图失败条件。正式实现必须使用现有 `ScreenshotGateway` 和 MediaStore，但允许在 Ready、Paused、Playing 状态截图，不能因为“没有播放”拒绝。

Demo 视觉事实：

- 截图胶囊包含上一帧、截图当前帧、下一帧、取消四个按钮；截图按钮约 `52dp` 宽，其他按钮约 `42dp`，胶囊高度不低于 `48dp`（这是 Demo 的视觉事实；正式实现已定为胶囊 `64dp`、四枚按钮统一 `48dp`，见 §5.10）。
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

视频信息 Dialog 必须在媒体元数据改变后刷新；Demo 展示的文件名、位置、封装、大小、分辨率、方向、时长、编码、帧率、平均码率、音轨都不能使用固定字符串。缺失字段显示“未知”，路径按安全上下文脱敏；帧率的具体显示口径见 §7.2 的 `frameRateLabel`。

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
4. AB 标记支持拖动、左右一帧键盘操作、设点边界规则（一帧间隔 / A 不晚于 B）和 TalkBack 范围语义；**不包含"把用户 seek 关进 `[A,B]`"**（D8-A，见 §5.11）。
5. 截图在 Ready/Paused/Playing 均可调用；预览飞入（起点为画面右下角）、3 秒倒计时、点击放大/收起、**删除真删文件**与**关闭预览不删文件**两种语义必须明确区分且一致，真实 MediaStore URI 可验证；帧数胶囊在容器帧率缺失时不出、`≈` 只在估算耗时 ≥600ms 时出现。
6. Shorts 手势覆盖轴向锁定、阈值回弹、邻项预加载失败、切换防重入、长按倍速与滑动冲突。
7. Shorts 自动下一条、循环当前、收藏、黑名单、删除确认和管理列表的状态同步覆盖空列表、当前项和文件缺失边界。
8. 常规比例与 Shorts 比例互不污染；旋转、PiP、全屏回调后按钮状态以系统事实为准。
9. 所有视觉尺寸转换为 Token 后，在 320dp 宽、最大字体、横竖屏和系统手势导航下无重叠、无裁切。
