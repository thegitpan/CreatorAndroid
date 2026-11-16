package it.panebarco.creator

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.panebarco.creator.domain.Project
import it.panebarco.creator.media.MediaProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable fun LibraryScreen(state: CreatorState, model: CreatorModel, onRename: (Project) -> Unit, onTrash: (Project) -> Unit, onBackup: (Project) -> Unit, onRestoreArchive: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    var createForRecording by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { createForRecording = true; create = true }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new_recording")) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(8.dp)); Text("Registra") }
            OutlinedButton(onClick = { createForRecording = false; create = true }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("new_project")) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Progetto") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !trash, onClick = { trash = false }, label = { Text("Progetti (${state.projects.size})") }, modifier = Modifier.testTag("projects_tab"))
            FilterChip(selected = trash, onClick = { trash = true }, label = { Text("Cestino (${state.trashed.size})") }, modifier = Modifier.testTag("trash_tab"))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRestoreArchive, enabled = !state.busy, modifier = Modifier.testTag("import_backup")) { Icon(Icons.Default.FolderZip, "Importa backup del progetto") }
        }
        LazyColumn(Modifier.weight(1f).testTag("project_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.warnings.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Verifica dell’archivio", style = MaterialTheme.typography.titleSmall)
                        state.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            val projects = if (trash) state.trashed else state.projects
            if (projects.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (trash) Icons.Default.DeleteOutline else Icons.Default.MovieCreation, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(if (trash) "Il cestino è vuoto" else "Inizia il tuo prossimo video", style = MaterialTheme.typography.titleLarge)
                    Text(if (trash) "I progetti spostati qui possono essere ripristinati." else "Registra una prima ripresa oppure crea un progetto e importa i tuoi video.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(projects, key = { it.id }) { project ->
                ProjectCard(project, trash, state.busy, model, onRename, onTrash, onBackup)
            }
            item { Text("Tutto resta su questo dispositivo. Il backup conserva anche gli originali e la cronologia.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    if (create) NameDialog("Nuovo progetto", onDismiss = { create = false }) { model.create(it, createForRecording); create = false }
}

@Composable private fun ProjectCard(project: Project, trashed: Boolean, busy: Boolean, model: CreatorModel, onRename: (Project) -> Unit, onTrash: (Project) -> Unit, onBackup: (Project) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("project_${project.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clip = project.clips.firstOrNull()
                var file by remember(project.id, clip?.sourceId) { mutableStateOf<File?>(null) }
                LaunchedEffect(project.id, clip?.sourceId) { file = withContext(Dispatchers.IO) { clip?.let { runCatching { model.repository.sourceFile(project.id, it.sourceId) }.getOrNull() } } }
                MediaThumbnail(file, clip?.sourceStartUs ?: 0, Modifier.size(72.dp).background(Color.Black))
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${durationLabel(project.durationUs)} · ${project.clips.size} clip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.updatedAtMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (!trashed) Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("project_menu_${project.id}")) { Icon(Icons.Default.MoreVert, "Azioni per ${project.title}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; onRename(project) })
                        DropdownMenuItem(text = { Text("Backup con originali") }, onClick = { menu = false; onBackup(project) })
                        DropdownMenuItem(text = { Text("Sposta nel cestino") }, onClick = { menu = false; onTrash(project) })
                    }
                }
            }
            if (trashed) OutlinedButton(onClick = { model.restore(project.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("restore_${project.id}")) { Icon(Icons.Default.RestoreFromTrash, null); Spacer(Modifier.width(8.dp)); Text("Ripristina progetto") }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.open(project.id) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("edit_${project.id}")) { Text("Monta") }
                OutlinedButton(onClick = { model.open(project.id, Destination.RECORDER) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("record_${project.id}")) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Continua") }
            }
        }
    }
}

@Composable fun MediaThumbnail(file: File?, positionUs: Long, modifier: Modifier = Modifier) {
    var bitmap by remember(file?.path, positionUs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.path, positionUs) {
        bitmap = withContext(Dispatchers.IO) { file?.let { runCatching { MediaProbe.thumbnail(it, positionUs) }.getOrNull() } }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val value = bitmap
        if (value != null) Image(value.asImageBitmap(), "Miniatura del video", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Default.Movie, "Video", tint = MaterialTheme.colorScheme.secondary)
    }
}
