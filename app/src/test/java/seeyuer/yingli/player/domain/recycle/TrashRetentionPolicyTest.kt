package seeyuer.yingli.player.domain.recycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri

/**
 * 保留期的两个独立口径（§8.7）：
 * **「恢复资格严格」**是纯数据库判定，物理清理有没有跑都不影响；**「物理删除尽快」**是另一回事。
 */
class TrashRetentionPolicyTest {
    @Test
    fun `entry expires exactly at configured retention boundary`() {
        val policy = TrashRetentionPolicy(30)
        val trashedAt = 1_000L
        val expiresAt = policy.expiresAt(trashedAt)
        val entry = entry(backend = TrashBackend.R2_APP_COPY, trashedAt = trashedAt, expiresAt = expiresAt)

        assertTrue(entry.canRestore(expiresAt - 1))
        assertFalse(entry.canRestore(expiresAt))
        assertFalse(entry.isExpired(expiresAt - 1))
        assertTrue(entry.isExpired(expiresAt))
    }

    @Test
    fun `an app copy cannot exist without its expiry`() {
        // 期限与移入时刻必须同生共死：只写了 trashedAt 说明有人绕过了「第 10 步才进入 ACTIVE」，
        // 构造时就该炸，而不是等到 UI 显示出一个能恢复但没有到期日的条目。
        assertThrows(IllegalArgumentException::class.java) {
            entry(backend = TrashBackend.R2_APP_COPY, trashedAt = 1_000L, expiresAt = null)
        }
    }

    @Test
    fun `an app copy that never reached active cannot be restored`() {
        val entry = entry(backend = TrashBackend.R2_APP_COPY, trashedAt = null, expiresAt = null)

        assertFalse(entry.canRestore(2_000L))
        assertNull(entry.remainingDays(2_000L))
    }

    @Test
    fun `a system entry with an unknown expiry stays restorable`() {
        // R1 的 DATE_EXPIRES 读不到时**不拒绝**：只有数据来源能可靠提供时才展示（§8.1）。
        val entry = entry(backend = TrashBackend.R1_SYSTEM, trashedAt = null, expiresAt = null)

        assertTrue(entry.canRestore(2_000L))
        assertNull(entry.remainingDays(2_000L))
    }

    @Test
    fun `a system entry expires by its own snapshot`() {
        val entry = entry(backend = TrashBackend.R1_SYSTEM, trashedAt = null, expiresAt = null)
            .copy(systemExpiresAtEpochMillis = 5_000L)

        assertTrue(entry.canRestore(4_999L))
        assertFalse(entry.canRestore(5_000L))
        assertTrue(entry.isExpired(5_000L))
    }

    @Test
    fun `remaining days rounds up so a partial day is never shown as expired`() {
        val policy = TrashRetentionPolicy(30)
        val trashedAt = 0L
        val entry = entry(backend = TrashBackend.R2_APP_COPY, trashedAt = trashedAt, expiresAt = policy.expiresAt(trashedAt))

        assertEquals(30L, entry.remainingDays(0L))
        assertEquals(1L, entry.remainingDays(policy.expiresAt(0L) - 1))
        assertEquals(0L, entry.remainingDays(policy.expiresAt(0L)))
    }

    private fun entry(
        backend: TrashBackend,
        trashedAt: Long?,
        expiresAt: Long?,
    ) = TrashEntry(
        locationId = MediaLocationId("location_1"),
        mediaItemId = MediaItemId("media_1"),
        backend = backend,
        state = TrashState.ACTIVE,
        originalUri = MediaUri("content://media/video/1"),
        originalDisplayName = "movie.mp4",
        originalSizeBytes = 100,
        updatedAtEpochMillis = 0,
        trashedAtEpochMillis = trashedAt,
        expiresAtEpochMillis = expiresAt,
    )
}
