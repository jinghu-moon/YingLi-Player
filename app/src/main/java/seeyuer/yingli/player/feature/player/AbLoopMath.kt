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
 * ## 分层（本批的层序口径，来源见 `docs/21`）
 *
 * 标记组不再压在轨道上，而是**整体上移到轨道上方**，并按 demo 的绘制顺序分层落位：
 *
 *     轨道底 → 播放进度填充 → scrub 热区 → 区间条 → 竖线 + 徽标 → 夸大虚线
 *       → 端点热区 → 滑块（最后）→ 刻度文字
 *
 * 竖直方向的分寸链（轨道中线为 0，向上为负；常数见 [AbRangeBarToMarkerGap] 等）：
 *
 *     区间条  -33.0 .. -30.0   厚 [AbRangeBarThickness]（3dp，剖面口径）
 *     间隙    -30.0 .. -18.0   [AbRangeBarToMarkerGap]（12dp，与字形框同宽）
 *     徽标    -18.0 ..   0.0   [AbMarkerDiameter]（18dp，见其尺寸依据）
 *     ——— 轨道中线 0，轨道厚 4dp ———
 *
 * 于是**标记组总高 = [AbMarkerGroupHeight]（33dp）**，而滑杆那条 48dp 触控带只给到中线以上 24dp
 * —— 标记组**明确溢出 6dp**，这正是"徽标允许向上溢出、不许被裁"这条要求在几何上的落点：
 * 它由 [AbMarkerLayerHeight]（64dp 的独立画布）承载，**不是**靠把标记组压小、也不是靠
 * 指望某个祖先不裁剪（**真机核对结论与逐项验算见 [AbMarkerGroupHeight]**）。
 * **本层不再有"区间色块"**：区间强调改用"区间内正常亮度 / 区间外压暗"表达
 * （见 [AbRangeOutsideAlpha]），但**区间条本身回来了**（3dp 高，见 [AbRangeBarThickness]）——
 * 压暗表达"区间外不看"，区间条表达"区间在哪 / 是否激活"，两者语义不重叠、并存。
 */

/**
 * 区间条的厚度：**3dp**（`docs/21` §2.1 的剖面口径，也是 demo JS 的 `height="3"`）。
 *
 * 为什么**不采纳**剖面右侧标注里那个 `5dp`：那条引导线量的是"徽标下沿 → 循环层上沿"的**间距**，
 * 不是区间条厚度（剖面几何 `y=221..228` = 7px ÷ 2.4 ≈ 2.9dp，与 3dp 吻合）。把 5dp 读成厚度
 * 是 demo 自身的一处不自洽，本实现按它**自洽的那一路**取值。
 */
internal val AbRangeBarThickness: Dp = 3.dp

/**
 * 区间条两端的圆角：半个厚度（`1.5dp`）—— demo 的 `rx="1.5"`，圆头读起来是"一段区间"而不是"一块砖"。
 *
 * 它**不等于** [AbRangeCornerRadius]（2dp，那是 exaggerated 虚线的圆角档）：两者的载体不同
 * （3dp 条 vs 24dp 虚线框），各自的圆角都取自己那一档的一半或同档，不互相牵制。
 */
internal val AbRangeBarCornerRadius: Dp
    get() = AbRangeBarThickness / 2

