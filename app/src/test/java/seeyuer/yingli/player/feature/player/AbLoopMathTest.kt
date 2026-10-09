package seeyuer.yingli.player.feature.player

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderTrackHeight
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector

/**
 * A–B 区间在进度条上的**几何判定**（纯函数，JVM）：
 * 徽标尺寸的推导、最小可视宽度、中心锚定、越界夹取、合并判定、徽标落点、夸大标记。
 *
 * 这一层是"长视频 + 短区间"这个真实缺陷的**根因落点**：进度条是全时长线性映射，
 * 95 分钟视频上 5 秒区间只有 0.088% 宽度，两端标记必然重合 —— 那不是画法问题，
 * 是信息密度问题，所以修法必须落在"渲染宽度"与"是否合并"这两条**可计算的规则**上，
 * 而不是在绘制代码里多一点少一点。
 *
 * 阈值全部从常量**推导**（见 `AbLoopMath` 的注释），这里同时也是"推导关系没被改坏"的守门测试。
 *
 * 所有用例都在**像素域**里断言：`abRangeGeometry` 的输入输出就是像素（绘制侧只负责把 dp 过一遍密度），
 * 所以这里用一组**整数像素**的标记尺寸造输入（直径 22px + 间距 20px → 最小可视宽度 44px、
 * 合并阈值 42px），让断言落在干净的整数上。真实常量（18dp / 10dp）由上面那组推导用例钉住。
 */
class AbLoopMathTest {

    private val markerDiameterPx = 22f
    private val markerRadiusPx = markerDiameterPx / 2f
    /** 最小可视宽度 == 合并阈值（见 `MIN_AB_RANGE_WIDTH`），由同一条不等式给出。 */
    private val minWidthPx = markerDiameterPx + TestMarkerGapPx
    private val mergeThresholdPx = minWidthPx
    private val centerY = 24f

    private fun geometry(
        trackWidthPx: Float,
        fractionStart: Float,
        fractionEnd: Float?,
        head: Float? = null,
    ) = abRangeGeometry(
        trackWidthPx = trackWidthPx,
        fractionStart = fractionStart,
        fractionEnd = fractionEnd,
        markerDiameterPx = markerDiameterPx,
        minMarkerGapPx = TestMarkerGapPx,
        markerCenterYPx = centerY,
        fractionHead = head,
        barMinWidthPx = barMinWidthPx,
    )

    /**
     * 测试用的预判条最小宽度（px）：**直接取真实常量的数值** [AbRangeBarThickness]。
     *
     * 不另拍一个数：预判条的"最小可辨识宽度 = 自身厚度"这条关系（见 [abRangeBarMinWidthPx]）
     * 必须在测试里也成立，否则断言就与绘制侧脱钩了。像素域里没有密度换算，
     * 所以这里取 dp 的**数值**（3px），与其余用例用整数像素造输入的做法一致。
     */
    private val barMinWidthPx = AbRangeBarThickness.value

    // ---- 徽标尺寸的推导 ------------------------------------------------------

    /**
     * 端点从"实心小圆"改成**字母徽标**之后，尺寸不再是审美选择：它由"字母认不认得出"反推，
     * 而字母就是图标库里的 `letter-a` / `letter-b`（[YingLiIcon.LETTER_A] / [YingLiIcon.LETTER_B]）。
     * 这一条把整条推导链钉住：字形框 → 徽标直径 → 半径 / 圆角，以及"字母墨高必须大于项目允许的
     * 最小文字"这个**硬要求**。
     */
    @Test
    fun `the badge diameter is derived from the letter that is actually drawn in it`() {
        // 字形框 = 滑杆圆钮的直径（这一行上已有的最大圆形元素）。
        assertEquals(YingLiSliderThumbRadius * 2, AbMarkerGlyphSize)
        assertEquals(14f, AbMarkerGlyphSize.value, 0.001f)
        // 留白取轨道描边圆头那一档（YingLiSliderTrackHeight / 2 = 2dp），徽标直径 = 字形框 + 两侧留白。
        assertEquals(2f, AbMarkerGlyphPadding.value, 0.001f)
        assertEquals(AbMarkerGlyphSize + AbMarkerGlyphPadding * 2, AbMarkerDiameter)
        assertEquals(18f, AbMarkerDiameter.value, 0.001f)
        assertEquals(9f, AbMarkerRadius.value, 0.001f)
        // 圆角 = 留白的 2 倍：向内缩一个留白之后，字形框自己的圆角正好是 2dp（同一档）。
        assertEquals(AbMarkerGlyphPadding * 2, AbMarkerCornerRadius)
        assertEquals(4f, AbMarkerCornerRadius.value, 0.001f)

        // 墨迹占比**从图标自己的 path 数据复算**：换掉字形（或图标库改了字形）这条就会红，
        // 整条尺寸推导随之失效 —— 这正是"依据"必须可验证的形式。
        listOf(YingLiIcon.LETTER_A, YingLiIcon.LETTER_B).forEach { icon ->
            val ink = letterInkRatios(icon)
            assertEquals("${icon.name} 的墨迹宽占比变了", AbLetterInkWidthRatio, ink.first, 0.001f)
            assertEquals("${icon.name} 的墨迹高占比变了", AbLetterInkHeightRatio, ink.second, 0.001f)
        }

        // 硬要求：字母在徽标里的墨高必须**大于**项目允许的最小文字（10sp）的墨高。
        val letterInkHeightDp = AbMarkerGlyphSize.value * AbLetterInkHeightRatio
        val smallestTextInkHeightDp = PlayerChromeTextMinFontSize.value * RobotoCapHeightRatio
        assertEquals(10.5f, letterInkHeightDp, 0.01f)
        assertTrue(
            "徽标里的字母墨高 ${letterInkHeightDp}dp 必须大于最小可读文字的墨高 ${smallestTextInkHeightDp}dp",
            letterInkHeightDp > smallestTextInkHeightDp,
        )
        // 反例（这条是"尺寸不是拍的"的证据）：旧的小圆点直径 11dp → 字形框 7dp → 墨高 5.25dp，
        // 已经低于那个下限 —— 所以"把小圆点放大到装得下字母"是可读性的硬要求。
        assertTrue(7f * AbLetterInkHeightRatio < smallestTextInkHeightDp)
    }

