package seeyuer.yingli.player.domain.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId

class ClipContractsTest {
    private fun segment(id: String, start: Long, end: Long, selected: Boolean = true) =
        ClipSegment(ClipSegmentId(id), start, end, id, selected)

    private fun project(segments: List<ClipSegment> = emptyList()) = ClipProject(
        ClipProjectId("project-1"),
        MediaItemId("media-1"),
        MediaLocationId("location-1"),
        10_000,
        segments,
        createdAtEpochMillis = 1,
    )

    @Test
    fun `overlapping segments are valid and preserve explicit order`() {
        val first = segment("first", 1_000, 4_000)
        val overlap = segment("overlap", 3_000, 6_000)

        assertEquals(listOf(first, overlap), project(listOf(first, overlap)).segments)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero length segment is rejected`() {
        segment("invalid", 1_000, 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `segment beyond source duration is rejected by project`() {
        project(listOf(segment("invalid", 9_000, 11_000)))
    }

    @Test
    fun `commands support add duplicate move select delete and undo`() {
        val first = segment("first", 1_000, 2_000)
        val commands = listOf(
            ClipEditCommand.Add(first, 2),
            ClipEditCommand.Duplicate(first.id, ClipSegmentId("copy"), 3),
            ClipEditCommand.Move(ClipSegmentId("copy"), 0, 4),
            ClipEditCommand.ToggleSelection(ClipSegmentId("copy"), 5),
            ClipEditCommand.DeleteSelected(6),
            ClipEditCommand.Undo,
        )

        val result = ClipProjectReducer.replay(ClipEditState(project()), commands)

        assertEquals(listOf("copy", "first"), result.project.segments.map { it.id.value })
        assertTrue(!result.project.segments.first().selected)
    }

    @Test
    fun `export plan contains selected clips only and deterministic safe names`() {
        val value = project(listOf(
            segment("one", 0, 1_000),
            ClipSegment(ClipSegmentId("two"), 1_000, 2_000, "bad/name", false),
        ))

        val plan = ClipExportPlan.from(value)

        assertEquals(1, plan.items.size)
        assertEquals("01-one.mp4", plan.items.single().displayName)
    }
}
