package seeyuer.yingli.player.domain.processing

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

/**
 * 统一的任务计划模型。
 *
 * 这里只有**一个**任务实体：`ProcessingPlan`。无损切片、压缩、格式转换、AB 循环区间导出
 * 四个产品入口都由它描述，差别只在 planner 选出的 [ProcessingPlan.operation]。
 * 「压缩」与「格式转换」不是两个功能，而是同一个目标（容器 × codec × 分辨率 × 码率）
 * 的两个目标函数（设计稿 §4.1）。
 */

@JvmInline
value class OutputTargetId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9_-]{1,48}"))) }
}

/** 一个任务的动作分类。由 planner 判定，不由调用方指定。 */
enum class ProcessingOperation {
    /** 不重新编码：只是把源轨道搬进目标容器。 */
    REMUX,

    /** 需要重新编码：目标画质低于源，或目标 codec 与源不同。 */
    TRANSCODE,
}

/**
 * 一个任务的目标描述。
 *
 * [id] 是目标的稳定标识，用于持久化的输出文件名与队列策略编码。
 * 设计稿 §4.2 的签名里没有它，但 §6.1 的预设表给出了 ID 列（`compatible_mp4` 等）
 * 并要求保留输出名 `"${baseName}_${preset.id.value}.mp4"`——没有 ID 就无法重建那个名字。
 */
data class OutputTarget(
    val id: OutputTargetId,
    val containerMimeType: String,
    val videoCodecMimeType: String?,
    val audioCodecMimeType: String?,
    val maximumLongEdge: Int? = null,
    val videoBitrate: Int? = null,
    val audioBitrate: Int? = null,
    /**
     * 调用方是否明确要求「精确到帧」。
     *
     * 这是**用户意图**，不是质量参数：源已经是目标 codec、也没有要求改分辨率/码率时
     * （`REMUX` 的充要条件全部成立），用户仍可能要求按给定区间精确重编码，
     * 因为无损 remux 只能落在关键帧上（`SEEK_TO_PREVIOUS_SYNC`，实际起点必然 ≤ 请求起点）。
     * 设计稿 §4.2 的判定表假定「目标三元组 + 质量参数」足以推出动作，唯独这一条推不出来：
     * 「快速（无损）还是精确（重编码）」在 REX-Player 参考实现里也是显式的用户二选一，
     * 不做默认（设计稿 §9）。故把它显式地放在目标上。
     */
    val frameAccurateCut: Boolean = false,
) {
    init {
        require(containerMimeType.contains('/'))
        require(videoCodecMimeType == null || videoCodecMimeType.startsWith("video/"))
        require(audioCodecMimeType == null || audioCodecMimeType.startsWith("audio/"))
        require(maximumLongEdge == null || (maximumLongEdge > 0 && maximumLongEdge % 2 == 0))
        require(videoBitrate == null || videoBitrate > 0)
        require(audioBitrate == null || audioBitrate > 0)
    }

    /** 是否存在会强制重新编码的质量参数。为真时动作必然是 [ProcessingOperation.TRANSCODE]。 */
    val hasQualityParameters: Boolean
        get() = maximumLongEdge != null || videoBitrate != null || audioBitrate != null

    /**
     * 输出文件的扩展名。
     *
     * 只覆盖设计稿 §4.4 可达矩阵里的容器；其它容器在本项目里不可达，
     * 给 `bin` 是为了让不可达的目标在文件名上就看得出来，而不是伪装成 mp4。
     */
    val fileExtension: String
        get() = when (containerMimeType) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "audio/mp4" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/aac" -> "aac"
            else -> "bin"
        }
}

/**
 * 命名目标常量。
 *
 * 原 `OutputTarget` 的三档预设折叠到这里：不再有独立的预设实体，也不再有 `version`
 * 字段（没有持久化契约需要版本号，版本号是凭空增加的实体）（设计稿 §6.1）。
 */
object OutputTargets {
    val Compatible = OutputTarget(
        id = OutputTargetId("compatible_mp4"),
        containerMimeType = "video/mp4",
        videoCodecMimeType = "video/avc",
        audioCodecMimeType = "audio/mp4a-latm",
        maximumLongEdge = 1_920,
        videoBitrate = 8_000_000,
        audioBitrate = 192_000,
    )

