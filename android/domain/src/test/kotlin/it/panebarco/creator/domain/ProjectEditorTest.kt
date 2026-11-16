package it.panebarco.creator.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProjectEditorTest {
    @Test fun `recording after undo survives every undo and redo without destroying redo`() {
        var project = Project.create("Intervista")
        val a = source(10_000_000)
        project = ProjectEditor.appendRecording(project, a)
        val originalA = project.clips.single()
        project = ProjectEditor.split(project, originalA.id, 4_000_000)
        project = ProjectEditor.undo(project)
        val historyBeforeRecording = project.history
        val b = source(2_000_000)
        project = ProjectEditor.appendRecording(project, b)
        assertTrue(project.canRedo)
        assertEquals(historyBeforeRecording, project.history)
        assertEquals(listOf(a.id, b.id), project.clips.map { it.sourceId })
        project = ProjectEditor.redo(project)
        assertEquals(listOf(a.id, a.id, b.id), project.clips.map { it.sourceId })
        assertEquals(12_000_000, project.durationUs)
        project = ProjectEditor.undo(project)
        assertEquals(listOf(a.id, b.id), project.clips.map { it.sourceId })
        assertEquals(listOf(a, b), project.sources)
        assertEquals(project, Json.decodeFromString<Project>(Json.encodeToString(project)))
    }

    @Test fun `explicit deletion of recording is undoable while later captures remain durable`() {
        var project = ProjectEditor.appendRecording(Project.create("Riprese"), source())
        val first = project.clips.single()
        project = ProjectEditor.delete(project, first.id)
        assertTrue(project.clips.isEmpty())
        val later = source()
        project = ProjectEditor.appendRecording(project, later)
        assertEquals(listOf(later.id), project.clips.map { it.sourceId })
        project = ProjectEditor.undo(project)
        assertEquals(listOf(first.sourceId, later.id), project.clips.map { it.sourceId })
        project = ProjectEditor.redo(project)
        assertEquals(listOf(later.id), project.clips.map { it.sourceId })
    }

    @Test fun `divergent edit truncates only redo and preserves imported and recorded originals`() {
        val a = source()
        val b = source()
        var project = ProjectEditor.appendImport(Project.create("Montaggio"), a)
        project = ProjectEditor.appendImport(project, b)
        project = ProjectEditor.undo(project)
        assertEquals(listOf(a.id), project.clips.map { it.sourceId })
        assertTrue(project.canRedo)
        val capture = source()
        project = ProjectEditor.appendRecording(project, capture)
        project = ProjectEditor.setCanvas(project, CanvasShape.LANDSCAPE)
        assertFalse(project.canRedo)
        assertEquals(listOf(a, b, capture), project.sources)
        assertEquals(listOf(a.id, capture.id), project.clips.map { it.sourceId })
        project = ProjectEditor.undo(project)
        assertEquals(CanvasShape.PORTRAIT, project.canvas)
        assertEquals(listOf(a.id, capture.id), project.clips.map { it.sourceId })
    }

    @Test fun `split trim volume reorder delete use immutable originals and exact source coordinates`() {
        val asset = source(9_999_999)
        var project = ProjectEditor.appendImport(Project.create("Tagli"), asset)
        val firstId = project.clips.single().id
        project = ProjectEditor.trim(project, firstId, 123, 9_000_001)
        project = ProjectEditor.split(project, firstId, 4_000_123)
        val secondId = project.clips[1].id
        assertEquals(4_000_000L, project.clips[0].durationUs)
        assertEquals(4_999_878L, project.clips[1].durationUs)
        project = ProjectEditor.setVolume(project, secondId, 0f)
        project = ProjectEditor.move(project, secondId, 0)
        assertEquals(secondId, project.clips[0].id)
        assertEquals(0f, project.clips[0].volume)
        project = ProjectEditor.delete(project, firstId)
        assertEquals(listOf(asset), project.sources)
        assertEquals(4_999_878L, project.durationUs)
        val renamed = ProjectEditor.rename(project, " Nuovo nome ")
        assertEquals("Nuovo nome", renamed.title)
        assertEquals(project.history, renamed.history)
        assertEquals(project.historyCursor, renamed.historyCursor)
        assertEquals(listOf(asset), ProjectEditor.undo(renamed).sources)
    }

    @Test fun `invalid bounds missing clips NaN volume and future schemas fail without changing project`() {
        val project = ProjectEditor.appendRecording(Project.create("Verifica"), source(1_000_000))
        val id = project.clips.single().id
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.split(project, id, 0) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.split(project, id, 1_000_000) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.trim(project, id, -1, 100) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.trim(project, id, 0, 1_000_001) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.setVolume(project, id, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.setVolume(project, id, Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.setVolume(project, id, 4.01f) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.delete(project, UUID.randomUUID().toString()) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.move(project, id, 1) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.validate(project.copy(schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.validate(project.copy(id = "../other")) }
        assertEquals(1_000_000L, project.durationUs)
        assertEquals(1, project.clips.size)
    }

    @Test fun `validation checks redo references duplicate captures and cumulative overflow`() {
        val asset = source(Long.MAX_VALUE)
        var project = ProjectEditor.appendImport(Project.create("Limiti"), asset)
        project = ProjectEditor.undo(project)
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.validate(project.copy(sources = emptyList())) }
        val captured = ProjectEditor.appendRecording(Project.create("Registro"), source())
        assertThrows(IllegalArgumentException::class.java) {
            ProjectEditor.validate(captured.copy(captureLedger = captured.captureLedger + captured.captureLedger))
        }
        val large = ProjectEditor.appendRecording(Project.create("Overflow"), asset)
        assertThrows(IllegalArgumentException::class.java) { ProjectEditor.appendRecording(large, source(1)) }
    }

    @Test fun `no-op operations preserve undo history and timestamp`() {
        val project = ProjectEditor.appendRecording(Project.create("Idempotente"), source())
        assertSame(project, ProjectEditor.undo(project))
        assertSame(project, ProjectEditor.redo(project))
        assertSame(project, ProjectEditor.setCanvas(project, CanvasShape.PORTRAIT))
        assertSame(project, ProjectEditor.rename(project, project.title))
        assertSame(project, ProjectEditor.setVolume(project, project.clips.single().id, 1f))
        assertSame(project, ProjectEditor.move(project, project.clips.single().id, 0))
    }

    private fun source(duration: Long = 3_000_000): SourceAsset {
        val id = UUID.randomUUID().toString()
        return SourceAsset(id, "$id.mp4", duration, 1920, 1080, sha256 = "0".repeat(64))
    }
}
