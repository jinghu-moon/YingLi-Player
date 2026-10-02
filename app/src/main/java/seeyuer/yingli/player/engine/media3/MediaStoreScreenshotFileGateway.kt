package seeyuer.yingli.player.engine.media3

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.playback.ScreenshotDeleteFailure
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway

class MediaStoreScreenshotFileGateway(
    private val dispatchers: AppDispatchers,
    private val deleteOperation: (String) -> Int,
) : ScreenshotFileGateway {
    constructor(resolver: ContentResolver, dispatchers: AppDispatchers) : this(
        dispatchers = dispatchers,
        deleteOperation = { value -> resolver.delete(Uri.parse(value), null, null) },
    )

    override suspend fun delete(uri: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            uri.takeIf(String::isNotBlank) ?: error(ScreenshotDeleteFailure.URI_INVALID.code)
            if (deleteOperation(uri) != 1) error(ScreenshotDeleteFailure.FAILED.code)
            Result.success(Unit)
        } catch (error: SecurityException) {
            Result.failure(IllegalStateException(ScreenshotDeleteFailure.PERMISSION_DENIED.code, error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
