@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package it.panebarco.creator.media

import android.content.Context
import android.media.MediaCodec
import android.media.metrics.LogSessionId
import android.os.Looper
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.SampleConsumer
import java.nio.ByteBuffer

/**
 * Media3 1.11.1's export audio renderer drops negative compressed timestamps before decoding.
 * Starting a clipped AAC stream that way loses decoder preroll and shifts its audio content.
 * Decode codec/GOP-defined preroll, then select PCM samples and video frames by timestamp.
 * The original Composition and one loader/playback thread per item are retained.
 *
 * Unknown audio codecs retain full-prefix decoding. No guessed AAC delay is applied.
 */
internal class PrerollAssetLoaderFactory(
    private val context: Context,
    clips: List<MediaClip>,
) : AssetLoader.Factory {
    private val clipsById = clips.associateBy(MediaClip::id)
    // Construction runs on Dispatchers.IO; media extraction never runs on the main thread.
    private val decodeStartById = clips.associate { it.id to DecoderPreroll.startUs(it.file, it.sourceStartUs) }

    override fun createAssetLoader(
        editedMediaItem: EditedMediaItem,
        looper: Looper,
        listener: AssetLoader.Listener,
        compositionSettings: AssetLoader.CompositionSettings,
    ): AssetLoader {
        val clip = requireNotNull(clipsById[editedMediaItem.mediaItem.mediaId])
        val decodeStartUs = decodeStartById.getValue(clip.id)
        val startInDecodedStreamUs = clip.sourceStartUs - decodeStartUs
        val endInDecodedStreamUs = clip.sourceEndUs - decodeStartUs
        val item = editedMediaItem.mediaItem.buildUpon()
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(decodeStartUs).setEndPositionUs(clip.sourceEndUs).build())
            .build()
        val input = EditedMediaItem.Builder(item)
            .setDurationUs(clip.source.durationUs)
            .setFrameRate(editedMediaItem.frameRate)
            .setRemoveAudio(editedMediaItem.removeAudio)
            .setRemoveVideo(editedMediaItem.removeVideo)
            .setEffects(editedMediaItem.effects)
            .build()
        val defaultDecoder = DefaultDecoderFactory.Builder(context).build()
        val decoders = object : Codec.DecoderFactory {
            override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec =
                WindowedAudioCodec(defaultDecoder.createForAudioDecoding(format, logSessionId), startInDecodedStreamUs, endInDecodedStreamUs)

            override fun createForVideoDecoding(format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean, logSessionId: LogSessionId?): Codec =
                WindowedVideoCodec(defaultDecoder.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId), startInDecodedStreamUs, endInDecodedStreamUs)
        }
        val callbacks = object : AssetLoader.Listener {
            override fun onDurationUs(durationUs: Long) { listener.onDurationUs(clip.durationUs) }
            override fun onTrackCount(trackCount: Int) { listener.onTrackCount(trackCount) }
            override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean {
                check(supportedOutputTypes and AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED != 0) { "Il video richiede un decoder non disponibile." }
                return listener.onTrackAdded(inputFormat, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED)
            }
            override fun onOutputFormat(format: Format): SampleConsumer? {
                val consumer = listener.onOutputFormat(format) ?: return null
                return if (MimeTypes.isVideo(format.sampleMimeType)) object : SampleConsumer {
                    override fun getInputSurface() = consumer.inputSurface
                    override fun getPendingVideoFrameCount() = consumer.pendingVideoFrameCount
                    override fun signalEndOfVideoInput() = consumer.signalEndOfVideoInput()
                    override fun setOnInputSurfaceReadyListener(runnable: Runnable) = consumer.setOnInputSurfaceReadyListener(runnable)
                    override fun registerVideoFrame(presentationTimeUs: Long): Boolean =
                        presentationTimeUs >= clip.durationUs || consumer.registerVideoFrame(presentationTimeUs)
                } else consumer
            }
            override fun onError(exportException: ExportException) { listener.onError(exportException) }
        }
        return ExoPlayerAssetLoader.Factory(context, decoders, Clock.DEFAULT)
            .createAssetLoader(input, looper, callbacks, compositionSettings)
    }
}

private class WindowedVideoCodec(
    private val delegate: Codec,
    private val startUs: Long,
    private val endUs: Long,
) : Codec by delegate {
    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
        val info = delegate.outputBufferInfo ?: return null
        // The standard renderer drops negative decoded timestamps without rendering to its Surface.
        val timestamp = info.presentationTimeUs - startUs
        return MediaCodec.BufferInfo().apply { set(info.offset, info.size, timestamp, info.flags) }
    }

    override fun releaseOutputBuffer(renderPresentationTimeUs: Long) {
        // The paired consumer does not register out-of-window frames; never send them to its Surface.
        if (renderPresentationTimeUs >= endUs - startUs) delegate.releaseOutputBuffer(false)
        else delegate.releaseOutputBuffer(renderPresentationTimeUs)
    }
}

private class WindowedAudioCodec(
    private val delegate: Codec,
    private val startUs: Long,
    private val endUs: Long,
) : Codec by delegate {
    private var prepared: ByteBuffer? = null
    private var preparedInfo: MediaCodec.BufferInfo? = null

    override fun getOutputBuffer(): ByteBuffer? {
        if (prepared != null) return prepared
        while (true) {
            val buffer = delegate.outputBuffer ?: return null
            val info = requireNotNull(delegate.outputBufferInfo)
            val format = requireNotNull(delegate.outputFormat)
            val bytesPerFrame = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            val window = PcmClipWindow.select(info.presentationTimeUs, buffer.remaining() / bytesPerFrame, format.sampleRate, startUs, endUs)
            if (window.firstFrame == window.endFrame) {
                delegate.releaseOutputBuffer(false)
                continue
            }
            val start = buffer.position() + window.firstFrame * bytesPerFrame
            val end = buffer.position() + window.endFrame * bytesPerFrame
            val selected = buffer.duplicate().apply { position(start); limit(end) }.slice().order(buffer.order())
            prepared = selected
            preparedInfo = MediaCodec.BufferInfo().apply { set(0, selected.remaining(), window.presentationTimeUs, info.flags) }
            return selected
        }
    }

    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
        getOutputBuffer()
        return preparedInfo
    }

    override fun releaseOutputBuffer(render: Boolean) {
        prepared = null
        preparedInfo = null
        delegate.releaseOutputBuffer(false)
    }
}
