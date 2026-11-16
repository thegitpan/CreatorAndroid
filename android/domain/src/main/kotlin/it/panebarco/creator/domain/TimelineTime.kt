package it.panebarco.creator.domain

import java.math.BigInteger

data class TimelinePosition(val clipId: String, val clipIndex: Int, val sourceTimeUs: Long, val timelineStartUs: Long)

/** Boundary conversions use cumulative integer timestamps; rounded frame durations are never accumulated. */
object TimelineTime {
    fun locate(project: Project, timelineTimeUs: Long): TimelinePosition? {
        ProjectEditor.validate(project)
        require(timelineTimeUs in 0..project.durationUs) { "Posizione fuori dalla timeline." }
        var start = 0L
        project.clips.forEachIndexed { index, clip ->
            val end = Math.addExact(start, clip.durationUs)
            if (timelineTimeUs < end || (index == project.clips.lastIndex && timelineTimeUs == end)) {
                return TimelinePosition(clip.id, index, clip.sourceStartUs + (timelineTimeUs - start), start)
            }
            start = end
        }
        return null
    }

    fun toTimelineUs(project: Project, clipId: String, sourceTimeUs: Long): Long {
        ProjectEditor.validate(project)
        var start = 0L
        project.clips.forEach { clip ->
            if (clip.id == clipId) {
                require(sourceTimeUs in clip.sourceStartUs..clip.sourceEndUs) { "Posizione fuori dalla clip." }
                return Math.addExact(start, sourceTimeUs - clip.sourceStartUs)
            }
            start = Math.addExact(start, clip.durationUs)
        }
        throw IllegalArgumentException("Clip non trovata nel progetto.")
    }

    /** Floor of a rational frame boundary, including rates such as 30000/1001. */
    fun frameBoundaryUs(frameIndex: Long, framesPerSecondNumerator: Long, framesPerSecondDenominator: Long = 1): Long {
        require(frameIndex >= 0 && framesPerSecondNumerator > 0 && framesPerSecondDenominator > 0) {
            "Indice o frequenza dei fotogrammi non validi."
        }
        return BigInteger.valueOf(frameIndex).multiply(BigInteger.valueOf(1_000_000))
            .multiply(BigInteger.valueOf(framesPerSecondDenominator))
            .divide(BigInteger.valueOf(framesPerSecondNumerator)).longValueExact()
    }

    fun millisecondsToUs(milliseconds: Long): Long {
        require(milliseconds >= 0) { "Timestamp negativo." }
        return Math.multiplyExact(milliseconds, 1_000L)
    }

    fun microsecondsToMillisecondsFloor(microseconds: Long): Long {
        require(microseconds >= 0) { "Timestamp negativo." }
        return microseconds / 1_000L
    }
}
