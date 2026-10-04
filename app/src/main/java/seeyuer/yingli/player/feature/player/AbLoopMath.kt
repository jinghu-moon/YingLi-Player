package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderTrackHeight
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon

/**
 * A–B 区间在进度条上的**几何与视觉判定**（全部是纯函数 / 纯数据，因而可以在 JVM 上逐条钉住）。
 *
 * ## 这一层为什么必须单独存在
 *
 * 进度条是**全时长线性**映射：`x = 轨道宽 × 时间 / 总时长`。于是"标记看不见"不是画法问题，
 * 而是信息密度问题 —— 真实案例：95 分钟（5 700 000ms）视频上 5 秒（5 000ms）区间只占
 * **0.088%** 宽度，1000px 轨道上不到 1px，两端标记必然重合。
 *
 * 在这一层之前，那个区间画出来就是"两个点糊在一起、什么也读不出"。现在它被拆成四条**可验证**的规则：
 *  1. **最小可视宽度**：区间渲染宽度 = `max(真实宽度, [MIN_AB_RANGE_WIDTH])`，
 *     以**中心为锚**放大，并夹在轨道可视范围内；
 *  2. **合并判定**：A、B 的像素中心距小于"徽标直径 + 最小间距"（即
 *     [AbMarkerMergeThreshold] = [AbMarkerDiameter] + [AbMarkerMinGap]）时不再画两枚徽标，
 *     改画**一枚合并块**（宽度 == 最小可视宽度，两半各放一个字形框）——
 *     阈值由徽标尺寸**推导**，不是另拍一个数，而且它**就是**最小可视宽度（同一个等式）；
 *  3. **端点徽标必须在轨道内**：徽标是**字母徽标**（内含 [YingLiIcon.LETTER_A] /
 *     [YingLiIcon.LETTER_B] 的字形，见 [AbMarkerDiameter] 的尺寸依据），比原来的小圆点大一档，
 *     贴边时中心向内收一个半径，保证整枚徽标（含字母）完整可见；
 *  4. **夸大必须可辨识**：被放大的区间边缘一律走**虚线**（见 [abRangeGeometry] 的
 *     [AbRangeGeometry.exaggerated]），避免用户把放大后的长度当成真实长度。
 *
 * 四条规则都在这里判定，绘制侧（`PlayerTransportControls` 的区间层）只负责照着画 ——
 * 判定与绘制分开之后，"95 分钟 + 5 秒区间"这种真实案例才有可断言的形态（见 `AbLoopMathTest`）。
 *
 * **本层不再有"区间色块"**：区间强调改用"区间内正常亮度 / 区间外压暗"表达
 * （见 [AbRangeOutsideAlpha] 与绘制侧的层序注释），所以这里也不再有填充透明度常量。
 */

/**
 * 区间带的**竖直跨度**（夸大时那两条虚线所在的上下沿）。
 *
 * 取 [YingLiSliderTrackHeight]（进度条轨道本体厚度）的 6 倍 = `24dp`，但夹在
 * `[YingLiSliderTrackHeight], 32dp` 之间：它必须**明显厚于轨道**（4dp）才有可读的边界，
 * 又不能把整行 48dp 的触控带吃掉。
 *
 * 它**不是**徽标的尺寸：徽标另有自己的直径（见 [AbMarkerDiameter]）。两者刻意分开 ——
 * 虚线边表达"这一段被放大过"的跨度，徽标表达"两端在哪"。
 */
internal val AbRangeBandHeight: Dp
    get() = (YingLiSliderTrackHeight * 6).coerceIn(YingLiSliderTrackHeight, 32.dp)

/**
 * 徽标里**字母字形的方框边长** = 滑杆圆钮的直径（`2 × 7dp = 14dp`）。
 *
 * 依据：圆钮是这一行上**已有的最大圆形元素**，字形框取它的直径，字母在进度行上的分量就与
 * "现在播到哪"同一量级 —— 既不抢走圆钮的注意力，也不会小到认不出字母（量化依据见
 * [AbMarkerDiameter]）。
 */
