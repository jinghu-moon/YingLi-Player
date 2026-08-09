package seeyuer.yingli.player.domain.playback

enum class PlaybackErrorKind {
    PERMISSION,
    FILE_MISSING,
    UNSUPPORTED_CONTAINER,
    UNSUPPORTED_DECODER,
    CORRUPT_MEDIA,
    UNKNOWN,
}

enum class PlaybackRecoveryAction {
    REAUTHORIZE,
    RELOCATE,
    VIEW_COMPATIBILITY,
    RETRY,
}

data class PlaybackError(
    val kind: PlaybackErrorKind,
    val recoveryAction: PlaybackRecoveryAction,
    val recoverable: Boolean,
    val diagnosticCode: String,
)

enum class PlaybackFailureSignal {
    ACCESS_DENIED,
    NOT_FOUND,
    CONTAINER_UNSUPPORTED,
    DECODER_UNAVAILABLE,
    MALFORMED_MEDIA,
    OTHER,
}

fun interface PlaybackErrorMapper {
    fun map(signal: PlaybackFailureSignal): PlaybackError
}

object DefaultPlaybackErrorMapper : PlaybackErrorMapper {
    override fun map(signal: PlaybackFailureSignal): PlaybackError = when (signal) {
        PlaybackFailureSignal.ACCESS_DENIED -> PlaybackError(
            PlaybackErrorKind.PERMISSION,
            PlaybackRecoveryAction.REAUTHORIZE,
            recoverable = true,
            diagnosticCode = "PLAYBACK_ACCESS_DENIED",
        )
        PlaybackFailureSignal.NOT_FOUND -> PlaybackError(
            PlaybackErrorKind.FILE_MISSING,
            PlaybackRecoveryAction.RELOCATE,
            recoverable = true,
            diagnosticCode = "PLAYBACK_SOURCE_NOT_FOUND",
        )
        PlaybackFailureSignal.CONTAINER_UNSUPPORTED -> PlaybackError(
            PlaybackErrorKind.UNSUPPORTED_CONTAINER,
            PlaybackRecoveryAction.VIEW_COMPATIBILITY,
            recoverable = false,
            diagnosticCode = "PLAYBACK_CONTAINER_UNSUPPORTED",
        )
        PlaybackFailureSignal.DECODER_UNAVAILABLE -> PlaybackError(
            PlaybackErrorKind.UNSUPPORTED_DECODER,
            PlaybackRecoveryAction.VIEW_COMPATIBILITY,
            recoverable = false,
            diagnosticCode = "PLAYBACK_DECODER_UNAVAILABLE",
        )
        PlaybackFailureSignal.MALFORMED_MEDIA -> PlaybackError(
            PlaybackErrorKind.CORRUPT_MEDIA,
            PlaybackRecoveryAction.RETRY,
            recoverable = true,
            diagnosticCode = "PLAYBACK_MEDIA_CORRUPT",
        )
        PlaybackFailureSignal.OTHER -> PlaybackError(
            PlaybackErrorKind.UNKNOWN,
            PlaybackRecoveryAction.RETRY,
            recoverable = true,
            diagnosticCode = "PLAYBACK_UNKNOWN",
        )
    }
}
