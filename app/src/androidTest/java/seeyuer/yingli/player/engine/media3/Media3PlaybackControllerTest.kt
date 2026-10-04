package seeyuer.yingli.player.engine.media3

import android.content.Context
import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.YingLiApplication
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge
import seeyuer.yingli.player.app.playback.YingLiPlaybackService
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopSessionCommands
import seeyuer.yingli.player.engine.media3.Media3PlaybackController
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.NoOpFrameCalibrationControl
import seeyuer.yingli.player.domain.playback.PlaybackAction
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
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

    /**
     * **真正缺失的那一跳**：设点成功后，会话状态必须经 session extras 回到**客户端投影**。
     *
     * 为什么这条用例必须存在：`AbLoopActivationInstrumentedTest` 直接调 `runtime.setPoint()`
     * （绕过 `MediaController`），`PlayerAbLoopCapsuleCommandTest` 用的是控制器替身（绕过会话）。
     * 两端各自都有覆盖，"自定义命令 → 服务侧处理 → 状态回流"这条**缝**上一次都没有真机断言 ——
     * 于是"点 A 有涟漪、状态不变"（命令被接受，但客户端投影里没有 A）可以一直存在。
     *
     * 四步断言缺一不可，因为它们指向不同的责任方：
     *  1. **命令已声明**：控制器的"可发送命令集"里必须有这两条自定义命令 —— 它直接来自服务侧
     *     `MediaSession.Callback.onConnect` 的 `setAvailableSessionCommands`。Media3 只允许控制器
     *     发送**在连接时声明过**的自定义命令，没声明的话命令在客户端就被拦下（连会话都到不了）；
     *  2. **回执**：真实自定义命令跨会话边界后是 `Applied`（不是被 Media3 拦下、也不是被吞掉）；
     *  3. **会话真相**：服务发布的 session extras（raw，未经客户端解码）里带上了 A；
     *  4. **客户端投影**：`Media3PlaybackController.abLoop`（= UI 读的那一份）里 A 也在。
     * 第 4 步曾经红：客户端重建状态时要求 A、B 同时存在，把"只设了 A"丢成空区间 ——
     * 胶囊因此一直显示"A 未设置"、B 与清除一直禁用，而且因为命令**确实成功**，
     * 没有任何拒绝提示可显示。
     */
    @Test
    @UnstableApi
    fun setPointOnTheRealSessionRoundTripsIntoTheClientProjection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as YingLiApplication
        val controller = application.playbackController
        assertTrue(waitForConnection(controller))

        val wav = writeSilentWav(context)
        try {
            // 0) 两条自定义命令必须**已在连接时声明**（服务侧 `onConnect`）：
            //    这一条是"命令到底能不能离开客户端"的前提，必须作为可断言的设备事实存在，
            //    而不是靠"命令没生效 ⇒ 大概没声明"来猜。
            val available = readOnMain {
                (controller.connectedPlayer() as? MediaController)?.availableSessionCommands
            }
            assertNotNull("控制器没有连上会话，拿不到可发送命令集", available)
            assertEquals(
                "AB 自定义命令必须在 onConnect 里声明，否则 Media3 会在客户端就拦下它们：" +
                    "available=$available",
                true,
                available?.contains(SessionCommand(AbLoopSessionCommands.SET_POINT, Bundle.EMPTY)),
            )
            assertEquals(
                "AB 清除命令同样必须在 onConnect 里声明",
                true,
                available?.contains(SessionCommand(AbLoopSessionCommands.CLEAR, Bundle.EMPTY)),
            )

            // 干净的起点：会话里没有媒体、也没有区间（上一个用例可能留下状态）。
            onMain { controller.stop() }
            assertTrue(
                "会话没有回到空区间：${controller.abLoop.value}",
                await { controller.abLoop.value == AbLoopSession.EMPTY },
            )
            // 用**客户端提供的 URI** 打开媒体（与 `PlaybackSessionClientBridge.open` 同一条路径：
            // 会话侧 `MediaSessionPlayerAdapter` 会把这条 URI 登记为自己的待解析来源）。
            onMain {
                controller.prepare(
                    ResolvedPlaybackSource(
                        request = PlaybackRequest(
                            mediaId = MediaItemId(MEDIA_ID),
                            locationId = MediaLocationId(MEDIA_ID),
                            startPositionMillis = 0,
                            sourceContext = PlaybackSourceContext.HOME,
                        ),
                        uri = Uri.fromFile(wav).toString(),
                        title = "ab-controller-round-trip.wav",
                        durationMillis = DURATION_MILLIS,
                    ),
                )
            }
            // 等到"可以 seek"为止：`Preparing`（首次缓冲）里没有 SEEK 动作，此时 seek 会被
            // 控制器直接拒绝 —— 用播放状态而不是"媒体项是否可 seek"作前提，前者才是命令层的事实。
            assertTrue(
                "会话没有进入可 seek 的状态（duration=${readOnMain { controller.connectedPlayer()?.duration }}" +
                    " state=${controller.state.value}" +
                    " mediaItem=${readOnMain { controller.connectedPlayer()?.currentMediaItem }}）",
                await { PlaybackAction.SEEK in controller.state.value.availableActions },
            )
            // 设点取的是会话侧**实时**位置，所以先把播放位置推到一个明确的点上。
            onMain { controller.seekTo(POINT_A_MILLIS) }
            assertTrue(
                "seek 没有生效：pos=${readOnMain { controller.currentPositionMillis() }}" +
                    " state=${controller.state.value}",
                await { readOnMain { controller.currentPositionMillis() } >= POINT_A_MILLIS },
            )

            // 1) 真实自定义命令：client → MediaSession → 服务 onCustomCommand → 会话 runtime
            val outcome = runBlocking { controller.requestSetAbPoint(AbPoint.A) }
            assertEquals("设 A 必须被会话接受（回执=$outcome）", AbLoopCommandOutcome.Applied, outcome)

            // 2) 会话真相：服务把 A 发布到 session extras（raw，未经客户端解码）。
            //    注意必须经 `MediaController` 读：`Player` 接口上没有 session extras。
            val publishedA = readOnMain {
                (controller.connectedPlayer() as? MediaController)?.sessionExtras
                    ?.getString(AbLoopSessionCommands.EXTRA_POINT_A)
            }
            assertNotNull("服务没有把 A 发布到 session extras：问题在会话/服务侧", publishedA)

            // 3) 客户端投影：UI 读的就是这一份，必须看到同一个 A
            assertTrue(
                "A 没有回流到客户端投影（extras=$publishedA projection=${controller.abLoop.value}）",
                await { controller.abLoop.value.pointA == publishedA?.toLongOrNull() },
            )

            // 设 B 之前先走到 A 之后至少一帧的位置：B 落在 A 同一帧上是"非法区间"，会被拒。
            onMain { controller.seekTo(POINT_A_MILLIS + 2_000) }
            assertTrue(
                "seek 没有生效：pos=${readOnMain { controller.currentPositionMillis() }}",
                await { readOnMain { controller.currentPositionMillis() } >= POINT_A_MILLIS + 1_000 },
            )
            assertEquals(
                "设 B 必须被会话接受",
                AbLoopCommandOutcome.Applied,
                runBlocking { controller.requestSetAbPoint(AbPoint.B) },
            )
            assertTrue(
                "区间设全后客户端投影必须 active：${controller.abLoop.value}",
                await { controller.abLoop.value.active },
            )

            // 清除走的是另一条自定义命令（`AB_CLEAR`），回流同样要生效。
            onMain { controller.requestClearAbLoop() }
            assertTrue(
                "清除没有回流到客户端投影：${controller.abLoop.value}",
                await { controller.abLoop.value == AbLoopSession.EMPTY },
            )
        } finally {
            onMain { controller.stop() }
            await { controller.abLoop.value == AbLoopSession.EMPTY }
            wav.delete()
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun <T> readOnMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(timeoutMillis: Long = ROUND_TRIP_TIMEOUT_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(20)
        }
        return condition()
    }

    /**
     * 自造一段单声道静音 WAV：不依赖设备上的任何素材，也不需要编解码器生成视频轨
     * （与 `AbLoopActivationInstrumentedTest` 同一份写法，避免测试之间共用外部文件）。
     */
    private fun writeSilentWav(context: Context): File {
        val file = File(context.cacheDir, "ab-controller-round-trip.wav")
        val sampleRate = 22_050
        val bitsPerSample = 8
        val dataSize = (sampleRate * DURATION_MILLIS / 1_000).toInt()
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLe(36 + dataSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLe(16))
            out.write(shortLe(1))
            out.write(shortLe(1))
            out.write(intLe(sampleRate))
            out.write(intLe(sampleRate * bitsPerSample / 8))
            out.write(shortLe(bitsPerSample / 8))
            out.write(shortLe(bitsPerSample))
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLe(dataSize))
            out.write(ByteArray(dataSize) { 128.toByte() })
        }
        return file
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

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

        /** 跨会话边界的命令回执 + 状态回流是跨进程往返：给它比连接超时更宽的上限。 */
        const val ROUND_TRIP_TIMEOUT_MILLIS = 10_000L

        const val MEDIA_ID = "ab-controller-round-trip"
        const val DURATION_MILLIS = 60_000L

        /** 设 A 的位置：客户端投影只断言"与**会话发布的那个值**一致"，不假设吸附结果。 */
        const val POINT_A_MILLIS = 10_000L
    }
}
