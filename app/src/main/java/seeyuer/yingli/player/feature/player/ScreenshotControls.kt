package seeyuer.yingli.player.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.FrameCounterState
import seeyuer.yingli.player.domain.playback.ScreenshotPreviewSize
import seeyuer.yingli.player.domain.playback.ScreenshotPreviewSpec
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.screenshotPreviewEnterScale
import seeyuer.yingli.player.domain.playback.screenshotPreviewEnterTranslation
import seeyuer.yingli.player.domain.playback.screenshotPreviewTrack

/**
 * 截图工具胶囊是否在场：Armed 与 Capturing 都算「工具打开」——捕获中胶囊要保持在场
 * （捕获按钮切等待态），否则按下的瞬间胶囊会在手指底下消失；Preview 让位给预览卡、
 * Idle / Failed 都没有胶囊。
 *
 * 这里是**唯一**的判定入口：底栏用它决定「托盘行让位 + 胶囊滑入」，
 * Screen 层用它决定底栏要不要因为胶囊而留在组合里（截图工具是浮层，不吃控件自动隐藏）。
 */
internal fun ScreenshotUiState.isCapsuleVisible(): Boolean =
    this is ScreenshotUiState.Armed || this is ScreenshotUiState.Capturing

/**
 * 画面中央的「上一个 / 播放暂停 / 下一个」三连要不要出现。
 *
 * 截图模式激活期间（Armed / Capturing）它**不出现**：这一刻用户在做的是"定位到某一帧"，
 * 中央三连与这个目标无关，还会正好压住用户要看的画面；退出截图模式后恢复。
 * `Preview` 不算激活——那时截图胶囊已经收掉、用户看的是左上角那张小卡，中央三连照常在场。
 *
 * 注意这里只加"截图模式下不出现"这一条：横竖屏、锁定、控件自动隐藏等既有条件仍由调用方
 * （`overlay.controlsVisible`、`overlay.locked`）判断，不能在这里重复实现。
 */
internal fun ScreenshotUiState.hidesCenterTransportControls(): Boolean =
    this is ScreenshotUiState.Armed || this is ScreenshotUiState.Capturing

/**
 * 截图工具胶囊的**内容**，出入场动画由外层负责（见 `BottomPlaybackControls` 的
 * `screenshotTool` 插槽）：它占用竖屏「更多」工具托盘行的位置，从右向左滑入并停在这一行中间。
 *
 * 截图入口在托盘最右端（托盘按 `TOOLS` 槽位反序渲染，`SCREENSHOT` 为第一项 → 显示在最右），
 * 所以外层按整行宽度从右滑入，视觉上就是「从这个按钮的位置滑出来」，退出时反向滑回去；
 * 若改成从底部滑入，起点与刚才点的按钮毫无关系，动效会显得凭空出现。
 *
 * 动画不放在这里：内外两层位移会叠加成一段突兀的加速，而且可见性只有一处说了算。
 *
 * 捕获按钮是**唯一的强调色实心按钮**（设计稿 §4.10「中间强调色圆角按钮」），
 * 其余三个是非实心的图标按钮；捕获中它切"等待态"（见 [ScreenshotCaptureButton]）。
 */
