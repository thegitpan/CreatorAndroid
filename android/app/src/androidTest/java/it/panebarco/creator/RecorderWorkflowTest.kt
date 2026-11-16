package it.panebarco.creator

import android.os.SystemClock
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import it.panebarco.creator.media.MediaProbe
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecorderWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
    @Test fun recordsTwoFinalizedSegmentsThenReopensAndContinuesWithThird() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CreatorApplication
        val repository = app.repository
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var projectId: String? = null
        try {
            idle()
            click("new_recording")
            val title = "QA Recorder ${UUID.randomUUID().toString().take(8)}"
            compose.onNodeWithTag("project_name").performTextInput(title)
            click("confirm_name")
            compose.waitUntil(10_000) { repository.listProjects().any { it.title == title } }
            val id = repository.listProjects().first { it.title == title }.id
            projectId = id
            for (count in 1..2) recordSegment { repository.load(id).clips.size == count }
            val before = repository.load(id)
            assertEquals(2, before.captureLedger.size)
            assertFalse(before.canUndo)
            val hashes = before.sources.associate { it.id to sha(repository.sourceFile(id, it.id).readBytes()) }
            leaveRecorder()
            click("back")
            click("record_$id")
            recordSegment { repository.load(id).clips.size == 3 }
            leaveRecorder()
            val after = repository.load(id)
            assertEquals(3, after.captureLedger.size)
            after.sources.forEach { source ->
                val file = repository.sourceFile(id, source.id)
                assertTrue(MediaProbe.inspect(file).durationUs > 500_000L)
                requireNotNull(MediaProbe.thumbnail(file)).recycle()
                if (source.id in hashes) assertEquals(hashes[source.id], sha(file.readBytes()))
            }
            val recordedHashes = after.sources.associate { it.id to sha(repository.sourceFile(id, it.id).readBytes()) }
            val captureReport = JSONArray()
            after.sources.forEachIndexed { index, source ->
                val tracks = trackReport(repository.sourceFile(id, source.id))
                assertTrue("The microphone-enabled CameraX take must contain audio", (0 until tracks.length()).any { tracks.getJSONObject(it).getString("mime").startsWith("audio/") })
                captureReport.put(JSONObject().put("take", index + 1).put("durationUs", source.durationUs)
                    .put("width", source.width).put("height", source.height).put("rotationDegrees", source.rotationDegrees)
                    .put("tracks", tracks))
            }
            // Exercise the real user-started foreground service with CameraX files, not imported fixtures.
            click("export")
            compose.waitUntil(90_000) { ExportJobs.state.value.projectId == id && ExportJobs.state.value.phase in listOf(ExportPhase.COMPLETE, ExportPhase.FAILED) }
            val result = ExportJobs.state.value
            assertEquals(result.error, ExportPhase.COMPLETE, result.phase)
            assertEquals(after.revisionId, result.revisionId)
            val exported = requireNotNull(result.file)
            val output = MediaProbe.inspect(exported)
            val outputTracks = trackReport(exported)
            val displayWidth = if (output.rotationDegrees % 180 == 0) output.width else output.height
            val displayHeight = if (output.rotationDegrees % 180 == 0) output.height else output.width
            val report = JSONObject().put("evidence", "emulator_camera_pipeline_only")
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("cameraXVersion", it.panebarco.creator.media.BuildConfig.CAMERA_X_VERSION)
                .put("media3Version", it.panebarco.creator.media.BuildConfig.MEDIA3_VERSION)
                .put("captures", captureReport).put("expectedDurationUs", after.durationUs)
                .put("outputDurationUs", output.durationUs).put("durationErrorUs", output.durationUs - after.durationUs)
                .put("outputWidth", output.width).put("outputHeight", output.height).put("outputRotationDegrees", output.rotationDegrees)
                .put("displayOutputWidth", displayWidth).put("displayOutputHeight", displayHeight)
                .put("outputTracks", outputTracks).put("outputBytes", exported.length())
            Log.i("CREATOR_CAMERA_EXPORT_QA", report.toString())
            assertTrue("Recorded timeline duration must survive export: $report", abs(output.durationUs - after.durationUs) <= 100_000L)
            assertEquals(1080, displayWidth)
            assertEquals(1920, displayHeight)
            assertTrue(output.hasAudio)
            assertEquals(2, outputTracks.length())
            val video = (0 until outputTracks.length()).map(outputTracks::getJSONObject).single { it.getString("mime").startsWith("video/") }
            val audio = (0 until outputTracks.length()).map(outputTracks::getJSONObject).single { it.getString("mime").startsWith("audio/") }
            assertEquals("video/avc", video.getString("mime"))
            assertEquals("audio/mp4a-latm", audio.getString("mime"))
            assertEquals(48_000, audio.getInt("sampleRateHz"))
            assertTrue(audio.getInt("channelCount") > 0)
            val decodedAudio = kotlinx.coroutines.runBlocking { MediaProbe.waveform(exported, bins = 48) }
            assertEquals(48, decodedAudio.size)
            assertTrue(decodedAudio.all { it.isFinite() })
            var timelineStartUs = 0L
            after.clips.forEach { clip ->
                val midpointUs = timelineStartUs + clip.durationUs / 2
                requireNotNull(MediaProbe.thumbnail(exported, midpointUs)) { "Exported segment at $midpointUs is not decodable" }.recycle()
                timelineStartUs += clip.durationUs
            }
            assertEquals(after.clips, repository.load(id).clips)
            after.sources.forEach { source -> assertEquals(recordedHashes[source.id], sha(repository.sourceFile(id, source.id).readBytes())) }
        } finally { scenario.close(); projectId?.let { repository.moveToTrash(it) } }
    }
    private fun recordSegment(saved: () -> Boolean) {
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("record_toggle").assertIsEnabled(); true }.getOrDefault(false) }
        click("record_toggle")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("REC", substring = true).fetchSemanticsNodes().isNotEmpty() }
        SystemClock.sleep(1_600)
        click("record_toggle")
        compose.waitUntil(20_000) { runCatching(saved).getOrDefault(false) }
        idle()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("record_status").fetchSemanticsNodes().singleOrNull()
                ?.config?.get(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.startsWith("Pronto") } == true
        }
    }
    private fun leaveRecorder() {
        click("leave_recorder")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("back").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    private fun idle() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("operation_progress").fetchSemanticsNodes().isEmpty() }; compose.waitForIdle() }
    private fun click(tag: String) { compose.clickVisibleControl(tag, InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density); idle() }
    private fun trackReport(file: File): JSONArray {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            JSONArray().apply {
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val track = JSONObject().put("mime", format.getString(MediaFormat.KEY_MIME))
                    for ((key, name) in listOf(MediaFormat.KEY_SAMPLE_RATE to "sampleRateHz", MediaFormat.KEY_CHANNEL_COUNT to "channelCount", MediaFormat.KEY_BIT_RATE to "bitRate", MediaFormat.KEY_WIDTH to "width", MediaFormat.KEY_HEIGHT to "height")) {
                        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull()?.let { track.put(name, it) }
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) track.put("durationUs", format.getLong(MediaFormat.KEY_DURATION))
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    track.put("firstSampleTimeUs", extractor.sampleTime)
                    extractor.unselectTrack(index)
                    put(track)
                }
            }
        } finally { extractor.release() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