    @Test
    fun `the merge threshold is derived from the badge diameter and the minimum gap`() {
        // 合并阈值不是拍出来的数：两枚直径 D 的徽标，中心距 d 时边到边间距是 d−D，
        // 要求它 ≥ 最小间距就得到 d ≥ D + gap。这一条把"阈值必须由徽标尺寸推导"钉住。
        assertEquals(AbMarkerDiameter + AbMarkerMinGap, AbMarkerMergeThreshold)
        // 本批的最终取值（要写进 docs/16 的几何表）。
        assertEquals(10.dp, AbMarkerMinGap)
        assertEquals(28f, AbMarkerMergeThreshold.value, 0.001f)
    }

    @Test
    fun `the minimum visible width is exactly the merge threshold and holds two glyph boxes`() {
        // 最小可视宽度**就是**合并阈值：同一条不等式同时回答"两端还分不分得开"与"这一段画多宽"。
        assertEquals(AbMarkerMergeThreshold, MIN_AB_RANGE_WIDTH)
        assertEquals(28f, MIN_AB_RANGE_WIDTH.value, 0.001f)
        // 合并块的两半各放**一个字形框**（不是两枚带留白的完整徽标）：块的半宽必须 ≥ 字形框。
        // 本批取值下两者正好相等（28/2 = 14dp），所以块里两个字母不会互相挤压。
        assertTrue(
            "合并块的半宽 ${AbMarkerMergeThreshold / 2} 必须放得下字形框 $AbMarkerGlyphSize",
            AbMarkerMergeThreshold / 2 >= AbMarkerGlyphSize,
        )
        assertEquals(AbMarkerGlyphSize, AbMarkerMergeThreshold / 2)
        // 为什么**不是** 2 × 直径 = 36dp：那要求在块的两半里各留一次完整留白，多出来的 8dp 会让
        // 400dp 手机上 ~13% 的轨道（95 分钟影片的 12 分钟）被画成同一个宽度 —— 真机
        // instrumented 用"10 分钟区间"抓出了这一条，所以这里把上界也钉住。
        assertTrue("最小可视宽度必须明显小于 2 × 直径（$AbMarkerDiameter × 2）", MIN_AB_RANGE_WIDTH < AbMarkerDiameter * 2)
        // 并且它明显厚于进度条轨道、又薄于整行触控带（48dp）：否则"看得见"这条目的达不到。
        assertTrue("区间带必须厚于轨道：$AbRangeBandHeight", AbRangeBandHeight > YingLiSliderTrackHeight)
        assertTrue("区间带不许吃掉整行：$AbRangeBandHeight", AbRangeBandHeight <= 32.dp)
        // 徽标比轨道明显（否则看不见）。
        assertTrue(AbMarkerRadius > YingLiSliderTrackHeight / 2)
    }

    // ---- 标记组的竖直几何（本批：徽标上移到轨道上方）----------------------------

