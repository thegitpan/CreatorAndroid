package it.panebarco.creator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.panebarco.creator.media.CameraUiState
import kotlin.math.max
import kotlin.math.min

internal data class RecorderViewState(
    val camera: CameraUiState,
    val clipCount: Int = 0,
    val projectDurationUs: Long = 0,
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val silent: Boolean = false,
    val microphoneAllowed: Boolean = true,
    val cameraAllowed: Boolean = true,
    val canRecord: Boolean = false,
    val error: String? = null,
)

internal data class RecorderActions(
    val onRecord: () -> Unit,
    val onLeave: () -> Unit,
    val onAudioToggle: (Boolean) -> Unit,
    val onSilentChoice: () -> Unit,
    val onSettings: () -> Unit,
    val onPermission: () -> Unit,
    val onSwitchCamera: () -> Unit,
    val onFocusLock: (Boolean) -> Unit,
    val onExposureLock: (Boolean) -> Unit,
    val onZoom: (Float) -> Unit,
    val onDismissError: () -> Unit,
)

internal fun fitPreviewBounds(width: Float, height: Float, ratio: Float?): Rect {
    if (ratio == null || !ratio.isFinite() || ratio <= 0f) return Rect(0f, 0f, width, height)
    val w = min(width, height * ratio)
    val h = w / ratio
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

private val CameraYellow = Color(0xFFFFD64D)
private val CameraRed = Color(0xFFFF4C43)
private val CameraScrim = Color.Black.copy(alpha = .42f)

@Composable internal fun RecorderLayout(
    state: RecorderViewState,
    actions: RecorderActions,
    preview: @Composable (Modifier) -> Unit,
) {
    var zoomOpen by rememberSaveable { mutableStateOf(false) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var dismissedError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) { if (state.error == null) dismissedError = null }
    LaunchedEffect(state.camera.front, state.camera.maxZoomRatio, state.camera.minZoomRatio) {
        if (state.camera.maxZoomRatio <= state.camera.minZoomRatio) zoomOpen = false
    }
    val density = LocalDensity.current
    val microphoneNeedsChoice = !state.microphoneAllowed && !state.silent
    val priorityNotice = (!state.cameraAllowed || microphoneNeedsChoice || zoomOpen || (state.error != null && state.error != dismissedError))
    val zoomControl: @Composable () -> Unit = {
        CameraMiniButton(Icons.Default.ZoomIn, "${"%.1f".format(state.camera.zoomRatio)}×", "Regola zoom", "zoom_toggle", { zoomOpen = !zoomOpen; profileOpen = false },
            enabled = state.camera.ready && state.camera.maxZoomRatio > state.camera.minZoomRatio && !state.camera.finalizing, active = zoomOpen)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("recorder_layout")) {
        preview(Modifier.matchParentSize())
        val frame = fitPreviewBounds(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), state.camera.previewAspectRatio)
        val safe = WindowInsets.safeDrawing
        val left = max(frame.left, safe.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat())
        val top = max(frame.top, safe.getTop(density).toFloat())
        val right = min(frame.right, constraints.maxWidth - safe.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat())
        val bottom = min(frame.bottom, constraints.maxHeight - safe.getBottom(density).toFloat())
        Box(Modifier.offset(with(density) { frame.left.toDp() }, with(density) { frame.top.toDp() })
            .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .46f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .48f)))))
        BoxWithConstraints(Modifier.offset(with(density) { left.toDp() }, with(density) { top.toDp() })
            .size(with(density) { (right - left).coerceAtLeast(0f).toDp() }, with(density) { (bottom - top).coerceAtLeast(0f).toDp() })
            .padding(8.dp).testTag("recorder_frame_overlay")) {
            val wide = maxWidth > maxHeight
            val sideWidth = if (wide) 96.dp else 0.dp
            Column(Modifier.fillMaxSize().padding(end = sideWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(28.dp)).background(CameraScrim), verticalAlignment = Alignment.CenterVertically) {
                    CameraMiniButton(Icons.AutoMirrored.Filled.ArrowBack, "Monta", "Monta progetto", "leave_recorder", actions.onLeave, enabled = !state.preparing)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if ((microphoneNeedsChoice || zoomOpen) && state.cameraAllowed) zoomControl() else
                        Column(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable { profileOpen = !profileOpen; zoomOpen = false }
                            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("camera_quality")
                            .semantics(mergeDescendants = true) { contentDescription = "Profilo video. ${state.camera.qualityLabel}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(state.camera.qualityLabel.substringBefore(" ·"), color = Color.White, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("SDR", color = Color.White.copy(alpha = .8f), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
                        }
                    }
                    CameraMiniButton(if (state.silent || !state.microphoneAllowed) Icons.Default.MicOff else Icons.Default.Mic,
                        if (state.silent) "MUTO" else "MIC", if (!state.microphoneAllowed && !state.silent) "Consenti microfono" else if (state.silent) "Attiva microfono" else "Disattiva microfono", "microphone",
                        { actions.onAudioToggle(state.silent || !state.microphoneAllowed) }, enabled = !state.preparing && !state.camera.starting && !state.camera.recording && !state.camera.finalizing,
                        active = state.silent, stateDescription = if (state.silent) "Senza audio" else if (state.microphoneAllowed) "Audio attivo" else "Permesso microfono necessario", extraTag = "record_audio_status")
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        state.error != null && state.error != dismissedError -> CameraNotice(state.error, "camera_error", { dismissedError = state.error; actions.onDismissError() })
                        !state.cameraAllowed -> CameraPermissionNotice(false, actions)
                        zoomOpen && state.camera.ready && state.camera.maxZoomRatio > state.camera.minZoomRatio -> ZoomOverlay(state.camera, actions.onZoom)
                        microphoneNeedsChoice -> CameraPermissionNotice(true, actions)
                        profileOpen -> CameraNotice(state.camera.qualityLabel, "camera_profile_detail", { profileOpen = false })
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RecorderStatus(state)
                    if (!priorityNotice) Row(Modifier.clip(RoundedCornerShape(28.dp)).background(CameraScrim).testTag("recorder_tools"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (state.camera.canLockFocus) CameraMiniButton(Icons.Default.CenterFocusStrong, if (state.camera.focusLocked) "AF-L" else "AF", "Blocco messa a fuoco", "focus_lock", { actions.onFocusLock(!state.camera.focusLocked) },
                            enabled = !state.camera.focusLockPending && !state.camera.starting && !state.camera.finalizing, active = state.camera.focusLocked, stateDescription = if (state.camera.focusLocked) "Fuoco bloccato" else "Fuoco automatico")
                        if (state.camera.canLockExposure) CameraMiniButton(Icons.Default.Exposure, if (state.camera.exposureLocked) "AE-L" else "AE", "Blocco esposizione", "exposure_lock", { actions.onExposureLock(!state.camera.exposureLocked) },
                            enabled = !state.camera.exposureLockPending && !state.camera.starting && !state.camera.finalizing, active = state.camera.exposureLocked, stateDescription = if (state.camera.exposureLocked) "Esposizione bloccata" else "Esposizione automatica")
                        zoomControl()
                    }
                }
                if (!wide) CaptureControls(state, actions, Modifier.fillMaxWidth())
            }
            if (wide) CaptureControls(state, actions, Modifier.align(Alignment.CenterEnd).width(sideWidth).fillMaxHeight(), vertical = true)
            if (state.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).testTag("operation_progress"), color = CameraYellow, trackColor = Color.Transparent)
        }
    }
}

