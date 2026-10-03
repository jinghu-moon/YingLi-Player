package seeyuer.yingli.player.app.playback

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.PlaybackSourceResolver
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId

/**
 * **缺陷 2 的真机回归**：按正常用户流程设完 A、B 之后，循环必须立刻开始回跳并累加计数。
 *
 * 为什么要跑整条 runtime + engine 链路，而不是像 `AbBoundaryLoopInstrumentedTest` 那样直接驱动引擎：
 * 缺陷本身出在**两者的交界处** —— runtime 把 A/B 设全之后没有把播放位置带回 A，引擎的边界检测
 * 因此在配置那一刻就认为"已经越过 B"、永不武装。只驱动引擎看不到这条缝。
 *
 * 现场完全照用户流程构造：播放推进到区间里 → 设 A（在当前位置）→ 播放**继续推进**到 B 之后的位置
 * → 设 B（就设在当前位置上）。用户正是这样设的，而修复前 60 秒内 0 次回跳。
 */
@RunWith(AndroidJUnit4::class)
class AbLoopActivationInstrumentedTest {
    @Test
    fun setting_both_points_starts_the_loop_immediately_and_advances_the_count() {
        withRuntime { runtime, player ->
            // 播放到区间里，让"设 A"落在一个明确的实时位置上。
            seekOnMain(player, A_MILLIS)
            assertTrue("player never reached point A: $player", await { onMain { player.currentPosition } >= A_MILLIS })

            assertEquals(AbLoopCommandOutcome.Applied, setPointOnMain(runtime, AbPoint.A))
            val a = requireNotNull(runtime.abLoop.value.state.pointA)

            // 播放**继续推进**过 B：这正是"用户把 B 设在当前位置上"的那一刻。
            seekOnMain(player, B_MILLIS + 200)
            assertTrue("player never reached past point B", await { onMain { player.currentPosition } > B_MILLIS })

            assertEquals(AbLoopCommandOutcome.Applied, setPointOnMain(runtime, AbPoint.B))
            val configured = runtime.abLoop.value.state
            assertEquals(a, configured.pointA)
            assertTrue("point B was not set: $configured", requireNotNull(configured.pointB) > a)

            // 激活的定义：位置被带回 A（回到区间起点）。
            assertTrue(
                "activation did not bring the position back to A: pos=${onMain { player.currentPosition }} a=$a",
                await { onMain { player.currentPosition } <= a + 250 },
            )

            // 关键断言：循环**必须**在没有任何人工 seek 的情况下自己回跳并累加计数。
            assertTrue(
                "the loop never started on its own: loopCount=${runtime.abLoop.value.loopCount}" +
                    " pos=${onMain { player.currentPosition }} state=${runtime.snapshot.value.phase}",
                await { runtime.abLoop.value.loopCount >= 1 },
            )
            val first = runtime.abLoop.value.loopCount

            // 而且它是**持续**的：第二次循环同样要自然发生。
            assertTrue(
                "the loop stopped after the first iteration: loopCount=$first",
                await { runtime.abLoop.value.loopCount > first },
            )
        }
    }

    // ---- 真机装配 ----

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun setPointOnMain(runtime: PlaybackSessionRuntime, point: AbPoint): AbLoopCommandOutcome =
        onMain { runBlocking { runtime.setPoint(point) } }

    private fun seekOnMain(player: ExoPlayer, positionMillis: Long) {
        onMain { player.seekTo(positionMillis) }
    }

    /** 装配真实的引擎 + runtime + 播放器，并用同一处负责清理。 */
    private fun withRuntime(block: (PlaybackSessionRuntime, ExoPlayer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav = writeSilentWav(context)
        val instrumented = InstrumentationRegistry.getInstrumentation()
        val registry = SourceHandleRegistry().apply {
            put(ACCESS_HANDLE, Uri.fromFile(wav).toString())
        }
        val player = onMain { ExoPlayer.Builder(context).build() }
        // 边界检测与位置读取都必须落在播放器的应用线程（主线程）上；`Dispatchers.Main.immediate`
        // 让"已经在主线程时直接执行"，测试从 runOnMainSync 里驱动时不会排到队列后面。
        val dispatchers = object : AppDispatchers {
            override val main = kotlinx.coroutines.Dispatchers.Main.immediate
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
            sessionId = PlaybackSessionId("ab-activation-test"),
            elapsedTimeSource = ElapsedTimeSource(SystemClock::elapsedRealtime),
        )
        try {
            // 走 runtime 的正常打开路径：它会 prepare 并 play，与用户点开一个视频一致。
            // 命令是异步的（解析来源跑在 IO 上），因此**不在** runOnMainSync 里等它 ——
            // 那样会把主线程占住，runtime 切回主线程 prepare 的这一步永远排不上。
            runtime.dispatch(
                seeyuer.yingli.player.domain.playback.PlaybackSessionCommand.Open(
                    PlaybackOpenRequest(
                        sessionId = PlaybackSessionId("ab-activation-test"),
                        mediaId = MediaItemId("ab-activation"),
                        sourceContext = PlaybackSourceContext.HOME,
                    ),
                ),
            )
            assertTrue(
                "playback never reached READY: phase=${runtime.snapshot.value.phase}" +
                    " playerState=${onMain { player.playbackState }}" +
                    " item=${onMain { player.currentMediaItem?.mediaId }} error=${onMain { player.playerError }}",
                await { onMain { player.playbackState } == Player.STATE_READY },
            )
            // 真机流程里播放页在打开之后会下发播放（`PlaybackSessionCommand.Play`）：
            // runtime 只在收到 Play 之后才会在准备完成时补一次 `engine.play()`。
            runtime.dispatch(seeyuer.yingli.player.domain.playback.PlaybackSessionCommand.Play)
            assertTrue(
                "playback never started: phase=${runtime.snapshot.value.phase}" +
                    " playerState=${onMain { player.playbackState }} pos=${onMain { player.currentPosition }}" +
                    " playWhenReady=${onMain { player.playWhenReady }} mediaItem=${onMain { player.currentMediaItem }}" +
                    " duration=${onMain { player.duration }} buffered=${onMain { player.bufferedPosition }}" +
                    " error=${onMain { player.playerError }}",
                await { onMain { player.isPlaying } },
            )
            block(runtime, player)
        } finally {
            instrumented.runOnMainSync {
                runtime.close()
                player.release()
            }
            wav.delete()
        }
    }

    private fun sourceHandle() = PlaybackSourceHandle(
        mediaId = MediaItemId("ab-activation"),
        locationId = MediaLocationId("ab-activation"),
        accessHandleId = SourceAccessHandleId(ACCESS_HANDLE),
        displayName = "ab-activation.wav",
        durationMillis = DURATION_MILLIS,
    )

    private fun await(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    /**
     * 自造一段单声道静音 WAV（8bit PCM，全 128）。选 WAV 是因为它能在测试里用几十行字节拼出来，
     * 不依赖任何外部素材，也不需要编解码器生成视频轨。
     */
    private fun writeSilentWav(context: Context): File {
        val file = File(context.cacheDir, "ab-activation-test.wav")
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
        const val ACCESS_HANDLE = "ab-activation"
        /** 区间起点：播放先走到这里，再"设 A"。 */
        const val A_MILLIS = 3_000L
        /** 区间终点：A 与 B 相隔 1 秒，循环一圈 1 秒，便于在超时内观察到多次回跳。 */
        const val B_MILLIS = 4_000L
        const val DURATION_MILLIS = 60_000L
        const val TIMEOUT_MILLIS = 20_000L
    }
}
