package it.panebarco.creator.domain

import java.util.UUID

/** Pure, non-destructive operations. Source coordinates and durations are integer microseconds. */
object ProjectEditor {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    private val fileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

    fun appendRecording(project: Project, source: SourceAsset): Project {
        validate(project)
        validateNewSource(project, source)
        return changed(project).copy(
            sources = project.sources + source,
            captureLedger = project.captureLedger + CaptureEntry(fullClip(source)),
            updatedAtMs = nextTimestamp(project),
        ).also(::validate)
    }

    fun appendImport(project: Project, source: SourceAsset): Project {
        validate(project)
        validateNewSource(project, source)
        return edit(project.copy(sources = project.sources + source), project.clips + fullClip(source))
    }

    fun split(project: Project, clipId: String, sourceTimeUs: Long): Project {
        validate(project)
        val index = indexOf(project, clipId)
        val clip = project.clips[index]
        require(sourceTimeUs > clip.sourceStartUs && sourceTimeUs < clip.sourceEndUs) {
            "Il taglio deve essere all'interno della clip."
        }
        val clips = project.clips.toMutableList()
        clips[index] = clip.copy(sourceEndUs = sourceTimeUs)
        clips.add(index + 1, clip.copy(id = UUID.randomUUID().toString(), sourceStartUs = sourceTimeUs))
        return edit(project, clips)
    }

    /** Trimming may reveal previously hidden original media, within the immutable source bounds. */
    fun trim(project: Project, clipId: String, startUs: Long, endUs: Long): Project =
        changeClip(project, clipId) { clip -> clip.copy(sourceStartUs = startUs, sourceEndUs = endUs) }

    fun delete(project: Project, clipId: String): Project {
        validate(project)
        val clips = project.clips.toMutableList()
        clips.removeAt(indexOf(project, clipId))
        return edit(project, clips)
    }

    /** targetIndex is the final zero-based index, after the move. */
    fun move(project: Project, clipId: String, targetIndex: Int): Project {
        validate(project)
        val sourceIndex = indexOf(project, clipId)
        require(targetIndex in project.clips.indices) { "Posizione della clip non valida." }
        if (sourceIndex == targetIndex) return project
        val clips = project.clips.toMutableList()
        clips.add(targetIndex, clips.removeAt(sourceIndex))
        return edit(project, clips)
    }

    fun setVolume(project: Project, clipId: String, volume: Float): Project =
        changeClip(project, clipId) { it.copy(volume = volume) }

    fun setCanvas(project: Project, canvas: CanvasShape): Project {
        validate(project)
        if (project.canvas == canvas) return project
        return edit(project, project.clips, canvas)
    }

    /** Library metadata is saved independently of timeline Undo/Redo. */
    fun rename(project: Project, title: String): Project {
        validate(project)
        val normalized = title.trim()
        if (normalized == project.title) return project
        return changed(project).copy(title = normalized).also(::validate)
    }

    fun undo(project: Project): Project {
        validate(project)
        return if (!project.canUndo) project else changed(project).copy(
            historyCursor = project.historyCursor - 1,
        ).also(::validate)
    }

    fun redo(project: Project): Project {
        validate(project)
        return if (!project.canRedo) project else changed(project).copy(
            historyCursor = project.historyCursor + 1,
        ).also(::validate)
    }

