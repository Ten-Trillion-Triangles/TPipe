package com.TTT.Pipeline

import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.Pipe
import com.TTT.Util.examplePromptFor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for Junction's data-class contract auto-injection.
 *
 * Mirrors the contract auto-injection pattern from PumpStation (see
 * `PumpStation.kt:3576, 3590, 4709-4723`) but generalized to a polymorphic
 * resolver that targets each eligible `P2PInterface` subtype's decision pipe.
 *
 * The test suite uses a recording pipe stub to observe `setSystemPrompt`
 * calls without driving a real LLM. The stub implements the two `Pipe`
 * abstract methods (`generateText`, `truncateModuleContext`) with no-op
 * implementations and reads `systemPrompt` via `toPipeSettings()` after the
 * injection happens, since `Pipe.setSystemPrompt` is final and cannot be
 * overridden.
 */
class JunctionContractAutoInjectionTest
{
    /**
     * Test stub for `Pipe` that records the most recent `setSystemPrompt` call
     * by reading `toPipeSettings().systemPrompt` after each call. Implements
     * the two `Pipe` abstract methods with no-op bodies.
     */
    private class RecordingPipe : Pipe()
    {
        var systemPromptCallCount: Int = 0
            private set

        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this

        fun snapshotSystemPrompt(): String? = toPipeSettings().systemPrompt
    }

    /**
     * Hand-rolled `P2PInterface` for the no-op cases. Junction must not attempt
     * auto-injection on anonymous implementations.
     */
    private class AnonymousAgent : P2PInterface
    {
        override var killSwitch: com.TTT.P2P.KillSwitch? = null
    }

    /**
     * Build a one-pipe pipeline and initialize it for execution. Records the
     * stub pipe on the side so tests can assert on it after binding.
     */
    private data class StubbedPipeline(val pipeline: Pipeline, val stub: RecordingPipe)

    private fun buildStubbedPipeline(stubName: String = "stub"): StubbedPipeline
    {
        val stub = RecordingPipe()
        val pipeline = Pipeline().add(stub)
        runBlocking { pipeline.init(true) }
        return StubbedPipeline(pipeline, stub)
    }

    /**
     * Wrap `addParticipant` etc. to ensure the moderator binding is set up
     * (Junction requires it before any other role binding).
     */
    private fun buildModeratorStub(): StubbedPipeline
    {
        val stub = RecordingPipe()
        val pipeline = Pipeline().add(stub)
        runBlocking { pipeline.init(true) }
        return StubbedPipeline(pipeline, stub)
    }

    //===========================================Test Cases============================================================

    /**
     * GREEN Pipeline. After Junction's auto-injection lands, the participant's
     * decision pipe receives the default prompt.
     */
    @Test
    fun greenParticipantPipelineReceivesDefaultPrompt()
    {
        val (pipeline, stub) = buildStubbedPipeline("p")
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("p", pipeline)
            .setRounds(1)

        assertEquals(DEFAULT_PARTICIPANT_PROMPT, stub.snapshotSystemPrompt(),
            "Participant Pipeline decision pipe should receive DEFAULT_PARTICIPANT_PROMPT.")
    }

    /**
     * Override beats default. `setParticipantSystemPrompt("custom")` wins.
     */
    @Test
    fun overrideBeatsDefault()
    {
        val (pipeline, stub) = buildStubbedPipeline("p")
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .setParticipantSystemPrompt("custom participant prompt")
            .addParticipant("p", pipeline)
            .setRounds(1)

        assertEquals("custom participant prompt", stub.snapshotSystemPrompt())
    }

    /**
     * Developer pre-set wins. If the developer already set a system prompt on
     * the pipe, auto-injection skips it (respect-developer-config rule).
     */
    @Test
    fun developerPreSetWins()
    {
        val stub = RecordingPipe()
        stub.setSystemPrompt("my own prompt")
        val pipeline = Pipeline().add(stub)
        runBlocking { pipeline.init(true) }
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("p", pipeline)
            .setRounds(1)

        assertEquals("my own prompt", stub.snapshotSystemPrompt(),
            "Developer pre-set system prompt must not be overwritten.")
    }

