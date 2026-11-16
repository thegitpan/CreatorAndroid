package it.panebarco.creator.media

import androidx.camera.video.VideoRecordEvent.Finalize

/** Classification follows the CameraX 1.6.2 Finalize source contract; every candidate is probed. */
internal object CameraFinalization {
    fun mayContainValidFile(error: Int): Boolean = error in setOf(
        Finalize.ERROR_NONE,
        Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        Finalize.ERROR_INSUFFICIENT_STORAGE,
        Finalize.ERROR_SOURCE_INACTIVE,
        Finalize.ERROR_DURATION_LIMIT_REACHED,
    )

    fun interruptionWarning(error: Int): String? = when (error) {
        Finalize.ERROR_NONE -> null
        Finalize.ERROR_INSUFFICIENT_STORAGE -> "Spazio esaurito: il segmento valido è stato salvato. Libera spazio prima di continuare."
        Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "Limite del file raggiunto: il segmento valido è stato salvato. Continua con una nuova ripresa."
        Finalize.ERROR_DURATION_LIMIT_REACHED -> "Limite di durata raggiunto: il segmento valido è stato salvato. Continua con una nuova ripresa."
        Finalize.ERROR_SOURCE_INACTIVE -> "Ripresa interrotta: il segmento valido è stato salvato. Riapri la videocamera per continuare."
        else -> null
    }
}
