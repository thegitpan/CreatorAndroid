package it.panebarco.creator.media

/** Sample boundaries are selected from timestamps, independent of codec priming or packet size. */
internal data class PcmClipWindow(val firstFrame: Int, val endFrame: Int, val presentationTimeUs: Long) {
    companion object {
        fun select(bufferTimeUs: Long, frames: Int, sampleRate: Int, startUs: Long, endUs: Long): PcmClipWindow {
            require(frames >= 0 && sampleRate > 0 && startUs >= 0L && endUs > startUs)
            fun boundary(timeUs: Long): Int {
                val deltaUs = timeUs - bufferTimeUs
                if (deltaUs <= 0L) return 0
                val bufferDurationCeilUs = (frames.toLong() * 1_000_000L + sampleRate - 1L) / sampleRate
                if (deltaUs >= bufferDurationCeilUs) return frames
                return ((deltaUs * sampleRate + 999_999L) / 1_000_000L).toInt().coerceIn(0, frames)
            }
            val first = boundary(startUs)
            val end = boundary(endUs).coerceAtLeast(first)
            val timestamp = bufferTimeUs + first.toLong() * 1_000_000L / sampleRate - startUs
            return PcmClipWindow(first, end, timestamp.coerceAtLeast(0L))
        }
    }
}
