package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import com.TTT.Context.ConverseHistory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the intervention-agent call site at PumpStation.kt:2745
 * (inside `invokePath`'s loop-guard block). The v3 call site consulted only the
 * `interventionAgent` field, ignoring the `interventionAgentBuilderFunction`. The
 * field's KDoc (PumpStation.kt:882-885) explicitly documents the builder as the
 * override: "Optional builder function for the intervention agent that overrides
 * [interventionAgent] at runtime each time it would be called."
 *
 * The result was a silent no-op when the developer used the recommended
 * thread-safe pattern (`setInterventionAgentBuilderFunction { harness -> agent }`)
 * without also calling `setInterventionAgent(...)`. The harness would emit
 * `InterventionStarted` and `InterventionCompleted` events with `result = null`
 * and the developer's agent would never execute.
 *
 * Tests pin the corrected contract:
 *  - builder function alone → agent executes (builder wins).
 *  - both set → builder wins (per KDoc "overrides ... at runtime each time it would be called").
 *  - neither set → events still emit with null result (preserves trace continuity).
 */
class PumpStationInterventionAgentTest
{
    /**
     * Counting P2PInterface used to verify that the intervention call site actually
     * invokes the configured agent. Records every [executeLocal] call and returns a
     * configurable scripted response.
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

    /**
     * Build a path whose `executionFunction` returns plain content (no exit signal).
     * When `maxConsecutiveSamePath = 1` and the dispatch keeps selecting this path,
     * the loop guard at PumpStation.kt:2711-2758 fires and the intervention agent
     * is invoked.
     */
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

    /**
     * Helper: build a dispatch Pipeline that always picks [pathName]. Mirrors the
     * pattern used in PumpStationFlagTriggeredJudgeTest and PumpStationNoExitSignalWarningTest.
     */
    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    /**
     * Run a configured harness to completion, capturing all events. Returns the
     * unique-by-(turnIndex, timestamp) event list for assertion.
     */
    private fun runAndCapture(
        station: PumpStation,
        events: MutableList<PumpStationEvent>
    )
    {
        runBlocking {
            station.executeLocal(MultimodalContent(text = "task"))
        }
        // Drain background events to surface any completed-event payload emitted after executeLocal returns.
        synchronized(events) { /* noop drain */ }
    }

    /**
     * Helper: deduplicate the harness's double-emitted events (each event is delivered
     * to the observer twice — once at emit, once at finalization drain) by (turnIndex,
     * timestamp) so the test sees the logical stream.
     */
    private fun <T : PumpStationEvent> uniqueBy(events: List<T>): List<T>
    {
        val seen = HashSet<Pair<Int, Long>>()
        val out = mutableListOf<T>()
        for (e in events)
        {
            val key = e.turnIndex to e.timestamp
            if (seen.add(key)) out.add(e)
        }
        return out
    }

    /**
     * Case 1 (red before fix, green after fix): developer uses the recommended
     * thread-safe pattern — sets only the builder function. The intervention agent
     * must be invoked when the loop guard fires.
     */
    @Test
    fun interventionBuilderFunction_firesWhenOnlyBuilderIsSet()
    {
        runBlocking {
            val counter = IntArray(1)
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

            // The agent must have been called when the loop guard fired (after the
            // second consecutive path execution on turn 2).
            assertTrue(
                agent.executeCallCount.get() >= 1,
                "Expected intervention builder function to be invoked at least once when loop guard fires; " +
                    "got executeCallCount=${agent.executeCallCount.get()}. " +
                    "Bug: call site at PumpStation.kt:2745 only consults interventionAgent field, " +
                    "ignoring interventionAgentBuilderFunction."
            )

            // The trace must contain both InterventionStarted and InterventionCompleted
            // events with a non-null result payload.
            val started = uniqueBy(synchronized(events) { events.filterIsInstance<InterventionStarted>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<InterventionCompleted>() })
            assertTrue(started.isNotEmpty(), "Expected at least one InterventionStarted event; got 0")
            assertTrue(completed.isNotEmpty(), "Expected at least one InterventionCompleted event; got 0")
            assertTrue(
                completed.any { it.result != null },
                "Expected InterventionCompleted to carry a non-null result payload from the agent; got ${completed.map { it.result }}"
            )
        }
    }

    /**
     * Case 2 (positive control — pins the KDoc "overrides" semantics): both the
     * field and the builder function are set. Per the KDoc the builder overrides at
     * runtime, so the BUILDER's agent must be invoked (not the field's).
     */
    @Test
    fun interventionBuilderFunction_overridesFieldWhenBothSet()
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

            assertTrue(
                builderAgent.executeCallCount.get() >= 1,
                "Expected builder-supplied agent to be invoked when both field and builder are set " +
                    "(per KDoc: 'builder function for the intervention agent that overrides " +
                    "[interventionAgent] at runtime each time it would be called'); " +
                    "got builderCalls=${builderAgent.executeCallCount.get()}, fieldCalls=${fieldAgent.executeCallCount.get()}"
            )
            assertEquals(
                0, fieldAgent.executeCallCount.get(),
                "Expected field-supplied agent to NOT be invoked when builder is set; " +
                    "got fieldCalls=${fieldAgent.executeCallCount.get()}"
            )
        }
    }

    /**
     * Case 3 (negative control — preserves trace continuity): neither field nor
     * builder is set. The harness must still emit InterventionStarted and
     * InterventionCompleted events with null result (preserves the current
     * "always emit" behavior so trace consumers see the full event stream).
     */
    @Test
    fun interventionNeitherSet_emitsEventsWithNullResult()
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

            val started = uniqueBy(synchronized(events) { events.filterIsInstance<InterventionStarted>() })
            val completed = uniqueBy(synchronized(events) { events.filterIsInstance<InterventionCompleted>() })
            assertTrue(started.isNotEmpty(), "Expected InterventionStarted to fire even when no agent is configured; got 0")
            assertTrue(completed.isNotEmpty(), "Expected InterventionCompleted to fire even when no agent is configured; got 0")
            assertTrue(
                completed.all { it.result == null },
                "Expected InterventionCompleted.result to be null when no agent is configured; " +
                    "got ${completed.map { it.result }}"
            )
        }
    }
}