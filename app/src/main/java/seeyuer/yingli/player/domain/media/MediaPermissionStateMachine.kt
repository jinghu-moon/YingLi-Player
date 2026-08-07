package seeyuer.yingli.player.domain.media

import seeyuer.yingli.player.core.media.MediaPermissionSnapshot

enum class MediaAccessCapability {
    ALL_FILES,
    MEDIA_STORE,
    SAF_TREE,
    EMPTY,
}

data class MediaPermissionState(
    val capability: MediaAccessCapability = MediaAccessCapability.EMPTY,
    val allFilesGranted: Boolean = false,
    val mediaStoreGranted: Boolean = false,
    val safTrees: Set<String> = emptySet(),
    val needsReauthorization: Boolean = false,
)

sealed interface MediaPermissionEvent {
    data class Inspected(val snapshot: MediaPermissionSnapshot) : MediaPermissionEvent
    data object SelectAllFiles : MediaPermissionEvent
    data object AllFilesGranted : MediaPermissionEvent
    data object AllFilesDenied : MediaPermissionEvent
    data object SelectSafTree : MediaPermissionEvent
    data class SafTreeSelected(val uri: String) : MediaPermissionEvent
    data object SafTreeCancelled : MediaPermissionEvent
    data class GrantLost(val uri: String) : MediaPermissionEvent
}

sealed interface MediaPermissionEffect {
    data object OpenAllFilesSettings : MediaPermissionEffect
    data object OpenSafTreePicker : MediaPermissionEffect
    data object StartMediaStoreScan : MediaPermissionEffect
    data object RequestMediaStorePermission : MediaPermissionEffect
    data object StartAllFilesScan : MediaPermissionEffect
    data class PersistSafAndScan(val uri: String) : MediaPermissionEffect
}

data class PermissionTransition(
    val state: MediaPermissionState,
    val effect: MediaPermissionEffect? = null,
)

object MediaPermissionStateMachine {
    fun reduce(state: MediaPermissionState, event: MediaPermissionEvent): PermissionTransition = when (event) {
        is MediaPermissionEvent.Inspected -> {
            val capability = when {
                event.snapshot.allFilesAccess -> MediaAccessCapability.ALL_FILES
                event.snapshot.persistedSafTrees.isNotEmpty() -> MediaAccessCapability.SAF_TREE
                event.snapshot.mediaStoreReadAccess -> MediaAccessCapability.MEDIA_STORE
                else -> MediaAccessCapability.EMPTY
            }
            val lostTrees = state.safTrees - event.snapshot.persistedSafTrees
            PermissionTransition(state.copy(
                capability = capability,
                allFilesGranted = event.snapshot.allFilesAccess,
                mediaStoreGranted = event.snapshot.mediaStoreReadAccess,
                safTrees = event.snapshot.persistedSafTrees,
                needsReauthorization = lostTrees.isNotEmpty(),
            ))
        }
        MediaPermissionEvent.SelectAllFiles -> PermissionTransition(state, MediaPermissionEffect.OpenAllFilesSettings)
        MediaPermissionEvent.AllFilesGranted -> PermissionTransition(
            state.copy(capability = MediaAccessCapability.ALL_FILES, allFilesGranted = true),
            MediaPermissionEffect.StartAllFilesScan,
        )
        MediaPermissionEvent.AllFilesDenied -> if (state.mediaStoreGranted) {
            PermissionTransition(
                state.copy(capability = MediaAccessCapability.MEDIA_STORE, allFilesGranted = false),
                MediaPermissionEffect.StartMediaStoreScan,
            )
        } else {
            PermissionTransition(
                state.copy(capability = state.fallbackCapability(), allFilesGranted = false),
                MediaPermissionEffect.RequestMediaStorePermission,
            )
        }
        MediaPermissionEvent.SelectSafTree -> PermissionTransition(state, MediaPermissionEffect.OpenSafTreePicker)
        is MediaPermissionEvent.SafTreeSelected -> PermissionTransition(
            state.copy(capability = MediaAccessCapability.SAF_TREE, safTrees = state.safTrees + event.uri),
            MediaPermissionEffect.PersistSafAndScan(event.uri),
        )
        MediaPermissionEvent.SafTreeCancelled -> PermissionTransition(state)
        is MediaPermissionEvent.GrantLost -> {
            val remainingTrees = state.safTrees - event.uri
            PermissionTransition(state.copy(
                capability = when {
                    state.allFilesGranted -> MediaAccessCapability.ALL_FILES
                    remainingTrees.isNotEmpty() -> MediaAccessCapability.SAF_TREE
                    state.mediaStoreGranted -> MediaAccessCapability.MEDIA_STORE
                    else -> MediaAccessCapability.EMPTY
                },
                safTrees = remainingTrees,
                needsReauthorization = true,
            ))
        }
    }

    private fun MediaPermissionState.fallbackCapability(): MediaAccessCapability = when {
        safTrees.isNotEmpty() -> MediaAccessCapability.SAF_TREE
        mediaStoreGranted -> MediaAccessCapability.MEDIA_STORE
        else -> MediaAccessCapability.EMPTY
    }
}
