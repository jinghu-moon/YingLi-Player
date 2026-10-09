package seeyuer.yingli.player.data.processing.transcode

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 编码前的最后一道可达性校验（G6 的后半）。
 *
 * G6 的修法要求**两件事都做**：① 覆写 `onFallbackApplied` 把回退暴露给用户
 * （见 [Media3FallbackMapping]）；② 在真正开始编码之前，把「这台设备根本做不到的组合」
 * 直接挡掉。第 ② 件事的意义是**省掉一次注定作废的整段编码**——一个两小时的视频
 * 编码到一半才发现没有对应编码器，代价是几分钟到几十分钟的 CPU 与电量。
 *
 * 这里用 [MediaCodecList.findEncoderForFormat] 而不是「按 mime 字符串扫一遍列表」：
 * 它会把分辨率/帧率对编码器的约束也一并算进去，与框架挑选编码器的口径一致。
 *
 * 注意这是**尽力而为的前置校验**，不是保证：[androidx.media3.transformer.DefaultEncoderFactory]
 * 仍然可能因为 profile/level、tier、并发实例数等原因回退，所以运行时那条路径不能删。
 */
internal object Media3CodecAvailability {

    private val codecList: MediaCodecList by lazy { MediaCodecList(MediaCodecList.ALL_CODECS) }

    fun missingVideoEncoder(mimeType: String, width: Int, height: Int): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        codecList.findEncoderForFormat(format) == null
    }.getOrDefault(false)

    fun missingAudioEncoder(
        mimeType: String,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channelCount: Int = DEFAULT_CHANNEL_COUNT,
    ): Boolean = runCatching {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
        codecList.findEncoderForFormat(format) == null
    }.getOrDefault(false)

    /** 找不到编码器时返回错误码，找得到返回 `null`。 */
    fun videoEncoderErrorCode(mimeType: String, width: Int, height: Int): String? =
        if (missingVideoEncoder(mimeType, width, height)) VideoCodecErrorCode else null

    fun audioEncoderErrorCode(mimeType: String): String? =
        if (missingAudioEncoder(mimeType)) AudioCodecErrorCode else null

    /** 与 [android.media.MediaCodecInfo.CodecCapabilities] 无关的输入格式，探测用固定值即可。 */
    private const val DEFAULT_SAMPLE_RATE = 44_100
    private const val DEFAULT_CHANNEL_COUNT = 2

    /**
     * 与 `DefaultTranscodePlanner` 的拒绝码保持一致——同一个原因（没有可用编码器）
     * 不应该因为「在哪一层发现」而出现两个不同的码。
     */
    private const val VideoCodecErrorCode = "VIDEO_ENCODER_UNAVAILABLE"
    private const val AudioCodecErrorCode = "AUDIO_ENCODER_UNAVAILABLE"
}
