package seeyuer.yingli.player.engine.media3

import android.graphics.Color
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.playback.SurfaceLease

/**
 * Media3 播放输出。
 *
 * [transformed] 表示这份输出会被视图层级变换（画面旋转）：此时必须用 TextureView，
 * 因为 SurfaceView 的内容由系统单独合成，不跟随 `graphicsLayer` 的旋转，
 * 只会看到画框在转、画面被拉伸。TextureView 需要额外一次 GPU 拷贝且 HDR 不能直通，
 * 所以只在 [transformed] 为真时切换，静止状态仍用 SurfaceView 保留 HDR 与功耗表现。
 */
@OptIn(UnstableApi::class)
@Composable
fun Media3VideoSurface(
    controller: Media3PlaybackController,
    // modifier 必须排在所有可选参数之前（Compose 的 ModifierParameter 规则）。
    modifier: Modifier = Modifier,
    transformed: Boolean = false,
) {
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val playbackState by controller.state.collectAsStateWithLifecycle()
    val scaleMode by controller.scaleMode.collectAsStateWithLifecycle()
    // 输出类型变化时整棵子树重建：旧的 PlayerView 必须解绑并释放租约，否则会留下第二个输出。
    key(transformed) {
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        var playerLease by remember { mutableStateOf<SurfaceLease?>(null) }
        AndroidView(
            factory = { context ->
                val view = if (transformed) {
                    // AndroidView 的 factory 只给 Context，没有现成的父容器。根节点是 match_parent，
                    // 传 null 会把这两个布局参数直接丢掉，所以显式给一个一次性的父容器作为解析基准
                    //（attachToRoot = false，不会真的挂上去；之后由 Compose 按自己的约束重新挂载）。
                    LayoutInflater.from(context)
                        .inflate(R.layout.view_player_texture, FrameLayout(context), false) as PlayerView
                } else {
                    PlayerView(context)
                }
                view.useController = false
                view.resizeMode = scaleMode.toResizeMode()
                // 关掉 Media3 自带的缓冲转圈：它在 STATE_BUFFERING 时会在画面上叠一个加载圈，
                // 进度条跳转 / 快进快退 都会触发。本地播放要"实时"，加载指示只由
                // PlayerStatusOverlay 在"首次准备"时给出（重缓冲已由引擎区分，不再显示）。
                view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // 透明快门：seek 期间保留上一帧，而不是盖一层黑（黑也会被误认为"在加载"）。
                view.setShutterBackgroundColor(Color.TRANSPARENT)
                view.player = controller.connectedPlayer()
                playerLease = controller.attachPlayerView(view)
                playerView = view
                view
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
                val view = playerView
                val lease = playerLease
                if (view != null && lease != null) controller.detachPlayerView(view, lease)
            }
        }
    }
}

@UnstableApi
private fun seeyuer.yingli.player.domain.playback.VideoScaleMode.toResizeMode(): Int = when (this) {
    seeyuer.yingli.player.domain.playback.VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    seeyuer.yingli.player.domain.playback.VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    seeyuer.yingli.player.domain.playback.VideoScaleMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
}
