package seeyuer.yingli.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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
import seeyuer.yingli.player.domain.playback.PlayerPreferences

enum class LibraryLayoutPreference {
    GRID,
    LIST,
}

enum class BreadcrumbPreference {
    COLLAPSED,
    SCROLL,
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
    val libraryBreadcrumb: BreadcrumbPreference = BreadcrumbPreference.SCROLL,
    val thumbnailScale: Float = 1f,
    val librarySort: LibrarySortPreference = LibrarySortPreference.RECENTLY_ADDED,
    val librarySortDirection: SortDirectionPreference = SortDirectionPreference.DESCENDING,
    val libraryFolderColumns: Int = 2,
    val libraryVideoColumns: Int = 3,
    val trashRetentionDays: Int = 30,
    val miniPlayerEnabled: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    /** "上一个" 的行为：true=播放超过阈值时先回到本集开头；false=直接切上一项。 */
    val previousRestartsCurrentItem: Boolean = true,
    val gestureSeekEnabled: Boolean = true,
    val gestureVolumeEnabled: Boolean = true,
    val gestureBrightnessEnabled: Boolean = true,
    val gestureZoomEnabled: Boolean = true,
    val gestureLeftSideIsVolume: Boolean = true,
    val gestureDoubleTapSeekMillis: Int = PlayerPreferences.DEFAULT_DOUBLE_TAP_SEEK_MILLIS,
    val gestureSwipeDownToExitEnabled: Boolean = false,
    val gestureHintShown: Boolean = false,
    val gestureLongPressSpeed: Float = PlayerPreferences.DEFAULT_LONG_PRESS_SPEED.value,
    val exportDirectory: ExportDirectoryPreference = ExportDirectoryPreference.YINGLI_OUTPUT,
    val customExportTreeUri: String? = null,
) {
    init {
        require(schemaVersion in 1..CURRENT_SCHEMA_VERSION)
        require(thumbnailScale in MIN_THUMBNAIL_SCALE..MAX_THUMBNAIL_SCALE)
        require(libraryFolderColumns in MIN_LIBRARY_COLUMNS..MAX_LIBRARY_COLUMNS)
        require(libraryVideoColumns in MIN_LIBRARY_COLUMNS..MAX_LIBRARY_COLUMNS)
        require(trashRetentionDays in MIN_TRASH_RETENTION_DAYS..MAX_TRASH_RETENTION_DAYS)
        require(gestureDoubleTapSeekMillis in PlayerPreferences.DOUBLE_TAP_SEEK_MILLIS_OPTIONS)
        require(PlayerPreferences.isSupportedLongPressSpeed(gestureLongPressSpeed))
        require(exportDirectory != ExportDirectoryPreference.USER_SELECTED || !customExportTreeUri.isNullOrBlank())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MIN_THUMBNAIL_SCALE = 0.75f
        const val MAX_THUMBNAIL_SCALE = 1.50f
        const val MIN_LIBRARY_COLUMNS = 1
        const val MAX_LIBRARY_COLUMNS = 6
        const val MIN_TRASH_RETENTION_DAYS = 1
        const val MAX_TRASH_RETENTION_DAYS = 365

        fun sanitize(
            schemaVersion: Int?,
            libraryLayout: String?,
            libraryBreadcrumb: String? = null,
            thumbnailScale: Float?,
            librarySort: String?,
            librarySortDirection: String?,
            libraryFolderColumns: Int? = null,
            libraryVideoColumns: Int? = null,
            trashRetentionDays: Int?,
            miniPlayerEnabled: Boolean?,
            autoPictureInPicture: Boolean?,
            previousRestartsCurrentItem: Boolean? = null,
            gestureSeekEnabled: Boolean? = null,
            gestureVolumeEnabled: Boolean? = null,
            gestureBrightnessEnabled: Boolean? = null,
            gestureZoomEnabled: Boolean? = null,
            gestureLeftSideIsVolume: Boolean? = null,
            gestureDoubleTapSeekMillis: Int? = null,
            gestureSwipeDownToExitEnabled: Boolean? = null,
            gestureHintShown: Boolean? = null,
            gestureLongPressSpeed: Float? = null,
            exportDirectory: String?,
            customExportTreeUri: String?,
        ): UserPreferences {
            val safeDirectory = exportDirectory.enumOrDefault(ExportDirectoryPreference.YINGLI_OUTPUT)
            val safeUri = customExportTreeUri?.takeIf(String::isNotBlank)
            return UserPreferences(
                schemaVersion = schemaVersion?.takeIf { it in 1..CURRENT_SCHEMA_VERSION } ?: CURRENT_SCHEMA_VERSION,
                libraryLayout = libraryLayout.enumOrDefault(LibraryLayoutPreference.GRID),
                libraryBreadcrumb = libraryBreadcrumb.enumOrDefault(BreadcrumbPreference.SCROLL),
                thumbnailScale = (thumbnailScale ?: 1f).coerceIn(MIN_THUMBNAIL_SCALE, MAX_THUMBNAIL_SCALE),
                librarySort = librarySort.enumOrDefault(LibrarySortPreference.RECENTLY_ADDED),
                librarySortDirection = librarySortDirection.enumOrDefault(SortDirectionPreference.DESCENDING),
                libraryFolderColumns = (libraryFolderColumns ?: 2).coerceIn(MIN_LIBRARY_COLUMNS, MAX_LIBRARY_COLUMNS),
                libraryVideoColumns = (libraryVideoColumns ?: 3).coerceIn(MIN_LIBRARY_COLUMNS, MAX_LIBRARY_COLUMNS),
                trashRetentionDays = (trashRetentionDays ?: 30)
                    .coerceIn(MIN_TRASH_RETENTION_DAYS, MAX_TRASH_RETENTION_DAYS),
                miniPlayerEnabled = miniPlayerEnabled ?: true,
                autoPictureInPicture = autoPictureInPicture ?: false,
                previousRestartsCurrentItem = previousRestartsCurrentItem ?: true,
                gestureSeekEnabled = gestureSeekEnabled ?: true,
                gestureVolumeEnabled = gestureVolumeEnabled ?: true,
                gestureBrightnessEnabled = gestureBrightnessEnabled ?: true,
                gestureZoomEnabled = gestureZoomEnabled ?: true,
                gestureLeftSideIsVolume = gestureLeftSideIsVolume ?: true,
                gestureDoubleTapSeekMillis = PlayerPreferences.doubleTapSeekMillisOrDefault(gestureDoubleTapSeekMillis),
                gestureSwipeDownToExitEnabled = gestureSwipeDownToExitEnabled ?: false,
                gestureHintShown = gestureHintShown ?: false,
                gestureLongPressSpeed = PlayerPreferences.longPressSpeedOrDefault(gestureLongPressSpeed).value,
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
        .map { it.readUserPreferences() }

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.edit { stored -> stored.writeUserPreferences(transform(stored.readUserPreferences())) }
    }
}

/** 读取 DataStore 记录：缺失字段与坏值统一由 [UserPreferences.sanitize] 回退。 */
internal fun Preferences.readUserPreferences(): UserPreferences = UserPreferences.sanitize(
    schemaVersion = this[SCHEMA_VERSION],
    libraryLayout = this[LIBRARY_LAYOUT],
    libraryBreadcrumb = this[LIBRARY_BREADCRUMB],
    thumbnailScale = this[THUMBNAIL_SCALE],
    librarySort = this[LIBRARY_SORT],
    librarySortDirection = this[LIBRARY_SORT_DIRECTION],
    libraryFolderColumns = this[LIBRARY_FOLDER_COLUMNS],
    libraryVideoColumns = this[LIBRARY_VIDEO_COLUMNS],
    trashRetentionDays = this[TRASH_RETENTION_DAYS],
    miniPlayerEnabled = this[MINI_PLAYER],
    autoPictureInPicture = this[AUTO_PIP],
    previousRestartsCurrentItem = this[PREVIOUS_RESTARTS_CURRENT_ITEM],
    gestureSeekEnabled = this[GESTURE_SEEK_ENABLED],
    gestureVolumeEnabled = this[GESTURE_VOLUME_ENABLED],
    gestureBrightnessEnabled = this[GESTURE_BRIGHTNESS_ENABLED],
    gestureZoomEnabled = this[GESTURE_ZOOM_ENABLED],
    gestureLeftSideIsVolume = this[GESTURE_LEFT_SIDE_IS_VOLUME],
    gestureDoubleTapSeekMillis = this[GESTURE_DOUBLE_TAP_SEEK_MILLIS],
    gestureSwipeDownToExitEnabled = this[GESTURE_SWIPE_DOWN_TO_EXIT_ENABLED],
    gestureHintShown = this[GESTURE_HINT_SHOWN],
    gestureLongPressSpeed = this[GESTURE_LONG_PRESS_SPEED],
    exportDirectory = this[EXPORT_DIRECTORY],
    customExportTreeUri = this[CUSTOM_EXPORT_TREE_URI],
)

/** 写入 DataStore 记录：与 [readUserPreferences] 共用同一组键，键名必须成对维护。 */
internal fun MutablePreferences.writeUserPreferences(value: UserPreferences) {
    this[SCHEMA_VERSION] = UserPreferences.CURRENT_SCHEMA_VERSION
    this[LIBRARY_LAYOUT] = value.libraryLayout.name
    this[LIBRARY_BREADCRUMB] = value.libraryBreadcrumb.name
    this[THUMBNAIL_SCALE] = value.thumbnailScale
    this[LIBRARY_SORT] = value.librarySort.name
    this[LIBRARY_SORT_DIRECTION] = value.librarySortDirection.name
    this[LIBRARY_FOLDER_COLUMNS] = value.libraryFolderColumns
    this[LIBRARY_VIDEO_COLUMNS] = value.libraryVideoColumns
    this[TRASH_RETENTION_DAYS] = value.trashRetentionDays
    this[MINI_PLAYER] = value.miniPlayerEnabled
    this[AUTO_PIP] = value.autoPictureInPicture
    this[PREVIOUS_RESTARTS_CURRENT_ITEM] = value.previousRestartsCurrentItem
    this[GESTURE_SEEK_ENABLED] = value.gestureSeekEnabled
    this[GESTURE_VOLUME_ENABLED] = value.gestureVolumeEnabled
    this[GESTURE_BRIGHTNESS_ENABLED] = value.gestureBrightnessEnabled
    this[GESTURE_ZOOM_ENABLED] = value.gestureZoomEnabled
    this[GESTURE_LEFT_SIDE_IS_VOLUME] = value.gestureLeftSideIsVolume
    this[GESTURE_DOUBLE_TAP_SEEK_MILLIS] = value.gestureDoubleTapSeekMillis
    this[GESTURE_SWIPE_DOWN_TO_EXIT_ENABLED] = value.gestureSwipeDownToExitEnabled
    this[GESTURE_HINT_SHOWN] = value.gestureHintShown
    this[GESTURE_LONG_PRESS_SPEED] = value.gestureLongPressSpeed
    this[EXPORT_DIRECTORY] = value.exportDirectory.name
    value.customExportTreeUri?.let { this[CUSTOM_EXPORT_TREE_URI] = it }
        ?: remove(CUSTOM_EXPORT_TREE_URI)
}

private val SCHEMA_VERSION = intPreferencesKey("schema_version")
private val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
private val LIBRARY_BREADCRUMB = stringPreferencesKey("library_breadcrumb")
private val THUMBNAIL_SCALE = floatPreferencesKey("thumbnail_scale")
private val LIBRARY_SORT = stringPreferencesKey("library_sort")
private val LIBRARY_SORT_DIRECTION = stringPreferencesKey("library_sort_direction")
private val LIBRARY_FOLDER_COLUMNS = intPreferencesKey("library_folder_columns")
private val LIBRARY_VIDEO_COLUMNS = intPreferencesKey("library_video_columns")
private val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
private val MINI_PLAYER = booleanPreferencesKey("mini_player_enabled")
private val AUTO_PIP = booleanPreferencesKey("auto_picture_in_picture")
    private val PREVIOUS_RESTARTS_CURRENT_ITEM = booleanPreferencesKey("previous_restarts_current_item")
private val GESTURE_SEEK_ENABLED = booleanPreferencesKey("gesture_seek_enabled")
private val GESTURE_VOLUME_ENABLED = booleanPreferencesKey("gesture_volume_enabled")
private val GESTURE_BRIGHTNESS_ENABLED = booleanPreferencesKey("gesture_brightness_enabled")
private val GESTURE_ZOOM_ENABLED = booleanPreferencesKey("gesture_zoom_enabled")
private val GESTURE_LEFT_SIDE_IS_VOLUME = booleanPreferencesKey("gesture_left_side_is_volume")
private val GESTURE_DOUBLE_TAP_SEEK_MILLIS = intPreferencesKey("gesture_double_tap_seek_millis")
private val GESTURE_SWIPE_DOWN_TO_EXIT_ENABLED = booleanPreferencesKey("gesture_swipe_down_to_exit_enabled")
private val GESTURE_HINT_SHOWN = booleanPreferencesKey("gesture_hint_shown")
private val GESTURE_LONG_PRESS_SPEED = floatPreferencesKey("gesture_long_press_speed")
private val EXPORT_DIRECTORY = stringPreferencesKey("export_directory")
private val CUSTOM_EXPORT_TREE_URI = stringPreferencesKey("custom_export_tree_uri")

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
