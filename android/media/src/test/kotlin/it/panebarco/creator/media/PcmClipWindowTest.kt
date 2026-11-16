package it.panebarco.creator.media

import org.junit.Assert.*
import org.junit.Test

class PcmClipWindowTest {
    @Test fun trimsBothEndsAtIndividualSamplesAndRebasesTimestamp() {
        val selected = PcmClipWindow.select(1_000_000L, 1024, 48_000, 1_000_101L, 1_001_010L)
        assertEquals(5, selected.firstFrame)
        assertEquals(49, selected.endFrame)
        assertEquals(3L, selected.presentationTimeUs)
    }

    @Test fun decoderPrerollAndTrailingPaddingCannotEnterTheClip() {
        val before = PcmClipWindow.select(0L, 1024, 48_000, 1_700_003L, 3_700_003L)
        assertEquals(1024, before.firstFrame)
        assertEquals(before.firstFrame, before.endFrame)
        val after = PcmClipWindow.select(3_720_000L, 1024, 48_000, 1_700_003L, 3_700_003L)
        assertEquals(0, after.firstFrame)
        assertEquals(after.firstFrame, after.endFrame)
    }

    @Test fun contiguousBuffersProduceOnlySamplesInsideExactWindow() {
        val startUs = 350_001L
        val endUs = 1_850_001L
        var kept = 0L
        for (frame in 0 until 96_000 step 1024) {
            val frames = minOf(1024, 96_000 - frame)
            val timeUs = frame * 1_000_000L / 48_000L
            val selected = PcmClipWindow.select(timeUs, frames, 48_000, startUs, endUs)
            kept += selected.endFrame - selected.firstFrame
        }
        assertEquals(72_000L, kept)
    }
}