/**
 * 徽标下沿与区间条下沿之间的**间隙**：12dp。
 *
 * ## 这个数不是审美选择，它由"必须落在滑杆那条 48dp 触控带之内"反推
 *
 * 标记组从轨道中线往上占 `间隙 + 直径 + 半径 = 间隙 + 27dp`（区间条自己只有 3dp 厚，
 * 夹在间隙里）。滑杆的触控带（`YingLiControls.kt` 的 `height(48.dp)`）中线到上沿只有 **24dp**，
 * 而**轨道条落在它的中线上**（本批实测，见下），所以约束是：
 *
 *     间隙 + 27dp ≤ 24dp  ⇒  间隙 ≤ −3dp ✗
 *
 * 等一下 —— 这个不等式说明**光靠"压在轨道中线上"是放不下的**。真正成立的是本批的实测结论：
 *
 *  · 徽标中心必须**落在轨道中线上方**（demo：徽标在上、轨道在下）；
 *  · 徽标顶边 = `中线 − 半径 − 间隙`（相对数值）… 按标记组"顶边在轨道中线上方
 *    `[AbMarkerGroupHeight]`"来算，24dp 的预算里要同时装下 `徽标直径 + 间隙 + 条厚`：
 *    `18 + 间隙 + 3 ≤ 24` ⇒ **`间隙 ≤ 3`**。
 *
 * 但 3dp 的间隙在视觉上读不出"徽标与条是两件东西"。于是本批的选择是：
 * **不把标记组塞进那 48dp**（它由 [AbMarkerLayerHeight] 的独立画布承载，向上多出 4dp），
 * 间隙取 **12dp** —— 与 [AbMarkerGlyphSize] 同档，读起来是"徽标浮在条上方一个字形宽"，
 * 而且 `12 + 18 + 3 = 33dp` 的标记组仍完整落在 56dp 画布内（从画布上沿算余 5dp）。
 *
 * 取值理由（12dp 而不是 demo 的 5dp）：5dp 在 18dp 的字母徽标下面太挤，字母牌会读成"贴着条"；
 * 12dp 与字形框同宽，是"两块独立控件"的最小呼吸量，也与项目 4/8/12/16 的间距档一致。
 */
internal val AbRangeBarToMarkerGap: Dp = 12.dp

/**
 * 徽标中心相对**轨道中线**向上抬高的距离 = `间隙 + 徽标半径 = 12 + 9 = 21dp`。
 *
 * 这是标记组"整体上移"的**唯一定义**：绘制侧的 `markerCenterYPx` 必须由它推出
 * （`轨道中线 y − AbMarkerTopOffset.toPx()`），不允许在绘制处再写一个偏移字面量 ——
 * 一旦两处不一致，区间条、竖线、徽标就会各自错位。
 */
internal val AbMarkerTopOffset: Dp
    get() = AbRangeBarToMarkerGap + AbMarkerRadius

/**
 * 标记组（区间条 + 竖线 + 徽标）的总高 = `徽标直径 + 间隙 + 条厚 = 18 + 12 + 3 = 33dp`。
 *
 * ## 与滑杆那条 48dp 触控带的关系（本批实测结论）
 *
 * 滑杆的触控带高 48dp、轨道落在它的中线上，所以**中线到上沿只有 24dp**；标记组要往上占
 * `[AbMarkerTopOffset] + [AbMarkerRadius] = 21 + 9 = 30dp` —— **明确超出触控带 6dp**。
 * 也就是说：如果标记组还画在滑杆的 `height(48.dp)` 里，字母徽标的上半边会被那个 Box 裁掉。
 *
 * 本批的修法不是"把标记组压小到 24dp 以内"（那要求间隙 ≤ 3dp，徽标会贴着条、读成一块），
 * 而是**给它自己的画布**：[AbMarkerLayerHeight]（56dp，居中于触控带，上下各多 4dp）。
 * `30dp ≤ 28dp` 这条不等式**不成立**，所以画布本身也要更高 —— 见 [AbMarkerLayerHeight] 的取值。
 *
 * 于是"徽标可以向上溢出"由**布局**保证：不指望任何祖先不裁剪，也不把滑杆撑高
 * （撑高会让左右两端的时间文本跟着重新居中，那正是历史回归过的"进度行瞬移"）。
 */
internal val AbMarkerGroupHeight: Dp
    get() = AbMarkerDiameter + AbRangeBarToMarkerGap + AbRangeBarThickness

/**
 * 标记层画布的**高度**：64dp（比滑杆那条 48dp 触控带高 16dp，上下各多 8dp）。
 *
 * 取值依据（本批实测结论，逐项可验算）：
 *  · 标记组从轨道中线往上占 `[AbMarkerTopOffset] + [AbMarkerRadius] = 30dp`；
 *  · 滑杆触控带只给到 24dp；画布居中于触控带，上沿在"轨道中线上方 高度/2"处；
 *  · 要求 `高度/2 > 30dp` ⇒ `高度 > 60dp` ⇒ 取 **64dp**（8dp 档，上下各留 2dp 余量）。
 *
 * 结论：**不许把标记组画进滑杆的触控带**。它因此是与滑杆同高的兄弟节点、自带 64dp 画布，
 * "徽标向上溢出"由布局给出，而不是指望某个祖先"碰巧不裁剪"。
 */