internal val AbMarkerGlyphSize: Dp
    get() = YingLiSliderThumbRadius * 2

/**
 * 徽标四周的**留白**（字形框到徽标边缘）= 与 [AbRangeCornerRadius] 同一档的 `2dp`。
 *
 * 它同时决定徽标的圆角（见 [AbMarkerCornerRadius]）：圆角取留白的 2 倍时，字形框自己也是一个
 * `2dp` 圆角 —— 与区间虚线、轨道描边同一套圆角档位，不引入新数字。
 */
internal val AbMarkerGlyphPadding: Dp = 2.dp

/**
 * 端点**字母徽标**的直径：`字形框 + 两侧留白 = 14dp + 4dp = 18dp`（轨道厚度的 4.5 倍）。
 *
 * ## 尺寸依据（为什么不是原来的 11dp）
 *
 * 旧端点是**实心小圆**，尺寸只受"比轨道明显、比圆钮小一档"约束；现在徽标里要放**字母**，
 * 尺寸就必须由"字母认不认得出"反推，而这件事可以量化：
 *
 *  1. 字母用图标字形画（[YingLiIcon.LETTER_A] / [YingLiIcon.LETTER_B]，Tabler `letter-a`/`letter-b`）。
 *     实测它们的路径落在 24 单位视口的 `x∈[7,17], y∈[4,20]`，加上 2 单位描边的半宽后是 `12 × 18`，
 *     即墨迹宽 `0.5 × 字形框`、墨迹高 `0.75 × 字形框`（见 [AbLetterInkHeightRatio]，
 *     由 `AbLoopMathTest` 从图标数据复算，换了字形那条测试就会红）。
 *  2. 本项目允许的**最小文字**是 [PlayerChromeTextMinFontSize] = `10sp`：Roboto 大写字母的墨高约为
 *     `0.71em`，即那个下限对应的墨高约 `7.1dp`。徽标里的字母至少要**不低于**它才算可读。
 *  3. 字形框取 `14dp`（= 圆钮直径）时，字母墨高 = `0.75 × 14 = 10.5dp > 7.1dp`，
 *     比项目允许的最小文字还大一圈；再小一档（例如旧圆点的 11dp 直径 → 字形框 7dp → 墨高 5.25dp）
 *     就已经低于那个下限了。
 *
 * 于是直径 = `14 + 2 × 2 = 18dp`。它同时是**合并阈值的唯一来源**（见 [AbMarkerMergeThreshold]）
 * 与**最小可视宽度的唯一来源**（见 [MIN_AB_RANGE_WIDTH]），所以改这里会连带改判定，
 * 不允许在绘制处再写一个尺寸字面量。
 */
internal val AbMarkerDiameter: Dp
    get() = AbMarkerGlyphSize + AbMarkerGlyphPadding * 2

/**
 * 徽标半径（= [AbMarkerDiameter] / 2 = `9dp`）：绘制侧一切"以徽标中心为原点"的偏移都用它。
 *
 * 它同时喂给 `abRangeGeometry`（以像素形式），所以**不允许**在绘制处再写一个半径字面量。
 */
internal val AbMarkerRadius: Dp
    get() = AbMarkerDiameter / 2

/**
 * 徽标的圆角半径 = 留白的 2 倍 = `4dp`。
 *
 * 为什么是 2 倍而不是"半个直径"（那会退化成圆点，与旧实现分不出来）：圆角半径 `R` 的圆角矩形
 * 向内缩一个留白 `p` 之后，内框的圆角半径正好是 `R − p`；取 `R = 2p` 时内框圆角 = `p` = `2dp`，
 * 也就是与区间虚线、轨道描边同一档 —— 字形框本身是一个 `2dp` 圆角的小方块。
 * 小于半直径的圆角同时保证徽标读起来是"一枚字母牌"而不是"一个小圆点"。
 */
