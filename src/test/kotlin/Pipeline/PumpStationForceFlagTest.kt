package com.TTT.Pipeline

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceEventType
import com.TTT.Enums.PumpStationForceTarget
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the developer-triggered force-flag mechanism.
 *
 * A force flag lets a developer activate a harness subsystem that would otherwise be
 * dormant because its interval / threshold gate did not fire. The registry itself is
 * covered here first; the gate wiring is covered by the later cases in this file.
 */
class PumpStationForceFlagTest
{
    //=====================================Force registry=======================================================

    @Test
    fun `consume returns true once after a single arm`() {
        val service = PumpStationForceService()
        service.arm(PumpStationForceTarget.Lorebook)

        assertTrue(service.consume(PumpStationForceTarget.Lorebook), "the armed force must be consumed")
        assertFalse(service.consume(PumpStationForceTarget.Lorebook), "a force is one-shot")
    }

    @Test
    fun `isArmed reflects a pending force`() {
        val service = PumpStationForceService()
        assertFalse(service.isArmed(PumpStationForceTarget.Compaction), "nothing is armed initially")

        service.arm(PumpStationForceTarget.Compaction)
        assertTrue(service.isArmed(PumpStationForceTarget.Compaction), "an armed force is visible")

        service.consume(PumpStationForceTarget.Compaction)
        assertFalse(service.isArmed(PumpStationForceTarget.Compaction), "a consumed force is not armed")
    }

    @Test
    fun `clear disarms the target`() {
        val service = PumpStationForceService()
        service.arm(PumpStationForceTarget.Summary)
        assertTrue(service.isArmed(PumpStationForceTarget.Summary), "precondition: the target is armed")

        service.clear(PumpStationForceTarget.Summary)

        assertFalse(service.isArmed(PumpStationForceTarget.Summary), "clear must disarm the target")
    }

    @Test
    fun `repeated arms queue multiple firings`() {
        val service = PumpStationForceService()
        service.arm(PumpStationForceTarget.Lorebook)
        service.arm(PumpStationForceTarget.Lorebook)

        assertTrue(service.consume(PumpStationForceTarget.Lorebook), "first queued force fires")
        assertTrue(service.consume(PumpStationForceTarget.Lorebook), "second queued force fires")
        assertFalse(service.consume(PumpStationForceTarget.Lorebook), "no third force was queued")
    }

    //=====================================Harness helpers=========================================================

    private fun notDoneJudge(): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    /**
     * P2P agent whose [executeLocal] blocks until [gate] is completed.
     *
     * The call is recorded on entry, so [callLog] proves the agent was invoked, while
     * [completed] proves it ran to the end. The gate is opened by the test only after
     * the harness would otherwise have finished, which turns "the harness awaited the
     * forced subsystem" into a deterministic assertion instead of a scheduling race.
     */
    private class GatedAgent(
        private val gate: CompletableDeferred<Unit>
    ) : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        val callLog = mutableListOf<MultimodalContent>()

        /** True once the gated call has run to completion. */
        @Volatile var completed = false

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            callLog.add(content)
            gate.await()
            completed = true
            return content
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

    //=====================================Station API============================================================

    @Test
    fun `force arms the target on the station`() {
        val station = PumpStation()
        assertFalse(station.isForceArmed(PumpStationForceTarget.Lorebook), "nothing is armed initially")

        station.force(PumpStationForceTarget.Lorebook)

        assertTrue(station.isForceArmed(PumpStationForceTarget.Lorebook), "the force is armed")
    }

    @Test
    fun `force returns the station for chaining`() {
        val station = PumpStation()

        val returned = station.force(PumpStationForceTarget.Compaction)

        assertSame(station, returned, "force must return this for chaining")
    }

    @Test
    fun `clearForces disarms every target`() {
        val station = PumpStation()
        station.force(PumpStationForceTarget.Lorebook)
        station.force(PumpStationForceTarget.Compaction)

        station.clearForces()

        assertFalse(station.isForceArmed(PumpStationForceTarget.Lorebook), "lorebook must be disarmed")
        assertFalse(station.isForceArmed(PumpStationForceTarget.Compaction), "compaction must be disarmed")
    }

    //=====================================Lorebook gate===========================================================

