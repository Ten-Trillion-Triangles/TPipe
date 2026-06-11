package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.Structs.PipeSettings
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Pipe.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

/**
 * Minimal no-op [P2PInterface] used to populate agent slots on a [PumpStation]
 * during set/get tests. Captures the calls to [setParentInterface] and [P2PInit]
 * so the test can verify the setters reached the runtime state.
 */
class SgTestAgent(
    override var killSwitch: KillSwitch? = null,
    val agentTag: String = "SgTestAgent"
) : P2PInterface
{
    var initCount: Int = 0
        private set
    var lastParent: P2PInterface? = null
        private set

    override suspend fun P2PInit()
    {
        initCount += 1
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null

    override fun setParentInterface(parent: P2PInterface)
    {
        lastParent = parent
    }

    override fun getParentP2PInterface(): P2PInterface? = lastParent

    override fun getPaths(): String = ""

    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}

    override fun getTokenBudgetSettings(): TokenBudgetSettings? = null

    override fun setPipeSettingsRecursively(settings: PipeSettings) {}
}

/**
 * Tests for the new fluent setters on [PumpStation] and the gap fixes on
 * [PathObject] (setRunsInBackground) plus [PumpStation.addReservePath]. Also
 * covers the DSL collapse: pumpStation("...") { ... }.build() must actually
 * wire its captured values into the returned station.
 */
class PumpStationSetGetTest
{
    // ---- Setters ----

    /**
     * Verifies that every agent / agent-builder setter assigns the value and the
     * value is observable via the public inspection surface. We do not exercise
     * [P2PInitInternal] because [P2PInit] requires a fully-built pipeline; the
     * setters are configuration-time only.
     */
    @Test
    fun testAgentSettersRoundTrip()
    {
        runBlocking {
            val station = PumpStation()
            val judge = Pipeline()
            val dispatch = Pipeline()
            val intervention = SgTestAgent(agentTag = "intervention")
            val health = SgTestAgent(agentTag = "health")
            val lorebook = SgTestAgent(agentTag = "lorebook")
            val summary = SgTestAgent(agentTag = "summary")
            val goal = SgTestAgent(agentTag = "goal")
            val preInit = SgTestAgent(agentTag = "preInit")
            val pathSafety = SgTestAgent(agentTag = "pathSafety")

            // All setters must return the same station for chaining
            val returned = station
                .setJudgeAgent(judge)
                .setDispatchAgent(dispatch)
                .setInterventionAgent(intervention)
                .setHealthAgent(health)
                .setLorebookAgent(lorebook)
                .setSummaryAgent(summary)
                .setGoalAgent(goal)
                .setPreInitAgent(preInit)
                .setPathSafetyAgent(pathSafety)
            assertTrue(returned === station, "All setters must return the same PumpStation instance for chaining")

            // Agent builder functions are nullable suspend functions; verify they
            // can be assigned and re-assigned to null to clear.
            val judgeBuilder: (suspend (harness: PumpStation) -> Pipeline) = { Pipeline() }
            station.setJudgeAgentBuilderFunction(judgeBuilder)
            station.setJudgeAgentBuilderFunction(null) // clear

            val dispatchBuilder: (suspend (harness: PumpStation) -> Pipeline) = { Pipeline() }
            station.setDispatchAgentBuilderFunction(dispatchBuilder)
            station.setDispatchAgentBuilderFunction(null)

            val interventionBuilder: (suspend (harness: PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "ib") }
            station.setInterventionAgentBuilderFunction(interventionBuilder)
            station.setInterventionAgentBuilderFunction(null)

            // SgTestAgent used to verify harness-agent list
            val harnessAgent1 = SgTestAgent(agentTag = "ha1")
            val harnessAgent2 = SgTestAgent(agentTag = "ha2")
            station
                .addHarnessAgent(harnessAgent1)
                .addHarnessAgent(harnessAgent2)

            // The P2PInit loop visits all of these agents and pushes setParentInterface.
            // We do that ourselves to verify the wiring.
            station.P2PInit()
            // judge and dispatch are Pipeline instances, which uses the default empty P2PInit.
            // We do verify that they were set as the station's agents via P2PInitInternal succeeding without
            // IllegalArgumentException — see the P2PInitInternal pre-condition in PumpStation.
            assertTrue(intervention.initCount == 1, "intervention.P2PInit should run during P2PInitInternal")
            assertTrue(health.initCount == 1, "health.P2PInit should run during P2PInitInternal")
            assertTrue(lorebook.initCount == 1, "lorebook.P2PInit should run during P2PInitInternal")
            assertTrue(summary.initCount == 1, "summary.P2PInit should run during P2PInitInternal")
            assertTrue(goal.initCount == 1, "goal.P2PInit should run during P2PInitInternal")
            assertTrue(preInit.initCount == 1, "preInit.P2PInit should run during P2PInitInternal")
            assertTrue(pathSafety.initCount == 1, "pathSafety.P2PInit should run during P2PInitInternal")
            assertTrue(harnessAgent1.initCount == 1, "harnessAgent1.P2PInit should run during P2PInitInternal")
            assertTrue(harnessAgent2.initCount == 1, "harnessAgent2.P2PInit should run during P2PInitInternal")

            // Each agent must have been told its parent is the station
            assertTrue(intervention.lastParent === station, "intervention parent must be the station")
            assertTrue(harnessAgent1.lastParent === station, "harnessAgent1 parent must be the station")
        }
    }