internal val AbMarkerCornerRadius: Dp
    get() = AbMarkerGlyphPadding * 2

/**
 * 字母字形在 24 单位视口里的**墨迹占比**（从图标自己的 path 数据量出来的，不是估的）。
 *
 * 见 [AbMarkerDiameter] 的推导：`letter-a` / `letter-b` 的路径是 `x∈[7,17], y∈[4,20]`，
 * 加上 2 单位描边的半宽（1）后为 `12 × 18`，即 `12/24 = 0.5`、`18/24 = 0.75`。
 *
 * 绘制侧**不读**这两个数（字形由图标库按字形框缩放），它们是徽标尺寸的推导依据；
 * `AbLoopMathTest` 会从 `YingLiIcon.LETTER_A` / `LETTER_B` 的路径数据**复算**它们。
 */
internal const val AbLetterInkWidthRatio = 0.5f

/** 字母字形的墨迹高占比（`0.75 × 字形框`），依据见 [AbLetterInkWidthRatio]。 */
internal const val AbLetterInkHeightRatio = 0.75f

/**
 * 两枚徽标之间的**最小可辨间距**（边到边）。
 *
 * 取 `10dp`：它回答的是"两枚徽标**断开**得看不看得出来"，不是"再放一枚徽标要多大空隙"。
 * 它明显小于一枚徽标（18dp），又比项目里任何一条缝隙 / 描边档（1–4dp）大得多，是"看得出断开"的量级；
 * 端点的身份由徽标里的**字母**给出，所以两端之间不需要再空出一整枚徽标的宽度。
 *
 * 它**不是**独立的一组留白审美：唯一作用是参与推导 [AbMarkerMergeThreshold]，而那条阈值**同时**
 * 就是 [MIN_AB_RANGE_WIDTH]，所以它也是"这一段画多宽"的唯一可调量 —— 调大它会把越来越多的区间
 * 推进"按最小宽度画"的那一档（见 [MIN_AB_RANGE_WIDTH] 里 36dp 的教训），因此取值必须克制。
 */
internal val AbMarkerMinGap: Dp = 10.dp

/**
 * 合并阈值：两端徽标的**中心距离**小于它时改画一枚合并块。
 *
 * 推导：两枚直径为 `D` 的徽标，中心距为 `d` 时边到边间距是 `d − D`；
 * 要求它不小于 [AbMarkerMinGap]，即 `d ≥ D + gap`。于是阈值就是这条不等式取等号：
 *
 *     AbMarkerMergeThreshold = AbMarkerDiameter + AbMarkerMinGap = 18dp + 10dp = 28dp
 *
 * 它同时**就是**最小可视宽度（[MIN_AB_RANGE_WIDTH]）—— 这不是巧合，而是同一个等式在回答
 * 同一件事：中心距小于 `D + gap` 时，两端连"边到边留出最小间距"都做不到，只能并成一枚块；
 * 而那一枚**合并块本身也正好需要这么宽**（块的两半各放一个字形框：`14dp × 2 = 28dp`）。
 * 两个判定因此由同一个数给出，不存在"合并了但没被放大"或"放大了却没合并"的中间态。
 */
internal val AbMarkerMergeThreshold: Dp
    get() = AbMarkerDiameter + AbMarkerMinGap

/**
 * 区间的**最小可视宽度** = 上面那条阈值（`28dp`）。
 *
 * 依据（两条合起来给同一个数）：
 *  1. **两端还分得开**：渲染出来的区间宽度必须容得下两枚徽标 + [AbMarkerMinGap] 的边到边间距，
 *     即 `≥ D + gap`；再窄就该由 [AbMarkerMergeThreshold] 判成合并，没有第二种画法；
 *  2. **合并块放得下两个字母**：合并块的宽度就是"两端还分得开"的那个宽度，而它的两半各要放一个
 *     字形框（[AbMarkerGlyphSize]，`14dp`）：`28dp / 2 = 14dp` 正好放得下。
 *
 * 为什么**不是** `2 × AbMarkerDiameter = 36dp`（本项目最初的写法）：那等于要求合并块容下两枚
 * "带留白的完整徽标"，而块的两半只需要容下**字形框**（留白只是块外缘的一圈，两枚徽标之间
 * 不需要各留一次）。多出来的 8dp 是纯粹的浪费，代价却很实在：`36dp` 在 400dp 手机上占轨道宽的
 * ~13%，也就是说**95 分钟影片上 12 分钟以内的区间会全部被画成同一个宽度**，"这段到底多长"
 * 在进度条上就读不出来了（真机 instrumented 用"95 分钟影片上的 10 分钟区间"抓出了这一条）。
 */
