package seeyuer.yingli.player.feature.organize

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidate
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionPlan
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionResult
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.Tag
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.domain.organize.TagId
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 「保留项选择」（设计稿 §14.7 第 1 项）。
 *
 * 三件事必须同时成立，缺一个都会产出非法计划：
 * ① 默认**不勾选**任何待删项；② 保留项自己不能被勾；③ 删除计划里的
 * `keepLocationIds` 是**具体那一份**，而不是「剩下的都算保留」的事后解释。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeViewModelDuplicateKeeperTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the recommended keeper cannot be moved to the trash`() = runTest {
        val group = group("a" to "a.mp4", "b" to "b (1).mp4")
        val viewModel = viewModel(group)
        val collect = subscribe(viewModel)
        runCurrent()

        val keeper = viewModel.state.value.duplicateKeepers
        assertTrue("用户还没选过，状态里不该有保留项", keeper.isEmpty())
        val recommended = group.sortedCandidates().first { candidate -> candidate.fileName == "a.mp4" }
        viewModel.toggleDuplicateTrash(group.id, recommended.locationId)
        runCurrent()

        assertEquals(
            "推荐保留项被点成待删后，选择集必须仍然为空",
            emptySet<MediaLocationId>(),
            viewModel.state.value.duplicateSelections[group.id].orEmpty(),
        )
        collect.cancel()
    }

    @Test
    fun `an explicit keeper is excluded from the delete plan`() = runTest {
        val group = group("a" to "a.mp4", "b" to "b (1).mp4")
        val executor = RecordingExecutor()
        val viewModel = viewModel(group, executor)
        val collect = subscribe(viewModel)
        runCurrent()

        val candidates = group.sortedCandidates()
        val keep = candidates.first { it.fileName == "b (1).mp4" }
        val trash = candidates.first { it.fileName == "a.mp4" }
        viewModel.setDuplicateKeeper(group.id, keep.locationId)
        viewModel.toggleDuplicateTrash(group.id, trash.locationId)
        runCurrent()
        assertEquals(setOf(trash.locationId), viewModel.state.value.duplicateSelections[group.id])

        viewModel.requestDuplicateDeletion(group.id)
        viewModel.confirmDuplicateDeletion()
        runCurrent()

        val plan = requireNotNull(executor.plan)
        assertEquals(setOf(keep.locationId), plan.keepLocationIds)
        assertEquals(setOf(trash.locationId), plan.trashLocationIds)
        collect.cancel()
    }

    @Test
    fun `choosing a keeper clears its pending trash mark`() = runTest {
        val group = group("a" to "a.mp4", "b" to "b (1).mp4")
        val viewModel = viewModel(group)
        val collect = subscribe(viewModel)
        runCurrent()

        // b 不是推荐保留项（推荐的是 a），所以它可以先被勾成待删。
        val target = group.sortedCandidates().first { it.fileName == "b (1).mp4" }
        viewModel.toggleDuplicateTrash(group.id, target.locationId)
        runCurrent()
        assertEquals(setOf(target.locationId), viewModel.state.value.duplicateSelections[group.id])

        viewModel.setDuplicateKeeper(group.id, target.locationId)
        runCurrent()

        assertEquals(setOf(target.locationId), setOf(viewModel.state.value.duplicateKeepers.getValue(group.id)))
        assertEquals(
            "保留项不能同时躺在待删集合里",
            emptySet<MediaLocationId>(),
            viewModel.state.value.duplicateSelections[group.id].orEmpty(),
        )
        collect.cancel()
    }

    @Test
    fun `deleting needs at least one trash selection`() = runTest {
        val group = group("a" to "a.mp4", "b" to "b (1).mp4")
        val executor = RecordingExecutor()
        val viewModel = viewModel(group, executor)
        val collect = subscribe(viewModel)
        runCurrent()

        viewModel.requestDuplicateDeletion(group.id)
        viewModel.confirmDuplicateDeletion()
        runCurrent()

        assertNull("没有勾任何待删项时不该产生计划", executor.plan)
        collect.cancel()
    }

    private fun TestScope.subscribe(viewModel: OrganizeViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

    private fun viewModel(
        group: DuplicateGroup,
        executor: DuplicateDeletionExecutor = RecordingExecutor(),
    ): OrganizeViewModel = OrganizeViewModel(
        repository = FakeOrganizeRepository(),
        duplicateRepository = FakeDuplicateRepository(listOf(group)),
        duplicateDeletionExecutor = executor,
        clock = AppClock { Instant.ofEpochMilli(NOW) },
    )

    private fun group(vararg members: Pair<String, String>): DuplicateGroup = DuplicateGroup(
        contentHash = "b".repeat(64),
        sizeBytes = GROUP_SIZE_BYTES,
        candidates = members.mapIndexed { index, (name, fileName) ->
            DuplicateCandidate(
                locationId = MediaLocationId("loc-$name"),
                mediaItemId = MediaItemId(name),
                uri = MediaUri("content://media/$name"),
                fileName = fileName,
                relativePath = "Movies",
                sourceMode = MediaSourceMode.MEDIA_STORE,
                sizeBytes = GROUP_SIZE_BYTES,
                // 排序器按 lastSeen/modified 降序推荐保留项：越早的越靠后，
                // 所以「a」是推荐保留项、「b」是看起来像副本的那份。
                modifiedEpochMillis = NOW - index * 1_000L,
                durationMillis = 1_000,
                width = 1920,
                height = 1080,
                missingScanCount = 0,
                lastSeenEpochMillis = NOW - index * 1_000L,
            )
        },
    )

    private class RecordingExecutor : DuplicateDeletionExecutor {
        var plan: DuplicateDeletionPlan? = null

        override suspend fun execute(plan: DuplicateDeletionPlan): DuplicateDeletionResult {
            this.plan = plan
            return DuplicateDeletionResult.Completed(plan.trashLocationIds.size)
        }
    }

    private class FakeDuplicateRepository(groups: List<DuplicateGroup>) : DuplicateRepository {
        private val state = MutableStateFlow(groups)

        override val groups: Flow<List<DuplicateGroup>> = state

        override suspend fun ignore(group: DuplicateGroup, ignoredAtEpochMillis: Long) = Unit
    }

    private class FakeOrganizeRepository : OrganizeRepository {
        override val snapshot = MutableStateFlow(OrganizeSnapshot())

        override suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult =
            OrganizeMutationResult.Success

        override suspend fun updateTag(tag: Tag) = OrganizeMutationResult.Success
        override suspend fun deleteTag(id: TagId) = OrganizeMutationResult.Success
        override suspend fun addTags(mediaIds: Set<MediaItemId>, tagIds: Set<TagId>) = OrganizeMutationResult.Success
        override suspend fun setFavorite(mediaIds: Set<MediaItemId>, favorite: Boolean) = OrganizeMutationResult.Success
        override suspend fun createPlaylist(name: String, mediaIds: List<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createCollection(name: String, mediaIds: Set<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createSmartCollection(name: String, filter: FilterExpression) = OrganizeMutationResult.Success
    }

    private companion object {
        const val NOW = 1_770_000_000_000L
        const val GROUP_SIZE_BYTES = 4_000L
    }
}
