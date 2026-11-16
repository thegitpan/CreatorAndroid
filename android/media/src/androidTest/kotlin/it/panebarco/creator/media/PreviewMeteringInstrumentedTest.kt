package it.panebarco.creator.media

import android.view.Surface
import androidx.camera.view.PreviewMeteringFixture
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewMeteringInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    @SdkSuppress(minSdkVersion = 33) // Preview MIRROR_MODE_OFF is effective starting at API 33.
    fun unmirroredFrontTapReachesSameSensorPointInBothLandscapeDirections() = onMain {
        assertSensorPoint(2340, 1080, Surface.ROTATION_90, 0, 690f, 810f)
        assertSensorPoint(2340, 1080, Surface.ROTATION_270, 180, 1650f, 270f)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun unmirroredFrontTapReachesSameSensorPointInBothPortraitDirections() = onMain {
        assertSensorPoint(1080, 2340, Surface.ROTATION_0, 270, 810f, 1650f)
        assertSensorPoint(1080, 2340, Surface.ROTATION_180, 90, 270f, 690f)
    }

    @Test fun rearAndAlreadyProcessedUnmirroredFrontKeepTheirCoordinates() = onMain {
        assertSensorPoint(2340, 1080, Surface.ROTATION_90, 0, 690f, 810f, front = false)
        assertSensorPoint(2340, 1080, Surface.ROTATION_90, 0, 690f, 810f, hasCameraTransform = false)
    }

    @Test
    @SdkSuppress(maxSdkVersion = 32)
    fun legacyFrontMirroredPreviewKeepsItsExistingTapCorrespondence() = onMain {
        val view = PreviewMeteringFixture.create(
            instrumentation.targetContext, 2340, 1080, Surface.ROTATION_90, 0,
            mirroring = true,
        )
        val point = PreviewMetering.createPoint(view, true, 690f, 810f)
        // API 31/32 ignore Preview MIRROR_MODE_OFF; the left tap sees the mirrored right point.
        assertEquals(.75f, point.x, .0001f)
        assertEquals(.6875f, point.y, .0001f)
    }

    @Test fun unreadyPreviewStillReturnsCameraXInvalidPoint() = onMain {
        val view = PreviewView(instrumentation.targetContext).apply { layout(0, 0, 2340, 1080) }
        val point = PreviewMetering.createPoint(view, true, 690f, 810f)
        assertEquals(2f, point.x, 0f)
        assertEquals(2f, point.y, 0f)
    }

    private fun assertSensorPoint(
        width: Int,
        height: Int,
        targetRotation: Int,
        rotationDegrees: Int,
        x: Float,
        y: Float,
        front: Boolean = true,
        hasCameraTransform: Boolean = true,
    ) {
        val view = PreviewMeteringFixture.create(
            instrumentation.targetContext, width, height, targetRotation, rotationDegrees,
            front, hasCameraTransform,
        )
        val point = PreviewMetering.createPoint(view, front, x, y)
        // Known scene point (1000,2062.5) on the 4000x3000 sensor. Each literal tap
        // reaches that point after rotation and FIT_CENTER bands; it must not hit its reflection.
        assertEquals("Sensor x at target $targetRotation", .25f, point.x, .0001f)
        assertEquals("Sensor y at target $targetRotation", .6875f, point.y, .0001f)
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        requireNotNull(result).getOrThrow()
    }
}
