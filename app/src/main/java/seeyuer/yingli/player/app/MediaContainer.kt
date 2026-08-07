package seeyuer.yingli.player.app

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import seeyuer.yingli.player.core.database.RoomMediaCatalogRepository
import seeyuer.yingli.player.core.database.RoomMediaSourceRepository
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.datastore.DataStoreMediaOnboardingRepository
import seeyuer.yingli.player.core.datastore.MediaOnboardingRepository
import seeyuer.yingli.player.core.foundation.AppContainer
import seeyuer.yingli.player.core.media.*
import seeyuer.yingli.player.domain.media.DefaultMediaIdentityResolver
import seeyuer.yingli.player.domain.media.DefaultMediaScanner
import seeyuer.yingli.player.domain.media.MediaScanner

data class MediaContainer(
    val sourceRepository: MediaSourceRepository,
    val catalogRepository: MediaCatalogRepository,
    val permissionGateway: MediaPermissionGateway,
    val scanner: MediaScanner,
    val thumbnailRepository: ThumbnailRepository,
    val onboardingRepository: MediaOnboardingRepository,
)

object ProductionMediaContainerFactory {
    fun create(context: Context, foundation: AppContainer): MediaContainer {
        val database = Room.databaseBuilder(context, YingLiDatabase::class.java, "yingli-media.db").build()
        val sourceRepository = RoomMediaSourceRepository(database.mediaSourceDao())
        val catalogRepository = RoomMediaCatalogRepository(database)
        val permissionGateway = AndroidMediaPermissionGateway(context, foundation.dispatchers)
        val dataSources = setOf(
            MediaStoreDiscoveryDataSource(context, foundation.dispatchers),
            SafTreeDiscoveryDataSource(context, foundation.dispatchers),
            AllFilesDiscoveryDataSource(foundation.dispatchers),
        )
        val scanner = DefaultMediaScanner(
            dataSources,
            sourceRepository,
            catalogRepository,
            DefaultMediaIdentityResolver,
            AndroidMediaContentHasher(context, foundation.dispatchers),
            foundation.idGenerator,
            foundation.clock,
        )
        val thumbnailScope = CoroutineScope(SupervisorJob() + foundation.dispatchers.io)
        return MediaContainer(
            sourceRepository,
            catalogRepository,
            permissionGateway,
            scanner,
            PriorityThumbnailRepository(thumbnailScope, CoilThumbnailExtractor(context)),
            DataStoreMediaOnboardingRepository(context),
        )
    }
}