    /**
     * 标记组的竖直分寸链：**从轨道中线往上**依次是徽标（直径 18dp，中心抬高
     * [AbMarkerTopOffset]）、一个 12dp 的间隙、3dp 厚的区间条。
     *
     * 这一条把"徽标上移 + 区间条在徽标上方"整条链钉住：任何一处改了间距或厚度，这里先红。
     * 同时钉住三个**来源口径**：
     *  · 区间条厚度取剖面里自洽的那一档 **3dp**（`docs/21` §2.1：右侧标注的 5dp 量的是
     *    "徽标下沿 → 循环层上沿"的**间距**，不是厚度）；
     *  · 间隙取 **12dp**（**有意偏离** demo 的 5dp）：5dp 在 18dp 的字母牌下面太挤，
     *    12dp 与字形框同宽、也与项目 4/8/12/16 的间距档一致；
     *  · 标记组总高 **33dp**，与 demo 文字里的"约 30dp"同一量级（demo 只给叙述、没有加总式）。
     */
    @Test
    fun `the marker group stacks the badge above the range bar with a scripted offset`() {
        assertEquals(3f, AbRangeBarThickness.value, 0.001f)
        assertEquals(AbRangeBarThickness / 2, AbRangeBarCornerRadius)
        assertEquals(12f, AbRangeBarToMarkerGap.value, 0.001f)
        assertEquals(AbRangeBarToMarkerGap + AbMarkerRadius, AbMarkerTopOffset)
        assertEquals(21f, AbMarkerTopOffset.value, 0.001f)
        assertEquals(AbMarkerDiameter + AbRangeBarToMarkerGap + AbRangeBarThickness, AbMarkerGroupHeight)
        assertEquals(33f, AbMarkerGroupHeight.value, 0.001f)
    }

    /**
     * **徽标必须能向上溢出滑杆那条 48dp 触控带 —— 真机核对结论（本批）**。
     *
     * 逐项验算（所有输入都来自上面那组常量，不是另拍的数）：
     *  · 滑杆触控带 48dp、轨道落在它的中线上 ⇒ 中线到上沿 **24dp**；
     *  · 标记组从轨道中线往上占 `[AbMarkerTopOffset] + [AbMarkerRadius] = 21 + 9 = 30dp`；
     *  · `30dp > 24dp` ⇒ 标记组**超出触控带 6dp**，画在 `height(48.dp)` 的 Box 里必然
     *    被裁掉徽标的上半部分。
     *
     * 所以本批把它移到滑杆之外的自带画布（[AbMarkerLayerHeight]，64dp、居中于触控带）：
     * `64/2 = 32dp > 30dp`，余量 2dp。这条测试同时钉住"溢出存在"与"画布容得下"两件事 ——
     * 谁把间隙调大、或把画布调小，都会先在这里红。
     */
    @Test
    fun `the marker group overflows the slider touch band and needs a taller layer`() {
        val touchBandHalfHeight = PlayerChromeButtonSize / 2
        assertEquals(24f, touchBandHalfHeight.value, 0.001f)
        val markerExtentAboveTrack = AbMarkerTopOffset + AbMarkerRadius
        assertEquals(21f, AbMarkerTopOffset.value, 0.001f)
        assertEquals(9f, AbMarkerRadius.value, 0.001f)
        assertEquals(30f, markerExtentAboveTrack.value, 0.001f)
        assertTrue(
            "标记组必须真的溢出滑杆触控带（否则这套分层是多余设计）：$markerExtentAboveTrack > $touchBandHalfHeight",
            markerExtentAboveTrack > touchBandHalfHeight,
        )
        assertEquals(
            "溢出量（徽标顶边超触控带上沿多少）",
            6f,
            (markerExtentAboveTrack - touchBandHalfHeight).value,
            0.001f,
        )
        // 画布上沿在"轨道中线上方 画布高/2"处：必须容得下标记组（余量 2dp）。
        val layerTopAboveTrack = AbMarkerLayerHeight / 2
        assertEquals(32f, layerTopAboveTrack.value, 0.001f)
        assertTrue(
            "标记组顶边 $markerExtentAboveTrack 必须落在标记层画布内（画布上沿 $layerTopAboveTrack）",
            markerExtentAboveTrack < layerTopAboveTrack,
        )
        assertEquals(
            "画布余量",
            2f,
            (layerTopAboveTrack - markerExtentAboveTrack).value,
            0.001f,
        )
    }