internal val MIN_AB_RANGE_WIDTH: Dp
    get() = AbMarkerMergeThreshold

/**
 * 区间色块左右两端的圆角半径。
 *
 * 取 `2dp` 而不是半个高度（那会变成胶囊）：夸张后的区间可能只有 [MIN_AB_RANGE_WIDTH] 宽，
 * 全圆角会把它变成"一个大圆点"，与徽标混淆。`2dp` 保留"这是一段矩形区间"的读感，
 * 又与轨道本身的描边圆角同一档。
 */
internal val AbRangeCornerRadius: Dp = 2.dp

/** 夸大区间的虚线描边宽度：与徽标同档，不引入新的视觉重量。 */
internal val AbRangeDashStrokeWidth: Dp = 1.dp

/** 夸大区间的虚线段长与间隔（都是段长的 1 倍）：与项目其余虚线同构。 */
internal val AbRangeDashLength: Dp = 3.dp

/**
 * AB 区间**外**那一段的亮度（同一批像素保留的 alpha）。
 *
 * ## 为什么是"降 alpha"而不是"叠黑"
 *
 * 区间强调改成"区间内正常亮度 / 区间外压暗"之后，压暗必须作用在**轨道底与播放进度填充之后**
 * （层序见绘制侧），做法是把**区间外**那一段已经画好的像素的 alpha 按这个系数缩放
 * （`BlendMode.DstOut`，只覆盖区间外，区间内一个像素都不碰），也就是"**同色**降 alpha"——
 * 颜色不变、只是变淡，画面与底栏其它内容都不受影响。
 * 反过来"叠一层黑"在播放器 chrome 上不是等价做法：chrome 背景本身就是黑的，
 * 叠黑只会在轨道之外多压一层已经在暗处的画面，既看不见压暗、又脏了画面。
 *
 * 0.55（实测取证见 `.tmp-abloop-badges`）的三个落点：已播放的那一段（`controlPrimary`，白）
 * 降到 `140/255`，未播放的轨道（白 24%）降到 `33/255`，区间内保持 `255/255` 与 `61/255` ——
 * "已播放 / 未播放"与"区间内 / 区间外"两个维度因此仍然读得出来（两档亮度 × 一个区间系数）。
 *
 * ## 取值与下限
 *
 * 取 `0.55`：已播放的那一段（`controlPrimary`，白）会降到 55% —— 与区间内的 100%
 * 一眼分得开；未播放的轨道（白 24%）降到 13%，仍在可辨范围内（不会整段消失）。
 *
 * **下限 `0.30` 是口径**（[AbRangeOutsideAlphaFloor]）：再低就会把区间外压成"几乎看不见"，
 * 用户读不出"轨道还在、只是不在循环里"。两条常量都由 `AbLoopMathTest` 钉住（含"取值不低于下限"），
 * **不允许**在绘制处再写一个更低的字面量。
 */
internal const val AbRangeOutsideAlpha = 0.55f

/** 区间外亮度的**下限**（口径，见 [AbRangeOutsideAlpha]）：任何取值都不允许低于它。 */
internal const val AbRangeOutsideAlphaFloor = 0.30f

/** 合并块中间那道**缝**的宽度：分隔两个"半块"，让合并块读起来是两枚徽标而不是一个色块。 */
internal val AbMarkerSeamWidth: Dp = 1.dp

