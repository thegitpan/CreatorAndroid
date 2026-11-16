package it.panebarco.creator.media

import android.graphics.Matrix
import android.os.Build
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView

/** Maps a touch through the live PreviewView factory, including its negotiated crop. */
internal object PreviewMetering {
    fun createPoint(view: PreviewView, front: Boolean, x: Float, y: Float): MeteringPoint {
        val boundedX = x.coerceIn(0f, view.width.toFloat())
        val boundedY = y.coerceIn(0f, view.height.toFloat())
        // In CameraX 1.6.2, the direct front-stream factory assumes mirroring even
        // when MIRROR_MODE_OFF is effective (API 33+). Cancel that extra reflection
        // in view coordinates, about the centered FIT_CENTER frame. A processed
        // stream or a corrected CameraX transform has positive determinant already.
        val values = FloatArray(9)
        val transform = if (front && Build.VERSION.SDK_INT >= 33) view.sensorToViewTransform else null
        transform?.getValues(values)
        val reflected = transform != null &&
            values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y] -
            values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y] < 0f
        val factoryX = if (reflected) view.width - boundedX else boundedX
        return view.meteringPointFactory.createPoint(factoryX, boundedY)
    }
}