@Composable
internal fun ScreenshotToolCapsule(
    state: ScreenshotUiState,
    onPreviousFrame: () -> Unit,
    onCapture: () -> Unit,
    onNextFrame: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = state is ScreenshotUiState.Armed
    ScreenshotCapsuleSurface(modifier.height(PlayerScreenshotCapsuleHeight)) {
        Row(
            modifier = Modifier.padding(horizontal = ScreenshotCapsuleInnerPadding),
            horizontalArrangement = Arrangement.spacedBy(PlayerScreenshotCapsuleButtonSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerChromeIconButton(
                icon = YingLiIcon.SEEK_BACKWARD,
                contentDescription = stringResource(R.string.player_previous_frame),
                onClick = onPreviousFrame,
                enabled = armed,
                tint = YingLiTheme.player.controlPrimary,
                // 尺寸与工具托盘里的按钮**同一个常量**：胶囊取代的就是托盘行，
                // 两边按钮圆径不一致会一眼看出"换了一套控件"。**不要在这里写死别的尺寸。**
                size = PlayerScreenshotCapsuleButtonSize,
            )
            ScreenshotCaptureButton(
                capturing = state is ScreenshotUiState.Capturing,
                onCapture = onCapture,
            )
            PlayerChromeIconButton(
                icon = YingLiIcon.SEEK_FORWARD,
                contentDescription = stringResource(R.string.player_next_frame),
                onClick = onNextFrame,
                enabled = armed,
                tint = YingLiTheme.player.controlPrimary,
                size = PlayerScreenshotCapsuleButtonSize,
            )
            PlayerChromeIconButton(
                icon = YingLiIcon.CLOSE,
                contentDescription = stringResource(R.string.action_cancel),
                onClick = onClose,
                tint = YingLiTheme.player.controlPrimary,
                size = PlayerScreenshotCapsuleButtonSize,
            )
        }
    }
}

/**
 * 捕获按钮：捕获中切"等待态"（设计稿 §4.10）。
 *
 * 等待态直接落在 `enabled = false` 上，而不是另做一套颜色：那正是"现在按不动"的语义
 * （等待期间重复点击本来也该被忽略），并且与项目里其余禁用按钮共用同一份视觉语言。
 * 颜色/描边仍来自 [PlayerChromeIconButton] 的 `filled` 分支，不在这里写第二份强调色。
 *
 * 它是四个按钮里**唯一实心**的一枚（设计稿的"强调色按钮"靠颜色与填充表达）；
 * 尺寸同样是 [PlayerChromeButtonSize]，不再比同伴大一圈——飞入轨迹的起点已经改成
 * 视频画面区域的右下角，不必再靠"按钮更大"来暗示"卡片从这里飞出去"。
 */
@Composable
private fun ScreenshotCaptureButton(
    capturing: Boolean,
    onCapture: () -> Unit,
) {
    PlayerChromeIconButton(
        icon = YingLiIcon.SCREENSHOT_CAPTURE,
        contentDescription = stringResource(R.string.player_screenshot_capture),
        onClick = onCapture,
        enabled = !capturing,
        filled = true,
        size = PlayerScreenshotCapsuleButtonSize,
    )
}

/**
 * 截图工具与帧数胶囊共用的容器材质：**与底栏按钮同源**——同一份
 * [PlayerChromeControlFillAlpha] 底、[PlayerChromeControlBorderWidth] /
 * [PlayerChromeControlBorderAlpha] 细描边，以及同一枚胶囊形圆角
 * [PlayerChromeCapsuleShape]。胶囊与按钮同屏出现，各写一套 alpha 必然出现色差。
 *
 * 这里用 [BorderStroke] 而不是 `border` 参数：与 `PlayerChromeIconButton` 保持同一写法，
 * 描边宽度/透明度的唯一来源就是上面那几个常量。
 */
@Composable
private fun ScreenshotCapsuleSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = PlayerChromeCapsuleShape,
        color = YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha),
        contentColor = YingLiTheme.player.controlPrimary,
        border = BorderStroke(
            PlayerChromeControlBorderWidth,
            YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha),
        ),
        content = content,
    )
}

/**
 * 帧数胶囊：截图模式下显示 `当前帧 / 总帧数`，**位置在顶栏下方**（由调用方给出顶部内边距）。
 *
 * 为什么下沉而不是留在标题槽：竖屏底栏没有音轨/字幕键，这两个槽位被顶栏占着，帧数文本一长
 * （`488912 / 802008` 这种）就会压到右上角快捷按钮上——真机截图证实右端被音轨按钮压住。
 * 下沉到顶栏下方后，它与顶栏所有按钮在**视觉与点击区域**上都彻底分开：不再共享任何一条
 * 水平带，也就不会再有"字越长越容易压住按钮"这种随位数变化的隐患。
 *
 * [pending] 为 true 时给整段数字加 `≈`：大文件（见 `frameCalibrationNoticeRequired`）的校准
 * 实测要等 1.3s 起步，这几秒里显示的是"时长 × 帧率"的估算值；不标出来，用户会把一个稍后
 * 会变的数字当成精确值。小文件不标（那只会在 100ms 内闪一下）。
 *
 * 宽度约束只是**兜底**：调用方会先给出 `widthIn(max = ...)`（整屏宽度的一个比例）作为上限，
 * 极窄屏 / 最大字体下这里再按可用宽度反推字号（等宽数字，不换行、不省略），
 * 保证数字依旧完整可读；正常机型上推出来的字号就是基础字号，不做缩放。
 */
