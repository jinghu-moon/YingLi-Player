package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderTrackHeight

/**
 * A–B 区间在进度条上的**几何判定**（纯函数，JVM）：
 * 最小可视宽度、中心锚定、越界夹取、合并判定、夸大标记。
 *
 * 这一层是"长视频 + 短区间"这个真实缺陷的**根因落点**：进度条是全时长线性映射，
 * 95 分钟视频上 5 秒区间只有 0.088% 宽度，两端标记必然重合 —— 那不是画法问题，
 * 是信息密度问题，所以修法必须落在"渲染宽度"与"是否合并"这两条**可计算的规则**上，
 * 而不是在绘制代码里多一点少一点。
 *
 * 阈值全部从常量**推导**（见 `AbLoopMath` 的注释），这里同时也是"推导关系没被改坏"的守门测试。
 *
 * 所有用例都在**像素域**里断言：`abRangeGeometry` 的输入输出就是像素（绘制侧只负责把 dp 过一遍密度），
 * 所以这里用的 1000px 轨道 / 44px 最小宽度 / 42px 合并阈值，正是一台 2 倍密度设备上的
 * `22dp` 与 `21dp`。
 */
class AbLoopMathTest {

    private val minWidthPx = 44f
    private val mergeThresholdPx = 42f
    private val markerRadiusPx = 11f
    private val centerY = 24f

    private fun geometry(
        trackWidthPx: Float,
        fractionStart: Float,
        fractionEnd: Float,
    ) = abRangeGeometry(
        trackWidthPx = trackWidthPx,
        fractionStart = fractionStart,
        fractionEnd = fractionEnd,
        minWidthPx = minWidthPx,
        mergeThresholdPx = mergeThresholdPx,
        markerCenterYPx = centerY,
    )

    // ---- 常量推导 ------------------------------------------------------------

    @Test
    fun `the merge threshold is derived from the marker diameter and the minimum gap`() {
        // 合并阈值不是拍出来的数：两枚半径 r 的圆，中心距 d 时边到边间距是 d−2r，
        // 要求它 ≥ 最小间距就得到 d ≥ 2r + gap。这一条把"阈值必须由标记尺寸推导"钉住。
        assertEquals(AbMarkerRadius * 2 + AbMarkerMinGap, AbMarkerMergeThreshold)
        // 本批的最终取值（要写进 docs/16 的几何表）。
        assertEquals(5.5f, AbMarkerRadius.value, 0.001f)
        assertEquals(11f, (AbMarkerRadius * 2).value, 0.001f)
        assertEquals(10.dp, AbMarkerMinGap)
        assertEquals(21f, AbMarkerMergeThreshold.value, 0.001f)
    }

    @Test
    fun `the minimum visible width holds two markers side by side and beats the merge threshold`() {
        // 最小可视宽度 = 两枚标记并排（2 × 直径 = 4r）：否则左右两半会被互相盖掉，
        // 连"合并块"都看不完整。
        assertEquals(AbMarkerRadius * 4, MIN_AB_RANGE_WIDTH)
        assertEquals(22f, MIN_AB_RANGE_WIDTH.value, 0.001f)
        // 关键关系：被判为"需要合并"的区间一定落在最小宽度分支上（4r > 2r + gap），
        // 两个分支因此不会互相打架 —— 合并块画出来的宽度永远 ≥ 2 × 直径。
        assertTrue(
            "最小宽度必须大于合并阈值，否则合并块会比两枚标记还窄：$MIN_AB_RANGE_WIDTH vs $AbMarkerMergeThreshold",
            MIN_AB_RANGE_WIDTH > AbMarkerMergeThreshold,
        )
        // 并且它明显厚于进度条轨道、又薄于整行触控带（48dp）：否则"看得见"这条目的达不到。
        assertTrue("区间色块必须厚于轨道：$AbRangeBandHeight", AbRangeBandHeight > YingLiSliderTrackHeight)
        assertTrue("区间色块不许吃掉整行：$AbRangeBandHeight", AbRangeBandHeight <= 32.dp)
        // 标记比轨道明显、比滑杆圆钮小一档（否则播放位置与区间端点分不清）。
        assertTrue(AbMarkerRadius > YingLiSliderTrackHeight / 2)
        assertTrue(AbMarkerRadius < YingLiSliderThumbRadius)
    }

    // ---- 真实宽度 vs 最小宽度 ------------------------------------------------

