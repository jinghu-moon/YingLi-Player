package seeyuer.yingli.player.feature.shorts

import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.shorts.ShortsFitMode
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

data class ShortsCandidate(
    val id: MediaItemId,
    val title: String,
    val uri: MediaUri? = null,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val playbackPositionMillis: Long = 0,
) {
    init {
        require(title.isNotBlank())
        require(durationMillis == null || durationMillis >= 0)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(playbackPositionMillis >= 0)
    }
}

data class ShortsUiState(
    val candidates: List<ShortsCandidate> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = true,
    val errorCode: String? = null,
    val progressMillis: Long = 0,
    val durationMillis: Long? = null,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val autoNext: Boolean = true,
    val repeatCurrent: Boolean = false,
    val fitMode: ShortsFitMode = ShortsFitMode.COVER,
    val lockedSpeed: Float = 1f,
    val mediaInfo: PlaybackMediaInfo? = null,
    val favoriteCount: Int = 0,
    val blockedCount: Int = 0,
    val screenshot: ScreenshotUiState = ScreenshotUiState.Idle,
    val hintShown: Boolean = false,
    val favoriteItems: List<ShortsCandidate> = emptyList(),
    val blockedItems: List<ShortsCandidate> = emptyList(),
) {
    val current: ShortsCandidate? get() = candidates.getOrNull(currentIndex)
}

sealed interface ShortsEvent {
    data class CandidatesLoaded(val candidates: List<ShortsCandidate>) : ShortsEvent
    data class CurrentChanged(val index: Int) : ShortsEvent
    data class PlaybackChanged(val playing: Boolean, val progressMillis: Long, val durationMillis: Long?) : ShortsEvent
    data class Failed(val code: String) : ShortsEvent
    data class FavoriteChanged(val value: Boolean, val count: Int = 0) : ShortsEvent
    data class BlockedChanged(val value: Boolean, val count: Int = 0) : ShortsEvent
    data class PreferencesChanged(
        val autoNext: Boolean,
        val repeatCurrent: Boolean,
        val fitMode: ShortsFitMode,
        val lockedSpeed: Float,
        val hintShown: Boolean,
    ) : ShortsEvent
    data class MediaInfoChanged(val info: PlaybackMediaInfo?) : ShortsEvent
    data class ScreenshotChanged(val screenshot: ScreenshotUiState) : ShortsEvent
    data class ManagedItemsChanged(
        val favorites: List<ShortsCandidate>,
        val blocked: List<ShortsCandidate>,
    ) : ShortsEvent
}

object ShortsReducer {
    fun reduce(state: ShortsUiState, event: ShortsEvent): ShortsUiState = when (event) {
        is ShortsEvent.CandidatesLoaded -> {
            val index = state.currentIndex.takeIf { it in event.candidates.indices } ?: event.candidates.indices.firstOrNull() ?: -1
            state.copy(
                candidates = event.candidates,
                currentIndex = index,
                loading = false,
                errorCode = null,
                progressMillis = event.candidates.getOrNull(index)?.playbackPositionMillis ?: 0,
                durationMillis = event.candidates.getOrNull(index)?.durationMillis,
            )
        }
        is ShortsEvent.CurrentChanged -> if (event.index in state.candidates.indices) {
            state.copy(
                currentIndex = event.index,
                playing = false,
                progressMillis = state.candidates[event.index].playbackPositionMillis,
                durationMillis = state.candidates[event.index].durationMillis,
            )
        } else state
        is ShortsEvent.PlaybackChanged -> state.copy(
            playing = event.playing,
            progressMillis = event.progressMillis.coerceAtLeast(0),
            durationMillis = event.durationMillis,
        )
        is ShortsEvent.Failed -> state.copy(loading = false, playing = false, errorCode = event.code)
        is ShortsEvent.FavoriteChanged -> state.copy(isFavorite = event.value, favoriteCount = event.count)
        is ShortsEvent.BlockedChanged -> state.copy(isBlocked = event.value, blockedCount = event.count)
        is ShortsEvent.PreferencesChanged -> state.copy(
            autoNext = event.autoNext,
            repeatCurrent = event.repeatCurrent,
            fitMode = event.fitMode,
            lockedSpeed = event.lockedSpeed,
            hintShown = event.hintShown,
        )
        is ShortsEvent.MediaInfoChanged -> state.copy(mediaInfo = event.info)
        is ShortsEvent.ScreenshotChanged -> state.copy(screenshot = event.screenshot)
        is ShortsEvent.ManagedItemsChanged -> state.copy(
            favoriteItems = event.favorites,
            blockedItems = event.blocked,
        )
    }

    fun nextIndex(state: ShortsUiState): Int? = when {
        state.candidates.isEmpty() -> null
        state.currentIndex + 1 in state.candidates.indices -> state.currentIndex + 1
        else -> 0
    }

    fun previousIndex(state: ShortsUiState): Int? = when {
        state.candidates.isEmpty() -> null
        state.currentIndex - 1 in state.candidates.indices -> state.currentIndex - 1
        else -> state.candidates.lastIndex
    }
}

enum class ShortsGestureAxis { NONE, VERTICAL, HORIZONTAL }

sealed interface ShortsGestureAction {
    data object None : ShortsGestureAction
    data object Next : ShortsGestureAction
    data object Previous : ShortsGestureAction
    data object SeekForward : ShortsGestureAction
    data object SeekBackward : ShortsGestureAction
}

data class ShortsGestureState(
    val axis: ShortsGestureAxis = ShortsGestureAxis.NONE,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val moved: Boolean = false,
)

object ShortsGestureReducer {
    fun drag(state: ShortsGestureState, deltaX: Float, deltaY: Float): ShortsGestureState {
        val x = state.deltaX + deltaX
        val y = state.deltaY + deltaY
        val axis = when (state.axis) {
            ShortsGestureAxis.NONE -> when {
                kotlin.math.abs(y) >= 10f && kotlin.math.abs(y) > kotlin.math.abs(x) -> ShortsGestureAxis.VERTICAL
                kotlin.math.abs(x) >= 10f && kotlin.math.abs(x) > kotlin.math.abs(y) -> ShortsGestureAxis.HORIZONTAL
                else -> ShortsGestureAxis.NONE
            }
            else -> state.axis
        }
        return state.copy(axis = axis, deltaX = x, deltaY = y, moved = state.moved || kotlin.math.hypot(x.toDouble(), y.toDouble()) >= 8)
    }

    fun finish(state: ShortsGestureState): ShortsGestureAction = when (state.axis) {
        ShortsGestureAxis.VERTICAL -> when {
            state.deltaY <= -64f -> ShortsGestureAction.Next
            state.deltaY >= 64f -> ShortsGestureAction.Previous
            else -> ShortsGestureAction.None
        }
        ShortsGestureAxis.HORIZONTAL -> when {
            state.deltaX >= 42f -> ShortsGestureAction.SeekForward
            state.deltaX <= -42f -> ShortsGestureAction.SeekBackward
            else -> ShortsGestureAction.None
        }
        ShortsGestureAxis.NONE -> ShortsGestureAction.None
    }
}
