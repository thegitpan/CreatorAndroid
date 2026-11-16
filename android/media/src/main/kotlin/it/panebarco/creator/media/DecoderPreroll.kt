package it.panebarco.creator.media

import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Uses actual seek points, not a guessed duration or a correction to the output timestamps. */
internal object DecoderPreroll {
    fun startUs(file: File, clipStartUs: Long): Long {
        if (clipStartUs == 0L) return 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val audio = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (audio < 0 || video < 0) return 0L
            val audioFormat = formats[audio]
            // AAC-LC has one-frame overlap. Decode at least two complete actual AAC packets,
            // also covering any longer priming declared in the track format. Unknown codecs
            // keep full-prefix decoding until their own preroll behavior has been verified.
            if (audioFormat.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm" ||
                audioFormat.intOrNull(MediaFormat.KEY_AAC_PROFILE) != MediaCodecInfo.CodecProfileLevel.AACObjectLC) return 0L
            extractor.selectTrack(video)
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val videoSyncUs = extractor.sampleTime.takeIf { it >= 0L } ?: return 0L
            extractor.unselectTrack(video)
            extractor.selectTrack(audio)
            extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstUs = extractor.sampleTime
            if (firstUs < 0L || !extractor.advance()) return 0L
            val packetDurationUs = extractor.sampleTime - firstUs
            if (packetDurationUs <= 0L) return 0L
            val rate = audioFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: return 0L
            if (rate <= 0) return 0L
            val delay = audioFormat.intOrNull(MediaFormat.KEY_ENCODER_DELAY) ?: 0
            val declaredDelayUs = delay.toLong() * 1_000_000L / rate
            val prerollUs = maxOf(packetDurationUs * 2L, declaredDelayUs + packetDurationUs)
            extractor.seekTo((clipStartUs - prerollUs).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val audioSyncUs = extractor.sampleTime.takeIf { it >= 0L } ?: return 0L
            val candidateUs = minOf(videoSyncUs, audioSyncUs).coerceAtLeast(0L)
            // Some extractors return the first sample after a seek before the media's origin.
            return if (candidateUs <= clipStartUs) candidateUs else 0L
        } finally { extractor.release() }
    }
}
