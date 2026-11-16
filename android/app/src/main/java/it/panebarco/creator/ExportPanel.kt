package it.panebarco.creator

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.panebarco.creator.domain.Project
import kotlinx.coroutines.launch

@Composable fun ExportPanel(project: Project, status: ExportStatus, model: CreatorModel) {
    val context = LocalContext.current
    val ui by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var gallerySaved by remember(status.file) { mutableStateOf(false) }
    val start = { runCatching { ExportService.start(context, project.id) }.onFailure { model.reportError("Impossibile avviare l’esportazione: ${it.message}") }; Unit }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = status.projectId == project.id
        if (status.phase == ExportPhase.RUNNING) {
            Text(if (current) "Esportazione in corso · ${status.progress}%" else "Un altro progetto è in esportazione", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth().testTag("export_progress"))
            TextButton(onClick = { ExportService.cancel(context) }, modifier = Modifier.testTag("cancel_export")) { Text("Annulla esportazione") }
        } else Button(onClick = { if (Build.VERSION.SDK_INT >= 33 && ContextCompatPermission.missing(context, Manifest.permission.POST_NOTIFICATIONS)) notification.launch(Manifest.permission.POST_NOTIFICATIONS) else start() }, enabled = project.clips.isNotEmpty() && !ui.busy, modifier = Modifier.fillMaxWidth().testTag("export")) { Text("Esporta video · 1080p") }
        if (current && status.phase in listOf(ExportPhase.CANCELED, ExportPhase.FAILED, ExportPhase.INTERRUPTED)) Text(status.error ?: "Esportazione interrotta. Puoi riprovare.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export_error"))
        if (current && status.phase == ExportPhase.COMPLETE && status.file != null) {
            Text(if (status.revisionId == project.revisionId) "Video esportato. Il progetto resta modificabile." else "Video di una versione precedente. Esporta di nuovo per includere le ultime modifiche.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export_complete"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    saving = true
                    scope.launch {
                        try { saveExportToGallery(context, status.file, project.title); gallerySaved = true }
                        catch (e: Exception) { model.reportError(e.message ?: "Salvataggio in galleria non riuscito.") }
                        finally { saving = false }
                    }
                }, enabled = !saving && !gallerySaved, modifier = Modifier.weight(1f).testTag("save_gallery")) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(if (gallerySaved) "Salvato" else if (saving) "Salvataggio…" else "Galleria") }
                OutlinedButton(onClick = { runCatching { shareExport(context, status.file) }.onFailure { model.reportError("Condivisione non disponibile: ${it.message}") } }, modifier = Modifier.weight(1f).testTag("share_export")) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("Condividi") }
            }
        }
    }
}
