package seeyuer.yingli.player.app.playback

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.YingLiApplication
import seeyuer.yingli.player.app.Media3PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
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

    private fun waitForConnection(controller: Media3PlaybackController): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (controller.connectionState.value == PlaybackConnectionState.CONNECTED) return true
            SystemClock.sleep(25)
        }
        return false
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 5_000L
    }
}
