package com.TTT.Native

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.Pipe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for [PipeHandle] and the Pipe C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + PipeHandle contract,
 * which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 *
 * Cycle 4 — Pipe prompt + sampling surface (10 new C symbols):
 *   - TPipe_Pipe_setSystemPrompt
 *   - TPipe_Pipe_getSystemPrompt
 *   - TPipe_Pipe_setUserPrompt
 *   - TPipe_Pipe_setMiddlePrompt
 *   - TPipe_Pipe_setFooterPrompt
 *   - TPipe_Pipe_setTopP
 *   - TPipe_Pipe_setTopK
 *   - TPipe_Pipe_setMaxTokens
 *   - TPipe_Pipe_setSeed
 *   - TPipe_Pipe_setStopSequences
 */
class PipeHandleTest
{

    private lateinit var ph: PipeHandle

    @BeforeTest
    fun setUp()
    {
        NativeBridge.setState(EnumMappings.LibraryState.READY.cValue)
        HandleRegistry.closeAll()
        NativeBridge.init()
        // Create a fresh DummyPipe + PipeSettingsHandle for each test.
        val pipe: Pipe = DummyPipe()
        val settings = PipeSettingsHandle.create()
        ph = PipeHandle(pipe, settings)
    }

    @AfterTest
    fun tearDown()
    {
        HandleRegistry.closeAll()
    }

    /**
     * Helper: build the (handleId) form used by the @JvmStatic bridge layer
     * so we exercise the same code path as the C ABI.
     */
    private fun registerAndGetHandleId(): Long =
        HandleRegistry.allocate(HandleTypes.PIPE, ph)

    //==========================================================================
    // Cycle 4 — Pipe prompt + sampling surface
    //==========================================================================