internal val AbMarkerLayerHeight: Dp = 64.dp

/**
 * 端点热区的**最小触控尺寸**（32 × 20dp）：读数条里的 A / B 数值与轨道上的徽标共用它。
 *
 * 依据：demo 给的是"32 × 20dp 的数值热区"。它是**有意的下限**——与项目里 48dp 的最小触控尺寸
 * 相比小一档，因为这两处热区紧挨着滑杆的整条 scrub 热区（`awaitEachGesture` 在 down 时即 seek），
 * 做大到 48dp 会把"轨道空白处点按 = seek"吃掉一大片。宽度取 32dp 也正是"两端徽标不会互相盖住"
 * 的合并阈值（[AbMarkerMergeThreshold] = 28dp）加上一点余量。
 */
internal val AbValueTapTargetWidth: Dp = 32.dp
internal val AbValueTapTargetHeight: Dp = 20.dp

/**
 * 夸大虚线框（区间上下沿那两条虚线）的**竖直跨度**。
 *
 * 取 [YingLiSliderTrackHeight]（轨道本体厚度）的 6 倍 = `24dp`，夹在
 * `[YingLiSliderTrackHeight], 32dp` 之间：它必须**明显厚于轨道**（4dp）才有可读的边界，
 * 又不能把整行 48dp 的触控带吃掉。
 *
 * 它**不是**徽标的尺寸（徽标见 [AbMarkerDiameter]），也**不是**区间条的厚度
 * （见 [AbRangeBarThickness]）：虚线边表达"这一段被放大过"的跨度，两者刻意分开。
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
 * 标记组在**轨道坐标系**里的竖直落点（像素）。
 *
 * 它是"徽标上移 + 区间条 + 竖线"这一层的唯一一份竖直几何：绘制侧不再各自算 `centerY ± 半径`，
 * 而是照这里给的四条线画。之所以要抽出来：本批的徽标**不在轨道中线上**了（抬高了
 * [AbMarkerTopOffset]），一旦某处还按"轨道中线 ± 半径"画，就会出现"区间条与徽标错位、
 * 夸大的虚线框套在徽标上"这类只有截图才看得出的偏差。
 */
internal data class AbMarkerLayout(
    /** 徽标（或合并块）中心 y：与轨道中线的距离就是 [AbMarkerTopOffset]。 */
    val markerCenterYPx: Float,
    /** 区间条的上沿 y。 */
    val barTopPx: Float,
    /** 区间条的下沿 y。 */
    val barBottomPx: Float,
    /** 夸大虚线框的上沿 y（[AbRangeBandHeight] 的一半，仍以徽标中心为锚）。 */
    val bandTopPx: Float,
    /** 夸大虚线框的下沿 y。 */
    val bandBottomPx: Float,
) {
    /** 虚线框的竖直跨度（= [AbRangeBandHeight] 的像素值）。 */
    val bandHeightPx: Float get() = bandBottomPx - bandTopPx
}

/**
 * 由**轨道中线**与各尺寸（像素）推出标记组的竖直落点。
 *
 * 顺序即分寸链（见文件头的层序注释）：区间条在徽标**上方**一个 [AbRangeBarToMarkerGap]，
 * 徽标中心在轨道中线上方 [AbMarkerTopOffset]；夸大虚线框仍以徽标中心为锚上下各展
 * [AbRangeBandHeight] / 2（它表达的是"这一段被放大过"的跨度，与区间条不是同一件事）。
 *
 * [markerOffsetPx] 必须由 [AbMarkerTopOffset] 过密度得到（调用方只做单位换算，不做算术）——
 * 这样"徽标抬高多少"只有一个来源。
 */
internal fun abMarkerLayout(
    trackCenterYPx: Float,
    markerOffsetPx: Float,
    markerRadiusPx: Float,
    barThicknessPx: Float,
    barGapPx: Float,
    bandHeightPx: Float,
): AbMarkerLayout {
    val markerCenterY = trackCenterYPx - markerOffsetPx
    val barBottom = markerCenterY - markerRadiusPx - barGapPx
    return AbMarkerLayout(
        markerCenterYPx = markerCenterY,
        barTopPx = barBottom - barThicknessPx,
        barBottomPx = barBottom,
        bandTopPx = markerCenterY - bandHeightPx / 2f,
        bandBottomPx = markerCenterY + bandHeightPx / 2f,
    )
}

