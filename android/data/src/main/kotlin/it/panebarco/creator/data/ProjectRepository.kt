package it.panebarco.creator.data

import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.SourceAsset
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class RepositoryWarning(val projectId: String, val message: String)

/** Useful for both tests and diagnostics; failure never removes a committed source. */
internal enum class SaveCheckpoint {
    RECEIPT_SYNCED, SOURCE_MOVED, REVISION_SYNCED, BEFORE_MANIFEST_REPLACE, MANIFEST_REPLACED, BEFORE_ARCHIVE_PUBLISH,
    ARCHIVE_DESTINATION_CREATED, ARCHIVE_COPYING, ARCHIVE_COPY_CLOSED,
}

@Serializable
private data class SourceCommitReceipt(val source: SourceAsset, val stagedName: String, val recording: Boolean)

/**
 * Synchronous, process/thread-serialized storage. Call from Dispatchers.IO.
 * All original sources (also those only referenced by Undo/Redo) remain immutable.
 */
class ProjectRepository internal constructor(
    root: File,
    private val checkpoint: (SaveCheckpoint) -> Unit,
) {
    constructor(root: File) : this(root, {})

    private val root = root.canonicalFile
    private val processLock = locks.computeIfAbsent(this.root.path) { Any() }
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
    private val memoryWarnings = linkedMapOf<String, RepositoryWarning>()

    fun listProjects(includeTrash: Boolean = false): List<Project> = locked {
        val parents = if (includeTrash) listOf(projects(), trash()) else listOf(projects())
        val result = mutableListOf<Project>()
        parents.forEach { parent ->
            parent.listFiles()?.sortedBy { it.name }?.forEach { directory ->
                if (directory.name.matches(UUID_PATTERN)) {
                    try {
                        result += loadUnlocked(directory.name)
                    } catch (error: IOException) {
                        warnUnlocked(directory.name, "Impossibile aprire il progetto: ${error.message.orEmpty()}")
                    } catch (error: IllegalArgumentException) {
                        warnUnlocked(directory.name, "Progetto non valido: ${error.message.orEmpty()}")
                    }
                } else {
                    warnUnlocked(INVENTORY_WARNING_ID, "Una cartella della libreria ha un nome non valido; i suoi dati sono conservati.")
                }
            } ?: throw IOException("Impossibile leggere la libreria dei progetti.")
        }
        result.sortedWith(compareByDescending<Project> { it.updatedAtMs }.thenBy { it.id })
    }

    fun create(title: String, canvas: CanvasShape = CanvasShape.PORTRAIT): Project = locked {
        val project = Project.create(title, canvas)
        val directory = child(projects(), project.id)
        if (!directory.mkdir()) throw IOException("Impossibile creare la cartella del progetto. Controlla lo spazio disponibile.")
        initializeProjectDirectory(directory)
        writeManifestUnlocked(directory, project)
        project
    }

    fun load(id: String): Project = locked { loadUnlocked(id) }

    fun save(project: Project) = locked {
        ProjectEditor.validate(project)
        val directory = locateProject(project.id)
        if (isTrashedUnlocked(project.id)) throw IOException("Ripristina il progetto dal cestino prima di modificarlo.")
        val current = loadUnlocked(project.id)
        verifySaveAgainstCurrent(project, current, directory)
        if (project != current) writeManifestUnlocked(directory, project)
    }

    fun sourceFile(projectId: String, sourceId: String): File = locked {
        checkId(sourceId)
        val project = loadUnlocked(projectId, checkPending = false)
        val source = project.sources.find { it.id == sourceId }
            ?: throw IOException("Sorgente non trovata nel progetto.")
        checkedSource(locateProject(projectId), source)
    }

    fun projectDirectory(id: String): File = locked { locateProject(id) }

    fun isInTrash(id: String): Boolean = locked {
        checkId(id)
        locateProject(id)
        isTrashedUnlocked(id)
    }

    fun moveToTrash(id: String) = locked { moveProjectUnlocked(id, toTrash = true) }

    fun restore(id: String) = locked { moveProjectUnlocked(id, toTrash = false) }

    fun recoveryWarnings(): List<RepositoryWarning> = locked {
        val files = try { warningsDirectory().listFiles().orEmpty() } catch (_: IOException) { emptyArray() }
        files.filter { it.extension == "json" }.forEach { file ->
            try {
                val warning = json.decodeFromString<RepositoryWarning>(readLimited(file, MAX_MANIFEST_BYTES))
                if (warning.projectId.matches(UUID_PATTERN) || warning.projectId == INVENTORY_WARNING_ID) {
                    val current = memoryWarnings[warning.projectId]
                    if (current == null) memoryWarnings[warning.projectId] = warning
                    else if (!current.message.contains(warning.message)) memoryWarnings[warning.projectId] = current.copy(
                        message = (warning.message + "\n" + current.message).takeLast(8_000),
                    )
                }
            } catch (_: Exception) {
                memoryWarnings[INVENTORY_WARNING_ID] = RepositoryWarning(
                    INVENTORY_WARNING_ID, "Un avviso di recupero non è leggibile; i progetti sono conservati.",
                )
            }
        }
        memoryWarnings.values.toList()
    }

    fun stageFile(projectId: String, extension: String = "mp4"): File = locked {
        checkId(projectId)
        loadUnlocked(projectId)
        if (isTrashedUnlocked(projectId)) throw IOException("Ripristina il progetto prima di aggiungere video.")
        val suffix = extension.lowercase(Locale.ROOT)
        require(suffix.matches(EXTENSION_PATTERN)) { "Estensione del video non valida." }
        val pending = ensureDirectory(child(locateProject(projectId), PENDING))
        val file = child(pending, "${UUID.randomUUID()}.$suffix")
        FileOutputStream(file).use { it.fd.sync() }
        syncDirectory(pending)
        file
    }

    /** Explicitly recover these with MediaProbe + commitSource; a crashed muxer may not have finalized them. */
    fun pendingMediaFiles(projectId: String): List<File> = locked {
        loadUnlocked(projectId)
        pendingMediaFilesUnlocked(locateProject(projectId))
    }

    fun commitSource(projectId: String, staged: File, metadata: SourceAsset, recording: Boolean): Project = locked {
        val project = loadUnlocked(projectId, checkPending = false)
        if (isTrashedUnlocked(projectId)) throw IOException("Ripristina il progetto prima di aggiungere video.")
        ProjectEditor.validateMediaMetadata(metadata)
        val directory = locateProject(projectId)
        val pending = child(directory, PENDING)
        rejectSymlink(pending)
        rejectSymlink(staged)
        if (!staged.name.matches(STAGED_MEDIA_PATTERN) || staged.canonicalFile.parentFile != pending.canonicalFile ||
            !staged.isFile || staged.length() <= 0L) {
            throw IOException("File temporaneo non valido. Importa o registra nuovamente il video.")
        }
        val extension = staged.extension.lowercase(Locale.ROOT)
        require(extension.matches(EXTENSION_PATTERN)) { "Estensione del video non valida." }
        val hash = sha256(staged)
        if (metadata.sha256.isNotBlank() && (!metadata.sha256.matches(SHA_PATTERN) || metadata.sha256 != hash)) {
            throw IOException("Il video è cambiato durante l'importazione: checksum non corrispondente.")
        }
        val id = UUID.randomUUID().toString()
        val source = metadata.copy(id = id, fileName = "$id.$extension", sha256 = hash)
        val next = if (recording) ProjectEditor.appendRecording(project, source) else ProjectEditor.appendImport(project, source)
        val destination = child(child(directory, SOURCES), source.fileName)
        FileOutputStream(staged, true).use { it.fd.sync() }
        val receipts = ensureDirectory(child(directory, TRANSACTIONS))
        val receiptFile = child(receipts, "$id.json")
        atomicWrite(receiptFile, json.encodeToString(SourceCommitReceipt(source, staged.name, recording)).toByteArray(), pending)
        checkpoint(SaveCheckpoint.RECEIPT_SYNCED)
        atomicMove(staged, destination, replace = false)
        syncDirectory(pending)
        syncDirectory(destination.parentFile)
        checkpoint(SaveCheckpoint.SOURCE_MOVED)
        // A failure after the move intentionally leaves a recoverable original in Sources.
        verifySaveAgainstCurrent(next, project, directory, verifiedNewSources = setOf(source.id))
        writeManifestUnlocked(directory, next)
        Files.delete(receiptFile.toPath())
        syncDirectory(receipts)
        next
    }

    fun exportArchive(id: String, destination: File) = locked {
        val project = loadUnlocked(id)
        val directory = locateProject(id)
        rejectDestinationInsideRepository(destination)
        val parent = destination.canonicalFile.parentFile ?: throw IOException("Destinazione del backup non valida.")
        ensureDirectory(parent)
        rejectSymlink(destination)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("La destinazione del backup esiste già. Scegli un nuovo nome.")
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            val manifest = encodeProject(project)
            if (project.sources.size >= MAX_ARCHIVE_ENTRIES) throw IOException("Il progetto contiene troppi file per il backup.")
            var expandedSize = manifest.size.toLong()
            FileOutputStream(temporary).use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(manifest)
                    zip.closeEntry()
                    project.sources.forEach { source ->
                        val file = checkedSource(directory, source)
                        expandedSize = checkedTotal(expandedSize, file.length())
                        zip.putNextEntry(ZipEntry("$SOURCES/${source.fileName}"))
                        val checksum = MessageDigest.getInstance("SHA-256")
                        FileInputStream(file).use { input -> copyBounded(input, zip, MAX_ARCHIVE_BYTES, checksum) }
                        zip.closeEntry()
                        if (checksum.hex() != source.sha256) throw IOException("Sorgente danneggiata: ${source.fileName}. Backup annullato.")
                    }
                    zip.finish()
                    zip.flush()
                    output.fd.sync()
                }
            }
            checkpoint(SaveCheckpoint.BEFORE_ARCHIVE_PUBLISH)
            publishArchiveExclusive(temporary, destination, parent)
        } finally {
            temporary.delete()
        }
    }

    fun importArchive(archive: File): Project = locked {
        rejectSymlink(archive)
        if (!archive.isFile || archive.length() <= 0 || archive.length() > MAX_ARCHIVE_BYTES) {
            throw IOException("Backup assente, vuoto o oltre il limite di 32 GiB.")
        }
        val incoming = ensureDirectory(child(root, INCOMING))
        val newId = UUID.randomUUID().toString()
        val staging = child(incoming, newId)
        if (!staging.mkdir()) throw IOException("Impossibile preparare il ripristino del backup.")
        try {
            val seen = mutableSetOf<String>()
            var count = 0
            var expanded = 0L
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ARCHIVE_ENTRIES) throw IOException("Il backup contiene troppi file.")
                    val name = validateArchiveName(entry)
                    if (!seen.add(name.lowercase(Locale.ROOT))) throw IOException("Il backup contiene nomi di file duplicati.")
                    if (entry.isDirectory) {
                        // closeEntry drains remaining data, so a directory must reach EOF before calling it.
                        // Even one hidden byte is invalid; never inflate an uncounted directory payload.
                        if (entry.size > 0L || zip.read() != -1) throw IOException("Una cartella del backup contiene dati non consentiti.")
                        ensureDirectory(child(staging, SOURCES))
                    } else {
                        val limit = if (name == MANIFEST) MAX_MANIFEST_BYTES else MAX_ARCHIVE_BYTES - expanded
                        if (entry.size > limit && entry.size >= 0) throw IOException("File del backup oltre il limite consentito.")
                        val target = if (name == MANIFEST) child(staging, MANIFEST) else {
                            child(ensureDirectory(child(staging, SOURCES)), name.removePrefix("$SOURCES/"))
                        }
                        FileOutputStream(target).use { output ->
                            expanded = checkedTotal(expanded, copyBounded(zip, output, limit))
                            output.fd.sync()
                        }
                    }
                    zip.closeEntry()
                }
            }
            val original = readProject(child(staging, MANIFEST))
            val expectedNames = original.sources.map { "$SOURCES/${it.fileName}".lowercase(Locale.ROOT) }.toSet() + MANIFEST
            if (seen.filterNot { it == "$SOURCES/".lowercase(Locale.ROOT) }.toSet() != expectedNames) {
                throw IOException("Il backup contiene sorgenti mancanti o file non previsti.")
            }
            verifyProjectFiles(staging, original, verifyChecksums = true)
            val restored = original.copy(id = newId, updatedAtMs = maxOf(System.currentTimeMillis(), original.updatedAtMs))
            ProjectEditor.validate(restored)
            initializeProjectDirectory(staging)
            writeManifestUnlocked(staging, restored)
            val target = child(projects(), newId)
            atomicMove(staging, target, replace = false)
            syncDirectory(incoming)
            syncDirectory(projects())
            restored
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Backup non valido. Il progetto esistente non è stato modificato.", error)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun loadUnlocked(id: String, checkPending: Boolean = true): Project {
        val directory = locateProject(id)
        val manifest = child(directory, MANIFEST)
        var failure: Exception? = null
        val current = try {
            val project = readProject(manifest)
            if (project.id != id) throw IOException("L'identificativo del manifest non corrisponde alla cartella.")
            project
        } catch (error: UnsupportedProjectSchemaException) {
            throw error
        } catch (error: Exception) {
            failure = error
            null
        }
        // Missing media and receipt I/O failures must never downgrade a valid edit manifest.
        if (current != null) return finishLoadUnlocked(directory, current, checkPending)
        val revisions = child(directory, REVISIONS)
        rejectSymlink(revisions)
        val candidates = revisions.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedWith(compareByDescending<File> { it.name }) ?: emptyList()
        for (revision in candidates) {
            val recovered = try {
                readProject(revision).also { project ->
                    if (project.id != id) throw IOException("Revisione appartenente a un altro progetto.")
                }
            } catch (error: UnsupportedProjectSchemaException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            val damaged = ensureDirectory(child(directory, DAMAGED))
            if (Files.exists(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlink(manifest)
                atomicMove(manifest, child(damaged, "manifest-${System.currentTimeMillis()}-${UUID.randomUUID()}.json"), false)
                syncDirectory(damaged)
            }
            warnUnlocked(id, "Manifest danneggiato: recuperata una revisione valida. Controlla l'ultimo montaggio; l'originale danneggiato è conservato.")
            writeManifestUnlocked(directory, recovered)
            return finishLoadUnlocked(directory, recovered, checkPending)
        }
        throw IOException("Progetto danneggiato: nessuna revisione valida. Conserva la cartella e prova un backup.", failure)
    }

    private fun finishLoadUnlocked(directory: File, initial: Project, checkPending: Boolean): Project {
        val project = replayReceiptsUnlocked(directory, initial)
        val missing = project.sources.filter { source ->
            try { checkedSource(directory, source); false } catch (_: IOException) { true }
        }
        if (missing.isNotEmpty()) warnUnlocked(project.id,
            "${missing.size} sorgenti originali mancanti o non accessibili. Il montaggio e tutta la cronologia sono conservati; ripristina un backup prima dell'export.")
        val pending = pendingMediaFilesUnlocked(directory)
        if (checkPending && pending.isNotEmpty()) warnUnlocked(project.id,
            "${pending.size} video temporanei da verificare. Usa Recupera video negli originali; una ripresa interrotta potrebbe non essere leggibile.")
        return project
    }

    private fun pendingMediaFilesUnlocked(directory: File): List<File> {
        val pending = child(directory, PENDING)
        rejectSymlink(pending)
        return pending.listFiles()?.filter { file ->
            file.name.matches(STAGED_MEDIA_PATTERN) && !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() > 0
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun replayReceiptsUnlocked(directory: File, initial: Project): Project {
        val transactions = child(directory, TRANSACTIONS)
        rejectSymlink(transactions)
        if (!transactions.exists()) return initial
        var project = initial
        transactions.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { receiptFile ->
            try {
                val text = readLimited(receiptFile, MAX_RECEIPT_BYTES)
                validateJsonComplexity(text)
                val receipt = json.decodeFromString<SourceCommitReceipt>(text)
                if (!receipt.stagedName.matches(STAGED_MEDIA_PATTERN) || receiptFile.name != "${receipt.source.id}.json") {
                    throw IOException("Ricevuta di acquisizione non valida.")
                }
                // Validate the complete source without trusting the receipt's path or metadata.
                ProjectEditor.appendRecording(Project.create("Verifica acquisizione"), receipt.source)
                val existing = project.sources.find { it.id == receipt.source.id }
                if (existing != null) {
                    if (existing != receipt.source) throw IOException("Ricevuta in conflitto con una sorgente già presente.")
                    if (sha256(checkedSource(directory, existing)) != existing.sha256) throw IOException("Checksum della sorgente acquisita non valido.")
                } else {
                    val sourceFile = child(child(directory, SOURCES), receipt.source.fileName)
                    val stagedFile = child(child(directory, PENDING), receipt.stagedName)
                    rejectSymlink(sourceFile)
                    rejectSymlink(stagedFile)
                    val candidate = if (sourceFile.isFile) sourceFile else stagedFile
                    if (!candidate.isFile || candidate.length() <= 0 || sha256(candidate) != receipt.source.sha256) {
                        throw IOException("Il video dell'acquisizione interrotta è mancante o non integro.")
                    }
                    if (candidate == stagedFile) {
                        atomicMove(stagedFile, sourceFile, replace = false)
                        syncDirectory(stagedFile.parentFile)
                        syncDirectory(sourceFile.parentFile)
                    }
                    val next = if (receipt.recording) ProjectEditor.appendRecording(project, receipt.source)
                    else ProjectEditor.appendImport(project, receipt.source)
                    writeManifestUnlocked(directory, next)
                    project = next
                }
                Files.delete(receiptFile.toPath())
                syncDirectory(transactions)
                warnUnlocked(project.id, "Recuperato un video dopo un salvataggio interrotto. Controlla il montaggio prima dell'export.")
            } catch (error: Exception) {
                // A directory sync may fail after the manifest replacement; retain its exact saved state.
                try {
                    val saved = readProject(child(directory, MANIFEST))
                    if (saved.id == project.id) project = saved
                } catch (_: Exception) { /* Keep the last validated state. */ }
                warnUnlocked(project.id, "Acquisizione interrotta conservata ma non recuperabile automaticamente: ${error.message.orEmpty()}")
            }
        }
        return project
    }

    private fun pruneRevisionsUnlocked(directory: File, projectId: String) {
        val revisions = child(directory, REVISIONS)
        val validated = revisions.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.name }?.filter { file ->
                try { readProject(file).id == projectId } catch (_: Exception) { false }
            } ?: return
        var removed = false
        validated.drop(RETAINED_REVISIONS).forEach { file ->
            try { removed = Files.deleteIfExists(file.toPath()) || removed } catch (_: IOException) { /* metadata only */ }
        }
        if (removed) syncDirectory(revisions)
    }

    private fun readProject(file: File): Project {
        val text = readLimited(file, MAX_MANIFEST_BYTES)
        validateJsonComplexity(text)
        try {
            val tree = json.parseToJsonElement(text).jsonObject
            val schema = tree["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            if (schema != Project.CURRENT_SCHEMA_VERSION) throw UnsupportedProjectSchemaException(schema)
            val decoded = json.decodeFromString<Project>(text)
            // Schema-1 manifests written before revision ancestry get a deterministic migration identity.
            val project = if (tree["revisionId"] == null) decoded.copy(
                revisionId = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString(),
            ) else decoded
            return project.also(ProjectEditor::validate)
        } catch (error: UnsupportedProjectSchemaException) {
            throw error
        } catch (error: SerializationException) {
            throw IOException("Manifest del progetto non leggibile.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Manifest del progetto non valido: ${error.message}", error)
        }
    }

    private fun verifySaveAgainstCurrent(
        project: Project, current: Project, directory: File, verifiedNewSources: Set<String> = emptySet(),
    ) {
        require(project.id == current.id && project.createdAtMs == current.createdAtMs) {
            "L'identità del progetto non può essere modificata."
        }
        if (project.updatedAtMs < current.updatedAtMs || (project.updatedAtMs == current.updatedAtMs && project != current)) {
            throw IOException("Il progetto è cambiato. Riaprilo prima di salvare la modifica.")
        }
        if (project != current && (current.revisionId !in project.ancestorRevisionIds ||
                project.ancestorRevisionIds.take(current.ancestorRevisionIds.size) != current.ancestorRevisionIds)) {
            throw IOException("Il progetto è stato modificato da un'altra operazione. Riaprilo prima di salvare.")
        }
        val incoming = project.sources.associateBy { it.id }
        current.sources.forEach { source ->
            if (incoming[source.id] != source) throw IOException("Una sorgente originale è stata rimossa o modificata. Salvataggio annullato.")
        }
        if (project.captureLedger.take(current.captureLedger.size) != current.captureLedger) {
            throw IOException("Il registro delle riprese non può essere rimosso o riscritto.")
        }
        verifyProjectFiles(directory, project)
        val previousIds = current.sources.map { it.id }.toSet()
        project.sources.filterNot { it.id in previousIds || it.id in verifiedNewSources }.forEach { source ->
            if (sha256(checkedSource(directory, source)) != source.sha256) throw IOException("Checksum della nuova sorgente non valido.")
        }
    }

    private fun writeManifestUnlocked(directory: File, project: Project) {
        val bytes = encodeProject(project)
        val pending = ensureDirectory(child(directory, PENDING))
        val revisions = ensureDirectory(child(directory, REVISIONS))
        val nonce = UUID.randomUUID().toString()
        val revision = child(revisions, "%019d-%s.json".format(Locale.ROOT, project.updatedAtMs, nonce))
        atomicWrite(revision, bytes, pending)
        checkpoint(SaveCheckpoint.REVISION_SYNCED)
        checkpoint(SaveCheckpoint.BEFORE_MANIFEST_REPLACE)
        atomicWrite(child(directory, MANIFEST), bytes, pending)
        checkpoint(SaveCheckpoint.MANIFEST_REPLACED)
        pruneRevisionsUnlocked(directory, project.id)
    }

    private fun encodeProject(project: Project): ByteArray {
        ProjectEditor.validate(project)
        val text = json.encodeToString(project)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_MANIFEST_BYTES) throw IOException("Progetto troppo grande: manifest oltre 16 MiB.")
        validateJsonComplexity(text)
        return bytes
    }

    private fun verifyProjectFiles(directory: File, project: Project, verifyChecksums: Boolean = false) {
        project.sources.forEach { source ->
            val file = checkedSource(directory, source)
            if (verifyChecksums && sha256(file) != source.sha256) throw IOException("Checksum della sorgente non valido: ${source.fileName}.")
        }
    }

    private fun checkedSource(directory: File, source: SourceAsset): File {
        val sources = child(directory, SOURCES)
        rejectSymlink(sources)
        val file = child(sources, source.fileName)
        rejectSymlink(file)
        if (!file.isFile || file.length() <= 0) throw IOException("Sorgente originale mancante o vuota: ${source.fileName}.")
        return file
    }

    private fun moveProjectUnlocked(id: String, toTrash: Boolean) {
        checkId(id)
        val directory = locateProject(id)
        val targetParent = if (toTrash) trash() else projects()
        if (directory.parentFile == targetParent) return
        loadUnlocked(id)
        val destination = child(targetParent, id)
        if (destination.exists()) throw IOException("Esiste già un progetto con questo identificativo nella destinazione.")
        atomicMove(directory, destination, replace = false)
        syncDirectory(directory.parentFile)
        syncDirectory(targetParent)
    }

    private fun locateProject(id: String): File {
        checkId(id)
        val active = child(projects(), id)
        val deleted = child(trash(), id)
        rejectSymlink(active)
        rejectSymlink(deleted)
        if (active.exists() && deleted.exists()) throw IOException("Due cartelle hanno lo stesso identificativo. I dati sono conservati.")
        return when {
            active.isDirectory -> active
            deleted.isDirectory -> deleted
            else -> throw IOException("Progetto non trovato. Verifica la libreria o ripristina un backup.")
        }
    }

    private fun isTrashedUnlocked(id: String): Boolean = child(trash(), id).isDirectory

    private fun initializeProjectDirectory(directory: File) {
        ensureDirectory(child(directory, SOURCES))
        ensureDirectory(child(directory, PENDING))
        ensureDirectory(child(directory, REVISIONS))
        ensureDirectory(child(directory, TRANSACTIONS))
        syncDirectory(directory)
        syncDirectory(directory.parentFile)
    }

    private fun projects(): File = ensureDirectory(child(root, PROJECTS))
    private fun trash(): File = ensureDirectory(child(root, TRASH))
    private fun warningsDirectory(): File = ensureDirectory(child(root, WARNINGS))

    private fun warnUnlocked(projectId: String, message: String) {
        val persisted = if (memoryWarnings[projectId] == null) try {
            json.decodeFromString<RepositoryWarning>(readLimited(child(warningsDirectory(), "$projectId.json"), MAX_MANIFEST_BYTES)).message
        } catch (_: Exception) { "" } else ""
        val previous = memoryWarnings[projectId]?.message ?: persisted
        val combined = if (previous.isEmpty() || previous.contains(message)) previous.ifEmpty { message }
        else (previous + "\n" + message).takeLast(8_000)
        val warning = RepositoryWarning(projectId, combined)
        memoryWarnings[projectId] = warning
        try {
            val directory = warningsDirectory()
            atomicWrite(child(directory, "$projectId.json"), json.encodeToString(warning).toByteArray(), directory)
        } catch (_: IOException) {
            // Full storage must not hide otherwise valid library entries. The warning remains visible in this process.
        }
    }

    private fun validateArchiveName(entry: ZipEntry): String {
        val name = entry.name
        if (name.isEmpty() || name.contains('\\') || name.contains(':') || name.contains('\u0000') || name.startsWith('/') ||
            name.split('/').any { it == ".." || it == "." }) throw IOException("Percorso non sicuro nel backup.")
        if (entry.isDirectory) {
            if (name != "$SOURCES/") throw IOException("Cartella non prevista nel backup.")
        } else if (name != MANIFEST) {
            if (!name.startsWith("$SOURCES/") || name.removePrefix("$SOURCES/").contains('/') ||
                !name.removePrefix("$SOURCES/").matches(SAFE_FILENAME) || name.contains("..")) {
                throw IOException("File non previsto o nome non sicuro nel backup.")
            }
        }
        return name
    }

    private fun rejectDestinationInsideRepository(destination: File) {
        if (destination.canonicalFile.toPath().startsWith(root.toPath())) {
            throw IOException("Salva il backup fuori dalla libreria dei progetti.")
        }
    }

    /**
     * Android untrusted_app SELinux denies hard links. CREATE_NEW reserves a fresh name without
     * replacing any existing entry; only a fully copied, synced and closed archive is reported.
     * Process death can leave an incomplete external file, which has never been reported successful.
     */
    private fun publishArchiveExclusive(temporary: File, destination: File, parent: File) {
        var created = false
        var ownedFileKey: Any? = null
        try {
            FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                created = true
                ownedFileKey = Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS).fileKey()
                checkpoint(SaveCheckpoint.ARCHIVE_DESTINATION_CREATED)
                FileInputStream(temporary).channel.use { input ->
                    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Backup annullato.")
                        buffer.clear()
                        if (input.read(buffer) < 0) break
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                        checkpoint(SaveCheckpoint.ARCHIVE_COPYING)
                    }
                }
                output.force(true)
            }
            checkpoint(SaveCheckpoint.ARCHIVE_COPY_CLOSED)
            if (ownedFileKey != null && !matchesOwnedFile(destination, ownedFileKey)) {
                throw IOException("La destinazione del backup è cambiata durante il salvataggio.")
            }
            syncDirectory(parent)
        } catch (error: Throwable) {
            // Some Android providers return a null fileKey: preserve the uncertain file in that case.
            if (created && ownedFileKey != null && matchesOwnedFile(destination, ownedFileKey)) {
                try { Files.deleteIfExists(destination.toPath()); syncDirectory(parent) } catch (_: IOException) { /* retain safely */ }
            }
            throw error
        }
    }

    private fun matchesOwnedFile(file: File, fileKey: Any): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attributes.isRegularFile && attributes.fileKey() == fileKey
    } catch (_: IOException) { false }

    private fun <T> locked(operation: () -> T): T = synchronized(processLock) {
        ensureDirectory(root)
        val lockFile = child(root, ".repository.lock")
        rejectSymlink(lockFile)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { operation() }
        }
    }

    private fun child(parent: File, name: String): File {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Percorso del progetto non sicuro.")
        }
        val result = File(parent, name)
        if (!result.absoluteFile.toPath().normalize().startsWith(root.toPath())) throw IOException("Percorso esterno alla libreria.")
        rejectSymlink(parent)
        return result
    }

    private fun ensureDirectory(directory: File): File {
        rejectSymlink(directory)
        if (!directory.isDirectory && (!directory.mkdirs() || !directory.isDirectory)) {
            throw IOException("Impossibile creare o leggere una cartella. Controlla spazio e permessi.")
        }
        return directory
    }

    private fun rejectSymlink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Collegamento simbolico non consentito nella libreria.")
    }

    private fun checkId(id: String) {
        if (!id.matches(UUID_PATTERN)) throw IOException("Identificativo del progetto o della sorgente non valido.")
    }

    private fun atomicWrite(destination: File, bytes: ByteArray, pending: File) {
        rejectSymlink(destination)
        val temporary = child(pending, "write-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            atomicMove(temporary, destination, replace = true)
            syncDirectory(destination.parentFile)
            if (pending != destination.parentFile) syncDirectory(pending)
        } finally {
            temporary.delete()
        }
    }

    private fun atomicMove(source: File, destination: File, replace: Boolean) {
        rejectSymlink(source)
        rejectSymlink(destination)
        if (!replace && Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Il file o la cartella di destinazione esiste già.")
        }
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Il supporto di archiviazione non permette un salvataggio atomico sicuro.", error)
        }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun readLimited(file: File, maximum: Long): String {
        rejectSymlink(file)
        if (!file.isFile || file.length() <= 0L || file.length() > maximum) throw IOException("Manifest assente, vuoto o troppo grande.")
        val output = java.io.ByteArrayOutputStream()
        FileInputStream(file).use { input -> copyBounded(input, output, maximum) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    /** Bound nesting and collection allocation before kotlinx.serialization builds an object tree. */
    private fun validateJsonComplexity(text: String) {
        var inString = false
        var escaped = false
        var depth = 0
        var tokens = 0
        var stringLength = 0
        text.forEach { character ->
            if (inString) {
                stringLength++
                if (stringLength > 8_192) throw IOException("Manifest con un campo di testo troppo grande.")
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else when (character) {
                '"' -> { inString = true; stringLength = 0; tokens++ }
                '[', '{' -> { depth++; tokens++ }
                ']', '}' -> depth--
                ',', ':' -> tokens++
            }
            if (depth > 64 || tokens > 200_000) throw IOException("Manifest troppo complesso per un ripristino sicuro.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long, digest: MessageDigest? = null): Long {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (copied > maximum - count) throw IOException("Il backup supera il limite di dimensione consentito.")
            copied += count
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
        }
        return copied
    }

    private fun checkedTotal(total: Long, addition: Long): Long {
        if (addition < 0 || total > MAX_ARCHIVE_BYTES - addition) throw IOException("Il backup supera il limite di 32 GiB.")
        return total + addition
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 10_000
        const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
        private const val PROJECTS = "projects"
        private const val TRASH = "trash"
        private const val SOURCES = "Sources"
        private const val PENDING = "pending"
        private const val REVISIONS = "revisions"
        private const val DAMAGED = "damaged"
        private const val WARNINGS = "warnings"
        private const val INCOMING = "incoming"
        private const val TRANSACTIONS = "transactions"
        private const val MANIFEST = "manifest.json"
        private const val RETAINED_REVISIONS = 5
        private const val MAX_RECEIPT_BYTES = 64L * 1024
        private const val INVENTORY_WARNING_ID = "library"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EXTENSION_PATTERN = Regex("[a-z0-9]{1,10}")
        private val STAGED_MEDIA_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-z0-9]{1,10}")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val SAFE_FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

private class UnsupportedProjectSchemaException(version: Int) : IOException(
    "Versione del progetto non supportata ($version). Aggiorna Creator prima di aprirlo.",
)
