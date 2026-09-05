package seeyuer.yingli.player.domain.library

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri

enum class LibraryGroup {
    ALL,
    FOLDER,
    RECENT,
    UNWATCHED,
}

enum class LibraryViewMode {
    GRID,
    LIST,
}

enum class LibrarySortField {
    NAME,
    RECENTLY_ADDED,
    DURATION,
    PLAY_COUNT,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

data class SortSpec(
    val field: LibrarySortField = LibrarySortField.RECENTLY_ADDED,
    val direction: SortDirection = SortDirection.DESCENDING,
)

data class DurationFilter(
    val minimumMillis: Long? = null,
    val maximumMillis: Long? = null,
) {
    init {
        require(minimumMillis == null || minimumMillis >= 0)
        require(maximumMillis == null || maximumMillis >= 0)
        require(minimumMillis == null || maximumMillis == null || minimumMillis <= maximumMillis)
    }

    fun matches(value: Long?): Boolean {
        value ?: return minimumMillis == null && maximumMillis == null
        return (minimumMillis == null || value >= minimumMillis) &&
            (maximumMillis == null || value <= maximumMillis)
    }
}

enum class TagMatchMode {
    ALL,
    ANY,
}

data class FilterExpression(
    val includedTags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val tagMatchMode: TagMatchMode = TagMatchMode.ALL,
    val duration: DurationFilter = DurationFilter(),
    val minimumWidth: Int? = null,
    val extensions: Set<String> = emptySet(),
) {
    init {
        require(includedTags.none(String::isBlank))
        require(excludedTags.none(String::isBlank))
        require(includedTags.intersect(excludedTags).isEmpty())
        require(minimumWidth == null || minimumWidth > 0)
        require(extensions.none(String::isBlank))
    }

    fun matches(item: LibraryMedia): Boolean {
        val tagsMatch = when (tagMatchMode) {
            TagMatchMode.ALL -> item.tags.containsAll(includedTags)
            TagMatchMode.ANY -> includedTags.isEmpty() || includedTags.any(item.tags::contains)
        }
        return tagsMatch &&
            excludedTags.none(item.tags::contains) &&
            duration.matches(item.durationMillis) &&
            (minimumWidth == null || (item.width ?: 0) >= minimumWidth) &&
            (extensions.isEmpty() || item.extension.lowercase() in extensions.map(String::lowercase))
    }

    fun serialize(): String = listOf(
        "include=${includedTags.sorted().joinToString(",", transform = ::encode)}",
        "exclude=${excludedTags.sorted().joinToString(",", transform = ::encode)}",
        "match=${tagMatchMode.name}",
        "minDuration=${duration.minimumMillis.orEmpty()}",
        "maxDuration=${duration.maximumMillis.orEmpty()}",
        "minWidth=${minimumWidth.orEmpty()}",
        "extensions=${extensions.map(String::lowercase).sorted().joinToString(",", transform = ::encode)}",
    ).joinToString("&")

    companion object {
        fun deserialize(value: String): FilterExpression {
            val fields = value.split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 0) null else part.take(separator) to part.drop(separator + 1)
            }.toMap()
            fun set(name: String) = fields[name].orEmpty().split(',')
                .filter(String::isNotBlank)
                .map(::decode)
                .toSet()
            return FilterExpression(
                includedTags = set("include"),
                excludedTags = set("exclude"),
                tagMatchMode = fields["match"]?.let { stored ->
                    TagMatchMode.entries.firstOrNull { it.name == stored }
                } ?: TagMatchMode.ALL,
                duration = DurationFilter(fields["minDuration"]?.toLongOrNull(), fields["maxDuration"]?.toLongOrNull()),
                minimumWidth = fields["minWidth"]?.toIntOrNull(),
                extensions = set("extensions"),
            )
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        private fun Number?.orEmpty(): String = this?.toString().orEmpty()
    }
}

data class LibraryDisplayPreference(
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val thumbnailScale: Float = 1f,
    val sort: SortSpec = SortSpec(),
) {
    init {
        require(thumbnailScale in MIN_SCALE..MAX_SCALE)
    }

    companion object {
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 1.50f
    }
}

data class LibraryCursor(
    val field: LibrarySortField,
    val direction: SortDirection,
    val mediaId: MediaItemId,
    val textValue: String? = null,
    val longValue: Long? = null,
)

data class LibraryQuery(
    val keyword: String = "",
    val group: LibraryGroup = LibraryGroup.ALL,
    val sort: SortSpec = SortSpec(),
    val filter: FilterExpression = FilterExpression(),
    val cursor: LibraryCursor? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    val normalizedKeyword: String = keyword.trim().take(MAX_KEYWORD_LENGTH)

    init {
        require(pageSize in 1..MAX_PAGE_SIZE)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 60
        const val MAX_PAGE_SIZE = 200
        const val MAX_KEYWORD_LENGTH = 120
    }
}

data class LibraryMedia(
    val id: MediaItemId,
    val locationId: MediaLocationId,
    val uri: MediaUri,
    val title: String,
    val fileName: String,
    val folderAlias: String,
    val extension: String,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val modifiedEpochMillis: Long,
    val playbackPositionMillis: Long,
    val completed: Boolean,
    val playCount: Int = 0,
    val tags: Set<String> = emptySet(),
    val sizeBytes: Long = 0,
) {
    val watchedFraction: Float
        get() = durationMillis?.takeIf { it > 0 }
            ?.let { duration -> (playbackPositionMillis.toFloat() / duration).coerceIn(0f, 1f) }
            ?: 0f
}

fun LibraryMedia.cursorFor(sort: SortSpec): LibraryCursor = when (sort.field) {
    LibrarySortField.NAME -> LibraryCursor(sort.field, sort.direction, id, textValue = title.lowercase())
    LibrarySortField.RECENTLY_ADDED -> LibraryCursor(sort.field, sort.direction, id, longValue = modifiedEpochMillis)
    LibrarySortField.DURATION -> LibraryCursor(sort.field, sort.direction, id, longValue = durationMillis ?: -1L)
    LibrarySortField.PLAY_COUNT -> LibraryCursor(sort.field, sort.direction, id, longValue = playCount.toLong())
}

data class LibraryPage(
    val items: List<LibraryMedia>,
    val nextCursor: LibraryCursor?,
    val totalCount: Int,
    val previousCursor: LibraryCursor? = null,
)

enum class LibraryPageDirection {
    REFRESH,
    APPEND,
    PREPEND,
}

sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T) : LibraryResult<T>
    data object RetryableFailure : LibraryResult<Nothing>
}

interface LibraryRepository {
    fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>>
    suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage>
}

/** Database-backed paging boundary for the media library UI. */
interface LibraryPagingRepository : LibraryRepository {
    suspend fun page(
        query: LibraryQuery,
        direction: LibraryPageDirection = LibraryPageDirection.APPEND,
    ): LibraryPage
    fun observeCount(query: LibraryQuery): Flow<Int>
    fun observeInvalidations(): Flow<Unit>
}

interface SearchRepository {
    suspend fun search(query: LibraryQuery): LibraryResult<LibraryPage>
}

interface LibraryPreferenceRepository {
    val preference: Flow<LibraryDisplayPreference>
    suspend fun setViewMode(mode: LibraryViewMode)
    suspend fun setThumbnailScale(scale: Float)
    suspend fun setSort(sort: SortSpec)
}
