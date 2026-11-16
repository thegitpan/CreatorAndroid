package it.panebarco.creator.media

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test

class CameraRotationTargetsTest {
    @Test fun lockedDisplayDoesNotFollowCaptureSensorWhenPhoneIsTurned() {
        val targets = CameraRotationTargets(Surface.ROTATION_0)
        assertTrue(targets.sensorChanged(270, CameraUiState()))
        assertEquals(Surface.ROTATION_90, targets.capture)
        assertEquals(Surface.ROTATION_0, targets.preview)
        assertTrue(targets.displayChanged(Surface.ROTATION_90))
        assertTrue(targets.sensorChanged(90, CameraUiState()))
        assertEquals(Surface.ROTATION_270, targets.capture)
        assertEquals(Surface.ROTATION_90, targets.preview)
    }

    @Test fun displayKeepsFollowingRotationWhileTakeKeepsItsOriginalCaptureTarget() {
        for (state in listOf(CameraUiState(starting = true), CameraUiState(recording = true), CameraUiState(finalizing = true))) {
            val targets = CameraRotationTargets(Surface.ROTATION_90)
            assertFalse(targets.sensorChanged(90, state))
            assertTrue(targets.displayChanged(Surface.ROTATION_270))
            assertEquals(Surface.ROTATION_270, targets.preview)
            assertEquals(Surface.ROTATION_90, targets.capture)
            assertFalse(targets.sensorChanged(0, state))
            assertEquals(Surface.ROTATION_90, targets.capture)
            assertTrue(targets.sensorChanged(90, CameraUiState()))
            assertEquals(Surface.ROTATION_270, targets.capture)
        }
    }

    @Test fun unknownSensorAndRepeatedDisplayEventsKeepTheLastValidTargets() {
        val targets = CameraRotationTargets(Surface.ROTATION_270)
        assertFalse(targets.sensorChanged(OrientationEventListener.ORIENTATION_UNKNOWN, CameraUiState()))
        assertFalse(targets.displayChanged(Surface.ROTATION_270))
        assertEquals(Surface.ROTATION_270, targets.capture)
        assertEquals(Surface.ROTATION_270, targets.preview)
    }
}
