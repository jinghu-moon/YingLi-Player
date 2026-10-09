package seeyuer.yingli.player.feature.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.domain.clips.ClipEditCommand
import seeyuer.yingli.player.domain.clips.ClipEditResult
import seeyuer.yingli.player.domain.clips.ClipEditState
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipExportQueue
import seeyuer.yingli.player.domain.clips.ClipPreset
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipProjectReducer
import seeyuer.yingli.player.domain.clips.ClipProjectRepository
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.clips.TimelineFrame
import seeyuer.yingli.player.domain.clips.TimelineFrameProvider
import seeyuer.yingli.player.domain.clips.TimelineFrameRequest
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.processing.ProcessingController
import seeyuer.yingli.player.domain.processing.ProcessingPresentation
import seeyuer.yingli.player.domain.processing.ProcessingPresentationMapper
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.DefaultProcessingPlanner
import seeyuer.yingli.player.domain.processing.MediaCapabilityProbe
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.ProcessingPlanningResult
import seeyuer.yingli.player.domain.processing.OutputTarget
import seeyuer.yingli.player.domain.processing.OutputTargets
import seeyuer.yingli.player.domain.processing.ProcessingQueue

data class ProcessingTaskItem(
    val task: ProcessingTask,
    val presentation: ProcessingPresentation,
)

data class ProcessingUiState(
    val tasks: List<ProcessingTaskItem> = emptyList(),
    val clipProjects: List<ClipProject> = emptyList(),
    val media: List<LibraryMedia> = emptyList(),
    val editor: ClipEditState? = null,
    val exporting: Boolean = false,
    val timelineFrames: List<TimelineFrame> = emptyList(),
    val outputTarget: OutputTarget = OutputTargets.Balanced,
    val processingPlan: ProcessingPlan? = null,
    val processingPlanning: Boolean = false,
    val transcodeErrorCode: String? = null,
)

