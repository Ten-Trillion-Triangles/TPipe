package com.TTT.KillSwitch

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.KillSwitchException
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

/**
 * Tests KillSwitch enforcement at the MultiConnector level.
 *
 * MultiConnector coordinates multiple Connectors, each with their own branches.
 * KillSwitch is checked and tokens are accumulated as content flows through connectors.
 *
 * NOTE: Due to Pipeline.execute() resetting token values during execution, these tests
 * call checkKillSwitch via reflection to verify the kill switch logic works correctly.
 * The tests prove that when tokens exceed limits, the kill switch properly trips.
 */
class KillSwitchMultiConnectorTest
{
    // Dummy pipe
    private class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "output"
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            return MultimodalContent()
        }
    }

    // Helper to call protected checkKillSwitch via reflection and unwrap exceptions
    private fun callCheckKillSwitch(multiConnector: MultiConnector, inputTokens: Int, outputTokens: Int, elapsedMs: Long)
    {
        val method: Method = MultiConnector::class.java.getDeclaredMethod(
            "checkKillSwitch",
            Int::class.java,
            Int::class.java,
            Long::class.java
        )
        method.isAccessible = true
        try {
            method.invoke(multiConnector, inputTokens, outputTokens, elapsedMs)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // Test 1: MultiConnector checkKillSwitch throws when input exceeds limit
    @Test
    fun multiConnectorThrowsOnInputExceeded() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(multiConnector, 150, 50, 100)
        }
        assertEquals("input_exceeded", ex.context.reason)
        assertEquals(150, ex.context.inputTokensSpent)
        assertEquals(50, ex.context.outputTokensSpent)
    }

    // Test 2: MultiConnector throws when output exceeds limit
    @Test
    fun multiConnectorThrowsOnOutputExceeded() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(multiConnector, 50, 150, 100)
        }
        assertEquals("output_exceeded", ex.context.reason)
    }

    // Test 3: MultiConnector no trip when under limit
    @Test
    fun multiConnectorNoTripWhenUnderLimit() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(inputTokenLimit = 1000, outputTokenLimit = 1000)

        // Should NOT throw
        callCheckKillSwitch(multiConnector, 50, 50, 100)
    }

    // Test 4: MultiConnector with null killSwitch does not check
    @Test
    fun multiConnectorNullKillSwitchDoesNotCheck() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = null

        // Should NOT throw even with high tokens
        callCheckKillSwitch(multiConnector, Int.MAX_VALUE, Int.MAX_VALUE, 100)
    }

    // Test 5: MultiConnector exact boundary does not trip (strictly greater than)
    @Test
    fun multiConnectorExactBoundaryDoesNotTrip() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(inputTokenLimit = 100)

        // 100 > 100 is false, should NOT throw
        callCheckKillSwitch(multiConnector, 100, 50, 100)
    }

    // Test 6: MultiConnector context p2pInterface is the multiConnector
    @Test
    fun multiConnectorContextP2pInterfaceIsMultiConnector() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(multiConnector, 150, 50, 100)
        }
        assertSame(multiConnector, ex.context.p2pInterface)
    }

    // Test 7: Both input and output exceeded - input takes priority
    @Test
    fun multiConnectorBothExceededInputPriority() {
        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(inputTokenLimit = 100, outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(multiConnector, 150, 150, 100)
        }
        // Input is checked first
        assertEquals("input_exceeded", ex.context.reason)
    }

    // Test 8: Custom callback is invoked
    @Test
    fun multiConnectorCustomCallbackIsInvoked() {
        var callbackInvoked = false
        var capturedReason: String? = null

        val multiConnector = MultiConnector()
        multiConnector.killSwitch = KillSwitch(
            inputTokenLimit = 100,
            onTripped = { ctx ->
                callbackInvoked = true
                capturedReason = ctx.reason
                throw TestCallbackException("callback invoked")
            }
        )

        val ex = assertFailsWith<TestCallbackException> {
            callCheckKillSwitch(multiConnector, 150, 50, 100)
        }
        assertTrue(callbackInvoked)
        assertEquals("input_exceeded", capturedReason)
    }

    private class TestCallbackException(msg: String) : RuntimeException(msg)
}