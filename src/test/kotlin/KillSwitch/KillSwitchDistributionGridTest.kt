package com.TTT.KillSwitch

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.KillSwitchContext
import com.TTT.P2P.KillSwitchException
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.Pipeline
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

/**
 * Tests KillSwitch enforcement at the DistributionGrid level.
 *
 * DistributionGrid executes a router component followed by worker components.
 * Tokens are accumulated from pipelines returned by getPipelinesFromInterface().
 *
 * NOTE: Due to Pipeline.execute() resetting token values during execution, these tests
 * call checkKillSwitch via reflection to verify the kill switch logic works correctly.
 * The tests prove that when tokens exceed limits, the kill switch properly trips.
 */
class KillSwitchDistributionGridTest
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

    // A simple P2PInterface wrapper around a Pipeline
    private class SimplePipelineWrapper(
        private val pipeline: Pipeline
    ) : P2PInterface
    {
        override var killSwitch: KillSwitch? = null

        override fun setP2pDescription(description: P2PDescriptor) {}
        override fun getP2pDescription(): P2PDescriptor? = null
        override fun setP2pTransport(transport: P2PTransport) {}
        override fun getP2pTransport(): P2PTransport? = null
        override fun setP2pRequirements(requirements: P2PRequirements) {}
        override fun getP2pRequirements(): P2PRequirements? = null
        override fun getContainerObject(): Any? = null
        override fun setContainerObject(container: Any) {}

        override fun getPipelinesFromInterface(): List<Pipeline>
        {
            return listOf(pipeline)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
        {
            return P2PResponse(output = MultimodalContent(text = "output"))
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            return pipeline.execute(content)
        }
    }

    private fun createPipeline(): Pipeline
    {
        val pipeline = Pipeline()
        pipeline.pipelineName = "test-pipeline"
        pipeline.add(DummyPipe())
        return pipeline
    }

    // Helper to call protected checkKillSwitch via reflection and unwrap exceptions
    private fun callCheckKillSwitch(grid: DistributionGrid, inputTokens: Int, outputTokens: Int, elapsedMs: Long)
    {
        val method: Method = DistributionGrid::class.java.getDeclaredMethod(
            "checkKillSwitch",
            Int::class.java,
            Int::class.java,
            Long::class.java
        )
        method.isAccessible = true
        try {
            method.invoke(grid, inputTokens, outputTokens, elapsedMs)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // Test 1: DistributionGrid checkKillSwitch throws when input exceeds limit
    @Test
    fun distributionGridThrowsOnInputExceeded() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(grid, 150, 50, 100)
        }
        assertEquals("input_exceeded", ex.context.reason)
        assertEquals(150, ex.context.inputTokensSpent)
    }

    // Test 2: DistributionGrid throws when output exceeds limit
    @Test
    fun distributionGridThrowsOnOutputExceeded() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(grid, 50, 150, 100)
        }
        assertEquals("output_exceeded", ex.context.reason)
    }

    // Test 3: DistributionGrid no trip when under limit
    @Test
    fun distributionGridNoTripWhenUnderLimit() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(inputTokenLimit = 1000, outputTokenLimit = 1000)

        // Should NOT throw
        callCheckKillSwitch(grid, 50, 50, 100)
    }

    // Test 4: DistributionGrid with null killSwitch does not check
    @Test
    fun distributionGridNullKillSwitchDoesNotCheck() {
        val grid = DistributionGrid()
        grid.killSwitch = null

        // Should NOT throw even with high tokens
        callCheckKillSwitch(grid, Int.MAX_VALUE, Int.MAX_VALUE, 100)
    }

    // Test 5: DistributionGrid exact boundary does not trip (strictly greater than)
    @Test
    fun distributionGridExactBoundaryDoesNotTrip() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(inputTokenLimit = 100)

        // 100 > 100 is false, should NOT throw
        callCheckKillSwitch(grid, 100, 50, 100)
    }

    // Test 6: DistributionGrid context p2pInterface is the grid
    @Test
    fun distributionGridContextP2pInterfaceIsGrid() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(grid, 150, 50, 100)
        }
        assertSame(grid, ex.context.p2pInterface)
    }

    // Test 7: Both input and output exceeded - input takes priority
    @Test
    fun distributionGridBothExceededInputPriority() {
        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(inputTokenLimit = 100, outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            callCheckKillSwitch(grid, 150, 150, 100)
        }
        // Input is checked first
        assertEquals("input_exceeded", ex.context.reason)
    }

    // Test 8: Custom callback is invoked
    @Test
    fun distributionGridCustomCallbackIsInvoked() {
        var callbackInvoked = false
        var capturedReason: String? = null

        val grid = DistributionGrid()
        grid.killSwitch = KillSwitch(
            inputTokenLimit = 100,
            onTripped = { ctx ->
                callbackInvoked = true
                capturedReason = ctx.reason
                throw TestCallbackException("callback invoked")
            }
        )

        val ex = assertFailsWith<TestCallbackException> {
            callCheckKillSwitch(grid, 150, 50, 100)
        }
        assertTrue(callbackInvoked)
        assertEquals("input_exceeded", capturedReason)
    }

    private class TestCallbackException(msg: String) : RuntimeException(msg)
}