@Composable
internal fun FrameCounterCapsule(
    counter: FrameCounterState,
    // modifier 必须排在所有可选参数之前（Compose 的 ModifierParameter 规则）。
    modifier: Modifier = Modifier,
    pending: Boolean = false,
) {
    ScreenshotCapsuleSurface(modifier) {
        // 可用宽度 = 外层 widthIn 给出的上限（也是 BoxWithConstraints 的 maxWidth）。
        // 它同时封住了胶囊自身的最大宽度：字号再大也不会撑出这个宽度去压住别的控件。
        BoxWithConstraints {
            val numbers = "${counter.currentFrame} / ${counter.totalFrames}"
            // 估算标记走字符串资源：它是用户可见文本，和"已保存到 %1$s"这类文案同一口径。
            val text = if (pending) stringResource(R.string.player_frame_counter_approximate, numbers) else numbers
            val maxContentWidth = maxWidth - PlayerFrameCounterTextHorizontalPadding * 2
            Text(
                text = text,
                color = YingLiTheme.player.controlPrimary,
                // 等宽数字：帧号每帧都在变，比例数字会让整段文本左右抖动（与进度时间文本同一做法）。
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontSize = playerFrameCounterFontSizeSp(
                        availableWidth = maxContentWidth,
                        characterCount = text.length,
                        baseFontSize = MaterialTheme.typography.labelLarge.fontSize,
                    ),
                ),
                maxLines = 1,
                // 兜底也不许省略成 `488912 / 80…`：宁可整体缩一圈，也要看到完整帧号。
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = PlayerFrameCounterTextHorizontalPadding,
                    vertical = 6.dp,
                ),
            )
        }
    }
}

/** 帧数文本左右内边距：字号兜底要用它扣掉非文本宽度，所以抽成唯一一份。 */
private val PlayerFrameCounterTextHorizontalPadding = 12.dp

/** 帧数文本字号下限：正常机型用不到，只在极窄屏 / 最大字体下兜底。 */
private val PlayerFrameCounterMinFontSize = 10.sp

/** 表格数字每字符宽度与字号之比（Roboto tabular figures 的 advance 约为 0.95em）。 */
private const val PlayerFrameCounterDigitAdvanceRatio = 0.95f

/**
 * 帧数文本能在 [availableWidth] 里完整放下的字号（sp），上限为 [baseFontSize]（调用方传主题的
 * `labelLarge`：与进度时间文本同一档，字号只有一个来源）。
 *
 * 依据：等宽数字每个字符约占 `0.95 × 字号`，`n` 个字符需要 `n × 字号 × 0.95`；
 * 取倒数即得字号。数字多一位只会让字号小一点，**不会**让某一位被裁掉——
 * 这正是"数字必须完整可读"这条要求在此处的实现。
 *
 * 纯函数（不读主题）以便直接做单元测试：主题字号由调用方传进来。
 */
internal fun playerFrameCounterFontSizeSp(
    availableWidth: Dp,
    characterCount: Int,
    baseFontSize: TextUnit,
): TextUnit {
    if (characterCount <= 0 || availableWidth <= 0.dp) return baseFontSize
    val fitted = availableWidth / (characterCount * PlayerFrameCounterDigitAdvanceRatio)
    return fitted.value.coerceIn(PlayerFrameCounterMinFontSize.value, baseFontSize.value).sp
}

/**
 * 截图预览卡：捕获成功后从**视频画面区域的右下角**飞入页面左上角的小卡（设计稿 §4.10 / §6）。
 *
 * - 出现：`scale(2.4) → 1` + 位移，0.42s（[ScreenshotPreviewSpec]）；
 * - 尺寸：116dp 宽、16:10、白色描边；
 * - 下沿一条 3 秒读条，匀速缩短（余量由 ViewModel 的会话给，这里只画比例）；
 * - 点击卡片 → 展开大图预览（[ScreenshotPreviewOverlay]）。
 *
 * 展开态下**不画这张卡**：大图预览是它放大后的形态，两者同时在场只会看到一厚一薄两张同图。
 *
 * [flyInOrigin] 是飞行**起点**（根布局坐标系）：视频**实际渲染区域**的右下角，
 * 由 `VideoRotationStageMath.pictureBounds` 按 letterbox 几何算出（未量到时为 null）。
 * 终点不需要外部给：卡片静止在左上角（调用方已按 safeDrawing 内边距摆好），
 * 它的中心就是终点，量在下面那个无变换的外层 Box 上。
 */
