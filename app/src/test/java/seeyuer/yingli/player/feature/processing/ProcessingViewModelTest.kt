package seeyuer.yingli.player.feature.processing

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipExportPlan
import seeyuer.yingli.player.domain.clips.ClipExportQueue
import seeyuer.yingli.player.domain.clips.ClipPreset
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipProjectRepository
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.clips.TimelineFrame
import seeyuer.yingli.player.domain.clips.TimelineFrameProvider
import seeyuer.yingli.player.domain.clips.TimelineFrameRequest
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.processing.DeviceMediaCapabilities
import seeyuer.yingli.player.domain.processing.MediaCapabilityProbe
import seeyuer.yingli.player.domain.processing.ProcessingController
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingQueue
import seeyuer.yingli.player.domain.processing.ProcessingReduction
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskEvent
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.SourceMediaInfo
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 「源 + 区间 → 单段项目」在编辑器侧的**golden 测试**（设计稿 §14.6 步骤 10、§15.2）。
 *
 * 步骤 10 是一次纯重构：`createProject` 从手写 `ClipProject(...)` 改成调 `ClipProject.forRange(...)`。
 * 重构要么字段级完全等价，要么就是行为变更 —— 所以这里把**改造前那份字面量**留在期望值里，
 * 逐字段比对（不是"看起来差不多"）。
 *
 * 为什么这些用例不能只靠 `ClipContractsTest`：工厂本身合法并不说明调用方喂给它的参数没变
 * （比如区间终点、段名、模式、预设在迁移中被改掉，工厂测试照样全绿）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `creating a project is field identical to the pre refactor literal construction`() = runTest {
        val harness = Harness(backgroundScope)
        val media = media(durationMillis = 30_000)

        harness.viewModel.createProject(media)
        advanceUntilIdle()

        val saved = harness.clipRepository.saved.single()
        assertEquals(
            ClipProject(
                id = ClipProjectId("project-1"),
                sourceMediaId = media.id,
                sourceLocationId = media.locationId,
                sourceDurationMillis = 30_000,
                segments = listOf(
                    ClipSegment(
                        id = ClipSegmentId("segment-1"),
                        startMillis = 0,
                        endMillis = 10_000,
                        name = "Clip 1",
                        selected = true,
                    ),
                ),
                exportMode = ClipExportMode.FAST,
                preset = ClipPreset.SOURCE_QUALITY,
                createdAtEpochMillis = NOW_MILLIS,
                updatedAtEpochMillis = NOW_MILLIS,
            ),
            saved,
        )
        // 导出计划的命名也随之逐字不变（用户可见的落点文件名的最后一道防线）。
        assertEquals("01-Clip 1.mp4", ClipExportPlan.from(saved).items.single().displayName)
    }

    @Test
    fun `a source shorter than the default segment is not extended past its end`() = runTest {
        val harness = Harness(backgroundScope)

        harness.viewModel.createProject(media(durationMillis = 4_000))
        advanceUntilIdle()

        assertEquals(4_000L, harness.clipRepository.saved.single().segments.single().endMillis)
    }

    @Test
    fun `a source without a usable duration produces no project at all`() = runTest {
        val harness = Harness(backgroundScope)

        harness.viewModel.createProject(media(durationMillis = null))
        harness.viewModel.createProject(media(durationMillis = 0))
        advanceUntilIdle()

        assertEquals(emptyList<ClipProject>(), harness.clipRepository.saved)
        assertTrue(harness.generator.consumed().isEmpty())
    }

    @Test
    fun `adding a segment starts where the previous one ended`() = runTest {
        val harness = Harness(backgroundScope)
        harness.viewModel.createProject(media(durationMillis = 30_000))
        advanceUntilIdle()

        harness.viewModel.addSegment()
        advanceUntilIdle()

        val project = harness.editorProject()
        assertEquals(listOf(0L to 10_000L, 10_000L to 20_000L), project.segments.map { it.startMillis to it.endMillis })
        assertEquals(listOf("Clip 1", "Clip 2"), project.segments.map { it.name })
    }

    @Test
    fun `adding a segment at the very end of the source is a no op`() = runTest {
        val harness = Harness(backgroundScope)
        harness.viewModel.createProject(media(durationMillis = 6_000))
        advanceUntilIdle()

        harness.viewModel.addSegment()
        advanceUntilIdle()

        assertEquals(1, harness.editorProject().segments.size)
    }

    private fun media(durationMillis: Long?) = LibraryMedia(
        id = MediaItemId("media-1"),
        locationId = MediaLocationId("location-1"),
        uri = MediaUri("content://media/1"),
        title = "影片",
        fileName = "影片.mp4",
        folderAlias = "Movies",
        extension = "mp4",
        durationMillis = durationMillis,
        width = 1_920,
        height = 1_080,
        modifiedEpochMillis = 1,
        playbackPositionMillis = 0,
        completed = false,
    )

    private inner class Harness(scope: CoroutineScope) {
        val clipRepository = FakeClipProjectRepository()
        val generator = SequentialIdGenerator("project-1", "segment-1", "segment-2", "segment-3")
        val viewModel = ProcessingViewModel(
            processingRepository = FakeProcessingRepository(),
            controller = object : ProcessingController {
                override fun pause(taskId: ProcessingTaskId) = Unit
                override fun resume(taskId: ProcessingTaskId) = Unit
                override fun cancel(taskId: ProcessingTaskId) = Unit
                override fun retry(taskId: ProcessingTaskId) = Unit
                override fun failRunning(errorCode: String) = Unit
            },
            clipRepository = clipRepository,
            exportQueue = object : ClipExportQueue {
                override suspend fun enqueue(project: ClipProject) = ProcessingProjectId("project")
            },
            libraryRepository = FakeLibraryRepository(),
            idGenerator = generator,
            clock = { Instant.ofEpochMilli(NOW_MILLIS) },
            timelineFrameProvider = object : TimelineFrameProvider {
                override suspend fun frames(request: TimelineFrameRequest) = emptyList<TimelineFrame>()
            },
            mediaCapabilityProbe = object : MediaCapabilityProbe {
                override suspend fun deviceCapabilities() =
                    DeviceMediaCapabilities(encoders = emptyList(), capturedAtEpochMillis = 0)

                override suspend fun source(
                    mediaId: MediaItemId,
                    uri: String,
                    displayName: String,
                ): SourceMediaInfo? = null
            },
            processingQueue = object : ProcessingQueue {
                override val pendingPlans = emptyFlow<Map<ProcessingProjectId, ProcessingPlan>>()

                override suspend fun enqueue(
                    plan: ProcessingPlan,
                    destructiveChangesConfirmed: Boolean,
                ) = ProcessingProjectId("project")

                override suspend fun plan(projectId: ProcessingProjectId): ProcessingPlan? = null
            },
            availableBytes = { Long.MAX_VALUE },
        )

        init {
            // `state` 是 `WhileSubscribed`：没人订阅时 `state.value` 永远停在初始值。
            scope.launch { viewModel.state.collect() }
        }

        fun editorProject(): ClipProject = requireNotNull(viewModel.state.value.editor).project
    }

    private class FakeClipProjectRepository : ClipProjectRepository {
        private val mutableProjects = MutableStateFlow<List<ClipProject>>(emptyList())
        val saved = mutableListOf<ClipProject>()
        override val projects: Flow<List<ClipProject>> = mutableProjects
        override suspend fun project(id: ClipProjectId): ClipProject? = saved.firstOrNull { it.id == id }
        override suspend fun save(project: ClipProject) {
            saved += project
            mutableProjects.value = saved.toList()
        }

        override suspend fun delete(id: ClipProjectId) = Unit
    }

    private class FakeLibraryRepository : LibraryRepository {
        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> =
            flowOf(LibraryResult.Success(LibraryPage(emptyList(), null, 0)))

        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> =
            LibraryResult.Success(LibraryPage(emptyList(), null, 0))
    }

    /** 逐次吐出预置 id：测试因此能钉住"哪个 id 用在项目、哪个用在段"，也挡住意外的额外取用。 */
    private class SequentialIdGenerator(private vararg val ids: String) : IdGenerator {
        private var index = 0
        val used = mutableListOf<String>()
        override fun newId(): String {
            if (index >= ids.size) error("测试没有为第 ${index + 1} 个 id 预留值")
            return ids[index++].also(used::add)
        }

        fun consumed(): List<String> = used.toList()
    }

    private class FakeProcessingRepository : ProcessingRepository {
        override val tasks: Flow<List<ProcessingTask>> = flowOf(emptyList())
        override suspend fun projects(): List<ProcessingProject> = emptyList()
        override suspend fun create(project: ProcessingProject, tasks: List<ProcessingTask>) = Unit
        override suspend fun apply(
            taskId: ProcessingTaskId,
            event: ProcessingTaskEvent,
        ): ProcessingReduction = error("本测试不驱动任务状态机")
        override suspend fun recoverInterrupted() = Unit
        override suspend fun clearTerminal() = Unit
    }

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
