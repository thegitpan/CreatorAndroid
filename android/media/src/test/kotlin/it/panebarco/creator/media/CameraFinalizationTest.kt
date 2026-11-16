package it.panebarco.creator.media

import androidx.camera.video.VideoRecordEvent.Finalize
import org.junit.Assert.*
import org.junit.Test

class CameraFinalizationTest {
    @Test fun onlyDocumentedRecoverableCandidatesReachSavedFileProbe() {
        for (code in listOf(Finalize.ERROR_NONE, Finalize.ERROR_SOURCE_INACTIVE, Finalize.ERROR_INSUFFICIENT_STORAGE,
            Finalize.ERROR_DURATION_LIMIT_REACHED, Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)) {
            assertTrue(CameraFinalization.mayContainValidFile(code))
        }
        for (code in listOf(Finalize.ERROR_UNKNOWN, Finalize.ERROR_ENCODING_FAILED, Finalize.ERROR_RECORDER_ERROR,
            Finalize.ERROR_INVALID_OUTPUT_OPTIONS, Finalize.ERROR_NO_VALID_DATA, Finalize.ERROR_RECORDING_GARBAGE_COLLECTED, 999)) {
            assertFalse(CameraFinalization.mayContainValidFile(code))
        }
        assertNull(CameraFinalization.interruptionWarning(Finalize.ERROR_NONE))
        assertTrue(requireNotNull(CameraFinalization.interruptionWarning(Finalize.ERROR_SOURCE_INACTIVE)).contains("salvato"))
    }
}
