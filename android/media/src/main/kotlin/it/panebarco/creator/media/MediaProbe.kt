package it.panebarco.creator.media

import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import it.panebarco.creator.domain.SourceAsset
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object MediaProbe {
    /** Call off the UI thread. Source duration retains the extractor's microsecond precision. */
    fun inspect(file: File): SourceAsset {
        require(file.isFile && file.length() > 0L) { "Il file video è vuoto o non è disponibile." }
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            retriever.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var hasAudio = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when {
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && video == null -> video = format
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true -> hasAudio = true
                }
            }
            val format = requireNotNull(video) { "Il file non contiene una traccia video supportata." }
            val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { Math.multiplyExact(it, 1_000L) }
                ?: 0L
            val width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: 0
            val height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0
            val rotation = format.intOrNull(MediaFormat.KEY_ROTATION)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?: 0
            val normalizedRotation = ((rotation % 360) + 360) % 360
            require(durationUs > 0L && width > 0 && height > 0) { "Il video ha durata o dimensioni non valide; prova a importare un altro file." }
            require(normalizedRotation in setOf(0, 90, 180, 270)) { "La rotazione di questo video non è supportata." }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return SourceAsset(
                UUID.randomUUID().toString(), file.name, durationUs, width, height,
                normalizedRotation, hasAudio, digest.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Impossibile leggere il video. Il file potrebbe essere danneggiato o usare un codec non supportato.", error)
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    internal fun verifyExportTracks(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val types = formats.map { it.getString(MediaFormat.KEY_MIME) }
            check(types.count { it == "video/avc" } == 1 && types.count { it == "audio/mp4a-latm" } == 1 && types.size == 2) {
                "Il file esportato non contiene le tracce H.264/AAC previste."
            }
            val transfer = formats.single { it.getString(MediaFormat.KEY_MIME) == "video/avc" }.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            check(transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG) {
                "L'encoder non ha prodotto il video SDR richiesto."
            }
        } finally {
            extractor.release()
        }
    }

    /** The returned bitmap fits these bounds; caller owns it. Call off the UI thread. */
    fun thumbnail(file: File, positionUs: Long = 0L, maxWidth: Int = 320, maxHeight: Int = 180): Bitmap? {
        require(positionUs >= 0L && maxWidth > 0 && maxHeight > 0)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxWidth
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxHeight
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val displayWidth = if (rotation % 180 == 0) width else height
            val displayHeight = if (rotation % 180 == 0) height else width
            val scale = minOf(maxWidth.toDouble() / displayWidth, maxHeight.toDouble() / displayHeight)
            retriever.getScaledFrameAtTime(
                positionUs, MediaMetadataRetriever.OPTION_CLOSEST,
                max(1, (displayWidth * scale).roundToInt()), max(1, (displayHeight * scale).roundToInt()),
            )
        } finally {
            retriever.release()
        }
    }

    /** Peak amplitude in [0,1] for equal source-time buckets. Empty audio is actual silence. */
    suspend fun waveform(file: File, bins: Int = 96, startUs: Long = 0L, endUs: Long? = null): FloatArray =
        withContext(Dispatchers.IO) {
            require(bins in 1..4096 && startUs >= 0L) { "Intervallo waveform non valido." }
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val audioTrack = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@withContext FloatArray(bins)
                val format = extractor.getTrackFormat(audioTrack)
                val durationUs = endUs ?: format.longOrNull(MediaFormat.KEY_DURATION)
                    ?: throw IllegalArgumentException("Durata audio non disponibile.")
                require(durationUs > startUs) { "L'intervallo audio è vuoto." }
                extractor.selectTrack(audioTrack)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val codec = MediaCodec.createDecoderByType(mime)
                decoder = codec
                codec.configure(format, null, null, 0)
                codec.start()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 48_000
                var channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo()
                val peaks = FloatArray(bins)
                var lastOutputMs = SystemClock.elapsedRealtime()
                val intervalUs = durationUs - startUs
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = requireNotNull(codec.getInputBuffer(inputIndex))
                            val count = extractor.readSampleData(input, 0)
                            val timestampUs = extractor.sampleTime
                            if (count < 0 || timestampUs >= durationUs) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, count, timestampUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = codec.outputFormat
                            sampleRate = actual.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            channels = actual.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            encoding = actual.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                            require(sampleRate > 0 && channels > 0)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> check(SystemClock.elapsedRealtime() - lastOutputMs < 15_000L) {
                            "Il decoder audio non risponde. Prova a importare un altro video."
                        }
                        else -> if (index >= 0) {
                            try {
                                val output = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytesPerSample = when (encoding) {
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                                    else -> throw IllegalArgumentException("Il formato audio decodificato non è supportato.")
                                }
                                val bytesPerFrame = bytesPerSample * channels
                                var frame = 0L
                                while (output.remaining() >= bytesPerFrame) {
                                    val timestampUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                                    var peak = 0f
                                    repeat(channels) {
                                        val sample = when (encoding) {
                                            AudioFormat.ENCODING_PCM_8BIT -> ((output.get().toInt() and 255) - 128) / 128f
                                            AudioFormat.ENCODING_PCM_16BIT -> output.short / 32768f
                                            AudioFormat.ENCODING_PCM_FLOAT -> output.float
                                            else -> output.int / 2147483648f
                                        }
                                        if (sample.isFinite()) peak = max(peak, abs(sample).coerceAtMost(1f))
                                    }
                                    if (timestampUs >= startUs && timestampUs < durationUs) {
                                        val bucket = (((timestampUs - startUs).toDouble() / intervalUs) * bins).toInt().coerceIn(0, bins - 1)
                                        peaks[bucket] = max(peaks[bucket], peak)
                                    }
                                    frame++
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastOutputMs = SystemClock.elapsedRealtime()
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
                peaks
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("Impossibile analizzare l'audio di questo video.", error)
            } finally {
                decoder?.let { codec ->
                    runCatching { codec.stop() }
                    codec.release()
                }
                extractor.release()
            }
        }
}

internal fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
internal fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
