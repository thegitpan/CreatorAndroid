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
                if (project != null) select(project, if (destination == Destination.RECORDER && !cameraEntryAllowed()) Destination.EDITOR else destination)
            }
        }
    }
    private fun select(project: Project, destination: Destination) {
        saved["selectedProject"] = project.id
        saved["destination"] = destination.name
        mutable.value = mutable.value.copy(project = project, destination = destination)
    }

    private fun perform(context: AppOperation = AppOperation.LIBRARY, onCompleted: (() -> Unit)? = null, operation: suspend () -> Unit) {
        val job = viewModelScope.launch {
            gate.withLock {
                mutable.value = mutable.value.copy(busy = true, error = null)
                try { operation() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.value = mutable.value.copy(error = AppErrors.describe(e, context)) }
                finally { mutable.value = mutable.value.copy(busy = false) }
            }
        }
        onCompleted?.let { callback -> job.invokeOnCompletion { callback() } }
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
    private fun cameraEntryAllowed(): Boolean {
        if (MediaWorkCoordinator.cameraAllowed() && ExportJobs.state.value.phase != ExportPhase.RUNNING) return true
        reportError(MediaWorkCoordinator.WAIT_FOR_EXPORT)
        return false
    }
    fun navigate(destination: Destination) {
        if (destination == Destination.RECORDER && !cameraEntryAllowed()) return
        saved["destination"] = destination.name
        mutable.value = mutable.value.copy(destination = destination)
    }
    fun create(title: String, record: Boolean = false) {
        if (record && !cameraEntryAllowed()) return
        perform(AppOperation.CREATE_PROJECT) {
            val project = withContext(Dispatchers.IO) { repository.create(title.trim()) }
            updateLists()
            select(project, if (record && cameraEntryAllowed()) Destination.RECORDER else Destination.EDITOR)
        }
    }
    fun open(id: String, destination: Destination = Destination.EDITOR) {
        if (destination == Destination.RECORDER && !cameraEntryAllowed()) return
        perform(AppOperation.OPEN_PROJECT) {
            val project = withContext(Dispatchers.IO) { repository.load(id) }
            val warnings = withContext(Dispatchers.IO) { repository.recoveryWarnings().map { it.message }.distinct() }
            select(project, if (destination == Destination.RECORDER && !cameraEntryAllowed()) Destination.EDITOR else destination)
            mutable.value = mutable.value.copy(warnings = warnings)
        }
    }
    fun edit(transform: (Project) -> Project) = perform(AppOperation.EDIT_PROJECT) {
        val id = mutable.value.project?.id ?: return@perform
        val project = withContext(Dispatchers.IO) {
            val fresh = repository.load(id)
            AppErrors.domainChange { transform(fresh) }.also { repository.save(it) }
        }
        mutable.value = mutable.value.copy(project = project)
        updateLists()
    }
    fun rename(id: String, title: String) = perform(AppOperation.EDIT_PROJECT) {
        val renamed = withContext(Dispatchers.IO) { repository.load(id).let { loaded -> AppErrors.domainChange { ProjectEditor.rename(loaded, title.trim()) } }.also(repository::save) }
        if (mutable.value.project?.id == id) mutable.value = mutable.value.copy(project = renamed)
        updateLists()
    }
    fun trash(id: String) = perform(AppOperation.TRASH_PROJECT) {
        withContext(Dispatchers.IO) { repository.moveToTrash(id) }
        updateLists()
        if (mutable.value.project?.id == id) { saved["selectedProject"] = null; navigate(Destination.LIBRARY); mutable.value = mutable.value.copy(project = null) }
    }
    fun restore(id: String) = perform(AppOperation.RESTORE_PROJECT) {
        withContext(Dispatchers.IO) { repository.restore(id) }
        updateLists()
        mutable.value = mutable.value.copy(message = "Progetto ripristinato con originali e cronologia.")
    }
    fun importVideos(projectId: String, uris: List<Uri>) = perform(AppOperation.IMPORT_VIDEO) {
        val id = projectId
        withContext(Dispatchers.IO) { if (repository.isInTrash(id)) throw UserFeedbackException("Il progetto è nel cestino. Ripristinalo prima di importare."); repository.load(id) }
        var saved: Project? = null
        var count = 0
        try {
            for (uri in uris) {
                saved = withContext(Dispatchers.IO) {
                    val staged = repository.stageFile(id, "mp4", recording = false)
                    try {
                        app.contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw UserFeedbackException("Il provider non ha aperto il video. Scaricalo sul dispositivo e selezionalo di nuovo.")
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
    suspend fun prepareRecording(projectId: String): File = withContext(Dispatchers.IO) { repository.stageFile(projectId, "mp4", recording = true) }
    fun finalizeRecording(projectId: String, result: Result<File>, recordingToken: String? = null) = perform(AppOperation.SAVE_RECORDING, onCompleted = { recordingToken?.let(MediaWorkCoordinator::releaseRecording) }) {
        val file = result.getOrThrow()
        val project = withContext(Dispatchers.IO) { repository.commitSource(projectId, file, MediaProbe.inspect(file), recording = true) }
        if (mutable.value.project?.id == projectId) mutable.value = mutable.value.copy(project = project)
        updateLists()
    }
    fun exportArchive(uri: Uri, projectId: String) = perform(AppOperation.EXPORT_BACKUP) {
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
    fun importArchive(uri: Uri) = perform(AppOperation.IMPORT_BACKUP) {
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
    fun recoverPending(projectId: String) = perform(AppOperation.RECOVER_VIDEO) {
        val files = withContext(Dispatchers.IO) { repository.pendingMediaFiles(projectId) }
        if (files.isEmpty()) { mutable.value = mutable.value.copy(message = "Nessuna ripresa interrotta da recuperare."); return@perform }
        var count = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            try {
                val project = withContext(Dispatchers.IO) {
                    val metadata = MediaProbe.inspect(file)
                    val frame = MediaProbe.thumbnail(file, 0, 64, 64) ?: throw UserFeedbackException("Il video interrotto non contiene fotogrammi decodificabili. Il file è conservato: prova un altro video o un backup.")
                    frame.recycle()
                    repository.commitSource(projectId, file, metadata, recording = repository.pendingMediaOrigin(projectId, file) ?: false)
                }
                if (mutable.value.project?.id == projectId) mutable.value = mutable.value.copy(project = project)
                count++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += AppErrors.describe(e, AppOperation.RECOVER_VIDEO) }
        }
        updateLists()
        mutable.value = mutable.value.copy(message = "$count video recuperati.", error = failures.takeIf { it.isNotEmpty() }?.let { "Alcuni video interrotti non sono stati recuperati e sono conservati. ${it.joinToString("; ")}" })
    }
    fun diagnostics() = perform(AppOperation.DIAGNOSTICS) {
        val report = withContext(Dispatchers.IO) { DeviceDiagnostics.collect(app) }
        mutable.value = mutable.value.copy(diagnostics = report, destination = Destination.DIAGNOSTICS)
    }
    fun setCanvas(canvas: CanvasShape) = edit { ProjectEditor.setCanvas(it, canvas) }
}
