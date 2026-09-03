package com.TTT.PipeContextProtocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WireFormatBackcompatTest
{
    @Test
    fun existingConstructionSiteWithoutOutputBufferStillCompilesAndRuns()
    {
        // This exact construction pattern exists in every executor pre-refactor.
        // If the field were renamed or removed, all 80+ call sites would break.
        val result = PcpRequestResult(
            success = true,
            output = "hello",
            executionTimeMs = 1L,
            transport = Transport.Http
        )

        assertEquals("hello", result.output)
        assertNull(result.outputBuffer)
    }

    @Test
    fun outputBufferIsOptionalWithNullDefault()
    {
        val r1 = PcpRequestResult(true, "x", 1L, Transport.Stdio)
        val r2 = PcpRequestResult(true, "x", 1L, Transport.Stdio, null)
        val r3 = PcpRequestResult(true, "x", 1L, Transport.Stdio, null, null)

        assertNull(r1.outputBuffer)
        assertNull(r2.outputBuffer)
        assertNull(r3.outputBuffer)
    }

    @Test
    fun serializationRoundTripPreservesOutputField()
    {
        val original = PcpRequestResult(
            success = true,
            output = "stdout-text\nSTDERR: stderr-text",
            executionTimeMs = 42L,
            transport = Transport.Python
        )

        val json = com.TTT.Util.serialize(original)
        val deserialized = com.TTT.Util.deserialize<PcpRequestResult>(json)!!

        assertEquals(original.output, deserialized.output)
        assertEquals(original.executionTimeMs, deserialized.executionTimeMs)
        assertEquals(original.transport, deserialized.transport)
    }

    @Test
    fun serializationRoundTripPreservesOutputBuffer()
    {
        val original = PcpRequestResult(
            success = true,
            output = "stdout-text\nSTDERR: stderr-text",
            executionTimeMs = 42L,
            transport = Transport.Python,
            outputBuffer = BufferedOutput(
                stdout = "stdout-text",
                stderr = "stderr-text",
                binary = null,
                totalBytes = 23L,
                truncated = false
            )
        )

        val json = com.TTT.Util.serialize(original)
        val deserialized = com.TTT.Util.deserialize<PcpRequestResult>(json)!!

        assertNotNull(deserialized.outputBuffer)
        assertEquals("stdout-text", deserialized.outputBuffer!!.stdout)
        assertEquals("stderr-text", deserialized.outputBuffer!!.stderr)
        assertEquals(23L, deserialized.outputBuffer!!.totalBytes)
    }

    @Test
    fun nativeOutputRemainsInProcessOnly()
    {
        val original = PcpRequestResult(
            success = true,
            output = "native-result",
            executionTimeMs = 42L,
            transport = Transport.Tpipe
        ).apply {
            nativeOutput = mapOf("key" to "value")
        }

        val json = com.TTT.Util.serialize(original)
        val deserialized = com.TTT.Util.deserialize<PcpRequestResult>(json)!!

        assertFalse(json.contains("nativeOutput"))
        assertEquals("native-result", deserialized.output)
        assertEquals(Transport.Tpipe, deserialized.transport)
        assertNull(deserialized.nativeOutput)
    }
}
