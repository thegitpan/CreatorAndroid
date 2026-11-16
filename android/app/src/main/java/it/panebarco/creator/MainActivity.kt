package it.panebarco.creator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.setContent
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: CreatorModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExportJobs.restore(this)
        setContent { CreatorTheme { CreatorApp(model) } }
    }
}

private val CreatorColors = darkColorScheme(
    primary = Color(0xFFA5EE9A), onPrimary = Color(0xFF10200F),
    secondary = Color(0xFFB6C6E3), background = Color(0xFF101217),
    surface = Color(0xFF171B23), surfaceVariant = Color(0xFF272D38),
    error = Color(0xFFFFB4AB),
)
@Composable fun CreatorTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = CreatorColors, content = content) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CreatorApp(model: CreatorModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var rename by remember { mutableStateOf<Project?>(null) }
    var confirmTrash by remember { mutableStateOf<Project?>(null) }
    var backupId by rememberSaveableCompat { mutableStateOf<String?>(null) }
    var importProjectId by rememberSaveableCompat { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = backupId
        backupId = null
        if (uri != null && id != null) model.exportArchive(uri, id)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importArchive(uri) }
    val importVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val target = importProjectId
        importProjectId = null
        if (uris.isNotEmpty()) {
            if (target != null) model.importVideos(target, uris)
            else model.reportError("Progetto di destinazione non disponibile. Riapri il progetto e importa di nuovo.")
        }
    }
    val onBackup: (Project) -> Unit = { project -> backupId = project.id; backup.launch("${safeName(project.title)}.creator.zip") }
    BackHandler(state.destination != Destination.LIBRARY && state.destination != Destination.RECORDER) { model.navigate(Destination.LIBRARY) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    if (state.destination == Destination.RECORDER) {
        state.project?.let { RecorderScreen(it, state.busy, model, state.error) }
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(if (state.destination == Destination.LIBRARY) "Creator" else state.project?.title ?: "Creator", maxLines = 1); if (state.destination == Destination.LIBRARY) Text("I tuoi video, sul tuo dispositivo", style = MaterialTheme.typography.labelMedium) } },
                navigationIcon = { if (state.destination != Destination.LIBRARY && state.destination != Destination.RECORDER) IconButton(onClick = { model.navigate(Destination.LIBRARY) }, modifier = Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Progetti") } },
                actions = {
                    if (state.destination == Destination.EDITOR) state.project?.let { project ->
                        IconButton(onClick = { model.edit(ProjectEditor::undo) }, enabled = !state.busy && project.canUndo, modifier = Modifier.testTag("undo")) { Icon(Icons.AutoMirrored.Filled.Undo, "Annulla modifica") }
                        IconButton(onClick = { model.edit(ProjectEditor::redo) }, enabled = !state.busy && project.canRedo, modifier = Modifier.testTag("redo")) { Icon(Icons.AutoMirrored.Filled.Redo, "Ripristina modifica") }
                    }
                    if (state.destination == Destination.LIBRARY) {
                        IconButton(onClick = model::diagnostics, modifier = Modifier.testTag("diagnostics")) { Icon(Icons.Default.Settings, "Diagnostica dispositivo") }
                        IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, "Informazioni e licenze") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("operation_progress"))
            when (state.destination) {
                Destination.LIBRARY -> LibraryScreen(state, model, onRename = { rename = it }, onTrash = { confirmTrash = it }, onBackup = onBackup, onRestoreArchive = { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                Destination.EDITOR -> state.project?.let { EditorScreen(it, state.busy, model, onImport = { importProjectId = it.id; importVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, onBackup = { onBackup(it) }) }
                Destination.RECORDER -> state.project?.let { RecorderScreen(it, state.busy, model) }
                Destination.ORIGINALS -> state.project?.let { OriginalsScreen(it, model) }
                Destination.DIAGNOSTICS -> DiagnosticScreen(state.diagnostics, onCopy = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diagnostica Creator", state.diagnostics.orEmpty()))
                })
            }
        }
    }
    if (state.destination != Destination.RECORDER) state.error?.let { message -> AlertDialog(onDismissRequest = model::clearError, title = { Text("Operazione non completata") }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::clearError) { Text("Ho capito") } }) }
    rename?.let { project -> NameDialog("Rinomina progetto", project.title, onDismiss = { rename = null }) { model.rename(project.id, it); rename = null } }
    confirmTrash?.let { project -> AlertDialog(onDismissRequest = { confirmTrash = null }, title = { Text("Sposta nel cestino?") }, text = { Text("“${project.title}” conserverà originali e cronologia. Potrai ripristinarlo dal cestino.") }, confirmButton = { TextButton(onClick = { model.trash(project.id); confirmTrash = null }, modifier = Modifier.testTag("confirm_trash")) { Text("Sposta nel cestino") } }, dismissButton = { TextButton(onClick = { confirmTrash = null }) { Text("Annulla") } }) }
    if (showAbout) AboutDialog { showAbout = false }
}

// This state survives rotation while the Storage Access Framework owns the activity.
@Composable private fun <T> rememberSaveableCompat(init: () -> MutableState<T>): MutableState<T> = androidx.compose.runtime.saveable.rememberSaveable { init() }

@Composable fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value = value, onValueChange = { if (it.length <= 100) value = it }, label = { Text("Nome del progetto") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("project_name")) }, confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.trim().isNotEmpty(), modifier = Modifier.testTag("confirm_name")) { Text("Salva") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable private fun DiagnosticScreen(report: String?, onCopy: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Diagnostica dispositivo", style = MaterialTheme.typography.headlineSmall)
        Text("Dati tecnici locali di camera, audio e codec. Nessun media, account o identificativo univoco viene inviato.")
        Text("La compatibilità della camera Samsung richiede una prova sul telefono con preview e file salvato.", color = MaterialTheme.colorScheme.secondary)
        OutlinedButton(onClick = onCopy, enabled = report != null) { Text("Copia report tecnico") }
        Text(report ?: "Raccolta in corso…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diagnostics_report"))
    }
}
@Composable private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var credits by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { credits = withContext(Dispatchers.IO) { context.assets.open("credits.txt").bufferedReader().use { it.readText() } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Creator · informazioni e licenze") }, text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Text("Montaggio e registrazione locali. Nessun account, pubblicità o caricamento dei media. I progetti privati vengono rimossi dalla disinstallazione: salva un backup per conservarli."); Spacer(Modifier.height(16.dp)); Text(credits, style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } })
}
fun safeName(value: String): String = value.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(80).ifBlank { "Creator" }
fun durationLabel(us: Long): String { val seconds = us.coerceAtLeast(0) / 1_000_000; return "%d:%02d".format(seconds / 60, seconds % 60) }
fun shareExport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = ClipData.newRawUri("Video Creator", uri) }, "Condividi video"))
}
