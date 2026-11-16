@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package it.panebarco.creator.media

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.domain.ProjectEditor
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated H.264/AAC fixtures exercise actual extractors, GL processing, decoders and encoders. */
@RunWith(AndroidJUnit4::class)
class MediaEngineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String, directory: File): File = File(directory, name).also { output ->
        instrumentation.context.assets.open(name).use { input -> output.outputStream().use(input::copyTo) }
    }

    private fun project(directory: File, canvas: CanvasShape = CanvasShape.PORTRAIT): Pair<Project, Map<String, File>> {
        val landscape = fixture("landscape-tone.mp4", directory)
        val silent = fixture("portrait-silent.mp4", directory)
        val rotated = fixture("rotated-tone.mp4", directory)
        val sources = listOf(landscape, silent, rotated).map { MediaProbe.inspect(it) }
        assertFalse(sources[1].hasAudio)
        // ffprobe reports display-matrix rotation counterclockwise; Android reports clockwise.
        assertEquals(270, sources[2].rotationDegrees)
        var project = Project.create("Fixture Media3", canvas)
        sources.forEach { project = ProjectEditor.appendImport(project, it) }
        val clips = project.clips
        project = ProjectEditor.trim(project, clips[0].id, 333_333L, 2_666_667L)
        project = ProjectEditor.setVolume(project, clips[0].id, .25f)
        project = ProjectEditor.trim(project, clips[1].id, 100_000L, 1_800_000L)
        project = ProjectEditor.trim(project, clips[2].id, 233_333L, 1_333_333L)
        project = ProjectEditor.setVolume(project, clips[2].id, 0f)
        return project to sources.mapIndexed { index, source -> source.id to listOf(landscape, silent, rotated)[index] }.toMap()
    }

    @Test fun previewAndExportMatchNonKeyframeTrimFitRotationGainAndGeneratedSilence() = runBlocking {
        val directory = File(context.filesDir, "media-qa/${UUID.randomUUID()}").apply { mkdirs() }
        val (project, files) = project(directory)
        val originalHashes = files.mapValues { MediaProbe.inspect(it.value).sha256 }
        val engine = ProjectMediaEngine(context)
        val mapping = MediaTimeline.from(project, files::getValue)
        val composition = engine.composition(mapping)
        val items = composition.sequences.single().editedMediaItems
        assertEquals(project.clips.map { it.sourceStartUs }, items.map { it.mediaItem.clippingConfiguration.startPositionUs })
        assertEquals(project.clips.map { it.sourceEndUs }, items.map { it.mediaItem.clippingConfiguration.endPositionUs })
        assertEquals(project.clips.map { clip -> project.sources.single { it.id == clip.sourceId }.durationUs }, items.map { it.durationUs })
        assertTrue(composition.sequences.single().trackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO))
        assertTrue(items[1].removeAudio)

        val ready = CompletableDeferred<Unit>()
        val reader = ImageReader.newInstance(540, 960, PixelFormat.RGBA_8888, 3)
        var preview: Player? = null
        try {
            withContext(Dispatchers.Main) {
                reader.setOnImageAvailableListener({ imageReader -> imageReader.acquireLatestImage()?.close() }, android.os.Handler(android.os.Looper.getMainLooper()))
                preview = engine.createPreview(project, files::getValue).also { player ->
                    (player as androidx.media3.transformer.CompositionPlayer).setVideoSurface(reader.surface, androidx.media3.common.util.Size(540, 960))
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY && !ready.isCompleted) ready.complete(Unit)
                        }
                        override fun onPlayerError(error: PlaybackException) { ready.completeExceptionally(error) }
                    })
                }
            }
            withTimeout(30_000L) { ready.await() }
            withContext(Dispatchers.Main) {
                val player = requireNotNull(preview)
                assertTrue(abs(player.duration * 1_000L - project.durationUs) < 2_000L)
                player.seekTo(2_500L) // Inside the generated-silence clip.
                assertTrue(abs(player.currentPosition - 2_500L) < 2L)
                player.pause()
            }
        } finally {
            withContext(Dispatchers.Main) { preview?.release(); reader.close() }
        }

        val output = File(directory, "portrait-export.mp4")
        val progress = mutableListOf<Int>()
        withTimeout(120_000L) { engine.export(project, files::getValue, output) { progress += it } }
        val exported = MediaProbe.inspect(output)
        MediaProbe.verifyExportTracks(output)
        val displayWidth = if (exported.rotationDegrees % 180 == 0) exported.width else exported.height
        val displayHeight = if (exported.rotationDegrees % 180 == 0) exported.height else exported.width
        assertEquals(1080, displayWidth)
        assertEquals(1920, displayHeight)
        assertTrue("Expected ${project.durationUs}, got ${exported.durationUs}", abs(exported.durationUs - project.durationUs) <= 250_000L)
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { it.first <= it.second })
        val sourcePeaks = MediaProbe.waveform(files.getValue(project.clips.first().sourceId), 24, 500_000L, 2_000_000L)
        val outputPeaks = MediaProbe.waveform(output, 24, 200_000L, 1_800_000L)
        val gainRatio = outputPeaks.average() / sourcePeaks.average()
        assertTrue("Gain ratio $gainRatio", gainRatio in .15.. .4)
        val silenceStartUs = project.clips.first().durationUs + 200_000L
        val silenceEndUs = project.clips.take(2).sumOf { it.durationUs } - 200_000L
        assertTrue(MediaProbe.waveform(output, 24, silenceStartUs, silenceEndUs).max() < .01f)
        assertTrue(MediaProbe.waveform(output, 24, project.durationUs - 800_000L, project.durationUs - 200_000L).max() < .01f)
        val thumbnail = requireNotNull(MediaProbe.thumbnail(output, 1_000_000L, 160, 160))
        assertEquals(90, thumbnail.width)
        assertEquals(160, thumbnail.height)
        thumbnail.recycle()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val frame = requireNotNull(retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST))
            // A landscape source fitted inside a portrait canvas has black top/bottom margins.
            assertTrue(rowBrightness(frame, .1f) < 8.0)
            assertTrue(rowBrightness(frame, .5f) > 15.0)
            frame.recycle()
            val rotatedFrame = requireNotNull(retriever.getFrameAtTime(project.durationUs - 500_000L, MediaMetadataRetriever.OPTION_CLOSEST))
            assertTrue("Rotated 16:9 fixture must fill the portrait canvas", rowBrightness(rotatedFrame, .1f) > 15.0)
            rotatedFrame.recycle()
        } finally { retriever.release() }
        assertEquals(originalHashes, files.mapValues { MediaProbe.inspect(it.value).sha256 })
        File(context.filesDir, "media-qa/latest.json").writeText(JSONObject()
            .put("directory", directory.name)
            .put("expectedDurationUs", project.durationUs)
            .put("actualDurationUs", exported.durationUs)
            .put("gainRatio", gainRatio)
            .put("width", displayWidth).put("height", displayHeight)
            .put("physicalCameraAcceptance", "pending").toString(2))
    }

    @Test fun cancellationAndTwoEngineDestinationCollisionPreserveSourcesAndExistingFiles() = runBlocking {
        val directory = File(context.filesDir, "media-qa/${UUID.randomUUID()}").apply { mkdirs() }
        val (base, files) = project(directory, CanvasShape.LANDSCAPE)
        val output = File(directory, "already-exists.mp4").apply { createNewFile() }
        for (engine in listOf(ProjectMediaEngine(context), ProjectMediaEngine(context))) {
            val error = runCatching { engine.export(base, files::getValue, output) {} }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(output.exists())
            assertEquals(0L, output.length())
        }
        output.writeText("foreign bytes")
        assertTrue(runCatching { ProjectMediaEngine(context).export(base, files::getValue, output) {} }.isFailure)
        assertEquals("foreign bytes", output.readText())
        var longProject = base
        repeat(12) { index ->
            val extra = base.sources.first().copy(id = UUID.randomUUID().toString(), fileName = "repeated-$index.mp4")
            longProject = ProjectEditor.appendImport(longProject, extra)
        }
        val cancelled = File(directory, "cancelled.mp4")
        val hashes = files.mapValues { MediaProbe.inspect(it.value).sha256 }
        val resolveLong: (String) -> File = { id -> files[id] ?: files.getValue(base.sources.first().id) }
        val job = launch { ProjectMediaEngine(context).export(longProject, resolveLong, cancelled) {} }
        delay(600L)
        job.cancelAndJoin()
        assertFalse(cancelled.exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".render.mp4") })
        assertEquals(hashes, files.mapValues { MediaProbe.inspect(it.value).sha256 })
    }

    @Test fun allSilentAndSilenceFirstSequencesKeepAudioAndExactTimelineDuration() = runBlocking {
        val directory = File(context.filesDir, "media-qa/${UUID.randomUUID()}").apply { mkdirs() }
        val silent = fixture("portrait-silent.mp4", directory)
        val tone = fixture("landscape-tone.mp4", directory)
        val silentMetadata = MediaProbe.inspect(silent)
        val toneMetadata = MediaProbe.inspect(tone)
        val files = mapOf(silentMetadata.id to silent, toneMetadata.id to tone)
        var project = ProjectEditor.appendImport(Project.create("Solo silenzio", CanvasShape.LANDSCAPE), silentMetadata)
        project = ProjectEditor.trim(project, project.clips.single().id, 83_333L, 1_250_000L)
        val engine = ProjectMediaEngine(context)
        val silentOutput = File(directory, "all-silent.mp4")
        withTimeout(90_000L) { engine.export(project, files::getValue, silentOutput) {} }
        MediaProbe.verifyExportTracks(silentOutput)
        assertTrue(MediaProbe.inspect(silentOutput).hasAudio)
        assertTrue(MediaProbe.waveform(silentOutput, 24).max() < .01f)
        assertTrue(abs(MediaProbe.inspect(silentOutput).durationUs - project.durationUs) < 150_000L)
        project = ProjectEditor.appendImport(project, toneMetadata)
        project = ProjectEditor.trim(project, project.clips.last().id, 333_333L, 1_333_333L)
        val silenceFirst = File(directory, "silence-first.mp4")
        withTimeout(90_000L) { engine.export(project, files::getValue, silenceFirst) {} }
        assertTrue(MediaProbe.waveform(silenceFirst, 24, 100_000L, 900_000L).max() < .01f)
        assertTrue(MediaProbe.waveform(silenceFirst, 24, project.durationUs - 700_000L, project.durationUs - 100_000L).average() > .02)
        assertTrue(abs(MediaProbe.inspect(silenceFirst).durationUs - project.durationUs) < 200_000L)
    }

    @Test fun androidExclusivePublicationHasOneWinnerAndCleansOnlyItsOwnInode() {
        val directory = File(context.filesDir, "media-qa/${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(directory, "exclusive.mp4")
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = listOf("one", "two").map { text -> executor.submit<Boolean> {
                val partial = File(directory, "$text.partial").apply { writeText(text) }
                start.await(5, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { ExportFiles.publishNoReplace(partial, destination) }.isSuccess
            } }
            start.countDown()
            val succeeded = results.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, succeeded.count { it })
            assertEquals(if (succeeded[0]) "one" else "two", destination.readText())
            val partial = File(directory, "copy.partial").apply { writeBytes(ByteArray(400_000) { 7 }) }
            val cancelOutput = File(directory, "cancel-copy.mp4")
            var checks = 0
            assertTrue(runCatching {
                ExportFiles.publishNoReplace(partial, cancelOutput, checkCancellation = {
                    if (++checks > 1) throw java.util.concurrent.CancellationException()
                })
            }.exceptionOrNull() is java.util.concurrent.CancellationException)
            assertFalse(cancelOutput.exists())
            val published = File(directory, "owned.mp4")
            val publication = ExportFiles.publishNoReplace(partial, published)
            assertTrue(published.delete())
            published.writeText("foreign")
            assertFalse(publication.deleteIfOwned())
            assertEquals("foreign", published.readText())
        } finally { executor.shutdownNow() }
    }

    @Test fun diagnosticsContainCapabilitiesWithoutMediaPathsOrUniqueDeviceIdentifiers() {
        val report = DeviceDiagnostics.collect(context)
        val json = JSONObject(report)
        assertEquals("pending_physical_preview_and_saved_file", json.getString("cameraAcceptance"))
        assertTrue(json.has("cameras"))
        assertTrue(json.has("codecs"))
        assertFalse(report.contains(context.filesDir.absolutePath))
        assertFalse(json.has("serial"))
        assertFalse(json.has("androidId"))
    }

    private fun rowBrightness(bitmap: android.graphics.Bitmap, fraction: Float): Double {
        val y = (bitmap.height * fraction).toInt().coerceIn(0, bitmap.height - 1)
        return (0 until bitmap.width step maxOf(1, bitmap.width / 100)).map { x ->
            val color = bitmap.getPixel(x, y)
            (Color.red(color) + Color.green(color) + Color.blue(color)) / 3.0
        }.average()
    }
}