@Composable
internal fun ScreenshotPreviewCard(
    state: ScreenshotUiState.Preview,
    flyInOrigin: Offset?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.expanded) return
    // 卡片静止时的左上角（根布局坐标系）。**量在无变换的外层上**：
    // 内层带着 scale/translation，若量它就会把动画中的形变量算进"目标位置"，
    // 目标一帧一变，位移公式的输入自己就在动（"先反向移动再回来"的成因之一）。
    var targetTopLeft by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // 只有起点与卡片几何都量到了才让 [entered] 变 true：动画**等几何到位再开始**，
    // 而不是先跑起来再纠正起点。首帧没坐标就多停一帧，用户看不到任何突变。
    var geometryReady by remember(state.uri) { mutableStateOf(false) }
    var entered by remember(state.uri) { mutableStateOf(false) }
    LaunchedEffect(state.uri, flyInOrigin, targetTopLeft) {
        if (flyInOrigin != null && targetTopLeft != Offset.Zero) geometryReady = true
    }
    LaunchedEffect(state.uri, geometryReady) {
        if (geometryReady) entered = true
    }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(ScreenshotPreviewSpec.ENTER_DURATION_MILLIS),
        label = "screenshot-preview-enter",
    )
    Box(modifier = modifier.onGloballyPositioned { coordinates -> targetTopLeft = coordinates.positionInRoot() }) {
        Surface(
            modifier = Modifier
                .width(PlayerScreenshotPreviewCardWidth)
                .graphicsLayer {
                    // **唯一的位移与缩放驱动**：两者都取自同一个 0→1 进度量。
                    // 变换原点锚在左上角，层自身 scale 对平移量的放大才与公式一致
                    //（推导见 screenshotPreviewEnterTranslation）。
                    transformOrigin = TransformOrigin(0f, 0f)
                    val size = ScreenshotPreviewSize(this.size.width, this.size.height)
                    val track = screenshotPreviewTrack(
                        startCenterX = flyInOrigin?.x,
                        startCenterY = flyInOrigin?.y,
                        targetX = targetTopLeft.x,
                        targetY = targetTopLeft.y,
                        size = size,
                    )
                    val scale = screenshotPreviewEnterScale(progress)
                    scaleX = scale
                    scaleY = scale
                    if (track != null) {
                        with(density) {
                            val translation = screenshotPreviewEnterTranslation(
                                track = track,
                                size = size,
                                targetX = targetTopLeft.x,
                                targetY = targetTopLeft.y,
                                progress = progress,
                            )
                            translationX = translation.first * size.width
                            translationY = translation.second * size.height
                        }
                    }
                }
                .testTag(PlayerTestTags.SCREENSHOT_PREVIEW)
                .clickable(onClick = onExpand),
            shape = RoundedCornerShape(PlayerScreenshotPreviewCornerRadius),
            // 卡片底色用播放页画布色：截图本身可能是任意亮度，深底能让白描边与读条都稳定可读。
            color = YingLiTheme.player.canvas,
            border = BorderStroke(PlayerScreenshotPreviewBorderWidth, PlayerScreenshotPreviewBorderColor),
            shadowElevation = 8.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ScreenshotPreviewSpec.ASPECT_RATIO),
            ) {
                AsyncImage(
                    model = state.uri.takeIf { it.isNotBlank() },
                    contentDescription = state.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
                // 读条：3 秒匀速缩短，下沿贴齐卡片底边。
                LinearProgressIndicator(
                    progress = { state.remainingMillis / ScreenshotUiState.PREVIEW_DURATION_MILLIS.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(PlayerScreenshotPreviewProgressHeight),
                    color = YingLiTheme.player.controlPrimary,
                    trackColor = YingLiTheme.player.track,
                )
            }
        }
    }
}

