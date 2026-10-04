package seeyuer.yingli.player.feature.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.PauseReason
import seeyuer.yingli.player.domain.playback.PlaybackCommandHandle
import seeyuer.yingli.player.domain.playback.PlaybackCommandId
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.VideoRotation

/**
 * 阶段 3 的**互斥三分支 + 异步晚到**（instrumented，驱动**真实** `PlayerViewModel` 与真实布局）。
 *
 * 为什么这一条要跑真机上的真 ViewModel，而不是只在 JVM 上比状态：这里要断言的时序是
 * "用户按下快门 → 立刻打开 AB 工具 → 那次捕获的回调才回来"，中间夹着真实的协程调度与
 * 组合/重组的时机；只有把状态投影真的画出来，才能断言"预览卡与 AB 胶囊不会同时出现"。
 *
 * JVM 侧的 `PlayerViewModelTest` 覆盖同一批分支的纯时序（含"状态绕回 Capturing"这一种），
 * 两层一起才算把 §3.2 的三分支与 §3.2 的异步晚到钉住。
 */
@RunWith(AndroidJUnit4::class)
class PlayerAbLoopExclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 几何读数的等稳定器（见 [BoundsSettler]）：`assertIsDisplayed()` 读的是带裁剪的
     * `boundsInWindow`，在刚重排过的那一帧会误报"不在场"（实测）。等布局稳定那一帧再判，
     * 判据仍然是框架自己那一套。
     */
    private val settler by lazy { BoundsSettler(composeRule.mainClock) { composeRule.waitForIdle() } }

    private fun SemanticsNodeInteraction.assertDisplayedSettled(describe: String) {
        settler.assertDisplayed(this) { describe }
    }

    @Test
    fun aLateCaptureResultNeverRendersAPreviewCardNextToTheAbCapsule() {
        val gateway = GatedScreenshotGateway()
        val viewModel = playerViewModel(gateway)
        val projected = mutableStateOf(PlayerUiState())
        composeRule.setContent {
            LaunchedEffect(Unit) { viewModel.state.collect { projected.value = it } }
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = projected.value,
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.playback is PlaybackState.Paused }

        // 按下快门（捕获挂起），随后立刻打开 AB 工具 —— 这正是"异步晚到"的现场。
        composeRule.runOnIdle { viewModel.armScreenshot() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.screenshot == ScreenshotUiState.Armed }
        composeRule.runOnIdle { viewModel.captureScreenshot() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.screenshot == ScreenshotUiState.Capturing }
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_CAPSULE).assertCountEquals(1)

        composeRule.runOnIdle { viewModel.openAbTool() }
        // Armed / Capturing 分支：截图会话整体结束，AB 胶囊进入辅助带那一格。
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            projected.value.abToolOpen && projected.value.screenshot == ScreenshotUiState.Idle
        }
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDisplayedSettled("AB 胶囊")
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_CAPSULE).assertCountEquals(0)

        // 现在那次捕获才回来：文件已落盘，但结果必须被丢弃。
        gateway.complete(ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg"))
        composeRule.waitForIdle()

        assertEquals(ScreenshotUiState.Idle, projected.value.screenshot)
        // 关键断言：预览卡与 AB 胶囊**不同时出现**。
        composeRule.onAllNodesWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertCountEquals(0)
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDisplayedSettled("AB 胶囊")
    }

    @Test
    fun openingTheAbToolKeepsThePreviewCardAndItsCountdown() {
        val viewModel = playerViewModel(
            ImmediateScreenshotGateway(ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg")),
        )
        val projected = mutableStateOf(PlayerUiState())
        composeRule.setContent {
            LaunchedEffect(Unit) { viewModel.state.collect { projected.value = it } }
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = projected.value,
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                )
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.playback is PlaybackState.Paused }

        composeRule.runOnIdle { viewModel.armScreenshot() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.screenshot == ScreenshotUiState.Armed }
        composeRule.runOnIdle { viewModel.captureScreenshot() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.screenshot is ScreenshotUiState.Preview }
        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertDisplayedSettled("截图预览卡")
        val before = (projected.value.screenshot as ScreenshotUiState.Preview).remainingMillis

        composeRule.runOnIdle { viewModel.openAbTool() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.abToolOpen }

        // Preview 分支：**保留**预览卡与倒计时（那是用户已经拿到的结果），只退出截图工具模式。
        // 注意：预览卡倒计时只有 3 秒（`PREVIEW_DURATION_MILLIS`），这一段里的每一次断言都必须
        // 保持"一次读数"的开销 —— 这也是 [BoundsSettler.assertDisplayed] 要"便宜"的原因
        //（"连续两帧一致"的等稳定写法实测每帧 300~800ms，会把倒计时耗光）。
        composeRule.onNodeWithTag(PlayerTestTags.SCREENSHOT_PREVIEW).assertDisplayedSettled("截图预览卡")
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDisplayedSettled("AB 胶囊")
        // 截图工具模式退出 = 底栏三段回来（AB 胶囊住的辅助带就在其中）。
        composeRule.onNodeWithTag(PlayerTestTags.PORTRAIT_CONTROLS).assertDisplayedSettled("竖屏底栏")

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            (projected.value.screenshot as? ScreenshotUiState.Preview)?.remainingMillis?.let { it < before } == true
        }
    }

    private fun playerViewModel(gateway: ScreenshotGateway): PlayerViewModel = PlayerViewModel(
        sessionClient = FakeSessionClient(REQUEST, TIMELINE),
        dispatchers = DefaultAppDispatchers,
        screenshotGateway = gateway,
    )

    /** 会话客户端的替身：只提供"已暂停的当前媒体"与实时位置，命令一律接受。 */
    private class FakeSessionClient(
        request: PlaybackRequest,
        private val timeline: PlaybackTimeline,
    ) : PlaybackSessionClient {
        override val snapshot = MutableStateFlow(
            PlaybackSessionSnapshot(
                sessionId = PlaybackSessionId("ab-exclusion-test"),
                mediaId = request.mediaId,
                title = "测试影片",
                phase = PlaybackPhase.Paused(PauseReason.USER),
                timeline = timeline,
            ),
        )
        override val events: Flow<PlaybackSessionEvent> = emptyFlow()

        override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle =
            PlaybackCommandHandle(PlaybackCommandId(1))

        /** 实时位置：截图时间戳取的就是它（与快照里的 timeline 位置刻意不同）。 */
        override fun currentPositionMillis(): Long = timeline.positionMillis + 5_000
    }

    /** 捕获结果由测试**显式放行**：用来构造"回调晚到"。 */
    private class GatedScreenshotGateway : ScreenshotGateway {
        private val gate = CompletableDeferred<ScreenshotResult>()

        fun complete(result: ScreenshotResult) {
            gate.complete(result)
        }

        override suspend fun capture(
            videoTitle: String,
            positionMillis: Long,
            rotation: VideoRotation,
        ): ScreenshotResult = gate.await()
    }

    private class ImmediateScreenshotGateway(private val result: ScreenshotResult) : ScreenshotGateway {
        override suspend fun capture(
            videoTitle: String,
            positionMillis: Long,
            rotation: VideoRotation,
        ): ScreenshotResult = result
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0,
            PlaybackSourceContext.HOME,
        )
        val TIMELINE = PlaybackTimeline(2_000, 600_000)
    }
}
