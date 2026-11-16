package it.panebarco.creator.media

import java.nio.file.Files
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ExportFilesTest {
    // Actual CREATE_NEW/filesystem behavior on the JVM; Android fd ownership is instrumented.
    private val jvmOpener = ExportFiles.Opener { destination ->
        val channel = FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val identity = Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey()
        val output = Channels.newOutputStream(channel)
        object : ExportFiles.ExclusiveOutput {
            override val stream = output
            override val publication = ExportFiles.Publication {
                val current = runCatching { Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey() }.getOrNull()
                if (identity != null && identity == current) destination.delete() else false
            }
            override fun sync() { channel.force(true) }
            override fun close() { channel.close() }
        }
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
                    runCatching { ExportFiles.publishNoReplace(partial, destination, opener = jvmOpener) }.isSuccess
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
            val publication = ExportFiles.publishNoReplace(partial, output, opener = jvmOpener)
            assertTrue(output.delete())
            output.writeText("foreign-replacement")
            publication.deleteIfOwned()
            assertEquals("foreign-replacement", output.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancelledCopyRemovesOwnIncompleteDestination() {
        val dir = Files.createTempDirectory("creator-publish-cancel").toFile()
        try {
            val partial = dir.resolve("partial.mp4").apply { writeBytes(ByteArray(400_000) { 3 }) }
            val output = dir.resolve("output.mp4")
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ExportFiles.publishNoReplace(partial, output, checkCancellation = {
                    if (++checks > 1) throw java.util.concurrent.CancellationException()
                }, opener = jvmOpener)
            }
            assertFalse(output.exists())
            assertEquals(400_000L, partial.length())
        } finally { dir.deleteRecursively() }
    }

}
