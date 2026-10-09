package seeyuer.yingli.player.domain.clips

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.ProcessingProjectId

private val STABLE_ID = Regex("[A-Za-z0-9_-]{1,128}")

@JvmInline
value class ClipProjectId(val value: String) {
    init { require(value.matches(STABLE_ID)) }
}

@JvmInline
value class ClipSegmentId(val value: String) {
    init { require(value.matches(STABLE_ID)) }
}

data class ClipSegment(
    val id: ClipSegmentId,
    val startMillis: Long,
    val endMillis: Long,
    val name: String,
    val selected: Boolean = true,
) {
    init {
        require(startMillis >= 0)
        require(endMillis > startMillis)
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
    }

    val durationMillis: Long = endMillis - startMillis

    companion object {
        const val MAX_NAME_LENGTH = 80

        /**
         * 「区间 → 片段」的**唯一命名构造路径**。
         *
         * 从 AB 区间导出（设计稿 §14.6 步骤 9）与编辑器新增片段（步骤 10）都走这里。
         * 它刻意不做任何加工：合法区间（`startMillis >= 0`、`endMillis > startMillis`）、
         * 名字非空且不超长全部由本类型自己的 `init` 把关 —— 工厂里再抄一遍规则就是第二个真源，
         * 而"把不合法的区间改成合法"（钳制、取整、截断）会让调用方拿到的区间与它请求的不是同一个。
         */
        fun forRange(
            id: ClipSegmentId,
            startMillis: Long,
            endMillis: Long,
            name: String,
        ): ClipSegment = ClipSegment(id, startMillis, endMillis, name)
    }
}

enum class ClipExportMode {
    FAST,
    ACCURATE,
}

enum class ClipPreset {
    SOURCE_QUALITY,
    COMPATIBLE_MP4,
}

data class ClipProject(
    val id: ClipProjectId,
    val sourceMediaId: MediaItemId,
    val sourceLocationId: MediaLocationId,
    val sourceDurationMillis: Long,
    val segments: List<ClipSegment>,
    val exportMode: ClipExportMode = ClipExportMode.FAST,
    val preset: ClipPreset = ClipPreset.SOURCE_QUALITY,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(sourceDurationMillis > 0)
        require(segments.map(ClipSegment::id).distinct().size == segments.size)
        require(segments.all { it.endMillis <= sourceDurationMillis })
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }

    companion object {
        /**
         * 「一个源 + 一个区间 → 单段项目」的纯工厂（设计稿 §9.3 / §14.6 步骤 9）。
         *
         * 存在的理由是 §9.2 的 C1：AB 区间是**会话临时状态**（`docs/18` 逐字「媒体切换清除 AB；
         * AB 为当前会话临时状态，不写全局偏好」），而 `ClipProject` 是 Room 持久实体。
         * 两者之间必须有一个**只在提交那一刻发生**的转换点：调用方（播放侧）在这一刻把
         * `(A, B)` 固化成 `ClipSegment`，从这以后项目里再也看不到 `AbLoopState`，
         * 播放会话把它清掉、把媒体换掉都不会影响已经落库的项目。
         *
         * 参数**不给默认值**、顺序与设计稿 §9.3 一致：导出既要求模式（`exportMode`/`preset`
         * 是用户当场二选一的结果，没有"预设默认"），也要求两个 id 由调用方生成
         *（本项目只允许 `IdGenerator` 造 id，域层不提供"随机"这条路）。
         *
         * 区间不符合 `ClipSegment` / `ClipProject` 的不变量时**抛出**，不做钳制：
         * 起点越界、终点超出源时长、起点等于终点都必须在进入队列**之前**就被拒绝，
         * 否则用户会在任务中心看到一个注定失败的任务。
         */
        fun forRange(
            sourceMediaId: MediaItemId,
            sourceLocationId: MediaLocationId,
            sourceDurationMillis: Long,
            startMillis: Long,
            endMillis: Long,
            name: String,
            exportMode: ClipExportMode,
            preset: ClipPreset,
            projectId: ClipProjectId,
            segmentId: ClipSegmentId,
            nowEpochMillis: Long,
        ): ClipProject = ClipProject(
            id = projectId,
            sourceMediaId = sourceMediaId,
            sourceLocationId = sourceLocationId,
            sourceDurationMillis = sourceDurationMillis,
            segments = listOf(ClipSegment.forRange(segmentId, startMillis, endMillis, name)),
            exportMode = exportMode,
            preset = preset,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }
}

data class ClipEditState(
    val project: ClipProject,
    val undo: List<ClipProject> = emptyList(),
)

