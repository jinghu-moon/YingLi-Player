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
            frameCalibrationControl = mediaContainer.frameCalibrationControl,
        )
    }

    override fun onTerminate() {
        // 容器持有的是跨宿主单例（校准作用域、处理调度器），只能由容器自己按顺序收尾。
        mediaContainer.shutdown()
        playbackController.close()
        playbackSessionClient.close()
        super.onTerminate()
    }
}
