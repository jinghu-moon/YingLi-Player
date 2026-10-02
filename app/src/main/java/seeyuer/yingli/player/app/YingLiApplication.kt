package seeyuer.yingli.player.app

import android.app.Application
import android.content.ComponentName
import seeyuer.yingli.player.app.playback.YingLiPlaybackService
import seeyuer.yingli.player.engine.media3.Media3PlaybackController
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge

class YingLiApplication : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var mediaContainer: MediaContainer
        private set
    lateinit var playbackController: Media3PlaybackController
        private set
    lateinit var playbackSessionClient: PlaybackSessionClientBridge
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainerFactory.create(this)
        mediaContainer = ProductionMediaContainerFactory.create(this, container)
        playbackController = Media3PlaybackController(
            this,
            ComponentName(this, YingLiPlaybackService::class.java),
            mediaContainer.playbackSourceRepository,
            container.dispatchers,
            container.logger,
        )
        playbackSessionClient = PlaybackSessionClientBridge(
            playbackController,
            mediaContainer.playbackSourceRepository,
            container.dispatchers,
            mediaContainer.playbackQueueRepository,
        )
    }

    override fun onTerminate() {
        mediaContainer.processingLifecycle.close()
        playbackController.close()
        playbackSessionClient.close()
        super.onTerminate()
    }
}