/**
 * 大图预览：点击预览卡后放大展示，右上角弹出**删除按钮**（设计稿 §4.10 / §6）。
 *
 * 为什么用一层铺满画布的半透明遮罩而不是弹窗：截图是"看画面"的事，弹窗会把视频画面
 * 整块盖住，用户想对照画面看截图反而做不到；同时它也是一道点击拦截——遮罩在场时，
 * 画面手势（单击唤出控件、双击跳秒）不会在用户想关掉预览时被触发。
 *
 * 遮罩把画面压暗但保留可见，观察到的画面帧与截图可以上下对照；关闭方式给三种
 * （点遮罩、点关闭按钮、返回键），因为这是"浮层不叠浮层"原则里的最上层浮层，
 * 必须有一个明确、随处可用的退出路径。
 *
 * 它同时满足项目既有的"浮层不叠浮层"：截图工具胶囊在这一段已经收掉（胶囊只在
 * Armed/Capturing 在场），设置/播放列表抽屉在进入截图模式时已关闭，所以这里不会与别的浮层同时出现。
 */
@Composable
internal fun ScreenshotPreviewOverlay(
    state: ScreenshotUiState.Preview,
    onCollapse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 返回键先关大图，而不是直接退出截图/播放页：用户此刻的"上一层"就是这张图。
    BackHandler(enabled = true, onBack = onCollapse)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerScreenshotPreviewScrimColor)
            .clickable(
                // 去掉水波纹：整屏的涟漪会很吵，这里点哪儿都是"关掉"。
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCollapse,
            )
            .testTag(PlayerTestTags.SCREENSHOT_PREVIEW_OVERLAY),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(PlayerScreenshotPreviewExpandedWidthFraction)
                .padding(horizontal = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ScreenshotPreviewSpec.ASPECT_RATIO),
            ) {
                // 图片本体也要吃掉点击，否则点在图上是"关掉"而不是"什么都不做"。
                AsyncImage(
                    model = state.uri.takeIf { it.isNotBlank() },
                    contentDescription = state.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }
            ScreenshotDeleteButton(
                onDelete = onDelete,
                // 贴在大图右上角外侧，与设计稿里预览卡的删除按钮同一位置关系。
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-11).dp),
            )
        }
    }
}

/**
 * 删除按钮：红色圆形 + 垃圾桶，**弹入**（0.2s，`scale(.55) rotate(-18deg) → 1`，设计稿 §6）。
 *
 * 它是预览卡/大图上唯一的危险操作，所以颜色走错误色而不是普通控制色；
 * 尺寸比控制按钮小一圈（27dp），这样它"贴"在卡片角上而不是压住图片。
 */
