package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderTrackHeight

/**
 * A–B 区间在进度条上的**几何与视觉判定**（全部是纯函数 / 纯数据，因而可以在 JVM 上逐条钉住）。
 *
 * ## 这一层为什么必须单独存在
 *
 * 进度条是**全时长线性**映射：`x = 轨道宽 × 时间 / 总时长`。于是"标记看不见"不是画法问题，
 * 而是信息密度问题 —— 真实案例：95 分钟（5 700 000ms）视频上 5 秒（5 000ms）区间只占
 * **0.088%** 宽度，1000px 轨道上不到 1px，两端标记必然重合。
 *
 * 在这一层之前，那个区间画出来就是"两个点糊在一起、什么也读不出"。现在它被拆成三条**可验证**的规则：
 *  1. **最小可视宽度**：区间渲染宽度 = `max(真实宽度, [MIN_AB_RANGE_WIDTH])`，
 *     以**中心为锚**放大，并夹在轨道可视范围内；
 *  2. **合并判定**：A、B 的像素距离小于"标记直径 + 最小间距"（即
 *     [AbMarkerMergeThreshold] = 2 × [AbMarkerRadius] + [AbMarkerMinGap]）时不再画两个圆，
 *     改画**一枚合并标记** —— 阈值由标记尺寸**推导**，不是另拍一个数；
 *  3. **夸大必须可辨识**：被放大的区间边缘一律走**虚线**（见 [abRangeGeometry] 的
 *     [AbRangeGeometry.exaggerated]），避免用户把放大后的长度当成真实长度。
 *
 * 三条规则都在这里判定，绘制侧（`PlayerTransportControls` 的进度行 Canvas）只负责照着画 ——
 * 判定与绘制分开之后，"95 分钟 + 5 秒区间"这种真实案例才有可断言的形态（见 `AbLoopMathTest`）。
 */

/**
 * A–B 区间高亮的**竖直厚度**。
 *
 * 取 [YingLiSliderTrackHeight]（进度条轨道本体厚度）的 6 倍 = `24dp`，但夹在
 * `[YingLiSliderTrackHeight], 32dp` 之间：它必须**明显厚于轨道**（4dp）才看得见，
 * 又不能把整行 48dp 的触控带吃掉。
 *
 * 注意它是"区间色块"的厚度，不是标记的尺寸 —— 标记（两端圆点 / 合并块）另有自己的直径，
 * 见 [AbMarkerRadius]。两者刻意分开：色块的厚度表达"这一段在循环"，标记的尺寸表达"两端在哪"。
 */
internal val AbRangeBandHeight: Dp
    get() = (YingLiSliderTrackHeight * 6).coerceIn(YingLiSliderTrackHeight, 32.dp)

/**
 * 区间两端的**标记半径**（圆点半径 / 合并块半高）。
 *
 * 推导（不是拍脑袋）：进度条自己的滑杆圆钮半径是 [YingLiSliderThumbRadius] = `7dp`，
 * 轨道本体是 `4dp`。标记必须**比轨道明显**（否则看不见）、又**比滑杆圆钮小一档**
 * （否则播放位置与区间端点分不清）。`(7 + 4) / 2 = 5.5dp` 正好落在这一档，
 * 换算成直径 `11dp`，是轨道厚度的 `2.75` 倍 —— 在 1000px 轨道上它占 11px，肉眼可辨。
 *
 * 它同时是**合并阈值的唯一来源**（见 [AbMarkerMergeThreshold]），所以改这里会连带改判定，
 * 不允许在绘制处再写一个半径字面量。
 */
internal val AbMarkerRadius: Dp
    get() = (YingLiSliderThumbRadius + YingLiSliderTrackHeight) / 2

/**
 * 两枚标记之间的**最小可辨间距**（边到边）。
 *
 * 取 `10dp`（≈ 标记直径 `11dp`）：两个圆点边缘之间至少要有"接近一个标记"的空隙，
 * 才能被看成一个"A 在左、B 在右"的区间而不是一个糊掉的点。小于它就该合并。
 *
 * 它**不是**独立的一组留白审美：它的唯一作用是参与推导 [AbMarkerMergeThreshold]。
 */
