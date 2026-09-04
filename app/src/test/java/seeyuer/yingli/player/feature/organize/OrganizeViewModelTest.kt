package seeyuer.yingli.player.feature.organize

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.Tag
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.domain.organize.TagId
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `tag editor preserves selected color and closes after save`() = runTest {
        val repository = FakeOrganizeRepository()
        val viewModel = OrganizeViewModel(repository)

        viewModel.openEditor(OrganizeEditorKind.TAG)
        viewModel.setName("旅行")
        viewModel.setTagColor(TagColor.BLUE)
        viewModel.save()

        assertEquals("旅行" to TagColor.BLUE, repository.createdTag)
        assertEquals(null, viewModel.state.value.editorKind)
    }

    private class FakeOrganizeRepository : OrganizeRepository {
        override val snapshot = MutableStateFlow(OrganizeSnapshot())
        var createdTag: Pair<String, TagColor>? = null
        override suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult {
            createdTag = name to color
            return OrganizeMutationResult.Success
        }
        override suspend fun updateTag(tag: Tag) = OrganizeMutationResult.Success
        override suspend fun deleteTag(id: TagId) = OrganizeMutationResult.Success
        override suspend fun addTags(mediaIds: Set<MediaItemId>, tagIds: Set<TagId>) = OrganizeMutationResult.Success
        override suspend fun setFavorite(mediaIds: Set<MediaItemId>, favorite: Boolean) = OrganizeMutationResult.Success
        override suspend fun createPlaylist(name: String, mediaIds: List<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createCollection(name: String, mediaIds: Set<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createSmartCollection(name: String, filter: FilterExpression) = OrganizeMutationResult.Success
    }
}
