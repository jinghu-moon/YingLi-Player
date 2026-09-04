package seeyuer.yingli.player.feature.processing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.clips.ClipEditState
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.processing.ProcessingOutput
import seeyuer.yingli.player.domain.processing.ProcessingPresentationMapper
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.ProcessingTaskState

class ProcessingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun taskMatrixUsesTextActionsAndIndeterminateProgressWithoutFakePercent() {
        val tasks = listOf(
            task("running", ProcessingTaskState.RUNNING, progress = ProcessingProgress("probe", 12)),
            task("failed", ProcessingTaskState.FAILED, error = "ENCODER_FAILED"),
            task(
                "done",
                ProcessingTaskState.SUCCEEDED,
                output = ProcessingOutput("clip.mp4", "content://output/1"),
            ),
        ).map { ProcessingTaskItem(it, ProcessingPresentationMapper.map(it)) }

        setScreen(ProcessingUiState(tasks = tasks))

        composeRule.onNodeWithText("正在处理，暂时无法估算总量").assertIsDisplayed()
        composeRule.onNodeWithText("暂停").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("打开输出").assertIsDisplayed()
    }

    @Test
    fun clipEditorExposesModeRangeSelectionAndBatchCommands() {
        val segment = ClipSegment(ClipSegmentId("segment-1"), 0, 5_000, "开场")
        val project = ClipProject(
            ClipProjectId("clip-project"),
            MediaItemId("media-1"),
            MediaLocationId("location-1"),
            20_000,
            listOf(segment),
            createdAtEpochMillis = 1,
        )

        setScreen(ProcessingUiState(editor = ClipEditState(project)))

        composeRule.onNodeWithText("片段编辑器").assertIsDisplayed()
        composeRule.onNodeWithText("快速").assertIsDisplayed()
        composeRule.onNodeWithText("精确").assertIsDisplayed()
        composeRule.onNode(hasText("开场") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("添加片段").assertIsDisplayed()
        composeRule.onNodeWithText("导出选中").assertIsDisplayed()
    }

    private fun setScreen(state: ProcessingUiState) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                ProcessingScreen(
                    state = state,
                    onCreateProject = {},
                    onOpenProject = {},
                    onCloseEditor = {},
                    onAddSegment = {},
                    onUpdateSegment = { _, _, _, _ -> },
                    onDuplicateSegment = {},
                    onToggleSegment = {},
                    onSelectAll = {},
                    onDeleteSelected = {},
                    onUndo = {},
                    onSetExportMode = {},
                    onExport = {},
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    onRetry = {},
                    onClearHistory = {},
                    onTranscodePreset = {},
                    onPlanTranscode = {},
                    onDismissTranscode = {},
                    onEnqueueTranscode = {},
                    onOpenOutput = {},
                )
            }
        }
    }

    private fun task(
        id: String,
        state: ProcessingTaskState,
        progress: ProcessingProgress? = null,
        error: String? = null,
        output: ProcessingOutput? = null,
    ) = ProcessingTask(
        ProcessingTaskId(id),
        ProcessingProjectId("project-1"),
        state = state,
        progress = progress,
        errorCode = error,
        outputDisplayName = output?.displayName,
        outputToken = output?.token,
        createdAtEpochMillis = 1,
    )
}
