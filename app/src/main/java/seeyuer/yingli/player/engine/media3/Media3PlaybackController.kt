package seeyuer.yingli.player.engine.media3

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DefaultPlaybackErrorMapper
import seeyuer.yingli.player.domain.playback.AdvancedPlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.PlaybackAction
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackFailureSignal
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackStateReducer
import seeyuer.yingli.player.domain.playback.PlaybackTransition
import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.playback.SurfaceOwner
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.domain.security.SecurePlaybackController
import seeyuer.yingli.player.domain.security.VaultItemId

class Media3PlaybackController(
    context: Context,
    private val serviceComponent: ComponentName,
    private val sourceRepository: PlaybackSourceRepository,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
) : AdvancedPlaybackController, SecurePlaybackController, PlaybackMediaInfoProvider, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val vaultTitle = applicationContext.getString(seeyuer.yingli.player.R.string.vault_title)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val mutableConnectionState = MutableStateFlow(PlaybackConnectionState.CONNECTING)
    override val connectionState: StateFlow<PlaybackConnectionState> = mutableConnectionState.asStateFlow()
    private val mutableAudioTracks = MutableStateFlow<List<TrackChoice>>(emptyList())
    override val audioTracks: StateFlow<List<TrackChoice>> = mutableAudioTracks.asStateFlow()
    private val mutableSubtitleTracks = MutableStateFlow<List<TrackChoice>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackChoice>> = mutableSubtitleTracks.asStateFlow()
    private val mutableSpeed = MutableStateFlow(PlaybackSpeed.Normal)
    override val speed: StateFlow<PlaybackSpeed> = mutableSpeed.asStateFlow()
    private val mutableScaleMode = MutableStateFlow(VideoScaleMode.FIT)
    override val scaleMode: StateFlow<VideoScaleMode> = mutableScaleMode.asStateFlow()
    private val mutableMediaInfo = MutableStateFlow<PlaybackMediaInfo?>(null)
    override val mediaInfo: StateFlow<PlaybackMediaInfo?> = mutableMediaInfo.asStateFlow()
    private var controller: MediaController? = null
    private var playerViewReference = WeakReference<PlayerView>(null)
    private val playerViewLeaseGuard = SurfaceLeaseGuard()
    private var secureSessionActive = false
    private var connectionGeneration = 0L
    private var releaseControllerFuture: (() -> Unit)? = null
    private var reconnectJob: Job? = null
    private var closed = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateFromPlayer(player)
            updateTracks(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = error.toPlaybackError()
            mutableState.value = PlaybackState.Failed(
                mutableState.value.request,
                mutableState.value.timeline,
                mapped,
            )
            logger.log(
                AppLogLevel.ERROR,
                AppLogEvent(
                    code = mapped.diagnosticCode,
                    message = "Media3 reported a playback failure.",
                    attributes = mapOf("media3Code" to LogValue.Public(error.errorCode.toString())),
                ),
            )
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@Media3PlaybackController.controller === controller) {
                controller.removeListener(playerListener)
                this@Media3PlaybackController.controller = null
                mutableConnectionState.value = PlaybackConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    init {
        connect()
    }

    private fun connect() {
        if (closed || controller != null) return
        reconnectJob?.cancel()
        reconnectJob = null
        connectionGeneration += 1L
        val generation = connectionGeneration
        mutableConnectionState.value = PlaybackConnectionState.CONNECTING
        releaseControllerFuture?.invoke()
        val future = MediaController.Builder(
            applicationContext,
            SessionToken(applicationContext, serviceComponent),
        ).setListener(controllerListener).buildAsync()
        releaseControllerFuture = { MediaController.releaseFuture(future) }
        future.addListener(
            {
                scope.launch {
                    try {
                        val connected = future.get()
                        if (closed || generation != connectionGeneration) {
                            MediaController.releaseFuture(future)
                            return@launch
                        }
                        controller = connected
                        connected.addListener(playerListener)
                        mutableConnectionState.value = PlaybackConnectionState.CONNECTED
                        applyVideoOutputSelection(connected)
                        updateFromPlayer(connected)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (closed || generation != connectionGeneration) return@launch
                        mutableConnectionState.value = PlaybackConnectionState.FAILED
                        mutableState.value = PlaybackState.Failed(
                            mutableState.value.request,
                            mutableState.value.timeline,
                            DefaultPlaybackErrorMapper.map(PlaybackFailureSignal.OTHER),
                        )
                        scheduleReconnect()
                    }
                }
            },
            Executor(Runnable::run),
        )
    }

    private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MILLIS)
            connect()
        }
    }

    override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
        val activeController = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        val current = mutableState.value
        if (current.request == request && current !is PlaybackState.Failed && current !is PlaybackState.Ended) {
            return PlaybackCommandResult.AlreadyApplied
        }
        secureSessionActive = false
        // 新请求从"未就绪"开始：下一次 BUFFERING 才算首次准备。
        hasEverBeenReady = false
        mutableState.value = PlaybackStateReducer.reduce(current, PlaybackTransition.Prepare(request))
        scope.launch(dispatchers.io) {
            val source = try {
                sourceRepository.resolve(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                fail(request, PlaybackFailureSignal.ACCESS_DENIED)
                return@launch
            } catch (_: FileNotFoundException) {
                fail(request, PlaybackFailureSignal.NOT_FOUND)
                return@launch
            } catch (_: Exception) {
                fail(request, PlaybackFailureSignal.OTHER)
                return@launch
            }
            if (source == null) {
                fail(request, PlaybackFailureSignal.NOT_FOUND)
                return@launch
            }
            withContext(dispatchers.main) {
                if (mutableState.value.request != request || controller !== activeController) return@withContext
                val extras = Bundle().apply {
                    putString(PlaybackMediaMetadata.LOCATION_ID, request.locationId.value)
                    putBoolean(PlaybackMediaMetadata.INCOGNITO, request.incognito)
                    putString(PlaybackMediaMetadata.SOURCE_CONTEXT, request.sourceContext.name)
                }
                val mediaItem = MediaItem.Builder()
                    .setMediaId(request.mediaId.value)
                    .setUri(Uri.parse(source.uri))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).setExtras(extras).build())
                    .build()
                activeController.setMediaItem(mediaItem, request.startPositionMillis)
                activeController.prepare()
                activeController.play()
            }
        }
        return PlaybackCommandResult.Accepted
    }

    override fun prepare(source: ResolvedPlaybackSource): PlaybackCommandResult {
        val activeController = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        val request = source.request
        val current = mutableState.value
        if (current.request == request && current !is PlaybackState.Failed && current !is PlaybackState.Ended) {
            return PlaybackCommandResult.AlreadyApplied
        }
        secureSessionActive = false
        // 每个媒体项都有独立的准备生命周期，不能继承上一项的 READY 事实。
        hasEverBeenReady = false
        mutableMediaInfo.value = PlaybackMediaInfo(
            title = source.title,
            durationMillis = source.durationMillis,
            width = source.width,
            height = source.height,
            fileSizeBytes = source.fileSizeBytes,
        )
        mutableState.value = PlaybackStateReducer.reduce(current, PlaybackTransition.Prepare(request))
        val extras = Bundle().apply {
            putString(PlaybackMediaMetadata.LOCATION_ID, request.locationId.value)
            putBoolean(PlaybackMediaMetadata.INCOGNITO, request.incognito)
            putString(PlaybackMediaMetadata.SOURCE_CONTEXT, request.sourceContext.name)
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(request.mediaId.value)
            .setUri(Uri.parse(source.uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).setExtras(extras).build())
            .build()
        activeController.setMediaItem(mediaItem, request.startPositionMillis)
        activeController.prepare()
        activeController.play()
        return PlaybackCommandResult.Accepted
    }

    override fun prepare(itemId: VaultItemId): Boolean {
        val activeController = controller ?: return false
        val request = PlaybackRequest(
            MediaItemId("vault-${itemId.value}"),
            MediaLocationId("vault-${itemId.value}"),
            startPositionMillis = 0,
            sourceContext = PlaybackSourceContext.DETAIL,
            incognito = true,
        )
        val extras = Bundle().apply {
            putString(PlaybackMediaMetadata.LOCATION_ID, request.locationId.value)
            putBoolean(PlaybackMediaMetadata.INCOGNITO, true)
            putString(PlaybackMediaMetadata.SOURCE_CONTEXT, request.sourceContext.name)
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(request.mediaId.value)
            .setUri(Uri.Builder().scheme(VAULT_SCHEME).authority(itemId.value).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(vaultTitle).setExtras(extras).build())
            .build()
        secureSessionActive = true
        // 安全媒体同样是新会话，首次 BUFFERING 必须显示初始准备状态。
        hasEverBeenReady = false
        mutableState.value = PlaybackState.Preparing(request)
        activeController.setMediaItem(mediaItem)
        activeController.prepare()
        activeController.play()
        return true
    }

    override fun invalidateSecureSession() {
        if (!secureSessionActive) return
        stop()
    }

    override fun play(): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (mutableState.value is PlaybackState.Ended) {
            active.seekTo(0)
            active.play()
            return PlaybackCommandResult.Accepted
        }
        return execute(PlaybackAction.PLAY, MediaController::play)
    }

    override fun pause(): PlaybackCommandResult = execute(PlaybackAction.PAUSE, MediaController::pause)

    override fun seekTo(positionMillis: Long): PlaybackCommandResult = execute(PlaybackAction.SEEK) { active ->
        active.seekTo(positionMillis.coerceAtLeast(0))
    }

    override fun seekBy(offsetMillis: Long): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (PlaybackAction.SEEK !in mutableState.value.availableActions) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE)
        }
        val maximum = active.duration.takeUnless { it == C.TIME_UNSET || it < 0 } ?: Long.MAX_VALUE
        active.seekTo((active.currentPosition + offsetMillis).coerceIn(0, maximum))
        return PlaybackCommandResult.Accepted
    }

    override fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        active.setPlaybackSpeed(speed.value)
        mutableSpeed.value = speed
        return PlaybackCommandResult.Accepted
    }

    override fun selectAudioTrack(id: String): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        val choice = mutableAudioTracks.value.firstOrNull { it.id == id }
            ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.SOURCE_UNAVAILABLE)
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(choice.language)
            .build()
        return PlaybackCommandResult.Accepted
    }

    override fun selectSubtitleTrack(id: String?): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        val builder = active.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val choice = mutableSubtitleTracks.value.firstOrNull { it.id == id }
                ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.SOURCE_UNAVAILABLE)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(choice.language)
        }
        active.trackSelectionParameters = builder.build()
        return PlaybackCommandResult.Accepted
    }

    override fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult {
        mutableScaleMode.value = mode
        return PlaybackCommandResult.Accepted
    }

    override fun stop(): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (mutableState.value == PlaybackState.Idle) return PlaybackCommandResult.AlreadyApplied
        active.stop()
        active.clearMediaItems()
        secureSessionActive = false
        mutableState.value = PlaybackState.Idle
        return PlaybackCommandResult.Accepted
    }

    override fun retry(): PlaybackCommandResult {
        val failed = mutableState.value as? PlaybackState.Failed
            ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE)
        val request = failed.request
            ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.SOURCE_UNAVAILABLE)
        mutableState.value = PlaybackState.Idle
        return prepare(request)
    }

    fun connectedPlayer(): Player? = controller

    fun attachPlayerView(view: PlayerView?): SurfaceLease? {
        if (view == null) return null
        val lease = playerViewLeaseGuard.acquire(
            SurfaceOwner.REGULAR_PLAYER,
            "player-view-${System.identityHashCode(view)}",
        )
        playerViewReference = WeakReference(view)
        setVideoOutputEnabled(true)
        return lease
    }

    fun detachPlayerView(view: PlayerView, lease: SurfaceLease): Result<Unit> =
        if (playerViewReference.get() === view && playerViewLeaseGuard.release(lease)) {
            playerViewReference = WeakReference(null)
            view.player = null
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("STALE_SURFACE_LEASE"))
        }

    fun attachedPlayerView(): PlayerView? = playerViewReference.get()

    /**
     * 视频输出视图在**窗口坐标系**里的矩形；画中画入场动画用它做 `setSourceRectHint`，
     * 系统才知道"这个浮窗是从画面里长出来的"，而不是从整屏缩放过去。
     *
     * 没有输出、还没测量、或已经脱离窗口时返回 null：宁可不下发提示，也不要给一个错的矩形
     * （错的起点比没有起点更难看）。
     *
     * `PlayerView.getVideoSurfaceView()` 标着 `@UnstableApi`：它是拿到"真实输出视图"的唯一入口，
     * 所以就近 opt-in；这里只读它的尺寸与位置，不碰 Media3 的任何播放语义。
     */
    @OptIn(UnstableApi::class)
    fun videoSurfaceBoundsInWindow(): Rect? {
        val surfaceView = attachedPlayerView()?.videoSurfaceView ?: return null
        if (!surfaceView.isAttachedToWindow || surfaceView.width <= 0 || surfaceView.height <= 0) return null
        val location = IntArray(2)
        surfaceView.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + surfaceView.width, location[1] + surfaceView.height)
    }

    /** Disable only video track selection while background audio continues. */
    fun setVideoOutputEnabled(enabled: Boolean) {
        videoOutputEnabled = enabled
        controller?.let(::applyVideoOutputSelection)
    }

    override fun close() {
        closed = true
        reconnectJob?.cancel()
        controller?.removeListener(playerListener)
        controller = null
        playerViewLeaseGuard.clear()
        releaseControllerFuture?.invoke()
        releaseControllerFuture = null
        scope.cancel()
    }

    private fun execute(action: PlaybackAction, command: (MediaController) -> Unit): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (action !in mutableState.value.availableActions) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE)
        }
        command(active)
        return PlaybackCommandResult.Accepted
    }

    private suspend fun fail(request: PlaybackRequest, signal: PlaybackFailureSignal) {
        withContext(dispatchers.main) {
            if (mutableState.value.request == request) {
                mutableState.value = PlaybackState.Failed(
                    request,
                    mutableState.value.timeline,
                    DefaultPlaybackErrorMapper.map(signal),
                )
            }
        }
    }

    private fun updateFromPlayer(player: Player) {
        val request = mutableState.value.request ?: requestFrom(player.currentMediaItem, player.currentPosition) ?: return
        // 记住"是否曾经就绪过"：重缓冲与首次准备在这里分岔（见 withPlayerState）。
        if (player.playbackState == Player.STATE_READY) hasEverBeenReady = true
        mutableState.value = mutableState.value.withPlayerState(player, request, hasEverBeenReady)
    }

    /** 见 [withPlayerState]：新文件重新开始后，下一次 BUFFERING 又算"首次准备"。 */
    private var hasEverBeenReady = false

    private var videoOutputEnabled = true

    private fun applyVideoOutputSelection(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !videoOutputEnabled)
            .build()
    }

    private fun updateTracks(player: Player) {
        mutableAudioTracks.value = player.trackChoices(androidx.media3.common.C.TRACK_TYPE_AUDIO, "Audio")
        mutableSubtitleTracks.value = player.trackChoices(androidx.media3.common.C.TRACK_TYPE_TEXT, "Subtitle")
        mutableSpeed.value = runCatching { PlaybackSpeed.of(player.playbackParameters.speed) }
            .getOrDefault(PlaybackSpeed.Normal)
        updateMediaInfo(player)
    }

    private fun updateMediaInfo(player: Player) {
        val current = mutableMediaInfo.value ?: return
        val video = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
        val audio = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
        mutableMediaInfo.value = current.copy(
            width = video?.width?.takeIf { it > 0 } ?: current.width,
            height = video?.height?.takeIf { it > 0 } ?: current.height,
            videoCodec = video?.codecs ?: video?.sampleMimeType ?: current.videoCodec,
            audioCodec = audio?.codecs ?: audio?.sampleMimeType ?: current.audioCodec,
            frameRate = video?.frameRate?.takeIf { it > 0 } ?: current.frameRate,
            durationMillis = player.duration.takeUnless { it < 0 || it == C.TIME_UNSET } ?: current.durationMillis,
        )
    }

    private fun requestFrom(mediaItem: MediaItem?, positionMillis: Long): PlaybackRequest? =
        mediaItem?.toPlaybackRequest(positionMillis)

    private companion object {
        const val RECONNECT_DELAY_MILLIS = 250L
        const val VAULT_SCHEME = "vault"
    }
}
