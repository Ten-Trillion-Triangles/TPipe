package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BinaryAutoDetectTest
{
    @Test
    fun asciiTextSurfacesAsString() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.write('hello world')"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertEquals("hello world", result.stdout)
        assertNull(result.binary)
        Unit
    }

    @Test
    fun validUtf8MultiByteSurfacesAsString() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.buffer.write('日本語'.encode('utf-8'))"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertEquals("日本語", result.stdout)
        assertNull(result.binary)
        Unit
    }

    @Test
    fun invalidUtf8SurfacesAsBinaryOnly() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.buffer.write(b'\\xc0\\x80\\xff\\xfe')"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertNull(result.stdout, "invalid UTF-8 must not populate stdout string")
        assertNotNull(result.binary)
        assertEquals(4, result.binary!!.size)
        Unit
    }

    @Test
    fun mixedValidAndInvalidBytesTriggerBinaryFallback() = runBlocking {
        val process = ProcessBuilder(
            "python3", "-c",
            "import sys; sys.stdout.buffer.write(b'hello\\xff\\xfe')"
        ).start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertNotNull(result.binary)
        assertNull(result.stdout)
        Unit
    }

    @Test
    fun emptyOutputProducesBothFieldsNull() = runBlocking {
        val process = ProcessBuilder("python3", "-c", "pass").start()

        val result = SubprocessOutputCapture.capture(process, 10_000L, 64 * 1024)

        assertNull(result.stdout)
        assertNull(result.binary)
        assertEquals(0L, result.totalBytes)
        Unit
    }
}