package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for [JunctionHandle] and the Junction C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + JunctionHandle
 * contract, which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 */
class JunctionHandleTest
{

    @BeforeTest
    fun setUp()
    {
        NativeBridge.setState(EnumMappings.LibraryState.READY.cValue)
        HandleRegistry.closeAll()
        NativeBridge.init()
    }

    @AfterTest
    fun tearDown()
    {
        HandleRegistry.closeAll()
    }


    //==========================================================================
    // Cycle 3 — Configuration surface
    //==========================================================================

    @Test
    fun testJunctionSetStrategy()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetStrategy(jh, 1)  // CONVERSATIONAL
        assertEquals(0, rc)
        val s = NativeBridge.junctionGetStrategy(jh)
        assertEquals(1, s, "strategy should be 1 (CONVERSATIONAL)")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetStrategyRejectsBadOrdinal()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetStrategy(jh, 99)
        assertEquals(-0x04, rc, "bad ordinal should return INVALID_ARGUMENT")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetRounds()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetRounds(jh, 7)
        assertEquals(0, rc)
        val r = NativeBridge.junctionGetRounds(jh)
        assertEquals(7, r)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetRoundsRejectsNonPositive()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetRounds(jh, 0)
        assertEquals(-0x04, rc)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetVotingThreshold()
    {
        val jh = NativeBridge.junctionCreate()
        val bits = 0.85.toRawBits()
        val rc = NativeBridge.junctionSetVotingThreshold(jh, bits)
        assertEquals(0, rc)
        val r = NativeBridge.junctionGetVotingThreshold(jh)
        assertEquals(bits, r, "voting threshold bits should round-trip")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetVotingThresholdRejectsOutOfRange()
    {
        val jh = NativeBridge.junctionCreate()
        val bits = 1.5.toRawBits()
        val rc = NativeBridge.junctionSetVotingThreshold(jh, bits)
        assertEquals(-0x04, rc, "threshold > 1.0 should be rejected")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetMaxNestedDepth()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetMaxNestedDepth(jh, 4)
        assertEquals(0, rc)
        val d = NativeBridge.junctionGetMaxNestedDepth(jh)
        assertEquals(4, d)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetMaxNestedDepthRejectsNonPositive()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetMaxNestedDepth(jh, 0)
        assertEquals(-0x04, rc)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetWorkflowRecipe()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetWorkflowRecipe(jh, 1)  // VOTE_ACT_VERIFY_REPEAT
        assertEquals(0, rc)
        val r = NativeBridge.junctionGetWorkflowRecipe(jh)
        assertEquals(1, r)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetWorkflowRecipeRejectsBadOrdinal()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetWorkflowRecipe(jh, 99)
        assertEquals(-0x04, rc)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetMemoryPolicy()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetMemoryPolicy(jh, 4096, 512)
        assertEquals(0, rc)
        val b = NativeBridge.junctionGetMemoryPolicy(jh)
        assertEquals(4096, b)
        val combined = NativeBridge.junctionGetMemoryPolicyEx(jh)
        val outbound = (combined and 0xFFFFFFFFL).toInt()
        val summary = ((combined shr 32) and 0xFFFFFFFFL).toInt()
        assertEquals(4096, outbound)
        assertEquals(512, summary)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionSetMemoryPolicyRejectsNegative()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionSetMemoryPolicy(jh, -1, 0)
        assertEquals(-0x04, rc)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionEnableTracing()
    {
        val jh = NativeBridge.junctionCreate()
        val rc = NativeBridge.junctionEnableTracing(jh)
        assertEquals(0, rc)
        // disableTracing round-trip
        val rc2 = NativeBridge.junctionDisableTracing(jh)
        assertEquals(0, rc2)
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionGetTraceId()
    {
        val jh = NativeBridge.junctionCreate()
        val buf = ByteArray(64)
        val n = NativeBridge.junctionGetTraceId(jh, buf, 0, 64)
        assertTrue(n > 0, "getTraceId should return at least one byte")
        assertTrue(n < 64, "trace ID should fit in 64 bytes")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionGetFailureAnalysis()
    {
        val jh = NativeBridge.junctionCreate()
        val buf = ByteArray(256)
        val n = NativeBridge.junctionGetFailureAnalysis(jh, buf, 0, 256)
        // No failure analysis expected (tracing disabled), but the
        // shim should return "{}" -> 2 bytes written.
        assertEquals(2, n, "empty failure analysis should serialize as {}")
        HandleRegistry.release(jh)
    }

    @Test
    fun testJunctionConfigMethodsRejectNonJunctionHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.junctionSetStrategy(ch, 0)
        assertEquals(-0x03, rc, "setStrategy on CONTENT should return INVALID_HANDLE")
        HandleRegistry.release(ch)
    }
}
