package it.panebarco.creator

import it.panebarco.creator.media.CameraUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** One process owns local camera/render resources; reservations precede asynchronous work. */
object MediaWorkCoordinator {
    const val WAIT_FOR_EXPORT = "È in corso un’esportazione. Attendi il completamento oppure annullala nell’editor prima di aprire la fotocamera."
    const val WAIT_FOR_CAMERA = "La fotocamera è aperta o sta salvando una ripresa. Torna all’editor e attendi il salvataggio prima di esportare."
    private val lock = Any()
    private val cameraLeases = mutableSetOf<String>()
    private var exportJob: String? = null
    private val finalization = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun cameraAllowed(): Boolean = synchronized(lock) { exportJob == null }
    fun acquireCamera(): String? = synchronized(lock) {
        if (exportJob != null) null else UUID.randomUUID().toString().also { cameraLeases += it }
    }
    fun releaseCamera(lease: String) { synchronized(lock) { cameraLeases -= lease } }
    fun releaseCameraWhenFinalized(lease: String, state: StateFlow<CameraUiState>) {
        val current = state.value
        if (!current.recording && !current.starting && !current.finalizing) releaseCamera(lease)
        else finalization.launch { state.first { !it.recording && !it.starting && !it.finalizing }; releaseCamera(lease) }
    }
    fun reserveExport(): String = synchronized(lock) {
        if (cameraLeases.isNotEmpty()) throw UserFeedbackException(WAIT_FOR_CAMERA)
        if (exportJob != null) throw UserFeedbackException("Un’esportazione è già in corso. Attendi o annullala prima di avviarne un’altra.")
        UUID.randomUUID().toString().also { exportJob = it }
    }
    fun ownsExport(jobId: String?): Boolean = synchronized(lock) { jobId != null && exportJob == jobId }
    fun releaseExport(jobId: String?) { synchronized(lock) { if (exportJob == jobId) exportJob = null } }
}
