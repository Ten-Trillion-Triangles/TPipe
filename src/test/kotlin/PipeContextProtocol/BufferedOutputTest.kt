package com.TTT.PipeContextProtocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferedOutputTest
{
    @Test
    fun bufferedOutputCarriesChannelSeparation()
    {
        val buffer = BufferedOutput(
            stdout = "hello",
            stderr = "warn: deprecated",
            binary = null,
            totalBytes = 19L,
            truncated = false
        )

        assertEquals("hello", buffer.stdout)
        assertEquals("warn: deprecated", buffer.stderr)
        assertNull(buffer.binary)
        assertEquals(19L, buffer.totalBytes)
        assertTrue(!buffer.truncated)
    }

    @Test
    fun pcpRequestResultExposesOutputBufferAlongsideOutputString()
    {
        val result = PcpRequestResult(
            success = true,
            output = "hello\nSTDERR: warn",
            executionTimeMs = 42L,
            transport = Transport.Python,
            outputBuffer = BufferedOutput(
                stdout = "hello",
                stderr = "warn",
                binary = null,
                totalBytes = 9L,
                truncated = false
            )
        )

        // Back-compat: output field populated as before
        assertEquals("hello\nSTDERR: warn", result.output)
        // New: channel separation available via outputBuffer
        assertNotNull(result.outputBuffer)
        assertEquals("hello", result.outputBuffer.stdout)
    }

    @Test
    fun pcpRequestResultDefaultsOutputBufferToNull()
    {
        // Existing call sites that don't set outputBuffer still compile and work
        val result = PcpRequestResult(
            success = true,
            output = "hello",
            executionTimeMs = 1L,
            transport = Transport.Http
        )

        assertNull(result.outputBuffer)
    }
}