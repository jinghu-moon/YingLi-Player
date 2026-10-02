package seeyuer.yingli.player.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                gestureSeekEnabled = true,
                gestureVolumeEnabled = true,
                gestureBrightnessEnabled = true,
                gestureZoomEnabled = true,
                gestureLeftSideIsVolume = true,
                gestureDoubleTapSeekMillis = 10_000,
                gestureSwipeDownToExitEnabled = false,
                gestureHintShown = false,
                gestureLongPressSpeed = 2f,
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
            libraryBreadcrumb = "WRAP",
            thumbnailScale = 9f,
            librarySort = "SIZE",
            librarySortDirection = "SIDEWAYS",
            libraryFolderColumns = -4,
            libraryVideoColumns = 99,
            trashRetentionDays = -2,
            miniPlayerEnabled = null,
            autoPictureInPicture = null,
            exportDirectory = ExportDirectoryPreference.USER_SELECTED.name,
            customExportTreeUri = null,
        )

        assertEquals(UserPreferences.CURRENT_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(LibraryLayoutPreference.GRID, restored.libraryLayout)
        assertEquals(BreadcrumbPreference.SCROLL, restored.libraryBreadcrumb)
        assertEquals(UserPreferences.MAX_THUMBNAIL_SCALE, restored.thumbnailScale)
        assertEquals(UserPreferences.MIN_LIBRARY_COLUMNS, restored.libraryFolderColumns)
        assertEquals(UserPreferences.MAX_LIBRARY_COLUMNS, restored.libraryVideoColumns)
        assertEquals(UserPreferences.MIN_TRASH_RETENTION_DAYS, restored.trashRetentionDays)
        assertEquals(ExportDirectoryPreference.YINGLI_OUTPUT, restored.exportDirectory)
    }

    @Test
    fun `unsupported gesture settings fall back to defaults`() {
        val restored = sanitizeGestures(
            doubleTapSeekMillis = 12_345,
            longPressSpeed = 2.5f,
        )

        assertEquals(10_000, restored.gestureDoubleTapSeekMillis)
        assertEquals(2f, restored.gestureLongPressSpeed)
        assertTrue(restored.gestureSeekEnabled)
        assertTrue(restored.gestureVolumeEnabled)
        assertTrue(restored.gestureBrightnessEnabled)
        assertTrue(restored.gestureZoomEnabled)
        assertTrue(restored.gestureLeftSideIsVolume)
        assertFalse(restored.gestureSwipeDownToExitEnabled)
        assertFalse(restored.gestureHintShown)
    }

    @Test
    fun `supported gesture settings survive sanitize unchanged`() {
        val restored = sanitizeGestures(
            seekEnabled = false,
            volumeEnabled = false,
            brightnessEnabled = false,
            zoomEnabled = false,
            leftSideIsVolume = false,
            doubleTapSeekMillis = 30_000,
            swipeDownToExitEnabled = true,
            hintShown = true,
            longPressSpeed = 1.5f,
        )

        assertEquals(
            UserPreferences(
                gestureSeekEnabled = false,
                gestureVolumeEnabled = false,
                gestureBrightnessEnabled = false,
                gestureZoomEnabled = false,
                gestureLeftSideIsVolume = false,
                gestureDoubleTapSeekMillis = 30_000,
                gestureSwipeDownToExitEnabled = true,
                gestureHintShown = true,
                gestureLongPressSpeed = 1.5f,
            ),
            restored,
        )
    }

    @Test
    fun `gesture settings round trip through the datastore record`() {
        val source = UserPreferences(
            gestureSeekEnabled = false,
            gestureVolumeEnabled = true,
            gestureBrightnessEnabled = false,
            gestureZoomEnabled = false,
            gestureLeftSideIsVolume = false,
            gestureDoubleTapSeekMillis = 15_000,
            gestureSwipeDownToExitEnabled = true,
            gestureHintShown = true,
            gestureLongPressSpeed = 3f,
        )

        val stored = mutablePreferencesOf()
        stored.writeUserPreferences(source)

        assertEquals(source, stored.readUserPreferences())
    }

    @Test
    fun `empty datastore record reads back the documented gesture defaults`() {
        val restored = mutablePreferencesOf().readUserPreferences()

        assertEquals(10_000, restored.gestureDoubleTapSeekMillis)
        assertEquals(2f, restored.gestureLongPressSpeed)
        assertTrue(restored.gestureSeekEnabled)
        assertTrue(restored.gestureVolumeEnabled)
        assertTrue(restored.gestureBrightnessEnabled)
        assertTrue(restored.gestureZoomEnabled)
        assertTrue(restored.gestureLeftSideIsVolume)
        assertFalse(restored.gestureSwipeDownToExitEnabled)
        // 旧记录没有 gesture_hint_shown 键：回退成"未展示"，一次性提示仍然会出现。
        assertFalse(restored.gestureHintShown)
    }

    private fun sanitizeGestures(
        seekEnabled: Boolean? = null,
        volumeEnabled: Boolean? = null,
        brightnessEnabled: Boolean? = null,
        zoomEnabled: Boolean? = null,
        leftSideIsVolume: Boolean? = null,
        doubleTapSeekMillis: Int? = null,
        swipeDownToExitEnabled: Boolean? = null,
        hintShown: Boolean? = null,
        longPressSpeed: Float? = null,
    ): UserPreferences = UserPreferences.sanitize(
        schemaVersion = null,
        libraryLayout = null,
        thumbnailScale = null,
        librarySort = null,
        librarySortDirection = null,
        trashRetentionDays = null,
        miniPlayerEnabled = null,
        autoPictureInPicture = null,
        gestureSeekEnabled = seekEnabled,
        gestureVolumeEnabled = volumeEnabled,
        gestureBrightnessEnabled = brightnessEnabled,
        gestureZoomEnabled = zoomEnabled,
        gestureLeftSideIsVolume = leftSideIsVolume,
        gestureDoubleTapSeekMillis = doubleTapSeekMillis,
        gestureSwipeDownToExitEnabled = swipeDownToExitEnabled,
        gestureHintShown = hintShown,
        gestureLongPressSpeed = longPressSpeed,
        exportDirectory = null,
        customExportTreeUri = null,
    )
}
