package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubprocessOutputCaptureTest
{
    @Test
    fun capturesLargeStdoutWithoutDeadlock() = runBlocking {
        // Output ~1MB — well past the ~64KB pipe buffer.
        // maxInMemoryBytes is 2MB so the entire output fits resident and
        // overflow-to-temp-file does not kick in. The point of this test
        // is to prove the deadlock is gone, not to exercise truncation —
        // see overflowsToTempFileAboveThreshold for the truncation path.
        val script = "import sys; sys.stdout.write('x' * (1024 * 1024))"
        val process = ProcessBuilder("python3", "-c", script).start()

        val result = SubprocessOutputCapture.capture(
            process = process,
            timeoutMs = 30_000L,
            maxInMemoryBytes = 2 * 1024 * 1024
        )

        assertEquals(1024 * 1024L, result.totalBytes)
        assertNotNull(result.stdout)
        assertEquals(1024 * 1024, result.stdout!!.length)
    }

    @Test
    fun capturesStdoutAndStderrSeparately() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; print('to stdout'); print('to stderr', file=sys.stderr)"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertEquals("to stdout\n", result.stdout)
        assertEquals("to stderr\n", result.stderr)
    }

    @Test
    fun autoDetectsNonUtf8BytesAndSurfacesAsBinary() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.buffer.write(b'\\xff\\xfe\\xfd')"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertNotNull(result.binary, "non-UTF-8 bytes should surface as ByteArray")
        assertEquals(3, result.binary!!.size)
        assertEquals(0xFF.toByte(), result.binary!![0])
        assertNull(result.stdout, "non-UTF-8 bytes should not also populate stdout string")
    }

    @Test
    fun validUtf8BytesSurfaceAsString() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.buffer.write('héllo'.encode('utf-8'))"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertEquals("héllo", result.stdout)
        assertNull(result.binary)
    }

    @Test
    fun overflowsToTempFileAboveThreshold() = runBlocking {
        val script = "import sys; sys.stdout.write('a' * (100 * 1024))"
        val process = ProcessBuilder("python3", "-c", script).start()

        val result = SubprocessOutputCapture.capture(
            process = process,
            timeoutMs = 30_000L,
            maxInMemoryBytes = 1024
        )

        assertTrue(result.truncated, "overflow should set truncated = true")
        assertEquals(100L * 1024L, result.totalBytes)
        assertNotNull(result.stdout)
        assertEquals(1024, result.stdout!!.length)
        assertNotNull(result.overflowPath)
        val onDisk = File(result.overflowPath!!).readBytes()
        assertEquals(100 * 1024, onDisk.size)
    }
}