@OptIn(FlowPreview::class)
class ProcessingViewModel(
    private val processingRepository: ProcessingRepository,
    private val controller: ProcessingController,
    private val clipRepository: ClipProjectRepository,
    private val exportQueue: ClipExportQueue,
    libraryRepository: LibraryRepository,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val timelineFrameProvider: TimelineFrameProvider,
    private val mediaCapabilityProbe: MediaCapabilityProbe,
    private val processingQueue: ProcessingQueue,
    private val availableBytes: () -> Long,
) : ViewModel() {
    private val editor = MutableStateFlow<ClipEditState?>(null)
    private val exporting = MutableStateFlow(false)
    private val timelineFrames = MutableStateFlow<List<TimelineFrame>>(emptyList())
    private val outputTarget = MutableStateFlow(OutputTargets.Balanced)
    private val processingPlan = MutableStateFlow<ProcessingPlan?>(null)
    private val processingPlanning = MutableStateFlow(false)
    private val transcodeErrorCode = MutableStateFlow<String?>(null)
    private val saveRequests = MutableSharedFlow<ClipProject>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val media = libraryRepository.observe(LibraryQuery(pageSize = LibraryQuery.MAX_PAGE_SIZE))
        .map { result -> (result as? LibraryResult.Success)?.value?.items.orEmpty() }
    private val editorSnapshot = combine(editor, exporting, timelineFrames, ::EditorSnapshot)
    private val transcodeSnapshot = combine(
        outputTarget,
        processingPlan,
        processingPlanning,
        transcodeErrorCode,
        ::TranscodeSnapshot,
    )

    val state: StateFlow<ProcessingUiState> = combine(
        processingRepository.tasks,
        clipRepository.projects,
        media,
        editorSnapshot,
        transcodeSnapshot,
    ) { tasks, projects, mediaItems, editorState, transcodeState ->
        ProcessingUiState(
            tasks = tasks.map { ProcessingTaskItem(it, ProcessingPresentationMapper.map(it)) },
            clipProjects = projects,
            media = mediaItems,
            editor = editorState.editor,
            exporting = editorState.exporting,
            timelineFrames = editorState.frames,
            outputTarget = transcodeState.preset,
            processingPlan = transcodeState.plan,
            processingPlanning = transcodeState.planning,
            transcodeErrorCode = transcodeState.errorCode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProcessingUiState())

    init {
        viewModelScope.launch {
            saveRequests.debounce(AUTO_SAVE_MILLIS).collect(clipRepository::save)
        }
    }

    fun createProject(media: LibraryMedia) {
        val duration = media.durationMillis?.takeIf { it > 0 } ?: return
        val now = clock.now().toEpochMilli()
        val initialEnd = minOf(duration, DEFAULT_SEGMENT_MILLIS)
        val project = ClipProject(
            ClipProjectId(idGenerator.newId()),
            media.id,
            media.locationId,
            duration,
            listOf(ClipSegment(ClipSegmentId(idGenerator.newId()), 0, initialEnd, "Clip 1")),
            createdAtEpochMillis = now,
        )
        editor.value = ClipEditState(project)
        loadTimeline(project, media.uri.value)
        viewModelScope.launch { clipRepository.save(project) }
    }

    fun openProject(id: ClipProjectId) {
        val project = state.value.clipProjects.firstOrNull { it.id == id } ?: return
        editor.value = ClipEditState(project)
        state.value.media.firstOrNull { it.id == project.sourceMediaId }
            ?.let { loadTimeline(project, it.uri.value) }
    }

    fun closeEditor() {
        editor.value?.project?.let { project -> viewModelScope.launch { clipRepository.save(project) } }
        editor.value = null
        timelineFrames.value = emptyList()
    }

    fun addSegment() {
        val project = editor.value?.project ?: return
        val start = project.segments.lastOrNull()?.endMillis?.coerceAtMost(project.sourceDurationMillis - 1) ?: 0
        if (start >= project.sourceDurationMillis - 1) return
        val end = minOf(project.sourceDurationMillis, start + DEFAULT_SEGMENT_MILLIS)
        apply(ClipEditCommand.Add(
            ClipSegment(
                ClipSegmentId(idGenerator.newId()),
                start,
                end,
                "Clip ${project.segments.size + 1}",
            ),
            now(),
        ))
    }

    fun updateSegment(id: ClipSegmentId, startMillis: Long, endMillis: Long, name: String? = null) {
        val project = editor.value?.project ?: return
        val current = project.segments.firstOrNull { it.id == id } ?: return
        val safeStart = startMillis.coerceIn(0, project.sourceDurationMillis - 1)
        val safeEnd = endMillis.coerceIn(safeStart + 1, project.sourceDurationMillis)
        val updated = runCatching {
            current.copy(
                startMillis = safeStart,
                endMillis = safeEnd,
                name = name?.take(ClipSegment.MAX_NAME_LENGTH)?.ifBlank { current.name } ?: current.name,
            )
        }.getOrNull() ?: return
        apply(ClipEditCommand.Update(updated, now()))
    }

    fun duplicate(id: ClipSegmentId) = apply(ClipEditCommand.Duplicate(id, ClipSegmentId(idGenerator.newId()), now()))
    fun toggleSelection(id: ClipSegmentId) = apply(ClipEditCommand.ToggleSelection(id, now()))
    fun selectAll(selected: Boolean) = apply(ClipEditCommand.SelectAll(selected, now()))
    fun deleteSelected() = apply(ClipEditCommand.DeleteSelected(now()))
    fun undo() = apply(ClipEditCommand.Undo)

    fun setExportMode(mode: ClipExportMode) {
        val project = editor.value?.project ?: return
        val preset = if (mode == ClipExportMode.FAST) ClipPreset.SOURCE_QUALITY else ClipPreset.COMPATIBLE_MP4
        apply(ClipEditCommand.ChangeExport(mode, preset, now()))
    }

    fun exportSelected() {
        val project = editor.value?.project ?: return
        if (project.segments.none(ClipSegment::selected) || exporting.value) return
        viewModelScope.launch {
            exporting.value = true
            runCatching {
                clipRepository.save(project)
                exportQueue.enqueue(project)
            }
            exporting.value = false
        }
    }

    fun pause(id: ProcessingTaskId) = controller.pause(id)
    fun resume(id: ProcessingTaskId) = controller.resume(id)
    fun cancel(id: ProcessingTaskId) = controller.cancel(id)
    fun retry(id: ProcessingTaskId) = controller.retry(id)
    fun clearHistory() = viewModelScope.launch { processingRepository.clearTerminal() }

    fun setOutputTarget(preset: OutputTarget) {
        outputTarget.value = preset
        processingPlan.value = null
        transcodeErrorCode.value = null
    }

    fun planTranscode(media: LibraryMedia) {
        if (processingPlanning.value) return
        viewModelScope.launch {
            processingPlanning.value = true
            processingPlan.value = null
            transcodeErrorCode.value = null
            val source = mediaCapabilityProbe.source(media.id, media.uri.value, media.fileName)
            val result = if (source == null) {
                ProcessingPlanningResult.Rejected("PROBE_FAILED")
            } else {
                DefaultProcessingPlanner.plan(
                    source,
                    mediaCapabilityProbe.deviceCapabilities(),
                    outputTarget.value,
                    availableBytes(),
                )
            }
            when (result) {
                is ProcessingPlanningResult.Ready -> processingPlan.value = result.plan
                is ProcessingPlanningResult.Rejected -> transcodeErrorCode.value = result.code
            }
            processingPlanning.value = false
        }
    }

    fun dismissProcessingPlan() {
        processingPlan.value = null
        transcodeErrorCode.value = null
    }

    fun enqueueTranscode(destructiveChangesConfirmed: Boolean) {
        val plan = processingPlan.value ?: return
        if (plan.requiresConfirmation && !destructiveChangesConfirmed) return
        viewModelScope.launch {
            runCatching { processingQueue.enqueue(plan, destructiveChangesConfirmed) }
                .onSuccess { processingPlan.value = null }
                .onFailure { transcodeErrorCode.value = "QUEUE_FAILED" }
        }
    }

    private fun apply(command: ClipEditCommand) {
        val current = editor.value ?: return
        val result = ClipProjectReducer.reduce(current, command)
        if (result is ClipEditResult.Applied) {
            editor.value = result.state
            saveRequests.tryEmit(result.state.project)
        }
    }

    private fun now(): Long = clock.now().toEpochMilli()

    private data class EditorSnapshot(
        val editor: ClipEditState?,
        val exporting: Boolean,
        val frames: List<TimelineFrame>,
    )

    private data class TranscodeSnapshot(
        val preset: OutputTarget,
        val plan: ProcessingPlan?,
        val planning: Boolean,
        val errorCode: String?,
    )

    private fun loadTimeline(project: ClipProject, sourceUri: String) {
        viewModelScope.launch {
            timelineFrames.value = timelineFrameProvider.frames(TimelineFrameRequest(
                sourceUri,
                0,
                project.sourceDurationMillis,
                TIMELINE_FRAME_COUNT,
            ))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val AUTO_SAVE_MILLIS = 500L
        private const val DEFAULT_SEGMENT_MILLIS = 10_000L
        private const val TIMELINE_FRAME_COUNT = 12

        fun factory(
            processingRepository: ProcessingRepository,
            controller: ProcessingController,
            clipRepository: ClipProjectRepository,
            exportQueue: ClipExportQueue,
            libraryRepository: LibraryRepository,
            idGenerator: IdGenerator,
            clock: AppClock,
            timelineFrameProvider: TimelineFrameProvider,
            mediaCapabilityProbe: MediaCapabilityProbe,
            processingQueue: ProcessingQueue,
            availableBytes: () -> Long,
        ) = viewModelFactory {
            initializer {
                ProcessingViewModel(
                    processingRepository,
                    controller,
                    clipRepository,
                    exportQueue,
                    libraryRepository,
                    idGenerator,
                    clock,
                    timelineFrameProvider,
                    mediaCapabilityProbe,
                    processingQueue,
                    availableBytes,
                )
            }
        }
    }
}
