package seeyuer.yingli.player.domain.playback

/**
 * 截图预览卡的动效与几何常量。全部是纯数据，UI 与单测读的是同一份数字。
 *
 * 依据：设计稿 §6「截图预览卡出现/消失：出现 `scale(2.4)→1` 飞入（0.42s）」与 §4.10
 * 「116px 宽，16:10，白色描边」。
 */
object ScreenshotPreviewSpec {
    /** 卡片出现时动画的起始缩放（设计稿 `scale(2.4)`）。 */
    const val ENTER_SCALE = 2.4f

    /** 飞入时长（设计稿 0.42s）。 */
    const val ENTER_DURATION_MILLIS = 420

    /** 删除按钮弹入时长（设计稿 §6「删除按钮弹入 0.2s」）。 */
    const val DELETE_BUTTON_DURATION_MILLIS = 200

    /** 删除按钮弹入的起始缩放与旋转（设计稿 keyframes `scale(.55) rotate(-18deg)`）。 */
    const val DELETE_BUTTON_START_SCALE = 0.55f
    const val DELETE_BUTTON_START_ROTATION_DEGREES = -18f

    /** 预览卡宽高比 16:10。 */
    const val ASPECT_RATIO = 1.6f
}

/** 卡片尺寸（像素）：16:10 的卡片只需要宽度就能推出高度。 */
data class ScreenshotPreviewSize(val width: Float, val height: Float) {
    val isValid: Boolean get() = width > 0f && height > 0f
}

/** 卡片位置（像素，根布局坐标系）。 */
data class ScreenshotPreviewPoint(val x: Float, val y: Float)

/** 一次飞入动画的完整几何：起点与终点（都是**根布局坐标系**里的卡片中心）。 */
data class ScreenshotPreviewTrack(
    val startCenter: ScreenshotPreviewPoint,
    val endCenter: ScreenshotPreviewPoint,
) {
    val isDegenerate: Boolean
        get() = startCenter.x == endCenter.x && startCenter.y == endCenter.y
}

/**
 * 由"飞行起点"与"卡片静止时左上角"推出飞入轨迹。
 *
 * 起点 = [startCenterX]/[startCenterY]，即**视频画面区域（不含黑边）的右下角**：
 * 这个点由 `VideoRotationStageMath.pictureBounds` 按 letterbox 几何算出并换算到根布局坐标系
 * （画面有黑边时它明显高于屏幕右下角；未知宽高比时退化为整块画布）。
 * 终点 = 卡片静止时的中心（左上角 + 尺寸/2），卡片由调用方按 safeDrawing 内边距摆在屏幕左上角。
 * 两个点都在**根布局坐标系**里量，因此不存在"两套坐标系叠一起"的可能。
 *
 * 按钮位置或卡片尺寸还没测量到（首帧、或测试环境里没有布局）时返回 `null`：
 * 调用方据此**等几何到位再开始动画**，而不是先动起来再纠正——
 * "首帧没几何、动画已经跑了，量到之后位置突变"正是"先反向移动再回来"这类缺陷的来源。
 */
fun screenshotPreviewTrack(
    startCenterX: Float?,
    startCenterY: Float?,
    targetX: Float,
    targetY: Float,
    size: ScreenshotPreviewSize,
): ScreenshotPreviewTrack? {
    if (startCenterX == null || startCenterY == null) return null
    if (!size.isValid) return null
    return ScreenshotPreviewTrack(
        startCenter = ScreenshotPreviewPoint(startCenterX, startCenterY),
        endCenter = ScreenshotPreviewPoint(targetX + size.width / 2f, targetY + size.height / 2f),
    )
}

/**
 * 轨迹上某个进度 `t`（0..1）的卡片**中心**（根布局坐标系）。
 *
 * 这是位置唯一的一次插值：整个飞入只有这一个位移驱动，`t` 由同一个 0→1 的动画进度给出。
 * 线性插值保证 `t` 递增时中心点沿直线从起点走到终点，不存在回头路。
 */
fun screenshotPreviewCenterAt(track: ScreenshotPreviewTrack, progress: Float): ScreenshotPreviewPoint {
    val t = progress.coerceIn(0f, 1f)
    return ScreenshotPreviewPoint(
        x = track.startCenter.x + (track.endCenter.x - track.startCenter.x) * t,
        y = track.startCenter.y + (track.endCenter.y - track.startCenter.y) * t,
    )
}

/**
 * 轨迹上某个进度 `t` 的 `graphicsLayer` 平移量（以**卡片尺寸**为单位）。
 *
 * 推导（UI 侧把 `transformOrigin` 锚在**左上角** `TransformOrigin(0f, 0f)`，这是公式成立的前提）：
 * 层自身的 scale 会把平移量一起放大，于是卡片中心的实际位置是
 * ```
 * center = target(不动点) + layerScale * (translation + size / 2)
 * ```
 * 令它等于 [screenshotPreviewCenterAt]，解得
 * ```
 * translation = (center(t) - target) / layerScale - size / 2
 * ```
 * 起始帧 `layerScale = ENTER_SCALE`、`center = 画面右下角`，卡片正好从画面右下角"长出来"；
 * 结束帧 `layerScale = 1`、`center = 终点`，平移量归零，卡片静静停在左上角。
 */
fun screenshotPreviewEnterTranslation(
    track: ScreenshotPreviewTrack,
    size: ScreenshotPreviewSize,
    targetX: Float,
    targetY: Float,
    progress: Float,
): Pair<Float, Float> {
    if (!size.isValid) return 0f to 0f
    val t = progress.coerceIn(0f, 1f)
    val scale = screenshotPreviewEnterScale(t)
    val center = screenshotPreviewCenterAt(track, t)
    val dx = (center.x - targetX) / (scale * size.width) - 0.5f
    val dy = (center.y - targetY) / (scale * size.height) - 0.5f
    return dx to dy
}

/**
 * 预览卡在某个动画进度下的缩放。
 *
 * progress 0 → [ScreenshotPreviewSpec.ENTER_SCALE]，1 → 1，线性插值。
 * 缩放与位移共用同一个进度量——**位置只有这一个驱动**（需求：不允许"入场动画管一段、
 * 手工 translate 管另一段"，那正是之前截图胶囊竖直跳变的同类缺陷）。
 */
fun screenshotPreviewEnterScale(progress: Float): Float =
    ScreenshotPreviewSpec.ENTER_SCALE - (ScreenshotPreviewSpec.ENTER_SCALE - 1f) * progress.coerceIn(0f, 1f)
