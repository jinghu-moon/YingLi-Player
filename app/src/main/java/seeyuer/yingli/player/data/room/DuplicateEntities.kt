package seeyuer.yingli.player.data.room

import androidx.room.Entity

/**
 * 被用户「忽略」的重复等价类。
 *
 * 设计稿 §7.1：重复组**不是实体**，等价关系由
 * `GROUP BY contentHash, sizeBytes` 直接给出，因此
 * `duplicate_fingerprints` / `duplicate_groups` / `duplicate_group_members`
 * 三张表已删除，这里只剩「用户不想再看这一组」这一条真正的持久化意图。
 *
 * 主键是 `(contentHash, sizeBytes)`——即等价类的身份本身。
 * [memberCount] 记录忽略时的成员数：**成员数变化时该组重新出现**
 * （修 G26：旧的 `ignore(groupId) = deleteGroup(groupId)` 会在下次扫描被重建，
 * 用户的忽略动作丢失）。
 */
@Entity(tableName = "duplicate_ignores", primaryKeys = ["contentHash", "sizeBytes"])
data class DuplicateIgnoreEntity(
    val contentHash: String,
    val sizeBytes: Long,
    val memberCount: Int,
    val ignoredAtEpochMillis: Long,
)
