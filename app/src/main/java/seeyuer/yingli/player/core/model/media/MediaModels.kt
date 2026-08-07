package seeyuer.yingli.player.core.model.media

@JvmInline
value class MediaSourceId(val value: String) {
    init {
        require(value.isStableId()) { "MediaSourceId must be a stable identifier." }
    }
}

@JvmInline
value class MediaItemId(val value: String) {
    init {
        require(value.isStableId()) { "MediaItemId must be a stable identifier." }
    }
}

@JvmInline
value class MediaLocationId(val value: String) {
    init {
        require(value.isStableId()) { "MediaLocationId must be a stable identifier." }
    }
}

@JvmInline
value class VolumeId(val value: String) {
    init {
        require(value.isStableId()) { "VolumeId must be a stable identifier." }
    }
}

@JvmInline
value class MediaUri(val value: String) {
    init {
        require(value.startsWith("content://") || value.startsWith("file://")) {
            "MediaUri must use content or file scheme."
        }
    }
}

enum class MediaSourceMode {
    ALL_FILES,
    MEDIA_STORE,
    SAF_TREE,
}

enum class MediaSourceAccessState {
    AVAILABLE,
    OFFLINE,
    PERMISSION_LOST,
}

data class MediaSource(
    val id: MediaSourceId,
    val displayName: String,
    val rootUri: MediaUri,
    val mode: MediaSourceMode,
    val volumeId: VolumeId?,
    val accessState: MediaSourceAccessState = MediaSourceAccessState.AVAILABLE,
    val includeHidden: Boolean = false,
    val lastSyncedEpochMillis: Long? = null,
    val mediaCount: Int = 0,
) {
    init {
        require(displayName.isNotBlank())
        require(mediaCount >= 0)
    }
}

data class MediaItem(
    val id: MediaItemId,
    val title: String,
    val playbackPositionMillis: Long = 0,
    val completed: Boolean = false,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(title.isNotBlank())
        require(playbackPositionMillis >= 0)
    }
}

data class MediaLocation(
    val id: MediaLocationId,
    val sourceId: MediaSourceId,
    val uri: MediaUri,
    val volumeId: VolumeId?,
    val documentId: String?,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val missingScanCount: Int = 0,
    val lastSeenEpochMillis: Long,
    val fastFingerprint: String? = null,
    val contentHash: String? = null,
) {
    init {
        require(fileName.isNotBlank())
        require(mimeType.startsWith("video/"))
        require(sizeBytes >= 0)
        require(modifiedEpochMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(missingScanCount >= 0)
        require(fastFingerprint == null || fastFingerprint.isNotBlank())
        require(contentHash == null || contentHash.isNotBlank())
    }
}

data class MediaIdentityEvidence(
    val uri: MediaUri,
    val volumeId: VolumeId?,
    val documentId: String?,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val fastFingerprint: String? = null,
    val contentHash: String? = null,
)

data class MediaCandidate(
    val sourceId: MediaSourceId,
    val evidence: MediaIdentityEvidence,
    val mimeType: String,
)

enum class ScanMode {
    INCREMENTAL,
    FULL,
}

data class ScanRequest(
    val sourceIds: Set<MediaSourceId>,
    val mode: ScanMode = ScanMode.INCREMENTAL,
) {
    init {
        require(sourceIds.isNotEmpty())
    }
}

enum class ScanFailureKind {
    PERMISSION,
    SOURCE_OFFLINE,
    UNSUPPORTED,
    MALFORMED_ENTRY,
    IO,
}

data class ScanFailure(
    val sourceId: MediaSourceId,
    val kind: ScanFailureKind,
    val recoverable: Boolean,
)

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unsupported: Int,
    val failures: List<ScanFailure>,
    val elapsedMillis: Long,
) {
    init {
        require(listOf(added, updated, missing, unsupported).all { it >= 0 })
        require(elapsedMillis >= 0)
    }
}

enum class ThumbnailPriority(val rank: Int) {
    VISIBLE(0),
    CONTINUE_WATCHING(1),
    RECENTLY_ADDED(2),
    BACKGROUND(3),
}

data class ThumbnailRequest(
    val mediaItemId: MediaItemId,
    val locationId: MediaLocationId,
    val uri: MediaUri,
    val widthPixels: Int,
    val heightPixels: Int,
    val priority: ThumbnailPriority,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0)
    }

    val cacheKey: String = "${mediaItemId.value}:${locationId.value}:$widthPixels:$heightPixels"
}

private fun String.isStableId(): Boolean = matches(Regex("[A-Za-z0-9_-]{1,128}"))
