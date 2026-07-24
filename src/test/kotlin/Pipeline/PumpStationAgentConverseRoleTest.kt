package com.TTT.Pipeline

import com.TTT.Context.ConverseRole
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the per-agent converse-role contract for PumpStation's agent slots.
 *
 * Authoritative agents (judge, dispatch, intervention, goal, path-safety,
 * health, preInit) gate the harness flow and get [ConverseRole.supervisor].
 * Memory workers (lorebook, summary) maintain state but do not gate flow
 * and keep the default [ConverseRole.agent]. The assignment is recursive
 * so it drills through any container pipes the agent may wrap.
 *
 * Per-agent role assignment lives in [PumpStation.init] right after
 * parent-interface binding. The setConverseRoleRecursive method on
 * [com.TTT.P2P.P2PInterface] is the propagation primitive; the override
 * on [Pipeline] drills through getPipes() and calls
 * [com.TTT.Pipe.Pipe.setConverseRole] on each leaf pipe.
 */
class PumpStationAgentConverseRoleTest
{
    /**
     * Authoritative agent role assignment. The "authoritative" half of
     * the per-agent role contract: judge, dispatch, intervention, goal,
     * path-safety, health, preInit all get supervisor.
     */
    @Test
    fun `authoritative agent slots are wired to converseRole supervisor`() {
        // Per Pitfall 8 (pump-station skill): the first `path("name") { }`
        // call triggers `promote()` which `copyFrom(this)` snapshots the
        // initial builder. Any property set on the lambda's `this` AFTER
        // the first path() is on the discarded initial builder, not the
        // promoted Ready-stage builder. So configure all the agent
        // properties BEFORE the first path() call. We use `dispatchAgent`
        // as the carrier — the build() guard requires it non-null, and
        // a sentinel path needs a pipeline. Setting it before path() means
        // the path() call promotes a builder that already has all the
        // agent assignments, and they survive the copyFrom snapshot.
        val authoritativeJudge = Pipeline().apply { add(StubPipe("judge-pipe")) }
        val authoritativeDispatch = Pipeline().apply { add(StubPipe("authoritative-dispatch")) }
        val authoritativeIntervention = Pipeline().apply { add(StubPipe("intervention-pipe")) }
        val authoritativeGoal = Pipeline().apply { add(StubPipe("goal-pipe")) }
        val authoritativePathSafety = Pipeline().apply { add(StubPipe("path-safety-pipe")) }
        val authoritativeHealth = Pipeline().apply { add(StubPipe("health-pipe")) }
        val authoritativePreInit = Pipeline().apply { add(StubPipe("preInit-pipe")) }

        val station = pumpStation("authoritative-role-${System.nanoTime()}") {
            judgeAgent = authoritativeJudge
            dispatchAgent = authoritativeDispatch
            interventionAgent = authoritativeIntervention
            goalAgent = authoritativeGoal
            pathSafetyAgent = authoritativePathSafety
            healthAgent = authoritativeHealth
            preInitAgent = authoritativePreInit
            path("noop-sentinel") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        // init() runs the per-agent role assignment in addition to P2PInit
        // on each agent. pumpStation { } only calls build(), so we have
        // to call init() explicitly to trigger the role assignment.
        // P2PInit is suspend; runBlocking drives it from the test thread.
        runBlocking { station.P2PInit() }

        assertAllAgentPipesAreSupervisor(station, "judge")
        assertAllAgentPipesAreSupervisor(station, "intervention")
        assertAllAgentPipesAreSupervisor(station, "goal")
        assertAllAgentPipesAreSupervisor(station, "pathSafety")
        assertAllAgentPipesAreSupervisor(station, "health")
        assertAllAgentPipesAreSupervisor(station, "preInit")
    }

    /**
     * Memory workers (lorebook, summary) keep the default
     * [ConverseRole.agent]. They maintain state but do not gate flow.
     */
    @Test
    fun `memory worker slots keep default converseRole agent`() {
        val sentinelDispatch = Pipeline().apply { add(StubPipe("noop")) }
        val memoryLorebook = Pipeline().apply { add(StubPipe("lorebook-pipe")) }
        val memorySummary = Pipeline().apply { add(StubPipe("summary-pipe")) }

        val station = pumpStation("memory-role-${System.nanoTime()}") {
            dispatchAgent = sentinelDispatch
            lorebookAgent = memoryLorebook
            summaryAgent = memorySummary
            path("noop-sentinel") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        runBlocking { station.P2PInit() }
        assertAllAgentPipesAreAgent(station, "lorebook")
        assertAllAgentPipesAreAgent(station, "summary")
    }

    private fun assertAllAgentPipesAreSupervisor(
        station: PumpStation,
        slotName: String
    )
    {
        val agent: P2PInterface? = when (slotName)
        {
            "judge"        -> station.judgeAgentInternal
            "dispatch"     -> station.dispatchAgentInternal
            "intervention" -> station.interventionAgentInternal
            "goal"         -> station.goalAgentInternal
            "pathSafety"   -> station.pathSafetyAgentInternal
            "health"       -> station.healthAgentInternal
            "preInit"      -> station.preInitAgentInternal
            else -> error("unknown slot $slotName")
        }
        assertNotNull(agent, "$slotName agent must be non-null")
        val pipes = (agent as Pipeline).getPipes()
        assertTrue(pipes.isNotEmpty(), "$slotName agent must have at least one pipe")
        for (pipe in pipes)
        {
            assertEquals(
                ConverseRole.supervisor,
                pipe.converseRoleForTest,
                "$slotName agent's pipe should have converseRole=supervisor, " +
                    "got ${pipe.converseRoleForTest}"
            )
        }
    }

    private fun assertAllAgentPipesAreAgent(
        station: PumpStation,
        slotName: String
    )
    {
        val agent: P2PInterface? = when (slotName)
        {
            "lorebook" -> station.lorebookAgentInternal
            "summary"  -> station.summaryAgentInternal
            else -> error("unknown slot $slotName")
        }
        assertNotNull(agent, "$slotName agent must be non-null")
        val pipes = (agent as Pipeline).getPipes()
        for (pipe in pipes)
        {
            assertEquals(
                ConverseRole.agent,
                pipe.converseRoleForTest,
                "$slotName memory worker should keep default converseRole=agent, " +
                    "got ${pipe.converseRoleForTest}"
            )
        }
    }

    /**
     * Minimal pipe stub. Doesn't need to actually generate text — the
     * converseRole assignment happens during init, before any LLM call.
     * Mirrors the pattern in DitlHookWiringTest and other PumpStation tests.
     */
    private class StubPipe(name: String) : com.TTT.Pipe.Pipe()
    {
        init { pipeName = name }
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): com.TTT.Pipe.Pipe = this
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}
        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
    }
}