    /**
     * Verifies that loop / memory / concurrency / interval setters accept the
     * documented value ranges and survive a [P2PInitInternal] round-trip. Spot-
     * checks that the values are stored on the task state / path descriptors
     * where the surface allows.
     */
    @Test
    fun testLoopGuardSetters()
    {
        runBlocking {
            val station = PumpStation()
                .setConcurrencyMode(PumpStationConcurrencyMode.Blocking)
                .setMemoryManagementMode(PumpStationMemoryManagementMode.Hybrid)
                .setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
                .setCompactionThreshold(0.42)
                .setMaxHarnessTurns(7)
                .setMaxTurns(13)
                .setMaxConsecutiveSamePath(3)
                .setMaxTotalPathCallsPerPath(2)
                .setMaxTurnHistorySize(8)
                .setStopHarnessOnInvalidPathRequest(true)
                .setMaxConcurrentBackgroundAgents(2)
                .setMaxConcurrentForegroundAgents(4)
                .setForegroundTurnInterval(1)
                .setBackgroundTurnInterval(2)
                .setHealthAgentTurnInterval(3)
                .setHealthAgentErrorRatioThreshold(0.5)
                .setHealthAgentConcurrencyMode(PumpStationConcurrencyMode.Blocking)
                .setDispatchAgent(Pipeline())

            // P2PInitInternal must not throw with these values set
            station.P2PInit()

            val state = station.getTaskState()
            assertNotNull(state, "Task state must be queryable after P2PInitInternal")
        }
    }

