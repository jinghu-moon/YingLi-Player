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
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.NoOpFrameCalibrationControl

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
    /**
     * 帧号校准组件。默认的 NoOp 实现让"没接校准"的宿主行为与改造前完全一致（只显示估算值）。
     */
    private val frameCalibrationControl: FrameCalibrationControl = NoOpFrameCalibrationControl,
) : PlaybackSessionClient, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val commandSequence = AtomicLong(0)
    /** Seek 是高频输入：只执行最新目标，避免拖动期间排队过时 seek。 */
    private var seekJob: kotlinx.coroutines.Job? = null
    private val mutableSnapshot = MutableStateFlow(PlaybackSessionSnapshot(sessionId, null, null, PlaybackPhase.Idle))
    private val mutableEvents = kotlinx.coroutines.flow.MutableSharedFlow<PlaybackSessionEvent>(extraBufferCapacity = 32)
    private var queue: PlaybackQueue? = null
    private var currentTitle: String? = null
    /** 最近一次打开用的来源场景：帧数校准要按同一场景重新解析 URI。 */
    private var currentSourceContext = PlaybackSourceContext.HOME
    private val queueNavigator = QueueNavigator(shufflePicker)

    /**
     * 会话侧的 AB 通道（可空：单测/预览宿主没有会话时保持"不支持"）。
     *
     * **这里不持有任何 AB 状态**：区间与计数都由会话 runtime 认定，本层只把命令送出去、
     * 把会话回流的投影并进快照。旧实现里的 `abLoop` 字段与本地 reducer 已删除 ——
     * 它会在每次 publish 时把自己的那份写回快照，覆盖会话取值（三份状态互相覆盖的根源之一）。
     */
    private val abLoopControl: AbLoopPlaybackControl? = controller as? AbLoopPlaybackControl

    /**
     * 会话 AB 区间在本层的**只读副本**，唯一来源是 [AbLoopPlaybackControl.abLoop]。
     * 它只是"投影用的缓存"：本层没有任何地方会修改/推导它，也没有本地 reducer。
     * 之所以要缓存而不是每次读 flow：`publish()` 与 AB 状态变化是两个独立事件源，
     * 快照需要同时反映两者的最新值。
     */
    private var projectedAbLoop = AbLoopState()

    override val snapshot: StateFlow<PlaybackSessionSnapshot> = mutableSnapshot.asStateFlow()
    override val events = mutableEvents

    init {
        scope.launch { controller.state.collect { publish(it) } }
        scope.launch { controller.connectionState.collect { mutableSnapshot.value = mutableSnapshot.value.copy(connectionState = it) } }
        abLoopControl?.let { control ->
            scope.launch { control.abLoop.collect { publishAbLoop(it) } }
        }
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
        scope.launch {
            frameCalibrationControl.result.collect { result ->
                mutableSnapshot.value = mutableSnapshot.value.copy(frameCalibration = result)
            }
        }
    }

    /**
     * 触发当前媒体的真实帧数校准。这里解析一次 URI：来源解析链本来就在这一层，
     * 校准组件只接受 URI，不需要知道媒体库的任何概念。
     */
    override fun calibrateFrames() {
        val mediaId = snapshot.value.mediaId ?: return
        val sourceContext = currentSourceContext
        scope.launch {
            val resolved = runCatching { sourceRepository.resolve(mediaId, sourceContext, incognito = false) }
                .getOrNull() ?: return@launch
            frameCalibrationControl.calibrate(resolved.uri, mediaId.value)
        }
    }

    override fun stopFrameCalibration() {
        frameCalibrationControl.close()
    }

    override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle {
        val id = PlaybackCommandId(commandSequence.incrementAndGet())
        if (command is PlaybackSessionCommand.Seek) {
            seekJob?.cancel()
            seekJob = scope.launch { controller.seekTo(command.positionMillis) }
        } else {
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
                is PlaybackSessionCommand.SetAbPoint -> abLoopControl?.requestSetAbPoint(command.point)
                    ?: feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
                PlaybackSessionCommand.ClearAb -> abLoopControl?.requestClearAbLoop()
                    ?: feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
                is PlaybackSessionCommand.BindSurface,
                is PlaybackSessionCommand.UnbindSurface,
                PlaybackSessionCommand.CaptureFrame -> feedback(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name)
                }
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
        currentSourceContext = request.sourceContext
        // 切媒体就把在跑的校准取消掉并清空：旧文件的帧数对新文件是错的。
        frameCalibrationControl.close()
        val startPosition = request.startPositionMillis.takeIf { it > 0 } ?: resolved.request.startPositionMillis
        controller.prepare(resolved.copy(request = resolved.request.copy(startPositionMillis = startPosition)))
        currentTitle = resolved.title
        mutableSnapshot.value = mutableSnapshot.value.copy(
            title = resolved.title,
            frameCalibration = null,
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
            // 保险库媒体不校准（来源是加密管道，不是可直接扫描的本地文件）。
            frameCalibration = null,
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
        // "先回本集开头 / 直接切上一项"的判定已上移到 PlayerViewModel.previous()（受设置控制），
        // 这里只负责按队列导航切项；否则会把用户选择的"永远直接切上一项"重新改回 5 秒惯例。
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

    private fun publish(state: PlaybackState) {
        val request = state.request
        val phase = state.toSessionPhase(
            next = if (state is PlaybackState.Ended && state.hasNext) {
                queue?.mediaIds?.getOrNull((queue?.currentIndex ?: -1) + 1)
            } else {
                null
            },
        )
        mutableSnapshot.value = mutableSnapshot.value.copy(
            mediaId = request?.mediaId,
            title = currentTitle ?: request?.mediaId?.value,
            phase = phase,
            timeline = state.timeline,
            // 播放状态与 AB 状态是两个独立事件源：这里保留 AB 投影的最新值，避免被覆盖回旧值。
            abLoop = projectedAbLoop,
        )
        mutableEvents.tryEmit(PlaybackSessionEvent.StateChanged(mutableSnapshot.value))
    }

    /**
     * 会话 AB 状态的**投影**：只并入快照，不参与任何判定，也不回写会话。
     * 区间与计数都来自会话，本层没有可写的地方 —— 这是"只有一份权威状态"的落点。
     */
    private fun publishAbLoop(session: AbLoopSession) {
        projectedAbLoop = session.state
        mutableSnapshot.value = mutableSnapshot.value.copy(
            abLoop = session.state,
            loopCount = session.loopCount,
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

/**
 * 保留播放状态的语义边界：首次准备和 seek 后的重缓冲不能都映射成 Preparing。
 *
 * [PlaybackState.Preparing.isRebuffering] 是 UI 是否显示全屏加载圈的关键事实；
 * 桥接层若把它丢掉，快进/快退/进度拖动就会被误认为首次加载。
 */
internal fun PlaybackState.toSessionPhase(next: MediaItemId? = null): PlaybackPhase = when (this) {
    PlaybackState.Idle -> PlaybackPhase.Idle
    is PlaybackState.Preparing -> if (isRebuffering) {
        PlaybackPhase.Buffering(BufferingReason.REBUFFER)
    } else {
        PlaybackPhase.Preparing(BackendId.MEDIA3)
    }
    is PlaybackState.Ready -> PlaybackPhase.Ready(timeline.isSeekable)
    is PlaybackState.Playing -> PlaybackPhase.Playing(0)
    is PlaybackState.Paused -> PlaybackPhase.Paused(PauseReason.USER)
    is PlaybackState.Ended -> PlaybackPhase.Ended(next)
    is PlaybackState.Failed -> PlaybackPhase.Failed(error)
}