@Composable private fun RecorderStatus(state: RecorderViewState) {
    val text = when {
        state.camera.recording -> "REC ${durationLabel(state.camera.durationUs)}"
        state.camera.starting || state.preparing -> "Avvio…"
        state.camera.finalizing || state.busy -> "Salvataggio…"
        else -> "Pronto · ${durationLabel(state.projectDurationUs)}"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = if (state.camera.recording) CameraRed else Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.testTag("record_status"))
        Text("${state.clipCount} clip", color = Color.White.copy(alpha = .8f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
    }
}

@Composable private fun CaptureControls(state: RecorderViewState, actions: RecorderActions, modifier: Modifier, vertical: Boolean = false) {
    val editor: @Composable () -> Unit = { CameraMiniButton(Icons.Default.VideoLibrary, "Monta", "Apri montaggio", "recorder_editor", actions.onLeave, enabled = !state.preparing) }
    val switch: @Composable () -> Unit = { CameraMiniButton(Icons.Default.Cameraswitch, "Camera", "Cambia fotocamera", "switch_camera", actions.onSwitchCamera,
        enabled = state.camera.hasFrontCamera && state.camera.hasBackCamera && !state.camera.recording && !state.camera.starting && !state.camera.finalizing && !state.preparing && !state.busy,
        stateDescription = if (state.camera.front) "Fotocamera frontale" else "Fotocamera posteriore") }
    val record: @Composable (Dp) -> Unit = { diameter ->
        val label = if (state.camera.recording) "Pausa e salva" else if (state.clipCount > 0) "Riprendi" else "Registra"
        Box(Modifier.size(diameter).clip(CircleShape).background(Color.White.copy(alpha = if (state.canRecord) 1f else .6f))
            .border(2.dp, Color.White, CircleShape).clickable(enabled = state.canRecord, role = Role.Button, onClick = actions.onRecord)
            .testTag("record_toggle").semantics { contentDescription = label; stateDescription = if (state.camera.recording) "Registrazione in corso" else if (state.canRecord) "Pronto" else "Attendi fotocamera o salvataggio" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (diameter < 84.dp) 24.dp else if (state.camera.recording) 28.dp else 32.dp).clip(if (state.camera.recording) RoundedCornerShape(5.dp) else CircleShape).background(CameraRed))
        }
    }
    if (vertical) BoxWithConstraints(modifier.testTag("capture_controls")) {
        val diameter = if (maxHeight < 220.dp) (maxHeight - 96.dp).coerceIn(48.dp, 64.dp) else 84.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) { switch(); record(diameter); editor() }
    } else Row(modifier.height(96.dp).testTag("capture_controls"), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { editor(); record(84.dp); switch() }
}

