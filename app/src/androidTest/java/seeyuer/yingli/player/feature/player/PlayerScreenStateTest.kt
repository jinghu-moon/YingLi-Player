package seeyuer.yingli.player.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