    @Test
    fun `a normal range keeps its real width and is never exaggerated`() {
        // 60 秒视频、12s→37s：占 25/60 ≈ 41.7%，1000px 轨道上约 416.7px。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 12_000f / 60_000f, fractionEnd = 37_000f / 60_000f)
        assertTrue(g.valid)
        assertEquals(416.67f, g.realWidthPx, 0.1f)
        assertEquals(416.67f, g.visualWidthPx, 0.1f)
        assertFalse("正常区间不允许被夸大", g.exaggerated)
        assertFalse("正常区间的两端必须画成两枚分离的圆点", g.merged)
        assertEquals(200f, g.startPx, 0.1f)
        assertEquals(616.67f, g.endPx, 0.1f)
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
            "渲染宽度必须容得下两枚并排的标记（矩形本身不能比真实宽度还短）",
            g.visualWidthPx >= minWidthPx,
        )
        assertTrue("被放大的区间必须可辨识（绘制侧据此走虚线）", g.exaggerated)
        assertTrue("两端靠得比阈值还近 → 必须画成一枚合并标记", g.merged)
        // 未被夹取（区间在轨道中段），所以中心必须仍是真实中心：602 500 / 5 700 000 × 1000。
        assertEquals(105.70f, g.markerCenterXPx, 0.05f)
        assertEquals(g.markerCenterXPx, (g.startPx + g.endPx) / 2f, 0.01f)
    }

    @Test
    fun `exaggeration is anchored at the real centre`() {
        // 中心锚定的意义：区间真实落在哪一段仍然成立，只是两端各自向外让出一半。
        val duration = 100_000f
        val g = geometry(trackWidthPx = 1000f, fractionStart = 50_000f / duration, fractionEnd = 50_050f / duration)
        assertEquals(500.25f, g.markerCenterXPx, 0.01f)
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
        assertEquals(0f, before.markerCenterXPx, 0.01f)
        assertEquals(0f, before.startPx, 0.01f)
        assertEquals(minWidthPx, before.endPx, 0.01f)

        val after = geometry(trackWidthPx = 1000f, fractionStart = 1.4f, fractionEnd = 1.9f)
        assertTrue(after.valid)
        // 两个比例都被夹到 1 → 中心距 0 → 合并，并按最小宽度贴住轨道右端。
        assertEquals(1000f, after.markerCenterXPx, 0.01f)
        assertEquals(1000f, after.endPx, 0.01f)
        assertEquals(1000f - minWidthPx, after.startPx, 0.01f)
    }

    @Test
    fun `an unmeasured track draws nothing`() {
        val g = geometry(trackWidthPx = 0f, fractionStart = 0.1f, fractionEnd = 0.2f)
        assertFalse("轨道还没测量出来时不允许画", g.valid)
        assertEquals(AbRangeGeometry.NONE, g)
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
        assertEquals(451f, below.markerCenterXPx + 41f / 2f, 0.05f)
        assertTrue("中心距 41px < 42px 必须合并", below.merged)

        val above = geometry(trackWidthPx = 2000f, fractionStart = 0.205f, fractionEnd = 0.2265f)
        assertEquals("输入必须是 43px 中心距", 43f, above.realWidthPx, 0.01f)
        assertFalse("中心距 43px > 42px 不允许合并", above.merged)
        // 未合并时必须真的画得下两枚标记：像素距离 ≥ 直径 + 最小间距（= 4 × 标记半径）。
        assertTrue(above.endPx - above.startPx >= 4f * markerRadiusPx)

        // 更宽的区间当然也不合并。
        assertFalse(geometry(trackWidthPx = 1000f, fractionStart = 0.1f, fractionEnd = 0.3f).merged)
    }

    @Test
    fun `a merged range is never narrower than two markers side by side`() {
        // 合并块与"两枚标记并排"是同一个形状，所以它的宽度必须容得下 2 × 直径 = 4r。
        // 这是"合并标记不会退化成一个小色块"的量化保证。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.5f, fractionEnd = 0.5f)
        assertTrue(g.merged)
        assertTrue("合并块宽度 ${g.visualWidthPx} 必须 ≥ ${4f * markerRadiusPx}", g.visualWidthPx >= 4f * markerRadiusPx)
    }

    @Test
    fun `zero width range is still visible and centred`() {
        // 理论上 A 与 B 至少隔一帧，但"两端恰好相等"的输入也必须画得出来（不能是 0 宽）。
        val g = geometry(trackWidthPx = 1000f, fractionStart = 0.3f, fractionEnd = 0.3f)
        assertEquals(minWidthPx, g.visualWidthPx, 0.01f)
        assertEquals(300f, (g.startPx + g.endPx) / 2f, 0.01f)
        assertTrue(g.merged)
    }
}
