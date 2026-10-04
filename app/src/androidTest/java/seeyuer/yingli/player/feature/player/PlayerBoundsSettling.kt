package seeyuer.yingli.player.feature.player

import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.fail

/**
 * 一次"几何自洽"的读数：未裁剪 + 已裁剪 + 框架自己判的"在场"，以及读到它花了几帧。
 *
 * [describe] 一律拼进断言消息：将来真出问题时，消息里必须能看出当时读到的几何、读了几帧、
 * 有没有读到自洽的那一帧 —— 否则就会把"读数还没跟上布局的那一帧"误判成"产品把按钮裁成 0×0"。
 */
internal data class BoundsSample(
    val unclipped: DpRect,
    val clipped: DpRect,
    /**
     * 同一帧里 `assertIsDisplayed()` 的结果 —— **就是框架自己那套"在场"判据**，这里不另立一套：
     * 它内部读 `SemanticsNode.boundsInWindow`（同样是 `findCoordinatorToGetBounds()` 出来的、
     * 带裁剪的那条路径），所以它也会被同一个瞬时脏值打到。
     */
    val displayed: Boolean,
    /** 读到这组几何时读了几帧（1 = 第一帧就自洽）。 */
    val frames: Int,
    /** 是否读到了"自洽"的那一帧；false = 一直不自洽，交给调用方的断言判死。 */
    val settled: Boolean,
) {
    val describe: String
        get() = "unclipped=$unclipped clipped=$clipped displayed=$displayed frames=$frames settled=$settled"
}

/**
 * **几何读数的等稳定器**：把"裁剪几何 / 在场判据这一次读"与"布局是否已经稳定"分开。
 *
 * ## 为什么需要它（实测证据，不是猜测）
 *
 * 同一份布局、`state` 一个字段都没动，连读多帧时，裁剪那条路径会间歇性读出与这枚节点
 * 位置毫无关系的值：
 *  · 横屏档"设置 B 点"（真实几何 `146..194 × 254..302`）读到过 `0×0 @ (0,0)`；
 *  · 读数行（真实几何 `12..348 × 516..542.67`，且**没有任何裁剪祖先**）读到过
 *    `9×9 @ (21,21)`，同一次运行的下一帧又是 `92..304 × 496..544`；
 *  · `assertIsDisplayed()` 也会误报 `not displayed`（它读 `boundsInWindow`，同一条路径）。
 * 而**未裁剪几何**在这些帧里逐像素一致，产品的真实几何也一直正确（真机截图复核过：
 * 横屏档四枚按钮完整画出、没有任何裁剪）。所以不稳定的是**读数**，不是产品。
 *
 * ## 为什么不是"连续两帧一致"
 *
 * 曾经写成"连续两帧读到同一组几何才返回"。它在真机上**不可用**：每一次读都要
 * `waitForIdle` + 一次帧推进，实测**每帧 300~800ms**（同一帧读两次 = 668ms），
 * 于是"每个节点读两帧"就把 `PlayerAbLoopExclusionTest` 里 3 秒的预览卡倒计时耗光了
 * （实测：4 次断言花掉 3.3s，卡片已经过期，断言随之失败）。等稳定必须**便宜**。
 *
 * ## 判据：第一帧"自洽"即返回
 *
 * 自洽 = 这一帧里 `assertIsDisplayed()` 通过、且已裁剪几何确实是同一枚节点该有的样子
 * （非空、宽高都不小于未裁剪、中心与未裁剪重合）。不自洽就推进一帧重读，最多 [SETTLE_ATTEMPTS] 帧。
 *
 * 它**不会**把真实的裁剪/隐藏缺陷掩盖成绿色：真实的裁剪在**每一帧**都一样地不自洽
 * （尺寸变小或中心偏移），循环用尽后返回的就是那组不自洽的读数，调用方的断言
 * （在场 / 非空 / 不小于未裁剪 / 中心重合 / 48dp 正圆）照旧逐条判死，消息里还带着 `frames`/`settled`。
 * 被跳过的只是那些"几何上讲不通、下一帧就自己变回来"的帧。
 *
 * 代价：自洽时**一次读数就返回**（与原来的单次断言同量级）；只有真的读到脏帧才会多花帧。
 */
