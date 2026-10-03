package seeyuer.yingli.player.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.annotation.OptIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import seeyuer.yingli.player.app.MainActivity
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.app.YingLiApplication
import seeyuer.yingli.player.domain.playback.PlaybackProgressSample
import seeyuer.yingli.player.domain.playback.PlaybackProgressWritePolicy
import seeyuer.yingli.player.domain.playback.ProgressWriteDecision
import seeyuer.yingli.player.domain.playback.ProgressWriteReason
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSourceResolver
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopSessionCommands
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.data.security.VaultAwareDataSource
import seeyuer.yingli.player.engine.media3.PlaybackMediaMetadata

class YingLiPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var application: YingLiApplication
    private lateinit var serviceScope: CoroutineScope
    private lateinit var writeScope: CoroutineScope
    private lateinit var sessionRuntime: PlaybackSessionRuntime
    private lateinit var sessionEngine: ServicePlaybackEngine
    private val progressPolicy = PlaybackProgressWritePolicy()
    private var periodicProgressJob: Job? = null
    private var historyRecordedMediaId: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPeriodicProgress() else {
                stopPeriodicProgress()
                persistProgress(ProgressWriteReason.PAUSED)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> persistProgress(ProgressWriteReason.ENDED)
                Player.STATE_IDLE -> persistProgress(ProgressWriteReason.STOPPED)
                Player.STATE_BUFFERING,
                Player.STATE_READY,
                -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            progressPolicy.reset()
            historyRecordedMediaId = null
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        application = getApplication() as YingLiApplication
        serviceScope = CoroutineScope(SupervisorJob() + application.container.dispatchers.main)
        writeScope = CoroutineScope(SupervisorJob() + application.container.dispatchers.io)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            VaultAwareDataSource.Factory(this, application.mediaContainer.securePlaybackSource),
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            it.setAudioAttributes(AudioAttributes.DEFAULT, true)
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(listener)
        }
        val sourceRegistry = SourceHandleRegistry()
        // 跳转精度开关由播放页写、这里读：同一个进程内共享同一个实例，所以"只改策略不打断播放"，
        // 不需要为一条控制指令重建媒体或接管 MediaSession 的 onConnect。
        sessionEngine = ServicePlaybackEngine(
            player,
            sourceRegistry,
            application.container.dispatchers,
            application.mediaContainer.seekPrecisionControl,
        )
        val resolver = PlaybackSourceResolver { request: PlaybackOpenRequest ->
            runCatching {
                sourceRegistry.resolvePending(request)?.let { return@runCatching Result.success(it) }
                val resolved = application.mediaContainer.playbackSourceRepository.resolve(
                    request.mediaId,
                    request.sourceContext,
                    request.incognito,
                ) ?: return@runCatching Result.failure<seeyuer.yingli.player.domain.playback.PlaybackSourceHandle>(
                    IllegalStateException("SOURCE_NOT_FOUND"),
                )
                val accessId = "${request.sessionId.value}:${request.mediaId.value}"
                sourceRegistry.put(accessId, resolved.uri)
                Result.success(
                    seeyuer.yingli.player.domain.playback.PlaybackSourceHandle(
                        mediaId = request.mediaId,
                        locationId = resolved.request.locationId,
                        accessHandleId = SourceAccessHandleId(accessId),
                        displayName = resolved.title,
                        durationMillis = resolved.durationMillis,
                        width = resolved.width,
                        height = resolved.height,
                        fileSizeBytes = resolved.fileSizeBytes,
                        mimeType = resolved.mimeType,
                    ),
                )
            }.getOrElse { Result.failure(it) }
        }
        sessionRuntime = PlaybackSessionRuntime(
            resolver = resolver,
            engine = sessionEngine,
            dispatchers = application.container.dispatchers,
            sessionId = PlaybackSessionId("service-${hashCode()}"),
            speedControl = sessionEngine,
            transformControl = sessionEngine,
            elapsedTimeSource = ElapsedTimeSource(android.os.SystemClock::elapsedRealtime),
            frameCalibrationControl = application.mediaContainer.frameCalibrationControl,
            sourceUriLookup = sourceRegistry::resolve,
            vaultResolver = { itemId: VaultItemId, displayTitle: String ->
                val mediaId = MediaItemId("vault-${itemId.value}")
                val accessId = "${sessionRuntimeId()}:${mediaId.value}"
                sourceRegistry.put(accessId, "vault://${itemId.value}")
                Result.success(
                    PlaybackSourceHandle(
                        mediaId = mediaId,
                        locationId = MediaLocationId("vault-${itemId.value}"),
                        accessHandleId = SourceAccessHandleId(accessId),
                        displayName = displayTitle,
                        durationMillis = null,
                        mimeType = "video/*",
                    ),
                )
            },
        )
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, MediaSessionPlayerAdapter(player, sessionRuntime, sourceRegistry))
            .setSessionActivity(sessionActivity)
            .setCallback(abLoopSessionCallback)
            .build()
        // AB 状态与计数由会话 runtime 持有；这里只把它推给已连接的客户端（播放页投影用）。
        serviceScope.launch {
            sessionRuntime.abLoop.collect { publishAbLoopExtras(it) }
        }
    }

    /**
     * 会话侧命令入口。
     *
     * 为什么 AB 命令必须经过这里，而不是 UI 侧自己算：设点校验、A/B 互换、相等边界、帧吸附、
     * 启用策略、计数条件都属于会话（与引擎同一个 session）；UI 侧再算一份就回到"三份状态"。
     *
     * `onConnect` 里补上两个自定义 session command：Media3 只允许控制器发送**在连接时声明过**的
     * 自定义命令（`MediaControllerImplBase.getSessionInterfaceWithSessionCommandIfAble`：
     * 未声明的命令在客户端就被拦下并记一条 warning），不声明的话命令永远到不了这里。
     */
    private val abLoopSessionCallback = @OptIn(UnstableApi::class) object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(AbLoopSessionCommands.SET_POINT, Bundle.EMPTY))
                .add(SessionCommand(AbLoopSessionCommands.CLEAR, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                AbLoopSessionCommands.SET_POINT -> {
                    val point = args.getString(AbLoopSessionCommands.ARG_POINT)
                        ?.let { name -> runCatching { AbPoint.valueOf(name) }.getOrNull() }
                    if (point != null) serviceScope.launch { sessionRuntime.setPoint(point) }
                }
                AbLoopSessionCommands.CLEAR -> serviceScope.launch { sessionRuntime.clear() }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * 把会话 AB 状态推给客户端。
     *
     * 用 session extras 而不是另开一条自定义广播：AB 是"会话 → 客户端"的单向小状态，
     * extras 自带变更通知（`Player.EVENT_SESSION_EXTRAS_CHANGED`），
     * 客户端（`Media3PlaybackController`）据此更新投影，不需要新增一条同步通道。
     */
    private fun publishAbLoopExtras(session: AbLoopSession) {
        val extras = Bundle().apply {
            session.state.pointA?.let { putString(AbLoopSessionCommands.EXTRA_POINT_A, it.toString()) }
            session.state.pointB?.let { putString(AbLoopSessionCommands.EXTRA_POINT_B, it.toString()) }
            putString(AbLoopSessionCommands.EXTRA_LOOP_COUNT, session.loopCount.toString())
        }
        mediaSession.setSessionExtras(extras)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress(ProgressWriteReason.BACKGROUNDED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopPeriodicProgress()
        val finalWrite = persistProgress(ProgressWriteReason.STOPPED)
        mediaSession.release()
        sessionRuntime.close()
        player.removeListener(listener)
        player.release()
        serviceScope.cancel()
        if (finalWrite == null) {
            writeScope.cancel()
        } else {
            finalWrite.invokeOnCompletion { writeScope.cancel() }
        }
        super.onDestroy()
    }

    private fun startPeriodicProgress() {
        if (periodicProgressJob?.isActive == true) return
        periodicProgressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MILLIS)
                persistProgress(ProgressWriteReason.PERIODIC)
            }
        }
    }

    private fun stopPeriodicProgress() {
        periodicProgressJob?.cancel()
        periodicProgressJob = null
    }

    private fun persistProgress(reason: ProgressWriteReason): Job? {
        if (!::player.isInitialized) return null
        val mediaItem = player.currentMediaItem ?: return null
        val extras = mediaItem.mediaMetadata.extras
        val incognito = extras?.getBoolean(PlaybackMediaMetadata.INCOGNITO, false) == true
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        recordHistoryIfEligible(mediaItem, currentPosition, incognito)
        val decision = progressPolicy.evaluate(
            PlaybackProgressSample(
                positionMillis = currentPosition,
                durationMillis = player.duration.takeUnless { it == C.TIME_UNSET },
                incognito = incognito,
            ),
            reason,
            application.container.clock.now().toEpochMilli(),
        )
        if (decision !is ProgressWriteDecision.Write) return null
        val mediaId = runCatching { MediaItemId(mediaItem.mediaId) }.getOrNull() ?: return null
        return writeScope.launch {
            try {
                application.mediaContainer.playbackProgressRepository.saveProgress(
                    mediaId,
                    decision.positionMillis,
                    decision.completed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                application.container.logger.log(
                    AppLogLevel.WARNING,
                    AppLogEvent(
                        code = "PLAYBACK_PROGRESS_WRITE_FAILED",
                        message = "Playback progress could not be persisted.",
                        attributes = mapOf("reason" to LogValue.Public(reason.name)),
                    ),
                )
            }
        }
    }

    private fun recordHistoryIfEligible(mediaItem: MediaItem, positionMillis: Long, incognito: Boolean) {
        if (incognito || positionMillis < MINIMUM_HISTORY_POSITION_MILLIS || historyRecordedMediaId == mediaItem.mediaId) return
        val mediaId = runCatching { MediaItemId(mediaItem.mediaId) }.getOrNull() ?: return
        historyRecordedMediaId = mediaItem.mediaId
        writeScope.launch {
            try {
                application.mediaContainer.historyRepository.recordPlayback(
                    mediaId,
                    positionMillis,
                    application.container.clock.now().toEpochMilli(),
                    incognito = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                application.container.logger.log(
                    AppLogLevel.WARNING,
                    AppLogEvent(
                        code = "PLAYBACK_HISTORY_WRITE_FAILED",
                        message = "Playback history could not be persisted.",
                    ),
                )
            }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 5_000L
        const val MINIMUM_HISTORY_POSITION_MILLIS = 10_000L
    }

    private fun sessionRuntimeId(): String = "service-${hashCode()}"
}