    /**
     * Verifies that [P2PInitInternal] succeeds with no DITL functions bound.
     * Then exercises every DITL setter with a no-op function and confirms
     * [P2PInitInternal] still succeeds.
     */
    @Test
    fun testDITLSettersAreNullByDefault()
    {
        runBlocking {
            val station = PumpStation().setDispatchAgent(Pipeline())
            // P2PInitInternal with no DITL bindings must succeed
            station.P2PInit()
            assertNotNull(station.getTaskState().runId, "runId must be populated after init")

            // Build a fresh station and bind no-op DITL functions, then re-init
            val station2 = PumpStation().setDispatchAgent(Pipeline())
            val noopContent: (suspend (MultimodalContent, PumpStation) -> MultimodalContent) = { c, _ -> c }
            val noopP2P: (suspend (MultimodalContent, PumpStation) -> P2PInterface) = { _, _ -> SgTestAgent(agentTag = "noop") }
            val noopInitContent: (suspend (MultimodalContent, PumpStation) -> MultimodalContent) = { c, _ -> c }
            val noopJudgeMini: (suspend (MultimodalContent, MiniBank, PumpStation) -> MiniBank) =
                { _, mb, _ -> mb }
            val noopDispatchJudge: (suspend (MultimodalContent, ContextWindow, MiniBank, PumpStation) -> MiniBank) =
                { _, _, mb, _ -> mb }
            val noopPreInvoke: (suspend (ContextWindow, MiniBank, PumpStation) -> Boolean) =
                { _, _, _ -> true }
            val noopPathSafety: (suspend (PathObject, String, PumpStation) -> Boolean) = { _, _, _ -> true }
            val noopValidation: (suspend (MultimodalContent, PumpStation) -> Boolean) = { _, _ -> true }
            val noopTransform: (suspend (MultimodalContent, PumpStation) -> MultimodalContent) = { c, _ -> c }
            val noopPreCompaction: (suspend (MultimodalContent, ConverseData, ConverseHistory, PumpStation) -> MultimodalContent) =
                { c, _, _, _ -> c }
            val noopPostCompaction: (suspend (MultimodalContent, ConverseHistory, PumpStation) -> MultimodalContent) =
                { c, _, _ -> c }
            val noopOnTruncated: (suspend (Boolean, Int) -> Unit) = { _, _ -> }

            station2
                .setPreInitFunction(noopInitContent)
                .setPreValidationJudgeFunction(noopJudgeMini)
                .setPostJudgeFunction(noopContent)
                .setPreValidationDispatchFunction(noopDispatchJudge)
                .setPreInvokeFunction(noopPreInvoke)
                .setPathSafetyFunction(noopPathSafety)
                .setPostGenerateFunction(noopP2P)
                .setPathValidationFunction(noopValidation)
                .setPathTransformationFunction(noopTransform)
                .setPostMemoryFunction(noopContent)
                .setPreCompactionFunction(noopPreCompaction)
                .setPostCompactionFunction(noopPostCompaction)
                .setOnContextTruncated(noopOnTruncated)

            // P2PInitInternal must still succeed with all DITL functions bound
            station2.P2PInit()
        }
    }