sealed interface ClipEditCommand {
    data class Add(val segment: ClipSegment, val atEpochMillis: Long) : ClipEditCommand
    data class Update(val segment: ClipSegment, val atEpochMillis: Long) : ClipEditCommand
    data class Duplicate(val sourceId: ClipSegmentId, val newId: ClipSegmentId, val atEpochMillis: Long) : ClipEditCommand
    data class Move(val id: ClipSegmentId, val targetIndex: Int, val atEpochMillis: Long) : ClipEditCommand
    data class ToggleSelection(val id: ClipSegmentId, val atEpochMillis: Long) : ClipEditCommand
    data class SelectAll(val selected: Boolean, val atEpochMillis: Long) : ClipEditCommand
    data class DeleteSelected(val atEpochMillis: Long) : ClipEditCommand
    data class ChangeExport(val mode: ClipExportMode, val preset: ClipPreset, val atEpochMillis: Long) : ClipEditCommand
    data object Undo : ClipEditCommand
}

sealed interface ClipEditResult {
    data class Applied(val state: ClipEditState) : ClipEditResult
    data class Ignored(val state: ClipEditState) : ClipEditResult
    data class Rejected(val state: ClipEditState) : ClipEditResult
}

object ClipProjectReducer {
    fun reduce(state: ClipEditState, command: ClipEditCommand): ClipEditResult {
        if (command == ClipEditCommand.Undo) {
            val previous = state.undo.lastOrNull() ?: return ClipEditResult.Ignored(state)
            return ClipEditResult.Applied(ClipEditState(previous, state.undo.dropLast(1)))
        }
        val project = state.project
        val updated = when (command) {
            is ClipEditCommand.Add -> {
                if (project.segments.any { it.id == command.segment.id } || command.segment.endMillis > project.sourceDurationMillis) {
                    return ClipEditResult.Rejected(state)
                }
                project.copy(segments = project.segments + command.segment, updatedAtEpochMillis = command.atEpochMillis.safeTime(project))
            }
            is ClipEditCommand.Update -> {
                val index = project.segments.indexOfFirst { it.id == command.segment.id }
                if (index < 0 || command.segment.endMillis > project.sourceDurationMillis) return ClipEditResult.Rejected(state)
                project.copy(
                    segments = project.segments.toMutableList().apply { set(index, command.segment) },
                    updatedAtEpochMillis = command.atEpochMillis.safeTime(project),
                )
            }
            is ClipEditCommand.Duplicate -> {
                val source = project.segments.firstOrNull { it.id == command.sourceId }
                    ?: return ClipEditResult.Rejected(state)
                if (project.segments.any { it.id == command.newId }) return ClipEditResult.Rejected(state)
                val copy = source.copy(id = command.newId, name = "${source.name} copy".take(ClipSegment.MAX_NAME_LENGTH))
                val index = project.segments.indexOf(source) + 1
                project.copy(
                    segments = project.segments.toMutableList().apply { add(index, copy) },
                    updatedAtEpochMillis = command.atEpochMillis.safeTime(project),
                )
            }
            is ClipEditCommand.Move -> {
                val sourceIndex = project.segments.indexOfFirst { it.id == command.id }
                if (sourceIndex < 0 || command.targetIndex !in project.segments.indices) return ClipEditResult.Rejected(state)
                if (sourceIndex == command.targetIndex) return ClipEditResult.Ignored(state)
                val moved = project.segments.toMutableList().apply { add(command.targetIndex, removeAt(sourceIndex)) }
                project.copy(segments = moved, updatedAtEpochMillis = command.atEpochMillis.safeTime(project))
            }
            is ClipEditCommand.ToggleSelection -> {
                val index = project.segments.indexOfFirst { it.id == command.id }
                if (index < 0) return ClipEditResult.Rejected(state)
                project.copy(
                    segments = project.segments.toMutableList().apply { set(index, get(index).copy(selected = !get(index).selected)) },
                    updatedAtEpochMillis = command.atEpochMillis.safeTime(project),
                )
            }
            is ClipEditCommand.SelectAll -> {
                if (project.segments.all { it.selected == command.selected }) return ClipEditResult.Ignored(state)
                project.copy(
                    segments = project.segments.map { it.copy(selected = command.selected) },
                    updatedAtEpochMillis = command.atEpochMillis.safeTime(project),
                )
            }
            is ClipEditCommand.DeleteSelected -> {
                val remaining = project.segments.filterNot(ClipSegment::selected)
                if (remaining.size == project.segments.size) return ClipEditResult.Ignored(state)
                project.copy(segments = remaining, updatedAtEpochMillis = command.atEpochMillis.safeTime(project))
            }
            is ClipEditCommand.ChangeExport -> {
                if (project.exportMode == command.mode && project.preset == command.preset) return ClipEditResult.Ignored(state)
                project.copy(
                    exportMode = command.mode,
                    preset = command.preset,
                    updatedAtEpochMillis = command.atEpochMillis.safeTime(project),
                )
            }
            ClipEditCommand.Undo -> error("Handled above")
        }
        return ClipEditResult.Applied(ClipEditState(updated, (state.undo + project).takeLast(MAX_UNDO_DEPTH)))
    }

    fun replay(initial: ClipEditState, commands: Iterable<ClipEditCommand>): ClipEditState =
        commands.fold(initial) { current, command ->
            when (val result = reduce(current, command)) {
                is ClipEditResult.Applied -> result.state
                is ClipEditResult.Ignored -> result.state
                is ClipEditResult.Rejected -> result.state
            }
        }

