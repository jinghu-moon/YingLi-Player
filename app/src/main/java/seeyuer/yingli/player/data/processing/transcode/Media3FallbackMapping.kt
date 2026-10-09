package seeyuer.yingli.player.data.processing.transcode

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.TransformationRequest
import seeyuer.yingli.player.domain.transcode.TranscodeChangeCode

/**
 * 把 Media3 的「回退通知」翻译成领域层的后果码（G6 的前半）。
 *
 * Media3 Transformer 在请求了它不支持的输出编码格式时，**不会失败**：
 * 它会把请求换成自己支持的格式继续做完，然后通过
 * `Transformer.Listener.onFallbackApplied(composition, original, fallback)` 通知调用方。
 * 不覆写该回调，调用方就会「请求 HEVC、拿到 H.264、并被报告成功」。
 *
 * 这个对象只做纯翻译，因此可以在 JVM 单元测试里用真实的 [TransformationRequest] 直接验证，
 * 不需要设备、不需要 `Context`、不需要跑一次真正的转码。
 */
internal object Media3FallbackMapping {

    /**
     * @param original 用户/规划器**请求**的转换，即 `onFallbackApplied` 的第二个参数。
     * @param fallback 库**实际采用**的转换，即 `onFallbackApplied` 的第三个参数。
     */
    @OptIn(UnstableApi::class)
    fun changes(original: TransformationRequest, fallback: TransformationRequest): Set<TranscodeChangeCode> =
        buildSet {
            // 只有「明确请求过、并且被换成了别的」才算回退。
            // null 表示「跟随输入推断」（TransformationRequest.Builder 的默认值），
            // 此时并没有对用户做出任何编码格式承诺，换成什么都不是回退。
            if (original.videoMimeType != null && original.videoMimeType != fallback.videoMimeType) {
                add(TranscodeChangeCode.VIDEO_CODEC_FALLBACK)
            }
            if (original.audioMimeType != null && original.audioMimeType != fallback.audioMimeType) {
                add(TranscodeChangeCode.AUDIO_CODEC_FALLBACK)
            }
        }
}
