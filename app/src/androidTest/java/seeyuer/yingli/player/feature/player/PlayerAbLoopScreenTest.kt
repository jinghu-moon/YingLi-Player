package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

/**
 * 阶段 3 的**呈现契约**（instrumented，真实 Compose 布局）：
 *
 *  1. AB 胶囊住在底栏**辅助带**里，与截图胶囊**同一格**（顶点与高度逐像素一致）；
 *  2. 胶囊里四枚按钮一律是**定尺寸 48dp 圆钮**（宽度不随内容变化）、未设 A 时 B 禁用、
 *     A/B 用 `selected` 表达"已设置"；
 *  3. 托盘按钮**只在循环生效时**选中（实心）；关闭胶囊不影响它（D3）；
 *  4. 进度行画出 A–B 区间与读数行，并且**关闭胶囊后仍然显示**；
 *  5. **长视频 + 极短区间**（95 分钟 / 5 秒）下区间仍然可见、标记合并、读数给出真实时长与计数。
 *
 * "点了之后状态怎么变"（互斥三分支、异步晚到）不在这一层断言：那一层是
 * `PlayerViewModelTest`（JVM，时序可控）与 `PlayerAbLoopExclusionTest`（instrumented，
 * 用真实 ViewModel 驱动到"晚到结果被丢弃"）。
 */
class PlayerAbLoopScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun abCapsuleTakesTheSameAuxiliaryBandSlotAsTheScreenshotCapsule() {
        var mode by mutableStateOf(SlotMode.SCREENSHOT)
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = stateFor(mode),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        // 进场动画（胶囊 360ms 横滑 / 三段 240ms 淡入）先跑完再断言几何与可见性。
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_CAPSULE)
            .assertDisplayedSettled("截图胶囊")
        val screenshotBounds = composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_CAPSULE)
            .getUnclippedBoundsInRoot()

        mode = SlotMode.AB_LOOP
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDisplayedSettled("AB 胶囊")
        // 同一格只能住一枚：AB 胶囊在场时截图胶囊不复存在。
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_CAPSULE).assertCountEquals(0)

        val abBounds = composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).getUnclippedBoundsInRoot()
        // 竖直带同源：两枚胶囊的顶边与高度必须一致（"换个工具就跳一下"就是在这里被抓出来的）。
        assertEquals("胶囊顶边必须与截图胶囊一致", screenshotBounds.top.value, abBounds.top.value, 0.5f)
        assertEquals("胶囊高度必须与截图胶囊一致", screenshotBounds.height.value, abBounds.height.value, 0.5f)
        assertEquals(PlayerScreenshotCapsuleHeight.value, abBounds.height.value, 1f)

        // 而且在**辅助带里**：顶边、底边都落在带子内（带子就是它与截图胶囊共用的那一格）。
        val band = composeRule.onNodeWithTag(PlayerTestTags.AUXILIARY_BAND).getUnclippedBoundsInRoot()
        assertTrue("胶囊必须完整落在辅助带里：capsule=$abBounds band=$band", abBounds.top.value >= band.top.value - 0.5f)
        assertTrue("胶囊必须完整落在辅助带里：capsule=$abBounds band=$band", abBounds.bottom.value <= band.bottom.value + 0.5f)
        assertTrue("胶囊必须完整落在辅助带里：capsule=$abBounds band=$band", abBounds.left.value >= band.left.value - 0.5f)
        assertTrue("胶囊必须完整落在辅助带里：capsule=$abBounds band=$band", abBounds.right.value <= band.right.value + 0.5f)
    }

    @Test
    fun abCapsuleShowsSetupLabelsUntilPointsAreSetAndDisablesBWithoutA() {
        var session by mutableStateOf(AbLoopSession.EMPTY)
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = stateFor(SlotMode.AB_LOOP, session = session),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        // 进场动画（胶囊 360ms 横滑 / 三段 240ms 淡入）先跑完再断言几何与可见性。
        composeRule.waitForIdle()

        // 四枚按钮一律用**读屏文案**定位：胶囊里已经没有正文文字了（数值全部搬到读数行），
        // 所以这些读屏文案是**唯一**的定位锚点，也是"按钮语义没被改成文字"的可断言形式。
        composeRule.onNodeWithContentDescription("设置 A 点").assertDisplayedSettled("未设点时的 A 钮")
        composeRule.onNodeWithContentDescription("设置 B 点").assertDisplayedSettled("未设点时的 B 钮")
        // 没有 A 就没有区间可言 → B 禁用（沿用旧胶囊的禁用规则）。
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsNotEnabled()
        // 未设置 = 未选中（语义）。这就是"是否已设置"的可断言形式：颜色本身读不出也测不了。
        composeRule.onNodeWithContentDescription("设置 A 点").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsNotSelected()
        // 清除与关闭都不可用（没有点可清）/可用（关闭永远可用）。
        composeRule.onNodeWithContentDescription("清除").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("关闭").assertIsEnabled()

        session = AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 37_000), loopCount = 3)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("设置 A 点").assertIsSelected()
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsSelected()
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsEnabled()
        composeRule.onNodeWithContentDescription("清除").assertIsEnabled()
        // 关闭按钮的文案是"关闭"而不是"取消"：关闭 ≠ 取消（D3），只有"清除"才取消循环。
        composeRule.onNodeWithContentDescription("关闭").assertDisplayedSettled("设点后的关闭钮")
    }

    /**
     * 胶囊里四枚按钮**永远是圆钮**：宽度恰为 [PlayerScreenshotCapsuleButtonSize]（48dp）。
     *
     * 这一条正是本批修的缺陷的守门测试：旧实现里 A / B / 清除是**弹性宽度文字按钮**，
     * 标签 `A 00:12` 一长就把圆钮撑成**椭圆**（宽 > 高）。现在按钮宽度与高度必须相等，
     * 且等于托盘按钮的尺寸 —— 任何"给按钮加回文字"的改动都会先在这里红。
     */
    @Test
    fun theCapsuleButtonsArePerfectCirclesAtTheTrayButtonSize() {
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = stateFor(
                        SlotMode.AB_LOOP,
                        AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 37_000), loopCount = 12),
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }
        composeRule.waitForIdle()

        listOf("设置 A 点", "设置 B 点", "清除", "关闭").forEach { label ->
            val bounds = composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertEquals("$label 必须是**正圆**：宽必须等于高（椭圆就是被内容撑宽的）", bounds.width.value, bounds.height.value, 0.5f)
            assertEquals(
                "$label 的直径必须等于托盘按钮尺寸（同一个常量）",
                PlayerChromeButtonSize.value,
                bounds.width.value,
                0.5f,
            )
        }
    }

    @Test
    fun theTrayAbButtonIsSelectedExactlyWhileTheLoopIsActive() {
        var session by mutableStateOf(AbLoopSession.EMPTY)
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = stateFor(SlotMode.SCREENSHOT_CAPSULE_CLOSED, session = session),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        // 托盘里的按钮要先展开托盘才在场上（AB_LOOP 默认住在 TOOLS 槽位）。
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("AB循环").assertIsNotSelected()

        session = AbLoopSession(state = AbLoopState(pointA = 1_000, pointB = 2_000), loopCount = 12)
        composeRule.waitForIdle()
        // 循环生效 → 实心（选中态）；判据是会话侧的 `abLoop.active`，与胶囊是否打开无关。
        composeRule.onNodeWithContentDescription("AB循环").assertIsSelected()
    }

    @Test
    fun theProgressRowShowsTheAbRangeAndLoopCountEvenAfterTheCapsuleIsClosed() {
        var session by mutableStateOf(
            AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 37_000), loopCount = 12),
        )
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    // 胶囊**关闭**：区间与计数仍然必须是可见的（D3：关闭 ≠ 取消）。
                    state = stateFor(SlotMode.SCREENSHOT_CAPSULE_CLOSED, session = session),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        // 进场动画（胶囊 360ms 横滑 / 三段 240ms 淡入）先跑完再断言几何与可见性。
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDoesNotExist()
        // A–B 区间高亮 + 两端标记画在进度条那一行上。
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE).assertDisplayedSettled("AB 区间层")
        val labels = composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
        labels.assertDisplayedSettled("AB 读数行")
        // 本批定稿的读数行：两端时刻 + **真实区间时长** + 循环计数，连成一句。
        // 区间 25 秒（12s→37s）在 60 秒的测试时长上是一段**正常区间**，所以必须原样显示 25.0s。
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
            .assertTextEquals("A 00:12 → B 00:37 · 25.0s · 循环 ×12")

        // 只设了 A：区间还不完整 → 不出现时长也不出现计数（否则会读成"已经在循环但一次没跑"），
        // 但 A 的读数照旧出现。
        session = AbLoopSession(state = AbLoopState(pointA = 12_000))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE).assertDisplayedSettled("只设 A 时的区间层")
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS).assertTextEquals("A 00:12")
    }

    /**
     * **本批的核心验收**：长视频 + 极短区间（95 分钟视频、5 秒区间）。
     *
     * 真实宽度只有 0.09% —— 1000px 轨道上不到 1px，旧实现画出来必然是两个点糊在一起。
     * 断言三件事，缺一不可：
     *  1. **区间可见**：渲染宽度 ≥ [MIN_AB_RANGE_WIDTH]（而不是真实的那个亚像素宽度）；
     *  2. **标记不重叠**：两端按 [AbMarkerMergeThreshold] 判定为**合并**（画一枚合并块），
     *     而不是画两个会互相盖住的圆点；作为对照，正常区间必须判为**不合并**；
     *  3. **读数行给出真实数值**：`5.0s` 与循环计数照样显示 —— 渲染长度不再代表真实长度之后，
     *     真实长度只能靠文字表达。
     */
    @Test
    fun aFiveSecondRangeOnANinetyFiveMinuteVideoStaysVisibleAndReportsItsRealLength() {
        val duration = 95 * 60 * 1000L
        val start = 600_000L
        val end = start + 5_000L
        var session by mutableStateOf(
            AbLoopSession(state = AbLoopState(pointA = start, pointB = end), loopCount = 7),
        )
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = stateFor(SlotMode.SCREENSHOT_CAPSULE_CLOSED, session = session, durationMillis = duration),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 读数行：真实时长（5.0s）与计数必须在场。
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
            .assertTextEquals("A 10:00 → B 10:05 · 5.0s · 循环 ×7")

        // 区间可见 + 标记合并：用**界面自己那一套**几何判定复核（同一函数、同一轨道宽度）。
        val track = trackWidthPx()
        val geometry = abRangeGeometry(
            trackWidthPx = track,
            fractionStart = start.toFloat() / duration,
            fractionEnd = end.toFloat() / duration,
            minWidthPx = with(composeRule.density) { MIN_AB_RANGE_WIDTH.toPx() },
            mergeThresholdPx = with(composeRule.density) { AbMarkerMergeThreshold.toPx() },
            markerCenterYPx = 0f,
        )
        assertTrue("区间必须可见（渲染宽度 ≥ 最小可视宽度）", geometry.visualWidthPx >= with(composeRule.density) { MIN_AB_RANGE_WIDTH.toPx() })
        assertTrue("95 分钟 / 5 秒：真实宽度必须远小于最小可视宽度", geometry.realWidthPx < geometry.visualWidthPx)
        assertTrue("被放大的区间必须走可辨识的视觉区分（虚线边）", geometry.exaggerated)
        assertTrue("两端标记太近 → 必须合并成一枚标记，而不是画两个会互相盖住的圆", geometry.merged)

        // 对照：同一部影片上换一段**正常区间**（10 分钟），必须走真实宽度、且不合并。
        // 时长读数走"≥60s"那一档：与两端同源的 `mm:ss`（10 分钟 = `10:00`），不是 `600.0s`。
        val normalStart = 600_000L
        val normalEnd = normalStart + 600_000L
        session = AbLoopSession(state = AbLoopState(pointA = normalStart, pointB = normalEnd), loopCount = 7)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
            .assertTextEquals("A 10:00 → B 20:00 · 10:00 · 循环 ×7")
        val normal = abRangeGeometry(
            trackWidthPx = track,
            fractionStart = normalStart.toFloat() / duration,
            fractionEnd = normalEnd.toFloat() / duration,
            minWidthPx = with(composeRule.density) { MIN_AB_RANGE_WIDTH.toPx() },
            mergeThresholdPx = with(composeRule.density) { AbMarkerMergeThreshold.toPx() },
            markerCenterYPx = 0f,
        )
        assertTrue("正常区间不允许被夸大", !normal.exaggerated)
        assertTrue("正常区间必须画成两枚分离的圆点", !normal.merged)
        assertEquals(
            "正常区间必须走真实宽度（10 分钟 / 95 分钟 = 轨道宽的 10.5%）",
            track * 600_000f / duration,
            normal.visualWidthPx,
            0.5f,
        )
    }

    /**
     * 窄屏 / 横屏 / 系统字号放大三种验收场景。
     *
     * 断言分三层，缺一不可：
     *  1. **布局**：整枚胶囊必须落在屏幕里（不溢出）；
     *  2. **节点**：四个按钮都在场，且 `clipped == unclipped`（没有被任何祖先裁掉），
     *     并且都是 **48dp 正圆**（宽度不随内容变 —— 本批修的缺陷就是它）；
     *  3. **读数**：进度行下方那一行必须完整放得下（横向不裁、纵向不被压扁）。
     */
    @Test
    fun abCapsuleLabelsStayCompleteOnNarrowLandscapeAndLargeFont() {
        var config by mutableStateOf(ScreenConfig(320.dp, 640.dp, 1f, "窄屏竖屏"))
        composeRule.setContent {
            val density = LocalDensity.current
            // 用 Density 直接给出 fontScale：这就是"系统字号放大"，比改系统设置更可控。
            CompositionLocalProvider(LocalDensity provides Density(density.density, config.fontScale)) {
                Box(Modifier.size(config.width, config.height)) {
                    YingLiTheme(darkTheme = true) {
                        PlayerScreen(
                            state = stateFor(
                                SlotMode.AB_LOOP,
                                AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 37_000), loopCount = 12),
                                durationMillis = 60_000,
                            ),
                            onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                            onRecovery = {}, videoSurface = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        listOf(
            ScreenConfig(320.dp, 640.dp, 1f, "窄屏竖屏"),
            ScreenConfig(800.dp, 400.dp, 1f, "横屏"),
            ScreenConfig(360.dp, 720.dp, 2f, "系统字号 2 倍"),
            ScreenConfig(320.dp, 640.dp, 2f, "窄屏 + 字号 2 倍"),
        ).forEach { next ->
            config = next
            composeRule.waitForIdle()
            assertAbCapsuleFits(next)
        }
    }

    private fun assertAbCapsuleFits(config: ScreenConfig) {
        val capsule = composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE)
        val capsuleSample = settler.bounds(capsule) { "${config.name}: 胶囊" }
        assertTrue("${config.name}: 胶囊不在场：${capsuleSample.describe}", capsuleSample.displayed)
        val bounds = capsuleSample.unclipped
        assertTrue(
            "${config.name}: 胶囊不得溢出屏幕：capsule=$bounds 屏幕宽=${config.width}",
            bounds.left.value >= -0.5f && bounds.right.value <= config.width.value + 0.5f,
        )
        // 胶囊自身也不许被裁：它住在辅助带里（带子带 `clipToBounds`），四周留着一圈呼吸圈，
        // 因此裁剪几何必须与未裁剪几何一致。
        assertTrue("${config.name}: 胶囊被裁掉了：${capsuleSample.describe}", boundsAgree(capsuleSample))

        val band = composeRule.onNodeWithTag(PlayerTestTags.AUXILIARY_BAND).getUnclippedBoundsInRoot()
        listOf("设置 A 点", "设置 B 点", "清除", "关闭").forEach { label ->
            // 先钉住"这个读屏文案只命中一个节点"：命中多个时 `onNodeWithContentDescription`
            // 会直接抛错，而如果某次实现让文案同时落到两个节点上（例如文字被重复挂到父节点），
            // 这一条会先红，而不是让下面的几何断言拿到另一个节点的坐标去比。
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(1)
            val node = composeRule.onNodeWithContentDescription(label)
            val sample = settler.bounds(node) { "${config.name}: $label" }
            val unclipped = sample.unclipped
            val clipped = sample.clipped
            assertTrue("${config.name}: $label 不在场：${sample.describe}", sample.displayed)
            assertTrue(
                "${config.name}: $label 被裁掉：${sample.describe}" +
                    " capsule=$bounds band=$band",
                clipped.width.value > 0f &&
                    clipped.height.value > 0f &&
                    clipped.width.value >= unclipped.width.value - 0.5f &&
                    clipped.height.value >= unclipped.height.value - 0.5f,
            )
            // 两个几何来源（裁剪 / 未裁剪）必须说的是**同一个东西**：中心重合。
            // 这一条同时回答"裁剪几何读到的到底是不是这枚按钮"：真实的裁剪会在这里（以及上面
            // 那条尺寸断言）以"中心/尺寸对不上"暴露，而不是被 settledBounds 的等稳定逻辑放过。
            assertTrue(
                "${config.name}: $label 的裁剪/未裁剪几何不属于同一个节点：${sample.describe}",
                kotlin.math.abs((clipped.left.value + clipped.right.value) / 2f - (unclipped.left.value + unclipped.right.value) / 2f) <= 1f &&
                    kotlin.math.abs((clipped.top.value + clipped.bottom.value) / 2f - (unclipped.top.value + unclipped.bottom.value) / 2f) <= 1f,
            )
            // 圆钮：宽度必须仍等于 48dp（不随字号/屏宽被撑宽或压扁）。
            assertEquals(
                "${config.name}: $label 必须仍是 ${PlayerChromeButtonSize} 的正圆：$unclipped",
                PlayerChromeButtonSize.value,
                unclipped.width.value,
                0.5f,
            )
            assertEquals(
                "${config.name}: $label 必须仍是 ${PlayerChromeButtonSize} 的正圆：$unclipped",
                unclipped.width.value,
                unclipped.height.value,
                0.5f,
            )
        }

        // 进度行上的读数行（本批起是**一整句**：两端时刻 + 真实时长 + 计数）必须横向放得下，
        // 并且纵向有足够行高（字号放大时槽位若还是 16dp，这一行会被压掉一半 ——
        // 真机 2 倍字号截图先暴露过，横向的 clipped/unclipped 断言抓不到"自己被压扁"）。
        val readout = composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
        val readoutFontSize = playerChromeTextFontSizeSp(
            availableWidth = band.width,
            labels = listOf("A 00:12 → B 00:37 · 25.0s · 循环 ×12"),
            baseFontSize = 14.sp,
            fontScale = config.fontScale,
        )
        val readoutSample = settler.bounds(readout) { "${config.name}: 读数行" }
        assertTrue("${config.name}: 读数行不在场：${readoutSample.describe}", readoutSample.displayed)
        assertTrue(
            "${config.name}: 读数行被横向裁掉了：${readoutSample.describe}",
            readoutSample.clipped.width.value >= readoutSample.unclipped.width.value - 0.5f,
        )
        // 行高必须容得下这个字号：一行的实际高度不能小于字号本身（否则就是被压扁了）。
        val minimumLineHeight = readoutFontSize.value * config.fontScale
        assertTrue(
            "${config.name}: 读数行被压扁了：height=${readoutSample.unclipped.height.value}" +
                " < 字号 ${readoutFontSize.value}sp × ${config.fontScale}",
            readoutSample.unclipped.height.value >= minimumLineHeight - 0.5f,
        )
    }

    /**
     * 几何读数的等稳定器（见 [BoundsSettler]）：裁剪几何与"在场"判据都要读到**几何自洽**的那一帧，
     * 否则会读到与节点位置无关的瞬时脏值（实测 `0×0`、`9×9 @ (21,21)` 这种）。
     */
    private val settler by lazy { BoundsSettler(composeRule.mainClock) { composeRule.waitForIdle() } }

    /** 只断言"在场"：判据仍然是框架自己的 `assertIsDisplayed()`，只是在逐帧重判之后下结论。 */
    private fun SemanticsNodeInteraction.assertDisplayedSettled(describe: String) {
        settler.assertDisplayed(this) { describe }
    }

    /** 已裁剪几何是不是同一枚节点该有的样子：非空、且宽高都不小于未裁剪。 */
    private fun boundsAgree(sample: BoundsSample): Boolean =
        sample.clipped.width.value > 0f &&
            sample.clipped.height.value > 0f &&
            sample.clipped.width.value >= sample.unclipped.width.value - 0.5f &&
            sample.clipped.height.value >= sample.unclipped.height.value - 0.5f

    /**
     * 进度条上 A–B 区间那一层的**轨道宽度**（px）。
     *
     * 它与绘制侧用的是同一段几何：`Canvas.matchParentSize()` + 两侧各缩进一个滑杆圆钮半径，
     * 所以这里也照同一个公式算，而不是直接取进度条整行的宽度。
     */
    private fun trackWidthPx(): Float {
        val range = composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE).getUnclippedBoundsInRoot()
        return with(composeRule.density) { (range.width - YingLiSliderThumbRadius * 2).toPx() }
    }

    private data class ScreenConfig(
        val width: Dp,
        val height: Dp,
        val fontScale: Float,
        val name: String,
    )

    private enum class SlotMode { SCREENSHOT, AB_LOOP, SCREENSHOT_CAPSULE_CLOSED }

    private fun stateFor(
        mode: SlotMode,
        session: AbLoopSession = AbLoopSession.EMPTY,
        durationMillis: Long = 60_000,
    ): PlayerUiState =
        PlayerUiState(
            playback = PlaybackState.Paused(REQUEST, PlaybackTimeline(12_000, durationMillis)),
            title = "测试影片",
            screenshot = if (mode == SlotMode.SCREENSHOT) ScreenshotUiState.Armed else ScreenshotUiState.Idle,
            abToolOpen = mode == SlotMode.AB_LOOP,
            abLoop = session,
        )

    private companion object {
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0,
            PlaybackSourceContext.HOME,
        )
    }
}
