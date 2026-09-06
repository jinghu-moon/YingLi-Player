package seeyuer.yingli.player.data.processing.clips

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import seeyuer.yingli.player.data.room.ClipProjectEntity
import seeyuer.yingli.player.data.room.ClipSegmentEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipPreset
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipProjectRepository
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSegmentId

class RoomClipProjectRepository(
    private val database: YingLiDatabase,
) : ClipProjectRepository {
    private val dao = database.clipDao()

    override val projects: Flow<List<ClipProject>> = combine(
        dao.observeProjects(),
        dao.observeSegments(),
    ) { projects, segments ->
        val grouped = segments.groupBy(ClipSegmentEntity::projectId)
        projects.map { it.toDomain(grouped[it.id].orEmpty()) }
    }

    override suspend fun project(id: ClipProjectId): ClipProject? =
        dao.project(id.value)?.toDomain(dao.segments(id.value))

    override suspend fun save(project: ClipProject) {
        database.withTransaction {
            dao.upsertProject(project.toEntity())
            dao.deleteSegments(project.id.value)
            dao.insertSegments(project.segments.mapIndexed { index, segment -> segment.toEntity(project.id, index) })
        }
    }

    override suspend fun delete(id: ClipProjectId) {
        dao.deleteProject(id.value)
    }

    private fun ClipProject.toEntity() = ClipProjectEntity(
        id.value,
        sourceMediaId.value,
        sourceLocationId.value,
        sourceDurationMillis,
        exportMode.name,
        preset.name,
        createdAtEpochMillis,
        updatedAtEpochMillis,
    )

    private fun ClipSegment.toEntity(projectId: ClipProjectId, position: Int) = ClipSegmentEntity(
        id.value,
        projectId.value,
        startMillis,
        endMillis,
        name,
        selected,
        position,
    )

    private fun ClipProjectEntity.toDomain(segments: List<ClipSegmentEntity>) = ClipProject(
        ClipProjectId(id),
        MediaItemId(sourceMediaId),
        MediaLocationId(sourceLocationId),
        sourceDurationMillis,
        segments.sortedBy(ClipSegmentEntity::position).map { it.toDomain() },
        ClipExportMode.valueOf(exportMode),
        ClipPreset.valueOf(preset),
        createdAtEpochMillis,
        updatedAtEpochMillis,
    )

    private fun ClipSegmentEntity.toDomain() = ClipSegment(
        ClipSegmentId(id),
        startMillis,
        endMillis,
        name,
        selected,
    )
}
