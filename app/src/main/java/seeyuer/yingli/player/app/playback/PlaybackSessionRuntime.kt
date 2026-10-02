package seeyuer.yingli.player.app.playback

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.AbLoopEvent
import seeyuer.yingli.player.domain.playback.AbLoopReducer
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCaptureRequest
import seeyuer.yingli.player.domain.playback.HistoryEligibilityPolicy
import seeyuer.yingli.player.domain.playback.NavigationDecision
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackCommandHandle
import seeyuer.yingli.player.domain.playback.PlaybackCommandId
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackQueueSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSourceResolver
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.domain.playback.SeekOrigin
import seeyuer.yingli.player.domain.playback.SnapshotControl
import seeyuer.yingli.player.domain.playback.SurfaceBindRequest
import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.playback.SpeedControl
import seeyuer.yingli.player.domain.playback.TrackSelection
import seeyuer.yingli.player.domain.playback.VideoTransformControl
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.security.VaultItemId

/**
 * The service-owned playback aggregate. Every command is serialized here;
 * engine callbacks are accepted only for the currently active open generation.
 */
class PlaybackSessionRuntime(
    private val resolver: PlaybackSourceResolver,
    private val engine: PlaybackEngine,
    private val dispatchers: AppDispatchers,
    private val sessionId: seeyuer.yingli.player.domain.playback.PlaybackSessionId,
    private val snapshotControl: SnapshotControl? = null,
    private val transformControl: VideoTransformControl? = null,
    private val speedControl: SpeedControl? = null,
    private val elapsedTimeSource: ElapsedTimeSource,
    private val vaultResolver: (suspend (VaultItemId, String) -> Result<seeyuer.yingli.player.domain.playback.PlaybackSourceHandle>)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.main),
) : PlaybackSessionClient, AutoCloseable {
    private val commandSequence = AtomicLong(0)
    private val commandMutex = Mutex()
    private val abReducer = AbLoopReducer()
    private val mutableSnapshot = MutableStateFlow(
        PlaybackSessionSnapshot(
            sessionId = sessionId,
            mediaId = null,
            title = null,
            phase = PlaybackPhase.Idle,
        ),
    )
    private val mutableEvents = MutableSharedFlow<PlaybackSessionEvent>(extraBufferCapacity = 32)
    private var commandJob: Job? = null
    private var openGeneration = 0L
    private var queue: PlaybackQueueSnapshot? = null
    private var playWhenReady = false
    private var activeRequest: PlaybackOpenRequest? = null

    override val snapshot: StateFlow<PlaybackSessionSnapshot> = mutableSnapshot.asStateFlow()
    override val events: SharedFlow<PlaybackSessionEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            engine.state.collect { state ->
                commandMutex.withLock { applyEngineState(state) }
            }
        }
        scope.launch {
            engine.capabilities.collect { capabilities ->
                commandMutex.withLock {
                    mutableSnapshot.value = mutableSnapshot.value.copy(capabilities = capabilities)
                    emitState()
                }
            }
        }
        (engine as? PlaybackMediaInfoProvider)?.let { provider ->
            scope.launch {
                provider.mediaInfo.collect { info ->
                    if (info != null) {
                        commandMutex.withLock {
                            mutableSnapshot.value = mutableSnapshot.value.copy(mediaInfo = info)
                            emitState()
                        }
                    }
                }
            }
        }
    }

    override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle {
        val id = PlaybackCommandId(commandSequence.incrementAndGet())
        commandJob = scope.launch {
            if (command is PlaybackSessionCommand.Open) open(id, command.request)
            else commandMutex.withLock { execute(id, command) }
        }
        return PlaybackCommandHandle(id)
    }

    fun setQueue(value: PlaybackQueueSnapshot?) {
        scope.launch {
            commandMutex.withLock {
                queue = value
                mutableSnapshot.value = mutableSnapshot.value.copy(queue = value)
                emitState()
            }
        }
    }

    private suspend fun execute(id: PlaybackCommandId, command: PlaybackSessionCommand) {
        when (command) {
            is PlaybackSessionCommand.Open -> error("Open is resolved outside the command lock")
            is PlaybackSessionCommand.OpenVault -> openVault(id, command.itemId, command.displayTitle)
            PlaybackSessionCommand.Play -> {
                playWhenReady = true
                engine.play()
            }
            PlaybackSessionCommand.Pause -> {
                playWhenReady = false
                engine.pause()
            }
            is PlaybackSessionCommand.Seek -> engine.seekTo(clampPosition(command.positionMillis))
            is PlaybackSessionCommand.SeekBy -> engine.seekTo(clampPosition(snapshot.value.timeline.positionMillis + command.offsetMillis))
            PlaybackSessionCommand.Stop -> {
                openGeneration++
                playWhenReady = false
                engine.stop()
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    mediaId = null,
                    title = null,
                    phase = PlaybackPhase.Idle,
                    timeline = PlaybackTimeline(),
                    abLoop = abReducer.reduce(snapshot.value.abLoop, AbLoopEvent.Clear).state,
                )
                emitState()
            }
            PlaybackSessionCommand.Retry -> if (snapshot.value.phase is PlaybackPhase.Failed) {
                val request = activeRequest
                if (request == null) feedback(PlaybackCommandRejection.INVALID_STATE.name)
                else scope.launch { open(id, request) }
            } else feedback(PlaybackCommandRejection.INVALID_STATE.name)
            PlaybackSessionCommand.Next -> navigate(id, next = true)
            PlaybackSessionCommand.Previous -> navigate(id, next = false)
            is PlaybackSessionCommand.SetOrder -> {
                queue = queue?.copy(order = command.order, shuffleHistory = emptyList())
                mutableSnapshot.value = mutableSnapshot.value.copy(queue = queue)
                emitState()
            }
            is PlaybackSessionCommand.SetSpeed -> {
                val control = speedControl
                if (control == null) feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
                else if (control.setSpeed(command.speed) is seeyuer.yingli.player.domain.playback.PlaybackCommandResult.Rejected) {
                    feedback(PlaybackCommandRejection.INVALID_STATE.name)
                }
            }
            is PlaybackSessionCommand.SelectTrack -> feedback("TRACK_DELEGATED:${command.selection.type}")
            is PlaybackSessionCommand.SetScale -> {
                val control = transformControl
                if (control == null) feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
                else if (control.setScaleMode(command.mode) is seeyuer.yingli.player.domain.playback.PlaybackCommandResult.Rejected) {
                    feedback(PlaybackCommandRejection.INVALID_STATE.name)
                }
            }
            is PlaybackSessionCommand.SetAbPoint -> {
                val current = snapshot.value
                val duration = current.timeline.durationMillis ?: 0L
                val update = abReducer.reduce(
                    current.abLoop,
                    AbLoopEvent.SetPoint(command.point, current.timeline.positionMillis, duration, null),
                )
                if (update.rejection != null) feedback(update.rejection.name)
                else {
                    mutableSnapshot.value = current.copy(abLoop = update.state)
                    emitState()
                }
            }
            PlaybackSessionCommand.ClearAb -> {
                mutableSnapshot.value = snapshot.value.copy(abLoop = abReducer.reduce(snapshot.value.abLoop, AbLoopEvent.Clear).state)
                emitState()
            }
            is PlaybackSessionCommand.BindSurface -> bindSurface(command.request)
            is PlaybackSessionCommand.UnbindSurface -> engine.unbindSurface(command.lease)
            PlaybackSessionCommand.CaptureFrame -> captureFrame()
        }
    }

    private suspend fun openVault(
        id: PlaybackCommandId,
        itemId: VaultItemId,
        displayTitle: String,
    ) {
        val resolver = vaultResolver
        if (resolver == null) {
            feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
            return
        }
        val request = PlaybackOpenRequest(
            sessionId = sessionId,
            mediaId = MediaItemId("vault-${itemId.value}"),
            sourceContext = seeyuer.yingli.player.domain.playback.PlaybackSourceContext.DETAIL,
            incognito = true,
        )
        val resolved = runCatching { resolver(itemId, displayTitle) }.getOrElse { Result.failure(it) }
        resolved.fold(
            onSuccess = { source ->
                activeRequest = request
                openGeneration += 1L
                playWhenReady = true
                mutableSnapshot.value = snapshot.value.copy(
                    sessionId = request.sessionId,
                    mediaId = request.mediaId,
                    title = source.displayName,
                    phase = PlaybackPhase.Preparing(BackendId.MEDIA3),
                    timeline = PlaybackTimeline(),
                    mediaInfo = PlaybackMediaInfo(
                        title = source.displayName,
                        durationMillis = source.durationMillis,
                        width = source.width,
                        height = source.height,
                        fileSizeBytes = source.fileSizeBytes,
                    ),
                )
                emitState()
                withContext(dispatchers.main) { engine.prepare(source, 0L) }
                withContext(dispatchers.main) { engine.play() }
            },
            onFailure = { feedback(PlaybackCommandRejection.SOURCE_UNAVAILABLE.name) },
        )
    }

    private suspend fun open(id: PlaybackCommandId, request: PlaybackOpenRequest) {
        val generation = commandMutex.withLock {
            openGeneration++
            mutableSnapshot.value = snapshot.value.copy(
                sessionId = request.sessionId,
                mediaId = request.mediaId,
                title = null,
                phase = PlaybackPhase.Resolving(id),
                timeline = PlaybackTimeline(request.startPositionMillis),
                abLoop = abReducer.reduce(snapshot.value.abLoop, AbLoopEvent.MediaChanged).state,
            )
            emitState()
            activeRequest = request
            openGeneration
        }
        val resolved = try {
            withContext(dispatchers.io) { resolver.resolve(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (commandMutex.withLock { generation != openGeneration }) return
        resolved.fold(
            onSuccess = { source ->
                val accepted = commandMutex.withLock {
                    if (generation != openGeneration) return@withLock false
                    mutableSnapshot.value = snapshot.value.copy(
                        title = source.displayName,
                        mediaInfo = PlaybackMediaInfo(
                            title = source.displayName,
                            durationMillis = source.durationMillis,
                            width = source.width,
                            height = source.height,
                            fileSizeBytes = source.fileSizeBytes,
                        ),
                        phase = PlaybackPhase.Preparing(BackendId.MEDIA3),
                    )
                    emitState()
                    true
                }
                if (!accepted) return@fold
                withContext(dispatchers.main) { engine.prepare(source, request.startPositionMillis) }
                if (commandMutex.withLock { generation == openGeneration && playWhenReady }) {
                    withContext(dispatchers.main) { engine.play() }
                }
            },
            onFailure = { if (commandMutex.withLock { generation == openGeneration }) feedback("SOURCE_RESOLUTION_FAILED") },
        )
    }

    private fun navigate(id: PlaybackCommandId, next: Boolean) {
        val currentQueue = queue ?: run { feedback(PlaybackCommandRejection.NO_CANDIDATE.name); return }
        val decision = if (next) {
            seeyuer.yingli.player.domain.playback.QueueNavigator().next(currentQueue, ended = false)
        } else {
            seeyuer.yingli.player.domain.playback.QueueNavigator().previous(currentQueue, snapshot.value.timeline.positionMillis)
        }
        when (decision) {
            is NavigationDecision.MoveTo -> {
                queue = currentQueue.copy(currentIndex = decision.index, shuffleHistory = decision.shuffleHistory)
                mutableSnapshot.value = snapshot.value.copy(queue = queue)
                emitState()
                if (decision.index == currentQueue.currentIndex) {
                    engine.seekTo(0)
                } else {
                    val request = activeRequest?.copy(mediaId = decision.mediaId, startPositionMillis = 0)
                    if (request == null) feedback(PlaybackCommandRejection.INVALID_STATE.name)
                    else scope.launch { open(id, request) }
                }
            }
            NavigationDecision.NoCandidate -> feedback(PlaybackCommandRejection.NO_CANDIDATE.name)
            NavigationDecision.StopAtEnd -> mutableSnapshot.value = snapshot.value.copy(phase = PlaybackPhase.Ended(null)).also { emitState() }
        }
    }

    private fun bindSurface(request: SurfaceBindRequest) {
        engine.bindSurface(request).onSuccess { lease ->
            mutableSnapshot.value = snapshot.value.copy(output = snapshot.value.output.copy(boundLease = lease))
            emitState()
        }
    }

    private suspend fun captureFrame() {
        val control = snapshotControl ?: run { feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name); return }
        val mediaId = snapshot.value.mediaId ?: run { feedback(PlaybackCommandRejection.INVALID_STATE.name); return }
        control.captureFrame(FrameCaptureRequest(mediaId, snapshot.value.timeline.positionMillis)).fold(
            onSuccess = { mutableEvents.emit(PlaybackSessionEvent.ScreenshotReady(it)) },
            onFailure = { feedback("SCREENSHOT_FAILED") },
        )
    }

    private fun applyEngineState(state: EngineState) {
        val current = snapshot.value
        val phase = when (state) {
            EngineState.Idle -> if (
                current.phase is PlaybackPhase.Resolving || current.phase is PlaybackPhase.Preparing
            ) current.phase else PlaybackPhase.Idle
            EngineState.Preparing -> PlaybackPhase.Preparing(current.backend?.id ?: BackendId.MEDIA3)
            is EngineState.Ready -> PlaybackPhase.Ready(state.timeline.isSeekable)
            is EngineState.Playing -> PlaybackPhase.Playing(elapsedTimeSource.nowMillis())
            is EngineState.Paused -> PlaybackPhase.Paused(seeyuer.yingli.player.domain.playback.PauseReason.USER)
            is EngineState.Buffering -> PlaybackPhase.Buffering(state.reason)
            is EngineState.Ended -> PlaybackPhase.Ended(null)
            is EngineState.Failed -> PlaybackPhase.Failed(state.error)
        }
        val timeline = when (state) {
            EngineState.Idle, EngineState.Preparing -> current.timeline
            is EngineState.Ready -> state.timeline
            is EngineState.Playing -> state.timeline
            is EngineState.Paused -> state.timeline
            is EngineState.Buffering -> state.timeline
            is EngineState.Ended -> state.timeline
            is EngineState.Failed -> state.timeline
        }
        mutableSnapshot.value = current.copy(phase = phase, timeline = timeline)
        emitState()
    }

    private fun clampPosition(position: Long): Long {
        val timeline = snapshot.value.timeline
        val duration = timeline.durationMillis ?: Long.MAX_VALUE
        return seeyuer.yingli.player.domain.playback.AbLoopLimiter().clamp(snapshot.value.abLoop, position, duration)
    }

    private fun feedback(code: String) {
        mutableEvents.tryEmit(PlaybackSessionEvent.OneShotFeedback(code))
    }

    private fun emitState() {
        mutableEvents.tryEmit(PlaybackSessionEvent.StateChanged(snapshot.value))
    }

    override fun close() {
        commandJob?.cancel()
        engine.release()
        scope.cancel()
    }
}
