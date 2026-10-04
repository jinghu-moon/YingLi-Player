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
import seeyuer.yingli.player.domain.playback.AbLoopAvailability
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbLoopEvent
import seeyuer.yingli.player.domain.playback.AbLoopReducer
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopSessionControl
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.MutableAbLoopSessionStore
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.EngineAbLoop
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.FrameCaptureRequest
import seeyuer.yingli.player.domain.playback.HistoryEligibilityPolicy
import seeyuer.yingli.player.domain.playback.NavigationDecision
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackCommandHandle
import seeyuer.yingli.player.domain.playback.PlaybackCommandId
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackEngineEvent
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
import seeyuer.yingli.player.domain.playback.abLoopAvailability
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
    /**
     * 帧号校准组件（可空：没有接入时帧号只显示估算值）。
     * 校准结果随媒体切换复位，见 [PlaybackSessionSnapshot.frameCalibration]。
     */
    private val frameCalibrationControl: FrameCalibrationControl? = null,
    /**
     * 已解析来源的 URI 表。只用于"按当前媒体的真实 URI 做帧数校准"：
     * [PlaybackSourceHandle] 本身只带 accessHandleId，URI 由注册表保管（与引擎同一份）。
     */
    private val sourceUriLookup: (String) -> String? = { null },
    private val vaultResolver: (suspend (VaultItemId, String) -> Result<seeyuer.yingli.player.domain.playback.PlaybackSourceHandle>)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.main),
) : PlaybackSessionClient, AbLoopSessionControl, AutoCloseable {
    private val commandSequence = AtomicLong(0)
    private val commandMutex = Mutex()
    private val abReducer = AbLoopReducer()

    /**
     * AB 循环的会话侧权威状态。
     *
     * 它是**唯一**一份：审计路径是 runtime 写 → `YingLiPlaybackService` 推 session extras →
     * `Media3PlaybackController` 解码 → bridge 投影 → UI。任何一层都不再做第二份判定。
     */
    private val abLoopStore = MutableAbLoopSessionStore()

    /**
     * 循环 generation：**AB 配置的版本号**。
     *
     * 什么时候递增（写死，见 `docs/20` §3.1）：A/B 变更、清除、切媒体（MediaChanged）。
     * 只有"当前 generation 的自然边界事件"才允许递增循环次数，因此晚到的旧事件
     *（旧配置的定时回调、被作废前的引擎事件）会被 generation 直接过滤掉。
     *
     * 用户 seek / 拖动 / 逐帧 **不**递增 generation：它们只是让引擎作废边界消息
     *（`configureAbLoop` 的取消/重置语义 + 位置不连续处置），语义上并没有产生"新配置"，
     * 给它一个新 generation 反而会让"过期事件"的定义变得含混。
     */
    private val abLoopGeneration = AtomicLong(0)

    /** 当前 AB 会话状态（区间 + 计数）的唯一权威来源。 */
    val abLoop: StateFlow<AbLoopSession> = abLoopStore.abLoop
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
    /** Seek 是高频输入：只执行最新目标，避免拖动期间排队 flush 过时位置。 */
    private var seekJob: Job? = null
    private var openGeneration = 0L
    private var queue: PlaybackQueueSnapshot? = null
    private var playWhenReady = false
    private var activeRequest: PlaybackOpenRequest? = null

    override val snapshot: StateFlow<PlaybackSessionSnapshot> = mutableSnapshot.asStateFlow()
    override val events: SharedFlow<PlaybackSessionEvent> = mutableEvents.asSharedFlow()

    /**
     * 会话侧的实时位置 = 引擎的实时位置（见 [PlaybackSessionClient.currentPositionMillis]）。
     * 会话只转发，不做插值也不缓存：位置只有播放器一个权威来源。
     */
    override fun currentPositionMillis(): Long = engine.currentPositionMillis()

    init {
        scope.launch {
            engine.state.collect { state ->
                commandMutex.withLock { applyEngineState(state) }
            }
        }
        scope.launch {
            engine.events.collect(::onEngineEvent)
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
        frameCalibrationControl?.let { control ->
            scope.launch {
                control.result.collect { result ->
                    commandMutex.withLock {
                        mutableSnapshot.value = mutableSnapshot.value.copy(frameCalibration = result)
                        emitState()
                    }
                }
            }
        }
    }

    override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle {
        val id = PlaybackCommandId(commandSequence.incrementAndGet())
        if (command is PlaybackSessionCommand.Seek) {
            seekJob?.cancel()
            seekJob = scope.launch {
                commandMutex.withLock { execute(id, command) }
            }
        } else {
            commandJob = scope.launch {
                if (command is PlaybackSessionCommand.Open) open(id, command.request)
                else commandMutex.withLock { execute(id, command) }
            }
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

    /**
     * 触发当前媒体的真实帧数校准（截图模式进入时调用）。
     *
     * 只在这里解析一次 URI：来源解析链本来就在会话这一层。媒体切换时 [open] 会取消在跑的校准，
     * 因此这里不需要再判断"是不是还在同一个媒体"——解析出来的 URI 就是当前媒体自己的。
     */
    override fun calibrateFrames() {
        val control = frameCalibrationControl
        val request = activeRequest
        if (control == null || request == null) return
        scope.launch {
            val resolved = runCatching { resolver.resolve(request) }.getOrNull()?.getOrNull() ?: return@launch
            val uri = sourceUriLookup(resolved.accessHandleId.value) ?: return@launch
            control.calibrate(uri, request.mediaId.value)
        }
    }

    override fun stopFrameCalibration() {
        frameCalibrationControl?.close()
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
            // 循环期间允许拖到区间外（D8-A，阶段 0 裁决保留）：AB 不钳制任何用户 seek。
            is PlaybackSessionCommand.Seek -> engine.seekTo(command.positionMillis.coerceAtLeast(0))
            // 相对跳转的**基准**必须是此刻的位置：用快照位置做基准，连续两次相对跳转会都从
            // 同一个旧位置起算（表现为"跳两次只动一次"），跳转距离还会随陈旧程度漂移。
            is PlaybackSessionCommand.SeekBy -> engine.seekTo(
                (engine.currentPositionMillis() + command.offsetMillis).coerceAtLeast(0),
            )
            PlaybackSessionCommand.Stop -> {
                openGeneration++
                playWhenReady = false
                // 会话结束：在跑的帧数校准既没有归属也没有意义，直接取消并清空结果。
                frameCalibrationControl?.close()
                engine.stop()
                // 会话结束：AB 配置随之作废（引擎侧已在 stop 里停掉检测，这里同步清掉状态与计数）。
                resetAbLoopForMediaChange()
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    mediaId = null,
                    title = null,
                    phase = PlaybackPhase.Idle,
                    timeline = PlaybackTimeline(),
                    frameCalibration = null,
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
            is PlaybackSessionCommand.SetAbPoint -> when (val outcome = applyAbPoint(command.point)) {
                AbLoopCommandOutcome.Applied -> Unit
                is AbLoopCommandOutcome.Rejected -> feedback(outcome.rejection.name)
            }
            PlaybackSessionCommand.ClearAb -> clearAbLoopState()
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
                // 新媒体的校准结果不能沿用上一个文件：取消在跑的任务并清空。
                frameCalibrationControl?.close()
                mutableSnapshot.value = snapshot.value.copy(
                    sessionId = request.sessionId,
                    mediaId = request.mediaId,
                    title = source.displayName,
                    phase = PlaybackPhase.Preparing(BackendId.MEDIA3),
                    timeline = PlaybackTimeline(),
                    frameCalibration = null,
                    mediaInfo = PlaybackMediaInfo(
                        title = source.displayName,
                        durationMillis = source.durationMillis,
                        width = source.width,
                        height = source.height,
                        fileSizeBytes = source.fileSizeBytes,
                    ),
                )
                // 保险库媒体同样是"换文件"：AB 清空（handle 的时长可能是未知值，准备完成后
                // 由引擎 timeline 提供真值，那时再按真实能力判定可用性）。
                resetAbLoopForMediaChange()
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
            // 切媒体就把在跑的校准取消掉：旧文件的帧数对新文件是错的。
            frameCalibrationControl?.close()
            mutableSnapshot.value = snapshot.value.copy(
                sessionId = request.sessionId,
                mediaId = request.mediaId,
                title = null,
                phase = PlaybackPhase.Resolving(id),
                timeline = PlaybackTimeline(request.startPositionMillis),
                frameCalibration = null,
            )
            // 切媒体：AB 区间描述的是"这一段媒体"，换文件必须清空（计数一并归零 + 新 generation）。
            resetAbLoopForMediaChange()
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
            // "上一项"要先判"当前是否播过 5 秒"，因此基准必须是**此刻**的位置：
            // 快照位置在稳定播放期间是陈旧值（可能远小于真实位置），会把本该"回到本集开头"
            // 的判定翻成"切到上一项"。
            seeyuer.yingli.player.domain.playback.QueueNavigator().previous(currentQueue, engine.currentPositionMillis())
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
        // 截图位置就是**此刻**的播放位置：它决定截到哪一帧，也是文件名时间戳的来源。
        // 用快照位置会让截图落在"上一次状态跳变时的位置"上（稳定播放期间可能差几十秒）。
        control.captureFrame(FrameCaptureRequest(mediaId, engine.currentPositionMillis())).fold(
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
        // 可用性判定必须发生在**播放器准备完成之后**，按真实 timeline 的 duration 与 seekability：
        // 时长从"未知"变成已知（保险库媒体就是这样）时它才可能从禁用变成可用；
        // 反过来媒体变得不可 seek 时，已有的 AB 区间必须撤掉，否则会留下一个永远循环不了的区间。
        dropAbLoopIfUnavailable()
    }

    // ---- AB 循环：会话侧唯一权威 ----

    /**
     * 设点。规则全在域层纯函数里（[AbLoopReducer] / [AbLoopLimiter]）：
     * A/B 互换、相等边界拒绝、帧索引吸附。
     *
     * 帧率取**实测优先**（`mediaInfo.frameRate` 是引擎从轨道读到的声明帧率）；拿不到就不吸附。
     */
    override suspend fun setPoint(point: AbPoint): AbLoopCommandOutcome =
        applyAbPoint(point)

    override suspend fun clear(): Unit = clearAbLoopState()

    private suspend fun applyAbPoint(point: AbPoint): AbLoopCommandOutcome {
        val availability = currentAbLoopAvailability()
        if (availability != AbLoopAvailability.ENABLED) {
            return AbLoopCommandOutcome.Rejected(PlaybackCommandRejection.AB_UNAVAILABLE)
        }
        val frameRate = snapshot.value.mediaInfo?.frameRate
        val update = abReducer.reduce(
            snapshot.value.abLoop,
            AbLoopEvent.SetPoint(
                point = point,
                // 位置必须是**此刻**的播放器位置。快照 timeline 只在引擎状态跳变时刷新，
                // 稳定播放期间会停在旧值上（真机实测：静置播放 60s 后仍是 0，于是"设 A"
                // 把 A 设在了 0ms）。这条判定就是缺陷 1 的原始现场。
                positionMillis = engine.currentPositionMillis(),
                frameRate = frameRate,
                // 片长取**引擎 timeline 的时长**（会话快照里的 `timeline.durationMillis` 就是它的投影，
                // 位置那一侧才需要绕开快照、直读引擎）。B 侧的"夹到片长"必须按真时长算，
                // 否则片尾附近设 B 会把 B 放到片尾之外，引擎随即在片尾反复回跳。
                durationMillis = snapshot.value.timeline.durationMillis,
            ),
        )
        val rejection = update.rejection
        if (rejection != null) return AbLoopCommandOutcome.Rejected(rejection)
        val generation = abLoopGeneration.incrementAndGet()
        publishAbLoop(
            // 设点是新一轮配置：计数从这一轮重新开始（旧计数属于旧区间，继续累加没有意义）。
            AbLoopSession(state = update.state, loopCount = 0),
            generation,
        )
        // 区间刚刚被设全（A、B 都在）：激活 = 立即精确跳回 A 并武装边界检测。
        // 这一步归引擎执行（`activateAbLoop`），既不在这里自己 `seekTo`，也不靠引擎"读位置猜"。
        val activation = engineAbLoop(update.state, generation)
        if (activation != null) {
            withContext(dispatchers.main) { engine.activateAbLoop(activation) }
        }
        return AbLoopCommandOutcome.Applied
    }

    private suspend fun clearAbLoopState() {
        publishAbLoop(AbLoopSession.EMPTY, abLoopGeneration.incrementAndGet())
    }

    /** 切媒体/停止会话：清空区间、归零计数、并生成新 generation（旧配置的边界事件一律作废）。 */
    private fun resetAbLoopForMediaChange() {
        publishAbLoop(AbLoopSession.EMPTY, abLoopGeneration.incrementAndGet())
    }

    /**
     * 媒体能力不再支持 AB 时撤掉区间（时长为未知值 / 不可 seek）。
     * 计数一并归零：它描述的是"这个区间循环了几次"，区间没了计数就没有意义。
     */
    private fun dropAbLoopIfUnavailable() {
        val current = abLoopStore.abLoop.value
        if (!current.active) return
        if (currentAbLoopAvailability() == AbLoopAvailability.ENABLED) return
        publishAbLoop(AbLoopSession.EMPTY, abLoopGeneration.incrementAndGet())
    }

    private fun currentAbLoopAvailability(): AbLoopAvailability =
        abLoopAvailability(snapshot.value.timeline.durationMillis, snapshot.value.timeline.isSeekable)

    private fun publishAbLoop(abLoopSession: AbLoopSession, generation: Long) {
        abLoopStore.publish(abLoopSession)
        // 会话快照同步带上区间与计数：快照是"客户端看到的完整会话视图"，
        // 而且它是设点校验读状态的地方（读 store 与读快照必须是同一份事实，否则第二次设点
        // 会看不到第一次设的 A，直接把合法区间判成 INVALID_AB_RANGE）。
        mutableSnapshot.value = mutableSnapshot.value.copy(
            abLoop = abLoopSession.state,
            loopCount = abLoopSession.loopCount,
        )
        engine.configureAbLoop(engineAbLoop(abLoopSession.state, generation))
    }

    /**
     * 把会话状态翻译成引擎配置。两端只有这一处转换：区间不完整（还没设全）时是 `null`（关闭循环），
     * 完整时带上 generation。**业务规则不在这里**（校验、互换、吸附都在域层与 [applyAbPoint] 里），
     * 这里只做"会话状态 → 引擎配置"的形状转换。
     */
    private fun engineAbLoop(state: AbLoopState, generation: Long): EngineAbLoop? {
        val start = state.pointA ?: return null
        val end = state.pointB ?: return null
        return EngineAbLoop(generation, start, end)
    }

    /**
     * 引擎上报的事件。
     *
     * **计数只认当前 generation 的自然边界事件**：过期事件（旧配置的定时回调、被作废前的
     * 引擎事件）在这里被 generation 过滤掉；用户 seek 越界与重缓冲位置回退根本不会产生
     * 该事件（引擎侧判定为"非自然"，见 `AbBoundaryWatcher`）。把这三条写在同一个地方，
     * 是因为它们合起来才是"计数不是按位置猜出来的"这一结论的完整依据。
     */
    private fun onEngineEvent(event: PlaybackEngineEvent) {
        when (event) {
            is PlaybackEngineEvent.AbBoundaryReached -> {
                // 引擎已经自己完成回跳（EXACT + 目标为当前配置的 A），runtime 只负责计数与投影。
                val generation = abLoopGeneration.get()
                if (event.generation != generation) return
                val current = abLoopStore.abLoop.value
                if (!current.active) return
                val next = current.copy(loopCount = current.loopCount + 1)
                abLoopStore.publish(next)
                mutableSnapshot.value = mutableSnapshot.value.copy(loopCount = next.loopCount)
            }
        }
    }

    private fun feedback(code: String) {
        mutableEvents.tryEmit(PlaybackSessionEvent.OneShotFeedback(code))
    }

    private fun emitState() {
        mutableEvents.tryEmit(PlaybackSessionEvent.StateChanged(snapshot.value))
    }

    override fun close() {
        commandJob?.cancel()
        frameCalibrationControl?.close()
        engine.release()
        scope.cancel()
    }
}