internal val AbMarkerMinGap: Dp = 10.dp

/**
 * 合并阈值：两端标记的**中心距离**小于它时改画一枚合并标记。
 *
 * 推导：两枚半径为 [AbMarkerRadius] 的圆，中心距为 `d` 时边到边间距是 `d − 2r`；
 * 要求它不小于 [AbMarkerMinGap]，即 `d ≥ 2r + gap`。于是阈值就是这条不等式取等号：
 *
 *     AbMarkerMergeThreshold = 2 × AbMarkerRadius + AbMarkerMinGap = 11dp + 10dp = 21dp
 *
 * 与 [MIN_AB_RANGE_WIDTH] 的关系不是巧合而是**包含关系**：最小可视宽度按"两枚标记正好
 * 边挨边"取 `4r = 2 × 标记直径 = 22dp`，而 `4r > 2r + gap`（`2r = 11 > gap = 10`），
 * 所以被判为"需要合并"的区间一定落在最小宽度分支上，两个分支互不打架。
 */
internal val AbMarkerMergeThreshold: Dp
    get() = AbMarkerRadius * 2 + AbMarkerMinGap

/**
 * 区间的**最小可视宽度**（= 两枚标记并排所需的宽度）。
 *
 * 推导：合并标记的形状就是"两枚标记并排叠在一起"，所以它的宽度必须容得下
 * `2 × 标记直径 = 4r = 22dp`，否则左边的圆会被右边的圆盖掉、连合并块都看不完整。
 * 于是取 `4 × [AbMarkerRadius] = 22dp`（轨道厚度的 5.5 倍）。
 *
 * 为什么不是 6–10dp：那个量级比标记自己的直径（11dp）还小，画出来仍然是一个点，
 * 与"看得见一个区间"这个目的相冲突；22dp 是**由标记尺寸推出的下界**，不是审美选择。
 */
internal val MIN_AB_RANGE_WIDTH: Dp
    get() = AbMarkerRadius * 4

/**
 * 区间色块左右两端的圆角半径。
 *
 * 取 `2dp` 而不是半个高度（那会变成胶囊）：夸张后的区间可能只有 [MIN_AB_RANGE_WIDTH] 宽，
 * 全圆角会把它变成"一个大圆点"，与小圆点标记混淆。`2dp` 保留"这是一段矩形区间"的读感，
 * 又与轨道本身的描边圆角同一档。
 */
internal val AbRangeCornerRadius: Dp = 2.dp

/** 夸大区间的虚线描边宽度：与标记描边同档，不引入新的视觉重量。 */
internal val AbRangeDashStrokeWidth: Dp = 1.dp

/** 夸大区间的虚线段长与间隔（都是段长的 1 倍）：与项目其余虚线同构。 */
internal val AbRangeDashLength: Dp = 3.dp

/**
 * 区间色块的填充透明度。
 *
 * 沿用旧实现的 `0.28`：它是"底下一层提示色"而不是前景色，必须**明显轻于**标记与进度条本身，
 * 否则区间色块会盖掉已经播过的那一段（active track）。夸大与否**不改这个值** ——
 * 夸大的视觉区分交给虚线（见 [AbRangeGeometry.exaggerated]），不借透明度表达第二层含义。
 */
internal const val AbRangeFillAlpha = 0.28f

/** 合并标记中间那道**缝**的宽度：分隔两个"半圆"，让合并块读起来是两枚标记而不是一个色块。 */
internal val AbMarkerSeamWidth: Dp = 1.dp

/**
 * 区间在**轨道坐标系**里的几何（像素）。
 *
 * [startPx] / [endPx] 是实际要画的区间两端；[markerCenterXPx] / [markerCenterYPx] 是标记中心
 * （合并时是区间中心，未合并时两端各画一个）；[merged] 表示"画一枚合并标记"；
 * [exaggerated] 表示渲染宽度**不等于**真实宽度（必须走可辨识的视觉区分）。
 */
