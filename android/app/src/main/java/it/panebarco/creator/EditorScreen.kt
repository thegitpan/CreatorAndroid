package it.panebarco.creator

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.TimelineClip
import it.panebarco.creator.media.MediaProbe
import it.panebarco.creator.media.ProjectMediaEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable fun EditorScreen(project: Project, busy: Boolean, model: CreatorModel, onImport: () -> Unit, onBackup: () -> Unit) {
    val context = LocalContext.current
    var selectedId by remember(project.id) { mutableStateOf<String?>(project.clips.firstOrNull()?.id) }
    var trim by remember { mutableStateOf<TimelineClip?>(null) }
    var delete by remember { mutableStateOf<TimelineClip?>(null) }
    var positionUs by remember(project.id) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<Player?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var sourceFiles by remember(project.id) { mutableStateOf<Map<String, File>>(emptyMap()) }
    val export by ExportJobs.state.collectAsStateWithLifecycle()
    val selected = project.clips.firstOrNull { it.id == selectedId }
    val selectedIndex = project.clips.indexOfFirst { it.id == selectedId }
    val selectedStart = project.clips.take(selectedIndex.coerceAtLeast(0)).sumOf { it.durationUs }
    val sourceSplit = selected?.let { it.sourceStartUs + positionUs - selectedStart }
    val canSplit = selected != null && sourceSplit != null && sourceSplit > selected.sourceStartUs && sourceSplit < selected.sourceEndUs
    LaunchedEffect(project) {
        if (project.clips.none { it.id == selectedId }) selectedId = project.clips.firstOrNull()?.id
        try {
            sourceFiles = withContext(Dispatchers.IO) { project.clips.map { it.sourceId }.distinct().associateWith { model.repository.sourceFile(project.id, it) } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { sourceFiles = emptyMap(); model.reportError("Un originale della timeline non è disponibile: ${e.message}. Conserva il progetto e ripristina il backup.") }
    }
    DisposableEffect(project, sourceFiles, export.phase) {
        var created: Player? = null
        if (export.phase != ExportPhase.RUNNING && project.clips.isNotEmpty() && sourceFiles.keys.containsAll(project.clips.map { it.sourceId })) {
            try {
                created = ProjectMediaEngine(context).createPreview(project) { sourceFiles.getValue(it) }
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { model.reportError("Anteprima interrotta: ${error.message ?: "formato non supportato"}. L’originale è conservato; prova un altro video.") }
                })
                created.seekTo((positionUs.coerceAtMost(project.durationUs) / 1_000L))
                player = created
            } catch (e: Exception) { model.reportError("Anteprima non disponibile: ${e.message ?: "formato non supportato"}") }
        }
        onDispose { if (player === created) player = null; created?.release() }
    }
    LaunchedEffect(player, dragging) {
        while (true) {
            player?.let { if (!dragging) positionUs = (it.currentPosition.coerceAtLeast(0) * 1_000).coerceAtMost(project.durationUs); isPlaying = it.isPlaying }
            delay(80)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { model.navigate(Destination.RECORDER) }, enabled = !busy, modifier = Modifier.testTag("continue_recording")) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Continua ripresa") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.testTag("import_video")) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Importa video") }
            IconButton(onClick = { model.edit(ProjectEditor::undo) }, enabled = !busy && project.canUndo, modifier = Modifier.testTag("undo")) { Icon(Icons.AutoMirrored.Filled.Undo, "Annulla modifica") }
            IconButton(onClick = { model.edit(ProjectEditor::redo) }, enabled = !busy && project.canRedo, modifier = Modifier.testTag("redo")) { Icon(Icons.AutoMirrored.Filled.Redo, "Ripristina modifica") }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            if (project.clips.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.MovieCreation, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp)); Text("La timeline è vuota", color = Color.White)
                Text("Registra o importa il primo video.", color = Color.LightGray)
            } else {
                AndroidView(factory = { PlayerView(it).apply { useController = false; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().height(280.dp).testTag("editor_preview"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }, enabled = player != null, modifier = Modifier.testTag("play_pause")) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausa anteprima" else "Riproduci anteprima") }
            Text("${durationLabel(positionUs)} / ${durationLabel(project.durationUs)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("playhead_time"))
            Spacer(Modifier.weight(1f))
            FilterChip(selected = project.canvas == CanvasShape.PORTRAIT, onClick = { if (!busy) model.setCanvas(CanvasShape.PORTRAIT) }, label = { Text("9:16") }, modifier = Modifier.testTag("canvas_portrait"))
            FilterChip(selected = project.canvas == CanvasShape.LANDSCAPE, onClick = { if (!busy) model.setCanvas(CanvasShape.LANDSCAPE) }, label = { Text("16:9") }, modifier = Modifier.testTag("canvas_landscape"))
        }
        Slider(value = if (project.durationUs == 0L) 0f else (positionUs.toDouble() / project.durationUs).toFloat().coerceIn(0f, 1f), onValueChange = { dragging = true; player?.pause(); positionUs = (project.durationUs * it.toDouble()).toLong(); player?.seekTo(positionUs / 1_000) }, onValueChangeFinished = { dragging = false }, enabled = project.durationUs > 0, modifier = Modifier.fillMaxWidth().testTag("timeline_scrubber"))
        if (project.clips.isNotEmpty()) {
            Text("Timeline · tocca una clip per selezionarla", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            LazyRow(Modifier.fillMaxWidth().height(154.dp).testTag("timeline"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(project.clips, key = { _, clip -> clip.id }) { index, clip ->
                    val selectedClip = clip.id == selectedId
                    Card(onClick = { selectedId = clip.id; positionUs = project.clips.take(index).sumOf { it.durationUs }; player?.pause(); player?.seekTo(positionUs / 1_000) }, colors = CardDefaults.cardColors(containerColor = if (selectedClip) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), border = if (selectedClip) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.width(144.dp).fillMaxHeight().testTag("clip_$index")) {
                        MediaThumbnail(sourceFiles[clip.sourceId], clip.sourceStartUs, Modifier.fillMaxWidth().height(70.dp).background(Color.Black))
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Clip ${index + 1} · ${durationLabel(clip.durationUs)}", style = MaterialTheme.typography.labelMedium)
                            ClipWaveform(sourceFiles[clip.sourceId], clip, Modifier.fillMaxWidth().height(24.dp))
                            Text(if (clip.volume == 0f) "Audio disattivato" else "Volume ${(clip.volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (selected != null) {
            Text("Clip ${selectedIndex + 1} selezionata", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("selected_clip"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { model.edit { ProjectEditor.split(it, selected.id, requireNotNull(sourceSplit)) } }, enabled = !busy && canSplit, modifier = Modifier.testTag("split")) { Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(6.dp)); Text("Dividi qui") }
                OutlinedButton(onClick = { trim = selected }, enabled = !busy, modifier = Modifier.testTag("trim")) { Text("Rifila") }
                OutlinedButton(onClick = { model.edit { ProjectEditor.move(it, selected.id, selectedIndex - 1) } }, enabled = !busy && selectedIndex > 0, modifier = Modifier.testTag("move_left")) { Icon(Icons.Default.ArrowBack, "Sposta prima") }
                OutlinedButton(onClick = { model.edit { ProjectEditor.move(it, selected.id, selectedIndex + 1) } }, enabled = !busy && selectedIndex < project.clips.lastIndex, modifier = Modifier.testTag("move_right")) { Icon(Icons.Default.ArrowForward, "Sposta dopo") }
                OutlinedButton(onClick = { delete = selected }, enabled = !busy, modifier = Modifier.testTag("delete_clip")) { Icon(Icons.Default.DeleteOutline, "Elimina clip") }
            }
            var volume by remember(selected.id, selected.volume) { mutableFloatStateOf(selected.volume) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.edit { ProjectEditor.setVolume(it, selected.id, if (selected.volume == 0f) 1f else 0f) } }, enabled = !busy, modifier = Modifier.testTag("mute")) { Icon(if (volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (volume == 0f) "Riattiva audio" else "Disattiva audio") }
                Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { model.edit { ProjectEditor.setVolume(it, selected.id, volume) } }, valueRange = 0f..2f, enabled = !busy, modifier = Modifier.weight(1f).testTag("volume"))
                Text("${(volume * 100).toInt()}%", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider()
        ExportPanel(project, export, model)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.navigate(Destination.ORIGINALS) }, modifier = Modifier.testTag("originals")) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text("Originali (${project.sources.size})") }
            TextButton(onClick = onBackup, enabled = !busy, modifier = Modifier.testTag("backup")) { Icon(Icons.Default.FolderZip, null); Spacer(Modifier.width(6.dp)); Text("Backup progetto") }
        }
        Spacer(Modifier.height(12.dp))
    }
    trim?.let { clip ->
        val source = project.sources.first { it.id == clip.sourceId }
        TrimDialog(clip, source.durationUs, onDismiss = { trim = null }) { start, end -> model.edit { ProjectEditor.trim(it, clip.id, start, end) }; trim = null }
    }
    delete?.let { clip -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Elimina la clip dalla timeline?") }, text = { Text("L’originale resta nel progetto. Puoi annullare questa modifica.") }, confirmButton = { TextButton(onClick = { model.edit { ProjectEditor.delete(it, clip.id) }; delete = null }, modifier = Modifier.testTag("confirm_delete_clip")) { Text("Elimina clip") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Annulla") } }) }
}

