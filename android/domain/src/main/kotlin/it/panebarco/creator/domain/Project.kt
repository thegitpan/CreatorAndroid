package it.panebarco.creator.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class CanvasShape { PORTRAIT, LANDSCAPE }

@Serializable
data class SourceAsset(
    val id: String,
    val fileName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val hasAudio: Boolean = true,
    val sha256: String,
)

@Serializable
data class TimelineClip(
    val id: String,
    val sourceId: String,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val volume: Float = 1f,
) {
    val durationUs: Long get() = Math.subtractExact(sourceEndUs, sourceStartUs)
}

@Serializable
data class EditSnapshot(
    val clips: List<TimelineClip>,
    val canvas: CanvasShape,
    /** Number of durable captures already represented (or explicitly deleted) by this edit. */
    val captureCursor: Int,
)

@Serializable
data class CaptureEntry(val clip: TimelineClip)

@Serializable
data class Project(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val sources: List<SourceAsset>,
    val history: List<EditSnapshot>,
    val historyCursor: Int,
    val captureLedger: List<CaptureEntry>,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revisionId: String = UUID.randomUUID().toString(),
    val ancestorRevisionIds: List<String> = emptyList(),
) {
    val clips: List<TimelineClip>
        get() = materialize(history[historyCursor])
    val canvas: CanvasShape get() = history[historyCursor].canvas
    val durationUs: Long get() = clips.fold(0L) { duration, clip -> Math.addExact(duration, clip.durationUs) }
    val canUndo: Boolean get() = historyCursor > 0
    val canRedo: Boolean get() = historyCursor < history.lastIndex

    internal fun materialize(snapshot: EditSnapshot): List<TimelineClip> =
        snapshot.clips + captureLedger.drop(snapshot.captureCursor).map(CaptureEntry::clip)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun create(title: String, canvas: CanvasShape = CanvasShape.PORTRAIT): Project {
            val now = System.currentTimeMillis()
            return Project(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                createdAtMs = now,
                updatedAtMs = now,
                sources = emptyList(),
                history = listOf(EditSnapshot(emptyList(), canvas, 0)),
                historyCursor = 0,
                captureLedger = emptyList(),
            ).also(ProjectEditor::validate)
        }
    }
}