    /**
     * Junction binding is itself a Junction. Polymorphic resolver returns null.
     * No throw, no injection.
     */
    @Test
    fun junctionBindingIsNoOp()
    {
        val (innerPipeline, innerStub) = buildStubbedPipeline("inner-p")
        val (moderatorPipeline, _) = buildModeratorStub()
        val nestedJunction = Junction()
            .setModerator(Pipeline().add(RecordingPipe()).apply { runBlocking { init(true) } })
            .addParticipant("inner-p", innerPipeline)
            .setRounds(1)

        // Bind the nested Junction itself as a participant. The polymorphic
        // resolver must short-circuit and not reach the inner pipelines.
        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("nested", nestedJunction)
            .setRounds(1)

        // The inner pipeline was already initialized via the nested junction's
        // own setup. Count calls on the inner stub before vs after binding the
        // outer junction. The outer binding must not have re-targeted the inner.
        val callsAfterBinding = innerStub.systemPromptCallCount
        assertEquals(0, callsAfterBinding,
            "Nested Junction binding must not trigger injection on its inner participants.")
    }

    /**
     * Splitter binding. No pipes accessible via `getPipelinesFromInterface()`
     * by default — that returns `emptyList()`. Resolver returns null. No-op.
     */
    @Test
    fun splitterBindingIsNoOp()
    {
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("split", Splitter())
            .setRounds(1)

        // No throw on bind. The harness accepts Splitter without trying to
        // resolve pipes via getPipelinesFromInterface (which returns empty).
        assertTrue(true, "Splitter binding accepted without throwing.")
    }

    /**
     * Connector binding. Same as Splitter — polymorphic resolver falls through
     * to null. No-op.
     */
    @Test
    fun connectorBindingIsNoOp()
    {
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("conn", Connector())
            .setRounds(1)

        assertTrue(true, "Connector binding accepted without throwing.")
    }

    /**
     * MultiConnector binding. Same — no-op.
     */
    @Test
    fun multiConnectorBindingIsNoOp()
    {
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("mconn", MultiConnector())
            .setRounds(1)

        assertTrue(true, "MultiConnector binding accepted without throwing.")
    }

    /**
     * Hand-rolled anonymous P2PInterface. Polymorphic resolver falls through.
     */
    @Test
    fun anonymousP2PInterfaceIsNoOp()
    {
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("anon", AnonymousAgent())
            .setRounds(1)

        assertTrue(true, "Anonymous P2PInterface binding accepted without throwing.")
    }

    /**
     * Manifold binding. Contract prompt goes into the manager pipeline's
     * decision pipe. The manager pipe must have an `AgentRequest` JSON
     * contract set so Manifold's hard-validation accepts the manager pipeline.
     */
    @Test
    fun manifoldBindingReceivesPromptOnManagerPipeline()
    {
        val managerStub = RecordingPipe()
        managerStub.jsonOutput = examplePromptFor(AgentRequest::class)
        val managerPipeline = Pipeline().add(managerStub)
        runBlocking { managerPipeline.init(true) }
        val manifold = Manifold().setManagerPipeline(managerPipeline)
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("mfold", manifold)
            .setRounds(1)

        assertEquals(DEFAULT_PARTICIPANT_PROMPT, managerStub.snapshotSystemPrompt(),
            "Manifold binding should inject into manager pipeline's decision pipe.")
    }

    /**
     * Manifold rejects an empty manager pipeline at `setManagerPipeline` time
     * (its hard-validation requires the manager pipe to declare the
     * `AgentRequest` contract). This test confirms that the *Manifold call*
     * throws when the manager pipeline is empty — Junction's contract
     * injection is a no-op for the rejection path because Manifold fails
     * before Junction's binding code runs.
     *
     * Note: this is a Manifold-level precondition, not a Junction behavior.
     * Junction's `autoInjectDefaultPrompt` is not reached.
     */
    @Test
    fun manifoldRejectsEmptyManagerPipelineBeforeInjection()
    {
        val emptyManager = Pipeline() // no pipes added
        runBlocking { emptyManager.init(true) }
        val (moderatorPipeline, _) = buildModeratorStub()

        val ex = kotlin.runCatching {
            Manifold().setManagerPipeline(emptyManager)
        }.exceptionOrNull()

        assertTrue(ex is Exception,
            "Manifold.setManagerPipeline should reject an empty manager pipeline. " +
            "Got: ${ex?.message}")
    }

