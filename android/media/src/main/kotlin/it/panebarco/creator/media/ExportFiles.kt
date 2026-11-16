package it.panebarco.creator.media

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/**
 * Exclusive creation prevents clobbering. Completion follows copy, fsync and writer close.
 * A returned Publication owns a live descriptor lease until commit/rollback/close. That lease
 * pins its inode, preventing unlink/recreate from reusing the identity during cleanup.
 *
 * Export paths are private to the app and have one writer. Identity-check + unlink is not an
 * atomic conditional unlink against an arbitrary concurrent mutator of the same private path.
 */
internal object ExportFiles {
    class Publication internal constructor(
        private val deleteOwned: () -> Boolean,
        private val closeLease: () -> Unit,
    ) : Closeable {
        private var leaseOpen = true

        /** Rollback is terminal. The ownership check runs before releasing the inode lease. */
        @Synchronized
        fun deleteIfOwned(): Boolean {
            if (!leaseOpen) return false
            var failure: Throwable? = null
            val deleted = try {
                deleteOwned()
            } catch (error: Throwable) {
                failure = error
                false
            }
            try {
                close()
            } catch (closeError: Throwable) {
                if (failure == null) failure = closeError else failure.addSuppressed(closeError)
            }
            failure?.let { throw it }
            return deleted
        }

        /** Commit preserves the completed file and permanently disables rollback. */
        fun commit() = close()

        /** Disable identity-based deletion before closing: a failed close cannot enable reuse. */
        @Synchronized
        override fun close() {
            if (!leaseOpen) return
            leaseOpen = false
            closeLease()
        }
    }

    internal interface ExclusiveOutput : Closeable {
        val stream: OutputStream
        val publication: Publication
        fun sync()
        /** Closes only the writer; publication's independently-owned lease must remain live. */
        override fun close()
    }

    internal fun interface Opener {
        fun open(destination: File): ExclusiveOutput
    }

    /** Caller must commit/rollback/close the returned lease, including later cancellation paths. */
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
        var closeAttempted = false
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
            closeAttempted = true
            opened.close()
            return opened.publication
        } catch (error: Throwable) {
            if (!closeAttempted) {
                try { opened.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            }
            try { opened.publication.deleteIfOwned() } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
            throw error
        }
    }

    /** FileOutputStream(fd) is non-owning on Android; the Os.open descriptor is ours to close. */
    private fun closeWriter(writer: FileOutputStream, descriptor: FileDescriptor) {
        var failure: Throwable? = null
        try { writer.close() } catch (error: Throwable) { failure = error }
        try {
            if (descriptor.valid()) Os.close(descriptor)
        } catch (closeError: Throwable) {
            if (failure == null) failure = closeError else failure.addSuppressed(closeError)
        }
        failure?.let { throw it }
    }

    private object AndroidOpener : Opener {
        override fun open(destination: File): ExclusiveOutput {
            val descriptor = try {
                Os.open(destination.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC, 0b110000000)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) throw FileAlreadyExistsException(destination.path)
                throw IOException("Impossibile creare il video. Verifica lo spazio disponibile e riprova.", error)
            }
            var lease: FileDescriptor? = null
            var output: FileOutputStream? = null
            try {
                // dup refers to this exact inode; reopening the pathname could lease a replacement.
                val pinnedDescriptor = Os.dup(descriptor)
                lease = pinnedDescriptor
                Os.fcntlInt(pinnedDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
                val identity = Os.fstat(pinnedDescriptor)
                val writer = FileOutputStream(descriptor)
                output = writer
                val ownedPublication = Publication(
                    deleteOwned = {
                        val current = runCatching { Os.lstat(destination.absolutePath) }.getOrNull()
                        if (current != null && current.st_dev == identity.st_dev && current.st_ino == identity.st_ino) {
                            destination.delete()
                        } else false
                    },
                    closeLease = { Os.close(pinnedDescriptor) },
                )
                return object : ExclusiveOutput {
                    override val stream: OutputStream = writer
                    override val publication = ownedPublication
                    override fun sync() { descriptor.sync() }
                    private var writerClosed = false
                    override fun close() {
                        if (writerClosed) return
                        writerClosed = true
                        closeWriter(writer, descriptor)
                    }
                }
            } catch (error: Throwable) {
                // Setup failed while the original descriptor still pins the created inode.
                try {
                    val owned = Os.fstat(descriptor)
                    val current = runCatching { Os.lstat(destination.absolutePath) }.getOrNull()
                    if (current != null && current.st_dev == owned.st_dev && current.st_ino == owned.st_ino) destination.delete()
                } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
                lease?.let { try { Os.close(it) } catch (closeError: Throwable) { error.addSuppressed(closeError) } }
                try {
                    if (output != null) closeWriter(output, descriptor) else Os.close(descriptor)
                } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                throw IOException("Impossibile preparare il file di esportazione.", error)
            }
        }
    }
}
