package it.panebarco.creator.media

import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExportFilesTest {
    private val descriptors = Collections.synchronizedList(mutableListOf<FileChannel>())

    // Real CREATE_NEW behavior on the JVM. This test namespace has no concurrent path mutation
    // during open; the separate read lease pins the created inode across writer.close().
    // Production Android leases the original fd with dup, without reopening its pathname.
    private val jvmOpener = ExportFiles.Opener { destination ->
        val writer = FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        descriptors += writer
        var lease: FileChannel? = null
        try {
            val identity = identity(destination)
            val pinned = FileChannel.open(destination.toPath(), StandardOpenOption.READ)
            lease = pinned
            descriptors += pinned
            check(identity != null && identity == identity(destination))
            val output = Channels.newOutputStream(writer)
            object : ExportFiles.ExclusiveOutput {
                override val stream = output
                override val publication = ExportFiles.Publication(
                    deleteOwned = {
                        check(pinned.isOpen) { "Ownership must not be checked after releasing the lease" }
                        if (identity == identity(destination)) destination.delete() else false
                    },
                    closeLease = { pinned.close() },
                )
                override fun sync() { writer.force(true) }
                override fun close() { writer.close() }
            }
        } catch (error: Throwable) {
            try { lease?.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            try { writer.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    private fun identity(file: java.io.File): Any? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    }.getOrNull()

    @After fun allWriterAndLeaseDescriptorsAreReleased() {
        val leaked = synchronized(descriptors) { descriptors.filter { it.isOpen } }
        leaked.forEach { it.close() }
        assertTrue("Leaked ${leaked.size} publication descriptors", leaked.isEmpty())
    }

    @Test fun refusesExistingEmptyAndNonemptyDestinations() {
        val dir = Files.createTempDirectory("creator-publish").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("valid-render") }
            for (contents in listOf("", "foreign-data")) {
                val destination = dir.resolve("output.mp4").apply { writeText(contents) }
                assertThrows(IllegalArgumentException::class.java) { ExportFiles.publishNoReplace(partial, destination, opener = jvmOpener) }
                assertEquals(contents, destination.readText())
                assertEquals("valid-render", partial.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentPublishersHaveOneWinnerWithoutReplacingIt() {
        val dir = Files.createTempDirectory("creator-publish-race").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val destination = dir.resolve("output.mp4")
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val tasks = listOf("one", "two").map { content ->
                executor.submit<Boolean> {
                    val partial = dir.resolve("$content.mp4").apply { writeText(content) }
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    runCatching { ExportFiles.publishNoReplace(partial, destination, opener = jvmOpener).use {} }.isSuccess
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it })
            assertEquals(if (outcomes.first()) "one" else "two", destination.readText())
        } finally { executor.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun cleanupPreservesForeignReplacement() {
        val dir = Files.createTempDirectory("creator-publish-owner").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("valid-render") }
            val output = dir.resolve("output.mp4")
            ExportFiles.publishNoReplace(partial, output, opener = jvmOpener).use { publication ->
                assertTrue(output.delete())
                output.writeText("foreign-replacement")
                assertFalse(publication.deleteIfOwned())
                assertEquals("foreign-replacement", output.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun repeatedUnlinkRecreateCannotReuseTheLeasedInode() {
        val dir = Files.createTempDirectory("creator-publish-aba").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("valid-render") }
            val output = dir.resolve("output.mp4")
            repeat(128) { iteration ->
                ExportFiles.publishNoReplace(partial, output, opener = jvmOpener).use { publication ->
                    val ownedIdentity = identity(output)
                    assertTrue(output.delete())
                    output.writeText("foreign-$iteration")
                    assertNotEquals("A live lease must pin the unlinked inode", ownedIdentity, identity(output))
                    assertFalse(publication.deleteIfOwned())
                    assertEquals("foreign-$iteration", output.readText())
                }
                assertTrue(output.delete())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun commitPreservesOutputAndClosedCapabilityCannotDeleteAnything() {
        val dir = Files.createTempDirectory("creator-publish-commit").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("completed-render") }
            val output = dir.resolve("output.mp4")
            ExportFiles.publishNoReplace(partial, output, opener = jvmOpener).use { publication ->
                publication.commit()
                assertFalse(publication.deleteIfOwned())
                assertEquals("completed-render", output.readText())
                assertTrue(output.delete())
                output.writeText("foreign-after-commit")
                assertFalse(publication.deleteIfOwned())
                assertEquals("foreign-after-commit", output.readText())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelledCopyRemovesOwnIncompleteDestination() {
        val dir = Files.createTempDirectory("creator-publish-cancel").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeBytes(ByteArray(400_000) { 3 }) }
            val output = dir.resolve("output.mp4")
            var checks = 0
            assertThrows(CancellationException::class.java) {
                ExportFiles.publishNoReplace(partial, output, checkCancellation = {
                    if (++checks > 1) throw CancellationException()
                }, opener = jvmOpener)
            }
            assertFalse(output.exists())
            assertEquals(400_000L, partial.length())
        } finally { dir.deleteRecursively() }
    }

    @Test fun closeFailureRemovesOwnedOutputAndPreservesPrimaryFailure() {
        val dir = Files.createTempDirectory("creator-publish-close").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("completed-render") }
            val output = dir.resolve("output.mp4")
            val failingClose = ExportFiles.Opener { destination ->
                val delegate = jvmOpener.open(destination)
                object : ExportFiles.ExclusiveOutput by delegate {
                    override fun close() { delegate.close(); throw IOException("close failed") }
                }
            }
            val failure = assertThrows(IOException::class.java) {
                ExportFiles.publishNoReplace(partial, output, opener = failingClose)
            }
            assertEquals("close failed", failure.message)
            assertFalse("A failed close must not leave a reported output", output.exists())
            val cancelled = assertThrows(CancellationException::class.java) {
                ExportFiles.publishNoReplace(partial, output,
                    checkCancellation = { throw CancellationException("cancelled") }, opener = failingClose)
            }
            assertEquals("cancelled", cancelled.message)
            assertEquals("close failed", cancelled.suppressed.single().message)
            assertFalse(output.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rollbackKeepsPrimaryFailureAndSuppressesLeaseCloseFailure() {
        val primary = IOException("cleanup failed")
        var leaseCloses = 0
        var identityChecks = 0
        val publication = ExportFiles.Publication(
            deleteOwned = { identityChecks++; throw primary },
            closeLease = { leaseCloses++; throw IOException("lease close failed") },
        )
        val actual = assertThrows(IOException::class.java) { publication.deleteIfOwned() }
        assertSame(primary, actual)
        assertEquals("lease close failed", actual.suppressed.single().message)
        assertFalse(publication.deleteIfOwned())
        publication.close()
        assertEquals(1, identityChecks)
        assertEquals(1, leaseCloses)
    }

    @Test fun cancellationRetainsWriterAndLeaseCloseErrorsWithoutLeaks() {
        val dir = Files.createTempDirectory("creator-publish-both-close").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeText("completed-render") }
            val output = dir.resolve("output.mp4")
            val failingCloses = ExportFiles.Opener { destination ->
                val delegate = jvmOpener.open(destination)
                object : ExportFiles.ExclusiveOutput by delegate {
                    override val publication = ExportFiles.Publication(
                        deleteOwned = { delegate.publication.deleteIfOwned() },
                        closeLease = { delegate.publication.close(); throw IOException("lease close failed") },
                    )
                    override fun close() { delegate.close(); throw IOException("writer close failed") }
                }
            }
            val primary = CancellationException("cancelled")
            val actual = assertThrows(CancellationException::class.java) {
                ExportFiles.publishNoReplace(partial, output,
                    checkCancellation = { throw primary }, opener = failingCloses)
            }
            assertSame(primary, actual)
            assertEquals(listOf("writer close failed", "lease close failed"), actual.suppressed.map { it.message })
            assertFalse(output.exists())
        } finally { dir.deleteRecursively() }
    }
}
