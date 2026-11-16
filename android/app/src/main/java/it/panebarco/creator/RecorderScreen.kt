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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.panebarco.creator.domain.Project
import it.panebarco.creator.media.CameraSession
import it.panebarco.creator.media.CameraUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContextCompatPermission { fun missing(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED }

@Composable fun RecorderScreen(project: Project, busy: Boolean, model: CreatorModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session = remember { CameraSession(context) }
    val camera by session.state.collectAsStateWithLifecycle()
    val recordingWork by MediaWorkCoordinator.recording.collectAsStateWithLifecycle()
    var cameraAllowed by remember { mutableStateOf(!ContextCompatPermission.missing(context, Manifest.permission.CAMERA)) }
    var microphoneAllowed by remember { mutableStateOf(!ContextCompatPermission.missing(context, Manifest.permission.RECORD_AUDIO)) }
    var silentChoice by rememberSaveable { mutableStateOf(false) }
    var preferredFront by rememberSaveable { mutableStateOf(false) }
    var recordingToken by remember { mutableStateOf<String?>(null) }
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
        // A completed repository write can precede the next collected UI frame.
        // Route using the camera's current state, not an older rendered snapshot.
        val actual = session.state.value
        if (actual.recording || actual.starting || actual.finalizing || preparing) { exitAfterSave = true; session.stop() }
        else model.navigate(Destination.EDITOR)
    }
    val toggleRecording = {
        val actual = session.state.value
        if (actual.recording) session.stop()
        else if (!actual.starting && !actual.finalizing && !preparing && !model.state.value.busy && actual.ready && !exitAfterSave && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val token = MediaWorkCoordinator.reserveRecording(project.id)
            if (token != null) {
                recordingToken = token
                preparing = true
                MediaWorkCoordinator.launchRecordingPreparation(scope, token) { handOff ->
                    try {
                        val target = model.prepareRecording(project.id)
                        withContext(Dispatchers.Main.immediate) {
                            if (!MediaWorkCoordinator.cameraAllowed() || ExportJobs.state.value.phase == ExportPhase.RUNNING) model.reportError(MediaWorkCoordinator.WAIT_FOR_EXPORT)
                            else if (exitAfterSave) model.navigate(Destination.EDITOR)
                            else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !model.state.value.busy && MediaWorkCoordinator.recording.value?.token == token && session.state.value.let { it.ready && !it.recording && !it.starting && !it.finalizing }) {
                                session.start(target, audioEnabled = !silentChoice) { result ->
                                    model.finalizeRecording(project.id, result, token)
                                    if (exitAfterSave) model.navigate(Destination.EDITOR)
                                }
                                handOff()
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { model.reportError(AppErrors.describe(e, AppOperation.START_RECORDING)) }
                    finally {
                        preparing = false
                    }
                }
            }
        }
        Unit
    }
    val waitingForPreviousTake = recordingWork != null && recordingWork?.token != recordingToken
    val canRecord = camera.recording || (cameraAllowed && camera.ready && !busy && recordingWork == null && !camera.starting && !camera.finalizing && !preparing && (microphoneAllowed || silentChoice))
    val openSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    val requestCamera = { permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    val audioToggle: (Boolean) -> Unit = { enabled ->
        silentChoice = !enabled
        if (enabled && !microphoneAllowed) permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        RecorderTools(
            camera = camera, silent = silentChoice, microphoneAllowed = microphoneAllowed,
            blocked = camera.recording || camera.starting || camera.finalizing || preparing,
            onAudioToggle = audioToggle, onSilentChoice = { silentChoice = true },
            onSettings = openSettings, onSwitchCamera = { session.switchCamera(); preferredFront = session.state.value.front },
            onFocusLock = session::setFocusLocked, onExposureLock = session::setExposureLocked,
            onZoom = session::setZoom, modifier = modifier,
        )
    }
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        RecorderPreview(session, owner, preferredFront, cameraAllowed && cameraLease != null && !waitingForPreviousTake, !cameraAllowed, requestCamera, openSettings, modifier)
    }
    val capture: @Composable (Boolean) -> Unit = { compact ->
        RecorderCaptureBar(camera, busy || waitingForPreviousTake || (recordingWork != null && !camera.recording && !camera.starting && !preparing), preparing, silentChoice, microphoneAllowed, project.clips.isNotEmpty(), project.durationUs, canRecord, toggleRecording, compact)
    }
    BackHandler { leave() }
    RecorderLayout(project, camera, preparing, leave, tools, preview, capture)
}

@Composable internal fun RecorderLayout(
    project: Project,
    camera: CameraUiState,
    preparing: Boolean,
    leave: () -> Unit,
    tools: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    capture: @Composable (Boolean) -> Unit,
) {
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).testTag("recorder_layout")) {
        val wide = maxWidth > maxHeight
        val compactLandscape = wide && maxHeight < 340.dp
        val portraitPanelHeight = maxHeight * .30f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = leave, enabled = !preparing, modifier = Modifier.weight(1f).testTag("leave_recorder")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (camera.recording) "Termina e monta" else "Monta progetto", maxLines = 2)
                }
                if (wide) Text("${project.clips.size} clip · ${durationLabel(project.durationUs)}", style = MaterialTheme.typography.labelMedium)
                else OutlinedButton(onClick = { controlsExpanded = !controlsExpanded }, modifier = Modifier.testTag("recorder_controls_toggle")) {
                    Icon(if (controlsExpanded) Icons.Default.ExpandLess else Icons.Default.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Controlli", maxLines = 1)
                }
            }
            if (!compactLandscape) camera.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("camera_error")) }
            if (compactLandscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview(Modifier.weight(.6f).fillMaxHeight())
                    tools(Modifier.weight(.4f).fillMaxHeight())
                }
                capture(true)
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        preview(Modifier.weight(1f).fillMaxWidth())
                        RecorderQuality(camera.qualityLabel)
                    }
                    Column(Modifier.weight(.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tools(Modifier.weight(1f).fillMaxWidth())
                        capture(false)
                    }
                }
            } else {
                preview(Modifier.weight(1f).fillMaxWidth())
                RecorderQuality(camera.qualityLabel)
                if (controlsExpanded) tools(Modifier.fillMaxWidth().heightIn(max = portraitPanelHeight))
                capture(false)
            }
        }
    }
}

