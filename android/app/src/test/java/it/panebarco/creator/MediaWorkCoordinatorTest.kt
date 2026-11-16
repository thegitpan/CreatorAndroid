package it.panebarco.creator

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

class MediaWorkCoordinatorTest {
    @Test fun canceledPreparationBeforeDispatchReleasesItsReservationWithoutEnteringTheBody() {
        val canceled = Job().apply { cancel() }
        val first = requireNotNull(MediaWorkCoordinator.reserveRecording("already-canceled"))
        MediaWorkCoordinator.launchRecordingPreparation(CoroutineScope(canceled + Dispatchers.Unconfined), first) { fail("Canceled scope entered preparation") }
        assertNull(MediaWorkCoordinator.recording.value)

        val queue = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        }
        val parent = Job()
        val second = requireNotNull(MediaWorkCoordinator.reserveRecording("queued-then-canceled"))
        try {
            MediaWorkCoordinator.launchRecordingPreparation(CoroutineScope(parent + dispatcher), second) { fail("Canceled queued preparation entered its body") }
            parent.cancel()
            queue.toList().forEach(Runnable::run)
            assertNull(MediaWorkCoordinator.recording.value)
        } finally { parent.cancel(); MediaWorkCoordinator.releaseRecording(second) }
    }
    @Test fun recordingReservationSurvivesCameraReleaseAndBlocksRestartAndExportUntilCommitCompletes() {
        val camera = requireNotNull(MediaWorkCoordinator.acquireCamera())
        val takeA = requireNotNull(MediaWorkCoordinator.reserveRecording("project-A"))
        try {
            MediaWorkCoordinator.releaseCamera(camera) // Activity/preview can disappear before commit.
            assertNull(MediaWorkCoordinator.reserveRecording("project-B"))
            assertThrows(UserFeedbackException::class.java) { MediaWorkCoordinator.reserveExport() }
            MediaWorkCoordinator.releaseRecording("late-old-token")
            assertEquals(takeA, MediaWorkCoordinator.recording.value?.token)
            MediaWorkCoordinator.releaseRecording(takeA)
            val takeB = requireNotNull(MediaWorkCoordinator.reserveRecording("project-A"))
            try {
                MediaWorkCoordinator.releaseRecording(takeA)
                assertEquals(takeB, MediaWorkCoordinator.recording.value?.token)
            } finally { MediaWorkCoordinator.releaseRecording(takeB) }
        } finally { MediaWorkCoordinator.releaseCamera(camera); MediaWorkCoordinator.releaseRecording(takeA) }
    }
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
