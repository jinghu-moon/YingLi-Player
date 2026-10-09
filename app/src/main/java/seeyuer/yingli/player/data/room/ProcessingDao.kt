package seeyuer.yingli.player.data.room

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

    /**
     * 某个媒体条目当前被哪些**非终态**操作占用（回收站的互斥判定，§11.4）。
     *
     * 粒度说明：`processing_project_inputs` 只到 `mediaItemId`，没有位置维度，
     * 所以守卫的粒度比「同一位置的处置」更粗——多位置媒体只要**任一**位置在任务里，
     * 整条都被视为占用。这是**偏保守**的方向（宁可多拦一次），而且与 D5-b 的意图一致：
     * 压缩/转码/切片期间不允许动同一个媒体条目。
     */
    @Query(
        """
        SELECT DISTINCT projects.type
        FROM processing_projects AS projects
        INNER JOIN processing_project_inputs AS inputs ON inputs.projectId = projects.id
        INNER JOIN processing_tasks AS tasks ON tasks.projectId = projects.id
        WHERE inputs.mediaItemId = :mediaItemId
          AND tasks.state IN ('QUEUED', 'PREPARING', 'RUNNING', 'CANCELING')
        """,
    )
    suspend fun activeProjectTypes(mediaItemId: String): List<String>
}
