package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
 *  2. 设置点按钮显示时间 / "设置"，未设 A 时 B 禁用；
 *  3. 托盘按钮**只在循环生效时**选中（实心）；关闭胶囊不影响它（D3）；
 *  4. 进度行画出 A–B 区间与 `循环 ×N`，并且**关闭胶囊后仍然显示**。
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

        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_CAPSULE).assertIsDisplayed()
        val screenshotBounds = composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_CAPSULE)
            .getUnclippedBoundsInRoot()

        mode = SlotMode.AB_LOOP
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertIsDisplayed()
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
        // 截图胶囊在同一个带子里也是这个竖直带（它的顶边与 AB 胶囊一致，见上面的断言）。
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

        // 胶囊里的按钮一律用**读屏文案**定位：正文 `A 00:12` 在进度行的区间读数里也有一个，
        // 用文本定位会同时命中两处（而进度行那一处没有读屏文案，所以它是唯一的）。
        composeRule.onNodeWithContentDescription("设置 A 点").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsDisplayed()
        // 没有 A 就没有区间可言 → B 禁用（沿用旧胶囊的禁用规则）。
        composeRule.onNodeWithContentDescription("设置 B 点").assertIsNotEnabled()

        session = AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 37_000), loopCount = 3)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("A 点 00:12").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("B 点 00:37").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("B 点 00:37").assertIsEnabled()
        // 关闭按钮的文案是"关闭"而不是"取消"：关闭 ≠ 取消（D3），只有"清除"才取消循环。
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
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
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE).assertIsDisplayed()
        val labels = composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
        labels.assertIsDisplayed()
        // 三处读数是**这一行**的孩子：按行取，避免与 AB 胶囊里的同名文字互相干扰。
        labels.onChildren().filterToOne(hasText("A 00:12")).assertIsDisplayed()
        labels.onChildren().filterToOne(hasText("循环 ×12")).assertIsDisplayed()
        labels.onChildren().filterToOne(hasText("B 00:37")).assertIsDisplayed()
        composeRule.onNodeWithTag(PlayerTestTags.AB_LOOP_COUNT).assertTextEquals("循环 ×12")

        // 只设了 A：区间还不完整 → 不显示计数（否则会读成"已经在循环但一次没跑"），
        // 但 A 的标记与读数照旧出现。
        session = AbLoopSession(state = AbLoopState(pointA = 12_000))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE).assertIsDisplayed()
        labels.onChildren().filterToOne(hasText("A 00:12")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PlayerTestTags.AB_LOOP_COUNT).assertCountEquals(0)
    }

    /**
     * 窄屏 / 横屏 / 系统字号放大三种验收场景（§3.2 的"带文字胶囊按钮"）。
     *
     * 断言分三层，缺一不可：
     *  1. **布局**：整枚胶囊必须落在屏幕里（不溢出）；
     *  2. **节点**：四个按钮都在场，且 `clipped == unclipped`（没有被任何祖先裁掉）；
     *  3. **模型**：按同一个字宽模型算出来的字号仍 ≥ 可读下限，且该字号下的估算总宽度
     *     不超过胶囊内的可用宽度 —— 这就是"缩字号但不截断"的可验证形式。
     *     （截图里的字形是否好看仍属观感项，见交付说明。）
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
        capsule.assertIsDisplayed()
        val bounds = capsule.getUnclippedBoundsInRoot()
        assertTrue(
            "${config.name}: 胶囊不得溢出屏幕：capsule=$bounds 屏幕宽=${config.width}",
            bounds.left.value >= -0.5f && bounds.right.value <= config.width.value + 0.5f,
        )

        val band = composeRule.onNodeWithTag(PlayerTestTags.AUXILIARY_BAND).getUnclippedBoundsInRoot()
        listOf("A 点 00:12", "B 点 00:37", "清除", "关闭").forEach { label ->
            val node = composeRule.onNodeWithContentDescription(label)
            val unclipped = node.getUnclippedBoundsInRoot()
            val clipped = node.getBoundsInRoot()
            assertTrue(
                "${config.name}: $label 不在场或被裁掉：unclipped=$unclipped clipped=$clipped" +
                    " capsule=$bounds band=$band",
                clipped.width.value > 0f &&
                    clipped.height.value > 0f &&
                    clipped.width.value >= unclipped.width.value - 0.5f &&
                    clipped.height.value >= unclipped.height.value - 0.5f,
            )
        }

        val labels = listOf("A 00:12", "B 00:37", "清除")
        // 用**界面自己那一套**排版决策（同一函数、同一可用宽度 = 辅助带宽度）复核：
        // 字号必须仍可读，且算出来确实放得下。这就是"缩字号但不截断"的可验证形式 ——
        // 截断会先在这些断言上红，而不是等用户看到。
        val layout = abCapsuleTextLayout(band.width, labels, 14.sp, config.fontScale)
        assertTrue(
            "${config.name}: 字号必须仍在可读下限之上：${layout.fontSize}",
            layout.fontSize.value >= PlayerChromeTextMinFontSize.value,
        )
        assertTrue(
            "${config.name}: 该档排版必须真的放得下：$layout",
            layout.labelsFit(labels, config.fontScale),
        )

        // 进度行上的 A–B 读数（`A 00:12` / `循环 ×12` / `B 00:37`）是**新加的一行**：
        // 它必须横向放得下、**并且**纵向有足够行高（字号放大时槽位若还是 16dp，这一行会被压掉一半 ——
        // 真机 2 倍字号截图先暴露过，横向的 clipped/unclipped 断言抓不到"自己被压扁"这种情况）。
        val readout = composeRule.onNodeWithTag(PlayerTestTags.AB_RANGE_LABELS)
        readout.assertIsDisplayed()
        val readoutFontSize = playerChromeTextFontSizeSp(
            availableWidth = band.width - AbReadoutGapForTest * 2,
            labels = listOf("A 00:12", "循环 ×12", "B 00:37"),
            baseFontSize = 14.sp,
            fontScale = config.fontScale,
        )
        listOf("A 00:12", "循环 ×12", "B 00:37").forEach { text ->
            val node = readout.onChildren().filterToOne(hasText(text))
            val unclipped = node.getUnclippedBoundsInRoot()
            val clipped = node.getBoundsInRoot()
            assertTrue(
                "${config.name}: 读数 '$text' 被横向裁掉了：unclipped=$unclipped clipped=$clipped",
                clipped.width.value > 0f &&
                    clipped.height.value > 0f &&
                    clipped.width.value >= unclipped.width.value - 0.5f &&
                    clipped.height.value >= unclipped.height.value - 0.5f,
            )
            // 行高必须容得下这个字号：一行的实际高度不能小于字号本身（否则就是被压扁了）。
            val minimumLineHeight = readoutFontSize.value * config.fontScale
            assertTrue(
                "${config.name}: 读数 '$text' 被压扁了：height=${unclipped.height.value}" +
                    " < 字号 ${readoutFontSize.value}sp × ${config.fontScale}",
                unclipped.height.value >= minimumLineHeight - 0.5f,
            )
        }
    }

    /** 与 `AbReadoutGap` 同值（那是 main 内部常量，测试只关心"两段之间各留 8dp"这一事实）。 */
    private val AbReadoutGapForTest = 8.dp

    private data class ScreenConfig(
        val width: Dp,
        val height: Dp,
        val fontScale: Float,
        val name: String,
    )

    private enum class SlotMode { SCREENSHOT, AB_LOOP, SCREENSHOT_CAPSULE_CLOSED }

    private fun stateFor(mode: SlotMode, session: AbLoopSession = AbLoopSession.EMPTY): PlayerUiState =
        PlayerUiState(
            playback = PlaybackState.Paused(REQUEST, TIMELINE),
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
        val TIMELINE = PlaybackTimeline(12_000, 60_000)
    }
}
