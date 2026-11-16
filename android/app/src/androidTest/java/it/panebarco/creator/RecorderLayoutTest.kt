package it.panebarco.creator

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import android.util.Log
import it.panebarco.creator.media.MediaProbe
import kotlin.math.abs

/** Reproduces the physical Samsung viewport; emulator frames establish layout only. */
@RunWith(AndroidJUnit4::class)
class RecorderLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CreatorApplication

    @Test fun frontSelectionAndASingleActiveTakeSurviveRotationAndCloselySpacedPauseTaps() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var projectId: String? = null
            try {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                idle()
                click("new_recording")
                val title = "QA Recorder rotation ${UUID.randomUUID().toString().take(8)}"
                compose.onNodeWithTag("project_name").performTextInput(title)
                click("confirm_name")
                compose.waitUntil(10_000) { app.repository.listProjects().any { it.title == title } }
                val id = app.repository.listProjects().first { it.title == title }.id
                projectId = id
                waitForCamera(scenario)
                click("switch_camera")
                waitForCamera(scenario)
                compose.onNodeWithTag("switch_camera").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fotocamera frontale"))
                shell("screencap -p /sdcard/Download/creator-front-before-rotation.png")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                waitForCamera(scenario)
                compose.onNodeWithTag("switch_camera").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fotocamera frontale"))
                shell("screencap -p /sdcard/Download/creator-front-after-rotation.png")

                click("record_toggle")
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Pausa e salva").fetchSemanticsNodes().size == 1 }
                waitForRecordedFrames()
                shell("screencap -p /sdcard/Download/creator-front-recording-before-rotation.png")
                // Recreate the real activity during REC: this must finalize once, never restart.
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(20_000) { app.repository.load(id).clips.size == 1 }
                waitForCamera(scenario)
                compose.waitUntil(10_000) { compose.onNodeWithTag("record_status").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.startsWith("Pronto") } }
                compose.onNodeWithTag("camera_error").assertDoesNotExist()
                val firstTake = app.repository.load(id)
                assertEquals(1, firstTake.captureLedger.size)
                assertEquals(1, firstTake.sources.size)
                assertNull(MediaWorkCoordinator.recording.value)
                val firstSource = firstTake.sources.single()
                val firstFile = app.repository.sourceFile(id, firstSource.id)
                val firstHash = hash(firstFile)
                assertTrue(firstSource.durationUs >= 500_000)
                requireNotNull(MediaProbe.thumbnail(firstFile)).recycle()
                compose.onNodeWithTag("switch_camera").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Fotocamera frontale"))
                shell("screencap -p /sdcard/Download/creator-front-stopped-after-rotation.png")
                assertTrue(app.repository.pendingMediaFiles(id).isEmpty())

                click("record_toggle")
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Pausa e salva").fetchSemanticsNodes().size == 1 }
                waitForRecordedFrames()
                // Keep the rendered button enabled across both real input events. The command
                // boundary must read live state even before the next Compose frame disables it.
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("record_toggle").performTouchInput { click(); advanceEventTime(1); click() }
                } finally { compose.mainClock.autoAdvance = true }
                compose.waitUntil(20_000) { app.repository.load(id).clips.size == 2 }
                compose.waitUntil(10_000) { compose.onNodeWithTag("record_status").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.startsWith("Pronto") } }
                compose.onNodeWithTag("camera_error").assertDoesNotExist()
                compose.onNodeWithText("Operazione non completata").assertDoesNotExist()
                val final = app.repository.load(id)
                assertEquals(2, final.captureLedger.size)
                assertEquals(listOf(firstSource.id, final.sources.last().id), final.captureLedger.map { it.clip.sourceId })
                assertEquals(firstHash, hash(app.repository.sourceFile(id, firstSource.id)))
                assertNull(MediaWorkCoordinator.recording.value)
                assertTrue("A repeated pause cannot stage an extra take", File(app.filesDir, "Projects/projects/$id/pending").listFiles().orEmpty().none { it.extension == "mp4" })
                Log.i("CREATOR_ROTATION_QA", JSONObject().put("evidence", "emulator_layout_and_lifecycle_only")
                    .put("frontSelectionRetained", true).put("segmentCount", final.captureLedger.size)
                    .put("firstSourceDurationUs", firstSource.durationUs).put("firstSourceSha256BeforeAndAfter", firstHash)
                    .put("sourceOrderPreserved", true).put("recordingReservationReleased", true).toString())
            } finally {
                compose.mainClock.autoAdvance = true
                projectId?.let { app.repository.moveToTrash(it) }
            }
        }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun waitForRecordedFrames() {
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("record_status").fetchSemanticsNode().config[SemanticsProperties.Text]
                .any { it.text.startsWith("REC ") && !it.text.endsWith("0:00") }
        }
    }

    // Requested orientation stresses window layout/lifecycle. It does not rotate emulator sensors.
    // Physical-pose evidence is collected separately with host sensor/display alignment.
    @Test fun captureAndWholeFitPreviewStayVisibleAtSamsungSizeAfterRotationAndLargeText() {
        val previousSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val previousDensity = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        val previousFontScale = shell("settings get system font_scale").trim()
        var scenario: ActivityScenario<MainActivity>? = null
        var projectId: String? = null
        try {
            shell("wm size 1080x2340")
            shell("wm density 450")
            shell("settings put system font_scale 1.0")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            idle()
            click("new_recording")
            val title = "QA Recorder layout ${UUID.randomUUID().toString().take(8)}"
            compose.onNodeWithTag("project_name").performTextInput(title)
            click("confirm_name")
            compose.waitUntil(10_000) { app.repository.listProjects().any { it.title == title } }
            projectId = app.repository.listProjects().first { it.title == title }.id

            var savedCount = 0
            for (fontScale in listOf(1f, 1.5f)) {
                shell("settings put system font_scale $fontScale")
                for (portrait in listOf(true, false)) {
                    scenario.onActivity { it.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    waitForConfiguration(scenario, portrait, fontScale)
                    waitForCamera(scenario)
                    val label = "${if (portrait) "portrait" else "window-landscape"}-${(fontScale * 100).toInt()}"
                    // Capture before asserting so a missing primary control leaves useful evidence.
                    shell("screencap -p /sdcard/Download/creator-recorder-$label.png")
                    assertCaptureAndPreviewVisible(scenario)
                    val beforeZoom = compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot
                    val zoom = compose.onNodeWithTag("zoom_toggle")
                    if (zoom.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)) {
                        zoom.assertIsNotEnabled()
                        compose.onNodeWithTag("camera_zoom").assertDoesNotExist()
                    } else {
                        click("zoom_toggle")
                        compose.onNodeWithTag("camera_zoom").assertIsDisplayed()
                        assertEquals(beforeZoom, compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot)
                        assertCaptureAndPreviewVisible(scenario)
                        shell("screencap -p /sdcard/Download/creator-recorder-$label-zoom.png")
                        click("zoom_toggle")
                    }
                    click("record_toggle")
                    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Pausa e salva").fetchSemanticsNodes().size == 1 }
                    waitForRecordedFrames()
                    assertCaptureAndPreviewVisible(scenario)
                    shell("screencap -p /sdcard/Download/creator-recorder-$label-recording.png")
                    click("record_toggle")
                    savedCount++
                    val expected = savedCount
                    compose.waitUntil(20_000) { app.repository.load(requireNotNull(projectId)).clips.size == expected }
                    compose.waitUntil(10_000) { compose.onNodeWithTag("record_status").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.startsWith("Pronto") } }
                }
            }

            assertEquals(4, app.repository.load(requireNotNull(projectId)).captureLedger.size)
            click("leave_recorder")
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("back").assertIsDisplayed(); true }.getOrDefault(false) }
            compose.onNodeWithTag("clip_0").assertExists()
        } finally {
            scenario?.close()
            projectId?.let { app.repository.moveToTrash(it) }
            shell("wm size ${previousSize ?: "reset"}")
            shell("wm density ${previousDensity ?: "reset"}")
            if (previousFontScale == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $previousFontScale")
        }
    }

    private fun waitForConfiguration(scenario: ActivityScenario<MainActivity>, portrait: Boolean, fontScale: Float) {
        compose.waitUntil(15_000) {
            var matches = false
            scenario.onActivity {
                val config = it.resources.configuration
                matches = config.orientation == (if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE) &&
                    abs(config.fontScale - fontScale) < .01f && config.densityDpi == 450
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun waitForCamera(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(20_000) {
            var streaming = false
            scenario.onActivity { activity -> streaming = findPreview(activity.window.decorView)?.previewStreamState?.value == PreviewView.StreamState.STREAMING }
            streaming && runCatching { compose.onNodeWithTag("record_toggle").assertIsEnabled(); true }.getOrDefault(false)
        }
    }

    private fun assertCaptureAndPreviewVisible(scenario: ActivityScenario<MainActivity>) {
        val density = app.resources.displayMetrics.density
        assertTrue("REC/Pausa must be entirely reachable without scrolling", compose.onNodeWithTag("record_toggle").fullyVisible(density))
        assertTrue("The entire preview viewport must remain visible", compose.onNodeWithTag("camera_preview").fullyVisible(density))
        compose.onNodeWithTag("record_audio_status").assertIsDisplayed()
        compose.onNodeWithTag("record_status").assertIsDisplayed()
        val preview = compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot
        val capture = compose.onNodeWithTag("capture_controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview needs a positive bounded viewport", preview.width > 0 && preview.height > 0)
        val root = compose.onNodeWithTag("recorder_layout").fetchSemanticsNode().boundsInRoot
        assertEquals("Native preview must occupy the whole recorder viewport", root, preview)
        assertTrue("Capture controls must overlay the video", capture.overlaps(compose.onNodeWithTag("recorder_frame_overlay").fetchSemanticsNode().boundsInRoot))
        assertTrue("Recorder cannot contain vertical scrollers", compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        scenario.onActivity { activity -> assertEquals(PreviewView.ScaleType.FIT_CENTER, requireNotNull(findPreview(activity.window.decorView)).scaleType) }
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPreview(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun idle() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("operation_progress").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }
    private fun click(tag: String) { val node = compose.onNodeWithTag(tag); assertTrue("$tag must be entirely visible", node.fullyVisible(app.resources.displayMetrics.density)); node.assertIsEnabled().performTouchInput { click() }; idle() }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}
