package seeyuer.yingli.player.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.catalog.MediaPermissionSnapshot

class MediaPermissionStateMachineTest {
    @Test
    fun `selecting all files only emits a user initiated settings effect`() {
        val transition = MediaPermissionStateMachine.reduce(
            MediaPermissionState(),
            MediaPermissionEvent.SelectAllFiles,
        )

        assertEquals(MediaAccessCapability.EMPTY, transition.state.capability)
        assertEquals(MediaPermissionEffect.OpenAllFilesSettings, transition.effect)
    }

    @Test
    fun `all files denial requests media store permission when no fallback is granted`() {
        val transition = MediaPermissionStateMachine.reduce(
            MediaPermissionState(),
            MediaPermissionEvent.AllFilesDenied,
        )

        assertEquals(MediaAccessCapability.EMPTY, transition.state.capability)
        assertEquals(MediaPermissionEffect.RequestMediaStorePermission, transition.effect)
    }

    @Test
    fun `all files denial starts existing media store fallback`() {
        val transition = MediaPermissionStateMachine.reduce(
            MediaPermissionState(mediaStoreGranted = true),
            MediaPermissionEvent.AllFilesDenied,
        )

        assertEquals(MediaAccessCapability.MEDIA_STORE, transition.state.capability)
        assertEquals(MediaPermissionEffect.StartMediaStoreScan, transition.effect)
    }

    @Test
    fun `cancelled saf picker leaves current state unchanged`() {
        val state = MediaPermissionState(capability = MediaAccessCapability.MEDIA_STORE, mediaStoreGranted = true)
        assertEquals(state, MediaPermissionStateMachine.reduce(state, MediaPermissionEvent.SafTreeCancelled).state)
    }

    @Test
    fun `inspection detects lost persisted tree and keeps remaining fallback`() {
        val previous = MediaPermissionState(
            capability = MediaAccessCapability.SAF_TREE,
            mediaStoreGranted = true,
            safTrees = setOf("content://tree/one", "content://tree/two"),
        )
        val transition = MediaPermissionStateMachine.reduce(
            previous,
            MediaPermissionEvent.Inspected(MediaPermissionSnapshot(
                allFilesAccess = false,
                mediaStoreReadAccess = true,
                persistedSafTrees = setOf("content://tree/two"),
            )),
        )

        assertEquals(MediaAccessCapability.SAF_TREE, transition.state.capability)
        assertTrue(transition.state.needsReauthorization)
        assertFalse(transition.state.allFilesGranted)
    }
}
