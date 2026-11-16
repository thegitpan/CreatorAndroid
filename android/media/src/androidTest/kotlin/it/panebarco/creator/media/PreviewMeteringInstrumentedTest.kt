package it.panebarco.creator.media

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewMeteringFixture
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewMeteringInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun frontTouchesReachSameUnmirroredScenePointInBothLandscapeDirections() = onMain {
        assertSensorPoint(2340, 1080, Surface.ROTATION_90, 0, 690f, 810f)
        assertSensorPoint(2340, 1080, Surface.ROTATION_270, 180, 1650f, 270f)
    }

    @Test fun frontTouchesReachSameUnmirroredScenePointInBothPortraitDirections() = onMain {
        assertSensorPoint(1080, 2340, Surface.ROTATION_0, 270, 810f, 1650f)
        assertSensorPoint(1080, 2340, Surface.ROTATION_180, 90, 270f, 690f)
    }

    @Test fun rearTouchesKeepTheirCoordinatesWithoutAViewReflection() = onMain {
        assertSensorPoint(2340, 1080, Surface.ROTATION_90, 0, 690f, 810f, front = false)
    }

    @Test fun changingFrontToRearAndBackResetsTheSamePreviewViewTransform() = onMain {
        val view = PreviewMeteringFixture.create(
            instrumentation.targetContext, 2340, 1080, Surface.ROTATION_90, 0,
            front = true, mirroring = true,
        )
        CameraPreview.configure(view, true)
        assertDispatchedPoint(view, 690f, 810f)
        CameraPreview.configure(view, false)
        // The native front stream remains mounted in this geometry fixture. A rear bind
        // resets the view reflection until CameraX replaces that stream.
        assertDispatchedPoint(view, 690f, 810f, expectedX = .75f)
        CameraPreview.configure(view, true)
        assertDispatchedPoint(view, 690f, 810f)
    }

    @Test fun unreadyPreviewStillReturnsCameraXInvalidPoint() = onMain {
        val view = PreviewView(instrumentation.targetContext).apply { layout(0, 0, 2340, 1080) }
        CameraPreview.configure(view, true)
        val point = view.meteringPointFactory.createPoint(690f, 810f)
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
    ) {
        val view = PreviewMeteringFixture.create(
            instrumentation.targetContext, width, height, targetRotation, rotationDegrees,
            front = front, mirroring = front,
        )
        CameraPreview.configure(view, front)
        assertDispatchedPoint(view, x, y)
    }

    private fun assertDispatchedPoint(view: PreviewView, x: Float, y: Float, expectedX: Float = .25f) {
        (view.parent as? FrameLayout)?.removeView(view)
        val parent = FrameLayout(instrumentation.targetContext).apply {
            addView(view, FrameLayout.LayoutParams(view.width, view.height))
            measure(
                View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, view.width, view.height)
        }
        var point: MeteringPoint? = null
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                point = view.meteringPointFactory.createPoint(event.x, event.y)
            }
            true
        }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + action, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("Public touch dispatch", parent.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
        val mapped = requireNotNull(point) { "The PreviewView touch listener must receive the gesture" }
        // Known unmirrored scene point (1000,2062.5) on the 4000x3000 sensor.
        // Parent-space taps pass through Android's inverse child matrix and the real
        // CameraX factory; no test code mirrors coordinates or copies its transform.
        assertEquals("Sensor x after public touch dispatch", expectedX, mapped.x, .0001f)
        assertEquals("Sensor y after public touch dispatch", .6875f, mapped.y, .0001f)
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        requireNotNull(result).getOrThrow()
    }
}