    @Test
    fun `force activates the lorebook agent when the memory gate is closed`() = runBlocking {
        val lorebook = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setLorebookAgent(lorebook)
            .setBackgroundTurnInterval(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(lorebook.callLog.isNotEmpty(), "the lorebook agent must run under the force")
    }

    //=====================================Summary gate============================================================

    @Test
    fun `force activates the summary agent when the memory gate is closed`() = runBlocking {
        val summary = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setBackgroundTurnInterval(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Summary)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(summary.callLog.isNotEmpty(), "the summary agent must run under the force")
    }

    @Test
    fun `a forced memory agent is awaited before the harness advances`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val summary = GatedAgent(gate)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setBackgroundTurnInterval(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Summary)

        // The gate opens only after the harness would otherwise have finished, so the
        // agent can only run to completion if the harness awaited it.
        val opener = launch { delay(200); gate.complete(Unit) }
        station.executeLocal(MultimodalContent(text = "task"))
        opener.join()

        assertTrue(summary.callLog.isNotEmpty(), "the forced summary agent must have been called")
        assertTrue(summary.completed, "the forced summary agent must complete before the harness advances")
    }

    @Test
    fun `a forced memory agent is awaited even when the memory timeout is zero`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val summary = GatedAgent(gate)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setBackgroundTurnInterval(0)
            .setMemoryUpdateTimeoutMs(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Summary)

        val opener = launch { delay(200); gate.complete(Unit) }
        station.executeLocal(MultimodalContent(text = "task"))
        opener.join()

