package seeyuer.yingli.player.feature.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipExportQueue
import seeyuer.yingli.player.domain.clips.ClipFastExportProbe
import seeyuer.yingli.player.domain.clips.ClipPreset
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.playback.ScreenshotDeleteFailure
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.DeviceControlGateway
import seeyuer.yingli.player.domain.playback.FrameCalibration
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import seeyuer.yingli.player.domain.playback.FrameCounterState
import seeyuer.yingli.player.domain.playback.SeekPrecision
import seeyuer.yingli.player.domain.playback.SeekPrecisionControl
import seeyuer.yingli.player.domain.playback.defaultSeekPrecision
import seeyuer.yingli.player.domain.playback.displayPositionMillis
import seeyuer.yingli.player.domain.playback.effectiveFrameRate
import seeyuer.yingli.player.domain.playback.frameDurationMillisOf
import seeyuer.yingli.player.domain.playback.frameStepTargetMillis
import seeyuer.yingli.player.domain.playback.DEFAULT_FRAME_STEP_MILLIS
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutRepository
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlaybackQueueSource
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.PlayerOverlayEvent
import seeyuer.yingli.player.domain.playback.PlayerOverlayReducer
import seeyuer.yingli.player.domain.playback.PlayerOverlayState
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.PlayerPanelEvent
import seeyuer.yingli.player.domain.playback.PlayerPanelReducer
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotPreviewSession
import seeyuer.yingli.player.domain.playback.SCREENSHOT_PREVIEW_TICK_MILLIS
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotUiEvent
import seeyuer.yingli.player.domain.playback.ScreenshotUiReducer
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.collapse
import seeyuer.yingli.player.domain.playback.expand
import seeyuer.yingli.player.domain.playback.isScreenshotCaptureResultCurrent
import seeyuer.yingli.player.domain.playback.markDeleted
import seeyuer.yingli.player.domain.playback.screenshotExpiredNaturally
import seeyuer.yingli.player.domain.playback.tick
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoZoom
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.FullscreenPolicy
import seeyuer.yingli.player.domain.playback.PlaybackAction
import seeyuer.yingli.player.domain.playback.frameCalibrationNoticeRequired
import seeyuer.yingli.player.domain.playback.frameCounterStateOf
import seeyuer.yingli.player.domain.playback.RequestedOrientation
import seeyuer.yingli.player.domain.playback.WindowPlaybackGateway
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.security.VaultItemId

data class PlayerUiState(
    val playback: PlaybackState = PlaybackState.Idle,
    val connection: PlaybackConnectionState = PlaybackConnectionState.CONNECTING,
    val title: String = "",
    val displayedPositionMillis: Long = 0,
    val sourceUnavailable: Boolean = false,
    val audioTracks: List<TrackChoice> = emptyList(),
    val subtitleTracks: List<TrackChoice> = emptyList(),
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val overlay: PlayerOverlayState = PlayerOverlayState(),
    val panel: PlayerPanel = PlayerPanel.NONE,
    /**
     * 当前 AB 会话：区间 + 循环次数**一起**投影进 UI。
     *
     * 为什么不只投影区间、另加一个 `abLoopCount`：区间与计数本来就同属"这一段会话"（[AbLoopSession]），
     * 拆成两个字段就会有两个状态源、两条刷新路径，UI 上完全可能出现"区间变了计数还没变"。
     * 它们的唯一写入者是会话 runtime，这里只是原样读回来。
     */
    val abLoop: AbLoopSession = AbLoopSession.EMPTY,
    val abToolOpen: Boolean = false,
    /**
     * 「导出当前 AB 区间」的 sheet；null = 没打开。它是**会话临时状态**的同侧产物：
     * 清除 AB、切换媒体都会关掉它（见 `clearAb`），绝不落库（§9.2 C1）。
     */
    val abExportSheet: AbExportSheetState? = null,
    val queue: PlaybackQueue? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
    val screenshot: ScreenshotUiState = ScreenshotUiState.Idle,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENCE,
    val rotation: VideoRotation = VideoRotation.Default,
    /** 来自窗口网关的真实全屏状态，不做乐观更新。 */
    val isFullscreen: Boolean = false,
    /** 全屏时是否需要视图层把画面填满（竖屏播放竖版视频）。 */
    val fillScreen: Boolean = false,
    val mediaInfo: seeyuer.yingli.player.domain.playback.PlaybackMediaInfo? = null,
    val controlLayout: PlayerControlLayout = PlayerControlLayout(),
    /** 画面手势的即时反馈；null 表示当前没有手势反馈。 */
    val gestureHud: PlayerGestureHud? = null,
    /** 自由缩放（双指捏合），只做视图层变换，随媒体切换重置。 */
    val zoom: VideoZoom = VideoZoom.Default,
    /** 长按临时倍速进行中：UI 显示"快进中"，且绝不写入媒体偏好。 */
    val isTemporarySpeed: Boolean = false,
    /**
     * 当前帧号（截图模式的帧数胶囊）。null = 不显示胶囊：帧率与时长都不可用、也没有校准值时
     * 宁可不出这个胶囊，也不显示编造的帧号。
     */
    val frameCounter: FrameCounterState? = null,
    /** 帧号校准状态（截图模式）：Calibrating 时显示估算值，Calibrated 后切换为精确值。 */
    val frameCalibration: FrameCalibrationResult? = null,
    /**
     * 大文件校准进行中：胶囊上的帧号是估算值，需要显式标出来。
     *
     * 判定见 [frameCalibrationNoticeRequired]（阈值带实测依据）。小文件不标记，
     * 因为它们的校准是 100ms 级、标记只会闪一下；大文件实测要等 1.3s 起步，
     * 那几秒里总数还可能整体变化，必须让用户看得出来这是估算值。
     */
    val frameCounterPending: Boolean = false,
    /**
     * 已校准的实测帧率。**步进与帧号都必须用它**（优先于容器 `Format.frameRate`）：
     * 它是从真实样本时间轴派生的，和帧号同源，否则帧号与步长会互相漂移。
     */
    val measuredFrameRate: Float? = null,
)

/** 截图工具是否处于"逐帧检查"阶段：Armed/Capturing 都算，Idle/Preview/Failed 不算。 */
internal fun ScreenshotUiState.isFrameSteppingActive(): Boolean =
    this is ScreenshotUiState.Armed || this is ScreenshotUiState.Capturing

/** 视频宽高比；宽高缺失或非法时返回 null，调用方按“形状未知”处理。 */
internal fun PlayerUiState.videoAspect(): Float? {
    val width = mediaInfo?.width
    val height = mediaInfo?.height
    return if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null
}

sealed interface PlayerUiEvent {
    /**
     * 统一瞬时反馈：命令结果和状态变化都走这一条通道（规格 §14.4）。
     * 文案用字符串资源 id 表达，不在事件里传裸文案；需要拼一段**运行时才有的内容**时用
     * [stringArgument]（例如"已保存到 …"里的保存位置），需要拼另一条资源文案时用 [argumentRes]
     * （例如「播放顺序：随机播放」）。
     *
     * 为什么不像别的消息那样只靠资源 id：截图保存位置是网关生成的文件路径，
     * 没有任何 `@StringRes` 能表达它；而把整句文案在 ViewModel 里拼好又会绕开资源体系。
     */
    data class TransientMessage(
        @StringRes val messageRes: Int,
        @StringRes val argumentRes: Int? = null,
        /** 资源文案 `%1$s` 的实参；为 null 时按无参文案渲染。 */
        val stringArgument: String? = null,
    ) : PlayerUiEvent

    /**
     * AB 区间已经**入队成功**（设计稿 §14.6 步骤 12）。
     *
     * 用事件而不是 UiState 布尔量：它描述的是"刚发生了什么"，要触发一次性的宿主动作
     * （跳到处理中心）。做成状态就会出现"跳过一次之后还一直是 true"的第二条语义。
     */
    data object ExportQueued : PlayerUiEvent
}

/**
 * 「导出当前 AB 区间」的 sheet 状态（设计稿 §14.6 步骤 12）。
 *
 * 两端点都是**冻结值**：它们是用户在那一刻看到的区间，即使播放继续、AB 被清掉，
 * sheet 上写着的仍然是用户点开时的那一段（与提交时固化成 `ClipSegment` 的正是同两个数）。
 */