@Composable private fun CameraMiniButton(icon: ImageVector, text: String, description: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, active: Boolean = false, stateDescription: String? = null, extraTag: String? = null) {
    val tint = (if (active) CameraYellow else Color.White).copy(alpha = if (enabled) 1f else .42f)
    Box(if (extraTag != null) Modifier.testTag(extraTag) else Modifier) {
    Column(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).semantics(mergeDescendants = true) { contentDescription = description; stateDescription?.let { this.stateDescription = it }; if (active) selected = true }.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        Text(text, color = tint, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
    }
    }
}

@Composable private fun ZoomOverlay(camera: CameraUiState, onZoom: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().widthIn(max = 300.dp).height(48.dp).clip(RoundedCornerShape(20.dp)).background(CameraScrim).shieldCameraTaps().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${"%.1f".format(camera.zoomRatio)}×", color = CameraYellow, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
        Box(Modifier.weight(1f).height(48.dp).testTag("camera_zoom"), contentAlignment = Alignment.Center) {
            Slider(camera.zoomRatio.coerceIn(camera.minZoomRatio, camera.maxZoomRatio), onZoom, valueRange = camera.minZoomRatio..camera.maxZoomRatio,
                modifier = Modifier.fillMaxWidth().height(48.dp), colors = SliderDefaults.colors(thumbColor = CameraYellow, activeTrackColor = CameraYellow, inactiveTrackColor = Color.White.copy(alpha = .4f)))
        }
    }
}

@Composable private fun CameraNotice(text: String, tag: String, onDismiss: () -> Unit) {
    var details by rememberSaveable(text) { mutableStateOf(false) }
    Row(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(16.dp)).background(CameraScrim).shieldCameraTaps().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag(tag))
        if (tag == "camera_error") IconButton({ details = true }, Modifier.size(48.dp).testTag("error_details")) { Icon(Icons.Default.Info, "Dettagli avviso", tint = Color.White) }
        IconButton(onDismiss, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Chiudi avviso", tint = Color.White) }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Dettagli avviso") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { details = false }, modifier = Modifier.testTag("close_error_details")) { Text("Chiudi") } })
}

@Composable private fun CameraPermissionNotice(audio: Boolean, actions: RecorderActions) {
    BoxWithConstraints(Modifier.widthIn(max = 340.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(CameraScrim).shieldCameraTaps().padding(horizontal = 12.dp)
        .semantics { contentDescription = if (audio) "Consenti il microfono oppure scegli una ripresa senza audio." else "Consenti la fotocamera per registrare." }) {
        val explanationHeight = (30 * LocalDensity.current.fontScale).dp + 56.dp
        val showExplanation = maxHeight >= explanationHeight
        Box(Modifier.fillMaxWidth().height(if (showExplanation) explanationHeight else 48.dp)) {
            // Reserve the complete touch row first. Explanations use only genuinely spare height.
            Row(Modifier.align(Alignment.BottomCenter).height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraMiniButton(if (audio) Icons.Default.Mic else Icons.Default.CameraAlt, "Consenti", if (audio) "Consenti microfono" else "Consenti fotocamera", "camera_permission", if (audio) ({ actions.onAudioToggle(true) }) else actions.onPermission)
                if (audio) CameraMiniButton(Icons.Default.MicOff, "Muto", "Scegli ripresa senza audio", "record_silent_choice", actions.onSilentChoice, active = true)
                CameraMiniButton(Icons.Default.Settings, "Permessi", "Apri impostazioni permessi", "camera_settings", actions.onSettings)
            }
            if (showExplanation) Text(if (audio) "Consenti il microfono o scegli senza audio." else "Consenti la fotocamera per registrare.", color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        }
    }
}

private fun Modifier.shieldCameraTaps(): Modifier = pointerInput(Unit) { detectTapGestures(onTap = {}) }
