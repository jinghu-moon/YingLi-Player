package seeyuer.yingli.player.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.annotation.OptIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackProgressSample
import seeyuer.yingli.player.domain.playback.PlaybackProgressWritePolicy
import seeyuer.yingli.player.domain.playback.ProgressWriteDecision
import seeyuer.yingli.player.domain.playback.ProgressWriteReason
import seeyuer.yingli.player.data.security.VaultAwareDataSource
import seeyuer.yingli.player.engine.media3.PlaybackMediaMetadata

class YingLiPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var application: YingLiApplication
    private lateinit var serviceScope: CoroutineScope
    private lateinit var writeScope: CoroutineScope
    private val progressPolicy = PlaybackProgressWritePolicy()
    private var periodicProgressJob: Job? = null
    private var historyRecordedMediaId: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPeriodicProgress() else {
                stopPeriodicProgress()
                persistProgress(ProgressWriteReason.PAUSED)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> persistProgress(ProgressWriteReason.ENDED)
                Player.STATE_IDLE -> persistProgress(ProgressWriteReason.STOPPED)
                Player.STATE_BUFFERING,
                Player.STATE_READY,
                -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            progressPolicy.reset()
            historyRecordedMediaId = null
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        application = getApplication() as YingLiApplication
        serviceScope = CoroutineScope(SupervisorJob() + application.container.dispatchers.main)
        writeScope = CoroutineScope(SupervisorJob() + application.container.dispatchers.io)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            VaultAwareDataSource.Factory(this, application.mediaContainer.securePlaybackSource),
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            it.setAudioAttributes(AudioAttributes.DEFAULT, true)
            it.setHandleAudioBecomingNoisy(true)
            it.addListener(listener)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress(ProgressWriteReason.BACKGROUNDED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopPeriodicProgress()
        val finalWrite = persistProgress(ProgressWriteReason.STOPPED)
        mediaSession.release()
        player.removeListener(listener)
        player.release()
        serviceScope.cancel()
        if (finalWrite == null) {
            writeScope.cancel()
        } else {
            finalWrite.invokeOnCompletion { writeScope.cancel() }
        }
        super.onDestroy()
    }

    private fun startPeriodicProgress() {
        if (periodicProgressJob?.isActive == true) return
        periodicProgressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MILLIS)
                persistProgress(ProgressWriteReason.PERIODIC)
            }
        }
    }

    private fun stopPeriodicProgress() {
        periodicProgressJob?.cancel()
        periodicProgressJob = null
    }

    private fun persistProgress(reason: ProgressWriteReason): Job? {
        if (!::player.isInitialized) return null
        val mediaItem = player.currentMediaItem ?: return null
        val extras = mediaItem.mediaMetadata.extras
        val incognito = extras?.getBoolean(PlaybackMediaMetadata.INCOGNITO, false) == true
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        recordHistoryIfEligible(mediaItem, currentPosition, incognito)
        val decision = progressPolicy.evaluate(
            PlaybackProgressSample(
                positionMillis = currentPosition,
                durationMillis = player.duration.takeUnless { it == C.TIME_UNSET },
                incognito = incognito,
            ),
            reason,
            application.container.clock.now().toEpochMilli(),
        )
        if (decision !is ProgressWriteDecision.Write) return null
        val mediaId = runCatching { MediaItemId(mediaItem.mediaId) }.getOrNull() ?: return null
        return writeScope.launch {
            try {
                application.mediaContainer.playbackProgressRepository.saveProgress(
                    mediaId,
                    decision.positionMillis,
                    decision.completed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                application.container.logger.log(
                    AppLogLevel.WARNING,
                    AppLogEvent(
                        code = "PLAYBACK_PROGRESS_WRITE_FAILED",
                        message = "Playback progress could not be persisted.",
                        attributes = mapOf("reason" to LogValue.Public(reason.name)),
                    ),
                )
            }
        }
    }

    private fun recordHistoryIfEligible(mediaItem: MediaItem, positionMillis: Long, incognito: Boolean) {
        if (incognito || positionMillis < MINIMUM_HISTORY_POSITION_MILLIS || historyRecordedMediaId == mediaItem.mediaId) return
        val mediaId = runCatching { MediaItemId(mediaItem.mediaId) }.getOrNull() ?: return
        historyRecordedMediaId = mediaItem.mediaId
        writeScope.launch {
            try {
                application.mediaContainer.historyRepository.recordPlayback(
                    mediaId,
                    positionMillis,
                    application.container.clock.now().toEpochMilli(),
                    incognito = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                application.container.logger.log(
                    AppLogLevel.WARNING,
                    AppLogEvent(
                        code = "PLAYBACK_HISTORY_WRITE_FAILED",
                        message = "Playback history could not be persisted.",
                    ),
                )
            }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 5_000L
        const val MINIMUM_HISTORY_POSITION_MILLIS = 10_000L
    }
}
