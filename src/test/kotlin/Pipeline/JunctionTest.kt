package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for the Junction harness.
 */
class JunctionTest
{
    /**
     * Verifies that Junction accepts any [P2PInterface] participant, preserves nested container binding, and emits
     * a structured decision artifact.
     */
    @Test
    fun executesWithArbitraryP2PParticipants()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        selectedParticipants = mutableListOf(),
                        finalDecision = "Approve",
                        nextRoundPrompt = "",
                        notes = "Consensus reached."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "worker",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "worker",
                        roundNumber = 1,
                        opinion = "Approve",
                        vote = "Approve",
                        confidence = 1.0,
                        reasoning = "The proposal is sound."
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("worker", participant)
                .setRounds(1)
                .setVotingThreshold(0.5)

            val result = junction.execute(
                MultimodalContent(
                    text = "Approve the proposal."
                )
            )

            val decision = deserialize<DiscussionDecision>(result.text)
            assertNotNull(decision)
            assertEquals("Approve", decision.decision)
            assertEquals(1, decision.roundsExecuted)
            assertEquals(1, participant.requestCount)
            assertEquals(1, moderator.requestCount)
            assertSame(junction, participant.containerRef)
            assertSame(junction, moderator.containerRef)
            assertTrue(result.passPipeline)
            assertIs<DiscussionState>(result.metadata["junctionState"])
            assertIs<DiscussionDecision>(result.metadata["junctionDecision"])
        }
    }

    /**
     * Verifies that the DSL builds a Junction configured with P2P-capable moderator and participant containers.
     */
    @Test
    fun buildsJunctionFromDsl()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Proceed",
                        notes = "Moderator ends the discussion."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "Looks good."
                    )
                )
            )

            val junction = junction {
                moderator(moderator)
                participant("participant", participant)
                rounds(1)
                threshold(0.5)
            }

            val result = junction.execute(MultimodalContent(text = "Start the workflow."))
            val decision = deserialize<DiscussionDecision>(result.text)

            assertNotNull(decision)
            assertEquals("Proceed", decision.decision)
            assertEquals(1, participant.requestCount)
            assertEquals(1, moderator.requestCount)
        }
    }

    /**
     * Verifies that the simultaneous strategy dispatches every participant in the round.
     */
    @Test
    fun simultaneousStrategyDispatchesAllParticipants()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Ship",
                        notes = "Proceed after one round."
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "first",
                        roundNumber = 1,
                        opinion = "Ship",
                        vote = "Ship",
                        confidence = 1.0,
                        reasoning = "Ready."
                    )
                )
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "second",
                        roundNumber = 1,
                        opinion = "Ship",
                        vote = "Ship",
                        confidence = 1.0,
                        reasoning = "Ready."
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipants(
                    "first" to first,
                    "second" to second
                )
                .simultaneous()
                .setRounds(1)

            val result = junction.execute(MultimodalContent(text = "Approve the release."))
            val decision = deserialize<DiscussionDecision>(result.text)

            assertNotNull(decision)
            assertEquals("Ship", decision.decision)
            assertEquals(1, first.requestCount)
            assertEquals(1, second.requestCount)
            assertEquals(1, moderator.requestCount)
        }
    }

    /**
     * Verifies that the round-robin strategy preserves participant order.
     */
    @Test
    fun roundRobinStrategyPreservesParticipantOrder()
    {
        runBlocking {
            val callOrder = mutableListOf<String>()
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Proceed",
                        notes = "Round-robin completed."
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "first",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "First."
                    )
                ),
                callLog = callOrder
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "second",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "Second."
                    )
                ),
                callLog = callOrder
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("first", first)
                .addParticipant("second", second)
                .roundRobin()
                .setRounds(1)

            val result = junction.execute(MultimodalContent(text = "Proceed with ordering."))
            val decision = deserialize<DiscussionDecision>(result.text)

            assertNotNull(decision)
            assertEquals(listOf("first", "second"), callOrder)
            assertEquals(1, first.requestCount)
            assertEquals(1, second.requestCount)
        }
    }

    /**
     * Verifies that the conversational strategy can narrow the next round to moderator-selected participants.
     */
    @Test
    fun conversationalStrategyUsesModeratorSelection()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseSequence = mutableListOf(
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = true,
                            selectedParticipants = mutableListOf("second"),
                            notes = "Focus on the second participant."
                        )
                    ),
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = false,
                            finalDecision = "Proceed",
                            notes = "Finalize after the selected follow-up."
                        )
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "first",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "Initial view."
                    )
                )
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "second",
                        roundNumber = 1,
                        opinion = "Hold",
                        vote = "Hold",
                        confidence = 1.0,
                        reasoning = "Follow-up view."
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("first", first)
                .addParticipant("second", second)
                .conversational()
                .setRounds(2)

            val result = junction.execute(MultimodalContent(text = "Refine the proposal."))
            val decision = deserialize<DiscussionDecision>(result.text)

            assertNotNull(decision)
            assertEquals(2, moderator.requestCount)
            assertEquals(1, first.requestCount)
            assertEquals(2, second.requestCount)
            assertEquals("Proceed", decision.decision)
        }
    }

    /**
     * Verifies that Junction rejects direct self-reference in a participant container chain.
     */
    @Test
    fun rejectsDirectSelfReference()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Stop"
                    )
                )
            )
            moderator.containerRef = moderator

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant(
                    "participant",
                    RecordingP2PInterface(
                        name = "participant",
                        responseText = serialize(
                            ParticipantOpinion(
                                participantName = "participant",
                                opinion = "Stop",
                                vote = "Stop",
                                reasoning = "Self-reference test."
                            )
                        )
                    )
                )

            assertFailsWith<IllegalStateException> {
                junction.init()
            }
        }
    }

    /**
     * Verifies that Junction rejects indirect container cycles.
     */
    @Test
    fun rejectsIndirectContainerCycle()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Stop"
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "first",
                        opinion = "Stop",
                        vote = "Stop",
                        reasoning = "First."
                    )
                )
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "second",
                        opinion = "Stop",
                        vote = "Stop",
                        reasoning = "Second."
                    )
                )
            )

            first.containerRef = second
            second.containerRef = first

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("first", first)

            assertFailsWith<IllegalStateException> {
                junction.init()
            }
        }
    }

    /**
     * Verifies that Junction still enforces the nested depth guard when a chain is long but not cyclic.
     */
    @Test
    fun rejectsChainsThatExceedNestedDepth()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Stop"
                    )
                )
            )
            val root = RecordingP2PInterface(
                name = "root",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "root",
                        opinion = "Stop",
                        vote = "Stop",
                        reasoning = "Depth chain root."
                    )
                )
            )
            val chainNodes = (1..10).map { index ->
                RecordingP2PInterface(
                    name = "node-$index",
                    responseText = serialize(
                        ParticipantOpinion(
                            participantName = "node-$index",
                            opinion = "Stop",
                            vote = "Stop",
                            reasoning = "Depth chain node."
                        )
                    )
                )
            }

            root.containerRef = chainNodes.first()
            chainNodes.zipWithNext().forEach { (current, next) ->
                current.containerRef = next
            }

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("root", root)
                .setMaxNestedDepth(5)

            assertFailsWith<IllegalStateException> {
                junction.init()
            }
        }
    }

    /**
     * Verifies that the plan -> vote -> act -> verify -> repeat workflow executes in order and repeats until the
     * verifier approves the result.
     */
    @Test
    fun planVoteActVerifyRepeatRunsMultipleCycles()
    {
        runBlocking {
            val planner = RecordingP2PInterface(
                name = "planner",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 1,
                            participantName = "planner",
                            text = "Plan cycle one.",
                            instructions = "Plan cycle one.",
                            passed = true,
                            notes = "Initial plan."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 2,
                            participantName = "planner",
                            text = "Plan cycle two.",
                            instructions = "Plan cycle two.",
                            passed = true,
                            notes = "Refined plan."
                        )
                    )
                )
            )
            val actor = RecordingP2PInterface(
                name = "actor",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 1,
                            participantName = "actor",
                            text = "Act cycle one.",
                            instructions = "Act cycle one.",
                            passed = true,
                            notes = "Initial action."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 2,
                            participantName = "actor",
                            text = "Act cycle two.",
                            instructions = "Act cycle two.",
                            passed = true,
                            notes = "Refined action."
                        )
                    )
                )
            )
            val verifier = RecordingP2PInterface(
                name = "verifier",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 1,
                            participantName = "verifier",
                            text = "Needs another cycle.",
                            instructions = "Needs another cycle.",
                            passed = false,
                            repeatRequested = true,
                            notes = "First pass still needs work."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 2,
                            participantName = "verifier",
                            text = "Verification passed.",
                            instructions = "Verification passed.",
                            passed = true,
                            repeatRequested = false,
                            notes = "Second pass is ready."
                        )
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 1,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "First cycle says proceed."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Second cycle still says proceed."
                        )
                    )
                )
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 1,
                            opinion = "Hold",
                            vote = "Hold",
                            confidence = 1.0,
                            reasoning = "First cycle says hold."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Second cycle says proceed."
                        )
                    )
                )
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipants(
                    "first" to first,
                    "second" to second
                )
                .setPlanner(planner)
                .setActor(actor)
                .setVerifier(verifier)
                .planVoteActVerifyRepeat()
                .roundRobin()
                .setRounds(2)
                .setVotingThreshold(0.6)

            val result = junction.execute(MultimodalContent(text = "Ship the workflow?"))
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.PLAN_VOTE_ACT_VERIFY_REPEAT, outcome.recipe)
            assertTrue(outcome.completed)
            assertEquals(2, outcome.cyclesExecuted)
            assertEquals(2, planner.requestCount)
            assertEquals(2, actor.requestCount)
            assertEquals(2, verifier.requestCount)
            assertEquals(2, first.requestCount)
            assertEquals(2, second.requestCount)
            assertTrue(outcome.phaseResults.any { it.phase == JunctionWorkflowPhase.PLAN })
            assertTrue(outcome.phaseResults.any { it.phase == JunctionWorkflowPhase.ACT })
            assertTrue(outcome.phaseResults.any { it.phase == JunctionWorkflowPhase.VERIFY })
            assertTrue(outcome.phaseResults.any { it.phase == JunctionWorkflowPhase.VOTE })
            assertTrue(result.passPipeline)
            assertIs<JunctionWorkflowState>(result.metadata["junctionWorkflowState"])
            assertIs<JunctionWorkflowOutcome>(result.metadata["junctionWorkflowOutcome"])
        }
    }

    /**
     * Verifies that the act -> vote -> verify -> repeat workflow starts with action and repeats until verification
     * succeeds.
     */
    @Test
    fun actVoteVerifyRepeatStartsWithAction()
    {
        runBlocking {
            val callOrder = mutableListOf<String>()
            val actor = RecordingP2PInterface(
                name = "actor",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 1,
                            participantName = "actor",
                            text = "Action cycle one.",
                            instructions = "Action cycle one.",
                            passed = true,
                            notes = "Initial action."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 2,
                            participantName = "actor",
                            text = "Action cycle two.",
                            instructions = "Action cycle two.",
                            passed = true,
                            notes = "Refined action."
                        )
                    )
                ),
                callLog = callOrder
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 1,
                            opinion = "Hold",
                            vote = "Hold",
                            confidence = 1.0,
                            reasoning = "First cycle says hold."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Second cycle says proceed."
                        )
                    )
                ),
                callLog = callOrder
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 1,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "First cycle says proceed."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Second cycle says proceed."
                        )
                    )
                ),
                callLog = callOrder
            )
            val verifier = RecordingP2PInterface(
                name = "verifier",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 1,
                            participantName = "verifier",
                            text = "Needs another cycle.",
                            instructions = "Needs another cycle.",
                            passed = false,
                            repeatRequested = true,
                            notes = "First pass still needs work."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 2,
                            participantName = "verifier",
                            text = "Verification passed.",
                            instructions = "Verification passed.",
                            passed = true,
                            repeatRequested = false,
                            notes = "Second pass is ready."
                        )
                    )
                ),
                callLog = callOrder
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipants(
                    "first" to first,
                    "second" to second
                )
                .setActor(actor)
                .setVerifier(verifier)
                .actVoteVerifyRepeat()
                .roundRobin()
                .setRounds(2)
                .setVotingThreshold(0.5)

            val result = junction.execute(MultimodalContent(text = "Execute the workflow first."))
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.ACT_VOTE_VERIFY_REPEAT, outcome.recipe)
            assertTrue(outcome.completed)
            assertEquals(2, outcome.cyclesExecuted)
            assertEquals("actor", callOrder.first())
            assertEquals(listOf("actor", "first", "second", "verifier"), callOrder.take(4))
            assertEquals(2, actor.requestCount)
            assertEquals(2, first.requestCount)
            assertEquals(2, second.requestCount)
            assertEquals(2, verifier.requestCount)
            assertTrue(result.passPipeline)
        }
    }

    /**
     * Verifies that the plan -> vote -> adjust -> output -> exit workflow produces a structured handoff artifact and
     * keeps the output phase explicit.
     */
    @Test
    fun planVoteAdjustOutputExitProducesHandoff()
    {
        runBlocking {
            val traceConfig = TraceConfig(
                enabled = true,
                outputFormat = TraceFormat.JSON,
                detailLevel = TraceDetailLevel.DEBUG,
                includeContext = false,
                includeMetadata = true
            )
            val callOrder = mutableListOf<String>()
            val planner = RecordingP2PInterface(
                name = "planner",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan the handoff.",
                        instructions = "Plan the handoff.",
                        passed = true,
                        notes = "Planning complete."
                    )
                ),
                callLog = callOrder
            )
            val adjuster = RecordingP2PInterface(
                name = "adjuster",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.ADJUST,
                        cycleNumber = 1,
                        participantName = "adjuster",
                        text = "Adjusted handoff instructions.",
                        instructions = "Adjusted handoff instructions.",
                        passed = true,
                        notes = "Adjustments applied."
                    )
                ),
                callLog = callOrder
            )
            val outputHandler = RecordingP2PInterface(
                name = "output",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.OUTPUT,
                        cycleNumber = 1,
                        participantName = "output",
                        text = "Final handoff instructions.",
                        instructions = "Final handoff instructions.",
                        passed = true,
                        notes = "Output ready."
                    )
                ),
                callLog = callOrder
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "Looks ready."
                    )
                ),
                callLog = callOrder
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .setAdjuster(adjuster)
                .setOutputHandler(outputHandler)
                .planVoteAdjustOutputExit()
                .roundRobin()
                .setRounds(1)
                .setVotingThreshold(0.5)
                .enableTracing(traceConfig)

            val result = junction.execute(MultimodalContent(text = "Produce the final handoff."))
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)
            val traceEvents = PipeTracer.getTrace(junction.getTraceId()).map { it.eventType }

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.PLAN_VOTE_ADJUST_OUTPUT_EXIT, outcome.recipe)
            assertTrue(outcome.completed)
            assertTrue(outcome.handoffOnly)
            assertEquals(1, planner.requestCount)
            assertEquals(1, participant.requestCount)
            assertEquals(1, adjuster.requestCount)
            assertEquals(1, outputHandler.requestCount)
            assertEquals(listOf("planner", "participant", "adjuster", "output"), callOrder.take(4))
            assertEquals("Final handoff instructions.", outcome.finalText)
            assertTrue(result.passPipeline)
            assertIs<JunctionWorkflowOutcome>(result.metadata["junctionWorkflowOutcome"])
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_WORKFLOW_START))
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_PHASE_START))
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_PHASE_END))
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_HANDOFF))
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_WORKFLOW_SUCCESS))
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_WORKFLOW_END))
        }
    }

    /**
     * Verifies that workflow pause and resume checkpoints work without corrupting the workflow outcome.
     */
    @Test
    fun workflowPauseAndResumeAtSafeCheckpoint()
    {
        runBlocking {
            val planner = RecordingP2PInterface(
                name = "planner",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 1,
                            participantName = "planner",
                            text = "Plan one.",
                            instructions = "Plan one.",
                            passed = true,
                            notes = "Planning first cycle."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 2,
                            participantName = "planner",
                            text = "Plan two.",
                            instructions = "Plan two.",
                            passed = true,
                            notes = "Planning second cycle."
                        )
                    )
                )
            )
            val actor = RecordingP2PInterface(
                name = "actor",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 1,
                            participantName = "actor",
                            text = "Act one.",
                            instructions = "Act one.",
                            passed = true,
                            notes = "Acting first cycle."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 2,
                            participantName = "actor",
                            text = "Act two.",
                            instructions = "Act two.",
                            passed = true,
                            notes = "Acting second cycle."
                        )
                    )
                )
            )
            val verifier = RecordingP2PInterface(
                name = "verifier",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 1,
                            participantName = "verifier",
                            text = "Repeat required.",
                            instructions = "Repeat required.",
                            passed = false,
                            repeatRequested = true,
                            notes = "First cycle still needs work."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 2,
                            participantName = "verifier",
                            text = "Verification complete.",
                            instructions = "Verification complete.",
                            passed = true,
                            repeatRequested = false,
                            notes = "Second cycle is ready."
                        )
                    )
                )
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", RecordingP2PInterface(name = "participant"))
                .setPlanner(planner)
                .setActor(actor)
                .setVerifier(verifier)
                .planVoteActVerifyRepeat()
                .roundRobin()
                .setRounds(2)
                .setVotingThreshold(0.6)
                .enableTracing(
                    TraceConfig(
                        enabled = true,
                        outputFormat = TraceFormat.JSON,
                        detailLevel = TraceDetailLevel.DEBUG,
                        includeContext = false,
                        includeMetadata = true
                    )
                )

            junction.pause()

            val execution = async {
                junction.execute(MultimodalContent(text = "Pause and resume the workflow."))
            }

            delay(50)
            junction.resume()

            val result = execution.await()
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.PLAN_VOTE_ACT_VERIFY_REPEAT, outcome.recipe)
            assertEquals(2, outcome.cyclesExecuted)
        }
    }

    /**
     * Verifies that nested P2P containers remain first-class workflow participants without being flattened.
     */
    @Test
    fun workflowAcceptsNestedP2PParticipants()
    {
        runBlocking {
            val plannerParent = RecordingP2PInterface(name = "planner-parent")
            val outputParent = RecordingP2PInterface(name = "output-parent")

            val planner = NestedWorkflowParticipant(
                name = "planner",
                parentContainer = plannerParent,
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan from nested container.",
                        instructions = "Plan from nested container.",
                        passed = true,
                        notes = "Planner executed through a nested container."
                    )
                )
            )
            val outputHandler = NestedWorkflowParticipant(
                name = "output",
                parentContainer = outputParent,
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.OUTPUT,
                        cycleNumber = 1,
                        participantName = "output",
                        text = "Nested handoff instructions.",
                        instructions = "Nested handoff instructions.",
                        passed = true,
                        notes = "Output executed through a nested container."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "Looks ready."
                    )
                )
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .setOutputHandler(outputHandler)
                .votePlanOutputExit()
                .roundRobin()
                .setRounds(1)
                .setVotingThreshold(0.5)

            val result = junction.execute(MultimodalContent(text = "Use nested workflow participants."))
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.VOTE_PLAN_OUTPUT_EXIT, outcome.recipe)
            assertTrue(outcome.completed)
            assertSame(plannerParent, planner.getContainerObject())
            assertSame(outputParent, outputHandler.getContainerObject())
            assertEquals(1, planner.requestCount)
            assertEquals(1, outputHandler.requestCount)
            assertTrue(result.passPipeline)
        }
    }

    /**
     * Verifies that a real Manifold can sit in the workflow ancestry chain without Junction flattening it into a
     * plain participant container.
     */
    @Test
    fun workflowAcceptsRealManifoldInNestedContainerChain()
    {
        runBlocking {
            val realManifold = buildNestedWorkflowManifoldFixture()
            val moderator = RecordingP2PInterface(name = "moderator")

            val planner = NestedWorkflowParticipant(
                name = "planner",
                parentContainer = realManifold,
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan from the real Manifold ancestry chain.",
                        instructions = "Plan from the real Manifold ancestry chain.",
                        passed = true,
                        notes = "Planner executed with a real Manifold parent."
                    )
                )
            )
            val outputHandler = NestedWorkflowParticipant(
                name = "output",
                parentContainer = realManifold,
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.OUTPUT,
                        cycleNumber = 1,
                        participantName = "output",
                        text = "Real Manifold handoff instructions.",
                        instructions = "Real Manifold handoff instructions.",
                        passed = true,
                        notes = "Output executed with a real Manifold parent."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Proceed",
                        vote = "Proceed",
                        confidence = 1.0,
                        reasoning = "The nested harness is valid."
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .setOutputHandler(outputHandler)
                .votePlanOutputExit()
                .roundRobin()
                .setRounds(1)
                .setVotingThreshold(0.5)

            val result = junction.execute(MultimodalContent(text = "Use a real Manifold ancestor."))
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.VOTE_PLAN_OUTPUT_EXIT, outcome.recipe)
            assertTrue(outcome.completed)
            assertSame(realManifold, planner.getContainerObject())
            assertSame(realManifold, outputHandler.getContainerObject())
            assertEquals(2, realManifold.getPipelinesFromInterface().size)
            assertEquals(1, planner.requestCount)
            assertEquals(1, outputHandler.requestCount)
            assertTrue(result.passPipeline)
        }
    }

    /**
     * Verifies that Junction rejects an indirect cycle even when a real Manifold is part of the ancestry chain.
     */
    @Test
    fun workflowRejectsRealManifoldCycle()
    {
        runBlocking {
            val realManifold = buildNestedWorkflowManifoldFixture()
            val cyclePartner = RecordingP2PInterface(name = "cycle-partner")

            realManifold.setContainerObject(cyclePartner)
            cyclePartner.containerRef = realManifold

            val planner = NestedWorkflowParticipant(
                name = "planner",
                parentContainer = realManifold,
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan through a cyclic ancestry chain.",
                        instructions = "Plan through a cyclic ancestry chain.",
                        passed = true,
                        notes = "Planner is nested inside a real Manifold cycle."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Hold",
                        vote = "Hold",
                        confidence = 1.0,
                        reasoning = "Presence-only participant for cycle validation."
                    )
                )
            )
            val moderator = RecordingP2PInterface(name = "moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .planVoteActVerifyRepeat()
                .roundRobin()
                .setRounds(1)

            assertFailsWith<IllegalStateException> {
                junction.init()
            }
        }
    }

    /**
     * Verifies that workflow graph validation still rejects nested cycles before execution.
     */
    @Test
    fun workflowRejectsNestedContainerCycles()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(name = "moderator")
            val planner = RecordingP2PInterface(
                name = "planner",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan.",
                        instructions = "Plan.",
                        passed = true
                    )
                )
            )
            val actor = RecordingP2PInterface(
                name = "actor",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.ACT,
                        cycleNumber = 1,
                        participantName = "actor",
                        text = "Act.",
                        instructions = "Act.",
                        passed = true
                    )
                )
            )

            planner.containerRef = actor
            actor.containerRef = planner

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", RecordingP2PInterface(name = "participant"))
                .setPlanner(planner)
                .setActor(actor)
                .planVoteActVerifyRepeat()
                .roundRobin()
                .setRounds(1)

            assertFailsWith<IllegalStateException> {
                junction.init()
            }
        }
    }

    /**
     * Verifies that discussion requests are compacted with a bounded outbound envelope and that optional
     * summarization only applies to older history tails.
     */
    @Test
    fun discussionMemoryPolicyCompactsOutboundRequests()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseSequence = mutableListOf(
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = true,
                            selectedParticipants = mutableListOf(),
                            finalDecision = "Proceed",
                            nextRoundPrompt = "",
                            notes = "Continue into the second round."
                        )
                    ),
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = false,
                            selectedParticipants = mutableListOf(),
                            finalDecision = "Proceed",
                            nextRoundPrompt = "",
                            notes = "Consensus can now close the discussion."
                        )
                    )
                )
            )
            val first = RecordingP2PInterface(
                name = "first",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 1,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Round one says proceed."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "first",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Round two still says proceed."
                        )
                    )
                )
            )
            val second = RecordingP2PInterface(
                name = "second",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 1,
                            opinion = "Hold",
                            vote = "Hold",
                            confidence = 1.0,
                            reasoning = "Round one says hold."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "second",
                            roundNumber = 2,
                            opinion = "Hold",
                            vote = "Hold",
                            confidence = 1.0,
                            reasoning = "Round two still says hold."
                        )
                    )
                )
            )

            first.setP2pDescription(first.getP2pDescription()!!.copy(contextWindowSize = 192))
            first.setP2pRequirements(first.getP2pRequirements()!!.copy(maxTokens = 192))
            second.setP2pDescription(second.getP2pDescription()!!.copy(contextWindowSize = 192))
            second.setP2pRequirements(second.getP2pRequirements()!!.copy(maxTokens = 192))

            val junction = Junction()
                .setModerator(moderator)
                .addParticipants(
                    "first" to first,
                    "second" to second
                )
                .roundRobin()
                .setRounds(2)
                .setVotingThreshold(0.75)
                .memoryPolicy {
                    outboundTokenBudget = 512
                    safetyReserveTokens = 32
                    minimumCriticalBudget = 64
                    minimumRecentBudget = 32
                    recentDiscussionEntries = 1
                    recentOpinionCount = 1
                    enableSummarization = true
                    summarizer = { text -> "SUMMARY:$text" }
                }

            val result = junction.execute(
                MultimodalContent(
                    text = buildString {
                        repeat(40) {
                            append("This discussion point is intentionally long to force compaction. ")
                        }
                    }
                )
            )
            val decision = deserialize<DiscussionDecision>(result.text)
            val envelope = first.lastRequest?.prompt?.metadata?.get("junctionMemoryEnvelope") as? JunctionMemoryEnvelope

            assertNotNull(decision)
            assertEquals("Proceed", decision.decision)
            assertEquals(2, decision.roundsExecuted)
            assertNotNull(envelope)
            assertEquals(192, envelope.resolvedBudget)
            assertEquals(JunctionMemoryRole.DISCUSSION_PARTICIPANT, envelope.roleKind)
            assertTrue(envelope.summarizationUsed)
            assertTrue(envelope.sections.any { section -> section.name == "summary" && section.text.contains("SUMMARY:") })
            assertTrue(first.lastRequest?.context?.contextElements?.any { it.contains("SUMMARY:") } == true)
            assertTrue(first.lastRequest?.prompt?.context?.contextElements?.any { it.contains("SUMMARY:") } == true)
            assertEquals(2, first.requestCount)
            assertEquals(2, second.requestCount)
            assertEquals(2, moderator.requestCount)
        }
    }

    /**
     * Verifies that workflow requests are also compacted with bounded outbound envelopes and optional summary
     * support for older phase history.
     */
    @Test
    fun workflowMemoryPolicyCompactsOutboundRequests()
    {
        runBlocking {
            val planner = RecordingP2PInterface(
                name = "planner",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 1,
                            participantName = "planner",
                            text = "Plan cycle one.",
                            instructions = "Plan cycle one.",
                            passed = true,
                            notes = "Planning first cycle."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.PLAN,
                            cycleNumber = 2,
                            participantName = "planner",
                            text = "Plan cycle two.",
                            instructions = "Plan cycle two.",
                            passed = true,
                            notes = "Planning second cycle."
                        )
                    )
                )
            )
            val actor = RecordingP2PInterface(
                name = "actor",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 1,
                            participantName = "actor",
                            text = "Act cycle one.",
                            instructions = "Act cycle one.",
                            passed = true,
                            notes = "Acting first cycle."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.ACT,
                            cycleNumber = 2,
                            participantName = "actor",
                            text = "Act cycle two.",
                            instructions = "Act cycle two.",
                            passed = true,
                            notes = "Acting second cycle."
                        )
                    )
                )
            )
            val verifier = RecordingP2PInterface(
                name = "verifier",
                responseSequence = mutableListOf(
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 1,
                            participantName = "verifier",
                            text = "Repeat required.",
                            instructions = "Repeat required.",
                            passed = false,
                            repeatRequested = true,
                            notes = "First cycle still needs work."
                        )
                    ),
                    serialize(
                        JunctionWorkflowPhaseResult(
                            phase = JunctionWorkflowPhase.VERIFY,
                            cycleNumber = 2,
                            participantName = "verifier",
                            text = "Verification complete.",
                            instructions = "Verification complete.",
                            passed = true,
                            repeatRequested = false,
                            notes = "Second cycle is ready."
                        )
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseSequence = mutableListOf(
                    serialize(
                        ParticipantOpinion(
                            participantName = "participant",
                            roundNumber = 1,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Round one says proceed."
                        )
                    ),
                    serialize(
                        ParticipantOpinion(
                            participantName = "participant",
                            roundNumber = 2,
                            opinion = "Proceed",
                            vote = "Proceed",
                            confidence = 1.0,
                            reasoning = "Round two still says proceed."
                        )
                    )
                )
            )
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseSequence = mutableListOf(
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = true,
                            selectedParticipants = mutableListOf(),
                            finalDecision = "Proceed",
                            nextRoundPrompt = "",
                            notes = "Continue into the second cycle."
                        )
                    ),
                    serialize(
                        ModeratorDirective(
                            continueDiscussion = false,
                            selectedParticipants = mutableListOf(),
                            finalDecision = "Proceed",
                            nextRoundPrompt = "",
                            notes = "The workflow can now stop."
                        )
                    )
                )
            )

            planner.setP2pDescription(planner.getP2pDescription()!!.copy(contextWindowSize = 256))
            planner.setP2pRequirements(planner.getP2pRequirements()!!.copy(maxTokens = 256))

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .setActor(actor)
                .setVerifier(verifier)
                .planVoteActVerifyRepeat()
                .roundRobin()
                .setRounds(2)
                .setVotingThreshold(0.5)
                .memoryPolicy {
                    outboundTokenBudget = 1024
                    safetyReserveTokens = 32
                    minimumCriticalBudget = 64
                    minimumRecentBudget = 32
                    recentPhaseResultCount = 1
                    enableSummarization = true
                    summarizer = { text -> "SUMMARY:$text" }
                }

            val result = junction.execute(
                MultimodalContent(
                    text = buildString {
                        repeat(20) {
                            append("This workflow content is intentionally long to force compaction. ")
                        }
                    }
                )
            )
            val outcome = deserialize<JunctionWorkflowOutcome>(result.text)
            val envelope = planner.lastRequest?.prompt?.metadata?.get("junctionMemoryEnvelope") as? JunctionMemoryEnvelope

            assertNotNull(outcome)
            assertEquals(JunctionWorkflowRecipe.PLAN_VOTE_ACT_VERIFY_REPEAT, outcome.recipe)
            assertEquals(2, outcome.cyclesExecuted)
            assertNotNull(envelope)
            assertEquals(256, envelope.resolvedBudget)
            assertEquals(JunctionMemoryRole.WORKFLOW_PLANNER, envelope.roleKind)
            assertTrue(envelope.summarizationUsed)
            assertTrue(envelope.sections.any { section -> section.name == "summary" && section.text.contains("SUMMARY:") })
            assertTrue(planner.lastRequest?.context?.contextElements?.any { it.contains("SUMMARY:") } == true)
            assertEquals(2, planner.requestCount)
            assertEquals(2, actor.requestCount)
            assertEquals(2, verifier.requestCount)
            assertEquals(2, participant.requestCount)
            assertEquals(0, moderator.requestCount)
        }
    }

    /**
     * Verifies that Junction fails fast when a request cannot be made safe enough to dispatch.
     */
    @Test
    fun memoryPolicyFailsFastWhenOutboundBudgetIsTooSmall()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        selectedParticipants = mutableListOf(),
                        finalDecision = "Stop",
                        nextRoundPrompt = "",
                        notes = "Fallback moderator."
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        roundNumber = 1,
                        opinion = "Stop",
                        vote = "Stop",
                        confidence = 1.0,
                        reasoning = "Does not matter because the request should never dispatch."
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setRounds(1)
                .setVotingThreshold(0.5)
                .memoryPolicy {
                    outboundTokenBudget = 100
                    safetyReserveTokens = 20
                    minimumCriticalBudget = 128
                    minimumRecentBudget = 64
                }

            assertFailsWith<IllegalStateException> {
                junction.execute(
                    MultimodalContent(
                        text = "This prompt should never reach a downstream participant because the budget is too small."
                    )
                )
            }
            assertEquals(0, participant.requestCount)
            assertEquals(0, moderator.requestCount)
        }
    }

    /**
     * Verifies that Junction enforces authentication when requiresAuth is enabled.
     */
    @Test
    fun enforcesAuthenticationSuccess()
    {
        runBlocking {
            val moderator = RecordingP2PInterface(
                name = "moderator",
                responseText = serialize(
                    ModeratorDirective(
                        continueDiscussion = false,
                        finalDecision = "Approve"
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "worker",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "worker",
                        opinion = "Approve",
                        vote = "Approve"
                    )
                )
            )

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("worker", participant)
                .setRounds(1)

            junction.setP2pDescription(P2PDescriptor(
                agentName = "junction",
                agentDescription = "desc",
                transport = P2PTransport(),
                requiresAuth = true,
                usesConverse = true,
                allowsAgentDuplication = false,
                allowsCustomContext = false,
                allowsCustomAgentJson = false,
                recordsInteractionContext = false,
                recordsPromptContent = false,
                allowsExternalContext = false,
                contextProtocol = ContextProtocol.none
            ))
            junction.setP2pRequirements(P2PRequirements(
                authMechanism = { authBody -> authBody == "valid-token" }
            ))

            val request = P2PRequest(
                prompt = MultimodalContent(text = "Test auth."),
                authBody = "valid-token"
            )

            val response = junction.executeP2PRequest(request)
            assertNotNull(response)
            val decision = deserialize<DiscussionDecision>(response!!.output!!.text)
            assertEquals("Approve", decision!!.decision)
        }
    }

    /**
     * Verifies that the Junction DSL can be built asynchronously using buildSuspend.
     */
    @Test
    fun testJunctionDslBuildSuspend()
    {
        runBlocking {
            val moderator = RecordingP2PInterface("moderator")
            val participant = RecordingP2PInterface("worker")

            val junction = JunctionDsl().apply {
                moderator(moderator)
                participant("worker", participant)
                rounds(1)
                threshold(0.5)
            }.buildSuspend()

            assertNotNull(junction)
            val result = junction.execute(MultimodalContent(text = "Test suspend build."))
            assertNotNull(result)
            assertTrue(result.passPipeline)
        }
    }

    /**
     * Verifies that passPipeline = true results in a JUNCTION_WORKFLOW_SUCCESS trace event during workflow execution.
     */
    @Test
    fun testTraceSuccessOnPassPipeline()
    {
        runBlocking {
            val traceConfig = TraceConfig(enabled = true)
            val planner = RecordingP2PInterface(
                name = "planner",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.PLAN,
                        cycleNumber = 1,
                        participantName = "planner",
                        text = "Plan.",
                        passed = true
                    )
                )
            )
            val participant = RecordingP2PInterface(
                name = "participant",
                responseText = serialize(
                    ParticipantOpinion(
                        participantName = "participant",
                        opinion = "Approve",
                        vote = "Approve"
                    )
                )
            )
            val outputHandler = RecordingP2PInterface(
                name = "output",
                responseText = serialize(
                    JunctionWorkflowPhaseResult(
                        phase = JunctionWorkflowPhase.OUTPUT,
                        cycleNumber = 1,
                        participantName = "output",
                        text = "Output.",
                        passed = true
                    )
                )
            )
            val moderator = RecordingP2PInterface("moderator")

            val junction = Junction()
                .setModerator(moderator)
                .addParticipant("participant", participant)
                .setPlanner(planner)
                .setOutputHandler(outputHandler)
                .votePlanOutputExit()
                .setRounds(1)
                .enableTracing(traceConfig)

            val result = junction.execute(MultimodalContent(text = "Test trace success."))
            assertTrue(result.passPipeline)

            val traceEvents = PipeTracer.getTrace(junction.getTraceId()).map { it.eventType }
            assertTrue(traceEvents.contains(TraceEventType.JUNCTION_WORKFLOW_SUCCESS), "Trace should contain JUNCTION_WORKFLOW_SUCCESS")
        }
    }
}

