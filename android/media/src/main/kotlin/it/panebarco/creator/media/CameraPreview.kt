package it.panebarco.creator.media

import androidx.camera.view.PreviewView

/** Keeps CameraX's native front mirror contract behind the unmirrored preview shown to users. */
internal object CameraPreview {
    fun configure(view: PreviewView, front: Boolean) {
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        view.scaleType = PreviewView.ScaleType.FIT_CENTER
        // Keep the native stream mirrored for CameraX's rotation/metering contract.
        // Reflect only its display; Android's inverse touch dispatch supplies local X.
        view.scaleX = if (front) -1f else 1f
    }
}
