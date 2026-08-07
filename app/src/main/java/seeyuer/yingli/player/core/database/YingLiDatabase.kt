package seeyuer.yingli.player.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaSourceEntity::class,
        MediaItemEntity::class,
        MediaLocationEntity::class,
        MediaItemLocationEntity::class,
        MediaTagEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class YingLiDatabase : RoomDatabase() {
    abstract fun mediaSourceDao(): MediaSourceDao
    abstract fun mediaCatalogDao(): MediaCatalogDao
}