/**
 * Test double for a P2P-capable participant or moderator.
 */
private class RecordingP2PInterface(
    private val name: String,
    private val responseText: String = "",
    private val responseSequence: MutableList<String> = mutableListOf(),
    private val callLog: MutableList<String>? = null
) : P2PInterface
{
    var requestCount: Int = 0
        private set

    var lastRequest: P2PRequest? = null
        private set

    var containerRef: Any? = null

    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var transport: P2PTransport? = null

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return descriptor
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        this.transport = transport
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return transport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        this.requirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return requirements
    }

    override fun getContainerObject(): Any?
    {
        return containerRef
    }

    override fun setContainerObject(container: Any)
    {
        containerRef = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        return listOf()
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        requestCount++
        lastRequest = request
        callLog?.add(name)
        val outputText = if(responseSequence.isNotEmpty())
        {
            responseSequence.removeAt(0)
        }
        else
        {
            responseText
        }
        return P2PResponse(
            output = MultimodalContent(text = outputText)
        )
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return content
    }

    init
    {
        descriptor = P2PDescriptor(
            agentName = name,
            agentDescription = "Recording test double for $name",
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = name
            ),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        )
        transport = descriptor?.transport
        requirements = P2PRequirements(
            allowExternalConnections = true,
            allowAgentDuplication = false,
            allowCustomContext = false,
            allowCustomJson = false
        )
    }
}

