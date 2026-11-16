package it.panebarco.creator.media

import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.SourceAsset
import java.io.File

/** Immutable, microsecond edit mapping consumed by both preview and export. */
internal data class MediaTimeline(
    val width: Int,
    val height: Int,
    val clips: List<MediaClip>,
) {
    val durationUs: Long get() = clips.lastOrNull()?.timelineEndUs ?: 0L

    companion object {
        fun from(project: Project, resolve: (String) -> File): MediaTimeline {
            ProjectEditor.validate(project)
            require(project.clips.isNotEmpty()) { "Il progetto non contiene clip da riprodurre." }
            val sources = project.sources.associateBy(SourceAsset::id)
            var positionUs = 0L
            val clips = project.clips.map { clip ->
                val source = requireNotNull(sources[clip.sourceId]) { "Sorgente della clip non trovata." }
                val file = resolve(source.id)
                require(file.isFile && file.length() > 0L) { "Una sorgente video non è disponibile. Ripristina il backup del progetto." }
                val endUs = Math.addExact(positionUs, clip.durationUs)
                MediaClip(
                    clip.id, source, file, clip.sourceStartUs, clip.sourceEndUs,
                    positionUs, endUs, clip.volume,
                ).also { positionUs = endUs }
            }
            val portrait = project.canvas == CanvasShape.PORTRAIT
            return MediaTimeline(if (portrait) 1080 else 1920, if (portrait) 1920 else 1080, clips)
        }
    }
}

internal data class MediaClip(
    val id: String,
    val source: SourceAsset,
    val file: File,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
    val volume: Float,
) {
    val durationUs: Long get() = sourceEndUs - sourceStartUs
}
