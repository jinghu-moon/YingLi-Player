package seeyuer.yingli.player.app.library

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.flowOn
import seeyuer.yingli.player.core.database.LibraryMediaRow
import seeyuer.yingli.player.core.database.MediaItemEntity
import seeyuer.yingli.player.core.database.MediaItemLocationEntity
import seeyuer.yingli.player.core.database.MediaLocationEntity
import seeyuer.yingli.player.core.database.MediaSourceEntity
import seeyuer.yingli.player.core.database.MediaTagEntity
import seeyuer.yingli.player.core.database.PlaybackHistoryEntity
import seeyuer.yingli.player.core.database.TrashEntryEntity
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

@OptIn(FlowPreview::class)
class RoomLibraryRepository(
    database: YingLiDatabase,
    private val dispatchers: AppDispatchers,
) : LibraryRepository, SearchRepository {
    private val libraryDao = database.libraryDao()
    private val catalogDao = database.mediaCatalogDao()
    private val countCache = ConcurrentHashMap<String, Int>()

    override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> = combine(
        libraryDao.observePage(buildQuery(query, countOnly = false)).conflate().debounce(150),
        libraryDao.observeCount(buildQuery(query.copy(cursor = null), countOnly = true)).conflate().debounce(150),
        catalogDao.observeTags().distinctUntilChanged().conflate().debounce(150),
    ) { rows, count, tags ->
        countCache[countKey(query)] = count
        LibraryResult.Success(toPage(query, rows, count, tags))
    }
        .flowOn(dispatchers.io)

    override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> = try {
        val rows = libraryDao.page(buildQuery(query, countOnly = false))
        val count = if (query.cursor != null) {
            countCache[countKey(query)] ?: libraryDao.count(buildQuery(query.copy(cursor = null), countOnly = true))
        } else {
            libraryDao.count(buildQuery(query.copy(cursor = null), countOnly = true))
        }
        countCache[countKey(query)] = count
        LibraryResult.Success(toPage(query, rows, count, catalogDao.tagsSnapshot()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LibraryResult.RetryableFailure
    }

    override suspend fun search(query: LibraryQuery): LibraryResult<LibraryPage> = query(query)

    private fun toPage(
        query: LibraryQuery,
        rows: List<LibraryMediaRow>,
        count: Int,
        tags: List<MediaTagEntity>,
    ): LibraryPage {
        val tagsByMedia = tags.groupBy(MediaTagEntity::mediaItemId)
            .mapValues { (_, values) -> values.map(MediaTagEntity::tag).toSet() }
        // Fetch one sentinel row so a full final page does not produce a bogus cursor.
        val hasMore = rows.size > query.pageSize
        val items = rows.take(query.pageSize).map { it.toModel(tagsByMedia[it.id].orEmpty()) }
        val next = items.lastOrNull()?.takeIf { hasMore }
            ?.let { it.cursor(query) }
        return LibraryPage(items, next, count)
    }

    private fun countKey(query: LibraryQuery): String = query.copy(cursor = null).toString()

    private fun buildQuery(query: LibraryQuery, countOnly: Boolean): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = mutableListOf(
            "trash_entries.mediaItemId IS NULL",
            "latest_location.rn = 1",
        )
        val keyword = query.normalizedKeyword.trim().lowercase()
        if (keyword.isNotEmpty()) {
            where += "(LOWER(media_items.title) LIKE ? OR LOWER(media_locations.fileName) LIKE ? OR LOWER(media_sources.displayName) LIKE ? OR EXISTS (SELECT 1 FROM media_tags searchTag WHERE searchTag.mediaItemId = media_items.id AND LOWER(searchTag.tag) LIKE ?))"
            val pattern = "%$keyword%"
            repeat(4) { args += pattern }
        }
        if (query.group == LibraryGroup.UNWATCHED) where += "media_items.completed = 0 AND media_items.playbackPositionMillis = 0"
        query.filter.minimumWidth?.let { where += "COALESCE(media_locations.width, 0) >= ?"; args += it }
        query.filter.duration.minimumMillis?.let { where += "COALESCE(media_locations.durationMillis, -1) >= ?"; args += it }
        query.filter.duration.maximumMillis?.let { where += "COALESCE(media_locations.durationMillis, -1) <= ?"; args += it }
        if (query.filter.extensions.isNotEmpty()) {
            where += query.filter.extensions.joinToString(" OR ", prefix = "(") { "LOWER(media_locations.fileName) LIKE ?" } + ")"
            query.filter.extensions.forEach { args += "%.${it.lowercase()}" }
        }
        val includedTags = query.filter.includedTags
        if (includedTags.isNotEmpty()) {
            if (query.filter.tagMatchMode == seeyuer.yingli.player.domain.library.TagMatchMode.ANY) {
                where += "EXISTS (SELECT 1 FROM media_tags includedTag WHERE includedTag.mediaItemId = media_items.id AND LOWER(includedTag.tag) IN (${includedTags.joinToString { "?" }}))"
                includedTags.forEach { args += it.lowercase() }
            } else {
                includedTags.forEach { tag ->
                    where += "EXISTS (SELECT 1 FROM media_tags includedTag WHERE includedTag.mediaItemId = media_items.id AND LOWER(includedTag.tag) = ?)"
                    args += tag.lowercase()
                }
            }
        }
        query.filter.excludedTags.forEach { tag ->
            where += "NOT EXISTS (SELECT 1 FROM media_tags excludedTag WHERE excludedTag.mediaItemId = media_items.id AND LOWER(excludedTag.tag) = ?)"
            args += tag.lowercase()
        }
        query.cursor?.let { cursor ->
            val column = query.sort.field.valueColumn
            val operator = if (query.sort.direction == SortDirection.ASCENDING) ">" else "<"
            val cursorValue: Any = when (query.sort.field) {
                LibrarySortField.NAME -> cursor.textValue.orEmpty()
                else -> cursor.longValue ?: 0L
            }
            where += "($column $operator ? OR ($column = ? AND media_items.id $operator ?))"
            args += cursorValue; args += cursorValue; args += cursor.mediaId.value
        }
        val select = if (countOnly) {
            // latest_location contains at most one row per media item, so DISTINCT is unnecessary.
            "SELECT COUNT(*)"
        } else {
            "SELECT media_items.id, media_items.title, media_items.playbackPositionMillis, media_items.completed, " +
                "media_locations.id AS locationId, media_locations.uri, media_locations.fileName, media_sources.displayName AS folderAlias, " +
                "media_locations.sizeBytes, media_locations.durationMillis, media_locations.width, media_locations.height, media_locations.modifiedEpochMillis, " +
                "COALESCE(playback_history.playCount, 0) AS playCount"
        }
        val sql = buildString {
            append("WITH latest_location AS (SELECT media_item_locations.mediaItemId, media_locations.id AS locationId, ")
            append("ROW_NUMBER() OVER (PARTITION BY media_item_locations.mediaItemId ")
            append("ORDER BY media_locations.lastSeenEpochMillis DESC, media_locations.id DESC) AS rn ")
            append("FROM media_item_locations INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId ")
            append("WHERE media_locations.missingScanCount = 0) ")
            append(select)
            append(" FROM media_items INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id")
            append(" INNER JOIN media_locations ON media_locations.id = latest_location.locationId")
            append(" INNER JOIN media_sources ON media_sources.id = media_locations.sourceId")
            append(" LEFT JOIN playback_history ON playback_history.mediaItemId = media_items.id")
            append(" LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id")
            append(" WHERE ").append(where.joinToString(" AND "))
            if (!countOnly) append(" ORDER BY ${query.sort.field.valueColumn} ${query.sort.direction.sql}, media_items.id ${query.sort.direction.sql} LIMIT ${query.pageSize + 1}")
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private val LibrarySortField.valueColumn: String get() = when (this) {
        LibrarySortField.NAME -> "LOWER(media_items.title)"
        LibrarySortField.RECENTLY_ADDED -> "media_locations.modifiedEpochMillis"
        LibrarySortField.DURATION -> "COALESCE(media_locations.durationMillis, -1)"
        LibrarySortField.PLAY_COUNT -> "COALESCE(playback_history.playCount, 0)"
    }

    private val SortDirection.sql: String get() = if (this == SortDirection.ASCENDING) "ASC" else "DESC"

    private fun LibraryMedia.cursor(query: LibraryQuery): LibraryCursor = when (query.sort.field) {
        LibrarySortField.NAME -> LibraryCursor(query.sort.field, query.sort.direction, id, textValue = title.lowercase())
        LibrarySortField.RECENTLY_ADDED -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = modifiedEpochMillis)
        LibrarySortField.DURATION -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = durationMillis ?: -1L)
        LibrarySortField.PLAY_COUNT -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = playCount.toLong())
    }

    private fun LibraryMediaRow.toModel(tags: Set<String>) = LibraryMedia(
        id = MediaItemId(id), locationId = MediaLocationId(locationId), uri = MediaUri(uri), title = title,
        fileName = fileName, folderAlias = folderAlias, extension = fileName.substringAfterLast('.', "").lowercase(),
        sizeBytes = sizeBytes, durationMillis = durationMillis, width = width, height = height,
        modifiedEpochMillis = modifiedEpochMillis, playbackPositionMillis = playbackPositionMillis,
        completed = completed, playCount = playCount, tags = tags,
    )
}
