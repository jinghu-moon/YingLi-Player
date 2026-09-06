package seeyuer.yingli.player.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.CachePolicy
import coil3.request.crossfade
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader
import seeyuer.yingli.player.engine.thumbnail.ThumbnailStorage
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.domain.thumbnail.ThumbnailState

@Composable
fun YingLiThumbnail(
    request: ThumbnailRequest,
    repository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (repository == null) {
        Box(modifier.fillMaxSize().background(YingLiTheme.colors.surfaceMuted))
        return
    }
    val context = LocalContext.current
    DisposableEffect(request.key, repository) {
        repository.request(request)
        onDispose { repository.cancel(request) }
    }
    val state = repository.observe(request).collectAsStateWithLifecycle(ThumbnailState.Queued).value
    val cachedFile = remember(request.key, context) {
        context.cacheDir.resolve("${ThumbnailStorage.DIRECTORY_NAME}/${request.key.diskName()}.png")
    }
    if (cachedFile.isFile && state is ThumbnailState.Ready) {
        val imageRequest = remember(request.key, context) {
            coil3.request.ImageRequest.Builder(context)
                .data(cachedFile)
                .size(request.widthPixels, request.heightPixels)
                .memoryCacheKey(request.key.diskName())
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build()
        }
        coil3.compose.AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier.fillMaxSize().background(YingLiTheme.colors.surfaceMuted))
    }
}
