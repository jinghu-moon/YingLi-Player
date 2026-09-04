package seeyuer.yingli.player.app

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.database.LibraryMediaRow
import seeyuer.yingli.player.core.database.MediaTagEntity
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.SearchRepository
import seeyuer.yingli.player.domain.library.SortDirection

class RoomLibraryRepository(
    database: YingLiDatabase,
    private val dispatchers: AppDispatchers,
) : LibraryRepository, SearchRepository {
    private val libraryDao = database.libraryDao()
    private val catalogDao = database.mediaCatalogDao()

    override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> = combine(
        libraryDao.observeRows(),
        catalogDao.observeTags(),
    ) { rows, tags -> execute(query, rows, tags) }
        .flowOn(dispatchers.io)

    override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> = try {
        val rows = libraryDao.rows()
        val tags = catalogDao.tagsSnapshot()
        execute(query, rows, tags)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LibraryResult.RetryableFailure
    }

    override suspend fun search(query: LibraryQuery): LibraryResult<LibraryPage> = query(query)

    private fun execute(
        query: LibraryQuery,
        rows: List<LibraryMediaRow>,
        tags: List<MediaTagEntity>,
    ): LibraryResult<LibraryPage> {
        val tagsByMedia = tags.groupBy(MediaTagEntity::mediaItemId)
            .mapValues { (_, values) -> values.map(MediaTagEntity::tag).toSet() }
        val keyword = query.normalizedKeyword.searchNormalized()
        val projected = rows.distinctBy(LibraryMediaRow::id)
            .map { row -> row.toModel(tagsByMedia[row.id].orEmpty()) }
            .asSequence()
            .filter { item ->
                keyword.isEmpty() || listOf(item.title, item.fileName, item.folderAlias)
                    .any { value -> keyword in value.searchNormalized() } ||
                    item.tags.any { tag -> keyword in tag.searchNormalized() }
            }
            .filter(query.filter::matches)
            .filter { item -> query.group != LibraryGroup.UNWATCHED || (!item.completed && item.playbackPositionMillis == 0L) }
            .sortedWith(query.comparator())
            .toList()
        val startIndex = query.cursor?.let { cursor ->
            val index = projected.indexOfFirst { it.id == cursor.mediaId && it.sortKey(query) == cursor.sortKey }
            if (index < 0) return LibraryResult.RetryableFailure
            index + 1
        } ?: 0
        val pageItems = projected.drop(startIndex).take(query.pageSize)
        val hasMore = startIndex + pageItems.size < projected.size
        val next = pageItems.lastOrNull()?.takeIf { hasMore }?.let { item ->
            LibraryCursor(item.sortKey(query), item.id)
        }
        return LibraryResult.Success(LibraryPage(pageItems, next, projected.size))
    }

    private fun LibraryQuery.comparator(): Comparator<LibraryMedia> {
        val base = when (sort.field) {
            LibrarySortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, LibraryMedia::title)
            LibrarySortField.RECENTLY_ADDED -> compareBy(LibraryMedia::modifiedEpochMillis)
            LibrarySortField.DURATION -> compareBy { it.durationMillis ?: -1L }
            LibrarySortField.PLAY_COUNT -> compareBy(LibraryMedia::playCount)
        }
        val directed = if (sort.direction == SortDirection.ASCENDING) base else base.reversed()
        return directed.thenBy { it.id.value }
    }

    private fun LibraryMedia.sortKey(query: LibraryQuery): String = when (query.sort.field) {
        LibrarySortField.NAME -> title.lowercase()
        LibrarySortField.RECENTLY_ADDED -> modifiedEpochMillis.toString()
        LibrarySortField.DURATION -> (durationMillis ?: -1L).toString()
        LibrarySortField.PLAY_COUNT -> playCount.toString()
    }

    private fun LibraryMediaRow.toModel(tags: Set<String>): LibraryMedia = LibraryMedia(
        id = MediaItemId(id),
        locationId = MediaLocationId(locationId),
        uri = MediaUri(uri),
        title = title,
        fileName = fileName,
        folderAlias = folderAlias,
        extension = fileName.substringAfterLast('.', "").lowercase(),
        sizeBytes = sizeBytes,
        durationMillis = durationMillis,
        width = width,
        height = height,
        modifiedEpochMillis = modifiedEpochMillis,
        playbackPositionMillis = playbackPositionMillis,
        completed = completed,
        playCount = playCount,
        tags = tags,
    )

    private fun String.searchNormalized(): String = Normalizer.normalize(trim(), Normalizer.Form.NFKC).lowercase()
}
