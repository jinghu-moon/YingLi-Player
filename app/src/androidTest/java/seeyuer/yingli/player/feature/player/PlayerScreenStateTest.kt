package seeyuer.yingli.player.feature.player

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DefaultPlaybackErrorMapper
import seeyuer.yingli.player.domain.playback.PlaybackFailureSignal
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

class PlayerScreenStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun preparingShowsLoadingOnBlackCanvas() {
        setPlayer(PlaybackState.Preparing(REQUEST))

        composeRule.onNodeWithTag(PlayerTestTags.CANVAS).assertSettledDisplayed("准备中：播放画布")
        composeRule.onNodeWithTag(PlayerTestTags.LOADING).assertSettledDisplayed("准备中：加载指示")
    }

    @Test
    fun rebufferingDoesNotShowBlockingLoadingOverlay() {
        setPlayer(PlaybackState.Preparing(REQUEST, TIMELINE, isRebuffering = true))

        composeRule.onNodeWithTag(PlayerTestTags.CANVAS).assertSettledDisplayed("重缓冲：播放画布")
        composeRule.onAllNodesWithTag(PlayerTestTags.LOADING).assertCountEquals(0)
    }

    @Test
    fun playingExposesPauseControlLabel() {
        setPlayer(PlaybackState.Playing(REQUEST, TIMELINE))
        composeRule.onNodeWithContentDescription("暂停").assertSettledDisplayed("播放中：暂停钮")
    }

    @Test
    fun pausedExposesPlayControlLabel() {
        setPlayer(PlaybackState.Paused(REQUEST, TIMELINE))
        composeRule.onNodeWithContentDescription("播放").assertSettledDisplayed("暂停中：播放钮")
    }

    @Test
    fun centerControlsExposePreviousAndNext() {
        var previousClicks = 0
        var nextClicks = 0
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        title = "测试影片",
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                    onPrevious = { previousClicks++ },
                    onNext = { nextClicks++ },
                    canNavigatePrevious = true,
                    canNavigateNext = true,
                )
            }
        }

        // 画面中央是"上一个 / 播放暂停 / 下一个"三连：不再放快退/快进 N 秒按钮。
        composeRule.onNodeWithContentDescription("上一项").performClick()
        composeRule.onNodeWithContentDescription("下一项").performClick()

        assertEquals(1, previousClicks)
        assertEquals(1, nextClicks)
    }

    @Test
    fun endedOffersReplayDisabledNextAndBack() {
        setPlayer(PlaybackState.Ended(REQUEST, TIMELINE.copy(positionMillis = 10_000)))

        composeRule.onNodeWithText("重新播放").assertSettledDisplayed("播完：重新播放")
        composeRule.onNodeWithText("下一项").assertSettledDisplayed("播完：下一项")
        composeRule.onNodeWithText("返回").assertSettledDisplayed("播完：返回")
    }

    @Test
    fun permissionFailureShowsReauthorizationAction() {
        setPlayer(PlaybackState.Failed(
            REQUEST,
            TIMELINE,
            DefaultPlaybackErrorMapper.map(PlaybackFailureSignal.ACCESS_DENIED),
        ))

        composeRule.onNodeWithText("媒体访问权限已失效，请重新授权。").assertSettledDisplayed("权限失效：说明文案")
        composeRule.onNodeWithText("重新授权").assertSettledDisplayed("权限失效：重新授权")
    }

    @Test
    fun lockedOverlayOnlyExposesUnlockAction() {
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Playing(REQUEST, TIMELINE),
                        title = "测试影片",
                        overlay = seeyuer.yingli.player.domain.playback.PlayerOverlayState(locked = true),
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        // 读屏文案按规格 §5.13「锁定界面」的「图标跟状态、文案跟动作」：锁定态按钮的读屏文案
        // 是「解锁屏幕」（`R.string.player_unlock`），不是旧实现里的「解锁控制」。
        composeRule.onNodeWithContentDescription("解锁屏幕").assertSettledDisplayed("锁定态：解锁屏幕")
        composeRule.onAllNodesWithContentDescription("返回").assertCountEquals(0)
    }

    @Test
    fun landscapeUsesLandscapeControlLayout() {
        setPlayerInSize(800, 400)

        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_CONTROLS).assertSettledDisplayed("横屏：底栏")
        composeRule.onAllNodesWithTag(PlayerTestTags.PORTRAIT_CONTROLS).assertCountEquals(0)
    }

    @Test
    fun portraitUsesFloatingControlLayout() {
        setPlayerInSize(400, 800)

        composeRule.onNodeWithTag(PlayerTestTags.PORTRAIT_CONTROLS).assertSettledDisplayed("竖屏：底栏")
        composeRule.onAllNodesWithTag(PlayerTestTags.LANDSCAPE_CONTROLS).assertCountEquals(0)
    }

    @Test
    fun settingsUsesRightPanelInLandscape() {
        setPlayerInSize(800, 400, PlayerPanel.SETTINGS)

        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_SETTINGS).assertSettledDisplayed("横屏：设置面板")
        composeRule.onAllNodesWithTag(PlayerTestTags.PORTRAIT_SETTINGS).assertCountEquals(0)
    }

    @Test
    fun settingsUsesBottomSheetInPortrait() {
        setPlayerInSize(400, 800, PlayerPanel.SETTINGS)

        composeRule.onNodeWithTag(PlayerTestTags.PORTRAIT_SETTINGS).assertSettledDisplayed("竖屏：设置面板")
        composeRule.onAllNodesWithTag(PlayerTestTags.LANDSCAPE_SETTINGS).assertCountEquals(0)
    }

    @Test
    fun screenshotPreviewCardExpandsAndDeletes() {
        var expanded: Boolean? = null
        var deleted = 0
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        title = "测试影片",
                        screenshot = ScreenshotUiState.Preview(
                            "frame.jpg",
                            "content://frame",
                            "Pictures/YingLi/frame.jpg",
                        ),
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                    onSetScreenshotPreviewExpanded = { expanded = it },
                    onDeleteScreenshot = { deleted++ },
                )
            }
        }

        // 预览卡阶段：没有删除按钮（删除只在放大预览里出现，设计稿 §4.10），点击即放大。
        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertSettledDisplayed("截图预览卡").performClick()
        assertEquals(true, expanded)
        composeRule.onAllNodesWithContentDescription("删除截图").assertCountEquals(0)
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_PREVIEW_OVERLAY).assertCountEquals(0)
    }

    @Test
    fun expandedScreenshotPreviewShowsTheDeleteActionAndCollapses() {
        var expanded: Boolean? = null
        var deleted = 0
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        title = "测试影片",
                        screenshot = ScreenshotUiState.Preview(
                            "frame.jpg",
                            "content://frame",
                            "Pictures/YingLi/frame.jpg",
                            expanded = true,
                        ),
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                    onSetScreenshotPreviewExpanded = { expanded = it },
                    onDeleteScreenshot = { deleted++ },
                )
            }
        }

        // 放大预览阶段：铺满画布、右上角有删除按钮，小卡不再同时出现。
        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_PREVIEW_OVERLAY).assertSettledDisplayed("截图大图预览")
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("删除截图").assertSettledDisplayed("截图大图：删除按钮").performClick()

        assertEquals(1, deleted)
        assertEquals(null, expanded)
    }

    @Test
    fun armedScreenshotRendersTheToolCapsuleWithoutTheCenterControls() {
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        title = "测试影片",
                        screenshot = ScreenshotUiState.Armed,
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }

        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_CAPSULE).assertSettledDisplayed("截图工具胶囊")
        // 截图模式激活期间中央三连（上一个/播放/下一个）不出现；退出后由
        // `centerControlsExposePreviousAndNext` 覆盖"回来了"这一半。
        composeRule.onAllNodesWithContentDescription("上一项").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("下一项").assertCountEquals(0)
    }

    /**
     * **需求 D**：真横屏 + AB 胶囊打开时，画面中央三连**不在场**。
     *
     * 上一批的"真横屏档"暴露：800×400dp 下 AB 胶囊比截图胶囊多一行读数条（读数条住胶囊首行），
     * 底栏整体更高，中央三连正好压住胶囊里的读数行与 B 徽标。处理与截图工具**对称** ——
     * 两者都是"这一刻用户在读数值 / 定位到某一帧"，中央三连与这个目标无关。
     *
     * 与 `armedScreenshotRendersTheToolCapsuleWithoutTheCenterControls` 是同一条判据的两个入口
     * （`hidesCenterTransportControlsForTool`）。
     */
    @Test
    fun landscapeAbCapsuleHidesTheCenterControls() {
        var abToolOpen by mutableStateOf(true)
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().aspectRatio(800f / 400f)) {
                    PlayerScreen(
                        state = PlayerUiState(
                            playback = PlaybackState.Playing(REQUEST, TIMELINE),
                            title = "测试影片",
                            abToolOpen = abToolOpen,
                        ),
                        onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                        onRecovery = {}, videoSurface = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 这一档必须是**真横屏**（否则断言的就不是横屏那条路径）。
        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_CONTROLS).assertSettledDisplayed("横屏：底栏")
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertSettledDisplayed("横屏：AB 胶囊")
        composeRule.onAllNodesWithTag(PlayerTestTags.LANDSCAPE_CENTER_CONTROLS).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("上一项").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("下一项").assertCountEquals(0)

        // 关掉胶囊：中央三连回来（只加这一条判据，横竖屏/锁定/自动隐藏的既有条件不变）。
        abToolOpen = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_CENTER_CONTROLS)
            .assertSettledDisplayed("横屏：胶囊关闭后的中央三连")
    }

    /**
     * 几何读数的**等稳定器**（见 [BoundsSettler]）：裸 `assertIsDisplayed()` 读的是带裁剪的
     * `boundsInWindow`，刚重排过的那一帧会间歇性误报"不可见"（实测过 `0×0 @ (0,0)` 这种
     * 与节点位置无关的瞬时脏值），于是"产品到底在不在场"读不出来。
     */
    private val settler by lazy { BoundsSettler(composeRule.mainClock) { composeRule.waitForIdle() } }

    /**
     * 在场断言，判据**比裸 `assertIsDisplayed()` 更强**：要求读到的那一帧同时满足
     *  ① 框架自己判"在场"（同一套 `boundsInWindow` 判据，不另立一套）；
     *  ② 已裁剪几何与未裁剪几何**属于同一个节点**（非空、宽高不缩水、中心重合 —— 即"没被裁掉"）。
     *
     * 它不会把真实的裁剪/隐藏掩盖成绿色：真被裁掉时**每一帧**都不自洽，十二帧用尽后
     * `settled` 仍是 false，断言照样失败，消息里还带着读到的几何与帧数。
     */
    private fun SemanticsNodeInteraction.assertSettledDisplayed(describe: String): SemanticsNodeInteraction {
        val sample = settler.bounds(this) { describe }
        assertTrue("$describe 不在场 / 被裁 / 几何不自洽：${sample.describe}", sample.settled)
        return this
    }

    private fun setPlayer(playbackState: PlaybackState) {
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(playback = playbackState, title = "测试影片"),
                    onBack = {},
                    onPlay = {},
                    onPause = {},
                    onSeek = {},
                    onReplay = {},
                    onRetry = {},
                    onRecovery = {},
                    videoSurface = {},
                )
            }
        }
    }

    private fun setPlayerInSize(width: Int, height: Int, panel: PlayerPanel = PlayerPanel.NONE) {
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().aspectRatio(width.toFloat() / height.toFloat())) {
                    PlayerScreen(
                        state = PlayerUiState(
                            playback = PlaybackState.Playing(REQUEST, TIMELINE),
                            title = "测试影片",
                            panel = panel,
                        ),
                        onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                        onRecovery = {}, videoSurface = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private companion object {
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0,
            PlaybackSourceContext.HOME,
        )
        val TIMELINE = PlaybackTimeline(2_000, 10_000, 4_000)
    }
}
