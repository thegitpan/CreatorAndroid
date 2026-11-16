@file:Suppress("RestrictedApi")

package androidx.camera.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.util.LayoutDirection
import android.util.Size
import androidx.camera.core.SurfaceRequest

/** Test-only access to the shipped CameraX factory; no copied transform implementation. */
object PreviewMeteringFixture {
    fun create(
        context: Context,
        width: Int,
        height: Int,
        targetRotation: Int,
        rotationDegrees: Int,
        front: Boolean = true,
        hasCameraTransform: Boolean = true,
        mirroring: Boolean = false,
    ): PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        layout(0, 0, width, height)
        // Samsung Camera1: sensor 270 degrees, 4000x3000 active array, 1920x1080 stream.
        // The affine matrix models the centered 16:9 crop (0,375)-(4000,2625).
        val sensorToBuffer = Matrix().apply {
            setValues(floatArrayOf(.48f, 0f, 0f, 0f, .48f, -180f, 0f, 0f, 1f))
        }
        mPreviewTransform.setTransformationInfo(
            SurfaceRequest.TransformationInfo.of(
                Rect(0, 0, 1920, 1080), rotationDegrees, targetRotation,
                hasCameraTransform, sensorToBuffer, mirroring,
            ),
            Size(1920, 1080), front,
        )
        mPreviewViewMeteringPointFactory.setSensorRect(Rect(0, 0, 4000, 3000))
        mPreviewViewMeteringPointFactory.recalculate(Size(width, height), LayoutDirection.LTR)
    }
}
