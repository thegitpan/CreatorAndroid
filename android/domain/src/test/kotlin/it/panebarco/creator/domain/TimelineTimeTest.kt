package it.panebarco.creator.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TimelineTimeTest {
    @Test fun `cumulative rational boundaries have no accumulated rounding drift over an hour`() {
        val count = 108_000L
        var accumulated = 0L
        for (frame in 0 until count) {
            accumulated += TimelineTime.frameBoundaryUs(frame + 1, 30) - TimelineTime.frameBoundaryUs(frame, 30)
        }
        assertEquals(3_600_000_000L, accumulated)
        assertEquals(3_603_600_000L, TimelineTime.frameBoundaryUs(count, 30_000, 1_001))
        assertEquals(33_333L, TimelineTime.frameBoundaryUs(1, 30))
        assertEquals(66_666L, TimelineTime.frameBoundaryUs(2, 30))
        assertEquals(100_000L, TimelineTime.frameBoundaryUs(3, 30))
        assertEquals(Long.MAX_VALUE, TimelineTime.frameBoundaryUs(Long.MAX_VALUE, 1_000_000))
        assertThrows(ArithmeticException::class.java) { TimelineTime.frameBoundaryUs(Long.MAX_VALUE, 1) }
    }

    @Test fun `timeline source mapping respects trimmed starts cuts exact end and empty project`() {
        var project = ProjectEditor.appendImport(Project.create("Posizioni"), source())
        val first = project.clips.single().id
        project = ProjectEditor.trim(project, first, 333_333, 1_000_001)
        project = ProjectEditor.appendRecording(project, source())
        val second = project.clips[1].id
        assertEquals(333_333L, TimelineTime.locate(project, 0)!!.sourceTimeUs)
        assertEquals(second, TimelineTime.locate(project, 666_668)!!.clipId)
        assertEquals(0L, TimelineTime.locate(project, 666_668)!!.sourceTimeUs)
        assertEquals(666_668L, TimelineTime.toTimelineUs(project, first, 1_000_001))
        assertEquals(project.durationUs, TimelineTime.toTimelineUs(project, second, 2_000_000))
        assertEquals(2_000_000L, TimelineTime.locate(project, project.durationUs)!!.sourceTimeUs)
        assertNull(TimelineTime.locate(Project.create("Vuoto"), 0))
        assertThrows(IllegalArgumentException::class.java) { TimelineTime.locate(project, project.durationUs + 1) }
        assertThrows(IllegalArgumentException::class.java) { TimelineTime.toTimelineUs(project, first, 0) }
        assertEquals(123_456_000L, TimelineTime.millisecondsToUs(123_456))
        assertEquals(123_456L, TimelineTime.microsecondsToMillisecondsFloor(123_456_999))
    }

    private fun source(): SourceAsset {
        val id = UUID.randomUUID().toString()
        return SourceAsset(id, "$id.mp4", 2_000_000, 1920, 1080, sha256 = "0".repeat(64))
    }
}
