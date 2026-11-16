@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package it.panebarco.creator.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet
import kotlin.math.abs

/** Observes only PCM actually consumed by the ordinary AudioTrack sink; forwarding is unchanged. */
internal class MarkerAudioSink(context: Context) : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
    private var format: Format? = null
    private val activeMillis = TreeSet<Long>()

    override fun configure(config: AudioSink.AudioSinkConfig) {
        format = config.format
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val value = requireNotNull(format)
        val bytesPerFrame = Util.getPcmFrameSize(value.pcmEncoding, value.channelCount)
        val initial = buffer.position()
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        copy.position(initial)
        copy.limit(buffer.position())
        // PlaybackAudioGraphWrapper advances this PTS after each partial AudioTrack write.
        var frame = 0
        synchronized(activeMillis) {
            while (copy.remaining() >= bytesPerFrame) {
                var peak = 0f
                repeat(value.channelCount) {
                    val sample = when (value.pcmEncoding) {
                        C.ENCODING_PCM_16BIT -> copy.short / 32768f
                        C.ENCODING_PCM_FLOAT -> copy.float
                        else -> error("Unexpected preview PCM encoding ${value.pcmEncoding}")
                    }
                    peak = maxOf(peak, abs(sample))
                }
                val timeUs = presentationTimeUs + frame.toLong() * 1_000_000L / value.sampleRate
                if (peak > .2f && timeUs >= 0L) activeMillis += timeUs / 1_000L
                frame++
            }
        }
        return handled
    }

    fun markerTimesUs(): List<Long> = synchronized(activeMillis) {
        val times = activeMillis.toList()
        times.filterIndexed { index, time -> index == 0 || time - times[index - 1] > 5L }.map { it * 1_000L }
    }

    fun clearMarkers() { synchronized(activeMillis) { activeMillis.clear() } }
}
