package it.panebarco.creator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.panebarco.creator.domain.Project
import it.panebarco.creator.media.CameraSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContextCompatPermission { fun missing(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED }

@Composable fun RecorderScreen(project: Project, busy: Boolean, model: CreatorModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session = remember { CameraSession(context) }
    val camera by session.state.collectAsStateWithLifecycle()
    var cameraAllowed by remember { mutableStateOf(!ContextCompatPermission.missing(context, Manifest.permission.CAMERA)) }
    var microphoneAllowed by remember { mutableStateOf(!ContextCompatPermission.missing(context, Manifest.permission.RECORD_AUDIO)) }
    var silentChoice by rememberSaveable { mutableStateOf(false) }
    var cameraLease by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var exitAfterSave by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraAllowed = !ContextCompatPermission.missing(context, Manifest.permission.CAMERA)
        microphoneAllowed = !ContextCompatPermission.missing(context, Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(cameraLease) { if (cameraLease != null && (!cameraAllowed || !microphoneAllowed)) permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    DisposableEffect(owner) {
        val lease = MediaWorkCoordinator.acquireCamera()
        cameraLease = lease
        if (lease == null) { model.reportError(MediaWorkCoordinator.WAIT_FOR_EXPORT); model.navigate(Destination.EDITOR) }
        val activity = context as? ComponentActivity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.stop()
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraAllowed = !ContextCompatPermission.missing(context, Manifest.permission.CAMERA)
                microphoneAllowed = !ContextCompatPermission.missing(context, Manifest.permission.RECORD_AUDIO)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); session.close(); lease?.let { MediaWorkCoordinator.releaseCameraWhenFinalized(it, session.state) }; activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val leave = {
        if (camera.recording || camera.starting || camera.finalizing || preparing) { exitAfterSave = true; session.stop() }
        else model.navigate(Destination.EDITOR)
    }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = leave, enabled = !preparing, modifier = Modifier.testTag("leave_recorder")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text(if (camera.recording) "Termina e monta" else "Monta progetto") }
            Spacer(Modifier.weight(1f))
            Text("${project.clips.size} clip · ${durationLabel(project.durationUs)}", style = MaterialTheme.typography.labelMedium)
        }
        if (cameraAllowed && cameraLease != null) AndroidView(factory = { PreviewView(it).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setOnTouchListener { view, event -> if (event.action == MotionEvent.ACTION_UP) { session.focus(event.x, event.y); view.performClick() }; true }
            session.bind(owner, this, front = false)
        } }, modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp, max = 460.dp).aspectRatio(9f / 12f).background(Color.Black).testTag("camera_preview"))
        else if (!cameraAllowed) Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Consenti l’accesso alla fotocamera per registrare.")
            Text("Puoi sempre importare e montare video senza questo permesso.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Consenti fotocamera") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Apri impostazioni app") }
        } }
        camera.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("camera_error")) }
        Text(camera.qualityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (silentChoice) Icons.Default.MicOff else Icons.Default.Mic, null)
            Text(if (silentChoice) "Senza audio" else "Microfono", modifier = Modifier.weight(1f))
            Switch(checked = !silentChoice, onCheckedChange = { enabled -> silentChoice = !enabled; if (enabled && !microphoneAllowed) permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }, enabled = !camera.recording && !camera.starting && !camera.finalizing && !preparing, modifier = Modifier.testTag("microphone"))
            IconButton(onClick = session::switchCamera, enabled = !camera.recording && !camera.starting && !camera.finalizing && !preparing && camera.hasFrontCamera && camera.hasBackCamera, modifier = Modifier.testTag("switch_camera")) { Icon(Icons.Default.Cameraswitch, "Cambia fotocamera tra le riprese") }
        }
        if (!microphoneAllowed && !silentChoice) {
            Text("Il microfono non è autorizzato. Consenti il permesso oppure scegli una ripresa senza audio.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { silentChoice = true }, modifier = Modifier.testTag("record_silent_choice")) { Text("Scegli ripresa senza audio") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Gestisci permesso microfono") }
        }
        if (camera.canLockFocus || camera.canLockExposure) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (camera.canLockFocus) FilterChip(selected = camera.focusLocked, onClick = { session.setFocusLocked(!camera.focusLocked) }, enabled = !camera.focusLockPending && !camera.finalizing && !camera.starting, label = { Text(if (camera.focusLockPending) "Blocco fuoco…" else if (camera.focusLocked) "Fuoco bloccato" else "Blocca fuoco") }, modifier = Modifier.testTag("focus_lock"))
            if (camera.canLockExposure) FilterChip(selected = camera.exposureLocked, onClick = { session.setExposureLocked(!camera.exposureLocked) }, enabled = !camera.exposureLockPending && !camera.finalizing && !camera.starting, label = { Text(if (camera.exposureLockPending) "Blocco esposizione…" else if (camera.exposureLocked) "Esposizione bloccata" else "Blocca esposizione") }, modifier = Modifier.testTag("exposure_lock"))
        }
        if (camera.maxZoomRatio > camera.minZoomRatio) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom ${"%.1f".format(camera.zoomRatio)}×", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
            Slider(value = camera.zoomRatio.coerceIn(camera.minZoomRatio, camera.maxZoomRatio), onValueChange = session::setZoom, valueRange = camera.minZoomRatio..camera.maxZoomRatio, enabled = camera.ready && !camera.finalizing, modifier = Modifier.weight(1f).testTag("camera_zoom"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (camera.recording) "REC ${durationLabel(camera.durationUs)}" else if (camera.starting || preparing) "Avvio…" else if (camera.finalizing || busy) "Salvataggio…" else "Pronto · ${durationLabel(project.durationUs)}", color = if (camera.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).testTag("record_status"), style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                if (camera.recording) session.stop()
                else {
                    preparing = true
                    scope.launch {
                        try {
                            val target = model.prepareRecording(project.id)
                            withContext(Dispatchers.Main.immediate) {
                                if (!MediaWorkCoordinator.cameraAllowed() || ExportJobs.state.value.phase == ExportPhase.RUNNING) model.reportError(MediaWorkCoordinator.WAIT_FOR_EXPORT)
                                else if (exitAfterSave) model.navigate(Destination.EDITOR)
                                else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) session.start(target, audioEnabled = !silentChoice) { result -> model.finalizeRecording(project.id, result); if (exitAfterSave) model.navigate(Destination.EDITOR) }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { model.reportError(AppErrors.describe(e, AppOperation.START_RECORDING)) }
                        finally { preparing = false }
                    }
                }
            }, enabled = camera.recording || (cameraAllowed && camera.ready && !busy && !camera.starting && !camera.finalizing && !preparing && (microphoneAllowed || silentChoice)), modifier = Modifier.height(56.dp).testTag("record_toggle"), colors = ButtonDefaults.buttonColors(containerColor = if (camera.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Icon(if (camera.recording) Icons.Default.Pause else Icons.Default.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text(if (camera.recording) "Pausa e salva" else if (project.clips.isEmpty()) "Registra" else "Riprendi") }
        }
        Text("Pausa finalizza e salva un segmento. Per continuare, premi Riprendi. Cambiando app la ripresa si arresta e viene salvata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
    }
}