internal class BoundsSettler(
    private val mainClock: MainTestClock,
    private val waitForIdle: () -> Unit,
) {
    /**
     * 取一组"自洽"的几何（含在场判据）。调用方自己决定断言什么 —— 它拿到的可能是
     * 一组一直不自洽的读数（[BoundsSample.settled] 为 false），那正是要判死的情况。
     */
    fun bounds(node: SemanticsNodeInteraction, describe: () -> String): BoundsSample {
        var last: BoundsSample? = null
        repeat(SETTLE_ATTEMPTS) { frame ->
            val sample = read(node, frame + 1)
            if (coherent(sample)) return sample.copy(settled = true)
            last = sample
            nextFrame()
        }
        return last ?: error("BoundsSettler 至少要读一次几何（${describe()}）")
    }

    /**
     * **便宜**的在场断言：判据完全交给框架自己的 `assertIsDisplayed()`，只在它被判为不可见时
     * 推进一帧重判（同一条带裁剪的路径会误报，见类注释）。若 [SETTLE_ATTEMPTS] 帧都判不可见，
     * 就按框架自己的结论断言失败 —— 节点真不在场时，这 12 帧会一直判不可见。
     *
     * 它**不读几何**（只读在场），所以开销与原来的一次 `assertIsDisplayed()` 相同。
     */
    fun assertDisplayed(node: SemanticsNodeInteraction, describe: () -> String) {
        var lastVerdict = "未见可读的几何"
        repeat(SETTLE_ATTEMPTS) { frame ->
            if (runCatching { node.assertIsDisplayed() }.isSuccess) return
            lastVerdict = "$describe（第 ${frame + 1} 帧仍判不可见）"
            nextFrame()
        }
        fail(
            "不在场：$lastVerdict —— 连读 $SETTLE_ATTEMPTS 帧都被判为不可见" +
                "（`assertIsDisplayed` 读的是带裁剪的 `boundsInWindow`，刚重排过的那一帧会误报；" +
                "这里已经逐帧重判过）。",
        )
    }

    private fun read(node: SemanticsNodeInteraction, frame: Int): BoundsSample {
        // 节点可能在等稳定的过程中**合法地消失**（例如预览卡倒计时到期）：这里不能让它以
        // "Failed to retrieve bounds of the node" 这种底层错误收场 —— 消失就该表现为
        // "不在场 + 空几何"，由调用方的断言给出可读的失败信息。
        val unclipped = runCatching { node.getUnclippedBoundsInRoot() }.getOrElse { EMPTY_RECT }
        val clipped = runCatching { node.getBoundsInRoot() }.getOrElse { EMPTY_RECT }
        val displayed = runCatching { node.assertIsDisplayed() }.isSuccess
        return BoundsSample(
            unclipped = unclipped,
            clipped = clipped,
            displayed = displayed,
            frames = frame,
            settled = false,
        )
    }

    private fun nextFrame() {
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /** 这一帧的读数是不是"同一枚节点、且没有被裁"的样子。 */
    private fun coherent(sample: BoundsSample): Boolean =
        sample.displayed &&
            sample.clipped.width.value > 0f &&
            sample.clipped.height.value > 0f &&
            sample.clipped.width.value >= sample.unclipped.width.value - 0.5f &&
            sample.clipped.height.value >= sample.unclipped.height.value - 0.5f &&
            centerMatches(sample.clipped, sample.unclipped)

    private fun centerMatches(a: DpRect, b: DpRect): Boolean =
        kotlin.math.abs((a.left.value + a.right.value) / 2f - (b.left.value + b.right.value) / 2f) <= 1f &&
            kotlin.math.abs((a.top.value + a.bottom.value) / 2f - (b.top.value + b.bottom.value) / 2f) <= 1f

    private companion object {
        /** 节点不在场时的几何（见 [read] 的容错口径）。 */
        val EMPTY_RECT = DpRect(0.dp, 0.dp, 0.dp, 0.dp)

        /**
         * 最多读这么多帧。
         *
         * 12 而不是 2~3：脏值是**逐帧间歇**出现的（同一节点实测"对、脏、对"），多留几帧才有把握
         * 拿到自洽的那一帧。它只在真的读到脏帧时才多花帧（每帧≈300ms，所以必须"自洽即返回"）。
         */
        const val SETTLE_ATTEMPTS = 12
    }
}
