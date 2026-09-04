package seeyuer.yingli.player.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractsTest {
    private val snapshot = BackupSnapshot(
        createdAtEpochMillis = 123,
        preferences = mapOf("theme" to "DARK"),
        tags = listOf(BackupTag("tag-1", "旅行", "BLUE")),
        tagAssignments = listOf(BackupTagAssignment("media-1", "tag-1")),
        favorites = listOf("media-1"),
        playlists = listOf(BackupPlaylist("playlist-1", "稍后看", listOf("media-1"))),
        collections = listOf(BackupCollection("collection-1", "短片", "MANUAL", null, listOf("media-1"))),
        history = listOf(BackupHistory("media-1", 2, 100, 30)),
    )

    @Test
    fun `backup codec round trips the versioned schema`() {
        assertEquals(snapshot, (BackupCodec.decode(BackupCodec.encode(snapshot)) as BackupDecodeResult.Success).snapshot)
    }

    @Test
    fun `backup codec rejects empty oversized truncated and unknown versions`() {
        assertEquals(BackupDecodeResult.Empty, BackupCodec.decode(" "))
        assertEquals(BackupDecodeResult.TooLarge, BackupCodec.decode("x".repeat(BackupCodec.MAX_DOCUMENT_CHARS + 1)))
        assertEquals(BackupDecodeResult.Invalid, BackupCodec.decode("{\"schemaVersion\":1"))
        assertEquals(BackupDecodeResult.UnsupportedVersion, BackupCodec.decode("{\"schemaVersion\":2}"))
    }

    @Test
    fun `github parser only accepts bounded github release pages`() {
        val release = GitHubReleaseParser.parse(
            """{"tag_name":"v1.0.0","name":"YingLi 1.0","html_url":"https://github.com/seeyuer/YingLi/releases/tag/v1.0.0","published_at":"2026-08-09","prerelease":false}""",
        )

        assertEquals("v1.0.0", release?.tagName)
        assertTrue(release?.prerelease == false)
        assertNull(GitHubReleaseParser.parse("""{"tag_name":"v1","html_url":"http://example.com/file"}"""))
        assertNull(GitHubReleaseParser.parse("x".repeat(GitHubReleaseParser.MAX_RESPONSE_CHARS + 1)))
    }
}
