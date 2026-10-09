package seeyuer.yingli.player.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 数据库 schema 版本。`@Database(version = ...)` 与 `ALL_MIGRATIONS` 的终点都用它，
 * 因此「版本加了、迁移忘了」或「迁移加了、没进 [YingLiDatabase.ALL_MIGRATIONS]」都会
 * 被 `YingLiDatabaseMigrationTest` 当场抓住。
 */
internal const val DATABASE_SCHEMA_VERSION = 11

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
        DuplicateIgnoreEntity::class,
        VaultItemEntity::class,
    ],
    version = DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class YingLiDatabase : RoomDatabase() {
    abstract fun mediaSourceDao(): MediaSourceDao
    abstract fun mediaCatalogDao(): MediaCatalogDao
    abstract fun libraryDao(): LibraryDao
    abstract fun homeDao(): HomeDao
    abstract fun organizeDao(): OrganizeDao
    abstract fun backupDao(): BackupDao
    abstract fun processingDao(): ProcessingDao
    abstract fun clipDao(): ClipDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun duplicateMergeDao(): DuplicateMergeDao
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_media_items_title` ON `media_items` (`title`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_items_completed` ON `media_items` (`completed`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_items_playbackPositionMillis` ON `media_items` (`playbackPositionMillis`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_modifiedEpochMillis` ON `media_locations` (`modifiedEpochMillis`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_durationMillis` ON `media_locations` (`durationMillis`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_width` ON `media_locations` (`width`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_missingScanCount` ON `media_locations` (`missingScanCount`)",
                ).forEach(connection::execSQL)
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `media_locations` ADD COLUMN `relativePath` TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `media_sources` ADD COLUMN `includeNomedia` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 阶段 3：去重模型归并 + 回收站主键改为 `locationId`。
         *
         * 三件事：
         * 1. `media_locations` 增加 `hashAlgorithmVersion` 与两条索引——哈希从此直接落在
         *    位置行上，不再有独立的去重指纹表；
         * 2. **删除** `duplicate_fingerprints` / `duplicate_groups` / `duplicate_group_members`，
         *    新建唯一的 `duplicate_ignores`（重复组不是实体，等价关系由 `GROUP BY` 给出）；
         * 3. `trash_entries` 主键 `mediaItemId` → `locationId`：SQLite 不能改主键，
         *    只能重建表。**已存在的条目按 `INSERT OR IGNORE` 迁移**——迁移只改键，
         *    不改语义，没有理由丢掉用户已回收的记录。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "ALTER TABLE `media_locations` ADD COLUMN `hashAlgorithmVersion` INTEGER",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_sizeBytes` ON `media_locations` (`sizeBytes`)",
                    "CREATE INDEX IF NOT EXISTS `index_media_locations_contentHash_hashAlgorithmVersion` ON `media_locations` (`contentHash`, `hashAlgorithmVersion`)",
                    "DROP TABLE IF EXISTS `duplicate_group_members`",
                    "DROP TABLE IF EXISTS `duplicate_groups`",
                    "DROP TABLE IF EXISTS `duplicate_fingerprints`",
                    "CREATE TABLE IF NOT EXISTS `duplicate_ignores` (`contentHash` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `memberCount` INTEGER NOT NULL, `ignoredAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`contentHash`, `sizeBytes`))",
                    "ALTER TABLE `trash_entries` RENAME TO `trash_entries_legacy`",
                    "CREATE TABLE IF NOT EXISTS `trash_entries` (`mediaItemId` TEXT NOT NULL, `locationId` TEXT NOT NULL, `originalUri` TEXT NOT NULL, `trashedUri` TEXT, `deletedAtEpochMillis` INTEGER NOT NULL, `purgeAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`locationId`))",
                    "INSERT OR IGNORE INTO `trash_entries` (`mediaItemId`, `locationId`, `originalUri`, `trashedUri`, `deletedAtEpochMillis`, `purgeAtEpochMillis`, `state`) " +
                        "SELECT `mediaItemId`, `locationId`, `originalUri`, `trashedUri`, `deletedAtEpochMillis`, `purgeAtEpochMillis`, `state` FROM `trash_entries_legacy`",
                    "DROP TABLE `trash_entries_legacy`",
                    "CREATE INDEX IF NOT EXISTS `index_trash_entries_mediaItemId` ON `trash_entries` (`mediaItemId`)",
                    "CREATE INDEX IF NOT EXISTS `index_trash_entries_purgeAtEpochMillis` ON `trash_entries` (`purgeAtEpochMillis`)",
                ).forEach(connection::execSQL)
            }
        }

        /**
         * 阶段 4：回收站模型按 §8.2 完整化。
         *
         * `trash_entries` 从「7 列、只有两个时间戳」扩展为「后端 + 7 态状态机 + 源文件快照 +
         * 副本信息 + 期限 + 错误诊断」。SQLite 不能改列集，只能重建表。
         *
         * **旧行按 `RECONCILIATION_REQUIRED` 迁移，而不是丢弃**：
         * - 旧行的 `trashedUri`（R3 的 `.Trash/YingLi/<name>`）**没有任何新字段能表达**，
         *   因此它被原样搬进 `copyRelativePath` 并配上 `lastErrorCode = 'LEGACY_R3_ENTRY'`；
         * - 「不丢唯一副本」是阶段 4 的退出条件：R3 是**移动**语义，那个文件就是唯一副本，
         *   丢掉记录等于让用户无法通过应用找回它；
         * - 该状态**禁止自动删除任何副本**（§8.3），副本路径又不属于应用管理的 `items/`，
         *   所以物理删除会返回 `Blocked("UNMANAGED_COPY")`，只能由用户显式「放弃条目」。
         * - 旧的 `deletedAtEpochMillis` / `purgeAtEpochMillis` 原样映射到
         *   `trashedAtEpochMillis` / `expiresAtEpochMillis`，期限语义不变。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                listOf(
                    "ALTER TABLE `trash_entries` RENAME TO `trash_entries_legacy`",
                    "CREATE TABLE IF NOT EXISTS `trash_entries` (" +
                        "`locationId` TEXT NOT NULL, `mediaItemId` TEXT NOT NULL, `sourceId` TEXT, " +
                        "`backend` TEXT NOT NULL, `state` TEXT NOT NULL, `originalUri` TEXT NOT NULL, " +
                        "`originalVolumeId` TEXT, `originalDocumentId` TEXT, `originalDisplayName` TEXT NOT NULL, " +
                        "`originalRelativePath` TEXT, `originalMimeType` TEXT, `originalSizeBytes` INTEGER NOT NULL, " +
                        "`originalModifiedEpochMillis` INTEGER, `originalDurationMillis` INTEGER, `originalWidth` INTEGER, " +
                        "`originalHeight` INTEGER, `contentHash` TEXT, `hashAlgorithmVersion` INTEGER, " +
                        "`copyRelativePath` TEXT, `copySizeBytes` INTEGER, `copyVerifiedAtEpochMillis` INTEGER, " +
                        "`trashedAtEpochMillis` INTEGER, `expiresAtEpochMillis` INTEGER, `systemExpiresAtEpochMillis` INTEGER, " +
                        "`restoreUri` TEXT, `restoredAtEpochMillis` INTEGER, `lastErrorCode` TEXT, `lastErrorDetail` TEXT, " +
                        "`retryCount` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`locationId`))",
                    "INSERT OR IGNORE INTO `trash_entries` (" +
                        "`locationId`, `mediaItemId`, `sourceId`, `backend`, `state`, `originalUri`, " +
                        "`originalDisplayName`, `originalSizeBytes`, `copyRelativePath`, " +
                        "`trashedAtEpochMillis`, `expiresAtEpochMillis`, `lastErrorCode`, `lastErrorDetail`, " +
                        "`retryCount`, `updatedAtEpochMillis`) " +
                        "SELECT `locationId`, `mediaItemId`, NULL, 'R2_APP_COPY', 'RECONCILIATION_REQUIRED', " +
                        "`originalUri`, '', 0, `trashedUri`, `deletedAtEpochMillis`, `purgeAtEpochMillis`, " +
                        "'LEGACY_R3_ENTRY', '阶段 4 已删除 R3（同名目录移动）；该文件仍在旧路径（见 copyRelativePath），" +
                        "需要用户决定保留还是恢复', 0, `deletedAtEpochMillis` FROM `trash_entries_legacy`",
                    "DROP TABLE `trash_entries_legacy`",
                    "CREATE INDEX IF NOT EXISTS `index_trash_entries_mediaItemId` ON `trash_entries` (`mediaItemId`)",
                    "CREATE INDEX IF NOT EXISTS `index_trash_entries_state` ON `trash_entries` (`state`)",
                    "CREATE INDEX IF NOT EXISTS `index_trash_entries_contentHash` ON `trash_entries` (`contentHash`)",
                ).forEach(connection::execSQL)
            }
        }

        /**
         * 全部迁移，按版本顺序排列。
         *
         * 生产的 `Room.databaseBuilder` 与迁移测试都从这里取。**不要再手写第二份列表**：
         * 2026-10-09 阶段 3 就是这么错的——`MIGRATION_9_10` 写好了、迁移测试也过了，
         * 但生产 builder 的 `addMigrations(...)` 忘了加它，于是已装 v9 的设备在下次
         * 打开数据库时抛 `IllegalStateException: A migration from 9 to 10 was required
         * but not found`，而所有单测与迁移测试都不会报错。
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
        )

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
