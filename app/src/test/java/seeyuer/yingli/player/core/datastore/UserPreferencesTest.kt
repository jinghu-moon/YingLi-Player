package seeyuer.yingli.player.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    @Test
    fun `user preference defaults match current fixed-light contract`() {
        assertEquals(
            UserPreferences(
                schemaVersion = 1,
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
        assertEquals(LibraryLayoutPreference.GRID, restored.libraryLayout)
        assertEquals(UserPreferences.MAX_THUMBNAIL_SCALE, restored.thumbnailScale)
        assertEquals(UserPreferences.MIN_TRASH_RETENTION_DAYS, restored.trashRetentionDays)
        assertEquals(ExportDirectoryPreference.YINGLI_OUTPUT, restored.exportDirectory)
    }
}