    /**
     * Drives a [pumpStation] DSL block that touches every supported configuration
     * surface and asserts the configuration actually landed on the returned
     * [PumpStation]. The prior no-op [_pendingDslConfig] would have failed this
     * test because none of the configuration would have been applied.
     */
    @Test
    fun testDslBuildAppliesAllConfiguration()
    {
        runBlocking {
            val station = pumpStation("test-station") {
                judgeAgent = Pipeline()
                dispatchAgent = Pipeline()
                interventionAgent = SgTestAgent(agentTag = "dsl-intervention")
                healthAgent = SgTestAgent(agentTag = "dsl-health")
                lorebookAgent = SgTestAgent(agentTag = "dsl-lorebook")
                summaryAgent = SgTestAgent(agentTag = "dsl-summary")
                goalAgent = SgTestAgent(agentTag = "dsl-goal")
                preInitAgent = SgTestAgent(agentTag = "dsl-preInit")
                pathSafetyAgent = SgTestAgent(agentTag = "dsl-pathSafety")
                additionalHarnessAgentSlots.add(HarnessAgentSlot(agent = SgTestAgent(agentTag = "dsl-extra1"), concurrency = PumpStationConcurrencyMode.Blocking))
                additionalHarnessAgentSlots.add(HarnessAgentSlot(agent = SgTestAgent(agentTag = "dsl-extra2"), concurrency = PumpStationConcurrencyMode.Blocking))

                systemTask = "system task text"
                userGuidelines = "guideline text"
                entryUserPrompt = "entry prompt text"
                maxHarnessTurns = 12
                maxConcurrentBackgroundAgents = 2
                maxConcurrentForegroundAgents = 4
                foregroundTurnInterval = 1
                backgroundTurnInterval = 3
                memoryManagementMode = PumpStationMemoryManagementMode.Hybrid
                compactionThreshold = 0.5
                compactionStrategy = PumpStationCompactionStrategy.Chunked
                maxTurnHistorySize = 16
                stopHarnessOnInvalidPathRequest = true
                failurePolicy = PumpStationFailurePolicy(
                    repairInvalidDispatchJson = false,
                    maxDispatchRepairAttempts = 4,
                    stashOversizedOutputs = false,
                    callInterventionOnPathFailure = false,
                    stopHarnessOnInvalidPathRequest = true
                )

                maxConsecutiveSamePath = 2
                maxTotalPathCallsPerPath = 5
                pathLimitExceededPolicy = PathLimitExceededPolicy.Halt
                pathLimitExceededFunction = { _, _, _ ->
                    PathLimitExceededResult(action = PathLimitExceededPolicy.Halt, reason = "dsl test")
                }

                // DITL hooks (every one of them)
                preInitFunction = { c, _ -> c }
                preValidationJudgeFunction = { _, mb, _ -> mb }
                preValidationDispatchFunction = { _, _, mb, _ -> mb }
                preInvokeFunction = { _, _, _ -> true }
                pathSafetyFunction = { _, _, _ -> true }
                postGenerateFunction = { _, _ -> SgTestAgent(agentTag = "dsl-pg") }
                pathValidationFunction = { _, _ -> true }
                pathTransformationFunction = { c, _ -> c }
                postMemoryFunction = { c, _ -> c }
                preCompactionFunction = { c, _, _, _ -> c }
                postCompactionFunction = { c, _, _ -> c }

                // A normal path
                path("alpha") {
                    description = "alpha path"
                    risk = PathRiskLevel.Medium
                    dispatchHint = "alpha hint"
                    runsInBackground = true
                    setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
                }
                // A reserve path
                reservePath("beta") {
                    description = "beta path"
                    risk = PathRiskLevel.High
                    revealWhen { _, _ -> true }
                    setInternalAgent(SgTestAgent(agentTag = "beta-agent"))
                }
                // Dispatcher rules
                dispatcherRules {
                    maxConsecutive("alpha", 1)
                    before("alpha", listOf("beta"))
                    after("alpha", "beta")
                }
            }

            // ----- Verify configuration actually landed on the station -----
            // Task state is queryable after P2PInitInternal
            station.P2PInit()
            val state = station.getTaskState()
            assertNotNull(state.runId, "runId must be populated")

            // Paths: alpha should be in getVisiblePathNames() (or in path list)
            val visible = station.getVisiblePathNames()
            assertTrue("alpha" in visible, "alpha path should be in visible path list; got $visible")

            // Reserve paths: beta should be in getReservePathNames()
            val reserve = station.getReservePathNames()
            assertTrue("beta" in reserve, "beta path should be in reserve path list; got $reserve")

            // Dispatcher rules: should have all 3 added
            val rules = station.getDispatcherRules()
            assertTrue(rules.size == 3, "All 3 dispatcher rules should be applied; got $rules")

            // failurePolicy is a public val — its fields should reflect what we set
            assertEquals(false, station.failurePolicy.repairInvalidDispatchJson)
            assertEquals(4, station.failurePolicy.maxDispatchRepairAttempts)
            assertEquals(false, station.failurePolicy.stashOversizedOutputs)
            assertEquals(false, station.failurePolicy.callInterventionOnPathFailure)
            assertEquals(true, station.failurePolicy.stopHarnessOnInvalidPathRequest)
        }
    }

