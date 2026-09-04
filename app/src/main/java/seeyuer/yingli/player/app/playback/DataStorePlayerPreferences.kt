package seeyuer.yingli.player.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.datastore.ThemeRepository
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceSet
import seeyuer.yingli.player.domain.playback.VideoScaleMode

private val Context.playerDataStore by preferencesDataStore(name = "player_preferences")

class DataStorePlayerPreferenceRepository(
    context: Context,
    private val userPreferences: ThemeRepository,
) : PlayerPreferenceRepository, TrackPreferenceRepository {
    private val dataStore = context.applicationContext.playerDataStore
    private val data = dataStore.data.catch { cause ->
        if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw cause
    }

    override val playerPreferences: Flow<PlayerPreferences> = userPreferences.settings.map { values ->
        PlayerPreferences(
            miniPlayerEnabled = values.miniPlayerEnabled,
            autoPictureInPicture = values.autoPictureInPicture,
        )
    }

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

    override suspend fun setAutoPictureInPicture(enabled: Boolean) {
        userPreferences.update { it.copy(autoPictureInPicture = enabled) }
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

    private fun TrackPreference.serialize(): String = listOf(
        audioLanguage.orEmpty(),
        subtitleLanguage.orEmpty(),
        subtitlesEnabled.toString(),
        speed.value.toString(),
        scaleMode.name,
    ).joinToString(",")

    private fun String.toTrackPreference(): TrackPreference {
        val fields = split(',')
        return TrackPreference(
            audioLanguage = fields.getOrNull(0)?.ifBlank { null },
            subtitleLanguage = fields.getOrNull(1)?.ifBlank { null },
            subtitlesEnabled = fields.getOrNull(2)?.toBooleanStrictOrNull() ?: false,
            speed = fields.getOrNull(3)?.toFloatOrNull()?.let { value ->
                runCatching { PlaybackSpeed.of(value) }.getOrNull()
            } ?: PlaybackSpeed.Normal,
            scaleMode = fields.getOrNull(4)?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
                ?: VideoScaleMode.FIT,
        )
    }

    private companion object {
        val GLOBAL_TRACK = stringPreferencesKey("global_track_preference")
        val MEDIA_TRACKS = stringPreferencesKey("media_track_preferences")
    }
}

class InMemoryPlaybackQueueRepository : PlaybackQueueRepository {
    private val mutableQueue = MutableStateFlow<PlaybackQueue?>(null)
    override val queue: Flow<PlaybackQueue?> = mutableQueue
    override suspend fun setQueue(queue: PlaybackQueue?) {
        mutableQueue.value = queue
    }
}
