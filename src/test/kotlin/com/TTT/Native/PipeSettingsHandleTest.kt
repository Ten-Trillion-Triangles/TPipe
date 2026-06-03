package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD tests for PipeSettingsHandle — the builder for Pipe execution settings.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * full ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class PipeSettingsHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val settings = PipeSettingsHandle.create()
        assertNotNull(settings, "create() should return a non-null PipeSettingsHandle")
    }

    @Test
    fun testTypeDiscriminator() {
        // PIPE_SETTINGS discriminator must match HandleTypes.PIPE_SETTINGS (=14)
        assertEquals(14, HandleTypes.PIPE_SETTINGS, "HandleTypes.PIPE_SETTINGS should be 14")
    }

    //==========================================================================
    // Setter Tests — verify each chainable setter mutates its field
    //==========================================================================

    @Test
    fun testSetModel() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
        assertEquals("anthropic.claude-3-5-sonnet-20240620-v1:0", settings.model)
        assertSame(settings, result, "setModel should return this for chaining")
    }

    @Test
    fun testSetTemperature() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setTemperature(0.42f)
        assertEquals(0.42f, settings.temperature)
        assertSame(settings, result, "setTemperature should return this for chaining")
    }

    @Test
    fun testSetMaxTokens() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setMaxTokens(8192)
        assertEquals(8192, settings.maxTokens)
        assertSame(settings, result, "setMaxTokens should return this for chaining")
    }

    @Test
    fun testSetTimeout() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setTimeout(120000)
        assertEquals(120000, settings.timeoutMs)
        assertSame(settings, result, "setTimeout should return this for chaining")
    }

    @Test
    fun testSetProvider() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setProvider("OLLAMA")
        assertEquals("OLLAMA", settings.providerName)
        assertSame(settings, result, "setProvider should return this for chaining")
    }

    @Test
    fun testSetSystemPrompt() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setSystemPrompt("You are a helpful assistant.")
        assertEquals("You are a helpful assistant.", settings.systemPrompt)
        assertSame(settings, result, "setSystemPrompt should return this for chaining")
    }

    @Test
    fun testSetJsonOutput() {
        val settings = PipeSettingsHandle.create()
        val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""
        val result = settings.setJsonOutput(schema)
        assertEquals(schema, settings.jsonOutput)
        assertSame(settings, result, "setJsonOutput should return this for chaining")
    }

    @Test
    fun testSetReasoning() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setReasoning(2048)
        assertEquals(2048, settings.reasoning)
        assertSame(settings, result, "setReasoning should return this for chaining")
    }

    @Test
    fun testSetRepetitionPenalty() {
        val settings = PipeSettingsHandle.create()
        val result = settings.setRepetitionPenalty(1.15f)
        assertEquals(1.15f, settings.repetitionPenalty)
        assertSame(settings, result, "setRepetitionPenalty should return this for chaining")
    }

    //==========================================================================
    // Builder Chaining — all setters return same instance
    //==========================================================================

    @Test
    fun testBuilderChaining() {
        val settings = PipeSettingsHandle.create()
            .setModel("claude-3-7-sonnet")
            .setTemperature(0.5f)
            .setMaxTokens(2048)
            .setTimeout(30000)
            .setProvider("BEDROCK")
            .setReasoning(1024)
            .setSystemPrompt("prompt")
            .setJsonOutput("{}")
            .setRepetitionPenalty(1.1f)
        assertNotNull(settings)
        assertEquals("claude-3-7-sonnet", settings.model)
        assertEquals(0.5f, settings.temperature)
        assertEquals(2048, settings.maxTokens)
        assertEquals(30000, settings.timeoutMs)
        assertEquals("BEDROCK", settings.providerName)
        assertEquals(1024, settings.reasoning)
        assertEquals("prompt", settings.systemPrompt)
        assertEquals("{}", settings.jsonOutput)
        assertEquals(1.1f, settings.repetitionPenalty)
    }

    //==========================================================================
    // Reference Counting
    //==========================================================================

    @Test
    fun testRefCounting() {
        val settings = PipeSettingsHandle.create()
        val handle = HandleRegistry.allocate(HandleTypes.PIPE_SETTINGS, settings)
        assertTrue(handle >= 0, "allocate should return non-negative handle")
        assertEquals(1, HandleRegistry.getRefCount(handle), "new handle should have refCount=1")
        assertEquals(0, HandleRegistry.addRef(handle), "addRef should succeed")
        assertEquals(2, HandleRegistry.getRefCount(handle), "refCount should be 2 after addRef")
        // cleanup
        HandleRegistry.release(handle)
        HandleRegistry.release(handle)
    }
}
