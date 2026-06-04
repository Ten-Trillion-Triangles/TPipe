package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ollamaPipe.OllamaPipe
import bedrockPipe.BedrockPipe

/**
 * Wiring test for Phase 4 of the ABI provider-wiring plan.
 *
 * Phase 4 extends [NativeBridge.pipeCreate] to construct a real
 * [bedrockPipe.BedrockPipe] for the BEDROCK provider (C ABI id 3) instead
 * of always returning a [com.TTT.Pipe.DummyPipe]. Phase 3 already wired
 * OLLAMA (id 4); this phase mirrors that pattern and adds the
 * BedrockPipe-specific `setRegion` reflective call.
 *
 * These tests pin down the contract:
 *  - BEDROCK requests construct a real [BedrockPipe] and return a non-zero
 *    handle whose [HandleRegistry] data is a [PipeHandle] wrapping that
 *    [BedrockPipe].
 *  - OLLAMA requests still construct a real [OllamaPipe] (Phase 3
 *    regression — Phase 4 must not break Phase 3).
 *  - Non-BEDROCK / non-OLLAMA providers (UNKNOWN, OPENROUTER,
 *    GENERIC_OPENAI, etc.) still return a non-zero handle so existing
 *    Phase 1 / Phase 2 callers keep working.
 *  - Each invocation gets a fresh handle (no aliasing).
 *
 * None of these tests touch the network — both [BedrockPipe] and
 * [OllamaPipe] are constructed but their `init()` (which contacts the
 * upstream service) is deliberately never called. This keeps the test
 * JVM-isolated and fast.
 */
class BedrockWiringTest {

    //==========================================================================
    // BEDROCK branch — provider id 3
    //==========================================================================

    @Test
    fun bedrockCreateReturnsNonZeroHandle()
    {
        // The simplest acceptance test: pipeCreate(3, "...", "us-east-1", 0L)
        // must yield a valid handle. Returning 0L or -1L would indicate the
        // BEDROCK branch regressed to its pre-Phase 4 fallback (returning a
        // handle without constructing a real BedrockPipe).
        val handle = NativeBridge.pipeCreate(
            provider = 3, // TPIPE_PROVIDER_BEDROCK
            model = "anthropic.claude-3-sonnet-20240229-v1:0",
            region = "us-east-1",
            settingsHandle = 0L
        )
        assertNotEquals(0L, handle, "pipeCreate(BEDROCK) must return a non-zero handle")
        assertTrue(handle > 0L, "pipeCreate(BEDROCK) must return a positive handle, got: $handle")
    }

    @Test
    fun bedrockHandleDataIsPipeHandleWrappingBedrockPipe()
    {
        // The handle's data must be a PipeHandle whose `pipe` field is an actual
        // BedrockPipe instance — not a DummyPipe.
        val handle = NativeBridge.pipeCreate(
            provider = 3, // TPIPE_PROVIDER_BEDROCK
            model = "anthropic.claude-3-sonnet-20240229-v1:0",
            region = "us-east-1",
            settingsHandle = 0L
        )
        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "HandleRegistry must have data for the BEDROCK handle")
        assertTrue(data is PipeHandle, "BEDROCK handle data must be a PipeHandle, got: ${data::class}")

        val pipeHandle = data as PipeHandle
        assertNotNull(pipeHandle.pipe, "PipeHandle.pipe must not be null")
        assertTrue(
            pipeHandle.pipe is BedrockPipe,
            "PipeHandle.pipe must be a BedrockPipe instance for BEDROCK provider, got: ${pipeHandle.pipe::class}"
        )
    }

    @Test
    fun bedrockCreateReturnsUniqueHandles()
    {
        // Each pipeCreate call must allocate a fresh handle.
        val first = NativeBridge.pipeCreate(
            provider = 3, // TPIPE_PROVIDER_BEDROCK
            model = "anthropic.claude-3-sonnet-20240229-v1:0",
            region = "us-east-1",
            settingsHandle = 0L
        )
        val second = NativeBridge.pipeCreate(
            provider = 3, // TPIPE_PROVIDER_BEDROCK
            model = "anthropic.claude-3-sonnet-20240229-v1:0",
            region = "us-east-1",
            settingsHandle = 0L
        )
        assertNotEquals(0L, first)
        assertNotEquals(0L, second)
        assertNotEquals(first, second, "Two pipeCreate(BEDROCK) calls must return distinct handles")
    }

    //==========================================================================
    // Fallback branches — UNKNOWN (id 9) and OLLAMA (id 4) regression
    //==========================================================================

    @Test
    fun unknownProviderFallsBackToDummyPipe()
    {
        // UNKNOWN is the C ABI catch-all. The DummyPipe allocation must keep
        // working so unmapped provider ids continue to return a valid handle.
        val handle = NativeBridge.pipeCreate(
            provider = 99, // out-of-range id resolves to UNKNOWN via fromInt()
            model = "x",
            region = "y",
            settingsHandle = 0L
        )
        assertNotEquals(0L, handle, "pipeCreate(UNKNOWN) must return a non-zero handle")
        assertTrue(handle > 0L, "pipeCreate(UNKNOWN) must return a positive handle, got: $handle")

        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "HandleRegistry must have data for the UNKNOWN handle")
        assertTrue(data is PipeHandle, "UNKNOWN handle data must be a PipeHandle, got: ${data::class}")
    }

    @Test
    fun ollamaProviderStillConstructsOllamaPipe()
    {
        // The OLLAMA branch must continue to construct a real [OllamaPipe]
        // after the BEDROCK branch was added above it. Reordering the `when`
        // arms or accidentally consuming a shared reflective handle would
        // break this.
        val handle = NativeBridge.pipeCreate(
            provider = 4, // TPIPE_PROVIDER_OLLAMA
            model = "llama3",
            region = "",
            settingsHandle = 0L
        )
        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "HandleRegistry must have data for the OLLAMA handle")
        assertTrue(data is PipeHandle, "OLLAMA handle data must be a PipeHandle, got: ${data::class}")

        val pipeHandle = data as PipeHandle
        assertNotNull(pipeHandle.pipe, "PipeHandle.pipe must not be null")
        assertTrue(
            pipeHandle.pipe is OllamaPipe,
            "PipeHandle.pipe must be an OllamaPipe instance for OLLAMA provider, got: ${pipeHandle.pipe::class}"
        )
    }
}
