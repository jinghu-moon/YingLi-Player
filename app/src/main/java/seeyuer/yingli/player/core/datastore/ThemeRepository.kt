package seeyuer.yingli.player.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class LibraryLayoutPreference {
    GRID,
    LIST,
}

enum class LibrarySortPreference {
    NAME,
    RECENTLY_ADDED,
    DURATION,
    PLAY_COUNT,
}

enum class SortDirectionPreference {
    ASCENDING,
    DESCENDING,
}

enum class ExportDirectoryPreference {
    YINGLI_OUTPUT,
    USER_SELECTED,
}

data class UserPreferences(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val libraryLayout: LibraryLayoutPreference = LibraryLayoutPreference.GRID,
    val thumbnailScale: Float = 1f,
    val librarySort: LibrarySortPreference = LibrarySortPreference.RECENTLY_ADDED,
    val librarySortDirection: SortDirectionPreference = SortDirectionPreference.DESCENDING,
    val trashRetentionDays: Int = 30,
    val miniPlayerEnabled: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    val exportDirectory: ExportDirectoryPreference = ExportDirectoryPreference.YINGLI_OUTPUT,
    val customExportTreeUri: String? = null,
) {
    init {
        require(schemaVersion in 1..CURRENT_SCHEMA_VERSION)
        require(thumbnailScale in MIN_THUMBNAIL_SCALE..MAX_THUMBNAIL_SCALE)
        require(trashRetentionDays in MIN_TRASH_RETENTION_DAYS..MAX_TRASH_RETENTION_DAYS)
        require(exportDirectory != ExportDirectoryPreference.USER_SELECTED || !customExportTreeUri.isNullOrBlank())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MIN_THUMBNAIL_SCALE = 0.75f
        const val MAX_THUMBNAIL_SCALE = 1.50f
        const val MIN_TRASH_RETENTION_DAYS = 1
        const val MAX_TRASH_RETENTION_DAYS = 365

        fun sanitize(
            schemaVersion: Int?,
            libraryLayout: String?,
            thumbnailScale: Float?,
            librarySort: String?,
            librarySortDirection: String?,
            trashRetentionDays: Int?,
            miniPlayerEnabled: Boolean?,
            autoPictureInPicture: Boolean?,
            exportDirectory: String?,
            customExportTreeUri: String?,
        ): UserPreferences {
            val safeDirectory = exportDirectory.enumOrDefault(ExportDirectoryPreference.YINGLI_OUTPUT)
            val safeUri = customExportTreeUri?.takeIf(String::isNotBlank)
            return UserPreferences(
                schemaVersion = schemaVersion?.takeIf { it in 1..CURRENT_SCHEMA_VERSION } ?: CURRENT_SCHEMA_VERSION,
                libraryLayout = libraryLayout.enumOrDefault(LibraryLayoutPreference.GRID),
                thumbnailScale = (thumbnailScale ?: 1f).coerceIn(MIN_THUMBNAIL_SCALE, MAX_THUMBNAIL_SCALE),
                librarySort = librarySort.enumOrDefault(LibrarySortPreference.RECENTLY_ADDED),
                librarySortDirection = librarySortDirection.enumOrDefault(SortDirectionPreference.DESCENDING),
                trashRetentionDays = (trashRetentionDays ?: 30)
                    .coerceIn(MIN_TRASH_RETENTION_DAYS, MAX_TRASH_RETENTION_DAYS),
                miniPlayerEnabled = miniPlayerEnabled ?: true,
                autoPictureInPicture = autoPictureInPicture ?: false,
                exportDirectory = if (safeDirectory == ExportDirectoryPreference.USER_SELECTED && safeUri == null) {
                    ExportDirectoryPreference.YINGLI_OUTPUT
                } else {
                    safeDirectory
                },
                customExportTreeUri = safeUri,
            )
        }
    }
}

typealias AppearanceSettings = UserPreferences

interface ThemeRepository {
    val settings: Flow<UserPreferences>

    suspend fun update(transform: (UserPreferences) -> UserPreferences)


}

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class DataStoreThemeRepository(context: Context) : ThemeRepository {
    private val dataStore = context.applicationContext.userPreferencesDataStore

    override val settings: Flow<UserPreferences> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::readPreferences)

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.edit { stored -> writePreferences(stored, transform(readPreferences(stored))) }
    }

    private fun readPreferences(preferences: Preferences): UserPreferences = UserPreferences.sanitize(
        schemaVersion = preferences[SCHEMA_VERSION],
        libraryLayout = preferences[LIBRARY_LAYOUT],
        thumbnailScale = preferences[THUMBNAIL_SCALE],
        librarySort = preferences[LIBRARY_SORT],
        librarySortDirection = preferences[LIBRARY_SORT_DIRECTION],
        trashRetentionDays = preferences[TRASH_RETENTION_DAYS],
        miniPlayerEnabled = preferences[MINI_PLAYER],
        autoPictureInPicture = preferences[AUTO_PIP],
        exportDirectory = preferences[EXPORT_DIRECTORY],
        customExportTreeUri = preferences[CUSTOM_EXPORT_TREE_URI],
    )

    private fun writePreferences(preferences: androidx.datastore.preferences.core.MutablePreferences, value: UserPreferences) {
        preferences[SCHEMA_VERSION] = UserPreferences.CURRENT_SCHEMA_VERSION
        preferences[LIBRARY_LAYOUT] = value.libraryLayout.name
        preferences[THUMBNAIL_SCALE] = value.thumbnailScale
        preferences[LIBRARY_SORT] = value.librarySort.name
        preferences[LIBRARY_SORT_DIRECTION] = value.librarySortDirection.name
        preferences[TRASH_RETENTION_DAYS] = value.trashRetentionDays
        preferences[MINI_PLAYER] = value.miniPlayerEnabled
        preferences[AUTO_PIP] = value.autoPictureInPicture
        preferences[EXPORT_DIRECTORY] = value.exportDirectory.name
        value.customExportTreeUri?.let { preferences[CUSTOM_EXPORT_TREE_URI] = it }
            ?: preferences.remove(CUSTOM_EXPORT_TREE_URI)
    }

    private companion object {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        val THUMBNAIL_SCALE = floatPreferencesKey("thumbnail_scale")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_SORT_DIRECTION = stringPreferencesKey("library_sort_direction")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val MINI_PLAYER = booleanPreferencesKey("mini_player_enabled")
        val AUTO_PIP = booleanPreferencesKey("auto_picture_in_picture")
        val EXPORT_DIRECTORY = stringPreferencesKey("export_directory")
        val CUSTOM_EXPORT_TREE_URI = stringPreferencesKey("custom_export_tree_uri")
    }
}

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
