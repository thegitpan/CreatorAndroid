@file:androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package it.panebarco.creator.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.MeteringPoint
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Requested 1080p/30 SDR; [qualityLabel] reports the negotiated stream without hardware claims. */
data class CameraUiState(
    val ready: Boolean = false,
    val recording: Boolean = false,
    val starting: Boolean = false,
    val finalizing: Boolean = false,
    val error: String? = null,
    val durationUs: Long = 0L,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val front: Boolean = false,
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val canFocus: Boolean = false,
    val canSwitchWhileRecording: Boolean = false,
    val canLockFocus: Boolean = false,
    val canLockExposure: Boolean = false,
    val focusLocked: Boolean = false,
    val exposureLocked: Boolean = false,
    val focusLockPending: Boolean = false,
    val exposureLockPending: Boolean = false,
    /** Display width / height of the negotiated preview crop, after its target rotation. */
    val previewAspectRatio: Float? = null,
    val qualityLabel: String = "Richiesti 1080p · 30 fps · SDR",
)

/** Pause is deliberately stop/finalize; Resume creates a fresh immutable source segment. */
class CameraSession(context: Context) {
    private val context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mutableState = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = mutableState.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var recording: Recording? = null
    private var completion: ((Result<File>) -> Unit)? = null
    private val finalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    private var bindGeneration = 0L
    private var rotations = CameraRotationTargets(Surface.ROTATION_0)
    private val lockHandler = Handler(Looper.getMainLooper())
    private val displayManager = this.context.getSystemService(DisplayManager::class.java)
    private var listeningToDisplay = false
    private var focusLockRequested = false
    private var focusLockApplied = false
    private var exposureLockRequested = false
    private var exposureLockApplied = false
    private var focusLockEpoch = 0L
    private var exposureLockEpoch = 0L
    private var lastFocusPoint: MeteringPoint? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { stop() }
        override fun onDestroy(owner: LifecycleOwner) { close() }
    }
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (closed) return
            if (rotations.sensorChanged(orientation, mutableState.value)) {
                capture?.targetRotation = rotations.capture
                updateQuality()
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (previewView?.display?.displayId == displayId) syncPreviewRotation()
        }
    }
    private val previewAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (previewView === view) syncPreviewRotation()
        }
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    @MainThread
    fun bind(owner: LifecycleOwner, previewView: PreviewView, front: Boolean) {
        checkMain()
        if (closed) {
            fail("La videocamera è stata chiusa. Riapri la schermata Registra.")
            return
        }
        if (mutableState.value.starting || mutableState.value.recording || mutableState.value.finalizing) {
            fail("Attendi il salvataggio della ripresa prima di cambiare videocamera.")
            return
        }
        stopRotationTracking()
        unbindOwnedUseCases()
        this.owner?.lifecycle?.removeObserver(lifecycleObserver)
        this.owner = owner
        this.previewView = previewView
        owner.lifecycle.addObserver(lifecycleObserver)
        // CameraX 1.6.2's TextureView rotation assumes a mirrored front stream.
        // Use the display's SurfaceView path only where Preview can disable that mirror.
        previewView.implementationMode = if (front && Build.VERSION.SDK_INT >= 33) {
            PreviewView.ImplementationMode.PERFORMANCE
        } else PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        rotations = CameraRotationTargets(previewView.display?.rotation ?: Surface.ROTATION_0)
        mutableState.update { it.copy(ready = false, front = front, error = null) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Consenti l'accesso alla fotocamera per registrare un video.")
            return
        }
        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || generation != bindGeneration) return@addListener
            try {
                provider = future.get()
                bindAvailableCamera(front)
                displayManager.registerDisplayListener(displayListener, lockHandler)
                listeningToDisplay = true
                previewView.addOnAttachStateChangeListener(previewAttachListener)
                syncPreviewRotation()
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            } catch (error: Exception) {
                fail("Impossibile avviare la fotocamera. Chiudi altre app che la usano e riprova.")
            }
        }, mainExecutor)
    }

    private fun bindAvailableCamera(front: Boolean) {
        val provider = requireNotNull(provider)
        val owner = requireNotNull(owner)
        val view = requireNotNull(previewView)
        view.display?.rotation?.let(rotations::displayChanged)
        val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        mutableState.update { it.copy(hasFrontCamera = hasFront, hasBackCamera = hasBack) }
        require(if (front) hasFront else hasBack) { "La fotocamera scelta non è disponibile." }
        unbindOwnedUseCases()
        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotations.preview)
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
        val callbackGeneration = bindGeneration
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                // Observe Camera2 results only; all requests remain owned by CameraX.
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val aeLock = result.get(CaptureResult.CONTROL_AE_LOCK) == true
                mainExecutor.execute {
                    if (closed || callbackGeneration != bindGeneration) return@execute
                    val focusLocked = focusLockRequested && focusLockApplied &&
                        afState in setOf(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
                    val exposureLocked = exposureLockRequested && exposureLockApplied && aeLock && aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                    mutableState.update { it.copy(
                        focusLocked = focusLocked, exposureLocked = exposureLocked,
                        focusLockPending = it.focusLockPending && !focusLocked,
                        exposureLockPending = it.exposureLockPending && !exposureLocked,
                    ) }
                }
            }
        })
        val preview = previewBuilder.build()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .build()
        val capture = VideoCapture.Builder(recorder)
            .setTargetRotation(rotations.capture)
            .setTargetFrameRate(Range(30, 30))
            .setDynamicRange(DynamicRange.SDR)
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)
        this.preview = preview
        this.capture = capture
        val camera = provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        this.camera = camera
        val center = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
        val canFocus = camera.cameraInfo.isFocusMeteringSupported(FocusMeteringAction.Builder(center).build())
        val camera2Info = runCatching { Camera2CameraInfo.from(camera.cameraInfo) }.getOrNull()
        val afModes = camera2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val minFocusDistance = camera2Info?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val canLockFocus = minFocusDistance > 0f && afModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true &&
            camera.cameraInfo.isFocusMeteringSupported(FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF).build())
        val canLockExposure = camera2Info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        mutableState.update { it.copy(front = front, canFocus = canFocus, canLockFocus = canLockFocus, canLockExposure = canLockExposure, error = null) }
        camera.cameraInfo.zoomState.observe(owner) { zoom ->
            if (this.camera === camera && zoom != null) mutableState.update {
                it.copy(zoomRatio = zoom.zoomRatio, minZoomRatio = zoom.minZoomRatio, maxZoomRatio = zoom.maxZoomRatio)
            }
        }
        camera.cameraInfo.cameraState.observe(owner) { value ->
            if (this.camera !== camera) return@observe
            mutableState.update { state ->
                state.copy(
                    ready = value.type == CameraState.Type.OPEN && !closed,
                    error = value.error?.let { "La fotocamera non è disponibile (codice ${it.code}). Chiudi altre app e riprova." } ?: state.error,
                )
            }
            updateQuality()
        }
        updateQuality()
    }

    private fun syncPreviewRotation() {
        if (closed) return
        val display = previewView?.display ?: return
        if (rotations.displayChanged(display.rotation)) {
            preview?.targetRotation = rotations.preview
            updateQuality()
        }
    }

    private fun stopRotationTracking() {
        orientationListener.disable()
        if (listeningToDisplay) {
            displayManager.unregisterDisplayListener(displayListener)
            listeningToDisplay = false
        }
        previewView?.removeOnAttachStateChangeListener(previewAttachListener)
    }

    @MainThread
    fun start(output: File, audioEnabled: Boolean, onFinalized: (Result<File>) -> Unit) {
        checkMain()
        fun reject(message: String) {
            fail(message)
            onFinalized(Result.failure(IllegalStateException(message)))
        }
        if (closed || !mutableState.value.ready || capture == null) { reject("La fotocamera non è pronta. Attendi l'anteprima e riprova."); return }
        if (recording != null || mutableState.value.finalizing) { reject("Una ripresa è già in corso o in salvataggio."); return }
        if (audioEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reject("Consenti l'accesso al microfono oppure scegli esplicitamente una ripresa senza audio.")
            return
        }
        if (output.exists() && output.length() > 0L) { reject("Il file scelto contiene già una ripresa. Gli originali sono protetti."); return }
        if (output.parentFile?.let { it.isDirectory || it.mkdirs() } != true) { reject("Impossibile creare il file di registrazione. Verifica lo spazio disponibile."); return }
        try {
            var pending = requireNotNull(capture).output.prepareRecording(context, FileOutputOptions.Builder(output).build())
            if (audioEnabled) pending = pending.withAudioEnabled()
            completion = onFinalized
            mutableState.update { it.copy(starting = true, recording = false, finalizing = false, durationUs = 0L, error = null) }
            recording = pending.start(mainExecutor) { event ->
                val durationUs = event.recordingStats.recordedDurationNanos / 1_000L
                when (event) {
                    is VideoRecordEvent.Start -> mutableState.update { it.copy(starting = false, recording = !it.finalizing) }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val callback = completion
                        completion = null
                        mutableState.update { it.copy(starting = false, recording = false, finalizing = true, durationUs = durationUs) }
                        finalizationScope.launch {
                            val inspected = if (CameraFinalization.mayContainValidFile(event.error)) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val metadata = MediaProbe.inspect(output)
                                        // A readable manifest alone does not establish a decodable saved take.
                                        val frame = requireNotNull(MediaProbe.thumbnail(output, 0L, 64, 64)) {
                                            "La ripresa non contiene fotogrammi decodificabili."
                                        }
                                        frame.recycle()
                                        metadata
                                    }
                                }
                            } else Result.failure(IllegalStateException("Errore del registratore (codice ${event.error}).", event.cause))
                            val error = if (inspected.isFailure) {
                                "Registrazione non salvata (codice ${event.error}). Il file temporaneo è conservato per il recupero; verifica spazio e permessi."
                            } else CameraFinalization.interruptionWarning(event.error)
                            mutableState.update {
                                it.copy(starting = false, recording = false, finalizing = false,
                                    durationUs = inspected.getOrNull()?.durationUs ?: durationUs, error = error)
                            }
                            try {
                                callback?.invoke(if (inspected.isSuccess) Result.success(output) else Result.failure(IllegalStateException(error, inspected.exceptionOrNull())))
                            } finally {
                                if (closed) {
                                    unbindOwnedUseCases()
                                    this@CameraSession.owner = null
                                    finalizationScope.cancel()
                                }
                            }
                        }
                    }
                    else -> mutableState.update { it.copy(durationUs = durationUs) }
                }
            }
        } catch (error: Exception) {
            recording = null
            completion = null
            val message = "Impossibile iniziare la ripresa. Verifica permessi e spazio disponibile."
            mutableState.update { it.copy(starting = false, recording = false, finalizing = false, error = message) }
            onFinalized(Result.failure(IllegalStateException(message, error)))
        }
    }

    @MainThread
    fun stop() {
        checkMain()
        val current = recording ?: return
        if (mutableState.value.finalizing) return
        mutableState.update { it.copy(starting = false, recording = false, finalizing = true) }
        current.stop()
    }

    @MainThread
    fun switchCamera() {
        checkMain()
        if (mutableState.value.starting || mutableState.value.recording || mutableState.value.finalizing) {
            fail("Il cambio camera è disponibile tra le riprese. Metti in pausa e attendi il salvataggio.")
            return
        }
        val currentOwner = owner ?: return
        val view = previewView ?: return
        bind(currentOwner, view, !mutableState.value.front)
    }

    /** Zoom is optical/digital as negotiated by CameraX and clamped to the live device limits. */
    @MainThread
    fun setZoom(ratio: Float) {
        checkMain()
        val current = camera ?: return
        if (!ratio.isFinite()) return
        val zoom = mutableState.value
        val result = current.cameraControl.setZoomRatio(ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
        result.addListener({
            val error = runCatching { result.get() }.exceptionOrNull()
            if (error != null && error.cause !is CameraControl.OperationCanceledException && !closed && camera === current) {
                fail("Impossibile regolare lo zoom. Riprova.")
            }
        }, mainExecutor)
    }

    /** [x], [y] are pixels in the bound PreviewView, including its actual transform. */
    @MainThread
    fun focus(x: Float, y: Float) {
        checkMain()
        val current = camera ?: return
        val view = previewView ?: return
        if (!mutableState.value.canFocus || !x.isFinite() || !y.isFinite() || view.width == 0 || view.height == 0) return
        val point = PreviewMetering.createPoint(view, mutableState.value.front, x, y)
        lastFocusPoint = point
        if (focusLockRequested) {
            setFocusLocked(true)
            return
        }
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        if (!current.cameraInfo.isFocusMeteringSupported(action)) return
        val result = current.cameraControl.startFocusAndMetering(action)
        result.addListener({
            val error = runCatching { result.get() }.exceptionOrNull()
            if (error != null && error.cause !is CameraControl.OperationCanceledException && !closed && camera === current) {
                fail("Messa a fuoco non disponibile in questo momento.")
            }
        }, mainExecutor)
    }

    /** Locks AF only. The state becomes locked after an actual Camera2 AF lock result. */
    @MainThread
    fun setFocusLocked(locked: Boolean) {
        checkMain()
        val current = camera ?: return
        val epoch = ++focusLockEpoch
        focusLockRequested = locked && mutableState.value.canLockFocus
        focusLockApplied = false
        mutableState.update { it.copy(focusLocked = false, focusLockPending = focusLockRequested) }
        if (!focusLockRequested) {
            current.cameraControl.cancelFocusAndMetering()
            if (locked) fail("Il blocco del fuoco non è supportato da questa fotocamera.")
            return
        }
        val point = lastFocusPoint ?: SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        val future = current.cameraControl.startFocusAndMetering(action)
        future.addListener({
            if (closed || camera !== current || epoch != focusLockEpoch) return@addListener
            val result = runCatching { future.get() }
            if (result.isSuccess) {
                focusLockApplied = true
                if (!result.getOrThrow().isFocusSuccessful) fail("Fuoco bloccato senza conferma di nitidezza. Tocca un dettaglio e riprova.")
            } else {
                setFocusLocked(false)
                fail("Impossibile bloccare il fuoco. Riprova dopo aver toccato il soggetto.")
            }
        }, mainExecutor)
        lockHandler.postDelayed({
            if (!closed && camera === current && epoch == focusLockEpoch && mutableState.value.focusLockPending) {
                setFocusLocked(false)
                fail("La fotocamera non ha confermato il blocco del fuoco. Riprova.")
            }
        }, 8_000L)
    }

    /** AE lock is independent of AF; it uses AE_LOCK_AVAILABLE and confirmed capture results. */
    @MainThread
    fun setExposureLocked(locked: Boolean) {
        checkMain()
        val current = camera ?: return
        val epoch = ++exposureLockEpoch
        exposureLockRequested = locked && mutableState.value.canLockExposure
        exposureLockApplied = false
        mutableState.update { it.copy(exposureLocked = false, exposureLockPending = exposureLockRequested) }
        if (locked && !exposureLockRequested) {
            fail("Il blocco dell'esposizione non è supportato da questa fotocamera.")
            return
        }
        val options = CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, exposureLockRequested).build()
        val future = Camera2CameraControl.from(current.cameraControl).addCaptureRequestOptions(options)
        future.addListener({
            if (closed || camera !== current || epoch != exposureLockEpoch) return@addListener
            if (runCatching { future.get() }.isSuccess) {
                exposureLockApplied = exposureLockRequested
            } else {
                exposureLockRequested = false
                exposureLockApplied = false
                mutableState.update { it.copy(exposureLockPending = false, exposureLocked = false) }
                fail("Impossibile confermare il blocco dell'esposizione. Riprova.")
            }
        }, mainExecutor)
        if (exposureLockRequested) lockHandler.postDelayed({
            if (!closed && camera === current && epoch == exposureLockEpoch && mutableState.value.exposureLockPending) {
                setExposureLocked(false)
                fail("La fotocamera non ha confermato il blocco dell'esposizione. Riprova.")
            }
        }, 3_000L)
    }

    @MainThread
    fun close() {
        checkMain()
        if (closed) return
        closed = true
        bindGeneration++
        stopRotationTracking()
        lockHandler.removeCallbacksAndMessages(null)
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        stop()
        // Keep the use cases until Finalize where lifecycle permits; interrupted files are probed.
        if (completion == null && !mutableState.value.finalizing) {
            unbindOwnedUseCases()
            owner = null
            finalizationScope.cancel()
        }
        previewView = null
        mutableState.update { it.copy(ready = false, previewAspectRatio = null) }
    }

    private fun unbindOwnedUseCases() {
        val previous = camera
        focusLockRequested = false
        focusLockApplied = false
        exposureLockRequested = false
        exposureLockApplied = false
        focusLockEpoch++
        exposureLockEpoch++
        lastFocusPoint = null
        lockHandler.removeCallbacksAndMessages(null)
        if (previous != null) {
            previous.cameraControl.cancelFocusAndMetering()
            runCatching { Camera2CameraControl.from(previous.cameraControl).clearCaptureRequestOptions() }
        }
        mutableState.update { it.copy(canLockFocus = false, canLockExposure = false, focusLocked = false, exposureLocked = false, focusLockPending = false, exposureLockPending = false, previewAspectRatio = null) }
        val previousOwner = owner
        if (previous != null && previousOwner != null) {
            previous.cameraInfo.zoomState.removeObservers(previousOwner)
            previous.cameraInfo.cameraState.removeObservers(previousOwner)
        }
        val cases = listOfNotNull(preview, capture)
        if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
        preview = null
        capture = null
        camera = null
    }

    private fun updateQuality() {
        // Preview and recording negotiate their own buffers. Overlay geometry follows Preview,
        // never the requested recording quality or a parsed user-facing label.
        val previewInfo = if (closed) null else preview?.resolutionInfo
        val previewAspectRatio = previewInfo?.let { info ->
            val width = info.cropRect.width()
            val height = info.cropRect.height()
            if (width <= 0 || height <= 0) null
            else if (info.rotationDegrees == 90 || info.rotationDegrees == 270) height.toFloat() / width
            else width.toFloat() / height
        }
        mutableState.update { it.copy(previewAspectRatio = previewAspectRatio) }
        val capture = capture ?: return
        val info = capture.resolutionInfo
        val quality = when (capture.selectedQuality) {
            Quality.FHD -> "1080p"
            Quality.HD -> "720p"
            Quality.SD -> "SD"
            Quality.UHD -> "4K"
            else -> "Profilo negoziato"
        }
        val geometry = info?.let { "${it.cropRect.width()}×${it.cropRect.height()} · rotazione ${it.rotationDegrees}°" }
        mutableState.update { it.copy(qualityLabel = "$quality${geometry?.let { value -> " · $value" } ?: ""} · SDR · richiesti 30 fps") }
    }

    private fun fail(message: String) { mutableState.update { it.copy(error = message) } }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "I controlli camera richiedono il thread principale." } }
}