    val Balanced = OutputTarget(
        id = OutputTargetId("balanced_mp4"),
        containerMimeType = "video/mp4",
        videoCodecMimeType = "video/avc",
        audioCodecMimeType = "audio/mp4a-latm",
        maximumLongEdge = 1_920,
        videoBitrate = 5_000_000,
        audioBitrate = 160_000,
    )

    val SpaceSaver = OutputTarget(
        id = OutputTargetId("space_saver_mp4"),
        containerMimeType = "video/mp4",
        videoCodecMimeType = "video/avc",
        audioCodecMimeType = "audio/mp4a-latm",
        maximumLongEdge = 1_280,
        videoBitrate = 2_500_000,
        audioBitrate = 128_000,
    )

    val all: List<OutputTarget> = listOf(Compatible, Balanced, SpaceSaver)
}

enum class ProcessingChangeCode {
    RESOLUTION_REDUCED,
    VIDEO_CODEC_CHANGED,
    AUDIO_CODEC_CHANGED,
    EXTRA_AUDIO_TRACKS_REMOVED,
    SUBTITLES_NOT_EMBEDDED,
    HDR_TO_SDR,
    FRAME_RATE_CAPPED,

    /**
     * 请求的视频编码格式被库**静默替换**成另一个（G6）。
     *
     * Media3 Transformer 对 `setVideoMimeType` 支持的取值只有 H.263 / H.264 / H.265 / MPEG-4，
     * 请求这些之外（或设备没有对应编码器）时它会**回退到支持的格式并报告成功**，
     * 且**只通过 `Transformer.Listener.onFallbackApplied` 通知**。
     * 不覆写该回调就会「用户要 HEVC、实际拿到 AVC 且被告知成功」——
     * 与 Q498「不静默丢轨」、Q500「不静默降质」是同一类错误。
     */
    VIDEO_CODEC_FALLBACK,

    /** 请求的音频编码格式被库静默替换（G6），语义同 [VIDEO_CODEC_FALLBACK]。 */
    AUDIO_CODEC_FALLBACK,
}

/**
 * 回退类 change 一律需要确认：它们是「你已经点了开始、结果却不是你点的那件事」，
 * 不能通过第一次提交时的那次统一确认糊过去。
 */
fun ProcessingChangeCode.requiresConfirmation(): Boolean = when (this) {
    ProcessingChangeCode.EXTRA_AUDIO_TRACKS_REMOVED,
    ProcessingChangeCode.SUBTITLES_NOT_EMBEDDED,
    ProcessingChangeCode.HDR_TO_SDR,
    ProcessingChangeCode.FRAME_RATE_CAPPED,
    ProcessingChangeCode.VIDEO_CODEC_FALLBACK,
    ProcessingChangeCode.AUDIO_CODEC_FALLBACK,
    -> true

    ProcessingChangeCode.RESOLUTION_REDUCED,
    ProcessingChangeCode.VIDEO_CODEC_CHANGED,
    ProcessingChangeCode.AUDIO_CODEC_CHANGED,
    -> false
}

/**
 * 一项已确定的处理后果。
 *
 * `requiresConfirmation` **不再作为字段存在**：它完全由 [code] 决定（见 [ProcessingChangeCode.requiresConfirmation]）。
 * 曾经两者并存（字段 + 枚举），也就意味着同一个事实有两个真源，可以互相矛盾；
 * 而「哪些后果需要用户确认」是产品语义，只应有一个出处。开发期允许破坏性收敛。
 */
data class ProcessingChange(
    val code: ProcessingChangeCode,
) {
    val requiresConfirmation: Boolean get() = code.requiresConfirmation()
}

/**
 * 要处理的源时间区间（毫秒，相对源起点）。
 *
 * 为 `null` 表示整文件。切片与 AB 循环区间导出用它；整文件 remux/转码不用它，
 * 这正是设计稿 §14.3 步骤 8「`fastCut` 的时间区间参数改为可选」在计划模型上的落点。
 */
data class MediaRange(
    val startMillis: Long,
    val endMillis: Long,
) {
    init {
        require(startMillis >= 0)
        require(endMillis > startMillis)
    }

    val durationMillis: Long get() = endMillis - startMillis
}