    /**
     * 竖直分寸链的**唯一计算入口** [abMarkerLayout]：徽标在上、竖线连到轨道上沿、区间条落在那段
     * 空隙的正中，并且**所有笔迹都落在 [AbMarkerLayerHeight] 那块 64dp 画布内**（画布中心与轨道
     * 中线重合）。
     *
     * 这一条是"标记组不许被裁"的可断言形式：谁把间隙调大、把条挪到画布外，这里先红。
     */
    @Test
    fun `the marker layout keeps every stroke inside the marker layer canvas`() {
        // 像素域：density = 1（本文件其余用例同样把 dp 的数值直接当 px 用）。
        val canvasHalf = AbMarkerLayerHeight.value / 2f
        val trackCenter = canvasHalf
        val layout = abMarkerLayout(
            trackCenterYPx = trackCenter,
            markerOffsetPx = AbMarkerTopOffset.value,
            markerRadiusPx = AbMarkerRadius.value,
            barThicknessPx = AbRangeBarThickness.value,
            trackThicknessPx = YingLiSliderTrackHeight.value,
            bandHeightPx = AbRangeBandHeight.value,
        )
        // 徽标：中心抬高 21dp，顶边到轨道中线 30dp（画布上沿 32 → 余量 2）。
        assertEquals(trackCenter - 21f, layout.markerCenterYPx, 0.001f)
        assertEquals(2f, canvasHalf - (trackCenter - layout.markerCenterYPx + AbMarkerRadius.value), 0.001f)
        // 竖线：从徽标下沿（中线 − 12）连到**轨道上沿**（中线 − 2）。
        assertEquals(trackCenter - AbRangeBarToMarkerGap.value, layout.connectorTopPx, 0.001f)
        assertEquals(trackCenter - YingLiSliderTrackHeight.value / 2f, layout.connectorBottomPx, 0.001f)
        assertEquals(
            AbRangeBarToMarkerGap.value - YingLiSliderTrackHeight.value / 2f,
            layout.connectorHeightPx,
            0.001f,
        )
        // 区间条：落在"徽标下沿 → 轨道上沿"的正中，厚 3dp，且**在徽标下方、轨道上方**。
        assertEquals(
            (layout.connectorTopPx + layout.connectorBottomPx) / 2f,
            (layout.barTopPx + layout.barBottomPx) / 2f,
            0.001f,
        )
        assertEquals(AbRangeBarThickness.value, layout.barBottomPx - layout.barTopPx, 0.001f)
        assertTrue("条必须在徽标下方：${layout.barTopPx} > ${layout.connectorTopPx}", layout.barTopPx > layout.connectorTopPx)
        assertTrue("条必须在轨道上沿之上：${layout.barBottomPx} < ${layout.connectorBottomPx}", layout.barBottomPx < layout.connectorBottomPx)
        // 夸大虚线框仍以**轨道中线**为锚（不跟着徽标上移）。
        assertEquals(trackCenter - AbRangeBandHeight.value / 2f, layout.bandTopPx, 0.001f)
        assertEquals(AbRangeBandHeight.value, layout.bandHeightPx, 0.001f)
        // 画布内的硬约束：所有笔迹的 y 都落在 [0, 画布高] 内。
        val strokes = listOf(
            layout.markerCenterYPx - AbMarkerRadius.value,
            layout.markerCenterYPx + AbMarkerRadius.value,
            layout.connectorBottomPx,
            layout.barTopPx,
            layout.barBottomPx,
            layout.bandTopPx,
            layout.bandBottomPx,
        )
        assertTrue(
            "标记组的笔迹必须全在画布 [0, ${AbMarkerLayerHeight.value}] 内：$strokes",
            strokes.all { it >= 0f && it <= AbMarkerLayerHeight.value },
        )
        // 竖线宽度：比区间条细一档，与幽灵竖线同档（否则会读成"又一截条"）。
        assertEquals(1.5f, AbMarkerConnectorWidth.value, 0.001f)
        assertTrue(AbMarkerConnectorWidth < AbRangeBarThickness)
        assertEquals(AbRangeGhostHeadWidth, AbMarkerConnectorWidth)
    }

    // ---- 仅 A：预判区间条与幽灵竖线 -------------------------------------------

    /**
     * 只设了 A 时，几何必须给出那条**预判区间条**（28% 的那条）与**播放头幽灵竖线**的落点。
     *
     * 三种相对位置都要覆盖：播放头在 A 之后、正好在 A 上、在 A 之前 —— 最后一种是 demo 特意纠正的
     * 一种画法（起止按 `min(aX, gx)` 排，**不是**"从 A 往回什么都不画"）。
     */
    @Test
    fun `a lone point A predicts the range bar up to the playhead and marks the playhead`() {
        // 播放头在 A 之后：条画在 [A, 播放头]。
        val ahead = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null, head = 0.5f)
        assertFalse(ahead.complete)
        assertEquals(300f, ahead.predictedStartPx!!, 0.01f)
        assertEquals(500f, ahead.predictedEndPx!!, 0.01f)
        assertEquals(500f, ahead.headCenterXPx!!, 0.01f)

