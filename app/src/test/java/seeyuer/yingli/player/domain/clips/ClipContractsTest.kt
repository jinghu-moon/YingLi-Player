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

    // ---- 阶段 5 步骤 9：`forRange` 是「源 + 区间 → 单段项目」的唯一构造路径（§9.2 C1/C2）----

    @Test
    fun `for range accepts the two legal boundaries of a source`() {
        val fromStart = ClipProject.forRange(
            sourceMediaId = MediaItemId("media-1"),
            sourceLocationId = MediaLocationId("location-1"),
            sourceDurationMillis = 10_000,
            startMillis = 0,
            endMillis = 3_000,
            name = "clip",
            exportMode = ClipExportMode.FAST,
            preset = ClipPreset.SOURCE_QUALITY,
            projectId = ClipProjectId("project-1"),
            segmentId = ClipSegmentId("segment-1"),
            nowEpochMillis = 7,
        )
        // 终点正好等于源时长：合法（用户把 B 设在片尾）。
        val toEnd = fromStart.copy(segments = listOf(segment("segment-1", 9_000, 10_000)))

        assertEquals(0L, fromStart.segments.single().startMillis)
        assertEquals(10_000L, toEnd.segments.single().endMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `for range rejects an end beyond the source duration`() {
        ClipProject.forRange(
            sourceMediaId = MediaItemId("media-1"),
            sourceLocationId = MediaLocationId("location-1"),
            sourceDurationMillis = 10_000,
            startMillis = 9_000,
            endMillis = 10_001,
            name = "clip",
            exportMode = ClipExportMode.FAST,
            preset = ClipPreset.SOURCE_QUALITY,
            projectId = ClipProjectId("project-1"),
            segmentId = ClipSegmentId("segment-1"),
            nowEpochMillis = 7,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `for range rejects an empty interval`() {
        ClipProject.forRange(
            sourceMediaId = MediaItemId("media-1"),
            sourceLocationId = MediaLocationId("location-1"),
            sourceDurationMillis = 10_000,
            startMillis = 4_000,
            endMillis = 4_000,
            name = "clip",
            exportMode = ClipExportMode.FAST,
            preset = ClipPreset.SOURCE_QUALITY,
            projectId = ClipProjectId("project-1"),
            segmentId = ClipSegmentId("segment-1"),
            nowEpochMillis = 7,
        )
    }

    @Test
    fun `for range freezes mode preset ids and timestamps into one selected segment`() {
        val value = ClipProject.forRange(
            sourceMediaId = MediaItemId("media-1"),
            sourceLocationId = MediaLocationId("location-1"),
            sourceDurationMillis = 10_000,
            startMillis = 2_000,
            endMillis = 6_000,
            name = "影片_clip_2s-6s_exact",
            exportMode = ClipExportMode.ACCURATE,
            preset = ClipPreset.COMPATIBLE_MP4,
            projectId = ClipProjectId("project-9"),
            segmentId = ClipSegmentId("segment-9"),
            nowEpochMillis = 42,
        )

        assertEquals(ClipProjectId("project-9"), value.id)
        assertEquals(ClipExportMode.ACCURATE, value.exportMode)
        assertEquals(ClipPreset.COMPATIBLE_MP4, value.preset)
        assertEquals(42L, value.createdAtEpochMillis)
        assertEquals(42L, value.updatedAtEpochMillis)
        assertEquals(
            listOf(ClipSegment(ClipSegmentId("segment-9"), 2_000, 6_000, "影片_clip_2s-6s_exact", true)),
            value.segments,
        )
    }

    @Test
    fun `for range does not clamp and leaves validation to the segment initialiser`() {
        // 区间反过来（B 落在 A 之前）：会话侧靠互换保证 A<B，这里必须**抛出**而不是悄悄交换，
        // 否则「实际导出的是哪一段」将与用户看到的不一致。
        val failure = runCatching {
            ClipSegment.forRange(ClipSegmentId("s"), startMillis = 6_000, endMillis = 2_000, name = "clip")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
