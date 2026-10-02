package seeyuer.yingli.player.domain.shorts

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

enum class ShortsFitMode { COVER, CONTAIN, FILL }

data class ShortsPreferences(
    val autoNext: Boolean = true,
    val repeatCurrent: Boolean = false,
    val fitMode: ShortsFitMode = ShortsFitMode.COVER,
    val lockedSpeed: Float = 1f,
    val hintShown: Boolean = false,
)

interface ShortsPreferenceRepository {
    val preferences: Flow<ShortsPreferences>
    val blockedMediaIds: Flow<Set<MediaItemId>>
    suspend fun setAutoNext(enabled: Boolean)
    suspend fun setRepeatCurrent(enabled: Boolean)
    suspend fun setFitMode(mode: ShortsFitMode)
    suspend fun setLockedSpeed(speed: Float)
    suspend fun setHintShown()
    suspend fun setBlocked(mediaId: MediaItemId, blocked: Boolean)
}
