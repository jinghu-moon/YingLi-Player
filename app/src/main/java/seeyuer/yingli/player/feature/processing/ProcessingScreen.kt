package seeyuer.yingli.player.feature.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiCheckbox
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiTextField
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.clips.TimelineFrame
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.ProcessingAction
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.ProcessingTaskState
import seeyuer.yingli.player.domain.processing.ProcessingTone
import seeyuer.yingli.player.domain.transcode.TranscodeChangeCode
import seeyuer.yingli.player.domain.transcode.TranscodePlan
import seeyuer.yingli.player.domain.transcode.TranscodePreset
import seeyuer.yingli.player.domain.transcode.TranscodePresets

private enum class ProcessingTab { TASKS, CLIPS, TRANSCODE }

@Composable
fun ProcessingRoute(
    viewModel: ProcessingViewModel,
    onOpenOutput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProcessingScreen(
        state = state,
        onCreateProject = viewModel::createProject,
        onOpenProject = viewModel::openProject,
        onCloseEditor = viewModel::closeEditor,
        onAddSegment = viewModel::addSegment,
        onUpdateSegment = viewModel::updateSegment,
        onDuplicateSegment = viewModel::duplicate,
        onToggleSegment = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onDeleteSelected = viewModel::deleteSelected,
        onUndo = viewModel::undo,
        onSetExportMode = viewModel::setExportMode,
        onExport = viewModel::exportSelected,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onClearHistory = viewModel::clearHistory,
        onTranscodePreset = viewModel::setTranscodePreset,
        onPlanTranscode = viewModel::planTranscode,
        onDismissTranscode = viewModel::dismissTranscodePlan,
        onEnqueueTranscode = viewModel::enqueueTranscode,
        onOpenOutput = onOpenOutput,
        modifier = modifier,
    )
}

