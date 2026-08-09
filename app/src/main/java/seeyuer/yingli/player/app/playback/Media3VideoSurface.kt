package seeyuer.yingli.player.app

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
    var playerView: PlayerView? = null
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                player = controller.connectedPlayer()
                playerView = this
            }
        },
        update = { view ->
            connectionState
            playbackState
            view.player = controller.connectedPlayer()
            playerView = view
        },
        modifier = modifier.fillMaxSize(),
    )
    DisposableEffect(controller) {
        onDispose { playerView?.player = null }
    }
}
