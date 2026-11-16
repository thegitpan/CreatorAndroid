package it.panebarco.creator

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import it.panebarco.creator.domain.CanvasShape
import it.panebarco.creator.domain.Project
import it.panebarco.creator.media.MediaProbe
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CreatorWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CreatorApplication
    private val repository get() = app.repository
    private var scenario: ActivityScenario<MainActivity>? = null
    private val owned = mutableSetOf<String>()
    private var intercepting = false
    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); idle() }
    private fun idle() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("operation_progress").fetchSemanticsNodes().isEmpty() }; compose.waitForIdle() }
    private fun waitProject(id: String, predicate: (Project) -> Boolean) { compose.waitUntil(15_000) { runCatching { predicate(repository.load(id)) }.getOrDefault(false) }; idle() }
    private fun click(tag: String) { compose.clickVisibleControl(tag, app.resources.displayMetrics.density); idle() }
    private fun seed(): Project {
        var project = repository.create("QA Editor ${UUID.randomUUID().toString().take(8)}")
        owned += project.id
        for (name in listOf("landscape-tone.mp4", "portrait-silent.mp4")) {
            val stage = repository.stageFile(project.id)
            instrumentation.context.assets.open(name).use { input -> stage.outputStream().use { input.copyTo(it) } }
            project = repository.commitSource(project.id, stage, MediaProbe.inspect(stage), recording = false)
        }
        return project
    }
    @After fun finish() { if (intercepting) Intents.release(); scenario?.close(); owned.forEach { id -> runCatching { if (!repository.isInTrash(id)) repository.moveToTrash(id) } } }

    @Test fun libraryCreatesRenamesTrashesAndRestoresThroughPublicControls() {
        launch()
        val title = "QA Libreria ${UUID.randomUUID().toString().take(8)}"
        click("new_project")
        compose.onNodeWithTag("project_name").performTextInput(title)
        click("confirm_name")
        compose.waitUntil(10_000) { repository.listProjects().any { it.title == title } }
        val project = repository.listProjects().first { it.title == title }
        owned += project.id
        click("back")
        click("project_menu_${project.id}")
        compose.onNodeWithText("Rinomina").performClick()
        compose.onNodeWithTag("project_name").performTextReplacement("$title rinominato")
        click("confirm_name")
        waitProject(project.id) { it.title.endsWith("rinominato") }
        click("project_menu_${project.id}")
        compose.onNodeWithText("Sposta nel cestino").performClick()
        click("confirm_trash")
        compose.waitUntil(10_000) { repository.isInTrash(project.id) }
        click("trash_tab")
        click("restore_${project.id}")
        compose.waitUntil(10_000) { !repository.isInTrash(project.id) }
        click("projects_tab")
        compose.onNodeWithText("$title rinominato").assertIsDisplayed()
    }

    @Test fun editorControlsPersistTrimSplitUndoRedoReorderVolumeAndCanvasWithoutChangingOriginals() {
        val original = seed()
        val checksums = original.sources.associate { it.id to sha(repository.sourceFile(original.id, it.id).readBytes()) }
        launch()
        click("edit_${original.id}")
        compose.onNodeWithTag("clip_0").performClick()
        compose.onNodeWithTag("timeline_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(.28f) }
        compose.onNodeWithTag("split").performScrollTo().assertIsEnabled().performClick()
        waitProject(original.id) { it.clips.size == 3 }
        click("undo")
        waitProject(original.id) { it.clips.size == 2 }
        click("redo")
        waitProject(original.id) { it.clips.size == 3 }
        click("clip_0")
        click("trim")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("trim_start").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("trim_start").performTextReplacement("0.2")
        compose.onNodeWithTag("trim_end").performTextReplacement("1.0")
        click("apply_trim")
        waitProject(original.id) { it.clips.first().sourceStartUs == 200_000L && it.clips.first().sourceEndUs == 1_000_000L }
        val moved = repository.load(original.id).clips.first().id
        click("move_right")
        waitProject(original.id) { it.clips[1].id == moved }
        click("mute")
        waitProject(original.id) { it.clips.first { c -> c.id == moved }.volume == 0f }
        compose.onNodeWithTag("volume").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        waitProject(original.id) { kotlin.math.abs(it.clips.first { c -> c.id == moved }.volume - 1.4f) < .02f }
        click("canvas_landscape")
        waitProject(original.id) { it.canvas == CanvasShape.LANDSCAPE }
        click("delete_clip")
        click("confirm_delete_clip")
        waitProject(original.id) { it.clips.size == 2 }
        click("undo")
        waitProject(original.id) { it.clips.size == 3 }
        click("originals")
        compose.onNodeWithText("Originali conservati").assertIsDisplayed()
        compose.onNodeWithTag("original_0").assertExists()
        original.sources.forEach { source -> assertEquals(checksums[source.id], sha(repository.sourceFile(original.id, source.id).readBytes())) }
        click("back")
        scenario?.recreate()
        idle()
        click("edit_${original.id}")
        compose.onNodeWithTag("redo").assertIsEnabled()
        assertEquals(3, repository.load(original.id).clips.size)
        assertEquals(2, repository.load(original.id).sources.size)
    }
    @Test fun backupAndRestoreUseDocumentControlsAndVerifyAllOriginalBytes() {
        val original = seed()
        val idsBefore = repository.listProjects(includeTrash = true).map { it.id }.toSet()
        val hashes = original.sources.associate { it.id to sha(repository.sourceFile(original.id, it.id).readBytes()) }
        launch()
        Intents.init()
        intercepting = true
        val uri = Uri.parse("content://it.panebarco.creator.test.archive/round-trip")
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        click("edit_${original.id}")
        click("backup")
        compose.waitUntil(15_000) { runCatching { app.contentResolver.openInputStream(uri).use { it != null && it.read() >= 0 } }.getOrDefault(false) }
        idle()
        Intents.intended(hasAction(Intent.ACTION_CREATE_DOCUMENT))
        click("back")
        click("import_backup")
        compose.waitUntil(15_000) { repository.listProjects().any { it.id !in idsBefore } }
        idle()
        val restored = repository.listProjects().first { it.id !in idsBefore }
        owned += restored.id
        assertNotEquals(original.id, restored.id)
        assertEquals(original.clips, restored.clips)
        assertEquals(original.history, restored.history)
        original.sources.forEach { source -> assertEquals(hashes[source.id], sha(repository.sourceFile(restored.id, source.id).readBytes())) }
        Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
    }

    @Test fun exportRunsForegroundThenGalleryShareAndCancellationPreserveTheProject() {
        val original = seed()
        val hashes = original.sources.associate { it.id to sha(repository.sourceFile(original.id, it.id).readBytes()) }
        launch()
        click("edit_${original.id}")
        click("export")
        compose.waitUntil(90_000) { ExportJobs.state.value.projectId == original.id && ExportJobs.state.value.phase in listOf(ExportPhase.COMPLETE, ExportPhase.FAILED) }
        val completed = ExportJobs.state.value
        assertEquals(completed.error, ExportPhase.COMPLETE, completed.phase)
        assertEquals(original.revisionId, completed.revisionId)
        val file = requireNotNull(completed.file)
        val metadata = MediaProbe.inspect(file)
        assertTrue(kotlin.math.abs(metadata.durationUs - original.durationUs) < 250_000L)
        click("save_gallery")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Salvato").fetchSemanticsNodes().isNotEmpty() }
        val uri = app.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.Video.Media._ID), "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("${original.title}-%"), null)?.use { cursor -> if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null }
        assertNotNull("A completed video must exist in MediaStore", uri)
        try {
            assertArrayEquals(file.readBytes(), app.contentResolver.openInputStream(requireNotNull(uri)).use { it!!.readBytes() })
            Intents.init(); intercepting = true
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            click("share_export")
            Intents.intended(hasAction(Intent.ACTION_CHOOSER))
            val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
            val sharing = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, sharing.action)
            val stream = sharing.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("${app.packageName}.files", stream.authority)
            assertTrue(sharing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(app.contentResolver.openInputStream(stream).use { it!!.read() >= 0 })
            click("export")
            compose.waitUntil(10_000) { ExportJobs.state.value.phase == ExportPhase.RUNNING }
            click("cancel_export")
            compose.waitUntil(15_000) { ExportJobs.state.value.phase == ExportPhase.CANCELED }
            click("export")
            compose.waitUntil(90_000) { ExportJobs.state.value.phase in listOf(ExportPhase.COMPLETE, ExportPhase.FAILED) }
            assertEquals(ExportPhase.COMPLETE, ExportJobs.state.value.phase)
            assertEquals(original.clips, repository.load(original.id).clips)
            original.sources.forEach { source -> assertEquals(hashes[source.id], sha(repository.sourceFile(original.id, source.id).readBytes())) }
        } finally { if (uri != null) app.contentResolver.delete(uri, null, null) }
    }

    @Test fun splitUsesThePlayheadInsideAnotherClipWithoutChangingSelection() {
        val original = seed()
        launch()
        click("edit_${original.id}")
        click("clip_0")
        compose.onNodeWithTag("timeline_scrubber").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        compose.onNodeWithTag("selected_clip").assertTextEquals("Clip 1 selezionata")
        compose.onNodeWithTag("split").performScrollTo().assertIsEnabled().performClick()
        waitProject(original.id) { it.clips.size == 3 }
        val split = repository.load(original.id)
        val expectedSourceTime = original.clips[1].sourceStartUs + original.durationUs * 3 / 4 - original.clips[0].durationUs
        assertEquals(original.clips[0], split.clips[0])
        assertEquals(original.clips[1].sourceId, split.clips[1].sourceId)
        assertEquals(original.clips[1].sourceId, split.clips[2].sourceId)
        assertEquals(expectedSourceTime, split.clips[1].sourceEndUs)
        assertEquals(expectedSourceTime, split.clips[2].sourceStartUs)
        click("undo")
        waitProject(original.id) { it.clips == original.clips }
    }

    @Test fun exportReservationBlocksAllPublicCameraEntryPointsUntilReleased() {
        val original = seed()
        val token = MediaWorkCoordinator.reserveExport()
        val job = ExportJobs.begin(original.id, token)
        launch()
        try {
            click("record_${original.id}")
            compose.onNodeWithText(MediaWorkCoordinator.WAIT_FOR_EXPORT).assertIsDisplayed()
            compose.onNodeWithText("Ho capito").performClick()
            click("new_recording")
            compose.onNodeWithTag("project_name").performTextInput("QA must not be created")
            click("confirm_name")
            compose.onNodeWithText(MediaWorkCoordinator.WAIT_FOR_EXPORT).assertIsDisplayed()
            assertFalse(repository.listProjects().any { it.title == "QA must not be created" })
            compose.onNodeWithText("Ho capito").performClick()
            click("edit_${original.id}")
            click("continue_recording")
            compose.onNodeWithText(MediaWorkCoordinator.WAIT_FOR_EXPORT).assertIsDisplayed()
            compose.onAllNodesWithTag("camera_preview").assertCountEquals(0)
            compose.onNodeWithText("Ho capito").performClick()
        } finally {
            MediaWorkCoordinator.releaseExport(token)
            kotlinx.coroutines.runBlocking { ExportJobs.publish(app, job.copy(phase = ExportPhase.CANCELED)) }
        }
        scenario?.onActivity { activity ->
            val model = androidx.lifecycle.ViewModelProvider(activity)[CreatorModel::class.java]
            model.navigate(Destination.RECORDER)
            assertEquals(Destination.RECORDER, model.state.value.destination)
            model.navigate(Destination.EDITOR)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
