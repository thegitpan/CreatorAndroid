package it.panebarco.creator

import android.os.SystemClock
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
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
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
            click("leave_recorder")
            click("back")
            click("record_$id")
            recordSegment { repository.load(id).clips.size == 3 }
            click("leave_recorder")
            val after = repository.load(id)
            assertEquals(3, after.captureLedger.size)
            after.sources.forEach { source ->
                val file = repository.sourceFile(id, source.id)
                assertTrue(MediaProbe.inspect(file).durationUs > 500_000L)
                assertNotNull(MediaProbe.thumbnail(file))
                if (source.id in hashes) assertEquals(hashes[source.id], sha(file.readBytes()))
            }
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
    }
    private fun idle() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("operation_progress").fetchSemanticsNodes().isEmpty() }; compose.waitForIdle() }
    private fun click(tag: String) { val node = compose.onNodeWithTag(tag); runCatching { node.performScrollTo() }; node.assertIsDisplayed().performClick(); idle() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
