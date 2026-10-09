package seeyuer.yingli.player.data.recycle

import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.data.room.ProcessingDao
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.recycle.MediaOperationGuard

/**
 * 互斥矩阵的数据库实现（§11.4）。
 *
 * 判据是**持久化的任务状态**，不是 UI 的按钮禁用：`InAppProcessingScheduler` 在进程被杀后
 * 会把 `PREPARING/RUNNING → QUEUED`（`recoverInterrupted()`），所以「查不到非终态任务」
 * 与「确实没有任务」是一回事，不需要额外的内存登记表（那会变成第二个真源）。
 */
class RoomMediaOperationGuard(private val dao: ProcessingDao) : MediaOperationGuard {
    override suspend fun activeOperations(
        mediaItemId: MediaItemId,
        locationId: MediaLocationId,
    ): Set<ProcessingProjectType> = dao.activeProjectTypes(mediaItemId.value)
        .mapNotNull { name -> ProcessingProjectType.entries.firstOrNull { it.name == name } }
        .toSet()

    /**
     * §11.4 矩阵的完整规则：
     * - 同类型 = 否（排队，不冲突）；
     * - `DEDUPLICATE` 与 `COMPRESS`/`CONVERT`/`CLIP` = 否（扫描只读哈希）；
     * - `DEDUPLICATE` 与 `RECYCLE` = **是**（一个在扫、一个在移文件，候选集合会失效）；
     * - 其余两两 = 是（都写文件）。
     */
    override fun conflicts(left: ProcessingProjectType, right: ProcessingProjectType): Boolean {
        if (left == right) return false
        val leftIsScan = left == ProcessingProjectType.DEDUPLICATE
        val rightIsScan = right == ProcessingProjectType.DEDUPLICATE
        if (!leftIsScan && !rightIsScan) return true
        return if (leftIsScan) right == ProcessingProjectType.RECYCLE else left == ProcessingProjectType.RECYCLE
    }
}