data class AbExportSheetState(
    val startMillis: Long,
    val endMillis: Long,
    /** 打开时默认选中的模式。源不支持无损复制时会被改成 [ClipExportMode.ACCURATE]。 */
    val defaultMode: ClipExportMode = ClipExportMode.FAST,
    /** 快速模式不可用（容器收不下源样本格式）；UI 必须说明理由，而不是静默换默认。 */
    val fastUnavailable: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val sessionClient: PlaybackSessionClient,
    private val dispatchers: AppDispatchers,
    private val playerPreferenceRepository: PlayerPreferenceRepository? = null,
    private val trackPreferenceRepository: TrackPreferenceRepository? = null,
    private val screenshotGateway: ScreenshotGateway? = null,
    private val pictureInPictureGateway: PictureInPictureGateway? = null,
    private val playbackQueueRepository: PlaybackQueueRepository? = null,
    private val controlLayoutRepository: PlayerControlLayoutRepository? = null,
    private val libraryRepository: LibraryPagingRepository? = null,
    private val windowPlaybackGateway: WindowPlaybackGateway? = null,
    private val deviceControlGateway: DeviceControlGateway? = null,
    /**
     * 截图模式的跳转精度开关。必须是**跨进程内共享**的那个实例（见 MediaContainer）：
     * 写它的是这里，读它并真正调用 `setSeekParameters` 的是 service 里的引擎。
     * 传 null（单测/预览）时行为与改造前一致：不改变跳转精度。
     */
    private val seekPrecisionControl: SeekPrecisionControl? = null,
    /**
     * AB 区间导出的四个协作者（设计稿 §14.6 步骤 11/13）。全部可空：单测与预览里没有它们时
     * 「导出这段」**不可用**（回一条明确的失败反馈），而不是造一个假项目出来。
     *
     * 为什么需要 [clock]：`ClipProject` 是 Room 持久实体，必须带 `createdAtEpochMillis`；
     * 而架构规则禁止在 `src/main` 里直接读系统时间（`System` 的墙上时钟与 `Instant` 的
     * 无参取当下都上黑名单，测试按文本扫），所以时间只能注入。
     * 为什么需要 [idGenerator]：项目 id 与段 id 必须由项目统一的生成器造，域层不提供随机路径。
     */
    private val clipExportQueue: ClipExportQueue? = null,
    private val clipFastExportProbe: ClipFastExportProbe? = null,
    private val idGenerator: IdGenerator? = null,
    private val clock: AppClock? = null,
    private val ownsSessionClient: Boolean = true,
) : ViewModel() {
    private val events = Channel<PlayerUiEvent>(Channel.BUFFERED)
    val event: Flow<PlayerUiEvent> = events.receiveAsFlow()
    private val title = MutableStateFlow("")
    private val sourceUnavailable = MutableStateFlow(false)
    private val overlay = MutableStateFlow(PlayerOverlayState())
    private val panel = MutableStateFlow(PlayerPanel.NONE)
    /** 工具浮层开关（AB 胶囊的"打开/关闭"）：关闭 ≠ 取消循环（D3），所以它和区间是两件事。 */
    private val abToolOpen = MutableStateFlow(false)

    /** AB 导出 sheet：null = 没打开（见 [AbExportSheetState]）。 */
    private val abExportSheet = MutableStateFlow<AbExportSheetState?>(null)
    private val screenshot = MutableStateFlow<ScreenshotUiState>(ScreenshotUiState.Idle)

    /**
     * 预览卡倒计时的**唯一状态源**（读条余量、展开定格、是否已删除）。UI 的
     * [ScreenshotUiState.Preview.remainingMillis] 只是它推给读条的镜像，不参与计时决策——
     * 展开期间读条定格而 UI 状态可能已经回到 Idle，余量只在这里还记得住
     * （原因见 [ScreenshotPreviewSession] 的注释）。
     */
    private val screenshotSession = MutableStateFlow<ScreenshotPreviewSession?>(null)

    /**
     * 截图捕获的 **generation**：捕获回调的"晚到"契约（docs/20 T3.6）的锚点。
     *
     * 任何让"在途的捕获"失去意义的动作都让它 +1：进入/结束截图会话、打开 AB 工具、
     * 换媒体、切面板、锁屏。捕获协程只带着出发时的那个值，回来时**必须**与当前值相等
     * 才允许发布结果。
     *
     * 为什么不是"就地把 state 比一遍"（旧实现的内联守卫）：状态是会被**复用**的 ——
     * 用户"武装 → 捕获 → 关闭 → 再武装"之后，状态又回到了 `Capturing`，那时上一次的
     * 旧结果落进来会被当成这一次的，预览卡上出现的是一张早就不对的帧。generation 单调递增，
     * 这种"状态绕回同一格"的时序骗不过它。
     */
    private var screenshotCaptureGeneration = 0L

    /**
     * AB 区间导出的 generation：与截图同构的「晚到结果」契约（§9.4 明确要求照抄截图那条）。
     *
     * 任何让"在途的导出"失去意义的动作都让它 +1：清除 AB、切换媒体（都经 [clearAb]）。
     * 导出协程带着出发时的值回来，值不再相等就不发布任何结果 —— 否则用户会收到一条
     * 关于**另一段区间**（甚至另一个文件）的反馈，而那段区间早就没了。
     */
    private var abExportGeneration = 0L

    /** 作废在途的捕获回调（见 [screenshotCaptureGeneration]）。 */
    private fun invalidateScreenshotCapture() {
        screenshotCaptureGeneration++
    }

    /** 画面旋转是视图层变换：不进播放管线，但按媒体持久化。 */
    private val rotation = MutableStateFlow(VideoRotation.Default)
    private val isFullscreen = MutableStateFlow(false)
    private val fillScreen = MutableStateFlow(false)

    /** 画面手势：HUD 反馈、自由缩放、长按临时倍速。 */
    private val gestureHud = MutableStateFlow<PlayerGestureHud?>(null)
    private val zoom = MutableStateFlow(VideoZoom.Default)
    private val isTemporarySpeed = MutableStateFlow(false)
    private var gestureHudHideJob: Job? = null

    /** 手势起点值：拖动期间用"起点 + 位移比例"计算目标值，避免逐帧累加误差。 */
    private var volumeAnchor = 0f
    private var brightnessAnchor = 0f
    private var zoomAnchor: VideoZoom = VideoZoom.Default
    private var temporarySpeedAnchor: PlaybackSpeed? = null
    private var seekAnchorMillis = 0L

    private var overlayHideJob: Job? = null
    private var screenshotTimerJob: Job? = null
    private var queueBuildJob: Job? = null
    private var activeQueueSource: PlaybackQueueSource? = null

    /**
     * 逐帧步进的"本步落点"锚点。
     *
     * 为什么需要它：seek 下发到位置被状态回报有延迟，连续点"下一帧"时若每次都从
     * [PlayerUiState.displayedPositionMillis] 重新计算，第二次就会拿旧位置算出同一目标，
     * 表现为"点两下只前进一帧"。锚点只在与回报位置相差不超过一帧时才被信任；
     * 用户手动拖动进度条或换媒体后会自然失配，从而回落到真实位置（规则见 [resolveFrameStepAnchor]）。
     */
    private var frameStepAnchorMillis: Long? = null

    override fun onCleared() {
        // 页面销毁：截图模式不再存在，既没有理由继续精确跳转，也没有理由让校准继续扫文件。
        // 预览卡倒计时也一并停掉：会话随页面一起没了，留着计时协程只会推一份没人看的状态。
        screenshotTimerJob?.cancel()
        screenshotTimerJob = null
        screenshotSession.value = null
        seekPrecisionControl?.setPrecision(SeekPrecision.CLOSEST_SYNC)
        sessionClient.stopFrameCalibration()
        if (ownsSessionClient) (sessionClient as? AutoCloseable)?.close()
        super.onCleared()
    }
    /**
     * 播放状态投影。**只投影快照**：状态的跳变就是状态本身，不需要任何按时推进。
     *
     * **循环回跳不在这里**：AB 的边界检测与回跳由引擎自己完成并上报事件（见 `AbBoundaryWatcher`）。
     * 旧实现每 250ms 在这里推算一次位置、越过 B 就下发 `Seek(A)` —— 那是"UI 按位置猜测"，
     * 暂停/缓冲/后台时 ticker 一停循环就失效，而且它无法区分自然抵达与用户 seek，计数只能靠猜。
     * 那条路径已整条删除，本文件不得再出现任何"按位置判定后下发播放命令"的代码。
     */
    private val projectedPlayback: Flow<PlaybackState> = sessionClient.snapshot.map(PlaybackSessionSnapshot::toPlaybackState)

    /**
     * 展示层位置（契约与理由见 [displayPositionMillis]）：播放中按固定间隔读**实时位置**。
     *
     * 与 [projectedPlayback] 分开的理由是两层的规矩不同：状态跳变才是状态，而位置在稳定播放期间
     * 也必须持续更新。这条流只产出位置，由 [baseState] 用 [withLiveDisplayPosition] 换进
     * `playback.timeline` 与 [PlayerUiState.displayedPositionMillis] —— 进度条、时间读数、帧号
     * 因此重新开始推进。
     *
     * **决策层不受影响**：凡"以当前位置为依据"的判定（设点、截图时间戳、上一项、相对跳转、
     * 帧步进锚点）继续直接调用 [PlaybackSessionClient.currentPositionMillis]，不读这里。
     */
    private val displayPosition: Flow<Long> = displayPositionMillis(sessionClient)

    /** 快照投影出的播放状态；位置在 [displayPosition] 里被展示层的位置替换。 */
    private fun PlayerUiState.withLiveDisplayPosition(positionMillis: Long): PlayerUiState =
        copy(
            displayedPositionMillis = positionMillis,
            playback = playback.withTimelinePosition(positionMillis),
        )

    private val baseState = combine(
        projectedPlayback,
        displayPosition,
        sessionClient.snapshot,
        title,
        sourceUnavailable,
    ) { playback, displayPosition, snapshot, currentTitle, unavailable ->
        PlayerUiState(
            playback = playback,
            connection = snapshot.connectionState,
            title = currentTitle.ifBlank { snapshot.title.orEmpty() },
            sourceUnavailable = unavailable,
            playbackOrder = snapshot.queue?.order ?: PlaybackOrder.SEQUENCE,
            mediaInfo = snapshot.mediaInfo,
        ).withLiveDisplayPosition(displayPosition)
    }
    private val advancedState = sessionClient.snapshot
        .let { snapshots -> snapshots.map { AdvancedState(it.audioTracks, it.subtitleTracks, it.speed, it.scaleMode) } }
    private val preferences = playerPreferenceRepository?.playerPreferences ?: flowOf(PlayerPreferences())
    private val queue = playbackQueueRepository?.queue ?: flowOf(null)
    val playlistPagingData = queue.flatMapLatest { currentQueue ->
        if (currentQueue == null || libraryRepository == null) {
            flowOf(PagingData.empty<PlaylistMediaItem>())
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = PLAYLIST_PAGE_SIZE,
                    initialLoadSize = PLAYLIST_PAGE_SIZE,
                    prefetchDistance = PLAYLIST_PREFETCH_DISTANCE,
                    maxSize = PLAYLIST_MAX_SIZE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = { PlaylistPagingSource(currentQueue.mediaIds, libraryRepository) },
            ).flow
        }
    }.cachedIn(viewModelScope)
    private val overlayAndPanel = combine(overlay, panel) { currentOverlay, currentPanel ->
        currentOverlay to currentPanel
    }
    /**
     * AB 区间与计数**只从会话快照读**：ViewModel 不再持有 `abLoop`/`abLimiter`，
     * 也不再自己判定区间合法性（旧实现三份状态之一就在这里）。
     *
     * 区间与计数在快照里是两个字段，但它们是同一段会话的两半（[AbLoopSession]）：
     * 这里合成一个对象再下发，避免 UI 读到"区间已更新、计数还没更新"的中间态。
     */
    private val abLoop = sessionClient.snapshot
        .map { AbLoopSession(state = it.abLoop, loopCount = it.loopCount) }
        .distinctUntilChanged()
    private val overlayPanelAbAndQueue = combine(overlayAndPanel, abLoop, abToolOpen, queue) { overlayPanel, currentAb, toolOpen, currentQueue ->
        Quadruple(overlayPanel.first, overlayPanel.second, currentAb, toolOpen, currentQueue)
    }
    private val preferencesAndQueue = combine(preferences, queue, abExportSheet) { currentPreferences, currentQueue, sheet ->
        Triple(currentPreferences, currentQueue, sheet)
    }
    private val fullscreenPolicy = FullscreenPolicy()

    /** 校准状态进 UI 的通道：Calibrating 期间仍显示估算值，Calibrated 后切成精确值。 */
    private val screenshotFrameCalibration: Flow<FrameCalibrationResult?> = sessionClient.snapshot
        .map { it.frameCalibration }
        .distinctUntilChanged()

    private val screenshotLayoutAndRotation = combine(
        screenshot,
        controlLayoutRepository?.layout ?: flowOf(PlayerControlLayout()),
        rotation,
        isFullscreen,
        fillScreen,
    ) { currentScreenshot, currentLayout, currentRotation, fullscreen, fill ->
        PlayerSurfaceState(currentScreenshot, currentLayout, currentRotation, fullscreen, fill)
    }

    /**
     * 帧号校准状态进 UI 的通道。单独合成一层（而不是塞进上面那个 combine），
     * 是因为"帧数校准"和"截图工具 UI"是两件独立的事：校准结果变化不该带动布局/旋转那一路。
     */
    private val surfaceAndCalibration = combine(screenshotLayoutAndRotation, screenshotFrameCalibration) { surface, result ->
        surface.copy(frameCalibration = result)
    }

    /** 手势相关子状态：单独合成一层，避免外层 combine 超过 5 个参数。 */
    private val surfaceAndGesture = combine(
        surfaceAndCalibration,
        gestureHud,
        zoom,
        isTemporarySpeed,
    ) { surface, hud, currentZoom, temporarySpeed ->
        GestureSurfaceState(surface, hud, currentZoom, temporarySpeed)
    }

    val state: StateFlow<PlayerUiState> = combine(
        baseState,
        advancedState,
        overlayPanelAbAndQueue,
        preferencesAndQueue,
        surfaceAndGesture,
    ) { base, advanced, overlayPanelAbAndQueueState, preferencesAndQueueState, gestureSurface ->
        val (currentOverlay, currentPanel, currentAb, currentAbToolOpen, currentQueue) = overlayPanelAbAndQueueState
        val (currentPreferences, _, exportSheet) = preferencesAndQueueState
        val surfaceState = gestureSurface.surface
        val calibrationResult = surfaceState.frameCalibration
        val calibration = (calibrationResult as? FrameCalibrationResult.Calibrated)?.calibration
        val next = base.copy(
            audioTracks = advanced.audioTracks,
            subtitleTracks = advanced.subtitleTracks,
            speed = advanced.speed,
            scaleMode = advanced.scaleMode,
            overlay = currentOverlay,
            panel = currentPanel,
            abLoop = currentAb,
            abToolOpen = currentAbToolOpen,
            abExportSheet = exportSheet,
            queue = currentQueue,
            preferences = currentPreferences,
            screenshot = surfaceState.screenshot,
            controlLayout = surfaceState.layout,
            rotation = surfaceState.rotation,
            isFullscreen = surfaceState.isFullscreen,
            fillScreen = surfaceState.fillScreen,
            gestureHud = gestureSurface.hud,
            zoom = gestureSurface.zoom,
            isTemporarySpeed = gestureSurface.temporarySpeed,
            frameCalibration = calibrationResult,
            measuredFrameRate = calibration?.measuredFrameRate,
        )
        // 帧号只在这里算一次：位置/时长/帧率/校准值全部来自同一份合并结果，
        // UI 层因此不需要（也不应该）自己再取一次这些字段。
        next.copy(
            frameCounter = frameCounterStateOf(
                positionMillis = next.displayedPositionMillis,
                durationMillis = next.playback.timeline.durationMillis ?: next.mediaInfo?.durationMillis,
                frameRate = next.mediaInfo?.frameRate,
                calibration = calibration,
            ),
            // 只有"正在校准 + 估算耗时超过阈值"才标记：小文件的扫描比胶囊入场动画还快，
            // 标出来只会闪一下。校准失败/跳过也不会留下永久标记（那属于"没有精确值"，
            // 不是"正在校准"，两者的提示口径不该混为一谈）。
            frameCounterPending = calibrationResult is FrameCalibrationResult.Calibrating &&
                frameCalibrationNoticeRequired(
                    durationMillis = next.playback.timeline.durationMillis ?: next.mediaInfo?.durationMillis,
                    frameRate = next.mediaInfo?.frameRate,
                    fileSizeBytes = next.mediaInfo?.fileSizeBytes,
                ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), PlayerUiState())

    fun open(
        mediaId: String,
        sourceContext: PlaybackSourceContext,
        queueSource: PlaybackQueueSource? = null,
        preserveQueue: Boolean = false,
    ) {
        val id = runCatching { MediaItemId(mediaId) }.getOrNull() ?: return
        if (sessionClient.snapshot.value.mediaId == id &&
            (queueSource == null || queueSource == activeQueueSource)
        ) return
        panel.value = PlayerPanel.NONE
        closeScreenshot()
        resetAbSession()
        sourceUnavailable.value = false
        val effectiveQueueSource = if (preserveQueue) {
            activeQueueSource ?: PlaybackQueueSource.allVideos()
        } else {
            queueSource ?: PlaybackQueueSource.allVideos()
        }
        activeQueueSource = effectiveQueueSource
        sourceUnavailable.value = false
        sessionClient.dispatch(
            PlaybackSessionCommand.Open(
                PlaybackOpenRequest(
                    sessionId = sessionClient.snapshot.value.sessionId ?: PlaybackSessionId("activity-session"),
                    mediaId = id,
                    sourceContext = sourceContext,
                ),
            ),
        )
        if (!preserveQueue) {
            replaceQueueForSource(id, effectiveQueueSource)
        }
        restoreTrackPreference(id)
    }

    fun openVault(itemId: String, displayTitle: String) {
        val id = runCatching { VaultItemId(itemId) }.getOrNull() ?: return
        panel.value = PlayerPanel.NONE
        closeScreenshot()
        resetAbSession()
        sourceUnavailable.value = false
        title.value = displayTitle
        // Vault 播放没有按媒体偏好，画面旋转回到默认，避免沿用上一个视频的角度。
        rotation.value = VideoRotation.Default
        sessionClient.dispatch(PlaybackSessionCommand.OpenVault(id, displayTitle))
    }

    fun closeVault() {
        sessionClient.dispatch(PlaybackSessionCommand.Stop)
        sourceUnavailable.value = false
    }

    fun play(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Play)
    fun pause(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Pause)
    fun seekTo(positionMillis: Long): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Seek(clampSeek(positionMillis)))
    fun replay(): PlaybackCommandResult {
        return dispatchResult(PlaybackSessionCommand.Play)
    }
    fun retry(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Retry)
    /**
     * 双击快进/快退。基准取**实时位置**：显示位置是会话快照里可能陈旧的值，
     * 用它当基准会让连续快退都从同一个旧位置起算（表现为"按两次只退一次"）。
     */
    fun seekBackward(): PlaybackCommandResult = seekTo(sessionClient.currentPositionMillis() - doubleTapSeekMillis())

    fun seekForward(): PlaybackCommandResult = seekTo(sessionClient.currentPositionMillis() + doubleTapSeekMillis())

    /** 双击快进/快退的步长来自设置（5/10/15/30 秒），不再是硬编码常量。 */
    private fun doubleTapSeekMillis(): Long =
        state.value.preferences.gestureDoubleTapSeekMillis.toLong()

    fun next(): PlaybackCommandResult {
        return dispatchResult(PlaybackSessionCommand.Next)
    }

    fun previous(): PlaybackCommandResult {
        // 两种口径由设置决定：默认"先回本集开头"（超过 5 秒时），也可设为"永远直接切上一项"。
        // 规则本身在域层纯函数里，客户端与媒体会话共用同一判定。
        //
        // 位置取**实时值**而不是 `state.displayedPositionMillis`：后者来自会话快照的 timeline，
        // 只在播放状态跳变时刷新，稳定播放期间会停在旧位置 —— 播到 30 秒时它可能还是几秒前甚至
        // 是 0，于是"播过 5 秒"这条判定会被做反。UI 继续用显示位置做**展示**，但判定必须用实时值。
        return if (
            seeyuer.yingli.player.domain.playback.shouldRestartCurrentItemOnPrevious(
                positionMillis = sessionClient.currentPositionMillis(),
                previousRestartsCurrentItem = state.value.preferences.previousRestartsCurrentItem,
            )
        ) {
            seekTo(0L)
        } else {
            dispatchResult(PlaybackSessionCommand.Previous)
        }
    }

    fun selectQueueItem(index: Int): PlaybackCommandResult {
        val current = state.value.queue ?: return PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.NO_CANDIDATE)
        val mediaId = current.mediaIds.getOrNull(index) ?: return PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.NO_CANDIDATE)
        viewModelScope.launch { playbackQueueRepository?.setQueue(current.copy(currentIndex = index)) }
        open(
            mediaId.value,
            state.value.playback.request?.sourceContext ?: PlaybackSourceContext.HOME,
            preserveQueue = true,
        )
        closePanel()
        return PlaybackCommandResult.Accepted
    }

    /**
     * 打开 AB 工具。三条入口（托盘按钮、设置面板「工具」chip、顶栏溢出菜单）**都走这一条**，
     * 因此三处的行为与状态源不会分叉。
     *
     * 与截图工具的**互斥**按截图状态分三支（docs/20 §3.2）：
     *  - `Armed` / `Capturing` → 先结束截图会话（等价"关闭截图工具"）再打开 AB；关闭 AB **不**恢复截图工具；
     *  - `Preview` → **保留预览卡与倒计时**（那是用户已经拿到的结果），只退出截图的**工具模式**
     *    （"AB 占用播放页时截图工具让位"这条判定收在 `PlayerUiState.isScreenshotToolActive` 一处）；
     *  - 其余（Idle / Failed）→ 直接打开。
     *
     * 两支都必须让**在途的捕获回调作废**：`Capturing` 时打开 AB 会结束会话，那次捕获的结果
     * 晚到之后不得再弹预览卡，否则预览卡会与 AB 胶囊同时出现（[screenshotCaptureGeneration]）。
     */
    fun openAbTool() {
        panel.value = PlayerPanel.NONE
        if (screenshot.value.isCapsuleVisible()) {
            // Armed / Capturing：整个截图会话结束（closeScreenshot 内部同时作废在途捕获）。
            closeScreenshot()
        } else {
            // Preview / Idle / Failed：会话不动，只需作废在途捕获这一次契约动作。
            invalidateScreenshotCapture()
        }
        abToolOpen.value = true
        registerInteraction()
    }

    fun closeAbTool() {
        abToolOpen.value = false
        scheduleOverlayHide()
    }

    /**
     * 设 A / 设 B。规则（互换、相等边界、帧吸附、启用策略）全在会话 runtime：
     * 这里只把命令送出去，结果由会话回流到 [PlayerUiState.abLoop]。
     *
     * 返回值只表示"命令构造是否成功"（参数可解析）。被会话拒绝时由 runtime 产生拒绝码，
     * 经 `PlaybackSessionEvent.OneShotFeedback` 走统一瞬时反馈 —— 拒绝原因不在客户端判定。
     */
    fun setAbPoint(point: AbPoint): Boolean {
        sessionClient.dispatch(PlaybackSessionCommand.SetAbPoint(point))
        registerInteraction()
        return true
    }

    fun clearAb() {
        // 清除 AB 是"让在途导出失去意义"的两个动作之一（另一个是切换媒体，它也经
        // resetAbSession 走到这里）。作废在途导出并关掉 sheet：留着 sheet 就会停在一段
        // 已经不存在的区间上，而它上面的"开始导出"仍然可点（§9.2 C1：AB 是会话临时状态）。
        abExportGeneration++
        abExportSheet.value = null
        sessionClient.dispatch(PlaybackSessionCommand.ClearAb)
    }

    /**
     * 打开「导出当前 AB 区间」的 sheet（§14.6 步骤 11/12）。
     *
     * 只回答"用户现在想导出"，不预设用户要哪种模式：sheet 一出现就给出二选一。
     * 源是否支持无损复制是**异步问**的（要先探一次文件），所以 sheet 先以 [ClipExportMode.FAST]
     * 打开，探测回来说不支持时改成 [ClipExportMode.ACCURATE] 并带上理由 —— 用户看到的是
     * "为什么默认不是快速"，而不是一个没有解释的默认值（§14.6 步骤 13）。
     */
    fun openAbExport() {
        val session = state.value.abLoop
        val start = session.pointA ?: return
        val end = session.pointB ?: return
        if (end <= start) return
        abExportSheet.value = AbExportSheetState(startMillis = start, endMillis = end)
        registerInteraction()
        val probe = clipFastExportProbe ?: return
        val library = libraryRepository ?: return
        val mediaId = state.value.playback.request?.mediaId ?: return
        val generation = abExportGeneration
        viewModelScope.launch {
            val supported = runCatching {
                val media = withContext(dispatchers.io) { library.findByIds(setOf(mediaId)).firstOrNull() }
                    ?: return@runCatching false
                withContext(dispatchers.io) { probe.supportsFastExport(media) }
            }.getOrDefault(false)
            // 期间 AB 被清掉/换了媒体：这张 sheet 说的已经不是当前区间了，绝不再改它。
            if (generation != abExportGeneration) return@launch
            if (!supported) {
                abExportSheet.value = abExportSheet.value?.copy(
                    defaultMode = ClipExportMode.ACCURATE,
                    fastUnavailable = true,
                )
            }
        }
    }

    fun closeAbExport() {
        abExportSheet.value = null
    }

    /**
     * 用户当场二选一之后的提交（§9.4：**不预设默认模式**）。
     *
     * 三件事的顺序是有意的：
     *  1. **先关 sheet**：这是"入队恰好一次"的实现 —— 按钮随 sheet 一起消失，连点不会入两次队；
     *  2. 冻结 `(mediaId, A, B, generation)`：区间在提交那一刻固化成一个 `ClipSegment`，
     *     此后播放继续、AB 被清除、甚至换媒体，都不影响这个已经落库的项目（§9.2 C1/C2）；
     *  3. 经 [ClipExportQueue] 入队（§9.2 C3：成功状态只能由独立验证器产生，所以不能自己写文件）。
     */
    fun exportAbRange(mode: ClipExportMode) {
        val sheet = abExportSheet.value ?: return
        abExportSheet.value = null
        val queue = clipExportQueue
        val ids = idGenerator
        val now = clock?.now()?.toEpochMilli()
        val library = libraryRepository
        val mediaId = state.value.playback.request?.mediaId
        if (queue == null || ids == null || now == null || library == null || mediaId == null) {
            // 协作方不齐（单测/预览）时如实说"这条路径此刻不可用"，不假装成功。
            events.trySend(PlayerUiEvent.TransientMessage(R.string.player_ab_export_unavailable))
            return
        }
        val generation = abExportGeneration
        viewModelScope.launch {
            val media = withContext(dispatchers.io) { library.findByIds(setOf(mediaId)).firstOrNull() }
            val duration = media?.durationMillis?.takeIf { it > 0 }
            if (media == null || duration == null) {
                if (generation == abExportGeneration) {
                    events.trySend(PlayerUiEvent.TransientMessage(R.string.player_ab_export_unavailable))
                }
                return@launch
            }
            // 区间不合法（越界/空）时 `forRange` 抛异常，这里翻成失败反馈 —— 绝不钳制成
            // "看起来能导出"的另一段区间。
            val project = runCatching {
                ClipProject.forRange(
                    sourceMediaId = media.id,
                    sourceLocationId = media.locationId,
                    sourceDurationMillis = duration,
                    startMillis = sheet.startMillis,
                    endMillis = sheet.endMillis,
                    name = abExportSegmentName(media.fileName, sheet.startMillis, sheet.endMillis, mode),
                    exportMode = mode,
                    preset = if (mode == ClipExportMode.FAST) ClipPreset.SOURCE_QUALITY else ClipPreset.COMPATIBLE_MP4,
                    projectId = ClipProjectId(ids.newId()),
                    segmentId = ClipSegmentId(ids.newId()),
                    nowEpochMillis = now,
                )
            }.getOrNull()
            if (project == null) {
                if (generation == abExportGeneration) {
                    events.trySend(PlayerUiEvent.TransientMessage(R.string.player_ab_export_failed))
                }
                return@launch
            }
            if (generation != abExportGeneration) return@launch
            val enqueued = runCatching { queue.enqueue(project) }.isSuccess
            if (generation != abExportGeneration) return@launch
            events.trySend(
                if (enqueued) {
                    PlayerUiEvent.ExportQueued
                } else {
                    PlayerUiEvent.TransientMessage(R.string.player_ab_export_failed)
                },
            )
        }
    }

    fun setSpeed(value: PlaybackSpeed): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetSpeed(value))
        state.value.playback.request?.mediaId?.let { mediaId ->
            viewModelScope.launch {
                val current = trackPreferenceRepository?.trackPreferences?.first()?.resolve(mediaId)
                    ?: TrackPreference()
                trackPreferenceRepository?.setForMedia(mediaId, current.copy(speed = value, scaleMode = state.value.scaleMode))
            }
        }
        return result
    }

    fun setScaleMode(value: VideoScaleMode): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetScale(value))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                persistTrackPreference(mediaId) { copy(scaleMode = value) }
            }
            // 与播放顺序一致：图标、短文本、设置值三处同步反映当前比例。
            events.trySend(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = videoScaleModeLabelRes(value),
                ),
            )
        }
        return result
    }

    /** 底栏快捷按钮：在适应 → 裁剪 → 拉伸之间循环。 */
    fun cycleScaleMode(): PlaybackCommandResult {
        val modes = VideoScaleMode.entries
        val next = modes[(modes.indexOf(state.value.scaleMode) + 1) % modes.size]
        return setScaleMode(next)
    }

    fun setPlaybackOrder(value: PlaybackOrder): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetOrder(value))
        if (result == PlaybackCommandResult.Accepted) {
            // 规格 §18.4：图标、短文本、设置值三处必须同步反映当前播放顺序。
            events.trySend(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_order_state,
                    argumentRes = playbackOrderLabelRes(value),
                ),
            )
        }
        return result
    }

    fun setControlLayout(value: PlayerControlLayout) {
        viewModelScope.launch { controlLayoutRepository?.set(value) }
    }

    /** 底栏快捷按钮：顺时针转到下一个画面角度。 */
    fun rotateVideo() {
        setRotation(state.value.rotation.next())
    }

    /** 设置画面旋转角度并按媒体记住；与播放顺序一样给出统一瞬时反馈。 */
    fun setRotation(value: VideoRotation) {
        rotation.value = value
        state.value.playback.request?.mediaId?.let { mediaId ->
            persistTrackPreference(mediaId) { copy(rotation = value) }
        }
        events.trySend(
            PlayerUiEvent.TransientMessage(
                messageRes = R.string.player_rotation_state,
                argumentRes = videoRotationLabelRes(value),
            ),
        )
    }

    /**
     * 全屏切换：按 [FullscreenPolicy] 决定方向请求与是否填满，然后分别交给窗口网关执行。
     * 只有系统确认成功才更新状态；失败走统一瞬时反馈，不改变已确认状态。
     */
    fun toggleFullscreen() {
        val gateway = windowPlaybackGateway
        val current = state.value
        val plan = fullscreenPolicy.toggle(
            isFullscreen = current.isFullscreen,
            videoAspect = current.videoAspect(),
            portraitWindow = gateway?.state?.value?.isPortrait ?: true,
            scaleMode = current.scaleMode,
        )
        if (gateway == null) {
            isFullscreen.value = plan.isFullscreen
            fillScreen.value = plan.fillScreen
            return
        }
        // 先请求方向：方向失败就整体不生效，避免出现“系统栏藏了但方向没变”的半成品状态。
        val orientationResult = gateway.requestOrientation(plan.orientation)
        if (orientationResult.isFailure) {
            events.trySend(PlayerUiEvent.TransientMessage(R.string.player_orientation_failed))
            return
        }
        val fullscreenResult = gateway.setFullscreen(plan.isFullscreen)
        if (fullscreenResult.isFailure) {
            events.trySend(PlayerUiEvent.TransientMessage(R.string.player_fullscreen_failed))
            return
        }
        fillScreen.value = plan.fillScreen
    }

    /** 离开播放页时调用：恢复系统栏与方向，避免全屏状态泄漏到其他页面。 */
    fun exitFullscreen() {
        val gateway = windowPlaybackGateway ?: return
        if (!state.value.isFullscreen) return
        gateway.setFullscreen(false)
        gateway.requestOrientation(RequestedOrientation.SENSOR)
        fillScreen.value = false
    }
    fun selectAudioTrack(id: String): PlaybackCommandResult {
        val track = state.value.audioTracks.firstOrNull { it.id == id }
        val result = if (track == null) PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.TRACK_UNAVAILABLE)
        else dispatchResult(PlaybackSessionCommand.SelectTrack(seeyuer.yingli.player.domain.playback.TrackSelection(seeyuer.yingli.player.domain.playback.TrackType.AUDIO, track.toFingerprint())))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                state.value.audioTracks.firstOrNull { it.id == id }?.language?.let { language ->
                    persistTrackPreference(mediaId) { copy(audioLanguage = language) }
                }
            }
        }
        return result
    }

    fun selectSubtitleTrack(id: String?): PlaybackCommandResult {
        val track = id?.let { value -> state.value.subtitleTracks.firstOrNull { it.id == value } }
        val result = dispatchResult(PlaybackSessionCommand.SelectTrack(seeyuer.yingli.player.domain.playback.TrackSelection(seeyuer.yingli.player.domain.playback.TrackType.SUBTITLE, track?.toFingerprint())))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                val language = id?.let { selected -> state.value.subtitleTracks.firstOrNull { it.id == selected }?.language }
                persistTrackPreference(mediaId) { copy(subtitleLanguage = language, subtitlesEnabled = id != null) }
            }
        }
        return result
    }

    fun toggleOverlay() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Tap(0))
        // 倍速档位条长在底栏行内：控件收起时它就不可见了，把编辑态一并收掉，
        // 避免下次唤出控件时底栏还停在档位条上。
        if (!overlay.value.controlsVisible) closeSpeedPanel()
        if (overlay.value.controlsVisible) scheduleOverlayHide()
    }

    /** 倍速档位条就地展开/收起（再点一次倍速按钮即收起）。 */
    fun toggleSpeedPanel() {
        if (panel.value == PlayerPanel.SPEED) {
            closePanel()
        } else {
            openPanel(PlayerPanel.SPEED)
        }
    }

    private fun closeSpeedPanel() {
        if (panel.value == PlayerPanel.SPEED) panel.value = PlayerPanel.NONE
    }

    fun toggleLock() {
        panel.value = PlayerPanel.NONE
        abToolOpen.value = false
        closeScreenshot()
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.ToggleLock)
        // 锁定态只保留解锁入口，且和普通控件一样吃 3 秒自动隐藏。
        events.trySend(
            PlayerUiEvent.TransientMessage(
                if (overlay.value.locked) R.string.player_locked else R.string.player_unlocked,
            ),
        )
        scheduleOverlayHide()
    }

    fun openPanel(value: PlayerPanel) {
        abToolOpen.value = false
        closeScreenshot()
        panel.value = PlayerPanelReducer.reduce(panel.value, PlayerPanelEvent.Open(value))
    }

    fun closePanel() {
        panel.value = PlayerPanelReducer.reduce(panel.value, PlayerPanelEvent.Close)
        scheduleOverlayHide()
    }

    fun registerInteraction() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Interaction(0))
        scheduleOverlayHide()
    }

    /**
     * 进入截图模式。
     *
     * **默认暂停播放**（需求：截图定位时不应继续播放）：截图模式下用户要做的是"找到那一帧"，
     * 播放中位置每 250ms 才回报一次，而一帧只有 33ms——画面一直在动，"当前帧"没有稳定含义，
     * 捕获出来的也未必是用户看到的那一帧。暂停后由用户按播放键恢复，与 [stepScreenshotFrame]
     * 的暂停共用 [pauseForFrameStepping] 这一条路径，避免两处各写一份暂停逻辑而互相打架。
     */
    fun armScreenshot() {
        if (screenshotGateway == null || !state.value.playback.supportsScreenshot()) return
        panel.value = PlayerPanel.NONE
        abToolOpen.value = false
        // 新一次截图会话：上一次在途的捕获结果不再属于任何人（generation 契约）。
        invalidateScreenshotCapture()
        pauseForFrameStepping()
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Arm)
        registerInteraction()
    }

    /**
     * 捕获当前帧。
     *
     * **时间戳用实时位置**（`sessionClient.currentPositionMillis()`），不是快照里的显示位置：
     * 显示位置只在状态跳变时刷新，播放推进不会发布状态，拿它当时间戳会存下一个偏后的位置
     * （与 `docs/20` 阶段 1 的"决策/时间戳一律在用时拉取实时值"同一条口径）。
     *
     * 结果回来时要过 [isScreenshotCaptureResultCurrent] 这道契约：期间只要发生过"结束/重开截图会话、
     * 打开 AB 工具、切媒体"，这次结果就**整条丢弃**。文件已经由网关落盘（保留在相册里），
     * 丢弃的只是"预览卡 + 保存位置提示"这条呈现路径。
     */
    fun captureScreenshot() {
        val gateway = screenshotGateway ?: return
        if (screenshot.value != ScreenshotUiState.Armed) return
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.CaptureStarted)
        // 入参全部在**发起前**取好：位置是"按下这一刻"的实时位置，标题/旋转同理。
        val captureTitle = state.value.title
        val capturePositionMillis = sessionClient.currentPositionMillis()
        val captureRotation = state.value.rotation
        val captureMediaId = state.value.playback.request?.mediaId
        val captureGeneration = screenshotCaptureGeneration
        viewModelScope.launch {
            val result = gateway.capture(captureTitle, capturePositionMillis, captureRotation)
            val currentMediaId = state.value.playback.request?.mediaId
            if (!isScreenshotCaptureResultCurrent(
                    captureGeneration = captureGeneration,
                    currentGeneration = screenshotCaptureGeneration,
                    captureMediaId = captureMediaId,
                    currentMediaId = currentMediaId,
                    state = screenshot.value,
                )
            ) {
                return@launch
            }
            screenshot.value = ScreenshotUiReducer.reduce(
                screenshot.value,
                ScreenshotUiEvent.CaptureCompleted(result),
            )
            if (result is ScreenshotResult.Saved) {
                screenshotSession.value = ScreenshotPreviewSession.start(
                    displayName = result.displayName,
                    uri = result.uri,
                    location = result.location,
                )
                startScreenshotPreviewTimer()
            }
        }
    }

    /**
     * 逐帧步进的暂停，也是进入截图模式的暂停：**两处必须走同一条路径**。
     *
     * 直接下发暂停命令而不改 [PlayerUiState] 的本地镜像：真实暂停状态由会话回报，
     * 抢先在本地把状态改成 Paused 只会让"控件在暂停时保持显示"那套自动隐藏逻辑提前生效。
     */
    private fun pauseForFrameStepping() {
        dispatchResult(PlaybackSessionCommand.Pause)
    }

    fun stepScreenshotFrame(forward: Boolean) {
        val current = state.value
        if (screenshot.value != ScreenshotUiState.Armed || !current.canSeek()) return
        // 步进必须与播放互斥：播放中位置每 250ms 才回报一次，而一帧只有 33ms（30fps），
        // 正在播放时"当前帧"没有稳定含义，seek 也会立刻被播放推进覆盖。
        // 因此这里先暂停，由用户按播放键恢复——逐帧检查本来就是暂停态下的交互。
        pauseForFrameStepping()
        val frameRate = effectiveFrameRate(current.mediaInfo?.frameRate, current.measuredFrameRate)
        val baseMillis = resolveFrameStepAnchor(
            // 基准取实时位置：显示位置来自会话快照，只在状态跳变时刷新，拿它步进会在
            // 连续两次"下一帧"时算出同一个目标（表现为"点两下只前进一帧"）。
            positionMillis = sessionClient.currentPositionMillis(),
            anchorMillis = frameStepAnchorMillis,
            frameDurationMillis = frameDurationMillisOf(frameRate),
        )
        val target = frameStepTargetMillis(
            positionMillis = baseMillis,
            frameRate = frameRate,
            durationMillis = current.playback.timeline.durationMillis ?: current.mediaInfo?.durationMillis,
            forward = forward,
        )
        // 锚点记的是"本次落点"，下一次步进从它起算，避免连续步进拿着尚未回报的旧位置原地打转。
        frameStepAnchorMillis = target
        dispatchResult(PlaybackSessionCommand.Seek(target))
        registerInteraction()
    }

    /**
     * 展开 / 收起大图预览（点击预览卡 / 关闭大图）。
     *
     * 展开让倒计时定格，收起时：
     * - 读条还有余量 → 接着原来的余量继续走（不是重新给 3 秒）；
     * - 读条在展开期间已走完 → 按"倒计时结束"的正常流程收场：卡片消失 + 提示保存路径。
     *   这条路径由 [ScreenshotPreviewSession.collapse] 的返回值判定，删除过的不再提示。
     */
    fun setScreenshotPreviewExpanded(expanded: Boolean) {
        if (screenshot.value !is ScreenshotUiState.Preview) return
        val session = screenshotSession.value
        if (expanded) {
            screenshot.value = ScreenshotUiReducer.reduce(
                screenshot.value,
                ScreenshotUiEvent.ExpandChanged(true),
            )
            session?.let { screenshotSession.value = it.expand() }
            return
        }
        // collapse() 只调一次：它同时负责"接着剩余时间继续走"与"判定是否已到期"两件事，
        // 调两次既浪费又容易让两次判定落到不同状态上。
        val resumed = session?.collapse()
        if (session != null && resumed == null) {
            // 读条走完（或这张图已被删除）：收起就等于原来的"倒计时结束、卡片消失"。
            finishScreenshotPreview(session)
            return
        }
        screenshot.value = ScreenshotUiReducer.reduce(
            screenshot.value,
            ScreenshotUiEvent.ExpandChanged(false),
        )
        resumed?.let { screenshotSession.value = it }
    }

    /**
     * 关闭截图工具（胶囊的关闭、返回键、切面板、切媒体都汇到这一条）。
     *
     * 除了收掉卡片与倒计时，它还是**在途捕获作废**的规范入口：[invalidateScreenshotCapture]
     * 必须在状态收掉之前发生，否则那次捕获回来时看到的可能又是 `Capturing`。
     */
    fun closeScreenshot() {
        invalidateScreenshotCapture()
        screenshotTimerJob?.cancel()
        screenshotTimerJob = null
        screenshotSession.value = null
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Close)
        scheduleOverlayHide()
    }

    /**
     * 截图工具激活状态发生了变化：切换跳转精度并触发/取消帧数校准。
     *
     * 退出时一律恢复"按时长选择"的默认策略——恢复成哪个值由 [defaultSeekPrecision] 与
     * 引擎手里的媒体时长决定，UI 不需要知道当前媒体多长。
     */
    private fun applyFrameCaptureSession(active: Boolean) {
        if (active) {
            // 会话层解析当前媒体的真实 URI 后交给校准组件（网络源会被跳过，保持估算显示）。
            sessionClient.calibrateFrames()
            seekPrecisionControl?.setPrecision(SeekPrecision.FRAME_ACCURATE)
        } else {
            sessionClient.stopFrameCalibration()
            seekPrecisionControl?.setPrecision(defaultSeekPrecision(state.value.playback.timeline.durationMillis))
            frameStepAnchorMillis = null
        }
    }
    /**
     * 删除截图。
     *
     * 三件事必须一起成立，顺序不能换：
     * 1. **先把会话标成已删除**——删除过的图片此后不再提示保存路径（需求明确要求），
     *    而"倒计时到期"这条提示路径是由会话驱动的，标记必须发生在状态收掉之前；
     * 2. 卡片立刻消失、倒计时取消：删除是即时反馈，不让用户等 IO；
     * 3. 文件**真的删掉**（复用既有的 [ScreenshotFileGateway]，不另造存储）；
     *    失败时按既有的"权限/失败"文案提示——文件其实还在，所以只提示不撒谎，
     *    也不把卡片重新弹回来（用户点的是删除，不是撤销）。
     */
    fun deleteScreenshot() {
        val preview = screenshot.value as? ScreenshotUiState.Preview ?: return
        screenshotSession.value = screenshotSession.value?.markDeleted()
        val uri = preview.uri
        closeScreenshot()
        if (uri.isBlank()) return
        viewModelScope.launch {
            val result = (screenshotGateway as? ScreenshotFileGateway)?.delete(uri)
                ?: Result.failure(IllegalStateException(ScreenshotDeleteFailure.FAILED.code))
            result.fold(
                onSuccess = {
                    events.send(PlayerUiEvent.TransientMessage(R.string.player_screenshot_deleted))
                },
                onFailure = { error ->
                    val messageRes = when (error.message) {
                        ScreenshotDeleteFailure.PERMISSION_DENIED.code ->
                            R.string.player_screenshot_delete_permission_denied
                        else -> R.string.player_screenshot_delete_failed
                    }
                    events.send(PlayerUiEvent.TransientMessage(messageRes))
                },
            )
        }
    }

    fun enterPictureInPicture(): Boolean = pictureInPictureGateway?.enter() == true

    // ---- 画面手势（规格 FR-PLAYER-004 / #527 / #339）----
    //
    // 识别出的轴向锁定、区域划分和阈值由 feature 层的 PlayerGestureRecognizer 负责；
    // 这里只负责"把位移应用到具体能力"，因此全部可按 JVM 测试驱动。

    fun togglePlayback() {
        when (state.value.playback) {
            is PlaybackState.Playing -> pause()
            else -> play()
        }
    }

    /** 左侧/右侧竖向拖动开始：记录起点值，后续按位移比例调整。 */
    fun beginVolumeGesture() {
        volumeAnchor = deviceControlGateway?.currentVolume() ?: return
    }

    fun beginBrightnessGesture() {
        brightnessAnchor = deviceControlGateway?.currentBrightness() ?: return
    }

    fun applyVolumeGesture(dyFraction: Float) {
        val gateway = deviceControlGateway ?: return
        val next = (volumeAnchor + dyFraction * GESTURE_VALUE_GAIN).coerceIn(0f, 1f)
        gateway.setVolume(next)
        showGestureHud(PlayerGestureHud.Volume(gateway.currentVolume()))
    }

    fun applyBrightnessGesture(dyFraction: Float) {
        val gateway = deviceControlGateway ?: return
        val next = (brightnessAnchor + dyFraction * GESTURE_VALUE_GAIN).coerceIn(0f, 1f)
        gateway.setBrightness(next)
        // 一旦滑动就说明用户在手动调，自动状态随之解除（isAuto 默认 false）。
        showGestureHud(PlayerGestureHud.Brightness(next))
    }

    /**
     * 亮度条顶部图标：在"自动（交回系统亮度覆盖）"与"手动（本页亮度）"之间切换。
     * 自动状态下仍可竖向滑动——滑动即切回手动，并从当前系统亮度接着调，避免跳变。
     */
    fun toggleAutoBrightness() {
        val gateway = deviceControlGateway ?: return
        if (gateway.isFollowingSystemBrightness()) {
            val current = gateway.currentBrightness()
            gateway.setBrightness(current)
            showGestureHud(PlayerGestureHud.Brightness(current, isAuto = false))
        } else {
            gateway.resetBrightness()
            showGestureHud(PlayerGestureHud.Brightness(gateway.currentBrightness(), isAuto = true))
        }
    }

    /** 手势结束：HUD 继续停留一小段时间再消退。 */
    fun endVolumeGesture() = scheduleGestureHudHide()

    fun endBrightnessGesture() = scheduleGestureHudHide()

    /**
     * 长按临时倍速：绝不写入媒体偏好（[setSpeed] 会按媒体持久化），松手恢复到
     * 按下前的倍速，避免"长按一下就把这个视频的偏好速度改了"。
     */
    fun beginTemporarySpeed() {
        if (temporarySpeedAnchor != null) return
        val target = state.value.preferences.longPressSpeed
        val current = state.value.speed
        if (target == current) return
        temporarySpeedAnchor = current
        isTemporarySpeed.value = true
        sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(target))
    }

    fun endTemporarySpeed() {
        val anchor = temporarySpeedAnchor ?: return
        temporarySpeedAnchor = null
        isTemporarySpeed.value = false
        sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(anchor))
    }

    fun setZoom(scale: Float, offsetX: Float, offsetY: Float) {
        zoom.value = VideoZoom.of(scale, offsetX, offsetY)
        showGestureHud(PlayerGestureHud.Zoom(zoom.value.scale))
    }

    fun captureZoomAnchor() {
        zoomAnchor = zoom.value
    }

    /**
     * 双指手势按"相对起点"的增量缩放与平移：捏合传倍率因子（1f 不变），
     * 平移传归一化位移。用增量而不是绝对值，Compose 层就不必自己维护锚点。
     */
    fun applyZoomGesture(scaleFactor: Float, dxFraction: Float = 0f, dyFraction: Float = 0f) {
        val anchor = zoomAnchor
        setZoom(
            scale = anchor.scale * scaleFactor,
            offsetX = anchor.offsetX + dxFraction,
            offsetY = anchor.offsetY + dyFraction,
        )
    }

    /** 放大状态下单指拖动改为平移画面（规格 #529），不再调音量/亮度/进度。 */
    fun panZoom(dxFraction: Float, dyFraction: Float) {
        val current = zoom.value
        if (!current.isActive) return
        zoom.value = VideoZoom.of(current.scale, current.offsetX + dxFraction, current.offsetY + dyFraction)
    }

    /** 水平拖动调整进度：拖动只更新 HUD 预览，松手才提交（与进度条一致）。 */
    fun beginSeekGesture() {
        // 起点取实时位置：拖动距离叠加在一个陈旧位置上提交，落点会整体偏移。
        seekAnchorMillis = sessionClient.currentPositionMillis()
    }

    fun applySeekGesture(dxFraction: Float) {
        val duration = state.value.playback.timeline.durationMillis ?: return
        val target = clampSeek((seekAnchorMillis + dxFraction * duration).toLong())
        gestureHud.value = PlayerGestureHud.Seek(target, duration)
        scheduleGestureHudHide()
    }

    fun endSeekGesture(dxFraction: Float) {
        val duration = state.value.playback.timeline.durationMillis
        if (duration != null) {
            seekTo((seekAnchorMillis + dxFraction * duration).toLong())
        }
        scheduleGestureHudHide()
    }

    fun resetZoom() {
        if (!zoom.value.isActive) return
        zoom.value = VideoZoom.Default
    }

    /** 离开播放页：恢复系统亮度、清掉临时倍速与缩放，不把页面状态带出去。 */
    fun onPlayerPageClosed() {
        exitFullscreen()
        deviceControlGateway?.resetBrightness()
        endTemporarySpeed()
        resetZoom()
        gestureHudHideJob?.cancel()
        gestureHud.value = null
    }

    private fun showGestureHud(hud: PlayerGestureHud) {
        gestureHud.value = hud
        scheduleGestureHudHide()
    }

    private fun scheduleGestureHudHide() {
        gestureHudHideJob?.cancel()
        gestureHudHideJob = viewModelScope.launch {
            delay(PlayerGestureHud.HUD_LINGER_MILLIS)
            gestureHud.value = null
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setMiniPlayerEnabled(enabled) }
    }

    fun setAutoPictureInPicture(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setAutoPictureInPicture(enabled) }
    }

    /** "上一个"的行为：true = 先回本集开头（默认），false = 永远直接切上一项。 */
    fun setPreviousRestartsCurrentItem(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setPreviousRestartsCurrentItem(enabled) }
    }

    // ---- 画面手势设置（规格 FR-PLAYER-004 / #208：每项手势可分别关闭）----

    fun setGestureSeekEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureSeekEnabled(enabled) }
    }

    fun setGestureVolumeEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureVolumeEnabled(enabled) }
    }

    fun setGestureBrightnessEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureBrightnessEnabled(enabled) }
    }

    fun setGestureZoomEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureZoomEnabled(enabled) }
    }

    fun setGestureLeftSideIsVolume(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureLeftSideIsVolume(enabled) }
    }

    fun setGestureDoubleTapSeekMillis(millis: Int) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureDoubleTapSeekMillis(millis) }
    }

    fun setGestureSwipeDownToExitEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureSwipeDownToExitEnabled(enabled) }
    }

    /**
     * 首次进入播放页的手势提示已展示：持久化为"已展示"，此后不再出现（规格 §5.15 / §19.3）。
     * 提示本身由 PlayerScreen 计时收起，这里只负责落库。
     */
    fun markGestureHintShown() {
        viewModelScope.launch { playerPreferenceRepository?.setGestureHintShown(true) }
    }

    fun setGestureLongPressSpeed(speed: PlaybackSpeed) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureLongPressSpeed(speed) }
    }

    /**
     * 后台播放开关（托盘里的状态型按钮）：true = 退到后台继续播放，false = 退到后台暂停。
     * 暂停动作本身由宿主 Activity 在 onStop 里按 [seeyuer.yingli.player.domain.playback.shouldPauseInBackground] 决定。
     */
    fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setBackgroundPlaybackEnabled(enabled) }
    }

    private fun scheduleOverlayHide() {
        overlayHideJob?.cancel()
        if (
            panel.value != PlayerPanel.NONE || abToolOpen.value ||
            screenshot.value is ScreenshotUiState.Armed || screenshot.value is ScreenshotUiState.Capturing
        ) return
        // 规格 §8「暂停默认保持显示」：不在这里短路（提前 return 会改变既有调用方的时序假设，
        // 曾让两个截图用例在删除前就丢失 Preview 状态），改为照常安排超时，在超时回调里判断。
        // 预览卡在场（Preview）刻意**不**加进上面那组短路条件：卡片与帧数胶囊一样是浮层，
        // 自己不受控件自动隐藏影响，遮挡与否由 UI 的层级关系决定，不需要借这条定时器。
        overlayHideJob = viewModelScope.launch {
            delay(PlayerOverlayReducer.AUTO_HIDE_MILLIS)
            val keepVisible = !overlay.value.locked && state.value.playback !is PlaybackState.Playing
            overlay.value = PlayerOverlayReducer.reduce(
                overlay.value,
                if (keepVisible) {
                    // 暂停中：只刷新交互时间，不隐藏控件；锁定态仍走正常隐藏（§5.13 防误触）。
                    PlayerOverlayEvent.Interaction(0)
                } else {
                    PlayerOverlayEvent.Timeout(PlayerOverlayReducer.AUTO_HIDE_MILLIS)
                },
            )
        }
    }

    /**
     * 预览卡倒计时的唯一计时器：每隔 [SCREENSHOT_PREVIEW_TICK_MILLIS] 把时间喂给
     * [ScreenshotPreviewSession]，由它决定读条要不要推进、要不要收场。
     *
     * 为什么是"一个恒转的计时器"而不是"每次改状态重启一个"：展开 / 收起 / 删除都会碰到倒计时，
     * 只要有一个入口忘了重启或忘了取消，就会出现"展开后读条还在走"或"收起后读条不动"；
     * 计时器只有一条生命周期（会话建立 → 会话结束），其余状态变化都只是喂给它的数据。
     * 展开期间循环照常跑（只是把时间记进"看大图的时长"而不是减读条），
     * 所以不存在两处同时推进的余地。
     */
    private fun startScreenshotPreviewTimer() {
        screenshotTimerJob?.cancel()
        screenshotTimerJob = viewModelScope.launch {
            while (true) {
                delay(SCREENSHOT_PREVIEW_TICK_MILLIS)
                val session = screenshotSession.value ?: break
                val tick = session.tick(SCREENSHOT_PREVIEW_TICK_MILLIS)
                screenshotSession.value = tick.session
                tick.publishRemainingMillis?.let { remaining ->
                    val preview = screenshot.value as? ScreenshotUiState.Preview ?: return@let
                    screenshot.value = preview.copy(remainingMillis = remaining)
                }
                if (tick.expired) {
                    // 读条走完 = 卡片消失 + 提示保存路径（需求一.2）。
                    screenshotSession.value = null
                    screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Close)
                    scheduleOverlayHide()
                    notifyScreenshotSavedTo(session)
                    break
                }
                if (tick.notifyLocationOnly) {
                    // 大图预览开着时读条到点：卡片（大图）不消失，只提示一次保存路径；
                    // 用户关掉大图时按同一流程收场（见 setScreenshotPreviewExpanded）。
                    notifyScreenshotSavedTo(session)
                }
            }
        }
    }

    /** 预览卡按"倒计时到期"收场：卡片消失 + 提示保存路径（已删除的不再提示）。 */
    private fun finishScreenshotPreview(session: ScreenshotPreviewSession) {
        screenshotTimerJob?.cancel()
        screenshotTimerJob = null
        screenshotSession.value = null
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Close)
        scheduleOverlayHide()
        notifyScreenshotSavedTo(session)
    }

    /**
     * 提示保存路径。位置为空就不提示——宁可不提示，也不弹一条"已保存到 "这样的半截文案。
     * 走的是项目既有的瞬时反馈通道（播放页顶部那条 Snackbar 样式提示），不新引入组件体系。
     *
     * "只提示一次"落在这里：**提示成功就把会话标成已提示**。会话里那条记录是唯一权威，
     * 而不是靠调用方各自记住"我是不是已经说过了"——那正是重复弹出同一条提示的成因。
     */
    private fun notifyScreenshotSavedTo(session: ScreenshotPreviewSession) {
        if (session.location.isBlank() || session.locationReported) return
        screenshotSession.value = screenshotSession.value?.copy(locationReported = true)
        events.trySend(
            PlayerUiEvent.TransientMessage(
                messageRes = R.string.player_screenshot_saved_to,
                stringArgument = session.location,
            ),
        )
    }

    /**
     * 用户 seek 不再被 AB 区间钳制：D8-A（阶段 0 裁决保留）允许循环期间拖到区间外，
     * 旧实现在这里做的 `abLimiter.clamp` 已删除。位置上下界由会话/引擎自己保证。
     */
    private fun clampSeek(positionMillis: Long): Long = positionMillis.coerceAtLeast(0)

    private fun dispatchResult(command: PlaybackSessionCommand): PlaybackCommandResult {
        sessionClient.dispatch(command)
        return PlaybackCommandResult.Accepted
    }

    private fun resetAbSession() {
        clearAb()
        abToolOpen.value = false
    }

    private fun replaceQueueForSource(mediaId: MediaItemId, source: PlaybackQueueSource) {
        val repository = playbackQueueRepository ?: return
        val library = libraryRepository ?: return
        queueBuildJob?.cancel()
        queueBuildJob = viewModelScope.launch {
            val items = withContext(dispatchers.io) { library.findAll(source.toQuery()) }
            // A newer route may have started another queue build while this query was running.
            // Never let a stale, slower query overwrite the queue for the active source.
            if (activeQueueSource != source) return@launch
            val mediaIds = items.map(LibraryMedia::id).distinct().toMutableList()
            if (mediaId !in mediaIds) mediaIds += mediaId
            val currentIndex = mediaIds.indexOf(mediaId)
            repository.setQueue(
                PlaybackQueue(
                    mediaIds = mediaIds,
                    currentIndex = currentIndex,
                    continuousPlayback = true,
                ),
            )
        }
    }

    private fun restoreTrackPreference(mediaId: MediaItemId) {
        val repository = trackPreferenceRepository ?: return
        viewModelScope.launch {
            val preference = repository.trackPreferences.first().resolve(mediaId)
            sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(preference.speed))
            sessionClient.dispatch(PlaybackSessionCommand.SetScale(preference.scaleMode))
            rotation.value = preference.rotation
            val loaded = withTimeoutOrNull(TRACK_LOAD_TIMEOUT_MILLIS) { state.filter {
                it.playback.request?.mediaId == mediaId &&
                    (it.audioTracks.isNotEmpty() || it.subtitleTracks.isNotEmpty())
            }.first() } ?: return@launch
            preference.audioLanguage?.let { language ->
                loaded.audioTracks.firstOrNull { it.language == language }?.let { selectAudioTrack(it.id) }
            }
            if (!preference.subtitlesEnabled) {
                selectSubtitleTrack(null)
            } else {
                preference.subtitleLanguage?.let { language ->
                    loaded.subtitleTracks.firstOrNull { it.language == language }?.let { selectSubtitleTrack(it.id) }
                }
            }
        }
    }

    private fun persistTrackPreference(mediaId: MediaItemId, update: TrackPreference.() -> TrackPreference) {
        val repository = trackPreferenceRepository ?: return
        viewModelScope.launch {
            val current = repository.trackPreferences.first().resolve(mediaId)
            repository.setForMedia(mediaId, update(current))
        }
    }

    /**
     * 规格 §14.4：命令被拒绝时也要走统一瞬时反馈通道，而不是静默失败。
     * 拒绝码由会话层在 dispatch 时同步产生（见 [PlaybackSessionEvent.OneShotFeedback]），
     * 这里只负责把码翻译成文案。
     */
    init {
        viewModelScope.launch {
            sessionClient.events.collect { event ->
                if (event is PlaybackSessionEvent.OneShotFeedback) {
                    events.trySend(PlayerUiEvent.TransientMessage(playbackRejectionMessageRes(event.code)))
                }
            }
        }
        /*
         * 截图工具进出时切换跳转精度并触发/取消帧数校准。
         *
         * 用 observe 而不是在 arm/close 各处直接调用：进入与退出有多条路径
         *（关闭按钮、返回键、切媒体、切面板、锁定、页面销毁），任何一条漏掉都会留下
         * "一直在精确跳转"或"一直在扫文件"的残留状态；订阅唯一的状态源就不会漏。
         */
        viewModelScope.launch {
            screenshot
                .map { state -> state.isFrameSteppingActive() }
                .drop(1)
                .distinctUntilChanged()
                .collect(::applyFrameCaptureSession)
        }
        // 全屏以系统事实为准：镜像窗口网关的状态，而不是点击后就假设成功。
        viewModelScope.launch {
            windowPlaybackGateway?.state?.collect { windowState ->
                isFullscreen.value = windowState.isFullscreen
            }
        }
        // 暂停时唤出控件（规格 §8「暂停默认保持显示」），只在"播放 → 暂停"的跳变时做一次，
        // 这样用户暂停后主动隐藏控件不会被反复抢回来。
        // 暂停态唤出控件的策略待定（规格 §8 与 §5.13 冲突），先在 init 里不做处理。
    }

    /** 播放画布上依赖的 UI 子状态，合成一个数据类避免 combine 元组过长。 */
    private data class PlayerSurfaceState(
        val screenshot: ScreenshotUiState,
        val layout: PlayerControlLayout,
        val rotation: VideoRotation,
        val isFullscreen: Boolean,
        val fillScreen: Boolean,
        /** 校准状态：Calibrating 期间仍按估算显示，Calibrated 才切精确值。 */
        val frameCalibration: FrameCalibrationResult? = null,
    )

    /** 手势层子状态。 */
    private data class GestureSurfaceState(
        val surface: PlayerSurfaceState,
        val hud: PlayerGestureHud?,
        val zoom: VideoZoom,
        val temporarySpeed: Boolean,
    )

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        /**
         * 竖向手势灵敏度：`2f` = "半屏滑到底"即覆盖整个取值范围。
         * 取 1f 时从屏幕中部划到顶部只能到 50%，用户会以为"不跟手"，故按反馈放大。
         */
        private const val GESTURE_VALUE_GAIN = 2f
        private const val TRACK_LOAD_TIMEOUT_MILLIS = 5_000L
        private const val PLAYLIST_PAGE_SIZE = 60
        private const val PLAYLIST_PREFETCH_DISTANCE = 12
        private const val PLAYLIST_MAX_SIZE = 240

        fun factory(
            sessionClient: PlaybackSessionClient,
            dispatchers: AppDispatchers,
            playerPreferenceRepository: PlayerPreferenceRepository? = null,
            trackPreferenceRepository: TrackPreferenceRepository? = null,
            screenshotGateway: ScreenshotGateway? = null,
            pictureInPictureGateway: PictureInPictureGateway? = null,
            playbackQueueRepository: PlaybackQueueRepository? = null,
            controlLayoutRepository: PlayerControlLayoutRepository? = null,
            libraryRepository: LibraryPagingRepository? = null,
            windowPlaybackGateway: WindowPlaybackGateway? = null,
            deviceControlGateway: DeviceControlGateway? = null,
            seekPrecisionControl: SeekPrecisionControl? = null,
            clipExportQueue: ClipExportQueue? = null,
            clipFastExportProbe: ClipFastExportProbe? = null,
            idGenerator: IdGenerator? = null,
            clock: AppClock? = null,
            ownsSessionClient: Boolean = true,
        ) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    sessionClient,
                    dispatchers,
                    playerPreferenceRepository,
                    trackPreferenceRepository,
                    screenshotGateway,
                    pictureInPictureGateway,
                    playbackQueueRepository,
                    controlLayoutRepository,
                    libraryRepository,
                    windowPlaybackGateway,
                    deviceControlGateway,
                    seekPrecisionControl,
                    clipExportQueue,
                    clipFastExportProbe,
                    idGenerator,
                    clock,
                    ownsSessionClient,
                )
            }
        }
    }

    private data class AdvancedState(
        val audioTracks: List<TrackChoice> = emptyList(),
        val subtitleTracks: List<TrackChoice> = emptyList(),
        val speed: PlaybackSpeed = PlaybackSpeed.Normal,
        val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    )

    private data class Quadruple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}