    fun validate(project: Project) {
        require(project.schemaVersion == Project.CURRENT_SCHEMA_VERSION) {
            "Versione del progetto non supportata (${project.schemaVersion}). Aggiorna Creator."
        }
        require(uuid.matches(project.id)) { "Identificativo del progetto non valido." }
        require(uuid.matches(project.revisionId) && project.ancestorRevisionIds.size <= 100_000 &&
            project.ancestorRevisionIds.all(uuid::matches) && project.revisionId !in project.ancestorRevisionIds &&
            project.ancestorRevisionIds.toSet().size == project.ancestorRevisionIds.size) {
            "Identificativi delle revisioni non validi."
        }
        require(project.title.isNotBlank() && project.title.length <= 200 && project.title.none { it.isISOControl() }) {
            "Il nome del progetto deve contenere da 1 a 200 caratteri leggibili."
        }
        require(project.createdAtMs >= 0 && project.updatedAtMs >= project.createdAtMs) {
            "Date del progetto non valide."
        }
        require(project.history.isNotEmpty() && project.historyCursor in project.history.indices) {
            "Cronologia del progetto non valida."
        }
        val sources = project.sources.associateBy { it.id }
        require(sources.size == project.sources.size) { "Sorgenti duplicate nel progetto." }
        require(project.sources.map { it.fileName }.toSet().size == project.sources.size) {
            "Nomi dei file sorgente duplicati."
        }
        project.sources.forEach(::validateSource)
        val captureIds = project.captureLedger.map { it.clip.id }
        require(captureIds.toSet().size == captureIds.size) { "Acquisizioni duplicate nel registro." }
        val clipSources = mutableMapOf<String, String>()
        fun checkClip(clip: TimelineClip) {
            validateClip(clip, sources)
            val previous = clipSources.putIfAbsent(clip.id, clip.sourceId)
            require(previous == null || previous == clip.sourceId) { "L'identità di una clip è cambiata nella cronologia." }
        }
        project.captureLedger.forEach {
            checkClip(it.clip)
            require(it.clip.sourceStartUs == 0L && it.clip.sourceEndUs == sources.getValue(it.clip.sourceId).durationUs &&
                it.clip.volume == 1f) { "La registrazione originale nel registro non può essere tagliata o modificata." }
        }
        require(project.captureLedger.map { it.clip.sourceId }.toSet().size == project.captureLedger.size) {
            "La stessa sorgente è registrata più volte nel registro acquisizioni."
        }
        require(project.history.first().captureCursor == 0) { "La cronologia iniziale non può nascondere acquisizioni." }
        var previousCaptureCursor = 0
        project.history.forEach { snapshot ->
            require(snapshot.captureCursor in previousCaptureCursor..project.captureLedger.size) {
                "Posizione del registro acquisizioni non valida."
            }
            previousCaptureCursor = snapshot.captureCursor
            snapshot.clips.forEach(::checkClip)
            val clips = project.materialize(snapshot)
            require(clips.map { it.id }.toSet().size == clips.size) { "Clip duplicate nella cronologia." }
            try {
                clips.fold(0L) { total, clip -> Math.addExact(total, clip.durationUs) }
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Durata del progetto troppo grande.", error)
            }
        }
    }

    /** Also usable before a staged capture has a repository-controlled id, name or checksum. */
    fun validateMediaMetadata(source: SourceAsset) {
        require(source.durationUs > 0) { "Il video deve avere una durata positiva." }
        require(source.width in 1..32768 && source.height in 1..32768) { "Dimensioni del video non valide." }
        require(source.rotationDegrees in setOf(0, 90, 180, 270)) { "Rotazione del video non supportata." }
    }

    private fun validateSource(source: SourceAsset) {
        validateMediaMetadata(source)
        require(uuid.matches(source.id)) { "Identificativo della sorgente non valido." }
        require(fileName.matches(source.fileName) && !source.fileName.contains("..")) {
            "Nome del file sorgente non sicuro."
        }
        require(digest.matches(source.sha256)) { "Checksum della sorgente non valido." }
    }

    private fun validateClip(clip: TimelineClip, sources: Map<String, SourceAsset>) {
        require(uuid.matches(clip.id)) { "Identificativo della clip non valido." }
        val source = requireNotNull(sources[clip.sourceId]) { "Sorgente della clip mancante." }
        require(clip.sourceStartUs >= 0 && clip.sourceEndUs > clip.sourceStartUs && clip.sourceEndUs <= source.durationUs) {
            "Intervallo della clip fuori dalla sorgente."
        }
        require(clip.volume.isFinite() && clip.volume in 0f..4f) { "Il volume deve essere compreso tra 0 e 4." }
    }

    private fun validateNewSource(project: Project, source: SourceAsset) {
        validateSource(source)
        require(project.sources.none { it.id == source.id || it.fileName == source.fileName }) {
            "La sorgente è già presente nel progetto."
        }
    }

    private fun fullClip(source: SourceAsset) = TimelineClip(
        id = UUID.randomUUID().toString(), sourceId = source.id, sourceStartUs = 0, sourceEndUs = source.durationUs,
    )

    private fun changeClip(project: Project, clipId: String, change: (TimelineClip) -> TimelineClip): Project {
        validate(project)
        val index = indexOf(project, clipId)
        val clips = project.clips.toMutableList()
        val replacement = change(clips[index])
        if (replacement == clips[index]) return project
        clips[index] = replacement
        return edit(project, clips)
    }

    private fun indexOf(project: Project, clipId: String): Int = project.clips.indexOfFirst { it.id == clipId }.also {
        require(it >= 0) { "Clip non trovata nel progetto." }
    }

    private fun edit(project: Project, clips: List<TimelineClip>, canvas: CanvasShape = project.canvas): Project {
        val history = project.history.take(project.historyCursor + 1) + EditSnapshot(
            clips.toList(), canvas, project.captureLedger.size,
        )
        return changed(project).copy(history = history, historyCursor = history.lastIndex)
            .also(::validate)
    }

    private fun changed(project: Project): Project = project.copy(
        revisionId = UUID.randomUUID().toString(),
        ancestorRevisionIds = project.ancestorRevisionIds + project.revisionId,
        updatedAtMs = nextTimestamp(project),
    )

    private fun nextTimestamp(project: Project): Long = maxOf(System.currentTimeMillis(), Math.addExact(project.updatedAtMs, 1L))
}
