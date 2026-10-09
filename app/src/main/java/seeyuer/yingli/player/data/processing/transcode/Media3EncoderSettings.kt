package seeyuer.yingli.player.data.processing.transcode

import android.content.Context
import android.media.MediaCodecInfo
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import seeyuer.yingli.player.domain.processing.ProcessingChangeCode
import seeyuer.yingli.player.domain.processing.ProcessingPlan

/**
 * G1 的唯一接线点：把 `OutputTarget` 的目标码率送进 Media3 的编码器设置。
 *
 * 修复前 `Media3ProcessingEngine` 只设了容器与 mime，**没有设码率**，实际码率完全由
 * `DefaultEncoderFactory.getSuggestedBitrate()` 按设备能力推导，于是三档预设在码率维度上
 * 完全等价。真机实测（`docs/architecture/Organizing-Page-Function-Design.md` §20.1.2）：
 * `compatible_mp4` 与 `balanced_mp4` 输出字节数完全相同（473 598），
 * 请求 2.5 Mbps 的 `space_saver_mp4` 反而输出 505 710。
 *
 * 码率模式固定为 VBR，两条依据都来自真机实测（同文档 §20.1.5）：
 * 全分辨率硬件编码器只报 `VBR` / `CBR`，`BITRATE_MODE_CQ` 只存在于尺寸上限 128–512 的编码器上，
 * 无法用于压缩预设；在 VBR 与 CBR 之间选 VBR，因为压缩预设承诺的是「同等体积下更好的质量」，
 * 而不是严格恒定的瞬时码率。`VideoEncoderSettings.BitrateMode` 的 `@IntDef` 也只有这两个取值，
 * 与实测一致。
 *
 * 注意 `AudioEncoderSettings.setBitrate` 的官方说明是「编码器可以忽略请求的码率以提升质量」，
 * 因此接线只保证**请求已下发**，不保证**结果精确等于请求**——后者由 §20.2 式的真机测量回答，
 * 而不是由单元测试断言。
 */
@OptIn(UnstableApi::class)
internal object Media3EncoderSettings {
    fun video(plan: ProcessingPlan): VideoEncoderSettings {
        val builder = VideoEncoderSettings.Builder()
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        // `OutputTarget` 的码率可空（设计稿 §4.2）：为空表示「只换容器/尺寸，不约束码率」。
        // 此时**不下发**码率，交给库按设备能力推导，而不是把 0 当成用户请求的真值下发。
        plan.target.videoBitrate?.let(builder::setBitrate)
        return builder.build()
    }

    fun audio(plan: ProcessingPlan): AudioEncoderSettings {
        val builder = AudioEncoderSettings.Builder()
        plan.target.audioBitrate?.let(builder::setBitrate)
        return builder.build()
    }

    fun encoderFactory(context: Context, plan: ProcessingPlan): Codec.EncoderFactory =
        DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(video(plan))
            .setRequestedAudioEncoderSettings(audio(plan))
            .build()

    /**
     * G2 的唯一接线点：把「计划是否要把 HDR 转成 SDR」映射成 [Composition.HdrMode]。
     *
     * 修复前 `Media3ProcessingEngine` 见到 `HDR_TO_SDR` 就直接
     * `Failed("HDR_TONE_MAPPING_UNAVAILABLE")`，而 `supportsHdr` 又硬编码为 `false`，
     * 于是每一个 HDR 源都必然走进那条失败分支——HDR 视频完全无法转码，与事实（tone mapping
     * 自 API 29 起可用，本项目 minSdk 31）矛盾。
     *
     * 三种候选模式的取舍**全部来自 Media3 官方 tone mapping 指南**
     * （`https://developer.android.com/media/media3/transformer/tone-mapping`）：
     * - `HDR_MODE_KEEP_HDR` 是**默认值**，「如果设备不支持 HDR 格式，Transformer 会自动改用
     *   `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`」。
     * - `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC` 画质最好，但「只在 API 31+ 的**部分**
     *   设备上、以及 API 33+ 支持 HDR 拍摄的设备上受支持；**不支持时 `Transformer` 抛
     *   `ExportException`**」。本项目的 `supportsHdr` 探测回答的是「这个编码器能不能**保留** HDR」，
     *   不是「这个设备能不能用 MediaCodec **做色调映射**」——后者没有可用的探测途径，
     *   因此这里**一律不请求它**，把「未知」变成硬失败是拿用户的编码时间去试雷。
     * - `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` 自 API 29 起可用、设备覆盖面更广、
     *   结果更一致，代价是与 MediaCodec 路径相比画质可能有轻微差异。
     * - `HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR` 支持面最广，但画面会发灰、
     *   可能显示不正确——那是把「能跑通」当成「做对了」，不用。
     *
     * 既然计划里出现 `HDR_TO_SDR`（一条**用户已确认**的后果）就已经承诺了输出是 SDR，
     * 就显式请求 OpenGL 色调映射，而不是依赖库「设备不支持时才回退」的内部启发式：
     * 后者会让「计划承诺的后果」与「实际输出」之间多一个不可观测的环节。
     *
     * `KEEP_HDR` 分支本来就等于默认值，仍然显式写出来——两个分支是同一个产品决策的两面，
     * 写成显式分支后读者不必去查默认值是多少。
     */
    fun hdrMode(plan: ProcessingPlan): Int =
        if (plan.changes.any { it.code == ProcessingChangeCode.HDR_TO_SDR }) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
            Composition.HDR_MODE_KEEP_HDR
        }
}
