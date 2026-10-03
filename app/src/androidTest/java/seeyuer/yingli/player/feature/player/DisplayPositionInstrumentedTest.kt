package seeyuer.yingli.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.playback.PlaybackSessionRuntime
import seeyuer.yingli.player.app.playback.ServicePlaybackEngine
import seeyuer.yingli.player.app.playback.SourceHandleRegistry
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DISPLAY_POSITION_TICK_MILLIS
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.PlaybackCommandHandle
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.PlaybackSourceResolver
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId
import seeyuer.yingli.player.domain.playback.displayPositionMillis

/**
 * **P1 显示层位置冻结的真机回归**：把真实的 `ExoPlayer` + `ServicePlaybackEngine` +
 * `PlaybackSessionRuntime` 装起来，直接观测展示层位置流
 * （[displayPositionMillis]，`PlayerViewModel` 用的就是它）。
 *
 * 为什么必须在真机上跑：这个缺陷只在**稳定播放不再发布状态跳变**时出现 —— 快照的 timeline
 * 停在原地，而播放器自己的 `currentPosition` 一直在走。离屏渲染与固定状态都构造不出这一条缝。
 */
@RunWith(AndroidJUnit4::class)
class DisplayPositionInstrumentedTest {
    @Test
    fun the_display_position_advances_while_playing_and_freezes_while_paused() {
        withRuntimeClient { client ->
            val scope = CoroutineScope(Dispatchers.Main.immediate)
            val samples = mutableListOf<Long>()
            val collector: Job = scope.launch { displayPositionMillis(client).collect { samples += it } }
            try {
                assertTrue("playback never reached PLAYING", await { client.snapshot.value.phase is PlaybackPhase.Playing })

                // ① 播放中：展示层位置必须**持续推进**（旧实现在这里冻结在快照值上）。
                assertTrue("the display position never advanced: $samples", await { samples.size >= 3 })
                val firstWindow = samples.takeLast(3)
                assertTrue("the display position did not advance across ticks: $firstWindow", firstWindow.toSet().size > 1)
                assertTrue(
                    "the display position did not follow the live player position: samples=$firstWindow" +
                        " live=${client.currentPositionMillis()}",
                    await { kotlin.math.abs(samples.last() - client.currentPositionMillis()) <= DISPLAY_POSITION_TICK_MILLIS * 2 },
                )

                // ② 暂停后：展示值必须停住（不再随 tick 变化），且停住的位置就是暂停时的实时位置。
                onMain { client.dispatch(PlaybackSessionCommand.Pause) }
                assertTrue("pause never took effect", await { client.snapshot.value.phase is PlaybackPhase.Paused })
                val pausedAt = client.currentPositionMillis()
                assertTrue(await { samples.last() == pausedAt })
                Thread.sleep(DISPLAY_POSITION_TICK_MILLIS * 6)
                assertEquals("the display position kept advancing while paused", pausedAt, samples.last())

                // ③ 恢复播放：立即继续推进，不需要额外触发。
                onMain { client.dispatch(PlaybackSessionCommand.Play) }
                assertTrue(
                    "the display position did not resume with playback",
                    await { samples.last() > pausedAt },
                )
                assertTrue(
                    "the resumed display position does not track the live position" +
                        " (display=${samples.last()} live=${client.currentPositionMillis()})",
                    await { kotlin.math.abs(samples.last() - client.currentPositionMillis()) <= DISPLAY_POSITION_TICK_MILLIS * 2 },
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /** 决策点仍然取实时值：展示层推进**不得**改变"决策问实时位置"这条规矩。 */
    @Test
    fun the_live_position_stays_independent_of_the_display_projection() {
        withRuntimeClient { client ->
            onMain { client.dispatch(PlaybackSessionCommand.Pause) }
            assertTrue("pause never took effect", await { client.snapshot.value.phase is PlaybackPhase.Paused })

            // 暂停期间快照位置固定：实时位置与快照位置一致（没有播放推进）。
            val live = client.currentPositionMillis()
            val snapshotPosition = client.snapshot.value.timeline.positionMillis
            assertEquals("paused: live and snapshot positions must agree", snapshotPosition, live)

            // seek 之后决策点立刻看到新位置，不需要等任何 tick。
            val target = live + 5_000
            onMain { client.dispatch(PlaybackSessionCommand.Seek(target)) }
            assertTrue(
                "the live position did not follow the seek (live=${client.currentPositionMillis()} target=$target)",
                await { kotlin.math.abs(client.currentPositionMillis() - target) <= SEEK_TOLERANCE_MILLIS },
            )
        }
    }

    // ---- 真机装配 ----

    private fun <T> onMain(block: () -> T): T = runOnMainSync(block)

    private fun <T> runOnMainSync(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * 真实引擎 + runtime + 播放器，外面套一层最小的 [PlaybackSessionClient]
     * （`PlayerViewModel` 消费的就是这个契约；`MediaController` 那一跳是直通转发）。
     */
    private fun withRuntimeClient(block: (RuntimeClient) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav = writeSilentWav(context)
        val instrumented = InstrumentationRegistry.getInstrumentation()
        val registry = SourceHandleRegistry().apply {
            put(ACCESS_HANDLE, Uri.fromFile(wav).toString())
        }
        val player = onMain { ExoPlayer.Builder(context).build() }
        val dispatchers = object : AppDispatchers {
            override val main = Dispatchers.Main.immediate
            override val io = DefaultAppDispatchers.io
            override val default = DefaultAppDispatchers.default
        }
        val engine = ServicePlaybackEngine(
            player = player,
            sourceRegistry = registry,
            dispatchers = dispatchers,
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val runtime = PlaybackSessionRuntime(
            resolver = PlaybackSourceResolver { Result.success(sourceHandle()) },
            engine = engine,
            dispatchers = dispatchers,
            sessionId = PlaybackSessionId("display-position-test"),
            elapsedTimeSource = ElapsedTimeSource(SystemClock::elapsedRealtime),
        )
        val client = RuntimeClient(runtime)
        try {
            runtime.dispatch(
                PlaybackSessionCommand.Open(
                    PlaybackOpenRequest(
                        sessionId = PlaybackSessionId("display-position-test"),
                        mediaId = MediaItemId("display-position"),
                        sourceContext = PlaybackSourceContext.HOME,
                    ),
                ),
            )
            runtime.dispatch(PlaybackSessionCommand.Play)
            assertTrue(
                "playback never started: phase=${runtime.snapshot.value.phase}" +
                    " playerState=${onMain { player.playbackState }} playWhenReady=${onMain { player.playWhenReady }}" +
                    " error=${onMain { player.playerError }}",
                await { onMain { player.isPlaying } },
            )
            block(client)
        } finally {
            instrumented.runOnMainSync {
                runtime.close()
                player.release()
            }
            wav.delete()
        }
    }

    private class RuntimeClient(private val runtime: PlaybackSessionRuntime) : PlaybackSessionClient {
        override val snapshot: StateFlow<PlaybackSessionSnapshot> = runtime.snapshot
        override val events: Flow<PlaybackSessionEvent> = runtime.events
        override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle = runtime.dispatch(command)
        override fun currentPositionMillis(): Long = runtime.currentPositionMillis()
    }

    private fun sourceHandle() = PlaybackSourceHandle(
        mediaId = MediaItemId("display-position"),
        locationId = MediaLocationId("display-position"),
        accessHandleId = SourceAccessHandleId(ACCESS_HANDLE),
        displayName = "display-position.wav",
        durationMillis = DURATION_MILLIS,
    )

    private fun await(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    /** 自造一段单声道静音 WAV（8bit PCM，全 128），不依赖任何外部素材。 */
    private fun writeSilentWav(context: Context): File {
        val file = File(context.cacheDir, "display-position-test.wav")
        val sampleRate = 22_050
        val channels = 1
        val bitsPerSample = 8
        val dataSize = (sampleRate * DURATION_MILLIS / 1_000).toInt()
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLe(36 + dataSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLe(16))
            out.write(shortLe(1))
            out.write(shortLe(channels))
            out.write(intLe(sampleRate))
            out.write(intLe(sampleRate * channels * bitsPerSample / 8))
            out.write(shortLe(channels * bitsPerSample / 8))
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

    private companion object {
        const val ACCESS_HANDLE = "display-position"
        const val DURATION_MILLIS = 120_000L
        const val TIMEOUT_MILLIS = 20_000L
        const val SEEK_TOLERANCE_MILLIS = 300L
    }
}