        // 只设 A 不动播放头：真实宽度为 0，但"预判条"仍有最小可辨识宽度（不许什么都不画）。
        val samePlace = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null, head = 0.3f)
        assertEquals(300f, samePlace.predictedStartPx!!, 0.01f)
        assertEquals(300f + AbRangeBarThickness.value, samePlace.predictedEndPx!!, 0.01f)

        // 播放头在 A **之前**：条画在 [播放头, A]（demo 的 Math.min(aX, gx)）。
        val behind = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null, head = 0.1f)
        assertEquals(100f, behind.predictedStartPx!!, 0.01f)
        assertEquals(300f, behind.predictedEndPx!!, 0.01f)
        assertEquals(100f, behind.headCenterXPx!!, 0.01f)

        // 幽灵竖线贴右端时不许越出轨道：末端位置被夹在轨道内。
        val atEnd = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null, head = 1.4f)
        assertEquals(1000f, atEnd.headCenterXPx!!, 0.01f)
        assertEquals(1000f, atEnd.predictedEndPx!!, 0.01f)
        assertTrue("预判条不许越出轨道右端", atEnd.predictedStartPx!! >= 0f)
    }

    /** 不传 `fractionHead` 时，预判条退化为"A 处的最小宽度条"（绘制侧的兜底路径）。 */
    @Test
    fun `a lone point A without a playhead still shows the minimum bar at A`() {
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null)
        assertEquals(300f, g.predictedStartPx!!, 0.01f)
        assertEquals(300f + AbRangeBarThickness.value, g.predictedEndPx!!, 0.01f)
        assertEquals(300f, g.headCenterXPx!!, 0.01f)
    }

    /** 区间**完整**时，三个"预判"字段必须是 `null`：定下来的区间不该再跟着播放头变。 */
    @Test
    fun `a complete range carries no prediction fields`() {
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.2f, fractionEnd = 0.6f, head = 0.9f)
        assertTrue(g.complete)
        assertNull(g.predictedStartPx)
        assertNull(g.predictedEndPx)
        assertNull(g.headCenterXPx)
    }

    /**
     * 预判条的透明度是**独立的一档**（28%），必须与"区间外压暗"的 0.55 分开：
     * 前者是"还没激活的预判"，后者是"区间外的亮度"，两者语义不重叠、也不允许合并成一个数。
     */
    @Test
    fun `the inactive prediction alpha is its own tier and does not collapse into the dim alpha`() {
        assertEquals(0.28f, AbRangeInactiveAlpha, 0.0001f)
        assertEquals(0.55f, AbRangeGhostHeadAlpha, 0.0001f)
        assertTrue(
            "预判条必须比幽灵竖线更淡（条是范围、线是锚点）：$AbRangeInactiveAlpha vs $AbRangeGhostHeadAlpha",
            AbRangeInactiveAlpha < AbRangeGhostHeadAlpha,
        )
        assertTrue("预判条必须明显淡于已锁定：$AbRangeInactiveAlpha", AbRangeInactiveAlpha < 0.5f)
        // 幽灵竖线的宽度：1.5dp，比区间条厚（3dp）窄一个量级，不会读成"又一截条"。
        assertEquals(1.5f, AbRangeGhostHeadWidth.value, 0.001f)
        assertTrue(AbRangeGhostHeadWidth < AbRangeBarThickness)
        // 它与压暗透明度**是两个不同的量**（名字与用途都不同）：一个作用在"还没激活的条"，
        // 一个作用在"区间外的像素"。这里只钉住它们各自有定义、且预判条更淡。
        assertEquals(AbRangeOutsideAlpha, AbRangeGhostHeadAlpha)
        assertTrue(AbRangeInactiveAlpha < AbRangeOutsideAlpha)
    }

    /**
     * 区间外的亮度只有一个来源：`AbRangeOutsideAlpha`，且**不允许低于**口径下限
     * （区间外被压成看不见，用户就读不出"轨道还在、只是不在循环里"）。
     */
    @Test
    fun `the dimmed outside keeps at least the stated alpha floor`() {
        assertEquals(0.30f, AbRangeOutsideAlphaFloor, 0.0001f)
        assertEquals(0.55f, AbRangeOutsideAlpha, 0.0001f)
        assertTrue(
            "区间外亮度 $AbRangeOutsideAlpha 不允许低于下限 $AbRangeOutsideAlphaFloor",
            AbRangeOutsideAlpha >= AbRangeOutsideAlphaFloor,
        )
        assertTrue("区间外必须真的比区间内暗", AbRangeOutsideAlpha < 1f)
    }

    // ---- 真实宽度 vs 最小宽度 ------------------------------------------------

    @Test
    fun `a normal range keeps its real width and is never exaggerated`() {
        // 60 秒视频、12s→37s：占 25/60 ≈ 41.7%，1000px 轨道上约 416.7px。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 12_000f / 60_000f, fractionEnd = 37_000f / 60_000f)
        assertTrue(g.valid)
        assertTrue(g.complete)
        assertEquals(416.67f, g.realWidthPx, 0.1f)
        assertEquals(416.67f, g.visualWidthPx, 0.1f)
        assertFalse("正常区间不允许被夸大", g.exaggerated)
        assertFalse("正常区间的两端必须画成两枚分离的徽标", g.merged)
        assertEquals(200f, g.startPx, 0.1f)
        assertEquals(616.67f, g.endPx, 0.1f)
        // 未合并：两枚徽标分别落在渲染区间的两端（中段没有夹取，所以就是真实时刻）。
        assertEquals(200f, g.aMarkerCenterXPx, 0.1f)
        assertEquals(616.67f, g.bMarkerCenterXPx, 0.1f)
    }

    @Test
    fun `a five second range on a ninety five minute video is lifted to the minimum width`() {
        // **真实案例**：95 分钟（5 700 000ms）视频上 5 秒（5 000ms）区间。
        // 真实宽度 = 1000px × 5000 / 5 700 000 ≈ 0.877px —— 不到一个像素，
        // 旧实现画出来必然是两个点糊在一起。现在它被抬到最小可视宽度。
        val duration = 95 * 60 * 1000L
        val start = 600_000L
        val end = start + 5_000L
        val g = geometry(
            trackWidthPx = 1000f,
            fractionStart = start.toFloat() / duration,
            fractionEnd = end.toFloat() / duration,
        )
        assertTrue(g.valid)
        assertEquals(0.877f, g.realWidthPx, 0.01f)
        assertEquals(minWidthPx, g.visualWidthPx, 0.01f)
        assertTrue(
            "渲染宽度必须容得下两枚并排的徽标（矩形本身不能比真实宽度还短）",
            g.visualWidthPx >= minWidthPx,
        )
        assertTrue("被放大的区间必须可辨识（绘制侧据此走虚线）", g.exaggerated)
        assertTrue("两端靠得比阈值还近 → 必须画成一枚合并块", g.merged)
        // 未被夹取（区间在轨道中段），所以渲染中心必须仍是真实中心：602 500 / 5 700 000 × 1000。
        assertEquals(105.70f, g.centerXPx, 0.05f)
        // 合并块的两半各放一个字形框：字形中心落在整块的 1/4 与 3/4 处，块宽就是最小可视宽度。
        assertEquals(g.startPx + minWidthPx / 4f, g.aMarkerCenterXPx, 0.01f)
        assertEquals(g.endPx - minWidthPx / 4f, g.bMarkerCenterXPx, 0.01f)
        assertEquals(g.startPx + minWidthPx, g.endPx, 0.01f)
    }

    @Test
    fun `exaggeration is anchored at the real centre`() {
        // 中心锚定的意义：区间真实落在哪一段仍然成立，只是两端各自向外让出一半。
        val duration = 100_000f
        val g = geometry(trackWidthPx = 1000f, fractionStart = 50_000f / duration, fractionEnd = 50_050f / duration)
        assertEquals(500.25f, g.centerXPx, 0.01f)
        assertEquals(500.25f, (g.startPx + g.endPx) / 2f, 0.01f)
        assertEquals(minWidthPx, g.visualWidthPx, 0.01f)
        assertEquals(500.25f - minWidthPx / 2f, g.startPx, 0.01f)
    }

    @Test
    fun `an exaggerated range is pushed back inside the track instead of overflowing it`() {
        // 靠近两端时以中心为锚会有一半探出轨道，必须整体平移回来。
        val duration = 5_700_000f

        val atStart = geometry(trackWidthPx = 1000f, fractionStart = 0f, fractionEnd = 5_000f / duration)
        assertEquals("贴左：起点夹在 0", 0f, atStart.startPx, 0.01f)
        assertEquals(minWidthPx, atStart.visualWidthPx, 0.01f)

        val atEnd = geometry(
            trackWidthPx = 1000f,
            fractionStart = 1f - 5_000f / duration,
            fractionEnd = 1f,
        )
        assertEquals("贴右：终点夹在轨道右端", 1000f, atEnd.endPx, 0.01f)
        assertEquals(minWidthPx, atEnd.visualWidthPx, 0.01f)
        assertTrue(atEnd.startPx >= 0f)
    }

    @Test
    fun `a range wider than the track is clamped to the track instead of overflowing`() {
        // 越界或测量异常的输入（例如时长刚变、比例还没夹好）最多画满整条轨道，
        // 绝不允许 startPx 为负或 endPx 超出轨道宽 —— 那会画到时间文本上。
        val g = geometry(trackWidthPx = 1000f, fractionStart = -0.2f, fractionEnd = 1.2f)
        assertTrue(g.valid)
        assertEquals(0f, g.startPx, 0.01f)
        assertEquals(1000f, g.endPx, 0.01f)
        assertEquals(1000f, g.realWidthPx, 0.01f)
        assertFalse("画满整条轨道不算夸大（真实宽度就是轨道宽）", g.exaggerated)
    }

    @Test
    fun `out of range fractions are clamped to the track`() {
        // 越界数据（理论上不该出现）不允许画到轨道外面去。
        val before = geometry(trackWidthPx = 1000f, fractionStart = -0.5f, fractionEnd = -0.1f)
        assertTrue(before.valid)
        // 两个比例都被夹到 0 → 中心距 0 → 合并，并按最小宽度画在轨道左端。
        assertEquals(0f, before.startPx, 0.01f)
        assertEquals(minWidthPx, before.endPx, 0.01f)
        assertEquals(minWidthPx / 2f, before.centerXPx, 0.01f)

        val after = geometry(trackWidthPx = 1000f, fractionStart = 1.4f, fractionEnd = 1.9f)
        assertTrue(after.valid)
        // 两个比例都被夹到 1 → 中心距 0 → 合并，并按最小宽度贴住轨道右端。
        assertEquals(1000f, after.endPx, 0.01f)
        assertEquals(1000f - minWidthPx, after.startPx, 0.01f)
        assertEquals(1000f - minWidthPx / 2f, after.centerXPx, 0.01f)
        // 越界输入的徽标也全部落在轨道内：合并块本身已经被夹在轨道里，块内的字形中心自然也在里面，
        // 而且 A 永远在 B 左边（贴边也不许对调）。未合并时中心还要各向内收一个半径 —— 见下一条用例。
        listOf(before, after).forEach { g ->
            assertTrue("A 徽标必须落在轨道内：${g.aMarkerCenterXPx}", g.aMarkerCenterXPx >= 0f)
            assertTrue("B 徽标必须落在轨道内：${g.bMarkerCenterXPx}", g.bMarkerCenterXPx <= 1000f)
            assertTrue("A 徽标不许越过 B 徽标", g.aMarkerCenterXPx < g.bMarkerCenterXPx)
        }
    }

    @Test
    fun `an unmeasured track draws nothing`() {
        val g = geometry(trackWidthPx = 0f, fractionStart = 0.1f, fractionEnd = 0.2f)
        assertFalse("轨道还没测量出来时不允许画", g.valid)
        assertEquals(AbRangeGeometry.NONE, g)
        // 只设了 A 也一样：轨道不可用就什么都不画。
        assertEquals(AbRangeGeometry.NONE, geometry(trackWidthPx = 0f, fractionStart = 0.1f, fractionEnd = null))
    }

    // ---- 只设了 A（区间不完整）------------------------------------------------

    /**
     * **只设了 A** 时几何必须回答"这一枚徽标落在 A 的真实位置上"。
     *
     * 旧实现让绘制侧传 `abEnd ?: abStart`，于是这种情况被判成"零宽的合并块"：渲染段按最小宽度
     * 展开、徽标画在**渲染段左端**，也就是真实位置左边一个半径处（旧圆点 11dp、新徽标 18dp）。
     * 现在"区间完不完整"是几何自己的事实（[AbRangeGeometry.complete]），徽标落点不再被渲染段带偏。
     */
    @Test
    fun `a lone point A puts its badge exactly on A and draws no dimmed range`() {
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = null)
        assertTrue(g.valid)
        assertFalse("只设了 A：区间还不完整", g.complete)
        assertFalse("没有区间可合并", g.merged)
        assertFalse("没有区间，谈不上夸大", g.exaggerated)
        assertEquals(0f, g.realWidthPx, 0.01f)
        assertEquals("徽标必须落在 A 的真实位置上（而不是渲染段左端）", 300f, g.aMarkerCenterXPx, 0.01f)
        assertEquals(300f, g.centerXPx, 0.01f)
        // 渲染段退化为一个点：绘制侧不画区间、不画缝、也不压暗（压暗要 complete）。
        assertEquals(g.aMarkerCenterXPx, g.startPx, 0.01f)
        assertEquals(g.aMarkerCenterXPx, g.endPx, 0.01f)
    }

    @Test
    fun `a lone point A keeps its whole badge inside the track`() {
        // A 落在轨道最左端：徽标中心内收一个半径，保证整枚徽标（含字母）完整可见。
        val atStart = geometry(trackWidthPx = 1000f, fractionStart = 0f, fractionEnd = null)
        assertEquals(markerRadiusPx, atStart.aMarkerCenterXPx, 0.01f)
        // 最右端同理。
        val atEnd = geometry(trackWidthPx = 1000f, fractionStart = 1f, fractionEnd = null)
        assertEquals(1000f - markerRadiusPx, atEnd.aMarkerCenterXPx, 0.01f)
    }

    // ---- 合并判定的阈值两侧 ---------------------------------------------------

    @Test
    fun `the merge decision flips exactly at the threshold`() {
        // 阈值 42px（= 2 × 标记半径 + 最小间距）。比例在 Float 里表示不精确，
        // 所以用"2000px 轨道 × 精确的小数比例"造出**整数像素**的中心距，
        // 同时避开两端的夹取区（0.2 附近远离边界）。
        val below = geometry(trackWidthPx = 2000f, fractionStart = 0.205f, fractionEnd = 0.2255f)
        // 被合并时 startPx/endPx 已经被放大到最小宽度，所以这里核对的是**真实宽度**
        // （410 → 451，中心距 41px），不是绘制宽度。
        assertEquals("输入必须是 41px 中心距", 41f, below.realWidthPx, 0.01f)
        assertTrue("中心距 41px < 42px 必须合并", below.merged)

        val above = geometry(trackWidthPx = 2000f, fractionStart = 0.205f, fractionEnd = 0.2265f)
        assertEquals("输入必须是 43px 中心距", 43f, above.realWidthPx, 0.01f)
        assertFalse("中心距 43px > 42px 不允许合并", above.merged)
        // 未合并 ⟹ 两枚徽标的**边到边间距**不小于最小间距（这正是合并判定的推导，取等号即阈值）。
        assertTrue(
            "未合并时边到边间距必须 ≥ ${TestMarkerGapPx}px：${above.endPx - above.startPx - markerDiameterPx}px",
            above.endPx - above.startPx - markerDiameterPx >= TestMarkerGapPx,
        )
        // 而它**不会**被放大：真实宽度已经超过最小可视宽度，渲染宽度就是真实宽度。
        assertEquals(above.realWidthPx, above.visualWidthPx, 0.01f)
        assertFalse("超过最小可视宽度就不该走虚线边", above.exaggerated)

        // 更宽的区间当然也不合并。
        assertFalse(geometry(trackWidthPx = 1000f, fractionStart = 0.1f, fractionEnd = 0.3f).merged)
    }

    @Test
    fun `a merged block is exactly the minimum visible width and centres both glyphs in its halves`() {
        // 合并块与"两端刚好还分得开"是同一个宽度（= 最小可视宽度 = 合并阈值），
        // 两半各放一个字形框：字形中心落在整块的 1/4 与 3/4 处（半块的中间）。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.5f, fractionEnd = 0.5f)
        assertTrue(g.merged)
        assertEquals(minWidthPx, g.visualWidthPx, 0.01f)
        assertEquals(g.startPx + minWidthPx / 4f, g.aMarkerCenterXPx, 0.01f)
        assertEquals(g.endPx - minWidthPx / 4f, g.bMarkerCenterXPx, 0.01f)
        // 两枚字形有明确的先后：A 永远在 B 左边（贴边夹取也不许对调）。
        assertTrue(g.aMarkerCenterXPx < g.bMarkerCenterXPx)
    }

    @Test
    fun `zero width range is still visible and centred`() {
        // 理论上 A 与 B 至少隔一帧，但"两端恰好相等"的输入也必须画得出来（不能是 0 宽）。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = 0.3f)
        assertEquals(minWidthPx, g.visualWidthPx, 0.01f)
        assertEquals(300f, g.centerXPx, 0.01f)
        assertTrue(g.merged)
    }

    // ---- 从图标数据复算字母的墨迹占比 -----------------------------------------

    /**
     * 字母在 24 单位视口里的墨迹包围盒（占比），**从图标自己的 path 数据算出来**。
     *
     * Tabler 的描边图标用 2 单位描边，所以墨迹要把**半宽（1 单位）**算进去；曲线按端点与控制点
     * 一起计入包围盒（对这两个字形，控制点落在端点外框内，结果与精确包围盒一致）。
     */
    private fun letterInkRatios(icon: YingLiIcon): Pair<Float, Float> {
        val vector = icon.imageVector
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        // Tabler 的描边宽度（2 单位）：墨迹要把半宽算进去。两个字形共用同一套描边约定。
        var halfStroke = 0f

        fun include(x: Float, y: Float) {
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }

        vector.root.filterIsInstance<VectorPath>().forEach { path ->
            halfStroke = maxOf(halfStroke, path.strokeLineWidth / 2f)
            var x = 0f
            var y = 0f
            path.pathData.forEach { node ->
                when (node) {
                    is PathNode.MoveTo -> { x = node.x; y = node.y }
                    is PathNode.RelativeMoveTo -> { x += node.dx; y += node.dy }
                    is PathNode.LineTo -> { x = node.x; y = node.y }
                    is PathNode.RelativeLineTo -> { x += node.dx; y += node.dy }
                    is PathNode.HorizontalTo -> x = node.x
                    is PathNode.RelativeHorizontalTo -> x += node.dx
                    is PathNode.VerticalTo -> y = node.y
                    is PathNode.RelativeVerticalTo -> y += node.dy
                    is PathNode.CurveTo -> {
                        include(node.x1, node.y1)
                        include(node.x2, node.y2)
                        include(node.x3, node.y3)
                        x = node.x3
                        y = node.y3
                    }
                    is PathNode.RelativeCurveTo -> {
                        include(x + node.dx1, y + node.dy1)
                        include(x + node.dx2, y + node.dy2)
                        include(x + node.dx3, y + node.dy3)
                        x += node.dx3
                        y += node.dy3
                    }
                    else -> Unit
                }
                include(x, y)
            }
        }

        val inkWidth = (maxX - minX) + halfStroke * 2f
        val inkHeight = (maxY - minY) + halfStroke * 2f
        assertTrue("${icon.name} 没解析出任何路径点", maxX > minX && maxY > minY)
        return inkWidth / vector.viewportWidth to inkHeight / vector.viewportHeight
    }

    private companion object {
        /** 测试用的最小可辨间距（px）：与直径 22px 一起给出干净的阈值 42px。 */
        const val TestMarkerGapPx = 20f

        /**
         * Roboto 大写字母的墨高与字号之比（cap height ≈ 0.71em）。
         *
         * 它只出现在**推导的验证**里（把"项目允许的最小文字 10sp"换算成墨高），不是运行时输入。
         */
        const val RobotoCapHeightRatio = 0.71f
    }
}
