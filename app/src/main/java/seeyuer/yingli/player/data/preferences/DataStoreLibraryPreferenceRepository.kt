package seeyuer.yingli.player.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.data.preferences.LibraryLayoutPreference
import seeyuer.yingli.player.data.preferences.ThemeRepository
import seeyuer.yingli.player.data.preferences.LibrarySortPreference
import seeyuer.yingli.player.data.preferences.SortDirectionPreference
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec

class DataStoreLibraryPreferenceRepository(
    private val repository: ThemeRepository,
) : LibraryPreferenceRepository {
    override val preference: Flow<LibraryDisplayPreference> = repository.settings.map { settings ->
        LibraryDisplayPreference(
            viewMode = settings.libraryLayout.toDomain(),
            thumbnailScale = settings.thumbnailScale,
            sort = SortSpec(
                LibrarySortField.valueOf(settings.librarySort.name),
                SortDirection.valueOf(settings.librarySortDirection.name),
            ),
        )
    }

    override suspend fun setViewMode(mode: LibraryViewMode) {
        repository.update { it.copy(libraryLayout = mode.toPreference()) }
    }

    override suspend fun setThumbnailScale(scale: Float) {
        repository.update {
            it.copy(thumbnailScale = scale.coerceIn(
                LibraryDisplayPreference.MIN_SCALE,
                LibraryDisplayPreference.MAX_SCALE,
            ))
        }
    }

    override suspend fun setSort(sort: SortSpec) {
        repository.update {
            it.copy(
                librarySort = LibrarySortPreference.valueOf(sort.field.name),
                librarySortDirection = SortDirectionPreference.valueOf(sort.direction.name),
            )
        }
    }

    private fun LibraryLayoutPreference.toDomain(): LibraryViewMode = LibraryViewMode.valueOf(name)
    private fun LibraryViewMode.toPreference(): LibraryLayoutPreference = LibraryLayoutPreference.valueOf(name)
}
