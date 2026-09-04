package seeyuer.yingli.player.domain.clips

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
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

data class ClipSource(
    val uri: String,
    val durationMillis: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

data class ClipCapabilities(
    val fastCut: Boolean,
    val accurateCut: Boolean,
    val diagnosticCode: String? = null,
)

sealed interface ClipEngineResult {
    data class Success(val actualStartMillis: Long, val actualEndMillis: Long) : ClipEngineResult
    data class Unsupported(val diagnosticCode: String) : ClipEngineResult
    data class Failed(val diagnosticCode: String) : ClipEngineResult
    data object Canceled : ClipEngineResult
}

interface ClipEngine {
    suspend fun probe(uri: String): Pair<ClipSource, ClipCapabilities>?
    suspend fun fastCut(source: ClipSource, segment: ClipSegment, outputPath: String): ClipEngineResult
    suspend fun accurateCut(source: ClipSource, segment: ClipSegment, outputPath: String): ClipEngineResult
}

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

private fun String.toSafeName(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "clip" }.take(60)
private fun String.isSafeFileName(): Boolean = isNotBlank() && length <= 120 && '/' !in this && '\\' !in this
