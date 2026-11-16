package it.panebarco.creator.media

import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.SourceAsset
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MediaTimelineTest {
    private fun asset(id: String, rotation: Int = 0, audio: Boolean = true) =
        SourceAsset(java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString(), "$id.mp4", 10_000_000L, 1920, 1080, rotation, audio, "a".repeat(64))

    @Test fun mapsExactMicrosecondsOrderGainAndRotationWithoutAccumulatedRounding() {
        val dir = Files.createTempDirectory("creator-mapping").toFile()
        try {
            listOf("first", "silent").forEach { dir.resolve("${asset(it).id}.mp4").writeText("fixture") }
            var project = ProjectEditor.appendImport(Project.create("Preciso"), asset("first", 90))
            project = ProjectEditor.trim(project, project.clips.single().id, 1_000_001L, 2_333_334L)
            project = ProjectEditor.setVolume(project, project.clips.single().id, .25f)
            project = ProjectEditor.appendRecording(project, asset("silent", audio = false))
            project = ProjectEditor.trim(project, project.clips.last().id, 13L, 1_234_580L)
            val mapping = MediaTimeline.from(project) { dir.resolve("$it.mp4") }
            assertEquals(1080, mapping.width)
            assertEquals(1920, mapping.height)
            assertEquals(1_000_001L, mapping.clips.first().sourceStartUs)
            assertEquals(2_333_334L, mapping.clips.first().sourceEndUs)
            assertEquals(1_333_333L, mapping.clips.last().timelineStartUs)
            assertEquals(2_567_900L, mapping.durationUs)
            assertEquals(.25f, mapping.clips.first().volume, 0f)
            assertEquals(90, mapping.clips.first().source.rotationDegrees)
            assertFalse(mapping.clips.last().source.hasAudio)
            val landscape = MediaTimeline.from(ProjectEditor.setCanvas(project, CanvasShape.LANDSCAPE)) { dir.resolve("$it.mp4") }
            assertEquals(1920, landscape.width)
            assertEquals(1080, landscape.height)
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyAndMissingMediaFailBeforeCreatingAnyPlayerOrOutput() {
        assertThrows(IllegalArgumentException::class.java) { MediaTimeline.from(Project.create("Vuoto")) { error("Must not resolve") } }
        val project = ProjectEditor.appendImport(Project.create("Mancante"), asset("missing"))
        assertThrows(IllegalArgumentException::class.java) { MediaTimeline.from(project) { java.io.File("/does-not-exist-creator.mp4") } }
    }

    @Test fun previewAndExportResolveOnlyCurrentTimelineNotAbandonedOrRedoSources() {
        val dir = Files.createTempDirectory("creator-mapping-history").toFile()
        try {
            listOf("first", "second", "capture").forEach { dir.resolve("${asset(it).id}.mp4").writeText("fixture") }
            var project = ProjectEditor.appendImport(Project.create("Cronologia"), asset("first"))
            project = ProjectEditor.appendImport(project, asset("second"))
            project = ProjectEditor.undo(project)
            project = ProjectEditor.appendRecording(project, asset("capture"))
            val resolved = mutableListOf<String>()
            val timeline = MediaTimeline.from(project) { resolved += it; dir.resolve("$it.mp4") }
            assertEquals(listOf(asset("first").id, asset("capture").id), resolved)
            assertEquals(listOf(asset("first").id, asset("capture").id), timeline.clips.map { it.source.id })
            assertTrue(project.canRedo)
        } finally { dir.deleteRecursively() }
    }
}
