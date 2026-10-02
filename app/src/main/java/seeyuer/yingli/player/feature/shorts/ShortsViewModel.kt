package seeyuer.yingli.player.feature.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.ScreenshotUiEvent
import seeyuer.yingli.player.domain.playback.ScreenshotUiReducer
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.shorts.ShortsPreferenceRepository
import seeyuer.yingli.player.domain.shorts.ShortsFitMode

/** 独立的短视频候选队列与会话状态投影。 */
class ShortsViewModel(
    private val sessionClient: PlaybackSessionClient,
    private val libraryRepository: LibraryPagingRepository,
    private val dispatchers: AppDispatchers,
    private val preferenceRepository: ShortsPreferenceRepository? = null,
    private val organizeRepository: OrganizeRepository? = null,
    private val mutationRepository: LibraryMutationRepository? = null,
    private val screenshotGateway: ScreenshotGateway? = null,
    private val pictureInPictureGateway: seeyuer.yingli.player.domain.playback.PictureInPictureGateway? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ShortsUiState())
    val state: StateFlow<ShortsUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var screenshotExpiryJob: Job? = null
    private var switching = false
    private var handledEndedMediaId: MediaItemId? = null

    init {
        viewModelScope.launch {
            sessionClient.snapshot.collect { snapshot ->
                val current = mutableState.value.current ?: return@collect
                if (snapshot.mediaId != current.id) return@collect
                val playing = snapshot.phase is PlaybackPhase.Playing
                if (snapshot.phase is PlaybackPhase.Ended && handledEndedMediaId != current.id) {
                    handledEndedMediaId = current.id
                    when {
                        mutableState.value.repeatCurrent -> openCurrent(startPositionMillis = 0L)
                        mutableState.value.autoNext -> next()
                    }
                }
                if (snapshot.phase !is PlaybackPhase.Ended) handledEndedMediaId = null
                mutableState.value = mutableState.value.copy(
                    playing = playing,
                    progressMillis = snapshot.timeline.positionMillis,
                    durationMillis = snapshot.timeline.durationMillis ?: current.durationMillis,
                )
                mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.MediaInfoChanged(snapshot.mediaInfo))
            }
        }
        preferenceRepository?.let { repository ->
            viewModelScope.launch {
                combine(repository.preferences, repository.blockedMediaIds) { preferences, blocked -> preferences to blocked }
                    .collect { (preferences, blocked) ->
                        mutableState.value = ShortsReducer.reduce(
                            mutableState.value,
                            ShortsEvent.PreferencesChanged(
                                preferences.autoNext,
                                preferences.repeatCurrent,
                                preferences.fitMode,
                                preferences.lockedSpeed,
                                preferences.hintShown,
                            ),
                        ).copy(
                            isBlocked = mutableState.value.current?.id in blocked,
                            blockedCount = blocked.size,
                        )
                    }
            }
        }
        organizeRepository?.let { repository ->
            viewModelScope.launch {
                repository.snapshot.collect { snapshot ->
                    val currentId = mutableState.value.current?.id
                    mutableState.value = ShortsReducer.reduce(
                        mutableState.value,
                        ShortsEvent.FavoriteChanged(
                            currentId != null && snapshot.favorites.any { it.mediaId == currentId },
                            snapshot.favorites.size,
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                libraryRepository.observe(LibraryQuery(pageSize = LibraryQuery.MAX_PAGE_SIZE, browseMode = LibraryBrowseMode.ALL_VIDEOS)),
                organizeRepository?.snapshot ?: kotlinx.coroutines.flow.flowOf(null),
                preferenceRepository?.blockedMediaIds ?: kotlinx.coroutines.flow.flowOf(emptySet()),
            ) { library, organize, blocked -> Triple(library, organize, blocked) }
                .collect { (library, organize, blocked) ->
                    val items = (library as? LibraryResult.Success)?.value?.items.orEmpty()
                        .filter { it.width != null && it.height != null && it.height > it.width }
                        .map { media -> ShortsCandidate(media.id, media.title, media.uri, media.durationMillis, media.width, media.height, media.playbackPositionMillis) }
                    val favoriteIds = organize?.favorites?.map { it.mediaId }?.toSet().orEmpty()
                    mutableState.value = ShortsReducer.reduce(
                        mutableState.value,
                        ShortsEvent.ManagedItemsChanged(items.filter { it.id in favoriteIds }, items.filter { it.id in blocked }),
                    )
                }
        }
    }

    fun initialize() {
        if (loadJob?.isActive == true || mutableState.value.candidates.isNotEmpty()) return
        loadJob = viewModelScope.launch {
            val blocked = preferenceRepository?.blockedMediaIds?.first().orEmpty()
            val result = withContext(dispatchers.io) {
                libraryRepository.query(
                    LibraryQuery(pageSize = LibraryQuery.MAX_PAGE_SIZE, browseMode = LibraryBrowseMode.ALL_VIDEOS),
                )
            }
            when (result) {
                is LibraryResult.Success -> {
                    val candidates = result.value.items
                        .asSequence()
                        .filter { media ->
                            val width = media.width
                            val height = media.height
                            media.id !in blocked && width != null && height != null && height > width
                        }
                        .map { media ->
                            ShortsCandidate(
                                id = media.id,
                                title = media.title,
                                uri = media.uri,
                                durationMillis = media.durationMillis,
                                width = media.width,
                                height = media.height,
                                playbackPositionMillis = media.playbackPositionMillis,
                            )
                        }
                        .toList()
                    mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.CandidatesLoaded(candidates))
                    if (candidates.isNotEmpty()) openCurrent()
                    if (!mutableState.value.hintShown) {
                        delay(2_800)
                        preferenceRepository?.setHintShown()
                    }
                }
                LibraryResult.RetryableFailure -> {
                    mutableState.value = ShortsReducer.reduce(
                        mutableState.value,
                        ShortsEvent.Failed("LIBRARY_UNAVAILABLE"),
                    )
                }
            }
        }
    }

    fun togglePlayback() {
        if (mutableState.value.current == null) return
        if (mutableState.value.playing) sessionClient.dispatch(PlaybackSessionCommand.Pause)
        else sessionClient.dispatch(PlaybackSessionCommand.Play)
    }

    fun next() = moveTo(ShortsReducer.nextIndex(mutableState.value))

    fun previous() = moveTo(ShortsReducer.previousIndex(mutableState.value))

    fun seekBy(offsetMillis: Long) {
        if (mutableState.value.current != null) sessionClient.dispatch(PlaybackSessionCommand.SeekBy(offsetMillis))
    }

    fun toggleLockedSpeed() {
        val speed = if (mutableState.value.lockedSpeed == 1f) 2f else 1f
        viewModelScope.launch { preferenceRepository?.setLockedSpeed(speed) }
        sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(PlaybackSpeed.of(speed)))
    }

    fun beginTemporarySpeed() {
        if (mutableState.value.current != null) {
            sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(PlaybackSpeed.of(2f)))
        }
    }

    fun endTemporarySpeed() {
        if (mutableState.value.current != null) {
            sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(PlaybackSpeed.of(mutableState.value.lockedSpeed)))
        }
    }


    fun setAutoNext(enabled: Boolean) { viewModelScope.launch { preferenceRepository?.setAutoNext(enabled) } }
    fun setRepeatCurrent(enabled: Boolean) { viewModelScope.launch { preferenceRepository?.setRepeatCurrent(enabled) } }
    fun cycleFitMode() {
        val next = ShortsFitMode.entries[(mutableState.value.fitMode.ordinal + 1) % ShortsFitMode.entries.size]
        viewModelScope.launch { preferenceRepository?.setFitMode(next) }
        val scale = when (next) {
            ShortsFitMode.COVER -> VideoScaleMode.FILL
            ShortsFitMode.CONTAIN -> VideoScaleMode.FIT
            ShortsFitMode.FILL -> VideoScaleMode.ORIGINAL
        }
        sessionClient.dispatch(PlaybackSessionCommand.SetScale(scale))
    }
    fun toggleFavorite() {
        val id = mutableState.value.current?.id ?: return
        viewModelScope.launch { organizeRepository?.setFavorite(setOf(id), !mutableState.value.isFavorite) }
    }

    fun removeFavorite(mediaId: MediaItemId) {
        viewModelScope.launch { organizeRepository?.setFavorite(setOf(mediaId), false) }
    }

    fun removeBlocked(mediaId: MediaItemId) {
        viewModelScope.launch { preferenceRepository?.setBlocked(mediaId, false) }
    }
    fun toggleBlocked() {
        val id = mutableState.value.current?.id ?: return
        viewModelScope.launch {
            val shouldBlock = !mutableState.value.isBlocked
            preferenceRepository?.setBlocked(id, shouldBlock)
            if (shouldBlock) {
                closeScreenshot()
                val remaining = mutableState.value.candidates.filterNot { it.id == id }
                mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.CandidatesLoaded(remaining))
                if (remaining.isNotEmpty()) openCurrent() else sessionClient.dispatch(PlaybackSessionCommand.Pause)
            }
        }
    }

    fun deleteCurrent() {
        val current = mutableState.value.current ?: return
        viewModelScope.launch {
            closeScreenshot()
            val repository = mutationRepository ?: return@launch
            val media = withContext(dispatchers.io) { libraryRepository.findByIds(setOf(current.id)).firstOrNull() }
            if (media == null) {
                mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.Failed("SOURCE_MISSING"))
                return@launch
            }
            val result = withContext(dispatchers.io) { repository.trash(listOf(media)) }
            if (result.succeeded == 1) {
                val remaining = mutableState.value.candidates.filterNot { it.id == current.id }
                mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.CandidatesLoaded(remaining))
                if (remaining.isNotEmpty()) openCurrent()
            } else {
                mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.Failed("DELETE_FAILED"))
            }
        }
    }

    fun captureScreenshot() {
        val current = mutableState.value.current ?: return
        val gateway = screenshotGateway ?: return
        viewModelScope.launch {
            screenshotExpiryJob?.cancel()
            mutableState.value = ShortsReducer.reduce(
                mutableState.value,
                ShortsEvent.ScreenshotChanged(ScreenshotUiState.Capturing),
            )
            val result = withContext(dispatchers.io) {
                // Shorts 不复用常规播放器的画面旋转，截图始终按原始朝向保存。
                gateway.capture(current.title, mutableState.value.progressMillis, VideoRotation.Default)
            }
            val screenshot = when (result) {
                is ScreenshotResult.Saved -> ScreenshotUiState.Preview(result.displayName, result.uri, result.location)
                is ScreenshotResult.Failed -> ScreenshotUiState.Failed(result.reason)
            }
            mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.ScreenshotChanged(screenshot))
            if (screenshot is ScreenshotUiState.Preview) scheduleScreenshotExpiry()
        }
    }

    fun closeScreenshot() {
        screenshotExpiryJob?.cancel()
        mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.ScreenshotChanged(ScreenshotUiState.Idle))
    }

    fun toggleScreenshotExpiryPause() {
        mutableState.value = ShortsReducer.reduce(
            mutableState.value,
            ShortsEvent.ScreenshotChanged(
                // 与常规播放页同一套语义：展开预览即定格倒计时（收起时若读条已走完，
                // 状态已经是 Idle，不会再被 TimeElapsed 推着走）。
                when (val screenshot = mutableState.value.screenshot) {
                    is ScreenshotUiState.Preview -> screenshot.copy(expanded = !screenshot.expanded && screenshot.uri.isNotBlank())
                    else -> screenshot
                },
            ),
        )
    }

    fun deleteScreenshot() {
        val preview = mutableState.value.screenshot as? ScreenshotUiState.Preview ?: return
        closeScreenshot()
        if (preview.uri.isBlank()) return
        viewModelScope.launch { (screenshotGateway as? ScreenshotFileGateway)?.delete(preview.uri) }
    }

    fun enterPictureInPicture(): Boolean = pictureInPictureGateway?.enter() == true

    private fun moveTo(index: Int?) {
        if (switching || index == null || index == mutableState.value.currentIndex) return
        switching = true
        closeScreenshot()
        mutableState.value = ShortsReducer.reduce(mutableState.value, ShortsEvent.CurrentChanged(index))
        openCurrent()
        viewModelScope.launch {
            delay(SWITCH_TRANSITION_MILLIS)
            switching = false
        }
    }

    private fun openCurrent(startPositionMillis: Long? = null) {
        val candidate = mutableState.value.current ?: return
        val sessionId = sessionClient.snapshot.value.sessionId ?: PlaybackSessionId("shorts-session")
        sessionClient.dispatch(
            PlaybackSessionCommand.Open(
                PlaybackOpenRequest(
                    sessionId = sessionId,
                    mediaId = candidate.id,
                    sourceContext = PlaybackSourceContext.LIBRARY,
                    startPositionMillis = startPositionMillis ?: candidate.playbackPositionMillis,
                ),
            ),
        )
        sessionClient.dispatch(PlaybackSessionCommand.Play)
    }

    private fun scheduleScreenshotExpiry() {
        screenshotExpiryJob?.cancel()
        screenshotExpiryJob = viewModelScope.launch {
            while (true) {
                delay(100)
                val preview = mutableState.value.screenshot as? ScreenshotUiState.Preview ?: break
                val nextScreenshot = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.TimeElapsed(100))
                mutableState.value = ShortsReducer.reduce(
                    mutableState.value,
                    ShortsEvent.ScreenshotChanged(nextScreenshot),
                )
            }
        }
    }

    companion object {
        private const val SWITCH_TRANSITION_MILLIS = 320L

        fun factory(
            sessionClient: PlaybackSessionClient,
            libraryRepository: LibraryPagingRepository,
            dispatchers: AppDispatchers,
            preferenceRepository: ShortsPreferenceRepository? = null,
            organizeRepository: OrganizeRepository? = null,
            mutationRepository: LibraryMutationRepository? = null,
            screenshotGateway: ScreenshotGateway? = null,
            pictureInPictureGateway: seeyuer.yingli.player.domain.playback.PictureInPictureGateway? = null,
        ) = viewModelFactory {
            initializer {
                ShortsViewModel(
                    sessionClient,
                    libraryRepository,
                    dispatchers,
                    preferenceRepository,
                    organizeRepository,
                    mutationRepository,
                    screenshotGateway,
                    pictureInPictureGateway,
                )
            }
        }
    }
}