@Composable
fun ProcessingScreen(
    state: ProcessingUiState,
    onCreateProject: (LibraryMedia) -> Unit,
    onOpenProject: (ClipProjectId) -> Unit,
    onCloseEditor: () -> Unit,
    onAddSegment: () -> Unit,
    onUpdateSegment: (ClipSegmentId, Long, Long, String?) -> Unit,
    onDuplicateSegment: (ClipSegmentId) -> Unit,
    onToggleSegment: (ClipSegmentId) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDeleteSelected: () -> Unit,
    onUndo: () -> Unit,
    onSetExportMode: (ClipExportMode) -> Unit,
    onExport: () -> Unit,
    onPause: (ProcessingTaskId) -> Unit,
    onResume: (ProcessingTaskId) -> Unit,
    onCancel: (ProcessingTaskId) -> Unit,
    onRetry: (ProcessingTaskId) -> Unit,
    onClearHistory: () -> Unit,
    onTranscodePreset: (TranscodePreset) -> Unit,
    onPlanTranscode: (LibraryMedia) -> Unit,
    onDismissTranscode: () -> Unit,
    onEnqueueTranscode: (Boolean) -> Unit,
    onOpenOutput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.editor?.let { editState ->
        ClipEditor(
            editState.project,
            state.exporting,
            state.timelineFrames,
            onCloseEditor,
            onAddSegment,
            onUpdateSegment,
            onDuplicateSegment,
            onToggleSegment,
            onSelectAll,
            onDeleteSelected,
            onUndo,
            onSetExportMode,
            onExport,
            modifier,
        )
        return
    }
    var tab by remember { mutableStateOf(ProcessingTab.TASKS) }
    Column(
        modifier = modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
    ) {
        YingLiSegmentedControl(
            options = listOf(
                stringResource(R.string.processing_tasks),
                stringResource(R.string.processing_clips),
                stringResource(R.string.processing_transcode),
            ),
            selectedIndex = tab.ordinal,
            onSelected = { tab = ProcessingTab.entries[it] },
        )
        when (tab) {
            ProcessingTab.TASKS -> TaskList(
                state.tasks,
                onPause,
                onResume,
                onCancel,
                onRetry,
                onOpenOutput,
                onClearHistory,
                Modifier.weight(1f),
            )
            ProcessingTab.CLIPS -> ClipProjectList(
                state,
                onCreateProject,
                onOpenProject,
                Modifier.weight(1f),
            )
            ProcessingTab.TRANSCODE -> TranscodePanel(
                state,
                onTranscodePreset,
                onPlanTranscode,
                onDismissTranscode,
                onEnqueueTranscode,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TranscodePanel(
    state: ProcessingUiState,
    onPreset: (TranscodePreset) -> Unit,
    onPlan: (LibraryMedia) -> Unit,
    onDismiss: () -> Unit,
    onEnqueue: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var confirmed by remember(state.transcodePlan) { mutableStateOf(false) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
        item {
            Text(stringResource(R.string.transcode_preset), style = MaterialTheme.typography.titleLarge)
            YingLiSegmentedControl(
                options = listOf(
                    stringResource(R.string.transcode_preset_compatible),
                    stringResource(R.string.transcode_preset_balanced),
                    stringResource(R.string.transcode_preset_space_saver),
                ),
                selectedIndex = TranscodePresets.all.indexOf(state.transcodePreset),
                onSelected = { onPreset(TranscodePresets.all[it]) },
            )
        }
        state.transcodeErrorCode?.let { code ->
            item {
                YingLiBanner(
                    message = stringResource(R.string.transcode_error, code),
                    kind = BannerKind.ERROR,
                )
            }
        }
        state.transcodePlan?.let { plan ->
            item {
                TranscodePlanSummary(plan)
                if (plan.requiresConfirmation) {
                    YingLiCheckbox(
                        label = stringResource(R.string.transcode_confirm_changes),
                        checked = confirmed,
                        onCheckedChange = { confirmed = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
                ) {
                    YingLiButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        leadingIcon = YingLiIcon.WARNING,
                    )
                    YingLiButton(
                        text = stringResource(R.string.transcode_enqueue),
                        onClick = { onEnqueue(confirmed) },
                        modifier = Modifier.weight(1f),
                        enabled = !plan.requiresConfirmation || confirmed,
                        leadingIcon = YingLiIcon.PROCESSING,
                    )
                }
            }
        } ?: run {
            item {
                Text(
                    if (state.transcodePlanning) stringResource(R.string.transcode_planning)
                    else stringResource(R.string.transcode_choose_source),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.media.take(MAX_SOURCE_CHOICES), key = { "transcode-${it.id.value}" }) { media ->
                YingLiButton(
                    text = media.title,
                    onClick = { onPlan(media) },
                    enabled = !state.transcodePlanning && media.durationMillis?.let { it > 0 } == true,
                    leadingIcon = YingLiIcon.PROCESSING,
                )
            }
        }
    }
}

@Composable
private fun TranscodePlanSummary(plan: TranscodePlan) {
    Surface(color = YingLiTheme.colors.surfaceComponent, shape = YingLiTheme.components.componentCorner) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            Text(plan.outputDisplayName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(
                R.string.transcode_dimensions,
                plan.source.width,
                plan.source.height,
                plan.targetWidth,
                plan.targetHeight,
            ))
            Text(stringResource(R.string.transcode_estimated_size, plan.estimatedOutputBytes.toMegabytes()))
            plan.changes.forEach { change ->
                Text(
                    text = stringResource(change.code.labelResource()),
                    color = if (change.requiresConfirmation) YingLiTheme.functional.warning.strong
                    else YingLiTheme.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun Long.toMegabytes(): Long = (this + 1024 * 1024 - 1) / (1024 * 1024)

private fun TranscodeChangeCode.labelResource(): Int = when (this) {
    TranscodeChangeCode.RESOLUTION_REDUCED -> R.string.transcode_change_resolution
    TranscodeChangeCode.VIDEO_CODEC_CHANGED -> R.string.transcode_change_video_codec
    TranscodeChangeCode.AUDIO_CODEC_CHANGED -> R.string.transcode_change_audio_codec
    TranscodeChangeCode.EXTRA_AUDIO_TRACKS_REMOVED -> R.string.transcode_change_audio_tracks
    TranscodeChangeCode.SUBTITLES_NOT_EMBEDDED -> R.string.transcode_change_subtitles
    TranscodeChangeCode.HDR_TO_SDR -> R.string.transcode_change_hdr
    TranscodeChangeCode.FRAME_RATE_CAPPED -> R.string.transcode_change_frame_rate
}

@Composable
private fun TaskList(
    tasks: List<ProcessingTaskItem>,
    onPause: (ProcessingTaskId) -> Unit,
    onResume: (ProcessingTaskId) -> Unit,
    onCancel: (ProcessingTaskId) -> Unit,
    onRetry: (ProcessingTaskId) -> Unit,
    onOpenOutput: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier,
) {
    if (tasks.isEmpty()) {
        YingLiEmptyState(
            title = stringResource(R.string.processing_empty_title),
            message = stringResource(R.string.processing_empty_message),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
        item {
            YingLiButton(
                text = stringResource(R.string.processing_clear_history),
                onClick = onClearHistory,
                leadingIcon = YingLiIcon.WARNING,
            )
        }
        items(tasks, key = { it.task.id.value }) { item ->
            TaskItem(item, onPause, onResume, onCancel, onRetry, onOpenOutput)
        }
    }
}

@Composable
private fun TaskItem(
    item: ProcessingTaskItem,
    onPause: (ProcessingTaskId) -> Unit,
    onResume: (ProcessingTaskId) -> Unit,
    onCancel: (ProcessingTaskId) -> Unit,
    onRetry: (ProcessingTaskId) -> Unit,
    onOpenOutput: (String) -> Unit,
) {
    val family = when (item.presentation.tone) {
        ProcessingTone.STEEL_BLUE -> YingLiTheme.functional.info
        ProcessingTone.AMBER -> YingLiTheme.functional.warning
        ProcessingTone.GREEN -> YingLiTheme.functional.success
        ProcessingTone.RED -> YingLiTheme.functional.error
        ProcessingTone.NEUTRAL -> null
    }
    Surface(
        color = family?.container ?: YingLiTheme.colors.surfaceComponent,
        contentColor = family?.onContainer ?: YingLiTheme.colors.textPrimary,
        shape = YingLiTheme.components.componentCorner,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            Text(item.task.operationKey.substringAfter('|'), style = MaterialTheme.typography.titleMedium)
            Text(item.task.state.label(), style = MaterialTheme.typography.bodyMedium)
            item.task.progress?.let { progress ->
                if (item.presentation.determinate) {
                    LinearProgressIndicator(
                        progress = { progress.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.processing_percent, ((progress.fraction ?: 0f) * 100).toInt()))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.processing_indeterminate))
                }
            }
            item.task.errorCode?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                if (ProcessingAction.PAUSE in item.presentation.actions) {
                    YingLiButton(stringResource(R.string.processing_pause), { onPause(item.task.id) }, leadingIcon = YingLiIcon.PAUSE)
                }
                if (ProcessingAction.RESUME in item.presentation.actions) {
                    YingLiButton(stringResource(R.string.processing_resume), { onResume(item.task.id) }, leadingIcon = YingLiIcon.PLAY)
                }
                if (ProcessingAction.CANCEL in item.presentation.actions) {
                    YingLiButton(stringResource(R.string.processing_cancel), { onCancel(item.task.id) }, leadingIcon = YingLiIcon.WARNING)
                }
                if (ProcessingAction.RETRY in item.presentation.actions) {
                    YingLiButton(stringResource(R.string.processing_retry), { onRetry(item.task.id) }, leadingIcon = YingLiIcon.REPLAY)
                }
                if (ProcessingAction.OPEN_OUTPUT in item.presentation.actions) {
                    val token = item.task.outputToken
                    YingLiButton(
                        stringResource(R.string.processing_open_output),
                        { token?.let(onOpenOutput) },
                        enabled = token != null,
                        leadingIcon = YingLiIcon.LIBRARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipProjectList(
    state: ProcessingUiState,
    onCreateProject: (LibraryMedia) -> Unit,
    onOpenProject: (ClipProjectId) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
        item { Text(stringResource(R.string.processing_clip_projects), style = MaterialTheme.typography.titleLarge) }
        items(state.clipProjects, key = { it.id.value }) { project ->
            Surface(
                onClick = { onOpenProject(project.id) },
                color = YingLiTheme.colors.surfaceComponent,
                shape = YingLiTheme.components.componentCorner,
            ) {
                Column(Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding)) {
                    Text(stringResource(R.string.processing_clip_project_name, project.segments.size))
                    Text(project.exportMode.name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text(stringResource(R.string.processing_create_from_video), style = MaterialTheme.typography.titleLarge) }
        items(state.media.take(MAX_SOURCE_CHOICES), key = { "source-${it.id.value}" }) { media ->
            YingLiButton(
                text = media.title,
                onClick = { onCreateProject(media) },
                enabled = media.durationMillis?.let { it > 0 } == true,
                leadingIcon = YingLiIcon.SCREENSHOT,
            )
        }
    }
}

@Composable
private fun ClipEditor(
    project: seeyuer.yingli.player.domain.clips.ClipProject,
    exporting: Boolean,
    timelineFrames: List<TimelineFrame>,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onUpdate: (ClipSegmentId, Long, Long, String?) -> Unit,
    onDuplicate: (ClipSegmentId) -> Unit,
    onToggle: (ClipSegmentId) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDeleteSelected: () -> Unit,
    onUndo: () -> Unit,
    onSetMode: (ClipExportMode) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                YingLiButton(stringResource(R.string.action_back), onClose, leadingIcon = YingLiIcon.BACK)
                YingLiButton(stringResource(R.string.processing_undo), onUndo, leadingIcon = YingLiIcon.REPLAY)
            }
        }
        item {
            Text(stringResource(R.string.processing_clip_editor), style = MaterialTheme.typography.titleLarge)
            YingLiSegmentedControl(
                options = listOf(stringResource(R.string.processing_fast), stringResource(R.string.processing_accurate)),
                selectedIndex = project.exportMode.ordinal,
                onSelected = { onSetMode(ClipExportMode.entries[it]) },
            )
        }
        if (timelineFrames.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                    items(timelineFrames, key = TimelineFrame::positionMillis) { frame ->
                        AsyncImage(
                            model = frame.cacheKey,
                            contentDescription = stringResource(R.string.processing_timeline_frame, frame.positionMillis),
                            modifier = Modifier.width(TIMELINE_FRAME_WIDTH).aspectRatio(16f / 9f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        items(project.segments, key = { it.id.value }) { segment ->
            ClipSegmentEditor(project.sourceDurationMillis, segment, onUpdate, onDuplicate, onToggle)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                YingLiButton(stringResource(R.string.processing_add_segment), onAdd, leadingIcon = YingLiIcon.SUCCESS)
                YingLiButton(
                    stringResource(R.string.processing_select_all),
                    { onSelectAll(true) },
                    leadingIcon = YingLiIcon.SUCCESS,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                YingLiButton(
                    stringResource(R.string.processing_delete_selected),
                    onDeleteSelected,
                    leadingIcon = YingLiIcon.WARNING,
                )
                YingLiButton(
                    stringResource(R.string.processing_export_selected),
                    onExport,
                    enabled = project.segments.any(ClipSegment::selected) && !exporting,
                    leadingIcon = YingLiIcon.BACKUP_EXPORT,
                )
            }
        }
    }
}

@Composable
private fun ClipSegmentEditor(
    durationMillis: Long,
    segment: ClipSegment,
    onUpdate: (ClipSegmentId, Long, Long, String?) -> Unit,
    onDuplicate: (ClipSegmentId) -> Unit,
    onToggle: (ClipSegmentId) -> Unit,
) {
    val durationSeconds = durationMillis / 1_000f
    Surface(color = YingLiTheme.colors.surfaceComponent, shape = YingLiTheme.components.componentCorner) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            YingLiCheckbox(segment.name, segment.selected, { onToggle(segment.id) })
            YingLiTextField(
                value = segment.name,
                onValueChange = { onUpdate(segment.id, segment.startMillis, segment.endMillis, it) },
                label = stringResource(R.string.processing_segment_name),
            )
            RangeSlider(
                value = (segment.startMillis / 1_000f)..(segment.endMillis / 1_000f),
                onValueChange = { range ->
                    onUpdate(segment.id, (range.start * 1_000).toLong(), (range.endInclusive * 1_000).toLong(), null)
                },
                valueRange = 0f..durationSeconds,
                modifier = Modifier.fillMaxWidth().heightIn(min = YingLiTheme.components.minimumTouchTarget),
            )
            Text(stringResource(R.string.processing_segment_range, segment.startMillis, segment.endMillis))
            YingLiButton(
                text = stringResource(R.string.processing_duplicate_segment),
                onClick = { onDuplicate(segment.id) },
                leadingIcon = YingLiIcon.SUCCESS,
            )
        }
    }
}

@Composable
private fun ProcessingTaskState.label(): String = stringResource(when (this) {
    ProcessingTaskState.QUEUED -> R.string.processing_state_queued
    ProcessingTaskState.PREPARING -> R.string.processing_state_preparing
    ProcessingTaskState.RUNNING -> R.string.processing_state_running
    ProcessingTaskState.PAUSED -> R.string.processing_state_paused
    ProcessingTaskState.CANCELING -> R.string.processing_state_canceling
    ProcessingTaskState.SUCCEEDED -> R.string.processing_state_succeeded
    ProcessingTaskState.FAILED -> R.string.processing_state_failed
    ProcessingTaskState.CANCELED -> R.string.processing_state_canceled
})

private const val MAX_SOURCE_CHOICES = 20
private val TIMELINE_FRAME_WIDTH = 128.dp