internal fun orderPlaylistItems(
    queueIds: List<MediaItemId>,
    items: List<LibraryMedia>,
): List<LibraryMedia> {
    val mediaById = items.associateBy { it.id }
    return queueIds.mapNotNull(mediaById::get)
}

/**
 * AB 导出的段名（纯函数，可在 JVM 上逐条钉住）：`<原名>_clip_<起>s-<止>s[_exact]`。
 *
 * 三处刻意的选择：
 *  - 秒数用整数（`6123ms → 6s`）：段名只用于**用户分辨这是哪一段**，毫秒级的精度在文件名里
 *    既读不出来也不可信（AB 两端本就经过帧吸附，真实起点还与关键帧对齐有关）；
 *  - 精确模式加 `_exact`：同一段区间导出两次（一次快速、一次精确）时，两个文件不会看起来一样；
 *  - 原名截断到 `MAX_NAME_LENGTH` 减去后缀长度：后缀必须完整保留，否则"截断"会把区间信息吃掉，
 *    而区间正是这个名字存在的理由。
 */
internal fun abExportSegmentName(
    fileName: String,
    startMillis: Long,
    endMillis: Long,
    mode: ClipExportMode,
): String {
    val base = fileName.substringBeforeLast('.').ifBlank { fileName }
    val suffix = buildString {
        append("_clip_")
        append(startMillis / 1_000)
        append('s')
        append('-')
        append(endMillis / 1_000)
        append('s')
        if (mode == ClipExportMode.ACCURATE) append("_exact")
    }
    val room = (ClipSegment.MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)
    return base.take(room) + suffix
}

