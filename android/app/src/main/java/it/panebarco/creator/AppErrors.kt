package it.panebarco.creator

import android.system.ErrnoException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

/** Authored UI context stays separate from exceptions produced by storage, codecs and providers. */
enum class AppOperation(val failure: String, val nextStep: String) {
    LIBRARY("Impossibile leggere la libreria", "Riavvia Creator e controlla lo spazio disponibile."),
    CREATE_PROJECT("Impossibile creare il progetto", "Scegli un nome leggibile e riprova."),
    OPEN_PROJECT("Impossibile aprire il progetto", "Conserva i dati, torna alla libreria e prova a ripristinare un backup."),
    EDIT_PROJECT("Impossibile salvare la modifica", "Correggi il valore oppure riapri il progetto e riprova. Gli originali sono conservati."),
    TRASH_PROJECT("Impossibile spostare il progetto nel cestino", "Torna alla libreria e riprova."),
    RESTORE_PROJECT("Impossibile ripristinare il progetto", "Controlla lo spazio disponibile e riprova dalla libreria."),
    IMPORT_VIDEO("Impossibile importare il video", "Scaricalo sul dispositivo, verifica che si apra in galleria e selezionalo di nuovo."),
    START_RECORDING("Impossibile avviare la ripresa", "Consenti fotocamera e microfono, chiudi altre app camera e riprova."),
    SAVE_RECORDING("Impossibile salvare la ripresa", "Il file temporaneo è conservato: apri Originali e scegli Recupera video interrotti."),
    EXPORT_BACKUP("Impossibile salvare il backup", "Controlla lo spazio nella destinazione e scegli un altro file."),
    IMPORT_BACKUP("Impossibile ripristinare il backup", "Scegli un archivio Creator completo e verifica lo spazio disponibile. I progetti esistenti sono conservati."),
    RECOVER_VIDEO("Impossibile recuperare il video interrotto", "Il file è conservato. Verifica che sia riproducibile e riprova da Originali."),
    PREVIEW("Anteprima non disponibile", "Verifica che l’originale si apra in galleria oppure importa un video H.264/AAC. Il progetto è conservato."),
    ORIGINAL("Originale non disponibile", "Conserva il progetto e prova a ripristinare il backup."),
    EXPORT_START("Il sistema non ha avviato l’esportazione", "Lascia l’app aperta, verifica le impostazioni batteria e riprova."),
    EXPORT_VIDEO("Impossibile esportare il video", "Controlla lo spazio disponibile, riapri il progetto e riprova."),
    GALLERY("Impossibile salvare in galleria", "Controlla lo spazio disponibile e riprova. Il video esportato resta nell’app."),
    SHARE("Impossibile condividere il video", "Apri o installa un’app compatibile e riprova."),
    DIAGNOSTICS("Impossibile raccogliere la diagnostica", "Riavvia l’app e riprova."),
}

/** Only app-authored feedback, never an arbitrary provider/codec message. */
internal class UserFeedbackException(val feedback: String) : IllegalStateException(feedback)
internal class ProjectChangeException(val feedback: String, cause: IllegalArgumentException) : IllegalArgumentException(feedback, cause)

object AppErrors {
    /** Call around the pure ProjectEditor operation only, never around I/O or MediaProbe. */
    fun <T> domainChange(change: () -> T): T = try { change() }
    catch (error: IllegalArgumentException) {
        throw ProjectChangeException(error.message ?: "La modifica del progetto non è valida.", error)
    }

    fun describe(error: Throwable, operation: AppOperation): String {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.any { errno(it) == 28 || errno(it) == 122 }) {
            return "${operation.failure}. Lo spazio disponibile è esaurito. Libera spazio sul dispositivo o scegli una destinazione diversa, poi riprova."
        }
        if (causes.any { it is SecurityException || it is AccessDeniedException || errno(it) == 13 || errno(it) == 1 }) {
            return "${operation.failure}. L’accesso non è consentito. Controlla i permessi in Impostazioni > App > Creator oppure scegli nuovamente il file."
        }
        causes.filterIsInstance<ProjectChangeException>().firstOrNull()?.let { return "${it.feedback} ${operation.nextStep}" }
        causes.filterIsInstance<UserFeedbackException>().firstOrNull()?.let { return it.feedback }
        return "${operation.failure}. ${operation.nextStep}"
    }

    private fun errno(error: Throwable): Int? = when (error) {
        is ErrnoException -> error.errno
        // Symbolic errno is a stable machine code, not a guess about the message's language.
        is FileSystemException -> when (error.reason) { "ENOSPC" -> 28; "EDQUOT" -> 122; "EACCES" -> 13; "EPERM" -> 1; else -> null }
        else -> null
    }
}
