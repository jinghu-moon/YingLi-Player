package seeyuer.yingli.player.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingDao {
    @Query("SELECT * FROM processing_tasks ORDER BY state, priority DESC, createdAtEpochMillis")
    fun observeTasks(): Flow<List<ProcessingTaskEntity>>

    @Query("SELECT * FROM processing_tasks WHERE id = :taskId")
    suspend fun task(taskId: String): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_tasks WHERE state IN ('PREPARING', 'RUNNING', 'CANCELING')")
    suspend fun interruptedTasks(): List<ProcessingTaskEntity>

    @Query("SELECT * FROM processing_projects ORDER BY createdAtEpochMillis DESC")
    suspend fun projects(): List<ProcessingProjectEntity>

    @Query("SELECT * FROM processing_project_inputs ORDER BY projectId, position")
    suspend fun projectInputs(): List<ProcessingProjectInputEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProcessingProjectEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjectInputs(inputs: List<ProcessingProjectInputEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTasks(tasks: List<ProcessingTaskEntity>)

    @Upsert
    suspend fun upsertTask(task: ProcessingTaskEntity)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM processing_task_events WHERE taskId = :taskId")
    suspend fun nextEventSequence(taskId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: ProcessingTaskEventEntity)

    @Query("DELETE FROM processing_tasks WHERE state IN ('SUCCEEDED', 'FAILED', 'CANCELED')")
    suspend fun clearTerminalTasks()
}
