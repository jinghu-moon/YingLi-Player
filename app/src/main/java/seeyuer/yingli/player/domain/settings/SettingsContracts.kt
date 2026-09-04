package seeyuer.yingli.player.domain.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BackupTag(val id: String, val name: String, val color: String)
data class BackupTagAssignment(val mediaId: String, val tagId: String)

data class BackupPlaylist(val id: String, val name: String, val mediaIds: List<String>)

data class BackupCollection(
    val id: String,
    val name: String,
    val kind: String,
    val filter: String?,
    val mediaIds: List<String>,
)

data class BackupHistory(
    val mediaId: String,
    val playCount: Int,
    val lastPlayedAtEpochMillis: Long,
    val lastPositionMillis: Long,
)

data class BackupSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAtEpochMillis: Long,
    val selection: BackupSelection = BackupSelection(),
    val preferences: Map<String, String> = emptyMap(),
    val tags: List<BackupTag> = emptyList(),
    val tagAssignments: List<BackupTagAssignment> = emptyList(),
    val favorites: List<String> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
    val history: List<BackupHistory> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(createdAtEpochMillis >= 0)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BackupSelection(
    val preferences: Boolean = true,
    val organize: Boolean = true,
    val history: Boolean = true,
) {
    val isEmpty: Boolean get() = !preferences && !organize && !history
}

enum class BackupConflictStrategy {
    KEEP_EXISTING,
    REPLACE,
}

data class BackupPreview(
    val preferenceCount: Int,
    val tagCount: Int,
    val favoriteCount: Int,
    val playlistCount: Int,
    val collectionCount: Int,
    val historyCount: Int,
    val conflictCount: Int,
    val tagAssignmentCount: Int = 0,
)

sealed interface BackupDecodeResult {
    data class Success(val snapshot: BackupSnapshot) : BackupDecodeResult
    data object Empty : BackupDecodeResult
    data object TooLarge : BackupDecodeResult
    data object UnsupportedVersion : BackupDecodeResult
    data object Invalid : BackupDecodeResult
}

