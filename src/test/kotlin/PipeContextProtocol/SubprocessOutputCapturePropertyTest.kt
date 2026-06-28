package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubprocessOutputCapturePropertyTest
{
    @Test
    fun invariantHoldsAcrossRandomOutputSizes() = runBlocking {
        val random = Random(seed = 42L)
        val sizes = listOf(0, 1, 100, 1024)
        val maxInMemory = 256 * 1024

        for(size in sizes)
        {
            for(trial in 0..2)
            {
                val bytes = random.nextBytes(size)
                val script = "import sys; sys.stdout.buffer.write(${pythonBytesLiteral(bytes)})"
                val process = ProcessBuilder("python3", "-c", script).start()
                val result = SubprocessOutputCapture.capture(process, 60_000L, maxInMemory)

                assertTrue(result.totalBytes >= 0)
                if(size <= maxInMemory)
                {
                    assertEquals(false, result.truncated,
                        "size=$size <= max=$maxInMemory must not truncate")
                }
                // totalBytes reflects the FULL stream contents (the cap
                // only affects in-memory residency, not the byte count).
                assertEquals(size.toLong(), result.totalBytes,
                    "totalBytes (${result.totalBytes}) != input size ($size)")
            }
        }
        Unit
    }

    @Test
    fun invariantHoldsForMixedUtf8AndBinary() = runBlocking {
        for(size in listOf(100, 10_000))
        {
            val validUtf8 = "héllo wörld ".repeat(size / 13)
            val invalidTrailer = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            val bytes = validUtf8.toByteArray(Charsets.UTF_8) + invalidTrailer
            val script = "import sys; sys.stdout.buffer.write(${pythonBytesLiteral(bytes)})"
            val process = ProcessBuilder("python3", "-c", script).start()
            val result = SubprocessOutputCapture.capture(process, 60_000L, 256 * 1024)

            assertNotNull(result.binary, "trailing invalid bytes must trigger binary fallback")
            assertEquals(bytes.size, result.binary!!.size)
            assertEquals(null, result.stdout)
        }
        Unit
    }

    private fun pythonBytesLiteral(bytes: ByteArray): String
    {
        val sb = StringBuilder("b'")
        for(b in bytes)
        {
            val unsigned = b.toInt() and 0xFF
            when(unsigned)
            {
                // 0x5C is backslash, 0x27 is single quote — both must be
                // escaped in a Python bytes literal to avoid breaking the
                // string boundary. 0x09/0x0A/0x0D also need escaping
                // because they terminate the source line (newline) or
                // have special meaning in a bytes literal (escape
                // sequences), even though they would be valid as raw
                // bytes — the issue is the wire-format, not the data.
                0x5C -> sb.append("\\\\")
                0x27 -> sb.append("\\'")
                0x09 -> sb.append("\\t")
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                in 0x20..0x7E -> sb.append(b.toInt().toChar())
                else -> sb.append("\\x").append(unsigned.toString(16).padStart(2, '0'))
            }
        }
        sb.append("'")
        return sb.toString()
    }
}