internal data class AbRangeGeometry(
    /** 区间是否完整（A、B 都在）。只有完整时才有区间可画。 */
    val valid: Boolean,
    val startPx: Float,
    val endPx: Float,
    val markerCenterXPx: Float,
    val markerCenterYPx: Float,
    val realWidthPx: Float,
    val merged: Boolean,
    val exaggerated: Boolean,
) {
    /** 实际渲染宽度。 */
    val visualWidthPx: Float get() = endPx - startPx

    internal companion object {
        /** 区间不完整（缺 A 或 B，或轨道还不可用）：什么都不画。 */
        val NONE = AbRangeGeometry(
            valid = false,
            startPx = 0f,
            endPx = 0f,
            markerCenterXPx = 0f,
            markerCenterYPx = 0f,
            realWidthPx = 0f,
            merged = false,
            exaggerated = false,
        )
    }
}

/**
 * 算出 A–B 区间在**轨道坐标系**里的最终几何。
 *
 * [fractionStart] / [fractionEnd] 是两端在**全时长**里的比例（0..1）；本函数**不**重排两端 ——
 * 域层已保证 A 不晚于 B（见 `AbLoopLimiter`），这里再排一次只会掩盖上游的错误。
 * **真实宽度由这两个比例自己推出**（不另外传一个宽度），这样"真实长度"只有一个来源。
 *
 * 四条规则（顺序即优先级）：
 *  1. 比例先夹到 `0..1`（越界的数据不允许画出轨道外）；
 *  2. 宽度不足 [minWidthPx] 时以**中心为锚**放大到它 —— 中心锚定的意义是"区间真实落在哪一段"
 *     仍然成立，只是两端各自向外让出一半；
 *  3. 放大后**整体平移**回轨道内（贴左 / 贴右），不允许任何一部分溢出轨道；
 *  4. 中心距离小于 [mergeThresholdPx] 时判定为**合并标记**。
 *
 * [exaggerated] 只表达"画出来的宽度 ≠ 真实宽度"，它是绘制侧**必须做视觉区分**的输入：
 * 被放大的区间若和真实区间长得一样，用户会把 22dp 误读成"这段区间有这么长"。
 */
internal fun abRangeGeometry(
    trackWidthPx: Float,
    fractionStart: Float,
    fractionEnd: Float,
    minWidthPx: Float,
    mergeThresholdPx: Float,
    markerCenterYPx: Float,
): AbRangeGeometry {
    // 轨道还没测量出来（首帧 / 不可用）：不画，避免画在 x=0 上闪一下。
    if (trackWidthPx <= 0f) return AbRangeGeometry.NONE

    fun fractionToX(fraction: Float): Float = (trackWidthPx * fraction).coerceIn(0f, trackWidthPx)

    val startX = fractionToX(fractionStart)
    val endX = fractionToX(fractionEnd)
    val rawWidth = (endX - startX).coerceAtLeast(0f)
    val realWidth = rawWidth.coerceAtMost(trackWidthPx)
    // 真实中心：放大以它为锚（用夹过的一对端点算，越界输入不会把中心带出轨道）。
    val realCenterX = (startX + endX) / 2f

    val width = maxOf(realWidth, minWidthPx.coerceAtLeast(0f)).coerceAtMost(trackWidthPx)
    // 以真实中心为锚展开，再整体平移回轨道内。coerceIn 的两端可能反序（轨道比区间还窄），
    // 所以下界也要夹一次，保证 start ≤ end。
    val maxStart = (trackWidthPx - width).coerceAtLeast(0f)
    val visualStart = (realCenterX - width / 2f).coerceIn(0f, maxStart)

    return AbRangeGeometry(
        valid = true,
        startPx = visualStart,
        endPx = visualStart + width,
        markerCenterXPx = (startX + endX) / 2f,
        markerCenterYPx = markerCenterYPx,
        realWidthPx = realWidth,
        merged = (endX - startX) < mergeThresholdPx,
        exaggerated = width > realWidth + 0.01f,
    )
}
