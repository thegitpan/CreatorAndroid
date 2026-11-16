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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.panebarco.creator.domain.Project
import it.panebarco.creator.media.CameraUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Geometry-only presentation regression: no synthetic capture is reported as a saved take. */
@RunWith(AndroidJUnit4::class)
class RecorderErrorLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun narrowLandscapeKeepsCaptureVisibleWithDoubleTextSizeAndThreeLineCameraError() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            val state = CameraUiState(ready = true, error = "Camera ferma.\nChiudi app.\nRiprova.")
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    CreatorTheme {
                        Box(Modifier.size(600.dp, 220.dp).clipToBounds().background(Color.Black)) {
                            RecorderLayout(
                                Project.create("QA viewport"), state, preparing = false, leave = {},
                                tools = { modifier -> RecorderTools(state, false, true, false, {}, {}, {}, {}, {}, {}, {}, modifier) },
                                preview = { modifier -> Box(modifier.background(Color.Black).testTag("camera_preview")) },
                                capture = { compact -> RecorderCaptureBar(state, false, false, false, true, false, 0, true, {}, compact) },
                            )
                        }
                    }
                }
            } }
            compose.waitForIdle()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val lines = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("camera_error_detail").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(lines) }
            assertEquals(3, lines.single().lineCount)
            assertTrue("REC must keep its complete hit area in a short landscape viewport", compose.onNodeWithTag("record_toggle").fullyVisible(density))
            assertTrue("Whole preview must remain visible", compose.onNodeWithTag("camera_preview").fullyVisible(density))
            val preview = compose.onNodeWithTag("camera_preview").fetchSemanticsNode().boundsInRoot
            assertTrue("The preview cannot collapse under the error banner", preview.height > 0)
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/creator-recorder-error-200.png"))
                .bufferedReader().use { it.readText() }
            compose.onNodeWithTag("record_toggle").assertIsEnabled().performClick()
        }
    }
}
