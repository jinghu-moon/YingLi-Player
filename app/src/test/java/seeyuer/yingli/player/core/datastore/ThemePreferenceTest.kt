package seeyuer.yingli.player.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test
    fun `stored values restore all three theme modes`() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromStoredValue(preference.name))
        }
    }

    @Test
    fun `missing and invalid values fall back to system`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredValue(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredValue("INVALID"))
    }

    @Test
    fun `user preference defaults match the first release contract`() {
        assertEquals(
            UserPreferences(
                schemaVersion = 1,
                themePreference = ThemePreference.SYSTEM,
                processingPinned = false,
                libraryLayout = LibraryLayoutPreference.GRID,
                thumbnailScale = 1f,
                librarySort = LibrarySortPreference.RECENTLY_ADDED,
                librarySortDirection = SortDirectionPreference.DESCENDING,
                trashRetentionDays = 30,
                miniPlayerEnabled = true,
                autoPictureInPicture = false,
                exportDirectory = ExportDirectoryPreference.YINGLI_OUTPUT,
                customExportTreeUri = null,
            ),
            UserPreferences(),
        )
    }

    @Test
    fun `damaged settings fall back and numeric values are clamped`() {
        val restored = UserPreferences.sanitize(
            schemaVersion = 99,
            theme = "AMOLED",
            processingPinned = null,
            libraryLayout = "CAROUSEL",
            thumbnailScale = 9f,
            librarySort = "SIZE",
            librarySortDirection = "SIDEWAYS",
            trashRetentionDays = -2,
            miniPlayerEnabled = null,
            autoPictureInPicture = null,
            exportDirectory = ExportDirectoryPreference.USER_SELECTED.name,
            customExportTreeUri = null,
        )

        assertEquals(UserPreferences.CURRENT_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(ThemePreference.SYSTEM, restored.themePreference)
        assertEquals(LibraryLayoutPreference.GRID, restored.libraryLayout)
        assertEquals(UserPreferences.MAX_THUMBNAIL_SCALE, restored.thumbnailScale)
        assertEquals(UserPreferences.MIN_TRASH_RETENTION_DAYS, restored.trashRetentionDays)
        assertEquals(ExportDirectoryPreference.YINGLI_OUTPUT, restored.exportDirectory)
    }
}
