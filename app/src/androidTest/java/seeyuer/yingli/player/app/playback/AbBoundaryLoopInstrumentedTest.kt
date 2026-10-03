package seeyuer.yingli.player.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.EngineAbLoop
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.PlaybackEngineEvent
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId

/**
 * 引擎侧 AB 边界检测的真机（instrumented）验证：**真的**跑一个 `ExoPlayer` 播一段静音 WAV。
 *
 * 为什么需要它：纯状态机（`AbBoundarySessionTest`）能证明判定规则，但证明不了"接在真实播放器上
 * 还成立" —— 定时消息跑在主线程 Looper 上、位置来自 `ExoPlayer.currentPosition`、
 * 位置回退来自 `Player.Listener.onPositionDiscontinuity`，这三件事都只有在真设备上才算验证过。
 *
 * 素材：测试自造的 22.05kHz 单声道静音 WAV（4 秒 / 8KB）。选 WAV 而不是 MP4，是因为它可以在测试里
 * 用几十行字节拼出来，不需要编解码器生成视频轨道，也不依赖任何外部文件。
 */
@RunWith(AndroidJUnit4::class)
class AbBoundaryLoopInstrumentedTest {
    @Test
    fun engine_reports_a_single_natural_boundary_and_seeks_back_to_a() {
        withEngine { engine, player, events ->
            onMain {
                runBlocking { engine.prepare(sourceHandle(), A_MILLIS) }
                // 会话 runtime 在 prepare 之后就会 `play()`；测试里直接驱动引擎，所以要显式补上。
                engine.play()
            }
            assertTrue(playbackDiagnostics(engine, player), awaitState(engine) { it is EngineState.Playing })

            onMain {
                engine.configureAbLoop(EngineAbLoop(generation = 1, pointAMillis = A_MILLIS, pointBMillis = B_MILLIS))
            }
            assertTrue("no natural boundary event was reported", await { events.any { it is PlaybackEngineEvent.AbBoundaryReached } })

            val first = events.first { it is PlaybackEngineEvent.AbBoundaryReached } as PlaybackEngineEvent.AbBoundaryReached
            assertEquals(1L, first.generation)
            assertTrue("boundary reported below B: ${first.positionMillis}", first.positionMillis >= B_MILLIS)

            // 回跳发生了：位置回到 A 附近（允许一点回绕余量）。
            assertTrue("player did not seek back to A", await { onMain { player.currentPosition } <= A_MILLIS + 250 })
        }
    }

    @Test
    fun configuring_a_loop_whose_b_is_already_behind_the_position_reports_nothing() {
        withEngine { engine, player, events ->
            onMain {
                runBlocking { engine.prepare(sourceHandle(), 0) }
                engine.play()
            }
            assertTrue(playbackDiagnostics(engine, player), awaitState(engine) { it is EngineState.Playing })
            // 用户直接把位置拖到 B 之后，再配置一个 B 在身后的区间：
            // 这是"用户 seek 造成的越界"，**不得**产生自然边界事件。
            onMain { player.seekTo(B_MILLIS + 200) }
            assertTrue(await { onMain { player.currentPosition } > B_MILLIS })
            onMain {
                engine.configureAbLoop(EngineAbLoop(generation = 5, pointAMillis = A_MILLIS, pointBMillis = B_MILLIS))
            }

            // 播到素材结束（B 之后不再有"从 B 之前跨过 B"的机会）：不应有任何自然边界事件。
            assertTrue(awaitState(engine) { it is EngineState.Ended })
            assertTrue(
                "user seek past B must not count as a natural boundary: $events",
                events.none { it is PlaybackEngineEvent.AbBoundaryReached },
            )
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun playbackDiagnostics(engine: ServicePlaybackEngine, player: ExoPlayer): String = buildString {
        append("player never started")
        append(" engineState=").append(onMain { engine.state.value })
        append(" playbackState=").append(onMain { player.playbackState })
        append(" position=").append(onMain { player.currentPosition })
        append(" buffered=").append(onMain { player.bufferedPosition })
        append(" duration=").append(onMain { player.duration })
        append(" playWhenReady=").append(onMain { player.playWhenReady })
        append(" error=").append(onMain { player.playerError })
    }

    /** 装配一个真实的引擎 + 播放器 + 事件收集器，并把清理交给同一处。 */
    private fun withEngine(block: (ServicePlaybackEngine, ExoPlayer, MutableList<PlaybackEngineEvent>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav = writeSilentWav(context)
        val instrumented = InstrumentationRegistry.getInstrumentation()
        val registry = SourceHandleRegistry().apply {
            put(ACCESS_HANDLE, Uri.fromFile(wav).toString())
        }
        val activePlayer = onMain { ExoPlayer.Builder(context).build() }
        val engine = ServicePlaybackEngine(
            player = activePlayer,
            sourceRegistry = registry,
            dispatchers = DefaultAppDispatchers,
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val events = mutableListOf<PlaybackEngineEvent>()
        val collector = CoroutineScope(Dispatchers.Main.immediate).launch {
            engine.events.collect { events += it }
        }
        try {
            block(engine, activePlayer, events)
        } finally {
            instrumented.runOnMainSync {
                collector.cancel()
                engine.release()
                activePlayer.release()
            }
            wav.delete()
        }
    }

    private fun sourceHandle() = PlaybackSourceHandle(
        mediaId = MediaItemId("ab-loop-test"),
        locationId = MediaLocationId("ab-loop-test"),
        accessHandleId = SourceAccessHandleId(ACCESS_HANDLE),
        displayName = "ab-loop-test.wav",
        durationMillis = DURATION_MILLIS,
    )

    private fun awaitState(engine: ServicePlaybackEngine, predicate: (EngineState) -> Boolean): Boolean =
        await { predicate(engine.state.value) }

    private fun await(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    /**
     * 自造一段单声道静音 WAV。静音数据全 128（8bit PCM 的中点），
     * 样本数按采样率 × 时长算，便于精确控制时长。
     */
    private fun writeSilentWav(context: Context): File {
        val file = File(context.cacheDir, "ab-loop-test.wav")
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
        const val ACCESS_HANDLE = "ab-loop-test"
        const val A_MILLIS = 1_000L
        const val B_MILLIS = 2_000L
        const val DURATION_MILLIS = 4_000L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
