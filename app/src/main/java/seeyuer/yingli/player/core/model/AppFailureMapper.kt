package seeyuer.yingli.player.core.model

import java.io.IOException
import kotlinx.coroutines.CancellationException

class DatabaseAccessException(cause: Throwable? = null) : Exception(cause)

class UnsupportedMediaException(cause: Throwable? = null) : Exception(cause)

class MediaDecodingException(cause: Throwable? = null) : Exception(cause)

fun interface AppFailureMapper {
    fun map(cause: Throwable): AppFailure
}

object DefaultAppFailureMapper : AppFailureMapper {
    override fun map(cause: Throwable): AppFailure =
        when (cause) {
            is SecurityException -> AppFailure.PermissionDenied
            is IOException -> AppFailure.StorageUnavailable
            is DatabaseAccessException -> AppFailure.DatabaseUnavailable
            is UnsupportedMediaException -> AppFailure.UnsupportedMediaFormat
            is MediaDecodingException -> AppFailure.DecodingFailed
            is CancellationException -> AppFailure.Cancelled
            else -> AppFailure.Unknown(causeType = cause::class.qualifiedName ?: "unknown")
        }
}