data class ProcessingPlan(
    val source: SourceMediaInfo,
    val target: OutputTarget,
    val operation: ProcessingOperation,
    val targetWidth: Int,
    val targetHeight: Int,
    val retainedTrackIds: Set<Int>,
    val estimatedOutputBytes: Long,
    val requiredFreeBytes: Long,
    val outputDisplayName: String,
    val changes: List<ProcessingChange>,
    val range: MediaRange? = null,
) {
    init {
        require(targetWidth > 0 && targetHeight > 0)
        require(targetWidth % 2 == 0 && targetHeight % 2 == 0)
        require(estimatedOutputBytes > 0)
        require(requiredFreeBytes >= estimatedOutputBytes)
        require(outputDisplayName.isNotBlank())
        require(range == null || range.endMillis <= source.durationMillis)
    }

    val requiresConfirmation: Boolean get() = changes.any(ProcessingChange::requiresConfirmation)
}

sealed interface ProcessingPlanningResult {
    data class Ready(val plan: ProcessingPlan) : ProcessingPlanningResult
    data class Rejected(val code: String) : ProcessingPlanningResult
}

/**
 * 唯一的规划器。
 *
 * 判定顺序（设计稿 §4.2 的 F7/F16）：
 * 1. 目标带质量参数、或目标 codec 与源不同、或调用方要求精确到帧（[OutputTarget.frameAccurateCut]）
 *    → [ProcessingOperation.TRANSCODE]；
 * 2. 否则 → [ProcessingOperation.REMUX]（只换容器，源轨道原样搬过去）；
 * 3. 判定不出可达路径 → 拒绝并给明确错误码，**不静默降级**（Q500）。
 *
 * 注意第 1 条里「质量参数」必须挡在 remux 之前：`compatible_mp4` 的三元组与源完全相同，
 * 若只看 codec 相等就会把「压缩」判成 remux，用户点压缩却得到原样的文件。
 *
 * plan 不查 muxer 能力（那是数据层的事，域层不得导入 Media3）。因此
 * [ProcessingOperation.REMUX] 只保证「不需要重新编码」，**不保证目标容器接受源 codec**——
 * 容器可达性由数据层的 muxer 反查负责（设计稿 §6.2、§14.3 步骤 7/8）。
 */
object DefaultProcessingPlanner {
    fun plan(
        source: SourceMediaInfo,
        capabilities: DeviceMediaCapabilities,
        target: OutputTarget,
        availableBytes: Long,
        range: MediaRange? = null,
    ): ProcessingPlanningResult {
        if (range != null && range.endMillis > source.durationMillis) {
            return ProcessingPlanningResult.Rejected("RANGE_EXCEEDS_SOURCE")
        }

        val sourceVideoMime = source.tracks.first { it.type == MediaTrackType.VIDEO }.mimeType
        val audioTracks = source.tracks.filter { it.type == MediaTrackType.AUDIO }
        val sourceAudioMime = audioTracks.firstOrNull()?.mimeType

        val videoCodecChanged = target.videoCodecMimeType != null && target.videoCodecMimeType != sourceVideoMime
        val audioCodecChanged = target.audioCodecMimeType != null &&
            sourceAudioMime != null &&
            target.audioCodecMimeType != sourceAudioMime
        val operation = if (target.hasQualityParameters || videoCodecChanged || audioCodecChanged || target.frameAccurateCut) {
            ProcessingOperation.TRANSCODE
        } else {
            ProcessingOperation.REMUX
        }

        val encoder = if (operation == ProcessingOperation.TRANSCODE) {
            capabilities.encoders
                .filter { it.mimeType == (target.videoCodecMimeType ?: sourceVideoMime) }
                .maxByOrNull { it.maxWidth.toLong() * it.maxHeight }
                ?: return ProcessingPlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE")
        } else {
            null
        }

        val width: Int
        val height: Int
        if (encoder == null) {
            width = even(source.width)
            height = even(source.height)
        } else {
            val sourceLong = maxOf(source.width, source.height)
            val sourceShort = minOf(source.width, source.height)
            val targetLong = minOf(sourceLong, target.maximumLongEdge ?: sourceLong, maxOf(encoder.maxWidth, encoder.maxHeight))
                .coerceAtLeast(2)
            val scale = targetLong.toDouble() / sourceLong
            val targetShort = (sourceShort * scale).toInt().coerceAtLeast(2)
            val landscape = source.width >= source.height
            width = even(if (landscape) targetLong else targetShort)
            height = even(if (landscape) targetShort else targetLong)
            if (width > encoder.maxWidth && height > encoder.maxHeight) {
                return ProcessingPlanningResult.Rejected("ENCODER_SIZE_UNSUPPORTED")
            }
        }

        val changes = buildList {
            if (width != source.width || height != source.height) {
                add(ProcessingChange(ProcessingChangeCode.RESOLUTION_REDUCED))
            }
            if (videoCodecChanged) {
                add(ProcessingChange(ProcessingChangeCode.VIDEO_CODEC_CHANGED))
            }
            if (audioCodecChanged) {
                add(ProcessingChange(ProcessingChangeCode.AUDIO_CODEC_CHANGED))
            }
            if (audioTracks.size > 1) {
                add(ProcessingChange(ProcessingChangeCode.EXTRA_AUDIO_TRACKS_REMOVED))
            }
            if (source.tracks.any { it.type == MediaTrackType.SUBTITLE }) {
                add(ProcessingChange(ProcessingChangeCode.SUBTITLES_NOT_EMBEDDED))
            }
            if (encoder != null && source.hdrFormat != HdrFormat.SDR && !encoder.supportsHdr) {
                add(ProcessingChange(ProcessingChangeCode.HDR_TO_SDR))
            }
            if (encoder != null && (source.frameRate ?: 0f) > encoder.maxFrameRate) {
                add(ProcessingChange(ProcessingChangeCode.FRAME_RATE_CAPPED))
            }
        }

        val durationSeconds = (range?.durationMillis ?: source.durationMillis) / 1_000.0
        val videoBitrate = target.videoBitrate ?: source.videoBitrate ?: 0
        val audioBitrate = if (audioTracks.isNotEmpty()) target.audioBitrate ?: 0 else 0
        val estimate = (((videoBitrate + audioBitrate) / 8.0) * durationSeconds * ESTIMATE_FACTOR)
            .toLong()
            .coerceAtLeast(MINIMUM_ESTIMATE_BYTES)
        val required = estimate + PROCESSING_FREE_SPACE_RESERVE_BYTES
        if (availableBytes < required) return ProcessingPlanningResult.Rejected("INSUFFICIENT_STORAGE")

        val baseName = source.displayName.substringBeforeLast('.', source.displayName).sanitizeOutputName()
        return ProcessingPlanningResult.Ready(
            ProcessingPlan(
                source = source,
                target = target,
                operation = operation,
                targetWidth = width,
                targetHeight = height,
                retainedTrackIds = audioTracks.firstOrNull()?.let { setOf(it.id) }.orEmpty(),
                estimatedOutputBytes = estimate,
                requiredFreeBytes = required,
                outputDisplayName = "${baseName}_${target.id.value}.${target.fileExtension}",
                changes = changes,
                range = range,
            ),
        )
    }