    private fun Long.safeTime(project: ClipProject): Long = coerceAtLeast(project.updatedAtEpochMillis)
    private const val MAX_UNDO_DEPTH = 50
}

enum class ClipConflictStrategy {
    RENAME,
    SKIP,
}

data class ClipExportItem(
    val segment: ClipSegment,
    val displayName: String,
)

data class ClipExportPlan(
    val projectId: ClipProjectId,
    val mode: ClipExportMode,
    val preset: ClipPreset,
    val items: List<ClipExportItem>,
    val outputDirectory: String = "YingLi-Output",
    val conflictStrategy: ClipConflictStrategy = ClipConflictStrategy.RENAME,
) {
    init {
        require(items.isNotEmpty())
        require(items.map { it.segment.id }.distinct().size == items.size)
        require(items.all { it.segment.selected })
        require(items.all { it.displayName.isSafeFileName() })
        require(outputDirectory.isNotBlank())
    }

    companion object {
        fun from(project: ClipProject): ClipExportPlan {
            val selected = project.segments.filter(ClipSegment::selected)
            require(selected.isNotEmpty())
            return ClipExportPlan(
                project.id,
                project.exportMode,
                project.preset,
                selected.mapIndexed { index, segment ->
                    ClipExportItem(segment, "${(index + 1).toString().padStart(2, '0')}-${segment.name.toSafeName()}.mp4")
                },
            )
        }
    }
}

/**
 * 切片不再有自己的引擎抽象。
 *
 * 原先这里定义过 `ClipEngine`（`probe` / `fastCut` / `accurateCut`）与配套的 `ClipSource`、
 * `ClipCapabilities`、`ClipEngineResult`。它们是**第四套与处理管线平行的抽象**：
 * `fastCut` 与 `InAppRemuxEngine` 做的是同一件事（`MediaExtractor` + `Muxer` 搬运），
 * `accurateCut` 与 `Media3ProcessingEngine` 做的是同一件事（`Transformer` 重编码），
 * 而 `probe` 与 `MediaCapabilityProbe.source` 重复。
 *
 * 2026-10-09 阶段 2 步骤 6 已把它们合并到统一的 `ProcessingEngine` + planner 路由
 * （设计稿 §4.3、§6.2）：切片 = 「目标容器 MP4、codec 同源、带时间区间」的一个 `ProcessingPlan`，
 * 快速与精确的差别只是 `OutputTarget.frameAccurateCut`。此处不留兼容层。
 */

data class TimelineFrameRequest(
    val sourceUri: String,
    val visibleStartMillis: Long,
    val visibleEndMillis: Long,
    val maximumFrames: Int,
) {
    init {
        require(visibleStartMillis >= 0 && visibleEndMillis > visibleStartMillis)
        require(maximumFrames in 1..30)
    }
}

data class TimelineFrame(val positionMillis: Long, val cacheKey: String)

interface TimelineFrameProvider {
    suspend fun frames(request: TimelineFrameRequest): List<TimelineFrame>
}

interface ClipProjectRepository {
    val projects: Flow<List<ClipProject>>
    suspend fun project(id: ClipProjectId): ClipProject?
    suspend fun save(project: ClipProject)
    suspend fun delete(id: ClipProjectId)
}

interface ClipExportQueue {
    suspend fun enqueue(project: ClipProject): ProcessingProjectId
}

/**
 * 「这个源能不能用**无损复制**（样本搬运）导出成目标容器」——设计稿 §14.6 步骤 13 / G11。
 *
 * 为什么需要它：快速切片（[ClipExportMode.FAST]）走的是 `SEEK_TO_PREVIOUS_SYNC` + 样本搬运，
 * 它只换容器、不重编码，所以**目标容器必须收得下源轨道的样本格式**。源是 VP9 / Opus 之类的
 * 组合（典型：WebM）时，MP4 收不下，快速路径必然失败 —— 而失败发生在任务中心里，
 * 用户已经点过一次、也等过一次。播放页在**打开 sheet 之前**问一句，就能把默认选项
 * 放在能成功的那个模式上（并被要求说明理由），而不是让用户先失败一次。
 *
 * 实现必须查**唯一那份**容器能力表（`MuxerContainer`，取自 `Mp4Muxer.SUPPORTED_*`），
 * **不得**在这里再抄一份 mime 白名单：G3/G11 的成因正是"手写的表"与库的表不一致。
 *
 * 返回 false 只表示"快速模式不可行"，不表示"不能导出"：精确模式（重编码）是另一条路径。
 */
fun interface ClipFastExportProbe {
    suspend fun supportsFastExport(media: LibraryMedia): Boolean
}

private fun String.toSafeName(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "clip" }.take(60)
private fun String.isSafeFileName(): Boolean = isNotBlank() && length <= 120 && '/' !in this && '\\' !in this
