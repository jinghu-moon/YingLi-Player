package seeyuer.yingli.player.app

import android.app.Application
import android.content.ComponentName
import seeyuer.yingli.player.app.playback.YingLiPlaybackService
import seeyuer.yingli.player.engine.media3.Media3PlaybackController

class YingLiApplication : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var mediaContainer: MediaContainer
        private set
    lateinit var playbackController: Media3PlaybackController
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
    }

    override fun onTerminate() {
        mediaContainer.processingLifecycle.close()
        playbackController.close()
        super.onTerminate()
    }
}
