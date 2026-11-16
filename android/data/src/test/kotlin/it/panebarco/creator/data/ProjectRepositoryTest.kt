package it.panebarco.creator.data

import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.SourceAsset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: File get() = File(temporary.root, "library")
    private fun repository() = ProjectRepository(root)

    @Test fun `public workflow persists captures imports undo redo trash and restore`() {
        val repository = repository()
        var project = repository.create("Intervista", CanvasShape.LANDSCAPE)
        project = commit(repository, project, "A", recording = false)
        val a = project.clips.single().id
        project = ProjectEditor.split(project, a, 1_000_001)
        repository.save(project)
        project = ProjectEditor.undo(repository.load(project.id))
        repository.save(project)
        project = commit(repository, project, "B", recording = true)
        assertTrue(project.canRedo)
        val reopened = repository().load(project.id)
        assertEquals(project, reopened)
        assertEquals(2, reopened.clips.size)
        val redone = ProjectEditor.redo(reopened)
        repository.save(redone)
        assertEquals(3, repository().load(project.id).clips.size)
        val hashes = project.sources.associate { it.id to digest(repository.sourceFile(project.id, it.id).readBytes()) }
        repository.moveToTrash(project.id)
        assertTrue(repository.isInTrash(project.id))
        assertTrue(repository.listProjects().isEmpty())
        assertEquals(listOf(project.id), repository.listProjects(includeTrash = true).map { it.id })
        assertThrows(IOException::class.java) { repository.stageFile(project.id) }
        repository.restore(project.id)
        assertFalse(repository.isInTrash(project.id))
        assertEquals(redone, repository.load(project.id))
        hashes.forEach { (id, hash) -> assertEquals(hash, digest(repository.sourceFile(project.id, id).readBytes())) }
    }

    @Test fun `repository controls source identity names and verifies provided hash before moving`() {
        val repository = repository()
        val project = repository.create("Importazione")
        val staged = repository.stageFile(project.id, "MP4").apply { writeText("media originale") }
        assertThrows(IOException::class.java) {
            repository.commitSource(project.id, staged, metadata(sha256 = "0".repeat(64)), recording = true)
        }
        assertTrue(staged.exists())
        assertTrue(repository.load(project.id).sources.isEmpty())
        val result = repository.commitSource(project.id, staged, metadata(), recording = true)
        val source = result.sources.single()
        assertNotEquals("placeholder", source.id)
        assertEquals("${source.id}.mp4", source.fileName)
        assertEquals(digest("media originale".toByteArray()), source.sha256)
        assertFalse(staged.exists())
        assertEquals("media originale", repository.sourceFile(project.id, source.id).readText())
    }

    @Test fun `source metadata and capture ledger cannot be rewritten by arbitrary save`() {
        val repository = repository()
        val project = commit(repository, repository.create("Originali"), "originale", recording = true)
        val edited = ProjectEditor.rename(project, "Nuovo")
        assertThrows(IOException::class.java) {
            repository.save(edited.copy(sources = project.sources.map { it.copy(width = 640) }))
        }
        assertThrows(IOException::class.java) {
            repository.save(edited.copy(captureLedger = emptyList()))
        }
        assertEquals(project, repository.load(project.id))
        assertEquals("originale", repository.sourceFile(project.id, project.sources.single().id).readText())
    }

    @Test fun `stale divergent save is rejected even with newer walltime while batched edits save`() {
        val repository = repository()
        val base = repository.create("Base")
        val first = ProjectEditor.rename(base, "Primo")
        repository.save(first)
        val divergent = ProjectEditor.rename(base, "Obsoleto").copy(updatedAtMs = first.updatedAtMs + 1_000)
        assertThrows(IOException::class.java) { repository().save(divergent) }
        assertEquals(first, repository.load(base.id))
        val batch = ProjectEditor.setCanvas(ProjectEditor.rename(first, "Lotto"), CanvasShape.LANDSCAPE)
        repository.save(batch)
        assertEquals(batch, repository.load(base.id))
    }

    @Test fun `corrupt manifest recovers newest revision and retains damaged evidence and durable warning`() {
        val repository = repository()
        var project = commit(repository, repository.create("Recupero"), "sorgente", recording = true)
        repeat(12) {
            project = ProjectEditor.rename(project, "Revisione $it")
            repository.save(project)
        }
        val directory = repository.projectDirectory(project.id)
        assertEquals(5, File(directory, "revisions").listFiles()!!.size)
        File(directory, "manifest.json").writeText("{incomplete")
        val recovered = repository().load(project.id)
        assertEquals(project, recovered)
        assertEquals("{incomplete", File(directory, "damaged").listFiles()!!.single().readText())
        assertTrue(repository().recoveryWarnings().any { it.projectId == project.id && "recuperata" in it.message })
        assertEquals(project, repository().load(project.id))
        assertEquals("sorgente", repository.sourceFile(project.id, project.sources.single().id).readText())
    }

    @Test fun `valid manifest with missing media preserves exact current edit and redo instead of rollback`() {
        val repository = repository()
        var project = commit(repository, repository.create("Mancante"), "A", recording = false)
        project = commit(repository, project, "B", recording = false)
        project = ProjectEditor.undo(project)
        project = ProjectEditor.rename(project, "Ultimo montaggio con redo")
        repository.save(project)
        val missing = project.sources.last()
        repository.sourceFile(project.id, missing.id).delete()
        val directory = repository.projectDirectory(project.id)
        val manifestBefore = File(directory, "manifest.json").readBytes()
        assertEquals(project, repository().load(project.id))
        assertArrayEquals(manifestBefore, File(directory, "manifest.json").readBytes())
        assertFalse(File(directory, "damaged").exists())
        assertTrue(repository().recoveryWarnings().any { it.projectId == project.id && "mancanti" in it.message })
        assertThrows(IOException::class.java) { repository.sourceFile(project.id, missing.id) }
        assertThrows(IOException::class.java) { repository.exportArchive(project.id, File(temporary.root, "missing.zip")) }
    }

    @Test fun `future schemas never downgrade to old retained revision`() {
        val repository = repository()
        val project = repository.create("Futuro")
        val manifest = File(repository.projectDirectory(project.id), "manifest.json")
        val future = manifest.readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        manifest.writeText(future)
        assertThrows(IOException::class.java) { repository.load(project.id) }
        assertEquals(future, manifest.readText())
        assertTrue(repository.listProjects().isEmpty())
        assertTrue(repository.recoveryWarnings().any { "Aggiorna" in it.message })
    }

    @Test fun `total corruption is isolated in library and no empty replacement is created`() {
        val repository = repository()
        val good = repository.create("Buono")
        val damaged = repository.create("Danneggiato")
        val directory = repository.projectDirectory(damaged.id)
        File(directory, "manifest.json").writeText("broken")
        File(directory, "revisions").listFiles()!!.forEach { it.writeText("broken revision") }
        assertThrows(IOException::class.java) { repository.load(damaged.id) }
        assertEquals(listOf(good), repository.listProjects())
        assertEquals("broken", File(directory, "manifest.json").readText())
        assertTrue(repository().recoveryWarnings().any { it.projectId == damaged.id })
    }

    @Test fun `unwritable warning storage does not hide valid projects or in-memory warning`() {
        val repository = repository()
        val good = repository.create("Buono")
        val broken = repository.create("Cattivo")
        val directory = repository.projectDirectory(broken.id)
        File(directory, "manifest.json").writeText("broken")
        File(directory, "revisions").listFiles()!!.forEach { it.writeText("broken") }
        File(root, "warnings").writeText("simulates unavailable directory")
        assertEquals(listOf(good), repository.listProjects())
        assertTrue(repository.recoveryWarnings().any { it.projectId == broken.id })
    }

    @Test fun `source commit receipts recover exactly once at every interruption checkpoint`() {
        listOf(SaveCheckpoint.RECEIPT_SYNCED, SaveCheckpoint.SOURCE_MOVED, SaveCheckpoint.REVISION_SYNCED,
            SaveCheckpoint.BEFORE_MANIFEST_REPLACE, SaveCheckpoint.MANIFEST_REPLACED).forEach { phase ->
            val caseRoot = File(temporary.root, phase.name)
            val normal = ProjectRepository(caseRoot)
            val project = normal.create("Interruzione $phase")
            val staged = normal.stageFile(project.id, recording = true).apply { writeText("video $phase") }
            val failing = ProjectRepository(caseRoot) { if (it == phase) throw IOException("Injected $phase") }
            assertThrows(IOException::class.java) { failing.commitSource(project.id, staged, metadata(), recording = true) }
            val recovered = ProjectRepository(caseRoot).load(project.id)
            assertEquals("$phase", 1, recovered.sources.size)
            assertEquals("$phase", 1, recovered.captureLedger.size)
            assertEquals(1, recovered.clips.size)
            assertEquals("video $phase", normal.sourceFile(project.id, recovered.sources.single().id).readText())
            repeat(3) { assertEquals(recovered, normal.load(project.id)) }
            assertTrue(File(normal.projectDirectory(project.id), "transactions").listFiles()!!.isEmpty())
            assertTrue(normal.pendingMediaFiles(project.id).isEmpty())
            assertTrue(File(normal.projectDirectory(project.id), "pending-origins").listFiles()!!.isEmpty())
        }
    }

    @Test fun `interrupted imported source recovery remains undoable and merges newest saved edits`() {
        val repository = repository()
        val project = repository.create("Import interrotto")
        val staged = repository.stageFile(project.id, recording = false).apply { writeText("import") }
        val failing = ProjectRepository(root) { if (it == SaveCheckpoint.SOURCE_MOVED) throw IOException("interrupted") }
        assertThrows(IOException::class.java) { failing.commitSource(project.id, staged, metadata(), recording = false) }
        val newer = ProjectEditor.setCanvas(ProjectEditor.rename(project, "Modifica più recente"), CanvasShape.LANDSCAPE)
        File(repository.projectDirectory(project.id), "manifest.json").writeText(Json { encodeDefaults = true }.encodeToString(newer))
        val recovered = repository().load(project.id)
        assertEquals(newer.title, recovered.title)
        assertEquals(CanvasShape.LANDSCAPE, recovered.canvas)
        assertTrue(recovered.canUndo)
        assertTrue(recovered.captureLedger.isEmpty())
        assertTrue(ProjectEditor.undo(recovered).clips.isEmpty())
        assertEquals(CanvasShape.LANDSCAPE, ProjectEditor.undo(recovered).canvas)
    }

    @Test fun `failed manifest replacement retains old authoritative project and all valid revisions`() {
        val repository = repository()
        val project = repository.create("Prima")
        val changed = ProjectEditor.rename(project, "Dopo")
        val failing = ProjectRepository(root) { if (it == SaveCheckpoint.BEFORE_MANIFEST_REPLACE) throw IOException("power loss") }
        assertThrows(IOException::class.java) { failing.save(changed) }
        assertEquals(project, repository.load(project.id))
        assertTrue(File(repository.projectDirectory(project.id), "revisions").listFiles()!!.any { "Dopo" in it.readText() })
        File(repository.projectDirectory(project.id), "manifest.json").writeText("corrupt")
        assertEquals(changed, repository.load(project.id))
    }

    @Test fun `pending inventory exposes only nonempty staged UUID media and preserves invalid recovery candidates`() {
        val repository = repository()
        val project = repository.create("Temporanei")
        val good = repository.stageFile(project.id).apply { writeText("unprobed") }
        repository.stageFile(project.id) // zero-length, possibly still a recording.
        val pending = good.parentFile
        File(pending, "write-${UUID.randomUUID()}.tmp").writeText("manifest write")
        File(pending, "other.mp4").writeText("not repository staging")
        assertEquals(listOf(good), repository.pendingMediaFiles(project.id))
        assertThrows(IllegalArgumentException::class.java) {
            repository.commitSource(project.id, good, metadata().copy(durationUs = 0), recording = true)
        }
        assertTrue(good.exists())
        assertTrue(repository.recoveryWarnings().any { "Recupera video" in it.message })
    }

    @Test fun `restart after full staged copy preserves import undo and durable capture purpose`() {
        listOf(false, true).forEach { recording ->
            val caseRoot = File(temporary.root, "staged-origin-$recording")
            val beforeCrash = ProjectRepository(caseRoot)
            val project = beforeCrash.create("Origine $recording")
            val staged = beforeCrash.stageFile(project.id, recording = recording)
            assertEquals(recording, ProjectRepository(caseRoot).pendingMediaOrigin(project.id, staged))
            staged.writeText("finalized media $recording")
            val restarted = ProjectRepository(caseRoot)
            val candidate = restarted.pendingMediaFiles(project.id).single()
            val recovered = restarted.commitSource(project.id, candidate, metadata(),
                recording = restarted.pendingMediaOrigin(project.id, candidate) ?: false)
            assertEquals(recording, recovered.captureLedger.isNotEmpty())
            assertEquals(!recording, recovered.canUndo)
            val undone = ProjectEditor.undo(recovered)
            assertEquals(recording, undone.clips.isNotEmpty())
            assertEquals(recovered.sources, undone.sources)
            assertEquals("finalized media $recording", restarted.sourceFile(project.id, recovered.sources.single().id).readText())
            assertTrue(File(restarted.projectDirectory(project.id), "pending-origins").listFiles()!!.isEmpty())
        }
    }

    @Test fun `legacy and malformed origin metadata recover conservatively as imports without enumerating sidecars`() {
        val repository = repository()
        val project = repository.create("Origine sconosciuta")
        val legacy = repository.stageFile(project.id).apply { writeText("legacy video") }
        val malformed = repository.stageFile(project.id, recording = true).apply { writeText("unknown purpose") }
        val originFile = File(repository.projectDirectory(project.id), "pending-origins/${malformed.name}.json")
        originFile.writeText("unfinished metadata")
        File(legacy.parentFile, "${legacy.name}.json").writeText("a sidecar must never be media")
        File(legacy.parentFile, "write-${UUID.randomUUID()}.tmp").writeText("{\"recording\":true}")
        val restarted = repository()
        assertEquals(setOf(legacy, malformed), restarted.pendingMediaFiles(project.id).toSet())
        assertNull(restarted.pendingMediaOrigin(project.id, legacy))
        assertNull(restarted.pendingMediaOrigin(project.id, malformed))
        var recovered = restarted.commitSource(project.id, legacy, metadata(),
            recording = restarted.pendingMediaOrigin(project.id, legacy) ?: false)
        recovered = restarted.commitSource(project.id, malformed, metadata(),
            recording = restarted.pendingMediaOrigin(project.id, malformed) ?: false)
        assertTrue(recovered.captureLedger.isEmpty())
        assertTrue(ProjectEditor.undo(ProjectEditor.undo(recovered)).clips.isEmpty())
        assertTrue(originFile.exists()) // Preserve uncertain metadata, rather than deleting another receipt.
        assertTrue(restarted.recoveryWarnings().any { "come video importato" in it.message })
    }

    @Test fun `origin metadata validates schema identity paths and known purpose before commit`() {
        val repository = repository()
        val project = repository.create("Origine verificata")
        val staged = repository.stageFile(project.id, recording = false).apply { writeText("import") }
        val origin = File(repository.projectDirectory(project.id), "pending-origins/${staged.name}.json")
        val correct = origin.readText()
        assertThrows(IOException::class.java) { repository.commitSource(project.id, staged, metadata(), recording = true) }
        assertTrue(staged.exists())
        assertTrue(repository.load(project.id).sources.isEmpty())
        listOf(
            correct.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            correct.replace(project.id, UUID.randomUUID().toString()),
            correct.replace(staged.name, "../other.mp4"),
        ).forEach { invalid ->
            origin.writeText(invalid)
            assertNull(repository().pendingMediaOrigin(project.id, staged))
            assertEquals(invalid, origin.readText())
        }
        val outside = File(temporary.root, "${UUID.randomUUID()}.mp4").apply { writeText("outside") }
        assertThrows(IOException::class.java) { repository.pendingMediaOrigin(project.id, outside) }
        origin.writeText(correct)
        val other = repository.stageFile(project.id, recording = true).apply { writeText("keep other metadata") }
        val otherOrigin = File(origin.parentFile, "${other.name}.json")
        val result = repository.commitSource(project.id, staged, metadata(), recording = false)
        assertTrue(result.canUndo)
        assertFalse(origin.exists())
        assertTrue(otherOrigin.exists())
        assertEquals(true, repository.pendingMediaOrigin(project.id, other))
    }

    @Test fun `origin-write interruptions never expose a media file with an incorrect purpose`() {
        listOf(SaveCheckpoint.BEFORE_PENDING_ORIGIN_WRITE, SaveCheckpoint.PENDING_ORIGIN_SYNCED).forEach { phase ->
            val caseRoot = File(temporary.root, "origin-interruption-$phase")
            val normal = ProjectRepository(caseRoot)
            val project = normal.create("Metadati interrotti")
            val failing = ProjectRepository(caseRoot) { if (it == phase) throw IOException("origin write interrupted") }
            assertThrows(IOException::class.java) { failing.stageFile(project.id, recording = false) }
            val reopened = ProjectRepository(caseRoot)
            assertTrue(reopened.pendingMediaFiles(project.id).isEmpty())
            assertEquals(project, reopened.load(project.id))
            assertTrue(File(reopened.projectDirectory(project.id), "pending").listFiles()!!.none { it.extension == "mp4" })
            val legacy = reopened.stageFile(project.id).apply { writeText("unknown legacy media") }
            assertNull(reopened.pendingMediaOrigin(project.id, legacy))
        }
    }

    @Test fun `archive roundtrip preserves undo redo ledger originals and gives every import a new id`() {
        val repository = repository()
        var project = commit(repository, repository.create("Backup"), "import", recording = false)
        project = ProjectEditor.split(project, project.clips.single().id, 1_234_567)
        repository.save(project)
        project = ProjectEditor.undo(project)
        repository.save(project)
        project = commit(repository, project, "capture", recording = true)
        val archive = File(temporary.root, "backup.zip")
        repository.exportArchive(project.id, archive)
        val restored = repository.importArchive(archive)
        val duplicate = repository.importArchive(archive)
        assertNotEquals(project.id, restored.id)
        assertNotEquals(restored.id, duplicate.id)
        assertEquals(project.history, restored.history)
        assertEquals(project.historyCursor, restored.historyCursor)
        assertEquals(project.captureLedger, restored.captureLedger)
        assertEquals(project.sources, restored.sources)
        assertEquals(project.clips, restored.clips)
        assertTrue(restored.canRedo)
        restored.sources.forEach { asset ->
            assertArrayEquals(repository.sourceFile(project.id, asset.id).readBytes(), repository.sourceFile(restored.id, asset.id).readBytes())
        }
        assertEquals(project, repository.load(project.id))
    }

    @Test fun `archive checksum mismatch missing redo source and unexpected files reject without modifying projects`() {
        val repository = repository()
        var project = commit(repository, repository.create("Integrità"), "media", recording = false)
        project = ProjectEditor.undo(project)
        repository.save(project)
        val archive = File(temporary.root, "valid.zip")
        repository.exportArchive(project.id, archive)
        val entries = unzip(archive)
        val sourceName = "Sources/${project.sources.single().fileName}"
        val before = repository.listProjects()
        val changed = File(temporary.root, "changed.zip")
        zip(changed, entries + (sourceName to "different".toByteArray()))
        assertThrows(IOException::class.java) { repository.importArchive(changed) }
        val missing = File(temporary.root, "missing.zip")
        zip(missing, entries - sourceName)
        assertThrows(IOException::class.java) { repository.importArchive(missing) }
        val extra = File(temporary.root, "extra.zip")
        zip(extra, entries + ("Sources/extra.mp4" to byteArrayOf(1)))
        assertThrows(IOException::class.java) { repository.importArchive(extra) }
        assertEquals(before, repository.listProjects())
        assertEquals("media", repository.sourceFile(project.id, project.sources.single().id).readText())
        assertTrue(File(root, "incoming").listFiles()!!.isEmpty())
    }

    @Test fun `zip traversal case collisions oversized manifests and empty backups are rejected`() {
        val repository = repository()
        val project = repository.create("Sicurezza")
        val manifest = Json { encodeDefaults = true }.encodeToString(project).toByteArray()
        listOf("../escape.mp4", "/absolute.mp4", "Sources/../../escape", "Sources\\escape.mp4", "Sources/a:b.mp4",
            "Sources/a/inside.mp4", "./manifest.json").forEachIndexed { index, path ->
            val archive = File(temporary.root, "unsafe$index.zip")
            zip(archive, linkedMapOf("manifest.json" to manifest, path to byteArrayOf(1)))
            assertThrows(IOException::class.java) { repository.importArchive(archive) }
        }
        val collisions = File(temporary.root, "collisions.zip")
        zip(collisions, linkedMapOf("manifest.json" to manifest, "Sources/A.mp4" to byteArrayOf(1), "Sources/a.mp4" to byteArrayOf(1)))
        assertThrows(IOException::class.java) { repository.importArchive(collisions) }
        val huge = File(temporary.root, "huge.zip")
        zip(huge, mapOf("manifest.json" to ByteArray((ProjectRepository.MAX_MANIFEST_BYTES + 1).toInt())))
        assertThrows(IOException::class.java) { repository.importArchive(huge) }
        val empty = File(temporary.root, "empty.zip").apply { writeBytes(byteArrayOf()) }
        assertThrows(IOException::class.java) { repository.importArchive(empty) }
        assertEquals(listOf(project), repository.listProjects())
        assertFalse(File(temporary.root, "escape.mp4").exists())
    }

    @Test fun `archive refuses overwrite and repository destinations while detecting changed original bytes`() {
        val repository = repository()
        val project = commit(repository, repository.create("Destinazione"), "media", recording = true)
        val existing = File(temporary.root, "existing.zip").apply { writeText("previous backup") }
        assertThrows(IOException::class.java) { repository.exportArchive(project.id, existing) }
        assertEquals("previous backup", existing.readText())
        assertThrows(IOException::class.java) { repository.exportArchive(project.id, File(root, "danger.zip")) }
        repository.sourceFile(project.id, project.sources.single().id).writeText("tampered source")
        val rejected = File(temporary.root, "tampered.zip")
        assertThrows(IOException::class.java) { repository.exportArchive(project.id, rejected) }
        assertFalse(rejected.exists())
    }

    @Test fun `archive publication never replaces a zero byte destination racing after initial check`() {
        val normal = repository()
        val project = normal.create("Conflitto backup")
        val output = File(temporary.root, "racing.zip")
        val racing = ProjectRepository(root) { phase ->
            if (phase == SaveCheckpoint.BEFORE_ARCHIVE_PUBLISH) output.writeBytes(byteArrayOf())
        }
        assertThrows(IOException::class.java) { racing.exportArchive(project.id, output) }
        assertTrue(output.isFile)
        assertEquals(0L, output.length())
        assertEquals(project, normal.load(project.id))
    }

    @Test fun `failed archive copy removes only its own partial output and preserves a replaced destination`() {
        val normal = repository()
        val project = normal.create("Copie interrotte")
        val ownOutput = File(temporary.root, "own-partial.zip")
        val ownFailure = ProjectRepository(root) { phase ->
            if (phase == SaveCheckpoint.ARCHIVE_COPYING) throw IOException("interrupted owned copy")
        }
        assertThrows(IOException::class.java) { ownFailure.exportArchive(project.id, ownOutput) }
        assertFalse(ownOutput.exists())

        val replacedOutput = File(temporary.root, "foreign.zip")
        var replaced = false
        val replacementFailure = ProjectRepository(root) { phase ->
            if (phase == SaveCheckpoint.ARCHIVE_COPYING && !replaced) {
                replaced = true
                Files.delete(replacedOutput.toPath())
                replacedOutput.writeText("foreign destination")
                throw IOException("interrupted after replacement")
            }
        }
        assertThrows(IOException::class.java) { replacementFailure.exportArchive(project.id, replacedOutput) }
        assertEquals("foreign destination", replacedOutput.readText())
        assertEquals(project, normal.load(project.id))
    }

    @Test fun `competing repositories publishing one archive name have exactly one winner`() {
        val first = ProjectRepository(File(temporary.root, "first-library"))
        val second = ProjectRepository(File(temporary.root, "second-library"))
        val firstProject = first.create("Primo backup")
        val secondProject = second.create("Secondo backup")
        val bothReady = CountDownLatch(2)
        val output = File(temporary.root, "one-winner.zip")
        val publisher: (SaveCheckpoint) -> Unit = { phase ->
            if (phase == SaveCheckpoint.BEFORE_ARCHIVE_PUBLISH) {
                bothReady.countDown()
                if (!bothReady.await(5, TimeUnit.SECONDS)) throw IOException("publish barrier timed out")
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(
                Callable { runCatching { ProjectRepository(File(temporary.root, "first-library"), publisher).exportArchive(firstProject.id, output) } },
                Callable { runCatching { ProjectRepository(File(temporary.root, "second-library"), publisher).exportArchive(secondProject.id, output) } },
            )).map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is IOException })
        } finally { executor.shutdownNow() }
        val restored = first.importArchive(output)
        assertTrue(restored.title in setOf(firstProject.title, secondProject.title))
    }

    @Test fun `directory entry payload is rejected without draining hidden expanded data`() {
        val repository = repository()
        val project = repository.create("Cartella ZIP ostile")
        val archive = File(temporary.root, "directory-payload.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Sources/"))
            val zeros = ByteArray(8_192)
            repeat(32_768) { zip.write(zeros) } // 256 MiB hidden inside a highly compressed directory.
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json { encodeDefaults = true }.encodeToString(project).toByteArray())
            zip.closeEntry()
        }
        assertTrue(archive.length() < 500_000)
        val error = assertThrows(IOException::class.java) { repository.importArchive(archive) }
        assertTrue(error.message.orEmpty().contains("cartella"))
        assertEquals(listOf(project), repository.listProjects())
        assertTrue(File(root, "incoming").listFiles()!!.isEmpty())
    }

    @Test fun `too complex save is rejected before replacing current readable manifest or revisions`() {
        val repository = repository()
        val project = repository.create("Montaggio valido")
        val directory = repository.projectDirectory(project.id)
        val manifest = File(directory, "manifest.json")
        val bytes = manifest.readBytes()
        val revisionNames = File(directory, "revisions").listFiles()!!.map { it.name }.toSet()
        val tooComplex = ProjectEditor.rename(project, "Troppo complesso").copy(
            history = List(30_000) { project.history.single() }, historyCursor = 0,
        )
        ProjectEditor.validate(tooComplex)
        assertThrows(IOException::class.java) { repository.save(tooComplex) }
        assertArrayEquals(bytes, manifest.readBytes())
        assertEquals(revisionNames, File(directory, "revisions").listFiles()!!.map { it.name }.toSet())
        assertEquals(project, repository.load(project.id))
    }

    @Test fun `untrusted identifiers staging paths and symlink sources cannot escape storage`() {
        val repository = repository()
        val project = commit(repository, repository.create("Percorsi"), "media", recording = true)
        assertThrows(IOException::class.java) { repository.load("../outside") }
        assertThrows(IOException::class.java) { repository.projectDirectory("/tmp/outside") }
        assertThrows(IllegalArgumentException::class.java) { repository.stageFile(project.id, "../mp4") }
        val outside = File(temporary.root, "outside.mp4").apply { writeText("outside") }
        assertThrows(IOException::class.java) { repository.commitSource(project.id, outside, metadata(), true) }
        val source = repository.sourceFile(project.id, project.sources.single().id)
        source.delete()
        Files.createSymbolicLink(source.toPath(), outside.toPath())
        assertEquals(project, repository.load(project.id))
        assertThrows(IOException::class.java) { repository.sourceFile(project.id, project.sources.single().id) }
        assertEquals("outside", outside.readText())
    }

    @Test fun `two repository instances serialize concurrent source commits without lost captures`() {
        val repository = repository()
        val project = repository.create("Concorrenza")
        val files = (0 until 8).map { repository.stageFile(project.id).apply { writeText("take $it") } }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = files.map { file -> Callable { repository().commitSource(project.id, file, metadata(), recording = true) } }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally { executor.shutdownNow() }
        val loaded = repository.load(project.id)
        assertEquals(8, loaded.sources.size)
        assertEquals(8, loaded.captureLedger.size)
        assertEquals(8, loaded.clips.size)
        assertEquals((0 until 8).map { "take $it" }.toSet(), loaded.sources.map { repository.sourceFile(project.id, it.id).readText() }.toSet())
    }

    @Test fun `old schema one manifests without ancestry migrate deterministically and remain editable`() {
        val repository = repository()
        val project = repository.create("Compatibilità")
        val manifest = File(repository.projectDirectory(project.id), "manifest.json")
        val json = Json { encodeDefaults = true }
        val obj = json.parseToJsonElement(manifest.readText()) as JsonObject
        manifest.writeText(JsonObject(obj - "revisionId" - "ancestorRevisionIds").toString())
        val migrated = repository.load(project.id)
        assertEquals(migrated.revisionId, repository().load(project.id).revisionId)
        val renamed = ProjectEditor.rename(migrated, "Migrato")
        repository.save(renamed)
        assertEquals(renamed, repository.load(project.id))
    }

    @Test fun `hostile manifest nesting invalid utf8 and huge fields fail before object allocation`() {
        val repository = repository()
        val project = repository.create("Limiti JSON")
        listOf(
            ("[".repeat(1_000) + "0" + "]".repeat(1_000)).toByteArray(),
            "{\"title\":\"${"x".repeat(8_193)}\"}".toByteArray(),
            byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d),
        ).forEachIndexed { index, manifest ->
            val archive = File(temporary.root, "hostile-json-$index.zip")
            zip(archive, mapOf("manifest.json" to manifest))
            assertThrows(IOException::class.java) { repository.importArchive(archive) }
        }
        assertEquals(listOf(project), repository.listProjects())
    }

    @Test fun `invalid source receipt is retained without changing valid edit state or deleting media`() {
        val repository = repository()
        val project = repository.create("Ricevuta non valida")
        val pending = repository.stageFile(project.id).apply { writeText("recuperabile manualmente") }
        val transactions = File(repository.projectDirectory(project.id), "transactions")
        val receipt = File(transactions, "${UUID.randomUUID()}.json").apply { writeText("invalid receipt") }
        assertEquals(project, repository.load(project.id))
        assertTrue(receipt.exists())
        assertEquals("recuperabile manualmente", pending.readText())
        assertTrue(repository.recoveryWarnings().any { "Recupera video interrotti" in it.message })
    }

    @Test fun `symlink pending directory and non-UUID staged names are rejected`() {
        val repository = repository()
        val project = repository.create("Staging sicuro")
        val pendingDirectory = File(repository.projectDirectory(project.id), "pending")
        val arbitrary = File(pendingDirectory, "arbitrary.mp4").apply { writeText("arbitrary") }
        assertThrows(IOException::class.java) { repository.commitSource(project.id, arbitrary, metadata(), true) }
        arbitrary.delete()
        pendingDirectory.delete()
        val outsideDirectory = temporary.newFolder("outside-staging")
        val outside = File(outsideDirectory, "${UUID.randomUUID()}.mp4").apply { writeText("outside") }
        Files.createSymbolicLink(pendingDirectory.toPath(), outsideDirectory.toPath())
        assertThrows(IOException::class.java) { repository.commitSource(project.id, outside, metadata(), true) }
        assertEquals("outside", outside.readText())
    }

    private fun metadata(sha256: String = "") = SourceAsset("placeholder", "ignored.mp4", 3_000_000, 1920, 1080, sha256 = sha256)

    private fun commit(repository: ProjectRepository, project: Project, bytes: String, recording: Boolean): Project {
        val staged = repository.stageFile(project.id).apply { writeText(bytes) }
        return repository.commitSource(project.id, staged, metadata(), recording)
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unzip(file: File): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes()
            zip.closeEntry()
        } }
        return entries
    }

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
}
