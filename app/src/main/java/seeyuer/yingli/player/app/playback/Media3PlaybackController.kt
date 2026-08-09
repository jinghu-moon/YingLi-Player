package seeyuer.yingli.player.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.FileNotFoundException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.foundation.AppLogEvent
import seeyuer.yingli.player.core.foundation.AppLogLevel
import seeyuer.yingli.player.core.foundation.AppLogger
import seeyuer.yingli.player.core.foundation.LogValue
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DefaultPlaybackErrorMapper
import seeyuer.yingli.player.domain.playback.PlaybackAction
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackFailureSignal
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackStateReducer
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.PlaybackTransition

class Media3PlaybackController(
    context: Context,
    private val sourceRepository: PlaybackSourceRepository,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
) : PlaybackController, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val mutableConnectionState = MutableStateFlow(PlaybackConnectionState.CONNECTING)
    override val connectionState: StateFlow<PlaybackConnectionState> = mutableConnectionState.asStateFlow()
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateFromPlayer(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = DefaultPlaybackErrorMapper.map(error.toFailureSignal())
            mutableState.value = PlaybackState.Failed(
                mutableState.value.request,
                mutableState.value.timeline,
                mapped,
            )
            logger.log(
                AppLogLevel.ERROR,
                AppLogEvent(
                    code = mapped.diagnosticCode,
                    message = "Media3 reported a playback failure.",
                    attributes = mapOf("media3Code" to LogValue.Public(error.errorCode.toString())),
                ),
            )
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@Media3PlaybackController.controller === controller) {
                controller.removeListener(playerListener)
                this@Media3PlaybackController.controller = null
                mutableConnectionState.value = PlaybackConnectionState.DISCONNECTED
            }
        }
    }

    private val controllerFuture = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context, YingLiPlaybackService::class.java)),
    ).setListener(controllerListener).buildAsync()

    init {
        controllerFuture.addListener(
            {
                scope.launch {
                    try {
                        val connected = controllerFuture.get()
                        controller = connected
                        connected.addListener(playerListener)
                        mutableConnectionState.value = PlaybackConnectionState.CONNECTED
                        updateFromPlayer(connected)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableConnectionState.value = PlaybackConnectionState.FAILED
                        mutableState.value = PlaybackState.Failed(
                            mutableState.value.request,
                            mutableState.value.timeline,
                            DefaultPlaybackErrorMapper.map(PlaybackFailureSignal.OTHER),
                        )
                    }
                }
            },
            Executor(Runnable::run),
        )
    }

    override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
        val activeController = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        val current = mutableState.value
        if (current.request == request && current !is PlaybackState.Failed && current !is PlaybackState.Ended) {
            return PlaybackCommandResult.AlreadyApplied
        }
        mutableState.value = PlaybackStateReducer.reduce(current, PlaybackTransition.Prepare(request))
        scope.launch(dispatchers.io) {
            val source = try {
                sourceRepository.resolve(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                fail(request, PlaybackFailureSignal.ACCESS_DENIED)
                return@launch
            } catch (_: FileNotFoundException) {
                fail(request, PlaybackFailureSignal.NOT_FOUND)
                return@launch
            } catch (_: Exception) {
                fail(request, PlaybackFailureSignal.OTHER)
                return@launch
            }
            if (source == null) {
                fail(request, PlaybackFailureSignal.NOT_FOUND)
                return@launch
            }
            withContext(dispatchers.main) {
                if (mutableState.value.request != request || controller !== activeController) return@withContext
                val extras = Bundle().apply {
                    putString(PlaybackMediaMetadata.LOCATION_ID, request.locationId.value)
                    putBoolean(PlaybackMediaMetadata.INCOGNITO, request.incognito)
                    putString(PlaybackMediaMetadata.SOURCE_CONTEXT, request.sourceContext.name)
                }
                val mediaItem = MediaItem.Builder()
                    .setMediaId(request.mediaId.value)
                    .setUri(Uri.parse(source.uri))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).setExtras(extras).build())
                    .build()
                activeController.setMediaItem(mediaItem, request.startPositionMillis)
                activeController.prepare()
                activeController.play()
            }
        }
        return PlaybackCommandResult.Accepted
    }

    override fun play(): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (mutableState.value is PlaybackState.Ended) {
            active.seekTo(0)
            active.play()
            return PlaybackCommandResult.Accepted
        }
        return execute(PlaybackAction.PLAY, MediaController::play)
    }

    override fun pause(): PlaybackCommandResult = execute(PlaybackAction.PAUSE, MediaController::pause)

    override fun seekTo(positionMillis: Long): PlaybackCommandResult = execute(PlaybackAction.SEEK) { active ->
        active.seekTo(positionMillis.coerceAtLeast(0))
    }

    override fun stop(): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (mutableState.value == PlaybackState.Idle) return PlaybackCommandResult.AlreadyApplied
        active.stop()
        active.clearMediaItems()
        mutableState.value = PlaybackState.Idle
        return PlaybackCommandResult.Accepted
    }

    override fun retry(): PlaybackCommandResult {
        val failed = mutableState.value as? PlaybackState.Failed
            ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE)
        val request = failed.request
            ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.SOURCE_UNAVAILABLE)
        mutableState.value = PlaybackState.Idle
        return prepare(request)
    }

    fun connectedPlayer(): Player? = controller

    override fun close() {
        controller?.removeListener(playerListener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
    }

    private fun execute(action: PlaybackAction, command: (MediaController) -> Unit): PlaybackCommandResult {
        val active = controller ?: return PlaybackCommandResult.Rejected(PlaybackCommandRejection.NOT_CONNECTED)
        if (action !in mutableState.value.availableActions) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.INVALID_STATE)
        }
        command(active)
        return PlaybackCommandResult.Accepted
    }

    private suspend fun fail(request: PlaybackRequest, signal: PlaybackFailureSignal) {
        withContext(dispatchers.main) {
            if (mutableState.value.request == request) {
                mutableState.value = PlaybackState.Failed(
                    request,
                    mutableState.value.timeline,
                    DefaultPlaybackErrorMapper.map(signal),
                )
            }
        }
    }

    private fun updateFromPlayer(player: Player) {
        val request = mutableState.value.request ?: requestFrom(player.currentMediaItem, player.currentPosition) ?: return
        val timeline = PlaybackTimeline(
            positionMillis = player.currentPosition.coerceAtLeast(0),
            durationMillis = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 },
            bufferedPositionMillis = player.bufferedPosition.coerceAtLeast(0),
        )
        mutableState.value = when (player.playbackState) {
            Player.STATE_IDLE -> mutableState.value
            Player.STATE_BUFFERING -> PlaybackState.Preparing(request, timeline)
            Player.STATE_READY -> when {
                player.isPlaying -> PlaybackState.Playing(request, timeline)
                mutableState.value is PlaybackState.Paused -> PlaybackState.Paused(request, timeline)
                else -> PlaybackState.Ready(request, timeline)
            }
            Player.STATE_ENDED -> PlaybackState.Ended(request, timeline)
            else -> mutableState.value
        }
    }

    private fun requestFrom(mediaItem: MediaItem?, positionMillis: Long): PlaybackRequest? {
        mediaItem ?: return null
        val extras = mediaItem.mediaMetadata.extras ?: return null
        val locationId = extras.getString(PlaybackMediaMetadata.LOCATION_ID) ?: return null
        val sourceContext = extras.getString(PlaybackMediaMetadata.SOURCE_CONTEXT)
            ?.let { value -> PlaybackSourceContext.entries.firstOrNull { it.name == value } }
            ?: PlaybackSourceContext.HOME
        return runCatching {
            PlaybackRequest(
                MediaItemId(mediaItem.mediaId),
                MediaLocationId(locationId),
                positionMillis.coerceAtLeast(0),
                sourceContext,
                extras.getBoolean(PlaybackMediaMetadata.INCOGNITO, false),
            )
        }.getOrNull()
    }

    private fun PlaybackException.toFailureSignal(): PlaybackFailureSignal = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailureSignal.ACCESS_DENIED
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailureSignal.NOT_FOUND
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackFailureSignal.CONTAINER_UNSUPPORTED
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackFailureSignal.DECODER_UNAVAILABLE
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> PlaybackFailureSignal.MALFORMED_MEDIA
        else -> PlaybackFailureSignal.OTHER
    }
}
