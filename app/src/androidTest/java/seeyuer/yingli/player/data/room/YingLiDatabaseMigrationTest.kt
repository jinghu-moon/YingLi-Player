package seeyuer.yingli.player.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun migrateFromOneToSevenValidatesCompleteUpgradeChain() {
        helper.createDatabase("migration-1-7", 1).close()

        helper.runMigrationsAndValidate(
            "migration-1-7",
            7,
            true,
            YingLiDatabase.MIGRATION_1_2,
            YingLiDatabase.MIGRATION_2_3,
            YingLiDatabase.MIGRATION_3_4,
            YingLiDatabase.MIGRATION_4_5,
            YingLiDatabase.MIGRATION_5_6,
            YingLiDatabase.MIGRATION_6_7,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-1-2"
    }
}
