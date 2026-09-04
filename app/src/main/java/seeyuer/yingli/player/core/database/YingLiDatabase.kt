package seeyuer.yingli.player.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        MediaSourceEntity::class,
        MediaItemEntity::class,
        MediaLocationEntity::class,
        MediaItemLocationEntity::class,
        MediaTagEntity::class,
        TrashEntryEntity::class,
        TagDefinitionEntity::class,
        MediaTagRefEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        PlaybackHistoryEntity::class,
        RecentlyOrganizedEntity::class,
        ProcessingProjectEntity::class,
        ProcessingProjectInputEntity::class,
        ProcessingTaskEntity::class,
        ProcessingTaskEventEntity::class,
        ClipProjectEntity::class,
        ClipSegmentEntity::class,
        DuplicateFingerprintEntity::class,
        DuplicateGroupEntity::class,
        DuplicateGroupMemberEntity::class,
        VaultItemEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class YingLiDatabase : RoomDatabase() {
    abstract fun mediaSourceDao(): MediaSourceDao
    abstract fun mediaCatalogDao(): MediaCatalogDao
    abstract fun libraryDao(): LibraryDao
    abstract fun organizeDao(): OrganizeDao
    abstract fun backupDao(): BackupDao
    abstract fun processingDao(): ProcessingDao
    abstract fun clipDao(): ClipDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun vaultDao(): VaultDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                statements.forEach(connection::execSQL)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                processingStatements.forEach(connection::execSQL)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                clipStatements.forEach(connection::execSQL)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                duplicateStatements.forEach(connection::execSQL)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                vaultStatements.forEach(connection::execSQL)
            }
        }

        private val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `trash_entries` (`mediaItemId` TEXT NOT NULL, `locationId` TEXT NOT NULL, `originalUri` TEXT NOT NULL, `trashedUri` TEXT, `deletedAtEpochMillis` INTEGER NOT NULL, `purgeAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`mediaItemId`))",
            "CREATE INDEX IF NOT EXISTS `index_trash_entries_locationId` ON `trash_entries` (`locationId`)",
            "CREATE INDEX IF NOT EXISTS `index_trash_entries_purgeAtEpochMillis` ON `trash_entries` (`purgeAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `tag_definitions` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_definitions_name` ON `tag_definitions` (`name`)",
            "CREATE TABLE IF NOT EXISTS `media_tag_refs` (`mediaItemId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`mediaItemId`, `tagId`), FOREIGN KEY(`tagId`) REFERENCES `tag_definitions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_media_tag_refs_mediaItemId` ON `media_tag_refs` (`mediaItemId`)",
            "CREATE INDEX IF NOT EXISTS `index_media_tag_refs_tagId` ON `media_tag_refs` (`tagId`)",
            "CREATE TABLE IF NOT EXISTS `favorites` (`mediaItemId` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`mediaItemId`))",
            "CREATE INDEX IF NOT EXISTS `index_favorites_createdAtEpochMillis` ON `favorites` (`createdAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)",
            "CREATE TABLE IF NOT EXISTS `playlist_items` (`playlistId` TEXT NOT NULL, `mediaItemId` TEXT NOT NULL, `position` INTEGER NOT NULL, `addedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `mediaItemId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` ON `playlist_items` (`playlistId`)",
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_mediaItemId` ON `playlist_items` (`mediaItemId`)",
            "CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `serializedFilter` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_name` ON `collections` (`name`)",
            "CREATE TABLE IF NOT EXISTS `collection_items` (`collectionId` TEXT NOT NULL, `mediaItemId` TEXT NOT NULL, `addedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `mediaItemId`), FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items` (`collectionId`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_items_mediaItemId` ON `collection_items` (`mediaItemId`)",
            "CREATE TABLE IF NOT EXISTS `playback_history` (`mediaItemId` TEXT NOT NULL, `playCount` INTEGER NOT NULL, `lastPlayedAtEpochMillis` INTEGER NOT NULL, `lastPositionMillis` INTEGER NOT NULL, PRIMARY KEY(`mediaItemId`))",
            "CREATE INDEX IF NOT EXISTS `index_playback_history_lastPlayedAtEpochMillis` ON `playback_history` (`lastPlayedAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `recently_organized` (`mediaItemId` TEXT NOT NULL, `organizedAtEpochMillis` INTEGER NOT NULL, `action` TEXT NOT NULL, PRIMARY KEY(`mediaItemId`))",
            "CREATE INDEX IF NOT EXISTS `index_recently_organized_organizedAtEpochMillis` ON `recently_organized` (`organizedAtEpochMillis`)",
        )

        private val processingStatements = listOf(
            "CREATE TABLE IF NOT EXISTS `processing_projects` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `outputPolicy` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_processing_projects_createdAtEpochMillis` ON `processing_projects` (`createdAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `processing_project_inputs` (`projectId` TEXT NOT NULL, `mediaItemId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`projectId`, `mediaItemId`), FOREIGN KEY(`projectId`) REFERENCES `processing_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_processing_project_inputs_projectId` ON `processing_project_inputs` (`projectId`)",
            "CREATE INDEX IF NOT EXISTS `index_processing_project_inputs_mediaItemId` ON `processing_project_inputs` (`mediaItemId`)",
            "CREATE TABLE IF NOT EXISTS `processing_tasks` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `state` TEXT NOT NULL, `stage` TEXT, `processedUnits` INTEGER, `totalUnits` INTEGER, `unitsPerSecond` REAL, `estimatedRemainingMillis` INTEGER, `priority` INTEGER NOT NULL, `attempt` INTEGER NOT NULL, `errorCode` TEXT, `outputDisplayName` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `processing_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_processing_tasks_projectId` ON `processing_tasks` (`projectId`)",
            "CREATE INDEX IF NOT EXISTS `index_processing_tasks_state` ON `processing_tasks` (`state`)",
            "CREATE INDEX IF NOT EXISTS `index_processing_tasks_updatedAtEpochMillis` ON `processing_tasks` (`updatedAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `processing_task_events` (`taskId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `eventType` TEXT NOT NULL, `payload` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `sequence`), FOREIGN KEY(`taskId`) REFERENCES `processing_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_processing_task_events_taskId` ON `processing_task_events` (`taskId`)",
            "CREATE INDEX IF NOT EXISTS `index_processing_task_events_createdAtEpochMillis` ON `processing_task_events` (`createdAtEpochMillis`)",
        )

        private val clipStatements = listOf(
            "ALTER TABLE `processing_tasks` ADD COLUMN `operationKey` TEXT NOT NULL DEFAULT 'legacy'",
            "ALTER TABLE `processing_tasks` ADD COLUMN `outputToken` TEXT",
            "CREATE TABLE IF NOT EXISTS `clip_projects` (`id` TEXT NOT NULL, `sourceMediaId` TEXT NOT NULL, `sourceLocationId` TEXT NOT NULL, `sourceDurationMillis` INTEGER NOT NULL, `exportMode` TEXT NOT NULL, `preset` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_clip_projects_sourceMediaId` ON `clip_projects` (`sourceMediaId`)",
            "CREATE INDEX IF NOT EXISTS `index_clip_projects_updatedAtEpochMillis` ON `clip_projects` (`updatedAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `clip_segments` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, `name` TEXT NOT NULL, `selected` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `clip_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_clip_segments_projectId` ON `clip_segments` (`projectId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_clip_segments_projectId_position` ON `clip_segments` (`projectId`, `position`)",
        )

        private val duplicateStatements = listOf(
            "CREATE TABLE IF NOT EXISTS `duplicate_fingerprints` (`mediaItemId` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `quickHash` TEXT, `fullHash` TEXT, `durationMillis` INTEGER, `width` INTEGER, `height` INTEGER, `perceptualHashes` TEXT NOT NULL, `algorithmVersion` INTEGER NOT NULL, `sourceModifiedEpochMillis` INTEGER NOT NULL, `generatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`mediaItemId`))",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_fingerprints_sizeBytes` ON `duplicate_fingerprints` (`sizeBytes`)",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_fingerprints_fullHash` ON `duplicate_fingerprints` (`fullHash`)",
            "CREATE TABLE IF NOT EXISTS `duplicate_groups` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `sizeBytes` INTEGER, `fullHash` TEXT, `visualScore` REAL, `durationScore` REAL, `dimensionScore` REAL, `overallScore` REAL, `algorithmVersion` INTEGER NOT NULL, `generatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_groups_mode` ON `duplicate_groups` (`mode`)",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_groups_generatedAtEpochMillis` ON `duplicate_groups` (`generatedAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `duplicate_group_members` (`groupId` TEXT NOT NULL, `mediaItemId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `mediaItemId`), FOREIGN KEY(`groupId`) REFERENCES `duplicate_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_group_members_groupId` ON `duplicate_group_members` (`groupId`)",
            "CREATE INDEX IF NOT EXISTS `index_duplicate_group_members_mediaItemId` ON `duplicate_group_members` (`mediaItemId`)",
        )

        private val vaultStatements = listOf(
            "CREATE TABLE IF NOT EXISTS `vault_items` (`id` TEXT NOT NULL, `encryptedContentToken` TEXT NOT NULL, `encryptedMetadataToken` TEXT NOT NULL, `encryptedBytes` INTEGER NOT NULL, `keyVersion` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_vault_items_createdAtEpochMillis` ON `vault_items` (`createdAtEpochMillis`)",
        )
    }
}
