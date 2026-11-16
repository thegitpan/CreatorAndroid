package it.panebarco.creator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.media.DeviceDiagnostics
import it.panebarco.creator.media.MediaProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Destination { LIBRARY, EDITOR, RECORDER, ORIGINALS, DIAGNOSTICS }
data class CreatorState(
    val projects: List<Project> = emptyList(),
    val trashed: List<Project> = emptyList(),
    val project: Project? = null,
    val destination: Destination = Destination.LIBRARY,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
    val diagnostics: String? = null,
)

class CreatorModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as CreatorApplication
    val repository get() = app.repository
    private val gate = Mutex()
    private val mutable = MutableStateFlow(CreatorState())
    val state = mutable.asStateFlow()
    init {
        perform {
            updateLists()
            val id = saved.get<String>("selectedProject")
            val destination = saved.get<String>("destination")?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: Destination.LIBRARY
            if (id != null && destination != Destination.LIBRARY && destination != Destination.DIAGNOSTICS) {
                val project = withContext(Dispatchers.IO) { if (repository.isInTrash(id)) null else repository.load(id) }
                if (project != null) select(project, destination)
            }
        }
    }
    private fun select(project: Project, destination: Destination) {
        saved["selectedProject"] = project.id
        saved["destination"] = destination.name
        mutable.value = mutable.value.copy(project = project, destination = destination)
    }

    private fun perform(operation: suspend () -> Unit) {
        viewModelScope.launch {
            gate.withLock {
                mutable.value = mutable.value.copy(busy = true, error = null)
                try { operation() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Operazione non riuscita. Riprova.") }
                finally { mutable.value = mutable.value.copy(busy = false) }
            }
        }
    }
    private suspend fun lists() = withContext(Dispatchers.IO) {
        val projects = repository.listProjects()
        val activeIds = projects.map { it.id }.toSet()
        val trash = repository.listProjects(includeTrash = true).filter { it.id !in activeIds }
        Triple(projects, trash, repository.recoveryWarnings().map { it.message }.distinct())
    }
    private suspend fun updateLists() {
        val (projects, trash, warnings) = lists()
        mutable.value = mutable.value.copy(projects = projects, trashed = trash, warnings = warnings)
    }
    fun refresh() = perform { updateLists() }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun navigate(destination: Destination) { saved["destination"] = destination.name; mutable.value = mutable.value.copy(destination = destination) }
    fun create(title: String, record: Boolean = false) = perform {
        val project = withContext(Dispatchers.IO) { repository.create(title.trim()) }
        updateLists()
        select(project, if (record) Destination.RECORDER else Destination.EDITOR)
    }
    fun open(id: String, destination: Destination = Destination.EDITOR) = perform {
        val project = withContext(Dispatchers.IO) { repository.load(id) }
        val warnings = withContext(Dispatchers.IO) { repository.recoveryWarnings().map { it.message }.distinct() }
        select(project, destination)
        mutable.value = mutable.value.copy(warnings = warnings)
    }
    fun edit(transform: (Project) -> Project) = perform {
        val id = mutable.value.project?.id ?: return@perform
        val project = withContext(Dispatchers.IO) {
            val fresh = repository.load(id)
            transform(fresh).also { repository.save(it) }
        }
        mutable.value = mutable.value.copy(project = project)
        updateLists()
    }
    fun rename(id: String, title: String) = perform {
        val renamed = withContext(Dispatchers.IO) { ProjectEditor.rename(repository.load(id), title.trim()).also(repository::save) }
        if (mutable.value.project?.id == id) mutable.value = mutable.value.copy(project = renamed)
        updateLists()
    }
    fun trash(id: String) = perform {
        withContext(Dispatchers.IO) { repository.moveToTrash(id) }
        updateLists()
        if (mutable.value.project?.id == id) { saved["selectedProject"] = null; navigate(Destination.LIBRARY); mutable.value = mutable.value.copy(project = null) }
    }
    fun restore(id: String) = perform {
        withContext(Dispatchers.IO) { repository.restore(id) }
        updateLists()
        mutable.value = mutable.value.copy(message = "Progetto ripristinato con originali e cronologia.")
    }
    fun importVideos(projectId: String, uris: List<Uri>) = perform {
        val id = projectId
        withContext(Dispatchers.IO) { require(!repository.isInTrash(id)) { "Il progetto è nel cestino. Ripristinalo prima di importare." }; repository.load(id) }
        var saved: Project? = null
        var count = 0
        try {
            for (uri in uris) {
                saved = withContext(Dispatchers.IO) {
                    val staged = repository.stageFile(id, "mp4")
                    try {
                        app.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Il provider non ha aperto il video. Scaricalo sul dispositivo e riprova." }
                            staged.outputStream().use { input.copyTo(it) }
                        }
                        val metadata = MediaProbe.inspect(staged)
                        repository.commitSource(id, staged, metadata, recording = false)
                    } catch (e: Exception) {
                        // A failed import is retained in staging for recovery, never reported as complete.
                        throw e
                    }
                }
                count++
                select(requireNotNull(saved), Destination.EDITOR)
            }
        } finally { updateLists() }
        mutable.value = mutable.value.copy(message = "$count video importati; gli originali sono nel progetto.")
    }
    suspend fun prepareRecording(projectId: String): File = withContext(Dispatchers.IO) { repository.stageFile(projectId, "mp4") }
    fun finalizeRecording(projectId: String, result: Result<File>) = perform {
        val file = result.getOrThrow()
        val project = withContext(Dispatchers.IO) { repository.commitSource(projectId, file, MediaProbe.inspect(file), recording = true) }
        if (mutable.value.project?.id == projectId) mutable.value = mutable.value.copy(project = project)
        updateLists()
        mutable.value = mutable.value.copy(message = "Ripresa salvata. Puoi continuare ora o in seguito.")
    }
    fun exportArchive(uri: Uri, projectId: String) = perform {
        withContext(Dispatchers.IO) {
            val temporary = File(app.cacheDir, "backup-${UUID.randomUUID()}.zip")
            try {
                repository.exportArchive(projectId, temporary)
                app.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Impossibile scrivere l’archivio nella posizione scelta." }
                    temporary.inputStream().use { it.copyTo(output) }
                }
            } finally { temporary.delete() }
        }
        mutable.value = mutable.value.copy(message = "Backup salvato con originali e cronologia.")
    }
    fun importArchive(uri: Uri) = perform {
        val project = withContext(Dispatchers.IO) {
            val temporary = File(app.cacheDir, "restore-${UUID.randomUUID()}.zip")
            try {
                app.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Impossibile aprire il backup scelto." }
                    temporary.outputStream().use { input.copyTo(it) }
                }
                repository.importArchive(temporary)
            } finally { temporary.delete() }
        }
        updateLists()
        select(project, Destination.EDITOR)
        mutable.value = mutable.value.copy(message = "Backup verificato e importato come nuovo progetto.")
    }
    fun recoverPending(projectId: String) = perform {
        val files = withContext(Dispatchers.IO) { repository.pendingMediaFiles(projectId) }
        if (files.isEmpty()) { mutable.value = mutable.value.copy(message = "Nessuna ripresa interrotta da recuperare."); return@perform }
        var count = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            try {
                val project = withContext(Dispatchers.IO) { repository.commitSource(projectId, file, MediaProbe.inspect(file), recording = true) }
                if (mutable.value.project?.id == projectId) mutable.value = mutable.value.copy(project = project)
                count++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += e.message ?: "File non ancora completo" }
        }
        updateLists()
        mutable.value = mutable.value.copy(message = "$count riprese recuperate.", error = failures.takeIf { it.isNotEmpty() }?.let { "Alcuni file interrotti non sono riproducibili e sono stati conservati. ${it.joinToString("; ")}" })
    }
    fun diagnostics() = perform {
        val report = withContext(Dispatchers.IO) { DeviceDiagnostics.collect(app) }
        mutable.value = mutable.value.copy(diagnostics = report, destination = Destination.DIAGNOSTICS)
    }
    fun setCanvas(canvas: CanvasShape) = edit { ProjectEditor.setCanvas(it, canvas) }
}
