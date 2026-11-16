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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContextCompatPermission { fun missing(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED }

@Composable fun RecorderScreen(project: Project, busy: Boolean, model: CreatorModel, error: String? = null) {
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
    val displayBusy = busy || waitingForPreviousTake || (recordingWork != null && !camera.recording && !camera.starting && !preparing)
    val viewState = RecorderViewState(camera, project.clips.size, project.durationUs, displayBusy, preparing,
        silentChoice, microphoneAllowed, cameraAllowed, canRecord, error ?: camera.error)
    val actions = RecorderActions(
        onRecord = toggleRecording, onLeave = leave, onAudioToggle = audioToggle,
        onSilentChoice = { silentChoice = true }, onSettings = openSettings, onPermission = requestCamera,
        onSwitchCamera = { session.switchCamera(); preferredFront = session.state.value.front },
        onFocusLock = session::setFocusLocked, onExposureLock = session::setExposureLocked,
        onZoom = session::setZoom, onDismissError = model::clearError,
    )
    BackHandler { leave() }
    RecorderLayout(viewState, actions) { modifier ->
        RecorderPreview(session, owner, preferredFront, cameraAllowed && cameraLease != null && !waitingForPreviousTake, modifier)
    }
}

@Composable private fun RecorderPreview(
    session: CameraSession,
    owner: androidx.lifecycle.LifecycleOwner,
    front: Boolean,
    allowed: Boolean,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black).testTag("recorder_preview_pane"), contentAlignment = Alignment.Center) {
        if (allowed) AndroidView(
            factory = { PreviewView(it).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        if (fitPreviewBounds(view.width.toFloat(), view.height.toFloat(), session.state.value.previewAspectRatio).contains(Offset(event.x, event.y))) session.focus(event.x, event.y)
                        view.performClick()
                    }
                    true
                }
                session.bind(owner, this, front = front)
            } },
            modifier = Modifier.fillMaxSize().testTag("camera_preview"),
        )
        else CircularProgressIndicator(Modifier.size(28.dp))
    }
}
