package seeyuer.yingli.player.domain.organize

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.FilterExpression

@JvmInline
value class TagId(val value: String) {
    init { require(value.isStableId()) }
}

@JvmInline
value class PlaylistId(val value: String) {
    init { require(value.isStableId()) }
}

@JvmInline
value class CollectionId(val value: String) {
    init { require(value.isStableId()) }
}

enum class TagColor {
    NEUTRAL,
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    INDIGO,
    VIOLET,
}

data class Tag(
    val id: TagId,
    val name: String,
    val color: TagColor,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(name.isNotBlank() && name.length <= 40)
        require(createdAtEpochMillis >= 0)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

data class CompositeTag(
    val name: String,
    val tagIds: Set<TagId>,
    val matchAll: Boolean = true,
) {
    init {
        require(name.isNotBlank())
        require(tagIds.size >= 2)
    }
}

data class Favorite(val mediaId: MediaItemId, val createdAtEpochMillis: Long)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val mediaIds: List<MediaItemId>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require(mediaIds.distinct().size == mediaIds.size)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

enum class CollectionKind {
    MANUAL,
    SMART,
}

data class Collection(
    val id: CollectionId,
    val name: String,
    val kind: CollectionKind,
    val mediaIds: Set<MediaItemId> = emptySet(),
    val filter: FilterExpression? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require((kind == CollectionKind.SMART) == (filter != null))
        require(kind != CollectionKind.SMART || mediaIds.isEmpty())
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

data class HistoryEntry(
    val mediaId: MediaItemId,
    val playCount: Int,
    val lastPlayedAtEpochMillis: Long,
    val lastPositionMillis: Long,
) {
    init {
        require(playCount > 0)
        require(lastPlayedAtEpochMillis >= 0)
        require(lastPositionMillis >= 0)
    }
}

enum class OrganizedAction {
    FAVORITED,
    TAGGED,
    PLAYLISTED,
    COLLECTED,
}

data class RecentlyOrganizedEntry(
    val mediaId: MediaItemId,
    val organizedAtEpochMillis: Long,
    val action: OrganizedAction,
)

data class ContinueWatchingPolicy(
    val minimumPlaybackMillis: Long = 10_000,
    val nearEndMillis: Long = 5_000,
) {
    init {
        require(minimumPlaybackMillis >= 0)
        require(nearEndMillis >= 0)
    }

    fun isEligible(
        positionMillis: Long,
        durationMillis: Long?,
        completed: Boolean,
        incognito: Boolean,
    ): Boolean {
        if (incognito || completed || positionMillis < minimumPlaybackMillis) return false
        val duration = durationMillis ?: return true
        return duration - positionMillis.coerceAtMost(duration) > nearEndMillis
    }

    fun shouldCountPlay(positionMillis: Long, incognito: Boolean): Boolean =
        !incognito && positionMillis >= minimumPlaybackMillis
}

data class OrganizeSnapshot(
    val tags: List<Tag> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val recentlyOrganized: List<RecentlyOrganizedEntry> = emptyList(),
)

sealed interface OrganizeMutationResult {
    data object Success : OrganizeMutationResult
    data object NameConflict : OrganizeMutationResult
    data object InvalidInput : OrganizeMutationResult
    data object RetryableFailure : OrganizeMutationResult
}

interface OrganizeRepository {
    val snapshot: Flow<OrganizeSnapshot>
    suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult
    suspend fun updateTag(tag: Tag): OrganizeMutationResult
    suspend fun deleteTag(id: TagId): OrganizeMutationResult
    suspend fun addTags(mediaIds: Set<MediaItemId>, tagIds: Set<TagId>): OrganizeMutationResult
    suspend fun setFavorite(mediaIds: Set<MediaItemId>, favorite: Boolean): OrganizeMutationResult
    suspend fun createPlaylist(name: String, mediaIds: List<MediaItemId>): OrganizeMutationResult
    suspend fun createCollection(name: String, mediaIds: Set<MediaItemId>): OrganizeMutationResult
    suspend fun createSmartCollection(name: String, filter: FilterExpression): OrganizeMutationResult
}

interface HistoryRepository {
    val history: Flow<List<HistoryEntry>>
    suspend fun recordPlayback(
        mediaId: MediaItemId,
        positionMillis: Long,
        nowEpochMillis: Long,
        incognito: Boolean,
    )
}

private fun String.isStableId(): Boolean = matches(Regex("[A-Za-z0-9_-]{1,128}"))
