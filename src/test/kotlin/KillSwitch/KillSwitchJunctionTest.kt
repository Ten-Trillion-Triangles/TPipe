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
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.Pipeline
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlinx.coroutines.delay

/**
 * Tests KillSwitch enforcement at the Junction level.
 *
 * Junction accumulates tokens from bound participant pipelines via getPipelinesFromInterface().
 * These tests verify that KillSwitch trips when accumulated tokens exceed configured limits.
 */
class KillSwitchJunctionTest
{
    /**
     * A P2PInterface test double that wraps a Pipeline with controllable token usage.
     * This allows us to simulate token consumption without making real LLM calls.
     */
    private class TokenTrackingP2PInterface(
        private val name: String,
        private var inputTokens: Int = 0,
        private var outputTokens: Int = 0
    ) : P2PInterface
    {
        private val pipeline = Pipeline()
        var requestCount = 0
            private set

        override var killSwitch: KillSwitch? = null

        init
        {
            pipeline.pipelineName = name
            pipeline.inputTokensSpent = inputTokens
            pipeline.outputTokensSpent = outputTokens
        }

        fun setTokenUsage(input: Int, output: Int)
        {
            inputTokens = input
            outputTokens = output
            pipeline.inputTokensSpent = input
            pipeline.outputTokensSpent = output
        }

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
            // Update pipeline with current token values before Junction reads them
            pipeline.inputTokensSpent = inputTokens
            pipeline.outputTokensSpent = outputTokens
            return listOf(pipeline)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
        {
            requestCount++
            return P2PResponse(
                output = MultimodalContent(text = """{"continueDiscussion":false,"selectedParticipants":[],"finalDecision":"test","nextRoundPrompt":"","notes":""}""")
            )
        }

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            requestCount++
            return MultimodalContent(text = """{"continueDiscussion":false,"selectedParticipants":[],"finalDecision":"test","nextRoundPrompt":"","notes":""}""")
        }
    }

    private fun createModeratorResponse(
        continueDiscussion: Boolean = false,
        finalDecision: String = "test"
    ): String
    {
        return """{"continueDiscussion":$continueDiscussion,"selectedParticipants":[],"finalDecision":"$finalDecision","nextRoundPrompt":"","notes":""}"""
    }

    private fun createParticipantResponse(
        opinion: String = "Approve"
    ): String
    {
        return """{"participantName":"worker","roundNumber":1,"opinion":"$opinion","vote":"$opinion","confidence":1.0,"reasoning":"test"}"""
    }

    // Test 1: Junction checkKillSwitch throws when input exceeds limit
    @Test
    fun junctionCheckKillSwitchThrowsOnInputExceeded() = runBlocking<Unit> {
        // Moderator consumes 150 tokens (exceeds limit of 100)
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 150, outputTokens = 10)
        // Participant consumes 10 tokens
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 10, outputTokens = 5)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            junction.execute(MultimodalContent(text = "test"))
        }
        assertEquals("input_exceeded", ex.context.reason)
        assertTrue(ex.context.inputTokensSpent >= 100)
    }

    // Test 2: Junction checkKillSwitch throws when output exceeds limit
    @Test
    fun junctionCheckKillSwitchThrowsOnOutputExceeded() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 50, outputTokens = 150)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 10, outputTokens = 10)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            junction.execute(MultimodalContent(text = "test"))
        }
        assertEquals("output_exceeded", ex.context.reason)
        assertTrue(ex.context.outputTokensSpent >= 100)
    }

    // Test 3: Junction does not trip when under limit
    @Test
    fun junctionNoTripWhenUnderLimit() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 50, outputTokens = 50)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 10, outputTokens = 10)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 1000, outputTokenLimit = 1000)

        // Should NOT throw
        val result = junction.execute(MultimodalContent(text = "test"))
        assertNotNull(result)
    }

    // Test 4: Junction with null killSwitch does not check
    @Test
    fun junctionNullKillSwitchDoesNotCheck() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = null

        // Should NOT throw even with high tokens
        val result = junction.execute(MultimodalContent(text = "test"))
        assertNotNull(result)
    }

    // Test 5: Junction exact boundary does not trip (strictly greater than)
    @Test
    fun junctionExactBoundaryDoesNotTrip() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 100, outputTokens = 50)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 0, outputTokens = 0)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 100)

        // 100 > 100 is false, should NOT throw
        val result = junction.execute(MultimodalContent(text = "test"))
        assertNotNull(result)
    }

    // Test 6: Accumulated tokens from moderator + participants exceed limit
    @Test
    fun junctionAccumulatedTokensTripAfterParticipant() = runBlocking<Unit> {
        // Moderator consumes 50 tokens (under limit)
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 50, outputTokens = 10)
        // Participant consumes 80 tokens (50 + 80 = 130 > 100 limit)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 80, outputTokens = 10)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            junction.execute(MultimodalContent(text = "test"))
        }
        assertEquals("input_exceeded", ex.context.reason)
        assertTrue(ex.context.accumulatedInputTokens >= 100)
    }

    // Test 7: Junction context p2pInterface is the junction
    @Test
    fun junctionContextP2pInterfaceIsJunction() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 150, outputTokens = 10)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 10, outputTokens = 5)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            junction.execute(MultimodalContent(text = "test"))
        }
        assertSame(junction, ex.context.p2pInterface)
    }

    // Test 9: Input limit exceeded takes priority (checked first)
    @Test
    fun junctionInputLimitCheckedBeforeOutput() = runBlocking<Unit> {
        val mod = TokenTrackingP2PInterface("mod", inputTokens = 200, outputTokens = 200)
        val participant = TokenTrackingP2PInterface("worker", inputTokens = 0, outputTokens = 0)

        val junction = Junction()
        junction.setModerator("mod", mod)
        junction.addParticipant("worker", participant)
        junction.setRounds(1)
        junction.setVotingThreshold(0.5)
        junction.killSwitch = KillSwitch(inputTokenLimit = 100, outputTokenLimit = 100)

        val ex = assertFailsWith<KillSwitchException> {
            junction.execute(MultimodalContent(text = "test"))
        }
        // Junction checks input first, so if input exceeds limit, it returns "input_exceeded" without checking output
        assertEquals("input_exceeded", ex.context.reason)
    }

    // Helper exception class
    private class SpecialTestException(msg: String) : RuntimeException(msg)
}