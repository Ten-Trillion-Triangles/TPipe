package com.TTT.Context

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for atomic memory persistence and same-host JVM lock coordination.
 */
class MemoryPersistenceTest
{
    private lateinit var tempDirectory: File

    @Before
    fun setUp()
    {
        tempDirectory = Files.createTempDirectory("memory-persistence-test").toFile()
    }

    @After
    fun tearDown()
    {
        tempDirectory.deleteRecursively()
    }

    /**
     * Verifies that memory persistence round-trips through the atomic write helper.
     */
    @Test
    fun writeMemoryFileRoundTripsContent() = runBlocking {
        val filePath = File(tempDirectory, "round-trip.bank")
        MemoryPersistence.writeMemoryFile(filePath.absolutePath, "first")
        MemoryPersistence.writeMemoryFile(filePath.absolutePath, "second")

        assertEquals("second", MemoryPersistence.readMemoryFile(filePath.absolutePath))
    }

    /**
     * Verifies that a reader blocks behind an exclusive lock held by another JVM process.
     */
    @Test
    fun readMemoryFileWaitsForExclusiveLockFromAnotherJvm() = runBlocking {
        val filePath = File(tempDirectory, "blocking-read.bank")
        MemoryPersistence.writeMemoryFile(filePath.absolutePath, "locked content")

        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classPath = System.getProperty("java.class.path")
        val lockHolderProcess = ProcessBuilder(
            javaBinary,
            "-cp",
            classPath,
            "com.TTT.Context.MemoryPersistenceLockHolderMainKt",
            filePath.absolutePath,
            "1500"
        ).start()

        try
        {
            val readySignal = lockHolderProcess.inputStream.bufferedReader().readLine()
            assertEquals("LOCKED", readySignal)

            val elapsedMillis = measureTimeMillis {
                val fileContents = MemoryPersistence.readMemoryFile(filePath.absolutePath)
                assertEquals("locked content", fileContents)
            }

            assertTrue(elapsedMillis >= 1000, "Reader should wait for the external writer lock before reading")
            assertEquals(0, lockHolderProcess.waitFor())
        }
        finally
        {
            lockHolderProcess.destroyForcibly()
        }
    }
}