@Composable
private fun ScreenshotDeleteButton(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(ScreenshotPreviewSpec.DELETE_BUTTON_DURATION_MILLIS),
        label = "screenshot-delete-enter",
    )
    val startScale = ScreenshotPreviewSpec.DELETE_BUTTON_START_SCALE
    Surface(
        onClick = onDelete,
        modifier = modifier
            .size(PlayerScreenshotDeleteButtonSize)
            .graphicsLayer {
                val scale = startScale + (1f - startScale) * progress
                scaleX = scale
                scaleY = scale
                rotationZ = ScreenshotPreviewSpec.DELETE_BUTTON_START_ROTATION_DEGREES * (1f - progress)
                alpha = progress
            }
            .semantics { contentDescription = "删除截图" },
        shape = CircleShape,
        // 危险操作用 Material 的错误色（与 ShortsScreen 的删除按钮同一口径），
        // 底色是错误容器色而不是纯红：贴在小图角上时不至于抢走截图本身的注意力。
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.error,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = YingLiIcon.DELETE.imageVector,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 预览卡宽度：设计稿 §4.10 的 116px。 */
internal val PlayerScreenshotPreviewCardWidth = 116.dp

/** 预览卡白色描边（设计稿 `border: 2px solid rgb(255 255 255 / .86)`）。 */
private val PlayerScreenshotPreviewBorderColor = Color.White.copy(alpha = 0.86f)

/**
 * 描边宽度取 1.5dp（≈ 设计稿 2px）而不是 2dp：卡片只有 116dp 宽，
 * 2dp 描边在 3x 屏上会占掉超过 6px，白边明显压住截图内容。
 */
private val PlayerScreenshotPreviewBorderWidth = 1.5.dp
private val PlayerScreenshotPreviewCornerRadius = 7.dp
private val PlayerScreenshotPreviewProgressHeight = 4.dp

/**
 * 截图胶囊的高度：比工具托盘的按钮高出 [PlayerPortraitControlsSpacing] 一档
 * （48dp + 16dp = 64dp）。
 *
 * 为什么是"增大"而不是沿用按钮尺寸：胶囊里并排放着四枚圆按钮，高度与按钮相等时
 * 胶囊上下沿正好贴着按钮顶点，看起来是一排按钮而不是"一个容器里的一组按钮"
 * （用户实测反馈原胶囊偏小）。高出一档后，四周留出 [ScreenshotCapsuleInnerPadding]
 * 的呼吸圈。**不要再退回到 PlayerChromeButtonSize 或另写一个更小的字面量。**
 *
 * 它同时决定辅助带的满高（[PlayerAuxiliaryBandHeight] = 本值 + 同一个间距），
 * 所以改这里会同时影响托盘行与胶囊在底栏里的竖直位置。
 *
 * `get()` 而不是顶层 `val`：它引用底栏文件里的 [PlayerPortraitControlsSpacing]，
 * 而底栏文件又引用本常量——顶层 `val` 会构成初始化环（先加载谁就拿到谁 0.dp 的默认值）。
 * 计算属性与取值顺序无关。**不要再改回顶层 val。**
 */
internal val PlayerScreenshotCapsuleHeight: Dp
    get() = PlayerChromeButtonSize + PlayerPortraitControlsSpacing

/**
 * 胶囊内所有按钮（含捕获按钮）的尺寸：**就等于工具托盘的按钮尺寸** [PlayerChromeButtonSize]。
 *
 * 用户实测反馈原实现的胶囊按钮（40dp）比托盘按钮（48dp）小一圈，同屏切换时像是换了一套控件；
 * 这里用一个等值别名把它绑死，单测直接断言两者相等——任何"顺手调小/调大胶囊按钮"的改动都会红。
 * **不要在胶囊里出现别的尺寸字面量。**
 */
internal val PlayerScreenshotCapsuleButtonSize = PlayerChromeButtonSize

/**
 * 胶囊内边距 = (胶囊高度 - 按钮尺寸) / 2：水平方向也取同一个值，
 * 四周呼吸圈才是一致的（写死 4dp 会让左右比上下窄一半）。
 */
internal val ScreenshotCapsuleInnerPadding: Dp
    get() = (PlayerScreenshotCapsuleHeight - PlayerScreenshotCapsuleButtonSize) / 2

/**
 * 胶囊内按钮之间的间距（12dp）。
 *
 * 比工具托盘里同组按钮的 [PlayerShortcutSpacing]（8dp）更大：托盘按钮之间的空隙外侧还有整行的
 * 空白可以"借"，而胶囊是一整块容器，同样的间距在胶囊里会明显更挤（用户实测反馈"按钮挤在一起"）。
 * 12dp 与项目里 8/12/16 那一档间距一致，不引入新数字。
 */
internal val PlayerScreenshotCapsuleButtonSpacing = 12.dp

/**
 * 截图胶囊滑入/滑出（含淡入淡出）的时长（360ms）。
 *
 * 比底栏三段的 [TRANSPORT_SECTION_TRANSITION_MILLIS]（240ms）**慢一档**，理由是：
 *   · 胶囊是"工具入口"，进出比播放控制更需要从容——240ms 与三段淡入同拍时，
 *     胶囊的横向滑行会显得仓促、和底栏的收起挤在一起（用户实测反馈"太快"）；
 *   · 胶囊比底栏按钮大一圈，同样的位移速度下大控件看起来更快，放慢后速度感才与底栏一致。
 * 辅助带的"退出留位窗口"必须跟着它走（见 `BottomPlaybackControls` 的 capsuleBandHeld），
 * 否则带子会在胶囊还没滑完时提前塌掉。**不要为了"跟底栏同拍"把它改回 240ms。**
 */
internal const val SCREENSHOT_CAPSULE_TRANSITION_MILLIS = 360

/** 删除按钮尺寸（设计稿 27px）。 */
private val PlayerScreenshotDeleteButtonSize = 27.dp

/** 大图预览的遮罩色：压暗画面但保留可见（与画面暗角同一族，不引入新色相）。 */
private val PlayerScreenshotPreviewScrimColor = Color.Black.copy(alpha = 0.72f)

/**
 * 大图预览占整屏宽度的比例。
 *
 * 取 0.82 而不是"尽量铺满"：留出的边距让用户一眼看出这是浮层、
 * 也保证图上的删除按钮不会贴到屏幕边上（小屏机型上删除按钮本来就在图的右上角外侧）。
 */
private const val PlayerScreenshotPreviewExpandedWidthFraction = 0.82f
