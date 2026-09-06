package seeyuer.yingli.player.data.duplicates

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.duplicates.DuplicateFingerprintGenerator
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.MediaFingerprint
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult

class DefaultDuplicateScannerTest {
    @Test
    fun `exact scan layers size quick and full hash and persists only verified groups`() = runTest {
        val media = listOf(item("a"), item("b"), item("c"), item("different-size"))
        val repository = FakeDuplicateRepository()
        val generator = FakeGenerator(
            sizes = mapOf("a" to 100, "b" to 100, "c" to 100, "different-size" to 200),
            quick = mapOf("a" to hash('1'), "b" to hash('1'), "c" to hash('1'), "different-size" to hash('1')),
            full = mapOf("a" to hash('a'), "b" to hash('a'), "c" to hash('c'), "different-size" to hash('a')),
        )
        val scanner = DefaultDuplicateScanner(FakeLibraryRepository(media), repository, generator, FixedClock)

        val result = scanner.scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(1, result.groups.size)
        assertEquals(setOf("a", "b"), result.groups.single().candidates.map { it.mediaId.value }.toSet())
        assertEquals(3, generator.fullCalls)
        assertEquals(3, generator.quickCalls)
        assertEquals(result.groups, repository.state.value)
    }

    @Test
    fun `similar scan remains disabled without measured baseline`() = runTest {
        val scanner = DefaultDuplicateScanner(
            FakeLibraryRepository(emptyList()), FakeDuplicateRepository(), FakeGenerator(emptyMap(), emptyMap(), emptyMap()), FixedClock,
        )

        assertEquals(
            DuplicateScanResult.Rejected("SIMILAR_EXPERIMENT_DISABLED"),
            scanner.scan(DuplicateMode.SIMILAR),
        )
    }

    private class FakeGenerator(
        private val sizes: Map<String, Long>,
        private val quick: Map<String, String>,
        private val full: Map<String, String>,
    ) : DuplicateFingerprintGenerator {
        var quickCalls = 0
        var fullCalls = 0
        override suspend fun size(uri: String): Long? = sizes[uri.substringAfterLast('/')]
        override suspend fun quickHash(uri: String, sizeBytes: Long): String? {
            quickCalls++
            return quick[uri.substringAfterLast('/')]
        }
        override suspend fun fullHash(uri: String): String? {
            fullCalls++
            return full[uri.substringAfterLast('/')]
        }
        override suspend fun perceptualHashes(uri: String, durationMillis: Long): List<Long> = emptyList()
    }

    private class FakeDuplicateRepository : DuplicateRepository {
        val state = MutableStateFlow<List<DuplicateGroup>>(emptyList())
        private val fingerprints = mutableMapOf<MediaItemId, MediaFingerprint>()
        override val groups: Flow<List<DuplicateGroup>> = state
        override suspend fun fingerprint(mediaId: MediaItemId): MediaFingerprint? = fingerprints[mediaId]
        override suspend fun saveFingerprints(fingerprints: List<MediaFingerprint>) {
            fingerprints.forEach { this.fingerprints[it.mediaId] = it }
        }
        override suspend fun replaceGroups(mode: DuplicateMode, groups: List<DuplicateGroup>) {
            state.value = state.value.filterNot { it.mode == mode } + groups
        }
        override suspend fun ignore(groupId: DuplicateGroupId) {
            state.value = state.value.filterNot { it.id == groupId }
        }
    }

    private class FakeLibraryRepository(private val media: List<LibraryMedia>) : LibraryRepository {
        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> = MutableStateFlow(page())
        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> = page()
        private fun page() = LibraryResult.Success(LibraryPage(media, null, media.size))
    }

    private object FixedClock : AppClock { override fun now(): Instant = Instant.ofEpochMilli(1_000) }

    private fun item(id: String) = LibraryMedia(
        MediaItemId(id), MediaLocationId("loc-$id"), MediaUri("content://media/$id"), id, "$id.mp4", "Movies", "mp4",
        1_000, 100, 100, 1, 0, false,
    )

    private fun hash(value: Char): String = value.toString().repeat(64)
}