/**
 * Test double that behaves like a nested workflow participant by preserving an explicit parent container reference.
 */
private class NestedWorkflowParticipant(
    private val name: String,
    private val parentContainer: Any? = null,
    private val responseText: String = ""
) : P2PInterface
{
    var requestCount: Int = 0
        private set

    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var transport: P2PTransport? = null

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return descriptor
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        this.transport = transport
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return transport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        this.requirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return requirements
    }

    override fun getContainerObject(): Any?
    {
        return parentContainer
    }

    override fun setContainerObject(container: Any)
    {
        // Nested fixtures keep the injected ancestry that was supplied at construction time.
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        return listOf()
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        requestCount++
        return P2PResponse(
            output = MultimodalContent(text = responseText)
        )
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return content
    }

    init
    {
        descriptor = P2PDescriptor(
            agentName = name,
            agentDescription = "Nested workflow test double for $name",
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = name
            ),
            requiresAuth = false,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        )
        transport = descriptor?.transport
        requirements = P2PRequirements(
            allowExternalConnections = true,
            allowAgentDuplication = false,
            allowCustomContext = false,
            allowCustomJson = false
        )
    }
}

/**
 * Build a minimal real Manifold fixture for nested-container regression coverage.
 */
private fun buildNestedWorkflowManifoldFixture(): Manifold
{
    return manifold {
        manager {
            pipeline {
                pipelineName = "nested-manifold-manager"
                add(
                    WorkflowFixturePipe()
                        .setPipeName("dispatcher")
                        .setJsonOutput(AgentRequest())
                        .setTokenBudget(
                            TokenBudgetSettings(
                                contextWindowSize = 4096,
                                userPromptSize = 1024,
                                maxTokens = 256
                            )
                        )
                )
            }
            agentDispatchPipe("dispatcher")
        }

        worker("nested-worker") {
            pipeline {
                pipelineName = "nested-manifold-worker"
                add(
                    WorkflowFixturePipe()
                        .setPipeName("worker")
                        .setContextWindowSize(2048)
                        .autoTruncateContext()
                )
            }
        }
    }
}

/**
 * Minimal pipe fixture used to assemble a real Manifold in Junction regression tests.
 */
private class WorkflowFixturePipe : Pipe()
{
    override fun truncateModuleContext(): Pipe
    {
        return this
    }

    override suspend fun generateText(promptInjector: String): String
    {
        return promptInjector
    }
}
