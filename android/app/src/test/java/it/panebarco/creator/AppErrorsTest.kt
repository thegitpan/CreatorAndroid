package it.panebarco.creator

import it.panebarco.creator.domain.ProjectEditor
import it.panebarco.creator.domain.SourceAsset
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AppErrorsTest {
    @Test fun noSpaceUsesKnownErrnoAndNeverShowsProviderPathOrEnglishCause() {
        val failure = IOException("write failed at /data/private/project.mp4", FileSystemException("/data/private/project.mp4", null, "ENOSPC"))
        val message = AppErrors.describe(failure, AppOperation.SAVE_RECORDING)
        assertTrue(message.contains("spazio disponibile è esaurito"))
        assertTrue(message.contains("Libera spazio"))
        assertFalse(message.contains("/data"))
        assertFalse(message.contains("write failed"))
    }
    @Test fun permissionAndEaccesOfferSettingsWithoutExposingThePath() {
        for (failure in listOf(AccessDeniedException("/private/secret"), FileSystemException("/private/secret", null, "EACCES"), SecurityException("Permission denied for uid 123"))) {
            val message = AppErrors.describe(failure, AppOperation.IMPORT_VIDEO)
            assertTrue(message.contains("Impostazioni > App > Creator"))
            assertFalse(message.contains("/private"))
            assertFalse(message.contains("uid"))
        }
    }
    @Test fun arbitraryProviderIOExceptionGetsTheImportNextStepWithoutRawText() {
        val message = AppErrors.describe(FileNotFoundException("Remote content provider failed at content://private/video"), AppOperation.IMPORT_VIDEO)
        assertEquals("Impossibile importare il video. Scaricalo sul dispositivo, verifica che si apra in galleria e selezionalo di nuovo.", message)
    }
    @Test fun onlyExplicitDomainBoundaryPreservesAuthoredValidation() {
        val source = SourceAsset(UUID.randomUUID().toString(), "source.mp4", 0, 1080, 1920, sha256 = "0".repeat(64))
        val failure = assertThrows(ProjectChangeException::class.java) { AppErrors.domainChange { ProjectEditor.validateMediaMetadata(source) } }
        val message = AppErrors.describe(failure, AppOperation.IMPORT_VIDEO)
        assertTrue(message.startsWith("Il video deve avere una durata positiva."))
        assertTrue(message.contains("galleria"))
        val untrusted = AppErrors.describe(IllegalArgumentException("Unexpected codec error /private/source.mp4"), AppOperation.PREVIEW)
        assertFalse(untrusted.contains("Unexpected"))
        assertFalse(untrusted.contains("/private"))
    }
}
