package seeyuer.yingli.player.engine.media3

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun Media3VideoSurface(controller: Media3PlaybackController, modifier: Modifier = Modifier) {
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val playbackState by controller.state.collectAsStateWithLifecycle()
    val scaleMode by controller.scaleMode.collectAsStateWithLifecycle()
    var playerView: PlayerView? = null
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = scaleMode.toResizeMode()
                setShutterBackgroundColor(Color.BLACK)
                player = controller.connectedPlayer()
                controller.attachPlayerView(this)
                playerView = this
            }
        },
        update = { view ->
            connectionState
            playbackState
            scaleMode
            view.player = controller.connectedPlayer()
            view.resizeMode = scaleMode.toResizeMode()
            playerView = view
        },
        modifier = modifier.fillMaxSize(),
    )
    DisposableEffect(controller) {
        onDispose {
            playerView?.player = null
            controller.attachPlayerView(null)
        }
    }
}

@UnstableApi
private fun seeyuer.yingli.player.domain.playback.VideoScaleMode.toResizeMode(): Int = when (this) {
    seeyuer.yingli.player.domain.playback.VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    seeyuer.yingli.player.domain.playback.VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    seeyuer.yingli.player.domain.playback.VideoScaleMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
}
