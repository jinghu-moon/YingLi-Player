package seeyuer.yingli.player.data.processing.transcode

import androidx.media3.transformer.TransformationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.transcode.TranscodeChangeCode

/**
 * G6 的核心测试：Media3 请求了不支持的输出编码格式时会静默回退，
 * 而这是我们**唯一**能发现它的信号来源。
 *
 * 之所以能在 JVM 上测：`TransformationRequest` 是纯数据类（公开 final 字段 + 无 `Context` 的 Builder），
 * 所以这里用真实的 Media3 类型直接构造 `onFallbackApplied` 的两个参数，
 * 而不是用 mock——用 mock 就只是在测自己写的 stub。
 */
class Media3FallbackMappingTest {

    @Test
    fun `video codec silently replaced is reported`() {
        // 请求 HEVC（阶段 0 已实测：HEVC 编码器不是平台必需项，设备可能没有），
        // 库回退到 H.264 并报告成功——这就是「要 HEVC 拿到 H.264」。
        val changes = Media3FallbackMapping.changes(
            original = request(video = "video/hevc"),
            fallback = request(video = "video/avc"),
        )

        assertEquals(setOf(TranscodeChangeCode.VIDEO_CODEC_FALLBACK), changes)
    }

    @Test
    fun `audio codec silently replaced is reported`() {
        val changes = Media3FallbackMapping.changes(
            original = request(audio = "audio/amr-wb"),
            fallback = request(audio = "audio/mp4a-latm"),
        )

        assertEquals(setOf(TranscodeChangeCode.AUDIO_CODEC_FALLBACK), changes)
    }

    @Test
    fun `both replaced reports both`() {
        val changes = Media3FallbackMapping.changes(
            original = request(video = "video/hevc", audio = "audio/amr-wb"),
            fallback = request(video = "video/avc", audio = "audio/mp4a-latm"),
        )

        assertEquals(
            setOf(TranscodeChangeCode.VIDEO_CODEC_FALLBACK, TranscodeChangeCode.AUDIO_CODEC_FALLBACK),
            changes,
        )
    }

    @Test
    fun `unchanged request is not a fallback`() {
        assertTrue(
            Media3FallbackMapping.changes(
                original = request(video = "video/avc", audio = "audio/mp4a-latm"),
                fallback = request(video = "video/avc", audio = "audio/mp4a-latm"),
            ).isEmpty(),
        )
    }

    @Test
    fun `inferred mime type is not a fallback`() {
        // null 表示「跟随输入推断」（TransformationRequest 的默认值）：
        // 既然没有对用户承诺过编码格式，换成什么都不算违约。
        assertTrue(
            Media3FallbackMapping.changes(
                original = request(video = null, audio = null),
                fallback = request(video = "video/avc", audio = "audio/mp4a-latm"),
            ).isEmpty(),
        )
    }

    @Test
    fun `video fallback alone does not claim an audio fallback`() {
        val changes = Media3FallbackMapping.changes(
            original = request(video = "video/hevc", audio = "audio/mp4a-latm"),
            fallback = request(video = "video/avc", audio = "audio/mp4a-latm"),
        )

        assertEquals(setOf(TranscodeChangeCode.VIDEO_CODEC_FALLBACK), changes)
    }

    private fun request(video: String? = null, audio: String? = null): TransformationRequest =
        TransformationRequest.Builder()
            .setVideoMimeType(video)
            .setAudioMimeType(audio)
            .build()
}
