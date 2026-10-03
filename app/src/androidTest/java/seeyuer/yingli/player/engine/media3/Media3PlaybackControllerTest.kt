package seeyuer.yingli.player.engine.media3

import android.content.Context
import android.content.ComponentName
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.YingLiApplication
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge
import seeyuer.yingli.player.app.playback.YingLiPlaybackService
import seeyuer.yingli.player.engine.media3.Media3PlaybackController
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.NoOpFrameCalibrationControl
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.VideoScaleMode

@RunWith(AndroidJUnit4::class)
class Media3PlaybackControllerTest {
    @Test
    fun applicationOwnsOneControllerThatConnectsToPlaybackService() {
        val application = ApplicationProvider.getApplicationContext<Context>() as YingLiApplication

        assertSame(application.playbackController, application.playbackController)
        assertTrue(waitForConnection(application.playbackController))
    }

    @Test
    fun invalidIdleCommandIsRejectedWithoutCreatingPlayback() {
        val application = ApplicationProvider.getApplicationContext<Context>() as YingLiApplication
        assertTrue(waitForConnection(application.playbackController))

        val result = application.playbackController.seekTo(1_000)

        assertTrue(
            result == PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE) ||
                result == PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED),
        )
    }

    @Test
    fun advancedPreferencesDoNotCreateAnotherSession() {
        val application = ApplicationProvider.getApplicationContext<Context>() as YingLiApplication
        assertTrue(waitForConnection(application.playbackController))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(application.playbackController.setSpeed(PlaybackSpeed.of(1.5f)) is PlaybackCommandResult.Accepted)
            assertTrue(application.playbackController.setScaleMode(VideoScaleMode.FILL) is PlaybackCommandResult.Accepted)
        }
        assertSame(application.playbackController, application.playbackController)
    }

    @Test
    fun newControllerReconnectsAfterPreviousClientCloses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as YingLiApplication
        assertTrue(waitForConnection(application.playbackController))

        val replacement = Media3PlaybackController(
            context,
            ComponentName(context, YingLiPlaybackService::class.java),
            application.mediaContainer.playbackSourceRepository,
            application.container.dispatchers,
            application.container.logger,
        )
        try {
            assertTrue(waitForConnection(replacement))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { replacement.close() }
        }
    }

    /**
     * 设点被拒的**回执**必须跨会话边界回到客户端。
     *
     * 这里没有加载任何媒体：会话侧 timeline 的时长是未知值 → `AB_UNAVAILABLE`。
     * 这正好是"不可用媒体上设点"的真机场景，也是之前 fire-and-forget 会静默吞掉的那条路径。
     * 断言到客户端拿到的拒绝码为止（`SessionResult` 结果码 + extras 的真实 IPC 往返），
     * 再往上的 bridge→UI 提示由 JVM 用例覆盖。
     */
    @Test
    fun setPointOnUnavailableMediaReturnsTheSessionRejection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as YingLiApplication
        assertTrue(waitForConnection(application.playbackController))
        // 先确保会话里没有媒体：否则"时长未知"这一前提取决于上一个用例留下的状态。
        onMain { application.playbackController.stop() }

        val outcome = runBlocking { application.playbackController.requestSetAbPoint(AbPoint.A) }
        val rejected = outcome as? AbLoopCommandOutcome.Rejected
        assertNotNull("set point on unavailable media must be rejected, was $outcome", rejected)
        assertEquals(PlaybackCommandRejection.AB_UNAVAILABLE, rejected?.rejection)
    }

    /** 真机上的完整回传链：会话拒绝 → 回执 → 控制器 → bridge 的统一瞬时反馈。 */
    @Test
    fun bridgeReceivesTheSetPointRejectionOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as YingLiApplication
        assertTrue(waitForConnection(application.playbackController))
        onMain { application.playbackController.stop() }

        val bridge = PlaybackSessionClientBridge(
            controller = application.playbackController,
            sourceRepository = application.mediaContainer.playbackSourceRepository,
            dispatchers = application.container.dispatchers,
            frameCalibrationControl = NoOpFrameCalibrationControl,
        )
        val feedback = CopyOnWriteArrayList<String>()
        val collector = CoroutineScope(Dispatchers.Main).launch {
            bridge.events.collect { event ->
                if (event is PlaybackSessionEvent.OneShotFeedback) feedback += event.code
            }
        }
        try {
            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.A))
            val deadline = SystemClock.elapsedRealtime() + FEEDBACK_TIMEOUT_MILLIS
            while (feedback.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(
                listOf(PlaybackCommandRejection.AB_UNAVAILABLE.name),
                feedback.toList(),
            )
        } finally {
            collector.cancel()
            bridge.close()
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun waitForConnection(
        controller: Media3PlaybackController,
        timeoutMillis: Long = CONNECTION_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (controller.connectionState.value == PlaybackConnectionState.CONNECTED) return true
            SystemClock.sleep(25)
        }
        return false
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 5_000L
        const val FEEDBACK_TIMEOUT_MILLIS = 5_000L
    }
}
