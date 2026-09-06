package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DuplicateDao {
    @Query("SELECT * FROM duplicate_groups ORDER BY mode, generatedAtEpochMillis DESC")
    fun observeGroups(): Flow<List<DuplicateGroupEntity>>

    @Query("SELECT * FROM duplicate_group_members WHERE groupId = :groupId ORDER BY position")
    suspend fun members(groupId: String): List<DuplicateGroupMemberEntity>

    @Query("SELECT * FROM duplicate_fingerprints WHERE mediaItemId = :mediaItemId")
    suspend fun fingerprint(mediaItemId: String): DuplicateFingerprintEntity?

    @Query("SELECT * FROM duplicate_fingerprints WHERE mediaItemId IN (:mediaItemIds)")
    suspend fun fingerprints(mediaItemIds: List<String>): List<DuplicateFingerprintEntity>

    @Upsert
    suspend fun upsertFingerprints(fingerprints: List<DuplicateFingerprintEntity>)

    @Query("DELETE FROM duplicate_groups WHERE mode = :mode")
    suspend fun deleteGroups(mode: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroups(groups: List<DuplicateGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembers(members: List<DuplicateGroupMemberEntity>)

    @Query("DELETE FROM duplicate_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)
}
