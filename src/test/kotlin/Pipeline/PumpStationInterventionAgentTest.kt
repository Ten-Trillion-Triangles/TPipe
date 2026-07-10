package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * When the [PumpStation.setMaxConsecutiveSamePath] loop guard trips, the
 * harness halts with [PumpStationExitReason.LoopGuardTripped] instead of
 * invoking the configured intervention agent. The intervention agent is
 * never called on the guard-trip path — guard fire + intervention = the
 * loop was already happening on the prior intervention, so a second
 * invocation is wasted work.
 *
 * Each test pins one configuration variant of the halt contract.
 */
class PumpStationInterventionAgentTest
{
    /**
     * Counting P2PInterface used to verify whether the intervention call site
     * actually invokes the configured agent. Records every executeLocal call.
     */
    private class CountingInterventionAgent(
        private val scriptedResponse: MultimodalContent = MultimodalContent(text = "intervention-result")
    ) : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        val executeCallCount = java.util.concurrent.atomic.AtomicInteger(0)
        val lastInput = java.util.concurrent.atomic.AtomicReference<MultimodalContent>(null)

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            executeCallCount.incrementAndGet()
            lastInput.set(content)
            return scriptedResponse
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}
    }

    private fun loopablePath(name: String, counter: IntArray): PathObject
    {
        return PathObject().apply {
            pathName = name
            pathDescription = "Test path that loops"
            setExecutionFunction { content, _, _, _ ->
                counter[0] = counter[0] + 1
                MultimodalContent(text = "loop iteration ${counter[0]}")
            }
        }
    }

    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    /**
     * Builder-only configuration: the loop guard halts the harness and the
     * builder-supplied agent is not invoked.
     */
    @Test
    fun loopGuardHalt_skipsInterventionAgentWhenBuilderSet()
    {
        runBlocking {
            val agent = CountingInterventionAgent()
            val pathCounter = IntArray(1)
            val events = mutableListOf<PumpStationEvent>()
            val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }

            val station = buildTestStation(maxHarnessTurns = 5)
                .setDispatchAgent(dispatchAlwaysPicks("loop"))
                .setMaxConsecutiveSamePath(1)
                .setInterventionAgentBuilderFunction { _ -> agent }
                .setEventObserver(observer)
            station.addPath(loopablePath("loop", pathCounter))

            station.executeLocal(MultimodalContent(text = "task"))

            assertEquals(
                PumpStationExitReason.LoopGuardTripped,
                station.getTaskState().exitReason,
                "Defect 19: harness must exit via PumpStationExitReason.LoopGuardTripped."
            )
            assertEquals(
                0, agent.executeCallCount.get(),
                "Defect 19: intervention agent must NOT be invoked after the policy change."
            )
            val tripped = events.filterIsInstance<LoopGuardTripped>()
            assertTrue(tripped.isNotEmpty(), "LoopGuardTripped must still be emitted so traces surface the halt.")
        }
    }

    /**
     * Both field and builder set: the halt contract wins over both. Neither
     * agent is invoked.
     */
    @Test
    fun loopGuardHalt_skipsInterventionAgentWhenBothSet()
    {
        runBlocking {
            val pathCounter = IntArray(1)
            val fieldAgent = CountingInterventionAgent(scriptedResponse = MultimodalContent(text = "from-field"))
            val builderAgent = CountingInterventionAgent(scriptedResponse = MultimodalContent(text = "from-builder"))
            val events = mutableListOf<PumpStationEvent>()
            val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }

            val station = buildTestStation(maxHarnessTurns = 5)
                .setDispatchAgent(dispatchAlwaysPicks("loop"))
                .setMaxConsecutiveSamePath(1)
                .setInterventionAgent(fieldAgent)
                .setInterventionAgentBuilderFunction { _ -> builderAgent }
                .setEventObserver(observer)
            station.addPath(loopablePath("loop", pathCounter))

            station.executeLocal(MultimodalContent(text = "task"))

            assertEquals(
                PumpStationExitReason.LoopGuardTripped,
                station.getTaskState().exitReason,
                "Defect 19: halt wins over intervention when both field and builder are set."
            )
            assertEquals(
                0, fieldAgent.executeCallCount.get(),
                "Defect 19: field-supplied intervention agent must not be invoked."
            )
            assertEquals(
                0, builderAgent.executeCallCount.get(),
                "Defect 19: builder-supplied intervention agent must not be invoked."
            )
        }
    }

    /**
     * No intervention configured: the harness still halts via
     * [PumpStationExitReason.LoopGuardTripped] and emits
     * [LoopGuardTripped]. The harness must not silently reach
     * [PumpStationExitReason.JudgeComplete] on a loop-guard trip.
     */
    @Test
    fun loopGuardHalt_emitsLoopGuardTrippedWhenNoAgentConfigured()
    {
        runBlocking {
            val pathCounter = IntArray(1)
            val events = mutableListOf<PumpStationEvent>()
            val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }

            val station = buildTestStation(maxHarnessTurns = 5)
                .setDispatchAgent(dispatchAlwaysPicks("loop"))
                .setMaxConsecutiveSamePath(1)
                .setEventObserver(observer)
            station.addPath(loopablePath("loop", pathCounter))

            station.executeLocal(MultimodalContent(text = "task"))

            assertEquals(
                PumpStationExitReason.LoopGuardTripped,
                station.getTaskState().exitReason,
                "Defect 19: harness must halt via PumpStationExitReason.LoopGuardTripped even with no intervention agent."
            )
            val tripped = events.filterIsInstance<LoopGuardTripped>()
            assertTrue(tripped.isNotEmpty(), "LoopGuardTripped must still fire when no intervention agent is set.")
            // No intervention-related events should fire — the harness halted.
            assertNotEquals(
                PumpStationExitReason.JudgeComplete,
                station.getTaskState().exitReason,
                "Defect 19: harness must NOT silently JudgeComplete on a loop-guard trip."
            )
        }
    }
}