/**
 * 区间在**轨道坐标系**里的几何（像素）。
 *
 * [startPx] / [endPx] 是实际要画的区间两端（放大、夹取之后）；[centerXPx] 是这一段**渲染出来**的
 * 水平中心（缝画在这里）；[aMarkerCenterXPx] / [bMarkerCenterXPx] 是两枚徽标的中心；
 * [merged] 表示"画一枚合并块"；[exaggerated] 表示渲染宽度**不等于**真实宽度
 * （必须走可辨识的视觉区分）。
 */
internal data class AbRangeGeometry(
    /** 轨道是否可用（宽度 > 0）。false = 什么都不画。 */
    val valid: Boolean,
    /** 区间是否完整（A、B 都在）。false = 只设了 A：只有一枚徽标，没有区间、没有缝、也不压暗。 */
    val complete: Boolean,
    val startPx: Float,
    val endPx: Float,
    /** 渲染出来的这一段（区间带 / 合并块）的水平中心：缝与一切"居中于这一段"的东西都用它。 */
    val centerXPx: Float,
    val aMarkerCenterXPx: Float,
    /** B 徽标中心；[complete] 为 false 时等于 [aMarkerCenterXPx]（不画）。 */
    val bMarkerCenterXPx: Float,
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
            complete = false,
            startPx = 0f,
            endPx = 0f,
            centerXPx = 0f,
            aMarkerCenterXPx = 0f,
            bMarkerCenterXPx = 0f,
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
 * [fractionStart] / [fractionEnd] 是两端在**全时长**里的比例（0..1）；[fractionEnd] 为 `null`
 * 表示"只设了 A"（区间还不完整）—— 这件事**由几何层自己回答**，而不是让绘制侧偷偷传一个
 * `abEnd ?: abStart` 再在画的地方补一堆 `if`：那正是旧实现把 A 徽标画在"渲染段左端"（＝真实位置
 * 左边一个半径处）的成因。本函数**不**重排两端 —— 域层已保证 A 不晚于 B（见 `AbLoopLimiter`），
 * 这里再排一次只会掩盖上游的错误。**真实宽度由这两个比例自己推出**（不另外传一个宽度），
 * 这样"真实长度"只有一个来源。
 *
 * [markerDiameterPx] / [minMarkerGapPx] 是徽标尺寸与最小可辨间距的**像素值**：最小可视宽度与
 * 合并阈值（两者都是 `直径 + 间距`，见 [MIN_AB_RANGE_WIDTH]）都由它们**在函数内推导**，
 * 调用方不再各算一份（那样两处一旦不一致，判定与画出来的形状就会对不上）。
 *
 * 五条规则（顺序即优先级）：
 *  1. 比例先夹到 `0..1`（越界的数据不允许画出轨道外）；
 *  2. 宽度不足最小可视宽度时以**中心为锚**放大到它 —— 中心锚定的意义是"区间真实落在哪一段"
 *     仍然成立，只是两端各自向外让出一半；
 *  3. 放大后**整体平移**回轨道内（贴左 / 贴右），不允许任何一部分溢出轨道；
 *  4. 中心距离小于合并阈值时判定为**合并块**，两枚徽标改为并排放在这一块的两半里；
 *  5. 徽标中心夹在 `[半径, 轨道宽 − 半径]`：徽标比旧圆点大一档，贴边时必须向内收，
 *     否则最外侧那半个字母会被裁掉（区间端点的**精确**位置由区间边缘与读数行表达）。
 *
 * [exaggerated] 只表达"画出来的宽度 ≠ 真实宽度"，它是绘制侧**必须做视觉区分**的输入：
 * 被放大的区间若和真实区间长得一样，用户会把 28dp 误读成"这段区间有这么长"。
 */
internal fun abRangeGeometry(
    trackWidthPx: Float,
    fractionStart: Float,
    fractionEnd: Float?,
    markerDiameterPx: Float,
    minMarkerGapPx: Float,
    markerCenterYPx: Float,
): AbRangeGeometry {
    // 轨道还没测量出来（首帧 / 不可用）：不画，避免画在 x=0 上闪一下。
    if (trackWidthPx <= 0f) return AbRangeGeometry.NONE

    fun fractionToX(fraction: Float): Float = (trackWidthPx * fraction).coerceIn(0f, trackWidthPx)

    val diameter = markerDiameterPx.coerceAtLeast(0f)
    val radius = diameter / 2f
    val mergeThreshold = diameter + minMarkerGapPx.coerceAtLeast(0f)
    // 最小可视宽度**就是**合并阈值：同一个等式既回答"两端还分不分得开"，也回答"这一段画多宽"
    //（见 [MIN_AB_RANGE_WIDTH]）。
    val minWidth = mergeThreshold
    // 徽标中心的可达区间：两端各收一个半径（轨道比一枚徽标还窄时退化为轨道中心）。
    val minCenter = radius.coerceAtMost(trackWidthPx / 2f)
    val maxCenter = (trackWidthPx - radius).coerceAtLeast(minCenter)

    val startX = fractionToX(fractionStart)
    if (fractionEnd == null) {
        // 只设了 A：还没有区间可画（不压暗、不画缝），只有一枚徽标落在 A 的**真实位置**上。
        val aX = startX.coerceIn(minCenter, maxCenter)
        return AbRangeGeometry(
            valid = true,
            complete = false,
            startPx = aX,
            endPx = aX,
            centerXPx = aX,
            aMarkerCenterXPx = aX,
            bMarkerCenterXPx = aX,
            markerCenterYPx = markerCenterYPx,
            realWidthPx = 0f,
            merged = false,
            exaggerated = false,
        )
    }

    val endX = fractionToX(fractionEnd)
    val rawWidth = (endX - startX).coerceAtLeast(0f)
    val realWidth = rawWidth.coerceAtMost(trackWidthPx)
    // 真实中心：放大以它为锚（用夹过的一对端点算，越界输入不会把中心带出轨道）。
    val realCenterX = (startX + endX) / 2f

    val width = maxOf(realWidth, minWidth).coerceAtMost(trackWidthPx)
    // 以真实中心为锚展开，再整体平移回轨道内。coerceIn 的两端可能反序（轨道比区间还窄），
    // 所以下界也要夹一次，保证 start ≤ end。
    val maxStart = (trackWidthPx - width).coerceAtLeast(0f)
    val visualStart = (realCenterX - width / 2f).coerceIn(0f, maxStart)
    val visualEnd = visualStart + width
    val visualCenter = (visualStart + visualEnd) / 2f
    val merged = (endX - startX) < mergeThreshold

    // 合并块的两半**各放一个字形框**：字形中心落在半块的中间，也就是整块的 1/4 与 3/4 处
    //（半块宽 = 宽度/2，字形框居中于半块）。
    // 轨道窄到连两枚字形都放不下时向中心收，保证 A 徽标永远不越过 B 徽标（宁可重叠也不对调）。
    val aMarkerCenterX = if (merged) {
        (visualStart + width / 4f).coerceAtMost(visualCenter)
    } else {
        visualStart.coerceIn(minCenter, maxCenter)
    }
    val bMarkerCenterX = if (merged) {
        (visualEnd - width / 4f).coerceAtLeast(visualCenter)
    } else {
        visualEnd.coerceIn(minCenter, maxCenter)
    }

    return AbRangeGeometry(
        valid = true,
        complete = true,
        startPx = visualStart,
        endPx = visualEnd,
        centerXPx = visualCenter,
        aMarkerCenterXPx = aMarkerCenterX,
        bMarkerCenterXPx = bMarkerCenterX,
        markerCenterYPx = markerCenterYPx,
        realWidthPx = realWidth,
        merged = merged,
        exaggerated = width > realWidth + 0.01f,
    )
}
