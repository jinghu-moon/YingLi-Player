package seeyuer.yingli.player.domain.duplicates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri

class DuplicateContractsTest {
    @Test
    fun `group id is derived from content hash and size`() {
        assertEquals(DuplicateGroupId("${hash('a')}-100"), DuplicateGroupId.of(hash('a'), 100))
        assertThrows(IllegalArgumentException::class.java) { DuplicateGroupId("has spaces") }
    }

    @Test
    fun `group requires at least two distinct locations of the same size`() {
        assertThrows(IllegalArgumentException::class.java) {
            group(listOf(candidate("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            group(listOf(candidate("a"), candidate("b", sizeBytes = 200)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            group(listOf(candidate("a"), candidate("a")))
        }
        assertEquals(2, group(listOf(candidate("a"), candidate("b"))).candidates.size)
    }

    @Test
    fun `reclaimable bytes counts everything except the kept location`() {
        val group = group(listOf(candidate("a"), candidate("b"), candidate("c")))

        assertEquals(200L, group.reclaimableBytes(setOf(MediaLocationId("a"))))
        assertEquals(300L, group.reclaimableBytes(emptySet()))
        assertEquals(0L, group.reclaimableBytes(group.candidates.map { it.locationId }.toSet()))
    }

    @Test
    fun `candidates sort by file name then location for a stable listing`() {
        val group = group(listOf(candidate("c", fileName = "b.mp4"), candidate("a", fileName = "a.mp4"), candidate("d", fileName = "a.mp4")))

        assertEquals(listOf("a", "d", "c"), group.sortedCandidates().map { it.locationId.value })
    }

    @Test
    fun `deletion plan requires explicit keep and trash selections`() {
        assertThrows(IllegalArgumentException::class.java) { plan(keep = emptySet(), trash = setOf("b")) }
        assertThrows(IllegalArgumentException::class.java) { plan(keep = setOf("a"), trash = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { plan(keep = setOf("a"), trash = setOf("a")) }
    }

    @Test
    fun `validation rejects a plan that belongs to another group`() {
        val group = group(listOf(candidate("a"), candidate("b")))

        assertEquals(
            DuplicatePlanValidation.Invalid("GROUP_MISMATCH"),
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("b"), groupId = DuplicateGroupId.of(hash('b'), 100)), snapshots(group)),
        )
    }

    @Test
    fun `validation rejects an evidence version change`() {
        val group = group(listOf(candidate("a"), candidate("b")))

        assertEquals(
            DuplicatePlanValidation.Invalid("EVIDENCE_VERSION_CHANGED"),
            DuplicateDeletionValidator.validate(
                group,
                plan(keep = setOf("a"), trash = setOf("b"), algorithmVersion = DUPLICATE_HASH_ALGORITHM_VERSION - 1),
                snapshots(group),
            ),
        )
    }

    @Test
    fun `validation rejects a selection outside the group`() {
        val group = group(listOf(candidate("a"), candidate("b")))

        assertEquals(
            DuplicatePlanValidation.Invalid("GROUP_MISMATCH"),
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("z")), snapshots(group)),
        )
    }

    @Test
    fun `validation rejects a missing snapshot`() {
        val group = group(listOf(candidate("a"), candidate("b")))

        assertEquals(
            DuplicatePlanValidation.Invalid("FINGERPRINT_MISSING"),
            DuplicateDeletionValidator.validate(
                group,
                plan(keep = setOf("a"), trash = setOf("b")),
                snapshots(group) - MediaLocationId("b"),
            ),
        )
    }

    @Test
    fun `validation rejects a snapshot that could not be hashed`() {
        val group = group(listOf(candidate("a"), candidate("b")))
        val current = snapshots(group).toMutableMap().apply {
            this[MediaLocationId("b")] = requireNotNull(this[MediaLocationId("b")]).copy(contentHash = null)
        }

        assertEquals(
            DuplicatePlanValidation.Invalid("FINGERPRINT_MISSING"),
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("b")), current),
        )
    }

    @Test
    fun `validation fails closed when the file content changed`() {
        val group = group(listOf(candidate("a"), candidate("b")))
        val current = snapshots(group).toMutableMap().apply {
            this[MediaLocationId("b")] = requireNotNull(this[MediaLocationId("b")]).copy(contentHash = hash('z'))
        }

        assertEquals(
            DuplicatePlanValidation.Invalid("FILE_CHANGED"),
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("b")), current),
        )
    }

    @Test
    fun `validation rejects a size change even when metadata and hash are unchanged`() {
        val group = group(listOf(candidate("a"), candidate("b")))
        val current = snapshots(group).toMutableMap().apply {
            this[MediaLocationId("b")] = requireNotNull(this[MediaLocationId("b")]).copy(sizeBytes = 101)
        }

        assertEquals(
            DuplicatePlanValidation.Invalid("FILE_CHANGED"),
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("b")), current),
        )
    }

    @Test
    fun `validation accepts a plan that only handles part of the group`() {
        val group = group(listOf(candidate("a"), candidate("b"), candidate("c")))

        assertTrue(
            DuplicateDeletionValidator.validate(group, plan(keep = setOf("a"), trash = setOf("b")), snapshots(group))
                is DuplicatePlanValidation.Valid,
        )
    }

    // ---- fixtures ----------------------------------------------------------

    private fun group(candidates: List<DuplicateCandidate>) =
        DuplicateGroup(contentHash = candidates.first().let { hash('a') }, sizeBytes = candidates.first().sizeBytes, candidates = candidates)

    private fun plan(
        keep: Set<String>,
        trash: Set<String>,
        groupId: DuplicateGroupId = DuplicateGroupId.of(hash('a'), 100),
        algorithmVersion: Int = DUPLICATE_HASH_ALGORITHM_VERSION,
    ) = DuplicateDeletionPlan(
        groupId = groupId,
        contentHash = hash('a'),
        sizeBytes = 100,
        keepLocationIds = keep.map(::MediaLocationId).toSet(),
        trashLocationIds = trash.map(::MediaLocationId).toSet(),
        algorithmVersion = algorithmVersion,
        createdAtEpochMillis = 1,
    )

    private fun snapshots(group: DuplicateGroup): Map<MediaLocationId, DuplicateCandidateSnapshot> =
        group.candidates.associate { candidate ->
            candidate.locationId to DuplicateCandidateSnapshot(
                locationId = candidate.locationId,
                sizeBytes = candidate.sizeBytes,
                modifiedEpochMillis = candidate.modifiedEpochMillis,
                contentHash = group.contentHash,
            )
        }

    private fun candidate(
        locationId: String,
        sizeBytes: Long = 100,
        fileName: String = "$locationId.mp4",
    ) = DuplicateCandidate(
        locationId = MediaLocationId(locationId),
        mediaItemId = MediaItemId("item-$locationId"),
        uri = MediaUri("content://media/$locationId"),
        fileName = fileName,
        relativePath = null,
        sourceMode = MediaSourceMode.MEDIA_STORE,
        sizeBytes = sizeBytes,
        modifiedEpochMillis = 1_000,
        durationMillis = 1_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = 0,
        lastSeenEpochMillis = 1_000,
    )

    private fun hash(char: Char): String = char.toString().repeat(64)
}
