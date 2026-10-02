package seeyuer.yingli.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceSet
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation

private val Context.playerDataStore by preferencesDataStore(name = "player_preferences")

class DataStorePlayerPreferenceRepository(
    context: Context,
    private val userPreferences: ThemeRepository,
) : PlayerPreferenceRepository, TrackPreferenceRepository {
    private val dataStore = context.applicationContext.playerDataStore
    private val data = dataStore.data.catch { cause ->
        if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw cause
    }

    override val playerPreferences: Flow<PlayerPreferences> = userPreferences.settings.map { it.toPlayerPreferences() }

    override val trackPreferences: Flow<TrackPreferenceSet>
        get() = data.map { values ->
            TrackPreferenceSet(
                global = values[GLOBAL_TRACK]?.toTrackPreference() ?: TrackPreference(),
                perMedia = values[MEDIA_TRACKS].orEmpty().lineSequence().mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0) null else runCatching {
                        MediaItemId(line.take(separator)) to line.drop(separator + 1).toTrackPreference()
                    }.getOrNull()
                }.toMap(),
            )
        }

    override suspend fun setMiniPlayerEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(miniPlayerEnabled = enabled) }
    }

    override suspend fun setPreviousRestartsCurrentItem(enabled: Boolean) {
        userPreferences.update { it.copy(previousRestartsCurrentItem = enabled) }
    }

    override suspend fun setAutoPictureInPicture(enabled: Boolean) {
        userPreferences.update { it.copy(autoPictureInPicture = enabled) }
    }

    override suspend fun setGestureSeekEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(gestureSeekEnabled = enabled) }
    }

    override suspend fun setGestureVolumeEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(gestureVolumeEnabled = enabled) }
    }

    override suspend fun setGestureBrightnessEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(gestureBrightnessEnabled = enabled) }
    }

    override suspend fun setGestureZoomEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(gestureZoomEnabled = enabled) }
    }

    override suspend fun setGestureLeftSideIsVolume(enabled: Boolean) {
        userPreferences.update { it.copy(gestureLeftSideIsVolume = enabled) }
    }

    override suspend fun setGestureDoubleTapSeekMillis(millis: Int) {
        val safeMillis = PlayerPreferences.doubleTapSeekMillisOrDefault(millis)
        userPreferences.update { it.copy(gestureDoubleTapSeekMillis = safeMillis) }
    }

    override suspend fun setGestureSwipeDownToExitEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(gestureSwipeDownToExitEnabled = enabled) }
    }

    override suspend fun setGestureHintShown(shown: Boolean) {
        userPreferences.update { it.copy(gestureHintShown = shown) }
    }

    override suspend fun setGestureLongPressSpeed(speed: PlaybackSpeed) {
        val safeSpeed = PlayerPreferences.longPressSpeedOrDefault(speed.value)
        userPreferences.update { it.copy(gestureLongPressSpeed = safeSpeed.value) }
    }

    override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        userPreferences.update { it.copy(backgroundPlaybackEnabled = enabled) }
    }

    override suspend fun setGlobal(preference: TrackPreference) {
        dataStore.edit { it[GLOBAL_TRACK] = preference.serialize() }
    }

    override suspend fun setForMedia(mediaId: MediaItemId, preference: TrackPreference?) {
        dataStore.edit { values ->
            val current = values[MEDIA_TRACKS].orEmpty().lineSequence().mapNotNull { line ->
                val separator = line.indexOf('|')
                if (separator <= 0) null else line.take(separator) to line.drop(separator + 1)
            }.toMap().toMutableMap()
            if (preference == null) current.remove(mediaId.value) else current[mediaId.value] = preference.serialize()
            values[MEDIA_TRACKS] = current.toSortedMap().entries.joinToString("\n") { "${it.key}|${it.value}" }
        }
    }

    private fun TrackPreference.serialize(): String = TrackPreferenceCodec.serialize(this)

    private fun String.toTrackPreference(): TrackPreference = TrackPreferenceCodec.parse(this)

    private companion object {
        val GLOBAL_TRACK = stringPreferencesKey("global_track_preference")
        val MEDIA_TRACKS = stringPreferencesKey("media_track_preferences")
    }
}

/**
 * 持久化的播放偏好投影到域层契约。长按倍速在这里从存储用的 Float 还原成 [PlaybackSpeed]，
 * 越界值回退默认档位，因此域层拿到的倍速一定是受支持的值。
 */
internal fun UserPreferences.toPlayerPreferences(): PlayerPreferences = PlayerPreferences(
    miniPlayerEnabled = miniPlayerEnabled,
    autoPictureInPicture = autoPictureInPicture,
    longPressSpeed = PlayerPreferences.longPressSpeedOrDefault(gestureLongPressSpeed),
    gestureSeekEnabled = gestureSeekEnabled,
    gestureVolumeEnabled = gestureVolumeEnabled,
    gestureBrightnessEnabled = gestureBrightnessEnabled,
    gestureZoomEnabled = gestureZoomEnabled,
    gestureLeftSideIsVolume = gestureLeftSideIsVolume,
    gestureDoubleTapSeekMillis = gestureDoubleTapSeekMillis,
    gestureSwipeDownToExitEnabled = gestureSwipeDownToExitEnabled,
    gestureHintShown = gestureHintShown,
    previousRestartsCurrentItem = previousRestartsCurrentItem,
    backgroundPlaybackEnabled = backgroundPlaybackEnabled,
)

/**
 * 按媒体的播放偏好编解码。字段是位置相关的 CSV，因此**只能在末尾追加字段**：
 * 老记录缺少尾部字段时按默认值补齐，坏值（未知枚举名、非法倍速）逐字段回退，
 * 不让一条脏记录毁掉整份偏好。
 */
internal object TrackPreferenceCodec {
    fun serialize(preference: TrackPreference): String = listOf(
        preference.audioLanguage.orEmpty(),
        preference.subtitleLanguage.orEmpty(),
        preference.subtitlesEnabled.toString(),
        preference.speed.value.toString(),
        preference.scaleMode.name,
        preference.rotation.name,
    ).joinToString(",")

    fun parse(raw: String): TrackPreference {
        val fields = raw.split(',')
        return TrackPreference(
            audioLanguage = fields.getOrNull(0)?.ifBlank { null },
            subtitleLanguage = fields.getOrNull(1)?.ifBlank { null },
            subtitlesEnabled = fields.getOrNull(2)?.toBooleanStrictOrNull() ?: false,
            speed = fields.getOrNull(3)?.toFloatOrNull()?.let { value ->
                runCatching { PlaybackSpeed.of(value) }.getOrNull()
            } ?: PlaybackSpeed.Normal,
            scaleMode = fields.getOrNull(4)?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
                ?: VideoScaleMode.FIT,
            rotation = fields.getOrNull(5)?.let { stored -> VideoRotation.entries.firstOrNull { it.name == stored } }
                ?: VideoRotation.Default,
        )
    }
}
