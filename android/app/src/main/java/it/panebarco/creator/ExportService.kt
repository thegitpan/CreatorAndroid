package it.panebarco.creator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.panebarco.creator.media.ProjectMediaEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class ExportPhase { IDLE, RUNNING, COMPLETE, CANCELED, FAILED, INTERRUPTED }
data class ExportStatus(val phase: ExportPhase = ExportPhase.IDLE, val projectId: String? = null, val progress: Int = 0, val file: File? = null, val error: String? = null, val revisionId: String? = null, val jobId: String? = null)
object ExportJobs {
    private val mutable = MutableStateFlow(ExportStatus())
    val state = mutable.asStateFlow()
    @Volatile private var restored = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence = kotlinx.coroutines.sync.Mutex()
    fun begin(projectId: String): ExportStatus = synchronized(this) {
        ExportStatus(ExportPhase.RUNNING, projectId, jobId = UUID.randomUUID().toString()).also { mutable.value = it }
    }
    fun restore(context: Context) {
        synchronized(this) { if (restored) return; restored = true }
        val app = context.applicationContext
        io.launch {
            val prefs = app.getSharedPreferences("export_receipt", Context.MODE_PRIVATE)
            val raw = prefs.getString("phase", null) ?: return@launch
            val savedPhase = runCatching { ExportPhase.valueOf(raw) }.getOrDefault(ExportPhase.FAILED)
            val phase = if (savedPhase == ExportPhase.RUNNING) ExportPhase.INTERRUPTED else savedPhase
            val name = prefs.getString("file", null)
            val file = name?.takeIf { it.matches(Regex("[a-zA-Z0-9._-]+")) }?.let { File(File(app.filesDir, "exports"), it) }?.takeIf { it.isFile }
            val status = ExportStatus(phase = if (phase == ExportPhase.COMPLETE && file == null) ExportPhase.FAILED else phase, projectId = prefs.getString("project", null), progress = if (phase == ExportPhase.COMPLETE) 100 else 0, file = file, error = if (phase == ExportPhase.INTERRUPTED) "L’esportazione è stata interrotta. Il progetto è salvo: avviala di nuovo." else prefs.getString("error", null), revisionId = prefs.getString("revision", null), jobId = prefs.getString("job", null))
            synchronized(this@ExportJobs) { if (mutable.value.phase == ExportPhase.IDLE) mutable.value = status }
        }
    }
    fun failNow(context: Context, projectId: String?, jobId: String?, message: String) {
        val failed = ExportStatus(ExportPhase.FAILED, projectId, error = message, jobId = jobId)
        synchronized(this) { if (mutable.value.jobId != jobId) return; mutable.value = failed }
        io.launch { runCatching { publish(context, failed) } }
    }
    suspend fun publish(context: Context, status: ExportStatus, persist: Boolean = true) {
        persistence.lock()
        try {
            if (status.jobId != mutable.value.jobId || (!persist && mutable.value.phase != ExportPhase.RUNNING)) return
            if (persist) withContext(Dispatchers.IO) {
                val success = context.getSharedPreferences("export_receipt", Context.MODE_PRIVATE).edit().putString("phase", status.phase.name).putString("project", status.projectId).putString("file", status.file?.name).putString("error", status.error).putString("revision", status.revisionId).putString("job", status.jobId).commit()
                if (!success) {
                    val message = "Impossibile salvare la ricevuta dell’esportazione. Controlla lo spazio libero."
                    synchronized(this@ExportJobs) {
                        if (status.jobId == mutable.value.jobId) mutable.value = if (status.phase == ExportPhase.COMPLETE) status.copy(phase = ExportPhase.FAILED, file = null, error = message) else status.copy(error = "${status.error.orEmpty()} $message".trim())
                    }
                    throw IOException(message)
                }
            }
            synchronized(this) { if (status.jobId == mutable.value.jobId) mutable.value = status }
        } finally { persistence.unlock() }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var projectId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Esportazione video", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { task?.cancel(); if (task == null) stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(PROJECT) ?: run { stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        projectId = id
        val base = ExportJobs.begin(id)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { startForeground(NOTIFICATION, notification(0), type) }
        catch (e: Exception) { ExportJobs.failNow(applicationContext, id, base.jobId, "Il sistema non consente l’esportazione in background: ${e.message}. Apri l’app e riprova."); stopSelf(); return START_NOT_STICKY }
        task = scope.launch {
            var output: File? = null
            try {
                ExportJobs.publish(this@ExportService, base)
                val app = application as CreatorApplication
                val project = withContext(Dispatchers.IO) { app.repository.load(id) }
                ExportJobs.publish(this@ExportService, base.copy(revisionId = project.revisionId))
                require(project.clips.isNotEmpty()) { "Aggiungi una clip prima di esportare." }
                output = withContext(Dispatchers.IO) { File(filesDir, "exports").apply { if (!isDirectory && !mkdirs()) throw IOException("Impossibile preparare l’esportazione: controlla lo spazio libero.") }.let { File(it, "creator-${UUID.randomUUID()}.mp4") } }
                val target = requireNotNull(output)
                val files = withContext(Dispatchers.IO) { project.clips.map { it.sourceId }.distinct().associateWith { app.repository.sourceFile(id, it) } }
                ProjectMediaEngine(this@ExportService).export(project, { files.getValue(it) }, target) { progress ->
                    val current = progress.coerceIn(0, 99)
                    scope.launch { if (ExportJobs.state.value.phase == ExportPhase.RUNNING) ExportJobs.publish(this@ExportService, base.copy(progress = current, revisionId = project.revisionId), persist = false) }
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(current)) }
                }
                ExportJobs.publish(this@ExportService, base.copy(phase = ExportPhase.COMPLETE, progress = 100, file = target, revisionId = project.revisionId))
                runCatching { getSystemService(NotificationManager::class.java).notify(COMPLETED_NOTIFICATION, completedNotification()) }
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { output?.delete(); runCatching { ExportJobs.publish(this@ExportService, base.copy(phase = ExportPhase.CANCELED, error = "Esportazione annullata. Il progetto e gli originali sono conservati.")) } }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { output?.delete() }
                ExportJobs.failNow(applicationContext, id, base.jobId, e.message ?: "Esportazione non riuscita. Riprova.")
            } finally { ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopSelf() }
    private fun contentIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(progress: Int): Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_creator).setContentTitle("Esportazione Creator").setContentText("Creazione del video · $progress%").setProgress(100, progress, progress == 0).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(contentIntent()).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annulla", PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    private fun completedNotification() = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_creator).setContentTitle("Il video è pronto").setContentText("Apri Creator per salvarlo in galleria o condividerlo.").setContentIntent(contentIntent()).setAutoCancel(true).build()
    companion object {
        private const val CHANNEL = "creator_export"
        private const val NOTIFICATION = 1001
        private const val COMPLETED_NOTIFICATION = 1002
        private const val PROJECT = "project"
        private const val CANCEL = "it.panebarco.creator.CANCEL_EXPORT"
        fun start(context: Context, projectId: String) { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java).putExtra(PROJECT, projectId)) }
        fun cancel(context: Context) { context.startService(Intent(context, ExportService::class.java).setAction(CANCEL)) }
    }
}

suspend fun saveExportToGallery(context: Context, file: File, title: String): Uri = withContext(Dispatchers.IO) {
    require(file.isFile && file.length() > 0) { "Il file esportato non è più disponibile. Esporta di nuovo." }
    val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "${safeName(title)}-${System.currentTimeMillis()}.mp4"); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Creator"); put(MediaStore.Video.Media.IS_PENDING, 1) }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw IOException("La galleria non ha creato il video. Controlla lo spazio disponibile.")
    try {
        resolver.openOutputStream(uri).use { output -> requireNotNull(output) { "Impossibile scrivere nella galleria." }; file.inputStream().use { it.copyTo(output) } }
        val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        if (published != 1) throw IOException("La galleria non ha pubblicato il video.")
        uri
    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
}
