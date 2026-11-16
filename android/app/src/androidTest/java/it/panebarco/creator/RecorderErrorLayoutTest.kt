package it.panebarco.creator

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.panebarco.creator.media.CameraUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation-only fault/permission states. Native-camera workflows are tested separately. */
@RunWith(AndroidJUnit4::class)
class RecorderErrorLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun narrowLandscapeKeepsCaptureAndPermissionTargetsReachableAtDoubleTextSize() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            val camera = CameraUiState(ready = true, maxZoomRatio = 4f, previewAspectRatio = 16f / 9f, qualityLabel = "720p · SDR · richiesti 30 fps", canLockFocus = true, canLockExposure = true, hasFrontCamera = true, hasBackCamera = true)
            val ui = mutableStateOf(RecorderViewState(camera, canRecord = false, microphoneAllowed = false))
            var recorded = 0
            var audioRequests = 0
            var cameraRequests = 0
            var settings = 0
            var dismissals = 0
            val actions = RecorderActions(
                onRecord = { recorded++ }, onLeave = {}, onAudioToggle = { audioRequests++ },
                onSilentChoice = { ui.value = ui.value.copy(silent = true, canRecord = true) },
                onSettings = { settings++ }, onPermission = { cameraRequests++ }, onSwitchCamera = {}, onFocusLock = {}, onExposureLock = {},
                onZoom = { value -> ui.value = ui.value.copy(camera = ui.value.camera.copy(zoomRatio = value)) }, onDismissError = { dismissals++ },
            )
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    CreatorTheme {
                        Box(Modifier.size(600.dp, 220.dp).clipToBounds().background(Color.Black)) {
                            RecorderLayout(ui.value, actions) { modifier -> Box(modifier.background(Color.Black).testTag("camera_preview")) }
                        }
                    }
                }
            } }
            compose.waitForIdle()
            screenshot("permission-before-assert")
            assertTargets("record_toggle", "camera_permission", "record_silent_choice", "camera_settings", "switch_camera", "recorder_editor")
            compose.onNodeWithTag("camera_permission").performTouchInput { click() }
            compose.onNodeWithTag("camera_settings").performTouchInput { click() }
            assertEquals(1, audioRequests)
            assertEquals(1, settings)
            compose.onNodeWithTag("microphone").assertContentDescriptionEquals("Consenti microfono")
            compose.onNodeWithTag("zoom_toggle").assertIsEnabled().performTouchInput { click() }
            assertTargets("camera_zoom", "record_toggle", "switch_camera", "recorder_editor")
            dragZoom()
            assertTrue(ui.value.camera.zoomRatio > 1.1f)
            compose.onNodeWithTag("zoom_toggle").performTouchInput { click() }
            screenshot("microphone-denied-200")
            compose.onNodeWithTag("record_silent_choice").performTouchInput { click() }
            compose.onNodeWithTag("record_toggle").assertIsEnabled().performTouchInput { click() }
            assertEquals(1, recorded)
            compose.onNodeWithTag("zoom_toggle").performTouchInput { click() }
            assertTargets("camera_zoom", "record_toggle", "switch_camera", "recorder_editor")
            dragZoom()
            compose.onNodeWithTag("zoom_toggle").performTouchInput { click() }
            compose.runOnIdle { ui.value = ui.value.copy(cameraAllowed = false, canRecord = false) }
            assertTargets("record_toggle", "camera_permission", "camera_settings", "switch_camera", "recorder_editor")
            compose.onNodeWithTag("camera_permission").performTouchInput { click() }
            assertEquals(1, cameraRequests)
            screenshot("camera-denied-200")
            compose.runOnIdle { ui.value = ui.value.copy(cameraAllowed = true, error = "Fotocamera occupata.\nChiudi altre app.\nRiprova.") }
            compose.waitForIdle()
            compose.onNodeWithTag("camera_error").assertIsDisplayed()
            assertTargets("record_toggle")
            screenshot("camera-error-200")
            assertTargets("error_details")
            compose.onNodeWithTag("error_details").performTouchInput { click() }
            compose.onNodeWithText(requireNotNull(ui.value.error)).assertIsDisplayed()
            screenshot("camera-error-details-200")
            compose.onNodeWithTag("close_error_details").performTouchInput { click() }
            compose.onNodeWithContentDescription("Chiudi avviso").performTouchInput { click() }
            compose.onNodeWithTag("camera_error").assertDoesNotExist()
            assertEquals(1, dismissals)
            assertNotNull(ui.value.error)
            val preview = compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot
            assertEquals(compose.onNodeWithTag("recorder_layout").fetchSemanticsNode().boundsInRoot, preview)
            assertTrue(preview.overlaps(compose.onNodeWithTag("capture_controls").fetchSemanticsNode().boundsInRoot))
            assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    private fun assertTargets(vararg tags: String) {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag)
            assertTrue("$tag must be entirely visible", node.fullyVisible(density))
            val rect = node.fetchSemanticsNode().boundsInRoot
            println("CREATOR_PRO_TARGET $tag bounds=$rect density=$density frame=${compose.onNodeWithTag("recorder_frame_overlay").fetchSemanticsNode().boundsInRoot}")
            assertTrue("$tag needs a 48dp touch width", rect.width + 1 >= 48 * density)
            assertTrue("$tag needs a 48dp touch height: $rect at density $density", rect.height + 1 >= 48 * density)
        }
    }
    private fun screenshot(name: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/creator-pro-$name.png")).bufferedReader().use { it.readText() }
    }
    private fun dragZoom() {
        compose.onNodeWithTag("camera_zoom").performTouchInput { swipe(Offset(width * .2f, height * .5f), Offset(width * .8f, height * .5f), 400) }
    }
}