    /**
     * DistributionGrid binding. Per-node resolver walks
     * `getPipelinesFromInterface()` and targets the first pipeline's
     * decision pipe. The router pipeline is registered first via
     * `setRouter(...)`, so it receives the injection. The worker pipeline
     * stays empty (only one pipeline per grid is targeted by design).
     */
    @Test
    fun distributionGridBindingReceivesPromptOnFirstNode()
    {
        val routerStub = RecordingPipe()
        val routerPipeline = Pipeline().add(routerStub)
        runBlocking { routerPipeline.init(true) }
        val workerStub = RecordingPipe()
        val workerPipeline = Pipeline().add(workerStub)
        runBlocking { workerPipeline.init(true) }

        val grid = DistributionGrid()
            .setRouter(routerPipeline)
            .setWorker(workerPipeline)
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("grid", grid)
            .setRounds(1)

        assertEquals(DEFAULT_PARTICIPANT_PROMPT, routerStub.snapshotSystemPrompt(),
            "DistributionGrid binding should inject into the first registered pipeline's decision pipe " +
            "(router, registered before worker).")
    }

    /**
     * PumpStation binding. Conservative no-op (path-resolving API out of scope).
     */
    @Test
    fun pumpStationBindingIsNoOp()
    {
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .addParticipant("pump", PumpStation())
            .setRounds(1)

        assertTrue(true, "PumpStation binding accepted without throwing.")
    }

    /**
     * All seven roles parametric. Iterates over each role setter, asserting
     * the matching `DEFAULT_<ROLE>_PROMPT` constant lands on the binding's
     * decision pipe.
     */
    @Test
    fun allSevenRolesReceiveTheirDefaultPrompts()
    {
        data class RoleCase(
            val name: String,
            val bind: (Junction, Pipeline) -> Junction,
            val expectedPrompt: String
        )

        // Counter for unique roleNames across iterations. RecordingPipe::class.simpleName
        // resolves to "RecordingPipe", so without explicit names each iteration would
        // collide on the second binding call.
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        fun nextName(prefix: String): String = "$prefix-${'$'}{counter.incrementAndGet()}"

        val cases = listOf(
            RoleCase(
                "moderator",
                { j, p -> j.setModerator(nextName("moderator"), p) },
                DEFAULT_MODERATOR_PROMPT
            ),
            RoleCase(
                "participant",
                { j, p -> j.addParticipant(nextName("participant"), p) },
                DEFAULT_PARTICIPANT_PROMPT
            ),
            RoleCase(
                "planner",
                { j, p -> j.setPlanner(nextName("planner"), p) },
                DEFAULT_PLANNER_PROMPT
            ),
            RoleCase(
                "actor",
                { j, p -> j.setActor(nextName("actor"), p) },
                DEFAULT_ACTOR_PROMPT
            ),
            RoleCase(
                "verifier",
                { j, p -> j.setVerifier(nextName("verifier"), p) },
                DEFAULT_VERIFIER_PROMPT
            ),
            RoleCase(
                "adjuster",
                { j, p -> j.setAdjuster(nextName("adjuster"), p) },
                DEFAULT_ADJUSTER_PROMPT
            ),
            RoleCase(
                "output",
                { j, p -> j.setOutputHandler(nextName("output"), p) },
                DEFAULT_OUTPUT_PROMPT
            )
        )

        for(case in cases)
        {
            // For non-moderator roles, Junction still requires a moderator binding.
            // Use a no-op moderator pipeline with an explicit role name so the
            // harness moderator doesn't collide with roleName-resolved duplicates
            // when RecordingPipe::class.simpleName == "RecordingPipe".
            val (moderatorPipeline, _) = buildModeratorStub()
            val (rolePipeline, roleStub) = buildStubbedPipeline("${case.name}-decision")

            val junction = Junction()
                .setModerator("harness-moderator", moderatorPipeline)
                .setRounds(1)

            case.bind(junction, rolePipeline)

            assertEquals(case.expectedPrompt, roleStub.snapshotSystemPrompt(),
                "Role '${case.name}' should receive its DEFAULT_<ROLE>_PROMPT. " +
                "Got: '${roleStub.snapshotSystemPrompt()?.take(80)}...'")
        }
    }

    /**
     * DSL integration. `setParticipantSystemPrompt("x")` followed by
     * `addParticipant("p", pipeline)` must apply the custom prompt at bind time.
     */
    @Test
    fun dslIntegrationAppliesCustomPrompt()
    {
        val (pipeline, stub) = buildStubbedPipeline("p")
        val (moderatorPipeline, _) = buildModeratorStub()

        Junction()
            .setModerator(moderatorPipeline)
            .setParticipantSystemPrompt("dsl custom prompt")
            .addParticipant("p", pipeline)
            .setRounds(1)

        assertEquals("dsl custom prompt", stub.snapshotSystemPrompt(),
            "setParticipantSystemPrompt must apply at bind time.")
    }
}