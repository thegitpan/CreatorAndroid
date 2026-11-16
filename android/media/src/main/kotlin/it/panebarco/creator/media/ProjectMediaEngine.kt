@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package it.panebarco.creator.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import it.panebarco.creator.domain.Project
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Main-thread Player ownership follows Media3; exports may be called from any coroutine. */
class ProjectMediaEngine(context: Context) {
    private val context = context.applicationContext
    private val activeOutputs = ConcurrentHashMap.newKeySet<String>()

    fun createPreview(project: Project, resolve: (String) -> File): Player {
        check(Looper.myLooper() == Looper.getMainLooper()) { "L'anteprima deve essere creata sul thread principale." }
        val composition = composition(MediaTimeline.from(project, resolve))
        return CompositionPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .also { player ->
                try {
                    player.setComposition(composition)
                    player.prepare()
                } catch (error: Throwable) {
                    player.release()
                    throw error
                }
            }
    }

    /** A successful return publishes one validated MP4; cancellation removes only this render. */
    suspend fun export(
        project: Project,
        resolve: (String) -> File,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        val timeline = withContext(Dispatchers.IO) { MediaTimeline.from(project, resolve) }
        val destination = withContext(Dispatchers.IO) { output.canonicalFile }
        require(timeline.clips.none { it.file.canonicalFile == destination }) { "L'esportazione deve usare un nuovo file; gli originali sono protetti." }
        require(activeOutputs.add(destination.path)) { "Un'esportazione verso questo file è già in corso." }
        val partial = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.render.mp4")
        var publication: ExportFiles.Publication? = null
        try {
            withContext(Dispatchers.IO) {
                require(!destination.exists()) { "Il file di destinazione esiste già. Scegli un nuovo nome." }
                require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Impossibile creare la cartella di esportazione." }
            }
            transform(composition(timeline), partial, onProgress)
            withContext(Dispatchers.IO) {
                ensureActive()
                val result = MediaProbe.inspect(partial)
                val displayWidth = if (result.rotationDegrees % 180 == 0) result.width else result.height
                val displayHeight = if (result.rotationDegrees % 180 == 0) result.height else result.width
                check(displayWidth == timeline.width && displayHeight == timeline.height) {
                    "L'encoder non ha prodotto il formato richiesto. Prova un altro dispositivo."
                }
                check(result.hasAudio) { "L'esportazione non contiene la traccia audio prevista." }
                val toleranceUs = Math.addExact(100_000L, Math.multiplyExact(timeline.clips.size.toLong(), 66_667L))
                check(abs(result.durationUs - timeline.durationUs) <= toleranceUs) {
                    "La durata esportata non corrisponde al montaggio. Il file non è stato pubblicato."
                }
                MediaProbe.verifyExportTracks(partial)
                FileOutputStream(partial, true).use { it.fd.sync() }
                ensureActive()
                publication = ExportFiles.publishNoReplace(partial, destination, checkCancellation = { ensureActive() })
            }
            withContext(Dispatchers.Main.immediate) { onProgress(100) }
        } catch (error: Throwable) {
            publication?.deleteIfOwned()
            throw error
        } finally {
            partial.delete()
            activeOutputs.remove(destination.path)
        }
    }

    private suspend fun transform(composition: Composition, partial: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            var poll: Runnable? = null
            val transformer = Transformer.Builder(context)
                .setLooper(Looper.getMainLooper())
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                .build()
            try {
                suspendCancellableCoroutine { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("Esportazione non riuscita (codice ${exportException.errorCode}). Verifica spazio e compatibilità del video.", exportException),
                            )
                        }
                    })
                    var lastProgress = -1
                    poll = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            try {
                                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    val progress = holder.progress.coerceIn(0, 99)
                                    if (progress != lastProgress) {
                                        onProgress(progress)
                                        lastProgress = progress
                                    }
                                }
                                handler.postDelayed(this, 200L)
                            } catch (error: Throwable) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                    try {
                        transformer.start(composition, partial.absolutePath)
                        handler.post(requireNotNull(poll))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            } finally {
                poll?.let(handler::removeCallbacks)
                // Transformer cancellation is synchronous and must precede deleting its partial.
                transformer.cancel()
            }
        }

    internal fun composition(timeline: MediaTimeline): Composition {
        val items = timeline.clips.map { clip ->
            val item = MediaItem.Builder()
                .setUri(clip.file.toURI().toString())
                .setMediaId(clip.id)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clip.sourceStartUs)
                        .setEndPositionUs(clip.sourceEndUs)
                        .setStartsAtKeyFrame(false)
                        .build(),
                ).build()
            // Container rotation is applied by the decoder exactly once, before Presentation.
            val mixing = ChannelMixingAudioProcessor().apply {
                for (channels in 1..8) {
                    val matrix = if (channels <= 2) {
                        ChannelMixingMatrix.createForConstantGain(channels, 2)
                    } else if (channels <= 6) {
                        ChannelMixingMatrix.createForConstantPower(channels, 2)
                    } else {
                        // Include every channel of uncommon 7/8-channel imports in a safe downmix.
                        ChannelMixingMatrix(channels, 2, FloatArray(channels * 2) { 1f / channels })
                    }
                    putChannelMixingMatrix(matrix.scaleBy(clip.volume))
                }
            }
            EditedMediaItem.Builder(item)
                .setDurationUs(clip.source.durationUs) // This API takes pre-trim source duration.
                .setFrameRate(30)
                .setRemoveAudio(!clip.source.hasAudio)
                .setEffects(
                    Effects(
                        listOf(ToInt16PcmAudioProcessor(), mixing, SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }),
                        listOf(Presentation.createForWidthAndHeight(timeline.width, timeline.height, Presentation.LAYOUT_SCALE_TO_FIT)),
                    ),
                ).build()
        }
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(items).build()
        return Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .setTransmuxAudio(false)
            .setTransmuxVideo(false)
            .build()
    }
}
