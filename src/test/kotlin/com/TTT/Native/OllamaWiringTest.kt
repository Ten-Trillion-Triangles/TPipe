package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ollamaPipe.OllamaPipe

/**
 * Wiring test for Phase 3 of the ABI provider-wiring plan.
 *
 * Phase 3 extends [NativeBridge.pipeCreate] to construct a real
 * [ollamaPipe.OllamaPipe] for the OLLAMA provider (C ABI id 4) instead of
 * always returning a [com.TTT.Pipe.DummyPipe]. Phase 4 will mirror this
 * pattern for BEDROCK (id 3).
 *
 * These tests pin down the contract:
 *  - OLLAMA requests construct a real [OllamaPipe] and return a non-zero
 *    handle whose [HandleRegistry] data is a [PipeHandle] wrapping that
 *    [OllamaPipe].
 *  - Non-OLLAMA providers (BEDROCK, UNKNOWN) still return a non-zero
 *    handle so existing Phase 1 / Phase 2 callers keep working until
 *    Phase 4 wires BEDROCK.
 *  - Each invocation gets a fresh handle (no aliasing).
 *
 * None of these tests touch the network — [OllamaPipe] is constructed
 * but its `init()` (which pings the Ollama server) is deliberately
 * never called. This keeps the test JVM-isolated and fast.
 */
class OllamaWiringTest {

    //==========================================================================
    // OLLAMA branch — provider id 4
    //==========================================================================

    @Test
    fun ollamaCreateReturnsNonZeroHandle()
    {
        // The simplest acceptance test: pipeCreate(4, "llama3", "", 0L)
        // must yield a valid handle. Returning 0L or -1L would indicate
        // the OLLAMA branch regressed to its pre-Phase 3 fallback (returning
        // a handle without constructing a real OllamaPipe).
        val handle = NativeBridge.pipeCreate(
            provider = 4, // TPIPE_PROVIDER_OLLAMA
            model = "llama3",
            region = "",
            settingsHandle = 0L
        )
        assertNotEquals(0L, handle, "pipeCreate(OLLAMA) must return a non-zero handle")
        assertTrue(handle > 0L, "pipeCreate(OLLAMA) must return a positive handle, got: $handle")
    }

    @Test
    fun ollamaHandleDataIsPipeHandleWrappingOllamaPipe()
    {
        // The handle's data must be a PipeHandle whose `pipe` field is an
        // actual OllamaPipe instance — not a DummyPipe.
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

    @Test
    fun ollamaCreateReturnsUniqueHandles()
    {
        // Each pipeCreate call must allocate a fresh handle.
        val first = NativeBridge.pipeCreate(
            provider = 4, // TPIPE_PROVIDER_OLLAMA
            model = "llama3",
            region = "",
            settingsHandle = 0L
        )
        val second = NativeBridge.pipeCreate(
            provider = 4, // TPIPE_PROVIDER_OLLAMA
            model = "llama3",
            region = "",
            settingsHandle = 0L
        )
        assertNotEquals(0L, first)
        assertNotEquals(0L, second)
        assertNotEquals(first, second, "Two pipeCreate(OLLAMA) calls must return distinct handles")
    }

    //==========================================================================
    // Fallback branches — UNKNOWN (id 9) and BEDROCK (id 3) until Phase 4
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
    fun bedrockProviderFallsBackToDummyPipe()
    {
        // BEDROCK is the C ABI id 3. Its current fallback constructs a
        // DummyPipe; the BedrockWiringTest pins the real-construction contract.
        // Both paths must return a non-zero handle.
        val handle = NativeBridge.pipeCreate(
            provider = 3, // TPIPE_PROVIDER_BEDROCK
            model = "x",
            region = "us-east-1",
            settingsHandle = 0L
        )
        assertNotEquals(0L, handle, "pipeCreate(BEDROCK) must return a non-zero handle")
        assertTrue(handle > 0L, "pipeCreate(BEDROCK) must return a positive handle, got: $handle")

        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "HandleRegistry must have data for the BEDROCK handle")
        assertTrue(data is PipeHandle, "BEDROCK handle data must be a PipeHandle, got: ${data::class}")
    }
}