@Composable private fun ClipWaveform(file: File?, clip: TimelineClip, modifier: Modifier = Modifier) {
    var values by remember(file?.path, clip.sourceStartUs, clip.sourceEndUs) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(file?.path, clip.sourceStartUs, clip.sourceEndUs) { values = file?.let { runCatching { MediaProbe.waveform(it, bins = 48, startUs = clip.sourceStartUs, endUs = clip.sourceEndUs) }.getOrNull() } }
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val waveform = values ?: return@Canvas
        waveform.forEachIndexed { index, amplitude -> val x = size.width * (index + .5f) / waveform.size; val height = amplitude.coerceIn(0f, 1f) * size.height * .45f; drawLine(color, Offset(x, size.height / 2 - height), Offset(x, size.height / 2 + height), strokeWidth = 2.dp.toPx()) }
    }
}

private fun secondsText(us: Long): String = BigDecimal(us).movePointLeft(6).stripTrailingZeros().toPlainString()
private fun parseUs(value: String): Long? = runCatching { BigDecimal(value.replace(',', '.')).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
@Composable private fun TrimDialog(clip: TimelineClip, sourceDurationUs: Long, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    var start by remember { mutableStateOf(secondsText(clip.sourceStartUs)) }
    var end by remember { mutableStateOf(secondsText(clip.sourceEndUs)) }
    val startUs = parseUs(start)
    val endUs = parseUs(end)
    val valid = startUs != null && endUs != null && startUs >= 0 && endUs > startUs && endUs <= sourceDurationUs
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rifila clip") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Intervallo nell’originale, in secondi. Durata originale: ${secondsText(sourceDurationUs)} s.")
            OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Inizio (s)") }, singleLine = true, modifier = Modifier.testTag("trim_start"))
            OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Fine (s)") }, singleLine = true, modifier = Modifier.testTag("trim_end"))
            if (!valid) Text("Inserisci un intervallo valido entro l’originale.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { onApply(requireNotNull(startUs), requireNotNull(endUs)) }, enabled = valid, modifier = Modifier.testTag("apply_trim")) { Text("Applica") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
