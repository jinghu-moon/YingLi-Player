package seeyuer.yingli.player.domain.duplicates

import java.util.Locale

/**
 * 「保留哪一份」的推荐排序（设计稿 §7.3）。
 *
 * EXACT 组内 `sizeBytes` / 时长 / 分辨率 / mime 必然相同，因此可用准则只剩
 * 与「这份字节的可信度与新鲜度」有关的那些：
 *
 * 1. 扫描中仍存在（`missingScanCount == 0`）优先于已缺失的位置；
 * 2. 最近见到（`lastSeenEpochMillis`）更新者优先；
 * 3. `modifiedEpochMillis` 更新者优先；
 * 4. 来源优先级 `SAF_TREE > MEDIA_STORE > ALL_FILES`；
 * 5. 路径不在隐藏目录 / `.nomedia` 目录内者优先；
 * 6. 文件名不含 `copy` / `副本` / `(1)` 者优先。
 *
 * 这只是**推荐**：UI 用它标注 `BEST`，默认不勾选任何删除项（阶段 6）。
 * 排序全序且稳定（最后以 `locationId` 收尾），因此同一输入永远给出同一结果。
 */
object DuplicateKeepRanking {
    private val COPY_SUFFIX = Regex("\\((\\d+)\\)")

    fun rank(candidates: List<DuplicateCandidate>): List<DuplicateCandidate> =
        candidates.sortedWith(
            compareByDescending<DuplicateCandidate> { it.missingScanCount == 0 }
                .thenByDescending { it.lastSeenEpochMillis }
                .thenByDescending { it.modifiedEpochMillis }
                .thenByDescending { it.sourceMode.ordinal }
                .thenByDescending { !it.isInHiddenDirectory() }
                .thenByDescending { !it.looksLikeACopy() }
                .thenBy { it.locationId.value },
        )

    /** 组分最高的候选；空输入返回 null。 */
    fun best(candidates: List<DuplicateCandidate>): DuplicateCandidate? = rank(candidates).firstOrNull()

    private fun DuplicateCandidate.isInHiddenDirectory(): Boolean {
        val path = relativePath ?: return false
        return path.split('/', '\\').any { segment -> segment.startsWith(".") }
    }

    private fun DuplicateCandidate.looksLikeACopy(): Boolean {
        val name = fileName.lowercase(Locale.ROOT)
        return name.contains("copy") || name.contains("副本") || COPY_SUFFIX.containsMatchIn(name)
    }
}