object BackupCodec {
    const val MAX_DOCUMENT_CHARS = 4 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: BackupSnapshot): String = buildJsonObject {
        put("schemaVersion", snapshot.schemaVersion)
        put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
        put("selection", buildJsonObject {
            put("preferences", snapshot.selection.preferences)
            put("organize", snapshot.selection.organize)
            put("history", snapshot.selection.history)
        })
        put("preferences", snapshot.preferences.toJsonObject())
        put("tags", snapshot.tags.toJsonArray { tag ->
            buildJsonObject {
                put("id", tag.id)
                put("name", tag.name)
                put("color", tag.color)
            }
        })
        put("tagAssignments", snapshot.tagAssignments.toJsonArray { assignment ->
            buildJsonObject {
                put("mediaId", assignment.mediaId)
                put("tagId", assignment.tagId)
            }
        })
        put("favorites", snapshot.favorites.toJsonArray(::JsonPrimitive))
        put("playlists", snapshot.playlists.toJsonArray { playlist ->
            buildJsonObject {
                put("id", playlist.id)
                put("name", playlist.name)
                put("mediaIds", playlist.mediaIds.toJsonArray(::JsonPrimitive))
            }
        })
        put("collections", snapshot.collections.toJsonArray { collection ->
            buildJsonObject {
                put("id", collection.id)
                put("name", collection.name)
                put("kind", collection.kind)
                collection.filter?.let { put("filter", it) }
                put("mediaIds", collection.mediaIds.toJsonArray(::JsonPrimitive))
            }
        })
        put("history", snapshot.history.toJsonArray { history ->
            buildJsonObject {
                put("mediaId", history.mediaId)
                put("playCount", history.playCount)
                put("lastPlayedAtEpochMillis", history.lastPlayedAtEpochMillis)
                put("lastPositionMillis", history.lastPositionMillis)
            }
        })
    }.toString()

    fun decode(document: String): BackupDecodeResult {
        if (document.isBlank()) return BackupDecodeResult.Empty
        if (document.length > MAX_DOCUMENT_CHARS) return BackupDecodeResult.TooLarge
        return runCatching {
            val root = json.parseToJsonElement(document).jsonObject
            val version = root.long("schemaVersion")?.toInt()
                ?: return BackupDecodeResult.Invalid
            if (version != BackupSnapshot.CURRENT_SCHEMA_VERSION) return BackupDecodeResult.UnsupportedVersion
            BackupDecodeResult.Success(
                BackupSnapshot(
                    schemaVersion = version,
                    createdAtEpochMillis = root.long("createdAtEpochMillis") ?: return BackupDecodeResult.Invalid,
                    selection = root["selection"]?.jsonObject?.let { selection ->
                        BackupSelection(
                            preferences = selection["preferences"]?.jsonPrimitive?.booleanOrNull ?: true,
                            organize = selection["organize"]?.jsonPrimitive?.booleanOrNull ?: true,
                            history = selection["history"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    } ?: BackupSelection(),
                    preferences = root.objectOrEmpty("preferences").mapValues { it.value.jsonPrimitive.content },
                    tags = root.arrayOrEmpty("tags").map { item ->
                        val value = item.jsonObject
                        BackupTag(value.text("id"), value.text("name"), value.text("color"))
                    },
                    tagAssignments = root.arrayOrEmpty("tagAssignments").map { item ->
                        val value = item.jsonObject
                        BackupTagAssignment(value.text("mediaId"), value.text("tagId"))
                    },
                    favorites = root.arrayOrEmpty("favorites").map { it.jsonPrimitive.content },
                    playlists = root.arrayOrEmpty("playlists").map { item ->
                        val value = item.jsonObject
                        BackupPlaylist(value.text("id"), value.text("name"), value.stringList("mediaIds"))
                    },
                    collections = root.arrayOrEmpty("collections").map { item ->
                        val value = item.jsonObject
                        BackupCollection(
                            value.text("id"),
                            value.text("name"),
                            value.text("kind"),
                            value["filter"]?.jsonPrimitive?.contentOrNull,
                            value.stringList("mediaIds"),
                        )
                    },
                    history = root.arrayOrEmpty("history").map { item ->
                        val value = item.jsonObject
                        BackupHistory(
                            value.text("mediaId"),
                            value.long("playCount")?.toInt() ?: error("Missing playCount"),
                            value.long("lastPlayedAtEpochMillis") ?: error("Missing lastPlayedAtEpochMillis"),
                            value.long("lastPositionMillis") ?: error("Missing lastPositionMillis"),
                        )
                    },
                ),
            )
        }.getOrDefault(BackupDecodeResult.Invalid)
    }

    private fun Map<String, String>.toJsonObject(): JsonObject = buildJsonObject {
        toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun <T> Iterable<T>.toJsonArray(transform: (T) -> kotlinx.serialization.json.JsonElement): JsonArray =
        buildJsonArray { forEach { add(transform(it)) } }

    private fun JsonObject.arrayOrEmpty(name: String): JsonArray = get(name)?.jsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectOrEmpty(name: String): JsonObject = get(name)?.jsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.text(name: String): String = get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Missing $name")
    private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
    private fun JsonObject.stringList(name: String): List<String> = arrayOrEmpty(name).map { it.jsonPrimitive.content }
}

interface BackupGateway {
    suspend fun export(selection: BackupSelection): BackupSnapshot
    suspend fun preview(snapshot: BackupSnapshot): BackupPreview
    suspend fun restore(snapshot: BackupSnapshot, strategy: BackupConflictStrategy): BackupPreview
}

interface SettingsDocumentGateway {
    suspend fun read(uri: String): String?
    suspend fun write(uri: String, content: String): Boolean
}

data class DiagnosticReport(val content: String, val includedRecordCount: Int)

interface DiagnosticsReporter {
    suspend fun createReport(): DiagnosticReport
}

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val pageUrl: String,
    val publishedAt: String?,
    val prerelease: Boolean,
)

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data object Current : UpdateCheckResult
    data object RateLimited : UpdateCheckResult
    data object Unavailable : UpdateCheckResult
    data object InvalidResponse : UpdateCheckResult
}

interface UpdateSource {
    suspend fun check(currentVersion: String): UpdateCheckResult
}

object GitHubReleaseParser {
    const val MAX_RESPONSE_CHARS = 256 * 1024

    fun parse(response: String): ReleaseInfo? {
        if (response.isBlank() || response.length > MAX_RESPONSE_CHARS) return null
        return runCatching {
            val root = Json.parseToJsonElement(response).jsonObject
            val pageUrl = root.text("html_url")
            if (!pageUrl.startsWith("https://github.com/")) return null
            ReleaseInfo(
                tagName = root.text("tag_name"),
                title = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: root.text("tag_name"),
                pageUrl = pageUrl,
                publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull,
                prerelease = root["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }
}

private fun JsonObject.text(name: String): String = get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    ?: error("Missing $name")
private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
