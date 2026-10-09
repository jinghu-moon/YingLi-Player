package seeyuer.yingli.player.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YingLiDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        YingLiDatabase::class.java,
    )

    @Test
    fun migrateFromOneToTwoCreatesLibraryAndOrganizeTables() {
        helper.createDatabase(DATABASE_NAME, 1).close()

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            YingLiDatabase.MIGRATION_1_2,
        ).close()
    }

    @Test
    fun migrateFromTwoToThreeCreatesPersistentProcessingTables() {
        helper.createDatabase("migration-2-3", 2).close()

        helper.runMigrationsAndValidate(
            "migration-2-3",
            3,
            true,
            YingLiDatabase.MIGRATION_2_3,
        ).close()
    }

    @Test
    fun migrateFromThreeToFourCreatesClipProjectsAndOperationMetadata() {
        helper.createDatabase("migration-3-4", 3).close()

        helper.runMigrationsAndValidate(
            "migration-3-4",
            4,
            true,
            YingLiDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun migrateFromFourToFiveCreatesDuplicateEvidenceTables() {
        helper.createDatabase("migration-4-5", 4).close()

        helper.runMigrationsAndValidate(
            "migration-4-5",
            5,
            true,
            YingLiDatabase.MIGRATION_4_5,
        ).close()
    }

    @Test
    fun migrateFromFiveToSixCreatesAnonymousVaultIndex() {
        helper.createDatabase("migration-5-6", 5).close()

        helper.runMigrationsAndValidate(
            "migration-5-6",
            6,
            true,
            YingLiDatabase.MIGRATION_5_6,
        ).close()
    }

    @Test
    fun migrateFromSixToSevenCreatesLibraryQueryIndexes() {
        helper.createDatabase("migration-6-7", 6).close()

        helper.runMigrationsAndValidate(
            "migration-6-7",
            7,
            true,
            YingLiDatabase.MIGRATION_6_7,
        ).close()
    }

    @Test
    fun migrateFromSevenToEightAddsMediaRelativePath() {
        helper.createDatabase("migration-7-8", 7).close()

        helper.runMigrationsAndValidate(
            "migration-7-8",
            8,
            true,
            YingLiDatabase.MIGRATION_7_8,
        ).close()
    }

    @Test
    fun migrateFromEightToNineAddsNomediaSetting() {
        helper.createDatabase("migration-8-9", 8).close()

        helper.runMigrationsAndValidate(
            "migration-8-9",
            9,
            true,
            YingLiDatabase.MIGRATION_8_9,
        ).close()
    }

    @Test
    fun migrateFromNineToTenReshapesDuplicateAndTrashTables() {
        helper.createDatabase("migration-9-10", 9).close()

        helper.runMigrationsAndValidate(
            "migration-9-10",
            10,
            true,
            YingLiDatabase.MIGRATION_9_10,
        ).close()
    }

    @Test
    fun migrateFromNineToTenKeepsExistingTrashEntries() {
        val legacy = helper.createDatabase("migration-9-10-rows", 9)
        legacy.execSQL(
            "INSERT INTO trash_entries(mediaItemId, locationId, originalUri, trashedUri, deletedAtEpochMillis, purgeAtEpochMillis, state) " +
                "VALUES('item-1', 'location-1', 'content://media/1', 'file:///storage/.Trash/1', 10, 20, 'TRASHED')",
        )
        legacy.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-9-10-rows",
            10,
            true,
            YingLiDatabase.MIGRATION_9_10,
        )
        migrated.query("SELECT locationId, mediaItemId, purgeAtEpochMillis FROM trash_entries").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("location-1", cursor.getString(0))
            assertEquals("item-1", cursor.getString(1))
            assertEquals(20L, cursor.getLong(2))
        }
        migrated.close()
    }

    @Test
    fun migrateFromTenToElevenReshapesTrashEntries() {
        helper.createDatabase("migration-10-11", 10).close()

        helper.runMigrationsAndValidate(
            "migration-10-11",
            11,
            true,
            YingLiDatabase.MIGRATION_10_11,
        ).close()
    }

    /**
     * 旧 R3 条目的副本路径指向 `.Trash/YingLi/...`——那个文件就是**唯一副本**，
     * 所以迁移必须保住记录，并且一律落到 `RECONCILIATION_REQUIRED`（禁止自动删除）。
     */
    @Test
    fun migrateFromTenToElevenKeepsLegacyEntriesForReview() {
        val legacy = helper.createDatabase("migration-10-11-rows", 10)
        legacy.execSQL(
            "INSERT INTO trash_entries(mediaItemId, locationId, originalUri, trashedUri, deletedAtEpochMillis, purgeAtEpochMillis, state) " +
                "VALUES('item-1', 'location-1', 'content://media/1', 'file:///storage/emulated/0/.Trash/YingLi/1.mp4', 10, 20, 'TRASHED')",
        )
        legacy.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-10-11-rows",
            11,
            true,
            YingLiDatabase.MIGRATION_10_11,
        )
        migrated.query(
            "SELECT locationId, mediaItemId, backend, state, copyRelativePath, trashedAtEpochMillis, " +
                "expiresAtEpochMillis, lastErrorCode FROM trash_entries",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("location-1", cursor.getString(0))
            assertEquals("item-1", cursor.getString(1))
            assertEquals("R2_APP_COPY", cursor.getString(2))
            assertEquals("RECONCILIATION_REQUIRED", cursor.getString(3))
            assertEquals("file:///storage/emulated/0/.Trash/YingLi/1.mp4", cursor.getString(4))
            assertEquals(10L, cursor.getLong(5))
            assertEquals(20L, cursor.getLong(6))
            assertEquals("LEGACY_R3_ENTRY", cursor.getString(7))
        }
        migrated.close()
    }

    @Test
    fun migrateFromOneToElevenValidatesCompleteUpgradeChain() {
        helper.createDatabase("migration-1-11", 1).close()

        helper.runMigrationsAndValidate(
            "migration-1-11",
            DATABASE_SCHEMA_VERSION,
            true,
            *YingLiDatabase.ALL_MIGRATIONS,
        ).close()
    }

    /**
     * 这条用例防的是「迁移写了但没接上」——2026-10-09 阶段 3 的真实事故：`MIGRATION_9_10`
     * 写好了、逐版本用例也过了，但 `MediaContainer` 的生产 builder 忘了注册它，已装 v9 的
     * 设备升级后直接抛 `A migration from 9 to 10 was required but not found`。
     *
     * 生产 builder 现在只消费 [YingLiDatabase.ALL_MIGRATIONS]，所以这里断言「数组本身
     * 从 1 连续覆盖到 [DATABASE_SCHEMA_VERSION]」就等价于断言生产路径完整。
     */
    @Test
    fun allMigrationsFormAContiguousChainUpToTheDeclaredSchemaVersion() {
        val steps = YingLiDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals(1, steps.first().first)
        steps.zipWithNext().forEach { (previous, next) ->
            assertEquals("迁移链在 $previous 与 $next 之间断开", previous.second, next.first)
        }
        assertEquals(DATABASE_SCHEMA_VERSION, steps.last().second)
    }

    private companion object {
        const val DATABASE_NAME = "migration-1-2"
    }
}
