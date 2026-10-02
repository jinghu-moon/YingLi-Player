package seeyuer.yingli.player.feature.player

import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

        composeRule.onNodeWithTag(PlayerTestTags.CANVAS).assertIsDisplayed()
        composeRule.onNodeWithTag(PlayerTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun playingExposesPauseControlLabel() {
        setPlayer(PlaybackState.Playing(REQUEST, TIMELINE))
        composeRule.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test
    fun pausedExposesPlayControlLabel() {
        setPlayer(PlaybackState.Paused(REQUEST, TIMELINE))
        composeRule.onNodeWithContentDescription("播放").assertIsDisplayed()
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

        composeRule.onNodeWithText("重新播放").assertIsDisplayed()
        composeRule.onNodeWithText("下一项").assertIsDisplayed()
        composeRule.onNodeWithText("返回").assertIsDisplayed()
    }

    @Test
    fun permissionFailureShowsReauthorizationAction() {
        setPlayer(PlaybackState.Failed(
            REQUEST,
            TIMELINE,
            DefaultPlaybackErrorMapper.map(PlaybackFailureSignal.ACCESS_DENIED),
        ))

        composeRule.onNodeWithText("媒体访问权限已失效，请重新授权。").assertIsDisplayed()
        composeRule.onNodeWithText("重新授权").assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription("解锁控制").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("返回").assertCountEquals(0)
    }

    @Test
    fun landscapeUsesLandscapeControlLayout() {
        setPlayerInSize(800, 400)

        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_CONTROLS).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PlayerTestTags.PORTRAIT_CONTROLS).assertCountEquals(0)
    }

    @Test
    fun portraitUsesFloatingControlLayout() {
        setPlayerInSize(400, 800)

        composeRule.onNodeWithTag(PlayerTestTags.PORTRAIT_CONTROLS).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PlayerTestTags.LANDSCAPE_CONTROLS).assertCountEquals(0)
    }

    @Test
    fun settingsUsesRightPanelInLandscape() {
        setPlayerInSize(800, 400, PlayerPanel.SETTINGS)

        composeRule.onNodeWithTag(PlayerTestTags.LANDSCAPE_SETTINGS).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PlayerTestTags.PORTRAIT_SETTINGS).assertCountEquals(0)
    }

    @Test
    fun settingsUsesBottomSheetInPortrait() {
        setPlayerInSize(400, 800, PlayerPanel.SETTINGS)

        composeRule.onNodeWithTag(PlayerTestTags.PORTRAIT_SETTINGS).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PlayerTestTags.LANDSCAPE_SETTINGS).assertCountEquals(0)
    }

    @Test
    fun screenshotPreviewSupportsPauseCloseAndDeleteActions() {
        var toggled = 0
        var closed = 0
        var deleted = 0
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        title = "测试影片",
                        screenshot = ScreenshotUiState.Preview("frame.jpg"),
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                    onToggleScreenshotPreview = { toggled++ },
                    onCloseScreenshot = { closed++ },
                    onDeleteScreenshot = { deleted++ },
                )
            }
        }

        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("取消").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("删除截图").assertIsDisplayed().performClick()

        assertEquals(1, toggled)
        assertEquals(1, closed)
        assertEquals(1, deleted)
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