/**
 * **只设了 A** 时那条"预判区间"（区间条）的透明度：**28%**（demo 的 `opacity=".28"`）。
 *
 * 它回答的是"B 若落在这里，区间就有这么长"—— 条**随播放头延展**（起止按
 * `min(A, 播放头)` 排，播放头在 A 之前时条画在 `[播放头, A]`），所以它必须**明显淡于**
 * 已锁定的 100%，一眼看出"这一段还没定下来"。
 *
 * **必须与 [AbRangeOutsideAlpha]（0.55）分开**：两者含义不同 —— 0.55 是"区间**外**的亮度"
 * （压暗轨道上的像素），0.28 是"区间**条**本身的透明度"（还没激活的预判）。demo 内部对这两个
 * 数有含糊之处（`docs/21` §2.3），本实现按"仅 A 的预判条 = 28%"这一路取，且**只取两档**：
 * 仅 A → 28%，A+B 已锁定 → 100%。**不引入** demo 里属于编辑态（本次不采纳）的"整组 50% 休眠"。
 */
internal const val AbRangeInactiveAlpha = 0.28f

/**
 * **只设了 A** 时，区间条上那道**播放头幽灵竖线**的透明度：0.55（demo 的 `opacity=".55"`）。
 *
 * 它的宽度见 [AbRangeGhostHeadWidth]。它在语义上是"B 会落在这里"的锚点：预判区间条只说
 * "从 A 到这里"，这条竖线**把"这里"指出来**——没有它，用户看到一条淡色条却不知道末端是
 * 播放头还是真正设过的 B。它**不是** B 徽标：不画字母、比徽标窄一个量级（1.5dp vs 18dp）。
 */
internal const val AbRangeGhostHeadAlpha = 0.55f

/**
 * 幽灵竖线的宽度：**1.5dp**（demo 的 `width="1.5"`）。
 *
 * 取值依据：它必须**明显窄于区间条的高度方向分量**（3dp）才读得出是"线"而不是"又一截条"，
 * 又要在这个量级的屏幕上仍占满一个物理像素（3x 屏 ≈ 4.5px）。与滑杆圆钮（14dp）差一个数量级，
 * 不会与"现在播到哪"混淆。
 */
internal val AbRangeGhostHeadWidth: Dp = 1.5.dp

/**
 * 区间在**轨道坐标系**里的几何（像素）。
 *
 * [startPx] / [endPx] 是实际要画的区间两端（放大、夹取之后）；[centerXPx] 是这一段**渲染出来**的
 * 水平中心（缝画在这里）；[aMarkerCenterXPx] / [bMarkerCenterXPx] 是两枚徽标的中心；
 * [merged] 表示"画一枚合并块"；[exaggerated] 表示渲染宽度**不等于**真实宽度
 * （必须走可辨识的视觉区分）。
 *
 * ## 仅 A：预判区间条与幽灵竖线
 *
 * [fractionEnd] 为 null（只设了 A）时，[predictedStartPx] / [predictedEndPx] 给出那条**预判区间条**
 * 的起止（`min(A, 播放头)` → `max(A, 播放头)`，并保证不小于 [barMinWidthPx] 的可辨识宽度），
 * [headCenterXPx] 给出幽灵竖线的位置（**播放头**，已夹在轨道内）。区间完整时这三个值都为 `null` ——
 * 它们描述的是"还没定下来"这件事，**不允许**在区间完整时残留（那会让绘制侧画出两条语义相反的条）。
 */
