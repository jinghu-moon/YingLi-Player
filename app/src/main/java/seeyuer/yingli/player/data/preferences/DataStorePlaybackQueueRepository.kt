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
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlaybackOrder

private val Context.playbackQueueDataStore by preferencesDataStore(name = "playback_queue")

class DataStorePlaybackQueueRepository(context: Context) : PlaybackQueueRepository {
    private val dataStore = context.applicationContext.playbackQueueDataStore

    override val queue: Flow<PlaybackQueue?> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw cause
        }
        .map { preferences -> PlaybackQueueCodec.decode(preferences[QUEUE]) }

    override suspend fun setQueue(queue: PlaybackQueue?) {
        dataStore.edit { preferences ->
            if (queue == null) preferences.remove(QUEUE) else preferences[QUEUE] = PlaybackQueueCodec.encode(queue)
        }
    }

    private companion object {
        val QUEUE = stringPreferencesKey("queue_v1")
    }
}

internal object PlaybackQueueCodec {
    private const val VERSION = "2"
    private const val FIELD_SEPARATOR = '|'
    private const val ID_SEPARATOR = ','

    fun encode(queue: PlaybackQueue): String = listOf(
        VERSION,
        queue.currentIndex.toString(),
        queue.continuousPlayback.toString(),
        queue.order.name,
        queue.shuffleHistory.joinToString(ID_SEPARATOR.toString()),
        queue.mediaIds.joinToString(ID_SEPARATOR.toString()) { it.value },
    ).joinToString(FIELD_SEPARATOR.toString())

    fun decode(value: String?): PlaybackQueue? {
        if (value.isNullOrBlank()) return null
        val fields = value.split(FIELD_SEPARATOR, limit = 6)
        if (fields.size != 6 || fields[0] != VERSION) return null
        val currentIndex = fields[1].toIntOrNull() ?: return null
        val continuousPlayback = fields[2].toBooleanStrictOrNull() ?: return null
        val order = fields[3].let { stored -> runCatching { PlaybackOrder.valueOf(stored) }.getOrNull() } ?: return null
        val shuffleHistory = fields[4].split(ID_SEPARATOR)
            .filter(String::isNotBlank)
            .map { it.toIntOrNull() ?: return null }
        val mediaIds = fields[5].split(ID_SEPARATOR)
            .filter(String::isNotBlank)
            .mapNotNull { stored -> runCatching { MediaItemId(stored) }.getOrNull() }
        if (
            mediaIds.isEmpty() || mediaIds.distinct().size != mediaIds.size ||
            currentIndex !in mediaIds.indices || shuffleHistory.any { it !in mediaIds.indices }
        ) {
            return null
        }
        return PlaybackQueue(mediaIds, currentIndex, continuousPlayback, order, shuffleHistory)
    }
}
