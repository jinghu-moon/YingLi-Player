package seeyuer.yingli.player.app.playback

import seeyuer.yingli.player.domain.playback.*

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId

/**
 * Application-owned connection to the service session. The player feature only sees the
 * session contract; MediaController and source resolution remain outside the feature layer.
 */
class PlaybackSessionClientBridge(
    private val controller: PlaybackController,
    private val sourceRepository: PlaybackSourceRepository,
    private val dispatchers: AppDispatchers,
    private val queueRepository: PlaybackQueueRepository? = null,
    private val sessionId: PlaybackSessionId = PlaybackSessionId("activity-session"),
    shufflePicker: ShufflePicker = ShufflePicker { candidates, _ -> candidates.random() },
) : PlaybackSessionClient, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val commandSequence = AtomicLong(0)
    private val mutableSnapshot = MutableStateFlow(PlaybackSessionSnapshot(sessionId, null, null, PlaybackPhase.Idle))
    private val mutableEvents = kotlinx.coroutines.flow.MutableSharedFlow<PlaybackSessionEvent>(extraBufferCapacity = 32)
    private var queue: PlaybackQueue? = null
    private var abLoop = AbLoopState()
    private var currentTitle: String? = null
    private val queueNavigator = QueueNavigator(shufflePicker)

    override val snapshot: StateFlow<PlaybackSessionSnapshot> = mutableSnapshot.asStateFlow()
    override val events = mutableEvents

    init {
        scope.launch { controller.state.collect { publish(it) } }
        scope.launch { controller.connectionState.collect { mutableSnapshot.value = mutableSnapshot.value.copy(connectionState = it) } }
        val advanced = controller as? AdvancedPlaybackController
        if (advanced != null) {
            scope.launch { advanced.audioTracks.collect { publishTracks(audio = it) } }
            scope.launch { advanced.subtitleTracks.collect { publishTracks(subtitles = it) } }
            scope.launch { advanced.speed.collect { publishSpeed(it) } }
            scope.launch { advanced.scaleMode.collect { publishScale(it) } }
        }
        (controller as? PlaybackMediaInfoProvider)?.let { provider ->
            scope.launch {
                provider.mediaInfo.collect { info ->
                    if (info != null) mutableSnapshot.value = mutableSnapshot.value.copy(mediaInfo = info)
                }
            }
        }
        queueRepository?.let { repository ->
            scope.launch { repository.queue.collect { queue = it; publishQueue() } }
        }
    }

    override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle {
        val id = PlaybackCommandId(commandSequence.incrementAndGet())
        scope.launch {
            when (command) {
                is PlaybackSessionCommand.Open -> open(command.request)
                is PlaybackSessionCommand.OpenVault -> openVault(command.itemId, command.displayTitle)
                PlaybackSessionCommand.Play -> controller.play()
                PlaybackSessionCommand.Pause -> controller.pause()
                is PlaybackSessionCommand.Seek -> controller.seekTo(command.positionMillis)
                is PlaybackSessionCommand.SeekBy -> (controller as? AdvancedPlaybackController)?.seekBy(command.offsetMillis)
                    ?: controller.seekTo(snapshot.value.timeline.positionMillis + command.offsetMillis)
                PlaybackSessionCommand.Stop -> controller.stop()
                PlaybackSessionCommand.Retry -> controller.retry()
                PlaybackSessionCommand.Next -> next()
                PlaybackSessionCommand.Previous -> previous()
                is PlaybackSessionCommand.SetOrder -> {
                    queue = queue?.copy(order = command.order, shuffleHistory = emptyList())
                    queueRepository?.setQueue(queue)
                    publishQueue()
                }
                is PlaybackSessionCommand.SetSpeed -> (controller as? AdvancedPlaybackController)?.setSpeed(command.speed)
                is PlaybackSessionCommand.SelectTrack -> selectTrack(command.selection)
                is PlaybackSessionCommand.SetScale -> (controller as? AdvancedPlaybackController)?.setScaleMode(command.mode)
                is PlaybackSessionCommand.SetAbPoint -> setAbPoint(command.point)
                PlaybackSessionCommand.ClearAb -> { abLoop = AbLoopState(); publishAb() }
                is PlaybackSessionCommand.BindSurface,
                is PlaybackSessionCommand.UnbindSurface,
                PlaybackSessionCommand.CaptureFrame -> feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
            }
        }
        return PlaybackCommandHandle(id)
    }

    private suspend fun open(request: PlaybackOpenRequest) {
        val resolved = runCatching {
            sourceRepository.resolve(request.mediaId, request.sourceContext, request.incognito)
        }.getOrNull()
        if (resolved == null) {
            feedback(PlaybackCommandRejection.SOURCE_UNAVAILABLE.name)
            return
        }
        val startPosition = request.startPositionMillis.takeIf { it > 0 } ?: resolved.request.startPositionMillis
        controller.prepare(resolved.copy(request = resolved.request.copy(startPositionMillis = startPosition)))
        currentTitle = resolved.title
        mutableSnapshot.value = mutableSnapshot.value.copy(
            title = resolved.title,
            mediaInfo = PlaybackMediaInfo(
                title = resolved.title,
                durationMillis = resolved.durationMillis,
                width = resolved.width,
                height = resolved.height,
                fileSizeBytes = resolved.fileSizeBytes,
            ),
        )
    }

    private fun openVault(itemId: seeyuer.yingli.player.domain.security.VaultItemId, displayTitle: String) {
        val secureController = controller as? seeyuer.yingli.player.domain.security.SecurePlaybackController
            ?: return feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
        currentTitle = displayTitle
        if (!secureController.prepare(itemId)) {
            feedback(PlaybackCommandRejection.SOURCE_UNAVAILABLE.name)
            return
        }
        val mediaId = MediaItemId("vault-${itemId.value}")
        mutableSnapshot.value = mutableSnapshot.value.copy(
            mediaId = mediaId,
            title = displayTitle,
            phase = PlaybackPhase.Preparing(BackendId.MEDIA3),
            mediaInfo = PlaybackMediaInfo(title = displayTitle),
        )
        publishQueue()
    }

    private suspend fun next() {
        val current = queue ?: return feedback(PlaybackCommandRejection.NO_CANDIDATE.name)
        val decision = queueNavigator.next(current.toSnapshot(), ended = false)
        val move = decision as? NavigationDecision.MoveTo ?: return feedback(PlaybackCommandRejection.NO_CANDIDATE.name)
        val id = move.mediaId
        queue = current.copy(currentIndex = move.index, shuffleHistory = move.shuffleHistory)
        queueRepository?.setQueue(queue)
        dispatch(PlaybackSessionCommand.Open(PlaybackOpenRequest(sessionId, id, PlaybackSourceContext.HOME)))
    }

    private suspend fun previous() {
        val current = queue ?: return feedback(PlaybackCommandRejection.NO_CANDIDATE.name)
        if (snapshot.value.timeline.positionMillis > 5_000) {
            controller.seekTo(0)
            return
        }
        val decision = queueNavigator.previous(current.toSnapshot(), snapshot.value.timeline.positionMillis)
        val move = decision as? NavigationDecision.MoveTo ?: return feedback(PlaybackCommandRejection.NO_CANDIDATE.name)
        val id = move.mediaId
        queue = current.copy(currentIndex = move.index, shuffleHistory = move.shuffleHistory)
        queueRepository?.setQueue(queue)
        dispatch(PlaybackSessionCommand.Open(PlaybackOpenRequest(sessionId, id, PlaybackSourceContext.HOME)))
    }

    private fun selectTrack(selection: TrackSelection) {
        val advanced = controller as? AdvancedPlaybackController ?: return feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
        when (selection.type) {
            TrackType.AUDIO -> selection.fingerprint?.language?.let { language ->
                snapshot.value.audioTracks.firstOrNull { it.language == language }?.let { advanced.selectAudioTrack(it.id) }
            }
            TrackType.SUBTITLE -> {
                val id = selection.fingerprint?.let { fingerprint -> snapshot.value.subtitleTracks.firstOrNull { it.language == fingerprint.language }?.id }
                advanced.selectSubtitleTrack(id)
            }
        }
    }

    private fun setAbPoint(point: AbPoint) {
        val timeline = snapshot.value.timeline
        val update = AbLoopReducer().reduce(
            abLoop,
            AbLoopEvent.SetPoint(point, timeline.positionMillis, timeline.durationMillis ?: 0, null),
        )
        if (update.rejection == null) { abLoop = update.state; publishAb() }
        else feedback(update.rejection.name)
    }

    private fun publish(state: PlaybackState) {
        val request = state.request
        val phase = when (state) {
            PlaybackState.Idle -> PlaybackPhase.Idle
            is PlaybackState.Preparing -> PlaybackPhase.Preparing(BackendId.MEDIA3)
            is PlaybackState.Ready -> PlaybackPhase.Ready(state.timeline.isSeekable)
            is PlaybackState.Playing -> PlaybackPhase.Playing(0)
            is PlaybackState.Paused -> PlaybackPhase.Paused(PauseReason.USER)
            is PlaybackState.Ended -> PlaybackPhase.Ended(if (state.hasNext) queue?.mediaIds?.getOrNull((queue?.currentIndex ?: -1) + 1) else null)
            is PlaybackState.Failed -> PlaybackPhase.Failed(state.error)
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(
            mediaId = request?.mediaId,
            title = currentTitle ?: request?.mediaId?.value,
            phase = phase,
            timeline = state.timeline,
            abLoop = abLoop,
        )
        mutableEvents.tryEmit(PlaybackSessionEvent.StateChanged(mutableSnapshot.value))
    }

    private fun publishTracks(audio: List<TrackChoice>? = null, subtitles: List<TrackChoice>? = null) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            audioTracks = audio ?: mutableSnapshot.value.audioTracks,
            subtitleTracks = subtitles ?: mutableSnapshot.value.subtitleTracks,
        )
    }

    private fun publishSpeed(value: PlaybackSpeed) { mutableSnapshot.value = mutableSnapshot.value.copy(speed = value) }
    private fun publishScale(value: VideoScaleMode) { mutableSnapshot.value = mutableSnapshot.value.copy(scaleMode = value) }
    private fun publishQueue() {
        val current = queue
        mutableSnapshot.value = mutableSnapshot.value.copy(
            queue = current?.toSnapshot(),
        )
    }
    private fun publishAb() { mutableSnapshot.value = mutableSnapshot.value.copy(abLoop = abLoop) }
    private fun feedback(code: String) { mutableEvents.tryEmit(PlaybackSessionEvent.OneShotFeedback(code)) }

    override fun close() { scope.cancel() }

    private fun PlaybackQueue.toSnapshot() = PlaybackQueueSnapshot(
        queueId = "player",
        mediaIds = mediaIds,
        currentIndex = currentIndex,
        order = order,
        shuffleHistory = shuffleHistory,
    )
}
