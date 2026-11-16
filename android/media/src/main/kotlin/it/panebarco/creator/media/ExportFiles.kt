package it.panebarco.creator.media

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/** Exclusive creation prevents clobbering. The private destination is reported only after fsync. */
internal object ExportFiles {
    class Publication internal constructor(private val deleteOwned: () -> Boolean) {
        fun deleteIfOwned(): Boolean = deleteOwned()
    }

    internal interface ExclusiveOutput : Closeable {
        val stream: OutputStream
        val publication: Publication
        fun sync()
    }

    internal fun interface Opener {
        fun open(destination: File): ExclusiveOutput
    }

    /** Android SELinux forbids hard links even in private storage; O_EXCL is supported. */
    fun publishNoReplace(
        partial: File,
        destination: File,
        checkCancellation: () -> Unit = {},
        opener: Opener = AndroidOpener,
    ): Publication {
        val opened = try {
            opener.open(destination)
        } catch (error: FileAlreadyExistsException) {
            throw IllegalArgumentException("Il file di destinazione esiste già. Scegli un nuovo nome.", error)
        }
        try {
            partial.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    opened.stream.write(buffer, 0, count)
                }
            }
            checkCancellation()
            opened.sync()
            checkCancellation()
            return opened.publication
        } catch (error: Throwable) {
            opened.publication.deleteIfOwned()
            throw error
        } finally {
            opened.close()
        }
    }

    private object AndroidOpener : Opener {
        override fun open(destination: File): ExclusiveOutput {
            val descriptor = try {
                Os.open(destination.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC, 0b110000000)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) throw FileAlreadyExistsException(destination.path)
                throw IOException("Impossibile creare il video. Verifica lo spazio disponibile e riprova.", error)
            }
            val identity = try {
                Os.fstat(descriptor)
            } catch (error: Exception) {
                Os.close(descriptor)
                throw IOException("Impossibile verificare il file di esportazione.", error)
            }
            val output = FileOutputStream(descriptor)
            val ownedPublication = Publication {
                val current = runCatching { Os.lstat(destination.absolutePath) }.getOrNull()
                if (current != null && current.st_dev == identity.st_dev && current.st_ino == identity.st_ino) {
                    destination.delete()
                } else false
            }
            return object : ExclusiveOutput {
                override val stream: OutputStream = output
                override val publication = ownedPublication
                override fun sync() { descriptor.sync() }
                override fun close() { output.close() }
            }
        }
    }
}