    @Test
    fun testTPipe_Pipe_setSystemPrompt_storesText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetSystemPrompt(h, "You are a helpful assistant.")
        assertEquals(0, rc, "setSystemPrompt should return 0 on success")
        // Roundtrip via the JVM Pipe accessor
        assertEquals("You are a helpful assistant.", ph.pipe.getSystemPromptText())
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setSystemPrompt_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetSystemPrompt(0L, "anything")
        assertEquals(-0x03, rc, "null handle should return INVALID_HANDLE")
    }

    @Test
    fun testTPipe_Pipe_setSystemPrompt_rejectsNullText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetSystemPrompt(h, null)
        // Null text is treated as empty (graceful default), not an error.
        assertEquals(0, rc, "null text should be accepted as empty prompt")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_getSystemPrompt_returnsStoredText()
    {
        val h = registerAndGetHandleId()
        NativeBridge.pipeSetSystemPrompt(h, "Ground control to Major Tom.")
        val buf = ByteArray(256)
        val n = NativeBridge.pipeGetSystemPrompt(h, buf, 0, 255)
        assertTrue(n > 0, "getSystemPrompt should return positive byte count, got $n")
        val text = String(buf, 0, n, Charsets.UTF_8)
        assertEquals("Ground control to Major Tom.", text)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_getSystemPrompt_rejectsNullHandle()
    {
        val buf = ByteArray(64)
        val n = NativeBridge.pipeGetSystemPrompt(0L, buf, 0, 63)
        assertEquals(-0x03, n, "null handle should return INVALID_HANDLE")
    }

    @Test
    fun testTPipe_Pipe_getSystemPrompt_rejectsNullBuffer()
    {
        val h = registerAndGetHandleId()
        NativeBridge.pipeSetSystemPrompt(h, "some text")
        val n = NativeBridge.pipeGetSystemPrompt(h, null, 0, 63)
        assertEquals(-0x05, n, "null buffer should return NULL_POINTER")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_getSystemPrompt_truncatesToBufferBounds()
    {
        val h = registerAndGetHandleId()
        NativeBridge.pipeSetSystemPrompt(h, "abcdefghij")  // 10 chars
        val buf = ByteArray(5)  // too small
        val n = NativeBridge.pipeGetSystemPrompt(h, buf, 0, 4)
        // n should be <= 4 (truncated)
        assertTrue(n <= 4, "truncated read should respect maxLen, got $n")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setUserPrompt_storesText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetUserPrompt(h, "USER: hello")
        assertEquals(0, rc, "setUserPrompt should return 0 on success")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setUserPrompt_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetUserPrompt(0L, "anything")
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setMiddlePrompt_storesText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetMiddlePrompt(h, "Middle injection text")
        assertEquals(0, rc, "setMiddlePrompt should return 0 on success")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setMiddlePrompt_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetMiddlePrompt(0L, "anything")
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setFooterPrompt_storesText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetFooterPrompt(h, "Footer injection text")
        assertEquals(0, rc, "setFooterPrompt should return 0 on success")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setFooterPrompt_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetFooterPrompt(0L, "anything")
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setTopP_storesDoubleFromBits()
    {
        val h = registerAndGetHandleId()
        val bits = java.lang.Double.doubleToRawLongBits(0.92)
        val rc = NativeBridge.pipeSetTopP(h, bits)
        assertEquals(0, rc, "setTopP should return 0 on success")
        // Verify the field on the JVM pipe was updated.
        val f = Pipe::class.java.getDeclaredField("topP").apply { isAccessible = true }
        assertEquals(0.92, f.getDouble(ph.pipe), 1e-9)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setTopP_rejectsNullHandle()
    {
        val bits = java.lang.Double.doubleToRawLongBits(0.5)
        val rc = NativeBridge.pipeSetTopP(0L, bits)
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setTopK_storesInt()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetTopK(h, 42)
        assertEquals(0, rc, "setTopK should return 0 on success")
        val f = Pipe::class.java.getDeclaredField("topK").apply { isAccessible = true }
        assertEquals(42, f.getInt(ph.pipe))
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setTopK_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetTopK(0L, 50)
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setMaxTokens_storesInt()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetMaxTokens(h, 2048)
        assertEquals(0, rc, "setMaxTokens should return 0 on success")
        val f = Pipe::class.java.getDeclaredField("maxTokens").apply { isAccessible = true }
        assertEquals(2048, f.getInt(ph.pipe))
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setMaxTokens_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetMaxTokens(0L, 512)
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setSeed_storesNonNullSeed()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetSeed(h, 12345L)
        assertEquals(0, rc, "setSeed should return 0 on success")
        val f = Pipe::class.java.getDeclaredField("seed").apply { isAccessible = true }
        val stored = f.get(ph.pipe) as Int?
        assertNotNull(stored, "seed should be non-null after setSeed(12345)")
        assertEquals(12345, stored)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setSeed_clearsSeedOnSentinel()
    {
        val h = registerAndGetHandleId()
        // First set a non-null seed
        NativeBridge.pipeSetSeed(h, 999L)
        // Then clear it via the documented sentinel (Long.MIN_VALUE)
        val rc = NativeBridge.pipeSetSeed(h, Long.MIN_VALUE)
        assertEquals(0, rc)
        val f = Pipe::class.java.getDeclaredField("seed").apply { isAccessible = true }
        val stored = f.get(ph.pipe) as Int?
        assertEquals(null, stored, "sentinel value should clear the seed")
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setSeed_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetSeed(0L, 7L)
        assertEquals(-0x03, rc)
    }

    @Test
    fun testTPipe_Pipe_setStopSequences_storesListFromNewlineText()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetStopSequences(h, "STOP1\nSTOP2\nSTOP3")
        assertEquals(0, rc, "setStopSequences should return 0 on success")
        val f = Pipe::class.java.getDeclaredField("stopSequences").apply { isAccessible = true }
        val seqs = f.get(ph.pipe) as List<String>
        assertEquals(listOf("STOP1", "STOP2", "STOP3"), seqs)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setStopSequences_handlesSingleValue()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetStopSequences(h, "ONLY")
        assertEquals(0, rc)
        val f = Pipe::class.java.getDeclaredField("stopSequences").apply { isAccessible = true }
        val seqs = f.get(ph.pipe) as List<String>
        assertEquals(listOf("ONLY"), seqs)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setStopSequences_handlesEmptyString()
    {
        val h = registerAndGetHandleId()
        val rc = NativeBridge.pipeSetStopSequences(h, "")
        assertEquals(0, rc, "empty string should clear stop sequences")
        val f = Pipe::class.java.getDeclaredField("stopSequences").apply { isAccessible = true }
        val seqs = f.get(ph.pipe) as List<String>
        assertEquals(emptyList(), seqs)
        HandleRegistry.release(h)
    }

    @Test
    fun testTPipe_Pipe_setStopSequences_rejectsNullHandle()
    {
        val rc = NativeBridge.pipeSetStopSequences(0L, "X")
        assertEquals(-0x03, rc)
    }
}