private fun PlaybackSessionSnapshot.toPlaybackState(): PlaybackState {
    val request = mediaId?.let {
        seeyuer.yingli.player.domain.playback.PlaybackRequest(
            it,
            seeyuer.yingli.player.core.model.media.MediaLocationId(it.value),
            timeline.positionMillis,
            PlaybackSourceContext.HOME,
        )
    }
    return when (val current = phase) {
        PlaybackPhase.Idle -> PlaybackState.Idle
        is PlaybackPhase.Resolving, is PlaybackPhase.Preparing -> request?.let { PlaybackState.Preparing(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Ready -> request?.let { PlaybackState.Ready(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Playing -> request?.let { PlaybackState.Playing(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Paused -> request?.let { PlaybackState.Paused(it, timeline) } ?: PlaybackState.Idle
        // 重缓冲（seek/缓冲不足）单独标记：UI 据此不显示全屏加载圈，避免快进/快退/拖动进度条时闪加载。
        is PlaybackPhase.Buffering -> request?.let { PlaybackState.Preparing(it, timeline, isRebuffering = true) } ?: PlaybackState.Idle
        is PlaybackPhase.Ended -> request?.let { PlaybackState.Ended(it, timeline, current.next != null) } ?: PlaybackState.Idle
        is PlaybackPhase.Failed -> PlaybackState.Failed(request, timeline, current.error)
    }
}

/**
 * 把展示层的位置换进播放状态的时间线。
 *
 * 只换 `positionMillis`：时长、可 seek 性、请求与动作集仍然来自会话快照 —— 它们随状态跳变，
 * 不随播放推进。这样"状态"和"位置"各有唯一来源，UI 拿到的两半永远同源一致。
 */
private fun PlaybackState.withTimelinePosition(positionMillis: Long): PlaybackState = when (this) {
    PlaybackState.Idle -> this
    is PlaybackState.Preparing -> copy(timeline = timeline.copy(positionMillis = positionMillis))
    is PlaybackState.Ready -> copy(timeline = timeline.copy(positionMillis = positionMillis))
    is PlaybackState.Playing -> copy(timeline = timeline.copy(positionMillis = positionMillis))
    is PlaybackState.Paused -> copy(timeline = timeline.copy(positionMillis = positionMillis))
    is PlaybackState.Ended -> copy(timeline = timeline.copy(positionMillis = positionMillis))
    is PlaybackState.Failed -> copy(timeline = timeline.copy(positionMillis = positionMillis))
}

private fun TrackChoice.toFingerprint() = seeyuer.yingli.player.domain.playback.TrackFingerprint(
    type = seeyuer.yingli.player.domain.playback.TrackType.AUDIO,
    language = language,
    roleFlags = 0,
    codec = null,
    channelCount = null,
    label = label,
)

private fun PlaybackState.supportsScreenshot(): Boolean =
    this is PlaybackState.Ready || this is PlaybackState.Playing || this is PlaybackState.Paused

/** 帧步进需要"可跳转"：队列未就绪/失败时按钮不该算数。 */
private fun PlayerUiState.canSeek(): Boolean = PlaybackAction.SEEK in playback.availableActions

/**
 * 步进起点：优先用上一次步进的落点（seek 回报有延迟），但只在它与回报位置相差不超过一帧时才算数。
 * 差距超过一帧说明用户拖过进度条或换了媒体，锚点已失效，必须回到真实位置。
 */
internal fun resolveFrameStepAnchor(
    positionMillis: Long,
    anchorMillis: Long?,
    frameDurationMillis: Long?,
): Long {
    val anchor = anchorMillis ?: return positionMillis
    val tolerance = (frameDurationMillis ?: DEFAULT_FRAME_STEP_MILLIS).coerceAtLeast(1L)
    return if (abs(positionMillis - anchor) <= tolerance) anchor else positionMillis
}

