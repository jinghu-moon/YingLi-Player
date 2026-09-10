package seeyuer.yingli.player.data.library

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
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.data.room.LibraryMediaRow
import seeyuer.yingli.player.data.room.MediaItemEntity
import seeyuer.yingli.player.data.room.MediaItemLocationEntity
import seeyuer.yingli.player.data.room.MediaLocationEntity
import seeyuer.yingli.player.data.room.MediaSourceEntity
import seeyuer.yingli.player.data.room.MediaTagEntity
import seeyuer.yingli.player.data.room.PlaybackHistoryEntity
import seeyuer.yingli.player.data.room.TrashEntryEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryFolder
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.SearchRepository
import seeyuer.yingli.player.domain.library.SortDirection

@OptIn(FlowPreview::class)
class RoomLibraryRepository(
    private val database: YingLiDatabase,
    private val dispatchers: AppDispatchers,
) : LibraryPagingRepository, SearchRepository {
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
        LibraryResult.Success(page(query))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LibraryResult.RetryableFailure
    }

    override suspend fun search(query: LibraryQuery): LibraryResult<LibraryPage> = query(query)

    override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage {
        val rows = libraryDao.page(buildQuery(query, countOnly = false, direction = direction))
        val count = if (query.cursor != null) {
            countCache[countKey(query)] ?: libraryDao.count(buildQuery(query.copy(cursor = null), countOnly = true))
        } else {
            libraryDao.count(buildQuery(query.copy(cursor = null), countOnly = true))
        }
        countCache[countKey(query)] = count
        val pageRows = rows.take(query.pageSize)
        val tags = if (pageRows.isEmpty()) emptyList() else catalogDao.tagsForItems(pageRows.map(LibraryMediaRow::id))
        return toPage(query, rows, count, tags, direction)
    }

    override fun observeCount(query: LibraryQuery): Flow<Int> =
        libraryDao.observeCount(buildQuery(query.copy(cursor = null), countOnly = true))
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override fun observeFolderTreeVideoCount(path: String): Flow<Int> =
        libraryDao.observeCount(buildFolderTreeCountQuery(path))
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override fun observeInvalidations(): Flow<Unit> = database.invalidationTracker
        .createFlow(*OBSERVED_TABLES, emitInitialState = false)
        .map { }

    override suspend fun folders(query: LibraryQuery): List<LibraryFolder> {
        val sql = """
            WITH latest_location AS (
                SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                    ROW_NUMBER() OVER (
                        PARTITION BY media_item_locations.mediaItemId
                        ORDER BY CASE
                            WHEN TRIM(media_locations.relativePath, '/') = ?
                              OR substr(TRIM(media_locations.relativePath, '/'), 1, length(?) + 1) = ? || '/'
                            THEN 0 ELSE 1 END,
                            media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                    ) AS rn
                FROM media_item_locations
                INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
                WHERE media_locations.missingScanCount = 0
            ), child_locations AS (
                SELECT CASE
                           WHEN ? = '' THEN trim(media_locations.relativePath, '/')
                           ELSE substr(trim(media_locations.relativePath, '/'), length(?) + 2)
                       END AS remainder,
                       media_locations.sizeBytes,
                       COALESCE(media_locations.durationMillis, 0) AS durationMillis,
                       media_items.completed,
                       media_items.playbackPositionMillis,
                       playback_history.mediaItemId AS historyMediaId
                FROM media_items
                INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rn = 1
                INNER JOIN media_locations ON media_locations.id = latest_location.locationId
                LEFT JOIN playback_history ON playback_history.mediaItemId = media_items.id
                LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
                WHERE trash_entries.mediaItemId IS NULL
                  AND COALESCE(media_locations.relativePath, '') <> ''
                  AND (
                      ? = '' OR
                      substr(trim(media_locations.relativePath, '/'), 1, length(?) + 1) = ? || '/'
                  )
            ), folder_rows AS (
                SELECT CASE
                           WHEN instr(remainder, '/') > 0 THEN substr(remainder, 1, instr(remainder, '/') - 1)
                           ELSE remainder
                       END AS name,
                       sizeBytes, durationMillis, completed, playbackPositionMillis, historyMediaId
                FROM child_locations
            )
            SELECT CASE WHEN ? = '' THEN name ELSE ? || '/' || name END AS path,
                   name,
                   COUNT(*) AS videoCount,
                   COALESCE(SUM(sizeBytes), 0) AS sizeBytes,
                   COALESCE(SUM(durationMillis), 0) AS totalDurationMillis,
                   COALESCE(SUM(CASE WHEN completed = 0 AND playbackPositionMillis = 0 THEN 1 ELSE 0 END), 0) AS unwatchedCount,
                   COALESCE(SUM(CASE WHEN historyMediaId IS NULL THEN 1 ELSE 0 END), 0) AS newCount
            FROM folder_rows
            WHERE name <> ''
            GROUP BY path, name
            ORDER BY name COLLATE NOCASE
        """.trimIndent()
        val args = arrayOf<Any?>(
            query.currentPath, query.currentPath, query.currentPath,
            query.currentPath, query.currentPath,
            query.currentPath, query.currentPath, query.currentPath,
            query.currentPath, query.currentPath,
        )
        return libraryDao.folders(SimpleSQLiteQuery(sql, args)).map {
            LibraryFolder(
                path = it.path,
                name = it.name,
                videoCount = it.videoCount,
                sizeBytes = it.sizeBytes,
                totalDurationMillis = it.totalDurationMillis,
                unwatchedCount = it.unwatchedCount,
                newCount = it.newCount,
            )
        }
    }

    override suspend fun findByIds(ids: Set<MediaItemId>): List<LibraryMedia> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT media_items.id, media_items.title, media_items.playbackPositionMillis, media_items.completed,
                media_locations.id AS locationId, media_locations.uri, media_locations.fileName,
                media_sources.displayName AS folderAlias, media_locations.sizeBytes,
                media_locations.durationMillis, media_locations.width, media_locations.height,
                media_locations.modifiedEpochMillis, COALESCE(playback_history.playCount, 0) AS playCount
            FROM media_items
            INNER JOIN media_item_locations ON media_item_locations.mediaItemId = media_items.id
            INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
            INNER JOIN media_sources ON media_sources.id = media_locations.sourceId
            LEFT JOIN playback_history ON playback_history.mediaItemId = media_items.id
            LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
            WHERE media_items.id IN ($placeholders) AND trash_entries.mediaItemId IS NULL
                AND media_locations.missingScanCount = 0
        """.trimIndent()
        val rows = libraryDao.page(SimpleSQLiteQuery(sql, ids.map { it.value }.toTypedArray()))
        return rows.map { it.toModel(emptySet()) }
    }

    private fun toPage(
        query: LibraryQuery,
        rows: List<LibraryMediaRow>,
        count: Int,
        tags: List<MediaTagEntity>,
        direction: LibraryPageDirection = LibraryPageDirection.APPEND,
    ): LibraryPage {
        val tagsByMedia = tags.groupBy(MediaTagEntity::mediaItemId)
            .mapValues { (_, values) -> values.map(MediaTagEntity::tag).toSet() }
        // Fetch one sentinel row so a full final page does not produce a bogus cursor.
        val hasMore = rows.size > query.pageSize
        val pageRows = rows.take(query.pageSize).let { page ->
            if (direction == LibraryPageDirection.PREPEND) page.asReversed() else page
        }
        val items = pageRows.map { it.toModel(tagsByMedia[it.id].orEmpty()) }
        // Paging's prevKey is the cursor at the beginning of this page. It is
        // deliberately derived from the first returned item, not from the
        // cursor used to issue the query (that cursor belongs to the page
        // before an appended page and would skip records during prepend).
        val previous = pageRows.firstOrNull()
            ?.takeIf {
                when (direction) {
                    LibraryPageDirection.PREPEND -> hasMore
                    LibraryPageDirection.REFRESH,
                    LibraryPageDirection.APPEND,
                    -> query.cursor != null
                }
            }
            ?.let { it.toModel(tagsByMedia[it.id].orEmpty()).cursor(query) }
        val next = when (direction) {
            LibraryPageDirection.PREPEND -> items.lastOrNull()?.cursor(query)
            LibraryPageDirection.REFRESH,
            LibraryPageDirection.APPEND,
            -> items.lastOrNull()?.takeIf { hasMore }?.cursor(query)
        }
        return LibraryPage(items, next, count, previous)
    }

    private fun countKey(query: LibraryQuery): String = query.copy(cursor = null).toString()

    private fun buildFolderTreeCountQuery(path: String): SimpleSQLiteQuery {
        val normalizedPath = path.trim('/')
        val sql = """
            WITH latest_location AS (
                SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                    ROW_NUMBER() OVER (
                        PARTITION BY media_item_locations.mediaItemId
                        ORDER BY CASE
                            WHEN TRIM(media_locations.relativePath, '/') = ?
                              OR substr(TRIM(media_locations.relativePath, '/'), 1, length(?) + 1) = ? || '/'
                            THEN 0 ELSE 1 END,
                            media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                    ) AS rn
                FROM media_item_locations
                INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
                WHERE media_locations.missingScanCount = 0
            )
            SELECT COUNT(*)
            FROM media_items
            INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rn = 1
            INNER JOIN media_locations ON media_locations.id = latest_location.locationId
            LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
            WHERE trash_entries.mediaItemId IS NULL
              AND (
                  TRIM(media_locations.relativePath, '/') = ?
                  OR substr(TRIM(media_locations.relativePath, '/'), 1, length(?) + 1) = ? || '/'
              )
        """.trimIndent()
        return SimpleSQLiteQuery(
            sql,
            arrayOf(normalizedPath, normalizedPath, normalizedPath, normalizedPath, normalizedPath, normalizedPath),
        )
    }

    private fun buildQuery(
        query: LibraryQuery,
        countOnly: Boolean,
        direction: LibraryPageDirection = LibraryPageDirection.APPEND,
    ): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val folderPath = query.currentPath.trim('/').takeIf {
            query.browseMode == seeyuer.yingli.player.domain.library.LibraryBrowseMode.FOLDER &&
                query.normalizedKeyword.isBlank() && it.isNotBlank()
        }
        args += if (folderPath != null) 1 else 0
        args += folderPath.orEmpty()
        args += folderPath.orEmpty()
        args += folderPath.orEmpty()
        val where = mutableListOf(
            "trash_entries.mediaItemId IS NULL",
            "latest_location.rn = 1",
        )
        val keyword = query.normalizedKeyword.trim().lowercase()
        if (keyword.isNotEmpty()) {
            where += "(LOWER(media_items.title) LIKE ? OR LOWER(media_locations.fileName) LIKE ? OR LOWER(media_sources.displayName) LIKE ? OR EXISTS (SELECT 1 FROM media_tags searchTag WHERE searchTag.mediaItemId = media_items.id AND LOWER(searchTag.tag) LIKE ?) OR EXISTS (SELECT 1 FROM collection_items searchCollectionItem INNER JOIN collections searchCollection ON searchCollection.id = searchCollectionItem.collectionId WHERE searchCollectionItem.mediaItemId = media_items.id AND LOWER(searchCollection.name) LIKE ?))"
            val pattern = "%$keyword%"
            repeat(5) { args += pattern }
        }
        if (query.group == LibraryGroup.UNWATCHED) where += "media_items.completed = 0 AND media_items.playbackPositionMillis = 0"
        if (query.browseMode == seeyuer.yingli.player.domain.library.LibraryBrowseMode.FOLDER && keyword.isEmpty()) {
            if (query.currentPath.isBlank()) {
                where += "1 = 0"
            } else {
                where += "COALESCE(TRIM(media_locations.relativePath, '/'), '') = ?"
                args += query.currentPath
            }
        }
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
            val appendOperator = if (query.sort.direction == SortDirection.ASCENDING) ">" else "<"
            val reverseOperator = if (query.sort.direction == SortDirection.ASCENDING) "<" else ">"
            val primaryOperator = when (direction) {
                LibraryPageDirection.REFRESH,
                LibraryPageDirection.APPEND,
                -> appendOperator
                LibraryPageDirection.PREPEND -> reverseOperator
            }
            val tieOperator = when (direction) {
                // A refresh key identifies the first item of the page to
                // restore, so only refresh includes that exact tie breaker.
                LibraryPageDirection.REFRESH -> if (query.sort.direction == SortDirection.ASCENDING) ">=" else "<="
                LibraryPageDirection.APPEND -> appendOperator
                // Prepend queries run in reverse order, but the cursor row must
                // remain exclusive. Including it creates a duplicate at the
                // boundary when Paging restores a dropped page.
                LibraryPageDirection.PREPEND -> reverseOperator
            }
            val cursorValue: Any = when (query.sort.field) {
                LibrarySortField.NAME -> cursor.textValue.orEmpty()
                LibrarySortField.RESOLUTION -> cursor.longValue ?: 0L
                else -> cursor.longValue ?: 0L
            }
            where += "($column $primaryOperator ? OR ($column = ? AND media_items.id $tieOperator ?))"
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
            append("ORDER BY CASE WHEN ? = 1 AND (TRIM(media_locations.relativePath, '/') = ? OR substr(TRIM(media_locations.relativePath, '/'), 1, length(?) + 1) = ? || '/') THEN 0 ELSE 1 END, ")
            append("media_locations.lastSeenEpochMillis DESC, media_locations.id DESC) AS rn ")
            append("FROM media_item_locations INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId ")
            append("WHERE media_locations.missingScanCount = 0) ")
            append(select)
            append(" FROM media_items INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id")
            append(" INNER JOIN media_locations ON media_locations.id = latest_location.locationId")
            append(" INNER JOIN media_sources ON media_sources.id = media_locations.sourceId")
            append(" LEFT JOIN playback_history ON playback_history.mediaItemId = media_items.id")
            append(" LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id")
            append(" WHERE ").append(where.joinToString(" AND "))
            if (!countOnly) {
                val orderDirection = if (direction == LibraryPageDirection.PREPEND) {
                    query.sort.direction.reverseSql
                } else {
                    query.sort.direction.sql
                }
                append(" ORDER BY ${query.sort.field.valueColumn} $orderDirection, media_items.id $orderDirection LIMIT ${query.pageSize + 1}")
            }
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private val LibrarySortField.valueColumn: String get() = when (this) {
        LibrarySortField.NAME -> "LOWER(media_items.title)"
        LibrarySortField.RECENTLY_ADDED -> "media_locations.modifiedEpochMillis"
        LibrarySortField.DURATION -> "COALESCE(media_locations.durationMillis, -1)"
        LibrarySortField.RESOLUTION -> "COALESCE(media_locations.width, 0)"
        LibrarySortField.PLAY_COUNT -> "COALESCE(playback_history.playCount, 0)"
    }

    private val SortDirection.sql: String get() = if (this == SortDirection.ASCENDING) "ASC" else "DESC"
    private val SortDirection.reverseSql: String get() = if (this == SortDirection.ASCENDING) "DESC" else "ASC"

    private fun LibraryMedia.cursor(query: LibraryQuery): LibraryCursor = when (query.sort.field) {
        LibrarySortField.NAME -> LibraryCursor(query.sort.field, query.sort.direction, id, textValue = title.lowercase())
        LibrarySortField.RECENTLY_ADDED -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = modifiedEpochMillis)
        LibrarySortField.DURATION -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = durationMillis ?: -1L)
        LibrarySortField.RESOLUTION -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = (width ?: 0).toLong())
        LibrarySortField.PLAY_COUNT -> LibraryCursor(query.sort.field, query.sort.direction, id, longValue = playCount.toLong())
    }

    private companion object {
        val OBSERVED_TABLES = arrayOf(
            "media_items",
            "media_locations",
            "media_item_locations",
            "media_sources",
            "playback_history",
            "trash_entries",
            "media_tags",
            "collections",
            "collection_items",
        )
    }

    private fun LibraryMediaRow.toModel(tags: Set<String>) = LibraryMedia(
        id = MediaItemId(id), locationId = MediaLocationId(locationId), uri = MediaUri(uri), title = title,
        fileName = fileName, folderAlias = folderAlias, extension = fileName.substringAfterLast('.', "").lowercase(),
        sizeBytes = sizeBytes, durationMillis = durationMillis, width = width, height = height,
        modifiedEpochMillis = modifiedEpochMillis, playbackPositionMillis = playbackPositionMillis,
        completed = completed, playCount = playCount, tags = tags,
    )
}