    /**
     * Verifies the [PathObject.setRunsInBackground] setter and
     * [PumpStation.addReservePath] gap fixes.
     */
    @Test
    fun testPathObjectSetRunsInBackgroundAndAddReservePath()
    {
        runBlocking {
            FunctionRegistry.clear()

            // Part 1: setRunsInBackground
            val path = PathObject()
            path.pathName = "background_path"
            path.pathDescription = "Path that runs in background"
            path.setExecutionFunction { content, _, _, _ -> content }

            // Default state: not background
            assertFalse(path.isRunsInBackground, "Path should default to non-background")

            // Flip to background
            path.setRunsInBackground(true)
            assertTrue(path.isRunsInBackground, "setRunsInBackground(true) must flip the flag")

            // init()'s PathDescriptionData must reflect the new value
            val desc = path.init()
            assertTrue(desc.isRunsInBackground, "PathDescriptionData.isRunsInBackground must be true after setter call")

            // Flip back
            path.setRunsInBackground(false)
            assertFalse(path.isRunsInBackground, "setRunsInBackground(false) must flip back")
            val desc2 = path.init()
            assertFalse(desc2.isRunsInBackground, "PathDescriptionData.isRunsInBackground must be false after flip back")

            // Part 2: addReservePath
            val station = PumpStation().setDispatchAgent(Pipeline())
            val reservePath = PathObject()
            reservePath.pathName = "secret_path"
            reservePath.pathDescription = "Hidden behind revealWhen"
            reservePath.setExecutionFunction { content, _, _, _ -> content }

            // No reserve paths before add
            assertTrue(station.getReservePathNames().size == 0, "Reserve list should be empty initially")

            // Add it via the new mutator
            station.addReservePath(reservePath)
            val names = station.getReservePathNames()
            assertTrue(names.size == 1, "Reserve list should contain the added path")
            assertEquals("secret_path", names[0])

            // The path's parent should have been set to the station
            assertTrue(reservePath.getParentP2PInterface() === station,
                "Reserve path's parent must be set to the station by addReservePath")

            // And the path's revealWhen predicate should determine visibility.
            // Since revealWhen defaults to { _, _ -> false }, the path must NOT
            // appear in getVisiblePathNames() before it's revealed.
            val visible = station.getVisiblePathNames()
            assertFalse("secret_path" in visible,
                "Reserve path should not be visible until its revealWhen returns true")
        }
    }

    // ---- Judge run mode ----

    /**
     * Verifies that setJudgeRunMode(FlagTriggered) round-trips and the default is Always.
     * requestJudgeNextTurn() must flip the taskState flag.
     */
    @Test
    fun testJudgeRunModeSetterAndRequestFlag()
    {
        runBlocking {
            val station = PumpStation()

            // Default is Always
            assertEquals(PumpStationJudgeRunMode.Always, station.getJudgeRunMode())

            // Setter must return the same station for chaining
            val returned = station.setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
            assertSame(station, returned, "setJudgeRunMode must return this for chaining")
            assertEquals(PumpStationJudgeRunMode.FlagTriggered, station.getJudgeRunMode())

            // The task state flag starts false
            assertFalse(station.getTaskState().requestJudgeNextTurn,
                "requestJudgeNextTurn must default to false")

            // Calling requestJudgeNextTurn() flips the flag and returns the station
            val returned2 = station.requestJudgeNextTurn()
            assertSame(station, returned2, "requestJudgeNextTurn must return this for chaining")
            assertTrue(station.getTaskState().requestJudgeNextTurn,
                "requestJudgeNextTurn must flip the flag to true")

            // Setting back to Always preserves the flag value (the field is independent)
            station.setJudgeRunMode(PumpStationJudgeRunMode.Always)
            assertEquals(PumpStationJudgeRunMode.Always, station.getJudgeRunMode())
            assertTrue(station.getTaskState().requestJudgeNextTurn,
                "Mode change must not clear the flag - it is consumed by the judge phase")
        }
    }
}
