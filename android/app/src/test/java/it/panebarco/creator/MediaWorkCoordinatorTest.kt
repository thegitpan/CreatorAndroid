package it.panebarco.creator

import org.junit.Assert.*
import org.junit.Test

class MediaWorkCoordinatorTest {
    @Test fun exportReservationImmediatelyExcludesCameraAndReleasesByExactJob() {
        val token = MediaWorkCoordinator.reserveExport()
        try {
            assertFalse(MediaWorkCoordinator.cameraAllowed())
            assertNull(MediaWorkCoordinator.acquireCamera())
            MediaWorkCoordinator.releaseExport("old-job")
            assertFalse(MediaWorkCoordinator.cameraAllowed())
        } finally { MediaWorkCoordinator.releaseExport(token) }
        val camera = requireNotNull(MediaWorkCoordinator.acquireCamera())
        try { assertThrows(UserFeedbackException::class.java) { MediaWorkCoordinator.reserveExport() } }
        finally { MediaWorkCoordinator.releaseCamera(camera) }
        assertTrue(MediaWorkCoordinator.cameraAllowed())
    }
}