@Composable private fun RecorderPreview(
    session: CameraSession,
    owner: androidx.lifecycle.LifecycleOwner,
    front: Boolean,
    allowed: Boolean,
    needsPermission: Boolean,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black).testTag("recorder_preview_pane"), contentAlignment = Alignment.Center) {
        if (allowed) AndroidView(
            factory = { PreviewView(it).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) { session.focus(event.x, event.y); view.performClick() }
                    true
                }
                session.bind(owner, this, front = front)
            } },
            modifier = Modifier.fillMaxSize().testTag("camera_preview"),
        )
        else if (needsPermission) Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Consenti l’accesso alla fotocamera per registrare.")
            Text("Puoi sempre importare e montare video senza questo permesso.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onPermission) { Text("Consenti fotocamera") }
            TextButton(onClick = onSettings) { Text("Apri impostazioni app") }
        }
        else CircularProgressIndicator(Modifier.size(28.dp))
    }
}

@Composable private fun RecorderQuality(label: String) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag("camera_quality"))
}

@Composable internal fun RecorderTools(
    camera: CameraUiState,
    silent: Boolean,
    microphoneAllowed: Boolean,
    blocked: Boolean,
    onAudioToggle: (Boolean) -> Unit,
    onSilentChoice: () -> Unit,
    onSettings: () -> Unit,
    onSwitchCamera: () -> Unit,
    onFocusLock: (Boolean) -> Unit,
    onExposureLock: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    Surface(modifier.testTag("recorder_tools"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Controlli fotocamera", style = MaterialTheme.typography.titleSmall)
            camera.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("camera_error_detail")) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (silent) Icons.Default.MicOff else Icons.Default.Mic, null)
                Text(if (silent) "Senza audio" else "Microfono", modifier = Modifier.weight(1f))
                Switch(checked = !silent, onCheckedChange = onAudioToggle, enabled = !blocked, modifier = Modifier.testTag("microphone"))
            }
            if (!microphoneAllowed && !silent) {
                Text("Il microfono non è autorizzato. Consenti il permesso oppure scegli una ripresa senza audio.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onSilentChoice, enabled = !blocked, modifier = Modifier.testTag("record_silent_choice")) { Text("Scegli ripresa senza audio") }
                TextButton(onClick = onSettings) { Text("Gestisci permesso microfono") }
            }
            OutlinedButton(onClick = onSwitchCamera, enabled = !blocked && camera.hasFrontCamera && camera.hasBackCamera, modifier = Modifier.fillMaxWidth().testTag("switch_camera").semantics { stateDescription = if (camera.front) "Fotocamera frontale" else "Fotocamera posteriore" }) {
                Icon(Icons.Default.Cameraswitch, null)
                Spacer(Modifier.width(6.dp))
                Text("Cambia fotocamera")
            }
            if (camera.canLockFocus) FilterChip(selected = camera.focusLocked, onClick = { onFocusLock(!camera.focusLocked) }, enabled = !camera.focusLockPending && !camera.finalizing && !camera.starting, label = { Text(if (camera.focusLockPending) "Blocco fuoco…" else if (camera.focusLocked) "Fuoco bloccato" else "Blocca fuoco") }, modifier = Modifier.fillMaxWidth().testTag("focus_lock"))
            if (camera.canLockExposure) FilterChip(selected = camera.exposureLocked, onClick = { onExposureLock(!camera.exposureLocked) }, enabled = !camera.exposureLockPending && !camera.finalizing && !camera.starting, label = { Text(if (camera.exposureLockPending) "Blocco esposizione…" else if (camera.exposureLocked) "Esposizione bloccata" else "Blocca esposizione") }, modifier = Modifier.fillMaxWidth().testTag("exposure_lock"))
            if (camera.maxZoomRatio > camera.minZoomRatio) {
                Text("Zoom ${"%.1f".format(camera.zoomRatio)}×", style = MaterialTheme.typography.labelMedium)
                Slider(value = camera.zoomRatio.coerceIn(camera.minZoomRatio, camera.maxZoomRatio), onValueChange = onZoom, valueRange = camera.minZoomRatio..camera.maxZoomRatio, enabled = camera.ready && !camera.finalizing, modifier = Modifier.fillMaxWidth().testTag("camera_zoom"))
            }
            Text(camera.qualityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text("Pausa finalizza e salva un segmento. Per continuare, premi Riprendi. Cambiando app la ripresa si arresta e viene salvata.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable internal fun RecorderCaptureBar(
    camera: CameraUiState,
    busy: Boolean,
    preparing: Boolean,
    silent: Boolean,
    microphoneAllowed: Boolean,
    hasClips: Boolean,
    projectDurationUs: Long,
    canRecord: Boolean,
    onRecord: () -> Unit,
    compact: Boolean = false,
) {
    Surface(Modifier.fillMaxWidth().testTag("capture_controls"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        val status: @Composable () -> Unit = {
                val status = if (camera.recording) "REC ${durationLabel(camera.durationUs)}" else if (camera.starting || preparing) "Avvio…" else if (camera.finalizing || busy) "Salvataggio…" else "Pronto · ${durationLabel(projectDurationUs)}"
                Text(status, color = if (camera.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.testTag("record_status"))
        }
        val audio: @Composable () -> Unit = {
                val audio = if (silent) "Senza audio" else if (microphoneAllowed) "Audio attivo" else "Consenti audio"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("record_audio_status")) {
                    Icon(if (silent || !microphoneAllowed) Icons.Default.MicOff else Icons.Default.Mic, audio, modifier = Modifier.size(20.dp), tint = if (!silent && !microphoneAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    Text(audio, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
        }
        val button: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onRecord, enabled = canRecord,
                modifier = modifier.heightIn(min = 56.dp).testTag("record_toggle"),
                colors = ButtonDefaults.buttonColors(containerColor = if (camera.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                Icon(if (camera.recording) Icons.Default.Pause else Icons.Default.FiberManualRecord, null)
                Spacer(Modifier.width(8.dp))
                Text(if (camera.recording) "Pausa e salva" else if (hasClips) "Riprendi" else "Registra", style = MaterialTheme.typography.titleMedium, maxLines = 2)
            }
        }
        if (compact) Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(.4f)) { status(); audio() }
            button(Modifier.weight(.6f))
        } else Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { status() }
                audio()
            }
            button(Modifier.fillMaxWidth())
        }
    }
}
