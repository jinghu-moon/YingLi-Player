package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT * FROM clip_projects ORDER BY updatedAtEpochMillis DESC")
    fun observeProjects(): Flow<List<ClipProjectEntity>>

    @Query("SELECT * FROM clip_segments ORDER BY projectId, position")
    fun observeSegments(): Flow<List<ClipSegmentEntity>>

    @Query("SELECT * FROM clip_projects WHERE id = :projectId")
    suspend fun project(projectId: String): ClipProjectEntity?

    @Query("SELECT * FROM clip_segments WHERE projectId = :projectId ORDER BY position")
    suspend fun segments(projectId: String): List<ClipSegmentEntity>

    @Upsert
    suspend fun upsertProject(project: ClipProjectEntity)

    @Query("DELETE FROM clip_segments WHERE projectId = :projectId")
    suspend fun deleteSegments(projectId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(segments: List<ClipSegmentEntity>)

    @Query("DELETE FROM clip_projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)
}