    /**
     * 某个源**真正可达**的目标集合（设计稿 §14.7 第 3 项：格式转换入口由可达矩阵生成）。
     *
     * 判据就是 [plan] 本身：能被 plan 出一个方案的目标才出现在入口里，
     * **不另写一份容器 × codec 的表** —— 两份表必然分叉，而 `plan` 已经把编码器可用性、
     * 尺寸上限、容器可达性（§4.4、§4.5）都算进去了。
     *
     * 两类拒绝被刻意**当作可达**：
     * - `INSUFFICIENT_STORAGE`：这是当下的存储状态而不是能力边界，用户清出空间后同一个目标就可用；
     *   因此不能把档位从入口里摘掉。
     * - 任何其他未知码：宁可让它在点下去时报错，也不要因为新增了一个拒绝码而静默隐藏档位。
     */
    fun reachableTargets(
        source: SourceMediaInfo,
        capabilities: DeviceMediaCapabilities,
        availableBytes: Long,
    ): List<OutputTarget> = OutputTargets.all.filter { target ->
        when (val result = plan(source, capabilities, target, availableBytes)) {
            is ProcessingPlanningResult.Ready -> true
            is ProcessingPlanningResult.Rejected -> result.code !in CAPABILITY_REJECTIONS
        }
    }

    /** 只描述「这台设备/这个源做不到」，不含存储与区间这类运行期条件。 */
    private val CAPABILITY_REJECTIONS = setOf(
        "VIDEO_ENCODER_UNAVAILABLE",
        "ENCODER_SIZE_UNSUPPORTED",
    )

    private fun even(value: Int): Int = value.coerceAtLeast(2) and -2

    private fun String.sanitizeOutputName(): String = replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')
        .take(96)
        .ifBlank { "YingLi_output" }

    private const val ESTIMATE_FACTOR = 1.15
    private const val MINIMUM_ESTIMATE_BYTES = 1L * 1024 * 1024
}