        assertTrue(summary.completed, "a zero memory timeout must not skip the forced await")
    }

    //=====================================Compaction gate=========================================================

    @Test
    fun `force attempts compaction below the fill threshold`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        // A short scripted response keeps the compaction attempt from inflating.
        val summary = MockP2PAgent(script = List(5) { MultimodalContent(text = "compact") })
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Compaction)

        station.executeLocal(MultimodalContent(text = "task"))

        val started = synchronized(events) { events.filterIsInstance<CompactionStarted>() }
        assertTrue(started.isNotEmpty(), "a forced compaction must attempt below the fill threshold")
    }

    //=====================================Health gate===========================================================

    @Test
    fun `force activates the health agent when its interval is not due`() = runBlocking {
        val health = MockP2PAgent(script = listOf(
            MultimodalContent(text = """{"status":"Healthy","warnings":[],"suggestions":[],"metrics":{}}""")
        ))
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setHealthAgent(health)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.HealthCheck)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(health.callLog.isNotEmpty(), "the health agent must run under the force")
    }

    //=====================================Harness agent slot gates================================================

    @Test
    fun `force activates the foreground harness agent slots`() = runBlocking {
        val slot = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .addHarnessAgent(slot, PumpStationConcurrencyMode.Blocking)
            .setForegroundTurnInterval(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.ForegroundAgents)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(slot.callLog.isNotEmpty(), "the foreground slot agent must run under the force")
    }

    @Test
    fun `force activates the background harness agent slots`() = runBlocking {
        val slot = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .addHarnessAgent(slot, PumpStationConcurrencyMode.Async)
            .setBackgroundTurnInterval(0)
            .setAsyncJobGracePeriodMs(5_000)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.BackgroundAgents)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(slot.callLog.isNotEmpty(), "the background slot agent must run under the force")
    }

    @Test
    fun `a forced background slot reports the background phase`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val slot = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .addHarnessAgent(slot, PumpStationConcurrencyMode.Async)
            .setBackgroundTurnInterval(0)
            .setAsyncJobGracePeriodMs(5_000)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.BackgroundAgents)

        station.executeLocal(MultimodalContent(text = "task"))

        val applied = synchronized(events) {
            events.filterIsInstance<ForceApplied>().first { it.target == PumpStationForceTarget.BackgroundAgents }
        }
        assertEquals(
            PumpStationPhase.BackgroundAgents,
            applied.phase,
            "a forced background slot must report the background phase"
        )
    }

    @Test
    fun `a forced foreground slot reports the foreground phase`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val slot = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .addHarnessAgent(slot, PumpStationConcurrencyMode.Blocking)
            .setForegroundTurnInterval(0)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.ForegroundAgents)

        station.executeLocal(MultimodalContent(text = "task"))

        val applied = synchronized(events) {
            events.filterIsInstance<ForceApplied>().first { it.target == PumpStationForceTarget.ForegroundAgents }
        }
        assertEquals(
            PumpStationPhase.ForegroundAgents,
            applied.phase,
            "a forced foreground slot must report the foreground phase"
        )
    }

    @Test
    fun `a forced background slot is awaited before the harness advances`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slot = GatedAgent(gate)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .addHarnessAgent(slot, PumpStationConcurrencyMode.Async)
            .setBackgroundTurnInterval(0)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.BackgroundAgents)

        // The gate opens only after the harness would otherwise have finished, so the
        // agent can only run to completion if the harness awaited it.
        val opener = launch { delay(200); gate.complete(Unit) }
        station.executeLocal(MultimodalContent(text = "task"))
        opener.join()

        assertTrue(slot.callLog.isNotEmpty(), "the forced background slot agent must have been called")
        assertTrue(slot.completed, "the forced background slot agent must complete before the harness advances")
    }

    //=====================================Safe prune gate=========================================================

    @Test
    fun `force runs safe prune below the history size threshold`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSafePruneEnabled(true)
            .enableSafePruneStrategy(SafePruneStrategy.DeduplicateByHash)
            .setSafePruneStrategyDryRun(SafePruneStrategy.DeduplicateByHash, true)
            .setSafePruneSizeThreshold(1000)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.SafePrune)

        station.executeLocal(MultimodalContent(text = "task"))

        val pruned = synchronized(events) { events.filterIsInstance<SafePruneDryRunCompleted>() }
        assertTrue(pruned.isNotEmpty(), "a forced safe prune must run below the size threshold")
    }

    //=====================================Judge gate=============================================================

    @Test
    fun `force activates the judge in flag triggered mode`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setJudgeRunMode(PumpStationJudgeRunMode.FlagTriggered)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Judge)

        station.executeLocal(MultimodalContent(text = "task"))

        val started = synchronized(events) { events.filterIsInstance<JudgeStarted>() }
        assertTrue(started.isNotEmpty(), "a forced judge must run in FlagTriggered mode")
    }

    @Test
    fun `a forced judge vetoed by the pre-invoke gate does not report the force as applied`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setPreInvokeFunction { _, _, _ -> false }
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Judge)

        station.executeLocal(MultimodalContent(text = "task"))

        val applied = synchronized(events) { events.filterIsInstance<ForceApplied>() }
        assertTrue(applied.isEmpty(), "a judge that never ran must not report the force as applied")
    }

    @Test
    fun `force activates the judge on the first turn`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSkipJudgeOnFirstTurn(true)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Judge)

        station.executeLocal(MultimodalContent(text = "task"))

        val started = synchronized(events) { events.filterIsInstance<JudgeStarted>() }
        assertTrue(started.isNotEmpty(), "a forced judge must run on turn 0")
    }

    //=====================================Force semantics==========================================================

    @Test
    fun `a force fires once and then the subsystem is dormant again`() = runBlocking {
        val lorebook = MockP2PAgent()
        val station = buildTestStation(maxHarnessTurns = 2)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setLorebookAgent(lorebook)
            .setBackgroundTurnInterval(0)
            .setAsyncJobGracePeriodMs(5_000)
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(1, lorebook.callLog.size, "the one-shot force must fire exactly once")
        assertFalse(station.isForceArmed(PumpStationForceTarget.Lorebook), "the force must be consumed")
    }

    @Test
    fun `a force with nothing configured is consumed without crashing`() = runBlocking {
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.HealthCheck)

        station.executeLocal(MultimodalContent(text = "task"))

        assertFalse(
            station.isForceArmed(PumpStationForceTarget.HealthCheck),
            "a force with no configured subsystem must still be consumed"
        )
    }

    //=====================================Content flag=============================================================

    @Test
    fun `the content flag survives a copy`() {
        val content = MultimodalContent(text = "original")
        content.force(PumpStationForceTarget.Compaction)

        val copied = content.copy(text = "copied")

        assertTrue(
            copied.forceTargets?.contains(PumpStationForceTarget.Compaction) == true,
            "a copy must preserve the force flag"
        )
    }

    @Test
    fun `the content flag records the requested targets`() {
        val content = MultimodalContent(text = "x")
        assertNull(content.forceTargets, "no force is requested initially")

        content.force(PumpStationForceTarget.Lorebook)

        assertEquals(listOf(PumpStationForceTarget.Lorebook), content.forceTargets, "the target must be recorded")
    }

    @Test
    fun `a path result carrying a force flag arms the harness force`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        // A short scripted response keeps the compaction attempt from inflating.
        val summary = MockP2PAgent(script = List(5) { MultimodalContent(text = "compact") })
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        val path = PathObject().apply {
            pathName = "p1"
            pathDescription = "Requests a forced compaction"
            setExecutionFunction { _, _, _, _ ->
                MultimodalContent(text = "done").apply { force(PumpStationForceTarget.Compaction) }
            }
        }
        station.addPath(path)

        station.executeLocal(MultimodalContent(text = "task"))

        val started = synchronized(events) { events.filterIsInstance<CompactionStarted>() }
        assertTrue(started.isNotEmpty(), "the content force flag must arm the compaction")
    }

    //=====================================Observability==========================================================

    @Test
    fun `a forced run emits a force applied event`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setLorebookAgent(MockP2PAgent())
            .setBackgroundTurnInterval(0)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        val applied = synchronized(events) { events.filterIsInstance<ForceApplied>() }
        assertTrue(applied.isNotEmpty(), "a forced run must emit ForceApplied")
        assertEquals(PumpStationForceTarget.Lorebook, applied.first().target, "the event must carry the target")
    }

    @Test
    fun `a force with nothing configured emits a force skipped event`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.HealthCheck)

        station.executeLocal(MultimodalContent(text = "task"))

        val skipped = synchronized(events) { events.filterIsInstance<ForceSkipped>() }
        assertTrue(skipped.isNotEmpty(), "a force with nothing configured must emit ForceSkipped")
        assertTrue(skipped.first().reason.isNotBlank(), "the skipped event must carry a reason")
    }

    @Test
    fun `a forced memory agent with no memory agent configured emits a force skipped event`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        val skipped = synchronized(events) { events.filterIsInstance<ForceSkipped>() }
        assertTrue(skipped.isNotEmpty(), "a forced lorebook with no lorebook agent must emit ForceSkipped")
        assertEquals(PumpStationForceTarget.Lorebook, skipped.first().target, "the skipped event must carry the target")
    }

    @Test
    fun `the force event reaches the tracer`() = runBlocking {
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setLorebookAgent(MockP2PAgent())
            .setBackgroundTurnInterval(0)
            .enableTracing(TraceConfig(enabled = true))
        station.addPath(testPath("p1"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        val runId = station.getTaskState().runId
        val traced = PipeTracer.getAllTraces().values.flatten()
            .filter { it.metadata["runId"] == runId && it.eventType == TraceEventType.PUMP_STATION_FORCE_APPLIED }
        assertTrue(traced.isNotEmpty(), "the force event must reach the tracer")
        assertEquals(PumpStationForceTarget.Lorebook.name, traced.first().metadata["target"], "the trace must carry the target")
    }

    //=====================================DSL===============================================================

    @Test
    fun `the dsl force policy seeds a force at construction`() = runBlocking {
        val lorebook = MockP2PAgent()
        val station = pumpStation("dsl-force-${System.nanoTime()}") {
            judgeAgent = Pipeline().apply { add(ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")) }
            dispatchAgent = Pipeline().apply { add(ScriptedTestPipe(response = """{"pathName": "p1", "pathSchema": "{}"}""")) }
            maxHarnessTurns = 1
            backgroundTurnInterval = 0
            lorebookAgent = lorebook
            forcePolicy { force(PumpStationForceTarget.Lorebook) }
            path("p1") { setExecutionFunction { content, _, _, _ -> content } }
        }
        station.setAsyncJobGracePeriodMs(5_000)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(lorebook.callLog.isNotEmpty(), "the seeded force must activate the lorebook agent")
    }

    //=====================================Concurrency===========================================================

    @Test
    fun `concurrent arms are all recorded`() {
        val service = PumpStationForceService()
        val threadCount = 8
        val armsPerThread = 25
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threadCount)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.submit {
                try
                {
                    start.await()
                    repeat(armsPerThread) { service.arm(PumpStationForceTarget.Lorebook) }
                }
                finally
                {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        pool.shutdown()

        var consumed = 0
        while (service.consume(PumpStationForceTarget.Lorebook)) consumed++

        assertEquals(threadCount * armsPerThread, consumed, "every concurrent arm must be recorded")
    }
}
