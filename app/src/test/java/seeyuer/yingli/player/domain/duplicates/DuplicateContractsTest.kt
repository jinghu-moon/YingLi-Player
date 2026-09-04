package seeyuer.yingli.player.domain.duplicates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

class DuplicateContractsTest {
    @Test
    fun `exact evidence requires full hash and does not trust size or quick hash`() {
        val collision = listOf(
            fingerprint("a", fullHash = hash('1')),
            fingerprint("b", fullHash = hash('2')),
        )
        assertTrue(DuplicateEvidenceFactory.exact(collision, 10).isEmpty())

        val exact = collision.map { it.copy(fullHash = hash('a')) }
        assertEquals(1, DuplicateEvidenceFactory.exact(exact, 10).size)
    }

    @Test
    fun `similar evidence rejects feature version mismatch`() {
        assertNull(DuplicateEvidenceFactory.similarity(
            fingerprint("a", version = 1, perceptual = listOf(1L)),
            fingerprint("b", version = 2, perceptual = listOf(1L)),
            1,
        ))
    }

    @Test
    fun `deletion plan requires explicit keep and trash selections`() {
        assertThrows(IllegalArgumentException::class.java) {
            DuplicateDeletionPlan(DuplicateGroupId("g"), emptySet(), setOf(MediaItemId("a")), 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DuplicateDeletionPlan(DuplicateGroupId("g"), setOf(MediaItemId("a")), emptySet(), 1, 1)
        }
    }

    @Test
    fun `deletion validation fails closed when file changes`() {
        val first = fingerprint("a", fullHash = hash('a'))
        val second = fingerprint("b", fullHash = hash('a'))
        val group = DuplicateGroup(
            DuplicateGroupId("g"),
            DuplicateMode.EXACT,
            listOf(DuplicateCandidate(first.mediaId, first), DuplicateCandidate(second.mediaId, second)),
            DuplicateEvidence.Exact(first.sizeBytes, hash('a'), 1, 1),
        )
        val plan = DuplicateDeletionPlan(
            group.id, setOf(first.mediaId), setOf(second.mediaId), 1, 2,
        )
        val changed = second.copy(sourceModifiedEpochMillis = 99)

        assertEquals(
            DuplicatePlanValidation.Invalid("FILE_CHANGED"),
            DuplicateDeletionValidator.validate(group, plan, mapOf(first.mediaId to first, second.mediaId to changed)),
        )
    }

    @Test
    fun `deletion validation rejects size change even when metadata and hash are unchanged`() {
        val first = fingerprint("a", fullHash = hash('a'))
        val second = fingerprint("b", fullHash = hash('a'))
        val group = DuplicateGroup(
            DuplicateGroupId("g"),
            DuplicateMode.EXACT,
            listOf(DuplicateCandidate(first.mediaId, first), DuplicateCandidate(second.mediaId, second)),
            DuplicateEvidence.Exact(first.sizeBytes, hash('a'), 1, 1),
        )
        val plan = DuplicateDeletionPlan(group.id, setOf(first.mediaId), setOf(second.mediaId), 1, 2)

        assertEquals(
            DuplicatePlanValidation.Invalid("FILE_CHANGED"),
            DuplicateDeletionValidator.validate(
                group,
                plan,
                mapOf(first.mediaId to first, second.mediaId to second.copy(sizeBytes = second.sizeBytes + 1)),
            ),
        )
    }

    @Test
    fun `similar mode cannot be approved without measured baseline`() {
        assertThrows(IllegalArgumentException::class.java) {
            SimilarFeatureBaseline(0, null, null, approved = true)
        }
    }

    private fun fingerprint(
        id: String,
        fullHash: String? = null,
        version: Int = 1,
        perceptual: List<Long> = emptyList(),
    ) = MediaFingerprint(
        MediaItemId(id), 100, hash('b'), fullHash, 1_000, 100, 100, perceptual,
        version, sourceModifiedEpochMillis = 1, generatedAtEpochMillis = 2,
    )

    private fun hash(char: Char): String = char.toString().repeat(64)
}