/**
 * 处理任务需要预留的空闲空间。
 *
 * 调度侧（`MediaContainer.MINIMUM_FREE_BYTES`）与这里原本各写了一个值（128 MiB 与 256 MiB），
 * 同一个事实两个真源且互相矛盾。现在只有这一个常量，调度侧引用它（设计稿 §6.1）。
 */
const val PROCESSING_FREE_SPACE_RESERVE_BYTES: Long = 256L * 1024 * 1024

sealed interface ProcessingEngineResult {
    /**
     * 处理完成。
     *
     * [fallbacks] 是**库在运行期静默替换掉的请求**，来自 `Transformer.Listener.onFallbackApplied`。
     * 它必须随成功结果一起返回，因为库在回退时**依然报告成功**——只看「成功/失败」
     * 就会把「用户要 HEVC、实际拿到 H.264」当作正常结果提交（G6）。
     *
     * 这里只有 `VIDEO_CODEC_FALLBACK` / `AUDIO_CODEC_FALLBACK` 两种取值，
     * 它们都属于「需要确认」的后果，因此执行器遇到非空 [fallbacks] 时**必须拒绝提交**。
     */
    data class Completed(
        val fallbacks: Set<ProcessingChangeCode> = emptySet(),
        /**
         * 引擎实际写入输出文件的时间区间。
         *
         * 只在 [ProcessingPlan.range] 非空时有意义，且**必须由引擎回报而不是由调用方假定**：
         * remux 走 `MediaExtractor.SEEK_TO_PREVIOUS_SYNC`，实际起点必然 ≤ 请求起点
         * （请求 10.0 s、实际 9.4 s 是常态）。Q220/Q537 要求把「实际起止」如实告诉用户，
         * 这是那条要求的落点。
         */
        val actualRange: MediaRange? = null,
    ) : ProcessingEngineResult {
        val requiresConfirmation: Boolean get() = fallbacks.any { it.requiresConfirmation() }
    }

    data class Failed(val errorCode: String) : ProcessingEngineResult
    data object Canceled : ProcessingEngineResult
}

/**
 * 统一的处理引擎。
 *
 * 两个实现：媒体封装层（`REMUX`，手写 `MediaExtractor` + `Muxer`）与编码层
 * （`TRANSCODE`，Media3 `Transformer`）。**两个引擎不是架构冗余，是 F18 的直接后果**：
 * 编码必须用 Transformer，而封装在少数场景下 Transformer 够不着
 * （VP9/Opus/Vorbis/AV1 只能以 remux 方式进输出）。
 * 调用方不选择实现，由 planner 的 [ProcessingPlan.operation] 路由（设计稿 §6.2）。
 */
interface ProcessingEngine {
    suspend fun process(
        plan: ProcessingPlan,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): ProcessingEngineResult

    suspend fun cancel()
}

data class OutputVerification(
    val valid: Boolean,
    val errorCodes: Set<String>,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /**
     * 输出文件的平均码率（`字节数 × 8 × 1000 / 时长毫秒`）计算。
     *
     * 这是**唯一可获得的码率观测量**：真机实测（`docs/architecture/Organizing-Page-Function-Design.md`
     * §20.1.3）输出的 MP4 视频轨**读不到** `KEY_BIT_RATE`（源与全部输出均为 `null`），
     * 因此 G8 原定的「读回 `KEY_BIT_RATE` 与预设比对」在本设备/该 MP4 路径不可行。
     *
     * 该值受内容复杂度影响，**不等于请求码率**（静态画面下编码器会大幅低于目标码率），
     * 因此它只作为对照数据供 UI 呈现，**不参与 [valid] 判定**——把码率偏差当作失败会误杀合法输出。
     * 「预设码率是否真的生效」由单元测试（请求已进入 `VideoEncoderSettings`）与真机测量
     * （不同预设的输出字节数是否分离）回答。
     */
    val averageBitrateBitsPerSecond: Int? = null,
)

interface OutputVerifier {
    suspend fun verify(path: String, plan: ProcessingPlan): OutputVerification
}

interface ProcessingQueue {
    val pendingPlans: Flow<Map<ProcessingProjectId, ProcessingPlan>>

    suspend fun enqueue(plan: ProcessingPlan, destructiveChangesConfirmed: Boolean): ProcessingProjectId

    suspend fun plan(projectId: ProcessingProjectId): ProcessingPlan?
}
