package it.panebarco.creator.media

import android.view.OrientationEventListener
import android.view.Surface

/** Preview follows the display; a take keeps its sensor target until finalization ends. */
internal class CameraRotationTargets(initialDisplayRotation: Int) {
    var preview: Int = initialDisplayRotation
        private set
    var capture: Int = initialDisplayRotation
        private set

    fun displayChanged(rotation: Int): Boolean {
        if (rotation == preview) return false
        preview = rotation
        return true
    }

    fun sensorChanged(orientation: Int, state: CameraUiState): Boolean {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN ||
            state.starting || state.recording || state.finalizing) return false
        val rotation = when (orientation) {
            in 45..134 -> Surface.ROTATION_270
            in 135..224 -> Surface.ROTATION_180
            in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rotation == capture) return false
        capture = rotation
        return true
    }
}