internal data class AbRangeGeometry(
    /** 轨道是否可用（宽度 > 0）。false = 什么都不画。 */
    val valid: Boolean,
    /** 区间是否完整（A、B 都在）。false = 只设了 A：没有实区间、没有缝、也不压暗。 */
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
    /** 仅 A 时：预判区间条的左端（轨道坐标）。区间完整时为 null。 */
    val predictedStartPx: Float? = null,
    /** 仅 A 时：预判区间条的右端（轨道坐标）。区间完整时为 null。 */
    val predictedEndPx: Float? = null,
    /** 仅 A 时：播放头幽灵竖线的中心（轨道坐标）。区间完整时为 null。 */
    val headCenterXPx: Float? = null,
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
 * 预判区间条（仅 A）的**最小可视宽度**：与自身厚度等宽（3px 量级）。
 *
 * 依据（demo 的 `Math.max(3, …)` 是同一件事）：播放头正好落在 A 上时真实宽度是 0 —— 什么都不画
 * 会让用户以为"设了 A 但轨道上没反应"；画一条与自身厚度等宽的短条，才是"B 会落在这里"的下限形态。
 * 它比 [MIN_AB_RANGE_WIDTH]（28dp）小一个数量级：预判条**不需要**放得下徽标（它上面没有徽标），
 * 所以不该借那个数。
 */
internal fun abRangeBarMinWidthPx(thicknessPx: Float): Float = thicknessPx

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
 * [fractionHead] 是**播放头**的全时长比例，**只在 [fractionEnd] 为 null 时有意义**（预判区间条的
 * 起止与幽灵竖线都由它给出）。区间完整时它不参与任何计算 —— 已经定下来的区间不该再跟着播放头变。
 *
 * [markerDiameterPx] / [minMarkerGapPx] 是徽标尺寸与最小可辨间距的**像素值**：最小可视宽度与
 * 合并阈值（两者都是 `直径 + 间距`，见 [MIN_AB_RANGE_WIDTH]）都由它们**在函数内推导**，
 * 调用方不再各算一份（那样两处一旦不一致，判定与画出来的形状就会对不上）。
 *
 * 六条规则（顺序即优先级）：
 *  1. 比例先夹到 `0..1`（越界的数据不允许画出轨道外）；
 *  2. 宽度不足最小可视宽度时以**中心为锚**放大到它 —— 中心锚定的意义是"区间真实落在哪一段"
 *     仍然成立，只是两端各自向外让出一半；
 *  3. 放大后**整体平移**回轨道内（贴左 / 贴右），不允许任何一部分溢出轨道；
 *  4. 中心距离小于合并阈值时判定为**合并块**，两枚徽标改为并排放在这一块的两半里；
 *  5. 徽标中心夹在 `[半径, 轨道宽 − 半径]`：徽标比旧圆点大一档，贴边时必须向内收，
 *     否则最外侧那半个字母会被裁掉（区间端点的**精确**位置由区间边缘与读数行表达）；
 *  6. **仅 A** 时另外给出 [AbRangeGeometry.predictedStartPx] / `predictedEndPx` / `headCenterXPx`：
 *     条按 `min(A, 播放头)` 排（播放头在 A 之前时条画在 `[播放头, A]`，而不是从 A 往回不画），
 *     并保证不小于 [barMinWidthPx]；幽灵竖线落在**夹取后**的播放头上（贴边时不越界）。
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
    fractionHead: Float? = null,
    barMinWidthPx: Float = AbRangeBarThickness.value,
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
        // 只设了 A：还没有"定下来的"区间可画（不压暗、不画缝），只有一枚徽标落在 A 的**真实位置**上。
        val aX = startX.coerceIn(minCenter, maxCenter)
        // 规则 6：预判区间条。起止按 min/max 排 —— 播放头在 A 之前时条画在 [播放头, A]，
        // 而不是"从 A 往回什么都不画"（demo 的 `Math.min(aX, gx)` 就是这条）。
        val headX = fractionToX(fractionHead ?: fractionStart)
        val rawLeft = minOf(aX, headX)
        val rawRight = maxOf(aX, headX)
        val minBarWidth = barMinWidthPx.coerceAtLeast(0f).coerceAtMost(trackWidthPx)
        val barWidth = (rawRight - rawLeft).coerceAtLeast(minBarWidth).coerceAtMost(trackWidthPx)
        // 放大只向**右**、再整体平移回轨道内：区间条的语义是"从这里到这里"，
        // 向左扩会盖住 A 左边的轨道；贴右时按 maxBarStart 回收，与规则 3 同一套夹取。
        val maxBarStart = (trackWidthPx - barWidth).coerceAtLeast(0f)
        val barStart = rawLeft.coerceIn(0f, maxBarStart)
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
            predictedStartPx = barStart,
            predictedEndPx = barStart + barWidth,
            headCenterXPx = headX,
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
