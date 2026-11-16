package it.panebarco.creator

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LifecycleContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CreatorApplication
    @Test fun lateExportUpdatesCannotReplaceANewerJob() = runBlocking {
        val old = ExportJobs.begin("old-qa-project")
        val newer = ExportJobs.begin("new-qa-project")
        ExportJobs.publish(app, old.copy(phase = ExportPhase.FAILED, error = "late failure"))
        ExportJobs.failNow(app, old.projectId, old.jobId, "late asynchronous failure")
        assertEquals(newer.jobId, ExportJobs.state.value.jobId)
        assertEquals(ExportPhase.RUNNING, ExportJobs.state.value.phase)
        ExportJobs.publish(app, newer.copy(phase = ExportPhase.COMPLETE))
        ExportJobs.publish(app, newer.copy(progress = 20), persist = false)
        assertEquals(ExportPhase.COMPLETE, ExportJobs.state.value.phase)
        ExportJobs.publish(app, newer.copy(phase = ExportPhase.CANCELED))
    }
    @Test fun pickerResultWithExplicitProjectSurvivesAnEmptyViewModel() {
        val repository = app.repository
        val project = repository.create("QA lifecycle ${UUID.randomUUID()}")
        val uri = Uri.parse("content://it.panebarco.creator.test.archive/video")
        val bytes = instrumentation.context.assets.open("landscape-tone.mp4").use { it.readBytes() }
        app.contentResolver.openOutputStream(uri, "wt").use { requireNotNull(it).write(bytes) }
        val store = ViewModelStore()
        val model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CreatorModel(app, SavedStateHandle()) as T
        })[CreatorModel::class.java]
        try {
            assertNull(model.state.value.project)
            model.importVideos(project.id, listOf(uri))
            eventually { model.state.value.project?.id == project.id && !model.state.value.busy }
            assertNull(model.state.value.error)
            assertEquals(Destination.EDITOR, model.state.value.destination)
            val loaded = repository.load(project.id)
            assertEquals(1, loaded.clips.size)
            assertArrayEquals(bytes, repository.sourceFile(project.id, loaded.sources.single().id).readBytes())
        } finally { store.clear(); repository.moveToTrash(project.id) }
    }
    @Test fun nativeErrnoCausesAreTranslatedAtTheAppBoundary() {
        val noSpace = java.io.IOException("write /private/movie failed", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
        val denied = java.io.FileNotFoundException("open /private/movie denied").apply { initCause(android.system.ErrnoException("open", android.system.OsConstants.EACCES)) }
        assertTrue(AppErrors.describe(noSpace, AppOperation.SAVE_RECORDING).contains("Libera spazio"))
        assertTrue(AppErrors.describe(denied, AppOperation.IMPORT_VIDEO).contains("Impostazioni > App > Creator"))
        assertFalse(AppErrors.describe(noSpace, AppOperation.SAVE_RECORDING).contains("/private"))
    }

    private fun eventually(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("The asynchronous operation did not complete", condition())
    }
}
