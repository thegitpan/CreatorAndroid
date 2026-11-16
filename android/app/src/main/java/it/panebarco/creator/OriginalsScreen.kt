package it.panebarco.creator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.SourceAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun OriginalsScreen(project: Project, model: CreatorModel) {
    val ui by model.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SourceAsset?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Originali conservati", style = MaterialTheme.typography.headlineSmall)
        Text("I tagli modificano la timeline. Questi file restano intatti, anche quando elimini una clip o annulli una modifica.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { model.recoverPending(project.id) }, enabled = !ui.busy, modifier = Modifier.testTag("recover_pending")) { Text("Recupera video interrotti") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            itemsIndexed(project.sources, key = { _, source -> source.id }) { index, source ->
                Card(onClick = { selected = source }, modifier = Modifier.fillMaxWidth().testTag("original_$index")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Originale ${index + 1} · ${durationLabel(source.durationUs)}", style = MaterialTheme.typography.titleMedium)
                        Text("${source.width} × ${source.height} · rotazione ${source.rotationDegrees}° · ${if (source.hasAudio) "con audio" else "senza audio"}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256 ${source.sha256}", style = MaterialTheme.typography.labelSmall)
                        Text("Tocca per vedere il file completo", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    selected?.let { source -> OriginalDialog(project.id, source, model, onDismiss = { selected = null }) }
}
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable private fun OriginalDialog(projectId: String, source: SourceAsset, model: CreatorModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    LaunchedEffect(source.id) {
        try { file = withContext(Dispatchers.IO) { model.repository.sourceFile(projectId, source.id) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { model.reportError("Originale non disponibile: ${e.message}. Verifica il backup."); onDismiss() }
    }
    DisposableEffect(file) {
        val created = file?.let { target -> ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(target))); prepare(); addListener(object : androidx.media3.common.Player.Listener { override fun onPlayerError(error: androidx.media3.common.PlaybackException) { model.reportError("L’originale non può essere riprodotto: ${error.message}") } }) } }
        player = created
        onDispose { player = null; created?.release() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Originale · ${durationLabel(source.durationUs)}") }, text = { AndroidView(factory = { PlayerView(it) }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().height(300.dp)) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } })
}
